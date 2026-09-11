package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Was der Prozess zurueckgibt, steht auch im Report.
 *
 * Gemeldet aus einem Konsumentenprojekt: ein `--execute`, der mit Exit 5 an
 * Post-execute-Drift endete, schrieb einen Report mit `status: ok`,
 * `exitCode: 0` und leeren `diagnostics`. Der Grund stand nur auf `stderr`.
 * Wer den Report auswertet — und `--execute` verlangt ihn als Audit-Spur —
 * bekam die Auskunft, alles sei in Ordnung.
 */
class SchemaMigrateReportMatchesExitTest : FunSpec({

    var written: String? = null

    fun sink() = SchemaMigrateArtefactSink(
        ensureParentDirectories = { },
        atomicWriter = { _, content -> written = content },
        stdout = { },
        printError = { _, _ -> },
        renderReport = { report, _ ->
            "status=${report.status} exitCode=${report.exitCode} " +
                "diagnostics=${report.diagnostics.map { it.code }}"
        },
        clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
    )

    val plannedOk = SchemaMigrateReport(
        status = "ok",
        exitCode = 0,
        source = "file:desired.yaml",
        target = "db:target",
        dialect = "POSTGRESQL",
        planOnly = false,
        operations = emptyList(),
        statements = null,
        blockers = emptyList(),
        diagnostics = emptyList(),
        summary = SchemaMigrateSummary(),
    )

    val request = SchemaMigrateRequest(
        source = "file:desired.yaml",
        target = "db:target",
        report = Path.of("report.json"),
        execute = true,
    )

    test("a run that ends in drift writes a report that says so") {
        val exit = sink().emitReportAndExit(
            request = request,
            report = plannedOk,
            rollbackFinalized = false,
            baseExit = 5,
            outcomeDiagnostic = PostCompareOutcome.Drift("fp").asDiagnostic(),
        )

        exit shouldBe 5
        written.toString() shouldContain "exitCode=5"
        written.toString() shouldContain "status=failed"
        written.toString() shouldContain "POST_EXECUTE_DRIFT"
    }

    test("a run that could not read the target back says that instead") {
        sink().emitReportAndExit(
            request = request,
            report = plannedOk,
            rollbackFinalized = false,
            baseExit = 5,
            outcomeDiagnostic = PostCompareOutcome.IntrospectionFailed.asDiagnostic(),
        )

        written.toString() shouldContain "POST_EXECUTE_INTROSPECTION_FAILED"
    }

    test("a clean run keeps its planned status and gains no diagnosis") {
        val exit = sink().emitReportAndExit(
            request = request,
            report = plannedOk,
            rollbackFinalized = true,
            baseExit = 0,
        )

        exit shouldBe 0
        written.toString() shouldContain "status=ok"
        written.toString() shouldContain "exitCode=0"
        plannedOk.diagnostics.shouldBeEmpty()
    }
})
