package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import io.kotest.core.spec.style.FunSpec
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
})
