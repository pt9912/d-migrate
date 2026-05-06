package dev.dmigrate.server.persistence.jdbc.quota

import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.QuotaReservationOwner
import dev.dmigrate.server.application.quota.QuotaReservationOwnerStore
import dev.dmigrate.server.application.quota.QuotaReservationStatus
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.internal.executeUpdate
import dev.dmigrate.server.persistence.jdbc.internal.getInstant
import dev.dmigrate.server.persistence.jdbc.internal.querySingle
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant

/**
 * Postgres-/JDBC-Implementierung des [QuotaReservationOwnerStore]-
 * Vertrags. SQL-Patterns: Plan § 6.9 in
 * `docs/planning/in-progress/ImpPlan-0.9.6-E2.md`.
 *
 * Atomicity:
 * - `register` ist `INSERT` (ohne ON CONFLICT). Doppel-register fuer
 *   denselben ownerId wirft eine `IllegalArgumentException`
 *   (Contract-Pin), die aus der Postgres-PK-Constraint-Verletzung
 *   uebersetzt wird.
 * - `markCommitted`/`markReleased`/`markRefunded` sind atomare CAS
 *   via `UPDATE … WHERE state = ? RETURNING …`. 0 affected rows ⇒
 *   `null` (terminale Stati absorbierend, parallele Verlierer).
 *
 * Cross-TX-Komposition: `*OnConnection`-Varianten (internal) erlauben
 * [JdbcOwnerAwareQuotaService] das Owner-mark UND den Counter-
 * Decrement in einer geteilten DB-TX (Plan § 6.9 Failure-Window).
 */
class JdbcQuotaReservationOwnerStore(
    private val transactionRunner: JdbcTransactionRunner,
) : QuotaReservationOwnerStore {

    override fun register(
        ownerId: String,
        reservation: QuotaReservation,
        leaseExpiresAt: Instant,
        now: Instant,
    ): QuotaReservationOwner = transactionRunner.inTransaction { conn ->
        registerOnConnection(conn, ownerId, reservation, leaseExpiresAt, now)
    }

    override fun markCommitted(ownerId: String, now: Instant): QuotaReservationOwner? =
        transactionRunner.inTransaction { conn ->
            transitionOnConnection(
                conn,
                ownerId = ownerId,
                from = QuotaReservationStatus.PENDING,
                to = QuotaReservationStatus.COMMITTED,
                now = now,
            )
        }

    override fun markReleased(ownerId: String, now: Instant): QuotaReservationOwner? =
        transactionRunner.inTransaction { conn -> markReleasedOnConnection(conn, ownerId, now) }

    override fun markRefunded(ownerId: String, now: Instant): QuotaReservationOwner? =
        transactionRunner.inTransaction { conn -> markRefundedOnConnection(conn, ownerId, now) }

    override fun findById(ownerId: String): QuotaReservationOwner? =
        transactionRunner.inTransaction { conn -> findByIdOnConnection(conn, ownerId) }

    override fun listExpiredPending(now: Instant): List<QuotaReservationOwner> =
        transactionRunner.inTransaction { conn ->
            conn.prepareStatement(
                """
                SELECT owner_id, reservation::text AS reservation_text,
                       state, lease_expires_at, created_at, updated_at
                  FROM quota_reservation_owners
                 WHERE state = 'PENDING' AND lease_expires_at <= ?
                 ORDER BY lease_expires_at ASC
                """.trimIndent(),
            ).use { ps ->
                ps.setTimestamp(1, java.sql.Timestamp.from(now))
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toOwner()) }
                }
            }
        }

    // ── *OnConnection helpers — used by JdbcOwnerAwareQuotaService ──

    internal fun registerOnConnection(
        conn: Connection,
        ownerId: String,
        reservation: QuotaReservation,
        leaseExpiresAt: Instant,
        now: Instant,
    ): QuotaReservationOwner {
        val reservationJson = QuotaJson.reservationToJson(reservation)
        try {
            conn.executeUpdate(
                sql = """
                    INSERT INTO quota_reservation_owners
                      (owner_id, reservation, state, lease_expires_at, created_at, updated_at)
                    VALUES (?, ?::jsonb, 'PENDING', ?, ?, ?)
                """.trimIndent(),
                ownerId, reservationJson, leaseExpiresAt, now, now,
            )
        } catch (cause: SQLException) {
            // Plan § 6.9 + Contract: PK-Verletzung -> IllegalArgumentException.
            // Postgres SQLState 23505 = unique_violation.
            if (cause.sqlState == PG_SQLSTATE_UNIQUE_VIOLATION) {
                throw IllegalArgumentException(
                    "QuotaReservationOwnerStore: ownerId $ownerId already registered",
                    cause,
                )
            }
            throw cause
        }
        return QuotaReservationOwner(
            ownerId = ownerId,
            reservation = reservation,
            status = QuotaReservationStatus.PENDING,
            leaseExpiresAt = leaseExpiresAt,
            createdAt = now,
            updatedAt = now,
        )
    }

    internal fun markCommittedOnConnection(
        conn: Connection,
        ownerId: String,
        now: Instant,
    ): QuotaReservationOwner? = transitionOnConnection(
        conn,
        ownerId = ownerId,
        from = QuotaReservationStatus.PENDING,
        to = QuotaReservationStatus.COMMITTED,
        now = now,
    )

    internal fun markReleasedOnConnection(
        conn: Connection,
        ownerId: String,
        now: Instant,
    ): QuotaReservationOwner? = transitionOnConnection(
        conn,
        ownerId = ownerId,
        from = QuotaReservationStatus.COMMITTED,
        to = QuotaReservationStatus.RELEASED,
        now = now,
    )

    internal fun markRefundedOnConnection(
        conn: Connection,
        ownerId: String,
        now: Instant,
    ): QuotaReservationOwner? = transitionOnConnection(
        conn,
        ownerId = ownerId,
        from = QuotaReservationStatus.PENDING,
        to = QuotaReservationStatus.REFUNDED,
        now = now,
    )

    internal fun findByIdOnConnection(
        conn: Connection,
        ownerId: String,
    ): QuotaReservationOwner? = conn.querySingle(
        sql = """
            SELECT owner_id, reservation::text AS reservation_text,
                   state, lease_expires_at, created_at, updated_at
              FROM quota_reservation_owners
             WHERE owner_id = ?
        """.trimIndent(),
        ownerId,
    ) { rs -> rs.toOwner() }

    private fun transitionOnConnection(
        conn: Connection,
        ownerId: String,
        from: QuotaReservationStatus,
        to: QuotaReservationStatus,
        now: Instant,
    ): QuotaReservationOwner? = conn.prepareStatement(
        """
        UPDATE quota_reservation_owners SET
            state = ?,
            updated_at = ?
          WHERE owner_id = ? AND state = ?
        RETURNING owner_id, reservation::text AS reservation_text,
                  state, lease_expires_at, created_at, updated_at
        """.trimIndent(),
    ).use { ps ->
        ps.setString(1, to.name)
        ps.setTimestamp(2, java.sql.Timestamp.from(now))
        ps.setString(3, ownerId)
        ps.setString(4, from.name)
        ps.executeQuery().use { rs ->
            if (rs.next()) rs.toOwner() else null
        }
    }

    private fun java.sql.ResultSet.toOwner(): QuotaReservationOwner = QuotaReservationOwner(
        ownerId = getString("owner_id"),
        reservation = QuotaJson.reservationFromJson(getString("reservation_text")),
        status = QuotaReservationStatus.valueOf(getString("state")),
        leaseExpiresAt = getInstant("lease_expires_at"),
        createdAt = getInstant("created_at"),
        updatedAt = getInstant("updated_at"),
    )

    companion object {
        private const val PG_SQLSTATE_UNIQUE_VIOLATION = "23505"
    }
}
