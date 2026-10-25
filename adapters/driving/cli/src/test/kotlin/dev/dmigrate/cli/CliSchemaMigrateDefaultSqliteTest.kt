package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.sqlite.SqliteDriver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Ein `default`, den Datei und SQLite-Datenbank gleich ausdruecken, ist kein
 * `AlterColumnDefault` (GitHub-Issue #54, Ursache 1) — durch die echte CLI und
 * eine echte Datei. Vorher planten `integer`-, `boolean`- und `decimal`-Spalten
 * mit `default` bei jedem Lauf eine Aenderung (Zahlentyp `Int` gegen `Long`,
 * `false` gegen `0`); der Neubau, den sie ausloesten, endete bei `boolean` und
 * `decimal` mit Exit 8.
 */
class CliSchemaMigrateDefaultSqliteTest : FunSpec({

    afterSpec {
        DatabaseDriverRegistry.clear()
        DatabaseDriverRegistry.loadAll()
    }

    fun run(vararg args: String): Int {
        DatabaseDriverRegistry.clear()
        DatabaseDriverRegistry.register(SqliteDriver())
        return try {
            DMigrate().subcommands(SchemaCommand()).parse(args.toList())
            0
        } catch (e: ProgramResult) {
            e.statusCode
        }
    }

    /** Tabelle `t(id, c [, d])`; [column] ist die Flow-Definition von `c`. */
    fun yaml(column: String, withD: Boolean = false): String =
        "schema_format: \"1.0\"\nname: \"D\"\nversion: \"1.0\"\ntables:\n  t:\n    columns:\n" +
            "      id: { type: integer, required: true }\n      c: $column\n" +
            (if (withD) "      d: { type: text }\n" else "") + "    primary_key: [id]\n"

    fun exec(db: Path, vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
        }
    }

    fun scalar(db: Path, query: String): String =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> st.executeQuery(query).use { rs -> rs.next(); rs.getString(1) } }
        }

    fun sqlStatements(sql: Path): List<String> =
        Files.readString(sql).lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun write(dir: Path, name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text) }

    fun databaseFrom(dir: Path, text: String): Path {
        val create = dir.resolve("create.sql")
        run(
            "schema", "generate", "--source", write(dir, "a.yaml", text).toString(),
            "--target", "sqlite", "--output", create.toString(),
        ) shouldBe 0
        val db = dir.resolve("db.sqlite")
        exec(db, *sqlStatements(create).toTypedArray())
        return db
    }

    /** Plan ohne Ausfuehrung: Exit-Code, Anzahl der Operationen und deren Arten. */
    fun plan(dir: Path, source: String, db: Path, name: String): Triple<Int, Int, List<String>> {
        val report = dir.resolve("$name.json")
        val code = run(
            "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--plan-only", "--report", report.toString(),
        )
        val text = Files.readString(report)
        val ops = Regex("\"operationsTotal\":(\\d+)").find(text)!!.groupValues[1].toInt()
        val kinds = Regex("\"kind\":\"(\\w+)\"").findAll(text).map { it.groupValues[1] }.toList()
        return Triple(code, ops, kinds)
    }

    fun execute(dir: Path, source: String, db: Path, name: String): Int = run(
        "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
        "--target", "db:sqlite://$db", "--execute", "--report", dir.resolve("$name.json").toString(),
    )

    // Jede Zeile: ein Default, den Datei und Datenbank gleich meinen.
    val unchanged = listOf(
        "integer, Pflicht, 0" to "{ type: integer, required: true, default: 0 }",
        "integer, Pflicht, 5" to "{ type: integer, required: true, default: 5 }",
        "integer, Pflicht, negativ" to "{ type: integer, required: true, default: -1 }",
        "integer, optional, 0" to "{ type: integer, default: 0 }",
        "integer, ueber Int" to "{ type: integer, required: true, default: 2147483648 }",
        "smallint" to "{ type: smallint, required: true, default: 5 }",
        "biginteger, 0" to "{ type: biginteger, required: true, default: 0 }",
        "biginteger, gross" to "{ type: biginteger, required: true, default: 5000000000 }",
        "float, ganze Zahl" to "{ type: float, required: true, default: 1 }",
        "float, Kommazahl" to "{ type: float, required: true, default: 1.5 }",
        "decimal, 0" to "{ type: decimal, precision: 10, scale: 2, required: true, default: 0 }",
        "decimal, 2.0" to "{ type: decimal, precision: 10, scale: 2, required: true, default: 2.0 }",
        "decimal, 1e3" to "{ type: decimal, precision: 10, scale: 2, required: true, default: 1e3 }",
        "boolean, false" to "{ type: boolean, required: true, default: false }",
        "boolean, true" to "{ type: boolean, required: true, default: true }",
        "boolean, optional, false" to "{ type: boolean, default: false }",
        "text" to "{ type: text, required: true, default: \"x\" }",
        "text, Ziffer als Text" to "{ type: text, required: true, default: \"0\" }",
        "Funktion current_timestamp" to "{ type: datetime, required: true, default: current_timestamp }",
        "kein Default" to "{ type: integer }",
        "default: null" to "{ type: integer, default: null }",
    )

    for ((label, column) in unchanged) {
        test("$label: keine Operation, additive Aenderung mit --execute Exit 0, zweiter Lauf 0") {
            val dir = Files.createTempDirectory("cli-migrate-default")
            val db = databaseFrom(dir, yaml(column))

            plan(dir, yaml(column), db, "same") shouldBe Triple(0, 0, emptyList<String>())
            execute(dir, yaml(column, withD = true), db, "up") shouldBe 0
            scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'd'") shouldBe "1"
            plan(dir, yaml(column, withD = true), db, "again") shouldBe Triple(0, 0, emptyList<String>())
        }
    }

    // Jede Zeile: eine echte Aenderung des Defaults; sie bleibt eine Operation.
    val changed = listOf(
        "integer 0 -> 5" to ("{ type: integer, required: true, default: 0 }" to
            "{ type: integer, required: true, default: 5 }"),
        "integer: Default entfernt" to ("{ type: integer, required: true, default: 0 }" to
            "{ type: integer, required: true }"),
        "integer: Default hinzugefuegt" to ("{ type: integer, required: true }" to
            "{ type: integer, required: true, default: 0 }"),
        "float 1.5 -> 2.5" to ("{ type: float, required: true, default: 1.5 }" to
            "{ type: float, required: true, default: 2.5 }"),
        "float 1.5 -> 1" to ("{ type: float, required: true, default: 1.5 }" to
            "{ type: float, required: true, default: 1 }"),
        "decimal 0 -> 2.5" to ("{ type: decimal, precision: 10, scale: 2, required: true, default: 0 }" to
            "{ type: decimal, precision: 10, scale: 2, required: true, default: 2.5 }"),
        "boolean false -> true" to ("{ type: boolean, required: true, default: false }" to
            "{ type: boolean, required: true, default: true }"),
        "boolean: Default entfernt" to ("{ type: boolean, required: true, default: false }" to
            "{ type: boolean, required: true }"),
        "text x -> y" to ("{ type: text, required: true, default: \"x\" }" to
            "{ type: text, required: true, default: \"y\" }"),
    )

    for ((label, columns) in changed) {
        test("echte Aenderung bleibt eine Operation: $label") {
            val dir = Files.createTempDirectory("cli-migrate-default-changed")
            val db = databaseFrom(dir, yaml(columns.first))

            val (_, ops, kinds) = plan(dir, yaml(columns.second), db, "plan")
            ops shouldBe 1
            kinds shouldBe listOf("AlterColumnDefault")
        }
    }

    test("echte Aenderung eines integer-Defaults wird ausgefuehrt, danach keine Operation") {
        val dir = Files.createTempDirectory("cli-migrate-default-apply")
        val db = databaseFrom(dir, yaml("{ type: integer, required: true, default: 0 }"))
        val after = yaml("{ type: integer, required: true, default: 5 }")

        execute(dir, after, db, "up") shouldBe 0
        scalar(db, "SELECT dflt_value FROM pragma_table_info('t') WHERE name = 'c'") shouldBe "5"
        plan(dir, after, db, "again") shouldBe Triple(0, 0, emptyList<String>())
    }

    test("boolean-Default aus schema reverse kommt als Zahl zurueck und plant nichts") {
        val dir = Files.createTempDirectory("cli-migrate-default-reverse")
        val column = "{ type: boolean, required: true, default: true }"
        val db = databaseFrom(dir, yaml(column))
        val reversed = dir.resolve("reversed.yaml")

        run("schema", "reverse", "--source", "sqlite://$db", "--output", reversed.toString()) shouldBe 0

        Files.readString(reversed) shouldContain "default: 1"
        plan(dir, yaml(column), db, "same").second shouldBe 0
    }

    test("schema rollback stellt den Stand wieder her, an einer Tabelle mit integer-Default") {
        val dir = Files.createTempDirectory("cli-migrate-default-rollback")
        val column = "{ type: integer, required: true, default: 5 }"
        val db = databaseFrom(dir, yaml(column))
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")

        run(
            "schema", "migrate", "--source", write(dir, "after.yaml", yaml(column, withD = true)).toString(),
            "--target", "db:sqlite://$db", "--generate-rollback", "--output", up.toString(),
            "--rollback-output", down.toString(), "--report", dir.resolve("plan.json").toString(),
        ) shouldBe 0
        exec(db, *sqlStatements(up).toTypedArray())
        run(
            "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
            "--execute", "--allow-destructive",
        ) shouldBe 0

        plan(dir, yaml(column), db, "after-rollback") shouldBe Triple(0, 0, emptyList<String>())
    }
})
