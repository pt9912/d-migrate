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
 * `WITHOUT ROWID` ueberlebt einen Tabellenneubau: aus einer Datei ohne das Merkmal (Bestandsdatei)
 * ebenso wie aus einer Datei mit `without_rowid: true`; `schema generate` schreibt es.
 */
class CliSchemaMigrateWithoutRowidSqliteTest : FunSpec({

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

    fun yaml(aRequired: Boolean, withoutRowid: Boolean): String {
        val meta = if (withoutRowid) "    metadata:\n      without_rowid: true\n" else ""
        return "schema_format: \"1.0\"\nname: \"W\"\nversion: \"1.0\"\ntables:\n  t:\n    columns:\n" +
            "      id: { type: integer, required: true }\n      a: { type: text, required: $aRequired }\n" +
            "    primary_key: [id]\n$meta"
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

    fun tableSql(db: Path) = scalar(db, "SELECT sql FROM sqlite_master WHERE name = 't'")

    fun write(dir: Path, name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text) }

    fun migrate(dir: Path, source: String, db: Path, name: String, vararg more: String): Pair<Int, String> {
        val report = dir.resolve("$name.json")
        val code = run(
            "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--report", report.toString(), *more,
        )
        return code to Files.readString(report)
    }

    fun operations(report: String) = Regex("\"operationsTotal\":(\\d+)").find(report)!!.groupValues[1].toInt()

    fun databaseWithoutRowid(dir: Path): Path {
        val db = dir.resolve("db.sqlite")
        exec(
            db,
            "CREATE TABLE t (id INTEGER NOT NULL, a TEXT NOT NULL, PRIMARY KEY (id)) WITHOUT ROWID",
            "INSERT INTO t (id, a) VALUES (1, 'x')",
        )
        return db
    }

    test("Bestandsdatei ohne Merkmal: der Neubau erhaelt WITHOUT ROWID, zweiter Lauf 0 Operationen") {
        val dir = Files.createTempDirectory("cli-wr-existing")
        val db = databaseWithoutRowid(dir)

        val (code, report) = migrate(dir, yaml(aRequired = false, withoutRowid = false), db, "up", "--execute")

        code shouldBe 0
        operations(report) shouldBe 1
        tableSql(db) shouldContain "WITHOUT ROWID"
        scalar(db, "SELECT count(*) FROM t") shouldBe "1"
        operations(migrate(dir, yaml(false, false), db, "again").second) shouldBe 0
    }

    test("Datei mit Merkmal: der Neubau erhaelt WITHOUT ROWID") {
        val dir = Files.createTempDirectory("cli-wr-flag")
        val db = databaseWithoutRowid(dir)

        migrate(dir, yaml(aRequired = false, withoutRowid = true), db, "up", "--execute").first shouldBe 0

        tableSql(db) shouldContain "WITHOUT ROWID"
        operations(migrate(dir, yaml(false, true), db, "again").second) shouldBe 0
    }

    test("Tabelle mit Rowid bleibt eine Tabelle mit Rowid") {
        val dir = Files.createTempDirectory("cli-wr-plain")
        val db = dir.resolve("db.sqlite")
        exec(db, "CREATE TABLE t (id INTEGER NOT NULL, a TEXT NOT NULL, PRIMARY KEY (id))")

        migrate(dir, yaml(aRequired = false, withoutRowid = false), db, "up", "--execute").first shouldBe 0

        tableSql(db) shouldNotContain "WITHOUT ROWID"
    }

    test("schema generate schreibt WITHOUT ROWID; schema reverse liest es zurueck") {
        val dir = Files.createTempDirectory("cli-wr-gen")
        val db = dir.resolve("db.sqlite")
        val create = dir.resolve("gen.sql")
        val src = write(dir, "g.yaml", yaml(aRequired = true, withoutRowid = true)).toString()
        run("schema", "generate", "--source", src, "--target", "sqlite", "--output", create.toString()) shouldBe 0
        Files.readString(create) shouldContain ") WITHOUT ROWID;"
        exec(db, Files.readString(create))

        tableSql(db) shouldContain "WITHOUT ROWID"
        val reversed = dir.resolve("reversed.yaml")
        run("schema", "reverse", "--source", "sqlite://$db", "--output", reversed.toString()) shouldBe 0
        Files.readString(reversed) shouldContain "without_rowid: true"
    }

    test("Reverse einer Rowid-Tabelle traegt kein without_rowid") {
        val dir = Files.createTempDirectory("cli-wr-reverse-plain")
        val db = dir.resolve("db.sqlite")
        exec(db, "CREATE TABLE t (id INTEGER NOT NULL, a TEXT NOT NULL, PRIMARY KEY (id))")
        val reversed = dir.resolve("reversed.yaml")

        run("schema", "reverse", "--source", "sqlite://$db", "--output", reversed.toString()) shouldBe 0

        Files.readString(reversed) shouldNotContain "without_rowid"
    }

    test("FULLTEXT-Index auf einer WITHOUT-ROWID-Tabelle der Datenbank: der Neubau degradiert mit Skip-Kommentar") {
        val dir = Files.createTempDirectory("cli-wr-fulltext")
        val db = databaseWithoutRowid(dir)
        val source = yaml(aRequired = false, withoutRowid = false) +
            "    indices:\n      - name: ix_ft\n        type: fulltext\n        columns: [a]\n"
        val up = dir.resolve("up.sql")

        run(
            "schema", "migrate", "--source", write(dir, "ft.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--output", up.toString(),
        ) shouldBe 0

        val sql = Files.readString(up)
        sql shouldContain ") WITHOUT ROWID;"
        sql shouldContain "-- FULLTEXT index"
        sql shouldNotContain "CREATE VIRTUAL TABLE"
    }

    test("Rollback eines Neubaus stellt die Tabelle mit WITHOUT ROWID wieder her") {
        val dir = Files.createTempDirectory("cli-wr-rollback")
        val db = databaseWithoutRowid(dir)
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")
        val source = write(dir, "s.yaml", yaml(aRequired = false, withoutRowid = false)).toString()
        run(
            "schema", "migrate", "--source", source, "--target", "db:sqlite://$db",
            "--output", up.toString(), "--generate-rollback", "--rollback-output", down.toString(),
        ) shouldBe 0

        Files.readString(down) shouldContain "WITHOUT ROWID"
    }
})
