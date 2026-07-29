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
 * Port of fairscape_graph_tools.pipeline.condense.condense_evidence_graph_cache
 * and the graph_utils helpers it depends on.
 *
 * Collapses sibling datasets that share an identical provenance signature into
 * a single DatasetGroup node — the case a scattered Nextflow process hits as
 * soon as it fans out over more than `threshold` inputs.
 *
 * Signatures are compared through a canonical string form. That keeps the
 * grouping identical to Python's tuple comparison; only the *ordering* within
 * `evi:provenanceSignature` can differ from CPython's, which no consumer reads.
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class GraphCondenser {

    private static final Set<String> EVI_TYPES = [
        'Dataset', 'Software', 'MLModel', 'Computation', 'Annotation',
        'Experiment', 'ROCrate', 'CreativeWork', 'Schema',
    ] as Set

    private static final List<String> TYPE_PREFERENCE = [
        'ROCrate', 'Computation', 'Software', 'MLModel', 'Experiment', 'Annotation', 'Schema',
    ]

    // ------------------------------------------------------------------
    // graph_utils
    // ------------------------------------------------------------------

    static List<String> shortTypes(Map node) {
        final raw = CrateJson.asList(node?.get('@type'))
        final shorts = []
        for( final t : raw ) {
            final s = String.valueOf(t)
            final short_ = s.contains('#') ? s.substring(s.lastIndexOf('#') + 1)
                         : (s.contains(':') ? s.substring(s.lastIndexOf(':') + 1) : s)
            if( short_ in EVI_TYPES )
                shorts << short_
        }
        return shorts
    }

    static String eviType(Map node) {
        final shorts = shortTypes(node)
        if( !shorts )
            return null
        for( final preferred : TYPE_PREFERENCE )
            if( preferred in shorts )
                return preferred
        return shorts[0]
    }

    static boolean isDataset(Map node) {
        final types = shortTypes(node)
        return 'Dataset' in types && !('ROCrate' in types)
    }

    static boolean isComputation(Map node) { eviType(node) == 'Computation' }

    static boolean isSoftware(Map node) { eviType(node) == 'Software' }

    static boolean isRoCrateRoot(Map node) { 'ROCrate' in shortTypes(node) }

    /** Collect @id strings out of one or more reference fields. */
    static List<String> idList(Map node, String... fields) {
        final ids = []
        for( final field : fields ) {
            for( final item : CrateJson.asList(node?.get(field)) ) {
                if( item instanceof Map && item['@id'] )
                    ids << (item['@id'] as String)
                else if( item instanceof CharSequence )
                    ids << item.toString()
            }
        }
        return ids
    }

    static List<String> generatedByIds(Map dataset) {
        return idList(dataset, 'generatedBy', 'prov:wasGeneratedBy')
    }

    static Map idRef(String id) { return ['@id': id] }

    // ------------------------------------------------------------------
    // provenance signature
    // ------------------------------------------------------------------

    /** [format, sortedSchemaIds, sortedComputationSignatures|null] */
    static List signature(String datasetId, Map index, Map<String,List> cache) {
        if( cache.containsKey(datasetId) )
            return cache[datasetId]

        final dataset = index[datasetId] as Map
        if( dataset == null ) {
            final sig = ['unknown', [], null]
            cache[datasetId] = sig
            return sig
        }

        // cycle guard: while this dataset's signature is being computed, a
        // back-edge to it resolves to this placeholder instead of recursing
        // forever (the Python port hits RecursionError; here it would be an
        // unrecoverable StackOverflowError). Overwritten with the real
        // signature below; unreachable on an acyclic graph.
        cache[datasetId] = ['cycle', [], null]

        final fmt = (dataset['format'] ?: 'unknown') as String
        final schemaIds = idList(dataset, 'evi:Schema').sort()
        final genCompIds = generatedByIds(dataset)

        List sig
        if( !genCompIds ) {
            sig = [fmt, schemaIds, null]
        }
        else {
            final compSigs = []
            for( final compId : genCompIds.sort() ) {
                final comp = index[compId] as Map
                if( comp == null ) {
                    compSigs << [[], []]
                    continue
                }
                final swIds = idList(comp, 'usedSoftware').sort()
                final inputSigs = idList(comp, 'usedDataset')
                    .collect { signature(it, index, cache) }
                    .sort { repr(it) }
                compSigs << [swIds, inputSigs]
            }
            sig = [fmt, schemaIds, compSigs.sort { repr(it) }]
        }

        cache[datasetId] = sig
        return sig
    }

    /** Python-style repr of a nested signature, used for equality and display. */
    static String repr(Object value) {
        if( value == null )
            return 'None'
        if( value instanceof List ) {
            final items = ((List) value).collect { repr(it) }
            return items.size() == 1 ? "(${items[0]},)" : "(${items.join(', ')})"
        }
        if( value instanceof CharSequence )
            return "'" + value.toString().replace('\\', '\\\\').replace("'", "\\'") + "'"
        return String.valueOf(value)
    }

    // ------------------------------------------------------------------
    // condensation
    // ------------------------------------------------------------------

    /**
     * Collapse repetitive dataset siblings in place and return the stats dict
     * the evidence graph carries as `condensation_stats`.
     */
    static Map condense(Map<String,Map> nodeCache, int threshold = 5, int maxMemberIds = 0) {
        final originalCount = nodeCache.size()
        final Map<String,List> sigCache = [:]
        final allGroupNodes = []
        final totalCollapsed = new LinkedHashSet<String>()

        final compIds = nodeCache.findAll { id, node -> isComputation(node) }*.key

        for( final compId : compIds ) {
            final node = nodeCache[compId]
            if( node == null )
                continue

            final inputDatasetIds = idList(node, 'usedDataset')
                .findAll { nodeCache.containsKey(it) && isDataset(nodeCache[it]) }

            if( inputDatasetIds.size() <= threshold )
                continue

            final sigToIds = new LinkedHashMap<String,List<String>>()
            final sigByKey = new LinkedHashMap<String,List>()
            for( final dsId : inputDatasetIds ) {
                final sig = signature(dsId, nodeCache, sigCache)
                final key = repr(sig)
                sigByKey[key] = sig
                sigToIds.computeIfAbsent(key, { [] }) << dsId
            }

            sigToIds.each { key, memberIds ->
                if( memberIds.size() <= threshold )
                    return

                final representativeId = new ArrayList(memberIds).sort()[0]
                final collapsedHere = new LinkedHashSet<String>()
                for( final memberId : memberIds )
                    if( memberId != representativeId )
                        collectExclusiveBackward(memberId, representativeId, nodeCache, collapsedHere)

                final groupNode = datasetGroupNode(
                    compId, sigByKey[key], memberIds, representativeId, nodeCache, maxMemberIds)
                allGroupNodes << groupNode

                final memberSet = memberIds as Set
                final kept = CrateJson.asList(node['usedDataset'])
                    .findAll { !(it instanceof Map && it['@id'] in memberSet) }
                kept << idRef(groupNode['@id'] as String)
                node['usedDataset'] = kept

                totalCollapsed.addAll(collapsedHere)
            }
        }

        for( final id : totalCollapsed )
            nodeCache.remove(id)
        for( final groupNode : allGroupNodes )
            nodeCache[groupNode['@id'] as String] = groupNode

        if( !allGroupNodes ) {
            return [
                condensed          : false,
                originalEntityCount: originalCount,
                condensedEntityCount: originalCount,
                datasetGroupCount  : 0,
            ]
        }

        return [
            condensed           : true,
            originalEntityCount : originalCount,
            condensedEntityCount: nodeCache.size(),
            datasetGroupCount   : allGroupNodes.size(),
            entitiesRemoved     : totalCollapsed.size(),
            groups              : allGroupNodes.collect { gn ->
                [
                    memberCount: gn['evi:memberCount'],
                    format     : gn['format'] ?: 'unknown',
                    groupId    : gn['@id'],
                ]
            },
        ]
    }

    /**
     * Walk backwards from a collapsed dataset, marking every entity that is not
     * also reachable from the representative's chain.
     */
    private static void collectExclusiveBackward(String datasetId, String representativeId,
                                                 Map index, Set collapsed) {
        final repChain = backwardChain(representativeId, index)
        final stack = [datasetId]
        final visited = new HashSet()

        while( stack ) {
            final id = stack.pop()
            if( !visited.add(id) )
                continue
            if( id in repChain )
                continue

            final node = index[id] as Map
            if( node == null )
                continue
            if( isSoftware(node) )
                continue

            collapsed << id

            if( isDataset(node) )
                stack.addAll(generatedByIds(node))
            if( isComputation(node) )
                stack.addAll(idList(node, 'usedDataset'))
        }
    }

    private static Set backwardChain(String datasetId, Map index) {
        final visited = new HashSet()
        final stack = [datasetId]
        while( stack ) {
            final id = stack.pop()
            if( !visited.add(id) )
                continue
            final node = index[id] as Map
            if( node == null )
                continue
            if( isDataset(node) )
                stack.addAll(generatedByIds(node))
            if( isComputation(node) )
                stack.addAll(idList(node, 'usedDataset', 'usedSoftware', 'usedMLModel'))
        }
        return visited
    }

    private static Map datasetGroupNode(String consumingCompId, List sig, List<String> memberIds,
                                        String representativeId, Map index, int maxMemberIds) {
        final representative = (index[representativeId] ?: [:]) as Map
        final count = memberIds.size()

        final commonSwIds = []
        if( sig[2] != null )
            for( final compSig : (List) sig[2] )
                commonSwIds.addAll((List) compSig[0])
        final sortedSwIds = new ArrayList(new TreeSet(commonSwIds))

        final fmt = sig[0] as String
        final fmtSlug = fmt.replace('/', '_').replaceAll(/^\.+/, '')

        final consuming = (index[consumingCompId] ?: [:]) as Map
        final consumingName = ((consuming['name'] ?: 'unknown') as String).toLowerCase().replace(' ', '-')
        final groupId = isRoCrateRoot(consuming)
            ? "ark:group/${consumingName}-${fmtSlug}-outputs".toString()
            : "ark:group/${consumingName}-${fmtSlug}-inputs".toString()

        final swNames = sortedSwIds.collect { swId -> ((index[swId] ?: [:]) as Map)['name'] ?: swId }

        String description = "${count} ${fmt} files with identical provenance structure."
        if( swNames )
            description += " All processed by ${swNames.join(', ')}."

        final node = [
            '@id'                     : groupId,
            '@type'                   : ['prov:Entity', 'https://w3id.org/EVI#DatasetGroup'],
            'name'                    : "${representative['name'] ?: (fmt + ' files')} (and ${count - 1} similar)".toString(),
            'description'             : description,
            'format'                  : fmt,
            'evi:memberCount'         : count,
            'evi:representativeDataset': idRef(representativeId),
            'evi:commonFormat'        : fmt,
            'evi:commonSoftware'      : sortedSwIds.collect { idRef(it as String) },
            'evi:provenanceSignature' : repr(sig),
            'evi:memberIds'           : truncateMemberIds(new ArrayList(memberIds).sort(), maxMemberIds),
        ]

        final schemaIds = (sig[1] ?: []) as List
        if( schemaIds )
            node['evi:commonSchema'] = schemaIds.collect { idRef(it as String) }

        return node
    }

    private static List<String> truncateMemberIds(List<String> ids, int maxIds) {
        if( maxIds <= 0 || ids.size() <= maxIds )
            return ids
        final excluded = ids.size() - maxIds
        return ids.take(maxIds) + ["... and ${excluded} more (total: ${ids.size()})".toString()]
    }
}
