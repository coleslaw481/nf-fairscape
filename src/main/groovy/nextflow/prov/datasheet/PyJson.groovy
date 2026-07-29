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

import groovy.transform.CompileDynamic

/**
 * JSON writer matching Python's `json.dump(obj, f, indent=N)` byte for byte:
 * two-space nesting, ", " / ": " separators, `{}` / `[]` for empties, and
 * ensure_ascii escaping of non-ASCII characters.
 *
 * Keeping the sidecar JSON identical to the CLI's is what makes the
 * Groovy/Python outputs directly diffable in the plugin's tests.
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class PyJson {

    static String dumps(Object value, int indent = 2) {
        final out = new StringBuilder()
        write(value, out, indent, 0)
        return out.toString()
    }

    /** `json.dumps(value)` with its default `(', ', ': ')` separators. */
    static String dumpsCompact(Object value) {
        final out = new StringBuilder()
        writeCompact(value, out)
        return out.toString()
    }

    private static void writeCompact(Object value, StringBuilder out) {
        if( value instanceof Map ) {
            out.append('{')
            boolean first = true
            ((Map) value).each { key, item ->
                if( !first ) out.append(', ')
                first = false
                out.append(quote(String.valueOf(key))).append(': ')
                writeCompact(item, out)
            }
            out.append('}')
            return
        }
        if( value instanceof Collection || value instanceof Object[] ) {
            final list = value instanceof Object[] ? Arrays.asList(value) : (Collection) value
            out.append('[')
            boolean first = true
            for( final item : list ) {
                if( !first ) out.append(', ')
                first = false
                writeCompact(item, out)
            }
            out.append(']')
            return
        }
        write(value, out, 0, 0)
    }

    private static void write(Object value, StringBuilder out, int indent, int level) {
        if( value == null ) {
            out.append('null')
            return
        }
        if( value instanceof Map ) {
            writeMap((Map) value, out, indent, level)
            return
        }
        if( value instanceof Collection || value instanceof Object[] ) {
            writeList(value instanceof Object[] ? Arrays.asList(value) : (Collection) value, out, indent, level)
            return
        }
        if( value instanceof Boolean ) {
            out.append(value ? 'true' : 'false')
            return
        }
        if( value instanceof Number ) {
            out.append(number((Number) value))
            return
        }
        out.append(quote(String.valueOf(value)))
    }

    private static void writeMap(Map map, StringBuilder out, int indent, int level) {
        if( map.isEmpty() ) {
            out.append('{}')
            return
        }
        final pad = ' ' * (indent * (level + 1))
        final closePad = ' ' * (indent * level)
        out.append('{\n')
        boolean first = true
        map.each { key, item ->
            if( !first )
                out.append(',\n')
            first = false
            out.append(pad).append(quote(String.valueOf(key))).append(': ')
            write(item, out, indent, level + 1)
        }
        out.append('\n').append(closePad).append('}')
    }

    private static void writeList(Collection list, StringBuilder out, int indent, int level) {
        if( list.isEmpty() ) {
            out.append('[]')
            return
        }
        final pad = ' ' * (indent * (level + 1))
        final closePad = ' ' * (indent * level)
        out.append('[\n')
        boolean first = true
        for( final item : list ) {
            if( !first )
                out.append(',\n')
            first = false
            out.append(pad)
            write(item, out, indent, level + 1)
        }
        out.append('\n').append(closePad).append(']')
    }

    private static String number(Number value) {
        if( value instanceof Double || value instanceof Float ) {
            final d = ((Number) value).doubleValue()
            // Locale.ROOT-safe via CrateJson.fixed: the default-locale
            // String.format here once meant a de_DE JVM emitted `1,0` -- invalid JSON
            return d == Math.rint(d) && !Double.isInfinite(d) ? CrateJson.fixed(d, 1) : String.valueOf(d)
        }
        return value.toString()
    }

    static String quote(String value) {
        final out = new StringBuilder('"')
        for( int i = 0; i < value.length(); i++ ) {
            final char ch = value.charAt(i)
            switch( ch ) {
                case '"' as char:  out.append('\\"'); break
                case '\\' as char: out.append('\\\\'); break
                case '\n' as char: out.append('\\n'); break
                case '\r' as char: out.append('\\r'); break
                case '\t' as char: out.append('\\t'); break
                case '\b' as char: out.append('\\b'); break
                case '\f' as char: out.append('\\f'); break
                default:
                    if( ch < 0x20 || ch > 0x7e )
                        out.append(String.format('\\u%04x', (int) ch))
                    else
                        out.append(ch)
            }
        }
        return out.append('"').toString()
    }
}
