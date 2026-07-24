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
    }
}
