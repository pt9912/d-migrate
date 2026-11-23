package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.core.version.VersionInfo
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.sqlite.SqliteDriver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * Issue #78: die Artefakte von `schema migrate` nennen die Version des laufenden Werkzeugs -- dieselbe, die
 * `--version` meldet. Bis 1.12.1 trug das Rücknahme-Artefakt `"createdByVersion":"d-migrate (dev)"`, weil die
 * CLI den Runner ohne Version erzeugte und dessen Vorgabe ein fester Platzhalter war. Geprüft wird über die
 * CLI, nicht am Runner: genau die Verdrahtung hat den Wert verloren.
 */
class CliSchemaMigrateArtefactVersionTest : FunSpec({

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

    fun yaml(withNote: Boolean): String {
        val note = if (withNote) "\n      note: { type: text }" else ""
        return "schema_format: \"1.0\"\nname: \"ArtefactVersion\"\nversion: \"1.0\"\ntables:\n  t:\n" +
            "    columns:\n      id: { type: integer, required: true }$note\n    primary_key: [id]\n"
    }

    fun write(dir: Path, name: String, content: String): Path =
        dir.resolve(name).also { Files.writeString(it, content) }

    val version = VersionInfo.PRODUCT_VERSION

    test("die Werkzeugversion ist aufgelöst, sonst prüfte der Test nichts") {
        version shouldNotContain "unknown"
        version shouldNotContain "dev"
    }

    test("Rücknahme- und Plan-Artefakt tragen die Version, die --version meldet") {
        val dir = Files.createTempDirectory("cli-migrate-artefact-version")
        val old = write(dir, "old.yaml", yaml(withNote = false))
        val new = write(dir, "new.yaml", yaml(withNote = true))
        val down = dir.resolve("down.sql")
        val plan = dir.resolve("plan.json")

        val exit = run(
            "schema", "migrate", "--source", new.toString(), "--target", "file:$old", "--dialect", "sqlite",
            "--output", dir.resolve("up.sql").toString(), "--report", dir.resolve("report.json").toString(),
            "--generate-rollback", "--rollback-output", down.toString(), "--plan-artefact", plan.toString(),
        )

        withClue(Files.readString(dir.resolve("report.json"))) { exit shouldBe 0 }
        val rollbackHeader = Files.readString(down).lineSequence().first { "createdByVersion" in it }
        rollbackHeader shouldContain "\"createdByVersion\":\"$version\""
        // Das Plan-Artefakt ist eingerueckt (`"dMigrateVersion": "…"`), das Ruecknahme-Artefakt kompakt.
        Files.readString(plan) shouldContain Regex("\"dMigrateVersion\":\\s*\"${Regex.escape(version)}\"")
    }
})
