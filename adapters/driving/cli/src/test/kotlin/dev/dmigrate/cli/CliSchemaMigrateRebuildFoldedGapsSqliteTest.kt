package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.sqlite.SqliteDriver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Luecken aus dem Review zu Issue #54, Ursache 2 (siehe `CliSchemaMigrateRebuildFoldedColumnsSqliteTest`):
 * Domain-Spalten, Laengenwechsel `text(32)` <-> `text(64)` von Datei zu Datei und die Verengung einer
 * Fremddatenbank mit einem Wert ueber der Laenge.
 */
class CliSchemaMigrateRebuildFoldedGapsSqliteTest : FunSpec({

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

    /** Tabelle `t(id, flag, <columns>)`; [customTypes] ist der fertige `custom_types:`-Block oder leer. */
    fun yaml(flagRequired: Boolean, columns: Map<String, String>, customTypes: String = ""): String {
        val rows = columns.entries.joinToString("\n") { "      ${it.key}: ${it.value}" }
        return listOf(
            "schema_format: \"1.0\"",
            "name: \"Folded\"",
            "version: \"1.0\"",
            customTypes,
            "tables:",
            "  t:",
            "    columns:",
            "      id: { type: integer, required: true }",
            "      flag: { type: integer, required: $flagRequired }",
            rows,
            "    primary_key: [id]",
        ).filter { it.isNotEmpty() }.joinToString("\n") + "\n"
    }

    fun domain(base: String) = "custom_types:\n  pos: { kind: domain, base_type: $base }"

    val domainColumn = mapOf("n" to "{ type: enum, ref_type: pos }")

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

    /** Datei gegen Datei: nur Plan, `--dialect sqlite`. */
    fun migrateFiles(dir: Path, source: Path, target: Path, name: String): Int = run(
        "schema", "migrate", "--source", source.toString(), "--target", "file:$target", "--dialect", "sqlite",
        "--output", dir.resolve("$name.sql").toString(), "--report", dir.resolve("$name.json").toString(),
    )

    fun report(dir: Path, name: String): String = Files.readString(dir.resolve("$name.json"))

    fun operations(dir: Path, name: String): Int =
        Regex("\"operationsTotal\":(\\d+)").find(report(dir, name))!!.groupValues[1].toInt()

    // ── Domain-Spalten ──

    test("Domain ueber varchar: Neubau aus anderem Grund laeuft durch, die Daten bleiben, kein Folge-Drift") {
        val dir = Files.createTempDirectory("cli-folded-domain-varchar")
        val before = write(dir, "a.yaml", yaml(false, domainColumn, domain("varchar")))
        val after = write(dir, "b.yaml", yaml(true, domainColumn, domain("varchar")))
        val db = databaseFromFile(dir, before)
        exec(db, "INSERT INTO t (id, flag, n) VALUES (1, 7, 'abc')")

        val code = migrate(dir, after, db, "up", "--execute")

        withClue(report(dir, "up")) { code shouldBe 0 }
        report(dir, "up") shouldNotContain "SQLITE_CAST_NOT_WHITELISTED"
        scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'flag' AND \"notnull\" = 1") shouldBe "1"
        scalar(db, "SELECT n FROM t") shouldBe "abc"
        // Nicht `operationsTotal` 0: die Datenbank fuehrt kein Typ-Objekt, `CreateCustomType` steht bei jedem Lauf im Plan
        // (SQLITE_CUSTOM_TYPE_AT_COLUMN, rendert nichts). Entscheidend: kein Neubau, kein Blocker.
        migrate(dir, after, db, "again", "--execute") shouldBe 0
        report(dir, "again") shouldNotContain "SQLITE_CAST_NOT_WHITELISTED"
        scalar(db, "SELECT n FROM t") shouldBe "abc"
    }

    test("Domain ueber varchar(32) von Datei zu Datei: Neubau aus anderem Grund laeuft ohne Blocker durch") {
        val dir = Files.createTempDirectory("cli-folded-domain-varchar-file")
        val before = write(dir, "a.yaml", yaml(false, domainColumn, domain("varchar(32)")))
        val after = write(dir, "b.yaml", yaml(true, domainColumn, domain("varchar(32)")))

        migrateFiles(dir, after, before, "plan") shouldBe 0

        report(dir, "plan") shouldNotContain "SQLITE_CAST_NOT_WHITELISTED"
        Files.readString(dir.resolve("plan.sql")) shouldContain "INSERT INTO"
    }

    // Regressionstest: die Datenbank liest `n` als `Text()` ohne Laenge, die aufgeloeste Domain traegt `Text(32)`. Die
    // Domain-Pruefung im Neubau (domainColumns) gilt nur, wenn BEIDE Seiten die Domain tragen (Datei gegen Datei); gegen
    // eine Datenbank wuerde sie hier eine Verengung ("target length is shorter than source length") ohne Typaenderung
    // melden und jeden Neubau einer Tabelle mit einer Domain mit Laenge blockieren.
    test("Domain ueber varchar(32) gegen eine Datenbank: der Neubau laeuft durch, die Daten bleiben") {
        val dir = Files.createTempDirectory("cli-folded-domain-varchar-length")
        val before = write(dir, "a.yaml", yaml(false, domainColumn, domain("varchar(32)")))
        val after = write(dir, "b.yaml", yaml(true, domainColumn, domain("varchar(32)")))
        val db = databaseFromFile(dir, before)
        exec(db, "INSERT INTO t (id, flag, n) VALUES (1, 7, 'abc')")

        val code = migrate(dir, after, db, "up", "--execute")

        withClue(report(dir, "up")) { code shouldBe 0 }
        report(dir, "up") shouldNotContain "SQLITE_CAST_NOT_WHITELISTED"
        scalar(db, "SELECT n FROM t") shouldBe "abc"
        scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'flag' AND \"notnull\" = 1") shouldBe "1"
    }

    // Regressionstest zu SqliteRefTypeColumns.domainColumns: der Vergleich meldet den Basistypwechsel einer
    // Domain als AlterCustomType, nicht als AlterColumnType; ohne die Pruefung im Neubau fiele er ohne CAST durch.
    test("Domain: Basistypwechsel text -> integer von Datei zu Datei blockiert an der Cast-Matrix") {
        val dir = Files.createTempDirectory("cli-folded-domain-file")
        val before = write(dir, "a.yaml", yaml(false, domainColumn, domain("text")))
        val after = write(dir, "b.yaml", yaml(true, domainColumn, domain("integer")))

        migrateFiles(dir, after, before, "plan") shouldBe 8

        val text = report(dir, "plan")
        text shouldContain "SQLITE_CAST_NOT_WHITELISTED"
        text shouldContain "cannot CAST column `n`"
    }

    test("Domain: Basistypwechsel text -> integer gegen eine Datenbank mit Text-Wert blockiert, die Daten bleiben") {
        val dir = Files.createTempDirectory("cli-folded-domain-db")
        val before = write(dir, "a.yaml", yaml(false, domainColumn, domain("text")))
        val after = write(dir, "b.yaml", yaml(true, domainColumn, domain("integer")))
        val db = databaseFromFile(dir, before)
        exec(db, "INSERT INTO t (id, flag, n) VALUES (1, 7, 'abc')")
        val catalogBefore = catalog(db)

        migrate(dir, after, db, "up", "--execute") shouldBe 8

        report(dir, "up") shouldContain "SQLITE_CAST_NOT_WHITELISTED"
        catalog(db) shouldBe catalogBefore
        scalar(db, "SELECT n FROM t") shouldBe "abc"
    }

    // Bekannte Grenze, Folge-Slice docs/planning/next/sqlite-domain-spalte-basistyp-vergleich.md: die Datenbank liest
    // die Domain ueber boolean als `integer`, die Datei traegt `ref_type`; der Vergleich plant bei jedem Lauf ein
    // AlterColumnType, und die Cast-Matrix blockiert den Neubau -- auch ohne jede Aenderung am Typ. Ist der Folge-Slice
    // umgesetzt, wird dieser Test zu Exit 0 mit erhaltenen Daten.
    test("Domain ueber boolean: der Neubau blockiert wegen des Dauer-AlterColumnType (bekannte Grenze)") {
        val dir = Files.createTempDirectory("cli-folded-domain-boolean")
        val before = write(dir, "a.yaml", yaml(false, domainColumn, domain("boolean")))
        val after = write(dir, "b.yaml", yaml(true, domainColumn, domain("boolean")))
        val db = databaseFromFile(dir, before)
        exec(db, "INSERT INTO t (id, flag, n) VALUES (1, 7, 1)")
        val catalogBefore = catalog(db)

        migrate(dir, after, db, "up", "--execute") shouldBe 8

        report(dir, "up") shouldContain "SQLITE_CAST_NOT_WHITELISTED"
        catalog(db) shouldBe catalogBefore
    }

    // ── Laengenwechsel von Datei zu Datei, beide Richtungen ──

    for ((from, to) in listOf(32 to 64, 64 to 32)) {
        val before = mapOf("x" to "{ type: text, max_length: $from }")
        val after = mapOf("x" to "{ type: text, max_length: $to }")

        test("text($from) -> text($to) von Datei zu Datei ohne anderen Neubaugrund: Exit 0, keine Operation") {
            val dir = Files.createTempDirectory("cli-folded-length-plain")
            val old = write(dir, "a.yaml", yaml(false, before))
            val new = write(dir, "b.yaml", yaml(false, after))

            migrateFiles(dir, new, old, "plan") shouldBe 0

            operations(dir, "plan") shouldBe 0
        }

        test("text($from) -> text($to) von Datei zu Datei mit Neubau aus anderem Grund: Exit 0, ohne Blocker") {
            val dir = Files.createTempDirectory("cli-folded-length-rebuild")
            val old = write(dir, "a.yaml", yaml(false, before))
            val new = write(dir, "b.yaml", yaml(true, after))

            migrateFiles(dir, new, old, "plan") shouldBe 0

            report(dir, "plan") shouldNotContain "SQLITE_CAST_NOT_WHITELISTED"
            Files.readString(dir.resolve("plan.sql")) shouldContain "INSERT INTO"
        }
    }

    // ── Fremddatenbank: Verengung ohne Datenverlust ──

    test("VARCHAR(64) nach text(32) gegen eine fremd angelegte Datenbank mit einem Wert ueber 32 Zeichen: nichts geht verloren") {
        val dir = Files.createTempDirectory("cli-folded-rebuild-foreign-long")
        val db = dir.resolve("foreign.sqlite")
        val long = "x".repeat(50)
        exec(
            db,
            "CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY, flag INTEGER, v VARCHAR(64))",
            "INSERT INTO t VALUES (1, 3, '$long')",
        )
        val after = write(dir, "b.yaml", yaml(true, mapOf("v" to "{ type: text, max_length: 32 }")))

        migrate(dir, after, db, "up", "--execute") shouldBe 0

        scalar(db, "SELECT v FROM t") shouldBe long
        scalar(db, "SELECT length(v) FROM t") shouldBe "50"
        migrate(dir, after, db, "again", "--execute") shouldBe 0
        operations(dir, "again") shouldBe 0
    }
})
