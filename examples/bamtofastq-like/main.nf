// nf-core/bamtofastq 2.2.1, annotated.
//
//   https://nf-co.re/bamtofastq/2.2.1/docs/usage/
//
// Aligned reads back out to FASTQ, with QC either side of the conversion. The interesting
// part is that it is not one `samtools fastq` call: a coordinate-sorted BAM has to be
// split by mapping state first (both mates mapped / neither / one of each), because the
// unmapped records have to be collated separately before the two halves are put back
// together. That is the fan-out and fan-in you see in the provenance graph.
//
// The process bodies are nf-core/bamtofastq's own module scripts with the nf-core plumbing
// (task.ext.args, stub blocks, conda directives, topic version channels) taken out, and one
// thing added -- an `ext fairscape:` block per process saying which tool it runs.
//
// What is left out, and why: CRAM support and everything that goes with it (the reference
// FASTA, SAMTOOLS_FAIDX), the `--chr` region-extraction branch, the single-end branch, and
// MultiQC. The test data is paired-end BAM, so none of those would run.
//
// Where nf-core/bamtofastq aliases one module several times -- SAMTOOLS_VIEW four ways,
// SAMTOOLS_COLLATEFASTQ twice, FASTQC before and after -- this runs one process over a
// channel carrying the variants instead. A process can only be aliased across a module
// boundary and this is one file. The task Computations in the crate come out the same;
// what differs is that the four `samtools view` tasks share one Software entity.
//
// Every process carries an `ext fairscape:` block naming the tool it runs. One key is
// deliberately absent from all of them: `softwareVersion`. Each process emits a
// `versions.yml` instead, so the version on each Software entity is the one the container
// reported at run time, rather than a number written here that can drift away from it.
//
// Inputs are declared in nextflow.config, so the report paths there can refer to
// `params.outdir`.


// ---------------------------------------------------------------------------------------
// PREPARE_INDICES -- bamtofastq's subworkflows/local/prepare_indices
// ---------------------------------------------------------------------------------------

process SAMTOOLS_INDEX {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/htslib_samtools:1.23.1--5b6bb4ede7e612e5'

    ext fairscape: [
        softwareName       : 'samtools index',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'samtools index writes the BAI index over a coordinate-sorted BAM, so a tool can seek to a locus without scanning the whole file. The samplesheet may name an index instead; this runs only for the entries that do not.',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-index.html',
        softwareKeywords   : ['samtools', 'htslib', 'bam', 'indexing']
    ]

    input:
    tuple val(meta), path(input)

    output:
    tuple val(meta), path('*.bai'), emit: index
    path 'versions.yml'           , emit: versions

    script:
    """
    samtools \\
        index \\
        -@ ${task.cpus} \\
        ${input}

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}


// ---------------------------------------------------------------------------------------
// PRE_CONVERSION_QC -- bamtofastq's subworkflows/local/pre_conversion_qc
// ---------------------------------------------------------------------------------------
// Three independent reads of the same BAM, plus FastQC. They fan out from one Dataset and
// nothing joins them back up, which is what that part of the graph should look like.

process SAMTOOLS_IDXSTATS {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/htslib_samtools:1.23.1--5b6bb4ede7e612e5'
    publishDir "${params.outdir}/samtools", mode: 'copy', pattern: '*.idxstats'

    ext fairscape: [
        softwareName       : 'samtools idxstats',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'samtools idxstats reads the BAI index alone and reports, per reference sequence, its length and how many mapped and unmapped reads it holds. It never touches the alignment records, so it is effectively free.',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-idxstats.html',
        softwareKeywords   : ['samtools', 'htslib', 'alignment-statistics', 'quality-control']
    ]

    input:
    tuple val(meta), path(bam), path(bai)

    output:
    tuple val(meta), path('*.idxstats'), emit: idxstats
    path 'versions.yml'                , emit: versions

    script:
    """
    # Note: --threads value represents *additional* CPUs to allocate (total CPUs = 1 + --threads).
    samtools \\
        idxstats \\
        --threads ${task.cpus - 1} \\
        ${bam} \\
        > ${meta.id}.idxstats

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}

process SAMTOOLS_FLAGSTAT {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/htslib_samtools:1.23.1--5b6bb4ede7e612e5'
    publishDir "${params.outdir}/samtools", mode: 'copy', pattern: '*.flagstat'

    ext fairscape: [
        softwareName       : 'samtools flagstat',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'samtools flagstat counts the records in an alignment file by SAM flag: total, mapped, paired, properly paired, singletons, and mates mapped to a different reference. It is the standard one-glance summary of whether an alignment looks sane.',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-flagstat.html',
        softwareKeywords   : ['samtools', 'htslib', 'alignment-statistics', 'quality-control']
    ]

    input:
    tuple val(meta), path(bam), path(bai)

    output:
    tuple val(meta), path('*.flagstat'), emit: flagstat
    path 'versions.yml'                , emit: versions

    script:
    """
    samtools \\
        flagstat \\
        --threads ${task.cpus} \\
        ${bam} \\
        > ${meta.id}.flagstat

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}

process SAMTOOLS_STATS {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/htslib_samtools:1.23.1--5b6bb4ede7e612e5'
    publishDir "${params.outdir}/samtools", mode: 'copy', pattern: '*.stats'

    ext fairscape: [
        softwareName       : 'samtools stats',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'samtools stats walks every record and produces the full set of alignment summary numbers -- insert size distribution, per-cycle quality, GC content, coverage, mismatch rates -- as a single tab-delimited file of labelled sections.',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-stats.html',
        softwareKeywords   : ['samtools', 'htslib', 'alignment-statistics', 'quality-control']
    ]

    input:
    tuple val(meta), path(bam), path(bai)

    output:
    tuple val(meta), path('*.stats'), emit: stats
    path 'versions.yml'             , emit: versions

    script:
    """
    samtools \\
        stats \\
        --threads ${task.cpus} \\
        ${bam} \\
        > ${meta.id}.stats

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}

// nf-core/bamtofastq runs FASTQC twice, aliased (`FASTQC_PRE_CONVERSION`,
// `FASTQC_POST_CONVERSION`). Here it is one process over a channel that carries both, with
// `meta.stage` saying which -- so the crate gets one FastQC Software entity and one task
// Computation per (sample, stage).
process FASTQC {
    tag "${meta.id} ${meta.stage}"
    container 'quay.io/biocontainers/fastqc:0.12.1--hdfd78af_0'
    publishDir "${params.outdir}/fastqc/${meta.stage}", mode: 'copy', pattern: '*{html,zip}'

    ext fairscape: [
        softwareName       : 'FastQC',
        softwareAuthor     : 'Simon Andrews (Babraham Bioinformatics)',
        softwareDescription: 'FastQC reports per-base quality, adapter content, duplication and GC distribution. It reads BAM as readily as FASTQ, which is what lets the same tool report on the alignment going in and on the reads coming out, so the two can be compared.',
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


// ---------------------------------------------------------------------------------------
// Conversion -- bamtofastq's modules/local/checkpairedend and
// subworkflows/local/alignment_to_fastq
// ---------------------------------------------------------------------------------------

// Decide single- or paired-end by looking at the first 1000 records' flags, rather than
// trusting the samplesheet. Only the paired branch is implemented below.
process CHECKPAIREDEND {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/htslib_samtools:1.23.1--5b6bb4ede7e612e5'

    ext fairscape: [
        softwareName       : 'samtools view (paired-end check)',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'A shell check built on samtools view: it counts how many of the first 1000 records carry the paired flag (0x1), and writes a marker file saying whether the library is paired-end or single-end. The conversion path taken downstream depends on the answer.',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-view.html',
        softwareKeywords   : ['samtools', 'htslib', 'sam-flags', 'paired-end']
    ]

    input:
    tuple val(meta), path(input), path(index)

    output:
    tuple val(meta), path('*single.txt'), emit: single_end, optional: true
    tuple val(meta), path('*paired.txt'), emit: paired_end, optional: true
    path 'versions.yml'                 , emit: versions

    script:
    """
    if [ "\$( samtools view ${input} -@${task.cpus} | head -n1000 | wc -l)" -lt "1000" ]; then
        LINES_TO_CHK=\$( samtools view ${input} -@${task.cpus} | wc -l)
    else
        LINES_TO_CHK=1000
    fi

    if [ \$({ samtools view -H ${input} -@${task.cpus} ; samtools view ${input} -@${task.cpus} | head -n\$LINES_TO_CHK; } | samtools view -c -f 1 -@${task.cpus} | awk -v lines=\$LINES_TO_CHK '{print \$1/lines}') = "1" ]; then
        echo 1 > ${meta.id}.paired.txt
    else
        echo 1 > ${meta.id}.single.txt
    fi

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}

// The fan-out. nf-core/bamtofastq aliases SAMTOOLS_VIEW four times with four flag sets;
// here one process runs over a channel carrying all four. The flag sets are theirs
// verbatim, from conf/modules.config:
//
//   map_map      -b -f1  -F12   both mates mapped
//   unmap_unmap  -b -f12 -F256  neither mate mapped
//   unmap_map    -b -f4  -F264  this read unmapped, mate mapped
//   map_unmap    -b -f8  -F260  this read mapped, mate unmapped
process SAMTOOLS_VIEW {
    tag "${meta.id} ${selection}"
    container 'community.wave.seqera.io/library/htslib_samtools:1.23.1--5b6bb4ede7e612e5'

    ext fairscape: [
        softwareName       : 'samtools view',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'samtools view filters an alignment file by SAM flag. It is used here to split the BAM into the four mapping states of a read pair, because the records where one or both mates failed to map have to be collated separately from the ones where both mapped.',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-view.html',
        softwareKeywords   : ['samtools', 'htslib', 'sam-flags', 'filtering']
    ]

    input:
    tuple val(meta), path(input), path(index), val(selection), val(flags)

    output:
    tuple val(meta), val(selection), path("*.${selection}.bam"), emit: bam
    path 'versions.yml'                                        , emit: versions

    script:
    """
    # Note: --threads value represents *additional* CPUs to allocate (total CPUs = 1 + --threads).
    samtools \\
        view \\
        --threads ${task.cpus - 1} \\
        ${flags} \\
        -o ${meta.id}.${selection}.bam \\
        ${input}

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}

// The fan-in: the three partially-or-wholly unmapped selections back into one BAM.
process SAMTOOLS_MERGE {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/htslib_samtools:1.23.1--5b6bb4ede7e612e5'

    ext fairscape: [
        softwareName       : 'samtools merge',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'samtools merge combines several sorted alignment files into one, reconciling their headers. Here it puts the three unmapped selections -- neither mate mapped, and each of the two half-mapped cases -- back into a single BAM to be collated as a unit.',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-merge.html',
        softwareKeywords   : ['samtools', 'htslib', 'bam', 'merging']
    ]

    input:
    tuple val(meta), path(input_files, stageAs: '?/*')

    output:
    tuple val(meta), path('*.merged_unmap.bam'), emit: bam
    path 'versions.yml'                        , emit: versions

    script:
    """
    # Note: --threads value represents *additional* CPUs to allocate (total CPUs = 1 + --threads).
    samtools \\
        merge \\
        --threads ${task.cpus - 1} \\
        ${meta.id}.merged_unmap.bam \\
        ${input_files}

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}

// nf-core/bamtofastq aliases this twice (COLLATE_FASTQ_MAP, COLLATE_FASTQ_UNMAP); here one
// process runs over a channel carrying both, with `kind` naming the half.
//
// `samtools collate` is the step that matters: a coordinate-sorted BAM has the two reads of
// a pair far apart in the file, and `samtools fastq` needs them adjacent. `-N` (args2 in
// their modules.config) keeps the /1 and /2 suffixes on the read names.
process SAMTOOLS_COLLATEFASTQ {
    tag "${meta.id} ${kind}"
    container 'community.wave.seqera.io/library/htslib_samtools:1.23.1--5b6bb4ede7e612e5'

    ext fairscape: [
        softwareName       : 'samtools collate + samtools fastq',
        softwareAuthor     : 'Heng Li, Bob Handsaker, James Bonfield et al. (Genome Research Limited)',
        softwareDescription: 'samtools collate shuffles a coordinate-sorted alignment file so the two reads of each pair sit next to each other, and samtools fastq then writes them out as paired FASTQ. Reads without a mate go to the singleton file and anything unpaired to the other file, so nothing is silently dropped.',
        softwareUrl        : 'https://www.htslib.org/doc/samtools-fastq.html',
        softwareKeywords   : ['samtools', 'htslib', 'bam-to-fastq', 'read-collation']
    ]

    input:
    tuple val(meta), val(kind), path(input)

    output:
    tuple val(meta), val(kind), path("*.${kind}_{1,2}.fq.gz"), emit: fastq
    tuple val(meta), path("*.${kind}_other.fq.gz")           , emit: fastq_other
    tuple val(meta), path("*.${kind}_singleton.fq.gz")       , emit: fastq_singleton
    path 'versions.yml'                                      , emit: versions

    script:
    """
    samtools collate \\
        --threads ${task.cpus} \\
        -O \\
        ${input} \\
        . |

    samtools fastq \\
        -N \\
        --threads ${task.cpus} \\
        -0 ${meta.id}.${kind}_other.fq.gz \\
        -1 ${meta.id}.${kind}_1.fq.gz \\
        -2 ${meta.id}.${kind}_2.fq.gz \\
        -s ${meta.id}.${kind}_singleton.fq.gz

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        samtools: \$(samtools version | sed '1!d;s/.* //')
    END_VERSIONS
    """
}

// Mapped R1 with unmapped R1, mapped R2 with unmapped R2. This is the pipeline's output.
process CAT_FASTQ {
    tag "${meta.id}"
    container 'community.wave.seqera.io/library/coreutils_grep_gzip_lbzip2_pruned:838ba80435a629f8'
    publishDir "${params.outdir}/reads", mode: 'copy', pattern: '*.merged.fastq.gz'

    ext fairscape: [
        softwareName       : 'cat',
        softwareAuthor     : 'Torbjorn Granlund, Richard M. Stallman (GNU coreutils)',
        softwareDescription: 'GNU coreutils cat concatenates files. Two gzip members appended to each other are themselves a valid gzip stream, so the mapped and unmapped halves of each read can simply be catted together without decompressing either.',
        softwareUrl        : 'https://www.gnu.org/software/coreutils/cat',
        softwareKeywords   : ['coreutils', 'concatenation', 'fastq']
    ]

    input:
    tuple val(meta), path(reads, stageAs: 'input*/*')

    output:
    tuple val(meta), path('*.merged.fastq.gz'), emit: reads
    path 'versions.yml'                       , emit: versions

    script:
    def readList = reads instanceof List ? reads.collect { item -> item.toString() } : [reads.toString()]
    def read1 = []
    def read2 = []
    readList.eachWithIndex { v, ix -> (ix & 1 ? read2 : read1) << v }
    """
    cat ${read1.join(' ')} > ${meta.id}_1.merged.fastq.gz
    cat ${read2.join(' ')} > ${meta.id}_2.merged.fastq.gz

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        cat: \$(cat --version 2>&1 | head -n 1 | sed 's/^.*coreutils) //; s/ .*\$//')
    END_VERSIONS
    """
}

// The last word on whether the conversion produced something well-formed: a FASTQ that
// FastQC will happily plot can still have mismatched pairs or a truncated final record.
process FASTQUTILS_INFO {
    tag "${meta.id}"
    container 'quay.io/biocontainers/fastq_utils:0.25.3--ha9dfd29_0'
    publishDir "${params.outdir}/fastq_utils", mode: 'copy', pattern: '*.txt'

    ext fairscape: [
        softwareName       : 'fastq_utils fastq_info',
        softwareAuthor     : 'Nuno A. Fonseca (fastq_utils)',
        softwareDescription: 'fastq_utils fastq_info validates a FASTQ file or a pair of them: record structure, quality-string length against sequence length, duplicate read names, and for a pair, that the two files are in the same order and the same length. It exits non-zero on a malformed file, so the conversion cannot pass quietly.',
        softwareUrl        : 'https://github.com/nunofonseca/fastq_utils',
        softwareKeywords   : ['fastq_utils', 'fastq', 'validation', 'quality-control']
    ]

    input:
    tuple val(meta), path(reads)

    output:
    tuple val(meta), path('*.txt'), emit: info
    path 'versions.yml'           , emit: versions

    script:
    """
    fastq_info ${reads}

    echo "fastq_utils fastq_info ran and found no issues with ${reads}" > ${meta.id}.txt

    cat <<-END_VERSIONS > versions.yml
    "${task.process}":
        fastqutils: \$(fastq_info -h 2>&1 | head -n 1 | sed 's/^fastq_utils //')
    END_VERSIONS
    """
}


workflow {

    // sample_id,mapped,index,file_type -- the columns nf-core/bamtofastq requires. `index`
    // is optional there and left empty here, so SAMTOOLS_INDEX runs. The samplesheet is a
    // param, so `paramInputs` registers it as a run input and infers a schema for it, even
    // though no task ever stages it.
    ch_samplesheet = channel
        .fromPath(params.input, checkIfExists: true)
        .splitCsv(header: true)
        .map { row ->
            assert row.file_type == 'bam' : "this example handles BAM only, not '${row.file_type}'"
            [[id: row.sample_id, filetype: row.file_type], file(row.mapped, checkIfExists: true)]
        }

    // PREPARE_INDICES
    SAMTOOLS_INDEX(ch_samplesheet)
    ch_input = ch_samplesheet.join(SAMTOOLS_INDEX.out.index)

    // PRE_CONVERSION_QC
    SAMTOOLS_IDXSTATS(ch_input)
    SAMTOOLS_FLAGSTAT(ch_input)
    SAMTOOLS_STATS(ch_input)

    CHECKPAIREDEND(ch_input)

    // Only the paired-end branch is implemented; join on the paired marker so an unpaired
    // BAM stops here rather than being converted as if it were paired.
    ch_paired = ch_input
        .join(CHECKPAIREDEND.out.paired_end)
        .map { meta, bam, bai, _marker -> [meta, bam, bai] }

    // ALIGNMENT_TO_FASTQ: split by mapping state, four ways
    ch_selections = channel.of(
        ['map_map'    , '-b -f1 -F12' ],
        ['unmap_unmap', '-b -f12 -F256'],
        ['unmap_map'  , '-b -f4 -F264'],
        ['map_unmap'  , '-b -f8 -F260'],
    )

    SAMTOOLS_VIEW(ch_paired.combine(ch_selections))

    ch_split = SAMTOOLS_VIEW.out.bam
        .branch { _meta, selection, _bam ->
            mapped:   selection == 'map_map'
            unmapped: true
        }

    // the three unmapped selections back into one BAM per sample
    SAMTOOLS_MERGE(
        ch_split.unmapped
            .map { meta, _selection, bam -> [meta, bam] }
            .groupTuple()
    )

    // collate + convert, both halves through the same process
    SAMTOOLS_COLLATEFASTQ(
        ch_split.mapped.map { meta, _selection, bam -> [meta, 'mapped', bam] }
            .mix(SAMTOOLS_MERGE.out.bam.map { meta, bam -> [meta, 'unmapped', bam] })
    )

    ch_collated = SAMTOOLS_COLLATEFASTQ.out.fastq
        .branch { _meta, kind, _reads ->
            mapped:   kind == 'mapped'
            unmapped: true
        }

    // mapped R1, mapped R2, unmapped R1, unmapped R2 -- CAT_FASTQ pairs them by position
    CAT_FASTQ(
        ch_collated.mapped.map { meta, _kind, reads -> [meta, reads] }
            .join(ch_collated.unmapped.map { meta, _kind, reads -> [meta, reads] })
            .map { meta, mapped, unmapped ->
                [meta, [mapped[0], mapped[1], unmapped[0], unmapped[1]]]
            }
    )

    // QC either side of the conversion, through the one FASTQC process
    FASTQC(
        ch_samplesheet.map { meta, bam -> [meta + [stage: 'pre_conversion'], bam] }
            .mix(CAT_FASTQ.out.reads.map { meta, reads -> [meta + [stage: 'post_conversion'], reads] })
    )

    FASTQUTILS_INFO(CAT_FASTQ.out.reads)

    // One collated versions file, as nf-core/bamtofastq writes into pipeline_info/: the
    // human-readable evidence for the version each Software entity claims. Note it does
    // NOT end up in the crate -- Nextflow writes a `collectFile(storeDir: ...)` output
    // itself, without a publish event, and publish events are how the plugin learns that
    // a file exists. The same is true of the trace, report, timeline and DAG beside it.
    channel.empty()
        .mix(
            SAMTOOLS_INDEX.out.versions,
            SAMTOOLS_IDXSTATS.out.versions,
            SAMTOOLS_FLAGSTAT.out.versions,
            SAMTOOLS_STATS.out.versions,
            CHECKPAIREDEND.out.versions,
            SAMTOOLS_VIEW.out.versions,
            SAMTOOLS_MERGE.out.versions,
            SAMTOOLS_COLLATEFASTQ.out.versions,
            CAT_FASTQ.out.versions,
            FASTQC.out.versions,
            FASTQUTILS_INFO.out.versions,
        )
        .collectFile(
            name: 'bamtofastq_like_software_versions.yml',
            storeDir: "${params.outdir}/pipeline_info",
            sort: true,
        )
}
