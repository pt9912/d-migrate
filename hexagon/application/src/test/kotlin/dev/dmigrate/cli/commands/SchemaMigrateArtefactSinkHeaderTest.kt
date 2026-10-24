package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Der Kopf des Plan-Only-Artefakts warnt vor dem, was ein Neubau ausserhalb des
 * Runners anrichten kann. Der dritte Hinweis betrifft verletzende Zeilen: bricht
 * der `INSERT ... SELECT` ab und die Shell macht weiter, ist die Tabelle leer.
 */
class SchemaMigrateArtefactSinkHeaderTest : FunSpec({

    var written: String? = null

    fun sink() = SchemaMigrateArtefactSink(
        ensureParentDirectories = { },
        atomicWriter = { _, content -> written = content },
        stdout = { },
        printError = { _, _ -> },
        renderReport = { _, _ -> "" },
        clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
    )

    val request = SchemaMigrateRequest(
        source = "file:desired.yaml",
        target = "db:target",
        output = Path.of("up.sql"),
    )

    fun result(sql: String) = MigrationDdlResult(
        statements = listOf(MigrationDdlStatement(sql, setOf("op"), OperationRisk.SAFE, DiffPhase.TABLES)),
        operationsRendered = setOf("op"),
    )

    test("ein Neubau bekommt den Hinweis, dass die Tabelle bei einem Fehler ohne Abbruch leer bleibt") {
        sink().writeOrEchoUpSql(request, result("CREATE TABLE \"t__dmg_rebuild_1\" (id INTEGER);")) shouldBe null

        written!! shouldContain "3. A rebuild copies the rows with INSERT ... SELECT"
        written!! shouldContain "EMPTY"
        written!! shouldContain "sqlite3 -bail"
    }

    test("ohne Neubau steht kein Kopf da") {
        sink().writeOrEchoUpSql(request, result("ALTER TABLE t ADD COLUMN x TEXT;")) shouldBe null

        written!! shouldNotContain "A rebuild copies the rows"
    }
})
