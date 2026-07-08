package dev.dmigrate.driver.connection

import java.sql.Connection

/**
 * JDBC-gebundene [DatabaseConnection] (ADR 0022, Option A).
 *
 * Wrappt die reale — typischerweise Hikari-gepoolte — [java.sql.Connection]. [close]
 * delegiert an die gewrappte Connection: bei einer Hikari-Connection gibt das die
 * Verbindung **in den Pool zurück**, statt sie physisch zu schließen (Hikari-Standard,
 * vgl. `ConnectionPool`).
 *
 * Adapter-Code, der echtes JDBC braucht (Reader/Writer/Lister sowie die
 * dialekt-spezifischen `AtomicSequencePreserveExecutor`-Implementierungen), holt die
 * reale Connection über [connection] bzw. die Erweiterung [DatabaseConnection.asJdbc].
 * Dieser Unwrap ist **nur im Adapter-Layer** zulässig — die Ports selbst sehen nur das
 * neutrale Handle.
 */
class JdbcDatabaseConnection(val connection: Connection) : DatabaseConnection {
    override val autoCommit: Boolean get() = connection.autoCommit

    override fun close() {
        connection.close()
    }
}

/**
 * Unwrap auf die reale JDBC-[Connection]. Nur im Adapter-Layer aufrufen (ADR 0022):
 * Die Ports reichen das neutrale [DatabaseConnection] herum; erst der Adapter, der ein
 * konkretes JDBC braucht, packt es hier aus.
 *
 * @throws IllegalStateException wenn das Handle keine [JdbcDatabaseConnection] ist.
 */
fun DatabaseConnection.asJdbc(): Connection =
    (this as? JdbcDatabaseConnection
        ?: error("DatabaseConnection ist keine JdbcDatabaseConnection: ${this::class.qualifiedName}"))
        .connection
