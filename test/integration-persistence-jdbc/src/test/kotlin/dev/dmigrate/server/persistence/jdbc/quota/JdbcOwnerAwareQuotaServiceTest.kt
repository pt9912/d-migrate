package dev.dmigrate.server.persistence.jdbc.quota

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.application.quota.QuotaReservationStatus
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.time.Instant


private val ownerAwareTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var ownerAwareTestDataSource: HikariDataSource? = null

/**
 * LF-012 / LN-011 / LN-017 / LN-027 — `JdbcOwnerAwareQuotaService`-spezifische Atomicity-Tests
 * (Plan-Akzeptanz (c) + (d)):
 *
 * (c) Reserve+Register, Double-Release/Refund laufen gegen das JDBC-Wiring.
 * (d) Crash-Window-Test: Exception zwischen Owner-markX und Counter-
 *     Decrement rollbackt beides via `JdbcTransactionRunner`-TX.
 */
class JdbcOwnerAwareQuotaServiceTest : FunSpec({


    val key = QuotaKey(
        tenantId = TenantId("acme"),
        dimension = QuotaDimension.ACTIVE_JOBS,
        principalId = PrincipalId("alice"),
        operation = "data.export",
    )
    val now: Instant = Instant.parse("2026-05-06T10:00:00Z")

    beforeSpec {
        ownerAwareTestContainer.start()
        val cfg = HikariConfig().apply {
            jdbcUrl = ownerAwareTestContainer.jdbcUrl
            username = ownerAwareTestContainer.username
            password = ownerAwareTestContainer.password
            maximumPoolSize = 8
            poolName = "phase-e-ownerquota-tests"
        }
        ownerAwareTestDataSource = HikariDataSource(cfg)
        JdbcMigrationRunner(ownerAwareTestDataSource!!).migrate()
    }

    afterSpec {
        ownerAwareTestDataSource?.close()
        ownerAwareTestDataSource = null
        ownerAwareTestContainer.stop()
    }

    fun freshFixture(limit: Long = 5L): Fixture {
        val ds = ownerAwareTestDataSource!!
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE quota_counters, quota_reservation_owners")
            }
        }
        val runner = JdbcTransactionRunner(ds)
        val quotaStore = JdbcQuotaStore(runner)
        val ownerStore = JdbcQuotaReservationOwnerStore(runner)
        val service = JdbcOwnerAwareQuotaService(
            transactionRunner = runner,
            jdbcQuotaStore = quotaStore,
            jdbcOwnerStore = ownerStore,
            limitFor = { limit },
        )
        return Fixture(runner, quotaStore, ownerStore, service)
    }

    // ── (c) Reserve+Register ───────────────────────────────────────

    test("Granted: reserve + Owner-Register laufen in einer TX, beide sichtbar") {
        val fx = freshFixture()
        val outcome = fx.service.reserve(key, 1L, "owner-1", now.plusSeconds(60), now)
        outcome.shouldBeInstanceOf<QuotaOutcome.Granted>()
        outcome.newCurrent shouldBe 1L
        fx.ownerStore.findById("owner-1").shouldNotBeNull()
        fx.ownerStore.findById("owner-1")!!.status shouldBe QuotaReservationStatus.PENDING
    }

    test("RateLimited: kein Owner-Eintrag (TX rollbackt vor register)") {
        val fx = freshFixture(limit = 0L)
        val outcome = fx.service.reserve(key, 1L, "owner-x", now.plusSeconds(60), now)
        outcome.shouldBeInstanceOf<QuotaOutcome.RateLimited>()
        fx.ownerStore.findById("owner-x").shouldBeNull()
    }

    // ── (c) Double-Release / Double-Refund ─────────────────────────

    test("Doppel-Release: zweiter Aufruf ist no-op (markReleased CAS-Verlierer)") {
        val fx = freshFixture()
        fx.service.reserve(key, 1L, "o1", now.plusSeconds(60), now)
        fx.service.commitForOwner("o1", now.plusSeconds(1))
        fx.ownerStore.findById("o1")!!.status shouldBe QuotaReservationStatus.COMMITTED

        fx.service.releaseForOwner("o1", now.plusSeconds(2))
        fx.ownerStore.findById("o1")!!.status shouldBe QuotaReservationStatus.RELEASED
        fx.quotaStore.current(key) shouldBe 0L

        // Zweiter Release ist no-op — Counter bleibt bei 0, kein doppelter Decrement.
        fx.service.releaseForOwner("o1", now.plusSeconds(3))
        fx.quotaStore.current(key) shouldBe 0L
    }

    test("Doppel-Refund: zweiter Aufruf ist no-op (markRefunded CAS-Verlierer)") {
        val fx = freshFixture()
        fx.service.reserve(key, 1L, "o1", now.plusSeconds(60), now)
        // Direkt refunden ohne commit — PENDING→REFUNDED
        fx.service.refundForOwner("o1", now.plusSeconds(1))
        fx.ownerStore.findById("o1")!!.status shouldBe QuotaReservationStatus.REFUNDED
        fx.quotaStore.current(key) shouldBe 0L

        fx.service.refundForOwner("o1", now.plusSeconds(2))
        fx.quotaStore.current(key) shouldBe 0L
    }

    // ── (d) Crash-Window: Exception zwischen markX und Counter-Decrement ──

    test("Failure-Injection: throw zwischen markReleased und releaseOnConnection rollbackt beides") {
        val ds = ownerAwareTestDataSource!!
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE quota_counters, quota_reservation_owners")
            }
        }
        val runner = JdbcTransactionRunner(ds)
        val ownerStore = JdbcQuotaReservationOwnerStore(runner)
        val throwingQuotaStore = ReleaseFailingQuotaStore(
            runner = runner,
            cause = RuntimeException("simulated crash"),
        )
        val service = JdbcOwnerAwareQuotaService(
            transactionRunner = runner,
            jdbcQuotaStore = throwingQuotaStore,
            jdbcOwnerStore = ownerStore,
            limitFor = { 5L },
        )

        // Setup: reserve + commit, sodass releaseForOwner vom COMMITTED-
        // Zustand startet und markReleased erfolgreich CAS macht.
        service.reserve(key, 1L, "o-crash", now.plusSeconds(60), now)
        service.commitForOwner("o-crash", now.plusSeconds(1))
        ownerStore.findById("o-crash")!!.status shouldBe QuotaReservationStatus.COMMITTED
        val counterBefore = JdbcQuotaStore(runner).current(key)
        counterBefore shouldBe 1L

        // releaseForOwner: markReleasedOnConnection schreibt RELEASED in
        // die DB (innerhalb der TX), dann throwt ReleaseFailingQuotaStore.
        // LF-012 / LN-011 / LN-017 / LN-027: TX MUSS beides rollbacken → Owner bleibt COMMITTED,
        // Counter bleibt unveraendert.
        shouldThrow<RuntimeException> {
            service.releaseForOwner("o-crash", now.plusSeconds(2))
        }
        ownerStore.findById("o-crash")!!.status shouldBe QuotaReservationStatus.COMMITTED
        JdbcQuotaStore(runner).current(key) shouldBe 1L
    }
})

private data class Fixture(
    val runner: JdbcTransactionRunner,
    val quotaStore: JdbcQuotaStore,
    val ownerStore: JdbcQuotaReservationOwnerStore,
    val service: JdbcOwnerAwareQuotaService,
)

private class ReleaseFailingQuotaStore(
    runner: JdbcTransactionRunner,
    private val cause: Throwable,
) : JdbcQuotaStore(runner) {
    override fun releaseOnConnection(
        conn: Connection,
        key: QuotaKey,
        amount: Long,
    ): Long = throw cause
}
