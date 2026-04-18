# Implementierungsplan: Phase B - Zerlegung der grossen Orchestrierungs- und Dialekt-Klassen

> **Milestone**: 0.9.1 - Library-Refactor und Integrationsschnitt
> **Phase**: B (Zerlegung der grossen Orchestrierungs- und Dialekt-Klassen)
> **Status**: Draft (2026-04-17)
> **Referenz**: `docs/implementation-plan-0.9.1.md` Abschnitt 1 bis 5,
> Abschnitt 6.2, Abschnitt 7, Abschnitt 8 und Abschnitt 9;
> `docs/quality.md`; `docs/ddl-output-split-plan.md`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`;
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`;
> `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingImporter.kt`;
> `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt`;
> die drei DDL-Generatoren unter `adapters/driven/driver-*`.

---

## 1. Ziel

Phase B zieht die wartungskritischen Grossklassen aus `docs/quality.md`
in kleinere, verantwortungsschaerfere Einheiten auseinander.

Der Teilplan beantwortet bewusst zuerst die Struktur- und
Vertragsfragen:

- wie `DataImportRunner` und `DataExportRunner` von grossen
  Allzweck-Orchestratoren zu Kompositionsschichten ueber kleinere
  Dienste werden
- wie `StreamingImporter` und `StreamingExporter` entlang von
  Orchestrierung, Tabellen-Pipeline und Chunk-Handling getrennt werden
- wie `SchemaComparator` pro Objekttyp in kleinere Diff-Einheiten
  geschnitten wird
- wie die drei DDL-Generatoren pro Objektart komponiert werden, ohne
  in 0.9.1 bereits einen neuen sichtbaren DDL-Output-Vertrag
  einzufuehren
- wie heutige `-- TODO: ...`-SQL-Kommentar-Platzhalter intern durch
  strukturierte `ManualActionRequired`-Eintraege ersetzt werden, ohne
  den bestehenden Diagnosevertrag ueber `DdlResult.notes` und
  `skippedObjects` zu umgehen
- wie Dialekt-Capabilities explizit modelliert werden, damit
  Generatoren konsistent ueber Generierung, Rewrite, Skip und manuelle
  Nacharbeit entscheiden
- wie Plan-/Milestone-Historie aus Produktionscode wieder in `docs/`
  zurueckwandert

Phase B liefert damit keine neue Endnutzerfunktion, sondern einen
intern deutlich saubereren Zuschnitt fuer Orchestrierung,
Schemavergleich und DDL-Komposition.

Nach Phase B soll klar gelten:

- die grossen Hotspot-Klassen sind in kleinere Dienste mit klarer
  Verantwortung zerlegt
- die DDL-Generatoren sind intern pro Objektart komponiert
- Unsupported-/Rewrite-/Manual-Action-Faelle sind strukturiert
  modelliert statt als lose `TODO`-Kommentare verteilt
- der bestehende `schema generate`-Default bleibt fuer Nutzer
  unveraendert

---

## 2. Ausgangslage

Laut `docs/quality.md` und dem 0.9.1-Masterplan sind fuer Phase B vor
allem diese Punkte relevant:

- **Mittel**: `DataImportRunner` liegt aktuell bei 949 LOC
  (Stand 2026-04-17; das in `docs/quality.md` zitierte 887-LOC-Finding
  ist der Stand der damaligen Analyse) und vereint Validierung,
  Aufloesung, Resume, Manifest- und Ausfuehrungslogik
- **Mittel**: `DataExportRunner` ist mit rund 923 LOC aehnlich
  ueberladen
- **Mittel**: `StreamingImporter` liegt bei rund 792 LOC und mischt
  Orchestrierung, Tabellensteuerung und Chunk-Verarbeitung
- `StreamingExporter` ist mit rund 450 LOC zwar kein gleich grosser
  Hotspot, aber das direkte Export-Gegenstueck zu `StreamingImporter`
  und deshalb bewusst Teil desselben Strukturschnitts
- **Mittel**: `SchemaComparator` liegt bei rund 656 LOC; die Klasse
  besitzt bereits getrennte Methoden wie `compareTables`,
  `compareViews`, `compareSequences`, `compareFunctions`,
  `compareProcedures`, `compareTriggers` und `compareCustomTypes`,
  fuehrt diese Bausteine heute aber noch in einer Datei zusammen
- **Mittel**: die drei DDL-Generatoren bundeln Views, Functions,
  Procedures, Triggers, Rewrite-Fallbacks und Capability-Entscheidungen
  jeweils in monolithischen Dateien
- in den DDL-Generatoren existieren aktuell 18 `-- TODO: ...`-
  SQL-Kommentar-Platzhalter statt strukturierter Modellierung von
  manueller Nacharbeit

Konsequenz:

- fachliche Verantwortung ist heute zu breit verteilt
- Dialekt-Fixes sind regressionsanfaellig, weil Capability- und
  Rewrite-Logik mehrfach und dateiweise gekoppelt ist
- Import- und Export-Pfad drohen unterschiedlich zu altern, wenn
  `StreamingImporter` zerlegt, `StreamingExporter` aber implizit als
  Nebensache behandelt wird
- der spaetere optionale DDL-Output-Split (`pre-data`/`post-data`)
  laesst sich ohne internen Objekt-/Reihenfolgeschnitt nur schwer
  sauber vorbereiten; explizite Phasenattribute im DDL-Vertrag bleiben
  aber weiterhin Thema der spaeteren Split-Phase

---

## 3. Scope fuer Phase B

### 3.1 In Scope

- Zerlegung von `DataImportRunner` in kleinere Dienste, z. B.:
  - `ImportRequestValidator`
  - `ImportSourceResolver`
  - `ResumeCoordinator`
  - `ManifestCoordinator`
  - `ImportExecutionService`
- analoge Zerlegung von `DataExportRunner` in kleinere Dienste
- Zerlegung von `StreamingImporter` in:
  - Orchestrator
  - Tabellen-Pipeline
  - Chunk-Handler
- gleichartige strukturelle Zerlegung von `StreamingExporter`
- Aufspaltung von `SchemaComparator` in kleinere Vergleichseinheiten pro
  Objekttyp:
  - Tables
  - Custom Types
  - Views
  - Functions
  - Procedures
  - Triggers
  - Sequences
- interner Objektart-Schnitt fuer DDL:
  - Generator-/Helper-Schnitt fuer Custom Types, Sequences,
    Constraints und Index-Fallbacks, soweit dort heute `TODO`-Pfade
    existieren
  - weitere Hilfseinheiten, wenn fuer Tabellen/Indizes/Capabilities
    noetig
- Einfuehrung expliziter Dialekt-Capabilities ueber ein zentrales
  `DialectCapabilities`-Modell, das pro `DatabaseDialect` aufgeloest
  wird
- klare Trennung zwischen:
  - Dialektfaehigkeiten (`DialectCapabilities`)
  - per-Objekt-Entscheidungen wie fehlender Body,
    `sourceDialect`-Inkompatibilitaet oder manuelle Nacharbeit
- Ersatz der bisherigen `TODO`-Platzhalter durch strukturierte
  `ManualActionRequired`-Eintraege, die weiter in den bestehenden
  DDL-Diagnosevertrag (`notes`, `skippedObjects`) integriert werden
- Rueckbau historischer Plan-/Milestone-Kommentare aus
  Produktionscode-Dateien, die in Phase B angefasst werden; weitere
  Repo-weite Altlasten duerfen als Folgearbeit gebuendelt werden
- Tests, die die interne Refaktorierung absichern, ohne den sichtbaren
  Default-Output zu veraendern
- Kover-Coverage bleibt pro betroffenem Modul bei mindestens 90 %

### 3.2 Bewusst nicht Teil von Phase B

- sichtbarer neuer DDL-Output-Vertrag
  (`pre-data`/`post-data`, `ddl_parts`, neue JSON-Felder)
- neue Endnutzer-Features fuer Import/Export
- groesserer Port-/Modulschnitt aus Phase C bis F
- vollstaendige Neuarchitektur aller Runner in einem einzigen Sweep

Praezisierung:

Phase B loest zuerst "wie schneiden wir die wartungskritischen Klassen
intern sauber?", nicht "welchen neuen Nutzervertrag rollen wir dafuer
sofort aus?".

---

## 4. Leitentscheidungen fuer Phase B

### 4.1 Runner werden Orchestratoren ueber kleinere Dienste

Verbindliche Entscheidung:

- `DataImportRunner` und `DataExportRunner` bleiben Einstiegspunkte
- sie tragen nach Phase B aber primaer Orchestrierung und Delegation
- Validierung, Aufloesung, Resume-/Manifest-Koordination und
  Ausfuehrung wandern in kleinere Einheiten

Nicht zulaessig ist:

- eine rein kosmetische Extraktion ohne klaren
  Verantwortungszuschnitt
- neue Utility-Gottklassen, die nur den alten Runner in anderer Form
  kopieren

### 4.2 Streaming wird entlang echter Verarbeitungsphasen geschnitten

Verbindliche Entscheidung:

- `StreamingImporter` und `StreamingExporter` werden nicht beliebig nach
  Hilfsmethoden, sondern entlang von:
  - Orchestrierung
  - Tabellensteuerung
  - Chunk-Verarbeitung
  getrennt
- Resume- und Fortschritts-Invarianten duerfen dabei nicht verloren
  gehen

### 4.3 Comparator-Zuschnitt folgt den Objekttypen

Verbindliche Entscheidung:

- `SchemaComparator` wird in kleinere Diff-Einheiten pro Objekttyp
  zerlegt
- die bereits vorhandenen per-Objekttyp-Methoden
  (`compareTables`, `compareViews`, `compareSequences`,
  `compareFunctions`, `compareProcedures`, `compareTriggers`,
  `compareCustomTypes`) sind dabei der Ausgangspunkt; Phase B extrahiert
  sie in eigenstaendige Kollaborateure statt die Vergleichslogik neu zu
  entwerfen
- `customTypes` gehoeren ausdruecklich dazu und bleiben nicht als
  monolithischer Restblock im Comparator stehen
- gemeinsame Vergleichslogik darf in Hilfseinheiten liegen
- die fachliche Top-Level-Sicht bleibt aber objektebenenscharf

### 4.4 DDL-Komposition wird intern objektorientiert, nicht output-wirksam

Verbindliche Entscheidung:

- die DDL-Generatoren werden intern aus Objektart-Generatoren
  zusammengesetzt
- Objektart-Generatoren sind dabei primaer dialektspezifische Bausteine;
  gemeinsamer, dialektuebergreifender Code bleibt auf Result-Assembly,
  Capability-Auswertung und kleine Hilfen beschraenkt
- Phase B verfolgt keinen starren 3x7-Klassensatz; neue Klassen werden
  nur dort eingefuehrt, wo sie einen echten Hotspot schneiden
- 0.9.1 veraendert dadurch noch nicht den sichtbaren
  `schema generate`-Default
- der interne Schnitt darf einen spaeteren `pre-data`/`post-data`-
  Split nur insoweit vorbereiten, wie Objektarten, stabile Reihenfolge
  und Generatorgrenzen sauberer werden; explizite DDL-Phasen
  (`DdlPhase`, `ddl_parts`, phasenattribuierte Notes) sind nicht Teil
  von Phase B

### 4.5 Capability-Entscheidungen werden explizit modelliert

Verbindliche Entscheidung:

- Generatoren sollen nicht implizit oder dateiweise entscheiden, ob ein
  Objekt generierbar, rewritable, unsupported oder manual-action ist
- `DatabaseDialect` bleibt das bestehende Enum; Phase B fuehrt keinen
  zweiten Dialekttyp und keine sealed-class-Hierarchie ein
- dafuer kommt ein kleines, unveraenderliches
  `DialectCapabilities`-Modell hinzu, das zentral je
  `DatabaseDialect` hinterlegt wird
- `DialectCapabilities` beschreibt nur echte Ziel-Dialektfaehigkeiten
  und Default-Strategien, z. B. ob ein Objekttyp nativ generierbar ist,
  ob Rewrite grundsaetzlich in Frage kommt oder ob nur Skip /
  Action-Required moeglich ist
- per-Objekt-Entscheidungen wie fehlender Body, konkrete
  `sourceDialect`-Inkompatibilitaet oder Objektmetadaten bleiben in
  einer getrennten Entscheidungsschicht oberhalb der reinen
  Dialekt-Capabilities
- Generatoren konsumieren diese klar getrennten Bausteine statt loser
  `when (dialect)`-Verzweigungen ueber mehrere Dateien
- diese Capability-Modellierung ist Teil der internen
  Konsolidierungsbasis fuer alle drei Treiber

### 4.6 `TODO`-SQL-Kommentare sind kein Endzustand

Verbindliche Entscheidung:

- heutige `-- TODO: ...`-Platzhalter in Generatoren werden intern durch
  strukturierte `ManualActionRequired`-Eintraege ersetzt
- `ManualActionRequired` ist dabei kein dritter sichtbarer
  Ausgabe-/JSON-Kanal neben `DdlResult.notes` und `skippedObjects`,
  sondern ein interner Modellbaustein, der weiterhin auf diese
  bestehenden Diagnosepfade abgebildet wird
- das gilt nicht nur fuer Views/Functions/Procedures/Triggers, sondern
  fuer alle heutigen `TODO`-Pfade, insbesondere auch:
  - Custom Types
  - Sequences
  - Constraint-Fallbacks wie `EXCLUDE`
  - Index-Typ-Fallbacks
- Kommentare duerfen weiterhin "why" und Invarianten erklaeren
- Planhistorie und Milestone-Verweise gehoeren aber zurueck in `docs/`

Grobe Zielstruktur:

```kotlin
data class ManualActionRequired(
    val code: String,
    val objectType: String,
    val objectName: String,
    val reason: String,
    val hint: String? = null,
    val sourceDialect: String? = null,
)
```

Damit tragen alle heutigen `TODO`-Pfade mindestens einen stabilen Code,
einen Objekttyp, einen Objektnamen und einen Freitext-Hinweis; der
Ziel-Dialekt ergibt sich weiterhin aus `DdlGenerator.dialect`.

Abbildungsregel fuer 0.9.1:

- jedes `ManualActionRequired` muss weiterhin mindestens als bestehende
  Diagnose im DDL-Ergebnis sichtbar werden:
  - als `TransformationNote` mit `ACTION_REQUIRED`, wenn die Nacharbeit
    den Nutzerhinweis betrifft
  - zusaetzlich als `SkippedObject`, wenn fuer das Objekt bewusst keine
    ausfuehrbare DDL erzeugt wurde
- Phase B fuehrt damit keinen Parallelvertrag fuer Report, JSON oder
  stderr ein; diese bleiben an `DdlResult.notes` und
  `DdlResult.skippedObjects` gekoppelt

Kompatibilitaetsvertrag fuer 0.9.1:

- intern darf die Modellierung auf `ManualActionRequired` umgestellt
  werden
- extern muss der bestehende Default-Output fuer `schema generate`
  zunaechst stabil bleiben
- JSON-, Report- und stderr-Ausgabe bleiben ebenfalls auf Basis der
  bestehenden `notes`-/`skippedObjects`-Semantik stabil
- solange Tests und Nutzervertrag noch auf `-- TODO: ...` im DDL-Text
  beruhen, wird `ManualActionRequired` im Default-Pfad wieder in die
  bisherige Kommentarform gerendert
- ein sichtbarer Wechsel auf neue DDL-Artefakte oder strukturierte
  Zusatzfelder ist explizit nicht Teil von Phase B

---

## 5. Konkrete Arbeitspakete

Abhaengigkeiten und Reihenfolge:

1. **5.1 Runner-/Streaming-Schnitt** zuerst, damit die groessten
   Orchestrierungs-Hotspots frueh schrumpfen
2. **5.2 Comparator-Schnitt** kann parallel zu Teilen von 5.1 laufen
3. **5.3 DDL-/Capability-Schnitt** baut inhaltlich auf den
   Strukturprinzipien auf, kann aber als eigener Block umgesetzt werden
4. **5.4 Code-Kommentare/Teststabilisierung** begleitet alle anderen
   Arbeitspakete

### 5.1 Runner- und Streaming-Zerlegung

Reihenfolge: 5.1.1 → 5.1.2 → 5.1.3 → 5.1.4. Jedes Sub-Paket ist
einzeln commitbar und reviewbar. Bottom-up: Streaming zuerst, dann
die Runner, die darauf aufbauen.

#### 5.1.1 StreamingExporter zerlegen (450 LOC → 3 Einheiten)

Kleinstes Streaming-Modul, etabliert das Zerlegungsmuster fuer 5.1.2.

- **OutputDispatcher**: Stdout/SingleFile/FilePerTable-Routing,
  Stream-Wrapping (CountingOutputStream, NonClosingOutputStream)
- **TableExporter**: Per-Tabelle-Streaming, Marker-Resolution
  (Phase C.2 §5.2), Chunk-Schleife, Progress-Reporting
- **ExportResultBuilder**: TableExportSummary-Aggregation,
  Byte-Counting, Duration-Berechnung
- CountingOutputStream / NonClosingOutputStream bleiben als interne
  Wrapper in der jeweiligen Einheit
- bestehende `StreamingExporterTest` muss gruen bleiben

#### 5.1.2 StreamingImporter zerlegen (792 LOC → 4 Einheiten)

Folgt dem in 5.1.1 etablierten Muster.

- **InputResolver**: stdin/file/directory-Aufloesung, Dateisuche
  mit Filter/Order, Duplikaterkennung,
  `ResolvedTableInput`-Konstruktion
- **ChunkProcessor**: BindingPlan-Aufbau (Spalten-Matching),
  Chunk-Normalisierung, Deserialisierung (CSV-Null-Handling),
  Fehlerbehandlung (ABORT/SKIP/LOG), `ChunkDecision`
- **TableImporter**: Per-Tabelle-Orchestrierung, Resume-Offset
  (committed chunks ueberspringen), Reader/Session-Lifecycle,
  Chunk-Streaming-Schleife, finishTable mit Reseed
- **ImportResultBuilder**: TableImportSummary-Aggregation,
  Row-Counts, Chunk-Failures, Gesamtstatistik
- Resume- und Fortschrittsinvarianten ueber Tests absichern
- bestehende `StreamingImporterTest` + `SqliteTest` muessen gruen
  bleiben

#### 5.1.3 DataExportRunner zerlegen (923 LOC → 5 Einheiten)

Baut auf dem in 5.1.1 zerlegten StreamingExporter auf.

- **ExportRequestValidator**: CLI-Validierung (incremental
  --since-column/--since Paarung, Filter-Literal-Check,
  Resume-Stdout-Ablehnung, Identifier-Validierung)
- **ExportSourceResolver**: Connection-Aufloesung, Encoding-Parsing,
  Pool-Erzeugung mit Fehler-Mapping, Reader/Lister-Lookup,
  Tabellenentdeckung
- **ExportResumeCoordinator**: Resume-Referenz-Aufloesung,
  Manifest-Laden, Fingerprint-Validierung,
  SingleFile-Completion-Check, per-Tabelle ResumeMarker-Aufloesung
  (3 Faelle Phase C.2 §4.1), PrimaryKey-Lookup und Memoization
- **ExportManifestCoordinator**: Fingerprint-Berechnung mit
  PK-Signatur, Initial-Manifest-Erstellung,
  `onTableCompleted`/`onChunkProcessed`-Callbacks,
  Staging-Redirect-Anwendung (SingleFile Phase C.2 §5.4)
- **ExportExecutionService**: ExportExecutor-Aufruf,
  Staging→Target Atomic-Move, Manifest-Abschluss,
  Exit-Code-Bestimmung
- bestehende `DataExportRunnerTest` muss gruen bleiben

#### 5.1.4 DataImportRunner zerlegen (949 LOC → 5 Einheiten)

Baut auf dem in 5.1.2 zerlegten StreamingImporter auf.

- **ImportRequestValidator**: CLI-Validierung (--table/--tables
  Mutual Exclusion, Identifier-Pattern, --truncate + --on-conflict
  Konflikt, Resume-Stdin-Validierung)
- **ImportSourceResolver**: Path-Existenzpruefung, Format-Erkennung
  aus Extension, Format-Parsing, Input-Typ-Bestimmung
  (stdin/file/dir), Directory-Scan mit Filter/Order
- **ImportResumeCoordinator**: Resume-Referenz-Aufloesung,
  Manifest-Laden, Fingerprint-Validierung,
  Tabellenlisten-Pruefung, Input-File-Binding-Validierung
  (Phase D.4), Resume-Context-Konstruktion (operationId, skipped
  tables, resume states)
- **ImportManifestCoordinator**: Fingerprint-Berechnung,
  Initial-Manifest-Erstellung,
  `onChunkCommitted`/`onTableCompleted`-Callbacks mit
  Manifest-Fortschreibung, Persistence-Error-Handling
  (continue vs abort)
- **ImportExecutionService**: Schema-Preflight,
  ImportExecutor-Aufruf, Result-Interpretation, Exit-Code
- bestehende `DataImportRunnerTest` muss gruen bleiben

### 5.2 Comparator-Zerlegung

- `SchemaComparator` pro Objekttyp in kleinere Diff-Einheiten trennen
- vorhandene Methoden wie `compareTables`/`compareViews`/...
  gezielt in eigene Bausteine extrahieren; kein fachliches Redesign der
  Vergleichsregeln
- `customTypes` ausdruecklich als eigener Diff-Baustein schneiden
- gemeinsame Vergleichshilfen extrahieren, wo sinnvoll
- bestehende Ergebnis- und Diff-Semantik stabil halten

### 5.3 DDL-Generatoren und Dialekt-Capabilities schneiden

- Objektart-Generatoren bzw. klar getrennte Hilfseinheiten fuer alle
  heutigen DDL-Hotspots einfuehren
- mindestens abdecken:
  - Custom Types
  - Sequences
  - Constraint-/Index-Fallbacks
  - Views (`ViewDdlGenerator`)
  - Functions (`FunctionDdlGenerator`)
  - Procedures (`ProcedureDdlGenerator`)
  - Triggers (`TriggerDdlGenerator`)
- dialektspezifische Generatoren auf Komposition aus diesen
  Objekt-Generatoren umstellen
- SQL-rendernde Objekt-Generatoren bleiben primaer dialektspezifisch;
  dialektuebergreifend werden nur kleine Hilfen fuer Capability-Checks,
  Note-/Result-Assembly und ggf. gemeinsame Fallback-Entscheidungen
  geteilt
- Rewrite-/Skip-/Manual-Action-Entscheidungen auf zwei explizite
  Ebenen stuetzen:
  - `DialectCapabilities` fuer echte Zieldialekt-Regeln
  - per-Objekt-Entscheidung fuer fehlenden Body,
    `sourceDialect`-Mismatch und vergleichbare Einzelfaelle
- bestehende `TODO`-Kommentar-Platzhalter durch strukturierte
  `ManualActionRequired`-Eintraege ersetzen
- `ManualActionRequired` konsequent in bestehende `TransformationNote`-
  und `SkippedObject`-Ergebnisse abbilden; kein neuer separater
  Ausgabeweg fuer CLI, JSON oder Reports
- Default-Rendering fuer 0.9.1 so belassen, dass bestehende
  `-- TODO: ...`-basierte DDL-Tests, Golden-Master-Files und
  Nutzererwartungen nicht brechen
- internen Objektart- und Reihenfolgeschnitt so vorbereiten, dass
  spaeter `pre-data`/`post-data` moeglich wird, ohne jetzt neue
  Artefakte zu emittieren; explizite Phasenattribute,
  Routine-Abhaengigkeitsanalyse fuer Views und `ddl_parts` bleiben
  weiter Thema des spaeteren Split-Plans

### 5.4 Plan-Kommentare rueckbauen und Stabilitaet absichern

- historische Plan-/Milestone-Verweise aus den in Phase B beruehrten
  Produktionscode-Dateien entfernen
- wenn waehrend Phase B weitere gleichartige Verweise in direkt
  angrenzenden Produktionsdateien auffallen, diese im selben Sweep
  mitbereinigen; kein unbegrenzter Repo-Cleanup ohne Zusammenhang
- im Code nur noch "why"-Kommentare und Invarianten belassen
- Refaktorierung mit gezielten Tests absichern:
  - Runner-/Streaming-Pfade
  - Comparator-Ergebnisse
  - DDL-Default-Output
  - strukturierte Unsupported-/Rewrite-/Manual-Action-Faelle
  - bestehende `-- TODO: ...`-Assertions und Golden-Master-Files
- Kover-Coverage pro betroffenem Modul bei mindestens 90 % halten

### 5.5 Grobe Aufwandseinschaetzung

- **5.1 Runner- und Streaming-Zerlegung**: L gesamt, aufgeteilt in:
  - 5.1.1 StreamingExporter: S (450 LOC, 3 Einheiten)
  - 5.1.2 StreamingImporter: M (792 LOC, 4 Einheiten)
  - 5.1.3 DataExportRunner: M-L (923 LOC, 5 Einheiten + Resume)
  - 5.1.4 DataImportRunner: M-L (949 LOC, 5 Einheiten + Resume)
- **5.2 Comparator-Zerlegung**: M - Logik ist bereits pro Objekttyp
  strukturiert, der Hauptaufwand liegt in Extraktion, Wiring und
  Teststabilitaet
- **5.3 DDL-Generatoren und Dialekt-Capabilities schneiden**: L -
  drei Treiber, 18 bestehende `TODO`-Pfade und neues
  Capability-/Manual-Action-Modell
- **5.4 Plan-Kommentare rueckbauen und Stabilitaet absichern**: M -
  Querschnitt ueber mehrere Dateien plus Golden-Master- und
  Kompatibilitaetstests

Gesamtschnitt fuer Phase B: L

---

## 6. Verifikation

Phase B ist erst abgeschlossen, wenn folgende Punkte gruen sind:

- bestehende Runner- und Streaming-Tests bleiben stabil
- neue Tests sichern die delegierte Struktur ohne Verhaltensverlust
- Comparator-Tests bleiben fuer alle betroffenen Objekttypen gruen
- Kover-Coverage bleibt pro betroffenem Modul bei mindestens 90 %
- DDL-Tests bestaetigen:
  - gleicher Default-Output fuer `schema generate`
  - strukturierte Modellierung von Unsupported-/Rewrite-Faellen
  - unveraenderte Sichtbarkeit dieser Faelle ueber bestehende
    `notes`-/`skippedObjects`-Pfade in CLI, JSON und Report
  - keine Rueckkehr zu losen, unstrukturierten `TODO`-Entscheidungen im
    Generatorinneren
  - weiterhin kompatibles `-- TODO: ...`-Rendering im sichtbaren
    Default-Pfad, solange 0.9.1 daran festhaelt
  - bestehende String-Assertions in DDL-Generator-Tests und
    referenzierte SQL-Fixtures bleiben gruen bzw. werden bewusst und
    gemeinsam migriert, falls Phase B ihren Vertrag spaeter doch lockert

Mindestergebnis:

- die wartungskritischen Hotspots sind in kleinere Einheiten zerlegt
- der DDL-Pfad ist intern auf Objektart- und Capability-Schnitt
  vorbereitet, ohne den bestehenden Diagnose- oder Split-Vertrag
  vorwegzunehmen
- Nutzer sehen in 0.9.1 noch keinen neuen DDL-Output-Vertrag

---

## 7. Betroffene Codebasis

Mit hoher Wahrscheinlichkeit betroffen:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataExportCommand.kt`
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingImporter.kt`
- `adapters/driven/streaming/src/main/kotlin/dev/dmigrate/streaming/StreamingExporter.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt`
- ggf. direkt angrenzende Produktionsdateien mit Plan-/Milestone-
  Verweisen, z. B. in `adapters/driven/formats/...`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDialect.kt`
- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
- ggf. neue gemeinsame Capability-/Manual-Action-Typen in
  `hexagon:ports` oder `adapters:driven:driver-common`
- DDL-Generator-Tests unter `adapters/driven/driver-*/src/test/...`
- Golden-Master-/Fixture-Dateien unter
  `adapters/driven/formats/src/test/resources/fixtures/ddl/...`
- `docs/ddl-output-split-plan.md`

Die genaue Paketlage darf waehrend der Umsetzung pragmatisch angepasst
werden; entscheidend ist der sauberere fachliche Zuschnitt.

---

## 8. Risiken und offene Punkte

### 8.1 Zerlegung kann nur verschieben statt vereinfachen

Gegenmassnahme:

- Extraktionen nur mit klarer Verantwortung je Einheit
- keine neuen Sammel-Utilities als versteckte Rest-Gottklassen

### 8.2 Resume- und Streaming-Invarianten koennen beim Schnitt brechen

Gegenmassnahme:

- Runner-/Streaming-Tests waehrend der Zerlegung gruen halten
- Schnitt entlang echter Pipeline-Phasen statt entlang von Methodenlisten

### 8.3 DDL-Refactor kann versehentlich sichtbaren Output veraendern

Gegenmassnahme:

- bestehende DDL-Golden-Masters beibehalten
- Default-Output explizit als Stabilitaetskriterium pruefen

### 8.4 Capability-Modell kann zu abstrakt werden

Gegenmassnahme:

- Capability-Typen nur so weit einfuehren, wie sie reale
  Generatorentscheidungen vereinheitlichen
- keine Vorab-Generalisierung fuer hypothetische Dialekte

---

## 9. Entscheidungsempfehlung

Phase B sollte direkt auf Phase A folgen.

Begruendung:

- sie adressiert die groessten Wartungs-Hotspots aus `docs/quality.md`
- sie schafft die interne Kompositionsbasis fuer spaetere Port-,
  Profiling- und Integrations-Refactors
- sie bereitet den spaeteren optionalen DDL-Output-Split vor, ohne den
  0.9.1-Nutzervertrag bereits zu aendern
