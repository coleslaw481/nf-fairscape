#!/usr/bin/env bash
# =============================================================================
# Build every container the cellmaps-docker pipeline runs.
# =============================================================================
# Six images, one per pipeline stage. Three are thin wrappers around images the
# Cell Maps team publishes (cm4ai/*); three are built from PyPI here because
# upstream has no usable amd64 image or the published one is 8 GB of unused
# CUDA. Which is which, and why, is documented in each Dockerfile.
#
#   ./build.sh              build all six
#   ./build.sh hierarchy    build one (imagedownloader|ppidownloader|
#                           image_embedding|ppi_embedding|coembedding|hierarchy)
#
# The tags produced here are exactly the ones nextflow.config expects. If you
# change a version, change it in nextflow.config's VERSIONS map — that map
# drives both the image tag AND the softwareVersion recorded in the RO-Crate,
# so they cannot silently drift apart.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

# Keep in sync with the VERSIONS map in ../nextflow.config.
IMAGEDOWNLOADER_VERSION=0.3.0
PPIDOWNLOADER_VERSION=0.2.2
IMAGE_EMBEDDING_VERSION=0.3.3
PPI_EMBEDDING_VERSION=0.4.3
COEMBEDDING_VERSION=1.5.0
HIERARCHY_VERSION=0.2.5

NS=${NF_CELLMAPS_NAMESPACE:-nf-cellmaps}

build_wrapper() {   # $1 = tool, $2 = upstream repo, $3 = version
    echo "==> ${NS}/$1:$3  (wrapping cm4ai/$2:$3)"
    docker build -f Dockerfile.upstream \
        --build-arg "BASE=cm4ai/$2:$3" \
        -t "${NS}/$1:$3" .
}

build_local() {     # $1 = tool, $2 = dockerfile suffix, $3 = version
    echo "==> ${NS}/$1:$3  (built from PyPI)"
    docker build -f "Dockerfile.$2" \
        --build-arg "TOOL_VERSION=$3" \
        -t "${NS}/$1:$3" .
}

target=${1:-all}
case "$target" in
  all|imagedownloader|ppidownloader|ppi_embedding|image_embedding|coembedding|hierarchy) ;;
  *) echo "unknown target: $target" >&2; exit 1 ;;
esac
want() { [ "$target" = all ] || [ "$target" = "$1" ]; }

# `if`, not `want x && build`: under `set -e` a false `&&` list ends the script.
if want imagedownloader; then build_wrapper imagedownloader    cellmaps_imagedownloader "$IMAGEDOWNLOADER_VERSION"; fi
if want ppidownloader;   then build_wrapper ppidownloader      cellmaps_ppidownloader   "$PPIDOWNLOADER_VERSION";   fi
if want ppi_embedding;   then build_wrapper ppi_embedding      cellmaps_ppi_embedding   "$PPI_EMBEDDING_VERSION";   fi
if want image_embedding; then build_local   image_embedding    image_embedding          "$IMAGE_EMBEDDING_VERSION"; fi
if want coembedding;     then build_local   coembedding        coembedding              "$COEMBEDDING_VERSION";     fi
if want hierarchy;       then build_local   generate_hierarchy hierarchy                "$HIERARCHY_VERSION";       fi

echo
echo "Built images:"
docker images --filter "reference=${NS}/*" \
    --format '  {{.Repository}}:{{.Tag}}  {{.Size}}'
