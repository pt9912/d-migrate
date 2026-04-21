# Implementierungsplan: 0.9.4 - Arbeitspaket 6.3 `Sequence-Default-Reverse ueber Trigger`

> **Milestone**: 0.9.4 - Beta: MySQL-Sequence Reverse-Engineering und Compare
> **Arbeitspaket**: 6.3 (`Phase D3: Sequence-Default-Reverse ueber Trigger`)
> **Status**: Draft (2026-04-21)
> **Referenz**: `docs/implementation-plan-0.9.4.md` Abschnitt 4.3 bis
> 4.5, Abschnitt 5.1, Abschnitt 5.4 bis 5.6, Abschnitt 6.3, Abschnitt 7
> und Abschnitt 9;
> `docs/ImpPlan-0.9.4-6.1.md`;
> `docs/ImpPlan-0.9.4-6.2.md`;
> `docs/mysql-sequence-emulation-plan.md` Abschnitt 6;
> `docs/ddl-generation-rules.md`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ColumnDefinition.kt`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DefaultValue.kt`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SchemaDefinition.kt`;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadNote.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueries.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceReverseSupport.kt`;
> `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReaderTest.kt`.

---

## 1. Ziel

Arbeitspaket 6.3 faltet sequence-basierte Spaltendefaults aus den in
MySQL erzeugten Support-Triggern zurueck in das neutrale Modell. D3
baut auf D1 (`Reader-Vertrag`) und D2 (`schema.sequences`) auf und
liefert damit den fehlenden zweiten Teil des Reverse-Pfads:
`DefaultValue.SequenceNextVal(sequenceName)` an den betroffenen
Spalten.

6.3 liefert vier konkrete Ergebnisse:

- kanonische Sequence-Support-Trigger werden robust als Supportobjekte
  erkannt
- betroffene `ColumnDefinition.default` werden zu
  `DefaultValue.SequenceNextVal(sequenceName)` materialisiert
- bestaetigte Support-Trigger werden aus `schema.triggers`
  unterdrueckt
- degradierte Triggerfaelle bleiben als spalten- oder
  sequence-bezogenes `W116` sichtbar, ohne falsche Zuordnungen zu
  raten

Nach 6.3 soll klar gelten:

- eine sequence-basierte Spalte wird im Reverse wieder als
  `SequenceNextVal` sichtbar
- derselbe Pfad funktioniert auch bei `includeTriggers = false`
- markerlose oder nicht kanonische Trigger werden nicht als
  `SequenceNextVal` fehlinterpretiert
- intakte Trigger verursachen keine Zusatztrigger im neutralen Ergebnis

---

## 2. Ausgangslage

Mit 6.1 und 6.2 ist die Infrastruktur fuer D3 bereits vorbereitet:

- der Reader kann Support-Trigger intern lesen, auch wenn
  `includeTriggers = false`
- `MysqlSequenceSupportSnapshot` traegt `triggerStates` und
  `SupportDiagnostic`
- `schema.sequences` ist nach D2 bereits materialisiert
- `W116`-Aggregation und `ReverseScope` sind als Reader-Vertrag
  vorhanden

Was nach 6.2 noch fehlt:

- echte Spaltenzuordnung `table.column -> sequenceName`
- Anreicherung von `ColumnDefinition.default` mit
  `DefaultValue.SequenceNextVal(sequenceName)`
- Unterdrueckung bestaetigter Support-Trigger aus `schema.triggers`
- robuste Trigger-Normalisierung statt der heutigen Minimalheuristik

Aktueller Scaffold-Gap vor D3:

- `listPotentialSupportTriggers(...)` arbeitet heute nur mit Name,
  Timing/Event, Markertext, regexbasierter Spaltenextraktion und
  einfachem `dmg_nextval`-Token; der referenzierte Sequence-Key aus
  `dmg_nextval('<sequence>')` wird in D1 bewusst noch nicht explizit
  extrahiert
- `buildSupportDiagnostics(...)` schluesselt degradierte Triggerfaelle
  zwar bereits ueber `ColumnDiagnosticKey(...)`, nutzt dabei aber noch
  keinen durchgaengig bestaetigten Spaltenschluessel; wenn die
  regexbasierte Spaltenextraktion leer bleibt, faellt der aktuelle
  Code noch auf den Triggernamen als Spaltenersatz zurueck
- `filterSupportTriggers(...)` unterdrueckt nur bestaetigte Trigger,
  ohne dass bisher Spaltendefaults gesetzt werden
- `SchemaDefinition` wird noch ohne nachtraegliche
  `SequenceNextVal`-Anreicherung der Spalten gebaut

Konsequenz ohne 6.3:

- `schema.sequences` sind zwar sichtbar, aber die betroffenen Spalten
  bleiben ohne neutralen Sequence-Default
- Compare bleibt auf Tabellenebene noisy, obwohl die Sequence selbst
  bereits korrekt reverse-bar ist

---

## 3. Scope fuer 6.3

### 3.1 In Scope

- D3 setzt D1 und D2 voraus und nutzt deren:
  - `ReverseScope`
  - `schema.sequences`
  - Support-Snapshot
  - Diagnose- und Gating-Vertrag
- robuste Erkennung kanonischer Sequence-Support-Trigger
- Aufbau einer Spaltenzuordnung `table.column -> sequenceName`
- Anreicherung von `ColumnDefinition.default` zu
  `DefaultValue.SequenceNextVal(sequenceName)`
- Unterdrueckung bestaetigter Support-Trigger aus `schema.triggers`
- Aggregation triggerbezogener `W116` auf Spaltenebene
- Tests fuer Happy-Path, Markerverlust, Nicht-Kanonizitaet und
  Metadatenzugriffsfehler

### 3.2 Bewusst nicht Teil von 6.3

- Compare-Renderer- oder Exit-Code-Aenderungen
- heuristische markerlose Trigger-Rekonstruktion
- aggressive Unterdrueckung aehnlich benannter Nutzertrigger
- neue oeffentliche Reverse-Flags
- Trigger-Rewrite oder Reparatur bestehender DB-Objekte

Praezisierung:

6.3 loest "welche Spalten benutzen sicher eine rekonstruierte
Sequence?", nicht "welche beliebigen Trigger sehen aehnlich aus wie
d-migrate-Trigger?".

---

## 4. Leitentscheidungen

### 4.1 Marker bleibt primaere Vertrauensquelle

Verbindliche Folge:

- ein Trigger wird in 0.9.4 nur dann als kanonischer Support-Trigger
  rekonstruiert, wenn Marker und Semantik zusammenpassen
- der Marker bleibt primaere Vertrauensquelle fuer die Spaltenzuordnung
- semantischer Triggertext ohne lesbaren Marker reicht in 0.9.4 nicht
  fuer `SequenceNextVal`

### 4.2 Trigger-Normalisierung ist semantisch, nicht textuell

Verbindliche Folge:

- D3 validiert Support-Trigger nicht per starrem Rohtextvergleich
- toleriert werden:
  - irrelevante Whitespace-Unterschiede
  - Delimiter-Artefakte
  - Quoting-Unterschiede bei Identifiers
  - Connector-/MySQL-formatbedingte Unterschiede im
    Metadatentext
- nicht toleriert werden fachliche Abweichungen bei:
  - Triggername
  - Markerinhalt
  - Timing/Event
  - Guard-Semantik `NEW.<column> IS NULL`
  - Zuweisung ueber `dmg_nextval('<sequence>')`

### 4.3 Markerloser Trigger bleibt degradierte Diagnose

Verbindliche Folge:

- fehlt der Marker, entsteht in 0.9.4 keine markerlose
  Trigger-Rekonstruktion
- der Body darf dann nur noch fuer die Diagnose
  "plausibel, aber nicht bestaetigbar" ausgewertet werden
- daraus folgt:
  - kein `SequenceNextVal`
  - Trigger bleibt Nutzertrigger oder degradierter Supportkandidat
  - `W116` statt Fehlzuordnung

### 4.4 Nur bestaetigte Trigger werden unterdrueckt

Verbindliche Folge:

- ein Trigger wird nur dann aus `schema.triggers` herausgefiltert, wenn
  er als kanonischer Support-Trigger bestaetigt ist
- `MISSING_MARKER`, `NON_CANONICAL`, `NOT_ACCESSIBLE` und `USER_OBJECT`
  werden nicht aggressiv unterdrueckt
- damit bleibt der Nutzerpfad fuer nicht bestaetigte Objekte sichtbar

### 4.5 Trigger-W116 aggregiert auf Spaltenidentitaet

Verbindliche Folge:

- degradierte Triggerfaelle werden in D3 nicht mehr auf
  Trigger-Hash-Ebene final ausgegeben
- die endgueltige Aggregation erfolgt auf
  `ColumnDiagnosticKey(reverseScope, canonicalTable, canonicalColumn)`
- pro betroffener Spalte entsteht hoechstens eine aggregierte `W116`,
  auch wenn mehrere technische Ursachen vorliegen

### 4.6 Triggerzugriff bleibt vom Include-Flag entkoppelt

Verbindliche Folge:

- `includeTriggers` bleibt Nutzervertrag fuer sichtbare Triggerobjekte,
  nicht Reader-Sperre fuer Support-Erkennung
- D3 darf Support-Trigger intern lesen und validieren, auch wenn
  `includeTriggers = false`
- aus dieser internen Lesung darf kein sichtbarer Trigger-Leak in den
  Nutzeroutput entstehen

---

## 5. Zielarchitektur fuer 6.3

### 5.1 D3-Pipeline im Reader

Nach D2 fuehrt `MysqlSchemaReader.read(...)` fuer D3 logisch diese
Schritte aus:

1. bestaetigte `schema.sequences` als bekannte Zielkeys uebernehmen
2. Trigger-Scan aus D1 fachlich nachvalidieren und normalisieren
3. bestaetigte Trigger auf `table.column -> sequenceName` aufloesen
4. Spaltendefaults in den Tabellen mit
   `DefaultValue.SequenceNextVal(sequenceName)` anreichern
5. bestaetigte Support-Trigger aus `schema.triggers` filtern
6. triggerbezogene `W116` aus D1/D3 auf Spaltenebene aggregieren

Architektur-Verortung:

- D3 lebt als eigener privater Reader-Schritt, z. B.
  `materializeSequenceDefaults(...)`
- im bestehenden `read()`-Ablauf liegt D3 nach dem internen
  Support-Scan aus Phase 2 und vor dem finalen `SchemaDefinition`-Bau;
  praktisch schiebt D3 damit den bisherigen Assemblierungsblock aus
  Phase 3 nach hinten und reichert dessen Eingaben fachlich an
- dieser Schritt konsumiert:
  - die D2-materialisierten `schema.sequences`
  - die Triggerrohdaten bzw. Triggerstatus aus dem Support-Snapshot
  - die bereits gelesenen Tabellen-/Triggerdefinitionen
- Ergebnis ist eine zweite fachliche Sicht auf:
  - `tables` mit angepassten `ColumnDefinition.default`
  - `triggers` ohne bestaetigte Support-Trigger
  - triggerbezogene `SchemaReadNote`s

### 5.2 Trigger-Erkennungsvertrag

Ein Trigger gilt in D3 nur dann als kanonisch bestaetigt, wenn alle
folgenden Bedingungen zugleich erfuellt sind:

- aus Marker und/oder Triggerbody muessen Tabelle, Spalte und
  Sequence fachlich rekonstruierbar sein; auf dieser Basis wird der
  erwartete Triggername vorwaerts ueber
  `MysqlSequenceNaming.triggerName(tableName, columnName)` berechnet
  und gegen den gelesenen Triggernamen geprueft
- der Marker-Kommentar enthaelt
  `d-migrate:mysql-sequence-v1 object=sequence-trigger`
- Marker, Triggername und Triggerinhalt benennen dieselbe
  Kombination aus:
  - Tabelle
  - Spalte
  - Sequence
- `action_timing = BEFORE`
- `event_manipulation = INSERT`
- der normalisierte Trigger-Body zeigt semantisch denselben Ablauf:
  - Guard `NEW.<column> IS NULL`
  - Zuweisung ueber `dmg_nextval('<sequence>')`

Trigger, die nur teilweise passen, werden nicht rekonstruiert.

### 5.3 Trigger-Normalisierung

D3 fuehrt eine robuste, semantische Normalisierung des Triggertexts ein.

Der bestehende Regex fuer `SET NEW.<column> = ...` reicht dafuer nicht
aus. D3 braucht zusaetzlich eine explizite Extraktion des Sequence-Keys
aus `dmg_nextval('<sequence>')` oder einen gleichwertig robusten,
D3-eigenen Parserpfad.

Mindestens zu tolerieren:

- Kommentare ausserhalb des Support-Markers
- Delimiter-Reste und formale Statement-Huellen
- irrelevante Whitespace-Unterschiede
- quoting- und case-insensitive Identifier
- harmlose Layout-Unterschiede in `BEGIN ... END`

Nicht zu tolerieren:

- anderer Triggername als die kanonische D3-Erwartung
- abweichendes Timing/Event
- fehlender `NEW.<column> IS NULL`-Guard
- andere Routine als `dmg_nextval`
- abweichender oder widerspruechlicher Sequence-Name

Normalisierung in D3 ist Diagnosehilfe und Bestaetigungslogik, keine
allgemeine SQL-Parser-Ambition. Wenn ein Trigger ueber einfache,
robuste Tokens nicht sicher bestaetigbar ist, bleibt er degradierter
Pfad statt heuristischem Guess.

### 5.4 Spaltenzuordnung und Default-Materialisierung

Verbindliches D3-Mapping:

- bestaetigter Trigger -> `table.column -> sequenceName`
- bestaetigte Zuordnung -> `ColumnDefinition.default =
  DefaultValue.SequenceNextVal(sequenceName)`

Weitere Regeln:

- die Sequence muss in `schema.sequences` aus D2 bereits eindeutig
  materialisiert sein
- verweist ein Trigger auf einen nicht vorhandenen oder mehrdeutigen
  Sequence-Key, entsteht keine Spaltenzuordnung
- als kompatibler bestehender Default gelten in D3 nur:
  - `null`
  - ein bereits identischer `DefaultValue.SequenceNextVal(sequenceName)`
- jeder andere bereits gelesene Nutzerdefault gilt als
  widerspruechlich, z. B. Literal-, Funktions- oder anderer
  Sequence-Default
- eine bestaetigte Triggerzuordnung ueberschreibt in D3 keinen bereits
  widerspruechlichen Nutzerdefault auf Verdacht; bei Widerspruch bleibt
  der degradierte Pfad mit `W116`

### 5.5 Status-zu-Diagnose-Vertrag auf Trigger-Ebene

Fuer D3 gilt:

- `SupportTriggerState.CONFIRMED`
  - Trigger wird bestaetigt
  - Spaltenzuordnung wird aufgebaut
  - Trigger wird unterdrueckt
  - kein `W116` allein aus dem Status
- `SupportTriggerState.MISSING_MARKER`
  - keine Spaltenzuordnung
  - aggregierbares spaltenbezogenes `W116`
- `SupportTriggerState.NON_CANONICAL`
  - keine Spaltenzuordnung
  - aggregierbares spaltenbezogenes `W116`
- `SupportTriggerState.NOT_ACCESSIBLE`
  - keine Spaltenzuordnung
  - aggregierbares spaltenbezogenes `W116`, sofern die betroffene
    Spalte/Sequence fachlich verankert werden kann
- `SupportTriggerState.USER_OBJECT`
  - kein Support-Trigger
  - keine Unterdrueckung
  - kein `W116` allein aus dem Status

Leitregel:

- D3 emittiert `W116` nicht pro Triggerobjekt, sondern pro betroffener
  Spalte
- Trigger-Hashes oder rohe Triggernamen bleiben interne Evidenz, nicht
  finale Nutzeridentitaet

### 5.6 Trigger-Scan-Zugriff und Gating

Wenn der gesamte Trigger-Scan nicht lesbar ist:

- bestaetigte D2-Sequences bleiben erhalten
- Spaltendefaults werden nicht rekonstruiert
- D3 erzeugt aggregiertes `W116`
- es entsteht kein Hard-Error, solange `dmg_sequences`, Tabellen und
  Spalten selbst lesbar bleiben

Wenn einzelne Trigger degradiert sind:

- nur die betroffenen Spalten verlieren ihre Zuordnung
- andere bestaetigte Trigger derselben DB bleiben reverse-bar

---

## 6. Konkrete Arbeitsschritte

### D3-0 Trigger-Scaffold-Korrekturen buendeln

- bestehende Trigger-Minimalheuristik dort korrigieren, wo sie fuer D3
  fachlich nicht ausreicht:
  - Triggerdiagnosen nicht bei Triggernamen als
    Spaltenersatzidentitaet stehenlassen
  - bestaetigte Trigger nicht nur filtern, sondern fachlich fuer
    Spaltenzuordnung weiterverwenden
  - Markerverlust weiterhin degrade statt markerloser Rekonstruktion
  - `includeTriggers = false` weiter sauber vom Supportpfad trennen
  - fragiles `endsWith(...)`-Matching in `filterSupportTriggers(...)`
    gegen eine stabile Triggeridentitaet aus dem Reader-/Object-Key-
    Vertrag haerten
- D3-0 ist kein reiner Vorbereitungsschritt, sondern fasst bewusst
  bestehende Reader-Scaffold-Logik und zugehoerige Tests an

Done-Kriterien fuer D3-0:

- degradierte Triggerdiagnosen fallen nicht mehr auf Triggernamen als
  Spaltenersatz zurueck, wenn die Spalte fachlich nicht verankert ist
- `filterSupportTriggers(...)` haengt nicht mehr an
  `endsWith(...)`-Matching gegen serialisierte Object-Keys
- die neue D3-Evidenz kann spaeter `sequenceName` getrennt vom reinen
  `dmg_nextval`-Token transportieren

### D3-1 Trigger-Rohdaten an D3 anbinden

- Triggerscan aus D1 so anschliessen, dass D3 mehr als nur
  Statusbooleans konsumieren kann
- den bestehenden `SupportTriggerAssessment`-Traeger oder einen
  gleichwertigen D3-internen Folgetyp um die fuer D3 zwingend benoetigte
  Sequence-Evidenz erweitern:
  - Triggername
  - `tableName`
  - `columnName`
  - `sequenceName: String?` aus `dmg_nextval('<sequence>')`
  - Triggertext/normalisierte Tokens
  - Timing/Event
  - ggf. aus Name ableitbare Segmentinformation

### D3-2 Trigger-Normalisierung implementieren

- semantische Normalisierung fuer Support-Trigger einziehen
- Marker, Name, Timing/Event und Body gemeinsam validieren
- neben der Spaltenextraktion auch den referenzierten Sequence-Key
  explizit aus dem Trigger-Body extrahieren und gegen Marker/Namensraum
  querpruefen
- harmlose Formatierungsunterschiede tolerieren
- markerlosen Trigger ausdruecklich auf Diagnosepfad begrenzen

### D3-3 Spaltenzuordnung aufbauen

- bestaetigte Trigger auf `table.column -> sequenceName` mappen
- dabei nur bereits aus D2 bestaetigte, eindeutige Sequence-Keys
  akzeptieren
- Konflikte oder widerspruechliche Zuordnungen nicht raten, sondern
  degradieren

### D3-4 Tabellendefaults materialisieren

- `ColumnDefinition.default` der betroffenen Spalten auf
  `DefaultValue.SequenceNextVal(sequenceName)` setzen
- bestehende Tabellenstruktur dabei ansonsten unveraendert lassen
- keine stille Default-Ueberschreibung bei widerspruechlicher Evidenz

### D3-5 Support-Trigger unterdruecken

- bestaetigte Support-Trigger aus `schema.triggers` entfernen
- `MISSING_MARKER`, `NON_CANONICAL`, `NOT_ACCESSIBLE` und `USER_OBJECT`
  nicht aggressiv unterdruecken
- Triggerunterdrueckung an denselben Kanonizitaetsvertrag binden wie die
  Default-Materialisierung
- D3-5 ist fachlich von D3-4 unabhaengig:
  - beide nutzen denselben Bestaetigungsvertrag
  - D3-4 materialisiert Spaltendefaults
  - D3-5 filtert nur sichtbare Triggerobjekte

### D3-6 Trigger-W116 auf Spaltenebene finalisieren

- bestehende Triggerdiagnostik von Hash-/Triggeridentitaeten auf
  `ColumnDiagnosticKey(...)` ueberfuehren
- pro Spalte hoechstens eine aggregierte `W116`
- sequence-bezogene Begleitsignale aus D2 nicht doppeln, sondern sauber
  von spaltenbezogenen Triggerdiagnosen trennen

### D3-7 Tests nachziehen

- Unit-Tests in `MysqlSchemaReaderTest` fuer:
  - intakten Support-Trigger mit `SequenceNextVal`
  - `includeTriggers = false` bei weiter funktionierender D3-Erkennung
  - Markerverlust ohne Fehlzuordnung
  - nicht kanonischen Trigger ohne Fehlzuordnung
  - Triggermetadaten nicht lesbar mit degradiertem `W116`
  - bestaetigten Support-Trigger bei bereits widerspruechlichem
    Nutzerdefault ohne stille Ueberschreibung
  - bestaetigte Support-Trigger verschwinden aus `schema.triggers`
  - `USER_OBJECT` bleibt sichtbarer Nutzertrigger
  - mehrere Spalten auf dieselbe Sequence
  - mehrere Sequences parallel, nur ein Trigger degradiert
- Integrationstests im MySQL-/Testcontainer-Setup fuer:
  - End-to-End-Round-Trip mit sequence-basierter Spalte
  - Triggertext mit harmlosen Formatting-/Quoting-Unterschieden
  - markerloser, aber semantisch aehnlicher Trigger als degradiertes
    Ergebnis ohne `SequenceNextVal`

Abhaengigkeiten:

- `D3-0` vor `D3-1` bis `D3-6`
- `D3-1` vor `D3-2`
- `D3-2` vor `D3-3`
- `D3-3` vor `D3-4` und `D3-6`
- `D3-4` und `D3-5` sind fachlich parallelisierbar, sobald
  `D3-3` den Bestaetigungsvertrag geliefert hat
- `D3-5` haengt vom finalen Bestaetigungsvertrag aus `D3-2`/`D3-3` ab

---

## 7. Verifikation

Pflichtfaelle fuer 6.3:

1. Eine sequence-basierte Spalte wird aus einer intakten MySQL-DB
   wieder zu `DefaultValue.SequenceNextVal(sequenceName)`
   rekonstruiert.
2. Derselbe Pfad funktioniert auch mit `includeTriggers = false`.
3. Ein bestaetigter Support-Trigger erscheint nicht mehr als normaler
   Trigger im Reverse-Ergebnis.
4. Reine Formatierungs-, Quoting- oder Delimiter-Unterschiede im
   Triggertext loesen fuer intakte Trigger kein `W116` aus.
5. Fehlt nur der Marker, entsteht keine Spaltenzuordnung, aber ein
   degradiertes `W116`.
6. Ein nicht kanonischer Trigger mit Marker, aber abweichender Semantik,
   fuehrt nicht zu einer Fehlzuordnung.
7. Ist der gesamte Trigger-Scan nicht lesbar, bleiben D2-Sequences
   sichtbar, aber die betroffenen Spaltendefaults fehlen und der Report
   enthaelt `W116`.
8. `USER_OBJECT`-Trigger bleiben sichtbare Nutzertrigger und werden
   weder unterdrueckt noch als `SequenceNextVal` fehlinterpretiert.
9. Mehrere Spalten koennen auf dieselbe Sequence zurueckgefaltet
   werden.
10. Ein degradierter Trigger blockiert andere bestaetigte Trigger nicht.
11. Triggerdiagnosen werden final pro betroffener Spalte aggregiert,
    nicht pro Triggerhash.
12. Ein Trigger, der auf einen nicht vorhandenen oder mehrdeutigen
    Sequence-Key verweist, erzeugt kein `SequenceNextVal`, sondern
    degradierte Diagnose.
13. Die Triggerunterdrueckung greift nur fuer bestaetigte Support-
    Trigger, nicht fuer `MISSING_MARKER`, `NON_CANONICAL`,
    `NOT_ACCESSIBLE` oder `USER_OBJECT`.
14. Eine Spalte mit bereits widerspruechlichem Nutzerdefault wird durch
    einen bestaetigten Support-Trigger nicht still ueberschrieben,
    sondern bleibt degradiert mit `W116`.

Akzeptanzkriterium fuer 6.3:

- sequence-basierte Spalten sind nach D3 neutral und compare-stabil
  rekonstruiert, ohne dass markerlose oder nicht kanonische Trigger zu
  Fehlzuordnungen fuehren

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueries.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceReverseSupport.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReaderTest.kt`
- ggf. `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueriesTest.kt`

Als fachliche Zieltypen bzw. konsumierte Vertragsanbieter relevant,
aber voraussichtlich nicht direkt zu aendern:

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ColumnDefinition.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DefaultValue.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SchemaDefinition.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadNote.kt`

Bewusst noch nicht direkt produktiv betroffen:

- `SchemaComparator`
- `SchemaCompareRunner`
- JSON-/YAML-Renderer fuer Compare
- D2-Materialisierung von `schema.sequences`

---

## 9. Risiken und Abgrenzung

### 9.1 Marker-Sichtbarkeit in Trigger-Metadaten

Risiko:

- MySQL-Metadaten liefern den Trigger-Marker nicht in jeder Umgebung
  stabil aus
- markerlose, aber semantisch aehnliche Trigger sehen dadurch wie fast
  bestaetigte Supporttrigger aus

Gegenmassnahme:

- Marker bleibt primaere Vertrauensquelle
- markerlose Trigger nur auf Diagnosepfad begrenzen
- echte MySQL-Integrationstests frueh absichern

### 9.2 Trigger-Normalisierung wird zu strikt oder zu lax

Risiko:

- zu strikte Normalisierung erzeugt unnoetige `W116`
- zu laxe Normalisierung erzeugt Fehlzuordnungen

Gegenmassnahme:

- nur robuste, semantische Tokens pruefen
- keine allgemeine SQL-Heuristik bauen
- verpflichtende Tests fuer harmlose Layout-Unterschiede und echte
  Fachabweichungen

### 9.3 Triggerdiagnosen bleiben auf Hash-Ebene haengen

Risiko:

- Nutzer sehen nur interne Triggerhashes statt betroffener Spalten
- D3 bleibt damit hinter dem 0.9.4-`W116`-Vertrag zurueck

Gegenmassnahme:

- finale Aggregation auf `ColumnDiagnosticKey(...)`
- Hash nur als interne Zwischenidentitaet verwenden

### 9.4 Unterdrueckung ohne bestaetigte Zuordnung waere zu aggressiv

Risiko:

- nicht kanonische oder markerlose Trigger verschwinden aus
  `schema.triggers`, obwohl keine sichere Spaltenzuordnung vorliegt

Gegenmassnahme:

- Unterdrueckung nur bei `SupportTriggerState.CONFIRMED`
- alle degradierten Triggerzustaende sichtbar lassen

### 9.5 Triggerzugriff darf D2 nicht rueckwirkend zerstoeren

Risiko:

- nicht lesbare Triggermetadaten koennten einen ansonsten erfolgreichen
  D2-Reverse unnoetig hart scheitern lassen

Gegenmassnahme:

- Triggerzugriff bleibt degradierter Pfad
- D2-Sequences bleiben erhalten
- D3 verliert nur die betroffenen Spaltendefaults

### 9.6 Mehrfachzuordnung und Konflikte an Spalten bleiben undeutlich

Risiko:

- derselbe Trigger oder mehrere Trigger koennten widerspruechliche
  Zuordnungen fuer dieselbe Spalte liefern

Gegenmassnahme:

- Spaltenzuordnung nur bei eindeutiger Bestaetigung
- Konflikte degradiert statt geraten behandeln

### 9.7 Trigger-Read bleibt ein In-Memory-Scan

Risiko:

- viele potenzielle Support-Trigger werden in 0.9.4 voll in den Reader
  geladen

Gegenmassnahme:

- fuer 0.9.4 als bewusste In-Memory-Entscheidung akzeptieren
- Scan auf den kanonischen Namensraum und Triggernamensraum begrenzen
- als bewusste technische Schuld dokumentieren; relevant wird das erst
  bei ungewoehnlich grossen Schemas mit deutlich dreistelligen bis
  vierstelligen potenziellen Support-Triggern
