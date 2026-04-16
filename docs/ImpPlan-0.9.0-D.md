# Implementierungsplan: Phase D - Import-Checkpoint und Resume

> **Milestone**: 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI
> **Phase**: D (Import-Checkpoint und Resume)
> **Status**: Planned (2026-04-16)
> **Referenz**: `docs/implementation-plan-0.9.0.md` Abschnitt 3,
> Abschnitt 4.3 bis 4.7, Abschnitt 5.5, Abschnitt 6.4, Abschnitt 8.1
> bis 8.3; `docs/ImpPlan-0.9.0-A.md`; `docs/ImpPlan-0.9.0-B.md`;
> `docs/cli-spec.md` `data import`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`;
> `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingImporter.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ImportResult.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt`;
> `docs/design.md` Abschnitt 3.2

---

## 1. Ziel

Phase D macht den in Phase A sichtbar gemachten und in Phase B
infrastrukturell vorbereiteten Resume-Vertrag fuer den Importpfad produktiv.

Der Teilplan beantwortet bewusst die import-spezifischen Fragen:

- wie `DataImportRunner` einen Resume-Lauf gegen Manifest und Target
  vorprueft
- wie `StreamingImporter` Checkpoints an committed Chunk-Grenzen fuehrt
- wie file- und directory-basierte Inputs stabil gegen einen Checkpoint
  validiert werden
- welche Importoptionen fuer eine Wiederaufnahme identisch sein muessen
- wie bereits abgeschlossene Tabellen und laufende Tabellen in einem
  Resume-Lauf behandelt werden
- wie `--on-error`, `--on-conflict`, `--trigger-mode` und `--truncate`
  in den Resume-Vertrag eingehen
- wie die Phase-A-Platzhalter in Runner und CLI-Spec durch echte Runtime
  ersetzt werden

Phase D liefert damit keine allgemeine Checkpoint-Infrastruktur mehr,
sondern den produktiven Import-Datenpfad auf dem Phase-B-Unterbau.

Nach Phase D soll klar und testbar gelten:

- file-/directory-basierte Importlaeufe koennen ab dem letzten bestaetigten
  Checkpoint kontrolliert fortgesetzt werden
- Checkpoints werden nur nach erfolgreich bestaetigten Importschritten
  fortgeschrieben
- semantisch inkompatible Resume-Versuche scheitern im Preflight mit der
  bestehenden Import-Fehlerklasse
- bestaetigte Tabellen- und Chunk-Ergebnisse bleiben bei Abbruch sichtbar
  und werden bei Resume nicht still neu interpretiert

---

## 2. Ausgangslage

Aktueller Stand im Repo:

- `DataImportCommand` exponiert bereits:
  - `--resume`
  - `--checkpoint-dir`
- `DataImportRunner` transportiert beide Werte im Request-Modell und
  fuehrt einen ersten CLI-Preflight aus:
  - `--resume` mit `--source -` endet mit Exit `2`
  - fuer file-/directory-basierte Quellen wird aktuell nur sichtbar
    gewarnt, dass die Resume-Runtime noch nicht aktiv ist
- `DataImportRunner` besitzt bereits einen ausgepraegten Preflight-Pfad:
  - Format-Erkennung bzw. `--format`
  - optionale Schema-Pruefung
  - Target-Aufloesung
  - Dialekt-/Flag-Validierung
  - Exit `3` fuer fachliche Preflight-Inkompatibilitaeten
  - Exit `7` fuer Konfigurations-, Parse- und Manifest-/Checkpoint-Fehler
- `StreamingImporter` arbeitet weiterhin ohne Checkpoint-Port, aber mit
  einer bereits passenden Transaktionsgrenze:
  - `session.write(...)`
  - `session.commitChunk()`
  - erst danach werden Zaehler und `ImportChunkProcessed` sichtbar
- `StreamingImporter` unterstuetzt bereits differenzierte Fehlerpfade ueber:
  - `--on-error abort`
  - `--on-error skip`
  - `--on-error log`
  - `failedFinish` nach `finishTable()`
- `docs/cli-spec.md` dokumentiert den sichtbaren 0.9.0-Resume-Einstieg
  bereits, verweist im Import-Text aber noch auf "Phase B/C", obwohl die
  produktive Import-Runtime in Phase D landet.

Konsequenz:

- der Importpfad besitzt bereits die sichtbare Nutzeroberflaeche und einen
  starken lokalen Preflight, aber noch keine Manifest-Auswertung und keine
  Wiederaufnahme auf committed Chunk-Grenzen
- ohne Phase D bleibt `--resume` im Importpfad nur ein dokumentierter
  Platzhalter mit Warning
- besonders kritisch ist die Semantik von `--on-error` und
  directory-basierten Imports:
  Resume darf bestaetigte Chunks nicht doppelt schreiben und darf
  Fehler-/Skip-Verhalten nicht durch eine zu grobe Fortschrittsmarke
  verfremden

---

## 3. Scope fuer Phase D

### 3.1 In Scope

- Resume-Preflight fuer `data import` auf Basis des Phase-B-Manifests
- Checkpoint-Initialisierung fuer neue Importlaeufe
- committed-chunk-basierte Fortschreibung waehrend des Imports
- importseitige Resume-Marker pro Input-Slice bzw. Tabelle
- Kompatibilitaetspruefungen fuer:
  - Quell-Fingerprint / effektive Source-Identitaet
  - Input-Datei bzw. Dateimenge bei Directory-Import
  - Tabellenzuordnung / Importreihenfolge
  - Format / Encoding / CSV-Optionen
  - `--on-error`
  - `--on-conflict`
  - `--trigger-mode`
  - `--truncate`
  - `--disable-fk-checks`
  - `--reseed-sequences`
  - optionales `--schema`-Preflight
- klare Ablehnung nicht resume-faehiger Importfaelle:
  - stdin
  - semantisch abweichende Optionen
  - unpassende Input-Dateien oder Input-Topologie
- Fortschritts- und Ergebnisanreicherung fuer Resume-/Operation-Kontext
- Import-spezifische Tests fuer Abbruch und Wiederaufnahme
- Korrektur der normativen Import-Doku auf Phase-B/D-Vertrag

### 3.2 Bewusst nicht Teil von Phase D

- Export-Resume-Logik
- Signalbehandlung bei SIGINT/SIGTERM
- Retry-Orchestrierung
- Resume fuer `data transfer`
- atomische Rollbacks auf fruehere Checkpoints
- paralleler oder partition-aware Import
- Resume fuer stdin-Import

Praezisierung:

Phase D loest "wie wird Import resume-faehig?", nicht
"wie werden alle Datenpfade gleichzeitig resilienzfaehig?".

---

## 4. Leitentscheidungen fuer Phase D

### 4.1 Import-Resume bleibt file-/directory-basiert

Phase D uebernimmt den in Phase A gesetzten 0.9.0-Zuschnitt:

- Resume ist nur fuer file- oder directory-basierte Importquellen gueltig
- stdin-Import bleibt regulaere Basisfunktion, aber kein Resume-Ziel

Verbindliche Folge:

- `DataImportRunner` behaelt den CLI-Preflight gegen `--resume` auf
  `--source -`
- `StreamingImporter` braucht keinen halbunterstuetzten Pipe-/TTY-Resume-Pfad

### 4.2 Der bestaetigte Resume-Stand liegt an committed Chunk-Grenzen

Phase D fixiert:

- ein Import-Checkpoint beschreibt den letzten erfolgreich bestaetigten
  Chunk einer Tabelle bzw. eines Input-Slices
- ein Checkpoint wird erst fortgeschrieben, wenn `session.commitChunk()`
  erfolgreich war
- Mid-Chunk-, Mid-Write- oder nur gelesene Zustaende sind kein gueltiger
  Resume-Stand

Verbindliche Folge:

- Wiederaufnahme startet immer am naechsten noch nicht bestaetigten Chunk
- ein Chunk, der zwar gelesen oder geschrieben, aber nicht committed wurde,
  gilt nach Resume als nicht bestaetigt
- `finishTable()` bzw. Tabellenabschluss braucht einen eigenen
  Abschlussstatus im Manifest; ein `failedFinish` darf die Tabelle nicht
  still als komplett markieren

### 4.3 Import-Kompatibilitaet ist semantisch, nicht nur dateibasiert

Phase D fixiert fuer Resume-Preflight mindestens diese Gleichheitsklassen:

- Quell-Fingerprint bzw. effektiv aufgeloeste Source-Identitaet des Laufs
- aufgeloeste Zielidentitaet des Laufs:
  - URL bzw. Alias
  - Dialekt
  - relevanter Schema-/Katalog-Kontext
- Input-Topologie:
  - Single-File
  - Directory
- Input-Datei bzw. stabile Dateimenge und Tabellenzuordnung
- Format
- Encoding
- CSV-relevante Optionen:
  - Header-Modus
  - NULL-String
- Tabellenliste und effektive Importreihenfolge
- optionales Schema-Preflight bzw. die daraus abgeleitete Reihenfolge
- `--on-error`
- `--on-conflict`
- `--trigger-mode`
- `--truncate`
- `--disable-fk-checks`
- `--reseed-sequences`
- `chunkSize`

Nicht zulaessig ist:

- einen Checkpoint gegen eine andere aufgeloeste Source oder ein anderes
  Target weiterzuverwenden
- einen Checkpoint mit veraendertem `--on-error` oder `--on-conflict`
  "best effort" fortzusetzen
- bei veraenderter Directory-Topologie still weiterzulaufen

Begruendung:

Gerade im Importpfad aendern `--on-error`, `--truncate`,
Trigger-/FK-Verhalten und die Tabellenreihenfolge den fachlichen Effekt
des Laufs unmittelbar. Resume darf diese Semantik nicht ueberdecken.

Praezisierung zur Zielvalidierung:

- verglichen wird die Zielidentitaet des Laufes, nicht der mutable
  Live-Zustand der Zieldatenbank
- durch bereits bestaetigte Import-Chunks veraenderte Zieldaten sind im
  Resume-Pfad erwartbar und fuer sich allein kein Inkompatibilitaetsgrund

### 4.4 `--truncate` darf bestaetigten Fortschritt nicht loeschen

`--truncate` ist im Erstlauf fachlich sinnvoll, kollidiert bei Resume aber
direkt mit bereits bestaetigten Chunks.

Phase D fixiert:

- `truncate` darf nur beim erstmaligen Start einer noch unbegonnenen Tabelle
  ausgefuehrt werden
- fuer eine Tabelle mit bereits bestaetigtem Chunk-Fortschritt darf ein
  Resume-Lauf kein erneutes `truncate` ausloesen
- fuer bereits erfolgreich abgeschlossene Tabellen ist `truncate` im
  Resume-Lauf ebenfalls nicht erneut zulaessig

Verbindliche Folge:

- der Manifeststatus einer Tabelle muss vor `openTable(..., options)` klar
  unterscheiden, ob die Tabelle frisch, teilweise bestaetigt oder bereits
  abgeschlossen ist
- ein Resume auf einer teilweise bestaetigten Tabelle mit gesetztem
  `--truncate` darf bestaetigte Daten nicht wieder entfernen

Damit bleibt der committed-chunk-basierte Resume-Marker fachlich gueltig und
wird nicht durch erneutes Leeren der Tabelle entwertet.

### 4.5 Directory-Importe brauchen eine stabile Slice-Zuordnung

Der Importpfad kann ganze Verzeichnisse importieren. Resume ist hier nur
sicher, wenn das Manifest nicht nur "letzte Tabelle X" kennt, sondern die
effektive Eingabemenge stabil beschreibt.

Phase D fixiert:

- das Manifest haelt fuer Directory-Importe die aufgeloeste Menge
  `table -> input file` stabil fest
- ein Resume-Lauf arbeitet gegen diese bestaetigte Zuordnung, nicht gegen
  eine spaeter neu entdeckte Dateimenge
- wenn `--schema` die Importreihenfolge topologisch beeinflusst, ist genau
  diese Reihenfolge Teil des Resume-Vertrags

Damit bleibt Resume deterministisch, auch wenn sich Verzeichnisinhalt oder
Dateinamen zwischen erstem Lauf und Wiederaufnahme geaendert haben.

### 4.6 `--on-error`-Semantik bleibt im Resume sichtbar

`StreamingImporter` behandelt Chunk-Fehler heute bereits unterschiedlich.
Phase D darf diese Sichtbarkeit nicht verlieren.

Verbindliche Folge:

- bei `--on-error abort` endet der Lauf ohne Fortschreibung ueber den
  letzten bestaetigten Chunk hinaus
- bei `--on-error skip` und `--on-error log` duerfen nur bereits committed
  Chunks als bestaetigt gelten; spaetere Lese-/Normalisierungsfehler
  duerfen keinen weitergezogenen Resume-Stand erzeugen
- protokollierte `chunkFailures` und `rowsFailed` bleiben Teil des
  Tabellenstatus und duerfen bei Resume nicht still verschwinden

Phase D verspricht damit kein "Fehler vergessen durch Resume", sondern eine
kontrollierte Fortsetzung ab dem letzten fachlich bestaetigten Stand.

### 4.7 Bereits abgeschlossene Tabellen werden nicht neu importiert

Phase D fixiert:

- Tabellen, die im Manifest bereits als erfolgreich abgeschlossen markiert
  sind, werden im Resume-Lauf uebersprungen
- Tabellen mit bestaetigtem Chunk-Fortschritt, aber ohne Abschlussstatus,
  werden ab dem naechsten offenen Chunk fortgesetzt
- eine Tabelle mit `failedFinish` bleibt unvollstaendig und darf nicht als
  abgeschlossen gelten

Das reduziert Doppelimporte und haelt den Resume-Vertrag fuer grosse
Directory-Importe nachvollziehbar.

### 4.8 Phase D aktiviert die Runtime und bereinigt die Platzhalter

Die aktuelle Warning in `DataImportRunner`, dass `--resume` akzeptiert,
aber ignoriert wird, ist nach Phase D nicht mehr zulaessig.

Verbindliche Folge:

- file-/directory-basiertes `--resume` fuehrt nach Phase D entweder zu
  echter Wiederaufnahme oder zu einem klaren Preflight-/Manifestfehler
- der Runner-Text und die Import-Stelle in `docs/cli-spec.md` werden von
  "Phase B/C" auf den produktiven Phase-B/D-Vertrag umgestellt
- ein "accepted but ignored" ist fuer den Importpfad nach Abschluss dieser
  Phase nicht mehr Teil des Vertrags

---

## 5. Geplante Arbeitspakete

### 5.1 D1 - Resume-Preflight im Runner fertigstellen

- `DataImportRunner` an den Phase-B-Checkpoint-Port anbinden
- Resume-Referenz aufloesen und Manifest laden
- semantische Kompatibilitaetspruefungen gegen Request, Source und Target
  ausfuehren
- Zielabgleich explizit auf Laufidentitaet statt auf den mutable
  DB-Inhaltszustand begrenzen
- den sichtbaren Warning-Platzhalter durch echten Resume-/Fehlerpfad
  ersetzen
- die Exit-Code-Grenze beibehalten:
  - Exit `3` fuer semantisch inkompatible Resume-Referenzen
  - Exit `7` fuer unlesbare oder unparseable Checkpoints/Manifeste

### 5.2 D2 - Import-Marker und Tabellenstatus definieren

- importseitigen Resume-Marker pro Tabelle/Input-Slice festziehen
- Tabellenstatus im Manifest unterscheiden:
  - noch nicht begonnen
  - teilweise bestaetigt
  - erfolgreich abgeschlossen
  - mit Fehler/`failedFinish` beendet
- Chunk-Index, Input-Datei und relevante Zaehler so persistieren, dass
  `StreamingImporter` den Lauf deterministisch fortsetzen kann

### 5.3 D3 - Checkpoint-Schreibpunkte in `StreamingImporter` verankern

- Checkpoint-Schreibpunkte an committed Chunk-Grenzen anbinden
- Fortschreibung bei Tabellenabschluss sauber modellieren
- `truncate` nur fuer frisch begonnene Tabellen zulaessig machen und bei
  teilweise oder vollstaendig bestaetigten Tabellen unterbinden
- sicherstellen, dass Lese-, Normalisierungs-, Write- und Commit-Fehler
  den Resume-Stand nicht zu weit ziehen
- Resume-Kontext in Progress- und Result-Pfaden durchreichen

### 5.4 D4 - Directory- und Schema-Pfad konsolidieren

- manifestgebundene Zuordnung `table -> input file` fuer Directory-Importe
  produktiv machen
- Zusammenspiel mit `--tables` und optionalem `--schema` festziehen
- Wiederaufnahme bei geaenderter Dateimenge oder anderer Reihenfolge klar
  als Preflight-Fehler behandeln

### 5.5 D5 - Import-Dokumentation und Fehlermeldungen angleichen

- `docs/cli-spec.md` fuer `data import` auf den produktiven Phase-D-Vertrag
  aktualisieren
- Import-Hilfetexte und Runner-Meldungen mit den finalen Resume-Regeln
  synchronisieren
- falls noetig Referenzen in weiteren 0.9.0-Teilplaenen auf "Phase B/C"
  auf "Phase B/D" oder "Phase B bis D" umstellen

### 5.6 Commit-Staffelung (2026-04-16)

Phase D ist vom Umfang mit C.1 + C.2 zusammen vergleichbar. Damit
jede Zwischenstufe ein sauberer Review- und Release-Anker ist,
staffeln wir die Umsetzung in vier Commits. Jeder Commit laesst den
Build (`docker build --target build --build-arg GRADLE_TASKS=check`)
komplett gruen zurueck — Kover-Verify inklusive.

**Commit 1 — D.1: Preflight + Warning-Removal**

- [ ] `DataImportRunner` an `CheckpointStore` + `CheckpointConfig.merge`
  anbinden (symmetrisch zu `DataExportRunner`)
- [ ] neuer `ImportOptionsFingerprint` mit allen §4.3-Feldern
  (Source, Target, Format, Encoding, CSV-Header/NULL, `--on-error`,
  `--on-conflict`, `--trigger-mode`, `--truncate`,
  `--disable-fk-checks`, `--reseed-sequences`, Tabellenliste,
  `chunkSize`, Input-Topologie)
- [ ] Preflight-Logik: Manifest laden, `operationType == IMPORT`,
  Fingerprint, Tabellenliste → Exit 3; unlesbarer Manifest /
  fehlende Checkpoint-Datei → Exit 7
- [ ] Initial-Manifest-Save bei fresh runs; `complete()` bei Erfolg
- [ ] "accepted but not yet active"-Warning entfernen (§4.8)
- [ ] `docs/cli-spec.md` + `docs/guide.md` Import-Abschnitte auf
  Phase-D-Vertrag umschreiben
- [ ] Runner-Tests fuer alle Preflight-Pfade (Exit 2/3/7, fresh-run,
  fingerprint-match/mismatch)

**Commit 2 — D.2 + D.3: Marker-Modell + Streaming-Checkpoint-Writes**

- [ ] Tabellenstatus im Manifest praezisieren
  (IN_PROGRESS mit `lastCommittedChunkIndex` vs. COMPLETED vs.
  FAILED_FINISH-Abgrenzung)
- [ ] `StreamingImporter` schreibt Checkpoint nach
  `session.commitChunk()`; skipt `COMPLETED`-Tabellen; fortgesetzte
  Tabellen starten am naechsten offenen Chunk
- [ ] `--truncate`-Guard (§4.4): nur fuer frisch begonnene Tabellen;
  Resume mit teilweise bestaetigten Chunks loest **kein** erneutes
  Truncate aus; bei bereits `COMPLETED`-Tabellen wird `truncate`
  ebenfalls nicht mehr ausgefuehrt
- [ ] `--on-error abort/skip/log` behalten ihre Semantik beim Resume
  (§4.6); `failedFinish` markiert eine Tabelle nicht als
  `COMPLETED`
- [ ] Runner-Tests fuer commit-boundary-Resume,
  `--truncate`-Guard, `--on-error`-Interaktionen
- [ ] Streaming-Tests: Abbruch nach bestaetigtem Chunk → Resume am
  naechsten Chunk; Fehler vor `commitChunk()` zieht Checkpoint nicht
  zu weit
- [ ] Kover-Coverage bleibt pro Modul ≥ 90 %

**Commit 3 — D.4: Directory-Topologie**

- [ ] Manifest persistiert `table -> inputFile` stabil fuer
  Directory-Importe (§4.5)
- [ ] Preflight: geaenderte Directory-Dateimenge oder andere
  Reihenfolge → Exit 3
- [ ] Schema-Reihenfolge-Zusammenspiel mit `--schema` fixiert
  (Manifest-Reihenfolge gewinnt, wenn gespeichert)
- [ ] Tests fuer stabile Slice-Zuordnung (file-added, file-removed,
  renamed, changed order)

**Commit 4 — D.5: Finalisierung**

- [ ] Plan-Status auf "In Review" flippen
- [ ] restliche Hilfetexte / Fehlermeldungen angleichen
  (Runner-Meldungen, CLI-Help)
- [ ] eventuelle `Phase B/C` → `Phase B/D` Quer-Referenzen in
  Geschwister-Plaenen und Masterplan bereinigen
- [ ] voller `docker build --target build --build-arg
  GRADLE_TASKS=check` gruen; CHANGELOG-Einsatz nach Release-Konvention
  erst am Release-Cut

---

## 6. Teststrategie fuer Phase D

Phase D braucht sowohl Runner- als auch Streaming-Tests. Reine
Manifest- oder Port-Tests aus Phase B reichen hier nicht aus.

### 6.1 Runner- und Preflight-Tests

- Resume mit stdin bleibt Exit `2`
- semantisch inkompatible Resume-Referenz endet mit Exit `3`
- unlesbares Manifest / kaputte Checkpoint-Datei endet mit Exit `7`
- Zielidentitaet wird geprueft, ohne den durch bereits bestaetigte Chunks
  veraenderten Live-Zustand der Zieldatenbank als Inkompatibilitaet zu
  behandeln
- veraenderte Importoptionen (`--on-error`, `--on-conflict`,
  `--trigger-mode`, `--truncate`, `--disable-fk-checks`,
  `--reseed-sequences`) werden als inkompatibel erkannt
- veraendertes Format / Encoding / CSV-Header-Modus / NULL-String wird als
  inkompatibel erkannt
- veraenderte Single-File-Quelle bzw. Directory-Dateimenge wird als
  inkompatibel erkannt
- `--schema`-basierte Reihenfolge wird bei Resume nicht still neu berechnet

### 6.2 Streaming- und Integrationsnahe Tests

- Abbruch nach bestaetigtem Chunk und erfolgreiche Wiederaufnahme ab dem
  naechsten Chunk
- Fehler vor `commitChunk()` fuehrt nicht zu zu weit gezogenem Checkpoint
- Resume einer teilweise bestaetigten Tabelle mit gesetztem `--truncate`
  loest kein erneutes Leeren der Tabelle aus
- `--on-error abort` resume-faehig ab letztem bestaetigten Chunk
- `--on-error skip` und `--on-error log` behalten Fehler-/Zaehlerbild und
  erzeugen keinen ungueltigen Resume-Fortschritt
- `failedFinish` markiert Tabelle nicht als abgeschlossen
- bereits abgeschlossene Tabellen werden im Resume-Lauf nicht neu
  geschrieben
- Directory-Import mit mehreren Dateien/Tabellen setzt nach Abbruch an der
  richtigen Tabelle und Datei fort

### 6.3 Normative Doku-Checks

- `docs/cli-spec.md` nennt fuer `data import` die korrekte Phasenreferenz
  und die finale Exit-Code-Abgrenzung
- die sichtbaren Runner-/Helptexte widersprechen der Doku nicht mehr

---

## 7. Datei- und Codebasis-Betroffenheit

Sicher betroffen:

- `docs/ImpPlan-0.9.0-D.md`
- `docs/cli-spec.md`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingImporter.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ImportResult.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt`
- Phase-B-Checkpoint-Port und dateibasierter Store

Sehr wahrscheinlich betroffen:

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataImportSmokeTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliHelpAndBootstrapTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterSqliteTest.kt`
- gemeinsame Checkpoint-/Manifest-Tests aus Phase B

Moeglicherweise betroffen:

- Import-spezifische Summary-/Formatter-Pfade, sofern Resume- oder
  `operationId`-Kontext sichtbar gemacht wird
- weitere 0.9.0-Teilplaene oder die Masterplan-Doku, falls dort noch
  importseitige "Phase B/C"-Formulierungen stehen

---

## 8. Risiken und offene Punkte

- `--on-error skip` und `--on-error log` brauchen besondere Sorgfalt, damit
  Resume keinen fachlich anderen Lauf simuliert als der Erstlauf.
- Bei Directory-Importen ist die Frage nach stabilem Dateifingerprint und
  Dateimengen-Vergleich zentral; eine zu schwache Pruefung oeffnet stille
  Fehlfortsetzungen.
- Das Zusammenspiel mit `--schema` darf den Resume-Pfad nicht doppelt
  entscheiden lassen: Manifest-Reihenfolge und aktuelles Schema muessen
  sauber priorisiert werden.
- `failedFinish` ist fuer Resume heikel, weil bereits geschriebene Daten und
  fehlender Tabellenabschluss auseinanderfallen koennen.
- Falls der Port aus Phase B Import-Slices zu grob modelliert, muss Phase D
  den Manifestvertrag vor der Runtime-Aktivierung nachschaerfen.

---

## 9. Entscheidungsempfehlung

Phase D sollte mit genau diesem Zuschnitt umgesetzt werden:

- Resume bleibt in 0.9.0 importseitig file-/directory-basiert
- Fortschritt wird nur an committed Chunk-Grenzen bestaetigt
- die semantische Kompatibilitaet umfasst mehr als Tabelle und Format,
  insbesondere auch `--on-error`, Trigger-/Conflict-Verhalten,
  `--truncate` und Directory-Topologie
- nach Abschluss der Phase gibt es im Importpfad keinen sichtbaren
  Resume-Platzhalter mehr, sondern einen echten produktiven Vertrag

Damit schliesst 0.9.0 den Importpfad auf denselben Beta-Resilienzstand wie
den Exportpfad auf, ohne den Milestone um Retry, Signale oder Parallelitaet
aufzuweiten.
