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

import java.nio.file.Files
import java.nio.file.Path

import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j

/**
 * Groovy port of `fairscape build datasheet`
 * (fairscape_cli.datasheet_builder.rocrate.DatasheetGenerator plus the section
 * generators and the FairscapeDatasheet mapping configuration).
 *
 * Renders ro-crate-datasheet.html from a crate's ro-crate-metadata.json and
 * writes the AI-Ready score alongside it as ai_ready_score.json, using the
 * templates vendored under src/main/resources/fairscape/templates.
 *
 * Scope note: crates emitted by this plugin are always single crates, so the
 * sub-crate branch of the CLI (loading nested crates, ro-crate-preview.html per
 * sub-crate, the LinkML sidecar and the PDF export) is not ported — the CLI's
 * `_build_single_crate_composition` path is what runs here.
 *
 * @author FAIRSCAPE
 */
@Slf4j
@CompileDynamic
class DatasheetGenerator {

    private final Path metadataFile
    private final Path baseDir
    private final boolean published

    private Map crate
    private List<Map> graph
    private Map root
    private Map index

    DatasheetGenerator(Path metadataFile, boolean published = false) {
        this.metadataFile = metadataFile
        this.baseDir = metadataFile.parent
        this.published = published
    }

    /** Render the datasheet and return the path written. */
    Path generate(Path outputPath = null) {
        crate = CrateJson.read(metadataFile)
        graph = CrateJson.graphOf(crate)
        root = CrateJson.rootEntity(graph)
        if( root == null )
            throw new IllegalStateException("Could not find the root dataset entity in ${metadataFile}")

        index = [:]
        for( final entity : graph )
            if( entity['@id'] )
                index[entity['@id']] = entity

        final output = outputPath ?: baseDir.resolve('ro-crate-datasheet.html')

        final overview = overviewContext()
        final subcrate = subcrateContext()

        final context = [
            title              : overview.title ?: 'Untitled RO-Crate',
            version            : overview.version,
            doi                : overview.doi,
            license_value      : overview.license_value,
            release_date       : overview.release_date,
            content_size       : overview.content_size,
            summary_section    : render('sections/summary.html', summaryContext()),
            overview_section   : render('sections/overview.html', overview),
            use_cases_section  : render('sections/use_cases.html', useCasesContext()),
            distribution_section: render('sections/distribution.html', distributionContext()),
            subcrates_section  : render('sections/subcrates.html', [subcrates: [subcrate], subcrate_count: 1]),
            subcrate_count     : 1,
            subcrates          : [[name: subcrate.name ?: 'Unnamed Sub-Crate']],
        ]

        Files.write(output, render('base.html', context).getBytes('UTF-8'))
        return output
    }

    private static String render(String template, Map context) {
        return MiniJinja.render(CrateJson.template(template), context)
    }

    // ------------------------------------------------------------------
    // Overview section (OVERVIEW_MAPPING + OverviewSectionGenerator)
    // ------------------------------------------------------------------

    private Map overviewContext() {
        return [
            title                   : str(root['name']) ?: 'Untitled RO-Crate',
            description             : str(root['description']),
            id_value                : str(root['@id']),
            doi                     : str(root['identifier']),
            license_value           : str(root['license']),
            ethical_review          : str(root['ethicalReview']),

            release_date            : str(root['datePublished']),
            created_date            : str(root['dateCreated']),
            updated_date            : str(root['dateModified']),

            authors                 : resolveAuthors().join(', '),
            publisher               : str(root['publisher']),
            principal_investigator  : str(root['principalInvestigator']),
            contact_email           : str(root['contactEmail']),

            copyright               : str(root['copyrightNotice']),
            terms_of_use            : str(root['conditionsOfAccess']),
            confidentiality_level   : str(root['confidentialityLevel']),
            citation                : root['citation'] ?: '',

            version                 : str(root['version']),
            content_size            : str(root['contentSize']),
            funding                 : asListStr(root['funder']).join(', '),
            keywords                : root['keywords'] ?: [],
            completeness            : withAdditionalProperty(root['completeness'], 'Completeness', null) ?: '',

            human_subject_research  : withAdditionalProperty(root['humanSubjectResearch'], 'Human Subject Research', '') ?: 'No',
            human_subject_exemptions: withAdditionalProperty(root['humanSubjectExemption'], 'Human Subjects Exemptions', '') ?: 'N/A',
            deidentified_samples    : withAdditionalProperty(boolToYesNo(root['deidentified']), 'De-identified Samples', '') ?: 'Yes',
            fda_regulated           : withAdditionalProperty(boolToYesNo(root['fdaRegulated']), 'FDA Regulated', '') ?: 'No',
            irb                     : withAdditionalProperty(irbPassthrough(root['irb']), 'IRB', '') ?: 'N/A',
            irb_protocol_id         : withAdditionalProperty(root['irbProtocolId'], 'IRB Protocol ID', '') ?: 'N/A',
            data_governance         : withAdditionalProperty(root['dataGovernanceCommittee'], 'Data Governance Committee', null) ?: '',

            related_publications    : relatedPublications(root['associatedPublication']),

            published               : published,
        ]
    }

    /** _resolve_authors: keep name strings, resolve {"@id": ...} stubs via the graph. */
    private List<String> resolveAuthors() {
        final raw = root['author']
        if( raw == null )
            return []

        final nameById = [:]
        for( final item : graph )
            if( item['@id'] && item['name'] )
                nameById[item['@id']] = item['name']

        final resolved = []
        for( final entry : CrateJson.asList(raw) ) {
            if( entry instanceof CharSequence ) {
                final s = entry.toString().trim()
                if( s )
                    resolved << s
            }
            else if( entry instanceof Map ) {
                final inline = entry['name']
                if( inline instanceof CharSequence && inline.toString().trim() ) {
                    resolved << inline.toString().trim()
                    continue
                }
                final refId = entry['@id']
                if( refId && nameById.containsKey(refId) )
                    resolved << nameById[refId] as String
            }
        }
        return resolved
    }

    // ------------------------------------------------------------------
    // Use cases section (USECASES_MAPPING)
    // ------------------------------------------------------------------

    private Map useCasesContext() {
        return [
            intended_uses                 : listToStr(root['rai:dataUseCases']),
            limitations                   : listToStr(root['rai:dataLimitations']),
            prohibited_uses               : withAdditionalProperty(root['prohibitedUses'], 'Prohibited Uses', null) ?: '',
            maintenance_plan              : str(root['rai:dataReleaseMaintenancePlan']),
            potential_bias                : listToStr(root['rai:dataBiases']),

            data_collection               : str(root['rai:dataCollection']),
            data_collection_type          : listToStr(root['rai:dataCollectionType']),
            data_collection_missing_data  : str(root['rai:dataCollectionMissingData']),
            data_collection_raw_data      : str(root['rai:dataCollectionRawData']),
            data_collection_timeframe     : listToStr(root['rai:dataCollectionTimeframe'], '- '),
            data_imputation_protocol      : str(root['rai:dataImputationProtocol']),
            data_manipulation_protocol    : str(root['rai:dataManipulationProtocol']),
            data_preprocessing_protocol   : listToStr(root['rai:dataPreprocessingProtocol']),
            data_annotation_protocol      : str(root['rai:dataAnnotationProtocol']),
            data_annotation_platform      : listToStr(root['rai:dataAnnotationPlatform']),
            data_annotation_analysis      : listToStr(root['rai:dataAnnotationAnalysis']),
            personal_sensitive_information: listToStr(root['rai:personalSensitiveInformation']),
            data_social_impact            : str(root['rai:dataSocialImpact']),
            annotations_per_item          : str(root['rai:annotationsPerItem']),
            annotator_demographics        : listToStr(root['rai:annotatorDemographics']),
            machine_annotation_tools      : listToStr(root['rai:machineAnnotationTools']),
        ]
    }

    // ------------------------------------------------------------------
    // Distribution section (DISTRIBUTION_MAPPING)
    // ------------------------------------------------------------------

    private Map distributionContext() {
        return [
            license_value: str(root['license']),
            publisher    : str(root['publisher']),
            host         : '',
            doi          : str(root['doi']),
            release_date : str(root['datePublished']),
            version      : str(root['version']),
        ]
    }

    // ------------------------------------------------------------------
    // Composition section (SUBCRATE_MAPPING, single-crate path)
    // ------------------------------------------------------------------

    private Map subcrateContext() {
        final context = [
            name                     : root['name'] ?: 'Unnamed Sub-Crate',
            id                       : root['@id'],
            description              : str(root['description']),
            authors                  : listToStr(root['author']),
            keywords                 : root['keywords'] ?: [],
            metadata_path            : '',
            size                     : str(root['contentSize']) ?: directorySize(),
            doi                      : str(root['identifier']),
            date                     : str(root['datePublished']),
            contact                  : str(root['contactEmail']),
            published                : published,
            copyright                : str(root['copyrightNotice']),
            license                  : str(root['license']),
            terms_of_use             : str(root['conditionsOfAccess']),
            confidentiality          : str(root['confidentialityLevel']),
            funder                   : str(root['funder']),
            md5                      : str(root['MD5']),
            evidence                 : root['localEvidenceGraph'] instanceof Map
                                        ? str(root['localEvidenceGraph']['@id'])
                                        : '',
            related_publications     : relatedPublications(root['associatedPublication']),
            statistical_summary_info : null,
            preview_url              : '',

            files                    : [],
            software                 : [],
            instruments              : [],
            samples                  : [],
            experiments              : [],
            computations             : [],
            schemas                  : [],
            other                    : [],
        ]
        context.putAll(CompositionBuilder.build(graph, index))
        return context
    }

    /** get_directory_size + format_size (1024-based, two decimals). */
    private String directorySize() {
        long total = 0
        try {
            final stream = Files.walk(baseDir)
            try {
                for( final path : stream.iterator() ) {
                    if( Files.isRegularFile(path) && !Files.isSymbolicLink(path) )
                        total += Files.size(path)
                }
            }
            finally {
                stream.close()
            }
        }
        catch( Exception e ) {
            log.debug "Unable to measure crate directory size", e
            return 'Unknown'
        }

        double size = total
        for( final unit : ['B', 'KB', 'MB', 'GB', 'TB'] ) {
            if( size < 1024d )
                return "${CrateJson.fixed(size, 2)} ${unit}"
            size /= 1024d
        }
        return "${CrateJson.fixed(size, 2)} PB"
    }

    // ------------------------------------------------------------------
    // Summary section (SummarySectionGenerator)
    // ------------------------------------------------------------------

    private Map summaryContext() {
        String sizeStr = str(root['contentSize'])
        if( !sizeStr && root['evi:totalContentSizeBytes'] )
            sizeStr = CrateJson.formatSize((root['evi:totalContentSizeBytes'] as Number).longValue())

        List<String> formats = new ArrayList(new TreeSet(CrateJson.normalizeFormats(root['evi:formats'] ?: [])))

        int totalEntities = (root['evi:totalEntities'] ?: 0) as int
        int datasetCount = (root['evi:datasetCount'] ?: 0) as int
        int computationCount = (root['evi:computationCount'] ?: 0) as int
        int softwareCount = (root['evi:softwareCount'] ?: 0) as int

        if( totalEntities == 0 ) {
            final formatsSet = new TreeSet<String>()
            for( final item : graph ) {
                if( item['@id'] == 'ro-crate-metadata.json' )
                    continue
                final typeStr = CrateJson.typeAsString(item['@type'])
                if( typeStr.contains('ROCrate') || typeStr.contains('CreativeWork') )
                    continue

                totalEntities++
                if( typeStr.contains('Dataset') ) {
                    datasetCount++
                    // upstream reads 'fileFormat' from an alias-dumped entity, where the
                    // key is 'format' — so this never collects anything. Kept as-is so the
                    // rendered datasheet matches the CLI's.
                    formatsSet.addAll(CrateJson.normalizeFormats(item['fileFormat']))
                }
                else if( typeStr.contains('Software') || typeStr.contains('SoftwareSourceCode') )
                    softwareCount++
                else if( typeStr.contains('Computation') )
                    computationCount++
            }
            if( !formats && formatsSet )
                formats = new ArrayList(formatsSet)
        }

        String description = str(root['description'])
        boolean truncated = false
        if( description.length() > 500 ) {
            description = description.substring(0, 500)
            final lastSpace = description.lastIndexOf(' ')
            if( lastSpace >= 0 )
                description = description.substring(0, lastSpace)
            truncated = true
        }

        String formatsStr = formats.take(10).join(', ')
        if( formats.size() > 10 )
            formatsStr += " (+${formats.size() - 10} more)"

        final score = AiReadyScorer.score(graph, root)
        writeAiReadyScore(score)
        final scoreData = summarize(score)

        return [
            description             : description,
            description_truncated   : truncated,
            content_url             : str(root['contentUrl']),
            total_size              : sizeStr,
            total_entities          : totalEntities ? CrateJson.thousands(totalEntities) : 'N/A',
            formats                 : formatsStr,
            dataset_count           : datasetCount ? CrateJson.thousands(datasetCount) : '0',
            computation_count       : computationCount ? CrateJson.thousands(computationCount) : '0',
            software_count          : softwareCount ? CrateJson.thousands(softwareCount) : '0',
            aiready_categories      : scoreData.categories,
            aiready_total_percentage: scoreData.total_percentage,
            aiready_total_color     : scoreData.total_color,
            aiready_json_filename   : 'ai_ready_score.json',
        ]
    }

    private void writeAiReadyScore(Map score) {
        final path = baseDir.resolve('ai_ready_score.json')
        Files.write(path, PyJson.dumps(score).getBytes('UTF-8'))
    }

    /** Roll the sub-criteria up into the per-category bars and overall donut. */
    private static Map summarize(Map score) {
        final categories = []
        int totalEarned = 0
        int totalPossible = 0

        AiReadyScorer.CATEGORY_MAP.each { key, spec ->
            final label = spec[0] as String
            final subCriteria = spec[1] as List
            final categoryScore = score[key] as Map
            final earned = subCriteria.count { (categoryScore[it] as Map).has_content }
            final possible = subCriteria.size()
            final percentage = possible > 0 ? (earned / (double) possible) * 100 : 0d

            categories << [
                label     : label,
                earned    : earned,
                possible  : possible,
                percentage: round(percentage, 1),
                color     : color(percentage),
            ]
            totalEarned += earned
            totalPossible += possible
        }

        final totalPercentage = totalPossible > 0 ? (totalEarned / (double) totalPossible) * 100 : 0d
        return [
            categories      : categories,
            total_earned    : totalEarned,
            total_possible  : totalPossible,
            total_percentage: round(totalPercentage, 1),
            total_color     : color(totalPercentage),
        ]
    }

    private static double round(double value, int scale) {
        return new BigDecimal(value).setScale(scale, java.math.RoundingMode.HALF_EVEN).doubleValue()
    }

    private static String color(double percentage) {
        if( percentage >= 75 ) return '#4CAF50'
        if( percentage >= 50 ) return '#8BC34A'
        if( percentage >= 25 ) return '#FFC107'
        return '#f44336'
    }

    // ------------------------------------------------------------------
    // mapping parsers (conversion.mapping.FairscapeDatasheet)
    // ------------------------------------------------------------------

    private static String str(Object value) {
        return value == null ? '' : String.valueOf(value)
    }

    /** _list_to_str / _list_to_str_hyphen */
    private static String listToStr(Object value, String sep = ', ') {
        if( value instanceof List )
            return value.collect { String.valueOf(it).trim() }.findAll { it }.join(sep)
        if( value instanceof CharSequence )
            return value.toString().trim()
        return value == null ? '' : String.valueOf(value).trim()
    }

    /** _as_list_str: split a delimited string, or clean up a list of strings. */
    private static List<String> asListStr(Object value) {
        if( value == null )
            return []
        if( value instanceof CharSequence ) {
            final s = value.toString()
            final sep = s.contains(';') ? ';' : ','
            return s.split(java.util.regex.Pattern.quote(sep))*.trim().findAll { it }
        }
        if( value instanceof List )
            return value.findAll { it instanceof CharSequence }*.toString()*.trim().findAll { it }
        return []
    }

    /** _related_publications: names/ids, de-duplicated, order preserved. */
    private static List<String> relatedPublications(Object value) {
        final out = []
        for( final item : CrateJson.asList(value) ) {
            String s = item instanceof Map
                ? String.valueOf(item['name'] ?: item['@id'] ?: item['identifier'] ?: '')
                : String.valueOf(item ?: '')
            s = s.trim()
            if( s && !out.contains(s) )
                out << s
        }
        return out
    }

    /**
     * Mirror the converter's source_key -> parser -> additionalProperty fallback
     * chain. Returns null when neither the top-level field nor the fallback
     * exists, so callers can apply the section generator's own default.
     */
    private Object withAdditionalProperty(Object value, String propertyName, String defaultValue) {
        if( value != null )
            return value
        final props = root['additionalProperty']
        if( props == null )
            return null
        for( final prop : CrateJson.asList(props) ) {
            if( prop instanceof Map && prop['name'] == propertyName )
                return prop['value'] != null ? String.valueOf(prop['value']) : defaultValue
        }
        return defaultValue
    }

    private static Object boolToYesNo(Object value) {
        if( value == null )
            return null
        if( value instanceof Boolean )
            return value ? 'Yes' : 'No'
        return String.valueOf(value)
    }

    private static Object irbPassthrough(Object value) {
        return (value instanceof Map || value instanceof CharSequence) ? value : null
    }
}
