# Implementierungsplan: Milestone 0.4.0 — Datenimport und inkrementelle Datenpfade

> Dieses Dokument beschreibt den konkreten Implementierungsplan für Milestone 0.4.0.
> Es dient als laufend gepflegte Spezifikation und Review-Grundlage während der Umsetzung.
>
> **Status**: In Arbeit — Phase A und Phase B umgesetzt; Plan wird gegen den Implementierungsstand nachgeführt.

---

## 1. Ziel

Daten aus JSON-, YAML- und CSV-Dateien wieder in PostgreSQL, MySQL und SQLite
einlesen — chunkbasiert, transaktional, mit korrekter Nachführung von
Sequenzen/Identity-Spalten und einem klaren Trigger-Vertrag. Außerdem die
inkrementelle Marker-Spalten-Variante des Exports (LF-013), die im
Roadmap-Update von 0.9.0 nach 0.4.0 vorgezogen wurde. Der Delta-Import läuft
über idempotenten UPSERT (`--on-conflict update`) — siehe §6.12.2.

```
Datei → d-migrate data import --target <url|name> --source <path> --table <name>      → DB
DB    → d-migrate data export ... --since-column updated_at --since "<ts>"             → Datei (delta)
Datei → d-migrate data import ... --table <name> --on-conflict update                  → DB (delta, idempotent)
```

**Was gehört zu 0.4.0:**

- Lese-Pfad in `adapters:driven:formats`: `DataChunkReader` für JSON, YAML, CSV mit
  echtem Streaming (kein Volltext-Parsen)
- `DataWriter`-Port in `hexagon:ports` plus JDBC-Adapter für PostgreSQL,
  MySQL und SQLite (Batch-`INSERT` per `PreparedStatement.addBatch`)
- `StreamingImporter` in `adapters:driven:streaming` (Glue Reader → Writer, mit
  Chunk-Transaktionen und konfigurierbarer Fehler-Politik)
- Sequence- und `AUTO_INCREMENT`-Reseeding pro Dialekt (siehe
  [`design-import-sequences-triggers.md`](./design-import-sequences-triggers.md))
- Trigger-Modi `fire`/`disable`/`strict` mit dialektspezifischer Umsetzung
- Schema-Validierung des Targets vor dem Import (über JDBC `ResultSetMetaData`,
  ohne den noch nicht existierenden `SchemaReader` aus 0.6.0). Das
  Target-Schema ist die **autoritative Quelle** für Spalten-Typen und
  -Reihenfolge — der Reader liefert höchstens die Header-Namen aus dem File.
- Encoding-Unterstützung für die Eingabedateien: BOM-basierte Auto-Detection
  nur für UTF-8 und UTF-16 BE/LE; alle anderen Encodings (ISO-8859-1,
  Windows-1252, UTF-32 etc.) ausschließlich über expliziten `--encoding`-Flag
- CLI-Kommando `d-migrate data import` (siehe `cli-spec.md` §6.2 — der
  Platzhalter wird konkretisiert)
- LF-013 inkrementeller **Export** mit `--since-column` und `--since`. Auf
  der Import-Seite gibt es bewusst **keinen** `--incremental`-Flag —
  inkrementelle Imports sind fachlich identisch zu idempotenten
  UPSERT-Imports und werden über `--on-conflict update` abgewickelt
  (siehe §6.12).
- `database.default_source` in `.d-migrate.yaml` wird ab 0.4.0 für
  `data export` aktiv; `database.default_target` analog für `data import`
  (war in 0.3.0 noch ignoriert, siehe Plan-0.3.0 §6.14)
- Round-Trip-Integration-Tests Export → Import → Vergleich (LN-043), inkl.
  inkrementellem Round-Trip
- Testcontainers-E2E für `data import` analog zu Phase F aus 0.3.0

**Was bewusst NICHT zu 0.4.0 gehört:**

- Checkpoint/Resume und `--resume` (LN-012 → 0.9.0)
- Atomare Rollbacks auf Checkpoint-Ebene (LN-013 → 1.0.0)
- Parallele Tabellenverarbeitung (LN-007 → 1.0.0)
- Schema-Reverse-Engineering (`schema reverse`, LF-004 → 0.6.0) — der
  Importer arbeitet ohne `SchemaReader` mit JDBC-Metadaten der Zieltabelle
- Auto-Discovery von Marker-Spalten für `--incremental` auf der Export-Seite
  (kommt in 0.6.0, sobald der `SchemaReader` `updated_at`-Konventionen
  erkennen kann); 0.4.0 fordert die Spalte explizit per CLI-Flag
- Automatische Encoding-Erkennung ohne BOM (chardet, ICU). Wer ISO-8859-1
  oder Windows-1252 importieren will, gibt `--encoding` explizit an.
- Atomarer Tabellen-Replace (`--replace-table`): TRUNCATE plus Import in
  einer Tabellen-Transaktion. Komplexer als chunkbasiert und braucht ein
  eigenes Transaktionsmodell — kommt in 0.5.0 oder später, falls Bedarf
  gemeldet wird. `--truncate` in 0.4.0 ist explizit ein nicht-atomarer
  Pre-Step (siehe §6.14).
- Replikations-/CDC-spezifische Importpfade
- Datenformat-Migration zwischen Versionen (kein `data migrate-format`)

---

## 2. Neue Module

Kein neues Gradle-Modul. Alle Erweiterungen finden in den existierenden Modulen
statt — die Architektur aus 0.3.0 trägt: `formats` bekommt Reader-Klassen
parallel zu den Writern, `hexagon:ports` einen `DataWriter`-Port parallel zum
`DataReader`, `streaming` einen `StreamingImporter` parallel zum
`StreamingExporter`, `cli` ein neues `DataImportCommand` und einen
`--since-column`/`--since`-Flow für `DataExportCommand`.

> **Wichtig**: Wir vermeiden bewusst ein neues Modul „d-migrate-import". Die
> bestehenden Module sind nach **Schicht** organisiert, nicht nach Read-/Write-
> Richtung. Ein separates Import-Modul würde die hexagonale Aufteilung
> aufweichen.

---

## 3. Bestehende Module — Änderungen

### 3.1 `hexagon:ports` / `adapters:driven:driver-common`

#### 3.1.1 Neuer Port `DataWriter`

```kotlin
package dev.dmigrate.driver.data

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
     * Borgt sich pro Aufruf eine Connection aus dem Pool (analog §6.18 aus
     * Plan 0.3.0). Die Connection wird in der Session gehalten und beim
     * `close()` zurückgegeben.
     *
     * **Cleanup-Vertrag (H1)**: `openTable(...)` führt schon vor dem
     * `return session` dialektspezifische Side-Effects aus — namentlich
     * `disableTriggers(...)` (bei `triggerMode = disable`) und das
     * `assertNoUserTriggers(...)`-Pre-Flight (bei `triggerMode = strict`).
     * Wirft die Methode danach noch — etwa weil `prepareStatement(...)`
     * scheitert oder die JDBC-Metadatenabfrage einen späten Fehler liefert
     * — bekommt der Caller **keine Session** zurück und kann `close()`
     * folglich nicht aufrufen. Der Writer MUSS solche Pfade intern in
     * `try { ... } catch (t) { ... }` wrappen und vor dem `throw`:
     *
     * 1. `enableTriggers(...)` idempotent erneut aufrufen, wenn
     *    `disableTriggers(...)` in dieser Methode bereits gelaufen ist
     * 2. die ausgeliehene Connection an den Pool zurückgeben
     * 3. ggf. `autoCommit` zurücksetzen, falls bereits umgeschaltet
     *
     * Erst danach darf die ursprüngliche Exception weiterreichen. Ohne
     * diesen Wrapper bliebe die Tabelle in einem inkonsistenten
     * Trigger-Zustand stehen und es gäbe niemanden mehr, der das aufräumen
     * könnte — ein hartes Recovery-Loch, das nicht durch `session.close()`
     * abgedeckt ist.
     *
     * **F2 — Exception-Chaining im Cleanup-Pfad**: wirft der idempotente
     * `enableTriggers(...)`-Versuch in Schritt 1 oder das Schließen der
     * Connection in Schritt 2 selbst, bleibt die ursprünglich aufgetretene
     * Exception die **primäre** und wird weitergereicht; der sekundäre
     * Cleanup-Fehler wird per `originalException.addSuppressed(cleanupError)`
     * angehängt. Es darf NIEMALS passieren, dass ein Cleanup-Fehler die
     * eigentliche Fehlerursache (z.B. die ursprüngliche
     * `prepareStatement(...)`-Exception) verschluckt. Der Phase-C-Test für
     * H1 prüft das explizit per `assertThat(thrown.suppressed).isNotEmpty()`
     * gegen einen Test-Subclass-Writer, der den Reenable-Pfad gezielt
     * scheitern lässt.
     *
     * @throws ImportSchemaMismatchException bei Target-seitigen
     *   Metadaten-/Schemafehlern (z.B. Tabelle nicht vorhanden oder
     *   unlesbare Spaltenmetadaten). Header-/Chunk-Mismatch wird erst im
     *   [StreamingImporter] nach dem ersten Reader-Zugriff geprüft (§6.4).
     */
    fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession
}

/**
 * **Session-State-Maschine (M1)**:
 *
 * Zustände: `OPEN`, `WRITTEN`, `FAILED`, `FINISHED`, `CLOSED`. Startzustand
 * ist `OPEN` direkt nach `openTable(...)`. Erlaubte Transitions:
 *
 * ```
 * OPEN     --write(...)------------------> WRITTEN
 * OPEN     --finishTable() [0-Chunk, F1]-> FINISHED
 * OPEN     --markTruncatePerformed()-----> OPEN          (idempotent, vor write)
 * OPEN     --close()---------------------> CLOSED
 *
 * WRITTEN  --commitChunk() erfolgreich---> OPEN
 * WRITTEN  --rollbackChunk() erfolgreich-> OPEN
 * WRITTEN  --commitChunk() wirft (H-R1)--> FAILED        (Exception propagiert)
 * WRITTEN  --rollbackChunk() wirft (H-R1)> FAILED        (Exception propagiert)
 * WRITTEN  --close()---------------------> CLOSED
 *
 * FAILED   --close()---------------------> CLOSED        (einzige erlaubte Op)
 *
 * FINISHED --close()---------------------> CLOSED
 *
 * CLOSED   --close()---------------------> CLOSED        (idempotent)
 * ```
 *
 * Jede Transition, die nicht in dieser Tabelle steht, ist ein harter
 * State-Fehler und wirft `IllegalStateException` (z.B. `write(...)` aus
 * `WRITTEN`, `commitChunk()` aus `OPEN`, `write(...)` aus `FAILED`,
 * irgendeine Non-close-Op aus `CLOSED`).
 *
 * - `OPEN`: frisch nach `openTable(...)` oder nach erfolgreichem
 *   commit/rollback. Erlaubt: `write(...)`, `finishTable()`, `close()`.
 *   `finishTable()` aus `OPEN` ist sowohl der reguläre Pfad nach dem
 *   letzten erfolgreichen `commitChunk()` als auch der 0-Chunk-Pfad (F1)
 *   für leere selbstbeschreibende Eingaben.
 * - `WRITTEN`: nach einem erfolgreichen `write(...)`. Erlaubt:
 *   `commitChunk()`, `rollbackChunk()`, `close()`. Ein zweiter `write(...)`
 *   ohne dazwischenliegenden Commit/Rollback ist ein State-Fehler. Ein
 *   `finishTable()` aus `WRITTEN` ist ebenfalls ein State-Fehler — der
 *   Importer MUSS vorher `commitChunk()` aufrufen, sonst gibt es kein
 *   implizites Commit-on-Finish.
 * - `FAILED` (**H-R1**): terminaler Fehler-Zustand, in den die Session
 *   wechselt, sobald `commitChunk()` oder `rollbackChunk()` selbst eine
 *   Exception werfen (z.B. connection lost beim Commit, DEFERRED-Constraint
 *   schlägt erst am Commit zu, oder `conn.rollback()` scheitert, weil die
 *   Connection bereits einseitig vom Server geschlossen wurde). In diesem
 *   Pfad ist der tatsächliche Commit-Zustand der DB-seitigen Transaktion
 *   **unbekannt** — der Chunk darf weder als erfolgreich committed noch
 *   als sauber zurückgerollt verbucht werden. Der Writer MUSS den internen
 *   State auf `FAILED` setzen, BEVOR die ursprüngliche Exception propagiert,
 *   und darf danach keine JDBC-Aufrufe mehr auf der Connection ausführen
 *   (insbesondere keinen zweiten `rollback()`-Versuch). Erlaubt aus
 *   `FAILED` ist ausschließlich `close()`; jeder andere Aufruf
 *   (`write(...)`, `commitChunk()`, `rollbackChunk()`, `finishTable()`,
 *   `markTruncatePerformed()`) wirft `IllegalStateException`. Der
 *   `close()`-Pfad überspringt in diesem Zustand seine Schritt-1-
 *   `conn.rollback()`-Logik (§R6) bewusst — die Connection gilt als
 *   verdächtig, und ein zweiter Rollback-Versuch würde im Regelfall nur
 *   dieselbe Exception produzieren. Stattdessen führt `close()` Schritt 2–4
 *   aus (autoCommit-Reset best-effort, Trigger-Reenable best-effort,
 *   Connection-Return) und schluckt dabei auftretende Sekundärfehler in
 *   den strukturierten Reporting-Pfad.
 *   - **Importer-Verhalten**: der Aufrufer fängt die ursprüngliche
 *     Exception aus `commitChunk()`/`rollbackChunk()` im `StreamingImporter`,
 *     behandelt den betroffenen Chunk als verloren (`rowsFailed += chunk.size`
 *     analog §6.5), und wendet `--on-error abort|skip|log` an. Bei
 *     `abort` wird `close()` sofort aufgerufen und der Tabellen-Import
 *     mit Exit 5 abgebrochen. Bei `skip|log` ruft der Importer ebenfalls
 *     sofort `close()` (da die Session aus `FAILED` kein weiteres
 *     `write(...)` akzeptiert) und öffnet bei Bedarf für die nächste
 *     Tabelle eine neue `TableImportSession`; für die aktuelle Tabelle
 *     ist nach einem `FAILED`-Zustand das Ende erreicht, weitere Chunks
 *     DIESER Tabelle werden nicht mehr versucht. Das ist strenger als
 *     der reguläre Chunk-Skip-Pfad (wo die Session nach
 *     `rollbackChunk()` erfolgreich wieder in `OPEN` wechselt), aber
 *     notwendig, weil der Zustand der zugrundeliegenden JDBC-Connection
 *     nach einem Commit/Rollback-Fehler nicht mehr vertrauenswürdig ist.
 * - `FINISHED`: nach erfolgreichem `finishTable()`. Erlaubt nur noch
 *   `close()`.
 * - `CLOSED`: terminal. Jeder weitere Aufruf außer `close()` (idempotent)
 *   wirft `IllegalStateException`.
 *
 * Jeder Pfad, der gegen diese State-Maschine verstößt, ist ein
 * Importer-Bug, KEIN tolerierter Edge-Case. Die Streaming-Layer-Tests in
 * Phase D fahren die State-Maschine mit einem Fake Writer ab, sodass
 * Container-Tests in Phase F nicht erst die echten Bugs finden müssen.
 * Der `FAILED`-Pfad wird dabei explizit über einen Test-Subclass-Writer
 * geübt, der beim ersten `commitChunk()` wirft (und einen zweiten Test,
 * der beim `rollbackChunk()` wirft), und der Phase-D-Test asserted, dass
 * (a) der Importer die ursprüngliche Exception sieht, (b) `close()`
 * idempotent durchläuft und (c) weitere State-Transitions aus `FAILED`
 * mit `IllegalStateException` abgewiesen werden.
 */
interface TableImportSession : AutoCloseable {
    /**
     * Autoritative Target-Spalten in Binding-Reihenfolge. Der Writer liest
     * sie beim Öffnen der Tabelle selbst über JDBC-Metadaten ein (§6.4).
     * Der Importer benutzt diese Liste für Header-Validierung,
     * Reorder-Mapping und die JDBC-Typ-Hints für den
     * [ValueDeserializer] (§3.5.2).
     *
     * **L15 — neuer `TargetColumn`-Typ in `hexagon:ports`, NICHT
     * `ColumnDescriptor`**: Phase A wollte ursprünglich
     * `ColumnDescriptor` in `hexagon:core` um ein `jdbcType: Int?`-Feld
     * erweitern — das hätte aber gegen die 0.3.0-Architektur-Regel "kein
     * JDBC-spezifisches Feld in core" verstoßen. Stattdessen definiert
     * `hexagon:ports` einen eigenen `TargetColumn(name, nullable,
     * jdbcType, sqlTypeName)`-Typ, der die vom Writer aus
     * `ResultSetMetaData` gelesenen Metadaten inklusive JDBC-Typcode
     * trägt. `core.ColumnDescriptor` bleibt bitwise wie 0.3.0.
     *
     * Die Konversion von [TargetColumn] zu [JdbcTypeHint][dev.dmigrate.format.data.JdbcTypeHint]
     * (für den `ValueDeserializer`) erfolgt erst im Phase-D
     * `StreamingImporter` (`adapters:driven:streaming`), das beide Module
     * kennt. `hexagon:ports` importiert `formats` nicht und bleibt damit
     * innerhalb der hexagonalen Architektur.
     */
    val targetColumns: List<TargetColumn>

    /**
     * Schreibt einen Chunk im aktuellen Transaktionskontext.
     *
     * Vor `write(...)` normalisiert der Importer `chunk.columns` auf die
     * tatsächlich im File vorhandene Target-Spalten-TEILMENGE in
     * Target-Reihenfolge. Der Writer erzeugt seinen INSERT-Spalten-Block aus
     * genau dieser Liste und lässt alle übrigen [targetColumns] weg, so dass
     * DB-DEFAULTs erhalten bleiben. Weicht `chunk.columns` von der beim
     * Session-Start abgeleiteten Binding-Liste ab, ist das ein harter
     * Programmierfehler und der Writer darf früh mit `IllegalStateException`
     * abbrechen.
     *
     * **M2 — Allocation-Profil**: Die Reorder-Normalisierung erzeugt heute
     * pro Chunk einen neuen `DataChunk` plus pro Row ein neues
     * `Array<Any?>`. Diese Kosten werden in Phase D Schritt 23 explizit
     * profiliert; falls sie spürbar werden, gibt es einen additiven
     * `write(reorderedRows, bindingColumns)`-Pfad, der `DataChunk` im
     * Hot-Path ganz vermeidet (siehe §10 M2). 0.4.0 startet aber bewusst
     * mit der einfacheren `DataChunk`-API und entscheidet die Optimierung
     * messungsbasiert.
     */
    fun write(chunk: DataChunk): WriteResult

    /**
     * Bestätigt den letzten geschriebenen Chunk. In JDBC mit
     * `autoCommit=false` entspricht das semantisch exakt `conn.commit()`;
     * ein „nächster Chunk" entsteht implizit durch die nächste
     * Schreiboperation, nicht durch zusätzliche Session-Logik.
     *
     * **State-Maschine (M1)**: erwartet `state == WRITTEN`. Aus jedem
     * anderen Zustand (`OPEN` ohne Write, `COMMITTED` direkt hintereinander,
     * `CLOSED`, `FAILED`) wirft `commitChunk()` `IllegalStateException`.
     * Nach erfolgreicher Bestätigung wechselt der Zustand zu `OPEN`, sodass
     * der nächste `write(...)` wieder regulär `WRITTEN` setzt.
     *
     * **H-R1 — Wenn `commitChunk()` selbst wirft**: Der zugrundeliegende
     * `conn.commit()` kann aus mehreren Gründen scheitern: Connection
     * während des Commits weggebrochen (Netzwerk-Reset, Idle-Timeout am
     * Server), eine DEFERRED-Constraint schlägt erst jetzt zu, ein
     * triggerbasierter serverseitiger Fehler kommt erst am Commit-Punkt
     * zurück. In all diesen Pfaden ist der **tatsächliche** Commit-Zustand
     * auf DB-Seite aus Sicht des Clients nicht eindeutig bestimmbar: die
     * Transaktion kann teilweise, ganz oder gar nicht durchgelaufen sein.
     * Der Writer MUSS in diesem Pfad:
     *
     * 1. den internen State auf `FAILED` setzen, **bevor** die Exception
     *    weiterpropagiert
     * 2. KEINE weiteren JDBC-Aufrufe auf der Connection machen —
     *    insbesondere keinen zweiten `rollback()`-Versuch, keinen
     *    `setAutoCommit(true)`-Reset
     * 3. die ursprüngliche Exception unverändert weiterwerfen (keine
     *    Wrapping, kein `IllegalStateException`)
     *
     * Der aufrufende `StreamingImporter` behandelt die Exception wie einen
     * Chunk-Fehler (§6.5), muss aber — anders als beim regulären
     * Rollback-Pfad — **sofort** `close()` aufrufen und darf nicht
     * versuchen, die Session für den nächsten Chunk weiterzuverwenden.
     * Aus `FAILED` gibt es keinen Weg zurück nach `OPEN`.
     */
    fun commitChunk()

    /**
     * Verwirft den letzten geschriebenen Chunk.
     *
     * **State-Maschine (M1)**: erwartet `state == WRITTEN`. Mehrfacher
     * Aufruf ohne dazwischenliegenden neuen `write(...)`-Aufruf wirft
     * `IllegalStateException`; ein `rollbackChunk()` nach erfolgreichem
     * `commitChunk()` wirft ebenfalls `IllegalStateException`; aus
     * `FAILED` oder `CLOSED` wirft `rollbackChunk()` ebenfalls
     * `IllegalStateException`. Diese Strenge ist Absicht (Finding M1):
     * Importer-Bugs in der State-Maschine sollen schon im Phase-D-
     * Streaming-Test (Fake Writer) auffallen, nicht erst in den
     * Phase-F-Containertests.
     *
     * Nach erfolgreichem Rollback wechselt der Zustand zu `OPEN`. Die
     * Connection bleibt im normalen `autoCommit=false`-Importzustand;
     * Statement/Session können für den nächsten Chunk weiterverwendet
     * werden.
     *
     * **H-R1 — Wenn `rollbackChunk()` selbst wirft**: `conn.rollback()`
     * kann scheitern, wenn die Connection bereits einseitig vom Server
     * geschlossen wurde, oder in seltenen Pfaden, wenn eine
     * Statement-Cancel-Race zwischen Writer-Timeout und Cleanup den
     * Transaktionszustand zerreißt. Der Writer MUSS in diesem Pfad genau
     * wie für `commitChunk()` den State auf `FAILED` setzen (bevor die
     * Exception propagiert), KEINEN zweiten Rollback versuchen und die
     * ursprüngliche Exception unverändert weiterreichen. Der
     * `StreamingImporter` behandelt das wie einen Chunk-Fehler und ruft
     * sofort `close()` auf; die Session ist für diese Tabelle verbraucht.
     */
    fun rollbackChunk()

    /**
     * **R3 — Truncate-Signal pro Tabelle**: vom [StreamingImporter]
     * direkt nach einem erfolgreich ausgeführten `--truncate`-Pre-Step
     * (§6.14) auf GENAU der `TableImportSession` der gerade truncated
     * Zieltabelle aufgerufen. Idempotent; mehrfacher Aufruf hat keinen
     * zusätzlichen Effekt.
     *
     * Mit dieser Methode landet der „Tabelle wurde im aktuellen Lauf
     * truncated"-Zustand bewusst **per Session** am Writer und nicht über
     * `ImportOptions` als globalen CLI-Flag — bei Multi-Tabellen-Imports
     * mit `--truncate` kann der Pre-Step für einzelne Tabellen geskippt
     * werden (z.B. weil die Tabelle nicht im Filter steht), und die
     * H5/F4-Sonderpfade in §6.6 dürfen sich NICHT auf einen tabelle-
     * fremden Zustand berufen. Der Writer wertet das Signal in
     * [finishTable] für den MySQL-H5- und SQLite-F4-Pfad aus (§3.3 / §3.4 /
     * §6.6 Schritt 3).
     *
     * **State-Maschine (M1)**: erlaubt aus `OPEN` (typischer Pfad: direkt
     * nach `openTable(...)` und vor dem ersten `write(...)`). Nach dem
     * ersten erfolgreichen `write(...)` liefert `markTruncatePerformed()`
     * `IllegalStateException`, weil ein nachträgliches Truncate-Signal
     * fachlich keinen Sinn ergibt.
     */
    fun markTruncatePerformed()

    /**
     * Regulärer Erfolgsabschluss einer Tabelle: führt etwaige
     * Sequence-/Identity-Nachführung (§6.6) aus, reaktiviert ggf. Trigger
     * (§6.7) und liefert das Ergebnis als [FinishTableResult] zurück.
     *
     * Reihenfolge in 0.4.0:
     * 0. `conn.commit()` idempotent (R2 — räumt jede offene Lese-/
     *    Chunk-Transaktion ab, damit `SELECT MAX(col)` und das
     *    dialektspezifische Reseeding in einem deterministischen
     *    Transaktionszustand starten; siehe §6.6 Schritt 0)
     * 1. `reseedGenerators(...)`
     * 2. `enableTriggers(...)`
     * 3. Ergebnis bauen und zurückgeben
     *
     * **Erfolgspfad**: liefert `FinishTableResult.Success(adjustments)`.
     *
     * **Schritt 1 wirft (Reseeding scheitert)**: `finishTable()` selbst
     * wirft die ursprüngliche SQLException weiter. `enableTriggers(...)`
     * bleibt Aufgabe des anschließenden `close()`-Cleanup-Pfads. Es gibt
     * bewusst keine zweite Erfolgs-/Fehler-Statusmaschine nur für
     * `finishTable()`. In diesem Pfad gibt es keine berechneten
     * Adjustments — `reseedGenerators(...)` hat sie ja nicht erzeugt.
     *
     * **Schritt 2 wirft (Trigger-Reenable scheitert nach erfolgreichem
     * Reseeding — H4)**: `finishTable()` wirft NICHT, sondern liefert
     * `FinishTableResult.PartialFailure(adjustments, cause)`. Damit kommen
     * die bereits durchgeführten Adjustments **strukturiert** beim
     * `StreamingImporter` an und landen in
     * `TableImportSummary.failedFinish` (§3.6.2). Der Importer kennzeichnet
     * die Tabelle dann als „committed mit Reenable-Fehler", schlägt den
     * Tabellen-Import fehl und ruft `close()` an, der `enableTriggers(...)`
     * idempotent erneut versucht. So existiert kein zweiter, nur
     * String-basierter Reporting-Pfad: §6.15 rendert den Fehler aus dem
     * strukturierten `failedFinish`-Feld, und Phase F kann den Pfad gezielt
     * asserten.
     *
     * Darf NUR nach erfolgreichem Schreiben aller Chunks aufgerufen werden.
     *
     * **F1 — 0-Chunk-Pfad ist gültig**: „aller Chunks" schließt explizit die
     * Anzahl null ein. Liefert der Reader für ein leeres
     * selbstbeschreibendes Eingabeformat (`[]` in JSON/YAML) bereits bei
     * `nextChunk()` ein `null`, ruft der Importer `finishTable()` direkt aus
     * dem `OPEN`-Zustand ohne vorangegangenen `write(...)` auf — das ist
     * KEIN State-Fehler, sondern der reguläre Pfad für „nichts zu
     * importieren". `reseedGenerators(...)` läuft dann gemäß §6.6 Schritt 3
     * als No-op (kein Adjustment im Report), und `enableTriggers(...)`
     * verhält sich symmetrisch idempotent. Der Erfolgsabschluss liefert
     * `FinishTableResult.Success(emptyList())`.
     */
    fun finishTable(): FinishTableResult

    /**
     * Cleanup-Pfad: reaktiviert ggf. Trigger, setzt Transaktionszustand zurück
     * und gibt die Connection an den Pool zurück. Führt bewusst KEIN Reseeding
     * aus; `close()` läuft auch im Fehlerpfad nach Teilimporten.
     *
     * **R6 — Cleanup-Reihenfolge** (verbindlich für Phase C, damit jeder
     * Treiber denselben Pfad implementiert und §6.7/M8 nicht versehentlich
     * unterläuft):
     *
     * 1. Wenn eine Chunk-Transaktion noch offen steht (state == `WRITTEN`,
     *    oder state == `OPEN` aber `autoCommit = false` aus dem Chunk-Pfad),
     *    `conn.rollback()` aufrufen — der Caller hat in diesem Pfad bewusst
     *    nicht committet, also wird verworfen.
     *    **H-R1-Ausnahme**: im Zustand `FAILED` (commitChunk/rollbackChunk
     *    haben zuvor geworfen) wird dieser Schritt **bewusst übersprungen**.
     *    Die Connection gilt in diesem Pfad als verdächtig; ein zweiter
     *    `rollback()`-Versuch würde typischerweise dieselbe Exception
     *    nochmals produzieren (Connection-Reset etc.) und nur den
     *    strukturierten Reporting-Pfad mit einer redundanten
     *    Sekundär-Exception verlärmen.
     * 2. `conn.autoCommit = true` setzen (symmetrisch zur DDL-Phase aus
     *    `openTable(...)` / §6.7 M8 — `enableTriggers(...)` MUSS unter
     *    `autoCommit = true` laufen, sonst hängt der Reenable in einer
     *    impliziten DDL-Transaktion). Im `FAILED`-Pfad ist auch dieser
     *    Schritt best-effort und fängt eigene Exceptions weg.
     * 3. Wenn `disableTriggers(...)` in dieser Session jemals erfolgreich
     *    war (Tracking via internem Boolean), `enableTriggers(...)`
     *    idempotent erneut aufrufen. Etwaige Exception aus diesem Schritt
     *    landet als strukturierter Dreiklang in
     *    `FailedFinishInfo.closeCauseMessage`/`closeCauseClass`/
     *    `closeCauseStack` (siehe F3 / M-R7) — `close()` selbst wirft
     *    NICHT.
     * 4. **H-R3 — Session-lokale FK-Schalter zurücksetzen**: wenn der
     *    Writer in dieser Session `SET FOREIGN_KEY_CHECKS = 0` (MySQL)
     *    oder `PRAGMA foreign_keys = OFF` (SQLite) gesetzt hat (Tracking
     *    via internem Boolean analog zum Trigger-Flag), MUSS der Writer
     *    den jeweiligen Schalter hier explizit wieder aktivieren —
     *    `SET FOREIGN_KEY_CHECKS = 1` bzw. `PRAGMA foreign_keys = ON` —
     *    BEVOR die Connection an den Pool zurückgegeben wird. Hintergrund
     *    ist §6.8.2: HikariCP setzt beliebige Session-Variablen beim
     *    Check-in NICHT automatisch zurück; ohne dieses Schritt-4 würde
     *    der nächste Borrower dieser Connection mit deaktivierten
     *    FK-Checks weiterarbeiten (im selben `d-migrate`-Prozess bei
     *    Multi-Table-Imports direkt reproduzierbar). Der Reset ist
     *    ebenfalls best-effort: wenn er wirft, landet die Exception im
     *    strukturierten Reporting-Pfad, `close()` selbst wirft NICHT. PG
     *    überspringt Schritt 4 komplett, weil §6.8.1 auf PG gar keinen
     *    `--disable-fk-checks`-Pfad hat.
     * 5. Connection an den Pool zurückgeben (`pool.release(conn)` o.ä.).
     *    Auch hier: Fehler werden geloggt und in den strukturierten
     *    Reporting-Pfad eingespeist, NICHT geworfen.
     *
     * **F3 — `close()` wirft nicht**: `close()` ist `AutoCloseable` und wird
     * üblicherweise aus `try-with-resources`/`use { ... }`-Blöcken
     * aufgerufen. Würde `close()` selbst werfen, würde das eine bereits
     * im Block aufgetretene Nutzexception maskieren. Konkret bedeutet das
     * für den H4-Folgepfad (`finishTable()` hat
     * `FinishTableResult.PartialFailure` geliefert): der zweite,
     * close-interne `enableTriggers(...)`-Versuch ist zwar
     * fachlich relevant, aber sein Fehlschlag darf NICHT als Exception aus
     * `close()` herausfallen. Stattdessen:
     *
     * 1. Der zweite Reenable-Fehler wird strukturiert in
     *    `FailedFinishInfo.closeCauseMessage`/`closeCauseClass`/
     *    `closeCauseStack` (§3.6.2, M-R7) gespeichert; der Importer
     *    setzt die drei Felder direkt nach dem `close()`-Aufruf.
     * 2. `close()` selbst kehrt normal zurück.
     * 3. Das Reporting in §6.15 rendert beide Causes (den ursprünglichen
     *    Reenable-Fehler aus `finishTable()` als
     *    `causeClass: causeMessage`, den zweiten Versuch aus `close()`
     *    als `closeCauseClass: closeCauseMessage`) im
     *    `PARTIAL FAILURE`-Block. Der gerenderte Stack-Trace landet nur
     *    im JSON-Output-Pfad, nicht im Klartext-Report.
     *
     * Für alle anderen `close()`-Fehlerpfade (Connection-Return scheitert,
     * `autoCommit`-Reset wirft) gilt dieselbe Regel: loggen, in den
     * strukturierten Reporting-Pfad einspeisen, NICHT werfen. Damit ist die
     * `try-with-resources`-Semantik im Importer eindeutig: die einzige
     * Exception, die der Importer aus dem Session-Lifecycle sehen kann,
     * stammt aus `write(...)`/`commitChunk()`/`rollbackChunk()`/
     * `finishTable()` — niemals aus `close()`.
     */
    override fun close()
}

/**
 * Per-Chunk-Ergebnis eines [TableImportSession.write].
 *
 * - `rowsInserted`: regulär per `INSERT` geschriebene Rows
 * - `rowsUpdated`: per `ON CONFLICT ... DO UPDATE` (PG/SQLite) bzw.
 *   `ON DUPLICATE KEY UPDATE` (MySQL) modifizierte Rows — entsteht nur im
 *   `--on-conflict update`-Pfad (§6.12.2)
 * - `rowsSkipped`: per `ON CONFLICT DO NOTHING` (PG/SQLite) bzw.
 *   `INSERT IGNORE` (MySQL) übersprungene Rows — entsteht nur im
 *   `--on-conflict skip`-Pfad. **Bewusst nicht** für `--on-error skip|log`:
 *   die `--on-error`-Politik aggregiert chunkweise verlorene Rows in
 *   [TableImportSummary.rowsFailed], NICHT in `rowsSkipped` (siehe §3.6.2,
 *   §6.5 und Finding M3/K2).
 * - `rowsUnknown`: **R10 — MySQL `SUCCESS_NO_INFO (-2)`-Fallback**.
 *   Wenn der MySQL-JDBC-Batch pro-Row-Counts vorenthält und stattdessen
 *   `Statement.SUCCESS_NO_INFO` liefert, kann der Writer `inserted`,
 *   `updated` und `skipped` für diesen Batch nicht trennen. Statt die
 *   Unklarheit stillschweigend als `inserted` zu zählen (was sowohl
 *   Delta-Imports per `--on-conflict update` strukturell als 100 %
 *   Inserts maskieren würde als auch `--on-conflict skip` als 100 %
 *   Inserts maskieren würde), legt der Writer die Row-Anzahl dieses
 *   Batches in `rowsUnknown` ab.
 *   **M-R8 — Regel gilt für UPSERT **und** Skip**: Sowohl der
 *   `--on-conflict update`-Pfad (`ON DUPLICATE KEY UPDATE`) als auch
 *   der `--on-conflict skip`-Pfad (`INSERT IGNORE`) laufen unter
 *   MySQL über `executeBatch()` und können deshalb beide das
 *   `SUCCESS_NO_INFO`-Verhalten des Treibers treffen. In beiden
 *   Pfaden wandert die Batch-Größe bei `-2` in `rowsUnknown`, nicht
 *   in `rowsInserted`, `rowsUpdated` oder `rowsSkipped`. Der
 *   `--on-conflict abort`-Pfad kennt dagegen keine semantische
 *   Mehrdeutigkeit (alle geschriebenen Rows sind per Definition
 *   Inserts, weil eine Konflikt-Row den Batch ja abbrechen würde) und
 *   rechnet `SUCCESS_NO_INFO` korrekt als `rowsInserted += batchSize`
 *   weiter. Alle Nicht-MySQL-Writer-Pfade und alle nicht-
 *   batch-basierten Pfade liefern `rowsUnknown = 0L`. Reporting und
 *   JSON-Output (§6.15) zeigen das Feld als getrennten Counter und
 *   setzen einen pfadspezifischen Hinweis:
 *   - UPSERT-Fallback: „inserts/updates not exactly distinguishable
 *     for N rows (MySQL SUCCESS_NO_INFO)"
 *   - Skip-Fallback: „inserts/skips not exactly distinguishable for N
 *     rows (MySQL SUCCESS_NO_INFO)"
 */
data class WriteResult(
    val rowsInserted: Long,
    val rowsUpdated: Long,
    val rowsSkipped: Long,
    val rowsUnknown: Long = 0L,
)

/**
 * Ergebnis von [TableImportSession.finishTable]. Trennt H4 explizit:
 *
 * - [Success]: Reseeding und Trigger-Reenable erfolgreich. Der Importer
 *   übernimmt die `adjustments` in das reguläre `TableImportSummary`.
 * - [PartialFailure]: Reseeding hat geklappt und ist in der DB sichtbar,
 *   aber das anschließende Trigger-Reenable schlägt fehl. [cause] trägt
 *   in diesem Pfad den **originalen [Throwable]**, damit der Importer
 *   (nicht der Writer) ihn beim Übertragen in
 *   `TableImportSummary.failedFinish` in die strukturierten
 *   `causeMessage`/`causeClass`/`causeStack`-Felder serialisieren kann
 *   (siehe §3.6.2 / M-R7). Der Importer markiert die Tabelle als
 *   „committed mit Reenable-Fehler" und mappt das Endergebnis auf
 *   Exit 5. Diese Variante existiert ausschließlich, damit der Reporting-
 *   Pfad nicht auf einer String-Fehlermeldung beruht und Phase F direkt
 *   per `failedFinish.causeClass` asserten kann.
 *
 * Wenn [reseedGenerators] selbst wirft, wird `finishTable()` die Exception
 * direkt durchreichen — es gibt dann definitionsgemäß noch keine
 * Adjustments und damit auch keinen `PartialFailure`-Fall.
 */
sealed class FinishTableResult {
    data class Success(val adjustments: List<SequenceAdjustment>) : FinishTableResult()
    data class PartialFailure(
        val adjustments: List<SequenceAdjustment>,
        val cause: Throwable,
    ) : FinishTableResult()
}

class UnsupportedTriggerModeException(message: String) : RuntimeException(message)
```

`ImportSchemaMismatchException` liegt bewusst **nicht** in
`hexagon:ports`, sondern in `hexagon:core` (z.B.
`dev.dmigrate.core.data`), weil sowohl `adapters:driven:formats` (Reader) als
auch `adapters:driven:streaming` und die Writer-Schicht dieselbe fachliche Ausnahme
verwenden, ohne dass `formats` eine Modul-Kante zu `hexagon:ports` bekommt.

#### 3.1.2 Erweiterung der bestehenden Registries

`DataReaderRegistry` bekommt ein Pendant: entweder `DataWriterRegistry` als
eigene `object`-Registry, oder die bestehende Registry wird zu
`DataAdapterRegistry` umbenannt. Wir entscheiden uns für **getrennte
Registries** (siehe §6.10), um die Symmetrie zu Reader/Writer in `formats`
beizubehalten und die Bootstrap-Pfade pro Treiber unverändert zu lassen.

```kotlin
object DataWriterRegistry {
    private val writers = mutableMapOf<DatabaseDialect, DataWriter>()
    fun registerDataWriter(writer: DataWriter)
    fun dataWriter(dialect: DatabaseDialect): DataWriter
    fun clear()
}
```

Treiber-Bootstrap-Objects (`PostgresDriver`, `MysqlDriver`, `SqliteDriver`)
ergänzen ihre `register()`-Methode um den jeweiligen `*DataWriter`.

#### 3.1.3 Sequence- und Trigger-Hooks

```kotlin
package dev.dmigrate.driver.data

/**
 * Dialekt-spezifische Operationen, die der StreamingImporter rund um den
 * Schreib-Zyklus aufruft. Der DataWriter wird per `schemaSync()`
 * gefragt, weil die konkrete Implementierung dialektabhängig ist.
 *
 * Plan §6.6 / §6.7 / design-import-sequences-triggers.md.
 */
interface SchemaSync {
    /**
     * Wird nach Abschluss aller Chunks einer Tabelle aufgerufen. Der
     * Implementierer ermittelt den höchsten importierten Wert der
     * Identity-/Sequence-Spalte und führt die Sequence darauf nach.
     *
     * @return Beschreibung der durchgeführten Anpassungen (für den
     *   Import-Report)
     */
    fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment>

    /**
     * Wird writer-intern aus `openTable(...)` vor dem ersten Chunk
     * aufgerufen, wenn `--trigger-mode = disable` ist. Wirft
     * `UnsupportedTriggerModeException`, wenn der Dialekt das nicht sicher
     * unterstützt (Plan §6.7 / Design-Doc §6.2).
     */
    fun disableTriggers(conn: Connection, table: String)

    /**
     * Sicherheits-Pre-Flight für `trigger-mode = strict`. Wird writer-intern
     * aus `openTable(...)` aufgerufen und bricht mit klarer Fehlermeldung ab,
     * wenn auf der Zieltabelle Trigger vorhanden sind.
     */
    fun assertNoUserTriggers(conn: Connection, table: String)

    /**
     * Reaktiviert die in [disableTriggers] deaktivierten Trigger. Wird bei
     * erfolgreichem Tabellenabschluss und im Fehlerpfad in `close()`
     * aufgerufen — Ausführung muss idempotent sein und Fehler hier sind
     * harte Fehler (Design-Doc §7.3).
     */
    fun enableTriggers(conn: Connection, table: String)
}

data class SequenceAdjustment(
    val table: String,
    val column: String,
    val sequenceName: String?,    // PG: explizit; MySQL/SQLite: null
    val newValue: Long,
)

/**
 * Writer-side Spalten-Metadaten für eine Import-Zieltabelle. Trägt
 * den JDBC-Typcode mit, den der [ValueDeserializer] (in `formats`)
 * für die String-/Number-zu-Java-Konvertierung beim Import braucht.
 *
 * Lebt **in `hexagon:ports`** (nicht in `hexagon:core`), weil:
 *
 * - `core.ColumnDescriptor` soll JDBC-frei bleiben — die 0.3.0-
 *   Architektur-Regel. Ein `jdbcType: Int?`-Slot dort wäre
 *   semantisch JDBC-coupled, auch wenn syntaktisch nur ein Int.
 * - `formats` darf von `hexagon:ports` nicht abhängen (hexagonale
 *   Schicht-Trennung), also kann `formats.JdbcTypeHint` nicht
 *   direkt von `hexagon:ports` referenziert werden.
 * - `hexagon:ports` ist der natürliche Ort für Writer-spezifische
 *   Metadaten, weil der Writer hier lebt und `ResultSetMetaData`
 *   ohnehin liest.
 *
 * Der Phase-D `StreamingImporter` (in `adapters:driven:streaming`, das
 * BEIDE Module kennt) konvertiert `TargetColumn` zu
 * `formats.JdbcTypeHint` und baut daraus die Lookup-Closure für den
 * `ValueDeserializer`. Damit bleibt jede Modul-Kante intakt.
 *
 * Vergleich mit [ColumnDescriptor]: `TargetColumn` enthält dieselben
 * Felder (`name`, `nullable`, `sqlTypeName`) plus den nicht-nullable
 * `jdbcType`. Bewusste leichte Duplikation statt Composition (kein
 * `descriptor: ColumnDescriptor`-Wrapper), damit der Writer-Pfad
 * direkt auf Felder zugreifen kann ohne `tc.descriptor.name`-
 * Boilerplate.
 *
 * @property name Spaltenname
 * @property nullable Ob die Spalte NULL erlauben darf
 *   (`ResultSetMetaData.isNullable(i) != columnNoNulls`)
 * @property jdbcType JDBC-Typcode aus
 *   `ResultSetMetaData.getColumnType(i)` (`java.sql.Types.*`)
 * @property sqlTypeName Optional: dialekt-spezifischer Type-Name aus
 *   `ResultSetMetaData.getColumnTypeName(i)`. Sekundärer Hint für
 *   Pfade, in denen [jdbcType] mehrdeutig ist (PG `Types.OTHER` →
 *   `"uuid"`/`"jsonb"`/`"interval"`, MySQL `Types.BIT` → `"BIT(1)"`
 *   vs `"BIT(8)"`).
 */
data class TargetColumn(
    val name: String,
    val nullable: Boolean,
    val jdbcType: Int,
    val sqlTypeName: String? = null,
)
```

`DataWriter.schemaSync()` ist **pflichtig**. Es gibt absichtlich keine
Default-No-Op-Implementierung; vergessene Treiber-Overrides sollen zur
Compile-Zeit oder spätestens im Review auffallen, nicht als stiller
Laufzeit-Fehler.

### 3.2 `adapters:driven:driver-postgresql`

```
adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/
├── PostgresDataWriter.kt       # JDBC Batch-INSERT, COPY-Pfad als Optimierung in 1.0.0
├── PostgresSchemaSync.kt       # setval(), session_replication_role NICHT — siehe §6.8.1
└── PostgresDriver.kt           # erweitert um registerDataWriter
```

PG-spezifika:

- **Batch-INSERT**: `INSERT INTO "schema"."tab" ("c1", "c2") VALUES (?, ?)`
  mit `addBatch()` pro Row und `executeBatch()` einmal pro Chunk.
  COPY-FROM ist ~10× schneller, kommt aber erst in 1.0.0 als Performance-Pfad.
- **Identity-/Sequence-Support**: PostgreSQL `SERIAL`/`BIGSERIAL` sowie
  `GENERATED { BY DEFAULT | ALWAYS } AS IDENTITY` werden in 0.4.0 explizit
  unterstützt. `pg_get_serial_sequence(quoted_table, column)` wird für alle
  drei Varianten als Sequence-Lookup benutzt.
- **INSERT gegen `GENERATED ALWAYS AS IDENTITY`**: wenn eine importierte
  Spalte im Ziel als `GENERATED ALWAYS` erkannt wird, erzeugt der Writer
  `INSERT ... OVERRIDING SYSTEM VALUE ...`, damit explizite Importwerte
  zulässig sind statt erst beim ersten betroffenen Datensatz zu scheitern.
  **K1 — finale Entscheidung**: `OVERRIDING SYSTEM VALUE` wird
  **ausschließlich** für `GENERATED ALWAYS` ausgegeben, NICHT für
  `GENERATED BY DEFAULT`. Letzteres akzeptiert explizite Insert-Werte
  ohnehin ohne Zusatz, und das selektive Spielen erzeugt klarere Test-
  Pfade in Phase C/F (ein einziger Pfad pro Identity-Form, kein
  „darf auch"-Schalter).
- **Sequence-Reseeding**: pro Identity-/SERIAL-Spalte die zugehörige Sequence
  via `pg_get_serial_sequence(quoted_table, column)` ermitteln und mit
  dem üblichen `setval(sequence, MAX(column), true)`-Idiom nachführen
  (äquivalent zu `pg_dump`). `nextval()` wird hierfür bewusst NICHT als
  Hilfskonstrukt benutzt; das wäre selbst schreibend und würde pro Reseed
  unnötig einen Sequence-Wert verbrennen. Die Nachführung ist ein
  Erfolgsabschluss nach Tabellen-Import, aber kein Lock gegen parallele
  Writer in anderen Sessions.
- **JSON/JSONB-Binding**: PG-Writer bindet `JSONB` nicht als plain
  `VARCHAR`, sondern über den JDBC-Pfad für `Types.OTHER` bzw. ein
  passendes PG-Objekt, damit `json`/`jsonb`-Spalten nicht am
  Treiber-Typecheck scheitern.
- **INTERVAL-Binding**: PG-Writer bindet `INTERVAL` nicht als generischen
  Text, sondern über den PG-spezifischen JDBC-Pfad (`PGobject("interval", value)`
  bzw. äquivalenten `Types.OTHER`-Pfad).
- **Trigger-Disable** (User-Trigger): `ALTER TABLE … DISABLE TRIGGER USER`
  pro Tabelle. `session_replication_role = replica` ist nicht zulässig
  (siehe §6.7) — zu breite Wirkung, Sicherheitsrisiko.
- **FK-Constraints sind NICHT generisch deaktivierbar** (F42, siehe §6.8.1).
  `--disable-fk-checks` auf PG → Exit 2 mit Hinweis auf `--schema` (topo-Sort)
  oder `DEFERRABLE`-Constraints. PG bekommt damit weder
  `ALTER TABLE … DISABLE TRIGGER ALL` (Superuser) noch
  `session_replication_role` (zu breit, oft auf Managed-Diensten gesperrt).
- **`autoCommit=false`** für Chunk-Transaktionen (analog zu Read-Pfad
  Plan-0.3.0 §6.12).

### 3.3 `adapters:driven:driver-mysql`

```
adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/
├── MysqlDataWriter.kt
├── MysqlSchemaSync.kt
└── MysqlDriver.kt
```

MySQL-spezifika:

- **Batch-INSERT** mit `rewriteBatchedStatements=true`-Hint im JDBC-URL
  (vom `MysqlJdbcUrlBuilder` bereits gesetzt — Verifikation im echten
  Writer-Pfad in Phase C/F).
  **L14 — Verifikation ist strukturell, nicht performanceabhängig**: Ein
  Durchsatz-basiertes Proxy („Batch schreibt mind. X rows/s") wäre
  brüchig (Hardware-Jitter, Docker-Layer, CI-Last) und würde die
  eigentliche Eigenschaft nicht absichern. Der Phase-C-Pflichttest
  greift stattdessen **direkt auf die aktive Setting-Abfrage** des
  MySQL-JDBC-Treibers zu: die offene `Connection` wird per
  `conn.unwrap(com.mysql.cj.jdbc.JdbcConnection::class.java)`
  entpackt, und der Test asserted
  `unwrapped.propertySet.getBooleanProperty(PropertyKey.rewriteBatchedStatements).value
  == true` (bzw. die zum verwendeten Treiber-Major passende
  Property-Key-Variante). Damit ist die Garantie an die
  Treiber-interne Config gekoppelt und nicht an fragile
  Performance-Zahlen. Der Test bekommt einen Fallback-Pfad, der bei
  älteren/modifizierten Treiber-Versionen, die den internen
  `PropertyKey`-Enum nicht exportieren, per JDBC-URL-Parse prüft
  (`MysqlJdbcUrlBuilder.build(...).contains("rewriteBatchedStatements=true")`).
  Beide Wege sind strukturell und deterministisch; der Phase-F-E2E-Test
  läuft dann zusätzlich einen echten Multi-Row-Insert gegen eine
  Testcontainers-MySQL-Instanz und verifiziert per `SHOW VARIABLES`
  bzw. Server-seitigem Logging, dass der konsolidierte
  `INSERT INTO ... VALUES (...),(...),(...)`-Pfad tatsächlich greift.
- **AUTO_INCREMENT-Reseeding**: `ALTER TABLE \`table\` AUTO_INCREMENT = N`
  mit `N = MAX(autoinc_column) + 1`. Dialect-Quote ist Backtick.
  **H5-Spezialfall „leere Tabelle nach `--truncate` (DELETE FROM)"**: nach
  einem `DELETE FROM` ohne anschließende neue Daten ist `MAX(col) = NULL`,
  und die Standard-Reseed-Regel aus §6.6 Schritt 3 wäre ein No-op.
  Trotzdem soll der nächste manuelle `INSERT` deterministisch wieder bei
  `1` starten — sonst hängt der `AUTO_INCREMENT`-Zähler weiterhin auf dem
  alten Wert (MySQL setzt ihn nach `DELETE FROM` ausdrücklich nicht
  zurück). Der MySQL-Reseed-Pfad muss diesen Sonderfall daher explizit
  behandeln: wenn der Importer dem Writer signalisiert, dass dieser Tabelle
  ein `--truncate`-Pre-Step vorausging UND die Tabelle danach leer geblieben
  ist (`MAX(col) = NULL`), führt der Writer ein
  `ALTER TABLE \`table\` AUTO_INCREMENT = 1` aus und schreibt einen
  entsprechenden `SequenceAdjustment(newValue = 1)` in den Report. Ohne
  `--truncate` bleibt das Standardverhalten aus §6.6: `MAX = NULL` →
  No-op. **R3 — Signaling**: die Information „Tabelle wurde im aktuellen
  Lauf truncated" reicht der Importer dem Writer über
  `TableImportSession.markTruncatePerformed()` (§3.1.1) GENAU auf der
  Session der jeweiligen Tabelle weiter — bewusst NICHT über
  `ImportOptions`, weil der CLI-Flag global ist und bei Multi-Tabellen-
  Imports nicht zwingend für jede Tabelle gilt. Der MySQL-Writer
  speichert das Signal als Boolean im Session-State und prüft es in
  `finishTable()` zusammen mit `MAX(col) = NULL`.
- **UPSERT-Zählung**: für `ON DUPLICATE KEY UPDATE` muss der Writer die
  per-Row-Rückgabewerte aus `executeBatch()` auswerten, nicht nur eine
  Gesamtsumme. MySQL wird auf `WriteResult` normalisiert als
  `1 -> rowsInserted`, `2 -> rowsUpdated`, `0 -> rowsUpdated`
  (idempotentes No-Op-Update auf bestehender Row), und
  `Statement.SUCCESS_NO_INFO (-2) -> rowsUnknown` (R10 — der ganze
  Batch wandert in `rowsUnknown` statt in `rowsInserted`, weil der
  Treiber Insert/Update für diesen Batch nicht trennt; siehe §6.12.2).
- **Skip-Zählung (`INSERT IGNORE`)**: `--on-conflict skip` nutzt auf
  MySQL `INSERT IGNORE` und läuft ebenfalls über `executeBatch()`. Der
  Writer zählt hier pro-Row: `1 -> rowsInserted`, `0 -> rowsSkipped`
  (Row wurde wegen Unique-/PK-Konflikt ignoriert). **M-R8 —
  `SUCCESS_NO_INFO` gilt analog zur UPSERT-Spalte**: liefert der
  Treiber für einen `INSERT IGNORE`-Batch `-2`, wandert die Batch-
  Größe in `rowsUnknown` (nicht in `rowsInserted` oder `rowsSkipped`),
  und der Report hängt den Skip-spezifischen Hinweis
  „inserts/skips not exactly distinguishable for N rows (MySQL
  SUCCESS_NO_INFO)" an.
- **Trigger-Disable**: in 0.4.0 **nicht generisch unterstützt**. Versuch,
  Trigger zu deaktivieren, wirft `UnsupportedTriggerModeException` mit
  Verweis auf Design-Doc §6.2. Default `fire` läuft normal.
- **Foreign-Key-Checks**: optional via `SET FOREIGN_KEY_CHECKS=0` für die
  Session — nur wenn `--disable-fk-checks` gesetzt ist (siehe §6.8).
  **H-R3**: der Writer merkt sich in einem internen Boolean
  `fkChecksDisabled`, dass er den Schalter gesetzt hat, und reaktiviert
  ihn im R6-Cleanup-Pfad (§3.1.1, Schritt 4) per
  `SET FOREIGN_KEY_CHECKS=1` auf **derselben** Connection, BEVOR sie an
  den HikariCP-Pool zurückgegeben wird. Ohne diesen Reset würde der
  nächste Borrower der Connection im selben Prozess (z.B. der Import der
  nächsten Tabelle) mit deaktivierten FK-Checks weiterarbeiten.

### 3.4 `adapters:driven:driver-sqlite`

```
adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/
├── SqliteDataWriter.kt
├── SqliteSchemaSync.kt
└── SqliteDriver.kt
```

SQLite-spezifika:

- **Batch-INSERT** mit `addBatch()`. SQLite-jdbc unterstützt das nativ und
  ist für die typischen Importgrößen ausreichend schnell.
- **AUTOINCREMENT-Reseeding**: `INSERT OR REPLACE INTO sqlite_sequence
  (name, seq) VALUES ('table', N)` mit `N = MAX(rowid)`. Nur relevant für
  Tabellen mit `INTEGER PRIMARY KEY AUTOINCREMENT`. Für „normale"
  `INTEGER PRIMARY KEY`-Tabellen ist die Folge-ID-Vergabe nicht
  persistiert — kein Reseeding nötig (Design-Doc §6.3 offene Frage:
  geklärt → kein Eingriff).
- **F4 — Truncate-mit-Leerzustand für `AUTOINCREMENT`-Tabellen**:
  parallel zu MySQL H5 (§3.3) muss der SQLite-Reseed-Pfad einen
  expliziten Sonderfall behandeln. Nach `--truncate` (= `DELETE FROM`,
  siehe §6.14) bleibt der `sqlite_sequence`-Eintrag für eine
  `INTEGER PRIMARY KEY AUTOINCREMENT`-Tabelle stehen — der nächste
  Insert würde sonst mit dem alten Hochstand + 1 statt mit `1`
  fortfahren, inkonsistent zu PG (`TRUNCATE` räumt das auf) und MySQL
  H5 (Reset auf 1). Wenn der Importer dem Writer signalisiert, dass
  dieser Tabelle ein `--truncate`-Pre-Step vorausging UND die Tabelle
  danach leer geblieben ist (`MAX(rowid) = NULL` UND
  `INTEGER PRIMARY KEY AUTOINCREMENT`), führt der SQLite-Writer ein
  `DELETE FROM sqlite_sequence WHERE name = ?` aus (nicht
  `INSERT OR REPLACE ... seq = 0`, weil das einen 0-Eintrag stehen
  ließe — `DELETE` entspricht semantisch dem PG-`TRUNCATE`-Reset) und
  schreibt einen `SequenceAdjustment(newValue = 0)` in den Report. Ohne
  `--truncate` bleibt das Standardverhalten aus §6.6 Schritt 3:
  `MAX = NULL` → No-op. **R3**: die `truncatePerformed`-Information
  kommt über `TableImportSession.markTruncatePerformed()` (§3.1.1)
  rein, identisch zur MySQL-H5-Variante in §3.3 — es gibt nur einen
  einzigen Signaling-Pfad pro Dialekt.
- **Trigger-Disable**: nicht unterstützt (Design-Doc §6.3). `disable` →
  `UnsupportedTriggerModeException`.
- **Foreign-Key-Checks**: temporär `PRAGMA foreign_keys = OFF` während des
  Imports, am Ende `PRAGMA foreign_keys = ON` zurückgesetzt — nur mit
  `--disable-fk-checks`. **H-R3**: der Reset läuft explizit im
  R6-Cleanup-Pfad (§3.1.1, Schritt 4) auf **derselben** Connection,
  BEVOR sie an den Pool zurückgegeben wird; der Writer hält dazu einen
  internen Boolean `fkChecksDisabled` analog zum MySQL-Writer. Für
  File-basierte SQLite-Instanzen greift dieselbe Pool-Leak-Semantik wie
  bei MySQL — der nächste Borrower der Connection würde sonst mit
  deaktivierten FK-Checks weiterarbeiten.

### 3.5 `adapters:driven:formats`

#### 3.5.1 Reader-Pendant zum Writer-Trio

```
adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/
├── DataChunkReader.kt              # neu, Interface
├── DataChunkReaderFactory.kt       # neu, Interface
├── DefaultDataChunkReaderFactory.kt# neu, Implementierung
├── ImportOptions.kt                # neu, parallel zu ExportOptions
├── ValueDeserializer.kt            # neu, Inverse zu ValueSerializer
├── EncodingDetector.kt             # neu, BOM-Sniff (§6.9)
├── json/JsonChunkReader.kt         # neu, DSL-JSON Streaming
├── yaml/YamlChunkReader.kt         # neu, SnakeYAML Engine Streaming
└── csv/CsvChunkReader.kt           # neu, uniVocity CsvParser
```

```kotlin
interface DataChunkReader : AutoCloseable {
    /**
     * Liest die nächsten bis zu `chunkSize` Rows. Liefert `null`, wenn die
     * Eingabe vollständig konsumiert wurde.
     *
     * **L1**: `chunkSize` wird einmalig bei [DataChunkReaderFactory.create]
     * gesetzt und vom Reader für alle Folgeaufrufe gespeichert. `nextChunk()`
     * nimmt deshalb kein Argument mehr — das spiegelt die 0.3.0-Streaming-
     * API (`PipelineConfig.chunkSize`) und vermeidet Argument-Drift, wenn
     * z.B. ein Reader-Refactor die Chunk-Größe pro Aufruf wechseln sollen
     * würde.
     *
     * **Anders als beim Reader-Vertrag in 0.3.0** (Plan §6.17) gibt es hier
     * KEINE Pflicht, einen "Empty-Chunk mit Spalten" zu emittieren: bei
     * leeren selbstbeschreibenden Eingaben (`[]` in JSON/YAML, leere
     * CSV-Datei mit nur einer Header-Zeile) darf der Reader sofort `null`
     * liefern. Spalten-Metadaten holt der [StreamingImporter] aus dem
     * Zielschema (siehe §6.4) — der Reader ist NICHT die autoritative
     * Quelle dafür.
     *
     * Die im zurückgelieferten [DataChunk] enthaltenen `columns` sind
     * file-derived (nur Namen, `sqlTypeName = null`) und werden vom
     * Importer nur für Header-Validierung, Reorder-Mapping und Debugging
     * verwendet. Vor `session.write(...)` normalisiert der Importer
     * `chunk.columns` auf die tatsächlich gebundene Target-Teilmenge und die
     * Row-Reihenfolge auf eben diese Binding-Liste.
     */
    fun nextChunk(): DataChunk?

    /**
     * Optionale, file-derived Header-Spaltennamen. Wird vom [StreamingImporter]
     * benutzt, um Header-zu-Target-Mapping zu validieren (verfehlte Spalten
     * → Exit 3 mit klarer Meldung). Spalten-**Typen** kommen IMMER aus dem
     * Zielschema, nie aus dem Reader.
     *
     * Nach `create(...)` bzw. spätestens nach dem ersten [nextChunk]-Aufruf
     * liefert die Methode einen deterministischen Snapshot der bekannten
     * Header. Für Header-only-CSV darf das also auch dann eine Liste sein,
     * wenn `nextChunk()` direkt `null` liefert. Für Eingaben ohne
     * Header-Information (`csvNoHeader = true`) oder leere JSON/YAML-Arrays
     * bleibt der Wert `null`.
     */
    fun headerColumns(): List<String>?
}

interface DataChunkReaderFactory {
    /**
     * `chunkSize` wird hier einmalig durchgereicht (L1) und im erzeugten
     * Reader gespeichert. Der `StreamingImporter` ruft `create(...)` mit
     * `chunkSize = config.chunkSize` aus dem [PipelineConfig], identisch
     * zum 0.3.0-Exportpfad.
     */
    fun create(
        format: DataExportFormat,
        input: InputStream,
        table: String,
        chunkSize: Int,
        options: ImportOptions = ImportOptions(),
    ): DataChunkReader
}
```

**Streaming-Vertrag pro Format**:

| Format | Streaming-Mechanik | `headerColumns()`-Quelle |
|---|---|---|
| JSON | DSL-JSON's pull-Parser (`JsonReader.next()`/`getNextToken()`) — wir parsen das Top-Level-Array tokenweise und produzieren `DataChunk`s zu je `chunkSize` Objekten. Phase A enthält dafür explizit einen Go/No-Go-Spike mit 100-MB-Fixture; wenn die Pull-API dabei keinen konstanten Speicherpfad liefert, wird die Library-Entscheidung vor der eigentlichen Reader-Implementierung neu geöffnet. | Schlüssel des ersten Objekts; bei `[]` → `null` |
| YAML | SnakeYAML Engine `Parse` API (Event-basiert). Der bestehende `Dump`-Pfad ist Tree-basiert; für das Lesen benutzen wir den Event-Stream-Pfad, der auch sehr große Sequenzen verträgt. | Schlüssel des ersten Mappings; bei `[]` → `null` |
| CSV | uniVocity `CsvParser` mit `IterableResult` — schon nativ chunked. Wir wrappen das in unseren `DataChunkReader`-Vertrag. | Header-Zeile, sofern nicht `csvNoHeader = true` |

Für JSON/YAML mit Objekt-/Mapping-Rows gilt zusätzlich: das Feldset der
ersten Row ist für die Tabelle autoritativ. Der Reader ist dafür
verantwortlich, spätere Rows gegen dieses Feldset zu normalisieren:
fehlende bekannte Schlüssel werden bereits im Reader als `null` in den
positionalen Slots materialisiert; zusätzliche, in der ersten Row
unbekannte Schlüssel führen bereits im Reader zu einem
`ImportSchemaMismatchException`, der dann über die `--on-error`-Politik
des Importers läuft.

**F8 — lookup by name, not by encounter order**: JSON-Objektschlüssel
sind per Spec ungeordnet, und auch YAML-Mappings dürfen ihre Keys in
beliebiger Reihenfolge serialisieren. Der Reader MUSS daraus folgend
die positionale Slot-Belegung jeder Row anhand des **Schlüsselnamens**
aus der First-Row-Map ableiten — NICHT anhand der Encounter-Reihenfolge
der Keys im aktuellen Objekt. Konkret baut der Reader nach der ersten
Row eine `Map<String, Int>` vom Feldnamen auf den positionalen Slot;
für jede Folge-Row wird pro `(key, value)`-Paar genau dieser Index
nachgeschlagen und der Wert dort einsortiert. Schreibt z.B. die erste
Row `{"a": 1, "b": 2}` und eine spätere Row `{"b": 3, "a": 4}`, müssen
beide auf `[1, 2]` und `[4, 3]` materialisiert werden, nicht auf
`[1, 2]` und `[3, 4]`. Die Phase-B-Reader-Tests enthalten dafür eine
explizite Permutations-Fixture mit reordered Keys, sodass eine
versehentliche Position-by-Encounter-Implementierung sofort kippt.

**R9 — Edge-Case „leere erste Row"**: Wenn die erste Row eines
JSON-/YAML-Inputs ein **leeres Objekt/Mapping** ist (`[{}, {"a": 1}]`,
`[\n  {}\n  {a: 1}\n]`), setzt der Reader das autoritative Feldset auf
die **leere Menge**. `headerColumns()` liefert dann eine leere Liste,
nicht `null`. Jede nicht-leere Folge-Row hat damit automatisch
mindestens einen „zusätzlichen, in der ersten Row unbekannten Schlüssel"
und löst die `ImportSchemaMismatchException`-Regel von oben aus, die
über `--on-error` läuft. Das ist explizit Vertrag, kein Edge-Case-Bug:
wer eine Datei mit lauter leeren Objekten am Anfang hat, soll früh
einen klaren Schema-Mismatch sehen statt eines stillen No-op-Imports.
Phase B nimmt dafür eine Mini-Fixture (`[{}, {"a": 1}]`) in die
Reader-Tests auf.

> **H3 — Round-Trip-Garantie aus 0.3.0**: Diese „erste Row ist autoritativ"-
> Regel funktioniert für Round-Trips nur, weil der 0.3.0-`data export`
> jedes Format-Writer-Trio (`JsonChunkWriter.writeRow`,
> `YamlChunkWriter.write`, `CsvChunkWriter.write`) alle in `begin(...)`
> übergebenen `columns` **pro Row** materialisiert — auch dann, wenn der
> Wert `null` ist. Damit ist garantiert, dass die erste Row eines
> 0.3.0-Exports immer den vollständigen Spalten-Header trägt. Diese
> Eigenschaft ist ab jetzt eine **Pflicht-Property** der 0.3.0-Writer und
> wird in Phase B als Property-Test gegen alle drei Writer abgesichert
> (siehe §4 Phase B Schritt 11). Wer einen externen Datensatz importieren
> will, in dem optionale Felder per Row ausgelassen werden, muss vorab eine
> „canonical first row" mit allen Feldern hinzufügen — der CLI-Help-Text
> für `data import` weist explizit auf diese JSON/YAML-Asymmetrie hin.

Für JSON gilt außerdem: 0.4.0 akzeptiert nur ein Top-Level-Array von
Objekten. Ein nacktes Top-Level-Objekt oder Wrapper-Formen wie
`{ "rows": [...] }` sind Formatfehler. NDJSON ist in 0.4.0 bewusst
out-of-scope und wird nicht heuristisch erkannt.

#### 3.5.2 `ValueDeserializer`

Inverse zur `ValueSerializer`-Mapping-Tabelle aus Plan-0.3.0 §6.4.1. Für jeden
Eingabe-Typ aus den drei Formaten bestimmt sie den entsprechenden Java-Wert,
den der Importer in Target-Reihenfolge an den Writer weitergeben kann.
Wichtig: die Tabelle unten ist nur der Ausschnitt mit den kniffligen Fällen;
die Implementierung MUSS die in 0.3.0/`neutral-model-spec.md` unterstützten
neutralen Typen vollständig abdecken.

**Verbindliche Architekturentscheidung für 0.4.0**: der
`ValueDeserializer` bekommt seinen JDBC-Typ-Hint **nicht** über ein neues
Feld auf `core.ColumnDescriptor`, sondern über eine Lookup-Closure
`(columnName: String) -> JdbcTypeHint?`, die der Phase-D-
`StreamingImporter` einmal pro Tabelle aus den Writer-seitigen
`TargetColumn`-Metadaten baut. `JdbcTypeHint` lebt damit lokal im
`formats`-Modul, `core` bleibt JDBC-frei, und die einzige Modul-Grenze,
die beide Schichten kennt, bleibt bewusst `streaming`. Das ist die
kanonische Lösung; ein JDBC-Slot in `core` gehört ausdrücklich NICHT zum
0.4.0-Zielbild.

| Format-Eingabe | Spalten-Hint (JDBC-Typ aus `ResultSetMetaData`) | Resultat |
|---|---|---|
| JSON String | `VARCHAR`/`TEXT`/`CLOB` | String |
| JSON Boolean | `BOOLEAN` | Boolean |
| JSON String `true`/`false` (case-insensitive) | `BOOLEAN` | Boolean |
| JSON String | `DATE` | `LocalDate.parse` |
| JSON String ohne Offset/Zone | `TIMESTAMP` | `LocalDateTime.parse` |
| JSON String | `TIMESTAMP WITH TIME ZONE` | `OffsetDateTime.parse` |
| JSON String | `UUID` | `UUID.fromString` |
| JSON String | `INTERVAL` | String (PostgreSQL-Writer bindet dialektspezifisch; siehe §3.2) |
| JSON Number | `REAL`/`FLOAT`/`DOUBLE` | Double |
| JSON Number ohne `.`/`e`/`E` im Token | `INTEGER`/`BIGINT` | Long |
| JSON Number | `NUMERIC`/`DECIMAL` | Entscheidungsbaum: `scale > 0` oder Token mit `.`/`e`/`E` oder `precision > 18` → `BigDecimal`, sonst ganzzahliger Parse mit Präzisionscheck |
| JSON `null` | beliebig | SQL NULL |
| JSON `null` | `JSON`/`JSONB` | ebenfalls SQL NULL, NICHT JSON-Literal `null` |
| JSON Array | `ARRAY` | `List<Any?>` (JDBC-`createArrayOf()` erst im Writer) |
| YAML Block-Sequence | `ARRAY` | wie JSON Array |
| JSON/YAML Binärwert oder Base64-String | `BLOB`/`BYTEA`/binary | `ByteArray` |
| JSON/YAML Objekt oder JSON-String | `JSON`/`JSONB` | JSON-String |
| JSON/YAML String | `BIT(1)` | Boolean |
| JSON/YAML String | `BIT(N>1)` | `BitSet` |
| CSV `csvNullString` | beliebig | SQL NULL |
| CSV String | `NUMERIC`/`DECIMAL` | `BigDecimal` |
| CSV String | `REAL`/`FLOAT`/`DOUBLE` | `toDouble()` mit festem Punkt-Format, nie Locale-abhängig |
| CSV alles andere | bestimmt aus Spalten-Hint via Try-Parse-Kette | typisierter Wert oder Fehler |

CSV ist der unangenehmste Fall, weil alle Werte als String reinkommen — die
Typ-Inferenz hängt **vollständig** vom JDBC-Spalten-Hint ab. Wir holen den
Hint via `SELECT * FROM target LIMIT 0` einmal vor dem Import (siehe §6.4).
Das `csvNullString` kommt aus `ImportOptions.csvNullString` und wird in
Phase A/CLI explizit über einen Import-Flag gespiegelt. Der Default bleibt
aus Symmetriegründen beim 0.3.0-CSV-Writer auf leerem String (`""`); damit
sind SQL-NULL und leerer String im CSV-Defaultpfad bewusst NICHT verlustfrei
unterscheidbar. Wer diese Unterscheidung braucht, muss beim Export und
Import explizit einen Sentinel wie `--csv-null-string NULL` setzen.

Für BOOLEAN gilt bewusst: nur `true`/`false` (case-insensitive) sind
zulässig. Werte wie `1`, `0`, `yes`, `no` werden nicht still interpretiert,
sondern laufen in den normalen Fehlerpfad.

Für `TIMESTAMP` ohne Zeitzone gilt ebenso: ein Eingabewert mit explizitem
Offset/Zone (`2026-04-07T10:00:00+02:00`) wird nicht still „abgeschnitten",
sondern als Typfehler behandelt und läuft über `--on-error`.

Primärer Typ-Anker ist dabei der JDBC-Typcode (`ResultSetMetaData` /
`java.sql.Types`), nicht der rohe `sqlTypeName`-String. Dialektspezifische
`sqlTypeName`-Werte (`int4` vs. `INT` vs. `INTEGER`) werden nur als
sekundärer Hint normalisiert, damit PG/MySQL/SQLite nicht an reinen
Treiber-Strings auseinanderlaufen.

Array-/Treiberobjekte werden bewusst NICHT in `adapters:driven:formats` erzeugt.
`formats` bleibt JDBC-frei; konkrete `java.sql.Array`- oder PG-`PGobject`-
Materialisierung passiert erst im Writer-Layer.

ImportOptions enthält für 0.4.0 mindestens die reader-/writerrelevanten
Schalter aus diesem Plan (`triggerMode`, `csvNoHeader`, `csvNullString`,
`encoding`, `reseedSequences`, `disableFkChecks`, `truncate`,
`onConflict`, `onError`).
`chunkSize` gehört bewusst NICHT hier hinein, sondern bleibt in
`PipelineConfig(chunkSize = ...)` wie im 0.3.0-Exportpfad.

### 3.6 `adapters:driven:streaming`

#### 3.6.1 `StreamingImporter`

```kotlin
class StreamingImporter(
    private val readerFactory: DataChunkReaderFactory,
    private val writerRegistry: DataWriterRegistry,
) {
    fun import(
        pool: ConnectionPool,
        input: ImportInput,
        format: DataExportFormat,
        options: ImportOptions = ImportOptions(),
        config: PipelineConfig = PipelineConfig(),   // unverändert aus 0.3.0 wiederverwendet
    ): ImportResult
}
```

Das Spiegelbild von `StreamingExporter`. Die wichtigen Vertragspunkte:

- Reader-Vertrag für den Start einer Tabelle:
  1. Reader wird erzeugt
  2. `session = writer.openTable(...)`
  3. Importer ruft `firstChunk = reader.nextChunk(...)`
  4. Danach ist `reader.headerColumns()` deterministisch verfügbar
  5. Importer baut aus `headerColumns()` und `session.targetColumns` das
     Reorder-Mapping und die gebundene Spalten-Teilmenge für diese Tabelle
  6. Header-Mismatch wird HIER als `ImportSchemaMismatchException`
     geworfen, bevor der erste `session.write(...)` läuft
  7. Erst danach schreibt der Importer `firstChunk` (falls nicht `null`)
- **M6 — Pre-Flight ist `--on-error abort`, immer**: die Schritte 1–6
  laufen vor dem ersten `session.write(...)` und sind damit Teil der
  Pre-Flight-Phase. Ein Fehlschlag in einem dieser Schritte (Reader-
  Konstruktion, erstes `nextChunk(...)`, Header-Mapping, Pre-Flight-
  Mismatch) wird **immer** als Abbruch behandelt — `--on-error skip|log`
  hat in der Pre-Flight-Phase keine Wirkung. Konsequenz: wenn der Reader
  beim Header-Sniffing schon Rows aus `firstChunk` materialisiert hat und
  Schritt 6 scheitert, sind diese Rows verloren und es gibt KEINE
  Recovery — das ist Vertrag, kein Bug. `--on-error` greift erst ab dem
  ersten erfolgreichen `session.write(...)` aus Schritt 7.
- Reader dürfen dafür intern die erste Row bzw. den ersten Chunk puffern;
  insbesondere JSON/YAML dürfen die Header aus dem ersten Objekt ableiten
  und denselben Datensatz anschließend regulär als Teil von `firstChunk`
  zurückgeben
- Pro Tabelle: `reader.nextChunk()` → Importer normalisiert/castet die Row-
  Reihenfolge auf die gebundene Spalten-Teilmenge und setzt `chunk.columns`
  auf genau diese Liste → `session.write(chunk)` → bei Erfolg
  `session.commitChunk()`, bei Fehler `session.rollbackChunk()` plus
  `--on-error`-Auswertung
- Wenn der Reader während `nextChunk()` mitten in einem Chunk mit
  `ImportSchemaMismatchException` oder einem Formatfehler scheitert, gilt
  der gerade gelesene Chunk als verloren; der Importer behandelt das wie
  einen Chunk-Fehler und wendet darauf `--on-error abort|skip|log` an.
  Eine Row-Recovery innerhalb des Chunks gibt es bewusst nicht.
- `commitChunk()` und `rollbackChunk()` kapseln dabei die eigentlichen
  JDBC-`commit()`/`rollback()`-Operationen; `commitChunk()` ist also
  semantisch nur `conn.commit()` ohne zusätzliche Session-State-Maschine.
  Der Importer arbeitet nur gegen den Session-Vertrag, nicht direkt gegen
  `Connection`
- Pro Tabelle wird genau eine `TableImportSession` über alle Chunks hinweg
  benutzt — bei erfolgreichem Tabellenabschluss ruft der Importer
  `session.finishTable()` auf; `session.close()` bleibt reiner Cleanup-Pfad
- Trigger-Disable läuft writer-intern in `openTable(...)`, weil dort
  Connection, `ImportOptions.triggerMode` und Tabellenkontext bereits
  gemeinsam vorliegen. `StreamingImporter` ruft NICHT separat
  `SchemaSync.disableTriggers(...)` auf. Gleiches gilt für
  `SchemaSync.assertNoUserTriggers(...)` im `strict`-Modus.
- Multi-Tabellen-Imports laufen sequentiell; Parallelisierung ist 1.0.0
- Es gibt auch bei Directory-/Multi-Table-Imports KEINE Gesamt-Atomarität:
  wenn Tabelle 3 scheitert, bleiben erfolgreich abgeschlossene Tabellen 1
  und 2 persistiert; Tabelle 3 folgt weiter dem chunkweisen Teil-Erfolg
  aus §6.5
- `ImportInput` ist eine sealed class analog zu `ExportOutput`. Das CLI muss
  vor der Konstruktion entscheiden, in welche Zieltabelle die Daten gehören
  — `--table` (singular, Pflicht) für Stdin und SingleFile, `--tables`
  (plural, optional) als Subset-Filter für Directory:

```kotlin
sealed class ImportInput {
    /** Eine Tabelle, Daten von Stdin. CLI-Pflicht: `--table <name>`. */
    data class Stdin(val table: String) : ImportInput()

    /**
     * Eine Tabelle, Daten aus einer Datei. CLI-Pflicht: `--table <name>`.
     * Format wird aus der Dateiendung oder `--format` abgeleitet (siehe §6.3).
     * `table` darf schema-qualifiziert sein (`schema.table`) und folgt
     * demselben Identifier-Pattern wie 0.3.0-`--tables`.
     * Kein automatisches Mapping vom Dateinamen auf den Tabellennamen — das
     * wäre eine Convenience-Erweiterung in einem späteren Milestone.
     */
    data class SingleFile(val table: String, val path: Path) : ImportInput()

    /**
     * Mehrere Tabellen aus einem Verzeichnis. Die Tabellen-zu-Datei-Zuordnung
     * folgt dem Schema `<table>.<format>` (siehe `ExportOutput.fileNameFor`
     * aus 0.3.0 Phase D). `tableFilter` ist optional und beschränkt den
     * Import auf eine Untermenge der gefundenen Dateien. `tableOrder`
     * erlaubt dem CLI-Layer, bei `--schema` eine bereits topologisch
     * sortierte Tabellenreihenfolge vorzugeben; ohne diesen Wert bleibt die
     * Ausführung deterministisch lexikographisch nach Tabellenname/
     * Dateiname sortiert. Für FK-abhängige Multi-Table-Imports ist ohne
     * `--schema` dennoch nur Best-Effort möglich (siehe §6.8.3).
     */
    data class Directory(
        val path: Path,
        val tableFilter: List<String>? = null,
        val tableOrder: List<String>? = null,
    ) : ImportInput()
}
```

Diese Trennung löst die Mehrdeutigkeit, die in der ersten Plan-Fassung
`--tables` als Multipurpose-Option für Single- und Multi-Quellen behandelt
hat. Für `Stdin` und `SingleFile` gibt es **immer genau eine** Zieltabelle
und sie muss vom Aufrufer benannt werden.

**L7 — Layer-Owner für Source-Auflösung**: das Mapping `--source` → konkrete
`ImportInput`-Variante passiert ausschließlich im **CLI-Layer**
(`DataImportCommand.run()` bzw. ein Helper in `DataImportHelpers`),
NICHT im `StreamingImporter`. Konkret:

1. `source == "-"` (oder das in 0.3.0 etablierte Stdin-Sentinel) →
   `ImportInput.Stdin(table)`
2. sonst `Path.of(source)` auflösen und `Files.isDirectory(...)` prüfen:
   - Verzeichnis → `ImportInput.Directory(path, tableFilter, tableOrder)`
   - reguläre Datei → `ImportInput.SingleFile(table, path)`
   - nichts davon → Exit 2 mit `--source <path> not found`

`StreamingImporter` bekommt also nie einen rohen `--source`-String. Das
verhindert, dass Phase D versehentlich einen Filesystem-Test gegen Stdin
schreibt oder umgekehrt — Stdin ist im Streaming-Layer eine simple
`InputStream`-Quelle ohne Filesystem-Semantik.

Schema-Qualifikation ist dabei explizit erlaubt: `table` folgt dem gleichen
Identifier-Pattern wie 0.3.0-`--tables`, also `name` oder `schema.table`,
ohne SQL-Quoting aus der CLI heraus.

#### 3.6.2 `ImportResult` und `TableImportSummary`

```kotlin
data class ChunkFailure(
    val table: String,
    val chunkIndex: Int,
    val rowsLost: Long,
    val reason: String,
)
```

Spiegelbild zu `ExportResult`. Zusätzlich pro Tabelle:

- `rowsInserted`, `rowsUpdated`, `rowsSkipped`, `rowsUnknown` (R10 —
  MySQL `SUCCESS_NO_INFO`-Fallback, siehe `WriteResult`-Kdoc in §3.1.1
  und §6.12.2), `rowsFailed`
- `chunkFailures: List<ChunkFailure>` für `--on-error log` auf Chunk-Granularität
- `sequenceAdjustments: List<SequenceAdjustment>` (für den Report)
- `targetColumns: List<ColumnDescriptor>` (für Debug/JSON-Output)
- `triggerMode: TriggerMode` (welcher Modus tatsächlich angewendet wurde)
- `failedFinish: FailedFinishInfo?` — H4: nur gesetzt, wenn
  `finishTable()` ein `FinishTableResult.PartialFailure` geliefert hat.
  Trägt die bereits durchgeführten Adjustments **und** die ursprüngliche
  Reenable-Exception strukturiert mit (als `causeMessage`/`causeClass`/
  `causeStack`-Tripel, siehe M-R7), damit §6.15 sie sauber rendern und
  Phase F sie gezielt asserten kann. F3: zusätzlich
  `closeCauseMessage`/`closeCauseClass`/`closeCauseStack`, falls der
  zweite Reenable-Versuch im `close()`-Cleanup ebenfalls scheitert.
- `error: String?`

```kotlin
/**
 * H4 / F3: strukturierter Träger für „Daten committed, aber Trigger-
 * Reenable gescheitert"-Fälle. Der StreamingImporter setzt das Feld
 * direkt aus dem `FinishTableResult.PartialFailure` aus §3.1.1; die
 * optionalen `closeCause*`-Felder werden nach dem `close()`-Aufruf
 * befüllt, wenn der zweite, idempotente Reenable-Versuch im
 * Cleanup-Pfad ebenfalls geworfen hat (siehe `close()`-Kdoc in §3.1.1,
 * F3).
 *
 * **M-R7 — kein Info-Verlust zwischen `PartialFailure(Throwable)` und
 * diesem Record**. Der `FinishTableResult.PartialFailure`-Fall aus
 * §3.1.1 trägt den originalen `Throwable`, und der ganze Sinn der
 * F3-Strukturierung war es, Phase-F-Assertions ohne stderr-Regex
 * möglich zu machen UND Debug-Info (Typ, Stack) für den manuellen
 * Folge-Eingriff zu erhalten. Der Importer serialisiert deshalb beim
 * Übertragen in [FailedFinishInfo] den `Throwable` in **drei**
 * getrennte Felder, nicht in eine einzelne Zeichenkette:
 *
 * - [causeMessage]: `throwable.message ?: ""` — die Klartext-
 *   Fehlermeldung für den Standard-Report in §6.15.
 * - [causeClass]: `throwable::class.qualifiedName` — der voll
 *   qualifizierte Exception-Typ, auf dem Phase-F gezielt per
 *   `assertThat(info.causeClass).isEqualTo("org.postgresql.util.
 *   PSQLException")` asserten kann, ohne den Nachrichtenstring parsen
 *   zu müssen.
 * - [causeStack]: ein gerendeter Stack-Trace (`Throwable.stackTraceToString()`
 *   bzw. äquivalent), der im JSON-Output und im Debug-Report
 *   verfügbar ist, im Standard-Klartext-Report aber unterdrückt bleibt
 *   (sonst wird §6.15 unlesbar). `null`, wenn `throwable.stackTrace`
 *   leer ist (z.B. bei einer fabrizierten Exception aus einem
 *   Test-Mock).
 *
 * Analog für [closeCauseMessage]/[closeCauseClass]/[closeCauseStack],
 * die der Importer direkt nach dem `close()`-Aufruf befüllt, wenn der
 * zweite Reenable-Versuch im Cleanup-Pfad ebenfalls geworfen hat.
 * Wenn der zweite Versuch erfolgreich war (oder gar nicht stattfand),
 * bleiben die drei Close-Felder `null`.
 *
 * Der Klartext-Report in §6.15 rendert bevorzugt `causeClass:
 * causeMessage`; der JSON-Output (`--output-format json`) serialisiert
 * alle sechs Felder strukturiert, sodass Phase F den Pfad per
 * deterministischem Key-Access testen kann.
 */
data class FailedFinishInfo(
    val adjustments: List<SequenceAdjustment>,
    val causeMessage: String,
    val causeClass: String,
    val causeStack: String? = null,
    val closeCauseMessage: String? = null,
    val closeCauseClass: String? = null,
    val closeCauseStack: String? = null,
)
```

**M3 / K2 — `rowsSkipped` vs. `rowsFailed`** (siehe auch `WriteResult`-
Kdoc in §3.1.1):

- `rowsSkipped` aggregiert ausschließlich die `--on-conflict skip`-Treffer
  (`ON CONFLICT DO NOTHING` / `INSERT IGNORE`). Es ist eine row-genaue
  Zahl, die direkt aus den per-Row-Affected-Counts der UPSERT/Skip-Pfade
  kommt.
- `rowsFailed` aggregiert die per `--on-error skip|log` verlorenen Rows.
  In 0.4.0 ist diese Politik bewusst chunk-granular: ein scheiternder
  Chunk verliert ALLE Rows dieses Chunks. Damit ist `rowsFailed` auf
  Summary-Ebene effektiv ein Vielfaches von `chunkSize` (bzw. die exakte
  Größe des verlorenen Schlusschunks). `rowsFailed` ist NICHT Teil von
  `WriteResult` und wird ausschließlich vom `StreamingImporter` aus den
  fehlgeschlagenen Chunks aggregiert.
- Diese Trennung muss auch in den `--quiet`/JSON-Output-Pfaden in §6.15
  sichtbar bleiben (`skipped`, `unknown` und `failed` als getrennte
  Counter; `unknown` ist R10-spezifisch und nur im MySQL-`SUCCESS_NO_INFO`-
  Pfad ungleich `0`).

### 3.7 `adapters:driving:cli`

#### 3.7.1 Neues `DataImportCommand`

```kotlin
class DataImportCommand : CliktCommand(name = "import") {
    // F47: --target ist NICHT hart .required() — der NamedConnectionResolver
    // löst es zusammen mit `database.default_target` aus .d-migrate.yaml auf.
    // Pflicht ist es nur, wenn weder --target noch ein default_target greift
    // (siehe §3.7.3 Auflösungstabelle).
    val target by option("--target")
    val source by option("--source").required()                        // Pfad oder "-" für Stdin
    val format by option("--format").choice("json", "yaml", "csv")     // Default: aus Endung
    // F40/F47: Tabellen-Auflösung ist explizit mehrere Pfade. `--table` und
    // `--tables` akzeptieren denselben Identifier-Vertrag wie 0.3.0:
    // `name` oder `schema.table`, ohne SQL-Quotes.
    val table by option("--table")                                     // Pflicht für Stdin/SingleFile
    val tables by option("--tables").split(",")                        // Subset-Filter für Directory
    val schema by option("--schema").path()                            // Optional Pre-Validation
    val onError by option("--on-error").choice("abort", "skip", "log").default("abort")
    val onConflict by option("--on-conflict").choice("abort", "skip", "update") // null = impliziter Default "abort"
    val triggerMode by option("--trigger-mode").choice("fire", "disable", "strict").default("fire")
    val truncate by option("--truncate").flag()                        // §6.14 — nicht atomar
    val disableFkChecks by option("--disable-fk-checks").flag()        // §6.8 — PG nicht supported
    // H2: Negativ-Name MUSS in flag(...) selbst stehen, sonst aktiviert
    // `--no-reseed-sequences` den Wert nicht — eine zweite Positional in
    // option(...) wäre nur ein Alias auf denselben „on"-Wert.
    val reseedSequences by option("--reseed-sequences").flag("--no-reseed-sequences", default = true)
    val encoding by option("--encoding").default("auto")               // Default: BOM-Detect für UTF-*
    val csvNoHeader by option("--csv-no-header").flag()
    val csvNullString by option("--csv-null-string").default("")       // symmetrisch zu 0.3.0; für lossless NULL/"": expliziten Sentinel setzen
    val chunkSize by option("--chunk-size").int().default(10_000)
}
```

`chunkSize` wird im Command bewusst über
`PipelineConfig(chunkSize = chunkSize)` an `StreamingImporter.import(...)`
durchgereicht, nicht über `ImportOptions`.
`--yes` wird wie in 0.3.0 vom gemeinsamen Root-/Confirmations-Pfad
bereitgestellt und erscheint deshalb nicht noch einmal lokal im
`DataImportCommand`-Snippet.
`--truncate` plus explizites `--on-conflict abort` bleibt in 0.4.0
bewusst Exit 2: der Effektivpfad wäre zwar derselbe wie beim impliziten
Default, aber der explizite Konflikt-Flag auf einem zuvor geleerten Ziel
gilt hier als User-Intent-Signal, dass die gewählte Kombination fachlich
missverstanden wurde. Dieser Hinweis gehört in den CLI-Help-Text.

**`--table` vs `--tables` Auflösungsregeln** (F40):

| `--source` | `--table` | `--tables` | Resultat |
|---|---|---|---|
| `-` (Stdin) | gesetzt | leer | `ImportInput.Stdin(table)` |
| `-` (Stdin) | leer | beliebig | **Exit 2** — Stdin braucht `--table` |
| `-` (Stdin) | gesetzt | gesetzt | **Exit 2** — `--tables` ist nur für Directory |
| Datei | gesetzt | leer | `ImportInput.SingleFile(table, path)` |
| Datei | leer | leer | **Exit 2** — SingleFile braucht `--table` |
| Datei | gesetzt | gesetzt | **Exit 2** — `--tables` ist nur für Directory |
| Verzeichnis | leer | leer | `ImportInput.Directory(path, null)` (alle Dateien) |
| Verzeichnis | leer | gesetzt | `ImportInput.Directory(path, tables)` (Subset-Filter) |
| Verzeichnis | gesetzt | beliebig | **Exit 2** — `--table` ist nur für Single/Stdin; Hinweis auf `--tables <name>` |

> **Bewusste Entscheidung**: keine automatische Ableitung der Zieltabelle
> aus dem Dateinamen in 0.4.0. Sowohl `users.json` als auch
> `dump-2026-04.json` sehen aus Dateisystem-Perspektive gleich legitim aus,
> und ein impliziter Mapping-Algorithmus würde Edge-Cases produzieren
> (Hyphens, Umlaute, fehlende Endung). User schreibt explizit
> `--table users` — kostet einen Token, gibt dafür einen klaren Fehler statt
> einer stillen Fehlinterpretation. Eine `--auto-table-from-filename`-
> Convenience kann später additiv kommen.

`--table` und `--tables` werden wie 0.3.0-`--tables` strikt validiert:
erlaubt sind `name` oder `schema.table`, jeweils ohne SQL-Quotes.

**Bewusst nicht enthalten**:

- `--incremental` / `--since-column` — auf der Import-Seite gibt es keinen
  inkrementellen Modus (F44). „Inkrementell importieren" ist fachlich
  identisch zu „idempotent UPSERT-en" und wird ausschließlich über
  `--on-conflict update` abgebildet (siehe §6.12).
- `--resume` — Checkpoint-Resume kommt mit LN-012 in 0.9.0.

**Pre-Flight-Reihenfolge in `DataImportCommand.run()`** — autoritative
Tabelle, alle Pre-Flight-Pfade landen genau hier:

| Phase | Prüft |
|---|---|
| CLI-Parse/Helper | `--source`-Form (stdin/file/dir), Identifier-Validierung für `--table`/`--tables`/`--since-column`, explizites `--on-conflict`, `--truncate`-Exklusivität, `--encoding`-Charset-Name |
| Lokale `--schema`-Validierung (M4 Phase 1) | wenn `--schema <path>` gesetzt ist: Datei lesen, gegen das neutrale Schema-Modell parsen, intra-Schema-Konsistenz prüfen (Spalten, Nullability, Constraints). Schlägt fehl → Exit 3 ohne dass jemals eine Connection aufgemacht wurde. Diese Phase macht NICHT die Konsistenz „Schema deckt Target wirklich ab" — die kommt erst nach `openTable(...)`. |
| Topo-Sort (nur Directory + `--schema`) | **R8**: läuft direkt im Anschluss an die M4-Phase-1-Validierung, BEVOR `resolveTarget(...)` aufgerufen wird. Wenn `ImportInput.Directory` UND `--schema` gesetzt: Tabellenreihenfolge per **`AbstractDdlGenerator.topologicalSort(...)`-Helper** aus dem bereits geladenen Schema-DAG ableiten und in `ImportInput.Directory.tableOrder` ablegen (per `copy(tableOrder = ...)`). Der Sort braucht keine Connection und keinen Dialekt — er nutzt nur die in M4 Phase 1 ohnehin geladene Schema-Definition. Für Single-File/Stdin **kein** Topo-Sort (siehe M5 / §6.8.3). **H-R2 — Zyklus-Check ist Pflicht**: der heutige `AbstractDdlGenerator.topologicalSort(...)` wirft nicht bei Zyklen, sondern liefert `TopologicalSortResult(sorted, circularEdges)` und hängt zyklische Tabellen hinten an `sorted` an (0.2.0-Best-Effort für DDL-Generierung). Für den Import-Pfad ist diese Semantik **nicht akzeptabel**: ein FK-lastiger Zyklus würde zu einem nicht-topologischen „Sort" führen und den ganzen Multi-Table-Import in ein Reihenfolge-Raten kippen. Der Runner-Pfad MUSS daher nach dem Aufruf explizit `result.circularEdges.isNotEmpty() → Exit 3` prüfen, mit einer Fehlermeldung, die die zyklischen Kanten listet. Schlägt der Sort selbst fehl (Input-Schema inkonsistent o.ä.) → ebenfalls Exit 3, weiterhin ohne Connection. |
| Nach `resolveTarget(...)` | Dialekt-Auflösung, `--disable-fk-checks` auf PG, Connection-/Config-Fehler |
| Während `openTable(...)` und direkt danach (writer-intern + CLI-Wrapper) | writer-interne Trigger-Strict-/Disable-Prüfung; CLI-Wrapper mappt `UnsupportedTriggerModeException` daraus auf Exit 2, danach Header-Validierung/Reorder-Mapping, PK-/UPSERT-Prüfung, ggf. FK-Deferral-Pre-Flight |
| Schema-vs-Target-Konsistenz (M4 Phase 2, nur wenn `--schema` gesetzt) | Direkt nach `openTable(...)` und dem Header-Mapping: das aus `session.targetColumns` aufgebaute Bild gegen die `--schema`-Definition prüfen (Spaltennamen, Nullability, Typkompatibilität). Mismatch → Exit 3, bevor der erste `write(...)` läuft. |

`--truncate` ist nur mit einem explizit gesetzten `--on-conflict`
gegenseitig ausschließend. `onConflict == null` bedeutet „kein Flag
gesetzt"; der Effektivwert `"abort"` wird erst nach dieser Prüfung
aufgelöst.

Registrierung: `DataCommand` aus 0.3.0 bekommt zusätzlich
`subcommands(DataExportCommand(), DataImportCommand())`.

#### 3.7.2 Erweiterung von `DataExportCommand` für LF-013

```kotlin
val sinceColumn by option("--since-column")
val since by option("--since")     // ISO-Datum/Datetime/Number — abhängig vom Spalten-Typ
```

LF-013 braucht in 0.4.0 keinen separaten `--incremental`-Schalter. Der
Filter wird direkt über `--since-column` plus `--since` aktiviert und intern
als `DataFilter.ParameterizedClause("\"<col>\" >= ?", listOf(typedSince))` gebaut —
mit **Parameter-Binding** statt String-Konkatenation, weil hier ein vom
Nutzer kommender Wert in ein SQL-Statement landet (siehe §6.7 im 0.3.0-Plan
hat `--filter` die Trust-Boundary „lokale Shell" akzeptiert; für `--since`
ziehen wir die Grenze enger und parametrisieren). Der `--since`-Wert wird
vor dem Binding anhand des JDBC-Typs der Marker-Spalte einmal in ein
passendes Java-Objekt konvertiert (`LocalDate`, `LocalDateTime`,
`OffsetDateTime`, `Long`, `BigDecimal`, fallback `String`).

> **Konsequenz**: `DataFilter.WhereClause` muss um eine optionale
> `params: List<Any?>`-Variante erweitert werden, oder es gibt ein neues
> `DataFilter.ParameterizedClause(sql, params)`. Wir entscheiden uns in
> Phase A für `ParameterizedClause`, weil das den existierenden
> `WhereClause`-Vertrag nicht bricht.

**Pre-Flight-Reihenfolge in `DataExportCommand.run()` für LF-013**:

| Phase | Prüft |
|---|---|
| CLI-Parse/Helper | `--since-column`/`--since` sind nur gemeinsam gültig; Identifier-Validierung für `--since-column` |
| Nach `resolveSource(...)` | Connection-/Config-Fehler, Dialekt-Auflösung |
| Vor Start des Streamings | Marker-Spalten-Metadaten via JDBC lesen, `--since` in den Zieltyp konvertieren, `ParameterizedClause` aufbauen |

#### 3.7.3 `NamedConnectionResolver` aktiviert `database.default_source` und `database.default_target`

In 0.3.0 wurde `default_source` gelesen aber für `data export` ignoriert.
Für 0.4.0 wird die Symmetrie sauber gezogen:

- `resolveSource(source: String?)` aktiviert `database.default_source` für
  `data export`
- `resolveTarget(target: String?)` aktiviert `database.default_target` für
  `data import`

Die Datei-Quelle von `data import` bleibt natürlich Pflichtparameter; dort
meint „source" den Input-Pfad, nicht die DB-Verbindung.

**Auflösungstabelle für `data import` (autoritativ — F47)**: Das
CLI-Snippet in §3.7.1 macht `--target` bewusst **nicht** mit `.required()`
pflichtig. Stattdessen entscheidet diese Tabelle, ob ein effektives Ziel
bestimmbar ist:

| `--target` | `default_target` in `.d-migrate.yaml` | Verhalten |
|---|---|---|
| gesetzt | irrelevant | wird benutzt |
| nicht gesetzt | gesetzt, ist URL | URL wird benutzt |
| nicht gesetzt | gesetzt, ist Connection-Name | über `connections`-Map auflösen |
| nicht gesetzt | gesetzt, ist Connection-Name, aber Name fehlt | **Exit 7** — wie 0.3.0: Name nicht in `database.connections` definiert |
| nicht gesetzt | nicht gesetzt | **Exit 2** mit Meldung „`--target` ist Pflicht, wenn `database.default_target` nicht gesetzt ist" |

`DataImportCommand.run()` ruft den `NamedConnectionResolver` mit dem
optionalen `target`-Wert; der Resolver wird in 0.4.0 um eine
`resolveTarget(target: String?)`-Methode erweitert, die die obige Tabelle
implementiert. Damit ist der „Pflicht"-Status von `--target` an genau einer
Stelle festgelegt — der Auflösungstabelle — und nicht doppelt im
Clikt-Snippet plus im Fließtext. `resolveTarget(...)` wirft dafür wie der
bestehende Resolver eine dedizierte Config-Resolution-Exception; der
CLI-Wrapper mappt sie auf Exit 7 (Config-Fehler) bzw. Exit 2, wenn nur die
Pflichtigkeit von `--target` verletzt ist.

Analog ruft `DataExportCommand.run()` in 0.4.0 einen
`resolveSource(source: String?)`-Pfad auf, der `database.default_source`
aktiviert. Die zugehörige `connection-config-spec.md`-Tabelle wird in §7
explizit für BEIDE Defaults nachgezogen, um den 0.3.0-Doku-Drift zu
beenden.

Der bestehende 0.3.0-Pfad `resolve(source: String)` bleibt in 0.4.0 als
kompatibler Wrapper erhalten und delegiert intern auf `resolveSource(source)`.
Damit bleiben die bestehenden Export-Tests stabil; neue Tests decken
zusätzlich `resolveSource(null)` und `resolveTarget(null)` mit Defaults ab.

**L6 — Behavior-Change-Analyse für `database.default_source`**: Der
0.3.0-`NamedConnectionResolver` liest `database.default_source` heute
**gar nicht** aus der `.d-migrate.yaml` aus — der Doc-Comment in
`NamedConnectionResolver.kt` erwähnt es zwar als „in 0.4.0+ relevant",
aber der Code-Pfad existiert nicht. Konsequenzen für die 0.4.0-Migration:

- 0.3.0-Configs, die `database.default_source` historisch **versehentlich**
  gesetzt hatten, hatten zur Laufzeit null Effekt — `data export`
  verlangte immer ein explizites `--source`. Damit kann es **keine**
  stille Verhaltensänderung gegen bestehende User-Workflows geben: jeder
  funktionierende 0.3.0-Aufruf bleibt 0.4.0-funktionsfähig, weil das
  `--source`-Argument weiterhin gewinnt.
- Die einzige neue Wirkung ist, dass `data export` ohne `--source`, das in
  0.3.0 mit Exit 2 abgebrochen ist, in 0.4.0 plötzlich mit `default_source`
  arbeitet, falls dieser im Config gesetzt war. Dieser Pfad ist also
  reines „vorher ignoriert → jetzt aktiv", kein „vorher A → jetzt B".
- CHANGELOG-Eintrag im `[Unreleased]`-Block reicht damit aus; ein
  separater Migrations-Hinweis im Help-Text oder in der README ist nicht
  nötig. Trotzdem muss der CHANGELOG-Bullet explizit sagen
  „`database.default_source` was previously parsed but ignored — in
  0.4.0 it is honored as a fallback for `--source`", damit User mit
  alten Configs nicht überrascht werden, wenn ein vergessener
  `default_source: prod` plötzlich greift.
- **L10 — KDoc-Drift explizit mit-fixen**: Der heutige Doc-Comment in
  `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/
  NamedConnectionResolver.kt:22` behauptet „liest `database.default_source`,
  ignoriert es aber für `data export`" — tatsächlich liest der Code die
  Option aktuell gar nicht (`lookupConnectionUrl` schaut ausschließlich
  in `database.connections`). Der Doc-Kommentar ist also bereits heute
  schief. Phase E Schritt 26 MUSS denselben PR, der
  `resolveSource(...)`/`resolveTarget(...)` implementiert, auch den
  KDoc-Block an den Klassen-Header aktualisieren (neue Rolle von
  `default_source`/`default_target`, Verweis auf die Auflösungstabelle
  in §3.7.3), sonst driftet die Doku weiter — die Lektion aus der
  L6-Analyse darf sich nicht wiederholen.

### 3.8 `hexagon:core`

`hexagon:core` bleibt weitgehend unverändert, bekommt für 0.4.0 aber zwei
kleine, schichtneutrale Ergänzungen: `ImportSchemaMismatchException` und den
neuen Filter-Typ `DataFilter.ParameterizedClause`.

`ColumnDescriptor` bleibt dabei **bitwise wie in 0.3.0**: kein
`jdbcType`-Feld, keine Import-spezifische Typinformation. Der
JDBC-Typ-Hint für den Import lebt stattdessen als
`formats.JdbcTypeHint(jdbcType, sqlTypeName, precision, scale)` im
`formats`-Modul und wird
dem `ValueDeserializer` über eine Lookup-Closure durchgereicht, die
`streaming` aus `hexagon:ports.TargetColumn` baut. Damit bleibt `core`
schichtneutral; JDBC-nahe Metadaten bleiben auf der Writer-Seite.

```kotlin
class ImportSchemaMismatchException(message: String) : RuntimeException(message)

sealed class DataFilter {
    data class WhereClause(val sql: String) : DataFilter()
    data class ColumnSubset(val columns: List<String>) : DataFilter()
    data class Compound(val parts: List<DataFilter>) : DataFilter()
    /** Neu in 0.4.0: parametrisierte WHERE-Klausel für --since (LF-013). */
    data class ParameterizedClause(val sql: String, val params: List<Any?>) : DataFilter()
}
```

`AbstractJdbcDataReader.buildSelectSql` und der Statement-Setup-Pfad werden
so erweitert, dass `ParameterizedClause`-Parameter über
`setObject(idx, value)` gebunden werden, statt in den SQL-String
konkateniert. Das ist auch der einzige Pfad, der `setObject(...)` für
Daten-Reader braucht — der Volltext-Export benutzt `setObject` nicht.

---

## 4. Implementierungsreihenfolge

### Phase A: Reader-Vertrag und Daten-Modell-Erweiterungen

1. `DataChunkReader`-Interface + `DataChunkReaderFactory`
2. `ImportOptions` (parallel zu `ExportOptions`)
3. `EncodingDetector` mit BOM-Sniff nur für UTF-8/UTF-16 BE/LE; alle anderen
   Encodings (ISO-8859-1, Windows-1252, …) ausschließlich über
   `--encoding`-Fallback (siehe §6.9)
4. `ValueDeserializer` mit der Mapping-Tabelle aus §3.5.2 plus Tests.
   **Architektur-Festlegung**: Typ-Hints kommen über
   `JdbcTypeHint` + Lookup-Closure `(columnName) -> JdbcTypeHint?`, die
   der spätere `StreamingImporter` aus `TargetColumn` baut; `core`
   bekommt KEIN `jdbcType`-Feld auf `ColumnDescriptor`.
5. `DataFilter.ParameterizedClause` und Erweiterung von
   `AbstractJdbcDataReader` für parametrisierte WHERE-Klauseln, inkl.
   Test für `Compound([WhereClause, ParameterizedClause])` mit korrekter
   SQL-Erzeugung und Parameter-Positionsbindung.
   **Umsetzungsstand Phase A**: der Reader-Pfad ist auf
   `SelectQuery(sql, params)` plus internem `WhereFragment(sql, params)`
   umgestellt; `streamTable(...)` bindet die flach aggregierten Parameter
   deterministisch per 1-basiertem `stmt.setObject(idx, value)`, und
   `Compound([...])` erhält damit stabile SQL-/Parameter-Reihenfolge ohne
   String-Konkatenationsdrift. Phase A Schritt 5 hat dazu die harten
   Regressionstests für (a) nur `WhereClause`, (b) nur
   `ParameterizedClause`, (c) gemischtes `Compound(...)` mit korrekter
   Bind-Reihenfolge und zusätzlich den M-R5-Verbotstest für literales `?`
   im rohen `WhereClause`.
6. Go/No-Go-Spike für DSL-JSON Pull-Parsing auf einem 100-MB-Top-Level-Array
   mit konstantem Speicherbudget; der Spike verifiziert dabei zusätzlich,
   dass die für §3.5.2 benötigte Integer-vs-Decimal-Diskriminierung auf
   Token-Ebene zuverlässig verfügbar ist. Bei Fehlschlag wird die JSON-
   Library-Entscheidung vor Phase B neu geöffnet.
   **L5 — Fixture-Erzeugung**: das 100-MB-Array wird NICHT als Datei in
   `src/test/resources/` eingecheckt (Repo-Bloat) und auch NICHT in der
   Standard-CI-Pipeline mitgezogen. Stattdessen kommt ein
   deterministischer Generator (`adapters/driven/formats/src/test/kotlin/.../perf/
   LargeJsonFixture.kt` oder als Gradle-Task), der die Datei einmalig in
   `build/perf-fixtures/` erzeugt und dort cacht. Der zugehörige Test
   läuft als opt-in-Perf-Spec (`LargeJsonPullSpikePerfTest`) und wird im
   Gradle-Build über `-Dkotest.tags=perf` aktiviert. CI-Defaults laufen
   ohne `perf`; lokale Performance-Runs aktivieren das Tag explizit. Der
   echte Spike läuft dabei inzwischen ebenfalls direkt gegen diesen
   `build/perf-fixtures/`-Cachepfad und löscht das Fixture nach dem Lauf
   nicht mehr weg.
   **R7 — Cache-Invalidation**: der Generator schreibt neben dem
   eigentlichen Fixture eine `<fixture>.stamp`-Datei mit dem SHA-256
   seines eigenen Source-Inhalts plus aller relevanten Parameter
   (Row-Anzahl, Spalten-Schema, Random-Seed). Vor Wiederverwendung
   eines vorhandenen Caches vergleicht der Generator den aktuellen
   Stamp gegen den auf Disk; bei Abweichung wird das Fixture neu
   erzeugt. Damit kann eine spätere Generator-Änderung (z.B. Zahl-Range
   oder zusätzliche Spalte) niemals stille stale-Test-Daten auf einem
   lokalen `build/perf-fixtures/` produzieren — der nächste Lauf
   regeneriert deterministisch. Der aktuelle Phase-A-Spike liest das
   Fixture erfolgreich über DSL-JSONs Streaming-Iterator-Pfad ein und
   erzwingt dabei ein retained-heap-Budget deutlich unterhalb der
   Fixture-Größe.

### Phase B: Format-Reader

7. `JsonChunkReader` mit DSL-JSON Pull-Parser, Streaming-Tests gegen
   ein 100-MB-Fixture (kein Buffer-Aufbau über Chunk-Größe hinaus). Das
   Fixture stammt aus dem L5-Generator-Pfad aus Phase A Schritt 6 und
   ist `@Tag("perf")` — Standard-CI führt diesen Pfad nicht aus,
   lokale Perf-Runs aktivieren das Tag.
8. `YamlChunkReader` mit SnakeYAML Engine Event-API
9. `CsvChunkReader` mit uniVocity `CsvParser`, inkl. Header-Auflösung
10. `DefaultDataChunkReaderFactory`
11. Golden-Master-Reader-Tests pro Format mit Round-Trip gegen die
    Phase-D-Writer aus 0.3.0 (Schreiben → Lesen → Vergleich). H3-Property-
    Test: pro Writer (`JsonChunkWriter`, `YamlChunkWriter`, `CsvChunkWriter`)
    explizit asserten, dass eine Row mit ausschließlich `null`-Werten alle
    in `begin(...)` übergebenen Spalten als Schlüssel im Output
    materialisiert — sonst kippt die First-Row-Schema-Garantie aus §3.5.1.

Hinweis zur Parallelisierung: Nach Abschluss von Phase A sind Phase B
(Reader) und Phase C (Writer) weitgehend unabhängig und können bei Bedarf
parallel umgesetzt werden. Phase D (`StreamingImporter`) ist der erste
Schritt, der beide Pfade zusammenführt.

### Phase C: DataWriter-Port und JDBC-Treiber

12. `DataWriter`/`TableImportSession` Interfaces in `hexagon:ports`
13. `SchemaSync` Interface + `SequenceAdjustment`
14. `DataWriterRegistry` (object) mit `clear()` für Tests
15. `PostgresDataWriter` + `PostgresSchemaSync` (setval, ALTER TABLE
    DISABLE TRIGGER USER, `OVERRIDING SYSTEM VALUE` für PG-Identity-ALWAYS,
    inkl. Test für `pg_get_serial_sequence('"schema"."table"', 'col')`-
    Quoting). **L2-Pflichttests** für das Quoting:
    - Tabelle in einem nicht-`public`-Schema (z.B. `app.users`)
    - Tabellenname mit Sonderzeichen, der Quoting erzwingt
      (z.B. `"Order Items"` mit Whitespace und Mixed-Case)
    - Tabellenname mit eingebettetem Doublequote (`"a""b"`) als
      Pathologic-Test, dass die 0.3.0-Quoting-Logik tatsächlich escapet
      und nicht naiv konkateniert
16. `MysqlDataWriter` + `MysqlSchemaSync` (ALTER TABLE … AUTO_INCREMENT,
    `disable` → UnsupportedTriggerModeException; `rewriteBatchedStatements`
    im echten Writer-Pfad verifizieren)
17. `SqliteDataWriter` + `SqliteSchemaSync` (sqlite_sequence, `disable`
    nicht unterstützt)
18. Treiber-Bootstrap-Objects um `registerDataWriter` ergänzt

### Phase D: StreamingImporter

19. `StreamingImporter` in `adapters:driven:streaming`
20. `ImportInput` sealed class (Stdin / SingleFile / Directory)
21. `ImportResult` + `TableImportSummary`
22. Chunk-Transaktionsmodell (§6.5) mit `--on-error`-Politik
23. Streaming-Layer-Tests gegen SQLite (direkter `StreamingImporter`,
    kein CLI-Wrapper, kein Container nötig). **M2-Pflichtschritt**: ein
    JFR-/Allocation-Profiling-Run auf dem Reorder-Pfad mit einem
    1-Million-Row-Fixture (im `@Tag("perf")`-Set, also nicht in der
    Standard-CI). Wenn der Allocation-Anteil im Reorder-Pfad
    `> 25 %` der Importzeit ausmacht, wird vor Phase F ein additiver
    Vertrag entschieden (`session.write(reorderedRows, bindingColumns)`
    oder ein `RowBuffer`-Pool, siehe §10).
    **R5 — Phase-F-Gate**: Phase D gilt **erst dann** als
    abgeschlossen, wenn der Perf-Run lokal ausgeführt UND sein Ergebnis
    in `docs/perf/0.4.0-phase-d-reorder.md` (mit
    Importzeit-Gesamt, Allocation-Anteil im Reorder-Pfad, GC-Druck und
    Entscheidung „additiver Vertrag JA/NEIN" plus Begründung) abgelegt
    ist. Phase F darf nicht starten, solange diese Datei fehlt — auch
    wenn die Standard-CI grün ist. Das schützt aktiv davor, dass der
    `@Tag("perf")`-Run unter Zeitdruck übersehen wird und Phase F dann
    in ein Performance-Surprise-Refactoring läuft.
    **L11 — Reproduzierbarkeits-Anforderungen an das Perf-Doc**: Die
    25 %-Allocation-Schwelle aus M2 ist hardware-sensitiv (CPU-Takt,
    JIT-Tier, GC-Variante, Heap-Größe und OS beeinflussen Allocation-
    Anteil und GC-Druck direkt). Damit das JA/NEIN-Urteil später
    überhaupt reproduzierbar oder zumindest kommentierbar ist, MUSS das
    Perf-Doc mindestens die folgenden Messumgebungsdaten festhalten
    (bevor der Inhalt als „Gate passed" gilt):
    - CPU-Modell (z.B. „AMD Ryzen 9 7950X, 16 Cores @ 4.5 GHz base"),
    - verfügbarer physischer RAM und tatsächlich genutzter JVM-Heap
      (`-Xmx`/`-Xms` explizit),
    - JDK-Distribution und -Version (`java -version` plus Vendor),
    - aktiver GC (`-XX:+UseG1GC`/`-XX:+UseZGC`/default) plus ggf.
      zusätzliche GC-Flags,
    - OS und Kernel-Version,
    - Datum des Laufs.
    Ein Perf-Ergebnis ohne diese sechs Felder gilt als unvollständig;
    das Gate bleibt dann auch bei vorhandener Datei „zu".

### Phase E: CLI-Integration

24. `DataImportCommand` mit allen Flags aus §3.7.1
25. `DataExportCommand` Erweiterung um `--since-column`/`--since`
    (Phase E aus 0.3.0 + dieser Schritt)
26. `NamedConnectionResolver` aktiviert `default_source` und `default_target`
27. Exit-Code-Mapping für `data import` (§6.11)
28. CLI-Integration-Tests gegen SQLite — JSON/YAML/CSV-Round-Trips,
    `--truncate`, `--on-conflict update`, `--trigger-mode disable`
    (mit erwarteter UnsupportedTriggerModeException für SQLite)

### Phase F: End-to-End-Tests mit Testcontainers

29. `DataImportE2EPostgresTest` analog zur Export-E2E aus 0.3.0:
    Schema anlegen, Datei einlesen, Daten verifizieren, Sequence-Reseeding
    via `nextval()` checken; **H4/M-R7-Pflichtfall**: `enableTriggers(...)`
    gezielt scheitern lassen (z.B. via Test-Subclass-`SchemaSync`, die in
    `enableTriggers` nach erfolgreichem Reseeding wirft) und asserten,
    dass `TableImportSummary.failedFinish` strukturiert alle drei
    Cause-Felder (`adjustments`, `causeMessage`, `causeClass`) trägt und
    `causeClass` dem erwarteten qualifizierten Exception-Typ entspricht
    (kein Regex auf `causeMessage`), plus zusätzlicher Test-Fall für
    `closeCauseClass`, wenn auch der close-interne Retry scheitert.
    Der CLI-Output MUSS ebenfalls den `PARTIAL FAILURE`-Block enthalten
    und unter `--output-format json` alle sechs Felder strukturiert
    serialisieren.
30. `DataImportE2EMysqlTest` mit `AUTO_INCREMENT`-Verifikation
31. SQLite-E2E direkt ohne Container
32. Inkrementeller Round-Trip-E2E: initial export → Änderungen in der
    Source → ein Delta-Export → Delta-Import → Vergleich
33. `integration.yml` läuft schon generisch (siehe F39 aus 0.3.0) — keine
    Workflow-Anpassung nötig

---

## 5. Abhängigkeiten zwischen Modulen

```
hexagon:core
└── plus `ImportSchemaMismatchException`

hexagon:ports
├── api(hexagon:core)
├── api("com.zaxxer:HikariCP")
└── neu: DataWriter / TableImportSession / SchemaSync / DataWriterRegistry

adapters:driven:driver-common
├── api(hexagon:ports)

adapters:driven:driver-postgresql      # implementation(hexagon:ports) + JDBC
adapters:driven:driver-mysql           # dito
adapters:driven:driver-sqlite          # dito

adapters:driven:formats
├── implementation(hexagon:core)
├── implementation("com.dslplatform:dsl-json-java8")     # bereits aus 0.3.0
├── implementation("com.univocity:univocity-parsers")    # bereits aus 0.3.0
└── implementation("org.snakeyaml:snakeyaml-engine")     # bereits aus 0.3.0

adapters:driven:streaming
├── api(hexagon:core)
├── api(hexagon:ports)
├── api(adapters:driven:formats)
└── neu: StreamingImporter, ImportInput, ImportResult

adapters:driving:cli
├── implementation(adapters:driven:streaming)
└── alle Treiber wie in 0.3.0
```

Keine neuen Modul-Kanten. Die hexagonale Architektur bleibt: `formats` ist
weiterhin JDBC-frei, `streaming` koordiniert nur Reader und Writer. Auch
ARRAY-/JSONB-Sonderfälle ändern daran nichts: JDBC-nahe Materialisierung
(`Connection.createArrayOf()`, PG-Objekte etc.) bleibt im Writer-Layer und
zieht keine zusätzliche Modul-Kante in `formats`.

### 5.1 Build-Konfiguration (`gradle.properties` / `build.gradle.kts`)

**L9**: Dieser Abschnitt stand in der ersten Plan-Fassung als „§3.9" unter
„Bestehende Module — Änderungen", was strukturell falsch war —
`gradle.properties` und `build.gradle.kts` sind keine Module. Jetzt als
Unterabschnitt von §5, weil es thematisch um Dependencies geht.

Unter der Baseline-Entscheidung aus Phase A/B kommen keine neuen externen
Dependencies hinzu. JSON/YAML/CSV-Lib-Versionen aus 0.3.0 reichen aus — die
Reader-Pfade benutzen die gleichen Bibliotheken. Der DSL-JSON-Go/No-Go-Spike
liegt bereits in Phase A; sollte dieser Gate-Test künftig auf realen
Änderungen scheitern, wird die JSON-Library-Entscheidung vor dem eigentlichen
Reader-Ausbau explizit neu geöffnet statt still fortgeschrieben.

```properties
# Keine neuen Versions-Properties — alle Bibliotheken aus 0.3.0 wiederverwendet.
```

---

## 6. Zentrale Design-Entscheidungen

### 6.1 Symmetrie zu 0.3.0 als oberste Designregel

Wo immer möglich, folgt der Import-Pfad dem Export-Pfad symmetrisch:

| Export (0.3.0) | Import (0.4.0) |
|---|---|
| `DataReader` | `DataWriter` |
| `ChunkSequence` | `TableImportSession` |
| `DataChunkWriter` | `DataChunkReader` |
| `DataChunkWriterFactory` | `DataChunkReaderFactory` |
| `ExportOutput` | `ImportInput` |
| `ExportOptions` | `ImportOptions` |
| `ExportResult` / `TableExportSummary` | `ImportResult` / `TableImportSummary` |
| `StreamingExporter` | `StreamingImporter` |
| `DataExportCommand` | `DataImportCommand` |
| `DataReaderRegistry` | `DataWriterRegistry` |

Damit ist jede Architekturfrage, die in 0.3.0 schon entschieden wurde,
automatisch auch hier entschieden — vor allem §6.18 Connection-Ownership
(Pool durchgereicht), §6.13 treiberinterne Tuning-Konstanten und §6.16
Tag-basierte Testcontainers-Trennung. Wir referenzieren den 0.3.0-Plan
explizit, statt die Entscheidungen zu wiederholen.

### 6.2 Streaming statt Buffer beim Lesen

Die Reader-Implementierungen MÜSSEN echtes Streaming machen, nicht
„parse-then-iterate". Konkret:

- **JSON**: DSL-JSON's `JsonReader` über `InputStream`. Wir lesen die
  Top-Level-`[`-Klammer, dann pro `getNextToken()`-Iteration ein Objekt,
  bauen daraus eine `Array<Any?>`-Row und sammeln `chunkSize` Rows zu einem
  `DataChunk`. Wenn `]` erreicht ist → Stream zu Ende. Andere Top-Level-
  Formen (`{...}`, `{ "rows": [...] }`, NDJSON) sind in 0.4.0 Formatfehler.
- **YAML**: SnakeYAML Engine `Parse(LoadSettings)` liefert einen
  `Iterable<Event>`. Wir tracken den Document-/Sequence-/Mapping-Stack und
  emittieren beim Schließen jedes Top-Level-Mappings eine Row.
- **CSV**: uniVocity ist nativ chunk-fähig — nur ein Wrapper.

**YAML-Mini-Design**: der Reader akzeptiert in 0.4.0 nur eine Top-Level-
Sequenz von Mappings. Eine kleine interne State-Machine reicht:

1. `StreamStart/DocumentStart/SequenceStart` initialisieren den Row-Kontext
2. Jedes Top-Level-`MappingStart` beginnt eine neue Row
3. Scalar-Key/Value-Paare werden in file-order gesammelt
4. Beim korrespondierenden `MappingEnd` wird genau eine Row emittiert
5. Andere YAML-Formen (Skalare am Top-Level, verschachtelte Dokumentformen
   außerhalb des erwarteten Records-Schemas) führen zu einem klaren
   Formatfehler statt stiller Interpretation

**Test-Vertrag**: Pro Reader gibt es einen Spec-Test, der eine 100k-Row-
Datei liest und gegen ein grobes Speicherbudget prüft. Für den Phase-A-
Spike und die Reader-Tests reicht eine `MemoryMXBean`-Messung vor/nach
explizitem GC; Akzeptanzkriterium ist „retained heap nach 100k Rows bleibt
in derselben Größenordnung wie einige wenige Chunks, nicht proportional zur
Gesamtdatei". `Runtime.freeMemory()` allein ist dafür zu GC-abhängig.

Falls der DSL-JSON-Go/No-Go-Spike künftig scheitert, ist Jackson Streaming der
bevorzugte Fallback-Kandidat für Phase B; die Entscheidung wird dann vor
Beginn von Phase B explizit neu geöffnet.

### 6.3 Keine Auto-Format-Detection ohne Endung

Wenn `--format` nicht gesetzt ist, leitet das CLI das Format aus der
Dateiendung ab (`.json`, `.yaml`/`.yml`, `.csv`). Bei Stdin oder einer Datei
ohne erkennbare Endung → Exit 2 mit klarer Meldung. **Keine** Content-Sniffing-
Heuristik (zu viele Edge-Cases, falsche Positivmeldungen bei JSON-in-CSV-
Strings etc.).

### 6.4 Schema-Validierung ohne SchemaReader

Der `SchemaReader` (LF-004) kommt erst in 0.6.0. Bis dahin ist das **Target-
Schema die einzige autoritative Quelle** für Spalten-Typen und -Reihenfolge
beim Import. `DataWriter.openTable()` holt die Spalten der Zieltabelle intern über

```sql
SELECT * FROM "schema"."table" WHERE 1=0
```

und liest `ResultSetMetaData` aus. Daraus entsteht die
`List<ColumnDescriptor>` in `session.targetColumns`, die für
PreparedStatement-Type-Hints beim `ValueDeserializer` und als Referenz für
die Ableitung der tatsächlich gebundenen Insert-Spalten benutzt wird.

**Was der Reader beiträgt**: nur die `headerColumns()` aus §3.5.1 — eine
Liste von Spaltennamen, die das File explizit kennt. Daraus
entsteht eine **Header-Validierung**, kein Typ-System:

1. Wenn `headerColumns()` gesetzt ist (CSV mit Header / JSON oder YAML mit
   mindestens einem Objekt), muss jeder Header-Name eine EXAKT gleich
   geschriebene Spalte in der Zieltabelle haben. Es gibt keine
   Case-Folding-Heuristik über Dialekte hinweg. Ein Fehler nennt die
   fehlende Spalte; ein „closest target"-Vorschlag ist nur ein
   BEST-EFFORT-Hinweis und darf entfallen, wenn kein günstig eindeutig
   bestimmbarer Kandidat existiert. Zusätzliche Spalten im Target sind
   erlaubt; Spalten, die im File über die ganze Tabelle hinweg fehlen,
   werden aus dem INSERT-Spaltenblock weggelassen und bekommen dadurch
   DB-DEFAULT-Werte oder NULL.
   Danach baut der Importer genau einmal pro Tabelle ein Mapping
   `headerColumns()[i] -> targetColumns[j]` und reordert jede Row vor
   `session.write(...)` in Binding-Reihenfolge der tatsächlich
   gebundenen Spalten-Teilmenge.
   Bei JSON/YAML ist das Header-Schema der ersten Row autoritativ; die
   konkrete Per-Row-Normalisierung passiert bereits im Reader. Fehlen in
   späteren Rows bekannte Felder, materialisiert der Reader `NULL` in den
   positionalen Slots; tauchen zusätzliche, zuvor unbekannte Felder auf,
   wirft bereits der Reader ein `ImportSchemaMismatchException`, das dann
   `--on-error` folgt. Verletzt ein so materialisiertes `NULL` eine
   NOT-NULL- oder Constraint-Regel im Ziel, greift der normale
   Chunk-Fehlerpfad aus §6.5.
2. Wenn `headerColumns()` `null` ist (leeres JSON-/YAML-Array, oder
   `csvNoHeader = true`), wird die Header-Validierung übersprungen — dann
   muss die Reihenfolge der `Array<Any?>`-Werte im Chunk implizit der
   Spalten-Reihenfolge der Zieltabelle entsprechen. Bei CSV ohne Header
   ist das die einzige Möglichkeit. Für headerlose CSV ist der Vertrag in
   0.4.0 bewusst **strikt**: `len(row)` muss genau
   `len(session.targetColumns)` entsprechen; zu kurze oder zu lange Rows
   sind Schema-Mismatch mit klarer Meldung
   (`headerless CSV row has 3 columns, target expects 5`) und folgen
   `--on-error`. Bei leerem JSON/YAML gibt es schlicht keine Rows zu
   importieren — der Import endet mit `0 rows inserted`.
   Header-only-CSV ist davon ausgenommen: dort darf `headerColumns()` trotz
   `nextChunk() == null` gesetzt sein, so dass die Header-Validierung noch
   vor dem Ergebnis `0 rows inserted` laufen kann.
   **M-R9 — Headered CSV mit falscher Row-Länge**: Derselbe strenge
   Vertrag gilt auch für CSV **mit** Header. Liefert uniVocity für eine
   Daten-Row eine andere Feldanzahl als die Header-Zeile definiert hat
   (zu viele oder zu wenige Spalten), ist das ein Format-Fehler mit der
   Meldung `headered CSV row has N columns, header defined M` und läuft
   über `--on-error`. Der `CsvChunkReader` konfiguriert den uniVocity-
   `CsvParserSettings` dafür explizit so, dass kurze Rows **nicht**
   still mit `null` aufgefüllt werden und lange Rows **nicht** still
   abgeschnitten werden — beide Fehlkonfigurationen würden den Import
   still falsch durchlaufen lassen. Phase B Schritt 9 nimmt dafür zwei
   Mini-Fixtures mit auf: eine Row mit zu wenigen Feldern und eine mit
   zu vielen.
3. JDBC-Typkompatibilität wird **nur** als Warnung geprüft, nicht als
   Fehler — der `ValueDeserializer` versucht den Cast und fällt sonst auf
   die `--on-error`-Politik zurück.
4. Wenn `--schema <path>` gesetzt ist, wird zusätzlich gegen die neutrale
   Schema-Definition aus 0.1.0/0.2.0 validiert: Spaltennamen, Nullability,
   Constraints (siehe `SchemaValidator` aus core). Diese Pre-Validierung
   ist optional, weil viele Importe gegen DBs laufen, deren Schema
   nicht in einem `.yaml` versioniert ist.

> **F43-Konsequenz**: Der Reader liefert NIE einen `ColumnDescriptor` mit
> `sqlTypeName` — diese Information existiert in den drei Format-Dateien
> nicht. Alle Typ-bezogenen Entscheidungen (Casting, Bind-Index,
> Nullability-Check) hängen am Target-Schema, nie am File.
> `TableImportSession.write()` arbeitet deshalb gegen den vom Importer
> normalisierten `chunk.columns`-Subset in Target-Reihenfolge; die
> vollständigen `session.targetColumns` bleiben Referenz für Typ-Hints und
> Header-Mapping. Bei headerlosen Inputs gilt der positionale Vertrag.
> Es gibt bewusst KEINE Case-Folding-Heuristik: ein Header `userId` matcht
> also nicht still auf eine Target-Spalte `userid`. Der CLI-Help-Text weist
> darauf explizit hin. Das ist auf MySQL bewusst strenger als die DB selbst;
> wer dort abweichende Header-Cases hat, muss sie vor dem Import angleichen.
>
> **Verantwortungsmatrix**:
> Reader:
> normalisiert JSON/YAML-Rows gegen das First-Row-Feldset und validiert
> headerlose CSV-Rowlängen.
> StreamingImporter:
> mapped File-Header gegen Target-Spalten, leitet die gebundene
> Spalten-Teilmenge ab und castet Werte in Target-Reihenfolge.
> Writer:
> erzeugt daraus den finalen INSERT-/UPSERT-SQL-Pfad gegen das Zielschema.

### 6.5 Chunk-Transaktionsmodell

Pro Tabelle eine Connection mit `autoCommit=false`. Pro Chunk:

```
session.write(chunk)                     -- batch Insert
  ├── Erfolg → session.commitChunk()     -- chunk persistiert
  │              ├── Erfolg → next Chunk (state: WRITTEN → OPEN)
  │              └── wirft  → Session in FAILED (H-R1) → siehe unten
  └── Fehler → session.rollbackChunk()   -- chunk verworfen
                  ├── Erfolg → next Chunk (state: WRITTEN → OPEN)
                  │              ├── --on-error abort → Tabellen-Import-Abbruch, Exit 5
                  │              ├── --on-error skip  → next Chunk (CHUNK-Granularität)
                  │              └── --on-error log   → next Chunk + im Report vermerken (CHUNK-Granularität)
                  └── wirft  → Session in FAILED (H-R1) → siehe unten
```

Nach erfolgreichem `rollbackChunk()` bleibt die Connection im normalen
`autoCommit=false`-Importzustand; Statement/Session können für den nächsten
Chunk weiterverwendet werden.

**H-R1 — Wenn `commitChunk()` oder `rollbackChunk()` selbst wirft**: Der
Writer hat die Session in `FAILED` überführt (siehe State-Maschine in
§3.1.1). Der DB-seitige Zustand der Transaktion ist in diesem Pfad
**unbekannt**, und die Connection selbst ist verdächtig — ein zweiter
Rollback-Versuch, ein weiterer `write(...)` oder gar `finishTable()` sind
deshalb nicht erlaubt und werfen aus `FAILED` `IllegalStateException`.
Der `StreamingImporter` muss diesen Pfad strenger behandeln als den
regulären Chunk-Fehlerpfad:

1. Der fehlerhafte Chunk wird als verloren gezählt (`rowsFailed +=
   chunk.size`, analog zum Skip/Log-Pfad).
2. `session.close()` wird **sofort** aufgerufen — die Session ist für
   diese Tabelle verbraucht, auch unter `--on-error skip|log`. Das
   weitere Abarbeiten weiterer Chunks DERSELBEN Tabelle ist bewusst
   nicht erlaubt, weil der DB-Zustand nicht mehr vertrauenswürdig ist.
3. `--on-error` wirkt dann nur noch auf die **Tabellen-Ebene**: bei
   `abort` endet der Lauf mit Exit 5; bei `skip|log` macht der
   Importer mit der nächsten Tabelle (Multi-Table-Pfad) weiter und
   vermerkt den Tabellen-Abbruch in `chunkFailures`.

Das ist strenger als „Chunk verloren, nächster Chunk derselben Tabelle"
aus dem regulären Rollback-Pfad. Rationale: nach einem scheiternden
Commit/Rollback kann der Writer nicht unterscheiden, ob der Insert
teilweise persistiert wurde; weitere Schreibversuche auf derselben
Session könnten den DB-Zustand still korrumpieren. Die strengere
Behandlung ist in Phase D Pflichttest: ein Test-Subclass-Writer, der
beim ersten `commitChunk()` wirft, plus ein zweiter, der beim
`rollbackChunk()` wirft — und beide Tests asserten, dass der Importer
nach dem Fehler keinen zweiten `write(...)` mehr auf derselben Session
versucht.

**Wichtig**: Der vorherige Chunk bleibt persistiert. Kein „alles oder
nichts" auf Tabellen-Ebene — das wäre ein Checkpoint/Resume-Problem (LN-012,
0.9.0). Einen generischen atomaren Tabellen-Import über mehrere Chunks gibt
es in 0.4.0 bewusst NICHT; wer dieses Verhalten braucht, wartet auf den
späteren `--replace-table`-Pfad aus §6.14/§11.

Dasselbe gilt eine Ebene höher für Multi-Tabellen-Imports: bereits
erfolgreich abgeschlossene Tabellen bleiben stehen, wenn eine spätere
Tabelle scheitert. 0.4.0 kennt keine transaktionsübergreifende
„alles-oder-nichts"-Klammer über mehrere Tabellen.

**Wichtig 2**: `skip` und `log` sind in 0.4.0 bewusst **keine
Row-Recovery-Modi**. Wenn eine Row einen Chunk scheitern lässt, gehen die
anderen Rows dieses Chunks mit verloren. Wer vereinzelte kaputte Rows
tolerieren will, arbeitet mit kleinerem `--chunk-size`. Ein späterer
Row-by-Row-Replay-Pfad wäre ein additiver Milestone.

### 6.6 Sequence-Reseeding-Trigger

Nach dem letzten erfolgreich committed Chunk pro Tabelle (in
`session.finishTable()`):

0. **R2 — Transaktions-Reset vor dem Reseeding**: `finishTable()` ruft
   als erste Aktion `conn.commit()` idempotent auf. Hintergrund: nach
   dem letzten `commitChunk()` läuft die Connection weiter im
   `autoCommit = false`-Modus aus dem Chunk-Pfad (§6.5), und der
   anschließende `SELECT MAX(col) FROM target` würde sonst eine neue
   implizite Lese-Transaktion eröffnen, die bis zum nächsten `commit()`
   offen bleibt. Das ist auf MySQL aktiv gefährlich, weil
   `ALTER TABLE … AUTO_INCREMENT = N` ein impliziter DDL-Commit ist und
   damit eine Transaktion mitten im Reseeding-Pfad schließt; auf dem
   0-Chunk-Pfad (F1) gibt es zudem überhaupt keinen vorherigen
   `commit()`, sodass der `SELECT MAX(col)` die allererste Transaktion
   der Session eröffnen würde. Der Step-0-Commit (no-op nach einem
   regulär gerade frisch committeten Chunk, aktiv abräumend nach 0
   Chunks oder nach einer impliziten Lese-Transaktion) macht den
   Transaktionszustand vor dem dialektspezifischen Reseeding
   deterministisch. Wirft Schritt 0 selbst (in der Praxis fast
   ausgeschlossen, weil `commit()` auf einer leeren Transaktion ein
   no-op ist), wird die Exception wie ein Reseeding-Fehler durchgereicht
   (§3.1.1 „Schritt 1 wirft").
   **L13 — Geltungsbereich explizit eingegrenzt**: R2 räumt
   ausschließlich den Transaktionszustand **innerhalb der eigenen
   Session-Connection** auf. Parallele Writer auf **anderen** Sessions
   (eine zweite `d-migrate`-Instanz, ein manuell abgesetztes
   `INSERT`-Statement aus `psql`, ein anderer Applikationsprozess)
   sind davon nicht betroffen — zwischen dem `SELECT MAX(col)` in
   Schritt 2 und dem dialektspezifischen Reseeding in Schritt 4 kann
   ein Fremd-Writer auf derselben Tabelle inserten und damit den
   gerade berechneten Reseed-Wert wieder „überholen", sodass der
   `setval`/`AUTO_INCREMENT`-Wert nach Abschluss von `finishTable()`
   bereits wieder hinter dem tatsächlichen Hochstand der Tabelle
   liegt. Diese **cross-session-Race** ist in 0.4.0 explizit
   out-of-scope (siehe §10, Risiko „Sequence-Reseeding race"); R2
   selbst beansprucht bewusst KEINE cross-session-Garantie und darf
   nicht als „race-frei" gelesen werden. Der CLI-Help-Text für
   `data import` weist darauf hin (§7, Sequence-Race-Hinweis im
   Help-Text-Block, vgl. L4 aus der ersten Review-Runde).
1. Pro Spalte aus `importedColumns` prüfen: ist sie eine Generator-Spalte
   im Ziel? (PG: `pg_get_serial_sequence` für `SERIAL` sowie
   `GENERATED { BY DEFAULT | ALWAYS } AS IDENTITY`; MySQL: Spalten-Metadata
   `EXTRA = 'auto_increment'`; SQLite: `INTEGER PRIMARY KEY AUTOINCREMENT`
   in `sqlite_master`)
2. Wenn ja: höchsten Wert der Spalte **im Ziel nach Abschluss aller Chunks**
   ermitteln (`SELECT MAX(col) FROM target` direkt nach dem Step-0-Commit
   auf derselben Session-Connection; ohne zusätzliche cross-session-
   Garantien — siehe §10)
3. Ergibt `MAX(col)` dabei `NULL` (leere Tabelle oder nur NULL-Werte in der
   Generator-Spalte), ist der Reseed-Pfad ein expliziter No-op: keine
   `setval`-/`AUTO_INCREMENT`-/`sqlite_sequence`-Änderung und kein
   `SequenceAdjustment` im Report. **H5/F4-Ausnahme** für MySQL und SQLite
   `INTEGER PRIMARY KEY AUTOINCREMENT`: wenn der Importer auf dieser
   Session vorher `markTruncatePerformed()` aufgerufen hat (R3 — siehe
   §3.1.1, §6.14) UND die Tabelle danach leer geblieben ist, MUSS der
   Folge-Zähler dieser Tabelle aktiv zurückgesetzt werden, damit der
   nächste Insert wieder bei `1` startet — sonst kollidiert das
   Verhalten mit der intuitiven PG-`TRUNCATE`-Semantik.
   - **MySQL (H5)**: `ALTER TABLE \`table\` AUTO_INCREMENT = 1`,
     `SequenceAdjustment(newValue = 1)` (siehe §3.3).
   - **SQLite (F4)**: `DELETE FROM sqlite_sequence WHERE name = ?` für
     `AUTOINCREMENT`-Tabellen, `SequenceAdjustment(newValue = 0)` (siehe
     §3.4). Für nicht-`AUTOINCREMENT`-Tabellen mit gewöhnlicher
     `INTEGER PRIMARY KEY` ist nichts zu tun, weil SQLite dort keinen
     persistenten Folge-Zähler führt.
   - **PG**: bekommt diesen Reset automatisch vom `TRUNCATE`-Pfad
     geschenkt; kein Sonderfall nötig.
4. Sonst Sequence/AUTO_INCREMENT/sqlite_sequence auf den nächsten gültigen
   Wert anheben
5. Ein `SequenceAdjustment` ins `ImportResult` aufnehmen

Für PostgreSQL gilt zusätzlich: `pg_get_serial_sequence(...)` erwartet den
qualifizierten Tabellennamen als SQL-Stringargument mit eingebetteten Quotes
(`'"public"."orders"'`), nicht den Identifier selbst. Die Implementierung
nutzt dafür die vorhandene Identifier-Quoting-Logik aus 0.3.0 statt eine
zweite Quoting-Variante im Writer zu erfinden.

Wenn das Ziel eine `GENERATED ALWAYS AS IDENTITY`-Spalte enthält und diese
Spalte importiert wird, muss der PG-Writer den Insert-Pfad mit
`OVERRIDING SYSTEM VALUE` erzeugen. **K1 — finale Entscheidung** (siehe
auch §3.2): für `GENERATED BY DEFAULT AS IDENTITY` wird der Zusatz
**bewusst nicht** ausgegeben, weil PG dort explizite Insert-Werte ohnehin
akzeptiert und ein Einheits-Pfad die Phase-C/F-Tests komplexer macht,
ohne fachlichen Mehrwert.

**Abschalten**: `--reseed-sequences=false` überspringt Schritt 1–3
komplett. Default ist `true`. Begründung: stilles Auslassen wäre genau die
„gefährlichste Klasse" aus dem Design-Doc §11.

**Fehlerpfad**: `close()` führt bewusst KEIN Reseeding aus. Nach
Teilimporten oder `--on-error abort` ist Cleanup korrekt, Generator-
Nachführung aber fachlich falsch.

**Grenze des Vertrags**: Eine echte cross-session-Race-Serialisierung gegen
parallele Writer anderer Sessions ist in 0.4.0 explizit out-of-scope; siehe
Risiko-Tabelle in §10.

### 6.7 Trigger-Modi

Direkter Übernahme des Vertrags aus `design-import-sequences-triggers.md`
§5.1, mit folgenden Konkretisierungen:

| Modus | PG | MySQL | SQLite |
|---|---|---|---|
| `fire` (Default) | nativ | nativ | nativ |
| `disable` | `ALTER TABLE ... DISABLE TRIGGER USER` pro Tabelle | **nicht unterstützt** → Exit 2 | **nicht unterstützt** → Exit 2 |
| `strict` | Tabelle hat Trigger? → Exit 3 mit Hinweis | dito | dito |

`disable` ist **explizit nicht** über `session_replication_role = replica`
implementiert (zu breit, Sicherheitsrisiko). Pro Tabelle `ALTER TABLE`
ist deutlich enger und reverskompatibel. Der Aufruf erfolgt beim Öffnen
der `TableImportSession` writer-intern; bei `finishTable()` oder `close()`
wird der Zustand symmetrisch zurückgeführt.

**M8 — Transaktions-Scope von `disableTriggers(...)` auf PG**:
`ALTER TABLE … DISABLE TRIGGER USER` ist eine DDL-artige Operation. Sie
darf NICHT Teil der späteren Chunk-Transaktionen sein, sonst würde ein
`rollbackChunk()` (z.B. nach einer Constraint-Verletzung im ersten Chunk)
die Trigger plötzlich wieder reaktivieren — und der nächste Chunk liefe
unter einem anderen Modus, ohne dass das jemand sieht. Konkreter
Vertrag:

1. `openTable(...)` schaltet die Connection auf `autoCommit = true`,
   bevor `disableTriggers(...)` aufgerufen wird
2. Nach erfolgreichem `disableTriggers(...)` wird sofort `commit()`
   aufgerufen (idempotent unter `autoCommit = true`)
3. Erst danach setzt `openTable(...)` `autoCommit = false` und beginnt
   den Chunk-Transaktionspfad aus §6.5
4. `enableTriggers(...)` läuft beim Erfolgsabschluss bzw. im
   `close()`-Cleanup symmetrisch wieder unter `autoCommit = true` mit
   eigenem `commit()`

Damit existieren `disableTriggers` und `enableTriggers` immer als ihre
eigenen Mini-Transaktionen, völlig getrennt vom Chunk-Pfad. Phase C
verifiziert das mit einem PG-Test, der nach `disableTriggers` einen
expliziten Chunk-Rollback provoziert und prüft, dass die Trigger danach
weiterhin deaktiviert sind.

`strict` benutzt `SchemaSync.assertNoUserTriggers(...)` als writer-internen
Pre-Flight (dialektspezifisch gegen `pg_trigger`/`information_schema.triggers`/
`sqlite_master`) und bricht mit Exit 3 ab, wenn die Tabelle Trigger hat.
Der Name meint hier also **Sicherheits-Pre-Flight**, nicht einen
„strengeren Trigger-Modus" zur Laufzeit.

### 6.8 Foreign-Key-Checks während Import

Multi-Tabellen-Imports mit Fremdschlüsseln scheitern oft am Lade-Reihenfolge-
Problem: Tabelle B referenziert A, aber B steht in der Eingabe vor A.
0.4.0 unterstützt drei Pfade — der vierte (PG `--disable-fk-checks` als
generischer Mechanismus) ist explizit aus Sicherheitsgründen ausgeschlossen
(F42).

#### 6.8.1 PostgreSQL

PG bekommt **bewusst keinen generischen `--disable-fk-checks`-Pfad** in
0.4.0. Die nahe liegenden Mechanismen funktionieren in der Praxis nicht
zuverlässig:

- `SET session_replication_role = replica` ist zu breit, ändert
  Sicherheits- und Replikations-Semantik global, und ist auf Managed-PG-
  Diensten oft komplett gesperrt.
- `ALTER TABLE … DISABLE TRIGGER ALL` umfasst zwar die System-Trigger für
  FK-Constraints, braucht aber **Superuser-Rechte** (`pg_authid.rolsuper`)
  und scheitert bei normalen App-Connections — also genau bei den Imports,
  die das Feature bräuchten. Auch der `pg_trigger.tgenabled`-Switch wird
  von einigen Managed-Diensten blockiert.

Für PG werden in 0.4.0 stattdessen zwei sichere Wege unterstützt:

| Pfad | Voraussetzung | Implementierung |
|---|---|---|
| **(a)** Topologische Sortierung der Tabellen | `--schema <path>` mit der neutralen Schema-Definition | Wiederverwendung des `AbstractDdlGenerator.topologicalSort(...)`-Helpers (0.2.0, Kahn-Algorithmus intern). Importe laufen in FK-respektierender Reihenfolge, FK-Checks bleiben aktiv. **H-R2**: der Helper ist heute `protected` und liefert `TopologicalSortResult(sorted, circularEdges)` ohne bei Zyklen zu werfen. Phase E muss (a) die Sichtbarkeit anheben oder einen öffentlichen `topologicalSort`-Helper extrahieren und (b) im Runner explizit `circularEdges.isNotEmpty() → Exit 3` prüfen (siehe R8 in §3.7.1). |
| **(b)** `SET CONSTRAINTS ALL DEFERRED` | Constraints sind im Ziel-Schema mit `DEFERRABLE` deklariert | Pro Chunk-Transaktion vor dem ersten Insert ausgeführt. Vorab prüft ein PG-Pre-Flight `pg_constraint.condeferrable` und loggt/reportet `N deferrable / M non-deferrable` FK-Constraints für die betroffenen Tabellen. Bei FK-abhängigem Multi-Table-Import ohne `--schema` und ohne passende deferrable Constraints → Exit 2 mit klarem Hinweis statt stillem No-Op. |

Wichtig: Pfad (b) verzögert Constraints nur bis zum nächsten Chunk-Commit.
Er ist also keine generische Alternative zu Cross-Table- oder Cross-Chunk-
Reihenfolgeproblemen; dafür bleibt Pfad (a) via `--schema` der saubere Weg.

**F6 — wofür Pfad (b) tatsächlich hilft (und wofür nicht)**: Der
realistische Anwendungsbereich von `SET CONSTRAINTS ALL DEFERRED` am
Chunk-Level ist eng. Konkret:

- **Hilft** bei **intra-Chunk** Reihenfolge-Problemen innerhalb **einer**
  Tabelle: z.B. selbstreferenzielle Hierarchien (Tree-Strukturen mit
  `parent_id`-FK auf dieselbe Tabelle), wo eine Row in der Mitte des
  Chunks auf eine Row weiter hinten im selben Chunk verweist. Die
  Constraints sind erst beim Chunk-Commit hart, also dürfen die Rows in
  beliebiger Reihenfolge innerhalb des Chunks geschrieben werden.
- **Hilft NICHT** bei **Multi-Table** Parent/Child-Reihenfolgen, wenn
  Parent- und Child-Tabelle in **separaten** Tabellen-Imports oder gar
  in **separaten Chunks** geladen werden. Sobald der Chunk der
  Parent-Tabelle commit-tet ist (oder umgekehrt der Chunk der
  Child-Tabelle gelesen wird, bevor die Parent-Rows existieren), greift
  der Constraint und der Insert wirft. `SET CONSTRAINTS ALL DEFERRED`
  endet **am Chunk-Commit** — eine cross-table Verzögerung erreicht der
  Pfad nicht. Wer Parent/Child in der richtigen Reihenfolge laden will,
  braucht zwingend Pfad (a) (`--schema`) oder einen manuell
  vorgeordneten Datenbestand.
- **Hilft NICHT** bei **Cross-Chunk** Verweisen innerhalb derselben
  Tabelle, wenn die referenzierte Row in einem späteren Chunk steht.
  Auch hier ist der frühere Chunk längst committed, bevor der spätere
  läuft.

Faustregel: Pfad (b) ist eine punktuelle Erleichterung für
selbstreferenzielle Strukturen in einer einzigen Tabelle, kein
„FK-Disable für Multi-Table-Importe ohne `--schema`". Der CLI-Help-Text
und die Pre-Flight-Meldung formulieren das entsprechend, damit kein
User Pfad (b) als Parent/Child-Retter missversteht.

Wer FK-Checks **wirklich** deaktivieren muss, läuft den Import als
Superuser oder bereitet die Constraints vorab als `DEFERRABLE` vor. Beide
Wege sind dem User aktiv überlassen; d-migrate stellt nicht stillschweigend
einen unsicheren Pfad bereit.

`--disable-fk-checks` auf PG → **Exit 2** mit Hinweis-Text:

```
--disable-fk-checks is not supported on PostgreSQL in 0.4.0.
Use --schema <path> for topological table ordering, or declare your
foreign-key constraints as DEFERRABLE and they will be deferred
automatically per table transaction.
```

#### 6.8.2 MySQL und SQLite

Beide Dialekte haben **session-lokale, nicht privilegierte** Schalter und
sind damit safe für `--disable-fk-checks`:

| Dialect | Schalter |
|---|---|
| MySQL | `SET FOREIGN_KEY_CHECKS=0` für die Session, am Ende `SET FOREIGN_KEY_CHECKS=1` |
| SQLite | `PRAGMA foreign_keys = OFF` für die Connection, am Ende `PRAGMA foreign_keys = ON` |

`--disable-fk-checks` ohne Setzen des Flags → Default: FK-Checks bleiben
aktiv, User muss die Eingabe-Reihenfolge selbst stimmig machen.

**H-R3 — Pool-Leak-Vertrag**: „am Ende" meint hier **explizit** den
R6-Cleanup-Pfad in `TableImportSession.close()` (siehe §3.1.1), Schritt 4,
auf **genau derselben Connection**, auf der der Schalter gesetzt wurde —
BEVOR `pool.release(conn)` die Connection an den HikariCP-Pool
zurückgibt. Hintergrund: HikariCP setzt beliebige Session-Variablen beim
Check-in der Connection **nicht automatisch** zurück. Ohne einen
expliziten Reset würde der nächste Borrower derselben Connection — z.B.
der Import der nächsten Tabelle in einem Multi-Table-Lauf, oder ein
paralleler Reader-Pfad im selben Prozess — mit deaktivierten FK-Checks
weiterarbeiten. Der Writer hält dafür einen internen Boolean
`fkChecksDisabled`, den er beim ersten `SET FOREIGN_KEY_CHECKS=0` bzw.
`PRAGMA foreign_keys = OFF` setzt, und wertet ihn in R6-Schritt 4 aus.
Der Reset ist dort best-effort: wirft der Reset-Aufruf selbst, landet die
Exception im strukturierten Reporting-Pfad, `close()` selbst wirft nicht,
und die Connection wird trotzdem an den Pool zurückgegeben (weil eine
zurückgehaltene kaputte Connection mehr Schaden anrichtet als eine
zurückgegebene mit markiertem Fehler — HikariCP verifiziert beim nächsten
Borrow).

**Phase-C-Pflichttest für den Pool-Leak**: Sowohl für MySQL als auch für
SQLite gibt es einen Test, der mit einem HikariCP-Pool der Größe 1 zwei
sequenzielle `TableImportSession`-Läufe auf derselben Connection fährt:
die erste Session setzt `--disable-fk-checks` und wird sauber geschlossen;
die zweite Session öffnet eine neue `TableImportSession` (ohne
`--disable-fk-checks`) und asserted via `SELECT @@FOREIGN_KEY_CHECKS`
(MySQL) bzw. `PRAGMA foreign_keys` (SQLite), dass der Wert wieder `1`
bzw. `ON` steht. Ein fehlender Reset ließe diesen Test sofort kippen.

Bei `--truncate` ist zusätzlich relevant:

- MySQL `TRUNCATE TABLE` auf FK-referenzierten Tabellen ist unzuverlässig bzw.
  kann trotz Import-Intention scheitern. 0.4.0 fällt auf MySQL deshalb auf
  `DELETE FROM` als destruktiven Pre-Step zurück statt einen unsauberen
  `TRUNCATE`-Pfad zu versprechen.
- Wenn `--disable-fk-checks` gesetzt ist, werden die session-lokalen
  Schalter immer auf genau der Connection gesetzt, auf der der jeweilige
  destruktive Pre-Step oder Import läuft. Ein späteres Aktivieren auf einer
  anderen Connection hätte keine Wirkung auf die bereits laufende Session.

#### 6.8.3 Topologische Sortierung mit `--schema`

`--schema <path>` hat zwei Wirkungen, die strikt getrennt sind (M5):

1. **Schema-Validierung** (immer aktiv, sobald `--schema` gesetzt ist —
   für `Stdin`, `SingleFile` und `Directory` gleichermaßen): die in §6.4
   Punkt 4 beschriebene Pre-Validierung gegen die neutrale Schema-
   Definition läuft. Das ist die einzige Wirkung von `--schema` für
   Single-File- und Stdin-Imports.
2. **FK-respektierende Topologische Sortierung** (nur für `Directory`):
   wenn `ImportInput.Directory` aktiv ist UND `--schema` gesetzt ist, wird
   die Tabellenreihenfolge per `AbstractDdlGenerator.topologicalSort(...)`
   aus dem Schema-DAG abgeleitet. Für `Stdin` und `SingleFile` macht
   Topo-Sort fachlich keinen Sinn (es gibt nur eine Tabelle) und der Plan
   macht hier ausdrücklich KEINEN Topo-Sort, auch wenn `--schema` gesetzt
   ist.

Innerhalb von Pfad (2) ist das der einzige Weg, der ohne jeden
FK-Disable-Trick funktioniert und auf allen Dialekten gleichermaßen sauber
ist. Implementierung kommt direkt aus dem 0.2.0
`AbstractDdlGenerator.topologicalSort(...)` — derselbe Kahn-Algorithmus
intern, andere Eingabe (Tabellen statt DDL-Statements).

**H-R2 — Anpassungen für den Import-Pfad**: Der heutige Helper ist
`protected` (nur aus Subklassen des `AbstractDdlGenerator` aufrufbar) und
wirft **nicht** bei Zyklen — er liefert `TopologicalSortResult(sorted,
circularEdges)` und hängt zyklische Tabellen hinten an `sorted` an (siehe
`AbstractDdlGenerator.kt:208-213`). Für die 0.2.0-DDL-Generierung ist das
Best-Effort-Verhalten vertretbar, weil der generierte DDL-Output danach
manuell reviewt wird; für den Import-Pfad ist es **nicht** akzeptabel,
weil ein Zyklus stillschweigend zu einer nicht-topologischen Reihenfolge
führt und jeden FK-abhängigen Import in ein Reihenfolge-Raten kippt.

Phase E zieht deshalb zwei kleine Refactorings mit:

1. **Sichtbarkeit**: Entweder wird `topologicalSort(...)` auf `public` (oder
   `internal` zwischen `adapters:driven:driver-common` und `adapters:driving:cli`/
   `adapters:driven:streaming`) angehoben, oder der Algorithmus wird in einen
   eigenständigen, öffentlichen Helper `TableTopologicalSort.of(schema)`
   im `adapters:driven:driver-common` (oder im neuen Runner-Layer des CLI) ausgelagert
   und aus dem `AbstractDdlGenerator` delegiert. Beide Varianten sind
   additiv und brechen keine bestehenden Caller.
2. **Zyklus-Check**: Der Runner-Pfad ruft den Helper, prüft
   `result.circularEdges.isNotEmpty()` und wirft bei Zyklen eine eigene
   Exception, die im CLI-Wrapper auf **Exit 3** mit einer Fehlermeldung
   gemappt wird, die mindestens die betroffenen (from → to)-Kanten listet.
   Das ist R8 (§3.7.1) und wird in Phase D/E mit einem expliziten Test
   abgesichert, der ein Schema mit zwei Tabellen A→B→A übergibt und
   asserted, dass der Import nicht stattfindet, sondern mit Exit 3 endet.

Ohne `--schema` gibt es KEINE FK-basierte Sortierung. Directory-Imports
laufen dann in deterministischer lexikographischer Reihenfolge der
Datei-/Tabellennamen. Für FK-abhängige Datensätze ist `--schema` damit
praktisch Pflicht; der CLI-Help-Text soll das explizit empfehlen.

Wenn zusätzlich `--tables a,b,c` gesetzt ist, wird die Reihenfolge aus dem
vollständigen Schema-DAG abgeleitet und danach auf das gewünschte Subset
gefiltert. Der Plan schließt also keine Kanten „weg", nur weil der User
einen Teilgraphen importieren will; fehlende Abhängigkeitstabellen werden
dadurch aber nicht automatisch mitimportiert.

Layer-Owner dafür ist der CLI-/Runner-Pfad: `DataImportCommand` bzw. sein
Runner berechnet bei `--schema` die Tabellenreihenfolge vor dem Aufruf von
`StreamingImporter.import(...)` und reicht sie über
`ImportInput.Directory.tableOrder` hinein. `StreamingImporter` respektiert
diese Reihenfolge, berechnet sie aber nicht selbst noch einmal.

Wenn zusätzlich `--truncate` gesetzt ist, wird die Reihenfolge pro Tabelle
für Single-Table-Imports als `truncate/delete -> import -> finishTable`
auf derselben Session-Connection gefahren.

Für Multi-Table-Imports mit `--schema` und FK-Abhängigkeiten gilt dagegen
eine explizite Zweiphasen-Regel:

1. destruktiver Pre-Pass über ALLE Tabellen in **umgekehrter**
   topologischer Reihenfolge (`TRUNCATE`/`DELETE`)
2. regulärer Import-Pass in normaler topologischer Reihenfolge

Nur so kollidiert das Leeren nicht mit noch vorhandenen Referenzen aus
Kindtabellen. Ohne `--schema` gibt es auch dafür keine zusätzliche Magie;
dann bleibt es bei deterministischer lexikographischer Reihenfolge bzw.
deren Umkehrung im Pre-Pass, was für FK-lastige Directory-Imports nur
Best-Effort ist.

### 6.9 Encoding-Unterstützung (BOM-Detection nur für UTF-Varianten)

`EncodingDetector` ist **kein** universeller Charset-Detector — er erkennt
ausschließlich BOM-markierte UTF-Streams. Alles andere ist Sache eines
expliziten `--encoding`-Flags (F45). BOM-Sniffing läuft NUR, wenn
`--encoding auto` gesetzt ist. Ein explizites `--encoding <charset>` hat
immer Vorrang und deaktiviert die Auto-Erkennung vollständig.

Bei `--encoding auto` liest `EncodingDetector` die ersten 4 Bytes des
`InputStream` per `PushbackInputStream` und entscheidet:

| Bytes | Encoding | Verhalten |
|---|---|---|
| `EF BB BF` | UTF-8 (BOM) | erkannt, BOM wird übersprungen |
| `FE FF` | UTF-16 BE (BOM) | erkannt, BOM wird übersprungen |
| `FF FE` | UTF-16 LE (BOM) | erkannt, BOM wird übersprungen |
| `00 00 FE FF` | UTF-32 BE (BOM) | nicht supported, Exit 2 mit Hinweis auf `--encoding` |
| `FF FE 00 00` | UTF-32 LE (BOM) | nicht supported, Exit 2 mit Hinweis auf `--encoding` |
| sonstige | (keine BOM) | Fallback auf UTF-8 |

Die geprüften Bytes werden via `unread()` zurück in den Stream gelegt, so
dass der eigentliche Reader sie noch sehen kann — der CSV-Parser zum Beispiel
verschluckt das BOM nicht zuverlässig, wir müssen es selbst überspringen.

Bei explizitem `--encoding utf-8|utf-16le|...` findet KEINE Auto-Detection
statt. Ein UTF-BOM wird in diesem Pfad nur dann konsumiert, wenn er zum
angeforderten Charset passt; bei einem Mismatch (`--encoding iso-8859-1`,
aber Datei beginnt mit UTF-8-BOM) bleiben die Bytes im Stream und werden
nicht still „weginterpretiert". Das BOM überschreibt in diesem Pfad nie das
angeforderte Charset.

Ungültige Charset-Namen (`--encoding latin42`) führen in 0.4.0 zu Exit 2
mit klarer Meldung aus dem `Charset.forName(...)`-Pfad
(`unsupported encoding: latin42`).

**Keine Heuristik** für Non-BOM-Dateien (chardet, ICU). Konkret bedeutet
das:

- **UTF-8-Dateien ohne BOM**: werden vom UTF-8-Default automatisch korrekt
  gelesen — keine User-Aktion nötig.
- **UTF-16-Dateien ohne BOM**: gelten als nicht-portabel und werden in 0.4.0
  als reguläre Bytes gelesen, die der UTF-8-Decoder dann ablehnt. User muss
  `--encoding utf-16le`/`utf-16be` explizit setzen.
- **ISO-8859-1, Windows-1252, CP-437 etc.**: ausschließlich über
  `--encoding iso-8859-1` (oder den jeweiligen Java-Charset-Namen). Es gibt
  **keine** automatische Erkennung — der Lastenheft-Eintrag LF-010, der von
  „Encoding-Erkennung" spricht, wird in 0.4.0 als „Encoding-Unterstützung
  mit explizitem Override" interpretiert.

Wenn der Default `auto` keine BOM findet und kein expliziter Wert gesetzt
ist, fällt der Detector auf UTF-8 zurück. **F9 — Hinweis nur bei
Verdacht oder `--verbose`**: Den stderr-Hinweis (`Hint: input file has
no BOM, using UTF-8. Set --encoding to override.`) gibt der Importer
NICHT für jeden BOM-losen Lauf aus — der häufigste reale Fall ist
„UTF-8 ohne BOM" und ein unkonditionaler Hinweis erzeugt klassische
Alarm-Fatigue, sodass User den Hinweis im einen Fall, in dem er
wirklich kritisch wäre, übersehen. Stattdessen wird der Hinweis in
**zwei** Fällen ausgegeben:

1. **Verdachtsfall** (Default-Pfad): wenn der UTF-8-Decoder beim Lesen
   einen `MalformedInputException`/`UnmappableCharacterException`
   wirft, hängt der Importer den Hinweis an die Fehlermeldung an
   („`Decoding error at byte offset N. Hint: input file has no BOM and
   does not parse as UTF-8 — set --encoding explicitly.`"). Das
   schließt genau den Fall ab, in dem ein still falsch dekodierter
   ISO-8859-1-Import sonst als „funktioniert" durchgewunken würde.
2. **`--verbose`-Pfad**: wenn der User explizit `--verbose` setzt,
   wird die Hinweis-Zeile als reines Info-Signal beim Reader-Start
   protokolliert, unabhängig vom Decoding-Erfolg. Das ist der Pfad
   für Debugging und Workflow-Audits.

Im Default-Pfad ohne `--verbose` und ohne Decoding-Fehler bleibt der
BOM-lose UTF-8-Import damit komplett still. Unter `--quiet` ist der
Hinweis ebenfalls unterdrückt — aber das war schon der bisherige
Vertrag und ändert sich nicht. Für `ImportInput.Stdin` gilt zusätzlich:
auch unter `--verbose` wird der Hinweis unterdrückt, weil Stdin-Pipes
keinen sinnvollen BOM-Hinweis-Workflow haben.

### 6.10 Getrennte Reader/Writer-Registries

Wir behalten `DataReaderRegistry` aus 0.3.0 unverändert und legen
`DataWriterRegistry` als zweite, unabhängige Registry an. Alternative wäre
eine zusammengelegte `DataAdapterRegistry` mit beiden Maps — aber:

- die existierenden Tests (~30 Stück) referenzieren `DataReaderRegistry`
  direkt und müssten alle umgeschrieben werden
- die Trennung macht die Bootstrap-Reihenfolge in `Main.kt` nicht
  komplizierter — `*Driver.register()` ruft beide Registries
- Symmetrie zur `formats`-Faktorisierung (`DataChunkReaderFactory` und
  `DataChunkWriterFactory` sind ebenfalls getrennt)

### 6.11 Exit-Code-Matrix für `data import`

| Code | Trigger | Beispiel-Meldung |
|---|---|---|
| `0` | Erfolgreicher Import, alle Tabellen geschrieben | `Imported 3 tables (12 345 inserted, 1 200 updated, 0 failed) in 4.2 s; reseeded 2 sequences` |
| `1` | Unerwarteter interner Fehler / ungefangene Exception | `Unexpected error while importing table 'orders'` |
| `2` | CLI-Fehler, ungültige Optionen, fehlendes `--target` oder `--table`, `--trigger-mode disable` auf MySQL/SQLite, `--disable-fk-checks` auf PG, unbekannte Endung ohne `--format`, `--truncate` zusammen mit explizitem `--on-conflict`, PK-lose Tabelle mit `--on-conflict update` | `--trigger-mode disable is not supported for dialect MYSQL` |
| `3` | Header-/Target-Schema-Mismatch, `trigger-mode strict` mit gefundenen Triggern oder Schema-Validierung gegen `--schema <path>` fehlgeschlagen | `column 'userId' has no exact match` |
| `4` | Connection-Fehler (HikariCP, fehlendes Target) | `Connection refused: localhost:5432` |
| `5` | Import-Fehler während Streaming **bei `--on-error abort`** (SQL-Constraint-Verletzung, IO-Exception), oder Post-Chunk-Finalisierungsfehler (Sequence-Reseeding bzw. Trigger-Reenable nach erfolgreichem Schreiben gescheitert). Unter `--on-error skip\|log` führen reine Chunk-Schreibfehler NICHT zu Exit 5; sie landen in `rowsFailed`/`chunkFailures` und der Lauf endet regulär mit Exit 0 (siehe §6.5 und R4). | `Failed to import table 'orders' at chunk 47: duplicate key value violates unique constraint` |
| `7` | Konfigurationsfehler (URL-Parser, `.d-migrate.yaml`, fehlende ENV-Variable) | wie 0.3.0 |

Code 3 ist in 0.3.0 für `data export` nicht relevant gewesen, kommt hier
als allgemeiner **Pre-Flight-Verstoß vor dem ersten Insert** zurück
(analog zu `schema validate` aus 0.1.0).
Bei Exit 5 nach bereits geschriebenen Daten (z.B. Sequence-Reseeding oder
Trigger-Reenable scheitert) muss die Fehlermeldung explizit sagen, dass die
Daten schon committed sind und ein manueller Post-Import-Fix nötig ist.

### 6.12 Inkrementeller Pfad (LF-013)

LF-013 ist in 0.4.0 **explizit zweigeteilt**:

- **Export-Seite**: bekommt funktionale Inkrement-Flags `--since-column`
  und `--since`, die einen WHERE-Filter ans SELECT anhängen.
- **Import-Seite**: bekommt **keine** Inkrement-Flags. Wer einen
  Delta-Datensatz idempotent einspielen will, benutzt `--on-conflict update`
  — das ist mechanisch identisch zu „inkrementeller Import" und verhält
  sich auch dann korrekt, wenn der Anwender denselben Delta-Export zweimal
  einspielt.

Diese Asymmetrie ist absichtlich (F44): „inkrementell" ist auf der Lese-Seite
ein **Filter-Konzept** (welche Rows extrahiere ich aus der DB?) und auf der
Schreib-Seite ein **Konflikt-Auflösungs-Konzept** (was passiert mit Rows,
die schon existieren?). Ein gemeinsames `--incremental` auf beiden Seiten
würde diese zwei Konzepte falsch verschmelzen.

#### 6.12.1 Export-Seite

```bash
d-migrate data export --source local_pg --format json \
    --tables orders --since-column updated_at --since "2026-01-01"
```

Intern:

1. `--since-column` strikt gegen das exakte 0.3.0-Identifier-Pattern
   validieren (L8): `^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)?$`
   — identisch zur `DataExportHelpers.TABLE_IDENTIFIER_PATTERN`-Konstante
   aus 0.3.0. Erlaubt sind also `name` oder `schema.column`, jeweils
   ohne SQL-Quotes; Whitespace, Doublequotes oder Sonderzeichen führen
   zu Exit 2. Der `DataImportCommand` und der erweiterte
   `DataExportCommand` rufen für diese Validierung dieselbe Konstante,
   damit Export- und Import-Pfad nie auseinanderdriften können.
2. JDBC-Metadaten der Marker-Spalte lesen und `--since` einmal in den
   passenden Java-Typ konvertieren (`LocalDate`, `LocalDateTime`,
   `OffsetDateTime`, `Long`, `BigDecimal`, sonst `String`)
3. `DataFilter.ParameterizedClause("\"<col>\" >= ?", listOf(typedSince))`
4. Reader baut: `SELECT ... FROM ... WHERE "updated_at" >= ?`
5. Wenn der Anwender zusätzlich `--filter` setzt, werden beide via
   `DataFilter.Compound([WhereClause(filter), ParameterizedClause(...)])`
   kombiniert (der in Phase A erweiterte `AbstractJdbcDataReader` trägt das
   dann für `ParameterizedClause` und `WhereClause`, inklusive korrekter
   Parameter-Positionsbindung im zusammengesetzten WHERE)
6. **M-R5 — Literale `?` in `WhereClause` + `ParameterizedClause`
   sind verboten**: Der rohe `--filter`-String kann fachlich ein literales
   `?`-Zeichen enthalten (z.B. `--filter "name LIKE 'Order?%'"` für einen
   LIKE-Ausdruck mit Fragezeichen im Pattern, oder ein String-Vergleich
   `--filter "note = 'really?'"`). Bei naivem Durchreichen in ein
   `PreparedStatement.prepareStatement(...)` würde dieses `?` vom
   JDBC-Parser als **zusätzlicher Bind-Parameter** interpretiert und mit
   dem aus `ParameterizedClause` stammenden `--since`-Wert positional
   durcheinandergebracht — im besten Fall ein Binding-Fehler, im
   schlimmsten Fall eine still falsche WHERE-Klausel.
   Der Runner-Pfad für LF-013 prüft deshalb explizit: wenn der Export
   mit `--since-column`/`--since` (also mit einem `ParameterizedClause`
   im Compound) läuft UND der Nutzer zusätzlich `--filter` gesetzt hat,
   darf der `--filter`-String **kein literales `?`-Zeichen** enthalten.
   Die Prüfung ist als simpler String-Scan (`'?' in filterSql`) im
   CLI-Pre-Flight spezifiziert; zusätzlich erzwingt der aktuelle
   `AbstractJdbcDataReader` dieselbe Regel defensiv auf Reader-Ebene,
   damit auch programmatic Caller nicht in stille Bind-Drift laufen.
   Verletzung → **Exit 2** im CLI-Pfad bzw. `IllegalArgumentException`
   im direkten Reader-Pfad, jeweils mit klarer Meldung.
   `--filter must not contain literal '?' when combined with --since
   (parameterized query); use a rewritten predicate or escape the
   literal differently`. Ohne `--since` (reiner `--filter`-Pfad aus
   0.3.0) bleibt das Verhalten unverändert, weil es dort kein
   `ParameterizedClause` gibt und das `?` bestenfalls den JDBC-Parser
   verwirrt, aber nicht mit einem Bind-Wert kollidiert.
   Phase A Schritt 5 nimmt dafür einen expliziten Test auf, der
   `Compound([WhereClause("a = 'x?'"), ParameterizedClause("b >= ?",
   [1])])` baut und asserted, dass der Runner mit Exit 2 abbricht.

`--since-column` und `--since` sind nur zusammen gültig; fehlt einer der
beiden Werte, endet der Export mit Exit 2. Der aktuelle Runner erzwingt
dies bereits im CLI-Preflight, bevor der Pool geöffnet oder der Export
gestartet wird.

#### 6.12.2 Import-Seite: idempotenter UPSERT statt eigenem Modus

```bash
# Vorher exportierter Delta-Stand (oder der letzte Voll-Export):
d-migrate data import --target local_pg --source orders.delta.json \
    --format json --table orders --on-conflict update
```

Inkrementelle Imports brauchen **idempotente Schreiblogik**, sonst entstehen
Duplikate bei der zweiten Ausführung desselben Deltas. Der einzige
unterstützte Pfad in 0.4.0 ist `--on-conflict update`, der pro Dialekt einen
UPSERT baut:

| Dialect | UPSERT-Syntax |
|---|---|
| PG | `INSERT INTO ... VALUES (...) ON CONFLICT ("pk_col1", "pk_col2") DO UPDATE SET col1 = EXCLUDED.col1, ...` |
| MySQL | `INSERT INTO ... VALUES (...) ON DUPLICATE KEY UPDATE col1 = VALUES(col1), ...` |
| SQLite | `INSERT INTO ... VALUES (...) ON CONFLICT(pk_col1, pk_col2) DO UPDATE SET col1 = excluded.col1, ...` |

Die Primärschlüssel-Spalten werden über JDBC `DatabaseMetaData.getPrimaryKeys()`
erkannt, ähnlich wie der Spalten-Hint in §6.4. Composite-PKs sind unterstützt;
PK-lose Tabellen können **nicht** mit `--on-conflict update` importiert
werden → Exit 2 mit klarer Meldung.

**M-R4 — PK-Spalten strikt nach `KEY_SEQ`**: `DatabaseMetaData.getPrimaryKeys(
catalog, schema, table)` liefert die PK-Spalten **nicht** garantiert in
PK-Definitionsreihenfolge — die Reihenfolge im `ResultSet` hängt vom
Treiber ab, und die autoritative Reihenfolge kommt aus der Spalte
`KEY_SEQ` (1-basierter Ordinalwert innerhalb der PK). Der Writer MUSS
die zurückgelieferten Rows per `ORDER BY KEY_SEQ` (bzw. client-seitig
per `sortedBy { rs.getShort("KEY_SEQ") }`) explizit sortieren und diese
Reihenfolge sowohl für den `ON CONFLICT (...)`-Target (PG/SQLite) als
auch für den MySQL-Pfad als „canonical PK order" für Phase-F-
Assertions benutzen. Der PG-`ON CONFLICT`-Target ist zwar auf dem
Matching-Pfad order-unabhängig (der Constraint wird per OID
nachgeschlagen), aber ein stabil sortierter Spalten-Block verhindert
Test-Flakiness zwischen Treiber-Versionen und macht die Phase-C/F-
Mixed-Case-Tests (F5) deterministisch vergleichbar. Phase C nimmt dafür
einen expliziten Test mit einer 3-spaltigen Composite-PK in eine
Fixture auf, in der die `KEY_SEQ`-Reihenfolge nicht der alphabetischen
Spaltenreihenfolge entspricht.

Für schema-qualifizierte Namen (`public.orders`) muss der Writer/Importer
dabei Schema und Tabellenname vor dem `getPrimaryKeys(catalog, schema, table)`-
Aufruf sauber trennen. Phase C/F enthält dafür explizit einen PG-Test mit
nicht-`public`-Schema und Composite-PK.

**F5 — Case-Handling pro Dialekt**: `getPrimaryKeys(catalog, schema, table)`
ist auf den Identifier-Casing-Konventionen des jeweiligen Dialekts
empfindlich, und ein naives Durchreichen des CLI-Strings würde auf PG
oder strikt-konfigurierten MySQL-Instanzen still ein leeres Result
liefern — die Tabelle würde fälschlich als „PK-los" klassifiziert und
mit Exit 2 abgewiesen, obwohl sie tatsächlich einen PK hat. Der
PK-Lookup MUSS deshalb pro Dialekt dieselbe Identifier-Normalisierung
verwenden, die auch §6.4 für `SELECT * FROM "schema"."table" WHERE 1=0`
nutzt — sonst driften die beiden Pfade auseinander, und ein Import
findet die Spalten, aber nicht den PK.

| Dialect | Normalisierung für `getPrimaryKeys` |
|---|---|
| PostgreSQL | unquoted Identifier werden zu lowercase gefoldet (PG-Standard); der Lookup nutzt deshalb den lowercase-Namen, ES SEI DENN der CLI-User hat den Namen in 0.3.0-Identifier-Notation `"Name"` quotiert übergeben (für 0.4.0 unsupported, siehe §3.7.1 — also: lowercase). Schema-Default ist `public`, wenn nicht qualifiziert. |
| MySQL | hängt an `lower_case_table_names`: bei `1` (Default auf macOS/Windows) lowercase, bei `0` (Linux strikt) der Roh-Name. Der MySQL-Writer liest `lower_case_table_names` einmal beim ersten `openTable(...)` und cached das Setting in der Session. Schema = MySQL-Catalog (`USE`-DB). |
| SQLite | Identifier sind case-insensitive in Lookups; der Roh-Name wird unverändert übergeben und SQLite matcht ihn in `sqlite_master` selbst case-insensitiv. Kein Schema-Konzept. |

Diese Normalisierung MUSS in einer einzigen Helfer-Funktion pro Writer
zentriert sein (`SqlIdentifierNormalizer.forMetadata(table)` o.ä.) und
sowohl in §6.4 (`SELECT * WHERE 1=0`) als auch in §6.12.2
(`getPrimaryKeys`) als auch in §6.6 (Sequence-Lookup) **identisch**
aufgerufen werden. Phase C nimmt für jeden Dialekt einen expliziten
Mixed-Case-Test in die L2-Pflichttests auf (z.B. CLI-Eingabe `Users`,
PG-Tabelle `users`), der absichert, dass alle drei Pfade dieselbe
Normalisierung sehen.

Wichtiger Dialekt-Unterschied: PostgreSQL/SQLite binden den Konflikt-Target
explizit an den erkannten PK; MySQLs `ON DUPLICATE KEY UPDATE` feuert dagegen
auf JEDEM verletzten Unique-Index. Diese Asymmetrie wird dokumentiert, nicht
wegabstrahiert.

MySQL-Hinweis: das gezeigte `VALUES(col)`-Idiom ist für 0.4.0 noch
ausreichend dokumentiert, wird aber als Follow-up beobachtet, weil neuere
MySQL-Versionen Alias-basiertes Update bevorzugen.

`WriteResult` normalisiert dabei den Schreibeffekt pro Chunk auf
`rowsInserted`, `rowsUpdated` und `rowsSkipped`, auch wenn einzelne
JDBC-Treiber unterschiedliche Affected-Row-Konventionen haben. Für
MySQL ist das verbindlich über die Batch-Rückgabewerte definiert:
`1 -> inserted`, `2 -> updated`, `0 -> updated` (idempotenter No-Op auf
bestehender Row) im `ON DUPLICATE KEY UPDATE`-Pfad; im `INSERT IGNORE`-
Pfad (`--on-conflict skip`) entsprechend `1 -> inserted`, `0 ->
skipped` (Row ignoriert).
**R10 / M-R8 — `SUCCESS_NO_INFO (-2)`-Fallback**: Liefert der Treiber
stattdessen `Statement.SUCCESS_NO_INFO`, fällt die saubere Trennung für
diesen Batch weg — in beiden Pfaden (UPSERT und Skip). 0.4.0 zählt den
Batch dann **NICHT** konservativ als `rowsInserted += chunk.size` (das
würde Delta-Imports per `--on-conflict update` strukturell als 100 %
Inserts maskieren und im Report einen falschen „5 000 inserted"-
Eindruck erzeugen, obwohl real „5 000 updated" passierte — und analog
einen `--on-conflict skip`-Lauf, der in Wahrheit alle Rows wegen
bestehender PKs verworfen hat, als „5 000 inserted" ausweisen).
Stattdessen wandert die Batch-Größe in das neue `rowsUnknown`-Feld auf
`WriteResult`/`TableImportSummary`. Die Summary in §6.15 zeigt
`rowsUnknown` als getrennten Counter und hängt einen pfadspezifischen
Hinweis an:
- im UPSERT-Pfad: „inserts/updates not exactly distinguishable for N
  rows (MySQL SUCCESS_NO_INFO)"
- im Skip-Pfad (`INSERT IGNORE`): „inserts/skips not exactly
  distinguishable for N rows (MySQL SUCCESS_NO_INFO)"

So bleiben die Inserted/Updated/Skipped-Counter ehrlich, und die
Unschärfe ist explizit ablesbar — sowohl im Klartext-Report als auch im
JSON-Output. Der `--on-conflict abort`-Pfad ist bewusst ausgenommen:
dort kann es keine Mehrdeutigkeit geben, weil jeder Konflikt den Batch
abbrechen würde; dort zählt `SUCCESS_NO_INFO` regulär als
`rowsInserted += batchSize`.

**Bewusst nicht enthalten** (F44):

- Kein `--incremental`-Flag auf `data import`. Der User soll explizit
  `--on-conflict update` setzen — das beschreibt das tatsächliche
  Verhalten und ist nicht durch einen anders klingenden Synonym-Flag
  verschleiert.
- Kein `--since-column` auf `data import`. Die Idee aus der ersten
  Plan-Fassung, den Marker als „informative" Report-Information zu
  protokollieren, war eine Brückenkonstruktion ohne fachlichen Mehrwert
  und wurde gestrichen.
- Keine Auswertung des „höchsten Markers im File" beim Import. Wer den
  letzten exportierten Marker tracken will, macht das auf der Export-Seite
  (z.B. via Shell-Variable mit Verweis auf den Datei-Namen).

#### 6.12.3 Was 0.4.0 NICHT macht

- Kein State-File mit „letzter exportierter Marker" — User trackt das
  selbst über Shell-Variablen oder Pipeline-State. State-Files sind
  Checkpoint-Territory (0.9.0).
- Keine Auto-Discovery von `updated_at`/`modified_at`/`changed_at`-Spalten —
  Spalte muss explizit angegeben werden (kommt mit `SchemaReader` 0.6.0)
- Keine Soft-Delete-Behandlung (`deleted_at`)
- Keine Tombstone-Synchronisation (Löschen über Inkrement)

### 6.13 Schreibperformance: Batch-Insert ohne COPY

Für 0.4.0 reicht JDBC `addBatch()` + `executeBatch()`. Erwartete Größenordnung
auf einer typischen lokalen PG-Instanz:

| Dialect | rows/s (Single-Thread, Batch=1000) |
|---|---|
| PostgreSQL | ~30 000 |
| MySQL | ~25 000 (mit `rewriteBatchedStatements=true`) |
| SQLite | ~50 000 (in-memory), ~10 000 (file mit WAL) |

Das deckt die typischen Use-Cases (Daten-Migration zwischen DBs,
Test-Daten-Import) komplett ab. **PostgreSQL `COPY FROM` ist ~10× schneller**
und kommt in 1.0.0 als optionaler Performance-Pfad — bis dahin reichen die
~30k rows/s.

Die Zahlen sind ausdrücklich **Schätzwerte zur Größenordnung**, keine
vertraglichen Acceptance Criteria. Ein späterer Review darf daraus keinen
Bug allein aus Abweichung gegen diese Tabelle ableiten.

### 6.14 `--truncate` als nicht-atomarer Pre-Step

```bash
d-migrate data import --target local_pg --source orders.json --format json \
    --table orders --truncate
```

`--truncate` läuft als **destruktiver Pre-Step** vor dem eigentlichen
Import. Für Single-Table-Imports geschieht das auf derselben
Session-Connection, die anschließend auch für den Tabellen-Import verwendet
wird: `TRUNCATE TABLE` (PG), `DELETE FROM` (MySQL **und** SQLite) in einer
eigenen, sofort committeten Transaktion, danach der reguläre chunkbasierte
Import-Pfad aus §6.5.

**R3 — Signaling-Vertrag pro Tabelle**: Direkt nachdem der Pre-Step für
**genau eine** Tabelle erfolgreich gelaufen ist (für PG erfolgreich, für
MySQL/SQLite nach `DELETE FROM`), ruft der `StreamingImporter` auf der
zugehörigen `TableImportSession` `markTruncatePerformed()` auf — und
zwar im `OPEN`-Zustand vor dem ersten `write(...)`. Damit landet der
Truncate-Zustand bewusst **per Session** am Writer und nicht über
`ImportOptions` als globaler CLI-Flag; bei Multi-Tabellen-Imports
(§6.8.3) wird `markTruncatePerformed()` nur für die Tabellen
aufgerufen, deren destruktiver Pre-Step im umgekehrt-topologischen
Vorlauf tatsächlich gelaufen ist. Der Writer wertet das Signal in den
H5/F4-Sonderpfaden in `finishTable()` aus (§3.3, §3.4, §6.6 Schritt 3).

**H5 — MySQL-Truncate-Pfad ist `DELETE FROM`, immer**: 0.4.0 versucht auf
MySQL bewusst KEIN `TRUNCATE TABLE` zuerst und kein „auf 1701 fallback"-
Probieren. Das hat zwei Gründe:

1. `TRUNCATE TABLE` scheitert auf FK-referenzierten MySQL-Tabellen mit
   Fehlercode `1701` und ist ohne FK-Disable ohnehin unbrauchbar
2. Ein „erst TRUNCATE versuchen, sonst DELETE" wäre eine versteckte
   Verhaltens-Variante, die die AUTO_INCREMENT-Semantik je nach Tabelle
   anders aussehen ließe — unangenehmes Debugging

Konsequenz: nach `DELETE FROM` ist der `AUTO_INCREMENT`-Zähler bei MySQL
ausdrücklich NICHT zurückgesetzt. Der MySQL-Writer hat dafür den
H5-Spezialfall in §3.3 implementiert (`AUTO_INCREMENT = 1` nach
truncate-mit-Leerzustand). **F4 — analog für SQLite**: nach
`DELETE FROM` bleibt der `sqlite_sequence`-Eintrag einer
`INTEGER PRIMARY KEY AUTOINCREMENT`-Tabelle ebenfalls stehen; der
SQLite-Writer hat dafür einen parallelen F4-Spezialfall in §3.4
(`DELETE FROM sqlite_sequence WHERE name = ?` nach
truncate-mit-Leerzustand). Der Effekt für den User ist damit auf allen
drei Dialekten derselbe wie bei PG-`TRUNCATE` plus §6.6-Reseed: nach
`--truncate` ohne neue Daten startet der nächste manuelle Insert wieder
bei `1`. Mit neuen Daten startet er bei `MAX(col) + 1`.

Für Multi-Table-Imports mit `--schema` und FK-Abhängigkeiten wird der
Pre-Step in einen separaten Vorlauf gezogen: zuerst alle destruktiven
Operationen in umgekehrter topologischer Reihenfolge, danach der eigentliche
Import in normaler topologischer Reihenfolge. Session-lokale FK-Schalter
werden dabei jeweils auf den Connections gesetzt, die den Pre-Step bzw. den
späteren Import tatsächlich ausführen.

> **Wichtig — Nicht-Atomarität (F41)**: Wenn der nachfolgende Import nach
> dem TRUNCATE scheitert (Connection-Abbruch, Constraint-Verletzung,
> Disk-Voll, …), bleibt die Tabelle im **leeren** Zustand zurück. Die alten
> Daten sind weg. Das ist die direkte Konsequenz aus dem
> Chunk-Transaktionsmodell (§6.5) plus der Tatsache, dass die meisten
> Dialekte TRUNCATE in einer impliziten DDL-Transaktion ausführen, die wir
> nicht über mehrere Chunks hinweg offen halten können.
>
> Wer **atomares** Replace braucht ("entweder die ganze Tabelle ist neu
> oder die alte ist unverändert"), wartet auf das geplante
> `--replace-table` in einem späteren Milestone (§11). Das wird intern als
> Single-Transaction `DELETE FROM` plus chunkweisen Import in **derselben**
> Connection implementiert — komplexer und mit eigenen Locking-
> Konsequenzen, deshalb nicht in 0.4.0.

`--truncate` ist eine destruktive Operation und braucht User-Bestätigung im
interaktiven Modus (per `--yes` oder `D_MIGRATE_ASSUME_YES=true` automatisch
akzeptiert, analog zu cli-spec.md §8.2).

`--truncate` ist nicht gleichbedeutend mit `--on-conflict update`:

- `--truncate` → Tabelle wird VOR dem Import geleert → reiner Insert-Modus
- `--on-conflict update` → UPSERT, behält bestehende Zeilen
- `--on-conflict skip` → Insert mit `ON CONFLICT DO NOTHING`
- `--on-conflict abort` (Default) → Erste Constraint-Verletzung killt den Chunk

Kombinationen:

| `--truncate` | `--on-conflict` | Verhalten |
|---|---|---|
| an | explizit gesetzt (`abort`/`skip`/`update`) | **Exit 2** — gegenseitig ausschließend |
| aus | abort (Default) | klassischer Insert, scheitert bei Constraint-Verletzung |
| aus | skip | Insert mit Skip-on-Conflict |
| aus | update | UPSERT |

Phase-E-Testfall dafür explizit fest einplanen:

- `--truncate` ohne `--on-conflict` → erlaubt (impliziter Default `abort`)
- `--truncate --on-conflict abort` → Exit 2 (expliziter Wert)

PostgreSQL benutzt dabei bewusst **kein** `RESTART IDENTITY`; der Plan
bleibt bei plain `TRUNCATE` plus anschließendem explizitem Reseeding aus
§6.6, damit die Generator-Nachführung an genau einer Stelle definiert ist.

### 6.15 Reporting

Der `ImportResult` wird vom CLI als ProgressSummary (analog `data export`)
auf stderr ausgegeben:

```
Imported 3 tables (12 345 inserted, 1 200 updated, 1 245 skipped, 0 failed) in 4.2 s
  customers: 5 000 inserted, 0 updated, 0 skipped, 0 failed
  orders:    6 000 inserted, 1 200 updated, 1 245 skipped, 0 failed
  products:    145 inserted, 0 updated, 0 skipped, 0 failed
Chunk failures logged: 1
  orders: chunk 47 skipped after schema mismatch in input row
Sequence adjustments: 2
  customers.id  → 5001
  products.id   → 146
Trigger mode:    fire
```

**R10 — `rowsUnknown` im MySQL-Fallback-Pfad**: Wenn auch nur ein Chunk
auf einen `Statement.SUCCESS_NO_INFO`-Treiber gestoßen ist, taucht
`rowsUnknown` als zusätzlicher Counter in der Summary-Zeile UND in der
pro-Tabelle-Zeile auf, plus eine separate Hinweiszeile am Ende.
**L12 — Headline-Rauschen vermeiden**: `rowsUnknown` wird **nur dann**
in der Headline-Summary-Zeile und in den pro-Tabelle-Zeilen
ausgegeben, wenn der aggregierte Wert `> 0` ist. Wäre der Counter
immer präsent, würde jeder Standard-Import (PG, SQLite, und der
Normalpfad auf MySQL mit per-Row-Counts) die irrelevante Zusatzspalte
„0 unknown" mitschleppen und das klassische Report-Layout unnötig
verbreitern. Der Counter bleibt im strukturierten `ImportResult`-
Objekt und im JSON-Output-Pfad natürlich immer sichtbar (auch als
`0`), damit maschinenlesbare Consumer nicht raten müssen, ob das Feld
existiert. Die unten gezeigte Beispiel-Ausgabe mit „5 000 unknown"
entspricht also dem Fallback-Pfad; im Regelfall sieht der User das
Feld gar nicht:

```
Imported 1 table (0 inserted, 0 updated, 0 skipped, 5 000 unknown, 0 failed) in 1.4 s
  events:  0 inserted, 0 updated, 0 skipped, 5 000 unknown, 0 failed
Note: 5 000 rows reported as unknown — MySQL JDBC driver returned
  SUCCESS_NO_INFO for these batches; inserts and updates are not exactly
  distinguishable. Consider --chunk-size 1 for forensic accounting, or
  upgrade the driver.
```

Der Hinweiszeilen-Text hängt dabei vom auslösenden `--on-conflict`-Pfad
ab (M-R8): der UPSERT-Fallback zeigt „inserts and updates are not
exactly distinguishable", der `INSERT IGNORE`-/Skip-Fallback zeigt
„inserts and skips are not exactly distinguishable". Werden in einem
Multi-Table-Lauf beide Fallback-Pfade getroffen, hängt der Report
beide Hinweise an.

`rowsUnknown` ist auch im JSON-Output (`--output-format json`) als
eigenes Feld auf `TableImportSummary` und `ImportResult` sichtbar, damit
Phase F den Pfad gezielt asserten kann. Im JSON-Output wird das Feld
immer serialisiert — auch als `0` — damit maschinenlesbare Consumer
nicht raten müssen, ob der Key existiert (L12 betrifft nur den
Klartext-Report).

**H4 / F3 — Reporting bei `failedFinish`**: wenn eine Tabelle mit
`TableImportSummary.failedFinish != null` zurückkommt (Daten committed,
aber Trigger-Reenable gescheitert), rendert das CLI einen zusätzlichen
Block aus dem strukturierten Feld — kein Rückgriff auf String-
Fehlermeldungen:

```
Imported 1 table (5 000 inserted, 0 updated, 0 skipped, 0 failed) in 1.8 s
  events:  5 000 inserted, 0 updated, 0 skipped, 0 failed
Sequence adjustments: 1
  events.id → 5001
PARTIAL FAILURE on 'events': data committed, sequence adjusted, but
  trigger re-enable failed:
    org.postgresql.util.PSQLException: ALTER TABLE … ENABLE TRIGGER USER
    rejected by managed-PG policy
  → manual fix required: re-enable triggers on table 'events'.
```

Wenn zusätzlich der zweite, close-interne Reenable-Versuch geworfen hat
(F3 / M-R7 — `FailedFinishInfo.closeCauseClass != null`), hängt der
Reporting-Pfad einen zweiten Block direkt an den ersten an, ohne dass
`close()` selbst geworfen hätte:

```
PARTIAL FAILURE on 'events': data committed, sequence adjusted, but
  trigger re-enable failed:
    org.postgresql.util.PSQLException: ALTER TABLE … ENABLE TRIGGER USER
    rejected by managed-PG policy
  cleanup retry on close() also failed:
    org.postgresql.util.PSQLException: connection has been closed
  → manual fix required: re-enable triggers on table 'events'.
```

`failedFinish` ist auch im JSON-Output-Pfad (`--output-format json`)
strukturiert sichtbar — inklusive aller sechs Felder (`causeMessage`,
`causeClass`, `causeStack`, und ihre optionalen `closeCause*`-Pendants).
Das ist die Bedingung, die Phase F gezielt asserten kann (kein Regex auf
einer stderr-Zeile; Phase F liest den JSON-Output und asserted z.B.
`result.tables[0].failedFinish.causeClass ==
"org.postgresql.util.PSQLException"`).

`--quiet` unterdrückt die Summary (wie in 0.3.0); `--no-progress` ebenfalls.
`PARTIAL FAILURE`-Blöcke bleiben auch unter `--quiet` erhalten, weil sie
einen manuellen Folge-Eingriff erfordern. Für eine maschinenlesbare
Variante: `--output-format json` am Root-Command liefert das `ImportResult`
als JSON auf stdout.

---

## 7. Spec- und Doku-Pflege

| Datei | Änderung |
|---|---|
| `docs/cli-spec.md` §6.2 `data import` | REWRITE des bestehenden Kurzblocks: vollständig analog zu 0.3.0-`data export`, alle Flags aus §3.7.1, veraltetes `--resume` entfernen, Output-Resolver (Stdin/SingleFile/Directory), Exit-Code-Matrix 0/1/2/3/4/5/7, Beispiele mit Round-Trip und UPSERT-Import |
| `docs/cli-spec.md` §6.2 `data export` | LF-013-Block ergänzen: `--since-column`, `--since` mit Beispielen |
| `docs/connection-config-spec.md` | Neue Sektion „Status pro Feature" anlegen und die bisher nur im 0.3.0-Plan referenzierte Tabelle dorthin migrieren; dabei `default_source` als aktiv für `data export` und `default_target` als aktiv für `data import` markieren |
| `docs/design-import-sequences-triggers.md` | Status `Draft` → `Approved`; offene Entscheidungen aus §10 mit den Antworten aus §6.6/§6.7/§6.8 dieses Plans befüllen |
| `docs/roadmap.md` | Milestone 0.4.0 als „in Arbeit" markieren bei Phase A; bei jedem abgeschlossenen Phase-Schritt aktualisieren |
| `CHANGELOG.md` | `[Unreleased]`-Block schon vorhanden (im 0.3.0-Post-Release-Bump angelegt); pro Phase Einträge nachziehen, inkl. explizitem L6-Hinweis: `database.default_source` was previously parsed but ignored — in 0.4.0 it is honored as a fallback for `--source` (kein „A → B"-Bruch, aber „vorher ignoriert → jetzt aktiv") |
| `docs/cli-spec.md` Help-Texte | Explizit erwähnen: bei JSON/YAML werden fehlende bekannte Schlüssel als `NULL` materialisiert; bei MySQL ist Header-Matching bewusst case-sensitiv trotz DB-seitiger Toleranz; `--truncate` + explizites `--on-conflict abort` wird als missverständliche Kombination abgelehnt; **JSON/YAML First-Row-Schema (H3)**: das Feldset der ersten Row ist für die ganze Datei autoritativ — wer Rows mit unterschiedlichen Optional-Feldsets importieren will, muss vorab eine kanonische Header-Row mit allen Feldern (Werte dürfen `null` sein) vorne anhängen; **BOOLEAN (L3)**: nur `true`/`false` (case-insensitive) sind zulässige Quellwerte, `0`/`1`/`yes`/`no`/`y`/`n` werden NICHT still interpretiert und führen zu einem Typfehler über `--on-error`; **Sequence-Race (L4)**: parallele Writer auf der Zieltabelle während des Imports werden NICHT serialisiert — wer Identity-Reseeding will, muss konkurrierende Schreibsessions selbst pausieren |

---

## 8. Coverage-Ziele

Pro Modul gilt der Projektstandard **90%**. Für `adapters:driving:cli` übernehmen
wir die 0.3.0-Ausnahme bewusst NICHT; der Runner-/Helper-Split macht die
Pfadabdeckung auch für Import und LF-013 direkt testbar.

| Modul | Ziel | Anmerkung |
|---|---|---|
| `hexagon:core` | 90% | `DataFilter.ParameterizedClause` direkt testen |
| `hexagon:ports` / `adapters:driven:driver-common` | 90% | DataWriter-Vertrag inline mit Stubs; `finishTable()`/`close()`-Pfad getrennt testen |
| `adapters:driven:driver-postgresql` | 90% via Testcontainers | `PostgresDataWriter` + `PostgresSchemaSync` Branch-Coverage |
| `adapters:driven:driver-mysql` | 90% via Testcontainers | dito; `disable`-Trigger-Pfad als Negativ-Test |
| `adapters:driven:driver-sqlite` | 90% inline | `sqlite_sequence` Reseeding mit File-DB |
| `adapters:driven:streaming` | 90% | `StreamingImporter` mit Fake Reader/Writer |
| `adapters:driven:formats` | 90% | je Reader Golden-Master-Round-Trip mit Phase-D-Writern aus 0.3.0; `EncodingDetector` mit allen 5 BOM-Varianten plus Fallback |
| `adapters:driving:cli` | 90% | `DataImportCommand`- und LF-013-Pfade direkt über Runner/Helper testen |

---

## 9. Verifikation

Vor dem 0.4.0-Release müssen die folgenden Round-Trip-Tests grün sein
(Phase F + zusätzliche Verifikations-Suite):

1. **Voll-Roundtrip pro Format pro Dialect** (3×3 = 9 Permutationen):
   `data export → data import → SELECT-Vergleich`
   - 1000 Rows, die in 0.3.0 bereits unterstützten neutralen Typen
     (Roadmap-Erweiterungen aus späteren Milestones sind hierfür kein
     Release-Gate von 0.4.0)
   - **H3-Pflichtfall**: die erste Row der Test-Fixture enthält `NULL` in
     mindestens einer nullable Spalte, idealerweise `NULL` in **allen**
     nullable Spalten. Das schützt aktiv die First-Row-Schema-Garantie
     aus §3.5.1: würde der 0.3.0-Writer das `null`-Feld weglassen, würde
     der Reader die Spalte nicht in den Header aufnehmen und der
     Round-Trip kollabiert mit `ImportSchemaMismatchException`. Der Test
     muss diesen Pfad aktiv prüfen, nicht zufällig durch eine
     „erste Row hat überall Werte"-Fixture umgehen.
   - **H3 + M7-Pflichtfall**: die Fixture vermeidet entweder die
     `""`-vs-`NULL`-Kollision in nullable Text-Spalten, oder sie setzt
     beim CSV-Pfad einen expliziten `--csv-null-string NULL` (siehe M7
     unten). Andernfalls ist das Daten-Äquivalenz-Gate für CSV mit dem
     Default `csvNullString = ""` strukturell nicht erfüllbar, ohne dass
     der Test still falsch grün wird.
   - Pflicht-Gate: Daten-Äquivalenz nach Re-Parsing/SELECT-Vergleich muss
     identisch sein
   - Zusatzsignal: SHA-256-Hash der Original- und Re-Export-Datei SOLL
     identisch sein; Abweichungen sind ein Writer-/Stabilitäts-Hinweis,
     aber nicht automatisch ein 0.4.0-Blocker
2. **Inkrementeller Round-Trip**:
   - Initial-Export (10 000 Rows)
   - 100 Updates + 50 Inserts in der Source
   - Delta-Export mit `--since-column updated_at --since "<initial-ts>"`
     liefert genau 150 Rows
   - Delta-Import mit `--on-conflict update` ins Ziel
   - SELECT-Vergleich Source vs. Ziel: identisch
3. **Sequence-Reseeding-Verifikation**:
   - Tabelle mit 100 Rows, höchste ID = 100, importieren
   - Anschließend `INSERT INTO ... DEFAULT VALUES` muss ID 101 vergeben,
     nicht 1 oder 2 (wäre der Fall ohne Reseeding)
   - Zusätzlicher Fall: leerer Import bzw. `MAX(col) = NULL` führt zu
     keinem Reseed und keinem `SequenceAdjustment`
4. **Trigger-Modus-Verifikation**:
   - Tabelle mit `BEFORE INSERT`-Trigger, der `value = value * 2` setzt
   - PG-Fixture enthält dafür eine echte `plpgsql`-Funktion plus
     `CREATE TRIGGER`-Statement im Test-Setup, nicht nur Pseudo-SQL
   - Import mit `--trigger-mode fire`: Werte sind verdoppelt
   - Import mit `--trigger-mode disable` (PG): Werte sind unverändert
   - Import mit `--trigger-mode strict` auf eine Tabelle mit Trigger:
     Exit 3, Meldung nennt Tabelle und Trigger-Pre-Flight
   - Import mit `--trigger-mode disable` (MySQL/SQLite): Exit 2 mit
     `UnsupportedTriggerModeException`
   - Für MySQL/SQLite reicht darüber hinaus die normale Round-Trip-Suite als
     `fire`-Pfad-Abdeckung; eine eigene Trigger-Fixture ist dort in 0.4.0
     nicht Pflicht-Gate
5. **`--truncate`-Pre-Step ist NICHT atomar** (F41 — explizit dokumentiert):
   - Tabelle mit 1000 Rows, Import mit `--truncate` und einer absichtlich
     fehlerhaften Row bei explizitem oder implizitem `--on-error abort`
     fehlerhaften Row → der Test asserted, dass die Tabelle danach **leer**
     ist (NICHT wieder mit den 1000 Rows gefüllt). Der Test schützt damit
     das in §6.14 dokumentierte Verhalten gegen versehentliche Atomarisierung
     in einer späteren Refactoring-Runde.
6. **MySQL UPSERT-Accounting-Verifikation** (R10):
   - Batch-Import mit `--on-conflict update` gegen MySQL
   - Test deckt sowohl per-Row-Counts als auch `SUCCESS_NO_INFO (-2)` ab
   - Bei per-Row-Counts: `rowsInserted`/`rowsUpdated` korrekt getrennt,
     `rowsUnknown == 0`
   - Bei `-2`: `rowsUnknown` enthält die Batch-Größe, `rowsInserted` und
     `rowsUpdated` bleiben für diesen Batch unverändert, und der Report
     zeigt sowohl die separate `unknown`-Zahl in der Summary-Zeile als
     auch die Hinweiszeile „inserts/updates not exactly distinguishable
     for N rows (MySQL SUCCESS_NO_INFO)"
   - JSON-Output (`--output-format json`) hat `rowsUnknown` als
     eigenes Feld auf `TableImportSummary` und `ImportResult`

---

## 10. Risiken

| Risiko | Mitigation |
|---|---|
| **Streaming-Reader leakt Speicher** bei großen Eingaben | Phase B Schritt 7: 100-MB-Heap-Sample-Test pro Reader |
| **Sequence-Reseeding race** zwischen Import-Ende und nächster INSERT | PG verwendet das normale nicht-mutierende `setval(seq, maxImported, true)`-Muster ohne `nextval()`-Side-Effect; der Plan beansprucht bewusst KEINE cross-session-Serialisierung. MySQL bleibt bei `ALTER TABLE ... AUTO_INCREMENT`. |
| **MySQL liefert `SUCCESS_NO_INFO (-2)` statt per-Row-Counts** | **R10**: Batch landet im neuen `WriteResult.rowsUnknown`-Feld (NICHT in `rowsInserted`, weil das Delta-Imports als 100 % Inserts maskieren würde); Summary in §6.15 zeigt `rowsUnknown` als getrennten Counter plus Hinweiszeile; Phase F testet den degradierten Accounting-Pfad explizit gegen den `rowsUnknown`-Counter |
| **Trigger-Reaktivierung scheitert** nach Fehler im Import | Design-Doc §7.3: harter Fehler mit Tabellen-Name; `close()` versucht trotz vorheriger Exceptions die Reaktivierung, aber nie das Reseeding |
| **`--on-conflict update` ohne PK** wird stillschweigend zu Insert | Pre-Flight-Check via `DatabaseMetaData.getPrimaryKeys()`; Tabelle ohne PK + `update` → Exit 2 |
| **Encoding-Auto-Fallback verschluckt Mojibake** | Wenn `auto` ohne BOM auf UTF-8 fällt, stderr-Hinweis ausgeben; in den Tests prüfen, dass ISO-8859-1 ohne expliziten Flag mit „Decoding error"-Meldung scheitert |
| **MySQL `disable` Trigger-Pfad** wird vom User erwartet, ist aber nicht implementiert | Klare Fehlermeldung mit Verweis auf design-import-sequences-triggers.md §6.2 |
| **`--truncate` löscht produktive Daten** ohne Rückfrage | `--yes` Pflicht im non-interaktiven Modus; cli-spec.md §8 dokumentiert das schon |
| **Inkrementeller Import läuft mehrfach und führt zu doppelten UPSERTs** (= idempotent, also OK) — aber der User bekommt kein Feedback, dass nichts geändert wurde | `WriteResult.rowsInserted` vs `rowsUpdated` getrennt zählen und im Report ausgeben |
| **`ParameterizedClause` aus User-Eingabe** wird falsch parametrisiert | Strikte Identifier-Validierung für `--since-column` (gleiches Pattern wie `--tables`); `--since` wird typisiert gebunden, kein String-Concat |
| **JDBC-Treiber liefern unterschiedliche `sqlTypeName`-Strings** | `ValueDeserializer` normiert primär über JDBC-Typcodes und nutzt `sqlTypeName` nur als sekundären Dialekt-Hint; Dialekt-Tests decken PG/MySQL/SQLite explizit ab |
| **MySQL `VALUES(col)`-UPSERT-Syntax altert weg** | In 0.4.0 noch dokumentiert, aber als Follow-up in §11 behalten; neuere MySQL-Versionen bevorzugen Alias-basiertes Update |
| **M2: Per-Chunk Allocation-Last durch Reorder-Mapping** — der Importer baut pro Chunk einen neuen `DataChunk` plus pro Row ein neues `Array<Any?>`, weil `DataChunk` eine `data class` mit `val`-Feldern ist. Bei 30 k rows/s und `chunkSize = 10 000` läuft das in einen spürbaren GC-Druck. | Phase D Schritt 23 enthält explizit ein JFR-/Allocation-Profiling auf dem Reorder-Pfad gegen ein 1-M-Row-Fixture (`@Tag("perf")`, nicht in Standard-CI). Wenn der Allocation-Anteil > 25 % der Importzeit ausmacht, kommt vor Phase F ein additiver Reorder-Vertrag dazu: entweder eine neue Session-Methode `write(reorderedRows: List<Array<Any?>>, bindingColumns: List<ColumnDescriptor>)` oder ein wiederverwendbarer `RowBuffer`-Pool im Importer. Beide Varianten sind additiv und brechen die in §3.1.1 dokumentierte API nicht. Die Entscheidung wird vor Phase F getroffen, damit Phase F kein Performance-Surprise-Refactoring auslöst. |

---

## 11. Out-of-Scope für 0.4.0

Folgende Punkte sind absichtlich vertagt — bitte nicht aus Versehen
mitziehen:

| Punkt | Wohin |
|---|---|
| `--resume` / Checkpoint-State-File | 0.9.0 (LN-012) |
| Atomare Multi-Tabellen-Transaktionen über Chunks hinweg | 1.0.0 (LN-013) |
| **`--replace-table`** (atomares DELETE+Import in einer Tabellen-Transaktion) | 0.5.0 oder später (§6.14, F41) |
| **PG `--disable-fk-checks`** als generischer Mechanismus | nie — F42, siehe §6.8.1; Nutzer wählen `--schema` oder `DEFERRABLE`-Constraints |
| Alias-basiertes MySQL-UPSERT statt `VALUES(col)` | Follow-up nach 0.4.0; 0.4.0 dokumentiert noch das kompatible Alt-Idiom |
| Parallele Tabellen-Imports | 1.0.0 (LN-007) |
| `schema reverse` und Auto-Marker-Discovery für `--since-column` | 0.6.0 (LF-004) |
| `--incremental` als Flag auf der Import-Seite | nie — F44, siehe §6.12.2; idempotenter Import läuft über `--on-conflict update` |
| Auto-Mapping vom Dateinamen auf die Zieltabelle (`users.json` → `users`) | später (additiv) — F40, siehe §3.7.1; 0.4.0 verlangt explizit `--table` |
| NDJSON / JSON Lines als Importformat | später (additiv); 0.4.0 akzeptiert nur Top-Level-JSON-Arrays |
| Auto-detect Encoding ohne BOM (ICU/chardet) | nie — F45; User setzt `--encoding` für Non-UTF-Streams |
| PG `COPY FROM` Performance-Pfad | 1.0.0 |
| Tombstone-/Soft-Delete-Synchronisation für Inkrement | 1.0.0+ |
| `data seed` (Test-Daten-Generierung) | 1.3.0 |
| Replikations-/CDC-Modi | 1.1.0+ |
| Encrypted credentials in `.d-migrate.yaml` | 1.0.0 |

---

## 12. Aufgelöste Review-Fragen und Findings

### 12.1 Initiale offene Fragen

- **Soll `--format` aus der Endung abgeleitet werden?** Ja, mit Fallback auf
  Exit 2 wenn keine Endung erkennbar (§6.3).
- **Default-Trigger-Modus?** `fire`, wie im Design-Doc §11 empfohlen
  (§6.7).
- **Sequence-Reseeding immer oder optional?** Default `true`, abschaltbar
  via `--reseed-sequences=false` (§6.6).
- **Wie wird `--since` gegen SQL-Injection geschützt?** Via
  `DataFilter.ParameterizedClause` und JDBC-Parameter-Binding (§3.8 + §6.12).
- **Foreign-Key-Checks während Import?** Default an; `--disable-fk-checks`
  als getrenntes Flag — aber NICHT auf PG (siehe §6.8.1, F42).
- **PK-lose Tabellen + UPSERT?** Pre-Flight-Check, Exit 2 mit klarer
  Meldung (§6.12.2).

### 12.2 Erste Review-Runde — Findings F40–F47

| ID | Finding | Auflösung |
|---|---|---|
| F40 (Hoch) | Single-File/Stdin-Import hatte keine eindeutige Tabellen-Auflösung — `--tables` ist als Liste mehrdeutig für Single-Source-Pfade | `--table` (singular) ist Pflicht für `Stdin` und `SingleFile`; `--tables` (plural) bleibt Subset-Filter nur für `Directory`. Tabelle der Auflösungsregeln in §3.7.1. Auto-Mapping vom Dateinamen ist explizit out-of-scope (§11) und kann später additiv kommen. |
| F41 (Hoch) | `--truncate`-Vertrag war in sich widersprüchlich — chunkbasierte Commits + atomarer Pre-Truncate gehen nicht generisch zusammen | `--truncate` ist explizit ein **nicht-atomarer Pre-Step** (§6.14). Wenn der Import nach dem TRUNCATE scheitert, bleibt die Tabelle leer. Verifikationstest 5 wurde umgedreht und prüft jetzt aktiv das nicht-atomare Verhalten. Atomares Replace landet als zukünftiges `--replace-table` in §11. |
| F42 (Hoch) | PG `--disable-fk-checks` über `ALTER TABLE … DISABLE TRIGGER ALL` braucht Superuser → unzuverlässig in der Praxis | PG bekommt **keinen** generischen `--disable-fk-checks`-Pfad in 0.4.0 (§6.8.1). Stattdessen zwei sichere Wege: topologische Sortierung mit `--schema` und `SET CONSTRAINTS ALL DEFERRED` für bereits-deferrable Constraints. `--disable-fk-checks` auf PG → Exit 2 mit Hinweis-Text. MySQL und SQLite behalten ihre session-lokalen Schalter (§6.8.2). |
| F43 (Mittel) | Reader-Vertrag „erster Chunk hat immer columns" ist für leere JSON/YAML nicht erfüllbar | `nextChunk()` darf für leere selbstbeschreibende Eingaben sofort `null` liefern (§3.5.1). Spalten-Metadaten kommen aus dem Target-Schema (§6.4). Reader liefert nur `headerColumns()` als file-derived Header-Namen — die Typen kommen IMMER aus JDBC-`ResultSetMetaData`, nie aus dem Reader. |
| F44 (Mittel) | LF-013 Import-Seite war mit halb-funktionalem `--since-column` doppelt belegt | `--incremental` und `--since-column` sind komplett aus `DataImportCommand` entfernt (§3.7.1, §6.12.2). Inkrementeller Import = idempotenter UPSERT-Import via `--on-conflict update`. Roadmap-Zeile entsprechend angepasst. |
| F45 (Niedrig) | „Encoding-Erkennung" war irreführend — ISO-8859-1 wird nicht erkannt | Wortlaut auf „Encoding-Unterstützung" geändert; in §1, §6.9 und der Roadmap explizit dokumentiert: BOM-Detection nur für UTF-8/UTF-16, alles andere via `--encoding`. |
| F46 (Hoch) | `database.default_source` war im 0.3.0-Übergang unklar und im 0.4.0-Plan zunächst nicht adressiert | 0.4.0 aktiviert die Symmetrie explizit: `resolveSource(...)` für `data export`, `resolveTarget(...)` für `data import`; Spec-Pflege in §7 zieht beide Defaults nach. |
| F47 (Mittel) | Schema-Qualifikation von `--table`/`--tables` war im Import-Plan implizit, aber nicht festgeschrieben | Import übernimmt den 0.3.0-Identifier-Vertrag explizit: erlaubt sind `name` oder `schema.table`, ohne SQL-Quotes aus der CLI; `ImportInput`/CLI/§6.4 wurden entsprechend präzisiert. |

### 12.3 Phase-A-Review — Findings L15 und folgende

Aufgetreten beim Self-Review nach Abschluss von Phase A. Die Findings
ergänzen die vorherigen Review-Runden und werden hier separat gelistet,
damit klar ist, was post-Implementation erkannt wurde.

| ID | Finding | Auflösung |
|---|---|---|
| L15 (Hoch) | Frühere Phase-A-Arbeitsfassung hatte testweise ein `jdbcType: Int? = null` auf `core.ColumnDescriptor`. Das verletzte die 0.3.0-Architektur-Regel „kein JDBC-spezifisches Feld in core". | Historisch bereinigt und im Haupttext kanonisch festgeschrieben: `core.ColumnDescriptor` bleibt JDBC-frei; Writer-seitige Metadaten laufen über `hexagon:ports.TargetColumn`, Deserializer-Hints über `formats.JdbcTypeHint`, und der `StreamingImporter` baut die Lookup-Closure `(columnName) -> JdbcTypeHint?` einmal pro Tabelle. |
| L16 (Hoch) | `ValueDeserializer.toBigDecimal(Float)` ging über `BigDecimal.valueOf((value as Number).toDouble())`, was für Float-Werte die binäre Float-Repräsentation durchreicht — `0.1f` wäre als `BigDecimal("0.10000000149011612")` an eine `NUMERIC(10,2)`-Spalte gelandet. Klassischer stiller Datenkorruptions-Pfad. | Float-Zweig explizit auf `BigDecimal(value.toString())` umgestellt (String-Konstruktor umgeht die binäre Repräsentation). Phase A hat einen expliziten Test gegen diesen Pfad: `deserialize(tableName, "c", 0.1f) shouldBe BigDecimal("0.1")`. |
| L17 (Hoch) | `ValueDeserializer.toLong(Double)` akzeptierte `1.0` als gültigen Integer, weil `d == d.toLong().toDouble()` für `1.0` true ist. Das widersprach der Plan-Regel aus §3.5.2, die token-basiert arbeitet: ein Reader, der `1.0` (mit Dezimalpunkt im Source-Token) liefert, hat bereits signalisiert, dass es ein Decimal-Token ist. Aus String-Pfad wurde `"1.0"` abgewiesen, aus Double-Pfad durchgewunken — Inkonsistenz. | `toLong(Number)` wirft jetzt konsequent bei allen Non-Integer-Number-Typen (Double/Float), auch wenn der Wert zufällig ganzzahlig ist. Test `H-A2 — Double 1.0 (decimal token) is rejected` sichert den Pfad ab. |
| L18 (Mittel) | `ValueDeserializer.toBoolean("  true  ")` akzeptierte ein getrimmtes `true` nicht — der `toLong`/`toDouble`-Pfad trimmte, aber `toBoolean` nicht. Inkonsistenz zwischen Konvertern. | `toBoolean(String)` trimmt jetzt vor dem `lowercase()`-Check. Explizites Test-Case `"  true  " shouldBe true`. |
| L19 (Niedrig) | `EncodingDetector.UnsupportedEncodingException` kollidierte namentlich mit `java.io.UnsupportedEncodingException`. Ein Caller mit Auto-Import würde den falschen Type catchen. | Bereinigt: der Typ heißt jetzt `UnsupportedFileEncodingException` und kollidiert nicht mehr mit dem JDK-Namen. Tests wurden auf den neuen Typ umgestellt. |
| L20 (Niedrig) | `ImportOptions.encoding` default war `StandardCharsets.UTF_8` — damit skippt der Default-Programmatic-Konstruktor die Auto-Detection, während der CLI-Default `--encoding auto` per Design auf `null` mappen sollte. Default-Inkonsistenz zwischen CLI und Library-Use. | Bereinigt: `ImportOptions.encoding` default ist jetzt `null` (= auto-detect), damit Programmatic-Default und CLI-Default denselben BOM-Sniff-Pfad benutzen. |
| L21 (Mittel) | `JdbcChunkSequence.readColumnMetadata` (in `AbstractJdbcDataReader`) befüllte `jdbcType` nicht — war in der Phase-A-`ColumnDescriptor`-Version mit neuem Slot irrelevant, nach dem Rollback ist es weiterhin irrelevant, weil `ColumnDescriptor` den Slot nicht mehr hat. Der Finding bleibt als Hinweis: wenn Phase C den `DataWriter.openTable`-Pfad baut, muss er seine eigene Metadata-Funktion schreiben, die `TargetColumn` erzeugt (nicht `ColumnDescriptor`). | Dokumentiert in §3.1.2 `TargetColumn`-Kdoc. Phase C macht eine eigene `readTargetMetadata`-Funktion im Writer-Layer, analog zum `JdbcChunkSequence.readColumnMetadata`, aber mit `getColumnType(i)` statt nur `getColumnTypeName(i)`. |
