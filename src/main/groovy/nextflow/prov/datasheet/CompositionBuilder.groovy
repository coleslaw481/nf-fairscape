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
 * Port of fairscape_models.conversion.mapping.subcrate_utils.build_composition_details:
 * the per-crate entity census (counts, format/access histograms, provenance
 * "equations") that fills the datasheet's Composition card.
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class CompositionBuilder {

    private static final String ARROW = '<strong>&rarr;</strong>'

    /** Provenance link keys in every form they appear in an RO-Crate. */
    private static final List<String> PROVENANCE_KEYS = [
        'generatedBy',
        'EVI:generatedBy',
        'evi:generatedBy',
        'https://w3id.org/EVI#generatedBy',
        'wasGeneratedBy',
        'prov:wasGeneratedBy',
        'http://www.w3.org/ns/prov#wasGeneratedBy',
    ]

    static Map build(List<Map> graph, Map index) {
        final details = [
            files_count                   : 0,
            software_count                : 0,
            instruments_count             : 0,
            samples_count                 : 0,
            experiments_count             : 0,
            computations_count            : 0,
            schemas_count                 : 0,
            other_count                   : 0,
            datasets_with_provenance_count: 0,
        ]

        final fileFormats = []
        final softwareFormats = []
        final fileAccess = []
        final softwareAccess = []
        final computationPatterns = []
        final experimentPatterns = []
        final cellLines = [:]
        final speciesCounts = []
        final experimentTypes = []

        for( final item : graph ) {
            if( item['@id'] == 'ro-crate-metadata.json' )
                continue
            if( item.containsKey('ro-crate-metadata') )
                continue

            switch( CrateJson.normalizeType(item) ) {
                case 'Dataset':
                    details.files_count++
                    if( PROVENANCE_KEYS.any { item[it] } )
                        details.datasets_with_provenance_count++
                    fileFormats.addAll(CrateJson.normalizeFormats(item['format'] ?: 'unknown'))
                    fileAccess << accessType(item)
                    break

                case 'Software':
                    details.software_count++
                    softwareFormats.addAll(CrateJson.normalizeFormats(item['format'] ?: 'unknown'))
                    softwareAccess << accessType(item)
                    break

                case 'Instrument':
                    details.instruments_count++
                    break

                case 'Sample':
                    details.samples_count++
                    speciesCounts << processSample(item, cellLines, index)
                    break

                case 'Experiment':
                    details.experiments_count++
                    final pattern = experimentPattern(item, index)
                    if( pattern )
                        experimentPatterns << pattern
                    experimentTypes << ((item['experimentType'] ?: 'Unknown') as String)
                    break

                case 'Computation':
                    details.computations_count++
                    final pattern = computationPattern(item, index)
                    if( pattern )
                        computationPatterns << pattern
                    break

                case 'Schema':
                    details.schemas_count++
                    break

                default:
                    details.other_count++
                    break
            }
        }

        details.file_formats = CrateJson.counter(fileFormats).findAll { k, v -> k && k != 'unknown' }
        details.software_formats = CrateJson.counter(softwareFormats).findAll { k, v -> k && k != 'unknown' }
        details.file_access = CrateJson.counter(fileAccess)
        details.software_access = CrateJson.counter(softwareAccess)
        details.computation_patterns = computationPatterns.unique(false)
        details.experiment_patterns = experimentPatterns.unique(false)
        details.cell_lines = cellLines
        details.species = CrateJson.counter(speciesCounts).collect { name, count -> "${name} (${count})".toString() }
        details.experiment_types = CrateJson.counter(experimentTypes)

        final inputCounts = inputDatasets(graph.size() > 1 ? graph[1] : null, index)
        details.input_datasets = inputCounts
        details.input_datasets_count = inputCounts.values().sum() ?: 0
        details.inputs_count = details.samples_count + details.input_datasets_count

        return details
    }

    private static String accessType(Map item) {
        final url = item['contentUrl']
        if( !url )
            return 'No link'
        return url == 'Embargoed' ? 'Embargoed' : 'Available'
    }

    private static String processSample(Map item, Map cellLines, Map index) {
        final ref = item['cellLineReference'] ?: item['derivedFrom']
        if( !ref )
            return 'Unknown'

        final refId = CrateJson.refId(ref) ?: ''
        final cellLine = (index[refId] ?: [:]) as Map
        if( !cellLines.containsKey(refId) ) {
            final organism = cellLine['organism']
            final organismName = (organism instanceof Map && organism['name']) ? organism['name'] as String : 'Unknown'
            cellLines[refId] = [
                name         : (cellLine['name'] ?: refId) as String,
                organism_name: organismName,
                identifier   : refId,
            ]
        }
        return cellLines[refId]['organism_name'] as String
    }

    /** Escape a (possibly comma-joined) format string and join its parts with ' + '. */
    private static String formatTokens(String fmt) {
        return fmt.split(',')
            .collect { it.trim() }
            .findAll { it }
            .collect { CrateJson.htmlEscape(it) }
            .join(' + ')
    }

    private static String inputFragment(String crateName, String fmt) {
        final display = formatTokens(fmt)
        return crateName ? "<strong>${CrateJson.htmlEscape(crateName)}</strong>: ${display}".toString() : display
    }

    private static String experimentPattern(Map item, Map index) {
        final outputs = []
        for( final ref : CrateJson.asList(item['generated']) ) {
            final id = CrateJson.refId(ref)
            if( !id )
                continue
            final fmt = CrateJson.normalizeFormatStr(lookupFormat(id, index))
            if( fmt && fmt != 'unknown' )
                outputs << fmt
        }
        if( !outputs )
            return null
        final outputStr = outputs.collect { formatTokens(it) }.toUnique().sort().join(' + ')
        return "Sample ${ARROW} ${outputStr}".toString()
    }

    private static String computationPattern(Map item, Map index) {
        final inputs = []
        final outputs = []

        final itemId = item['@id'] as String
        final currentCrateName = itemId && index[itemId] ? index[itemId]['rocrateName'] : null

        for( final ref : CrateJson.asList(item['usedDataset']) ) {
            final id = CrateJson.refId(ref)
            if( !id || !index.containsKey(id) )
                continue
            final info = index[id] as Map
            final fmt = CrateJson.normalizeFormatStr(info['format'] ?: 'unknown')
            final crateName = info['rocrateName']
            if( fmt && fmt != 'unknown' )
                inputs << inputFragment(crateName && crateName != currentCrateName ? crateName as String : null, fmt)
        }

        for( final ref : CrateJson.asList(item['generated']) ) {
            final id = CrateJson.refId(ref)
            if( !id )
                continue
            final fmt = CrateJson.normalizeFormatStr(lookupFormat(id, index))
            if( fmt && fmt != 'unknown' )
                outputs << fmt
        }

        if( !inputs || !outputs )
            return null

        final inputStr = inputs.toUnique().sort().join(' + ')
        final outputStr = outputs.collect { formatTokens(it) }.toUnique().sort().join(' + ')
        return "${inputStr} ${ARROW} ${outputStr}".toString()
    }

    private static Object lookupFormat(String id, Map index) {
        final info = index[id] as Map
        return info != null ? (info['format'] ?: 'unknown') : 'unknown'
    }

    /** Count the crate's declared EVI inputs by format (or by source crate). */
    private static Map<String,Integer> inputDatasets(Map root, Map index) {
        final counts = new LinkedHashMap<String,Integer>()
        if( root == null )
            return counts

        final raw = root['EVI:inputs'] ?: root['https://w3id.org/EVI#inputs'] ?: root['inputs']
        for( final ref : CrateJson.asList(raw) ) {
            final inputId = CrateJson.refId(ref)
            if( !inputId )
                continue

            final info = index[inputId] as Map
            if( info == null ) {
                counts['unknown'] = (counts['unknown'] ?: 0) + 1
                continue
            }

            if( CrateJson.isRoCrate(info['@type']) ) {
                final outputs = CrateJson.asList(
                    info['https://w3id.org/EVI#outputs'] ?: info['EVI:outputs'] ?: info['outputs'])
                final crateName = (info['name'] ?: 'Unknown RO-Crate') as String
                for( final outputRef : outputs ) {
                    final outputId = CrateJson.refId(outputRef)
                    if( outputId && index.containsKey(outputId) ) {
                        final fmt = CrateJson.normalizeFormatStr((index[outputId] as Map)['format'] ?: 'unknown')
                        final key = "${crateName} (${fmt})".toString()
                        counts[key] = (counts[key] ?: 0) + 1
                    }
                }
            }
            else {
                def fmt = CrateJson.normalizeFormatStr(info['format'] ?: 'unknown')
                if( fmt == 'unknown' )
                    fmt = 'Sample'
                final crateName = info['rocrateName']
                final key = (crateName && crateName != root['name'])
                    ? "${crateName} (${fmt})".toString()
                    : fmt
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts
    }
}
