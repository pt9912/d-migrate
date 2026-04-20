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
- pro sequence-basierter Spalte ein kanonischer `BEFORE INSERT`-Trigger,
  der bei `NEW.<column> IS NULL` den naechsten Wert direkt ueber ein
  inline `UPDATE`/`SELECT` auf `dmg_sequences` einsetzt

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
    "increment_by" INTEGER NOT NULL,
    "min_value" INTEGER NULL,
    "max_value" INTEGER NULL,
    "cycle_enabled" INTEGER NOT NULL,
    "cache_size" INTEGER NULL,
    PRIMARY KEY ("name")
);
```

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
- Trigger: deterministisch begrenztes Schema
  `dmg_seq_<table16>_<column16>_<hash10>_bi`

Vorgeschlagene Marker:

- `managed_by = 'd-migrate'`
- `format_version = 'sqlite-sequence-v1'`
- Sequence-Support-Trigger starten mit einem kanonischen Marker-Kommentar,
  z. B.
  `/* d-migrate:sqlite-sequence-v1 object=sequence-trigger sequence=<name> table=<table> column=<column> */`

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
  - Suffix `_bi`
- dieses Schema bleibt immer <= 55 Zeichen
- Reverse identifiziert Sequence-Support-Trigger **nicht** nur ueber den
  Namen, sondern ueber Name + Marker-Kommentar + kanonische Body-Form
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
-- Pseudocode: Logischer Ablauf eines Sequence-Support-Triggers
--
-- Schritt 1: Aktuellen Wert merken (= Rueckgabewert)
--   returned_value := next_value           -- aus dmg_sequences
--
-- Schritt 2: next_value inkrementieren
--   next_value := next_value + increment_by
--
-- Schritt 3: Grenzpruefung (NACH Inkrement, auf den NEUEN next_value)
--   IF increment_by > 0
--      AND next_value > COALESCE(max_value, MAX_INT):
--     IF cycle_enabled = 0: RAISE(ABORT, 'exhausted')
--     ELSE: next_value := COALESCE(min_value, 1)
--   IF increment_by < 0
--      AND next_value < COALESCE(min_value, MIN_INT):
--     IF cycle_enabled = 0: RAISE(ABORT, 'exhausted')
--     ELSE: next_value := COALESCE(max_value, MAX_INT)
--
-- Schritt 4: returned_value in NEW.<column> einsetzen
--   NEW.<column> := returned_value         -- NICHT direkt moeglich (s.u.)
```

Hinweis zur Zyklus-Logik: Die Grenzpruefung muss auf den **neuen**
`next_value` (nach Inkrement) erfolgen, nicht auf den zurueckgegebenen
Wert. Der zurueckgegebene Wert (`returned_value`) ist immer der Wert
**vor** dem Inkrement und wird durch einen Cycle-Reset nicht veraendert.
Der Reset setzt nur `next_value` zurueck, damit der **naechste** Aufruf
vom Zyklusanfang startet.

**Zentrale Limitation: NEW-Zuweisung in SQLite-Triggern**

SQLite-Trigger koennen `NEW.<column>` nicht direkt per `SET` zuweisen
wie MySQL (`SET NEW.col = expr`). Es gibt in SQLite **keine**
eingebaute Funktion, die das ermoeglicht. Die Zuweisung muss
stattdessen ueber eine der folgenden Strategien erfolgen:

- **Strategie A — Zwei-Trigger (BEFORE + AFTER INSERT)**:
  Ein `BEFORE INSERT`-Trigger reserviert den naechsten Wert in
  `dmg_sequences` (atomares `UPDATE`). Ein `AFTER INSERT`-Trigger
  schreibt den reservierten Wert per
  `UPDATE ... WHERE ROWID = NEW.ROWID` in die eingefuegte Zeile.
  Vorteile: transparent, kein Applikationscode noetig.
  Nachteile: erfordert `ROWID`-Zugriff; **bricht bei
  `WITHOUT ROWID`-Tabellen** (siehe R5 und offene Fragen unten).
  Phase A muss entscheiden, ob `WITHOUT ROWID`-Tabellen mit
  sequence-basierten Spalten als Einschraenkung abgelehnt werden
  (`action_required` oder Fehler) oder ob der `AFTER INSERT`-Trigger
  alternativ ueber den PK der Tabelle adressiert.

- **Strategie B — Nur Tabelle + dokumentierte INSERT-Syntax (App-Level)**:
  Kein automatischer Trigger. Die Anwendung holt den naechsten Wert
  selbst aus `dmg_sequences` und setzt ihn explizit im `INSERT` ein.
  Ein optionaler `BEFORE INSERT`-Trigger blockiert per `RAISE(ABORT)`
  wenn `NEW.<column> IS NULL`, um fehlende Werte frueh abzufangen.
  Vorteile: zuverlaessigste Strategie, kein `ROWID`-Problem,
  kompatibel mit allen Tabellentypen.
  Nachteile: weniger transparent; die Sequence-Vergabe ist nicht
  automatisch, sondern erfordert Applikationslogik.

- **Strategie C — BEFORE INSERT mit RETURNING-basiertem Subselect**:
  Seit SQLite 3.35.0 (2021-03-12) unterstuetzt `UPDATE ... RETURNING`.
  Ein BEFORE INSERT-Trigger koennte theoretisch den Wert in einem
  einzigen Statement reservieren und zurueckgeben. Problem: auch mit
  RETURNING kann der Rueckgabewert nicht direkt in `NEW.<column>`
  geschrieben werden; RETURNING ist primaer fuer die aufrufende
  Applikation, nicht fuer Trigger-interne Logik.

Phase A muss die endgueltige Zuweisungsstrategie festlegen. Die
zentrale Herausforderung ist die `NEW.<column>`-Zuweisung, die in
SQLite-Triggern nicht direkt moeglich ist — es gibt keine eingebaute
Funktion oder Syntax dafuer.

Realistischer Ansatz fuer Phase A:

Die wahrscheinlichste Produktstrategie ist entweder:

- **Strategie A** (Zwei-Trigger) fuer Tabellen mit `ROWID`
  (= Standard), mit explizitem Ausschluss von `WITHOUT ROWID`-Tabellen
- **Strategie B** (App-Level) als sicherster Fallback, der auf allen
  Tabellentypen funktioniert

Beispiel fuer Strategie B (ausfuehrbares SQL):

```sql
-- Anwendung holt naechsten Wert und fuehrt INSERT in einer Transaktion aus:
BEGIN;
UPDATE "dmg_sequences"
    SET "next_value" = "next_value" + "increment_by"
    WHERE "name" = 'order_number_seq';
INSERT INTO "orders" ("order_number", ...)
VALUES (
    (SELECT "next_value" - "increment_by" FROM "dmg_sequences"
     WHERE "name" = 'order_number_seq'),
    ...
);
COMMIT;
```

Beispiel fuer Strategie A (ausfuehrbares SQL):

```sql
-- BEFORE INSERT: reserviert den Wert
CREATE TRIGGER "dmg_seq_orders_order_num_a1b2c3d4e5_bi"
BEFORE INSERT ON "orders"
FOR EACH ROW
WHEN NEW."order_number" IS NULL
BEGIN
    /* d-migrate:sqlite-sequence-v1 object=sequence-trigger sequence=order_number_seq table=orders column=order_number */
    UPDATE "dmg_sequences"
        SET "next_value" = "next_value" + "increment_by"
        WHERE "name" = 'order_number_seq';
END;

-- AFTER INSERT: schreibt den reservierten Wert in die Zeile
CREATE TRIGGER "dmg_seq_orders_order_num_a1b2c3d4e5_ai"
AFTER INSERT ON "orders"
FOR EACH ROW
WHEN NEW."order_number" IS NULL
BEGIN
    /* d-migrate:sqlite-sequence-v1 object=sequence-trigger-post sequence=order_number_seq table=orders column=order_number */
    UPDATE "orders"
        SET "order_number" = (
            SELECT "next_value" - "increment_by"
            FROM "dmg_sequences"
            WHERE "name" = 'order_number_seq'
        )
        WHERE ROWID = NEW.ROWID;
END;
```

Hinweis: Bei Strategie A ist die Grenzpruefung (Exhaustion, Cycle)
im BEFORE INSERT-Trigger zu implementieren, **bevor** der INSERT
durchlaeuft. Der obige Pseudocode-Ablauf (Schritte 1-3) bildet diese
Reihenfolge ab. Die Zyklus-Logik muss zwischen dem UPDATE (Schritt 2)
und dem Ende des BEFORE-Triggers liegen.

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

- `helper_table`: Mapping auf kanonischen `BEFORE INSERT`-Trigger
  (oder Zwei-Trigger-Ansatz), der bei `NEW.<column> IS NULL` intern
  den naechsten Wert aus `dmg_sequences` bezieht; diese Abbildung ist
  lossy (identisch zu MySQL)
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
SQLite-DDL auf einen kanonischen Trigger-Pfad abgebildet werden.

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

### 5.2 Rollback

Rollback muss die kanonischen Hilfsobjekte wieder entfernen:

- generierte Sequence-Support-Trigger droppen
- `dmg_sequences` droppen

Vorgeschlagene Rollback-Reihenfolge:

1. Generierte Sequence-Support-Trigger (`DROP TRIGGER IF EXISTS`)
2. `dmg_sequences` (`DROP TABLE IF EXISTS`)

Anders als bei MySQL gibt es keine Support-Routinen zum Droppen.

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
  - `W117`: Sequence values are transaction-bound in SQLite
    helper-table mode; rollback retracts sequence increments unlike
    native PostgreSQL sequences (identisch zu MySQL)
  - `W118`: SQLite sequence trigger cannot directly assign
    NEW.<column>; see documentation for the applied workaround
    strategy (nur falls Strategie A oder B gewaehlt wird)

Diese Codes muessen vor Implementierung zentral dokumentiert werden.
`W114`, `W115` und `W117` sind mit dem MySQL-Plan geteilt; `W118` ist
SQLite-spezifisch.

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
  Sequence-Support-Trigger mit Name + Marker-Kommentar + kanonischer
  Body-Form

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
- **wenn der Marker-Kommentar fehlt oder nicht parsbar ist**, wird
  der Trigger **nicht** als d-migrate-Supportobjekt erkannt, auch
  wenn sein Name dem kanonischen Schema entspricht
- es gibt bewusst **keinen** strukturellen Fallback (z. B. Analyse
  des Trigger-Bodys auf `dmg_sequences`-Zugriffe ohne Marker), da
  das zu False Positives bei aehnlich aussehenden, aber manuell
  erstellten Triggern fuehren wuerde
- Konsequenz: wenn ein Nutzer den Marker-Kommentar aus einem
  generierten Trigger entfernt, verliert Reverse die Zuordnung zu
  der Sequence; die `dmg_sequences`-Zeile wird weiterhin erkannt,
  aber die Spaltenzuordnung fehlt, und der Trigger taucht als
  normaler nutzerdefinierter Trigger im neutralen Schema auf
- dieses Verhalten ist gewollt und wird als Stabilitaetsgarantie
  dokumentiert: keine Heuristik, kein "fuzzy" Matching

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

- Zuweisungsstrategie fuer `NEW.<column>` in SQLite-Triggern
  endgueltig festlegen (Zwei-Trigger, App-Level, oder alternative
  Loesung) — mit Prototyp gegen echte SQLite-DB validieren
- `WITHOUT ROWID`-Tabellen: Verhalten bei Strategie A festlegen
  (Ablehnung mit `action_required`/Fehler, oder Adressierung ueber PK
  statt ROWID)
- minimale SQLite-Version fuer `helper_table`-Modus festlegen
- kanonisches Hilfsobjekt-Layout finalisieren (Trigger-Body-Form
  haengt von der Zuweisungsstrategie ab)
- DEFAULT-Constraint-Interaktion: Validierungsregel festlegen, dass
  `SequenceNextVal`-Spalten keinen `DEFAULT` tragen duerfen
- Marker- und Namespace-Vertrag finalisieren (konsistent mit MySQL)
- Konfliktcode fuer reservierte Hilfsnamen festlegen
- Warning-Codes festziehen (`W114`, `W115`, `W117`, ggf. `W118`)
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

### 8.3 Round-Trip

- neutral -> SQLite-DDL -> SQLite reverse -> neutral
- Compare zwischen Originalschema und reverse-Schema bleibt
  sequence-stabil

---

## 9. Risiken

### R1 — Zuweisungsstrategie fuer `NEW.<column>` ist nicht trivial

SQLite-Trigger koennen `NEW.<column>` nicht direkt per `SET` zuweisen.
Das ist die **zentrale technische Herausforderung** dieses Plans und
der Hauptunterschied zur MySQL-Variante.

Gegenmassnahme:

- Phase A evaluiert alle Strategien (Zwei-Trigger, App-Level,
  RETURNING-basiert) und waehlt die zuverlaessigste
- prototypische Implementierung vor der vollen Generierung
- Integrationstests gegen verschiedene SQLite-Versionen

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

Falls die Zwei-Trigger-Strategie (Strategie A) gewaehlt wird, haengt
der `AFTER INSERT`-Trigger von `ROWID` ab. `WITHOUT ROWID`-Tabellen
wuerden diesen Pfad brechen.

Gegenmassnahme:

- `WITHOUT ROWID`-Tabellen als Einschraenkung dokumentieren
- oder alternative Strategie waehlen, die nicht von `ROWID` abhaengt

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

Vor dem ersten Code:

1. Zuweisungsstrategie fuer `NEW.<column>` in SQLite-Triggern
   festlegen und prototypisch validieren
2. `WITHOUT ROWID`-Verhalten bei Strategie A entscheiden
3. DEFAULT-Constraint-Ausschluss fuer `SequenceNextVal`-Spalten als
   Validierungsregel festlegen
4. Minimale SQLite-Version festlegen (z. B. 3.35.0 fuer RETURNING)
5. Abhaengigkeit zum MySQL-Plan klaeren: wird
   `DefaultValue.SequenceNextVal` gemeinsam oder getrennt implementiert?
6. Kanonisches SQLite-Objektlayout und Trigger-Body-Form festlegen
7. `cache`-Warnung (`W114`) fuer SQLite bestaetigen
8. SQLite-spezifischen Warning-Code `W118` entscheiden
9. Transaktionsvertrag fuer manuelle `dmg_sequences`-Zugriffe festlegen

Erst danach sollte die eigentliche Implementierung beginnen. Die
zentrale Unsicherheit liegt in der `NEW.<column>`-Zuweisungsstrategie —
ohne einen validierten Prototyp droht eine Emulation, die zwar
`dmg_sequences` erzeugt, aber die Werte nicht zuverlaessig in die
Zielzeile schreiben kann.

### Vergleich mit MySQL-Plan

| Aspekt | MySQL | SQLite |
|---|---|---|
| Stored Functions | `dmg_nextval`, `dmg_setval` | Nicht moeglich |
| Nextval-Logik | In Stored Function | Inline in Trigger |
| Trigger-Zuweisung | `SET NEW.<col> = ...` | Offen (Phase A) |
| Concurrency | Row-Level-Lock auf `dmg_sequences` | Single-Writer (implizit serialisiert) |
| Rollback-Semantik | Identisch (transaktionsgebunden) | Identisch |
| Reverse-Komplexitaet | Tabelle + Routinen + Trigger | Tabelle + Trigger |
| Support-Routinen droppen | Ja | Nein (gibt es nicht) |
| `format_version` | `mysql-sequence-v1` | `sqlite-sequence-v1` |
