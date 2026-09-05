package dev.dmigrate.server.persistence.jdbc.schema

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.internal.bindAll
import dev.dmigrate.server.persistence.jdbc.internal.executeUpdate
import dev.dmigrate.server.persistence.jdbc.internal.querySingle
import dev.dmigrate.server.persistence.jdbc.job.paginate
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaListFilter
import dev.dmigrate.server.ports.SchemaRegisterOutcome
import dev.dmigrate.server.ports.SchemaStore
import java.sql.Connection
import java.time.Instant

/**
 * Postgres-/JDBC-Implementierung des [SchemaStore]-Vertrags
 * (ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md AP2).
 * SQL-Patterns analog `JdbcJobStore`: eine JSONB-Spalte (`entry`) traegt
 * den vollstaendigen [SchemaIndexEntry], extrahierte Spalten
 * (`job_ref`, `created_at`, `expires_at`) tragen Filter/Sortierung.
 *
 * [register] nutzt `INSERT ... ON CONFLICT DO NOTHING RETURNING *`, NICHT
 * `SELECT ... FOR UPDATE` + bedingtes `INSERT` (AE-2-Review-Korrektur):
 * `SELECT ... FOR UPDATE` kann keine Zeile sperren, die noch nicht
 * existiert, also loest es die Replay-Race nicht, die
 * `InMemorySchemaStore.register()`s `ConcurrentHashMap.compute()` per
 * Bucket-Sperre bereits loest. `SELECT ... FOR UPDATE` kommt hier nur noch
 * zur Konfliktaufloesung zum Einsatz, nachdem die INSERT-Runde bereits
 * feststeht, dass eine Zeile existiert.
 */
class JdbcSchemaStore(
    private val transactionRunner: JdbcTransactionRunner,
) : SchemaStore {

    override fun save(entry: SchemaIndexEntry): SchemaIndexEntry = transactionRunner.inTransaction { conn ->
        conn.executeUpdate(
            sql = """
                INSERT INTO schema_index_entries
                  (tenant_id, schema_id, entry, job_ref, created_at, expires_at)
                VALUES (?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (tenant_id, schema_id) DO UPDATE SET
                  entry = EXCLUDED.entry,
                  job_ref = EXCLUDED.job_ref,
                  created_at = EXCLUDED.created_at,
                  expires_at = EXCLUDED.expires_at
            """.trimIndent(),
            entry.tenantId.value, entry.schemaId, SchemaIndexEntryJson.toJson(entry),
            entry.jobRef, entry.createdAt, entry.expiresAt,
        )
        entry
    }

    override fun findById(tenantId: TenantId, schemaId: String): SchemaIndexEntry? =
        transactionRunner.inTransaction { conn -> conn.findEntry(tenantId, schemaId) }

    private fun Connection.findEntry(tenantId: TenantId, schemaId: String): SchemaIndexEntry? = querySingle(
        sql = """
            SELECT entry::text AS entry_text
              FROM schema_index_entries
             WHERE tenant_id = ? AND schema_id = ?
        """.trimIndent(),
        tenantId.value, schemaId,
    ) { rs -> SchemaIndexEntryJson.fromJson(rs.getString("entry_text")) }

    override fun list(tenantId: TenantId, page: PageRequest): PageResult<SchemaIndexEntry> =
        transactionRunner.inTransaction { conn ->
            val items = conn.fetchSorted(
                tenantId = tenantId,
                jobRefFilter = null,
                createdAfter = null,
                createdBefore = null,
                sortDescending = false,
            )
            paginate(items, page)
        }

    override fun list(
        tenantId: TenantId,
        filter: SchemaListFilter,
        page: PageRequest,
    ): PageResult<SchemaIndexEntry> = transactionRunner.inTransaction { conn ->
        val items = conn.fetchSorted(
            tenantId = tenantId,
            jobRefFilter = filter.jobRef,
            createdAfter = filter.createdAfter,
            createdBefore = filter.createdBefore,
            // AE-2-Vorbild JdbcJobStore.fetchSorted: legacy list() ASC, gefiltertes list() DESC.
            sortDescending = true,
        )
        paginate(items, page)
    }

    private fun Connection.fetchSorted(
        tenantId: TenantId,
        jobRefFilter: String?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        sortDescending: Boolean,
    ): List<SchemaIndexEntry> {
        val direction = if (sortDescending) "DESC" else "ASC"
        val sql = """
            SELECT entry::text AS entry_text
              FROM schema_index_entries
             WHERE tenant_id = ?
               AND (?::text IS NULL OR job_ref = ?)
               AND (?::timestamptz IS NULL OR created_at >= ?)
               AND (?::timestamptz IS NULL OR created_at <= ?)
             ORDER BY created_at $direction, schema_id ASC
        """.trimIndent()
        return prepareStatement(sql).use { ps ->
            ps.bindAll(
                tenantId.value,
                jobRefFilter, jobRefFilter,
                createdAfter, createdAfter,
                createdBefore, createdBefore,
            )
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(SchemaIndexEntryJson.fromJson(rs.getString("entry_text")))
                }
            }
        }
    }

    override fun deleteExpired(now: Instant): Int = transactionRunner.inTransaction { conn ->
        conn.executeUpdate("DELETE FROM schema_index_entries WHERE expires_at < ?", now)
    }

    override fun register(entry: SchemaIndexEntry): SchemaRegisterOutcome = transactionRunner.inTransaction { conn ->
        val inserted = conn.querySingle(
            sql = """
                INSERT INTO schema_index_entries
                  (tenant_id, schema_id, entry, job_ref, created_at, expires_at)
                VALUES (?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (tenant_id, schema_id) DO NOTHING
                RETURNING entry::text AS entry_text
            """.trimIndent(),
            entry.tenantId.value, entry.schemaId, SchemaIndexEntryJson.toJson(entry),
            entry.jobRef, entry.createdAt, entry.expiresAt,
        ) { rs -> SchemaIndexEntryJson.fromJson(rs.getString("entry_text")) }

        if (inserted != null) return@inTransaction SchemaRegisterOutcome.Registered(inserted)
        conn.resolveRegisterConflict(entry)
    }

    /** Nur erreicht, wenn die INSERT-Runde bereits feststellte, dass die Zeile existiert (siehe Klassendoc). */
    private fun Connection.resolveRegisterConflict(entry: SchemaIndexEntry): SchemaRegisterOutcome {
        val existing = querySingle(
            sql = """
                SELECT entry::text AS entry_text
                  FROM schema_index_entries
                 WHERE tenant_id = ? AND schema_id = ?
                 FOR UPDATE
            """.trimIndent(),
            entry.tenantId.value, entry.schemaId,
        ) { rs -> SchemaIndexEntryJson.fromJson(rs.getString("entry_text")) }
            ?: error(
                "register(): row for (${entry.tenantId.value}, ${entry.schemaId}) vanished between " +
                    "INSERT ... ON CONFLICT DO NOTHING and the recovery SELECT",
            )
        return if (existing.artifactRef == entry.artifactRef) {
            SchemaRegisterOutcome.AlreadyRegistered(existing)
        } else {
            SchemaRegisterOutcome.Conflict(existing, entry)
        }
    }
}
