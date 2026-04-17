# Unterplan: Phase C.1 - Export-Resume (tabellengranular)

> **Milestone**: 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI
> **Phase**: C (Export-Checkpoint und Resume) — Unterphase C.1
> **Status**: Implemented (2026-04-16) — tabellengranulares Export-Resume
> umgesetzt: Fingerprint-Utility, Executor-Seam mit operationId +
> resuming, Manifest-Lifecycle im DataExportRunner (load/compat/save-
> per-table/complete), StreamingExporter skippt COMPLETED-Tabellen,
> ProgressRenderer zeigt Starting/Resuming-Label. Tests grün.
> Mid-Table-Resume in Phase C.2.
> **Überplan**: [`ImpPlan-0.9.0-C.md`](./ImpPlan-0.9.0-C.md)
> **Parallel-Unterplan**: [`ImpPlan-0.9.0-C2.md`](./ImpPlan-0.9.0-C2.md) —
> Mid-Table-Resume
> **Referenz**: `docs/ImpPlan-0.9.0-A.md`, `docs/ImpPlan-0.9.0-B.md`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`;
> `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/checkpoint/CheckpointManifest.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ExportResult.kt`

---

## 1. Ziel

C.1 macht Export-Resume auf **tabellengranularer** Ebene produktiv. Nach
Abschluss dieser Unterphase gilt:

- ein abgebrochener file-basierter Exportlauf kann mit `--resume` so
  fortgesetzt werden, dass bereits im Manifest als `COMPLETED` markierte
  Tabellen **nicht erneut** exportiert werden
- unvollstaendige Tabellen werden in C.1 nicht fortgesetzt, sondern von
  vorn exportiert (Mid-Table-Resume ist Teil von C.2)
- der Runner aktiviert die Resume-Runtime; der bisherige
  „accepted but ignored"-Warnpfad aus Phase A entfaellt
- die in Phase B offene `operationId`-Kante vom Runner durch den
  Executor-Seam bis in `ProgressEvent.RunStarted` ist geschlossen

C.1 ist bewusst die kleinere und risikoaermere Hälfte von Phase C. Sie
setzt den gesamten Manifest-Lifecycle und alle Kompatibilitaetspruefungen
um, verzichtet aber auf die Marker-basierte Fortsetzung innerhalb einer
Tabelle.

---

## 2. Ausgangslage

Stand nach Phase A und B:

- CLI-Oberflaeche fuer `--resume` und `--checkpoint-dir` ist in
  `DataExportCommand` bereits vorhanden; `DataExportRunner` lehnt
  stdout-Kombinationen mit Exit 2 ab und gibt fuer file-basiertes
  `--resume` aktuell nur eine sichtbare Warnung aus.
- `CheckpointStore`-Port, `CheckpointManifest` (versioniert),
  `FileCheckpointStore` und `CheckpointConfig.merge(...)` liegen in
  Phase B produktiv vor — sind aber im Runner noch nicht aufgerufen.
- `operationId` ist als optionales Feld in `ProgressEvent.RunStarted`,
  `ExportResult` und `ImportResult` verankert; `StreamingExporter.export`
  akzeptiert es; der Runner erzeugt eine UUID und zeigt sie in der
  stderr-Summary. **Offen**: Durchreichen durch den
  `ExportExecutor`-Seam, sodass die ID live im `RunStarted`-Event
  ankommt.

Konsequenz: C.1 muss den bereits vorbereiteten Phase-B-Unterbau an den
Exportpfad anschliessen und den Phase-A-Warnpfad durch echten
Runtime-/Fehlerpfad ersetzen.

---

## 3. Scope fuer C.1

### 3.1 In Scope

- Manifest-Lifecycle im Exportpfad:
  - neuer Lauf ohne `--resume` → Manifest mit
    `operationType = EXPORT`, `operationId` (UUID), `schemaVersion = 1`,
    `chunkSize`, `format`, `tableSlices = emptyList()` anlegen, sobald
    die effektive Tabellenliste steht
  - nach jeder fachlich abgeschlossenen Tabelle → Manifest fortschreiben
    (`tableSlices` aktualisiert, `updatedAt` gesetzt)
  - nach vollstaendigem Lauf → `CheckpointStore.complete(operationId)`
- Resume-Preflight im Runner:
  - Manifest laden (Exit 7 bei unlesbar / `UnsupportedCheckpointVersion`)
  - Kompatibilitaetspruefung gegen den aktuellen Request
    (Quell-Fingerprint, Tabellenliste, Output-Modus, Zielpfade, Format,
    Encoding, `--csv-*`-Optionen, `--filter`, `--since-column`,
    `--since`)
  - Mismatch → Exit 3 mit klarer Fehlermeldung
- `optionsFingerprint` im Manifest tragen:
  - deterministisch aus den oben genannten Optionen berechnet
  - Phase C.1 legt Hash-/Kodierungsvertrag fest und pruzte ihn im
    Preflight
- Wiederaufnahme:
  - Tabellen mit Status `COMPLETED` werden übersprungen
  - Tabellen mit Status `PENDING`/`IN_PROGRESS`/`FAILED` werden von vorn
    exportiert (File wird mit `TRUNCATE_EXISTING` neu geschrieben) und
    ihr Slice-Status wird anschliessend auf `COMPLETED` aktualisiert
- Output-Vertrag:
  - file-per-table: Zuordnung `table → <dir>/<table>.<format>` ist
    manifestgebunden stabil; Resume prueft, dass Zielverzeichnis und
    Dateinamen-Schema identisch sind
  - single-file mit genau einer Tabelle: Resume ist nur zulaessig, wenn
    die Tabelle im Manifest nicht `COMPLETED` ist — dann wird die Datei
    neu angelegt (TRUNCATE_EXISTING) und der Lauf als Fortsetzung mit
    demselben `operationId` geschrieben
- Executor-Seam fuer `operationId`:
  - `ExportExecutor.execute` erhaelt einen `operationId: String?`-
    Parameter
  - Runner reicht die UUID durch; `StreamingExporter.export` setzt sie
    in das `RunStarted`-Event
- `ProgressRenderer` zeigt den Resume-/Operation-Kontext sichtbar:
  - `RunStarted`-Render-Zeile trägt die `operationId` (wenn im Event
    vorhanden), damit Stderr-Progress-Output denselben Lauf wie
    Summary und Manifest referenziert
  - Resume-Lauf signalisiert am RunStart sichtbar, dass es sich um eine
    Wiederaufnahme handelt (z.B. „resuming run <id>"); neuer Lauf
    signalisiert „starting run <id>"
- Runner-Warning „resume runtime is not yet active in this build" wird
  entfernt

### 3.2 Bewusst nicht Teil von C.1

- Mid-Table-Resume innerhalb einer unvollstaendigen Tabelle (→ C.2)
- `DataReader`-Port-Erweiterung fuer Marker-Paging (→ C.2)
- kontrollierte Single-File-Fortsetzung mit mehreren Tabellen (→ C.2)
- importseitige Resume-Fortsetzung (→ Phase D)
- Signalbehandlung, Retry, Parallelisierung

---

## 4. Leitentscheidungen (C.1-spezifisch, ergaenzend zum Ueberplan §4)

### 4.1 C.1-Granularitaet ist „Tabelle komplett oder von vorn"

Der Resume-Status einer Tabelle in C.1 ist binaer aus der Sicht des
Exportpfads: wenn sie im Manifest `COMPLETED` ist, wird sie uebersprungen;
sonst wird sie neu exportiert.

Verbindliche Folge:

- `CheckpointTableSlice.rowsProcessed` und `chunksProcessed` werden von
  C.1 gefuellt, aber **nicht** fuer eine Fortsetzungsposition genutzt.
  Das Feld bleibt fuer C.2 als Vorbereitung stehen.
- `CheckpointTableSlice.status` ist in C.1 entweder `COMPLETED` (nach
  erfolgreichem Tabellenabschluss) oder fehlt bzw. ist `IN_PROGRESS`
  (waehrend des Laufs); `FAILED` folgt aus einem Tabellenfehler.

### 4.2 `optionsFingerprint` ist deterministisch und versioniert

C.1 fuehrt den Fingerprint produktiv ein:

- kanonisch serialisierte Form aus
  `format | encoding | csvDelimiter | csvBom | csvNoHeader | csvNullString | filter | sinceColumn | since | tables(in-Reihenfolge) | outputMode`
- Die Tabellenliste wird **in der Reihenfolge** aufgenommen, in der sie
  nach der Auto-Discovery bzw. `--tables`-Auflösung steht.
  Der Ueberplan §4.3 zaehlt „Tabellenmenge **und** Reihenfolge" explizit
  zu den Kompatibilitaetskriterien; ein Sortieren vor dem Hash wuerde
  diese Garantie stillschweigend aufweichen.
- SHA-256 ueber die kanonische Form → Hex-String (64 Zeichen)
- gespeichert in `CheckpointManifest.optionsFingerprint`
- gelesen im Preflight; Mismatch → Exit 3

Verbindliche Folge:

- Reihenfolge der **Optionen** im Hash-Input ist in C.1 eingefroren;
  eine spaetere Erweiterung um neue Optionen in 0.9.x-Folgereleases
  erfordert ein `schemaVersion`-Bump.
- Reihenfolge der **Tabellen** geht unsortiert in den Hash ein; ein
  Nutzer, der nur `--tables a,b` gegen `--tables b,a` austauscht,
  erhaelt einen anderen Fingerprint und damit einen Preflight-Mismatch.
  Das ist bewusst: bei Streaming-Export mit Tabellenreihenfolge
  koennen sich in Auto-Discovery nachtraeglich Unterschiede ergeben,
  die der Lauf stabil reflektieren soll.

### 4.3 Executor-Seam bleibt source-identisch

Der neue `operationId`-Parameter an `ExportExecutor.execute` wird als
**optional** (default `null`) eingefuehrt. Das haelt den Test-Churn auf
Bestandstests minimal — Bestandstests passen die Signatur ohne
Parameterbelegung an.

Begruendung:

- fast alle Bestandstests erzeugen `ExportExecutor` ueber einen Lambda
  mit mehreren underscore-Parametern; ein neuer Parameter erzwingt ein
  Kotlin-Compile-Fix, kein Test-Logik-Umbau
- fuer Tests, die die `operationId` beobachten wollen, reicht ein
  zusaetzlicher benannter Parameter

---

## 5. Arbeitspakete

### 5.1 C1.1 — Kompatibilitaets-/Options-Fingerprint-Utility

- neuer Hexagon-Helfer im `application`- oder `core`-Modul, der aus einem
  `DataExportRequest` (oder einem daraus abgeleiteten
  „ExportRunFingerprint"-Typ) einen stabilen SHA-256-Hex-String
  berechnet
- Unit-Tests: gleiche Inputs → gleiche Hashes; geaenderte Felder →
  andere Hashes; **geaenderte Tabellenreihenfolge** erzeugt
  absichtlich einen anderen Hash (siehe §4.2)
- Integration in `CheckpointManifest.optionsFingerprint`

### 5.2 C1.2 — Manifest-Lifecycle im Runner

- `DataExportRunner` haengt an:
  - `FileCheckpointStore(directory)`, konstruiert aus
    `CheckpointConfig.merge(cliDirectory = request.checkpointDir,
    config = …)`
  - **Resume-Referenz aufloesen** (CLI-Vertrag
    `--resume <checkpoint-id|path>`, siehe `docs/cli-spec.md` und
    `docs/implementation-plan-0.9.0.md` §4.3):
    - ist `request.resume` ein absoluter oder relativer Pfad (enthaelt
      `/` oder endet auf `.checkpoint.yaml`) und zeigt auf eine
      existierende Datei → Dateiname zu `operationId` ableiten
      (Dateiname ohne Suffix) **und** validieren, dass die Datei
      innerhalb des konfigurierten Checkpoint-Verzeichnisses liegt;
      Mismatch → Exit 7
    - sonst wird der Wert direkt als `operationId` interpretiert
    - die so aufgeloeste `operationId` geht an `store.load(...)`
  - Laden: `store.load(resolvedOperationId)`; fehlendes Manifest → Exit 7
    (`"checkpoint not found"`); inkompatible `schemaVersion` → Exit 7
    (aus `UnsupportedCheckpointVersionException`)
  - Preflight: Kompatibilitaetscheck gegen aktuelle Request-Werte
    (Fingerprint, Tabellenliste, Output-Modus, Zielpfade,
    Source-URL-Fingerprint) — Mismatch → Exit 3
  - Initialisieren: neuer Lauf → Manifest mit frischer
    `operationId` (UUID), `operationType = EXPORT`, `optionsFingerprint`,
    `tableSlices` gemaess aufgeloester Tabellenliste schreiben
  - Fortschreiben: nach jeder abgeschlossenen Tabelle → Slice-Update
  - Abschluss: nach erfolgreichem Lauf → `store.complete(operationId)`
- Runner-Warning entfernen; stattdessen bei `resume != null` und
  fehlendem `--output` bleibt Exit 2 (Phase A); andere Fehlerpfade → Exit 3
  (Mismatch) oder Exit 7 (unlesbar / nicht gefunden / Version)

### 5.3 C1.3 — `ExportExecutor`-Seam und Progress-Anzeige fuer `operationId`

- `ExportExecutor.execute` bekommt einen zusaetzlichen Parameter
  `operationId: String? = null`
- Runner gibt die UUID weiter
- `StreamingExporter` setzt sie in `ProgressEvent.RunStarted`
- `ProgressRenderer` zeigt die `operationId` in der RunStarted-Zeile an
  und differenziert zwischen neuem Lauf und Wiederaufnahme. Sichtbares
  Label-Schema (Phase C.1 fixiert):
  - neuer Lauf: `Starting run <id>: exporting <n> table(s)`
  - Resume: `Resuming run <id>: exporting <n> table(s)`
- Bestand: Test-Lambdas bekommen einen zusaetzlichen underscore-Parameter;
  bestehende `ProgressRendererTest`-Faelle werden um den Resume-Kontext
  ergaenzt
- optional: eine `onTableCompleted(table, slice)`-Callback-Schnittstelle,
  damit der Runner das Manifest nach jeder Tabelle fortschreiben kann
  ohne zwischen Executor und Runner ein zusaetzliches
  Return-Typ-Feld einfuehren zu muessen

### 5.4 C1.4 — `StreamingExporter` fuer tabellengranulares Resume

- bei vorhandenem Resume-Manifest: Tabellen mit `COMPLETED`-Status aus
  der Iteration entfernen, bevor sie exportiert werden
- pro Tabellen-Ende: `onTableCompleted`-Callback aufrufen (siehe 5.3)
- leere Tabellen werden explizit als `COMPLETED` mit `rowsProcessed = 0`
  markiert, damit sie bei Wiederaufnahme nicht erneut geoeffnet werden

### 5.5 C1.5 — Output-Vertrag file-per-table und single-file

- file-per-table: Runner validiert, dass `--output` ein Verzeichnis
  ist und dass Dateinamen-Schema dem im Manifest gespeicherten Layout
  entspricht; Mismatch → Exit 3
- single-file: Runner pruzte, dass genau **eine** Tabelle im Manifest
  noch nicht `COMPLETED` ist; andernfalls → Exit 3 („run already
  completed" oder „single-file resume requires exactly one pending
  table"); der Exporter schreibt dann in die Zieldatei mit
  TRUNCATE_EXISTING (Datei war vorher nicht valide)

### 5.6 C1.6 — Tests fuer C.1

- Runner-Preflight-Tests:
  - unlesbares Manifest → Exit 7
  - inkompatible `schemaVersion` → Exit 7
  - `optionsFingerprint`-Mismatch → Exit 3
  - Tabellenmengen-Mismatch → Exit 3
  - Output-Modus-Mismatch (single-file vs file-per-table) → Exit 3
- Runner-Manifest-Lifecycle-Tests:
  - neuer Lauf → Manifest wird angelegt
  - Lauf erfolgreich → `complete()` entfernt das Manifest
- Streaming-Resume-Tests:
  - file-per-table mit 3 Tabellen, 2 bereits `COMPLETED` → nur die
    dritte wird exportiert
  - leere Tabelle wird als `COMPLETED` markiert, ohne Rows zu schreiben
  - Tabellenfehler → `FAILED`-Status, andere Tabellen unangetastet
- Executor-Seam-Tests:
  - `operationId` kommt in `RunStarted` an
  - Bestandstests kompilieren mit dem zusaetzlichen Parameter weiter

---

## 6. Teststrategie fuer C.1

- Vertrag: Fingerprint-Utility hat deterministische Unit-Tests
- Preflight: volle Mismatch-Matrix an `DataExportRunner`-Tests
- Lifecycle: Runner schreibt Manifest an den erwarteten Stellen
- Streaming: `StreamingExporter`-Tests beweisen, dass `COMPLETED`-
  Tabellen nicht erneut geoeffnet werden
- Integration: keine neuen E2E-Testsuiten in C.1 — die bestehenden
  Fixture-Tests reichen, weil Resume hier tabellengranular und
  vorhersagbar ist

---

## 7. Datei- und Codebasis-Betroffenheit

Sicher betroffen:

- `docs/ImpPlan-0.9.0-C.md` (Ueberplan — nur Statusnotiz, sobald C.1 in
  Review/Implemented ist)
- `docs/ImpPlan-0.9.0-C1.md`
- `docs/cli-spec.md` (Runner-Warning-Entfernung im Verhaltens-Abschnitt)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt` (ggf.
  Helper fuer Fingerprint)
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/checkpoint/CheckpointManifest.kt`
  (optional: Helper zum Slice-Upsert)

Mit hoher Wahrscheinlichkeit betroffen:

- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingExporterTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`

Nicht Ziel von C.1:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/data/DataReader.kt` (bleibt unveraendert)
- `adapters/driven/driver-*` (bleiben unveraendert)

---

## 8. Risiken

### 8.1 Executor-Signatur-Aenderung bricht Bestandstests

Das Hinzufuegen des `operationId: String? = null`-Parameters zu
`ExportExecutor.execute` erfordert in allen SAM-Lambdas einen
zusaetzlichen underscore-Parameter.

Mitigation:

- Parameter ans **Ende** der Signatur mit Default stellen
- Kotlin-Compile-Fix bei Bestandstests ist rein syntaktisch

### 8.2 Output-Validation kann bei Resume frustrierend streng wirken

Wenn sich das Output-Layout (z.B. Dateinamen-Schema) marginal aendert,
lehnt C.1 Resume ab.

Mitigation:

- Fehlermeldungen nennen genau die divergierenden Felder
- Nutzer koennen entweder die Option anpassen oder einen neuen Lauf
  starten (und das alte Manifest loeschen)

### 8.3 C.1 entfernt die Warnung aus Phase A

Nach C.1 darf `--resume` nicht mehr im Silent-no-op-Modus laufen.

Mitigation: Test, der explizit verifiziert, dass bei `--resume` kein
„run from scratch"-Warning mehr ausgegeben wird (und stattdessen
entweder Resume oder Exit 3/7 erfolgt).

---

## 9. Entscheidungsempfehlung

C.1 ist der erste echte Resume-Datenpfad im Milestone und deckt die
haeufigsten Praxisfaelle ab (abgebrochener Export → fertige Tabellen
nicht neu laden). Es traegt die volle Preflight- und Manifest-
Lifecycle-Arbeit, ohne den `DataReader`-Port anzufassen. Damit ist das
Risikoprofil bewusst klein, der Nutzwert aber gross.

Nach C.1 ist `--resume` fuer den Exportpfad produktiv; die feinere
Granularitaet (Mid-Table-Resume) folgt separat in C.2.
