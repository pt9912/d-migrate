package dev.dmigrate.driver.data

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool

/**
 * Port: Auflistung aller Tabellen in der aktuellen Datenbank/Schema.
 *
 * Wird vom CLI verwendet, wenn `--tables` nicht gesetzt ist (siehe
 * docs/archive/implementation-plan-0.3.0.md §3.6 / §6.8). Dieser Port ist bewusst
 * **eigenständig** vom row-streaming-fokussierten [DataReader] (SRP) und
 * wird in 0.6.0 nahtlos durch den vollständigen `SchemaReader` (LF-004)
 * abgelöst.
 *
 * Connection-Ownership: borgt sich die Connection selbst aus dem Pool und
 * gibt sie nach dem Listing sofort zurück (siehe §6.18).
 */
interface TableLister {
    val dialect: DatabaseDialect

    /**
     * Liefert die Namen aller Tabellen im current schema. Reihenfolge ist
     * implementation-defined, aber stabil pro Aufruf.
     *
     * Welche Tabellen "current schema" sind, hängt vom Treiber ab:
     * - PostgreSQL: `information_schema.tables WHERE table_schema = current_schema()`
     * - MySQL:      `information_schema.tables WHERE table_schema = DATABASE()`
     * - SQLite:     `sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'`
     */
    fun listTables(pool: ConnectionPool): List<String>
}
