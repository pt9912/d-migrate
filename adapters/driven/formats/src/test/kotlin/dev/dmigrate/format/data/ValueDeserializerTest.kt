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

class ValueDeserializerTest : FunSpec({

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

    test("null value → null regardless of column type") {
        forCol(Types.VARCHAR).deserialize(tableName, "c", null) shouldBe null
        forCol(Types.INTEGER).deserialize(tableName, "c", null) shouldBe null
    }

    test("csv null sentinel → null (isCsvSource=true)") {
        val d = forColCsv(Types.VARCHAR, csvNullString = "NULL")
        d.deserialize(tableName, "c", "NULL", isCsvSource = true) shouldBe null
        // In non-CSV path the literal "NULL" stays a string
        d.deserialize(tableName, "c", "NULL", isCsvSource = false) shouldBe "NULL"
    }

    test("csv default null sentinel is empty string") {
        val d = forCol(Types.VARCHAR)
        d.deserialize(tableName, "c", "", isCsvSource = true) shouldBe null
    }

    // ───────────── Strings / CLOB ─────────────

    test("VARCHAR: string passthrough") {
        forCol(Types.VARCHAR).deserialize(tableName, "c", "hello") shouldBe "hello"
    }

    test("VARCHAR: numeric input is rendered as string") {
        forCol(Types.VARCHAR).deserialize(tableName, "c", 42) shouldBe "42"
    }

    test("CLOB / NCLOB / LONGVARCHAR: strings pass through") {
        forCol(Types.CLOB).deserialize(tableName, "c", "long text") shouldBe "long text"
        forCol(Types.NCLOB).deserialize(tableName, "c", "unicode") shouldBe "unicode"
        forCol(Types.LONGVARCHAR).deserialize(tableName, "c", "more") shouldBe "more"
    }

    // ───────────── Boolean ─────────────

    test("BOOLEAN: Boolean input passthrough") {
        val d = forCol(Types.BOOLEAN)
        d.deserialize(tableName, "c", true) shouldBe true
        d.deserialize(tableName, "c", false) shouldBe false
    }

    test("BOOLEAN: case-insensitive true/false strings") {
        val d = forCol(Types.BOOLEAN)
        d.deserialize(tableName, "c", "true") shouldBe true
        d.deserialize(tableName, "c", "TRUE") shouldBe true
        d.deserialize(tableName, "c", "False") shouldBe false
    }

    test("BOOLEAN: trimmed true/false strings (M-A8)") {
        val d = forCol(Types.BOOLEAN)
        d.deserialize(tableName, "c", "  true  ") shouldBe true
        d.deserialize(tableName, "c", "\tFALSE\n") shouldBe false
    }

    test("BOOLEAN: L3 — 0/1/yes/no are rejected") {
        val d = forCol(Types.BOOLEAN)
        shouldThrow<ImportSchemaMismatchException> { d.deserialize(tableName, "c", "yes") }
        shouldThrow<ImportSchemaMismatchException> { d.deserialize(tableName, "c", "1") }
        shouldThrow<ImportSchemaMismatchException> { d.deserialize(tableName, "c", 1) }
        shouldThrow<ImportSchemaMismatchException> { d.deserialize(tableName, "c", 0) }
    }

    // ───────────── Integer ─────────────

    test("INTEGER / BIGINT: Long / Int / Short / Byte pass through as Long") {
        forCol(Types.INTEGER).deserialize(tableName, "c", 42) shouldBe 42L
        forCol(Types.BIGINT).deserialize(tableName, "c", 10_000_000_000L) shouldBe 10_000_000_000L
    }

    test("INTEGER: string without decimal point parses") {
        forCol(Types.INTEGER).deserialize(tableName, "c", "42") shouldBe 42L
    }

    test("INTEGER: string with decimal point is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.INTEGER).deserialize(tableName, "c", "42.5")
        }
    }

    test("INTEGER: H-A2 — Double 1.0 (decimal token) is rejected even though value is integer-shaped") {
        // Plan §3.5.2: token-format-based, not value-based. Reader produces
        // Double for any token with `.`/`e`/`E`, and that must NOT silently
        // pass an INTEGER column.
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.INTEGER).deserialize(tableName, "c", 1.0)
        }
    }

    test("INTEGER: non-integer Double is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.INTEGER).deserialize(tableName, "c", 42.5)
        }
    }

    test("INTEGER: Boolean is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.INTEGER).deserialize(tableName, "c", true)
        }
    }

    // ───────────── Floating point ─────────────

    test("DOUBLE / REAL / FLOAT: Double/Float input passthrough") {
        forCol(Types.DOUBLE).deserialize(tableName, "c", 3.14) shouldBe 3.14
        forCol(Types.REAL).deserialize(tableName, "c", 1.5f) shouldBe 1.5
    }

    test("DOUBLE: integer input is widened to Double") {
        forCol(Types.DOUBLE).deserialize(tableName, "c", 10) shouldBe 10.0
    }

    test("DOUBLE: string parses with fixed dot, never locale-dependent") {
        forCol(Types.DOUBLE).deserialize(tableName, "c", "3.14") shouldBe 3.14
    }

    test("DOUBLE: Boolean is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.DOUBLE).deserialize(tableName, "c", true)
        }
    }

    // ───────────── NUMERIC / DECIMAL ─────────────

    test("NUMERIC: BigDecimal passthrough") {
        val bd = BigDecimal("123.456")
        forCol(Types.NUMERIC).deserialize(tableName, "c", bd) shouldBe bd
    }

    test("NUMERIC: integer token with scale 0 and precision <= 18 becomes Long") {
        forCol(Types.NUMERIC, precision = 10, scale = 0).deserialize(tableName, "c", 42) shouldBe 42L
    }

    test("NUMERIC: integer token with fractional scale stays BigDecimal") {
        forCol(Types.NUMERIC, precision = 10, scale = 2).deserialize(tableName, "c", 42) shouldBe BigDecimal(42)
    }

    test("NUMERIC: integer token with precision > 18 stays BigDecimal") {
        forCol(Types.NUMERIC, precision = 19, scale = 0).deserialize(tableName, "c", 42) shouldBe BigDecimal(42)
    }

    test("NUMERIC: Double input preserves value via String constructor (H-A1)") {
        // Critical: must NOT use BigDecimal.valueOf(double) which goes
        // through Double.toString() and is OK for Double — but the bug was
        // that the old code went through that for Float too. Verify the
        // Double path stays exact.
        forCol(Types.DECIMAL).deserialize(tableName, "c", 3.14) shouldBe BigDecimal("3.14")
    }

    test("NUMERIC: H-A1 — Float input does NOT introduce binary float ghosts") {
        // Old code: BigDecimal.valueOf((value as Number).toDouble()) for
        // Float used to render `0.1f → 0.10000000149011612`. The fix
        // routes Float through `BigDecimal(value.toString())`.
        val result = forCol(Types.NUMERIC).deserialize(tableName, "c", 0.1f) as BigDecimal
        result shouldBe BigDecimal("0.1")
    }

    test("NUMERIC: string parses as BigDecimal") {
        forCol(Types.DECIMAL).deserialize(tableName, "c", "99999999999999.9999") shouldBe
            BigDecimal("99999999999999.9999")
    }

    test("NUMERIC: integer-shaped string with scale 0 and precision <= 18 becomes Long outside CSV") {
        forCol(Types.DECIMAL, precision = 18, scale = 0).deserialize(tableName, "c", "42") shouldBe 42L
    }

    test("NUMERIC: CSV integer-shaped string stays BigDecimal because token form is unavailable") {
        forColCsv(Types.DECIMAL, precision = 18, scale = 0).deserialize(
            tableName,
            "c",
            "42",
            isCsvSource = true,
        ) shouldBe BigDecimal("42")
    }

    test("NUMERIC: precision overflow on integer path is rejected") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.NUMERIC, precision = 3, scale = 0).deserialize(tableName, "c", 1234)
        }
    }

    // ───────────── DATE / TIME / TIMESTAMP ─────────────

    test("DATE: LocalDate passthrough") {
        val ld = LocalDate.parse("2026-04-07")
        forCol(Types.DATE).deserialize(tableName, "c", ld) shouldBe ld
    }

    test("DATE: string parses to LocalDate") {
        forCol(Types.DATE).deserialize(tableName, "c", "2026-04-07") shouldBe LocalDate.parse("2026-04-07")
    }

    test("TIMESTAMP: LocalDateTime passthrough") {
        val ldt = LocalDateTime.parse("2026-04-07T10:00:00")
        forCol(Types.TIMESTAMP).deserialize(tableName, "c", ldt) shouldBe ldt
    }

    test("TIMESTAMP: string parses to LocalDateTime") {
        forCol(Types.TIMESTAMP).deserialize(tableName, "c", "2026-04-07T10:00:00") shouldBe
            LocalDateTime.parse("2026-04-07T10:00:00")
    }

    test("TIMESTAMP: string with explicit offset is rejected (not silently truncated)") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.TIMESTAMP).deserialize(tableName, "c", "2026-04-07T10:00:00+02:00")
        }
    }

    test("TIMESTAMP: string with Z offset is rejected for TIMESTAMP (use TIMESTAMPTZ)") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.TIMESTAMP).deserialize(tableName, "c", "2026-04-07T10:00:00Z")
        }
    }

    test("TIMESTAMP WITH TIME ZONE: string with offset parses to OffsetDateTime") {
        val input = "2026-04-07T10:00:00+02:00"
        forCol(Types.TIMESTAMP_WITH_TIMEZONE).deserialize(tableName, "c", input) shouldBe
            OffsetDateTime.parse(input)
    }

    // 0.8.0 Phase E (`docs/ImpPlan-0.8.0-E.md` §4.1):
    // Der Lesepfad folgt exakt den JDK-ISO-Profilen; reduzierte Zeitformen
    // ohne Sekunden sind dort legal und werden akzeptiert.
    // Der Schreibpfad bleibt demgegenueber kanonisch mit Sekunden.
    test("Phase E §4.1: TIMESTAMP ohne Sekunden-Anteil wird akzeptiert (Lesepfad-Toleranz)") {
        forCol(Types.TIMESTAMP).deserialize(tableName, "c", "2026-04-07T10:15") shouldBe
            LocalDateTime.parse("2026-04-07T10:15")
    }

    test("Phase E §4.1: TIMESTAMPTZ ohne Sekunden-Anteil wird akzeptiert (Lesepfad-Toleranz)") {
        forCol(Types.TIMESTAMP_WITH_TIMEZONE).deserialize(tableName, "c", "2026-04-07T10:15+02:00") shouldBe
            OffsetDateTime.parse("2026-04-07T10:15+02:00")
    }

    // ───────────── UUID (Types.OTHER + sqlTypeName "uuid") ─────────────

    test("UUID: via Types.OTHER + sqlTypeName 'uuid' — string parses") {
        val u = UUID.randomUUID()
        forCol(Types.OTHER, "uuid").deserialize(tableName, "c", u.toString()) shouldBe u
    }

    test("UUID: java.util.UUID passthrough") {
        val u = UUID.randomUUID()
        forCol(Types.OTHER, "uuid").deserialize(tableName, "c", u) shouldBe u
    }

    test("UUID: invalid string raises schema mismatch") {
        shouldThrow<ImportSchemaMismatchException> {
            forCol(Types.OTHER, "uuid").deserialize(tableName, "c", "not-a-uuid")
        }
    }

    // ───────────── JSON / JSONB (Types.OTHER + sqlTypeName "json"/"jsonb") ─────────────

    test("JSONB: string passthrough") {
        val input = """{"k":"v"}"""
        forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", input) shouldBe input
    }

    test("JSONB: Map gets serialized to JSON string") {
        val input = mapOf("k" to "v", "n" to 42)
        val result = forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", input) as String
        // We don't care about key ordering — just that both fields appear
        (result.contains("\"k\":\"v\"") && result.contains("\"n\":42")) shouldBe true
    }

    test("JSONB: List gets serialized to JSON array string") {
        val input = listOf(1, 2, 3)
        forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", input) shouldBe "[1,2,3]"
    }

    test("JSONB: nested Map with string escape") {
        val input = mapOf("quote" to "a\"b", "newline" to "a\nb")
        val result = forCol(Types.OTHER, "jsonb").deserialize(tableName, "c", input) as String
        result.contains("\\\"") shouldBe true
        result.contains("\\n") shouldBe true
    }

    test("JSON null ≠ SQL NULL within JSONB column (JSON null literal not emitted)") {
        // A Kotlin null → SQL NULL directly (tested earlier). What about a
        // JSON object that contains a null value? The outer value is a Map,
        // it serializes to the literal "null" inside the JSON string.
        val result = forCol(Types.OTHER, "jsonb").deserialize(
            tableName, "c", mapOf("k" to null),
        ) as String
        result shouldBe """{"k":null}"""
    }

    // ───────────── INTERVAL / XML (Types.OTHER other sqlTypeNames) ─────────────

    test("INTERVAL: string passthrough — writer layer binds as PGobject") {
        forCol(Types.OTHER, "interval").deserialize(tableName, "c", "1 day") shouldBe "1 day"
    }

    test("XML: string passthrough") {
        forCol(Types.OTHER, "xml").deserialize(tableName, "c", "<root/>") shouldBe "<root/>"
    }

    // ───────────── Binary ─────────────

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
        @Suppress("UNCHECKED_CAST")
        val out = forCol(Types.ARRAY).deserialize(tableName, "c", list) as List<Any?>
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
        @Suppress("UNCHECKED_CAST")
        val out = forCol(Types.ARRAY).deserialize(tableName, "c", arr) as List<Any?>
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
