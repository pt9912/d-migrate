package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.cancel.OperationCancelSource
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.ReverseSourceKind
import dev.dmigrate.driver.ReverseSourceRef
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadReportInput
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SchemaCompareJobWorkerTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val sourceRef = "dmigrate://tenants/acme/schemas/source-1"
    val targetRef = "dmigrate://tenants/acme/schemas/target-1"
    val emptySchema = SchemaDefinition(name = "test", version = "1")
    val identicalDiff = SchemaComparator().compare(emptySchema, emptySchema)

    fun publisher(prefix: String = "dmigrate://tenants/acme/artifacts/") =
        JobArtifactPublisher<Any> { job, _ -> prefix + job.managedJob.jobId }

    // Zwei gespeicherte Schemata haben keinen Reverse-Report.
    val unusedReports = JobArtifactPublisher<SchemaReadReportInput> { _, _ -> error("no read report expected") }

    test("Happy path: load source + load target → compare → publish → Succeeded") {
        val refsSeen = mutableListOf<String>()
        val worker = SchemaCompareJobWorker(
            sourceRef = sourceRef,
            targetRef = targetRef,
            schemaLoader = { ref, _, _ ->
                refsSeen += ref
                LoadedCompareSide(emptySchema)
            },
            comparator = { _, _ -> identicalDiff },
            publisher = publisher(),
            reportPublisher = unusedReports,
        )
        val outcome = worker.execute(Fixtures.jobRecord("j-1"), CancellationToken.none())
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>()
        outcome.artifactRefs shouldBe listOf("dmigrate://tenants/acme/artifacts/j-1")
        // sourceRef wird zuerst geladen, dann targetRef — deterministische
        // Reihenfolge, damit Reader-Pool-Reuse spaeter sinnvoll greifen kann.
        refsSeen shouldBe listOf(sourceRef, targetRef)
    }

    test("Token vor Source-Load cancelled → Loader wird nicht aufgerufen") {
        val source = CancellationTokenSource.create()
        source.cancel("user-cancel")
        var loaderCalled = false
        val worker = SchemaCompareJobWorker(
            sourceRef = sourceRef,
            targetRef = targetRef,
            schemaLoader = { _, _, _ ->
                loaderCalled = true
                LoadedCompareSide(emptySchema)
            },
            comparator = { _, _ -> identicalDiff },
            publisher = publisher(),
            reportPublisher = unusedReports,
        )
        val ex = shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-2"), source.token)
        }
        ex.reason shouldBe "user-cancel"
        ex.source shouldBe OperationCancelSource.JOB_CANCEL
        loaderCalled shouldBe false
    }

    test("Token zwischen Source und Target cancelled → Target-Load uebersprungen") {
        val source = CancellationTokenSource.create()
        var loadCount = 0
        val worker = SchemaCompareJobWorker(
            sourceRef = sourceRef,
            targetRef = targetRef,
            schemaLoader = { _, _, _ ->
                loadCount++
                if (loadCount == 1) source.cancel("after-source")
                LoadedCompareSide(emptySchema)
            },
            comparator = { _, _ -> identicalDiff },
            publisher = publisher(),
            reportPublisher = unusedReports,
        )
        shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-3"), source.token)
        }
        loadCount shouldBe 1 // nur source geladen, target uebersprungen
    }

    test("Token zwischen Target und Compare cancelled → Comparator nicht aufgerufen") {
        val source = CancellationTokenSource.create()
        var loadCount = 0
        var compareCalled = false
        val worker = SchemaCompareJobWorker(
            sourceRef = sourceRef,
            targetRef = targetRef,
            schemaLoader = { _, _, _ ->
                loadCount++
                if (loadCount == 2) source.cancel("after-target")
                LoadedCompareSide(emptySchema)
            },
            comparator = { _, _ ->
                compareCalled = true
                identicalDiff
            },
            publisher = publisher(),
            reportPublisher = unusedReports,
        )
        shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-4"), source.token)
        }
        loadCount shouldBe 2
        compareCalled shouldBe false
    }

    test("Token zwischen Compare und Publish cancelled → Publisher nicht aufgerufen") {
        val source = CancellationTokenSource.create()
        var publishCalled = false
        val worker = SchemaCompareJobWorker(
            sourceRef = sourceRef,
            targetRef = targetRef,
            schemaLoader = { _, _, _ -> LoadedCompareSide(emptySchema) },
            comparator = { _, _ ->
                source.cancel("after-compare")
                identicalDiff
            },
            publisher = JobArtifactPublisher<Any> { _, _ ->
                publishCalled = true
                "dmigrate://x"
            },
            reportPublisher = unusedReports,
        )
        shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-5"), source.token)
        }
        publishCalled shouldBe false
    }

    test("Loader propagiert RUNNER_TIMEOUT → Source bleibt erhalten") {
        val worker = SchemaCompareJobWorker(
            sourceRef = sourceRef,
            targetRef = targetRef,
            schemaLoader = { _, _, _ ->
                throw OperationCancelledException(
                    reason = "load-budget-exhausted",
                    source = OperationCancelSource.RUNNER_TIMEOUT,
                )
            },
            comparator = { _, _ -> identicalDiff },
            publisher = publisher(),
            reportPublisher = unusedReports,
        )
        val ex = shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-6"), CancellationToken.none())
        }
        ex.source shouldBe OperationCancelSource.RUNNER_TIMEOUT
    }

    test("Loader bekommt Tenant + Token weitergereicht (Multi-Tenant-Defense + Cancel-Token)") {
        var seenTenant: TenantId? = null
        var seenToken: CancellationToken? = null
        val tokenSource = CancellationTokenSource.create()
        val worker = SchemaCompareJobWorker(
            sourceRef = sourceRef,
            targetRef = targetRef,
            schemaLoader = { _, t, tok ->
                seenTenant = t
                seenToken = tok
                LoadedCompareSide(emptySchema)
            },
            comparator = { _, _ -> identicalDiff },
            publisher = publisher(),
            reportPublisher = unusedReports,
        )
        worker.execute(
            Fixtures.jobRecord("j-7").copy(tenantId = Fixtures.tenant("beta")),
            tokenSource.token,
        )
        seenTenant shouldBe Fixtures.tenant("beta")
        (seenToken === tokenSource.token) shouldBe true
    }

    test("Loader / Comparator / Publisher generic exceptions propagieren") {
        // Loader-Pfad
        shouldThrow<IllegalStateException> {
            SchemaCompareJobWorker(
                sourceRef = sourceRef,
                targetRef = targetRef,
                schemaLoader = { _, _, _ -> error("loader-down") },
                comparator = { _, _ -> identicalDiff },
                publisher = publisher(),
                reportPublisher = unusedReports,
            ).execute(Fixtures.jobRecord("j-8"), CancellationToken.none())
        }

        // Comparator-Pfad
        shouldThrow<IllegalStateException> {
            SchemaCompareJobWorker(
                sourceRef = sourceRef,
                targetRef = targetRef,
                schemaLoader = { _, _, _ -> LoadedCompareSide(emptySchema) },
                comparator = { _, _ -> error("comparator-bug") },
                publisher = publisher(),
                reportPublisher = unusedReports,
            ).execute(Fixtures.jobRecord("j-9"), CancellationToken.none())
        }

        // Publisher-Pfad
        shouldThrow<IllegalStateException> {
            SchemaCompareJobWorker(
                sourceRef = sourceRef,
                targetRef = targetRef,
                schemaLoader = { _, _, _ -> LoadedCompareSide(emptySchema) },
                comparator = { _, _ -> identicalDiff },
                publisher = JobArtifactPublisher<Any> { _, _ -> error("artifact-store-down") },
                reportPublisher = unusedReports,
            ).execute(Fixtures.jobRecord("j-10"), CancellationToken.none())
        }
    }

    test("nicht-identische Diff: Comparator-Output landet bei Publisher") {
        var publisherPayload: Any? = null
        val schemaA = SchemaDefinition(name = "a", version = "1")
        val schemaB = SchemaDefinition(name = "b", version = "1")
        val worker = SchemaCompareJobWorker(
            sourceRef = sourceRef,
            targetRef = targetRef,
            schemaLoader = { ref, _, _ -> LoadedCompareSide(if (ref == sourceRef) schemaA else schemaB) },
            comparator = SchemaComparator()::compare,
            publisher = JobArtifactPublisher<Any> { job, payload ->
                publisherPayload = payload
                "dmigrate://tenants/acme/artifacts/${job.managedJob.jobId}"
            },
            reportPublisher = unusedReports,
        )
        worker.execute(Fixtures.jobRecord("j-11"), CancellationToken.none())
        // Publisher bekommt EINEN SchemaDiff, nicht null oder String.
        publisherPayload.shouldBeInstanceOf<SchemaDiff>()
    }

    test("a side read from a connection publishes its read report; the refs list the result first, source before target") {
        // Ueber MCP gibt es sonst keinen Ort fuer die Notes des Readers —
        // auch nicht fuer die Bestaetigung einer deklarierten Praeferenz.
        val note = SchemaReadNote(SchemaReadSeverity.INFO, "R205", "t.id", "read as identity")
        val connA = "dmigrate://tenants/acme/connections/a"
        val connB = "dmigrate://tenants/acme/connections/b"
        val reports = mutableListOf<SchemaReadReportInput>()
        fun run(source: String, target: String): List<String> {
            reports.clear()
            val worker = SchemaCompareJobWorker(
                sourceRef = source,
                targetRef = target,
                schemaLoader = { ref, _, _ ->
                    if (ref.contains("/connections/")) {
                        LoadedCompareSide.read(SchemaReadResult(emptySchema, notes = listOf(note.copy(objectName = ref))))
                    } else {
                        LoadedCompareSide(emptySchema)
                    }
                },
                comparator = { _, _ -> identicalDiff },
                publisher = publisher(),
                reportPublisher = JobArtifactPublisher { _, input ->
                    reports += input
                    "dmigrate://tenants/acme/artifacts/report-${reports.size}"
                },
            )
            val outcome = worker.execute(Fixtures.jobRecord("j-12"), CancellationToken.none())
            return outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>().artifactRefs
        }

        run(connA, connB) shouldBe listOf(
            "dmigrate://tenants/acme/artifacts/j-12",
            "dmigrate://tenants/acme/artifacts/report-1",
            "dmigrate://tenants/acme/artifacts/report-2",
        )
        reports.map { it.source } shouldBe listOf(
            ReverseSourceRef(ReverseSourceKind.CONNECTION, connA),
            ReverseSourceRef(ReverseSourceKind.CONNECTION, connB),
        )
        reports.map { it.result.notes.single().objectName } shouldBe listOf(connA, connB)

        run(sourceRef, connB) shouldBe listOf(
            "dmigrate://tenants/acme/artifacts/j-12",
            "dmigrate://tenants/acme/artifacts/report-1",
        )
        reports.single().source.value shouldBe connB
    }

    test("the result is published last: a failing report publish leaves no compare artefact behind") {
        // spec/mcp-server.md: das Ergebnis (mit Eintrag im Index `diffs`)
        // wird zuletzt abgelegt.
        val order = mutableListOf<String>()
        fun worker(reports: JobArtifactPublisher<SchemaReadReportInput>) = SchemaCompareJobWorker(
            sourceRef = "dmigrate://tenants/acme/connections/a",
            targetRef = targetRef,
            schemaLoader = { ref, _, _ ->
                if (ref.contains("/connections/")) LoadedCompareSide.read(SchemaReadResult(emptySchema))
                else LoadedCompareSide(emptySchema)
            },
            comparator = { _, _ -> identicalDiff },
            publisher = JobArtifactPublisher { _, _ ->
                order += "result"
                "dmigrate://tenants/acme/artifacts/result"
            },
            reportPublisher = reports,
        )

        val reports = JobArtifactPublisher<SchemaReadReportInput> { _, _ ->
            order += "report"
            "dmigrate://tenants/acme/artifacts/report"
        }
        worker(reports).execute(Fixtures.jobRecord("j-13"), CancellationToken.none())
        order shouldBe listOf("report", "result")

        order.clear()
        shouldThrow<IllegalStateException> {
            worker(JobArtifactPublisher { _, _ -> error("report-store-unavailable") })
                .execute(Fixtures.jobRecord("j-14"), CancellationToken.none())
        }
        order shouldBe emptyList()
    }

    test("with two connection sides the result is published only after both reports") {
        val order = mutableListOf<String>()
        val worker = SchemaCompareJobWorker(
            sourceRef = "dmigrate://tenants/acme/connections/a",
            targetRef = "dmigrate://tenants/acme/connections/b",
            schemaLoader = { _, _, _ -> LoadedCompareSide.read(SchemaReadResult(emptySchema)) },
            comparator = { _, _ -> identicalDiff },
            publisher = JobArtifactPublisher { _, _ ->
                order += "result"
                "dmigrate://tenants/acme/artifacts/result"
            },
            reportPublisher = JobArtifactPublisher { _, input ->
                order += "report:" + input.source.value.substringAfterLast('/')
                if (input.source.value.endsWith("/b")) error("target-report-store-unavailable")
                "dmigrate://tenants/acme/artifacts/report"
            },
        )
        shouldThrow<IllegalStateException> {
            worker.execute(Fixtures.jobRecord("j-two-sides"), CancellationToken.none())
        }
        order shouldBe listOf("report:a", "report:b")
    }
})
