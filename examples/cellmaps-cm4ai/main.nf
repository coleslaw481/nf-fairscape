#!/usr/bin/env nextflow
/*
===============================================================================
 CM4AI Pipeline
===============================================================================

 Pipeline stages (same 8 steps as the bash script):
   1. CONVERT_MANIFEST   HPA manifest.csv -> cellmaps node-attributes TSV
   2. IMAGE_EMBEDDING    IF images -> 1024-d embeddings (DenseNet)
   3. SECMS_TO_EDGELIST  SEC-MS elution profiles -> PPI edge list (cosine sim)
   4. PPI_EMBEDDING      PPI network -> embeddings (node2vec)
   5. SPLIT_FILTER       split combined gene IDs, filter to image/PPI overlap
   6. COEMBEDDING        fuse image + PPI embeddings (MUSE)
   7. HIERARCHY          build hierarchical cell map (HiDeF)
   8. VISUALIZE          MuSIC-style hierarchy figure

===============================================================================
*/

nextflow.enable.dsl = 2

// SEC-MS report abbreviation per treatment (from the bash `case` block)
def SECMS_ABBREV = [ untreated: 'CTRL', vorinostat: 'VRST', paclitaxel: 'PTXL' ]

// =============================================================================
// STEP 1: MANIFEST CONVERSION
// Convert HPA-style manifest.csv to cellmaps_image_embedding node-attributes TSV.
// Assembles an "image crate" dir (image channels + manifest + generated TSV)
// that becomes the --inputdir for step 2.
// =============================================================================
process CONVERT_MANIFEST {
    tag "${params.treatment}"

    // Software provenance for the RO-Crate (read by nf-fairscape). Local pipeline
    // script, so the URL is its path within the CM4AI project (not a cellmaps repo).
    ext fairscape: [
        softwareName       : 'CM4AI HPA Manifest Converter',
        softwareAuthor     : 'CM4AI Pipeline',
        softwareDescription: 'Converts an HPA-style manifest.csv into the cellmaps_image_embedding node-attributes TSV, matching immunofluorescence image files to genes by plate/well parsed from filenames.',
        softwareUrl        : 'images/convert_manifest.py'
    ]

    input:
    path images_dir

    output:
    path 'image_input'

    script:
    """
    # Assemble the cellmaps input directory. Symlink the heavy per-channel image
    # folders (cheap) and copy the small manifest, then generate the node-attrs TSV.
    mkdir -p image_input
    for sub in red green blue yellow rgb; do
        if [ -e "${images_dir}/\$sub" ]; then
            ln -s "\$(readlink -f ${images_dir}/\$sub)" "image_input/\$sub"
        fi
    done
    cp "${images_dir}/manifest.csv" image_input/manifest.csv

    ${params.python} ${params.base_dir}/images/convert_manifest.py ${params.treatment} \\
        --input-dir image_input \\
        --output-dir image_input
    """
}

// =============================================================================
// STEP 2: IMAGE EMBEDDING (DenseNet)
// 1024-d embeddings from immunofluorescence images. Uses a pretrained model when
// one is configured & present (params.model_path), else trains from scratch.
// (The original --start-clean flag was a fairscape-cli concern and is dropped.)
// =============================================================================
process IMAGE_EMBEDDING {
    tag "${params.treatment}"
    publishDir "${params.outdir}/image_embedding", mode: 'copy'

    // cellmaps tool -> its idekerlab GitHub repo (version/author/description authoritative
    // from the installed package's __init__.py)
    ext fairscape: [
        softwareName       : 'Cell Maps ImmunoFluorescent Image Embedder',
        softwareVersion    : '0.3.3',
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to generate embeddings from HPA IF images; produces 1024-dimensional immunofluorescence image embeddings using a pretrained DenseNet convolutional model.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_image_embedding'
    ]

    input:
    path image_crate
    path provenance

    output:
    path "${params.treatment}_embedding"

    script:
    def use_model = params.model_path && file(params.model_path).exists()
    def model_arg = use_model ? "--model_path ${params.model_path}" : ''
    // --provenance satisfies the runner's "inputdir must be an RO-Crate OR provenance
    // must be given" check (our assembled image_input isn't a full RO-Crate).
    """
    ${params.python} ${params.base_dir}/cellmaps_image_embedding/cellmaps_image_embedding/cellmaps_image_embeddingcmd.py \\
        ${params.treatment}_embedding \\
        --inputdir ${image_crate} \\
        --provenance ${provenance} \\
        ${model_arg} ${params.image_embedding_args}
    """
}

// =============================================================================
// STEP 3: SEC-MS TO EDGELIST
// Co-elution (cosine similarity) of SEC-MS profiles -> PPI edge list.
// Emits a directory holding ppi_edgelist.tsv (the --inputdir for step 4).
// =============================================================================
process SECMS_TO_EDGELIST {
    tag "${params.treatment}"
    publishDir "${params.outdir}/edgelist", mode: 'copy'

    // Local pipeline script (feeds cellmaps_ppi_embedding); URL is its project path.
    ext fairscape: [
        softwareName       : 'CM4AI SEC-MS Edge List Builder',
        softwareAuthor     : 'CM4AI Pipeline',
        softwareDescription: 'Converts SEC-MS (size-exclusion chromatography / mass-spectrometry) elution profiles into a protein-protein interaction edge list, scoring co-elution of proteins by cosine similarity across fractions.',
        softwareUrl        : 'sec-ms/secms_to_edgelist.py'
    ]

    input:
    path report

    output:
    path 'edgelist'

    script:
    """
    mkdir -p edgelist
    ${params.python} ${params.base_dir}/sec-ms/secms_to_edgelist.py \\
        ${report} \\
        edgelist/ppi_edgelist.tsv \\
        --similarity-threshold ${params.similarity_threshold} \\
        --min-shared-fractions ${params.min_shared_fractions}
    """
}

// =============================================================================
// STEP 4: PPI EMBEDDING (node2vec)
// Network embeddings from the PPI edge list.
// =============================================================================
process PPI_EMBEDDING {
    tag "${params.treatment}"
    publishDir "${params.outdir}/ppi_embedding", mode: 'copy'

    // cellmaps tool -> its idekerlab GitHub repo
    ext fairscape: [
        softwareName       : 'Cell Maps PPI Embedder',
        softwareVersion    : '0.4.3',
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to generate embeddings from networks; embeds a protein-protein interaction network into a vector space using the node2vec random-walk algorithm.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_ppi_embedding'
    ]

    input:
    path edgelist_dir
    path provenance

    output:
    path "${params.treatment}_embedding_output"

    script:
    """
    ${params.python} ${params.base_dir}/cellmaps_ppi_embedding/cellmaps_ppi_embedding/cellmaps_ppi_embeddingcmd.py \\
        ${params.treatment}_embedding_output \\
        --inputdir ${edgelist_dir} \\
        --provenance ${provenance} ${params.ppi_embedding_args}
    """
}

// =============================================================================
// STEP 5: SPLIT COMBINED IDs & FILTER TO OVERLAP
// Splits combined antibody gene IDs (GENE1_GENE2_...), dedups, and keeps only
// genes present in BOTH the image and PPI embeddings.
//
// split_combined_embeddings.py hard-codes its I/O layout relative to --base-dir
// (<base>/images/<t>_embedding/image_emd.tsv, <base>/sec-ms/<t>_embedding_output/
// ppi_emd.tsv -> <base>/<t>_cleaned_embeddings). We reconstruct that minimal tree
// inside the task work dir and point --base-dir at it, so the real script runs
// unmodified.
// =============================================================================
process SPLIT_FILTER {
    tag "${params.treatment}"
    publishDir "${params.outdir}/cleaned_embeddings", mode: 'copy'

    // Local pipeline script; URL is its project path.
    ext fairscape: [
        softwareName       : 'CM4AI Embedding Splitter and Filter',
        softwareAuthor     : 'CM4AI Pipeline',
        softwareDescription: 'Splits combined antibody gene IDs (GENE1_GENE2_...) into separate rows, averages duplicate gene embeddings, and filters to the genes present in both the image and PPI embeddings.',
        softwareUrl        : 'split_combined_embeddings.py'
    ]

    input:
    path image_embedding_dir
    path ppi_embedding_dir

    output:
    path "${params.treatment}_cleaned_embeddings"

    script:
    """
    mkdir -p images/${params.treatment}_embedding sec-ms/${params.treatment}_embedding_output
    cp ${image_embedding_dir}/image_emd.tsv images/${params.treatment}_embedding/image_emd.tsv
    cp ${ppi_embedding_dir}/ppi_emd.tsv     sec-ms/${params.treatment}_embedding_output/ppi_emd.tsv

    ${params.python} ${params.base_dir}/split_combined_embeddings.py ${params.treatment} --base-dir .
    """
}

// =============================================================================
// STEP 6: COEMBEDDING (MUSE)
// Fuse the filtered image and PPI embeddings into a single latent space.
// =============================================================================
process COEMBEDDING {
    tag "${params.treatment}"
    publishDir "${params.outdir}/coembedding", mode: 'copy'

    // cellmaps tool -> its idekerlab GitHub repo
    ext fairscape: [
        softwareName       : 'Cell Maps CoEmbedder',
        softwareVersion    : '1.5.0',
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to generate coembeddings from IF image embeddings and PPI network embeddings; fuses the two modalities into a single latent space using the MUSE multi-modal autoencoder.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_coembedding'
    ]

    input:
    path cleaned_dir
    path provenance

    output:
    path "coembedding_output_${params.treatment}"

    script:
    """
    ${params.python} ${params.base_dir}/cellmaps_coembedding/cellmaps_coembedding/cellmaps_coembeddingcmd.py \\
        coembedding_output_${params.treatment} \\
        --embeddings ${cleaned_dir}/image_emd_split_filtered.tsv \\
                     ${cleaned_dir}/ppi_emd_split_filtered.tsv \\
        --embedding_names image ppi \\
        --provenance ${provenance} ${params.coembedding_args}
    """
}

// =============================================================================
// STEP 7: HIERARCHY GENERATION (HiDeF)
// Build the multi-scale hierarchical cell map from the coembedding.
// =============================================================================
process HIERARCHY {
    tag "${params.treatment}"
    publishDir "${params.outdir}/hierarchy", mode: 'copy'

    // cellmaps tool -> its idekerlab GitHub repo
    ext fairscape: [
        softwareName       : 'Cell Maps Generate Hierarchy',
        softwareVersion    : '0.2.5',
        softwareAuthor     : 'Cell Maps team',
        softwareDescription: 'A tool to generate hierarchies from protein to protein interaction networks; builds a multi-scale hierarchical cell map from the coembedding using the HiDeF community-detection algorithm.',
        softwareUrl        : 'https://github.com/idekerlab/cellmaps_generate_hierarchy'
    ]

    input:
    path coembedding_dir
    path provenance

    output:
    path "hierarchy_output_${params.treatment}"

    script:
    """
    ${params.python} ${params.base_dir}/cellmaps_generate_hierarchy/cellmaps_generate_hierarchy/cellmaps_generate_hierarchycmd.py \\
        hierarchy_output_${params.treatment} \\
        --coembedding_dirs ${coembedding_dir} \\
        --provenance ${provenance} ${params.hierarchy_args}
    """
}

// =============================================================================
// STEP 8: HIERARCHY VISUALIZATION (MuSIC-style)
// =============================================================================
process VISUALIZE {
    tag "${params.treatment}"
    publishDir "${params.outdir}/visualization", mode: 'copy'

    // Local pipeline script; URL is its project path.
    ext fairscape: [
        softwareName       : 'CM4AI Hierarchy Visualizer',
        softwareAuthor     : 'CM4AI Pipeline',
        softwareDescription: 'Renders the protein hierarchy as a MuSIC-style tree figure, after Qin et al., Nature 2021, "A multi-scale map of cell structure fusing protein images and interactions".',
        softwareUrl        : 'visualize_hierarchy.py'
    ]

    input:
    path hierarchy_dir

    output:
    path 'hierarchy_music_style.png'

    script:
    """
    ${params.python} ${params.base_dir}/visualize_hierarchy.py \\
        --input ${hierarchy_dir} \\
        --output hierarchy_music_style.png \\
        --title "SEC-MS MDAMB468 ${params.treatment} Protein Hierarchy"
    """
}

// =============================================================================
// WORKFLOW
// =============================================================================
workflow {
    def abbrev = SECMS_ABBREV[params.treatment.toLowerCase()]
    if( abbrev == null )
        error "Unknown treatment '${params.treatment}'. Expected: untreated, Vorinostat, or Paclitaxel"

    def images_dir   = params.images_dir        ?: "${params.base_dir}/images/${params.treatment}"
    def reports_dir  = params.secms_reports_dir ?: "${params.base_dir}/sec-ms/cancer-cells"
    def provenance   = params.provenance        ?: "${params.base_dir}/sec-ms/provenance.json"

    ch_images = Channel.fromPath(images_dir, type: 'dir', checkIfExists: true)

    // Pick the first matching SEC-MS report deterministically (bash used `ls ... | head -1`)
    ch_report = Channel
        .fromPath("${reports_dir}/Biosep_MDAMB468_${abbrev}_*_Report.tsv", checkIfExists: true)
        .toSortedList()
        .map { reports -> reports[0] }

    // Shared provenance template used by the cellmaps steps
    ch_prov = file(provenance, checkIfExists: true)

    // ---- image branch ----
    image_crate     = CONVERT_MANIFEST(ch_images)
    image_embedding = IMAGE_EMBEDDING(image_crate, ch_prov)

    // ---- PPI branch ----
    edgelist      = SECMS_TO_EDGELIST(ch_report)
    ppi_embedding = PPI_EMBEDDING(edgelist, ch_prov)

    // ---- fuse -> hierarchy -> figure ----
    cleaned     = SPLIT_FILTER(image_embedding, ppi_embedding)
    coembedding = COEMBEDDING(cleaned, ch_prov)
    hierarchy   = HIERARCHY(coembedding, ch_prov)
    VISUALIZE(hierarchy)
}
