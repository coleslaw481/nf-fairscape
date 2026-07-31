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

package nextflow.prov.util

import java.nio.file.Files

import spock.lang.Specification

class VersionsYamlTest extends Specification {

    def 'should read the tool version an nf-core module reports' () {
        expect: 'the shape the nf-core module template writes'
        VersionsYaml.parse('''"NFCORE_DEMO:DEMO:FASTQC":
    fastqc: 0.12.1
''') == [fastqc: '0.12.1']
    }

    def 'should read every tool under every process key' () {
        when:
        def versions = VersionsYaml.parse('''"NFCORE_DEMO:DEMO:SAMTOOLS_SORT":
    samtools: 1.21
    htslib: "1.21"
''')

        then: 'quoted versions lose their quotes, since YAML needed them to stay strings'
        versions == [samtools: '1.21', htslib: '1.21']

        and: 'several tools are named so the version string stays unambiguous'
        VersionsYaml.format(versions) == 'samtools 1.21, htslib 1.21'
    }

    def 'a single tool contributes just its version' () {
        expect:
        VersionsYaml.format([fastqc: '0.12.1']) == '0.12.1'
        VersionsYaml.format([:]) == null
        VersionsYaml.format(null) == null
    }

    def 'should ignore anything that is not a version pair' () {
        expect: 'a malformed file leaves the manifest fallback in place'
        VersionsYaml.parse('') == [:]
        VersionsYaml.parse(null) == [:]
        VersionsYaml.parse('not yaml at all') == [:]
        VersionsYaml.parse('"PROCESS":\n') == [:]
        VersionsYaml.parse('# just a comment\n') == [:]
    }

    def 'should read a versions file from disk and tolerate a missing one' () {
        given:
        def file = Files.createTempFile('versions', '.yml')
        file.text = '"NFCORE_DEMO:DEMO:SEQTK_TRIM":\n    seqtk: 1.4-r122\n'

        expect:
        VersionsYaml.read(file) == [seqtk: '1.4-r122']
        VersionsYaml.read(file.resolveSibling('nope.yml')) == [:]
        VersionsYaml.read(null) == [:]

        cleanup:
        Files.deleteIfExists(file)
    }
}
