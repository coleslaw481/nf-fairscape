// Adversarial cases for nf-fairscape, each isolated in its own process.
// 1. SAME FILE staged twice into one task -> two FileHolders sharing a stage name
process DUP_STAGE {
    publishDir "$params.outdir", mode: 'copy'
    input:
    path a
    path b
    output:
    path 'dup.txt'
    script:
    """
    cat $a $b > dup.txt
    """
}

// 2. a published file with spaces and non-ASCII in the name
process ODD_NAME {
    publishDir "$params.outdir", mode: 'copy'
    output:
    path '*.csv'
    script:
    """
    printf 'a,b\\n1,2\\n' > 'wéird name (1).csv'
    """
}

// 3. publishDir mode 'move' -> the work-dir source no longer exists at crate time
process MOVED {
    publishDir "$params.outdir/moved", mode: 'move'
    output:
    path 'moved.tsv'
    script:
    """
    printf 'x\\ty\\n1\\t2\\n' > moved.tsv
    """
}

// 4. a tabular file that is not really tabular: a .csv holding gzip bytes
process FAKE_CSV {
    publishDir "$params.outdir", mode: 'copy'
    output:
    path 'binary.csv'
    script:
    """
    printf 'col\\n1\\n' | gzip -c > binary.csv
    """
}

// 5. an empty .tsv and a .tsv with duplicate header names
process EDGE_TABLES {
    publishDir "$params.outdir", mode: 'copy'
    output:
    path '*.tsv'
    script:
    """
    : > empty.tsv
    printf 'a\\ta\\ta\\n1\\t2\\t3\\n' > dupcols.tsv
    """
}

workflow {
    seed = Channel.of('hello\n').collectFile(name: 'seed.txt')
    // DUP_STAGE: Nextflow rejects duplicate stage names itself (input file name collision)
    ODD_NAME()
    MOVED()
    FAKE_CSV()
    EDGE_TABLES()
}
