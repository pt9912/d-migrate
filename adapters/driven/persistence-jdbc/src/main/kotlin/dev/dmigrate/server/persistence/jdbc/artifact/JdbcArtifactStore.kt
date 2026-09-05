package dev.dmigrate.server.persistence.jdbc.artifact

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.internal.bindAll
import dev.dmigrate.server.persistence.jdbc.internal.executeUpdate
import dev.dmigrate.server.persistence.jdbc.internal.querySingle
import dev.dmigrate.server.persistence.jdbc.job.paginate
import dev.dmigrate.server.ports.ArtifactListFilter
import dev.dmigrate.server.ports.ArtifactStore
import java.sql.Connection
import java.time.Instant

/**
 * Postgres-/JDBC-Implementierung des [ArtifactStore]-Vertrags
 * (ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md AP3).
 * SQL-Patterns analog `JdbcJobStore`/`JdbcSchemaStore`: eine JSONB-Spalte
 * (`record`) traegt den vollstaendigen [ArtifactRecord], extrahierte
 * Spalten (`kind`, `owner_principal_id`, `job_ref`, `created_at`,
 * `expires_at`) tragen Filter/Sortierung.
 *
 * [deleteExpiredRecords] (AE-4) ueberschreibt den [ArtifactStore]-Default
 * (der nur zaehlt) korrekt: JSONB traegt den vollen Record, der
 * Retention-Sweeper braucht ihn, um Byte-Quotas freizugeben und die
 * Content-Store-Payloads (Datei/S3) zu loeschen.
 */
class JdbcArtifactStore(
    private val transactionRunner: JdbcTransactionRunner,
) : ArtifactStore {

    override fun save(record: ArtifactRecord): ArtifactRecord = transactionRunner.inTransaction { conn ->
        conn.executeUpdate(
            sql = """
                INSERT INTO artifact_records
                  (tenant_id, artifact_id, record, kind, owner_principal_id, job_ref, created_at, expires_at)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, artifact_id) DO UPDATE SET
                  record = EXCLUDED.record,
                  kind = EXCLUDED.kind,
                  owner_principal_id = EXCLUDED.owner_principal_id,
                  job_ref = EXCLUDED.job_ref,
                  created_at = EXCLUDED.created_at,
                  expires_at = EXCLUDED.expires_at
            """.trimIndent(),
            record.tenantId.value, record.managedArtifact.artifactId, ArtifactRecordJson.toJson(record),
            record.kind.name, record.ownerPrincipalId.value, record.jobRef,
            record.managedArtifact.createdAt, record.managedArtifact.expiresAt,
        )
        record
    }

    override fun findById(tenantId: TenantId, artifactId: String): ArtifactRecord? =
        transactionRunner.inTransaction { conn -> conn.findRecord(tenantId, artifactId) }

    private fun Connection.findRecord(tenantId: TenantId, artifactId: String): ArtifactRecord? = querySingle(
        sql = """
            SELECT record::text AS record_text
              FROM artifact_records
             WHERE tenant_id = ? AND artifact_id = ?
        """.trimIndent(),
        tenantId.value, artifactId,
    ) { rs -> ArtifactRecordJson.fromJson(rs.getString("record_text")) }

    override fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId?,
        kindFilter: ArtifactKind?,
    ): PageResult<ArtifactRecord> = transactionRunner.inTransaction { conn ->
        val items = conn.fetchSorted(
            tenantId = tenantId,
            ownerFilterValue = ownerFilter?.value,
            kindFilterValue = kindFilter?.name,
            jobRefFilter = null,
            createdAfter = null,
            createdBefore = null,
            sortDescending = false,
        )
        paginate(items, page)
    }

    override fun list(
        tenantId: TenantId,
        filter: ArtifactListFilter,
        page: PageRequest,
    ): PageResult<ArtifactRecord> = transactionRunner.inTransaction { conn ->
        val items = conn.fetchSorted(
            tenantId = tenantId,
            ownerFilterValue = filter.ownerFilter?.value,
            kindFilterValue = filter.kindFilter?.name,
            jobRefFilter = filter.jobRef,
            createdAfter = filter.createdAfter,
            createdBefore = filter.createdBefore,
            sortDescending = true,
        )
        paginate(items, page)
    }

    private fun Connection.fetchSorted(
        tenantId: TenantId,
        ownerFilterValue: String?,
        kindFilterValue: String?,
        jobRefFilter: String?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        sortDescending: Boolean,
    ): List<ArtifactRecord> {
        val direction = if (sortDescending) "DESC" else "ASC"
        val sql = """
            SELECT record::text AS record_text
              FROM artifact_records
             WHERE tenant_id = ?
               AND (?::text IS NULL OR owner_principal_id = ?)
               AND (?::text IS NULL OR kind = ?)
               AND (?::text IS NULL OR job_ref = ?)
               AND (?::timestamptz IS NULL OR created_at >= ?)
               AND (?::timestamptz IS NULL OR created_at <= ?)
             ORDER BY created_at $direction, artifact_id ASC
        """.trimIndent()
        return prepareStatement(sql).use { ps ->
            ps.bindAll(
                tenantId.value,
                ownerFilterValue, ownerFilterValue,
                kindFilterValue, kindFilterValue,
                jobRefFilter, jobRefFilter,
                createdAfter, createdAfter,
                createdBefore, createdBefore,
            )
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(ArtifactRecordJson.fromJson(rs.getString("record_text")))
                }
            }
        }
    }

    override fun deleteExpired(now: Instant): Int = deleteExpiredRecords(now).size

    override fun deleteExpiredRecords(now: Instant): List<ArtifactRecord> = transactionRunner.inTransaction { conn ->
        conn.prepareStatement(
            """
                DELETE FROM artifact_records
                 WHERE expires_at < ?
                RETURNING record::text AS record_text
            """.trimIndent(),
        ).use { ps ->
            ps.bindAll(now)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(ArtifactRecordJson.fromJson(rs.getString("record_text")))
                }
            }
        }
    }
}
