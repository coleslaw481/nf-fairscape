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
 * Pins the port of `fairscape-cli augment link-inverses`. The counts and shapes
 * here were taken from running the CLI's `augment_rocrate_with_inverses` over
 * the same input, so a failure means the port and the entailment have diverged.
 *
 * @author FAIRSCAPE
 */
class InverseLinkerTest extends Specification {

    private static Map entity(String id, Map extra = [:]) {
        return ['@id': id] + extra
    }

    def 'fills generated on a computation from every generatedBy that names it'() {
        given: 'a computation that claims one output, and three files that claim it as their producer'
        final computation = entity('ark:1/comp', ['generated': [['@id': 'ark:1/dir']]])
        final graph = [
            computation,
            entity('ark:1/dir', ['generatedBy': [['@id': 'ark:1/comp']]]),
            entity('ark:1/file-a', ['generatedBy': [['@id': 'ark:1/comp']]]),
            entity('ark:1/file-b', ['generatedBy': [['@id': 'ark:1/comp']]]),
        ]

        when:
        final modified = InverseLinker.link(graph)

        then: 'the two files the computation did not list are added, without duplicating the one it did'
        computation['generated'] == [
            ['@id': 'ark:1/dir'],
            ['@id': 'ark:1/file-a'],
            ['@id': 'ark:1/file-b'],
        ]
        modified == 2
    }

    def 'links usedDataset and usedSoftware back to their subjects'() {
        given:
        final dataset = entity('ark:1/data')
        final software = entity('ark:1/tool')
        final graph = [
            entity('ark:1/comp', [
                'usedDataset' : [['@id': 'ark:1/data']],
                'usedSoftware': [['@id': 'ark:1/tool']],
            ]),
            dataset,
            software,
        ]

        when:
        InverseLinker.link(graph)

        then:
        dataset['datasetUsedBy'] == [['@id': 'ark:1/comp']]
        software['softwareUsedBy'] == [['@id': 'ark:1/comp']]
    }

    def 'is idempotent: a fully linked graph is left alone'() {
        given:
        final graph = [
            entity('ark:1/comp', ['generated': [['@id': 'ark:1/out']]]),
            entity('ark:1/out', ['generatedBy': [['@id': 'ark:1/comp']]]),
        ]

        when:
        final first = InverseLinker.link(graph)
        final second = InverseLinker.link(graph)

        then:
        first == 0
        second == 0
    }

    def 'ignores references that leave the crate and literals that are not references'() {
        given:
        final graph = [
            entity('ark:1/comp', [
                'generated'   : [['@id': 'https://example.org/elsewhere']],
                'usedSoftware': ['a bare string, not a reference'],
            ]),
        ]

        when:
        final modified = InverseLinker.link(graph)

        then:
        modified == 0
        graph[0].keySet() == ['@id', 'generated', 'usedSoftware'] as Set
    }

    def 'promotes a single reference to a list rather than overwriting it'() {
        given:
        final target = entity('ark:1/out', ['generatedBy': ['@id': 'ark:1/comp-a']])
        final graph = [
            entity('ark:1/comp-a', [:]),
            entity('ark:1/comp-b', ['generated': [['@id': 'ark:1/out']]]),
            target,
        ]

        when:
        InverseLinker.link(graph)

        then:
        target['generatedBy'] == [['@id': 'ark:1/comp-a'], ['@id': 'ark:1/comp-b']]
    }

    def 'addLink reports whether the entity actually changed'() {
        expect:
        InverseLinker.addLink(entity, 'generated', 'ark:1/x') == changed
        entity['generated'] == expected

        where:
        entity                                          || changed | expected
        ['@id': 'a']                                    || true    | [['@id': 'ark:1/x']]
        ['@id': 'a', 'generated': null]                 || true    | [['@id': 'ark:1/x']]
        ['@id': 'a', 'generated': ['@id': 'ark:1/x']]   || false   | ['@id': 'ark:1/x']
        ['@id': 'a', 'generated': [['@id': 'ark:1/x']]] || false   | [['@id': 'ark:1/x']]
        ['@id': 'a', 'generated': 'junk']               || true    | [['@id': 'ark:1/x']]
    }

    def 'carries every owl:inverseOf pair the EVI ontology declares'() {
        expect:
        InverseLinker.INVERSE_PAIRS.size() == 18
        ['generated', 'generatedBy'] in InverseLinker.INVERSE_PAIRS
        ['datasetUsedBy', 'usedDataset'] in InverseLinker.INVERSE_PAIRS
        ['softwareUsedBy', 'usedSoftware'] in InverseLinker.INVERSE_PAIRS
        ['derivedFrom', 'derivedTo'] in InverseLinker.INVERSE_PAIRS
        // sorted by the first property, so the augmented crate is byte-stable
        InverseLinker.INVERSE_PAIRS*.first() == InverseLinker.INVERSE_PAIRS*.first().sort(false)
    }
}
