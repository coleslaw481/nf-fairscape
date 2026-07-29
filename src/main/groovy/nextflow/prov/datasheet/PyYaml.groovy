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

import java.text.SimpleDateFormat

import groovy.transform.CompileDynamic

/**
 * A byte-compatible `yaml.dump(data, stream)` for the document shapes the D4D
 * conversion produces — the YAML counterpart of {@link PyJson}.
 *
 * PyYAML's defaults are what have to be matched, and they are not the obvious
 * ones: keys are sorted, block style is forced, scalars are folded at column 80
 * at space boundaries with a two-space continuation indent, and a scalar is
 * written plain unless its content would be ambiguous (a `": "`, a leading
 * indicator, or a value the implicit resolver would read back as a number,
 * boolean, null or timestamp), in which case it is single-quoted.
 *
 * The emitter therefore mirrors PyYAML's `Emitter` column bookkeeping —
 * `column`/`whitespace`/`indention`/`indent` and the `write_indent`,
 * `write_indicator`, `write_plain` and `write_single_quoted` primitives —
 * rather than pretty-printing independently, because the fold points depend on
 * exactly where each character lands.
 *
 * Supported node types: Map, List, String, Number, Boolean, Date, null. That
 * covers every D4D document; anything else is written via `toString()`.
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class PyYaml {

    private static final int BEST_WIDTH = 80
    private static final int BEST_INDENT = 2

    private final StringBuilder out = new StringBuilder()

    private int column = 0
    private boolean whitespace = true
    private boolean indention = true
    private Integer indent = null
    private final List<Integer> indents = []
    private boolean simpleKeyContext = false

    /** `yaml.dump(data)` with PyYAML's default settings. */
    static String dump(Object data) {
        final writer = new PyYaml()
        writer.emitNode(data, true, false, false)
        writer.writeLineBreak()
        return writer.out.toString()
    }

    // ------------------------------------------------------------------
    // Node emission (Emitter.expect_node and friends)
    // ------------------------------------------------------------------

    private void emitNode(Object value, boolean root, boolean sequence, boolean mapping) {
        if( value instanceof Map )
            ((Map) value).isEmpty() ? emitFlowEmpty('{}') : emitBlockMapping((Map) value, mapping)
        else if( value instanceof List )
            ((List) value).isEmpty() ? emitFlowEmpty('[]') : emitBlockSequence((List) value, mapping)
        else
            emitScalar(value)
    }

    /**
     * PyYAML writes an empty collection in flow style: `key: {}` / `key: []`.
     * Without this branch an empty Map or List fell through to emitScalar and
     * came out as Groovy's toString -- the literal strings '[:]' and '[]'.
     */
    private void emitFlowEmpty(String text) {
        increaseIndent(true, false)
        writePlain(text, !simpleKeyContext)
        indent = indents.removeLast()
    }

    private void emitBlockMapping(Map node, boolean mappingContext) {
        increaseIndent(false, false)
        // sort_keys=True; Python orders str keys by code point, as String does
        final keys = new ArrayList(node.keySet()).sort { a, b -> String.valueOf(a) <=> String.valueOf(b) }
        for( final key : keys ) {
            writeIndent()
            simpleKeyContext = true
            emitScalar(key)
            simpleKeyContext = false
            writeIndicator(':', false, false, false)
            emitNode(node.get(key), false, false, true)
        }
        indent = indents.removeLast()
    }

    private void emitBlockSequence(List node, boolean mappingContext) {
        // a sequence that is a block-mapping value sits at the mapping's own
        // indent rather than one level in ("indentless")
        increaseIndent(false, mappingContext && !indention)
        for( final item : node ) {
            writeIndent()
            writeIndicator('-', true, false, true)
            emitNode(item, false, true, false)
        }
        indent = indents.removeLast()
    }

    private void emitScalar(Object value) {
        increaseIndent(true, false)
        final text = scalarText(value)
        final analysis = analyze(text, plainFormResolvesBack(value))
        final split = !simpleKeyContext
        if( analysis.style == '"' )
            writeDoubleQuoted(text, split)
        else if( analysis.style == '\'' )
            writeSingleQuoted(text, split)
        else
            writePlain(text, split)
        indent = indents.removeLast()
    }

    private void increaseIndent(boolean flow, boolean indentless) {
        indents.add(indent)
        if( indent == null )
            indent = flow ? BEST_INDENT : 0
        else if( !indentless )
            indent = indent + BEST_INDENT
    }

    // ------------------------------------------------------------------
    // Scalar rendering
    // ------------------------------------------------------------------

    /** How a value is spelled before any quoting is decided. */
    private static String scalarText(Object value) {
        if( value == null )
            return 'null'
        if( value instanceof Boolean )
            return ((Boolean) value) ? 'true' : 'false'
        if( value instanceof Date )
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((Date) value)
        return String.valueOf(value)
    }

    /**
     * PyYAML's `implicit[0]`: whether the implicit resolver reading the plain
     * form back would produce the type it was written from. A number, boolean,
     * date or null always round-trips, so it is written bare. Only a String has
     * to defend itself — if it looks like one of those it must be quoted.
     */
    private static boolean plainFormResolvesBack(Object value) {
        if( !(value instanceof CharSequence) )
            return true
        return !looksLikeNonString(value.toString())
    }

    private static boolean looksLikeNonString(String text) {
        if( text.isEmpty() )
            return true
        if( text in ['~', 'null', 'Null', 'NULL', '=', '<<'] )
            return true
        if( text ==~ /(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF)/ )
            return true
        if( text ==~ /[-+]?(?:0b[0-1_]+|0[0-7_]*|0x[0-9a-fA-F_]+|[1-9][0-9_]*(?::[0-5]?[0-9])*)/ )
            return true
        if( text ==~ /[-+]?(?:[0-9][0-9_]*)?\.[0-9_]*(?:[eE][-+]?[0-9]+)?/ && text ==~ /.*[0-9].*/ )
            return true
        if( text ==~ /[-+]?[0-9][0-9_]*(?:[eE][-+][0-9]+)/ )
            return true
        if( text ==~ /[-+]?\.(?:inf|Inf|INF)/ || text ==~ /\.(?:nan|NaN|NAN)/ )
            return true
        // timestamp: yyyy-mm-dd, optionally with a time part
        if( text ==~ /[0-9]{4}-[0-9]{2}-[0-9]{2}/ )
            return true
        if( text ==~ /[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}(?:[Tt]|[ \t]+)[0-9]{1,2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]*)?(?:[ \t]*(?:Z|[-+][0-9]{1,2}(?::[0-9]{2})?))?/ )
            return true
        return false
    }

    /**
     * Emitter.analyze_scalar + choose_scalar_style, reduced to the question the
     * caller actually asks: which of plain / single / double to write.
     */
    private static Map analyze(String text, boolean plainAllowedByResolver) {
        if( text.isEmpty() )
            return [style: '\'']

        boolean blockIndicators = false
        boolean lineBreaks = false
        boolean specialCharacters = false
        boolean spaceBreak = false
        boolean breakSpace = false

        if( text.startsWith('---') || text.startsWith('...') )
            blockIndicators = true

        boolean previousSpace = false
        boolean previousBreak = false
        for( int i = 0; i < text.length(); i++ ) {
            final char ch = text.charAt(i)
            final boolean followedByWhitespace =
                i + 1 >= text.length() || text.charAt(i + 1) in [' ' as char, '\t' as char, '\r' as char, '\n' as char]
            final boolean precededByWhitespace =
                i == 0 || text.charAt(i - 1) in [' ' as char, '\t' as char, '\r' as char, '\n' as char]

            if( i == 0 ) {
                if( ch in ('#,[]{}&*!|>\'"%@`' as char[]) as List )
                    blockIndicators = true
                if( (ch == ('?' as char) || ch == (':' as char)) && followedByWhitespace )
                    blockIndicators = true
                if( ch == ('-' as char) && followedByWhitespace )
                    blockIndicators = true
            }
            else {
                if( ch == (':' as char) && followedByWhitespace )
                    blockIndicators = true
                if( ch == ('#' as char) && precededByWhitespace )
                    blockIndicators = true
            }

            if( ch == ('\n' as char) )
                lineBreaks = true
            // printable ASCII plus newline is plain-safe; PyYAML defaults to
            // allow_unicode=False, so anything above it forces double quotes
            if( !(ch == ('\n' as char) || (ch >= (0x20 as char) && ch <= (0x7E as char))) )
                specialCharacters = true

            final boolean isSpace = ch == (' ' as char)
            final boolean isBreak = ch == ('\n' as char)
            if( isSpace && previousBreak )
                breakSpace = true
            if( isBreak && previousSpace )
                spaceBreak = true
            previousSpace = isSpace
            previousBreak = isBreak
        }

        final boolean leadingOrTrailingSpace =
            text.startsWith(' ') || text.endsWith(' ') || text.startsWith('\n') || text.endsWith('\n')

        boolean allowBlockPlain = true
        boolean allowSingleQuoted = true
        if( leadingOrTrailingSpace ) allowBlockPlain = false
        if( breakSpace ) { allowBlockPlain = false; allowSingleQuoted = false }
        if( spaceBreak || specialCharacters ) { allowBlockPlain = false; allowSingleQuoted = false }
        if( lineBreaks ) allowBlockPlain = false
        if( blockIndicators ) allowBlockPlain = false

        // DELIBERATE deviation from PyYAML: a multi-line scalar goes to the
        // double-quoted style, whose `\n` escape round-trips. PyYAML would keep
        // single quotes and fold the newline with a blank-line convention this
        // emitter does not implement -- writeSingleQuoted would emit the break
        // raw, and a raw newline inside single quotes folds to a SPACE on read,
        // silently corrupting any description that contains one.
        if( lineBreaks ) allowSingleQuoted = false

        if( plainAllowedByResolver && allowBlockPlain )
            return [style: '']
        if( allowSingleQuoted )
            return [style: '\'']
        return [style: '"']
    }

    // ------------------------------------------------------------------
    // Emitter write primitives
    // ------------------------------------------------------------------

    private void writeLineBreak() {
        whitespace = true
        indention = true
        column = 0
        out.append('\n')
    }

    private void writeIndent() {
        final target = indent ?: 0
        if( !indention || column > target || (column == target && !whitespace) )
            writeLineBreak()
        if( column < target ) {
            whitespace = true
            final data = ' ' * (target - column)
            column = target
            out.append(data)
        }
    }

    private void writeIndicator(String indicator, boolean needWhitespace, boolean isWhitespace, boolean isIndention) {
        final data = (whitespace || !needWhitespace) ? indicator : ' ' + indicator
        whitespace = isWhitespace
        indention = indention && isIndention
        column += data.length()
        out.append(data)
    }

    /** Emitter.write_plain — folds at spaces once past column 80. */
    private void writePlain(String text, boolean split) {
        if( !whitespace ) {
            column += 1
            out.append(' ')
        }
        whitespace = false
        indention = false
        if( !text )
            return

        boolean spaces = false
        int start = 0
        int end = 0
        while( end <= text.length() ) {
            Character ch = end < text.length() ? text.charAt(end) : null
            if( spaces ) {
                if( ch == null || ch != (' ' as char) ) {
                    if( start + 1 == end && column > BEST_WIDTH && split && start != 0 && end != text.length() )
                        writeIndent()
                    else
                        write(text.substring(start, end))
                    start = end
                }
            }
            else if( ch == null || ch == (' ' as char) || ch == ('\n' as char) ) {
                write(text.substring(start, end))
                start = end
            }
            if( ch != null )
                spaces = ch == (' ' as char)
            end += 1
        }
    }

    /** Emitter.write_single_quoted — same folding, plus `''` escaping. */
    private void writeSingleQuoted(String text, boolean split) {
        writeIndicator('\'', true, false, false)
        boolean spaces = false
        int start = 0
        int end = 0
        while( end <= text.length() ) {
            Character ch = end < text.length() ? text.charAt(end) : null
            if( spaces ) {
                if( ch == null || ch != (' ' as char) ) {
                    if( start + 1 == end && column > BEST_WIDTH && split && start != 0 && end != text.length() )
                        writeIndent()
                    else
                        write(text.substring(start, end))
                    start = end
                }
            }
            else if( ch == null || ch == (' ' as char) || ch == ('\n' as char) || ch == ('\'' as char) ) {
                if( start < end ) {
                    write(text.substring(start, end))
                    start = end
                }
            }
            if( ch != null && ch == ('\'' as char) ) {
                write("''")
                start = end + 1
            }
            if( ch != null )
                spaces = ch == (' ' as char)
            end += 1
        }
        writeIndicator('\'', false, false, false)
    }

    /** Emitter.ESCAPE_REPLACEMENTS, for the characters that get a short escape. */
    private static final Map<Character,String> ESCAPE_REPLACEMENTS = [
        ((char) 0x00): '0', ((char) 0x07): 'a', ((char) 0x08): 'b',
        ((char) 0x09): 't', ((char) 0x0A): 'n', ((char) 0x0B): 'v',
        ((char) 0x0C): 'f', ((char) 0x0D): 'r', ((char) 0x1B): 'e',
        ((char) 0x22): '"', ((char) 0x5C): '\\', ((char) 0x85): 'N',
        ((char) 0xA0): '_', ((char) 0x2028): 'L', ((char) 0x2029): 'P',
    ]

    /**
     * Emitter.write_double_quoted. Reached whenever a scalar carries a character
     * the plain and single-quoted styles reject — with PyYAML's default
     * `allow_unicode=False` that means anything outside printable ASCII, so an
     * em dash in a description lands here. Long values fold with a trailing `\`
     * continuation and a leading `\ ` when the break falls on a space.
     */
    private void writeDoubleQuoted(String text, boolean split) {
        writeIndicator('"', true, false, false)
        int start = 0
        int end = 0
        while( end <= text.length() ) {
            Character ch = end < text.length() ? text.charAt(end) : null
            if( ch == null || ch == ('"' as char) || ch == ('\\' as char)
                    || ch == ((char) 0x85) || ch == ((char) 0x2028)
                    || ch == ((char) 0x2029) || ch == ((char) 0xFEFF)
                    || !(ch >= (0x20 as char) && ch <= (0x7E as char)) ) {
                if( start < end ) {
                    write(text.substring(start, end))
                    start = end
                }
                if( ch != null ) {
                    String data
                    if( ESCAPE_REPLACEMENTS.containsKey(ch) )
                        data = '\\' + ESCAPE_REPLACEMENTS.get(ch)
                    else if( Character.isHighSurrogate(ch) && end + 1 < text.length()
                            && Character.isLowSurrogate(text.charAt(end + 1)) ) {
                        // non-BMP character (e.g. an emoji): escaping the two
                        // UTF-16 surrogate units separately produces escapes a
                        // YAML reader rejects, so emit the code point the way
                        // PyYAML does (backslash-U + 8 hex digits) and consume
                        // both units
                        data = String.format('\\U%08X', text.codePointAt(end))
                        end += 1
                    }
                    else if( ((int) ch) <= 0xFF )
                        data = String.format('\\x%02X', (int) ch)
                    else
                        data = String.format('\\u%04X', (int) ch)
                    write(data)
                    start = end + 1
                }
            }
            if( 0 < end && end < text.length() - 1 && (ch == (' ' as char) || start >= end)
                    && column + (end - start) > BEST_WIDTH && split ) {
                // start may sit past end right after an escape; Python's
                // text[start:end] slices to '' there, Java's substring throws
                final data = (start < end ? text.substring(start, end) : '') + '\\'
                if( start < end )
                    start = end
                write(data)
                writeIndent()
                whitespace = false
                indention = false
                if( text.charAt(start) == (' ' as char) )
                    write('\\')
            }
            end += 1
        }
        writeIndicator('"', false, false, false)
    }

    private void write(String data) {
        column += data.length()
        out.append(data)
    }
}
