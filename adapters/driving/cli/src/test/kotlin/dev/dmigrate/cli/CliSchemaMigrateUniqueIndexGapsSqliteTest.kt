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
import java.sql.SQLException

/**
 * Luecken aus dem Review zu Issue #53 (siehe `CliSchemaMigrateUniqueIndexSqliteTest`): `--execute`
 * mit weiterer additiver Aenderung fuer die uebrigen Zeilen, die dokumentierte Grenze fuer
 * Bestandsdateien (Exit 8, Datenbank unveraendert), der Ausdrucks-Index, `schema rollback` fuer
 * Ein-Spalten- und Primaerschluesselindex und die weiter erzwungene Eindeutigkeit.
 */
class CliSchemaMigrateUniqueIndexGapsSqliteTest : FunSpec({

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

    fun yaml(extra: String = "", colUnique: Boolean = false, withD: Boolean = false): String {
        val a = if (colUnique) "{ type: text, required: true, unique: true }" else "{ type: text, required: true }"
        val d = if (withD) "      d: { type: text }\n" else ""
        return "schema_format: \"1.0\"\nname: \"U\"\nversion: \"1.0\"\ntables:\n  t:\n    columns:\n" +
            "      id: { type: integer, required: true }\n      a: $a\n      b: { type: text, required: true }\n" +
            "      c: { type: text, required: true }\n$d    primary_key: [id]\n$extra"
    }

    fun fkYaml(extra: String, withD: Boolean): String {
        val d = if (withD) "      d: { type: text }\n" else ""
        return "schema_format: \"1.0\"\nname: \"U\"\nversion: \"1.0\"\ntables:\n  p:\n    columns:\n" +
            "      id: { type: integer, required: true }\n    primary_key: [id]\n  t:\n    columns:\n" +
            "      id: { type: integer, required: true }\n      a: { type: integer, required: true }\n" +
            "      b: { type: text, required: true }\n      c: { type: text, required: true }\n$d" +
            "    primary_key: [id]\n    constraints:\n      - name: fk_t_a\n        type: foreign_key\n" +
            "        columns: [a]\n        references: { table: p, columns: [id] }\n$extra"
    }

    fun indices(vararg lines: String) = "    indices:\n" + lines.joinToString("") { "      $it\n" }

    fun uniqueIndex(columns: String) = indices("- name: ix_t", "  unique: true", "  columns: $columns")

    fun exec(db: Path, vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
        }
    }

    fun rejects(db: Path, statement: String): Boolean = try {
        exec(db, statement)
        false
    } catch (_: SQLException) {
        true
    }

    fun scalar(db: Path, query: String): String =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> st.executeQuery(query).use { rs -> rs.next(); rs.getString(1) } }
        }

    fun catalog(db: Path): String =
        scalar(db, "SELECT group_concat(type || ':' || name || ':' || coalesce(sql, ''), char(10)) FROM sqlite_master")

    fun sqlStatements(sql: Path): List<String> =
        Files.readString(sql).lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun write(dir: Path, name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text) }

    /** Legt die Datenbank aus [text] an und fuegt eine Zeile ein (`a` = [aValue] als SQL-Literal). */
    fun databaseFrom(dir: Path, text: String, aValue: String = "'x'"): Path {
        val create = dir.resolve("create.sql")
        run(
            "schema", "generate", "--source", write(dir, "a.yaml", text).toString(),
            "--target", "sqlite", "--output", create.toString(),
        ) shouldBe 0
        val db = dir.resolve("db.sqlite")
        exec(db, *sqlStatements(create).toTypedArray())
        exec(db, "INSERT INTO t (id, a, b, c) VALUES (1, $aValue, 'y', 'z')")
        return db
    }

    fun plan(dir: Path, source: String, db: Path, name: String): Pair<Int, String> {
        val up = dir.resolve("$name.sql")
        val report = dir.resolve("$name.json")
        run(
            "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--output", up.toString(), "--report", report.toString(),
        ) shouldBe 0
        val ops = Regex("\"operationsTotal\":(\\d+)").find(Files.readString(report))!!.groupValues[1].toInt()
        return ops to (if (Files.exists(up)) Files.readString(up) else "")
    }

    fun execute(dir: Path, source: String, db: Path, name: String): Pair<Int, String> {
        val report = dir.resolve("$name.json")
        val code = run(
            "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--execute", "--report", report.toString(),
        )
        return code to Files.readString(report)
    }

    fun hasColumnD(db: Path) = scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'd'")

    // ── --execute mit weiterer additiver Aenderung fuer die uebrigen Zeilen der Tabelle im Slice ──

    test("Fremdschluesselspalte mit eindeutigem Index: --execute mit neuer Spalte Exit 0, zweiter Lauf 0 Operationen") {
        val dir = Files.createTempDirectory("cli-unique-index-gap-fk")
        val extra = uniqueIndex("[a]")
        val db = databaseFrom(dir, fkYaml(extra, withD = false), aValue = "1")

        val (code, report) = execute(dir, fkYaml(extra, withD = true), db, "up")

        report shouldNotContain "TRANSACTION_SCOPE_UNSUPPORTED"
        code shouldBe 0
        hasColumnD(db) shouldBe "1"
        plan(dir, fkYaml(extra, withD = true), db, "again").first shouldBe 0
    }

    test("WITHOUT ROWID mit eindeutigem Index: --execute mit neuer Spalte Exit 0, zweiter Lauf 0 Operationen") {
        val dir = Files.createTempDirectory("cli-unique-index-gap-wr")
        val db = dir.resolve("db.sqlite")
        exec(
            db,
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT NOT NULL, b TEXT NOT NULL, c TEXT NOT NULL) WITHOUT ROWID",
            "CREATE UNIQUE INDEX ix_t ON t (a)",
        )
        val after = yaml(uniqueIndex("[a]"), withD = true)

        val (code, report) = execute(dir, after, db, "up")

        report shouldNotContain "TRANSACTION_SCOPE_UNSUPPORTED"
        code shouldBe 0
        hasColumnD(db) shouldBe "1"
        plan(dir, after, db, "again").first shouldBe 0
    }

    test("Spalte unique plus Index auf derselben Spalte: --execute mit neuer Spalte Exit 0, zweiter Lauf 0 Operationen") {
        val dir = Files.createTempDirectory("cli-unique-index-gap-both")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]"), colUnique = true))
        val after = yaml(uniqueIndex("[a]"), colUnique = true, withD = true)

        val (code, report) = execute(dir, after, db, "up")

        report shouldNotContain "TRANSACTION_SCOPE_UNSUPPORTED"
        code shouldBe 0
        hasColumnD(db) shouldBe "1"
        plan(dir, after, db, "again").first shouldBe 0
    }

    test("Spalte unique ohne Index: --execute mit neuer Spalte Exit 0, zweiter Lauf 0 Operationen") {
        val dir = Files.createTempDirectory("cli-unique-index-gap-column")
        val db = databaseFrom(dir, yaml(colUnique = true))
        val after = yaml(colUnique = true, withD = true)

        val (code, report) = execute(dir, after, db, "up")

        report shouldNotContain "TRANSACTION_SCOPE_UNSUPPORTED"
        code shouldBe 0
        hasColumnD(db) shouldBe "1"
        plan(dir, after, db, "again").first shouldBe 0
    }

    // ── Eindeutigkeit bleibt erzwungen ──

    test("Ein-Spalten-Index: nach --execute mit neuer Spalte lehnt die Datenbank ein Duplikat weiter ab") {
        val dir = Files.createTempDirectory("cli-unique-index-gap-enforce")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]")))

        execute(dir, yaml(uniqueIndex("[a]"), withD = true), db, "up").first shouldBe 0

        rejects(db, "INSERT INTO t (id, a, b, c) VALUES (2, 'x', 'y', 'z')") shouldBe true
        rejects(db, "INSERT INTO t (id, a, b, c) VALUES (3, 'other', 'y', 'z')") shouldBe false
    }

    test("neu angelegter Ein-Spalten-Index erzwingt die Eindeutigkeit") {
        val dir = Files.createTempDirectory("cli-unique-index-gap-enforce-new")
        val db = databaseFrom(dir, yaml())

        execute(dir, yaml(uniqueIndex("[a]")), db, "up").first shouldBe 0

        rejects(db, "INSERT INTO t (id, a, b, c) VALUES (2, 'x', 'y', 'z')") shouldBe true
    }

    // ── Bestandsdateien (ADR 0062): Index und Constraint sind verschiedene Objekte, der Wechsel ist ein Neubau ──

    fun hasIndex(db: Path) = scalar(db, "SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name = 'ix_t'")

    test("Bestandsdatei (unique an der Spalte) gegen eindeutigen Index: --execute mit weiterer Aenderung Exit 0") {
        val dir = Files.createTempDirectory("cli-unique-index-gap-legacy")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]")))
        val after = yaml(colUnique = true, withD = true)

        val (code, report) = execute(dir, after, db, "up")

        report shouldNotContain "TRANSACTION_SCOPE_UNSUPPORTED"
        code shouldBe 0
        hasColumnD(db) shouldBe "1"
        hasIndex(db) shouldBe "0"
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 't'") shouldContain "UNIQUE"
        rejects(db, "INSERT INTO t (id, a, b, c) VALUES (2, 'x', 'y', 'z')") shouldBe true
        plan(dir, after, db, "again").first shouldBe 0
    }

    test("Bestandsdatei (unique an der Spalte) gegen eindeutigen Index: --execute ohne weitere Aenderung Exit 0") {
        val dir = Files.createTempDirectory("cli-unique-index-gap-legacy-alone")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]")))
        val after = yaml(colUnique = true)

        val (code, report) = execute(dir, after, db, "up")

        report shouldNotContain "TRANSACTION_SCOPE_UNSUPPORTED"
        code shouldBe 0
        hasIndex(db) shouldBe "0"
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 't'") shouldContain "UNIQUE"
        rejects(db, "INSERT INTO t (id, a, b, c) VALUES (2, 'x', 'y', 'z')") shouldBe true
        plan(dir, after, db, "again").first shouldBe 0
    }

    test("schema rollback stellt nach dem Neubau (Bestandsdatei) den eindeutigen Index wieder her") {
        val dir = Files.createTempDirectory("cli-unique-index-gap-legacy-rollback")
        val db = databaseFrom(dir, yaml(uniqueIndex("[a]")))
        val before = catalog(db)
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")

        run(
            "schema", "migrate", "--source", write(dir, "after.yaml", yaml(colUnique = true)).toString(),
            "--target", "db:sqlite://$db", "--generate-rollback", "--output", up.toString(),
            "--rollback-output", down.toString(), "--report", dir.resolve("plan.json").toString(),
        ) shouldBe 0
        exec(db, *sqlStatements(up).toTypedArray())
        hasIndex(db) shouldBe "0"
        run(
            "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
            "--execute", "--allow-destructive",
        ) shouldBe 0

        catalog(db) shouldBe before
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_t'") shouldContain "UNIQUE"
        plan(dir, yaml(uniqueIndex("[a]")), db, "after-rollback").first shouldBe 0
    }

    // ── Ausdrucks-Index ──

    // Der Generator klammert den Ausdruck (`((lower(a)))`), der Reader gibt `lower(a)` zurueck (Issue #55,
    // `CliSchemaMigrateExpressionIndexSqliteTest`): keine Operation, und die Eindeutigkeit wird weiter erzwungen.
    test("eindeutiger Ausdrucks-Index: Text (lower(a)) gegen lower(a) plant keine Operation") {
        val dir = Files.createTempDirectory("cli-unique-index-gap-expression")
        val text = yaml(uniqueIndex("[{ expression: \"lower(a)\" }]"))
        val db = databaseFrom(dir, text)
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_t'") shouldContain "(lower(a))"

        plan(dir, text, db, "plan").first shouldBe 0
        execute(dir, text, db, "up").first shouldBe 0
        plan(dir, text, db, "again").first shouldBe 0
        rejects(db, "INSERT INTO t (id, a, b, c) VALUES (2, 'X', 'y', 'z')") shouldBe true
    }

    // ── schema rollback ──

    for (columns in listOf("[a]", "[id]")) {
        test("schema rollback stellt den Stand mit eindeutigem Index auf $columns wieder her") {
            val dir = Files.createTempDirectory("cli-unique-index-gap-rollback")
            val before = yaml(uniqueIndex(columns))
            val after = yaml(uniqueIndex(columns), withD = true)
            val db = databaseFrom(dir, before)
            val up = dir.resolve("up.sql")
            val down = dir.resolve("down.sql")

            run(
                "schema", "migrate", "--source", write(dir, "after.yaml", after).toString(), "--target", "db:sqlite://$db",
                "--generate-rollback", "--output", up.toString(), "--rollback-output", down.toString(),
                "--report", dir.resolve("plan.json").toString(),
            ) shouldBe 0
            exec(db, *sqlStatements(up).toTypedArray())
            hasColumnD(db) shouldBe "1"
            run(
                "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
                "--execute", "--allow-destructive",
            ) shouldBe 0

            hasColumnD(db) shouldBe "0"
            scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_t'") shouldContain "UNIQUE"
            plan(dir, before, db, "after-rollback").first shouldBe 0
        }
    }
})
