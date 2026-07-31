# CM4AI Pipeline — Nextflow

A faithful port of [`../track_pipelinev2.sh`](../track_pipelinev2.sh) to Nextflow DSL2.

## What changed from the bash version

The bash pipeline wrapped **every** step in `fairscape-cli track ... -- <command>`, producing
one RO-Crate per step. **That wrapper is gone.** Provenance is now emitted automatically by the
[`nf-fairscape`](../..) Nextflow plugin (enabled in
`nextflow.config`). The plugin observes the whole task DAG and, when the run succeeds, writes a
**single EVI RO-Crate** for the entire workflow to `${params.outdir}/ro-crate-metadata.json`:

- each process execution → an `EVI:Computation` (linked to a run-level Computation via `isPartOf`)
- every file that flows through a channel or is published → an `EVI:Dataset` with bidirectional
  `generated` / `generatedBy` edges
- the workflow script and Nextflow engine → `EVI:Software`

So the processes in `main.nf` are just the plain commands — no tracking boilerplate. The old
`--start-clean` flags were a `fairscape-cli` concern and are dropped.

## Files

| File | Purpose |
| --- | --- |
| `main.nf` | The 8-step DSL2 workflow |
| `nextflow.config` | Params + enables the `nf-fairscape` plugin (replaces `fairscape-cli track`) |
| `mini-example/make_mini_cohort.py` | Builds a tiny test cohort from the full dataset |
| `mini-example/mini.config` | Runs the full pipeline on that cohort as a fast smoke test |

## Step → process mapping

| Bash step | Process | Underlying command |
| --- | --- | --- |
| 1. Manifest conversion | `CONVERT_MANIFEST` | `images/convert_manifest.py` |
| 2. Image embedding | `IMAGE_EMBEDDING` | `cellmaps_image_embeddingcmd.py` (DenseNet) |
| 3. SEC-MS → edgelist | `SECMS_TO_EDGELIST` | `sec-ms/secms_to_edgelist.py` (cosine sim) |
| 4. PPI embedding | `PPI_EMBEDDING` | `cellmaps_ppi_embeddingcmd.py` (node2vec) |
| 5. Split & filter | `SPLIT_FILTER` | `split_combined_embeddings.py` |
| 6. Coembedding | `COEMBEDDING` | `cellmaps_coembeddingcmd.py` (MUSE) |
| 7. Hierarchy | `HIERARCHY` | `cellmaps_generate_hierarchycmd.py` (HiDeF) |
| 8. Visualization | `VISUALIZE` | `visualize_hierarchy.py` |

Dataflow (the provenance chain re-emerges automatically from shared file ARKs):

```
CONVERT_MANIFEST → IMAGE_EMBEDDING ┐
                                   ├→ SPLIT_FILTER → COEMBEDDING → HIERARCHY → VISUALIZE
SECMS_TO_EDGELIST → PPI_EMBEDDING  ┘
```

## Prerequisites

- **Nextflow ≥ 25.10** (`~/.local/bin/nextflow`, currently 25.10.4).
- **nf-fairscape plugin** installed at `~/.nextflow/plugins/nf-fairscape-0.1.0`
  (`make install` in the plugin repo). The config pins `nf-fairscape@0.1.0` so it resolves offline.
- A Python env where the `cellmaps_*` packages and ML deps (pandas, sklearn, torch, …) import.
  Per project convention that is the `subcell` conda env — `params.python` defaults to
  `conda run -n subcell python`. Override with `--python "${params.base_dir}/.venv/bin/python"`
  if they live elsewhere.

### Environment fixes needed on this machine

Two gaps in the `subcell` env had to be patched for the cellmaps steps to run (found while
running the mini cohort):

1. **torch can't find `libnvJitLink.so.12`.** subcell's torch (2.4.1+cu121) is missing that CUDA
   lib; a copy usually ships in the `nvidia-nvjitlink-cu12` wheel. Point
   `params.extra_ld_library_path` in `nextflow.config` at the directory holding it and it is
   exported as `LD_LIBRARY_PATH` to every task. Leave it `''` if your torch resolves its own.

2. **HiDeF's `hidef_finder.py` isn't in `subcell/bin`.** `cellmaps_generate_hierarchy` looks for it
   next to the python binary. One-time symlink (additive, reversible):
   ```bash
   ln -s "$(command -v hidef_finder.py)" "$(dirname "$(conda run -n subcell which python)")"/
   ```

Note: the cellmaps tools also log their **own** `fairscape-cli ... failed / No such file` errors —
those are harmless. That is cellmaps' internal per-tool provenance (which we are replacing);
it's caught with `raise_on_error=False` and does not affect the run or the nf-fairscape crate.

## Run the full pipeline

```bash
cd nextflow
nextflow run main.nf                       # untreated (default)
nextflow run main.nf --treatment Paclitaxel
nextflow run main.nf --treatment Vorinostat
```

Outputs land under `${base_dir}/nf_results_<treatment>/`, with the run's RO-Crate at
`nf_results_<treatment>/ro-crate-metadata.json`.

## Run the mini test cohort

A tiny end-to-end run (10 genes, ~50 proteins, `--fake_embedder`, small dims/epochs) that
exercises all 8 steps plus provenance in minutes:

```bash
cd nextflow/mini-example
python make_mini_cohort.py                 # build data/ once (~20 MB)
nextflow run ../main.nf -c mini.config     # full pipeline on the small cohort
```

`mini.config` inherits `../nextflow.config`, then overrides only the **data** locations
(`images_dir`, `secms_reports_dir`, `provenance`) and shrinks the ML steps — the scripts still
come from the full project. Results and the mini RO-Crate go to `mini-example/results/`.

Cohort-specific tuning baked into `mini.config` (so the tiny inputs don't break the ML steps):
- image step uses `--fake_embedder`; **PPI uses real node2vec** (its `--fake_embedder` path needs a
  `ppi_gene_node_attributes.tsv` that `secms_to_edgelist.py` doesn't emit — node2vec only needs the
  edgelist and is fast at this size).
- coembedding `--k 6` so MUSE/phenograph's `k+1` neighbours stay below the overlapping-gene count.
- looser `similarity_threshold=0.5`, `min_shared_fractions=1` so a small protein set still forms edges.

**Verified:** this runs end-to-end. The emitted crate validates against `fairscape_models`
(`ROCrateV1_2`) with 9 Computations (workflow run + 8 steps), 11 Datasets, 10 Software, and no
dangling references; `results/visualization/hierarchy_music_style.png` is produced.

Rebuild bigger/smaller cohorts with flags:

```bash
python make_mini_cohort.py --n-genes 6 --images-per-gene 1 --extra-proteins 30
```

## Key parameters

| Param | Default | Notes |
| --- | --- | --- |
| `treatment` | `untreated` | `untreated` / `Vorinostat` / `Paclitaxel` |
| `base_dir` | `/path/to/cm4ai-pipeline` | where the **scripts** live |
| `python` | `conda run -n subcell python` | interpreter for every step |
| `images_dir` / `secms_reports_dir` / `provenance` | `null` → `base_dir` defaults | override for a subset |
| `model_path` | `images/Paclitaxel_embedding/model.pth` | pretrained image model; used only if it exists |
| `similarity_threshold` / `min_shared_fractions` | `0.98` / `3` | SEC-MS → edgelist |
| `image_embedding_args` / `ppi_embedding_args` / `coembedding_args` / `hierarchy_args` | `''` | extra CLI args per cellmaps step |
| `outdir` | `${base_dir}/nf_results_${treatment}` | results + RO-Crate location |

## Validating the crate

After a real run, validate the emitted crate against the FAIRSCAPE schema:

```bash
PYTHONPATH=/path/to/fairscape_models \
  python3 nf-fairscape-test/validate_crate.py \
  nf_results_untreated
```
