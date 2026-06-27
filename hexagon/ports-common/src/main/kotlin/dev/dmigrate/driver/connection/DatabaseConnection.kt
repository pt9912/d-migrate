package dev.dmigrate.driver.connection

/**
 * Neutrales Verbindungs-Handle der Ports-Schicht (ADR 0022).
 *
 * Ersetzt die bis dahin durchgereichte JDBC-`Connection` in den Port-Signaturen
 * ([ConnectionPool.borrow] und dem `AtomicSequencePreserveExecutor`), damit
 * `hexagon:ports-*` JDBC-frei bleibt — Technologie lebt in den Adaptern.
 *
 * Bewusst **minimal** (ADR 0022, Option A; Leitplanke gegen eine Leaky Abstraction):
 * das Handle ist im Kern opak. Die JDBC-gebundene Implementierung lebt in
 * `adapters:driven:driver-common` (`JdbcDatabaseConnection`) und trägt die reale
 * Connection; Adapter-Konsumenten, die echtes JDBC brauchen (Reader/Writer/Lister,
 * Executor-Implementierungen), unwrappen sie dort. Einzige Zustands-Fähigkeit auf der
 * neutralen Schnittstelle ist [autoCommit] — sie trägt die Owned-Transaction-Prüfung
 * des `AtomicSequencePreserveExecutor`, die im Port-Layer (ohne JDBC) verbleiben soll.
 */
interface DatabaseConnection : AutoCloseable {
    /**
     * `true`, wenn die Verbindung **nicht** in einer umschließenden Transaktion läuft
     * (Auto-Commit-Modus). Grundlage von
     * `AtomicSequencePreserveExecutor.requireOwnedConnection`: Der Executor verlangt eine
     * eigene, nicht eingeschlossene Verbindung (`autoCommit == true` beim Eintritt).
     */
    val autoCommit: Boolean
}
