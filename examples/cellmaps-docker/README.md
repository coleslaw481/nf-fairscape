# cellmaps-docker

The vanilla Cell Maps pipeline from [`../cellmaps`](../cellmaps), with the
shared `cellmaps` conda environment replaced by **one container per stage**. Same six stock
`cellmaps_*` tools, same nf-fairscape provenance crate, no dependency on anything installed
on the host except Nextflow and a Docker daemon.

```
IMAGE_DOWNLOAD ─→ IMAGE_EMBEDDING ─┐
                                   ├─→ COEMBEDDING ─→ HIERARCHY
PPI_DOWNLOAD   ─→ PPI_EMBEDDING   ─┘
```

---

## Quick start

```bash
cd examples/cellmaps-docker
containers/build.sh                       # build all six images (~9 GB, one-time)
nextflow run main.nf -profile standard    # the canonical U2OS/BioPlex demo
```

Offline-ish smoke test — fake images, fake embedders, minutes instead of ~an hour, but it
exercises all six containers and the whole provenance path:

```bash
nextflow run main.nf -profile standard,test
```

Results land in `nf_results_cellmaps_docker/`, with `ro-crate-metadata.json`,
`ro-crate-datasheet.html` and `provenance-graph.html` at the top.

---

## What is actually available upstream

This was the main research question, and the answer is messier than "just use their images".

The `docker/Dockerfile` in each `cellmaps_*` package builds and pushes to
**`idekerlab/cellmaps_*`**. That namespace has four repositories and none of them is a
cellmaps tool — those tags do not exist. The images that do exist are published under
**`cm4ai/`**, are `python:3.9-slim` based (not the `continuumio/miniconda3` the repo
Dockerfiles use), and were last pushed 2026-07-23 … 2026-07-27. So the in-repo Dockerfiles
are not what produced what is on Docker Hub, and they are not a reliable guide to it.

| stage | published image | platform | size | what this pipeline uses |
|---|---|---|---|---|
| image download | `cm4ai/cellmaps_imagedownloader:0.3.0` | amd64 | 242 MB | **wrapped** |
| ppi download | `cm4ai/cellmaps_ppidownloader:0.2.2` | amd64 | 244 MB | **wrapped** |
| ppi embedding | `cm4ai/cellmaps_ppi_embedding:0.4.3` | amd64 | 299 MB | **wrapped** |
| image embedding | `cm4ai/cellmaps_image_embedding:0.3.3` | amd64 | 8.3 GB | **rebuilt slim** |
| coembedding | `cm4ai/cellmaps_coembedding:1.5.0` | amd64 | 8.2 GB | **rebuilt slim** |
| coembedding | `cm4ai/cellmaps_coembedding:1.6.0` | **arm64 only** | 523 MB | unusable on x86 |
| hierarchy | `cm4ai/cellmaps_generate_hierarchy:0.3.0.post1` | **arm64 only** | 237 MB | **built here** |
| (hierarchy eval) | `cm4ai/cellmaps_hierarchyeval:0.2.2` | **arm64 only** | 250 MB | not in this pipeline |

Three findings drove the design:

**1. The hierarchy stage has no amd64 image at all.** Its only tag is arm64. On an x86_64
host the last stage of the pipeline has to be built locally no matter what, so
`containers/Dockerfile.hierarchy` builds it from PyPI. (`cellmaps_generate_hierarchy/docker/Dockerfile`
in the repo would also work here — it is a plain `pip install .` on miniconda3 — it is just
conda-fat and pinned to your working tree rather than a release.)

**2. The two ML images are ~8 GB each because of a CUDA torch nobody uses.** They are
`continuumio/anaconda3` plus the default PyPI torch, which drags in ~2.5 GB of `nvidia-*`
wheels. Unpacked they do not fit next to each other on a disk with ~30 GB free. Worse, for
image embedding the GPU is *unreachable*: `cellmaps_image_embedding/runner.py` hardcodes

```python
self._device = 'cpu'
self._cuda_available = False
```

and never reads them from a flag. A CPU-only build is not a downgrade there, it is the same
computation in 2.6 GB instead of ~20 GB. Coembedding genuinely can use a GPU
(`torch.cuda.is_available()` in `proteinprojector/__init__.py`), so `-profile gpu` and a
CUDA build arg are provided for when that matters — at example scale it does not.

**3. Every published image sets `ENTRYPOINT ["...cmd.py"]`, which breaks Nextflow.**
Nextflow launches a task as `docker run <image> /bin/bash -ue .command.sh`. With that
entrypoint it becomes `cellmaps_ppidownloadercmd.py /bin/bash -ue ...` and exits 2 with an
argparse usage dump. Nextflow can override the entrypoint, but in 25.10 **only** via the
`NXF_CONTAINER_ENTRYPOINT_OVERRIDE=true` environment variable — it is not a
`nextflow.config` setting (`ContainerHelper.entrypointOverride()` reads `SysEnv` and
`DockerConfig` does not override it). Depending on an env var that silently breaks the run
when forgotten is a bad trade against a two-line wrapper image, so the wrappers reset
`ENTRYPOINT []`.

The wrappers also add `procps`. The upstream images are slim and have no `ps`, which
Nextflow needs to sample task CPU/RAM — without it every task logs
"Failed to collect task metrics" and `-with-trace`/`-with-report` resource columns are empty.

Resulting images, all six built by `containers/build.sh`:

```
nf-cellmaps/image_embedding:0.3.3     2.6 GB     (from PyPI, CPU torch, model baked in)
nf-cellmaps/coembedding:1.5.0        2.34 GB     (from PyPI, CPU torch)
nf-cellmaps/generate_hierarchy:0.2.5 1.27 GB     (from PyPI — no amd64 upstream)
nf-cellmaps/ppi_embedding:0.4.3         1 GB     (wraps cm4ai/)
nf-cellmaps/ppidownloader:0.2.2       865 MB     (wraps cm4ai/)
nf-cellmaps/imagedownloader:0.3.0     860 MB     (wraps cm4ai/)
```

If you would rather run the published images unmodified:

```bash
export NXF_CONTAINER_ENTRYPOINT_OVERRIDE=true
nextflow run main.nf -profile upstream
```

which works for the first five stages and cannot run the sixth on x86.

---

## Entry points: you do not have to start from the downloaders

Each modality independently enters at one of three points. Mix freely — reuse an image
crate while downloading fresh PPI data, or vice versa.

**(a) Download (default).** Fetch IF images from HPA, read a BioPlex edge+bait list.

```bash
nextflow run main.nf
nextflow run main.nf --cell_line MCF7 --samples my_samples.csv --unique my_unique.csv
```

**(b) Point at images / AP-MS data already on disk.** The downloader tools still run — they
still build a proper RO-Crate — but they read a local CM4AI crate instead of hitting the
network. Give the path to the TSV; for images the whole containing directory is staged,
because the TSV names the `red/blue/green/yellow` image files beside it.

```bash
nextflow run main.nf \
    --cm4ai_image_table /data/cm4ai_chromatin_.../image_gene_node_attributes.tsv \
    --cm4ai_apms_table  /data/cm4ai_chromatin_.../apms.tsv
```

**(c) Point at finished crates.** Skip a downloader entirely and feed an earlier run's
output directory straight into the embedding stage. The crate becomes a workflow *input*
dataset in the provenance record rather than something this run produced.

```bash
nextflow run main.nf \
    --image_crate ../nf_results_cellmaps/image_download/image_download \
    --ppi_crate   ../nf_results_cellmaps/ppi_download/ppi_download
```

Everything downstream is unchanged in all three cases: the embedding steps read provenance
out of whatever crate they are handed.

---

## Why every external file is a declared `path` input

Nextflow bind-mounts exactly two things into a task container: the task work directory, and
the parent directory of every file staged as a declared `path` input — both at their
original absolute paths (`-v /host/x:/host/x`). Confirmed from a real `.command.run`:

```
docker run -i --cpu-shares 1024 -e "NXF_TASK_WORKDIR" \
  -v /path/to/cm4ai-pipeline:/path/to/cm4ai-pipeline \
  -v /path/to/work/a1/7756f0:/path/to/work/a1/7756f0 \
  -w "$NXF_TASK_WORKDIR" ... nf-cellmaps/ppidownloader:0.2.2 /bin/bash -ue .../.command.sh
```

A path interpolated into the script as a bare string is **not** mounted, and the tool gets
ENOENT. That is why `--proteinatlasxml` and `--model_path` are staged inputs here, where
`../cellmaps` pastes `params.proteinatlasxml` straight into the command line. That
version works only because the conda process shares the host filesystem; move it into a
container unchanged and the 697 MB HPA cache silently disappears.

Two useful consequences of the path-identical mount:

- absolute paths the cellmaps tools write into **their own** RO-Crates (`outdir`, `cwd` in
  `task_*_start.json`) stay valid on the host — no path rewriting needed anywhere;
- `-resume` and the nf-fairscape ARK minting (which hashes normalized file paths) behave
  exactly as they did under conda.

---

## What the conda pipeline needed and this one does not

Deleted from the config, because the problems do not exist when each tool ships its own
userland:

- `params.python = 'conda run -n cellmaps python'` and `params.base_dir` — commands now call
  the console scripts directly (`cellmaps_ppidownloadercmd.py`), which are on `PATH`;
- `LD_LIBRARY_PATH` pointing at `~/.local/.../nvidia/nvjitlink/lib` so torch could find its
  CUDA libs;
- the `PYTHONPATH` hack that forced the env's numpy 1.26 ahead of `~/.local`'s numpy 2 so
  that `cv2` (compiled against numpy 1.x) would not crash with
  "numpy.core.multiarray failed to import".

That last one is the clearest argument for containers here: it is a host-specific
interaction between three separately installed things, and it silently breaks image
embedding on any other machine.

---

## Environment notes for this box

**Use the native daemon, not Docker Desktop.** `docker context ls` shows `desktop-linux` as
current — a linuxkit VM that only bind-mounts a fixed set of shared host paths. Running
Nextflow against it fails on any work directory outside those paths:

```
docker: Error response from daemon: mounts denied:
The path /tmp/.../work/ea/578fda is not shared from the host and is not known to Docker.
```

The native Ubuntu daemon at `/var/run/docker.sock` has no such restriction. Either
`docker context use default` or export `DOCKER_HOST=unix:///var/run/docker.sock` before
launching. Note the two daemons keep separate image stores, so images built or pulled under
one are invisible to the other.

**Disk.** `/` is at ~93% with ~27 GB free, and it is shared by `/var/lib/docker` *and* the
Docker Desktop VM's `Docker.raw` (34 GB). The native daemon reports ~50 GB reclaimable
images, ~38 GB reclaimable build cache and ~36 GB reclaimable volumes, so `docker system df`
is worth a look before a big pull. This is a large part of why the two ML images are built
slim rather than pulled.

**Run options.** `containerRunOptions` in `nextflow.config` carries three flags, each of
which fixes something observed failing:

```groovy
'-u $(id -u):$(id -g) -v /etc/passwd:/etc/passwd:ro -e HOME="$NXF_TASK_WORKDIR"'
```

- `-u` — without it containers run as root and every result file, published crate and work
  directory is root-owned. `docker.fixOwnership` chowns afterwards; running as the invoking
  user avoids the problem rather than repairing it.
- `-v /etc/passwd:ro` — `-u 1000` names a uid that does not exist inside the image, so
  `getpwuid()` fails, the cellmaps tools log
  `Unable to get login for user: 'getpwuid(): uid not found: 1000'` and write `"login": ""`
  into **their own** RO-Crate provenance. Mounting the host passwd file read-only restores
  the username (`getpass.getuser()` → `oj`, verified).
- `-e HOME` — with no passwd entry HOME is unset and anything resolving `~` writes to `/`.
  Matplotlib (via cellmaps_coembedding) fails with
  `mkdir -p failed for path /.config/matplotlib: Permission denied` and falls back to a temp
  dir with a warning; a tool less forgiving than matplotlib would die. Pointing HOME at the
  task work directory makes it writable, per-task, and disposable.

**Do not remove `--fake_embedder` from the `test` profile.** `--fake_images` makes every
downloaded image a copy of the first one per channel, so a *real* DenseNet pass over that
crate returns 163 embedding vectors that are pairwise identical (measured: cosine
1.000000 across all 1,770 pairs). PhenoGraph's Louvain then finds no modularity values and
`cellmaps_coembedding` dies with `IndexError: list index out of range` in
`phenograph/core.py:293`. Fake images and a real embedder do not mix — that combination
fails, and it is not a container problem.

---

## How this affects nf-fairscape provenance

Reviewed against the plugin source and verified by running the plugin over a
containerised task.

### What already works, unchanged

**The container is recorded.** `FairscapeRenderer.groovy:247` already writes
`'containerImage': task.container` onto every task Computation as a passthrough extra
(valid because the pydantic model is `extra='allow'`). A real crate from a containerised
run:

```json
{
  "@id": "ark:59853/computation-ppi-download-...",
  "@type": ["prov:Activity", "https://w3id.org/EVI#Computation"],
  "name": "PPI_DOWNLOAD",
  "containerImage": "nf-cellmaps/ppidownloader:0.2.2",
  "command": "cellmaps_ppidownloadercmd.py ppi_download --edgelist edgelist.tsv ...",
  "identifier": "a17756f0d96a0d028b9072060d3e3b3f"
}
```

**The recorded `command` gets strictly better.** Under conda it was
`conda run -n cellmaps python /path/to/cm4ai-pipeline/cellmaps_ppidownloader/.../cmd.py ...` —
a command that only means something on the machine that ran it. Under containers it is the tool
invocation alone, and `command` + `containerImage` together are actually re-runnable
somewhere else. This is the EVI Computation story working as intended.

**Everything host-side is untouched.** `checksums`, `expandDirectories`, `contentSizes` and
the schema inference all run in the launcher JVM over the *published* copies under
`params.outdir`, not inside any container. Directory expansion, md5, `treeSize`, the
datasheet and the evidence graph are all indifferent to how the task ran. The one way
containers could have broken them is file ownership, which `-u` handles.

**ARKs are stable.** They hash normalized file paths, and the bind mount is path-identical,
so ARKs mint the same under containers as under conda.

### What degrades

**Machine identity is replaced by container identity in the *tools'* own crates.** The
cellmaps tools write a `task_*_start.json` with `uname`, `login` and `python`. In a
container:

```json
"login": "",
"uname": "... node='d524b48bd832' ...",
"python": "3.9.25"
```

`node` is the container ID rather than the host, and `login` is empty because the `-u 1000`
user has no `/etc/passwd` entry. Arguably a fair trade — the container ID is more useful
than a hostname — but "which machine ran this" is now only in the nf-fairscape layer. Mount
`/etc/passwd:ro` if you want the username back.

**The version claim now has two authorities.** `ext fairscape.softwareVersion` is what the
crate asserts; the image tag is what actually ran. Nothing checks they agree, and a stale
`ext` block would produce a crate that lies with no visible symptom. Handled here by driving
both from one `params.versions` map in `nextflow.config` — that is the single most important
config decision in this port.

### Gaps worth fixing in the plugin

### Implemented in the plugin (2026-07-28)

The first two gaps below were real enough to fix rather than write up, so
`fairscape.containerProvenance = true` now exists and this pipeline enables it. It resolves
each distinct image once via the container engine and records:

| Entity | Field |
| ------ | ----- |
| Task Computation | `containerImage` (as before) + `containerDigest` |
| Process Software | `containerImage`, `containerDigest`, `containerImageId` |

Putting the image on the **process Software** is the substantive part: `contentUrl` there
names the tool's GitHub repo, which says what the software *is*, while the container is
what actually ran. This crate now carries both.

One correction worth knowing, because the intuitive reading is wrong: **a digest pins
content, not availability.** Under Docker's containerd image store `Id` *is* the manifest
digest, so `containerDigest` and `containerImageId` come out as the same string — verified
for a pulled image (`cm4ai/cellmaps_ppidownloader:0.2.2` → `sha256:85b359d3…`, matching
what Docker Hub serves) *and* for a locally built one that exists only on this machine. So
the presence of a repo digest is not evidence anyone else can pull the image; the six
`nf-cellmaps/*` images have perfectly good digests and are not published anywhere.

Off by default, and proven so: the pre-change plugin was reconstructed from the same
working tree with only these edits reverted, and `examples/letters-chain` diffs run-for-run
identical with the option off. (The one difference that shows up between *any* two runs is
the order of the run-level `generated` list, which flips on ~2 runs in 10 in the unmodified
baseline — pre-existing publish-event ordering, not this change.) 158 unit tests pass and
`make verify` still hits its expected 9/17/6/1 entity counts.

### Still open

1. **`containerImage` is invisible to every downstream artifact.** Grepping the datasheet
   templates, `DatasheetGenerator`, `EvidenceGraphBuilder` and `AiReadyScorer` finds no
   reference to it. It reaches `ro-crate-metadata.json` and stops — the datasheet never
   shows it, the evidence graph never projects it, and the AI-Ready scorer does not count
   it (`interpretable` counts `Software` entities; there is no environment/reproducibility
   criterion at all). A containerised run is materially more reproducible than a conda one
   and scores identically. Surfacing the image on the datasheet's computation rows is cheap;
   an AI-Ready sub-criterion for "execution environment captured" is the more interesting
   change, and would need coordinating with `fairscape_models`' AIReady mapping rather than
   being done unilaterally here.

2. **Scaffolding files become described inputs.** Not container-specific, but it showed up
   here: the usual nf-core idiom for an optional `path` input is a placeholder file
   (`assets/NO_FILE`), and a staged placeholder lands in the crate as a workflow input
   Dataset alongside the real ones — provenance asserting that a 65-byte
   "placeholder standing in for an unset optional input" was scientific input. `patterns`
   filters *published* files only; `getWorkflowInputs` is unfiltered. Worked around here by
   passing `[]` instead of a placeholder (see `optionalPath` in `main.nf`), which stages
   nothing, but a `fairscape.excludeInputs` glob would be the general fix.

(The third gap — nothing in the plugin docs warning about the `ENTRYPOINT` /
`NXF_CONTAINER_ENTRYPOINT_OVERRIDE` trap — is now closed: `docs/FAIRSCAPE.md` has an
"If your tasks die with an argparse usage dump" section, since anyone pointing nf-fairscape
at community bioinformatics images will hit it.)

---

## What was actually verified

Not inferred — run on this machine, 2026-07-28, native daemon, Nextflow 25.10.4.

- **Full six-stage run on REAL data, `-profile standard`** (the canonical demo — real HPA
  download, real DenseNet, real node2vec, real MUSE, real HiDeF): exit 0. 1,868 HPA JPEGs
  fetched (spot-checked 40/40 distinct md5s, i.e. genuinely different images);
  `image_emd.tsv` 164 × 1024; `ppi_emd.tsv` 1,010 × 1024; `coembedding_emd.tsv` 164 × 128;
  `hierarchy.cx2` with 37 systems / 37 edges. Crate `VALID` — 7 Computations, 72 Datasets,
  8 Software, 31 Schemas.
- **Login recorded**: with `-v /etc/passwd:ro`, all six tools now write `"login": "oj"` into
  their own crates instead of `""`; `node` is the per-stage container ID, as expected.
- **Full six-stage run, `-profile standard,test`**: exit 0, all six containers used.
- **Crate validity**: `ROCrateV1_2.model_validate` (fairscape_models) + dangling-ARK check →
  `VALID`. 7 Computations, 66 Datasets, 8 Software, 31 Schemas.
- **Container recorded per stage**: all six task Computations carry the expected
  `containerImage`; all six tool `Software` entities carry the version from
  `params.versions`, confirming `ext fairscape` resolves params at runtime.
- **Entry mode (c)**: `--image_crate/--ppi_crate` against a previous run's crates → only
  four processes ran, both downloaders skipped, the two crates appear as `usedDataset` on
  the run Computation, crate `VALID` (5 Computations, 45 Datasets, 19 Schemas).
- **Baked DenseNet, offline**: `docker run --network none` → the weights load through the
  tool's own `class_densenet121_large_dropout` and a forward pass returns the `(1, 1024)`
  feature vector the pipeline consumes. `/opt/densenet` is absent, confirming the clone in
  the upstream Dockerfile is vestigial. torch reports `2.6.0+cpu`, `cuda: None`.
- **ENTRYPOINT behaviour**: reproduced the failure on an unwrapped `cm4ai/*` image (exit 2,
  argparse usage dump) and the fix under `NXF_CONTAINER_ENTRYPOINT_OVERRIDE=true`
  (`--entrypoint /bin/bash … -c "…"`). Wrapper images work with neither.
- **Ownership**: without `-u`, outputs land as `root:root`; with it, the invoking user.
- **Mounts**: `.command.run` shows the examples directory bind-mounted alongside the work
  dir, both path-identical. A 697 MB `proteinatlas.xml.gz` staged from a separate mount was
  read successfully inside the container.
- **Profiles**: `standard`, `gpu`, `upstream`, `singularity`, `standard,test` all parse;
  `gpu` swaps only coembedding to `-cu124` and adds `--gpus all`; `upstream` swaps all six
  to `cm4ai/*`.

Two bugs were found this way and fixed. A shared `assets/NO_FILE` placeholder for optional
inputs triggers Nextflow's "input file name collision" as soon as two optional inputs on one
process are both unset — which is the default whenever `--proteinatlasxml` is not set. And
`--proteinatlasxml ''` reaches the script as boolean `true`, so `file(true)` died with
`Missing process or function getFileSystem()`. Both are gone: optional inputs now pass `[]`.

## Layout

```
main.nf                              six processes, three entry points per modality
nextflow.config                      versions + containers + profiles + fairscape block
containers/
  build.sh                           builds all six; `build.sh hierarchy` for one
  Dockerfile.upstream                ARG BASE wrapper: ENTRYPOINT [] + procps
  Dockerfile.image_embedding         PyPI + CPU torch + baked DenseNet weights
  Dockerfile.coembedding             PyPI + CPU torch (CUDA via --build-arg)
  Dockerfile.hierarchy               PyPI; the stage with no amd64 image upstream
```

Profiles: `standard` (docker, default), `test` (fake images/embedders), `gpu` (CUDA
coembedding), `upstream` (published images, needs the env var), `singularity`.
