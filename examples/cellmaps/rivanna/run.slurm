#!/bin/bash
# Nextflow head job. Submits one SLURM job per process; must outlive them all.
#   sbatch rivanna/run.slurm          # full
#   sbatch rivanna/run.slurm smoke    # fake images
#
#SBATCH --job-name=cellmaps-nf
#SBATCH --account=clarklab
#SBATCH --partition=standard
#SBATCH --cpus-per-task=1
#SBATCH --mem=4G
#SBATCH --time=2-00:00:00
#SBATCH --output=cellmaps-nf-%j.log

set -euo pipefail

export CELLMAPS_ROOT="${CELLMAPS_ROOT:-/project/clarklab/nf/cellmap}"
export CELLMAPS_PYTHON="${CELLMAPS_PYTHON:-$CELLMAPS_ROOT/env/bin/python}"

PROFILE="rivanna"
[ "${1:-}" = "smoke" ] && PROFILE="rivanna,smoke"

# not $0: SLURM runs a spool copy, so $0 points at an unwritable system dir
PIPELINE_DIR="${PIPELINE_DIR:-${SLURM_SUBMIT_DIR:-$PWD}}"
[ -f "$PIPELINE_DIR/main.nf" ] || PIPELINE_DIR="$CELLMAPS_ROOT/cellmaps"
cd "$PIPELINE_DIR"

module purge
module load nextflow/25.10.4

echo "profile=$PROFILE  cwd=$PWD  python=$CELLMAPS_PYTHON"

[ -f main.nf ] || { echo "no main.nf in $PWD"; exit 1; }
[ -w . ]       || { echo "cannot write to $PWD"; exit 1; }
[ -x "$CELLMAPS_PYTHON" ] || { echo "no interpreter at $CELLMAPS_PYTHON — run rivanna/setup.sh"; exit 1; }

nextflow run main.nf \
    -c rivanna/rivanna.config \
    -profile "$PROFILE" \
    -plugins nf-fairscape@0.1.0 \
    -resume \
    -with-report  "cellmaps-report-${SLURM_JOB_ID}.html" \
    -with-timeline "cellmaps-timeline-${SLURM_JOB_ID}.html"
