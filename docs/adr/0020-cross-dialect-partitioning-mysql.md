---
status: proposed
date: 2026-06-24
decision-makers: pt9912
consulted: docs/planning/next/cross-dialect-partitioning.md (AP6-Slice), docs/adr/0019-partition-hierarchy-structured-representation.md, docs/adr/0015-fulltext-tsvector-neutral-type.md (Carve-Out-Präzedenz), spec/ledger.md
informed: adapters/driven/driver-mysql (Generate + Reverse), examples/sample-db (Pagila-Cross-Smoke pg2my)
---

# Cross-Dialect-Partitionierung PG↔MySQL: Mapping-Entscheide (AP6)

> **Status: proposed.** Gate-ADR für den Slice
> [`../planning/next/cross-dialect-partitioning.md`](../planning/next/cross-dialect-partitioning.md)
> (AP6). Muss **accepted** sein, bevor AP6.2/AP6.3 codiert werden. Aufbauend auf der
> Modellform aus [ADR 0019](0019-partition-hierarchy-structured-representation.md).

## Kontext und Problemstellung

Die PG-first-Scheibe hat das strukturierte `PartitionDefinition`-Modell + PG-Reverse-Capture
geliefert; PG `payment` round-trippt als echte RANGE-Partition. Der **Cross-Dialect**-Pfad ist
aber unvollständig — **datenbelegt** (`make sample-db-cross-smoke-pg2my`, 2026-06-24):

```sql
-- generierte MySQL-DDL (heute, INVALID):
PARTITION BY RANGE (`payment_date`) (
    PARTITION `payment_p2022_01` VALUES LESS THAN ('2022-02-01 00:00:00+00'), …)
```

Zwei harte Fehler + zwei strukturelle Lücken:

1. **`RANGE (datetime_col)` ist invalid** — MySQL-RANGE (nicht-COLUMNS) verlangt einen
   **Integer-Ausdruck**; eine DATETIME-Spalte direkt geht nicht.
2. **Literal `'…+00'`** trägt einen PG-timestamptz-**tz-Suffix**, den MySQL-DATETIME ablehnt.
3. **MySQL-Reverse ist partitions-blind** (kein Reader befüllt `partitions`).
4. **HASH** (`modulus`/`remainder`), **LIST-`DEFAULT`** und **kind-lokale Indizes** haben in
   MySQL keine 1:1-Form.

Diese ADR fixiert **alle** Mapping-Entscheide an einem Ort (Carve-Out = strukturierte
Entscheidung mit ADR-Präzedenz, nicht „entweder/oder im Code" — vgl. fulltext/geometry).

## Entscheidung

### 1. RANGE → `RANGE COLUMNS(key)` (statt `RANGE(key)` oder Funktions-Wrapper)

PG-RANGE bildet auf **`PARTITION BY RANGE COLUMNS(key)`** ab (nicht auf `RANGE(key)` und
**nicht** auf `RANGE(UNIX_TIMESTAMP(col))`/`TO_DAYS(col)`). Begründung: `RANGE COLUMNS`
akzeptiert **Spalten-Schlüssel der Typen INT/DATE/DATETIME/CHAR/VARCHAR** direkt mit
Literal-Grenzen, ohne Ausdrucks-Wrapping — es löst das Integer-Coercion-Problem für genau den
Trigger-Fall (`payment_date` DATETIME) auf. Der Wrapper-Weg wäre brüchiger (tz-/Typ-abhängig,
Determinismus-Auflagen). **Verlust bleibt:** MySQL-RANGE kennt nur `VALUES LESS THAN`
(Obergrenze, Kontiguität) → der PG-`from`-Anteil wird verworfen (wie heute, weiter per **W112**).

**Geltungsgrenze (kein invalides DDL):** `RANGE COLUMNS` schließt **DECIMAL/FLOAT** und
**Ausdrucks-Schlüssel** aus (MySQL erlaubt dort nur den Funktions-/Integer-Weg). PG-RANGE auf
einem solchen Schlüssel ist nicht verlustarm abbildbar → Partitionierung **übersprungen +
`action_required`-Note** (statt invalides DDL zu emittieren).

### 2. Temporal-Literal-Normalisierung (PG→MySQL) → neuer Note-Code W129

PG-timestamptz-Grenzen rendern mit tz-Suffix (`'2022-02-01 00:00:00+00'`); MySQL-DATETIME hat
keine Zeitzone. Der MySQL-Generate **normalisiert auf UTC** und entfernt dann den Suffix.

**Kritisch — UTC-Garantie, nicht bloßes Strippen:** reines Suffix-Abschneiden ist nur
instant-erhaltend, wenn die Grenze bei **UTC** gerendert wurde. Eine `+02`-Grenze, bloß
gestrippt, verschöbe die Partitionsgrenze um 2 h. Deshalb:
- Der **PG-Reverse rendert Partitions-Grenzen bei UTC** (Lese-Session `TimeZone = UTC`), sodass
  timestamptz-Literale immer `+00` tragen. Damit ist Strippen = UTC-erhaltend.
- Trägt eine Grenze dennoch einen Nicht-`+00`-Offset, wird sie **nach UTC konvertiert** (Offset
  verrechnet), **nicht** abgeschnitten; ist das nicht sicher möglich → `action_required`-Note.

Das ist eine **Transformation**, kein Verwurf — semantisch anderer Verlust als W112 →
**eigener Code `W129`** (Temporal-Grenze auf UTC normalisiert). Ledger-Eintrag in
[`../../spec/ledger.md`](../../spec/ledger.md) ergänzen; **W112 ist dort selbst ledger-rückständig**
(nur als Bereich `W100–W112` geführt) → bei der Gelegenheit nachziehen.

### 3. HASH → `PARTITIONS n` (benannte Kinder), modulus/remainder als bewusster Verlust

PG-HASH ist `modulus`/`remainder` **pro Kind**; MySQL-HASH ist anzahlbasiert
(`PARTITION BY HASH(key) (PARTITION …, …)` bzw. `PARTITIONS n`). N PG-Kinder (alle `modulus = N`)
→ MySQL behält die N **benannten** Kinder (Anzahl = Modulus; Namen bleiben erhalten); die
per-Kind-`remainder` entfällt (MySQL verteilt selbst) → Note. **Auflage:** MySQL-HASH verlangt
einen Integer-Schlüssel; ist der Schlüssel nicht integer-coercible, wird die HASH-Partitionierung
per Note **übersprungen** (kein stilles invalides DDL).

**Platzierung nicht erhalten (Note, kein Datenverlust):** PG- und MySQL-Hash-Funktionen sind
verschieden — Anzahl/Namen bleiben, aber **welche Zeile in welches Kind fällt, ändert sich**
(MySQL re-hasht beim Import). Das ist kein Datenverlust (Parent-Routing fängt es), aber eine
bewusste Abweichung → eigene Note neben dem `remainder`-Verlust.

### 4. LIST → `LIST COLUMNS(key)`; `DEFAULT` ist ein Transfer-Datenverlust (action_required)

**Form (Spiegel zu §1):** PG-LIST bildet auf **`PARTITION BY LIST COLUMNS(key)`** ab (nicht
plain `LIST(key)`). Plain `LIST` verlangt — wie plain `RANGE` — einen **Integer**; ist der
Schlüssel String/Datum (z. B. `region TEXT`), wäre `PARTITION BY LIST (region)` dieselbe invalide
DDL-Klasse, die §1 für RANGE behebt. `LIST COLUMNS` akzeptiert dieselben Spaltentypen wie §1
(INT/DATE/DATETIME/CHAR/VARCHAR) direkt; DECIMAL/FLOAT/Ausdruck → skip + `action_required` (wie §1).

**`DEFAULT` = Datenverlust, nicht nur DDL-Note:** MySQL-LIST kennt keinen Catch-all. Die
expliziten LIST-Kinder werden emittiert, die `DEFAULT`-Partition **verworfen**. Das ist
**datenwirksam**: Zeilen, die in PG in der DEFAULT-Partition lagen, haben in MySQL **keine
Ziel-Partition** und werden beim Re-Import abgelehnt. Daher **`action_required`-Note + Preflight-
Flag** (nicht eine weiche W-Note) — der Transfer muss den Verlust melden, bevor er Zeilen verliert.

### 5. Kind-lokale Partition-Indizes (AP6.3) → NICHT-unique heben, UNIQUE skip+Note

MySQL teilt Indizes über **alle** Partitionen (kein per-Partition-Index wie PG). Differenziert
nach Index-Klasse (heute verwirft `MysqlIndexPartitionDdlHelper` alle still):

- **Nicht-unique kind-lokale Indizes** → auf die **Tabellen-Indizes** heben (dedupliziert nach
  Spalten/Typ; Namens-Kollision → eindeutiger Name + Note). Partitionsübergreifend ist
  unproblematisch (reine Performance-Struktur).
- **UNIQUE/PK-artige kind-lokale Indizes** → **NICHT heben, skip + `action_required`-Note.** Zwei
  Gründe: (a) **MySQL-Regel** — jeder UNIQUE/PK einer partitionierten Tabelle **muss alle
  Partitionsschlüssel-Spalten enthalten** ([`../../spec/ddl-generation-rules.md`](../../spec/ddl-generation-rules.md),
  „PK enthält Partitionsschlüssel"; trägt auch die PG-Scheibe als allgemeingültig fort) — eine
  gehobene UNIQUE ohne Partitionsschlüssel ergäbe **invalides DDL** (genau die Fehlerklasse, die
  diese ADR eliminiert). (b) **Semantik** — PG-partition-**lokale** Eindeutigkeit ≠ globale
  Eindeutigkeit; eine global gehobene UNIQUE würde in PG gültige partitionsübergreifende Duplikate
  ablehnen. Beides macht blindes Heben falsch → skip + Note.

**Carve-Out FK auf partitionierter Tabelle (Allgemeinfall):** MySQL/InnoDB unterstützt **keine
Foreign Keys auf partitionierten Tabellen**. Ein PG-Parent mit FKs (am Parent deklariert,
propagiert) ist so nicht abbildbar → FK **skip + `action_required`-Note**. (Pagila `payment` ist
FK-frei → Trigger-Fall sicher; die Regel gilt für den Allgemeinfall.)

### 6. MySQL→PG-Richtung (AP6.5)

Spiegelbild: MySQLs kontiguierliche `VALUES LESS THAN` → PG-`from`/`to`-Paare rekonstruieren
(`fromₙ = toₙ₋₁`, erstes `from = MINVALUE`); `RANGE COLUMNS` → PG-RANGE; **`LIST COLUMNS` →
PG-LIST**; HASH `PARTITIONS n` → `modulus = n, remainder = 0..n-1` synthetisieren. Der tz-Verlust
aus (2) ist **nicht** invertierbar (MySQL hat die Zeitzone nie gespeichert) → als Annahme (UTC)
dokumentiert, nicht „raten".

## Konsequenzen

- **Generate wird gültig** für den Trigger-Fall (Pagila `payment` → `RANGE COLUMNS` + normalisierte
  Literale wenden in MySQL an). Cross-Smoke-Notes-Baseline neu pinnen (E055 entfällt, W112+W129 neu).
- **SQLite unverändert:** keine Partitionierung → `E055` (ganze Partitionierung verworfen).
- **Zwei Verlust-Klassen, sauber getrennt:**
  - **Weiche Verluste (W-Note, datenneutral):** `from` (W112), tz-UTC-Normalisierung (W129),
    HASH-`remainder` + HASH-Platzierung. Round-Trip bleibt datenkorrekt.
  - **Harte Verluste (`action_required` + Preflight, datenwirksam):** LIST-`DEFAULT` (Zeilen ohne
    Ziel-Partition), nicht-abbildbare Partitionierung (DECIMAL/FLOAT/Ausdruck-Schlüssel),
    UNIQUE-Heben (MySQL-Partitionsschlüssel-Regel + Semantik), FK auf partitionierter Tabelle.
    Diese **blockieren bzw. flaggen** statt still zu verlieren.
- **Kein stilles invalides DDL mehr** — jeder nicht abbildbare Fall ist skip+Note statt Emit.
- **Neutrales Modell bleibt sauber:** kein neuer Dialekt-Passthrough; die Entscheide leben im
  MySQL-Generate/-Reverse, nicht im Modell.
- **Note-/Ledger-Codes:** W112 (bestehend, nachzutragen), W129 (neu, tz-UTC); die
  `action_required`-Fälle erhalten E-Codes je Klasse — exakte Nummern + Ledger-Einträge beim
  Implementieren (AP6.2/AP6.3), in dieser ADR als Klasse festgelegt.

## Verworfene Alternativen

- **`RANGE(UNIX_TIMESTAMP(col))` / `RANGE(TO_DAYS(col))`:** funktioniert nur für bestimmte Typen
  (TIMESTAMP vs DATETIME), trägt Determinismus-/tz-Fallen und verzerrt die Grenzen-Literale —
  brüchiger als `RANGE COLUMNS`. Verworfen.
- **W112 für die tz-Normalisierung wiederverwenden:** vermischt Verwurf (`from`) und Transformation
  (tz) unter einem Code → diagnostisch unscharf. Eigener Code `W129`. Verworfen.
- **HASH per modulus/remainder in MySQL nachbilden:** MySQL modelliert das nicht — nicht abbildbar.
  Anzahlbasiert + Note. Verworfen.
