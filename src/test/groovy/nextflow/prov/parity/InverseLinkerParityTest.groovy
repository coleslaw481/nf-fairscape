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

import java.nio.file.Path

import groovy.json.JsonOutput
import nextflow.prov.datasheet.CrateJson
import nextflow.prov.datasheet.InverseLinker
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

/**
 * `InverseLinker` vs `fairscape-cli augment link-inverses`.
 *
 * <p>The CLI rdflib-parses EVI.owl and SPARQLs for `owl:inverseOf`; the port
 * carries the 18-pair answer as data. This is the spec that keeps those two in
 * agreement -- if the ontology gains a pair, the CLI picks it up on its next
 * release and this test goes red.
 *
 * <p>Compared on the PARSED graph, not the bytes: the CLI writes
 * `json.dump(indent=2)` through its `prune_none` pass while the plugin writes
 * `JsonOutput.prettyPrint`, so formatting differs by construction. Multi-valued
 * properties are compared as sets -- the CLI's SPARQL order is nondeterministic,
 * which is exactly why the port sorts its pairs.
 *
 * @author FAIRSCAPE
 */
@Requires({ CliParity.available() })
class InverseLinkerParityTest extends Specification {

    @Unroll
    def 'link-inverses matches the CLI on #crate'() {
        given: 'the same crate for each implementation'
        def pythonDir = CliParity.fixture(crate)
        def groovyDir = CliParity.fixture(crate)

        when: 'the CLI entails its side'
        CliParity.cli(['augment', 'link-inverses', pythonDir.toString()])

        and: 'the port entails its own'
        final metadataFile = groovyDir.resolve('ro-crate-metadata.json')
        final groovyCrate = CrateJson.read(metadataFile)
        InverseLinker.link(CrateJson.graphOf(groovyCrate))
        metadataFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(groovyCrate))

        then:
        CliParity.diffGraphs(graphOf(pythonDir), graphOf(groovyDir)).take(20) == []

        cleanup:
        pythonDir.deleteDir()
        groovyDir.deleteDir()

        where:
        crate << CliParity.CRATES
    }

    def 'the port adds the same number of links a second run would not'() {
        given: 'entailment is idempotent, so a linked crate gains nothing'
        def dir = CliParity.fixture('letters-chain')
        final metadataFile = dir.resolve('ro-crate-metadata.json')

        when:
        final crate = CrateJson.read(metadataFile)
        final graph = CrateJson.graphOf(crate)
        final first = InverseLinker.link(graph)
        final second = InverseLinker.link(graph)

        then:
        first > 0
        second == 0

        cleanup:
        dir.deleteDir()
    }

    private static List<Map> graphOf(Path dir) {
        return CrateJson.graphOf(CrateJson.read(dir.resolve('ro-crate-metadata.json')))
    }
}
