# Implementierungsplan: Milestone 0.4.0 — Datenimport und inkrementelle Datenpfade

> Dieses Dokument beschreibt den konkreten Implementierungsplan für Milestone 0.4.0.
> Es dient als Review-Grundlage vor Beginn der Umsetzung.
>
> **Status**: Entwurf — vor Beginn von Phase A reviewen.

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

- Lese-Pfad in `d-migrate-formats`: `DataChunkReader` für JSON, YAML, CSV mit
  echtem Streaming (kein Volltext-Parsen)
- `DataWriter`-Port in `d-migrate-driver-api` plus JDBC-Adapter für PostgreSQL,
  MySQL und SQLite (Batch-`INSERT` per `PreparedStatement.addBatch`)
- `StreamingImporter` in `d-migrate-streaming` (Glue Reader → Writer, mit
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
parallel zu den Writern, `driver-api` einen `DataWriter`-Port parallel zum
`DataReader`, `streaming` einen `StreamingImporter` parallel zum
`StreamingExporter`, `cli` ein neues `DataImportCommand` und einen
`--since-column`/`--since`-Flow für `DataExportCommand`.

> **Wichtig**: Wir vermeiden bewusst ein neues Modul „d-migrate-import". Die
> bestehenden Module sind nach **Schicht** organisiert, nicht nach Read-/Write-
> Richtung. Ein separates Import-Modul würde die hexagonale Aufteilung
> aufweichen.

---

## 3. Bestehende Module — Änderungen

### 3.1 `d-migrate-driver-api`

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

interface TableImportSession : AutoCloseable {
    /**
     * Autoritative Target-Spalten in Binding-Reihenfolge. Der Writer liest
     * sie beim Öffnen der Tabelle selbst über JDBC-Metadaten ein (§6.4).
     * Der Importer benutzt diese Liste für Type-Hints und Reorder-Mapping.
     */
    val targetColumns: List<ColumnDescriptor>

    /**
     * Schreibt einen Chunk im aktuellen Transaktionskontext.
     *
     * Vor `write(...)` normalisiert der Importer `chunk.columns` auf die
     * tatsächlich im File vorhandene Target-Spalten-TEILMENGE in
     * Target-Reihenfolge. Der Writer erzeugt seinen INSERT-Spalten-Block aus
     * genau dieser Liste und lässt alle übrigen [targetColumns] weg, so dass
     * DB-DEFAULTs erhalten bleiben.
     */
    fun write(chunk: DataChunk): WriteResult

    /**
     * Bestätigt den letzten geschriebenen Chunk. In JDBC mit
     * `autoCommit=false` entspricht das semantisch exakt `conn.commit()`;
     * ein „nächster Chunk" entsteht implizit durch die nächste
     * Schreiboperation, nicht durch zusätzliche Session-Logik.
     */
    fun commitChunk()

    /**
     * Verwirft den letzten geschriebenen Chunk und liefert eine
     * Abbruch-Information zurück. Idempotent.
     */
    fun rollbackChunk()

    /**
     * Regulärer Erfolgsabschluss einer Tabelle: führt etwaige
     * Sequence-/Identity-Nachführung (§6.6) aus, reaktiviert ggf. Trigger
     * (§6.7) und liefert die Sequence-Anpassungen für den Import-Report.
     *
     * Darf NUR nach erfolgreichem Schreiben aller Chunks aufgerufen werden.
     */
    fun finishTable(): List<SequenceAdjustment>

    /**
     * Cleanup-Pfad: reaktiviert ggf. Trigger, setzt Transaktionszustand zurück
     * und gibt die Connection an den Pool zurück. Führt bewusst KEIN Reseeding
     * aus; `close()` läuft auch im Fehlerpfad nach Teilimporten.
     */
    override fun close()
}

data class WriteResult(
    val rowsInserted: Long,
    val rowsUpdated: Long,
    val rowsSkipped: Long,
)

class ImportSchemaMismatchException(message: String) : RuntimeException(message)
class UnsupportedTriggerModeException(message: String) : RuntimeException(message)
```

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
```

`DataWriter.schemaSync()` ist **pflichtig**. Es gibt absichtlich keine
Default-No-Op-Implementierung; vergessene Treiber-Overrides sollen zur
Compile-Zeit oder spätestens im Review auffallen, nicht als stiller
Laufzeit-Fehler.

### 3.2 `d-migrate-driver-postgresql`

```
d-migrate-driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/
├── PostgresDataWriter.kt       # JDBC Batch-INSERT, COPY-Pfad als Optimierung in 1.0.0
├── PostgresSchemaSync.kt       # setval(), session_replication_role NICHT — siehe §6.8.1
└── PostgresDriver.kt           # erweitert um registerDataWriter
```

PG-spezifika:

- **Batch-INSERT**: `INSERT INTO "schema"."tab" ("c1", "c2") VALUES (?, ?)`
  mit `addBatch()` pro Row und `executeBatch()` einmal pro Chunk.
  COPY-FROM ist ~10× schneller, kommt aber erst in 1.0.0 als Performance-Pfad.
- **Sequence-Reseeding**: pro Identity-/SERIAL-Spalte die zugehörige Sequence
  via `pg_get_serial_sequence(quoted_table, column)` ermitteln und mit
  dem üblichen `setval(sequence, MAX(column), true)`-Idiom nachführen
  (äquivalent zu `pg_dump`). `nextval()` wird hierfür bewusst NICHT als
  Hilfskonstrukt benutzt; das wäre selbst schreibend und würde pro Reseed
  unnötig einen Sequence-Wert verbrennen. Die Nachführung ist ein
  Erfolgsabschluss nach Tabellen-Import, aber kein Lock gegen parallele
  Writer in anderen Sessions.
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

### 3.3 `d-migrate-driver-mysql`

```
d-migrate-driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/
├── MysqlDataWriter.kt
├── MysqlSchemaSync.kt
└── MysqlDriver.kt
```

MySQL-spezifika:

- **Batch-INSERT** mit `rewriteBatchedStatements=true`-Hint im JDBC-URL
  (vom `MysqlJdbcUrlBuilder` bereits gesetzt — Verifikation im echten
  Writer-Pfad in Phase C/F).
- **AUTO_INCREMENT-Reseeding**: `ALTER TABLE \`table\` AUTO_INCREMENT = N`
  mit `N = MAX(autoinc_column) + 1`. Dialect-Quote ist Backtick.
- **Trigger-Disable**: in 0.4.0 **nicht generisch unterstützt**. Versuch,
  Trigger zu deaktivieren, wirft `UnsupportedTriggerModeException` mit
  Verweis auf Design-Doc §6.2. Default `fire` läuft normal.
- **Foreign-Key-Checks**: optional via `SET FOREIGN_KEY_CHECKS=0` für die
  Session — nur wenn `--disable-fk-checks` gesetzt ist (siehe §6.8).

### 3.4 `d-migrate-driver-sqlite`

```
d-migrate-driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/
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
- **Trigger-Disable**: nicht unterstützt (Design-Doc §6.3). `disable` →
  `UnsupportedTriggerModeException`.
- **Foreign-Key-Checks**: temporär `PRAGMA foreign_keys = OFF` während des
  Imports, am Ende `PRAGMA foreign_keys = ON` zurückgesetzt — nur mit
  `--disable-fk-checks`.

### 3.5 `d-migrate-formats`

#### 3.5.1 Reader-Pendant zum Writer-Trio

```
d-migrate-formats/src/main/kotlin/dev/dmigrate/format/data/
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
    fun nextChunk(chunkSize: Int): DataChunk?

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
    fun create(
        format: DataExportFormat,
        input: InputStream,
        table: String,
        options: ImportOptions = ImportOptions(),
    ): DataChunkReader
}
```

**Streaming-Vertrag pro Format**:

| Format | Streaming-Mechanik | `headerColumns()`-Quelle |
|---|---|---|
| JSON | DSL-JSON's pull-Parser (`JsonReader.next()`/`getNextToken()`) — wir parsen das Top-Level-Array tokenweise und produzieren `DataChunk`s zu je `chunkSize` Objekten. Vor Beginn von Phase B gibt es einen Go/No-Go-Spike mit 100-MB-Fixture; wenn die Pull-API dabei keinen konstanten Speicherpfad liefert, wird die Library-Entscheidung VOR der Reader-Implementierung neu geöffnet. | Schlüssel des ersten Objekts; bei `[]` → `null` |
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

#### 3.5.2 `ValueDeserializer`

Inverse zur `ValueSerializer`-Mapping-Tabelle aus Plan-0.3.0 §6.4.1. Für jeden
Eingabe-Typ aus den drei Formaten bestimmt sie den entsprechenden Java-Wert,
den der Importer in Target-Reihenfolge an den Writer weitergeben kann.
Wichtig: die Tabelle unten ist nur der Ausschnitt mit den kniffligen Fällen;
die Implementierung MUSS die in 0.3.0/`neutral-model-spec.md` unterstützten
neutralen Typen vollständig abdecken.

| Format-Eingabe | Spalten-Hint (JDBC-Typ aus `ResultSetMetaData`) | Resultat |
|---|---|---|
| JSON String | `VARCHAR`/`TEXT`/`CLOB` | String |
| JSON String `true`/`false` (case-insensitive) | `BOOLEAN` | Boolean |
| JSON String | `DATE` | `LocalDate.parse` |
| JSON String | `TIMESTAMP` | `LocalDateTime.parse` |
| JSON String | `TIMESTAMP WITH TIME ZONE` | `OffsetDateTime.parse` |
| JSON String | `UUID` | `UUID.fromString` |
| JSON String | `INTERVAL` | String (dialektspezifisches Binding später im Writer) |
| JSON Number | `REAL`/`FLOAT`/`DOUBLE` | Double |
| JSON Number ohne `.`/`e`/`E` im Token | `INTEGER`/`BIGINT` | Long |
| JSON Number | `NUMERIC`/`DECIMAL` | Entscheidungsbaum: `scale > 0` oder Token mit `.`/`e`/`E` oder `precision > 18` → `BigDecimal`, sonst ganzzahliger Parse mit Präzisionscheck |
| JSON `null` | beliebig | SQL NULL |
| JSON `null` | `JSON`/`JSONB` | ebenfalls SQL NULL, NICHT JSON-Literal `null` |
| JSON Array | `ARRAY` | `List<Any?>` (JDBC-`createArrayOf()` erst im Writer) |
| YAML Block-Sequence | `ARRAY` | wie JSON Array |
| JSON/YAML Binärwert oder Base64-String | `BLOB`/`BYTEA`/binary | `ByteArray` |
| JSON/YAML Objekt oder JSON-String | `JSON`/`JSONB` | JSON-String |
| JSON/YAML String | `BIT` | `Boolean` oder `BitSet` je nach Zieltyp |
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

Primärer Typ-Anker ist dabei der JDBC-Typcode (`ResultSetMetaData` /
`java.sql.Types`), nicht der rohe `sqlTypeName`-String. Dialektspezifische
`sqlTypeName`-Werte (`int4` vs. `INT` vs. `INTEGER`) werden nur als
sekundärer Hint normalisiert, damit PG/MySQL/SQLite nicht an reinen
Treiber-Strings auseinanderlaufen.

Array-/Treiberobjekte werden bewusst NICHT in `d-migrate-formats` erzeugt.
`formats` bleibt JDBC-frei; konkrete `java.sql.Array`- oder PG-`PGobject`-
Materialisierung passiert erst im Writer-Layer.

ImportOptions enthält für 0.4.0 mindestens die reader-/writerrelevanten
Schalter aus diesem Plan (`triggerMode`, `csvNoHeader`, `csvNullString`,
`encoding`, `reseedSequences`, `disableFkChecks`, `truncate`,
`onConflict`, `onError`).
`chunkSize` gehört bewusst NICHT hier hinein, sondern bleibt in
`PipelineConfig(chunkSize = ...)` wie im 0.3.0-Exportpfad.

### 3.6 `d-migrate-streaming`

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
- Reader dürfen dafür intern die erste Row bzw. den ersten Chunk puffern;
  insbesondere JSON/YAML dürfen die Header aus dem ersten Objekt ableiten
  und denselben Datensatz anschließend regulär als Teil von `firstChunk`
  zurückgeben
- Pro Tabelle: `reader.nextChunk()` → Importer normalisiert/castet die Row-
  Reihenfolge auf die gebundene Spalten-Teilmenge und setzt `chunk.columns`
  auf genau diese Liste → `session.write(chunk)` → bei Erfolg
  `session.commitChunk()`, bei Fehler `session.rollbackChunk()` plus
  `--on-error`-Auswertung
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
     * Import auf eine Untermenge der gefundenen Dateien. Die Ausführung ist
     * deterministisch lexikographisch nach Tabellenname/Dateiname sortiert;
     * für FK-abhängige Multi-Table-Imports ist ohne `--schema` dennoch nur
     * Best-Effort möglich (siehe §6.8.3).
     */
    data class Directory(
        val path: Path,
        val tableFilter: List<String>? = null,
    ) : ImportInput()
}
```

Diese Trennung löst die Mehrdeutigkeit, die in der ersten Plan-Fassung
`--tables` als Multipurpose-Option für Single- und Multi-Quellen behandelt
hat. Für `Stdin` und `SingleFile` gibt es **immer genau eine** Zieltabelle
und sie muss vom Aufrufer benannt werden.

Schema-Qualifikation ist dabei explizit erlaubt: `table` folgt dem gleichen
Identifier-Pattern wie 0.3.0-`--tables`, also `name` oder `schema.table`,
ohne SQL-Quoting aus der CLI heraus.

#### 3.6.2 `ImportResult` und `TableImportSummary`

Spiegelbild zu `ExportResult`. Zusätzlich pro Tabelle:

- `rowsInserted`, `rowsUpdated`, `rowsSkipped`, `rowsFailed`
- `chunkFailures: List<String>` für `--on-error log` auf Chunk-Granularität
- `sequenceAdjustments: List<SequenceAdjustment>` (für den Report)
- `targetColumns: List<ColumnDescriptor>` (für Debug/JSON-Output)
- `triggerMode: TriggerMode` (welcher Modus tatsächlich angewendet wurde)
- `error: String?`

`rowsFailed` ist dabei ausdrücklich KEIN Teil von `WriteResult`. Der Wert
wird vom `StreamingImporter` aggregiert, wenn ein Chunk write/commit-seitig
scheitert und die `--on-error`-Politik `skip` oder `log` ein Weitermachen
erlaubt. In 0.4.0 ist das daher auf Summary-Ebene effektiv `0` oder
`chunk.size` pro fehlgeschlagenem Chunk.

### 3.7 `d-migrate-cli`

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
    val reseedSequences by option("--reseed-sequences", "--no-reseed-sequences").flag(default = true)
    val encoding by option("--encoding").default("auto")               // Default: BOM-Detect für UTF-*
    val csvNoHeader by option("--csv-no-header").flag()
    val csvNullString by option("--csv-null-string").default("")       // symmetrisch zu 0.3.0; für lossless NULL/"": expliziten Sentinel setzen
    val chunkSize by option("--chunk-size").int().default(10_000)
}
```

`chunkSize` wird im Command bewusst über
`PipelineConfig(chunkSize = chunkSize)` an `StreamingImporter.import(...)`
durchgereicht, nicht über `ImportOptions`.

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

**Pre-Flight-Reihenfolge in `DataImportCommand.run()`**:

| Phase | Prüft |
|---|---|
| CLI-Parse/Helper | `--source`-Form (stdin/file/dir), Identifier-Validierung für `--table`/`--tables`, explizites `--on-conflict`, `--truncate`-Exklusivität |
| Nach `resolveTarget(...)` | Dialekt-Auflösung, `--disable-fk-checks` auf PG, Connection-/Config-Fehler |
| Nach `openTable(...)` | Header-Validierung/Reorder-Mapping, Trigger-Strict-/Disable-Prüfung, PK-/UPSERT-Prüfung, ggf. FK-Deferral-Pre-Flight |

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

### 3.8 `d-migrate-core`

Daten-Modell aus 0.3.0 bleibt unverändert. Wir ergänzen einen neuen Filter-Typ:

```kotlin
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

### 3.9 `gradle.properties` und `build.gradle.kts`

Unter der Baseline-Entscheidung aus Phase A/B kommen keine neuen externen
Dependencies hinzu. JSON/YAML/CSV-Lib-Versionen aus 0.3.0 reichen aus — die
Reader-Pfade benutzen die gleichen Bibliotheken. Falls der DSL-JSON-Go/No-Go-
Spike in Phase B scheitert, wird diese Annahme vor Implementierungsstart von
Phase B explizit neu entschieden statt still fortgeschrieben.

```properties
# Keine neuen Versions-Properties — alle Bibliotheken aus 0.3.0 wiederverwendet.
```

---

## 4. Implementierungsreihenfolge

### Phase A: Reader-Vertrag und Daten-Modell-Erweiterungen

1. `DataChunkReader`-Interface + `DataChunkReaderFactory`
2. `ImportOptions` (parallel zu `ExportOptions`)
3. `EncodingDetector` mit BOM-Sniff nur für UTF-8/UTF-16 BE/LE; alle anderen
   Encodings (ISO-8859-1, Windows-1252, …) ausschließlich über
   `--encoding`-Fallback (siehe §6.9)
4. `ValueDeserializer` mit der Mapping-Tabelle aus §3.5.2 plus Tests
5. `DataFilter.ParameterizedClause` und Erweiterung von
   `AbstractJdbcDataReader` für parametrisierte WHERE-Klauseln, inkl.
   Test für `Compound([WhereClause, ParameterizedClause])` mit korrekter
   SQL-Erzeugung und Parameter-Positionsbindung
6. Go/No-Go-Spike für DSL-JSON Pull-Parsing auf einem 100-MB-Top-Level-Array
   mit konstantem Speicherbudget; bei Fehlschlag wird die JSON-Library-
   Entscheidung vor Phase B neu geöffnet

### Phase B: Format-Reader

7. `JsonChunkReader` mit DSL-JSON Pull-Parser, Streaming-Tests gegen
   ein 100-MB-Fixture (kein Buffer-Aufbau über Chunk-Größe hinaus)
8. `YamlChunkReader` mit SnakeYAML Engine Event-API
9. `CsvChunkReader` mit uniVocity `CsvParser`, inkl. Header-Auflösung
10. `DefaultDataChunkReaderFactory`
11. Golden-Master-Reader-Tests pro Format mit Round-Trip gegen die
    Phase-D-Writer aus 0.3.0 (Schreiben → Lesen → Vergleich)

Hinweis zur Parallelisierung: Nach Abschluss von Phase A sind Phase B
(Reader) und Phase C (Writer) weitgehend unabhängig und können bei Bedarf
parallel umgesetzt werden. Phase D (`StreamingImporter`) ist der erste
Schritt, der beide Pfade zusammenführt.

### Phase C: DataWriter-Port und JDBC-Treiber

12. `DataWriter`/`TableImportSession` Interfaces in `driver-api`
13. `SchemaSync` Interface + `SequenceAdjustment`
14. `DataWriterRegistry` (object) mit `clear()` für Tests
15. `PostgresDataWriter` + `PostgresSchemaSync` (setval, ALTER TABLE
    DISABLE TRIGGER USER, inkl. Test für
    `pg_get_serial_sequence('"schema"."table"', 'col')`-Quoting)
16. `MysqlDataWriter` + `MysqlSchemaSync` (ALTER TABLE … AUTO_INCREMENT,
    `disable` → UnsupportedTriggerModeException; `rewriteBatchedStatements`
    im echten Writer-Pfad verifizieren)
17. `SqliteDataWriter` + `SqliteSchemaSync` (sqlite_sequence, `disable`
    nicht unterstützt)
18. Treiber-Bootstrap-Objects um `registerDataWriter` ergänzt

### Phase D: StreamingImporter

19. `StreamingImporter` in `d-migrate-streaming`
20. `ImportInput` sealed class (Stdin / SingleFile / Directory)
21. `ImportResult` + `TableImportSummary`
22. Chunk-Transaktionsmodell (§6.5) mit `--on-error`-Politik
23. Streaming-Layer-Tests gegen SQLite (direkter `StreamingImporter`,
    kein CLI-Wrapper, kein Container nötig)

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
    via `nextval()` checken
30. `DataImportE2EMysqlTest` mit `AUTO_INCREMENT`-Verifikation
31. SQLite-E2E direkt ohne Container
32. Inkrementeller Round-Trip-E2E: initial export → 2× delta export →
    delta import → Vergleich
33. `integration.yml` läuft schon generisch (siehe F39 aus 0.3.0) — keine
    Workflow-Anpassung nötig

---

## 5. Abhängigkeiten zwischen Modulen

```
d-migrate-core
└── (unverändert)

d-migrate-driver-api
├── api(d-migrate-core)
├── api("com.zaxxer:HikariCP")
└── neu: DataWriter / TableImportSession / SchemaSync / DataWriterRegistry

d-migrate-driver-postgresql      # implementation(driver-api) + JDBC
d-migrate-driver-mysql           # dito
d-migrate-driver-sqlite          # dito

d-migrate-formats
├── implementation(d-migrate-core)
├── implementation("com.dslplatform:dsl-json-java8")     # bereits aus 0.3.0
├── implementation("com.univocity:univocity-parsers")    # bereits aus 0.3.0
└── implementation("org.snakeyaml:snakeyaml-engine")     # bereits aus 0.3.0

d-migrate-streaming
├── api(d-migrate-core)
├── api(d-migrate-driver-api)
├── api(d-migrate-formats)
└── neu: StreamingImporter, ImportInput, ImportResult

d-migrate-cli
├── implementation(d-migrate-streaming)
└── alle Treiber wie in 0.3.0
```

Keine neuen Modul-Kanten. Die hexagonale Architektur bleibt: `formats` ist
weiterhin JDBC-frei, `streaming` koordiniert nur Reader und Writer. Auch
ARRAY-/JSONB-Sonderfälle ändern daran nichts: JDBC-nahe Materialisierung
(`Connection.createArrayOf()`, PG-Objekte etc.) bleibt im Writer-Layer und
zieht keine zusätzliche Modul-Kante in `formats`.

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
  `DataChunk`. Wenn `]` erreicht ist → Stream zu Ende.
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
   ist das die einzige Möglichkeit; bei leerem JSON/YAML gibt es schlicht
   keine Rows zu importieren — der Import endet mit `0 rows inserted`.
   Header-only-CSV ist davon ausgenommen: dort darf `headerColumns()` trotz
   `nextChunk() == null` gesetzt sein, so dass die Header-Validierung noch
   vor dem Ergebnis `0 rows inserted` laufen kann.
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
> darauf explizit hin.

### 6.5 Chunk-Transaktionsmodell

Pro Tabelle eine Connection mit `autoCommit=false`. Pro Chunk:

```
session.write(chunk)                   -- batch Insert
  ├── Erfolg → session.commitChunk()   -- chunk persistiert
  └── Fehler → session.rollbackChunk() -- chunk verworfen
                  ├── --on-error abort → Tabellen-Import-Abbruch, Exit 5
                  ├── --on-error skip  → next Chunk (CHUNK-Granularität)
                  └── --on-error log   → next Chunk + im Report vermerken (CHUNK-Granularität)
```

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

1. Pro Spalte aus `importedColumns` prüfen: ist sie eine Generator-Spalte
   im Ziel? (PG: `pg_get_serial_sequence`; MySQL: Spalten-Metadata
   `EXTRA = 'auto_increment'`; SQLite: `INTEGER PRIMARY KEY AUTOINCREMENT`
   in `sqlite_master`)
2. Wenn ja: höchsten Wert der Spalte **im Ziel nach Abschluss aller Chunks**
   ermitteln (`SELECT MAX(col) FROM target` direkt nach dem letzten Commit
   auf derselben Session-Connection; ohne zusätzliche cross-session-
   Garantien — siehe §10)
3. Sequence/AUTO_INCREMENT/sqlite_sequence auf den nächsten gültigen Wert
   anheben
4. Ein `SequenceAdjustment` ins `ImportResult` aufnehmen

Für PostgreSQL gilt zusätzlich: `pg_get_serial_sequence(...)` erwartet den
qualifizierten Tabellennamen als SQL-Stringargument mit eingebetteten Quotes
(`'"public"."orders"'`), nicht den Identifier selbst. Die Implementierung
nutzt dafür die vorhandene Identifier-Quoting-Logik aus 0.3.0 statt eine
zweite Quoting-Variante im Writer zu erfinden.

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
| **(a)** Topologische Sortierung der Tabellen | `--schema <path>` mit der neutralen Schema-Definition | Wiederverwendung der Kahn-Sortierung aus `AbstractDdlGenerator` (0.2.0). Importe laufen in FK-respektierender Reihenfolge, FK-Checks bleiben aktiv. |
| **(b)** `SET CONSTRAINTS ALL DEFERRED` | Constraints sind im Ziel-Schema mit `DEFERRABLE` deklariert | Pro Chunk-Transaktion vor dem ersten Insert ausgeführt. Vorab prüft ein PG-Pre-Flight `pg_constraint.condeferrable` und loggt/reportet `N deferrable / M non-deferrable` FK-Constraints für die betroffenen Tabellen. Bei FK-abhängigem Multi-Table-Import ohne `--schema` und ohne passende deferrable Constraints → Exit 2 mit klarem Hinweis statt stillem No-Op. |

Wichtig: Pfad (b) verzögert Constraints nur bis zum nächsten Chunk-Commit.
Er ist also keine generische Alternative zu Cross-Table- oder Cross-Chunk-
Reihenfolgeproblemen; dafür bleibt Pfad (a) via `--schema` der saubere Weg.

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

Wenn `--schema <path>` gesetzt ist, wird **immer** topologisch sortiert
(unabhängig vom Dialekt). Das ist der einzige Weg, der ohne jeden
FK-Disable-Trick funktioniert und auf allen Dialekten gleichermaßen sauber
ist. Implementierung kommt direkt aus dem 0.2.0
`AbstractDdlGenerator.kahnSort()` — derselbe Algorithmus, andere Eingabe
(Tabellen statt DDL-Statements).

Ohne `--schema` gibt es KEINE FK-basierte Sortierung. Directory-Imports
laufen dann in deterministischer lexikographischer Reihenfolge der
Datei-/Tabellennamen. Für FK-abhängige Datensätze ist `--schema` damit
praktisch Pflicht; der CLI-Help-Text soll das explizit empfehlen.

Wenn zusätzlich `--tables a,b,c` gesetzt ist, wird die Reihenfolge aus dem
vollständigen Schema-DAG abgeleitet und danach auf das gewünschte Subset
gefiltert. Der Plan schließt also keine Kanten „weg", nur weil der User
einen Teilgraphen importieren will; fehlende Abhängigkeitstabellen werden
dadurch aber nicht automatisch mitimportiert.

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
statt, aber bekannte UTF-BOMs werden trotzdem konsumiert, damit sie nicht als
erstes Zeichen in CSV/JSON/YAML landen. Das BOM überschreibt in diesem Pfad
nicht das angeforderte Charset; es wird nur als Prefix entfernt.

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
ist, fällt der Detector auf UTF-8 zurück und protokolliert eine Hinweis-
Zeile auf stderr (`Hint: input file has no BOM, using UTF-8. Set --encoding
to override.`). Das verhindert, dass ein still falsch dekodierter ISO-8859-1-
Import als „funktioniert" durchgewunken wird, ohne dass jemand Mojibake
bemerkt. Unter `--quiet` wird dieser reine Hinweis unterdrückt.

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
| `0` | Erfolgreicher Import, alle Tabellen geschrieben | `Imported 3 tables (12 345 inserted, 1 200 updated) in 4.2 s; reseeded 2 sequences` |
| `1` | Unerwarteter interner Fehler / ungefangene Exception | `Unexpected error while importing table 'orders'` |
| `2` | CLI-Fehler, ungültige Optionen, fehlendes `--target` oder `--table`, `--trigger-mode disable` auf MySQL/SQLite, `--disable-fk-checks` auf PG, unbekannte Endung ohne `--format`, `--truncate` zusammen mit explizitem `--on-conflict`, PK-lose Tabelle mit `--on-conflict update` | `--trigger-mode disable is not supported for dialect MYSQL` |
| `3` | Header-/Target-Schema-Mismatch, `trigger-mode strict` mit gefundenen Triggern oder Schema-Validierung gegen `--schema <path>` fehlgeschlagen | `column 'userId' has no exact match` |
| `4` | Connection-Fehler (HikariCP, fehlendes Target) | `Connection refused: localhost:5432` |
| `5` | Import-Fehler während Streaming (SQL-Constraint-Verletzung, IO-Exception, Sequence-Reseeding gescheitert) | `Failed to import table 'orders' at chunk 47: duplicate key value violates unique constraint` |
| `7` | Konfigurationsfehler (URL-Parser, `.d-migrate.yaml`, fehlende ENV-Variable) | wie 0.3.0 |

Code 3 ist in 0.3.0 für `data export` nicht relevant gewesen, kommt hier
für die Schema-Validierung zurück (analog zu `schema validate` aus 0.1.0).

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

1. `--since-column` strikt validieren wie `--tables` (Plan-0.3.0 §6.7
   Identifier-Pattern)
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

`--since-column` und `--since` sind nur zusammen gültig; fehlt einer der
beiden Werte, endet der Export mit Exit 2.

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

Wichtiger Dialekt-Unterschied: PostgreSQL/SQLite binden den Konflikt-Target
explizit an den erkannten PK; MySQLs `ON DUPLICATE KEY UPDATE` feuert dagegen
auf JEDEM verletzten Unique-Index. Diese Asymmetrie wird dokumentiert, nicht
wegabstrahiert.

MySQL-Hinweis: das gezeigte `VALUES(col)`-Idiom ist für 0.4.0 noch
ausreichend dokumentiert, wird aber als Follow-up beobachtet, weil neuere
MySQL-Versionen Alias-basiertes Update bevorzugen.

`WriteResult` normalisiert dabei den Schreibeffekt pro Chunk auf
`rowsInserted` und `rowsUpdated`, auch wenn einzelne JDBC-Treiber
unterschiedliche Affected-Row-Konventionen haben.

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
wird: `TRUNCATE TABLE` (PG), `DELETE FROM` (MySQL und SQLite) in einer
eigenen, sofort committeten Transaktion, danach der reguläre chunkbasierte
Import-Pfad aus §6.5.

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
Imported 3 tables (12 345 inserted, 1 200 updated, 1 245 skipped) in 4.2 s
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

`--quiet` unterdrückt die Summary (wie in 0.3.0); `--no-progress` ebenfalls.
Für eine maschinenlesbare Variante: `--output-format json` am Root-Command
liefert das `ImportResult` als JSON auf stdout.

---

## 7. Spec- und Doku-Pflege

| Datei | Änderung |
|---|---|
| `docs/cli-spec.md` §6.2 `data import` | Vollständiger Block analog zu 0.3.0-`data export`: alle Flags aus §3.7.1, veraltetes `--resume` entfernen, Output-Resolver (Stdin/SingleFile/Directory), Exit-Code-Matrix 0/1/2/3/4/5/7, Beispiele mit Round-Trip und UPSERT-Import |
| `docs/cli-spec.md` §6.2 `data export` | LF-013-Block ergänzen: `--since-column`, `--since` mit Beispielen |
| `docs/connection-config-spec.md` | Neue Sektion „Status pro Feature" anlegen und die bisher nur im 0.3.0-Plan referenzierte Tabelle dorthin migrieren; dabei `default_source` als aktiv für `data export` und `default_target` als aktiv für `data import` markieren |
| `docs/cli-spec.md` §6.2 `data import` | Block REWRITE statt Extend: der heutige Kurzblock wird ersetzt, nicht um einen zweiten Import-Block ergänzt |
| `docs/design-import-sequences-triggers.md` | Status `Draft` → `Approved`; offene Entscheidungen aus §10 mit den Antworten aus §6.6/§6.7/§6.8 dieses Plans befüllen |
| `docs/roadmap.md` | Milestone 0.4.0 als „in Arbeit" markieren bei Phase A; bei jedem abgeschlossenen Phase-Schritt aktualisieren |
| `CHANGELOG.md` | `[Unreleased]`-Block schon vorhanden (im 0.3.0-Post-Release-Bump angelegt); pro Phase Einträge nachziehen |

---

## 8. Coverage-Ziele

Pro Modul gilt der Projektstandard **90%**. Für `d-migrate-cli` übernehmen
wir die 0.3.0-Ausnahme bewusst NICHT; der Runner-/Helper-Split macht die
Pfadabdeckung auch für Import und LF-013 direkt testbar.

| Modul | Ziel | Anmerkung |
|---|---|---|
| `d-migrate-core` | 90% | `DataFilter.ParameterizedClause` direkt testen |
| `d-migrate-driver-api` | 90% | DataWriter-Vertrag inline mit Stubs; `finishTable()`/`close()`-Pfad getrennt testen |
| `d-migrate-driver-postgresql` | 90% via Testcontainers | `PostgresDataWriter` + `PostgresSchemaSync` Branch-Coverage |
| `d-migrate-driver-mysql` | 90% via Testcontainers | dito; `disable`-Trigger-Pfad als Negativ-Test |
| `d-migrate-driver-sqlite` | 90% inline | `sqlite_sequence` Reseeding mit File-DB |
| `d-migrate-streaming` | 90% | `StreamingImporter` mit Fake Reader/Writer |
| `d-migrate-formats` | 90% | je Reader Golden-Master-Round-Trip mit Phase-D-Writern aus 0.3.0; `EncodingDetector` mit allen 5 BOM-Varianten plus Fallback |
| `d-migrate-cli` | 90% | `DataImportCommand`- und LF-013-Pfade direkt über Runner/Helper testen |

---

## 9. Verifikation

Vor dem 0.4.0-Release müssen die folgenden Round-Trip-Tests grün sein
(Phase F + zusätzliche Verifikations-Suite):

1. **Voll-Roundtrip pro Format pro Dialect** (3×3 = 9 Permutationen):
   `data export → data import → SELECT-Vergleich`
   - 1000 Rows, die in 0.3.0 bereits unterstützten neutralen Typen
     (Roadmap-Erweiterungen aus späteren Milestones sind hierfür kein
     Release-Gate von 0.4.0)
   - Für CSV mit nullable Text-Spalten wird im Test ein explizites
     `csvNullString` gesetzt; der Default `""` ist symmetrisch, aber nicht
     verlustfrei für `NULL` vs. leerer String
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
4. **Trigger-Modus-Verifikation**:
   - Tabelle mit `BEFORE INSERT`-Trigger, der `value = value * 2` setzt
   - PG-Fixture enthält dafür eine echte `plpgsql`-Funktion plus
     `CREATE TRIGGER`-Statement im Test-Setup, nicht nur Pseudo-SQL
   - Import mit `--trigger-mode fire`: Werte sind verdoppelt
   - Import mit `--trigger-mode disable` (PG): Werte sind unverändert
   - Import mit `--trigger-mode disable` (MySQL/SQLite): Exit 2 mit
     `UnsupportedTriggerModeException`
   - Für MySQL/SQLite reicht darüber hinaus die normale Round-Trip-Suite als
     `fire`-Pfad-Abdeckung; eine eigene Trigger-Fixture ist dort in 0.4.0
     nicht Pflicht-Gate
5. **`--truncate`-Pre-Step ist NICHT atomar** (F41 — explizit dokumentiert):
   - Tabelle mit 1000 Rows, Import mit `--truncate` und einer absichtlich
     fehlerhaften Row → der Test asserted, dass die Tabelle danach **leer**
     ist (NICHT wieder mit den 1000 Rows gefüllt). Der Test schützt damit
     das in §6.14 dokumentierte Verhalten gegen versehentliche Atomarisierung
     in einer späteren Refactoring-Runde.

---

## 10. Risiken

| Risiko | Mitigation |
|---|---|
| **Streaming-Reader leakt Speicher** bei großen Eingaben | Phase B Schritt 7: 100-MB-Heap-Sample-Test pro Reader |
| **Sequence-Reseeding race** zwischen Import-Ende und nächster INSERT | PG verwendet das normale nicht-mutierende `setval(seq, maxImported, true)`-Muster ohne `nextval()`-Side-Effect; der Plan beansprucht bewusst KEINE cross-session-Serialisierung. MySQL bleibt bei `ALTER TABLE ... AUTO_INCREMENT`. |
| **Trigger-Reaktivierung scheitert** nach Fehler im Import | Design-Doc §7.3: harter Fehler mit Tabellen-Name; `close()` versucht trotz vorheriger Exceptions die Reaktivierung, aber nie das Reseeding |
| **`--on-conflict update` ohne PK** wird stillschweigend zu Insert | Pre-Flight-Check via `DatabaseMetaData.getPrimaryKeys()`; Tabelle ohne PK + `update` → Exit 2 |
| **Encoding-Auto-Fallback verschluckt Mojibake** | Wenn `auto` ohne BOM auf UTF-8 fällt, stderr-Hinweis ausgeben; in den Tests prüfen, dass ISO-8859-1 ohne expliziten Flag mit „Decoding error"-Meldung scheitert |
| **MySQL `disable` Trigger-Pfad** wird vom User erwartet, ist aber nicht implementiert | Klare Fehlermeldung mit Verweis auf design-import-sequences-triggers.md §6.2 |
| **`--truncate` löscht produktive Daten** ohne Rückfrage | `--yes` Pflicht im non-interaktiven Modus; cli-spec.md §8 dokumentiert das schon |
| **Inkrementeller Import läuft mehrfach und führt zu doppelten UPSERTs** (= idempotent, also OK) — aber der User bekommt kein Feedback, dass nichts geändert wurde | `WriteResult.rowsInserted` vs `rowsUpdated` getrennt zählen und im Report ausgeben |
| **`ParameterizedClause` aus User-Eingabe** wird falsch parametrisiert | Strikte Identifier-Validierung für `--since-column` (gleiches Pattern wie `--tables`); `--since` wird typisiert gebunden, kein String-Concat |
| **JDBC-Treiber liefern unterschiedliche `sqlTypeName`-Strings** | `ValueDeserializer` normiert primär über JDBC-Typcodes und nutzt `sqlTypeName` nur als sekundären Dialekt-Hint; Dialekt-Tests decken PG/MySQL/SQLite explizit ab |
| **MySQL `VALUES(col)`-UPSERT-Syntax altert weg** | In 0.4.0 noch dokumentiert, aber als Follow-up in §11 behalten; neuere MySQL-Versionen bevorzugen Alias-basiertes Update |

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
