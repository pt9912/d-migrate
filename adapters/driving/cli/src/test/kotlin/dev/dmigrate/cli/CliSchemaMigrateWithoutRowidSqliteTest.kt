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
 * Ein Tabellenneubau behaelt das Merkmal der Datenbank: `WITHOUT ROWID` bleibt, eine Rowid-Tabelle bleibt eine
 * Rowid-Tabelle. Die Datei aendert es nicht; fuehrt sie `without_rowid: true` gegen eine Rowid-Tabelle, warnt
 * `W164`. `schema generate` schreibt das Merkmal der Datei.
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

    fun rowidDatabase(dir: Path): Path {
        val db = dir.resolve("db.sqlite")
        exec(
            db,
            "CREATE TABLE t (id INTEGER NOT NULL, a TEXT NOT NULL, PRIMARY KEY (id))",
            "INSERT INTO t (id, a) VALUES (1, 'x')",
        )
        return db
    }

    fun w164(report: String) = Regex("\"code\":\"W164\"").findAll(report).count()

    for (mode in listOf("--plan-only", "--execute")) {
        test("Fall A ($mode): Datei true gegen Rowid-Datenbank: der Neubau bleibt Rowid, W164 im Report, Exit 0") {
            val dir = Files.createTempDirectory("cli-wr-a")
            val db = rowidDatabase(dir)

            val (code, report) = migrate(dir, yaml(aRequired = false, withoutRowid = true), db, "up", mode)

            code shouldBe 0
            w164(report) shouldBe 1
            report shouldContain "\"code\":\"W164\",\"severity\":\"WARNING\""
            report shouldContain "\"operationId\":\"AlterColumnNullability:"
            if (mode == "--execute") {
                tableSql(db) shouldNotContain "WITHOUT ROWID"
                scalar(db, "SELECT count(*) FROM t") shouldBe "1"
            }
        }

        test("Fall B ($mode): Datei ohne Angabe gegen Rowid-Datenbank: Rowid, keine Warnung") {
            val dir = Files.createTempDirectory("cli-wr-b")
            val db = rowidDatabase(dir)

            val (code, report) = migrate(dir, yaml(aRequired = false, withoutRowid = false), db, "up", mode)

            code shouldBe 0
            w164(report) shouldBe 0
            if (mode == "--execute") tableSql(db) shouldNotContain "WITHOUT ROWID"
        }

        test("Fall C1 und C2 ($mode): Datenbank WITHOUT ROWID bleibt WITHOUT ROWID, keine Warnung") {
            for (withFlag in listOf(true, false)) {
                val dir = Files.createTempDirectory("cli-wr-c")
                val db = databaseWithoutRowid(dir)

                val (code, report) = migrate(dir, yaml(aRequired = false, withoutRowid = withFlag), db, "up", mode)

                code shouldBe 0
                w164(report) shouldBe 0
                if (mode == "--execute") tableSql(db) shouldContain "WITHOUT ROWID"
            }
        }
    }

    test("Fall E und F: Datei true ohne Neubau (neue nullable Spalte): 1 Operation, Rowid, keine Warnung") {
        for (mode in listOf("--plan-only", "--execute")) {
            val dir = Files.createTempDirectory("cli-wr-e")
            val db = rowidDatabase(dir)
            val source = yaml(aRequired = true, withoutRowid = true)
                .replace("      a: {", "      n: { type: text, required: false }\n      a: {")

            val (code, report) = migrate(dir, source, db, "up", mode)

            code shouldBe 0
            operations(report) shouldBe 1
            w164(report) shouldBe 0
            if (mode == "--execute") tableSql(db) shouldNotContain "WITHOUT ROWID"
        }
    }

    test("Fall A mit FULLTEXT-Index (A-FT, plan-only): FTS5-Aufbau statt W132, Tabelle bleibt Rowid, W164") {
        val dir = Files.createTempDirectory("cli-wr-aft")
        val db = rowidDatabase(dir)
        val source = yaml(aRequired = false, withoutRowid = true) +
            "    indices:\n      - name: ix_ft\n        type: fulltext\n        columns: [a]\n"

        val (code, report) = migrate(dir, source, db, "up", "--plan-only")

        code shouldBe 0
        w164(report) shouldBe 1
        report shouldNotContain "\"code\":\"W132\""
        val up = dir.resolve("up.sql")
        run(
            "schema", "migrate", "--source", dir.resolve("up.yaml").toString(),
            "--target", "db:sqlite://$db", "--output", up.toString(),
        ) shouldBe 0
        val sql = Files.readString(up)
        sql shouldContain "CREATE VIRTUAL TABLE"
        sql shouldNotContain "WITHOUT ROWID"
    }

    test("Rollback-Artefakt von Fall A: die Tabelle kommt als Rowid-Tabelle zurueck") {
        val dir = Files.createTempDirectory("cli-wr-a-down")
        val db = rowidDatabase(dir)
        val down = dir.resolve("down.sql")
        run(
            "schema", "migrate", "--source", write(dir, "s.yaml", yaml(aRequired = false, withoutRowid = true)).toString(),
            "--target", "db:sqlite://$db", "--output", dir.resolve("up.sql").toString(),
            "--generate-rollback", "--rollback-output", down.toString(),
        ) shouldBe 0

        Files.readString(dir.resolve("up.sql")) shouldNotContain "WITHOUT ROWID"
        Files.readString(down) shouldNotContain "WITHOUT ROWID"
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
