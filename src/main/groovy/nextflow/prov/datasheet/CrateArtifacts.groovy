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
import java.nio.file.StandardCopyOption

import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j

/**
 * Runs the derived-artifact steps `fairscape build` performs on a finished
 * crate, in the same order the CLI's `build subcrate` pipeline uses:
 *
 * <ol>
 *   <li>link every owl:inverseOf pair the graph already states
 *       (fairscape augment link-inverses)</li>
 *   <li>add EVI:inputs / EVI:outputs to the root entity
 *       (fairscape_cli.entailments.find_outputs)</li>
 *   <li>build the evidence graph rooted at the crate, write
 *       provenance-graph.json + provenance-graph.html, and record the viewer
 *       on the root as localEvidenceGraph</li>
 *   <li>write ro-crate-linkml.yaml (the D4D translation)</li>
 *   <li>render ro-crate-datasheet.html + ai_ready_score.json</li>
 * </ol>
 *
 * Every step is best-effort (see {@link #step}): a failure is logged, the
 * remaining steps still run, and the crate itself is left intact -- it is
 * only ever rewritten atomically via {@link #writeCrate}.
 *
 * @author FAIRSCAPE
 */
@Slf4j
@CompileDynamic
class CrateArtifacts {

    static final String EVI_INPUTS = 'https://w3id.org/EVI#inputs'
    static final String EVI_OUTPUTS = 'https://w3id.org/EVI#outputs'

    static final String GRAPH_JSON = 'provenance-graph.json'
    static final String GRAPH_HTML = 'provenance-graph.html'

    /**
     * Steps run in the CLI's `process_subcrate` order: inverses are linked
     * before inputs/outputs are derived, so the entailment sees a graph where
     * both halves of every stated relationship are present.
     *
     * @param metadataFile   the crate's ro-crate-metadata.json, already written
     * @param linkInverses   complete every `owl:inverseOf` pair in the graph
     * @param evidenceGraph  build provenance-graph.json/.html
     * @param linkml         build ro-crate-linkml.yaml (the D4D translation)
     * @param datasheet      build ro-crate-datasheet.html + ai_ready_score.json
     * @param published      render the crate as published (affects link text)
     */
    static void generate(Path metadataFile, boolean linkInverses, boolean evidenceGraph,
                         boolean linkml, boolean datasheet, boolean published = false) {
        if( linkInverses )
            step('link-inverses') { applyInverses(metadataFile) }

        // its own stage, as in the CLI: the datasheet's composition section and
        // the D4D translation read the root EVI#inputs/#outputs, so the
        // entailment must run even when the evidence graph is disabled
        if( evidenceGraph || linkml || datasheet )
            step('inputs/outputs') { applyInputsOutputs(metadataFile) }

        if( evidenceGraph )
            step('evidence graph') { buildEvidenceGraph(metadataFile) }

        if( linkml )
            step('linkml') { buildLinkml(metadataFile) }

        if( datasheet )
            step('datasheet') {
                final output = new DatasheetGenerator(metadataFile, published).generate()
                log.info "FAIRSCAPE datasheet: ${output.toUriString()}"
            }
    }

    /**
     * Run one derived-artifact step best-effort: a failure (including an Error
     * such as a StackOverflowError from a cyclic graph) is logged and the
     * remaining steps still run, since each artifact stands on its own.
     */
    private static void step(String name, Closure action) {
        try {
            action.call()
        }
        catch( Throwable e ) {
            log.warn "Error building the FAIRSCAPE ${name} artifact -- see Nextflow log for details"
            log.debug "Error building the FAIRSCAPE ${name} artifact", e
        }
    }

    /**
     * Rewrite the crate atomically: write a sibling temp file, then move it
     * over `ro-crate-metadata.json`. A failure mid-write must never leave a
     * truncated crate behind -- the workflow has already succeeded.
     */
    private static void writeCrate(Path metadataFile, Object crate) {
        final tmp = metadataFile.resolveSibling(metadataFile.name + '.tmp')
        Files.write(tmp, JsonOutput.prettyPrint(JsonOutput.toJson(crate)).getBytes('UTF-8'))
        try {
            Files.move(tmp, metadataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        catch( java.nio.file.AtomicMoveNotSupportedException e ) {
            // object-store backed paths may not support atomic moves
            Files.move(tmp, metadataFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * `fairscape_cli.entailments.find_outputs`: record what the run consumed
     * and produced as EVI:inputs / EVI:outputs on the root entity.
     */
    private static void applyInputsOutputs(Path metadataFile) {
        final crate = CrateJson.read(metadataFile)
        final graph = CrateJson.graphOf(crate)
        final root = CrateJson.rootEntity(graph)
        if( root == null )
            throw new IllegalStateException("Could not find the root dataset entity in ${metadataFile}")

        final io = calculateInputsOutputs(graph)
        root[EVI_INPUTS] = io.inputs
        root[EVI_OUTPUTS] = io.outputs
        writeCrate(metadataFile, crate)
    }

    /**
     * `fairscape augment link-inverses`: add the missing half of every inverse
     * relationship the graph already states, then rewrite the crate.
     */
    private static void applyInverses(Path metadataFile) {
        final crate = CrateJson.read(metadataFile)
        final graph = CrateJson.graphOf(crate)
        final modified = InverseLinker.link(graph)
        if( modified > 0 ) {
            writeCrate(metadataFile, crate)
            log.debug "FAIRSCAPE inverse properties linked: ${modified} added"
        }
    }

    /**
     * `fairscape build linkml`: translate the crate root into a D4D document.
     */
    private static void buildLinkml(Path metadataFile) {
        final crate = CrateJson.read(metadataFile)
        final root = CrateJson.rootEntity(CrateJson.graphOf(crate))
        if( root == null )
            throw new IllegalStateException("Could not find the root dataset entity in ${metadataFile}")

        final output = D4dConverter.write(root, metadataFile.parent)
        log.info "FAIRSCAPE LinkML/D4D: ${output.toUriString()}"
    }

    private static void buildEvidenceGraph(Path metadataFile) {
        final crateDir = metadataFile.parent
        final crate = CrateJson.read(metadataFile)
        final graph = CrateJson.graphOf(crate)
        final root = CrateJson.rootEntity(graph)
        if( root == null )
            throw new IllegalStateException("Could not find the root dataset entity in ${metadataFile}")

        // 1. evidence graph rooted at the crate (EVI:inputs/EVI:outputs were
        //    already written to the root by applyInputsOutputs)
        final builder = new EvidenceGraphBuilder(graph)
        final resolved = builder.findEntity(root['@id'] as String)
        if( resolved == null )
            throw new IllegalStateException("Crate root ${root['@id']} is not indexable in ${metadataFile}")

        final rootId = resolved['@id'] as String
        final rootName = (resolved['name'] ?: 'Unknown') as String
        final evidenceGraph = builder.build(
            rootId,
            "Evidence Graph - ${rootName}".toString(),
            "Evidence graph for ${rootName}".toString())

        EvidenceGraphBuilder.write(evidenceGraph, crateDir.resolve(GRAPH_JSON))
        EvidenceGraphHtml.write(evidenceGraph, crateDir.resolve(GRAPH_HTML))
        log.info "FAIRSCAPE provenance graph: ${crateDir.resolve(GRAPH_HTML).toUriString()}"

        // 2. point the crate at the viewer and rewrite it, keeping the renderer's
        //    JSON formatting so the file only differs by the added fields
        root['localEvidenceGraph'] = ['@id': GRAPH_HTML]
        writeCrate(metadataFile, crate)
    }

    // ------------------------------------------------------------------
    // fairscape_cli.entailments.find_outputs
    // ------------------------------------------------------------------

    /**
     * Outputs are the datasets no computation consumed; inputs are the samples,
     * the consumed datasets that nothing generated, and any standalone dataset.
     */
    static Map calculateInputsOutputs(List<Map> graph) {
        final datasets = [:] as Map<String,Boolean>     // @id -> has generatedBy
        final datasetOrder = []
        final samples = []
        final usedDatasetIds = new LinkedHashSet<String>()

        for( final entity : graph ) {
            final type = CrateJson.lastType(entity)
            final id = entity['@id'] as String

            if( type.contains('Dataset') && id ) {
                if( !datasets.containsKey(id) )
                    datasetOrder << id
                datasets[id] = (boolean) entity['generatedBy']
            }
            if( (type == 'https://w3id.org/EVI#Sample' || type == 'EVI:Sample') && id )
                samples << id
            if( type == 'https://w3id.org/EVI#Computation' || type == 'EVI:Computation' ) {
                for( final ref : CrateJson.asList(entity['usedDataset']) ) {
                    final refId = CrateJson.refId(ref)
                    if( refId )
                        usedDatasetIds << refId
                }
            }
        }

        final inputs = new LinkedHashSet<String>()
        final outputs = new LinkedHashSet<String>()

        inputs.addAll(samples)

        for( final usedId : usedDatasetIds ) {
            if( datasets.containsKey(usedId) ) {
                if( !datasets[usedId] )
                    inputs << usedId
            }
            else {
                inputs << usedId
            }
        }

        for( final id : datasetOrder ) {
            if( !usedDatasetIds.contains(id) ) {
                outputs << id
                if( !datasets[id] )
                    inputs << id
            }
        }

        return [
            inputs : inputs.collect { ['@id': it] },
            outputs: outputs.collect { ['@id': it] },
        ]
    }
}
