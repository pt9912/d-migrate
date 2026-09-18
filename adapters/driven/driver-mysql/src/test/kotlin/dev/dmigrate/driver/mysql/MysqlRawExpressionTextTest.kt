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
            // M1: ein nacktes reserviertes Wort ist in MySQL kein Bezeichner.
            // Aus `` `key` `` des Reverse wurde neutral `key` — ohne Backticks
            // ist `CHECK (key > 0)` dort ERROR 1064 (gemessen, 9.7.2).
            "key > 0" to "`key` > 0",
            "order > 0 AND `key` > 0" to "`order` > 0 AND `key` > 0",
            "t.order > 0" to "t.`order` > 0",
            // Die Schreibweise des Wortes bleibt, wie sie war.
            "ORDER > 0" to "`ORDER` > 0",
            // Syntax bleibt Syntax: `` `and` `` waere kein Operator mehr.
            "a > 0 AND b IS NOT NULL OR c BETWEEN 1 AND 2" to "a > 0 AND b IS NOT NULL OR c BETWEEN 1 AND 2",
            "CASE WHEN a > 0 THEN 1 ELSE 0 END" to "CASE WHEN a > 0 THEN 1 ELSE 0 END",
            "binary nm = 'x'" to "binary nm = 'x'",
            "nm collate utf8mb4_bin = 'x'" to "nm collate utf8mb4_bin = 'x'",
            "dt + interval 1 year_month" to "dt + interval 1 year_month",
            "created_at < current_timestamp" to "created_at < current_timestamp",
            // Vor `(` steht ein Funktionsname, hinter `AS` ein Typname.
            "left(nm, 1) <> ''" to "left(nm, 1) <> ''",
            "cast(nm as char) <> ''" to "cast(nm as char) <> ''",
            "cast(nm as decimal(10,2)) > 0" to "cast(nm as decimal(10,2)) > 0",
            // Der Typname ist mehrwortig (MySQL-Handbuch, „Cast Functions and
            // Operators"): `signed integer`, `character set <name>`, `double
            // precision`. Nur das erste Wort freizulassen, ergab
            // `cast(total as signed `integer`)` — ERROR 1064 auf 9.7.2 und
            // 8.0.46. Nackt nehmen beide Server jede dieser Formen an.
            "cast(total as signed integer) > 0" to "cast(total as signed integer) > 0",
            "cast(total as unsigned integer) > 0" to "cast(total as unsigned integer) > 0",
            "cast(note as char character set utf8mb4) <> 'x'" to "cast(note as char character set utf8mb4) <> 'x'",
            "cast(x as double precision) > 0" to "cast(x as double precision) > 0",
            "cast(note as char character set utf8mb4 binary) <> 'x'" to
                "cast(note as char character set utf8mb4 binary) <> 'x'",
            "cast(note as char charset utf8mb4) <> 'x'" to "cast(note as char charset utf8mb4) <> 'x'",
            "cast(note as nchar(5)) <> 'x'" to "cast(note as nchar(5)) <> 'x'",
            "cast(j as unsigned array) > 0" to "cast(j as unsigned array) > 0",
            // Dieselbe Typgrammatik traegt `CONVERT(expr, type)`.
            "convert(total, unsigned) > 0" to "convert(total, unsigned) > 0",
            "convert(total, signed integer) > 0" to "convert(total, signed integer) > 0",
            "convert(note, char character set utf8mb4) <> 'x'" to "convert(note, char character set utf8mb4) <> 'x'",
            "convert(note, decimal(10,2)) > 0" to "convert(note, decimal(10,2)) > 0",
            "convert(note using utf8mb4) = 'x'" to "convert(note using utf8mb4) = 'x'",
            "cast(convert(total, unsigned) as char) <> ''" to "cast(convert(total, unsigned) as char) <> ''",
            // Gegenprobe: mit der schliessenden Klammer des Aufrufs greift die
            // Quotierung wieder (`cast(x as char) = key` ist sonst ERROR 1064).
            "cast(nm as char) = key" to "cast(nm as char) = `key`",
            "convert(nm, char) = key" to "convert(nm, char) = `key`",
            "cast(total as signed integer) + key > 0" to "cast(total as signed integer) + `key` > 0",
            "cast(total as decimal(10,2)) > 0 and key > 0" to "cast(total as decimal(10,2)) > 0 and `key` > 0",
            // Ein Wort in **Operandenstellung** ist ein Bezeichner, auch wenn
            // es in Operatorstellung Syntax waere (gemessen auf 9.7.2 und
            // 8.0.46: `CHECK (mod > 0)` ist ERROR 1064, `CHECK (`mod` > 0)`
            // wird angenommen).
            "mod > 0" to "`mod` > 0",
            "default > 0" to "`default` > 0",
            "match > 0" to "`match` > 0",
            "is > 0" to "`is` > 0",
            "year_month > 0" to "`year_month` > 0",
            "separator > 0" to "`separator` > 0",
            "t.default > 0" to "t.`default` > 0",
            "a > 0 and mod > 0" to "a > 0 and `mod` > 0",
            "case when default > 0 then 1 else 0 end" to "case when `default` > 0 then 1 else 0 end",
            "(default + mod) > 0" to "(`default` + `mod`) > 0",
            "match between 1 and 9" to "`match` between 1 and 9",
            // Gegenprobe: dieselben Woerter in Operatorstellung bleiben nackt.
            "total mod 2 = 0" to "total mod 2 = 0",
            "total div 2 > 0" to "total div 2 > 0",
            "nm like 'x%'" to "nm like 'x%'",
            "nm not like 'x%'" to "nm not like 'x%'",
            "nm like binary 'x%'" to "nm like binary 'x%'",
            "a between 1 and 2" to "a between 1 and 2",
            "a not between 1 and 2" to "a not between 1 and 2",
            "a is not null" to "a is not null",
            "a in (1,2)" to "a in (1,2)",
            "a not in (1,2)" to "a not in (1,2)",
            "not (a > 0)" to "not (a > 0)",
            "group_concat(nm separator ',')" to "group_concat(nm separator ',')",
            "count(distinct a)" to "count(distinct a)",
            // Ein reserviertes Wort in einem Literal oder Kommentar bleibt Text.
            "note <> 'order'" to "note <> 'order'",
            "a > 0 -- order\nAND b > 0" to "a > 0 -- order\nAND b > 0",
            // Zahlen sind keine Woerter.
            "0x41 <> nm AND 1e5 > n" to "0x41 <> nm AND 1e5 > n",
            // Nichts zu tun.
            "quantity > 0" to "quantity > 0",
            "total > 0" to "total > 0",
            "" to "",
        ).forEachIndexed { index, (input, expected) ->
            test("rewrite #$index: $input") {
                MysqlRawExpressionText.toMysql(input) shouldBe expected
            }
        }

        // Die Restflaeche, als Pin. Diese Woerter sind auch am Anfang eines
        // Operanden Syntax; quotiert braeche jedes davon die Anweisung
        // (gemessen auf 9.7.2 und 8.0.46). Eine Spalte dieses Namens bleibt
        // deshalb nackt und scheitert weiter am Server — bei `null`, `true`
        // und `false` sogar still, weil MySQL das Literal liest.
        // `docs/planning/open/nackte-reservierte-woerter-im-rohen-ausdruck.md`
        listOf(
            "not", "case", "binary", "interval", "distinct",
            "null", "true", "false",
            "current_date", "current_time", "current_timestamp", "current_user",
            "localtime", "localtimestamp", "utc_date", "utc_time", "utc_timestamp",
        ).forEach { word ->
            test("still bare, the server decides: $word") {
                MysqlRawExpressionText.toMysql("$word > 0") shouldBe "$word > 0"
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
