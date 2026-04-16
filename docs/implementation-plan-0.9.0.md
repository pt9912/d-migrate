# Implementierungsplan: Milestone 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.9.0. Es dient als laufend gepflegte Spezifikation und
> Review-Grundlage waehrend der Umsetzung.
>
> Status: Draft (2026-04-16)
> Referenzen: `docs/roadmap.md` Milestone 0.9.0, `docs/lastenheft-d-migrate.md`
> LN-012 und LF-006, `docs/design.md` Abschnitte 3.2, 8.2 und 9.2,
> `docs/architecture.md` (`CheckpointStore`, `I18nConfig`),
> `docs/cli-spec.md` globale Flags / Exit-Codes,
> `docs/connection-config-spec.md` (`pipeline.checkpoint.*`),
> `docs/implementation-plan-0.8.0.md` als I18n-Vorstufe.

---

## 1. Ziel

Milestone 0.9.0 schliesst zwei bewusst offen gelassene Beta-Luecken:

- langlaeufige `data export`- und `data import`-Operationen werden
  unterbrechbar und ueber Checkpoints wieder aufsetzbar
- die in 0.8.0 eingefuehrte I18n-Runtime wird ueber `--lang` produktiv und
  fuer Nutzer explizit steuerbar

Der Milestone bleibt dabei absichtlich klein:

- Fokus auf Code- und Vertragsreife, nicht auf vollstaendige Handbuecher
- keine Vermischung mit spaeteren 1.0.0-Themen wie atomaren
  Checkpoint-Rollbacks, Parallelisierung oder partition-aware Resume
- keine Ruecknahme der 0.9.5-Entscheidung, Dokumentation und Pilot-QA
  separat zu schneiden

0.9.0 ist damit der technische Beta-Cut fuer Resilienz und fuer die
vollstaendig benutzbare i18n-CLI.

---

## 2. Ausgangslage

Stand im aktuellen Repo:

- Die 0.8.0-I18n-Basis ist vorhanden:
  - ResourceBundles fuer Englisch und Deutsch
  - `I18nSettingsResolver` fuer Env-/Config-/System-Aufloesung
  - lokalisierte Plain-Text-Ausgaben und Fehlertexte
- Das Root-CLI deklariert `--lang` bereits, lehnt jede Nutzung in
  `Main.kt` aber bewusst mit Exit 7 ab und verweist auf 0.9.0.
- Der Streaming-Pfad fuer Export und Import ist heute bewusst
  single-threaded und checkpoint-los:
  - `PipelineConfig` kennt nur `chunkSize`
  - `StreamingExporter` und `StreamingImporter` besitzen keinen
    Resume- oder Retry-Vertrag
- `docs/design.md` und `docs/architecture.md` enthalten bereits ein
  konzeptionelles Checkpoint-Modell (`MigrationCheckpoint`,
  `CheckpointStore`), das im Produktionscode noch nicht konkretisiert ist.
- `docs/connection-config-spec.md` dokumentiert `pipeline.checkpoint.*`
  bereits vorlaufend, obwohl diese Konfiguration zur Laufzeit noch nicht
  wirksam ist.
- Fortschritts- und Ergebnisobjekte (`ProgressEvent`, `ExportResult`,
  `ImportResult`) transportieren derzeit weder `operationId` noch
  Resume-Metadaten.

Die Hauptaufgabe von 0.9.0 ist daher keine Greenfield-Implementierung,
sondern die Konsolidierung mehrerer halbfertiger Vertraege zu einer
belastbaren Beta-Laufzeit.

---

## 3. Scope

### 3.1 In Scope fuer 0.9.0

- produktiver CLI-Vertrag fuer `--lang` als oberste Sprach-Override-Quelle
- Checkpoint/Resume fuer `d-migrate data export`
- Checkpoint/Resume fuer `d-migrate data import`
- lokaler, dateibasierter Checkpoint-Store fuer Beta-Deployments
- deterministische Resume-Validierung ueber persistierte
  Operations-Metadaten und Kompatibilitaetspruefungen
- minimaler CLI-Vertrag fuer Wiederaufnahme:
  - Checkpoint-Verzeichnis
  - Resume-Referenz
  - klare Fehlermeldung bei inkompatibler Wiederaufnahme
- Tests fuer unterbrochene und erfolgreich fortgesetzte Export-/Import-Laeufe
- gezielte Aktualisierung der normativen Spezifikationsdokumente, die
  durch den neuen Vertrag direkt betroffen sind

### 3.2 Bewusst nicht Teil von 0.9.0

- `data transfer`-Checkpointing oder Resume fuer DB-zu-DB-Streaming
- atomare Rollbacks auf Checkpoint-Ebene (`LN-013`, bleibt 1.0.0-RC)
- Parallelisierung, partition-aware Resume oder Retry-Orchestrierung
- vollstaendige Handbuecher, Administrationsleitfaeden und Pilot-QA
  (bleiben 0.9.5)
- Lokalisierung strukturierter JSON-/YAML-Ausgaben
- neue Sprachen jenseits des 0.8.0-Bundle-Sets
- Resume fuer `stdout`-Export oder `stdin`-Import

Begruendung:

Die Roadmap definiert 0.9.0 explizit als kleinen Code-Milestone fuer
Resilienz und finale `--lang`-Freischaltung. Alles, was dazu nicht direkt
noetig ist, wuerde den Beta-Cut unnoetig verbreitern.

Der hier gewaehlte dateibasierte Resume-Zuschnitt ist dabei enger als der
allgemeine Roadmap-Satz zu "unterbrechbaren/wiederaufsetzbaren" Operationen
und muss bei Beibehaltung deshalb in Roadmap und CLI-Spec explizit
nachgezogen werden.

---

## 4. Leitentscheidungen

### 4.1 `--lang` wird in 0.9.0 zur hoechsten Sprach-Prioritaet

Verbindliche Entscheidung:

- `--lang` aktiviert in 0.9.0 die bereits vorhandene I18n-Runtime
- Prioritaetskette fuer die effektive Locale:
  1. `--lang`
  2. `D_MIGRATE_LANG`
  3. `LC_ALL`
  4. `LANG`
  5. `i18n.default_locale`
  6. System-Locale
  7. Fallback `en`

Zusatzregeln:

- 0.9.0 garantiert mindestens `de` und `en`
- tags wie `de-DE` oder `en-US` duerfen auf die unterstuetzte Basissprache
  kanonisiert werden
- nicht unterstuetzte Werte in explizitem `--lang` fuehren zu einem klaren
  lokalen CLI-Fehler
- Env-/Config-/System-Locale-Pfade behalten den 0.8.0-Vertrag:
  syntaktisch gueltige generische Locales duerfen weiter aufgeloest werden;
  wenn dafuer kein spezifisches Bundle existiert, faellt die Message-Aufloesung
  weiter auf das Root-/Englisch-Bundle zurueck

### 4.2 Strukturierte Ausgaben bleiben locale-unabhaengig

Verbindliche Entscheidung:

- `--lang` beeinflusst nur menschenlesbare Plain-Text-/stderr-Ausgaben
- JSON-/YAML-Schluessel, Codes, Enum-Werte und maschinenlesbare Reports
  bleiben stabil und englisch

0.9.0 erweitert also den Nutzerzugriff auf die 0.8.0-I18n-Basis, aber
veraendert keinen API-aehnlichen Ausgabevertrag.

### 4.3 Resume ist ein expliziter Nutzerpfad, kein stiller Automatismus

Verbindliche Entscheidung:

- Checkpoints werden waehrend langer Operationen automatisch geschrieben,
  sofern sie aktiviert sind
- eine Wiederaufnahme erfolgt bewusst ueber eine explizite Resume-Referenz,
  nicht ueber heimliches Auto-Resume
- der CLI-Vertrag bekommt dafuer einen klaren Wiederaufnahme-Einstieg
  fuer `data export` und `data import`

Finaler CLI-Vertrag fuer 0.9.0 (festgezogen in Phase A,
`docs/ImpPlan-0.9.0-A.md` §4.3 / §3.1; Auflösungs-Semantik prazisiert
in Phase C.1, `docs/ImpPlan-0.9.0-C1.md` §5.2):

- `--resume <checkpoint-id|path>` startet eine Wiederaufnahme. Der Wert
  wird in dieser Reihenfolge aufgelöst:
  1. Pfad-Kandidat: enthaelt `/` oder endet auf `.checkpoint.yaml`. Der
     Pfad MUSS innerhalb des effektiven Checkpoint-Verzeichnisses
     (`--checkpoint-dir` oder `pipeline.checkpoint.directory`) liegen;
     der Dateiname (ohne `.checkpoint.yaml`-Suffix) wird zur
     `operationId` und ueber den `CheckpointStore` geladen.
  2. Sonst wird der Wert direkt als `operationId` interpretiert und
     gegen das effektive Checkpoint-Verzeichnis geladen.
- Pfade ausserhalb des effektiven Checkpoint-Verzeichnisses werden mit
  Exit 7 abgelehnt. Damit bleibt der `CheckpointStore`-Port (der per
  `operationId` arbeitet) die einzige Persistenzkante; externe Manifeste
  muessen vorher in das Verzeichnis kopiert werden.
- `--checkpoint-dir <path>` uebersteuert `pipeline.checkpoint.directory`
  aus der Config

Die Flag-Benennung wurde von der fruehen Arbeitsannahme in Phase A
verbindlich uebernommen und ist ab 0.9.0 stabil. Phase A hat zusaetzlich
eingebaut:

- stdout-Export bzw. stdin-Import in Kombination mit `--resume` ist
  unzulaessig (Exit 2)
- `--lang` wird zum aktiven Root-CLI-Override mit strenger
  Produktsprachen-Validierung (`de`/`en` plus kanonische Varianten;
  unsupported → Exit 2)

### 4.4 Checkpoint-Trigger folgen LN-012: rows oder Zeit

Verbindliche Entscheidung:

- Checkpoints werden spaetestens nach `10_000` verarbeiteten Zeilen oder
  nach `5` Minuten geschrieben
- die bestehende Spezifikationsidee `pipeline.checkpoint.interval`
  wird als row-basierter Trigger produktiv
- zusaetzlich braucht 0.9.0 einen Zeit-Trigger im Runtime-Vertrag, damit
  LN-012 vollstaendig abgedeckt ist

Konsequenz:

- `docs/connection-config-spec.md` muss von der vorlaufenden Skizze zu einem
  realen 0.9.0-Vertrag geschoben werden
- `PipelineConfig` wird um Checkpoint-Konfiguration erweitert

### 4.5 Resume startet immer am letzten bestaetigten Checkpoint

Verbindliche Entscheidung:

- Resume erfolgt nie "mitten im Chunk"
- ein Checkpoint wird erst dann als gueltig persistiert, wenn der bis dahin
  verarbeitete Zustand fachlich abgeschlossen ist
- Export und Import garantieren in 0.9.0 damit eine Wiederaufnahme ab dem
  letzten bestaetigten Stand, nicht eine bytegenaue Mid-Chunk-Fortsetzung

Fuer den Import ist das besonders wichtig:

- Checkpoints werden nur nach erfolgreich abgeschlossenem Chunk-Write bzw.
  nach sauberem Tabellenabschluss fortgeschrieben
- 0.9.0 verspricht keine atomischen Rollbacks auf fruehere Checkpoints

### 4.6 Resume ist an Kompatibilitaetspruefungen gebunden

Verbindliche Entscheidung:

Jeder Checkpoint muss genug Metadaten tragen, um Fehlbenutzung sicher zu
erkennen, mindestens:

- Operationstyp (`export` oder `import`)
- betroffene Tabellen bzw. Input-Slices
- Quelle/Ziel/Fingerprint des Laufes
- Format und relevante Formatoptionen
- `chunkSize`
- relevante Filter- und Inkrement-Parameter
- fuer Import zusaetzlich konflikt- und triggerrelevante Optionen

Wiederaufnahme mit abweichender Semantik wird nicht "best effort"
fortgesetzt, sondern klar abgelehnt.

### 4.7 0.9.0 bleibt im Beta-Zuschnitt zunaechst bei dateibasiertem Resume

Verbindliche Entscheidung:

- Resume wird in 0.9.0 nur fuer dateibasierte Ein-/Ausgabepfade garantiert
- `stdout`-Export und `stdin`-Import sind weiter gueltige Basisfunktionen,
  aber keine resume-faehigen Beta-Pfade
- `data transfer` bleibt unberuehrt

Das haelt den Implementierungsumfang beherrschbar und vermeidet einen
unsauber nur halb getesteten Streaming-/TTY-Sonderpfad.

Normative Folge:

- falls dieser Beta-Zuschnitt beibehalten wird, muessen `docs/roadmap.md` und
  `docs/cli-spec.md` die Einschraenkung fuer `stdout`-Export und `stdin`-
  Import explizit als 0.9.0-Limitation mittragen
- falls Roadmap und CLI-Spec bewusst beim allgemeineren Resume-Wortlaut bleiben
  sollen, muss dieser Teilplan vor Umsetzung wieder aufgeweitet werden

---

## 5. Zielarchitektur

### 5.1 Finale I18n-Aufloesung im Root-CLI

Das Root-CLI aktiviert `--lang` als produktiven Override und gibt die
effektive Locale ueber `CliContext` an alle Commands weiter. Die 0.8.0-
Bundles und Formatter bleiben die fachliche Basis; 0.9.0 vervollstaendigt
den Einstiegspfad, nicht die Bundle-Architektur selbst.

### 5.2 Checkpoint-Domaene als klarer Port-Vertrag

Zwischen CLI/Application und Streaming-Layer wird ein expliziter
Checkpoint-Vertrag benoetigt, voraussichtlich mit:

- einer serialisierbaren Checkpoint-DTO pro Operation
- einem `CheckpointStore`-Port fuer Laden, Speichern, Auflisten und
  Abschliessen von Checkpoints
- Resume-Metadaten pro Tabelle bzw. Input-Slice
- einer stabilen `operationId`, die in Logs, stderr und Resultaten
  referenzierbar ist

Die vorhandene Konzeptklasse `MigrationCheckpoint` aus `docs/design.md`
ist dafuer Startpunkt, muss aber auf den tatsaechlichen Export-/Import-Pfad
zugeschnitten werden.

### 5.3 Erweiterte Pipeline-Konfiguration

`PipelineConfig` wird von einem reinen `chunkSize`-Behaelter zu einem
kleinen Laufzeitvertrag fuer resiliente Operationen erweitert, mindestens:

- `chunkSize`
- `checkpoint.enabled`
- `checkpoint.rowInterval`
- `checkpoint.maxInterval`
- `checkpoint.directory`

Retry und Parallelisierung bleiben ausserhalb des 0.9.0-Vertrags, auch wenn
die Konfigurationsspezifikation sie bereits skizziert.

### 5.4 Export-Resume ueber persistierte Positionsmarker

Der Exportpfad braucht pro Tabelle einen serialisierbaren Resume-Marker.
Der genaue Marker ist treiberabhaengig, fachlich aber immer derselbe
Vertrag:

- deterministische Verarbeitungsreihenfolge
- persistierter letzter erfolgreicher Stand
- Fortsetzung ab dem naechsten noch nicht bestaetigten Bereich

Fuer den Dateipfad bedeutet das zusaetzlich:

- Output-Ziele muessen zum Checkpoint passen
- Resume in bereits bestehende Dateien darf nur stattfinden, wenn
  Manifest und Dateiziel kompatibel sind

### 5.5 Import-Resume ueber committed Chunk-Grenzen

Der Importpfad braucht einen resume-faehigen Positionsmarker ueber
Input-Datei(en), Tabelle und verarbeitete Chunks. Die Wiederaufnahme muss
mit dem bestehenden Preflight zusammenarbeiten:

- Format-Erkennung bzw. explizites Format
- optionales Schema-Preflight
- `--on-conflict`
- `--trigger-mode`
- ggf. multi-file Import-Eingaenge

Checkpoints duerfen hier erst nach erfolgreichem Write-/Commit-Schritt
fortgeschrieben werden.

---

## 6. Geplante Arbeitspakete

### 6.1 Phase A - CLI-Vertrag fuer Sprache und Resume

- `--lang` in `Main.kt` von "reserved" auf produktive Override-Quelle
  umstellen
- Wertvalidierung und Kanonisierung fuer unterstuetzte Sprachcodes
  definieren
- Resume-Einstieg fuer `data export` und `data import` festlegen
  (Resume-Referenz, optionales Checkpoint-Verzeichnis)
- Exit-Code- und Fehlermeldungsvertrag fuer inkompatible Resume-Versuche
  definieren

Ergebnis:

Ein nachvollziehbarer, dokumentierbarer CLI-Vertrag fuer Sprache und
Wiederaufnahme, ohne implizite Magie und ohne den 0.8.0-Fallback fuer
nicht-explizite Locale-Quellen unnoetig zu brechen.

### 6.2 Phase B - Checkpoint-Port, Manifest und Konfiguration

- `PipelineConfig` um Checkpoint-Konfiguration erweitern
- Port/Adapter fuer dateibasierten Checkpoint-Store einfuehren
- persistiertes Manifest fuer Export-/Import-Laeufe definieren
- `operationId` und Resume-Metadaten in Resultat-/Progress-Pfaden verankern
- bestehende vorlaufende Spezifikation in `connection-config-spec.md`
  auf den realen 0.9.0-Umfang einengen

Ergebnis:

Ein kleiner, stabiler Kernvertrag, auf dem Export und Import gleichartig
aufsetzen koennen.

### 6.3 Phase C - Export-Checkpoint und Resume

Phase C ist in zwei Unterphasen gegliedert (siehe Ueberplan
`docs/ImpPlan-0.9.0-C.md`):

**Phase C.1 — Export-Resume tabellengranular**
(`docs/ImpPlan-0.9.0-C1.md`):

- `DataExportRunner` um Resume-Preflight und Checkpoint-Initialisierung
  erweitern (Manifest laden, Options-Fingerprint pruefen, Manifest-
  Lifecycle)
- `StreamingExporter` ueberspringt Tabellen mit Status `COMPLETED`;
  unvollstaendige Tabellen werden von vorn exportiert
- `ExportExecutor`-Seam fuer `operationId` schliessen (aus Phase B
  offene Kante: die UUID landet live im `RunStarted`-Event)
- Runner-Warning „run from scratch" wird durch echten Runtime- oder
  Fehlerpfad ersetzt
- Kompatibilitaetspruefungen fuer:
  - Quell-Fingerprint / effektiv aufgeloeste Source-Identitaet
  - Tabellenliste
  - Output-Modus (single-file vs file-per-table) und Zielpfade
  - Format / Encoding / CSV-Optionen
  - `--filter` / `--since-column` / `--since`
- klare Ablehnung nicht resume-faehiger Faelle:
  - `stdout`
  - inkompatible Zielpfade
  - nicht validierbare Checkpoints (`schemaVersion`-Mismatch, unlesbare
    Datei)
  - `optionsFingerprint`-Mismatch

**Phase C.2 — Mid-Table-Resume via Composite-Marker**
(`docs/ImpPlan-0.9.0-C2.md`):

- `DataReader`-Port (`streamTable(...)`) um eine Composite-Marker-
  Ueberladung erweitern: `ResumeMarker(markerColumn, lastMarkerValue,
  tieBreakerColumns, lastTieBreakerValues)`. Ohne Tie-Breaker (typisch
  Primaerschluessel) wuerde reines `>=` auf der Marker-Spalte an
  Chunk-Grenzen Duplikate erzeugen (`updated_at` mit identischen
  Timestamps); `>` wuerde dagegen Rows verlieren. Der Composite-
  Vergleich `(markerColumn, tieBreakers) > (lastMarkerValue,
  lastTieBreakerValues)` loest das strikt.
- `StreamingExporter` schreibt nach jedem fachlich bestaetigten Chunk
  den Composite-`lastMarker` ins Manifest fort
- Runner setzt eine unvollstaendige Tabelle mit Composite-`lastMarker`
  ab dem Marker fort; ohne `--since-column` **oder** ohne erkennbaren
  Primaerschluessel bleibt der C.1-Vertrag (Tabelle neu exportieren,
  mit sichtbarem stderr-Hinweis; **kein** harter Preflight-Abbruch).
  Nur der Fall „Manifest hat `lastMarker`, aber aktueller Request hat
  kein `--since-column`" ist ein echter Preflight-Fehler (Exit 3).
- Single-File-Fortsetzung ausschliesslich fuer **Single-Table-Single-File**
  via format-spezifischem, kontrolliertem Fortsetzungspfad
  (JSON/YAML-Rebuild ueber eine vom `CheckpointStore` verwaltete
  Zwischendatei; CSV-Single-Table-Single-File ebenfalls via Zwischendatei
  mit einmaligem Header). Multi-Table-Single-File wird bereits im
  Basispfad mit Exit 2 abgelehnt (`StreamingExporter.require`, siehe
  `docs/cli-spec.md`) und hat daher keinen gueltigen Ausgangslauf, der
  wiederaufgenommen werden muesste.

Ergebnis:

Dateibasierte Export-Laeufe koennen nach Abbruch kontrolliert ab dem
letzten gueltigen Checkpoint fortgesetzt werden — in C.1 tabellengranular,
in C.2 zusaetzlich innerhalb grosser Einzeltabellen.

### 6.4 Phase D - Import-Checkpoint und Resume

- `DataImportRunner` um Resume-Preflight und Checkpoint-Initialisierung
  erweitern
- `StreamingImporter` um committed Chunk-Checkpoints und Resume-Pfade
  erweitern
- Kompatibilitaetspruefungen fuer:
  - Input-Datei(en)
  - Format / Encoding
  - Tabellenzuordnung
  - `--on-conflict`, `--trigger-mode`, `--truncate`
- klare Ablehnung nicht resume-faehiger Faelle:
  - `stdin`
  - semantisch abweichende Optionen zum Checkpoint

Ergebnis:

Dateibasierte Import-Laeufe koennen nach Abbruch kontrolliert ab dem
letzten bestaetigten Fortschritt fortgesetzt werden.

### 6.5 Phase E - Tests und normative Doku-Synchronisierung

- Unit-Tests fuer Locale-Prioritaet, `--lang`-Validierung und den erhaltenen
  Env-/Config-/System-Fallback
- Runner-Tests fuer Resume-Preflight, Inkompatibilitaetsfehler und
  korrekte Checkpoint-Verkabelung
- Streaming-Tests fuer Checkpoint-Schreibpunkte und Wiederaufnahme
- mindestens ein simulierter Abbruch-mit-Resume-Test fuer Export und Import
- Aktualisierung der normativen Doku:
  - `docs/cli-spec.md`
  - `docs/connection-config-spec.md`
  - `docs/roadmap.md` fuer eine ggf. explizit dateibasierte 0.9.0-
    Resume-Limitation
  - gezielte Verweise in `docs/design.md`, `docs/architecture.md`,
    `docs/guide.md`

Nicht Teil dieser Phase:

- vollstaendige Anwenderhandbuecher
- Pilot-Testmatrix; fuer spaetere reale Datenbasis siehe
  `docs/test-database-candidates.md`
- 0.9.5-Dokumentationspaket

---

## 7. Datei- und Codebasis-Betroffenheit

Sicher betroffen:

- `docs/roadmap.md`
- `docs/cli-spec.md`
- `docs/connection-config-spec.md`
- `docs/design.md`
- `docs/architecture.md`
- `docs/guide.md`
- `docs/implementation-plan-0.9.0.md`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/I18nSettingsResolver.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/PipelineConfig.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ExportResult.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ImportResult.kt`
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt`
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingImporter.kt`

Wahrscheinlich neu:

- Checkpoint-Port-/Modelltypen unter `hexagon/ports/...`
- dateibasierter Checkpoint-Store unter einem getriebenen Adapter
- zugehoerige Testdateien fuer Manifest- und Resume-Verhalten

Sicher betroffene Tests:

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliI18nContextTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/config/I18nSettingsResolverTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingExporterTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterSqliteTest.kt`

---

## 8. Risiken und offene Punkte

### 8.1 Deterministische Resume-Marker

Offen ist die genaue technische Form des Export-Resume-Markers pro Treiber.
Der Plan verlangt deterministische Wiederaufnahme; falls ein Pfad das nicht
sauber garantieren kann, muss 0.9.0 dort lieber klar ablehnen als einen
unsauberen Resume-Modus zu versprechen.

### 8.2 Output-/Input-Kompatibilitaet

Resume ist nur dann sicher, wenn Output- bzw. Input-Pfade, Formatoptionen
und Tabellenzuordnung unveraendert oder nachweisbar kompatibel sind.
Das Manifest braucht dafuer genug Fingerprint-Information.

### 8.3 Import-Semantik ohne LN-013

Da atomare Rollbacks auf Checkpoint-Ebene erst in 1.0.0-RC kommen, muss die
Import-Semantik fuer 0.9.0 bewusst auf "fortsetzen ab letztem bestaetigten
Checkpoint" eingegrenzt werden. Jede Formulierung, die bereits Rollback-
Sicherheit suggeriert, waere fuer 0.9.0 zu stark.

### 8.4 Umfang von "vollstaendige i18n-CLI"

Fachlich meint die Roadmap fuer 0.9.0 die vollstaendig benutzbare
Sprachauswahl auf Basis der 0.8.0-I18n-Runtime. Das ist nicht automatisch
gleichbedeutend mit einer kompletten Uebersetzung saemtlicher Clikt-
internen Hilfe- und Parsertexte. Dieser Punkt muss in CLI-Spec und Guide
bewusst sauber formuliert werden.

### 8.5 Unterbrechungssignale und Exit-Codes

Checkpoint-Erzeugung bei SIGINT/SIGTERM, sauberer Exit-Code `130` und
brauchbare Resume-Hinweise muessen zusammengedacht werden. Ein Checkpoint,
der nur auf "normalen" Fehlerpfaden entsteht, waere fuer LN-012 zu schwach.

### 8.6 Roadmap-/CLI-Spec-Abgleich fuer dateibasiertes Resume

Wenn 0.9.0 Resume bewusst nur fuer dateibasierte Pfade garantiert, darf diese
Einschraenkung nicht nur in diesem Teilplan stehen.

Konsequenz:

- Roadmap und CLI-Spec muessen denselben 0.9.0-Limitierungswortlaut tragen
- bleibt dort der allgemeinere Resume-Vertrag stehen, ist der Scope vor
  Umsetzung erneut zu klaeren

---

## 9. Entscheidungsempfehlung

Milestone 0.9.0 sollte bewusst als schmaler Beta-Code-Milestone umgesetzt
werden:

- `--lang` wird final freigeschaltet und klar priorisiert
- explizites `--lang` validiert unterstuetzte Sprachen hart, ohne den
  bestehenden Env-/Config-/System-Locale-Fallback global zu brechen
- Checkpoint/Resume wird auf Export und Import begrenzt
- der Resume-Vertrag bleibt dateibasiert, explizit und deterministisch, muss
  dann aber auch in Roadmap und CLI-Spec so benannt werden
- alles darueber hinaus wandert weiterhin nicht heimlich in 0.9.0 hinein

Das schliesst die sichtbarsten Beta-Luecken und schafft einen belastbaren
Uebergang zu 0.9.5 und 1.0.0, ohne den Milestone mit spaeteren
Enterprise-Themen zu ueberladen. Falls der dateibasierte Resume-Zuschnitt
bleibt, muessen Roadmap und CLI-Spec denselben Limitierungswortlaut tragen.
