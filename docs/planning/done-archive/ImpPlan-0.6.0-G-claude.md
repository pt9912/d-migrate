# Phase G: data transfer ohne Zwischenformat

## Context

Phase G fuehrt `data transfer` als direkten DB-zu-DB-Datenpfad ein.
Daten fliessen von `DataReader.streamTable()` direkt zu
`DataWriter.openTable().write()` — kein Zwischenformat, keine
temporaeren Dateien. Der Pfad kombiniert bestehende Export-Flags
(source-seitig) mit Import-Flags (target-seitig) und nutzt ein
target-autoritatives Schema-Preflight fuer Kompatibilitaet und
FK-Reihenfolge.

Docker zum Bauen/Testen:
```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:application:test :adapters:driving:cli:test" \
  -t d-migrate:phase-g .

./scripts/test-integration-docker.sh
docker build -t d-migrate:dev .
```

---

## Implementierungsschritte

### Schritt 1: DataTransferRequest + DataTransferRunner Grundgeruest (G.1, G.2)

**Neue Datei:** `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`

```kotlin
data class DataTransferRequest(
    val source: String,
    val target: String,
    val tables: List<String>? = null,
    val filter: String? = null,
    val sinceColumn: String? = null,
    val since: String? = null,
    val onConflict: String = "abort",
    val triggerMode: String = "fire",
    val truncate: Boolean = false,
    val chunkSize: Int = 10000,
    val cliConfigPath: Path? = null,
    val quiet: Boolean = false,
    val noProgress: Boolean = false,
)
```

Runner-Collaborators (injiziert wie bei Export/Import):
- `sourceResolver: (String, Path?) -> String`
- `targetResolver: (String, Path?) -> String`
- `urlParser: (String) -> ConnectionConfig`
- `poolFactory: (ConnectionConfig) -> ConnectionPool`
- `driverLookup: (DatabaseDialect) -> DatabaseDriver`
- `urlScrubber: (String) -> String`
- `printError: (String, String) -> Unit`
- `stderr: (String) -> Unit`

Runner-Flow:
1. CLI-Vorvalidierung (Exit 2):
   - Identifier-Validierung fuer `--tables`
   - `--since-column` und `--since` nur gemeinsam erlaubt
   - `--since` wird als `DataFilter.ParameterizedClause` typisiert
   - Kombination `--filter` + `--since`: Compound-Filter
   - Literales `?` in `--filter` verboten wenn `--since` aktiv (M-R5)
   - `--truncate` plus explizites `--on-conflict abort` → Widerspruch
   - `--trigger-mode disable`/`strict` auf nicht unterstuetzten
     Dialekten → Exit 2
2. Source-/Target-Aufloesung → Exit 7 bei Config-Fehlern
3. URL-Parsing + Dialektbestimmung → Exit 7
4. Pool-Erzeugung beider Seiten → Exit 4
5. Schema-Read beider Seiten (minimaler Read, keine Views/Routinen) → Exit 4
6. Preflight: Tabellenwahl, Kompatibilitaet, FK-Reihenfolge, Zyklen → Exit 3
7. Streaming: pro Tabelle `DataReader.streamTable()` → Binding-Plan → `DataWriter.openTable().write()` + `commitChunk()` → Exit 5
8. Abschluss-Summary und Progress:
   - `--quiet`: unterdrueckt ALLES ausser Fehler (kein Progress, keine
     Summary) — konsistent mit Export/Import
   - `--no-progress`: unterdrueckt Progress-Updates UND finale
     ProgressSummary, aber nicht explizite Fehlermeldungen
   - ohne Flags: Progress auf stderr waehrend Streaming, finale
     Summary auf stderr nach Abschluss

**Tests:** `DataTransferRunnerTest.kt` mit gemockten Collaborators

---

### Schritt 2: Transfer-Preflight (G.3, G.4, G.5)

**In `DataTransferRunner` oder eigener Helper-Klasse:**

Preflight-Logik (seiteneffektfrei, vor erstem Write):

1. **Tabellenwahl**: `--tables` oder alle Source-Tabellen
   - Bei expliziten `--tables`: Validierung dass jede angeforderte
     Tabelle im **Source-Schema** existiert (Tippfehler → Exit 3,
     nicht erst spaeter als Streaming-Fehler Exit 5)
2. **Target-Existenz**: Jede Kandidatentabelle muss im Target-Schema
   existieren
3. **Spalten- und Typkompatibilitaet**: Source-Spalten muessen im
   Target vorhanden sein und die neutralen Typen muessen ausreichend
   uebereinstimmen (z.B. `integer` → `biginteger` ist ok,
   `text` → `integer` ist Fehler). Target darf zusaetzliche nullable
   Spalten haben. Dialekt-Mismatches werden hier als Exit 3 erkannt,
   nicht erst als spaete Write-Fehler mit Exit 5.
4. **FK-Reihenfolge**: Topologische Sortierung der Kandidatentabellen
   anhand Target-FK-Graph
5. **FK-Zyklen**: Harter Fehler mit Exit 3 (kein stiller Bypass)
6. **PK-Check bei --on-conflict update**: Target-Tabelle muss PK haben
7. **Trigger-Mode-Validierung**: Dialekt-spezifisch

Wiederverwendung aus bestehendem Code:
- `AbstractDdlGenerator` in `driver-common` hat bereits eine
  topologische Sortierung mit Zyklenerkennung fuer die
  DDL-Statement-Reihenfolge
  (`adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`).
  Diese Logik kann in einen gemeinsamen `TopologicalSort`-Helper
  extrahiert oder direkt fuer das Transfer-Preflight wiederverwendet
  werden. Die Sortierung arbeitet auf dem neutralen Modell
  (`SchemaDefinition.tables` + FK-Constraints), nicht auf
  JDBC-Metadaten.

---

### Schritt 3: Streaming-Pipeline (G.6)

**In `DataTransferRunner`:**

Pro Tabelle in FK-sortierter Reihenfolge:
```kotlin
val chunks = dataReader.streamTable(sourcePool, table, filter, chunkSize)
chunks.use { seq ->
    val session = dataWriter.openTable(targetPool, table, importOptions)
    session.use { s ->
        // 1. Binding-Plan gegen s.targetColumns (autoritativ)
        val bindingPlan = buildBindingPlan(seq.columns, s.targetColumns)
        // 2. Pro Chunk
        for (chunk in seq) {
            val normalized = applyBindingPlan(bindingPlan, chunk)
            s.write(normalized)
            s.commitChunk()
        }
        // 3. Erfolgsabschluss
        s.finishTable()
    }
    // session.close() via use {} — idempotentes Cleanup + Connection-Return
}
// chunks.close() via use {} — Connection-Return fuer Source
progressUpdate(table)
```

Kritisch: Sowohl `ChunkSequence` als auch `TableImportSession`
werden via `use { }` (= `AutoCloseable.close()`) verwaltet.
`session.close()` ist der Vertragspunkt fuer Connection-Return
und idempotentes Cleanup auch auf Fehlerpfaden nach `openTable()`
oder nach `finishTable()`. Ohne `use { }` oder aequivalentes
`finally` sind Fehlerpfade leak-gefaehrdet.

Fehler waehrend Streaming → Exit 5 (nicht 4).

Wichtig:
- Der Transfer-Orchestrator delegiert Truncate, Trigger-Handling
  und Reseeding vollstaendig an die bestehenden Port-Vertraege
  (`DataWriter.openTable()` + `TableImportSession.finishTable()`).
  Er fuehrt KEINE dieser Operationen selbst aus.
- Source-Chunks werden NICHT roh an den Writer durchgereicht.
  Zwischen Reader-Output und Writer-Input liegt ein
  Spalten-Normalisierungsschritt basierend auf
  `session.targetColumns` (nicht auf einem vorgelagerten
  Schema-Read). Diese Logik wird aus `StreamingImporter`
  wiederverwendet oder in einen gemeinsamen Helper extrahiert.

Wiederverwendung:
- `DataFilter` aus Export fuer source-seitige Filterung
- `ImportOptions` aus Import fuer target-seitige Write-Optionen
  (traegt bereits `truncate`, `onConflict`, `triggerMode`)
- `StreamingImporter.buildBindingPlan()` oder aequivalenter Helper
  fuer Spalten-Normalisierung — Autoritaet ist `session.targetColumns`
- `ProgressReporter` fuer Fortschrittsanzeige
- Bestehende `ChunkSequence`-Semantik und `TableImportSession`-
  State-Machine (OPEN → write → commitChunk → ... → finishTable)

---

### Schritt 4: CLI-Command (G.1)

**Neue Datei:** `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferCommand.kt`

Clikt-Command mit:
- `--source` (required)
- `--target` (required)
- `--tables`, `--filter`, `--since-column`, `--since`
- `--on-conflict`, `--trigger-mode`, `--truncate`
- `--chunk-size`

Registrierung unter `DataCommand`.

**Datei editieren:** `DataCommand` Help-Text aktualisieren

---

### Schritt 5: Tests + cli-spec.md + Verifikation (G.7)

**Unit-Tests (ohne DB):**
- `DataTransferRunnerTest.kt`: Exit-Codes (0/2/3/4/5/7), Preflight
  (fehlende Tabellen, Typinkompatibilitaet, FK-Zyklen, PK-Check),
  Flag-Kombinationen (since+filter, truncate+abort), Scrubbing,
  quiet/no-progress Verhalten
- CLI-Smoke-Test fuer `data transfer --help`
- Preflight-Helper-Tests fuer Typkompatibilitaet und FK-Sortierung

**Integrationstests (mit Testcontainers):**
- `DataTransferIntegrationTest.kt`: Mindestens ein E2E-Test mit
  zwei echten Datenbanken (z.B. SQLite → SQLite oder
  PostgreSQL → PostgreSQL via Testcontainers) der den vollen Pfad
  abdeckt: Schema-Read, Preflight, Streaming mit Binding-Plan,
  commitChunk(), finishTable(), session.close()
- Gezielt zu pruefen:
  - FK-Reihenfolge bei abhaengigen Tabellen
  - `--truncate` + `--on-conflict update` mit PK
  - Spaltenreihenfolge-Normalisierung
  - `--trigger-mode` (mindestens `fire` und `disable`)

**Docs:**
- `spec/cli-spec.md`: `data transfer` Section aktualisieren

**Docker-Build:**
```bash
docker build -t d-migrate:dev .
```

---

## Betroffene Dateien

### Neue Dateien (~3)

| Datei | Modul |
|-------|-------|
| `application/commands/DataTransferRunner.kt` | application |
| `application/test/DataTransferRunnerTest.kt` | application (test) |
| `cli/commands/DataTransferCommand.kt` | cli |

### Zu aendernde Dateien (~3)

| Datei | Aenderung |
|-------|-----------|
| `cli/commands/DataCommands.kt` (oder aequiv.) | +transfer Subcommand, Help-Text |
| `spec/cli-spec.md` | data transfer als umgesetzt |
| `docs/planning/roadmap.md` | Status |

---

## Abhaengigkeiten zwischen Schritten

```
Schritt 1 (Runner-Grundgeruest) ── Voraussetzung fuer alles
Schritt 2 (Preflight)           ── abhaengig von Schritt 1
Schritt 3 (Streaming)           ── abhaengig von Schritt 2
Schritt 4 (CLI-Command)         ── abhaengig von Schritt 1
Schritt 5 (Tests/Docs)          ── abhaengig von Schritt 2+3+4
```

Schritt 1 zuerst, dann 2+4 parallel, dann 3, dann 5.

---

## Verifikation

```bash
# Unit-Tests
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:application:test :adapters:driving:cli:test" \
  -t d-migrate:phase-g .

# Vollstaendiger Build
docker build -t d-migrate:dev .
```

Gezielt zu pruefen:
- Direkte DB-zu-DB-Uebertragung ohne Zwischendateien
- Preflight erkennt fehlende Target-Tabellen (Exit 3)
- Preflight erkennt FK-Zyklen (Exit 3)
- `--on-conflict update` ohne PK → Exit 3
- Connection-Fehler → Exit 4
- Streaming-Fehler → Exit 5
- Config-Fehler → Exit 7
- Source/Target URLs gescrubbt in allen Ausgaben
- `--tables`, `--filter`, `--since-column/--since` funktionieren
- `--truncate`, `--on-conflict`, `--trigger-mode` funktionieren
- `--quiet` und `--no-progress` unterdruecken Progress
- Kein Export-Default-Fallback, kein Import-Default-Fallback
