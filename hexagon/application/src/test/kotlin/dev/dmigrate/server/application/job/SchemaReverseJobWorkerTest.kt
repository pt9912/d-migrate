package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.cancel.OperationCancelSource
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ReverseSourceKind
import dev.dmigrate.driver.ReverseSourceRef
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadReportInput
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.server.application.connection.ConnectionMaterializer
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val REPORTS = "dmigrate://tenants/acme/artifacts/report-"

class SchemaReverseJobWorkerTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val connectionRef = "dmigrate://tenants/acme/connections/c1"
    val emptySchema = SchemaDefinition(name = "test", version = "1")

    fun materializer(
        config: ConnectionConfig = ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = "localhost",
            port = null,
            database = "test",
            user = null,
            password = null,
        ),
        verifyTenant: Boolean = true,
    ) = ConnectionMaterializer { ref, t ->
        if (verifyTenant && t != tenant) error("tenant-mismatch")
        if (ref != connectionRef) error("ref-mismatch")
        config
    }

    fun publisher(prefix: String = "dmigrate://tenants/acme/artifacts/") =
        JobArtifactPublisher<Any> { job, _ -> prefix + job.managedJob.jobId }

    test("Happy path: materialize → read → publish → Succeeded") {
        var readWasCalled = false
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            readSchema = { _, _ ->
                readWasCalled = true
                SchemaReadResult(emptySchema)
            },
            publisher = publisher(),
            reportPublisher = publisher(REPORTS),
        )
        val record = Fixtures.jobRecord("j-1")
        val outcome = worker.execute(record, CancellationToken.none())
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>()
        outcome.artifactRefs shouldBe listOf(
            "dmigrate://tenants/acme/artifacts/j-1",
            "dmigrate://tenants/acme/artifacts/report-j-1",
        )
        readWasCalled shouldBe true
    }

    test("Token vor Materialize cancelled → OperationCancelledException, Materializer wird nicht aufgerufen") {
        var matCalled = false
        val source = CancellationTokenSource.create()
        source.cancel("user-cancel")
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = ConnectionMaterializer { _, _ ->
                matCalled = true
                error("should not be called")
            },
            readSchema = { _, _ -> SchemaReadResult(emptySchema) },
            publisher = publisher(),
            reportPublisher = publisher(REPORTS),
        )
        val record = Fixtures.jobRecord("j-2")
        val ex = shouldThrow<OperationCancelledException> {
            worker.execute(record, source.token)
        }
        ex.reason shouldBe "user-cancel"
        ex.source shouldBe OperationCancelSource.JOB_CANCEL
        matCalled shouldBe false
    }

    test("Token zwischen Materialize und Read cancelled → Reader wird nicht aufgerufen") {
        val source = CancellationTokenSource.create()
        var readCalled = false
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = ConnectionMaterializer { _, _ ->
                source.cancel("after-materialize")
                ConnectionConfig(
                    dialect = DatabaseDialect.POSTGRESQL,
                    host = "h", port = null, database = "d",
                    user = null, password = null,
                )
            },
            readSchema = { _, _ ->
                readCalled = true
                SchemaReadResult(emptySchema)
            },
            publisher = publisher(),
            reportPublisher = publisher(REPORTS),
        )
        val ex = shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-3"), source.token)
        }
        ex.reason shouldBe "after-materialize"
        readCalled shouldBe false
    }

    test("Token zwischen Read und Publish cancelled → Publisher wird nicht aufgerufen") {
        val source = CancellationTokenSource.create()
        var publishCalled = false
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            readSchema = { _, _ ->
                source.cancel("after-read")
                SchemaReadResult(emptySchema)
            },
            publisher = JobArtifactPublisher<Any> { _, _ ->
                publishCalled = true
                "dmigrate://x"
            },
            reportPublisher = JobArtifactPublisher<Any> { _, _ ->
                publishCalled = true
                "dmigrate://y"
            },
        )
        shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-4"), source.token)
        }
        publishCalled shouldBe false
    }

    test("Materializer wirft (z.B. ResourceNotFound) → Exception propagiert") {
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = ConnectionMaterializer { _, _ -> error("not-found") },
            readSchema = { _, _ -> SchemaReadResult(emptySchema) },
            publisher = publisher(),
            reportPublisher = publisher(REPORTS),
        )
        // Worker faengt nicht selbst — Dispatcher klassifiziert als
        // RUNNER_ERROR. Hier verifizieren wir nur die Propagation.
        val ex = shouldThrow<IllegalStateException> {
            worker.execute(Fixtures.jobRecord("j-5"), CancellationToken.none())
        }
        ex.message shouldBe "not-found"
    }

    test("Reader propagiert OperationCancelledException mit RUNNER_TIMEOUT") {
        // Fall: Reader hat eigenes Timeout-Budget und wirft mit
        // RUNNER_TIMEOUT-Source. Worker reicht durch — Dispatcher mappt
        // auf Failed(OPERATION_TIMEOUT).
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            readSchema = { _, _ ->
                throw OperationCancelledException(
                    reason = "read-budget-exhausted",
                    source = OperationCancelSource.RUNNER_TIMEOUT,
                )
            },
            publisher = publisher(),
            reportPublisher = publisher(REPORTS),
        )
        val ex = shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-6"), CancellationToken.none())
        }
        ex.source shouldBe OperationCancelSource.RUNNER_TIMEOUT
        ex.reason shouldBe "read-budget-exhausted"
    }

    test("Publisher wirft → Exception propagiert") {
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            readSchema = { _, _ -> SchemaReadResult(emptySchema) },
            publisher = JobArtifactPublisher<Any> { _, _ -> error("artifact-store-unavailable") },
            reportPublisher = publisher(REPORTS),
        )
        shouldThrow<IllegalStateException> {
            worker.execute(Fixtures.jobRecord("j-7"), CancellationToken.none())
        }
    }

    test("Reader bekommt den Token weitergereicht (fuer interne Checkpoints)") {
        val source = CancellationTokenSource.create()
        var receivedToken: CancellationToken? = null
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            readSchema = { _, t ->
                receivedToken = t
                SchemaReadResult(emptySchema)
            },
            publisher = publisher(),
            reportPublisher = publisher(REPORTS),
        )
        worker.execute(Fixtures.jobRecord("j-8"), source.token)
        // Identitaets-Vergleich: Reader sieht denselben Token den der
        // Worker uebergeben bekommt — keine Wrappper-Ebene.
        (receivedToken === source.token) shouldBe true
    }

    test("ConnectionMaterializer bekommt die jobRecord-tenantId") {
        // Multi-Tenant-Defense: Worker reicht NIE einen anderen Tenant
        // an den Materializer. Bei kompromittiertem connectionRef-String
        // sind die Stores die letzte Verteidigungslinie.
        var seenTenant: TenantId? = null
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = ConnectionMaterializer { _, t ->
                seenTenant = t
                ConnectionConfig(
                    dialect = DatabaseDialect.POSTGRESQL,
                    host = "h", port = null, database = "d",
                    user = null, password = null,
                )
            },
            readSchema = { _, _ -> SchemaReadResult(emptySchema) },
            publisher = publisher(),
            reportPublisher = publisher(REPORTS),
        )
        worker.execute(Fixtures.jobRecord("j-9").copy(tenantId = Fixtures.tenant("beta")), CancellationToken.none())
        seenTenant shouldBe Fixtures.tenant("beta")
    }

    test("the read report carries the reader's notes and names the connection, after the schema") {
        // Das Schema-Dokument traegt keine Notes (wie `schema reverse`);
        // ohne den Report blieben sie ueber MCP stumm.
        val note = SchemaReadNote(SchemaReadSeverity.INFO, "R205", "t.id", "read as identity")
        val published = mutableListOf<Any>()
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            readSchema = { _, _ -> SchemaReadResult(emptySchema, notes = listOf(note)) },
            publisher = JobArtifactPublisher { _, schema ->
                published += schema
                "dmigrate://tenants/acme/artifacts/schema"
            },
            reportPublisher = JobArtifactPublisher { _, report ->
                published += report
                "dmigrate://tenants/acme/artifacts/report"
            },
        )
        val outcome = worker.execute(Fixtures.jobRecord("j-10"), CancellationToken.none())
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>().artifactRefs shouldBe listOf(
            "dmigrate://tenants/acme/artifacts/schema",
            "dmigrate://tenants/acme/artifacts/report",
        )
        published[0] shouldBe emptySchema
        published[1] shouldBe SchemaReadReportInput(
            ReverseSourceRef(ReverseSourceKind.CONNECTION, connectionRef),
            SchemaReadResult(emptySchema, notes = listOf(note)),
        )
    }
})
