# Implementierungsplan: 0.9.2 - Arbeitspaket 6.6 Runner-Zerlegung und Executor-Kontextschnitt nachziehen

> **Milestone**: 0.9.2 - Beta: DDL-Phasen und importfreundliche
> Schema-Artefakte
> **Arbeitspaket**: 6.6 (Runner-Zerlegung und Executor-Kontextschnitt
> nachziehen)
> **Status**: Done (2026-04-19)
> **Referenz**: `docs/implementation-plan-0.9.2.md` Abschnitt 3.2,
> Abschnitt 5.4, Abschnitt 6.6, Abschnitt 7, Abschnitt 8, Abschnitt
> 9.4;
> `docs/quality.md`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ExportResumeCoordinator.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ImportResumeCoordinator.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt`.

---

## 1. Ziel

Arbeitspaket 6.6 entschlackt die beiden verbleibenden Runner-Hotspots
aus `docs/quality.md`:

- `DataExportRunner.executeWithPool()` wird in kleine,
  fachlich erkennbare Schrittfunktionen zerlegt
- `DataImportRunner.executeWithPool()` wird analog geschnitten
- `ExportExecutor.execute(...)` und `ImportExecutor.execute(...)`
  verlieren ihre breiten Parameterlisten zugunsten kontextgruppierter
  DTOs und Callback-Gruppen
- die bestehende Exit-Code-, Resume- und Progress-Semantik bleibt
  unveraendert

6.6 ist ein internes Qualitaets- und Wartbarkeitsarbeitspaket. Es
liefert keinen neuen CLI-Vertrag und keine neue Streaming-Funktionalitaet.

Nach 6.6 soll klar gelten:

- Runner-Code ist entlang der fachlichen Phasen lesbar
- `ExportExecutor` und `ImportExecutor` transportieren Laufzeitdaten
  nicht mehr als 14-17 Einzelparameter
- bestehende Tests koennen Schrittfunktionen und Kontextobjekte gezielt
  absichern, statt jede Verzweigung nur ueber einen grossen Runner-
  Einstieg zu pruefen

Abhaengigkeit zu 6.1 bis 6.5:

- 6.6 ist vom DDL-Split fachlich weitgehend unabhaengig
- das Paket kann parallel zu 6.4/6.5 laufen
- wichtig ist nur, dass der Runner-Umbau keine Exit-Code- oder
  Output-Regression in den bereits definierten Vertraegen erzeugt

---

## 2. Ausgangslage

`docs/quality.md` nennt fuer 0.9.2 zwei konkrete Hotspots:

- `DataExportRunner.executeWithPool()` mit rund 16 distinkten Phasen
- `DataImportRunner.executeWithPool()` mit rund 15 distinkten Phasen

Zusammen mit den Executor-Seams ergibt sich heute ein schwer
scannbarer Kontrollfluss:

- Runner mischen Preflight, Kontextaufbau, Manifest-Lifecycle,
  Streaming-Aufruf und Finalisierung in grossen Methoden
- `ExportExecutor.execute(...)` traegt 17 Parameter
- `ImportExecutor.execute(...)` traegt 14 Parameter
- CLI-Wiring und Tests muessen diese breiten SAM-Signaturen an vielen
  Stellen mitschleppen

Gleichzeitig ist die Codebasis nicht mehr komplett unstrukturiert:

- `ExportResumeCoordinator` und `ImportResumeCoordinator` existieren
  bereits
- beide Runner haben bereits lokale `ResumeContext`-Objekte
- die Streaming-Ausfuehrung ist hinter `ExportExecutor` /
  `ImportExecutor` testbar entkoppelt

6.6 baut also nicht bei null an. Das Paket soll die vorhandenen
Teilschnitte konsolidieren und den verbleibenden "grossen Methodenblock"
sauber in benannte Schritte zerlegen.

Die kritische Randbedingung ist dabei:

- die Runner transportieren viel Semantik in kleinen Details
- insbesondere Resume, Checkpoint, Finalisierung und Exit-Code-Mapping
  duerfen beim Umbau nicht versehentlich kippen

---

## 3. Scope fuer 6.6

### 3.1 In Scope

- Zerlegung von `DataExportRunner.executeWithPool()` in kleine,
  benannte Schrittfunktionen
- Zerlegung von `DataImportRunner.executeWithPool()` analog
- Einfuehrung gruppierter Executor-Kontexte fuer Export und Import
- Zusammenfassen der Callback-Parameter in explizite Callback-DTOs
  oder aequivalente Gruppenobjekte
- Nachziehen des CLI-Wirings in `DataExportCommand` und
  `DataImportCommand`
- Nachziehen der Runner-Tests auf die neue Executor-Signatur und die
  neuen Schnitte

### 3.2 Bewusst nicht Teil von 6.6

- fachliche Aenderungen am Export-/Importvertrag
- neue Exit-Codes oder neue Resume-Regeln
- Umbau von `StreamingExporter` oder `StreamingImporter`
- neue Module oder grossflaechige Service-Extraktionen ueber den
  lokalen Runner-/Executor-Zuschnitt hinaus
- Refactor anderer Runner ausser `DataExportRunner` und
  `DataImportRunner`

Praezisierung:

6.6 loest "wie wird bestehende Runner-Orchestrierung kleiner und klarer
geschnitten?", nicht "wie bauen wir die Streaming-Schicht neu?".

---

## 4. Leitentscheidungen

### 4.1 Die Runner werden entlang fachlicher Phasen geschnitten

Verbindliche Folge fuer `DataExportRunner`:

- die Methode wird in klar benannte Phasen zerlegt:
  - Registry-/Reader-/Lister-Aufloesung
  - Tabellen- und Output-Aufloesung
  - Filter-/Fingerprint-Build
  - Resume-/Checkpoint-Kontext
  - Streaming-Ausfuehrung
  - Finalisierung/Reporting

Verbindliche Folge fuer `DataImportRunner`:

- analoger Zuschnitt in:
  - Writer-/Format-Aufloesung
  - Schema-Preflight und Input-Aufbereitung
  - Optionen-/ReadOptions-/Pipeline-Build
  - Resume-/Manifest-Kontext
  - Streaming-Ausfuehrung
  - Finalisierung/Reporting

Wichtig:

- die Schrittfunktionen schneiden entlang vorhandener Semantik
- kein "Helper-Split" nur nach Syntax oder willkuerlichen LOC-Bloecken

### 4.2 Exit-Code- und Resume-Semantik bleiben byte- bzw. verhaltensstabil

Verbindliche Folge:

- 6.6 ist kein fachlicher Umbau
- dieselben Preflight- und Fehlerfaelle muessen weiterhin dieselben
  Exit-Codes liefern
- Manifest-Update, Resume-Validierung und Progress-Labels bleiben
  funktional unveraendert
- Callback-Semantik bleibt ebenfalls stabil:
  - Aufrufreihenfolge
  - Aufrufanzahl
  - Summary-Texte
  - Resume-bezogene Labels und Marker

Praezisierung:

- ein neuer Funktionsschnitt darf keine Fehler "schlucken" oder anders
  mappen
- Rueckgabewerte bleiben weiterhin im Runner koordiniert; die
  Schrittfunktionen duerfen die Exit-Matrix nicht implizit verteilen
- der sichtbare Contract fuer Progress-/Summary-Verhalten bleibt:
  - `onTable...`-, Chunk- und Progress-Callbacks werden in derselben
    fachlichen Reihenfolge ausgeliefert
  - Summary-Texte bleiben in Wortlaut und Triggerzeitpunkt stabil
  - Resume-Laufe behalten dieselbe "resuming vs starting"-Semantik

### 4.3 Executor-Parameter werden in Kontexte und Callback-Gruppen zerlegt

Verbindliche Folge:

- `ExportExecutor.execute(...)` und `ImportExecutor.execute(...)`
  arbeiten nach 6.6 nicht mehr mit langen Einzelparameterlisten
- stattdessen werden Parameter fachlich gruppiert, mindestens in:
  - Laufzeitkontext
  - Optionen / I/O-Kontext
  - Resume-Kontext
  - Callbacks

Praezisierung fuer Export:

- ein zulaessiger Schnitt ist z. B.:
  - `ExportExecutionContext`
  - `ExportExecutionOptions`
  - `ExportResumeContext`
  - `ExportCallbacks`

Praezisierung fuer Import:

- analog z. B.:
  - `ImportExecutionContext`
  - `ImportExecutionOptions`
  - `ImportResumeContext`
  - `ImportCallbacks`

Die exakten Namen koennen bei der Implementierung abweichen. Hart ist
die Gruppierungsregel, nicht der Klassenname.

Kompatibilitaetsanker fuer 6.6:

- der Umbau erfolgt ueber einen expliziten Mapping-Pfad von alter
  Einzelparameter-Semantik zu neuen Kontextobjekten
- CLI-Wiring und Tests duerfen waehrend des Refactors ueber kleine
  Builder oder Adapter-Helfer auf die neue Struktur umgestellt werden,
  statt jede Call-Site ad hoc neu zusammenzusetzen
- fuer `StreamingExporter` und `StreamingImporter` gilt ein
  1:1-Mapping-Vertrag:
  - dieselben fachlichen Werte
  - dieselben Callback-Instanzen
  - keine implizite Neuinterpretation in den DTOs

### 4.4 Bestehende lokale Kontextobjekte werden weitergenutzt, nicht neu ueberschrieben

Verbindliche Folge:

- vorhandene `ResumeContext`-Schnitte in den Runnern bleiben der
  Ausgangspunkt
- `ExportResumeCoordinator` und `ImportResumeCoordinator` bleiben die
  fachlichen Inhaber fuer Resume-spezifische Hilfslogik
- 6.6 fuehrt keine konkurrierende zweite Resume-Abstraktion ein

Harte Akzeptanzkante:

- jeder neue Schritt mit Resume-Bezug muss entweder:
  - direkt den bestehenden `resumeCoordinator` aufrufen
  - oder explizit dokumentieren, welche rein lokale
    Datenweitergabe er uebernimmt und warum dort keine neue
    Resume-Entscheidung entsteht
- neue DTOs duerfen Resume-Daten transportieren, aber keine zweite
  fachliche Resume-Regel definieren

### 4.5 CLI-Wiring und Tests muessen den neuen Schnitt direkt spiegeln

Verbindliche Folge:

- `DataExportCommand` und `DataImportCommand` bauen die neuen
  Executor-Kontexte explizit
- die Runner-Tests werden nicht nur "irgendwie repariert", sondern
  nutzen die neue Signatur bewusst
- der Umbau muss die heute zahlreichen SAM-Lambdas in den Tests
  deutlich lesbarer machen

Praezisierung zum Umfang:

- die Testmigration ist ein eigener Arbeitsposten, nicht nur
  Nacharbeiten am Rand
- betroffen sind grob 130 Runner-Testfaelle in zwei grossen Dateien
  (`DataExportRunnerTest.kt` mit rund 1.8k LOC,
  `DataImportRunnerTest.kt` mit rund 1.7k LOC)
- vor der Einzelmigration soll je Executor ein gemeinsamer Test-Builder
  oder Capture-Helper eingefuehrt werden, z. B.:
  - `testExportExecutor { ... }`
  - `testImportExecutor { ... }`
- Ziel ist eine systematische und reviewbare Migration statt vieler
  lokaler Ad-hoc-Umbauten

---

## 5. Konkrete Arbeitsschritte

### 5.1 `DataExportRunner.executeWithPool()` in benannte Schritte zerlegen

- die bestehende Export-Methode in kleine private Funktionen schneiden,
  z. B. fuer:
  - Reader-/Lister-Lookup
  - Tabellenaufloesung
  - Output-/Factory-Aufbau
  - Filter-/Fingerprint-Erzeugung
  - Resume-/Checkpoint-Aufbau
  - Executor-Aufruf
  - Abschluss / Summary / Fehlerbehandlung
- jede Funktion soll einen fachlich lesbaren Ein- und Ausgang haben
- gemeinsam genutzte Zwischenwerte werden ueber kleine DTOs oder
  benannte Rueckgabeobjekte getragen, nicht ueber lose lokale Variablen

### 5.2 `DataImportRunner.executeWithPool()` analog schneiden

- dieselbe Strategie fuer:
  - Writer-Lookup
  - Optionen- und ReadOptions-Build
  - Directory-Scan / ImportInput-Kontext
  - Resume-/Manifest-Lifecycle
  - Executor-Aufruf
  - Finalisierung / Summary / Fehlerpfad
- lokale Sonderfaelle wie Schema-Preflight und Trigger-/FK-Checks
  bleiben inhaltlich unveraendert, werden aber in eigene Schritte
  ueberfuehrt

### 5.3 Export-Executor auf Kontextobjekte umstellen

- das heutige `ExportExecutor`-Interface umstellen:
  - keine breite Liste aus Pool, Reader, Lister, Factory, Tabellen,
    Output, Format, Optionen, Config, Filter, Reporter, Resume-Flags,
    Callbacks und Markern mehr
- stattdessen kontextgruppierte Eingaben einfuehren
- dabei die heutige fachliche Trennung explizit abbilden:
  - Infrastruktur / Laufzeit
  - fachliche Exportoptionen
  - Resume-Zustand
  - Callbacks fuer Tabellen- und Chunk-Fortschritt
- fuer die Umstellung einen expliziten Adapter- oder Builder-Pfad
  vorsehen, damit CLI-Wiring und Tests die neuen Kontexte deterministisch
  befuellen koennen
- der Mapping-Pfad muss nachweisbar 1:1 sein:
  - jeder alte Parameter hat genau eine neue Heimat
  - kein Wert wird still verworfen oder neu interpretiert

Wichtig:

- der Runner bleibt Orchestrator
- der Executor bekommt keinen versteckten Zugriff auf Runner-Interna
- nur die Parameterform aendert sich, nicht die Verantwortungsgrenze
- mindestens ein Test muss den produktiven Aufrufpfad
  `DataExportCommand` -> `ExportExecutor` -> `StreamingExporter`
  nach dem Umbau explizit absichern

### 5.4 Import-Executor analog auf Kontextobjekte umstellen

- `ImportExecutor` mit derselben Logik von 14 Einzelparametern auf
  Kontexte und Callback-Gruppen umstellen
- `onTableOpened`, `onChunkCommitted` und `onTableCompleted` werden als
  explizite Callback-Gruppe behandelt
- Import-spezifische Resume-Daten bleiben in einem eigenen
  Resume-Kontext statt lose zwischen weiteren Parametern
- auch hier einen expliziten Builder- oder Adapter-Pfad fuer CLI-Wiring
  und Tests vorsehen
- mindestens ein Test muss den produktiven Aufrufpfad
  `DataImportCommand` -> `ImportExecutor` -> `StreamingImporter`
  nach dem Umbau explizit absichern

### 5.5 CLI-Wiring in `DataExportCommand` und `DataImportCommand` nachziehen

- die produktiven `ExportExecutor`-/`ImportExecutor`-Lambdas in der CLI
  auf die neuen DTOs umstellen
- `StreamingExporter` und `StreamingImporter` bleiben fachlich
  unveraendert; die CLI mappt nur die neuen Kontextobjekte auf die
  bestehenden Streaming-Aufrufe
- die CLI-Wiring-Stellen sind die normative 1:1-Mapping-Referenz fuer
  den Refactor; dort muss sichtbar bleiben, wie alte Semantik auf die
  neuen Kontexte abgebildet wird
- die Wiring-Stelle soll nach 6.6 kuerzer und lesbarer sein als heute,
  nicht nur anders verschachtelt

### 5.6 Testzuschnitt nachziehen

- `DataExportRunnerTest` und `DataImportRunnerTest` auf die neuen
  Executor-Signaturen umstellen
- die Migration betrifft grob 130 Testfaelle; sie wird nicht Fall fuer
  Fall freihaendig umgeschrieben, sondern ueber gemeinsame
  Test-Builder/Capture-Helfer vorbereitet
- SAM-Lambdas mit vielen Platzhaltern `_` duerfen nach 6.6 nicht in
  gleicher Breite weiterleben
- neue oder explizit nachgezogene Tests sollen die Schrittfunktionen
  indirekt absichern, z. B.:
  - Preflight / Exit-Matrix
  - Resume-Fingerprint
  - Manifest-Lifecycle
  - Progress-/Summary-Verhalten
- Contract-Tests ergaenzen fuer:
  - Aufrufreihenfolge und Aufrufanzahl von `onTable...`- und
    Chunk-Callbacks
  - Progress-Labels fuer Start- vs Resume-Lauf
  - Summary-Text und Triggerzeitpunkt
  - Resume-Marker-Weitergabe vor und nach dem Executor-Aufruf
- wenn fuer zentrale Schrittfunktionen kleine eigene Tests sinnvoll
  sind, ist das zulaessig; der Bestandspfad bleibt aber ueber die
  Runner-Tests abgesichert

Einfacher Qualitaetsanker fuer den Schnitt:

- `executeWithPool()` in beiden Runnern soll nach 6.6 nur noch die
  Phasenfolge koordiniert und selbst keine neue tiefe
  Kontrollstruktur ueber mehrere fachliche Abschnitte mehr tragen
- Zielkorridor:
  - `executeWithPool()` liegt nach 6.6 unter 80 LOC
  - die Methode ist als lineare Sequenz benannter Schrittaufrufe lesbar
  - mehrstufige Kontrollstruktur lebt in den Schrittfunktionen, nicht
    mehr im Top-Level-Ablauf
- jede neue Schrittfunktion hat genau eine Hauptverantwortung
- pro Hauptphase ist mindestens ein Testfall direkt oder indirekt
  nachvollziehbar zuordenbar

### 5.7 Dokumentations- und Quality-Spuren aktualisieren

- `docs/quality.md` darf die Hotspots nach Umsetzung nicht mehr als
  offene Runner-/Executor-Findings fuehren
- falls interne Architekturdoku oder Changelog den Zuschnitt explizit
  nennt, muss der neue Kontextschnitt dort konsistent gespiegelt werden

---

## 6. Verifikation

### 6.1 Pflichtfaelle fuer Export-Runner-Stabilitaet

- bestehende `DataExportRunnerTest`-Exit-Pfade bleiben gruen:
  - 0
  - 2
  - 3
  - 4
  - 5
  - 7
- Resume, Checkpoint und Summary verhalten sich unveraendert
- Pool-Close und Fehlerpfade bleiben unveraendert abgesichert
- Progress-/Callback-Contract bleibt stabil:
  - gleiche Aufrufreihenfolge
  - gleiche Aufrufanzahl
  - gleiche Summary-Ausgabe
  - gleiche Resume-Labels

### 6.2 Pflichtfaelle fuer Import-Runner-Stabilitaet

- bestehende `DataImportRunnerTest`-Exit-Pfade bleiben gruen:
  - 0
  - 1
  - 2
  - 3
  - 4
  - 5
  - 7
- Directory-Scan, Schema-Preflight, Resume und Summary bleiben
  funktional unveraendert
- Progress-/Callback-Contract bleibt auch hier stabil:
  - gleiche Aufrufreihenfolge
  - gleiche Aufrufanzahl
  - gleiche Summary-Ausgabe
  - gleiche Resume-bezogene Marker und Labels

### 6.3 Pflichtfaelle fuer den Executor-Kontextschnitt

- `ExportExecutor` hat nach 6.6 keine breite 17-Parameter-Signatur mehr
- `ImportExecutor` hat nach 6.6 keine breite 14-Parameter-Signatur mehr
- CLI-Wiring und Runner-Tests kompilieren mit den neuen
  Kontextobjekten
- ein expliziter Mapping-/Adapterpfad von alter Semantik zu neuen
  Kontexten ist im Code sichtbar und testseitig abgesichert
- produktive Aufrufpfade zu `StreamingExporter` und `StreamingImporter`
  bleiben mit 1:1-Semantik verifiziert
- die neuen DTOs bilden die heutige Semantik vollstaendig ab:
  - keine impliziten globalen Abhaengigkeiten
  - keine "misc"/"rest"-Container ohne fachliche Bedeutung

### 6.4 Pflichtfaelle fuer Lesbarkeit und lokalen Zuschnitt

- `executeWithPool()` in beiden Runnern liegt nach 6.6 unter 80 LOC und
  ist als lineare Phasenfolge lesbar
- Resume-spezifische Logik bleibt bei den vorhandenen Coordinators oder
  den bestehenden Resume-Kontexten verankert
- es entstehen keine zweiten konkurrierenden Helper-Ketten fuer dieselbe
  Phase
- jeder neue Schritt mit Resume-Bezug verweist entweder auf den
  bestehenden Coordinator oder bleibt explizit als reine
  Datenweitergabe ausgewiesen
- pro Hauptphase ist mindestens ein Testfall zuordenbar; der Refactor
  ist nicht nur optisch, sondern verifikationsseitig nachvollziehbar

Erwuenschte Zusatzfaelle:

- kleine Komponententests fuer neue Kontextobjekte oder Schrittfunktionen
- gezielte Review-Checks auf Reduktion der Platzhalter-Lambdas in den
  Runner-Tests

---

## 7. Betroffene Codebasis

Direkt betroffen:

- `docs/quality.md`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ExportResumeCoordinator.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ImportResumeCoordinator.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt`

Wahrscheinlich mit betroffen:

- weitere CLI-/Smoke-Tests fuer Export und Import
- interne Doku oder Changelog-Eintraege, die den Runner-/Executor-
  Zuschnitt beschreiben

Praezisierung:

- `StreamingExporter` und `StreamingImporter` sind in 6.6 nicht primaer
  Ziel des Umbaus
- falls dort doch Codeaenderungen noetig werden, muessen sie strikt auf
  den neuen Kontextschnitt begrenzt bleiben

---

## 8. Risiken und Abgrenzung

### 8.1 Ein Refactor kann Resume- und Exit-Semantik unbemerkt brechen

Die Runner enthalten viele kleine Regeln, die heute nur implizit in der
grossen Methode nebeneinander stehen.

Mitigation:

- Exit-Code-Tests vor und nach dem Umbau gruen halten
- Resume- und Manifest-Pfade explizit in der Verifikation auffuehren
- keine fachliche Aenderung ohne neuen Test

### 8.2 Kontext-DTOs koennen inhaltsleere Sammelbehaelter werden

Wenn die neuen DTOs nur "alle bisherigen Parameter in vier Tueten"
sind, sinkt die Komplexitaet nur optisch.

Mitigation:

- DTOs fachlich schneiden
- keine `MiscContext`-/`OtherOptions`-Container
- Callback-Gruppen nur dort bilden, wo die Klammer fachlich echt ist

### 8.3 Testmigration kann den Refactor teurer machen als den Produktionscode

Die Runner-Tests enthalten heute viele breite SAM-Lambdas.

Mitigation:

- Test-Helfer frueh auf die neuen Kontexte ausrichten
- gemeinsame Builder/Capture-Helfer nutzen
- Lesbarkeit der Tests als eigenes Erfolgskriterium behandeln

### 8.4 6.6 ist kein Vorwand fuer einen grossen Architekturumbau

Das Arbeitspaket soll die Hotspots verkleinern, nicht die komplette
Application-Schicht neu zuschneiden.

Mitigation:

- Scope auf die beiden Runner plus Executor-Seams begrenzen
- vorhandene Coordinators weiterverwenden
- neue Extraktionen nur dort, wo sie direkt die benannten Hotspots
  entschaerfen

---

## 9. Ist-Stand der betroffenen Codebasis (ermittelt 2026-04-19)

### 9.1 DataExportRunner.kt

- **Gesamt**: 758 LOC
- **`executeWithPool()`**: Zeile 219–695 (477 LOC)
- **ExportExecutor**: `fun interface` mit 16 Parametern (Zeile 36–68)

Identifizierte Phasen in `executeWithPool()`:

| Phase | Zeilen | LOC | Beschreibung |
|-------|--------|-----|-------------|
| 1 | 225–237 | 13 | Reader + Lister Aufloesung |
| 2 | 239–261 | 23 | Tabellenaufzaehlung (explizit oder Auto-Discovery) |
| 3 | 263–273 | 11 | ExportOutput Aufloesung |
| 4 | 275–288 | 14 | ExportOptions aus CLI-Flags |
| 5 | 297–438 | 142 | Resume-Preflight + Manifest-Lifecycle |
| 6 | 513–528 | 16 | Per-Table ResumeMarker |
| 7 | 532–565 | 34 | onChunkProcessed Callback-Setup |
| 8 | 567–601 | 35 | Single-File Staging Redirect |
| 9 | 603–629 | 27 | Streaming-Ausfuehrung (Executor-Aufruf) |
| 10 | 632–637 | 6 | Per-Table Error Check |
| 11 | 642–665 | 24 | Staging File Atomic Rename |
| 12 | 669–675 | 7 | Manifest Cleanup |
| 13 | 677–692 | 16 | Warnings + Progress Summary |

### 9.2 DataImportRunner.kt

- **Gesamt**: 851 LOC
- **`executeWithPool()`**: Zeile 323–768 (446 LOC)
- **ImportExecutor**: `fun interface` mit 14 Parametern (Zeile 57–83)

Identifizierte Phasen in `executeWithPool()`:

| Phase | Zeilen | LOC | Beschreibung |
|-------|--------|-----|-------------|
| 1 | 332–338 | 7 | Writer Lookup |
| 2 | 340–368 | 29 | ImportOptions Konstruktion |
| 3 | 370–403 | 34 | Directory Scan + effektive Tabellenliste |
| 4 | 409–430 | 22 | Fingerprint-Berechnung |
| 5 | 370–559 | ~190 | Resume-Preflight + Manifest-Lifecycle |
| 6 | 568–594 | 27 | Initiales Manifest fuer frische Laeufe |
| 7 | 596–693 | 98 | Per-Chunk und Per-Table Callback-Setup |
| 8 | 695–724 | 30 | Streaming-Ausfuehrung (Executor-Aufruf) |
| 9 | 728–745 | 18 | Result-Evaluation (Per-Table Errors) |
| 10 | 749–755 | 7 | Manifest Cleanup |
| 11 | 757–765 | 9 | Progress Summary |

### 9.3 CLI-Wiring

- `DataExportCommand.kt`: ExportExecutor-Lambda Zeile 176–197 (22 LOC)
- `DataImportCommand.kt`: ImportExecutor-Lambda Zeile 190–214 (25 LOC)

### 9.4 Tests

- `DataExportRunnerTest.kt`: 1.786 LOC
- `DataImportRunnerTest.kt`: 1.702 LOC

---

## 10. Konkrete Implementierungsschritte (verfeinert)

### 10.1 Schritt A — ExportExecutor-Kontextobjekte definieren

Neue Dateien in `hexagon/application/.../commands/`:

```kotlin
data class ExportExecutionContext(
    val pool: ConnectionPool,
    val reader: DataReader,
    val lister: TableLister,
    val factory: DataChunkWriterFactory,
)

data class ExportExecutionOptions(
    val tables: List<String>,
    val output: ExportOutput,
    val format: DataExportFormat,
    val options: ExportOptions,
    val config: PipelineConfig,
    val filter: DataFilter?,
)

data class ExportResumeState(
    val operationId: String?,
    val resuming: Boolean,
    val skippedTables: Set<String>,
    val resumeMarkers: Map<String, ResumeMarker>,
)

data class ExportCallbacks(
    val progressReporter: ProgressReporter,
    val onTableCompleted: (TableExportSummary) -> Unit,
    val onChunkProcessed: (TableChunkProgress) -> Unit,
)
```

`ExportExecutor` neue Signatur:

```kotlin
fun interface ExportExecutor {
    fun execute(
        context: ExportExecutionContext,
        options: ExportExecutionOptions,
        resume: ExportResumeState,
        callbacks: ExportCallbacks,
    ): ExportResult
}
```

Abhaengigkeiten: keine. Eigenstaendig commitbar (nur neue Typen).

### 10.2 Schritt B — ImportExecutor-Kontextobjekte definieren

Analog zu Schritt A:

```kotlin
data class ImportExecutionContext(
    val pool: ConnectionPool,
    val input: ImportInput,
)

data class ImportExecutionOptions(
    val format: DataExportFormat,
    val options: ImportOptions,
    val readOptions: FormatReadOptions,
    val config: PipelineConfig,
)

data class ImportResumeState(
    val operationId: String?,
    val resuming: Boolean,
    val skippedTables: Set<String>,
    val resumeStateByTable: Map<String, ImportTableResumeState>,
)

data class ImportCallbacks(
    val progressReporter: ProgressReporter,
    val onTableOpened: (table: String, targetColumns: List<TargetColumn>) -> Unit,
    val onChunkCommitted: (ImportChunkCommit) -> Unit,
    val onTableCompleted: (TableImportSummary) -> Unit,
)
```

`ImportExecutor` neue Signatur:

```kotlin
fun interface ImportExecutor {
    fun execute(
        context: ImportExecutionContext,
        options: ImportExecutionOptions,
        resume: ImportResumeState,
        callbacks: ImportCallbacks,
    ): ImportResult
}
```

Abhaengigkeiten: keine. Eigenstaendig commitbar.

### 10.3 Schritt C — DataExportRunner.executeWithPool() zerlegen

Die 477-LOC-Methode wird in benannte private Funktionen geschnitten.
Zielstruktur fuer `executeWithPool()` (< 80 LOC):

```kotlin
fun executeWithPool(pool: ConnectionPool): Int {
    val infra = resolveInfrastructure(pool)              // Phase 1
    val tables = resolveTables(infra)                     // Phase 2
    val output = resolveOutput()                         // Phase 3
    val options = buildExportOptions()                    // Phase 4
    val resume = resolveResumeContext(tables, output)     // Phase 5
    val markers = resolveResumeMarkers(resume, tables)    // Phase 6
    val callbacks = buildCallbacks(resume)                // Phase 7
    val staging = setupStaging(output)                    // Phase 8
    val result = executeStreaming(infra, options, resume,  // Phase 9
                                 markers, callbacks, staging)
    return finalizeResult(result, staging, resume)         // Phase 10-13
}
```

Jede Schrittfunktion hat:
- klaren Eingang (wenige Parameter oder vorheriger Schritt-Output)
- klaren Ausgang (DTO oder benanntes Objekt)
- eigene Fehlerbehandlung wo noetig (z.B. Exit 2/5/7)

Resume-Logik bleibt beim bestehenden `ExportResumeCoordinator`.

### 10.4 Schritt D — DataImportRunner.executeWithPool() zerlegen

Analog zu Schritt C. Zielstruktur:

```kotlin
fun executeWithPool(pool: ConnectionPool): Int {
    val writer = resolveWriter(pool)                      // Phase 1
    val options = buildImportOptions()                    // Phase 2
    val input = resolveInput(options)                     // Phase 3
    val fingerprint = computeFingerprint(options, input)  // Phase 4
    val resume = resolveResumeContext(fingerprint, input)  // Phase 5-6
    val callbacks = buildCallbacks(resume)                 // Phase 7
    val result = executeStreaming(writer, options, input,   // Phase 8
                                 resume, callbacks)
    return finalizeResult(result, resume)                   // Phase 9-11
}
```

### 10.5 Schritt E — CLI-Wiring auf neue Kontexte umstellen

- `DataExportCommand.kt`: ExportExecutor-Lambda mappt auf neue DTOs
- `DataImportCommand.kt`: ImportExecutor-Lambda mappt auf neue DTOs
- Sichtbares 1:1-Mapping von `StreamingExporter`/`StreamingImporter`
  Parametern auf die neuen Kontextobjekte

### 10.6 Schritt F — Test-Builder einfuehren und Tests migrieren

Vor der Einzelmigration:
- `testExportExecutor { }` Helper in `DataExportRunnerTest.kt`
- `testImportExecutor { }` Helper in `DataImportRunnerTest.kt`

Dann ~130 Testfaelle systematisch migrieren. Die meisten aendern sich
nur in der Executor-Lambdasignatur.

### 10.7 Schritt G — `docs/quality.md` aktualisieren

- Runner-/Executor-Findings als umgesetzt markieren
- LOC-Zahlen aktualisieren

---

## 11. Empfohlene Commit-Reihenfolge

```
A + B (DTO-Definitionen fuer Export + Import)
  ↓
C + D (Runner-Zerlegung, alte Executor-Signatur noch parallel)
  ↓
E (CLI-Wiring auf neue Kontexte)
  ↓
F (Test-Migration)
  ↓
G (Doku)
```

Alle Edits eines Schritts abschliessen, dann einmal `docker build`.
Keine Zwischenbuilds bei Teilaenderungen.
