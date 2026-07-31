# Configuring nf-fairscape

Two places to configure:

- `fairscape { }` in `nextflow.config` — the run, and every field on the root crate entity.
- `ext fairscape: [...]` on a process — the tool that process runs.

Both are optional. With no config at all you get a valid crate in the launch directory.

## What you get

After a successful run, in the directory holding `fairscape.file`:

| File | Written when | What it is |
| ---- | ------------ | ---------- |
| `ro-crate-metadata.json` | always | the crate: tasks, files, tools, containers as one graph |
| `provenance-graph.json` | `evidenceGraph` (on) | evidence graph rooted at the crate |
| `provenance-graph.html` | `evidenceGraph` | self-contained interactive viewer |
| `ro-crate-datasheet.html` | `datasheet` (on) | the datasheet, with the AI-Readiness donut |
| `ai_ready_score.json` | `datasheet` | the 28 sub-criteria behind that donut |
| `ro-crate-linkml.yaml` | `linkml` (on) | the crate root as a D4D document |

A failed run writes nothing. A failed *artifact* is logged and skipped; you never lose the
crate over it.

## What is in the crate

| Node | How many | From |
| ---- | -------- | ---- |
| Root crate | 1 | the run; carries everything in `fairscape.metadata` |
| Run Computation | 1 | launch command, params, start/end time |
| Task Computation | one per successful task | resolved command, inputs, outputs, container |
| Workflow Software | 1 | the main script |
| Nextflow Software | 1 | the engine, versioned |
| Process Software | one per process | the process body; override with [`ext fairscape`](#ext-fairscape) |
| Dataset | one per unique file | workflow inputs, task outputs, published files |
| Schema | one per csv/tsv | `schemas = true` only |
| Container | one per image | `containerProvenance = true` only |

The bundled `nf-fairscape-test` pipeline (4 processes, 8 tasks) gives 9 Computations,
18 Datasets, 6 Software, 2 Schemas. Field-by-field mapping: [FAIRSCAPE.md](FAIRSCAPE.md).

## Options

### Output and file selection

| Option | Type | Default | What it does |
| ------ | ---- | ------- | ------------ |
| `enabled` | boolean | `true` | `false` disables the plugin entirely. |
| `file` | String | `ro-crate-metadata.json` | Where the crate goes. **Its parent is the crate directory** — files published under it get crate-relative `contentUrl`s; anything outside it gets an absolute URI or a [`localPath`](FAIRSCAPE.md#where-a-datasets-bytes-are-contenturl-vs-localpath) instead. Relative paths resolve from the launch dir, so point it inside your `outputDir`. |
| `overwrite` | boolean | `false` | With `false`, an existing crate file **aborts the run at startup**. Set `true` for anything you run twice. |
| `patterns` | List | `[]` (all) | Only include published files matching these globs. Auto-anchored: `'*.txt'` means `**/*.txt`. |
| `includeWorkflow` | boolean | `true` | Copy the workflow script and every config file the run used into `<crateDir>/workflow/`, and point the workflow Software — and every process defined in a copied file — at the copy. Without it the crate references the script where it sat — a `file:///home/you/…` path good on one machine, or a shared `~/.nextflow/assets` cache — so zipping the crate loses the definition of what ran. Copies two or three small text files. [Details](FAIRSCAPE.md#the-workflow-travels-with-the-crate). |
| `paramInputs` | boolean | `true` | Describe file-valued pipeline params as run inputs. A `--input` samplesheet is read in the workflow body, never staged into a task, so nothing else sees the run's primary input. A param qualifies when its value looks like a path with an extension; local files must exist, remote URIs are recorded without being fetched. |

### Identity and attribution

| Option | Type | Default | What it does |
| ------ | ---- | ------- | ------------ |
| `author` | String | manifest `author` → manifest `contributors` → OS user | Root author. Also `author` on Datasets, Containers and your Software (the Nextflow entity stays `Seqera Labs`), and `runBy` on Computations. |
| `description` | String | manifest description → generated | Root description. Under 10 characters is replaced by the fallback. |
| `keywords` | List | `['nextflow', 'workflow']` | Root, Datasets, Containers, and process Software (unless it sets `softwareKeywords`). |
| `license` | String | manifest license → Apache-2.0 | Use an [SPDX](https://spdx.org/licenses/) URI. |
| `organization` | String | none | Becomes `publisher`. |
| `naan` | String | `59853` | [ARK](https://arks.org/) authority number in every identifier. `59853` means locally minted; use your own when publishing to a FAIRSCAPE server. |
| `toolVersions` | boolean | `true` | Take each process Software's `version` from the `versions.yml` its tasks emit, not from the manifest — every nf-core module reports the version of the tool it actually ran, so without this a FastQC entity claims the pipeline's version. Reads one small file per process. `ext.fairscape.softwareVersion` still wins. |
| `metadata` | Map | `[:]` | Any other root field — see [below](#fairscapemetadata). |

Root `name` comes from `manifest.name`, `version` from `manifest.version` (default `1.0`).
Both are overridable through `metadata`.

### Derived artifacts

On by default: they read the crate, not your data.

| Option | Type | Default | What it does |
| ------ | ---- | ------- | ------------ |
| `datasheet` | boolean | `true` | Write `ro-crate-datasheet.html` + `ai_ready_score.json`. |
| `evidenceGraph` | boolean | `true` | Write `provenance-graph.json`/`.html`; add `EVI:inputs`/`outputs` + `localEvidenceGraph` to the root. |
| `linkInverses` | boolean | `true` | Complete EVI's 18 `owl:inverseOf` pairs, so a relationship stated once appears on both entities. |
| `linkml` | boolean | `true` | Write `ro-crate-linkml.yaml`. |
| `published` | boolean | `false` | Makes the datasheet's accession a `https://fairscape.net/<ark>` link. Only true once the crate is registered there. |

### File description depth

Off by default — each costs I/O (a directory walk, a full read per file, a parse per table).
Cheap on local disk, expensive when the crate directory is a bucket.

| Option | Type | Default | What it does |
| ------ | ---- | ------- | ------------ |
| `expandDirectories` | boolean | `false` | Describe files *inside* each published directory as their own Datasets (`isPartOf` the directory, `generatedBy` its producer). Without this a `path 'results'` output is one opaque Dataset. |
| `expandPatterns` | List | `[]` (all) | Which of those files to describe. Matched against the **full path**, so write `'**/*.tsv'`, not `'*.tsv'`. |
| `expandMaxFiles` | int | `1000` | Per-directory cap; overflow logs a `WARN`. |
| `checksums` | boolean | `false` | `md5` on every Dataset resolving to a readable local file. Feeds AI-Ready *verifiable*. |
| `contentSizes` | boolean | `false` | Recursive `contentSize` on directory Datasets + the crate total on the root. Files are always sized. |

### Tabular schemas

| Option | Type | Default | What it does |
| ------ | ---- | ------- | ------------ |
| `schemas` | boolean | `false` | An `EVI:Schema` per csv/tsv, linked by `evi:schema`. Port of `fairscape-cli schema infer`. |
| `schemaPatterns` | List | `['**/*.csv', '**/*.tsv']` | Which files get one. Matched against the **full path**. |
| `schemaSampleSize` | int | `100` | Rows read per file. |
| `schemaArrayThreshold` | int | `0` (off) | Collapse a trailing run of ≥ N same-typed columns into one array property — a 1024-dim embedding becomes 1 column, not 1024. |
| `schemaMaxFiles` | int | `500` | Per-run cap; overflow logs a `WARN`. |
| `schemaCommentChar` | String | `'#'` | Skip a leading comment preamble, so a MultiQC `*_mqc.tsv` is described by its real header instead of by its first `# id: '...'` line. A line that splits into as many fields as the line below it is treated as a header, not a comment, so a BED-style `#chrom<TAB>start<TAB>end` survives. `''` restores the CLI's behaviour. |

csv/tsv only. A file that can't be described keeps its Dataset, minus the schema.

### Why `toolVersions` and `paramInputs` default to on

Everything in the section above is off by default because it buys metadata with I/O
proportional to how much *data* the run moved. These two cost one small read per process and
one `exists` per parameter — bounded by the shape of the pipeline — and what they replace is
wrong or missing rather than merely absent, so they are on.

`toolVersions` looks in both places a version can live: the `versions.yml` a task writes, and
the run-wide `pipeline_info/*_software_mqc_versions.yml` — the only record once a module
reports through a `topic` channel with `eval`, which writes no per-task file. Keyed by bare
process name, then by qualified name. MultiQC keeps the manifest version by construction: the
collated file is written before MultiQC runs, because MultiQC is what consumes it.

`paramInputs` treats a parameter as naming a file when its value contains a `/` and its last
segment has an extension, which keeps out base directories and URL prefixes (`igenomes_base`,
`custom_config_base`). Local paths must exist; a remote URI is recorded without being fetched
— though with `schemas` on, a remote table is then read to infer its columns.

### Container provenance

| Option | Type | Default | What it does |
| ------ | ---- | ------- | ------------ |
| `containerProvenance` | boolean | `false` | Resolve each image's content digest once. Adds a `Container` entity per image, `usedContainer` + `containerDigest` on tasks, and `containerImage`/`containerDigest`/`containerImageId` on the process Software. |
| `containerEngineCommand` | String | the enabled engine, else `docker` | The engine binary, when it isn't on the `PATH` under its usual name. |

Best-effort: no engine, missing binary, deleted image or a timeout each leave the crate
untouched. Singularity/Apptainer aren't guessed at — no daemon to ask. See
[FAIRSCAPE.md](FAIRSCAPE.md#container-provenance).

## fairscape.metadata

Every key becomes a property on the root crate entity.

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

- Merged **on top of** the computed root, so it can also set `name`, `description`,
  `author`, `keywords`, `version`, `license` — the dedicated options are just shortcuts.
- `@id`, `@type`, `conformsTo`, `hasPart` are refused with a `WARN`.
- `null` values are dropped.
- **Quote any key with a `:`, `-` or `@` in it** — `'rai:dataBiases'` is not valid Groovy
  bare.

### Keys that mean something

The root is validated by `ROCrateMetadataElem` in
[`fairscape_models`](https://github.com/fairscape/fairscape-models) (`extra="allow"`), which
declares:

**Identity** — `name`, `description`, `keywords`, `version`, `identifier` (DOI or other
PID), `url`, `about`, `language` ([BCP-47](https://www.rfc-editor.org/info/bcp47)),
`creativeWorkStatus`, `datePublished`, `dateCreated`, `dateModified`, `correction`,
`isPartOf` (list of `['@id': ...]`).

**Attribution** — `author`, `publisher`, `principalInvestigator`, `funder`, `contactEmail`,
`citation`, `associatedPublication`. The first four accept a name string, an `['@id': ...]`
reference to a `Person` in the graph, or a list.

**Licensing** — `license`, `conditionsOfAccess`, `copyrightNotice`, `usageInfo`,
`prohibitedUses`.

**Ethics** — `ethicalReview`, `humanSubjectResearch`, `humanSubjectExemption`, `irb`,
`irbProtocolId`, `dataGovernanceCommittee`, `deidentified`, `fdaRegulated`,
`confidentialityLevel` (an [HL7](https://terminology.hl7.org/ValueSet-v3-Confidentiality.html)
code, e.g. `'normal'`).

**Content** — `contentSize`, `hasSummaryStatistics`, `additionalProperty`:

```groovy
metadata = [
    additionalProperty: [
        ['@type': 'PropertyValue', name: 'Sequencing platform', value: 'Illumina NovaSeq X'],
        ['@type': 'PropertyValue', name: 'Human Subject',       value: 'No']
    ]
]
```

**Responsible AI** ([Croissant RAI 1.0](http://mlcommons.org/croissant/RAI/1.0), prefix
declared in the crate `@context`) — `rai:dataCollection`, `rai:dataCollectionType`,
`rai:dataCollectionRawData`, `rai:dataCollectionMissingData`, `rai:dataCollectionTimeframe`,
`rai:dataBiases`, `rai:dataLimitations`, `rai:dataUseCases`, `rai:dataSocialImpact`,
`rai:personalSensitiveInformation`, `rai:dataPreprocessingProtocol`,
`rai:dataManipulationProtocol`, `rai:dataImputationProtocol`, `rai:dataAnnotationProtocol`,
`rai:dataAnnotationPlatform`, `rai:dataAnnotationAnalysis`, `rai:annotationsPerItem`,
`rai:machineAnnotationTools`, `rai:dataReleaseMaintenancePlan`.

These also feed `ro-crate-linkml.yaml` and the AI-Ready score.

Anything else survives in the JSON, but `@vocab` is schema.org — so `myLabProtocol` expands
to `https://schema.org/myLabProtocol`, a URI that resolves to nothing. Use a declared field,
a `rai:` one, or `additionalProperty`.

## ext fairscape

Without it, a process's Software entity describes the *Nextflow process*. With it, the
*tool*:

```nextflow
process REVERSE {
    ext fairscape: [
        softwareName       : 'tac',
        softwareVersion    : '8.32',
        softwareAuthor     : 'Jay Lepreau, David MacKenzie (GNU coreutils)',
        softwareDescription: 'Reverses the order of lines in a text file.',
        softwareUrl        : 'https://www.gnu.org/software/coreutils/tac',
        softwareFormat     : 'application/x-executable',
        softwareKeywords   : ['coreutils', 'text-processing']
    ]
    ...
}
```

Must be a map. Every key is optional and replaces one field:

| Key | Software field | Default when omitted |
| --- | -------------- | -------------------- |
| `softwareName` | `name` | the process name |
| `softwareVersion` | `version` | `manifest.version`, else the git commit id |
| `softwareAuthor` | `author` | the crate author |
| `softwareDescription` | `description` | the process body as written (≥ 10 chars) |
| `softwareUrl` | `contentUrl` | the script/module defining the process |
| `softwareFormat` | `format` | `nextflow` |
| `softwareKeywords` | `keywords` | `fairscape.keywords` (a bare string is wrapped) |

Renaming loses nothing — task Computations still carry the process name and still point here
via `usedSoftware`.

From config instead, to annotate a pipeline you don't own (both selectors work):

```groovy
process {
    withName:  'REVERSE'      { ext.fairscape = [softwareName: 'tac', softwareVersion: '8.32'] }
    withLabel: 'process_high' { ext.fairscape = [softwareKeywords: ['compute-intensive']] }
}
```

A bad annotation never fails the run, but always logs a `WARN`: a non-map value is ignored
whole, unrecognized keys are dropped and the known ones still applied.

## What moves the AI-Ready score

28 sub-criteria. Seven are free for being an RO-Crate, ten more come from what the plugin
records anyway — `nf-fairscape-test` scores **17/28** with nothing but `file` and
`overwrite`. The other eleven only you know; the [max recipe](#everything-on) scores 28/28.

| Criterion | Satisfied by |
| --------- | ------------ |
| findable, persistent | the root ARK; `metadata.identifier` upgrades it to your DOI |
| reusable | `license` — the Apache-2.0 default already scores, so set your real one |
| transparent, traceable, interpretable | the graph itself |
| key_actors_identified | `author` (defaults to your OS user name), `organization`, `metadata.principalInvestigator` |
| statistics | per-file `contentSize`; `contentSizes = true` makes the total real instead of a sum |
| standards | `schemas = true` |
| potential_sources_of_bias | `'rai:dataBiases'` |
| data_quality | `'rai:dataCollectionMissingData'` |
| fit_for_purpose | `'rai:dataUseCases'` / `'rai:dataLimitations'` |
| verifiable | `checksums = true` |
| ethically_acquired | `'rai:dataCollection'` / `humanSubjectResearch` |
| ethically_managed | `ethicalReview` / `dataGovernanceCommittee` |
| ethically_disseminated | the license alone scores; add `'rai:personalSensitiveInformation'`, `prohibitedUses` |
| secure | `confidentialityLevel` |
| domain_appropriate | `'rai:dataReleaseMaintenancePlan'` |
| well_governed | `dataGovernanceCommittee` |
| standardized | `format` on entities |
| computationally_accessible | `organization` |

Each unmet criterion carries its own fix in `ai_ready_score.json` — the file is a checklist.

## Recipes

### Minimal

```groovy
fairscape {
    file      = "${params.outdir}/ro-crate-metadata.json"
    overwrite = true
}
```

### Publication-ready

```groovy
fairscape {
    file      = "${params.outdir}/ro-crate-metadata.json"
    overwrite = true

    author       = 'Jane Roe'
    organization = 'Example Institute'
    description  = 'Bulk RNA-seq quantification for the Example cohort, release 3.'
    keywords     = ['rna-seq', 'example-cohort']
    license      = 'https://spdx.org/licenses/CC-BY-4.0'

    checksums    = true
    schemas      = true
    contentSizes = true

    metadata = [
        identifier           : 'https://doi.org/10.1234/example',
        principalInvestigator: 'Jane Roe',
        funder               : 'NIH Bridge2AI (OT2OD032742)',
        associatedPublication: 'https://doi.org/10.1234/example-paper',
        citation             : 'Roe J. et al. Example cohort RNA-seq. 2026.',
        contactEmail         : 'jane@example.org',
        conditionsOfAccess   : 'Non-commercial research use only.',
        copyrightNotice      : 'Copyright (c) 2026 Example Institute.'
    ]
}
```

### Everything on

Scores 28/28, verified on `nf-fairscape-test` (`containerProvenance` is score-neutral).
Write something true in the strings — an honest "no missing values" beats a grand
placeholder.

```groovy
fairscape {
    file      = "${params.outdir}/ro-crate-metadata.json"
    overwrite = true

    author       = 'Jane Roe'
    organization = 'Example Institute'
    license      = 'https://spdx.org/licenses/CC-BY-4.0'

    expandDirectories   = true
    checksums           = true
    contentSizes        = true
    schemas             = true
    containerProvenance = true

    metadata = [
        identifier             : 'https://doi.org/10.1234/example',
        principalInvestigator  : 'Jane Roe',
        funder                 : 'NIH Bridge2AI (OT2OD032742)',
        associatedPublication  : 'https://doi.org/10.1234/example-paper',
        confidentialityLevel   : 'normal',
        ethicalReview          : 'IRB #2026-001, approved 2026-01-05.',
        humanSubjectResearch   : 'No human subjects involved.',
        dataGovernanceCommittee: 'Example Institute Data Governance Committee',
        prohibitedUses         : 'No re-identification attempts.',

        'rai:dataCollection'              : 'Generated in-house, cohort protocol v3.',
        'rai:dataCollectionMissingData'   : 'QC failures dropped; no imputation.',
        'rai:dataBiases'                  : 'All donors from a single recruitment site.',
        'rai:dataUseCases'                : 'Differential expression, pathway analysis.',
        'rai:dataLimitations'             : 'Not validated for clinical use.',
        'rai:personalSensitiveInformation': 'De-identified; no direct identifiers.',
        'rai:dataReleaseMaintenancePlan'  : 'Re-released annually; old versions stay resolvable.'
    ]
}
```

## Gotchas

- An **empty `fairscape { }` block** is an error (`Unknown config attribute`) — Nextflow
  can't tell it from a typo. Set one option or drop the block.
- **`overwrite = false` aborts a re-run**, it doesn't skip the crate.
- **The three pattern options anchor differently.** `patterns` is auto-prefixed with `**/`,
  so `'*.txt'` works. `expandPatterns` and `schemaPatterns` match the full path, so they
  need `'**/*.tsv'`.
- **Publish with `mode: 'copy'` if the crate is going anywhere.** Nextflow's default publish
  mode is `symlink`, and a symlinked published file gets a crate-relative `contentUrl` like
  any other — the path is right, but the bytes are still in `work/`. Zip that crate and you
  ship dangling links; delete `work/` and it is hollow. The plugin cannot tell the two apart,
  so this is on your pipeline. nf-core pipelines set `publish_dir_mode = 'copy'` already.
  Local runs only: publishing to `s3://`, `az://` or `gs://` is always a copy, because
  Nextflow will not symlink across filesystems.
  [Longer version](FAIRSCAPE.md#precondition-publish-with-mode-copy-if-you-want-a-portable-crate).
- **On S3/Azure/Google, set `file` and `outputDir` together.** `file` defaults to a launch-dir
  path, so a remote `outputDir` with the default leaves the crate behind on local disk and
  every published file outside it — each then gets an absolute `s3://…` `contentUrl` instead
  of a crate-relative one. Work-directory intermediates are `localPath: work/…` on every
  connector, since Nextflow normalizes the work directory away before the plugin sees it.
  [Longer version](FAIRSCAPE.md#the-same-rule-on-s3-azure-and-google-storage).
- **A file the crate describes but does not contain has `localPath`, not `contentUrl`** — a
  work-directory intermediate, say. It keeps its `md5` and all its provenance edges; what it
  does not have is a promise that it is retrievable.
  [Longer version](FAIRSCAPE.md#where-a-datasets-bytes-are-contenturl-vs-localpath).
- **A published directory is one Dataset** unless `expandDirectories = true`.
- **Only `path` values become Datasets.** `val` inputs show up in the task `command` and the
  run `parameter` list.
- **The crate is written only on success**, for successful tasks only. EVI has no failure
  model.
- **Run `make install` after changing plugin code**, or Nextflow uses the stale copy in
  `~/.nextflow/plugins/`.

More: [FAIRSCAPE.md](FAIRSCAPE.md) (what's in the crate),
[DATASHEET.md](DATASHEET.md) (derived artifacts), [../examples](../examples) — `reverse-list`
and `letters-chain` are minimal demos of both hooks; `fastquorum-like` and `bamtofastq-like`
use them on real bioinformatics pipelines and score 28/28.
