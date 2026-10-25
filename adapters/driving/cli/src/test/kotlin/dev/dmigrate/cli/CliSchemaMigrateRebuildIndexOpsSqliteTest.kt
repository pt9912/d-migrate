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
 * Issue #60: Index-Operationen einer Neubau-Tabelle. `--execute` laeuft (Exit 0), die Skripte von `UP` und `DOWN`
 * gelingen ohne den Guard, und der Rueckweg gleicht dem Stand vor dem Lauf.
 */
class CliSchemaMigrateRebuildIndexOpsSqliteTest : FunSpec({

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

    fun exec(db: Path, vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
        }
    }

    fun scalar(db: Path, query: String): String =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> st.executeQuery(query).use { rs -> rs.next(); rs.getString(1) } }
        }

    fun catalog(db: Path): String =
        scalar(db, "SELECT group_concat(type || ':' || name || ':' || coalesce(sql, ''), char(10)) FROM sqlite_master")

    /** Der Neubau schreibt das `CREATE TABLE` mit Anfuehrungszeichen und Zeilenumbruechen; die Struktur zaehlt. */
    fun structure(db: Path): String =
        catalog(db).replace("\"", "").replace(Regex("\\s*([(),])\\s*"), "$1").replace(Regex("\\s+"), " ")

    fun indexCount(db: Path, name: String) =
        scalar(db, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = '$name'")

    fun sqlStatements(sql: Path): List<String> =
        Files.readString(sql).lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun write(dir: Path, name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text) }

    fun file(
        columns: String,
        indices: String = "",
        other: String = "",
        constraints: String = "",
    ) = "schema_format: \"1.0\"\nname: \"U\"\nversion: \"1.0\"\ntables:\n$other  t:\n    columns:\n" +
        "      id: { type: integer, required: true }\n$columns    primary_key: [id]\n$constraints$indices"

    fun index(vararg lines: String) = "    indices:\n" + lines.joinToString("") { "      $it\n" }

    val uniqueColumn = "      u: { type: integer, unique: true }\n"
    val plainColumn = "      u: { type: integer }\n"

    fun required(extra: String = "") = "$plainColumn      a: { type: text, required: true }\n      b: { type: text }\n$extra"

    val uniqueIndexDb = listOf(
        "CREATE TABLE t (id INTEGER NOT NULL, u INTEGER, PRIMARY KEY (id))",
        "CREATE UNIQUE INDEX ix_u ON t (u)",
    )
    val textTableDb = "CREATE TABLE t (id INTEGER NOT NULL, u INTEGER, a TEXT, b TEXT, PRIMARY KEY (id))"

    fun database(dir: Path, vararg statements: String): Path = dir.resolve("db.sqlite").also { exec(it, *statements) }

    fun operations(report: Path): Int =
        Regex("\"operationsTotal\":(\\d+)").find(Files.readString(report))!!.groupValues[1].toInt()

    fun execute(dir: Path, source: String, db: Path, name: String): Pair<Int, Path> {
        val report = dir.resolve("$name.json")
        val code = run(
            "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--execute", "--report", report.toString(),
        )
        return code to report
    }

    fun secondRun(dir: Path, source: String, db: Path): Int {
        val report = dir.resolve("again.json")
        run(
            "schema", "migrate", "--source", write(dir, "again.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--output", dir.resolve("again.sql").toString(), "--report", report.toString(),
        ) shouldBe 0
        return operations(report)
    }

    /** Schreibt `UP` und `DOWN` ohne Ausfuehrung; der Plan endet mit Exit 0, auch wo `--execute` blockierte. */
    fun scripts(dir: Path, source: String, db: Path): Pair<Path, Path> {
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")
        run(
            "schema", "migrate", "--source", write(dir, "plan.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--generate-rollback", "--output", up.toString(),
            "--rollback-output", down.toString(), "--report", dir.resolve("plan.json").toString(),
        ) shouldBe 0
        return up to down
    }

    // ── P0.1 / P0.10: Anweisungsfolge ohne den Guard ──

    test("Skript UP (Bestandsdatei gegen eindeutigen Index) laeuft ohne Guard durch: kein DROP INDEX nach dem Neubau") {
        val dir = Files.createTempDirectory("cli-rebuild-index-up-script")
        val db = database(dir, *uniqueIndexDb.toTypedArray())
        val (up, _) = scripts(dir, file(uniqueColumn), db)

        Files.readString(up) shouldNotContain "DROP INDEX"
        exec(db, *sqlStatements(up).toTypedArray())

        indexCount(db, "ix_u") shouldBe "0"
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 't'") shouldContain "UNIQUE"
    }

    test("Skript DOWN stellt den eindeutigen Index genau einmal wieder her, der Katalog gleicht dem Stand vor dem Lauf") {
        val dir = Files.createTempDirectory("cli-rebuild-index-down-script")
        val db = database(dir, *uniqueIndexDb.toTypedArray())
        val before = structure(db)
        val (up, down) = scripts(dir, file(uniqueColumn), db)

        exec(db, *sqlStatements(up).toTypedArray())
        sqlStatements(down).count { it.startsWith("CREATE UNIQUE INDEX") } shouldBe 1
        exec(db, *sqlStatements(down).toTypedArray())

        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_u'") shouldContain "CREATE UNIQUE INDEX"
        structure(db) shouldBe before
    }

    // ── P0.1 / P0.2: --execute ──

    test("Bestandsdatei gegen eindeutigen Index mit Fremdschluessel auf die Spalte: --execute Exit 0, zweiter Lauf 0") {
        val dir = Files.createTempDirectory("cli-rebuild-index-fk")
        val db = database(
            dir,
            "CREATE TABLE p (id INTEGER NOT NULL PRIMARY KEY)",
            "CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY, u INTEGER, " +
                "CONSTRAINT fk_t_u FOREIGN KEY (u) REFERENCES p (id))",
            "CREATE UNIQUE INDEX ix_u ON t (u)",
            "INSERT INTO p (id) VALUES (1)",
            "INSERT INTO t (id, u) VALUES (1, 1)",
        )
        val source = file(
            uniqueColumn,
            other = "  p:\n    columns:\n      id: { type: integer, required: true }\n    primary_key: [id]\n",
            constraints = "    constraints:\n      - name: fk_t_u\n        type: foreign_key\n        columns: [u]\n" +
                "        references: { table: p, columns: [id] }\n",
        )

        val (code, _) = execute(dir, source, db, "up")

        code shouldBe 0
        indexCount(db, "ix_u") shouldBe "0"
        scalar(db, "SELECT count(*) FROM t") shouldBe "1"
        secondRun(dir, source, db) shouldBe 0
    }

    // ── P0.4: neuer Index neben einem Neubau ──

    test("neuer Index neben einem Nullability-Neubau: --execute Exit 0, Index einmal angelegt, zweiter Lauf 0") {
        val dir = Files.createTempDirectory("cli-rebuild-index-add")
        val db = database(dir, textTableDb)
        val after = file(required(), index("- { name: ix_b, columns: [b] }"))

        val (code, _) = execute(dir, after, db, "up")

        code shouldBe 0
        indexCount(db, "ix_b") shouldBe "1"
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 't'") shouldContain "\"a\" TEXT NOT NULL"
        secondRun(dir, after, db) shouldBe 0
    }

    // ── P0.5: entfallender Index neben einem Neubau ──

    test("entfallender Index neben einem Nullability-Neubau: --execute Exit 0, Index weg, zweiter Lauf 0") {
        val dir = Files.createTempDirectory("cli-rebuild-index-drop")
        val db = database(dir, textTableDb, "CREATE INDEX ix_b ON t (b)")
        val after = file(required())

        val (code, _) = execute(dir, after, db, "up")

        code shouldBe 0
        indexCount(db, "ix_b") shouldBe "0"
        secondRun(dir, after, db) shouldBe 0
    }

    // ── P0.6: unveraenderter Index im Neubau ──

    test("unveraenderter Index im Neubau bleibt erhalten") {
        val dir = Files.createTempDirectory("cli-rebuild-index-keep")
        val db = database(dir, textTableDb, "CREATE INDEX ix_b ON t (b)")
        val after = file(required(), index("- { name: ix_b, columns: [b] }"))

        val (code, _) = execute(dir, after, db, "up")

        code shouldBe 0
        indexCount(db, "ix_b") shouldBe "1"
        secondRun(dir, after, db) shouldBe 0
    }

    // ── Rollback nach dem Fix ──

    for ((label, dbStatements, after) in listOf(
        Triple("neuer Index", listOf(textTableDb), file(required(), index("- { name: ix_b, columns: [b] }"))),
        Triple("entfallender Index", listOf(textTableDb, "CREATE INDEX ix_b ON t (b)"), file(required())),
    )) {
        test("schema rollback nach Neubau mit $label stellt den Stand vor dem Lauf wieder her") {
            val dir = Files.createTempDirectory("cli-rebuild-index-rollback")
            val db = database(dir, *dbStatements.toTypedArray())
            val start = structure(db)
            val (up, down) = scripts(dir, after, db)

            exec(db, *sqlStatements(up).toTypedArray())
            run(
                "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
                "--execute", "--allow-destructive",
            ) shouldBe 0

            structure(db) shouldBe start
        }
    }

    // ── Grenzen, die bleiben ──

    test("Neubau plus AddColumn einer anderen Tabelle: --execute bleibt Exit 8, Datenbank unveraendert") {
        val dir = Files.createTempDirectory("cli-rebuild-index-foreign")
        val db = database(dir, *uniqueIndexDb.toTypedArray(), "CREATE TABLE x (id INTEGER NOT NULL PRIMARY KEY)")
        val before = catalog(db)
        val source = file(
            uniqueColumn,
            other = "  x:\n    columns:\n      id: { type: integer, required: true }\n      e: { type: text }\n" +
                "    primary_key: [id]\n",
        )

        val (code, report) = execute(dir, source, db, "up")

        code shouldBe 8
        Files.readString(report) shouldContain "TRANSACTION_SCOPE_UNSUPPORTED"
        catalog(db) shouldBe before
    }

    test("FULLTEXT-Index neben einem Neubau behaelt seinen Pfad: --execute bleibt Exit 8, Datenbank unveraendert") {
        val dir = Files.createTempDirectory("cli-rebuild-index-fulltext")
        val db = database(dir, textTableDb)
        val before = catalog(db)

        val (code, report) = execute(dir, file(required(), index("- { name: ft_b, type: fulltext, columns: [b] }")), db, "up")

        code shouldBe 8
        Files.readString(report) shouldContain "TRANSACTION_SCOPE_UNSUPPORTED"
        catalog(db) shouldBe before
    }
})
