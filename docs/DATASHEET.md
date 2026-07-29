# Datasheet and provenance graph

After a successful run the plugin does what you would otherwise run
`fairscape build` for: it turns the crate it just wrote into the human-facing
artifacts. Everything is a Groovy port of `fairscape-cli`, living in
`src/main/groovy/nextflow/prov/datasheet/`, so no Python is involved at runtime.

```
results/
  ro-crate-metadata.json     # the crate (now also carries EVI:inputs/outputs)
  provenance-graph.json      # evidence graph rooted at the crate
  provenance-graph.html      # interactive, self-contained viewer
  ro-crate-datasheet.html    # the datasheet
  ai_ready_score.json        # AI-Readiness rubric behind the datasheet's donut
```

Both steps are on by default and are best-effort: if one fails the run still
succeeds and the crate is left intact (the failure is logged, with the stack
trace at `DEBUG` in `.nextflow.log`).

```groovy
fairscape {
    datasheet     = false   // skip ro-crate-datasheet.html + ai_ready_score.json
    evidenceGraph = false   // skip provenance-graph.json/.html + EVI:inputs/outputs
    published     = true    // render the datasheet as a published release
}
```

## What runs, in order

The sequence mirrors the CLI's `fairscape build subcrate` pipeline.

1. **`EVI:inputs` / `EVI:outputs` on the crate root**
   (port of `fairscape_cli.entailments.find_outputs`). Outputs are the datasets
   no computation consumed — for a workflow, the terminal published files.
   Inputs are the samples, the consumed datasets nothing generated, and any
   standalone dataset. They are written under their full URIs
   (`https://w3id.org/EVI#outputs`), which is what the CLI and the FAIRSCAPE
   server read.

2. **Evidence graph** (`fairscape build evidence-graph`). Rooted at the crate
   itself, which is why step 1 has to come first: the builder starts from the
   crate's declared outputs and walks *backwards* along `generatedBy` →
   `usedDataset`/`usedSoftware` until the chain runs out. The result is the
   hierarchical `@graph` map the FAIRSCAPE web client renders, plus a
   `condensation_stats` summary.

   Anything not on a `generatedBy`/`used*` edge is unreachable by design — the
   run-level Computation and the workflow-script/Nextflow-engine Software do not
   appear in the evidence graph, only in the crate.

   **Condensation** matters for scattered workflows: when more than five sibling
   datasets with an identical provenance signature (same format, schema, software
   chain and input signature) feed one Computation, they collapse into a single
   `EVI:DatasetGroup` node keeping one representative plus `evi:memberIds`. A
   100-sample scatter renders as one node instead of a hairball.

3. **Datasheet** (`fairscape build datasheet`), rendered from the crate through
   the same HTML templates the CLI ships, vendored verbatim under
   `src/main/resources/fairscape/templates/`. The AI-Readiness score is computed
   from the crate (28 sub-criteria across 7 categories) and written next to the
   datasheet as `ai_ready_score.json`.

The crate is rewritten once at the end of step 2 to add `localEvidenceGraph`
(pointing at `provenance-graph.html`), keeping the renderer's JSON formatting so
the file differs only by the fields that were added.

## Where each piece came from

| Groovy | ported from (fairscape-cli / fairscape_models / fairscape_graph_tools) |
| --- | --- |
| `CrateArtifacts` | `commands/build_commands.py` (`build datasheet`, `build evidence-graph`), `entailments/find_outputs.py` |
| `EvidenceGraphBuilder` | `evidence_graph_builder.py`, `pipeline/evidence_graph.py`, `interpret/local_graph.py` |
| `GraphCondenser` | `pipeline/condense.py`, `pipeline/graph_utils.py` |
| `EvidenceGraphHtml` | `datasheet_builder/evidence_graph/html_builder.py` |
| `DatasheetGenerator` | `datasheet_builder/rocrate/{datasheet_generator,section_generators,summary_generator}.py`, `conversion/mapping/FairscapeDatasheet.py` |
| `CompositionBuilder` | `conversion/mapping/subcrate_utils.py` |
| `AiReadyScorer` | `conversion/mapping/AIReady.py`, `conversion/models/AIReady.py` |
| `MiniJinja` / `ExpressionTranslator` | the Jinja2 subset the templates use |
| `PyJson` | `json.dump(..., indent=2)` byte compatibility |

## Templates

The templates are copies, not rewrites — updating them is a file copy from
`fairscape-cli/src/fairscape_cli/datasheet_builder/templates/`. `MiniJinja`
implements the Jinja2 subset they need (`if`/`elif`/`else`, `for` with
`loop.index`/`loop.last` and `k, v` unpacking, `set`, comments, the
`safe`/`lower`/`join`/`list`/`int`/`round`/`length` filters, `is string` /
`is mapping`, `in`, `startswith`/`endswith`/`keys`/`items`/`split`), including
Jinja's `trim_blocks`/`lstrip_blocks` whitespace rules, so output is
byte-identical. Expressions are translated to Groovy and evaluated with a
`GroovyShell`; an unknown name resolves to null, which renders as empty and is
falsy — Jinja's `Undefined` semantics.

If a template ever uses something outside that subset, rendering throws
`Unsupported template tag/filter/test`, which the observer catches: you lose the
datasheet, never the crate.

## Parity with the CLI

`tools/parity.sh <crate dir>` runs both implementations over the same inputs and
diffs the results. It needs `fairscape-cli` importable by the local `python3`.

```bash
tools/parity.sh examples/letters-chain/results
```

`provenance-graph.json`, `provenance-graph.html` and `ai_ready_score.json` come
out **byte-identical**. `ro-crate-datasheet.html` differs by exactly one block —
see below.

### Deviations

- **Summary stat cards (deliberate).** The CLI reads `evi:totalEntities` from a
  pydantic dump where the field is declared, so it reads back as `None` rather
  than absent; its `== 0` guard therefore never fires and the per-crate fallback
  count is skipped. Every single crate renders "N/A" entities and no
  Datasets/Computations/Software cards. This port runs the fallback, so those
  three cards show real counts. This is the only content difference in the
  datasheet, and it is why `tools/parity.sh` reports a ~12-line diff.
- **Formats chip list.** Kept as-is, i.e. empty: the CLI reads `fileFormat` from
  an alias-dumped entity whose key is `format`, so it never collects anything.
  Reproduced so the rest of the summary matches.
- **Pattern ordering.** `computation_patterns` / `experiment_patterns` come from
  a Python `set`, whose iteration order varies between interpreter runs. This
  port preserves discovery order instead. Same members, stable order.
- **`evi:provenanceSignature`.** The signature string is a Groovy rendering of
  the same nested tuple; grouping is identical, but the ordering *inside* the
  printed signature may differ from CPython's tuple sort. Nothing reads it.
- **`localEvidenceGraph`** is recorded as the crate-relative
  `provenance-graph.html`; the CLI records whatever path string it was invoked
  with.

### Not ported

Deliberately out of scope, because a crate emitted by this plugin is always a
single crate with no nested sub-crates:

- sub-crate discovery, the per-sub-crate `ro-crate-preview.html`, and the
  release-level `build release` pipeline
- the Croissant export and the Merkle tree
- PDF export (the CLI shells out to Playwright/Chromium)

Run `fairscape build ...` on the results directory if you want any of these.

The LinkML sidecar (`ro-crate-linkml.yaml`) and the `link-inverses` entailment
were on this list until 2026-07-28 and are now ported — see
[FAIRSCAPE.md § D4D / LinkML export](FAIRSCAPE.md#d4d--linkml-export) and
[§ Inverse relationships](FAIRSCAPE.md#inverse-relationships). `tools/parity.sh`
diffs both against the CLI.
