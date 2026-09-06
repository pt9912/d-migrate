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
    /**
     * Ob der Dialekt einen Bitmap-Index als eigene Zugriffsmethode traegt.
     * Nur Oracle (`CREATE BITMAP INDEX`); die uebrigen vier legen einen
     * gewoehnlichen Index an und melden das (`W102`).
     *
     * Fuer den Fingerabdruck heisst das: was der Zielserver nicht als
     * Bitmap ablegt, kann sein Reverse auch nicht als Bitmap zurueckgeben —
     * er liest `btree`. Ohne die Projektion meldete der Post-Compare nach
     * jedem `migrate --execute` eines Oracle-Schemas gegen PostgreSQL,
     * MySQL, SQLite oder SQL Server Drift fuer eine Migration, die genau
     * das getan hat, was verlangt war. Dieselbe Begruendung wie bei
     * [namesFullTextIndexes].
     */
    val supportsBitmapIndexes: Boolean = false,
    /**
     * Ob der Fingerabdruck sich auf den Namen der Sequenz hinter einer
     * IDENTITY-Spalte stuetzen darf.
     *
     * Fuer Oracle **nein**, und das ist gemessen (2026-09-06), nicht
     * angenommen — vier Belege:
     * - `GENERATED ALWAYS AS IDENTITY (SEQUENCE NAME s)` scheitert mit
     *   `ORA-02000`, `… USING <eigene_sequenz>` mit `ORA-03076`;
     * - der vergebene Name ist **nicht einmal stabil**: dieselbe Tabelle
     *   geloescht und identisch neu angelegt bekam `ISEQ${'$'}${'$'}_73345`
     *   und danach `ISEQ${'$'}${'$'}_73349`;
     * - nachtraeglich umbenennen geht auch nicht
     *   (`ORA-32799: cannot rename a system-generated sequence`).
     *
     * Dieselbe Begruendung wie bei [namesFullTextIndexes]: was im
     * Soll-Schema nicht stehen kann, der Reverse aber liest, driftet nach
     * jedem `migrate --execute`.
     *
     * **Der Default `true` ist fuer MySQL/SQLite/MSSQL wirkungslos** —
     * deren Reverse setzt `ColumnGeneration.Identity.sequenceName` nie.
     * Fuer **PostgreSQL** ist er eine offene Frage, keine Zusicherung: der
     * PG-Renderer schreibt den Namen ebenfalls nie (`GENERATED … AS
     * IDENTITY` ohne `SEQUENCE NAME`), der PG-Reverse liest ihn aber
     * schema-qualifiziert. PG auf `false` zu stellen aendert bestehende
     * PG-Fingerabdruecke und damit die Gueltigkeit bereits erzeugter
     * Rollback-Artefakte — das ist eine eigene Entscheidung, kein Beifang
     * des Oracle-Rollouts:
     * `docs/planning/open/pg-identity-sequence-name-fingerprint.md`.
     */
    val namesIdentitySequences: Boolean = true,
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
            // Objekttyp-Flags nach heutigem Oracle-Inventar
            // (docs/planning/in-progress/oracle-dialect-scoping.md, ADR 0052).
            // supportsCustomTypes bleibt bewusst false: Oracle-Objekttypen
            // (CREATE TYPE) sind nicht Teil des heutigen Slice-Schnitts.
            // batchSeparator bleibt null. `/` ist zwar die SQL*Plus/SQLcl-
            // Konvention -- aber es bedeutet etwas anderes als T-SQLs `GO`:
            // `GO` beendet einen Batch, `/` fuehrt den Puffer ERNEUT aus.
            // Hinter einer mit `;` abgeschlossenen Anweisung laeuft sie damit
            // zweimal (live im Sample-DB-Harness: jedes `CREATE SEQUENCE`
            // meldete beim zweiten Durchlauf `ORA-00955`; bei einem
            // Datenskript waere es ein doppelter INSERT gewesen).
            // `/` gehoert erst zu PL/SQL-Bloecken (Slice 9) -- und dort
            // ANSTELLE des `;`, nicht dahinter. Das braucht dann eine
            // Trenner-Entscheidung je Anweisung, keine je Dialekt.
            // partitionChildrenAreTables=false: Oracle-Partitionen brauchen wie
            // bei MySQL die `PARTITION (name)`-Klausel, sind keine eigenstaendig
            // adressierbaren Relationen. namesFullTextIndexes=true: Oracle-Text-
            // Indizes (CONTEXT/CTXCAT) tragen anders als MSSQL einen Namen.
            DatabaseDialect.ORACLE -> DialectCapabilities(
                supportsViews = true,
                supportsFunctions = true,
                supportsProcedures = true,
                supportsTriggers = true,
                supportsSequences = true,
                supportsCustomTypes = false,
                supportsPartitioning = true,
                supportsDisableFkChecks = true,
                supportsTriggerDisable = false,
                supportsTriggerStrict = false,
                supportsSchemaParameter = true,
                partitionChildrenAreTables = false,
                requiresPrimaryKeyForSkip = true,
                supportsIndexIncludeColumns = false,
                supportsClusteredIndexes = false,
                namesFullTextIndexes = true,
                namesIdentitySequences = false,
                supportsBitmapIndexes = true,
                batchSeparator = null,
            )
        }
    }
}
