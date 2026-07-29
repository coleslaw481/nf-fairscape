/*
 * Copyright 2026, FAIRSCAPE (nf-fairscape)
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

import spock.lang.Specification

class ContainerInspectorTest extends Specification {

    // Trimmed from real `docker image inspect --format '{{json .}}'` output.
    static final String PULLED = '''
        {"Id":"sha256:aaaa1111","RepoTags":["cm4ai/cellmaps_ppidownloader:0.2.2"],
         "RepoDigests":["cm4ai/cellmaps_ppidownloader@sha256:85b359d315901d71e1a"]}
    '''

    // An image built locally and never pushed: an id, but no repo digest at all.
    static final String LOCAL_ONLY = '''
        {"Id":"sha256:bbbb2222","RepoTags":["nf-cellmaps/ppidownloader:0.2.2"],"RepoDigests":[]}
    '''

    def 'should read digest and id from a pulled image' () {
        when:
        def result = ContainerInspector.parse('cm4ai/cellmaps_ppidownloader:0.2.2', PULLED)
        then:
        result.repoDigest == 'cm4ai/cellmaps_ppidownloader@sha256:85b359d315901d71e1a'
        result.imageId == 'sha256:aaaa1111'
    }

    def 'should report only an image id when the engine lists no repo digest' () {
        when:
        def result = ContainerInspector.parse('nf-cellmaps/ppidownloader:0.2.2', LOCAL_ONLY)
        then:
        // classic image store, never pushed: no repo digest exists to record
        !result.containsKey('repoDigest')
        result.imageId == 'sha256:bbbb2222'
    }

    def 'should keep both values when the containerd store makes them identical' () {
        given:
        // Real shape from `docker image inspect` under the containerd image
        // store, where Id IS the manifest digest — true for a pulled image AND
        // for one built locally, so equality here says nothing about whether
        // the image can be pulled from anywhere.
        def json = '''{"Id":"sha256:85b359d3","RepoTags":["cm4ai/cellmaps_ppidownloader:0.2.2"],
                       "RepoDigests":["cm4ai/cellmaps_ppidownloader@sha256:85b359d3"]}'''
        when:
        def result = ContainerInspector.parse('cm4ai/cellmaps_ppidownloader:0.2.2', json)
        then:
        result.repoDigest == 'cm4ai/cellmaps_ppidownloader@sha256:85b359d3'
        result.imageId == 'sha256:85b359d3'
    }

    def 'should accept inspect output wrapped in a list' () {
        when:
        def result = ContainerInspector.parse('img:1', '[' + PULLED + ']')
        then:
        result.imageId == 'sha256:aaaa1111'
    }

    def 'should return nothing rather than throw on unusable output' () {
        expect:
        ContainerInspector.parse('img:1', input) == [:]

        where:
        input << [null, '', '   ', 'not json', '[]', 'null']
    }

    def 'should prefer the digest whose repository matches the reference' () {
        given:
        def digests = ['other/mirror@sha256:dead', 'cm4ai/tool@sha256:beef']
        expect:
        ContainerInspector.pickDigest('cm4ai/tool:1.0', digests) == 'cm4ai/tool@sha256:beef'
        // no match -> still record something rather than nothing
        ContainerInspector.pickDigest('unrelated/thing:1.0', digests) == 'other/mirror@sha256:dead'
        ContainerInspector.pickDigest('cm4ai/tool:1.0', []) == null
        ContainerInspector.pickDigest('cm4ai/tool:1.0', null) == null
    }

    def 'should split repository from tag, digest and registry port' () {
        expect:
        ContainerInspector.repositoryOf(ref) == repo

        where:
        ref                                   | repo
        'cm4ai/tool:1.0'                      | 'cm4ai/tool'
        'cm4ai/tool'                          | 'cm4ai/tool'
        'cm4ai/tool@sha256:abc'               | 'cm4ai/tool'
        'cm4ai/tool:1.0@sha256:abc'           | 'cm4ai/tool'
        'registry.io:5000/cm4ai/tool:1.0'     | 'registry.io:5000/cm4ai/tool'
        'registry.io:5000/cm4ai/tool'         | 'registry.io:5000/cm4ai/tool'   // port is not a tag
        null                                  | null
    }

    def 'should pick the engine from whichever is enabled' () {
        expect:
        ContainerInspector.engineFor([docker: [enabled: true]]) == 'docker'
        ContainerInspector.engineFor([podman: [enabled: true]]) == 'podman'
        ContainerInspector.engineFor([docker: [enabled: false], podman: [enabled: true]]) == 'podman'
        // singularity/apptainer run image files; there is no daemon to ask
        ContainerInspector.engineFor([singularity: [enabled: true]]) == null
        ContainerInspector.engineFor([:]) == null
        ContainerInspector.engineFor(null) == null
    }

    def 'should cache one inspect call per distinct image' () {
        given:
        def calls = 0
        def inspector = new ContainerInspector('docker') {
            // no engine needed: count how often the uncached path is taken
        }
        when:
        def a = inspector.inspect('definitely-not-an-image:xyz')
        def b = inspector.inspect('definitely-not-an-image:xyz')
        then:
        // a missing image resolves to nothing, and the second call is cached
        a == [:]
        b.is(a)
    }
}
