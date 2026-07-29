/*
 * Copyright 2026, FAIRSCAPE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.prov.datasheet

import groovy.transform.CompileDynamic

/**
 * Port of `fairscape-cli augment link-inverses`
 * (fairscape_cli.entailments.inverse.augment_rocrate_with_inverses).
 *
 * The CLI loads EVI.owl with rdflib and SPARQLs for `owl:inverseOf` pairs. The
 * ontology is a fixed, versioned artifact and the query answer is 18 pairs, so
 * this port carries the answer as a table rather than a parser plus a SPARQL
 * engine — no rdflib, no ontology file to ship, and nothing to mock.
 *
 * For every pair (p, q) and every entity that states `p: {"@id": T}`, the entity
 * T gains `q: {"@id": S}`, and vice versa. In practice this is what fills
 * `generated` on a Computation for each file that names it in `generatedBy` —
 * including the files discovered inside a published directory, which the
 * renderer links only in the `generatedBy` direction.
 *
 * Pairs are applied in ascending order of the first property's URI, and entities
 * in `@graph` order, so the augmented crate is byte-stable across runs. (The
 * CLI's order comes from rdflib's SPARQL result set, which is not.)
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class InverseLinker {

    static final String EVI_NAMESPACE = 'https://w3id.org/EVI#'

    /**
     * The `owl:inverseOf` pairs declared in EVI.owl, as the JSON keys they
     * reduce to once the EVI namespace is stripped. Regenerate with:
     *
     *   python3 -c "from rdflib import Graph; g=Graph(); \
     *     g.parse('fairscape-cli/src/fairscape_cli/entailments/evi.xml'); \
     *     print(sorted((str(a),str(b)) for a,b in \
     *       g.query('SELECT ?a ?b WHERE { ?a <http://www.w3.org/2002/07/owl#inverseOf> ?b }')))"
     */
    static final List<List<String>> INVERSE_PAIRS = [
        ['associateFor', 'associatedWith'],
        ['challengedBy', 'challenges'],
        ['containedBy', 'contains'],
        ['created', 'createdBy'],
        ['datasetUsedBy', 'usedDataset'],
        ['derivedFrom', 'derivedTo'],
        ['describedBy', 'describes'],
        ['directlyChallengedBy', 'directlyChallenges'],
        ['directlySupportedBy', 'directlySupports'],
        ['distributedFrom', 'hasDistribution'],
        ['generated', 'generatedBy'],
        ['indirectlyChallengedBy', 'indirectlyChallenges'],
        ['packagedBy', 'packages'],
        ['representedBy', 'represents'],
        ['serviceUsedBy', 'usedService'],
        ['softwareUsedBy', 'usedSoftware'],
        ['supportedBy', 'supports'],
        ['used', 'usedBy'],
    ].asImmutable() as List<List<String>>

    /**
     * Add the missing half of every inverse relationship stated in the graph,
     * mutating the entities in place.
     *
     * @param graph the crate's `@graph` list
     * @return how many entities gained a link (the CLI's `modified_count`)
     */
    static int link(List<Map> graph) {
        // keyed by @id like the CLI's entity_map, so a duplicated id resolves to
        // the last entity that claimed it while keeping its first position
        final index = [:] as Map<String,Map>
        for( final entity : graph ) {
            final id = entity?.get('@id')
            if( id != null )
                index.put(id as String, entity)
        }

        int modified = 0
        for( final pair : INVERSE_PAIRS ) {
            final forward = pair[0]
            final backward = pair[1]
            for( final source : new ArrayList<Map>(index.values()) ) {
                // both directions per entity, so a crate that states only one
                // side of a pair is completed regardless of which side it chose
                modified += apply(source, index, forward, backward)
                modified += apply(source, index, backward, forward)
            }
        }
        return modified
    }

    /**
     * For every `sourceKey: {"@id": target}` on this entity, ensure the target
     * entity carries `targetKey: {"@id": source}`.
     */
    private static int apply(Map source, Map<String,Map> index, String sourceKey, String targetKey) {
        final sourceId = source?.get('@id')
        if( sourceId == null || !source.containsKey(sourceKey) )
            return 0

        int modified = 0
        for( final ref : CrateJson.asList(source.get(sourceKey)) ) {
            // only `{"@id": ...}` references are followed; a bare string is a
            // literal (an author name, say), not an edge into the graph
            if( !(ref instanceof Map) )
                continue
            final targetId = ((Map) ref).get('@id')
            if( targetId == null )
                continue
            final target = index.get(targetId)
            // a reference to something outside the crate has nothing to link back
            if( target == null || target.is(source) )
                continue
            if( addLink(target, targetKey, sourceId as String) )
                modified++
        }
        return modified
    }

    /**
     * Port of `add_or_update_json_link`: attach `{"@id": id}` under `key`,
     * promoting a single object to a list and never duplicating an id.
     *
     * @return true when the entity actually changed
     */
    static boolean addLink(Map entity, String key, String id) {
        final link = ['@id': id]
        final current = entity.get(key)

        if( !entity.containsKey(key) || current == null ) {
            entity.put(key, [link])
            return true
        }
        if( current instanceof Map ) {
            if( ((Map) current).get('@id') == id )
                return false
            entity.put(key, [current, link])
            return true
        }
        if( current instanceof List ) {
            for( final item : (List) current )
                if( item instanceof Map && ((Map) item).get('@id') == id )
                    return false
            ((List) current).add(link)
            return true
        }
        // a scalar where a reference was expected: the CLI replaces it outright
        entity.put(key, [link])
        return true
    }
}
