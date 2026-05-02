# Implementierungsplan: Phase C – Schritt 12 – DataWriter/TableImportSession Interfaces

> **Milestone**: 0.4.0 — Datenimport und inkrementelle Datenpfade
> **Phase**: C (DataWriter-Port und JDBC-Treiber)
> **Schritt**: 12
> **Status**: Abgeschlossen (2026-04-10)
> **Referenz**: `implementation-plan-0.4.0.md` §3.1.1, §4 Phase C Schritt 12

---

## 1. Ziel

Definition der Writer-seitigen Port-Interfaces `DataWriter` und
`TableImportSession` samt aller zugehörigen Typen in
`hexagon:ports`. Dies ist das symmetrische Gegenstück zum
bestehenden `DataReader`/`ChunkSequence`-Port und bildet den
vollständigen Vertrag ab, gegen den die konkreten Treiber-Writer
(Schritt 15–17) und der `StreamingImporter` (Phase D) implementieren.

Enthaltene Typen (Ziel-Package: `dev.dmigrate.driver.data`):

| Typ | Art |
|---|---|
| `DataWriter` | Interface |
| `TableImportSession` | Interface (extends `AutoCloseable`) |
| `WriteResult` | Data Class |
| `FinishTableResult` | Sealed Class |
| `TargetColumn` | Data Class |
| `UnsupportedTriggerModeException` | Exception |

Zusätzlich werden `SchemaSync` (Interface) und `SequenceAdjustment`
(Data Class) als Minimal-Definitionen angelegt, da `DataWriter.schemaSync()`
und `FinishTableResult.Success` sie referenzieren. Die vollständige
Vertragsdokumentation und Tests für diese beiden Typen folgen in
Schritt 13.

**Vorbereitende Refaktorierung**: `ImportOptions` (samt `TriggerMode`,
`OnConflict`, `OnError`) wird innerhalb von `hexagon:ports` von
`dev.dmigrate.format.data` nach `dev.dmigrate.driver.data` umbenannt
(Package-Rename, siehe §4.1).

---

## 2. Vorbedingungen

| Abhängigkeit | Schritt | Status |
|---|---|---|
| `ImportOptions`, `TriggerMode`, `OnConflict`, `OnError` | Phase A Schritt 2 | ✅ Vorhanden (in `hexagon:ports`, Package `dev.dmigrate.format.data`; wird nach `dev.dmigrate.driver.data` umbenannt) |
| `ImportSchemaMismatchException` | Phase A Schritt 3 | ✅ Vorhanden (in `hexagon:core`) |
| `DataReader` / `DataReaderRegistry` (Symmetrie-Vorlage) | 0.3.0 | ✅ Vorhanden |
| `ConnectionPool` | 0.3.0 | ✅ Vorhanden |
| `DatabaseDialect` | 0.2.0 | ✅ Vorhanden |
| `DataChunk`, `ColumnDescriptor` | 0.3.0 core | ✅ Vorhanden |

---

## 3. Betroffene Dateien

### 3.1 Neue Dateien

| Datei | Zweck |
|---|---|
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/DataWriter.kt` | Writer-Port-Interface |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/TableImportSession.kt` | Session-Interface mit State-Maschine |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/WriteResult.kt` | Per-Chunk-Ergebnis |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/FinishTableResult.kt` | Sealed Result für `finishTable()` |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/TargetColumn.kt` | Writer-side Spalten-Metadaten |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/UnsupportedTriggerModeException.kt` | Exception für nicht-unterstützte Trigger-Modi |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/SchemaSync.kt` | Minimal-Interface (Detailvertrag in Schritt 13) |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/SequenceAdjustment.kt` | Data Class (Detailtests in Schritt 13) |
| `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/data/WriteResultTest.kt` | Unit-Tests WriteResult |
| `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/data/FinishTableResultTest.kt` | Unit-Tests FinishTableResult |
| `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/data/TargetColumnTest.kt` | Unit-Tests TargetColumn |
| `adapters/driven/driver-common/src/test/kotlin/dev/dmigrate/driver/data/TableImportSessionContractTest.kt` | State-Maschine-Vertragstests mit Stub-Writer |

### 3.2 Umbenannte Dateien (Package-Rename innerhalb `hexagon:ports`)

| Von | Nach | Grund |
|---|---|---|
| `hexagon/ports/src/main/kotlin/dev/dmigrate/format/data/ImportOptions.kt` | `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/ImportOptions.kt` | Port-Level-Konfiguration gehört zum Writer-Port-Package (siehe §4.1) |

### 3.3 Geänderte Dateien

| Datei | Änderung |
|---|---|
| `hexagon/ports/src/main/kotlin/dev/dmigrate/format/data/DataChunkReaderFactory.kt` | Import hinzufügen: `dev.dmigrate.driver.data.ImportOptions` (bisher same-package, jetzt anderes Package) |
| `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/DefaultDataChunkReaderFactory.kt` | Import-Pfad: `dev.dmigrate.format.data.ImportOptions` → `dev.dmigrate.driver.data.ImportOptions` |
| `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/csv/CsvChunkReader.kt` | Import-Pfad analog |
| `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/json/JsonChunkReader.kt` | Import-Pfad analog |
| `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/yaml/YamlChunkReader.kt` | Import-Pfad analog |
| Alle Test-Dateien, die `ImportOptions` referenzieren | Import-Pfad analog |
| `adapters/driven/driver-common/build.gradle.kts` | Kover-Excludes für neue pure Interfaces hinzufügen |

---

## 4. Design

### 4.1 ImportOptions Package-Rename innerhalb `hexagon:ports`

`DataWriter.openTable()` nimmt laut Plan §3.1.1 `ImportOptions` als
Parameter. `ImportOptions` lebt bereits in `hexagon:ports`, aber
im Package `dev.dmigrate.format.data` (neben den Reader-Factory-
Interfaces). Da `ImportOptions` primär Port-Level-Konfiguration für
den Writer-Port enthält (Trigger-Modi, FK-Check-Steuerung,
Conflict-Verhalten, Sequence-Reseeding), gehört es semantisch ins
Package `dev.dmigrate.driver.data` — dort, wo auch `DataWriter`,
`DataReader` und die übrigen Treiber-Port-Interfaces liegen.

**Warum nicht in `hexagon:core`?** `ImportOptions` enthält
Vertragsparameter für den Writer-Port, nicht domänenneutrale
Modelltypes. Eine Platzierung in `hexagon:core` würde die
Domain-Core-Schicht mit adapterspezifischer Konfiguration
verschmutzen und gegen den Architekturstil „Neutrales Modell —
keine externen Deps" verstoßen.

Die Änderung ist ein reiner Package-Rename innerhalb desselben
Moduls (`hexagon:ports`). Die Datei-Inhalte bleiben unverändert;
nur Package-Deklaration und Import-Statements in Consumer-Klassen
werden angepasst. Da `DataChunkReaderFactory.kt` bisher im selben
Package lag (same-package-Zugriff), braucht es dort einen neuen
expliziten Import.

### 4.2 DataWriter Interface

Symmetrisch zu `DataReader`, aber mit Session-basiertem Schreibzyklus
statt Sequence-basiertem Lesezyklus:

```
DataReader.streamTable(pool, table, ...) → ChunkSequence (pull)
DataWriter.openTable(pool, table, options)  → TableImportSession (push)
```

- `dialect` — Symmetrie zu `DataReader.dialect`, für Registry-Lookup.
- `schemaSync()` — pflichtig, keine Default-No-Op-Implementierung.
  Rückgabetyp `SchemaSync` wird als Vorab-Definition aus Schritt 13
  mit angelegt.
- `openTable(...)` — borgt Connection aus Pool, baut
  PreparedStatement, liefert Session. **H1-Cleanup-Vertrag**: bei
  Exception nach bereits ausgeführten Side-Effects (disableTriggers)
  muss der Writer intern aufräumen, bevor er wirft (siehe Plan §3.1.1
  H1-Dokumentation).

### 4.3 TableImportSession State-Maschine (M1)

Die Session implementiert eine strenge State-Maschine mit fünf
Zuständen:

```
OPEN     --write(chunk)--------------> WRITTEN
OPEN     --finishTable() [0-Chunk]---> FINISHED
OPEN     --markTruncatePerformed()---> OPEN       (idempotent, nur vor erstem write)
OPEN     --close()------------------> CLOSED

WRITTEN  --commitChunk() ok---------> OPEN
WRITTEN  --rollbackChunk() ok-------> OPEN
WRITTEN  --commitChunk() wirft------> FAILED
WRITTEN  --rollbackChunk() wirft----> FAILED
WRITTEN  --close()------------------> CLOSED

FAILED   --close()------------------> CLOSED      (einzige erlaubte Op)

FINISHED --close()------------------> CLOSED

CLOSED   --close()------------------> CLOSED      (idempotent)
```

Jede nicht aufgeführte Transition wirft `IllegalStateException` — das
sind Importer-Bugs, keine tolerierten Edge-Cases. Insbesondere gilt:
`markTruncatePerformed()` ist auch aus `OPEN` verboten, sobald
mindestens ein `write()` stattgefunden hat (auch nach `commitChunk()`
zurück in OPEN). Das `hasWritten`-Flag ist dabei sticky — einmal
gesetzt, bleibt es true.

Die State-Maschine wird in Phase D mit einem Fake-Writer vollständig
abgefahren; Phase C definiert hier den Vertrag und testet ihn mit
einem minimalen Stub.

### 4.4 WriteResult und FinishTableResult

- `rowsUnknown` — R10: MySQL `SUCCESS_NO_INFO (-2)`-Fallback
- Invariante: alle Felder `≥ 0`

- `Success` — Reseeding + Trigger-Reenable erfolgreich
- `PartialFailure` — Reseeding ok, Trigger-Reenable gescheitert (H4)

### 4.5 TargetColumn

Writer-seitige Spalten-Metadaten mit JDBC-Typcode. Lebt bewusst in
`hexagon:ports` (nicht in `hexagon:core`), weil `core.ColumnDescriptor` JDBC-frei
bleiben soll (L15).

Die Konversion `TargetColumn → JdbcTypeHint` erfolgt erst im Phase-D
`StreamingImporter`, der beide Module kennt.

### 4.6 UnsupportedTriggerModeException

Geworfen von `SchemaSync.disableTriggers()` auf MySQL/SQLite (Plan
§3.3/§3.4).

### 4.7 Vorab-Definitionen für Schritt 13

Damit `DataWriter` und `FinishTableResult` kompilieren, werden
`SchemaSync` und `SequenceAdjustment` bereits als Minimal-Typen
angelegt. Die vollständige Vertragsdokumentation, Tests und die
`Connection`-Parameter-Signatur werden in Schritt 13 finalisiert.

---

## 5. Implementierung

### 5.1 ImportOptions Package-Rename

1. `ImportOptions.kt` innerhalb von `hexagon/ports` verschieben:
   `hexagon/ports/.../format/data/ImportOptions.kt` →
   `hexagon/ports/.../driver/data/ImportOptions.kt`.
2. Package-Deklaration ändern: `dev.dmigrate.format.data` →
   `dev.dmigrate.driver.data`.
3. `import java.nio.charset.Charset` bleibt unverändert (JDK-Klasse,
   keine neue Abhängigkeit).
4. In `DataChunkReaderFactory.kt` (selbes Modul, bisher same-package-
   Zugriff) expliziten Import ergänzen:
   `import dev.dmigrate.driver.data.ImportOptions`.
5. Alle Import-Statements in Consumer-Klassen anderer Module
   (Reader, Tests in `adapters:driven:formats`) anpassen:
   `dev.dmigrate.format.data.ImportOptions` →
   `dev.dmigrate.driver.data.ImportOptions` (analog für `TriggerMode`,
   `OnConflict`, `OnError`).
6. Build verifizieren: `docker build --target build
   --build-arg GRADLE_TASKS=":adapters:driven:driver-common:test :adapters:driven:formats:test"
   -t d-migrate:c12-prep .`

### 5.2 `DataWriter.kt`

### 5.3 `TableImportSession.kt`

Nutzungsbeispiel:

```kotlin
val session = writer.openTable(pool, table, options)
session.use {
    for (chunk in chunks) {
        it.write(chunk)
        it.commitChunk()   // oder rollbackChunk() bei Fehler
    }
    it.finishTable()
}
```

### 5.4 `WriteResult.kt`

### 5.5 `FinishTableResult.kt`

### 5.6 `TargetColumn.kt`

### 5.7 `UnsupportedTriggerModeException.kt`

### 5.8 `SchemaSync.kt` (Vorab-Definition)

### 5.9 `SequenceAdjustment.kt` (Vorab-Definition)

---

## 6. Tests

### 6.1 Teststrategie

Drei Achsen:

1. **Data-Class-Invarianten**: `WriteResult` Validierung, `FinishTableResult`
   Sealed-Class-Dispatch, `TargetColumn` Gleichheit.
2. **State-Maschine-Vertrag**: `TableImportSession`-Kontrakt wird über
   einen minimalen `StubTableImportSession` abgefahren. Jede erlaubte und
   verbotene Transition wird geprüft.
3. **Smoke-Check**: `DataWriter`, `SchemaSync` und `UnsupportedTriggerModeException`
   sind reine Interfaces/Exceptions ohne eigene Logik — kein
   separater Unit-Test nötig (Coverage-Exclude, siehe §7).

### 6.2 Testfälle — `WriteResultTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `basic construction` | Felder korrekt gesetzt, `totalRows` berechnet |
| 2 | `default rowsUnknown is 0` | Drei-Arg-Konstruktor → `rowsUnknown == 0` |
| 3 | `totalRows sums all fields` | Alle vier Felder aufsummiert |
| 4 | `rejects negative rowsInserted` | `IllegalArgumentException` |
| 5 | `rejects negative rowsUpdated` | `IllegalArgumentException` |
| 6 | `rejects negative rowsSkipped` | `IllegalArgumentException` |
| 7 | `rejects negative rowsUnknown` | `IllegalArgumentException` |
| 8 | `all-zero is valid` | `WriteResult(0,0,0,0)` → `totalRows == 0` |
| 9 | `data class equality` | Gleiche Werte → `equals == true` |
| 10 | `data class copy` | `copy(rowsUpdated = 5)` ändert nur dieses Feld |

### 6.3 Testfälle — `FinishTableResultTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `Success carries adjustments` | `Success(listOf(adj))` → `adjustments` korrekt |
| 2 | `Success with empty adjustments` | `Success(emptyList())` → gültig |
| 3 | `PartialFailure carries adjustments and cause` | Felder korrekt gesetzt |
| 4 | `sealed dispatch via when` | Exhaustiver `when`-Block kompiliert und dispatcht korrekt |

### 6.4 Testfälle — `TargetColumnTest.kt`

| # | Testname | Prüfung |
|---|---|---|
| 1 | `basic construction` | Alle Felder korrekt |
| 2 | `sqlTypeName defaults to null` | Drei-Arg-Konstruktor → `sqlTypeName == null` |
| 3 | `data class equality` | Gleiche Werte → equals |
| 4 | `different jdbcType → not equal` | Unterschiedlicher Typcode → not equals |

### 6.5 Testfälle — `TableImportSessionContractTest.kt`

State-Maschine-Vertragstests mit einem minimalen `StubTableImportSession`,
der die State-Transitions als In-Memory-Enum verwaltet, ohne
JDBC-Abhängigkeit.

| # | Testname | Prüfung |
|---|---|---|
| 1 | `initial state is OPEN` | Frisch erzeugte Session akzeptiert `write()` |
| 2 | `OPEN → write → WRITTEN` | `write()` liefert `WriteResult` |
| 3 | `WRITTEN → commitChunk → OPEN` | Nach Commit wieder bereit für nächsten Chunk |
| 4 | `WRITTEN → rollbackChunk → OPEN` | Nach Rollback wieder bereit |
| 5 | `double write without commit throws` | `write()` aus WRITTEN → `IllegalStateException` |
| 6 | `commitChunk from OPEN throws` | Kein vorangegangener Write → `IllegalStateException` |
| 7 | `rollbackChunk from OPEN throws` | Analog |
| 8 | `OPEN → finishTable → FINISHED` | 0-Chunk-Pfad (F1) gültig |
| 9 | `finishTable from WRITTEN throws` | Implizites Commit verhindern → `IllegalStateException` |
| 10 | `FINISHED → close → CLOSED` | Normaler Abschluss |
| 11 | `close is idempotent` | Mehrfacher `close()` wirft nicht |
| 12 | `write after close throws` | `IllegalStateException` |
| 13 | `commitChunk after close throws` | `IllegalStateException` |
| 14 | `finishTable after close throws` | `IllegalStateException` |
| 15 | `markTruncatePerformed from OPEN` | Idempotent, kein State-Wechsel |
| 16 | `markTruncatePerformed after write throws` | Aus WRITTEN → `IllegalStateException` |
| 17 | `FAILED state — only close allowed` | Simulierter commitChunk-Fehler → nur `close()` erlaubt |
| 18 | `write from FAILED throws` | `IllegalStateException` |
| 19 | `finishTable from FAILED throws` | `IllegalStateException` |
| 20 | `markTruncatePerformed from FAILED throws` | `IllegalStateException` |
| 21 | `write-commit-write-commit-finish cycle` | Multi-Chunk Happy Path |
| 22 | `close from OPEN without any write` | Cleanup-Pfad ohne Daten |

### 6.6 Stub-Design für State-Maschine-Tests

Dieser Stub wird ausschließlich in den Vertragstests verwendet. Die
konkreten Treiber-Writer (Schritt 15–17) bauen ihre eigene
State-Maschinen-Implementierung; Phase D fährt sie dann mit einem
Fake-Writer ab.

---

## 7. Coverage-Ziel

| Typ | Coverage | Anmerkung |
|---|---|---|
| `WriteResult` | 100 % | `require`-Validierung + `totalRows` |
| `FinishTableResult` | 100 % | Sealed-Class-Dispatch |
| `TargetColumn` | 100 % | Data-Class-Felder |
| `StubTableImportSession` | 100 % | Wird vollständig über Vertragstests abgefahren |
| `DataWriter` | Exclude | Reines Interface ohne Default-Methoden |
| `TableImportSession` | Exclude | Reines Interface ohne Default-Methoden |
| `SchemaSync` | Exclude | Reines Interface (Tests in Schritt 13) |
| `SequenceAdjustment` | Exclude | Pure Data Class (Tests in Schritt 13) |
| `UnsupportedTriggerModeException` | Exclude | Triviale Exception-Subclass |

Kover-Excludes in `build.gradle.kts` hinzufügen (bisher keine Excludes vorhanden):

Gesamtziel pro Modul: ≥ 90 % (Plan §8).

---

## 8. Build & Verifizierung

```bash
# 1. ImportOptions-Verschiebung verifizieren
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:driver-common:test :adapters:driven:formats:test" \
  -t d-migrate:c12-prep .

# 2. Neue Interfaces + Tests in hexagon:ports / adapters:driven:driver-common
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:driver-common:test" \
  -t d-migrate:c12 .

# 3. Vollständiger Build inkl. Coverage-Verification
docker build -t d-migrate:dev .
```

**Ergebnis (2026-04-10)**:

| Build | Status |
|---|---|
| `d-migrate:c12-prep` (ImportOptions-Rename + formats + driver-common) | BUILD SUCCESSFUL |
| `d-migrate:c12` (driver-common Tests isoliert) | BUILD SUCCESSFUL |
| `d-migrate:dev` (Vollbuild inkl. Kover ≥ 90 % alle Module) | BUILD SUCCESSFUL |

---

## 9. Abnahmekriterien

- [x] `ImportOptions.kt` lebt in `hexagon:ports` (`dev.dmigrate.driver.data`)
- [x] Alle bestehenden Tests grün nach der Verschiebung
- [x] `DataWriter` Interface in `hexagon:ports`
- [x] `TableImportSession` Interface mit dokumentierter State-Maschine (M1)
- [x] `WriteResult` mit `require`-Validierung und `totalRows`
- [x] `FinishTableResult` als Sealed Class (Success/PartialFailure)
- [x] `TargetColumn` mit `jdbcType` (L15)
- [x] `UnsupportedTriggerModeException`
- [x] `SchemaSync` und `SequenceAdjustment` als Vorab-Definitionen
- [x] State-Maschine-Vertragstests (24 Tests) grün
- [x] WriteResult-Tests (10 Tests) grün
- [x] FinishTableResult-Tests (4 Tests) grün
- [x] TargetColumn-Tests (4 Tests) grün
- [x] Kover-Excludes für pure Interfaces gesetzt
- [x] Coverage ≥ 90 % für `adapters:driven:driver-common`
- [x] Docker-Build (`docker build -t d-migrate:dev .`) ist grün

---

## 10. Offene Punkte

Keine. Die Design-Entscheidungen folgen direkt aus Plan §3.1.1. Der
ImportOptions-Package-Rename von `format.data` nach `driver.data`
innerhalb von `hexagon:ports` ist die einzige Abweichung vom Plan und
architektonisch begründet (Port-Level-Konfiguration gehört zum
Writer-Port-Package, vgl. `architecture.md` §1.2).
