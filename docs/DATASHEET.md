# Datasheet and provenance graph

After a successful run the plugin does what you'd otherwise run `fairscape build` for. It's
a Groovy port of `fairscape-cli` under `src/main/groovy/nextflow/prov/datasheet/`, so no
Python is involved at runtime.

```
results/
  ro-crate-metadata.json     # the crate (now also carries EVI:inputs/outputs)
  provenance-graph.json      # evidence graph rooted at the crate
  provenance-graph.html      # interactive, self-contained viewer
  ro-crate-datasheet.html    # the datasheet
  ai_ready_score.json        # AI-Readiness rubric behind the donut
  ro-crate-linkml.yaml       # crate root as a D4D document
```

All of it is on by default and best-effort: if a step fails the run still succeeds and the
crate is intact (logged, stack trace at `DEBUG` in `.nextflow.log`). Options —
`datasheet`, `evidenceGraph`, `linkInverses`, `linkml`, `published` — are documented in
[CONFIGURATION.md](CONFIGURATION.md#derived-artifacts); what fills in the score is
[What moves the AI-Ready score](CONFIGURATION.md#what-moves-the-ai-ready-score).

## What runs, in order

Mirrors the CLI's `fairscape build subcrate`.

1. **Link inverses** — complete EVI's 18 `owl:inverseOf` pairs. See
   [FAIRSCAPE.md](FAIRSCAPE.md#inverse-relationships).
2. **`EVI:inputs`/`EVI:outputs` on the root** (port of `entailments.find_outputs`). Outputs
   are datasets no computation consumed; inputs are samples, consumed datasets nothing
   generated, and standalone datasets. Written under their full URIs, which is what the CLI
   and the FAIRSCAPE server read.
3. **Evidence graph** (`build evidence-graph`), rooted at the crate — which is why step 2
   comes first. It starts from the declared outputs and walks *backwards* along
   `generatedBy` → `usedDataset`/`usedSoftware`. Anything not on such an edge is unreachable
   by design: the run-level Computation and the script/engine Software appear in the crate,
   not the graph.

   **Condensation:** when more than five sibling datasets with an identical provenance
   signature (format, schema, software chain, input signature) feed one Computation, they
   collapse into one `EVI:DatasetGroup` keeping a representative plus `evi:memberIds`. A
   100-sample scatter renders as one node instead of a hairball.
4. **LinkML/D4D** — see [FAIRSCAPE.md](FAIRSCAPE.md#d4d--linkml-export).
5. **Datasheet** (`build datasheet`), rendered through the CLI's own HTML templates. The
   AI-Readiness score (28 sub-criteria, 7 categories) is written next to it.

The crate is rewritten once after step 3 to add `localEvidenceGraph`, keeping the renderer's
JSON formatting so it differs only by the added fields.

## Where each piece came from

| Groovy | ported from |
| --- | --- |
| `CrateArtifacts` | `commands/build_commands.py`, `entailments/find_outputs.py` |
| `InverseLinker` | `augment link-inverses` (the 18 pairs carried as data, not re-derived) |
| `EvidenceGraphBuilder` | `evidence_graph_builder.py`, `pipeline/evidence_graph.py`, `interpret/local_graph.py` |
| `GraphCondenser` | `pipeline/condense.py`, `pipeline/graph_utils.py` |
| `EvidenceGraphHtml` | `datasheet_builder/evidence_graph/html_builder.py` |
| `DatasheetGenerator` | `datasheet_builder/rocrate/*.py`, `conversion/mapping/FairscapeDatasheet.py` |
| `CompositionBuilder` | `conversion/mapping/subcrate_utils.py` |
| `AiReadyScorer` | `conversion/{mapping,models}/AIReady.py` |
| `D4dConverter` / `PyYaml` | `build linkml` + `yaml.dump` |
| `MiniJinja` / `ExpressionTranslator` | the Jinja2 subset the templates use |
| `PyJson` | `json.dump(..., indent=2)` byte compatibility |

## Templates

Verbatim copies under `src/main/resources/fairscape/templates/` — update by re-copying from
`fairscape-cli/src/fairscape_cli/datasheet_builder/templates/`, not by editing. `MiniJinja`
implements the subset they need (`if`/`elif`/`else`, `for` with `loop.index`/`loop.last`,
`set`, comments, the `safe`/`lower`/`join`/`list`/`int`/`round`/`length` filters,
`is string`/`is mapping`, `in`, `startswith`/`endswith`/`keys`/`items`/`split`) plus Jinja's
`trim_blocks`/`lstrip_blocks` rules, so output is byte-identical. Anything outside it throws
`Unsupported template tag/filter/test`, which the observer catches: you lose the datasheet,
never the crate.

## Parity with the CLI

fairscape-cli is the ground truth. Parity is checked two ways, and both need it importable by
the local `python3` (override the invocation with `FAIRSCAPE_CLI=...`):

```bash
make parity-test                 # the suite: frozen fixtures, a CI check, pass/fail
tools/parity.sh <crate dir>      # the tool: any crate directory, prints the diffs
```

`src/test/groovy/nextflow/prov/parity` runs both implementations over three committed crates
and asserts on the result. Each spec hands the SAME crate directory to both sides, so a
failure means the two diverged rather than that they were given different inputs:

| Spec | Ported from | Claim |
| ---- | ----------- | ----- |
| `InverseLinkerParityTest` | `augment link-inverses` | identical parsed graph |
| `InputsOutputsParityTest` | `augment add-io` | identical, minus the `isPartOf` deviation below |
| `EvidenceGraphParityTest` | `build evidence-graph` | `provenance-graph.json`/`.html` **byte-identical** |
| `DatasheetParityTest` | `build datasheet` | `ro-crate-linkml.yaml` and `ai_ready_score.json` **byte-identical**; `ro-crate-datasheet.html` identical after the two normalizations below |
| `SchemaInferParityTest` | `schema infer` | identical document minus `fairscapeVersion` and the CLI's uuid `@id` |

The fixtures are real crates, frozen by `tools/make-parity-fixtures.sh` with every derived
artifact turned off — deriving them is what is being tested. `letters-chain` is a plain
publish chain, `nf-test` adds expanded directories, checksums and an inferred schema, and
`fanout` is the synthetic shape that reaches `GraphCondenser`.

`tools/parity.sh` is the same comparison run interactively against **any** crate directory,
including real pipeline output — reach for it when a crate from a live run disagrees.

CI runs the suite against the **latest fairscape-cli from PyPI**, deliberately unpinned: a CLI
release that changes any of these outputs should turn the check red. `FAIRSCAPE_PARITY_REQUIRED=1`
(which `make parity-test` sets) turns a missing CLI into a failure instead of a silent skip.

### Deviations

- **Summary stat cards (deliberate).** The CLI reads `evi:totalEntities` from a pydantic dump
  where the field is declared, so it reads back as `None` rather than absent; its `== 0` guard
  never fires and the per-crate fallback is dead code. Every CLI crate renders "N/A" entities
  and no Datasets/Computations/Software cards. We run the fallback and show real counts. This
  is the only content difference, and the reason `parity.sh` reports a ~12-line diff.
- **Formats chip list.** Kept empty: the CLI reads `fileFormat` from an entity whose key is
  `format`, so it never collects anything. Reproduced so the rest of the summary matches.
- **Pattern ordering (unreachable, not a choice).** `computation_patterns`/`experiment_patterns`
  are built with `list(set(...))` in `fairscape_models/conversion/mapping/subcrate_utils.py`,
  so the CLI's OWN datasheet reorders them between runs — confirmed by running `build datasheet`
  under several `PYTHONHASHSEED` values on one crate and getting two different files. Byte
  parity on that line is impossible for anyone; the content is a set either way. We preserve
  discovery order, which at least is stable, and `DatasheetParityTest` sorts the list before
  comparing. (`ro-crate-linkml.yaml` and `ai_ready_score.json` were checked the same way and
  are deterministic.)
- **`evi:provenanceSignature`.** Same grouping, but the ordering inside the printed signature
  may differ from CPython's tuple sort. Nothing reads it.
- **`localEvidenceGraph`** is the crate-relative `provenance-graph.html`; the CLI records
  whatever path it was invoked with.
- **Files inside a published directory are not crate outputs (deliberate).** `find_outputs`
  calls a Dataset an output when no computation consumed it, which with `expandDirectories`
  makes every file inside a published folder a top-level output of the crate. We skip Datasets
  that carry `isPartOf`: the directory Dataset stands for its contents, the same rule that
  keeps them off `Computation.generated`. On nf-core/seqinspector this took the root from 1020
  outputs to 35, and the evidence graph from 1187 nodes (a 1 MB viewer) to 202. `parity.sh`
  cannot see this deviation — it hands the Groovy-computed `EVI:outputs` to the CLI side —
  but `InputsOutputsParityTest` does: it asserts the two agree on inputs exactly, that we
  never claim an output the CLI does not, and that every output we drop is one of these
  `isPartOf` Datasets. A divergence of any other shape fails.
- **A cyclic crate no longer recurses forever.** `projectNode` keeps an `inFlight` set, so a
  back edge is not followed instead of exhausting the stack. `fairscape-cli build
  evidence-graph` raises `RecursionError` on such a crate. The renderer does not emit one:
  a Computation's `usedDataset` never contains anything from its own `generated`.

### Not ported

A crate from this plugin is always a single crate, so: sub-crate discovery,
`ro-crate-preview.html`, `build release`, the Croissant export, the Merkle tree, and PDF
export (the CLI shells out to Playwright). Run `fairscape build ...` on the results directory
if you want any of them.
