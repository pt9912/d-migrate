package dev.dmigrate.format.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.format.data.csv.CsvChunkReader
import dev.dmigrate.format.data.csv.CsvChunkWriter
import dev.dmigrate.format.data.json.JsonChunkReader
import dev.dmigrate.format.data.json.JsonChunkWriter
import dev.dmigrate.format.data.yaml.YamlChunkReader
import dev.dmigrate.format.data.yaml.YamlChunkWriter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Golden-Master-Round-Trip-Tests: Writer → Reader → Vergleich.
 *
 * Plan §4 Phase B Schritt 11 / §3.5.1: Für jedes Format (JSON, YAML, CSV)
 * wird verifiziert, dass die Phase-B-Reader den Output der 0.3.0-Writer
 * korrekt zurücklesen können. Typbewusster Vergleich über
 * BigDecimal-Normalisierung (Int/Long/Double-Unterschiede zwischen
 * Original und Reader-Output sind erwartbar).
 */
class GoldenMasterRoundTripTest : FunSpec({

    val table = "round_trip"
    val chunkSize = 100

    val columns = listOf(
        ColumnDescriptor("id", nullable = false),
        ColumnDescriptor("name", nullable = true),
        ColumnDescriptor("active", nullable = true),
        ColumnDescriptor("score", nullable = true),
    )

    // ─── Helper ─────────────────────────────────────────────────

    fun writeJson(chunks: List<DataChunk>): ByteArray {
        val out = ByteArrayOutputStream()
        JsonChunkWriter(out).use { w ->
            w.begin(table, columns)
            chunks.forEach { w.write(it) }
            w.end()
        }
        return out.toByteArray()
    }

    fun writeYaml(chunks: List<DataChunk>): ByteArray {
        val out = ByteArrayOutputStream()
        YamlChunkWriter(out).use { w ->
            w.begin(table, columns)
            chunks.forEach { w.write(it) }
            w.end()
        }
        return out.toByteArray()
    }

    fun writeCsv(
        chunks: List<DataChunk>,
        options: ExportOptions = ExportOptions(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        CsvChunkWriter(out, options).use { w ->
            w.begin(table, columns)
            chunks.forEach { w.write(it) }
            w.end()
        }
        return out.toByteArray()
    }

    fun readAllChunks(reader: DataChunkReader): List<DataChunk> {
        val result = mutableListOf<DataChunk>()
        while (true) {
            val chunk = reader.nextChunk() ?: break
            result.add(chunk)
        }
        return result
    }

    fun assertValueEquivalent(expected: Any?, actual: Any?, context: String) {
        when {
            expected == null -> actual.shouldBeNull()
            expected is Number && actual is Number ->
                BigDecimal(expected.toString()) shouldBe BigDecimal(actual.toString())
            expected is Boolean -> actual shouldBe expected
            else -> actual.toString() shouldBe expected.toString()
        }
    }

    fun assertRowsEquivalent(
        expectedRows: List<Array<Any?>>,
        actualChunks: List<DataChunk>,
    ) {
        val actualRows = actualChunks.flatMap { it.rows }
        actualRows.size shouldBe expectedRows.size
        for (i in expectedRows.indices) {
            val exp = expectedRows[i]
            val act = actualRows[i]
            act.size shouldBe exp.size
            for (j in exp.indices) {
                assertValueEquivalent(exp[j], act[j], "row[$i]col[$j]")
            }
        }
    }

    fun assertCsvRowsEquivalent(
        expectedStringRows: List<Array<String?>>,
        actualChunks: List<DataChunk>,
    ) {
        val actualRows = actualChunks.flatMap { it.rows }
        actualRows.size shouldBe expectedStringRows.size
        for (i in expectedStringRows.indices) {
            val exp = expectedStringRows[i]
            val act = actualRows[i]
            act.size shouldBe exp.size
            for (j in exp.indices) {
                if (exp[j] == null) {
                    act[j].shouldBeNull()
                } else {
                    act[j].toString() shouldBe exp[j]
                }
            }
        }
    }

    // ─── Fixtures ───────────────────────────────────────────────

    val singleRow = listOf(arrayOf<Any?>(1, "alice", true, 9.5))
    val multiRow = listOf(
        arrayOf<Any?>(1, "alice", true, 9.5),
        arrayOf<Any?>(2, "bob", false, 7.0),
        arrayOf<Any?>(3, "charlie", true, 8.25),
    )
    val withNulls = listOf(arrayOf<Any?>(1, null, null, null))
    val allNullRow = listOf(arrayOf<Any?>(null, null, null, null))
    val allNullFirstThenData = listOf(
        arrayOf<Any?>(null, null, null, null),
        arrayOf<Any?>(1, "x", true, 2.5),
    )
    val precisionRow = listOf(
        arrayOf<Any?>(BigDecimal("12345.6789"), "x", true, BigInteger("9000000000000000000")),
    )

    fun chunk(rows: List<Array<Any?>>, index: Long = 0) =
        DataChunk(table, columns, rows, index)

    // ═══════════════════════════════════════════════════════════
    // JSON Round-Trip
    // ═══════════════════════════════════════════════════════════

    test("JSON round-trip: single row") {
        val bytes = writeJson(listOf(chunk(singleRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(singleRow, readAllChunks(reader))
        }
    }

    test("JSON round-trip: multiple rows") {
        val bytes = writeJson(listOf(chunk(multiRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(multiRow, readAllChunks(reader))
        }
    }

    test("JSON round-trip: null values") {
        val bytes = writeJson(listOf(chunk(withNulls)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(withNulls, readAllChunks(reader))
        }
    }

    test("JSON round-trip: all-null row (H3)") {
        val bytes = writeJson(listOf(chunk(allNullRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.flatMap { it.rows }.size shouldBe 1
            val row = chunks.first().rows.first()
            row.size shouldBe columns.size
            row.forEach { it.shouldBeNull() }
            chunks.first().columns.map { it.name } shouldBe columns.map { it.name }
        }
    }

    test("JSON round-trip: all-null first row + data row (H3 regression)") {
        val bytes = writeJson(listOf(chunk(allNullFirstThenData)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.first().columns.map { it.name } shouldBe columns.map { it.name }
            assertRowsEquivalent(allNullFirstThenData, chunks)
        }
    }

    test("JSON round-trip: empty table") {
        val bytes = writeJson(listOf(chunk(emptyList())))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.nextChunk().shouldBeNull()
        }
    }

    test("JSON round-trip: multi-chunk with chunkSize=2") {
        val rows5 = (1..5).map { arrayOf<Any?>(it, "name$it", it % 2 == 0, it * 1.1) }
        val bytes = writeJson(listOf(chunk(rows5)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, 2).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.map { it.rows.size } shouldBe listOf(2, 2, 1)
            chunks.forEach { c ->
                c.columns.map { it.name } shouldBe columns.map { it.name }
            }
            assertRowsEquivalent(rows5, chunks)
        }
    }

    test("JSON round-trip: headerColumns matches column names") {
        val bytes = writeJson(listOf(chunk(singleRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.nextChunk()
            reader.headerColumns() shouldBe columns.map { it.name }
        }
    }

    test("JSON round-trip: column order preserved") {
        val bytes = writeJson(listOf(chunk(singleRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val c = reader.nextChunk()!!
            c.columns.map { it.name } shouldBe listOf("id", "name", "active", "score")
        }
    }

    test("JSON round-trip: BigDecimal/BigInteger precision preserved") {
        val bytes = writeJson(listOf(chunk(precisionRow)))
        JsonChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            assertRowsEquivalent(precisionRow, chunks)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // YAML Round-Trip
    // ═══════════════════════════════════════════════════════════

    test("YAML round-trip: single row") {
        val bytes = writeYaml(listOf(chunk(singleRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(singleRow, readAllChunks(reader))
        }
    }

    test("YAML round-trip: multiple rows") {
        val bytes = writeYaml(listOf(chunk(multiRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(multiRow, readAllChunks(reader))
        }
    }

    test("YAML round-trip: null values") {
        val bytes = writeYaml(listOf(chunk(withNulls)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertRowsEquivalent(withNulls, readAllChunks(reader))
        }
    }

    test("YAML round-trip: all-null row (H3)") {
        val bytes = writeYaml(listOf(chunk(allNullRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.flatMap { it.rows }.size shouldBe 1
            val row = chunks.first().rows.first()
            row.size shouldBe columns.size
            row.forEach { it.shouldBeNull() }
            chunks.first().columns.map { it.name } shouldBe columns.map { it.name }
        }
    }

    test("YAML round-trip: all-null first row + data row (H3 regression)") {
        val bytes = writeYaml(listOf(chunk(allNullFirstThenData)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.first().columns.map { it.name } shouldBe columns.map { it.name }
            assertRowsEquivalent(allNullFirstThenData, chunks)
        }
    }

    test("YAML round-trip: empty table") {
        val bytes = writeYaml(listOf(chunk(emptyList())))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.nextChunk().shouldBeNull()
        }
    }

    test("YAML round-trip: multi-chunk with chunkSize=2") {
        val rows5 = (1..5).map { arrayOf<Any?>(it, "name$it", it % 2 == 0, it * 1.1) }
        val bytes = writeYaml(listOf(chunk(rows5)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, 2).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.map { it.rows.size } shouldBe listOf(2, 2, 1)
            chunks.forEach { c ->
                c.columns.map { it.name } shouldBe columns.map { it.name }
            }
            assertRowsEquivalent(rows5, chunks)
        }
    }

    test("YAML round-trip: headerColumns matches column names") {
        val bytes = writeYaml(listOf(chunk(singleRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.nextChunk()
            reader.headerColumns() shouldBe columns.map { it.name }
        }
    }

    test("YAML round-trip: column order preserved") {
        val bytes = writeYaml(listOf(chunk(singleRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val c = reader.nextChunk()!!
            c.columns.map { it.name } shouldBe listOf("id", "name", "active", "score")
        }
    }

    test("YAML round-trip: BigDecimal/BigInteger precision preserved") {
        val bytes = writeYaml(listOf(chunk(precisionRow)))
        YamlChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val chunks = readAllChunks(reader)
            assertRowsEquivalent(precisionRow, chunks)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CSV Round-Trip
    // ═══════════════════════════════════════════════════════════

    test("CSV round-trip: single row") {
        val bytes = writeCsv(listOf(chunk(singleRow)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertCsvRowsEquivalent(
                listOf(arrayOf("1", "alice", "true", "9.5")),
                readAllChunks(reader),
            )
        }
    }

    test("CSV round-trip: multiple rows") {
        val bytes = writeCsv(listOf(chunk(multiRow)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertCsvRowsEquivalent(
                listOf(
                    arrayOf("1", "alice", "true", "9.5"),
                    arrayOf("2", "bob", "false", "7.0"),
                    arrayOf("3", "charlie", "true", "8.25"),
                ),
                readAllChunks(reader),
            )
        }
    }

    test("CSV round-trip: null values with csvNullString") {
        val nullString = "NULL"
        val bytes = writeCsv(
            listOf(chunk(withNulls)),
            ExportOptions(csvNullString = nullString),
        )
        CsvChunkReader(
            ByteArrayInputStream(bytes), table, chunkSize,
            ImportOptions(csvNullString = nullString),
        ).use { reader ->
            assertCsvRowsEquivalent(
                listOf(arrayOf("1", null, null, null)),
                readAllChunks(reader),
            )
        }
    }

    test("CSV round-trip: all-null row (H3)") {
        val nullString = "NULL"
        val bytes = writeCsv(
            listOf(chunk(allNullRow)),
            ExportOptions(csvNullString = nullString),
        )
        CsvChunkReader(
            ByteArrayInputStream(bytes), table, chunkSize,
            ImportOptions(csvNullString = nullString),
        ).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.flatMap { it.rows }.size shouldBe 1
            val row = chunks.first().rows.first()
            row.size shouldBe columns.size
            row.forEach { it.shouldBeNull() }
        }
    }

    test("CSV round-trip: all-null first row + data row (H3 regression)") {
        val nullString = "NULL"
        val bytes = writeCsv(
            listOf(chunk(allNullFirstThenData)),
            ExportOptions(csvNullString = nullString),
        )
        CsvChunkReader(
            ByteArrayInputStream(bytes), table, chunkSize,
            ImportOptions(csvNullString = nullString),
        ).use { reader ->
            val chunks = readAllChunks(reader)
            val rows = chunks.flatMap { it.rows }
            rows.size shouldBe 2
            rows[0].forEach { it.shouldBeNull() }
            rows[1][0].toString() shouldBe "1"
            reader.headerColumns() shouldBe columns.map { it.name }
        }
    }

    test("CSV round-trip: empty table (header only)") {
        val bytes = writeCsv(listOf(chunk(emptyList())))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.headerColumns() shouldBe columns.map { it.name }
            reader.nextChunk().shouldBeNull()
        }
    }

    test("CSV round-trip: multi-chunk with chunkSize=2") {
        val rows5 = (1..5).map { arrayOf<Any?>(it, "name$it", it % 2 == 0, it * 1.1) }
        val bytes = writeCsv(listOf(chunk(rows5)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, 2).use { reader ->
            val chunks = readAllChunks(reader)
            chunks.map { it.rows.size } shouldBe listOf(2, 2, 1)
            chunks.forEach { c ->
                c.columns.map { it.name } shouldBe columns.map { it.name }
            }
            assertCsvRowsEquivalent(
                rows5.map { row -> row.map { it?.toString() }.toTypedArray() },
                chunks,
            )
        }
    }

    test("CSV round-trip: headerColumns matches column names") {
        val bytes = writeCsv(listOf(chunk(singleRow)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            reader.headerColumns() shouldBe columns.map { it.name }
        }
    }

    test("CSV round-trip: column order preserved") {
        val bytes = writeCsv(listOf(chunk(singleRow)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            val c = reader.nextChunk()!!
            c.columns.map { it.name } shouldBe listOf("id", "name", "active", "score")
        }
    }

    test("CSV round-trip: BigDecimal/BigInteger as string representation") {
        val bytes = writeCsv(listOf(chunk(precisionRow)))
        CsvChunkReader(ByteArrayInputStream(bytes), table, chunkSize).use { reader ->
            assertCsvRowsEquivalent(
                listOf(arrayOf("12345.6789", "x", "true", "9000000000000000000")),
                readAllChunks(reader),
            )
        }
    }
})
