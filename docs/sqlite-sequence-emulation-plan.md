# Implementierungsplan: Vollständige SQLite-Sequence-Emulation

> Status: Draft (2026-04-20)
>
> Zweck: Produktplan fuer eine **vollstaendige** SQLite-Variante von
> benannten Sequences im DDL-Pfad, inklusive DDL-Generierung,
> Reverse-Engineering, Compare-/Diff-Kompatibilitaet und klarem
> Betriebsvertrag.
>
> Referenzen:
> - `docs/ddl-generation-rules.md` §7
> - `docs/neutral-model-spec.md`
> - `docs/mysql-sequence-emulation-plan.md`
> - `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/SequenceDefinition.kt`
> - `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt`
> - `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt`
> - SQLite Dokumentation: "CREATE TRIGGER", "Autoincrement", "lang_createtrigger"

---

## 1. Ziel

SQLite soll benannte Sequences nicht mehr nur mit `E056` ueberspringen,
sondern als echte, wiedererkennbare Produktfunktion unterstuetzen.

Die Vollvariante liefert:

- generierbare SQLite-DDL fuer neutrale `sequences`
- generierbare und reverse-bare Nutzung von benannten Sequences fuer
  sequence-basierte Spaltenwerte
- einen klaren Laufzeitvertrag, wie Anwendungen den naechsten Wert abrufen
- Reverse-Engineering der emulierten Strukturen zurueck auf
  `SequenceDefinition`
- Compare-/Diff-Verhalten auf Neutralmodell-Ebene statt auf den
  SQLite-Hilfsobjekten
- definierte Report-/Warning-Semantik bei lossy Mapping

Die Vollvariante liefert bewusst nicht:

- stille Migration beliebiger, handgeschriebener SQLite-Sequence-Loesungen
- Multi-Process-Serialisierung jenseits des dokumentierten SQLite-Vertrags
- Ersatz fuer `INTEGER PRIMARY KEY AUTOINCREMENT` — dieses Feature bleibt
  unabhaengig und wird weiterhin direkt ueber `NeutralType.Identifier`
  abgebildet

---

## 2. Ausgangslage

Heute gilt:

- PostgreSQL erzeugt native `CREATE SEQUENCE`
- MySQL erzeugt `action_required` (`E056`), ein `helper_table`-Modus
  ist geplant (siehe `docs/mysql-sequence-emulation-plan.md`)
- SQLite erzeugt `action_required` (`E056`)
- das neutrale Modell hat `SequenceDefinition`, aber keinen expliziten
  Verwendungs-Link von Spalten auf eine Sequence-Nutzung
  (geplant als `DefaultValue.SequenceNextVal` im MySQL-Plan)
- der SQLite-Reader liest keine Sequences zurueck
- SQLite hat keine Stored Functions oder Stored Procedures
- SQLite-Trigger koennen jedoch `BEGIN...END`-Bloecke mit mehreren
  SQL-Statements ausfuehren, einschliesslich `UPDATE` auf andere Tabellen

Konsequenz: Eine SQLite-Sequence-Emulation muss **ohne** Stored
Functions auskommen und die gesamte Logik in Trigger-Bodys inline
realisieren. Das ist der zentrale Unterschied zur MySQL-Variante.

---

## 3. Produktentscheidung

### 3.1 Betriebsmodi

SQLite bekommt einen expliziten Modus:

- `action_required`
  - heutiges Verhalten
  - `E056`, keine Emulation
- `helper_table`
  - produktive Emulation ueber kanonische SQLite-Hilfsobjekte

Vorgeschlagener Optionspfad:

```yaml
ddl:
  sqlite:
    named_sequences: action_required   # action_required | helper_table
```

Default fuer die Einfuehrungsphase:

- CLI/Runner-Default bleibt vorerst `action_required`
- `helper_table` ist opt-in
- ein spaeterer Milestone kann den Default kippen, wenn Reverse und
  Compare stabil sind
- beim Wechsel von `action_required` auf `helper_table` werden
  bestehende, manuell implementierte Sequence-Loesungen in der
  Zieldatenbank **nicht** automatisch uebernommen oder migriert;
  der Nutzer ist verantwortlich, manuelle Hilfsobjekte vorher zu
  entfernen oder den Startwert in `dmg_sequences` nach Generierung
  manuell abzugleichen

### 3.2 Kanonische Emulationsobjekte

Die Emulation muss **kanonisch** sein, damit Reverse und Compare sie
zuverlaessig erkennen koennen.

Vorgeschlagene Objekte:

- zentrale Support-Tabelle `dmg_sequences`
- pro sequence-basierter Spalte ein kanonisches **Trigger-Paar**:
  ein `BEFORE INSERT`-Trigger (`_bi`), der den naechsten Wert in
  `dmg_sequences` reserviert, und ein `AFTER INSERT`-Trigger (`_ai`),
  der den reservierten Wert per `UPDATE ... WHERE ROWID = NEW.ROWID`
  in die eingefuegte Zeile schreibt

Im Gegensatz zur MySQL-Variante gibt es **keine** separaten Stored
Functions (`dmg_nextval`, `dmg_setval`), da SQLite keine benutzerdefinierten
SQL-Routinen unterstuetzt. Die Nextval-Logik lebt vollstaendig in den
generierten Triggern.

Fuer administrative Zwecke (Sequence zuruecksetzen, aktuellen Wert
abfragen) wird stattdessen ein dokumentierter SQL-Befehl bereitgestellt:

```sql
-- Naechsten Wert abfragen:
SELECT "next_value" FROM "dmg_sequences" WHERE "name" = 'invoice_seq';

-- Wert manuell setzen (dmg_setval-Aequivalent):
UPDATE "dmg_sequences" SET "next_value" = 42 WHERE "name" = 'invoice_seq';
```

Transaktionsvertrag fuer manuelle Zugriffe:

- manuelle `SELECT`/`UPDATE`-Operationen auf `dmg_sequences` sind
  **kein stabiles API** im Sinne einer Stored Function, sondern eine
  dokumentierte **Advanced Operation**
- ein manuelles `UPDATE` auf `next_value` waehrend einer laufenden
  Transaktion, die auch Trigger-basierte INSERTs ausfuehrt, kann zu
  inkonsistenten Sequence-Werten fuehren, da der Trigger denselben
  `next_value` liest und schreibt
- manuelle Updates sollten deshalb nur in dedizierten Transaktionen
  ohne gleichzeitige INSERTs auf sequence-basierte Tabellen erfolgen
- SQLite's Single-Writer-Modell verhindert zwar parallele
  Schreibzugriffe, aber innerhalb **einer** Transaktion koennen
  Trigger-Writes und manuelle Writes interferieren
- diese Einschraenkung wird in der Nutzerdokumentation als
  Vorbedingung dokumentiert, nicht als Warning-Code

Vorgeschlagenes Tabellenschema:

```sql
CREATE TABLE "dmg_sequences" (
    "managed_by" TEXT NOT NULL,
    "format_version" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "next_value" INTEGER NOT NULL,
    "last_returned_value" INTEGER NULL,
    "increment_by" INTEGER NOT NULL,
    "min_value" INTEGER NULL,
    "max_value" INTEGER NULL,
    "cycle_enabled" INTEGER NOT NULL,
    "cache_size" INTEGER NULL,
    PRIMARY KEY ("name")
);
```

`last_returned_value` speichert den zuletzt von einem Trigger
zurueckgegebenen Wert. Die Spalte ist `NULL` nach der initialen
Seed-Erzeugung und wird erst beim ersten Trigger-Aufruf gesetzt.
Sie ist **nicht** Teil des neutralen Modells (`SequenceDefinition`),
sondern ein Implementierungsdetail der SQLite-Emulation, das den
AFTER INSERT-Trigger vom `next_value`-Zustand entkoppelt — insbesondere
bei Zyklus-Resets, bei denen `next_value - increment_by` den falschen
Wert liefern wuerde.

Jede neutrale Sequence belegt genau eine Zeile in `dmg_sequences`.

`cache_size = NULL` bedeutet "kein Caching konfiguriert" (nicht
"Default-Caching des Dialekts"). Caching wird in SQLite nicht umgesetzt
(siehe §3.4), da SQLite keinen Multi-Connection-Cache nutzen kann.

### 3.3 Namespace- und Kollisionsvertrag

Konsistent mit dem MySQL-Plan gilt:

- der SQLite-Support nutzt denselben reservierten, d-migrate-spezifischen
  Namespace-Prefix wie MySQL
- Supportobjekte tragen zusaetzlich einen inhaltlichen Marker
- Reverse erkennt Hilfsobjekte nur, wenn **Name, Form und Marker**
  zusammenpassen

Vorgeschlagener Prefix:

- Tabelle: `dmg_sequences`
- Trigger-Paar: deterministisch begrenztes Schema
  - BEFORE INSERT: `dmg_seq_<table16>_<column16>_<hash10>_bi`
  - AFTER INSERT:  `dmg_seq_<table16>_<column16>_<hash10>_ai`

Vorgeschlagene Marker:

- `managed_by = 'd-migrate'`
- `format_version = 'sqlite-sequence-v1'`
- Sequence-Support-Trigger starten mit einem kanonischen Marker-Kommentar:
  - BEFORE INSERT (`_bi`):
    `/* d-migrate:sqlite-sequence-v1 object=sequence-trigger sequence=<name> table=<table> column=<column> */`
  - AFTER INSERT (`_ai`):
    `/* d-migrate:sqlite-sequence-v1 object=sequence-trigger-post sequence=<name> table=<table> column=<column> */`

Trigger-Namensvertrag:

- SQLite hat kein hartes Identifier-Laengenlimit, aber der Plan uebernimmt
  bewusst dasselbe Schema wie MySQL fuer Konsistenz und Vorhersagbarkeit
- fuer `table16`, `column16` und `hash10` wird die neutrale
  Kanonform des Identifiers verwendet (identisch zu MySQL-Plan §3.3)
- Schema:
  - Prefix `dmg_seq_`
  - `table16`: erste 16 Zeichen des kanonischen neutralen
    Tabellennamens
  - `column16`: erste 16 Zeichen des kanonischen neutralen
    Spaltennamens
  - `hash10`: erste 10 Hex-Zeichen (lowercase) eines SHA-256-Hashes
    ueber den UTF-8-kodierten String
    `<canonical-table>\u0000<canonical-column>\u0000<sequence>`
  - Suffix `_bi` (BEFORE INSERT) bzw. `_ai` (AFTER INSERT)
- beide Trigger eines Paares verwenden denselben Hash; sie
  unterscheiden sich nur im Suffix
- das Schema bleibt immer <= 55 Zeichen (laengster Fall mit `_bi`;
  `_ai` ist gleich lang)
- Reverse identifiziert Sequence-Support-Trigger **nicht** nur ueber den
  Namen, sondern ueber Name + Marker-Kommentar + kanonische Body-Form
- Reverse muss **beide** Trigger eines Paares (`_bi` + `_ai`)
  erkennen; fehlt einer, ist die Emulation degradiert (`W116`)
- Kollisionen auf `hash10` gelten als praktisch unwahrscheinlich; tritt
  dennoch eine Kollision auf, ist das ein expliziter Generate-Fehler und
  kein stilles Umbenennen

Konfliktpfad bei Vorwaertsgenerierung:

- wenn das neutrale Schema bereits ein Objekt mit reserviertem
  Hilfsnamen enthaelt (`dmg_sequences`, `dmg_seq_*` nach dem kanonischen
  Namensschema), darf `helper_table` **nicht** still generieren
- stattdessen erzeugt der Generator einen expliziten Fehler- oder
  `action_required`-Pfad mit eigenem Code
- keine implizite automatische Umbenennung in Phase 1

### 3.4 Laufzeitvertrag

Der Produktvertrag muss mehr liefern als "es gibt irgendwo eine
Hilfstabelle".

Deshalb wird verbindlich dokumentiert:

- naechster Wert wird fuer jede sequence-basierte Spalte automatisch
  ueber den generierten `BEFORE INSERT`-Trigger vergeben
- der aktuelle Zustand liegt in `dmg_sequences`
- manueller Abruf und Setzen sind ueber direkte `SELECT`/`UPDATE` auf
  `dmg_sequences` moeglich (keine Stored Function noetig)
- sequence-basierte Spaltenwerte werden ueber kanonische
  `BEFORE INSERT`-Trigger realisiert, die intern ein atomares
  `UPDATE`/`SELECT` auf `dmg_sequences` ausfuehren
- `helper_table` bildet die neutrale `SequenceNextVal`-Semantik nur
  **lossy** ab: ein Wert wird immer dann erzeugt, wenn `NEW.<column> IS NULL`
- SQLite kann in diesem Trigger-Pfad nicht unterscheiden, ob der Wert
  im `INSERT` ausgelassen oder explizit als `NULL` gesetzt wurde
- explizites `NULL` verbraucht daher im `helper_table`-Pfad ebenfalls
  einen Sequence-Wert; wer exakte PostgreSQL-`DEFAULT`-Semantik braucht,
  muss bei SQLite im Modus `action_required` bleiben
- `cache` wird gespeichert, aber **nicht** als echte Preallocation
  implementiert; SQLite ist ein Embedded-DBMS mit Single-Writer und
  profitiert nicht von Connection-lokalem Caching

Illustrativer Ablauf (Pseudocode):

Der folgende Block beschreibt die **logische Abfolge** der
Trigger-Operationen. Er ist **kein ausfuehrbares SQL** — insbesondere
existiert keine SQLite-Funktion zum direkten Setzen von `NEW.<column>`
in einem `BEFORE INSERT`-Trigger. Die tatsaechliche Zuweisungsstrategie
ist eine offene Entscheidung fuer Phase A (siehe unten).

```
-- Pseudocode: Logischer Ablauf des Trigger-Paares
--
-- BEFORE INSERT (_bi):
--
-- Schritt 1: Overflow-sichere Grenzpruefung VOR dem Inkrement
--   (prueft ob next_value + increment_by die Grenze ueberschreiten
--    WUERDE, ohne die Addition auszufuehren; formuliert als
--    next_value > max_value - increment_by)
--   IF increment_by > 0
--      AND next_value > COALESCE(max_value, MAX_INT) - increment_by:
--     IF cycle_enabled = 0: RAISE(ABORT, 'exhausted')
--   IF increment_by < 0
--      AND next_value < COALESCE(min_value, MIN_INT) - increment_by:
--     IF cycle_enabled = 0: RAISE(ABORT, 'exhausted')
--
-- Schritt 2: Rueckgabewert sichern + inkrementieren (atomar)
--   last_returned_value := next_value
--   IF Grenze ueberschritten AND cycle_enabled = 1:
--     next_value := cycle-Startwert (min_value bzw. max_value)
--   ELSE:
--     next_value := next_value + increment_by
--   IF rows_affected = 0: RAISE(ABORT, 'sequence row not found')
--
-- AFTER INSERT (_ai):
--
-- Schritt 3: last_returned_value in die eingefuegte Zeile schreiben
--   UPDATE table SET column = last_returned_value WHERE ROWID = NEW.ROWID
```

Hinweis zur Triggerlogik:

- Die Grenzpruefung laeuft **vor** dem Inkrement, formuliert als
  `next_value > max_value - increment_by` statt
  `next_value + increment_by > max_value`, um Integer-Overflow bei
  Werten nahe `Long.MIN_VALUE`/`Long.MAX_VALUE` zu vermeiden
- `last_returned_value` wird im selben UPDATE wie das Inkrement
  gesichert; der AFTER INSERT-Trigger liest daraus statt zu rechnen
- Zyklus-Reset und normales Inkrement sind im CASE-Ausdruck
  desselben UPDATE zusammengefasst — kein separater Zyklus-UPDATE

**Zentrale Limitation: NEW-Zuweisung in SQLite-Triggern**

SQLite-Trigger koennen `NEW.<column>` nicht direkt per `SET` zuweisen
wie MySQL (`SET NEW.col = expr`). Es gibt in SQLite **keine**
eingebaute Funktion, die das ermoeglicht.

**Entscheidung: Zwei-Trigger-Strategie als kanonischer Produktpfad**

Der `helper_table`-Modus nutzt verbindlich die **Zwei-Trigger-Strategie**:

1. Ein `BEFORE INSERT`-Trigger (`_bi`) reserviert den naechsten Wert
   in `dmg_sequences` (atomares `UPDATE`) und prueft Grenzen/Zyklus
2. Ein `AFTER INSERT`-Trigger (`_ai`) schreibt den reservierten Wert
   per `UPDATE ... WHERE ROWID = NEW.ROWID` in die eingefuegte Zeile

Vorteile:

- transparent, kein Applikationscode noetig
- kanonische Trigger ermoeglichen vollstaendiges Reverse-Engineering
  zurueck auf `DefaultValue.SequenceNextVal`
- Compare/Diff bleibt auf Neutralmodell-Ebene stabil

Einschraenkung:

- erfordert `ROWID`-Zugriff im `AFTER INSERT`-Trigger
- **`WITHOUT ROWID`-Tabellen** koennen diesen Pfad nicht nutzen
  (siehe §3.7)

Alternative Ansaetze, die evaluiert und verworfen wurden:

- **App-Level (kein automatischer Trigger)**: Die Anwendung holt den
  Wert selbst aus `dmg_sequences`. Verworfen fuer `helper_table`, weil
  ohne kanonische Trigger kein Reverse-Mapping auf
  `DefaultValue.SequenceNextVal` moeglich ist — das bricht die
  Kernziele des Plans (§1). Nutzer, die App-Level-Sequencing
  bevorzugen, koennen `action_required` verwenden und die
  `dmg_sequences`-Tabelle manuell anlegen; ein dokumentiertes
  SQL-Pattern steht in §3.2 (manuelle Zugriffe).
- **RETURNING-basiert**: `UPDATE ... RETURNING` (seit SQLite 3.35.0)
  kann den Rueckgabewert nicht direkt in `NEW.<column>` schreiben;
  RETURNING ist fuer die aufrufende Applikation, nicht fuer
  Trigger-interne Logik.

Kanonisches Trigger-Paar (ausfuehrbares SQL):

```sql
-- BEFORE INSERT: sichert Rueckgabewert, inkrementiert, prueft Grenzen
CREATE TRIGGER "dmg_seq_orders_order_num_a1b2c3d4e5_bi"
BEFORE INSERT ON "orders"
FOR EACH ROW
WHEN NEW."order_number" IS NULL
BEGIN
    /* d-migrate:sqlite-sequence-v1 object=sequence-trigger sequence=order_number_seq table=orders column=order_number */
    -- Schritt 1: Overflow-sichere Grenzpruefung VOR dem Inkrement.
    -- Prueft ob next_value + increment_by die Grenze ueberschreiten
    -- WUERDE, ohne die Addition tatsaechlich auszufuehren.
    -- Formulierung als next_value > max_value - increment_by (statt
    -- next_value + increment_by > max_value), um Integer-Overflow bei
    -- Werten nahe Long.MIN_VALUE/MAX_VALUE zu vermeiden.
    -- Bei cycle_enabled = 0: RAISE(ABORT).
    SELECT RAISE(ABORT, 'dmg_sequences: sequence order_number_seq exhausted')
        WHERE (SELECT "cycle_enabled" FROM "dmg_sequences"
               WHERE "name" = 'order_number_seq') = 0
        AND (
            ((SELECT "increment_by" FROM "dmg_sequences"
              WHERE "name" = 'order_number_seq') > 0
             AND (SELECT "next_value" FROM "dmg_sequences"
                  WHERE "name" = 'order_number_seq')
                 > COALESCE((SELECT "max_value" FROM "dmg_sequences"
                             WHERE "name" = 'order_number_seq'),
                            9223372036854775807)
                   - (SELECT "increment_by" FROM "dmg_sequences"
                      WHERE "name" = 'order_number_seq'))
            OR
            ((SELECT "increment_by" FROM "dmg_sequences"
              WHERE "name" = 'order_number_seq') < 0
             AND (SELECT "next_value" FROM "dmg_sequences"
                  WHERE "name" = 'order_number_seq')
                 < COALESCE((SELECT "min_value" FROM "dmg_sequences"
                             WHERE "name" = 'order_number_seq'),
                            -9223372036854775808)
                   - (SELECT "increment_by" FROM "dmg_sequences"
                      WHERE "name" = 'order_number_seq'))
        );
    -- Schritt 2: Rueckgabewert sichern + next_value inkrementieren (atomar).
    -- Bei cycle_enabled = 1 und Grenzueberschreitung: next_value wird
    -- direkt auf den Zyklusstartwert gesetzt statt normal inkrementiert.
    -- Die CASE-Bedingungen verwenden dieselbe Subtraktionsformel wie
    -- der Exhaustion-Check (next_value > max_value - increment_by),
    -- um Integer-Overflow konsistent zu vermeiden.
    UPDATE "dmg_sequences"
        SET "last_returned_value" = "next_value",
            "next_value" = CASE
                WHEN "increment_by" > 0
                     AND "next_value"
                         > COALESCE("max_value", 9223372036854775807)
                           - "increment_by"
                     AND "cycle_enabled" = 1
                THEN COALESCE("min_value", 1)
                WHEN "increment_by" < 0
                     AND "next_value"
                         < COALESCE("min_value", -9223372036854775808)
                           - "increment_by"
                     AND "cycle_enabled" = 1
                THEN COALESCE("max_value", 9223372036854775807)
                ELSE "next_value" + "increment_by"
            END
        WHERE "name" = 'order_number_seq';
    -- Guard: Sequence-Zeile muss existieren (changes() = 0 heisst kein UPDATE).
    -- Hinweis: changes() zaehlt gematchte Zeilen, nicht geaenderte Werte;
    -- selbst bei increment_by mit Netto-Null-Effekt (theoretisch durch
    -- Validierung ausgeschlossen, §3.5) wuerde changes() = 1 zurueckgeben,
    -- solange die WHERE-Klausel matcht.
    SELECT RAISE(ABORT, 'dmg_sequences: sequence row order_number_seq not found')
        WHERE changes() = 0;
END;

-- AFTER INSERT: schreibt den gesicherten Rueckgabewert in die Zeile
CREATE TRIGGER "dmg_seq_orders_order_num_a1b2c3d4e5_ai"
AFTER INSERT ON "orders"
FOR EACH ROW
WHEN NEW."order_number" IS NULL
BEGIN
    /* d-migrate:sqlite-sequence-v1 object=sequence-trigger-post sequence=order_number_seq table=orders column=order_number */
    UPDATE "orders"
        SET "order_number" = (
            SELECT "last_returned_value"
            FROM "dmg_sequences"
            WHERE "name" = 'order_number_seq'
        )
        WHERE ROWID = NEW.ROWID;
END;
```

Hinweis: Die Grenzpruefung (Exhaustion-Check) laeuft **vor** dem
Inkrement und ist overflow-sicher formuliert (`next_value > max_value
- increment_by`). Der Zyklus-Reset und das normale Inkrement sind
im CASE-Ausdruck desselben UPDATE zusammengefasst. Der AFTER
INSERT-Trigger liest `last_returned_value` statt zu rechnen —
dadurch ist der Rueckgabewert vom `next_value`-Zustand entkoppelt.

**NOT NULL-Kompatibilitaet bei Zwei-Trigger-Strategie**

Die Zwei-Trigger-Strategie fuegt die Zeile zuerst mit `NULL` in der
sequence-basierten Spalte ein (der INSERT durchlaeuft mit dem
WHEN-gefilterten NULL-Wert), und der AFTER INSERT-Trigger schreibt
danach den tatsaechlichen Wert per UPDATE. Wenn die Spalte ein
`NOT NULL`-Constraint traegt, scheitert der INSERT **vor** dem
AFTER INSERT-Trigger an der Constraint-Pruefung.

**Entscheidung:** Der SQLite-Generator **unterdrueckt** `NOT NULL`
auf sequence-basierten Spalten im generierten DDL.

Verhalten im Detail:

- wenn das neutrale Modell `required: true` und
  `DefaultValue.SequenceNextVal(...)` auf derselben Spalte hat,
  emittiert der SQLite-Generator die Spalte **ohne** `NOT NULL`
- der AFTER INSERT-Trigger garantiert, dass nach jedem INSERT ein
  Wert gesetzt wird; die Spalte ist de facto nie dauerhaft NULL
- das unterdrueckte `NOT NULL` wird als lossy Mapping ueber `W119`
  gemeldet
- bei Reverse erkennt der Reader, dass die Spalte im DDL kein
  `NOT NULL` hat, aber ueber einen Sequence-Trigger versorgt wird;
  er rekonstruiert `required: true` im neutralen Modell, weil die
  Trigger-Semantik das garantiert
- fuer den Compare-Pfad ist das transparent: das neutrale Modell
  hat `required: true`, unabhaengig von der DDL-Darstellung

Ausnahme: Spalten mit `NeutralType.Identifier` (AUTOINCREMENT) sind
nicht betroffen — diese nutzen SQLite's nativen Mechanismus und
brauchen keinen Trigger.

### 3.7 `WITHOUT ROWID`-Tabellen

**Entscheidung:** `WITHOUT ROWID`-Tabellen mit sequence-basierten
Spalten werden im `helper_table`-Modus **nicht** unterstuetzt.

Verhalten bei Generierung:

- wenn eine Tabelle im neutralen Modell als `WITHOUT ROWID` markiert
  ist **und** eine Spalte `DefaultValue.SequenceNextVal(...)` traegt,
  erzeugt der Generator fuer diese Spalte einen `action_required`-Pfad
  mit Code `E057`
- die `SequenceDefinition` selbst wird weiterhin in `dmg_sequences`
  erzeugt (sie kann von anderen Tabellen genutzt werden)
- fuer die betroffene Spalte wird deterministisch **nichts** erzeugt:
  kein Trigger-Paar (`_bi`/`_ai`), kein `DEFAULT`-Ausdruck, keine
  NOT NULL-Unterdrueckung — die Spalte wird exakt so emittiert, wie
  sie im neutralen Modell definiert ist (inklusive `NOT NULL` falls
  `required: true`)
- der Rest der Tabelle (andere Spalten, Constraints, Indizes) wird
  normal generiert
- `E057` wird als `TransformationNote` an die betroffene Spalte
  gehaengt, nicht an die Tabelle; die Tabelle selbst wird nicht
  blockiert

Verhalten bei Reverse:

- `WITHOUT ROWID`-Tabellen haben keine implizite `ROWID`; der Reader
  erwartet dort keine Sequence-Support-Trigger
- wenn dennoch ein Trigger mit passendem Marker auf einer
  `WITHOUT ROWID`-Tabelle gefunden wird, wird er als degradiert
  markiert (Reverse-Warning)

Warning-Code:

- `E057`: Sequence-backed column on WITHOUT ROWID table cannot use
  automatic trigger assignment; use action_required mode or
  application-level sequencing

Begruendung: Ein alternativer AFTER INSERT-Trigger, der ueber den PK
statt ROWID adressiert, waere technisch moeglich, fuehrt aber zu
einem zweiten Trigger-Pfad mit eigener Body-Form, eigener
Reverse-Erkennung und eigenen Edge Cases (zusammengesetzte PKs,
PK-Aenderungen). Fuer Phase 1 ist die klare Ablehnung das
stabilere Design. Eine spaetere Ausbaustufe kann den PK-basierten
Pfad ergaenzen, wenn der Bedarf belegt ist.

### 3.5 Sequenzsemantik

Die Semantik ist identisch zum MySQL-Plan (§3.5) und wird hier nicht
wiederholt. Die verbindlichen Regeln fuer `next_value`, `increment_by`,
`min_value`, `max_value` und `cycle` gelten dialektuebergreifend.

Zusaetzliche SQLite-spezifische Punkte:

- `increment_by = 0` ist ungueltig und wird als Validierungsfehler im
  neutralen Modell abgefangen (identisch zu MySQL)
- SQLite speichert alle Ganzzahlen als `INTEGER` (bis zu 8 Bytes,
  -9223372036854775808 bis 9223372036854775807); die effektiven
  Grenzwerte entsprechen `Long.MIN_VALUE`/`Long.MAX_VALUE` in Kotlin
- der Fehlerpfad fuer erschoepfte Sequences nutzt `RAISE(ABORT, ...)`
  in SQLite, nicht einen SQL-Routine-Fehler wie in MySQL

### 3.6 Concurrency-Vertrag

SQLite's Concurrency-Modell unterscheidet sich grundlegend von MySQL:

- SQLite ist ein **Single-Writer**-DBMS: zu jedem Zeitpunkt kann
  hoechstens eine Schreibtransaktion aktiv sein
- mit WAL-Modus sind parallele Leser moeglich, aber es gibt immer
  nur einen Writer
- dadurch gibt es **keine** Row-Level-Lock-Contention auf
  `dmg_sequences` wie bei MySQL
- die Sequence-Wertvergabe ist implizit serialisiert durch die
  globale Schreibsperre
- bei Rollback der Transaktion wird auch der Sequence-Inkrement
  zurueckgerollt (identisch zu MySQL `helper_table`)
- Sequence-Werte werden **nicht** transaktionsunabhaengig vergeben
  wie bei nativen PostgreSQL-Sequences
- dieser Unterschied wird als `W117` dokumentiert (identischer Code
  wie bei MySQL, da die Ursache — Transaktionsbindung — dieselbe ist)

---

## 4. Zielarchitektur

### 4.1 Neutralmodell

Identisch zum MySQL-Plan (§4.1):

- `SequenceDefinition` bleibt das fachliche Modellobjekt
- `DefaultValue.SequenceNextVal(sequenceName: String)` als neuer
  sealed-class-Subtyp (Voraussetzung: Audit aller `when(defaultValue)`-
  Stellen, bereits im MySQL-Plan eingeplant)
- SQLite nutzt denselben neutralen Default-Typ

Folgen fuer SQLite:

- `helper_table`: Mapping auf kanonisches Trigger-Paar (`_bi` + `_ai`),
  das bei `NEW.<column> IS NULL` intern den naechsten Wert aus
  `dmg_sequences` reserviert und per ROWID in die Zeile schreibt;
  diese Abbildung ist lossy (identisch zu MySQL);
  `WITHOUT ROWID`-Tabellen erhalten `E057` statt Trigger (§3.7)
- `action_required`: sequence-basierte Default-Semantik bleibt manuell

### 4.2 Generator-Optionen

`DdlGenerationOptions` bekommt eine SQLite-spezifische Sequence-Option:

- `sqliteNamedSequenceMode: SqliteNamedSequenceMode`

Neues Enum:

- `ACTION_REQUIRED`
- `HELPER_TABLE`

Da MySQL bereits `MysqlNamedSequenceMode` hat, wird die SQLite-Variante
strukturell parallel angelegt. Beide Enums haben identische Werte, sind
aber getrennte Typen, um die dialektspezifische Validierung sauber zu
halten.

### 4.3 CLI-/Runner-Pfad

Betroffene Stellen:

- `SchemaGenerateRequest`
- `SchemaGenerateRunner`
- `SchemaGenerateCommand`
- ggf. Tool-Export-Runners

Neuer CLI-Parameter:

```
--sqlite-named-sequences <action_required|helper_table>
```

Die Option muss:

- per CLI gesetzt werden koennen
- im Report/JSON nachvollziehbar bleiben
- bei SQLite wirksam sein
- fuer PostgreSQL/MySQL validiert abgelehnt werden

### 4.4 Abhaengigkeit zum MySQL-Plan

Der SQLite-Plan teilt folgende Bausteine mit dem MySQL-Plan:

- `DefaultValue.SequenceNextVal` (Neutralmodell-Erweiterung)
- `dmg_sequences`-Tabellenschema (gleiche Spaltenstruktur, nur
  SQLite-Typen statt MySQL-Typen)
- Trigger-Namensschema und Hash-Algorithmus
- Marker-Konvention (`managed_by`, `format_version`)
- Warning-Codes `W114`, `W115`, `W117`

Phase B dieses Plans kann daher erst beginnen, wenn die
`DefaultValue.SequenceNextVal`-Erweiterung aus dem MySQL-Plan (Phase B)
implementiert ist — oder beide Plaene werden koordiniert in derselben
Phase umgesetzt.

---

## 5. DDL-Vertrag fuer SQLite

### 5.1 Generierung

In `helper_table`-Mode erzeugt `generateSequences(...)` nicht mehr pro
Sequence einen Skip, sondern:

1. Support-Tabelle `dmg_sequences` (mit SQLite-Typen: `TEXT`, `INTEGER`)
2. Seed-Statements (ein `INSERT INTO "dmg_sequences"` pro Sequence)

Zusaetzlich muessen Spalten mit `DefaultValue.SequenceNextVal(...)` im
SQLite-DDL auf ein kanonisches Trigger-Paar (`_bi` + `_ai`) abgebildet
werden (ausser bei `WITHOUT ROWID`-Tabellen, die `E057` erhalten).

Praezisierung:

- technisch ist die Trigger-Bedingung `WHEN NEW.<column> IS NULL`
- damit werden ausgelassene Werte und explizit gesetzte `NULL`-Werte
  gleich behandelt
- diese Abweichung ist bewusst dokumentiert und wird als lossy Mapping
  ueber `W115` ausgewiesen

Wichtig: Interaktion mit DEFAULT-Constraints:

- in SQLite fuellt der Engine die `DEFAULT`-Werte **bevor** ein
  `BEFORE INSERT`-Trigger feuert
- wenn eine Spalte sowohl `DEFAULT 42` als auch einen Sequence-Trigger
  mit `WHEN NEW.<column> IS NULL` hat, greift der Trigger **nicht** bei
  ausgelassenen Werten, weil `NEW.<column>` bereits den Default `42`
  enthaelt
- deshalb **duerfen** sequence-basierte Spalten im generierten DDL
  **keinen** `DEFAULT`-Constraint haben (weder explizit noch ueber
  `DefaultValue`); der Generator muss das sicherstellen
- konkret: wenn eine Spalte `DefaultValue.SequenceNextVal(...)` traegt,
  darf der SQLite-Generator keinen `DEFAULT`-Ausdruck an die Spalte
  emittieren; der Trigger-Pfad ersetzt den Default vollstaendig
- eine Spalte mit sowohl `DEFAULT` als auch `SequenceNextVal` im
  neutralen Modell ist ein Validierungsfehler, der vor der Generierung
  abgefangen wird

Normative Beispiele fuer Trigger-Semantik im `helper_table`-Modus:

Gegeben: Tabelle `orders` mit Spalte `order_number` (SequenceNextVal,
im neutralen Modell `required: true`, im generierten DDL ohne NOT NULL
wegen W119).

```sql
-- Fall 1: INSERT ohne Angabe der Spalte
--   → NEW."order_number" IS NULL → Trigger feuert → Wert wird gesetzt
INSERT INTO "orders" ("customer_id") VALUES (42);
-- Ergebnis: order_number = naechster Sequence-Wert ✓

-- Fall 2: INSERT mit explizitem NULL
--   → NEW."order_number" IS NULL → Trigger feuert → Wert wird gesetzt
--   (lossy: identisch zu Fall 1, dokumentiert als W115)
INSERT INTO "orders" ("order_number", "customer_id") VALUES (NULL, 42);
-- Ergebnis: order_number = naechster Sequence-Wert ✓

-- Fall 3: INSERT mit explizitem Wert
--   → NEW."order_number" IS NOT NULL → Trigger feuert NICHT
--   → expliziter Wert bleibt erhalten
INSERT INTO "orders" ("order_number", "customer_id") VALUES (999, 42);
-- Ergebnis: order_number = 999, kein Sequence-Verbrauch ✓

-- Fall 4: INSERT ... DEFAULT VALUES
--   → alle Spalten erhalten ihren DEFAULT; da order_number keinen
--     DEFAULT hat (Generator unterdrueckt ihn), ist NEW."order_number" NULL
--   → Trigger feuert → Wert wird gesetzt
INSERT INTO "orders" DEFAULT VALUES;
-- Ergebnis: order_number = naechster Sequence-Wert ✓
-- Hinweis: Andere Spalten ohne DEFAULT erhalten ebenfalls NULL;
-- ob das sinnvoll ist, haengt vom Schema ab.

-- Fall 5: INSERT ... ON CONFLICT DO UPDATE (UPSERT)
--   → beim initialen INSERT-Versuch feuert der BEFORE INSERT-Trigger
--     und reserviert einen Sequence-Wert
--   → bei ON CONFLICT DO UPDATE wird der INSERT verworfen, aber der
--     Sequence-Wert ist bereits verbraucht (next_value wurde inkrementiert)
--   → der AFTER INSERT-Trigger feuert NICHT, weil kein INSERT stattfand
--   → Konsequenz: Sequence-Wert geht bei Conflict verloren (Gap)
INSERT INTO "orders" ("order_number", "customer_id") VALUES (NULL, 42)
    ON CONFLICT ("customer_id") DO UPDATE SET "updated_at" = datetime('now');
-- Ergebnis bei Conflict: kein neuer order_number, aber Sequence-Gap ⚠
-- Ergebnis ohne Conflict: order_number = naechster Sequence-Wert ✓
```

Diese Faelle muessen in der Integrations-Teststrategie (§8.2)
abgedeckt werden. Fall 5 (UPSERT-Gap) wird als bekannte
Einschraenkung dokumentiert, nicht als Fehler.

Vorgeschlagene Reihenfolge:

1. Header
2. Custom Types (Kommentare)
3. Sequence-Supportobjekte (`dmg_sequences` + Seeds)
4. User-Tabellen
5. Generierte Sequence-Support-Trigger
6. Indizes
7. Views
8. Nutzerdefinierte Trigger

SQLite erlaubt mehrere Trigger mit gleichem Event und Timing auf
derselben Tabelle, solange die Triggernamen verschieden sind. Die
`WHEN`-Klausel spielt fuer die Eindeutigkeit keine Rolle — sie ist
nur ein Ausfuehrungsfilter. Da jeder Sequence-Support-Trigger einen
eindeutigen kanonischen Namen traegt (§3.3), koennen beliebig viele
Sequences verschiedene Spalten derselben Tabelle bedienen, ohne dass
es zu Namenskonflikten kommt.

Wenn zwei Sequences auf **dieselbe** Spalte zeigen, ist das ein
Modellfehler (zwei `DefaultValue.SequenceNextVal` auf einer Spalte),
der vom Validator abgefangen wird.

Interaktion mit nutzerdefinierten BEFORE INSERT-Triggern:

SQLite fuehrt BEFORE INSERT-Trigger in der Reihenfolge ihrer
Erzeugung aus. Im generierten DDL stehen Sequence-Support-Trigger
(Position 5) **vor** nutzerdefinierten Triggern (Position 8). Damit
feuern Sequence-Trigger zuerst, und nutzerdefinierte Trigger sehen
den Zustand **nach** der Sequence-Reservierung.

Moegliche Interferenz:

- wenn ein nutzerdefinierter BEFORE INSERT-Trigger `NEW.<column>`
  auf einen nicht-NULL-Wert setzt, **und** dieser Trigger vor dem
  Sequence-Trigger erzeugt wurde (z. B. bei manuell erstellten
  Triggern in einer bestehenden Datenbank), dann evaluiert der
  Sequence-Trigger `WHEN NEW.<column> IS NULL` zu `FALSE` und
  feuert nicht
- das kann gewuenscht sein (bewusstes Override durch den Nutzer)
  oder ueberraschend (unbeabsichtigte Maskierung)

Vertrag:

- im `helper_table`-Modus des Generators ist die Reihenfolge
  deterministisch: Sequence-Support-Trigger werden immer vor
  nutzerdefinierten Triggern erzeugt
- bei Reverse einer bestehenden Datenbank, in der nutzerdefinierte
  Trigger vor Sequence-Triggern existieren, wird die
  Erzeugungsreihenfolge **nicht** garantiert; dies ist ein bekannter
  Randfall, der als Einschraenkung dokumentiert wird
- der Generator emittiert **keinen** expliziten Reihenfolge-Hint
  (SQLite hat kein `PRECEDES`/`FOLLOWS` wie MySQL 8); die
  Reihenfolge ergibt sich allein aus der Erzeugungsreihenfolge
  im DDL-Script
- Nutzer, die eigene BEFORE INSERT-Trigger auf sequence-basierten
  Spalten verwenden, muessen sicherstellen, dass diese nach den
  Sequence-Triggern erzeugt werden

### 5.2 Rollback

Rollback muss die kanonischen Hilfsobjekte wieder entfernen:

- generierte Sequence-Support-Trigger-Paare droppen (beide: `_bi` + `_ai`)
- `dmg_sequences` droppen

Vorgeschlagene Rollback-Reihenfolge:

1. Generierte Sequence-Support-Trigger (`DROP TRIGGER IF EXISTS` fuer
   jeden `_bi`- und `_ai`-Trigger)
2. Abhaengigkeitspruefung auf `dmg_sequences`: vor dem DROP prueft
   der Generator, ob es Trigger (oder andere Objekte) gibt, die auf
   `dmg_sequences` verweisen und **nicht** zum kanonischen
   Sequence-Support gehoeren
3. Wenn fremde Abhaengigkeiten existieren: Rollback emittiert einen
   Fehlercode (`E058`) statt eines stillen `DROP TABLE`, damit der
   Nutzer die Abhaengigkeiten zuerst entfernen kann
4. Wenn keine fremden Abhaengigkeiten: `dmg_sequences`
   (`DROP TABLE IF EXISTS`)

Anders als bei MySQL gibt es keine Support-Routinen zum Droppen.

Abhaengigkeitspruefung im Detail:

- der Scan durchsucht **alle** Objekte in `sqlite_schema` (Typ
  `trigger`, `view`, `table`, `index`) nach Textverweisen auf
  `dmg_sequences` im `sql`-Feld
- Objekte, die ueber kanonischen Marker oder kanonischen Namen als
  Sequence-Support identifiziert wurden, werden ausgenommen
- alle verbleibenden Treffer gelten als fremde Abhaengigkeiten
- typische Faelle: nutzerdefinierte Trigger, die `dmg_sequences`
  lesen/schreiben; Views, die `dmg_sequences` referenzieren;
  Indizes auf `dmg_sequences` (unwahrscheinlich, aber moeglich)
- in SQLite ist diese Pruefung statisch moeglich, weil alle
  DDL-Definitionen in `sqlite_schema.sql` als Text vorliegen
- die Pruefung ist konservativ: im Zweifel wird `E058` emittiert
- `E058`: Cannot drop dmg_sequences: non-managed objects reference
  this table; remove external dependencies first

### 5.3 Warning-/Error-Semantik

- `E056` nur noch in `action_required`-Mode
- in `helper_table`-Mode stattdessen regulaere DDL
- reservierte Hilfsnamen im Nutzerschema erzeugen einen expliziten
  Konfliktcode; keine stille Umbenennung
- lossy Mapping bekommt eigene Warnings:
  - `W114`: Sequence cache is not emulated in SQLite helper-table mode
    (SQLite ist Single-Writer; Caching bringt keinen Vorteil)
  - `W115`: SequenceNextVal uses lossy trigger semantics; explicit
    NULL is treated like omitted value (identisch zu MySQL)
  - `W116`: Sequence metadata reconstructed, but required support
    triggers are missing or incomplete (Reverse-Degradation;
    identisch zu MySQL)
  - `W117`: Sequence values are transaction-bound in SQLite
    helper-table mode; rollback retracts sequence increments unlike
    native PostgreSQL sequences (identisch zu MySQL)
- NOT NULL-Unterdrueckung auf sequence-basierten Spalten:
  - `W119`: NOT NULL constraint suppressed on sequence-backed column
    for two-trigger compatibility; column value is guaranteed by
    AFTER INSERT trigger (§3.4)
- `WITHOUT ROWID`-Tabellen mit sequence-basierten Spalten:
  - `E057`: Sequence-backed column on WITHOUT ROWID table cannot use
    automatic trigger assignment (§3.7)
- Rollback-Abhaengigkeitskonflikte:
  - `E058`: Cannot drop dmg_sequences: non-managed objects reference
    this table; remove external dependencies first (§5.2)

Diese Codes muessen vor Implementierung zentral dokumentiert werden.
`W114`, `W115`, `W116` und `W117` sind mit dem MySQL-Plan geteilt;
`W119`, `E057` und `E058` sind SQLite-spezifisch.

---

## 6. Reverse-Engineering und Compare

### 6.1 SqliteSchemaReader

Der Reader muss die kanonischen Hilfsobjekte erkennen und in
`schema.sequences` zurueckfalten.

Dazu braucht er:

- Erkennung der Tabelle `dmg_sequences` (ueber Name + Spaltenstruktur)
- Pruefung der Marker `managed_by` und `format_version`
- Rekonstruktion der `SequenceDefinition`-Felder aus den Zeilen
- Rekonstruktion von sequence-basierten Spaltenwerten ueber kanonische
  Sequence-Support-Trigger-Paare (`_bi` + `_ai`) mit Name +
  Marker-Kommentar + kanonischer Body-Form

Die Hilfsobjekte duerfen danach nicht zugleich als normale Tabelle
oder Trigger im neutralen Schema auftauchen.

Wichtig:

- Sequence-Reverse darf **nicht** von `includeTriggers` abhaengen;
  der Reader muss kanonische Sequence-Support-Trigger intern
  inspizieren
- es gibt keine Support-Routinen zu pruefen (anders als bei MySQL)
- Trigger ohne gueltigen Marker-Kommentar werden **nicht** als
  d-migrate-Supportobjekte interpretiert, auch wenn ihr Name aehnlich
  aussieht
- fehlen Sequence-Support-Trigger, koennen `SequenceDefinition`-Zeilen
  zwar weiterhin rekonstruiert werden, aber die Zuordnung zu
  sequence-basierten Spaltenwerten gilt dann als unvollstaendig

SQLite-spezifische Reader-Herausforderungen:

- SQLite speichert das vollstaendige `CREATE TRIGGER`-Statement in
  `sqlite_master.sql` (bzw. `sqlite_schema` ab SQLite 3.33.0); der
  Reader muss dieses parsen, um den Marker-Kommentar und die Body-Form
  zu extrahieren
- da SQLite keine Routinen hat, ist der Reverse-Pfad einfacher als
  bei MySQL: nur Tabelle + Trigger muessen erkannt werden

Reverse-Erkennungsstrategie und Fallback:

- primaere Erkennung laeuft ueber den **Marker-Kommentar** im
  Trigger-Body (erster Kommentar im `BEGIN...END`-Block)
- der Marker enthaelt `d-migrate:sqlite-sequence-v1`, Objekttyp,
  Sequence-Name, Tabelle und Spalte — das ist die autoritaive Quelle
- **wenn der Marker-Kommentar fehlt oder nicht parsbar ist**, greift
  ein **deterministisches Sekundaer-Matching** ueber drei Kriterien:
  1. Triggername entspricht dem kanonischen Schema
     (`dmg_seq_<...>_bi` / `dmg_seq_<...>_ai`)
  2. Trigger-Event und -Timing passen (BEFORE INSERT / AFTER INSERT)
  3. WHEN-Klausel hat die Form `NEW.<column> IS NULL`
  Nur wenn **alle drei** Kriterien zutreffen, wird der Trigger als
  **wahrscheinliches** Sequence-Supportobjekt behandelt und mit
  `W116` (degradiert) markiert; die Spaltenzuordnung wird aus dem
  Triggernamen und der WHEN-Klausel heuristisch rekonstruiert
- wenn keines der drei Kriterien zutrifft, wird der Trigger als
  normaler nutzerdefinierter Trigger ins neutrale Schema uebernommen
- das Sekundaer-Matching ist bewusst **eng begrenzt**: es prueft
  nur deterministische, strukturelle Merkmale (Name, Event, WHEN),
  keine Body-Analyse; dadurch bleibt das False-Positive-Risiko
  minimal
- Konsequenz: wenn ein Nutzer den Marker-Kommentar aus einem
  generierten Trigger entfernt, verliert Reverse die Zuordnung zu
  der Sequence; die `dmg_sequences`-Zeile wird weiterhin erkannt,
  aber die Spaltenzuordnung fehlt, und der Trigger taucht als
  normaler nutzerdefinierter Trigger im neutralen Schema auf
- dieses Verhalten ist gewollt und wird als Stabilitaetsgarantie
  dokumentiert: keine Heuristik, kein "fuzzy" Matching

**Roundtrip-Risiko bei manueller Trigger-Bearbeitung:**

Wenn ein Nutzer einen generierten Sequence-Support-Trigger manuell
editiert, ergeben sich folgende Szenarien:

- **Marker-Kommentar entfernt, Name/Event/WHEN intakt**: Sekundaer-
  Matching greift, Zuordnung wird mit `W116` (degradiert)
  rekonstruiert; der Trigger erscheint nicht als nutzerdefiniert
- **Trigger umbenannt** (Name passt nicht mehr zum Schema):
  Zuordnung geht verloren; Trigger erscheint als nutzerdefiniert;
  `dmg_sequences`-Zeile bleibt als `SequenceDefinition` erkannt,
  aber Spaltenzuordnung fehlt
- **Body geaendert, Marker und Name intakt**: primaeres Matching
  ueber Marker greift weiterhin; Body-Aenderungen werden nicht
  geprueft (der Trigger gilt als d-migrate-Supportobjekt, auch wenn
  der Body abweicht)

Dieses abgestufte Verhalten wird in der Nutzerdokumentation als
**Roundtrip-Risiko** dokumentiert:

> "Sequence-Support-Trigger sind generierte Infrastruktur. Das
> Entfernen des Marker-Kommentars fuehrt zu degradierter Erkennung
> (W116). Das Umbenennen des Triggers fuehrt zum Verlust der
> Spaltenzuordnung. Aendern Sie Sequence-Parameter ausschliesslich
> im neutralen Schema und generieren Sie neu."

### 6.2 Compare

Sobald Reverse die Hilfsobjekte wieder auf `sequences` mappt, bleibt
`SchemaComparator` weitgehend neutral und braucht keine
sonderdialektische Compare-Logik (identisch zu MySQL).

Trotzdem zu pruefen:

- gleiche neutrale Sequence -> kein Diff
- geaenderte Emulationszeile -> `sequencesChanged`
- fehlende Supportobjekte -> Reverse-Warning oder unvollstaendige Sequence

---

## 7. Arbeitspakete

### Phase A — Vertrag schaerfen

- Zwei-Trigger-Strategie (`_bi` + `_ai`) gegen echte SQLite-DB
  prototypisch validieren (Entscheidung fuer diesen Ansatz steht,
  Prototyp muss Korrektheit bestaetigen)
- minimale SQLite-Version fuer `helper_table`-Modus festlegen
  (Zwei-Trigger-Strategie benoetigt nur BEFORE/AFTER-Trigger und
  ROWID — verfuegbar seit SQLite 3.0; die Mindestversion sollte
  sich an der aeltesten im Projekt getesteten Version orientieren,
  nicht an RETURNING/3.35.0)
- kanonisches Hilfsobjekt-Layout finalisieren (Trigger-Body-Form
  haengt von der Zuweisungsstrategie ab)
- DEFAULT-Constraint-Interaktion: Validierungsregel festlegen, dass
  `SequenceNextVal`-Spalten keinen `DEFAULT` tragen duerfen
- Marker- und Namespace-Vertrag finalisieren (konsistent mit MySQL)
- Konfliktcode fuer reservierte Hilfsnamen festlegen
- Warning-Codes festziehen (`W114`, `W115`, `W116`, `W117`, `W119`,
  `E057`, `E058`)
- CLI-/Config-Vertrag dokumentieren
- Abhaengigkeit zum MySQL-Plan fuer
  `DefaultValue.SequenceNextVal` klaeren
- SQLite `RAISE(ABORT, ...)` als Fehlerpfad validieren
- Transaktionsvertrag fuer manuelle `dmg_sequences`-Zugriffe
  dokumentieren (Advanced Operation, kein stabiles API)

### Phase B — Generator und Optionen

Voraussetzung: `DefaultValue.SequenceNextVal` ist implementiert
(ggf. aus MySQL-Plan Phase B).

- `DdlGenerationOptions` um `sqliteNamedSequenceMode` erweitern
- `SqliteNamedSequenceMode` Enum anlegen
- CLI-/Runner-Pfad erweitern (`--sqlite-named-sequences`)
- `SchemaGenerateRequest` erweitern
- `SchemaGenerateRunner` um SQLite-Sequence-Mode-Validierung erweitern
- Mapping fuer `DefaultValue.SequenceNextVal(...)` in SQLite implementieren
- `SqliteDdlGenerator.generateSequences(...)` auf `helper_table` ausbauen
- Kanonische Sequence-Support-Trigger fuer SQLite generieren
- Rollback-Inversion fuer Supportobjekte implementieren

### Phase C — Tests und Golden Masters

Phase C laeuft bewusst parallel zu Phase B.

- SQLite-Unit-Tests fuer beide Modi
- Golden Master fuer `full-featured.sqlite.sql` im `helper_table`-Pfad
- Runner-Tests fuer Option-Parsing und Report-Inhalt
- Validierung, dass `--sqlite-named-sequences` bei nicht-SQLite-Targets
  abgelehnt wird

### Phase D — Reverse-Engineering

- `SqliteSchemaReader` um Sequence-Erkennung erweitern
- `sqlite_master.sql`-Parsing fuer Marker-Kommentar und Body-Form
- Reverse von sequence-basierten Spaltenwerten ueber
  Sequence-Support-Trigger implementieren
- Reader-Integrationstests gegen echte SQLite-DB
- Sicherstellen, dass Hilfsobjekte nicht doppelt im neutralen Modell
  landen

### Phase E — Compare und Stabilisierung

- Compare-Fixtures fuer emulierte Sequences
- Reverse->Generate->Reverse Round-Trip
- Dokumentation und Release-Hinweise
- Konsistenz mit MySQL-Plan pruefen (gleiche Codes, gleiche Semantik)

---

## 8. Teststrategie

Coverage-Ziel: 90% Zeilenabdeckung pro betroffenem Modul, insbesondere
fuer die neuen Pfade in `SqliteDdlGenerator`, `SqliteSchemaReader` und
die `DefaultValue.SequenceNextVal`-Behandlung.

### 8.1 Unit

- `SqliteDdlGeneratorTest`
  - `action_required` bleibt wie heute
  - `helper_table` erzeugt Support-DDL statt `E056`
  - sequence-basierte Spaltenwerte erzeugen kanonische Trigger
  - `W114` bei `cache`-Werten
  - `W115` bei sequence-basierten Spaltenwerten
  - `W117` bei jeder Sequence im `helper_table`-Modus
  - `W119` bei `required: true` + `SequenceNextVal`-Spalten
  - `E057` bei `WITHOUT ROWID` + `SequenceNextVal`-Spalten
  - Konflikt mit reservierten Hilfsnamen wird sauber abgelehnt
- `SchemaGenerateRunnerTest`
  - neuer Optionspfad `--sqlite-named-sequences`
  - Fehler bei ungueltigem Wert
  - Fehler bei falschem Dialekt
- Format-/Golden-Master-Tests
  - neue SQLite-DDL-Fixtures

### 8.2 Integration

- SQLite-DDL gegen echte Datenbank ausfuehren
- Sequence-Wert wird bei `INSERT` korrekt vergeben (je nach
  Zuweisungsstrategie)
- negativer `increment` liefert fallende Werte
- `cycle`- und `max_value`-Pfad
- erschoepfte Sequence ohne `cycle` liefert `RAISE(ABORT, ...)`
- Reverse liest `dmg_sequences` wieder als `sequences`
- Reverse liest sequence-basierte Spaltenwerte ueber kanonische Trigger
  wieder in den neutralen Default-Typ zurueck
- Rollback einer Transaktion retrahiert den Sequence-Inkrement
- mehrere Sequences auf verschiedenen Spalten derselben Tabelle
  funktionieren korrekt
- INSERT ohne Angabe der Sequence-Spalte vergibt Wert (Fall 1)
- INSERT mit explizitem NULL vergibt Wert (Fall 2, W115)
- INSERT mit explizitem Wert behaelt ihn (Fall 3)
- INSERT ... DEFAULT VALUES vergibt Wert (Fall 4)
- INSERT ... ON CONFLICT verbraucht Sequence-Wert bei Conflict (Fall 5)
- fehlende `dmg_sequences`-Zeile fuehrt zu RAISE(ABORT), nicht zu
  stillem NULL
- NOT NULL-Spalte mit SequenceNextVal akzeptiert INSERT ohne Wert
  (W119-Unterdrueckung wirkt korrekt)

### 8.3 Round-Trip

- neutral -> SQLite-DDL -> SQLite reverse -> neutral
- Compare zwischen Originalschema und reverse-Schema bleibt
  sequence-stabil

---

## 9. Risiken

### R1 — Zwei-Trigger-Strategie muss prototypisch validiert werden

Die Entscheidung fuer die Zwei-Trigger-Strategie (`_bi` + `_ai`)
steht (§3.4). Das Restrisiko liegt in der prototypischen Validierung:
die AFTER INSERT-Zuweisung per `ROWID` muss unter allen relevanten
Bedingungen korrekt funktionieren (mehrere Sequences pro Tabelle,
Batch-INSERTs, Transaktions-Rollback).

Gegenmassnahme:

- prototypische Implementierung in Phase A gegen echte SQLite-DB
- Integrationstests gegen verschiedene SQLite-Versionen
- Edge-Case-Tests fuer Batch-INSERT und Multi-Sequence-Tabellen

### R2 — Emulation ohne Stored Functions ist weniger ergonomisch

Ohne `dmg_nextval(...)` muessen Anwendungen, die Sequence-Werte
ausserhalb von `INSERT`-Statements abrufen wollen, direkt SQL gegen
`dmg_sequences` ausfuehren. Das ist weniger elegant als ein
Funktionsaufruf.

Gegenmassnahme:

- klare Dokumentation der SQL-Befehle fuer manuelle Nutzung
- die primaere Nutzung (automatischer Wert bei `INSERT`) ist ueber
  Trigger abgedeckt und braucht keinen manuellen Aufruf

### R3 — Reverse erkennt Trigger-Body nicht robust genug

Der SQLite-Trigger-Body aus `sqlite_master.sql` kann durch
Formatierungsunterschiede oder SQLite-Versions-Unterschiede variieren.

Gegenmassnahme:

- Reverse erkennt primaer ueber Marker-Kommentar, nicht ueber
  exaktes Body-Matching
- kanonische Body-Form als zusaetzliche Pruefung, nicht als alleiniges
  Kriterium
- Tests gegen mindestens zwei SQLite-Versionen

### R4 — Hilfsobjekte kollidieren mit Nutzerobjekten

Identisch zu MySQL (R3 im MySQL-Plan).

Gegenmassnahme:

- reservierter d-migrate-Namespace
- Marker-Spalten statt Namensheuristik allein
- Reverse erkennt Supportobjekte nur bei voller Signatur

### R5 — ROWID-Abhaengigkeit bei Zwei-Trigger-Strategie

Die Zwei-Trigger-Strategie haengt von `ROWID` ab. `WITHOUT ROWID`-
Tabellen mit sequence-basierten Spalten werden deshalb mit `E057`
abgelehnt (§3.7).

Restrisiko: Nutzer mit `WITHOUT ROWID`-Tabellen und Sequence-Bedarf
muessen `action_required` verwenden und die Sequencing-Logik manuell
implementieren. Wenn der Bedarf fuer einen PK-basierten AFTER
INSERT-Trigger nachgewiesen wird, kann eine spaetere Ausbaustufe
diesen Pfad ergaenzen.

Gegenmassnahme:

- klare `E057`-Fehlermeldung mit Hint auf `action_required`
- PK-basierter Trigger als optionale spaetere Erweiterung
  dokumentiert, nicht als Phase-1-Ziel

### R6 — Semantikabweichung gegen native PostgreSQL-Sequences

Identisch zu MySQL (R6 im MySQL-Plan).

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
- Integrationstests

Die **vollstaendige Produktvariante** dieses Dokuments ist als
**grosses** Arbeitspaket einzustufen, allerdings kleiner als die
MySQL-Variante, da keine Stored Functions generiert oder reverse-bart
werden muessen.

Grobe Einschaetzung pro Phase (T-Shirt-Sizing):

- Phase A (Vertrag): M — primaer Doku und Entscheidungen, aber die
  Zuweisungsstrategie erfordert einen Prototyp
- Phase B (Generator + Optionen): M — schmaler als MySQL, da keine
  Routinen-DDL; aber Trigger-Body-Generierung ist komplex
- Phase C (Tests + Golden Masters): M — parallel zu Phase B
- Phase D (Reverse): M — einfacher als MySQL (nur Tabelle + Trigger,
  keine Routinen)
- Phase E (Compare + Stabilisierung): S — Round-Trip-Absicherung

---

## 11. Empfehlung

Bereits getroffene Entscheidungen:

- **Zuweisungsstrategie**: Zwei-Trigger (`_bi` + `_ai`) als
  kanonischer Produktpfad (§3.4)
- **`WITHOUT ROWID`**: hard-fail mit `E057` (§3.7)
- **App-Level (ex-Strategie B)**: nicht Teil von `helper_table`;
  nur als Doku-Pattern fuer `action_required`-Nutzer

Vor dem ersten Code:

1. Zwei-Trigger-Prototyp gegen echte SQLite-DB validieren
   (Korrektheit, Batch-INSERT, Multi-Sequence-Tabelle)
2. Minimale SQLite-Version festlegen (Zwei-Trigger braucht nur
   BEFORE/AFTER-Trigger + ROWID — keine RETURNING-Abhaengigkeit;
   Minimum orientiert sich an der aeltesten getesteten Version)
3. Abhaengigkeit zum MySQL-Plan klaeren: wird
   `DefaultValue.SequenceNextVal` gemeinsam oder getrennt implementiert?
4. Kanonisches SQLite-Trigger-Body-Layout finalisieren (Grenzpruefung,
   Zyklus-Reset, Marker-Kommentar-Position)
5. `cache`-Warnung (`W114`) fuer SQLite bestaetigen
6. Transaktionsvertrag fuer manuelle `dmg_sequences`-Zugriffe festlegen

Erst danach sollte die eigentliche Implementierung beginnen. Das
Hauptrisiko liegt jetzt in der prototypischen Validierung der
Zwei-Trigger-Strategie unter realen Bedingungen.

### Vergleich mit MySQL-Plan

| Aspekt | MySQL | SQLite |
|---|---|---|
| Stored Functions | `dmg_nextval`, `dmg_setval` | Nicht moeglich |
| Nextval-Logik | In Stored Function | Inline in Trigger-Paar (`_bi` + `_ai`) |
| Trigger-Zuweisung | `SET NEW.<col> = ...` (1 Trigger) | AFTER INSERT + ROWID (2 Trigger) |
| `WITHOUT ROWID` | n/a (MySQL-Konzept existiert nicht) | `E057` hard-fail |
| Concurrency | Row-Level-Lock auf `dmg_sequences` | Single-Writer (implizit serialisiert) |
| Rollback-Semantik | Identisch (transaktionsgebunden) | Identisch |
| Reverse-Komplexitaet | Tabelle + Routinen + Trigger | Tabelle + Trigger-Paare |
| Support-Routinen droppen | Ja | Nein (gibt es nicht) |
| `format_version` | `mysql-sequence-v1` | `sqlite-sequence-v1` |
