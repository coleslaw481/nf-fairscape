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
| Task Computations | `["prov:Activity", "EVI#Computation"]` | One per successful task: `command` = task script, `usedSoftware` = its process Software, `usedDataset` = task input files, `generated` = task output files, `isPartOf` → run Computation. Extra keys: `containerImage`, `identifier` (task hash) |
| Workflow Software | `["prov:Entity", "EVI#Software"]` | The main workflow script (`contentUrl` = repository or normalized path); referenced only by the run Computation |
| Nextflow Software | `["prov:Entity", "EVI#Software"]` | The engine itself, versioned; referenced only by the run Computation |
| Process Software | `["prov:Entity", "EVI#Software"]` | One per process (e.g. `REVERSE`): `description` = the process body source as written in the workflow (unresolved variables — the template; the resolved command lives on each Computation), `contentUrl` = the script/module file defining it, `isPartOf` → workflow Software. Each task Computation's `usedSoftware` points here. All fields can be overridden per process via `ext fairscape: [...]` (see below) |
| Datasets | `["prov:Entity", "EVI#Dataset"]` | One per unique file (workflow inputs, task outputs, published files): `format` = MIME type or extension, `generatedBy` → producing Computation (inverse of `generated`), `contentUrl` = crate-relative path for published files, normalized path/URL otherwise, `contentSize` in bytes |

Dataflow between steps emerges from shared Dataset identifiers: a task consuming another
task's output references the same `EVI:Dataset` ARK. Prospective-workflow entities
(WRROC's `FormalParameter`, `HowToStep`, `ControlAction`, …) have no EVI equivalent and
are intentionally not modeled.

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

Identifiers are deterministic: `-resume` reproduces the same ARKs for unchanged tasks and
files. A published file and its work-directory source share a single Dataset ARK.

## Validation

The acceptance test validates emitted crates with the
[`fairscape_models`](https://github.com/fairscape/fairscape-models) pydantic schema
(`ROCrateV1_2.model_validate`) plus a referential-integrity check that every `ark:` reference
resolves within the graph. See `nf-fairscape-test/validate_crate.py` and `make verify`.
