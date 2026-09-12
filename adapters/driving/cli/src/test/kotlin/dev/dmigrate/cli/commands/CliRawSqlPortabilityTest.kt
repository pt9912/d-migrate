package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Die Bindung, mit der der Migrationspfad rohen Ausdruckstext beurteilt.
 *
 * Sie ist im Hexagon ein Port ohne Vorgabe: bleibt sie ungebunden, prueft der
 * Lauf nicht — und das faellt nirgends auf. Diese Spec haelt wenigstens fest,
 * dass die Bindung selbst das Richtige sagt.
 */
class CliRawSqlPortabilityTest : FunSpec({

    test("a PostgreSQL cast is refused everywhere but on PostgreSQL") {
        CliRawSqlPortability.assess("(quantity)::numeric * unit_price", DatabaseDialect.MSSQL)
            .shouldNotBeNull() shouldContain "::"
        CliRawSqlPortability.assess("(quantity)::numeric * unit_price", DatabaseDialect.POSTGRESQL)
            .shouldBeNull()
    }

    test("PostgreSQL's internal LIKE operator is named as such") {
        CliRawSqlPortability.assess("email ~~ 'x'", DatabaseDialect.ORACLE)
            .shouldNotBeNull() shouldContain "~~"
    }

    test("plain expressions pass on every dialect") {
        DatabaseDialect.entries.forEach { dialect ->
            CliRawSqlPortability.assess("quantity * unit_price", dialect).shouldBeNull()
        }
    }

    test("nothing to judge is not a refusal") {
        CliRawSqlPortability.assess(null, DatabaseDialect.MSSQL).shouldBeNull()
        CliRawSqlPortability.assess("   ", DatabaseDialect.MSSQL).shouldBeNull()
    }

    test("a cast inside a string literal is text, not a cast") {
        CliRawSqlPortability.assess("note = 'a::b'", DatabaseDialect.MSSQL).shouldBeNull()
    }
})
