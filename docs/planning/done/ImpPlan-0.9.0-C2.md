# Unterplan: Phase C.2 - Mid-Table-Resume via Marker

> **Milestone**: 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI
> **Phase**: C (Export-Checkpoint und Resume) — Unterphase C.2
> **Status**: Implemented (2026-04-16)
> **Überplan**: [`ImpPlan-0.9.0-C.md`](./ImpPlan-0.9.0-C.md)
> **Vorgaenger-Unterplan**: [`ImpPlan-0.9.0-C1.md`](./ImpPlan-0.9.0-C1.md) —
> tabellengranulares Export-Resume
> **Referenz**: `docs/planning/ImpPlan-0.9.0-A.md`, `docs/planning/ImpPlan-0.9.0-B.md`,
> `docs/planning/ImpPlan-0.9.0-C.md`, `docs/planning/ImpPlan-0.9.0-C1.md`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/DataReader.kt`;
> `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/data/`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/checkpoint/CheckpointManifest.kt`;
> `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt`

---

## 1. Ziel

C.2 erweitert das in C.1 etablierte Export-Resume auf **Mid-Table-
Granularitaet**. Nach Abschluss dieser Unterphase gilt:

- ein Exportlauf, der mitten in einer grossen Tabelle abgebrochen ist,
  kann mit `--resume` an einem fachlich bestaetigten Marker
  fortgesetzt werden (nicht von vorn)
- der Marker ist ein **Composite** aus `--since-column`-Wert **und**
  einem eindeutigen Tie-Breaker (Primaerschluessel der Tabelle). Das
  verhindert Duplikate/Luecken an Chunk-Grenzen, wenn die Marker-Spalte
  nicht eindeutig ist (z.B. `updated_at` mit identischen Timestamps).
  Der bestehende LF-013-Filterpfad (`>=` auf nur der Marker-Spalte,
  `spec/cli-spec.md`) bleibt ein anderer, gröberer Kontrakt und wird
  von C.2 nicht als Resume-Anker wiederverwendet.
- der Marker ist `--since-column`-gebunden: C.2 liefert Mid-Table-Resume
  nur fuer Tabellen, die der Nutzer mit einer stabilen, monotonen
  Marker-Spalte und einem Primaerschluessel exportiert (typisch:
  `updated_at` + `id`)
- fuer Tabellen ohne `--since-column` oder ohne PK faellt C.2 auf den
  C.1-Vertrag zurueck (fertige Tabellen skippen, unvollstaendige neu
  exportieren) — das ist bewusst konservativ, nicht optional-best-effort
- Single-File-Fortsetzung mit genau **einer** Tabelle (der heutigen
  Basiszusicherung des Exports, siehe §8.3) wird ueber einen
  format-spezifischen, kontrollierten Fortsetzungspfad umgesetzt

C.2 ist die risikotragendere Haelfte von Phase C: sie fasst den
`DataReader`-Port an und definiert das Marker-Modell.

---

## 2. Ausgangslage (nach C.1)

- Manifest-Lifecycle und Preflight stehen; `optionsFingerprint`,
  Kompatibilitaetscheck und Exit-3/Exit-7-Mapping sind produktiv.
- `CheckpointTableSlice.rowsProcessed`, `chunksProcessed` und
  `lastMarker` sind bereits Bestandteil des Manifests (Phase B),
  werden in C.1 aber nur zur Buchfuehrung gefuellt und nicht fuer
  Wiederaufnahme benutzt.
- `StreamingExporter` ueberspringt `COMPLETED`-Tabellen und exportiert
  unvollstaendige von vorn.
- `DataReader.streamTable(pool, table, filter, chunkSize)` kennt heute
  weder Marker-Paging noch einen Tie-Breaker-fähigen Fortsetzungsvertrag.

Konsequenz: C.2 erweitert den `DataReader`-Port und den Marker-
Schreibpfad, ohne den C.1-Vertrag zu brechen.

---

## 3. Scope fuer C.2

### 3.1 In Scope

- Erweiterung des `DataReader`-Ports
  (heute `streamTable(pool, table, filter, chunkSize)`):
  - neue Ueberladung oder zusaetzlicher Parameter, der einen
    **Composite-Marker** akzeptiert:
    - `markerColumn` + letzter Marker-Wert
    - `tieBreakerColumns` + letzte Tie-Breaker-Werte (typisch: PK)
  - die Fortsetzungs-Semantik ist strikter als LF-013:
    `(markerColumn, tieBreakerColumns) > (lastMarkerValue, lastTieBreakerValues)`
    in lexikografischer Reihenfolge; das verhindert Duplikate bei
    gleichen Marker-Werten, ohne Zeilen zu verlieren
  - deterministische Sortierung des Chunk-Streams nach
    `(markerColumn ASC, tieBreakerColumns ASC)`, damit Wiederaufnahme
    reproduzierbar ist
- Marker-Schreibpfad im `StreamingExporter`:
  - nach **jedem** fachlich abgeschlossenen Chunk (Datei-Flush + Counter-
    Update) wird der letzte Marker-Wert ins Manifest geschrieben
  - atomarer Manifest-Schreibpfad aus Phase B bleibt die
    Persistenzkante
- Runner-Logik:
  - bei vorhandenem Manifest-Slice mit `status = IN_PROGRESS` und
    vorhandenem `lastMarker` → Exporter startet diese Tabelle ab dem
    Marker-Wert
  - bei Tabellen ohne `--since-column` in der Request → C.1-Verhalten
    (unvollstaendige Tabellen werden neu exportiert)
- Single-File-Fortsetzung mit **einer Tabelle**:
  - der Basispfad des Exports erlaubt Single-File bereits heute **nur**
    fuer genau eine Tabelle (`StreamingExporter` wirft sonst; `spec/cli-spec.md`
    lehnt Datei + ≥2 Tabellen mit Exit 2 ab). Multi-Table-Single-File ist
    kein gueltiger Exportlauf und braucht deshalb auch keinen Resume-Pfad.
  - C.2 liefert den kontrollierten Fortsetzungspfad fuer genau diesen
    Single-Table-Single-File-Fall, wenn die Tabelle nicht `COMPLETED` ist:
    - JSON-Array: die partielle Exportdatei ist nach Abbruch in der
      Regel nicht parsebar. C.2 oeffnet den Container neu und
      re-exportiert die Tabelle vom Marker aus, indem die bestaetigten
      Chunk-Bereiche aus einer vom `CheckpointStore` verwalteten
      Zwischenform plus der Fortsetzung ab Marker zusammengefuehrt
      werden. Finaler atomic rename auf das Zielfile am Lauf-Ende.
    - YAML-Sequenz: analog zu JSON.
    - CSV: Single-Table-Single-File mit Header wird über eine
      Zwischendatei neu aufgebaut (Header einmalig + bestaetigte Rows
      + ab-Marker-Fortsetzung), finaler atomic rename.
  - Container-/Header-Semantik ist pro Format eigenstaendig; C.2 fixiert
    pro Format den kontrollierten Fortsetzungspfad
- Kompatibilitaetspruefung erweitert:
  - `--since-column` ist verpflichtend Teil des `optionsFingerprint`;
    ein Wechsel bricht Resume mit Exit 3 (das erbt C.2 aus C.1)
  - wenn das Manifest einen `lastMarker` enthaelt, aber im Request
    `--since-column` fehlt, ist das ein Preflight-Fehler (Exit 3)

### 3.2 Bewusst nicht Teil von C.2

- Marker-Paging ohne nutzerseitig deklariertes `--since-column` (z.B.
  automatische PK-Erkennung) — das ist ein spaeteres Ausbaufeld
- Import-Resume (→ Phase D)
- Retry-Logik, Parallelisierung, Signal-Handling
- Archivierung/GC alter Checkpoints

---

## 4. Leitentscheidungen (C.2-spezifisch)

### 4.1 Der Marker ist user-deklariert und composite

C.2 stuetzt sich auf den bereits vorhandenen
`--since-column`/`--since`-Vertrag aus LF-013 (Phase 0.4.0) **plus**
einen eindeutigen Tie-Breaker aus dem Primaerschluessel der Tabelle.
Die Marker-Spalte allein ist:

- stabil
- monoton
- vom Nutzer fachlich benannt
- bereits im `DataFilter.ParameterizedClause`-Pfad typisiert (siehe
  `DataExportHelpers.parseSinceLiteral`)

...aber **nicht zwingend eindeutig** (`updated_at` kann identische
Timestamps haben). Der reine LF-013-Filter `>= lastMarker` wuerde
deshalb bei einer Wiederaufnahme an einer Chunk-Grenze entweder
Duplikate erzeugen (mehrere Rows mit demselben Marker-Wert werden
erneut exportiert) oder Luecken (wenn man auf `>` zurueckfaellt, dann
verliert man die nicht-mitbewegten Ties).

Phase C.2 fixiert als Loesung ein **Composite-Marker-Modell**:

- Der Marker ist ein Tupel aus
  `(markerColumnValue, tieBreakerValues)` — letzterer sind die
  PK-Spalten der Tabelle.
- Fortsetzung vergleicht **lexikografisch**:
  `(markerColumn, tieBreakers...) > (lastMarkerValue, lastTieBreakerValues...)`
- Export-Sortierung beim Lesen: `ORDER BY markerColumn, pk1, pk2, ...`
  (alles ASC)

Verbindliche Folge:

Drei Fallunterscheidungen beim Start eines Resume-Laufs:

1. **Kein `--since-column` im aktuellen Request, und das Manifest hat
   auch keinen `lastMarker`** → kein Mid-Table-Resume möglich,
   C.1-Fallback (unvollständige Tabellen werden neu exportiert). Kein
   Fehler, keine Warnung — es ist schlicht C.1-Verhalten.
2. **`--since-column` gesetzt, aber die Tabelle hat keinen
   erkennbaren Primaerschluessel** (PK-Introspektion liefert nichts) →
   **kein harter Preflight-Abbruch**, sondern **konservativer
   C.1-Fallback** mit sichtbarem stderr-Hinweis („mid-table resume
   disabled for table '<name>': no primary key for tie-breaker;
   re-exporting from scratch"). Der Lauf läuft weiter, die Tabelle wird
   neu exportiert.
3. **Manifest trägt `lastMarker`, aber der aktuelle Request hat kein
   `--since-column`** → **echter Preflight-Fehler** (Exit 3), weil die
   beiden Laufvertrage semantisch nicht zueinander passen. Das Resume
   wird abgebrochen, nicht stumm degradiert.

Zusaetzlich:

- keine Heuristik („find a primary key") im Exporter **ausser** dem
  expliziten Lesen der Tabellen-PK-Metadaten ueber die bestehende
  Schema-Introspektion; findet die Introspektion keinen PK, greift
  Fall 2 (konservativer C.1-Fallback)
- `optionsFingerprint` nimmt PK-Spaltensignatur als zusaetzliches Feld
  auf, damit eine spaetere PK-Aenderung Resume sauber invalidiert

### 4.2 Marker-Schreiben ist pro Chunk, nicht pro Row

Ein Marker wird erst nach einem fachlich bestaetigten Chunk
fortgeschrieben. Das ist die gleiche Garantie wie in C.1 (nur feiner):
„ein Checkpoint entspricht einem vollstaendig committeten Schritt",
nicht einem Mid-Chunk-Buffer.

### 4.3 Der `DataReader`-Port bekommt Marker-Paging als erster Konsument

C.2 fuehrt die Marker-Paging-API im Port **gezielt** ein. Es gibt
bewusst keinen Sammel-Refactor des `DataReader`-Ports; andere Features
(etwa partition-aware Reads) bleiben separate Erweiterungspfade.

Ebenso C.2-konform: die bestehende
`streamTable(pool, table, filter, chunkSize)`-Signatur bleibt existierend;
die Marker-Paging-API ist eine parallele Ueberladung oder ein
zusaetzlicher optionaler Parameter mit Default.

### 4.4 Single-File-Fortsetzung ist format-spezifisch, nicht textgenerisch

Ein naives „append" auf eine bereits begonnene JSON-Datei produziert
ungueltiges JSON, auf eine CSV-Datei mit Header dagegen doppelt
geschriebene Header. C.2 modelliert den Fortsetzungspfad pro Format
explizit. Der einfachste Pfad ist: bei Resume die Zieldatei vollstaendig
neu aufbauen, indem die bereits gespeicherten Tabellenbereiche aus einer
Checkpoint-Store-verwalteten Zwischenform plus die ab-dem-Marker-Daten
konkateniert werden.

---

## 5. Arbeitspakete

### 5.1 C2.1 — `DataReader`-Port erweitern

Die bestehende Port-Signatur lautet heute:

```kotlin
fun streamTable(
    pool: ConnectionPool,
    table: String,
    filter: DataFilter? = null,
    chunkSize: Int = 10_000,
): ChunkSequence
```

C.2 ergaenzt eine neue Ueberladung fuer den Composite-Marker-Pfad:

```kotlin
fun streamTable(
    pool: ConnectionPool,
    table: String,
    filter: DataFilter? = null,
    chunkSize: Int = 10_000,
    resumeMarker: ResumeMarker,
): ChunkSequence
```

mit einem neuen Port-Typ:

```kotlin
data class ResumeMarker(
    val markerColumn: String,              // z.B. "updated_at"
    val lastMarkerValue: Any?,             // zuletzt bestaetigt
    val tieBreakerColumns: List<String>,   // PK-Spalten, stable order
    val lastTieBreakerValues: List<Any?>,  // parallel zu tieBreakerColumns
)
```

Semantik: der Reader erzeugt intern
`WHERE <markerColumn> > ? OR (<markerColumn> = ? AND (<tieBreakers> > <values>))`
und sortiert `ORDER BY <markerColumn>, <tieBreakers>`.

- Dialektadapter (`adapters/driven/driver-postgresql`,
  `driver-mysql`, `driver-sqlite`) setzen die Erweiterung konsistent um
- C.2 trifft die Entscheidung, ob `ResumeMarker` im `hexagon:ports`-
  Paket oder als `ResumeMarker`-Sealed-Interface in
  `streaming.checkpoint` lebt (Praeferenz: `hexagon:ports` nahe am
  `DataReader`, damit Treiber-Adapter keinen neuen Modul-Impfad brauchen)

### 5.2 C2.2 — Marker-Schreibpfad im `StreamingExporter`

- nach jedem Chunk: `lastMarker` im Manifest-Slice fortschreiben
- atomarer Manifest-Write ueber `CheckpointStore.save()` bleibt
- `rowsProcessed`/`chunksProcessed` werden tatsaechlich als laufender
  Counter gefuehrt

### 5.3 C2.3 — Runner-Pfad fuer Mid-Table

- bei vorhandenem Slice mit `status = IN_PROGRESS` und `lastMarker`:
  Tabelle wird nicht neu exportiert, sondern ab Marker fortgesetzt
- Kompatibilitaets-Edge: Manifest hat `lastMarker`, aber Request hat
  kein `--since-column` → Exit 3 mit klarer Meldung

### 5.4 C2.4 — Single-Table-Single-File-Fortsetzungspfad

- Scope: der Basispfad erlaubt Single-File bereits heute nur fuer genau
  eine Tabelle. Multi-Table-Single-File wird vom Exporter (und vom
  CLI-Runner) mit Exit 2 abgelehnt und braucht deshalb keinen
  Resume-Pfad.

**Pragmatische 0.9.0-Auspraegung** (Implementierung im Commit
`<TBD>` — siehe `docs/planning/roadmap.md`): der Lauf schreibt Single-File-
Ziele **immer** in eine vom Checkpoint-Verzeichnis gemanagte
Staging-Datei (`<checkpoint-dir>/<operationId>.single-file.staging`);
die Zieldatei wird erst beim Lauf-Abschluss per `Files.move`
(`ATOMIC_MOVE`, `REPLACE_EXISTING`) ersetzt. Dadurch kann ein
Abbruch niemals eine halb-valide Container-Datei am Zielpfad
hinterlassen.

**Bewusst verschoben**: echte Mid-Table-Fortsetzung in bereits
gestreamten JSON/YAML/CSV-Containern ("Zwischenform merged mit
ab-Marker-Fortsetzung") erfordert einen format-spezifischen
Append-/Rebuild-Writer, den C.2 nicht liefert. Statt dessen gilt
fuer Single-File-Resume in 0.9.0:

- Der Runner ignoriert einen eventuell im Manifest gespeicherten
  `resumePosition` fuer Single-File-Laeufe und setzt den
  `ResumeMarker.position` auf `null` (Fresh-Track) — der Lauf
  exportiert die Tabelle erneut **von vorn** in eine frische
  Staging-Datei.
- Der `optionsFingerprint` bleibt gueltig (Tabelle, PK, Filter etc.
  muessen weiterhin passen), der Manifest-Slice wechselt nach
  Abschluss auf `COMPLETED`.

Die volle "Zwischenform + Rebuild"-Variante aus dem urspruenglichen
Plan (JSON-Container-Rebuild, CSV-Header-einmalig, YAML-Sequence-
Append) wird erst in einem spaeteren Release geliefert, wenn ein
dediziertes Staging-Writer-Interface existiert.

### 5.5 C2.5 — Tests

- `DataReader`-Adapter-Tests: Marker-Paging liefert deterministische
  Chunks; fehlendes `ORDER BY` bricht den Test (negative Kontrolle)
- Streaming-Tests: abgebrochener Export mitten in einer Tabelle wird
  ab Marker fortgesetzt; Rows-Processed-Zaehler stimmt nach Resume
- Runner-Tests: Preflight lehnt Manifest-`lastMarker` ohne
  `--since-column` ab
- Single-File-Format-Tests: JSON/YAML-Container bleibt nach Resume
  valide

---

## 6. Teststrategie fuer C.2

- Driver-Integrationstests: Marker-Paging wird gegen Postgres, MySQL
  und SQLite gefahren (Testcontainers / in-memory SQLite)
- Streaming-Tests: simulierter Abbruch nach `n` Chunks und Fortsetzung
  landet exakt bei Chunk `n+1`
- Determinismus-Tests: zweimaliger Export mit identischem Marker
  liefert identische Byte-Sequenzen
- Format-Tests: Single-File-JSON/YAML bleibt bei Resume parsebar

---

## 7. Datei- und Codebasis-Betroffenheit

Sicher betroffen:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/DataReader.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/`
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/checkpoint/CheckpointManifest.kt`
  (Semantikverfeinerung, nicht Strukturaenderung)

Mit hoher Wahrscheinlichkeit betroffen:

- alle Driver-Tests unter `adapters/driven/driver-*/src/test/`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingExporterTest.kt`

---

## 8. Risiken

### 8.1 Marker ohne `--since-column`

Ohne `--since-column` gibt es kein Mid-Table-Resume. Nutzer, die das
erwarten, sehen in C.2 weiterhin den C.1-Fallback (Tabelle neu
exportieren).

Mitigation:

- Dokumentation in `cli-spec.md` und `guide.md` macht explizit, dass
  Mid-Table-Resume `--since-column` verlangt
- Fehlermeldung beim Lauf verweist auf den Zusammenhang

### 8.2 Dialektunterschiede bei `ORDER BY` + NULL-Handling

PostgreSQL, MySQL und SQLite haben unterschiedliches Default-NULL-
Sortierverhalten. Der Marker-Paging-Pfad muss NULLs fachlich stabil
behandeln.

Mitigation:

- C.2 dokumentiert: `--since-column`-Spalten duerfen in der Praxis nicht
  nullable sein; nullable Marker-Spalten sind eine Nutzereinschraenkung
- Adapter setzen konsistentes `NULLS LAST` / `NULLS FIRST` (je nach
  ASC/DESC) und dokumentieren die Wahl

### 8.3 Single-File-Fortsetzung produziert fragile Container

Naives Append auf JSON/YAML-Dateien ist gefaehrlich. C.2 geht den
Zwischendatei-/Rebuild-Weg.

Mitigation:

- Rebuild passiert ueber eine `CheckpointStore`-verwaltete Datei im
  Checkpoint-Verzeichnis, nicht durch direktes Modifizieren der
  Zieldatei
- atomic rename erst am Lauf-Ende

### 8.4 Test-Churn bei DataReader-Erweiterung

Die `DataReader.readChunks`-Ueberladung zieht Anpassungen in allen
Driver-Adaptern nach.

Mitigation:

- Default-Parameter mit `sinceColumn = null, sinceValue = null`
- Adapter-Bestandstests bleiben unveraendert, wenn sie die alten
  Parameter benutzen

---

## 9. Entscheidungsempfehlung

C.2 wird **nach** C.1 umgesetzt und baut auf dessen Manifest-Lifecycle
und Preflight-Matrix auf. Damit kommt die riskantere
`DataReader`-Port-Erweiterung erst, nachdem der Runner- und
Store-Pfad stabil ist.

Empfohlen ist, C.2 erst zu starten, wenn:

- C.1 in Review oder Implemented ist
- die Test-Suite der C.1-Preflight- und Streaming-Tests grün ist
- die Entscheidung zu `--since-column`-basiertem Mid-Table-Resume vs.
  automatischer PK-Erkennung explizit getragen ist

Nach C.2 liefert der Exportpfad das volle 0.9.0-Resilienzbild aus dem
Masterplan — inklusive Fortsetzung grosser Einzeltabellen.
