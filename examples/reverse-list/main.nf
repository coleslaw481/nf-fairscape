process REVERSE {
    publishDir 'results', mode: 'copy'

    // describe the actual tool this process runs; nf-fairscape uses these
    // values for the EVI Software entity instead of the process-derived defaults
    ext fairscape: [
        softwareName       : 'tac',
        softwareVersion    : '8.32',
        softwareAuthor     : 'Jay Lepreau, David MacKenzie (GNU coreutils)',
        softwareDescription: 'A command-line utility that reverses the order of lines in a text file.',
        softwareUrl        : 'https://www.gnu.org/software/coreutils/tac',
        softwareKeywords   : ['coreutils', 'text-processing']
    ]

    input:
    path listfile

    output:
    path 'reversed.txt'

    script:
    """
    tac ${listfile} > reversed.txt
    """
}

workflow {
    Channel.of('apple', 'banana', 'cherry')
        .collectFile(name: 'list.txt', newLine: true, sort: false)
        | REVERSE
}
