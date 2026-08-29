package dev.dmigrate.driver

/**
 * Declares which schema object types a target dialect can natively
 * generate, rewrite, or must skip/flag as manual action.
 *
 * Resolved per [DatabaseDialect] via [DialectCapabilities.forDialect].
 * Generators consume this to make consistent generate/skip/action-required
 * decisions without scattered `when (dialect)` checks.
 */
data class DialectCapabilities(
    val supportsViews: Boolean,
    val supportsFunctions: Boolean,
    val supportsProcedures: Boolean,
    val supportsTriggers: Boolean,
    val supportsSequences: Boolean,
    val supportsCustomTypes: Boolean,
    val supportsPartitioning: Boolean,
    /** Whether cross-dialect routine bodies can be rewritten (placeholder for future rewrite engine). */
    val supportsRoutineRewrite: Boolean = false,
    /** Whether the dialect supports disabling FK checks during import (MySQL/SQLite: yes, PostgreSQL: no). */
    val supportsDisableFkChecks: Boolean = false,
    /** Whether the dialect supports `triggerMode=disable` (PostgreSQL: yes, others: no). */
    val supportsTriggerDisable: Boolean = false,
    /** Whether the dialect supports `triggerMode=strict` (PostgreSQL: yes, others: no). */
    val supportsTriggerStrict: Boolean = false,
    /** Whether the dialect supports a `--schema` parameter for namespace scoping. */
    val supportsSchemaParameter: Boolean = false,
    /**
     * Whether partition children are addressable as standalone relations
     * (PostgreSQL: yes — `SELECT … FROM child_partition`; MySQL: no — children
     * are sub-objects reachable only via `SELECT … FROM parent PARTITION (p)`).
     * Gates the LN-008 per-child parallel fan-out (ADR 0032): only when `true`
     * may a partitioned parent be transferred/exported one child at a time.
     */
    val partitionChildrenAreTables: Boolean = false,
    /**
     * Batch-Trenner für Skript-Darstellungen (Dateiausgabe, Tool-Export):
     * T-SQL verlangt, dass `CREATE VIEW`/Routinen allein in einem Batch stehen,
     * und Clients wie sqlcmd/SSMS/Flyway trennen Batches nur an `GO`-Zeilen.
     * `null` = keine Batch-Semantik (Statements stehen mit `;` hintereinander).
     */
    val batchSeparator: String? = null,
    /**
     * Praeambel-Batch am Anfang einer Skript-Darstellung. SQL Server verlangt
     * fuer gefilterte Indizes (und indizierte Sichten, Computed-Column-Indizes,
     * Spatial-Indizes) bestimmte SET-Optionen; `sqlcmd` verbindet sich per
     * Default mit `QUOTED_IDENTIFIER OFF` und laesst ein `CREATE INDEX … WHERE`
     * sonst mit Msg 1934 scheitern. Der Block macht das Skript
     * client-unabhaengig. `null` = keine Praeambel.
     */
    val scriptPreamble: String? = null,
    /**
     * Ob `onConflict=skip` einen Primärschlüssel braucht. PostgreSQL/MySQL/SQLite
     * haben mit `ON CONFLICT DO NOTHING`/`INSERT IGNORE` eine schlüsselfreie
     * Form; SQL Server muss dafür `MERGE` mit einem Schlüsselprädikat bauen.
     * Der Transfer-Preflight prüft das, bevor eine Verbindung aufgebaut wird.
     */
    val requiresPrimaryKeyForSkip: Boolean = false,
    /**
     * Ob der Dialekt Nicht-Schluesselspalten eines abdeckenden Index traegt
     * (`INCLUDE (…)`). PostgreSQL ab 11 und SQL Server: ja; MySQL und SQLite
     * kennen die Form nicht und lassen sie beim Generate fallen.
     */
    val supportsIndexIncludeColumns: Boolean = false,
    /**
     * Ob der Dialekt steuert, welcher Index die Ablage der Tabelle bildet
     * (`CREATE CLUSTERED INDEX`). Nur SQL Server. MySQL legt sie in InnoDB
     * unveraenderlich auf den Primaerschluessel, SQLite auf die `rowid`, und
     * PostgreSQL kennt `CLUSTER` nur als einmalige Reorganisation.
     */
    val supportsClusteredIndexes: Boolean = false,
    /**
     * Ob der Dialekt einen Volltext-Index **benennt**. PostgreSQL, MySQL und
     * SQLite tun es; SQL Server nicht — `CREATE FULLTEXT INDEX ON t (…)` kennt
     * keinen Namen, und der Katalog fuehrt keinen.
     *
     * Der Reverse muss dort synthetisieren. Ohne diese Faehigkeit ginge der
     * erfundene Name in den Fingerabdruck ein und liesse jeden Round-Trip
     * driften, obwohl sich nichts geaendert hat.
     */
    val namesFullTextIndexes: Boolean = true,
) {
    companion object {
        /**
         * SET-Optionen, die SQL Server fuer gefilterte Indizes verlangt (und die
         * `sqlcmd` nicht per Default setzt). Eigener Batch, damit sie fuer alle
         * folgenden Batches der Sitzung gelten.
         */
        private val MSSQL_SCRIPT_PREAMBLE = listOf(
            "SET ANSI_NULLS ON;",
            "SET ANSI_PADDING ON;",
            "SET ANSI_WARNINGS ON;",
            "SET ARITHABORT ON;",
            "SET CONCAT_NULL_YIELDS_NULL ON;",
            "SET NUMERIC_ROUNDABORT OFF;",
            "SET QUOTED_IDENTIFIER ON;",
        ).joinToString("\n")

        fun forDialect(dialect: DatabaseDialect): DialectCapabilities = when (dialect) {
            DatabaseDialect.POSTGRESQL -> DialectCapabilities(
                supportsViews = true,
                supportsFunctions = true,
                supportsProcedures = true,
                supportsTriggers = true,
                supportsSequences = true,
                supportsCustomTypes = true,
                supportsPartitioning = true,
                supportsDisableFkChecks = false,
                supportsTriggerDisable = true,
                supportsTriggerStrict = true,
                supportsSchemaParameter = true,
                partitionChildrenAreTables = true,
                supportsIndexIncludeColumns = true,
            )
            DatabaseDialect.MYSQL -> DialectCapabilities(
                supportsViews = true,
                supportsFunctions = true,
                supportsProcedures = true,
                supportsTriggers = true,
                supportsSequences = false,
                supportsCustomTypes = false,
                supportsPartitioning = true,
                supportsDisableFkChecks = true,
                supportsTriggerDisable = false,
                supportsTriggerStrict = false,
                supportsSchemaParameter = true,
            )
            DatabaseDialect.SQLITE -> DialectCapabilities(
                supportsViews = true,
                supportsFunctions = false,
                supportsProcedures = false,
                supportsTriggers = true,
                supportsSequences = false,
                supportsCustomTypes = false,
                supportsPartitioning = false,
                supportsDisableFkChecks = true,
                supportsTriggerDisable = false,
                supportsTriggerStrict = false,
                supportsSchemaParameter = false,
            )
            // Objekttyp-Flags = Faehigkeiten von SQL Server (2017+, ADR 0047);
            // die Import-Modus-Flags (FK-/Trigger-Disable) beschreiben den
            // Werkzeug-Pfad, den d-migrate fuer MSSQL nicht faehrt.
            DatabaseDialect.MSSQL -> DialectCapabilities(
                supportsViews = true,
                supportsFunctions = true,
                supportsProcedures = true,
                supportsTriggers = true,
                supportsSequences = true,
                supportsCustomTypes = false,
                supportsPartitioning = true,
                supportsDisableFkChecks = false,
                supportsTriggerDisable = false,
                supportsTriggerStrict = false,
                supportsSchemaParameter = true,
                partitionChildrenAreTables = false,
                batchSeparator = "GO",
                scriptPreamble = MSSQL_SCRIPT_PREAMBLE,
                requiresPrimaryKeyForSkip = true,
                supportsIndexIncludeColumns = true,
                supportsClusteredIndexes = true,
                namesFullTextIndexes = false,
            )
        }
    }
}
