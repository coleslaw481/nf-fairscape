/*
 * Copyright 2022, Seqera Labs
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

package nextflow.prov

import groovy.transform.CompileStatic
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.script.dsl.Description

@ScopeName('fairscape')
@Description('''
    The `fairscape` scope allows you to configure the `nf-fairscape` plugin,
    which produces a FAIRSCAPE EVI RO-Crate (https://w3id.org/EVI#) for each run.
''')
@CompileStatic
class FairscapeConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        Create the EVI RO-Crate (default: `true` if plugin is loaded).
    ''')
    final boolean enabled

    @ConfigOption
    @Description('''
        The file name of the EVI RO-Crate metadata file. Its parent directory is
        treated as the crate directory and should match the workflow `outputDir`.
    ''')
    final String file

    @ConfigOption
    @Description('''
        When `true` overwrites any existing crate metadata file with the same name (default: `false`).
    ''')
    final boolean overwrite

    @ConfigOption
    @Description('''
        List of file patterns to include in the crate, from the set of published files. By default, all published files are included.
    ''')
    final List<String> patterns

    @ConfigOption
    @Description('''
        The ARK Name Assigning Authority Number used when minting identifiers (default: `59853`).
    ''')
    final String naan

    @ConfigOption
    @Description('''
        The author recorded on the crate and its entities. Defaults to the workflow manifest author, then the local user name.
    ''')
    final String author

    @ConfigOption
    @Description('''
        The description of the crate. Defaults to the workflow manifest description.
    ''')
    final String description

    @ConfigOption
    @Description('''
        Keywords describing the crate (default: `['nextflow', 'workflow']`).
    ''')
    final List<String> keywords

    @ConfigOption
    @Description('''
        The license URL for the crate, e.g. an SPDX license URI. Defaults to the workflow manifest license.
    ''')
    final String license

    @ConfigOption
    @Description('''
        The organization associated with the crate (optional).
    ''')
    final String organization

    @ConfigOption
    @Description('''
        A map of additional metadata merged into the root RO-Crate entity, for
        fields not covered by the dedicated options above (e.g.
        `associatedPublication`, `funder`, `principalInvestigator`, `citation`,
        `conditionsOfAccess`). Keys become properties on the root Dataset. The
        structural keys `@id`, `@type`, `conformsTo` and `hasPart` are managed by
        the plugin and cannot be overridden. Example:
        `fairscape.metadata = [associatedPublication: 'https://doi.org/...', funder: 'NIH ...']`.
    ''')
    final Map metadata

    @ConfigOption
    @Description('''
        Render `ro-crate-datasheet.html` (and `ai_ready_score.json`) next to the
        crate once the run completes, the Groovy equivalent of
        `fairscape build datasheet` (default: `true`).
    ''')
    final boolean datasheet

    @ConfigOption
    @Description('''
        Build `provenance-graph.json` and the self-contained
        `provenance-graph.html` viewer next to the crate once the run completes,
        the Groovy equivalent of `fairscape build evidence-graph`. Also records
        `EVI:inputs`/`EVI:outputs` on the crate root (default: `true`).
    ''')
    final boolean evidenceGraph

    @ConfigOption
    @Description('''
        Render the datasheet as a published release, which turns crate and
        sub-crate identifiers into resolver links (default: `false`).
    ''')
    final boolean published

    @ConfigOption
    @Description('''
        Complete every `owl:inverseOf` pair the EVI ontology declares, so a
        relationship stated once appears on both entities — most visibly
        `generated` on a Computation for every file that names it in
        `generatedBy`. The Groovy equivalent of `fairscape augment
        link-inverses` (default: `true`).
    ''')
    final boolean linkInverses

    @ConfigOption
    @Description('''
        Write `ro-crate-linkml.yaml`, the crate root translated into a D4D
        (Datasheets for Datasets) document, the Groovy equivalent of
        `fairscape build linkml` (default: `true`).
    ''')
    final boolean linkml

    @ConfigOption
    @Description('''
        Describe the files *inside* a published directory as their own Dataset
        entities, each `generatedBy` the task that produced the directory and
        `isPartOf` the directory's Dataset. Without this a process that publishes
        a whole output directory contributes exactly one opaque Dataset with no
        size, format or checksum (default: `false`).
    ''')
    final boolean expandDirectories

    @ConfigOption
    @Description('''
        Glob patterns limiting which files inside a published directory are
        expanded when `expandDirectories` is enabled. Empty (the default) expands
        every file. Patterns are matched against the whole path, e.g.
        `['**/*.tsv', '**/*.csv']`.
    ''')
    final List<String> expandPatterns

    @ConfigOption
    @Description('''
        Maximum number of files expanded per published directory (default:
        `1000`). Directories with more matching files are truncated and a warning
        naming the directory and the number of files dropped is logged.
    ''')
    final int expandMaxFiles

    @ConfigOption
    @Description('''
        Record an `md5` checksum on every Dataset entity that resolves to a
        readable local file (default: `false`). Checksums are what the AI-Ready
        "verifiable" criterion looks for; the cost is one full read of every
        described file.
    ''')
    final boolean checksums

    @ConfigOption
    @Description('''
        Measure directories as well as files: give each directory Dataset the
        recursive `contentSize` of its contents, and the crate root the total
        size of the crate directory (default: `false`). Regular files always
        carry their own `contentSize`; this option only controls the directory
        walks, which are worth opting out of when the crate is published to an
        object store where walking means LIST/HEAD traffic.
    ''')
    final boolean contentSizes

    @ConfigOption
    @Description('''
        Infer an `EVI:Schema` entity for every described csv/tsv file and link it
        from that file's Dataset via `evi:schema` — the Groovy equivalent of
        `fairscape-cli schema infer` (default: `false`).
    ''')
    final boolean schemas

    @ConfigOption
    @Description('''
        Glob patterns selecting which described files get a schema when `schemas`
        is enabled (default: `['**/*.csv', '**/*.tsv']`). Only extensions the
        inferrer supports are ever considered.
    ''')
    final List<String> schemaPatterns

    @ConfigOption
    @Description('''
        Number of data rows read when inferring a schema (default: `100`, the
        frictionless default that `fairscape-cli schema infer` uses).
    ''')
    final int schemaSampleSize

    @ConfigOption
    @Description('''
        Collapse a trailing run of at least this many identically-typed columns
        into a single spanning-array property (`index: "N::"`), so a
        1024-dimension embedding table is described as one `array` column instead
        of 1024 scalar ones. `0` (the default) keeps every column.
    ''')
    final int schemaArrayThreshold

    @ConfigOption
    @Description('''
        Maximum number of schemas inferred per run (default: `500`). Excess files
        are skipped and a warning naming the number skipped is logged.
    ''')
    final int schemaMaxFiles

    @ConfigOption
    @Description('''
        Describe the container each task ran in (default: `false`). Task
        Computations always carry `containerImage`, but that is the image
        REFERENCE the `container` directive resolved to — typically a mutable
        tag. With this enabled the plugin asks the container engine for each
        distinct image's content digest once, and records `containerImage`,
        `containerDigest` and `containerImageId` on the process Software entity
        as well as `containerDigest` on the task Computations, so the crate
        identifies the bits that ran rather than a label that can be repointed
        tomorrow. Note a digest pins content, not availability: a locally built
        image gets a valid digest that exists nowhere else. Off by default
        because it shells out to the container engine once per distinct image.
    ''')
    final boolean containerProvenance

    @ConfigOption
    @Description('''
        Command used to resolve image digests when `containerProvenance` is
        enabled (default: inferred from the enabled container engine, falling
        back to `docker`). Set this when the engine binary is not on the PATH
        under its usual name.
    ''')
    final String containerEngineCommand

    /* required by extension point -- do not remove */
    FairscapeConfig() {}

    FairscapeConfig(Map opts) {
        enabled = opts.enabled != null ? opts.enabled as boolean : true
        file = opts.file ?: 'ro-crate-metadata.json'
        overwrite = opts.overwrite as boolean
        patterns = opts.patterns as List<String> ?: []
        naan = opts.naan ?: '59853'
        author = opts.author
        description = opts.description
        keywords = opts.keywords as List<String> ?: ['nextflow', 'workflow']
        license = opts.license
        organization = opts.organization
        metadata = opts.metadata as Map ?: [:]
        datasheet = opts.datasheet != null ? opts.datasheet as boolean : true
        evidenceGraph = opts.evidenceGraph != null ? opts.evidenceGraph as boolean : true
        published = opts.published as boolean
        linkInverses = opts.linkInverses != null ? opts.linkInverses as boolean : true
        linkml = opts.linkml != null ? opts.linkml as boolean : true
        expandDirectories = opts.expandDirectories as boolean
        expandPatterns = opts.expandPatterns as List<String> ?: []
        expandMaxFiles = opts.expandMaxFiles != null ? opts.expandMaxFiles as int : 1000
        checksums = opts.checksums as boolean
        contentSizes = opts.contentSizes as boolean
        schemas = opts.schemas as boolean
        schemaPatterns = opts.schemaPatterns as List<String> ?: ['**/*.csv', '**/*.tsv']
        schemaSampleSize = opts.schemaSampleSize != null ? opts.schemaSampleSize as int : 100
        schemaArrayThreshold = opts.schemaArrayThreshold != null ? opts.schemaArrayThreshold as int : 0
        schemaMaxFiles = opts.schemaMaxFiles != null ? opts.schemaMaxFiles as int : 500
        containerProvenance = opts.containerProvenance as boolean
        containerEngineCommand = opts.containerEngineCommand
    }
}
