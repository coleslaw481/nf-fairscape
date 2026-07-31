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

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

import groovy.transform.CompileDynamic

/**
 * Groovy port of `fairscape build evidence-graph`: the BFS + condensation +
 * projection pipeline in fairscape_graph_tools.evidence_graph_builder and
 * pipeline/evidence_graph.py, backed by the on-disk RO-Crate index that the
 * CLI's LocalGraphSource provides.
 *
 * Writes provenance-graph.json (and, via {@link EvidenceGraphHtml}, a
 * self-contained provenance-graph.html) next to the crate, then records the
 * viewer on the crate root as `localEvidenceGraph` exactly as the CLI does.
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class EvidenceGraphBuilder {

    private static final Pattern ARK = ~/^ark:\/?(\d+)\/(.*)$/

    private final Map<String,Map> index = [:]

    private final int condenseThreshold

    /**
     * Index the crate's entities by @id, skipping the metadata descriptor —
     * the same view of a crate the CLI's LocalGraphSource builds. Nodes are
     * deep-copied because condensation rewrites the ones it collapses.
     */
    EvidenceGraphBuilder(List<Map> graph, int condenseThreshold = 5) {
        this.condenseThreshold = condenseThreshold
        for( final node : graph ) {
            final id = node['@id'] as String
            if( !id || id == 'ro-crate-metadata.json' )
                continue
            if( !index.containsKey(id) )
                index[id] = CrateJson.deepCopy(node) as Map
        }
    }

    /** find_entity: exact match, then a dash-tolerant ARK match. */
    Map findEntity(String arkId) {
        if( index.containsKey(arkId) )
            return index[arkId]

        final matcher = ARK.matcher(arkId)
        if( !matcher.matches() )
            return null
        final naan = matcher.group(1)
        final stripped = matcher.group(2).replace('-', '')
        final fuzzy = stripped.toCharArray().collect { Pattern.quote(String.valueOf(it)) }.join('-?')
        final pattern = Pattern.compile("^ark:${naan}/${fuzzy}\$")
        return index.find { id, node -> pattern.matcher(id).matches() }?.value
    }

    /** `ark:NAAN/postfix` -> `ark:NAAN/evidence-graph-postfix`. */
    static String evidenceGraphId(String nodeId) {
        final matcher = ARK.matcher(nodeId)
        return matcher.matches()
            ? "ark:${matcher.group(1)}/evidence-graph-${matcher.group(2)}".toString()
            : "${nodeId}-evidence-graph".toString()
    }

    /**
     * Build the evidence graph rooted at `nodeId`. Field order matches the
     * pydantic EvidenceGraph dump the CLI writes.
     */
    Map build(String nodeId, String name = null, String description = null) {
        final startNode = index[nodeId]

        final graph = [
            '@type'      : 'evi:EvidenceGraph',
            '@id'        : evidenceGraphId(nodeId),
            'owner'      : nodeId,
            'description': description ?: "Automatically generated Evidence Graph for node ${nodeId}".toString(),
            'name'       : name ?: "Evidence Graph for ${nodeId}".toString(),
        ]

        final graphDict = [:] as Map<String,Map>
        final outputNodes = []

        if( startNode == null ) {
            outputNodes << ['@id': nodeId]
            graphDict[nodeId] = ['@id': nodeId, 'error': 'not found']
            graph['outputs'] = outputNodes
            graph['@graph'] = graphDict
            return graph
        }

        final nodeCache = [(nodeId): startNode] as Map<String,Map>

        String startRoCrateId = null
        List startRoCrateOutputs = null
        if( CrateJson.isRoCrate(startNode['@type']) ) {
            startRoCrateOutputs = new ArrayList(roCrateOutputs(startNode))
            final traversal = new ArrayList(startRoCrateOutputs)
            traversal << ['@id': nodeId]
            startRoCrateId = nodeId
            for( final ref : traversal )
                if( ref['@id'] )
                    outputNodes << ['@id': ref['@id']]
        }
        else {
            outputNodes << ['@id': nodeId]
        }

        // breadth-first walk over every reference reachable from the outputs
        Set<String> currentLevel = outputNodes.collect { it['@id'] as String } as Set
        final processed = new LinkedHashSet<String>()

        while( currentLevel ) {
            final toFetch = new LinkedHashSet(currentLevel)
            toFetch.removeAll(processed)
            if( !toFetch )
                break

            for( final id : toFetch )
                if( !nodeCache.containsKey(id) )
                    nodeCache[id] = index.containsKey(id) ? index[id] : ['@id': id, 'error': 'not found']

            final nextLevel = new LinkedHashSet<String>()
            for( final id : toFetch ) {
                if( !processed.add(id) )
                    continue
                final node = nodeCache[id]
                if( node != null && !node.containsKey('error') )
                    nextLevel.addAll(referencedIds(node))
            }
            currentLevel = nextLevel
        }

        graph['outputs'] = outputNodes
        final stats = GraphCondenser.condense(nodeCache, condenseThreshold)

        for( final output : outputNodes )
            projectNode(output['@id'] as String, nodeCache, graphDict, startRoCrateId, startRoCrateOutputs)

        graph['@graph'] = graphDict
        graph['condensation_stats'] = stats
        return graph
    }

    /** Write the JSON sidecar; returns the path. */
    static Path write(Map evidenceGraph, Path outputFile) {
        if( outputFile.parent != null )
            Files.createDirectories(outputFile.parent)
        Files.write(outputFile, PyJson.dumps(evidenceGraph).getBytes('UTF-8'))
        return outputFile
    }

    // ------------------------------------------------------------------
    // traversal helpers (pipeline/evidence_graph.py)
    // ------------------------------------------------------------------

    private static List roCrateOutputs(Map node) {
        for( final field : ['https://w3id.org/EVI#outputs', 'EVI:outputs', 'outputs'] ) {
            if( node.containsKey(field) ) {
                final outputs = node[field]
                if( outputs instanceof List )
                    return (List) outputs
                if( outputs instanceof Map )
                    return [outputs]
            }
        }
        return []
    }

    /** The EVI type that drives which edges a node contributes. */
    private static String edgeType(Map node) {
        final raw = node['@type']
        if( raw instanceof List ) {
            final types = (List) raw
            for( final candidate : ['Dataset', 'Computation', 'Sample', 'Software', 'MLModel', 'Experiment', 'Activity'] )
                if( candidate in types )
                    return candidate
            return types ? String.valueOf(types.last()) : ''
        }
        return raw == null ? '' : String.valueOf(raw)
    }

    private static boolean isEntityLike(String type) {
        return type.contains('Dataset') || type.contains('Sample') || type.contains('Instrument') ||
               type.contains('Software') || type.contains('MLModel')
    }

    private static boolean isActivityLike(String type) {
        return type.contains('Computation') || type.contains('Experiment') ||
               type.contains('Annotation') || type.contains('Activity')
    }

    private static Set<String> referencedIds(Map node) {
        final ids = new LinkedHashSet<String>()
        final type = edgeType(node)

        if( isEntityLike(type) ) {
            final generatedBy = node['generatedBy']
            if( generatedBy instanceof List && generatedBy ) {
                final id = (generatedBy[0] as Map)?.get('@id')
                if( id ) ids << (id as String)
            }
            else if( generatedBy instanceof Map ) {
                final id = generatedBy['@id']
                if( id ) ids << (id as String)
            }
        }
        else if( isActivityLike(type) ) {
            for( final field : ['usedDataset', 'usedSoftware', 'usedSample', 'usedInstrument', 'usedMLModel'] ) {
                for( final item : CrateJson.asList(node[field]) ) {
                    if( item instanceof Map && item['@id'] )
                        ids << (item['@id'] as String)
                }
            }
        }
        return ids
    }

    /**
     * _build_node_from_cache: project one node and everything upstream of it.
     *
     * `graphDict` is only written once a node's whole subtree is projected — the
     * order it ends up in is what the CLI produces, and provenance-graph.json is
     * compared against the CLI byte for byte — so a back-edge would re-enter a
     * node that is still in flight and recurse until the stack dies. `inFlight`
     * stops that without touching the insertion order: on the DAG the renderer
     * emits it never triggers, and on a graph some other tool made cyclic the
     * cycle is simply not followed. {@link GraphCondenser#signature} guards
     * itself the same way.
     */
    private static void projectNode(String nodeId, Map nodeCache, Map graphDict,
                                    String startRoCrateId, List roCrateOutputs,
                                    Set<String> inFlight = new HashSet<String>()) {
        if( graphDict.containsKey(nodeId) || !inFlight.add(nodeId) )
            return
        try {
            projectNode0(nodeId, nodeCache, graphDict, startRoCrateId, roCrateOutputs, inFlight)
        }
        finally {
            inFlight.remove(nodeId)
        }
    }

    private static void projectNode0(String nodeId, Map nodeCache, Map graphDict,
                                     String startRoCrateId, List roCrateOutputs,
                                     Set<String> inFlight) {

        final node = nodeCache[nodeId] as Map
        if( node == null ) {
            graphDict[nodeId] = ['@id': nodeId, 'error': 'not found']
            return
        }
        if( node.containsKey('error') ) {
            graphDict[nodeId] = node
            return
        }

        final result = [
            '@id'        : node['@id'],
            '@type'      : node['@type'],
            'name'       : node['name'],
            'description': node['description'],
        ]

        if( node['createdBy'] )
            result['createdBy'] = node['createdBy']

        if( startRoCrateId && nodeId == startRoCrateId && CrateJson.isRoCrate(node['@type']) ) {
            if( roCrateOutputs )
                result['hasOutputs'] = roCrateOutputs
        }

        final type = edgeType(node)

        if( isEntityLike(type) ) {
            final generatedBy = node['generatedBy']
            if( generatedBy ) {
                String compId = null
                if( generatedBy instanceof List && generatedBy )
                    compId = (generatedBy[0] as Map)?.get('@id')
                else if( generatedBy instanceof Map )
                    compId = generatedBy['@id']

                if( compId ) {
                    projectNode(compId, nodeCache, graphDict, startRoCrateId, roCrateOutputs, inFlight)
                    result['generatedBy'] = ['@id': compId]
                }
                else {
                    result['generatedBy'] = generatedBy
                }
            }
        }
        else if( isActivityLike(type) ) {
            final usedDataset = node['usedDataset']
            if( usedDataset ) {
                final refs = resolveUsedDatasets(usedDataset, nodeCache)
                if( refs ) {
                    result['usedDataset'] = refs
                    for( final ref : refs )
                        projectNode(ref['@id'] as String, nodeCache, graphDict, startRoCrateId, roCrateOutputs, inFlight)
                }
            }

            for( final field : ['usedSoftware', 'usedSample', 'usedInstrument', 'usedMLModel'] ) {
                final value = node[field]
                if( !value )
                    continue
                final refs = []
                for( final item : CrateJson.asList(value) ) {
                    final id = (item instanceof Map) ? item['@id'] : null
                    if( id ) {
                        projectNode(id as String, nodeCache, graphDict, startRoCrateId, roCrateOutputs, inFlight)
                        refs << ['@id': id]
                    }
                }
                if( refs )
                    result[field] = refs
            }
        }

        // DatasetGroup summaries keep their stats and pull in the representative
        if( node['@type'] instanceof List && ((List) node['@type']).any { String.valueOf(it).contains('DatasetGroup') } ) {
            for( final field : ['evi:memberCount', 'evi:representativeDataset', 'evi:commonFormat',
                                'evi:commonSoftware', 'format', 'evi:memberIds'] ) {
                if( node.containsKey(field) )
                    result[field] = node[field]
            }
            final rep = node['evi:representativeDataset']
            final repId = rep instanceof Map ? rep['@id'] : (rep instanceof CharSequence ? rep.toString() : null)
            if( repId )
                projectNode(repId as String, nodeCache, graphDict, startRoCrateId, roCrateOutputs, inFlight)
        }

        graphDict[nodeId] = result
    }

    /** An input that is itself an RO-Crate contributes its outputs instead. */
    private static List resolveUsedDatasets(Object usedDataset, Map nodeCache) {
        final refs = []
        for( final ref : CrateJson.asList(usedDataset) ) {
            if( !(ref instanceof Map) || !ref['@id'] )
                continue
            final datasetId = ref['@id'] as String
            final node = nodeCache[datasetId] as Map

            if( node != null && !node.containsKey('error') && CrateJson.isRoCrate(node['@type']) ) {
                final outputs = roCrateOutputs(node)
                if( outputs ) {
                    for( final output : outputs )
                        if( output['@id'] )
                            refs << ['@id': output['@id']]
                }
                else {
                    refs << ['@id': datasetId]
                }
            }
            else {
                refs << ['@id': datasetId]
            }
        }
        return refs
    }
}
