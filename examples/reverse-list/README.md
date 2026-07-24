# reverse-list example

Minimal pipeline: builds a three-item list, reverses it with `tac`, and publishes
`reversed.txt`. The plugin writes the EVI RO-Crate next to the published output.

```bash
# from the repo root, install the plugin once
make install

# run the example
cd examples/reverse-list
nextflow run . -plugins nf-fairscape@0.1.0

# view the output and the crate
cat results/reversed.txt
python3 -m json.tool results/ro-crate-metadata.json | less
```

The crate contains one run-level Computation, one `REVERSE` task Computation, the
`list.txt` input Dataset, and the published `reversed.txt` Dataset with a
`generatedBy` edge back to the task.

### Describing the software

The `REVERSE` process carries an `ext fairscape: [...]` annotation describing the
actual tool it runs, so the Software entity in the crate is `tac` with a full
`softwareName` / `softwareVersion` / `softwareAuthor` / `softwareDescription` /
`softwareUrl` / `softwareKeywords` set instead of the process-derived default. Every
`software*` key attaches to that process's Software entity; see `docs/FAIRSCAPE.md`
for the full list.

### Describing the run (root metadata)

Core crate fields are set with dedicated `fairscape` options — `author`,
`description`, `keywords`, `license`, and `organization` (→ `publisher`). Everything
else goes in `fairscape.metadata`, a map merged onto the root RO-Crate entity: this
example adds `name`, `principalInvestigator`, `funder`, `associatedPublication`,
`citation`, `contactEmail`, `conditionsOfAccess`, and `copyrightNotice`. Inspect the
assembled root with:

```bash
python3 -c "import json; r=[n for n in json.load(open('results/ro-crate-metadata.json'))['@graph'] if 'ROCrate' in str(n.get('@type'))][0]; [print(f'{k:22} {r[k]}') for k in ('name','description','author','keywords','license','publisher','funder','principalInvestigator','associatedPublication','citation','contactEmail','conditionsOfAccess','copyrightNotice') if k in r]"
```

Build the provenance graph for the published output (JSON + interactive HTML):

```bash
fairscape-cli build evidence-graph results <ark of reversed.txt from the crate>
```

Optionally validate against the `fairscape_models` schema:

```bash
PYTHONPATH=/path/to/fairscape_models python3 ../../nf-fairscape-test/validate_crate.py results/ro-crate-metadata.json
```
