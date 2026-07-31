# CM4AI Pipeline — vanilla Cell Maps flow

A second Nextflow pipeline that runs the **stock idekerlab Cell Maps pipeline
end to end**, starting from the two official data downloaders. Unlike the
`../nextflow/` pipeline, this one contains **none** of the CM4AI custom scripts —
the downloaders produce the input RO-Crates directly, and every stage is an
unmodified `cellmaps_*` tool.

## What changed vs. `../nextflow/`

| `../nextflow/` (CM4AI custom)                        | this pipeline (stock Cell Maps)              |
| ---------------------------------------------------- | -------------------------------------------- |
| `convert_manifest.py` (HPA manifest → node attrs)    | **`cellmaps_imagedownloader`**               |
| `secms_to_edgelist.py` (SEC-MS → PPI edge list)      | **`cellmaps_ppidownloader`**                 |
| `split_combined_embeddings.py` (split/filter genes)  | *dropped* — downloaders emit compatible sets |
| `visualize_hierarchy.py` (MuSIC-style figure)        | *dropped* — pipeline ends at the hierarchy   |
| `cellmaps_image_embedding` / `cellmaps_ppi_embedding` / `cellmaps_coembedding` / `cellmaps_generate_hierarchy` | **unchanged** |

## Stages

```
1. IMAGE_DOWNLOAD   HPA IF images        -> image RO-Crate   (cellmaps_imagedownloader 0.3.0)
2. PPI_DOWNLOAD     AP-MS edge/bait list -> PPI RO-Crate      (cellmaps_ppidownloader 0.2.2)
3. IMAGE_EMBEDDING  image crate  -> 1024-d embeddings         (cellmaps_image_embedding 0.3.3)
4. PPI_EMBEDDING    PPI crate    -> node2vec embeddings       (cellmaps_ppi_embedding 0.4.3)
5. COEMBEDDING      fuse image + PPI (MUSE)                    (cellmaps_coembedding 1.5.0)
6. HIERARCHY        HiDeF hierarchical cell map               (cellmaps_generate_hierarchy 0.2.5)
```

Steps 1–2 run in parallel (independent branches) and converge at COEMBEDDING.

## Provenance

Only the **two downloaders** take `--provenance`. Every downstream step reads
its provenance from the RO-Crate it is handed as input (the cellmaps runners
fall back to `get_merged_rocrate_provenance_attrs()` when `--provenance` is
omitted and `inputdir` contains `ro-crate-metadata.json`). As in `../nextflow/`,
the **nf-fairscape** plugin observes the whole run and writes one EVI RO-Crate
to `${outdir}/ro-crate-metadata.json`.

### AI-Readiness

The crate scores **28/28 (100%)** on the FAIRSCAPE AI-Ready rubric
(`ai_ready_score.json`, rendered in `ro-crate-datasheet.html`). Getting there
took three things, all configured in the `fairscape { }` block of
`nextflow.config`:

| Setting | What it fixes |
| ------- | ------------- |
| `metadata = [ 'rai:…', ethicalReview, confidentialityLevel, … ]` | Root-level RAI/ethics/governance properties. These are the 8 criteria no tool can infer — bias, data quality, fit for purpose, ethical acquisition/management, security, maintenance plan, governance. |
| `expandDirectories = true` + `checksums = true` + `contentSizes = true` | Each step publishes a *directory*, which the crate used to describe as one opaque Dataset. Now every data file inside gets its own Dataset with a size, format, `md5` and `generatedBy` edge, and the crate reports its real 945 MB rather than 0.1 MB. |
| `schemas = true` | An `EVI:Schema` per csv/tsv, inferred by the plugin's port of `fairscape-cli schema infer`. Fills the "standards" criterion. |

All four plugin options **default to off** — they each cost extra I/O, so nothing
is forced on other pipelines. This config turns them on deliberately.

Two further plugin steps run by default (they read only the crate JSON, like the
datasheet and provenance graph, and are switched off with `linkInverses = false`
/ `linkml = false`):

- **inverse linking** completes every `owl:inverseOf` pair EVI declares, so each
  step's `generated` lists everything it produced rather than just its output
  directory — `HIERARCHY` goes from 1 entry to 24 — and datasets/software gain
  `datasetUsedBy`/`softwareUsedBy`.
- **`ro-crate-linkml.yaml`** is the crate root translated into a D4D (Datasheets
  for Datasets) document. Most of its content comes from the `fairscape.metadata`
  block above: `rai:dataBiases` → `known_biases`, `rai:dataUseCases` →
  `purposes`/`tasks`/`intended_uses`, `prohibitedUses` → `prohibited_uses`, and
  so on.

Concretely, the crate went from 28 `@graph` entries to **117**: 73 Datasets
(68 with provenance), 27 Schemas, 8 Software, 7 Computations.

**Why the datasheet says "68 / 74", not "74 / 74".** The composition table counts
a Dataset as having provenance when it carries `generatedBy`. The six without it
are the five raw workflow inputs (`samples.csv`, `unique.csv`, `edgelist.tsv`,
`baitlist.tsv`, `provenance.json`) — nothing in the run generated them, so an
empty `generatedBy` is the correct statement — plus the root crate entity itself,
which the table counts in its denominator. Every file the pipeline *produced* has
provenance. Before the changes above this read **6 / 12**, because only the six
published directories were described at all.

To describe the ~1,860 individual HPA JPEGs as well, drop `expandPatterns` from
`nextflow.config` (`expandPatterns = []` describes every file). That gives a
~4,000-entity crate and a correspondingly large datasheet; the default keeps the
tabular/JSON/text data products and lets the image directories stand for their
contents.

## Inputs

Defaults point at the **U2OS / Bioplex example inputs bundled inside the
downloader packages**, so it reproduces the canonical Cell Maps demo with no
extra data:

| param          | default (under `base_dir`)                             | meaning                     |
| -------------- | ------------------------------------------------------ | --------------------------- |
| `--samples`    | `cellmaps_imagedownloader/examples/samples.csv`        | HPA IF images to download   |
| `--unique`     | `cellmaps_imagedownloader/examples/unique.csv`         | best antibodies per gene    |
| `--edgelist`   | `cellmaps_ppidownloader/examples/edgelist.tsv`         | AP-MS edges                 |
| `--baitlist`   | `cellmaps_ppidownloader/examples/baitlist.tsv`         | AP-MS baits                 |
| `--provenance` | `cellmaps_imagedownloader/examples/provenance.json`    | describes all four inputs   |
| `--cell_line`  | `U2OS`                                                 | HPA cell line to fetch      |

To run on **different data**, point those params at your own files (a `samples`
CSV + `unique` CSV in HPA format, and an `edgelist` + `baitlist` TSV in Bioplex
format) and supply a matching `provenance.json` — no edits to `main.nf` needed.

## Environment (`cellmaps` conda env)

Runs in a conda env named `cellmaps` (`params.python = 'conda run -n cellmaps python'`)
holding all six tools. Building one takes:

```bash
conda create -y -n cellmaps python=3.10
ENV=$(conda run -n cellmaps python -c 'import sys; print(sys.prefix)')
# install the two downloaders editable, so imports resolve from Nextflow's
# per-task work dir (a cwd-relative namespace package is not enough):
conda run -n cellmaps pip install --no-deps -e ./cellmaps_imagedownloader -e ./cellmaps_ppidownloader
conda run -n cellmaps pip install mygene
# pin numpy<2 and scipy<1.15 INTO the env (see "Incompatibilities" below):
$ENV/bin/python -m pip install --ignore-installed --no-deps numpy==1.26.4 scipy==1.13.1
# fairscape-cli has to be reachable at the env-adjacent path the tools shell out to:
$ENV/bin/python -m pip install --force-reinstall --no-deps fairscape-cli==1.2.4
```

If user-site packages shadow the env (Python searches `~/.local` first in some setups), set
`params.env_site_packages` to `$ENV/lib/python3.10/site-packages` so the env's numpy/scipy
win; set `params.extra_ld_library_path` if torch can't find its CUDA libs.

### Incompatibilities that were fixed

| Symptom | Cause | Fix |
| --- | --- | --- |
| `ModuleNotFoundError: cellmaps_imagedownloader` | downloaders not installed (only a cwd namespace-package illusion) | `pip install -e` both into `cellmaps` |
| `numpy.core.multiarray failed to import` (cv2) | `~/.local` numpy 2.x shadows env; opencv built for numpy 1.x | env `numpy==1.26.4` + `PYTHONPATH` front |
| HiDeF `'numpy.float64' has no attribute '_variable'` | scipy ≥1.15 exports `abs`, hijacked by HiDeF's `from scipy.stats import *` | env `scipy==1.13.1` |
| `can't open .../bin/fairscape-cli` | tools call the python-adjacent CLI path | install `fairscape-cli` into env bin |
| coembedding `no overlapping embeddings` | stock `cellmaps_ppidownloader` writes `ppi_edgelist.tsv` with **CRLF**; node2vec then glues the trailing `\r` onto gene names (`"MED19\r"`) so they never match the clean image genes | PPI_DOWNLOAD strips CRLF→LF from the crate's `*.tsv` before node2vec (see main.nf) |

## The stock example DOES overlap (CRLF was the real bug)

The bundled downloader examples ARE the official `cellmaps_pipeline` matched example
(HPA U2OS IF images + BioPlex AP-MS): the image `gene_names` and the AP-MS edgelist
symbols overlap by **~166/168 genes**. Coembedding failed only because the stock
`cellmaps_ppidownloader` emits its intermediate `ppi_edgelist.tsv` with **CRLF** line
endings; node2vec keeps the trailing `\r` on each line's second gene, so PPI embedding
node names come out as `"GENE\r"` and match nothing. Stripping CRLF in PPI_DOWNLOAD
takes the coembedding overlap from **0 → 163** — verified directly on the outputs.

So the defaults run the real stock matched example. `smoke-data/` (a synthetic PPI
network generated by `make_smoke_ppi.py` over the image genes) remains only as an
optional fully-offline fixture — pass `--edgelist=smoke-data/edgelist.tsv
--baitlist=smoke-data/baitlist.tsv` to use it. For your own study, override
`--edgelist`/`--baitlist`/`--samples`/`--unique` with matched data for the same cells.

## Run

```bash
# out-of-the-box end-to-end demo (bundled U2OS images + smoke-data PPI)
nextflow run main.nf \
  -work-dir /path/to/cellmaps-run/work \
  --outdir  /path/to/cellmaps-run/results

# fast offline-ish smoke test (validated: all 6 steps + RO-Crate)
nextflow run main.nf -resume \
  -work-dir /path/to/cellmaps-run/work \
  --outdir  /path/to/cellmaps-run/results_fake \
  --image_download_args='--fake_images' \
  --image_embedding_args='--fake_embedder --dimensions 16' \
  --ppi_embedding_args='--dimensions 16' \
  --coembedding_args='--latent_dimension 16 --n_epochs 5 --k 6'
```

> **Nextflow CLI gotcha:** a param value that starts with `--` (e.g. `--fake_images`)
> MUST use the `--param=value` form. `--image_download_args '--fake_images'` is
> mis-parsed (the value becomes a boolean `true`); `--image_download_args='--fake_images'`
> is correct.

### Notes

- **Network access** is needed: `IMAGE_DOWNLOAD` fetches from HPA and (once) a
  ~697 MB `proteinatlas.xml.gz` reference — this is cached at
  `params.proteinatlasxml` so reruns skip it; `PPI_DOWNLOAD` calls `mygene.info`
  (which occasionally 502s — the two download steps retry up to 4×);
  `IMAGE_EMBEDDING` auto-downloads a ~66 MB DenseNet model unless `--fake_embedder`.
- IMAGE_DOWNLOAD is the slow step: it registers each of ~1,900 image files into its
  RO-Crate via a separate `fairscape-cli` subprocess (~10–15 min), same cost on a
  real run.
- Requires Nextflow ≥ 25.10 and the locally-installed `nf-fairscape@0.1.0` plugin.

### Validated result (full run, real data)

All 6 steps ✔ on a full run → `ro-crate-metadata.json`
with **117 `@graph` entries** (7 Computations, 8 Software, 73 Datasets, 27 Schemas,
root + descriptor), 945.5 MB of described payload, plus `provenance-graph.html`,
`ro-crate-datasheet.html` and `ro-crate-linkml.yaml`. **AI-Ready score 28/28 (100%)**,
up from 17/28 (61%).

Checked against the reference implementations:

```bash
# crate validates against the fairscape_models pydantic schema
PYTHONPATH=/path/to/fairscape_models python3 \
  nf-fairscape-test/validate_crate.py \
  /path/to/cellmaps-run/results/ro-crate-metadata.json

# all 27 inferred schemas match `fairscape-cli schema infer` column for column
# ro-crate-linkml.yaml is byte-identical to `fairscape build linkml`
# the inverse-linked graph is identical to `fairscape augment link-inverses`
```

Re-running is cheap: `-resume` against the same `-work-dir` reuses the cached
IMAGE_DOWNLOAD and IMAGE_EMBEDDING steps (the expensive ones), so the crate is
rebuilt in ~90 s without re-downloading anything.
