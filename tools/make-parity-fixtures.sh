#!/usr/bin/env bash
# Regenerate the frozen crate fixtures the parity suite runs against.
#
#   tools/make-parity-fixtures.sh [name ...]     (default: every fixture)
#
# Each fixture is a real crate directory published by one of the example
# pipelines, captured with every DERIVED artifact turned off -- link-inverses,
# evidence graph, LinkML and datasheet are exactly what the parity tests make
# the two implementations produce, so a fixture that already carries them would
# be testing idempotence instead of the port.
#
# Needs nextflow on PATH and the plugin installed (`make install`). The crates
# are committed: ARK suffixes hash the work directory, so a regenerated fixture
# reshuffles every id and the diff is large but meaningless -- only regenerate
# when the renderer's OUTPUT changed, and say so in the commit message.
set -euo pipefail

PLUGIN=$(cd "$(dirname "$0")/.." && pwd)
DEST=$PLUGIN/src/test/resources/parity
VERSION=$(cat "$PLUGIN/VERSION")
SCRATCH=${SCRATCH:-$(mktemp -d)}
mkdir -p "$SCRATCH"

command -v nextflow >/dev/null || { echo "nextflow not on PATH"; exit 1; }

capture() {
  local name=$1 pipeline=$2
  local run=$SCRATCH/$name
  echo "=== $name ==="
  rm -rf "$run"
  # Copy the pipeline and launch from its own directory. Running it in place
  # would drop results/ in the working tree; running it from elsewhere would put
  # BOTH the pipeline's config and the launch directory's in play, and
  # `includeWorkflow` copies every config it is given -- the crate would carry a
  # `nextflow-2.config` no ordinary run of the pipeline produces.
  cp -r "$pipeline" "$run"
  rm -rf "$run/results" "$run/work" "$run"/.nextflow*
  # turn off everything CrateArtifacts derives, so the fixture is the renderer's
  # crate and nothing else -- deriving these is what the parity suite tests
  cat >> "$run/nextflow.config" <<'EOF'

// added by tools/make-parity-fixtures.sh -- the parity suite derives these
fairscape {
    linkInverses  = false
    evidenceGraph = false
    datasheet     = false
    linkml        = false
}
EOF
  ( cd "$run" && nextflow run . -plugins "nf-fairscape@$VERSION" >/dev/null )
  [ -f "$run/results/ro-crate-metadata.json" ] || { echo "no crate published for $name"; exit 1; }
  rm -rf "${DEST:?}/$name"
  mkdir -p "$DEST/$name"
  cp -r "$run/results"/. "$DEST/$name/"
  # `paramInputs` records a file-shaped parameter by its absolute path, which
  # here is this script's scratch directory. Both implementations read the same
  # frozen crate so parity is unaffected, but a committed fixture should not
  # carry the path of whoever last regenerated it.
  sed -i "s#$run#/pipeline#g" "$DEST/$name/ro-crate-metadata.json"
  # a fixture that still carries a derived artifact would be compared against
  # itself rather than rebuilt
  rm -f "$DEST/$name"/{provenance-graph.json,provenance-graph.html,ro-crate-datasheet.html,ro-crate-linkml.yaml,ai_ready_score.json}
  python3 - "$DEST/$name/ro-crate-metadata.json" <<'PYEOF'
import json, sys
graph = json.load(open(sys.argv[1]))["@graph"]
print(f"  {len(graph)} entities -> {sys.argv[1]}")
PYEOF
}

want() { [ $# -eq 0 ] && return 0; for a in "$@"; do [ "$a" = "$FIXTURE" ] && return 0; done; return 1; }

FIXTURE=letters-chain
want "$@" && capture letters-chain "$PLUGIN/examples/letters-chain"

# the enriched crate: published directories expanded into per-file Datasets,
# md5 checksums, directory contentSizes and an inferred EVI:Schema for the tsv
FIXTURE=nf-test
want "$@" && capture nf-test "$PLUGIN/nf-fairscape-test"

# A synthetic crate: eight sibling shards feeding one merge step, the shape that
# trips GraphCondenser's DatasetGroup collapse. No pipeline produces it, so it is
# hand-written rather than captured -- but the CLI reads it like any other crate.
FIXTURE=fanout
if want "$@"; then
  echo "=== fanout ==="
  mkdir -p "$DEST/fanout"
  cp "$PLUGIN/src/test/resources/crates/fanout-crate.json" "$DEST/fanout/ro-crate-metadata.json"
  echo "  hand-written -> $DEST/fanout/ro-crate-metadata.json"
fi

echo
echo "fixtures in $DEST"
