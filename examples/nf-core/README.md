# nf-core examples

The other examples are pipelines written to show one feature. These are **real released
[nf-core](https://nf-co.re) pipelines**, run unmodified, with nothing added but a `-c` config
that switches the plugin on. Every bug in the "Hardened against real nf-core pipelines"
section of `CLAUDE.md` was found by running one of them.

Nothing here is written by us. `run.sh` fetches each pipeline from GitHub the way you would run
it yourself, and copies its `main.nf` and `nextflow.config` in afterwards so the crate can be
read against the workflow that produced it:

```bash
nextflow run nf-core/demo -r 1.2.0 -profile test,docker --outdir results -c fairscape.config
```

## Running them

```bash
make install                    # from the repo root, once, after any plugin change
cd examples/nf-core

./run.sh                        # all five, in order (~20 min, mostly container pulls)
./run.sh demo 1.2.0             # or one at a time
```

Each pipeline gets its own directory here:

| | |
| --- | --- |
| `<pipeline>/main.nf`, `nextflow.config` | the workflow that ran, copied out of Nextflow's assets directory — so the provenance graph can be read against the thing that produced it |
| `<pipeline>/results/` | the crate and everything it describes; same layout as every other example, and git-ignored the same way |
| `<pipeline>/results/workflow/` | the same `main.nf` and configs, copied in by the plugin itself (`includeWorkflow`) — this is the copy the crate *points at*, so `results/` can be zipped and still say what ran. The pair above is for reading beside the run; this one is part of the crate |
| `<pipeline>/nf.out`, `.nextflow.log` | the raw Nextflow output and log |

The modules and subworkflows are not copied — there are hundreds and they would swamp the
directory. You do not need them to check the graph: every process Software entity in the crate
carries a `contentUrl` pointing at its own module file **pinned to the commit that ran**, and
its `description` is that process's script template verbatim. So a step in the graph can be
traced to its source without leaving the crate:

```bash
cd demo
python3 -c "
import json
g = json.load(open('results/ro-crate-metadata.json'))['@graph']
for s in g:
    if 'EVI#Software' in str(s.get('@type')) and s.get('isPartOf'):
        print(f\"{s['name'].split(':')[-1]:<14} {s.get('version'):<10} {s['contentUrl']}\")"
```

```
COWPY          1.1.5      https://github.com/nf-core/demo/tree/32893af.../modules/local/cowpy/main.nf
SEQTK_TRIM     1.4-r122   https://github.com/nf-core/demo/tree/32893af.../modules/nf-core/seqtk/trim/main.nf
FASTQC         0.12.1     https://github.com/nf-core/demo/tree/32893af.../modules/nf-core/fastqc/main.nf
```

If you do want the full tree, it is the checkout Nextflow already fetched:
`~/.nextflow/assets/nf-core/<pipeline>` on Nextflow 25.10, and
`~/.nextflow/assets/.repos/nf-core/<pipeline>/clones/<sha>/` on 26.04.

`run.sh` does three things after the run: greps the log for FAIRSCAPE warnings (a crate
failure never fails a run, so the exit code will not tell you), runs
`nf-fairscape-test/validate_crate.py` (the acceptance oracle — is this a valid EVI RO-Crate),
and runs `check-crate.py` (below).

Add `-resume` as a trailing argument to reuse a previous run's work directory. The crate is
re-rendered either way, which is what you want after changing the plugin:

```bash
./run.sh seqinspector 1.1.0 -resume
```

## The five, and what each one is for

| Pipeline | Rev | Tasks | Why it is in the set |
| -------- | --- | ----- | -------------------- |
| `demo` | 1.2.0 | 8 | Smoke test. Three modules, and MultiQC publishes directories, so `expandDirectories` gets exercised on the cheapest possible run. |
| `phyloplace` | 2.0.1 | 13 | Fifteen modules of real tools in real containers, and it finishes in seconds. The best one to run after a change. |
| `pairgenomealign` | 3.0.3 | 20 | Heaviest module aliasing — 24 aliases, nested subworkflows — which is what broke ARK slugs. Publishes MultiQC `*_mqc.tsv` tables, which is what broke schema inference. **Needs Nextflow ≥ 26.04.** |
| `differentialabundance` | 2.0.0 | 25 | The one that used to crash: `topic`-channel version collation makes the graph cyclic. Also R/Quarto `template` modules and ~46 real tables (GSEA, DESeq2) for schema inference. |
| `seqinspector` | 1.1.0 | 48 | Scale. ~1100 Datasets over 150 MB of output — this is where the outputs explosion and the graph size showed up. **Needs Nextflow ≥ 26.04.** |

Two of them require a newer Nextflow than the plugin's floor of 25.10, which is worth knowing
on its own: the plugin runs unchanged on 26.04.6, so there is no TraceObserverV2 breakage
between the two. If `nextflow -version` says 25.x, note that `NXF_VER=26.04.6` only works with
the *bash launcher* — a standalone `-dist` binary ignores it. Download the release binary and
point `NF` at it:

```bash
curl -sSL -o /tmp/nextflow-2604 \
    https://github.com/nextflow-io/nextflow/releases/download/v26.04.6/nextflow-26.04.6-dist
chmod +x /tmp/nextflow-2604
NF=/tmp/nextflow-2604 ./run.sh pairgenomealign 3.0.3
```

## check-crate.py

`validate_crate.py` answers *is this a valid EVI RO-Crate*. `check-crate.py` answers the
questions that only get wrong answers once a real pipeline is on the other end — each check is
a bug that actually happened:

| Check | The bug it guards |
| ----- | ----------------- |
| the graph is a DAG | a Nextflow-written file that was both consumed and published closed a cycle, and the evidence-graph walk never terminated |
| the evidence graph exists | that failure was silent: run succeeded, crate valid, `provenance-graph.json` simply absent |
| `author` is not the local user | nf-core replaced `manifest.author` with `manifest.contributors`, so crates were attributed to whoever ran them |
| process Software has a tool version | every entity carried the *pipeline's* release number instead |
| no schema headed by a comment | MultiQC tables open with `# id: '...'`, which became a one-column schema named after the comment |
| process ARK slugs are distinct | fully qualified nf-core names hit the 40-character slug cap, so six entities shared one id stem |

Run it on anything:

```bash
python3 check-crate.py demo/results/ro-crate-metadata.json
python3 check-crate.py */results/ro-crate-metadata.json     # all of them; exits 1 on any failure
```

Expected output per crate is the counts, the graph size, then `OK`. One note is normal:
`process(es) on the manifest version: MULTIQC` — MultiQC's own version cannot appear in the
collated versions file, because that file is written before MultiQC runs and handed to it as
input.

## verify-against-run.py

The other two checkers read the crate. Neither one looks at what actually ran, so **a crate
that describes a completely different workflow, perfectly, passes both.** This one closes
that gap: it rebuilds the run's provenance from Nextflow's own artifacts — which the plugin
does not write and cannot influence after the fact — and diffs the two.

```bash
python3 verify-against-run.py differentialabundance     # a run dir, not a crate file
python3 verify-against-run.py */                        # all of them; exits 1 on any failure
```

| Ground truth | Where it comes from | What it pins down |
| ------------ | ------------------- | ----------------- |
| the task list | `results/pipeline_info/execution_trace_*.txt` | every task that ran has a Computation, and vice versa |
| what each task ran | `work/<hash>/.command.sh` | `Computation.command` is that task's script |
| what it consumed | the `nxf_stage()` block in `work/<hash>/.command.run` | `usedDataset` — Nextflow generated this list from the resolved inputs and the shell executed it, so it cannot be wrong |
| what it produced | the files left in `work/<hash>/` | `generated` |
| which container | the `nxf_launch()` line | `containerImage` |

Four exact join keys make the diff non-heuristic: `Computation.identifier` **is** the task
hash, `Dataset.md5` **is** the bytes (so a file published under a new name still matches its
work-directory original), `contentUrl` resolves to a path, and `command` compares to
`.command.sh`.

The asymmetry is the point. **An edge the crate asserts that the run does not support is a
failure** — the crate is describing something that did not happen. An edge the run has that
the crate omits is reported but not failed, because unpublished intermediates are legitimately
out of scope; the counts just tell you how much of the run reached the graph.

Expected output is the Dataset locator split, the task count, the edge coverage, then `OK`.
Currently four of the five pass. differentialabundance reports one real defect, left open:

```
FAIL: 1 generated edge(s) the run does not support: QUARTONOTEBOOK (SRP254919) -> differentialabundance_report.qmd
```

The `.qmd` in that task's work directory is a symlink to an unmodified pipeline asset, staged
in and read. nf-core's quartonotebook module declares the notebook in its `output:` block, so
it lands in the task's declared outputs and the plugin trusts that list — leaving the crate
with two Dataset nodes for identical bytes that contradict each other, one correctly
attributed to the asset on GitHub and one claiming `generatedBy: QUARTONOTEBOOK`. The fix is
to drop a declared output that is a symlink resolving outside the task's work directory, since
that only ever means a staged input re-declared as an output.

**What it cannot see:** whether a published file is a real file or a symlink into `work/`.
Both sit under the crate root, so both get a crate-relative `contentUrl` and both resolve
here. Only `publishDir mode: 'copy'` makes the bytes travel with the crate; Nextflow's default
is `symlink`. These five pipelines set `publish_dir_mode = 'copy'`, so they are clean — see
[docs/FAIRSCAPE.md](../../docs/FAIRSCAPE.md#precondition-publish-with-mode-copy-if-you-want-a-portable-crate).

## What to look at

```bash
cd demo                         # or any of them
python3 -m json.tool results/ro-crate-metadata.json | less
xdg-open results/ro-crate-datasheet.html
xdg-open results/provenance-graph.html
```

The two things worth checking by eye, because they are what a reviewer of the crate would ask:

```bash
# does the crate name the tools that actually ran, with their real versions?
python3 -c "
import json
g = json.load(open('results/ro-crate-metadata.json'))['@graph']
for s in g:
    if 'EVI#Software' in str(s.get('@type')) and s.get('isPartOf'):
        print(f\"  {s['name'].split(':')[-1]:<32} {s.get('version')}\")"

# is the run's own input in there, or only the files it happened to stage?
python3 -c "
import json
g = json.load(open('results/ro-crate-metadata.json'))['@graph']
idx = {e['@id']: e for e in g}
root = next(e for e in g if 'ROCrate' in str(e.get('@type')))
print('inputs :', [idx[r['@id']]['name'] for r in root['https://w3id.org/EVI#inputs'] if r['@id'] in idx])
print('outputs:', len(root['https://w3id.org/EVI#outputs']))"
```
