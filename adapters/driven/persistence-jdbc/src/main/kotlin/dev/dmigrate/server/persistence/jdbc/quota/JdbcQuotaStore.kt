package dev.dmigrate.server.persistence.jdbc.quota

import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.internal.executeUpdate
import dev.dmigrate.server.persistence.jdbc.internal.querySingle
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import dev.dmigrate.server.ports.quota.QuotaStore
import java.sql.Connection
import java.time.Clock

/**
 * Postgres-/JDBC-Implementierung des [QuotaStore]-Vertrags. SQL-Patterns:
 * Plan § 6.8 in `docs/planning/done/ImpPlan-0.9.6-E2.md`.
 *
 * Atomicity:
 * - `reserve` nutzt `INSERT … ON CONFLICT DO UPDATE WHERE limit-check`,
 *   sodass parallele Aufrufe nie das Limit ueberschreiten (Postgres
 *   serialisiert die Konflikt-Updates auf der PK-Zeile).
 * - `release` floored bei 0 via `GREATEST(used - ?, 0)`.
 *
 * Cross-TX-Komposition: jede Methode hat eine `*OnConnection`-Variante
 * (internal), die [JdbcOwnerAwareQuotaService] in einer geteilten
 * DB-TX aufruft. Plan § 6.9: Counter-Decrement und Owner-Status-CAS
 * MUESSEN gemeinsam atomar sein.
 */
open class JdbcQuotaStore(
    private val transactionRunner: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : QuotaStore {

    override fun reserve(key: QuotaKey, amount: Long, limit: Long): QuotaOutcome =
        transactionRunner.inTransaction { conn -> reserveOnConnection(conn, key, amount, limit) }

    override fun release(key: QuotaKey, amount: Long): Long =
        transactionRunner.inTransaction { conn -> releaseOnConnection(conn, key, amount) }

    override fun current(key: QuotaKey): Long =
        transactionRunner.inTransaction { conn -> currentOnConnection(conn, key) }

    /**
     * Plan § 6.8 atomarer Reserve-Pfad: INSERT-or-UPDATE mit Limit-Check
     * im SQL. 0 affected rows ⇒ Limit ueberschritten ⇒ RateLimited mit
     * Follow-up SELECT fuer den aktuellen Counter-Wert.
     */
    internal fun reserveOnConnection(
        conn: Connection,
        key: QuotaKey,
        amount: Long,
        limit: Long,
    ): QuotaOutcome {
        val keyText = QuotaJson.keyToText(key)
        val now = clock.instant()
        val newUsed = conn.querySingle(
            sql = """
                INSERT INTO quota_counters (quota_key, used, updated_at)
                SELECT ?, ?, ?
                  WHERE ? <= ?
                ON CONFLICT (quota_key) DO UPDATE
                  SET used = quota_counters.used + EXCLUDED.used,
                      updated_at = EXCLUDED.updated_at
                  WHERE quota_counters.used + EXCLUDED.used <= ?
                RETURNING used
            """.trimIndent(),
            keyText, amount, now,
            amount, limit,
            limit,
        ) { rs -> rs.getLong("used") }

        return if (newUsed != null) {
            QuotaOutcome.Granted(key = key, amount = amount, newCurrent = newUsed, limit = limit)
        } else {
            val current = currentOnConnection(conn, key)
            QuotaOutcome.RateLimited(key = key, amount = amount, current = current, limit = limit)
        }
    }

    /**
     * Plan § 6.8 release: floored UPDATE. Bei fehlender Zeile (kein
     * Counter angelegt) liefert die Funktion 0 — das ist die im Contract
     * geforderte „release fuer unbekannten Key ist no-op".
     *
     * `open` fuer Failure-Injection-Tests aus Plan § 7.9 Akzeptanz (d)
     * (Crash-Window zwischen Owner-markX und Counter-Decrement).
     */
    internal open fun releaseOnConnection(conn: Connection, key: QuotaKey, amount: Long): Long {
        val keyText = QuotaJson.keyToText(key)
        val now = clock.instant()
        val updated = conn.querySingle(
            sql = """
                UPDATE quota_counters SET
                    used = GREATEST(used - ?, 0),
                    updated_at = ?
                  WHERE quota_key = ?
                RETURNING used
            """.trimIndent(),
            amount, now, keyText,
        ) { rs -> rs.getLong("used") }
        return updated ?: 0L
    }

    internal fun currentOnConnection(conn: Connection, key: QuotaKey): Long {
        val keyText = QuotaJson.keyToText(key)
        val current = conn.querySingle(
            sql = "SELECT used FROM quota_counters WHERE quota_key = ?",
            keyText,
        ) { rs -> rs.getLong("used") }
        return current ?: 0L
    }
}
