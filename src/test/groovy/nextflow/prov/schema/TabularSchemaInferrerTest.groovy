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

package nextflow.prov.schema

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import spock.lang.Specification

/**
 * Pins the port against `fairscape-cli schema infer`. Every expectation here was
 * produced by running the CLI on the same fixture and reading back the emitted
 * schema, so a failure means the Groovy port and frictionless have diverged.
 *
 * @author FAIRSCAPE
 */
class TabularSchemaInferrerTest extends Specification {

    private Path fixture(String name) {
        return Paths.get(getClass().getResource("/tabular/${name}").toURI())
    }

    private static Map typesOf(Map schema) {
        return schema.properties.collectEntries { name, property ->
            [(name): [property['type'], property['source-type']]]
        } as Map
    }

    def 'detects every frictionless candidate type the CLI does'() {
        when:
        final schema = TabularSchemaInferrer.infer(fixture('types.csv'), 'ark:99999/schema-types', 'types.csv', 'a description')

        then:
        typesOf(schema) == [
            i     : ['integer', null],
            n     : ['number', null],
            b01   : ['integer', null],          // 1/0 is an integer before it is a boolean
            btf   : ['boolean', null],
            yr    : ['integer', null],          // integer outranks year in the candidate list
            dt    : ['string', 'date'],
            dtm   : ['string', 'datetime'],
            tm    : ['string', 'time'],
            ym    : ['string', 'yearmonth'],
            dur   : ['string', 'duration'],
            arr   : ['array', null],
            obj   : ['object', null],
            gp    : ['array', 'geopoint'],
            s     : ['string', null],
            empty : ['string', 'any'],          // an all-empty column never scores
            mixed : ['string', null],
        ]

        and: 'the document is shaped the way the CLI writes it'
        schema['@id'] == 'ark:99999/schema-types'
        schema['@type'] == 'EVI:Schema'
        schema['EVI:schemaType'] == 'tabular'
        schema['separator'] == ','
        schema['header'] == true
        schema['type'] == 'object'
        schema['conformsTo'] == ['@id': 'https://json-schema.org/draft/2020-12/schema']
        schema['required'] == (schema.properties as Map).keySet() as List
        schema.properties['i'] == [description: 'Column i', index: 0, type: 'integer']
    }

    def 'reads RFC-4180 quoting: embedded delimiters, doubled quotes, newlines in fields'() {
        when:
        final records = TabularSchemaInferrer.readRecords(fixture('quoted.csv'), ',' as char, 10)

        then:
        records == [
            ['name', 'note', 'num'],
            ['a,b', 'he said "hi"', '1'],
            ['c', 'multi\nline', '2'],
            ['d', 'plain', '3'],
        ]
    }

    def 'an all-empty column falls back to any, a partially populated one still scores'() {
        when:
        final schema = TabularSchemaInferrer.infer(fixture('sparse.tsv'), 'ark:99999/schema-sparse', 'sparse.tsv', 'a description')

        then:
        schema['separator'] == '\t'
        typesOf(schema) == [a: ['integer', null], b: ['string', 'any'], c: ['string', 'any']]
    }

    def 'blank and duplicate header labels get frictionless names'() {
        expect:
        TabularSchemaInferrer.fieldNames(['x', 'x', '', 'y'], [['1', '2', '3', '4']]) == ['x', 'x2', 'field3', 'y']
        TabularSchemaInferrer.fieldNames([], [['1', '2']]) == ['field1', 'field2']
    }

    def 'a header with no data rows types every column as any'() {
        expect:
        TabularSchemaInferrer.detectTypes(['a', 'b'], []) == ['any', 'any']
    }

    def 'cell readers agree with the frictionless field readers'() {
        expect:
        TabularSchemaInferrer.readable(type, cell) == expected

        where:
        type       | cell                  || expected
        'integer'  | '42'                  || true
        'integer'  | '+42'                 || true
        'integer'  | '4.2'                 || false
        'integer'  | ''                    || true      // missing values never score against a runner
        'number'   | '-2.5e3'              || true
        'number'   | 'inf'                 || true
        'number'   | 'abc'                 || false
        'boolean'  | 'TRUE'                || true
        'boolean'  | 'yes'                 || false
        'year'     | '2019'                || true
        'year'     | '19'                  || false     // YearField requires exactly four characters
        'yearmonth'| '2019-05'             || true
        'yearmonth'| '2019-13'             || false
        'yearmonth'| '2019-05-06'          || false
        'date'     | '2019-05-06'          || true
        'date'     | '2019-02-30'          || false
        'datetime' | '2019-05-06T10:11:12' || true
        'datetime' | '2019-05-06T10:11'    || false     // shorter than 19 characters
        'time'     | '10:11:12'            || true
        'duration' | 'P3Y6M4DT12H30M5S'    || true
        'duration' | 'P'                   || false
        'geopoint' | '10.5,20.5'           || true
        'geopoint' | '200,20'              || false     // longitude out of range
        'array'    | '[1,2]'               || true
        'array'    | '{"a":1}'             || false
        'object'   | '{"a":1}'             || true
        'geojson'  | '{"type":"Point","coordinates":[1,2]}' || true
        'geojson'  | '{"type":"Point"}'    || false
        'string'   | 'anything'            || true
    }

    def 'a trailing run of same-typed columns collapses into one spanning array'() {
        given:
        Path file = Files.createTempFile('embedding', '.tsv')
        final header = (['id'] + (0..<8).collect { it.toString() }).join('\t')
        final row = (['GENE'] + (0..<8).collect { '0.1' }).join('\t')
        file.text = "${header}\n${row}\n"

        when:
        final schema = TabularSchemaInferrer.infer(file, 'ark:99999/schema-emd', 'emd.tsv', 'a description', 100, 4)

        then:
        (schema.properties as Map).keySet() as List == ['id', 'values']
        schema.properties['id'].index == 0
        schema.properties['values'].index == '1::'
        schema.properties['values'].type == 'array'
        schema.properties['values'].items == [type: 'number']
        schema.properties['values']['min-items'] == 8
        schema.properties['values']['max-items'] == 8
        schema['required'] == ['id', 'values']

        and: 'the collapse is opt-in'
        (TabularSchemaInferrer.infer(file, 'ark:99999/schema-emd', 'emd.tsv', 'a description').properties as Map).size() == 9

        cleanup:
        Files.deleteIfExists(file)
    }

    def 'only csv and tsv are supported'() {
        expect:
        TabularSchemaInferrer.supports(Paths.get('/tmp/a.tsv'))
        TabularSchemaInferrer.supports(Paths.get('/tmp/a.CSV'))
        !TabularSchemaInferrer.supports(Paths.get('/tmp/a.parquet'))
        !TabularSchemaInferrer.supports(Paths.get('/tmp/a'))
    }

    def 'should skip a MultiQC comment preamble' () {
        given: 'the shape nf-core modules publish for MultiQC custom content'
        def file = Files.createTempFile('mqc', '.tsv')
        file.text = """# id: 'contigs_length_statistics'
# section_name: 'Contig length statistics'
# plot_type: 'table'
Sample\tTotalLength\tContigs
MT192765\t29903\t1
OY074094\t29782\t2
"""

        when:
        def schema = TabularSchemaInferrer.infer(file, 'ark:99999/schema-mqc', 'contig_length_mqc.tsv', 'a description')

        then: 'the real header is the header, not the first comment'
        (schema.properties as Map).keySet() as List == ['Sample', 'TotalLength', 'Contigs']
        schema.properties['TotalLength'].type == 'integer'

        and: 'skipping is off when no comment character is configured'
        def raw = TabularSchemaInferrer.infer(file, 'ark:99999/schema-mqc', 'x.tsv', 'a description', 100, 0, '')
        (raw.properties as Map).keySet() as List == ["# id: 'contigs_length_statistics'"]

        cleanup:
        Files.deleteIfExists(file)
    }

    def 'should keep a hash-prefixed header that is a real header' () {
        given: 'a BED-style table whose header line starts with #'
        def file = Files.createTempFile('bed', '.tsv')
        file.text = "#chrom\tstart\tend\nchr1\t100\t200\nchr2\t300\t400\n"

        when:
        def schema = TabularSchemaInferrer.infer(file, 'ark:99999/schema-bed', 'regions.tsv', 'a description')

        then: 'it splits into as many fields as the data, so it is not a comment'
        (schema.properties as Map).keySet() as List == ['#chrom', 'start', 'end']

        cleanup:
        Files.deleteIfExists(file)
    }

    def 'should treat an all-comment file as having no rows' () {
        given:
        def file = Files.createTempFile('empty', '.tsv')
        file.text = "# only\n# comments\n"

        when:
        def schema = TabularSchemaInferrer.infer(file, 'ark:99999/schema-none', 'none.tsv', 'a description')

        then: 'nothing is claimed about a file with no table in it'
        (schema.properties as Map).isEmpty()

        cleanup:
        Files.deleteIfExists(file)
    }

    def 'dropComments leaves an uncommented table untouched' () {
        given:
        def records = [['a', 'b'], ['1', '2']]

        expect: 'the CLI-parity path is a no-op'
        TabularSchemaInferrer.dropComments(records, '#') === records
        TabularSchemaInferrer.dropComments(records, null) === records
    }
}
