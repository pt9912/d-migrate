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

    fun tableSql(db: Path): String =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT sql FROM sqlite_master WHERE name = 't'").use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    test("geaenderter Wertevorrat gegen eine Datenbank baut den CHECK neu auf, ohne Folge-Drift") {
        val dir = Files.createTempDirectory("cli-migrate-custom-types-live")
        val before = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml("a, b")) }
        val after = dir.resolve("c-vals.yaml").also { Files.writeString(it, schemaYaml("a, b, c")) }
        val db = database(dir, before)
        val report = dir.resolve("report.json")

        run(
            "schema", "migrate", "--source", after.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", report.toString(),
        ) shouldBe 0

        // Der neue Wert steht im CHECK der Datenbank, die Daten sind erhalten.
        tableSql(db) shouldContain "'c'"
        exec(db, "INSERT INTO t (id, e) VALUES (3, 'c')")
        rows(db) shouldBe listOf("1:a", "2:b", "3:c")
        rejects(db, "INSERT INTO t (id, e) VALUES (4, 'x')") shouldBe true
        Files.readString(report) shouldNotContain "SQLITE_CUSTOM_TYPE_VOCABULARY_UNVERIFIED"

        // Gleicher Stand noch einmal: nichts mehr zu tun, kein Drift.
        run(
            "schema", "migrate", "--source", after.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("again.json").toString(),
        ) shouldBe 0
    }

    test("ein entfernter Wert, den Zeilen noch tragen, laesst die Tabelle unveraendert") {
        val dir = Files.createTempDirectory("cli-migrate-custom-types-violating")
        val wide = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml("a, b, c")) }
        val narrow = dir.resolve("c-narrow.yaml").also { Files.writeString(it, schemaYaml("a, b")) }
        val db = database(dir, wide)
        exec(db, "INSERT INTO t (id, e) VALUES (3, 'c')")
        val sqlBefore = tableSql(db)

        val report = dir.resolve("report.json")
        val exit = run(
            "schema", "migrate", "--source", narrow.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", report.toString(),
        )

        (exit != 0) shouldBe true
        // Der Preflight meldet es vor dem Apply, nicht erst der abbrechende INSERT.
        Files.readString(report) shouldContain "CHECK_PREFLIGHT_VIOLATIONS"
        Files.readString(report) shouldContain "e (enum values)"
        rows(db) shouldBe listOf("1:a", "2:b", "3:c")
        tableSql(db) shouldBe sqlBefore
    }

    test("ein entfernter Wert ohne betroffene Zeilen baut den CHECK enger neu auf") {
        val dir = Files.createTempDirectory("cli-migrate-custom-types-narrow")
        val wide = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml("a, b, c")) }
        val narrow = dir.resolve("c-narrow.yaml").also { Files.writeString(it, schemaYaml("a, b")) }
        val db = database(dir, wide)

        run(
            "schema", "migrate", "--source", narrow.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("report.json").toString(),
        ) shouldBe 0

        // Ein No-Op bliebe hier nicht grün: der alte, weitere CHECK muss weg sein.
        tableSql(db) shouldNotContain "'c'"
        rejects(db, "INSERT INTO t (id, e) VALUES (3, 'c')") shouldBe true
        rows(db) shouldBe listOf("1:a", "2:b")
    }

    fun inlineYaml(values: String) = """
        schema_format: "1.0"
        name: "I"
        version: "1.0"
        tables:
          t:
            columns:
              id: { type: integer, required: true }
              e:  { type: enum, values: [$values], required: true }
            primary_key: [id]
    """.trimIndent() + "\n"

    test("ein Inline-Enum verhaelt sich unveraendert: gleicher Stand ohne Neubau, neue Werte mit Neubau") {
        val dir = Files.createTempDirectory("cli-migrate-inline-enum")
        val before = dir.resolve("i.yaml").also { Files.writeString(it, inlineYaml("a, b")) }
        val after = dir.resolve("i-vals.yaml").also { Files.writeString(it, inlineYaml("a, b, c")) }
        val db = database(dir, before)
        val sqlBefore = tableSql(db)

        run(
            "schema", "migrate", "--source", before.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("same.json").toString(),
        ) shouldBe 0
        tableSql(db) shouldBe sqlBefore

        run(
            "schema", "migrate", "--source", after.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("vals.json").toString(),
        ) shouldBe 0
        exec(db, "INSERT INTO t (id, e) VALUES (3, 'c')")
        rejects(db, "INSERT INTO t (id, e) VALUES (4, 'x')") shouldBe true
    }

    test("ein handgeschriebener CHECK mit anderem Wertevorrat wird auf das Soll gebracht") {
        val dir = Files.createTempDirectory("cli-migrate-custom-types-handwritten-diff")
        val yaml = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml("a, b, c")) }
        val db = dir.resolve("db.sqlite")
        exec(
            db,
            "CREATE TABLE t (id INTEGER NOT NULL, e TEXT NOT NULL DEFAULT 'a' CHECK (e IN ('a', 'b')), PRIMARY KEY (id))",
            "INSERT INTO t (id, e) VALUES (1, 'a')",
        )

        run(
            "schema", "migrate", "--source", yaml.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("report.json").toString(),
        ) shouldBe 0
        exec(db, "INSERT INTO t (id, e) VALUES (2, 'c')")
        // Der alte CHECK ist ersetzt, nicht ergaenzt.
        rejects(db, "INSERT INTO t (id, e) VALUES (3, 'x')") shouldBe true
        tableSql(db) shouldContain "'c'"
    }

    test("ein handgeschriebener Wertelisten-CHECK an einer Spalte, die das Soll als Text fuehrt, wird abgeloest") {
        // Wie ein benannter CHECK, den das Soll nicht fuehrt: das Soll ist die Autoritaet.
        val dir = Files.createTempDirectory("cli-migrate-custom-types-handwritten-extra")
        val yaml = dir.resolve("p.yaml").also {
            Files.writeString(
                it,
                """
                schema_format: "1.0"
                name: "P"
                version: "1.0"
                tables:
                  t:
                    columns:
                      id: { type: integer, required: true }
                      e:  { type: text, required: true }
                    primary_key: [id]
                """.trimIndent() + "\n",
            )
        }
        val db = dir.resolve("db.sqlite")
        exec(db, "CREATE TABLE t (id INTEGER NOT NULL, e TEXT NOT NULL CHECK (e IN ('a', 'b')), PRIMARY KEY (id))")

        run(
            "schema", "migrate", "--source", yaml.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("report.json").toString(),
        ) shouldBe 0
        exec(db, "INSERT INTO t (id, e) VALUES (1, 'zzz')")
    }

    test("ein handgeschriebener CHECK in anderer Schreibweise mit gleichem Wertevorrat loest keinen Neubau aus") {
        val dir = Files.createTempDirectory("cli-migrate-custom-types-spelling")
        val yaml = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml("a, b")) }
        val db = dir.resolve("db.sqlite")
        exec(
            db,
            "CREATE TABLE t (id INTEGER NOT NULL, e TEXT NOT NULL DEFAULT 'a' CHECK (  e IN ( 'b' ,'a' )  ), PRIMARY KEY (id))",
            "INSERT INTO t (id, e) VALUES (1, 'a')",
        )
        val sqlBefore = tableSql(db)

        run(
            "schema", "migrate", "--source", yaml.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("report.json").toString(),
        ) shouldBe 0
        tableSql(db) shouldBe sqlBefore
    }

    test("schema rollback nach dem Neubau stellt den Ausgangszustand her") {
        val dir = Files.createTempDirectory("cli-migrate-custom-types-rollback")
        val before = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml("a, b")) }
        val after = dir.resolve("c-vals.yaml").also { Files.writeString(it, schemaYaml("a, b, c")) }
        val db = database(dir, before)
        val sqlBefore = tableSql(db)
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")

        run(
            "schema", "migrate", "--source", after.toString(), "--target", "db:sqlite://$db",
            "--generate-rollback", "--output", up.toString(), "--rollback-output", down.toString(),
            "--report", dir.resolve("plan.json").toString(),
        ) shouldBe 0
        applyFile(db, up)
        tableSql(db) shouldContain "'c'"

        run(
            "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
            "--execute", "--allow-destructive",
        ) shouldBe 0
        rejects(db, "INSERT INTO t (id, e) VALUES (9, 'c')") shouldBe true
        tableSql(db) shouldBe sqlBefore

        // Der Ausgangszustand stimmt auch fuer den Abdruck: derselbe Stand noch einmal ist nichts zu tun.
        run(
            "schema", "migrate", "--source", before.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("after-rollback.json").toString(),
        ) shouldBe 0
        // Nichts zu tun: der Zustand nach dem Rollback ist der Ausgangszustand, auch fuer den Abdruck.
        tableSql(db) shouldBe sqlBefore
        Files.readString(dir.resolve("after-rollback.json")) shouldNotContain "TARGET_STATE_MISMATCH"
    }

    fun domainYaml(check: String, base: String = "integer") = """
        schema_format: "1.0"
        name: "D"
        version: "1.0"
        custom_types:
          pos: { kind: domain, base_type: $base, check: "$check" }
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

    test("eine Domain mit Wertelisten-CHECK wird wie eine Enum erkannt: gleicher Stand ohne Neubau, neue Werte mit Neubau") {
        val dir = Files.createTempDirectory("cli-migrate-domain-list")
        val before = dir.resolve("d.yaml").also { Files.writeString(it, domainYaml("VALUE IN ('a', 'b')", "text")) }
        val after = dir.resolve("d-vals.yaml").also { Files.writeString(it, domainYaml("VALUE IN ('a', 'b', 'c')", "text")) }
        val create = dir.resolve("create.sql")
        run("schema", "generate", "--source", before.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        val db = dir.resolve("db.sqlite")
        applyFile(db, create)
        exec(db, "INSERT INTO t (id, n) VALUES (1, 'a')")
        val sqlBefore = tableSql(db)

        // Ohne die Aufloesung der Domain wuerde die Spalte bei jedem Lauf neu gebaut und der Post-Compare scheitern.
        run(
            "schema", "migrate", "--source", before.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("same.json").toString(),
        ) shouldBe 0
        tableSql(db) shouldBe sqlBefore

        run(
            "schema", "migrate", "--source", after.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("vals.json").toString(),
        ) shouldBe 0
        exec(db, "INSERT INTO t (id, n) VALUES (2, 'c')")
        rejects(db, "INSERT INTO t (id, n) VALUES (3, 'x')") shouldBe true
    }

    test("eine Domain mit Wertenliste und Nicht-Text-Basistyp bleibt ohne Drift: der Basistyp geht nicht verloren") {
        val dir = Files.createTempDirectory("cli-migrate-domain-list-integer")
        val yaml = dir.resolve("d.yaml").also { Files.writeString(it, domainYaml("VALUE IN ('1', '2')", "integer")) }
        val create = dir.resolve("create.sql")
        run("schema", "generate", "--source", yaml.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        val db = dir.resolve("db.sqlite")
        applyFile(db, create)
        exec(db, "INSERT INTO t (id, n) VALUES (1, 1)")
        val sqlBefore = tableSql(db)

        run(
            "schema", "migrate", "--source", yaml.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("report.json").toString(),
        ) shouldBe 0
        tableSql(db) shouldBe sqlBefore
    }

    test("eine Domain-Wertenliste in gemischter Schreibweise bleibt ohne Drift") {
        val dir = Files.createTempDirectory("cli-migrate-domain-list-case")
        val yaml = dir.resolve("d.yaml").also { Files.writeString(it, domainYaml("Value IN ('a', 'b')", "text")) }
        val create = dir.resolve("create.sql")
        run("schema", "generate", "--source", yaml.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        val db = dir.resolve("db.sqlite")
        applyFile(db, create)
        val sqlBefore = tableSql(db)

        run(
            "schema", "migrate", "--source", yaml.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", dir.resolve("report.json").toString(),
        ) shouldBe 0
        tableSql(db) shouldBe sqlBefore
    }

    test("ein entfernter Domain-Wert, den Zeilen noch tragen, wird vor dem Apply gemeldet") {
        val dir = Files.createTempDirectory("cli-migrate-domain-list-violating")
        val wide = dir.resolve("d.yaml").also { Files.writeString(it, domainYaml("VALUE IN ('a', 'b', 'c')", "text")) }
        val narrow = dir.resolve("d-narrow.yaml").also { Files.writeString(it, domainYaml("VALUE IN ('a', 'b')", "text")) }
        val create = dir.resolve("create.sql")
        run("schema", "generate", "--source", wide.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        val db = dir.resolve("db.sqlite")
        applyFile(db, create)
        exec(db, "INSERT INTO t (id, n) VALUES (1, 'c')")
        val sqlBefore = tableSql(db)
        val report = dir.resolve("report.json")

        (run(
            "schema", "migrate", "--source", narrow.toString(), "--target", "db:sqlite://$db",
            "--execute", "--report", report.toString(),
        ) != 0) shouldBe true
        Files.readString(report) shouldContain "CHECK_PREFLIGHT_VIOLATIONS"
        tableSql(db) shouldBe sqlBefore
    }
})
