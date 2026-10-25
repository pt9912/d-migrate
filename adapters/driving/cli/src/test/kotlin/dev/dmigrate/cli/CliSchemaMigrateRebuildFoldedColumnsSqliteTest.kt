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
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Ein Tabellenneubau auf SQLite, den eine andere Aenderung ausloest (hier: `flag` von optional auf
 * Pflicht), blockiert nicht an einer Spalte, deren Typ sich nicht aendert (GitHub-Issue #54,
 * Ursache 2) — auch wenn SQLite sie enger ablegt, als die Datei sie schreibt.
 */
class CliSchemaMigrateRebuildFoldedColumnsSqliteTest : FunSpec({

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

    /** Je Spalte ein Typ, den SQLite enger ablegt, als die Datei ihn schreibt. */
    val folded = linkedMapOf(
        "c_text" to "{ type: text, max_length: 32 }",
        "c_char" to "{ type: char, length: 10 }",
        "c_date" to "{ type: date }",
        "c_time" to "{ type: time }",
        "c_dt" to "{ type: datetime }",
        "c_dttz" to "{ type: datetime, timezone: true }",
        "c_uuid" to "{ type: uuid }",
        "c_json" to "{ type: json }",
        "c_xml" to "{ type: xml }",
        "c_email" to "{ type: email }",
        "c_array" to "{ type: array, element_type: text }",
        "c_bool" to "{ type: boolean }",
        "c_dec" to "{ type: decimal, precision: 10, scale: 2 }",
        "c_fts" to "{ type: fulltext }",
        "c_single" to "{ type: float, float_precision: single }",
    )

    fun yaml(flagRequired: Boolean, columns: Map<String, String>): String {
        val rows = columns.entries.joinToString("\n") { "      ${it.key}: ${it.value}" }
        return listOf(
            "schema_format: \"1.0\"",
            "name: \"Folded\"",
            "version: \"1.0\"",
            "tables:",
            "  t:",
            "    columns:",
            "      id: { type: integer, required: true }",
            "      flag: { type: integer, required: $flagRequired }",
            rows,
            "    primary_key: [id]",
        ).joinToString("\n") + "\n"
    }

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

    fun write(dir: Path, name: String, content: String): Path =
        dir.resolve(name).also { Files.writeString(it, content) }

    fun databaseFromFile(dir: Path, yaml: Path): Path {
        val create = dir.resolve("create.sql")
        run("schema", "generate", "--source", yaml.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        val db = dir.resolve("db.sqlite")
        exec(db, *sqlStatements(create).toTypedArray())
        return db
    }

    fun migrate(dir: Path, source: Path, db: Path, name: String, vararg more: String): Int = run(
        "schema", "migrate", "--source", source.toString(), "--target", "db:sqlite://$db",
        "--report", dir.resolve("$name.json").toString(), *more,
    )

    fun report(dir: Path, name: String): String = Files.readString(dir.resolve("$name.json"))

    // Je Typ ein eigener Test, damit ein Ausfall dem Typ zuzuordnen ist: SQL-Literal und erwarteter Text je Spalte.
    val sample = mapOf(
        "c_text" to ("'abc'" to "abc"),
        "c_char" to ("'abc'" to "abc"),
        "c_date" to ("'2026-01-02'" to "2026-01-02"),
        "c_time" to ("'10:11:12'" to "10:11:12"),
        "c_dt" to ("'2026-01-02 10:11:12'" to "2026-01-02 10:11:12"),
        "c_dttz" to ("'2026-01-02T10:11:12+01:00'" to "2026-01-02T10:11:12+01:00"),
        "c_uuid" to ("'8d1f0c9e-0000-4000-8000-000000000001'" to "8d1f0c9e-0000-4000-8000-000000000001"),
        "c_json" to ("'{\"a\":1}'" to "{\"a\":1}"),
        "c_xml" to ("'<a/>'" to "<a/>"),
        "c_email" to ("'a@b.example'" to "a@b.example"),
        "c_array" to ("'[\"x\"]'" to "[\"x\"]"),
        "c_bool" to ("1" to "1"),
        "c_dec" to ("2.5" to "2.5"),
        "c_fts" to ("'hello world'" to "hello world"),
        "c_single" to ("1.5" to "1.5"),
    )

    test("jede gefaltete Spalte hat einen Beispielwert") {
        sample.keys shouldBe folded.keys
    }

    for ((name, type) in folded) {
        test("Neubau aus anderem Grund (flag wird Pflicht) mit gefalteter Spalte $name ($type) behaelt die Daten") {
            val dir = Files.createTempDirectory("cli-folded-rebuild-$name")
            val column = mapOf(name to type)
            val before = write(dir, "a.yaml", yaml(false, column))
            val after = write(dir, "b.yaml", yaml(true, column))
            val db = databaseFromFile(dir, before)
            val (literal, expected) = sample.getValue(name)
            exec(db, "INSERT INTO t (id, flag, $name) VALUES (1, 7, $literal)")

            migrate(dir, after, db, "up", "--execute") shouldBe 0

            report(dir, "up") shouldNotContain "SQLITE_CAST_NOT_WHITELISTED"
            scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'flag' AND \"notnull\" = 1") shouldBe "1"
            scalar(db, "SELECT CAST($name AS TEXT) FROM t") shouldBe expected
            scalar(db, "SELECT flag FROM t") shouldBe "7"
            migrate(dir, after, db, "again", "--execute") shouldBe 0
            report(dir, "again") shouldContain "\"operationsTotal\":0"
        }
    }

    test("Plan-Only desselben Neubaus plant den Neubau, kopiert ohne CAST und endet ohne Blocker") {
        val dir = Files.createTempDirectory("cli-folded-rebuild-plan")
        val before = write(dir, "a.yaml", yaml(false, folded))
        val after = write(dir, "b.yaml", yaml(true, folded))
        val db = databaseFromFile(dir, before)
        val up = dir.resolve("up.sql")

        migrate(dir, after, db, "plan", "--output", up.toString()) shouldBe 0

        report(dir, "plan") shouldNotContain "SQLITE_CAST_NOT_WHITELISTED"
        report(dir, "plan") shouldNotContain "\"operationsTotal\":0"
        val script = Files.readString(up)
        script shouldContain "DROP TABLE"
        script shouldContain "INSERT INTO"
        script shouldNotContain "CAST("
    }

    test("Neubau gegen eine fremd angelegte Datenbank (VARCHAR, CHAR, DATE, BOOLEAN, DECIMAL) blockiert nicht") {
        val dir = Files.createTempDirectory("cli-folded-rebuild-foreign")
        val db = dir.resolve("foreign.sqlite")
        exec(
            db,
            "CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY, flag INTEGER, v VARCHAR(64), ch CHAR(10), " +
                "d DATE, b BOOLEAN, dc DECIMAL(10,2))",
            "INSERT INTO t VALUES (1, 3, 'x', 'y', '2026-01-02', 1, 1.5)",
        )
        val after = write(
            dir,
            "b.yaml",
            yaml(
                true,
                linkedMapOf(
                    "v" to "{ type: text, max_length: 64 }",
                    "ch" to "{ type: char, length: 10 }",
                    "d" to "{ type: date }",
                    "b" to "{ type: boolean }",
                    "dc" to "{ type: decimal, precision: 10, scale: 2 }",
                ),
            ),
        )

        migrate(dir, after, db, "up", "--execute") shouldBe 0

        scalar(db, "SELECT v || ch || d || b || dc FROM t") shouldBe "xy2026-01-0211.5"
        migrate(dir, after, db, "again", "--execute") shouldBe 0
        report(dir, "again") shouldContain "\"operationsTotal\":0"
    }

    test("VARCHAR(64) nach text(32) gegen eine fremd angelegte Datenbank: der Vergleich faltet die Laenge, kein Blocker") {
        val dir = Files.createTempDirectory("cli-folded-rebuild-foreign-narrow")
        val db = dir.resolve("foreign.sqlite")
        exec(
            db,
            "CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY, flag INTEGER, v VARCHAR(64))",
            "INSERT INTO t VALUES (1, 3, 'x')",
        )
        val after = write(dir, "b.yaml", yaml(true, mapOf("v" to "{ type: text, max_length: 32 }")))

        migrate(dir, after, db, "up", "--execute") shouldBe 0

        scalar(db, "SELECT v FROM t") shouldBe "x"
    }

    test("integer nach text neben gefalteten Spalten blockiert weiter, und nur diese Spalte steht im Blocker") {
        val dir = Files.createTempDirectory("cli-folded-rebuild-real")
        val before = write(dir, "a.yaml", yaml(false, folded + ("x" to "{ type: integer }")))
        val after = write(dir, "b.yaml", yaml(true, folded + ("x" to "{ type: text }")))
        val db = databaseFromFile(dir, before)

        migrate(dir, after, db, "up", "--execute") shouldBe 8

        val text = report(dir, "up")
        text shouldContain "SQLITE_CAST_NOT_WHITELISTED"
        text shouldContain "cannot CAST column `x`"
        for (name in folded.keys) text shouldNotContain "cannot CAST column `$name`"
    }

    test("text(64) nach text(32) von Datei zu Datei: der Vergleich meldet keine Typaenderung, der Neubau blockiert nicht") {
        val dir = Files.createTempDirectory("cli-folded-rebuild-narrow")
        val before = write(dir, "a.yaml", yaml(false, mapOf("x" to "{ type: text, max_length: 64 }")))
        val after = write(dir, "b.yaml", yaml(true, mapOf("x" to "{ type: text, max_length: 32 }")))
        val db = databaseFromFile(dir, before)
        exec(db, "INSERT INTO t (id, flag, x) VALUES (1, 1, 'abc')")

        migrate(dir, after, db, "up", "--execute") shouldBe 0

        scalar(db, "SELECT x FROM t") shouldBe "abc"
    }

    test("schema rollback baut den Neubau zurueck und der Ausgangszustand ist ohne Operationen") {
        val dir = Files.createTempDirectory("cli-folded-rebuild-rollback")
        val before = write(dir, "a.yaml", yaml(false, folded))
        val after = write(dir, "b.yaml", yaml(true, folded))
        val db = databaseFromFile(dir, before)
        exec(db, "INSERT INTO t (id, flag, c_text) VALUES (1, 1, 'abc')")
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")

        // Der Up-Schritt laeuft ueber die CLI (`--execute`), nicht ueber rohes JDBC, damit der echte Ausfuehrungsweg
        // (Transaktionsgrenzen, PRAGMA foreign_keys) den Zustand erzeugt, den der Rollback zuruecknimmt.
        migrate(
            dir, after, db, "up",
            "--execute", "--generate-rollback", "--output", up.toString(), "--rollback-output", down.toString(),
        ) shouldBe 0
        scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'flag' AND \"notnull\" = 1") shouldBe "1"
        scalar(db, "SELECT c_text FROM t") shouldBe "abc"
        run(
            "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
            "--execute", "--allow-destructive",
        ) shouldBe 0

        scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'flag' AND \"notnull\" = 0") shouldBe "1"
        scalar(db, "SELECT c_text FROM t") shouldBe "abc"
        migrate(dir, before, db, "after-rollback", "--execute") shouldBe 0
        report(dir, "after-rollback") shouldContain "\"operationsTotal\":0"
    }
})
