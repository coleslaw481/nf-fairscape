#!/usr/bin/env python3
"""Validate an nf-fairscape crate against the fairscape_models schema.

Usage: python validate_crate.py <path/to/ro-crate-metadata.json>
"""
import json
import sys

from fairscape_models.rocrate import ROCrateV1_2


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__.strip())

    with open(sys.argv[1]) as f:
        metadata = json.load(f)

    crate = ROCrateV1_2.model_validate(metadata)

    # ROCrateV1_2 accepts any graph its member models accept, so it does not by
    # itself say WHICH profile the crate claims. Check the two declarations that
    # make it an RO-Crate 1.2 and a FAIRSCAPE crate rather than generic JSON-LD.
    graph = json.load(open(sys.argv[1]))['@graph']
    descriptor = next((n for n in graph if n['@id'] == 'ro-crate-metadata.json'), None)
    if descriptor is None:
        sys.exit("no ro-crate-metadata.json descriptor entity in the @graph")
    conforms = descriptor.get('conformsTo') or {}
    conforms = conforms.get('@id') if isinstance(conforms, dict) else conforms
    if conforms != 'https://w3id.org/ro/crate/1.2':
        sys.exit(f"descriptor conformsTo is {conforms!r}, expected the RO-Crate 1.2 profile")

    root = next((n for n in graph if 'ROCrate' in str(n.get('@type'))), None)
    if root is None:
        sys.exit("no root ROCrate entity in the @graph")
    if descriptor.get('about', {}).get('@id') != root['@id']:
        sys.exit(f"descriptor is about {descriptor.get('about')}, not the root {root['@id']}")

    # referential integrity: every @id reference must resolve within the graph
    ids = {node['@id'] for node in graph}
    dangling = []
    for node in graph:
        for key in ('hasPart', 'usedDataset', 'usedSoftware', 'generated', 'generatedBy', 'isPartOf', 'about'):
            refs = node.get(key) or []
            refs = refs if isinstance(refs, list) else [refs]
            for ref in refs:
                if isinstance(ref, dict) and ref.get('@id', '').startswith('ark:') and ref['@id'] not in ids:
                    dangling.append(f"{node['@id']} .{key} -> {ref['@id']}")
    if dangling:
        sys.exit("DANGLING REFERENCES:\n  " + "\n  ".join(dangling))

    counts = {}
    for elem in crate.metadataGraph:
        counts[type(elem).__name__] = counts.get(type(elem).__name__, 0) + 1
    print(f"VALID: {sys.argv[1]}")
    for name, count in sorted(counts.items()):
        print(f"  {name}: {count}")


if __name__ == '__main__':
    main()
