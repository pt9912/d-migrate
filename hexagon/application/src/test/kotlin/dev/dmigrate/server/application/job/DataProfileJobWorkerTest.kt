package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.cancel.OperationCancelSource
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.server.application.connection.ConnectionMaterializer
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DataProfileJobWorkerTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val connectionRef = "dmigrate://tenants/acme/connections/c1"
    val emptyProfile = DatabaseProfile(databaseProduct = "postgres", tables = emptyList())

    fun config() = ConnectionConfig(
        dialect = DatabaseDialect.POSTGRESQL,
        host = "localhost",
        port = null,
        database = "test",
        user = null,
        password = null,
    )

    fun materializer(verifyTenant: Boolean = true) =
        ConnectionMaterializer { ref, t ->
            if (verifyTenant && t != tenant) error("tenant-mismatch")
            if (ref != connectionRef) error("ref-mismatch")
            config()
        }

    fun publisher(prefix: String = "dmigrate://tenants/acme/artifacts/") =
        JobArtifactPublisher { job, _ -> prefix + job.managedJob.jobId }

    test("Happy path: materialize → profile → publish → Succeeded") {
        var profileWasCalled = false
        val worker = DataProfileJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            runProfile = { _, _ ->
                profileWasCalled = true
                emptyProfile
            },
            publisher = publisher(),
        )
        val record = Fixtures.jobRecord("j-1")
        val outcome = worker.execute(record, CancellationToken.none())
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>()
        outcome.artifactRefs shouldBe listOf("dmigrate://tenants/acme/artifacts/j-1")
        profileWasCalled shouldBe true
    }

    test("Token vor Materialize cancelled → Materializer wird nicht aufgerufen") {
        val source = CancellationTokenSource.create()
        source.cancel("user-cancel")
        var matCalled = false
        val worker = DataProfileJobWorker(
            connectionRef = connectionRef,
            materializer = ConnectionMaterializer { _, _ ->
                matCalled = true
                error("should not be called")
            },
            runProfile = { _, _ -> emptyProfile },
            publisher = publisher(),
        )
        val ex = shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-2"), source.token)
        }
        ex.reason shouldBe "user-cancel"
        ex.source shouldBe OperationCancelSource.JOB_CANCEL
        matCalled shouldBe false
    }

    test("Token zwischen Materialize und Profile cancelled → runProfile wird nicht aufgerufen") {
        val source = CancellationTokenSource.create()
        var profileCalled = false
        val worker = DataProfileJobWorker(
            connectionRef = connectionRef,
            materializer = ConnectionMaterializer { _, _ ->
                source.cancel("after-materialize")
                config()
            },
            runProfile = { _, _ ->
                profileCalled = true
                emptyProfile
            },
            publisher = publisher(),
        )
        shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-3"), source.token)
        }
        profileCalled shouldBe false
    }

    test("Token zwischen Profile und Publish cancelled → Publisher wird nicht aufgerufen") {
        val source = CancellationTokenSource.create()
        var publishCalled = false
        val worker = DataProfileJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            runProfile = { _, _ ->
                source.cancel("after-profile")
                emptyProfile
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

    test("Materializer wirft → Exception propagiert (Dispatcher mappt zu Failed/RUNNER_ERROR)") {
        val worker = DataProfileJobWorker(
            connectionRef = connectionRef,
            materializer = ConnectionMaterializer { _, _ -> error("not-found") },
            runProfile = { _, _ -> emptyProfile },
            publisher = publisher(),
        )
        val ex = shouldThrow<IllegalStateException> {
            worker.execute(Fixtures.jobRecord("j-5"), CancellationToken.none())
        }
        ex.message shouldBe "not-found"
    }

    test("runProfile propagiert RUNNER_TIMEOUT → Source bleibt erhalten fuer Dispatcher-Klassifikation") {
        val worker = DataProfileJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            runProfile = { _, _ ->
                throw OperationCancelledException(
                    reason = "profile-budget-exhausted",
                    source = OperationCancelSource.RUNNER_TIMEOUT,
                )
            },
            publisher = publisher(),
        )
        val ex = shouldThrow<OperationCancelledException> {
            worker.execute(Fixtures.jobRecord("j-6"), CancellationToken.none())
        }
        ex.source shouldBe OperationCancelSource.RUNNER_TIMEOUT
        ex.reason shouldBe "profile-budget-exhausted"
    }

    test("Publisher wirft → Exception propagiert") {
        val worker = DataProfileJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            runProfile = { _, _ -> emptyProfile },
            publisher = JobArtifactPublisher { _, _ -> error("artifact-store-unavailable") },
        )
        shouldThrow<IllegalStateException> {
            worker.execute(Fixtures.jobRecord("j-7"), CancellationToken.none())
        }
    }

    test("runProfile bekommt den Token weitergereicht (fuer interne Profiler-Checkpoints)") {
        val source = CancellationTokenSource.create()
        var receivedToken: CancellationToken? = null
        val worker = DataProfileJobWorker(
            connectionRef = connectionRef,
            materializer = materializer(),
            runProfile = { _, t ->
                receivedToken = t
                emptyProfile
            },
            publisher = publisher(),
        )
        worker.execute(Fixtures.jobRecord("j-8"), source.token)
        (receivedToken === source.token) shouldBe true
    }

    test("ConnectionMaterializer bekommt die jobRecord-tenantId (Multi-Tenant-Defense)") {
        var seenTenant: TenantId? = null
        val worker = DataProfileJobWorker(
            connectionRef = connectionRef,
            materializer = ConnectionMaterializer { _, t ->
                seenTenant = t
                config()
            },
            runProfile = { _, _ -> emptyProfile },
            publisher = publisher(),
        )
        worker.execute(Fixtures.jobRecord("j-9").copy(tenantId = Fixtures.tenant("beta")), CancellationToken.none())
        seenTenant shouldBe Fixtures.tenant("beta")
    }
})
