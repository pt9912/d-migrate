# Implementierungsplan: Phase B - Zerlegung der grossen Orchestrierungs- und Dialekt-Klassen

> **Milestone**: 0.9.1 - Library-Refactor und Integrationsschnitt
> **Phase**: B (Zerlegung der grossen Orchestrierungs- und Dialekt-Klassen)
> **Status**: Draft (2026-04-17)
> **Referenz**: `docs/implementation-plan-0.9.1.md` Abschnitt 1 bis 5,
> Abschnitt 6.2, Abschnitt 7, Abschnitt 8 und Abschnitt 9;
> `docs/quality.md`; `docs/ddl-output-split-plan.md`;
> `hexagon/application/.../DataImportRunner.kt`;
> `hexagon/application/.../DataExportRunner.kt`;
> `adapters/driven/streaming/.../StreamingImporter.kt`;
> `hexagon/core/.../SchemaComparator.kt`;
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
- wie `StreamingImporter` und die Export-Seite entlang von
  Orchestrierung, Tabellen-Pipeline und Chunk-Handling getrennt werden
- wie `SchemaComparator` pro Objekttyp in kleinere Diff-Einheiten
  geschnitten wird
- wie die drei DDL-Generatoren pro Objektart komponiert werden, ohne
  in 0.9.1 bereits einen neuen sichtbaren DDL-Output-Vertrag
  einzufuehren
- wie heutige `-- TODO: ...`-SQL-Kommentar-Platzhalter intern durch
  strukturierte `ManualActionRequired`-Eintraege ersetzt werden
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

- **Mittel**: `DataImportRunner` ist mit rund 887 LOC zu gross und
  vereint Validierung, Aufloesung, Resume, Manifest- und
  Ausfuehrungslogik
- **Mittel**: `DataExportRunner` ist mit rund 923 LOC aehnlich
  ueberladen
- **Mittel**: `StreamingImporter` liegt bei rund 792 LOC und mischt
  Orchestrierung, Tabellensteuerung und Chunk-Verarbeitung
- **Mittel**: `SchemaComparator` liegt bei rund 656 LOC und behandelt
  mehrere Objekttypen in einer Klasse
- **Mittel**: die drei DDL-Generatoren bundeln Views, Functions,
  Procedures, Triggers, Rewrite-Fallbacks und Capability-Entscheidungen
  jeweils in monolithischen Dateien
- in den DDL-Generatoren existieren weiterhin mehrere `-- TODO: ...`-
  SQL-Kommentar-Platzhalter statt strukturierter Modellierung von
  manueller Nacharbeit

Konsequenz:

- fachliche Verantwortung ist heute zu breit verteilt
- Dialekt-Fixes sind regressionsanfaellig, weil Capability- und
  Rewrite-Logik mehrfach und dateiweise gekoppelt ist
- der spaetere optionale DDL-Output-Split (`pre-data`/`post-data`)
  laesst sich ohne internen Objekt-/Phasenschnitt nur schwer sauber
  vorbereiten

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
- gleichartige strukturelle Zerlegung der Export-Seite
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
  - `ViewDdlGenerator`
  - `FunctionDdlGenerator`
  - `ProcedureDdlGenerator`
  - `TriggerDdlGenerator`
  - weitere Hilfseinheiten, wenn fuer Tabellen/Indizes/Capabilities
    noetig
- Einfuehrung expliziter Dialekt-Capabilities pro `DatabaseDialect`
- Ersatz der bisherigen `TODO`-Platzhalter durch strukturierte
  `ManualActionRequired`-Eintraege
- Rueckbau historischer Plan-/Milestone-Kommentare aus Produktionscode
- Tests, die die interne Refaktorierung absichern, ohne den sichtbaren
  Default-Output zu veraendern

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
- sie tragen nach Phase B aber primär Orchestrierung und Delegation
- Validierung, Aufloesung, Resume-/Manifest-Koordination und
  Ausfuehrung wandern in kleinere Einheiten

Nicht zulaessig ist:

- eine rein kosmetische Extraktion ohne klaren
  Verantwortungszuschnitt
- neue Utility-Gottklassen, die nur den alten Runner in anderer Form
  kopieren

### 4.2 Streaming wird entlang echter Verarbeitungsphasen geschnitten

Verbindliche Entscheidung:

- `StreamingImporter` und die Export-Seite werden nicht beliebig nach
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
- `customTypes` gehoeren ausdruecklich dazu und bleiben nicht als
  monolithischer Restblock im Comparator stehen
- gemeinsame Vergleichslogik darf in Hilfseinheiten liegen
- die fachliche Top-Level-Sicht bleibt aber objektebenenscharf

### 4.4 DDL-Komposition wird intern objektorientiert, nicht output-wirksam

Verbindliche Entscheidung:

- die DDL-Generatoren werden intern aus Objektart-Generatoren
  zusammengesetzt
- 0.9.1 veraendert dadurch noch nicht den sichtbaren
  `schema generate`-Default
- der interne Schnitt darf aber spaetere Phasenobjekte und den
  `pre-data`/`post-data`-Pfad vorbereiten

### 4.5 Capability-Entscheidungen werden explizit modelliert

Verbindliche Entscheidung:

- Generatoren sollen nicht implizit oder dateiweise entscheiden, ob ein
  Objekt generierbar, rewritable, unsupported oder manual-action ist
- dafuer kommt ein expliziter Capability-Typ je Dialekt hinzu
- diese Capability-Modellierung ist Teil der internen
  Konsolidierungsbasis fuer alle drei Treiber

### 4.6 `TODO`-SQL-Kommentare sind kein Endzustand

Verbindliche Entscheidung:

- heutige `-- TODO: ...`-Platzhalter in Generatoren werden intern durch
  strukturierte `ManualActionRequired`-Eintraege ersetzt
- das gilt nicht nur fuer Views/Functions/Procedures/Triggers, sondern
  fuer alle heutigen `TODO`-Pfade, insbesondere auch:
  - Custom Types
  - Sequences
  - Constraint-Fallbacks wie `EXCLUDE`
  - Index-Typ-Fallbacks
- Kommentare duerfen weiterhin "why" und Invarianten erklaeren
- Planhistorie und Milestone-Verweise gehoeren aber zurueck in `docs/`

Kompatibilitaetsvertrag fuer 0.9.1:

- intern darf die Modellierung auf `ManualActionRequired` umgestellt
  werden
- extern muss der bestehende Default-Output fuer `schema generate`
  zunaechst stabil bleiben
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

- `DataImportRunner` in kleinere Dienste trennen
- `DataExportRunner` analog aufteilen
- `StreamingImporter` in Orchestrator, Tabellen-Pipeline und
  Chunk-Handler schneiden
- Export-Seite entlang derselben Logik aufteilen
- Resume-, Manifest- und Fortschrittsinvarianten ueber Tests absichern

### 5.2 Comparator-Zerlegung

- `SchemaComparator` pro Objekttyp in kleinere Diff-Einheiten trennen
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
  - Views
  - Functions
  - Procedures
  - Triggers
- dialektspezifische Generatoren auf Komposition aus diesen
  Objekt-Generatoren umstellen
- Rewrite-/Skip-/Manual-Action-Entscheidungen auf explizite
  Capability-Typen stützen
- bestehende `TODO`-Kommentar-Platzhalter durch strukturierte
  `ManualActionRequired`-Eintraege ersetzen
- Default-Rendering fuer 0.9.1 so belassen, dass bestehende
  `-- TODO: ...`-basierte DDL-Tests und Nutzererwartungen nicht brechen
- internen Phasen-/Objektschnitt so vorbereiten, dass spaeter
  `pre-data`/`post-data` moeglich wird, ohne jetzt neue Artefakte zu
  emittieren

### 5.4 Plan-Kommentare rueckbauen und Stabilitaet absichern

- historische Plan-/Milestone-Verweise aus Produktionscode entfernen
- im Code nur noch "why"-Kommentare und Invarianten belassen
- Refaktorierung mit gezielten Tests absichern:
  - Runner-/Streaming-Pfade
  - Comparator-Ergebnisse
  - DDL-Default-Output
  - strukturierte Unsupported-/Rewrite-/Manual-Action-Faelle

---

## 6. Verifikation

Phase B ist erst abgeschlossen, wenn folgende Punkte gruen sind:

- bestehende Runner- und Streaming-Tests bleiben stabil
- neue Tests sichern die delegierte Struktur ohne Verhaltensverlust
- Comparator-Tests bleiben fuer alle betroffenen Objekttypen gruen
- DDL-Tests bestaetigen:
  - gleicher Default-Output fuer `schema generate`
  - strukturierte Modellierung von Unsupported-/Rewrite-Faellen
  - keine Rueckkehr zu losen, unstrukturierten `TODO`-Entscheidungen im
    Generatorinneren
  - weiterhin kompatibles `-- TODO: ...`-Rendering im sichtbaren
    Default-Pfad, solange 0.9.1 daran festhaelt

Mindestergebnis:

- die wartungskritischen Hotspots sind in kleinere Einheiten zerlegt
- der DDL-Pfad ist intern auf Objektart- und Capability-Schnitt
  vorbereitet
- Nutzer sehen in 0.9.1 noch keinen neuen DDL-Output-Vertrag

---

## 7. Betroffene Codebasis

Mit hoher Wahrscheinlichkeit betroffen:

- `hexagon/application/.../DataImportRunner.kt`
- `hexagon/application/.../DataExportRunner.kt`
- `adapters/driven/streaming/.../StreamingImporter.kt`
- Export-Gegenstuecke im Streaming-/Application-Bereich
- `hexagon/core/.../SchemaComparator.kt`
- `adapters/driven/driver-postgresql/.../PostgresDdlGenerator.kt`
- `adapters/driven/driver-mysql/.../MysqlDdlGenerator.kt`
- `adapters/driven/driver-sqlite/.../SqliteDdlGenerator.kt`
- ggf. neue gemeinsame Capability-/Manual-Action-Typen in Core- oder
  Driver-Common-Modulen
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

- Runner-/Streaming-Tests waehrend der Zerlegung grün halten
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
