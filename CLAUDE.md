# nf-fairscape — AI context

Nextflow plugin (Groovy, Gradle) emitting FAIRSCAPE EVI RO-Crates. Fork of
nextflow-io/nf-prov v1.7.0 (sibling checkout at `../nf-prov`, Nextflow source at
`../nextflow`). Built and validated end-to-end 2026-07-20. Human-oriented tour:
`docs/WALKTHROUGH.md`; emission spec: `docs/FAIRSCAPE.md`. The user does NOT know
Groovy/Nextflow — explain changes in Python-adjacent terms and point to files.

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
   fails the run; stack trace logged at DEBUG to .nextflow.log).
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
  pins ARK format/slugging/description floor; ProvObserverFactoryTest pins wiring).
- `make verify` — install → run `nf-fairscape-test/` → validate via
  `nf-fairscape-test/validate_crate.py` = **the acceptance oracle**:
  `ROCrateV1_2.model_validate` (fairscape_models) + dangling-ark check. Run
  standalone: `PYTHONPATH=~/fairscape/fairscape_models python3 nf-fairscape-test/validate_crate.py <crate>`.
  Expected on nf-fairscape-test: 8 Computations, 14 Datasets, 5 Software
  (script + engine + 3 processes).
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
