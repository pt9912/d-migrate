# Implementierungsplan: 0.9.4 - Arbeitspaket 6.2 `Sequence-Reverse aus dmg_sequences`

> **Milestone**: 0.9.4 - Beta: MySQL-Sequence Reverse-Engineering und Compare
> **Arbeitspaket**: 6.2 (`Phase D2: Sequence-Reverse aus dmg_sequences`)
> **Status**: Draft (2026-04-21)
> **Referenz**: `docs/implementation-plan-0.9.4.md` Abschnitt 4.2 bis
> 4.5, Abschnitt 5.1 bis 5.3, Abschnitt 5.5, Abschnitt 6.2, Abschnitt 7
> und Abschnitt 9;
> `docs/ImpPlan-0.9.4-6.1.md`;
> `docs/mysql-sequence-emulation-plan.md` Abschnitt 6;
> `docs/ddl-generation-rules.md`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SequenceDefinition.kt`;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadNote.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueries.kt`;
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt`;
> `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReaderTest.kt`.

---

## 1. Ziel

Arbeitspaket 6.2 materialisiert aus der in 6.1 vorbereiteten
Support-Infrastruktur die neutralen `SequenceDefinition`-Objekte fuer
MySQL-Sequences, die ueber `dmg_sequences` emuliert werden.

6.2 liefert vier konkrete Ergebnisse:

- die kanonische Support-Tabelle `dmg_sequences` wird als primaere
  Wahrheitsquelle fuer Sequence-Metadaten ausgewertet
- gueltige Support-Zeilen werden in `schema.sequences` ueberfuehrt
- erkannte `dmg_sequences`-Supporttabellen werden aus
  `schema.tables` unterdrueckt
- degradierte oder konfliktbehaftete Sequence-Faelle bleiben als
  aggregiertes `W116` sichtbar, ohne andere gueltige Sequences zu
  blockieren

Nach 6.2 soll klar gelten:

- ein frisch generiertes MySQL-Schema reverse-bar wieder zu denselben
  neutralen Sequences zurueckfalten
- `dmg_sequences` nicht mehr als normale Nutzertabelle im Ergebnis
  erscheinen, wenn die Support-Grundform kanonisch erkannt ist
- teilinvalide oder mehrdeutige Sequence-Zeilen nur die betroffene
  Sequence verlieren, nicht den gesamten Reverse-Lauf
- der noch offene Trigger-Pfad aus 6.3 kein Vorwand mehr sein, die
  Sequence-Metadaten selbst unvollstaendig oder instabil zu halten

---

## 2. Ausgangslage

Mit 6.1 ist der Reader-Vertrag fuer Support-Metadaten vorbereitet:

- `MysqlMetadataQueries` darf `dmg_sequences` gezielt im
  `ReverseScope` lesen
- `MysqlSchemaReader` kann Supportstatus und Diagnoseevidenz getrennt
  von normalen Nutzerobjekten halten
- Konflikt- und Aggregationspfade fuer `W116` sind bereits festgelegt
- `missing`, `invalid_shape` und `not_accessible` sind fuer die
  Support-Tabelle unterscheidbar

Was nach 6.1 noch fehlt:

- Mapping von Support-Zeilen auf `SequenceDefinition`
- verbindliche Typ- und Grundformvalidierung fuer die D2-Materialisierung
- Unterdrueckung von `dmg_sequences` als sichtbare Tabelle, sobald die
  Supportform kanonisch erkannt ist
- klare Behandlung von gemischter Zeilenqualitaet, mehrdeutigen Keys
  und Laufzeitwerten in `next_value`
- Korrektur des aktuellen 6.1-Scaffolds dort, wo er fuer D2 noch zu
  breit oder zu weich ist:
  - Unterdrueckung von `dmg_sequences` auch bei `INVALID_SHAPE`
  - rohe `==`-Vergleiche fuer Markerfelder
  - stille Numerics-Fallbacks wie `0L` oder `1L`

Konsequenz ohne 6.2:

- `dmg_sequences` bleibt weiterhin als normale Tabelle im Reverse
  sichtbar
- `schema.sequences` bleibt leer, obwohl die primaere Wahrheitsquelle
  vorhanden ist
- Compare bleibt schon vor dem Trigger-Thema auf Sequence-Ebene noisy

---

## 3. Scope fuer 6.2

### 3.1 In Scope

- D2 setzt 6.1 voraus und nutzt dessen:
  - `ReverseScope`
  - Support-Snapshot
  - Status-zu-Diagnose-Vertrag
  - Konflikt- und Aggregationsschluessel
- Validierung der kanonischen Grundform von `dmg_sequences`
- zeilenweise Bewertung von Support-Zeilen
- Mapping gueltiger Zeilen auf `SequenceDefinition`
- Befuellung von `schema.sequences`
- Unterdrueckung von `dmg_sequences` aus `schema.tables`, wenn die
  Tabelle als kanonisches Supportobjekt erkannt ist
- Aggregation sequence-bezogener `W116` fuer:
  - invalide Einzelzeilen
  - mehrdeutige Sequence-Keys
  - fehlende oder nicht bestaetigbare Support-Routinen
- Tests fuer Reverse-Stabilitaet und Degradationsfaelle auf
  Sequence-Ebene

### 3.2 Bewusst nicht Teil von 6.2

- Rekonstruktion von `DefaultValue.SequenceNextVal(...)` an
  Spaltendefaults
- Triggererkennung und Trigger-Unterdrueckung
- Compare-Renderer- oder Exit-Code-Aenderungen
- Aufloesung des Produktentscheids "Laufzeitzaehler vs. Sollzustand"
  ueber 0.9.4 hinaus
- aggressive Unterdrueckung aehnlicher, aber nicht kanonischer
  Nutzertabellen

Praezisierung:

6.2 loest "welche neutralen Sequences lassen sich sicher aus
`dmg_sequences` gewinnen?", nicht "welche Spalten benutzen diese
Sequences bereits wieder korrekt?".

---

## 4. Leitentscheidungen

### 4.1 `dmg_sequences` bleibt die primaere Wahrheitsquelle

Verbindliche Folge:

- D2 rekonstruiert `SequenceDefinition` primaer aus `dmg_sequences`,
  nicht aus Triggern oder Routinen
- Routinen und Trigger sind in D2 nur Begleitevidenz fuer Degradation,
  nicht fachliche Ersatzquellen
- ist `dmg_sequences` im kanonischen Sinn nicht auswertbar, emittiert
  D2 keine Sequence auf Verdacht

### 4.2 Nur kanonische Grundform fuehrt zu Sequence-Reverse

Verbindliche Folge:

- D2 materialisiert Sequences nur dann, wenn die Tabelle
  `dmg_sequences` als kanonisches Supportobjekt bestaetigt ist
- zusaetzliche Spalten sind zulaessig, solange die Pflichtspalten
  eindeutig und in Zielsemantik lesbar bleiben
- fehlt eine Pflichtspalte oder ist ihre Zielsemantik nicht sicher
  lesbar, gilt die Tabellenform als `invalid_shape`
- bei `invalid_shape` entsteht kein Sequence-Reverse aus dieser Tabelle,
  sondern nur der degradierte Diagnosepfad aus 6.1

### 4.3 Zeilen werden lokal bewertet, nicht tabellenweit bestraft

Verbindliche Folge:

- D2 wertet `dmg_sequences` streng zeilenweise aus
- eine invalide, fremde oder manuell beschaedigte Zeile blockiert nicht
  andere gueltige Sequence-Zeilen
- nur die betroffene Sequence wird aus der Materialisierung
  herausgenommen und ueber den aggregierten `W116`-Pfad dokumentiert
- nur der Verlust der globalen Grundform der Tabelle verhindert den
  Sequence-Reverse insgesamt

### 4.4 `next_value -> start` ist bewusst nur ein Designzeit-Mapping

Verbindliche Folge:

- `next_value` wird in 0.9.4 auf `SequenceDefinition.start` gemappt
- dieses Mapping zielt auf generate/reverse-Stabilitaet frisch
  generierter Schemata
- nach produktiver Nutzung kann `next_value` vom urspruenglichen
  `start` abweichen; D2 behandelt das trotzdem nicht als Fehler
  oder Sonderfall
- ein spaeterer Compare kann diesen Laufzeitzustand in 0.9.4 weiter als
  normalen Sequence-Diff zeigen

### 4.5 Mehrdeutige Sequence-Keys bleiben blockiert

Verbindliche Folge:

- der in 6.1 definierte Konfliktvertrag gilt in D2 ohne Aufweichung
  weiter
- wenn mehrere Zeilen auf denselben neutralen Sequence-Key fallen,
  entsteht fuer diesen Key keine `SequenceDefinition`
- es gibt keinen "beste Zeile gewinnt"- und keinen
  `last write wins`-Pfad
- Konflikte werden sequence-bezogen als aggregiertes `W116`
  sichtbar, nicht als stille Map-Ueberschreibung

### 4.6 Erkanntes Supportobjekt wird aus `schema.tables` unterdrueckt

Verbindliche Folge:

- sobald `dmg_sequences` im aktiven `ReverseScope` als kanonische
  Support-Tabelle bestaetigt ist, erscheint sie nicht zusaetzlich in
  `schema.tables`
- fuer D2 bedeutet "bestaetigt" bewusst nur `supportTableState =
  AVAILABLE`
- die Unterdrueckung haengt nicht davon ab, ob alle Einzelzeilen gueltig
  sind; entscheidend ist die kanonische Tabellenform
- ein aktuelles 6.1-Scaffold, das `INVALID_SHAPE` bereits
  mitunterdrueckt, wird in D2 auf diesen engeren Vertrag korrigiert
- nicht kanonische oder nicht bestaetigte Tabellen bleiben normale
  Nutzerobjekte

---

## 5. Zielarchitektur fuer 6.2

### 5.1 D2-Pipeline im Reader

Nach dem D1-Support-Scan fuehrt `MysqlSchemaReader.read(...)` fuer D2
logisch diese Schritte aus:

1. `supportTableState` fuer `dmg_sequences` auswerten
2. bei `available` die Zeilen lokal validieren und normieren
3. gueltige Zeilen zu neutralen Sequence-Kandidaten mappen
4. Konflikt- und Degradationsfaelle ueber die vorhandenen
   Aggregationsschluessel einsammeln
5. `schema.sequences` aus den eindeutigen, gueltigen Kandidaten bauen
6. `dmg_sequences` bei bestaetigter Supportform aus `schema.tables`
   herausfiltern
7. sequence-bezogene `W116` an `SchemaReadResult` anhaengen

D2 fuehrt dabei keine Spaltenzuordnung ein; die Sequence-Liste muss
isoliert stabil sein, bevor 6.3 Defaults anreichert.

Architektur-Verortung:

- D2 lebt als eigener privater Materialisierungsschritt in
  `MysqlSchemaReader`, z. B. `materializeSupportSequences(...)`
- dieser Schritt konsumiert ausschliesslich den D1-Snapshot und baut
  daraus:
  - `schema.sequences`
  - table suppression fuer `dmg_sequences`
  - sequence-bezogene `SchemaReadNote`s
- `scanSequenceSupport(...)` bleibt D1-Infrastruktur und wird in D2
  nicht mit Fachmapping ueberladen

### 5.2 Kanonische Grundform von `dmg_sequences`

Pflichtspalten fuer D2:

- `managed_by`
- `format_version`
- `name`
- `next_value`
- `increment_by`
- `min_value`
- `max_value`
- `cycle_enabled`
- `cache_size`

Verbindliche Formregeln:

- jede Pflichtspalte muss eindeutig aufloesbar sein
- Zusatzspalten sind erlaubt und werden still ignoriert
- relevante Typsemantik zaehlt mehr als exakte SQL-Typgleichheit:
  - `managed_by`, `format_version`, `name`: string-artig lesbar
  - `next_value`, `increment_by`, `min_value`, `max_value`:
    integer-artig lesbar
  - `cycle_enabled`: boolean-/tinyint-artig lesbar
  - `cache_size`: integer-artig oder `NULL`
- Validierung erfolgt ueber `information_schema.columns`
  (`DATA_TYPE`/`COLUMN_TYPE`) plus JDBC-Lesekompatibilitaet, nicht ueber
  exakte DDL-Textgleichheit
- fuer string-artige Pflichtfelder gilt in D2 eine explizite
  Lesesemantik:
  - JDBC-Strings werden vor Fachvergleich als gelesene Werte behandelt,
    nicht als rohe SQL-Literale
  - `managed_by` und `format_version` werden vor dem Vergleich per
    `trim()` normalisiert und danach exakt gegen ihre kanonischen
    Literalwerte geprueft
  - `name` wird fuer Key-Bildung und Konflikterkennung ebenfalls per
    `trim()` normalisiert
  - fuer `name` gibt es in D2 keine zusaetzliche Case-Faltung, keine
    Sonderzeichenbereinigung und keine Wiederverwendung einer
    Naming-Heuristik wie `MysqlSequenceNaming.normalize()`

Grundform gilt in D2 als verloren, wenn:

- eine Pflichtspalte fehlt
- eine Pflichtspalte mehrfach oder uneindeutig aufloesbar ist
- eine Pflichtspalte nicht mehr in die benoetigte Zielsemantik gelesen
  werden kann
- `name` als stabiler Sequence-Key nicht rekonstruierbar ist

### 5.3 Zeilenvertrag fuer `dmg_sequences`

Eine Zeile ist nur dann D2-relevant, wenn:

- `managed_by = 'd-migrate'`
- `format_version = 'mysql-sequence-v1'`

Vergleichssemantik fuer string-artige Zeilenwerte:

- D2 verwendet fuer `managed_by`, `format_version` und `name` eine
  einheitliche Reader-Normalisierung vor dem Fachvergleich
- diese Normalisierung ist fuer D2 verbindlich:
  - `managed_by`, `format_version`: `trim()` vor exaktem Vergleich
  - `name`: `trim()` vor Key-Bildung, ohne Case-Faltung
- dieselbe Normalisierung wird sowohl fuer Filterung als auch fuer
  Konflikterkennung auf `name` verwendet, damit kein Drift zwischen
  "Zeile gilt" und "Key kollidiert" entsteht

Fremde oder historisch nicht passende Zeilen:

- werden nicht zu `SequenceDefinition` gemappt
- blockieren andere gueltige Zeilen nicht
- bleiben interne Evidenz fuer den sequence-bezogenen Diagnosepfad

Eine lokal gueltige D2-Zeile muss mindestens sicher liefern koennen:

- `name`
- `next_value`
- `increment_by`
- `min_value`
- `max_value`
- `cycle_enabled`
- `cache_size` oder `NULL`

Sonderregeln fuer `cache_size`:

- `NULL` bleibt zulaessig und wird unveraendert als fehlender Cachewert
  behandelt
- nicht numerische Werte sind invalide
- negative Werte sind invalide
- `0` ist in D2 zulaessig und wird unveraendert als
  `SequenceDefinition.cache = 0` materialisiert; das Neutralmodell kann
  diesen Wert ohne Sonderbehandlung tragen
- sehr grosse, aber noch zieltyp-kompatible Werte bleiben zulaessig;
  Ueberlaeufe oder nicht lesbare Grosswerte degradieren nur den
  betroffenen Key
- fuer Pflichtnumerics wie `next_value` oder `increment_by` gilt
  explizit:
  - nicht lesbare Werte machen die betroffene Zeile invalide
  - D2 fuehrt hier keine stillen Fallbacks wie `0L` oder `1L` ein

Gemischte Qualitaet fuer denselben neutralen Sequence-Key:

- sobald mehr als eine Zeile denselben Key beansprucht und mindestens
  eine davon widerspruechlich oder invalide ist, wird der gesamte Key
  blockiert
- es gibt keine partielle Weiternutzung "der besten Zeile"
- nach aussen erscheint genau eine aggregierte Konfliktdiagnose fuer
  diesen Key

### 5.4 Mapping auf `SequenceDefinition`

Verbindliches D2-Mapping:

- `description` -> `SequenceDefinition.description = null`
- `name` -> `SequenceDefinition.name`
- `next_value` -> `SequenceDefinition.start`
- `increment_by` -> `SequenceDefinition.increment`
- `min_value` -> `SequenceDefinition.minValue`
- `max_value` -> `SequenceDefinition.maxValue`
- `cycle_enabled` -> `SequenceDefinition.cycle`
- `cache_size` -> `SequenceDefinition.cache`

Weitere Regeln:

- `dmg_sequences.name` wird unveraendert als neutraler Sequence-Name
  nach D2-`trim()`-Normalisierung uebernommen
- D2 fuehrt keine eigene Umbenennung oder Kompatibilitaetsheuristik fuer
  Sequence-Namen ein
- Sortierung und Materialisierung muessen deterministisch bleiben:
  - D2 materialisiert Sequences stabil aufsteigend nach dem
    normalisierten neutralen Sequence-Key
  - damit bleiben Compare und Tests reproduzierbar

### 5.5 Status-zu-Diagnose-Vertrag auf Sequence-Ebene

Fuer D2 gilt der aus 6.1 vorbereitete Diagnosepfad auf
Sequence-Schluesseln:

- `supportTableState.missing`
  - kein `W116`
  - kein Sequence-Reverse
  - kompatibler Nicht-Sequence-Fall
- `supportTableState.invalid_shape`
  - keine `SequenceDefinition` aus dieser Tabelle
  - aggregierbares sequence-bezogenes `W116`
- `supportTableState.not_accessible`
  - harter Sequence-Pfad nur gemaess D1-Gating-Regel
  - keine Teilrekonstruktion auf Verdacht
  - Testvertrag: entweder harter Abbruch nach legitimierter
    Primaerquelle oder kompatibler Nicht-Sequence-Fall gemaess Gating,
    aber keine partielle implizite Sequence-Materialisierung
- invalide oder widerspruechliche Einzelzeile
  - blockiert nur den betroffenen Sequence-Key
  - aggregierbares `W116`
- `routineState.missing`
  - erzeugt in 6.1 noch keine Diagnose allein aus dem Status
  - wird erst in D2 bei bereits bestaetigter Sequence-Materialisierung
    zur sequence-bezogenen degradierenden Begleitevidenz
  - daraus entsteht dann aggregierbares `W116`
- `routineState.not_accessible`
  - Sequence-Rekonstruktion bleibt moeglich
  - aggregierbares sequence-bezogenes `W116`

Leitregel:

- D2 emittiert `W116` nicht pro Rohzeile, sondern pro betroffenem
  Sequence-Schluessel
- Rohzeilen und Query-Status bleiben interne Evidenz

---

## 6. Konkrete Arbeitsschritte

### D2-1 D1-Snapshot an D2 anbinden

- Support-Snapshot aus 6.1 im Reader so anschliessen, dass D2 die
  benoetigten Table-/Row-/Routine-Status konsumiert
- klar trennen zwischen:
  - globalem Tabellenstatus
  - lokaler Zeilenvalidierung
  - sequence-bezogener Diagnoseaggregation
- D2 als eigene private Reader-Methode verorten, statt D1-Scan und
  Fachmapping in einem Block zu vermischen

### D2-2 Grundformvalidierung implementieren

- kanonische Pflichtspalten fuer `dmg_sequences` programmatisch
  pruefen
- Typsemantik gegen ResultSet-Lesbarkeit absichern
- Zusatzspalten bewusst tolerieren und ignorieren
- `invalid_shape` frueh und eindeutig vom zeilenweisen Inhaltsfehler
  trennen
- Support-Row-Query weiter streng an `ReverseScope` binden; kein
  impliziter Rueckfall auf unscopte Current-Schema-Lesung

### D2-3 Zeilenvalidierung und Mapping implementieren

- `managed_by`- und `format_version`-Filter anwenden
- string-artige Pflichtfelder ueber eine zentrale D2-Normalisierung
  vergleichen
- Zeilen lokal validieren
- gueltige Zeilen auf `SequenceDefinition` mappen
- `next_value -> start` explizit an einer zentralen Stelle dokumentiert
  und testbar implementieren
- `cache_size`-Sonderwerte (`NULL`, `0`, negativ, nicht lesbar,
  Zieltyp-Ueberlauf) explizit gegen den D2-Vertrag pruefen
- nicht lesbare Pflichtnumerics als Invaliditaet behandeln, statt
  Defaultwerte zu injizieren

### D2-4 Konflikt- und W116-Pfad festziehen

- mehrdeutige Sequence-Keys gemaess D1 blockieren
- sequence-bezogene Aggregation statt Rohzeilen-Notes umsetzen
- fehlende, nicht bestaetigbare oder inkonsistente Support-Routinen als
  D2-spezifisch degradierte
  Sequence-Diagnose einfalten
- sicherstellen, dass gueltige andere Sequences parallel materialisiert
  bleiben

### D2-5 Supporttabelle unterdruecken

- `dmg_sequences` nur bei `supportTableState = AVAILABLE` aus
  `schema.tables` entfernen
- `INVALID_SHAPE` und andere nicht bestaetigte Zustaende explizit nicht
  unterdruecken
- Unterdrueckung und Sequence-Materialisierung an denselben
  Kanonizitaetsvertrag binden

### D2-6 Reader-Tests nachziehen

- `MysqlSchemaReaderTest` fuer:
  - intakte Sequence-Rekonstruktion aus `dmg_sequences`
  - konsistente Normalisierung von `managed_by`,
    `format_version` und `name`
  - CHAR-Padding in `managed_by`, `format_version` und `name`
  - Zusatzspalten ohne Grundformverlust
  - fehlende Pflichtspalten mit degradiertem `W116`
  - `cache_size`-Randfaelle (`NULL`, `0`, negativ, Grosswert)
  - nicht lesbare Pflichtnumerics ohne stillen `0`-/`1`-Fallback
  - einzelne kaputte Zeilen ohne Totalausfall
  - mehrere Sequences parallel, davon eine degradiert
  - mehrdeutige Sequence-Keys ohne stille Ueberschreibung
  - fehlende Support-Routinen bei weiter rekonstruierbaren Sequences
  - `supportTableState.missing` ohne `W116` und ohne implizite
    Sequence-Materialisierung
  - `supportTableState.not_accessible` gemaess D1-Gating-Regel ohne
    partielle Sequence-Materialisierung auf Verdacht
  - Unterdrueckung von `dmg_sequences` nur bei `AVAILABLE`, nicht bei
    `INVALID_SHAPE`
  erweitern

---

## 7. Verifikation

Pflichtfaelle fuer 6.2:

1. Eine frisch generierte MySQL-DB mit kanonischer `dmg_sequences`-
   Tabelle liefert die erwarteten `schema.sequences`.
2. `dmg_sequences` erscheint nach erfolgreicher D2-Erkennung nicht mehr
   als normale Tabelle im Reverse-Ergebnis.
3. Zusatzspalten in `dmg_sequences` brechen den Reverse nicht, solange
   die Pflichtspalten kanonisch lesbar bleiben.
4. Fehlt eine Pflichtspalte oder ist ihre Zielsemantik nicht sicher
   lesbar, entsteht keine Sequence-Rekonstruktion aus dieser Tabelle,
   sondern der degradierte Diagnosepfad.
5. Eine invalide Einzelzeile blockiert andere gueltige Sequence-Zeilen
   nicht.
6. Mehrere Sequences in derselben DB bleiben parallel reverse-bar; ein
   degradierter Zustand einer Sequence blockiert die andere nicht.
7. Mehrdeutige oder kollidierende Sequence-Keys werden nicht still
   ueberschrieben; fuer den betroffenen Key entsteht keine
   `SequenceDefinition`.
8. `dmg_sequences` wird nur bei `supportTableState = AVAILABLE`
   unterdrueckt; `INVALID_SHAPE` bleibt als normale Tabelle sichtbar.
9. Fehlen `dmg_nextval` oder `dmg_setval`, bleiben die Sequences
   sichtbar, aber der Report enthaelt sequence-bezogenes `W116`.
10. `managed_by`, `format_version` und `name` werden ueber eine
   konsistente D2-Normalisierung validiert, so dass Padding- oder
   Leseunterschiede nicht zu driftender Filter- und Key-Semantik
   fuehren.
11. `cache_size`-Randfaelle sind explizit abgesichert:
    `NULL` bleibt zulaessig, invalide oder nicht lesbare Werte
    degradieren nur den betroffenen Sequence-Key.
12. Nicht lesbare Pflichtnumerics fuehren zur Invalidierung der
    betroffenen Zeile, nicht zu stillen `0`-/`1`-Defaults.
13. `supportTableState.missing` fuehrt zu keinem `W116` und zu keiner
    impliziten Sequence-Materialisierung.
14. `supportTableState.not_accessible` fuehrt nur gemaess D1-Gating zu
    Hard-Fail oder kompatiblem Nicht-Sequence-Fall, nicht zu einem
    stillen Teilergebnis.
15. `SequenceDefinition.description` bleibt in D2 explizit `null`.
16. `next_value` wird in 0.9.4 auf `start` gemappt; ein spaeterer
    Laufzeitzaehler wird nicht in D2 "wegkorrigiert".
17. Ein Schema ohne bestaetigte `dmg_sequences`-Supporttabelle bleibt
    ein kompatibler Nicht-Sequence-Fall ohne implizite Sequences.

Akzeptanzkriterium fuer 6.2:

- `schema.sequences` ist nach D2 fuer kanonische
  `dmg_sequences`-Schemas stabil rekonstruiert, ohne dass D3 bereits
  Spaltendefaults liefern muss

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueries.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SequenceDefinition.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadNote.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReaderTest.kt`
- ggf. `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueriesTest.kt`

Bewusst noch nicht direkt produktiv betroffen:

- Trigger-Reverse fuer `SequenceNextVal`
- `SchemaComparator`
- `SchemaCompareRunner`
- JSON-/YAML-Renderer fuer Compare

---

## 9. Risiken und Abgrenzung

### 9.1 Laufzeitzustand vs. Sollzustand in `next_value`

Risiko:

- produktiv genutzte Datenbanken tragen in `next_value` einen
  Laufzeitzaehler statt des urspruenglichen Designzeit-Starts
- Compare kann deshalb spaeter Sequence-Diffs zeigen, obwohl nur
  Zaehlerverbrauch stattgefunden hat

Gegenmassnahme:

- `next_value -> start` in D2 explizit als 0.9.4-Designzeit-Mapping
  dokumentieren
- Tests auf frisch generierte Round-Trip-Faelle fokussieren
- keine versteckte Heuristik fuer "aktuellen Zaehlerstand" einbauen

### 9.2 Teilinvalides `dmg_sequences` darf nicht zum Totalausfall fuehren

Risiko:

- eine manuell beschaedigte oder fremde Einzelzeile koennte sonst den
  gesamten Sequence-Reverse blockieren

Gegenmassnahme:

- Tabellenform global validieren
- Zeileninhalt lokal bewerten
- `W116` pro Sequence aggregieren, nicht pro Rohzeile

### 9.3 Ueberunterdrueckung von Nutzerobjekten

Risiko:

- eine aehnlich benannte Nutzertabelle koennte verschwinden, obwohl sie
  kein kanonisches Supportobjekt ist

Gegenmassnahme:

- Unterdrueckung nur bei `supportTableState = AVAILABLE`
- Name allein reicht nie
- Negativtests fuer nicht kanonische Tabellen im selben Namensraum

### 9.4 Weiche Feldnormalisierung erzeugt Drift

Risiko:

- Markerfilter, Key-Bildung und Konflikterkennung koennten je Feld
  leicht unterschiedliche Vergleichsregeln verwenden
- CHAR-Padding oder rohe JDBC-Werte fuehren dann zu instabiler
  Materialisierung

Gegenmassnahme:

- D2-Normalisierung pro Feld explizit festschreiben:
  - `managed_by`, `format_version`: `trim()` + exakter Vergleich
  - `name`: `trim()` ohne Case-Faltung
- CHAR-Padding-Testfaelle verpflichtend machen

### 9.5 Konfliktpfad wird spaeter aufgeweicht

Risiko:

- D2 oder D3 fuehren doch wieder implizite Ueberschreibung oder
  "beste Zeile gewinnt" ein

Gegenmassnahme:

- Konfliktvertrag aus 6.1 unveraendert in D2 uebernehmen
- Akzeptanztests fuer mehrdeutige Keys verpflichtend machen

### 9.6 Support-Routinen werden faelschlich zur Pflicht fuer D2

Risiko:

- fehlende oder nicht lesbare `dmg_nextval`-/`dmg_setval`-Metadaten
  koennten unnoetig den Sequence-Reverse blockieren

Gegenmassnahme:

- Routinen in D2 nur als degradierende Begleitevidenz behandeln
- `SequenceDefinition` weiter aus `dmg_sequences` rekonstruieren, sofern
  die Primaerquelle lesbar ist

### 9.7 Stille Numerics-Fallbacks verfaelschen Sequence-Metadaten

Risiko:

- nicht lesbare Pflichtfelder wie `next_value` oder `increment_by`
  koennten durch Defaultwerte maskiert werden
- daraus entstuenden scheinbar gueltige, fachlich aber falsche
  `SequenceDefinition`s

Gegenmassnahme:

- nicht lesbare Pflichtnumerics in D2 immer als Invaliditaet der
  betroffenen Zeile behandeln
- kein stilles Auffuellen mit `0L`, `1L` oder aehnlichen Defaults
