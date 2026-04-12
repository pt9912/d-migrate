package dev.dmigrate.format.data.yaml

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.EncodingDetector
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.format.data.json.CharsetTranscodingInputStream
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.events.DocumentEndEvent
import org.snakeyaml.engine.v2.events.DocumentStartEvent
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.MappingEndEvent
import org.snakeyaml.engine.v2.events.MappingStartEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.events.SequenceEndEvent
import org.snakeyaml.engine.v2.events.SequenceStartEvent
import org.snakeyaml.engine.v2.events.StreamEndEvent
import org.snakeyaml.engine.v2.events.StreamStartEvent
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * YAML-Format-Reader mit SnakeYAML Engine Event-API.
 *
 * Plan §3.5.1 / §6.2:
 * - Akzeptiert nur eine Top-Level-Sequenz von Mappings
 * - Skalare am Top-Level, verschachtelte Dokumentformen → Formatfehler
 * - First-Row-Schema ist autoritativ (F8: lookup by name, R9: leere erste Row)
 * - Streaming: Event-basierte State-Machine, kein Tree-Aufbau
 *
 * **YAML-Mini-Design (§6.2)**:
 * 1. `StreamStart/DocumentStart/SequenceStart` initialisieren den Row-Kontext
 * 2. Jedes Top-Level-`MappingStart` beginnt eine neue Row
 * 3. Scalar-Key/Value-Paare werden in File-Order gesammelt
 * 4. Beim korrespondierenden `MappingEnd` wird genau eine Row emittiert
 * 5. Andere YAML-Formen → klarer Formatfehler
 *
 * **Typ-Auflösung (YAML Core Schema)**: Plain-Skalare werden nach dem
 * Core-Schema aufgelöst (null/~/leer → null, true/false → Boolean,
 * Ganzzahl → Long, Dezimal → Double). Quoted-Skalare sind immer String.
 */
class YamlChunkReader(
    rawInput: InputStream,
    private val table: String,
    private val chunkSize: Int,
    options: ImportOptions = ImportOptions(),
) : DataChunkReader {

    private val resolvedInput: InputStream
    private val events: Iterator<Event>
    private val headerNames: List<String>?
    private val fieldIndex: Map<String, Int>?
    private var pendingFirstRow: Array<Any?>? = null
    private var chunkIndex: Long = 0
    private var exhausted: Boolean
    private var sequenceEnded: Boolean = false
    private var closed: Boolean = false

    init {
        // 1. Encoding (§6.9)
        val (charset, stream) = resolveEncoding(rawInput, options.encoding)
        resolvedInput = stream

        // SnakeYAML Engine liest intern UTF-8 — für Nicht-UTF-8 transkodieren
        val utf8Stream = if (charset == StandardCharsets.UTF_8) {
            resolvedInput
        } else {
            CharsetTranscodingInputStream(resolvedInput, charset)
        }

        // 2. Event-basierter Parser
        events = try {
            val parse = Parse(
                LoadSettings.builder()
                    // Reader-Path muss 100k-Row-Inputs aus Phase B verarbeiten können;
                    // das SnakeYAML-Default-Limit von 3 MiB wäre dafür zu klein.
                    .setCodePointLimit(Int.MAX_VALUE)
                    .build()
            )
            parse.parseInputStream(utf8Stream).iterator()
        } catch (e: YamlEngineException) {
            throw invalidYaml("invalid YAML input", e)
        }

        // 3. Prolog: StreamStart → DocumentStart → SequenceStart
        val streamStart = nextEvent("reading YAML stream start")
        if (streamStart !is StreamStartEvent) {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML stream, got ${streamStart.eventId}",
            )
        }
        val docStart = nextEvent("reading YAML document start")
        if (docStart !is DocumentStartEvent) {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML document, got ${docStart.eventId}",
            )
        }
        val seqStart = nextEvent("reading YAML top-level sequence")
        if (seqStart !is SequenceStartEvent) {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML sequence of mappings, got ${seqStart.eventId}",
            )
        }

        // 4. Erstes Element lesen → Schema-Erkennung (F8, R9)
        val firstEvent = nextEvent("reading first YAML row")
        if (firstEvent is SequenceEndEvent) {
            // Leere Sequenz
            sequenceEnded = true
            validateTailAfterSequence()
            exhausted = true
            headerNames = null
            fieldIndex = null
        } else if (firstEvent is MappingStartEvent) {
            val firstMap = readMappingContent()
            val names = firstMap.keys.toList()
            headerNames = names
            fieldIndex = names.withIndex().associate { (i, n) -> n to i }
            pendingFirstRow = mapToRow(firstMap)
            exhausted = false
        } else {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML mapping in sequence, got ${firstEvent.eventId}",
            )
        }
    }

    // ── DataChunkReader ─────────────────────────────────────────────

    override fun nextChunk(): DataChunk? {
        check(!closed) { "YamlChunkReader is already closed" }
        if (exhausted) return null

        val names = headerNames ?: return null
        val index = fieldIndex ?: return null
        val columns = names.map { ColumnDescriptor(it, nullable = true) }

        val rows = mutableListOf<Array<Any?>>()

        // Pending first row aus dem Init
        pendingFirstRow?.let {
            rows.add(it)
            pendingFirstRow = null
        }

        // Weitere Rows aus dem Event-Stream sammeln
        while (rows.size < chunkSize && !sequenceEnded) {
            val event = nextEvent("reading next YAML row")
            if (event is SequenceEndEvent) {
                sequenceEnded = true
                validateTailAfterSequence()
                break
            }
            if (event !is MappingStartEvent) {
                throw ImportSchemaMismatchException(
                    "Table '$table': expected YAML mapping in sequence, " +
                        "got ${event.eventId}",
                )
            }
            val map = readMappingContent()
            rows.add(normalizeRow(map, names, index))
        }

        if (rows.isEmpty()) {
            exhausted = true
            return null
        }

        if (sequenceEnded) exhausted = true

        return DataChunk(table, columns, rows, chunkIndex++)
    }

    override fun headerColumns(): List<String>? = headerNames

    override fun close() {
        if (closed) return
        closed = true
        try {
            resolvedInput.close()
        } catch (_: Throwable) {
            // Idempotent cleanup
        }
    }

    // ── Interne Helfer ──────────────────────────────────────────────

    /** Liest Key/Value-Paare bis MappingEnd. */
    private fun readMappingContent(): LinkedHashMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            val event = nextEvent("reading YAML mapping entry")
            if (event is MappingEndEvent) break
            if (event !is ScalarEvent) {
                throw ImportSchemaMismatchException(
                    "Table '$table': expected scalar key in YAML mapping, " +
                        "got ${event.eventId}",
                )
            }
            val key = event.value
            val value = readValue()
            map[key] = value
        }
        return map
    }

    /** Liest einen beliebigen YAML-Wert (Skalar, Mapping, Sequenz). */
    private fun readValue(): Any? {
        val event = nextEvent("reading YAML value")
        return when (event) {
            is ScalarEvent -> resolveScalar(event)
            is MappingStartEvent -> readNestedMapping()
            is SequenceStartEvent -> readNestedSequence()
            is AliasEvent -> throw ImportSchemaMismatchException(
                "Table '$table': YAML aliases are not supported for data import",
            )
            else -> throw ImportSchemaMismatchException(
                "Table '$table': unexpected YAML event ${event.eventId} " +
                    "in value position",
            )
        }
    }

    private fun readNestedMapping(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            val event = nextEvent("reading nested YAML mapping")
            if (event is MappingEndEvent) break
            if (event !is ScalarEvent) {
                throw ImportSchemaMismatchException(
                    "Table '$table': expected scalar key in YAML mapping, " +
                        "got ${event.eventId}",
                )
            }
            map[event.value] = readValue()
        }
        return map
    }

    private fun readNestedSequence(): List<Any?> {
        val list = mutableListOf<Any?>()
        while (true) {
            val event = nextEvent("reading nested YAML sequence")
            if (event is SequenceEndEvent) break
            list.add(
                when (event) {
                    is ScalarEvent -> resolveScalar(event)
                    is MappingStartEvent -> readNestedMapping()
                    is SequenceStartEvent -> readNestedSequence()
                    is AliasEvent -> throw ImportSchemaMismatchException(
                        "Table '$table': YAML aliases are not supported",
                    )
                    else -> throw ImportSchemaMismatchException(
                        "Table '$table': unexpected event ${event.eventId}",
                    )
                },
            )
        }
        return list
    }

    /**
     * YAML Core Schema Typ-Auflösung für Skalare.
     *
     * - **Plain** (unquoted): null/~/leer → null, true/false → Boolean,
     *   Ganzzahl → Long, .inf/.nan/Dezimal → Double, sonst String
     * - **Quoted** (single/double/literal/folded): immer String
     */
    private fun resolveScalar(event: ScalarEvent): Any? {
        val value = event.value
        // Quoted → immer String
        if (event.scalarStyle != ScalarStyle.PLAIN) return value
        // Plain → YAML Core Schema
        if (value == "null" || value == "~" || value.isEmpty()) return null
        if (value == "true") return true
        if (value == "false") return false
        value.toLongOrNull()?.let { return it }
        if (value == ".inf" || value == "+.inf") return Double.POSITIVE_INFINITY
        if (value == "-.inf") return Double.NEGATIVE_INFINITY
        if (value == ".nan") return Double.NaN
        value.toDoubleOrNull()?.let { return it }
        return value
    }

    /** Erste Row → Array (definiert das Schema). */
    private fun mapToRow(map: Map<String, Any?>): Array<Any?> {
        val row = arrayOfNulls<Any?>(headerNames!!.size)
        for ((key, value) in map) {
            val slot = fieldIndex!![key] ?: continue
            row[slot] = value
        }
        return row
    }

    /**
     * F8: Normalisiert eine Folge-Row gegen das First-Row-Schema.
     * - Bekannte Keys → richtige Slot-Position
     * - Fehlende Keys → null
     * - Zusätzliche Keys → [ImportSchemaMismatchException]
     */
    private fun normalizeRow(
        map: Map<String, Any?>,
        names: List<String>,
        index: Map<String, Int>,
    ): Array<Any?> {
        val row = arrayOfNulls<Any?>(names.size)
        for ((key, value) in map) {
            val slot = index[key]
            if (slot == null) {
                throw ImportSchemaMismatchException(
                    "Table '$table': YAML mapping contains key '$key' " +
                        "which is not present in the first row's schema $names",
                )
            }
            row[slot] = value
        }
        return row
    }

    /**
     * Nach dem Ende der Records-Sequenz MUSS nur noch das reguläre
     * DocumentEnd/StreamEnd folgen. Zusätzliche Dokumente oder anderes
     * Trailing-Content sind klare Formatfehler.
     */
    private fun validateTailAfterSequence() {
        val documentEnd = nextEvent("reading YAML document end after records sequence")
        if (documentEnd !is DocumentEndEvent) {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML document end after records sequence, " +
                    "got ${documentEnd.eventId}",
            )
        }
        val streamEnd = nextEvent("reading YAML stream end after records sequence")
        if (streamEnd !is StreamEndEvent) {
            throw ImportSchemaMismatchException(
                "Table '$table': expected YAML stream end after records sequence, " +
                    "got ${streamEnd.eventId}",
            )
        }
        if (hasMoreEvents()) {
            val trailing = nextEvent("reading trailing YAML content")
            throw ImportSchemaMismatchException(
                "Table '$table': unexpected trailing YAML content after records sequence: " +
                    trailing.eventId,
            )
        }
    }

    private fun nextEvent(context: String): Event = try {
        events.next()
    } catch (e: ImportSchemaMismatchException) {
        throw e
    } catch (e: NoSuchElementException) {
        throw invalidYaml("unexpected end of YAML input while $context", e)
    } catch (e: YamlEngineException) {
        throw invalidYaml("invalid YAML input while $context", e)
    }

    private fun hasMoreEvents(): Boolean = try {
        events.hasNext()
    } catch (e: YamlEngineException) {
        throw invalidYaml("invalid YAML input while checking for trailing content", e)
    }

    private fun invalidYaml(message: String, cause: Throwable): ImportSchemaMismatchException =
        ImportSchemaMismatchException("Table '$table': $message", cause)

    companion object {
        private fun resolveEncoding(
            raw: InputStream,
            explicit: Charset?,
        ): EncodingDetector.Detected {
            return if (explicit == null) {
                EncodingDetector.detectOrFallback(raw)
            } else {
                EncodingDetector.Detected(explicit, EncodingDetector.wrapWithExplicit(raw, explicit))
            }
        }
    }
}
