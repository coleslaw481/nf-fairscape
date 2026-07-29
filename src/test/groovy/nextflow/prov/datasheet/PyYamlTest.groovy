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

import spock.lang.Specification

/**
 * Pins the emitter against PyYAML's `yaml.dump` defaults. Every expected string
 * here was produced by running `yaml.dump` on the equivalent Python structure,
 * so a failure means the port has drifted from what `fairscape build linkml`
 * writes.
 *
 * @author FAIRSCAPE
 */
class PyYamlTest extends Specification {

    def 'sorts keys and writes block style'() {
        expect:
        PyYaml.dump([b: 'two', a: 'one', c: 'three']) == 'a: one\nb: two\nc: three\n'
    }

    def 'writes a sequence at the mapping indent, not one level in'() {
        expect:
        PyYaml.dump([keywords: ['cm4ai', 'hpa']]) == 'keywords:\n- cm4ai\n- hpa\n'
    }

    def 'writes a list of description objects the way the D4D structure needs'() {
        expect:
        PyYaml.dump([known_biases: [[description: 'short text']]]) ==
            'known_biases:\n- description: short text\n'
    }

    def 'folds a long scalar at column 80 with a two-space continuation'() {
        given:
        final text = 'Cell Maps pipeline (idekerlab cellmaps_* tools) executed with Nextflow and nf-fairscape; see https://github.com/idekerlab/cellmaps_pipeline'

        expect:
        PyYaml.dump([citation: text]) == '''\
citation: Cell Maps pipeline (idekerlab cellmaps_* tools) executed with Nextflow and
  nf-fairscape; see https://github.com/idekerlab/cellmaps_pipeline
'''
    }

    def 'folds a nested description at a four-space continuation'() {
        given:
        final text = 'Both inputs come from immortalized cancer cell lines and therefore do not represent the biological variation of primary human tissue.'

        expect:
        PyYaml.dump([known_biases: [[description: text]]]) == '''\
known_biases:
- description: Both inputs come from immortalized cancer cell lines and therefore
    do not represent the biological variation of primary human tissue.
'''
    }

    def 'single-quotes a scalar carrying a colon-space, and doubles inner quotes'() {
        expect:
        PyYaml.dump([a: 'note: this is ambiguous']) == "a: 'note: this is ambiguous'\n"
        PyYaml.dump([a: "it's fine"]) == "a: it's fine\n"
        PyYaml.dump([a: "'leading quote"]) == "a: '''leading quote'\n"
    }

    def 'quotes a string that would otherwise read back as another type'() {
        expect:
        PyYaml.dump([a: value]) == "a: ${expected}\n".toString()

        where:
        value      || expected
        '1331'     || "'1331'"
        'true'     || "'true'"
        'null'     || "'null'"
        '2019-05-06' || "'2019-05-06'"
        '1.5'      || "'1.5'"
        'plain'    || 'plain'
        'ark:59853/rocrate-x'          || 'ark:59853/rocrate-x'
        'https://spdx.org/licenses/X'  || 'https://spdx.org/licenses/X'
    }

    def 'writes real numbers and booleans bare'() {
        expect:
        PyYaml.dump([bytes: 991533465L]) == 'bytes: 991533465\n'
        PyYaml.dump([flag: true]) == 'flag: true\n'
    }

    def 'writes empty collections in flow style like PyYAML, not Groovy toString'() {
        expect:
        PyYaml.dump([keywords: []]) == 'keywords: []\n'
        PyYaml.dump([extra: [:]]) == 'extra: {}\n'
        PyYaml.dump([:]) == '{}\n'
    }

    def 'escapes a newline so a multi-line scalar round-trips'() {
        // deliberate deviation from PyYAML (which single-quotes with blank-line
        // folding): double-quoted keeps the newline as \n instead of losing it
        expect:
        PyYaml.dump([description: 'line one\nline two']) == 'description: "line one\\nline two"\n'
    }

    def 'escapes a non-BMP character as one code point, not a surrogate pair'() {
        expect:
        PyYaml.dump([description: 'hi 😀 there']) == 'description: "hi \\U0001F600 there"\n'
    }

    def 'double-quotes and folds a scalar with non-ascii, since allow_unicode is off'() {
        given:
        final text = 'A three-step demonstration pipeline that builds a letter list, reverses it, and splits it in half — a full provenance chain packaged as a FAIRSCAPE EVI RO-Crate.'

        expect:
        PyYaml.dump([description: text]) == '''\
description: "A three-step demonstration pipeline that builds a letter list, reverses\\
  \\ it, and splits it in half \\u2014 a full provenance chain packaged as a FAIRSCAPE\\
  \\ EVI RO-Crate."
'''
    }
}
