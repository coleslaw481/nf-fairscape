#!/usr/bin/env nextflow

/*
 * Stock Cell Maps pipeline
 *
 *   IMAGE_DOWNLOAD -> IMAGE_EMBEDDING -\
 *                                       COEMBEDDING -> HIERARCHY
 *   PPI_DOWNLOAD   -> PPI_EMBEDDING   -/
 *
 * Each process runs in its own isolated Conda environment.
 *
 * Each environment YAML is also the source of Fairscape software metadata.
 * The software version is derived from the pinned pip dependency, e.g.:
 *
 *   - pip:
 *       - cellmaps_imagedownloader==0.3.0
 *
 * Expected custom metadata block:
 *
 * metadata:
 *   softwareName: "Cell Maps Image Downloader"
 *   softwareAuthor: "Cell Maps team"
 *   softwareDescription: "..."
 *   softwareUrl: "https://github.com/idekerlab/cellmaps_imagedownloader"
 */

nextflow.enable.dsl = 2


/*
 * Read Fairscape software metadata from a Conda environment YAML and derive
 * softwareVersion from the package's pinned pip dependency.
 */
def loadSoftwareConfig(String yamlPath, String packageName) {

    def yamlFile = file(yamlPath, checkIfExists: true)
    def config = new org.yaml.snakeyaml.Yaml().load(yamlFile.text)

    if (!config.metadata) {
        error "Missing metadata block in ${yamlPath}"
    }

    def pipBlock = config.dependencies?.find { dep ->
        dep instanceof Map && dep.containsKey('pip')
    }

    if (!pipBlock) {
        error "Missing pip dependency block in ${yamlPath}"
    }

    def packageSpec = pipBlock['pip'].find { spec ->
        spec.toString().startsWith("${packageName}==")
    }

    if (!packageSpec) {
        error "${packageName} must be pinned with == in ${yamlPath}"
    }

    def parts = packageSpec.toString().split('==', 2)

    if (parts.size() != 2 || !parts[1]) {
        error "Unable to determine version for ${packageName} from ${packageSpec}"
    }

    def meta = config.metadata

    return [
        softwareName       : meta.softwareName ?: packageName,
        softwareVersion    : parts[1],
        softwareAuthor     : meta.softwareAuthor ?: '',
        softwareDescription: meta.softwareDescription ?: '',
        softwareUrl        : meta.softwareUrl ?: ''
    ]
}


process IMAGE_DOWNLOAD {

    tag "${params.cell_line}"

    conda "${projectDir}/envs/imagedownloader.yml"

    publishDir "${params.outdir}/image_download", mode: 'copy'

    ext fairscape: loadSoftwareConfig(
        "${projectDir}/envs/imagedownloader.yml",
        'cellmaps_imagedownloader'
    )

    input:
    path samples
    path unique
    path provenance

    output:
    path 'image_download'

    script:
    // If unset, cellmaps_imagedownloader downloads proteinatlas.xml.gz itself.
    def pa_arg = params.proteinatlasxml
        ? "--proteinatlasxml ${params.proteinatlasxml}"
        : ''

    """
    cellmaps_imagedownloadercmd.py \
        image_download \
        --samples ${samples} \
        --unique ${unique} \
        --cell_line ${params.cell_line} \
        --provenance ${provenance} \
        ${pa_arg} \
        ${params.image_download_args}
    """
}


process PPI_DOWNLOAD {

    tag 'apms'

    conda "${projectDir}/envs/ppidownloader.yml"

    publishDir "${params.outdir}/ppi_download", mode: 'copy'

    ext fairscape: loadSoftwareConfig(
        "${projectDir}/envs/ppidownloader.yml",
        'cellmaps_ppidownloader'
    )

    input:
    path edgelist
    path baitlist
    path provenance

    output:
    path 'ppi_download'

    script:
    """
    cellmaps_ppidownloadercmd.py \
        ppi_download \
        --edgelist ${edgelist} \
        --baitlist ${baitlist} \
        --provenance ${provenance} \
        ${params.ppi_download_args}

    # The downloader may write CRLF line endings. Normalize them before
    # node2vec so gene names do not retain a trailing carriage return.
    # Not all platforms support the sed option below. Commenting out
    # for now since this should really be a bug that is fixed in 
    # Created ticket: https://github.com/idekerlab/cellmaps_ppidownloader/issues/10
    # find ppi_download -name '*.tsv' -exec sed -i 's/\\r\$//' {} +
    """
}


process IMAGE_EMBEDDING {

    tag "${params.cell_line}"

    conda "${projectDir}/envs/image_embedding.yml"

    publishDir "${params.outdir}/image_embedding", mode: 'copy'

    ext fairscape: loadSoftwareConfig(
        "${projectDir}/envs/image_embedding.yml",
        'cellmaps_image_embedding'
    )

    input:
    path image_crate

    output:
    path 'image_embedding'

    script:
    // If unset, the tool uses/downloads its default pretrained model.
    def model_arg = params.model_path
        ? "--model_path ${params.model_path}"
        : ''

    """
    cellmaps_image_embeddingcmd.py \
        image_embedding \
        --inputdir ${image_crate} \
        ${model_arg} \
        ${params.image_embedding_args}
    """
}


process PPI_EMBEDDING {

    tag 'apms'

    conda "${projectDir}/envs/ppi_embedding.yml"

    publishDir "${params.outdir}/ppi_embedding", mode: 'copy'

    ext fairscape: loadSoftwareConfig(
        "${projectDir}/envs/ppi_embedding.yml",
        'cellmaps_ppi_embedding'
    )

    input:
    path ppi_crate

    output:
    path 'ppi_embedding'

    script:
    """
    cellmaps_ppi_embeddingcmd.py \
        ppi_embedding \
        --inputdir ${ppi_crate} \
        ${params.ppi_embedding_args}
    """
}


process COEMBEDDING {

    tag "${params.cell_line}"

    conda "${projectDir}/envs/coembedding.yml"

    publishDir "${params.outdir}/coembedding", mode: 'copy'

    ext fairscape: loadSoftwareConfig(
        "${projectDir}/envs/coembedding.yml",
        'cellmaps_coembedding'
    )

    input:
    path image_embedding_dir
    path ppi_embedding_dir

    output:
    path 'coembedding'

    script:
    """
    cellmaps_coembeddingcmd.py \
        coembedding \
        --image_embeddingdir ${image_embedding_dir} \
        --ppi_embeddingdir ${ppi_embedding_dir} \
        ${params.coembedding_args}
    """
}


process HIERARCHY {

    tag "${params.cell_line}"

    conda "${projectDir}/envs/hierarchy.yml"

    publishDir "${params.outdir}/hierarchy", mode: 'copy'

    ext fairscape: loadSoftwareConfig(
        "${projectDir}/envs/hierarchy.yml",
        'cellmaps_generate_hierarchy'
    )

    input:
    path coembedding_dir

    output:
    path 'hierarchy'

    script:
    """
    cellmaps_generate_hierarchycmd.py \
        hierarchy \
        --coembedding_dirs ${coembedding_dir} \
        ${params.hierarchy_args}
    """
}


workflow {

    /*
     * Default input files.
     *
     * Override any of these from the command line:
     *
     *   --samples /path/to/samples.csv
     *   --unique /path/to/unique.csv
     *   --edgelist /path/to/edgelist.tsv
     *   --baitlist /path/to/baitlist.tsv
     *   --provenance /path/to/provenance.json
     */

    def samples = params.samples ?:
        "${projectDir}/inputs/samples.csv"

    def unique = params.unique ?:
        "${projectDir}/inputs/unique.csv"

    def edgelist = params.edgelist ?:
        "${projectDir}/inputs/edgelist.tsv"

    def baitlist = params.baitlist ?:
        "${projectDir}/inputs/baitlist.tsv"

    def provenance = params.provenance ?:
        "${projectDir}/inputs/provenance.json"


    ch_samples = file(
        samples,
        checkIfExists: true
    )

    ch_unique = file(
        unique,
        checkIfExists: true
    )

    ch_edgelist = file(
        edgelist,
        checkIfExists: true
    )

    ch_baitlist = file(
        baitlist,
        checkIfExists: true
    )

    ch_prov = file(
        provenance,
        checkIfExists: true
    )


    image_crate = IMAGE_DOWNLOAD(
        ch_samples,
        ch_unique,
        ch_prov
    )

    image_embedding = IMAGE_EMBEDDING(
        image_crate
    )


    ppi_crate = PPI_DOWNLOAD(
        ch_edgelist,
        ch_baitlist,
        ch_prov
    )

    ppi_embedding = PPI_EMBEDDING(
        ppi_crate
    )


    coembedding = COEMBEDDING(
        image_embedding,
        ppi_embedding
    )

    HIERARCHY(
        coembedding
    )
}
