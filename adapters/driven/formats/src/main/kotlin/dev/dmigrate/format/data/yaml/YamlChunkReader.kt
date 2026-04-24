package dev.dmigrate.format.data.yaml

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.FormatReadOptions
import org.snakeyaml.engine.v2.events.MappingStartEvent
import org.snakeyaml.engine.v2.events.SequenceEndEvent

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
    rawInput: java.io.InputStream,
    private val table: String,
    private val chunkSize: Int,
    options: FormatReadOptions = FormatReadOptions(),
) : DataChunkReader {

    private val eventCursor = YamlEventCursor(rawInput, table, options.encoding)
    private val schemaBinding: YamlSchemaBinding?
    private var pendingFirstRow: Array<Any?>? = null
    private var chunkIndex: Long = 0
    private var exhausted: Boolean
    private var sequenceEnded: Boolean = false
    private var closed: Boolean = false

    init {
        eventCursor.expectTopLevelSequence()

        when (val firstEvent = eventCursor.nextEvent("reading first YAML row")) {
            is SequenceEndEvent -> {
                sequenceEnded = true
                eventCursor.validateTailAfterSequence()
                exhausted = true
                schemaBinding = null
            }

            is MappingStartEvent -> {
                val firstMap = eventCursor.readMappingContent()
                schemaBinding = YamlSchemaBinding(firstMap.keys.toList())
                pendingFirstRow = schemaBinding.toFirstRow(firstMap)
                exhausted = false
            }

            else -> throw ImportSchemaMismatchException(
                "Table '$table': expected YAML mapping in sequence, got ${firstEvent.eventId}",
            )
        }
    }

    override fun nextChunk(): DataChunk? {
        check(!closed) { "YamlChunkReader is already closed" }
        if (exhausted) return null

        val binding = schemaBinding ?: return null
        val rows = mutableListOf<Array<Any?>>()

        pendingFirstRow?.let {
            rows += it
            pendingFirstRow = null
        }

        while (rows.size < chunkSize && !sequenceEnded) {
            when (val event = eventCursor.nextEvent("reading next YAML row")) {
                is SequenceEndEvent -> {
                    sequenceEnded = true
                    eventCursor.validateTailAfterSequence()
                }

                is MappingStartEvent -> {
                    val row = binding.normalizeRow(table, eventCursor.readMappingContent())
                    rows += row
                }

                else -> throw ImportSchemaMismatchException(
                    "Table '$table': expected YAML mapping in sequence, got ${event.eventId}",
                )
            }
        }

        if (rows.isEmpty()) {
            exhausted = true
            return null
        }
        if (sequenceEnded) exhausted = true

        return DataChunk(table, binding.columns, rows, chunkIndex++)
    }

    override fun headerColumns(): List<String>? = schemaBinding?.headerNames

    override fun close() {
        if (closed) return
        closed = true
        eventCursor.close()
    }
}
