package dev.dmigrate.format.data.csv

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.format.data.FormatReadOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream

/**
 * Unit-Tests für [CsvChunkReader].
 *
 * Plan §4 Phase B Schritt 9: CsvChunkReader mit uniVocity CsvParser.
 * CSV-Werte sind immer String (oder null via csvNullString) — keine
 * Typ-Auflösung im Reader. Die Struktur spiegelt die Json/Yaml-Reader-Tests.
 */
class CsvChunkReaderTest : FunSpec({

    fun reader(csv: String, chunkSize: Int = 100, options: FormatReadOptions = FormatReadOptions()) =
        CsvChunkReader(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)), "t", chunkSize, options)

    // ─── Happy Path ─────────────────────────────────────────────────

    test("two rows with header in single chunk") {
        reader("id,name\n1,alice\n2,bob").use { r ->
            val chunk = r.nextChunk()!!
            chunk.table shouldBe "t"
            chunk.columns.map { it.name } shouldBe listOf("id", "name")
            chunk.rows shouldHaveSize 2
            chunk.rows[0][0] shouldBe "1"
            chunk.rows[0][1] shouldBe "alice"
            chunk.rows[1][0] shouldBe "2"
            chunk.rows[1][1] shouldBe "bob"
            chunk.chunkIndex shouldBe 0L

            r.nextChunk().shouldBeNull()
        }
    }

    test("single row, single chunk") {
        reader("x\n42").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 1
            chunk.rows[0][0] shouldBe "42"
            r.nextChunk().shouldBeNull()
        }
    }

    test("chunkSize larger than data returns one chunk") {
        reader("a\n1\n2\n3", chunkSize = 100).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 3
            r.nextChunk().shouldBeNull()
        }
    }

    // ─── Multi-Chunk ────────────────────────────────────────────────

    test("splits into multiple chunks at chunkSize boundary") {
        reader("v\n1\n2\n3\n4\n5", chunkSize = 2).use { r ->
            val c0 = r.nextChunk()!!
            c0.rows shouldHaveSize 2
            c0.chunkIndex shouldBe 0L
            c0.rows[0][0] shouldBe "1"
            c0.rows[1][0] shouldBe "2"

            val c1 = r.nextChunk()!!
            c1.rows shouldHaveSize 2
            c1.chunkIndex shouldBe 1L

            val c2 = r.nextChunk()!!
            c2.rows shouldHaveSize 1
            c2.chunkIndex shouldBe 2L
            c2.rows[0][0] shouldBe "5"

            r.nextChunk().shouldBeNull()
        }
    }

    // ─── Empty / Header-Only ────────────────────────────────────────

    test("empty file returns null on first nextChunk, headerColumns is null") {
        reader("").use { r ->
            r.headerColumns().shouldBeNull()
            r.nextChunk().shouldBeNull()
        }
    }

    test("header-only file returns null on first nextChunk but headerColumns is set") {
        reader("id,name\n").use { r ->
            r.headerColumns() shouldBe listOf("id", "name")
            r.nextChunk().shouldBeNull()
        }
    }

    // ─── Header Discovery ───────────────────────────────────────────

    test("headerColumns returns column names from header line") {
        reader("alpha,beta\n1,2").use { r ->
            r.headerColumns() shouldBe listOf("alpha", "beta")
        }
    }

    // ─── csvNoHeader ────────────────────────────────────────────────

    test("csvNoHeader: headerColumns is null, data read positionally") {
        val opts = FormatReadOptions(csvNoHeader = true)
        reader("1,alice\n2,bob", options = opts).use { r ->
            r.headerColumns().shouldBeNull()
            val chunk = r.nextChunk()!!
            chunk.columns shouldBe emptyList()
            chunk.rows shouldHaveSize 2
            chunk.rows[0][0] shouldBe "1"
            chunk.rows[0][1] shouldBe "alice"
        }
    }

    test("csvNoHeader: empty file") {
        val opts = FormatReadOptions(csvNoHeader = true)
        reader("", options = opts).use { r ->
            r.headerColumns().shouldBeNull()
            r.nextChunk().shouldBeNull()
        }
    }

    // ─── Null Sentinel (csvNullString) ──────────────────────────────

    test("default csvNullString: empty field becomes null") {
        reader("a,b\n1,\n,2").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "1"
            chunk.rows[0][1].shouldBeNull()
            chunk.rows[1][0].shouldBeNull()
            chunk.rows[1][1] shouldBe "2"
        }
    }

    test("custom csvNullString: matching value becomes null") {
        val opts = FormatReadOptions(csvNullString = "NULL")
        reader("a,b\n1,NULL\nNULL,2", options = opts).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "1"
            chunk.rows[0][1].shouldBeNull()
            chunk.rows[1][0].shouldBeNull()
            chunk.rows[1][1] shouldBe "2"
        }
    }

    test("custom csvNullString: non-matching value stays as string") {
        val opts = FormatReadOptions(csvNullString = "NULL")
        reader("a\nhello\nNULL", options = opts).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows shouldHaveSize 2
            chunk.rows[0][0] shouldBe "hello"   // nicht "NULL" → bleibt String
            chunk.rows[1][0].shouldBeNull()      // "NULL" → null
        }
    }

    // ─── Quoted Fields ──────────────────────────────────────────────

    test("quoted fields with commas preserved") {
        reader("a,b\n\"hello, world\",2").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "hello, world"
            chunk.rows[0][1] shouldBe "2"
        }
    }

    test("quoted fields with embedded newlines preserved") {
        reader("a\n\"line1\nline2\"").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "line1\nline2"
        }
    }

    test("quoted fields with escaped quotes preserved") {
        reader("a\n\"say \"\"hello\"\"\"").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "say \"hello\""
        }
    }

    // ─── Row Width Strictness ───────────────────────────────────────

    test("headered short row throws ImportSchemaMismatchException") {
        val ex = shouldThrow<ImportSchemaMismatchException> {
            reader("a,b,c\n1")
        }
        ex.message shouldContain "headered CSV row has 1 columns, header defined 3"
    }

    test("headered long row throws ImportSchemaMismatchException") {
        val ex = shouldThrow<ImportSchemaMismatchException> {
            reader("a,b\n1,2,3")
        }
        ex.message shouldContain "headered CSV row has 3 columns, header defined 2"
    }

    test("csvNoHeader: later short row throws ImportSchemaMismatchException instead of padding") {
        val opts = FormatReadOptions(csvNoHeader = true)
        reader("1,alice,admin\n2,bob", chunkSize = 1, options = opts).use { r ->
            val first = r.nextChunk()!!
            first.rows[0].size shouldBe 3

            val ex = shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
            ex.message shouldContain "headerless CSV row has 2 columns, first row defined 3"
        }
    }

    test("csvNoHeader: later long row throws ImportSchemaMismatchException instead of truncation") {
        val opts = FormatReadOptions(csvNoHeader = true)
        reader("1,alice\n2,bob,admin", chunkSize = 1, options = opts).use { r ->
            val first = r.nextChunk()!!
            first.rows[0].size shouldBe 2

            val ex = shouldThrow<ImportSchemaMismatchException> {
                r.nextChunk()
            }
            ex.message shouldContain "headerless CSV row has 3 columns, first row defined 2"
        }
    }

    // ─── Lifecycle ──────────────────────────────────────────────────

    test("close is idempotent") {
        val r = reader("")
        r.close()
        r.close() // no exception
    }

    test("nextChunk after close throws IllegalStateException") {
        val r = reader("a\n1")
        r.close()
        shouldThrow<IllegalStateException> {
            r.nextChunk()
        }
    }

    // ─── Encoding ───────────────────────────────────────────────────

    test("UTF-8 BOM handled correctly") {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val csv = "v\n1".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(bom + csv)
        CsvChunkReader(input, "t", 100).use { r ->
            r.headerColumns() shouldBe listOf("v")
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "1"
        }
    }

    test("ISO-8859-1 with explicit encoding") {
        val csv = "name\ncafé".toByteArray(Charsets.ISO_8859_1)
        val input = ByteArrayInputStream(csv)
        val opts = FormatReadOptions(encoding = Charsets.ISO_8859_1)
        CsvChunkReader(input, "t", 100, opts).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "café"
        }
    }

    // 0.8.0 Phase F (docs/ImpPlan-0.8.0-F.md §4.5):
    // BOM-/Encoding-Pfade werden mit nicht-lateinischen Payloads gefahren.

    test("Phase F §4.5: UTF-8 BOM + kyrillisch/CJK/Emoji bleibt byte- und zeichenstabil") {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val csv = "name,note\nМосква,東京 🇯🇵".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(bom + csv)
        CsvChunkReader(input, "t", 100).use { r ->
            r.headerColumns() shouldBe listOf("name", "note")
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "Москва"
            chunk.rows[0][1] shouldBe "東京 🇯🇵"
        }
    }

    test("Phase F §4.5: UTF-16 LE BOM + Unicode-Payload wird erkannt und transcodiert") {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val csv = "name\nΑθήνα".toByteArray(Charsets.UTF_16LE)
        val input = ByteArrayInputStream(bom + csv)
        CsvChunkReader(input, "t", 100).use { r ->
            r.headerColumns() shouldBe listOf("name")
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "Αθήνα"
        }
    }

    test("Phase F §4.5: UTF-16 BE BOM + Unicode-Payload wird erkannt") {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val csv = "name\nMünchen 🇩🇪".toByteArray(Charsets.UTF_16BE)
        val input = ByteArrayInputStream(bom + csv)
        CsvChunkReader(input, "t", 100).use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "München 🇩🇪"
        }
    }

    // ─── All Values Are Strings ─────────────────────────────────────

    test("numeric-looking values remain strings") {
        reader("a,b,c\n42,3.14,true").use { r ->
            val chunk = r.nextChunk()!!
            chunk.rows[0][0] shouldBe "42"
            chunk.rows[0][1] shouldBe "3.14"
            chunk.rows[0][2] shouldBe "true"
        }
    }
})
