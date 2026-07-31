#!/usr/bin/env bash
#
# Run a released nf-core pipeline under nf-fairscape and check the crate it emits.
#
#   ./run.sh demo 1.2.0                     # any pipeline/revision
#   ./run.sh                                # the whole regression set, in order
#   NF=/path/to/nextflow ./run.sh pairgenomealign 3.0.3    # needs Nextflow >= 26.04
#
# Each pipeline gets its own directory here, with the crate and everything it describes in
# <pipeline>/results/ — the same layout as every other example, and git-ignored the same way.
# Add -resume as a trailing argument to reuse a previous run's work directory; the crate is
# re-rendered either way, which is what you want after changing the plugin.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
PLUGIN=$(cd "$HERE/../.." && pwd)
NF=${NF:-nextflow}
MODELS=${FAIRSCAPE_MODELS:-$PLUGIN/../../../fairscape_models}

# Docker refuses to pull when config.json names a credential helper that isn't installed
# (`credsStore: desktop` on a machine with no Docker Desktop). Point Docker at an empty
# config for this run rather than editing the user's.
if [[ -z ${DOCKER_CONFIG:-} ]]; then
    helper=$(python3 -c "
import json, pathlib
p = pathlib.Path.home() / '.docker' / 'config.json'
print(json.loads(p.read_text()).get('credsStore', '') if p.exists() else '')" 2>/dev/null)
    if [[ -n $helper ]] && ! command -v "docker-credential-$helper" > /dev/null; then
        export DOCKER_CONFIG="$HERE/.docker"
        mkdir -p "$DOCKER_CONFIG"
        echo '{}' > "$DOCKER_CONFIG/config.json"
    fi
fi

# The regression set: small test profiles, each stressing something different.
# Keep in sync with README.md and the "Hardened against real nf-core pipelines" section
# of CLAUDE.md.
SUITE=(
    "demo 1.2.0"                    # smoke test: 3 modules, MultiQC directory publishing
    "phyloplace 2.0.1"              # 15 modules of real tools, cheap
    "pairgenomealign 3.0.3"         # heaviest module aliasing; needs Nextflow >= 26.04
    "differentialabundance 2.0.0"   # topic-channel versions, R/Quarto templates, 46 tables
    "seqinspector 1.1.0"            # scale: ~1100 Datasets, 150 MB of output
)

run_one() {
    local pipeline=$1 revision=$2; shift 2
    local out="$HERE/$pipeline"
    mkdir -p "$out"

    echo "=== nf-core/$pipeline $revision"
    # The run happens in $out, so Nextflow auto-loads $out/nextflow.config -- which after a
    # previous run is the PIPELINE's own config, copied in below for reading the graph
    # against. It includes conf/base.config, which was not copied, so every re-run died on
    # a missing file. Clear the reference copies first; they are rewritten on success.
    rm -f "$out/main.nf" "$out/nextflow.config"
    ( cd "$out" && NXF_ANSI_LOG=false "$NF" -log "$out/.nextflow.log" \
        run "nf-core/$pipeline" -r "$revision" \
        -profile test,docker --outdir results \
        -c "$HERE/fairscape.config" -work-dir "$out/work" "$@" ) > "$out/nf.out" 2>&1
    local status=$?

    if [[ $status -ne 0 ]]; then
        echo "  pipeline FAILED (exit $status) -- last lines of $out/nf.out:"
        tail -15 "$out/nf.out" | sed 's/^/    /'
        return 1
    fi

    # a crate failure never fails a run, so look for one rather than trusting the exit code
    grep -E "WARN.*FAIRSCAPE|Error building the FAIRSCAPE" "$out/.nextflow.log" | sed 's/^/    /'

    # Copy the workflow next to the crate it produced, so the provenance graph can be read
    # against the thing that generated it. Nextflow keeps the fetched pipeline in its assets
    # directory; 26.04 checks it out under .repos/<name>/clones/<sha>/ rather than directly.
    local src=$HOME/.nextflow/assets/nf-core/$pipeline
    [[ -f $src/main.nf ]] || src=$(ls -d "$HOME/.nextflow/assets/.repos/nf-core/$pipeline/clones/"*/ 2>/dev/null | head -1)
    if [[ -f $src/main.nf ]]; then
        cp "$src/main.nf" "$src/nextflow.config" "$out/"
        echo "    workflow: $out/main.nf, $out/nextflow.config"
    fi

    PYTHONPATH=$MODELS python3 "$PLUGIN/nf-fairscape-test/validate_crate.py" \
        "$out/results/ro-crate-metadata.json" 2>&1 | grep -v RequestsDependencyWarning \
        | grep -v warnings.warn | sed 's/^/    /'
    python3 "$HERE/check-crate.py" "$out/results/ro-crate-metadata.json" | sed 's/^/    /'
}

if [[ $# -ge 2 ]]; then
    run_one "$@"
else
    for entry in "${SUITE[@]}"; do
        # shellcheck disable=SC2086
        run_one $entry "$@"
        echo
    done
fi
