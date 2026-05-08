package dev.dmigrate.format.data

import dev.dmigrate.core.data.ImportSchemaMismatchException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.BitSet
import java.util.Base64
import java.util.UUID

class ValueDeserializerTestPart2 : FunSpec({

    // ──────────────────────────────────────────────────────────
    // Test helpers — L15: lookup-closure based, single-column
    // ──────────────────────────────────────────────────────────

    /**
     * Builds a [ValueDeserializer] whose lookup contains exactly one
     * column called "c" with the given JDBC hint. Most tests in this
     * suite need exactly one column, so the helper short-circuits the
     * map-construction boilerplate.
     */
    fun forCol(
        jdbcType: Int,
        sqlTypeName: String? = null,
        precision: Int? = null,
        scale: Int? = null,
    ): ValueDeserializer {
        val hint = JdbcTypeHint(jdbcType, sqlTypeName, precision, scale)
        return ValueDeserializer(typeHintOf = { name -> if (name == "c") hint else null })
    }

    /** Same, but with an explicit CSV null sentinel. */
    fun forColCsv(
        jdbcType: Int,
        sqlTypeName: String? = null,
        csvNullString: String = "",
        precision: Int? = null,
        scale: Int? = null,
    ): ValueDeserializer {
        val hint = JdbcTypeHint(jdbcType, sqlTypeName, precision, scale)
        return ValueDeserializer(
            typeHintOf = { name -> if (name == "c") hint else null },
            csvNullString = csvNullString,
        )
    }

    val tableName = "t"

    // ───────────── Null ─────────────


    test("VARBINARY: ByteArray passthrough") {
        val bytes = byteArrayOf(1, 2, 3)
        val result = forCol(Types.VARBINARY).deserialize(tableName, "c", bytes) as ByteArray
        result.contentEquals(bytes) shouldBe true
    }

    test("BLOB: Base64 string decodes to ByteArray") {
        val original = "hello".toByteArray()
        val b64 = Base64.getEncoder().encodeToString(original)
        val result = forCol(Types.BLOB).deserialize(tableName, "c", b64) as ByteArray
        result.contentEquals(original) shouldBe true
    }

    test("BLOB: invalid Base64 string is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.BLOB).deserialize(tableName, "c", "not base 64!!!")
        }
    }

    // ───────────── ARRAY ─────────────

    test("ARRAY: List passthrough") {
        val list = listOf(1, 2, 3)
        val out = forCol(Types.ARRAY).deserialize(tableName, "c", list) as List<*>
        out shouldContainExactly list
    }

    test("ARRAY: plain string is rejected (no comma-separated interpretation)") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.ARRAY).deserialize(tableName, "c", "a,b,c")
        }
    }

    // ───────────── BIT / BIT(N) ─────────────

    test("BIT(1): string 'true' → Boolean") {
        forCol(Types.BIT, "BIT(1)").deserialize(tableName, "c", "true") shouldBe true
    }

    test("BIT(4): string of 0/1 → BitSet") {
        val bits = forCol(Types.BIT, "BIT(4)").deserialize(tableName, "c", "1010")
        bits.shouldBeInstanceOf<BitSet>()
        // "1010" with LSB right → bits 1 and 3 are set
        bits.get(0) shouldBe false
        bits.get(1) shouldBe true
        bits.get(2) shouldBe false
        bits.get(3) shouldBe true
    }

    test("BIT(8): invalid characters rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.BIT, "BIT(8)").deserialize(tableName, "c", "10102")
        }
    }

    // ───────────── Unknown jdbcType / passthrough ─────────────

    test("unknown jdbcType path: value passthrough") {
        val weirdType = 9999
        forCol(weirdType).deserialize(tableName, "c", "x") shouldBe "x"
    }

    test("unknown column (lookup returns null): value passthrough") {
        // This is the L15 / "unknown column" path: the deserializer was
        // called with a column the lookup doesn't know about. Best-effort
        // passthrough.
        val d = ValueDeserializer(typeHintOf = { null })
        d.deserialize(tableName, "anything", "x") shouldBe "x"
        d.deserialize(tableName, "other", 42) shouldBe 42
    }

    // ───────────── Multi-column lookup ─────────────

    test("multi-column lookup: different hints per column") {
        val hints = mapOf(
            "id" to JdbcTypeHint(Types.INTEGER, "INTEGER"),
            "name" to JdbcTypeHint(Types.VARCHAR, "VARCHAR"),
            "score" to JdbcTypeHint(Types.DOUBLE, "DOUBLE PRECISION"),
        )
        val d = ValueDeserializer(typeHintOf = hints::get)
        d.deserialize("users", "id", "42") shouldBe 42L
        d.deserialize("users", "name", 99) shouldBe "99"
        d.deserialize("users", "score", "3.14") shouldBe 3.14
        // Unknown column → passthrough
        d.deserialize("users", "extra", "raw") shouldBe "raw"
    }

    // ───────────── Error metadata ─────────────

    test("error message names table, column and jdbcType") {
        val ex = shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.INTEGER).deserialize("orders", "c", "not-a-number")
        }
        val msg = ex.message!!
        msg.contains("orders") shouldBe true
        msg.contains("c") shouldBe true
        msg.contains("jdbcType=${Types.INTEGER}") shouldBe true
    }

    // ───────────────────────────────────────────────────────────
    // Additional coverage for else-paths and passthrough branches
    // ───────────────────────────────────────────────────────────

    test("VARCHAR: Boolean input is stringified") {
        forCol(Types.VARCHAR).deserialize(tableName, "c", true) shouldBe "true"
        forCol(Types.VARCHAR).deserialize(tableName, "c", false) shouldBe "false"
    }

    test("VARCHAR: UUID input is rendered via toString()") {
        val u = UUID.fromString("00000000-0000-0000-0000-000000000000")
        forCol(Types.VARCHAR).deserialize(tableName, "c", u) shouldBe u.toString()
    }

    test("INTEGER: NumberFormatException is wrapped as ImportSchemaMismatchException with full metadata") {
        val ex = shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.INTEGER).deserialize("t", "c", "hello")
        }
        ex.message!!.contains("jdbcType=${Types.INTEGER}") shouldBe true
    }

    test("INTEGER: unknown non-Number, non-String, non-Boolean type is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.INTEGER).deserialize(tableName, "c", Any())
        }
    }

    test("DOUBLE: unknown type is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.DOUBLE).deserialize(tableName, "c", Any())
        }
    }

    test("NUMERIC: unknown type is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.NUMERIC).deserialize(tableName, "c", Any())
        }
    }

    test("DATE: unknown type is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.DATE).deserialize(tableName, "c", 42)
        }
    }

    test("DATE: DateTimeParseException on invalid string is wrapped") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.DATE).deserialize(tableName, "c", "not-a-date")
        }
    }

    test("TIME: LocalTime passthrough") {
        val t = LocalTime.parse("10:20:30")
        forCol(Types.TIME).deserialize(tableName, "c", t) shouldBe t
    }

    test("TIME: string parses to LocalTime") {
        forCol(Types.TIME).deserialize(tableName, "c", "10:20:30") shouldBe LocalTime.parse("10:20:30")
    }

    test("TIME: unknown type rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.TIME).deserialize(tableName, "c", 42)
        }
    }

    test("TIMESTAMP: unknown type rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.TIMESTAMP).deserialize(tableName, "c", 42)
        }
    }

    test("TIMESTAMPTZ: OffsetDateTime passthrough") {
        val odt = OffsetDateTime.parse("2026-04-07T10:00:00+02:00")
        forCol(Types.TIMESTAMP_WITH_TIMEZONE).deserialize(tableName, "c", odt) shouldBe odt
    }

    test("TIMESTAMPTZ: unknown type rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.TIMESTAMP_WITH_TIMEZONE).deserialize(tableName, "c", 42)
        }
    }

    test("UUID: non-string non-UUID type rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.OTHER, "uuid").deserialize(tableName, "c", 42)
        }
    }

    test("OTHER: unknown sqlTypeName → value passthrough") {
        forCol(Types.OTHER, "exotic_type").deserialize(tableName, "c", "x") shouldBe "x"
    }

    test("OTHER: missing sqlTypeName → value passthrough") {
        forCol(Types.OTHER).deserialize(tableName, "c", 42) shouldBe 42
    }

    test("INTERVAL: non-string is rendered via toString") {
        forCol(Types.OTHER, "interval").deserialize(tableName, "c", 42) shouldBe "42"
    }

    test("XML: non-string is rendered via toString") {
        forCol(Types.OTHER, "xml").deserialize(tableName, "c", 42) shouldBe "42"
    }

    test("JSONB: else-branch → value.toString for non-string non-Map non-List") {
        forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", 42) shouldBe "42"
    }

    test("VARBINARY: unknown type is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.VARBINARY).deserialize(tableName, "c", 42)
        }
    }

    test("ARRAY: Java Array<Any?> input is converted to List") {
        val arr: Array<Any?> = arrayOf(1, 2, 3)
        val out = forCol(Types.ARRAY).deserialize(tableName, "c", arr) as List<*>
        out shouldContainExactly listOf<Any?>(1, 2, 3)
    }

    test("ARRAY: unknown non-List non-Array type is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.ARRAY).deserialize(tableName, "c", 42)
        }
    }

    test("BIT(1): Boolean passthrough (not multi-bit)") {
        forCol(Types.BIT, "BIT(1)").deserialize(tableName, "c", true) shouldBe true
    }

    test("BIT: no explicit size → BOOLEAN semantics (BIT(1) fallback)") {
        forCol(Types.BIT, "BIT").deserialize(tableName, "c", "true") shouldBe true
    }

    test("BIT(N): BitSet passthrough") {
        val bits = BitSet(4).apply { set(0); set(2) }
        forCol(Types.BIT, "BIT(4)").deserialize(tableName, "c", bits) shouldBe bits
    }

    test("BIT(N): unknown type rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.BIT, "BIT(4)").deserialize(tableName, "c", 42)
        }
    }

    test("BOOLEAN: unknown type (e.g. List) rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.BOOLEAN).deserialize(tableName, "c", listOf(true))
        }
    }

    test("Types.NULL → null") {
        forCol(Types.NULL).deserialize(tableName, "c", "anything") shouldBe null
    }

    // Coverage for jsonToken / escapeJson branches
    test("JSONB: nested List of Maps") {
        val input = listOf(mapOf("a" to 1), mapOf("b" to 2))
        val out = forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", input) as String
        out shouldBe """[{"a":1},{"b":2}]"""
    }

    test("JSONB: deeply nested escape coverage") {
        val input = mapOf(
            "tab" to "\t",
            "cr" to "\r",
            "bs" to "\b",
            "ff" to "\u000C",
            "bslash" to "\\",
            "ctrl" to "\u0001", // below 0x20, non-standard escape
        )
        val out = forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", input) as String
        // All control characters must be escaped, not passed through raw.
        out.contains("\\t") shouldBe true
        out.contains("\\r") shouldBe true
        out.contains("\\b") shouldBe true
        out.contains("\\f") shouldBe true
        out.contains("\\\\") shouldBe true
        out.contains("\\u0001") shouldBe true
    }

    test("JSONB: array inside a map renders as JSON array") {
        val input = mapOf("nums" to listOf(1, 2, 3))
        val out = forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", input) as String
        out shouldBe """{"nums":[1,2,3]}"""
    }

    test("JSONB: Java Array<Any?> inside a map renders as JSON array") {
        val input = mapOf("nums" to arrayOf<Any?>(1, 2, 3))
        val out = forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", input) as String
        out shouldBe """{"nums":[1,2,3]}"""
    }

    test("JSONB: boolean and number primitives render natively") {
        val input = mapOf("b" to true, "n" to 3.14)
        val out = forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", input) as String
        out.contains("\"b\":true") shouldBe true
        out.contains("\"n\":3.14") shouldBe true
    }

    test("JSONB: an unknown value type renders via toString inside JSON string literal") {
        val exotic = UUID.randomUUID()
        val input = mapOf("id" to exotic)
        val out = forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", input) as String
        out.contains("\"id\":\"$exotic\"") shouldBe true
    }
})
