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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Issue #56: eine Domain-Spalte, die Datei und SQLite-Datenbank gleich ausdruecken, erzeugt keine Aenderung -- gleich
 * ueber welchen Basistyp. Die Datenbank fuehrt die Domain nicht als Typ-Objekt, der Reader liest den Basistyp
 * (`Integer`, `Float`, `Binary`, `Text`); der Vergleich muss die Domain-Spalte deshalb ueber ihren Basistyp projizieren.
 *
 * `CreateCustomType` steht bei jedem Lauf im Plan (`SQLITE_CUSTOM_TYPE_AT_COLUMN`, rendert nichts); gemessen wird die
 * Abwesenheit von `AlterColumnType`.
 */
class CliSchemaMigrateDomainColumnSqliteTest : FunSpec({

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

    fun yaml(flagRequired: Boolean, types: String): String = """
        schema_format: "1.0"
        name: "DomainColumn"
        version: "1.0"
        custom_types:
        $types
        tables:
          t:
            columns:
              id: { type: integer, required: true }
              flag: { type: integer, required: $flagRequired }
              n: { type: enum, ref_type: d }
            primary_key: [id]
    """.trimIndent() + "\n"

    fun domain(base: String) = "  d: { kind: domain, base_type: \"$base\" }"
    val decimalDomain = "  d: { kind: domain, base_type: decimal, precision: 10, scale: 2 }"

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

    fun migrateFiles(dir: Path, source: Path, target: Path, name: String): Int = run(
        "schema", "migrate", "--source", source.toString(), "--target", "file:$target", "--dialect", "sqlite",
        "--output", dir.resolve("$name.sql").toString(), "--report", dir.resolve("$name.json").toString(),
    )

    fun report(dir: Path, name: String): String = Files.readString(dir.resolve("$name.json"))

    /** Die Operationsarten im Plan, ohne die Operations-IDs (`Art:KIND:hash:hash`). */
    fun kinds(dir: Path, name: String): Set<String> =
        Regex("\"(?:operationId|id|type|kind)\"\\s*:\\s*\"([A-Za-z]+)\"").findAll(report(dir, name))
            .map { it.groupValues[1] }.filter { it.startsWith("Alter") || it.startsWith("Create") }.toSet()

    // ── P0-Matrix: eine Zeile je Basistyp ──

    val unchanged = setOf("CreateCustomType")
    val rebuilt = setOf("CreateCustomType", "AlterColumnNullability")
    val bases = listOf(
        "integer" to domain("integer"), "biginteger" to domain("biginteger"), "smallint" to domain("smallint"),
        "boolean" to domain("boolean"), "real" to domain("real"), "float" to domain("float"),
        "decimal(10,2)" to decimalDomain, "decimal ohne precision" to domain("decimal"),
        "text" to domain("text"), "varchar(32)" to domain("varchar(32)"), "char(5)" to domain("char(5)"),
        "uuid" to domain("uuid"), "json" to domain("json"), "xml" to domain("xml"), "binary" to domain("binary"),
        "date" to domain("date"), "time" to domain("time"), "datetime" to domain("datetime"),
        "timestamptz" to domain("timestamptz"),
        "Enum mit ref_type" to "  d: { kind: enum, values: [a, b] }",
        "Domain mit Wertelisten-CHECK" to "  d: { kind: domain, base_type: text, check: \"VALUE IN ('a','b')\" }",
        "Domain ueber integer mit CHECK" to "  d: { kind: domain, base_type: integer, check: \"VALUE > 0\" }",
    )

    for ((label, types) in bases) {
        test("Domain ueber $label: kein AlterColumnType, Neubau aus anderem Grund laeuft durch, zweiter Lauf und Rueckweg") {
            val dir = Files.createTempDirectory("cli-domain-column")
            val before = write(dir, "a.yaml", yaml(false, types))
            val after = write(dir, "b.yaml", yaml(true, types))
            val db = databaseFromFile(dir, before)
            exec(db, "INSERT INTO t (id, flag, n) VALUES (1, 7, NULL)")

            val plan = migrate(dir, before, db, "plan")
            withClue("Plan-Only ohne Aenderung: ${report(dir, "plan")}") {
                plan shouldBe 0
                kinds(dir, "plan") shouldBe unchanged
            }
            val up = migrate(dir, after, db, "up", "--execute")
            withClue("Neubau --execute: ${report(dir, "up")}") {
                up shouldBe 0
                kinds(dir, "up") shouldBe rebuilt
                scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'flag' AND \"notnull\" = 1") shouldBe "1"
                scalar(db, "SELECT count(*) FROM t") shouldBe "1"
            }
            val again = migrate(dir, after, db, "again", "--execute")
            withClue("zweiter Lauf: ${report(dir, "again")}") {
                again shouldBe 0
                kinds(dir, "again") shouldBe unchanged
            }
            val down = migrate(dir, before, db, "down", "--execute")
            withClue("Rueckweg: ${report(dir, "down")}") {
                down shouldBe 0
                kinds(dir, "down") shouldBe rebuilt
                scalar(db, "SELECT count(*) FROM pragma_table_info('t') WHERE name = 'flag' AND \"notnull\" = 1") shouldBe "0"
            }
        }
    }

    // ── Datei gegen Datei ──

    test("Datei gegen Datei: derselbe Basistyp ist keine Aenderung") {
        val dir = Files.createTempDirectory("cli-domain-column-file-same")
        val a = write(dir, "a.yaml", yaml(false, domain("boolean")))

        migrateFiles(dir, a, a, "plan") shouldBe 0

        kinds(dir, "plan") shouldBe emptySet()
    }

    test("Datei gegen Datei: ein Basistypwechsel ist genau ein AlterCustomType, kein AlterColumnType") {
        val dir = Files.createTempDirectory("cli-domain-column-file-change")
        val old = write(dir, "a.yaml", yaml(false, domain("integer")))
        val new = write(dir, "b.yaml", yaml(false, domain("biginteger")))

        migrateFiles(dir, new, old, "plan") shouldBe 0

        kinds(dir, "plan") shouldBe setOf("AlterCustomType")
    }

    // ── Echter Unterschied bleibt sichtbar ──

    test("Domain ueber integer gegen eine Textspalte der Datenbank bleibt ein AlterColumnType") {
        val dir = Files.createTempDirectory("cli-domain-column-real-change")
        val textFile = write(dir, "a.yaml", yaml(false, domain("text")))
        val intFile = write(dir, "b.yaml", yaml(false, domain("integer")))
        val db = databaseFromFile(dir, textFile)

        migrate(dir, intFile, db, "plan")

        withClue(report(dir, "plan")) { kinds(dir, "plan").contains("AlterColumnType") shouldBe true }
    }
})
