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

package nextflow.prov.schema

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic

/**
 * Groovy port of `fairscape-cli schema infer` for csv/tsv files — i.e. of
 * {@code fairscape_models.schema.tabular.TabularSchema.infer}, which delegates
 * column-type detection to frictionless's {@code describe()}.
 *
 * Two layers are reproduced here:
 *
 *  1. frictionless's {@code Detector.detect_schema} — the "runner" algorithm that
 *     races a fixed list of candidate types against the first N data rows and
 *     keeps the first candidate to reach a 90% confidence score. Candidate order,
 *     the per-type cell readers, the missing-value handling and the `field{N}`
 *     naming/dedup rules all follow the frictionless 5.x implementation.
 *  2. fairscape's mapping of the detected frictionless type onto the six
 *     canonical JSON-Schema types, keeping the original under `source-type`
 *     whenever the mapping is lossy (date -> string, year -> integer, ...).
 *
 * The emitted document is the same JSON the CLI writes, minus `fairscapeVersion`
 * (which names a Python package version the plugin has no business asserting;
 * pydantic fills it in on read). The `@id` is a deterministic plugin-minted ARK
 * rather than the CLI's random uuid suffix, so crates stay reproducible across
 * `-resume`.
 *
 * Deliberate approximations, none reachable from ordinary scientific tables:
 *  - `geojson` cells are recognized structurally (a JSON object whose `type` is a
 *    GeoJSON type with its required member) rather than by running the full
 *    GeoJSON JSON-Schema profile.
 *  - `duration` accepts the ISO-8601 designator form, not isodate's alternate
 *    `P0003-06-04T12:30:05` calendar form.
 *
 * @author FAIRSCAPE
 */
@CompileDynamic
class TabularSchemaInferrer {

    /** frictionless settings.DEFAULT_SAMPLE_SIZE */
    static final int DEFAULT_SAMPLE_SIZE = 100

    /** frictionless settings.DEFAULT_FIELD_CONFIDENCE */
    static final double FIELD_CONFIDENCE = 0.9

    /** frictionless settings.DEFAULT_MISSING_VALUES */
    static final List<String> MISSING_VALUES = ['']

    /** frictionless settings.DEFAULT_FIELD_CANDIDATES, in priority order. */
    static final List<String> CANDIDATES = [
        'yearmonth', 'geopoint', 'duration', 'geojson', 'object', 'array',
        'datetime', 'time', 'date', 'integer', 'number', 'boolean', 'year', 'string'
    ].asImmutable() as List<String>

    /** frictionless settings.DEFAULT_TRUE_VALUES / DEFAULT_FALSE_VALUES */
    private static final Set<String> TRUE_VALUES = ['true', 'True', 'TRUE', '1'] as Set
    private static final Set<String> FALSE_VALUES = ['false', 'False', 'FALSE', '0'] as Set

    /** tabular.frictionless_type_to_json_schema */
    private static final Map<String,String> TYPE_MAP = [
        string   : 'string',
        integer  : 'integer',
        number   : 'number',
        boolean  : 'boolean',
        date     : 'string',
        datetime : 'string',
        year     : 'integer',
        yearmonth: 'string',
        duration : 'string',
        geopoint : 'array',
        geojson  : 'object',
        array    : 'array',
        object   : 'object',
        time     : 'string',
    ].asImmutable() as Map<String,String>

    private static final Set<String> GEOJSON_TYPES = [
        'Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon',
        'MultiPolygon', 'GeometryCollection', 'Feature', 'FeatureCollection'
    ] as Set

    static final Set<String> SUPPORTED_EXTENSIONS = ['csv', 'tsv'] as Set

    /** The comment introducer skipped by default; '' skips nothing, as the CLI does. */
    static final String DEFAULT_COMMENT_CHAR = '#'

    /** How deep a comment preamble may run before we stop looking for a header. */
    static final int MAX_COMMENT_LINES = 50

    /** True when this file's extension is one the inferrer can describe. */
    static boolean supports(Path file) {
        return extensionOf(file) in SUPPORTED_EXTENSIONS
    }

    private static String extensionOf(Path file) {
        final name = file.fileName.toString()
        final dot = name.lastIndexOf('.')
        return dot < 0 ? '' : name.substring(dot + 1).toLowerCase()
    }

    /**
     * Infer an EVI:Schema document for a csv/tsv file.
     *
     * @param file           the data file to describe
     * @param guid           the schema identifier (an ARK minted by the caller)
     * @param name           schema name
     * @param description    schema description
     * @param sampleSize     rows read for type detection (frictionless default 100)
     * @param arrayThreshold when > 0, a trailing run of at least this many
     *                       same-typed columns collapses into one spanning-array
     *                       property (`index: "N::"`), the shape the hand-written
     *                       CM4AI embedding schemas use. 0 disables the collapse.
     */
    static Map infer(Path file, String guid, String name, String description,
                     int sampleSize = DEFAULT_SAMPLE_SIZE, int arrayThreshold = 0,
                     String commentChar = DEFAULT_COMMENT_CHAR) {

        final ext = extensionOf(file)
        if( !(ext in SUPPORTED_EXTENSIONS) )
            throw new IllegalArgumentException("Unsupported file extension '${ext}' for schema inference")

        final separator = ext == 'tsv' ? '\t' : ','
        // read the comment allowance on top of the sample so a preamble does not
        // eat into the rows the type detection gets to see, then cut back to the
        // sample size frictionless would have used
        List<List<String>> records = dropComments(
            readRecords(file, separator as char, sampleSize + 1 + MAX_COMMENT_LINES), commentChar)
        if( records.size() > sampleSize + 1 )
            records = records.subList(0, sampleSize + 1)

        final labels = records ? records[0] : []
        final fragment = records.size() > 1 ? records[1..-1] : []

        final names = fieldNames(labels, fragment)
        final types = detectTypes(names, fragment)

        Map properties = [:]
        for( int i = 0; i < names.size(); i++ )
            properties[names[i]] = propertyOf(names[i], types[i], i)

        if( arrayThreshold > 0 )
            properties = collapseTrailingArray(properties, types, arrayThreshold)

        return [
            '@id'              : guid,
            '@type'            : 'EVI:Schema',
            'name'             : name,
            'isPartOf'         : [],
            '@context'         : ['@vocab': 'https://schema.org/', 'evi': 'https://w3id.org/EVI#'],
            'conformsTo'       : ['@id': 'https://json-schema.org/draft/2020-12/schema'],
            'properties'       : properties,
            'type'             : 'object',
            'additionalProperties': true,
            'required'         : new ArrayList(properties.keySet()),
            'separator'        : separator,
            'header'           : true,
            'examples'         : [],
            'EVI:schemaType'   : 'tabular',
            'description'      : description,
        ]
    }

    /**
     * Drop a leading comment preamble so the header is the real header. MultiQC
     * custom-content tables open with `# id: '...'` lines, and taking the first
     * of those as the header describes the file as one column named after a
     * comment — metadata that is worse than none.
     *
     * A leading record counts as a comment when its first field starts with the
     * comment character AND it does not split into the same number of fields as
     * the record after it: `#chrom<TAB>start<TAB>end` above tab-separated data is
     * a header written in the BED style, not a comment, and stays.
     *
     * @param records     the parsed records, header first
     * @param commentChar the comment introducer, or null/'' to drop nothing
     */
    static List<List<String>> dropComments(List<List<String>> records, String commentChar) {
        if( !commentChar || !records )
            return records

        int start = 0
        final limit = Math.min(records.size(), MAX_COMMENT_LINES)
        while( start < limit ) {
            final record = records[start]
            if( !record || !(record[0] as String)?.startsWith(commentChar) )
                break
            final next = start + 1 < records.size() ? records[start + 1] : null
            if( next != null && record.size() > 1 && record.size() == next.size() )
                break
            start++
        }
        return start == 0 ? records : records.subList(start, records.size())
    }

    /** One canonical Property, with the frictionless type kept when the map is lossy. */
    private static Map propertyOf(String name, String frictionlessType, int index) {
        final canonical = TYPE_MAP.get(frictionlessType) ?: 'string'
        final property = [
            'description': "Column ${name}".toString(),
            'index'      : index,
            'type'       : canonical,
        ]
        if( canonical != frictionlessType )
            property['source-type'] = frictionlessType
        return property
    }

    // ------------------------------------------------------------------
    // frictionless Detector.detect_schema
    // ------------------------------------------------------------------

    /**
     * Column names from the header labels: newlines flattened, whitespace
     * trimmed, blanks replaced by `field{N}` (1-based), duplicates suffixed with
     * their occurrence count. With no labels at all, every column is `field{N}`.
     */
    static List<String> fieldNames(List<String> labels, List<List<String>> fragment) {
        List<String> names = labels.collect { label -> (label ?: '').replace('\n', ' ').trim() }
        if( !names ) {
            if( !fragment )
                return []
            names = (1..fragment[0].size()).collect { number -> "field${number}".toString() }
        }
        for( int i = 0; i < names.size(); i++ )
            if( !names[i] )
                names[i] = "field${i + 1}".toString()

        if( names.size() != new HashSet(names).size() ) {
            final seen = []
            final deduped = new ArrayList<String>(names.size())
            for( final entry : names ) {
                final count = seen.count(entry) + 1
                deduped << (count > 1 ? "${entry}${count}".toString() : entry)
                seen << entry
            }
            names = deduped
        }
        return names
    }

    /**
     * The runner race. Each column keeps one scored runner per candidate type;
     * a runner scores +1 for every cell it can read and -1 for every cell it
     * cannot, and the first runner (in candidate order) whose score reaches 90%
     * of the column's non-missing cell count wins. Columns nothing wins for —
     * including all-empty columns, whose max score decays to zero — fall back to
     * frictionless's `any` field.
     */
    static List<String> detectTypes(List<String> names, List<List<String>> fragment) {
        final n = names.size()
        if( n == 0 )
            return []
        // Detector short-circuits to the default field type when there is no data
        // to inspect; DEFAULT_FIELD_TYPE is 'any'.
        if( !fragment )
            return (1..n).collect { 'any' }

        final runners = (0..<n).collect { CANDIDATES.collect { type -> [type: type, score: 0] } }
        final String[] fields = new String[n]
        final int[] maxScore = new int[n]
        Arrays.fill(maxScore, fragment.size())
        final double threshold = fragment.size() * (FIELD_CONFIDENCE - 1)

        for( final cells : fragment ) {
            for( int index = 0; index < n; index++ ) {
                if( fields[index] != null )
                    continue
                final source = index < cells.size() ? cells[index] : null
                final isMissing = source != null && MISSING_VALUES.contains(source)
                if( isMissing )
                    maxScore[index] -= 1
                for( final runner : runners[index] ) {
                    if( runner.score < threshold )
                        continue
                    if( !isMissing )
                        runner.score += readable(runner.type as String, source) ? 1 : -1
                    if( maxScore[index] > 0 && runner.score >= maxScore[index] * FIELD_CONFIDENCE ) {
                        fields[index] = runner.type
                        break
                    }
                }
            }
        }

        return (0..<n).collect { index -> fields[index] ?: 'any' }
    }

    /**
     * Whether a candidate type's cell reader accepts this cell, i.e. whether
     * frictionless's `read_cell` would come back without notes. A null cell (a
     * row shorter than the header) is never a missing value and reads clean for
     * every type — matching frictionless, where `str(None)` is not `""`.
     */
    static boolean readable(String type, String cell) {
        if( cell == null || MISSING_VALUES.contains(cell) )
            return true
        switch( type ) {
            case 'string'   : return true
            case 'integer'  : return isInteger(cell)
            case 'number'   : return isNumber(cell)
            case 'boolean'  : return TRUE_VALUES.contains(cell) || FALSE_VALUES.contains(cell)
            case 'year'     : return isYear(cell)
            case 'yearmonth': return isYearMonth(cell)
            case 'date'     : return isDate(cell)
            case 'datetime' : return isDateTime(cell)
            case 'time'     : return isTime(cell)
            case 'duration' : return isDuration(cell)
            case 'geopoint' : return isGeopoint(cell)
            case 'array'    : return jsonValue(cell) instanceof List
            case 'object'   : return jsonValue(cell) instanceof Map
            case 'geojson'  : return isGeoJson(cell)
            default         : return false
        }
    }

    // -- per-type cell readers (frictionless fields/*.py) --------------------

    /** Python `int(cell.strip())`: optional sign, digits, `_` group separators. */
    static boolean isInteger(String cell) {
        return cell.trim() ==~ /^[+-]?\d+(_\d+)*$/
    }

    /** Python `Decimal(cell.strip())`: decimals, exponents, and the inf/nan words. */
    static boolean isNumber(String cell) {
        final value = cell.trim()
        if( value ==~ /(?i)^[+-]?(inf|infinity|nan|snan)$/ )
            return true
        return value ==~ /^[+-]?(\d+(_\d+)*(\.(\d+(_\d+)*)?)?|\.\d+(_\d+)*)([eE][+-]?\d+)?$/
    }

    /** YearField: exactly four characters that parse as an int in 0..9999. */
    static boolean isYear(String cell) {
        if( cell.length() != 4 || !isInteger(cell) )
            return false
        final value = Integer.parseInt(cell.trim().replace('_', ''))
        return value >= 0 && value <= 9999
    }

    /** YearmonthField: `cell.split("-")` must yield exactly year and month 1..12. */
    static boolean isYearMonth(String cell) {
        final parts = cell.split('-', -1)
        if( parts.length != 2 || !isInteger(parts[0]) || !isInteger(parts[1]) )
            return false
        try {
            // frictionless wraps the whole read in `except Exception`; without
            // this a cell like `1-100000000000` (passes isInteger, overflows
            // Integer.parseInt) threw out of the type race
            final month = Integer.parseInt(parts[1].trim().replace('_', ''))
            return month >= 1 && month <= 12
        }
        catch( NumberFormatException e ) {
            return false
        }
    }

    /** DateField (format "default"): `strptime(cell, "%Y-%m-%d")`. */
    static boolean isDate(String cell) {
        final matcher = cell =~ /^(\d{1,4})-(\d{1,2})-(\d{1,2})$/
        if( !matcher.matches() )
            return false
        try {
            LocalDate.of(matcher[0][1] as int, matcher[0][2] as int, matcher[0][3] as int)
            return true
        }
        catch( Exception e ) {
            return false
        }
    }

    /** DatetimeField (format "default"): length >= 19, `cell[16] == ':'`, ISO parse. */
    static boolean isDateTime(String cell) {
        if( cell.length() < 19 || cell.charAt(16) != (':' as char) )
            return false
        return cell ==~ /^\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:?\d{2})?$/
    }

    /** TimeField (format "default"): length >= 8, `cell[5] == ':'`, ISO parse. */
    static boolean isTime(String cell) {
        if( cell.length() < 8 || cell.charAt(5) != (':' as char) )
            return false
        return cell ==~ /^\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:?\d{2})?$/
    }

    /** DurationField: the ISO-8601 designator form isodate.parse_duration accepts. */
    static boolean isDuration(String cell) {
        if( !(cell ==~ /^[+-]?P(?!$)(\d+(\.\d+)?Y)?(\d+(\.\d+)?M)?(\d+(\.\d+)?W)?(\d+(\.\d+)?D)?(T(?=\d)(\d+(\.\d+)?H)?(\d+(\.\d+)?M)?(\d+(\.\d+)?S)?)?$/) )
            return false
        // "PT" alone (a T with no time components) is not a valid duration
        return !cell.endsWith('T')
    }

    /** GeopointField (format "default"): "lon,lat", both decimals, both in range. */
    static boolean isGeopoint(String cell) {
        final parts = cell.split(',', -1)
        if( parts.length != 2 || !isNumber(parts[0]) || !isNumber(parts[1]) )
            return false
        try {
            final lon = new BigDecimal(parts[0].trim())
            final lat = new BigDecimal(parts[1].trim())
            return lon.abs() <= 180g && lat.abs() <= 90g
        }
        catch( Exception e ) {
            return false
        }
    }

    /** GeojsonField, approximated structurally (see the class comment). */
    static boolean isGeoJson(String cell) {
        final value = jsonValue(cell)
        if( !(value instanceof Map) )
            return false
        final type = ((Map) value).get('type')
        if( !(type instanceof CharSequence) || !GEOJSON_TYPES.contains(type.toString()) )
            return false
        switch( type.toString() ) {
            case 'GeometryCollection' : return ((Map) value).containsKey('geometries')
            case 'Feature'            : return ((Map) value).containsKey('geometry')
            case 'FeatureCollection'  : return ((Map) value).containsKey('features')
            default                   : return ((Map) value).containsKey('coordinates')
        }
    }

    private static Object jsonValue(String cell) {
        try {
            return new JsonSlurper().parseText(cell)
        }
        catch( Exception e ) {
            return null
        }
    }

    // ------------------------------------------------------------------
    // Wide-table collapse
    // ------------------------------------------------------------------

    /**
     * Collapse a trailing run of identically-typed columns into a single
     * spanning-array property, so a 1024-dimension embedding is described as one
     * `array` column rather than 1024 scalar ones. This is the shape the
     * hand-written CM4AI embedding schemas use and the one
     * `build_frictionless_schema` can expand again for validation (hence the
     * equal min/max item counts it requires).
     */
    static Map collapseTrailingArray(Map properties, List<String> types, int threshold) {
        if( types.size() < threshold )
            return properties

        final last = types[-1]
        int start = types.size()
        while( start > 0 && types[start - 1] == last )
            start--

        final span = types.size() - start
        if( span < threshold || start == 0 )
            return properties

        final names = new ArrayList<String>(properties.keySet())
        final kept = names.subList(0, start)
        final collapsed = names.subList(start, names.size())

        String arrayName = 'values'
        while( kept.contains(arrayName) )
            arrayName = '_' + arrayName

        final out = [:]
        kept.each { key -> out[key] = properties[key] }
        out[arrayName] = [
            'description' : "Columns ${collapsed[0]}..${collapsed[-1]} (${span} ${TYPE_MAP.get(last) ?: 'string'} values) collapsed into one spanning array".toString(),
            'index'       : "${start}::".toString(),
            'type'        : 'array',
            'items'       : ['type': TYPE_MAP.get(last) ?: 'string'],
            'min-items'   : span,
            'max-items'   : span,
            'unique-items': false,
        ]
        return out
    }

    // ------------------------------------------------------------------
    // Delimited reading
    // ------------------------------------------------------------------

    /**
     * Read up to {@code limit} records from a delimited file, honouring RFC-4180
     * quoting (doubled quotes inside a quoted field, delimiters and newlines
     * allowed inside quotes) exactly as Python's `csv` module does with its
     * default dialect.
     */
    static List<List<String>> readRecords(Path file, char delimiter, int limit) {
        final records = new ArrayList<List<String>>()
        final decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        Files.newInputStream(file).withCloseable { stream ->
            new PushbackReader(new BufferedReader(new InputStreamReader(stream, decoder))).withCloseable { reader ->
                final QUOTE = '"' as char
                final CR = '\r' as char
                final LF = '\n' as char
                List<String> record = []
                StringBuilder field = new StringBuilder()
                boolean inQuotes = false
                boolean quotedField = false
                boolean pendingRecord = false

                // strip a UTF-8 BOM (Excel exports one): frictionless reads such
                // files as utf-8-sig, so without this the first header label --
                // and the schema property named after it -- kept the BOM
                final first = reader.read()
                if( first != -1 && first != 0xFEFF )
                    reader.unread(first)

                int ch
                while( records.size() < limit && (ch = reader.read()) != -1 ) {
                    final char c = (char) ch
                    pendingRecord = true
                    if( inQuotes ) {
                        if( c == QUOTE ) {
                            final next = reader.read()
                            if( next == (int) QUOTE )
                                field.append(QUOTE)
                            else {
                                inQuotes = false
                                if( next != -1 )
                                    reader.unread(next)
                            }
                        }
                        else
                            field.append(c)
                        continue
                    }
                    if( c == QUOTE && field.length() == 0 && !quotedField ) {
                        inQuotes = true
                        quotedField = true
                    }
                    else if( c == delimiter ) {
                        record << field.toString()
                        field = new StringBuilder()
                        quotedField = false
                    }
                    else if( c == LF || c == CR ) {
                        if( c == CR ) {
                            final next = reader.read()
                            if( next != -1 && next != (int) LF )
                                reader.unread(next)
                        }
                        record << field.toString()
                        records << record
                        record = []
                        field = new StringBuilder()
                        quotedField = false
                        pendingRecord = false
                    }
                    else
                        field.append(c)
                }
                if( pendingRecord && records.size() < limit ) {
                    record << field.toString()
                    records << record
                }
            }
        }
        return records
    }
}
