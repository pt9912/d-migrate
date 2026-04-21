# Implementierungsplan: 0.9.4 - Arbeitspaket 6.2 `Sequence-Reverse aus dmg_sequences`

> **Milestone**: 0.9.4 - Beta: MySQL-Sequence Reverse-Engineering und Compare
> **Arbeitspaket**: 6.2 (`Phase D2: Sequence-Reverse aus dmg_sequences`)
> **Status**: Done (2026-04-21)
> **Referenz**: `docs/implementation-plan-0.9.4.md` Abschnitt 4.2 bis
> 4.5, Abschnitt 5.1 bis 5.3, Abschnitt 5.5, Abschnitt 6.2, Abschnitt 7
> und Abschnitt 9;
> `docs/ImpPlan-0.9.4-6.1.md`;
> `docs/mysql-sequence-emulation-plan.md` Abschnitt 6;
> `docs/ddl-generation-rules.md`;
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SequenceDefinition.kt`;
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
  - die bereits erfolgte Scope-Bindung von `listSupportSequenceRows(...)`
    muss in D2 nur noch gegen den Endvertrag verifiziert werden
  - rohe `==`-Vergleiche fuer Markerfelder
  - stille Numerics-Fallbacks wie `0L` oder `1L`
  - `W116` fuer fremde Markerzeilen statt nur fuer echte d-migrate-
    Degradationsfaelle
  - Routine-Diagnosen werden noch auf Routinenamen statt auf
    materialisierte Sequence-Keys aggregiert

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
- gezielte Korrektur des aktuellen D1-Scaffolds, wo dieser fuer D2
  fachlich noch nicht ausreicht
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
- spaetere Produktentscheidung zur `next_value`-Semantik jenseits
  0.9.4
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
  - `SchemaDefinition(..., sequences = ...)`
  - `schema.sequences`
  - table suppression fuer `dmg_sequences`
  - sequence-bezogene `SchemaReadNote`s
- `scanSequenceSupport(...)` bleibt D1-Infrastruktur und wird in D2
  nicht mit Fachmapping ueberladen
- zwischen D1-Snapshot und fachlichem Mapping liegt in D2 bevorzugt eine
  eigene Vorvalidierungsstufe:
  - zuerst globale Tabellenform- und Typsemantik pruefen
  - erst danach lokale Zeilenvalidierung und Materialisierung starten
  - D2 mutiert den D1-Snapshot dabei nicht rueckwirkend, sondern
    arbeitet mit einer zweiten, fachlichen Sicht auf denselben
    Rohdatenbestand
- fuer die Zeilenvalidierung fuehrt D2 eine zweite, fachliche
  Resultstufe ein:
  - bevorzugt als getrennte Validierungsobjekte wie
    `ValidSequenceRowCandidate` vs. `InvalidSequenceRowEvidence`
  - D2 darf nicht auf nicht-nullbaren Snapshot-Feldern mit
    Platzhalterwerten wie `0L` oder `1L` weiterarbeiten, wenn eine
    Pflichtspalte fachlich nicht lesbar war
- dabei bleibt `SequenceRowSnapshot` als D1-Rohtyp zulaessig, solange:
  - D1 nur rohe Leseevidenz sammelt
  - D2 daraus die fachlich gueltigen Kandidaten und invalide Evidenz
    explizit neu ableitet
- wenn `SequenceRowSnapshot` als D1-Rohtyp bestehen bleibt, muss er fuer
  D2 die Unterscheidung "nicht lesbar" vs. "fachlich gelesener Wert"
  weitergeben koennen:
  - bevorzugt ueber nullable Felder oder rohe Lesewerte ohne
    fachlichen Default
  - konkret betrifft das mindestens:
    - `nextValue: Long?`
    - `incrementBy: Long?`
    - `minValue: Long?`
    - `maxValue: Long?`
    - `cycleEnabled` nicht als bereits ausgewertetes `Boolean`, sondern
      als rohe bool-/numeric-lesbare Evidenz
    - `cacheSize: Int?` nur nach sicherem Zieltyp-Nachweis
  - nicht ueber bereits eingesetzte Platzhalter wie `0L` oder `1L`
- alternativ ist auch ein Refactoring des D1-Rohtyps zulaessig; fuer den
  D2-Vertrag ist nur entscheidend, dass fachliches Mapping nie auf
  Platzhalter-Fallbacks basiert

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
- fuer D2 gelten dabei mindestens diese MySQL-Typklassen als
  kompatibel:
  - string-artig:
    `char`, `varchar`, `text`, `tinytext`, `mediumtext`, `longtext`
  - integer-artig:
    `tinyint`, `smallint`, `mediumint`, `int`, `bigint`
  - boolean-/tinyint-artig:
    `tinyint`
  - weitere Typen wie `bit(1)` sind nur zulaessig, wenn die
    JDBC-Lesbarkeit dieselbe fachliche Bool-Semantik robust liefert;
    sonst bleiben sie Post-0.9.4 oder gelten als `invalid_shape`
- Validierung erfolgt ueber `information_schema.columns`
  (`DATA_TYPE`/`COLUMN_TYPE`) plus JDBC-Lesekompatibilitaet, nicht ueber
  exakte DDL-Textgleichheit
- "JDBC-Lesekompatibilitaet" meint in D2:
  - der Treiber liefert den Wert ueber den vorgesehenen Lesezugriff
    ohne Fachverlust oder Exception
  - reine technische Lesbarkeit als beliebiger String reicht nicht, wenn
    die Zielsemantik (`Long`, `Int`, Bool) dadurch nicht sicher
    herleitbar ist
- Typsemantik-Pruefung gehoert in D2 zur Grundformvalidierung der
  Tabelle selbst, nicht erst zur spaeten Zeilenbewertung:
  - eine Tabelle mit `name` als nicht string-artigem Typ oder
    `cycle_enabled` als fachlich nicht bool-lesbarem Typ ist
    `invalid_shape`
  - solche Faelle werden nicht als "eigentlich kanonische Tabelle mit
    kaputten Einzelzeilen" behandelt
  - bevorzugt wird dafuer die bestehende
    `checkSupportTableShape(...)`-Stufe in der Query-/Reader-Grenze
    erweitert, statt dieselbe Shape-Logik spaeter implizit in die
    Zeilenvalidierung zu verschieben
  - konkrete Query-Erweiterung:
    - `column_name` allein reicht nicht mehr
    - D2 liest fuer die Pflichtspalten zusaetzlich mindestens
      `DATA_TYPE` und `COLUMN_TYPE`
    - diese Werte werden gegen die in D2 erlaubten Typklassen geprueft
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
  - Begruendung:
    - D2 rekonstruiert den neutralen Sequence-Key aus persistierten
      Metadaten, nicht aus Generator-Namensheuristik
    - jede zusaetzliche Case- oder Sonderzeichen-Normalisierung wuerde
      ueber die in `dmg_sequences` gespeicherte Wahrheit hinausraten

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

Eine kanonische, aber leere `dmg_sequences`-Tabelle bedeutet in D2:

- `supportTableState = AVAILABLE`
- `dmg_sequences` wird als Supporttabelle unterdrueckt
- `schema.sequences` bleibt leer
- es entsteht kein `W116` allein wegen der leeren Tabelle

Vergleichssemantik fuer string-artige Zeilenwerte:

- D2 verwendet fuer `managed_by`, `format_version` und `name` eine
  einheitliche Reader-Normalisierung vor dem Fachvergleich
- diese Normalisierung ist fuer D2 verbindlich:
  - `managed_by`, `format_version`: `trim()` vor exaktem Vergleich
  - `name`: `trim()` vor Key-Bildung, ohne Case-Faltung
- dieselbe Normalisierung wird sowohl fuer Filterung als auch fuer
  Konflikterkennung auf `name` verwendet, damit kein Drift zwischen
  "Zeile gilt" und "Key kollidiert" entsteht
- ergibt `name.trim()` einen Leerstring, ist die Zeile invalide; das ist
  ein Zeilenfehler, nicht automatisch ein Verlust der gesamten
  Tabellenform

Fremde oder historisch nicht passende Zeilen:

- werden nicht zu `SequenceDefinition` gemappt
- blockieren andere gueltige Zeilen nicht
- bleiben interne Evidenz, fuehren fuer sich allein aber zu keinem
  `W116`

Eine lokal gueltige D2-Zeile muss mindestens sicher liefern koennen:

- `name`
- `next_value`
- `increment_by`
- `min_value` oder `NULL`
- `max_value` oder `NULL`
- `cycle_enabled`
- `cache_size` oder `NULL`

Sonderregeln fuer `min_value` und `max_value`:

- `NULL` ist in D2 zulaessig und wird unveraendert als `null` in das
  Neutralmodell uebernommen
- nicht lesbare Werte machen die betroffene Zeile invalide
- Werte ausserhalb des fachlich lesbaren `Long`-Bereichs gelten als
  nicht lesbar und invalidieren die betroffene Zeile

Sonderregeln fuer `cycle_enabled`:

- `cycle_enabled` ist ein Pflichtfeld und darf in D2 nicht `NULL` sein
- zulaessig sind nur sauber lesbare Bool-Repraesentationen:
  - `0` -> `false`
  - `1` -> `true`
- andere Werte wie negativ, groesser als `1` oder fachlich nicht lesbar
  machen die betroffene Zeile invalide
- D2 fuehrt hier keine implizite "alles ausser 1 ist false"-Heuristik
  ein

Sonderregeln fuer `cache_size`:

- `NULL` bleibt zulaessig und wird unveraendert als fehlender Cachewert
  behandelt
- nicht numerische Werte sind invalide
- negative Werte sind invalide
- `0` ist in D2 zulaessig und wird unveraendert als
  `SequenceDefinition.cache = 0` materialisiert; das Neutralmodell kann
  diesen Wert ohne Sonderbehandlung tragen
- Werte groesser als `Int.MAX_VALUE` sind fuer D2 nicht
  zieltyp-kompatibel und degradieren nur den betroffenen Key
- sehr grosse, aber noch `Int`-kompatible Werte bleiben zulaessig
- fuer Pflichtnumerics wie `next_value` oder `increment_by` gilt
  explizit:
  - nicht lesbare Werte machen die betroffene Zeile invalide
  - D2 fuehrt hier keine stillen Fallbacks wie `0L` oder `1L` ein
- dieselbe Regel gilt fuer andere fachlich als `Long` zu lesende
  Pflichtwerte:
  - Werte ausserhalb von `Long.MIN_VALUE .. Long.MAX_VALUE` sind fuer
    D2 nicht lesbar und invalidieren die betroffene Zeile
- `increment_by = 0` ist in D2 invalide:
  - die betroffene Zeile wird nicht materialisiert
  - es entsteht der degradierte Sequence-Key-Pfad statt stiller
    Korrektur

Gemischte Qualitaet fuer denselben neutralen Sequence-Key:

- sobald mehr als eine Zeile denselben Key beansprucht und mindestens
  eine davon widerspruechlich oder invalide ist, wird der gesamte Key
  blockiert
- es gibt keine partielle Weiternutzung "der besten Zeile"
- nach aussen erscheint genau eine aggregierte Konfliktdiagnose fuer
  diesen Key

### 5.4 Mapping auf `SequenceDefinition`

Verbindliches D2-Mapping:

- D2 setzt `SequenceDefinition.description` immer auf `null`
  (keine Quellspalte in `dmg_sequences`)
- `name` -> Map-Key in `schema.sequences`
- `next_value` -> `SequenceDefinition.start`
- `increment_by` -> `SequenceDefinition.increment`
- `min_value` -> `SequenceDefinition.minValue`
- `max_value` -> `SequenceDefinition.maxValue`
- `cycle_enabled` -> `SequenceDefinition.cycle`
- `cache_size` -> `SequenceDefinition.cache`

Weitere Regeln:

- `dmg_sequences.name` wird nach D2-`trim()`-Normalisierung als
  neutraler Sequence-Key in `schema.sequences` uebernommen
- D2 fuehrt keine eigene Umbenennung oder Kompatibilitaetsheuristik fuer
  Sequence-Namen ein
- Sortierung und Materialisierung muessen deterministisch bleiben:
  - D2 materialisiert Sequences stabil aufsteigend nach dem
    normalisierten neutralen Sequence-Key in lexikographischer Ordnung
    gemaess Kotlin-`String.compareTo()`, nicht gemaess MySQL-Collation
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
  - D1-Gating-Regel fuer D2 in Kurzform:
    - Hard-Fail nur, wenn `dmg_sequences` zuvor als vorhandene oder
      eindeutig adressierbare Primaerquelle legitimiert wurde
    - sonst kompatibler Nicht-Sequence-Fall ohne implizite
      Teilmaterialisierung
  - Testvertrag: entweder harter Abbruch nach legitimierter
    Primaerquelle oder kompatibler Nicht-Sequence-Fall gemaess Gating,
    aber keine partielle implizite Sequence-Materialisierung
- invalide oder widerspruechliche Einzelzeile
  - blockiert nur den betroffenen Sequence-Key
  - aggregierbares `W116`
- fremde oder historisch nicht passende Markerzeile
  - bleibt reine Evidenz
  - erzeugt fuer sich allein kein `W116`
- `routineState.missing`
  - erzeugt in 6.1 noch keine Diagnose allein aus dem Status
  - wird erst in D2 bei bereits bestaetigter Sequence-Materialisierung
    zur sequence-bezogenen degradierenden Begleitevidenz
  - Aggregationsebene ist der materialisierte Sequence-Key:
    - kein globales DB-weites Routine-`W116`
    - keine getrennte Note pro fehlender Routine
    - hoechstens eine degradierte `W116` pro betroffener Sequence,
      auch wenn beide Support-Routinen fehlen
- `routineState.not_accessible`
  - Sequence-Rekonstruktion bleibt moeglich
  - Aggregationsebene ist ebenfalls der materialisierte Sequence-Key
  - aggregierbares sequence-bezogenes `W116`
- fehlerhaft als `missing` klassifizierte Nutzer- oder nicht kanonische
  Routinen sind in D2 zu korrigieren:
  - bevorzugt durch konsistente Nutzung des bereits vorhandenen
    `SupportRoutineState.NON_CANONICAL` fuer Nutzer- oder anderweitig
    nicht kanonische Routinen
  - Nutzerobjekt oder nicht kanonische Routine ist keine fehlende
    Support-Routine
  - daraus darf fuer sich allein kein sequence-bezogenes `W116`
    entstehen

Leitregel:

- D2 emittiert `W116` nicht pro Rohzeile, sondern pro betroffenem
  Sequence-Schluessel
- Rohzeilen und Query-Status bleiben interne Evidenz

---

## 6. Konkrete Arbeitsschritte

### D2-0 D1-Scaffold-Korrekturen buendeln

- bestehende D1-Lesepfade dort korrigieren, wo sie D2 fachlich
  widersprechen:
  - `INVALID_SHAPE` nicht unterdruecken
  - die bereits erfolgte `ReverseScope`-Bindung von
    `listSupportSequenceRows(...)` nur noch verifizieren und gegen den
    finalen Quoting-Vertrag absichern
  - Schema-Identifier dabei sicher quoten; reine String-Interpolation
    bleibt in D2 zulaessig, wenn der Identifier vorab sicher gequotet
    bzw. fuer Backticks escaped wird
  - Markerfelder nicht roh mit `==` vergleichen
  - keine stillen `0L`-/`1L`-Fallbacks
  - keine `W116` fuer fremde Markerzeilen
  - Routine-Diagnosen nicht auf Routinenamen aggregieren
  - falsch als `missing` klassifizierte Nutzer-/Nicht-Support-Routinen
    berichtigen
- D2-0 greift dabei bewusst in bestehende D1-Scanlogik und ihre Tests
  ein; es ist kein rein vorbereitender Dokumentationsschritt

Abhaengigkeiten:

- `D2-0` vor `D2-1` bis `D2-4`
- `D2-2` vor `D2-3`
- `D2-3` vor `D2-4`
- `D2-5` kann parallel zu `D2-3`/`D2-4` vorbereitet werden, haengt aber
  vom finalen Kanonizitaetsvertrag ab
- `D2-6` laeuft inkrementell mit und deckt Unit- sowie
  Integrationstestfaelle ab

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
- Typsemantik gegen ResultSet-Lesbarkeit absichern und in die
  Grundformvalidierung einziehen
- die bestehende Shape-Pruefung gezielt um Typsemantik erweitern, statt
  diesen Teil still in die spaetere Zeilenvalidierung zu verschieben
- Zusatzspalten bewusst tolerieren und ignorieren
- `invalid_shape` frueh und eindeutig vom zeilenweisen Inhaltsfehler
  trennen
- Support-Row-Query weiter streng an `ReverseScope` binden:
  - die bestehende explizite Schema-Qualifizierung bleibt fuer D2
    akzeptabel, muss aber sicher gequotet bleiben
  - wenn ueber Identifier-Interpolation qualifiziert wird, muss das
    Quoting fuer den Schema-Namen verbindlich sicher sein:
    - Backticks im Namen werden escaped oder
    - der Name wird vorab auf einen sicheren kanonischen
      Datenbank-Identifier begrenzt
  - falls technisch nur unqualifiziert gelesen werden kann, muss D2
    explizit absichern, dass die aktive Connection-Datenbank exakt dem
    `ReverseScope` entspricht
  - kein stiller Rueckfall auf ambienten Current-Schema-Kontext

### D2-3 Zeilenvalidierung und Mapping implementieren

- `managed_by`- und `format_version`-Filter anwenden
- string-artige Pflichtfelder ueber eine zentrale D2-Normalisierung
  vergleichen
- Zeilen lokal validieren
- aus der Zeilenvalidierung explizit zwischen:
  - gueltigem Sequence-Kandidaten
  - invalider Zeilenevidenz
  unterscheiden
- gueltige Zeilen auf `SequenceDefinition` mappen
- `next_value -> start` explizit an einer zentralen Stelle dokumentiert
  und testbar implementieren
- `cycle_enabled`- und `cache_size`-Sonderwerte (`NULL`, `0`, negativ,
  nicht lesbar, Zieltyp-Ueberlauf) explizit gegen den D2-Vertrag
  pruefen
- nicht lesbare Pflichtnumerics als Invaliditaet behandeln, statt
  Defaultwerte zu injizieren

### D2-4 Konflikt- und W116-Pfad festziehen

- mehrdeutige Sequence-Keys gemaess D1 blockieren
- sequence-bezogene Aggregation statt Rohzeilen-Notes umsetzen
- fehlende, nicht bestaetigbare oder inkonsistente Support-Routinen als
  D2-spezifisch degradierte
  Sequence-Diagnose einfalten
- W116-Emission fuer fremde Markerzeilen explizit unterbinden
- Routine-Diagnosen von globalen Routinenamen auf die tatsaechlich
  materialisierten Sequence-Keys umlegen
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

- Unit-Tests in `MysqlSchemaReaderTest` fuer:
  - intakte Sequence-Rekonstruktion aus `dmg_sequences`
  - expliziten CHAR-Padding-Fall in `managed_by`, `format_version` und
    `name`
  - konsistente Normalisierung von `managed_by`,
    `format_version` und `name`
  - Zusatzspalten ohne Grundformverlust
  - Typsemantikfehler in Pflichtspalten als `invalid_shape`
  - fehlende Pflichtspalten mit degradiertem `W116`
  - `cycle_enabled`-Randfaelle (`NULL`, `0`, `1`, > `1`, negativ)
  - `cache_size`-Randfaelle (`NULL`, `0`, negativ, > `Int.MAX_VALUE`)
  - nicht lesbare Pflichtnumerics ohne stillen `0`-/`1`-Fallback
  - einzelne kaputte Zeilen ohne Totalausfall
  - mehrere Sequences parallel, davon eine degradiert
  - deterministische Sortierung bei 3+ Sequences nach normalisiertem
    Sequence-Key
  - mehrdeutige Sequence-Keys ohne stille Ueberschreibung
  - fremde Markerzeilen ohne `W116`
  - fehlende Support-Routinen bei weiter rekonstruierbaren Sequences
  - fehlende Support-Routinen erzeugen nur dann `W116`, wenn mindestens
    eine Sequence materialisiert wurde
  - `supportTableState.missing` ohne `W116` und ohne implizite
    Sequence-Materialisierung
  - `supportTableState.not_accessible` gemaess D1-Gating-Regel ohne
    partielle Sequence-Materialisierung auf Verdacht
  - Unterdrueckung von `dmg_sequences` nur bei `AVAILABLE`, nicht bei
    `INVALID_SHAPE`
  erweitern
- Integrationstests im MySQL-/Testcontainer-Setup fuer:
  - kompletten Round-Trip mit realer `dmg_sequences`-Tabelle
  - scope-gebundene Zeilenlesung gegen echte DB-Metadaten
  - Materialisierung von `schema.sequences` im End-to-End-Lauf

---

## 7. Verifikation

Pflichtfaelle fuer 6.2:

1. Eine frisch generierte MySQL-DB mit kanonischer `dmg_sequences`-
   Tabelle liefert die erwarteten `schema.sequences`.
   Dieser Fall ist primaer als MySQL-Integrationstest bzw.
   Testcontainer-Fall zu verstehen, nicht nur als reiner Unit-Test.
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
10. Fehlende Routinen erzeugen nur dann `W116`, wenn mindestens eine
    Sequence erfolgreich materialisiert wurde; bei reinem
    Nicht-Sequence-Fall bleibt der Lauf note-frei.
11. `managed_by`, `format_version` und `name` werden ueber eine
   konsistente D2-Normalisierung validiert, so dass Padding- oder
   Leseunterschiede nicht zu driftender Filter- und Key-Semantik
   fuehren.
12. Ein expliziter CHAR-Padding-Fall in `managed_by`,
    `format_version` und `name` bleibt nach `trim()` stabil und fuehrt
    weder zu Markerbruch noch zu Key-Drift.
13. `cycle_enabled` akzeptiert in D2 nur kanonische Bool-Werte
    (`0`/`1`); `NULL`, negative oder andere numerische Werte machen nur
    die betroffene Zeile invalide.
14. `min_value` und `max_value` duerfen in D2 explizit `NULL` sein;
    nicht lesbare oder ausserhalb von `Long` liegende Werte
    invalidieren nur die betroffene Zeile.
15. `cache_size`-Randfaelle sind explizit abgesichert:
    `NULL` bleibt zulaessig, `0` bleibt zulaessig, Werte groesser als
    `Int.MAX_VALUE`, invalide oder nicht lesbare Werte degradieren nur
    den betroffenen Sequence-Key.
16. `increment_by = 0` ist invalide und fuehrt nicht zu einer
    materialisierten Sequence.
17. Eine kanonische, aber leere `dmg_sequences`-Tabelle wird
    unterdrueckt, erzeugt 0 Sequences und kein `W116`.
18. Nicht lesbare Pflichtnumerics fuehren zur Invalidierung der
    betroffenen Zeile, nicht zu stillen `0`-/`1`-Defaults.
19. D2 verwendet fuer invalide Zeilen keine Fake-Werte in
    fachlich gemappten Kandidaten, sondern trennt gueltige Kandidaten
    und invalide Zeilenevidenz.
20. `supportTableState.missing` fuehrt zu keinem `W116` und zu keiner
    impliziten Sequence-Materialisierung.
21. `supportTableState.not_accessible` fuehrt nur gemaess D1-Gating zu
    Hard-Fail oder kompatiblem Nicht-Sequence-Fall, nicht zu einem
    stillen Teilergebnis.
22. Fremde oder historisch nicht passende Markerzeilen fuehren fuer
    sich allein zu keinem `W116`.
23. Zwei Zeilen mit Namen wie `seq_a` und `seq_a ` kollidieren nach
    `trim()` auf denselben Sequence-Key und laufen in den
    Konfliktpfad.
24. Fehlende oder nicht lesbare Support-Routinen aggregieren in D2 pro
    betroffenem Sequence-Key, nicht global pro Datenbank und nicht pro
    Routineobjekt.
25. `SequenceDefinition.description` bleibt in D2 explizit `null`.
26. Die Sequence-Reihenfolge bleibt bei 3+ Eintraegen deterministisch
    aufsteigend nach dem normalisierten Sequence-Key.
27. `next_value` wird in 0.9.4 auf `start` gemappt; ein spaeterer
    Laufzeitzaehler wird nicht in D2 "wegkorrigiert".
28. Ein Schema ohne bestaetigte `dmg_sequences`-Supporttabelle bleibt
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
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceReverseSupport.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt`
  nur falls fuer D2 ueber Konstantenzugriff hinaus doch Reader-Helfer
  angepasst werden muessen; nach aktuellem Plan eher nicht direkt zu
  aendern
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SequenceDefinition.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SchemaDefinition.kt`
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

### 9.2 Mehrstatement-Reads sehen theoretisch inkonsistente DDL-Sichten

Risiko:

- zwischen Shape-Check und Zeilenlesung koennte in derselben DB
  theoretisch DDL wechseln

Gegenmassnahme:

- fuer 0.9.4 wird das als tolerierbares Read-Only-Scan-Risiko
  akzeptiert
- kein eigener Transaktionsvertrag fuer den Reverse-Read in diesem
  Milestone

### 9.3 Teilinvalides `dmg_sequences` darf nicht zum Totalausfall fuehren

Risiko:

- eine manuell beschaedigte oder fremde Einzelzeile koennte sonst den
  gesamten Sequence-Reverse blockieren

Gegenmassnahme:

- Tabellenform global validieren
- Zeileninhalt lokal bewerten
- `W116` pro Sequence aggregieren, nicht pro Rohzeile

### 9.4 Ueberunterdrueckung von Nutzerobjekten

Risiko:

- eine aehnlich benannte Nutzertabelle koennte verschwinden, obwohl sie
  kein kanonisches Supportobjekt ist

Gegenmassnahme:

- Unterdrueckung nur bei `supportTableState = AVAILABLE`
- Name allein reicht nie
- Negativtests fuer nicht kanonische Tabellen im selben Namensraum

### 9.5 Weiche Feldnormalisierung erzeugt Drift

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

### 9.6 Unsaubere Bool-/Integer-Lesung verfaelscht Sequence-Zeilen

Risiko:

- `cycle_enabled`, `next_value`, `increment_by` oder `cache_size`
  koennten bei nicht lesbaren Werten still in scheinbar gueltige
  Standardwerte kippen
- daraus entstuenden fachlich falsche `SequenceDefinition`s

Gegenmassnahme:

- `cycle_enabled` nur fuer `0`/`1` akzeptieren
- nicht lesbare Pflichtnumerics und Zieltyp-Ueberlaeufe immer als
  Zeileninvaliditaet behandeln
- gueltige Kandidaten und invalide Evidenzobjekte sauber trennen

### 9.7 Konfliktpfad wird spaeter aufgeweicht

Risiko:

- D2 oder D3 fuehren doch wieder implizite Ueberschreibung oder
  "beste Zeile gewinnt" ein

Gegenmassnahme:

- Konfliktvertrag aus 6.1 unveraendert in D2 uebernehmen
- Akzeptanztests fuer mehrdeutige Keys verpflichtend machen

### 9.8 Support-Routinen werden faelschlich zur Pflicht fuer D2

Risiko:

- fehlende oder nicht lesbare `dmg_nextval`-/`dmg_setval`-Metadaten
  koennten unnoetig den Sequence-Reverse blockieren

Gegenmassnahme:

- Routinen in D2 nur als degradierende Begleitevidenz behandeln
- `SequenceDefinition` weiter aus `dmg_sequences` rekonstruieren, sofern
  die Primaerquelle lesbar ist

### 9.9 Viele Sequence-Zeilen bleiben ein In-Memory-Scan

Risiko:

- sehr grosse `dmg_sequences`-Tabellen werden in 0.9.4 voll in den
  Reader geladen

Gegenmassnahme:

- fuer 0.9.4 bewusste In-Memory-Entscheidung beibehalten
- Performance-Optimierungen wie Streaming oder Pagination sind
  Post-0.9.4

### 9.10 Unqualifizierte Support-Row-Queries driften vom Scope ab

Risiko:

- eine unqualifizierte `dmg_sequences`-Abfrage verlaesst sich still auf
  den ambienten Connection-Kontext
- in Multi-Schema- oder Testaufbauten koennten dadurch falsche Tabellen
  gelesen werden

Gegenmassnahme:

- Support-Row-Query explizit an `ReverseScope` binden
- wenn explizite Schema-Qualifizierung technisch nicht moeglich ist,
  aktive Connection-Datenbank gegen `ReverseScope` verifizieren
