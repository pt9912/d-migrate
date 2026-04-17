# Implementierungsplan: Vollständige MySQL-Sequence-Emulation

> Status: Draft (2026-04-17)
>
> Zweck: Produktplan fuer eine **vollstaendige** MySQL-Variante von
> benannten Sequences im DDL-Pfad, inklusive DDL-Generierung,
> Reverse-Engineering, Compare-/Diff-Kompatibilitaet und klarem
> Betriebsvertrag.
>
> Referenzen:
> - `docs/ddl-generation-rules.md` §7
> - `docs/neutral-model-spec.md` §9
> - `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SequenceDefinition.kt`
> - `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
> - `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`
> - MySQL 8.0 Reference Manual, "Data Type Default Values"
> - MySQL 8.0 Reference Manual, "CREATE TRIGGER Statement"

---

## 1. Ziel

MySQL soll benannte Sequences nicht mehr nur mit `E056` ueberspringen,
sondern als echte, wiedererkennbare Produktfunktion unterstuetzen.

Die Vollvariante liefert:

- generierbare MySQL-DDL fuer neutrale `sequences`
- generierbare und reverse-bare Nutzung von benannten Sequences fuer
  sequence-basierte Spaltenwerte
- einen klaren Laufzeitvertrag, wie Anwendungen den naechsten Wert abrufen
- Reverse-Engineering der emulierten Strukturen zurueck auf
  `SequenceDefinition`
- Compare-/Diff-Verhalten auf Neutralmodell-Ebene statt auf den
  MySQL-Hilfsobjekten
- definierte Report-/Warning-Semantik bei lossy Mapping

Die Vollvariante liefert bewusst nicht:

- stille Migration beliebiger, handgeschriebener MySQL-Sequence-Loesungen
- Cross-Session-Serialisierung jenseits des dokumentierten MySQL-Vertrags

---

## 2. Ausgangslage

Heute gilt:

- PostgreSQL erzeugt native `CREATE SEQUENCE`
- SQLite und MySQL erzeugen `action_required` (`E056`)
- die Doku behauptet fuer MySQL bereits Emulation, der Code kennt aber
  nur den Skip-Pfad
- das neutrale Modell hat `SequenceDefinition`, aber keinen expliziten
  Verwendungs-Link von Spalten auf eine Sequence-Nutzung
- der MySQL-Reader liest keine Sequences zurueck

Konsequenz: Eine "vollstaendige" MySQL-Unterstuetzung ist groesser als
ein lokaler Patch in `generateSequences(...)`.

---

## 3. Produktentscheidung

### 3.1 Betriebsmodi

MySQL bekommt einen expliziten Modus:

- `action_required`
  - heutiges Verhalten
  - `E056`, keine Emulation
- `helper_table`
  - produktive Emulation ueber kanonische MySQL-Hilfsobjekte

Vorgeschlagener Optionspfad:

```yaml
ddl:
  mysql:
    named_sequences: action_required   # action_required | helper_table
```

Default fuer die Einfuehrungsphase:

- CLI/Runner-Default bleibt vorerst `action_required`
- `helper_table` ist opt-in
- ein spaeterer Milestone kann den Default kippen, wenn Reverse und
  Compare stabil sind

### 3.2 Kanonische Emulationsobjekte

Die Emulation muss **kanonisch** sein, damit Reverse und Compare sie
zuverlaessig erkennen koennen.

Vorgeschlagene Objekte:

- zentrale Support-Tabelle `dmg_sequences`
- eine MySQL-Routine fuer Wertvergabe, z. B. `dmg_nextval(seq_name)`
- optional eine zweite Routine `dmg_setval(seq_name, value)` fuer
  Synchronisation und Admin-Aufgaben
- pro sequence-basierter Spalte ein kanonischer `BEFORE INSERT`-Trigger,
  der bei `NEW.<column> IS NULL` einen Wert ueber `dmg_nextval(...)`
  einsetzt

Vorgeschlagenes Tabellenschema:

```sql
CREATE TABLE `dmg_sequences` (
    `managed_by` VARCHAR(32) NOT NULL,
    `format_version` VARCHAR(16) NOT NULL,
    `name` VARCHAR(255) NOT NULL,
    `next_value` BIGINT NOT NULL,
    `increment_by` BIGINT NOT NULL,
    `min_value` BIGINT NULL,
    `max_value` BIGINT NULL,
    `cycle_enabled` TINYINT(1) NOT NULL,
    `cache_size` INT NULL,
    PRIMARY KEY (`name`)
) ENGINE=InnoDB;
```

Jede neutrale Sequence belegt genau eine Zeile in `dmg_sequences`.

### 3.3 Namespace- und Kollisionsvertrag

Die Vollvariante darf Hilfsobjekte nicht nur ueber harte Namen "erraten".

Deshalb gilt:

- der MySQL-Support nutzt einen reservierten, d-migrate-spezifischen
  Namespace-Prefix
- Supportobjekte tragen zusaetzlich einen inhaltlichen Marker
- Reverse erkennt Hilfsobjekte nur, wenn **Name, Form und Marker**
  zusammenpassen

Vorgeschlagener Prefix:

- Tabelle: `dmg_sequences`
- Routinen: `dmg_nextval`, `dmg_setval`
- Trigger: deterministisch begrenztes Schema
  `dmg_seq_<table16>_<column16>_<hash10>_bi`

Vorgeschlagene Marker:

- `managed_by = 'd-migrate'`
- `format_version = 'mysql-sequence-v1'`
- Routinen starten mit einem kanonischen Marker-Kommentar, z. B.
  `/* d-migrate:mysql-sequence-v1 object=nextval */`
- Sequence-Support-Trigger starten mit einem kanonischen Marker-Kommentar,
  z. B.
  `/* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=<name> table=<table> column=<column> */`

Trigger-Namensvertrag:

- MySQL-Identifier sind auf 64 Zeichen begrenzt
- der Triggername wird deshalb **nicht** frei aus vollem Tabellen- und
  Spaltennamen zusammengesetzt
- fuer `table16`, `column16` und `hash10` wird **nicht** die rohe
  MySQL-Metadaten-Schreibweise verwendet, sondern die neutrale
  Kanonform des Identifiers
- diese Kanonform ist genau die Identifier-Schreibweise, die im
  neutralen Schema verwendet und beim Reverse nach der
  `lower_case_table_names`-bewussten Reader-Normalisierung ausgegeben
  wird
- stattdessen gilt verbindlich:
  - Prefix `dmg_seq_`
  - `table16`: erste 16 Zeichen des kanonischen neutralen
    Tabellennamens
  - `column16`: erste 16 Zeichen des kanonischen neutralen
    Spaltennamens
  - `hash10`: erste 10 Hex-Zeichen eines stabilen Hashes ueber
    `<canonical-table>\u0000<canonical-column>\u0000<sequence>`
  - Suffix `_bi`
- dieses Schema bleibt immer <= 64 Zeichen
- Reverse identifiziert Sequence-Support-Trigger **nicht** nur ueber den
  Namen, sondern ueber Name + Marker-Kommentar + kanonische Body-Form
- Kollisionen auf `hash10` gelten als praktisch unwahrscheinlich; tritt
  dennoch eine Kollision auf, ist das ein expliziter Generate-Fehler und
  kein stilles Umbenennen

Optional spaeter erweiterbar:

- konfigurierbarer Prefix `ddl.mysql.sequence_support_prefix`

Phase 1 nutzt jedoch bewusst einen festen kanonischen Prefix, damit
Reverse und Compare nicht mit frei waehlbaren Namen verkompliziert
werden.

Konfliktpfad bei Vorwaertsgenerierung:

- wenn das neutrale Schema bereits ein Objekt mit reserviertem
  Hilfsnamen enthaelt (`dmg_sequences`, `dmg_nextval`, `dmg_setval`,
  `dmg_seq_*` nach dem kanonischen Namensschema),
  darf `helper_table` **nicht** still generieren
- stattdessen erzeugt der Generator einen expliziten Fehler- oder
  `action_required`-Pfad mit eigenem Code
- Phase A muss entscheiden, ob dieser Konflikt als Modellfehler,
  Generate-Fehler oder `action_required` modelliert wird
- keine implizite automatische Umbenennung in Phase 1

### 3.4 Laufzeitvertrag

Der Produktvertrag muss mehr liefern als "es gibt irgendwo eine
Hilfstabelle".

Deshalb wird verbindlich dokumentiert:

- naechster Wert wird ueber `dmg_nextval('<name>')` geholt
- aktueller Zustand liegt in `dmg_sequences`
- sequence-basierte Spaltenwerte werden in MySQL **nicht** ueber einen
  SQL-`DEFAULT`-Ausdruck realisiert, sondern ueber kanonische
  `BEFORE INSERT`-Trigger, die intern `dmg_nextval(...)` verwenden
- `helper_table` bildet die neutrale `SequenceNextVal`-Semantik nur
  **lossy** ab: ein Wert wird immer dann erzeugt, wenn `NEW.<column> IS NULL`
- MySQL kann in diesem Trigger-Pfad nicht unterscheiden, ob der Wert
  im `INSERT` ausgelassen oder explizit als `NULL` gesetzt wurde
- explizites `NULL` verbraucht daher im `helper_table`-Pfad ebenfalls
  einen Sequence-Wert; wer exakte PostgreSQL-`DEFAULT`-Semantik braucht,
  muss bei MySQL im Modus `action_required` bleiben
- `cache` wird gespeichert, aber fuer die erste Ausbaustufe nicht als
  echte Preallocation implementiert

Wenn `cache` nicht echt umgesetzt wird, braucht es eine Warning
(`W114` oder aehnlich), nicht stilles Ignorieren.

Support-Routinen sind fuer einen **betriebsfaehigen** `helper_table`-Pfad
verbindlich. Eine Datenbank kann zwar auch ohne diese Routinen noch
teilweise reverse-bar sein, gilt dann aber nur als rekonstruierbar,
nicht als produktiv vollstaendig.

Der Trigger-Pfad ist notwendig, weil MySQL Default-Expressions keine
Stored Functions enthalten duerfen. `dmg_nextval(...)` ist deshalb fuer
MySQL ein Laufzeitbaustein, aber kein direktes Column-Default.

### 3.5 Sequenzsemantik

Die Vollvariante braucht eine exakt dokumentierte Semantik fuer
auf- und absteigende Sequenzen.

Verbindlicher Zustand:

- `next_value` speichert immer den **naechsten auszugebenden Wert**
- `increment_by` darf positiv oder negativ sein
- `increment_by = 0` ist ungueltig und muss vor der Generierung als
  Modell-/Validierungsfehler abgefangen werden

Effektive Grenzen:

- `min_value`, wenn gesetzt
- sonst `Long.MIN_VALUE` fuer Sequenzen mit positivem oder negativem
  Schritt
- `max_value`, wenn gesetzt
- sonst `Long.MAX_VALUE`

Verhalten bei `increment_by > 0`:

- Rueckgabewert ist `next_value`
- neuer Zustand ist `next_value + increment_by`
- wenn der neue Zustand `max_value` ueberschreiten wuerde:
  - bei `cycle = false`: Fehlerpfad, kein stilles Wrapping
  - bei `cycle = true`: neuer Zustand springt auf `min_value`

Verhalten bei `increment_by < 0`:

- Rueckgabewert ist `next_value`
- neuer Zustand ist `next_value + increment_by`
- wenn der neue Zustand `min_value` unterschreiten wuerde:
  - bei `cycle = false`: Fehlerpfad, kein stilles Wrapping
  - bei `cycle = true`: neuer Zustand springt auf `max_value`

Der Fehlerpfad fuer erschöpfte Sequences muss vor Implementierung
festgelegt werden:

- SQLSTATE-/Routine-Fehler fuer `dmg_nextval(...)`
- keine stille Rueckgabe von `NULL`
- kein implizites `E056`, weil die Sequence bereits generiert wurde

---

## 4. Zielarchitektur

### 4.1 Neutralmodell

`SequenceDefinition` bleibt das fachliche Modellobjekt.

Zusatz fuer die Vollvariante:

- das Modell braucht einen expliziten Verwendungs-Link fuer
  sequence-basierte Spaltenwerte

Vorgeschlagene Erweiterung:

- `DefaultValue.SequenceNextVal(sequenceName: String)`

Damit kann das neutrale Schema nicht nur Sequence-Objekte definieren,
sondern auch ausdruecken, dass eine konkrete Spalte den naechsten Wert
dieser Sequence mit Default-Semantik verwendet.

Bestehende Felder von `SequenceDefinition` bleiben erhalten:

- `start`
- `increment`
- `minValue`
- `maxValue`
- `cycle`
- `cache`

Folgen fuer die Dialekte:

- PostgreSQL: Mapping auf natives `DEFAULT nextval(...)`
- MySQL `helper_table`: Mapping auf kanonischen `BEFORE INSERT`-Trigger,
  der bei `NEW.<column> IS NULL` intern `dmg_nextval(...)` aufruft;
  diese Abbildung ist gegenueber echtem `DEFAULT nextval(...)` lossy,
  weil explizites `NULL` nicht von ausgelassenen Werten getrennt werden
  kann
- MySQL `action_required`: sequence-basierte Default-Semantik bleibt
  manuell
- SQLite: weiterhin kein produktiver Sequence-Default-Pfad in diesem Plan

Validierung:

- `DefaultValue.SequenceNextVal` muss auf eine existierende
  `SequenceDefinition` verweisen
- Reverse muss sequence-basierte Spaltenwerte wieder auf diesen
  neutralen Default-Typ rekonstruieren

### 4.2 Generator-Optionen

`DdlGenerationOptions` bekommt eine MySQL-spezifische Sequence-Option:

- `mysqlNamedSequenceMode: MysqlNamedSequenceMode`

Neues Enum:

- `ACTION_REQUIRED`
- `HELPER_TABLE`

### 4.3 CLI-/Runner-Pfad

Betroffene Stellen:

- `SchemaGenerateRequest`
- `SchemaGenerateRunner`
- `SchemaGenerateCommand`
- ggf. Tool-Export-Runners, falls diese denselben DDL-Optionspfad nutzen

Die Option muss:

- per CLI gesetzt werden koennen
- im Report/JSON nachvollziehbar bleiben
- bei MySQL wirksam sein
- fuer PostgreSQL/SQLite ignoriert oder validiert abgelehnt werden

---

## 5. DDL-Vertrag fuer MySQL

### 5.1 Generierung

In `helper_table`-Mode erzeugt `generateSequences(...)` nicht mehr pro
Sequence einen Skip, sondern:

1. Support-Tabelle `dmg_sequences`
2. Seed-Statements fuer alle neutralen Sequences
3. Support-Routine(n) fuer `nextval`/`setval`

Zusätzlich muessen Spalten mit
`DefaultValue.SequenceNextVal(...)` im MySQL-DDL auf einen kanonischen
`BEFORE INSERT`-Trigger-Pfad abgebildet werden. Der Trigger setzt
`NEW.<column>` immer dann, wenn `NEW.<column> IS NULL`, und ruft intern
`dmg_nextval(...)` auf.

Praezisierung dieser Abbildung:

- technisch ist die Bedingung `NEW.<column> IS NULL`
- damit werden im MySQL-Pfad ausgelassene Werte und explizit gesetzte
  `NULL`-Werte gleich behandelt
- diese Abweichung ist bewusst dokumentiert und wird als lossy Mapping
  ueber `W115` ausgewiesen

Fuer Trigger-Reihenfolge gilt in Phase 1:

- MySQL 8 erlaubt mehrere Trigger mit gleichem Event/Timing; Phase 1
  nutzt das aus
- generierte Sequence-Support-Trigger werden vor nutzerdefinierten
  Triggern erzeugt
- damit laufen sie bei gleichem Event/Timing aufgrund der
  Erzeugungsreihenfolge zuerst
- eine spaetere Ausbaustufe kann bei Bedarf explizites
  `PRECEDES`/`FOLLOWS`-Management ergaenzen

Vorgeschlagene Reihenfolge:

1. Header
2. Custom Types
3. Sequence-Supportobjekte
4. User-Tabellen
5. generierte Sequence-Support-Trigger
6. Indizes
7. Views / Functions / Procedures
8. nutzerdefinierte Trigger

### 5.2 Rollback

Rollback muss die kanonischen Hilfsobjekte wieder entfernen:

- generierte Sequence-Support-Trigger droppen
- Support-Routine(n) droppen
- `dmg_sequences` droppen

Vorgeschlagene Rollback-Reihenfolge:

1. generierte Sequence-Support-Trigger
2. Support-Routine(n)
3. `dmg_sequences`

### 5.3 Warning-/Error-Semantik

- `E056` nur noch in `action_required`-Mode
- in `helper_table`-Mode stattdessen reguläre DDL
- reservierte Hilfsnamen im Nutzerschema erzeugen einen expliziten
  Konfliktcode; keine stille Umbenennung
- lossy Mapping bekommt eigene Warnings, z. B.:
  - `W114`: Sequence cache is not fully emulated in MySQL helper-table mode
  - `W115`: SequenceNextVal uses lossy MySQL trigger semantics; explicit NULL is treated like omitted value
  - `W116`: Sequence metadata reconstructed, but required support objects are missing

Diese Codes muessen vor Implementierung zentral dokumentiert werden.

---

## 6. Reverse-Engineering und Compare

### 6.1 MysqlSchemaReader

Der Reader muss die kanonischen Hilfsobjekte erkennen und in
`schema.sequences` zurueckfalten.

Dazu braucht er:

- Erkennung der Tabelle `dmg_sequences`
- Erkennung der kanonischen Spaltenform
- Pruefung der Marker `managed_by` und `format_version`
- Validierung der kanonischen Support-Routine(n) ueber
  Name + Marker-Kommentar + kanonische Signatur
- Rekonstruktion der `SequenceDefinition`-Felder aus den Zeilen
- Rekonstruktion von sequence-basierten Spaltenwerten ueber kanonische
  Sequence-Support-Trigger mit Name + Marker-Kommentar + kanonischer
  Body-Form

Die Hilfsobjekte duerfen danach nicht zugleich als normale Tabelle oder
Routine im neutralen Schema auftauchen.

Wichtig:

- Sequence-Reverse darf **nicht** von `includeFunctions` oder
  `includeProcedures` abhaengen
- die Erkennung der Sequence-Emulation muss daher primaer ueber die
  Support-Tabelle laufen
- die Rekonstruktion sequence-basierter Spaltenwerte darf **nicht** von
  `includeTriggers` abhaengen; der Reader muss kanonische
  Sequence-Support-Trigger dafuer intern inspizieren
- Support-Routinen werden fuer Reverse nur zusaetzlich geprueft, wenn
  sie verfuegbar sind; ihr Fehlen erzeugt eine Note, darf aber die
  Rekonstruktion der Sequence-Zeilen nicht komplett verhindern
- Routinen oder Trigger ohne gueltigen Marker-Kommentar werden **nicht**
  als d-migrate-Supportobjekte interpretiert, auch wenn ihr Name aehnlich
  aussieht
- fehlen Sequence-Support-Trigger, koennen `SequenceDefinition`-Zeilen
  zwar weiterhin rekonstruiert werden, aber die Zuordnung zu
  sequence-basierten Spaltenwerten gilt dann als unvollstaendig
- fehlen Routinen oder Sequence-Support-Trigger, muss Reverse den
  Zustand als **degradiert** markieren; die Sequence ist dann
  rekonstruierbar, aber nicht voll betriebsfaehig im Sinne des
  Produktvertrags

### 6.2 Compare

Sobald Reverse die Hilfsobjekte wieder auf `sequences` mappt, bleibt
`SchemaComparator` weitgehend neutral und braucht idealerweise keine
sonderdialektische Compare-Logik.

Trotzdem zu pruefen:

- gleiche neutrale Sequence -> kein Diff
- geaenderte Emulationszeile -> `sequencesChanged`
- fehlende Supportobjekte -> Reverse-Warning oder unvollstaendige Sequence

---

## 7. Arbeitspakete

### Phase A - Vertrag schaerfen

- Doku fuer MySQL-Sequence-Modi finalisieren
- kanonisches Hilfsobjekt-Layout festlegen
- Marker- und Namespace-Vertrag finalisieren
- Konfliktcode fuer reservierte Hilfsnamen festlegen
- Warning-Codes fuer lossy Mapping festziehen
- Fehlerverhalten fuer erschöpfte Sequences festziehen
- neutralen Default-Typ fuer sequence-basierte Spalten festlegen
- Trigger-Vertrag fuer MySQL-Sequence-Nutzung festlegen
- CLI-/Config-Vertrag dokumentieren

### Phase B - Generator und Optionen

- `DdlGenerationOptions` erweitern
- CLI-/Runner-Pfad erweitern
- Mapping fuer `DefaultValue.SequenceNextVal(...)` in PostgreSQL und
  MySQL implementieren
- `MysqlDdlGenerator.generateSequences(...)` auf `helper_table` ausbauen
- kanonische Sequence-Support-Trigger fuer MySQL generieren
- Rollback-Inversion fuer Supportobjekte pruefen

### Phase C - Tests und Golden Masters

- MySQL-Unit-Tests fuer beide Modi
- Golden Master fuer `full-featured.mysql.sql` im `helper_table`-Pfad
- Runner-Tests fuer Option-Parsing und Report-Inhalt

### Phase D - Reverse-Engineering

- `MysqlSchemaReader` um Sequence-Erkennung erweitern
- Sequence-Reverse explizit von `includeFunctions`/`includeProcedures`
  entkoppeln
- reverse von sequence-basierten Spaltenwerten ueber
  Sequence-Support-Trigger implementieren
- Reader-Integrationstests gegen echte MySQL-DB
- sicherstellen, dass Hilfsobjekte nicht doppelt im neutralen Modell landen

### Phase E - Compare und Stabilisierung

- Compare-Fixtures fuer emulierte Sequences
- Reverse->Generate->Reverse Round-Trip
- Dokumentation und Release-Hinweise

---

## 8. Teststrategie

### 8.1 Unit

- `MysqlDdlGeneratorTest`
  - `action_required` bleibt wie heute
  - `helper_table` erzeugt Support-DDL statt `E056`
  - sequence-basierte Spaltenwerte erzeugen kanonische
    `BEFORE INSERT`-Trigger statt eines SQL-`DEFAULT`
- `SchemaGenerateRunnerTest`
  - neuer Optionspfad
  - Fehler bei ungueltigem Wert
  - Konflikt mit reservierten Hilfsnamen wird sauber abgelehnt
- Format-/Golden-Master-Tests
  - neue MySQL-DDL-Fixtures

### 8.2 Integration

- MySQL-DDL gegen echte Datenbank ausfuehren
- `dmg_nextval('invoice_seq')` liefert erwartete Werte
- `INSERT` ohne expliziten Wert fuer eine sequence-basierte Spalte wird
  ueber den generierten `BEFORE INSERT`-Trigger korrekt befuellt
- negativer `increment` liefert fallende Werte
- `cycle`- und `max_value`-Pfad
- erschoepfte Sequence ohne `cycle` liefert klaren Fehler
- Reverse liest `dmg_sequences` wieder als `sequences`
- Reverse liest sequence-basierte Spaltenwerte ueber kanonische Trigger
  wieder in den neutralen Default-Typ zurueck
- Reverse funktioniert auch dann, wenn Routinen nicht ueber
  `includeFunctions`/`includeProcedures` angefordert wurden
- Reverse funktioniert fuer sequence-basierte Spaltenwerte auch dann,
  wenn Trigger nicht ueber `includeTriggers` angefordert wurden

### 8.3 Round-Trip

- neutral -> MySQL-DDL -> MySQL reverse -> neutral
- Compare zwischen Originalschema und reverse-Schema bleibt sequence-stabil

---

## 9. Risiken

### R1 - Emulation ist nur "DDL-schön", aber nicht praktisch nutzbar

Gegenmassnahme:

- Laufzeitvertrag vor dem Coding festziehen
- `nextval`-Pfad integrativ testen
- triggerbasierten Sequence-Insert-Pfad integrativ testen

### R2 - Reverse erkennt Hilfsobjekte nicht robust genug

Gegenmassnahme:

- streng kanonische Namen, Marker und Spaltenform
- keine "fuzzy" Reverse-Heuristik in Phase 1

### R3 - Hilfsobjekte kollidieren mit legitimen Nutzerobjekten

Gegenmassnahme:

- reservierter d-migrate-Namespace
- Marker-Spalten statt Namensheuristik allein
- Reverse erkennt Supportobjekte nur bei voller Signatur

### R4 - Vollvariante modelliert Sequence-Objekte, aber nicht deren Nutzung

Gegenmassnahme:

- neutralen Default-Typ fuer sequence-basierte Spalten verpflichtend
  einplanen
- Generator-, Reverse- und Compare-Tests immer inklusive
  Sequence-Nutzungsfaellen bauen

### R5 - Compare zeigt Hilfsobjekt-Rauschen statt Sequences

Gegenmassnahme:

- Reverse zuerst sauber bauen
- Compare auf Neutralmodell-Ebene testen

### R6 - Semantikabweichung gegen native PostgreSQL-Sequences

Gegenmassnahme:

- Unterschiede offen dokumentieren
- Warncodes fuer lossy Aspekte statt stiller Abweichung

---

## 10. Aufwandseinschaetzung

### Mittel

- nur Generator + CLI + Golden Masters
- keine Reverse-/Compare-Unterstuetzung

### Gross

- Generator + CLI + Golden Masters
- Reverse-Erkennung
- Compare-Stabilisierung
- Integrationstests fuer den `nextval`-Vertrag

Die **vollstaendige Produktvariante** dieses Dokuments ist deshalb klar
als **grosses** Arbeitspaket einzustufen.

---

## 11. Empfehlung

Vor dem ersten Code:

1. Dokuvertrag fuer `helper_table` finalisieren
2. kanonisches MySQL-Objektlayout festlegen
3. entscheiden, ob `cache` nur gespeichert oder echt emuliert wird
4. entscheiden, ob `helper_table` in 1. Phase opt-in bleibt

Erst danach sollte die eigentliche Implementierung beginnen. Ohne diese
Produktentscheidungen droht eine halbe Emulation, die zwar DDL erzeugt,
aber weder sauber reverse-bar noch stabil vergleichbar ist.
