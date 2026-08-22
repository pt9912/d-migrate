package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class DialectCommandGateTest : FunSpec({

    test("every gated command refuses mssql with an ADR-referencing message") {
        DialectCommandGate.GatedCommand.entries.forEach { command ->
            val refusal = DialectCommandGate.refusal(command, DatabaseDialect.MSSQL)
            refusal.shouldNotBeNull()
            refusal shouldContain command.display
            refusal shouldContain "mssql"
            refusal shouldContain "ADR 0047"
            refusal shouldContain "schema reverse"
            refusal shouldContain "schema generate"
        }
    }

    test("only schema migrate and data profile remain gated for mssql") {
        DialectCommandGate.GatedCommand.entries.map { it.display } shouldBe listOf(
            "schema migrate", "data profile",
        )
        DialectCommandGate.AVAILABLE_FOR_MSSQL shouldContain "export flyway/liquibase/django/knex"
        DialectCommandGate.AVAILABLE_FOR_MSSQL shouldContain "data export/import/transfer"
    }

    test("established dialects pass every gate") {
        val established = listOf(
            DatabaseDialect.POSTGRESQL,
            DatabaseDialect.MYSQL,
            DatabaseDialect.SQLITE,
        )
        DialectCommandGate.GatedCommand.entries.forEach { command ->
            established.forEach { dialect ->
                DialectCommandGate.refusal(command, dialect).shouldBeNull()
            }
        }
    }
})
