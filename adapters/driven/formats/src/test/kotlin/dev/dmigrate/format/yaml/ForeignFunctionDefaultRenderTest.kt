package dev.dmigrate.format.yaml

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.mssql.MssqlDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.oracle.OracleDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Ein Funktions-Default, den der Reverse **mit** Klammern liefert
 * (`newid()`, `now() - interval '1 day'`), darf beim Rendern keine zweiten
 * bekommen.
 *
 * Die Namen der vier neutralen Funktionen tragen keine Klammern und
 * bekommen sie beim Rendern; ein fremder Name traegt sie schon. Blind
 * angehaengt entstuende `newid()()` — gueltig aussehendes SQL, das keine
 * Datenbank annimmt.
 */
class ForeignFunctionDefaultRenderTest : FunSpec({

    val dialects: List<Pair<String, DdlGenerator>> = listOf(
        "postgresql" to PostgresDdlGenerator(),
        "mysql" to MysqlDdlGenerator(),
        "sqlite" to SqliteDdlGenerator(),
        "mssql" to MssqlDdlGenerator(),
        "oracle" to OracleDdlGenerator(),
    )

    fun schemaWith(default: DefaultValue) = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf("c" to ColumnDefinition(NeutralType.Text(maxLength = 40), default = default)),
            ),
        ),
    )

    test("a call that already carries its parentheses keeps exactly one pair") {
        for ((name, generator) in dialects) {
            val sql = generator.generate(schemaWith(DefaultValue.FunctionCall("newid()")), DdlGenerationOptions())
                .render()
            withClue("$name:\n$sql") {
                sql shouldContain "newid()"
                sql shouldNotContain "newid()()"
            }
        }
    }

    test("a bare neutral name still gets its parentheses where the dialect needs them") {
        // Die Gegenprobe: `gen_uuid` traegt keine, und jeder Dialekt setzt
        // seine eigene Funktion dafuer ein.
        val rendered = dialects.associate { (name, generator) ->
            name to generator.generate(
                schemaWith(DefaultValue.FunctionCall("gen_uuid")), DdlGenerationOptions(),
            ).render()
        }
        rendered.getValue("postgresql") shouldContain "gen_random_uuid()"
        rendered.getValue("mssql") shouldContain "NEWID()"
        rendered.getValue("oracle") shouldContain "SYS_GUID()"
        // Wo der Dialekt keine eigene Funktion hat, bleibt der neutrale Name
        // stehen -- aber mit genau EINEM Klammernpaar.
        for ((name, sql) in rendered) {
            withClue("$name:\n$sql") { sql shouldNotContain "gen_uuid()()" }
        }
    }
})
