# Implementierungsplan: Milestone 0.9.4 - Beta: MySQL-Sequence Reverse-Engineering und Compare

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.9.4. Es baut direkt auf 0.9.3 auf und schliesst die dort
> bewusst offen gelassenen Arbeiten fuer MySQL-Sequence-Emulation:
> Reverse-Engineering der kanonischen Hilfsobjekte und compare-stabiles
> Verhalten auf Neutralmodell-Ebene.
>
> Status: Draft (2026-04-21)
> Referenzen: `docs/roadmap.md` Abschnitt "Milestone 0.9.4",
> `docs/mysql-sequence-emulation-plan.md` Abschnitt 6,
> `docs/implementation-plan-0.9.3.md`,
> `docs/ddl-generation-rules.md`,
> `docs/cli-spec.md`,
> `docs/guide.md`,
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/DefaultValue.kt`,
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SequenceDefinition.kt`,
> `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt`,
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadOptions.kt`,
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadResult.kt`,
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadNote.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaReverseRunner.kt`,
> `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`,
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareHelpers.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueries.kt`,
> `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt`.

---

## 1. Ziel

Milestone 0.9.4 macht die in 0.9.3 eingefuehrte MySQL-Sequence-
Emulation vollstaendig:

- generierte Hilfsobjekte (`dmg_sequences`, `dmg_nextval`,
  `dmg_setval`, kanonische `BEFORE INSERT`-Trigger) werden beim
  Reverse wieder auf `schema.sequences` und
  `DefaultValue.SequenceNextVal(...)` zurueckgefaltet
- `schema compare` bleibt gegen generierte MySQL-Hilfsobjekte stabil
  und zeigt Sequences statt Support-Objekt-Rauschen
- degradierte Datenbankzustaende werden sichtbar, aber nicht still
  fehlinterpretiert

Der Milestone liefert damit drei konkrete Nutzerergebnisse:

- `schema reverse` erkennt die kanonische MySQL-Sequence-Emulation
  robust genug fuer den produktiven Round-Trip
- `schema compare` zwischen neutralem Schema und einer realen MySQL-DB
  bleibt sequence-stabil, obwohl die DB nur Hilfsobjekte kennt
- fehlende oder beschaedigte Supportobjekte fuehren zu klaren Reverse-
  Warnungen (`W116`) statt zu still falschen Ergebnissen

Bewusst nicht Teil dieses Milestones:

- Erkennung beliebiger handgeschriebener MySQL-Sequence-Loesungen
- Lockerung des kanonischen Namens-/Marker-Vertrags
- Wechsel des Generator-Defaults von `action_required` auf
  `helper_table`
- SQLite- oder andere Dialektarbeiten

---

## 2. Ausgangslage

### 2.1 Stand aus 0.9.3

Der Generatorpfad fuer MySQL ist vorhanden:

- `DefaultValue.SequenceNextVal(sequenceName)` existiert im
  Neutralmodell
- `MysqlDdlGenerator` erzeugt im Modus `helper_table`
  - die Support-Tabelle `dmg_sequences`
  - die Support-Funktionen `dmg_nextval` und `dmg_setval`
  - kanonische `BEFORE INSERT`-Trigger fuer sequence-basierte Spalten
- Warning-Codes `W114`, `W115` und `W117` sind generatorseitig
  etabliert
- `SchemaCompareHelpers.defaultValueToString(...)` kann
  `SequenceNextVal` bereits darstellen

Damit ist der Vorwaertspfad fachlich vorhanden, aber nur halb
abgeschlossen: die Ziel-DB enthaelt heute reale Hilfsobjekte, der
Reverse-Pfad kennt diese Objekte jedoch noch nicht.

### 2.2 Aktueller Reader-Stand

`MysqlSchemaReader` liest heute:

- Basistabellen
- Views
- Funktionen
- Prozeduren
- Trigger

aber noch nicht:

- emulierte Sequences aus `dmg_sequences`
- Sequence-Support-Routinen als besondere Infrastruktur
- sequence-basierte Spaltendefaults aus kanonischen Support-Triggern

Konsequenz:

- `dmg_sequences` landet heute als normale Tabelle im neutralen Schema
- `dmg_nextval` und `dmg_setval` landen ggf. als normale Funktionen
- generierte Sequence-Trigger landen ggf. als normale Trigger
- `schema.sequences` bleibt leer
- Spalten mit `SequenceNextVal` werden beim Reverse nicht rekonstruiert

### 2.3 Compare ist fachlich fast fertig, aber auf den falschen Input angewiesen

`SchemaComparator` vergleicht bereits neutral:

- `schema.sequences`
- Spaltendefaults inklusive `DefaultValue.SequenceNextVal`
- Trigger, Funktionen und Tabellen als eigene Objektarten

Das Core-Problem fuer 0.9.4 ist daher primaer **nicht** ein fehlender
Diff-Algorithmus, sondern die Vorstufe:

- solange der Reader MySQL-Hilfsobjekte nicht zurueckfaltet,
  produziert Compare Hilfsobjekt-Rauschen
- sobald der Reader sauber normalisiert, bleibt der vorhandene
  Comparator weitgehend ausreichend

---

## 3. Scope

### 3.1 In Scope

- Phase D: `MysqlSchemaReader` um Sequence-Erkennung erweitern
- kanonische Erkennung von:
  - `dmg_sequences`
  - `dmg_nextval`
  - `dmg_setval`
  - sequence-bezogenen `BEFORE INSERT`-Triggern
- Rekonstruktion von `SequenceDefinition` aus `dmg_sequences`-Zeilen
- Rekonstruktion von `DefaultValue.SequenceNextVal(...)` an den
  betroffenen Spalten
- Unterdrueckung erkannter Supportobjekte im neutralen Modell
  (`tables`, `functions`, `triggers`)
- degradierter Reverse-Vertrag mit `W116`
- Phase E: compare-stabile Absicherung fuer:
  - neutral -> MySQL-DDL -> MySQL reverse -> neutral
  - file-vs-db und db-vs-db Compare-Pfade
- Unit-, Integration-, CLI- und Round-Trip-Tests
- Doku-Nachzug fuer CLI-/Guide-/Roadmap-/Regel-Dokumente

### 3.2 Bewusst nicht Teil von 0.9.4

- fuzzy Erkennung von nur aehnlichen Hilfsobjekten
- automatische Reparatur fehlender Routinen oder Trigger
- Rekonstruktion manuell veraenderter Trigger-Bodys ohne Marker
- neue oeffentliche CLI-Flags fuer `schema reverse` oder
  `schema compare`
- neue Warning- oder Fehlercodes jenseits des bereits reservierten
  `W116`, sofern sich im Coding kein echter Vertragsspalt ergibt

---

## 4. Leitentscheidungen

### 4.1 Reverse bleibt kanonisch, nicht heuristisch

Verbindliche Entscheidung:

- MySQL-Sequence-Emulation wird nur erkannt, wenn Name, Marker und
  minimale Form zusammenpassen
- ein Objekt mit aehnlichem Namen, aber ohne Marker, wird **nicht**
  als d-migrate-Supportobjekt interpretiert
- 0.9.4 fuehrt keinen generischen "best effort"-Importer fuer fremde
  Sequence-Loesungen ein

Begruendung:

- der 0.9.3-Generator erzeugt bewusst kanonische Objekte
- Reverse darf daraus keinen unscharfen Vertragsraum machen
- False Positives im Reverse waeren fuer Compare schaedlicher als ein
  bewusst nicht erkannter Fremdaufbau

### 4.2 `dmg_sequences` ist die primaere Wahrheitsquelle

Verbindliche Entscheidung:

- `SequenceDefinition` wird primaer aus `dmg_sequences` rekonstruiert
- Routinen und Trigger sind Zusatzsignale:
  - Routinen bestaetigen die Betriebsfaehigkeit des Supportpfads
  - Trigger liefern die Zuordnung auf Spaltenebene
- fehlt die Support-Tabelle oder passt ihre kanonische Form nicht,
  findet **kein** Sequence-Reverse statt

Begruendung:

- `includeFunctions` und `includeTriggers` duerfen den Sequence-Reverse
  nicht steuern
- die Support-Tabelle ist der einzige Ort mit den eigentlichen
  Sequence-Metadaten

### 4.3 Include-Flags bleiben Sichtbarkeitsflags, keine internen Sperren

Verbindliche Entscheidung:

- `SchemaReadOptions.includeFunctions`, `includeProcedures` und
  `includeTriggers` steuern weiterhin nur, ob nutzerdefinierte Objekte
  im neutralen Ergebnis erscheinen
- fuer die interne Erkennung der Sequence-Emulation darf
  `MysqlSchemaReader` Support-Routinen und Support-Trigger auch dann
  inspizieren, wenn die entsprechenden Include-Flags `false` sind

Begruendung:

- der Produktvertrag aus dem Sequence-Plan verlangt explizit, dass
  Sequence-Reverse nicht von diesen Flags abhaengt
- ohne diese Entkopplung waere Round-Trip-Verhalten fragil und fuer
  Nutzer schwer nachvollziehbar

### 4.4 Degradierte Zustaende werden als `W116` sichtbar, nicht still kaschiert

Verbindliche Entscheidung:

- wenn `dmg_sequences` gueltig ist, aber benoetigte Supportobjekte
  fehlen oder nicht kanonisch sind, wird Reverse nicht komplett
  abgebrochen
- stattdessen gilt:
  - Sequences werden so weit wie sicher moeglich rekonstruiert
  - Spaltenzuordnungen werden nur dort rekonstruiert, wo der Trigger
    kanonisch bestaetigt ist
  - fehlende Routinen oder Trigger fuehren zu `W116`

Praktische Semantik:

- fehlende oder ungueltige Routinen:
  - Sequence-Metadaten bleiben lesbar
  - `W116` markiert "rekonstruiert, aber nicht voll betriebsfaehig"
- fehlender oder ungueltiger Support-Trigger fuer eine Spalte:
  - die Sequence bleibt erhalten
  - die betroffene Spalte erhaelt **kein**
    `DefaultValue.SequenceNextVal`
  - `W116` nennt das betroffene Objekt

### 4.5 Compare wird ueber Reverse-Normalisierung stabilisiert

Verbindliche Entscheidung:

- 0.9.4 bevorzugt einen neutralen Compare ohne MySQL-Sonderzweig
- Hilfsobjekt-Rauschen wird primaer im Reader beseitigt, nicht durch
  spaetes Herausfiltern im Comparator
- compare-seitige Codeaenderungen sind nur dann zulaessig, wenn nach
  sauberer Reader-Normalisierung noch ein echter Neutralmodell-Spalt
  verbleibt

---

## 5. Zielarchitektur

### 5.1 Reader-Pipeline fuer Sequence-Supportobjekte

`MysqlSchemaReader.read(...)` wird in 0.9.4 logisch in zwei Ebenen
geteilt:

1. Basis-Metadaten lesen wie bisher
2. internen Support-Scan fuer Sequence-Emulation ausfuehren
3. aus dem Support-Scan drei Ergebnisse gewinnen:
   - `sequences`
   - Spaltenzuordnungen `table.column -> sequenceName`
   - erkannte Supportobjekte zur Unterdrueckung im neutralen Modell
4. normale Tabellen/Funktionen/Trigger ohne erkannte Supportobjekte in
   `SchemaDefinition` uebernehmen
5. Spaltendefaults nachtraeglich mit `SequenceNextVal(...)` anreichern
6. Reverse-Notes (`W116`) an `SchemaReadResult` anhaengen

Der Reader braucht dafuer keine neue oeffentliche API, aber eine
interne Struktur, die Supporterkennung von normaler Objektuebernahme
trennt.

### 5.2 Benoetigte Metadaten-Erweiterungen

`MysqlMetadataQueries` reicht fuer 0.9.4 in der aktuellen Form nicht
aus. Erwartete Erweiterungen:

- Tabellenform von `dmg_sequences` gezielt lesen und validieren
- Zeilen aus `dmg_sequences` selektiv laden
- Support-Routinen gezielt nach Namen lesen, unabhaengig von
  `includeFunctions`
- Support-Trigger gezielt lesen, unabhaengig von `includeTriggers`

Naheliegende neue Hilfsabfragen:

- `listTableColumns(...)` bzw. Wiederverwendung von `listColumns(...)`
  fuer `dmg_sequences`
- `listSequenceSupportRows(...)`
- `findFunctionByName(...)` oder `listFunctionsByNames(...)`
- `listTriggersByPrefix(...)` oder `listTriggersForTables(...)`

Wichtig:

- die Reader-Erweiterung soll nicht auf "alle Trigger immer lesen"
  fuer die Nutzer-API hinauslaufen
- interne Supportabfragen duerfen gezielt und billig bleiben

### 5.3 Rekonstruktion der `SequenceDefinition`

Eine Sequence wird nur dann aus `dmg_sequences` rekonstruiert, wenn:

- die Tabelle `dmg_sequences` existiert
- die kanonischen Spalten vorhanden sind
- jede auszuwertende Zeile `managed_by = 'd-migrate'` und
  `format_version = 'mysql-sequence-v1'` traegt

Mapping:

- `name` -> Sequence-Name
- `next_value` -> `start`
- `increment_by` -> `increment`
- `min_value` -> `minValue`
- `max_value` -> `maxValue`
- `cycle_enabled` -> `cycle`
- `cache_size` -> `cache`

Leitlinie fuer 0.9.4:

- `next_value` wird beim Reverse als derselbe Startwert interpretiert,
  den der Generator initial seeded
- Live-Laufzeitabweichungen nach produktiver Nutzung sind fuer Compare
  erwartbar; 0.9.4 zielt auf generate/reverse-Stabilitaet, nicht auf
  "aktuellen Zaehlerstand als Sollzustand"

Das muss in Doku und Tests explizit bleiben, damit Compare nicht als
"Laufzeitzustands-Diff" missverstanden wird.

### 5.4 Rekonstruktion von `SequenceNextVal` ueber Support-Trigger

Fuer sequence-basierte Spalten wird ein interner Trigger-Scan
eingefuehrt.

Ein Support-Trigger gilt als kanonisch, wenn mindestens diese Kriterien
zugleich zutreffen:

- Triggername entspricht `MysqlSequenceNaming.triggerName(table, column)`
- Marker-Kommentar enthaelt
  `d-migrate:mysql-sequence-v1 object=sequence-trigger`
- Marker benennt dieselbe Sequence, Tabelle und Spalte wie Name und
  Metadaten
- Timing/Event sind `BEFORE INSERT`
- Trigger-Body setzt `NEW.<column>` ueber `dmg_nextval('<sequence>')`,
  wenn `NEW.<column> IS NULL`

Ergebnis:

- die Spalte bekommt im neutralen Modell
  `DefaultValue.SequenceNextVal(sequenceName)`
- der Support-Trigger erscheint nicht zusaetzlich in
  `schema.triggers`

### 5.5 Unterdrueckung von Supportobjekten

Sobald ein Objekt als d-migrate-Supportobjekt erkannt wurde, darf es
nicht parallel als normales Nutzerobjekt im Reverse-Ergebnis landen.

Das gilt fuer:

- Tabelle `dmg_sequences`
- Funktionen `dmg_nextval` und `dmg_setval`
- kanonische Sequence-Support-Trigger

Nicht erkannte oder nicht kanonische Objekte bleiben dagegen normale
Objekte. 0.9.4 fuehrt bewusst keine aggressive "wahrscheinlich
Supportobjekt"-Unterdrueckung ein.

### 5.6 Compare-Stabilisierung ohne Spezial-Comparator

Wenn der Reader korrekt arbeitet, sollte folgender Pfad ohne
MySQL-Sonderlogik stabil sein:

- Quellschema (neutral)
- `schema generate --target mysql --mysql-named-sequences helper_table`
- Ausfuehrung gegen reale MySQL-DB
- `schema reverse`
- `schema compare` neutral vs. reverse

Erwartetes Ergebnis:

- keine Zusatzdiffs fuer `dmg_sequences`, `dmg_nextval`,
  `dmg_setval` oder kanonische Sequence-Trigger
- Sequences und sequence-basierte Spaltendefaults erscheinen im Diff
  nur dann, wenn sich ihre neutralen Eigenschaften unterscheiden

---

## 6. Konkrete Arbeitspakete

### 6.1 Phase D1: Reader-Vertrag und Metadatenzugriff festziehen

- `MysqlSchemaReader` in interne Teilschritte zerlegen:
  - normale Objektlese-Pfade
  - Sequence-Support-Scan
  - Post-Processing fuer Spaltendefaults
- `MysqlMetadataQueries` um gezielte Support-Abfragen erweitern
- Hilfsdatentyp fuer erkannte Supportobjekte einfuehren, z. B.
  `MysqlSequenceReverseSupport`
- festlegen, an welcher Stelle `W116` gesammelt und strukturiert an
  `SchemaReadResult.notes` angehaengt wird

Akzeptanzkriterium:

- der Reader kann intern Supportobjekte inspizieren, ohne dass sich das
  oeffentliche `SchemaReadOptions`-API aendert

### 6.2 Phase D2: Sequence-Reverse aus `dmg_sequences`

- kanonische Tabellenform von `dmg_sequences` validieren
- Support-Zeilen in `SequenceDefinition` mappen
- `schema.sequences` damit fuellen
- `dmg_sequences` aus `schema.tables` unterdruecken, wenn die Tabelle
  als kanonisches Supportobjekt erkannt wurde
- fehlende oder inkonsistente Support-Routinen als `W116` markieren,
  ohne die Sequence-Rekonstruktion komplett zu verlieren

Akzeptanzkriterien:

- reverse einer frisch generierten MySQL-DB liefert die erwarteten
  `schema.sequences`
- `dmg_sequences` taucht nicht mehr als normale Tabelle im Schema auf
- fehlen `dmg_nextval` oder `dmg_setval`, bleiben die Sequences
  sichtbar, aber der Report enthaelt `W116`

### 6.3 Phase D3: Sequence-Default-Reverse ueber Trigger

- Support-Trigger intern laden, auch bei `includeTriggers = false`
- kanonische Trigger via Name + Marker + Minimalform validieren
- Spaltenzuordnung `table.column -> sequenceName` aufbauen
- betroffene `ColumnDefinition.default` zu
  `DefaultValue.SequenceNextVal(sequenceName)` setzen
- erkannte Support-Trigger aus `schema.triggers` unterdruecken
- bei fehlenden oder nicht kanonischen Triggern `W116` emittieren
  statt eine falsche Zuordnung zu raten

Akzeptanzkriterien:

- reverse einer sequence-basierten Spalte rekonstruiert den neutralen
  Default
- derselbe Pfad funktioniert auch mit `includeTriggers = false`
- kanonische Support-Trigger erscheinen nicht als normale Trigger

### 6.4 Phase E1: Compare-Stabilisierung

- bestehende Compare-Tests um sequence-emulierte MySQL-Faelle erweitern
- file-vs-file, file-vs-db und db-vs-db Pfade absichern
- pruefen, ob `SchemaComparator` unveraendert ausreicht
- nur falls notwendig: minimale compare-seitige Anpassung auf
  Neutralmodell-Ebene, keine MySQL-Stringfilter

Akzeptanzkriterien:

- neutral vs. reverse ist fuer intakte MySQL-Sequence-Emulation
  diff-frei
- geaenderte Sequence-Metadaten fuehren zu `sequencesChanged`
- Hilfsobjekt-Rauschen taucht im Diff nicht auf

### 6.5 Phase E2: Doku- und Vertragsnachzug

- `docs/roadmap.md` fuer 0.9.4-Status aktualisieren
- `docs/mysql-sequence-emulation-plan.md` Phase D/E auf
  Umsetzungsstand ziehen
- `docs/cli-spec.md` fuer Reverse-/Compare-Vertrag und `W116`
  konkretisieren
- `docs/guide.md` um Reverse-/Compare-Hinweise fuer MySQL-Sequences
  ergaenzen

Akzeptanzkriterium:

- die Nutzerdokumentation beschreibt klar, wann Sequences sauber
  erkannt werden und wann `W116` erscheint

### 6.6 Phase D/E: Tests und Verifikation

- `MysqlSchemaReaderTest` um Supportobjekt- und Degradationsfaelle
  erweitern
- neue oder erweiterte MySQL-Testcontainer-Integrationstests fuer
  Reverse und Compare anlegen
- `SchemaCompareRunnerTest` bzw. CLI-Compare-Tests fuer db-basierte
  Sequence-Faelle erweitern
- Round-Trip-Test neutral -> generate -> MySQL -> reverse -> compare
  verbindlich machen

Akzeptanzkriterien:

- Unit- und Integrationstests belegen die Sequence-Stabilitaet
- degradierte Faelle erzeugen reproduzierbar `W116`
- Compare bleibt ohne Hilfsobjekt-Rauschen

---

## 7. Verifikationsstrategie

Pflichtfaelle fuer 0.9.4:

1. **Intakter Round-Trip**
   neutrales Schema mit benannter Sequence und
   `SequenceNextVal`-Spalte -> MySQL-DDL -> echte MySQL-DB ->
   reverse -> compare = identisch

2. **Sequence-Metadaten-Diff**
   `increment`, `minValue`, `maxValue`, `cycle` oder `cache`
   unterscheiden sich -> Compare zeigt genau Sequence-Diffs

3. **Support-Routinen fehlen**
   `dmg_sequences` vorhanden, `dmg_nextval` oder `dmg_setval`
   entfernt -> Sequence bleibt reverse-bar, Report enthaelt `W116`

4. **Support-Trigger fehlt**
   Sequence-Zeile vorhanden, Trigger fuer eine betroffene Spalte fehlt
   -> Sequence bleibt sichtbar, Spaltendefault wird nicht rekonstruiert,
   Report enthaelt `W116`

5. **Include-Flag-Unabhaengigkeit**
   Reverse mit `includeTriggers = false` und
   `includeFunctions = false` rekonstruiert Sequences trotzdem

6. **Supportobjekt-Unterdrueckung**
   intakte Supportobjekte erscheinen nicht zusaetzlich in
   `tables`, `functions` oder `triggers`

7. **Nicht-kanonische Objekte bleiben Nutzerobjekte**
   aehnlich benannte, aber nicht markierte Objekte werden nicht
   versehentlich weggefiltert

Coverage-Ziel:

- mindestens dieselbe Modulschwelle wie in den betroffenen
  Driver-/Application-Modulen
- Schwerpunkt auf neuen Reverse-Pfaden in `driver-mysql`

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueries.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceNaming.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReaderTest.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/SchemaComparator.kt`
  vermutlich nur testseitig relevant, produktiv idealerweise nicht
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaReverseRunner.kt`
  nur falls Note-/Report-Sichtbarkeit nachgezogen werden muss
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`
  primaer testseitig
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaCompareTest.kt`
- `docs/mysql-sequence-emulation-plan.md`
- `docs/cli-spec.md`
- `docs/guide.md`
- `docs/roadmap.md`

Optional, falls sich Testaufbau anbietet:

- neuer MySQL-E2E-Test fuer reverse/compare im CLI-Modul statt nur
  Reader-Integrationstests im Driver-Modul

---

## 9. Risiken und offene Punkte

### 9.1 Marker-Sichtbarkeit in MySQL-Metadaten

Offen ist nicht der Generatorvertrag, sondern die konkrete
Metadatenquelle:

- Kommentare muessen im Reverse robust auslesbar sein
- falls `information_schema.routines.routine_definition` oder
  `information_schema.triggers.action_statement` Marker nicht
  verlaesslich transportieren, braucht der Reader einen gezielten
  Fallback

Gegenmassnahme:

- frueh mit echter MySQL-DB absichern
- Metadatenstrategie vor breiter Testimplementierung festziehen

### 9.2 Laufzeitzustand vs. Sollzustand in `dmg_sequences`

`next_value` ist in einer laufenden Datenbank kein reiner
Designzeitwert mehr.

Risiko:

- ein Compare gegen eine produktiv genutzte Datenbank zeigt Sequence-
  Aenderungen, obwohl nur Laufzeitverbrauch stattgefunden hat

Konsequenz fuer 0.9.4:

- der Milestone optimiert bewusst den Generate/Reverse-Round-Trip
- ob Compare spaeter Laufzeitzaehler von Sollmetadaten trennt, ist ein
  eigener Produktentscheid und **nicht** Teil von 0.9.4

### 9.3 Ueberunterdrueckung von Nutzerobjekten

Wenn die Supporterkennung zu breit ist, koennten legitime Nutzerobjekte
verschwinden.

Gegenmassnahme:

- nur kanonische Objekte unterdruecken
- Name alleine reicht nie
- Tests fuer aehnlich benannte, aber nicht markierte Objekte sind
  Pflicht

### 9.4 Unterdrueckung ohne Spaltenzuordnung waere fachlich unvollstaendig

Nur `schema.sequences` zu rekonstruieren reicht nicht.

Risiko:

- Compare bleibt bei den betroffenen Tabellen noisy, weil
  `SequenceNextVal` an den Spalten fehlt

Gegenmassnahme:

- Trigger-Reverse ist kein Optionalteil, sondern Kern des Milestones
- Akzeptanzkriterien muessen Sequences **und** Spaltendefaults abdecken

---

## 10. Entscheidungsempfehlung

0.9.4 sollte bewusst reader-first umgesetzt werden:

1. erst den kanonischen Reverse-Vertrag fuer `dmg_sequences`,
   Routinen und Trigger sauber bauen
2. danach Compare nur noch gegen das normalisierte Neutralmodell
   absichern
3. compare-seitige Sonderlogik nur als letzte Ausnahme zulassen

Damit bleibt die Architektur konsistent:

- Generator erzeugt kanonische Supportobjekte
- Reader faltet exakt diese Supportobjekte zurueck
- Compare arbeitet weiter auf neutralen Schemas statt auf
  Dialektartefakten

Das ist die kleinste plausible 0.9.4-Loesung mit sauberem
Langzeitvertrag.
