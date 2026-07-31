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

package nextflow.prov.util

import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Reader for the `versions.yml` file every nf-core module emits, which is the
 * only place a run records the version of the tool a process actually ran:
 *
 * <pre>
 * "NFCORE_DEMO:DEMO:FASTQC":
 *     fastqc: 0.12.1
 * </pre>
 *
 * The shape is fixed by the nf-core module template (one quoted process key,
 * then indented `tool: version` pairs), so this reads it directly rather than
 * pulling in a YAML parser. Anything that does not match is ignored: a version
 * we cannot read must never fail a run, it just leaves the manifest fallback in
 * place.
 *
 * @author FAIRSCAPE
 */
@Slf4j
@CompileStatic
class VersionsYaml {

    /**
     * Bound on what we will read. A per-module file is a few hundred bytes; the
     * run-wide collated one grows with the number of processes.
     */
    private static final long MAX_SIZE = 1024 * 1024

    /**
     * The `tool -> version` pairs in a versions.yml, flattened across process
     * keys (a module reports its own process, so there is normally one key).
     * Returns an empty map for a missing, oversized or unreadable file.
     *
     * @param file a task's `versions.yml` output
     */
    static Map<String,String> read(Path file) {
        final grouped = readGrouped(file)
        final flat = new LinkedHashMap<String,String>()
        grouped.each { process, versions -> flat.putAll(versions) }
        return flat
    }

    /**
     * The whole document as `process -> tool -> version`. This is the shape of
     * the run-wide file nf-core collates into `pipeline_info/` — the only place
     * versions appear at all once a module reports them through a `topic`
     * channel with `eval` instead of writing a versions.yml of its own.
     *
     * @param file a versions.yml, per-module or collated
     */
    static Map<String,Map<String,String>> readGrouped(Path file) {
        try {
            if( file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_SIZE )
                return [:]
            return parseGrouped(new String(Files.readAllBytes(file), 'UTF-8'))
        }
        catch( Exception e ) {
            log.debug("nf-fairscape: could not read tool versions from ${file}", e)
            return [:]
        }
    }

    /**
     * The `tool -> version` pairs of a single-process document.
     *
     * @param text the file contents
     */
    static Map<String,String> parse(String text) {
        final flat = new LinkedHashMap<String,String>()
        parseGrouped(text).each { process, versions -> flat.putAll(versions) }
        return flat
    }

    /**
     * Parse the two-level document: an unindented `name:` line opens a process
     * block, the indented `key: value` lines under it are the tools it reported.
     * Quotes are stripped from both sides, as the template quotes the process
     * name and sometimes the version (`"1.21"` keeps YAML from reading a number).
     *
     * @param text the file contents
     */
    static Map<String,Map<String,String>> parseGrouped(String text) {
        final grouped = new LinkedHashMap<String,Map<String,String>>()
        if( !text )
            return grouped

        String process = null
        for( final line : text.readLines() ) {
            if( !line.trim() || line.trim().startsWith('#') )
                continue

            final colon = line.indexOf(':')
            if( colon < 0 )
                continue
            final key = unquote(line.substring(0, colon).trim())
            final value = unquote(line.substring(colon + 1).trim())

            if( !Character.isWhitespace(line.charAt(0)) ) {
                // an unindented line names the process the versions below belong to
                process = key
                if( process )
                    grouped.computeIfAbsent(process, { k -> new LinkedHashMap<String,String>() })
                continue
            }
            if( process == null || !key || !value )
                continue
            grouped[process].put(key, value)
        }
        return grouped
    }

    /**
     * Render `tool -> version` pairs as the `version` field of a Software
     * entity: a lone tool contributes just its version, several are named so
     * the string stays unambiguous.
     *
     * @param versions pairs as returned by {@link #read}
     */
    static String format(Map<String,String> versions) {
        if( !versions )
            return null
        if( versions.size() == 1 )
            return versions.values().first()
        return versions.collect { tool, version -> "${tool} ${version}" }.join(', ')
    }

    private static String unquote(String value) {
        if( value == null )
            return null
        final trimmed = value.trim()
        if( trimmed.length() >= 2 ) {
            final first = trimmed.charAt(0)
            if( (first == ('"' as char) || first == ("'" as char)) && trimmed.charAt(trimmed.length() - 1) == first )
                return trimmed.substring(1, trimmed.length() - 1)
        }
        return trimmed
    }
}
