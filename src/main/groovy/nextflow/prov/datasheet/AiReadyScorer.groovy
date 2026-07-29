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

import groovy.transform.CompileDynamic

/**
 * Port of fairscape_models.conversion.mapping.AIReady.score_rocrate and the
 * default sub-criterion texts in conversion.models.AIReady.
 *
 * Produces the same nested {category: {sub_criterion: {has_content, details}}}
 * structure the CLI writes to ai_ready_score.json and feeds to the datasheet's
 * summary donut.
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class AiReadyScorer {

    /** Category -> [display label, sub-criteria] (SummarySectionGenerator.CATEGORY_MAP). */
    static final Map<String,List> CATEGORY_MAP = [
        fairness                : ['Fairness', ['findable', 'accessible', 'interoperable', 'reusable']],
        provenance              : ['Provenance', ['transparent', 'traceable', 'interpretable', 'key_actors_identified']],
        characterization        : ['Characterization', ['semantics', 'statistics', 'standards', 'potential_sources_of_bias', 'data_quality']],
        pre_model_explainability: ['Explainability', ['data_documentation_template', 'fit_for_purpose', 'verifiable']],
        ethics                  : ['Ethics', ['ethically_acquired', 'ethically_managed', 'ethically_disseminated', 'secure']],
        sustainability          : ['Sustainability', ['persistent', 'domain_appropriate', 'well_governed', 'associated']],
        computability           : ['Computability', ['standardized', 'computationally_accessible', 'portable', 'contextualized']],
    ]

    private static Map sub(boolean hasContent, String details) {
        return [has_content: hasContent, details: details]
    }

    /** The pydantic default_factory values for every sub-criterion. */
    private static Map defaults(String name) {
        return [
            name                    : name,
            fairness                : [
                findable     : sub(false, "No persistent identifier found. To add an identifier, set 'identifier' (for DOI) or '@id' in root dataset"),
                accessible   : sub(true, "The RO-Crate's JSON-LD metadata is machine-readable and publicly accessible by design."),
                interoperable: sub(true, 'The dataset uses the schema.org vocabulary within the RO-Crate framework and conforms to the Croissant RAI specification for interoperability.'),
                reusable     : sub(false, "No license specified. To add a license, set 'license' in root dataset"),
            ],
            provenance              : [
                transparent          : sub(false, "No root datasets identified. To document datasets, add entities with @type 'Dataset' to metadata graph"),
                traceable            : sub(false, "No transformation steps documented. To document workflows, add entities with @type 'Computation' or 'Experiment' to metadata graph"),
                interpretable        : sub(false, "No software documented. To document software, add entities with @type 'Software' to metadata graph"),
                key_actors_identified: sub(false, "No key actors identified. To add actors, set 'author', 'publisher', or 'principalInvestigator' in root dataset"),
            ],
            characterization        : [
                semantics                : sub(true, 'Data is semantically described using the schema.org vocabulary within a machine-readable RO-Crate.'),
                statistics               : sub(false, "No statistical characterization available. To add statistics, set 'contentSize' and/or 'hasSummaryStatistics' in Dataset/ROCrate entities"),
                standards                : sub(false, "No schemas provided for datasets. To document schemas, add entities with @type 'schema' to metadata graph"),
                potential_sources_of_bias: sub(false, "No bias description provided. To document biases, set 'rai:dataBiases' in root dataset"),
                data_quality             : sub(false, "Data quality procedures not documented. To document quality, set 'rai:dataCollectionMissingData' in root dataset"),
            ],
            pre_model_explainability: [
                data_documentation_template: sub(true, "Documentation is provided via the RO-Crate's structured JSON-LD metadata, this HTML Datasheet, and Croissant RAI properties."),
                fit_for_purpose            : sub(false, "No use cases or limitations specified. To document purpose, set 'rai:dataUseCases' and/or 'rai:dataLimitations' in root dataset"),
                verifiable                 : sub(false, "No checksums available. To add checksums for verification, set 'md5' or 'sha256' in Dataset/Software/ROCrate entities"),
            ],
            ethics                  : [
                ethically_acquired    : sub(false, "No ethical acquisition information. To document data collection, set 'rai:dataCollection' and/or additionalProperty with name='Human Subject' in root dataset"),
                ethically_managed     : sub(false, "No ethical management information. To document ethical oversight, set 'ethicalReview' and/or additionalProperty with name='Data Governance Committee' in root dataset"),
                ethically_disseminated: sub(false, "No dissemination controls specified. To document usage controls, set 'license', 'rai:personalSensitiveInformation', and/or additionalProperty with name='Prohibited Uses' in root dataset"),
                secure                : sub(false, "No security requirements specified. To document security level, set 'confidentialityLevel' in root dataset"),
            ],
            sustainability          : [
                persistent        : sub(false, "No persistent identifier found. To add an identifier, set 'identifier' (for DOI) or '@id' in root dataset"),
                domain_appropriate: sub(false, "Data release plan not documented. To add a release plan, set 'rai:dataReleaseMaintenancePlan' in root dataset"),
                well_governed     : sub(false, "No governance structure specified. To document governance, set additionalProperty with name='Data Governance Committee' in root dataset"),
                associated        : sub(true, "All data, software, and computations are explicitly linked within the RO-Crate's provenance graph."),
            ],
            computability           : [
                standardized              : sub(false, "No format information available. To document file formats, set 'format' in Dataset/Software entities"),
                computationally_accessible: sub(false, "No publisher provided. To specify publisher, set 'publisher' in root dataset"),
                portable                  : sub(true, 'The dataset is packaged as a self-contained RO-Crate, a standard designed for portability across systems.'),
                contextualized            : sub(true, "Context is provided by the RO-Crate's graph structure and detailed in properties such as rai:dataLimitations."),
            ],
        ]
    }

    // ------------------------------------------------------------------

    static Map score(List<Map> graph, Map root) {
        final result = defaults("AI-Ready Score for ${root?.get('name')}".toString())
        scoreFairness(result.fairness, root)
        scoreProvenance(result.provenance, root, graph)
        scoreCharacterization(result.characterization, root, graph)
        scorePreModel(result.pre_model_explainability, root, graph)
        scoreEthics(result.ethics, root)
        scoreSustainability(result.sustainability, root)
        scoreComputability(result.computability, root, graph)
        return result
    }

    private static boolean present(Object value) {
        // Python-truthiness first: `if value and str(value).strip()` is falsy for
        // an empty list/map, but String.valueOf([]) is "[]" and would score a point
        if( value instanceof Collection && ((Collection) value).isEmpty() )
            return false
        if( value instanceof Map && ((Map) value).isEmpty() )
            return false
        return value != null && String.valueOf(value).trim()
    }

    /** additionalProperty lookup used as a fallback by several criteria. */
    private static Object additionalProperty(Map root, List<String> names) {
        for( final prop : CrateJson.asList(root?.get('additionalProperty')) ) {
            if( prop instanceof Map && (prop['name'] as String) in names )
                return prop['value']
        }
        return null
    }

    private static void scoreFairness(Map fairness, Map root) {
        final doi = root?.get('identifier')
        final idVal = root?.get('@id')
        if( present(doi) )
            fairness.findable = sub(true, "Dataset has DOI: ${doi}".toString())
        else if( present(idVal) )
            fairness.findable = sub(true, "Dataset has persistent identifier: ${idVal}".toString())

        final license = root?.get('license')
        if( present(license) )
            fairness.reusable = sub(true, "License: ${license}".toString())
    }

    private static void scoreProvenance(Map provenance, Map root, List<Map> graph) {
        final actors = []
        final author = root?.get('author')
        if( author )
            actors << (author instanceof List ? "${author.size()} authors".toString() : 'Author specified')

        final publisher = root?.get('publisher')
        if( publisher )
            actors << (publisher instanceof Map
                ? "Publisher: ${publisher['name'] ?: 'Unknown'}".toString()
                : "Publisher: ${publisher}".toString())

        final pi = root?.get('principalInvestigator')
        if( pi )
            actors << "PI: ${pi}".toString()

        if( actors )
            provenance.key_actors_identified = sub(true, actors.join(', '))

        int datasets = 0
        int transformations = 0
        int software = 0

        if( root?.get('evi:datasetCount') != null ) {
            datasets = root['evi:datasetCount'] as int
            transformations = (root['evi:computationCount'] ?: 0) as int
            software = (root['evi:softwareCount'] ?: 0) as int
        }
        else {
            for( final entity : graph ) {
                final type = CrateJson.lastType(entity)
                if( type.contains('Dataset') ) datasets++
                if( type.contains('Computation') || type.contains('Experiment') ) transformations++
                if( type.contains('Software') ) software++
            }
        }

        if( datasets > 0 )
            provenance.transparent = sub(true, "${datasets} dataset(s) documented".toString())
        if( transformations > 0 )
            provenance.traceable = sub(true, "${transformations} computation/experiment steps documented".toString())
        if( software > 0 )
            provenance.interpretable = sub(true, "${software} software instances documented".toString())
    }

    private static void scoreCharacterization(Map characterization, Map root, List<Map> graph) {
        final bias = root?.get('rai:dataBiases')
        if( present(bias) )
            characterization.potential_sources_of_bias = sub(true, truncate(String.valueOf(bias), 200))

        final missing = root?.get('rai:dataCollectionMissingData')
        if( present(missing) )
            characterization.data_quality = sub(true, truncate(String.valueOf(missing), 200))

        int schemaCount = 0
        if( root?.get('evi:schemaCount') != null ) {
            // pre-aggregated counts are deliberately ignored upstream (pass)
            schemaCount = 0
        }
        else {
            for( final entity : graph )
                if( CrateJson.lastType(entity).contains('Schema') )
                    schemaCount++
        }
        if( schemaCount > 0 )
            characterization.standards = sub(true, "${schemaCount} schema(s) documented".toString())

        double totalSize
        int statsCount
        if( root?.get('evi:totalContentSizeBytes') != null ) {
            totalSize = (root['evi:totalContentSizeBytes'] as Number).doubleValue()
            statsCount = (root['evi:entitiesWithSummaryStats'] ?: 0) as int
        }
        else {
            statsCount = 0
            double summed = 0d
            final rootId = root?.get('@id')
            final seen = new HashSet()
            for( final entity : graph ) {
                final type = CrateJson.lastType(entity)
                if( type.contains('Dataset') || type.contains('ROCrate') ) {
                    if( entity['hasSummaryStatistics'] )
                        statsCount++
                    final entityId = entity['@id']
                    if( entityId == rootId || seen.contains(entityId) )
                        continue
                    if( entityId )
                        seen << entityId
                    summed += CrateJson.parseContentSizeBytes(entity['contentSize'])
                }
            }
            final rootSize = CrateJson.parseContentSizeBytes(root?.get('contentSize'))
            totalSize = rootSize ?: summed
        }

        final details = []
        if( totalSize > 0 ) {
            if( totalSize >= 1e12 )      details << "Total size: ${CrateJson.fixed(totalSize / 1e12, 1)} TB".toString()
            else if( totalSize >= 1e9 )  details << "Total size: ${CrateJson.fixed(totalSize / 1e9, 1)} GB".toString()
            else                         details << "Total size: ${CrateJson.fixed(totalSize / 1e6, 1)} MB".toString()
        }
        if( statsCount > 0 )
            details << "Summary statistics available for ${statsCount} dataset(s)".toString()

        if( details )
            characterization.statistics = sub(true, details.join(', '))
    }

    private static void scorePreModel(Map preModel, Map root, List<Map> graph) {
        final details = []
        final useCases = root?.get('rai:dataUseCases')
        final limitations = root?.get('rai:dataLimitations')
        if( present(useCases) )
            details << "Use cases: ${useCases}".toString()
        if( present(limitations) )
            details << "Limitations: ${limitations}".toString()
        if( details )
            preModel.fit_for_purpose = sub(true, details.join(', '))

        int total
        int withChecksum
        if( root?.get('evi:totalEntities') != null ) {
            total = root['evi:totalEntities'] as int
            withChecksum = (root['evi:entitiesWithChecksums'] ?: 0) as int
        }
        else {
            total = 0
            withChecksum = 0
            for( final entity : graph ) {
                final type = CrateJson.lastType(entity)
                if( type.contains('Dataset') || type.contains('Software') || type.contains('ROCrate') ) {
                    total++
                    if( entity['md5'] || entity['MD5'] || entity['sha256'] || entity['SHA256'] || entity['hash'] )
                        withChecksum++
                }
            }
        }

        if( total > 0 && withChecksum > 0 ) {
            final percentage = (withChecksum / (double) total) * 100
            preModel.verifiable = sub(true, "${CrateJson.fixed(percentage, 0)}% of files have checksums (${withChecksum}/${total})".toString())
        }
    }

    private static void scoreEthics(Map ethics, Map root) {
        def details = []
        final collection = root?.get('rai:dataCollection')
        if( present(collection) )
            details << "Data collection: ${collection}".toString()

        def hs = root?.get('humanSubjectResearch')
        if( !hs )
            hs = additionalProperty(root, ['Human Subject Research', 'Human Subject'])
        if( hs )
            details << "Human subject info: ${hs}".toString()
        if( details )
            ethics.ethically_acquired = sub(true, details.join(', '))

        details = []
        final review = root?.get('ethicalReview')
        if( present(review) )
            details << "Ethical review: ${review}".toString()

        def gov = root?.get('dataGovernanceCommittee')
        if( !gov )
            gov = additionalProperty(root, ['Data Governance Committee'])
        if( gov )
            details << "Governance: ${gov}".toString()
        if( details )
            ethics.ethically_managed = sub(true, details.join(', '))

        details = []
        final license = root?.get('license')
        if( license )
            details << "License: ${license}".toString()

        final psi = root?.get('rai:personalSensitiveInformation')
        if( present(psi) )
            details << "Sensitive info: ${psi}".toString()

        def prohibited = root?.get('prohibitedUses')
        if( !prohibited )
            prohibited = additionalProperty(root, ['Prohibited Uses'])
        if( prohibited )
            details << "Prohibited uses: ${prohibited}".toString()
        if( details )
            ethics.ethically_disseminated = sub(true, details.join(', '))

        final conf = root?.get('confidentialityLevel')
        if( present(conf) )
            ethics.secure = sub(true, "Confidentiality level: ${conf}".toString())
    }

    private static void scoreSustainability(Map sustainability, Map root) {
        final doi = root?.get('identifier')
        final idVal = root?.get('@id')
        if( present(doi) )
            sustainability.persistent = sub(true, "Dataset has DOI: ${doi}".toString())
        else if( present(idVal) )
            sustainability.persistent = sub(true, "Dataset has persistent identifier: ${idVal}".toString())

        final maint = root?.get('rai:dataReleaseMaintenancePlan')
        if( present(maint) )
            sustainability.domain_appropriate = sub(true, 'Maintenance plan: ' + maint)

        def gov = root?.get('dataGovernanceCommittee')
        if( !gov )
            gov = additionalProperty(root, ['Data Governance Committee'])
        if( gov )
            sustainability.well_governed = sub(true, "Governance committee: ${gov}".toString())
    }

    private static void scoreComputability(Map computability, Map root, List<Map> graph) {
        Collection formats
        if( root?.get('evi:formats') != null ) {
            formats = new LinkedHashSet(CrateJson.asList(root['evi:formats']))
        }
        else {
            formats = new LinkedHashSet()
            for( final entity : graph ) {
                final type = CrateJson.lastType(entity)
                if( type.contains('Dataset') || type.contains('Software') ) {
                    final fmt = entity['format']
                    if( fmt )
                        formats << String.valueOf(fmt)
                }
            }
        }

        if( formats ) {
            final list = new ArrayList(formats).sort().take(5)
            final suffix = formats.size() > 5 ? '...' : ''
            computability.standardized = sub(true, "Formats: ${list.join(', ')}${suffix}".toString())
        }

        if( root?.get('publisher') )
            computability.computationally_accessible = sub(true, "Publisher: ${root['publisher']}".toString())
    }

    private static String truncate(String value, int limit) {
        return value.length() > limit ? value.substring(0, limit) + '...' : value
    }
}
