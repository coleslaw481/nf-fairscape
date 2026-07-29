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
import groovy.util.logging.Slf4j

/**
 * A Jinja2-compatible renderer for the subset of template syntax used by the
 * fairscape-cli datasheet templates, so those templates can be vendored into
 * this plugin verbatim (see src/main/resources/fairscape/templates).
 *
 * Supported: {{ expr }}, {% if %}/{% elif %}/{% else %}/{% endif %},
 * {% for x in expr %} / {% for k, v in map.items() %} (with loop.index,
 * loop.index0, loop.first, loop.last, loop.length), {% set name = expr %},
 * {# comments #}, the filters safe/lower/join/list/int/round/length, the tests
 * `is string` / `is mapping`, the `in` operator, and the Python string methods
 * startswith/endswith/split plus dict methods keys/items/values.
 *
 * Whitespace handling mirrors the CLI's Environment(trim_blocks=True,
 * lstrip_blocks=True) so rendered output is byte-identical to Jinja2's.
 * Autoescaping is off in the CLI too, so `|safe` is a no-op here.
 *
 * Expressions are translated to Groovy and evaluated with a GroovyShell; a
 * missing name or a failed lookup yields null (Jinja's Undefined), which
 * renders as the empty string and is falsy in conditions.
 *
 * @author FAIRSCAPE
 */
@Slf4j
@CompileDynamic
class MiniJinja {

    private static final Map<String,Script> SCRIPT_CACHE = new ConcurrentHashMap<>()

    private static final GroovyShell SHELL = new GroovyShell()

    /** Expressions already warned about, so a failure logs once, not per render. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet()

    /** Re-set on every cached script after use so it never pins a render's context. */
    private static final Binding EMPTY_BINDING = new Binding()

    // ------------------------------------------------------------------
    // public entry point
    // ------------------------------------------------------------------

    static String render(String source, Map context) {
        final nodes = parse(source)
        final out = new StringBuilder()
        final scope = new LinkedHashMap(context ?: [:])
        renderNodes(nodes, scope, out)
        return out.toString()
    }

    // ------------------------------------------------------------------
    // lexer
    // ------------------------------------------------------------------

    /**
     * Split the template into text / {{ var }} / {% tag %} tokens, applying
     * Jinja's lstrip_blocks (drop the indentation in front of a block tag) and
     * trim_blocks (drop the newline right after one).
     */
    private static List<Map> tokenize(String src) {
        final tokens = []
        final text = new StringBuilder()
        int i = 0
        final int n = src.length()

        while( i < n ) {
            final int open = indexOfAny(src, i)
            if( open < 0 ) {
                text.append(src, i, n)
                break
            }

            final String kind = src.substring(open, open + 2)
            text.append(src, i, open)

            if( kind != '{{' )
                lstripBlock(src, open, text)

            final String close = kind == '{{' ? '}}' : (kind == '{%' ? '%}' : '#}')
            final int end = src.indexOf(close, open + 2)
            if( end < 0 ) {
                // unterminated tag: treat the rest as literal text
                text.append(src, open, n)
                break
            }

            final String body = src.substring(open + 2, end).trim()
            if( kind == '{{' ) {
                tokens << [type: 'text', value: text.toString()]
                text.setLength(0)
                tokens << [type: 'var', expr: body]
            }
            else if( kind == '{%' ) {
                tokens << [type: 'text', value: text.toString()]
                text.setLength(0)
                tokens << [type: 'tag', value: body]
            }
            // '{#' comments emit nothing

            i = end + close.length()
            if( kind != '{{' )
                i = trimBlock(src, i)
        }

        if( text.length() > 0 )
            tokens << [type: 'text', value: text.toString()]

        return tokens
    }

    private static int indexOfAny(String src, int from) {
        final a = src.indexOf('{{', from)
        final b = src.indexOf('{%', from)
        final c = src.indexOf('{#', from)
        int best = -1
        for( int idx : [a, b, c] )
            if( idx >= 0 && (best < 0 || idx < best) )
                best = idx
        return best
    }

    /**
     * lstrip_blocks: drop the indentation between the last newline and a block
     * tag. Decided against the raw source, like Jinja's lexer — a tag earlier on
     * the same line counts as content and cancels the strip, even though it
     * contributed nothing to the pending text buffer.
     */
    private static void lstripBlock(String src, int tagStart, StringBuilder text) {
        int i = tagStart
        while( i > 0 && (src.charAt(i - 1) == ' ' as char || src.charAt(i - 1) == '\t' as char) )
            i--
        if( i > 0 && src.charAt(i - 1) != '\n' as char )
            return          // something other than whitespace precedes it on this line
        final strip = tagStart - i
        if( strip > 0 )
            text.setLength(Math.max(0, text.length() - strip))
    }

    /** trim_blocks: drop the first newline that follows a block tag. */
    private static int trimBlock(String src, int pos) {
        if( pos < src.length() && src.charAt(pos) == '\r' as char )
            pos++
        if( pos < src.length() && src.charAt(pos) == '\n' as char )
            pos++
        return pos
    }

    // ------------------------------------------------------------------
    // parser
    // ------------------------------------------------------------------

    private static List<Map> parse(String src) {
        final tokens = tokenize(src)
        final pos = [0]
        final nodes = parseNodes(tokens, pos, [] as Set)
        return nodes
    }

    /**
     * Consume tokens until one of `stopAt` is reached (left unconsumed) or the
     * stream ends.
     */
    private static List<Map> parseNodes(List<Map> tokens, List<Integer> pos, Set<String> stopAt) {
        final nodes = []
        while( pos[0] < tokens.size() ) {
            final tok = tokens[pos[0]]
            if( tok.type == 'tag' ) {
                final keyword = firstWord(tok.value as String)
                if( keyword in stopAt )
                    return nodes
                pos[0]++
                nodes << parseTag(tok.value as String, keyword, tokens, pos)
            }
            else {
                pos[0]++
                nodes << tok
            }
        }
        return nodes
    }

    private static Map parseTag(String tag, String keyword, List<Map> tokens, List<Integer> pos) {
        switch( keyword ) {
            case 'if':
                return parseIf(tag, tokens, pos)
            case 'for':
                return parseFor(tag, tokens, pos)
            case 'set':
                final m = (tag =~ /(?s)^set\s+(\w+)\s*=\s*(.+)$/)
                if( !m.find() )
                    throw new IllegalArgumentException("Unsupported set tag: {% ${tag} %}")
                return [type: 'set', name: m.group(1), expr: m.group(2)]
            default:
                throw new IllegalArgumentException("Unsupported template tag: {% ${tag} %}")
        }
    }

    private static Map parseIf(String tag, List<Map> tokens, List<Integer> pos) {
        final branches = []
        String cond = tag.substring('if'.length()).trim()
        final stop = ['elif', 'else', 'endif'] as Set

        while( true ) {
            final body = parseNodes(tokens, pos, stop)
            branches << [cond: cond, body: body]

            if( pos[0] >= tokens.size() )
                break
            final next = tokens[pos[0]].value as String
            pos[0]++
            final keyword = firstWord(next)
            if( keyword == 'elif' ) {
                cond = next.substring('elif'.length()).trim()
                continue
            }
            if( keyword == 'else' ) {
                final elseBody = parseNodes(tokens, pos, ['endif'] as Set)
                if( pos[0] < tokens.size() )
                    pos[0]++   // consume endif
                return [type: 'if', branches: branches, elseBody: elseBody]
            }
            break   // endif
        }
        return [type: 'if', branches: branches, elseBody: null]
    }

    private static Map parseFor(String tag, List<Map> tokens, List<Integer> pos) {
        final m = (tag =~ /(?s)^for\s+(.+?)\s+in\s+(.+)$/)
        if( !m.find() )
            throw new IllegalArgumentException("Unsupported for tag: {% ${tag} %}")

        final targets = (m.group(1) as String).split(',').collect { it.trim() }
        final expr = m.group(2) as String
        final body = parseNodes(tokens, pos, ['else', 'endfor'] as Set)

        List elseBody = null
        if( pos[0] < tokens.size() ) {
            final keyword = firstWord(tokens[pos[0]].value as String)
            pos[0]++
            if( keyword == 'else' ) {
                elseBody = parseNodes(tokens, pos, ['endfor'] as Set)
                if( pos[0] < tokens.size() )
                    pos[0]++
            }
        }
        return [type: 'for', targets: targets, expr: expr, body: body, elseBody: elseBody]
    }

    private static String firstWord(String s) {
        final t = s.trim()
        int i = 0
        while( i < t.length() && !Character.isWhitespace(t.charAt(i)) )
            i++
        return t.substring(0, i)
    }

    // ------------------------------------------------------------------
    // renderer
    // ------------------------------------------------------------------

    private static void renderNodes(List nodes, Map scope, StringBuilder out) {
        for( final node : nodes ) {
            switch( node.type ) {
                case 'text':
                    out.append(node.value as String)
                    break

                case 'var':
                    final value = evaluate(node.expr as String, scope)
                    if( value != null )
                        out.append(stringify(value))
                    break

                case 'set':
                    scope[node.name as String] = evaluate(node.expr as String, scope)
                    break

                case 'if':
                    boolean done = false
                    for( final branch : node.branches ) {
                        if( truthy(evaluate(branch.cond as String, scope)) ) {
                            renderNodes(branch.body as List, scope, out)
                            done = true
                            break
                        }
                    }
                    if( !done && node.elseBody != null )
                        renderNodes(node.elseBody as List, scope, out)
                    break

                case 'for':
                    renderFor(node, scope, out)
                    break
            }
        }
    }

    private static void renderFor(Map node, Map scope, StringBuilder out) {
        final items = asIterable(evaluate(node.expr as String, scope))
        if( !items ) {
            if( node.elseBody != null )
                renderNodes(node.elseBody as List, scope, out)
            return
        }

        final targets = node.targets as List
        final int total = items.size()
        int index = 0
        for( final item : items ) {
            index++
            bindTargets(targets, item, scope)
            scope['loop'] = [
                index  : index,
                index0 : index - 1,
                first  : index == 1,
                last   : index == total,
                length : total,
            ]
            renderNodes(node.body as List, scope, out)
        }
        scope.remove('loop')
    }

    private static void bindTargets(List targets, Object item, Map scope) {
        if( targets.size() == 1 ) {
            scope[targets[0] as String] = item
            return
        }
        List parts
        if( item instanceof Map.Entry )
            parts = [item.key, item.value]
        else if( item instanceof Map )
            parts = new ArrayList(item.values())
        else
            parts = asIterable(item) as List
        targets.eachWithIndex { name, i ->
            scope[name as String] = i < parts.size() ? parts[i] : null
        }
    }

    private static Collection asIterable(Object value) {
        if( value == null )
            return []
        if( value instanceof Map )
            return new ArrayList(((Map) value).keySet())   // Jinja iterates dict keys
        if( value instanceof Collection )
            return (Collection) value
        if( value instanceof Object[] )
            return Arrays.asList((Object[]) value)
        if( value instanceof Iterable )
            return ((Iterable) value).toList()
        if( value instanceof CharSequence )
            return ((CharSequence) value).toString().toList()
        return [value]
    }

    /** Render a value the way Python's str() would for the types we emit. */
    static String stringify(Object value) {
        if( value == null )
            return ''
        if( value instanceof Boolean )
            return value ? 'True' : 'False'
        if( value instanceof Double || value instanceof Float ) {
            final d = ((Number) value).doubleValue()
            return d == Math.rint(d) && !Double.isInfinite(d)
                ? CrateJson.fixed(d, 1)
                : String.valueOf(d)
        }
        if( value instanceof BigDecimal )
            return stringify(((BigDecimal) value).doubleValue())
        if( value instanceof List )
            return '[' + ((List) value).collect { reprElement(it) }.join(', ') + ']'
        return value.toString()
    }

    private static String reprElement(Object value) {
        if( value instanceof CharSequence )
            return "'" + value.toString().replace('\\', '\\\\').replace("'", "\\'") + "'"
        return stringify(value)
    }

    /** Python/Jinja truthiness: empty string, empty collection, 0 and null are false. */
    static boolean truthy(Object value) {
        if( value == null )
            return false
        if( value instanceof Boolean )
            return (Boolean) value
        if( value instanceof CharSequence )
            return ((CharSequence) value).length() > 0
        if( value instanceof Collection )
            return !((Collection) value).isEmpty()
        if( value instanceof Map )
            return !((Map) value).isEmpty()
        if( value instanceof Number )
            return ((Number) value).doubleValue() != 0d
        return true
    }

    // ------------------------------------------------------------------
    // expression evaluation
    // ------------------------------------------------------------------

    private static Object evaluate(String expr, Map scope) {
        final source = ExpressionTranslator.translate(expr)
        try {
            final script = SCRIPT_CACHE.computeIfAbsent(source, { String src -> SHELL.parse(src) })
            final binding = new Binding()
            binding.setVariable('__var', { String name -> scope.containsKey(name) ? scope[name] : null })
            binding.setVariable('__attr', MiniJinja.&attr)
            binding.setVariable('__m', MiniJinja.&method)
            binding.setVariable('__filter', MiniJinja.&filter)
            binding.setVariable('__in', MiniJinja.&contains)
            binding.setVariable('__truthy', MiniJinja.&truthy)
            binding.setVariable('__index', MiniJinja.&item)
            binding.setVariable('__slice', MiniJinja.&slice)
            synchronized(script) {
                script.setBinding(binding)
                try {
                    return script.run()
                }
                finally {
                    // don't let the cached script pin this render's context
                    script.setBinding(EMPTY_BINDING)
                }
            }
        }
        catch( Exception e ) {
            // loud, once per expression: a silently-empty render hid a real
            // template bug before ({{ pub[4:] }} with no slice support)
            if( WARNED.add(expr) )
                log.warn "nf-fairscape: datasheet template expression failed and renders as empty: {{ ${expr} }} (${e.message})"
            log.debug "Datasheet template expression failed: ${expr} -> ${source}", e
            return null
        }
    }

    /**
     * Python subscript `obj[key]`: dict key lookup, or index into a list or
     * string with negative-from-the-end semantics. Out of range yields null
     * (Jinja's Undefined) rather than Python's IndexError.
     */
    static Object item(Object obj, Object key) {
        if( obj == null )
            return null
        if( obj instanceof Map )
            return ((Map) obj).get(key)
        if( key instanceof Number ) {
            final int idx = ((Number) key).intValue()
            if( obj instanceof CharSequence ) {
                final s = obj.toString()
                final n = idx < 0 ? s.length() + idx : idx
                return n >= 0 && n < s.length() ? s.substring(n, n + 1) : null
            }
            if( obj instanceof List ) {
                final list = (List) obj
                final n = idx < 0 ? list.size() + idx : idx
                return n >= 0 && n < list.size() ? list[n] : null
            }
        }
        return null
    }

    /** Python slice `obj[from:to]` over a string or list; a null bound is open. */
    static Object slice(Object obj, Object from, Object to) {
        if( obj == null )
            return null
        if( obj instanceof CharSequence ) {
            final s = obj.toString()
            final int[] bounds = sliceBounds(s.length(), from, to)
            return s.substring(bounds[0], bounds[1])
        }
        if( obj instanceof List ) {
            final list = (List) obj
            final int[] bounds = sliceBounds(list.size(), from, to)
            return new ArrayList(list.subList(bounds[0], bounds[1]))
        }
        return null
    }

    /** Clamp Python slice bounds (negative counts from the end) to [0, size]. */
    private static int[] sliceBounds(int size, Object from, Object to) {
        int a = from == null ? 0 : ((Number) from).intValue()
        int b = to == null ? size : ((Number) to).intValue()
        if( a < 0 ) a += size
        if( b < 0 ) b += size
        a = Math.max(0, Math.min(a, size))
        b = Math.max(0, Math.min(b, size))
        return [a, Math.max(a, b)] as int[]
    }

    /** Attribute/key access: dict-first, like Jinja's getattr fallback chain. */
    static Object attr(Object obj, String name) {
        if( obj == null )
            return null
        if( obj instanceof Map )
            return ((Map) obj).get(name)
        if( obj instanceof Map.Entry )
            return name == 'key' ? ((Map.Entry) obj).key : (name == 'value' ? ((Map.Entry) obj).value : null)
        try {
            return obj.getProperties().containsKey(name) ? obj[name] : null
        }
        catch( Exception e ) {
            return null
        }
    }

    /** The handful of Python methods the templates call. */
    static Object method(Object obj, String name, List args) {
        if( obj == null )
            return null
        switch( name ) {
            case 'startswith':
                return obj.toString().startsWith(args[0].toString())
            case 'endswith':
                return obj.toString().endsWith(args[0].toString())
            case 'split':
                return args ? obj.toString().split(java.util.regex.Pattern.quote(args[0].toString())) as List
                            : obj.toString().split('\\s+') as List
            case 'keys':
                return obj instanceof Map ? new ArrayList(((Map) obj).keySet()) : []
            case 'values':
                return obj instanceof Map ? new ArrayList(((Map) obj).values()) : []
            case 'items':
                return obj instanceof Map ? new ArrayList(((Map) obj).entrySet()) : []
            case 'strip':
                return obj.toString().trim()
            case 'lower':
                return obj.toString().toLowerCase()
            case 'upper':
                return obj.toString().toUpperCase()
            default:
                throw new IllegalArgumentException("Unsupported template method: .${name}()")
        }
    }

    static Object filter(Object value, String name, List args) {
        switch( name ) {
            case 'safe':
            case 'e':
            case 'escape':
                return value
            case 'lower':
                return value == null ? null : value.toString().toLowerCase()
            case 'upper':
                return value == null ? null : value.toString().toUpperCase()
            case 'trim':
                return value == null ? null : value.toString().trim()
            case 'list':
                return asIterable(value) as List
            case 'length':
            case 'count':
                return asIterable(value).size()
            case 'join':
                final sep = args ? args[0].toString() : ''
                return asIterable(value).collect { stringify(it) }.join(sep)
            case 'int':
                if( value == null )
                    return 0
                if( value instanceof Number )
                    return ((Number) value).intValue()   // truncates, like Python int()
                try {
                    return Double.parseDouble(value.toString()).intValue()
                }
                catch( Exception e ) {
                    return args ? args[0] : 0
                }
            case 'float':
                return value == null ? 0d : Double.parseDouble(value.toString())
            case 'round':
                final precision = args ? (args[0] as Number).intValue() : 0
                final d = value == null ? 0d : ((Number) value).doubleValue()
                return new BigDecimal(d).setScale(precision, java.math.RoundingMode.HALF_EVEN).doubleValue()
            case 'default':
                return truthy(value) ? value : (args ? args[0] : '')
            default:
                throw new IllegalArgumentException("Unsupported template filter: |${name}")
        }
    }

    /** Jinja's `in`: substring for strings, membership for collections. */
    static boolean contains(Object needle, Object haystack) {
        if( haystack == null )
            return false
        if( haystack instanceof CharSequence )
            return haystack.toString().contains(needle == null ? '' : needle.toString())
        if( haystack instanceof Map )
            return ((Map) haystack).containsKey(needle)
        return asIterable(haystack).contains(needle)
    }
}
