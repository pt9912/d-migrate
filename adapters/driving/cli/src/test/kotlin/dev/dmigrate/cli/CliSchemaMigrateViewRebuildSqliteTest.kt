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
 * `schema migrate --execute` baut eine SQLite-Tabelle neu auf, an der Sichten
 * haengen (GitHub-Issue #50) — durch die echte CLI und eine echte Datei. Der
 * Neubau ist hier die Aenderung von `n` von Pflicht auf optional.
 */
class CliSchemaMigrateViewRebuildSqliteTest : FunSpec({

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

    fun yaml(
        required: Boolean,
        declare: Boolean,
        extraTable: Boolean = false,
        viewOverView: Boolean = false,
        withJoin: Boolean = extraTable,
    ): String {
        val deps = if (declare) "\n            dependencies: { tables: [t], columns: { t: [id, n] } }" else ""
        val second = if (extraTable) {
            """
          u:
            columns:
              id: { type: integer, required: ${!required} }
              m:  { type: integer, required: ${required} }
            primary_key: [id]"""
        } else {
            ""
        }
        val joined = if (withJoin) {
            """
          v_tu:
            query: "SELECT t.id AS tid, u.m AS m FROM t JOIN u ON u.id = t.id"
"""
        } else {
            ""
        }
        val stacked = if (viewOverView) {
            """
          v_top:
            query: "SELECT id FROM v_t"
"""
        } else {
            ""
        }
        return """
        schema_format: "1.0"
        name: "V"
        version: "1.0"
        tables:
          t:
            columns:
              id: { type: integer, required: true }
              n:  { type: text, required: $required }
            primary_key: [id]$second
        views:
          v_t:
            query: "SELECT id, n FROM t"$deps
          v_x:
            query: "SELECT 1 AS one"
$joined$stacked""".trimIndent() + "\n"
    }

    fun exec(db: Path, vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
        }
    }

    fun sqlStatements(sql: Path): List<String> =
        Files.readString(sql).lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun scalar(db: Path, query: String): String =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> st.executeQuery(query).use { rs -> rs.next(); rs.getString(1) } }
        }

    fun database(dir: Path, yaml: Path): Path {
        val create = dir.resolve("create.sql")
        run("schema", "generate", "--source", yaml.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        val db = dir.resolve("db.sqlite")
        exec(db, *sqlStatements(create).toTypedArray())
        exec(db, "INSERT INTO t (id, n) VALUES (1, 'a')", "INSERT INTO t (id, n) VALUES (2, 'b')")
        return db
    }

    fun migrate(dir: Path, source: Path, db: Path, name: String): Int {
        val code = run(
            "schema", "migrate", "--source", source.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("$name.json").toString(),
        )
        // Bei einem Fehlschlag steht der Bericht in der Meldung des Tests.
        if (code != 0) error("exit $code: " + Files.readString(dir.resolve("$name.json")))
        return code
    }

    for (declare in listOf(false, true)) {
        test("Neubau mit abhaengiger Sicht (dependencies deklariert: $declare) endet ohne Fehler und ohne Drift") {
            val dir = Files.createTempDirectory("cli-migrate-view-rebuild")
            val before = dir.resolve("a.yaml").also { Files.writeString(it, yaml(required = true, declare = declare)) }
            val after = dir.resolve("b.yaml").also { Files.writeString(it, yaml(required = false, declare = declare)) }
            val db = database(dir, before)

            migrate(dir, after, db, "up") shouldBe 0

            scalar(db, "SELECT count(*) FROM v_t") shouldBe "2"
            scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'n' AND \"notnull\" = 0") shouldBe "1"
            // Ein zweiter Lauf ist ohne Operationen.
            migrate(dir, after, db, "again") shouldBe 0
            // Ein zweiter Lauf ist ohne Operationen: die Sicht gilt nicht mehr als geaendert.
            Files.readString(dir.resolve("again.json")) shouldContain "\"operationsTotal\":0"
        }
    }

    test("Sicht ueber zwei neu gebaute Tabellen und Sicht ueber eine Sicht werden mitgenommen") {
        val dir = Files.createTempDirectory("cli-migrate-view-rebuild-multi")
        val before = dir.resolve("a.yaml").also {
            Files.writeString(it, yaml(required = true, declare = false, extraTable = true, viewOverView = true))
        }
        val after = dir.resolve("b.yaml").also {
            Files.writeString(it, yaml(required = false, declare = false, extraTable = true, viewOverView = true))
        }
        val db = database(dir, before)
        exec(db, "INSERT INTO u (id, m) VALUES (1, 10)")

        migrate(dir, after, db, "up") shouldBe 0

        scalar(db, "SELECT count(*) FROM v_top") shouldBe "2"
        scalar(db, "SELECT m FROM v_tu WHERE tid = 1") shouldBe "10"
        migrate(dir, after, db, "again") shouldBe 0
        Files.readString(dir.resolve("again.json")) shouldContain "\"operationsTotal\":0"
    }

    test("eine neue Sicht ueber zwei neu gebaute Tabellen wird einmal angelegt") {
        val dir = Files.createTempDirectory("cli-migrate-view-rebuild-new")
        val before = dir.resolve("a.yaml").also {
            Files.writeString(it, yaml(required = true, declare = false, extraTable = true, withJoin = false))
        }
        val after = dir.resolve("b.yaml").also {
            Files.writeString(it, yaml(required = false, declare = false, extraTable = true, withJoin = true))
        }
        val db = database(dir, before)
        exec(db, "INSERT INTO u (id, m) VALUES (1, 10)")

        migrate(dir, after, db, "up") shouldBe 0

        scalar(db, "SELECT m FROM v_tu WHERE tid = 1") shouldBe "10"
        migrate(dir, after, db, "again") shouldBe 0
        Files.readString(dir.resolve("again.json")) shouldContain "\"operationsTotal\":0"
    }

    test("Plan-Only meldet keinen TRANSACTION_SCOPE_UNSUPPORTED") {
        val dir = Files.createTempDirectory("cli-migrate-view-rebuild-plan")
        val before = dir.resolve("a.yaml").also { Files.writeString(it, yaml(required = true, declare = false)) }
        val after = dir.resolve("b.yaml").also { Files.writeString(it, yaml(required = false, declare = false)) }
        val db = database(dir, before)
        val up = dir.resolve("up.sql")

        run(
            "schema", "migrate", "--source", after.toString(), "--target", "db:sqlite://$db",
            "--output", up.toString(), "--report", dir.resolve("plan.json").toString(),
        ) shouldBe 0
        Files.readString(dir.resolve("plan.json")) shouldNotContain "TRANSACTION_SCOPE_UNSUPPORTED"
        val script = Files.readString(up)
        script shouldContain "DROP VIEW IF EXISTS \"v_t\""
        // Die Sicht verschwindet vor `DROP TABLE` und kommt nach dem `RENAME` zurueck.
        (script.indexOf("\nDROP VIEW IF EXISTS") < script.indexOf("\nDROP TABLE")) shouldBe true
        (script.indexOf("\nCREATE VIEW \"v_t\"") > script.indexOf("\nALTER TABLE")) shouldBe true
        // Eine Sicht, die die Tabelle nicht nennt, bleibt unberuehrt.
        script shouldNotContain "v_x"
    }

    test("schema rollback stellt Tabelle und Sicht wieder her") {
        val dir = Files.createTempDirectory("cli-migrate-view-rebuild-rollback")
        val before = dir.resolve("a.yaml").also { Files.writeString(it, yaml(required = true, declare = false)) }
        val after = dir.resolve("b.yaml").also { Files.writeString(it, yaml(required = false, declare = false)) }
        val db = database(dir, before)
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")

        run(
            "schema", "migrate", "--source", after.toString(), "--target", "db:sqlite://$db",
            "--generate-rollback", "--output", up.toString(), "--rollback-output", down.toString(),
            "--report", dir.resolve("plan.json").toString(),
        ) shouldBe 0
        exec(db, *sqlStatements(up).toTypedArray())
        // Auch die Down-Richtung nimmt die Sicht um den Neubau mit.
        val downScript = Files.readString(down)
        (downScript.indexOf("\nDROP VIEW IF EXISTS \"v_t\"") in 0 until downScript.indexOf("\nDROP TABLE")) shouldBe true
        (downScript.indexOf("\nCREATE VIEW \"v_t\"") > downScript.indexOf("\nALTER TABLE")) shouldBe true

        run(
            "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
            "--execute", "--allow-destructive",
        ) shouldBe 0
        scalar(db, "SELECT count(*) FROM v_t") shouldBe "2"
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'v_t'") shouldContain "SELECT id, n FROM t"
        scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'n' AND \"notnull\" = 1") shouldBe "1"
    }
})
