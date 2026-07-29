/*
 * Copyright 2026, FAIRSCAPE (nf-fairscape)
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

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Resolves a container image reference to the identity of the bits behind it.
 *
 * A task's `container` directive gives a reference like
 * `cm4ai/cellmaps_ppidownloader:0.2.2`. That names a tag, and a tag is mutable:
 * re-pushed tomorrow it points at different bytes, and a crate that records only
 * the tag cannot tell the two runs apart. This asks the container engine what
 * the reference actually resolved to:
 *
 *   repoDigest  `repo@sha256:...` — the content digest the engine reports for
 *               the image under a repository name.
 *   imageId     `sha256:...` — the engine's local id for the image.
 *
 * What those two mean depends on the image store, and it is worth being precise
 * because the obvious reading is wrong. Under Docker's **containerd** image
 * store (the default in recent Docker), `Id` IS the manifest digest, so both
 * values are the same string — verified here against a pulled image
 * (`cm4ai/cellmaps_ppidownloader:0.2.2` → both `sha256:85b359d3…`, matching the
 * digest Docker Hub serves) and against a locally-built one
 * (`nf-cellmaps/ppidownloader:0.2.2` → both `sha256:5fae2c5a…`). Under the
 * classic image store `Id` is the config digest and differs, and an image built
 * locally and never pushed reports no repoDigest at all.
 *
 * So: a digest identifies the exact bits, and that is what makes it worth
 * recording. It does NOT by itself prove the image can be pulled from anywhere —
 * a locally built image gets a perfectly good digest that exists on one machine.
 * Retrievability depends on the repository actually having been pushed, which
 * nothing visible from the local engine can confirm.
 *
 * Everything here is best-effort. If the engine is missing, the image is gone,
 * or the call times out, the resolver returns nulls and the crate simply keeps
 * the plain `containerImage` it always had.
 */
@Slf4j
@CompileStatic
class ContainerInspector {

    /** How long to wait for a single inspect call before giving up on it. */
    static final long TIMEOUT_MS = 10_000

    private final String engine
    private final Map<String,Map> cache = [:]

    ContainerInspector(String engine) {
        this.engine = engine ?: 'docker'
    }

    /**
     * Pick the CLI to ask, given the Nextflow config. Singularity and Apptainer
     * are deliberately absent: they run image FILES (or convert on the fly), so
     * there is no daemon holding a digest to ask for, and guessing would produce
     * confident nonsense. Set `fairscape.containerEngineCommand` to override.
     */
    static String engineFor(Map config) {
        if( config == null )
            return null
        for( final name : ['docker', 'podman'] ) {
            final scope = config.get(name)
            if( scope instanceof Map && (scope as Map).get('enabled') )
                return name
        }
        return null
    }

    /**
     * `[repoDigest: String|null, imageId: String|null]` for an image reference,
     * or an empty map when nothing could be resolved. Cached per reference: a
     * six-stage pipeline with 200 tasks still makes at most six calls.
     */
    Map inspect(String image) {
        if( !image )
            return [:]
        if( cache.containsKey(image) )
            return cache[image]
        final result = parse(image, run(image))
        cache[image] = result
        return result
    }

    /**
     * Turn `docker image inspect` output into the two identities. Split out from
     * the process call so it can be tested without a container engine present.
     */
    static Map parse(String image, String json) {
        if( !json?.trim() )
            return [:]
        try {
            final parsed = new JsonSlurper().parseText(json.trim())
            final entry = (parsed instanceof List ? (parsed as List)[0] : parsed) as Map
            if( entry == null )
                return [:]
            final id = entry.get('Id') as String
            final digests = (entry.get('RepoDigests') ?: []) as List
            return withoutNulls([
                repoDigest: pickDigest(image, digests.collect { it as String }),
                imageId   : id ?: null
            ])
        }
        catch( Exception e ) {
            log.debug("nf-fairscape: could not parse inspect output for '${image}': ${e.message}" as String)
            return [:]
        }
    }

    /**
     * An image can carry several repo digests (same bits, known under more than
     * one repository). Prefer the one whose repository matches the reference the
     * workflow actually named, so the recorded digest reads as the same image
     * the config asked for rather than an alias of it.
     */
    static String pickDigest(String image, List<String> digests) {
        if( !digests )
            return null
        final repo = repositoryOf(image)
        final match = digests.find { it && repositoryOf(it) == repo }
        return match ?: digests.find { it }
    }

    /** Repository part of a reference: strip any `@sha256:...` then any `:tag`. */
    static String repositoryOf(String ref) {
        if( !ref )
            return null
        String s = ref
        final at = s.indexOf('@')
        if( at > 0 )
            s = s.substring(0, at)
        final colon = s.lastIndexOf(':')
        // a colon before the last slash is a registry port, not a tag separator
        if( colon > 0 && colon > s.lastIndexOf('/') )
            s = s.substring(0, colon)
        return s
    }

    private String run(String image) {
        try {
            // stderr is discarded rather than left attached: an undrained pipe
            // that fills up would block the child until the timeout
            final proc = new ProcessBuilder([engine, 'image', 'inspect', '--format', '{{json .}}', image])
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            final out = new StringBuilder()
            final reader = Thread.start { proc.inputStream.eachLine { out.append(it).append('\n') } }
            if( !proc.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) ) {
                proc.destroyForcibly()
                log.debug("nf-fairscape: '${engine} image inspect ${image}' timed out" as String)
                return null
            }
            reader.join(TIMEOUT_MS)
            if( proc.exitValue() != 0 ) {
                log.debug("nf-fairscape: '${engine} image inspect ${image}' exited ${proc.exitValue()}" as String)
                return null
            }
            return out.toString()
        }
        catch( Exception e ) {
            // engine not installed, not on PATH, not running — all non-fatal
            log.debug("nf-fairscape: could not run '${engine} image inspect ${image}': ${e.message}" as String)
            return null
        }
    }

    private static Map withoutNulls(Map map) {
        return map.findAll { k, v -> v != null } as Map
    }
}
