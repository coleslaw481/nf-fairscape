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

Note: Nextflow rejects an *empty* `fairscape { }` block ("Unknown config attribute") — either set at least one option or omit the block entirely.

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
