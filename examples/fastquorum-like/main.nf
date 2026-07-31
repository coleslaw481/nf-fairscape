// nf-core/fastquorum 2.0.0, annotated.
//
//   https://nf-co.re/fastquorum/2.0.0/docs/usage/
//
// The default `rd` (research and development) path for duplex-sequencing data, flattened
// into one file: raw FASTQs carrying UMIs in-line, into a consensus BAM in which each read
// is the agreement of both strands of one original source molecule. The process bodies are
// nf-core/fastquorum's own module scripts with the nf-core plumbing (task.ext.args, stub
// blocks, conda directives, topic version channels) taken out, and one thing added --
// an `ext fairscape:` block per process saying which tool that process runs.
//
// What is left out, and why: CORRECTUMIS (only used when a sample declares a known UMI
// file), MERGE_BAM (only used when a library was sequenced over several lanes), the three
// non-duplex/`ht` branches, and MultiQC (a large container for a report the crate does not
// need). The tools, their flags and the parameter defaults are unchanged.
//
// Every process carries an `ext fairscape:` block naming the tool it runs. One key is
// deliberately absent from all of them: `softwareVersion`. Each process emits a
// `versions.yml` instead, so the version on each Software entity is the one the container
// reported at run time, rather than a number written here that can drift away from it.
//
// Inputs and the fgbio settings are declared in nextflow.config, so the report paths there
// can refer to `params.outdir`.
//
// One deviation worth knowing about: nf-core/fastquorum uses three containers (fgbio,
// fgbio+samtools, fgbio+bwa+samtools). Everything here that is not FastQC runs out of the
// third one, so a first run pulls one image instead of three. That is also why the
// `ext fairscape:` annotations earn their keep -- the image cannot tell you which of its
// three tools a given step actually ran.


// ---------------------------------------------------------------------------------------
// PREPARE_GENOME -- fastquorum's subworkflows/local/prepare_genome
// ---------------------------------------------------------------------------------------
// Nothing here is published: nf-core/fastquorum publishes the reference indexes only when
// `save_reference` is set, and it defaults to false. They stay in the work directory, so in
// the crate they are Datasets with a `localPath` and no `contentUrl` -- described, with all
// their provenance edges, but not carried inside the crate.

process BWA_INDEX {
    tag "${fasta}"
    container 'community.wave.seqera.io/library/fgbio_bwa_samtools:04bc9788bca8242c'
    // nf-core/bwa/index sizes memory from the FASTA (`memory { 7.B * fasta.size() }`, since
    // bwa needs ~5.37N for a database of size N). Resources are pinned in nextflow.config
    // here instead, and 4 GB covers chr17 with room to spare.

    ext fairscape: [
        softwareName       : 'bwa index',
        softwareAuthor     : 'Heng Li (Broad Institute)',
        softwareDescription: 'bwa index builds the FM-index -- Burrows-Wheeler transform, suffix array and packed sequence -- that bwa mem searches. It is a function of the reference alone, so it is built once and reused by both alignment steps.',
        softwareUrl        : 'https://bio-bwa.sourceforge.net/bwa.shtml',
        softwareKeywords   : ['bwa', 'burrows-wheeler', 'reference-genome', 'indexing']
    ]

    input:
    path fasta

    output:
    path 'bwa'         , emit: index
    path 'versions.yml', emit: versions

    script:
    """
    mkdir bwa
    bwa \\
        index \\
        -p bwa/${fasta.baseName} \\
        ${fasta}

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        bwa: \$(bwa 2>&1 | sed -n 's/^Version: //p')
    END_VERSIONS
    """
}

process SAMTOOLS_FAIDX {
    tag "${fasta}"
    container 'community.wave.seqera.io/library/fgbio_bwa_samtools:04bc9788bca8242c'

    ext fairscape: [
        softwareName       : 'samtools faidx',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'samtools faidx builds the .fai index that lets a tool seek to an arbitrary position in a FASTA file without reading it from the start. fgbio and bwa both expect one alongside the reference.',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-faidx.html',
        softwareKeywords   : ['samtools', 'htslib', 'reference-genome', 'indexing']
    ]

    input:
    path fasta

    output:
    path '*.fai'       , emit: fai
    path 'versions.yml', emit: versions

    script:
    """
    samtools \\
        faidx \\
        ${fasta}

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}

process SAMTOOLS_DICT {
    tag "${fasta}"
    container 'community.wave.seqera.io/library/fgbio_bwa_samtools:04bc9788bca8242c'

    ext fairscape: [
        softwareName       : 'samtools dict',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'samtools dict writes the sequence dictionary for a reference FASTA -- the @SQ header lines carrying each sequence name, length and checksum. Every fgbio tool that takes --ref reads it to check that a BAM and a reference describe the same sequences.',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-dict.html',
        softwareKeywords   : ['samtools', 'htslib', 'reference-genome', 'sequence-dictionary']
    ]

    input:
    path fasta

    output:
    path '*.dict'      , emit: dict
    path 'versions.yml', emit: versions

    script:
    """
    samtools \\
        dict \\
        ${fasta} \\
        > ${fasta.baseName}.dict

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}


// ---------------------------------------------------------------------------------------
// FASTQUORUM -- workflows/fastquorum.nf, the rd + duplex_seq branch
// ---------------------------------------------------------------------------------------

process FASTQC {
    tag "${meta.id}"
    container 'quay.io/biocontainers/fastqc:0.12.1--hdfd78af_0'
    publishDir "${params.outdir}/preprocessing/fastqc/${meta.id}", mode: 'copy', pattern: '*{html,zip}'

    ext fairscape: [
        softwareName       : 'FastQC',
        softwareAuthor     : 'Simon Andrews (Babraham Bioinformatics)',
        softwareDescription: 'FastQC reports per-base quality, adapter content, duplication and GC distribution for raw sequencing reads. Run here on the FASTQs as they arrive, before the UMI bases are moved out of the read sequence.',
        softwareUrl        : 'https://www.bioinformatics.babraham.ac.uk/projects/fastqc/',
        softwareKeywords   : ['fastqc', 'quality-control', 'sequencing-reads']
    ]

    input:
    tuple val(meta), path(reads)

    output:
    tuple val(meta), path('*.html'), emit: html
    tuple val(meta), path('*.zip') , emit: zip
    path 'versions.yml'            , emit: versions

    script:
    """
    fastqc \\
        --quiet \\
        --threads ${task.cpus} \\
        ${reads}

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        fastqc: \$(fastqc --version | sed '/FastQC v/!d; s/.*v//')
    END_VERSIONS
    """
}

// Read structure `10M1S+T` per read: 10 UMI bases, one skipped base, then the template.
// This step is what turns that convention into data -- the UMI moves out of the sequence
// and into the RX tag, so everything downstream can group reads by source molecule.
process FGBIO_FASTQTOBAM {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/fgbio_bwa_samtools:04bc9788bca8242c'
    publishDir "${params.outdir}/preprocessing/fastqtobam/${meta.id}", mode: 'copy', pattern: '*.unmapped.bam'

    ext fairscape: [
        softwareName       : 'fgbio FastqToBam',
        softwareAuthor     : 'Nils Homer, Tim Fennell et al. (Fulcrum Genomics)',
        softwareDescription: 'fgbio FastqToBam converts FASTQs into an unmapped BAM, using the read structure to split each read into its parts: UMI bases are written to the RX tag, skipped bases are dropped, and only the template bases remain as the read sequence.',
        softwareUrl        : 'https://fulcrumgenomics.github.io/fgbio/tools/latest/FastqToBam.html',
        softwareKeywords   : ['fgbio', 'umi', 'unmapped-bam', 'read-structure']
    ]

    input:
    tuple val(meta), path(fastqs)

    output:
    tuple val(meta), path('*.unmapped.bam'), emit: bam
    path 'versions.yml'                    , emit: versions

    script:
    """
    fgbio \\
        -Xmx${task.memory.giga}g \\
        --tmp-dir=. \\
        --async-io=true \\
        --compression=1 \\
        FastqToBam \\
        --input ${fastqs} \\
        --output "${meta.id}.unmapped.bam" \\
        --read-structures ${meta.read_structure} \\
        --sample ${meta.sample} \\
        --library ${meta.id}

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        fgbio: \$(fgbio --version 2>&1 | tr -d '[:cntrl:]' | sed -e 's/^.*Version: //;s/\\[.*\$//')
    END_VERSIONS
    """
}

// fastquorum's modules/local/align_bam, with `sort_type = "template-coordinate"`. The
// unmapped BAM goes out to FASTQ, through bwa, and straight back into fgbio ZipperBams,
// which re-attaches every tag bwa dropped -- RX above all -- by reading the unmapped BAM
// alongside the alignments. Template-coordinate order is what GroupReadsByUmi requires.
process ALIGN_RAW_BAM {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/fgbio_bwa_samtools:04bc9788bca8242c'
    publishDir "${params.outdir}/preprocessing/align_raw_bam/${meta.id}", mode: 'copy', pattern: '*.mapped.{bam,bam.bai}'

    ext fairscape: [
        softwareName       : 'bwa mem + fgbio ZipperBams',
        softwareAuthor     : 'Heng Li (bwa); Nils Homer, Tim Fennell et al. (fgbio); Heng Li et al. (samtools)',
        softwareDescription: 'samtools fastq streams the unmapped BAM out as interleaved reads, bwa mem aligns them to the reference, and fgbio ZipperBams merges the alignments back onto the original records so the UMI and other tags survive. samtools sort then puts the result in template-coordinate order, which is what fgbio GroupReadsByUmi requires of its input.',
        softwareUrl        : 'https://fulcrumgenomics.github.io/fgbio/tools/latest/ZipperBams.html',
        softwareKeywords   : ['bwa', 'fgbio', 'samtools', 'alignment', 'umi']
    ]

    input:
    tuple val(meta), path(unmapped_bam)
    path fasta
    path fasta_fai
    path dict
    path bwa_dir

    output:
    tuple val(meta), path('*.mapped.bam'), emit: bam
    path 'versions.yml'                  , emit: versions

    script:
    """
    # The real path to the BWA index prefix
    BWA_INDEX_PREFIX=`find -L ./ -name "*.amb" | sed 's/.amb//'`

    samtools fastq ${unmapped_bam} \\
        | bwa mem -t ${task.cpus} -p -K 150000000 -Y \$BWA_INDEX_PREFIX - \\
        | fgbio -Xmx${task.memory.giga}g \\
            --compression 0 \\
            --async-io=true \\
            ZipperBams \\
            --unmapped ${unmapped_bam} \\
            --ref ${fasta} \\
            --output /dev/stdout \\
            --tags-to-reverse Consensus \\
            --tags-to-revcomp Consensus \\
        | samtools sort --template-coordinate --threads ${task.cpus} -o ${meta.id}.mapped.bam -

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        bwa: \$(bwa 2>&1 | sed -n 's/^Version: //p')
        fgbio: \$(fgbio --version 2>&1 | tr -d '[:cntrl:]' | sed -e 's/^.*Version: //;s/\\[.*\$//')
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}

// The step the whole pipeline exists for: reads that share a UMI and a mapping position
// came from the same original molecule and get the same MI tag. `Paired` is the duplex
// strategy -- it also records which strand each read came from, as MI suffixes /A and /B.
process FGBIO_GROUPREADSBYUMI {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/fgbio_bwa_samtools:04bc9788bca8242c'
    publishDir "${params.outdir}/grouping/groupreadsbyumi/${meta.id}", mode: 'copy', pattern: '*.{grouped.bam,grouped-family-sizes.txt}'

    ext fairscape: [
        softwareName       : 'fgbio GroupReadsByUmi',
        softwareAuthor     : 'Nils Homer, Tim Fennell et al. (Fulcrum Genomics)',
        softwareDescription: 'fgbio GroupReadsByUmi assigns reads to source molecules by UMI and mapping position, writing the molecule id into the MI tag. The Paired strategy is the duplex one: the two strands of a molecule get the same numeric id with /A and /B suffixes, which is what makes duplex consensus calling possible downstream.',
        softwareUrl        : 'https://fulcrumgenomics.github.io/fgbio/tools/latest/GroupReadsByUmi.html',
        softwareKeywords   : ['fgbio', 'umi', 'duplex-sequencing', 'molecule-grouping']
    ]

    input:
    tuple val(meta), path(mapped_bam)
    val  strategy
    val  edits

    output:
    tuple val(meta), path('*.grouped.bam')             , emit: bam
    tuple val(meta), path('*.grouped-family-sizes.txt'), emit: histogram
    tuple val(meta), path('*.grouped-read-metrics.txt'), emit: read_metrics
    path 'versions.yml'                                , emit: versions

    script:
    """
    fgbio \\
        -Xmx${task.memory.giga}g \\
        --tmp-dir=. \\
        --async-io=true \\
        --compression=1 \\
        GroupReadsByUmi \\
        --strategy ${strategy} \\
        --edits ${edits} \\
        --input ${mapped_bam} \\
        --output ${meta.id}.grouped.bam \\
        --family-size-histogram ${meta.id}.grouped-family-sizes.txt \\
        --grouping-metrics ${meta.id}.grouped-read-metrics.txt

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        fgbio: \$(fgbio --version 2>&1 | tr -d '[:cntrl:]' | sed -e 's/^.*Version: //;s/\\[.*\$//')
    END_VERSIONS
    """
}

// Five tab-delimited metrics tables. They are named .txt rather than .tsv, and the
// plugin's schema inferrer is gated on the extension, so they end up as Datasets with
// checksums and provenance but no inferred column schema.
process FGBIO_COLLECTDUPLEXSEQMETRICS {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/fgbio_bwa_samtools:04bc9788bca8242c'
    publishDir "${params.outdir}/metrics/duplex_seq/${meta.id}", mode: 'copy', pattern: '*duplex_seq_metrics*.txt'

    ext fairscape: [
        softwareName       : 'fgbio CollectDuplexSeqMetrics',
        softwareAuthor     : 'Nils Homer, Tim Fennell et al. (Fulcrum Genomics)',
        softwareDescription: 'fgbio CollectDuplexSeqMetrics summarises a UMI-grouped BAM as a set of tab-delimited tables: family sizes, duplex family sizes, UMI and duplex-UMI counts, and a yield curve estimating how much duplex coverage further sequencing would buy.',
        softwareUrl        : 'https://fulcrumgenomics.github.io/fgbio/tools/latest/CollectDuplexSeqMetrics.html',
        softwareKeywords   : ['fgbio', 'duplex-sequencing', 'metrics', 'quality-control']
    ]

    input:
    tuple val(meta), path(grouped_bam)

    output:
    tuple val(meta), path('*duplex_seq_metrics*.txt'), emit: metrics
    path 'versions.yml'                              , emit: versions

    script:
    """
    fgbio \\
        -Xmx${task.memory.giga}g \\
        --tmp-dir=. \\
        --async-io=true \\
        --compression=1 \\
        CollectDuplexSeqMetrics \\
        --input ${grouped_bam} \\
        --output ${meta.id}.duplex_seq_metrics \\
        --duplex-umi-counts=true

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        fgbio: \$(fgbio --version 2>&1 | tr -d '[:cntrl:]' | sed -e 's/^.*Version: //;s/\\[.*\$//')
    END_VERSIONS
    """
}

// `--min-reads 1 1 0`: one read minimum for the duplex consensus, one from the first
// strand, none required from the second -- so single-stranded families still produce a
// consensus and are judged on their merits by the filter, rather than dropped here.
process FGBIO_CALLDDUPLEXCONSENSUSREADS {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/fgbio_bwa_samtools:04bc9788bca8242c'
    publishDir "${params.outdir}/consensus_calling/called/${meta.id}", mode: 'copy', pattern: '*.cons.unmapped.bam'

    ext fairscape: [
        softwareName       : 'fgbio CallDuplexConsensusReads',
        softwareAuthor     : 'Nils Homer, Tim Fennell et al. (Fulcrum Genomics)',
        softwareDescription: 'fgbio CallDuplexConsensusReads collapses each duplex family into a single consensus read: a consensus per strand, then a consensus of the two strands. An error introduced after the molecule was tagged appears on one strand only and is corrected away, while a true variant is present on both. The output is unmapped, because a consensus sequence is a new sequence.',
        softwareUrl        : 'https://fulcrumgenomics.github.io/fgbio/tools/latest/CallDuplexConsensusReads.html',
        softwareKeywords   : ['fgbio', 'duplex-sequencing', 'consensus-calling', 'error-correction']
    ]

    input:
    tuple val(meta), path(grouped_bam)
    val  min_reads
    val  min_baseq

    output:
    tuple val(meta), path('*.cons.unmapped.bam'), emit: bam
    path 'versions.yml'                         , emit: versions

    script:
    """
    fgbio \\
        -Xmx${task.memory.giga}g \\
        --tmp-dir=. \\
        --async-io=true \\
        --compression=1 \\
        CallDuplexConsensusReads \\
        --input ${grouped_bam} \\
        --output ${meta.id}.cons.unmapped.bam \\
        --min-reads ${min_reads} \\
        --min-input-base-quality ${min_baseq} \\
        --threads ${task.cpus}

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        fgbio: \$(fgbio --version 2>&1 | tr -d '[:cntrl:]' | sed -e 's/^.*Version: //;s/\\[.*\$//')
    END_VERSIONS
    """
}

// The same module again with `sort_type = "none"`, on the consensus reads. nf-core/
// fastquorum gets this by aliasing one module twice (`ALIGN_BAM as ALIGN_CONSENSUS_BAM`);
// a single-file pipeline cannot alias a process, so it is written out.
process ALIGN_CONSENSUS_BAM {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/fgbio_bwa_samtools:04bc9788bca8242c'
    publishDir "${params.outdir}/filtering/align_consensus_bam/${meta.id}", mode: 'copy', pattern: '*.mapped.{bam,bam.bai}'

    ext fairscape: [
        softwareName       : 'bwa mem + fgbio ZipperBams',
        softwareAuthor     : 'Heng Li (bwa); Nils Homer, Tim Fennell et al. (fgbio); Heng Li et al. (samtools)',
        softwareDescription: 'The alignment step again, this time over the consensus reads: samtools fastq, bwa mem, and fgbio ZipperBams to restore the consensus tags (cD, cE, cM and their per-strand equivalents) that bwa does not carry through. Left unsorted, in the order ZipperBams emits, which is what fgbio FilterConsensusReads expects next.',
        softwareUrl        : 'https://fulcrumgenomics.github.io/fgbio/tools/latest/ZipperBams.html',
        softwareKeywords   : ['bwa', 'fgbio', 'samtools', 'alignment', 'consensus-reads']
    ]

    input:
    tuple val(meta), path(unmapped_bam)
    path fasta
    path fasta_fai
    path dict
    path bwa_dir

    output:
    tuple val(meta), path('*.mapped.bam'), emit: bam
    path 'versions.yml'                  , emit: versions

    script:
    """
    # The real path to the BWA index prefix
    BWA_INDEX_PREFIX=`find -L ./ -name "*.amb" | sed 's/.amb//'`

    samtools fastq ${unmapped_bam} \\
        | bwa mem -t ${task.cpus} -p -K 150000000 -Y \$BWA_INDEX_PREFIX - \\
        | fgbio -Xmx${task.memory.giga}g \\
            --compression 1 \\
            --async-io=true \\
            ZipperBams \\
            --unmapped ${unmapped_bam} \\
            --ref ${fasta} \\
            --output ${meta.id}.mapped.bam \\
            --tags-to-reverse Consensus \\
            --tags-to-revcomp Consensus

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        bwa: \$(bwa 2>&1 | sed -n 's/^Version: //p')
        fgbio: \$(fgbio --version 2>&1 | tr -d '[:cntrl:]' | sed -e 's/^.*Version: //;s/\\[.*\$//')
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}

// `--min-reads 3 1 1`: keep a consensus read only if it came from at least three raw reads
// in total, with at least one from each strand -- that is, only true duplex observations.
process FGBIO_FILTERCONSENSUSREADS {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/fgbio_bwa_samtools:04bc9788bca8242c'
    publishDir "${params.outdir}/consensus_filtering/filtered/${meta.id}", mode: 'copy', pattern: '*.cons.filtered.{bam,bam.bai}'

    ext fairscape: [
        softwareName       : 'fgbio FilterConsensusReads + samtools sort',
        softwareAuthor     : 'Nils Homer, Tim Fennell et al. (fgbio); Heng Li et al. (samtools)',
        softwareDescription: 'fgbio FilterConsensusReads discards consensus reads built from too little evidence and masks individual bases that remain uncertain, using the per-read and per-base depth and error-rate tags written during consensus calling. It reads the reference so that bases disagreeing with it can be weighed against the raw-read error rate. samtools sort then writes the coordinate-sorted BAM and its index together.',
        softwareUrl        : 'https://fulcrumgenomics.github.io/fgbio/tools/latest/FilterConsensusReads.html',
        softwareKeywords   : ['fgbio', 'samtools', 'duplex-sequencing', 'consensus-filtering']
    ]

    input:
    tuple val(meta), path(consensus_bam)
    path fasta
    path fasta_fai
    path dict
    val  min_reads
    val  min_baseq
    val  max_base_error_rate

    output:
    tuple val(meta), path('*.cons.filtered.bam')    , emit: bam
    tuple val(meta), path('*.cons.filtered.bam.bai'), emit: bai
    path 'versions.yml'                             , emit: versions

    script:
    """
    fgbio \\
        -Xmx${task.memory.giga}g \\
        --tmp-dir=. \\
        --compression=0 \\
        FilterConsensusReads \\
        --input ${consensus_bam} \\
        --output /dev/stdout \\
        --ref ${fasta} \\
        --min-reads ${min_reads} \\
        --min-base-quality ${min_baseq} \\
        --max-base-error-rate ${max_base_error_rate} \\
        | samtools sort \\
        --threads ${task.cpus} \\
        -o ${meta.id}.cons.filtered.bam##idx##${meta.id}.cons.filtered.bam.bai \\
        --write-index

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        fgbio: \$(fgbio --version 2>&1 | tr -d '[:cntrl:]' | sed -e 's/^.*Version: //;s/\\[.*\$//')
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}


workflow {

    // sample,fastq_1,fastq_2,read_structure -- the columns nf-core/fastquorum requires.
    // The samplesheet is a param, so `paramInputs` registers it as a run input and infers
    // a schema for it, even though no task ever stages it.
    ch_samplesheet = channel
        .fromPath(params.input, checkIfExists: true)
        .splitCsv(header: true)
        .map { row ->
            def meta = [id: row.sample, sample: row.sample, read_structure: row.read_structure]
            [meta, [file(row.fastq_1, checkIfExists: true), file(row.fastq_2, checkIfExists: true)]]
        }

    ch_fasta = channel.value(file(params.fasta, checkIfExists: true))

    // PREPARE_GENOME
    BWA_INDEX(ch_fasta)
    SAMTOOLS_FAIDX(ch_fasta)
    SAMTOOLS_DICT(ch_fasta)

    FASTQC(ch_samplesheet)
    FGBIO_FASTQTOBAM(ch_samplesheet)

    ALIGN_RAW_BAM(
        FGBIO_FASTQTOBAM.out.bam,
        ch_fasta,
        SAMTOOLS_FAIDX.out.fai,
        SAMTOOLS_DICT.out.dict,
        BWA_INDEX.out.index,
    )

    FGBIO_GROUPREADSBYUMI(
        ALIGN_RAW_BAM.out.bam,
        params.groupreadsbyumi_strategy,
        params.groupreadsbyumi_edits,
    )

    FGBIO_COLLECTDUPLEXSEQMETRICS(FGBIO_GROUPREADSBYUMI.out.bam)

    FGBIO_CALLDDUPLEXCONSENSUSREADS(
        FGBIO_GROUPREADSBYUMI.out.bam,
        params.call_min_reads,
        params.call_min_baseq,
    )

    ALIGN_CONSENSUS_BAM(
        FGBIO_CALLDDUPLEXCONSENSUSREADS.out.bam,
        ch_fasta,
        SAMTOOLS_FAIDX.out.fai,
        SAMTOOLS_DICT.out.dict,
        BWA_INDEX.out.index,
    )

    FGBIO_FILTERCONSENSUSREADS(
        ALIGN_CONSENSUS_BAM.out.bam,
        ch_fasta,
        SAMTOOLS_FAIDX.out.fai,
        SAMTOOLS_DICT.out.dict,
        params.filter_min_reads,
        params.filter_min_baseq,
        params.filter_max_base_error_rate,
    )

    // One collated versions file, as nf-core/fastquorum writes into pipeline_info/: the
    // human-readable evidence for the version each Software entity claims. Note it does
    // NOT end up in the crate -- Nextflow writes a `collectFile(storeDir: ...)` output
    // itself, without a publish event, and publish events are how the plugin learns that
    // a file exists. The same is true of the trace, report, timeline and DAG beside it.
    channel.empty()
        .mix(
            BWA_INDEX.out.versions,
            SAMTOOLS_FAIDX.out.versions,
            SAMTOOLS_DICT.out.versions,
            FASTQC.out.versions,
            FGBIO_FASTQTOBAM.out.versions,
            ALIGN_RAW_BAM.out.versions,
            FGBIO_GROUPREADSBYUMI.out.versions,
            FGBIO_COLLECTDUPLEXSEQMETRICS.out.versions,
            FGBIO_CALLDDUPLEXCONSENSUSREADS.out.versions,
            ALIGN_CONSENSUS_BAM.out.versions,
            FGBIO_FILTERCONSENSUSREADS.out.versions,
        )
        .collectFile(
            name: 'fastquorum_like_software_versions.yml',
            storeDir: "${params.outdir}/pipeline_info",
            sort: true,
        )
}
