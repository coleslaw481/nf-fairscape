#!/usr/bin/env nextflow
/*
 * Stock Cell Maps pipeline — every stage an unmodified idekerlab cellmaps_*
 * tool, starting from the two official downloaders. None of the CM4AI custom
 * scripts: the downloaders produce the IF-image and PPI RO-Crates directly and
 * carry provenance forward, so the split/filter and visualize steps aren't
 * needed.
 *
 *   IMAGE_DOWNLOAD -> IMAGE_EMBEDDING -\
 *                                       COEMBEDDING -> HIERARCHY
 *   PPI_DOWNLOAD   -> PPI_EMBEDDING   -/
 *
 * Only the downloaders take --provenance; every later step reads it from the
 * RO-Crate handed to it as inputdir.
 *
 * Defaults reproduce the canonical U2OS/BioPlex demo, which needs network for
 * the HPA images and the model download. `--image_download_args '--fake_images'`
 * for an offline smoke test.
 */

nextflow.enable.dsl = 2


process IMAGE_DOWNLOAD {
    tag "${params.cell_line}"
    publishDir "${params.outdir}/image_download", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps Image Downloader',
        softwareVersion    : '0.3.0',
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to download immunofluorescence images from the Human Protein Atlas and assemble them, with per-gene node attributes, into a cellmaps image RO-Crate.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_imagedownloader'
    ]

    input:
    path samples
    path unique
    path provenance

    output:
    path 'image_download'

    script:
    // no cached proteinatlas.xml.gz -> the tool fetches its own (~697 MB)
    def pa_arg = (params.proteinatlasxml && file(params.proteinatlasxml).exists()) ? "--proteinatlasxml ${params.proteinatlasxml}" : ''
    """
    ${params.python} ${params.base_dir}/cellmaps_imagedownloader/cellmaps_imagedownloader/cellmaps_imagedownloadercmd.py \\
        image_download \\
        --samples ${samples} \\
        --unique ${unique} \\
        --cell_line ${params.cell_line} \\
        --provenance ${provenance} \\
        ${pa_arg} ${params.image_download_args}
    """
}


process PPI_DOWNLOAD {
    tag 'apms'
    publishDir "${params.outdir}/ppi_download", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps PPI Downloader',
        softwareVersion    : '0.2.2',
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to load AP-MS protein-protein interaction data (edge list + bait list) and assemble it, with per-gene node attributes, into a cellmaps PPI RO-Crate.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_ppidownloader'
    ]

    input:
    path edgelist
    path baitlist
    path provenance

    output:
    path 'ppi_download'

    script:
    """
    ${params.python} ${params.base_dir}/cellmaps_ppidownloader/cellmaps_ppidownloader/cellmaps_ppidownloadercmd.py \\
        ppi_download \\
        --edgelist ${edgelist} \\
        --baitlist ${baitlist} \\
        --provenance ${provenance} ${params.ppi_download_args}

    # The downloader writes CRLF. node2vec then keeps the trailing \\r on every
    # second gene, so PPI node names never match the image gene names and
    # coembedding reports "no overlapping embeddings".
    find ppi_download -name '*.tsv' -exec sed -i 's/\\r\$//' {} +
    """
}


process IMAGE_EMBEDDING {
    tag "${params.cell_line}"
    publishDir "${params.outdir}/image_embedding", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps ImmunoFluorescent Image Embedder',
        softwareVersion    : '0.3.3',
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to generate embeddings from HPA IF images; produces 1024-dimensional immunofluorescence image embeddings using a pretrained DenseNet convolutional model.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_image_embedding'
    ]

    input:
    path image_crate

    output:
    path 'image_embedding'

    script:
    // unset -> the tool's pretrained DenseNet, auto-downloaded
    def model_arg = params.model_path ? "--model_path ${params.model_path}" : ''
    """
    ${params.python} ${params.base_dir}/cellmaps_image_embedding/cellmaps_image_embedding/cellmaps_image_embeddingcmd.py \\
        image_embedding \\
        --inputdir ${image_crate} \\
        ${model_arg} ${params.image_embedding_args}
    """
}


process PPI_EMBEDDING {
    tag 'apms'
    publishDir "${params.outdir}/ppi_embedding", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps PPI Embedder',
        softwareVersion    : '0.4.3',
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
    ${params.python} ${params.base_dir}/cellmaps_ppi_embedding/cellmaps_ppi_embedding/cellmaps_ppi_embeddingcmd.py \\
        ppi_embedding \\
        --inputdir ${ppi_crate} ${params.ppi_embedding_args}
    """
}


process COEMBEDDING {
    tag "${params.cell_line}"
    publishDir "${params.outdir}/coembedding", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps CoEmbedder',
        softwareVersion    : '1.5.0',
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
    ${params.python} ${params.base_dir}/cellmaps_coembedding/cellmaps_coembedding/cellmaps_coembeddingcmd.py \\
        coembedding \\
        --image_embeddingdir ${image_embedding_dir} \\
        --ppi_embeddingdir ${ppi_embedding_dir} ${params.coembedding_args}
    """
}


process HIERARCHY {
    tag "${params.cell_line}"
    publishDir "${params.outdir}/hierarchy", mode: 'copy'

    ext fairscape: [
        softwareName       : 'Cell Maps Generate Hierarchy',
        softwareVersion    : '0.2.5',
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
    ${params.python} ${params.base_dir}/cellmaps_generate_hierarchy/cellmaps_generate_hierarchy/cellmaps_generate_hierarchycmd.py \\
        hierarchy \\
        --coembedding_dirs ${coembedding_dir} ${params.hierarchy_args}
    """
}


workflow {
    // default to the U2OS/Bioplex examples bundled in the downloader packages
    def samples    = params.samples    ?: "${params.base_dir}/cellmaps_imagedownloader/examples/samples.csv"
    def unique     = params.unique     ?: "${params.base_dir}/cellmaps_imagedownloader/examples/unique.csv"
    def edgelist   = params.edgelist   ?: "${params.base_dir}/cellmaps_ppidownloader/examples/edgelist.tsv"
    def baitlist   = params.baitlist   ?: "${params.base_dir}/cellmaps_ppidownloader/examples/baitlist.tsv"
    // one provenance file covers all four, and both downloaders share it
    def provenance = params.provenance ?: "${params.base_dir}/cellmaps_imagedownloader/examples/provenance.json"

    ch_samples  = file(samples,    checkIfExists: true)
    ch_unique   = file(unique,     checkIfExists: true)
    ch_edgelist = file(edgelist,   checkIfExists: true)
    ch_baitlist = file(baitlist,   checkIfExists: true)
    ch_prov     = file(provenance, checkIfExists: true)

    image_crate     = IMAGE_DOWNLOAD(ch_samples, ch_unique, ch_prov)
    image_embedding = IMAGE_EMBEDDING(image_crate)

    ppi_crate       = PPI_DOWNLOAD(ch_edgelist, ch_baitlist, ch_prov)
    ppi_embedding   = PPI_EMBEDDING(ppi_crate)

    coembedding = COEMBEDDING(image_embedding, ppi_embedding)
    HIERARCHY(coembedding)
}
