package dev.dmigrate.format

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DatabaseDialect
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
import io.kotest.matchers.collections.shouldContain

/**
 * `source_dialect` darf in jeder Schreibweise stehen, die d-migrate sonst
 * ueberall akzeptiert.
 *
 * Der Wert ist im Schema-Format eine freie Zeichenkette; die CLI, die
 * Verbindungs-Konfiguration und der Sichten-Rumpf loesen `postgres`, `pg`,
 * `mariadb`, `sqlite3` und `sqlserver` als das auf, was sie meinen. Die
 * Routinen-Pfade verglichen dagegen Zeichen fuer Zeichen: `source_dialect:
 * postgres` gegen ein PostgreSQL-Ziel fiel mit `E053` weg, obwohl der Rumpf
 * fuer genau dieses Ziel geschrieben war.
 */
class RoutineOriginAliasRenderTest : FunSpec({

    val dialects: List<Triple<DatabaseDialect, DdlGenerator, List<String>>> = listOf(
        Triple(DatabaseDialect.POSTGRESQL, PostgresDdlGenerator(), listOf("postgres", "pg", "PostgreSQL")),
        Triple(DatabaseDialect.MYSQL, MysqlDdlGenerator(), listOf("maria", "mariadb", "MySQL")),
        Triple(DatabaseDialect.SQLITE, SqliteDdlGenerator(), listOf("sqlite3", "SQLite")),
        Triple(DatabaseDialect.MSSQL, MssqlDdlGenerator(), listOf("sqlserver", "SqlServer")),
        Triple(DatabaseDialect.ORACLE, OracleDdlGenerator(), listOf("Oracle", "ORACLE")),
    )

    fun schemaWith(origin: String) = SchemaDefinition(
        name = "S", version = "1",
        functions = mapOf("f" to FunctionDefinition(body = "RETURN 1;", sourceDialect = origin)),
        procedures = mapOf("p" to ProcedureDefinition(body = "SELECT 1;", sourceDialect = origin)),
        triggers = mapOf(
            "t::trg" to TriggerDefinition(
                table = "t",
                timing = TriggerTiming.BEFORE,
                events = setOf(TriggerEvent.INSERT),
                body = "SELECT 1;",
                sourceDialect = origin,
            ),
        ),
    )

    /** Ohne den Kopf, dessen Zeitstempel bei jedem Aufruf ein anderer ist. */
    fun bodyOf(sql: String) = sql.lines().filterNot { it.contains("Generated") }

    test("an alias renders exactly like the canonical spelling") {
        for ((dialect, generator, aliases) in dialects) {
            val canonical = generator.generate(schemaWith(dialect.name.lowercase()), DdlGenerationOptions())
            // Vorbedingung: die kanonische Schreibweise rendert ueberhaupt
            // etwas. Sonst waere die Gleichheit unten trivial erfuellt.
            withClue("$dialect kanonisch:\n${canonical.render()}") {
                canonical.render().contains("SELECT 1;") shouldBe true
            }
            for (alias in aliases) {
                val rendered = generator.generate(schemaWith(alias), DdlGenerationOptions())
                withClue("$dialect / $alias:\n${rendered.render()}") {
                    bodyOf(rendered.render()) shouldBe bodyOf(canonical.render())
                    rendered.notes.map { it.code } shouldBe canonical.notes.map { it.code }
                }
            }
        }
    }

    test("a body from another dialect still falls away, named") {
        // Die Gegenprobe: die Normalisierung macht nicht alles portabel, sie
        // liest nur den Namen richtig.
        for ((dialect, generator, _) in dialects) {
            val foreign = if (dialect == DatabaseDialect.ORACLE) "postgres" else "oracle"
            val rendered = generator.generate(schemaWith(foreign), DdlGenerationOptions())
            withClue("$dialect <- $foreign:\n${rendered.render()}") {
                rendered.notes.map { it.code } shouldContain "E053"
            }
        }
    }
})
