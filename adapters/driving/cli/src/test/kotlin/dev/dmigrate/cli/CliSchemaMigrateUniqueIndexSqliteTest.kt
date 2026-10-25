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
 * Ein eindeutiger Index (`CREATE UNIQUE INDEX`) gilt gegen die SQLite-Datenbank
 * nicht mehr als geaendert (GitHub-Issue #53) — durch die echte CLI und eine
 * echte Datei. Vorher plante der Vergleich fuer den unveraenderten Index
 * `DropConstraint` + `AddIndex`; `--execute` mit einer weiteren additiven
 * Aenderung endete mit `TRANSACTION_SCOPE_UNSUPPORTED` (Exit 8).
 */
class CliSchemaMigrateUniqueIndexSqliteTest : FunSpec({

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

    /** Tabelle `t(id, a, b, c [, d])`; [extra] sind Schluessel der Tabelle (`indices:`/`constraints:`). */
    fun yaml(extra: String = "", colUnique: Boolean = false, withD: Boolean = false, cRequired: Boolean = true): String {
        val a = if (colUnique) "{ type: text, required: true, unique: true }" else "{ type: text, required: true }"
        val d = if (withD) "      d: { type: text }\n" else ""
        return "schema_format: \"1.0\"\nname: \"U\"\nversion: \"1.0\"\ntables:\n  t:\n    columns:\n" +
            "      id: { type: integer, required: true }\n      a: $a\n      b: { type: text, required: true }\n" +
            "      c: { type: text, required: $cRequired }\n$d    primary_key: [id]\n$extra"
    }

    fun indices(vararg lines: String) = "    indices:\n" + lines.joinToString("") { "      $it\n" }

    fun constraints(vararg lines: String) = "    constraints:\n" + lines.joinToString("") { "      $it\n" }

    fun uniqueIndex(columns: String, vararg more: String) =
        indices("- name: ix_t", "  unique: true", "  columns: $columns", *more)

    fun uniqueConstraint(name: String, columns: String) =
        constraints("- name: $name", "  type: unique", "  columns: $columns")

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
        exec(db, "INSERT INTO t (id, a, b, c) VALUES (1, 'x', 'y', 'z')")
        return db
    }

    /** Plan ohne Ausfuehrung: Anzahl der Operationen und das erzeugte Skript. */
    fun plan(dir: Path, source: String, db: Path, name: String): Pair<Int, String> {
        val up = dir.resolve("$name.sql")
        val report = dir.resolve("$name.json")
        val code = run(
            "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--output", up.toString(), "--report", report.toString(),
        )
        code shouldBe 0
        val ops = Regex("\"operationsTotal\":(\\d+)").find(Files.readString(report))!!.groupValues[1].toInt()
        return ops to (if (Files.exists(up)) Files.readString(up) else "")
    }

    /** Fuehrt aus; liefert Exit-Code und Bericht (fuer die Fehlerklasse). */
    fun execute(dir: Path, source: String, db: Path, name: String): Pair<Int, String> {
        val report = dir.resolve("$name.json")
        val code = run(
            "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--execute", "--report", report.toString(),
        )
        return code to Files.readString(report)
    }

    fun fkSchema(extra: String): String =
        "schema_format: \"1.0\"\nname: \"U\"\nversion: \"1.0\"\ntables:\n  p:\n    columns:\n" +
            "      id: { type: integer, required: true }\n    primary_key: [id]\n  t:\n    columns:\n" +
            "      id: { type: integer, required: true }\n      a: { type: integer, required: true }\n" +
            "      b: { type: text, required: true }\n      c: { type: text, required: true }\n" +
            "    primary_key: [id]\n    constraints:\n      - name: fk_t_a\n        type: foreign_key\n" +
            "        columns: [a]\n        references: { table: p, columns: [id] }\n$extra"

    // Jede Zeile: ein Index/Constraint, den Datei und Datenbank gleich meinen.
    val unchanged = mapOf(
        "eindeutig, eine Spalte" to uniqueIndex("[a]"),
        "eindeutig, zwei Spalten" to uniqueIndex("[a, b]"),
        "eindeutig, drei Spalten" to uniqueIndex("[a, b, c]"),
        "eindeutig mit Praedikat" to uniqueIndex("[a]", "  where: \"b IS NOT NULL\""),
        "eindeutig, absteigend" to uniqueIndex("\n        - { name: a, direction: desc }"),
        "eindeutig auf der Primaerschluesselspalte" to uniqueIndex("[id]"),
        "nicht eindeutig" to indices("- name: ix_t", "  columns: [a]"),
        "UNIQUE-Constraint, zwei Spalten" to uniqueConstraint("uq_t", "[a, b]"),
        "UNIQUE-Constraint, eine Spalte" to uniqueConstraint("uq_t", "[a]"),
    )

    for ((label, extra) in unchanged) {
        test("$label: keine Operation, additive Aenderung mit --execute Exit 0, zweiter Lauf 0") {
            val dir = Files.createTempDirectory("cli-migrate-unique-index")
            val db = databaseFrom(dir, yaml(extra))

            plan(dir, yaml(extra), db, "same").first shouldBe 0
            val (code, report) = execute(dir, yaml(extra, withD = true), db, "up")
            report shouldNotContain "TRANSACTION_SCOPE_UNSUPPORTED"
            code shouldBe 0
            scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'd'") shouldBe "1"
            plan(dir, yaml(extra, withD = true), db, "again").first shouldBe 0
        }
    }

    test("Spalten-unique und eigener Index auf derselben Spalte: keine Operation") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-both")
        val text = yaml(uniqueIndex("[a]"), colUnique = true)
        val db = databaseFrom(dir, text)

        plan(dir, text, db, "same").first shouldBe 0
        execute(dir, yaml(uniqueIndex("[a]"), colUnique = true, withD = true), db, "up").first shouldBe 0
    }

    test("Spalte mit unique: true (kein Index): keine Operation") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-column")
        val db = databaseFrom(dir, yaml(colUnique = true))

        plan(dir, yaml(colUnique = true), db, "same").first shouldBe 0
    }

    test("Fremdschluesselspalte mit eindeutigem Index: keine Operation") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-fk")
        val text = fkSchema(indices("- name: ix_t", "  unique: true", "  columns: [a]"))
        val db = databaseFrom(dir, text)

        plan(dir, text, db, "same").first shouldBe 0
    }

    test("WITHOUT ROWID mit eindeutigem Index: keine Operation") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-wr")
        val db = dir.resolve("db.sqlite")
        exec(
            db,
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT NOT NULL, b TEXT NOT NULL, c TEXT NOT NULL) WITHOUT ROWID",
            "CREATE UNIQUE INDEX ix_t ON t (a)",
        )

        plan(dir, yaml(uniqueIndex("[a]")), db, "same").first shouldBe 0
    }

    test("neuer eindeutiger Index ist rein additiv und endet ohne Drift") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-new")
        val db = databaseFrom(dir, yaml())
        val after = yaml(uniqueIndex("[a]"))

        val (ops, script) = plan(dir, after, db, "plan")
        ops shouldBe 1
        script shouldContain "CREATE UNIQUE INDEX"
        script shouldNotContain "DROP TABLE"
        execute(dir, after, db, "up").first shouldBe 0
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_t'") shouldContain "UNIQUE"
        plan(dir, after, db, "again").first shouldBe 0
    }

    test("Unique an einer Indexspalte entfernen: der Index bleibt, ohne UNIQUE") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-removed")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]")))
        val after = yaml(indices("- name: ix_t", "  columns: [a]"))

        plan(dir, after, db, "plan").first shouldBe 2
        execute(dir, after, db, "up").first shouldBe 0
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_t'") shouldNotContain "UNIQUE"
        exec(db, "INSERT INTO t (id, a, b, c) VALUES (2, 'x', 'y', 'z')")
        plan(dir, after, db, "again").first shouldBe 0
    }

    test("Eindeutigkeit an einem bestehenden Index nachtragen") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-added")
        val db = databaseFrom(dir, yaml(indices("- name: ix_t", "  columns: [a]")))
        val after = yaml(uniqueIndex("[a]"))

        plan(dir, after, db, "plan").first shouldBe 2
        execute(dir, after, db, "up").first shouldBe 0
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_t'") shouldContain "UNIQUE"
        plan(dir, after, db, "again").first shouldBe 0
    }

    test("andere Spalten am eindeutigen Index bleiben eine Aenderung") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-columns")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]")))
        val after = yaml(uniqueIndex("[b]"))

        plan(dir, after, db, "plan").first shouldBe 2
        execute(dir, after, db, "up").first shouldBe 0
        scalar(db, "SELECT count(*) FROM pragma_index_info('ix_t') WHERE name = 'b'") shouldBe "1"
        plan(dir, after, db, "again").first shouldBe 0
    }

    test("eindeutigen Index streichen bleibt eine Aenderung") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-dropped")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]")))

        plan(dir, yaml(), db, "plan").first shouldBe 1
        execute(dir, yaml(), db, "up").first shouldBe 0
        scalar(db, "SELECT count(*) FROM sqlite_master WHERE type = 'index'") shouldBe "0"
    }

    test("eindeutiger Index auf einer Tabelle, die im selben Lauf neu gebaut wird, entsteht einmal") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-rebuild")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]")))
        val after = yaml(uniqueIndex("[a]"), cRequired = false)

        val (code, _) = execute(dir, after, db, "up")
        code shouldBe 0
        scalar(db, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'ix_t'") shouldBe "1"
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_t'") shouldContain "UNIQUE"
        plan(dir, after, db, "again").first shouldBe 0
    }

    // Bewusste Verhaltensaenderung (ADR 0062): Index und Constraint sind in SQLite verschiedene Objekte.
    test("UNIQUE-Constraint in der Datei gegen eindeutigen Index in der Datenbank ist eine Aenderung") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-vs-constraint")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]")))

        plan(dir, yaml(uniqueConstraint("uq_t", "[a]")), db, "plan").first shouldBe 2
    }

    test("eindeutiger Index in der Datei gegen UNIQUE-Constraint in der Datenbank ist eine Aenderung") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-vs-constraint-rev")
        val db = databaseFrom(dir, yaml(uniqueConstraint("uq_t", "[a]")))

        plan(dir, yaml(uniqueIndex("[a]")), db, "plan").first shouldBe 2
    }

    // Grenze (ADR 0062): eine Datei aus einem frueheren `schema reverse` einer Datenbank mit
    // `CREATE UNIQUE INDEX` traegt `unique: true` an der Spalte; derselbe Stand liest jetzt als Index.
    test("Bestandsdatei mit unique an der Spalte gegen eindeutigen Index in der Datenbank ist eine Aenderung") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-legacy")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]")))

        plan(dir, yaml(colUnique = true), db, "plan").first shouldBe 2
    }

    for (columns in listOf("[a]", "[a, b]")) {
        test("Round-Trip: reverse, generate, neue Datenbank, Vergleich mit der Ausgangsdatei ($columns)") {
            val dir = Files.createTempDirectory("cli-migrate-unique-index-roundtrip")
            val text = yaml(uniqueIndex(columns))
            val db = databaseFrom(dir, text)
            val reversed = dir.resolve("reversed.yaml")
            run("schema", "reverse", "--source", "sqlite://$db", "--output", reversed.toString()) shouldBe 0
            Files.readString(reversed) shouldContain "ix_t"
            val create = dir.resolve("again.sql")
            run(
                "schema", "generate", "--source", reversed.toString(), "--target", "sqlite", "--output", create.toString(),
            ) shouldBe 0
            val second = dir.resolve("second.sqlite")
            exec(second, *sqlStatements(create).toTypedArray())

            plan(dir, text, second, "plan").first shouldBe 0
        }
    }

    test("schema rollback stellt den Stand mit eindeutigem Index wieder her") {
        val dir = Files.createTempDirectory("cli-migrate-unique-index-rollback")
        val before = yaml(uniqueIndex("[a, b]"))
        val after = yaml(uniqueIndex("[a, b]"), withD = true)
        val db = databaseFrom(dir, before)
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")

        run(
            "schema", "migrate", "--source", write(dir, "after.yaml", after).toString(), "--target", "db:sqlite://$db",
            "--generate-rollback", "--output", up.toString(), "--rollback-output", down.toString(),
            "--report", dir.resolve("plan.json").toString(),
        ) shouldBe 0
        exec(db, *sqlStatements(up).toTypedArray())
        run(
            "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
            "--execute", "--allow-destructive",
        ) shouldBe 0

        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_t'") shouldContain "UNIQUE"
        plan(dir, before, db, "after-rollback").first shouldBe 0
    }
})
