# nf-fairscape

## Summary

Nextflow plugin that writes a [FAIRSCAPE](https://fairscape.github.io/) EVI RO-Crate for
each pipeline run — the [EVI ontology](https://w3id.org/EVI#) provenance model emitted
natively, rather than converted from another provenance format after the fact.

The crate conforms to [RO-Crate 1.2](https://w3id.org/ro/crate/1.2), the
[FAIRSCAPE profile 0.1](https://w3id.org/fairscape/profile/0.1) and
[EVI](https://w3id.org/EVI#), with [PROV-O](http://www.w3.org/ns/prov#) typing over
[schema.org](https://schema.org/).

Each successful task becomes an `EVI:Computation` under a run-level Computation. Files
become `EVI:Dataset` entities with `generated`/`generatedBy` edges; the script, the engine
and each process become `EVI:Software`. Full mapping: [docs/FAIRSCAPE.md](docs/FAIRSCAPE.md).

| Plugin version | Minimum Nextflow |
| -------------- | ---------------- |
| 0.1.x | 25.10 |

## Get started

Requires Nextflow 25.10 or later. Enable the plugin in `nextflow.config` and Nextflow
fetches it from the [plugin registry](https://registry.nextflow.io) on the first run:

```groovy
plugins { id 'nf-fairscape@0.1.0' }
```

Until the first registry release lands, build and install it locally instead — same
result, one extra step ([CONTRIBUTING.md](CONTRIBUTING.md)):

```bash
make install
nextflow run <pipeline> -plugins nf-fairscape@0.1.0
```

A configured run looks like this:

```groovy
plugins { id 'nf-fairscape@0.1.0' }

outputDir = params.outdir

fairscape {
    file      = "${params.outdir}/ro-crate-metadata.json"
    overwrite = true
    author    = 'Jane Doe'
    keywords  = ['genomics', 'my-project']
    license   = 'https://spdx.org/licenses/MIT'
}
```

No pipeline changes needed. On success you get:

```
results/
  ro-crate-metadata.json     # the crate
  provenance-graph.json      # evidence graph rooted at the crate
  provenance-graph.html      # interactive, self-contained viewer
  ro-crate-datasheet.html    # datasheet
  ai_ready_score.json        # AI-Readiness rubric behind it
  ro-crate-linkml.yaml       # crate root as a D4D document
  workflow/                  # the script and configs that produced all of it
```

Everything has a fallback (manifest, then a generated value), so the crate is valid with no
`fairscape` block at all.

One thing that is on your pipeline rather than the plugin: **if the crate is going to be
archived or shared, publish with `mode: 'copy'`.** Nextflow's default publish mode is
`symlink`, and a symlinked published file gets a crate-relative `contentUrl` like any other —
the path is right, but the bytes are still in `work/`, so zipping the crate ships dangling
links. nf-core pipelines already set `publish_dir_mode = 'copy'`. Files the crate describes but
does not contain (work-directory intermediates) carry
[`localPath`](docs/FAIRSCAPE.md#where-a-datasets-bytes-are-contenturl-vs-localpath) instead of a
`contentUrl`, so the graph keeps them without claiming they are retrievable.

## Examples

Every example is a self-contained pipeline plus the config that switches the plugin on.
Install the plugin, then run one:

```bash
make install
cd examples/letters-chain && nextflow run . -plugins nf-fairscape@0.1.0
```

That writes `results/ro-crate-metadata.json` and the derived artifacts next to it — open
`results/provenance-graph.html` to see the run's evidence graph.

Runnable demos: [examples/reverse-list](examples/reverse-list) (minimal),
[examples/letters-chain](examples/letters-chain) (multi-step chain). New to Nextflow
plugins? [docs/WALKTHROUGH.md](docs/WALKTHROUGH.md).

Against real pipelines: [examples/nf-core](examples/nf-core) runs five released nf-core
pipelines unmodified and checks the crates they produce — the regression set the plugin is
hardened against. Because they are unmodified, none of them can carry an `ext fairscape:`
block; [examples/fastquorum-like](examples/fastquorum-like) and
[examples/bamtofastq-like](examples/bamtofastq-like) are the other half of that — real
bioinformatics (fgbio duplex UMI consensus calling, and samtools BAM→FASTQ conversion)
after [nf-core/fastquorum](https://nf-co.re/fastquorum/2.0.0/docs/usage/) and
[nf-core/bamtofastq](https://nf-co.re/bamtofastq/2.2.1/docs/usage/), flattened into one
annotated `main.nf` each, on their own test data. [examples/cycle-repro](examples/cycle-repro) and
[examples/edge-cases](examples/edge-cases) are the two smallest failures those runs turned up,
reduced to something that finishes in seconds.

## Adding your own metadata

Nextflow knows what ran. It doesn't know who funded it, what paper it belongs to, or which
tool is inside a process. Two hooks cover that; every key either accepts is listed in
[docs/CONFIGURATION.md](docs/CONFIGURATION.md#fairscapemetadata).

**Per run** — `fairscape.metadata` is merged onto the root crate entity. Any field the
[FAIRSCAPE profile](https://w3id.org/fairscape/profile/0.1) declares works, including the
[Croissant RAI](http://mlcommons.org/croissant/RAI/1.0) `rai:*` properties (quote keys
containing a colon — Groovy needs it):

```groovy
fairscape {
    author       = 'Jane Roe'
    organization = 'Example Institute'          // -> publisher
    license      = 'https://spdx.org/licenses/CC-BY-4.0'

    metadata = [
        identifier           : 'https://doi.org/10.1234/example',
        principalInvestigator: 'Jane Roe',
        funder               : 'NIH Bridge2AI (OT2OD032742)',
        associatedPublication: 'https://doi.org/10.1234/example-paper',
        conditionsOfAccess   : 'Non-commercial research use only.',
        'rai:dataLimitations': 'Not validated for clinical use.'
    ]
}
```

It merges on top of the computed root, so it can also override `name`, `description`,
`keywords` and the rest — the dedicated options are shortcuts. `@id`, `@type`, `conformsTo`
and `hasPart` are refused with a `WARN`.

**Per process** — `ext fairscape` describes the tool a process actually runs, replacing the
defaults on its Software entity:

```nextflow
process REVERSE {
    ext fairscape: [
        softwareName   : 'tac',
        softwareVersion: '8.32',
        softwareUrl    : 'https://www.gnu.org/software/coreutils/tac',
        softwareAuthor : 'Jay Lepreau, David MacKenzie (GNU coreutils)'
    ]
    ...
}
```

The same map works from config (`process { withName: 'REVERSE' { ext.fairscape = [...] } }`),
which is how you annotate a pipeline you don't own. Bad keys are ignored with a `WARN`, never
a failure.

## Configuration

Every option, with types, defaults, costs and what each adds to the crate:
**[docs/CONFIGURATION.md](docs/CONFIGURATION.md)**. The groups:

| Group | Options | Default |
| ----- | ------- | ------- |
| Output and file selection | `enabled`, `file`, `overwrite`, `patterns`, `paramInputs` | crate written to `file`, no filtering |
| Identity and attribution | `author`, `description`, `keywords`, `license`, `organization`, `naan`, `toolVersions`, `metadata` | falls back to the manifest, then a generated value |
| Derived artifacts | `datasheet`, `evidenceGraph`, `linkInverses`, `linkml`, `published` | **on** — they read the crate, not your data |
| File description depth | `expandDirectories`, `expandPatterns`, `expandMaxFiles`, `checksums`, `contentSizes` | **off** — each costs I/O |
| Tabular schemas | `schemas`, `schemaPatterns`, `schemaSampleSize`, `schemaArrayThreshold`, `schemaMaxFiles`, `schemaCommentChar` | **off** (`schemaCommentChar` defaults to `#`) |
| Container provenance | `containerProvenance`, `containerEngineCommand` | **off** |

An *empty* `fairscape { }` block is an error ("Unknown config attribute") — set one option or
omit the block.

`paramInputs` and `toolVersions` are the two on-by-default options that do touch your files —
one `exists` per file-shaped parameter and one small read per process. They correct metadata
that is otherwise wrong (a FastQC entity carrying the *pipeline's* version) or missing (the
samplesheet, which nf-core parses in the workflow body and never stages into a task).

The last three groups are opt-in and the crate is identical without them. Each costs extra
I/O — a directory walk, a full read per file, a parse per table, an `image inspect` — cheap
locally, expensive against an object store:

```groovy
fairscape {
    expandDirectories   = true
    expandPatterns      = ['**/*.tsv', '**/*.csv', '**/*.json']   // omit to describe everything
    checksums           = true
    contentSizes        = true
    schemas             = true
    containerProvenance = true
}
```

## Identifiers

Deterministic [ARKs](https://arks.org/): `ark:{naan}/{prefix}-{slug}-{sha1[0:7]}`, hashed
from stable sources (task hash, normalized file path, session id). `-resume` reproduces the
same identifiers for unchanged tasks and files. The default NAAN `59853` marks locally
minted, unregistered identifiers — set `naan` to yours when publishing to a FAIRSCAPE server.

## Validation

```bash
make test                        # unit tests (the parity suite skips without fairscape-cli)
make verify                      # run the test pipeline, validate against fairscape_models
make parity-test                 # diff every derived artifact against fairscape-cli's
tools/parity.sh <crate dir>      # the same diff, printed, against any crate directory
```

fairscape-cli is the ground truth for everything this plugin ports from it — inverse
entailment, inputs/outputs, the evidence graph, the LinkML/D4D export, the AI-Ready score, the
datasheet and tabular schema inference. `make parity-test` re-derives each one from three
committed crates and compares; the evidence graph, LinkML and score are byte-identical. Two CI
checks cover the two halves: **Parity vs fairscape-cli** installs the latest CLI from PyPI and
runs that suite, and **RO-Crate 1.2 validation** runs the pipelines under Nextflow and
validates the crate they publish against `fairscape_models`.

See [docs/DATASHEET.md](docs/DATASHEET.md#parity-with-the-cli) for the per-artifact claims and
the deviations.

## Scope

- One output format: an EVI RO-Crate, configured through a flat `fairscape` config scope.
  Other provenance serializations — BCO, GEXF, a DAG dump,
  [Workflow Run RO-Crate](https://www.researchobject.org/workflow-run-crate/) — are out of
  scope; [nf-prov](https://github.com/nextflow-io/nf-prov) emits those.
- Files are referenced by `contentUrl` rather than copied into the crate. The exception is
  the workflow itself, which `includeWorkflow` copies so the crate can still say what ran
  after it is zipped and moved.
- Successful native (`exec:`) tasks are described like any other task.

## Limitations

- Only `path` values become Datasets; `val` inputs appear only in the task `command` and the
  run `parameter` list.
- EVI has one timestamp (`dateCreated`) and no failure model. Per-task times and container
  images ride along as extra keys the FAIRSCAPE schema accepts but doesn't define.

## License

Apache-2.0 — full text in [LICENSE](LICENSE). Parts of the plugin framework derive from
[nf-prov](https://github.com/nextflow-io/nf-prov), also Apache-2.0; [NOTICE](NOTICE)
records what and from where.
