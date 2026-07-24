/*
 * Copyright 2026, FAIRSCAPE (nf-fairscape fork)
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

package nextflow.prov.renderers

import spock.lang.Specification

class FairscapeRendererTest extends Specification {

    def 'should mint valid deterministic ARKs' () {
        when:
        def ark1 = FairscapeRenderer.mintArk('59853', 'dataset', 'output file.txt', '/work/ab/12/output file.txt')
        def ark2 = FairscapeRenderer.mintArk('59853', 'dataset', 'output file.txt', '/work/ab/12/output file.txt')
        def ark3 = FairscapeRenderer.mintArk('59853', 'dataset', 'output file.txt', '/work/cd/34/output file.txt')
        then:
        ark1 ==~ /^ark:[0-9]{5}\/[a-zA-Z0-9_\-]+$/
        ark1 == ark2
        ark1 != ark3
        ark1.startsWith('ark:59853/dataset-output-file-txt-')
    }

    def 'should slugify names' () {
        expect:
        FairscapeRenderer.slugify(name) == slug

        where:
        name                    | slug
        'FetchSequences (1)'    | 'fetchsequences-1'
        'Übung/Straße.txt'      | 'bung-stra-e-txt'
        '---'                   | 'unnamed'
        null                    | 'unnamed'
        'a' * 50                | 'a' * 40
    }

    def 'should enforce minimum description length' () {
        expect:
        FairscapeRenderer.ensureDescription(value, 'A generated fallback description') == expected

        where:
        value        | expected
        null         | 'A generated fallback description'
        'too short'  | 'A generated fallback description'
        'this one is long enough' | 'this one is long enough'
    }

    def 'should extract fairscape metadata from the ext directive' () {
        expect:
        FairscapeRenderer.fairscapeExt(ext) == expected

        where:
        ext                                      | expected
        null                                     | [:]
        'not a map'                              | [:]
        [args: '--verbose']                      | [:]
        [fairscape: 'not a map']                 | [:]
        [fairscape: [softwareName: 'tac']]       | [softwareName: 'tac']
        [fairscape: [softwareName: 'tac', softwareVersion: '8.32']] | [softwareName: 'tac', softwareVersion: '8.32']
    }

    def 'should not warn for absent or valid ext.fairscape annotations' () {
        expect:
        FairscapeRenderer.fairscapeExtWarnings(ext).isEmpty()

        where:
        ext << [
            null,
            'not a map',
            [args: '--verbose'],                                   // no fairscape key
            [fairscape: [softwareName: 'tac', softwareKeywords: ['cli']]], // all known keys
        ]
    }

    def 'should warn when ext.fairscape is present but not a map' () {
        when:
        def warnings = FairscapeRenderer.fairscapeExtWarnings([fairscape: ['made-up-property']])
        then:
        warnings.size() == 1
        warnings[0].contains('must be a map')
        warnings[0].contains('ignored')
    }

    def 'should warn about unrecognized ext.fairscape keys' () {
        when:
        def warnings = FairscapeRenderer.fairscapeExtWarnings([fairscape: [softwareName: 'tac', madeUpProperty: 'x']])
        then:
        warnings.size() == 1
        warnings[0].contains('unrecognized')
        warnings[0].contains('madeUpProperty')
        warnings[0].contains('supported keys')
    }

    def 'should coerce keywords into a list of strings' () {
        expect:
        FairscapeRenderer.asStringList(value) == expected

        where:
        value               | expected
        null                | null
        'cli'               | ['cli']
        ['cli', 'genomics'] | ['cli', 'genomics']
    }

}
