package dev.dmigrate.driver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Die Herkunftspruefung eines Routinen-Rumpfs.
 *
 * Sie entscheidet, ob ein Rumpf ueberhaupt gerendert wird — es gibt keine
 * zweite Instanz, die einen faelschlich verworfenen Rumpf wieder einfaengt.
 * Entsprechend genau muss sie lesen, was in `source_dialect` steht.
 */
class RoutineBodyOriginTest : FunSpec({

    val aliases = mapOf(
        DatabaseDialect.POSTGRESQL to listOf("postgresql", "postgres", "pg", "PostgreSQL", "POSTGRES"),
        DatabaseDialect.MYSQL to listOf("mysql", "maria", "mariadb", "MySQL", "MariaDB"),
        DatabaseDialect.SQLITE to listOf("sqlite", "sqlite3", "SQLite"),
        DatabaseDialect.MSSQL to listOf("mssql", "sqlserver", "SqlServer"),
        DatabaseDialect.ORACLE to listOf("oracle", "Oracle", "ORACLE"),
    )

    test("every spelling of the target's own name counts as its own") {
        // Der Zeichenvergleich, der hier vorher stand, verwarf einen Rumpf,
        // der fuer genau dieses Ziel geschrieben war.
        for ((dialect, spellings) in aliases) {
            for (spelling in spellings) {
                withClue("$spelling -> $dialect") {
                    RoutineBodyOrigin.isForeign(spelling, dialect) shouldBe false
                }
            }
        }
    }

    test("the name of another dialect is foreign, whichever spelling it uses") {
        for ((dialect, spellings) in aliases) {
            for ((other, otherSpellings) in aliases) {
                if (other == dialect) continue
                for (spelling in otherSpellings) {
                    withClue("$spelling -> $dialect") {
                        RoutineBodyOrigin.isForeign(spelling, dialect) shouldBe true
                    }
                }
            }
        }
    }

    test("no origin means the body was written for the target") {
        // Die dokumentierte Art, eine Routine von Hand zu fuehren. Sie
        // abzulehnen machte jedes handgeschriebene Schema unbrauchbar.
        for (dialect in aliases.keys) {
            RoutineBodyOrigin.isForeign(null, dialect) shouldBe false
        }
    }

    test("an unreadable origin stays foreign — nothing is guessed") {
        for (dialect in aliases.keys) {
            withClue("$dialect") {
                RoutineBodyOrigin.isForeign("db2", dialect) shouldBe true
                RoutineBodyOrigin.isForeign("", dialect) shouldBe true
            }
        }
    }
})
