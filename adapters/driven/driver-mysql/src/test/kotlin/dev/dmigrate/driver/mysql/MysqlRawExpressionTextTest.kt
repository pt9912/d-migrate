package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/**
 * `"…"` ist im neutralen Modell ein Bezeichner, ein Backslash in `'…'` ein
 * gewoehnliches Zeichen. MySQL liest beides anders; der MySQL-Generator setzt
 * deshalb beides in MySQL-Schreibweise um — und sonst nichts
 * (`spec/ddl-generation-rules.md`, „Roher Ausdruckstext").
 */
class MysqlRawExpressionTextTest : FunSpec({

    context("toMysql") {
        listOf(
            // Der Fall, um den es geht: eine PascalCase-Spalte aus SQL Server.
            "\"Qty\" > 0" to "`Qty` > 0",
            "\"Qty\" * \"UnitPrice\"" to "`Qty` * `UnitPrice`",
            "\"qty\" > 0" to "`qty` > 0",
            // Escapes beider Seiten.
            "\"a\"\"b\" > 0" to "`a\"b` > 0",
            "\"a`b\" > 0" to "`a``b` > 0",
            // Gegenprobe: ein String-Literal mit `"` bleibt ein Literal.
            "note <> 'say \"hi\"'" to "note <> 'say \"hi\"'",
            "note <> 'it''s' AND \"Qty\" > 0" to "note <> 'it''s' AND `Qty` > 0",
            // Gegenprobe: ein Backtick-Bezeichner bleibt, auch mit `"` darin.
            "`Qty` > 0" to "`Qty` > 0",
            "`a\"b` > 0" to "`a\"b` > 0",
            // Der Backslash eines neutralen Literals ist ein Zeichen.
            "path NOT LIKE '%\\_%'" to "path NOT LIKE '%\\\\_%'",
            "note <> 'a\\'" to "note <> 'a\\\\'",
            // Ausserhalb eines Literals bleibt er stehen.
            "a \\ b" to "a \\ b",
            // Kommentare fasst der Scanner nicht an.
            "a > 0 -- \"x\"\nAND \"B\" > 0" to "a > 0 -- \"x\"\nAND `B` > 0",
            "/* \"x\" */ \"B\" > 0" to "/* \"x\" */ `B` > 0",
            // Nichts zu tun.
            "quantity > 0" to "quantity > 0",
            "" to "",
        ).forEachIndexed { index, (input, expected) ->
            test("rewrite #$index: $input") {
                MysqlRawExpressionText.toMysql(input) shouldBe expected
            }
        }

        listOf(
            "\"Qty > 0",
            "note <> 'open",
            "`Qty > 0",
            "/* \"Qty\" > 0",
            // MySQL-Schreibweise in einer neutralen Datei: nach Standard-Regeln
            // nicht abzugrenzen — der Text bleibt, wie er ist.
            "note <> 'it\\'s' AND \"Qty\" > 0",
        ).forEachIndexed { index, input ->
            test("unchanged when not lexable #$index: $input") {
                MysqlRawExpressionText.toMysql(input) shouldBe input
            }
        }
    }

    test("an index key: the expression in MySQL spelling, a column quoted") {
        IndexColumn(name = "lower(\"Name\")", expression = "lower(\"Name\")").mysqlKey { "`$it`" } shouldBe
            "(lower(`Name`))"
        IndexColumn(name = "Name").mysqlKey { "`$it`" } shouldBe "`Name`"
    }

    context("schema generate") {
        val generator = MysqlDdlGenerator()
        val schema = SchemaDefinition(
            name = "s",
            version = "1",
            tables = mapOf(
                "line" to TableDefinition(
                    columns = mapOf(
                        "Qty" to ColumnDefinition(type = NeutralType.Integer),
                        "UnitPrice" to ColumnDefinition(type = NeutralType.Decimal(10, 2)),
                        "LineTotal" to ColumnDefinition(
                            type = NeutralType.Decimal(12, 2),
                            generation = ColumnGeneration.Computed("\"Qty\" * \"UnitPrice\"", stored = true),
                        ),
                        "Name" to ColumnDefinition(
                            type = NeutralType.Text(maxLength = 40),
                            generation = null,
                        ),
                        "code" to ColumnDefinition(type = NeutralType.Enum(refType = "code_domain")),
                    ),
                    indices = listOf(
                        IndexDefinition(
                            name = "ix_line_name",
                            columns = listOf(IndexColumn(name = "lower(\"Name\")", expression = "lower(\"Name\")")),
                        ),
                    ),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "ck_line_qty",
                            type = ConstraintType.CHECK,
                            expression = "\"Qty\" > 0 AND \"Name\" <> 'a\\b'",
                        ),
                    ),
                ),
            ),
            customTypes = mapOf(
                "code_domain" to CustomTypeDefinition(
                    kind = CustomTypeKind.DOMAIN,
                    baseType = "VARCHAR(10)",
                    check = "VALUE <> 'a\\b'",
                ),
            ),
        )
        val ddl = generator.generate(schema).render()

        test("CHECK") {
            ddl shouldContain "CONSTRAINT `ck_line_qty` CHECK (`Qty` > 0 AND `Name` <> 'a\\\\b')"
        }
        test("computed column") {
            ddl shouldContain "GENERATED ALWAYS AS (`Qty` * `UnitPrice`) STORED"
        }
        test("index expression key") {
            ddl shouldContain "(lower(`Name`))"
        }
        test("domain CHECK") {
            ddl shouldContain "CHECK (VALUE <> 'a\\\\b')"
        }
        test("no double-quoted identifier reaches MySQL") {
            ddl shouldNotContain "\"Qty\""
            ddl shouldNotContain "\"Name\""
        }
    }

    context("schema migrate") {
        val sql = MysqlDiffSqlBuilders(MysqlTypeMapper())

        test("CHECK") {
            sql.constraintLine(
                ConstraintDefinition(name = "ck", type = ConstraintType.CHECK, expression = "\"Qty\" > 0"),
            ) shouldBe "CONSTRAINT `ck` CHECK (`Qty` > 0)"
        }
        test("computed column") {
            sql.columnLine(
                "LineTotal",
                ColumnDefinition(
                    type = NeutralType.Decimal(12, 2),
                    generation = ColumnGeneration.Computed("\"Qty\" * \"UnitPrice\"", stored = false),
                ),
            ) shouldBe "`LineTotal` DECIMAL(12,2) GENERATED ALWAYS AS (`Qty` * `UnitPrice`) VIRTUAL"
        }
        test("index expression key") {
            sql.createIndexSql(
                "line",
                IndexDefinition(
                    name = "ix",
                    columns = listOf(IndexColumn(name = "lower(\"Name\")", expression = "lower(\"Name\")")),
                ),
            ) shouldBe "CREATE INDEX `ix` ON `line` ((lower(`Name`)));"
        }
    }

    test("the CHECK preflight probes the same condition the generator creates") {
        val seen = mutableListOf<String>()
        val connection = mockk<Connection>(relaxed = true)
        val statement = mockk<Statement>(relaxed = true)
        every { connection.createStatement() } returns statement
        every { statement.executeQuery(any()) } answers {
            seen += firstArg<String>()
            mockk<ResultSet>(relaxed = true).also {
                every { it.next() } returnsMany listOf(true, false)
                every { it.getLong(1) } returns 0L
            }
        }
        val empty = SchemaDefinition(name = "s", version = "1")
        val diff = DiffPlanner().plan(
            empty, empty,
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "line",
                        constraintsAdded = listOf(
                            ConstraintDefinition(name = "ck", type = ConstraintType.CHECK, expression = "\"Qty\" > 0"),
                        ),
                    ),
                ),
            ),
        )

        val declaration = MysqlCheckPreflightProbe.probe(connection, diff).single()

        seen.single() shouldBe "SELECT count(*) FROM `line` WHERE NOT (`Qty` > 0)"
        // Gemeldet wird der neutrale Text.
        declaration.expression shouldBe "\"Qty\" > 0"
    }
})
