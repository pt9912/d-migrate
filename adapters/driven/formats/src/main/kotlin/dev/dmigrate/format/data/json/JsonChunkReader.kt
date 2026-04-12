package dev.dmigrate.format.data.json

import com.dslplatform.json.DslJson
import com.dslplatform.json.ParsingException
import com.dslplatform.json.runtime.Settings
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.EncodingDetector
import dev.dmigrate.driver.data.ImportOptions
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * JSON-Format-Reader mit DSL-JSON Pull-Iterator.
 *
 * Plan §3.5.1 / §6.2 / §6.4:
 * - Akzeptiert nur ein Top-Level-Array von Objekten (`[{...}, ...]`)
 * - Nacktes Objekt, Wrapper (`{ "rows": [...] }`), NDJSON → Formatfehler
 * - First-Row-Schema ist autoritativ (F8: lookup by name, R9: leere erste Row)
 * - Streaming: konstanter Speicher unabhängig von der Dateigröße
 *
 * **Encoding**: DSL-JSON arbeitet nativ mit UTF-8-Bytes. Für Nicht-UTF-8-
 * Inputs (z.B. UTF-16 mit BOM) wird der Stream intern über
 * [CharsetTranscodingInputStream] in UTF-8 re-encodet, bevor er an
 * DSL-JSONs Iterator geht. Für UTF-8 (Regelfall) entfällt der Overhead.
 */
class JsonChunkReader(
    rawInput: InputStream,
    private val table: String,
    private val chunkSize: Int,
    options: ImportOptions = ImportOptions(),
) : DataChunkReader {

    // ── Encoding ────────────────────────────────────────────────────

    private val resolvedInput: InputStream
    private val headerNames: List<String>?
    private val fieldIndex: Map<String, Int>?

    // ── DSL-JSON Iterator ───────────────────────────────────────────

    private val iterator: Iterator<Any>?
    private var pendingFirstRow: Array<Any?>? = null
    private var chunkIndex: Long = 0
    private var exhausted: Boolean
    private var closed: Boolean = false

    init {
        // 1. Encoding auflösen (§6.9)
        val (charset, stream) = resolveEncoding(rawInput, options.encoding)
        resolvedInput = stream

        // 2. UTF-8-Stream an DSL-JSON übergeben
        val utf8Stream = if (charset == StandardCharsets.UTF_8) {
            resolvedInput
        } else {
            CharsetTranscodingInputStream(resolvedInput, charset)
        }

        val dslJson = DslJson<Any>(Settings.withRuntime<Any>().includeServiceLoader())
        val iter = try {
            dslJson.iterateOver(Any::class.java, utf8Stream, ByteArray(64 * 1024))
        } catch (e: ParsingException) {
            throw ImportSchemaMismatchException(
                "Table '$table': invalid JSON input — expected top-level array",
                e,
            )
        }

        if (iter == null || !iter.hasNext()) {
            // null-Iterator → leere Eingabe oder kein Top-Level-Array
            // Leeres Array [] → iter!=null aber !hasNext
            iterator = iter
            exhausted = true
            headerNames = null
            fieldIndex = null
        } else {
            iterator = iter

            // Erste Row lesen → Schema-Erkennung (F8, R9)
            val first = iter.next()
            if (first !is Map<*, *>) {
                throw ImportSchemaMismatchException(
                    "Table '$table': expected JSON object as array element, " +
                        "got ${first?.javaClass?.simpleName ?: "null"}"
                )
            }

            @Suppress("UNCHECKED_CAST")
            val firstMap = first as Map<String, Any?>
            val names = firstMap.keys.toList()
            headerNames = names
            fieldIndex = names.withIndex().associate { (i, name) -> name to i }
            pendingFirstRow = mapToRow(firstMap, names, fieldIndex!!)
            exhausted = false
        }
    }

    // ── DataChunkReader ─────────────────────────────────────────────

    override fun nextChunk(): DataChunk? {
        check(!closed) { "JsonChunkReader is already closed" }
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

        // Weitere Rows aus dem Iterator sammeln
        while (rows.size < chunkSize && iterator != null && iterator.hasNext()) {
            val element = iterator.next()
            if (element !is Map<*, *>) {
                throw ImportSchemaMismatchException(
                    "Table '$table': expected JSON object as array element, " +
                        "got ${element?.javaClass?.simpleName ?: "null"}"
                )
            }
            @Suppress("UNCHECKED_CAST")
            val map = element as Map<String, Any?>
            rows.add(normalizeRow(map, names, index))
        }

        if (rows.isEmpty()) {
            exhausted = true
            return null
        }

        // Prüfe ob der Iterator erschöpft ist (nach dem Sammeln)
        if (iterator == null || !iterator.hasNext()) {
            exhausted = true
        }

        return DataChunk(table, columns, rows, chunkIndex++)
    }

    override fun headerColumns(): List<String>? = headerNames

    override fun close() {
        if (closed) return
        closed = true
        try {
            resolvedInput.close()
        } catch (_: Throwable) {
            // Idempotent cleanup — Fehler beim Schließen ignorieren
        }
    }

    // ── Interne Helfer ──────────────────────────────────────────────

    /**
     * Konvertiert die erste Row (Map → Array) ohne Schema-Normalisierung,
     * weil die erste Row das Schema definiert.
     */
    private fun mapToRow(
        map: Map<String, Any?>,
        names: List<String>,
        index: Map<String, Int>,
    ): Array<Any?> {
        val row = arrayOfNulls<Any?>(names.size)
        for ((key, value) in map) {
            val slot = index[key] ?: continue
            row[slot] = normalizeValue(value)
        }
        return row
    }

    /**
     * F8: Normalisiert eine Folge-Row gegen das First-Row-Schema.
     * - Bekannte Keys → in die richtige Slot-Position
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
                    "Table '$table': JSON object contains key '$key' " +
                        "which is not present in the first row's schema $names"
                )
            }
            row[slot] = normalizeValue(value)
        }
        return row
    }

    /**
     * Normalisiert DSL-JSON-Werte auf die vom Plan §3.5.2 erwarteten
     * Java-Typen: Integer → Long (DSL-JSON liefert für kleine Zahlen
     * `Integer` statt `Long`; der ValueDeserializer und die Tests
     * erwarten einheitlich `Long`).
     */
    private fun normalizeValue(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Float -> value.toDouble()
        else -> value
    }

    companion object {
        /**
         * Encoding auflösen: auto-detect (BOM) oder explizit.
         */
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
