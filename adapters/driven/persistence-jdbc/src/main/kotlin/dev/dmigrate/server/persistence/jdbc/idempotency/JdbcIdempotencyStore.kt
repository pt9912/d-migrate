package dev.dmigrate.server.persistence.jdbc.idempotency

import dev.dmigrate.server.core.approval.ApprovalChallenge
import dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.idempotency.IdempotencyState
import dev.dmigrate.server.core.idempotency.InitResumeOutcome
import dev.dmigrate.server.core.idempotency.InitResumeScope
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.internal.bindAll
import dev.dmigrate.server.persistence.jdbc.internal.executeUpdate
import dev.dmigrate.server.persistence.jdbc.internal.getInstant
import dev.dmigrate.server.persistence.jdbc.internal.querySingle
import dev.dmigrate.server.ports.IdempotencyStore
import java.sql.Connection
import java.time.Instant

/**
 * Postgres-/JDBC-Implementierung des [IdempotencyStore]-Vertrags.
 *
 * SQL-Patterns: LF-012 / LN-011 / LN-017 / LN-027–§ 6.6 in
 * `docs/planning/done/LF-012 / LN-011 / LN-017 / LN-027`. Atomicity:
 * INSERT…ON CONFLICT DO NOTHING fuer den Hot-Path; SELECT…FOR UPDATE
 * fuer Recovery- und Claim-Pfade; CAS via `WHERE state IN (...)
 * AND expires_at <= ?` im Recovery-UPDATE.
 *
 * Dieser Pfad implementiert NICHT [reserveInitResume] — der Init-
 * Resume-Pfad lebt in einer separaten Tabelle (`init_resume_reservations`)
 * und kommt in AP LF-012 / LN-011 / LN-017 / LN-027 (`JdbcInitResumeStore`).
 */
class JdbcIdempotencyStore(
    private val transactionRunner: JdbcTransactionRunner,
    private val pendingLeaseSeconds: Long = DEFAULT_PENDING_LEASE_SECONDS,
    private val awaitingApprovalSeconds: Long = DEFAULT_AWAITING_APPROVAL_SECONDS,
    private val deniedRetentionSeconds: Long = DEFAULT_DENIED_RETENTION_SECONDS,
    private val committedRetentionSeconds: Long = DEFAULT_COMMITTED_RETENTION_SECONDS,
    private val failedRetentionSeconds: Long = DEFAULT_FAILED_RETENTION_SECONDS,
    private val initResumeSeconds: Long = DEFAULT_INIT_RESUME_SECONDS,
) : IdempotencyStore {

    override fun reserve(
        scope: IdempotencyScope,
        payloadFingerprint: String,
        now: Instant,
    ): IdempotencyReserveOutcome = transactionRunner.inTransaction { conn ->
        // LF-012 / LN-011 / LN-017 / LN-027 (1): try insert-if-absent.
        val inserted = conn.tryInsertPending(scope, payloadFingerprint, now)
        if (inserted != null) {
            return@inTransaction IdempotencyReserveOutcome.Reserved(scope, inserted)
        }

        // LF-012 / LN-011 / LN-017 / LN-027 (2): existing row — lock, entscheiden,
        // nur bei Recovery schreiben.
        val existing = conn.lockExisting(scope) ?: error(
            "Race: insert returned no row but SELECT FOR UPDATE found nothing for $scope",
        )
        when (val decision = decideReserve(scope, payloadFingerprint, now, existing)) {
            is ReserveDecision.Complete -> decision.outcome
            ReserveDecision.RecoverExpired -> recoverExpired(conn, scope, payloadFingerprint, now)
        }
    }

    private fun Connection.tryInsertPending(
        scope: IdempotencyScope,
        fingerprint: String,
        now: Instant,
    ): Instant? {
        val expiresAt = now.plusSeconds(pendingLeaseSeconds)
        return querySingle(
            sql = """
                INSERT INTO idempotency_reservations
                  (tenant_id, caller_id, tool_name, idempotency_key,
                   state, claimed, payload_fingerprint,
                   expires_at, retention_until, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', FALSE, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, caller_id, tool_name, idempotency_key)
                  DO NOTHING
                RETURNING expires_at
            """.trimIndent(),
            scope.tenantId.value, scope.callerId.value, scope.toolName, scope.idempotencyKey.value,
            fingerprint, expiresAt, expiresAt, now, now,
        ) { it.getInstant("expires_at") }
    }

    private fun Connection.lockExisting(scope: IdempotencyScope): ReservationRow? = querySingle(
        sql = """
            SELECT state, claimed, payload_fingerprint, expires_at,
                   result_ref, challenge::text AS challenge_text, reason
              FROM idempotency_reservations
             WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
             FOR UPDATE
        """.trimIndent(),
        scope.tenantId.value, scope.callerId.value, scope.toolName, scope.idempotencyKey.value,
    ) { rs ->
        ReservationRow(
            state = IdempotencyState.valueOf(rs.getString("state")),
            claimed = rs.getBoolean("claimed"),
            fingerprint = rs.getString("payload_fingerprint"),
            expiresAt = rs.getInstant("expires_at"),
            resultRef = rs.getString("result_ref"),
            challengeJson = rs.getString("challenge_text"),
            reason = rs.getString("reason"),
        )
    }

    private fun recoverExpired(
        conn: Connection,
        scope: IdempotencyScope,
        fingerprint: String,
        now: Instant,
    ): IdempotencyReserveOutcome {
        val newExpires = now.plusSeconds(pendingLeaseSeconds)
        val updated = conn.executeUpdate(
            """
            UPDATE idempotency_reservations SET
                state = 'PENDING', claimed = FALSE,
                payload_fingerprint = ?, result_ref = NULL,
                challenge = NULL, reason = NULL,
                expires_at = ?, retention_until = ?, updated_at = ?
              WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
                AND state IN ('PENDING','AWAITING_APPROVAL')
                AND expires_at <= ?
            """.trimIndent(),
            fingerprint, newExpires, newExpires, now,
            scope.tenantId.value, scope.callerId.value, scope.toolName, scope.idempotencyKey.value,
            now,
        )
        check(updated == 1) {
            "Recovery-CAS expected to update exactly 1 row within FOR-UPDATE TX, got $updated"
        }
        return IdempotencyReserveOutcome.Reserved(scope, newExpires)
    }

    override fun reserveInitResume(
        scope: InitResumeScope,
        payloadFingerprint: String,
        sessionId: String,
        now: Instant,
    ): InitResumeOutcome = transactionRunner.inTransaction { conn ->
        // LF-012 / LN-011 / LN-017 / LN-027 (1): try insert-if-absent; bei Erfolg sofort Reserved.
        val expiresAt = now.plusSeconds(initResumeSeconds)
        val inserted = conn.querySingle(
            sql = """
                INSERT INTO init_resume_reservations
                  (tenant_id, caller_id, tool_name, client_request_id,
                   session_id, payload_fingerprint,
                   expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, caller_id, tool_name, client_request_id)
                  DO NOTHING
                RETURNING session_id, expires_at
            """.trimIndent(),
            scope.tenantId.value, scope.callerId.value, scope.toolName, scope.clientRequestId,
            sessionId, payloadFingerprint, expiresAt, now, now,
        ) { rs -> InitResumeRow(rs.getString("session_id"), null, rs.getInstant("expires_at")) }

        if (inserted != null) {
            return@inTransaction InitResumeOutcome.Reserved(scope, inserted.sessionId, inserted.expiresAt)
        }

        // LF-012 / LN-011 / LN-017 / LN-027 (2): existing row — dispatch by fingerprint.
        val existing = conn.querySingle(
            sql = """
                SELECT session_id, payload_fingerprint, expires_at
                  FROM init_resume_reservations
                 WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND client_request_id = ?
            """.trimIndent(),
            scope.tenantId.value, scope.callerId.value, scope.toolName, scope.clientRequestId,
        ) { rs ->
            InitResumeRow(
                rs.getString("session_id"),
                rs.getString("payload_fingerprint"),
                rs.getInstant("expires_at"),
            )
        } ?: error("Race: insert returned no row but SELECT found nothing for $scope")

        decideInitResume(scope, payloadFingerprint, existing)
    }


    override fun markAwaitingApproval(
        scope: IdempotencyScope,
        now: Instant,
        challenge: ApprovalChallenge?,
    ): Boolean = transactionRunner.inTransaction { conn ->
        val challengeJson = challenge?.let { ApprovalChallengeJson.toJson(it) }
        val newExpires = now.plusSeconds(awaitingApprovalSeconds)
        val updated = conn.executeUpdate(
            """
            UPDATE idempotency_reservations SET
                state = 'AWAITING_APPROVAL',
                claimed = FALSE,
                challenge = ?::jsonb,
                expires_at = ?,
                updated_at = ?
              WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
                AND state = 'PENDING' AND expires_at > ?
            """.trimIndent(),
            challengeJson, newExpires, now,
            scope.tenantId.value, scope.callerId.value, scope.toolName, scope.idempotencyKey.value,
            now,
        )
        updated == 1
    }

    override fun claimApproved(
        scope: IdempotencyScope,
        now: Instant,
    ): IdempotencyClaimOutcome = transactionRunner.inTransaction { conn ->
        when (val decision = decideClaim(scope, now, conn.lockExisting(scope))) {
            is ClaimDecision.Complete -> decision.outcome
            ClaimDecision.TransitionToClaimed -> transitionToClaimed(conn, scope, now)
        }
    }

    private fun transitionToClaimed(
        conn: Connection,
        scope: IdempotencyScope,
        now: Instant,
    ): IdempotencyClaimOutcome.Claimed {
        val newLease = now.plusSeconds(pendingLeaseSeconds)
        val updated = conn.executeUpdate(
            """
            UPDATE idempotency_reservations SET
                state = 'PENDING',
                claimed = TRUE,
                expires_at = ?,
                updated_at = ?
              WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
                AND state = 'AWAITING_APPROVAL'
                AND expires_at > ?
            """.trimIndent(),
            newLease, now,
            scope.tenantId.value, scope.callerId.value, scope.toolName, scope.idempotencyKey.value,
            now,
        )
        check(updated == 1) {
            "Claim-CAS expected to update exactly 1 row within FOR-UPDATE TX, got $updated"
        }
        return IdempotencyClaimOutcome.Claimed(scope, newLease)
    }

    override fun commit(
        scope: IdempotencyScope,
        resultRef: String,
        now: Instant,
        retentionUntil: Instant?,
    ): Boolean = transactionRunner.inTransaction { conn ->
        commitOnConnection(conn, scope, resultRef, now, retentionUntil)
    }

    /**
     * Plan LF-012 / LN-011 / LN-017 / LN-027 § 3.5 + § 6.5 Cross-Store-Komposition: erlaubt
     * [JdbcJobStartTransaction] das `commit` UND ein `JobStore.save`
     * in derselben DB-TX auszufuehren. Caller MUSS im
     * `JdbcTransactionRunner.inTransaction`-Block sein und die
     * Connection durchreichen.
     */
    internal fun commitOnConnection(
        conn: Connection,
        scope: IdempotencyScope,
        resultRef: String,
        now: Instant,
        retentionUntil: Instant?,
    ): Boolean {
        val terminalExpiresAt = terminalExpiry(now, committedRetentionSeconds, retentionUntil)
        val updated = conn.executeUpdate(
            """
            UPDATE idempotency_reservations SET
                state = 'COMMITTED',
                result_ref = ?,
                claimed = FALSE,
                expires_at = ?,
                retention_until = ?,
                updated_at = ?
              WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
                AND state IN ('PENDING','AWAITING_APPROVAL')
            """.trimIndent(),
            resultRef, terminalExpiresAt, terminalExpiresAt, now,
            scope.tenantId.value, scope.callerId.value, scope.toolName, scope.idempotencyKey.value,
        )
        return updated == 1
    }

    override fun deny(
        scope: IdempotencyScope,
        reason: String,
        now: Instant,
    ): Instant? = transactionRunner.inTransaction { conn ->
        val terminalExpiresAt = now.plusSeconds(deniedRetentionSeconds)
        conn.prepareStatement(
            """
            UPDATE idempotency_reservations SET
                state = 'DENIED',
                claimed = FALSE,
                reason = ?,
                expires_at = ?,
                retention_until = ?,
                updated_at = ?
              WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
                AND state IN ('PENDING','AWAITING_APPROVAL')
            RETURNING expires_at
            """.trimIndent(),
        ).use { ps ->
            ps.bindAll(
                reason, terminalExpiresAt, terminalExpiresAt, now,
                scope.tenantId.value, scope.callerId.value, scope.toolName, scope.idempotencyKey.value,
            )
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getInstant("expires_at") else null
            }
        }
    }

    override fun markFailed(
        scope: IdempotencyScope,
        reason: String,
        now: Instant,
        retentionUntil: Instant?,
    ): Boolean = transactionRunner.inTransaction { conn ->
        val terminalExpiresAt = terminalExpiry(now, failedRetentionSeconds, retentionUntil)
        val updated = conn.executeUpdate(
            """
            UPDATE idempotency_reservations SET
                state = 'FAILED',
                claimed = FALSE,
                reason = ?,
                expires_at = ?,
                retention_until = ?,
                updated_at = ?
              WHERE tenant_id = ? AND caller_id = ? AND tool_name = ? AND idempotency_key = ?
                AND state IN ('PENDING','AWAITING_APPROVAL')
            """.trimIndent(),
            reason, terminalExpiresAt, terminalExpiresAt, now,
            scope.tenantId.value, scope.callerId.value, scope.toolName, scope.idempotencyKey.value,
        )
        updated == 1
    }

    override fun cleanupExpired(now: Instant): Int = transactionRunner.inTransaction { conn ->
        // LF-012 / LN-011 / LN-017 / LN-027: regulaerer Pfad loescht NUR terminale Eintraege
        // der idempotency_reservations. Abgelaufene PENDING/
        // AWAITING_APPROVAL bleiben fuer Recovery erhalten — sie werden
        // im naechsten reserve(...) recovered.
        val deletedIdempotency = conn.executeUpdate(
            """
            DELETE FROM idempotency_reservations
              WHERE state IN ('COMMITTED','DENIED','FAILED')
                AND retention_until < ?
            """.trimIndent(),
            now,
        )
        // LF-012 / LN-011 / LN-017 / LN-027: InitResume hat keinen Recovery-Pfad — abgelaufene
        // Eintraege werden direkt geloescht.
        val deletedInit = conn.executeUpdate(
            "DELETE FROM init_resume_reservations WHERE expires_at < ?",
            now,
        )
        deletedIdempotency + deletedInit
    }



    companion object {
        const val DEFAULT_PENDING_LEASE_SECONDS: Long = 60
        const val DEFAULT_AWAITING_APPROVAL_SECONDS: Long = 600
        const val DEFAULT_DENIED_RETENTION_SECONDS: Long = 600
        const val DEFAULT_COMMITTED_RETENTION_SECONDS: Long = 86_400
        const val DEFAULT_FAILED_RETENTION_SECONDS: Long = 600
        const val DEFAULT_INIT_RESUME_SECONDS: Long = 600
    }
}
