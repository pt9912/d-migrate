package dev.dmigrate.server.persistence.jdbc.quota

import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.application.quota.OwnerAwareQuotaService
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.QuotaReservationOwner
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.time.Instant

/**
 * Postgres-/JDBC-Variante des [OwnerAwareQuotaService]-Vertrags
 * (Plan-Refs: `ImpPlan-0.9.6-E2.md` § 6.8 + § 6.9).
 *
 * Schliesst die Atomicity-Lücke aus § 6.9: `markReleased`/`markRefunded`
 * UND der zugehoerige Counter-Decrement laufen in derselben DB-TX
 * ueber [JdbcTransactionRunner]. Bei Crash zwischen Owner-Statuswechsel
 * und Counter-Update rollbackt Postgres beides; es entsteht kein
 * dauerhaft belegter Slot mit terminalem Owner.
 *
 * Das `delegate`-Argument der Basisklasse wird mit einem [DefaultQuotaService]
 * ueber [JdbcQuotaStore] befuellt — dieser wird allerdings NICHT direkt
 * gerufen, weil alle 4 OwnerAware-Methoden ueberschrieben sind. Er
 * erfuellt nur den Konstruktor-Vertrag.
 */
class JdbcOwnerAwareQuotaService(
    private val transactionRunner: JdbcTransactionRunner,
    private val jdbcQuotaStore: JdbcQuotaStore,
    private val jdbcOwnerStore: JdbcQuotaReservationOwnerStore,
    private val limitFor: (QuotaKey) -> Long,
) : OwnerAwareQuotaService(
    delegate = DefaultQuotaService(
        store = object : dev.dmigrate.server.ports.quota.QuotaStore by jdbcQuotaStore {},
        limitFor = limitFor,
    ),
    ownerStore = jdbcOwnerStore,
) {

    override fun reserve(
        key: QuotaKey,
        amount: Long,
        ownerId: String,
        leaseExpiresAt: Instant,
        now: Instant,
    ): QuotaOutcome = transactionRunner.inTransaction { conn ->
        val outcome = jdbcQuotaStore.reserveOnConnection(conn, key, amount, limitFor(key))
        if (outcome is QuotaOutcome.Granted) {
            jdbcOwnerStore.registerOnConnection(
                conn = conn,
                ownerId = ownerId,
                reservation = QuotaReservation.of(outcome),
                leaseExpiresAt = leaseExpiresAt,
                now = now,
            )
        }
        outcome
    }

    override fun commitForOwner(ownerId: String, now: Instant) {
        // commit ist im DefaultQuotaService ein no-op (Phase A audit-only).
        // markCommitted-CAS auf dem Owner-Store braucht keine Counter-Mutation,
        // also reicht ein einfacher inTransaction-Block ohne shared-conn-Helper.
        transactionRunner.inTransaction { conn ->
            jdbcOwnerStore.markCommittedOnConnection(conn, ownerId, now)
        }
    }

    override fun releaseForOwner(ownerId: String, now: Instant) {
        // Plan § 6.9: markReleased + Counter-Decrement MUESSEN gemeinsam.
        transactionRunner.inTransaction { conn ->
            val transitioned: QuotaReservationOwner =
                jdbcOwnerStore.markReleasedOnConnection(conn, ownerId, now)
                    ?: return@inTransaction
            jdbcQuotaStore.releaseOnConnection(
                conn = conn,
                key = transitioned.reservation.key,
                amount = transitioned.reservation.amount,
            )
        }
    }

    override fun refundForOwner(ownerId: String, now: Instant) {
        transactionRunner.inTransaction { conn ->
            val transitioned: QuotaReservationOwner =
                jdbcOwnerStore.markRefundedOnConnection(conn, ownerId, now)
                    ?: return@inTransaction
            jdbcQuotaStore.releaseOnConnection(
                conn = conn,
                key = transitioned.reservation.key,
                amount = transitioned.reservation.amount,
            )
        }
    }
}
