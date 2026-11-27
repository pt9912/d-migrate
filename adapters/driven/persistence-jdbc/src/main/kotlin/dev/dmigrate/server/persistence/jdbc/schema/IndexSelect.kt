package dev.dmigrate.server.persistence.jdbc.schema

import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * Auswahl fuer [JdbcIndexTable.select]. [entryFields] vergleicht Felder der JSONB-Spalte auf Gleichheit; ein
 * `null`-Wert filtert nicht.
 */
internal data class IndexQuery(
    val jobRef: String? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val entryFields: Map<String, String?> = emptyMap(),
    val newestFirst: Boolean = true,
)

/** Ein SELECT mit seinen Bind-Werten in Reihenfolge der Platzhalter. */
internal data class IndexStatement(val sql: String, val parameters: List<Any?>)

/**
 * Das SELECT ueber eine Index-Tabelle. [table], [idColumn] und die Schluessel aus [IndexQuery.entryFields] sind
 * Konstanten der Stores; Werte gehen nur als Bind-Parameter in die Anfrage.
 */
internal fun indexSelect(table: String, idColumn: String, tenantId: TenantId, query: IndexQuery): IndexStatement {
    val fields = query.entryFields.entries.filter { it.value != null }
    val fieldClauses = fields.joinToString("") { " AND entry->>'${it.key}' = ?" }
    val direction = if (query.newestFirst) "DESC" else "ASC"
    val sql = """
        SELECT entry::text AS entry_text
          FROM $table
         WHERE tenant_id = ?
           AND (?::text IS NULL OR job_ref = ?)
           AND (?::timestamptz IS NULL OR created_at >= ?)
           AND (?::timestamptz IS NULL OR created_at <= ?)$fieldClauses
         ORDER BY created_at $direction, $idColumn ASC
    """.trimIndent()
    val parameters = listOf<Any?>(
        tenantId.value,
        query.jobRef, query.jobRef,
        query.createdAfter, query.createdAfter,
        query.createdBefore, query.createdBefore,
    ) + fields.map { it.value }
    return IndexStatement(sql, parameters)
}
