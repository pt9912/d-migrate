package dev.dmigrate.server.integration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.application.quota.QuotaReservationSweeper
import dev.dmigrate.server.application.quota.QuotaReservationStatus
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.persistence.jdbc.quota.JdbcOwnerAwareQuotaService
import dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaReservationOwnerStore
import dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

private val IntegrationTag = NamedTag("integration")

private val sweeperTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var sweeperTestDataSource: HikariDataSource? = null

/**
 * LF-012 / LN-011 / LN-017 / LN-027 — `QuotaReservationSweeper` exactly-once-Refund gegen
 * Postgres (Plan-Akzeptanz: "Sweeper findet orphane Owner-Eintraege"
 * + LF-012 / LN-011 / LN-017 / LN-027).
 *
 * Spiegelt das Bestands-`QuotaReservationSweeperTest` aber mit
 * `JdbcQuotaReservationOwnerStore` + echter PG-Persistenz statt
 * InMemory.
 */
class QuotaReservationSweeperE2ETest : FunSpec({

    tags(IntegrationTag)

    val key = QuotaKey(tenantId = TenantId("acme"), dimension = QuotaDimension.ACTIVE_JOBS)

    beforeSpec {
        sweeperTestContainer.start()
        val cfg = HikariConfig().apply {
            jdbcUrl = sweeperTestContainer.jdbcUrl
            username = sweeperTestContainer.username
            password = sweeperTestContainer.password
            maximumPoolSize = 4
            poolName = "phase-e-sweeper-e2e"
        }
        sweeperTestDataSource = HikariDataSource(cfg)
        JdbcMigrationRunner(sweeperTestDataSource!!).migrate()
    }

    afterSpec {
        sweeperTestDataSource?.close()
        sweeperTestDataSource = null
        sweeperTestContainer.stop()
    }

    fun freshFixture(jobLimit: Long = 5L): SweeperFixture {
        val ds = sweeperTestDataSource!!
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE quota_counters, quota_reservation_owners")
            }
        }
        val initial = Instant.parse("2026-05-05T12:00:00Z")
        val clock = ManualClock(initial)
        val runner = JdbcTransactionRunner(ds)
        val quotaStore = JdbcQuotaStore(runner)
        val ownerStore = JdbcQuotaReservationOwnerStore(runner)
        val owned = JdbcOwnerAwareQuotaService(
            transactionRunner = runner,
            jdbcQuotaStore = quotaStore,
            jdbcOwnerStore = ownerStore,
            limitFor = { jobLimit },
        )
        val delegate = DefaultQuotaService(
            store = object : dev.dmigrate.server.ports.quota.QuotaStore by quotaStore {},
            limitFor = { jobLimit },
        )
        return SweeperFixture(
            initial = initial,
            clock = clock,
            ownerStore = ownerStore,
            quotaStore = quotaStore,
            owned = owned,
            sweeper = QuotaReservationSweeper(ownerStore, delegate, clock),
        )
    }

    test("Sweeper refunded abgelaufene PENDING-Eintraege exactly-once gegen Postgres") {
        val fx = freshFixture()
        val now0 = fx.initial

        // Drei Reservierungen mit unterschiedlichen Lease-Zeiten.
        fx.owned.reserve(key, 1L, "o1", now0.plusSeconds(10), now0)
        fx.owned.reserve(key, 1L, "o2", now0.plusSeconds(30), now0)
        fx.owned.reserve(key, 1L, "o3", now0.plusSeconds(60), now0)
        fx.quotaStore.current(key) shouldBe 3L

        // Zeit auf 20s nach Start: o1 abgelaufen.
        fx.clock.setTo(now0.plusSeconds(20))
        fx.sweeper.sweep() shouldBe 1
        fx.quotaStore.current(key) shouldBe 2L
        fx.ownerStore.findById("o1")!!.status shouldBe QuotaReservationStatus.REFUNDED
        fx.ownerStore.findById("o2")!!.status shouldBe QuotaReservationStatus.PENDING

        // Zweiter Sweep am gleichen Zeitpunkt: o1 NICHT erneut refunded
        // (CAS-Verlierer auf bereits-REFUNDED-State).
        fx.sweeper.sweep() shouldBe 0
        fx.quotaStore.current(key) shouldBe 2L
    }

    test("Sweeper laesst COMMITTED-Eintraege unangetastet (LF-012 / LN-011 / LN-017 / LN-027)") {
        val fx = freshFixture()
        val now0 = fx.initial

        fx.owned.reserve(key, 1L, "o-committed", now0.plusSeconds(10), now0)
        fx.owned.commitForOwner("o-committed", now0.plusSeconds(5))

        // Lease abgelaufen, aber Status ist COMMITTED -> kein Refund.
        fx.clock.setTo(now0.plusSeconds(20))
        fx.sweeper.sweep() shouldBe 0
        fx.ownerStore.findById("o-committed")!!.status shouldBe QuotaReservationStatus.COMMITTED
        fx.quotaStore.current(key) shouldBe 1L
    }
})

private class ManualClock(initial: Instant) : Clock() {
    private val current = AtomicReference(initial)
    fun setTo(t: Instant) {
        current.set(t)
    }
    override fun instant(): Instant = current.get()
    override fun withZone(zone: ZoneId?): Clock = this
    override fun getZone(): ZoneId = ZoneOffset.UTC
}

private data class SweeperFixture(
    val initial: Instant,
    val clock: ManualClock,
    val ownerStore: JdbcQuotaReservationOwnerStore,
    val quotaStore: JdbcQuotaStore,
    val owned: JdbcOwnerAwareQuotaService,
    val sweeper: QuotaReservationSweeper,
)
