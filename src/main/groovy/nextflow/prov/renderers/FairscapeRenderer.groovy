/*
 * Copyright 2023, Seqera Labs
 * Modifications Copyright 2026, FAIRSCAPE (nf-fairscape fork)
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

package nextflow.prov.renderers

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.util.stream.Stream

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.config.Manifest
import nextflow.file.FileHelper
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.prov.FairscapeConfig
import nextflow.prov.Renderer
import nextflow.prov.datasheet.CrateJson
import nextflow.prov.schema.TabularSchemaInferrer
import nextflow.prov.util.ContainerInspector
import nextflow.prov.util.ProvHelper
import nextflow.prov.util.VersionsYaml
import nextflow.script.ScriptMeta
import nextflow.script.WorkflowMetadata
import nextflow.util.Duration
import nextflow.util.MemoryUnit
import nextflow.util.PathNormalizer

/**
 * Renderer for the FAIRSCAPE EVI RO-Crate format.
 *
 * The crate follows the EVI ontology (https://w3id.org/EVI#), the RO-Crate 1.2
 * specification (https://w3id.org/ro/crate/1.2), and the FAIRSCAPE profile
 * (https://w3id.org/fairscape/profile/0.1). Each task execution is recorded as
 * an EVI Computation linked to a parent run-level Computation; files become
 * EVI Datasets and the workflow script and Nextflow engine become EVI Software.
 */
@Slf4j
@CompileStatic
class FairscapeRenderer implements Renderer {

    private static final String EVI_COMPUTATION = 'https://w3id.org/EVI#Computation'
    private static final String EVI_DATASET = 'https://w3id.org/EVI#Dataset'
    private static final String EVI_SOFTWARE = 'https://w3id.org/EVI#Software'
    private static final String EVI_ROCRATE = 'https://w3id.org/EVI#ROCrate'
    private static final String EVI_SCHEMA = 'EVI:Schema'
    private static final String EVI_CONTAINER = 'https://w3id.org/EVI#Container'

    // an absolute URI is resolvable from anywhere; a bare path is only meaningful
    // relative to some base, which is exactly what a crate outside its root lacks
    private static final java.util.regex.Pattern URI_SCHEME = ~/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//

    private static final String FILE_SCHEME = 'file://'

    // where copyWorkflowSources() puts the workflow, relative to the crate root
    private static final String WORKFLOW_DIR = 'workflow'

    private static final Map EVI_CONTEXT = [
        '@vocab': 'https://schema.org/',
        'evi'   : 'https://w3id.org/EVI#',
        'rai'   : 'http://mlcommons.org/croissant/RAI/',
        'prov'  : 'http://www.w3.org/ns/prov#',
        'usedSoftware'   : ['@id': 'https://w3id.org/EVI#usedSoftware', '@type': '@id'],
        'usedDataset'    : ['@id': 'https://w3id.org/EVI#usedDataset', '@type': '@id'],
        'generatedBy'    : ['@id': 'https://w3id.org/EVI#generatedBy', '@type': '@id'],
        'generated'      : ['@id': 'https://w3id.org/EVI#generated', '@type': '@id'],
        'annotates'      : ['@id': 'https://w3id.org/EVI#annotates', '@type': '@id'],
        'hasDistribution': ['@id': 'https://w3id.org/EVI#hasDistribution', '@type': '@id'],
        // RO-Crate's term for "this was at that path when the run happened", used for
        // entities outside the crate root. Spelled the same as in the RO-Crate 1.2
        // context so a consumer reading with either one gets the same IRI; it has to be
        // declared because @vocab would otherwise resolve it to schema.org/localPath.
        'localPath'      : 'https://w3id.org/ro/terms#localPath'
    ]

    private FairscapeConfig config

    private Path path

    private boolean overwrite

    private String naan

    @Delegate
    private PathNormalizer normalizer

    // one Dataset ARK per physical file; published targets alias their work-dir source
    private Map<Path,String> fileArks = [:]

    // files discovered inside a published directory -> that directory's published path
    private Map<Path,Path> expandedParents = [:]

    FairscapeRenderer(FairscapeConfig config) {
        this.config = config
        this.path = (config.file as Path).complete()
        this.overwrite = config.overwrite
        this.naan = config.naan

        ProvHelper.checkFileOverwrite(path, overwrite)
    }

    @Override
    void render(Session session, Set<TaskRun> tasks, Map<String,Path> workflowOutputs, Map<Path,Path> publishedFiles) {
        final taskLookup = ProvHelper.getTaskLookup(tasks)
        // sorted so the same run always lists its inputs in the same order
        // (getWorkflowInputs collects into a hash set)
        final workflowInputs = new LinkedHashSet<Path>(
            ProvHelper.getWorkflowInputs(tasks, taskLookup).sort { Path p -> p.toString() })
        // a samplesheet named by `--input` is read in the workflow body, never
        // staged into a task, so nothing above sees the run's primary input
        if( config.paramInputs )
            workflowInputs.addAll(paramFiles(session.params))

        final metadata = session.workflowMetadata
        final crateDir = path.getParent()
        this.normalizer = new PathNormalizer(metadata)

        final manifest = metadata.manifest
        final formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        final dateStarted = formatter.format(metadata.start)
        final dateCompleted = formatter.format(metadata.complete)
        final nextflowVersion = metadata.nextflow.version.toString()

        // -- crate-level fields, with fallbacks so required EVI fields are never empty
        final runName = (manifest.name ?: metadata.projectName) as String
        final author = (config.author ?: manifest.author ?: manifestContributors(manifest)
            ?: System.getProperty('user.name') ?: 'Unknown') as String
        final license = (config.license ?: manifest.license ?: 'https://spdx.org/licenses/Apache-2.0') as String
        final description = ensureDescription(
            (config.description ?: manifest.description) as String,
            "RO-Crate generated by nf-fairscape for the Nextflow workflow run '${runName}'" as String)
        final keywords = config.keywords
        final version = (manifest.version ?: '1.0') as String

        // -- mint identifiers
        final sessionId = session.uniqueId.toString()
        final rootArk = mintArk(naan, 'rocrate', runName, sessionId)
        final runArk = mintArk(naan, 'computation', runName, sessionId + '#run')
        final workflowArk = mintArk(naan, 'software', runName, normalizePath(metadata.scriptFile) + (manifest.version ?: ''))
        final engineArk = mintArk(naan, 'software', 'nextflow', "nextflow-${nextflowVersion}")

        if( workflowOutputs == null )
            workflowOutputs = [:]

        // -- register every file up front so all references share one ARK per file;
        //    a published file may have no source when written by Nextflow itself (e.g. an index file)
        for( final entry : publishedFiles ) {
            final ark = fileArk(entry.key ?: entry.value)
            fileArks[entry.value] = ark
        }
        workflowInputs.each { source -> fileArk(source) }
        tasks.each { task -> ProvHelper.getTaskOutputs(task).each { target -> fileArk(target) } }
        workflowOutputs.values().each { value ->
            if( value instanceof Path )
                fileArk(value)
        }

        // -- when asked, resolve each distinct container image to the identity of
        //    the bits behind the tag (once per image, not once per task)
        final containers = containerIdentities(session, tasks)

        // -- one EVI Container entity per distinct image (fairscape_models
        //    container.py); task Computations reference it via `usedContainer`.
        //    Additive: the flat containerImage/containerDigest keys on the
        //    Computations and process Software stay for compatibility. The ARK
        //    hashes the digest when known so the identifier names the bits, not
        //    the tag; a digestless (local-only) image falls back to the reference.
        final containerArks = [:] as Map<String,String>
        final containerEntities = containers.values().collect { Map container ->
            final image = container.get('image') as String
            final ark = mintArk(naan, 'container', image, (container.get('repoDigest') ?: image) as String)
            containerArks[image] = ark
            return withoutNulls([
                '@id'        : ark,
                '@type'      : ['prov:Entity', EVI_CONTAINER],
                'name'       : image,
                'author'     : author,
                'description': "Container image '${image}' used by the Nextflow workflow run '${runName}'" as String,
                'keywords'   : keywords,
                'containerImage'  : image,
                'containerDigest' : container.get('repoDigest'),
                'containerImageId': container.get('imageId')
            ])
        }

        // -- a process that publishes a whole directory contributes one opaque
        //    Dataset; optionally describe the files inside it as their own
        //    Datasets, each part of (and generated with) the directory
        final publishedDirArks = [:] as Map<Path,String>
        for( final entry : publishedFiles )
            publishedDirArks[entry.value] = fileArks[entry.key ?: entry.value]
        if( config.expandDirectories )
            expandPublishedDirectories(publishedFiles.values() as Set<Path>)

        // -- software entities
        // The copy inside the crate is the locator that survives being zipped and moved, so
        // it wins over both the repository URL and the on-disk path. `codeRepository` still
        // names where the workflow came from, and the process Software entities still carry
        // commit-pinned module URLs, so nothing is lost by preferring the copy.
        final workflowCopies = config.includeWorkflow ? copyWorkflowSources(metadata, crateDir) : [:]
        // with no copy to point at, a commit-pinned repository URL still beats a path on
        // one machine, so it stays the fallback for a pipeline run from a registry
        final workflowLocator = metadata.repository && !workflowCopies.containsKey(metadata.scriptFile)
            ? ['contentUrl': metadata.repository as String]
            : definitionLocator(metadata.scriptFile, workflowCopies, crateDir)

        final workflowSoftware = withoutNulls([
            '@id'        : workflowArk,
            '@type'      : ['prov:Entity', EVI_SOFTWARE],
            'name'       : runName,
            'author'     : author,
            'description': "Nextflow workflow script for the run '${runName}'" as String,
            'format'     : 'nextflow',
            'version'    : manifest.version ?: metadata.commitId,
            'contentUrl' : workflowLocator['contentUrl'],
            'localPath'  : workflowLocator['localPath'],
            'codeRepository': metadata.repository
        ])

        final engineSoftware = [
            '@id'        : engineArk,
            '@type'      : ['prov:Entity', EVI_SOFTWARE],
            'name'       : 'Nextflow',
            'author'     : 'Seqera Labs',
            'description': 'Nextflow workflow management system (https://www.nextflow.io/)',
            'format'     : 'application/java-archive',
            'version'    : nextflowVersion,
            'contentUrl' : 'https://github.com/nextflow-io/nextflow'
        ]

        // -- one software entity per process; each task points at its own process,
        //    only the run-level computation references the whole workflow script.
        //    A process may describe the actual tool it runs via the `ext` directive:
        //    `ext fairscape: [softwareName: 'tac', softwareVersion: '8.32', ...]` —
        //    those values then replace the process-derived defaults below.
        final processArks = [:] as Map<TaskProcessor,String>
        final collatedVersions = config.toolVersions ? collatedVersions(tasks, publishedFiles) : [:]
        final processSoftware = tasks.collect { task -> task.processor }.unique(false).collect { processor ->
            final scriptPath = ScriptMeta.get(processor.getOwnerScript())?.getScriptPath()
            final scriptUrl = normalizePath(scriptPath ?: metadata.scriptFile)
            // slug from the bare process name, hash from the fully qualified one:
            // `NFCORE_DEMO:DEMO:FASTQC` slugs past the 40-char cap into an
            // unreadable id shared with its siblings, while the id must stay
            // unique per process and stable across -resume
            final ark = mintArk(naan, 'software', bareName(processor.name), scriptUrl + '#' + processor.name)
            processArks[processor] = ark

            final ext = processor.config.get('ext')
            final userMeta = fairscapeExt(ext)
            // surface annotation mistakes without failing the run (research: warn on
            // a non-map value or unrecognized keys the plugin would silently ignore)
            fairscapeExtWarnings(ext).each { warning ->
                log.warn("nf-fairscape: process '${processor.name}' — ${warning}" as String)
            }

            // the unresolved source of the process body, as written in the workflow,
            // minus the delimiters around its script block
            final source = stripScriptDelimiters(processor.getTaskBody()?.getSource())?.stripIndent()

            // the version of the tool this process ran, as the process itself
            // reported it in versions.yml -- the manifest version describes the
            // pipeline, not FastQC
            final reportedVersion = config.toolVersions
                ? VersionsYaml.format(processVersions(processor, tasks, collatedVersions))
                : null

            // the image this process's tasks ran in. `contentUrl` points at the
            // tool's source repository, which says what the software IS but not
            // what was executed; the container is the built artifact, so it
            // belongs on the Software entity and not only on each Computation.
            final container = containers[processContainer(processor, tasks)] ?: [:]

            // an explicit softwareUrl names the tool, not the module file, so it is a
            // locator for something outside the crate by definition
            final processLocator = userMeta.get('softwareUrl')
                ? ['contentUrl': userMeta.get('softwareUrl') as String]
                : definitionLocator(scriptPath ?: metadata.scriptFile, workflowCopies, crateDir)

            return withoutNulls([
                '@id'        : ark,
                '@type'      : ['prov:Entity', EVI_SOFTWARE],
                'name'       : userMeta.get('softwareName') ?: processor.name,
                'author'     : userMeta.get('softwareAuthor') ?: author,
                'description': ensureDescription((userMeta.get('softwareDescription') ?: source?.trim()) as String,
                    "Nextflow process '${processor.name}' defined in ${scriptUrl}" as String),
                'format'     : userMeta.get('softwareFormat') ?: 'nextflow',
                'version'    : userMeta.get('softwareVersion') ?: reportedVersion ?: manifest.version ?: metadata.commitId,
                'contentUrl' : processLocator['contentUrl'],
                'localPath'  : processLocator['localPath'],
                'keywords'   : userMeta.containsKey('softwareKeywords') ? asStringList(userMeta.get('softwareKeywords')) : keywords,
                'isPartOf'   : [ ['@id': workflowArk] ],
                // runtime details beyond the EVI model, preserved as extra keys
                'containerImage' : container.get('image'),
                'containerDigest': container.get('repoDigest'),
                'containerImageId': container.get('imageId')
            ])
        }

        // -- per-task computations
        final taskArks = [:] as Map<TaskRun,String>
        tasks.each { task ->
            taskArks[task] = mintArk(naan, 'computation', bareName(task.name), task.hash.toString())
        }

        final taskComputations = tasks.collect { task ->
            final generated = ProvHelper.getTaskOutputs(task).collect { target -> ['@id': fileArk(target)] }.unique()
            final usedDataset = withoutGenerated(
                ProvHelper.getTaskInputs(task).values().collect { source -> ['@id': fileArk(source)] }.unique(),
                generated)

            return withoutNulls([
                '@id'         : taskArks[task],
                '@type'       : ['prov:Activity', EVI_COMPUTATION],
                'name'        : task.name,
                'description' : "Nextflow task '${task.name}' (process ${task.processor.name})" as String,
                'runBy'       : author,
                'dateCreated' : dateStarted,
                'command'     : task.script?.trim(),
                'usedSoftware': [ ['@id': processArks[task.processor]] ],
                'usedDataset' : usedDataset,
                'generated'   : generated,
                'isPartOf'    : [ ['@id': runArk] ],
                'usedContainer': containerArks.containsKey(task.container as String)
                    ? [ ['@id': containerArks[task.container as String]] ] : null,
                // runtime details beyond the EVI model, preserved as extra keys
                'containerImage': task.container,
                'containerDigest': (containers[task.container as String] ?: [:]).get('repoDigest'),
                'identifier'  : task.hash.toString()
            ])
        }

        // -- parent (run-level) computation
        final runGenerated = (publishedFiles.values() + workflowOutputs.values().findAll { value -> value instanceof Path })
            .collect { target -> ['@id': fileArks[target as Path]] }
            .unique()

        final runComputation = withoutNulls([
            '@id'         : runArk,
            '@type'       : ['prov:Activity', EVI_COMPUTATION],
            'name'        : "Nextflow workflow run of '${runName}'" as String,
            'description' : "Execution of the Nextflow workflow '${runName}' (session ${sessionId})" as String,
            'runBy'       : author,
            'dateCreated' : dateStarted,
            'command'     : metadata.commandLine,
            'parameter'   : foldParams(session.params),
            'usedSoftware': [ ['@id': workflowArk], ['@id': engineArk] ],
            // a run cannot use what it produced: a file Nextflow itself writes and
            // then publishes (the collated versions.yml of a `topic` channel) is
            // both an input no task produced and a run output, and recording both
            // makes the provenance graph cyclic. It stays an output; the task that
            // read it keeps its own usedDataset edge, so no evidence is lost.
            'usedDataset' : withoutGenerated(
                workflowInputs.collect { source -> ['@id': fileArk(source)] }.unique(), runGenerated),
            'generated'   : runGenerated,
            // runtime details beyond the EVI model, preserved as extra keys
            'startTime'   : dateStarted,
            'endTime'     : dateCompleted,
            'nextflowVersion': nextflowVersion,
            'identifier'  : sessionId
        ])

        // -- dataset entities, one per unique ARK, described from the published copy when available
        final publishedByArk = publishedFiles.collectEntries { source, target -> [(fileArks[source ?: target]): target] } as Map<String,Path>
        final producerByArk = [:] as Map<String,String>
        taskLookup.each { source, task -> producerByArk[fileArks[source]] = taskArks[task] }

        // first path registered for each ARK (published targets alias their work-dir source)
        final pathByArk = [:] as Map<String,Path>
        fileArks.each { path, ark -> pathByArk.putIfAbsent(ark, path) }

        // an expanded file inherits the producer of the directory it was found in
        final partOfByArk = [:] as Map<String,String>
        expandedParents.each { file, dir ->
            final parentArk = publishedDirArks[dir]
            partOfByArk[fileArks[file]] = parentArk
            if( producerByArk[parentArk] )
                producerByArk[fileArks[file]] = producerByArk[parentArk]
        }

        final schemas = [] as List<Map>
        final schemaMatchers = globMatchers(config.schemaPatterns)
        int schemasSkipped = 0

        final datasets = fileArks.values().unique(false).collect { ark ->
            final source = pathByArk[ark]
            final target = publishedByArk[ark] ?: source
            final producer = producerByArk[ark]
            final partOf = partOfByArk[ark]

            String schemaArk = null
            if( config.schemas && TabularSchemaInferrer.supports(target) && matchesAny(schemaMatchers, target) ) {
                if( schemas.size() >= config.schemaMaxFiles )
                    schemasSkipped++
                else {
                    final schema = inferSchema(target, ark, runName)
                    if( schema != null ) {
                        schemas << schema
                        schemaArk = schema['@id'] as String
                    }
                }
            }

            final locator = fileLocator(target, crateDir)

            return withoutNulls([
                '@id'          : ark,
                '@type'        : ['prov:Entity', EVI_DATASET],
                'name'         : target.name,
                'author'       : author,
                'description'  : "${Files.isDirectory(target) ? 'Directory' : 'File'} '${target.name}' ${producer ? 'produced' : 'used'} by the Nextflow workflow run '${runName}'" as String,
                'datePublished': dateCompleted,
                'keywords'     : keywords,
                'format'       : getFileFormat(target),
                'generatedBy'  : producer ? [ ['@id': producer] ] : [],
                'isPartOf'     : partOf ? [ ['@id': partOf] ] : null,
                'evi:schema'   : schemaArk ? ['@id': schemaArk] : null,
                'contentUrl'   : locator['contentUrl'],
                'localPath'    : locator['localPath'],
                'contentSize'  : getContentSize(target, config.contentSizes),
                'md5'          : config.checksums ? md5Hex(target) : null
            ])
        }

        if( schemasSkipped > 0 )
            log.warn("nf-fairscape: schema inference capped at ${config.schemaMaxFiles} files (fairscape.schemaMaxFiles); ${schemasSkipped} eligible file(s) have no schema" as String)

        // -- the config files copied in alongside the workflow script
        // The script itself is the workflow Software's contentUrl; its configs are files
        // the crate now contains, so they are described as Datasets rather than left as
        // undeclared bytes in the payload. `isPartOf` the workflow Software says what they
        // configure, and keeps them out of the outputs entailment — nothing generated them,
        // they were an input to the run.
        final configDatasets = workflowCopies
            .findAll { source, relative -> source != metadata.scriptFile }
            .collect { source, relative ->
                withoutNulls([
                    '@id'          : mintArk(naan, 'dataset', source.fileName.toString(), relative),
                    '@type'        : ['prov:Entity', EVI_DATASET],
                    'name'         : source.fileName.toString(),
                    'author'       : author,
                    'description'  : "Nextflow configuration file '${source.fileName}' for the run '${runName}'" as String,
                    'datePublished': dateCompleted,
                    'keywords'     : keywords,
                    'format'       : 'nextflow-config',
                    'generatedBy'  : [],
                    'isPartOf'     : [ ['@id': workflowArk] ],
                    'contentUrl'   : relative,
                    'contentSize'  : getContentSize(crateDir.resolve(relative), config.contentSizes),
                    'md5'          : config.checksums ? md5Hex(crateDir.resolve(relative)) : null
                ])
            }

        // -- root dataset and metadata descriptor
        final hasPart = ([runArk] + taskArks.values() + [workflowArk, engineArk] + processArks.values() + containerArks.values() + fileArks.values().unique(false) + configDatasets.collect { it['@id'] as String } + schemas.collect { it['@id'] as String })
            .collect { id -> ['@id': id] }

        final Map root = withoutNulls([
            '@id'          : rootArk,
            '@type'        : ['Dataset', EVI_ROCRATE],
            'conformsTo'   : ['@id': 'https://w3id.org/fairscape/profile/0.1'],
            'name'         : runName,
            'description'  : description,
            'keywords'     : keywords,
            'version'      : version,
            'author'       : author,
            'license'      : license,
            'datePublished': dateCompleted,
            'publisher'    : config.organization,
            'contentSize'  : config.contentSizes ? crateContentSize(crateDir) : null,
            'hasPart'      : hasPart
        ])

        // -- overlay user-supplied workflow-level metadata onto the root entity
        //    (long-tail fields not covered by dedicated config options). Structural
        //    keys are managed by the plugin and cannot be overridden.
        mergeRootMetadata(root, config.metadata)

        final descriptor = [
            '@id'       : 'ro-crate-metadata.json',
            '@type'     : 'CreativeWork',
            'conformsTo': ['@id': 'https://w3id.org/ro/crate/1.2'],
            'about'     : ['@id': rootArk]
        ]

        final crate = [
            '@context': EVI_CONTEXT,
            '@graph'  : [descriptor, root, runComputation] + taskComputations + [workflowSoftware, engineSoftware] + processSoftware + containerEntities + datasets + configDatasets + schemas
        ]

        // render crate to JSON and write to file
        path.text = JsonOutput.prettyPrint(JsonOutput.toJson(crate))
    }

    /**
     * Where a Dataset's bytes are, split across the two properties that can say it:
     *
     *   under the crate directory     -> contentUrl, crate-relative   `tables/x.tsv`
     *   outside it, with a scheme     -> contentUrl, absolute         `https://…`, `s3://…`
     *   outside it, bare path         -> localPath                    `work/ab/cd…/x.tsv`
     *
     * A RELATIVE contentUrl is a promise that the file is at that path inside the crate,
     * so it may only be made for files that actually are — RO-Crate resolves a relative
     * reference against the crate root, and 1.2 gives no way to spell one that escapes it
     * (https://www.researchobject.org/ro-crate/specification/1.2/data-entities.html).
     * Published outputs qualify; a work-directory intermediate does not, and emitting one
     * for it made the crate assert a file was present where nothing was — 44 of 130
     * entities on nf-core/differentialabundance.
     *
     * "Under the crate directory" only means anything within ONE filesystem, hence the
     * provider guard. It holds the comparison together across storage backends: a crate
     * written to `s3://bucket/results` compares two S3 paths and still recognizes its own
     * published files, while a crate written locally never claims to contain an s3:// one.
     * The guard also keeps a false match from reaching `relativize`, which throws
     * ProviderMismatchException across providers (nf-azure's `startsWith` compares path
     * STRINGS and does not check the provider itself).
     *
     * The entity keeps its name, md5, size and every provenance edge whichever branch it
     * takes, so the derivation chain through unpublished intermediates stays intact.
     */
    private Map<String,String> fileLocator(Path target, Path crateDir) {
        final sameStorage = target.fileSystem.provider() == crateDir.fileSystem.provider()
        if( sameStorage && target.startsWith(crateDir) )
            return ['contentUrl': crateDir.relativize(target).toString()]
        return outsideLocator(normalizePath(target))
    }

    /**
     * Where a Software entity's definition is: the copy inside the crate when
     * `includeWorkflow` put one there, else wherever the file itself sits.
     *
     * A process defined in `main.nf` is defined in the `main.nf` the crate carries, so it
     * has to name that copy. Without this the workflow Software pointed at
     * `workflow/main.nf` while every process defined in the same file pointed at
     * `/home/you/…/main.nf` — an absolute path on one machine, describing bytes that were
     * sitting in the crate all along. nf-core modules are unaffected: they are not copied
     * in, so they keep normalizing to their commit-pinned repository URLs.
     *
     * @param source the file that defines the entity
     * @param copies original path -> crate-relative path, from {@link #copyWorkflowSources}
     * @param crateDir the crate root
     */
    private Map<String,String> definitionLocator(Path source, Map<Path,String> copies, Path crateDir) {
        final copy = copies[source]
        return copy ? ['contentUrl': copy] : fileLocator(source, crateDir)
    }

    /**
     * Copy the workflow script and its configs into `<crateDir>/workflow/`.
     *
     * A crate that cannot say what was run is not much of a provenance record, and a
     * reference to the source does not survive being moved: a local pipeline normalizes to
     * `file:///home/you/…`, which resolves on exactly one machine, and a registry pipeline
     * lives in `~/.nextflow/assets`, a cache shared by every run of it. Copying the files in
     * is the only form that survives zipping the crate and handing it to someone.
     *
     * Returns original path -> crate-relative path for whatever was copied, so callers can
     * point `contentUrl` at the copy. Best-effort: a file that cannot be read is skipped
     * with a WARN and its entity falls back to the reference it would have had.
     */
    protected Map<Path,String> copyWorkflowSources(WorkflowMetadata metadata, Path crateDir) {
        final copied = [:] as Map<Path,String>
        final sources = ([metadata.scriptFile] + (metadata.configFiles ?: []))
            .findAll { it != null && Files.isRegularFile(it) }
            .unique(false)
        if( !sources )
            return copied

        // created once rather than per file: on an object-store crate directory each call
        // is a PUT of a directory marker, and if it fails no copy can succeed anyway
        final dir = crateDir.resolve(WORKFLOW_DIR)
        try {
            Files.createDirectories(dir)
        }
        catch( Exception e ) {
            log.warn("nf-fairscape: could not create '${dir}' to hold the workflow copy: ${e.message}" as String)
            return copied
        }

        // distinct config files routinely share a basename (the pipeline's nextflow.config
        // and the -c one), so a name that is already taken by a DIFFERENT source is
        // disambiguated rather than silently overwritten
        final used = [] as Set<String>
        for( final source : sources ) {
            String name = source.fileName.toString()
            if( !used.add(name) ) {
                final dot = name.lastIndexOf('.')
                final stem = dot > 0 ? name.substring(0, dot) : name
                final ext = dot > 0 ? name.substring(dot) : ''
                int n = 2
                while( !used.add("${stem}-${n}${ext}" as String) ) n++
                name = "${stem}-${n}${ext}" as String
            }
            try {
                Files.copy(source, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                copied[source] = "${WORKFLOW_DIR}/${name}" as String
            }
            catch( Exception e ) {
                log.warn("nf-fairscape: could not copy '${source}' into the crate: ${e.message}" as String)
            }
        }
        return copied
    }

    /**
     * Split a PathNormalizer result for a file that is NOT in the crate.
     *
     * Two rewrites have already happened upstream of this, and the first one is easy to
     * miss: a path under the WORK DIRECTORY comes back as a bare `work/ab/cd…` with its
     * scheme stripped, so a cloud work dir (`s3://bucket/work/…`) arrives here looking
     * exactly like a local one and takes the same `localPath` branch. A path under the
     * project directory comes back as a commit-pinned repository URL.
     *
     * `file://` is the odd one out: it is syntactically absolute, so it looks retrievable,
     * but it resolves only on the machine that ran the pipeline — PathNormalizer emits it
     * for any local absolute path it cannot rewrite to a repository URL. That is precisely
     * what `localPath` is for, so the scheme is stripped and it goes there. A `https://`
     * or `s3://` locator (a remote input, an igenomes reference) resolves for anyone and
     * stays `contentUrl`.
     */
    protected static Map<String,String> outsideLocator(String normalized) {
        if( !normalized )
            return [:]
        if( normalized.startsWith(FILE_SCHEME) )
            return ['localPath': normalized.substring(FILE_SCHEME.length())]
        return URI_SCHEME.matcher(normalized).find()
            ? ['contentUrl': normalized]
            : ['localPath': normalized]
    }

    /**
     * Get (or mint) the Dataset ARK for a file.
     *
     * @param source
     */
    protected String fileArk(Path source) {
        return fileArks.computeIfAbsent(source, p -> mintArk(naan, 'dataset', p.name, normalizePath(p)))
    }

    /**
     * Mint a deterministic ARK identifier:
     * ark:{naan}/{prefix}-{name-slug}-{sha1(sourceId)[0..6]}
     *
     * @param naan
     * @param prefix
     * @param name
     * @param sourceId
     */
    static String mintArk(String naan, String prefix, String name, String sourceId) {
        return "ark:${naan}/${prefix}-${slugify(name)}-${sha1Hex(sourceId).substring(0, 7)}"
    }

    static String slugify(String name) {
        String slug = (name ?: '').toLowerCase().replaceAll(/[^a-z0-9]+/, '-').replaceAll(/^-+|-+$/, '')
        if( slug.length() > 40 )
            slug = slug.substring(0, 40).replaceAll(/-+$/, '')
        return slug ?: 'unnamed'
    }

    static String sha1Hex(String value) {
        return MessageDigest.getInstance('SHA-1').digest(value.getBytes('UTF-8')).encodeHex().toString()
    }

    /**
     * Ensure a description meets the EVI minimum length of 10 characters.
     *
     * @param value
     * @param fallback
     */
    static String ensureDescription(String value, String fallback) {
        return value && value.length() >= 10 ? value : fallback
    }

    /**
     * The crate author taken from `manifest.contributors`, which is where
     * nf-core records authorship now that `manifest.author` is deprecated —
     * without this every modern pipeline falls through to the local username.
     * Contributors credited as authors win; when none is, everyone is named.
     *
     * @param manifest
     */
    static String manifestContributors(Manifest manifest) {
        final contributors = manifest?.getContributors()
        if( !contributors )
            return null
        final authors = contributors.findAll { contributor ->
            contributor.contribution?.any { type -> String.valueOf(type).equalsIgnoreCase('author') }
        }
        final names = (authors ?: contributors).collect { contributor -> contributor.name }.findAll { name -> name }
        return names ? names.join(', ') : null
    }

    /**
     * A process or task name without its enclosing workflow scope:
     * `NFCORE_DEMO:DEMO:FASTQC` -> `FASTQC`. Only used for the readable part of
     * an ARK — the identifier still hashes the fully qualified name, so ids stay
     * unique between two aliases of one module and stable across `-resume`.
     *
     * @param name
     */
    static String bareName(String name) {
        if( !name )
            return name
        final colon = name.lastIndexOf(':')
        return colon < 0 ? name : name.substring(colon + 1).trim()
    }

    /**
     * Remove the delimiters around a process body's script block. The body is
     * not simply a quoted string: every nf-core module opens with a Groovy
     * prologue (`def args = task.ext.args ?: ''`) before the triple-quoted
     * command, so stripping only leading and trailing delimiters left the
     * opening one in place and deleted its partner.
     *
     * @param source the process body source, as written in the workflow
     */
    static String stripScriptDelimiters(String source) {
        if( !source )
            return source
        final matcher = source =~ /(?s)^(.*?)("""|''')(.*)\2\s*$/
        return matcher.matches() ? (matcher.group(1) + matcher.group(3)) as String : source
    }

    /**
     * Reference lists minus anything the same Computation generated. Nothing may
     * be its own ancestor: an activity that both used and generated a file would
     * close a cycle in the provenance graph, and every consumer of the graph —
     * the evidence-graph walk, the condenser, any SPARQL over it — assumes a DAG.
     *
     * @param used      the `usedDataset` refs
     * @param generated the `generated` refs of the same Computation
     */
    static List withoutGenerated(List used, List generated) {
        if( !used || !generated )
            return used
        final produced = generated.collect { ref -> ((Map) ref)['@id'] } as Set
        return used.findAll { ref -> !produced.contains(((Map) ref)['@id']) }
    }

    /**
     * Files named by pipeline parameters, so the run records inputs it never
     * staged into a task. A value qualifies when it looks like a path to a file
     * — it contains a separator and its last segment has an extension — which
     * keeps out base directories and URL prefixes (`igenomes_base`,
     * `custom_config_base`). Local paths must exist; a remote URI is taken at
     * its word rather than fetched, since resolving it is network I/O the user
     * did not ask for.
     *
     * @param params the run's resolved parameters
     */
    protected List<Path> paramFiles(Map<String,Object> params) {
        final List<Path> found = []
        for( final entry : (params ?: [:]) ) {
            final value = entry.value
            if( !(value instanceof CharSequence) && !(value instanceof Path) )
                continue
            final text = value.toString()
            if( !text || !text.contains('/') || !fileShaped(text) )
                continue
            try {
                final path = value instanceof Path ? (Path) value : FileHelper.asPath(text)
                final remote = path.getFileSystem() != FileSystems.getDefault()
                if( remote || Files.isRegularFile(path) )
                    found << path
            }
            catch( Exception e ) {
                // an unresolvable or inaccessible value is just not an input
                log.debug("nf-fairscape: could not resolve param '${entry.key}' as a file", e)
            }
        }
        return found
    }

    /** True when the last segment of a path-like string carries an extension. */
    private static boolean fileShaped(String text) {
        final name = text.substring(text.lastIndexOf('/') + 1)
        final dot = name.lastIndexOf('.')
        return dot > 0 && dot < name.length() - 1
    }

    /**
     * The `tool -> version` pairs a process reported, from whichever of the two
     * places this pipeline's modules use: the `versions.yml` each task writes,
     * or the run-wide file nf-core collates in `pipeline_info/` — a module that
     * reports through a `topic` channel with `eval` writes no file of its own,
     * so the collated one is the only record of what ran.
     *
     * @param processor
     * @param tasks     every task of the run
     * @param collated  process -> tool -> version, keyed as the collated file keys it
     */
    protected Map<String,String> processVersions(TaskProcessor processor, Set<TaskRun> tasks,
                                                 Map<String,Map<String,String>> collated) {
        for( final task : tasks ) {
            if( task.processor != processor )
                continue
            for( final output : ProvHelper.getTaskOutputs(task) ) {
                if( output?.getFileName()?.toString() != 'versions.yml' )
                    continue
                final versions = VersionsYaml.read(output)
                if( versions )
                    return versions
            }
        }
        // nf-core's collated file keys the bare process name, but a pipeline
        // writing `task.process` verbatim keys the qualified one
        return collated.get(bareName(processor.name)) ?: collated.get(processor.name) ?: [:]
    }

    /**
     * Every `process -> tool -> version` block the run recorded in a collated
     * versions file, merged. Candidates are the versions-shaped yaml files the
     * run published or fed to a task (`nf_core_<pipeline>_software_mqc_versions.yml`,
     * `collated_versions.yml`); anything that does not parse contributes nothing.
     *
     * @param tasks          every task of the run
     * @param publishedFiles published source -> target
     */
    protected Map<String,Map<String,String>> collatedVersions(Set<TaskRun> tasks, Map<Path,Path> publishedFiles) {
        final candidates = new LinkedHashSet<Path>()
        for( final entry : publishedFiles )
            candidates.add(entry.value)
        for( final task : tasks )
            candidates.addAll(ProvHelper.getTaskInputs(task).values())

        final merged = [:] as Map<String,Map<String,String>>
        for( final candidate : candidates ) {
            final name = candidate?.getFileName()?.toString()
            if( !name || !(name ==~ /(?i).*versions.*\.ya?ml/) || name == 'versions.yml' )
                continue
            VersionsYaml.readGrouped(candidate).each { process, versions ->
                if( versions )
                    merged.computeIfAbsent(process, { key -> [:] as Map<String,String> }).putAll(versions)
            }
        }
        return merged
    }

    /**
     * The keys nf-fairscape understands inside a process `ext.fairscape` map.
     * Each overrides one field of that process's Software entity; anything else
     * is ignored (and warned about via {@link #fairscapeExtWarnings}).
     */
    static final List<String> KNOWN_EXT_KEYS = [
        'softwareName', 'softwareVersion', 'softwareAuthor',
        'softwareDescription', 'softwareUrl', 'softwareFormat', 'softwareKeywords'
    ].asImmutable() as List<String>

    /**
     * Root-crate keys the plugin manages itself; user-supplied
     * `fairscape.metadata` may not override these without breaking the graph.
     */
    static final List<String> PROTECTED_ROOT_KEYS = [
        '@id', '@type', 'conformsTo', 'hasPart'
    ].asImmutable() as List<String>

    /**
     * The container image every task of a process ran in, or null when the
     * process ran natively or its tasks disagree. Tasks of one process normally
     * share an image; if a dynamic `container` directive gave them different
     * ones there is no single image to put on the process Software entity, and
     * the per-task Computations already carry the truth, so return nothing
     * rather than pick one arbitrarily.
     */
    static String processContainer(TaskProcessor processor, Set<TaskRun> tasks) {
        final images = tasks.findAll { it.processor == processor }
            .collect { it.container as String }
            .findAll { it }
            .unique(false)
        return images.size() == 1 ? images[0] : null
    }

    /**
     * Map of image reference -> `[image:, repoDigest:, imageId:]` for every
     * distinct container used by the run. Empty unless
     * `fairscape.containerProvenance` is on; empty too when no container engine
     * is enabled or the engine cannot answer, since every field it feeds is
     * dropped by withoutNulls and the crate falls back to what it always had.
     */
    private Map<String,Map> containerIdentities(Session session, Set<TaskRun> tasks) {
        if( !config.containerProvenance )
            return [:]
        final engine = config.containerEngineCommand ?: ContainerInspector.engineFor(session.config)
        if( !engine ) {
            log.warn('nf-fairscape: fairscape.containerProvenance is enabled but no container engine is enabled; set fairscape.containerEngineCommand to resolve image digests')
            return [:]
        }
        final inspector = new ContainerInspector(engine)
        final result = [:] as Map<String,Map>
        for( final image : tasks.collect { it.container as String }.findAll { it }.unique(false) ) {
            final identity = inspector.inspect(image)
            result[image] = ([image: image] + identity) as Map
            if( !identity.get('repoDigest') )
                log.debug("nf-fairscape: '${image}' has no registry digest (built locally and never pushed?); recording its image id only" as String)
        }
        return result
    }

    /**
     * Extract the user-supplied software metadata from a process `ext` directive
     * value (`ext fairscape: [softwareName: ..., ...]`). Returns an empty map when
     * absent or malformed, so callers can fall back to process-derived defaults.
     *
     * @param ext
     */
    static Map fairscapeExt(Object ext) {
        final value = ext instanceof Map ? ext.get('fairscape') : null
        return value instanceof Map ? value : Collections.emptyMap()
    }

    /**
     * Validate a process `ext.fairscape` annotation and return human-readable
     * warnings for anything the plugin will silently ignore: a non-map value
     * (e.g. `ext fairscape: ['made-up-property']`) or unrecognized keys. Returns
     * an empty list when the annotation is absent or fully valid. Callers log the
     * result; nothing here fails the run.
     *
     * @param ext the whole `ext` directive value (a map keyed by directive name)
     */
    static List<String> fairscapeExtWarnings(Object ext) {
        if( !(ext instanceof Map) || !((Map) ext).containsKey('fairscape') )
            return []
        final value = ((Map) ext).get('fairscape')
        if( !(value instanceof Map) ) {
            final type = value == null ? 'null' : value.getClass().getSimpleName()
            return ["ext.fairscape must be a map like [softwareName: 'tac', softwareVersion: '8.32'] " +
                "but was a ${type}; the annotation will be ignored" as String]
        }
        final unknown = ((Map) value).keySet().findAll { key -> !KNOWN_EXT_KEYS.contains(key) }
        if( unknown )
            return ["ext.fairscape has unrecognized key(s) ${unknown.toList()} that will be ignored; " +
                "supported keys are ${KNOWN_EXT_KEYS}" as String]
        return []
    }

    /**
     * Overlay user-supplied root metadata onto the root crate map in place,
     * skipping null values and plugin-managed structural keys (a skipped
     * structural key is warned about).
     *
     * @param root     the assembled root crate map (mutated)
     * @param metadata the user's `fairscape.metadata` map (may be empty)
     */
    protected void mergeRootMetadata(Map root, Map metadata) {
        if( !metadata )
            return
        metadata.each { key, value ->
            final k = key as String
            if( PROTECTED_ROOT_KEYS.contains(k) )
                log.warn("nf-fairscape: fairscape.metadata key '${k}' is managed by the plugin and cannot be overridden; ignoring" as String)
            else if( value != null )
                root.put(k, value)
        }
    }

    /**
     * Coerce a user-supplied keywords value into a list of strings: a list is
     * mapped element-wise, a scalar is wrapped in a singleton list, null stays
     * null. Keeps `keywords` well-formed even when written as a bare string.
     *
     * @param value
     */
    static List<String> asStringList(Object value) {
        if( value == null )
            return null
        if( value instanceof List )
            return ((List) value).collect { item -> item?.toString() } as List<String>
        return [value.toString()]
    }

    /**
     * Fold workflow params into a list of "name: value" strings.
     *
     * @param params
     */
    protected List<String> foldParams(Map<String,Object> params) {
        return params.collect { name, value -> "${name}: ${normalizeParamValue(value)}" as String }
    }

    private String normalizeParamValue(Object value) {
        if( value == null )
            return 'null'
        if( value instanceof Path )
            return normalizePath(value.toString())
        if( value instanceof Duration || value instanceof MemoryUnit )
            return value.toString()
        if( value instanceof List || value instanceof Map )
            return JsonOutput.toJson(value)
        return String.valueOf(value)
    }

    // ------------------------------------------------------------------
    // Published-directory expansion
    // ------------------------------------------------------------------

    /**
     * Register the files inside each published directory so they become Dataset
     * entities of their own. Without this a process whose output is a directory
     * contributes a single Dataset with no size, format, checksum or schema —
     * every file it actually produced stays invisible to the crate.
     *
     * @param targets the published paths, of which the directories are expanded
     */
    protected void expandPublishedDirectories(Set<Path> targets) {
        final matchers = globMatchers(config.expandPatterns)
        for( final target : targets ) {
            if( target == null || !Files.isDirectory(target) )
                continue
            List<Path> files
            try {
                files = walkFiles(target, matchers)
            }
            catch( Exception e ) {
                log.warn("nf-fairscape: unable to list '${target}' for expansion -- describing it as a single Dataset" as String)
                log.debug("Error expanding published directory ${target}", e)
                continue
            }
            if( files.size() > config.expandMaxFiles ) {
                log.warn("nf-fairscape: '${target}' holds ${files.size()} matching files, more than fairscape.expandMaxFiles=${config.expandMaxFiles}; describing the first ${config.expandMaxFiles} and omitting ${files.size() - config.expandMaxFiles}" as String)
                files = files.subList(0, config.expandMaxFiles)
            }
            for( final file : files ) {
                fileArk(file)
                expandedParents[file] = target
            }
        }
    }

    /** Regular files under a directory, sorted so ARKs come out in a stable order. */
    private static List<Path> walkFiles(Path dir, List<PathMatcher> matchers) {
        final List<Path> found = []
        final Stream<Path> stream = Files.walk(dir)
        try {
            for( final Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
                final Path path = it.next()
                if( Files.isRegularFile(path) && matchesAny(matchers, path) )
                    found.add(path)
            }
        }
        finally {
            stream.close()
        }
        return found.sort { Path path -> path.toString() }
    }

    private static List<PathMatcher> globMatchers(List<String> patterns) {
        return (patterns ?: []).collect { pattern ->
            FileSystems.getDefault().getPathMatcher("glob:${pattern}" as String)
        }
    }

    /** An empty matcher list means "no filter", matching every path. */
    private static boolean matchesAny(List<PathMatcher> matchers, Path path) {
        return matchers.isEmpty() || matchers.any { matcher -> matcher.matches(path) }
    }

    // ------------------------------------------------------------------
    // Schema inference
    // ------------------------------------------------------------------

    /**
     * Infer an EVI:Schema for a tabular file, the Groovy equivalent of
     * `fairscape-cli schema infer`. Returns null (with a warning) when the file
     * cannot be described, since a missing schema must never fail a run.
     *
     * @param target      the file to describe
     * @param datasetArk  the ARK of the Dataset the schema belongs to
     * @param runName     used in the schema description
     */
    protected Map inferSchema(Path target, String datasetArk, String runName) {
        try {
            final schemaArk = mintArk(naan, 'schema', target.name, datasetArk)
            return TabularSchemaInferrer.infer(
                target,
                schemaArk,
                target.name,
                "Inferred schema for '${target.name}' from the Nextflow workflow run '${runName}'" as String,
                config.schemaSampleSize,
                config.schemaArrayThreshold,
                config.schemaCommentChar)
        }
        catch( Exception e ) {
            log.warn("nf-fairscape: could not infer a schema for '${target.name}' -- describing the file without one" as String)
            log.debug("Error inferring schema for ${target}", e)
            return null
        }
    }

    // ------------------------------------------------------------------
    // File description
    // ------------------------------------------------------------------

    private static String getFileFormat(Path target) {
        return ProvHelper.getEncodingFormat(target) ?: target.getExtension() ?: 'unknown'
    }

    /**
     * Byte count of a file, or — only when the caller opted into directory
     * measurement — the recursive byte count of a directory. A directory has no
     * size of its own, so measuring one means walking it, which is why it is
     * opt-in: on a crate published to an object store that walk is LIST/HEAD
     * traffic the user never asked for.
     */
    private static String getContentSize(Path target, boolean measureDirectories) {
        try {
            if( Files.isRegularFile(target) )
                return Files.size(target).toString()
            if( measureDirectories && Files.isDirectory(target) )
                return String.valueOf(treeSize(target))
            return null
        }
        catch( Exception e ) {
            return null
        }
    }

    /**
     * The crate's total payload as a human-readable string. Without it the
     * AI-Ready scorer falls back to summing the Dataset `contentSize` values,
     * which counts a directory and the files inside it twice.
     */
    private static String crateContentSize(Path crateDir) {
        try {
            return Files.isDirectory(crateDir) ? CrateJson.formatSize(treeSize(crateDir)) : null
        }
        catch( Exception e ) {
            return null
        }
    }

    private static long treeSize(Path dir) {
        long total = 0
        final Stream<Path> stream = Files.walk(dir)
        try {
            for( final Iterator<Path> it = stream.iterator(); it.hasNext(); ) {
                final Path path = it.next()
                if( !Files.isRegularFile(path) )
                    continue
                try {
                    total += Files.size(path)
                }
                catch( Exception e ) {
                    // an unreadable file contributes nothing to the total
                }
            }
        }
        finally {
            stream.close()
        }
        return total
    }

    /** MD5 of a regular file, streamed; null for directories and unreadable paths. */
    private static String md5Hex(Path target) {
        try {
            if( !Files.isRegularFile(target) )
                return null
            final digest = MessageDigest.getInstance('MD5')
            final byte[] buffer = new byte[1 << 16]
            final InputStream stream = Files.newInputStream(target)
            try {
                int read
                while( (read = stream.read(buffer)) != -1 )
                    digest.update(buffer, 0, read)
            }
            finally {
                stream.close()
            }
            return digest.digest().encodeHex().toString()
        }
        catch( Exception e ) {
            return null
        }
    }

    private static Map withoutNulls(Map map) {
        return map.findAll { k, v -> v != null }
    }

}
