# bamtofastq-like example

[nf-core/bamtofastq 2.2.1](https://nf-co.re/bamtofastq/2.2.1/docs/usage/), annotated.

The companion to [`../fastquorum-like`](../fastquorum-like): `examples/nf-core/` runs
released pipelines **unmodified**, so nothing there can carry an `ext fairscape:` block.
This is the same tools, the same flags and the same test data flattened into one `main.nf`
that *is* annotated.

Aligned reads back out to FASTQ, with QC either side. The reason it is not one
`samtools fastq` call is the shape of the problem: a coordinate-sorted BAM has the two
mates of a pair far apart, and the records where one or both mates failed to map have to be
collated separately from the ones where both mapped. So the BAM is split four ways by
mapping state, three of those are merged back, both halves are collated and converted, and
the two FASTQ pairs are concatenated. That fan-out and fan-in is the interesting part of
the provenance graph.

```bash
make install                    # from the repo root, once, after any plugin change
cd examples/bamtofastq-like
nextflow run .

python3 -m json.tool results/ro-crate-metadata.json | less
xdg-open results/provenance-graph.html
xdg-open results/ro-crate-datasheet.html
```

Thirty-two tasks over two samples, under a minute after the first run. Downloads: 380 kB of
test data (two chr22 BAMs), and four containers — samtools, FastQC, coreutils and
fastq_utils, about 1.6 GB together, of which FastQC is most.

## What it runs

| Process | Tool | From |
| ------- | ---- | ---- |
| `SAMTOOLS_INDEX` | samtools index | `subworkflows/local/prepare_indices` — the samplesheet leaves `index` empty, so this runs |
| `SAMTOOLS_IDXSTATS`, `SAMTOOLS_FLAGSTAT`, `SAMTOOLS_STATS` | samtools | `subworkflows/local/pre_conversion_qc` |
| `FASTQC` | FastQC | before conversion on the BAM, after it on the FASTQs |
| `CHECKPAIREDEND` | samtools view | `modules/local/checkpairedend` — reads the flags of the first 1000 records rather than trusting the samplesheet |
| `SAMTOOLS_VIEW` | samtools view | four flag sets: `map_map`, `unmap_unmap`, `unmap_map`, `map_unmap` |
| `SAMTOOLS_MERGE` | samtools merge | the three unmapped selections back into one BAM |
| `SAMTOOLS_COLLATEFASTQ` | samtools collate \| samtools fastq | mapped and unmapped halves, `-N` to keep the `/1` `/2` suffixes |
| `CAT_FASTQ` | cat | mapped R1 + unmapped R1, mapped R2 + unmapped R2 |
| `FASTQUTILS_INFO` | fastq_utils fastq_info | validates the pair — mismatched or truncated records exit non-zero |

Left out, and why: CRAM support and the reference FASTA plumbing that goes with it, the
`--chr` region-extraction branch, the single-end branch, and MultiQC. The test data is
paired-end BAM, so none of those would run.

**Aliasing.** nf-core/bamtofastq aliases `SAMTOOLS_VIEW` four times, `SAMTOOLS_COLLATEFASTQ`
twice and `FASTQC` twice. A process can only be aliased across a module boundary and this
is one file, so each of those is one process fed a channel carrying the variants. The task
Computations come out the same — eight `samtools view` tasks, four collate tasks, four
FastQC tasks — but they share one Software entity each instead of one per alias. If you want
to see how the plugin handles real aliasing, that is
[`examples/nf-core/pairgenomealign`](../nf-core/README.md), which has 24 of them.

## The `ext fairscape:` blocks

Every process has one, naming the tool it runs rather than the Nextflow process:

```groovy
process SAMTOOLS_COLLATEFASTQ {
    ext fairscape: [
        softwareName       : 'samtools collate + samtools fastq',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'samtools collate shuffles a coordinate-sorted alignment file so the two reads of each pair sit next to each other ...',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-fastq.html',
        softwareKeywords   : ['samtools', 'htslib', 'bam-to-fastq', 'read-collation']
    ]
```

This pipeline is nine-tenths samtools, which is exactly the case the annotation exists for:
without it every Software entity would be named after a Nextflow process, and the crate
could not say that `SAMTOOLS_VIEW` and `CHECKPAIREDEND` both run `samtools view` while
`SAMTOOLS_COLLATEFASTQ` runs two subcommands in a pipe.

**`softwareVersion` is deliberately absent from all eleven.** Each process emits a
`versions.yml` instead, and `toolVersions` (on by default) reads the version out of it, so
what lands on the entity is what the container reported at run time:

```bash
python3 -c "
import json
g = json.load(open('results/ro-crate-metadata.json'))['@graph']
for s in g:
    if 'EVI#Software' in str(s.get('@type')) and s.get('isPartOf'):
        print(f\"  {s['name']:<36} {s.get('version')}\")"
```

```
  samtools index                       1.23.1
  samtools view (paired-end check)     1.23.1
  samtools collate + samtools fastq    1.23.1
  FastQC                               0.12.1
  cat                                  9.5
  fastq_utils fastq_info               0.25.3
```

`cat 9.5` is the one worth noticing: it is GNU coreutils' version, read out of the
container the task ran in, and nothing in this repository knew it in advance.

## The root metadata

`nextflow.config` sets every root field the FAIRSCAPE profile understands — the dedicated
options (`author`, `description`, `keywords`, `license`, `organization`) plus `metadata`
for the long tail: `identifier`, `principalInvestigator`, `associatedPublication`,
`citation`, the ethics fields, the Croissant `rai:` keys, and five `additionalProperty`
entries. The statements are meant to be read as true — `humanSubjectResearch` says no,
because these are synthetic nf-core test alignments over a 40 kb window of chr22, and
`rai:dataBiases` says so plainly. With all of it set the run scores **28/28** on the
AI-Readiness rubric:

```bash
python3 -c "
import json
s = json.load(open('results/ai_ready_score.json'))
for cat, v in s.items():
    subs = [x for x in v.values() if isinstance(x, dict) and 'has_content' in x]
    print(f\"  {cat:<26} {sum(1 for x in subs if x['has_content'])}/{len(subs)}\")"
```

## Checking the crate

```bash
PYTHONPATH=/path/to/fairscape_models python3 ../../nf-fairscape-test/validate_crate.py \
    results/ro-crate-metadata.json      # is this a valid EVI RO-Crate
python3 ../nf-core/check-crate.py results/ro-crate-metadata.json   # is it acyclic, attributed, versioned
python3 ../nf-core/verify-against-run.py .                         # does it describe the run that happened
```

Expected:

```
  33 Computations, 90 Datasets, 13 Software, 1 Schemas, 4 Containers, 62 root outputs
  evidence graph: 132 nodes
  OK: acyclic, attributed, versioned, readable ids

  90 Datasets: 25 in the crate, 63 via localPath, 2 remote
  32 tasks in the trace, 32 task Computations + 1 run Computation
  edges: 61/61 staged inputs and 80/80 produced files are in the graph (0 + 0 unpublished intermediates not carried)
  OK: every task, command, container and edge is backed by the run
```

**80/80 produced files, and 0 unpublished intermediates not carried.** This is the stronger
of the two coverage numbers and this example is the one that reaches it: every file any
task wrote is a Dataset in the graph, published or not. The four flag-split BAMs, the merged
unmapped BAM and the intermediate `.fq.gz` halves never leave `work/` — they are 63 of the
90 Datasets, described with checksums and full provenance but carrying a `localPath` rather
than a `contentUrl`, because the crate does not contain them. Two more are remote: the
input BAM URLs.

**1 Schema.** The samplesheet. `samtools stats` output is sectioned, not tabular, and the
`.idxstats`/`.flagstat` files have no header row, so there is nothing for the column
inferrer to describe. They still get Datasets, checksums and edges.

## The provenance graph

```bash
xdg-open results/provenance-graph.html
```

Per sample, the conversion half of it:

```
test.paired_end.sorted.bam
  -> SAMTOOLS_INDEX -> .bai
  -> SAMTOOLS_VIEW  -> test.map_map.bam ------------------------------.
                    -> test.unmap_unmap.bam --.                       |
                    -> test.unmap_map.bam ----+-> SAMTOOLS_MERGE      |
                    -> test.map_unmap.bam ----'      -> test.merged_unmap.bam
                                                              |       |
                    SAMTOOLS_COLLATEFASTQ <-------------------'       |
                       -> test.unmapped_{1,2}.fq.gz --.               |
                    SAMTOOLS_COLLATEFASTQ <---------------------------'
                       -> test.mapped_{1,2}.fq.gz ----+-> CAT_FASTQ
                                                          -> test_{1,2}.merged.fastq.gz
                                                             -> FASTQC, FASTQUTILS_INFO
```
