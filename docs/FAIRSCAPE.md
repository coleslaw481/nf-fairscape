# FAIRSCAPE EVI RO-Crate format

`nf-fairscape` writes one `ro-crate-metadata.json` per run. The `@context` is the
FAIRSCAPE default context: [schema.org](https://schema.org/) as `@vocab`, plus the
[EVI](https://w3id.org/EVI#) and [W3C PROV-O](http://www.w3.org/ns/prov#) namespaces and
`@id`-typed EVI terms (`usedSoftware`, `usedDataset`, `generated`, `generatedBy`).

## Graph structure

| Node | `@type` | Source in Nextflow |
| ---- | ------- | ------------------ |
| `ro-crate-metadata.json` descriptor | `CreativeWork` | `conformsTo` [RO-Crate 1.2](https://w3id.org/ro/crate/1.2), `about` → root |
| Root crate | `["Dataset", "EVI#ROCrate"]` | `conformsTo` [FAIRSCAPE profile 0.1](https://w3id.org/fairscape/profile/0.1); name/description/version from workflow manifest, `hasPart` lists every node below |
| Run Computation | `["prov:Activity", "EVI#Computation"]` | The whole run: `command` = launch command line, `parameter` = folded params, `usedSoftware` = workflow script + Nextflow engine, `usedDataset` = external workflow inputs, `generated` = published/declared outputs. Extra keys: `startTime`, `endTime`, `nextflowVersion`, `identifier` (session id) |
| Task Computations | `["prov:Activity", "EVI#Computation"]` | One per successful task: `command` = task script, `usedSoftware` = its process Software, `usedDataset` = task input files, `generated` = task output files, `isPartOf` → run Computation. Extra keys: `containerImage`, `identifier` (task hash), and `containerDigest` when `containerProvenance` is on |
| Workflow Software | `["prov:Entity", "EVI#Software"]` | The main workflow script (`contentUrl` = repository or normalized path); referenced only by the run Computation |
| Nextflow Software | `["prov:Entity", "EVI#Software"]` | The engine itself, versioned; referenced only by the run Computation |
| Process Software | `["prov:Entity", "EVI#Software"]` | One per process (e.g. `REVERSE`): `description` = the process body source as written in the workflow (unresolved variables — the template; the resolved command lives on each Computation), `contentUrl` = the script/module file defining it, `isPartOf` → workflow Software. Each task Computation's `usedSoftware` points here. All fields can be overridden per process via `ext fairscape: [...]` (see below). With `containerProvenance` on, gains `containerImage`/`containerDigest`/`containerImageId` — the built artifact this software was executed from (see [Container provenance](#container-provenance)) |
| Datasets | `["prov:Entity", "EVI#Dataset"]` | One per unique file (workflow inputs, task outputs, published files): `format` = MIME type or extension, `generatedBy` → producing Computation (inverse of `generated`), `contentUrl` = crate-relative path for published files, normalized path/URL otherwise, `contentSize` in bytes. Opt-in extras: `md5` (`checksums`), recursive `contentSize` for directories (`contentSizes`), `isPartOf` → containing directory for expanded files (`expandDirectories`), `evi:schema` → its inferred Schema (`schemas`) |
| Schemas | `EVI:Schema` | One per described csv/tsv when `schemas` is on: the `fairscape-cli schema infer` document, referenced from its Dataset by `evi:schema` (see [Inferred schemas](#inferred-schemas)) |

## Inverse relationships

Unless `fairscape.linkInverses = false`, the crate is completed against the
`owl:inverseOf` pairs the EVI ontology declares — the Groovy equivalent of
`fairscape augment link-inverses`. A relationship stated once is then present on both
entities:

| Stated by the renderer | Entailed by the linker |
| ---------------------- | ---------------------- |
| `Dataset.generatedBy` → Computation | `Computation.generated` → Dataset |
| `Computation.usedDataset` → Dataset | `Dataset.datasetUsedBy` → Computation |
| `Computation.usedSoftware` → Software | `Software.softwareUsedBy` → Computation |

This is what fills `generated` for the files found inside a published directory: the
renderer links them only in the `generatedBy` direction (the directory already stands for
them in `generated`), and the entailment supplies the other half. On the Cell Maps
pipeline it takes `HIERARCHY.generated` from 1 entry to 24.

The full table is the 18 pairs in `InverseLinker.INVERSE_PAIRS`, carried as data rather
than re-derived with rdflib and a SPARQL query at runtime — the ontology is a fixed,
versioned artifact, so the answer is fixed too. Pairs are applied in ascending order of
the first property and entities in `@graph` order, which makes the augmented crate
byte-stable across runs; the CLI's order comes from rdflib's result set, which is not.

A Dataset published by the run picks up the run-level Computation in `generatedBy`
alongside its task, because the run Computation lists published files in `generated`.
That is the correct entailment of what the crate states, and matches the CLI exactly.

## D4D / LinkML export

Unless `fairscape.linkml = false`, the crate root is translated into a D4D (Datasheets
for Datasets) document at `ro-crate-linkml.yaml` — the Groovy equivalent of
`fairscape build linkml`. Despite the file name the LinkML artifact *is* the D4D
translation: `ROCRATE_TO_D4D_MAPPING` re-expresses the root's schema.org and Croissant
RAI properties in D4D's vocabulary (`rai:dataBiases` → `known_biases`,
`rai:dataUseCases` → `purposes`/`tasks`/`intended_uses`, `prohibitedUses` →
`discouraged_uses`/`prohibited_uses`, …). Only the root is read; D4D describes a dataset,
not a provenance graph.

`PyYaml` reproduces `yaml.dump`'s defaults — sorted keys, block style, folding at column
80, plain scalars unless the content would be ambiguous — because the output is diffed
against the CLI's byte for byte.

One inherited quirk is worth knowing about: the D4D `bytes` field parses `contentSize`
with **1024-based** suffixes while the datasheet formats it with **1000-based** ones, so a
human-readable `contentSize` does not round-trip exactly (`945.6 MB` reads back as
991,533,465). Reproduced rather than corrected — the point of the port is to agree with
the CLI.

## Opt-in enrichment

Everything in this section is **off by default**, and a run that sets none of it emits the
same crate the plugin has always emitted. Each option buys metadata with I/O the plugin
would otherwise not perform, and that trade is the user's to make — a directory walk is
free on a local disk and a pile of LIST/HEAD requests when the crate directory is an S3
bucket.

(The derived-artifact steps — `datasheet`, `evidenceGraph`, `linkInverses`, `linkml` — are
a different category and default to *on*: they read the crate JSON and nothing else, so
they cost no I/O over the data. `evidenceGraph` and `linkInverses` do rewrite the crate.)

| Option | Cost | Buys |
| ------ | ---- | ---- |
| `expandDirectories` | one walk per published directory | a Dataset per file inside it |
| `checksums` | one full read per described file | `md5` (AI-Ready *verifiable*) |
| `contentSizes` | one walk per directory + one over the crate directory | directory and crate-total `contentSize` (AI-Ready *statistics*) |
| `schemas` | reads `schemaSampleSize` rows per csv/tsv | an `EVI:Schema` per table (AI-Ready *standards*) |
| `containerProvenance` | one `image inspect` per distinct image | `containerDigest` on tasks, container fields on process Software |

With `contentSizes` the root carries `contentSize` — the crate directory's total payload,
formatted the way the datasheet displays it. It is measured *before* the datasheet and
evidence graph are written next to the crate, so those few hundred KB are not counted.
Without it the AI-Ready scorer sums the per-entity `contentSize` values instead, which
double counts an expanded directory and the files inside it.

One unconditional change came with this work: a Dataset that is a directory is now
described as `Directory 'x' produced by …` rather than `File 'x' produced by …`. It was
simply wrong before, and it only affects free text.

Dataflow between steps emerges from shared Dataset identifiers: a task consuming another
task's output references the same `EVI:Dataset` ARK. Prospective-workflow entities
(WRROC's `FormalParameter`, `HowToStep`, `ControlAction`, …) have no EVI equivalent and
are intentionally not modeled.

Unless `fairscape.evidenceGraph = false`, the root additionally carries:

| Field | Meaning |
| ----- | ------- |
| `https://w3id.org/EVI#outputs` | Datasets no computation consumed — the run's terminal outputs |
| `https://w3id.org/EVI#inputs` | Samples, consumed datasets nothing generated, and standalone datasets |
| `localEvidenceGraph` | `{"@id": "provenance-graph.html"}`, the generated viewer |

These are derived from the graph exactly as `fairscape build subcrate` derives them, and
they are what lets the evidence graph be rooted at the crate. See
[DATASHEET.md](DATASHEET.md).

## Container provenance

A task Computation always carries `containerImage` — whatever string the `container`
directive resolved to. That is usually a tag, and a tag is a label, not an identity: the
same `cm4ai/cellmaps_coembedding:1.5.0` can be re-pushed tomorrow over different bits and
the two crates will look identical. `fairscape.containerProvenance = true` asks the
container engine what the reference actually resolved to:

```groovy
fairscape {
    containerProvenance = true
    // containerEngineCommand = 'podman'   // default: whichever engine the run enabled
}
```

Each distinct image is inspected once per run, and the crate gains:

| Entity | Field | Value |
| ------ | ----- | ----- |
| Container (one per distinct image) | `containerImage` / `containerDigest` / `containerImageId` | the image's reference, content digest and local id |
| Task Computation | `usedContainer` | reference to the Container entity for the image that task ran in |
| Task Computation | `containerDigest` | `repo@sha256:…` for the image that task ran in |
| Process Software | `containerImage` | the reference every task of that process used |
| Process Software | `containerDigest` | its content digest |
| Process Software | `containerImageId` | the engine's local image id |

The Container entity is the typed home for this information: it is an EVI `Container`
(`fairscape_models/container.py`, `@type` `['prov:Entity', 'https://w3id.org/EVI#Container']`),
listed in the root `hasPart`, with an ARK minted from the content digest when one exists
(so the identifier names the bits, not the tag) and from the reference otherwise. Task
Computations point at it via `usedContainer`, which `fairscape_models` folds into
`prov:used` alongside `usedSoftware`/`usedDataset`. The flat `container*` keys on the
Computations and process Software are kept as well, so existing consumers keep working.

Putting the image on the **process Software** is the point of the option. `contentUrl` on
that entity names the tool's source repository, which says what the software *is*; the
container is what was actually executed. Recording it beside the tool, rather than only on
each activity, is the difference between "this crate names a GitHub URL" and "this crate
names the artifact that produced these files".

A process whose tasks used different images (a dynamic `container` directive) gets no
image on its Software entity — the per-task Computations already carry the truth, and
picking one arbitrarily would be a claim the run does not support.

**A digest pins content, not availability.** Under Docker's containerd image store `Id`
*is* the manifest digest, so `containerDigest` and `containerImageId` are the same string —
for a pulled image (`cm4ai/cellmaps_ppidownloader:0.2.2` → `sha256:85b359d3…`, matching
what Docker Hub serves) and equally for one built locally that exists on a single machine.
Under the classic image store they differ and a never-pushed image reports no repo digest
at all. So a digest identifies exactly which bits ran; whether anyone else can obtain them
depends on the repository having been pushed, which nothing visible locally can confirm.

Everything here is best-effort: no engine enabled, engine not on the PATH, image already
deleted, inspect times out — each leaves the crate exactly as it would have been. Only
`docker` and `podman` are auto-detected; Singularity and Apptainer run image *files* with
no daemon to interrogate, so they are not guessed at.

### If your tasks die with an argparse usage dump

Not a plugin problem, but you will meet it the first time you point a workflow at
single-purpose community tool images, and the symptom does not name the cause. Images built
to be run as `docker run image --help` set an entrypoint:

```dockerfile
ENTRYPOINT ["mytoolcmd.py"]
```

Nextflow launches a task as `docker run <image> /bin/bash -ue .command.sh`, which with that
entrypoint becomes `mytoolcmd.py /bin/bash -ue .command.sh` — the tool parses the launcher
as its own arguments and exits non-zero, usually printing its usage. Nextflow can override
the entrypoint, but as of 25.10 only through the environment:

```bash
export NXF_CONTAINER_ENTRYPOINT_OVERRIDE=true
```

There is no `docker.entrypointOverride` config setting — `ContainerConfig.entrypointOverride()`
reads `SysEnv` and `DockerConfig` does not override it. The alternative, if you would rather
not depend on an environment variable that breaks the run when forgotten, is a two-line
wrapper image that resets `ENTRYPOINT []`.

## Describing the tool a process runs

By default the process Software entity only reflects the Nextflow process (name, body
source, script path) — Nextflow itself knows nothing about the underlying tool. To record
the actual software, annotate the process with Nextflow's
[`ext` directive](https://www.nextflow.io/docs/latest/reference/process.html#ext) (custom
process directives are not allowed, so `ext` is the supported namespace for user metadata):

```nextflow
process REVERSE {
    ext fairscape: [
        softwareName       : 'tac',
        softwareVersion    : '8.32',
        softwareAuthor     : 'Jay Lepreau, David MacKenzie (GNU coreutils)',
        softwareDescription: 'A command-line utility that reverses the order of lines in a text file.',
        softwareUrl        : 'https://www.gnu.org/software/coreutils/tac',
        softwareFormat     : 'application/x-executable',
        softwareKeywords   : ['coreutils', 'text-processing']
    ]
    ...
}
```

The value of `ext.fairscape` **must be a map** (`[key: value, ...]`). Every key is optional
and overrides exactly one field of that process's `EVI:Software` entity; missing keys keep
the process-derived default shown below.

| `ext.fairscape` key   | Type            | Software field | Default when omitted |
| --------------------- | --------------- | -------------- | -------------------- |
| `softwareName`        | String          | `name`         | the process name (e.g. `REVERSE`) |
| `softwareVersion`     | String          | `version`      | workflow manifest version, else git commit id |
| `softwareAuthor`      | String          | `author`       | crate author (`fairscape.author` → manifest author → OS user) |
| `softwareDescription` | String          | `description`  | the process body source as written in the workflow (must be ≥10 chars, else a generated fallback) |
| `softwareUrl`         | String (URL or local path) | `contentUrl` | the script/module file that defines the process |
| `softwareFormat`      | String (MIME type or label) | `format` | `nextflow` |
| `softwareKeywords`    | List of strings (a bare string is accepted and wrapped in a list) | `keywords` | the crate keywords (`fairscape.keywords`) |

The process name itself is still preserved on every task Computation (its `description` and
its `usedSoftware` link both reference the process), so nothing is lost by renaming the
Software entity to the tool it runs.

The same values can be supplied from `nextflow.config` without editing the workflow —
useful for annotating third-party pipelines:

```groovy
process {
    withName: 'REVERSE' {
        ext.fairscape = [ softwareName: 'tac', softwareVersion: '8.32' ]
    }
}
```

### Validation warnings

The plugin never fails a run over a bad annotation — a crate that can't be built is skipped,
not fatal — but it does emit a `WARN` to the console and `.nextflow.log` when it finds an
`ext.fairscape` it will silently ignore, so mistakes don't pass unnoticed:

- **Not a map.** `ext fairscape: ['made-up-property']` (a list) or any non-map value:
  `process 'X' — ext.fairscape must be a map like [softwareName: 'tac', ...] but was a
  ArrayList; the annotation will be ignored`.
- **Unrecognized keys.** `ext fairscape: [madeUpProperty: 'x']`:
  `process 'X' — ext.fairscape has unrecognized key(s) [madeUpProperty] that will be ignored;
  supported keys are [softwareName, softwareVersion, ...]`. Known keys in the same map are
  still applied; only the unknown ones are dropped.

## Adding workflow-level metadata

The process `ext` directive annotates one Software entity. To add fields to the **root**
crate entity (the `["Dataset", "EVI#ROCrate"]` node describing the run as a whole), set
`fairscape.metadata` in `nextflow.config` to a map. Each key becomes a property on the root
node:

```groovy
fairscape {
    author  = 'Jane Roe'
    metadata = [
        associatedPublication: 'https://doi.org/10.1234/example',
        funder               : 'NIH Bridge2AI (OT2OD032742)',
        principalInvestigator: 'Jane Roe',
        citation             : 'Roe J. et al. Example Pipeline. 2026.',
        conditionsOfAccess   : 'Available for non-commercial research use only.'
    ]
}
```

Use this for the long tail of root fields that have no dedicated `fairscape.*` option. The
[FAIRSCAPE profile](https://w3id.org/fairscape/profile/0.1) root entity already recognizes
many such fields — `associatedPublication`, `citation`, `funder`, `principalInvestigator`,
`publisher`, `contactEmail`, `conditionsOfAccess`, `copyrightNotice`, `ethicalReview`, and
the Croissant `rai:*` responsible-AI fields among them — and any other key is preserved as an
extra property. Values should match the type the field expects (`keywords`, for instance, is
a list of strings).

Common fields have dedicated options that are easier to set and are documented in the config
scope: `author`, `description`, `keywords`, `license`, and `organization` (→ `publisher`).
`fairscape.metadata` is merged **on top of** the computed root, so a key set both ways takes
its value from `metadata`. The four structural keys the plugin manages — `@id`, `@type`,
`conformsTo`, and `hasPart` — cannot be overridden; supplying one in `metadata` is ignored
with a `WARN`.

## Describing directory outputs

Nextflow publishes a `path 'results'` output as a single directory, so the crate gets one
Dataset with `format: unknown`, no checksum and no schema — everything the step actually
produced stays invisible. `fairscape.expandDirectories = true` walks each published
directory and registers the files inside it:

```groovy
fairscape {
    expandDirectories = true
    expandPatterns    = ['**/*.tsv', '**/*.csv', '**/*.json']  // omit to describe every file
    expandMaxFiles    = 1000
}
```

Each expanded file becomes a Dataset with `isPartOf` → the directory's Dataset and
`generatedBy` → the Computation that produced the directory. The directory Dataset stays,
now carrying the recursive `contentSize` of its contents. Expanded files are deliberately
*not* added to the producing Computation's `generated` list — the directory already stands
for them there — but the `generatedBy` edge means they still appear as crate outputs and the
evidence graph still walks back from them to the step that made them.

Files are walked in sorted order and capped at `expandMaxFiles` per directory; hitting the
cap logs a `WARN` naming the directory and how many files were left out.

## Inferred schemas

`fairscape.schemas = true` gives every described csv/tsv an `EVI:Schema` node, linked from
its Dataset by `evi:schema`. This is a Groovy port of `fairscape-cli schema infer`
(`fairscape_models.schema.tabular.TabularSchema.infer`), which delegates column typing to
frictionless's `describe()`; `nextflow/prov/schema/TabularSchemaInferrer.groovy` reproduces
both layers:

- frictionless's `Detector.detect_schema` — the candidate list (`yearmonth, geopoint,
  duration, geojson, object, array, datetime, time, date, integer, number, boolean, year,
  string`) raced against the first `schemaSampleSize` rows at the 90% confidence threshold,
  the `field{N}` naming and dedup rules, and the `any` fallback for a column nothing wins.
- fairscape's mapping of that type onto the six canonical JSON-Schema types, keeping the
  frictionless type under `source-type` when the mapping is lossy (`date` → `string`,
  `year` → `integer`, `geopoint` → `array`, …).

The emitted document is what the CLI writes, minus `fairscapeVersion` (a Python package
version the plugin has no business asserting; pydantic fills it in on read) and with a
deterministic ARK in place of the CLI's random uuid suffix, so crates stay reproducible
across `-resume`.

Two approximations, neither reachable from ordinary scientific tables: `geojson` cells are
recognized structurally rather than by running the full GeoJSON JSON-Schema profile, and
`duration` accepts the ISO-8601 designator form but not isodate's alternate
`P0003-06-04T12:30:05` calendar form.

**Wide tables.** A 1024-dimension embedding would otherwise emit 1024 scalar properties.
`schemaArrayThreshold = N` collapses a trailing run of at least N identically-typed columns
into one spanning-array property — `index: "1::"`, `items`, equal `min-items`/`max-items` —
the shape the hand-written CM4AI embedding schemas use, and the one the CLI's
`build_frictionless_schema` expands again for row validation.

Only `csv` and `tsv` are supported; the CLI's parquet/HDF5/WFDB/DICOM inference is not
ported. A file that cannot be described logs a `WARN` and keeps its Dataset without a schema.

## ARK minting

`ark:{naan}/{prefix}-{slug(name)}-{sha1(sourceId)[0:7]}`, where the hashed source id is:

| Entity | Prefix | Hashed source id |
| ------ | ------ | ---------------- |
| Root crate | `rocrate` | session id |
| Run Computation | `computation` | session id + `#run` |
| Task Computation | `computation` | task hash |
| Workflow Software | `software` | normalized script path + version |
| Process Software | `software` | defining script path + `#` + process name |
| Nextflow Software | `software` | `nextflow-<version>` |
| Dataset | `dataset` | normalized file path |
| Schema | `schema` | the ARK of the Dataset it describes |

Identifiers are deterministic: `-resume` reproduces the same ARKs for unchanged tasks and
files. A published file and its work-directory source share a single Dataset ARK.

## Validation

The acceptance test validates emitted crates with the
[`fairscape_models`](https://github.com/fairscape/fairscape-models) pydantic schema
(`ROCrateV1_2.model_validate`) plus a referential-integrity check that every `ark:` reference
resolves within the graph. See `nf-fairscape-test/validate_crate.py` and `make verify`.

The derived artifacts have their own oracle: `tools/parity.sh <crate dir>` diffs the
datasheet and evidence graph against the Python `fairscape-cli` implementation they were
ported from. See [DATASHEET.md](DATASHEET.md#parity-with-the-cli).
