# cycle-repro example

Two processes, no containers, runs in seconds. It exists to reproduce one bug and to keep it
fixed: a provenance graph that is not a DAG.

```bash
make install                                  # from the repo root, once
cd examples/cycle-repro
nextflow run . -plugins nf-fairscape@0.1.0

ls results/provenance-graph.json              # this file is the whole point
```

## The shape

`collated_versions.yml` is written by **Nextflow**, not by a task — `collectFile` produces it.
That means:

- no task generated it, so it counts as an input the *run* used, and
- it is published, so it counts as something the *run* generated.

Complete the `owl:inverseOf` pairs and the file gains `generatedBy: <the run>` while the run
still lists it under `usedDataset`. Two nodes, one cycle, and the backwards walk that builds
the evidence graph never terminates.

This is not a synthetic shape. nf-core 3.4+ collects tool versions exactly this way
(`channel.topic('versions').collectFile(...)`), which is how it was found — on
nf-core/differentialabundance 2.0.0, where the run finished, the crate was written and valid,
and `provenance-graph.json`/`.html` were silently missing.

## What to check

```bash
# 1. the artifact that used to be missing
ls -la results/provenance-graph.json results/provenance-graph.html

# 2. no StackOverflowError in the log
grep -c StackOverflowError .nextflow.log        # 0

# 3. the crate root points at the viewer
python3 -c "
import json
g = json.load(open('results/ro-crate-metadata.json'))['@graph']
root = next(e for e in g if 'ROCrate' in str(e.get('@type')))
print(root['localEvidenceGraph'])"

# 4. the graph really is acyclic
python3 ../nf-core/check-crate.py results/ro-crate-metadata.json
```

The fix is at emission, not in the walk: a Computation's `usedDataset` never contains anything
from its own `generated`, because nothing can be its own ancestor. The file stays an output of
the run, and the task that read it keeps its own `usedDataset` edge, so no evidence is lost —
`CONSUME.usedDataset` still names `collated_versions.yml`:

```bash
python3 -c "
import json
g = json.load(open('results/ro-crate-metadata.json'))['@graph']
idx = {e['@id']: e for e in g}
for e in g:
    if 'Computation' in str(e.get('@type')):
        used = [idx[r['@id']]['name'] for r in e.get('usedDataset', []) if r['@id'] in idx]
        print(f\"{e['name'][:44]:<46} used={used}\")"
```
