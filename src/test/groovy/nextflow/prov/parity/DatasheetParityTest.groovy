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

import nextflow.prov.datasheet.CrateJson
import nextflow.prov.datasheet.D4dConverter
import nextflow.prov.datasheet.DatasheetGenerator
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

/**
 * `D4dConverter`/`PyYaml`, `AiReadyScorer` and `DatasheetGenerator`/`MiniJinja`
 * vs `fairscape-cli build datasheet`, which writes all three artifacts.
 *
 * <p>What each claim is:
 * <ul>
 * <li>`ro-crate-linkml.yaml` -- BYTE-IDENTICAL. `PyYaml` is a `yaml.dump` clone
 *     down to the Emitter's column bookkeeping precisely so this can hold.
 * <li>`ai_ready_score.json` -- BYTE-IDENTICAL.
 * <li>`ro-crate-datasheet.html` -- identical after two documented normalizations,
 *     see {@link CliParity#normalizeDatasheet}. One is a deliberate deviation
 *     (we render summary stat cards the CLI's dead-code guard never reaches);
 *     the other is unreachable in principle, because the CLI's own composition
 *     patterns come out of a `list(set(...))` and reorder with PYTHONHASHSEED.
 * </ul>
 *
 * <p>Both sides are given byte-identical directories: the datasheet embeds the
 * crate's directory size, so a stray file on one side moves a number in the HTML.
 * The CLI writes the LinkML sidecar before it measures, so the port's LinkML pass
 * has to run first too.
 *
 * @author FAIRSCAPE
 */
@Requires({ CliParity.available() })
class DatasheetParityTest extends Specification {

    @Unroll
    def 'ro-crate-linkml.yaml is byte-identical to the CLI on #crate'() {
        given:
        def dirs = prepared(crate)

        when:
        buildBoth(dirs)

        then:
        CliParity.byteDifference(dirs.python.resolve('ro-crate-linkml.yaml'),
                                 dirs.groovy.resolve('ro-crate-linkml.yaml')) == null

        cleanup:
        discard(dirs)

        where:
        crate << CliParity.CRATES
    }

    @Unroll
    def 'ai_ready_score.json is byte-identical to the CLI on #crate'() {
        given:
        def dirs = prepared(crate)

        when:
        buildBoth(dirs)

        then:
        CliParity.byteDifference(dirs.python.resolve('ai_ready_score.json'),
                                 dirs.groovy.resolve('ai_ready_score.json')) == null

        cleanup:
        discard(dirs)

        where:
        crate << CliParity.CRATES
    }

    @Unroll
    def 'ro-crate-datasheet.html matches the CLI on #crate once the documented deviations are normalized'() {
        given:
        def dirs = prepared(crate)

        // the difference is reduced HERE rather than in the condition: Spock renders
        // every operand of a failing condition, and these are 50 KB documents
        when:
        buildBoth(dirs)
        final difference = CliParity.textDifference(
            CliParity.normalizeDatasheet(dirs.python.resolve('ro-crate-datasheet.html').text),
            CliParity.normalizeDatasheet(dirs.groovy.resolve('ro-crate-datasheet.html').text))

        then:
        difference == null

        cleanup:
        discard(dirs)

        where:
        crate << CliParity.CRATES
    }

    def 'the only unnormalized difference is the summary stat cards we add'() {
        given: 'pinning the deviation, so a NEW one cannot hide inside the normalizer'
        def dirs = prepared('letters-chain')

        when:
        buildBoth(dirs)
        final fromCli = dirs.python.resolve('ro-crate-datasheet.html').text
        final fromPlugin = dirs.groovy.resolve('ro-crate-datasheet.html').text
        final cardsRemoved = CliParity.textDifference(
            fromCli, fromPlugin.replaceAll(/(?s)\s*<div class="stat-card">.*?<\/div>/, ''))

        then: 'the CLI emits no stat cards at all'
        !fromCli.contains('class="stat-card"')

        and: 'we emit one per entity kind'
        fromPlugin.count('class="stat-card"') == 3
        fromPlugin.contains('<span class="stat-caption">Datasets</span>')
        fromPlugin.contains('<span class="stat-caption">Computations</span>')
        fromPlugin.contains('<span class="stat-caption">Software</span>')

        and: 'and removing them alone reconciles this crate, which has one composition pattern'
        cardsRemoved == null

        cleanup:
        discard(dirs)
    }

    /**
     * A crate with the CLI's inverses, inputs/outputs and evidence graph already
     * in place, copied to a directory per implementation. The datasheet's
     * composition section reads the root EVI#inputs/#outputs and its provenance
     * link reads the evidence graph, so both have to exist before it runs.
     */
    private static Map<String, Path> prepared(String crate) {
        final source = CliParity.fixture(crate)
        CliParity.cli(['augment', 'link-inverses', source.toString()])
        CliParity.cli(['augment', 'add-io', source.toString()])
        CliParity.cli(['build', 'evidence-graph', source.toString(),
                       CliParity.rootArk(source.resolve('ro-crate-metadata.json'))])

        final pythonDir = Files.createTempDirectory("nf-fairscape-parity-${crate}-py-")
        final groovyDir = Files.createTempDirectory("nf-fairscape-parity-${crate}-gr-")
        CliParity.copyTree(source, pythonDir)
        CliParity.copyTree(source, groovyDir)
        source.deleteDir()
        return [python: pythonDir, groovy: groovyDir]
    }

    private static void buildBoth(Map<String, Path> dirs) {
        CliParity.cli(['build', 'datasheet', dirs.python.toString()])

        final metadataFile = dirs.groovy.resolve('ro-crate-metadata.json')
        // LinkML first: `build datasheet` writes the sidecar before it measures
        // the directory, and the measurement lands in the HTML
        final root = CrateJson.rootEntity(CrateJson.graphOf(CrateJson.read(metadataFile)))
        D4dConverter.write(root, metadataFile.parent)
        new DatasheetGenerator(metadataFile, false).generate()
    }

    private static void discard(Map<String, Path> dirs) {
        dirs.python.deleteDir()
        dirs.groovy.deleteDir()
    }

}
