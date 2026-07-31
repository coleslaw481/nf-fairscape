#!/usr/bin/env nextflow
/*
 * Cell Maps pipeline, one container per stage.
 *
 *   IMAGE_DOWNLOAD -> IMAGE_EMBEDDING -\
 *                                       COEMBEDDING -> HIERARCHY
 *   PPI_DOWNLOAD   -> PPI_EMBEDDING   -/
 *
 * Each modality can start from a download, from a local CM4AI crate
 * (--cm4ai_image_table / --cm4ai_apms_table), or from a finished crate
 * (--image_crate / --ppi_crate). See README.md.
 *
 * Anything a container has to read must be a declared `path` input — Nextflow
 * only bind-mounts the work dir and the parents of staged inputs, so a bare
 * path pasted into a command line is invisible inside the container.
 */

nextflow.enable.dsl = 2

// Optional path param -> the file, or [] meaning "nothing staged".
// Using [] rather than a placeholder file keeps unset inputs out of the
// RO-Crate and avoids the input-name collision two unset placeholders cause.
// The CharSequence check is load-bearing: `--foo ''` arrives as boolean true.
def isSet = { value -> value instanceof CharSequence && value.toString().trim() as boolean }

def optionalPath = { value -> isSet(value) ? file(value.toString(), checkIfExists: true) : [] }


process IMAGE_DOWNLOAD {
    tag "${params.cell_line}"
    container "${params.containers.image_download}"
    publishDir "${params.outdir}/image_download", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps Image Downloader',
        softwareVersion    : params.versions.image_download,
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to download immunofluorescence images from the Human Protein Atlas and assemble them, with per-gene node attributes, into a cellmaps image RO-Crate.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_imagedownloader'
    ]

    input:
    path samples
    path unique
    path provenance
    path proteinatlas
    path cm4ai_dir
    val  cm4ai_table_name

    output:
    path 'image_download'

    script:
    // no cached proteinatlas.xml.gz -> the tool fetches its own (~697 MB)
    def pa_arg = proteinatlas ? "--proteinatlasxml ${proteinatlas}" : ''
    def src_arg = cm4ai_dir
        ? "--cm4ai_table ${cm4ai_dir}/${cm4ai_table_name}"
        : "--samples ${samples} --unique ${unique} --cell_line ${params.cell_line}"
    """
    cellmaps_imagedownloadercmd.py \\
        image_download \\
        ${src_arg} \\
        --provenance ${provenance} \\
        ${pa_arg} ${params.image_download_args}
    """
}


process PPI_DOWNLOAD {
    tag 'apms'
    container "${params.containers.ppi_download}"
    publishDir "${params.outdir}/ppi_download", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps PPI Downloader',
        softwareVersion    : params.versions.ppi_download,
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to load AP-MS protein-protein interaction data (edge list + bait list) and assemble it, with per-gene node attributes, into a cellmaps PPI RO-Crate.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_ppidownloader'
    ]

    input:
    path edgelist
    path baitlist
    path provenance
    path cm4ai_table

    output:
    path 'ppi_download'

    script:
    def src_arg = cm4ai_table
        ? "--cm4ai_table ${cm4ai_table}"
        : "--edgelist ${edgelist} --baitlist ${baitlist}"
    """
    cellmaps_ppidownloadercmd.py \\
        ppi_download \\
        ${src_arg} \\
        --provenance ${provenance} ${params.ppi_download_args}

    # The downloader writes CRLF. node2vec then keeps the trailing \\r on every
    # second gene, so PPI node names never match the image gene names and
    # coembedding reports "no overlapping embeddings".
    find ppi_download -name '*.tsv' -exec sed -i 's/\\r\$//' {} +
    """
}


process IMAGE_EMBEDDING {
    tag "${params.cell_line}"
    container "${params.containers.image_embedding}"
    publishDir "${params.outdir}/image_embedding", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps ImmunoFluorescent Image Embedder',
        softwareVersion    : params.versions.image_embedding,
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to generate embeddings from HPA IF images; produces 1024-dimensional immunofluorescence image embeddings using a pretrained DenseNet convolutional model.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_image_embedding'
    ]

    input:
    path image_crate
    path model

    output:
    path 'image_embedding'

    script:
    // a staged .pth wins, else the copy baked into the image; falling through
    // to the tool's default means re-downloading 69 MB per task
    def model_arg = model
        ? "--model_path ${model}"
        : '${CELLMAPS_DENSENET_MODEL:+--model_path $CELLMAPS_DENSENET_MODEL}'
    """
    cellmaps_image_embeddingcmd.py \\
        image_embedding \\
        --inputdir ${image_crate} \\
        ${model_arg} ${params.image_embedding_args}
    """
}


process PPI_EMBEDDING {
    tag 'apms'
    container "${params.containers.ppi_embedding}"
    publishDir "${params.outdir}/ppi_embedding", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps PPI Embedder',
        softwareVersion    : params.versions.ppi_embedding,
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to generate embeddings from networks; embeds a protein-protein interaction network into a vector space using the node2vec random-walk algorithm.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_ppi_embedding'
    ]

    input:
    path ppi_crate

    output:
    path 'ppi_embedding'

    script:
    """
    cellmaps_ppi_embeddingcmd.py \\
        ppi_embedding \\
        --inputdir ${ppi_crate} ${params.ppi_embedding_args}
    """
}


process COEMBEDDING {
    tag "${params.cell_line}"
    container "${params.containers.coembedding}"
    publishDir "${params.outdir}/coembedding", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps CoEmbedder',
        softwareVersion    : params.versions.coembedding,
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to generate coembeddings from IF image embeddings and PPI network embeddings; fuses the two modalities into a single latent space using the MUSE multi-modal autoencoder.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_coembedding'
    ]

    input:
    path image_embedding_dir
    path ppi_embedding_dir

    output:
    path 'coembedding'

    script:
    """
    cellmaps_coembeddingcmd.py \\
        coembedding \\
        --image_embeddingdir ${image_embedding_dir} \\
        --ppi_embeddingdir ${ppi_embedding_dir} ${params.coembedding_args}
    """
}


process HIERARCHY {
    tag "${params.cell_line}"
    container "${params.containers.hierarchy}"
    publishDir "${params.outdir}/hierarchy", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps Generate Hierarchy',
        softwareVersion    : params.versions.hierarchy,
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to generate hierarchies from protein to protein interaction networks; builds a multi-scale hierarchical cell map from the coembedding using the HiDeF community-detection algorithm.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_generate_hierarchy'
    ]

    input:
    path coembedding_dir

    output:
    path 'hierarchy'

    script:
    """
    cellmaps_generate_hierarchycmd.py \\
        hierarchy \\
        --coembedding_dirs ${coembedding_dir} ${params.hierarchy_args}
    """
}


workflow {
    ch_proteinatlas = optionalPath(params.proteinatlasxml)
    ch_cm4ai_apms   = optionalPath(params.cm4ai_apms_table)
    ch_model        = optionalPath(params.model_path)

    // default to the U2OS/Bioplex examples bundled in the downloader packages
    def samples    = params.samples    ?: "${params.examples_dir}/cellmaps_imagedownloader/examples/samples.csv"
    def unique     = params.unique     ?: "${params.examples_dir}/cellmaps_imagedownloader/examples/unique.csv"
    def edgelist   = params.edgelist   ?: "${params.examples_dir}/cellmaps_ppidownloader/examples/edgelist.tsv"
    def baitlist   = params.baitlist   ?: "${params.examples_dir}/cellmaps_ppidownloader/examples/baitlist.tsv"
    // one provenance file covers all four
    def provenance = params.provenance ?: "${params.examples_dir}/cellmaps_imagedownloader/examples/provenance.json"

    ch_prov = file(provenance, checkIfExists: true)

    if( isSet(params.image_crate) ) {
        image_crate = Channel.value(file(params.image_crate, type: 'dir', checkIfExists: true))
    }
    else {
        // the CM4AI table names image files beside it, so stage the whole crate dir
        def cm4ai_tbl  = isSet(params.cm4ai_image_table)
            ? file(params.cm4ai_image_table.toString(), checkIfExists: true) : null
        def cm4ai_img  = cm4ai_tbl ? file(cm4ai_tbl.parent, type: 'dir') : []
        def cm4ai_name = cm4ai_tbl ? cm4ai_tbl.name : ''
        image_crate = IMAGE_DOWNLOAD(
            file(samples, checkIfExists: true),
            file(unique,  checkIfExists: true),
            ch_prov,
            ch_proteinatlas,
            cm4ai_img,
            cm4ai_name)
    }

    if( isSet(params.ppi_crate) ) {
        ppi_crate = Channel.value(file(params.ppi_crate, type: 'dir', checkIfExists: true))
    }
    else {
        ppi_crate = PPI_DOWNLOAD(
            file(edgelist, checkIfExists: true),
            file(baitlist, checkIfExists: true),
            ch_prov,
            ch_cm4ai_apms)
    }

    image_embedding = IMAGE_EMBEDDING(image_crate, ch_model)
    ppi_embedding   = PPI_EMBEDDING(ppi_crate)

    coembedding = COEMBEDDING(image_embedding, ppi_embedding)
    HIERARCHY(coembedding)
}
