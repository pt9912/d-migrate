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
-- Wichtig: exhausted und last_returned_value muessen ebenfalls
-- zurueckgesetzt werden, damit die Sequence wieder funktioniert
-- (insbesondere nach Erschoepfung bei cycle_enabled = 0).
UPDATE "dmg_sequences"
    SET "next_value" = 42,
        "exhausted" = 0,
        "last_returned_value" = NULL
    WHERE "name" = 'invoice_seq';
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
    "exhausted" INTEGER NOT NULL DEFAULT 0,
    "increment_by" INTEGER NOT NULL,
    "min_value" INTEGER NULL,
    "max_value" INTEGER NULL,
    "cycle_enabled" INTEGER NOT NULL,
    "cache_size" INTEGER NULL,
    PRIMARY KEY ("name")
);
```

`exhausted` ist ein Boolean-Flag (0/1), das anzeigt, ob die Sequence
bei `cycle_enabled = 0` erschoepft ist. Der BEFORE INSERT-Trigger
setzt `exhausted = 1` wenn das naechste Inkrement die Grenze
ueberschreiten wuerde. Der Erschoepfungs-RAISE prueft dieses Flag
statt eines numerischen Sentinel-Werts — dadurch entfaellt das
Problem, dass `max_value + 1` oder `min_value - 1` den 64-bit
INTEGER-Bereich von SQLite ueberschreiten koennte.

`last_returned_value` speichert den zuletzt von einem Trigger
zurueckgegebenen Wert. Die Spalte ist `NULL` nach der initialen
Seed-Erzeugung und wird erst beim ersten Trigger-Aufruf gesetzt.
Sie ist **nicht** Teil des neutralen Modells (`SequenceDefinition`),
sondern ein Implementierungsdetail der SQLite-Emulation, das den
AFTER INSERT-Trigger vom `next_value`-Zustand entkoppelt — insbesondere
bei Zyklus-Resets, bei denen `next_value - increment_by` den falschen
Wert liefern wuerde.

Jede neutrale Sequence belegt genau eine Zeile in `dmg_sequences`.

`cache_size`-Behandlung und Roundtrip-Vertrag:

- `cache_size` wird aus dem neutralen Modell (`SequenceDefinition.cache`)
  **unveraendert** in die `dmg_sequences`-Zeile uebernommen und dort
  als reine Metadaten persistiert
- SQLite implementiert kein echtes Caching (Single-Writer, kein
  Multi-Connection-Cache); `cache_size` hat keinen Laufzeiteffekt
- beim Reverse liest der Reader `cache_size` aus `dmg_sequences`
  und rekonstruiert `SequenceDefinition.cache` daraus; dadurch ist
  der Roundtrip fuer `cache_size` **verlustfrei** auf Metadatenebene
- bei der Generierung wird `W114` emittiert wenn `cache_size != NULL`,
  um darauf hinzuweisen, dass der Wert gespeichert, aber nicht als
  echte Preallocation umgesetzt wird
- `cache_size = NULL` bedeutet "kein Caching konfiguriert" (nicht
  "Default-Caching des Dialekts")

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

Identifier-Escaping in Marker-Kommentaren:

- `<name>`, `<table>` und `<column>` im Marker sind immer die
  **neutrale Kanonform** des Identifiers (dieselbe Form, die im
  neutralen Schema und in den Trigger-Namen verwendet wird)
- die neutrale Kanonform enthaelt keine SQL-Sonderzeichen, keine
  Anführungszeichen und keine Whitespace-Zeichen; sie besteht aus
  `[a-zA-Z0-9_]` und ggf. UTF-8-Buchstaben
- falls ein Identifier Zeichen enthaelt, die in einem SQL-Kommentar
  problematisch waeren (`*`, `/`, `=`, Whitespace), werden diese
  im Marker **Percent-encoded** (RFC 3986):
  - `*` → `%2A`, `/` → `%2F`, `=` → `%3D`, ` ` → `%20`
  - `%` selbst → `%25`
- der Reverse-Parser dekodiert Percent-Encoding beim Lesen
- Begründung: SQL-Kommentare koennen durch `*/` vorzeitig beendet
  werden und durch `=` die Key-Value-Struktur brechen; Percent-
  Encoding ist eindeutig, umkehrbar und benoetigt keinen
  eigenen Escape-Parser
- Identifiers im SQL-Statement-Text (Tabellen-/Spaltennamen in
  UPDATE/WHERE) verwenden weiterhin das normale Double-Quote-Quoting
  des SQLite-Dialekts (`"identifier"`); das betrifft nur den
  Marker-Kommentar

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
-- Schritt 0: Existenzpruefung per EXISTS (deterministisch)
--   IF NOT EXISTS(dmg_sequences WHERE name = seq): RAISE(ABORT, 'not found')
--
-- Schritt 1: Erschoepfungspruefung ueber exhausted-Flag
--   IF exhausted = 1: RAISE(ABORT, 'exhausted')
--
-- Schritt 2: Rueckgabewert sichern + inkrementieren (atomar)
--   last_returned_value := next_value
--
--   Grenzvorausschau (sign-spezifisch, overflow-sicher):
--     Fuer increment_by > 0 (aufsteigende Sequenz):
--       Pruefe: next_value > max_value - increment_by
--       (aequivalent zu next_value + increment_by > max_value,
--        aber ohne die Addition auszufuehren)
--     Fuer increment_by < 0 (absteigende Sequenz):
--       Pruefe: next_value < min_value - increment_by
--       (da increment_by negativ: min_value - increment_by = min_value + |inc|;
--        aequivalent zu next_value + increment_by < min_value)
--
--   IF Grenze wuerde ueberschritten:
--     IF cycle_enabled = 1: next_value := Zyklusstartwert
--     IF cycle_enabled = 0: next_value bleibt, exhausted := 1
--   ELSE:
--     next_value := next_value + increment_by
--     (overflow-sicher: ELSE nur erreichbar wenn innerhalb der Grenze)
--
-- AFTER INSERT (_ai):
--
-- Schritt 3: last_returned_value in die eingefuegte Zeile schreiben
--   UPDATE table SET column = last_returned_value WHERE ROWID = NEW.ROWID
```

Hinweis zur Triggerlogik:

- **Erschoepfung ueber `exhausted`-Flag**: Schritt 1 prueft nur
  `exhausted = 1` — kein numerischer Vergleich, kein Overflow-Risiko.
  Kein numerisches Sentinel (`max_value + 1`) noetig, das den
  64-bit INTEGER-Bereich ueberschreiten koennte.
- **Overflow-sichere Vorausschau** (sign-spezifisch): Schritt 2
  prueft per Subtraktionsformel, ob das naechste Inkrement die
  Grenze ueberschreiten wuerde:
  - aufsteigend (`inc > 0`): `next_value > max_value - increment_by`
  - absteigend (`inc < 0`): `next_value < min_value - increment_by`
    (da `inc` negativ: `min_value - inc = min_value + |inc|`)
  Bei `cycle=1`: Zyklusstartwert. Bei `cycle=0`: `next_value` bleibt,
  `exhausted` wird auf 1 gesetzt.
- Die Subtraktionen sind durch die Validator-Regel
  `|increment_by| <= max_value - min_value` (§3.6) vor Overflow
  geschuetzt.
- Dadurch wird der letzte gueltige Wert (z. B. `next_value = 10`
  bei `max_value = 10`) korrekt zurueckgegeben, bevor die Sequence
  beim naechsten Aufruf per Flag als erschoepft erkannt wird.
- `last_returned_value` wird im selben UPDATE wie das Inkrement
  gesichert; der AFTER INSERT-Trigger liest daraus statt zu rechnen

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
  (siehe §3.5)

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
    -- Schritt 0: Existenzpruefung — Sequence-Zeile muss vorhanden sein.
    -- Deterministischer EXISTS-Check, unabhaengig von changes()-Semantik.
    SELECT RAISE(ABORT, 'dmg_sequences: sequence row order_number_seq not found')
        WHERE NOT EXISTS (
            SELECT 1 FROM "dmg_sequences" WHERE "name" = 'order_number_seq'
        );
    -- Schritt 1: Erschoepfungspruefung ueber das exhausted-Flag.
    -- Kein numerischer Vergleich, kein Overflow-Risiko.
    SELECT RAISE(ABORT, 'dmg_sequences: sequence order_number_seq exhausted')
        WHERE (SELECT "exhausted" FROM "dmg_sequences"
               WHERE "name" = 'order_number_seq') = 1;
    -- Schritt 2: Rueckgabewert sichern + next_value inkrementieren (atomar).
    -- Die CASE-Bedingungen verwenden die overflow-sichere
    -- Subtraktionsformel (next_value > max_value - increment_by)
    -- fuer die Grenzvorausschau. Drei Pfade:
    -- (a) cycle=1, Grenze erreicht: Zyklusstartwert
    -- (b) cycle=0, Grenze erreicht: next_value bleibt, exhausted=1
    -- (c) ELSE: normales Inkrement (overflow-sicher: nur erreichbar
    --     wenn next_value innerhalb der Subtraktionsgrenze)
    UPDATE "dmg_sequences"
        SET "last_returned_value" = "next_value",
            "next_value" = CASE
                -- (a) Zyklus-Reset positiv
                WHEN "increment_by" > 0
                     AND "next_value"
                         > COALESCE("max_value", 9223372036854775807)
                           - "increment_by"
                     AND "cycle_enabled" = 1
                THEN COALESCE("min_value", 1)
                -- (a) Zyklus-Reset negativ
                WHEN "increment_by" < 0
                     AND "next_value"
                         < COALESCE("min_value", -9223372036854775808)
                           - "increment_by"
                     AND "cycle_enabled" = 1
                THEN COALESCE("max_value", 9223372036854775807)
                -- (b) Nicht-Zyklus, Grenze erreicht: next_value bleibt
                --     (exhausted-Flag wird separat gesetzt, s.u.)
                WHEN "increment_by" > 0
                     AND "next_value"
                         > COALESCE("max_value", 9223372036854775807)
                           - "increment_by"
                     AND "cycle_enabled" = 0
                THEN "next_value"
                WHEN "increment_by" < 0
                     AND "next_value"
                         < COALESCE("min_value", -9223372036854775808)
                           - "increment_by"
                     AND "cycle_enabled" = 0
                THEN "next_value"
                -- (c) Normales Inkrement (overflow-sicher: nur erreichbar
                --     wenn next_value innerhalb der Subtraktionsgrenze)
                ELSE "next_value" + "increment_by"
            END,
            -- exhausted-Flag setzen wenn cycle=0 und Grenze erreicht
            "exhausted" = CASE
                WHEN "cycle_enabled" = 0
                     AND (
                         ("increment_by" > 0
                          AND "next_value"
                              > COALESCE("max_value", 9223372036854775807)
                                - "increment_by")
                         OR
                         ("increment_by" < 0
                          AND "next_value"
                              < COALESCE("min_value", -9223372036854775808)
                                - "increment_by")
                     )
                THEN 1
                ELSE "exhausted"
            END
        WHERE "name" = 'order_number_seq';
    -- (Existenzpruefung bereits in Schritt 0 per EXISTS erledigt;
    --  kein changes()-Check mehr noetig)
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

**UPDATE-Trigger-Kaskade durch AFTER INSERT und `recursive_triggers`**

Der `_ai`-Trigger fuehrt ein `UPDATE` auf die Zieltabelle aus, um
den Sequence-Wert in die eingefuegte Zeile zu schreiben. Ob dieses
UPDATE bestehende BEFORE/AFTER UPDATE-Trigger auf derselben Tabelle
ausloest, haengt von `PRAGMA recursive_triggers` ab:

- **`recursive_triggers = OFF`** (SQLite-Default): Trigger, die
  innerhalb eines anderen Triggers auf dieselbe Tabelle feuern,
  werden **nicht** ausgeloest. Das `_ai`-UPDATE loest keine
  UPDATE-Trigger aus. Das ist der sicherere Modus.
- **`recursive_triggers = ON`**: Das `_ai`-UPDATE loest bestehende
  BEFORE/AFTER UPDATE-Trigger auf derselben Tabelle aus. Das kann
  gewuenscht sein (z. B. `updated_at`-Trigger) oder unerwuenscht
  (z. B. Audit-Trigger protokolliert die Sequence-Zuweisung als
  Datenaenderung).

Deterministische vs. rekursionsabhaengige Trigger-Pfade:

Die Trigger-Ausfuehrung im `helper_table`-Modus hat zwei Ebenen:

1. **INSERT-Ebene (deterministisch)**: Die Reihenfolge der BEFORE
   INSERT (`_bi`) und AFTER INSERT (`_ai`) Trigger relativ zu
   nutzerdefinierten INSERT-Triggern ist durch die DDL-
   Erzeugungsreihenfolge festgelegt (Position 5 vs. 8). Dieses
   Verhalten ist **unabhaengig** von `recursive_triggers`.

2. **UPDATE-Nebenweg (rekursionsabhaengig)**: Ob das `_ai`-UPDATE
   weitere UPDATE-Trigger ausloest, haengt von `recursive_triggers`
   ab. Dieser Pfad ist **nicht** deterministisch steuerbar durch
   die DDL-Erzeugungsreihenfolge allein.

Vertrag:

- der `helper_table`-Modus setzt **kein** bestimmtes
  `recursive_triggers`-Setting voraus
- die Sequence-Emulation selbst funktioniert mit beiden Settings
  korrekt (der `_ai`-Trigger schreibt den Wert unabhaengig davon)
- `W122` wird emittiert wenn die Zieltabelle nutzerdefinierte
  UPDATE-Trigger hat UND sequence-basierte Spalten im
  `helper_table`-Modus verwendet

W122-Schweregrad mit statischer Analyse:

Der Generator fuehrt eine **statische Pruefung** der UPDATE-Trigger-
Bodys auf der Zieltabelle durch:

- **kein UPDATE-Trigger vorhanden**: kein W122
- **UPDATE-Trigger vorhanden, Body referenziert weder `dmg_sequences`
  noch die sequence-basierte Spalte**: W122 als `NoteType.INFO`
  (harmloser Beobachtungsunterschied; z. B. `updated_at`-Trigger)
- **UPDATE-Trigger vorhanden, Body referenziert `dmg_sequences`
  ODER die sequence-basierte Spalte**: W122 als `NoteType.WARNING`
  (potenziell gefaehrlich bei `recursive_triggers = ON`;
  Endlosrekursion oder Datenkorruption moeglich)

Die Body-Referenzpruefung verwendet dasselbe Token-basierte Matching
wie die Rollback-Abhaengigkeitspruefung (case-insensitive, String-
Literale/Kommentare vorab entfernt).

Report-Text unterscheidet explizit:

- **`recursive_triggers = OFF`** (Default): **nur Beobachtungs-
  unterschied**. UPDATE-Trigger feuern nicht; W122 ist rein
  informativ.
- **`recursive_triggers = ON`**: bei INFO-Stufe ungefaehrlich;
  bei WARNING-Stufe **Pflichtpruefung** durch den Nutzer,
  da Rekursion/Korruption moeglich ist.

- `W122` (INFO): AFTER INSERT sequence trigger performs UPDATE on
  the same table; with recursive_triggers=OFF (default) this has
  no observable effect; with recursive_triggers=ON, existing UPDATE
  triggers will fire — verify compatibility

Integrationstests muessen diesen Fall fuer **beide** Pragma-Settings
abdecken (§8.2).

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

**Weitere Constraint-Einschraenkungen bei Zwei-Trigger-Strategie**

Da die Zeile zwischen INSERT und `_ai`-UPDATE den Wert `NULL` in der
sequence-basierten Spalte traegt, koennen auch andere Constraints
als `NOT NULL` mit dem Zwischenzustand kollidieren:

- **CHECK-Constraints** auf der Spalte (z. B. `CHECK(order_number > 0)`)
  werden beim INSERT ausgewertet, wenn die Spalte noch `NULL` ist;
  `NULL` in einem CHECK-Ausdruck evaluiert zu `UNKNOWN`, was in
  SQLite als "nicht verletzt" gilt — daher ist das in der Regel
  kein Problem
- **CHECK-Constraints, die NULL explizit ablehnen**
  (z. B. `CHECK(order_number IS NOT NULL)`) scheitern beim INSERT
  vor dem `_ai`-UPDATE — identisches Problem wie bei `NOT NULL`
- **UNIQUE-Constraints** auf der Spalte: mehrere NULL-Werte sind in
  SQLite bei UNIQUE erlaubt (NULL != NULL), daher kein Problem beim
  INSERT; der `_ai`-UPDATE setzt den endgueltigen Wert
- **FOREIGN KEY-Constraints** auf der Spalte: FK-Pruefung erfolgt
  in SQLite standardmaessig am Statement-Ende (nach allen Triggern),
  nicht zwischen den Triggern; daher kein Problem

Validierungsregel:

- der Generator prueft vor der DDL-Erzeugung, ob CHECK-Constraints
  auf sequence-basierten Spalten `NULL` explizit ablehnen (z. B.
  `IS NOT NULL`-Bedingung im CHECK); wenn ja, wird der CHECK analog
  zu NOT NULL unterdrueckt und als lossy Mapping gemeldet
- CHECK-Constraints, die `NULL` implizit tolerieren (Standard in
  SQLite), werden normal emittiert — sie greifen erst nach dem
  `_ai`-UPDATE, wenn ein nutzerdefinierter Trigger oder eine
  spätere Aenderung den Wert veraendert

**PRIMARY KEY-Einschraenkung bei Zwei-Trigger-Strategie**

SQLite erzwingt `NOT NULL` implizit auf `PRIMARY KEY`-Spalten (ausser
bei `INTEGER PRIMARY KEY`, das als Alias fuer `ROWID` eine Sonder-
stellung hat). Die W119-NOT NULL-Unterdrueckung kann diese implizite
Constraint nicht aufheben — ein INSERT mit `NULL` in einer PK-Spalte
scheitert unabhaengig von einem expliziten `NOT NULL`.

**Entscheidung:** Sequence-basierte Spalten duerfen **nicht** Teil
eines `PRIMARY KEY` sein, wenn der `helper_table`-Modus aktiv ist.

Verhalten:

- wenn eine Spalte `DefaultValue.SequenceNextVal(...)` traegt **und**
  im `PRIMARY KEY` der Tabelle enthalten ist, erzeugt der Generator
  einen Validierungsfehler mit Code `E059`
- die Pruefung erfolgt vor der DDL-Generierung im Validator, nicht
  erst im dialektspezifischen Generator
- `E059`: Sequence-backed column cannot be part of PRIMARY KEY in
  SQLite helper-table mode; use INTEGER PRIMARY KEY AUTOINCREMENT
  or application-level sequencing
- Hinweis: `INTEGER PRIMARY KEY` (ohne AUTOINCREMENT) in SQLite ist
  ein ROWID-Alias und akzeptiert NULL-INSERTs (SQLite vergibt dann
  automatisch eine ROWID); dieses Verhalten ist jedoch ein SQLite-
  spezifischer Sonderfall und wird hier nicht als Sequence-Ersatz
  modelliert

### 3.5 `WITHOUT ROWID`-Tabellen

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
- `E057` wird als `TransformationNote` mit `NoteType.ACTION_REQUIRED`
  an die betroffene Spalte gehaengt **und** die Spalte wird in die
  `skippedObjects`-Liste des Reports aufgenommen (Typ
  `sequence_column`, Name `<table>.<column>`)
- die Tabelle selbst wird **nicht** blockiert — nur die Spalten-
  Zuordnung fehlt
- **Konsequenz bei `required: true`**: die Spalte hat `NOT NULL` im
  generierten DDL, aber keinen Trigger und keinen Default; ein
  INSERT ohne expliziten Wert fuer diese Spalte scheitert zur
  Laufzeit
- **UX-Strategie**: E057 erscheint im Report-Output auf drei Ebenen:
  1. als `ACTION_REQUIRED`-Note auf stderr (wie andere E0xx-Codes)
  2. als Eintrag in `skippedObjects` (sichtbar im JSON-Report)
  3. im DDL als Kommentar vor der Tabelle:
     `-- ⚠ E057: Column <col> requires application-level sequencing (WITHOUT ROWID)`
- der Hint weist explizit darauf hin:
  "Use action_required mode or application-level sequencing."
  Damit ist unmissverstaendlich, dass das generierte Schema
  ohne manuellen Eingriff nicht vollstaendig nutzbar ist

Verhalten bei Reverse:

- `WITHOUT ROWID`-Tabellen haben keine implizite `ROWID`; der Reader
  erwartet dort keine Sequence-Support-Trigger
- die `dmg_sequences`-Zeile wird weiterhin als `SequenceDefinition`
  erkannt (sie ist tabellenunabhaengig)
- wenn ein manuelles Trigger-Setup auf einer `WITHOUT ROWID`-Tabelle
  gefunden wird, das `dmg_sequences` referenziert, aber keinen
  kanonischen Marker traegt: der Trigger wird als normaler
  nutzerdefinierter Trigger ins neutrale Schema uebernommen; er wird
  **nicht** als Sequence-Supportobjekt interpretiert, auch wenn er
  funktional eine Sequence-Emulation implementiert
- wenn ein Trigger mit passendem Marker auf einer
  `WITHOUT ROWID`-Tabelle gefunden wird, wird er als degradiert
  markiert (Reverse-Warning `W116`), da die ROWID-basierte
  Emulation auf dieser Tabelle nicht funktionieren kann

Roundtrip-Verhalten bei `WITHOUT ROWID` + `SequenceNextVal`:

- bei der Vorwaertsgenerierung (neutral -> SQLite) erzeugt E057
  einen `action_required`-Pfad fuer die betroffene Spalte; die
  `SequenceDefinition` wird in `dmg_sequences` erzeugt, aber kein
  Trigger-Paar
- beim Reverse (SQLite -> neutral) erkennt der Reader die
  `dmg_sequences`-Zeile als `SequenceDefinition`, findet aber kein
  Trigger-Paar auf der `WITHOUT ROWID`-Tabelle
- der Reader rekonstruiert die `SequenceDefinition`, kann aber die
  Spaltenzuordnung (`DefaultValue.SequenceNextVal`) **nicht**
  herstellen, weil kein Trigger existiert
- der Reverse-Report emittiert `E057` auf Spaltenebene als
  **Transformationshinweis**, damit der Nutzer nachvollziehen kann,
  warum die Spaltenzuordnung fehlt
- im Compare zwischen Original- und Reverse-Schema erscheint die
  Sequence als vorhanden, aber die Spaltenzuordnung als fehlend —
  das ist **erwartetes Verhalten**, kein Bug; der Roundtrip ist
  fuer diesen spezifischen Pfad bewusst nicht verlustfrei

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

### 3.6 Sequenzsemantik

Die Semantik ist identisch zum MySQL-Plan (§3.5) und wird hier nicht
wiederholt. Die verbindlichen Regeln fuer `next_value`, `increment_by`,
`min_value`, `max_value` und `cycle` gelten dialektuebergreifend.

Zusaetzliche SQLite-spezifische Punkte:

- `increment_by = 0` ist ungueltig und wird als **harter
  Validierungsfehler** im neutralen Modell abgefangen, **bevor** der
  Generator laeuft (identisch zu MySQL). Der Fehler wird ueber
  `SchemaValidator` gemeldet (Exit-Code 3), nicht erst im
  dialektspezifischen Generator. Damit ist ausgeschlossen, dass ein
  Trigger mit `increment_by = 0` erzeugt wird, der permanent
  denselben Wert zurueckgibt oder in eine Endlosschleife gerät
- SQLite speichert alle Ganzzahlen als `INTEGER` (bis zu 8 Bytes,
  -9223372036854775808 bis 9223372036854775807); die effektiven
  Grenzwerte entsprechen `Long.MIN_VALUE`/`Long.MAX_VALUE` in Kotlin
- der Fehlerpfad fuer erschoepfte Sequences nutzt `RAISE(ABORT, ...)`
  in SQLite, nicht einen SQL-Routine-Fehler wie in MySQL

Overflow-Schutz fuer Subtraktionsformel:

Die Grenzvorausschau im Trigger verwendet Subtraktionen der Form
`max_value - increment_by` (positiv) bzw. `min_value - increment_by`
(negativ). Diese Subtraktionen koennen bei extremen Werten von
`increment_by` selbst ueberlaufen:

- negativ: `min_value - (-|inc|) = min_value + |inc|`; bei
  `min_value = 0` und `|inc| = MAX_INT` ist das Ergebnis `MAX_INT`
  (kein Overflow); aber bei `min_value = 1` und `|inc| = MAX_INT`
  waere das Ergebnis `MAX_INT + 1` → **Overflow**
- positiv: `max_value - inc`; bei `max_value` nahe `MIN_INT` und
  grossem `inc` kann das Ergebnis unter `MIN_INT` fallen → Overflow

Der `SchemaValidator` prueft deshalb zusaetzlich:

- `|increment_by|` darf nicht groesser sein als der Abstand zwischen
  `min_value` und `max_value`

Implementierung der Pruefung (overflow-sicher in Kotlin):

Die naive Berechnung `max_value - min_value` ist in `Long`-Arithmetik
**nicht** sicher: bei Standardgrenzen (`min = Long.MIN_VALUE`,
`max = Long.MAX_VALUE`) waere das Ergebnis `Long.MAX_VALUE -
Long.MIN_VALUE = -1` (Wraparound). Der Validator verwendet deshalb
eine **overflow-sichere Vergleichslogik ohne Subtraktion**:

```kotlin
// Overflow-sichere Pruefung: |increment_by| <= max_value - min_value
// Umformuliert als zwei separate Vergleiche ohne Subtraktion:
fun isIncrementInRange(inc: Long, min: Long, max: Long): Boolean {
    // Trivialfall: inc = 0 wird separat als Fehler behandelt
    if (inc > 0) {
        // Pruefe: inc <= max - min, umgeschrieben als: min <= max - inc
        // max - inc ist sicher wenn inc > 0 und max >= min (immer wahr)
        // Aber max - inc kann underflow'en wenn inc > max - Long.MIN_VALUE
        // Sichere Variante: inc <= max && min <= max - inc
        //   (erster Check verhindert, dass max - inc negativ underflow't
        //    fuer den Fall max < inc)
        return inc <= max && min <= max - inc
    } else { // inc < 0
        // Pruefe: -inc <= max - min, d.h. |inc| <= max - min
        // Umgeschrieben: min - inc <= max (da inc < 0: min - inc = min + |inc|)
        // Sichere Variante: inc >= min && max >= min - inc
        return inc >= min && max >= min - inc
    }
}
```

- bei `min_value = NULL` und/oder `max_value = NULL` werden die
  Defaultwerte `Long.MIN_VALUE`/`Long.MAX_VALUE` eingesetzt; die
  Pruefung `isIncrementInRange` ist auch fuer diese Extremwerte
  korrekt (keine Subtraktion von `max - min`)
- bei Verletzung: Validierungsfehler (Exit-Code 3) mit Hinweis,
  dass `|increment_by|` den Sequence-Bereich nicht ueberschreiten darf

Beweis der Subtraktionssicherheit in den Triggern:

Wenn die Validator-Regel erfuellt ist (`|inc| <= max - min`), gilt:
- fuer `inc > 0`: `max - inc >= max - (max - min) = min >= MIN_INT`
  → keine Underflow-Gefahr in `max_value - increment_by`
- fuer `inc < 0`: `min - inc = min + |inc| <= min + (max - min) = max <= MAX_INT`
  → keine Overflow-Gefahr in `min_value - increment_by`

### 3.7 Concurrency-Vertrag

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
  `WITHOUT ROWID`-Tabellen erhalten `E057` statt Trigger (§3.5)
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
--
--   SQLite-Trigger-Verhalten bei ON CONFLICT (dokumentiert in
--   SQLite-Doku "CREATE TRIGGER", Abschnitt "ON CONFLICT"):
--
--   a) INSERT ... ON CONFLICT DO UPDATE:
--      - BEFORE INSERT feuert → Sequence-Wert wird reserviert
--      - Constraint-Pruefung erkennt Conflict
--      - INSERT wird in ein UPDATE umgewandelt (DO UPDATE-Pfad)
--      - AFTER INSERT feuert NICHT (kein INSERT fand statt)
--      - BEFORE UPDATE / AFTER UPDATE feuern stattdessen
--      - Konsequenz: Sequence-Wert ist verbraucht (Gap), die Zeile
--        hat den alten order_number-Wert (vom UPDATE, nicht INSERT)
--
--   b) INSERT ... ON CONFLICT DO NOTHING:
--      - BEFORE INSERT feuert → Sequence-Wert wird reserviert
--      - Constraint-Pruefung erkennt Conflict
--      - INSERT wird verworfen (NOTHING-Pfad)
--      - AFTER INSERT feuert NICHT
--      - Konsequenz: Sequence-Wert ist verbraucht (Gap), keine
--        Zeile wurde eingefuegt oder geaendert
--
--   c) INSERT ... ON CONFLICT ABORT/ROLLBACK/FAIL:
--      - BEFORE INSERT feuert → Sequence-Wert wird reserviert
--      - Constraint-Pruefung erkennt Conflict
--
--      ABORT (Default):
--        Statement wird abgebrochen und alle Aenderungen des
--        aktuellen Statements (inklusive Trigger-Seiteneffekte
--        wie das dmg_sequences-UPDATE) werden zurueckgerollt.
--        Die umgebende Transaktion bleibt intakt.
--        → kein Gap (Sequence-Inkrement wird zurueckgerollt)
--
--      FAIL:
--        Statement wird abgebrochen, aber bereits ausgefuehrte
--        Aenderungen innerhalb desselben Statements bleiben
--        bestehen. Bei einem einzelnen INSERT ist das Verhalten
--        identisch zu ABORT. Bei einem Multi-Row INSERT (z. B.
--        INSERT INTO ... SELECT) bleiben die Trigger-Seiteneffekte
--        der bereits verarbeiteten Zeilen bestehen → Gap moeglich
--        fuer die fehlgeschlagene Zeile.
--
--      ROLLBACK:
--        Gesamte Transaktion wird zurueckgerollt, inklusive aller
--        Trigger-Seiteneffekte → kein Gap
--
--   d) INSERT OR REPLACE:
--      - BEFORE INSERT feuert → Sequence-Wert wird reserviert
--      - Constraint-Pruefung erkennt Conflict
--      - bestehende Zeile wird geloescht (DELETE-Trigger feuern),
--        dann neue Zeile eingefuegt (AFTER INSERT feuert)
--      - Konsequenz: Sequence-Wert wird korrekt gesetzt, ABER die
--        alte Zeile geht verloren; kein Gap, aber Datenverlust
--        wenn die alte Zeile gewollt war
--
--   e) INSERT OR IGNORE:
--      - BEFORE INSERT feuert → Sequence-Wert wird reserviert
--      - Constraint-Pruefung erkennt Conflict
--      - INSERT wird still verworfen
--      - AFTER INSERT feuert NICHT
--      - Konsequenz: Sequence-Wert ist verbraucht (Gap),
--        identisch zu ON CONFLICT DO NOTHING
--
INSERT INTO "orders" ("order_number", "customer_id") VALUES (NULL, 42)
    ON CONFLICT ("customer_id") DO UPDATE SET "updated_at" = datetime('now');
-- Ergebnis bei Conflict: kein neuer order_number, aber Sequence-Gap ⚠
-- Ergebnis ohne Conflict: order_number = naechster Sequence-Wert ✓
```

Diese Faelle muessen in der Integrations-Teststrategie (§8.2)
abgedeckt werden. Alle fuenf Konflikt-Varianten (DO UPDATE,
DO NOTHING, ABORT/ROLLBACK/FAIL, OR REPLACE, OR IGNORE) muessen
jeweils separat getestet werden, da das Trigger-Verhalten sich
zwischen den Pfaden unterscheidet.

Fall 5 (Konflikt-Gap) wird ueber `W121` als eigenes Warning-Signal
gemeldet:

- `W121`: Sequence values may be consumed without insertion when
  conflict-handling INSERT forms are used (ON CONFLICT DO UPDATE/
  DO NOTHING, INSERT OR IGNORE, INSERT OR FAIL with multi-row);
  ABORT and ROLLBACK roll back trigger side effects and do NOT
  produce gaps
- `W121` wird als `NoteType.INFO` emittiert und an die **konkrete
  Tabelle und Spalte** gehaengt, auf der die Sequence-Trigger
  generiert wurden — nicht pauschal an die Sequence-Definition
- der Report-Eintrag nennt explizit: Tabelle, Spalte, Sequence-Name
  und die betroffenen Conflict-Formen
- dadurch kann der Nutzer im Report gezielt die relevanten Tabellen
  identifizieren, statt eine globale Note interpretieren zu muessen
- im Compare-Report macht `W121` sichtbar, dass die Emulation
  bei bestimmten Conflict-Handling-Formen Gaps erzeugen kann

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

Interaktion mit nutzerdefinierten AFTER INSERT-Triggern:

Dieselbe Reihenfolgelogik gilt fuer AFTER INSERT-Trigger: die
generierten `_ai`-Trigger stehen in Position 5, nutzerdefinierte
AFTER INSERT-Trigger in Position 8. Damit feuert der `_ai`-Trigger
(der den Sequence-Wert per UPDATE in die Zeile schreibt) **vor**
nutzerdefinierten AFTER INSERT-Triggern.

- nutzerdefinierte AFTER INSERT-Trigger sehen die Zeile **mit** dem
  gesetzten Sequence-Wert (weil der `_ai`-Trigger bereits lief)
- dies ist das gewuenschte Verhalten: der Sequence-Wert ist
  verfuegbar, bevor nutzerdefinierte Logik auf die Zeile zugreift
- bei Reverse einer bestehenden Datenbank gilt dieselbe
  Einschraenkung wie bei BEFORE INSERT: die Erzeugungsreihenfolge
  wird nicht garantiert

### 5.2 Rollback

Rollback muss die kanonischen Hilfsobjekte wieder entfernen:

- generierte Sequence-Support-Trigger-Paare droppen (beide: `_bi` + `_ai`)
- `dmg_sequences` droppen

Vorgeschlagene Rollback-Reihenfolge:

Der Rollback ist ein **Preflight + Execute**-Pattern: alle Pruefungen
laufen vor dem ersten destruktiven Statement. Dadurch ist der
Rollback atomar im Sinne von "ganz oder gar nicht".

Preflight (keine Seiteneffekte):

1. Abhaengigkeitspruefung auf `dmg_sequences`: der Generator prueft,
   ob es Objekte gibt, die auf `dmg_sequences` verweisen und **nicht**
   zum kanonischen Sequence-Support gehoeren
2. Wenn fremde Abhaengigkeiten existieren: Rollback bricht **sofort**
   ab mit Fehlercode `E058` — kein Trigger wird gedroppt, kein
   Zustand wird veraendert

Execute (nur wenn Preflight bestanden):

3. Generierte Sequence-Support-Trigger (`DROP TRIGGER IF EXISTS` fuer
   jeden `_bi`- und `_ai`-Trigger)
4. `dmg_sequences` (`DROP TABLE IF EXISTS`)

Anders als bei MySQL gibt es keine Support-Routinen zum Droppen.

Abhaengigkeitspruefung im Detail:

- der Scan durchsucht **alle** Objekte in `sqlite_schema` (Typ
  `trigger`, `view`, `table`, `index`) nach Verweisen auf
  `dmg_sequences` im `sql`-Feld
- Objekte werden nur dann als managed (= Sequence-Support) ausgenommen,
  wenn sie **dieselbe strenge Erkennung** wie beim Reverse durchlaufen:
  primaer ueber Marker-Kommentar, sekundaer ueber das vollstaendige
  5-Kriterien-Matching (§6.1). Ein Trigger, der nur namentlich zum
  kanonischen Schema passt, aber weder Marker noch Sekundaer-Kriterien
  erfuellt, wird **nicht** als managed eingestuft und gilt als fremde
  Abhaengigkeit
- alle verbleibenden Treffer gelten als fremde Abhaengigkeiten
- typische Faelle: nutzerdefinierte Trigger, die `dmg_sequences`
  lesen/schreiben; Views, die `dmg_sequences` referenzieren;
  Indizes auf `dmg_sequences` (unwahrscheinlich, aber moeglich)
- in SQLite ist diese Pruefung statisch moeglich, weil alle
  DDL-Definitionen in `sqlite_schema.sql` als Text vorliegen

Scope der Abhaengigkeitspruefung:

- der Scan durchsucht **nur** das `sqlite_schema` der Haupt-
  Datenbank (`main`); Objekte in per `ATTACH` angebundenen
  Datenbanken oder in `temp`-Schemas werden **nicht** geprueft
- Begruendung: `dmg_sequences` wird im `main`-Schema erzeugt;
  Cross-Database-Trigger (die auf `main.dmg_sequences` zugreifen)
  sind in SQLite zwar syntaktisch moeglich, aber selten und
  fehleranfaellig; eine vollstaendige Pruefung aller ATTACHed DBs
  wuerde `PRAGMA database_list` + Iteration ueber alle Schemas
  erfordern und ist fuer Phase 1 unverhältnismaessig
- der Preflight prueft per `PRAGMA database_list`, ob neben `main`
  (und `temp`) weitere Datenbanken per ATTACH angebunden sind
- wenn ATTACHed Datenbanken erkannt werden, **blockiert** der
  Rollback standardmaessig mit `E060`: "Attached databases detected;
  rollback cannot guarantee dependency safety across schemas.
  Use --force-rollback to override."
- mit dem CLI-Flag `--force-rollback` kann der Nutzer den Rollback
  trotz ATTACHed DBs erzwingen; in diesem Fall wird `W123`
  (WARNING) statt `E060` emittiert
- Begruendung fuer die harte Blockierung: ein destructives
  `DROP TABLE dmg_sequences` bei unbekannten Cross-Database-
  Abhaengigkeiten kann Daten in angehängten Schemas korrumpieren;
  das ist schlimmer als ein blockierter Rollback
- diese Einschraenkung wird zusaetzlich in der Nutzerdokumentation
  als **Precondition** formuliert: "Vor dem Rollback im
  `helper_table`-Modus sicherstellen, dass keine per ATTACH
  angebundenen Datenbanken oder `temp`-Objekte auf `dmg_sequences`
  zugreifen."
- eine spaetere Ausbaustufe kann den Scan auf alle Schemas erweitern

Normalisierung und Matching-Strategie:

- die Suche ist **case-insensitive** und erkennt alle vier
  SQLite-Identifier-Quoting-Formen:
  - unquoted: `dmg_sequences`
  - double-quoted: `"dmg_sequences"`
  - bracket-quoted: `[dmg_sequences]`
  - backtick-quoted: `` `dmg_sequences` ``
  (SQLite akzeptiert alle vier Formen; siehe SQLite-Doku "SQL
  Language Expressions", Abschnitt "column-name")
- zusaetzlich wird schema-qualifizierter Zugriff erkannt, in
  allen Quoting-Kombinationen:
  - `main.dmg_sequences`, `main."dmg_sequences"`
  - `main.[dmg_sequences]`, `` main.`dmg_sequences` ``
  - `"main".dmg_sequences`, `"main"."dmg_sequences"` usw.
- das Matching verwendet eine **Token-basierte** Erkennung:
  `dmg_sequences` wird als eigenstaendiger Identifier gesucht,
  nicht als beliebiger Substring; dadurch werden False Positives
  durch Identifier wie `my_dmg_sequences_backup` vermieden
- **vor dem Matching** werden String-Literale (`'...'`) und
  SQL-Kommentare (`-- ...` und `/* ... */`) aus dem `sql`-Feld
  entfernt/maskiert, um False Positives durch `dmg_sequences` in
  Kommentartexten oder String-Konstanten zu vermeiden
- auf dem bereinigten Text erkennt der Scan `dmg_sequences` wenn es:
  - von einem Quoting-Zeichen umschlossen ist (`"`, `[`/`]`, `` ` ``), oder
  - an einer Wort-Grenze steht (vorhergehendes Zeichen ist `.`,
    Whitespace oder Zeilenanfang; nachfolgendes Zeichen ist
    Whitespace, `.`, `,`, `)`, `;` oder Zeilenende)
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
- Body-Integritaet bei Reverse:
  - `W120`: Sequence-support trigger has valid marker but modified
    body; emulation may not function correctly (§6.1)
- PRIMARY KEY-Einschraenkung:
  - `E059`: Sequence-backed column cannot be part of PRIMARY KEY in
    SQLite helper-table mode (§3.4)
- `WITHOUT ROWID`-Tabellen mit sequence-basierten Spalten:
  - `E057`: Sequence-backed column on WITHOUT ROWID table cannot use
    automatic trigger assignment (§3.5)
- UPSERT-Gap bei Conflict-Handling:
  - `W121`: Sequence values may be consumed without insertion when
    INSERT ... ON CONFLICT is used (§5.1)
- UPDATE-Trigger-Kaskade:
  - `W122` (INFO): AFTER INSERT sequence trigger may cascade into
    existing UPDATE triggers on the same table; nur relevant bei
    `recursive_triggers = ON` (§3.4)
- Rollback-Abhaengigkeitskonflikte:
  - `E058`: Cannot drop dmg_sequences: non-managed objects reference
    this table; remove external dependencies first (§5.2)
  - `E060`: Attached databases detected; rollback blocked (§5.2);
    ueberschreibbar mit `--force-rollback` (dann `W123`)
  - `W123` (WARNING): Rollback erzwungen trotz ATTACHed Datenbanken;
    Cross-Database-Abhaengigkeiten nicht geprueft (§5.2)

Diese Codes muessen vor Implementierung zentral dokumentiert werden.
`W114`, `W115`, `W116` und `W117` sind mit dem MySQL-Plan geteilt;
`W119`, `W120`, `W121`, `W122`, `W123`, `E057`, `E058`, `E059` und `E060` sind SQLite-spezifisch.

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
  Trigger-Body: der Parser sucht den Marker-String
  `d-migrate:sqlite-sequence-v1` **irgendwo** innerhalb eines
  `/* ... */`-Kommentars im `BEGIN...END`-Block (nicht nur im ersten
  Kommentar). Dadurch ist die Erkennung robust gegen:
  - Kommentar-Reihenfolge (Marker muss nicht der erste Kommentar sein)
  - zusaetzliche Praeambel-Kommentare vor dem Marker
  - Whitespace-/Zeilenumbruch-Unterschiede innerhalb des Kommentars
- innerhalb des gefundenen Marker-Kommentars werden die Key-Value-
  Paare (`object=`, `sequence=`, `table=`, `column=`) per
  Whitespace-tolerantem Parsing extrahiert
- der Marker enthaelt `d-migrate:sqlite-sequence-v1`, Objekttyp,
  Sequence-Name, Tabelle und Spalte — das ist die autoritaive Quelle
- **wenn der Marker-Kommentar fehlt oder nicht parsbar ist**, greift
  ein **deterministisches Sekundaer-Matching** mit folgenden
  Anforderungen (alle muessen zutreffen):
  1. **Trigger-Paar vorhanden**: sowohl der `_bi`- als auch der
     `_ai`-Trigger muessen existieren (nur ein einzelner Trigger
     reicht nicht — das reduziert False Positives erheblich)
  2. Triggernamen entsprechen dem kanonischen Schema
     (`dmg_seq_<...>_bi` und `dmg_seq_<...>_ai` mit identischem Hash)
  3. Trigger-Event und -Timing passen (BEFORE INSERT bzw. AFTER INSERT)
  4. WHEN-Klausel hat die Form `NEW.<column> IS NULL` (identische
     Spalte in beiden Triggern)
  5. **Token-basierte Body-Pruefung**: der Body wird **nicht** per
     einfachem Substring-Match geprueft, sondern mit derselben
     Token-basierten Erkennung wie die Rollback-Abhaengigkeitspruefung
     (§5.2): String-Literale und Kommentare werden vorab entfernt,
     Identifier werden case-insensitive und in allen Quoting-Formen
     (`"..."`, `[...]`, `` `...` ``) erkannt.
     Konkret:
     - `_bi`-Trigger-Body muss den Identifier `dmg_sequences` als
       Token enthalten (beliebige Quoting-Form, ggf.
       schema-qualifiziert)
     - `_ai`-Trigger-Body muss sowohl den Tabellennamen der
       Zieltabelle als auch `ROWID` als Tokens enthalten
  Nur wenn **alle fuenf** Kriterien zutreffen, wird das Trigger-Paar
  als **wahrscheinliches** Sequence-Supportobjekt behandelt und mit
  `W116` (degradiert) markiert; die Spaltenzuordnung wird aus den
  Triggernamen und der WHEN-Klausel rekonstruiert
- wenn nicht alle Kriterien zutreffen, werden die Trigger als
  normale nutzerdefinierte Trigger ins neutrale Schema uebernommen
- das Sekundaer-Matching ist bewusst **eng begrenzt**, aber
  Quoting-tolerant: Trigger-Paar-Anforderung + Token-basierte
  Body-Pruefung zusammen mit Name/Event/WHEN machen versehentliche
  Treffer auf nutzerdefinierte Trigger extrem unwahrscheinlich,
  waehrend alternativ formatierte kompatible Trigger (unquoted,
  schema-qualifiziert) korrekt erkannt werden

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
  ueber Marker greift, aber der Reader fuehrt zusaetzlich eine
  **Token-basierte Body-Integritaetspruefung** durch (identische
  Logik wie beim Sekundaer-Matching):
  - `_bi`-Trigger muss `dmg_sequences` als Identifier-Token enthalten
  - `_ai`-Trigger muss Tabellennamen + `ROWID` als Tokens enthalten
  Wenn der Marker passt, aber die Body-Pruefung fehlschlaegt, wird
  der Trigger als `W120` (body-modified) markiert: die Zuordnung
  zur Sequence bleibt bestehen (Marker ist autoritativ), aber der
  Trigger wird als **moeglicherweise nicht funktional** geflaggt.
  Reverse und Compare behandeln die Sequence-Zuordnung weiterhin,
  aber der Report weist explizit darauf hin, dass die Emulation
  veraendert wurde und moeglicherweise nicht korrekt arbeitet.

Dieses abgestufte Verhalten wird in der Nutzerdokumentation als
**Roundtrip-Risiko** dokumentiert:

> "Sequence-Support-Trigger sind generierte Infrastruktur. Das
> Entfernen des Marker-Kommentars fuehrt zu degradierter Erkennung
> (W116). Das Aendern des Trigger-Bodys fuehrt zu einer Body-Modified-
> Warnung (W120). Das Umbenennen des Triggers fuehrt zum Verlust der
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

Compare-Verhalten bei degradierten/modifizierten Triggern:

- `W116` (degradiert, Sekundaer-Matching): die Sequence-Zuordnung
  wird im Compare beruecksichtigt, aber der Compare-Report markiert
  die Emulation als **laufzeit-nicht-vertrauenswuerdig**; der
  Metadaten-Diff ist stabil, aber der Report weist explizit darauf
  hin, dass das tatsaechliche Laufzeitverhalten nicht verifiziert
  werden kann
- `W120` (body-modified): die Sequence-Zuordnung bleibt im Compare,
  aber der Report markiert die betroffene Sequence als
  **emulations-veraendert**; ein Compare zwischen Quell- und
  Zielschema zeigt keinen Metadaten-Diff, aber der Report enthaelt
  eine Warnung, dass die Emulation moeglicherweise nicht korrekt
  arbeitet
- in beiden Faellen gilt: der Compare ist **nicht** aequivalent
  zu einem Compare mit intakten Triggern; Nutzer muessen die
  Trigger-Integritaet vor einem vertrauenswuerdigen Roundtrip
  sicherstellen

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
  `E057`, `E058`, `E059`, `E060`, `W120`, `W121`, `W122`, `W123`)
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
  - `E059` bei PRIMARY KEY + `SequenceNextVal`-Spalten
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
- Tabelle mit bestehendem AFTER UPDATE-Trigger + sequence-basierter
  Spalte, `recursive_triggers = ON`: INSERT loest korrekte
  Sequence-Vergabe aus, der UPDATE-Trigger feuert durch die
  `_ai`-Kaskade (W122-Testfall)
- derselbe Test mit `recursive_triggers = OFF` (Default): UPDATE-
  Trigger feuert **nicht**; Sequence-Vergabe ist trotzdem korrekt
- INSERT OR REPLACE auf Tabelle mit Sequence-Spalte + Conflict:
  alte Zeile wird geloescht, neue mit Sequence-Wert eingefuegt
- INSERT OR IGNORE auf Tabelle mit Sequence-Spalte + Conflict:
  INSERT wird verworfen, Sequence-Wert ist verbraucht (Gap)

Pflicht-Tests fuer NOT NULL / W115 / W119 Randfaelle:

- **required: true + INSERT ohne Wert**: Spalte hat kein NOT NULL
  im DDL (W119); INSERT mit ausgelassenem Wert vergibt Sequence-Wert;
  die Zeile hat danach einen nicht-NULL Wert (de-facto NOT NULL)
- **required: true + explizites NULL**: INSERT mit `VALUES (NULL, ...)`
  vergibt Sequence-Wert (W115 lossy, identisch zu ausgelassenem Wert)
- **required: true + explizites NULL + FK auf Sequence-Spalte**:
  wenn eine andere Tabelle einen FK auf die sequence-basierte Spalte
  hat, muss der FK erst nach dem AFTER INSERT-UPDATE pruefbar sein;
  in SQLite werden FK-Constraints standardmaessig am Statement-Ende
  geprueft (nicht zwischen den Triggern), daher ist der FK nach dem
  `_ai`-UPDATE erfuellt
- **NOT NULL + explicit NULL + AFTER INSERT-Reihenfolge**: wenn ein
  nutzerdefinierter AFTER INSERT-Trigger die sequence-basierte Spalte
  liest, muss er den Wert sehen (weil `_ai` in Position 5, Nutzer
  in Position 8); verifizieren, dass die Reihenfolge korrekt ist

Pflicht-Tests fuer Reverse-Degradation (W116, W120):

- **Marker fehlt bei beiden Triggern**: Sekundaer-Matching greift
  (alle 5 Kriterien), Sequence wird mit `W116` rekonstruiert;
  Compare zeigt laufzeit-nicht-vertrauenswuerdigen Diff
- **Marker vorhanden, Body veraendert** (z. B. UPDATE dmg_sequences
  entfernt): primaeres Matching greift, Body-Pruefung schlaegt fehl,
  Trigger wird mit `W120` markiert; Sequence-Zuordnung bleibt, aber
  Report flaggt "body-modified"
- **Nur ein Trigger des Paares fehlt** (z. B. `_ai` manuell
  gedroppt): weder primaeres noch sekundaeres Matching ergibt ein
  vollstaendiges Paar; `_bi`-Trigger mit Marker wird als
  degradiertes Einzelobjekt mit `W116` behandelt, Spaltenzuordnung
  ist unvollstaendig
- **Trigger umbenannt** (Name passt nicht mehr zum Schema, Marker
  noch vorhanden): primaeres Matching greift weiterhin ueber Marker
  (Marker ist autoritativ, nicht der Name); Trigger wird als
  Supportobjekt erkannt, aber `W120` wenn Body-Pruefung zusaetzlich
  fehlschlaegt

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
abgelehnt (§3.5).

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
- **`WITHOUT ROWID`**: hard-fail mit `E057` (§3.5); PK-basierter
  `_ai`-Fallback ist **nicht** fuer Phase 1 geplant; die
  Einschraenkung bleibt bestehen, bis ein konkreter Bedarf belegt ist
- **App-Level (ex-Strategie B)**: nicht Teil von `helper_table`;
  nur als Doku-Pattern fuer `action_required`-Nutzer
- **`cache_size`**: reine Metadaten, verlustfrei persistiert und
  im Roundtrip stabil; kein Laufzeiteffekt in SQLite (W114)
- **Existenzpruefung**: deterministischer `EXISTS`-Precheck statt
  `changes()`-Semantik (robuster ueber SQLite-Versionen)
- **Overflow-Validierung**: Kotlin-Implementierung verwendet
  overflow-sichere Vergleichslogik ohne `Long`-Subtraktion (§3.6);
  kein `BigInteger` noetig
- **ATTACH-Rollback**: standardmaessig blockierend (`E060`) wenn
  ATTACHed DBs erkannt werden; ueberschreibbar mit `--force-rollback`
- **Sekundaer-Matching**: Token-basierte Body-Pruefung statt
  Substring-Match; erkennt alle Quoting-Formen

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
