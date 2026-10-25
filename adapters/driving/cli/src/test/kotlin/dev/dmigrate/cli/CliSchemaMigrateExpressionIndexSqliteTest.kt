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
 * Ein Ausdrucks-Index, den Datei und SQLite-Datenbank gleich ausdruecken, erzeugt
 * keine Operation (GitHub-Issue #55) — durch die echte CLI und eine echte Datei.
 * Der Generator klammert jeden Ausdruck (`((lower(a)))`); der Reader gibt den
 * Schluessel ohne diese eine Klammer zurueck.
 */
class CliSchemaMigrateExpressionIndexSqliteTest : FunSpec({

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

    fun yaml(extra: String = "", withD: Boolean = false): String {
        val d = if (withD) "      d: { type: text }\n" else ""
        return "schema_format: \"1.0\"\nname: \"X\"\nversion: \"1.0\"\ntables:\n  t:\n    columns:\n" +
            "      id: { type: integer, required: true }\n      a: { type: text, required: true }\n" +
            "      b: { type: text, required: true }\n$d    primary_key: [id]\n$extra"
    }

    fun index(columns: String, unique: Boolean = false, where: String? = null): String =
        "    indices:\n      - name: ix_t\n" + (if (unique) "        unique: true\n" else "") +
            "        columns: $columns\n" + (where?.let { "        where: \"$it\"\n" } ?: "")

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

    fun generated(dir: Path, text: String): Path {
        val create = dir.resolve("create.sql")
        run(
            "schema", "generate", "--source", write(dir, "a.yaml", text).toString(),
            "--target", "sqlite", "--output", create.toString(),
        ) shouldBe 0
        return dir.resolve("db.sqlite").also { exec(it, *sqlStatements(create).toTypedArray()) }
    }

    fun handwritten(dir: Path, indexSql: String): Path {
        val db = dir.resolve("db.sqlite")
        exec(db, "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT NOT NULL, b TEXT NOT NULL)", indexSql)
        return db
    }

    fun operations(dir: Path, source: String, db: Path, name: String): Int {
        val report = dir.resolve("$name.json")
        run(
            "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--output", dir.resolve("$name.sql").toString(),
            "--report", report.toString(),
        ) shouldBe 0
        return Regex("\"operationsTotal\":(\\d+)").find(Files.readString(report))!!.groupValues[1].toInt()
    }

    fun execute(dir: Path, source: String, db: Path, name: String): Int =
        run(
            "schema", "migrate", "--source", write(dir, "$name.yaml", source).toString(),
            "--target", "db:sqlite://$db", "--execute", "--report", dir.resolve("$name.json").toString(),
        )

    // Datei, die der Generator anlegt und der Reader zurueckgeben muss. Fehlerzeilen des Plans:
    // eine Klammer, UNIQUE mit Ausdruck und Spalte, Ausdruck mit DESC; der Rest sind Kontrollzeilen.
    val cases = linkedMapOf(
        "Funktionsaufruf" to index("[{ expression: \"lower(a)\" }]"),
        "eindeutig, Ausdruck und Spalte" to index("[{ expression: \"lower(a)\" }, { name: b }]", unique = true),
        "Spalte und Ausdruck absteigend" to
            index("[{ name: a }, { expression: \"(b + 1)\", direction: desc }]"),
        "zwei Klammerteile" to index("[{ expression: \"(a) || (b)\" }]"),
        "Klammer vor dem Rest" to index("[{ expression: \"(a || b) || 'x'\" }]"),
        "Klammer in der Zeichenkette" to index("[{ expression: \"'(' || a\" }]"),
        "mehrere Ausdruecke" to index("[{ expression: \"lower(a)\" }, { expression: \"upper(b)\" }]"),
        "Reihenfolge Ausdruck, Spalte, Ausdruck" to
            index("[{ expression: \"lower(a)\" }, { name: b }, { expression: \"upper(a)\" }]"),
        "Praedikat" to index("[{ expression: \"lower(a)\" }]", where = "b IS NOT NULL"),
    )

    for ((label, extra) in cases) {
        test("$label: keine Operation, additive Aenderung mit --execute Exit 0, zweiter Lauf 0") {
            val dir = Files.createTempDirectory("cli-migrate-expr-index")
            val db = generated(dir, yaml(extra))

            operations(dir, yaml(extra), db, "same") shouldBe 0
            execute(dir, yaml(extra, withD = true), db, "up") shouldBe 0
            scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'd'") shouldBe "1"
            operations(dir, yaml(extra, withD = true), db, "again") shouldBe 0
        }

        test("$label: Rundlauf reverse, generate, reverse ist stabil") {
            val dir = Files.createTempDirectory("cli-migrate-expr-index-roundtrip")
            val db = generated(dir, yaml(extra))
            val first = dir.resolve("first.yaml")
            run("schema", "reverse", "--source", "sqlite://$db", "--output", first.toString()) shouldBe 0
            val create = dir.resolve("again.sql")
            run(
                "schema", "generate", "--source", first.toString(), "--target", "sqlite", "--output",
                create.toString(),
            ) shouldBe 0
            val second = dir.resolve("second.sqlite")
            exec(second, *sqlStatements(create).toTypedArray())
            val again = dir.resolve("second.yaml")
            run("schema", "reverse", "--source", "sqlite://$second", "--output", again.toString()) shouldBe 0

            Files.readString(again).substringAfter("indices:") shouldBe Files.readString(first).substringAfter("indices:")
            operations(dir, yaml(extra), second, "plan") shouldBe 0
        }
    }

    // Von Hand angelegt: der Katalog traegt den Schluessel so, wie er geschrieben wurde.
    val handwrittenCases = linkedMapOf(
        "ohne zusaetzliche Klammer" to ("lower(a)" to "lower(a)"),
        "mit Klammer um den Aufruf" to ("(lower(a))" to "lower(a)"),
        "zwei Klammerteile" to ("(a) || (b)" to "(a) || (b)"),
        "Klammer um das Ganze und Rest" to ("(a + 1) * 2" to "(a + 1) * 2"),
        "Klammer in der Zeichenkette" to ("'(' || a" to "'(' || a"),
        "Ausdruck mit COLLATE" to ("lower(a) COLLATE NOCASE" to "lower(a)"),
    )

    for ((label, pair) in handwrittenCases) {
        test("von Hand, $label: keine Operation") {
            val (key, expression) = pair
            val dir = Files.createTempDirectory("cli-migrate-expr-index-hand")
            val db = handwritten(dir, "CREATE INDEX ix_t ON t ($key)")
            val text = yaml(index("[{ expression: \"${expression.replace("\"", "\\\"")}\" }]"))

            operations(dir, text, db, "same") shouldBe 0
        }
    }

    // Der Katalog meldet `((a))` als Spalte `a` (`index_xinfo`), nicht als Ausdruck.
    test("von Hand, geklammerte Spalte: gleich der Spalte, keine Operation") {
        val dir = Files.createTempDirectory("cli-migrate-expr-index-column")
        val db = handwritten(dir, "CREATE INDEX ix_t ON t ((a))")

        operations(dir, yaml(index("[{ name: a }]")), db, "same") shouldBe 0
    }

    test("lower(a) zu upper(a) bleibt eine Aenderung") {
        val dir = Files.createTempDirectory("cli-migrate-expr-index-changed")
        val db = generated(dir, yaml(index("[{ expression: \"lower(a)\" }]")))
        val after = yaml(index("[{ expression: \"upper(a)\" }]"))

        operations(dir, after, db, "plan") shouldBe 2
        execute(dir, after, db, "up") shouldBe 0
        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_t'") shouldContain "upper(a)"
        operations(dir, after, db, "again") shouldBe 0
    }

    test("Klammern im Ausdruck bleiben erhalten: (a) || (b) zu (a) || (a) ist eine Aenderung") {
        val dir = Files.createTempDirectory("cli-migrate-expr-index-inner")
        val db = generated(dir, yaml(index("[{ expression: \"(a) || (b)\" }]")))

        operations(dir, yaml(index("[{ expression: \"(a) || (a)\" }]")), db, "plan") shouldBe 2
    }

    test("schema rollback stellt den Stand mit Ausdrucks-Index wieder her") {
        val dir = Files.createTempDirectory("cli-migrate-expr-index-rollback")
        val before = yaml(index("[{ expression: \"lower(a)\" }, { name: b }]", unique = true))
        val after = yaml(index("[{ expression: \"lower(a)\" }, { name: b }]", unique = true), withD = true)
        val db = generated(dir, before)
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

        scalar(db, "SELECT sql FROM sqlite_master WHERE name = 'ix_t'") shouldContain "lower(a)"
        operations(dir, before, db, "after-rollback") shouldBe 0
    }
})
