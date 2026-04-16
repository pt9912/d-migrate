# Implementierungsplan: Phase E - Tests und normative Doku-Synchronisierung

> **Milestone**: 0.9.0 - Beta: Resilienz und vollstaendige i18n-CLI
> **Phase**: E (Tests und normative Doku-Synchronisierung)
> **Status**: Planned (2026-04-16)
> **Referenz**: `docs/implementation-plan-0.9.0.md` Abschnitt 3,
> Abschnitt 4.1 bis 4.7, Abschnitt 6.5, Abschnitt 7 und Abschnitt 8;
> `docs/ImpPlan-0.9.0-A.md`; `docs/ImpPlan-0.9.0-B.md`;
> `docs/ImpPlan-0.9.0-C.md`; `docs/ImpPlan-0.9.0-D.md`;
> `docs/cli-spec.md`; `docs/connection-config-spec.md`;
> `docs/roadmap.md`; `docs/guide.md`; `docs/design.md`;
> `docs/architecture.md`

---

## 1. Ziel

Phase E schliesst den 0.9.0-Milestone nicht ueber neue Laufzeitlogik,
sondern ueber Verifikation und Vertrags-Synchronisierung ab.

Der Teilplan beantwortet bewusst die querschnittlichen Abschlussfragen:

- welche Tests die in Phase A bis D gezogenen Vertraege verbindlich absichern
- welche Resume- und I18n-Pfade mindestens simulierterweise ueber
  Abbruch/Wiederaufnahme geprueft werden muessen
- welche normativen Dokumente auf denselben 0.9.0-Wortlaut gezogen werden
  muessen
- wie veraltete "Phase B/C"-Hinweise und Platzhaltertexte aus CLI-Spec,
  Guide und Roadmap entfernt werden
- wie Architektur- und Designdoku auf den tatsaechlichen 0.9.0-Port- und
  Resume-Vertrag referenzieren sollen

Phase E liefert damit keinen weiteren Datenpfad mehr, sondern den
Abschlussrahmen, der 0.9.0 als konsistenten Beta-Milestone absichert.

Nach Phase E soll klar und testbar gelten:

- der CLI-, Resume- und I18n-Vertrag ist ueber Unit-, Runner-, Streaming-
  und mindestens einfache Resume-Szenarien abgesichert
- die normativen Dokumente sprechen denselben 0.9.0-Vertrag
- offene Platzhalter aus Phase A bis D sind aus Nutzer- und Spezifikations-
  texten entfernt

---

## 2. Ausgangslage

Aktueller Stand im Repo:

- Phase A bis D sind als Teilplaene dokumentiert und schneiden den
  Milestone bereits sauber in:
  - CLI-Vertrag
  - Checkpoint-Port und Konfiguration
  - Export-Resume
  - Import-Resume
- Die Testbasis ist heute bereits breiter als im fruehen Masterplan:
  - `CliI18nContextTest`
  - `I18nSettingsResolverTest`
  - Runner-Tests fuer Export und Import
  - Streaming-Tests fuer Export und Import
  - `PipelineConfigTest`
  - Checkpoint-/Manifest-Tests unter `hexagon/ports` und
    `adapters/driven/streaming/checkpoint`
  - Progress-/Help-/Smoke-Tests fuer sichtbare CLI-Pfade
- Gleichzeitig haengen mehrere normative Texte und Kommentare noch an
  Zwischenstaenden:
  - `docs/cli-spec.md` verwendet fuer Resume teils noch "Phase B/C"
  - `docs/guide.md` referenziert Export- und Import-Resume ebenfalls noch
    als Phase-B/C-Fortsetzung
  - `docs/roadmap.md` traegt bewusst einen Ist-Stand-Kasten aus Phase A,
    muss fuer den finalen 0.9.0-Zustand aber erneut synchronisiert werden
  - `docs/design.md` und `docs/architecture.md` enthalten vorlaufende
    Checkpoint-Modelle, die auf den tatsaechlichen Port- und
    Manifestvertrag zurueckgebunden werden muessen
- Der Worktree zeigt bereits laufende 0.9.0-Arbeit an Port, Store,
  Progress-/Result-Typen und Streaming-Layer; Phase E muss diese Aenderungen
  nicht neu erfinden, aber auf Test- und Dokumentationsreife schliessen.

Konsequenz:

- 0.9.0 braucht keine weitere Funktionsphase mehr, sondern einen
  stabilen Abschluss fuer Beweisbarkeit und Doku-Konsistenz
- ohne Phase E bleibt der technische Vertrag verteilt ueber Teilplaene,
  Tests und halbfertige Doku-Hinweise
- gerade die 0.9.0-Beta-Aussage haengt davon ab, dass Resume/I18n nicht nur
  implementiert, sondern auch normativ deckungsgleich beschrieben sind

---

## 3. Scope fuer Phase E

### 3.1 In Scope

- Unit-Tests fuer:
  - Locale-Prioritaet
  - `--lang`-Validierung
  - erhaltenen Env-/Config-/System-Fallback
- Runner-Tests fuer:
  - Resume-Preflight
  - Inkompatibilitaetsfehler
  - Exit-Code-Grenzen
  - korrekte Checkpoint-Verkabelung
- Streaming-Tests fuer:
  - Checkpoint-Schreibpunkte
  - Fortschritts-/Resultat-Kontext
  - Wiederaufnahme ab letztem bestaetigten Stand
- mindestens ein simulierter Abbruch-mit-Resume-Test fuer:
  - Export
  - Import
- Aktualisierung der normativen Doku:
  - `docs/cli-spec.md`
  - `docs/connection-config-spec.md`
  - `docs/roadmap.md`
  - `docs/guide.md`
  - gezielte Verweise in `docs/design.md` und `docs/architecture.md`
- Abgleich sichtbarer CLI-Hilfe- und Warning-Texte mit dem finalen Vertrag

### 3.2 Bewusst nicht Teil von Phase E

- vollstaendige Anwenderhandbuecher
- Pilot-Testmatrix
- 0.9.5-Dokumentationspaket
- neue Resume- oder Retry-Funktionalitaet jenseits des in Phase A bis D
  gesetzten Vertrags
- neue Produktsprachen

Praezisierung:

Phase E loest "ist 0.9.0 konsistent abgesichert und dokumentiert?", nicht
"welche spaeteren Beta-/GA-Handbuecher waeren noch wuenschenswert?".

---

## 4. Leitentscheidungen fuer Phase E

### 4.1 Phase E validiert die Vertraege aus A bis D, erfindet keine neuen

Phase E ist absichtlich eine Abschlussphase.

Verbindliche Folge:

- neue Tests und Doku-Angleichungen muessen sich an den bereits
  festgezogenen Teilplaenen A bis D orientieren
- wenn in Phase E eine Vertragsluecke sichtbar wird, muss sie explizit an
  den betreffenden Teilplan zurueckgebunden werden statt still in der
  Doku "neu entschieden" zu werden

### 4.2 Resume braucht mindestens einfache echte Wiederaufnahme-Pfade

Reine Unit- oder Port-Tests reichen fuer 0.9.0 nicht aus.

Phase E fixiert:

- mindestens ein simulierter Abbruch-mit-Resume-Test fuer Export
- mindestens ein simulierter Abbruch-mit-Resume-Test fuer Import
- diese Tests muessen den fachlichen Kernvertrag pruefen:
  - letzter bestaetigter Checkpoint bleibt erhalten
  - Wiederaufnahme setzt am naechsten offenen Stand fort
  - bereits bestaetigte Teilergebnisse werden nicht still verworfen

Damit wird die Beta-Aussage nicht nur ueber isolierte Bausteine, sondern
auch ueber wenigstens einen zusammenhaengenden Resume-Lauf abgesichert.

### 4.3 Normative Dokumente muessen denselben 0.9.0-Zeitpunkt sprechen

Phase E fixiert:

- CLI-Spec, Guide und Roadmap duerfen keine veralteten Platzhalter wie
  "Runtime folgt in Phase B/C" mehr tragen, sobald die betreffenden
  Phasen umgesetzt sind
- `docs/connection-config-spec.md` muss den produktiven 0.9.0-Vertrag fuer
  `pipeline.checkpoint.*` beschreiben, nicht nur eine Vorab-Skizze
- `docs/design.md` und `docs/architecture.md` duerfen weiterhin abstrakter
  bleiben, muessen aber auf den realen 0.9.0-Port- und Resume-Vertrag
  referenzierbar sein

Nicht zulaessig ist:

- in Teilplaenen und normativer Doku unterschiedliche Phasen- oder
  Exit-Code-Aussagen stehen zu lassen
- sichtbare CLI-Hilfetexte enger oder weiter zu formulieren als die
  Spezifikationsdokumente

### 4.4 Help-, Warning- und Progress-Texte sind Teil des Vertrags

0.9.0 macht `--lang`, `--resume` und `--checkpoint-dir` fuer Nutzer sichtbar.

Verbindliche Folge:

- Helptexte in `Main.kt`, `DataExportCommand.kt` und `DataImportCommand.kt`
  gehoeren in Phase E zur Verifikationsbasis
- Warning-Texte aus Phase A muessen entfernt oder auf den finalen
  Runtime-Vertrag gezogen werden
- sichtbare Progress-/Summary-Ausgaben muessen neuen Resume- oder
  `operationId`-Kontext nicht zwingend ausschmuecken, duerfen ihn aber
  auch nicht verlieren oder widerspruechlich darstellen

### 4.5 Die Resume-Limitation fuer stdin/stdout bleibt normativ sichtbar

Der 0.9.0-Beta-Zuschnitt ist bewusst dateibasiert.

Phase E fixiert:

- `stdout`-Export plus `--resume` und `stdin`-Import plus `--resume`
  bleiben klar als Exit-2-Faelle dokumentiert
- Roadmap, CLI-Spec und Guide tragen diese Einschraenkung konsistent mit
- `data transfer` bleibt in 0.9.0 explizit ausserhalb des Resume-Vertrags

### 4.6 Checkpoint-Doku muss den produktiven Namens- und Prioritaetsvertrag tragen

Phase E schliesst die dokumentarische Seite des Phase-B-Vertrags ab.

Verbindliche Folge:

- `pipeline.checkpoint.interval` bleibt in der Config-Spec klar als
  row-basierter Trigger beschrieben
- der Zeit-Trigger (`pipeline.checkpoint.max_interval`) ist als eigener
  0.9.0-Key eindeutig dokumentiert
- die Prioritaet `CLI > Config > Runtime-Default` fuer `--checkpoint-dir`
  und verwandte Checkpoint-Werte muss in CLI-Spec und Config-Spec
  widerspruchsfrei lesbar sein

---

## 5. Geplante Arbeitspakete

### 5.1 E1 - Testmatrix aus den Teilplaenen A bis D konsolidieren

- offene Testforderungen aus A bis D in eine 0.9.0-Abschlussliste ziehen
- Ueberschneidungen zwischen Unit-, Runner-, Streaming- und
  Resume-Szenarien bereinigen
- sicherstellen, dass Phase-E-Checks nicht nur neue, sondern auch bereits
  vorhandene 0.9.0-Testdateien sinnvoll einbeziehen

### 5.2 E2 - Resume-End-to-End-nahe Szenarien absichern

- mindestens einen Export-Abbruch-mit-Resume-Lauf simulieren
- mindestens einen Import-Abbruch-mit-Resume-Lauf simulieren
- Checkpoint-Store, Manifest, Runner-Preflight und Streaming-Pfad in diesen
  Szenarien gemeinsam verifizieren

### 5.3 E3 - Sichtbare CLI-Vertraege pruefen

- `--lang`-Prioritaet und unsupported-Locale-Verhalten erneut gegen die
  Root-CLI absichern
- Help-/Smoke-Tests fuer `data export` und `data import` auf finale
  Resume-Texte abstimmen
- veraltete Warning-Pfade aus Phase A entfernen oder in Tests gezielt als
  nicht mehr zulaessig markieren

### 5.4 E4 - Normative Dokumente synchronisieren

- `docs/cli-spec.md` auf den finalen 0.9.0-Resume- und I18n-Vertrag ziehen
- `docs/connection-config-spec.md` fuer Checkpoint-Namen, Trigger und
  Prioritaeten vervollstaendigen
- `docs/roadmap.md` vom Phase-A-Ist-Stand auf den finalen 0.9.0-Stand
  nachziehen
- `docs/guide.md` fuer sichtbare Nutzerpfade und 0.9.0-Limitationen
  aktualisieren
- Referenzen in `docs/design.md` und `docs/architecture.md` gezielt an den
  produktiven Port-/Manifestvertrag rueckbinden

### 5.5 E5 - Abschlussabgleich gegen Teilplaene und Masterplan

- pruefen, dass Teilplaene A bis D und `docs/implementation-plan-0.9.0.md`
  denselben Status- und Phasenwortlaut tragen
- offene "Phase B/C"- bzw. aehnliche Zwischenstandsformulierungen
  repo-weit bereinigen, soweit sie normativen oder sichtbaren Charakter haben

---

## 6. Teststrategie fuer Phase E

Phase E prueft keine neue Einzelfunktion, sondern die Vollstaendigkeit des
0.9.0-Vertrags. Die Teststrategie ist deshalb bewusst mehrschichtig.

### 6.1 I18n- und CLI-Vertrag

- `CliI18nContextTest` fuer aktives `--lang`
- `I18nSettingsResolverTest` fuer Prioritaetskette und erhaltenen
  Root-Bundle-Fallback ausserhalb des expliziten CLI-Flags
- Help-/Smoke-Tests fuer `data import`, `data export` und Root-CLI
- keine sichtbaren "accepted but ignored"-Resume-Warnings mehr, sobald
  Export/Import produktiv resume-faehig sind

### 6.2 Resume-Preflight und Runner-Verkabelung

- `DataExportRunnerTest` fuer:
  - Resume-Preflight
  - stdout-Limitation bei `--resume`
  - inkompatible Referenzen
  - Exit `3` versus Exit `7`
- `DataImportRunnerTest` fuer:
  - Resume-Preflight
  - stdin-Limitation bei `--resume`
  - semantische Inkompatibilitaeten
  - Manifest-/Checkpoint-Fehler

### 6.3 Streaming- und Checkpoint-Verhalten

- `StreamingExporterTest` fuer Checkpoint-Schreibpunkte und Wiederaufnahme
- `StreamingImporterTest` und `StreamingImporterSqliteTest` fuer committed
  Chunk-Grenzen, `failedFinish`, `--on-error` und Resume-Fortsetzung
- `PipelineConfigTest` fuer produktive Checkpoint-Defaults und Merges
- Checkpoint-/Manifest-Tests fuer:
  - Portmodell
  - Dateistore
  - Serialisierung und Versionsvertrag

### 6.4 Mindestens einfache Resume-Szenarien

- ein simulierter Export-Abbruch mit anschliessender Wiederaufnahme
- ein simulierter Import-Abbruch mit anschliessender Wiederaufnahme
- in beiden Faellen: bestaetigte Teilergebnisse bleiben erhalten, nur der
  offene Rest wird fortgesetzt

### 6.5 Doku- und Vertragsabgleich

- `docs/cli-spec.md`, `docs/guide.md` und `docs/roadmap.md` widersprechen
  den Teilplaenen nicht mehr
- `docs/connection-config-spec.md` beschreibt den produktiven 0.9.0-
  Checkpoint-Vertrag klar
- `docs/design.md` und `docs/architecture.md` verweisen nicht mehr nur auf
  Konzeptklassen, sondern sind zum realen Port-/Manifestvertrag ruecklesbar

---

## 7. Datei- und Codebasis-Betroffenheit

Sicher betroffen:

- `docs/ImpPlan-0.9.0-E.md`
- `docs/implementation-plan-0.9.0.md`
- `docs/cli-spec.md`
- `docs/connection-config-spec.md`
- `docs/roadmap.md`
- `docs/guide.md`
- `docs/design.md`
- `docs/architecture.md`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/I18nSettingsResolver.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/PipelineConfig.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ExportResult.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ImportResult.kt`
- Checkpoint-Port-/Modelltypen unter `hexagon/ports/.../checkpoint`
- dateibasierter Checkpoint-Store und Resume-Adapter im getriebenen
  Streaming-Layer

Sicher betroffene Tests:

- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliI18nContextTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/config/I18nSettingsResolverTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliHelpAndBootstrapTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataExportTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataImportSmokeTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataExportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/DataImportRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/output/ProgressRendererTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/PipelineConfigTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingExporterTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/StreamingImporterSqliteTest.kt`
- `adapters/driven/streaming/src/test/kotlin/dev/dmigrate/streaming/checkpoint/FileCheckpointStoreTest.kt`
- `hexagon/ports/src/test/kotlin/dev/dmigrate/streaming/checkpoint/CheckpointManifestTest.kt`

Moeglicherweise betroffen:

- Formatter-/Summary-Pfade, sofern `operationId` oder Resume-Kontext in
  sichtbaren Ausgaben nachgezogen wird

---

## 8. Risiken und offene Punkte

- Wenn Phase C oder D noch offene Vertragskanten haben, kann Phase E nur
  begrenzt "synchronisieren"; in diesem Fall muss die Luecke explizit in den
  betreffenden Teilplan zurueckgespielt werden.
- Resume-Tests koennen leicht nur happy-path-lastig werden. Fuer 0.9.0 sind
  die Preflight- und Inkompatibilitaetsfaelle mindestens ebenso wichtig wie
  der erfolgreiche Wiederanlauf.
- Architektur- und Designdoku duerfen nicht in Detailkopien der
  Spezifikation kippen; Phase E muss dort gezielt Rueckverweise setzen,
  nicht den gesamten CLI-Vertrag doppelt pflegen.
- Der Worktree zeigt bereits laufende 0.9.0-Implementierung. Phase E muss
  deshalb sorgfaeltig zwischen "bereits produktiv", "im Teilplan fixiert"
  und "noch Zwischenstand" unterscheiden.

---

## 9. Entscheidungsempfehlung

Phase E sollte als Abschlussphase fuer 0.9.0 genau diesen Zuschnitt haben:

- Verifikation der Vertraege aus A bis D statt neuer Funktionsausweitung
- mindestens einfache echte Resume-Szenarien fuer Export und Import
- repo-weite Bereinigung veralteter Zwischenstandsformulierungen in
  normativen und sichtbaren Texten
- klare Trennung zwischen 0.9.0-Beta-Vertrag und dem spaeteren 0.9.5-
  Dokumentationspaket

Damit wird 0.9.0 nicht nur technisch funktionsfaehig, sondern auch als
konsistenter Beta-Milestone belegbar und dokumentiert.
