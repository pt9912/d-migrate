# Implementierungsplan: Phase C - Export-Checkpoint und Resume

> **Milestone**: 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI
> **Phase**: C (Export-Checkpoint und Resume)
> **Status**: Planned (2026-04-16)
> **Referenz**: `docs/implementation-plan-0.9.0.md` Abschnitt 3, Abschnitt 4.3
> bis 4.7, Abschnitt 5.4, Abschnitt 6.3, Abschnitt 8.1 und 8.2;
> `docs/ImpPlan-0.9.0-A.md`; `docs/ImpPlan-0.9.0-B.md`;
> `docs/cli-spec.md` `data export`; `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`;
> `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ExportResult.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt`;
> `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ExportOutput.kt`;
> `docs/design.md` Abschnitt 3.2

---

## 1. Ziel

Phase C macht den in Phase A sichtbaren und in Phase B infrastrukturell
vorbereiteten Resume-Vertrag fuer den Exportpfad produktiv.

Der Teilplan beantwortet bewusst die export-spezifischen Fragen:

- wie ein Exportlauf einen resume-faehigen Positionsmarker pro Tabelle fuehrt
- wann waehrend eines Exports ein Checkpoint als bestaetigt gilt
- wie `DataExportRunner` und `StreamingExporter` mit vorhandenen
  Checkpoint-Manifests zusammenspielen
- welche Exportoptionen fuer einen Resume-Lauf identisch sein muessen
- wie die Quellidentitaet eines Exportlaufs gegen das Manifest validiert wird
- wie file-basierte Output-Ziele gegen einen Checkpoint validiert werden
- wie der Exportpfad nicht resume-faehige Faelle klar abweist

Phase C liefert damit keine allgemeine Checkpoint-Infrastruktur mehr, sondern
den ersten echten Datenpfad auf diesem Unterbau.

Nach Phase C soll klar und testbar gelten:

- file-basierte Exportlaeufe koennen ab einem bestaetigten Checkpoint
  kontrolliert fortgesetzt werden
- der Exportpfad schreibt Checkpoints nur an fachlich sauberen Stellen
- semantisch inkompatible Resume-Versuche scheitern im Preflight
- bestehende Export-Statistiken und Fortschrittsmeldungen bleiben erhalten,
  bekommen aber Resume-/Operation-Kontext hinzu

---

## 2. Ausgangslage

Aktueller Stand im Repo:

- `DataExportCommand` besitzt inzwischen sichtbare Resume-Oberflaeche:
  - `--resume`
  - `--checkpoint-dir`
- `DataExportRunner` transportiert diese Werte bereits im Request-Modell und
  fuehrt einen ersten CLI-Preflight aus:
  - `--resume` ohne `--output` wird abgewiesen
  - bei file-basiertem Resume wird aktuell nur sichtbar gewarnt, dass die
    Runtime noch nicht aktiv ist
- `StreamingExporter` arbeitet weiterhin strikt sequenziell und ohne
  Checkpoint-Schreibpunkte:
  - Tabellen werden von vorn gelesen
  - Outputs werden immer neu `TRUNCATE_EXISTING` aufgebaut
  - Fortschritt wird nur ueber Run-/Table-/Chunk-Events emittiert
- `ExportResult` und `ProgressEvent` tragen noch keinen resume-spezifischen
  Exportzustand.
- `docs/cli-spec.md` dokumentiert den 0.9.0-Resume-Einstieg fuer Export
  bereits, ordnet die eigentliche Runtime aber noch Phase B/C zu.

Konsequenz:

- der Exportpfad besitzt bereits die sichtbare Nutzeroberflaeche, aber noch
  keine Manifest-Auswertung und keine Wiederaufnahme aus bestaetigten
  Checkpoints
- ohne Phase C bleibt `--resume` fuer Export nur ein dokumentierter
  Preflight-Vertrag ohne reale Wirkung
- besonders kritisch ist die Output-Kante:
  der heutige Export ueberschreibt Ziel-Dateien immer neu und braucht daher
  fuer Resume einen expliziten Kompatibilitaets- und Dateivertrag

---

## 3. Scope fuer Phase C

### 3.1 In Scope

- Resume-Preflight fuer `data export` auf Basis des Phase-B-Manifests
- Checkpoint-Initialisierung fuer neue Exportlaeufe
- resume-faehige Exportfortsetzung pro Tabelle
- persistierte Export-Positionsmarker pro Tabelle
- Kompatibilitaetspruefungen fuer:
  - Quell-Fingerprint / effektive Source-Identitaet
  - Tabellenliste
  - Output-Ziel
  - Format / Encoding / CSV-Optionen
  - `--filter`
  - `--since-column` / `--since`
- klare Ablehnung nicht resume-faehiger Exportfaelle:
  - stdout
  - unpassende Zielpfade
  - semantisch inkompatible Checkpoints
- Fortschritts- und Ergebnisanreicherung fuer Resume/Operation-Kontext
- Export-spezifische Tests fuer Abbruch und Wiederaufnahme

### 3.2 Bewusst nicht Teil von Phase C

- importseitige Resume-Fortsetzung
- allgemeine Signalbehandlung bei SIGINT/SIGTERM
- Retry-Logik
- paralleler Export mehrerer Tabellen
- Resume fuer `data transfer`
- nachtraegliche Archivierungs-/GC-Strategien fuer alte Checkpoints

Praezisierung:

Phase C loest "wie wird Export resume-faehig?", nicht
"wie werden alle Datenpfade gleichzeitig resumable?".

---

## 4. Leitentscheidungen fuer Phase C

### 4.1 Export-Resume bleibt file-basiert

Phase C uebernimmt den in Phase A gesetzten 0.9.0-Zuschnitt:

- Resume ist nur fuer file-basierte Exportpfade gueltig
- stdout-Export bleibt regulaere Basisfunktion, aber kein Resume-Ziel

Verbindliche Folge:

- `DataExportRunner` behaelt den CLI-Preflight gegen `--resume` ohne
  `--output`
- `StreamingExporter` braucht keinen halbunterstuetzten TTY-/Pipe-Resume-Pfad

### 4.2 Der Resume-Marker ist pro Tabelle und fachlich bestaetigt

Phase C fixiert:

- Export-Resume arbeitet pro Tabelle mit einem serialisierbaren Marker
- ein Marker repraesentiert den letzten fachlich bestaetigten Stand, nicht
  einen beliebigen Byte-Offset
- Wiederaufnahme startet am naechsten noch nicht bestaetigten Bereich

Verbindliche Folge:

- ein Checkpoint wird erst fortgeschrieben, wenn ein Chunk inklusive
  Dateischreibpfad erfolgreich abgeschlossen ist
- Mid-Chunk- oder Mid-Writer-Zustaende sind kein gueltiger Resume-Stand

### 4.3 Export-Kompatibilitaet ist strenger als "gleiche Quelle"

Phase C fixiert fuer Resume-Preflight mindestens diese Gleichheitsklassen:

- Quell-Fingerprint bzw. effektiv aufgeloeste Source-Identitaet des Laufs
- Tabellenmenge und Reihenfolge des Laufs
- Output-Modus:
  - Single-File
  - File-per-table
- Zielfade/Dateinamen
- Ausgabeformat
- Encoding
- CSV-relevante Optionen:
  - Delimiter
  - BOM
  - Header
  - NULL-String
- `--filter`
- `--since-column`
- `--since`

Nicht zulaessig ist:

- einen Checkpoint "best effort" mit geaendertem Filter oder anderem
  Zielpfad wiederzuverwenden
- einen Checkpoint gegen eine andere aufgeloeste Source-DB oder einen
  abweichenden Lauf-Fingerprint weiterzuverwenden
- Resume bei formell gleichem Format, aber abweichender Output-Topologie
  still durchlaufen zu lassen

### 4.4 Auto-Discovery und Resume duerfen sich nicht widersprechen

Der heutige Exportpfad erlaubt Auto-Discovery aller Tabellen. Fuer Resume ist
dieser Pfad nur dann sicher, wenn das Manifest die aufgeloeste Tabellenmenge
stabil festhaelt.

Phase C fixiert:

- ein Resume-Lauf arbeitet gegen die im Checkpoint bestaetigte effektive
  Tabellenliste
- spaetere Unterschiede zwischen aktueller Auto-Discovery und Manifest sind
  ein semantischer Preflight-Fehler, kein stilles Nachziehen

Damit bleibt Resume deterministisch, auch wenn sich die Quelle seit dem
abgebrochenen Lauf veraendert hat.

### 4.5 Resume und bestehende Output-Dateien brauchen einen expliziten Vertrag

Der aktuelle Exportpfad oeffnet Ziel-Dateien mit `TRUNCATE_EXISTING`.
Resume braucht deshalb einen kontrollierten Dateivertrag.

Phase C fixiert:

- Resume darf bestehende Exportdateien nur weiterverwenden, wenn Manifest und
  Dateiziel kompatibel sind
- fuer 0.9.0 wird Single-File-Resume nicht als In-Place-Fortsetzung in eine
  bereits begonnene Exportdatei freigegeben
- Single-File-Resume arbeitet stattdessen ueber eine manifestgebundene,
  vom Checkpoint-Store kontrollierte Fortsetzungsdatei bzw. einen
  gleichwertigen kontrollierten Fortsetzungspfad
- fuer File-per-table-Resume muss die Zuordnung
  `table -> output path` manifestgebunden stabil sein

Begruendung:

- der aktuelle Exportpfad oeffnet Single-File-Ziele mit
  `TRUNCATE_EXISTING`
- JSON-/YAML-/CSV-Writer besitzen unterschiedliche Container- und
  Header-Semantik
- ein kontrollierter Fortsetzungspfad ist fuer 0.9.0 belastbarer als ein
  scheinbar einfacher In-Place-Append-Vertrag

### 4.6 Leere Tabellen und Teilfehler bleiben Teil des Resume-Vertrags

`StreamingExporter` garantiert heute fuer leere Tabellen einen echten
Writer-Lebenszyklus. Phase C darf diese Garantie nicht brechen.

Verbindliche Folge:

- auch leere Tabellen muessen einen nachvollziehbaren Resume-/Abschlussstatus
  im Manifest hinterlassen
- Tabellenfehler bleiben pro Tabelle sichtbar; ein fehlgeschlagener
  Exportlauf darf bereits bestaetigte Tabellenergebnisse nicht entwerten

### 4.7 Phase C aktiviert die Runtime, nicht nur den Preflight

Die aktuelle Warnung im Runner, dass `--resume` zwar akzeptiert, aber noch
nicht aktiv ist, ist nach Phase C nicht mehr zulaessig.

Verbindliche Folge:

- file-basiertes `--resume` fuehrt nach Phase C entweder zu echter
  Wiederaufnahme oder zu einem klaren Preflight-/Manifestfehler
- ein "accepted but ignored" ist fuer den Exportpfad nach Abschluss dieser
  Phase nicht mehr Teil des Vertrags

---

## 5. Geplante Arbeitspakete

### 5.1 C1 - Export-Resume-Preflight im Runner fertigstellen

- `DataExportRunner` an den Phase-B-Checkpoint-Port anbinden
- Resume-Referenz aufloesen und Manifest laden
- semantische Kompatibilitaetspruefungen fuer Exportoptionen ausfuehren
- den derzeitigen sichtbaren Warning-Platzhalter durch echten
  Resume-/Fehlerpfad ersetzen

### 5.2 C2 - Export-Marker und Schreibpunkte definieren

- per-Tabelle-Resume-Status aus dem Manifest in den Exportpfad einhaengen
- bestaetigte Checkpoint-Schreibpunkte im Exportfluss festlegen
- Marker-Modell fuer naechsten noch nicht exportierten Bereich definieren

### 5.3 C3 - `StreamingExporter` fuer Wiederaufnahme erweitern

- Tabellenstart aus Manifest-/Resume-Status ableiten
- pro Tabelle vom bestaetigten Marker aus weiterlesen
- bereits abgeschlossene Tabellen nicht erneut voll exportieren
- Progress-/Result-Pfade um Resume-Kontext erweitern

### 5.4 C4 - Dateivertrag fuer Export-Outputs festziehen

- Resume-Verhalten fuer Single-File- und File-per-table-Output explizit
  modellieren
- Kompatibilitaet bestehender Dateien gegen Manifest pruefen
- Single-File-Resume ueber den festgelegten kontrollierten
  Fortsetzungspfad statt ueber stilles In-Place-Append umsetzen
- unpassende Zielpfade klar und frueh abweisen

### 5.5 C5 - Export-Tests und Spezifikationsabgleich

- Runner-Tests fuer Resume-Preflight, Manifestfehler und
  Kompatibilitaetsmismatches
- Streaming-Tests fuer Checkpoint-Schreibpunkte und Wiederaufnahme
- Abbruch-und-Fortsetzungs-Tests fuer Single-File und File-per-table
- Abgleich mit:
  - `docs/cli-spec.md`
  - `docs/implementation-plan-0.9.0.md`
  - `docs/ImpPlan-0.9.0-A.md`
  - `docs/ImpPlan-0.9.0-B.md`

---

## 6. Teststrategie fuer Phase C

Phase C braucht echte Resume-Verifikation im Exportpfad.

Mindestens noetige Tests:

- Runner-Preflight:
  - `--resume` ohne `--output` bleibt Exit `2`
  - unlesbare Manifest-/Checkpoint-Referenz fuehrt zu lokalem Fehler
  - semantisch inkompatible Resume-Referenz fuehrt zu Preflight-Fehler
- Export-Kompatibilitaet:
  - abweichende aufgeloeste Source-Identitaet wird erkannt
  - geaenderte Tabellenliste wird erkannt
  - geaenderter Output-Modus wird erkannt
  - geaendertes Format/Encoding/CSV-Setup wird erkannt
  - geaenderter Filter oder geaenderte `--since-*`-Werte werden erkannt
- Streaming-Resume:
  - ein abgebrochener Export wird ab dem letzten bestaetigten Stand
    fortgesetzt
  - bereits abgeschlossene Tabellen werden nicht erneut von vorn exportiert
  - leere Tabellen bleiben korrekt abgeschlossen
- Output-Vertrag:
  - Single-File-Resume folgt dem festgelegten kontrollierten
    Fortsetzungspfad
  - Resume auf inkompatiblen Ziel-Dateien scheitert klar
  - file-per-table-Zuordnung bleibt stabil
- Fortschritt/Resultat:
  - `operationId` und Resume-Kontext bleiben in Events und Endergebnis
    sichtbar
  - stderr-nahe Summary-Pfade verlieren keine Kernstatistiken

Explizit nicht Ziel von Phase C:

- Import-Resume
- Signal-/Interrupt-Tests
- Retry unter Verbindungsunterbrechungen

---

## 7. Datei- und Codebasis-Betroffenheit

Sicher betroffen:

- `docs/implementation-plan-0.9.0.md`
- `docs/ImpPlan-0.9.0-A.md`
- `docs/ImpPlan-0.9.0-B.md`
- `docs/ImpPlan-0.9.0-C.md`
- `docs/cli-spec.md`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ExportResult.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ExportOutput.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportHelpers.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt`

Mit hoher Wahrscheinlichkeit betroffen:

- die in Phase B eingefuehrten Checkpoint-Port-/Manifesttypen
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingExporterTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/output/ProgressRendererTest.kt`

---

## 8. Risiken und offene Punkte

### 8.1 Export-Marker ohne ausreichend deterministische Quelle

Der groesste fachliche Risikopunkt ist der eigentliche Export-Marker. Wenn
ein Treiber keinen ausreichend deterministischen Wiederaufnahmeanker liefern
kann, darf Phase C hier keinen scheinbar belastbaren Resume-Vertrag
versprechen.

### 8.2 Single-File-Resume ist heikler als File-per-table

Bei einer Datei pro Tabelle ist die Zuordnung vergleichsweise klar. Ein
Single-File-Resume ist deutlich sensibler, weil Datencontainer, Writer-Header
und Dateikonsistenz sauber zusammenpassen muessen. Der Teilplan legt deshalb
bewusst keinen In-Place-Append-Vertrag fest, sondern einen kontrollierten
Fortsetzungspfad ueber das Manifest/den Store.

### 8.3 Auto-Discovery gegen manifestierte Tabellenmenge

Resume gegen eine dynamisch neu erkannte Tabellenliste ist riskant. Phase C
muss das Manifest als kanonische Laufreferenz behandeln, sonst wird der
Exportpfad nondeterministisch.

### 8.4 Teilweise geschriebene Output-Dateien

Auch mit atomarem Manifest-Schreiben bleibt die Frage kritisch, wie mit
bereits begonnenen, aber nicht sauber abgeschlossenen Output-Dateien
umgegangen wird. Der Dateivertrag muss hier strenger sein als ein blosses
"Datei existiert".

### 8.5 Resume-Warnpfad darf nicht versehentlich bestehen bleiben

Der aktuelle Repo-Stand akzeptiert `--resume` bereits sichtbar, fuehrt den
Lauf aber noch von vorn aus. Wenn Phase C diese Zwischenstufe nicht sauber
ablost, bleibt ein irrefuehrender Nutzervertrag bestehen.

---

## 9. Entscheidungsempfehlung

Phase C sollte den Exportpfad als ersten echten Resume-Datenpfad fertigziehen.

Das ist sinnvoll, weil:

- die CLI-Oberflaeche fuer Export-Resume bereits sichtbar ist
- der Exportpfad im Vergleich zum Import die einfachere erste
  Wiederaufnahmeoberflaeche bietet
- zentrale Fragen wie Marker, Dateivertrag und Manifest-Kompatibilitaet hier
  zuerst konkret und testbar entschieden werden koennen

Damit schafft Phase C den praktischen Nachweis, dass der 0.9.0-Unterbau aus
Phase B tatsaechlich einen echten Resilienzpfad traegt, statt nur neue
DTOs und Config-Felder einzufuehren.
