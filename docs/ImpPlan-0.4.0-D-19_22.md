# Implementierungsplan: Phase D – Schritte 19–22 – StreamingImporter, ImportInput, ImportResult und Chunk-Fehlerpolitik

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade
> **Phase**: D (Streaming-Import-Orchestrierung)
> **Schritte**: 19 + 20 + 21 + 22
> **Status**: Abgeschlossen (2026-04-11)
> **Referenz**: `implementation-plan-0.4.0.md` §3.6.1, §3.6.2, §4 Phase D Schritte 19–22, §6.5, §6.14, §6.15

---

## 1. Ziel

Nach Phase C existieren nun:

1. produktive `DataChunkReaderFactory`-/Reader-Pfade für JSON/YAML/CSV
2. produktive `DataWriter`-/`TableImportSession`-Pfade für PostgreSQL, MySQL und SQLite
3. `SchemaSync`, `SequenceAdjustment` und die Dialekt-spezifischen Cleanup-/Reseed-Verträge

Was noch fehlt, ist die Orchestrierung zwischen diesen Bausteinen:

- eine importseitige Eingabeabstraktion (`ImportInput`)
- ein Ergebnis-/Reporting-Modell (`ImportResult`, `TableImportSummary`)
- der eigentliche `StreamingImporter`
- die verbindliche Chunk-Fehlerpolitik für `--on-error abort|skip|log`

Diese vier Punkte bilden im aktuellen Repo einen **atomaren** Block. Ein
isolierter Schnitt zwischen 19, 20, 21 und 22 wäre künstlich, weil der
Importer ohne Eingabe-/Ergebnisvertrag nicht sinnvoll kompilier- und testbar
ist und die `--on-error`-Semantik nicht als nachträglicher Zusatz kommen kann.

Schritt 23 bleibt bewusst separat: dort liegen die SQLite-Ende-zu-Ende-
Streamingtests und das Perf-/JFR-Gate, nicht der Grundvertrag.

---

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `DataChunkReaderFactory`, `DataChunkReader`, `ValueDeserializer` | Phase B | ✅ Vorhanden |
| `DataWriter`, `TableImportSession`, `WriteResult`, `FinishTableResult` | Phase C Schritt 12 | ✅ Vorhanden |
| `SchemaSync`, `SequenceAdjustment` | Phase C Schritt 13 | ✅ Vorhanden |
| PostgreSQL-/MySQL-/SQLite-Writer | Phase C Schritte 14–18 | ✅ Vorhanden |
| `PipelineConfig` aus 0.3.0 | Bestand | ✅ Vorhanden |
| `ConnectionPool.dialect` | Bestand | ✅ Vorhanden |

---

## 3. Architekturentscheidung

### 3.1 Schritte 19–22 als ein gemeinsamer Implementierungsschnitt

Phase D startet den ersten echten End-to-End-Pfad des Imports innerhalb der
Architektur. Dafür müssen in **einem** Change zusammenkommen:

- Import-Eingabetypen
- Import-Ergebnistypen
- Orchestrierung Reader → Writer
- Chunk-Fehlerpolitik

Darum werden die Masterplan-Schritte praktisch so abgebildet:

- Schritt 19: `StreamingImporter`
- Schritt 20: `ImportInput`
- Schritt 21: `ImportResult` + `TableImportSummary`
- Schritt 22: Chunk-Transaktionsmodell und `--on-error`-Auswertung

### 3.2 Reale Repo-Architektur statt veralteter `DataWriterRegistry`

Der Masterplan spricht in §3.6.1 noch von einer `DataWriterRegistry`. Diese
Zwischenarchitektur existiert im Repo nicht mehr. Produktiv gilt stattdessen:

- `DatabaseDriverRegistry` ist der einzige Driver-Lookup
- `ConnectionPool` trägt den Zieldialekt bereits in `pool.dialect`
- Import-Layer darf deshalb **keine** neue Registry einführen

Der `StreamingImporter` wird daher an die aktuelle Architektur angepasst:

- entweder via `writerLookup: (DatabaseDialect) -> DataWriter`
- oder äquivalentem injizierten `DataWriter`

Entscheidend ist nur: der Lookup bleibt beim bestehenden Driver-Aggregat.

### 3.3 `ImportInput` ist ein Port-/Runner-Vertrag, kein CLI-Resolver

`ImportInput` gehört in die Streaming-/Port-Schnittstelle, aber **die**
Auflösung von `--source` zu `Stdin`/`SingleFile`/`Directory` gehört noch nicht
in Phase D.

Für diesen Schritt gilt deshalb:

- `ImportInput` wird als sealed class eingeführt
- Phase D konsumiert bereits fertige `ImportInput`-Instanzen
- die CLI-Auflösung (`source == "-"`, Datei vs. Verzeichnis, `--table` vs.
  `--tables`) bleibt Phase E

Damit bleibt der `StreamingImporter` filesystem-agnostisch genug, um direkt
und ohne Clikt getestet zu werden.

**Wichtig für `Stdin`**: Die `Stdin`-Variante trägt in Phase D bereits den
konkreten `InputStream` und nicht nur den Tabellennamen. Damit bleibt die
Ownership explizit und testbar:

- Phase D kennt keinen globalen `System.in`-Zugriff
- Tests können `ByteArrayInputStream` o.ä. übergeben
- Phase E baut dann `ImportInput.Stdin(table, System.`in`)`

### 3.4 Truncate-Pre-Step bleibt bis Phase E außerhalb von 19–22

Der destruktive `--truncate`-Vorlauf aus §6.14 ist **kein** sauberer Teil von
Phase D `19–22`, weil es im aktuellen Driver-/Port-Schnitt weder einen
dialektneutralen Reset-Hook noch einen anderen Owner für
`TRUNCATE TABLE`/`DELETE FROM` gibt.

Für diesen Plan gilt deshalb ausdrücklich:

- `StreamingImporter` führt in `19–22` **keine** dialektspezifische
  Truncate-/Delete-SQL selbst aus
- es wird in Phase D **keine** neue Registry und **kein** ad-hoc-SQL-Pfad im
  Streaming-Layer eingeführt
- der destruktive Pre-Step samt Confirmations/Exit-2-Regeln bleibt Phase E
- der bereits bestehende Session-Hook `markTruncatePerformed()` bleibt für die
  spätere Verdrahtung erhalten, wird aber in `19–22` noch nicht produktiv
  aus dem CLI angesteuert

### 3.5 Ergebnis-Typen parallel zu `ExportResult`

`ImportResult` und `TableImportSummary` gehören wie `ExportResult` in den
`dev.dmigrate.streaming`-Vertragsschichtbereich, nicht in CLI oder JDBC-
Module.

Neue Typen in diesem Schritt:

- `ImportInput`
- `ImportResult`
- `TableImportSummary`
- `ChunkFailure`
- `FailedFinishInfo`

Sie bilden die strukturierte Brücke zwischen Phase D, Phase E und den späteren
Phase-F-E2E-Assertions.

---

## 4. Betroffene Dateien

### 4.1 Neue Dateien

| Datei | Zweck |
|---|---|
| `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ImportInput.kt` | Eingabe-Modell für Import |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ImportResult.kt` | Ergebnis-/Reporting-Modell |
| `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingImporter.kt` | Reader→Writer-Orchestrierung |
| `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterTest.kt` | Contract-/Fehlerpolitik-Tests mit Fakes |

### 4.2 Mögliche Folgeänderungen

| Datei | Änderung |
|---|---|
| `adapters/driven/streaming/build.gradle.kts` | ggf. Coverage-/Test-Konfiguration für neue Importer-Tests |
| `docs/roadmap.md` | später nach Abschluss von Schritt 23/Phase D insgesamt |

Für Phase D `19–22` sind **keine** Änderungen an `cli` notwendig; diese folgen
erst in Phase E.

---

## 5. Design

### 5.1 `ImportInput`

Der Typ wird analog zu `ExportOutput` als sealed class eingeführt:

- `ImportInput.Stdin(table, input)`
- `ImportInput.SingleFile(table, path)`
- `ImportInput.Directory(path, tableFilter, tableOrder)`

Wichtig:

- `Stdin` trägt den tatsächlichen `InputStream`; kein impliziter
  `System.in`-Zugriff im Importer
- `table` folgt dem 0.3.0-Identifier-Vertrag (`name` oder `schema.table`)
- kein automatisches Tabellen-Mapping aus Dateinamen in diesem Schritt
- `tableOrder` bleibt ein vorbereiteter Hook für den CLI-/Schema-Pfad aus
  Phase E, wird aber schon im Vertrag aufgenommen

### 5.2 `ImportResult` und `TableImportSummary`

Der Ergebnisvertrag spiegelt `ExportResult`, trägt aber importspezifische
Zähler und Fehlerarten:

- `rowsInserted`
- `rowsUpdated`
- `rowsSkipped`
- `rowsUnknown`
- `rowsFailed`
- `chunkFailures`
- `sequenceAdjustments`
- `targetColumns`
- `triggerMode`
- `failedFinish`
- `error`

Zusätzlich braucht der Aggregattyp sinnvolle Summenfelder auf Run-Ebene:

- `tables`
- Gesamtsummen der Row-Counter
- `durationMs`
- `success`

### 5.3 `StreamingImporter`

Der `StreamingImporter` bleibt strukturell das Spiegelbild des
`StreamingExporter`, aber für Reader→Writer:

1. `ImportInput` in eine deterministische Tabellenliste auflösen
2. pro Tabelle einen Reader aus `DataChunkReaderFactory` erzeugen
3. pro Tabelle genau **eine** `TableImportSession` öffnen
4. den ersten Chunk für Header-/Pre-Flight lesen
5. Reorder-/Subset-Mapping aus `reader.headerColumns()` und
   `session.targetColumns` bauen
6. Chunks normalisieren und an `session.write(...)` geben
7. bei Erfolg `commitChunk()`, bei Fehler `rollbackChunk()` plus
   `--on-error`-Auswertung
8. am Ende `finishTable()` auswerten und Summary füllen
9. `close()` immer als Cleanup laufen lassen

### 5.4 M6: Pre-Flight ist immer Abort

Der Plan übernimmt die harte M6-Regel aus dem Masterplan:

- Reader-Konstruktion
- erstes `nextChunk()`
- Header-Ermittlung
- Header-/Target-Mapping
- Pre-Flight-Mismatch

sind **immer Abort**, auch wenn `--on-error skip|log` gesetzt ist.

`--on-error` greift erst nach dem ersten erfolgreichen `session.write(...)`.

### 5.5 Schritt 22: Chunk-Fehlerpolitik

Der `StreamingImporter` übernimmt die Chunk-Politik zentral:

- `abort`: erster Chunk-Fehler beendet den ganzen Import
- `skip`: Chunk wird verworfen, `rowsFailed += lostRows`, Import läuft weiter
- `log`: wie `skip`, zusätzlich strukturierter `ChunkFailure`-Eintrag

Wichtig:

- `rowsSkipped` bleibt ausschließlich Konflikt-Skip aus dem Writer-Pfad
- `rowsFailed` bleibt ausschließlich verlorene Rows aus Chunk-Fehlern
- `rowsUnknown` kommt ausschließlich aus Writer-`WriteResult`

### 5.6 `Directory.tableFilter` und `tableOrder` sind bindende Vertragsfelder

`ImportInput.Directory` trägt zwei Felder, die nicht nur „für später" im Typ
stehen dürfen, sondern bereits in Phase D bindend sein müssen:

- `tableFilter`: begrenzt die verarbeitete Tabellenmenge
- `tableOrder`: überschreibt die Default-Reihenfolge explizit

Konkreter Vertrag:

- ohne `tableOrder`: deterministisch lexikographisch
- mit `tableOrder`: Verarbeitung strikt in dieser Reihenfolge
- `tableFilter` wirkt vor der Verarbeitung und begrenzt die Kandidatenmenge

Damit ist der Typ schon in Phase D belastbar genug für den späteren
Topo-Sort-/Schema-Pfad aus Phase E.

### 5.7 Truncate-Signaling bleibt vorbereiteter Hook für Phase E

`markTruncatePerformed()` bleibt Teil des Session-Vertrags, aber **nicht** der
produktiven Phase-D-Implementierung von `19–22`.

Phase D dokumentiert nur:

- der Hook existiert und ist der richtige spätere Zielpunkt
- produktive Ansteuerung folgt erst mit dem destruktiven Pre-Step in Phase E

### 5.8 `FailedFinishInfo` als strukturierter Träger

`FinishTableResult.PartialFailure` darf nicht als bloße Fehlermeldung
verlorengehen. Der Importer serialisiert ihn deshalb in:

- `adjustments`
- `causeMessage`
- `causeClass`
- `causeStack`
- optional `closeCauseMessage`
- optional `closeCauseClass`
- optional `closeCauseStack`

Damit bleiben Phase-F-Assertions und späterer JSON-Output deterministisch.

---

## 6. Implementierung

### 6.1 `ImportInput.kt`

- sealed class unter `dev.dmigrate.streaming`
- Varianten `Stdin`, `SingleFile`, `Directory`
- `Stdin` mit explizitem `InputStream`
- KDoc klar auf Phase-E-CLI-Resolver verweisen

### 6.2 `ImportResult.kt`

- `ChunkFailure`
- `FailedFinishInfo`
- `ImportResult`
- `TableImportSummary`
- Aggregat-/`success`-Hilfsfelder analog zu `ExportResult`

### 6.3 `StreamingImporter.kt`

Pflichten:

- `readerFactory` injizieren
- Writer-Lookup an reale `DatabaseDriverRegistry`-Architektur anpassen
- erste Chunk-/Header-Pre-Flight-Phase korrekt behandeln
- Reorder-/Subset-Mapping aus Header und `session.targetColumns`
- Chunk-Transaktionsmodell sauber über `commitChunk()` / `rollbackChunk()`
- `FinishTableResult.Success` und `PartialFailure` in Summary überführen
- `Directory.tableFilter` und `Directory.tableOrder` verbindlich respektieren
- `close()` immer laufen lassen

### 6.4 Reorder-/Subset-Logik

Der Importer baut pro Tabelle:

- die gebundene Spaltenliste in Target-Reihenfolge
- ein Mapping Header/Row → gebundene Writer-Reihenfolge
- einen `JdbcTypeHint`-/Deserializer-Lookup aus `session.targetColumns`

Header-Mismatch wird **vor** dem ersten `write(...)` als
`ImportSchemaMismatchException` geworfen.

### 6.5 Verarbeitungsreihenfolge pro Tabelle

Der 0-Chunk-/Empty-Table-Pfad bleibt gültig:

- wenn `firstChunk` leer ist, aber gültige Header trägt, wird trotzdem
  `finishTable()` ausgeführt
- die Summary enthält dann `0` für alle Row-Zähler, aber korrekte
  `targetColumns`/`triggerMode`

---

## 7. Tests

### 7.1 `StreamingImporterTest.kt`

Schritt `19–22` soll primär mit Fakes/Spies abgesichert werden, nicht schon
mit echten SQLite-JDBC-Läufen. Diese kommen gesammelt in Schritt 23.

Pflichttests:

| # | Testname | Prüfung |
|---|---|---|
| 1 | `imports single table from single file input` | Grundpfad Reader→Writer |
| 2 | `stdin input uses provided stream instead of global system in` | explizite Stream-Ownership |
| 3 | `directory input processes tables deterministically without explicit order` | Default-Reihenfolge |
| 4 | `directory input respects explicit table order` | `tableOrder` |
| 5 | `directory input respects table filter` | `tableFilter` |
| 6 | `preflight header mismatch aborts before first write regardless of onError` | M6 |
| 7 | `write failure aborts on onError abort` | Chunk-Policy `abort` |
| 8 | `write failure skips chunk and counts rowsFailed on onError skip` | Chunk-Policy `skip` |
| 9 | `write failure logs chunk failure on onError log` | Chunk-Policy `log` |
| 10 | `finish partial failure is transferred into failedFinish info` | `FailedFinishInfo` |
| 11 | `rowsSkipped and rowsFailed stay separate` | K2 / M3 |
| 12 | `rowsUnknown is aggregated without becoming rowsInserted` | R10 |
| 13 | `close runs even after finish partial failure` | F3 |
| 14 | `zero chunk path still produces table summary` | Empty-Table-Pfad |

### 7.2 Bewusst nicht Teil dieses Schritts

Nicht in `19–22`, sondern separat in `23`:

- echte SQLite-Streamingtests gegen `StreamingImporter`
- Allocation-/JFR-Run
- Perf-Dokument `docs/perf/0.4.0-phase-d-reorder.md`

---

## 8. Build & Verifizierung

```bash
# Phase-D-Orchestrierung mit Fake-/Unit-Tests
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:streaming:test" \
  -t d-migrate:d19-22 .

# Optional gezielt lokal
./gradlew :adapters:driven:streaming:test
```

Ausgeführt für den umgesetzten Stand:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:streaming:test" \
  -t d-migrate:d19-22 .
```

Ergebnis:

```text
BUILD SUCCESSFUL
```

Schritt 23 ergänzt danach:

- echte SQLite-Streamingtests
- Perf-Run außerhalb der Standard-CI

---

## 9. Abnahmekriterien

- [x] `ImportInput` ist als sealed class mit `Stdin`, `SingleFile`, `Directory` vorhanden
- [x] `ImportInput.Stdin` trägt einen expliziten `InputStream` statt implizitem `System.in`-Zugriff
- [x] `ImportResult`, `TableImportSummary`, `ChunkFailure` und `FailedFinishInfo` sind vorhanden
- [x] `StreamingImporter` ist produktiv in `adapters:driven:streaming` implementiert
- [x] der Importer nutzt die reale Driver-Architektur und führt **keine** `DataWriterRegistry` neu ein
- [x] `Directory.tableFilter` und `Directory.tableOrder` werden verbindlich respektiert und getestet
- [x] Pre-Flight-Fehler laufen immer als Abort, unabhängig von `--on-error`
- [x] Chunk-Fehlerpolitik für `abort`, `skip` und `log` ist implementiert
- [x] `rowsSkipped`, `rowsFailed` und `rowsUnknown` bleiben semantisch sauber getrennt
- [x] `FinishTableResult.PartialFailure` wird strukturiert in `failedFinish` übertragen
- [x] der 0-Chunk-/Empty-Table-Pfad ist im Importer korrekt abgedeckt
- [x] die Phase-D-Unit-Tests laufen ohne CLI-Wrapper und ohne Container

---

## 10. Offene Punkte

- Schritt 23 bleibt als separater Abschluss offen:
  echte SQLite-Streamingtests plus Perf-/JFR-Gate.
- Der destruktive `--truncate`-Pre-Step bleibt für Phase E offen, weil der
  aktuelle Driver-/Port-Zuschnitt in Phase D noch keinen dedizierten
  dialektneutralen Owner dafür hat.
- Der 0.4.0-Masterplan sollte später textlich nachgezogen werden:
  `DataWriterRegistry` in §3.6.1 ist historisch überholt; real gilt
  `DatabaseDriverRegistry`/Driver-Aggregat.
