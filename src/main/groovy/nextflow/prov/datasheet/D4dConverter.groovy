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

/**
 * Port of `fairscape build linkml` — i.e. of
 * `fairscape_cli.datasheet_builder.linkml.convert_rocrate.GenerateLinkML`,
 * which applies `fairscape_models.conversion.mapping.d4d.ROCRATE_TO_D4D_MAPPING`
 * to the crate's root entity and dumps the result as `ro-crate-linkml.yaml`.
 *
 * Despite the file name this is the D4D (Datasheets for Datasets) translation:
 * the LinkML artifact *is* the D4D document, a flat re-expression of the root
 * entity in D4D's vocabulary. Only the root is read — D4D describes a dataset,
 * not a provenance graph.
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class D4dConverter {

    static final String OUTPUT_NAME = 'ro-crate-linkml.yaml'

    /**
     * ROCRATE_TO_D4D_MAPPING, in declaration order. Each entry is
     * `[d4dKey, sourceKey, parser]`; a null sourceKey means the value is fixed
     * and `parser` holds it.
     */
    private static final List<List> MAPPING = [
        // named thing
        ['id'                        , '@id'                            , null],
        ['name'                      , 'name'                           , null],
        ['title'                     , 'name'                           , null],
        ['description'               , 'description'                    , null],
        // information
        ['compression'               , 'evi:formats'                    , null],
        ['conforms_to'               , null                             , 'D4D Schema'],
        ['created_by'                , 'author'                         , null],
        ['created_on'                , 'dateCreated'                    , 'datetime'],
        ['doi'                       , 'identifier'                     , null],
        ['download_url'              , 'contentUrl'                     , null],
        ['keywords'                  , 'keywords'                       , null],
        ['language'                  , 'language'                       , null],
        ['last_updated_on'           , 'dateModified'                   , 'datetime'],
        ['license'                   , 'license'                        , null],
        ['page'                      , 'url'                            , null],
        ['publisher'                 , 'publisher'                      , null],
        ['version'                   , 'version'                        , null],
        ['was_derived_from'          , 'generatedBy'                    , null],
        // dataset
        ['bytes'                     , 'contentSize'                    , 'bytes'],
        ['encoding'                  , 'evi:formats'                    , null],
        ['format'                    , 'evi:formats'                    , null],
        ['hash'                      , 'MD5'                            , null],
        ['md5'                       , 'MD5'                            , null],
        ['sha256'                    , 'sha256'                         , null],
        ['purposes'                  , 'rai:dataUseCases'               , null],
        ['tasks'                     , 'rai:dataUseCases'               , null],
        ['creators'                  , 'author'                         , null],
        ['funders'                   , 'funders'                        , null],
        ['known_biases'              , 'rai:dataBiases'                 , null],
        ['known_limitations'         , 'rai:dataLimitations'            , null],
        ['sensitive_elements'        , 'rai:personalSensitiveInformation', null],
        ['aquisition_methods'        , 'rai:dataCollection'             , null],
        ['collection_mechanisms'     , 'rai:dataCollection'             , null],
        ['collection_timeframes'     , 'rai:dataCollectionTimeframe'    , null],
        ['missing_data_documentation', 'rai:dataCollectionMissingData'  , null],
        ['raw_data_sources'          , 'rai:dataCollectionRawData'      , null],
        ['ethical_reviews'           , 'ethicalReview'                  , null],
        ['human_subject_research'    , 'humanSubject'                   , null],
        ['preprocessing_strategies'  , 'rai:dataPreprocessingProtocol'  , null],
        ['labeling_strategies'       , 'rai:dataAnnotationProtocol'     , null],
        ['raw_sources'               , 'rai:dataCollectionRawData'      , null],
        ['imputation_protocols'      , 'rai:dataImputationProtocol'     , null],
        ['annotation_analyses'       , 'rai:dataAnnotationProtocol'     , null],
        ['machine_annotation_tools'  , 'rai:machineAnnotationTools'     , null],
        ['future_use_impacts'        , 'rai:dataSocialImpact'           , null],
        ['discouraged_uses'          , 'prohibitedUses'                 , null],
        ['intended_uses'             , 'rai:dataUseCases'               , null],
        ['prohibited_uses'           , 'prohibitedUses'                 , null],
        ['distribution_formats'      , 'evi:formats'                    , null],
        ['license_and_use_terms'     , 'license'                        , null],
        ['citation'                  , 'citation'                       , null],
    ].asImmutable() as List<List>

    /** convert_to_d4d_structure: fields copied straight across, in this order. */
    private static final List<String> DIRECT_FIELDS = [
        'id', 'name', 'title', 'description', 'page', 'language', 'version',
        'license', 'doi', 'download_url', 'publisher', 'citation',
        'bytes', 'encoding', 'format', 'hash', 'md5', 'sha256',
        'compression', 'conforms_to', 'created_by', 'created_on',
        'last_updated_on', 'was_derived_from',
    ].asImmutable() as List<String>

    /** convert_to_d4d_structure: fields reshaped into `[{description: ...}]`. */
    private static final List<String> LIST_DESCRIPTION_FIELDS = [
        'purposes', 'tasks', 'known_biases', 'known_limitations',
        'sensitive_elements', 'collection_mechanisms', 'collection_timeframes',
        'missing_data_documentation', 'raw_data_sources', 'ethical_reviews',
        'human_subject_research', 'preprocessing_strategies',
        'labeling_strategies', 'raw_sources', 'imputation_protocols',
        'annotation_analyses', 'machine_annotation_tools', 'future_use_impacts',
        'discouraged_uses', 'intended_uses', 'prohibited_uses',
        'distribution_formats', 'creators', 'funders',
    ].asImmutable() as List<String>

    /** Build the D4D document for a crate's root entity. */
    static Map convert(Map root) {
        return structure(applyMapping(root))
    }

    /** Write `ro-crate-linkml.yaml` next to the crate. */
    static Path write(Map root, Path crateDir) {
        final output = crateDir.resolve(OUTPUT_NAME)
        Files.write(output, PyYaml.dump(convert(root)).getBytes('UTF-8'))
        return output
    }

    // ------------------------------------------------------------------

    /** apply_mapping: pull each source key across, dropping nulls. */
    private static Map applyMapping(Map root) {
        final out = [:]
        for( final entry : MAPPING ) {
            final key = entry[0] as String
            final sourceKey = entry[1] as String
            final parser = entry[2]

            Object value
            if( sourceKey == null ) {
                value = parser
            }
            else {
                value = root?.get(sourceKey)
                if( value != null && parser != null )
                    value = parse(parser as String, value)
            }
            if( value != null )
                out[key] = value
        }
        return out
    }

    private static Object parse(String parser, Object value) {
        switch( parser ) {
            case 'datetime': return parseIsoToDateTime(value)
            case 'bytes'   : return parseSizeToBytes(value)
            default        : return value
        }
    }

    /** convert_to_d4d_structure. */
    private static Map structure(Map flat) {
        final out = [:]

        for( final field : DIRECT_FIELDS )
            if( flat.containsKey(field) && flat[field] != null )
                out[field] = flat[field]

        if( flat.containsKey('keywords') ) {
            final keywords = flat['keywords']
            if( keywords instanceof CharSequence )
                out['keywords'] = keywords.toString().split(',')
                    .collect { it.trim() }.findAll { it }
            else if( keywords instanceof List )
                out['keywords'] = keywords
        }

        for( final field : LIST_DESCRIPTION_FIELDS ) {
            if( !flat.containsKey(field) || flat[field] == null )
                continue
            final value = flat[field]
            if( value instanceof List )
                out[field] = ((List) value).collect { item ->
                    item instanceof CharSequence ? ['description': item.toString()] : item
                }
            else if( value instanceof CharSequence )
                out[field] = [['description': value.toString()]]
            else
                out[field] = value
        }

        return out
    }

    /**
     * _parse_iso_to_datetime: the CLI tries three `strptime` formats against the
     * value truncated at the first '.', and yields nothing when none match.
     */
    static Object parseIsoToDateTime(Object value) {
        if( !(value instanceof CharSequence) )
            return null
        final text = value.toString().split('\\.', 2)[0]
        // "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d", "%m/%d/%Y" -- kept as a Date so the
        // YAML emitter can write a timestamp scalar the way PyYAML does
        final patterns = ['yyyy-MM-dd\'T\'HH:mm:ss', 'yyyy-MM-dd', 'MM/dd/yyyy']
        for( final pattern : patterns ) {
            try {
                return Date.parse(pattern, text)
            }
            catch( Exception e ) {
                // try the next format, like the CLI's ValueError loop
            }
        }
        return null
    }

    /**
     * _parse_size_to_bytes. Note the CLI reads these suffixes as POWERS OF 1024
     * while it *writes* sizes as powers of 1000, so a human-readable
     * `contentSize` does not round-trip exactly. Reproduced rather than fixed:
     * the point of the port is to agree with the CLI.
     */
    static Object parseSizeToBytes(Object value) {
        if( value instanceof Number )
            return ((Number) value).longValue()
        if( !(value instanceof CharSequence) )
            return null

        final text = value.toString().trim().toLowerCase()
        if( text && text.every { Character.isDigit(it as char) } )
            return Long.parseLong(text)

        // insertion order matters: 'b' must not shadow 'kb', so the CLI's dict
        // order (b, byte, bytes, kb, ..., tb) is preserved and the first suffix
        // that both matches and parses wins
        final units = [
            'b': 1L, 'byte': 1L, 'bytes': 1L,
            'kb': 1024L, 'kilobyte': 1024L, 'kilobytes': 1024L,
            'mb': 1024L**2, 'megabyte': 1024L**2, 'megabytes': 1024L**2,
            'gb': 1024L**3, 'gigabyte': 1024L**3, 'gigabytes': 1024L**3,
            'tb': 1024L**4, 'terabyte': 1024L**4, 'terabytes': 1024L**4,
        ]
        for( final unit : units.entrySet() ) {
            if( !text.endsWith(unit.key) )
                continue
            try {
                final number = Double.parseDouble(text.substring(0, text.length() - unit.key.length()).trim())
                return (long) (number * (unit.value as long))
            }
            catch( NumberFormatException e ) {
                // keep scanning, like the CLI's `continue`
            }
        }
        return null
    }
}
