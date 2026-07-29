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
import spock.lang.Unroll

/**
 * Pins the Jinja2 subset the vendored datasheet templates rely on. Each
 * expectation is the output Jinja2 produces with
 * `Environment(trim_blocks=True, lstrip_blocks=True)`, which is how
 * fairscape-cli renders them.
 */
class MiniJinjaTest extends Specification {

    @Unroll
    def 'should render expression #expr'() {
        expect:
        MiniJinja.render("{{ ${expr} }}", context) == expected

        where:
        expr                            | context                                  || expected
        'name'                          | [name: 'crate']                          || 'crate'
        'missing'                       | [:]                                      || ''
        'item.name'                     | [item: [name: 'x']]                      || 'x'
        'item.size'                     | [item: [size: '2 KB']]                   || '2 KB'
        'item.missing'                  | [item: [:]]                              || ''
        'a.b.c'                         | [a: [b: [c: 1]]]                         || '1'
        'count'                         | [count: 3]                               || '3'
        'ratio'                         | [ratio: 75.0d]                           || '75.0'
        'ratio|int'                     | [ratio: 75.6d]                           || '75'
        '(ratio * 3.393)|round(1)'      | [ratio: 60.7d]                           || '206.0'
        '(ratio * 3.393)|round(1)'      | [ratio: 57.1d]                           || '193.7'
        'words|join(", ")'              | [words: ['a', 'b']]                      || 'a, b'
        'name|lower'                    | [name: 'CRATE']                          || 'crate'
        'formats.keys()|join(" ")'      | [formats: [txt: 2, csv: 1]]              || 'txt csv'
        'text | safe'                   | [text: '<b>x</b>']                       || '<b>x</b>'
        'size if size else "—"'         | [size: null]                             || '—'
        'size if size else "—"'         | [size: '1 KB']                           || '1 KB'
        'a + b'                         | [a: 2, b: 3]                             || '5'
    }

    @Unroll
    def 'should evaluate condition #cond'() {
        expect:
        MiniJinja.render("{% if ${cond} %}yes{% else %}no{% endif %}", context) == expected

        where:
        cond                                  | context                       || expected
        'flag'                                | [flag: true]                  || 'yes'
        'value'                               | [value: '']                   || 'no'
        'value'                               | [value: 0]                    || 'no'
        'items'                               | [items: []]                   || 'no'
        'not items'                           | [items: []]                   || 'yes'
        'a and b'                             | [a: 'x', b: 'y']              || 'yes'
        'a or b'                              | [a: '', b: 'y']               || 'yes'
        'count and count != "0"'              | [count: '0']                  || 'no'
        'count and count != "0"'              | [count: '4']                  || 'yes'
        'doi.startswith("http")'              | [doi: 'https://doi.org/10']   || 'yes'
        'link.endswith(".html")'              | [link: 'graph.html']          || 'yes'
        "'@' in contact"                      | [contact: 'a@b.org']          || 'yes'
        "'@' in contact"                      | [contact: 'https://b.org']    || 'no'
        'keywords is string'                  | [keywords: 'a,b']             || 'yes'
        'keywords is string'                  | [keywords: ['a']]             || 'no'
        'irb is mapping'                      | [irb: [name: 'x']]            || 'yes'
        'irb is mapping'                      | [irb: 'N/A']                  || 'no'
        'n > 0'                               | [n: 2]                        || 'yes'
        'missing.deeply.nested'               | [:]                           || 'no'
    }

    def 'should support python subscripts and slices'() {
        // {{ pub[4:] }} in subcrates.html silently rendered '' before slices existed
        expect:
        MiniJinja.render(src, ctx) == expected

        where:
        src                            | ctx                     || expected
        '{{ pub[4:] }}'                | [pub: 'doi:10.1234/x']  || '10.1234/x'
        '{{ pub[0] }}'                 | [pub: 'doi:10.1234/x']  || 'd'
        '{{ pub[:3] }}'                | [pub: 'doi:10.1234/x']  || 'doi'
        '{{ items[-1] }}'              | [items: ['a', 'b']]     || 'b'
        '{{ items[1:]|join(",") }}'    | [items: ['a', 'b', 'c']]|| 'b,c'
        '{{ missing[4:] }}'            | [:]                     || ''
    }

    def 'should iterate with loop variables'() {
        expect:
        MiniJinja.render('{% for x in items %}{{ loop.index }}:{{ x }}{% if not loop.last %}, {% endif %}{% endfor %}',
            [items: ['a', 'b', 'c']]) == '1:a, 2:b, 3:c'
    }

    def 'should unpack map entries in a for loop'() {
        expect:
        MiniJinja.render('{% for k, v in formats.items() %}{{ k }} ({{ v }}){% if not loop.last %}, {% endif %}{% endfor %}',
            [formats: [txt: 4, csv: 1]]) == 'txt (4), csv (1)'
    }

    def 'should support set and comments'() {
        expect:
        MiniJinja.render('{# hidden #}{% set total = a %}{{ total }}', [a: 7]) == '7'
    }

    def 'should apply trim_blocks and lstrip_blocks like the CLI environment'() {
        given: 'block tags own their whole line; a tag mid-line does not'
        final template = '''<ul>
    {% for x in items %}
    <li>{{ x }}</li>
    {% endfor %}
</ul>
<p>{{ label }}   {% if label %}!{% endif %}</p>'''

        expect:
        MiniJinja.render(template, [items: ['a'], label: 'x']) == '''<ul>
    <li>a</li>
</ul>
<p>x   !</p>'''
    }

    def 'should render a null as empty and a boolean the way Python does'() {
        expect:
        MiniJinja.render('[{{ missing }}][{{ flag }}]', [flag: true]) == '[][True]'
    }
}
