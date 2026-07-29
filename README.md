# nf-fairscape

Nextflow plugin that renders a [FAIRSCAPE](https://fairscape.github.io/) EVI RO-Crate for each pipeline run. It is a fork of [nf-prov](https://github.com/nextflow-io/nf-prov) that emits the [EVI ontology](https://w3id.org/EVI#) provenance model natively, instead of a Workflow Run RO-Crate.

The emitted `ro-crate-metadata.json` conforms to:

- [RO-Crate 1.2](https://w3id.org/ro/crate/1.2)
- [FAIRSCAPE profile 0.1](https://w3id.org/fairscape/profile/0.1)
- [EVI ontology](https://w3id.org/EVI#) (Evidence Graph vocabulary), with [W3C PROV-O](http://www.w3.org/ns/prov#) typing
- [schema.org](https://schema.org/) as the base vocabulary

Each successful task execution becomes an `EVI:Computation` linked to a parent run-level Computation via `isPartOf`. Files become `EVI:Dataset` entities with bidirectional `generated`/`generatedBy` edges, and the workflow script and Nextflow engine become `EVI:Software`. See [docs/FAIRSCAPE.md](docs/FAIRSCAPE.md) for the full mapping and ARK identifier rules.

## Requirements

| Version | Minimum Nextflow version |
| ------- | ------------------------ |
| 0.1.x   | 25.10 |

## Getting Started

Install the plugin locally (until it is published to the plugin registry):

```bash
make install
```

Then enable it in your Nextflow config:

```groovy
plugins {
  id 'nf-fairscape'
}

outputDir = params.outdir

fairscape {
  file = "${params.outdir}/ro-crate-metadata.json"
  overwrite = true
  author = "Jane Doe"
  keywords = ['genomics', 'my-project']
  license = "https://spdx.org/licenses/MIT"
}
```

You do not need to modify your pipeline script. When the run completes successfully, the plugin writes the EVI RO-Crate metadata file. The crate directory is the parent directory of `file` — set it inside your workflow `outputDir` so published outputs get crate-relative `contentUrl`s.

Alongside the crate it also writes the artifacts you would otherwise get from `fairscape build` — an interactive provenance graph and an HTML datasheet:

```
results/
  ro-crate-metadata.json     # the crate
  provenance-graph.json      # evidence graph rooted at the crate
  provenance-graph.html      # interactive, self-contained viewer
  ro-crate-datasheet.html    # the datasheet
  ai_ready_score.json        # AI-Readiness rubric behind the datasheet
  ro-crate-linkml.yaml       # the crate root as a D4D (Datasheets for Datasets) document
```

and completes the crate's `owl:inverseOf` pairs, so each Computation's `generated` lists
every file that names it in `generatedBy` — including the files inside a published
directory, which the renderer links in one direction only.

Set `datasheet`, `evidenceGraph`, `linkml` or `linkInverses` to `false` to skip any of
them. See [docs/DATASHEET.md](docs/DATASHEET.md).

Every configuration option has a fallback (workflow manifest, then a generated value), so the crate is valid even with no `fairscape` block at all.

For a minimal end-to-end demo, see [examples/reverse-list](examples/reverse-list); for a multi-step provenance chain with saved intermediates, see [examples/letters-chain](examples/letters-chain). New to Groovy/Nextflow plugins? Read [docs/WALKTHROUGH.md](docs/WALKTHROUGH.md) for a guided tour of the codebase.

## Configuration

| Option | Default | Description |
| ------ | ------- | ----------- |
| `enabled` | `true` | Create the crate at the end of the run. |
| `file` | `ro-crate-metadata.json` | Output file; its parent directory is the crate directory. |
| `overwrite` | `false` | Overwrite an existing metadata file. |
| `patterns` | `[]` | Glob patterns to filter which published files are included. |
| `naan` | `59853` | ARK Name Assigning Authority Number used when minting identifiers. |
| `author` | manifest author → local user | Author recorded on the crate and its entities. |
| `description` | manifest description → generated | Crate description (min 10 characters). |
| `keywords` | `['nextflow', 'workflow']` | Crate/dataset keywords. |
| `license` | manifest license → Apache-2.0 URI | License URL (use an [SPDX](https://spdx.org/licenses/) URI). |
| `organization` | none | Optional publisher organization name. |
| `metadata` | `[:]` | Map of extra fields merged into the root crate entity (e.g. `associatedPublication`, `funder`, `principalInvestigator`). See [Annotating tools and metadata](#annotating-tools-and-metadata). |
| `datasheet` | `true` | Render `ro-crate-datasheet.html` and `ai_ready_score.json` after the run. |
| `evidenceGraph` | `true` | Build `provenance-graph.json`/`.html` and record `EVI:inputs`/`EVI:outputs` on the crate root. |
| `linkInverses` | `true` | Complete every `owl:inverseOf` pair EVI declares, so a relationship stated once appears on both entities. |
| `linkml` | `true` | Write `ro-crate-linkml.yaml`, the crate root translated into a D4D document. |
| `published` | `false` | Render the datasheet as a published release (identifiers become resolver links). |
| `expandDirectories` | `false` | Describe the files *inside* a published directory as Datasets of their own. |
| `expandPatterns` | `[]` (all files) | Glob patterns limiting which files inside a published directory are expanded. |
| `expandMaxFiles` | `1000` | Cap on files expanded per published directory; the overflow is dropped with a warning. |
| `checksums` | `false` | Record an `md5` on every Dataset that resolves to a readable local file. |
| `contentSizes` | `false` | Also measure directories: recursive `contentSize` per directory Dataset, plus the crate total on the root. Regular files are always sized. |
| `schemas` | `false` | Infer an `EVI:Schema` per described csv/tsv and link it via `evi:schema`. |
| `schemaPatterns` | `['**/*.csv', '**/*.tsv']` | Glob patterns selecting which described files get a schema. |
| `schemaSampleSize` | `100` | Data rows read when inferring a schema (the frictionless default). |
| `schemaArrayThreshold` | `0` (off) | Collapse a trailing run of ≥ N same-typed columns into one spanning-array property. |
| `schemaMaxFiles` | `500` | Cap on schemas inferred per run; the overflow is skipped with a warning. |

Note: Nextflow rejects an *empty* `fairscape { }` block ("Unknown config attribute") — either set at least one option or omit the block entirely.

### Describing directory outputs

A process whose output is a whole directory (`output: path 'results'`) contributes exactly **one** Dataset — with no size, format, checksum or schema, because a directory has none. `expandDirectories = true` walks each published directory and gives every file inside it its own Dataset, `generatedBy` the task that produced the directory and `isPartOf` the directory's Dataset:

```groovy
fairscape {
    expandDirectories = true
    expandPatterns    = ['**/*.tsv', '**/*.csv', '**/*.json']   // omit to describe every file
    checksums         = true
    contentSizes      = true
    schemas           = true
}
```

**All of these are off by default and the crate is unchanged without them.** They
each cost extra I/O — a directory walk, a full read per file for `md5`, a parse per
tabular file — which is cheap on a local filesystem and much less so when the crate
directory lives in an object store. Turn on what you want; a run with no `fairscape`
block at all still produces the same valid crate it always did.

### Inferred schemas

`schemas = true` runs a Groovy port of `fairscape-cli schema infer` over every described csv/tsv: frictionless's candidate-type detection (integer/number/boolean/date/…, `source-type` kept when the canonical mapping is lossy) producing the same schema document the CLI writes, as an `EVI:Schema` node in the crate graph. Wide tables — a 1024-dimension embedding, say — collapse into a single spanning-array property with `schemaArrayThreshold`, the shape the hand-written CM4AI embedding schemas use.

## Annotating tools and metadata

Two annotation hooks let you enrich the crate beyond what Nextflow knows automatically:

- **Per process** — describe the actual tool a process runs (name, version, author, URL, keywords…) with the `ext fairscape: [...]` directive, in the pipeline or from config.
- **Per run** — add root-level fields (publication, funder, PI, access terms…) with the `fairscape.metadata` config map.

Both are documented, with the full list of supported keys and validation behavior, in [docs/FAIRSCAPE.md](docs/FAIRSCAPE.md#describing-the-tool-a-process-runs).

## Identifiers

All entities are minted deterministic [ARK](https://arks.org/) identifiers of the form
`ark:{naan}/{prefix}-{name-slug}-{sha1-hash[0:7]}`, hashed from stable sources (task hash, normalized file path, session id). Re-running with `-resume` reproduces identical identifiers for unchanged tasks and files. The default NAAN `59853` marks locally-minted, unregistered identifiers; set `naan` to your registered NAAN when publishing to a FAIRSCAPE server.

## Validation

`nf-fairscape-test/validate_crate.py` validates an emitted crate against the
[`fairscape_models`](https://github.com/fairscape/fairscape-models) pydantic schema and checks referential integrity:

```bash
make verify
```

`tools/parity.sh <crate dir>` diffs the datasheet and provenance graph against
the Python `fairscape-cli` output — see [docs/DATASHEET.md](docs/DATASHEET.md#parity-with-the-cli).

## Differences from nf-prov

- Single output format (`fairscape` scope, no `prov.formats` nesting); the BCO/DAG/GEXF/WRROC renderers were removed — use nf-prov itself for those.
- Files are referenced (via `contentUrl`), never copied into the crate directory.
- Successful native (`exec:`) tasks are included as Computations; upstream drops them on fresh runs.
- The observer/renderer framework (`ProvObserver`, `Renderer`, `ProvHelper`) is kept intact from nf-prov to ease rebasing onto upstream.

## Limitations

- Only file (`path`) channel values become Datasets; scalar (`val`) inputs are visible only through the task `command` and run-level `parameter` list (same limitation as nf-prov).
- EVI models a single timestamp (`dateCreated`) and successful runs only; per-task start/end times and container images are carried as extra keys (`startTime`, `endTime`, `containerImage`), which the FAIRSCAPE schema accepts but does not define.

## License

Apache-2.0, same as nf-prov. This is a modified fork of [nextflow-io/nf-prov](https://github.com/nextflow-io/nf-prov) v1.7.0.
