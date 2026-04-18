package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.profiling.types.LogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [MysqlLogicalTypeResolver] — pure function,
 * no database required. Covers every branch of the `when` expression.
 */
class MysqlLogicalTypeResolverTest : FunSpec({

    val resolver = MysqlLogicalTypeResolver()

    // ── BOOLEAN (must come before INTEGER — tinyint(1) special case) ─
    test("tinyint(1) → BOOLEAN") { resolver.resolve("tinyint(1)") shouldBe LogicalType.BOOLEAN }

    // ── INTEGER ─────────────────────────────────────
    test("int → INTEGER") { resolver.resolve("int") shouldBe LogicalType.INTEGER }
    test("int unsigned → INTEGER") { resolver.resolve("int unsigned") shouldBe LogicalType.INTEGER }
    test("tinyint → INTEGER") { resolver.resolve("tinyint") shouldBe LogicalType.INTEGER }
    test("smallint → INTEGER") { resolver.resolve("smallint") shouldBe LogicalType.INTEGER }
    test("mediumint → INTEGER") { resolver.resolve("mediumint") shouldBe LogicalType.INTEGER }
    test("bigint → INTEGER") { resolver.resolve("bigint") shouldBe LogicalType.INTEGER }

    // ── DECIMAL ─────────────────────────────────────
    test("decimal(10,2) → DECIMAL") { resolver.resolve("decimal(10,2)") shouldBe LogicalType.DECIMAL }
    test("numeric → DECIMAL") { resolver.resolve("numeric") shouldBe LogicalType.DECIMAL }
    test("float → DECIMAL") { resolver.resolve("float") shouldBe LogicalType.DECIMAL }
    test("double → DECIMAL") { resolver.resolve("double") shouldBe LogicalType.DECIMAL }
    test("double precision → DECIMAL") { resolver.resolve("double precision") shouldBe LogicalType.DECIMAL }

    // ── DATE ────────────────────────────────────────
    test("date → DATE") { resolver.resolve("date") shouldBe LogicalType.DATE }

    // ── DATETIME ────────────────────────────────────
    test("datetime → DATETIME") { resolver.resolve("datetime") shouldBe LogicalType.DATETIME }
    test("timestamp → DATETIME") { resolver.resolve("timestamp") shouldBe LogicalType.DATETIME }
    test("time → DATETIME") { resolver.resolve("time") shouldBe LogicalType.DATETIME }
    test("year → DATETIME") { resolver.resolve("year") shouldBe LogicalType.DATETIME }

    // ── BINARY ──────────────────────────────────────
    test("blob → BINARY") { resolver.resolve("blob") shouldBe LogicalType.BINARY }
    test("tinyblob → BINARY") { resolver.resolve("tinyblob") shouldBe LogicalType.BINARY }
    test("mediumblob → BINARY") { resolver.resolve("mediumblob") shouldBe LogicalType.BINARY }
    test("longblob → BINARY") { resolver.resolve("longblob") shouldBe LogicalType.BINARY }
    test("binary(16) → BINARY") { resolver.resolve("binary(16)") shouldBe LogicalType.BINARY }
    test("varbinary(255) → BINARY") { resolver.resolve("varbinary(255)") shouldBe LogicalType.BINARY }

    // ── JSON ────────────────────────────────────────
    test("json → JSON") { resolver.resolve("json") shouldBe LogicalType.JSON }

    // ── GEOMETRY ────────────────────────────────────
    test("point → GEOMETRY") { resolver.resolve("point") shouldBe LogicalType.GEOMETRY }
    test("linestring → GEOMETRY") { resolver.resolve("linestring") shouldBe LogicalType.GEOMETRY }
    test("polygon → GEOMETRY") { resolver.resolve("polygon") shouldBe LogicalType.GEOMETRY }
    test("geometry → GEOMETRY") { resolver.resolve("geometry") shouldBe LogicalType.GEOMETRY }
    test("multipoint → GEOMETRY") { resolver.resolve("multipoint") shouldBe LogicalType.GEOMETRY }
    test("geometrycollection → GEOMETRY") { resolver.resolve("geometrycollection") shouldBe LogicalType.GEOMETRY }

    // ── STRING ──────────────────────────────────────
    test("varchar(100) → STRING") { resolver.resolve("varchar(100)") shouldBe LogicalType.STRING }
    test("char(1) → STRING") { resolver.resolve("char(1)") shouldBe LogicalType.STRING }
    test("text → STRING") { resolver.resolve("text") shouldBe LogicalType.STRING }
    test("tinytext → STRING") { resolver.resolve("tinytext") shouldBe LogicalType.STRING }
    test("mediumtext → STRING") { resolver.resolve("mediumtext") shouldBe LogicalType.STRING }
    test("longtext → STRING") { resolver.resolve("longtext") shouldBe LogicalType.STRING }
    test("enum('a','b') → STRING") { resolver.resolve("enum('a','b')") shouldBe LogicalType.STRING }
    test("set('x','y') → STRING") { resolver.resolve("set('x','y')") shouldBe LogicalType.STRING }

    // ── UNKNOWN ─────────────────────────────────────
    test("unknown type → UNKNOWN") { resolver.resolve("bit(1)") shouldBe LogicalType.UNKNOWN }
    test("empty string → UNKNOWN") { resolver.resolve("") shouldBe LogicalType.UNKNOWN }
})
