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
     * Ob der Dialekt die Text-Search-Konfiguration eines Volltext-Index
     * **speichert** (ADR 0025, z. B. `english`).
     *
     * Oracle nicht: der Analyzer haengt dort an einer benannten
     * `CTX_DDL`-Lexer-Preference, nicht an der Index-Anweisung — der
     * Generate-Pfad verwirft die Angabe mit `W154`, und der Reverse kann sie
     * folglich nicht zurueckgeben. Ohne die Projektion meldete der
     * Post-Compare nach jedem `migrate --execute` Drift, **und** der naechste
     * Lauf plante erneut `DropIndex` + `AddIndex` — dauerhaft.
     *
     * MySQL, SQLite und SQL Server ebenso wenig: keiner der drei emittiert
     * die Angabe und keiner liest sie zurueck. `true` bleibt allein
     * PostgreSQL, dessen Reverse sie aus den `to_tsvector`-Argumenten
     * rekonstruiert.
     */
    val carriesFullTextConfiguration: Boolean = true,
    /**
     * Ob der Dialekt das Praedikat eines partiellen Index
     * (`IndexDefinition.where`) traegt.
     *
     * Oracle nicht: es kennt kein `WHERE` an einer Index-Anweisung. Der
     * Generate-Pfad legt den Index trotzdem an — als **vollen**, und meldet
     * das mit `W155`. Sein Reverse liest folglich einen Index ohne Praedikat
     * zurueck; ohne die Projektion meldete der Post-Compare nach jedem
     * `migrate --execute` Drift, und der naechste Lauf plante denselben Index
     * erneut.
     *
     * MySQL traegt es ebenso wenig, steht hier aber auf `true`: es legt den
     * Index gar nicht erst an, sondern ueberspringt ihn mit `E057`. Es gibt
     * dort also nichts zu versoehnen — und die Projektion haette einen
     * Schaden: ein von Hand angelegter voller Index saehe aus wie der
     * verlangte partielle.
     *
     * PostgreSQL, SQLite und SQL Server rendern und lesen das Praedikat.
     */
    val carriesPartialIndexPredicate: Boolean = true,
    /**
     * Ob der Dialekt `identifier` + `auto_increment` und den numerischen Typ
     * mit `generation: identity` zur **selben** Spalte rendert.
     *
     * Gemessen (2026-09-08) an allen fuenf Generatoren: MySQL, SQLite, SQL
     * Server und Oracle schreiben in beiden Faellen dieselbe Spalte
     * (`AUTO_INCREMENT`, `AUTOINCREMENT`, `IDENTITY(1,1)`,
     * `GENERATED ALWAYS AS IDENTITY`). PostgreSQL nicht: dort wird aus der
     * ersten Schreibweise `SERIAL` — eine Sequenz mit Default — und aus der
     * zweiten `GENERATED ALWAYS AS IDENTITY`. Das sind zwei verschiedene
     * Dinge, und der Unterschied bleibt dort ein Unterschied.
     *
     * Wo beide dasselbe ergeben, darf der Vergleich sie nicht auseinander
     * halten: der Reverse liefert immer die zweite Form, ein
     * handgeschriebenes Soll meist die erste, und der Planer plante sonst bei
     * jedem Lauf eine Aenderung an einer unveraenderten Spalte — auf Oracle
     * sogar eine, die dort gar nicht ausfuehrbar ist (`ORA-30673`).
     */
    val rendersAutoIncrementAsIdentity: Boolean = true,
    /**
     * Ob der Dialekt die Refresh-Einstellung einer materialisierten Sicht
     * (`ViewDefinition.refresh`) tatsaechlich umsetzt.
     *
     * PostgreSQL kennt nur den Refresh auf Anforderung und hat keine Klausel
     * dafuer; die Angabe bleibt dort ohne Wirkung. Oracle setzt sie um. Der
     * Migrationsreport haengt daran, ob er die Operation als „Refresh-Semantik
     * nicht ausgewertet" ausweist — fuer einen Dialekt, der sie ausweislich
     * rendert, waere das eine falsche Auskunft ueber einen Lauf, der genau das
     * getan hat.
     */
    val rendersViewRefreshSetting: Boolean = false,
    /**
     * Ob der Dialekt die **untere** Grenze einer RANGE-Partition fuehrt.
     * PostgreSQL tut es (`FOR VALUES FROM … TO …`); Oracle, MySQL und SQL
     * Server kennen nur die obere und leiten die untere aus der
     * vorhergehenden Partition ab.
     *
     * Entscheidend ist nicht, was der Server ablegt, sondern was sein
     * Reverse zurueckgibt: MySQL (`MysqlPartitionReader`) und SQL Server
     * (`MssqlSchemaReader`) **rekonstruieren** die untere Grenze aus der
     * Kontiguitaet und stehen deshalb auf `true`. Oracle tut das nicht.
     *
     * Die Rekonstruktion trifft, solange die Partitionen lueckenlos sind.
     * Sind sie es nicht, ist der Unterschied echt — diese Dialekte koennen
     * eine Luecke gar nicht abbilden (der Generate-Pfad meldet es mit
     * `W112`), und ihn wegzuprojizieren verstecke einen wirklichen Verlust.
     */
    val carriesPartitionLowerBounds: Boolean = true,
    /**
     * Ob der Dialekt Modulus und Remainder einer HASH-Partition fuehrt.
     * PostgreSQL tut es; Oracle verteilt selbst und fuehrt nur die Anzahl
     * (`ALL_TAB_PARTITIONS.HIGH_VALUE` ist bei HASH `null`).
     *
     * Wie [namesFullTextIndexes]: was der Zielserver nicht fuehrt, kann sein
     * Reverse nicht zurueckgeben, und ohne die Projektion meldete der
     * Post-Compare nach jedem `migrate --execute` Drift.
     *
     * MySQL steht auf `true`: sein Reverse leitet `modulus = n` und
     * `remainder = Ordinalindex` aus `PARTITIONS n` ab. SQL Server ebenso —
     * dort ist der Verlust ein anderer und groesserer, weil eine emulierte
     * HASH-Partitionierung als RANGE zurueckkommt; das kann keine
     * Feld-Projektion heilen.
     */
    val carriesPartitionHashModulus: Boolean = true,
    /**
     * Ob der Dialekt Partitionen **benennt**.
     *
     * Vier der fuenf tun es. SQL Server nicht: es nummeriert sie, die Namen
     * des Soll-Schemas gibt es dort nicht, und der Reverse vergibt `p1…pn`
     * in Grenzreihenfolge (`R346`).
     *
     * Dieselbe Erwaegung wie bei [namesFullTextIndexes] — und dieselbe, die
     * `CanonicalPayload.partitionConfig` bereits trifft: den Namen laesst es
     * ausdruecklich aus dem Operations-ID-Schluessel heraus. Ohne die
     * Projektion sagte die Payload „derselbe Vorgang", waehrend der Vergleich
     * bei jedem Lauf eine Aenderung meldete.
     */
    val namesPartitions: Boolean = true,
    /**
     * Ob der Dialekt eine Partitionierung nach **Wertemenge** ausdruecken kann.
     *
     * Vier der fuenf koennen es. SQL Server nicht: dort ist Partitionierung
     * ausschliesslich eine Folge von RANGE-Grenzen ueber einer Spalte. Eine
     * LIST-Partitionierung ist genau dann als RANGE ausdrueckbar, wenn die
     * Wertemengen in einer Ordnung zusammenhaengend und ueberschneidungsfrei
     * liegen — welche Grenze zu welcher Menge gehoert, sagt aber der Anwender
     * ueber ein `partition-mapping`-Overlay; das Werkzeug raet es nicht.
     *
     * Anders als [namesPartitions] ist das keine Projektionsfrage: hier geht
     * nichts beim Zurueckgeben verloren, sondern der Dialekt kennt die Form
     * gar nicht.
     */
    val supportsListPartitioning: Boolean = true,
    /**
     * Ob der Dialekt einen reinen Datumswert von einem Zeitstempel um
     * Mitternacht unterscheiden kann.
     *
     * Oracle kann es **nicht**: sein `DATE` traegt immer eine Uhrzeit, und
     * der Katalog fuehrt sie mit (`… 00:00:00`). Eine als `'2024-01-01'`
     * geschriebene Partitionsgrenze kommt deshalb als `'2024-01-01 00:00:00'`
     * zurueck — derselbe Wert, andere Schreibweise. Ohne die Angleichung im
     * Fingerabdruck meldete der Post-Compare nach jedem `migrate --execute`
     * Drift.
     *
     * Das ist die Grenzwert-Seite derselben Faltung, die der
     * Typ-Kanonisierer auf der Spalten-Seite vornimmt (`date` → `datetime`
     * fuer Oracle).
     */
    val separatesDateFromDateTime: Boolean = true,
    /**
     * Ob der Fingerabdruck sich auf den Namen der Sequenz hinter einer
     * IDENTITY-Spalte stuetzen darf.
     *
     * Fuer Oracle **nein**, aus vier Gruenden:
     * - `GENERATED ALWAYS AS IDENTITY (SEQUENCE NAME s)` scheitert mit
     *   `ORA-02000`, `… USING <eigene_sequenz>` mit `ORA-03076`;
     * - der vergebene Name ist **nicht einmal stabil**: dieselbe Tabelle
     *   geloescht und identisch neu angelegt bekommt einen anderen;
     * - nachtraeglich umbenennen geht auch nicht
     *   (`ORA-32799: cannot rename a system-generated sequence`).
     *
     * Dieselbe Begruendung wie bei [namesFullTextIndexes]: was im
     * Soll-Schema nicht stehen kann, der Reverse aber liest, driftet nach
     * jedem `migrate --execute`.
     *
     * Fuer **PostgreSQL** ebenfalls `false`, aus demselben Grund in
     * milderer Form: der PG-Renderer schreibt den Namen nie (`GENERATED …
     * AS IDENTITY` ohne `SEQUENCE NAME`), der PG-Reverse liest ihn aber
     * schema-qualifiziert zurueck. Ein Soll-Schema kann ihn nicht tragen —
     * auch ein von Hand geschriebener unqualifizierter Name driftete gegen
     * den qualifizierten.
     *
     * Der Default `true` bleibt fuer MySQL, SQLite und SQL Server stehen und
     * ist dort wirkungslos: deren Reverse setzt
     * `ColumnGeneration.Identity.sequenceName` nie.
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
                // Der Reverse liest den system-vergebenen Sequenznamen einer
                // IDENTITY-Spalte schema-qualifiziert zurueck; gerendert wird
                // er von keinem Dialekt, ein Soll-Schema kann ihn also nicht
                // tragen.
                namesIdentitySequences = false,
                // `SERIAL` und `GENERATED ... AS IDENTITY` sind in PostgreSQL
                // zwei verschiedene Dinge, nicht zwei Schreibweisen desselben.
                rendersAutoIncrementAsIdentity = false,
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
                carriesFullTextConfiguration = false,
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
                carriesFullTextConfiguration = false,
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
                carriesFullTextConfiguration = false,
                namesPartitions = false,
                supportsListPartitioning = false,
            )
            // Objekttyp-Flags nach dem Oracle-Inventar (ADR 0052).
            // supportsCustomTypes bleibt bewusst false: Oracle-Objekttypen
            // (CREATE TYPE) bildet d-migrate nicht ab.
            // batchSeparator bleibt null. `/` ist zwar die SQL*Plus/SQLcl-
            // Konvention -- aber es bedeutet etwas anderes als T-SQLs `GO`:
            // `GO` beendet einen Batch, `/` fuehrt den Puffer ERNEUT aus.
            // Hinter einer mit `;` abgeschlossenen Anweisung laeuft sie damit
            // zweimal; jedes `CREATE SEQUENCE` meldete beim zweiten Durchlauf
            // `ORA-00955`, bei einem Datenskript waere es ein doppelter INSERT
            // gewesen. `/` gehoert nur zu PL/SQL-Bloecken und dort ANSTELLE
            // des `;` -- also an die einzelne Anweisung
            // (`DdlStatement.scriptTerminator`), nicht an den Dialekt.
            // partitionChildrenAreTables=false: Oracle-Partitionen brauchen wie
            // bei MySQL die `PARTITION (name)`-Klausel, sind keine eigenstaendig
            // adressierbaren Relationen. namesFullTextIndexes=true: Oracle-Text-
            // Indizes (CONTEXT/CTXCAT) tragen anders als MSSQL einen Namen.
            DatabaseDialect.ORACLE -> DialectCapabilities(
                rendersViewRefreshSetting = true,
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
                carriesFullTextConfiguration = false,
                carriesPartialIndexPredicate = false,
                carriesPartitionLowerBounds = false,
                carriesPartitionHashModulus = false,
                separatesDateFromDateTime = false,
                batchSeparator = null,
            )
        }
    }
}
