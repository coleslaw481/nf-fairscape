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

package nextflow.prov.parity

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

/**
 * Shared plumbing for the parity suite: run fairscape-cli, prepare a fixture
 * crate for both implementations, and compare their output.
 *
 * <p>fairscape-cli is the ground truth. Every spec in this package makes the two
 * implementations consume the SAME crate directory and then asserts on what each
 * wrote, so a failure means the Groovy port and the CLI have diverged -- not that
 * the two were fed different inputs.
 *
 * <p>The CLI is invoked as {@code python3 -m fairscape_cli} so it resolves to
 * whatever is installed for that interpreter; override with the
 * {@code FAIRSCAPE_CLI} environment variable (e.g. {@code FAIRSCAPE_CLI=fairscape-cli}).
 * When it is not importable the specs skip, so `make test` stays runnable without
 * a Python environment. CI sets {@code FAIRSCAPE_PARITY_REQUIRED=1}, which makes
 * {@link CliAvailableTest} fail rather than let the suite pass green-but-skipped.
 *
 * @author FAIRSCAPE
 */
@CompileStatic
class CliParity {

    /** Fixture crates, frozen by tools/make-parity-fixtures.sh. */
    static final Path FIXTURES = Path.of('src/test/resources/parity')

    /** Every fixture the whole-crate specs run against. */
    static final List<String> CRATES = ['letters-chain', 'nf-test', 'fanout']

    private static Boolean available
    private static String versionText

    /** The CLI invocation, as a command prefix. */
    static List<String> command() {
        final override = System.getenv('FAIRSCAPE_CLI')
        return override ? override.split(' ').toList() : ['python3', '-m', 'fairscape_cli']
    }

    /** True when the configured CLI runs. Probed once per JVM. */
    static synchronized boolean available() {
        if( available == null ) {
            try {
                final probe = exec(command() + ['--help'], null)
                available = probe.exit == 0
            }
            catch( Exception e ) {
                available = false
            }
        }
        return available
    }

    /**
     * The CLI version parity is being claimed against, for failure messages --
     * a diff usually means the CLI moved, and the version is the first thing to
     * look at.
     */
    static synchronized String version() {
        if( versionText == null ) {
            try {
                final r = exec(['python3', '-c',
                    'import importlib.metadata as m; print(m.version("fairscape-cli"))'], null)
                versionText = r.exit == 0 ? r.output.trim() : 'unknown'
            }
            catch( Exception e ) {
                versionText = 'unknown'
            }
        }
        return versionText
    }

    /** Run the CLI, failing loudly with its own output when it exits non-zero. */
    static void cli(List<String> args, Path cwd = null) {
        final result = exec(command() + args, cwd)
        if( result.exit != 0 )
            throw new IllegalStateException(
                "fairscape-cli ${args.join(' ')} exited ${result.exit}:\n${result.output}")
    }

    static Result exec(List<String> argv, Path cwd) {
        final builder = new ProcessBuilder(argv).redirectErrorStream(true)
        if( cwd )
            builder.directory(cwd.toFile())
        final process = builder.start()
        final output = process.inputStream.getText('UTF-8')
        if( !process.waitFor(10, TimeUnit.MINUTES) ) {
            process.destroyForcibly()
            throw new IllegalStateException("timed out: ${argv.join(' ')}")
        }
        return new Result(exit: process.exitValue(), output: output)
    }

    static class Result {
        int exit
        String output
    }

    /**
     * Copy a fixture crate into a fresh temp directory. Derived artifacts are
     * never in a fixture (see tools/make-parity-fixtures.sh), so both sides
     * start from the renderer's crate and nothing else.
     */
    static Path fixture(String name) {
        final source = FIXTURES.resolve(name)
        if( !Files.isDirectory(source) )
            throw new IllegalStateException("no parity fixture at ${source.toAbsolutePath()}")
        final target = Files.createTempDirectory("nf-fairscape-parity-${name}-")
        copyTree(source, target)
        return target
    }

    /** Copy a directory tree, replacing whatever is at the destination. */
    static void copyTree(Path source, Path target) {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                    StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** The root RO-Crate entity's @id -- the ARK the CLI's evidence-graph takes. */
    static String rootArk(Path metadataFile) {
        final graph = (List<Map>) new JsonSlurper().parse(metadataFile.toFile())['@graph']
        final root = graph.find { Map e -> e['@type'].toString().contains('ROCrate') }
        if( !root )
            throw new IllegalStateException("no ROCrate entity in ${metadataFile}")
        return root['@id'] as String
    }

    /**
     * Compare two @graph lists as GRAPHS rather than as documents: the CLI writes
     * `json.dump(indent=2)` through its pruner and we write `JsonOutput.prettyPrint`,
     * so key order and whitespace differ by construction and only the content is
     * meaningful. Returns one line per difference, empty when identical.
     */
    static List<String> diffGraphs(List<Map> expected, List<Map> actual) {
        final left = expected.collectEntries { Map e -> [(e['@id'] as String): e] } as Map<String, Map>
        final right = actual.collectEntries { Map e -> [(e['@id'] as String): e] } as Map<String, Map>
        final problems = new ArrayList<String>()

        for( final id : (left.keySet() - right.keySet()).sort() )
            problems << "only the CLI has entity ${id}".toString()
        for( final id : (right.keySet() - left.keySet()).sort() )
            problems << "only the plugin has entity ${id}".toString()

        for( final id : (left.keySet().intersect(right.keySet()) as Set<String>).sort() ) {
            final a = left[id]
            final b = right[id]
            for( final key : (a.keySet() - b.keySet()).sort() )
                problems << "${id}: only the CLI has ${key}".toString()
            for( final key : (b.keySet() - a.keySet()).sort() )
                problems << "${id}: only the plugin has ${key}".toString()
            for( final key : (a.keySet().intersect(b.keySet()) as Set<String>).sort() ) {
                if( canonical(a[key]) != canonical(b[key]) )
                    problems << "${id}.${key}: cli=${trim(a[key])} plugin=${trim(b[key])}".toString()
            }
        }
        return problems
    }

    /**
     * Compare two artifacts byte for byte. Returns null when identical, else a
     * SHORT report -- these files run to hundreds of kilobytes, and a Spock
     * condition on the contents themselves renders both of them into the failure
     * message and buries the one line that actually differs.
     */
    static String byteDifference(Path expected, Path actual) {
        final a = Files.readAllBytes(expected)
        final b = Files.readAllBytes(actual)
        if( Arrays.equals(a, b) )
            return null
        return "${actual.fileName} differs from fairscape-cli ${version()} " +
            "(cli ${a.length} bytes, plugin ${b.length} bytes)\n" +
            textDifference(new String(a, 'UTF-8'), new String(b, 'UTF-8'))
    }

    /** As {@link #byteDifference}, for text already in hand. Null when equal. */
    static String textDifference(String expected, String actual) {
        if( expected == actual )
            return null
        final left = expected.readLines()
        final right = actual.readLines()
        final out = new StringBuilder()
        int shown = 0
        for( int i = 0; i < Math.max(left.size(), right.size()) && shown < 8; i++ ) {
            final a = i < left.size() ? left[i] : '<end of file>'
            final b = i < right.size() ? right[i] : '<end of file>'
            if( a != b ) {
                out << "  line ${i + 1}:\n    cli    ${a.trim().take(150)}\n    plugin ${b.trim().take(150)}\n"
                shown++
            }
        }
        return out.toString() ?: '  contents differ only in trailing bytes'
    }

    /** Order-insensitive rendering of a JSON value, for comparison only. */
    private static String canonical(Object value) {
        if( value instanceof Map )
            return '{' + ((Map) value).collectEntries { k, v -> [(k): canonical(v)] }
                .sort { Map.Entry e -> e.key.toString() }
                .collect { Map.Entry e -> "${e.key}=${e.value}" }.join(',') + '}'
        if( value instanceof List )
            // a JSON-LD multi-value is a set of references; the CLI's rdflib pass
            // and our sorted pass agree on membership, not on order
            return '[' + ((List) value).collect { canonical(it) }.sort().join(',') + ']'
        return String.valueOf(value)
    }

    private static String trim(Object value) {
        final s = String.valueOf(value)
        return s.length() > 90 ? s.substring(0, 90) + '...' : s
    }

    /**
     * Normalize a datasheet for comparison. Two known differences survive, both
     * documented in docs/DATASHEET.md:
     *
     * <ol>
     * <li>The three summary stat cards (Datasets/Computations/Software counts).
     *     The CLI's `evi:totalEntities == 0` guard never fires -- pydantic dumps
     *     the declared field as None -- so its per-crate fallback is dead code
     *     and it emits no cards. We run the fallback.
     * <li>The order of the composition patterns. fairscape_models builds them
     *     with `list(set(...))` (conversion/mapping/subcrate_utils.py), so the
     *     CLI's OWN output flips with PYTHONHASHSEED -- byte parity on this line
     *     is unreachable in principle, and the content is a set either way.
     * </ol>
     */
    static String normalizeDatasheet(String html) {
        final withoutCards = html.replaceAll(/(?s)\s*<div class="stat-card">.*?<\/div>/, '')
        return withoutCards.replaceAll(/(?s)(<span class="stat-value small">)(.*?)(<\/span>)/) { List<String> m ->
            final divs = (m[2] =~ /<div>.*?<\/div>/).collect { it.toString() }.sort()
            return divs ? m[1] + divs.join('') + m[3] : m[0]
        }
    }
}
