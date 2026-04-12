package dev.dmigrate.driver

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DatabaseDialectTest : FunSpec({

    test("enum exposes exactly POSTGRESQL, MYSQL, SQLITE") {
        DatabaseDialect.entries.toSet() shouldBe setOf(
            DatabaseDialect.POSTGRESQL,
            DatabaseDialect.MYSQL,
            DatabaseDialect.SQLITE,
        )
    }

    // ─── PostgreSQL aliases ──────────────────────────────────────

    test("fromString resolves canonical postgresql") {
        DatabaseDialect.fromString("postgresql") shouldBe DatabaseDialect.POSTGRESQL
    }

    test("fromString resolves alias postgres") {
        DatabaseDialect.fromString("postgres") shouldBe DatabaseDialect.POSTGRESQL
    }

    test("fromString resolves alias pg") {
        DatabaseDialect.fromString("pg") shouldBe DatabaseDialect.POSTGRESQL
    }

    // ─── MySQL aliases ───────────────────────────────────────────

    test("fromString resolves canonical mysql") {
        DatabaseDialect.fromString("mysql") shouldBe DatabaseDialect.MYSQL
    }

    test("fromString resolves alias maria") {
        DatabaseDialect.fromString("maria") shouldBe DatabaseDialect.MYSQL
    }

    test("fromString resolves alias mariadb") {
        DatabaseDialect.fromString("mariadb") shouldBe DatabaseDialect.MYSQL
    }

    // ─── SQLite aliases ──────────────────────────────────────────

    test("fromString resolves canonical sqlite") {
        DatabaseDialect.fromString("sqlite") shouldBe DatabaseDialect.SQLITE
    }

    test("fromString resolves alias sqlite3") {
        DatabaseDialect.fromString("sqlite3") shouldBe DatabaseDialect.SQLITE
    }

    // ─── Case-insensitivity ──────────────────────────────────────

    test("fromString is case-insensitive") {
        DatabaseDialect.fromString("PostgreSQL") shouldBe DatabaseDialect.POSTGRESQL
        DatabaseDialect.fromString("MYSQL") shouldBe DatabaseDialect.MYSQL
        DatabaseDialect.fromString("SQLite") shouldBe DatabaseDialect.SQLITE
    }

    // ─── Error cases ─────────────────────────────────────────────

    test("fromString throws on unknown dialect") {
        val ex = shouldThrow<IllegalArgumentException> {
            DatabaseDialect.fromString("oracle")
        }
        ex.message!!.contains("oracle") shouldBe true
        ex.message!!.contains("postgresql") shouldBe true
    }

    test("fromString throws on empty string") {
        shouldThrow<IllegalArgumentException> { DatabaseDialect.fromString("") }
    }
})
