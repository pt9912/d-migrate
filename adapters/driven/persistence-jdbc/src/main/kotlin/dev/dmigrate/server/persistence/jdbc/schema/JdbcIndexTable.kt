package dev.dmigrate.server.persistence.jdbc.schema

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.internal.bindAll
import dev.dmigrate.server.persistence.jdbc.internal.executeUpdate
import dev.dmigrate.server.persistence.jdbc.internal.querySingle
import java.time.Instant

/** Die extrahierten Spalten eines Index-Eintrags neben seiner JSONB-Form. */
internal data class IndexRow(
    val tenantId: TenantId,
    val id: String,
    val json: String,
    val jobRef: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * Zugriff auf eine Index-Tabelle der Form `(tenant_id, <idColumn>, entry, job_ref, created_at, expires_at)`.
 * [table], [idColumn] und die Schluessel aus [IndexQuery.entryFields] sind Konstanten der Stores, nie Eingaben.
 */
internal class JdbcIndexTable<E : Any>(
    private val transactionRunner: JdbcTransactionRunner,
    private val table: String,
    private val idColumn: String,
    private val decode: (String) -> E,
) {

    fun upsert(row: IndexRow) {
        transactionRunner.inTransaction { conn ->
            conn.executeUpdate(
                sql = """
                    INSERT INTO $table (tenant_id, $idColumn, entry, job_ref, created_at, expires_at)
                    VALUES (?, ?, ?::jsonb, ?, ?, ?)
                    ON CONFLICT (tenant_id, $idColumn) DO UPDATE SET
                      entry = EXCLUDED.entry,
                      job_ref = EXCLUDED.job_ref,
                      created_at = EXCLUDED.created_at,
                      expires_at = EXCLUDED.expires_at
                """.trimIndent(),
                row.tenantId.value, row.id, row.json, row.jobRef, row.createdAt, row.expiresAt,
            )
        }
    }

    fun find(tenantId: TenantId, id: String): E? = transactionRunner.inTransaction { conn ->
        conn.querySingle(
            sql = "SELECT entry::text AS entry_text FROM $table WHERE tenant_id = ? AND $idColumn = ?",
            tenantId.value, id,
        ) { rs -> decode(rs.getString("entry_text")) }
    }

    fun select(tenantId: TenantId, query: IndexQuery): List<E> = transactionRunner.inTransaction { conn ->
        val statement = indexSelect(table, idColumn, tenantId, query)
        conn.prepareStatement(statement.sql).use { ps ->
            ps.bindAll(*statement.parameters.toTypedArray())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(decode(rs.getString("entry_text")))
                }
            }
        }
    }

    fun deleteExpired(now: Instant): Int = transactionRunner.inTransaction { conn ->
        conn.executeUpdate("DELETE FROM $table WHERE expires_at < ?", now)
    }
}
