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

package nextflow.prov.parity

import java.nio.file.Files
import java.nio.file.Path

import groovy.json.JsonSlurper
import nextflow.prov.datasheet.CrateJson
import nextflow.prov.datasheet.EvidenceGraphBuilder
import nextflow.prov.datasheet.EvidenceGraphHtml
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

/**
 * `EvidenceGraphBuilder` + `GraphCondenser` + `EvidenceGraphHtml` vs
 * `fairscape-cli build evidence-graph`.
 *
 * <p>This is the strictest claim the port makes: provenance-graph.json and
 * provenance-graph.html are BYTE-IDENTICAL to the CLI's. `PyJson` exists so the
 * JSON claim can hold (it reproduces `json.dump(indent=2)`), and the HTML claim
 * covers the whole `MiniJinja` template pass.
 *
 * <p>The crate handed to both sides is prepared BY THE CLI -- link-inverses then
 * add-io -- so the walk starts from the same entry points on both sides and only
 * the graph builder is under test. (The port's own entry points can differ; that
 * divergence has its own spec, {@link InputsOutputsParityTest}.)
 *
 * @author FAIRSCAPE
 */
@Requires({ CliParity.available() })
class EvidenceGraphParityTest extends Specification {

    @Unroll
    def 'provenance-graph.json is byte-identical to the CLI on #crate'() {
        given:
        def dirs = prepared(crate)

        when:
        buildBoth(dirs)

        then:
        CliParity.byteDifference(dirs.python.resolve('provenance-graph.json'),
                                 dirs.groovy.resolve('provenance-graph.json')) == null

        cleanup:
        dirs.python.deleteDir()
        dirs.groovy.deleteDir()

        where:
        crate << CliParity.CRATES
    }

    @Unroll
    def 'provenance-graph.html is byte-identical to the CLI on #crate'() {
        given:
        def dirs = prepared(crate)

        when:
        buildBoth(dirs)

        then:
        CliParity.byteDifference(dirs.python.resolve('provenance-graph.html'),
                                 dirs.groovy.resolve('provenance-graph.html')) == null

        cleanup:
        dirs.python.deleteDir()
        dirs.groovy.deleteDir()

        where:
        crate << CliParity.CRATES
    }

    def 'the fan-out crate really is the one that condenses'() {
        given: 'otherwise the byte-identical claim above would never reach GraphCondenser'
        def dirs = prepared('fanout')

        when:
        buildBoth(dirs)
        final graph = new JsonSlurper().parse(dirs.python.resolve('provenance-graph.json').toFile())

        then:
        graph['condensation_stats']['condensed']

        cleanup:
        dirs.python.deleteDir()
        dirs.groovy.deleteDir()
    }

    /**
     * One crate, entailed by the CLI, copied to a directory per implementation.
     */
    private static Map<String, Path> prepared(String crate) {
        final source = CliParity.fixture(crate)
        CliParity.cli(['augment', 'link-inverses', source.toString()])
        CliParity.cli(['augment', 'add-io', source.toString()])

        final pythonDir = Files.createTempDirectory("nf-fairscape-parity-${crate}-py-")
        final groovyDir = Files.createTempDirectory("nf-fairscape-parity-${crate}-gr-")
        CliParity.copyTree(source, pythonDir)
        CliParity.copyTree(source, groovyDir)
        source.deleteDir()
        return [python: pythonDir, groovy: groovyDir]
    }

    private static void buildBoth(Map<String, Path> dirs) {
        final metadataFile = dirs.groovy.resolve('ro-crate-metadata.json')
        CliParity.cli(['build', 'evidence-graph',
                       dirs.python.toString(), CliParity.rootArk(metadataFile)])
        // the same calls CrateArtifacts.buildEvidenceGraph makes, minus the crate
        // rewrite -- only the two artifacts are being compared
        final graph = CrateJson.graphOf(CrateJson.read(metadataFile))
        final builder = new EvidenceGraphBuilder(graph)
        final resolved = builder.findEntity(CliParity.rootArk(metadataFile))
        final name = (resolved['name'] ?: 'Unknown') as String
        final evidence = builder.build(
            resolved['@id'] as String,
            "Evidence Graph - ${name}".toString(),
            "Evidence graph for ${name}".toString())
        EvidenceGraphBuilder.write(evidence, dirs.groovy.resolve('provenance-graph.json'))
        EvidenceGraphHtml.write(evidence, dirs.groovy.resolve('provenance-graph.html'))
    }

}
