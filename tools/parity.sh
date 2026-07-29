#!/usr/bin/env bash
# Diff the Groovy datasheet/evidence-graph port against fairscape-cli's Python
# implementation. This is the acceptance oracle for src/main/groovy/nextflow/prov/datasheet.
#
#   tools/parity.sh <crate dir>       e.g. tools/parity.sh examples/letters-chain/results
#
# Requires fairscape-cli importable by the local python3 (python3 -m fairscape_cli).
# Expected result: provenance-graph.json, provenance-graph.html and
# ai_ready_score.json are IDENTICAL; ro-crate-datasheet.html differs only by the
# summary stat cards -- see "Deviations" in docs/FAIRSCAPE.md.
#
# Stage 0: link-inverses   (same crate JSON in, compare the augmented graph)
# Stage 1: evidence graph  (same crate JSON in, compare provenance-graph.json)
# Stage 2: linkml + datasheet (same directory contents in, compare YAML + HTML + score)
set -uo pipefail

PLUGIN=$(cd "$(dirname "$0")/.." && pwd)
SRC=${1:?usage: parity.sh <crate dir>}
WORK=${WORK:-$PLUGIN/build/parity}
PY=$WORK/py
GR=$WORK/gr

# a CLI stage that fails must kill the run: diffing against stale leftovers can
# report IDENTICAL for a stage that never executed (grep -v in the pipeline can
# legitimately exit 1 on empty output, so check the CLI's own status)
cli_ok() {
  [ "${PIPESTATUS[0]}" -eq 0 ] || { echo "FAILED: $1 (see output above)"; exit 1; }
}

# record what the byte-parity claim is being made against
echo "=== reference versions ==="
python3 -m pip show fairscape-cli pyyaml frictionless 2>/dev/null | grep -iE '^(name|version):' || true
echo

rm -rf "$WORK"; mkdir -p "$PY" "$GR"
# the full payload, whatever its extensions (an allow-list once silently dropped
# the .tsv the test pipeline publishes, skewing the directory-size stat cards);
# generated artifacts are excluded so both sides re-create them
for f in "$SRC"/*; do
  case "$(basename "$f")" in
    ro-crate-metadata.json|provenance-graph.*|ro-crate-datasheet.html|ro-crate-linkml.yaml|ai_ready_score.json) ;;
    *) cp -r "$f" "$PY/" ;;
  esac
done
cp "$SRC"/ro-crate-metadata.json "$PY/"

# --- Stage 0: inverse entailment (same crate JSON in, augmented crate out) ----
# Formatting differs by construction (the CLI writes json.dump(indent=2) through
# prune_none, we write JsonOutput.prettyPrint), so compare the parsed graphs.
INV=$WORK/inv
rm -rf "$INV/py" "$INV/gr"; mkdir -p "$INV/py" "$INV/gr"
cp "$PY/ro-crate-metadata.json" "$INV/py/"
cp "$PY/ro-crate-metadata.json" "$INV/gr/"

python3 -m fairscape_cli augment link-inverses "$INV/py" 2>&1 | grep -vE 'RequestsDependencyWarning|warnings.warn' | sed 's/^/  py| /'
cli_ok "fairscape-cli augment link-inverses"
( cd "$PLUGIN" && ./gradlew test --tests '*CrateArtifactsTest*' -DparityDir="$INV/gr" -DparityStep=inverses -q ) || exit 1

echo
echo "=== link-inverses (parsed graph) ==="
python3 - "$INV/py/ro-crate-metadata.json" "$INV/gr/ro-crate-metadata.json" <<'PYEOF'
import json, sys
a = json.load(open(sys.argv[1]))["@graph"]
b = json.load(open(sys.argv[2]))["@graph"]
ai = {e["@id"]: e for e in a}
bi = {e["@id"]: e for e in b}
problems = []
if set(ai) != set(bi):
    problems.append("entity id sets differ")
for k in sorted(set(ai) & set(bi)):
    ea, eb = ai[k], bi[k]
    if set(ea) != set(eb):
        problems.append(f"{k}: keys py-only={sorted(set(ea)-set(eb))} gr-only={sorted(set(eb)-set(ea))}")
        continue
    for prop in ea:
        if json.dumps(ea[prop], sort_keys=True) != json.dumps(eb[prop], sort_keys=True):
            problems.append(f"{k}.{prop}: py={str(ea[prop])[:70]} gr={str(eb[prop])[:70]}")
print("IDENTICAL (%d entities)" % len(ai) if not problems else "\n".join(problems[:20]))
PYEOF

# --- Groovy: produce the crate with EVI:inputs/outputs + evidence graph -------
cp -r "$PY"/. "$GR/"
( cd "$PLUGIN" && ./gradlew test --tests '*CrateArtifactsTest*' -DparityDir="$GR" -DparityStep=graph -q ) || exit 1

# --- Python: same crate JSON in, evidence graph out --------------------------
cp "$GR/ro-crate-metadata.json" "$PY/ro-crate-metadata.json"
python3 - "$PY" <<'EOF'
import json, sys, pathlib
# strip the pointer the Groovy run added so the CLI writes its own
p = pathlib.Path(sys.argv[1]) / "ro-crate-metadata.json"
d = json.loads(p.read_text())
for e in d["@graph"]:
    e.pop("localEvidenceGraph", None)
p.write_text(json.dumps(d, indent=4))
EOF
[ $? -eq 0 ] || { echo "FAILED: stripping localEvidenceGraph"; exit 1; }
ROOT_ARK=$(python3 -c "
import json,sys
d=json.load(open('$PY/ro-crate-metadata.json'))
print(next(e['@id'] for e in d['@graph'] if 'ROCrate' in str(e.get('@type'))))")
python3 -m fairscape_cli build evidence-graph "$PY" "$ROOT_ARK" 2>&1 | grep -vE 'RequestsDependencyWarning|warnings.warn' | sed 's/^/  py| /'
cli_ok "fairscape-cli build evidence-graph"

echo
echo "=== provenance-graph.json ==="
if diff -q "$PY/provenance-graph.json" "$GR/provenance-graph.json" >/dev/null; then
  echo "IDENTICAL ($(wc -c < "$GR/provenance-graph.json") bytes)"
else
  diff "$PY/provenance-graph.json" "$GR/provenance-graph.json" | head -40
fi

echo
echo "=== provenance-graph.html ==="
if diff -q "$PY/provenance-graph.html" "$GR/provenance-graph.html" >/dev/null; then
  echo "IDENTICAL ($(wc -c < "$GR/provenance-graph.html") bytes)"
else
  echo "sizes: py=$(wc -c < "$PY/provenance-graph.html") gr=$(wc -c < "$GR/provenance-graph.html")"
  cmp "$PY/provenance-graph.html" "$GR/provenance-graph.html" | head -5
fi

# --- Stage 2: datasheet ------------------------------------------------------
# Both sides must see identical directory contents (the datasheet embeds the
# directory size), so start each from the same file set.
rm -rf "$WORK/ds-py" "$WORK/ds-gr"; mkdir -p "$WORK/ds-py" "$WORK/ds-gr"
for f in "$GR"/*; do
  case "$(basename "$f")" in
    ro-crate-datasheet.html|ro-crate-linkml.yaml|ai_ready_score.json) ;;
    *) cp -r "$f" "$WORK/ds-py/"; cp -r "$f" "$WORK/ds-gr/" ;;
  esac
done

python3 -m fairscape_cli build datasheet "$WORK/ds-py" 2>&1 | grep -vE 'RequestsDependencyWarning|warnings.warn' | sed 's/^/  py| /'
cli_ok "fairscape-cli build datasheet"
# `build datasheet` writes the LinkML sidecar before measuring the directory, so
# the Groovy side has to write its own first for both to measure the same bytes
( cd "$PLUGIN" && ./gradlew test --tests '*CrateArtifactsTest*' -DparityDir="$WORK/ds-gr" -DparityStep=linkml -q ) || exit 1

echo
echo "=== ro-crate-linkml.yaml ==="
if diff -q "$WORK/ds-py/ro-crate-linkml.yaml" "$WORK/ds-gr/ro-crate-linkml.yaml" >/dev/null; then
  echo "IDENTICAL ($(wc -c < "$WORK/ds-gr/ro-crate-linkml.yaml") bytes)"
else
  diff "$WORK/ds-py/ro-crate-linkml.yaml" "$WORK/ds-gr/ro-crate-linkml.yaml" | head -40
fi

( cd "$PLUGIN" && ./gradlew test --tests '*CrateArtifactsTest*' -DparityDir="$WORK/ds-gr" -DparityStep=datasheet -q ) || exit 1

echo
echo "=== ai_ready_score.json ==="
if diff -q "$WORK/ds-py/ai_ready_score.json" "$WORK/ds-gr/ai_ready_score.json" >/dev/null; then
  echo "IDENTICAL ($(wc -c < "$WORK/ds-gr/ai_ready_score.json") bytes)"
else
  diff "$WORK/ds-py/ai_ready_score.json" "$WORK/ds-gr/ai_ready_score.json" | head -40
fi

echo
echo "=== ro-crate-datasheet.html ==="
if diff -q "$WORK/ds-py/ro-crate-datasheet.html" "$WORK/ds-gr/ro-crate-datasheet.html" >/dev/null; then
  echo "IDENTICAL ($(wc -c < "$WORK/ds-gr/ro-crate-datasheet.html") bytes)"
else
  echo "sizes: py=$(wc -c < "$WORK/ds-py/ro-crate-datasheet.html") gr=$(wc -c < "$WORK/ds-gr/ro-crate-datasheet.html")"
  diff "$WORK/ds-py/ro-crate-datasheet.html" "$WORK/ds-gr/ro-crate-datasheet.html" | head -60
fi
