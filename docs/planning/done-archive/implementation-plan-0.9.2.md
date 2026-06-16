# Implementierungsplan: Milestone 0.9.2 - Beta: DDL-Phasen und importfreundliche Schema-Artefakte

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.9.2. Es dient als laufend gepflegte Spezifikation und
> Review-Grundlage fuer den sichtbaren DDL-Split von `schema generate`
> sowie fuer die noch offenen Quality-Arbeiten, die laut Roadmap im
> selben Milestone abgeschlossen werden sollen.
>
> Status: Draft (2026-04-18)
> Referenzen: `docs/planning/roadmap.md` Abschnitt "Milestone 0.9.2",
> `docs/planning/ddl-output-split-plan.md`,
> `spec/ddl-generation-rules.md`,
> `spec/cli-spec.md`,
> `docs/user/quality.md`,
> `docs/planning/implementation-plan-0.9.1.md` Abschnitt 4.6,
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`,
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/ManualActionRequired.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`,
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`,
> `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`,
> `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`.

---

## 1. Ziel

Milestone 0.9.2 fuehrt den in 0.9.1 bewusst zurueckgestellten sichtbaren
Endnutzervertrag fuer importfreundliche DDL-Artefakte ein.

Der Milestone liefert zwei zusammengehoerende Ergebnisse:

- `schema generate` kann optional statt eines einzigen DDL-Stroms zwei
  phasenbezogene Artefakte erzeugen:
  - `pre-data`
  - `post-data`
- die noch offenen Quality-Arbeiten aus `docs/user/quality.md` werden so weit
  geschlossen, dass der neue Split-Vertrag nicht auf einem unsauberen
  Generator- oder Runner-Unterbau aufsetzt

Der fachliche Hauptnutzen ist operativ:

- MySQL- und SQLite-Workflows bekommen einen sauberen Weg, Trigger,
  Routinen und davon abhaengige Views erst nach einem `data import`
  zu aktivieren
- PostgreSQL kann denselben Vertrag fuer manuelle Rollouts, Tooling und
  spaetere Exportpfade nutzen
- JSON-, Report- und Diagnoseausgaben bleiben im Split-Fall
  maschinenlesbar, ohne den bestehenden Default-Output zu brechen

0.9.2 ist damit bewusst ein Nutzer-Feature-Milestone auf Basis der
0.9.1-Vorarbeiten:

- 0.9.1 durfte den DDL-Modell-Schnitt intern vorbereiten
- 0.9.2 macht daraus einen expliziten CLI- und Output-Vertrag

---

## 2. Ausgangslage

Stand im aktuellen Repo:

- `DdlResult` ist aktuell noch ein flaches Modell aus:
  - `statements: List<DdlStatement>`
  - `skippedObjects: List<SkippedObject>`
  - `notes` werden implizit aus allen Statements abgeleitet
- `DdlStatement`, `TransformationNote` und `SkippedObject` tragen noch
  keinen Phasenbezug
- `SchemaGenerateRequest` kennt noch keinen Split-Modus; der Runner
  rendert nur einen zusammenhaengenden DDL-Text ueber `result.render()`
- `SchemaGenerateRunner` unterstuetzt heute genau drei sichtbare
  Ausgabemodi:
  - SQL an `stdout`
  - SQL-Datei plus Sidecar-Report
  - JSON mit einem einzelnen `ddl`-String
- `TransformationReportWriter` kennt heute weder `split_mode` noch
  phasenbezogene Notes oder `skipped_objects`
- `SchemaGenerateHelpers.formatJsonOutput(...)` serialisiert im
  Erfolgsfall immer genau ein Feld `ddl`
- die Generatoren sind in 0.9.1 bereits auf strukturierte
  `ManualActionRequired`-Metadaten vorbereitet; das Default-Rendering
  emittiert aber noch aus Rueckwaertskompatibilitaetsgruenden
  `-- TODO: ...`-Kommentare
- der in 0.9.1 geplante Port-Schnitt ist im Repo bereits angelegt:
  - `hexagon:ports-common`
  - `hexagon:ports-read`
  - `hexagon:ports-write`
- mehrere vormals offene Quality-Arbeiten sind bereits erledigt und
  gehoeren **nicht** mehr zum 0.9.2-Scope:
  - Routine-Ddl-Helper sind direkt getestet
  - `ports-*`-Module haben explizite Kover-Schwellen
  - PostgreSQL- und SQLite-TODO-Platzhalter sind bereinigt

Noch offen und fuer 0.9.2 relevant bleiben laut `docs/user/quality.md`:

- systematische Absicherung der DDL-Interpolation von
  Schema-Metadaten
- Zerlegung der langen `executeWithPool()`-Methoden in
  `DataExportRunner` und `DataImportRunner`
- Entschaerfung der breiten `ExportExecutor`-/`ImportExecutor`-
  Signaturen ueber Kontext-DTOs
- Eliminierung der verbleibenden 4 MySQL-`-- TODO`-Plaetze
- systematische Fehlercode-Abdeckung gegen die Validierungsmatrix
- ein echter E2E-Round-Trip-Test ueber den Gesamtpfad

Konsequenz:

- 0.9.2 ist nicht nur "noch ein Flag" fuer `schema generate`
- der Milestone muss Generator-Modell, CLI-Vertrag, JSON-/Report-
  Serialisierung, Generator-Zuordnung und begleitende
  Qualitaetssicherung zusammen liefern

---

## 3. Scope

### 3.1 In Scope (Kernfeature)

- neuer Split-Vertrag fuer `schema generate`:
  - `single`
  - `pre-post`
- phasenbezogenes DDL-Modell fuer:
  - Statements
  - Notes
  - `skipped_objects`
- deterministische Zuordnung der Objektarten zu:
  - `pre-data`
  - `post-data`
- sichtbarer CLI- und Output-Vertrag:
  - SQL-Dateiausgabe fuer Split-Fall
  - JSON-Ausgabe ueber `ddl_parts`
  - Sidecar-Report mit `split_mode` und `phase`
- Rueckwaertskompatibilitaet des heutigen Default-Pfads
- Tests fuer:
  - Golden Masters
  - CLI-Validierung
  - JSON-/Report-Ausgabe
  - Fehlerpfade
  - View-/Routinen-Abhaengigkeiten

### 3.2 In Scope (begleitende Quality-Arbeiten)

Die folgenden Arbeiten sind Teil von 0.9.2, weil sie laut Roadmap im
Milestone liegen und direkt an den neuen DDL-/Importvertrag andocken:

- DDL-Interpolation systematisch absichern:
  - CHECK-Constraints
  - Partition-Ausdruecke
  - Trigger-Bedingungen
  - SpatiaLite-Funktionsaufrufe
  - Umgang mit Routine-/View-Bodies explizit als Trusted-Input-Grenze
- `DataExportRunner.executeWithPool()` und
  `DataImportRunner.executeWithPool()` in kleinere Schrittfunktionen
  zerlegen
- `ExportExecutor` und `ImportExecutor` ueber Kontext-DTOs und
  Callback-Gruppen entschlacken
- die verbleibenden 4 MySQL-`-- TODO`-Plaetze durch rein strukturierte
  `ManualActionRequired`-/`SkippedObject`-Wege ersetzen
- Fehlercodes E006-E121 gegen die dokumentierte Matrix absichern
- einen echten E2E-Round-Trip-Test fuer
  DB -> Export -> Format -> Import -> DB -> Schema-Vergleich nachziehen

### 3.3 Bewusst nicht Teil von 0.9.2

- automatische Ausfuehrung eines Datenimports zwischen `pre-data` und
  `post-data`
- neuer Importmodus fuer Trigger-Deaktivierung auf MySQL oder SQLite
- Rollback-Split im selben Milestone
- weitere DDL-Phasen jenseits von `pre-data`/`post-data`
  (z. B. `post-constraints`, `seed-data`, `tool-only`)
- neuer Tool-Exportvertrag fuer Flyway/Liquibase auf Basis der neuen
  Phasen
- Aenderung des bestehenden Default-Outputs von `schema generate`
  ausserhalb des expliziten Split-Modus
- oeffentlicher Publish-Vertrag oder Artefakt-Neuschnitt fuer 1.0.0

---

## 4. Leitentscheidungen

### 4.1 `single` bleibt der unveraenderte Default

Verbindliche Entscheidung:

- ohne `--split` bleibt `schema generate` fachlich und textuell beim
  bisherigen Verhalten
- bestehende Golden Masters fuer den Single-Pfad duerfen sich nicht
  unbeabsichtigt aendern
- der neue Split ist opt-in und darf keine heimliche Default-
  Umdeutung einfuehren

### 4.2 Der sichtbare Vertrag ist `single|pre-post`

Verbindliche Entscheidung:

- der CLI-Vertrag lautet:
  - `--split single`
  - `--split pre-post`
- Default ist `single`
- `--split single` ist ein explizites No-Op: es ist gleichwertig zu
  keinem `--split`-Argument und existiert nur, damit Automatisierungen
  den Modus immer explizit angeben koennen
- andere Modellvarianten bleiben intern offen, werden aber in 0.9.2
  nicht nach aussen exponiert

### 4.3 Der Phasenbezug lebt im DDL-Modell, nicht nur im Renderer

Verbindliche Entscheidung:

- `DdlStatement` traegt eine `phase`
- `TransformationNote` und `SkippedObject` tragen ebenfalls einen
  optionalen oder expliziten `phase`-Bezug
- `DdlResult` bleibt ein einheitlicher Ergebniscontainer; er wird nicht
  nur fuer die Dateiausgabe in zwei unverbundene Strings zerlegt

Begruendung:

- dieselbe Quelle steuert SQL-Rendering, JSON, Report und Diagnose
- phasenbezogene Hinweise koennen spaeter ohne Sonderwege erweitert
  werden
- Inkonsistenzen zwischen SQL, JSON und Report werden reduziert

### 4.3a `DdlPhase`-Defaults

Verbindliche Entscheidung:

- `DdlPhase` ist ein Enum mit mindestens `PRE_DATA` und `POST_DATA`
- `DdlStatement.phase` hat den Default `DdlPhase.PRE_DATA`
- `TransformationNote.phase` und `SkippedObject.phase` sind
  **nullable** (`DdlPhase? = null`)
- Generatoren setzen `POST_DATA` nur dort explizit, wo die
  Objektzuordnung (§5.3) dies verlangt
- Notes, die an einem konkreten Statement haengen, erben dessen Phase
  automatisch (DdlStatement traegt die Phase; seine Notes werden beim
  Rendering/Export der Phase des Statements zugeordnet)
- freistehende Notes oder SkippedObjects ohne Phase (`null`) werden im
  Split-Fall keiner Phase zugeordnet
  - im JSON bleibt bei solchen Eintraegen das Feld `phase`
    vollstaendig weg
  - im Report erscheinen sie als globale Diagnose ohne `phase`

Begruendung:

- bestehender Code erzeugt ausschliesslich `DdlStatement(sql, notes)`
  ohne drittes Argument; mit Default bleibt dieser Code korrekt, ohne
  dass alle Aufrufer gleichzeitig umgestellt werden muessen
- der Single-Renderpfad ignoriert das Feld beim Rendering; der Default
  ist dort semantisch irrelevant
- im Split-Fall ist `PRE_DATA` der konservative Default fuer
  Statements, weil Tabellenstruktur immer vor Routinen kommen muss
- globale Diagnose-Notes (z.B. View-Sortierungswarnungen aus
  `AbstractDdlGenerator`) gehoeren fachlich zu keiner einzelnen Phase;
  ein erzwungener Default wuerde sie falsch etikettieren

### 4.4 JSON bleibt rueckwaertskompatibel

Verbindliche Entscheidung:

- im Single-Fall bleibt `ddl` ein String
- im Split-Fall wird **kein** Typwechsel von `ddl` vorgenommen
- stattdessen fuehrt der Split-Fall ein neues Feld `ddl_parts` ein

Nicht zulaessig ist:

- `ddl` bei `single` als String und bei `pre-post` als Objekt
  umzudeuten

### 4.5 SQL-Split braucht einen adressierbaren Outputpfad

Verbindliche Entscheidung:

- `--split pre-post` mit Text-SQL ist nur zusammen mit `--output`
  erlaubt
- `--split pre-post` ohne `--output` endet mit Exit 2, sofern nicht
  `--output-format json` gewaehlt ist
- JSON darf im Split-Fall auch ohne `--output` serialisiert werden,
  weil es ein einzelnes strukturiertes Antwortartefakt ist

### 4.6 Rollback bleibt im Split-Fall fuer 0.9.2 bewusst aus

Verbindliche Entscheidung:

- `--split pre-post --generate-rollback` ist in 0.9.2 nicht
  unterstuetzt
- der Aufruf endet mit Exit 2 und klarer Fehlermeldung

Begruendung:

- ein halbspezifizierter Rollback-Split vergroessert den Vertrag stark,
  ohne den Kernnutzen des Features zu liefern
- `pre-data`/`post-data` fuer Down-Migrationen braucht eine eigene
  fachliche Modellierung

### 4.7 `pre-data` muss fuer sich ausfuehrbar bleiben

Verbindliche Entscheidung:

- `pre-data` darf keine Objekte enthalten, die erst durch
  `post-data`-Objekte ausfuehrbar werden
- Views mit Routine-Abhaengigkeiten wandern nach `post-data`
- wenn die Analyse fuer einen sicheren Split nicht ausreicht, wird der
  Split-Lauf abgebrochen statt ein potenziell defektes Artefakt zu
  erzeugen

### 4.8 Quality-Arbeiten werden nur in einer Form akzeptiert, die den neuen Vertrag staerkt

Verbindliche Entscheidung:

- Runner-Zerlegung ist kein separater Schoenheitsrefactor, sondern muss
  die Verstaendlichkeit der 0.9.2-Feature- und Fehlerpfade messbar
  verbessern
- DDL-Haertung darf sichtbare Generatorsemantik nur dort aendern, wo
  unsichere oder unquotierte Interpolation vorlag
- MySQL-`-- TODO`-Bereinigung darf den Diagnosevertrag nicht
  verschlechtern; strukturierte `ManualActionRequired` muss in Text,
  JSON und Report gleichwertig sichtbar bleiben

---

## 5. Zielarchitektur

### 5.1 DDL-Modell mit explizitem Phasenbezug

Zielstruktur:

- `DdlPhase` mit mindestens:
  - `PRE_DATA`
  - `POST_DATA`
- Serialisierung in JSON und Report als Kebab-Case (`"pre-data"`,
  `"post-data"`), konsistent mit dem CLI-Vertrag `--split pre-post`
- `DdlStatement(sql, notes, phase)` — Default: `PRE_DATA`
- `TransformationNote(..., phase)` — nullable; erbt Phase vom Statement;
  im JSON/Report nur gesetzt, wenn fachlich bekannt
- `SkippedObject(..., phase)` — nullable; Phase des ersetzten Objekttyps;
  im JSON/Report nur gesetzt, wenn fachlich bekannt
- `DdlResult` mit:
  - Gesamtliste aller Statements
  - Render-Helfern fuer Gesamt- und Phasensicht
  - konsistenten Aggregationen fuer Notes und Skips

### 5.2 Sichtbarer Output-Vertrag

SQL:

- `single`:
  - ein DDL-Artefakt wie heute
- `pre-post`:
  - `<name>.pre-data.sql`
  - `<name>.post-data.sql`
  - Report bleibt ein einzelnes Sidecar-Artefakt

JSON:

- `single`:
  - `ddl: "<string>"`
- `pre-post`:

```json
{
  "command": "schema.generate",
  "status": "completed",
  "exit_code": 0,
  "target": "postgresql",
  "schema": {"name": "my_schema", "version": "1.0"},
  "split_mode": "pre-post",
  "ddl_parts": {
    "pre_data": "CREATE TABLE ...;\nCREATE INDEX ...;",
    "post_data": "CREATE FUNCTION ...;\nCREATE TRIGGER ...;"
  },
  "warnings": 1,
  "action_required": 0,
  "notes": [
    {
      "type": "warning",
      "code": "W102",
      "object": "trg_audit",
      "message": "...",
      "phase": "post-data"
    }
  ],
  "skipped_objects": [
    {
      "type": "sequence",
      "name": "user_id_seq",
      "reason": "...",
      "code": "E056",
      "phase": "pre-data"
    }
  ]
}
```

  Aenderungen gegenueber dem bestehenden Single-JSON:
  - `ddl` entfaellt; stattdessen `split_mode` und `ddl_parts`
  - `notes` und `skipped_objects` erhalten im Split-Fall ein optionales
    `phase`-Feld, sofern die Diagnose einer Phase zuordenbar ist
  - alle uebrigen Felder (`command`, `status`, `exit_code`, `target`,
    `schema`, `warnings`, `action_required`) bleiben identisch
  - der `phase`-Wert in JSON verwendet Kebab-Case (`"pre-data"`,
    `"post-data"`), konsistent mit dem CLI-Vertrag `--split pre-post`
  - im Single-Fall fehlen `split_mode`, `ddl_parts` und die
    `phase`-Felder vollstaendig; `ddl` bleibt ein String
  - globale Diagnosen ohne Phasenbezug bleiben in `notes` bzw.
    `skipped_objects`, aber ohne `phase`

Report:

- `split_mode`
- zusammenfassende Kennzahlen fuer beide Phasen
- Notes und `skipped_objects` jeweils mit optionalem `phase`
- globale Diagnosen ohne Phasenbezug bleiben im Report sichtbar, aber
  ohne `phase`

### 5.3 Objektzuordnung

Zielzuordnung in 0.9.2:

- `pre-data`:
  - Header
  - Custom Types
  - Sequences, sofern der Dialekt sie nativ traegt
  - Tabellen
  - zirkulaere FK-Nachzuegler
  - Indizes
  - Views ohne Routine-Abhaengigkeiten
- `post-data`:
  - Functions
  - Procedures
  - Triggers (inkl. zugehoeriger Trigger-Funktionen, s.u.)
  - Views mit Function-Abhaengigkeiten

Phasenzuordnung von `SkippedObject`/`ManualActionRequired`:

- ein Skip oder `ACTION_REQUIRED` erhaelt die Phase des Objekttyps,
  den er ersetzt (z.B. MySQL Composite Type → `pre-data`, weil
  Custom Types in `pre-data` stehen; nicht unterstuetzte Trigger →
  `post-data`)

Dialektspezifische Randfaelle:

- MySQL- und SQLite-Sequences bleiben wie heute
  `action_required`/`skipped` und erhalten `phase: PRE_DATA`
- PostgreSQL-Trigger erzeugen zwei Statements:
  `CREATE FUNCTION trg_fn_<name>()` und `CREATE TRIGGER <name>`.
  Beide muessen als Einheit in `post-data` landen.
  `PostgresRoutineDdlHelper.generateTriggers()` erzeugt sie bereits
  zusammen; die Phasenmarkierung muss auf beide Statements angewendet
  werden
- SpatiaLite-spezifische DDL (`AddGeometryColumn`) bleibt in
  `pre-data`, muss aber sauber gequotet oder explizit als
  Trusted-Expression-Grenze modelliert werden

### 5.4 Runner- und Executor-Zuschnitt

Fuer 0.9.2 soll die Runner-Struktur erkennbar werden:

- `DataExportRunner`:
  - Registry-/Preflight-Aufloesung
  - Tabellen-/Output-Aufloesung
  - Resume-/Checkpoint-Kontext
  - Streaming-Ausfuehrung
  - Finalisierung/Reporting
- `DataImportRunner`:
  - CLI-/Format-Preflight
  - Schema-Preflight
  - Options-/Fingerprint-Build
  - Resume-/Manifest-Kontext
  - Streaming-Ausfuehrung
  - Finalisierung/Reporting
- `ExportExecutor` und `ImportExecutor` sollen stattdessen mit
  kontextgruppierten Parametern arbeiten:
  - Laufzeitkontext
  - Optionen
  - Resume-Kontext
  - Callbacks

---

## 6. Konkrete Arbeitspakete

Abhaengigkeiten und Reihenfolge:

Kritischer Pfad (sequenziell, jedes Paket haengt vom vorherigen ab):

1. **6.1** zieht den Phasenbezug in das DDL-Modell
2. **6.3** ordnet Generatorobjekte den Phasen zu
3. **6.2** erweitert den sichtbaren CLI-/Output-Vertrag
4. **6.4** implementiert Rendering, JSON und Report

Parallelisierbar zum kritischen Pfad:

5. **6.5** DDL-Haertung und MySQL-Bereinigung (ab sofort, unabhaengig
   vom Phasenmodell; Ergebnisse fliessen in 6.3/6.4 ein)
6. **6.6** Runner-/Executor-Zerlegung (ab sofort, unabhaengig vom
   DDL-Split; beruehrt andere Dateien als 6.1-6.4)

Nachlaufend (setzt fertiges Feature voraus):

7. **6.7** Tests, Fehlercode-Matrix und E2E-Verifikation

### 6.1 DDL-Modell phasenfaehig machen

- `DdlPhase` einfuehren (vgl. §4.3a: Default ist `PRE_DATA`)
- `DdlStatement`, `TransformationNote` und `SkippedObject` um `phase`
  erweitern
- `DdlResult` um Hilfen fuer:
  - Gesamt-Rendering
  - `renderPhase(PRE_DATA)`
  - `renderPhase(POST_DATA)`
  - phasenbezogene Aggregation von Notes/Skips
- bestehende Aufrufer auf den neuen Vertrag umstellen, ohne den
  Single-Renderpfad zu veraendern

Ergebnis:

SQL, JSON und Report koennen denselben Phasenbezug auswerten.

### 6.2 `schema generate` um den Split-Vertrag erweitern

- `SchemaGenerateRequest` um `splitMode` erweitern
- `SchemaGenerateCommand` um `--split single|pre-post` erweitern
- CLI-Validierung ergaenzen:
  - `pre-post` ohne `--output` und ohne JSON -> Exit 2
  - `pre-post` mit `--generate-rollback` -> Exit 2
- Fehlermeldungen, Help-Text und CLI-Spezifikation angleichen

Ergebnis:

Der Nutzervertrag fuer den Split ist explizit und testbar.

### 6.3 Generator-Zuordnung pro Dialekt und Objekttyp festziehen

- alle drei DDL-Generatoren und ihre Routine-Helfer auf explizite
  Phasenmarkierung umstellen
- Zuordnung fest verdrahten fuer:
  - Header → `PRE_DATA`
  - Types → `PRE_DATA`
  - Sequences → `PRE_DATA`
  - Tables → `PRE_DATA`
  - Indices → `PRE_DATA`
  - FK-Nachzuegler → `PRE_DATA`
  - Views ohne Routine-Abhaengigkeit → `PRE_DATA`
  - Views mit Routine-Abhaengigkeit → `POST_DATA`
  - Functions → `POST_DATA`
  - Procedures → `POST_DATA`
  - Triggers → `POST_DATA`
- unsicher aufloesbare Split-Faelle mit klarer Fehlermeldung und Exit 2
  abbrechen

#### 6.3.1 Strategie fuer View-zu-Routine-Abhaengigkeitsanalyse

Ausgangslage:

- `ViewDefinition.dependencies` enthaelt ein `DependencyInfo`-Objekt
  mit Feldern `tables` und `views`, aber aktuell **keine** Felder
  fuer `functions`
- `sortViewsByDependencies()` in `AbstractDdlGenerator` analysiert
  nur View→View-Abhaengigkeiten (per Regex auf FROM/JOIN-Klauseln
  und per deklariertem `dependencies.views`)
- Funktionsaufrufe in SELECT-Ausdruecken (z.B.
  `SELECT get_status() FROM ...`) werden nicht erkannt

Dreistufige Strategie:

1. **Modell und Codecs erweitern**:
   - `DependencyInfo` um optionale Felder
     `functions: List<String>` ergaenzen.
     Das ist ein additives, rueckwaertskompatibles Modell-Update in
     `hexagon/core`.
   - `SchemaNodeParser` (`adapters/driven/formats/.../SchemaNodeParser.kt`)
     muss den neuen `dependencies.functions`-Block aus YAML/JSON-
     Schema-Dateien lesen koennen.
   - `SchemaNodeBuilder` (`adapters/driven/formats/.../SchemaNodeBuilder.kt`)
     muss die neuen Felder beim Schema-Export zurueckschreiben.
   - Ohne diese Codec-Anpassung bleiben dateibasierte Schemas (YAML/JSON)
     beim Split blind fuer Routine-Abhaengigkeiten.

2. **Schema-Reader populieren** (primaere Datenquelle):
   - PostgreSQL: `pg_depend` + `pg_rewrite` liefern belastbare
     View→Function-Kanten auf Katalog-Ebene.
   - MySQL: `INFORMATION_SCHEMA.VIEW_ROUTINE_USAGE` (ab MySQL 8.0.13)
     liefert View→Routine-Referenzen; als Fallback Regex auf
     `view.query`.
   - SQLite: kein Systemkatalog fuer Abhaengigkeiten; Regex-Fallback
     auf `view.query` gegen bekannte Funktionsnamen im Schema.

3. **Konservative Zuordnung im Split**:
   - wenn `view.dependencies.functions` nicht leer ist → `POST_DATA`
   - wenn die Schema-Reader die Abhaengigkeiten nicht populieren
     konnten (z.B. weil die Quelle eine YAML-Datei ohne
     `dependencies`-Block ist) und der View-Query unbekannte
     Bezeichner enthaelt, die auch als Funktionsname im Schema
     vorkommen → `POST_DATA`
   - wenn keine belastbare Zuordnung moeglich ist und `--split`
     aktiv ist → Exit 2 mit Fehlermeldung, die den betroffenen
     View benennt
   - ohne `--split` bleibt die Zuordnung irrelevant und wird
     nicht ausgewertet

Nicht-Ziel fuer 0.9.2:

- vollstaendige SQL-AST-Analyse von View-Queries
- transitive Abhaengigkeiten (View A → View B → Function C); fuer
  0.9.2 genuegt die direkte Ebene, weil View B bereits wegen seiner
  eigenen Abhaengigkeit in `POST_DATA` landet und View A dann ueber
  die bestehende View→View-Sortierung nachzieht

Ergebnis:

`pre-data` und `post-data` sind nicht nur unterschiedlich gerenderte
Strings, sondern fachlich getrennte Artefakte.

### 6.4 SQL-, JSON- und Report-Ausgabe fuer den Split nachziehen

- `SchemaGenerateRunner` fuer Split-Ausgabe erweitern
- Dateinamenslogik fuer:
  - `.pre-data.sql`
  - `.post-data.sql`
- `SchemaGenerateHelpers.formatJsonOutput(...)` auf:
  - `split_mode`
  - `ddl_parts`
  - phasenattribuierte Notes/Skips
  umstellen
- `TransformationReportWriter` um:
  - `split_mode`
  - phasenattribuierte Notes/Skips
  erweitern
- der Single-Fall bleibt fuer Text, JSON und Report byte- bzw.
  semantikstabil

Ergebnis:

Der neue Vertrag ist ueber alle Ausgabekanaele konsistent sichtbar.

### 6.5 DDL-Haertung und MySQL-`-- TODO`-Bereinigung nachziehen

- Interpolationsstellen systematisch inventarisieren und absichern:
  - CHECK-Expressions
  - Domain-/Type-Checks
  - Partition-Werte
  - Trigger-Bedingungen
  - SpatiaLite-Aufrufe
- klare Regel pro Feld dokumentieren:
  - identifier-quoted
  - literal-escaped
  - bewusst als Trusted Input durchgereicht
- MySQL-Generator von sichtbaren `-- TODO`-Placeholders befreien:
  - Composite Types
  - Sequences
  - EXCLUDE-Constraints
  - nicht unterstuetzte Index-Typen
- strukturierte `ManualActionRequired`-/`SkippedObject`-Ausgabe in
  SQL, JSON und Report absichern

Ergebnis:

Der neue DDL-Split baut auf einem saubereren und expliziter gesicherten
Generatorvertrag auf.

### 6.6 Runner-Zerlegung und Executor-Kontextschnitt nachziehen

- `DataExportRunner.executeWithPool()` in kleine Schrittfunktionen
  aufteilen
- `DataImportRunner.executeWithPool()` analog schneiden
- `ExportExecutor.execute(...)` durch Kontextobjekte entlasten
- `ImportExecutor.execute(...)` analog schneiden
- bestehende Tests so nachziehen, dass die neuen Schrittfunktionen und
  Kontexte einzeln absicherbar bleiben

Ergebnis:

Die in `docs/user/quality.md` benannten Hotspots werden kleiner, lesbarer und
fuer 0.9.2-Nacharbeiten stabiler.

### 6.7 Tests, Fehlercode-Matrix und E2E-Round-Trip nachziehen

- bestehende Golden Masters fuer `single` einfrieren
- neue Golden Masters fuer `pre-data` und `post-data` je Dialekt
- CLI-Tests fuer Split-Validierung und Dateinamenslogik
- JSON-/Report-Tests fuer `ddl_parts`, `split_mode` und `phase`
- Fehlercode-Matrix E006-E121 systematisch mit Tests abdecken
- E2E-Round-Trip-Test:
  - Quell-DB erzeugen
  - Daten exportieren
  - in Ziel-DB importieren
  - Schema erneut lesen/vergleichen

Ergebnis:

0.9.2 ist nicht nur funktional implementiert, sondern fachlich und
operativ belastbar belegt.

---

## 7. Verifikationsstrategie

Pflichtfaelle fuer das Kernfeature:

- `schema generate` ohne `--split` bleibt fuer alle bestehenden
  Golden-Master-Faelle unveraendert
- `schema generate --split pre-post --output out/schema.sql` erzeugt:
  - `out/schema.pre-data.sql`
  - `out/schema.post-data.sql`
  - einen Report mit `split_mode`
- `--split pre-post --output-format json` liefert:
  - `split_mode`
  - `ddl_parts`
  - phasenattribuierte `notes`
  - phasenattribuierte `skipped_objects`
- `--split pre-post` ohne `--output` und ohne JSON endet mit Exit 2
- `--split pre-post --generate-rollback` endet mit Exit 2

Pflichtfaelle fuer die DDL-Zuordnung:

- Trigger erscheinen nie in `pre-data`
- Functions und Procedures erscheinen nie in `pre-data`
- Tabellen und Indizes erscheinen nie in `post-data`
- Views ohne Routine-Abhaengigkeit bleiben in `pre-data`
- Views mit Routine-Abhaengigkeit landen in `post-data`
- `pre-data + post-data` ist **semantisch** aequivalent zum
  Single-Gesamtbild: die sequenzielle Ausfuehrung beider Dateien muss
  dasselbe Schema-Ergebnis liefern wie die Ausfuehrung des
  Single-Artefakts.
  Die Statement-Reihenfolge darf abweichen (z.B. Views, die im
  Single-Fall vor Functions stehen, koennen im Split nach `post-data`
  wandern). Ein Byte-Vergleich `cat pre-data.sql post-data.sql | diff`
  ist daher **nicht** der Massstab.

Pflichtfaelle fuer die Quality-Arbeiten:

- DDL-Haertung mit gezielten Negativ- oder Boundary-Tests fuer:
  - CHECK
  - Partition
  - Trigger-WHEN
  - SpatiaLite-Identifier
- keine verbleibenden sichtbaren MySQL-`-- TODO`-Kommentare im
  Generator-Output
- Runner-Refactor bleibt in Exit-Code- und Resume-Semantik stabil
- `ExportExecutor`-/`ImportExecutor`-Aufrufer funktionieren nach
  Kontextschnitt unveraendert
- Fehlercodes E006-E121 sind gegen die Matrix testseitig nachweisbar

Pflichtfall fuer Integrationsverifikation:

- E2E-Round-Trip-Test ueber mindestens einen realen Pfad:
  - Schema erzeugen
  - Daten exportieren
  - Daten importieren
  - Zielschema gegen Erwartung pruefen

Erwuenschte Zusatzfaelle:

- dedizierte Split-Fixtures fuer MySQL- und SQLite-Trigger-Szenarien
- Split-Fixtures mit Routine-abhaengigen Views
- JSON-Contract-Smokes fuer externe Consumer

---

## 8. Betroffene Codebasis

Direkt betroffen:

- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/ManualActionRequired.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`
- die jeweiligen Routine-Helfer pro Dialekt

Direkt betroffen (Codec-Erweiterung fuer Routine-Dependencies, vgl. §6.3.1):

- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeParser.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeBuilder.kt`

Wahrscheinlich mit betroffen:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataExportRunner.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`
- CLI-Tests unter `adapters/driving/cli/src/test/...`
- DDL-Generator-Tests unter `adapters/driven/driver-*/src/test/...`
- Golden Masters unter
  `adapters/driven/formats/src/test/resources/fixtures/ddl/...`
- Integrationstests unter `test/integration-postgresql` und
  `test/integration-mysql`

Dokumentation:

- `docs/planning/roadmap.md`
- `docs/planning/ddl-output-split-plan.md`
- `spec/ddl-generation-rules.md`
- `spec/cli-spec.md`
- `docs/user/guide.md`
- `spec/architecture.md`

---

## 9. Risiken und offene Punkte

### 9.1 Split ohne belastbare View-/Routinen-Analyse kann defekte `pre-data`-Artefakte erzeugen

Wenn Views in `pre-data` landen, obwohl sie Functions aus `post-data`
benoetigen, ist der Split operativ wertlos.

Mitigation (vgl. §6.3.1 fuer die vollstaendige Strategie):

- `DependencyInfo` um `functions` erweitern
- Schema-Reader populieren Abhaengigkeiten aus Systemkatalogen
  (PostgreSQL: `pg_depend`, MySQL: `VIEW_ROUTINE_USAGE`)
- konservative Zuordnung: im Zweifel `POST_DATA` oder Exit 2
- ohne `--split` wird die Analyse nicht ausgewertet (kein Overhead
  fuer den bestehenden Pfad)

### 9.2 JSON- und Report-Vertrag koennen bestehende Consumer brechen

Der Split beruehrt den strukturierten Output von `schema generate`.

Mitigation:

- `ddl` im Single-Fall unveraendert lassen
- `ddl_parts` nur im Split-Fall einfuehren
- Contract-Tests fuer JSON und Report ergaenzen

### 9.3 DDL-Haertung kann sichtbare SQL-Ausgabe unbeabsichtigt veraendern

Sicherheitsverbesserungen an Interpolationsstellen koennen Golden Masters
oder bestehende Handgriffe beeinflussen.

Mitigation:

- fuer jedes betroffene Feld explizit festhalten, ob es gequotet,
  escaped oder bewusst als Trusted Input behandelt wird
- Golden Masters gezielt nur dort anpassen, wo die Aenderung fachlich
  begruendet ist

### 9.4 Runner-Refactor kann Resume-/Checkpoint-Verhalten regressieren

Die langen Runner-Methoden sind zwar wartungskritisch, enthalten aber
viele Semantikdetails.

Mitigation:

- Exit-Code- und Resume-Tests vor dem Umbau stabil halten
- Schrittfunktionen entlang vorhandener Phasen statt quer zur Semantik
  schneiden
- keine fachliche Aenderung ohne explizite Tests

### 9.5 MySQL-`ManualActionRequired` ohne sichtbare SQL-Marker kann Nutzer verwirren

Wenn sichtbare TODO-Kommentare verschwinden, muss die Diagnose trotzdem
operativ sichtbar bleiben.

Mitigation:

- Text-, JSON- und Report-Ausgabe fuer `ACTION_REQUIRED` klar und
  redundant halten
- dokumentieren, dass strukturierte Diagnose die alten TODO-Kommentare
  ersetzt

### 9.6 Der E2E-Round-Trip kann zu breit werden, wenn er zu viele Dialektfaelle zugleich abdeckt

Ein ueberfrachteter Integrationstest verlangsamt den Milestone und wird
instabil.

Mitigation:

- einen schmalen, aber echten End-to-End-Pfad als Pflichtfall
  definieren
- weitere Dialekte oder Sonderfaelle als Folgeabdeckung nachziehen

---

## 10. Entscheidungsempfehlung

Milestone 0.9.2 sollte als klarer Nutzer-Feature-Milestone mit
begleitender Quality-Schliessung umgesetzt werden.

Empfohlener Zuschnitt:

1. `schema generate` bekommt einen expliziten Split-Vertrag
   `single|pre-post`
2. das DDL-Modell wird phasenfaehig gemacht und steuert SQL, JSON und
   Report aus einer gemeinsamen Quelle
3. Generatoren ordnen Trigger, Routinen und davon abhaengige Views
   konsequent `post-data` zu
4. JSON fuehrt `ddl_parts` ein, ohne `ddl` im Default-Fall zu brechen
5. die noch offenen Quality-Arbeiten aus `docs/user/quality.md` werden im
   selben Milestone geschlossen:
   - DDL-Haertung
   - Runner-Zerlegung
   - Executor-Kontextschnitt
   - MySQL-`-- TODO`-Bereinigung
   - Fehlercode-Matrix
   - E2E-Round-Trip

Damit liefert 0.9.2 nicht nur neue Dateien, sondern einen belastbaren
operativen Vertrag fuer importfreundliche Schema-Artefakte, auf dem
spaetere Tool-Integrationen und 1.0.0-Dokumentation sauber aufsetzen
koennen.
