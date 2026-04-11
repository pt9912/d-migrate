package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect
import java.sql.Connection

/**
 * Connection-Pool-Wrapper. Owner aller HikariDataSource-Operationen.
 *
 * **Lifecycle (siehe docs/archive/implementation-plan-0.3.0.md §6.12 / §6.18):**
 *
 * - [borrow] liefert eine ausgeliehene [java.sql.Connection]. **HikariCP
 *   wrappt die Connection so, dass `connection.close()` sie NICHT physisch
 *   schließt, sondern in den Pool zurückgibt.** Der Caller MUSS sie mit
 *   `close()` freigeben (idiomatisch via `pool.borrow().use { conn -> ... }`).
 *   Es gibt KEINE separate `return()`-Methode auf diesem Interface — das ist
 *   Hikari-Standard.
 * - [close] schließt den gesamten Pool (am Ende des CLI-Aufrufs).
 *
 * **Connection-Ownership**: Reader und TableLister bekommen den Pool, NICHT
 * eine fertige Connection. Damit vermeidet der Plan doppelten Connection-
 * Besitz beim Mehr-Tabellen-Export.
 */
interface ConnectionPool : AutoCloseable {
    /** Dialekt des zugrundeliegenden Treibers. */
    val dialect: DatabaseDialect

    /**
     * Borgt eine Connection aus dem Pool. Die zurückgegebene Connection ist
     * ein Hikari-Wrapper — `conn.close()` führt sie in den Pool zurück, statt
     * die echte JDBC-Verbindung zu schließen.
     */
    fun borrow(): Connection

    /**
     * Anzahl aktuell ausgeliehener Connections. Wird primär vom
     * `ConnectionLeakTest` verwendet (siehe §6.12), um sicherzustellen, dass
     * `ChunkSequence.close()` alle Connections korrekt zurückgibt.
     */
    fun activeConnections(): Int

    /** Schließt den gesamten Pool. Idempotent. */
    override fun close()
}
