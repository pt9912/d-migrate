package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain

class DialectCommandGateTest : FunSpec({

    test("every gated command refuses oracle with an ADR-referencing message") {
        DialectCommandGate.GatedCommand.entries.forEach { command ->
            val refusal = DialectCommandGate.refusal(command, DatabaseDialect.ORACLE)
            refusal.shouldNotBeNull()
            refusal shouldContain command.display
            refusal shouldContain "oracle"
            refusal shouldContain "ADR 0052"
            refusal shouldContain "schema reverse"
        }
    }

    test("oracle refusal lists schema generate and export as already available (Slice 2)") {
        val refusal = DialectCommandGate.refusal(DialectCommandGate.GatedCommand.DATA_PROFILE, DatabaseDialect.ORACLE)
        refusal.shouldNotBeNull()
        refusal shouldContain "schema generate"
        refusal shouldContain "export flyway/liquibase/django/knex"
    }

    test("established dialects (including MSSQL) pass every gate") {
        val established = listOf(
            DatabaseDialect.POSTGRESQL,
            DatabaseDialect.MYSQL,
            DatabaseDialect.SQLITE,
            DatabaseDialect.MSSQL,
        )
        DialectCommandGate.GatedCommand.entries.forEach { command ->
            established.forEach { dialect ->
                DialectCommandGate.refusal(command, dialect).shouldBeNull()
            }
        }
    }
})
