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
akzeptiert INT/DATE/DATETIME/CHAR-Spalten **direkt mit Literal-Grenzen**, ohne Ausdrucks-
Wrapping — es löst das Integer-Coercion-Problem für genau den Trigger-Fall (`payment_date`
DATETIME) auf und ist uniform für alle Schlüsseltypen. Der Wrapper-Weg wäre brüchiger
(tz-/Typ-abhängig, Determinismus-Auflagen). **Verlust bleibt:** MySQL-RANGE kennt nur
`VALUES LESS THAN` (Obergrenze, Kontiguität) → der PG-`from`-Anteil wird verworfen (wie heute,
weiter per **W112**).

### 2. Temporal-Literal-Normalisierung (PG→MySQL) → neuer Note-Code W129

PG-timestamptz-Grenzen rendern mit tz-Suffix (`'2022-02-01 00:00:00+00'`); MySQL-DATETIME hat
keine Zeitzone. Der MySQL-Generate **normalisiert** das Literal (tz-Suffix entfernen, auf
`'2022-02-01 00:00:00'`). Das ist eine **Transformation**, kein Verwurf — semantisch anderer
Verlust als W112 → **eigener Code `W129`** (Temporal-Grenze für MySQL normalisiert; UTC-Annahme).
Ledger-Eintrag in [`../../spec/ledger.md`](../../spec/ledger.md) ergänzen; **W112 ist dort selbst
ledger-rückständig** (nur als Bereich `W100–W112` geführt) → bei der Gelegenheit nachziehen.

### 3. HASH → `PARTITIONS n` (benannte Kinder), modulus/remainder als bewusster Verlust

PG-HASH ist `modulus`/`remainder` **pro Kind**; MySQL-HASH ist anzahlbasiert
(`PARTITION BY HASH(key) (PARTITION …, …)` bzw. `PARTITIONS n`). N PG-Kinder (alle `modulus = N`)
→ MySQL behält die N **benannten** Kinder (Anzahl = Modulus; Namen bleiben erhalten); die
per-Kind-`remainder` entfällt (MySQL verteilt selbst) → Note. **Auflage:** MySQL-HASH verlangt
einen Integer-Schlüssel; ist der Schlüssel nicht integer-coercible, wird die HASH-Partitionierung
per Note **übersprungen** (kein stilles invalides DDL).

### 4. LIST-`DEFAULT` → kein MySQL-Pendant → verwerfen + Note

MySQL-LIST kennt keinen Catch-all (`DEFAULT`). Die expliziten LIST-Kinder werden emittiert, die
`DEFAULT`-Partition **verworfen** und per Note gemeldet (Zeilen, die in keinen LIST-Wert fallen,
würde MySQL ohnehin ablehnen — bewusster Carve-Out).

### 5. Kind-lokale Partition-Indizes (AP6.3) → auf MySQL-Tabellenebene heben

MySQL teilt Indizes über **alle** Partitionen (kein per-Partition-Index wie PG). Die
kind-lokalen `partition.indices` werden auf die **Tabellen-Indizes** der MySQL-Tabelle gehoben
(dedupliziert nach Spalten/Typ/Unique; Namens-Kollision → eindeutiger Name + Note). So gehen sie
**nicht still** verloren (heute verwirft `MysqlIndexPartitionDdlHelper` sie).

### 6. MySQL→PG-Richtung (AP6.5)

Spiegelbild: MySQLs kontiguierliche `VALUES LESS THAN` → PG-`from`/`to`-Paare rekonstruieren
(`fromₙ = toₙ₋₁`, erstes `from = MINVALUE`); HASH `PARTITIONS n` → `modulus = n, remainder = 0..n-1`
synthetisieren; `RANGE COLUMNS` → PG-RANGE. Der tz-Verlust aus (2) ist **nicht** invertierbar
(MySQL hat die Zeitzone nie gespeichert) → als Annahme (UTC) dokumentiert, nicht „raten".

## Konsequenzen

- **Generate wird gültig** für den Trigger-Fall (Pagila `payment` → `RANGE COLUMNS` + normalisierte
  Literale wenden in MySQL an). Cross-Smoke-Notes-Baseline neu pinnen (E055 entfällt, W112+W129 neu).
- **SQLite unverändert:** keine Partitionierung → `E055` (ganze Partitionierung verworfen).
- **Bewusste Verluste**, alle per Note + hier begründet: `from` (W112), tz-Normalisierung (W129),
  HASH-`remainder`, LIST-`DEFAULT`. Kein stilles invalides DDL mehr.
- **Neutrales Modell bleibt sauber:** kein neuer Dialekt-Passthrough; die Entscheide leben im
  MySQL-Generate/-Reverse, nicht im Modell.

## Verworfene Alternativen

- **`RANGE(UNIX_TIMESTAMP(col))` / `RANGE(TO_DAYS(col))`:** funktioniert nur für bestimmte Typen
  (TIMESTAMP vs DATETIME), trägt Determinismus-/tz-Fallen und verzerrt die Grenzen-Literale —
  brüchiger als `RANGE COLUMNS`. Verworfen.
- **W112 für die tz-Normalisierung wiederverwenden:** vermischt Verwurf (`from`) und Transformation
  (tz) unter einem Code → diagnostisch unscharf. Eigener Code `W129`. Verworfen.
- **HASH per modulus/remainder in MySQL nachbilden:** MySQL modelliert das nicht — nicht abbildbar.
  Anzahlbasiert + Note. Verworfen.
