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

import java.util.concurrent.ConcurrentHashMap

import groovy.transform.CompileDynamic

/**
 * Translates a Jinja2 expression into an equivalent Groovy expression that
 * {@link MiniJinja} can evaluate with a GroovyShell.
 *
 * Names resolve through helper closures rather than Groovy's own property
 * access so that Jinja semantics survive: an unknown name is null instead of a
 * MissingPropertyException, and `subcrate.size` reads the map key rather than
 * calling Map.size().
 *
 * <pre>
 *   subcrate.doi and subcrate.doi != "None"
 *     -> __attr(__var('subcrate'),'doi') &amp;&amp; __attr(__var('subcrate'),'doi') != 'None'
 *
 *   subcrate.file_formats.keys()|join(' ')|lower
 *     -> __filter(__filter(__m(__attr(__var('subcrate'),'file_formats'),'keys',[]),'join',[' ']),'lower',[])
 * </pre>
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class ExpressionTranslator {

    private static final Map<String,String> CACHE = new ConcurrentHashMap<>()

    private static final Map<String,String> TESTS = [
        'string'  : 'String',
        'mapping' : 'Map',
        'number'  : 'Number',
        'sequence': 'Collection',
    ]

    static String translate(String expr) {
        return CACHE.computeIfAbsent(expr, { String e -> compile(e) })
    }

    private static String compile(String expr) {
        final ternary = splitTernary(expr)
        if( ternary )
            return "((${compile(ternary[1])}) ? (${compile(ternary[0])}) : (${compile(ternary[2])}))"
        return scan(expr)
    }

    /**
     * Split `A if B else C` at the top level (outside quotes and parentheses).
     * Returns [A, B, C] or null when the expression is not a conditional.
     */
    private static List<String> splitTernary(String expr) {
        int depth = 0
        int ifAt = -1
        int elseAt = -1
        int i = 0
        while( i < expr.length() ) {
            final ch = expr.charAt(i)
            if( ch == '\'' as char || ch == '"' as char ) {
                i = skipString(expr, i)
                continue
            }
            if( ch == '(' as char || ch == '[' as char )
                depth++
            else if( ch == ')' as char || ch == ']' as char )
                depth--
            else if( depth == 0 && isWordStart(ch) ) {
                final end = wordEnd(expr, i)
                final word = expr.substring(i, end)
                if( word == 'if' && ifAt < 0 )
                    ifAt = i
                else if( word == 'else' && ifAt >= 0 && elseAt < 0 )
                    elseAt = i
                i = end
                continue
            }
            i++
        }
        if( ifAt < 0 || elseAt < 0 )
            return null
        return [
            expr.substring(0, ifAt).trim(),
            expr.substring(ifAt + 2, elseAt).trim(),
            expr.substring(elseAt + 4).trim(),
        ]
    }

    // ------------------------------------------------------------------
    // scanner
    // ------------------------------------------------------------------

    private static String scan(String expr) {
        final out = new StringBuilder()
        int atomStart = -1          // index in `out` where the latest atom begins
        int i = 0

        while( i < expr.length() ) {
            final char ch = expr.charAt(i)

            if( Character.isWhitespace(ch) ) {
                out.append(ch)
                i++
                continue
            }

            if( ch == '\'' as char || ch == '"' as char ) {
                final end = skipString(expr, i)
                atomStart = out.length()
                out.append(groovyString(unquote(expr.substring(i, end))))
                i = end
                i = consumeTrailers(expr, i, out, atomStart)
                atomStart = out.length() == 0 ? -1 : atomStart
                continue
            }

            if( Character.isDigit(ch) ) {
                int end = i
                while( end < expr.length() && (Character.isDigit(expr.charAt(end)) || expr.charAt(end) == '.' as char) )
                    end++
                atomStart = out.length()
                out.append(expr, i, end)
                i = consumeTrailers(expr, end, out, atomStart)
                continue
            }

            if( ch == '(' as char ) {
                final end = matchParen(expr, i)
                atomStart = out.length()
                out.append('(').append(compile(expr.substring(i + 1, end - 1))).append(')')
                i = consumeTrailers(expr, end, out, atomStart)
                continue
            }

            if( isWordStart(ch) ) {
                final end = wordEnd(expr, i)
                final word = expr.substring(i, end)

                switch( word ) {
                    case 'and':
                        out.append('&&'); i = end; continue
                    case 'or':
                        out.append('?:'); i = end; continue
                    case 'not':
                        out.append('!'); i = end; continue
                    case 'None':
                    case 'none':
                        atomStart = out.length(); out.append('null'); i = end; continue
                    case 'True':
                        atomStart = out.length(); out.append('true'); i = end; continue
                    case 'False':
                        atomStart = out.length(); out.append('false'); i = end; continue

                    case 'is':
                        i = skipSpace(expr, end)
                        boolean negated = false
                        int wend = wordEnd(expr, i)
                        if( expr.substring(i, wend) == 'not' ) {
                            negated = true
                            i = skipSpace(expr, wend)
                            wend = wordEnd(expr, i)
                        }
                        final test = expr.substring(i, wend)
                        final type = TESTS[test]
                        if( !type )
                            throw new IllegalArgumentException("Unsupported template test: is ${test}")
                        // wrap the left-hand atom so `not` binds to the whole test
                        final lhs = out.substring(atomStart < 0 ? 0 : atomStart)
                        out.setLength(atomStart < 0 ? 0 : atomStart)
                        out.append(negated ? "!(${lhs} instanceof ${type})" : "(${lhs} instanceof ${type})")
                        i = wend
                        continue

                    case 'in':
                        final lhs = out.substring(atomStart < 0 ? 0 : atomStart)
                        out.setLength(atomStart < 0 ? 0 : atomStart)
                        final rhs = compile(expr.substring(end).trim())
                        out.append("__in(${lhs}, ${rhs})")
                        return out.toString()

                    default:
                        atomStart = out.length()
                        out.append("__var('").append(word).append("')")
                        i = consumeTrailers(expr, end, out, atomStart)
                        continue
                }
            }

            // operators and anything else pass through unchanged
            out.append(ch)
            i++
        }

        return out.toString()
    }

    /**
     * Consume `.attr`, `.method(...)` and `|filter(...)` suffixes, rewriting the
     * atom that starts at `atomStart` in `out`.
     */
    private static int consumeTrailers(String expr, int pos, StringBuilder out, int atomStart) {
        int i = pos
        while( i < expr.length() ) {
            final char ch = expr.charAt(i)

            if( ch == '.' as char && i + 1 < expr.length() && isWordStart(expr.charAt(i + 1)) ) {
                final end = wordEnd(expr, i + 1)
                final name = expr.substring(i + 1, end)
                final atom = out.substring(atomStart)
                out.setLength(atomStart)
                final afterName = skipSpace(expr, end)
                if( afterName < expr.length() && expr.charAt(afterName) == '(' as char ) {
                    final close = matchParen(expr, afterName)
                    final args = splitArgs(expr.substring(afterName + 1, close - 1))
                    out.append("__m(${atom},'${name}',[${args.collect { compile(it) }.join(', ')}])")
                    i = close
                }
                else {
                    out.append("__attr(${atom},'${name}')")
                    i = end
                }
                continue
            }

            if( ch == '[' as char ) {
                final close = matchBracket(expr, i)
                final content = expr.substring(i + 1, close - 1)
                final atom = out.substring(atomStart)
                out.setLength(atomStart)
                final colon = topLevelColon(content)
                if( colon < 0 ) {
                    out.append("__index(${atom}, ${compile(content.trim())})")
                }
                else {
                    if( topLevelColon(content.substring(colon + 1)) >= 0 )
                        throw new IllegalArgumentException("Unsupported step slice: [${content}]")
                    final from = content.substring(0, colon).trim()
                    final to = content.substring(colon + 1).trim()
                    out.append("__slice(${atom}, ${from ? compile(from) : 'null'}, ${to ? compile(to) : 'null'})")
                }
                i = close
                continue
            }

            final int bar = skipSpace(expr, i)
            if( bar < expr.length() && expr.charAt(bar) == '|' as char
                    && bar + 1 < expr.length() && expr.charAt(bar + 1) != '|' as char ) {
                int j = skipSpace(expr, bar + 1)
                if( j >= expr.length() || !isWordStart(expr.charAt(j)) )
                    break
                final end = wordEnd(expr, j)
                final name = expr.substring(j, end)
                final atom = out.substring(atomStart)
                out.setLength(atomStart)
                final afterName = skipSpace(expr, end)
                List<String> args = []
                int next = end
                if( afterName < expr.length() && expr.charAt(afterName) == '(' as char ) {
                    final close = matchParen(expr, afterName)
                    args = splitArgs(expr.substring(afterName + 1, close - 1))
                    next = close
                }
                out.append("__filter(${atom},'${name}',[${args.collect { compile(it) }.join(', ')}])")
                i = next
                continue
            }

            break
        }
        return i
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------

    private static List<String> splitArgs(String args) {
        if( !args?.trim() )
            return []
        final parts = []
        int depth = 0
        int start = 0
        int i = 0
        while( i < args.length() ) {
            final ch = args.charAt(i)
            if( ch == '\'' as char || ch == '"' as char ) {
                i = skipString(args, i)
                continue
            }
            if( ch == '(' as char || ch == '[' as char )
                depth++
            else if( ch == ')' as char || ch == ']' as char )
                depth--
            else if( ch == ',' as char && depth == 0 ) {
                parts << args.substring(start, i)
                start = i + 1
            }
            i++
        }
        parts << args.substring(start)
        return parts.collect { it.trim() }.findAll { it }
    }

    private static int skipString(String s, int start) {
        final quote = s.charAt(start)
        int i = start + 1
        while( i < s.length() ) {
            final ch = s.charAt(i)
            if( ch == '\\' as char ) {
                i += 2
                continue
            }
            if( ch == quote )
                return i + 1
            i++
        }
        return s.length()
    }

    private static int matchBracket(String s, int start) {
        int depth = 0
        int i = start
        while( i < s.length() ) {
            final ch = s.charAt(i)
            if( ch == '\'' as char || ch == '"' as char ) {
                i = skipString(s, i)
                continue
            }
            if( ch == '[' as char )
                depth++
            else if( ch == ']' as char ) {
                depth--
                if( depth == 0 )
                    return i + 1
            }
            i++
        }
        return s.length()
    }

    /** Index of the first top-level ':' (outside quotes and nesting), or -1. */
    private static int topLevelColon(String s) {
        int depth = 0
        int i = 0
        while( i < s.length() ) {
            final ch = s.charAt(i)
            if( ch == '\'' as char || ch == '"' as char ) {
                i = skipString(s, i)
                continue
            }
            if( ch == '(' as char || ch == '[' as char )
                depth++
            else if( ch == ')' as char || ch == ']' as char )
                depth--
            else if( ch == ':' as char && depth == 0 )
                return i
            i++
        }
        return -1
    }

    private static int matchParen(String s, int start) {
        int depth = 0
        int i = start
        while( i < s.length() ) {
            final ch = s.charAt(i)
            if( ch == '\'' as char || ch == '"' as char ) {
                i = skipString(s, i)
                continue
            }
            if( ch == '(' as char )
                depth++
            else if( ch == ')' as char ) {
                depth--
                if( depth == 0 )
                    return i + 1
            }
            i++
        }
        return s.length()
    }

    private static String unquote(String literal) {
        return literal.length() >= 2
            ? literal.substring(1, literal.length() - 1).replace('\\\'', '\'').replace('\\"', '"')
            : literal
    }

    /**
     * Always emit single-quoted Groovy strings so `$` is never interpolated.
     * Line breaks are escaped too: a Jinja literal may span lines (the
     * datasheet's `keywords|join(',\n      ')` does), which single quotes in
     * Groovy cannot.
     */
    private static String groovyString(String value) {
        return "'" + value
            .replace('\\', '\\\\')
            .replace("'", "\\'")
            .replace('\n', '\\n')
            .replace('\r', '\\r')
            .replace('\t', '\\t') + "'"
    }

    private static boolean isWordStart(char ch) {
        return Character.isLetter(ch) || ch == '_' as char
    }

    private static int wordEnd(String s, int start) {
        int i = start
        while( i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' as char) )
            i++
        return i
    }

    private static int skipSpace(String s, int start) {
        int i = start
        while( i < s.length() && Character.isWhitespace(s.charAt(i)) )
            i++
        return i
    }
}
