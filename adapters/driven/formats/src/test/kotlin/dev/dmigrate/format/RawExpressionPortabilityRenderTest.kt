package dev.dmigrate.format

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Rohe Ausdruecke landen nicht mehr ungeprueft in fremder DDL.
 *
 * Gemessen war: ein zurueckgelesener PostgreSQL-CHECK
 * `((email ~~ '%@%'::text))` ging woertlich nach T-SQL, MySQL, SQLite und
 * Oracle — `~~` und `::` gibt es dort nicht, und der Fehler fiel erst dem
 * Zielserver auf. Dasselbe fuer das Index-Praedikat und den
 * Ausdrucks-Schluessel.
 *
 * Sichten-Ruempfe wurden seit jeher beurteilt, Routinen-Ruempfe an ihrer
 * Herkunft — diese drei Felder gar nicht.
 */
class RawExpressionPortabilityRenderTest : FunSpec({

    val dialects: List<Triple<DatabaseDialect, DdlGenerator, String>> = listOf(
        Triple(DatabaseDialect.MSSQL, MssqlDdlGenerator(), "mssql"),
        Triple(DatabaseDialect.MYSQL, MysqlDdlGenerator(), "mysql"),
        Triple(DatabaseDialect.SQLITE, SqliteDdlGenerator(), "sqlite"),
        Triple(DatabaseDialect.ORACLE, OracleDdlGenerator(), "oracle"),
    )

    fun schemaWith(
        constraints: List<ConstraintDefinition> = emptyList(),
        indices: List<IndexDefinition> = emptyList(),
    ) = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, ordinal = 1),
                    "email" to ColumnDefinition(NeutralType.Text(maxLength = 200), ordinal = 2),
                    "amt" to ColumnDefinition(NeutralType.Decimal(12, 2), ordinal = 3),
                ),
                primaryKey = listOf("id"),
                constraints = constraints,
                indices = indices,
            ),
        ),
    )

    /**
     * Nur das ausfuehrbare SQL. Die Meldung zitiert den beanstandeten Ausdruck
     * absichtlich — sonst wuesste der Anwender nicht, welchen er umschreiben
     * soll —, und sie steht als Kommentar in derselben Datei.
     */
    fun executable(sql: String) = sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")

    val pgCheck = ConstraintDefinition(
        name = "ck_email", type = ConstraintType.CHECK, expression = "((email ~~ '%@%'::text))",
    )

    test("a PostgreSQL CHECK expression is refused by name instead of rendered into foreign DDL") {
        for ((dialect, generator, _) in dialects) {
            val result = generator.generate(schemaWith(constraints = listOf(pgCheck)), DdlGenerationOptions())
            val sql = result.render()
            withClue("$dialect:\n$sql\n${result.notes.map { it.code to it.message }}") {
                executable(sql).contains("~~") shouldBe false
                executable(sql).contains("ck_email") shouldBe false
                result.notes.map { it.code } shouldContain "E053"
            }
        }
    }

    test("the same expression still renders for PostgreSQL — it is native there") {
        // Die Gegenprobe: die Pruefung beurteilt das ZIEL, nicht die Herkunft.
        val sql = PostgresDdlGenerator()
            .generate(schemaWith(constraints = listOf(pgCheck)), DdlGenerationOptions()).render()
        executable(sql).contains("~~") shouldBe true
        executable(sql).contains("ck_email") shouldBe true
    }

    test("an index predicate and an expression key are refused the same way") {
        val indices = listOf(
            IndexDefinition(
                name = "ix_partial", columns = listOf(IndexColumn("email")),
                where = "(amt > (0)::numeric)",
            ),
            IndexDefinition(
                name = "ix_expr",
                columns = listOf(IndexColumn("upper(email)", expression = "upper((email)::text)")),
            ),
        )
        for ((dialect, generator, _) in dialects) {
            val result = generator.generate(schemaWith(indices = indices), DdlGenerationOptions())
            val sql = result.render()
            withClue("$dialect:\n$sql\n${result.notes.map { it.code to it.message }}") {
                executable(sql).contains("::") shouldBe false
                result.notes.map { it.code } shouldContain "E053"
            }
        }
    }

    test("a portable expression is untouched everywhere") {
        // Sonst waere die Pruefung ein Rueckschritt: sie darf nur verwerfen,
        // was das Ziel wirklich nicht kann.
        val plain = ConstraintDefinition(
            name = "ck_amt", type = ConstraintType.CHECK, expression = "(amt > 0)",
        )
        for ((dialect, generator, _) in dialects + Triple(
            DatabaseDialect.POSTGRESQL, PostgresDdlGenerator(), "postgresql",
        )) {
            val result = generator.generate(schemaWith(constraints = listOf(plain)), DdlGenerationOptions())
            withClue("$dialect:\n${result.render()}") {
                executable(result.render()).contains("ck_amt") shouldBe true
                result.notes.none { it.code == "E053" } shouldBe true
            }
        }
    }
})
