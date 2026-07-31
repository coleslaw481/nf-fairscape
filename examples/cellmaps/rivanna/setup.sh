#!/usr/bin/env bash
# Builds the env and clones the six cellmaps_* repos. Re-runnable.
#   bash setup.sh
set -euo pipefail

CELLMAPS_ROOT="${CELLMAPS_ROOT:-/project/clarklab/nf/cellmap}"
ENV_PREFIX="$CELLMAPS_ROOT/env"
SRC_DIR="$CELLMAPS_ROOT/src"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# main.nf calls each tool by source path, so the trees must exist
REPOS=(
  "cellmaps_imagedownloader     6da86e1"
  "cellmaps_ppidownloader       9ce9607"
  "cellmaps_image_embedding     99fa579"
  "cellmaps_ppi_embedding       4160147"
  "cellmaps_coembedding         627bb8c"
  "cellmaps_generate_hierarchy  be68d00"
)

mkdir -p "$SRC_DIR"

if command -v conda >/dev/null 2>&1; then
  CONDA_SH="$(conda info --base)/etc/profile.d/conda.sh"
else
  if [ ! -d "$HOME/miniforge3" ]; then
    echo "==> installing Miniforge"
    curl -fsSL -o /tmp/miniforge-$$.sh \
      "https://github.com/conda-forge/miniforge/releases/latest/download/Miniforge3-Linux-x86_64.sh"
    bash /tmp/miniforge-$$.sh -b -p "$HOME/miniforge3"
    rm -f /tmp/miniforge-$$.sh
  fi
  CONDA_SH="$HOME/miniforge3/etc/profile.d/conda.sh"
fi
# shellcheck disable=SC1090
source "$CONDA_SH"

if [ -x "$ENV_PREFIX/bin/python" ]; then
  echo "==> env exists at $ENV_PREFIX"
else
  echo "==> creating env at $ENV_PREFIX"
  conda env create -p "$ENV_PREFIX" -f "$HERE/environment.yml"
fi
PY="$ENV_PREFIX/bin/python"

for entry in "${REPOS[@]}"; do
  read -r name commit <<<"$entry"
  dest="$SRC_DIR/$name"
  [ -d "$dest/.git" ] || git clone --quiet "https://github.com/idekerlab/$name" "$dest"
  git -C "$dest" fetch --quiet --all --tags
  git -C "$dest" checkout --quiet "$commit"
  echo "    $name @ $(git -C "$dest" rev-parse --short HEAD)"
done

# one pip call: installed separately, a later package lifts numpy past the <2 cap
echo "==> installing packages"
"$PY" -m pip install --quiet \
  -e "$SRC_DIR/cellmaps_imagedownloader" \
  -e "$SRC_DIR/cellmaps_ppidownloader" \
  -e "$SRC_DIR/cellmaps_image_embedding" \
  -e "$SRC_DIR/cellmaps_ppi_embedding" \
  -e "$SRC_DIR/cellmaps_coembedding" \
  -e "$SRC_DIR/cellmaps_generate_hierarchy"

echo "==> verifying"
"$PY" - <<'EOF'
import importlib, sys
bad = []
for m in ("numpy", "scipy", "networkx", "torch", "cv2", "pkg_resources", "node2vec",
          "cellmaps_utils", "cellmaps_imagedownloader", "cellmaps_ppidownloader",
          "cellmaps_image_embedding", "cellmaps_ppi_embedding",
          "cellmaps_coembedding", "cellmaps_generate_hierarchy"):
    try:
        mod = importlib.import_module(m)
        print(f"    ok   {m:32s} {getattr(mod, '__version__', '')}")
    except Exception as e:
        bad.append(m)
        print(f"    FAIL {m:32s} {e}")

import numpy, networkx, scipy
for name, got, ok in (
    ("numpy",    numpy.__version__,    numpy.__version__.startswith("1.")),
    ("networkx", networkx.__version__, networkx.__version__.startswith("2.8")),
    ("scipy",    scipy.__version__,    tuple(map(int, scipy.__version__.split(".")[:2])) < (1, 13)),
):
    if not ok:
        print(f"    FAIL {name} {got} violates the cellmaps_* cap")
        bad.append(name)

sys.exit(1 if bad else 0)
EOF

cat <<EOF

==> done.
    export CELLMAPS_ROOT="$CELLMAPS_ROOT"
    export CELLMAPS_PYTHON="$PY"

    sbatch rivanna/run.slurm
EOF
