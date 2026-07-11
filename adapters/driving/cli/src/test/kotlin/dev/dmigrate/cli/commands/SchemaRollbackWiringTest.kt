package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.audit.CliAuditRecorder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Wiring-Test für `SchemaRollbackWiring.execute` — deckt den Zusammenbau
 * (Formatter/Validator/Request/Runner) über den DB-freien Nicht-Execute-Pfad ab.
 * Die integrationsgebundene `loadFromDb`-Naht wird nur bei DB-Quelle + `--execute`
 * betreten und bleibt der Integrationsabdeckung überlassen.
 */
class SchemaRollbackWiringTest : FunSpec({

    fun options(
        source: Path,
        target: String = "db:postgres://localhost/db",
        execute: Boolean = false,
        cliContext: CliContext = CliContext(quiet = true),
        configPath: Path? = null,
    ) = SchemaRollbackOptions(
        source = source,
        target = target,
        execute = execute,
        allowDestructive = false,
        allowPartialRollback = false,
        dryRun = !execute,
        cliContext = cliContext,
        configPath = configPath,
    )

    test("invalides Artefakt beendet mit Exit 7 (Parse/Hash) vor jedem DB-Zugriff") {
        val dir = Files.createTempDirectory("rollback-wiring")
        val artefact = dir.resolve("bad.sql")
        Files.writeString(artefact, "-- not a valid d-migrate rollback-sql artefact\n")

        val exit = SchemaRollbackWiring.execute(options(source = artefact))

        exit shouldBe 7
    }

    test("nicht lesbare Quelle wird als Artefakt-Fehler gemeldet (nicht-null Exit)") {
        val dir = Files.createTempDirectory("rollback-wiring-missing")
        val missing = dir.resolve("does-not-exist.sql")

        val exit = SchemaRollbackWiring.execute(options(source = missing))

        (exit != 0) shouldBe true
    }

    test("--execute wird als schema.rollback mit gescrubbtem Ziel-Ref auditiert") {
        val spy = SpyRecorder()
        val artefact = Files.createTempDirectory("rb").resolve("a.sql")
        Files.writeString(artefact, "x")

        SchemaRollbackWiring.execute(
            options(source = artefact, target = "db:prod", execute = true),
            recorder = spy,
        )

        spy.calls shouldBe listOf("schema.rollback" to listOf("db:prod"))
    }

    test("dry-run (kein --execute) wird nicht auditiert") {
        val spy = SpyRecorder()
        val artefact = Files.createTempDirectory("rb").resolve("a.sql")
        Files.writeString(artefact, "not-a-valid-artefact")

        SchemaRollbackWiring.execute(
            options(source = artefact, target = "db:prod", execute = false),
            recorder = spy,
        )

        spy.calls.shouldBeEmpty()
    }
})

/** Captured die record-Aufrufe und führt den Block NICHT aus (Metadaten-Assertion ohne echte Operation). */
private class SpyRecorder : CliAuditRecorder {
    val calls = mutableListOf<Pair<String, List<String>>>()
    override fun record(toolName: String, resourceRefs: List<String>, block: () -> Int): Int {
        calls += toolName to resourceRefs
        return 0
    }
}
