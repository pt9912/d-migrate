package dev.dmigrate.format.yaml

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.mssql.MssqlDdlGenerator
import dev.dmigrate.driver.mssql.MssqlDiffDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDiffDdlGenerator
import dev.dmigrate.driver.oracle.OracleDdlGenerator
import dev.dmigrate.driver.oracle.OracleDiffDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDiffDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDiffDdlGenerator
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Slice 6b: ein Index ueber einem **Ausdruck**. Vier der fuenf Dialekte
 * tragen ihn nativ; SQL Server braucht dafuer eine persistierte berechnete
 * Spalte, die das neutrale Modell nicht kennt, und lehnt deshalb ab.
 *
 * Der Test steht hier statt je Dialekt, weil gerade der Unterschied die
 * Zusicherung ist: vier rendern, einer lehnt ab — und keiner quotet den
 * Ausdruck als Bezeichner.
 */
class ExpressionIndexCrossDialectTest : FunSpec({

    val schema = SchemaDefinition(
        name = "expr-schema",
        version = "1.0.0",
        tables = mapOf(
            "people" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                    "nm" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
                ),
                primaryKey = listOf("id"),
                indices = listOf(
                    IndexDefinition(
                        name = "ix_people_upper",
                        columns = listOf(IndexColumn.expression("UPPER(nm)")),
                    ),
                ),
            ),
        ),
    )

    val rendering: Map<String, DdlGenerator> = mapOf(
        "postgresql" to PostgresDdlGenerator(),
        "sqlite" to SqliteDdlGenerator(),
        "oracle" to OracleDdlGenerator(),
    )

    rendering.forEach { (dialect, generator) ->
        test("$dialect indexes the expression verbatim, without quoting it as an identifier") {
            val ddl = generator.generate(schema).render()
            val create = ddl.lines().single { it.uppercase().startsWith("CREATE INDEX") }
            withClue(create) {
                create shouldContain "UPPER(nm)"
                // Gequotet waere der Ausdruck ein Spaltenname, den es nicht
                // gibt -- die Anweisung liefe ins Leere.
                create shouldNotContain "\"UPPER(nm)\""
                create shouldNotContain "[UPPER(nm)]"
                create shouldNotContain "`UPPER(nm)`"
            }
        }
    }

    test("MySQL wraps a functional key in a second pair of parentheses") {
        // MySQL 8 liest `(UPPER(nm))` sonst als Spaltenliste und lehnt ab.
        val create = MysqlDdlGenerator().generate(schema).render()
            .lines().single { it.uppercase().startsWith("CREATE INDEX") }
        create shouldContain "((UPPER(nm)))"
    }

    test("SQL Server refuses instead of inventing a computed column") {
        val result = MssqlDdlGenerator().generate(schema)
        result.notes.single { it.objectName == "ix_people_upper" }.code shouldBe "E057"
        // Auf die Anweisungen geprueft, nicht auf das Skript: der Ausdruck
        // steht legitim im Meldungstext, der als Kommentar mitgerendert wird.
        result.render().lines().none { it.uppercase().startsWith("CREATE INDEX") } shouldBe true
    }

    test("an expression key is not a column — the type lookup must never see it") {
        val index = schema.tables.getValue("people").indices.single()
        // Genau hier lag der Fehler, den 6a im Oracle-Reverse fand: ein
        // Ausdruck, der als Spaltenname weitergereicht wird.
        index.columnNames shouldBe emptyList()
        index.keyLabels shouldBe listOf("UPPER_nm")
    }

    test("a direction survives on an expression key") {
        val descending = IndexColumn.expression("UPPER(nm)", IndexSortDirection.DESC)
        val withDirection = schema.copy(
            tables = mapOf(
                "people" to schema.tables.getValue("people").copy(
                    indices = listOf(
                        IndexDefinition(name = "ix_desc", columns = listOf(descending)),
                    ),
                ),
            ),
        )
        val create = PostgresDdlGenerator().generate(withDirection).render()
            .lines().single { it.uppercase().startsWith("CREATE INDEX") }
        create shouldContain "(UPPER(nm)) DESC"
    }

    // Jeder Dialekt hat ZWEI Index-Renderer -- einen fuer `schema generate`,
    // einen fuer den Diff-Pfad. Getrennt gepflegt hat der zweite den Ausdruck
    // als Bezeichner gequotet; diese Zusicherung haelt beide zusammen.
    val diffGenerators: Map<String, DiffDdlGenerator> = mapOf(
        "postgresql" to PostgresDiffDdlGenerator(),
        "sqlite" to SqliteDiffDdlGenerator(),
        "mysql" to MysqlDiffDdlGenerator(),
        "oracle" to OracleDiffDdlGenerator(),
    )

    diffGenerators.forEach { (dialect, diffGenerator) ->
        test("$dialect renders the expression key the same way in the migrate path") {
            val index = schema.tables.getValue("people").indices.single()
            val current = schema.copy(
                tables = mapOf("people" to schema.tables.getValue("people").copy(indices = emptyList())),
            )
            val plan = DiffPlanner().plan(
                current, schema,
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "people", indicesAdded = listOf(index)))),
            )
            val sql = diffGenerator.generateUp(plan, DdlGenerationOptions())
                .statements.single { it.sql.uppercase().startsWith("CREATE INDEX") }.sql
            withClue("$dialect: $sql") {
                sql shouldContain "(UPPER(nm))"
                sql shouldNotContain "\"UPPER(nm)\""
                sql shouldNotContain "`UPPER(nm)`"
            }
        }
    }

    test("SQL Server blocks the expression index in the migrate path too") {
        val index = schema.tables.getValue("people").indices.single()
        val current = schema.copy(
            tables = mapOf("people" to schema.tables.getValue("people").copy(indices = emptyList())),
        )
        val plan = DiffPlanner().plan(
            current, schema,
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "people", indicesAdded = listOf(index)))),
        )
        val result = MssqlDiffDdlGenerator().generateUp(plan, DdlGenerationOptions())
        result.statements.none { it.sql.uppercase().startsWith("CREATE INDEX") } shouldBe true
        result.blockers.isEmpty() shouldBe false
    }
})
