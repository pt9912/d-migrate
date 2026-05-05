package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.cancel.OperationCancelSource
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
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
        JobArtifactPublisher { job, _ -> prefix + job.managedJob.jobId }

    test("Happy path: load source + load target → compare → publish → Succeeded") {
        val refsSeen = mutableListOf<String>()
        val worker = SchemaCompareJobWorker(
            sourceRef = sourceRef,
            targetRef = targetRef,
            schemaLoader = { ref, _, _ ->
                refsSeen += ref
                emptySchema
            },
            comparator = { _, _ -> identicalDiff },
            publisher = publisher(),
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
                emptySchema
            },
            comparator = { _, _ -> identicalDiff },
            publisher = publisher(),
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
                emptySchema
            },
            comparator = { _, _ -> identicalDiff },
            publisher = publisher(),
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
                emptySchema
            },
            comparator = { _, _ ->
                compareCalled = true
                identicalDiff
            },
            publisher = publisher(),
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
            schemaLoader = { _, _, _ -> emptySchema },
            comparator = { _, _ ->
                source.cancel("after-compare")
                identicalDiff
            },
            publisher = JobArtifactPublisher { _, _ ->
                publishCalled = true
                "dmigrate://x"
            },
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
        )
        val ex = shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-6"), CancellationToken.none())
        }
        ex.source shouldBe OperationCancelSource.RUNNER_TIMEOUT
    }

    test("Loader bekommt Tenant + Token weitergereicht (Multi-Tenant-Defense + E0-Token)") {
        var seenTenant: TenantId? = null
        var seenToken: CancellationToken? = null
        val tokenSource = CancellationTokenSource.create()
        val worker = SchemaCompareJobWorker(
            sourceRef = sourceRef,
            targetRef = targetRef,
            schemaLoader = { _, t, tok ->
                seenTenant = t
                seenToken = tok
                emptySchema
            },
            comparator = { _, _ -> identicalDiff },
            publisher = publisher(),
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
            ).execute(Fixtures.jobRecord("j-8"), CancellationToken.none())
        }

        // Comparator-Pfad
        shouldThrow<IllegalStateException> {
            SchemaCompareJobWorker(
                sourceRef = sourceRef,
                targetRef = targetRef,
                schemaLoader = { _, _, _ -> emptySchema },
                comparator = { _, _ -> error("comparator-bug") },
                publisher = publisher(),
            ).execute(Fixtures.jobRecord("j-9"), CancellationToken.none())
        }

        // Publisher-Pfad
        shouldThrow<IllegalStateException> {
            SchemaCompareJobWorker(
                sourceRef = sourceRef,
                targetRef = targetRef,
                schemaLoader = { _, _, _ -> emptySchema },
                comparator = { _, _ -> identicalDiff },
                publisher = JobArtifactPublisher { _, _ -> error("artifact-store-down") },
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
            schemaLoader = { ref, _, _ -> if (ref == sourceRef) schemaA else schemaB },
            comparator = SchemaComparator()::compare,
            publisher = JobArtifactPublisher { job, payload ->
                publisherPayload = payload
                "dmigrate://tenants/acme/artifacts/${job.managedJob.jobId}"
            },
        )
        worker.execute(Fixtures.jobRecord("j-11"), CancellationToken.none())
        // Publisher bekommt EINEN SchemaDiff, nicht null oder String.
        publisherPayload.shouldBeInstanceOf<SchemaDiff>()
    }
})
