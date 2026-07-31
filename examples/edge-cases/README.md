# edge-cases example

Four processes, no containers, runs in seconds. Each one publishes something awkward. The
point is that the run ends with a crate that still validates — none of these may abort the
render or produce a dangling reference.

```bash
make install                                  # from the repo root, once
cd examples/edge-cases
nextflow run . -plugins nf-fairscape@0.1.0

PYTHONPATH=../../../../fairscape_models python3 ../../nf-fairscape-test/validate_crate.py \
    results/ro-crate-metadata.json
```

Expected: `VALID`, 5 Computations, 5 Datasets, 5 Schemas, 6 Software.

## What each process is for

| Process | Publishes | The question it asks |
| ------- | --------- | -------------------- |
| `MOVED` | `moved.tsv` with `mode: 'move'` | The work-directory source is *gone* by the time the crate is rendered. Can the Dataset still be sized, hashed and described? |
| `ODD_NAME` | `wéird name (1).csv` | Non-ASCII, spaces and parentheses in a filename, through slugging, JSON and a `contentUrl`. |
| `FAKE_CSV` | `binary.csv` (gzip bytes) | An extension that lies. Schema inference must not throw. |
| `EDGE_TABLES` | `empty.tsv`, `dupcols.tsv` | A zero-byte table, and a header whose three columns are all named `a`. |

## What to check

```bash
python3 -c "
import json
g = json.load(open('results/ro-crate-metadata.json'))['@graph']
idx = {e['@id']: e for e in g}
for d in g:
    if 'EVI#Dataset' not in str(d.get('@type')): continue
    ref = d.get('evi:schema')
    s = idx.get(ref['@id']) if isinstance(ref, dict) else None
    print(f\"{d['name']!r:34} size={d.get('contentSize')} md5={str(d.get('md5'))[:8]}\")
    print(f\"{'':34} cols={list((s or {}).get('properties') or {})}\")"
```

What you should see, and why:

- `moved.tsv` — sized, hashed, columns `x, y`. Datasets are described from the *published*
  copy when there is one, so `mode: 'move'` costs nothing.
- `wéird name (1).csv` — described, columns `a, b`. The ARK slug drops everything that isn't
  `[a-z0-9]`, so the identifier stays clean while `name` keeps the real filename.
- `dupcols.tsv` — columns `a, a2, a3`. Duplicate labels get an occurrence suffix, matching
  frictionless (and so `fairscape-cli schema infer`).
- `empty.tsv` — no columns. A schema entity with an empty `properties` is not useful; it is
  emitted for consistency rather than because it says anything.
- `binary.csv` — **columns are gibberish.** Inference trusts the extension and the first line
  of a gzip stream is not text. This is a known gap, not a passing case: the file is here so
  the failure stays visible and so that "does not crash the render" keeps being true. Contrast
  with the comment-preamble case, which *is* handled — see `schemaCommentChar` in
  [docs/CONFIGURATION.md](../../docs/CONFIGURATION.md).

## Not reproducible from here

Two inputs staged under the same name would break `ProvHelper.getTaskInputs`, which builds a
`Map.ofEntries` and throws on duplicate keys. You cannot get there: Nextflow rejects it first
with `Process input file name collision`. Verified by trying, twice.
