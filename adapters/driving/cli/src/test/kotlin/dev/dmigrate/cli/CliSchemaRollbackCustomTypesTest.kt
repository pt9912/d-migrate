package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.sqlite.SqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Der Ablauf aus GitHub-Issue #47 durch die echte CLI und eine echte
 * SQLite-Datei: `schema generate` -> `schema migrate --generate-rollback`
 * (ohne `--execute`) -> Up-SQL anwenden -> `schema rollback --execute`.
 *
 * Das Schema traegt eine Aufzaehlung mit `ref_type`. SQLite kann den benannten
 * Typ nicht zurueckgeben; der Rollback darf daran nicht mit
 * `TARGET_STATE_MISMATCH` (Exit 8) scheitern.
 */
class CliSchemaRollbackCustomTypesTest : FunSpec({

    // Die Registry ist prozessweit: nicht leer zuruecklassen.
    afterSpec {
        DatabaseDriverRegistry.clear()
        DatabaseDriverRegistry.loadAll()
    }

    fun cli(): DMigrate {
        DatabaseDriverRegistry.clear()
        DatabaseDriverRegistry.register(SqliteDriver())
        return DMigrate().subcommands(SchemaCommand())
    }

    /** Exit-Code eines CLI-Laufs; ein Lauf ohne `ProgramResult` ist Exit 0. */
    fun run(vararg args: String): Int =
        try {
            cli().parse(args.toList())
            0
        } catch (e: ProgramResult) {
            e.statusCode
        }

    val schemaYaml = """
        schema_format: "1.0"
        name: "C"
        version: "1.0"
        custom_types:
          ee: { kind: enum, values: [a, b] }
        tables:
          t:
            columns:
              id: { type: integer, required: true }
              e:  { type: enum, ref_type: ee, required: true, default: a }
            primary_key: [id]
    """.trimIndent() + "\n"

    fun apply(db: Path, sql: Path) {
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            Files.readString(sql).lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { stmt -> conn.createStatement().use { it.executeUpdate(stmt) } }
        }
    }

    fun columns(db: Path): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(t)").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                }
            }
        }

    test("schema rollback --execute besteht auf SQLite mit einer ref_type-Aufzaehlung") {
        val dir = Files.createTempDirectory("cli-rollback-custom-types")
        val base = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml) }
        val added = dir.resolve("c-add.yaml").also {
            Files.writeString(it, schemaYaml.replace("    primary_key: [id]", "      extra: { type: text }\n    primary_key: [id]"))
        }
        val db = dir.resolve("db.sqlite")
        val create = dir.resolve("create.sql")
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")

        run("schema", "generate", "--source", base.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        run(
            "schema", "migrate", "--source", added.toString(), "--target", "file:$base", "--dialect", "sqlite",
            "--generate-rollback", "--output", up.toString(), "--rollback-output", down.toString(),
        ) shouldBe 0
        apply(db, create)
        apply(db, up)
        columns(db) shouldBe listOf("id", "e", "extra")

        run(
            "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
            "--execute", "--allow-destructive",
        ) shouldBe 0
        columns(db) shouldBe listOf("id", "e")
    }

    test("ein veraenderter Zustand bleibt TARGET_STATE_MISMATCH (Exit 8)") {
        val dir = Files.createTempDirectory("cli-rollback-custom-types-drift")
        val base = dir.resolve("c.yaml").also { Files.writeString(it, schemaYaml) }
        val added = dir.resolve("c-add.yaml").also {
            Files.writeString(it, schemaYaml.replace("    primary_key: [id]", "      extra: { type: text }\n    primary_key: [id]"))
        }
        val db = dir.resolve("db.sqlite")
        val create = dir.resolve("create.sql")
        val up = dir.resolve("up.sql")
        val down = dir.resolve("down.sql")
        run("schema", "generate", "--source", base.toString(), "--target", "sqlite", "--output", create.toString()) shouldBe 0
        run(
            "schema", "migrate", "--source", added.toString(), "--target", "file:$base", "--dialect", "sqlite",
            "--generate-rollback", "--output", up.toString(), "--rollback-output", down.toString(),
        ) shouldBe 0
        apply(db, create)
        apply(db, up)
        DriverManager.getConnection("jdbc:sqlite:$db").use { it.createStatement().use { st ->
            st.executeUpdate("ALTER TABLE t ADD COLUMN stray TEXT")
        } }

        run(
            "schema", "rollback", "--source", down.toString(), "--target", "db:sqlite://$db",
            "--execute", "--allow-destructive",
        ) shouldBe 8
        columns(db) shouldBe listOf("id", "e", "extra", "stray")
    }
})
