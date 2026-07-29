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

import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileDynamic

/**
 * Port of fairscape_cli.datasheet_builder.evidence_graph.html_builder.
 *
 * Inlines the vendored React/ReactDOM/dagre bundles, the viewer application
 * script and the evidence graph itself into one offline HTML document, so the
 * generated page has no CDN dependency.
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class EvidenceGraphHtml {

    private static final List<String> VENDOR_FILES = [
        'evidence_graph/vendor/react.production.min.js',
        'evidence_graph/vendor/react-dom.production.min.js',
        'evidence_graph/vendor/dagre.min.js',
    ]

    static Path write(Map evidenceGraph, Path outputPath) {
        if( outputPath.parent != null )
            Files.createDirectories(outputPath.parent)
        Files.write(outputPath, render(evidenceGraph).getBytes('UTF-8'))
        return outputPath
    }

    static String render(Map evidenceGraph) {
        // read verbatim: only the .j2 shell goes through Jinja's newline trimming
        final vendorJs = VENDOR_FILES.collect { CrateJson.resource(it) }.join(';\n')
        final appJs = CrateJson.resource('evidence_graph/evidence_graph.js')

        // Escaping '<' keeps the JSON valid JS while preventing a '</script>' (or
        // '<!--') inside metadata values from terminating the script tag.
        final graphJson = PyJson.dumpsCompact(evidenceGraph).replace('<', '\\u003c')

        return MiniJinja.render(CrateJson.template('evidence_graph/evidence_graph.html.j2'), [
            title    : 'Evidence Graph Visualization',
            vendor_js: inlineSafe(vendorJs),
            graph_json: graphJson,
            app_js   : inlineSafe(appJs),
        ])
    }

    /** Make JS safe to inline in a <script> tag (no '</script>' breakout). */
    private static String inlineSafe(String js) {
        return js.replace('</script', '<\\/script')
    }
}
