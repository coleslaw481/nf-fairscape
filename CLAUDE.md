# nf-fairscape — AI context

Nextflow plugin (Groovy, Gradle) emitting FAIRSCAPE EVI RO-Crates. Fork of
nextflow-io/nf-prov v1.7.0 (sibling checkout at `../nf-prov`, Nextflow source at
`../nextflow`). Built and validated end-to-end 2026-07-20. Human-oriented tour:
`docs/WALKTHROUGH.md`; emission spec: `docs/FAIRSCAPE.md`; datasheet/evidence-graph
port: `docs/DATASHEET.md`; **user-facing option/metadata reference:
`docs/CONFIGURATION.md`** (canonical — every new config option and every
`ext.fairscape`/`fairscape.metadata` key goes there; FAIRSCAPE.md keeps only the
crate semantics and links across). The user does NOT know Groovy/Nextflow — explain
changes in Python-adjacent terms and point to files.

## Architecture (5 files, call order)

1. `build.gradle` — declares plugin id via `settings.gradle` name, min Nextflow
   25.10.0, extensionPoints = [FairscapeConfig, ProvObserverFactory]. Version read
   from `VERSION` (0.1.0). Internal package deliberately still `nextflow.prov`
   (eases upstream rebases; only user-facing names rebranded).
2. `src/main/groovy/nextflow/prov/FairscapeConfig.groovy` — `@ScopeName('fairscape')`
   FLAT config scope (no `prov.formats` nesting). Options: enabled, file
   (default `ro-crate-metadata.json`), overwrite, patterns, naan (default
   **59853**), author, description, keywords (`['nextflow','workflow']`), license,
   organization. All defaults in the Map constructor. Manifest fallbacks
   (author/description/license/version) applied in the renderer, not here.
   Later additions: schemaCommentChar (`'#'`), toolVersions (**true**),
   paramInputs (**true**) — see "Hardened against real nf-core pipelines".
3. `ProvObserverFactory.groovy` — TraceObserverFactoryV2; reads
   `session.config.fairscape`, returns ProvObserver or null when disabled.
4. `ProvObserver.groovy` — TraceObserverV2. Collects Set<TaskRun> (onTaskComplete +
   onTaskCached), publishedFiles Map<source,target> (onFilePublish, filtered by
   `patterns` globs), workflowOutputs (onWorkflowOutput). onFlowComplete: if
   session.isSuccess(), calls each Renderer in try/catch (crate failure never
   fails the run; stack trace logged at DEBUG to .nextflow.log), then
   `renderDerivedArtifacts()` → `CrateArtifacts.generate` (also best-effort;
   returns early if the crate render failed).
5. `renderers/FairscapeRenderer.groovy` (~350 lines, the core) — implements
   `Renderer.render(session, tasks, workflowOutputs, publishedFiles)`. Plus
   `util/ProvHelper.groovy` (unchanged from nf-prov): getTaskLookup
   (outputPath→TaskRun), getWorkflowInputs (inputs no task produced),
   getTaskInputs/Outputs, getEncodingFormat, checkFileOverwrite.

## Renderer emission (must stay in sync with fairscape_models)

- `@context` = DEFAULT_CONTEXT from
  `~/fairscape/fairscape_models/fairscape_models/fairscape_base.py`, **plus one key**:
  `localPath` → `https://w3id.org/ro/terms#localPath` (2026-07-30). This is the ONE
  deliberate divergence from verbatim; **fairscape_models has not been updated to
  match**, so decide whether DEFAULT_CONTEXT should carry it before treating the two as
  in sync. Declared bare, not prefixed, because RO-Crate 1.2's own context declares
  ZERO prefixes (0 of 2899 terms) and spells this one `localPath` — prefixing it as
  `ro:localPath` would expand to the same IRI but stop matching the literal key
  RO-Crate tooling reads. It has to be declared at all because `@vocab` would otherwise
  resolve a bare `localPath` to `https://schema.org/localPath`, which does not exist.
- **`includeWorkflow` (default TRUE, 2026-07-30) copies the workflow into the crate.**
  `copyWorkflowSources()` copies `metadata.scriptFile` + every `metadata.configFiles`
  entry into `<crateDir>/workflow/`, and the workflow Software `contentUrl` prefers that
  copy over BOTH `metadata.repository` and the on-disk path. **`definitionLocator()`
  applies the same preference to PROCESS Software** (2026-07-30): a process declared in
  `main.nf` points at `workflow/main.nf`, not at `/home/you/…/main.nf`. Before this, a
  single-file pipeline's crate carried the file and still described its processes with an
  absolute local path (3 of 6 Software entities in nf-fairscape-test). nf-core is
  unaffected — modules live under `projectDir`, are never copied, and keep their pinned
  `…/tree/<sha>/modules/…` URLs (verified against all 5 example crates);
  `ext.fairscape.softwareUrl` still wins over both. Rationale, since it looks
  like it duplicates the pinned URLs: a crate must be able to say what ran after being
  zipped and moved, and neither reference survives that — a local script normalizes to
  `file:///home/you/…` (one machine) and a registry one to `~/.nextflow/assets/<name>`
  (shared cache, overwritten on update). The REJECTED alternative was writing the crate
  into the directory holding main.nf so those files fall under the crate root: impossible
  for registry pipelines (shared cache), and it puts run artifacts in the source tree.
  Basename collisions between config files are disambiguated (`nextflow-2.config`), not
  overwritten. Best-effort: an unreadable source WARNs and falls back to the reference.
  **Entailment consequence, and a test depends on it:** the config copies become Datasets
  with `isPartOf` → workflow Software and no `generatedBy`, so `calculateInputsOutputs`
  entails them as crate INPUTS (correct — they parameterize the run) while `isPartOf`
  keeps them out of OUTPUTS. `CrateArtifactsTest` "should add EVI inputs and outputs"
  asserts exactly that, and it reads the LIVE `examples/letters-chain/results` crate as
  its fixture — so re-running that example changes what the test sees. Parity is
  unaffected (verified: all stages identical after the change).
- **Dataset locators are split by whether the file is inside the crate** (2026-07-30,
  `fileLocator()`): under `crateDir` → crate-relative `contentUrl`; outside it and
  PathNormalizer returned a URI scheme (pinned GitHub URL, `s3://`) → that as
  `contentUrl`; outside it and bare → `localPath` instead, and NO contentUrl. A relative
  `contentUrl` is a promise the file is at that path under the crate root, and work-dir
  intermediates broke it — 44 of 130 entities on differentialabundance pointed at
  `work/…` from a crate rooted at `results/`. Edges, `md5`, `contentSize` all unchanged,
  so the derivation chain through intermediates is intact. **The plugin cannot detect
  the symlink case**: `publishDir mode: 'symlink'` (Nextflow's DEFAULT) puts a link under
  the crate root, which passes `startsWith(crateDir)` and gets a relative contentUrl
  while the bytes stay in `work/`. Documented as a precondition (`mode: 'copy'`), NOT
  validated at runtime — the user explicitly declined a runtime check. That hazard is
  **local-only**: `PublishDir.validatePublishMode()` forces `copy` when the publish target
  is off the default filesystem, so an S3/Azure/GS crate cannot hold a dangling link.
- **Connector behaviour of the locators** (reviewed 2026-07-30, no behaviour change):
  the in-crate test is guarded on `fileSystem.provider()` equality, so it compares two
  S3 paths for a remote crate and never mixes providers (nf-azure's `AzPath.startsWith`
  compares path STRINGS with no provider check, and a false match would reach
  `relativize` → ProviderMismatchException). **PathNormalizer strips the work dir first**
  (`workDir` prefix → bare `work/…`), scheme and bucket included, so a cloud intermediate
  at `s3://bucket/work/…` records `localPath: work/…` exactly like a local one — kept
  deliberately (consistent across connectors, work dir is scratch), but it means the crate
  offers no URL for an intermediate that may still be retrievable. Change it in
  `fileLocator` if that is ever wanted. **`fairscape.file` still defaults to a launch-dir
  path**, so a remote `outputDir` with the default writes the crate locally and gives every
  published file an absolute `s3://` contentUrl (no crate-relative ones, nothing packaged);
  documented in CONFIGURATION.md, NOT warned about at runtime.
- Graph: descriptor (conformsTo ro/crate/1.2) → root `["Dataset", EVI#ROCrate]`
  (conformsTo fairscape/profile/0.1, hasPart = everything) → run Computation →
  per-task Computations (`isPartOf` run) → Software: workflow script + Nextflow
  engine (referenced ONLY by run Computation) + one per PROCESS (`isPartOf`
  workflow Software; description = process body SOURCE via
  `processor.getTaskBody().getSource()` stripped of quote delimiters — the
  template, vs resolved `command` on each Computation; each task's
  usedSoftware → its process; resolved via
  `ScriptMeta.get(processor.getOwnerScript()).getScriptPath()`, works with multiple
  processes per file unlike upstream's one-process-per-module HACK) → Datasets
  (one per unique file). Both edge directions written:
  Computation.generated AND Dataset.generatedBy (matches LakeDB fixture:
  `fairscape_models/tests/test_rocrates/LakeDB/ro-crate-metadata.json`).
  `prov:used`/`prov:wasAssociatedWith` mirrors intentionally OMITTED (pydantic
  derives them; fixture validates without).
- Per-process tool override (2026-07-23, `keywords`+warnings added 2026-07-24):
  `ext fairscape: [softwareName, softwareVersion, softwareAuthor,
  softwareDescription, softwareUrl, softwareFormat, softwareKeywords]` — each key
  optional, replaces the matching process-Software field (softwareName = the tool
  BECOMES the entity name; process name survives on task Computations).
  `softwareKeywords` (named for parity with the other `software*` keys) → Software
  `keywords` field (defaults to crate keywords like Datasets; coerced to a string
  list via `asStringList`, so a bare string is wrapped). Allowed-key set =
  `KNOWN_EXT_KEYS` constant. Read via `processor.config.get('ext')` →
  `fairscapeExt()` static helper (unit-tested). Also settable from config:
  `process { withName:'X' { ext.fairscape = [...] } }` (verified). Custom BARE
  directives are impossible — Nextflow whitelists directive names
  (ProcessBuilder.checkName → IllegalDirectiveException) and `foo: 'bar'` in a
  process body is a Groovy labeled statement, silently ignored. `ext` is the
  sanctioned escape hatch. Documented in docs/CONFIGURATION.md; demoed in both
  examples (letters-chain annotates only REVERSE → mixed default/override).
- Ext validation warnings (2026-07-24): `fairscapeExtWarnings(ext)` static helper
  (returns warning strings, pure/testable) → renderer `log.warn`s per process when
  `ext.fairscape` is present but NOT a map (e.g. `['made-up-property']`) or has
  unrecognized keys. WARN-only by design: crate render is already best-effort
  (observer swallows failures), and there's no way to reject a value Nextflow
  already accepted into `ext`. Verified firing in a real run.
- Workflow-level root metadata (2026-07-24): `fairscape.metadata = [key: value,
  ...]` config map (flat scope, new `FairscapeConfig.metadata`, default `[:]`) is
  overlaid onto the root crate node via `mergeRootMetadata(root, metadata)` —
  long-tail fields (associatedPublication, funder, principalInvestigator, RAI,
  etc.) that have no dedicated option. Root `ROCrateMetadataElem` is `extra=allow`
  and declares most of these, so they validate. metadata wins over computed
  values; `PROTECTED_ROOT_KEYS` (`@id`,`@type`,`conformsTo`,`hasPart`) can't be
  overridden (WARN if attempted). This is the workflow-level analogue of process
  `ext`. Exercised in nf-fairscape-test (metadata + WC_SAMPLE keywords override).
- Required-field floors: description ≥10 chars (`ensureDescription`), keywords
  non-empty, datePublished/format/author always set, `contentSize` must be a
  STRING (pydantic rejects int — was a real bug).
- ARKs: `ark:{naan}/{prefix}-{slug}-{sha1(sourceId)[:7]}`, deterministic. Hashed
  sources: session id (root, run), task hash (task Computations), normalized
  script path+version (workflow Software), normalized file path (Datasets).
  `fileArks` cache map = one ARK per physical file; published target registered
  as alias of work-dir source. Verified identical across `-resume`.
  The SLUG of a process Software / task Computation comes from `bareName()` =
  the part after the last `:` (2026-07-29). nf-core process names are fully
  qualified (`NFCORE_PAIRGENOMEALIGN:PAIRGENOMEALIGN:PAIRALIGN_M2O:ALIGNMENT_LASTDB`)
  and blew past `slugify`'s 40-char cap, so 6 of 12 ARKs in one real run shared
  the slug `nfcore-pairgenomealign-pairgenomealign-p`. The HASHED sourceId still
  uses the fully qualified name, so two aliases of one module stay distinct and
  `-resume` still reproduces the ARK — only the readable half changed.
- Files referenced not copied: published-under-crateDir → crate-relative
  contentUrl; else PathNormalizer output (remote inputs keep original URL).
- Passthrough extras (user-approved): startTime/endTime/nextflowVersion/identifier
  on run Computation; containerImage/identifier(task hash) on task Computations.
  Valid via pydantic `extra='allow'`.
- Directory expansion + checksums + sizes (2026-07-28): a process publishing a
  DIRECTORY used to give one opaque Dataset (format `unknown`, no size). Now
  `expandDirectories`/`expandPatterns`/`expandMaxFiles` walk each published dir
  and register its files as Datasets with `isPartOf` → dir Dataset and
  `generatedBy` → the dir's producer (inherited via `publishedDirArks`).
  Deliberately NOT added to `Computation.generated` (the dir stands for them);
  `generatedBy` alone is enough for find_outputs + the evidence-graph BFS.
  `checksums` → streamed `md5` per Dataset. `contentSizes` → recursive
  `treeSize` for directory Datasets + ROOT `contentSize` =
  `CrateJson.formatSize(treeSize(crateDir))` (WITHOUT the root value AiReadyScorer
  sums per-entity contentSize and double-counts an expanded dir plus its files;
  measured before the datasheet/graph HTML land in the crate dir).
- Container provenance (2026-07-28; Container entities 2026-07-29): `containerProvenance`
  → `util/ContainerInspector.groovy`
  runs `<engine> image inspect --format '{{json .}}'` once per DISTINCT image
  (cached). Emits one EVI `Container` entity per distinct image (`container` ARK
  prefix hashed from digest-or-reference, in hasPart) and `usedContainer` refs on
  task Computations — ADDITIVE next to the flat keys, which stay:
  `containerDigest` on task Computations and
  `containerImage`/`containerDigest`/`containerImageId` on the process Software
  (`processContainer()` returns null when one process's tasks used different
  images, so nothing is invented). Engine auto-detected from
  `docker.enabled`/`podman.enabled`, overridable via `containerEngineCommand`;
  singularity/apptainer deliberately NOT guessed (image files, no daemon to ask).
  Best-effort everywhere — engine missing / image gone / inspect times out ⇒
  nulls ⇒ withoutNulls drops them ⇒ crate unchanged. **Gotcha that bit the first
  draft of the docs:** under the containerd image store `.Id` IS the manifest
  digest, so `RepoDigests[0] == Id` both for a PULLED image and for a LOCALLY
  BUILT one — presence of a repo digest does NOT mean the image is pullable.
  Don't reintroduce that claim. fairscape_models side (2026-07-29): `Container`
  added to `ROCrateV1_2` union + type_map, `usedContainer` added to `Computation`
  (folded into prov:used). `usedContainer` is NOT yet declared in EVI.owl —
  flag before publishing the ontology claim; also NOT in InverseLinker
  INVERSE_PAIRS or the evidence-graph BFS field lists (deliberate, waits on
  EVI.owl + CLI parity).
- **Everything above is OPT-IN and defaults must stay that way.** The rule: a run
  that sets none of the new options emits the crate the plugin always emitted.
  Each option buys metadata with I/O (dir walk / full read per file / parse per
  table) that is free on local disk and expensive when the crate dir is an object
  store — that asymmetry is why none of them default to true, and it is what the
  user explicitly asked for. **The two exceptions are `toolVersions` and
  `paramInputs`** (2026-07-29, user-approved): they cost O(processes) and
  O(params) tiny reads rather than O(data), and what they replace is WRONG (a
  FastQC entity claiming the pipeline's version number) or MISSING (the
  samplesheet), not merely absent metadata. Regression proof (redo it if you touch the
  renderer): re-run `examples/letters-chain` with its stock config and diff
  against a pre-change crate — `ai_ready_score.json` identical, crate identical
  after normalizing ARK hash suffixes (they hash the work-dir path, so a fresh
  run remints them), timestamps and the session uuid. Only unconditional change
  in the whole diff: a directory Dataset's description says `Directory 'x'`
  instead of `File 'x'`.
- Schema inference (2026-07-28): `schema/TabularSchemaInferrer.groovy` ports
  `fairscape-cli schema infer` = `fairscape_models.schema.tabular.TabularSchema.infer`
  + frictionless `Detector.detect_schema`. Candidate order, 0.9 confidence,
  missing-values `['']`, `field{N}` naming/dedup, `any` fallback and every
  per-type cell reader mirror frictionless 5.x (read those from
  `~/miniconda3/lib/python3.13/site-packages/frictionless/{settings.py,detector,fields}`).
  Emits the CLI's document minus `fairscapeVersion`, with a DETERMINISTIC ARK
  (`schema` prefix, hashed from the Dataset ARK) instead of the CLI's uuid.
  `schemaArrayThreshold` collapses a trailing same-typed run into a spanning
  array (`index: "N::"`, equal `min-items`/`max-items` — required by the CLI's
  `build_frictionless_schema`). Only csv/tsv; parquet/HDF5/WFDB/DICOM not ported.
  Oracle: run `fairscape-cli schema infer` on the same file and diff columns —
  did this for 27 real cellmaps outputs + synthetic edge cases, 0 mismatches.
  (`chardet` must be pip-installed into the CLI's interpreter for it to run.)
  **One DELIBERATE divergence from the CLI (2026-07-29): `schemaCommentChar`**
  (default `#`) skips a leading comment preamble via `dropComments`. MultiQC
  custom-content tables (`*_mqc.tsv`, which nf-core pipelines publish by the
  dozen) open with `# id: '...'` lines, and the CLI describes such a file as ONE
  column literally named `# id: 'contigs_length_statistics'`, marked `required` —
  wrong metadata asserted confidently, seen for real on nf-core/pairgenomealign.
  A leading line only counts as a comment when it does NOT split into as many
  fields as the line after it, so a BED-style `#chrom<TAB>start<TAB>end` header
  still counts as the header. Set `schemaCommentChar = ''` to restore CLI parity.
  The read limit is `sampleSize + 1 + MAX_COMMENT_LINES`, truncated back to
  `sampleSize + 1` after dropping, so an uncommented file sees exactly the rows
  frictionless would have seen (parity tests depend on this).

## Datasheet + evidence graph (2026-07-24, `nextflow.prov.datasheet`)

Groovy port of `fairscape build datasheet` / `build evidence-graph`, run by the
observer after the crate is written. Full spec + provenance of each file:
`docs/DATASHEET.md`. Config: `datasheet`/`evidenceGraph` (default true),
`published` (default false).

- Order (mirrors CLI `process_subcrate`): **link-inverses** → `find_outputs`
  entailment → `EVI:inputs`/`EVI:outputs` on root (full URI keys) → evidence
  graph rooted at the CRATE (needs those outputs; BFS backwards over
  generatedBy/used*) → provenance-graph.json/.html → root gains
  `localEvidenceGraph` → crate rewritten with `JsonOutput.prettyPrint` (same
  formatting the renderer uses) → **linkml** → datasheet.
- find_outputs divergence (2026-07-29): `calculateInputsOutputs` skips a Dataset
  that has `isPartOf` → another entity, i.e. a file found inside a published
  directory. The dir Dataset stands for its contents (same rule as
  `Computation.generated`), and without this ONE published MultiQC folder made
  every plot file a top-level crate output: nf-core/seqinspector reported 1020
  outputs and the evidence graph started a walk from each, giving a 1187-node
  graph and a 1 MB provenance-graph.html. Now 35 outputs / 202 nodes / 411 KB.
  Only reachable when `expandDirectories` is on. parity.sh does NOT catch this
  (it feeds the Groovy-computed outputs to the CLI side), so if you change it,
  re-check by hand.
- Inverses (2026-07-28, `InverseLinker`): port of `fairscape augment
  link-inverses`. The CLI rdflib-parses EVI.owl and SPARQLs `owl:inverseOf`; the
  ontology is fixed so the 18-pair ANSWER is carried as `INVERSE_PAIRS` instead
  (regen command in the class doc). Faithful details that matter: only
  `{"@id":...}` refs are followed (a bare string is a literal); `entity_map` is
  keyed by @id so a duplicate id collapses last-wins; pairs iterate outer,
  entities inner, BOTH directions per entity — matching Python's key-insertion
  order. We sort pairs (CLI's SPARQL order is nondeterministic) so the augmented
  crate is byte-stable. Verified: same modification counts (92/11/30) and
  identical parsed graphs vs the CLI on cellmaps/letters/nf-fairscape-test.
  NOTE the run-level Computation ends up in published datasets' `generatedBy`
  (it lists them in `generated`) — correct entailment, matches the CLI, and
  EvidenceGraphBuilder takes `generatedBy[0]` so the graph shape is unchanged.
  `prov:` namespace mirrors deliberately still NOT emitted (user confirmed
  2026-07-28: "prov ones" meant the EVI provenance pairs).
- LinkML/D4D (2026-07-28, `D4dConverter` + `PyYaml`): port of `fairscape build
  linkml` = `ROCRATE_TO_D4D_MAPPING` over the ROOT ONLY + `convert_to_d4d_structure`.
  `PyYaml` is a `yaml.dump` clone — it mirrors PyYAML's Emitter column
  bookkeeping (column/whitespace/indention/indent, write_indent/write_indicator/
  write_plain/write_single_quoted/write_double_quoted) because fold points depend
  on exact columns. Gotchas hit for real: `implicit[0]` is TRUE for non-strings
  (an int must be written bare — only a String has to defend itself against the
  resolver); double-quoted style IS reached in practice (allow_unicode=False, so
  an em dash forces it) and needs the `\`-continuation splitting branch.
  Byte-identical to the CLI on 4 crates.
- Files: `CrateArtifacts` (orchestration + find_outputs), `EvidenceGraphBuilder`
  (BFS + projection), `GraphCondenser` (DatasetGroup collapse, threshold 5),
  `EvidenceGraphHtml`, `DatasheetGenerator` (+ `CompositionBuilder`,
  `AiReadyScorer`), `MiniJinja`/`ExpressionTranslator` (Jinja2 subset →
  GroovyShell), `PyJson` (byte-compatible `json.dump(indent=2)`), `CrateJson`.
- Templates are VERBATIM copies of the CLI's under
  `src/main/resources/fairscape/templates/` — update by re-copying, not editing.
  MiniJinja implements trim_blocks/lstrip_blocks + the filters/tests they use.
- **Oracle, automated: `make parity-test`** = `src/test/groovy/nextflow/prov/parity`
  (2026-07-31). Five specs — InverseLinker / InputsOutputs / EvidenceGraph /
  Datasheet / SchemaInfer — over three COMMITTED crates in
  `src/test/resources/parity/` (letters-chain, nf-test, fanout), regenerated by
  `tools/make-parity-fixtures.sh` with every derived artifact off. Each spec hands
  the SAME directory to both sides (the CLI does the preparation steps, so a
  failure is a divergence and not a different input). provenance-graph.json/.html,
  ro-crate-linkml.yaml and ai_ready_score.json are BYTE-IDENTICAL;
  ro-crate-datasheet.html after two normalizations in `CliParity.normalizeDatasheet`.
  Specs `@Requires` the CLI so `make test` runs without Python; CI sets
  `FAIRSCAPE_PARITY_REQUIRED=1` so `CliAvailableTest` FAILS rather than the suite
  skipping itself green. Gotcha when editing these: Spock renders every operand of
  a failing condition, so reduce a 50 KB comparison to a short String in `when:`
  and assert on THAT, or the failure message is the whole datasheet.
- **Oracle, interactive: `tools/parity.sh <crate dir>`** — same comparison against
  ANY crate directory (real pipeline output), prints diffs instead of pass/fail.
  Keep both: the suite is the CI gate, parity.sh is what you point at cellmaps.
- Two datasheet deviations, both normalized by the suite: the 3 summary stat cards
  (deliberate — the CLI's `evi:totalEntities == 0` guard never fires because
  pydantic dumps the declared field as None, so its per-crate fallback is dead
  code; we run it), and composition-pattern ORDER, which is unreachable rather
  than chosen: `fairscape_models/conversion/mapping/subcrate_utils.py` builds
  those with `list(set(...))`, so the CLI's own datasheet flips between
  PYTHONHASHSEED values (verified 2026-07-31; linkml + ai_ready_score checked the
  same way and ARE deterministic). Full list in docs/DATASHEET.md.
- `.gitignore` ignores `results`, `ro-crate-metadata.json`, `provenance-graph.*`
  etc. globally, which SWALLOWS the fixtures — `!src/test/resources/parity/**`
  re-includes them. Before this, `CrateArtifactsTest` pointed at
  `examples/letters-chain/results` and 6 of its 9 tests failed on a fresh
  checkout; it now points at the committed fixture. Check `git check-ignore` after
  adding any fixture under a crate-shaped name.
- Gotcha (hit for real): condensation MUTATES the nodes it collapses
  (`usedDataset` → group ref). `EvidenceGraphBuilder` deep-copies every node into
  its index; without that the rewritten crate got a dangling `ark:group/...` ref.
- Not ported: sub-crates/previews, Croissant, Merkle tree, PDF — nf crates are
  always single crates. (LinkML/D4D and link-inverses WERE ported, see above.)

## Hardened against real nf-core pipelines (2026-07-29)

Ran 5 released nf-core pipelines under the plugin with every opt-in on:
**demo 1.2.0, phyloplace 2.0.1, pairgenomealign 3.0.3, differentialabundance
2.0.0, seqinspector 1.1.0** (`-profile test,docker`, all small: 8-48 tasks).
All emit a crate that passes `validate_crate.py`. Also verified the plugin runs
unchanged on **Nextflow 26.04.6** (pairgenomealign and seqinspector require it;
same crate, same counts as 25.10.4) — there is no TraceObserverV2 breakage. Pick
these when testing changes: demo is the smoke test, differentialabundance is the
one that used to CRASH, seqinspector is the scale probe (1125 Datasets, 153 MB).

What real pipelines broke, and the fix:

- **Cyclic graph → StackOverflowError, no evidence graph.** nf-core 3.4+ collates
  tool versions with `channel.topic('versions').collectFile(name: 'collated_versions.yml')`.
  NEXTFLOW writes that file, so no task produced it → it is a "workflow input"
  (run `usedDataset`) — and it is also published → run `generated` → link-inverses
  gives it `generatedBy: run`. Two-node cycle. `EvidenceGraphBuilder.projectNode`
  writes `graphDict[nodeId]` only AFTER recursing (that ORDER is what the CLI
  produces and parity.sh compares byte-for-byte, so it must not change), so it
  re-entered the in-flight node forever. **Fix is at emission: `withoutGenerated`
  drops from a Computation's `usedDataset` anything in its own `generated`** — a
  run cannot use what it produced, and the task that read the file keeps its own
  edge, so no evidence is lost. Plus an `inFlight` Set in `projectNode` as a
  guard for crates other tools made cyclic (order-preserving; `GraphCondenser.signature`
  already guarded itself the same way). Note `fairscape-cli build evidence-graph`
  hits `RecursionError` on the same crate — same structure, upstream, NOT fixed
  here per the user: crates must be DAGs.
- **`author` was the local Unix username.** nf-core dropped `manifest.author` for
  `manifest.contributors` (7 of 9 pipelines checked have no `author` at all), so
  every modern pipeline fell through to `System.getProperty('user.name')`.
  `manifestContributors()` reads `Manifest.Contributor`, preferring those whose
  `contribution` includes `author`, else naming everyone. Order:
  config.author → manifest.author → contributors → user.name.
- **Process Software claimed the PIPELINE's version** (a FastQC entity saying
  `1.2.0`). `toolVersions` + `util/VersionsYaml.groovy` read the real one from
  whichever place the module used: the per-task `versions.yml`, or the run-wide
  `pipeline_info/*_software_mqc_versions.yml` (newer modules report through
  `topic:` + `eval` and write NO file — pairgenomealign's LAST modules do this,
  which is why the per-task lookup alone found nothing). Collated keys are BARE
  process names (nf-core's `softwareVersionsToYAML` takes `tokenize(':').last()`),
  so look up `bareName` first, qualified second. MultiQC keeps the manifest
  fallback by construction: the collated file is built BEFORE MultiQC runs
  because MultiQC consumes it.
- **The samplesheet was invisible.** `params.input` is parsed in the workflow body
  by nf-schema, never staged into a task, so `getWorkflowInputs` never saw the
  run's primary input — 4 of 5 crates had ZERO csv Datasets and `schemas = true`
  produced nothing. `paramInputs` + `paramFiles()` register file-shaped param
  values (contains `/`, last segment has an extension → excludes `igenomes_base`,
  `custom_config_base`). Local must exist; a remote URI is recorded unfetched
  (though `schemas` will then read it to infer columns — demo's samplesheet is an
  https URL and gets a `sample,fastq_1,fastq_2` schema).
- Process ARK slugs, MultiQC comment-preamble schemas and the outputs explosion:
  see the ARK, schema-inference and find_outputs bullets above.
- **Process `description` was unbalanced source.** The old regex stripped only
  leading/trailing `"""`, but every nf-core module opens with a `def args =
  task.ext.args ?: ''` prologue, so the opening delimiter survived mid-text and
  its partner was deleted. `stripScriptDelimiters` strips the matched pair.
- Checked and NOT a risk: `ProvHelper.getTaskInputs` uses `Map.ofEntries`, which
  throws on duplicate keys — but Nextflow rejects colliding stage names itself
  ("Process input file name collision"), verified by trying to build one.
- Known-noisy, left alone: `ProvObserver` warns "Workflow output X should either
  be a single path or declare an index file" once per target on new-output-DSL
  pipelines (10x on seqinspector). Inherited from nf-prov and misleading here —
  `onFilePublish` captures those files anyway (75 of 80 published files described;
  the 5 misses are Nextflow's own `pipeline_info/` trace/report/timeline/DAG/params,
  which no publish event covers).

## Deviations from upstream nf-prov

- All renderers except Fairscape deleted (BCO/DAG/GEXF/WRROC); framework kept.
- `onTaskComplete` filters `task.failed || task.aborted` instead of
  `!task.isSuccess()`: native `exec:` tasks have exitStatus=Integer.MAX_VALUE so
  upstream drops them on fresh runs but includes them on -resume (inconsistent
  crates). Our filter includes them always.
- Observer logs render exceptions with full stack trace (upstream logged toString).

## Gotchas (all hit for real during development)

- Build needs Java 21 toolchain; machine has Java 17 → foojay-resolver-convention
  1.0.0 in `settings.gradle` auto-provisions. Don't remove it.
- Groovy `.unique()` MUTATES and throws UnsupportedOperation on map views → use
  `.unique(false)`.
- publishedFiles can contain **null source** (Nextflow-written index files) →
  mint from target (`entry.key ?: entry.value`).
- Nextflow rejects an EMPTY `fairscape { }` block ("Unknown config attribute") —
  empty scope is indistinguishable from a typo. ≥1 option or omit.
- After ANY code change run `make install` or Nextflow keeps using the stale
  plugin in `~/.nextflow/plugins/nf-fairscape-0.1.0`.
- Nextflow launcher installed at `~/.local/bin/nextflow` (25.10.4 standalone dist;
  there was no system nextflow). The `../nextflow` checkout is source, not a
  usable launcher.

## Test / verify

- `make test` — Spock units (`src/test/groovy/.../FairscapeRendererTest.groovy`
  pins ARK format/slugging/description floor; ProvObserverFactoryTest pins wiring;
  `datasheet/MiniJinjaTest` pins the Jinja subset; `datasheet/CrateArtifactsTest`
  pins the artifacts + condensation, incl. the "condensation must not leak into the
  crate" regression, fixture `src/test/resources/crates/fanout-crate.json`;
  `schema/TabularSchemaInferrerTest` pins the CLI parity — every expectation in it
  came from running `fairscape-cli schema infer` on the same fixture, so a failure
  means the port and frictionless diverged. Fixtures in `src/test/resources/tabular/`).
  The `nextflow.prov.parity.*` specs SKIP here unless fairscape-cli is importable.
- `make parity-test` — the CLI-parity suite, see the datasheet section above.
- `make fixtures` — regenerate `src/test/resources/parity/` (needs nextflow +
  `make install`). Only after the RENDERER's output changes: ARK suffixes hash the
  work dir, so a regenerated fixture reshuffles every id and the diff is large and
  meaningless. Say so in the commit message.
- CI (2026-07-31) is three workflows, one concern each: `build.yml` (compile +
  units, no Python, no Nextflow), `parity.yml` (installs the LATEST fairscape-cli
  from PyPI — deliberately unpinned, a CLI release that moves any output SHOULD
  turn it red — and runs the parity suite), `crate.yml` (installs the plugin, runs
  nf-fairscape-test AND examples/letters-chain under Nextflow, validates each
  crate). All on every push.
- `make verify` — install → run `nf-fairscape-test/` → validate via
  `nf-fairscape-test/validate_crate.py` = **the acceptance oracle**:
  `ROCrateV1_2.model_validate` (fairscape_models) + descriptor `conformsTo`
  RO-Crate 1.2 + `about` → root + dangling-ark check. Run
  standalone: `PYTHONPATH=~/fairscape/fairscape_models python3 nf-fairscape-test/validate_crate.py <crate>`.
  Expected on nf-fairscape-test: 9 Computations, **19** Datasets, 6 Software
  (script + engine + 4 processes), **2** Schemas. TABULATE publishes a DIRECTORY
  (`summary/` with summary.tsv + README.txt) purely so the run exercises
  expandDirectories + schemas; its config turns those and `checksums` on.
  (The 18th Dataset and 2nd Schema are `testdata.csv`, picked up from
  `params.input` by `paramInputs` — bumped from 17/1 on 2026-07-29. The 19th is
  `workflow/nextflow.config`, copied in by `includeWorkflow` — 2026-07-30.)
  Note ROCrateV1_2.model_validate MUTATES its input dict.
- `examples/reverse-list/` — minimal demo (user wants it kept).
- **`examples/fastquorum-like/` + `examples/bamtofastq-like/` (2026-07-31)** — the
  counterpart to `examples/nf-core/`: those run released pipelines UNMODIFIED, so none of
  them can carry an `ext fairscape:` block. These are nf-core/fastquorum 2.0.0 (rd +
  duplex_seq path) and nf-core/bamtofastq 2.2.1 (paired-end BAM path) flattened into one
  annotated `main.nf` each — the upstream module scripts with the nf-core plumbing
  (task.ext.args, stubs, conda, topic version channels) removed and one `ext fairscape:`
  block per process added. Real tools, real flags, the pipelines' own test data. Both pass
  validate_crate.py + check-crate.py + verify-against-run.py and score **28/28** AI-Ready.
  User's framing, follow it if you extend them: "an annotated version of theirs, don't get
  fancy" — stay close to the upstream module bodies rather than restructuring.
  Deliberate choices worth not re-litigating:
  * **`softwareVersion` is omitted from every ext block on purpose.** Each process writes a
    `versions.yml` and `toolVersions` reads it, so the Software version is what the
    container reported rather than a literal that drifts. This is also what keeps
    check-crate.py's version check live on these crates.
  * Where upstream ALIASES a module (fastquorum's `ALIGN_BAM` ×2; bamtofastq's
    `SAMTOOLS_VIEW` ×4, `SAMTOOLS_COLLATEFASTQ` ×2, `FASTQC` ×2), a single file cannot —
    aliasing needs a module boundary. fastquorum-like writes the two align processes out;
    bamtofastq-like feeds one process a channel carrying the variants. Same task
    Computations, fewer Software entities. Real aliasing coverage stays in
    `examples/nf-core/pairgenomealign`.
  * fastquorum-like uses ONE container (`fgbio_bwa_samtools`) where upstream uses three,
    to keep a first run to one big pull. Still 4.3 GB with FastQC — fgbio is a JDK+conda
    image and there is no smaller one.
  * fastquorum-like aligns against all of chr17 (84 MB) like the real test profile. An
    earlier draft added a `SUBSET_REFERENCE` step cutting the TP53 window; the user
    rejected it as editing theirs too much. Don't re-add it.
  * Confirmed NOT bugs, don't "fix" them: only 1 Schema per crate (fgbio metrics are
    tab-delimited but named `.txt`, and `TabularSchemaInferrer.supports()` gates on the
    extension — widening `schemaPatterns` cannot reach them; samtools stats/idxstats/
    flagstat are not tabular either). And nothing in `results/pipeline_info/` is in the
    crate — a `collectFile(storeDir:)` output and Nextflow's own trace/report/timeline/DAG
    are written directly, with no publish event for the observer to see.
- **`examples/nf-core/verify-against-run.py` — the accuracy oracle** (2026-07-30). The
  other checkers read the crate; a crate describing a DIFFERENT workflow perfectly passes
  both. This rebuilds the run from artifacts the plugin cannot influence (execution
  trace, `.command.sh`, the `nxf_stage()` block, leftover work-dir files) and diffs.
  Joins on `Computation.identifier` == task hash and `Dataset.md5` == the bytes. Crate
  edge unsupported by the run = FAIL; run edge the crate omits = counted, not failed.
  Run it as `verify-against-run.py <run dir>` (the dir holding results/ AND work/), not
  on a crate file. Four of five nf-core pipelines pass clean.
  **Two open findings it reports, neither fixed:**
  1. QUARTONOTEBOOK "generated" `differentialabundance_report.qmd`, which is a SYMLINK to
     an unmodified pipeline asset it merely read. nf-core's quartonotebook module declares
     the notebook in its `output:` block and the plugin trusts declared outputs, so the
     crate carries two Dataset nodes for identical bytes with contradictory provenance.
     Fix would be to drop a declared output that is a symlink resolving outside the task
     work dir.
  2. NOT a plugin bug, confirmed 2026-07-30 — under `-resume` only, a CACHED task's crate
     `command` is re-rendered this session while `.command.sh` on disk is the original
     session's. differentialabundance's SHINYNGS_APP differed only in the ORDER of a
     comma-joined `--differential_results` file list (upstream channel nondeterminism,
     same family as the run-level `generated` ordering noted above). A fresh non-`-resume`
     run of the same pipeline reports it clean, so always re-run without `-resume` before
     chasing a command mismatch.
  Gotchas already fixed in it, do not reintroduce: staging can be
  `mkdir -p d && ln -s src d/name` on ONE line; the crate holds the AUTHORED script
  template (indented) vs the rendered `.command.sh` (dedented + shebang + env epilogue),
  so compare stripped lines; directory `contentSize` is the RECURSIVE sum; `CACHED` is a
  success status.
- `run.sh` clears `<pipeline>/main.nf` and `nextflow.config` before each run. They are
  the PIPELINE's own files, copied in after a successful run so the graph can be read
  against them — but the run happens in that directory, so Nextflow auto-loaded the
  copied `nextflow.config`, which includes an uncopied `conf/base.config`. Every
  `-resume` died on it. Don't "tidy" the `rm -f` away.

Examples ship workflow definitions and configs only. Every crate artifact
(`ro-crate-metadata.json`, `ro-crate-datasheet.html`, `provenance-graph.*`,
`ai_ready_score.json`, `ro-crate-linkml.yaml`) is gitignored by name wherever it
lands, along with `results/`, `work/` and `nf_results*` — run an example to get
your own. Local run outputs are fine to keep on disk; they just never commit.

## Semantic ground truth (why the mapping is what it is)

The WRROC↔EVI mapping was worked out separately (a CSV-driven bridge study, kept
outside this repo). Key conclusions this plugin encodes:
WRROC CreateAction ≅ EVI Computation (object/result/instrument/agent →
usedDataset/generated/usedSoftware/runBy); WRROC's prospective layer
(FormalParameter/HowToStep/ControlAction/OrganizeAction) has NO EVI equivalent —
dropped, dataflow re-emerges from shared Dataset ARKs; EVI has one timestamp
(dateCreated) and no failure model. In the broader Nextflow-provenance strategy
this plugin is "Tier 2".
