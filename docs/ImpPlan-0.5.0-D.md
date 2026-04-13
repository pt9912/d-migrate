# Implementierungsplan: Phase D - MVP-Fortschrittsanzeige

> **Milestone**: 0.5.0 - MVP-Release
> **Phase**: D (MVP-Fortschrittsanzeige)
> **Status**: Done (2026-04-13)
> **Referenz**: `implementation-plan-0.5.0.md` Abschnitt 4.4, Abschnitt 5
> Phase D, Abschnitt 7, Abschnitt 8, Abschnitt 9.2; `docs/cli-spec.md`
> Abschnitt 7; `docs/implementation-plan-0.4.0.md` Abschnitt 6.11

---

## 1. Ziel

Phase D fuehrt fuer bestehende langlaufende CLI-Operationen ein einheitliches,
ereignisbasiertes Progress-Konzept ein, ohne die bisherige Skriptfaehigkeit,
Exit-Code-Stabilitaet oder Testbarkeit zu verlieren.

Der MVP-Schnitt fuer 0.5.0 konzentriert sich bewusst auf:

- `data export`
- `data import`

Das Ergebnis der Phase ist **kein** vollwertiges TUI-System und auch keine
parallelisierte Multi-Table-Anzeige, sondern ein robuster line-orientierter
stderr-Pfad mit:

- Tabellenstart
- Chunk-/Zeilenfortschritt
- Tabellenabschluss
- unveraenderter finaler Summary-Semantik

Wichtig:

- stdout bleibt sauber fuer eigentliche Command-Ergebnisse
- `--quiet` und `--no-progress` behalten ihre bestehende Bedeutung
- JSON-Output bekommt **keine** JSON-Progress-Events

---

## 2. Ausgangslage

Aktueller Stand der Codebasis:

- `data export` und `data import` besitzen heute bereits eine **finale**
  ProgressSummary auf `stderr`:
  - Export via `DataExportHelpers.formatProgressSummary(...)`
  - Import via `DataImportRunner.formatProgressSummary(...)`
- Diese Summary wird derzeit erst **nach** Abschluss der Streaming-Operation
  ausgegeben.
- Es gibt aktuell **keinen** gemeinsamen `ProgressEvent`-Typ und keinen
  injizierbaren Progress-Reporter.
- Der eigentliche langlaufende Tabellen-/Chunk-Loop sitzt nicht in den Runnern,
  sondern in:
  - `adapters/driven/streaming/.../StreamingExporter.kt`
  - `adapters/driven/streaming/.../StreamingImporter.kt`
- `StreamingImporter` besitzt heute bereits einen separaten Callback
  `onTableOpened(...)`, dieser dient aber der Schema-/Target-Validierung und
  ist **kein** Progress-Mechanismus.
- Export- und Import-Runner respektieren bereits heute die Flags:
  - `--quiet`: keine Warnings, keine Summary, nur Fehler
  - `--no-progress`: keine Summary; bei Export bleiben Warnings sichtbar
- Diese Semantik ist in bestehenden Runner- und CLI-Tests bereits abgesichert.

Wichtige Spannungen zwischen Spec und Ist-Zustand:

- `docs/cli-spec.md` Abschnitt 7 zeigt aktuell eine Fortschrittsanzeige mit
  mehreren gleichzeitig "active" Tabellen.
- Das ist mit der realen Architektur in 0.5.0 nicht konsistent:
  - Export und Import laufen heute single-threaded und sequenziell
  - es gibt in 0.5.0 immer hoechstens **eine** aktive Tabelle gleichzeitig
- Die CLI-Spec spricht von automatischer Anzeige fuer Operationen `>2` Sekunden.
- Die aktuelle CLI kennt keine Zeit-Schwelle; die finale Summary erscheint
  deterministisch auch bei kurzen Laeufen.

Konsequenz fuer Phase D:

- Progress-Events muessen dort entstehen, wo Tabellen und Chunks wirklich
  verarbeitet werden: im Streaming-Adapter
- die neue Event-Infrastruktur darf die bestehende Summary nicht ersetzen,
  sondern muss auf ihr aufbauen
- die CLI-Spec muss auf den tatsaechlichen MVP-Vertrag abgeschaerft werden

---

## 3. Scope fuer Phase D

### 3.1 In Scope

- gemeinsamer Progress-Vertrag fuer Streaming-Operationen
- line-orientierte stderr-Progressanzeige fuer `data export`
- line-orientierte stderr-Progressanzeige fuer `data import`
- Ereignisse fuer:
  - Run-Start
  - Table-Start
  - Chunk-Fortschritt
  - Table-Ende
- Wahrung der bestehenden finalen ProgressSummary
- Wiring ueber Runner- und CLI-Grenzen ohne direkte CLI-Ausgabe aus dem
  Streaming-Pfad
- Tests fuer:
  - Event-Emission
  - Flag-Semantik `--quiet` / `--no-progress`
  - Nicht-Regression der finalen Summary
- Doku-Abgleich in `docs/cli-spec.md` Abschnitt 7

### 3.2 Bewusst nicht Teil von Phase D

- ANSI-Progress-Bar mit Cursor-Rewrite
- spinners, Prozentwerte oder ETA als Pflicht
- mehrere gleichzeitig aktive Tabellen
- JSON- oder YAML-Progress-Streaming
- Progress fuer `schema validate`, `schema generate` oder `schema compare`
- Progress fuer noch nicht implementierte Commands wie `schema reverse`,
  `schema migrate` oder `data seed`
- generische Time-Threshold-Logik wie "erst nach 2 Sekunden anzeigen"

---

## 4. Architekturentscheidungen

### 4.1 Progress-Vertrag lebt in `hexagon:ports`, nicht in `hexagon:application`

Der Masterplan spricht von einem application-nahen `ProgressEvent`-Typ. In der
realen Modulstruktur waere eine Platzierung in `hexagon:application` jedoch
falsch, weil die Event-Emission in `StreamingExporter` und `StreamingImporter`
sitzt und `adapters:driven:streaming` **nicht** von `hexagon:application`
abhaengen darf.

Verbindliche Entscheidung fuer 0.5.0:

- der gemeinsame Progress-Vertrag liegt unter
  `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/`
- dort entstehen:
  - `ProgressEvent`
  - `ProgressReporter`
  - kleine Enum-/Hilfstypen fuer Operation und Status

Das passt zur bestehenden Verortung von:

- `PipelineConfig`
- `ExportResult`
- `ImportResult`

Damit bleibt der Vertrag:

- adapteruebergreifend nutzbar
- testbar
- ohne zyklische Modulabhaengigkeiten

### 4.2 Bestehende ProgressSummary bleibt die Kompatibilitaetsbasis

Phase D ersetzt **nicht** die bereits existierenden finalen Summary-Strings.
Diese sind heute Teil des Nutzervertrags und bereits in Tests verankert.

Verbindliche Regel:

- die finale Summary fuer Export/Import bleibt in 0.5.0 erhalten
- die neue Event-Anzeige ist eine **Ergaenzung waehrend des Laufs**
- die Phase baut also auf "incremental events + bestehende summary", nicht auf
  "neues System statt altem System"

Pragmatische Konsequenz:

- `DataExportHelpers.formatProgressSummary(...)` bleibt zunaechst bestehen
- `DataImportRunner.formatProgressSummary(...)` bleibt zunaechst bestehen
- eine spaetere Vereinheitlichung in einen gemeinsamen Summary-Renderer ist
  moeglich, aber kein Pflichtteil von 0.5.0

### 4.3 Event-Emission gehoert in den Streaming-Pfad, nicht in die Runner

Die Runner sehen heute nur:

- Eingabeaufloesung
- Pool-/Registry-Wiring
- finales `ExportResult` bzw. `ImportResult`

Sie sehen **nicht** die eigentlichen Chunk-Schleifen. Deshalb duerfen Runner in
Phase D keine kuenstliche Pseudo-Fortschrittslogik auf Basis des Endergebnisses
simulieren.

Verbindliche Regel:

- `StreamingExporter` emittiert Export-Events
- `StreamingImporter` emittiert Import-Events
- Runner reichen nur einen Reporter durch und unterdruecken ihn bei Bedarf

Die Konsequenz fuer die Tests:

- echte Event-Sequenzen werden primaer im Streaming-Modul getestet
- Runner-Tests pruefen Suppression und Nicht-Regression

### 4.4 `--quiet` und `--no-progress` behalten ihre heutige Semantik

Hier gibt es eine Spannung zwischen Masterplan-Formulierung und aktueller
Codebasis. Die heutige, bereits getestete Semantik lautet:

- `--quiet`
  - unterdrueckt ProgressSummary
  - unterdrueckt Export-Warnings
  - laesst nur Fehler-/Failure-Pfade sichtbar
- `--no-progress`
  - unterdrueckt die finale ProgressSummary
  - unterdrueckt auch die neuen Zwischen-Events
  - laesst nicht-progressbezogene stderr-Ausgaben sichtbar
  - bei Export also insbesondere Warnings

Verbindliche Entscheidung fuer 0.5.0:

- diese Semantik wird **nicht** gebrochen
- neue Zwischen-Events gelten ebenfalls als "Progress" und werden unter
  `--no-progress` unterdrueckt

Damit bleibt `--no-progress` in Export/Import voll abwaertskompatibel.

### 4.5 Line-orientiert statt Dashboard

Die CLI-Spec zeigt derzeit ein Board mit `active`, `completed` und `pending`
Tabellen. Das passt nicht zur 0.5.0-Realitaet:

- keine Parallelisierung
- keine Cursor-Rewrites
- keine stabile Terminal-Breite/Interaktivitaet als Voraussetzung

Verbindliche Entscheidung:

- die MVP-Anzeige ist **line-orientiert**
- pro Event wird hoechstens eine Zeile geschrieben
- es gibt zu jedem Zeitpunkt hoechstens eine aktive Tabelle
- ein "pending/active/completed"-Panel ist nicht Teil von 0.5.0

Beispielrichtung:

```text
Exporting 3 table(s)
Exporting table 'users' (1/3)
Exporting table 'users' | chunk 1 | 10,000 rows | 0.82 MB
Exported table 'users' | 12,345 rows | 2 chunks | 1.01 MB
```

und fuer Import:

```text
Importing 2 table(s)
Importing table 'orders' (1/2)
Importing table 'orders' | chunk 1 | 10,000 rows processed | 9,980 inserted, 20 skipped
Imported table 'orders' | 12,000 inserted, 20 skipped
```

### 4.6 Keine >2s-Schwelle im MVP

Eine Zeit-Schwelle wuerde den MVP unnoetig verkomplizieren:

- Buffering von Events bis zur Schwelle
- Timing-abhaengige Tests
- unscharfe Regeln fuer sehr kurze Laeufe

Verbindliche Entscheidung:

- fuer `data export` und `data import` wird Progress deterministisch emittiert,
  sobald ein Reporter aktiv ist
- es gibt in 0.5.0 **keine** Laufzeit-Schwelle wie "erst ab 2 Sekunden"

Die CLI-Spec wird entsprechend ehrlich angepasst.

### 4.7 Import-Fortschritt wird erst nach finalisiertem Chunk-Zustand gemeldet

Beim Import waere ein Event direkt nach `session.write(...)` fachlich falsch,
weil:

- `commitChunk()` noch scheitern kann
- ein Rollback den vorherigen Zwischenstand invalidieren kann

Verbindliche Regel:

- Import-Chunk-Events entstehen erst nach feststehendem Chunk-Ergebnis
- also:
  - nach erfolgreichem `commitChunk()`
  - oder nach einem bewusst fortgesetzten chunk-lokalen Failure mit bekannter
    verlorener Row-Anzahl
- Reader-Failures oder unrecoverte Write-/Commit-Failures fuehren dagegen direkt
  in den Tabellenfehlerpfad; dafuer ist kein zusaetzliches Chunk-Event Pflicht

Damit bleiben die gezeigten Zaehler monoton und vertrauenswuerdig.

### 4.8 `onTableOpened(...)` bleibt separat

`StreamingImporter` besitzt heute bereits `onTableOpened(...)` fuer
Schema-/Target-Pruefungen. Dieser Hook bleibt fachlich getrennt.

Verbindliche Regel:

- Progress und Schema-Validierung werden nicht ueber denselben Callback
  zusammengeworfen
- `onTableOpened(...)` bleibt fuer Preflight/Validierung
- `ProgressReporter` ist ein eigener Kanal

### 4.9 Fehler und manuelle Folgeaktionen bleiben ausserhalb des Progress-Renderers

Nicht alles auf `stderr` ist "Progress".

Verbindliche Regel:

- Fehlerausgaben wie:
  - `Error: ...`
  - Import-`failedFinish`-/Manual-Fix-Hinweise
  - Export-Warnings
  bleiben eigene stderr-Pfade
- der Progress-Renderer formatiert nur `ProgressEvent`s

Damit bleibt klar:

- `--no-progress` unterdrueckt nur Progress
- `--quiet` unterdrueckt zusaetzlich Warnings
- echte Fehler-/Failure-Pfade bleiben sichtbar

### 4.10 Import-Status folgt dem Rueckgabevertrag, nicht jedem Throw-Pfad

Beim Import gibt es drei relevante Failure-Klassen:

- ein Tabellenpfad liefert regulaer eine `TableImportSummary` mit `error != null`
- ein Tabellenpfad liefert regulaer eine `TableImportSummary` mit
  `failedFinish != null`
- ein harter Fehler wirft vor dem Summary-Aufbau und bricht den Tabellenlauf
  sofort ab

Verbindliche Regel fuer 0.5.0:

- `ImportTableFinished(status = FAILED)` ist fuer alle Tabellenpfade Pflicht,
  die mit einer `TableImportSummary` in den Resultatpfad zurueckkehren
- dazu gehoeren sowohl `error != null` als auch `failedFinish != null`
- fuer harte Exception-Pfade vor dem Summary-Aufbau wird **kein** synthetisches
  `ImportTableFinished` garantiert
- falls die Implementierung spaeter Catch/Report/Rethrow fuer solche harten
  Abbrueche hinzufuegt, darf das den Originalfehler nicht verdecken

Damit bleibt der Event-Vertrag konsistent mit der heutigen Import-Architektur:

- sichtbare Tabellenfehler werden als `FAILED` signalisiert
- ungefangene Fatal-Pfade bleiben ungefangen und veraendern ihren Exit-Pfad nicht

### 4.11 Globales `--output-format` bleibt fuer Export/Import ausserhalb des Runner-Vertrags

`--output-format` ist heute ein globales Root-CLI-Flag. `data export` und
`data import` liefern aber keine strukturierten Command-Ergebnisse auf stdout,
die in Phase D neu serialisiert werden muessten.

Verbindliche Regel fuer 0.5.0:

- Phase D fuehrt **kein** neues `outputFormat`-Feld in
  `DataExportRequest`/`DataImportRequest` ein
- Phase D fuehrt **kein** JSON-/YAML-Progress-Streaming ein
- wenn das Root-Flag `--output-format json|yaml` gesetzt ist, bleibt der
  Progress-Pfad fuer Export/Import trotzdem plain-text auf `stderr`
- dieser Punkt ist eine CLI-Wiring-/Nicht-Interaktions-Regel, kein neuer
  fachlicher Runner-Vertrag

Damit wird der Scope klein gehalten: Die Phase verbessert den stderr-Fortschritt,
ohne die Export-/Import-Requests unnoetig um einen bislang irrelevanten
Serialisierungsparameter zu erweitern.

### 4.12 Neuer Progress-Renderer formatiert deterministisch, legacy Summary bleibt unberuehrt

Die neue line-orientierte Event-Anzeige bekommt eigene Render-Regeln. Diese
duerfen nicht von der JVM-Default-Locale abhaengen, sonst werden exakte
Renderer-Tests instabil.

Verbindliche Regel fuer 0.5.0:

- der neue `ProgressRenderer` formatiert Zahlen mit explizit fixer Locale oder
  aequivalent deterministischen Formatierern
- empfohlen ist `Locale.US` oder eine funktional gleichwertige explizite
  Formatter-Konfiguration
- die Beispielsyntax in Abschnitt 6.6 ist fuer den neuen Renderer die
  Zielrichtung: Tausendertrennung mit `,`, Dezimaltrennung mit `.`
- die bestehende finale ProgressSummary fuer Export/Import bleibt in Phase D
  bewusst unberuehrt und muss deshalb nicht auf exakte locale-neutrale Strings
  umgebaut werden

Damit gilt:

- neue Event-Zeilen duerfen in Renderer-Tests exakt geprueft werden
- bestehende Summary-Tests bleiben bewusst robuster und muessen keine exakten
  Dezimalformate festschreiben

---

## 5. Betroffene Dateien und Module

### 5.1 Neue Produktionsdateien

| Datei | Zweck |
|---|---|
| `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt` | gemeinsamer Event-Vertrag fuer Export/Import |
| `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressReporter.kt` | Reporter-Interface inkl. No-Op-Variante |
| `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt` | CLI-seitige line-orientierte stderr-Formatierung |

Hinweis:

- Ob `ProgressReporter` und `ProgressEvent` in einer oder zwei Dateien liegen,
  ist ein Implementierungsdetail.
- Verbindlich ist nur die Verortung unter `hexagon:ports` im Package
  `dev.dmigrate.streaming`.

### 5.2 Geaenderte Produktionsdateien

| Datei | Aenderung |
|---|---|
| `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt` | Export-Events emittieren |
| `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingImporter.kt` | Import-Events emittieren |
| `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt` | Reporter-Wiring und Suppression fuer `--quiet` / `--no-progress` |
| `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt` | Reporter-Wiring und Suppression fuer `--quiet` / `--no-progress` |
| `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt` | CLI-ProgressRenderer instanziieren und in Runner verdrahten |
| `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt` | CLI-ProgressRenderer instanziieren und in Runner verdrahten |
| `docs/cli-spec.md` | Fortschrittsabschnitt auf line-orientierten MVP und echte Flag-Semantik abgleichen |

Optional, nur wenn fuer Klarheit noetig:

| Datei | Aenderung |
|---|---|
| `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt` | Summary-Helfer nur falls kleine Signatur-/Doc-Anpassungen noetig sind |

### 5.3 Geaenderte Testdateien

| Datei | Zweck |
|---|---|
| `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingExporterTest.kt` | Event-Sequenzen fuer Export |
| `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterTest.kt` | Event-Sequenzen fuer Import |
| `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt` | Runner-Suppression und Summary-Erhalt |
| `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt` | Runner-Suppression und Summary-Erhalt |
| `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt` | echte stderr-Verifikation fuer Export |
| `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataImportTest.kt` | echte stderr-Verifikation fuer Import |

### 5.4 Neue Testdateien

| Datei | Zweck |
|---|---|
| `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/output/ProgressRendererTest.kt` | line-orientierte Render-Tests fuer Events |

---

## 6. Progress-Vertrag

### 6.1 Minimaler Typ-Schnitt

Empfohlene Richtung:

```kotlin
enum class ProgressOperation {
    EXPORT,
    IMPORT,
}

enum class TableProgressStatus {
    COMPLETED,
    FAILED,
}

fun interface ProgressReporter {
    fun report(event: ProgressEvent)
}

sealed interface ProgressEvent {
    data class RunStarted(
        val operation: ProgressOperation,
        val totalTables: Int,
    ) : ProgressEvent

    data class ExportTableStarted(...)
    data class ExportChunkProcessed(...)
    data class ExportTableFinished(...)

    data class ImportTableStarted(...)
    data class ImportChunkProcessed(...)
    data class ImportTableFinished(...)
}
```

Nicht Teil des MVP:

- `RunFinished`
- separate ETA-/Percentage-Events
- machine-readable event channels fuer JSON/YAML

### 6.2 Export-spezifische Event-Felder

Fuer Export reichen im MVP folgende Daten:

- `table`
- `tableOrdinal`
- `tableCount`
- `chunkIndex`
- `rowsInChunk`
- `rowsProcessed`
- `bytesWritten`
- bei Tabellenende:
  - `chunksProcessed`
  - `durationMs`
  - `status`

Wichtige Regeln:

- `rowsProcessed` ist kumulativ pro Tabelle
- `bytesWritten` ist kumulativ pro Tabelle
- fuer leere Tabellen ist ein Empty-Chunk laut bestehendem Vertrag erlaubt und
  fuehrt zu:
  - `TableStarted`
  - genau einem Chunk-Event mit `rowsInChunk = 0`
  - `TableFinished`

### 6.3 Import-spezifische Event-Felder

Fuer Import soll der MVP pro Chunk die bereits bekannten Resultat-Zaehler
sichtbar machen:

- `table`
- `tableOrdinal`
- `tableCount`
- `chunkIndex`
- `rowsInChunk`
- `rowsProcessed`
- `rowsInserted`
- `rowsUpdated`
- `rowsSkipped`
- `rowsUnknown`
- `rowsFailed`
- bei Tabellenende:
  - `durationMs`
  - `status`

Wichtige Regeln:

- alle Zaehler sind kumulativ pro Tabelle
- `rowsProcessed` beschreibt die aus der Quelle verarbeiteten Rows der Tabelle
- `rowsFailed` steigt nur, wenn ein fortgesetzter Tabellenpfad einen konkret
  verlorenen Chunk mit bekannter Row-Anzahl verarbeitet
- reine Reader-Failures koennen in den Fehlerpfad laufen, ohne `rowsFailed`
  zu erhoehen
- `rowsInserted`/`rowsUpdated`/`rowsSkipped`/`rowsUnknown` werden erst nach
  erfolgreichem Commit des Chunks erhoeht und emittiert
- `status = COMPLETED` gilt nur fuer Table-Summaries ohne `error` und ohne
  `failedFinish`
- `status = FAILED` gilt fuer alle Table-Summaries mit `error != null` oder
  `failedFinish != null`
- fuer harte Exceptions vor dem Summary-Aufbau wird kein
  `ImportTableFinished`-Event garantiert

### 6.4 Emissionspunkte Export

Empfohlener Ablauf in `StreamingExporter.export(...)`:

1. nach Ermittlung von `effectiveTables`: `RunStarted`
2. vor `exportSingleTable(...)`: `ExportTableStarted`
3. nach jedem erfolgreich geschriebenen Chunk: `ExportChunkProcessed`
4. nach Abschluss der Tabelle: `ExportTableFinished`

Bei Tabellenfehlern:

- `ExportTableFinished(status = FAILED)` wird trotzdem emittiert
- die finale `ExportResult`-/Runner-Auswertung bleibt unveraendert

### 6.5 Emissionspunkte Import

Empfohlener Ablauf in `StreamingImporter.import(...)`:

1. nach Ermittlung von `tableInputs`: `RunStarted`
2. nach `writer.openTable(...)` und nach `onTableOpened(...)`: `ImportTableStarted`
3. nach jedem finalisierten Chunk-Ergebnis:
   - Commit erfolgreich -> `ImportChunkProcessed`
   - fortgesetzter chunk-lokaler Failure mit bekannter verlorener Row-Anzahl ->
     `ImportChunkProcessed` mit erhoehtem `rowsFailed`
   - Reader-Failures und unrecoverte Write-/Commit-Failures muessen kein
     zusaetzliches `ImportChunkProcessed` erzeugen
4. nach Abschluss der Tabelle: `ImportTableFinished`

Bei Tabellenfehlern:

- wenn der Tabellenpfad mit `TableImportSummary(error != null)` oder
  `TableImportSummary(failedFinish != null)` endet, wird
  `ImportTableFinished(status = FAILED)` vor Rueckgabe der Summary emittiert
- fuer harte Exceptions vor dem Summary-Aufbau wird kein synthetisches
  `ImportTableFinished` versprochen
- echte Exceptions duerfen nicht durch Event-Emission verdeckt werden

### 6.6 Renderer-Vertrag

Der CLI-Renderer rendert Events als reine stderr-Textzeilen.

Verbindliche MVP-Regeln:

- keine ANSI-Cursorsteuerung
- keine interaktive Ueberschreibung alter Zeilen
- keine Farbabhaengigkeit
- `chunkIndex` wird fuer Menschen 1-basiert dargestellt
- bei Export kann `bytesWritten` in MB oder KB dargestellt werden
- bei Import stehen Row-Outcome-Zaehler im Vordergrund
- der neue Renderer formatiert Zahlen deterministisch und nicht ueber die
  JVM-Default-Locale
- empfohlen: explizite Formatter mit `Locale.US` oder aequivalent

Empfohlene Render-Richtung:

```text
Exporting 4 table(s)
Exporting table 'customers' (1/4)
Exporting table 'customers' | chunk 1 | 10,000 rows | 0.81 MB
Exported table 'customers' | 12,340 rows | 2 chunks | 0.99 MB
```

```text
Importing 2 table(s)
Importing table 'orders' (1/2)
Importing table 'orders' | chunk 1 | 10,000 rows processed | 9,980 inserted, 20 skipped
Imported table 'orders' | 12,000 inserted, 20 skipped
```

Nicht Teil des MVP:

- eine gleichzeitige Anzeige von `active` + `pending` + `completed`
- ein Fortschrittsboard ueber mehrere Zeilen mit nachtraeglicher Mutation

---

## 7. Implementierung

### 7.1 `ProgressEvent` und `ProgressReporter`

In `hexagon:ports` werden die neuen Vertragstypen eingefuehrt.

Zwingend noetig:

- `ProgressOperation`
- `TableProgressStatus`
- `ProgressEvent`
- `ProgressReporter`
- `NoOp`-Variante fuer Suppression

Empfehlung:

- `ProgressReporter` bekommt eine einfache No-Op-Implementierung, z. B.:

```kotlin
object NoOpProgressReporter : ProgressReporter {
    override fun report(event: ProgressEvent) = Unit
}
```

oder aequivalent ueber Companion/Factory.

### 7.2 `StreamingExporter`

Der Exporter bekommt einen optionalen Reporter-Parameter:

```kotlin
fun export(
    ...,
    progressReporter: ProgressReporter = NoOpProgressReporter,
): ExportResult
```

Pflichten:

- `RunStarted` nach `effectiveTables`
- `ExportTableStarted` vor der Tabellenverarbeitung
- `ExportChunkProcessed` nach jedem `writer.write(chunk)`
- `ExportTableFinished` fuer erfolgreiche und fehlgeschlagene Tabellen

Wichtig:

- bestehende Ergebniszaehler in `ExportResult` bleiben die Source of Truth
- Event-Zaehler muessen aus denselben Zahlen hergeleitet werden
- `writer.begin/end`-Vertraege aus den bestehenden Tests duerfen nicht
  regressieren

### 7.3 `StreamingImporter`

Der Importer bekommt ebenfalls einen Reporter-Parameter:

```kotlin
fun import(
    ...,
    progressReporter: ProgressReporter = NoOpProgressReporter,
): ImportResult
```

Pflichten:

- `RunStarted` nach `tableInputs`
- `ImportTableStarted` nach `openTable()` und `onTableOpened(...)`
- `ImportChunkProcessed` erst nach finalisiertem Chunk-Zustand
- `ImportTableFinished` fuer erfolgreiche Tabellen und fuer Rueckgabepfade mit
  `error` oder `failedFinish`

Wichtig:

- `onTableOpened(...)` bleibt erhalten
- Event-Emission darf keine Fehler verschlucken
- bei fortgesetzten chunk-lokalen Failures mit bekannter verlorener Row-Anzahl
  muss `rowsFailed` monoton wachsen
- Reader-Failures und unrecoverte Write-/Commit-Failures duerfen direkt in den
  Tabellenfehlerpfad laufen, ohne zusaetzliches Chunk-Event
- `failedFinish` wird im Progress-Event als `FAILED` behandelt
- fuer harte Throw-Pfade vor dem Summary-Aufbau ist kein synthetisches
  Table-Finish-Event gefordert

### 7.4 `ExportExecutor` und `ImportExecutor`

Beide application-seitigen Executor-Seams muessen den Reporter durchreichen.

Empfohlene Signaturanpassung:

```kotlin
fun interface ExportExecutor {
    fun execute(
        ...,
        progressReporter: ProgressReporter,
    ): ExportResult
}
```

```kotlin
fun interface ImportExecutor {
    fun execute(
        ...,
        onTableOpened: (table: String, targetColumns: List<TargetColumn>) -> Unit,
        progressReporter: ProgressReporter,
    ): ImportResult
}
```

Damit bleiben die Runner testbar:

- Fake-Executors koennen Events kuenstlich ausloesen
- Runner-Tests koennen Suppression ohne echte Streaming-Implementierung pruefen

### 7.5 Runner-Wiring

`DataExportRunner` und `DataImportRunner` bekommen einen injizierten Reporter:

- in Produktivverdrahtung: CLI-Renderer
- in Tests: Fake-/Recording-Reporter

Die Runner entscheiden auf Basis des Requests ueber den effektiven Reporter:

- `quiet == true` -> No-Op
- `noProgress == true` -> No-Op
- sonst -> injizierter Reporter

Wichtig:

- die bestehende finale Summary-Ausgabe bleibt in den Runnern an Ort und Stelle
- die Summary-Suppression bleibt wie heute:
  - `--quiet` -> keine Summary
  - `--no-progress` -> keine Summary
- Export-Warnings bleiben unter `--no-progress` sichtbar

### 7.6 CLI-Renderer

Im CLI-Modul entsteht ein Renderer, z. B.:

- `StderrProgressRenderer`
- oder `ProgressRenderer`

Pflichten:

- `ProgressReporter` implementieren
- Events deterministisch in Textzeilen ueberfuehren
- nur `stderr` schreiben
- keine stdout-Abhaengigkeiten

Die Commands `DataExportCommand` und `DataImportCommand` verdrahten:

- Runner
- Executor
- `ProgressRenderer`

### 7.7 Doku-Abgleich

`docs/cli-spec.md` Abschnitt 7 wird auf den tatsaechlichen MVP-Vertrag
umgestellt.

Zu korrigieren:

- keine `>2s`-Schwelle als Pflicht
- keine gleichzeitigen `2 active`-Tabellen
- klare sequenzielle Beispiele
- `--no-progress` fuer Export/Import suppressiert sowohl Zwischen-Events als
  auch die finale ProgressSummary
- `--quiet` bleibt "nur Fehler"
- das globale Root-Flag `--output-format` wird fuer Export/Import nur als
  Nicht-Interaktion dokumentiert: Progress bleibt plain-text auf `stderr`,
  ohne neues Runner-Wiring oder JSON-/YAML-Eventformat

---

## 8. Tests

### 8.1 Streaming-Tests Export

`StreamingExporterTest.kt` bekommt dedizierte Event-Sequenz-Tests.

Pflichtfaelle:

| # | Testname | Pruefung |
|---|---|---|
| 1 | `emits run started for effective tables` | `RunStarted(totalTables)` |
| 2 | `emits started chunk progress and finished for single table` | Standardpfad |
| 3 | `empty table still emits chunk progress with zero rows` | bestehender Empty-Chunk-Vertrag bleibt sichtbar |
| 4 | `multi table export emits deterministic ordinals` | 1/3, 2/3, 3/3 |
| 5 | `failed table emits finished event with failed status` | Fehlerpfad sichtbar |

### 8.2 Streaming-Tests Import

`StreamingImporterTest.kt` bekommt dedizierte Event-Sequenz-Tests.

Pflichtfaelle:

| # | Testname | Pruefung |
|---|---|---|
| 1 | `emits run started for resolved inputs` | `RunStarted(totalTables)` |
| 2 | `emits started chunk progress and finished for successful table` | Standardpfad |
| 3 | `emits chunk progress only after commit` | kein vorzeitiges Ueberzaehlen |
| 4 | `continued chunk local failure increments rowsFailed in progress events` | nur Failures mit bekannter verlorener Row-Anzahl erzeugen Chunk-Progress |
| 5 | `reader failure may end table without additional chunk progress event` | kein kuenstliches Chunk-Event fuer Read-Failure-Pfad |
| 6 | `returned table error emits finished event with failed status` | `error != null` bleibt als `FAILED` sichtbar |
| 7 | `failed finish emits finished event with failed status` | `failedFinish != null` bleibt als `FAILED` sichtbar |
| 8 | `fatal abort propagates without synthetic finished event requirement` | harter Throw-Pfad bleibt unverdeckt |

### 8.3 Runner-Tests

Die bestehenden Runner-Tests werden erweitert, nicht ersetzt.

`DataExportRunnerTest.kt`:

- default path nutzt Reporter und behaelt finale Summary
- `--quiet` suppressiert Events und Summary
- `--no-progress` suppressiert Events und Summary
- Warnings bleiben unter `--no-progress` sichtbar

`DataImportRunnerTest.kt`:

- default path nutzt Reporter und behaelt finale Summary
- `--quiet` suppressiert Events und Summary
- `--no-progress` suppressiert Events und Summary
- Fehler-/failedFinish-Pfade bleiben sichtbar

### 8.4 Renderer-Tests

`ProgressRendererTest.kt` prueft:

- Run-Start-Zeilen
- Table-Start-Zeilen
- Export-Chunk-Zeilen
- Import-Chunk-Zeilen
- Table-End-Zeilen fuer `COMPLETED`
- Table-End-Zeilen fuer `FAILED`
- exakte locale-neutrale Zahlenformatierung fuer neue Event-Zeilen
- stabile menschenlesbare Formatierung ohne ANSI-/Cursor-Abhaengigkeit

### 8.5 CLI-Tests

`CliDataExportTest.kt` und `CliDataImportTest.kt` pruefen echte stderr-Ausgabe.

Pflichtfaelle:

- Default: Zwischen-Events + finale Summary sichtbar
- `--no-progress`: keine Zwischen-Events, keine finale Summary
- `--quiet`: keine Zwischen-Events, keine Summary, keine Export-Warnings

Zusaetzlicher duenn gehaltener CLI-Wiring-Fall:

- gesetztes Root-Flag `--output-format json` oder `yaml` aendert den
  Progress-Pfad von Export/Import nicht; Events bleiben Text auf `stderr`

Wichtig:

- bestehende Assertions zur ProgressSummary bleiben erhalten
- neue Assertions kommen additiv dazu
- exakte String-Assertions gelten primaer fuer neue Event-Zeilen, nicht fuer
  die unveraendert bestehende finale legacy Summary

### 8.6 Nicht Ziel der Tests in Phase D

Nicht noetig fuer 0.5.0:

- Terminal-Width-Tests
- Cursor-Rewrite-Tests
- ETA-/Prozenttests
- Lasttests auf Millionen Event-Zeilen

---

## 9. Coverage-Ziel

Fuer Phase D gelten die bestehenden Modul-Gates weiter:

- `adapters:driven:streaming` mindestens auf bestehendem Niveau halten
- `hexagon:application` mindestens auf bestehendem Niveau halten
- `adapters:driving:cli` mindestens auf bestehendem Niveau halten

Pragmatische Zielsetzung:

- Streaming-Tests sichern die echten Event-Sequenzen
- Runner-Tests sichern die Flag-Semantik
- CLI-Tests sichern die reale stderr-Darstellung

Die Phase rechtfertigt keine Coverage-Ausnahme.

---

## 10. Build und Verifikation

Gezielter Testlauf fuer den Progress-Slice:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:streaming:test :hexagon:application:test :adapters:driving:cli:test --rerun-tasks" \
  -t d-migrate:progress-mvp .
```

Optionaler Fokuslauf lokal ohne den ganzen Baum:

```bash
./gradlew \
  :adapters:driven:streaming:test \
  :adapters:driving:cli:test \
  --rerun-tasks
```

Wichtig:

- fuer Phase D ist ein gezielter Testlauf ausreichend
- die echte Progress-Verifikation liegt in Streaming-/Runner-/CLI-Tests
- ein separater manueller Runtime-Smoke-Test ist fuer den MVP nicht zwingend,
  solange die stderr-Pfade in den CLI-Tests direkt geprueft werden

---

## 11. Abnahmekriterien

- `data export` emittiert waehrend des Laufs line-orientierte Progress-Events
  auf `stderr`, sofern weder `--quiet` noch `--no-progress` gesetzt ist.
- `data import` emittiert waehrend des Laufs line-orientierte Progress-Events
  auf `stderr`, sofern weder `--quiet` noch `--no-progress` gesetzt ist.
- Export und Import behalten ihre bestehende finale ProgressSummary im
  Default-Pfad.
- `--quiet` unterdrueckt sowohl Zwischen-Events als auch finale
  ProgressSummary.
- `--no-progress` unterdrueckt sowohl Zwischen-Events als auch finale
  ProgressSummary, behaelt aber nicht-progressbezogene stderr-Ausgaben
  unveraendert bei.
- Export-Warnings bleiben unter `--no-progress` sichtbar und werden unter
  `--quiet` unterdrueckt.
- stdout bleibt fuer Nutzdaten sauber; Progress geht ausschliesslich auf
  `stderr`.
- Fuer Import gilt: zurueckgegebene Table-Failures mit `error` oder
  `failedFinish` werden als `FAILED` signalisiert; fuer harte Exceptions vor
  dem Summary-Aufbau wird kein synthetisches Table-Finish-Event versprochen.
- Das globale Root-Flag `--output-format json` oder `yaml` aendert den
  Progress-Pfad fuer Export/Import nicht; es werden keine JSON-/YAML-Progress-
  Events eingefuehrt und kein neues Runner-Request-Feld noetig.
- `docs/cli-spec.md` Abschnitt 7 beschreibt danach den tatsaechlichen
  line-orientierten, sequenziellen MVP-Vertrag und verspricht weder
  Mehrfach-Active-Tabellen noch eine harte `>2s`-Schwelle.
- Der gezielte Testlauf aus Abschnitt 10 besteht ohne Regression bestehender
  Summary-/Flag-Tests.
