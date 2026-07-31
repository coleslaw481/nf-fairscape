# letters-chain example

Three steps, a full provenance chain with saved intermediates:

1. `MAKE_LIST` — writes the first *n* letters (`--n`, default 8) to `letters.txt`
2. `REVERSE` — reverses them into `reversed.txt`
3. `SPLIT_HALVES` — splits that into `first_half.txt` and `second_half.txt`

Every step publishes to `results/`, so each intermediate becomes an `EVI:Dataset` and the
Computations chain through them:

```
                    MAKE_LIST ──generated──> letters.txt
letters.txt  ──used──> REVERSE ──generated──> reversed.txt
reversed.txt ──used──> SPLIT_HALVES ──generated──> first_half.txt, second_half.txt
```

Each Computation also has `isPartOf` → the run Computation, each Dataset the inverse
`generatedBy`.

```bash
nextflow run . -plugins nf-fairscape@0.1.0            # 8 letters
nextflow run . -plugins nf-fairscape@0.1.0 --n 12     # or pick a length

python3 -m json.tool results/ro-crate-metadata.json | less
```

`--n` is a scalar (`val`) input, so it isn't a Dataset — it appears in the run Computation's
`parameter` list (`n: 8`) and in the `MAKE_LIST` command (`head -n 8`).

**Software.** Every step carries an `ext fairscape: [...]` annotation, so each Software
entity names the coreutils tool it runs (`head`, `tac`, `wc`/`head`/`tail`) rather than the
process. It's optional per process — drop it and that entity falls back to the default.

**Root metadata.** Core fields via the dedicated options (`author`, `description`,
`keywords`, `license`, `organization` → `publisher`); the long tail via `fairscape.metadata`
(`name`, `principalInvestigator`, `funder`, `associatedPublication`, `citation`,
`contactEmail`, `conditionsOfAccess`, `copyrightNotice`). Every key both hooks accept:
`docs/CONFIGURATION.md`.

```bash
xdg-open results/provenance-graph.html    # the full chain, interactive
xdg-open results/ro-crate-datasheet.html  # datasheet + AI-Readiness score
```

The graph is rooted at the crate, so both halves and everything upstream are in one picture.
For a graph rooted at one entity: `fairscape-cli build evidence-graph results <ark>`.

Print the chain from the crate:

```bash
python3 - <<'EOF'
import json
g = json.load(open('results/ro-crate-metadata.json'))['@graph']
name = {n['@id']: n.get('name','?') for n in g}
for n in g:
    if 'Computation' in str(n['@type']):
        used = ', '.join(name[r['@id']] for r in n.get('usedDataset', []))
        gen  = ', '.join(name[r['@id']] for r in n.get('generated', []))
        print(f"{n['name']:35} used: [{used}]  generated: [{gen}]")
EOF
```
