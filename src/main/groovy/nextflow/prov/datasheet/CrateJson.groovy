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

import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic

/**
 * Shared RO-Crate reading helpers, ported from fairscape-cli's
 * utils/rocrate_helpers.py and conversion/mapping/subcrate_utils.py.
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class CrateJson {

    static Map read(Path metadataFile) {
        final text = new String(Files.readAllBytes(metadataFile), 'UTF-8')
        return new JsonSlurper().parseText(text) as Map
    }

    static List<Map> graphOf(Map crate) {
        return (crate?.get('@graph') ?: []) as List<Map>
    }

    /**
     * Recursively copy parsed JSON so downstream mutation cannot reach the
     * caller's structure. Condensation rewrites `usedDataset` on the nodes it
     * collapses, which must never leak back into the crate itself.
     */
    static Object deepCopy(Object value) {
        if( value instanceof Map ) {
            final out = new LinkedHashMap()
            ((Map) value).each { k, v -> out[k] = deepCopy(v) }
            return out
        }
        if( value instanceof List )
            return ((List) value).collect { deepCopy(it) }
        return value
    }

    /**
     * Resolution order: descriptor `about` reference, then the first entity
     * typed as an ROCrate, then graph[1] (get_root_entity_dict).
     */
    static Map rootEntity(List<Map> graph) {
        if( !graph )
            return null

        Map descriptor = graph.find { it['@id'] == 'ro-crate-metadata.json' }
        if( descriptor == null )
            descriptor = graph.find { (it['@id'] as String)?.endsWith('ro-crate-metadata.json') }

        if( descriptor != null ) {
            final about = descriptor['about']
            final aboutId = about instanceof Map ? about['@id'] : about
            if( aboutId ) {
                final root = graph.find { !it.is(descriptor) && it['@id'] == aboutId }
                if( root != null )
                    return root
            }
        }

        final byType = graph.find { isRoCrate(it['@type']) }
        if( byType != null )
            return byType

        return graph.size() > 1 ? graph[1] : null
    }

    static boolean isRoCrate(Object typeField) {
        return typeAsString(typeField).contains('ROCrate')
    }

    static String typeAsString(Object typeField) {
        if( typeField instanceof List )
            return ((List) typeField).collect { String.valueOf(it) }.join(' ')
        return typeField == null ? '' : String.valueOf(typeField)
    }

    /**
     * The EVI entity class of a graph node (subcrate_utils._normalize_type).
     * Order matters: the first match wins.
     */
    static String normalizeType(Map entity) {
        final s = typeAsString(entity?.get('@type'))
        if( !s )
            return 'Other'
        if( s.contains('Dataset') ) return 'Dataset'
        if( s.contains('Software') || s.contains('SoftwareSourceCode') ) return 'Software'
        if( s.contains('Instrument') ) return 'Instrument'
        if( s.contains('Sample') ) return 'Sample'
        if( s.contains('Experiment') ) return 'Experiment'
        if( s.contains('Computation') ) return 'Computation'
        if( s.contains('Schema') ) return 'Schema'
        return 'Other'
    }

    /**
     * The last @type entry, which is what AIReady._get_type inspects (so a root
     * typed ["Dataset", "…#ROCrate"] reads as ROCrate, not Dataset).
     */
    static String lastType(Map entity) {
        final t = entity?.get('@type') ?: entity?.get('metadataType')
        if( t instanceof List )
            return ((List) t).isEmpty() ? '' : String.valueOf(((List) t).last())
        return t == null ? '' : String.valueOf(t)
    }

    /** Resolve a {"@id": ...} reference, a bare string, or null. */
    static String refId(Object ref) {
        if( ref instanceof Map )
            return (ref['@id'] ?: ref['guid']) as String
        if( ref instanceof CharSequence )
            return ref.toString()
        return null
    }

    static List asList(Object value) {
        if( value == null )
            return []
        return value instanceof List ? (List) value : [value]
    }

    /**
     * Canonicalize a raw format value into dotless lowercase tokens
     * (subcrate_utils.normalize_formats).
     */
    static List<String> normalizeFormats(Object raw) {
        final out = []
        for( final item : asList(raw) ) {
            if( !(item instanceof CharSequence) )
                continue
            for( final part : item.toString().split('[/,;]') ) {
                def tok = part.trim()
                while( tok.startsWith('.') )
                    tok = tok.substring(1)
                tok = tok.trim().toLowerCase()
                if( tok && tok != 'unknown' )
                    out << tok
            }
        }
        return out
    }

    static String normalizeFormatStr(Object raw) {
        final tokens = normalizeFormats(raw)
        return tokens ? tokens.join(', ') : 'unknown'
    }

    /** Count occurrences preserving first-seen order, like collections.Counter. */
    static Map<String,Integer> counter(List<String> values) {
        final out = new LinkedHashMap<String,Integer>()
        for( final v : values )
            out[v] = (out[v] ?: 0) + 1
        return out
    }

    static String htmlEscape(String value) {
        // matches Python html.escape(quote=True)
        return value == null ? '' : value
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
            .replace("'", '&#x27;')
    }

    /**
     * A double with a fixed number of decimals, matching Python's `f'{v:.Nf}'`:
     * locale-independent (Java's `String.format('%f')` uses the default locale,
     * so `de_DE` would produce `75,0`) and rounded half-even on exact ties
     * (Java's `%f` rounds HALF_UP, so 12.5 at 0 decimals gives "13" where
     * Python gives "12").
     */
    static String fixed(double value, int decimals) {
        return new BigDecimal(value).setScale(decimals, java.math.RoundingMode.HALF_EVEN).toPlainString()
    }

    /** Human-readable size, matching summary_generator._format_size. */
    static String formatSize(long bytes) {
        if( bytes >= 1e12 ) return "${fixed(bytes / 1e12, 1)} TB"
        if( bytes >= 1e9 )  return "${fixed(bytes / 1e9, 1)} GB"
        if( bytes >= 1e6 )  return "${fixed(bytes / 1e6, 1)} MB"
        if( bytes >= 1e3 )  return "${fixed(bytes / 1e3, 1)} KB"
        return "${bytes} B"
    }

    /** Thousands separators, matching Python's format(n, ','). */
    static String thousands(Number value) {
        return String.format(Locale.ROOT, '%,d', value.longValue())
    }

    /** Parse "1.2 TB" / "500 MB" / raw bytes into a byte count. */
    static double parseContentSizeBytes(Object value) {
        if( value instanceof Number )
            return ((Number) value).doubleValue()
        if( !(value instanceof CharSequence) || !value.toString().trim() )
            return 0d
        final size = value.toString().trim().toUpperCase()
        try {
            for( final entry : [['TB', 1e12d], ['GB', 1e9d], ['MB', 1e6d], ['KB', 1e3d], ['B', 1d]] ) {
                final unit = entry[0] as String
                if( size.endsWith(unit) )
                    return Double.parseDouble(size.substring(0, size.length() - unit.length()).trim()) * (entry[1] as double)
            }
            return Double.parseDouble(size)
        }
        catch( NumberFormatException e ) {
            return 0d
        }
    }

    /** Read a bundled file from src/main/resources/fairscape/templates verbatim. */
    static String resource(String name) {
        final stream = CrateJson.class.getResourceAsStream("/fairscape/templates/${name}")
        if( stream == null )
            throw new IllegalStateException("Missing bundled template: ${name}")
        try {
            return new String(stream.bytes, 'UTF-8')
        }
        finally {
            stream.close()
        }
    }

    /**
     * Read a bundled template. Jinja's keep_trailing_newline defaults to false,
     * so one trailing newline is dropped from the source.
     */
    static String template(String name) {
        final text = resource(name)
        return text.endsWith('\n') ? text.substring(0, text.length() - 1) : text
    }
}
