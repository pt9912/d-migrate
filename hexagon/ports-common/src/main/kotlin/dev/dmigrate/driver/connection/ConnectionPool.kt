package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect

/**
 * Connection-Pool-Wrapper. Owner aller HikariDataSource-Operationen.
 *
 * **Lifecycle (siehe docs/archive/implementation-plan-0.3.0.md §6.12 / §6.18):**
 *
 * - [borrow] liefert ein ausgeliehenes [DatabaseConnection] (neutrales Handle,
 *   ADR 0022). **HikariCP wrappt die zugrundeliegende Verbindung so, dass
 *   `close()` sie NICHT physisch schließt, sondern in den Pool zurückgibt.** Der
 *   Caller MUSS sie mit `close()` freigeben (idiomatisch via
 *   `pool.borrow().use { handle -> ... }`). Adapter, die echtes JDBC brauchen,
 *   unwrappen via `handle.asJdbc()`. Es gibt KEINE separate `return()`-Methode —
 *   das ist Hikari-Standard.
 * - [close] schließt den gesamten Pool (am Ende des CLI-Aufrufs).
 *
 * **Connection-Ownership**: Reader und TableLister bekommen den Pool, NICHT
 * ein fertiges Handle. Damit vermeidet der Plan doppelten Connection-Besitz
 * beim Mehr-Tabellen-Export.
 */
interface ConnectionPool : AutoCloseable {
    /** Dialekt des zugrundeliegenden Treibers. */
    val dialect: DatabaseDialect

    /**
     * Borgt ein [DatabaseConnection] aus dem Pool. `close()` auf dem Handle
     * führt die zugrundeliegende Verbindung in den Pool zurück, statt sie
     * physisch zu schließen (Hikari-Standard).
     */
    fun borrow(): DatabaseConnection

    /**
     * Anzahl aktuell ausgeliehener Connections. Wird primär vom
     * `ConnectionLeakTest` verwendet (siehe §6.12), um sicherzustellen, dass
     * `ChunkSequence.close()` alle Connections korrekt zurückgibt.
     */
    fun activeConnections(): Int

    /** Schließt den gesamten Pool. Idempotent. */
    override fun close()
}
