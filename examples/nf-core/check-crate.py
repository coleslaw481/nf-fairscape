#!/usr/bin/env python3
"""Check the properties a crate from a real nf-core pipeline has to have.

`nf-fairscape-test/validate_crate.py` answers "is this a valid EVI RO-Crate" — it is the
acceptance oracle and this does not replace it. This answers the questions that only have
wrong answers once a *real* pipeline is on the other end, each of which was a bug found by
running one:

  * the provenance graph is a DAG            (a cyclic crate used to kill the evidence graph)
  * the evidence graph was actually written  (it failed silently, the crate stayed valid)
  * `author` is not whoever ran the pipeline (nf-core uses manifest.contributors now)
  * process Software carries a tool version, not the pipeline's release
  * no schema is described by a comment line (MultiQC tables open with `# id: '...'`)
  * ARK slugs are distinct enough to read    (qualified nf-core names hit the 40-char cap)

Usage:  check-crate.py <ro-crate-metadata.json> [...]
Exit:   0 when every crate passes, 1 otherwise.
"""
import getpass
import json
import os
import sys
from collections import Counter

ENTITY_LIKE = ("Dataset", "Sample", "Instrument", "Software", "MLModel")
ACTIVITY_LIKE = ("Computation", "Experiment", "Annotation", "Activity")
USED_FIELDS = ("usedDataset", "usedSoftware", "usedSample", "usedInstrument", "usedMLModel")


def evi_type(node):
    """The EVI class of a node. `@type` holds full URIs (`https://w3id.org/EVI#Dataset`)
    alongside prov aliases, so match on the suffix rather than on equality."""
    raw = node.get("@type")
    types = [str(t) for t in (raw if isinstance(raw, list) else [raw])]
    for candidate in ("ROCrate", "Container", "Schema", "Computation", "Software",
                      "Dataset", "Sample", "MLModel", "Experiment", "Activity"):
        if any(t.rsplit("#", 1)[-1].rsplit(":", 1)[-1] == candidate for t in types):
            return candidate
    return types[-1] if types else ""


def as_list(value):
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def edges(node):
    """The references the evidence-graph walk follows out of this node."""
    kind = evi_type(node)
    out = []
    if any(k in kind for k in ENTITY_LIKE):
        for ref in as_list(node.get("generatedBy"))[:1]:
            if isinstance(ref, dict) and ref.get("@id"):
                out.append(ref["@id"])
    elif any(k in kind for k in ACTIVITY_LIKE):
        for field in USED_FIELDS:
            for ref in as_list(node.get(field)):
                if isinstance(ref, dict) and ref.get("@id"):
                    out.append(ref["@id"])
    return out


def find_cycle(index):
    """One cycle as a list of @ids, or None. Iterative: a cyclic crate can be deep."""
    UNVISITED, ACTIVE, DONE = 0, 1, 2
    state = {}
    for start in index:
        if state.get(start, UNVISITED) != UNVISITED:
            continue
        stack = [(start, iter(edges(index[start])))]
        state[start] = ACTIVE
        path = [start]
        while stack:
            node, children = stack[-1]
            advanced = False
            for child in children:
                if child not in index:
                    continue
                if state.get(child, UNVISITED) == ACTIVE:
                    return path[path.index(child):] + [child]
                if state.get(child, UNVISITED) == UNVISITED:
                    state[child] = ACTIVE
                    path.append(child)
                    stack.append((child, iter(edges(index[child]))))
                    advanced = True
                    break
            if not advanced:
                state[node] = DONE
                path.pop()
                stack.pop()
    return None


def check(path):
    with open(path) as handle:
        graph = json.load(handle)["@graph"]
    index = {e["@id"]: e for e in graph if "@id" in e}
    root = next((e for e in graph if "ROCrate" in str(e.get("@type"))), None)
    crate_dir = os.path.dirname(os.path.abspath(path))
    problems, notes = [], []

    if root is None:
        return [f"{path}: no root RO-Crate entity"], []

    counts = Counter(evi_type(e) for e in graph if "@id" in e)
    manifest_version = root.get("version")
    notes.append(
        f"{counts['Computation']} Computations, {counts['Dataset']} Datasets, "
        f"{counts['Software']} Software, {counts['Schema']} Schemas, "
        f"{counts['Container']} Containers, "
        f"{len(as_list(root.get('https://w3id.org/EVI#outputs')))} root outputs")

    cycle = find_cycle(index)
    if cycle:
        names = " -> ".join(str(index[i].get("name"))[:40] for i in cycle)
        problems.append(f"provenance graph is cyclic: {names}")

    graph_json = os.path.join(crate_dir, "provenance-graph.json")
    if root.get("localEvidenceGraph") and not os.path.exists(graph_json):
        problems.append("root points at an evidence graph that was never written")
    elif os.path.exists(graph_json):
        with open(graph_json) as handle:
            notes.append(f"evidence graph: {len(json.load(handle)['@graph'])} nodes")

    author = str(root.get("author") or "")
    if author in (getpass.getuser(), "Unknown", ""):
        problems.append(f"author is {author!r} -- manifest.contributors was not read")

    # only meaningful when the run reported versions somewhere: a pipeline of shell
    # one-liners has none to find, and the manifest fallback is then the right answer
    reports_versions = any(
        "versions" in str(e.get("name", "")).lower() and str(e.get("name", "")).endswith((".yml", ".yaml"))
        for e in graph if evi_type(e) == "Dataset")
    processes = [e for e in graph
                 if evi_type(e) == "Software" and e.get("isPartOf")]
    stale = [e["name"].split(":")[-1] for e in processes if e.get("version") == manifest_version]
    if reports_versions and processes and len(stale) == len(processes):
        problems.append(
            f"every process Software carries the pipeline version {manifest_version!r} -- "
            "the run reported tool versions and none was read")
    elif stale:
        # MultiQC legitimately cannot appear in a file it is given as input
        notes.append(f"process(es) on the manifest version: {', '.join(stale)}"
                     + ("" if reports_versions else " (this run reports no tool versions)"))

    for schema in (e for e in graph if evi_type(e) == "Schema"):
        columns = list(schema.get("properties") or {})
        if columns and str(columns[0]).startswith("#"):
            problems.append(
                f"schema for {schema.get('name')!r} is headed by a comment: {columns[0]!r}")

    stems = Counter(e["@id"].rsplit("-", 1)[0] for e in processes)
    collided = {stem: n for stem, n in stems.items() if n > 1}
    if collided:
        problems.append(
            f"process ARK slugs collide, {sum(collided.values())} entities over "
            f"{len(collided)} stem(s): {', '.join(list(collided)[:2])}")

    return problems, notes


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    failed = False
    for path in sys.argv[1:]:
        problems, notes = check(path)
        for note in notes:
            print(f"  {note}")
        for problem in problems:
            print(f"  FAIL: {problem}")
        if problems:
            failed = True
        else:
            print("  OK: acyclic, attributed, versioned, readable ids")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
