// Minimal reproducer for the nf-fairscape evidence-graph StackOverflowError.
// Mirrors the nf-core 3.4+ "topic channel -> collectFile -> publish + consume"
// versions pattern: `collated.yml` is written by NEXTFLOW (no task produced it),
// is consumed by a task (so it counts as a workflow INPUT) and is also published
// (so it lands in the run Computation's `generated`). link-inverses then gives it
// generatedBy = the run Computation, closing a 2-node cycle.
process EMIT_VERSION {
    output:
    path 'versions.yml'
    script:
    """
    printf 'EMIT_VERSION:\\n  tool: 1.0\\n' > versions.yml
    """
}

process CONSUME {
    input:
    path collated
    output:
    path 'report.txt'
    script:
    """
    cat $collated > report.txt
    """
}

workflow {
    main:
    EMIT_VERSION()
    collated = EMIT_VERSION.out.collectFile(name: 'collated_versions.yml')
    CONSUME(collated)

    publish:
    versions = collated
    report   = CONSUME.out
}

output {
    versions {
        path 'pipeline_info'
    }
    report {
        path 'reports'
    }
}
