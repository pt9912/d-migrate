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
import java.sql.SQLException

/**
 * `schema migrate` mit benannten Typen auf SQLite (GitHub-Issue #48), durch die
 * echte CLI und eine echte SQLite-Datei. SQLite hat kein Typ-Objekt: die
 * Aufzaehlung steht als `TEXT` + `CHECK` an der Spalte.
 */
class CliSchemaMigrateCustomTypesSqliteTest : FunSpec({

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

    fun schemaYaml(values: String) = """
        schema_format: "1.0"
        name: "C"
        version: "1.0"
        custom_types:
          ee: { kind: enum, values: [$values] }
        tables:
          t:
            columns:
              id: { type: integer, required: true }
              e:  { type: enum, ref_type: ee, required: true, default: a }
            primary_key: [id]
    """.trimIndent() + "\n"

    fun sqlStatements(sql: Path): List<String> =
        Files.readString(sql).lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun exec(db: Path, vararg statements: String) {
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st -> statements.forEach { st.execute(it) } }
        }
    }

    fun applyFile(db: Path, sql: Path) = exec(db, *sqlStatements(sql).toTypedArray())

    fun rejects(db: Path, insert: String): Boolean =
        try {
            exec(db, insert)
            false
        } catch (e: SQLException) {
            e.message.orEmpty().contains("CHECK constraint failed")
        }

    fun rows(db: Path): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id || ':' || e FROM t ORDER BY id").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    /** Datenbank aus `schema generate` von [yaml], mit zwei Zeilen. */
    fun database(dir: Path, yaml: Path): Path {
        val create = dir.resolve("create.sql")
        run("schema", "generate", "--source", yaml.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        val db = dir.resolve("db.sqlite")
        applyFile(db, create)
        exec(db, "INSERT INTO t (id, e) VALUES (1, 'a')", "INSERT INTO t (id, e) VALUES (2, 'b')")
        return db
    }

    test("migrate --execute gegen eine Datenbank, die dem Schema entspricht, ist nicht mehr blockiert") {
        val dir = Files.createTempDirectory("cli-migrate-custom-types-same")
        val yaml = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml("a, b")) }
        val db = database(dir, yaml)

        run(
            "schema", "migrate", "--source", yaml.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("report.json").toString(),
        ) shouldBe 0
        rows(db) shouldBe listOf("1:a", "2:b")
    }

    test("geaenderter Wertevorrat gegen ein file:-Ziel baut die Tabelle mit neuem CHECK neu auf") {
        val dir = Files.createTempDirectory("cli-migrate-custom-types-alter")
        val before = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml("a, b")) }
        val after = dir.resolve("c-vals.yaml").also { Files.writeString(it, schemaYaml("a, b, c")) }
        val db = database(dir, before)
        val up = dir.resolve("up.sql")

        run(
            "schema", "migrate", "--source", after.toString(), "--target", "file:$before", "--dialect", "sqlite",
            "--output", up.toString(), "--report", dir.resolve("plan.json").toString(),
        ) shouldBe 0
        applyFile(db, up)

        exec(db, "INSERT INTO t (id, e) VALUES (3, 'c')")
        rows(db) shouldBe listOf("1:a", "2:b", "3:c")
        rejects(db, "INSERT INTO t (id, e) VALUES (4, 'x')") shouldBe true

        // Der Neubau schreibt den CHECK unbenannt, wie `schema generate`: ein benannter
        // kaeme vom Reverse zurueck, und der naechste Lauf endete in POST_EXECUTE_DRIFT (Exit 5).
        run(
            "schema", "migrate", "--source", after.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("follow-up.json").toString(),
        ) shouldBe 0
    }

    test("geaenderter Wertevorrat gegen eine Datenbank wird nicht erkannt und als Warnung gemeldet") {
        val dir = Files.createTempDirectory("cli-migrate-custom-types-live")
        val before = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml("a, b")) }
        val after = dir.resolve("c-vals.yaml").also { Files.writeString(it, schemaYaml("a, b, c")) }
        val db = database(dir, before)
        val report = dir.resolve("report.json")

        run(
            "schema", "migrate", "--source", after.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", report.toString(),
        ) shouldBe 0

        // Kein stilles "ok" ohne Wirkung: der Lauf sagt, dass der CHECK nicht neu gebaut wurde.
        Files.readString(report) shouldContain "SQLITE_CUSTOM_TYPE_VOCABULARY_UNVERIFIED"
        rejects(db, "INSERT INTO t (id, e) VALUES (3, 'c')") shouldBe true
    }

    fun domainYaml(check: String) = """
        schema_format: "1.0"
        name: "D"
        version: "1.0"
        custom_types:
          pos: { kind: domain, base_type: integer, check: "$check" }
        tables:
          t:
            columns:
              id: { type: integer, required: true }
              n:  { type: enum, ref_type: pos, required: true }
            primary_key: [id]
    """.trimIndent() + "\n"

    test("Domain-Spalte: schema generate schreibt Basistyp und CHECK (Spec 6.3)") {
        val dir = Files.createTempDirectory("cli-migrate-domain-generate")
        val yaml = dir.resolve("d.yaml").also { Files.writeString(it, domainYaml("VALUE >= 0")) }
        val create = dir.resolve("create.sql")
        run("schema", "generate", "--source", yaml.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        val db = dir.resolve("db.sqlite")
        applyFile(db, create)

        Files.readString(create) shouldContain "\"n\" INTEGER NOT NULL CHECK (\"n\" >= 0)"
        exec(db, "INSERT INTO t (id, n) VALUES (1, 5)")
        rejects(db, "INSERT INTO t (id, n) VALUES (2, -1)") shouldBe true
    }

    test("geaenderter Domain-CHECK gegen ein file:-Ziel baut die Tabelle neu auf, ohne Folge-Drift") {
        val dir = Files.createTempDirectory("cli-migrate-domain-alter")
        val before = dir.resolve("d.yaml").also { Files.writeString(it, domainYaml("VALUE >= 0")) }
        val after = dir.resolve("d-new.yaml").also { Files.writeString(it, domainYaml("VALUE >= 10")) }
        val create = dir.resolve("create.sql")
        run("schema", "generate", "--source", before.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        val db = dir.resolve("db.sqlite")
        applyFile(db, create)
        exec(db, "INSERT INTO t (id, n) VALUES (1, 20)")
        val up = dir.resolve("up.sql")

        run(
            "schema", "migrate", "--source", after.toString(), "--target", "file:$before", "--dialect", "sqlite",
            "--output", up.toString(), "--report", dir.resolve("plan.json").toString(),
        ) shouldBe 0
        applyFile(db, up)

        rejects(db, "INSERT INTO t (id, n) VALUES (2, 5)") shouldBe true
        exec(db, "INSERT INTO t (id, n) VALUES (3, 11)")
        run(
            "schema", "migrate", "--source", after.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("follow-up.json").toString(),
        ) shouldBe 0
    }

    test("COMPOSITE bleibt gegen SQLite blockiert (Exit 8)") {
        val dir = Files.createTempDirectory("cli-migrate-custom-types-composite")
        val yaml = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml("a, b")) }
        val db = database(dir, yaml)
        val composite = dir.resolve("composite.yaml").also {
            Files.writeString(
                it,
                schemaYaml("a, b").replace(
                    "custom_types:\n",
                    "custom_types:\n  addr: { kind: composite, fields: { street: { type: text } } }\n",
                ),
            )
        }

        run(
            "schema", "migrate", "--source", composite.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("report.json").toString(),
        ) shouldBe 8
    }
})
