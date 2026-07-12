package dev.dmigrate.driver.data

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool

/**
 * Port: Tabellen-Import über eine Session-basierte Push-API.
 *
 * Symmetrisches Gegenstück zu [DataReader] (Pull-basierter Lese-Port).
 *
 * Connection-Ownership (symmetrisch zu DataReader, §6.18):
 * - Der Writer bekommt einen [ConnectionPool], NICHT eine fertige Connection.
 * - Pro [openTable]-Aufruf borgt der Writer sich eine eigene Connection
 *   und hält sie für die Lifetime der zurückgegebenen [TableImportSession].
 * - Der Caller besitzt nie eine JDBC-Connection.
 *
 * Konkrete Treiber (PostgreSQL, MySQL, SQLite) implementieren dieses
 * Interface direkt (kein AbstractJdbcDataWriter in 0.4.0 — die
 * Writer-Mechanik ist hinreichend dialektspezifisch).
 */
interface DataWriter {
    val dialect: DatabaseDialect

    /**
     * Liefert die dialektspezifische Sequence-/Trigger-Synchronisation.
     * Es gibt bewusst KEINE Default-No-Op-Implementierung: jeder Treiber
     * muss den Vertrag explizit erfüllen.
     */
    fun schemaSync(): SchemaSync

    /**
     * Bereitet einen Tabellen-Import vor: prüft Spalten, baut die
     * PreparedStatement-INSERT-Vorlage und liefert eine [TableImportSession]
     * zurück, die der Caller pro Chunk benutzt und am Ende schließt.
     *
     * Borgt sich pro Aufruf eine Connection aus dem Pool.
     * Die Connection wird in der Session gehalten und beim
     * `close()` zurückgegeben.
     *
     * **Cleanup-Vertrag (H1)**: Führt vor dem Return ggf.
     * `disableTriggers(...)` oder `assertNoUserTriggers(...)` aus. Wirft
     * die Methode danach noch, MUSS der Writer intern aufräumen (Trigger
     * re-enablen, Connection zurückgeben) bevor die Exception
     * weiterreicht. Sekundäre Cleanup-Fehler werden per
     * `addSuppressed()` angehängt.
     *
     * @throws dev.dmigrate.core.data.ImportSchemaMismatchException bei
     *   Target-seitigen Metadaten-/Schemafehlern
     */
    fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession

    /**
     * LN-013: setzt die gegebenen Tabellen FK-sicher auf **leer** zurück
     * (Clean-Load-Kompensation für `data import`/`data transfer --atomic`).
     *
     * Wird bei einem Fehler eines `--atomic`-Laufs über den **vollständigen**
     * Operations-Tabellensatz aufgerufen, um „alle Tabellen oder keine" auf einer
     * bekannt-leeren Basis herzustellen. Truncatet alle Tabellen in einem borrow
     * FK-sicher (Postgres `TRUNCATE … CASCADE`; MySQL/SQLite FK-Checks aus + `DELETE`).
     *
     * Vertrag: **nicht-transaktional** (wie `--truncate`, siehe [ImportOptions]) und
     * **idempotent** (leere Tabelle → no-op) — deshalb resume-sicher, weil ein
     * `--atomic`-Lauf ohnehin per erzwungenem `--truncate` sauber startet.
     *
     * Default-Delegation: ohne Dialekt-Impl wirft die Methode
     * [UnsupportedOperationException] (analog [DataReader.streamTable] mit
     * `resumeMarker`) — die realen Treiber überschreiben sie.
     */
    fun truncateTables(pool: ConnectionPool, tables: List<String>): Unit =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support truncateTables (LN-013 --atomic compensation)"
        )
}
