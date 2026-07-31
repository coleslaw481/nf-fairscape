# fastquorum-like example

[nf-core/fastquorum 2.0.0](https://nf-co.re/fastquorum/2.0.0/docs/usage/), annotated.

`examples/nf-core/` runs released pipelines **unmodified**, so nothing there can carry an
`ext fairscape:` block — an nf-core module is not ours to edit. This is the other half of
that: the same tools, the same flags, the same test data, flattened into one `main.nf` that
*is* annotated, so you can read the annotation and the crate entity it produces side by side.

The pipeline: raw FASTQs carrying UMIs in-line, into a consensus BAM in which each read is
the agreement of both strands of one original source molecule. That is nf-core/fastquorum's
default `rd` (research and development) path with `duplex_seq = true`, and the process
bodies here are its own module scripts with the nf-core plumbing (`task.ext.args`, stub
blocks, conda directives, `topic:` version channels) taken out.

```bash
make install                    # from the repo root, once, after any plugin change
cd examples/fastquorum-like
nextflow run .

python3 -m json.tool results/ro-crate-metadata.json | less
xdg-open results/provenance-graph.html
xdg-open results/ro-crate-datasheet.html
```

Eleven tasks, about three minutes after the first run. Downloads: 85 MB of test data
(chromosome 17, plus 2.7 MB of reads), and two containers — the `fgbio_bwa_samtools` image
nf-core/fastquorum uses for its alignment module, and FastQC. Those two are 4.3 GB on disk
between them, most of it the JDK and conda layers under fgbio; it is the dominant cost of a
first run and there is no smaller fgbio image to reach for.

## What it runs

| Process | Tool | From |
| ------- | ---- | ---- |
| `BWA_INDEX`, `SAMTOOLS_FAIDX`, `SAMTOOLS_DICT` | bwa, samtools | `subworkflows/local/prepare_genome` |
| `FASTQC` | FastQC | `modules/nf-core/fastqc` |
| `FGBIO_FASTQTOBAM` | fgbio FastqToBam | UMIs out of the sequence, into the `RX` tag |
| `ALIGN_RAW_BAM` | samtools fastq \| bwa mem \| fgbio ZipperBams \| samtools sort | `modules/local/align_bam`, `sort_type = "template-coordinate"` |
| `FGBIO_GROUPREADSBYUMI` | fgbio GroupReadsByUmi | `--strategy Paired` — the duplex one: both strands of a molecule get one `MI` id, suffixed `/A` and `/B` |
| `FGBIO_COLLECTDUPLEXSEQMETRICS` | fgbio CollectDuplexSeqMetrics | five metrics tables |
| `FGBIO_CALLDDUPLEXCONSENSUSREADS` | fgbio CallDuplexConsensusReads | `--min-reads 1 1 0` |
| `ALIGN_CONSENSUS_BAM` | the alignment module again | `sort_type = "none"` |
| `FGBIO_FILTERCONSENSUSREADS` | fgbio FilterConsensusReads \| samtools sort | `--min-reads 3 1 1` — only true duplex observations survive |

Left out, and why: `CORRECTUMIS` (only runs when a sample declares a known UMI file),
`MERGE_BAM` (only when a library was sequenced over several lanes), the three non-duplex
and `ht` branches, and MultiQC — a large container for a report the crate does not need.

Two deviations from the original, both deliberate:

- **One container instead of three.** nf-core/fastquorum uses `fgbio`, `fgbio_samtools` and
  `fgbio_bwa_samtools`. Everything here that is not FastQC runs out of the third, so a
  first run pulls one image rather than three. It is also what makes the annotations earn
  their keep — an image carrying three tools cannot tell you which one a step ran.
- **Aliasing.** nf-core/fastquorum runs its alignment module twice (`ALIGN_BAM as
  ALIGN_RAW_BAM`, `as ALIGN_CONSENSUS_BAM`). A process can only be aliased across a module
  boundary, and this is one file, so the two are written out separately.

## The `ext fairscape:` blocks

Every process has one. Each names the tool that process actually runs — not the Nextflow
process, which is what the Software entity describes by default:

```groovy
process FGBIO_GROUPREADSBYUMI {
    ext fairscape: [
        softwareName       : 'fgbio GroupReadsByUmi',
        softwareAuthor     : 'Nils Homer, Tim Fennell et al. (Fulcrum Genomics)',
        softwareDescription: 'fgbio GroupReadsByUmi assigns reads to source molecules by UMI ...',
        softwareUrl        : 'https://fulcrumgenomics.github.io/fgbio/tools/latest/GroupReadsByUmi.html',
        softwareKeywords   : ['fgbio', 'umi', 'duplex-sequencing', 'molecule-grouping']
    ]
```

**`softwareVersion` is deliberately absent from all eleven.** It is the one `software*` key
whose right answer is not known when the file is written: pin it here and it drifts from
the container the moment either changes. Each process emits a `versions.yml` instead, and
`toolVersions` (on by default) reads the version out of it — so what lands on the Software
entity is what the container reported at run time:

```bash
python3 -c "
import json
g = json.load(open('results/ro-crate-metadata.json'))['@graph']
for s in g:
    if 'EVI#Software' in str(s.get('@type')) and s.get('isPartOf'):
        print(f\"  {s['name']:<44} {s.get('version')}\")"
```

```
  FastQC                                       0.12.1
  samtools faidx                               1.21
  bwa index                                    0.7.18-r1243-dirty
  fgbio FastqToBam                             2.5.21
  bwa mem + fgbio ZipperBams                   bwa 0.7.18-r1243-dirty, fgbio 2.5.21, samtools 1.21
  fgbio FilterConsensusReads + samtools sort   fgbio 2.5.21, samtools 1.21
```

The last two are the multi-tool processes: `versions.yml` reports all three tools, so the
`version` field names each rather than picking one.

## The root metadata

`nextflow.config` sets every root field the FAIRSCAPE profile understands — the dedicated
options (`author`, `description`, `keywords`, `license`, `organization`) plus `metadata`
for the long tail: `identifier`, `principalInvestigator`, `associatedPublication`,
`citation`, the ethics fields, the Croissant `rai:` keys, and six `additionalProperty`
entries recording the platform, library preparation and read structure.

The statements are about the real dataset and are meant to be read as true —
`rai:dataBiases` says one sample at one locus subsampled to 9,901 read pairs, because that
is what it is. With all of it set, the run scores **28/28** on the AI-Readiness rubric:

```bash
python3 -c "
import json
s = json.load(open('results/ai_ready_score.json'))
for cat, v in s.items():
    subs = [x for x in v.values() if isinstance(x, dict) and 'has_content' in x]
    print(f\"  {cat:<26} {sum(1 for x in subs if x['has_content'])}/{len(subs)}\")"
```

## Checking the crate

The same three checkers the nf-core examples use, in increasing order of strength:

```bash
PYTHONPATH=/path/to/fairscape_models python3 ../../nf-fairscape-test/validate_crate.py \
    results/ro-crate-metadata.json      # is this a valid EVI RO-Crate
python3 ../nf-core/check-crate.py results/ro-crate-metadata.json   # is it acyclic, attributed, versioned
python3 ../nf-core/verify-against-run.py .                         # does it describe the run that happened
```

Expected:

```
  12 Computations, 37 Datasets, 13 Software, 1 Schemas, 2 Containers, 24 root outputs
  evidence graph: 58 nodes
  OK: acyclic, attributed, versioned, readable ids

  37 Datasets: 18 in the crate, 16 via localPath, 3 remote
  11 tasks in the trace, 11 task Computations + 1 run Computation
  edges: 22/22 staged inputs and 31/37 produced files are in the graph
  OK: every task, command, container and edge is backed by the run
```

Three things in those numbers are worth knowing, because they are the plugin behaving
correctly rather than by accident:

**16 Datasets have a `localPath`, not a `contentUrl`.** nf-core/fastquorum only publishes
the reference indexes when `save_reference` is set, and it defaults off — so `bwa/`, the
`.fai` and the `.dict` stay in `work/`. They are described, with every provenance edge
intact, but the crate does not claim to contain them. Three more are remote: the two FASTQ
URLs and `chr17.fa`, recorded at the URL they were fetched from.

**1 Schema, not 6.** The samplesheet gets one. fgbio writes its five metrics tables
tab-delimited but names them `.txt`, and the inferrer is gated on the extension (csv/tsv),
so widening `schemaPatterns` would not reach them. They still get Datasets, checksums and
edges — just no column schema.

**Nothing in `results/pipeline_info/` is in the crate.** The collated
`fastquorum_like_software_versions.yml` and Nextflow's own trace, report, timeline and DAG
all land there, and none of them is a Dataset. They are written by Nextflow directly —
`collectFile(storeDir: ...)` and the report writers — and the plugin learns about files
from publish events, which those never fire. The versions file is still worth writing: it
is the human-readable evidence for the versions above, and `execution_trace_*.txt` is what
`verify-against-run.py` reads. Same gap the nf-core examples have.

## The provenance graph

```bash
xdg-open results/provenance-graph.html
```

The chain to read is the one the pipeline exists for, and every arrow in it is an edge the
crate asserts and `verify-against-run.py` confirmed against `work/`:

```
samplesheet.csv + SRR6109255_{1,2}.fastq.gz
  -> FGBIO_FASTQTOBAM        -> SRR6109255.unmapped.bam
  -> ALIGN_RAW_BAM           -> SRR6109255.mapped.bam          (+ chr17.fa, bwa/, .fai, .dict)
  -> FGBIO_GROUPREADSBYUMI   -> SRR6109255.grouped.bam         (+ family-sizes.txt)
  -> FGBIO_CALLDDUPLEXCONSENSUSREADS -> SRR6109255.cons.unmapped.bam
  -> ALIGN_CONSENSUS_BAM     -> SRR6109255.mapped.bam
  -> FGBIO_FILTERCONSENSUSREADS -> SRR6109255.cons.filtered.bam (+ .bai)
```
