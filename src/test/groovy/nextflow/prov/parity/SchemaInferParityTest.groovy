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
import java.nio.file.Paths

import groovy.json.JsonSlurper
import nextflow.prov.schema.TabularSchemaInferrer
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Unroll

/**
 * `TabularSchemaInferrer` vs `fairscape-cli schema infer`
 * (`fairscape_models.schema.tabular.TabularSchema.infer` over frictionless
 * `Detector.detect_schema`).
 *
 * <p>The whole emitted document is compared, not just the column types: the port
 * has to produce the CLI's `properties`, `required` order, `separator`, `header`
 * and JSON-Schema envelope. Two fields are excluded by design and each is pinned
 * by its own feature below, so neither can quietly grow into a real difference:
 *
 * <ul>
 * <li>`fairscapeVersion` -- the CLI stamps its own version; the port has none.
 * <li>`@id` -- the CLI mints a uuid, so it is never reproducible. The port hashes
 *     the Dataset ARK instead, which makes a re-run of the same pipeline emit the
 *     same schema id.
 * </ul>
 *
 * <p>Type detection is compared with `schemaCommentChar` OFF, which is the
 * configuration that claims CLI parity. The default (`#`) deliberately skips a
 * comment preamble -- see the divergence feature at the bottom.
 *
 * @author FAIRSCAPE
 */
@Requires({ CliParity.available() })
class SchemaInferParityTest extends Specification {

    /** csv/tsv fixtures. parquet/HDF5/WFDB/DICOM are not ported, so not compared. */
    static final List<String> TABLES = [
        'src/test/resources/tabular/types.csv',
        'src/test/resources/tabular/quoted.csv',
        'src/test/resources/tabular/sparse.tsv',
        'src/test/resources/parity/nf-test/table/summary/summary.tsv',
    ]

    @Unroll
    def 'the inferred schema matches the CLI for #table'() {
        given:
        final input = Paths.get(table)
        final guid = 'ark:99999/schema-parity'

        when: 'the CLI infers its schema'
        def out = Files.createTempDirectory('nf-fairscape-parity-schema-')
        final schemaFile = out.resolve('schema.json')
        CliParity.cli(['schema', 'infer',
                       '--name', input.fileName.toString(),
                       '--description', 'parity fixture',
                       '--guid', guid,
                       input.toString(), schemaFile.toString()])
        final expected = comparable(new JsonSlurper().parse(schemaFile.toFile()) as Map)

        and: 'the port infers its own, with the CLI-parity comment setting'
        final actual = comparable(TabularSchemaInferrer.infer(
            input, guid, input.fileName.toString(), 'parity fixture',
            TabularSchemaInferrer.DEFAULT_SAMPLE_SIZE, 0, ''))
        final difference = difference(expected, actual)

        then:
        difference == null

        cleanup:
        out.deleteDir()

        where:
        table << TABLES
    }

    def 'the CLI stamps a version and a uuid the port deliberately does not'() {
        given: 'the two excluded fields, pinned so the exclusion stays this narrow'
        final input = Paths.get(TABLES[0])
        def out = Files.createTempDirectory('nf-fairscape-parity-schema-')
        final schemaFile = out.resolve('schema.json')

        when: 'the CLI is run twice with no --guid'
        CliParity.cli(['schema', 'infer', '--name', 'types.csv', '--description', 'd',
                       input.toString(), schemaFile.toString()])
        final fromCli = new JsonSlurper().parse(schemaFile.toFile()) as Map
        CliParity.cli(['schema', 'infer', '--name', 'types.csv', '--description', 'd',
                       input.toString(), schemaFile.toString()])
        final again = new JsonSlurper().parse(schemaFile.toFile()) as Map

        then: 'it mints a fresh id each time, so no port could reproduce it'
        fromCli['@id'] != again['@id']

        and: 'the port takes the id from its caller, which hashes the Dataset ARK'
        TabularSchemaInferrer.infer(input, 'ark:99999/x', 'types.csv', 'd')['@id'] == 'ark:99999/x'

        and: 'and the CLI stamps itself, which the port has no equivalent of'
        fromCli.containsKey('fairscapeVersion')
        !TabularSchemaInferrer.infer(input, 'ark:99999/x', 'types.csv', 'd')
            .containsKey('fairscapeVersion')

        cleanup:
        out.deleteDir()
    }

    def 'the comment-preamble divergence is deliberate and is the only one'() {
        given: 'a MultiQC-style table, the shape nf-core pipelines publish by the dozen'
        def dir = Files.createTempDirectory('nf-fairscape-parity-comment-')
        final input = dir.resolve('table_mqc.tsv')
        input.text = "# id: 'contigs_length_statistics'\n# section_name: 'Contigs'\nsample\tlength\nA\t100\nB\t200\n"
        final schemaFile = dir.resolve('schema.json')

        when:
        CliParity.cli(['schema', 'infer', '--name', 'table_mqc.tsv', '--description', 'd',
                       '--guid', 'ark:99999/schema-mqc', input.toString(), schemaFile.toString()])
        final fromCli = new JsonSlurper().parse(schemaFile.toFile()) as Map

        then: 'the CLI describes the file as one column named after a comment'
        fromCli['properties'].keySet() == ["# id: 'contigs_length_statistics'"] as Set

        and: 'the port, by default, reads the real header'
        TabularSchemaInferrer.infer(input, 'ark:99999/schema-mqc', 'table_mqc.tsv', 'd')
            .properties.keySet() == ['sample', 'length'] as Set

        and: 'turning the setting off restores CLI parity exactly'
        difference(comparable(fromCli), comparable(TabularSchemaInferrer.infer(
            input, 'ark:99999/schema-mqc', 'table_mqc.tsv', 'd',
            TabularSchemaInferrer.DEFAULT_SAMPLE_SIZE, 0, ''))) == null
        // the schema document is small enough to render in a failure message

        cleanup:
        dir.deleteDir()
    }

    /** The schema document minus the two fields that cannot agree by design. */
    private static Map comparable(Map schema) {
        final copy = new LinkedHashMap(schema)
        copy.remove('fairscapeVersion')
        copy.remove('@id')
        return copy
    }

    /** Null when the two schemas agree, else only the fields that do not. */
    private static String difference(Map expected, Map actual) {
        final problems = new ArrayList<String>()
        for( final key : ((expected.keySet() + actual.keySet()) as Set).sort() ) {
            if( expected[key] != actual[key] )
                problems << "  ${key}:\n    cli    ${expected[key]}\n    plugin ${actual[key]}".toString()
        }
        return problems ? "diverged from fairscape-cli ${CliParity.version()}:\n" + problems.join('\n') : null
    }
}
