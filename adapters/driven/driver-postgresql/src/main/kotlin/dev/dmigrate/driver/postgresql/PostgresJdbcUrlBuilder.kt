package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder

/**
 * PostgreSQL [JdbcUrlBuilder].
 *
 * LF-008 / LN-009 / LN-010: Default-Parameter:
 * - `ApplicationName=d-migrate` — sichtbar in `pg_stat_activity`,
 *   hilft beim Identifizieren der Verbindung in der DB
 * - `reWriteBatchedInserts=true` — pgjdbc schreibt `addBatch`/`executeBatch`-Einzelzeilen-
 *   `INSERT … VALUES (…)` serverseitig in **Multi-Row-INSERTs** um (deutlich höherer
 *   Import-Durchsatz). Das **direkte Pendant** zu MySQLs `rewriteBatchedStatements=true`
 *   (im MySQL-Builder bereits gesetzt); für PG fehlte es. `bindRow`/`valuePlaceholder`
 *   bleiben unberührt (Geometrie-Wrap/JSON/Enum/Array erhalten); Shapes, die pgjdbc nicht
 *   umschreiben kann (z. B. `RETURNING` im UPSERT-Pfad), fallen sicher auf den normalen
 *   Batch zurück — kein Korrektheitsrisiko (siehe `docs/planning/open/import-throughput-copy-path.md`,
 *   Schritt 0).
 *
 * **Bootstrap**: Wird via [PostgresDriver.register] in der globalen
 * [JdbcUrlBuilderRegistry] registriert. Es gibt KEINE automatische
 * Self-Registration beim Klassenladen — siehe [PostgresDriver]-KDoc.
 */
class PostgresJdbcUrlBuilder : JdbcUrlBuilder {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun defaultParams(): Map<String, String> = mapOf(
        "ApplicationName" to "d-migrate",
        "reWriteBatchedInserts" to "true",
    )

    override fun baseJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == DatabaseDialect.POSTGRESQL) {
            "PostgresJdbcUrlBuilder cannot build URL for ${config.dialect}"
        }
        val port = config.port ?: 5432
        return "jdbc:postgresql://${config.host}:$port/${config.database}"
    }

}
