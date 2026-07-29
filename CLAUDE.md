# nf-fairscape — AI context

Nextflow plugin (Groovy, Gradle) emitting FAIRSCAPE EVI RO-Crates. Fork of
nextflow-io/nf-prov v1.7.0 (sibling checkout at `../nf-prov`, Nextflow source at
`../nextflow`). Built and validated end-to-end 2026-07-20. Human-oriented tour:
`docs/WALKTHROUGH.md`; emission spec: `docs/FAIRSCAPE.md`; datasheet/evidence-graph
port: `docs/DATASHEET.md`. The user does NOT know Groovy/Nextflow — explain changes
in Python-adjacent terms and point to files.

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

- `@context` = verbatim DEFAULT_CONTEXT from
  `~/fairscape/fairscape_models/fairscape_models/fairscape_base.py`.
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
  sanctioned escape hatch. Documented in docs/FAIRSCAPE.md; demoed in both
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
  user explicitly asked for. Regression proof (redo it if you touch the
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
- **Oracle: `tools/parity.sh <crate dir>`** (needs fairscape-cli importable).
  Stage 0 link-inverses (compared on the PARSED graph — the two sides format
  JSON differently), stage 1 evidence graph, stage 2 linkml + datasheet.
  provenance-graph.json/.html, ro-crate-linkml.yaml and ai_ready_score.json are
  BYTE-IDENTICAL to the CLI's; ro-crate-datasheet.html differs only by the 3 summary stat cards
  (deliberate — the CLI's `evi:totalEntities == 0` guard never fires because
  pydantic dumps the declared field as None, so its per-crate fallback is dead
  code; we run it). Deviations enumerated in docs/DATASHEET.md.
- Gotcha (hit for real): condensation MUTATES the nodes it collapses
  (`usedDataset` → group ref). `EvidenceGraphBuilder` deep-copies every node into
  its index; without that the rewritten crate got a dangling `ark:group/...` ref.
- Not ported: sub-crates/previews, Croissant, Merkle tree, PDF — nf crates are
  always single crates. (LinkML/D4D and link-inverses WERE ported, see above.)

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
- `make verify` — install → run `nf-fairscape-test/` → validate via
  `nf-fairscape-test/validate_crate.py` = **the acceptance oracle**:
  `ROCrateV1_2.model_validate` (fairscape_models) + dangling-ark check. Run
  standalone: `PYTHONPATH=~/fairscape/fairscape_models python3 nf-fairscape-test/validate_crate.py <crate>`.
  Expected on nf-fairscape-test: 9 Computations, 17 Datasets, 6 Software
  (script + engine + 4 processes), 1 Schema. TABULATE publishes a DIRECTORY
  (`summary/` with summary.tsv + README.txt) purely so the run exercises
  expandDirectories + schemas; its config turns those and `checksums` on.
  Note ROCrateV1_2.model_validate MUTATES its input dict.
- `examples/reverse-list/` — minimal demo (user wants it kept, results included).

## Semantic ground truth (why the mapping is what it is)

WRROC↔EVI mapping solved in `~/fairscape/NewMoniWork/workflow_run_crate/wrroc/`
(CSV-driven bridge + REPORT.md/COMPARISON.md). Key conclusions this plugin encodes:
WRROC CreateAction ≅ EVI Computation (object/result/instrument/agent →
usedDataset/generated/usedSoftware/runBy); WRROC's prospective layer
(FormalParameter/HowToStep/ControlAction/OrganizeAction) has NO EVI equivalent —
dropped, dataflow re-emerges from shared Dataset ARKs; EVI has one timestamp
(dateCreated) and no failure model. Strategy doc:
`~/fairscape/NewMoniWork/NEXTFLOW_PROVENANCE.md` (this plugin = its "Tier 2").
Approved implementation plan: `~/.claude/plans/draft-a-plan-for-golden-diffie.md`.
