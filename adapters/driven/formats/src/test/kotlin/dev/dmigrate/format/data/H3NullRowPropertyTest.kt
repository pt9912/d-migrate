package dev.dmigrate.format.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.csv.CsvChunkWriter
import dev.dmigrate.format.data.json.JsonChunkWriter
import dev.dmigrate.format.data.yaml.YamlChunkWriter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * H3-Property-Test (§3.5.1): Jeder Writer MUSS bei einer Row mit
 * ausschließlich null-Werten alle in begin(...) übergebenen Spalten
 * als Schlüssel im Output materialisieren.
 *
 * Wenn diese Eigenschaft verletzt wird, kann der Reader bei einem
 * Round-Trip das autoritative Feldset der ersten Row nicht korrekt
 * ableiten, und die First-Row-Schema-Garantie bricht.
 *
 * Die Prüfung erfolgt strukturell (JSON: Regex-Key-Extraktion,
 * YAML: SnakeYAML Load-API, CSV: Feld-Splitting), nicht über
 * fragile Roh-String-Vergleiche.
 */
class H3NullRowPropertyTest : FunSpec({

    val table = "h3_test"

    val columns = listOf(
        ColumnDescriptor("alpha", nullable = true),
        ColumnDescriptor("beta", nullable = true),
        ColumnDescriptor("gamma", nullable = true),
        ColumnDescriptor("delta", nullable = true),
    )

    val expectedColumnNames = columns.map { it.name }

    val allNullRow = DataChunk(
        table, columns,
        listOf(arrayOf<Any?>(null, null, null, null)),
        chunkIndex = 0,
    )

    val allNullFirstThenData = DataChunk(
        table, columns,
        listOf(
            arrayOf<Any?>(null, null, null, null),
            arrayOf<Any?>(1, "x", true, 2.5),
        ),
        chunkIndex = 0,
    )

    // ─── Helper ─────────────────────────────────────────────────

    fun writeJson(chunk: DataChunk): String {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).use { w ->
            w.begin(table, columns)
            w.write(chunk)
            w.end()
        }
        return out.toString(Charsets.UTF_8)
    }

    fun writeYaml(chunk: DataChunk): String {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).use { w ->
            w.begin(table, columns)
            w.write(chunk)
            w.end()
        }
        return out.toString(Charsets.UTF_8)
    }

    fun writeCsv(chunk: DataChunk): String {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out).use { w ->
            w.begin(table, columns)
            w.write(chunk)
            w.end()
        }
        return out.toString(Charsets.UTF_8)
    }

    /** Extrahiert JSON-Schlüsselnamen aus einer Objekt-Zeile. */
    fun extractJsonKeys(jsonObjectLine: String): Set<String> =
        Regex(""""(\w+)"\s*:""").findAll(jsonObjectLine)
            .map { it.groupValues[1] }
            .toSet()

    /** Parst YAML-Output zu einer Liste von Maps via SnakeYAML Load-API. */
    fun parseYamlRows(yamlOutput: String): List<Map<*, *>> {
        val load = Load(LoadSettings.builder().build())
        val parsed = load.loadFromInputStream(
            ByteArrayInputStream(yamlOutput.toByteArray(Charsets.UTF_8)),
        ) as List<*>
        return parsed.map { it as Map<*, *> }
    }

    // ═══════════════════════════════════════════════════════════
    // H3 — JSON: all-null row materializes all column keys
    // ═══════════════════════════════════════════════════════════

    test("H3 JSON: all-null row contains all column keys") {
        val output = writeJson(allNullRow)
        val objectLines = output.lines().filter { it.trim().startsWith("{") }
        objectLines.size shouldBe 1
        extractJsonKeys(objectLines[0]) shouldContainExactlyInAnyOrder expectedColumnNames
    }

    test("H3 JSON: all-null first row + data row — each row has all keys") {
        val output = writeJson(allNullFirstThenData)
        val objectLines = output.lines().filter { it.trim().startsWith("{") }
        objectLines.size shouldBe 2
        for (line in objectLines) {
            extractJsonKeys(line) shouldContainExactlyInAnyOrder expectedColumnNames
        }
    }

    // ═══════════════════════════════════════════════════════════
    // H3 — YAML: all-null row materializes all column keys
    // ═══════════════════════════════════════════════════════════

    test("H3 YAML: all-null row contains all column keys") {
        val output = writeYaml(allNullRow)
        val rows = parseYamlRows(output)
        rows.size shouldBe 1
        rows[0].keys shouldContainExactlyInAnyOrder expectedColumnNames
    }

    test("H3 YAML: all-null first row + data row — each row has all keys") {
        val output = writeYaml(allNullFirstThenData)
        val rows = parseYamlRows(output)
        rows.size shouldBe 2
        for (row in rows) {
            row.keys shouldContainExactlyInAnyOrder expectedColumnNames
        }
    }

    // ═══════════════════════════════════════════════════════════
    // H3 — CSV: all-null row has correct number of delimited fields
    // ═══════════════════════════════════════════════════════════

    test("H3 CSV: header contains all column names") {
        val output = writeCsv(allNullRow)
        val headerLine = output.lines().first()
        headerLine.split(",") shouldContainExactly expectedColumnNames
    }

    test("H3 CSV: all-null row has same field count as header") {
        val output = writeCsv(allNullRow)
        val lines = output.lines().filter { it.isNotBlank() }
        lines.size shouldBe 2
        val headerFields = lines[0].split(",").size
        lines[1].split(",").size shouldBe headerFields
    }

    test("H3 CSV: all-null first row + data row — field count consistent") {
        val output = writeCsv(allNullFirstThenData)
        val lines = output.lines().filter { it.isNotBlank() }
        lines.size shouldBe 3
        val headerFields = lines[0].split(",").size
        for (dataLine in lines.drop(1)) {
            dataLine.split(",").size shouldBe headerFields
        }
    }
})
