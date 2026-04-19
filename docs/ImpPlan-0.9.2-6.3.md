# Implementierungsplan: 0.9.2 - Arbeitspaket 6.3 Generator-Zuordnung pro Dialekt und Objekttyp festziehen

> **Milestone**: 0.9.2 - Beta: DDL-Phasen und importfreundliche
> Schema-Artefakte
> **Arbeitspaket**: 6.3 (Generator-Zuordnung pro Dialekt und Objekttyp
> festziehen)
> **Status**: Draft (2026-04-19)
> **Referenz**: `docs/implementation-plan-0.9.2.md` Abschnitt 4.7,
> Abschnitt 5.3, Abschnitt 6.3, Abschnitt 6.3.1, Abschnitt 7,
> Abschnitt 8 und Abschnitt 9.1;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DependencyInfo.kt`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ViewDefinition.kt`;
> `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeParser.kt`;
> `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeBuilder.kt`;
> `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`;
> `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresRoutineDdlHelper.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`;
> `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`;
> `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRoutineDdlHelper.kt`;
> `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaReader.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`;
> `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt`.

---

## 1. Ziel

Arbeitspaket 6.3 zieht die fachliche Split-Zuordnung in die Generatoren
selbst:

- alle relevanten DDL-Objekttypen erhalten pro Dialekt eine feste
  Zuordnung zu `PRE_DATA` oder `POST_DATA`
- Views mit Routine-Abhaengigkeiten werden belastbar von Views ohne
  solche Abhaengigkeiten getrennt
- unsichere Split-Faelle werden nicht still in `pre-data` durchgereicht,
  sondern konservativ nach `post-data` verschoben oder mit Exit 2
  abgebrochen
- `SkippedObject` und `ACTION_REQUIRED` tragen dieselbe Phasenlogik wie
  der ersetzte Objekttyp

6.3 liefert damit den fachlichen Kern des Splits. 6.4 kann erst dann
saubere SQL-, JSON- und Report-Artefakte ausgeben, wenn die Generatoren
die Zuordnung bereits korrekt markieren.

Nach 6.3 soll klar gelten:

- `pre-data` ist fuer sich ausfuehrbar
- Trigger, Functions und Procedures tauchen nie in `pre-data` auf
- Tabellen, Indizes und vergleichbare Struktur-DDL tauchen nie in
  `post-data` auf
- View-Zuordnung ist nicht mehr bloesse Regex-Heuristik ueber
  View→View-Kanten, sondern beruecksichtigt auch direkte
  Routine-Abhaengigkeiten

---

## 2. Ausgangslage

Der aktuelle gemeinsame Generatorpfad in
`AbstractDdlGenerator.generate(...)` baut die DDL noch in genau einer
linearen Reihenfolge auf:

- Header
- Custom Types
- Sequences
- Tabellen
- Indizes
- FK-Nachzuegler
- Views
- Functions
- Procedures
- Triggers

Die neue Phasenfaehigkeit des DDL-Modells aus 6.1 reicht dafuer allein
noch nicht:

- Generatoren muessen die Phasenmarkierung explizit setzen
- `SkippedObject` und `ManualActionRequired` muessen dieselbe
  Objektphasenlogik spiegeln
- `sortViewsByDependencies()` in `AbstractDdlGenerator` kennt bisher nur
  View→View-Abhaengigkeiten
- `DependencyInfo` kennt aktuell:
  - `tables`
  - `views`
  - `columns`
  aber noch keine `functions`

Der kritische offene Punkt fuer den Split ist damit fachlich klar:

- ein View, der eine Function benoetigt, darf nicht in `pre-data`
  landen, wenn diese Function erst in `post-data` erzeugt wird
- genau diese Kante ist heute im dateibasierten Schema-Modell und in der
  View-Sortierung noch nicht belastbar modelliert

Gleichzeitig darf 6.3 den Bestandspfad nicht ueberziehen:

- ohne `--split` bleibt die Zuordnung fuer die sichtbare Ausgabe
  irrelevant
- 0.9.2 braucht keine vollstaendige SQL-AST-Analyse
- direkte View→Function-Kanten reichen fuer den Milestone, solange
  bestehende View→View-Sortierung aufbaut und dadurch transitive Faelle
  nachzieht

---

## 3. Scope fuer 6.3

### 3.1 In Scope

- alle drei DDL-Generatoren und ihre Routine-Helfer auf explizite
  Phasenmarkierung umstellen
- feste Zuordnung fuer:
  - Header
  - Custom Types
  - Sequences
  - Tabellen
  - Indizes
  - FK-Nachzuegler
  - Views ohne Routine-Abhaengigkeit
  - Views mit Routine-Abhaengigkeit
  - Functions
  - Procedures
  - Triggers
- `SkippedObject`/`ACTION_REQUIRED` mit der Phase des ersetzten
  Objekttyps anreichern
- `DependencyInfo` um `functions` erweitern
- Parser/Builder fuer YAML/JSON-Schema-Dateien auf
  `dependencies.functions` erweitern
- Schema-Reader-Strategie fuer View→Function-Kanten pro Dialekt
  festziehen
- konservative Exit-2-Abbrueche fuer unsicher aufloesbare Split-Faelle

### 3.2 Bewusst nicht Teil von 6.3

- finale SQL-/JSON-/Report-Ausgabe fuer den Split
- vollstaendige AST-Analyse von View-Queries
- allgemeine transitive Routine-Abhaengigkeitsanalyse ueber beliebig
  tiefe Graphen
- weitere Phasen jenseits von `PRE_DATA` und `POST_DATA`

Praezisierung:

6.3 loest "welche DDL-Objekte gehoeren fachlich in welche Phase?", nicht
"wie werden diese Phasen danach sichtbar gerendert?".

---

## 4. Leitentscheidungen

### 4.1 Die Objektzuordnung wird fest verdrahtet

Verbindliche Folge:

- folgende Objekttypen gehoeren immer zu `PRE_DATA`:
  - Header
  - Custom Types
  - Sequences
  - Tabellen
  - Indizes
  - FK-Nachzuegler
  - Views ohne direkte oder transitive Abhaengigkeit auf
    `POST_DATA`-Views
- folgende Objekttypen gehoeren immer zu `POST_DATA`:
  - Functions
  - Procedures
  - Triggers
  - Views mit Routine-Abhaengigkeit

6.3 laesst diese Zuordnung nicht pro Dialekt offen; Unterschiede liegen
nur in der Generierung der Statements, nicht in ihrer Zielphase.

Praezisierung:

- die View-Zuordnung erfolgt zweistufig:
  1. direkte Bestimmung von Views mit Routine-Abhaengigkeit
  2. transitive Propagation ueber View→View-Kanten
- wenn View A von View B abhaengt und View B in `POST_DATA` liegt,
  wechselt auch View A nach `POST_DATA`, selbst wenn A keine direkte
  Routine-Kante hat
- damit bleibt `pre-data` fuer sich ausfuehrbar; die bestehende
  View-Toposortierung ordnet Views danach nur noch **innerhalb** ihrer
  Phase

### 4.2 `pre-data` muss fuer sich ausfuehrbar bleiben

Verbindliche Folge:

- ein Objekt darf nur dann in `PRE_DATA` bleiben, wenn seine Ausfuehrung
  keine spaeteren `POST_DATA`-Objekte voraussetzt
- Views mit Function-Abhaengigkeiten wechseln deshalb nach `POST_DATA`
- im Zweifel ist eine konservative Verschiebung nach `POST_DATA`
  zulaessig; ein riskantes Belassen in `PRE_DATA` nicht

### 4.3 Unsichere Split-Faelle brechen hart ab

Verbindliche Folge:

- wenn bei aktivem Split keine belastbare View-Zuordnung moeglich ist,
  endet der Lauf mit Exit 2
- die Fehlermeldung benennt den betroffenen View konkret
- ohne `--split` wird diese zusaetzliche Analyse nicht als
  sichtbarer Fehlerpfad erzwungen

Architekturentscheidung fuer den Signalfluss:

- der Generator kennt nicht selbst den sichtbaren CLI-Splitvertrag
- stattdessen markiert die Generatoranalyse nicht belastbar
  aufloesbare Split-Faelle ueber dedizierte phasenlose Diagnoseeintraege
  in `DdlResult.globalNotes`
- diese Diagnoseeintraege bleiben `TransformationNote`-Eintraege mit:
  - `type = NoteType.ACTION_REQUIRED`
  - einem dedizierten Split-Code fuer nicht belastbare View-Zuordnung
  - `phase = null`
- der Runner wertet diese Diagnosen nur dann als Exit 2 aus, wenn Split
  aktiv ist
- die Identifikation im Runner erfolgt ueber diesen dedizierten
  Diagnose-Code, nicht ueber freie Textsuche
- ohne Split bleiben dieselben Diagnosen sichtbar, aber nicht
  exit-bestimmend

Praezisierung der Entscheidungsreihenfolge:

- **Level A - belastbar**:
  - explizite `dependencies.functions` aus dem Schema-Modell
  - Reader-Katalogdaten mit direkter View→Function-/Routine-Kante
  -> Ergebnis: `POST_DATA`
- **Level B - konservativ ausreichend**:
  - keine direkte Katalogkante, aber Heuristik findet einen plausiblen
    Funktionsaufruf gegen bekannte Schema-Funktionen
  -> Ergebnis: `POST_DATA`
- **Level C - nicht belastbar aufloesbar**:
  - weder Modell noch Reader noch Heuristik liefern eine ausreichend
    eindeutige Entscheidung
  -> Ergebnis bei aktivem Split: Exit 2

Damit gilt fuer 0.9.2 explizit:

- `POST_DATA` ist der konservative Default bei plausibler
  Routine-Abhaengigkeit
- Exit 2 ist nur fuer echte Rest-Unsicherheit vorgesehen, nicht fuer
  jeden fehlenden Katalognachweis

### 4.4 `DependencyInfo.functions` ist die modellierte Routine-Kante

Verbindliche Folge:

- `DependencyInfo` wird additiv um `functions: List<String>` erweitert
- Eintraege werden in 0.9.2 in normalisierter, unqualifizierter
  Form gespeichert:
  - Funktionsname ohne Schema-Praefix
  - ohne Signatur
  - case-insensitive Vergleich nach derselben Normalisierung
- YAML-/JSON-Codecs muessen dieses Feld lesen und schreiben koennen
- Schema-Reader sollen bevorzugt dieses Feld belastbar populieren, statt
  die Split-Zuordnung spaeter aus reinem SQL-Text neu zu erraten

Praezisierung:

- 0.9.2 nimmt bewusst an, dass `SchemaDefinition` selbst kein
  Multi-Schema-Modell transportiert; deshalb reicht fuer diesen
  Milestone der normalisierte Funktionsname als Matching-Schluessel
- sollte eine Quelle mehrere kollidierende Routinen liefern, die nach
  dieser Normalisierung nicht eindeutig sind, gilt der Fall fuer Split
  als nicht belastbar aufloesbar

### 4.5 Reader-Kataloge sind primaere Quelle, Regex nur Fallback

Verbindliche Folge:

- PostgreSQL nutzt Systemkataloge fuer View→Function-Kanten
- MySQL nutzt `INFORMATION_SCHEMA.VIEW_ROUTINE_USAGE`, wo verfuegbar
- SQLite faellt mangels Katalog auf Query-Analyse gegen bekannte
  Funktionsnamen im Schema zurueck
- Regex-/Token-Heuristiken sind nur Fallback fuer fehlende
  Metadatenquellen oder dateibasierte Schemas

Praezisierung fuer den SQLite-/Fallback-Pfad:

- die Heuristik arbeitet nicht ueber rohe Substring-Suche, sondern ueber
  einfache SQL-Tokenisierung mit mindestens:
  - Entfernung oder Ignorieren von String-Literalen
  - Entfernung oder Ignorieren von SQL-Kommentaren
  - Erkennung von Identifier- oder `schema.identifier`-Token gefolgt von
    `(`
- als Funktionskandidat zaehlt nur ein bekannter Funktionsname im
  aufrufartigen Kontext `name(`
- Treffer in Kommentaren, String-Literalen oder blossen Alias-/Spalten-
  Namen ohne Aufrufkontext duerfen nicht als Routine-Kante gewertet
  werden
- wenn die einfache Tokenisierung keinen eindeutigen Befund erlaubt,
  faellt der Fall unter Abschnitt 4.3 Level C

### 4.6 Trigger-Familien muessen als Einheit in `POST_DATA` landen

Verbindliche Folge:

- PostgreSQL-Trigger und ihre erzeugten Hilfsfunctions bleiben gemeinsam
  in `POST_DATA`
- MySQL- und SQLite-Trigger bleiben ebenfalls durchgaengig
  `POST_DATA`
- `ACTION_REQUIRED` oder `SkippedObject` fuer nicht erzeugbare Trigger
  muessen dieselbe `POST_DATA`-Phasenlogik tragen

### 4.7 Ohne Split bleibt die Analyse nicht massgeblich

Verbindliche Folge:

- 6.3 darf den bestehenden Single-Fall nicht mit neuen Split-spezifischen
  Exit-2-Fehlern ueberziehen
- zusaetzliche Unsicherheitsabbrueche gelten nur fuer aktive
  Split-Laeufe
- additive Modell- und Reader-Anreicherungen duerfen im Single-Fall
  sichtbar folgenlos bleiben

---

## 5. Konkrete Arbeitsschritte

### 5.1 `DependencyInfo` und Codecs erweitern

- `DependencyInfo` um `functions: List<String> = emptyList()` erweitern
- `SchemaNodeParser` auf `dependencies.functions` erweitern
- `SchemaNodeBuilder` auf `dependencies.functions` erweitern
- additive Rueckwaertskompatibilitaet fuer bestehende Schema-Dateien
  beibehalten:
  - fehlendes Feld bleibt `emptyList()`
  - bestehende Dateien ohne Funktionsblock bleiben lesbar
- vorhandene Werte in `dependencies.functions` vor Verwendung
  normalisieren:
  - leere Eintraege verwerfen
  - Duplikate nach Normalisierung entfernen
  - nicht-stringfoermige oder strukturell ungueltige Eintraege als
    inkonsistente Eingabedaten behandeln
- inkonsistente Legacy-Daten loesen fuer aktive Split-Laeufe keinen
  stillen Guess aus:
  - bei eindeutig sanitisierbaren Daten -> sanitisiert weiterverwenden
  - bei nicht eindeutig interpretierbaren Daten -> Exit 2

### 5.2 Gemeinsame Generator-Zuordnung in `AbstractDdlGenerator` ziehen

- die heute lineare Erzeugungsreihenfolge um explizite Phasenmarkierung
  ergaenzen
- `generateHeader(...)`, Tabellen-, Index-, Sequence-, View-, Function-,
  Procedure- und Trigger-Pfade auf die feste Zuordnung aus Abschnitt 4
  ziehen
- `handleCircularReferences(...)` bzw. FK-Nachzuegler explizit als
  `PRE_DATA` markieren
- View-Phasenmarkierung mechanisch so umsetzen, dass die abstrakte
  Signatur `generateViews(...)` unveraendert bleiben kann:
  - Views zunaechst auf Generator-Ebene in `PRE_DATA`- und
    `POST_DATA`-Maps aufteilen
  - `generateViews(...)` danach getrennt pro Phase aufrufen
  - die jeweils zurueckgegebenen `DdlStatement`s gesammelt mit der Phase
    des aufrufenden Buckets markieren
  - `SkippedObject`s aus beiden Aufrufen ebenfalls bucketweise
    nachmarkieren:
    - entweder ueber separate Skip-Listen pro Aufruf
    - oder ueber Vergleich der Listenlaenge vor/nach jedem Aufruf

Wichtig:

- die Zuordnung muss aus dem Generator kommen, nicht erst aus 6.4-
  Rendererlogik
- `pre-data + post-data` darf semantisch vom Single-Gesamtbild
  abweichen, aber nicht fachlich defekt sein
- die bestehende View-Toposortierung bleibt nuetzlich fuer die Ordnung
  innerhalb einer Phase, ersetzt aber nicht die transitive
  Phasen-Propagation

### 5.3 `SkippedObject` und `ManualActionRequired` angleichen

- jeder Skip und jedes `ACTION_REQUIRED` bekommt die Phase des
  Objekttyps, den der Eintrag ersetzt
- Beispiele:
  - nicht unterstuetzter Composite Type -> `PRE_DATA`
  - nicht unterstuetzter Trigger -> `POST_DATA`
  - MySQL-/SQLite-Sequence-Workarounds -> `PRE_DATA`

### 5.4 View→Function-Analyse pro Quelle festziehen

PostgreSQL:

- View→Function-Kanten aus Systemkatalogen (`pg_depend`, `pg_rewrite`)
  bevorzugt auslesen

MySQL:

- `INFORMATION_SCHEMA.VIEW_ROUTINE_USAGE` als primaere Quelle nutzen
- Query-basierte Heuristik nur als Fallback einsetzen

SQLite:

- mangels Systemkatalog Query-Analyse gegen bekannte Funktionsnamen im
  Schema verwenden
- nur aufrufartige Token `name(` oder `schema.name(` nach Entfernung von
  Kommentaren und String-Literalen auswerten
- wenn mehrere normalisierte Kandidaten kollidieren oder der Query-Text
  keinen eindeutigen Aufrufkontext hergibt -> nicht belastbar

Dateibasierte Schemas:

- `dependencies.functions` aus YAML/JSON als primaere Quelle verwenden
- fehlt der Block, greift die konservative Heuristik
- nach Bestimmung direkter Routine-Kanten die `POST_DATA`-Phase
  anschliessend ueber View→View-Abhaengigkeiten transitiv propagieren

### 5.5 Konservative Zuordnung und Exit-2-Pfade umsetzen

- Entscheidungsreihenfolge festziehen:
  1. explizites `view.dependencies.functions` nach Normalisierung
     -> wenn nicht leer, `POST_DATA`
  2. direkte Reader-Katalogkante
     -> `POST_DATA`
  3. konservative Heuristik mit aufrufartigem Treffer gegen bekannte
     Funktionsnamen
     -> `POST_DATA`
  4. Restfall ohne belastbare Entscheidung bei aktivem Split
     -> Exit 2
- nach diesen direkten Entscheidungen folgt die transitive Propagation:
  - jeder View, der direkt oder indirekt von einem bereits als
    `POST_DATA` markierten View abhaengt, wird ebenfalls `POST_DATA`
- nicht belastbar aufloesbare Views werden im Generator ueber dedizierte
  globale Diagnosen in `globalNotes` markiert; der Runner macht daraus
  nur bei aktivem Split einen Exit-2-Fehler
- die Fehlermeldung muss den betroffenen View benennen und die Ursache
  als nicht sicher aufloesbare Routine-Abhaengigkeit kenntlich machen

### 5.6 Tests ergaenzen

Mindestens abzudecken:

- Trigger erscheinen nie in `PRE_DATA`
- Functions und Procedures erscheinen nie in `PRE_DATA`
- Tabellen, Indizes und FK-Nachzuegler erscheinen nie in `POST_DATA`
- Views ohne Routine-Abhaengigkeit bleiben in `PRE_DATA`
- Views mit Routine-Abhaengigkeit landen in `POST_DATA`
- Views, die von `POST_DATA`-Views abhaengen, propagieren transitiv nach
  `POST_DATA`
- PostgreSQL-Trigger-Hilfsfunction und `CREATE TRIGGER` teilen dieselbe
  `POST_DATA`-Zuordnung
- `DependencyInfo.functions` wird durch Parser und Builder
  rueckwaertskompatibel gelesen und geschrieben
- `DependencyInfo.functions` wird normalisiert und bei Duplikaten oder
  leeren Eintraegen stabil sanitisiert
- nicht eindeutig interpretierbare `dependencies.functions`-Legacy-Daten
  fuehren im Split-Fall zu Exit 2 statt zu stiller Fehlsortierung
- `SkippedObject` und `ACTION_REQUIRED` erben die Phase des ersetzten
  Objekttyps
- Skips aus getrennten `generateViews(...)`-Aufrufen werden konsistent
  mit der Phase des jeweiligen Buckets nachmarkiert
- Split-unsichere Views liefern bei aktivem Split Exit 2 mit View-Name
- derselbe View mit plausibler, aber nur heuristisch belegter
  Routine-Kante landet konservativ in `POST_DATA`, nicht in Exit 2
- derselbe unsichere View bleibt ohne Split kein neuer sichtbarer
  Fehlerpfad
- derselbe unsichere View bleibt im Single-Pfad erfolgreich verarbeitbar
  und wird hoechstens als Diagnose markiert

---

## 6. Verifikation

Pflichtfaelle fuer 6.3:

- Trigger erscheinen nie in `pre-data`
- Functions und Procedures erscheinen nie in `pre-data`
- Tabellen und Indizes erscheinen nie in `post-data`
- Views ohne Routine-Abhaengigkeit bleiben in `pre-data`
- Views mit Routine-Abhaengigkeit landen in `post-data`
- Views, die von `post-data`-Views abhaengen, landen ebenfalls in
  `post-data`
- `pre-data + post-data` ist semantisch aequivalent zum Single-
  Gesamtbild; Byte-Gleichheit ist nicht der Massstab
- PostgreSQL-Triggerfunktion plus Trigger landen als Einheit in
  `post-data`
- MySQL- und SQLite-Sequence-Skips oder `ACTION_REQUIRED` tragen
  `PRE_DATA`
- `DependencyInfo.functions` bleibt fuer bestehende Dateien additiv und
  rueckwaertskompatibel
- `DependencyInfo.functions` verwendet normalisierte unqualifizierte
  Funktionsnamen; Kollisionsfaelle ohne eindeutige Normalisierung werden
  im Split-Fall nicht still hingenommen
- unsichere View-Split-Faelle enden mit Exit 2 und benennen den View
  explizit
- ein heuristisch plausibler Funktionsaufruf fuehrt konservativ zu
  `post-data`, nicht zu `pre-data`
- ohne `--split` wird dieselbe Unsicherheit nicht als neuer harter
  Nutzerfehler sichtbar
- derselbe unsichere View bleibt im Single-Pfad erfolgreich verarbeitbar
  und erzeugt keinen neuen Exit-2-Fehler
- der Exit-2-Signalfluss bleibt zweistufig:
  - Generator markiert den unsicheren View fachlich
  - Runner macht dies nur im aktiven Split-Lauf zu Exit 2

Erwuenschte Zusatzfaelle:

- dedizierte Split-Fixtures fuer MySQL- und SQLite-Trigger-Szenarien
- dateibasierte Schema-Fixtures mit `dependencies.functions`
- Reader-nahe Tests fuer PostgreSQL- und MySQL-Katalogabhaengigkeiten
- SQLite-Heuristiktests fuer View-Queries mit bekannten
  Funktionsnamen
- Single-/Split-Matrix fuer denselben unsicheren View-Fall

---

## 7. Betroffene Codebasis

Direkt betroffen:

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DependencyInfo.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ViewDefinition.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeParser.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeBuilder.kt`
- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt`
- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresRoutineDdlHelper.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlRoutineDdlHelper.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteRoutineDdlHelper.kt`

Wahrscheinlich indirekt mit betroffen:

- `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresSchemaReader.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`
- `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt`
- Generator-, Reader- und Codec-Tests unter:
  - `adapters/driven/driver-*/src/test/...`
  - `adapters/driven/driver-common/src/test/...`
  - `adapters/driven/formats/src/test/...`
  - `hexagon/core/src/test/...`
- Split-Ausgabe-Tests in 6.4, die auf der hier festgezogenen
  Generatorlogik aufbauen

---

## 8. Risiken und Abgrenzung

### 8.1 Ohne belastbare View-/Routine-Analyse wird `pre-data` wertlos

Risiko:

- ein View in `pre-data`, der eine Function aus `post-data` benoetigt,
  macht den Split operativ kaputt

Mitigation:

- `DependencyInfo.functions` modellieren
- Reader-Kataloge bevorzugen
- konservativ nach `POST_DATA` schieben oder mit Exit 2 abbrechen

### 8.2 Dateibasierte Schemas bleiben ohne Codec-Update blind

Risiko:

- selbst wenn Reader fuer Live-Datenbanken Function-Kanten kennen,
  bleiben YAML-/JSON-Schemas ohne `dependencies.functions` fuer den
  Split unpraezise

Mitigation:

- Parser und Builder im selben Arbeitspaket mitziehen
- additive Rueckwaertskompatibilitaet explizit absichern
- Sanitizing- und Split-Validierungsregeln fuer inkonsistente
  `dependencies.functions` festziehen

### 8.3 Dialekt-Fallbacks koennen zu aggressiv oder zu lax sein

Risiko:

- zu aggressive Fallbacks schieben zu viele Views nach `POST_DATA`
- zu laxe Fallbacks lassen defekte Views in `PRE_DATA`

Mitigation:

- fuer 0.9.2 konservative Bias zugunsten von `POST_DATA`
- unsichere Restfaelle mit Exit 2 statt stiller Fehlsortierung
- fuer SQLite/Fallbacks Mindest-Tokenisierung statt roher
  Substring-Suche festlegen

### 8.4 6.3 darf den Single-Fall nicht mit Split-Unsicherheiten belasten

Risiko:

- neue Analyse-Unsicherheiten koennten versehentlich auch normale
  Single-Laeufe brechen

Mitigation:

- harte Unsicherheitsabbrueche nur fuer aktive Split-Laeufe
- Single-Lauf weiterhin ohne neue Split-spezifische Fehlerpfade

### 8.5 Trigger-Gruppen koennen auseinanderfallen

Risiko:

- insbesondere bei PostgreSQL koennte die Hilfsfunction eines Triggers
  anders markiert werden als das `CREATE TRIGGER` selbst

Mitigation:

- Triggerfamilien als Einheit markieren
- dedizierte Tests fuer Triggerfunction plus Triggerstatement fuehren
