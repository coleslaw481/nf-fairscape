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

import groovy.json.JsonSlurper
import spock.lang.Requires
import spock.lang.Specification

/**
 * Builds the derived artifacts for the letters-chain example crate and pins the
 * shape of each one.
 *
 * The `parityDir` test is the diff harness against the Python CLI: point it at
 * a prepared crate directory and it runs the same steps `fairscape build` would
 * so the two outputs can be compared byte for byte.
 */
class CrateArtifactsTest extends Specification {

    static final Path FIXTURE = Path.of('examples/letters-chain/results')

    private Path crateDir

    def setup() {
        crateDir = Files.createTempDirectory('nf-fairscape-datasheet')
        for( final name : ['ro-crate-metadata.json', 'letters.txt', 'reversed.txt', 'first_half.txt', 'second_half.txt'] ) {
            final source = FIXTURE.resolve(name)
            if( Files.exists(source) )
                Files.copy(source, crateDir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    def 'should add EVI inputs and outputs to the crate root'() {
        when:
        CrateArtifacts.generate(crateDir.resolve('ro-crate-metadata.json'), false, true, false, false)
        final crate = new JsonSlurper().parse(crateDir.resolve('ro-crate-metadata.json').toFile())
        final root = CrateJson.rootEntity(crate['@graph'] as List)

        then:
        // the halves are terminal: nothing consumes them
        root[CrateArtifacts.EVI_OUTPUTS]*.get('@id').every { it.contains('half-txt') }
        root[CrateArtifacts.EVI_OUTPUTS].size() == 2
        root[CrateArtifacts.EVI_INPUTS] == []
        root['localEvidenceGraph'] == ['@id': 'provenance-graph.html']
    }

    def 'should build an evidence graph rooted at the crate'() {
        when:
        CrateArtifacts.generate(crateDir.resolve('ro-crate-metadata.json'), false, true, false, false)
        final graph = new JsonSlurper().parse(crateDir.resolve('provenance-graph.json').toFile())

        then:
        graph['@type'] == 'evi:EvidenceGraph'
        graph['@id'].startsWith('ark:59853/evidence-graph-rocrate-')
        graph['name'] == 'Evidence Graph - letters-chain demonstration crate'
        // both outputs plus the crate itself are entry points
        graph['outputs'].size() == 3
        // the crate, both outputs and everything upstream of them: 3 computations,
        // 3 software, 4 datasets. The run-level Computation and the workflow/engine
        // Software are not on any generatedBy/used* edge, so they are not reachable.
        graph['@graph'].size() == 11
        graph['condensation_stats']['condensed'] == false

        and: 'the chain is walkable backwards from an output'
        final second = graph['@graph'].find { id, node -> id.contains('second-half') }.value
        final split = graph['@graph'][second['generatedBy']['@id']]
        split['name'] == 'SPLIT_HALVES'
        split['usedDataset']*.get('@id').any { it.contains('reversed-txt') }
    }

    def 'should render a self-contained evidence graph viewer'() {
        when:
        CrateArtifacts.generate(crateDir.resolve('ro-crate-metadata.json'), false, true, false, false)
        final html = crateDir.resolve('provenance-graph.html').text

        then:
        html.contains('window.__EVIDENCE_GRAPH_DATA__')
        html.contains('Evidence Graph Visualization')
        !html.contains('http://cdn')
        !html.contains('src="http')
    }

    def 'should render the datasheet and AI-Ready score'() {
        when:
        CrateArtifacts.generate(crateDir.resolve('ro-crate-metadata.json'), false, true, false, true)
        final html = crateDir.resolve('ro-crate-datasheet.html').text
        final score = new JsonSlurper().parse(crateDir.resolve('ai_ready_score.json').toFile())

        then:
        html.contains('letters-chain demonstration crate')
        html.contains('Datasheet Summary')
        html.contains('AI-Readiness Score')
        !html.contains('{%')
        !html.contains('{{')

        and: 'the score reflects what the crate actually declares'
        score['fairness']['reusable']['has_content']
        score['provenance']['traceable']['details'] == '4 computation/experiment steps documented'
        score['characterization']['standards']['has_content'] == false
        score['sustainability']['well_governed']['has_content'] == false
    }

    def 'should link the provenance graph from the datasheet'() {
        when:
        CrateArtifacts.generate(crateDir.resolve('ro-crate-metadata.json'), false, true, false, true)

        then:
        crateDir.resolve('ro-crate-datasheet.html').text.contains('provenance-graph.html')
    }

    def 'should condense a scattered fan-out into a DatasetGroup'() {
        given: 'a crate where eight sibling shards feed one merge step'
        final dir = Files.createTempDirectory('nf-fairscape-fanout')
        final metadataFile = dir.resolve('ro-crate-metadata.json')
        metadataFile.text = getClass().getResourceAsStream('/crates/fanout-crate.json').text

        when:
        CrateArtifacts.generate(metadataFile, false, true, false, false)
        final graph = new JsonSlurper().parse(metadataFile.toFile())
        final evidence = new JsonSlurper().parse(dir.resolve('provenance-graph.json').toFile())

        then: 'the shards collapse to one group with a representative'
        evidence['condensation_stats']['condensed']
        evidence['condensation_stats']['datasetGroupCount'] == 1
        evidence['condensation_stats']['entitiesRemoved'] == 14
        final group = evidence['@graph']['ark:group/merge-txt-inputs']
        group['evi:memberCount'] == 8
        group['evi:representativeDataset'] == ['@id': 'ark:59853/dataset-shard-0']
        evidence['@graph'].containsKey('ark:59853/dataset-shard-0')
        !evidence['@graph'].containsKey('ark:59853/dataset-shard-1')

        and: 'condensation never leaks back into the crate itself'
        final merge = graph['@graph'].find { it['@id'] == 'ark:59853/computation-merge' }
        merge['usedDataset'].size() == 8
        merge['usedDataset'].every { it['@id'].startsWith('ark:59853/dataset-shard-') }
    }

    @Requires({ System.getProperty('parityDir') })
    def 'should build artifacts in the directory given by -DparityDir (CLI diff harness)'() {
        given:
        final dir = Path.of(System.getProperty('parityDir'))
        final steps = System.getProperty('parityStep', 'all')

        when:
        CrateArtifacts.generate(
            dir.resolve('ro-crate-metadata.json'),
            steps in ['all', 'inverses'],
            steps in ['all', 'graph'],
            steps in ['all', 'linkml'],
            steps in ['all', 'datasheet'])

        then:
        Files.exists(dir.resolve('ro-crate-metadata.json'))
    }
}
