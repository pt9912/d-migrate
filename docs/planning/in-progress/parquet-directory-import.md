# AP8: Manifestgebundene Directory-Import-Aufloesung fuer Parquet

> Dokumenttyp: Architekturentscheidung zu `parquet-export-import-evaluation.md`
>
> Status: Entwurf (2026-06-05)
>
> Referenzen: `parquet-export-import-evaluation.md` Abschnitt 8
> Arbeitspaket 8 + Abschnitt 6 (Directory-Bindings-Anforderungen),
> `parquet-manifest-format.md` (AP7-Manifest-Format und
> Preflight-Vertrag), `parquet-libraries.md` (insbesondere §7.1 zum
> `SeekableDataChunkReaderFactory`-Port), `parquet-schema-source.md`
> (AP2-`ChunkSchema`), `spec/architecture.md`.

---

## 1. Ziel

Arbeitspaket 8 verlangt eine Skizze, wie das von AP7 validierte
`manifest.yaml` praktisch in die Streaming-Import-Iteration uebersetzt
wird — so, dass der Streaming-Layer nicht erneut aus Dateiendungen
oder Dateinamen inferiert (Hauptplan §6, Bullet „der Streaming-Import
bekommt die manifestseitig aufgeloeste Tabellenordnung und
Datei-Zuordnung").

Konkret klaert dieser Sub-Doc:

- wie der Aufloesungsvertrag vom `ResolvedParquetBundle` (AP7 §9.3)
  in den `StreamingImporter` uebergeht,
- wie sich `tableFilter` / `tableOrder` aus
  `ImportInput.Directory` zum manifestseitigen Datenbestand
  verhalten,
- wie `ChunkSchema` pro Tabelle aus den Manifest-Spaltenmetadaten
  konstruiert wird,
- welches Fehlerverhalten mid-stream gilt (z.B. Tabelle N OK,
  Tabelle N+1 schlaegt fehl),
- welcher Manifest-Fingerprint in den Resume-Checkpoint einfliesst,
- wie der CLI-Format-Resolver `manifest.yaml` als Directory-Indikator
  fuer Parquet erkennt,
- welche Code-Anpassungen am `ImportInputResolver` / `StreamingImporter`
  noetig werden, ohne den JSON/YAML/CSV-Pfad zu zerstoeren.

Die finale Implementierung haengt an AP9 (DTO-Wahl) und AP10
(Stream-vs-Datei-Portentscheidung — Vorentscheidung in
`parquet-libraries.md` §7.1 bereits getroffen). Dieser Sub-Doc liefert
die Resolver-Skizze.

---

## 2. Ausgangslage

Bestehende Bausteine (verifiziert via Code-Sichtung 2026-06-05):

- `hexagon/ports-write/.../streaming/ImportInput.kt`: sealed mit
  `Stdin`, `SingleFile`, `Directory`. `Directory` traegt nur
  `path`, `tableFilter`, `tableOrder` — kein Manifest-Slot.
- `adapters/driven/streaming/.../streaming/ImportInputResolver.kt`
  (`internal class`): loest `ImportInput` in eine Liste von
  `ResolvedTableInput(table: String, openInput: () -> InputStream)`
  auf. Fuer `Directory` macht er heute extension-basierte
  Inferenz (`fileExtensions` aus `DataExportFormat`), Tabellenname
  = Dateiname ohne Suffix, plus Filter-/Order-Validierung
  (`tableFilter` muss vollstaendig vertreten sein, `tableOrder` muss
  alle Kandidaten abdecken).
- `adapters/driven/streaming/.../streaming/StreamingImporter.kt`
  iteriert ueber `inputResolver.resolve(input, format)` und ruft
  pro Tabelle den `tableImporter`/`DataChunkReaderFactory`-Pfad.
- Der heutige Reader-Pfad ist strikt stream-basiert
  (`openInput: () -> InputStream`); Parquet braucht stattdessen einen
  seekbaren Pfad (`parquet-libraries.md` §7.1
  `SeekableDataChunkReaderFactory`, akzeptiert `SeekableChunkSource.Local(path)`).
- `parquet-manifest-format.md` §9.3 sagt: Preflight liefert ein
  `ResolvedParquetBundle`-DTO, der Streaming-Import iteriert
  ausschliesslich darueber. §10.2 empfiehlt fuer AP9 einen neuen
  `ResolvedParquetBundleInput`-Subtyp (vs. Erweiterung von
  `ImportInput.Directory`).
- `parquet-schema-source.md` §6 fuehrt einen `ChunkSchema`-Vertrag
  ein, der formatseitig vor dem ersten Chunk bekannt ist und
  Decimal-Precision/Scale, Temporal-Timezone usw. traegt.

Folgerung: Der bestehende `ImportInputResolver` deckt JSON/YAML/CSV
weiterhin, **kann aber Parquet nicht abbilden** — er produziert
`openInput: () -> InputStream`, was fuer Parquet nicht der richtige
Vertrag ist. AP8 muss klaeren, wie ein zweiter, paralleler
Aufloesungspfad fuer Parquet entsteht, ohne dass der vorhandene
Pfad gebrochen wird.

---

## 3. Anforderungen

Aus Hauptplan §6 und AP7 abgeleitet:

- D1 Streaming-Import iteriert pro Bundle ausschliesslich ueber die
  manifestseitig aufgeloesten Tabellen — keine Datei-Inferenz im
  Streaming-Pfad.
- D2 Tabellenordnung kommt **primaer** aus dem Manifest
  (`tables[]`-Reihenfolge); `tableOrder` aus
  `ImportInput.Directory` ist optional und darf die Manifest-
  Ordnung explizit ueberschreiben (User > Producer).
- D3 `tableFilter` aus `ImportInput.Directory` ist eine echte
  Teilmenge der Manifest-Tabellen. Tabellen im Filter, die im
  Manifest fehlen, fuehren zum Preflight-Fehler (analog zum
  heutigen `ImportInputResolver`).
- D4 ChunkSchema pro Tabelle wird aus den Manifest-
  Spaltenmetadaten konstruiert; ein zusaetzlicher `SchemaReader`-
  Aufruf auf dem Import-Ziel ist NICHT noetig
  (`schemaSource`-Hinweis im Manifest reicht).
- D5 Mid-stream-Fehler einer Tabelle brechen den gesamten
  Bundle-Import strukturell ab; bereits committete Tabellen werden
  nicht zurueckgerollt (atomic-preserve-Logik ist Ziel-DB-Sache,
  vgl. `ImpPlan-0.9.8-atomic-preserve-AE.md` im done-Pfad). Es
  gibt keinen impliziten Skip-Modus.
- D6 Resume-Checkpoint traegt einen Manifest-Fingerprint
  (Stable-Hash des `manifest.yaml`-Inhalts); Resume gegen einen
  veraenderten Manifest schlaegt mit klarer Diagnose fehl.
- D7 CLI-Auto-Detection erkennt `manifest.yaml` im Bundle-
  Verzeichnis und routet das Directory als Parquet-Bundle, sofern
  `--format` nicht explizit ein anderes Format vorgibt
  (Hauptplan §6).
- D8 Bestehende JSON/YAML/CSV-Directory-Imports bleiben funktional
  unveraendert — kein Manifest-Pflichtvertrag fuer andere Formate.

---

## 4. Aufloesungsmodell

### 4.1 Konzeptuelle Pipeline

```text
ImportInput.Directory(path)
        |
        v
 ParquetBundlePreflight     (AP7 §9.1)
        |
        v
 ResolvedParquetBundle       (Tabellenordnung,
        |                     Tabelle->Pfad,
        |                     Spaltenmetadaten pro Tabelle)
        v
 ParquetBundleResolver       (NEU, AP8)
        |
        v   (pro Tabelle)
 (table, SeekableChunkSource.Local(path), ChunkSchema)
        |
        v
 SeekableDataChunkReaderFactory.create(...)
        |
        v
 DataChunkReader  ->  TableImporter  ->  Ziel-DB
```

### 4.2 Bundle-Resolver-Vertrag

Der `ParquetBundleResolver` (Skizze, nicht-final benannt) ist eine
reine Wrapper-Klasse um `ResolvedParquetBundle`:

```text
class ParquetBundleResolver(
    private val bundle: ResolvedParquetBundle,
    private val tableFilter: Set<String>?,
    private val tableOrder: List<String>?,
) {
    fun resolve(): List<ParquetTableBinding>
}
```

Bewusst eine `List`-Rueckgabe, kein `Iterable`/`Sequence`/Iterator:

- Der `StreamingImporter` braucht `tables.size` vor dem ersten
  Tabellenimport, damit `ProgressReporter.RunStarted.totalTables`
  korrekt befuellt wird (vgl.
  `adapters/driven/streaming/.../StreamingImporter.kt`, der heute
  `discoveredInputs.size` an exakt dieser Stelle nutzt).
- Der Bundle-Inhalt ist nach dem Preflight (§9.1 AP7) **bereits
  vollstaendig aufgeloest** — eine Lazy-Sequence wuerde nichts
  einsparen.
- `List` ist sowohl mehrfach iterierbar als auch indexierbar;
  damit ist sie strikt allgemeiner als ein one-shot `Iterable`.

`ParquetTableBinding` ist ein einfacher Record:

```text
data class ParquetTableBinding(
    val table: String,
    val source: SeekableChunkSource.Local,  // file path
    val schema: ChunkSchema,                 // aus Manifest-Spalten
    val expectedSha256: String?,             // optional, AP7 §7
)
```

Der Resolver liefert die Bindings **in einem Schritt** nach
Filter-/Order-Auflösung; der `StreamingImporter` iteriert linear
ueber die zurueckgegebene `List`.

### 4.3 Eigenschaften des Resolvers

- Aufloesung ist deterministisch in der Ordnung aus §4.4.
- Keine I/O in `resolve()`; der Preflight hat alle Filesystem-
  Pruefungen bereits gemacht.
- `resolve()` ist seiteneffektfrei und kann mehrfach aufgerufen
  werden — semantisch ist es ein reiner Builder ueber dem
  Bundle und den Filter-/Order-Parametern. In der Praxis ruft
  der `StreamingImporter` es pro Importlauf genau einmal auf,
  cached das Ergebnis lokal und uebergibt die Liste an den
  Progress-Reporter plus die Per-Tabellen-Iteration.
- Keine Lazyness gegenueber dem Preflight: der gesamte Bundle-
  Inhalt steht zum Aufloesungs-Start bereits aufgeloest da.
  Lazy-Optionen sind kein AP8-Scope (würden den Preflight-
  Determinismus aus AP7 §9 brechen).

### 4.4 Tabellenordnung im Resolver-Output

Reihenfolge der Aufloesung:

1. Wenn `tableOrder` explizit gesetzt: nutze `tableOrder` exakt
   (D2). Vorab-Pruefung: keine Duplikate (`tableOrder` ist eine
   Liste), alle Eintraege sind im (gefilterten) Manifest-Bestand
   enthalten, der Filter-Bestand ist vollstaendig abgedeckt — alle
   drei Pruefungen entsprechen 1:1 dem bestehenden
   `ImportInputResolver`-Verhalten fuer JSON/YAML/CSV-Directories.
2. Sonst: nutze die Manifest-`tables[]`-Reihenfolge (D2), gefiltert
   durch `tableFilter` (D3). Reihenfolge der gefilterten Tabellen
   bleibt die Manifest-Reihenfolge (kein Re-Sort).

Anders als beim heutigen JSON/YAML/CSV-Resolver gibt es keinen
Default-Sort nach Tabellenname — die Manifest-Reihenfolge ist der
intentional ausgesprochene Default. Ein Default-Sort wuerde
implizit die Producer-Intention ueberschreiben.

---

## 5. Interaktion mit `tableFilter` / `tableOrder`

### 5.1 Bewusste Carve-outs zum JSON/YAML/CSV-Resolver

Der bestehende `ImportInputResolver` hat zwei Validierungen, die fuer
Parquet **nicht analog** uebernommen werden:

- (a) „`tableFilter` referenziert Tabellen ohne passende Dateien":
  beim Parquet-Bundle wird diese Pruefung gegen den Manifest-
  Bestand gemacht, **nicht** gegen ein Verzeichnis-Listing.
  Verzeichnis-Listings haben im Preflight bereits stattgefunden
  (M13 / K5 — unbekannte `.parquet`-Dateien wurden abgelehnt).
- (b) „`tableOrder` muss alle Kandidaten abdecken": bleibt fuer
  Parquet bestehen, weil der Resolver-Output deterministisch sein soll und
  ein partieller `tableOrder` einen impliziten Anhang in
  Manifest-Reihenfolge erzeugen wuerde — das waere weniger
  vorhersehbar als ein Fehler. Schreiber, die nur eine Teilmenge
  wollen, setzen `tableFilter` plus `tableOrder` gemeinsam.

### 5.2 Validierungsfehler

Die Validierungen aus §4.4 / §5.1 produzieren **neue, parquet-
spezifische** Fehlerklassen, die sich von den AP7-§9.2-Codes
unterscheiden (jene sind Preflight-/Manifest-Fehler; diese sind
Iterator-/Filter-Fehler):

| Code | Bedeutung |
| ---- | --------- |
| `BUNDLE_FILTER_UNKNOWN_TABLE` | `tableFilter` referenziert eine Tabelle, die nicht im Manifest steht. |
| `BUNDLE_ORDER_DUPLICATE` | `tableOrder` enthaelt eine Tabelle mehrfach. |
| `BUNDLE_ORDER_UNKNOWN_TABLE` | `tableOrder` referenziert eine Tabelle, die nicht im (gefilterten) Manifest-Bestand ist. |
| `BUNDLE_ORDER_INCOMPLETE` | `tableOrder` deckt nicht alle (gefilterten) Tabellen ab. |

CLI-Mapping (Exit-Codes, Wortlaut) ist AP12.

---

## 6. ChunkSchema-Aufbau pro Tabelle

### 6.1 Quellen im Manifest

Pro `tables[i].columns[j]`-Eintrag stehen laut AP7 §5.2 zur
Verfuegung:

- `name` (Pflicht)
- `nullable` (Pflicht)
- `neutralType` (optional, Map mit `kind`-Diskriminator)
- `sqlTypeName` (optional)
- `jdbcType` (optional, `java.sql.Types`-Konstante)
- `precision` / `scale` (optional, fuer Decimal)
- `timezone` (optional, fuer Temporal)

### 6.2 Konstruktionsregel

Wichtig: `ChunkColumnSchema` aus `parquet-schema-source.md` §4.4 ist
explizit auf `(name, nullable, neutralType)` festgelegt. JDBC- und
SQL-Hints sind **bewusst nicht Teil** des neutralen Schema-Modells —
sie wuerden den Port-Schnitt JDBC-spezifisch machen und Option D der
AP2-Vorentscheidung verletzen.

Der Builder zieht die Hints deshalb nur als **Eingaben** in die
NeutralType-Ableitung; sie werden danach verworfen und nicht in
`ChunkColumnSchema` persistiert.

```text
ParquetTableBinding.schema = ChunkSchema(
    table = tables[i].table,
    columns = tables[i].columns.map { c ->
        val neutralType = c.neutralType?.toNeutralType()
            ?: deriveFromJdbcHint(c.jdbcType, c.sqlTypeName,
                                  c.precision, c.scale, c.timezone)
            ?: NeutralType.fallbackFromSqlTypeName(c.sqlTypeName)
            ?: throw BundleSchemaError(
                code = BUNDLE_SCHEMA_UNRESOLVED,
                column = c.name,
            )
        ChunkColumnSchema(
            name = c.name,
            nullable = c.nullable,
            neutralType = neutralType,
        )
    },
    origin = when (manifest.schemaSource) {
        "schema-reader" -> SchemaOrigin.SCHEMA_READER
        "jdbc-metadata" -> SchemaOrigin.JDBC_METADATA
        "manifest-fallback" -> SchemaOrigin.MANIFEST_FALLBACK
    },
)
```

(`ChunkSchema`/`ChunkColumnSchema`/`SchemaOrigin` aus
`parquet-schema-source.md` §4.4. `NeutralType.fallbackFromSqlTypeName`
und `deriveFromJdbcHint` sind interne Builder-Helfer im
Parquet-Adapter, nicht Teil eines Ports.)

**AP2-Erweiterung erforderlich.** Der `SchemaOrigin`-Enum in
`parquet-schema-source.md` §4.4 traegt heute nur
`JDBC_METADATA`/`SCHEMA_READER`/`MERGED`. `MERGED` waere fuer
`manifest-fallback` semantisch falsch (es bedeutet „aus mehreren
Quellen kombiniert", nicht „best-effort"). AP8 schlaegt deshalb
eine vierte Enum-Variante `MANIFEST_FALLBACK` vor; die
AP2-Aktualisierung wird beim AP9-Abschluss mit-gezogen und ist
in §11 als offener Punkt notiert.

Reihenfolge der Quellen:

1. Explizites `neutralType` im Manifest (verlustfreieste Quelle).
2. Aus JDBC-Hint-Tupel `(jdbcType, sqlTypeName, precision, scale,
   timezone)` ableitbar (`parquet-schema-source.md` §8
   Mapping-Tabelle). Die Hint-Felder gehen als Funktionsparameter ein,
   landen aber nicht im Ergebnistyp.
3. Fallback aus `sqlTypeName` per Heuristik (z.B. „varchar*" ->
   `Varchar`); auch hier wird `sqlTypeName` nur als Eingabe genutzt.
4. Fehler `BUNDLE_SCHEMA_UNRESOLVED`, weil die Importseite das
   ohne raten nicht legal abbilden kann.

Folge fuer den Importpfad: Wenn der Ziel-Writer doch JDBC-Hints
braucht (z.B. fuer dialektspezifisches `INSERT`-Typkasting), holt er
sie aus dem **Ziel-Schema** (`ResultSetMetaData` der Ziel-Tabelle),
nicht aus dem Manifest. Das ist konsistent zum bestehenden
Import-Pfad fuer JSON/YAML/CSV.

### 6.3 Konsistenz mit AP2

`parquet-schema-source.md` §6.2 hat fuer den Writer-Pfad
festgelegt, dass `schemaSource = schema-reader` immer `neutralType`
liefert. Der Importpfad kann sich also bei AP2-konformen Bundles auf
Schritt 1 (Explizit-Quelle) verlassen. Schritte 2 und 3 sind
Toleranzpfade fuer Bundles aus `schemaSource = jdbc-metadata` oder
`schemaSource = manifest-fallback`.

---

## 7. Fehlerverhalten mid-stream

### 7.1 Strikte Variante (gewaehlt)

Mid-stream-Fehler einer Tabelle (z.B. korrupter Footer, Schema-
Mismatch gegen Ziel-Schema, I/O-Fehler) brechen den gesamten
Bundle-Import an dieser Stelle ab. Tabellen, die bereits committet
wurden, bleiben in der Ziel-DB; Tabellen nach dem Fehlerort werden
nicht versucht.

Begruendung:

- **Konsistent mit dem bestehenden `StreamingImporter`**: er bricht
  heute beim Per-Table-Fehler ebenfalls ab (`require(...)` /
  Exceptions propagieren). Es waere unintuitiv, fuer Parquet einen
  Skip-Modus einzufuehren, der ausserhalb von Parquet nicht
  existiert.
- **Atomic-Preserve-Logik gehoert der Ziel-DB**, nicht dem
  Bundle-Adapter (vgl. `ImpPlan-0.9.8-atomic-preserve-AE.md` im
  done-Pfad). Ein Bundle-weiter Rollback wuerde transactional
  Eigenschaften des Imports nachbilden, die der Streaming-Layer
  nicht hat.
- **Diagnose-Praezision**: ein hartes Abbrechen liefert klar, welche
  Tabelle den Fehler ausgeloest hat. Ein Skip-Modus haette
  „mehrere Tabellen rot, einige committed" als Defaultausgang,
  was die Pilot-Bewertung erschwert.

### 7.2 Verworfene Varianten

- **Best-effort-Skip**: ueberlaufender CSV-Style-Tolerieren passt
  nicht zur Parquet-Bundle-Erwartung „kuratiertes Artefakt".
- **Bundle-weiter Rollback**: setzt Cross-Table-Transaktionen
  voraus, die in keinem aktuellen Adapter existieren.

### 7.3 Fehlerklasse

```
BUNDLE_TABLE_IMPORT_FAILED { table, cause }
```

Wraps die ursaechliche Exception aus `TableImporter`. CLI-Exit-Code
und Wortlaut sind AP12.

---

## 8. Resume- und Checkpoint-Fingerprint

Hauptplan §6 verlangt: „Resume-/Checkpoint-Fingerprints muessen
Parquet-spezifische Output-Regeln, Manifestversion und ggf.
Row-Group-/Writer-Optionen einbeziehen."

### 8.1 Eckpfeiler: Datei-Hashes sind Resume-Voraussetzung

Resume ueber Bundle-Grenzen hinweg setzt voraus, dass der Importer
bei Wiederaufnahme erkennen kann, ob eine **einzelne**
Parquet-Datei seit dem Checkpoint veraendert wurde. Ein reiner
`manifest.yaml`-Hash reicht dafuer **nicht**:

- `tables[].sha256` ist laut AP7 §7.1 optional, sogar Mischbetrieb
  pro Bundle ist erlaubt.
- Wenn `sha256` fehlt, kann eine Parquet-Datei zwischen Checkpoint
  und Resume ausgetauscht werden, ohne dass sich `manifest.yaml`
  oder dessen Hash aendert.
- Mid-table-Resume (eine Tabelle nach Chunk K weiterlaufen lassen)
  wuerde dann Chunks im falschen Datei-Inhalt ueberspringen — der
  Importer haette keine Chance, das zu erkennen.

Konsequenz: **Resume ist nur erlaubt, wenn fuer jede Tabelle im
Resume-Scope ein `sha256` vorliegt.** Bundles ohne Datei-Hashes
sind im normalen Import (ohne `--resume`) weiterhin voll
unterstuetzt; sie verzichten lediglich auf Resume-Faehigkeit.

### 8.2 Zwei Pruefungen, klare Trennung

Resume macht zwei verschiedene Hash-Pruefungen, die **nicht**
verwechselt werden duerfen:

- (P1) **Live-Datei-Integritaet**: vor dem Mid-Table-Resume wird
  fuer jede Datei im Resume-Scope der **live berechnete** SHA-256
  gegen `tables[].sha256` aus dem aktuellen `manifest.yaml`
  verglichen. Das ist exakt der AP7-Preflight-Check aus
  `parquet-manifest-format.md` §7.4. Im Normal-Import ist er
  opt-in; im Resume-Modus ist er **zwangsweise** aktiv (sonst
  koennte eine ausgetauschte Datei nicht erkannt werden).
  Mismatch ist `MANIFEST_SHA256_MISMATCH` (AP7-Code, kein neuer
  AP8-Code).
- (P2) **Manifest-Stabilitaet seit Checkpoint**: die im Checkpoint
  persistierten Fingerprint-Felder (`manifestSha256`,
  `formatVersion`, `tableOrder`) werden gegen die heutigen
  Manifest-Werte verglichen, um zu erkennen, ob jemand das
  `manifest.yaml` zwischen Checkpoint und Resume editiert hat
  (z.B. Tabelle entfernt, `sha256`-Werte nachgepflegt,
  Tabellen-Reihenfolge geaendert). Mismatch ist AP8-Code (siehe
  §8.4).

Folge: `fileSha256ByTable` als separates Checkpoint-Feld waere
**redundant** — der `manifestSha256` deckt alle
`tables[].sha256`-Werte implizit ab (jede Datei-Hash-Aenderung im
Manifest aendert den Manifest-Bytestrom). `fileSha256ByTable` wird
deshalb **nicht** in den Fingerprint aufgenommen.

### 8.3 Fingerprint-Inhalt

Der Resume-Checkpoint traegt fuer Bundle-Importe:

- `manifestSha256`: SHA-256 ueber den `manifest.yaml`-Bytestrom auf
  Disk (analog zu AP7 §7.2: gehashed wird die Disk-Datei, nicht
  irgendeine kanonisierte Repraesentation).
- `formatVersion`: aus dem Manifest, fuer Schema-Brueche.
- `producerVersion`: aus dem Manifest, informativ — nicht
  funktional ausgewertet, aber fuer Diagnose im
  `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`-Fehler hilfreich.
- `tableOrder`: die effektive, vom Resolver (§4.4) aufgeloeste
  Reihenfolge — nicht nur die Manifest-Reihenfolge, weil ein
  `tableOrder`-Override den Resume-Punkt definiert.

### 8.4 Resume-Verhalten

- (P1-Pre-Check) Alle Tabellen im Resume-Scope haben einen
  `sha256`-Wert im Manifest. Fehlt einer, ist Resume strukturell
  nicht moeglich (eine Datei-Aenderung waere unerkannt) — Fehler
  `BUNDLE_RESUME_REQUIRES_FILE_HASHES`.
- (P1) AP7-Preflight-SHA256-Verifikation laeuft zwangsweise: ueber
  jede Resume-Datei wird live ein SHA-256 berechnet und gegen
  `tables[].sha256` verglichen. Mismatch ist AP7-Code
  `MANIFEST_SHA256_MISMATCH`.
- (P2) Beim Resume wird der `manifest.yaml`-Inhalt erneut gehashed
  und gegen den im Checkpoint persistierten `manifestSha256`
  verglichen. Mismatch ist `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`;
  Resume wird abgelehnt.
- (P2) Mismatch in `formatVersion` MAJOR ist Fehler
  `BUNDLE_FORMAT_VERSION_INCOMPATIBLE_WITH_CHECKPOINT`.
- (P2) Mismatch in `tableOrder` (geaenderter Resolver-Output
  gegenueber Checkpoint) ist Fehler `BUNDLE_TABLE_ORDER_CHANGED`.

### 8.5 Was im Checkpoint NICHT steht

- Row-Group-/Writer-Optionen: betreffen den Writer-Pfad, nicht den
  Reader-Pfad. Der Reader liest, was tatsaechlich in der Datei
  steht.
- Spaltenmetadaten: bereits durch `manifestSha256` gedeckt (eine
  Spaltendefinition aendert sich nicht ohne Manifest-Aenderung).

### 8.6 CLI-Auswirkung

Der CLI muss dem Operator klarmachen, dass `--resume` ohne
manifestseitige Datei-Hashes nicht funktioniert. Vorgeschlagene
Diagnose: „bundle has no `tables[].sha256`; either export with
`--manifest-sha256` or omit `--resume`." Exit-Code-Mapping und
Wortlaut sind AP12.

---

## 9. Format-Autodetection und CLI-Sichtbarkeit

### 9.1 Voraussetzung: `DataExportFormat.PARQUET`

Der heutige Format-Port `hexagon/ports-common/.../data/
DataExportFormat.kt` kennt nur `JSON`/`YAML`/`CSV` mit ihren
Cli-Namen und Dateiendungen. Die hier beschriebene Autodetection
und die CLI-Spiegelung aus `parquet-libraries.md` §7
(`--format parquet`) brauchen also als **harte Voraussetzung**:

- Neuer Enum-Eintrag `PARQUET("parquet", listOf("parquet"))` in
  `DataExportFormat`. `DataExportFormat.fromCli("parquet")` muss
  auflösen.
- Cli-Wiring (Clikt-Choices in den `data export`-/`data import`-
  Commands) muss `parquet` zulassen — heute lehnt es das
  schlicht ab.
- `DefaultDataChunkWriterFactory` und `DefaultDataChunkReaderFactory`
  muessen `PARQUET` aufloesen koennen; Reader-seitig zeigt der
  Lookup auf `SeekableDataChunkReaderFactory` statt auf den
  `InputStream`-Pfad (vgl. `parquet-libraries.md` §7.1).

Diese Erweiterung gehoert eigentlich AP12 (CLI- und Factory-
Wiring-Skizze), wird hier aber explizit als Vorbedingung
festgenagelt, damit die Autodetection-Regel unten konsistent
implementierbar ist.

### 9.2 `resolveFormat`-Hook fuer Directory-Manifest-Erkennung

`hexagon/application/.../DataImportHelpers.resolveFormat` mappt
heute ausschliesslich Text-Endungen
(`EXTENSION_FORMAT_MAP = json|yaml|yml|csv`). Fuer Parquet-Bundles
muss vor `inferFormatFromExtension` ein neuer Schritt eingezogen
werden:

```text
fun resolveFormat(request, isStdin, sourcePath, stderr): DataExportFormat? {
    request.format?.let { return DataExportFormat.fromCli(it) }

    if (sourcePath != null && Files.isDirectory(sourcePath)
        && Files.isRegularFile(sourcePath.resolve("manifest.yaml"))) {
        return DataExportFormat.PARQUET
    }

    return sourcePath?.let(::inferFormatFromExtension)
        ?.let(DataExportFormat::fromCli)
}
```

Reihenfolge:

1. explizites `--format` gewinnt.
2. sonst: Directory mit `manifest.yaml` -> `PARQUET`.
3. sonst: Endungs-Inferenz wie heute.
4. sonst: `null` -> heutiger CLI-Fehlerpfad.

### 9.3 Auto-Detection-Regel

- `data import --source <dir>` ohne explizites `--format` und mit
  vorhandenem `<dir>/manifest.yaml` wird automatisch als
  `--format parquet` interpretiert (§9.2 Schritt 2).
- `data import --source <dir> --format parquet` erzwingt den
  Parquet-Pfad auch ohne `manifest.yaml`, schlaegt dann aber im
  Preflight mit `MANIFEST_NOT_FOUND` (AP7 §9.2) fehl.
- `data import --source <dir> --format json` ignoriert `manifest.yaml`
  vollstaendig und nutzt den heutigen JSON-Resolver. Damit bleibt
  D8 (JSON/YAML/CSV unveraendert) gewahrt.

### 9.4 Konflikt mit `manifest.yaml`-aehnlichen Dateinamen

Andere Formate koennten zufaellig eine `manifest.yaml` im
Verzeichnis haben (z.B. ein YAML-Bundle, das ohnehin keine
Dateiendung benoetigt). Konfliktregel:

- Wenn `--format` explizit gesetzt ist: der `manifest.yaml`-Check
  wird uebersprungen; das Format gewinnt (§9.2 Schritt 1).
- Wenn `--format` NICHT gesetzt und `manifest.yaml` existiert: ein
  Parquet-Bundle-Preflight wird versucht. Schlaegt der Preflight
  fehl (z.B. `MANIFEST_PARSE_ERROR`), wird **kein** Fallback auf
  YAML-Format gemacht; der CLI bricht mit klarer Diagnose ab. Ein
  stillschweigender Fallback wuerde D8 verletzen, weil der User
  in dem Fall vermutlich tatsaechlich Parquet erwartet hat (sonst
  haette er `--format yaml` gesetzt).

### 9.5 Checkpoint-Diagnose

Der CLI gibt beim Resume an, welcher Hash gebrochen ist, damit
Operatoren erkennen koennen, wo die Veraenderung liegt:

- AP7-Preflight (`MANIFEST_SHA256_MISMATCH`): „Parquet file
  public.orders.parquet does not match manifest sha256: expected
  9b74…, got c8d1…" — Datei wurde nach Manifest-Erstellung
  veraendert.
- AP8-Checkpoint (`BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`):
  „Bundle manifest differs from checkpoint: manifestSha256
  a3f5… → c1e2…" — Manifest wurde nach Checkpoint editiert.

---

## 10. Konsequenzen fuer Code

### 10.1 `hexagon/ports-write/.../streaming/ImportInput.kt`

AP9 entscheidet bindend zwischen (a) Erweiterung von
`ImportInput.Directory` und (b) einem neuen Subtyp. AP8 empfiehlt
(b), aber mit einer architektonischen Praezisierung:
`hexagon:ports-write` darf nicht von `adapters:driven:formats-parquet`
abhaengen (vgl. `spec/architecture.md` zur Hexagon-Schichtung). Das
heisst: der neue Subtyp darf **keine** Adapter-Klassen wie
`ResolvedParquetBundle` referenzieren. Stattdessen lebt im Port ein
Adapter-neutrales DTO:

```text
package dev.dmigrate.streaming   // hexagon:ports-write

data class ResolvedBundleTableBinding(
    val table: String,
    val path: java.nio.file.Path,
    val schema: ChunkSchema,          // ports-common
    val expectedSha256: String?,
)

sealed class ImportInput {
    ...
    data class ResolvedBundle(
        val tables: List<ResolvedBundleTableBinding>,
        val resumeFingerprint: BundleResumeFingerprint,
    ) : ImportInput()
}

data class BundleResumeFingerprint(
    val manifestSha256: String,
    val formatVersion: String,
    val producerVersion: String,                 // Diagnose, siehe §8.3
    val tableOrder: List<String>,
    // fileSha256ByTable bewusst NICHT enthalten — siehe §8.2:
    // Datei-Aenderungen werden ueber den live-berechneten AP7-
    // Preflight gegen Manifest-tables[].sha256 erkannt
    // (MANIFEST_SHA256_MISMATCH), nicht ueber den Checkpoint.
)
```

Der Subtyp heisst bewusst `ResolvedBundle` (nicht
`ResolvedParquetBundleInput`), weil im Port der Begriff „Parquet"
nicht auftauchen muss — der Vertrag ist Pfad + ChunkSchema +
Resume-Fingerprint, nichts Parquet-spezifisches. Damit ist der Port
strukturell offen fuer kuenftige Bundle-Formate (z.B. ein
Arrow-IPC-Bundle), bleibt aber im aktuellen Schnitt
parquet-exklusiv.

Der Adapter `adapters:driven:formats-parquet` haelt sein eigenes,
reichhaltigeres `ResolvedParquetBundle` (mit allen
Manifest-Spalteninfos) intern; der `ParquetBundlePreflight`
**uebersetzt** beim Eintritt in den Streaming-Layer in das port-
eigene `ImportInput.ResolvedBundle`. Die port-seitige DTO traegt
nur das, was der Streaming-Layer tatsaechlich braucht.

Argumente fuer den Subtyp gegenueber einer Erweiterung von
`Directory`:

- Der Bundle-Vertrag traegt mehr Information (Tabellenbindings +
  Resume-Fingerprint) als ein generisches Directory-Konstrukt
  sinnvoll mitfuehren kann.
- Magic-Felder auf `Directory` (`bundleManifest: ...?`) wuerden
  den Sealed-Vertrag der bestehenden Variante semantisch unsauber
  machen.
- Format-Auto-Detection (§9.1) routet sowieso schon im Resolver,
  bevor der Streaming-Layer den Input sieht.

### 10.2 `adapters/driven/streaming/.../streaming/ImportInputResolver.kt`

Heutige Klasse bleibt fuer JSON/YAML/CSV unveraendert. Bundle-
Imports bekommen einen **parallelen** Aufloesungspfad:

```text
when (input) {
    is ImportInput.Stdin, is ImportInput.SingleFile,
    is ImportInput.Directory -> /* heutiger Pfad */
    is ImportInput.ResolvedBundle -> resolveBundle(input)
}
```

`resolveBundle` produziert nicht
`ResolvedTableInput(table, openInput)`, sondern eine zweite
Variante mit Pfad statt Stream — vermutlich
`ResolvedTableInput.Seekable(table, source, schema, expectedSha256)`.

Wichtig: `ImportInput.ResolvedBundle` ist bereits vom CLI-/Resolver-
Pfad (siehe §10.6) erzeugt worden. Der Parquet-Adapter, der den
`ParquetBundlePreflight` und das `ResolvedParquetBundle`-Adapter-DTO
fuehrt, wird im Resolver hier NICHT aufgerufen — die Adapter-Logik
endet beim Bau des port-neutralen `ImportInput.ResolvedBundle`.

### 10.3 `adapters/driven/streaming/.../streaming/StreamingImporter.kt`

`TableImporter` muss zwei Reader-Pfade kennen:

- `DataChunkReaderFactory.create(InputStream, ...)` fuer
  JSON/YAML/CSV (heutiger Pfad).
- `SeekableDataChunkReaderFactory.create(SeekableChunkSource, ChunkSchema, ...)`
  fuer Parquet (vgl. `parquet-libraries.md` §7.1).

Die Verzweigung lebt im `TableImporter`, nicht im Aufrufer.
`StreamingImporter` selbst bleibt format-agnostisch.

### 10.4 `adapters/driven/formats-parquet`

Neue Klassen (zusaetzlich zu AP7 §10.1):

- `ParquetBundleResolver` — Aufloesungs-Wrapper aus §4.2
  (liefert `List<ParquetTableBinding>` im Adapter-Modul; die
  AP9-Translation in `ImportInput.ResolvedBundle.tables` ist
  trivial).
- `ChunkSchemaBuilder` — Manifest-zu-`ChunkSchema`-Konstruktor
  aus §6.2.
- `ParquetBundleAdapter` — uebersetzt das interne
  `ResolvedParquetBundle` (AP7) in das port-eigene
  `ImportInput.ResolvedBundle` (§10.1). Dieser Uebersetzer ist
  die Stelle, an der das adapterseitige Manifest-Modell endet
  und der port-eigene Vertrag beginnt.

Alle drei leben im Parquet-Modul, weil sie auf
`ResolvedParquetBundle` (AP7) und `ChunkSchema` (AP2) zugreifen.
Das port-eigene `ResolvedBundleTableBinding` aus §10.1 traegt
selbst keinen Parquet-Begriff und bleibt im Hexagon-Port.

### 10.5 Checkpoint-Persistenz (Bundle-Resume-Fingerprint)

Der `BundleResumeFingerprint` aus §10.1 ist im
`hexagon:ports-write`-Modul definiert, muss aber vom existierenden
Checkpoint-Store persistiert werden. Stand 2026-06-05 fehlt
dafuer das Code-Wiring; AP8 nennt es hier konkret als
Vorbedingung fuer eine Implementierung nach AP12.

Vorhandene Bausteine in d-migrate (verifiziert via Code-Sichtung):

- `hexagon/ports-write/.../streaming/checkpoint/CheckpointManifest.kt`
  haelt bereits eine sealed interface
  `CheckpointOperationSpecifics` als Erweiterungspunkt
  (`val operationSpecific: CheckpointOperationSpecifics? = null`).
  Sie hat **noch keine konkrete Implementierung** — Parquet-
  Bundles waeren der erste Use Case.
- `adapters/driven/streaming/.../streaming/checkpoint/FileCheckpointStore.kt`
  `toMap`/`fromMap` (Z. 150 ff.) serialisiert das `operationSpecific`-
  Feld heute **nicht**; nur `schemaVersion`/`operationId`/
  `operationType`/`createdAt`/`updatedAt`/`format`/`chunkSize`/
  `optionsFingerprint`/`tableSlices` landen im YAML-Manifest.
- `hexagon/application/.../cli/commands/ImportCheckpointManager.kt`
  `writeInitialManifest` (Z. 166 ff.) baut den `CheckpointManifest`
  ohne `operationSpecific`-Argument.

AP8 fordert deshalb fuer die Implementierung nach AP12:

1. Neue Klasse `BundleCheckpointSpecifics(
   val fingerprint: BundleResumeFingerprint
   ) : CheckpointOperationSpecifics` im `hexagon:ports-write`-
   Modul. Bewusst Parquet-frei im Klassennamen, konsistent zur
   §10.1-Entscheidung fuer `ImportInput.ResolvedBundle`: der Port
   spricht nur „Bundle", der Adapter befuellt das mit
   Parquet-spezifischer Information. Der `kind`-Diskriminator im
   YAML (siehe Schritt 2) darf `"parquet-bundle"` heissen — das
   ist ein adapter-spezifischer Inhaltswert, nicht der Port-
   Klassenname.
2. `FileCheckpointStore.toMap` erweitern um den Block
   `operationSpecific: { kind: "parquet-bundle", manifestSha256: ...,
   formatVersion: ..., producerVersion: ..., tableOrder: [...] }`.
   Der `kind`-Diskriminator ist Pflicht, damit der `fromMap`-
   Parser entscheidet, welche `CheckpointOperationSpecifics`-
   Variante er instanziiert (auch wenn aktuell nur eine
   existiert — Format-Stabilitaet fuer kuenftige Varianten).
3. `FileCheckpointStore.fromMap` erweitern um den Parser-Pfad,
   inkl. Validierung der Pflichtfelder. Fehlt `operationSpecific`
   im YAML, ist das in `fromMap` kein Fehler (Backward-Compat zu
   pre-AP8-Checkpoints) — der Manifest laedt sauber mit
   `operationSpecific = null`. **Erst beim Bundle-Resume** prueft
   der `ImportCheckpointManager`, ob ein
   `BundleCheckpointSpecifics` vorliegt. Fehlt es bei einem
   Bundle-Import-Resume, ist es ein eigener Fehlerfall:
   `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT`. Dieser Code
   ist semantisch verschieden von
   `BUNDLE_RESUME_REQUIRES_FILE_HASHES` (das prueft das aktuelle
   Manifest, nicht den Checkpoint) und von
   `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT` (das vergleicht
   gespeicherten gegen aktuellen `manifestSha256`, was ohne
   gespeicherten Fingerprint ohnehin nicht moeglich ist). Der
   neue Code adressiert exakt die „pre-AP8-Checkpoint trifft
   AP8-Bundle"-Konstellation.
4. `ImportCheckpointManager.writeInitialManifest`-Signatur und
   -Aufrufer um den `BundleResumeFingerprint`-Parameter ergaenzen
   (nullable, weil JSON/YAML/CSV-Imports ihn nicht haben). Bei
   Parquet-Bundle-Imports baut der CLI-Resolver den Fingerprint
   aus dem `ParquetBundlePreflight`-Ergebnis und reicht ihn
   durch.
5. Beim Resume liest `ImportCheckpointManager` den
   `CheckpointOperationSpecifics` aus dem geladenen Manifest, prueft
   gegen den frisch berechneten Fingerprint des Bundles (§8.4 P2)
   und reicht das Ergebnis an den `StreamingImporter` weiter.

Schema-Version-Bump: `CheckpointManifest.CURRENT_SCHEMA_VERSION`
ist heute `2`. Die Aufnahme von `operationSpecific` in den
YAML-Bytestrom **kann** einen Bump auf `3` rechtfertigen — die
Entscheidung haengt davon ab, ob pre-AP8-Checkpoints lesbar
bleiben sollen. AP8-Vorschlag: keinen Bump, weil das Feld
optional bleibt und alte Checkpoints lesbar bleiben. Pre-AP8-
Checkpoints werden erst beim Bundle-Resume strukturell abgelehnt
(`BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` aus dem Schritt 3
oben); fuer JSON/YAML/CSV-Importe bleiben sie voll funktional.
AP12 trifft die endgueltige Entscheidung beim Wiring.

### 10.6 `adapters/driving/cli`

- Format-Auto-Detection-Regel (§9) im Import-CLI implementieren.
- Mapping der neuen Fehlerklassen auf Exit-Codes — AP12:
  - `BUNDLE_FILTER_UNKNOWN_TABLE`, `BUNDLE_ORDER_DUPLICATE`,
    `BUNDLE_ORDER_UNKNOWN_TABLE`, `BUNDLE_ORDER_INCOMPLETE` (§5.2),
  - `BUNDLE_SCHEMA_UNRESOLVED` (§6.2),
  - `BUNDLE_TABLE_IMPORT_FAILED` (§7.3),
  - `BUNDLE_RESUME_REQUIRES_FILE_HASHES`,
    `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`,
    `BUNDLE_FORMAT_VERSION_INCOMPATIBLE_WITH_CHECKPOINT`,
    `BUNDLE_TABLE_ORDER_CHANGED` (§8.4),
  - `BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT` (§10.5,
    pre-AP8-Checkpoint trifft AP8-Bundle).
  Datei-Aenderungen werden bewusst nicht ueber einen AP8-Code
  signalisiert, sondern ueber den AP7-Code
  `MANIFEST_SHA256_MISMATCH` aus `parquet-manifest-format.md` §9.2
  (siehe §8.2 zur Begruendung).

---

## 11. Offene Punkte fuer Folge-APs

- **AP9**: bindende Wahl zwischen `ImportInput.ResolvedBundle`-
  Subtyp (von AP7 und AP8 empfohlen; Begriff Parquet-frei, port-
  intern, mit Adapter-Translator) und Erweiterung von
  `ImportInput.Directory`. Inkl. konkretem Kotlin-Skelett der DTOs
  `ResolvedBundleTableBinding` und `BundleResumeFingerprint`.
- **AP10**: Stream-vs-Datei-Portentscheidung — Vorentscheidung in
  `parquet-libraries.md` §7.1 steht; AP10 fixiert das endgueltig
  und beschreibt die exakte
  `SeekableDataChunkReaderFactory`-Signatur (inkl. ggf.
  `ChunkSchema`-Parameter aus §6.2 hier).
- **AP11**: Single-File-Importe sind in diesem Sub-Doc nicht
  abgedeckt. Falls AP11 einen Sidecar `<datei>.manifest.yaml`
  einfuehrt, sollte er strukturell als trivialer Spezialfall des
  Bundle-Resolvers (genau ein `tables[]`-Eintrag) implementiert
  werden, damit der `ChunkSchemaBuilder` aus §6.2 wiederverwendet
  werden kann.
- **AP2-Korrektur** (Trigger fuer AP9-Abschluss):
  `SchemaOrigin`-Enum in `parquet-schema-source.md` §4.4 um
  `MANIFEST_FALLBACK` erweitern, damit §6.2-Mapping
  `manifest-fallback -> MANIFEST_FALLBACK` semantisch sauber ist.
- **AP12**: konkretes CLI-Wiring, Auto-Detection-Implementierung,
  Fehler-zu-Exit-Code-Mapping, Manifest-Hash-Algorithmus
  (Bytestrom-Definition fuer `manifestSha256` — analog zu
  AP7 §7.2: gehasht wird der `manifest.yaml`-Bytestrom auf Disk).
- **Partitionierte Bundles**: in AP7 §11 schon als open point
  notiert; AP8 entscheidet das ebenfalls explizit als
  out-of-scope. Falls partitionierte Bundles kommen, ist der
  `ParquetBundleResolver` der natuerliche Erweiterungspunkt
  (`tables[].partitions[]` statt einzelner `file`).

---

## 12. Risiken

- **Resolver-Doppelpfad** vergroessert den
  `StreamingImporter`-Vertrag (zwei `ResolvedTableInput`-
  Varianten). AP9 muss die DTO-Hierarchie sauber halten, sonst
  drohen "Format-Switch im TableImporter"-Smells.
- **Resume-Strenge**: `BUNDLE_MANIFEST_CHANGED_SINCE_CHECKPOINT`
  ist absichtlich hart. Operatoren, die das Bundle absichtlich
  aktualisieren (z.B. SHA-256-Nachtrag), verlieren ihren Resume-
  Punkt. AP12 sollte das in der CLI-Diagnose mit einer Hinweis-
  Zeile ("checkpoint is stale; rerun without --resume") begleiten.
  Zusaetzlich kann der AP7-Preflight-Check
  (`MANIFEST_SHA256_MISMATCH`) im Resume zuschnappen, wenn die
  Parquet-Datei selbst veraendert wurde — auch dann ist Resume
  bewusst nicht moeglich.
- **Resume-Bedingung an Datei-Hashes** (§8.1): Operatoren, die
  Resume nutzen wollen, muessen den Export mit
  `--manifest-sha256` fahren. Das ist eine bewusste Trade-off-
  Setzung: lieber explizite Vorbedingung als implizite
  Korrektheits-Luecke bei hash-losen Bundles. AP12 muss
  `--manifest-sha256` in der CLI prominent dokumentieren.
- **Auto-Detection-Falle**: die §9.2-Regel kann fuer Operatoren
  ueberraschend sein, die `manifest.yaml` als generischen Namen
  in ihren YAML-Bundles haben. Klare Diagnose im
  `MANIFEST_PARSE_ERROR`-Pfad ist Pflicht.
- **Schema-Fallback (§6.2 Schritt 3)** ist eine Heuristik. Wenn
  Bundles aus `schemaSource = manifest-fallback` zu unzuverlaessig
  importieren, sollte AP12 erwaegen, den Fallback komplett zu
  entfernen und statt dessen den Bundle als invalid abzulehnen.
- **ChunkSchemaBuilder-Komplexitaet**: die §6.2 Drei-Stufen-
  Regel braucht klare Unit-Tests fuer jede Stufe; sonst wandert
  unbemerkte Schema-Inferenz in den Import-Pfad.
