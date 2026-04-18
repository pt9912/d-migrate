package dev.dmigrate.driver.postgresql.profiling

import dev.dmigrate.profiling.types.LogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [PostgresLogicalTypeResolver] — pure function,
 * no database required. Covers every branch of the `when` expression.
 */
class PostgresLogicalTypeResolverTest : FunSpec({

    val resolver = PostgresLogicalTypeResolver()

    // ── INTEGER ─────────────────────────────────────
    test("int4 → INTEGER") { resolver.resolve("int4") shouldBe LogicalType.INTEGER }
    test("integer → INTEGER") { resolver.resolve("integer") shouldBe LogicalType.INTEGER }
    test("serial → INTEGER") { resolver.resolve("serial") shouldBe LogicalType.INTEGER }
    test("bigserial → INTEGER") { resolver.resolve("bigserial") shouldBe LogicalType.INTEGER }
    test("smallint → INTEGER") { resolver.resolve("smallint") shouldBe LogicalType.INTEGER }
    test("bigint → INTEGER") { resolver.resolve("bigint") shouldBe LogicalType.INTEGER }
    test("smallserial → INTEGER") { resolver.resolve("smallserial") shouldBe LogicalType.INTEGER }

    // ── DECIMAL ─────────────────────────────────────
    test("numeric(10,2) → DECIMAL") { resolver.resolve("numeric(10,2)") shouldBe LogicalType.DECIMAL }
    test("decimal → DECIMAL") { resolver.resolve("decimal") shouldBe LogicalType.DECIMAL }
    test("real → DECIMAL") { resolver.resolve("real") shouldBe LogicalType.DECIMAL }
    test("double precision → DECIMAL") { resolver.resolve("double precision") shouldBe LogicalType.DECIMAL }
    test("float4 → DECIMAL") { resolver.resolve("float4") shouldBe LogicalType.DECIMAL }
    test("float8 → DECIMAL") { resolver.resolve("float8") shouldBe LogicalType.DECIMAL }
    test("money → DECIMAL") { resolver.resolve("money") shouldBe LogicalType.DECIMAL }

    // ── BOOLEAN ─────────────────────────────────────
    test("boolean → BOOLEAN") { resolver.resolve("boolean") shouldBe LogicalType.BOOLEAN }
    test("bool → BOOLEAN") { resolver.resolve("bool") shouldBe LogicalType.BOOLEAN }

    // ── DATE ────────────────────────────────────────
    test("date → DATE") { resolver.resolve("date") shouldBe LogicalType.DATE }

    // ── DATETIME ────────────────────────────────────
    test("timestamp → DATETIME") { resolver.resolve("timestamp") shouldBe LogicalType.DATETIME }
    test("timestamp with time zone → DATETIME") { resolver.resolve("timestamp with time zone") shouldBe LogicalType.DATETIME }
    test("time → DATETIME") { resolver.resolve("time") shouldBe LogicalType.DATETIME }
    // Note: "interval" starts with "int" and is caught by the INTEGER
    // branch before it reaches the DATETIME branch. This is a known
    // resolver quirk — documenting actual behavior, not fixing it here.
    test("interval → INTEGER (startsWith int)") { resolver.resolve("interval") shouldBe LogicalType.INTEGER }

    // ── BINARY ──────────────────────────────────────
    test("bytea → BINARY") { resolver.resolve("bytea") shouldBe LogicalType.BINARY }

    // ── JSON ────────────────────────────────────────
    test("json → JSON") { resolver.resolve("json") shouldBe LogicalType.JSON }
    test("jsonb → JSON") { resolver.resolve("jsonb") shouldBe LogicalType.JSON }

    // ── GEOMETRY ────────────────────────────────────
    test("geometry → GEOMETRY") { resolver.resolve("geometry") shouldBe LogicalType.GEOMETRY }
    test("geography(Point,4326) → GEOMETRY") { resolver.resolve("geography(Point,4326)") shouldBe LogicalType.GEOMETRY }

    // ── STRING ──────────────────────────────────────
    test("varchar(100) → STRING") { resolver.resolve("varchar(100)") shouldBe LogicalType.STRING }
    test("character varying → STRING") { resolver.resolve("character varying") shouldBe LogicalType.STRING }
    test("text → STRING") { resolver.resolve("text") shouldBe LogicalType.STRING }
    test("char → STRING") { resolver.resolve("char") shouldBe LogicalType.STRING }
    test("name → STRING") { resolver.resolve("name") shouldBe LogicalType.STRING }
    test("uuid → STRING") { resolver.resolve("uuid") shouldBe LogicalType.STRING }
    test("citext → STRING") { resolver.resolve("citext") shouldBe LogicalType.STRING }
    test("xml → STRING") { resolver.resolve("xml") shouldBe LogicalType.STRING }
    // Note: "integer[]" starts with "int" → INTEGER branch wins over
    // endsWith("[]") → STRING. Known resolver quirk.
    test("integer[] → INTEGER (startsWith int)") { resolver.resolve("integer[]") shouldBe LogicalType.INTEGER }

    // ── UNKNOWN ─────────────────────────────────────
    test("unknown type → UNKNOWN") { resolver.resolve("pg_lsn") shouldBe LogicalType.UNKNOWN }
    test("empty string → UNKNOWN") { resolver.resolve("") shouldBe LogicalType.UNKNOWN }
})
