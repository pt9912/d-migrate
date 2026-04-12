# Implementierungsplan: Phase D – Schritt 23 – SQLite-Streamingtests und Reorder-Perf-Gate

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade
> **Phase**: D (Streaming-Import-Verifikation und Perf-Gate)
> **Schritt**: 23
> **Status**: Geplant
> **Referenz**: `implementation-plan-0.4.0.md` §4 Phase D Schritt 23, §6.5, §6.15, §10 M2

---

## 1. Ziel

Nach `D-19_22` existieren bereits:

1. `StreamingImporter`
2. `ImportInput`
3. `ImportResult` / `TableImportSummary`
4. die Chunk-Fehlerpolitik für `--on-error abort|skip|log`

Was noch fehlt, ist der **reale Verifikationsabschluss** von Phase D:

- direkte Streaming-Layer-Tests gegen einen echten SQLite-Pfad
- ein lokaler Perf-/JFR-Run auf dem Reorder-Pfad
- das dokumentierte Gate-Artefakt `docs/perf/0.4.0-phase-d-reorder.md`

Schritt 23 ist damit kein weiterer Architektur- oder Port-Schritt, sondern
der **Abschluss der Phase D gegen echte Laufzeitpfade**. Phase E/F soll erst
danach starten.

---

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `StreamingImporter`, `ImportInput`, `ImportResult` | Phase D `19–22` | ✅ Vorhanden |
| `SqliteDriver.dataWriter()` / `SqliteDataWriter` / `SqliteSchemaSync` | Phase C `16–18` | ✅ Vorhanden |
| produktive JSON/YAML/CSV-Reader | Phase B | ✅ Vorhanden |
| `DefaultDataChunkReaderFactory` | Phase B | ✅ Vorhanden |
| `ConnectionPool` / SQLite-Treiberpfad | Bestand / Phase C | ✅ Vorhanden |

---

## 3. Architekturentscheidung

### 3.1 Direkter Streaming-Layer-Test, kein CLI

Der Schritt bleibt bewusst **unterhalb** von Phase E:

- keine Clikt-Command-Tests
- kein `--source`-/`--table`-Parsing
- kein Exit-Code-Mapping
- keine stderr-/JSON-Output-Assertions des CLI

Stattdessen wird direkt gegen die produktiven Bausteine getestet:

- echter `ConnectionPool` für SQLite
- echter `SqliteDataWriter`
- echter `DefaultDataChunkReaderFactory`
- echter `StreamingImporter`

Der SQLite-Test bleibt im Modul `adapters/driven/streaming`, aber der dafür
nötige reale Modulschnitt ist Teil dieses Schritts:

- `adapters/driven/streaming` bekommt testseitig eine explizite Abhängigkeit
  auf `:adapters:driven:driver-sqlite`
- die für den echten SQLite-Pfad benötigten Test-Abhängigkeiten werden dort
  ebenfalls verfügbar gemacht

Das ist kein optionaler Nachzug, sondern Voraussetzung dafür, dass
`StreamingImporterSqliteTest.kt` im geplanten Modul überhaupt kompilieren und
laufen kann.

### 3.2 SQLite statt Container

Für Schritt 23 ist SQLite der richtige Verifikationsdialekt:

- kein Docker/Testcontainers nötig
- schneller lokaler Lauf
- echter JDBC-/Writer-/SchemaSync-Pfad
- ausreichend, um den Reader→Importer→Writer-Glue fachlich abzusichern

PostgreSQL/MySQL-E2E bleiben Phase F bzw. bestehende Dialekt-Tests.

### 3.3 M2 ist ein echtes Gate, kein Nice-to-have

Der Masterplan definiert den Reorder-Pfad explizit als Performance-Risiko:

- pro Chunk neuer `DataChunk`
- pro Row neues `Array<Any?>`
- potenziell relevanter GC-Druck bei großen Imports

Darum gehört in Schritt 23 **zwingend**:

- ein `@Tag("perf")`-Test oder äquivalenter opt-in-Perf-Lauf
- ein JFR-/Allocation-Profiling gegen ein 1-Million-Row-Fixture
- eine dokumentierte JA/NEIN-Entscheidung, ob vor Phase F ein additiver
  Reorder-Vertrag gebraucht wird

Ohne dieses Artefakt gilt Phase D nicht als abgeschlossen.

---

## 4. Betroffene Dateien

### 4.1 Neue Dateien

| Datei | Zweck |
|---|---|
| `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterSqliteTest.kt` | direkter SQLite-Streaming-Layer-Test |
| `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterReorderPerfTest.kt` | opt-in-Perf-/JFR-Gate für den Reorder-Pfad |
| `docs/perf/0.4.0-phase-d-reorder.md` | dokumentiertes Perf-Gate-Ergebnis |

### 4.2 Mögliche Folgeänderungen

| Datei | Änderung |
|---|---|
| `adapters/driven/streaming/build.gradle.kts` | **verbindlich**: Test-Abhängigkeit auf `:adapters:driven:driver-sqlite` und nötige SQLite-Testpfad-Abhängigkeiten ergänzen; optional Perf-Tag-/JFR-Konfiguration |
| `docs/roadmap.md` | später nach Abschluss von Schritt 23/Phase D insgesamt |

---

## 5. Design

### 5.1 `StreamingImporterSqliteTest`

Der Test soll den produktiven Importpfad ohne CLI direkt verifizieren:

- SQLite-DB anlegen
- Zieltabelle mit echten JDBC-Metadaten erzeugen
- Reader-Fabrik produktiv verwenden
- `StreamingImporter.import(...)` gegen genau zwei reale Formatpfade laufen
  lassen: JSON als Pflichtpfad plus genau einer aus YAML/CSV
- anschließend DB-Inhalt und `ImportResult` prüfen

Pflichtbereiche:

- erfolgreicher Single-Table-Import
- Header-Reordering gegen echte `targetColumns`
- `--on-conflict skip` / `--on-conflict update`
- `--on-error abort|skip|log`
- `rowsInserted` / `rowsUpdated` / `rowsSkipped` / `rowsFailed`
- `SequenceAdjustment.newValue = next generated value`

### 5.2 Fokus des SQLite-E2E-Schnitts

Der Test soll den Teil absichern, den Fake-Tests aus `D-19_22` nicht leisten:

- Reader und Deserializer arbeiten mit echtem Input
- `SqliteDataWriter` wird über JDBC wirklich ausgeführt
- `SqliteSchemaSync` wird am Ende real aufgerufen
- `StreamingImporter` verarbeitet den echten Reorder-/Subset-Pfad

Nicht Teil dieses Schritts:

- CLI-Help/Flag-Parsing
- `--truncate`
- Topo-Sort mit `--schema`
- PG-/MySQL-spezifische Dialekt-E2E

### 5.3 Perf-Test-Schnitt

Der Perf-Lauf soll gezielt den Reorder-Pfad messen, nicht die komplette
End-to-End-CLI:

- großes JSON- oder YAML-Fixture mit 1 000 000 Rows
- Header-Reordering aktiv, damit der Importer wirklich neue Row-Arrays baut
- SQLite als schlanker Zielpfad
- opt-in über `@Tag("perf")`, nicht Teil des Standard-Testlaufs

Erfasst werden mindestens:

- Gesamtimportzeit
- Allocation-Anteil im Reorder-Pfad
- GC-Druck / relevante GC-Events
- Entscheidung `additiver Vertrag JA/NEIN`

---

## 6. Implementierung

### 6.1 `StreamingImporterSqliteTest.kt`

Pflichten:

- produktive ReaderFactory statt Fakes
- produktiver SQLite-Driver/Writer statt Fakes
- Testdaten als echte Dateien/Streams
- Assertions auf DB-Zustand und `ImportResult`

### 6.2 Reale SQLite-Fälle

Pflichttests:

- JSON-Import mit Header-Reordering
- genau ein zusätzlicher zweiter Formatpfad: CSV **oder** YAML
- `onConflict=skip` zählt `rowsSkipped`, nicht `rowsFailed`
- `onConflict=update` zählt `rowsUpdated`
- `onError=log` erzeugt `chunkFailures`
- `sqlite_sequence` wird nach Import korrekt nachgeführt

### 6.3 `StreamingImporterReorderPerfTest.kt`

Pflichten:

- nur opt-in ausführen
- Fixture-Größe 1 000 000 Rows
- messbarer Reorder-Pfad
- JFR oder gleichwertige Allocation-Messung
- stabiler Testname und reproduzierbarer Ablauf

### 6.4 Perf-Dokument

`docs/perf/0.4.0-phase-d-reorder.md` muss mindestens enthalten:

- Gesamtimportzeit
- Allocation-Anteil im Reorder-Pfad
- Beobachteter GC-Druck
- Entscheidung `additiver Vertrag JA/NEIN`
- Begründung der Entscheidung

Zusätzlich verpflichtend für Reproduzierbarkeit:

- CPU-Modell
- Physischer RAM und JVM-Heap (`-Xms` / `-Xmx`)
- JDK-Distribution und -Version
- aktiver GC inkl. zusätzlicher GC-Flags
- OS und Kernel-Version
- Datum des Laufs

Fehlt eines dieser Felder, bleibt das Gate offen.

---

## 7. Tests

### 7.1 `StreamingImporterSqliteTest.kt`

Pflichttests:

| # | Testname | Prüfung |
|---|---|---|
| 1 | `sqlite importer round-trips json into target table` | produktiver Grundpfad |
| 2 | `sqlite importer reorders header columns before write` | echter Reorder-Pfad |
| 3 | `sqlite importer reports skipped rows for onConflict skip` | `rowsSkipped` |
| 4 | `sqlite importer reports updated rows for onConflict update` | `rowsUpdated` |
| 5 | `sqlite importer logs chunk failures for onError log` | reale Chunk-Policy |
| 6 | `sqlite importer reseeds sqlite_sequence after import` | `SqliteSchemaSync` |

### 7.2 `StreamingImporterReorderPerfTest.kt`

Pflichtlauf:

| Testname | Prüfung |
|---|---|
| `reorder path stays below allocation gate or produces explicit contract decision` | M2 / R5 |

### 7.3 Bewusst nicht Teil dieses Schritts

Nicht in 23:

- CLI-Integrationstests
- `data import`-Command
- Exit-Code-Mapping
- PG-/MySQL-E2E

---

## 8. Build & Verifizierung

```bash
# Standard-Streamingtests inkl. SQLite-Layer
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:streaming:test" \
  -t d-migrate:d23 .

# Verbindlicher opt-in-Perf-Lauf außerhalb der Standard-CI
./gradlew :adapters:driven:streaming:test -Dkotest.tags=perf

# Optional mit explizitem JFR-Recording
./gradlew :adapters:driven:streaming:test \
  -Dkotest.tags=perf \
  -Dorg.gradle.jvmargs="-XX:StartFlightRecording=filename=build/jfr/streaming-importer-reorder.jfr,dumponexit=true"
```

Für das Gate reicht ein grüner Standard-Testlauf **nicht**. Zusätzlich
verpflichtend:

1. Perf-Lauf lokal ausführen
2. Ergebnis in `docs/perf/0.4.0-phase-d-reorder.md` dokumentieren
3. Entscheidung `additiver Vertrag JA/NEIN` festhalten

---

## 9. Abnahmekriterien

- [ ] `StreamingImporterSqliteTest.kt` ist vorhanden
- [ ] der SQLite-Test nutzt produktive Reader-/Importer-/Writer-Pfade statt Fakes
- [ ] `adapters/driven/streaming` ist testseitig so verdrahtet, dass der reale SQLite-Pfad dort kompiliert und läuft
- [ ] der SQLite-Layer deckt genau zwei echte Formatpfade ab: JSON plus genau einer aus YAML/CSV
- [ ] Header-Reordering wird gegen echte SQLite-Imports verifiziert
- [ ] `onConflict=skip` und `onConflict=update` sind im SQLite-Streamingpfad getestet
- [ ] `onError=log` ist im SQLite-Streamingpfad getestet
- [ ] `SqliteSchemaSync` / `sqlite_sequence` ist über den Importpfad verifiziert
- [ ] `StreamingImporterReorderPerfTest.kt` oder äquivalenter opt-in-Perf-Lauf ist vorhanden
- [ ] der Perf-Lauf wurde lokal ausgeführt
- [ ] `docs/perf/0.4.0-phase-d-reorder.md` ist vorhanden
- [ ] das Perf-Dokument enthält alle sechs Reproduzierbarkeitsfelder
- [ ] das Perf-Dokument enthält die Entscheidung `additiver Vertrag JA/NEIN`
- [ ] Modul `adapters/driven/streaming` erreicht ≥ 90 % Line-Coverage (Jacoco)
- [ ] Phase D ist erst nach diesem Artefakt als abgeschlossen dokumentiert

---

## 10. Offene Punkte

- Falls der Allocation-Anteil im Reorder-Pfad `> 25 %` der Importzeit liegt,
  muss **vor Phase F** ein additiver Vertrag entschieden werden:
  entweder `session.write(reorderedRows, bindingColumns)` oder ein
  wiederverwendbarer `RowBuffer`-Pool.
- Die eigentliche CLI-Integration bleibt Phase E.
