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

import nextflow.config.Manifest
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


    def 'should slug an ARK from the bare process name' () {
        expect: 'the workflow scope nf-core puts on every process name is dropped'
        FairscapeRenderer.bareName(name) == expected

        where:
        name                                                       | expected
        'FASTQC'                                                   | 'FASTQC'
        'NFCORE_DEMO:DEMO:FASTQC'                                  | 'FASTQC'
        'NFCORE_PAIRGENOMEALIGN:PAIRGENOMEALIGN:PAIRALIGN_M2O:ALIGNMENT_LASTDB' | 'ALIGNMENT_LASTDB'
        'NFCORE_DEMO:DEMO:MULTIQC (demo)'                          | 'MULTIQC (demo)'
        null                                                       | null
    }

    def 'two aliases of one module keep distinct ARKs' () {
        given: 'the same module included twice under different names'
        def script = '/modules/nf-core/fastqc/main.nf'

        when:
        def raw = FairscapeRenderer.mintArk('59853', 'software', FairscapeRenderer.bareName('NFCORE_RNASEQ:RNASEQ:FASTQC_RAW'),
            script + '#NFCORE_RNASEQ:RNASEQ:FASTQC_RAW')
        def trimmed = FairscapeRenderer.mintArk('59853', 'software', FairscapeRenderer.bareName('NFCORE_RNASEQ:RNASEQ:FASTQC_TRIM'),
            script + '#NFCORE_RNASEQ:RNASEQ:FASTQC_TRIM')

        then: 'the readable part names the alias and the hash still separates them'
        raw.startsWith('ark:59853/software-fastqc-raw-')
        trimmed.startsWith('ark:59853/software-fastqc-trim-')
        raw != trimmed

        and: 'minting again from the same source is stable, as -resume relies on'
        raw == FairscapeRenderer.mintArk('59853', 'software', FairscapeRenderer.bareName('NFCORE_RNASEQ:RNASEQ:FASTQC_RAW'),
            script + '#NFCORE_RNASEQ:RNASEQ:FASTQC_RAW')
    }

    def 'should strip the delimiters around a script block, not just the ends' () {
        given: 'an nf-core module body: a Groovy prologue, then the command'
        def source = """def args = task.ext.args ?: ''
\"\"\"
fastqc \$args \$reads
\"\"\""""

        when:
        def stripped = FairscapeRenderer.stripScriptDelimiters(source)

        then: 'no delimiter survives on either side'
        !stripped.contains('\"\"\"')
        stripped.contains("def args = task.ext.args ?: ''")
        stripped.contains('fastqc')
    }

    def 'should leave a body with no script block alone' () {
        expect:
        FairscapeRenderer.stripScriptDelimiters('exec:\n  println 1') == 'exec:\n  println 1'
        FairscapeRenderer.stripScriptDelimiters(null) == null
    }

    def 'a computation must not use what it generated' () {
        given: 'a run that publishes a file Nextflow wrote and fed to a task'
        def collated = ['@id': 'ark:59853/dataset-collated-versions-yml-1234567']
        def report = ['@id': 'ark:59853/dataset-report-html-7654321']

        when:
        def used = FairscapeRenderer.withoutGenerated([collated], [collated, report])

        then: 'the self edge that would close a cycle is gone'
        used == []

        and: 'genuine inputs are untouched'
        FairscapeRenderer.withoutGenerated([report], [collated]) == [report]
        FairscapeRenderer.withoutGenerated([report], []) == [report]
        FairscapeRenderer.withoutGenerated([], [collated]) == []
    }

    def 'a file outside the crate keeps an absolute URI, everything else is a localPath' () {
        expect: 'the value PathNormalizer produced decides which property can carry it'
        FairscapeRenderer.outsideLocator(normalized) == locator

        where:
        normalized                                  | locator
        // a work-dir intermediate, local or cloud: PathNormalizer strips the scheme
        // and the run-relative path is all the crate can honestly say
        'work/ab/cd12/out.bam'                      | ['localPath': 'work/ab/cd12/out.bam']
        // an absolute local path resolves on one machine only
        'file:///data/refs/genome.fa'               | ['localPath': '/data/refs/genome.fa']
        // absolute and resolvable for anyone: stays a contentUrl
        's3://ngi-igenomes/references/genome.fa'    | ['contentUrl': 's3://ngi-igenomes/references/genome.fa']
        'https://github.com/nf-core/demo/tree/1a2b' | ['contentUrl': 'https://github.com/nf-core/demo/tree/1a2b']
        'az://container/inputs/samples.csv'         | ['contentUrl': 'az://container/inputs/samples.csv']
        'gs://bucket/inputs/samples.csv'            | ['contentUrl': 'gs://bucket/inputs/samples.csv']
        // nothing to say
        null                                        | [:]
        ''                                          | [:]
    }

    def 'should read the author from manifest contributors' () {
        given:
        def manifest = new Manifest(contributors: [
            [name: 'Ada Lovelace', contribution: ['author', 'maintainer']],
            [name: 'Someone Else', contribution: ['contributor']],
        ])

        expect: 'only the credited author is named'
        FairscapeRenderer.manifestContributors(manifest) == 'Ada Lovelace'
    }

    def 'should name every contributor when none is credited as author' () {
        given:
        def manifest = new Manifest(contributors: [
            [name: 'Ada Lovelace', contribution: ['maintainer']],
            [name: 'Grace Hopper', contribution: ['contributor']],
        ])

        expect:
        FairscapeRenderer.manifestContributors(manifest) == 'Ada Lovelace, Grace Hopper'
    }

    def 'should fall through when there are no contributors' () {
        expect:
        FairscapeRenderer.manifestContributors(new Manifest([:])) == null
    }

}
