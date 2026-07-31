# FAIRSCAPE EVI RO-Crate format

What ends up in the crate, and why. For the options that control it, see
[CONFIGURATION.md](CONFIGURATION.md).

The `@context` is FAIRSCAPE's default: [schema.org](https://schema.org/) as `@vocab`, plus
[EVI](https://w3id.org/EVI#), [PROV-O](http://www.w3.org/ns/prov#) and
[Croissant RAI](http://mlcommons.org/croissant/RAI/1.0) prefixes, and `@id`-typed EVI terms
(`usedSoftware`, `usedDataset`, `generated`, `generatedBy`).

## Graph structure

| Node | `@type` | Content |
| ---- | ------- | ------- |
| Descriptor | `CreativeWork` | `conformsTo` [RO-Crate 1.2](https://w3id.org/ro/crate/1.2), `about` → root |
| Root crate | `["Dataset", "EVI#ROCrate"]` | `conformsTo` [FAIRSCAPE profile 0.1](https://w3id.org/fairscape/profile/0.1); name/description/version from the manifest; `hasPart` lists everything below |
| Run Computation | `["prov:Activity", "EVI#Computation"]` | the whole run: `command` = launch line, `parameter` = folded params, `usedSoftware` = script + engine, `usedDataset` = external inputs, `generated` = published outputs. Extras: `startTime`, `endTime`, `nextflowVersion`, `identifier` (session id) |
| Task Computation | `["prov:Activity", "EVI#Computation"]` | one per successful task: `command` = task script, `usedSoftware` → its process, `usedDataset`/`generated` = its files, `isPartOf` → run. Extras: `containerImage`, `identifier` (task hash), `containerDigest` with `containerProvenance` |
| Workflow Software | `["prov:Entity", "EVI#Software"]` | the main script; referenced only by the run Computation |
| Nextflow Software | `["prov:Entity", "EVI#Software"]` | the engine, versioned |
| Process Software | `["prov:Entity", "EVI#Software"]` | one per process: `description` = the process body as written (the template — the resolved command lives on each Computation), `contentUrl` = its defining file (the copy in `workflow/` when the crate carries it, else a commit-pinned module URL), `isPartOf` → workflow Software. Overridable via [`ext fairscape`](#user-supplied-metadata) |
| Dataset | `["prov:Entity", "EVI#Dataset"]` | one per unique file: `format`, `contentSize`, `generatedBy` → producer, `contentUrl` = crate-relative when published. Opt-in: `md5`, directory sizes, `isPartOf`, `evi:schema` |
| Schema | `EVI:Schema` | one per csv/tsv with `schemas` on |
| Container | `["prov:Entity", "EVI#Container"]` | one per image with `containerProvenance` on |

Dataflow between steps emerges from shared Dataset ARKs — a task consuming another's output
references the same entity. WRROC's prospective layer (`FormalParameter`, `HowToStep`,
`ControlAction`) has no EVI equivalent and is not modeled.

## Inverse relationships

Unless `linkInverses = false`, the crate is completed against the `owl:inverseOf` pairs EVI
declares, so a relationship stated once appears on both entities:

| Stated by the renderer | Entailed |
| ---------------------- | -------- |
| `Dataset.generatedBy` → Computation | `Computation.generated` → Dataset |
| `Computation.usedDataset` → Dataset | `Dataset.datasetUsedBy` → Computation |
| `Computation.usedSoftware` → Software | `Software.softwareUsedBy` → Computation |

This is what fills `generated` for files found inside a published directory — the renderer
links those one way only. On the Cell Maps pipeline it takes `HIERARCHY.generated` from 1
entry to 24.

All 18 pairs live in `InverseLinker.INVERSE_PAIRS`, carried as data rather than re-derived
with rdflib at runtime: the ontology is versioned, so the answer is fixed. Pairs apply in
sorted order, which makes the augmented crate byte-stable; the CLI's rdflib order is not.

Published datasets pick up the run-level Computation in `generatedBy` alongside their task,
because the run lists them in `generated`. Correct entailment, and what the CLI does too.

## D4D / LinkML export

Unless `linkml = false`, the crate root becomes a D4D (Datasheets for Datasets) document at
`ro-crate-linkml.yaml`. Despite the filename, the LinkML artifact *is* the D4D translation:
`ROCRATE_TO_D4D_MAPPING` re-expresses the root's schema.org and RAI properties in D4D's
vocabulary (`rai:dataBiases` → `known_biases`, `rai:dataUseCases` → `purposes`/`tasks`,
`prohibitedUses` → `discouraged_uses`, …). Root only — D4D describes a dataset, not a graph.

`PyYaml` reproduces `yaml.dump`'s defaults exactly, because the output is diffed against the
CLI byte for byte.

One inherited quirk: D4D's `bytes` field parses `contentSize` with 1024-based suffixes while
the datasheet formats it with 1000-based ones, so `945.6 MB` reads back as 991,533,465.
Reproduced, not corrected — the port's job is to agree with the CLI.

## Opt-in enrichment

Off by default. A run that sets none of it emits the crate the plugin always emitted.

| Option | Cost | Buys |
| ------ | ---- | ---- |
| `expandDirectories` | one walk per published directory | a Dataset per file inside it |
| `checksums` | one full read per file | `md5` (AI-Ready *verifiable*) |
| `contentSizes` | one walk per directory + the crate | directory and crate-total `contentSize` (*statistics*) |
| `schemas` | reads N rows per csv/tsv | an `EVI:Schema` per table (*standards*) |
| `containerProvenance` | one `image inspect` per image | `containerDigest` on tasks, container fields on Software |

The derived artifacts (`datasheet`, `evidenceGraph`, `linkInverses`, `linkml`) are a
different category and default to *on*: they read the crate JSON only. Two of them rewrite it.

Two more default to *on* because what they replace is wrong rather than merely absent, and
because their cost is bounded by the number of processes and parameters, not by the amount of
data — the asymmetry the table above is guarding against:

| Option | Cost | Buys |
| ------ | ---- | ---- |
| `toolVersions` | one small file read per run | the version of the tool a process ran, not the pipeline's |
| `paramInputs` | one `exists` per file-shaped parameter | Datasets for inputs no task staged (the samplesheet) |

`toolVersions` looks in both places a version can live: the `versions.yml` a task writes, and
the run-wide file nf-core collates into `pipeline_info/` (the only record once a module reports
through a `topic` channel with `eval`, which writes no per-task file). It is keyed by bare
process name, then by qualified name. A process whose version reaches neither place keeps the
manifest fallback — including MultiQC, whose own version cannot be in a file it is given as
input. `ext.fairscape.softwareVersion` still wins over both.

`paramInputs` takes a parameter to name a file when its value contains a `/` and its last
segment has an extension, which excludes base directories and URL prefixes
(`igenomes_base`, `custom_config_base`). Local paths must exist; a remote URI is recorded
without being fetched.

The root `contentSize` is measured before the datasheet and graph HTML land in the crate
directory, so those aren't counted. Without it the AI-Ready scorer sums per-entity sizes,
double-counting an expanded directory and its files.

With `evidenceGraph` the root also carries:

| Field | Meaning |
| ----- | ------- |
| `https://w3id.org/EVI#outputs` | Datasets no computation consumed — the run's terminal outputs |
| `https://w3id.org/EVI#inputs` | samples, consumed datasets nothing generated, standalone datasets |
| `localEvidenceGraph` | `{"@id": "provenance-graph.html"}` |

Derived exactly as `fairscape build subcrate` derives them. See [DATASHEET.md](DATASHEET.md).

## Container provenance

A task Computation always carries `containerImage` — whatever the `container` directive
resolved to. That's usually a tag, and a tag is a label, not an identity:
`cm4ai/cellmaps_coembedding:1.5.0` can be re-pushed tomorrow over different bits and two
crates look identical. `containerProvenance = true` asks the engine what it really was:

| Entity | Field | Value |
| ------ | ----- | ----- |
| Container (one per image) | `containerImage` / `containerDigest` / `containerImageId` | reference, content digest, local id |
| Task Computation | `usedContainer` | reference to that Container |
| Task Computation | `containerDigest` | `repo@sha256:…` |
| Process Software | `containerImage` / `containerDigest` / `containerImageId` | the image every task of that process used |

The Container entity is an EVI `Container` (`fairscape_models/container.py`), listed in the
root `hasPart`, with an ARK minted from the digest when there is one — so the identifier
names the bits, not the tag. `fairscape_models` folds `usedContainer` into `prov:used`
alongside `usedSoftware`/`usedDataset`. The flat `container*` keys stay for compatibility.

Putting the image on the **process Software** is the point. `contentUrl` there names the
tool's source repo — what the software *is*. The container is what actually executed.

A process whose tasks used different images (a dynamic `container` directive) gets none on
its Software entity; the per-task Computations already carry the truth.

**A digest pins content, not availability.** Under Docker's containerd image store `Id` *is*
the manifest digest, so `containerDigest` and `containerImageId` are the same string — for a
pulled image and equally for one built locally that exists nowhere else. Under the classic
store they differ, and a never-pushed image reports no repo digest at all. So a digest says
exactly which bits ran; whether anyone else can get them depends on a push that nothing
locally can confirm.

Best-effort throughout. Only `docker` and `podman` are auto-detected — Singularity and
Apptainer run image *files*, with no daemon to interrogate.

### If your tasks die with an argparse usage dump

Not a plugin problem, but you'll meet it with single-purpose tool images. Images built to be
run as `docker run image --help` set an entrypoint:

```dockerfile
ENTRYPOINT ["mytoolcmd.py"]
```

Nextflow launches tasks as `docker run <image> /bin/bash -ue .command.sh`, which becomes
`mytoolcmd.py /bin/bash -ue .command.sh` — the tool parses the launcher as its own arguments
and exits non-zero. As of 25.10 the override is environment-only:

```bash
export NXF_CONTAINER_ENTRYPOINT_OVERRIDE=true
```

There is no `docker.entrypointOverride` setting (`ContainerConfig.entrypointOverride()` reads
`SysEnv`; `DockerConfig` doesn't override it). The alternative is a two-line wrapper image
that resets `ENTRYPOINT []`.

## User-supplied metadata

Two hooks put in what Nextflow can't know. Keys and syntax:
[CONFIGURATION.md](CONFIGURATION.md#fairscapemetadata). What they do to the graph:

**`fairscape.metadata`** is overlaid onto the **root** entity after it's assembled — the long
tail with no dedicated option: `associatedPublication`, `funder`, `principalInvestigator`,
`citation`, `contactEmail`, `conditionsOfAccess`, `ethicalReview`, the `rai:*` properties,
anything else the FAIRSCAPE profile root recognizes. It wins over computed values; `@id`,
`@type`, `conformsTo` and `hasPart` are refused with a `WARN`.

**`ext fairscape: [...]`** replaces fields on one process's Software entity (`softwareName` →
`name`, `softwareVersion` → `version`, `softwareUrl` → `contentUrl`, and so on). Without it
the entity describes the *Nextflow process*; with it, the *tool*. Nothing is lost — task
Computations still carry the process name and still point here via `usedSoftware`.

Nextflow whitelists directive names, so a custom bare directive is impossible; `ext` is the
sanctioned namespace. Bad values are ignored with a `WARN` rather than failing the run —
there's no way to reject something Nextflow already accepted into `ext`.

## Where a Dataset's bytes are: `contentUrl` vs `localPath`

A Dataset says where its bytes are with one of two properties, and which one it gets depends
on whether the file is inside the crate.

| Where the file is | Property | Value |
| ----------------- | -------- | ----- |
| published under the crate directory | `contentUrl` | crate-relative path (`tables/x.tsv`) |
| a pipeline asset, or a remote input | `contentUrl` | absolute URI — the pinned GitHub URL, `s3://…` |
| a work-directory intermediate | `localPath` | path relative to the run directory (`work/ab/cd…/x.tsv`) |

The split exists because **a relative `contentUrl` is a promise that the file is at that path
inside the crate.** [RO-Crate resolves a relative reference against the crate
root](https://www.researchobject.org/ro-crate/specification/1.2/data-entities.html) and 1.2
gives no way to spell one that escapes it. Work-directory intermediates are not under the
crate root, so emitting a relative path for them made the crate assert a file was present
where nothing was — 44 of 130 entities on nf-core/differentialabundance pointed at
`work/…` paths that resolve to nothing from `results/`.

[`localPath`](https://w3id.org/ro/terms#localPath) is RO-Crate's term for *the file was here
when this ran*, carrying no claim that it still is — the honest reading of a work directory
Nextflow may already have deleted. It is declared in the crate's `@context` as a bare term,
spelled the way [RO-Crate 1.2's own context](https://w3id.org/ro/crate/1.2/context) spells
it, so a consumer reading with either context gets the same IRI.

Nothing else changes: the entity keeps its `name`, `md5`, `contentSize`, `format` and every
provenance edge, so **the derivation chain through unpublished intermediates stays intact**.
Only the false locator is gone.

### The same rule on S3, Azure and Google Storage

"Inside the crate directory" is a filesystem question, not a local-disk one, so the split
holds for a crate written to an object store: both paths come from the same provider and are
compared there. Three things are worth knowing before you run one.

**Point `fairscape.file` at the output directory.** It defaults to `ro-crate-metadata.json`,
which resolves from the launch directory. Set `outputDir = 's3://bucket/results'` and leave
that default and the crate is written locally, so *no* published file is inside it — every one
of them gets an absolute `s3://…` `contentUrl`. That crate is not wrong, but it is a
description of a dataset rather than a package of one. Set the two together:

```groovy
outputDir = 's3://bucket/results'
fairscape.file = 's3://bucket/results/ro-crate-metadata.json'
```

**A cloud work directory reads like a local one.** Nextflow's `PathNormalizer` rewrites
anything under `workDir` to a bare `work/ab/cd…` — scheme and bucket included — before the
plugin sees it, so an intermediate still sitting at `s3://bucket/work/ab/cd…` is recorded as
`localPath: work/ab/cd…`, exactly as it would be locally. That keeps crates comparable across
connectors and is true as *where the run put it*, but note it does not hand you a URL for an
intermediate that may in fact still be retrievable. Intermediates are scratch by construction
— Nextflow will happily delete the work directory — so the crate does not promise otherwise.

**The `mode: 'copy'` precondition below is local-only.** Nextflow refuses to symlink across
filesystems: `PublishDir.validatePublishMode()` forces `copy` (with a warning if you asked for
`symlink`, `rellink` or `link`) whenever the publish target is not on the default filesystem.
So a crate whose output directory is on S3, Azure or Google Storage cannot contain the
dangling-link case at all — it is self-contained by construction. Only local runs can get
this wrong.

The workflow copy travels the same way: `<crateDir>/workflow/` is created and written with
plain `java.nio` calls, which become a cross-provider stream copy when the crate directory is
remote.

### The workflow travels with the crate

`includeWorkflow` (default `true`) copies the workflow script and every config file the run
used into `<crateDir>/workflow/`, and points the workflow Software entity's `contentUrl` at
the copy:

```
results/
  ro-crate-metadata.json
  workflow/
    main.nf                  <- workflow Software contentUrl: workflow/main.nf
    nextflow.config          <- a Dataset, isPartOf the workflow Software
    fairscape.config         <- and any -c config, because that changed the run too
```

Every Software entity whose definition was copied in points at the copy, not just the
workflow one. A process declared in `main.nf` is declared in the `main.nf` sitting in
`workflow/`, so its Software entity says `workflow/main.nf` too — otherwise the crate would
describe those bytes with an absolute path on the machine that ran the pipeline while
carrying them all along. Modules are untouched by this: they are not copied, so an nf-core
process keeps normalizing to its commit-pinned
`https://github.com/nf-core/<pipeline>/tree/<sha>/modules/…` URL, which is a better locator
than any copy. An `ext.fairscape.softwareUrl` override still wins over both, since it names
the tool rather than the file that declares the process.

Without it a crate cannot reliably say what was run. A local pipeline normalizes to
`file:///home/you/pipeline/main.nf`, which resolves on exactly one machine; a pipeline run
from a registry lives in `~/.nextflow/assets/nf-core/<name>`, a cache shared by every run of
that pipeline and gone as soon as it is updated. Neither survives zipping the crate and handing
it to someone, which is the case the crate exists for.

Copying is deliberately preferred over the alternative — writing the crate *into* the
directory that holds `main.nf` so those files fall under the crate root. That works for a local
pipeline, but not for a registry one, where the script is in a shared cache you must not write
per-run output into. Copying handles both, keeps run artifacts out of your source tree, and
leaves the output directory as the single thing to zip.

`codeRepository` still records where the workflow came from, and the per-process Software
entities still carry commit-pinned module URLs, so preferring the local copy loses nothing.
The config files are described as Datasets rather than left as undeclared bytes in the payload;
they have no `generatedBy` and nothing consumes them, so they entail as crate **inputs**, which
is what a file that parameterizes the run is. `isPartOf` → the workflow Software keeps them out
of `EVI:outputs`.

Set `includeWorkflow = false` to go back to referencing the script where it sits.

### Precondition: publish with `mode: 'copy'` if you want a portable crate

`contentUrl` being crate-relative means the path is right. It does **not** by itself mean the
bytes travel with the crate, and the plugin cannot tell the difference.

Nextflow's default publish mode is `symlink`. A symlinked published file still lives at a path
under the crate directory, so it still gets a relative `contentUrl` — but its bytes are in
`work/`. Zip that crate and you ship dangling links; delete `work/` and the crate is hollow.

So if the crate is meant to be archived, transferred, or handed to anyone, publish with
`mode: 'copy'`:

```groovy
process {
    publishDir = [ path: { "${params.outdir}/${task.process.tokenize(':').last().toLowerCase()}" },
                   mode: 'copy' ]
}
```

nf-core pipelines already do this — they set `publish_dir_mode = 'copy'` by default — which is
why the example crates in `examples/nf-core/` are self-contained. A hand-written pipeline that
never sets `mode` is not. This is a property of your pipeline, not of the plugin, and it is
worth checking before you publish a crate rather than after.

## Describing directory outputs

A `path 'results'` output publishes as one directory, so the crate gets one Dataset with
`format: unknown`, no checksum and no schema — everything the step produced stays invisible.
`expandDirectories = true` walks it and registers the files inside.

Each expanded file becomes a Dataset with `isPartOf` → the directory and `generatedBy` → the
Computation that produced it. The directory Dataset stays, now with the recursive
`contentSize` of its contents. Expanded files are deliberately *not* added to the producing
Computation's `generated` — the directory already stands for them — but the `generatedBy`
edge means they still count as crate outputs and the evidence graph still walks back.

Files are walked in sorted order and capped at `expandMaxFiles`; hitting the cap logs a
`WARN` naming the directory and the count dropped.

## Inferred schemas

`schemas = true` gives every described csv/tsv an `EVI:Schema` node linked by `evi:schema`.
It's a Groovy port of `fairscape-cli schema infer`
(`fairscape_models.schema.tabular.TabularSchema.infer`), reproducing both layers:

- frictionless's `Detector.detect_schema` — the candidate list (`yearmonth, geopoint,
  duration, geojson, object, array, datetime, time, date, integer, number, boolean, year,
  string`) raced over the first `schemaSampleSize` rows at 90% confidence, the `field{N}`
  naming and dedup rules, and the `any` fallback.
- fairscape's mapping onto the six canonical JSON-Schema types, keeping the frictionless type
  under `source-type` when lossy (`date` → `string`, `year` → `integer`, …).

The document is what the CLI writes minus `fairscapeVersion`, with a deterministic ARK
instead of the CLI's random uuid, so crates stay reproducible across `-resume`.

Two approximations, neither reachable from ordinary tables: `geojson` is recognized
structurally rather than by the full JSON-Schema profile, and `duration` accepts the ISO-8601
designator form but not isodate's `P0003-06-04T12:30:05` calendar form.

**Wide tables.** `schemaArrayThreshold = N` collapses a trailing run of ≥ N same-typed
columns into one spanning-array property (`index: "1::"`, equal `min-items`/`max-items`) —
the shape the hand-written CM4AI embedding schemas use, and the one the CLI's
`build_frictionless_schema` expands again for validation.

csv/tsv only; parquet/HDF5/WFDB/DICOM inference is not ported.

## ARK minting

`ark:{naan}/{prefix}-{slug(name)}-{sha1(sourceId)[0:7]}`:

| Entity | Prefix | Hashed source |
| ------ | ------ | ------------- |
| Root crate | `rocrate` | session id |
| Run Computation | `computation` | session id + `#run` |
| Task Computation | `computation` | task hash |
| Workflow Software | `software` | normalized script path + version |
| Process Software | `software` | defining script path + `#` + process name |
| Nextflow Software | `software` | `nextflow-<version>` |
| Dataset | `dataset` | normalized file path |
| Schema | `schema` | the ARK of the Dataset it describes |
| Container | `container` | content digest, else the image reference |

Deterministic: `-resume` reproduces the same ARKs for unchanged tasks and files. A published
file and its work-directory source share one Dataset ARK.

## Validation

`make verify` validates an emitted crate with `ROCrateV1_2.model_validate`
([`fairscape_models`](https://github.com/fairscape/fairscape-models)) plus a check that every
`ark:` reference resolves in the graph (`nf-fairscape-test/validate_crate.py`).

`tools/parity.sh <crate dir>` diffs the derived artifacts against `fairscape-cli` — see
[DATASHEET.md](DATASHEET.md#parity-with-the-cli).
