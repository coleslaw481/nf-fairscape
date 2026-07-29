/*
 * Copyright 2022, Seqera Labs
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

package nextflow.prov

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.prov.datasheet.CrateArtifacts
import nextflow.prov.renderers.FairscapeRenderer
import nextflow.trace.TraceObserverV2
import nextflow.trace.event.FilePublishEvent
import nextflow.trace.event.TaskEvent
import nextflow.trace.event.WorkflowOutputEvent

/**
 * Plugin observer of workflow events
 *
 * @author Bruno Grande <bruno.grande@sagebase.org>
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
class ProvObserver implements TraceObserverV2 {

    private Session session

    private List<Renderer> renderers

    private List<PathMatcher> matchers

    private Set<TaskRun> tasks = []

    private Map<String,Path> workflowOutputs

    private Map<Path,Path> publishedFiles = [:]

    private Lock lock = new ReentrantLock()

    private FairscapeConfig config

    ProvObserver(FairscapeConfig config) {
        this.config = config
        this.renderers = [ new FairscapeRenderer(config) as Renderer ]
        this.matchers = config.patterns.collect( pattern ->
            FileSystems.getDefault().getPathMatcher("glob:**/${pattern}")
        )
    }

    @Override
    void onFlowCreate(Session session) {
        this.session = session
    }

    @Override
    void onTaskComplete(TaskEvent event) {
        // skip failed tasks; native (exec) tasks have no exit status,
        // so check the failure flags rather than isSuccess()
        final task = event.handler.task
        if( task.failed || task.aborted )
            return

        lock.withLock {
            tasks << task
        }
    }

    @Override
    void onTaskCached(TaskEvent event) {
        lock.withLock {
            tasks << event.handler.task
        }
    }

    @Override
    void onWorkflowOutput(WorkflowOutputEvent event) {
        if( workflowOutputs == null )
            workflowOutputs = [:]

        final value = event.value instanceof Path
            ? event.value as Path
            : event.index

        if( !value )
            log.warn "Workflow output `${event.name}` should either be a single path or declare an index file in order to be included in provenance reports"

        workflowOutputs[event.name] = value
    }

    @Override
    void onFilePublish(FilePublishEvent event) {
        final match = matchers.isEmpty() || matchers.any { matcher -> matcher.matches(event.target) }
        if( !match )
            return

        lock.withLock {
            publishedFiles[event.source] = event.target
        }
    }

    @Override
    void onFlowComplete() {
        if( !session.isSuccess() )
            return

        boolean rendered = true
        for( final renderer : renderers ) {
            try {
                renderer.render(session, tasks, workflowOutputs, publishedFiles)
            }
            catch( Throwable e ) {
                rendered = false
                log.warn "Error occurred while rendering FAIRSCAPE provenance crate -- see Nextflow log for details"
                log.debug "Error rendering FAIRSCAPE provenance crate", e
            }
        }

        if( rendered )
            renderDerivedArtifacts()
    }

    /**
     * Build the datasheet and evidence graph from the crate that was just
     * written -- the plugin's equivalent of running `fairscape build datasheet`
     * and `fairscape build evidence-graph` on the results directory. Failures
     * are logged and never fail the run, matching how the crate itself is
     * rendered.
     */
    private void renderDerivedArtifacts() {
        if( !config.datasheet && !config.evidenceGraph && !config.linkInverses && !config.linkml )
            return

        try {
            final metadataFile = (config.file as Path).complete()
            if( !Files.exists(metadataFile) ) {
                log.debug "FAIRSCAPE crate not found at ${metadataFile} -- skipping datasheet and provenance graph"
                return
            }
            CrateArtifacts.generate(metadataFile, config.linkInverses, config.evidenceGraph,
                config.linkml, config.datasheet, config.published)
        }
        // Throwable, not Exception: a cyclic or malformed crate can surface as a
        // StackOverflowError, and derived artifacts must never fail the run
        catch( Throwable e ) {
            log.warn "Error occurred while building the FAIRSCAPE datasheet or provenance graph -- see Nextflow log for details"
            log.debug "Error building FAIRSCAPE derived artifacts", e
        }
    }

}
