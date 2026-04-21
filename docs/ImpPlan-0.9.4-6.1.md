# Implementierungsplan: 0.9.4 - Arbeitspaket 6.1 `Reader-Vertrag und Metadatenzugriff festziehen`

> **Milestone**: 0.9.4 - Beta: MySQL-Sequence Reverse-Engineering und Compare
> **Arbeitspaket**: 6.1 (`Phase D1: Reader-Vertrag und Metadatenzugriff festziehen`)
> **Status**: Draft (2026-04-21)
> **Referenz**: `docs/implementation-plan-0.9.4.md` Abschnitt 4.1 bis
> 4.5, Abschnitt 5.1 bis 5.4, Abschnitt 6.1, Abschnitt 6.6, Abschnitt 7
> und Abschnitt 9;
> `docs/mysql-sequence-emulation-plan.md` Abschnitt 6;
> `docs/ddl-generation-rules.md`;
> `docs/cli-spec.md`;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadOptions.kt`;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadNote.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueries.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt`;
> `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReaderTest.kt`.

---

## 1. Ziel

Arbeitspaket 6.1 zieht den technischen Unterbau fuer den 0.9.4-Reverse-
Pfad in den MySQL-Reader ein. Es liefert noch **keine** komplette
Sequence-Rekonstruktion, sondern den belastbaren Rahmen, auf dem 6.2
(`dmg_sequences` -> `schema.sequences`) und 6.3
(`BEFORE INSERT`-Trigger -> `SequenceNextVal`) aufsetzen.

6.1 liefert vier konkrete Ergebnisse:

- `MysqlSchemaReader` bekommt einen expliziten internen
  Sequence-Support-Scan statt nur flacher Objektlese-Pfade
- `MysqlMetadataQueries` bekommt die gezielten Abfragen, die fuer
  Support-Tabelle, Support-Routinen und Support-Trigger noetig sind
- der degradierte Reverse-Vertrag (`W116`) wird als Reader-Infrastruktur
  festgelegt, inklusive Aggregation und Berechtigungs-Fallback
- Konflikt- und Mehrdeutigkeitsregeln fuer Sequence-Keys werden vor D2
  und D3 zentral festgezogen

Nach 6.1 soll klar gelten:

- der Reader kann interne Supportobjekte inspizieren, ohne das
  oeffentliche `SchemaReadOptions`-API zu aendern
- fehlende Metadatenrechte auf Routinen/Trigger fuehren nicht
  automatisch zum Hard-Error
- `W116` wird nicht ad hoc an beliebigen Stellen erzeugt, sondern ueber
  einen konsistenten Aggregationspfad
- D2 und D3 muessen nicht mehr ueber Reader-Grundsatzfragen diskutieren,
  sondern koennen direkt auf eine klare Infrastruktur aufsetzen

---

## 2. Ausgangslage

Der heutige `MysqlSchemaReader` ist noch auf allgemeines Reverse
ausgelegt:

- Tabellen werden ueber `information_schema.tables` gelesen
- Views, Funktionen, Prozeduren und Trigger werden optional ueber die
  Include-Flags geladen
- das Ergebnis wird direkt in `SchemaDefinition` ueberfuehrt

Was heute fehlt:

- ein eigener interner Scan fuer d-migrate-Supportobjekte
- gezielte Metadatenabfragen fuer `dmg_sequences`,
  `dmg_nextval`, `dmg_setval` und Sequence-Support-Trigger
- ein gemeinsamer Ort fuer degradierte Reverse-Notes
- ein Konfliktvertrag fuer mehrdeutige Sequence-Keys

Konsequenz:

- `dmg_sequences` wird heute nur als normale Basistabelle gesehen
- Support-Routinen und Support-Trigger koennen nicht getrennt von
  Nutzerobjekten behandelt werden
- `W116` existiert fachlich im Plan, aber noch nicht als konkrete
  Reader-Infrastruktur

Gleichzeitig darf 6.1 den bestehenden Reverse-Vertrag nicht unnötig
brechen:

- Include-Flags bleiben sichtbare Output-Flags fuer Nutzerobjekte
- normale Tabellen-, View- und Routine-Pfade muessen weiterlaufen
- bestehende Reader-Tests fuer Nicht-Sequence-Faelle duerfen nicht
  still kippen

---

## 3. Scope fuer 6.1

### 3.1 In Scope

- interne Zerlegung von `MysqlSchemaReader.read(...)` in:
  - allgemeine Objektlese-Pfade
  - Sequence-Support-Scan
  - Post-Processing-/Anreicherungsphase
- neue oder erweiterte `MysqlMetadataQueries` fuer:
  - Support-Tabellenform
  - Support-Zeilen
  - Support-Routinen
  - Support-Trigger
- Hilfsdatentyp fuer den internen Support-Scan, z. B.
  `MysqlSequenceReverseSupport`
- Reader-seitige Aggregationsregeln fuer `W116`
- Berechtigungs- und Metadaten-Fallback fuer Support-Routinen und
  Support-Trigger
- Konfliktregeln fuer mehrdeutige Sequence-Keys
- Unit-Tests auf Reader-/Query-Ebene fuer die neue Infrastruktur

### 3.2 Bewusst nicht Teil von 6.1

- eigentliche Rekonstruktion von `SequenceDefinition` aus
  `dmg_sequences`-Zeilen
- eigentliche Rekonstruktion von `DefaultValue.SequenceNextVal(...)`
  aus Triggern
- Compare-Renderer- oder CLI-Ausgabeaenderungen
- neue oeffentliche Flags fuer `schema reverse`
- Heuristiken fuer fremde, nicht markierte Sequence-Loesungen

Praezisierung:

6.1 loest "wie liest und bewertet der Reader Support-Metadaten
robust?", nicht "welches vollstaendige Reverse-Ergebnis wird schon
produziert?".

---

## 4. Leitentscheidungen

### 4.1 6.1 schafft Infrastruktur, keine Teil-Rekonstruktion auf Verdacht

Verbindliche Folge:

- 6.1 fuehrt keine halbfertige Sequence-Rekonstruktion ein
- die neuen Reader-Hilfstypen duerfen Support-Metadaten sammeln,
  klassifizieren und mit Diagnosezustand versehen
- erst 6.2 und 6.3 materialisieren daraus `schema.sequences` bzw.
  `SequenceNextVal`

Begruendung:

- damit bleibt der Cut zwischen Infrastruktur und Fachmapping klar
- Review und Tests koennen D1 sauber von D2/D3 trennen

### 4.2 Include-Flags bleiben Nutzervertrag, nicht Reader-Sperre

Verbindliche Folge:

- `includeFunctions`, `includeProcedures` und `includeTriggers`
  bestimmen weiter nur, welche **Nutzerobjekte** im neutralen Ergebnis
  auftauchen
- 6.1 darf Support-Routinen und Support-Trigger intern trotzdem lesen,
  wenn sie fuer den Sequence-Support-Scan benoetigt werden
- daraus darf aber kein sichtbarer Nebenpfad entstehen:
  - bei deaktivierten Include-Flags duerfen keine Supportobjekte im
    Nutzerergebnis auftauchen
  - ein reiner Support-Scan darf keine zusaetzlichen sichtbaren Notes
    fuer Nicht-Sequence-Faelle erzeugen

### 4.3 Berechtigungsfehler auf Zusatzmetadaten werden degradiert

Verbindliche Folge:

- Fehler beim Lesen von Support-Routinen oder Support-Triggern werden in
  6.1 nicht als Hard-Error modelliert, solange Kernmetadaten
  (`dmg_sequences`, Tabellen, Spalten) lesbar bleiben
- solche Faelle werden in den internen Support-Scan als
  "nicht bestaetigbar" ueberfuehrt und spaeter als `W116`
  aggregierbar gemacht
- eine **fehlende** `dmg_sequences`-Tabelle ist dagegen ein kompatibler
  Soft-Missing-Fall:
  - kein Sequence-Support erkannt
  - kein `W116` allein wegen Abwesenheit der Tabelle
  - kein Hard-Error
- nur der Verlust der primaeren Wahrheitsquelle bei **vorhandener, aber
  nicht lesbarer** `dmg_sequences`-Metadatenquelle bleibt ein harter
  Abbruchgrund
- die Unterscheidung zwischen `missing` und `not_accessible` muss in
  6.1 explizit ueber einen zweistufigen Erkennungsprozess erfolgen:
  - Schritt 1: Existenz-/Adressierbarkeitspruefung fuer
    `dmg_sequences` ueber eine gezielte Metadatenabfrage mit
    unterscheidbarem Ergebniszustand
  - Schritt 2: nur wenn die Tabelle adressierbar ist, Form-/Datenzugriff
    pruefen; dabei muessen Zugriffsfehler separat von inhaltlich
    ungueltiger Tabellenform modelliert werden
  - "nicht vorhanden" und "vorhanden, aber nicht lesbar" duerfen nicht
    aus demselben Catch-All-Zweig abgeleitet werden
  - wenn bereits die Existenzpruefung wegen Rechten oder technischer
    Metadatenstoerung kein sauberes "vorhanden"/"nicht vorhanden"
    liefern kann, ist das als `not_accessible` zu klassifizieren, nicht
    als `missing`

### 4.4 W116 wird auf Objektidentitaeten aggregiert

Verbindliche Folge:

- 6.1 definiert die Aggregationsschluessel bereits vor der
  Fachrekonstruktion:
  - Sequence-Ebene fuer `dmg_sequences`-/Routine-Probleme
  - Spaltenebene fuer Trigger-/Default-Zuordnung
- diese Schluessel muessen stabil und explizit sein:
  - `SequenceDiagnosticKey(reverseScope, neutralSequenceKey)`
  - `ColumnDiagnosticKey(reverseScope, canonicalTable, canonicalColumn)`
- die Schluessel werden in 6.1 als strukturierte Composite-Keys
  modelliert, nicht als String mit Trennzeichenkodierung
- erst spaetere Render-/Logpfade duerfen daraus lesbare Strings
  ableiten
- konkurrierende technische Ursachen fuer denselben Schluessel
  (z. B. mehrere invalide Zeilen derselben Sequence oder mehrere
  Triggerbeobachtungen derselben Spalte) werden in genau einer
  aggregierten Diagnose zusammengefuehrt
- Rohzeilen oder einzelne fehlgeschlagene Metadatenabfragen sind nur
  interne Evidenz, nicht direkte Nutzer-Notes
- mehrfache technische Einzelursachen duerfen zu genau einer
  aggregierten Diagnose pro betroffenem Fachobjekt zusammenlaufen

### 4.5 Markerloser Trigger bleibt in 6.1 ein degradiertes Signal

Verbindliche Folge:

- semantische Trigger-Normalisierung ist in 6.1 nur
  Bestaetigungsinfrastruktur fuer markierte Support-Trigger
- fehlt der Marker, darf 6.1 den Trigger hoechstens als "plausibel,
  aber nicht bestaetigbar" markieren
- daraus entsteht **keine** implizite Spaltenzuordnung

### 4.6 Mehrdeutige Sequence-Keys werden frueh blockiert

Verbindliche Folge:

- 6.1 definiert einen zentralen Konfliktpfad fuer doppelte oder
  mehrdeutige Sequence-Keys
- D2 und D3 duerfen sich auf einen eindeutigen Keyspace verlassen
- Konflikte werden nicht still durch "last write wins" aufgeloest

---

## 5. Zielarchitektur fuer 6.1

### 5.1 Reader-Schichten

`MysqlSchemaReader.read(...)` wird intern in drei Ebenen getrennt:

1. Basismetadaten und allgemeine Objektlisten lesen
2. Sequence-Support-Scan ausfuehren
3. Ergebnis aus allgemeinem Schema und Supportdiagnostik zusammensetzen

Der Sequence-Support-Scan liefert in 6.1 noch keine fertige
`SchemaDefinition`, sondern einen internen Snapshot mit mindestens:

- Tabellenform-/Zeilenstatus fuer `dmg_sequences`
- Routinenstatus fuer `dmg_nextval` und `dmg_setval`
- Triggerstatus fuer potenzielle Sequence-Support-Trigger
- Konfliktzustand fuer Sequence-Key-Mehrdeutigkeiten
- aggregierbare Reverse-Diagnosen

### 5.2 Query-Vertrag

`MysqlMetadataQueries` wird um gezielte Support-Abfragen erweitert.
Erwartete Bausteine:

- Abfrage fuer `dmg_sequences`-Spaltenform
- Abfrage fuer `dmg_sequences`-Zeilen
- gezielte Routine-Lookups fuer `dmg_nextval`/`dmg_setval`
- gezielte Trigger-Lookups fuer moegliche Support-Trigger

Wichtig fuer 6.1:

- keine "lade immer alle Trigger/Funktionen"-Ausweitung des
  Nutzervertrags
- Supportabfragen bleiben gezielt und separat vom sichtbaren
  Include-Flag-Pfad
- Permission-Denied und "metadata not accessible" werden als eigener
  technischer Status modelliert, nicht nur als rohe Exception
- neben Berechtigungsfehlern muessen in 6.1 auch
  Metadaten-/Parsing-Fehler sauber klassifiziert werden:
  - Zugriff nicht moeglich (`not_accessible`)
  - Objekt fehlt (`missing`)
  - Metadaten formal vorhanden, aber nicht kanonisch lesbar
    (`invalid_shape` bzw. `non_canonical`)
  - Nutzerobjekt statt Supportobjekt (`user_object`)
- fuer Routinen- und Trigger-Lookups gilt dabei derselbe
  Fehlerklassenvertrag:
  - Rechte-/Sichtbarkeitsfehler laufen nach `not_accessible`
  - SQL-/Lookup-Fehler bei technisch vorhandener Quelle laufen ebenfalls
    nach `not_accessible`, wenn dadurch keine verlaessliche fachliche
    Bewertung mehr moeglich ist
  - Parser-/Normalisierungsfehler auf gelesenen Definitionen laufen nach
    `non_canonical`, nicht nach `missing`

### 5.3 Support-Snapshot

Empfohlene interne Struktur:

- `supportTableState`
  - `missing`
  - `available`
  - `invalid_shape`
  - `not_accessible`
- `sequenceRows`
  - rohe Zeilen
  - vorvalidierte Zeilen
  - Konflikt-/Mehrdeutigkeitsmarken
- `routineState`
  - pro Support-Routine: `confirmed`, `missing`, `not_accessible`
- `triggerState`
  - pro potenziellem Support-Trigger:
    `confirmed`, `missing_marker`, `not_accessible`,
    `non_canonical`, `user_object`
- `diagnostics`
  - aggregierbare Vorstufe fuer spaetere `SchemaReadNote`s

Der genaue Typname ist offen; wichtig ist die funktionale Trennung.

Semantik von `supportTableState`:

- `missing`:
  - kompatibler Nicht-Sequence-Fall
  - kein Hard-Error
  - kein `W116` allein wegen fehlender Tabelle
  - wird nur gesetzt, wenn die Existenzpruefung negativ, aber technisch
    erfolgreich war
- `available`:
  - primaere Wahrheitsquelle ist lesbar
- `invalid_shape`:
  - Support-Tabelle ist vorhanden, aber nicht kanonisch auswertbar
  - degradierter Sequence-Pfad; spaeter aggregierbares `W116`
- `not_accessible`:
  - Support-Tabelle ist adressierbar oder die Existenzpruefung liefert
    nur einen Rechte-/Zugriffsfehler, aber kein sauberes "nicht
    vorhanden"
  - harter Abbruchgrund fuer den Sequence-Pfad, weil die primaere
    Wahrheitsquelle nicht inspizierbar ist
  - dieser Zustand darf nicht durch spaeteres "wir haben keine Zeilen
    gefunden" zu `missing` umgedeutet werden

### 5.4 Konflikt- und Aggregationspfad

6.1 legt die Basisregeln fest:

- jede `dmg_sequences`-Zeile wird zunaechst lokal bewertet
- mehrere technische Probleme derselben Sequence werden auf einen
  Sequence-Schluessel aggregiert
- Triggerprobleme werden auf `ColumnDiagnosticKey(...)` aggregiert
- mehrdeutige Sequence-Keys blockieren spaetere Rekonstruktion fuer
  genau diesen Key
- fuer gemischte Qualitaet auf demselben Sequence-Key gilt in 6.1
  verbindlich:
  - sobald mehr als eine Zeile denselben neutralen Sequence-Key
    beansprucht und mindestens eine davon nicht konsistent mit den
    anderen ist, wird der gesamte Key als mehrdeutig blockiert
  - "nicht konsistent" umfasst hier explizit auch gemischte
    Zeilenqualitaet:
    - eine fachlich verwertbare Zeile plus eine invalide oder
      widerspruechliche Schwesterzeile fuer denselben Key blockiert den
      gesamten Key
    - eine Teilmenge gueltiger Zeilen darf nicht selektiv weiterverwendet
      werden, solange derselbe Key gleichzeitig widerspruechliche Evidenz
      traegt
  - es gibt fuer diesen Key keinen "beste gueltige Zeile gewinnt"-
    Fallback und kein `last write wins`
  - die Rohzeilen bleiben interne Evidenz; nach aussen erscheint genau
    eine aggregierte Konfliktdiagnose

Diese Regeln muessen bereits in D1 zentral liegen, damit D2 und D3
nicht eigene konkurrierende Aggregationslogiken erfinden.

---

## 6. Konkrete Arbeitsschritte

### 6.1 Reader intern zerlegen

- `MysqlSchemaReader.read(...)` in private Teilschritte schneiden
- Basispfade fuer Tabellen/Views/Funktionen/Prozeduren/Trigger
  isolieren
- Hook fuer Sequence-Support-Scan einziehen

### 6.2 `MysqlMetadataQueries` erweitern

- gezielte Support-Abfragen einfuehren
- Rueckgabeformen so schneiden, dass `not_accessible` von `missing`
  unterschieden werden kann
- den zweistufigen Erkennungsprozess fuer `dmg_sequences` abbilden:
  Existenz-/Adressierbarkeitspruefung getrennt von Form-/Datenzugriff
- fuer `dmg_sequences` einen expliziten Rueckgabezustand vorsehen, bei
  dem die Existenzfrage selbst technisch nicht entscheidbar ist; dieser
  Zustand mappt nach `not_accessible`, nicht nach `missing`
- Query-Tests fuer die neuen Pfade anlegen oder vorhandene Tests
  erweitern

### 6.3 Support-Snapshot einfuehren

- internen Support-Datentyp anlegen
- Support-Tabelle, Routinen und Trigger in diesem Snapshot sammeln
- Konflikt- und Diagnosestatus strukturiert ablegen

### 6.4 W116-Aggregation festziehen

- Aggregationsschluessel fuer Sequence- und Spaltenebene festlegen
- dafuer strukturierte Composite-Key-Typen statt Stringkodierung
  einfuehren
- technische Einzelereignisse in aggregierbare Diagnoseeintraege
  ueberfuehren
- Mappingpfad zu spaeteren `SchemaReadNote`s vorbereiten

### 6.5 Konfliktvertrag fuer Sequence-Keys festziehen

- Regel fuer doppelte oder mehrdeutige Keys in der Infrastruktur
  verankern
- verhindern, dass nachfolgende Phasen durch Map-Ueberschreibung
  nondeterministisch werden

### 6.6 Reader-Tests nachziehen

- `MysqlSchemaReaderTest` fuer:
  - Supportabfragen ohne API-Aenderung
  - degradierte Zusatzmetadatenrechte
  - deaktivierte Include-Flags ohne sichtbare Supportobjekte und ohne
    zusaetzliche Nutzer-Notes
  - Aggregationsverhalten
  - mehrdeutige Sequence-Keys
  - Nicht-Sequence-Faelle ohne Supportobjekte und ohne zusaetzliche
    Diagnose-Notes
  - Baseline-Vergleich gegen bestehende Nicht-Sequence-Fixtures oder
    bestehende Reader-Testfaelle ohne Ergebnisdrift in Schemaobjekten,
    Notes und Fehlerverhalten
  erweitern

---

## 7. Verifikation

Pflichtfaelle fuer 6.1:

1. Support-Metadaten koennen intern gelesen werden, ohne das
   oeffentliche `SchemaReadOptions`-API zu aendern.
2. Fehlende Rechte auf Routinen/Trigger fuehren nicht zum Hard-Error,
   solange `dmg_sequences` lesbar bleibt.
3. `missing`, `not_accessible`, `invalid_shape` und `non_canonical`
   werden intern unterscheidbar modelliert.
4. Doppelte/mehrdeutige Sequence-Keys fuehren nicht zu stiller
   Ueberschreibung.
5. Die Aggregation liefert pro Sequence bzw. pro Spalte genau eine
   zusammengefuehrte Diagnosebasis.
6. Ein Schema ohne `dmg_sequences` und ohne Supportobjekte bleibt ein
   kompatibler Nicht-Sequence-Fall: kein Hard-Error, kein implizites
   `W116`, keine zusaetzlichen Reverse-Notes.
7. Bei gemischter Zeilenqualitaet fuer denselben Sequence-Key wird der
   gesamte Key blockiert; es gibt keinen partiellen "beste Zeile
   gewinnt"-Pfad.
8. Deaktivierte Include-Flags bleiben fuer Nutzerobjekte sauber:
   Support-Scans erzeugen weder sichtbare Supportobjekte noch
   zusaetzliche Nutzer-Notes.
9. Bestehende Nicht-Sequence-Reader-Faelle bleiben gegen bestehende
   Test-Baselines unveraendert:
   keine Drift in Schemaobjekten, Notes und Hard-/Soft-Fail-Verhalten.

Akzeptanzkriterium fuer 6.1:

- der Reader hat nach 6.1 eine belastbare interne Support-Infrastruktur,
  auf der 6.2 und 6.3 ohne weitere Grundsatzentscheidungen aufsetzen
  koennen

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueries.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadNote.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReaderTest.kt`
- ggf. `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueriesTest.kt`

Bewusst noch nicht direkt produktiv betroffen:

- `SchemaComparator`
- `SchemaCompareRunner`
- JSON-/YAML-Renderer fuer Compare
- eigentliche `SequenceDefinition`- und `SequenceNextVal`-Materialisierung

---

## 9. Risiken und Abgrenzung

### 9.1 D1 driftet in D2/D3 hinein

Risiko:

- Infrastruktur und Fachmapping werden vermischt
- der Teilplan verliert seinen klaren Cut

Gegenmassnahme:

- 6.1 erzeugt nur den Support-Snapshot und den Diagnosevertrag
- eigentliche Materialisierung bleibt fuer 6.2 und 6.3 reserviert

### 9.2 Query-Fallback wird zu breit

Risiko:

- Supportabfragen lesen ploetzlich wieder "alles"
- Include-Flags verlieren indirekt ihre Bedeutung

Gegenmassnahme:

- nur gezielte Support-Lookups
- keine implizite Generalisierung des bestehenden Funktions-/Triggerpfads

### 9.3 `missing` und `not_accessible` driften auseinander

Risiko:

- eingeschraenkte Metadatenrechte werden faelschlich als "Objekt fehlt"
  gelesen
- echte Berechtigungsprobleme verschwinden als Soft-Missing
- oder harmlose Nicht-Sequence-Faelle werden faelschlich zu Hard-Fails

Gegenmassnahme:

- zweistufige Existenz-/Adressierbarkeitspruefung explizit modellieren
- kein gemeinsamer Catch-All fuer "nicht vorhanden" und "nicht lesbar"
- negativer Existenzbefund nur bei technisch erfolgreicher Pruefung
  als `missing`

### 9.4 Aggregation wird zu spaet festgezogen

Risiko:

- D2 und D3 fuehren jeweils eigene `W116`-Logik ein
- spaetere Compare-/Report-Pfade bekommen widerspruechliche Diagnosen

Gegenmassnahme:

- Aggregationsschluessel und Diagnosebasis schon in 6.1 definieren

### 9.5 Mehrdeutige Keys werden zu still behandelt

Risiko:

- spaetere Maps ueberschreiben Werte nondeterministisch
- Reverse-Ergebnisse haengen von Einlesereihenfolge ab

Gegenmassnahme:

- Konfliktpfad in 6.1 explizit modellieren
- keine "last write wins"-Semantik

### 9.6 Nicht-Sequence-Baseline bleibt unbeweisbar

Risiko:

- "unveraendert" bleibt nur eine verbale Zusage
- spaetere Reader-Aenderungen schieben unbeabsichtigt Notes oder
  Fehlerverhalten in bestehende Faelle

Gegenmassnahme:

- bestehende Nicht-Sequence-Fixtures oder etablierte Reader-Testfaelle
  als Baseline benennen und weiterverwenden
- bei 6.1 explizit auf Schemaobjekte, Notes und Hard-/Soft-Fail-
  Verhalten vergleichen
