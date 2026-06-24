# Schema-YAML-Referenz

Kurzreferenz fuer das neutrale Schema-Format von d-migrate.
Vollstaendige Spezifikation: [neutral-model-spec.md](./neutral-model-spec.md)

---

## Dateistruktur

```yaml
# ── Metadaten (Pflicht) ───────────────────
schema_format: "1.0"          # Format-Version (immer "1.0")
name: "Mein Schema"           # Schema-Name
version: "1.0.0"              # Anwendungs-Schema-Version

# ── Metadaten (optional) ──────────────────
description: "Beschreibung"   # Freitext
encoding: "utf-8"             # Standard-Encoding
locale: "de_DE"               # Standard-Locale

# ── Objekte ───────────────────────────────
custom_types: {}               # Benutzerdefinierte Typen
tables: {}                     # Tabellen
procedures: {}                 # Stored Procedures
functions: {}                  # User-Defined Functions
views: {}                      # Views
triggers: {}                   # Trigger
sequences: {}                  # Sequenzen
```

---

## Typsystem

18 neutrale Typen, die pro Zieldatenbank automatisch uebersetzt werden:

| Typ | Attribute | Beschreibung |
|---|---|---|
| `identifier` | `auto_increment: true/false` | Auto-Increment Primaerschluessel |
| `text` | `max_length: <n>` | Variabler Text (VARCHAR/TEXT) |
| `char` | `length: <n>` **(Pflicht)** | Feste Laenge |
| `integer` | — | Ganzzahl |
| `smallint` | — | Kleine Ganzzahl |
| `biginteger` | — | Grosse Ganzzahl |
| `float` | `float_precision: single/double` | Gleitkommazahl |
| `decimal` | `precision: <p>`, `scale: <s>` **(Pflicht)** | Dezimalzahl |
| `boolean` | — | Wahrheitswert |
| `datetime` | `timezone: true/false` | Datum und Uhrzeit |
| `date` | — | Nur Datum |
| `time` | — | Nur Uhrzeit |
| `uuid` | — | Universelle ID |
| `json` | — | JSON-Daten |
| `xml` | — | XML-Daten |
| `binary` | — | Binaerdaten (BLOB/BYTEA) |
| `email` | — | E-Mail (VARCHAR(254), Laenge fest) |
| `enum` | `values: [...]` oder `ref_type: <name>` | Aufzaehlungstyp |
| `array` | `element_type: <typ>` **(Pflicht)** | Array |

---

## Spalten-Definition

```yaml
columns:
  <spaltenname>:
    type: <typ>                # Pflicht: einer der 18 neutralen Typen
    required: true             # NOT NULL (Default: false)
    unique: true               # UNIQUE-Constraint (Default: false)
    default: <wert>            # Default-Wert (Literal, Zahl, Boolean oder Funktion)

    # Typ-spezifische Attribute (siehe Typsystem-Tabelle oben)
    max_length: 255            # nur bei text
    length: 2                  # nur bei char (Pflicht)
    precision: 10              # nur bei decimal (Pflicht)
    scale: 2                   # nur bei decimal (Pflicht)
    timezone: true             # nur bei datetime
    float_precision: single    # nur bei float (Default: double)
    auto_increment: true       # nur bei identifier
    values: [a, b, c]          # nur bei enum (inline)
    ref_type: order_status     # nur bei enum (Verweis auf custom_types)
    element_type: text         # nur bei array (Pflicht)

    # Identity/Generation (optional; nur integer oder biginteger)
    generation:
      type: identity           # aktuell einzige Generation-Form
      mode: by_default         # by_default | always
      sequence_name: users_id_seq
      legacy_serial_syntax: true

    # Foreign Key (optional)
    references:
      table: <tabellenname>
      column: <spaltenname>
      on_delete: restrict      # restrict | cascade | set_null | set_default | no_action
      on_update: cascade       # (gleiche Optionen)
```

`generation` ist mit `default` gegenseitig ausgeschlossen. Ein
`generation.sequence_name` beschreibt eine an die Spalte gebundene
owned/implizite Sequence; dieselbe Sequence darf nicht zusaetzlich unter
`sequences:` als eigenstaendige Business-Sequence definiert werden.

### Default-Werte

```yaml
default: "active"              # String-Literal
default: 42                    # Zahl
default: true                  # Boolean
default: current_timestamp     # DB-Funktion (pro Dialekt uebersetzt)
default: gen_uuid              # UUID-Generierung (pro Dialekt uebersetzt)
default:                       # Sequence-basierter Default (0.9.3)
  sequence_nextval: invoice_seq  # Referenziert schema.sequences
```

Hinweis: `sequence_nextval` ist eine Objektform und nur fuer numerische/Identifier-Spalten
zulaessig. Alte `nextval(...)`-Textnotationen werden seit 0.9.3 mit E122 abgelehnt.

---

## Tabellen-Definition

```yaml
tables:
  <tabellenname>:
    description: "..."         # Optional
    columns: {}                # Pflicht (mindestens eine Spalte)
    primary_key: [id]          # Pflicht (einfach oder composite: [col1, col2])

    # Indizes (optional)
    indices:
      - name: idx_name
        columns:
          - col1
          - name: col2
            direction: desc   # Optional: asc | desc
        type: btree            # btree | hash | gin | gist | brin
        unique: false          # Default: false
        where: "col2 IS NOT NULL" # Optionales Partial-Index-Praedikat (Raw-SQL)

    # Constraints (optional)
    constraints:
      - name: chk_name
        type: check            # check | unique | foreign_key
        expression: "col > 0"  # nur bei check
        columns: [col1]        # bei unique und foreign_key

      # Composite Foreign Key als Constraint
      - name: fk_composite
        type: foreign_key
        columns: [col_a, col_b]
        references:
          table: andere_tabelle
          columns: [id_a, id_b]
          on_delete: cascade

    # Partitionierung (optional)
    partitioning:
      type: range              # range | hash | list
      key: [spalte]
      # Partitionierung ist vergleichs- und fingerprint-relevant: `schema compare`
      # meldet Unterschiede in Strategie, Schlüssel und Kind-Partitionen (kindweise
      # als Menge — die Reihenfolge der Kinder ist nicht signifikant).
      partitions:
        # RANGE: from/to als Bound-Tupel (Sentinels MINVALUE/MAXVALUE);
        # HASH: modulus/remainder; LIST: values; DEFAULT-Partition: default: true
        - name: part_2025
          from: ["2025-01-01"]
          to: ["2026-01-01"]
          # Kind-lokale Indizes (nur direkt auf der Partition definierte; vom
          # Parent propagierte Indizes/Constraints/FKs bleiben am Parent).
          indices:
            - name: idx_part_2025_col
              columns: [col]
```

---

## Custom Types

### Enum

```yaml
custom_types:
  order_status:
    kind: enum
    values: [pending, processing, shipped, delivered]
    description: "..."         # Optional
```

Verwendung in Spalten: `type: enum` + `ref_type: order_status`

### Composite

```yaml
custom_types:
  address:
    kind: composite
    fields:
      street: { type: text, max_length: 200 }
      city:   { type: text, max_length: 100 }
```

### Domain

```yaml
custom_types:
  positive_amount:
    kind: domain
    base_type: decimal
    precision: 10
    scale: 2
    check: "VALUE >= 0"
```

---

## Procedures und Functions

```yaml
procedures:
  update_status:
    description: "..."
    parameters:
      - name: p_id
        type: integer
        direction: in          # in | out | inout
    language: plpgsql          # Quell-Sprache
    body: |
      BEGIN
          UPDATE orders SET status = 'done' WHERE id = p_id;
      END;
    dependencies:
      tables: [orders]
      columns:
        orders: [id, status]
    source_dialect: postgresql

functions:
  calc_total:
    parameters:
      - { name: p_id, type: integer, direction: in }
    returns:
      type: decimal
      precision: 10
      scale: 2
    language: plpgsql
    deterministic: false       # Fuer MySQL: DETERMINISTIC / NOT DETERMINISTIC
    volatility: immutable      # PostgreSQL: immutable | stable | volatile
    strict: true               # PostgreSQL: STRICT / RETURNS NULL ON NULL INPUT
    body: |
      BEGIN RETURN 0; END;
    dependencies:
      tables: [orders]
    source_dialect: postgresql
```

---

## Views

```yaml
views:
  active_orders:
    description: "..."
    materialized: false        # true fuer Materialized Views
    refresh: on_demand         # on_demand | on_commit (nur materialized, nur PG)
    query: |
      SELECT * FROM orders WHERE status != 'delivered'
    columns:
      - name: id
        type: integer
    dependencies:
      tables: [orders]
      table_projection_status: complete
      column_projection_status: complete
      routine_projection_status: empty_verified
    source_dialect: postgresql
```

`dependencies.tables` listet bei Views die Basistabellen. Optionale
Abhaengigkeiten auf andere Views werden in `dependencies.views`
aufgefuehrt. Generatoren duerfen zusaetzliche View-Abhaengigkeiten best
effort aus `query` ableiten.

`columns` ist optional und beschreibt die sichtbare View-Signatur in
Ausgabereihenfolge. PostgreSQL-Diff-Migrationen rendern `CREATE OR REPLACE
VIEW` nur, wenn diese Signatur fuer Vorher- und Nachher-Zustand bekannt und
kompatibel ist. Projection-Status-Felder in `dependencies` nutzen die Werte
`complete`, `empty_verified`, `incomplete_privilege`,
`incomplete_object_missing` oder `unknown`.

---

## Triggers

```yaml
triggers:
  trg_updated_at:
    table: orders              # Pflicht: Zieltabelle
    event: update              # insert | update | delete (Skalar) …
    # … oder Liste für Multi-Event-Trigger (PostgreSQL INSERT OR UPDATE):
    # event: [insert, update]
    timing: before             # before | after | instead_of
    for_each: row              # row | statement
    condition: "OLD.x != NEW.x"  # Optional: WHEN-Bedingung
    body: |
      NEW.updated_at = CURRENT_TIMESTAMP;
    dependencies:
      tables: [orders]
      columns:
        orders: [updated_at]
    source_dialect: postgresql
```

---

## Sequences

```yaml
sequences:
  invoice_seq:
    description: "..."
    start: 10000               # Startwert
    increment: 1               # Schrittweite
    min_value: 10000           # Minimum
    max_value: 99999999        # Maximum
    cycle: false               # Neustart nach max_value?
    cache: 20                  # Vorausberechnete Werte
```

---

## Minimalbeispiel

```yaml
schema_format: "1.0"
name: "Minimal"
version: "1.0.0"

tables:
  users:
    columns:
      id:
        type: identifier
        auto_increment: true
      name:
        type: text
        max_length: 100
    primary_key: [id]
```

---

## Verwandte Dokumentation

- [Neutrale-Modell-Spezifikation](./neutral-model-spec.md) — Vollstaendige Spezifikation
- [DDL-Generierungsregeln](./ddl-generation-rules.md) — Wie das Schema in DDL uebersetzt wird
- [Schnellstart-Anleitung](../docs/user/guide.md) — Erste Schritte mit d-migrate
