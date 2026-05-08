# Implementierungsplan: 0.9.2 - Arbeitspaket 6.5 DDL-Haertung und MySQL-`-- TODO`-Bereinigung nachziehen

> **Milestone**: 0.9.2 - Beta: DDL-Phasen und importfreundliche
> Schema-Artefakte
> **Arbeitspaket**: 6.5 (DDL-Haertung und MySQL-`-- TODO`-
> Bereinigung nachziehen)
> **Status**: Done (2026-04-19)
> **Referenz**: `docs/planning/implementation-plan-0.9.2.md` Abschnitt 6.5,
> Abschnitt 7, Abschnitt 8, Abschnitt 9.3 und Abschnitt 9.5;
> `docs/user/quality.md`;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/ManualActionRequired.kt`;
> `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`;
> `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresRoutineDdlHelper.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`;
> `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`;
> `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRoutineDdlHelper.kt`;
> `docs/planning/ImpPlan-0.9.2-6.4.md`.

---

## 1. Ziel

Arbeitspaket 6.5 schliesst die noch offenen Qualitaetsarbeiten im
DDL-Pfad:

- Interpolationsstellen werden systematisch inventarisiert und
  feldspezifisch abgesichert
- fuer jedes betroffene DDL-Fragment wird verbindlich festgelegt, ob es
  identifier-quoted, literal-escaped oder bewusst als Trusted Input
  behandelt wird
- die verbleibenden sichtbaren MySQL-`-- TODO`-Kommentare werden aus dem
  Generator-Output entfernt
- `ManualActionRequired`, `TransformationNote` und `SkippedObject`
  bleiben der einzige Diagnosevertrag fuer nicht generierbare Faelle

6.5 ist kein neues Nutzerfeature. Das Arbeitspaket haertet den
bestehenden Generatorvertrag, auf dem 6.1 bis 6.4 aufbauen.

Abhaengigkeit zu 6.4:

- 6.5 kann in der Generatorhaertung und in der MySQL-TODO-Bereinigung
  parallel zu 6.4 begonnen werden
- die kanaluebergreifende Diagnosekonsistenz in Text, JSON und Report
  setzt aber voraus, dass der 6.4-Outputvertrag abgeschlossen ist

Nach 6.5 soll klar gelten:

- DDL-Generatoren interpolieren keine rohen Identifier mehr in
  syntaktische Hilfsaufrufe wie `AddGeometryColumn(...)`
- vollstaendige SQL-Fragmente wie CHECK-, Partition-, Trigger- oder
  Routine-Bodies werden nicht blind "escaped", sondern explizit als
  Trusted Input behandelt
- MySQL zeigt bei nicht unterstuetzten Objekten keine sichtbaren
  `-- TODO`-Marker mehr im SQL an; die Information lebt nur noch in
  strukturierten Diagnosen

---

## 2. Ausgangslage

`docs/user/quality.md` benennt fuer 0.9.2 zwei offene Cluster im
DDL-Bereich:

- ein breites Muster ungehaerteter Interpolation von Schema-Metadaten
- vier verbliebene sichtbare MySQL-`-- TODO`-Plaetze

Die betroffenen Stellen sind im Repo konkret sichtbar:

- CHECK-Expressions:
  - `constraint.expression` in allen drei DDL-Generatoren
  - `typeDef.check` bei PostgreSQL-Domain-Typen
- Partition-Ausdruecke:
  - `partition.from`
  - `partition.to`
  - `partition.values`
- Trigger-Bedingungen:
  - `trigger.condition` in PostgreSQL und SQLite
- SpatiaLite-Aufrufe:
  - Tabellen- und Spaltennamen in `AddGeometryColumn(...)`
- View-/Function-/Procedure-Bodies:
  - nach der jeweiligen Query-/Body-Transformation direkt im erzeugten
    SQL

Parallel dazu traegt der MySQL-Generator noch sichtbare Platzhalter:

- Composite Types
- Sequences
- EXCLUDE-Constraints
- nicht unterstuetzte Index-Typen

Der technische Zwischenstand ist inkonsistent:

- `ManualActionRequired` existiert bereits als strukturierter
  Diagnose-Typ
- die MySQL-Generatoren erzeugen aber teilweise weiterhin
  Pseudo-SQL-Zeilen mit `-- TODO`
- `ManualActionRequired.toTodoComment()` dokumentiert diese Rueckwaerts-
  kompatibilitaet sogar noch explizit

Fuer 0.9.2 reicht diese Zwischenstufe nicht mehr:

- 6.4 fuehrt strukturierte JSON- und Report-Ausgabe fuer denselben
  Diagnosebestand ein
- sichtbare TODO-Kommentare im SQL waeren dazu ein zweiter,
  unstrukturierter Diagnosekanal
- die Haertung muss zugleich sauber zwischen Identifiern, Literalen und
  echten SQL-Ausdruecken unterscheiden; ein pauschales Escaping waere
  fachlich falsch

---

## 3. Scope fuer 6.5

### 3.1 In Scope

- alle in `docs/user/quality.md` benannten Interpolationsstellen im
  DDL-Pfad inventarisieren und einer festen Behandlungsregel zuordnen
- DDL-Helfer und Generatoren pro Dialekt dort anpassen, wo Identifier
  oder Literalwerte heute roh interpoliert werden
- Trusted-Input-Grenze fuer vollstaendige SQL-Fragmente explizit
  dokumentieren und testseitig absichern
- sichtbare MySQL-`-- TODO`-Kommentare aus dem Generator-Output
  im `MysqlDdlGenerator` entfernen
- strukturierte `ACTION_REQUIRED`-/`SkippedObject`-Ausgabe ueber SQL,
  JSON und Report absichern
- Golden Masters und gezielte Boundary-Tests nur dort anpassen, wo die
  Haertung fachlich begruendete SQL-Aenderungen erzeugt

### 3.2 Bewusst nicht Teil von 6.5

- neue Dialekt-Features fuer heute nicht unterstuetzte MySQL-Objekte
- allgemeine SQL-Sandboxing- oder AST-Validierung fuer ganze Query-
  Bodies
- Veraenderung der fachlichen Split-Zuordnung aus 6.3
- neue Fehlercodes ausser dort, wo fuer strukturierte Diagnosen bereits
  ein stabiler Codepfad fehlt
- Bereinigung der bestehenden `-- TODO`-Emitter in
  `MysqlRoutineDdlHelper`, `PostgresRoutineDdlHelper` und
  `SqliteRoutineDdlHelper`

Praezisierung:

6.5 loest "wie wird vorhandene DDL-Erzeugung sicherer und sauberer
dokumentiert?", nicht "welche neuen DDL-Konstrukte unterstuetzen wir
zusaetzlich?".

Fuer die TODO-Bereinigung gilt damit explizit:

- in Scope sind die vier bekannten MySQL-Faelle im
  `MysqlDdlGenerator`
- Routine-Helfer-TODOs sind fachlich ein eigener Komplex
  fuer dialektuebergreifend nicht generierbare Functions, Procedures und
  Triggers
- deren Vereinheitlichung ist ein separates Folgearbeitspaket und keine
  Abschlussbedingung fuer 6.5

---

## 4. Leitentscheidungen

### 4.1 DDL-Haertung folgt einer expliziten Feldklassifikation

Verbindliche Folge:

- jedes interpolierte Feld wird genau einer Klasse zugeordnet:
  - `IDENTIFIER`
  - `LITERAL`
  - `TRUSTED_SQL`
- Implementierung und Tests folgen dieser Klassifikation, nicht einer
  fallweisen Ad-hoc-Behandlung

Die Zuordnung fuer 0.9.2 ist verbindlich:

- `IDENTIFIER`:
  - Objekt- und Spaltennamen, die in Hilfsaufrufe oder DDL-Schablonen
    eingesetzt werden
- `LITERAL`:
  - String-Literale, die syntaktisch SQL-Literale bleiben muessen
- `TRUSTED_SQL`:
  - `constraint.expression`
  - `typeDef.check`
  - `partition.from`
  - `partition.to`
  - `partition.values`
  - `trigger.condition`
  - View-/Function-/Procedure-Bodies

### 4.2 Trusted Input ist eine bewusste Vertragsschnitt, kein Restfehler

Verbindliche Folge:

- vollstaendige SQL-Ausdruecke oder SQL-Bodies werden nicht generisch
  escaped oder quoted
- diese Felder werden fuer 0.9.2 bewusst als Trusted Input aus
  Quell-DB- oder Schema-Datei behandelt
- die Dokumentation muss diese Grenze explizit benennen, damit eine
  spaetere "Sicherheitsbereinigung" nicht versehentlich gueltige SQL-
  Bodies zerstoert

Praezisierung:

- `CHECK (...)`-Inhalte bleiben fachlich Ausdruckssprache des
  Quellschemas
- Partition-Werte bleiben fachlich Dialekt- oder Quellschema-Syntax
- Trigger-`WHEN` bleibt Ausdruckssprache des Trigger-Systems
- View-/Function-/Procedure-Bodies bleiben vollstaendige SQL- bzw.
  Routine-Koerper

6.5 haertet diese Felder daher nicht durch Blind-Escaping, sondern
durch klare Vertragsdokumentation und gezielte Boundary-Tests.

### 4.3 Nur echte Identifier-/Literalpfade werden technisch gehaertet

Verbindliche Folge:

- dort, wo heute rohe Namen in SQL-Funktionsaufrufe oder Hilfssyntax
  fliessen, wird auf zentrale Quote-/Escape-Helfer umgestellt
- der Hauptfall in 0.9.2 ist SpatiaLite:
  - Tabellen- und Spaltennamen fuer `AddGeometryColumn(...)` duerfen
    nicht roh zwischen einfache Quotes interpoliert werden
- semantisch identifierartige Werte, die syntaktisch als SQL-String-
  Literale erscheinen, werden ueber einen expliziten Literalpfad aus
  bereits normalisierten Identifiern serialisiert

Damit gilt fuer 0.9.2:

- Identifier bleiben Identifier
- Literal-Escaping bleibt Literal-Escaping
- keine Vermischung mit `TRUSTED_SQL`

### 4.4 Sichtbare MySQL-`-- TODO`-Kommentare entfallen vollstaendig

Verbindliche Folge:

- der MySQL-Generator emittiert fuer die vier bekannten Luecken keine
  Kommentarzeilen mehr
- die Diagnoseart fuer diese vier Faelle wird in 6.5 explizit
  normiert; sie bleibt nicht pro Call-Site offen
- `render()` und `renderPhase(...)` enthalten nach 6.5 keine aus
  diesen vier MySQL-Faellen abgeleiteten `-- TODO`-Zeilen mehr

Praezisierung pro Fall:

- Composite Types:
  - kein SQL-Statement
  - `ACTION_REQUIRED`
  - `SkippedObject`
- Sequences:
  - kein SQL-Statement
  - `ACTION_REQUIRED`
  - `SkippedObject`
- EXCLUDE-Constraints:
  - Table-DDL bleibt erhalten
  - Constraint wird ausgelassen
  - `ACTION_REQUIRED` an der betroffenen Table-/Constraint-Diagnose
- nicht unterstuetzte Index-Typen:
  - kein Pseudo-SQL-Kommentar
  - `WARNING`
  - kein `SkippedObject`, da nicht ein eigenstaendiges Top-Level-Objekt
    entfiel, sondern nur ein nicht erzeugbarer Indexversuch

Verbindliche Diagnose-Matrix fuer 6.5:

- Composite Type nicht unterstuetzt:
  - Phase: `PRE_DATA`
  - Diagnoseobjekte:
    - genau eine `TransformationNote` mit `type = ACTION_REQUIRED`
    - genau ein `SkippedObject`
  - Pflichtfelder:
    - `code`
    - `objectName`
    - `reason`/`message`
    - `phase`
- Sequence nicht unterstuetzt:
  - Phase: `PRE_DATA`
  - Diagnoseobjekte:
    - genau eine `TransformationNote` mit `type = ACTION_REQUIRED`
    - genau ein `SkippedObject`
  - Pflichtfelder:
    - `code`
    - `objectName`
    - `reason`/`message`
    - `phase`
- EXCLUDE-Constraint nicht unterstuetzt:
  - Phase: `PRE_DATA`
  - Diagnoseobjekte:
    - genau eine `TransformationNote` mit `type = ACTION_REQUIRED`
    - kein `SkippedObject`
  - Pflichtfelder:
    - `code`
    - `objectName`
    - `reason`/`message`
    - `phase`
- nicht unterstuetzter Index-Typ:
  - Phase: `PRE_DATA`
  - Diagnoseobjekte:
    - genau eine `TransformationNote` mit `type = WARNING`
    - kein `SkippedObject`
  - Pflichtfelder:
    - `code`
    - `objectName`
    - `reason`/`message`
    - `phase`

Diese Matrix gilt fuer SQL-, JSON- und Report-Sicht identisch. 6.4 darf
dieselben fachlichen Faelle nicht kanalabhaengig anders serialisieren.

### 4.5 Strukturierte Diagnose ist der einzige sichtbare TODO-Ersatz

Verbindliche Folge:

- SQL-Output bleibt ausfuehrbare DDL oder bewusst leere Phase, aber
  kein Diagnoseprotokoll mehr
- Text-, JSON- und Report-Ausgabe muessen dieselben
  `ACTION_REQUIRED`-/`SkippedObject`-Eintraege transportieren
- die Entfernung sichtbarer TODO-Kommentare ist eine gewollte
  Vertragsaenderung fuer SQL, keine Regression

Wichtig:

- `ManualActionRequired.toTodoComment()` kann als Legacy-Helfer
  bestehenbleiben, ist fuer den 0.9.2-Generatorpfad aber nicht mehr
  normative Renderquelle
- im von 6.5 betroffenen `MysqlDdlGenerator`-Pfad sind direkte Aufrufe
  von
  `toTodoComment()` und `DdlStatement("-- TODO ...")` nicht mehr
  zulaessig
- operative Sichtbarkeit von Luecken wird ueber 6.4-Ausgabekanaele
  abgesichert, nicht ueber Kommentar-SQL

### 4.6 Single-Fall bleibt nur dort sichtbar anders, wo Haertung es erfordert

Verbindliche Folge:

- Golden Masters werden nicht breit neu aufgenommen
- sichtbare SQL-Aenderungen sind nur zulaessig, wenn sie direkt aus der
  definierten Haertung resultieren
- reine Vertragsklarstellungen fuer `TRUSTED_SQL` ohne Render-Aenderung
  muessen bestehende Outputs unveraendert lassen

---

## 5. Konkrete Arbeitsschritte

### 5.1 Interpolationsinventar als feste Regelmatrix dokumentieren

- die in `docs/user/quality.md` benannten Stellen in eine konkrete Matrix
  ueberfuehren:
  - Feld
  - Fundstelle
  - Klasse (`IDENTIFIER`/`LITERAL`/`TRUSTED_SQL`)
  - gewaehlte Behandlung
- dieselbe Matrix in den relevanten DDL-Regeln oder im Teilplan selbst
  referenzierbar festhalten
- Ziel ist nicht mehr "irgendwo escapen", sondern eine nachvollziehbare
  und reviewbare Vertragsbasis

### 5.2 DDL-Generatoren fuer echte Identifier-/Literalfaelle nachziehen

- `SqliteDdlGenerator` fuer `AddGeometryColumn(...)` umstellen:
  - keine rohe Einbettung von Tabellen- und Spaltennamen mehr
  - SQL-String-Literale aus korrekt serialisierten Namen erzeugen
- angrenzende Hilfslogik pro Dialekt pruefen, ob vergleichbare
  Identifier-in-String-Literal-Faelle existieren
- wo bereits zentrale Helfer (`quoteIdentifier`, Literal-Escaping) da
  sind, diese wiederverwenden statt neue Sonderpfade einzufuehren

### 5.3 Trusted-Input-Grenze pro SQL-Fragment absichern

- fuer folgende Felder die bewusste `TRUSTED_SQL`-Behandlung im Code
  oder in naher Doku verankern:
  - `constraint.expression`
  - `typeDef.check`
  - `partition.from`
  - `partition.to`
  - `partition.values`
  - `trigger.condition`
  - View-/Function-/Procedure-Bodies
- Kommentare und Tests muessen klar machen:
  - keine automatische Quoting-/Escaping-Pflicht
  - Aenderungen an diesen Feldern duerfen nicht "zur Sicherheit"
    umformatiert werden
- fuer Routine-/View-Bodies bleibt die bestehende
  Query-/Body-Transformation erlaubt, aber sie ist kein
  Sicherheitsfilter

### 5.4 MySQL-Generator von sichtbaren TODO-Pfaden befreien

- `MysqlDdlGenerator` fuer die vier bekannten Stellen umbauen:
  - Composite Types
  - Sequences
  - EXCLUDE-Constraints
  - nicht unterstuetzte Index-Typen
- an keiner dieser Stellen duerfen neue `DdlStatement("-- TODO ...")`
  mehr entstehen
- stattdessen:
  - Composite Types -> `ACTION_REQUIRED` + `SkippedObject`
  - Sequences -> `ACTION_REQUIRED` + `SkippedObject`
  - EXCLUDE-Constraints -> `ACTION_REQUIRED` ohne `SkippedObject`
  - nicht unterstuetzte Index-Typen -> `WARNING` ohne `SkippedObject`
- bestehende Codes und Hinweise moeglichst beibehalten, damit JSON- und
  Report-Consumer keinen unnoetigen Diagnosebruch sehen

Praezisierung:

- bei Inline-Faellen wie EXCLUDE-Constraints darf die umgebende
  Tabellen-DDL weiter generiert werden
- bei vollstaendig unstuetzten Objekten darf die DDL-Liste leer bleiben,
  solange Diagnose und Skip sauber mitlaufen

### 5.5 No-TODO-Pruefschritt fuer den Generatorpfad verankern

- ein expliziter Such- und Review-Schritt stellt sicher, dass im
  betroffenen `MysqlDdlGenerator`-Pfad keine sichtbaren TODO-Emitter
  verbleiben
- Pflichtsuche mindestens nach:
  - `DdlStatement("-- TODO`
  - `toTodoComment()`
- betroffen ist fuer 6.5 verbindlich:
  - `MysqlDdlGenerator`
- Suchtreffer in den Routine-Helfern werden in 6.5 bewusst nur
  dokumentiert, nicht als Abschlussblocker behandelt
- 6.5 gilt erst als abgeschlossen, wenn diese Suche fuer den
  `MysqlDdlGenerator`-Renderpfad keine aktiven TODO-Call-Sites mehr
  ergibt
- zusaetzlich ist mindestens ein negativer Output-Test Pflicht:
  - ein MySQL-Fall mit den vier bekannten Luecken darf im gesamten
    generierten SQL kein `-- TODO` mehr enthalten
  - derselbe Test deckt implizit Rueckfaelle auf
    `toTodoComment()`-basierte Renderpfade ab

### 5.6 SQL-, JSON- und Report-Sicht auf strukturierte Diagnosen abgleichen

- sicherstellen, dass der Wegfall sichtbarer TODO-Kommentare nicht zu
  Diagnoseverlust fuehrt
- Textausgabe, JSON und YAML-Report muessen fuer dieselben MySQL-Faelle
  weiterhin:
  - Code
  - Objektname
  - Grund
  - optionalen Hint
  transportieren
- 6.4 bleibt der eigentliche Output-Arbeitsstrang; 6.5 definiert hier
  aber die fachliche Erwartung, dass SQL selbst kein TODO-Kanal mehr
  ist
- diese Verifikation ist erst voll abschliessbar, wenn 6.4 den
  Split-Outputvertrag fuer JSON und Report fertiggezogen hat

Praezisierung fuer Split:

- dieselbe Diagnose-Matrix gilt auch bei aktivem `split pre-post`
- weder `renderPhase(PRE_DATA)` noch `renderPhase(POST_DATA)` duerfen
  aus den vier MySQL-Faellen sichtbare TODO-Kommentarzeilen enthalten
- Split-JSON und Split-Report muessen dieselben strukturierten
  Diagnoseeintraege wie der Single-Fall tragen
- der Split-Pfad ist kein nachgelagerter Sonderfall:
  - dieselben Codes
  - dieselben `phase`-Werte
  - dieselbe `action_required`-/`warning`-/`skipped_objects`-Zaehllogik

### 5.7 Doku und Quality-Basis nachziehen

- `docs/user/quality.md` muss die vier offenen MySQL-`-- TODO`-Plaetze nach
  Umsetzung nicht mehr als offenen Punkt fuehren
- die DDL-Regeln fuer `TRUSTED_SQL` vs. Identifier-/Literalpfade
  muessen an einer dauerhaften Stelle dokumentiert sein
- sofern `spec/cli-spec.md` oder Generator-Regeln strukturierte
  Diagnosen im SQL-Kontext erwaehnen, muss der Wegfall sichtbarer TODO-
  Kommentare dort konsistent gespiegelt werden

---

## 6. Verifikation

### 6.1 Pflichtfaelle fuer DDL-Haertung

- Boundary-Test fuer CHECK-Expressions:
  - Ausdruck bleibt inhaltlich unveraendert
  - keine zusaetzliche Quote-/Escape-Manipulation
- Boundary-Test fuer Domain-/Type-Checks unter PostgreSQL:
  - `typeDef.check` bleibt unveraendert eingebettet
- Boundary-Test fuer Partition-Ausdruecke:
  - `from`/`to`/`values` bleiben `TRUSTED_SQL`
- Boundary-Test fuer Trigger-`WHEN`:
  - PostgreSQL und SQLite serialisieren die Bedingung unveraendert als
    Ausdruck
- Boundary-Test fuer SpatiaLite-Identifier:
  - Tabellen-/Spaltennamen mit einfachen Quotes oder Sonderzeichen
    werden im `AddGeometryColumn(...)`-Aufruf korrekt als SQL-Literale
    serialisiert

### 6.2 Pflichtfaelle fuer MySQL-`-- TODO`-Bereinigung

- keine verbleibenden sichtbaren MySQL-`-- TODO`-Kommentare im
  `MysqlDdlGenerator`-Output
- Composite-Type-Fall erzeugt genau `ACTION_REQUIRED` +
  `SkippedObject` mit `phase = PRE_DATA`, aber keine SQL-
  Kommentarzeile
- Sequence-Fall erzeugt genau `ACTION_REQUIRED` + `SkippedObject`,
  jeweils mit `phase = PRE_DATA`, aber keine SQL-Kommentarzeile
- EXCLUDE-Constraint-Fall behaelt Tabellen-DDL und liefert genau
  eine `ACTION_REQUIRED`-Note mit `phase = PRE_DATA` ohne
  `SkippedObject`
- nicht unterstuetzter Index-Typ erzeugt keine Pseudo-SQL-Zeile und
  genau eine `WARNING`-Note mit `phase = PRE_DATA` ohne `SkippedObject`
- Suchtest oder Guard-Test fuer den Generatorpfad:
  - keine aktiven `DdlStatement("-- TODO ...")`-Emitter im
    `MysqlDdlGenerator`
  - keine aktiven `toTodoComment()`-Aufrufe im betroffenen
    `MysqlDdlGenerator`-Renderpfad
  - Treffer in Routine-Helfern sind fuer 6.5 kein Fail, sondern
    dokumentierter Folgebedarf
- negativer Output-Test:
  - gesamter MySQL-Generatoroutput fuer einen kombinierten TODO-Fall
    enthaelt nirgendwo `-- TODO`

### 6.3 Pflichtfaelle fuer Diagnosekonsistenz

- derselbe MySQL-Fall bleibt in Text, JSON und Report sichtbar
- Wegfall des SQL-Kommentars reduziert nur den SQL-Kanal, nicht die
  strukturierte Diagnosebasis
- `warnings`, `action_required` und `skipped_objects` bleiben fuer die
  betroffenen Faelle konsistent zaehlbar
- Split-Pflichtfall:
  - `--split pre-post` plus MySQL-TODO-Fall erzeugt in keiner Phase
    sichtbare TODO-Zeilen
  - dieselbe strukturierte Diagnose erscheint weiterhin in Text, JSON
    und Report
  - `phase = pre-data` bleibt in Split-JSON und Split-Report identisch
    serialisiert

### 6.4 Rueckwaertskompatibilitaet und Regression

- Golden Masters ausserhalb der gezielt gehaerteten Stellen bleiben
  unveraendert
- Single-Output bleibt semantisch stabil; nur die bewusst entfernten
  TODO-Kommentare und echte Haertungsfaelle duerfen Output-Diffs
  erzeugen
- betroffene DDL-Generator-Tests fuer PostgreSQL, MySQL und SQLite
  laufen gruen

Erwuenschte Zusatzfaelle:

- eigener Test fuer `ManualActionRequired.toTodoComment()`, falls der
  Helper vorerst im Code verbleibt, damit sein Restvertrag bewusst ist
- Golden-Master-Fixture fuer einen MySQL-Fall mit mehreren
  `ACTION_REQUIRED`-Eintraegen ohne sichtbare TODO-SQL

---

## 7. Betroffene Codebasis

Direkt betroffen:

- `docs/user/quality.md`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/ManualActionRequired.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresRoutineDdlHelper.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRoutineDdlHelper.kt`
- Tests unter `adapters/driven/driver-*/src/test/...`

Wahrscheinlich mit betroffen:

- `adapters/driven/formats/src/test/resources/fixtures/ddl/...`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateHelpers.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- begleitende JSON-/Report-Contract-Tests

Praezisierung:

- fuer `TransformationReportWriter`, `SchemaGenerateHelpers` und
  `SchemaGenerateRunner` erzwingt 6.5 nicht automatisch eigene
  Codeaenderungen
- diese Module sind in 6.5 primaer fuer Konsistenzpruefung gegen den in
  6.4 definierten Outputvertrag relevant
- Codeaenderungen dort sind nur noetig, wenn die Verifikation zeigt,
  dass die 6.4-Kanaele die 6.5-Diagnose-Matrix nicht konsistent
  transportieren

Dokumentation:

- `docs/planning/implementation-plan-0.9.2.md`
- `docs/planning/ImpPlan-0.9.2-6.4.md`
- DDL-Regel- oder Generator-Dokumentation, sofern vorhanden

---

## 8. Risiken und Abgrenzung

### 8.1 Blindes Escaping kann fachlich gueltige SQL-Fragmente zerstoeren

Wenn `TRUSTED_SQL`-Felder spaeter pauschal gequoted oder escaped
werden, brechen gueltige CHECK-, Partition-, Trigger- oder Routine-
Koerper.

Mitigation:

- Klassifikation hart dokumentieren
- gezielte Boundary-Tests je Feldtyp
- keine "Sicherheitsvereinheitlichung" ohne Feldklassifikation

### 8.2 Wegfall sichtbarer TODO-Kommentare kann operativ uebersehen werden

Wenn Teams sich an SQL-Kommentaren orientiert haben, koennte der neue
Pfad zuerst stiller wirken.

Mitigation:

- Text-, JSON- und Report-Diagnosen konsistent sichtbar halten
- Tests gegen `ACTION_REQUIRED`-/`SkippedObject`-Praesenz statt gegen
  Kommentar-SQL ausrichten
- Doku explizit auf strukturierten Diagnosevertrag umstellen

### 8.3 Golden Masters koennen unnoetig breit kippen

DDL-Haertung beruehrt viele Renderstellen; ohne harte Begrenzung droht
unnuetige Churn.

Mitigation:

- nur echte Haertungsfaelle aendern
- Trusted-Input-Faelle ohne Not renderstabil lassen
- Fixture-Updates eng an die dokumentierte Regelmatrix koppeln

### 8.4 6.5 fuehrt keine Vollvalidierung fuer untrusted SQL-Dateien ein

Die Trusted-Input-Einordnung bleibt eine bewusste Produktannahme:
Quellschema oder Schema-Datei werden fuer diese Felder als kontrollierter
Input betrachtet.

Mitigation:

- Grenze explizit dokumentieren
- keine falsche Sicherheit durch halbgares Body-Escaping suggerieren
- weitergehende Validierung als separates spaeteres Arbeitspaket
  behandeln
