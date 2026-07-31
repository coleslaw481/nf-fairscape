/*
 * Three tiny steps whose outputs land in an S3 bucket rather than on a local
 * disk. Deliberately trivial -- the point is the filesystem under the crate,
 * not the computation.
 */

params.n      = 8
params.outdir = 's3://nf-prov/results'

process MAKE_LIST {
    publishDir params.outdir, mode: 'copy'

    ext fairscape: [
        softwareName       : 'head',
        softwareVersion    : '8.32',
        softwareAuthor     : 'David MacKenzie, Jim Meyering (GNU coreutils)',
        softwareDescription: 'GNU coreutils head -- takes the first n lines of a bash-generated a-to-z sequence to build the letter list.',
        softwareUrl        : 'https://www.gnu.org/software/coreutils/head'
    ]

    input:
    val n

    output:
    path 'letters.txt'

    script:
    """
    printf '%s\\n' {a..z} | head -n ${n} > letters.txt
    """
}

process REVERSE {
    publishDir params.outdir, mode: 'copy'

    ext fairscape: [
        softwareName       : 'tac',
        softwareVersion    : '8.32',
        softwareAuthor     : 'Jay Lepreau, David MacKenzie (GNU coreutils)',
        softwareDescription: 'GNU coreutils tac -- reverses the order of lines in a text file.',
        softwareUrl        : 'https://www.gnu.org/software/coreutils/tac'
    ]

    input:
    path letters

    output:
    path 'reversed.txt'

    script:
    """
    tac ${letters} > reversed.txt
    """
}

process SPLIT_HALVES {
    publishDir params.outdir, mode: 'copy'

    ext fairscape: [
        softwareName       : 'GNU coreutils (wc, head, tail)',
        softwareVersion    : '8.32',
        softwareAuthor     : 'GNU coreutils authors',
        softwareDescription: 'GNU coreutils text utilities -- wc counts the lines, then head and tail split the reversed list in two.',
        softwareUrl        : 'https://www.gnu.org/software/coreutils/'
    ]

    input:
    path reversed

    output:
    path 'first_half.txt'
    path 'second_half.txt'

    script:
    """
    lines=\$(wc -l < ${reversed})
    half=\$(( (lines + 1) / 2 ))
    head -n \$half ${reversed} > first_half.txt
    tail -n +\$(( half + 1 )) ${reversed} > second_half.txt
    """
}

workflow {
    MAKE_LIST(params.n) | REVERSE | SPLIT_HALVES
}
