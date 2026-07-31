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

import nextflow.prov.datasheet.CrateArtifacts
import nextflow.prov.datasheet.CrateJson
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

/**
 * `CrateArtifacts.calculateInputsOutputs` vs `fairscape-cli augment add-io`
 * (`fairscape_cli.entailments.find_outputs.calculate_inputs_outputs`).
 *
 * <p>Both sides are given a crate the CLI has already run link-inverses over, so
 * the entailment sees the same graph either way and only the outputs calculation
 * is under test.
 *
 * <p>ONE DELIBERATE DIVERGENCE, and this spec pins its exact extent: the port
 * does not report a Dataset that is `isPartOf` another entity -- a file found
 * inside a published directory -- as a crate output, because the directory's own
 * Dataset already stands for it. Reachable only with `expandDirectories`, where
 * the CLI's rule makes one published MultiQC folder look like a thousand crate
 * outputs. Anything else that differs is a regression.
 *
 * @author FAIRSCAPE
 */
@Requires({ CliParity.available() })
class InputsOutputsParityTest extends Specification {

    @Unroll
    def 'inputs and outputs match the CLI on #crate, except for files inside a published directory'() {
        given: 'one crate, entailed by the CLI, handed to both implementations'
        def dir = CliParity.fixture(crate)
        CliParity.cli(['augment', 'link-inverses', dir.toString()])
        final graph = CrateJson.graphOf(CrateJson.read(dir.resolve('ro-crate-metadata.json')))

        when: 'the port derives its inputs/outputs from that graph'
        final port = CrateArtifacts.calculateInputsOutputs(graph)

        and: 'the CLI writes its own onto the root'
        CliParity.cli(['augment', 'add-io', dir.toString()])
        final root = CrateJson.rootEntity(
            CrateJson.graphOf(CrateJson.read(dir.resolve('ro-crate-metadata.json'))))

        then: 'inputs agree exactly'
        ids(port.inputs) == ids(root[CrateArtifacts.EVI_INPUTS])

        and: 'the port never claims an output the CLI does not'
        ids(port.outputs) - ids(root[CrateArtifacts.EVI_OUTPUTS]) == [] as Set

        and: 'and every output it drops is a file inside a published directory'
        final dropped = ids(root[CrateArtifacts.EVI_OUTPUTS]) - ids(port.outputs)
        dropped == partOfDatasets(graph).intersect(ids(root[CrateArtifacts.EVI_OUTPUTS]))

        cleanup:
        dir.deleteDir()

        where:
        crate << CliParity.CRATES
    }

    def 'the divergence is real, not vacuous: the enriched crate exercises it'() {
        given: 'nf-test publishes directories, so it has isPartOf datasets'
        def dir = CliParity.fixture('nf-test')
        final graph = CrateJson.graphOf(CrateJson.read(dir.resolve('ro-crate-metadata.json')))

        expect:
        !partOfDatasets(graph).isEmpty()

        cleanup:
        dir.deleteDir()
    }

    private static Set<String> ids(Object refs) {
        return (CrateJson.asList(refs).collect { CrateJson.refId(it) }.findAll() as Set<String>)
    }

    private static Set<String> partOfDatasets(List<Map> graph) {
        return graph.findAll { Map e ->
            CrateJson.lastType(e).contains('Dataset') &&
                CrateJson.asList(e['isPartOf']).any { ref -> CrateJson.refId(ref) }
        }.collect { Map e -> e['@id'] as String } as Set<String>
    }
}
