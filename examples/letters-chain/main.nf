params.n = 8

// step 1: make a list of the first n letters, one per line
process MAKE_LIST {
    publishDir 'results', mode: 'copy'

    ext fairscape: [
        softwareName       : 'head',
        softwareVersion    : '8.32',
        softwareAuthor     : 'David MacKenzie, Jim Meyering (GNU coreutils)',
        softwareDescription: 'GNU coreutils head — here it takes the first n lines of a bash-generated a-to-z sequence to build the letter list.',
        softwareUrl        : 'https://www.gnu.org/software/coreutils/head',
        softwareKeywords   : ['coreutils', 'text-processing']
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

// step 2: reverse the list
process REVERSE {
    publishDir 'results', mode: 'copy'

    ext fairscape: [
        softwareName       : 'tac',
        softwareVersion    : '8.32',
        softwareAuthor     : 'Jay Lepreau, David MacKenzie (GNU coreutils)',
        softwareDescription: 'GNU coreutils tac — a command-line utility that reverses the order of lines in a text file.',
        softwareUrl        : 'https://www.gnu.org/software/coreutils/tac',
        softwareKeywords   : ['coreutils', 'text-processing']
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

// step 3: divide the reversed list into two halves
process SPLIT_HALVES {
    publishDir 'results', mode: 'copy'

    ext fairscape: [
        softwareName       : 'GNU coreutils (wc, head, tail)',
        softwareVersion    : '8.32',
        softwareAuthor     : 'GNU coreutils authors (wc: Paul Rubin, David MacKenzie)',
        softwareDescription: 'GNU coreutils text utilities — wc counts the lines, then head and tail split the reversed list into a first and second half.',
        softwareUrl        : 'https://www.gnu.org/software/coreutils/',
        softwareKeywords   : ['coreutils', 'text-processing', 'partitioning']
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
