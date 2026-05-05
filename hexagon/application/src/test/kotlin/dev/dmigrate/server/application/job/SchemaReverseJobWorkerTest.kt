package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.cancel.OperationCancelSource
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.server.application.connection.ConnectionMaterializer
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

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
        JobArtifactPublisher { job, _ -> prefix + job.managedJob.jobId }

    test("Happy path: materialize → read → publish → Succeeded") {
        var readWasCalled = false
        val worker = SchemaReverseJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            readSchema = { _, _ ->
                readWasCalled = true
                emptySchema
            },
            publisher = publisher(),
        )
        val record = Fixtures.jobRecord("j-1")
        val outcome = worker.execute(record, CancellationToken.none())
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>()
        outcome.artifactRefs shouldBe listOf("dmigrate://tenants/acme/artifacts/j-1")
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
            readSchema = { _, _ -> emptySchema },
            publisher = publisher(),
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
                emptySchema
            },
            publisher = publisher(),
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
                emptySchema
            },
            publisher = JobArtifactPublisher { _, _ ->
                publishCalled = true
                "dmigrate://x"
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
            readSchema = { _, _ -> emptySchema },
            publisher = publisher(),
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
            readSchema = { _, _ -> emptySchema },
            publisher = JobArtifactPublisher { _, _ -> error("artifact-store-unavailable") },
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
                emptySchema
            },
            publisher = publisher(),
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
            readSchema = { _, _ -> emptySchema },
            publisher = publisher(),
        )
        worker.execute(Fixtures.jobRecord("j-9").copy(tenantId = Fixtures.tenant("beta")), CancellationToken.none())
        seenTenant shouldBe Fixtures.tenant("beta")
    }
})
