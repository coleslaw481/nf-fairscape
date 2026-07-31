# reverse-list example

Minimal pipeline: build a three-item list, reverse it with `tac`, publish `reversed.txt`.
The crate lands next to the output.

```bash
make install                                  # from the repo root, once
cd examples/reverse-list
nextflow run . -plugins nf-fairscape@0.1.0

cat results/reversed.txt
python3 -m json.tool results/ro-crate-metadata.json | less
```

The crate holds a run Computation, the `REVERSE` task Computation, the `list.txt` input
Dataset, and the published `reversed.txt` Dataset with a `generatedBy` edge back to the task.

**Software.** `REVERSE` carries an `ext fairscape: [...]` annotation, so its Software entity
is `tac` — name, version, author, description, URL and keywords — instead of the
process-derived default. Full key list: `docs/CONFIGURATION.md`.

**Root metadata.** Core fields use the dedicated options (`author`, `description`,
`keywords`, `license`, `organization` → `publisher`); the rest goes in `fairscape.metadata`,
here `name`, `principalInvestigator`, `funder`, `associatedPublication`, `citation`,
`contactEmail`, `conditionsOfAccess`, `copyrightNotice`. Inspect the assembled root:

```bash
python3 -c "import json; r=[n for n in json.load(open('results/ro-crate-metadata.json'))['@graph'] if 'ROCrate' in str(n.get('@type'))][0]; [print(f'{k:22} {r[k]}') for k in ('name','description','author','keywords','license','publisher','funder','principalInvestigator','associatedPublication','citation','contactEmail','conditionsOfAccess','copyrightNotice') if k in r]"
```

The derived artifacts land beside the crate:

```bash
xdg-open results/provenance-graph.html    # interactive evidence graph
xdg-open results/ro-crate-datasheet.html  # datasheet + AI-Readiness score
```

The graph is rooted at the crate, so it covers every output at once. For a single entity,
use the CLI: `fairscape-cli build evidence-graph results <ark>`.

Validate:

```bash
PYTHONPATH=/path/to/fairscape_models python3 ../../nf-fairscape-test/validate_crate.py results/ro-crate-metadata.json
```
