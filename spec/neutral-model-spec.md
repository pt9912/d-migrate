# Spezifikation: Neutrales Schema-Modell

**d-migrate — Format-Referenz für das datenbankunabhängige Zwischenformat**

> Dokumenttyp: Spezifikation / Referenz
>
> Dieses Dokument definiert das neutrale Schema-Modell, das als Zwischenformat zwischen allen unterstützten Datenbanken dient. Es ist die zentrale Datenstruktur von d-migrate.

---

## 1. Überblick

Das neutrale Modell beschreibt Datenbankstrukturen **deklarativ** (was, nicht wie) und **datenbankunabhängig**. Es wird als YAML-Datei gespeichert und intern als Kotlin-Objektstruktur verarbeitet.

### 1.1 Zweck

```
  Quelle                   Neutral                     Ziel
┌───────────┐          ┌──────────────┐            ┌───────────┐
│PostgreSQL │──JDBC───▶│              │──generate─▶│  MySQL    │
│MySQL      │ reverse  │  Neutrales   │            │  SQLite   │
│SQLite     │          │  Schema-     │◀──parse────│  YAML     │
│           │          │  Modell      │            │  JSON     │
└───────────┘          └──────────────┘            └───────────┘
```

Eingabepfade für das neutrale Modell:

1. **YAML/JSON-Definition**: Manuell geschriebene Schema-Datei
2. **JDBC-Reverse-Engineering**: Extraktion aus einer bestehenden
   Datenbank über eine Live-Verbindung (`schema reverse`)

Ein dritter Pfad — **DDL-Datei-Parsing** (Analyse von SQL-DDL-Dateien) —
ist ein additiver Eingabepfad neben Definition und Live-Reverse.

### 1.2 Design-Prinzipien

- **Deklarativ**: Beschreibt Struktur und Absicht, nicht DB-spezifische Syntax
- **Informationsbewahrend**: Das neutrale Modell erhaelt die semantisch relevanten Informationen. Wenn ein Zieldialekt ein Feature nicht nativ abbilden kann, muss dies explizit als `transformation_note` dokumentiert oder als `action_required` abgebrochen werden.
- **Erweiterbar**: Neue Typen und Objekte können hinzugefügt werden
- **Menschenlesbar**: YAML als primäres Dateiformat, kommentierbar
- **Versioniert**: Jede Schema-Datei hat eine Format-Version (`schema_format`)

---

## 2. Dateistruktur

### 2.1 Top-Level-Struktur

```yaml
# ── Metadaten ──────────────────────────────────
schema_format: "1.0"              # Format-Version von d-migrate (Pflicht)
name: "E-Commerce System"        # Schema-Name (Pflicht)
version: "1.0.0"                  # Anwendungs-Schema-Version (Pflicht)
description: "Produktiv-Schema"   # Beschreibung (optional)
encoding: "utf-8"                 # Standard-Encoding (optional, Default: utf-8)
locale: "de_DE"                   # Standard-Locale (optional)

# ── Objekte ────────────────────────────────────
custom_types: {}                  # Benutzerdefinierte Typen (ENUMs, Composite, Domain)
tables: {}                        # Tabellen mit Spalten, Indizes, Constraints
procedures: {}                    # Stored Procedures (ohne Rückgabewert)
functions: {}                     # User-Defined Functions (mit Rückgabewert)
views: {}                         # Views und Materialized Views
triggers: {}                      # Trigger
sequences: {}                     # Sequenzen (explizit definierte)
```

### 2.2 Reverse-generierte Metadaten

Reverse-generierte Schemas verwenden technische Provenienzwerte fuer
`name` und `version`, damit sie nach YAML-/JSON-Serialisierung ohne
Sidecar-Datei wiedererkennbar bleiben:

- `version`: `0.0.0-reverse` (fester Platzhalter)
- `name`: beginnt mit dem reservierten Prefix `__dmigrate_reverse__:`

Format des Reverse-Names:

```
__dmigrate_reverse__:<dialect>:<key>=<value>[;<key>=<value>...]
```

Beispiele:
- `__dmigrate_reverse__:postgresql:database=mydb;schema=public`
- `__dmigrate_reverse__:mysql:database=shopdb`
- `__dmigrate_reverse__:sqlite:schema=main`

Komponentenwerte werden fuer Strukturtrenner (`;`, `=`, `:`, `%`)
per RFC-3986-Percent-Encoding kodiert.

**Reservierter Prefix**: Der Prefix `__dmigrate_reverse__:` ist fuer
tool-generierte Reverse-Metadaten reserviert. Handgeschriebene
Schema-Dateien duerfen ihn nicht als `name`-Wert verwenden.

### 2.3 Objekt-Hierarchie

```
SchemaDefinition
├── custom_types
│   └── CustomTypeDefinition (name, kind, values/fields)
├── tables
│   └── TableDefinition
│       ├── columns
│       │   └── ColumnDefinition (name, type, ordinal, required, unique, default, references, typ-spezifische Attribute)
│       ├── primary_key
│       ├── indices
│       │   └── IndexDefinition (name, columns, type, unique)
│       ├── constraints
│       │   └── ConstraintDefinition (name, type, columns, expression¹)
│       └── partitioning
│           └── PartitionConfig (type, key, partitions)
├── procedures
│   └── ProcedureDefinition (name, parameters, language, body, dependencies)
├── functions
│   └── FunctionDefinition (name, parameters, returns, language, body, dependencies)
├── views
│   └── ViewDefinition (name, query, materialized, dependencies)
├── triggers
│   └── TriggerDefinition (name, table, event, timing, body, dependencies)
└── sequences
    └── SequenceDefinition (name, start, increment, min, max, cycle)
```

---

## 3. Neutrales Typsystem

### 3.1 Typ-Mapping-Tabelle

Jeder Spaltentyp im neutralen Modell wird pro Zieldatenbank in den passenden nativen Typ übersetzt:

| Neutraler Typ | PostgreSQL              | MySQL                 | SQLite                            |
| ------------- | ----------------------- | --------------------- | --------------------------------- |
| `identifier`  | SERIAL                  | INT AUTO_INCREMENT    | INTEGER PRIMARY KEY AUTOINCREMENT |
| `text`        | VARCHAR(n) / TEXT       | VARCHAR(n) / TEXT     | TEXT                              |
| `char`        | CHAR(n)                 | CHAR(n)               | TEXT                              |
| `integer`     | INTEGER                 | INT                   | INTEGER                           |
| `smallint`    | SMALLINT                | SMALLINT              | INTEGER                           |
| `biginteger`  | BIGINT                  | BIGINT                | INTEGER                           |
| `float`       | REAL / DOUBLE PRECISION | FLOAT / DOUBLE        | REAL                              |
| `decimal`     | DECIMAL(p,s)            | DECIMAL(p,s)          | REAL                              |
| `boolean`     | BOOLEAN                 | TINYINT(1)            | INTEGER                           |
| `datetime`    | TIMESTAMP               | DATETIME              | TEXT (ISO 8601)                   |
| `date`        | DATE                    | DATE                  | TEXT (ISO 8601)                   |
| `time`        | TIME                    | TIME                  | TEXT (ISO 8601)                   |
| `uuid`        | UUID                    | CHAR(36)              | TEXT                              |
| `json`        | JSONB                   | JSON                  | TEXT                              |
| `xml`         | XML                     | TEXT (Fallback)       | TEXT                              |
| `binary`      | BYTEA                   | BLOB                  | BLOB                              |
| `email`       | VARCHAR(254)            | VARCHAR(254)          | TEXT                              |
| `enum`        | CREATE TYPE ... ENUM    | ENUM(...)             | TEXT + CHECK                      |
| `array`       | type[]                  | JSON                  | TEXT (JSON)                       |
| `geometry`    | geometry(type, srid) *  | POINT / POLYGON / ... | AddGeometryColumn() *             |
| `fulltext`    | tsvector                | TEXT **               | TEXT **                           |

\* Spatial-Mapping haengt vom gewaehlten `--spatial-profile` ab. Details in
`spec/ddl-generation-rules.md`. Bei Profil `none` wird die Spalte nicht als
DDL generiert, sondern als `action_required` gemeldet.

\*\* `fulltext` (PostgreSQL `tsvector`) ist ein first-class neutraler Volltext-Such-
Vektor (parameterlos). PostgreSQL round-trippt ihn als `tsvector` (inkl. GiST-Index).
MySQL/SQLite haben Volltext nur strukturell anders (MySQL `FULLTEXT`-Index, SQLite
`FTS5`-virtuelle Tabelle), nicht als Spaltentyp — dort degradiert die Spalte zu
`TEXT`; die strukturelle Übersetzung ist ein eigener Folge-Slice.

`identifier` ist der aktuelle 32-bit-Auto-Increment-Vertrag. PostgreSQL
`BIGSERIAL` und `BIGINT GENERATED ... AS IDENTITY` werden nicht durch
`NeutralType.BigInteger` allein ausgedrueckt; sie brauchen ein separates
Spaltenmetadatum fuer Generation/Identity. Der Modell-Vertrag ist als
`ColumnGeneration.Identity` festgelegt. `biginteger` ohne dieses Metadatum
bleibt im Forward-Generate `BIGINT`.

Die Tabelle verwendet die kanonischen Typnamen. Parameter wie `length`, `precision`, `scale`, `values`, `ref_type`, `element_type`, `geometry_type` oder `srid` werden als separate YAML-Attribute angegeben.

### 3.2 Typ-Attribute

Jeder Typ kann zusätzliche Attribute haben:

```yaml
columns:
  # Text mit Längenbeschränkung
  name:
    type: text
    max_length: 100

  # Fixed-length String
  country_code:
    type: char
    length: 2

  # Dezimalzahl mit Präzision
  price:
    type: decimal
    precision: 10
    scale: 2

  # Float mit Präzisionsstufe
  latitude:
    type: float
    float_precision: double    # single | double

  # Datetime mit Zeitzonen-Info
  created_at:
    type: datetime
    timezone: true             # TIMESTAMP WITH TIME ZONE vs. TIMESTAMP

  # Enum mit Referenz auf custom_type
  status:
    type: enum
    ref_type: order_status     # Verweis auf custom_types

  # Enum inline (ohne custom_type)
  priority:
    type: enum
    values: [low, medium, high]

  # Array
  tags:
    type: array
    element_type: text

  # Auto-Increment Identifier
  id:
    type: identifier
    auto_increment: true

  # Geometry mit Typ und SRID
  location:
    type: geometry
    geometry_type: point        # optional, Default: geometry
    srid: 4326                  # optional, positive Ganzzahl
```

#### Spatial-Typ-Attribute

| Attribut        | Pflicht | Typ         | Default    | Beschreibung                                       |
| --------------- | ------- | ----------- | ---------- | -------------------------------------------------- |
| `geometry_type` | nein    | String      | `geometry` | Geometrietyp der Spalte                            |
| `srid`          | nein    | Integer > 0 | —          | Raeumliches Referenzsystem (z.B. 4326 fuer WGS 84) |

Erlaubte `geometry_type`-Werte:

- `geometry` (beliebiger Typ, Default)
- `point`
- `linestring`
- `polygon`
- `multipoint`
- `multilinestring`
- `multipolygon`
- `geometrycollection`

Nicht Teil des neutralen Geometry-Modells:

- `geography` (sphaerische Koordinaten)
- `z` (3D-Koordinaten)
- `m` (Messwerte)
- Spatial-Indizes als eigener neutraler Typ

`geometry` ist **nicht** als zulaessiger `array.element_type`
vorgesehen. Basistyp-Allowlist und Array-Element-Allowlist sind getrennte
Vertraege.

### 3.3 Semantische Typen

Neben den technischen Typen bietet das Modell semantische Typen, die als Alias mit eingebauter Validierung fungieren:

| Semantischer Typ | Technischer Typ    | Eingebaute Einschränkung                            |
| ---------------- | ------------------ | --------------------------------------------------- |
| `email`          | `text(254)`        | Feste Maximallänge 254 (Singleton, keine Parameter) |
| `identifier`     | `integer`          | Auto-Increment, Primary Key                         |

Semantische Typen werden beim DDL-Export in ihren technischen Typ aufgelöst.
`email` ist als Singleton implementiert (`data object Email` mit `MAX_LENGTH =
254`) — die Länge ist nicht konfigurierbar. 64-bit-Identity-Spalten sind kein
eigener semantischer Typ, sondern werden ueber `biginteger` plus
`ColumnGeneration.Identity`-Metadatum modelliert.

---

## 4. Tabellen-Definition

### 4.1 Vollständige Tabellen-Syntax

```yaml
tables:
  orders:
    description: "Bestellungen"          # Optionale Beschreibung

    # ── Spalten ──────────────────────
    columns:
      id:
        type: identifier
        auto_increment: true
      customer_id:
        type: integer
        required: true                   # NOT NULL
        references:                      # FOREIGN KEY
          table: customers
          column: id
          on_delete: restrict            # restrict | cascade | set_null | set_default | no_action
          on_update: cascade             # (gleiche Optionen)
      order_date:
        type: datetime
        required: true
      total_amount:
        type: decimal
        precision: 10
        scale: 2
      status:
        type: enum
        ref_type: order_status
        default: "pending"               # Default-Wert
      notes:
        type: text
      is_archived:
        type: boolean
        default: false

    # ── Primärschlüssel ──────────────
    primary_key: [id]                    # Einfach oder Composite: [col1, col2]

    # ── Indizes ──────────────────────
    indices:
      - name: idx_orders_customer_date
        columns:
          - customer_id
          - name: order_date
            direction: desc              # optional: asc | desc
        type: btree                      # btree | hash | gin | gist | brin
        unique: false
      - name: idx_orders_status
        columns: [status]
        type: hash
      - name: uq_active_customer
        columns: [customer_id]
        unique: true
        where: "is_archived = false"     # Partial-Index-Praedikat, Raw-SQL

    # ── Constraints ──────────────────
    constraints:
      - name: chk_total_positive
        type: check                      # check | unique | exclude
        expression: "total_amount >= 0"   # ¹ Trusted Input — Raw-SQL, keine Sanitization
      - name: uq_customer_date
        type: unique
        columns: [customer_id, order_date]

    # ── Partitionierung (optional) ───
    partitioning:
      type: range                        # range | hash | list
      key: [order_date]
      partitions:
        # Bound-Literale tragen ihr SQL-Quoting (`'2024-01-01'`) — identisch für alle Dialekte.
        - name: orders_2024
          from: ["'2024-01-01'"]
          to: ["'2025-01-01'"]
        - name: orders_2025
          from: ["'2025-01-01'"]
          to: ["'2026-01-01'"]

    # ── Tabellen-Metadaten (optional) ───
    metadata:
      engine: InnoDB                     # MySQL-Tabellen-Engine (InnoDB, MyISAM, etc.)
      without_rowid: false               # SQLite WITHOUT ROWID-Tabelle
```

Jede Spalte traegt optional ein `ordinal` (1-basierte physische Position der Quelle).
Reverse befuellt es; Serialisierung und DDL-Generierung emittieren die Spalten in
Ordinalreihenfolge, sodass die Quell-Spaltenreihenfolge ueber den Round-Trip erhalten
bleibt. Fehlt `ordinal` (hand-authored), gilt die Reihenfolge im Dokument. `ordinal` ist
bewusst nicht Teil von `schema compare` (eine reine Umsortierung ist kein Migrationsschritt).

### 4.2 Tabellen-Metadaten

Tabellen koennen optionale physische Metadaten tragen, die
compare-relevant sind:

| Feld           | Typ      | Default | Beschreibung                      |
|----------------|----------|---------|-----------------------------------|
| `engine`       | `string` | `null`  | MySQL-Tabellen-Engine             |
| `without_rowid`| `boolean`| `false` | SQLite WITHOUT ROWID-Eigenschaft  |

Diese Felder werden primaer durch Reverse-Engineering befuellt. In
handgeschriebenen Schema-Dateien sind sie optional. Fehlende `metadata`
oder `null`-Werte sind aequivalent zu den Defaults.

### 4.2 Referenzen (Foreign Keys)

Beziehungen werden als Metadaten an der Spalte modelliert, nicht als eigener Datentyp:

```yaml
# Die Spalte hat einen skalaren Typ (integer), die Referenz ist Constraint-Metadaten
customer_id:
  type: integer            # Technischer Typ
  required: true
  references:              # FK-Constraint
    table: customers
    column: id
    on_delete: restrict
```

Dies ermöglicht:
- Klare Trennung von Datentyp und Beziehung
- Composite Foreign Keys über `constraints`
- Derselbe Typ für referenzierende und nicht-referenzierende Spalten

**Composite Foreign Keys** werden als Constraint definiert:

```yaml
constraints:
  - name: fk_order_product
    type: foreign_key
    columns: [order_id, product_id]
    references:
      table: order_products
      columns: [order_id, product_id]
      on_delete: cascade
```

### 4.3 Default-Werte

```yaml
columns:
  # Literaler Wert
  status:
    type: enum
    values: [active, inactive]
    default: "active"

  # Numerischer Wert
  quantity:
    type: integer
    default: 1

  # Boolean
  is_active:
    type: boolean
    default: true

  # DB-Funktion
  created_at:
    type: datetime
    default: current_timestamp

  # UUID-Generierung
  id:
    type: uuid
    default: gen_uuid          # DB-spezifisch aufgelöst: uuid_generate_v4() / UUID() / etc.

  # Sequence-basierter Default
  invoice_number:
    type: integer
    default:
      sequence_nextval: invoice_seq   # Referenziert eine benannte Sequence aus schema.sequences
```

`sequence_nextval` ist eine Objektform (nicht skalar) und referenziert eine
benannte Sequence aus `schema.sequences`. Nur fuer numerische und
Identifier-Spalten zulaessig. PostgreSQL erzeugt nativ `DEFAULT nextval('...')`;
MySQL nutzt im `helper_table`-Modus kanonische Emulationsobjekte.

Historische `nextval(...)`-Notationen als freier Text oder FunctionCall werden
mit E122 abgelehnt. Migration: `default: "nextval('seq')"` →
`default: { sequence_nextval: seq }`.

---

## 5. Custom Types

### 5.1 Enum-Typen

```yaml
custom_types:
  order_status:
    kind: enum
    values: [pending, processing, shipped, delivered, cancelled]
    description: "Status einer Bestellung"
```

**Generierung pro Datenbank**:
- PostgreSQL: `CREATE TYPE order_status AS ENUM (...)`
- MySQL: Inline `ENUM(...)` in der Spalte
- SQLite: `TEXT` + `CHECK (status IN (...))`

### 5.2 Composite-Typen

```yaml
custom_types:
  address:
    kind: composite
    fields:
      street:
        type: text
        max_length: 200
      city:
        type: text
        max_length: 100
      zip:
        type: char
        length: 10
      country:
        type: char
        length: 2
```

**Generierung**: PostgreSQL unterstützt `CREATE TYPE ... AS (...)`. Für MySQL/SQLite ist eine explizite Fallback-Strategie erforderlich (`json`, `flatten`, `action_required`). Ohne konfigurierte Strategie erfolgt kein stillschweigender Fallback; stattdessen wird `action_required` erzeugt.

### 5.3 Domain-Typen

```yaml
custom_types:
  positive_amount:
    kind: domain
    base_type: decimal
    precision: 10
    scale: 2
    check: "VALUE >= 0"
    description: "Nicht-negativer Geldbetrag"
```

---

## 6. Stored Procedures und Functions

### 6.1 Procedures (ohne Rückgabewert)

```yaml
procedures:
  update_order_status:
    description: "Aktualisiert den Status einer Bestellung"
    parameters:
      - name: p_order_id
        type: integer
        direction: in                 # in | out | inout
      - name: p_new_status
        type: text
        direction: in
      - name: p_rows_affected
        type: integer
        direction: out
    language: plpgsql                  # Quell-Sprache (plpgsql, sql, tsql, etc.)
    body: |
      BEGIN
          UPDATE orders
          SET status = p_new_status
          WHERE id = p_order_id;
          GET DIAGNOSTICS p_rows_affected = ROW_COUNT;
      END;
    dependencies:
      tables: [orders]
      columns:
        orders: [id, status]
    source_dialect: postgresql         # Quell-DB für den Body
```

### 6.2 Functions (mit Rückgabewert)

```yaml
functions:
  calculate_order_total:
    description: "Berechnet die Gesamtsumme einer Bestellung"
    parameters:
      - name: p_order_id
        type: integer
        direction: in
    returns:
      type: decimal
      precision: 10
      scale: 2
    language: plpgsql
    deterministic: false               # Für MySQL: DETERMINISTIC / NOT DETERMINISTIC
    body: |
      DECLARE
          total DECIMAL(10,2) := 0;
      BEGIN
          SELECT SUM(item_price * quantity) INTO total
          FROM order_items
          WHERE order_id = p_order_id;
          RETURN total;
      END;
    dependencies:
      tables: [order_items]
      columns:
        order_items: [order_id, item_price, quantity]
    source_dialect: postgresql
```

### 6.3 Kanonische Objekt-Keys

Fuer die verlustfreie Identitaet von Routinen und Triggern definiert das
neutrale Modell kanonische Schluesselformate. Diese werden als Map-Keys
in `procedures`, `functions` und `triggers` verwendet.

**Routinen** (Procedures und Functions) verwenden einen
Signatur-basierten Key:

```
name(direction:type,direction:type,...)
```

Beispiel: Eine Funktion `calc` mit zwei `IN`-Parametern vom Typ
`integer` erhaelt den Key `calc(in:integer,in:integer)`. Ueberladene
Routinen (gleicher Name, unterschiedliche Signatur) erhalten
unterschiedliche Keys und koennen damit verlustfrei nebeneinander im
Schema existieren.

**Trigger** verwenden einen tabellenqualifizierten Key:

```
table::name
```

Beispiel: Ein Trigger `audit` auf Tabelle `users` erhaelt den Key
`users::audit`. Gleichnamige Trigger auf verschiedenen Tabellen
kollidieren damit nicht.

**Percent-Encoding**: Reservierte Trennzeichen (`%`, `(`, `)`, `,`,
`:`) in Objekt- oder Tabellennamen werden komponentenweise
Percent-encodiert (z.B. `my%3Afunc` fuer `my:func`), bevor der Key
zusammengesetzt wird. Damit bleibt die String-Repraesentation
verlustfrei und round-trippbar.

In handgeschriebenen YAML-Dateien ohne Ueberladungen koennen weiterhin
einfache Namen als Keys verwendet werden (z.B. `calc` statt
`calc(in:integer)`). Der kanonische Key wird primaer beim
Reverse-Engineering und beim Compare von Live-Datenbanken relevant.

**Beispiel: Ueberladene Funktionen im YAML**

```yaml
functions:
  "calc(in:integer)":
    parameters:
      - name: x
        type: integer
        direction: in
    returns:
      type: integer
    body: "RETURN x * 2;"
    source_dialect: postgresql
  "calc(in:integer,in:integer)":
    parameters:
      - name: x
        type: integer
        direction: in
      - name: y
        type: integer
        direction: in
    returns:
      type: integer
    body: "RETURN x + y;"
    source_dialect: postgresql
```

**Beispiel: Gleichnamige Trigger auf verschiedenen Tabellen**

```yaml
triggers:
  "users::audit_insert":
    table: users
    event: insert
    timing: after
    body: "INSERT INTO audit_log (table_name) VALUES ('users');"
    source_dialect: postgresql
  "orders::audit_insert":
    table: orders
    event: insert
    timing: after
    body: "INSERT INTO audit_log (table_name) VALUES ('orders');"
    source_dialect: postgresql
```

Der YAML-Codec uebernimmt die Map-Keys verlustfrei: kanonische Keys
bleiben als solche erhalten, einfache Namen werden nicht implizit auf
kanonische Keys normalisiert.

### 6.4 Hinweis zu Body und Transformation

Das `body`-Feld enthält den Quell-Code im **Quell-Dialekt** (angegeben in `source_dialect`). Für die Generierung im Ziel-Dialekt gibt es zwei Wege:

1. **Regelbasiert**: Einfache Syntax-Transformationen (z.B. `:=` → `DEFAULT`, `GET DIAGNOSTICS` → `ROW_COUNT()`)
2. **KI-gestützt**: Über `d-migrate transform procedure` wird der Body in ein abstraktes Markdown-Zwischenformat transformiert und dann im Ziel-Dialekt neu generiert

---

## 7. Views

```yaml
views:
  active_orders:
    description: "Alle offenen Bestellungen"
    materialized: false                # true für Materialized Views
    query: |
      SELECT o.*, c.name AS customer_name
      FROM orders o
      JOIN customers c ON o.customer_id = c.id
      WHERE o.status NOT IN ('delivered', 'cancelled')
    columns:                         # optional: sichtbare View-Signatur
      - name: id
        type: integer
      - name: customer_name
        type: text
    dependencies:
      tables: [orders, customers]
      table_projection_status: complete
      column_projection_status: complete
      routine_projection_status: empty_verified
    source_dialect: postgresql

  monthly_revenue:
    description: "Monatliche Umsatzübersicht"
    materialized: true
    refresh: on_demand                 # on_demand | on_commit (nur PostgreSQL)
    query: |
      SELECT
          DATE_TRUNC('month', order_date) AS month,
          SUM(total_amount) AS revenue,
          COUNT(*) AS order_count
      FROM orders
      WHERE status = 'delivered'
      GROUP BY DATE_TRUNC('month', order_date)
    dependencies:
      tables: [orders]
      views: [active_orders]
    source_dialect: postgresql
```

**Transformationshinweise**:
- Diff-basierte Migrationen blockieren Materialized Views bis zu einem eigenen Refresh-/Staleness-Vertrag; sie werden nicht als normale Views gerendert.
- `DATE_TRUNC` wird pro Dialekt übersetzt (PostgreSQL: nativ, MySQL: `DATE_FORMAT`, SQLite: `strftime`)
- `dependencies.tables` enthält bei Views die Basistabellen, die von der Query gelesen werden
- `dependencies.views` enthält optionale Abhängigkeiten auf andere Views, die vor dieser View erzeugt werden müssen
- `columns` enthaelt optional die sichtbare View-Signatur. PostgreSQL nutzt sie, um `CREATE OR REPLACE VIEW` nur bei kompatibler Spaltenanzahl, Reihenfolge, Namen und sichtbaren Typen zu rendern.
- `table_projection_status`, `column_projection_status` und `routine_projection_status` koennen `complete`, `empty_verified`, `incomplete_privilege`, `incomplete_object_missing` oder `unknown` sein. Nicht verwendbare Status blockieren migrationskritische View-Operationen.
- Generatoren dürfen zusätzliche View-Abhängigkeiten best effort aus der Query ableiten; deklarierte `dependencies.views` haben dabei Vorrang für die Emissionsreihenfolge

---

## 8. Triggers

Trigger-Keys folgen dem kanonischen Format `table::name`
(siehe Abschnitt 6.3). In handgeschriebenen YAML-Dateien ohne
Namenskollisionen koennen weiterhin einfache Namen verwendet werden.

```yaml
triggers:
  trg_orders_updated_at:
    description: "Setzt updated_at bei jeder Änderung"
    table: orders
    event: update                      # insert | update | delete
    timing: before                     # before | after | instead_of
    for_each: row                      # row | statement
    condition: null                    # Optional: WHEN-Bedingung
    body: |
      NEW.updated_at = CURRENT_TIMESTAMP;
    dependencies:
      tables: [orders]
      columns:
        orders: [updated_at]
    source_dialect: postgresql
```

---

## 9. Sequences

Explizit definierte Sequenzen (außerhalb von `identifier`/Auto-Increment):

```yaml
sequences:
  invoice_number_seq:
    description: "Rechnungsnummern-Sequenz"
    start: 10000
    increment: 1
    min_value: 10000
    max_value: 99999999
    cycle: false                       # Neustart nach max_value?
    cache: 20                          # Anzahl vorausberechneter Werte
    preserve_current_value: false      # Runtime-Wert über Migration retten?
```

**Generierung**:
- PostgreSQL: `CREATE SEQUENCE ... START WITH ... INCREMENT BY ...`
- MySQL: Emulation über dedizierte Sequenz-Tabelle oder generator-spezifische Hilfsstruktur
- SQLite: Keine nativen benannten Sequenzen; Emulation nur über explizite Hilfstabelle/Trigger oder `action_required`

### 9.1 `preserve_current_value`

Per Default verlieren Sequenzen ihren Laufzeit-Wert bei einer
Migration: eine `CREATE SEQUENCE … START WITH 1` startet `nextval` bei
1, auch wenn die existierende Sequenz auf dem Ziel bereits bei 5000
stand. Das passt für Neu-Migrationen; für **bestehende Produktions-
DBs mit befüllten Tabellen** führt es zu PK-Konflikten bei der
ersten INSERT-Operation nach der Migration.

`preserve_current_value: true` aktiviert das opt-in pro Sequence:
nach jeder declarative `CREATE` / `ALTER` / `RENAME SEQUENCE`-Operation
emittiert der Migrate-Pipeline einen Follow-up, der den runtime-Wert
aus dem Live-Target übernimmt:

| Dialekt | Renderer | Probe |
|---|---|---|
| PostgreSQL | `SELECT setval('<seq>', <last_value>, <is_called>);` | `SELECT last_value, is_called FROM <seq>` |
| MySQL (HELPER_TABLE-Mode) | `UPDATE dmg_sequences SET next_value = <v> WHERE name = <key> AND managed_by IN (…) AND format_version IN (…);` | `SELECT next_value, managed_by, format_version FROM dmg_sequences WHERE name = <key>` |
| SQLite (HELPER_TABLE-Mode) | `UPDATE "dmg_sequences" SET "next_value" = <v> WHERE "name" = <key>;` (Up auf `applySequenceRef`, Down auf `probeSequenceRef`) | `SELECT "next_value", "managed_by", "format_version" FROM "dmg_sequences" WHERE "name" = <key>` |

**Voraussetzungen**:

- `--execute` mit DB-Target. Die Probe braucht eine offene
  Connection; File-Mode emittiert `SEQUENCE_PRESERVE_NOT_RUN_POLICY`
  als INFO ohne Follow-up.
- Für SQLite ist zusätzlich `--sqlite-named-sequences helper_table`
  Pflicht (sonst `SEQUENCE_PRESERVE_OPT_IN_REQUIRED`,
  `primaryBlockedReason = MANUAL_ACTION_REQUIRED`).
- Auf `AlterSequence` / `RenameSequence` muss das Live-Target die
  Sequenz bereits kennen — sonst blockt der Plan mit
  `SEQUENCE_PRESERVE_PROBE_FAILED` (kein deterministischer
  Vor-Zustand).
- Für reine `CreateSequence`-Operationen (ohne Rename-Provenance)
  emittiert die Pipeline `SEQUENCE_PRESERVE_NOT_FOUND` als INFO und
  überspringt den Follow-up — der current-value-Restore wird im
  Down-Pfad als `ROLLBACK_NOT_POSSIBLE` dokumentiert.

**Default-Verhalten**: ohne `preserve_current_value` (oder mit
explizitem `false`) bleibt die Migration unverändert — kein Probe,
kein Follow-up, keine neuen Statements im Migrate-Output.

### 9.2 Cross-Dialect-Capability-Matrix

`SequenceDefinition`-Attribute überleben den Cross-Dialect-Transfer
unterschiedlich. Der Renderer pro Dialekt konsultiert
`SequenceCapability` (definiert in `hexagon:ports-read`,
Defaults in `SequenceCapabilityDefaults.forDialect(...)`) als
einzige Wahrheits-Quelle für die Frage „welches Attribut wird wie
gerendert, welches blockt, welches emittiert nur eine Warnung?".
Die Defaults sind die unterste Präzedenz-Schicht; eine spätere
Tranche kann Overlay-/CLI-Overrides ergänzen.

| Attribut | PG | MySQL (Emul.) | SQLite (`helper_table`-Mode, opt-in via `--sqlite-named-sequences`) | Cross-Dialect-Verhalten |
|---|---|---|---|---|
| `name` | nativ | `dmg_sequences.name` | `dmg_sequences.name` (`E056`-Skip im Default `action_required`-Mode) | Source = neutral; verlustfrei sobald Target-Renderer Sequenzen aktiviert |
| `start` | `START WITH` | `dmg_sequences.next_value` (Seed) | `dmg_sequences.next_value` (Seed) | Verlustfrei für frische Migrationen; SQLite-`helper_table` modelliert nur den Seed-Zustand |
| `increment` | `INCREMENT BY` | `dmg_sequences.increment_by` | `dmg_sequences.increment_by` | Verlustfrei zwischen allen drei Dialekten |
| `min_value` | `MINVALUE` | `dmg_sequences.min_value` | `dmg_sequences.min_value` | Verlustfrei in `helper_table` |
| `max_value` | `MAXVALUE` | `dmg_sequences.max_value` | `dmg_sequences.max_value` | Verlustfrei in `helper_table` |
| `cycle` | `CYCLE` / `NO CYCLE` | `dmg_sequences.cycle_enabled` (`TINYINT(1)`) | `dmg_sequences.cycle_enabled` (`INTEGER`) | Verlustfrei in `helper_table` |
| `cache` | `CACHE n` (Runtime-Preallocation) | `dmg_sequences.cache_size` (Metadatum, keine Preallocation) | `dmg_sequences.cache_size` (Metadatum, keine Preallocation) | Renderer emittiert `W114` ohne Overlay, wenn der Wert als Metadatum gespeichert aber nicht als Runtime-Cache emuliert wird. Alle Render-Pfade (Full-Schema und Diff) konsumieren dieselbe Capability — siehe `SequenceCapability.emitsCachePreallocationWarning`. |
| `preserve_current_value` | `setval(…, true)` | `UPDATE dmg_sequences SET next_value = …` | `UPDATE dmg_sequences SET next_value = …` *(opt-in via `--sqlite-named-sequences helper_table`, sonst `SEQUENCE_PRESERVE_OPT_IN_REQUIRED`)* | Execute-only; siehe §9.1 |
| `OWNED BY <table>.<col>` (nur PG nativ) | nativ, aber nicht im neutralen Modell | nicht abbildbar | nicht abbildbar | Out of scope: PG-Reader filtert `pg_depend.deptype IN ('a','i')` aus `schema.sequences`. Reserviert: `SEQUENCE_OWNED_BY_NOT_REPRESENTABLE_IN_DIALECT` für eine spätere Neutralmodell-Erweiterung mit Ownership-Feld. |

**SQLite-Defaults (Reality-First)**: die
SQLite-Sequence-Emulation liefert eine vollständige `helper_table`-Variante
inklusive `preserveCurrentValue`-Pfad. Damit melden die SQLite-
Capability-Defaults `supportsNamedSequences = true` und
`supportsCurrentValuePreserve = true`. Der Default-Mode bleibt
`action_required` (`E056`-Skip im Full-Schema-Pfad,
`SEQUENCE_PRESERVE_OPT_IN_REQUIRED` im Diff-Pfad); erst
`--sqlite-named-sequences helper_table` aktiviert die Emulation und
den Preserve-Probe. Per-Attribut-Mismatch via
`SEQUENCE_ATTRIBUTE_NOT_SUPPORTED_BY_DIALECT` ist reservierte
Forward-Compat-Diagnose und greift nur, wenn ein zukünftiges
Capability-Subset ein einzelnes Attribut ausschliessen muss.

---

## 10. Vollständiges Beispiel

### 10.1 PostgreSQL-Eingabe (DDL-Referenz)

> Das folgende DDL dient als Referenz dafür, welches neutrale Modell bei einem
> Reverse-Engineering dieser Datenbankstruktur entstehen würde. Die
> Extraktion erfolgt via Live-DB-Verbindung (`schema reverse`) oder
> über DDL-Datei-Parsing.

```sql
CREATE TYPE order_status AS ENUM ('pending', 'processing', 'shipped', 'delivered', 'cancelled');

CREATE TABLE customers (
    id          SERIAL PRIMARY KEY,
    email       VARCHAR(254) NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    metadata    JSONB,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_customers_email ON customers (email);

CREATE TABLE orders (
    id              SERIAL PRIMARY KEY,
    customer_id     INTEGER NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    order_date      TIMESTAMP NOT NULL,
    total_amount    DECIMAL(10,2) CHECK (total_amount >= 0),
    status          order_status DEFAULT 'pending',
    notes           TEXT
);

CREATE INDEX idx_orders_customer_date ON orders (customer_id, order_date DESC);

CREATE OR REPLACE FUNCTION calculate_order_total(p_order_id INT)
RETURNS DECIMAL(10,2) AS $$
DECLARE
    total DECIMAL(10,2) := 0;
BEGIN
    SELECT SUM(item_price * quantity) INTO total
    FROM order_items
    WHERE order_id = p_order_id;
    RETURN total;
END;
$$ LANGUAGE plpgsql;

CREATE VIEW active_orders AS
    SELECT o.*, c.name AS customer_name
    FROM orders o
    JOIN customers c ON o.customer_id = c.id
    WHERE o.status NOT IN ('delivered', 'cancelled');
```

### 10.2 Neutrales Modell (YAML)

```yaml
schema_format: "1.0"
name: "E-Commerce System"
version: "1.0.0"
encoding: "utf-8"

custom_types:
  order_status:
    kind: enum
    values: [pending, processing, shipped, delivered, cancelled]

tables:
  customers:
    columns:
      id:
        type: identifier
        auto_increment: true
      email:
        type: text
        max_length: 254
        required: true
        unique: true
      name:
        type: text
        max_length: 100
        required: true
      metadata:
        type: json
      created_at:
        type: datetime
        timezone: true
        default: current_timestamp
    primary_key: [id]
    indices:
      - name: idx_customers_email
        columns: [email]
        type: btree

  orders:
    columns:
      id:
        type: identifier
        auto_increment: true
      customer_id:
        type: integer
        required: true
        references:
          table: customers
          column: id
          on_delete: restrict
      order_date:
        type: datetime
        required: true
      total_amount:
        type: decimal
        precision: 10
        scale: 2
      status:
        type: enum
        ref_type: order_status
        default: "pending"
      notes:
        type: text
    primary_key: [id]
    indices:
      - name: idx_orders_customer_date
        columns:
          - customer_id
          - name: order_date
            direction: desc
        type: btree
    constraints:
      - name: chk_total_positive
        type: check
        expression: "total_amount >= 0"

functions:
  calculate_order_total:
    parameters:
      - name: p_order_id
        type: integer
        direction: in
    returns:
      type: decimal
      precision: 10
      scale: 2
    language: plpgsql
    body: |
      DECLARE
          total DECIMAL(10,2) := 0;
      BEGIN
          SELECT SUM(item_price * quantity) INTO total
          FROM order_items
          WHERE order_id = p_order_id;
          RETURN total;
      END;
    dependencies:
      tables: [order_items]
      columns:
        order_items: [order_id, item_price, quantity]
    source_dialect: postgresql

views:
  active_orders:
    materialized: false
    query: |
      SELECT o.*, c.name AS customer_name
      FROM orders o
      JOIN customers c ON o.customer_id = c.id
      WHERE o.status NOT IN ('delivered', 'cancelled')
    dependencies:
      tables: [orders, customers]
    source_dialect: postgresql
```

### 10.3 MySQL-Ausgabe (generiertes DDL)

```sql
-- Generated by d-migrate
-- Source: neutral schema v1.0.0 "E-Commerce System"

CREATE TABLE `customers` (
    `id`         INT NOT NULL AUTO_INCREMENT,
    `email`      VARCHAR(254) NOT NULL,
    `name`       VARCHAR(100) NOT NULL,
    `metadata`   JSON,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_customers_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX `idx_customers_email` ON `customers` (`email`);

CREATE TABLE `orders` (
    `id`            INT NOT NULL AUTO_INCREMENT,
    `customer_id`   INT NOT NULL,
    `order_date`    DATETIME NOT NULL,
    `total_amount`  DECIMAL(10,2),
    `status`        ENUM('pending','processing','shipped','delivered','cancelled')
                    DEFAULT 'pending',
    `notes`         TEXT,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_orders_customer_id` FOREIGN KEY (`customer_id`)
        REFERENCES `customers` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_total_positive` CHECK (`total_amount` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX `idx_orders_customer_date` ON `orders` (`customer_id`, `order_date` DESC);

CREATE OR REPLACE VIEW `active_orders` AS
    SELECT o.*, c.`name` AS customer_name
    FROM `orders` o
    JOIN `customers` c ON o.`customer_id` = c.`id`
    WHERE o.`status` NOT IN ('delivered', 'cancelled');
```

### 10.4 SQLite-Ausgabe (generiertes DDL)

```sql
-- Generated by d-migrate
-- Source: neutral schema v1.0.0 "E-Commerce System"

CREATE TABLE "customers" (
    "id"         INTEGER PRIMARY KEY AUTOINCREMENT,
    "email"      TEXT NOT NULL UNIQUE,
    "name"       TEXT NOT NULL,
    "metadata"   TEXT,
    "created_at" TEXT DEFAULT (datetime('now'))
);

CREATE INDEX "idx_customers_email" ON "customers" ("email");

CREATE TABLE "orders" (
    "id"            INTEGER PRIMARY KEY AUTOINCREMENT,
    "customer_id"   INTEGER NOT NULL REFERENCES "customers"("id") ON DELETE RESTRICT,
    "order_date"    TEXT NOT NULL,
    "total_amount"  REAL,
    "status"        TEXT DEFAULT 'pending'
                    CHECK ("status" IN ('pending','processing','shipped','delivered','cancelled')),
    "notes"         TEXT,
    CHECK ("total_amount" >= 0)
);

CREATE INDEX "idx_orders_customer_date" ON "orders" ("customer_id", "order_date" DESC);

CREATE VIEW "active_orders" AS
    SELECT o.*, c."name" AS customer_name
    FROM "orders" o
    JOIN "customers" c ON o."customer_id" = c."id"
    WHERE o."status" NOT IN ('delivered', 'cancelled');
```

---

## 11. Transformationshinweise

Bei der Konvertierung zwischen Datenbanken erzeugt d-migrate automatisch Hinweise zu Kompatibilitätsproblemen:

### 11.1 Hinweis-Typen

| Typ               | Bedeutung                                     | Beispiel                                |
| ----------------- | --------------------------------------------- | --------------------------------------- |
| `info`            | Automatisch gelöst, zur Kenntnisnahme         | TIMESTAMP WITH TIME ZONE → DATETIME     |
| `warning`         | Funktionalitätseinschränkung möglich          | JSONB → JSON (kein GIN-Index möglich)   |
| `action_required` | Manuelle Prüfung oder KI-Transformation nötig | Function Body muss transformiert werden |

### 11.2 Format

```yaml
transformation_notes:
  - type: info
    object: customers.created_at
    source: "TIMESTAMP WITH TIME ZONE"
    target: "DATETIME"
    message: "MySQL DATETIME hat keine Zeitzonen-Information. UTC-Konvertierung empfohlen."

  - type: warning
    object: customers.metadata
    source: "JSONB"
    target: "JSON"
    message: "MySQL JSON unterstützt keine GIN-Indizes. Queries auf JSON-Felder sind langsamer."

  - type: warning
    object: order_status
    source: "CREATE TYPE ... ENUM"
    target: "inline ENUM"
    message: "PostgreSQL ENUM als separater Typ → MySQL inline ENUM. Typ-Änderungen erfordern ALTER TABLE statt ALTER TYPE."

  - type: action_required
    object: calculate_order_total
    source: "PL/pgSQL FUNCTION"
    target: "MySQL PROCEDURE/FUNCTION"
    message: "Function Body enthält PL/pgSQL-Syntax. KI-gestützte Transformation empfohlen: d-migrate transform procedure --procedure calculate_order_total"

  - type: info
    object: orders (SQLite)
    source: "DECIMAL(10,2)"
    target: "REAL"
    message: "SQLite hat keine native DECIMAL-Unterstützung. Werte werden als REAL gespeichert. Rundungsfehler bei finanziellen Berechnungen möglich."
```

---

## 12. DDL-Parser

Der DDL-Parser liest SQL-Dateien (Datei-Parsing, Dialekt-Erkennung aus Dateien,
stdin-DDL) und projiziert sie ins neutrale Modell — ein dateibasierter
Eingabepfad neben dem Live-DB-Reverse. Beide Pfade ergeben dasselbe
`SchemaDefinition`.

### 12.1 Unterstützte Statements

Der DDL-Parser soll folgende SQL-Statements erkennen und verarbeiten:

| Statement                        | Ergebnis im neutralen Modell        |
| -------------------------------- | ----------------------------------- |
| `CREATE TABLE`                   | `tables.<name>`                     |
| `ALTER TABLE ... ADD COLUMN`     | Spalte in bestehender Tabelle       |
| `ALTER TABLE ... ADD CONSTRAINT` | Constraint in bestehender Tabelle   |
| `CREATE INDEX`                   | `tables.<name>.indices`             |
| `CREATE TYPE ... AS ENUM`        | `custom_types.<name>`               |
| `CREATE TYPE ... AS (...)`       | `custom_types.<name>` (composite)   |
| `CREATE FUNCTION`                | `functions.<name>`                  |
| `CREATE PROCEDURE`               | `procedures.<name>`                 |
| `CREATE VIEW`                    | `views.<name>`                      |
| `CREATE MATERIALIZED VIEW`       | `views.<name>` (materialized: true) |
| `CREATE TRIGGER`                 | `triggers.<name>`                   |
| `CREATE SEQUENCE`                | `sequences.<name>`                  |

### 12.2 Dialekt-Erkennung

Der Parser soll den Quell-Dialekt automatisch erkennen:

| Indikator                                       | Erkannter Dialekt       |
| ----------------------------------------------- | ----------------------- |
| `SERIAL`, `BIGSERIAL`, `$$`, `LANGUAGE plpgsql` | PostgreSQL              |
| `AUTO_INCREMENT`, `ENGINE=`, `DELIMITER //`     | MySQL                   |
| `AUTOINCREMENT`, `WITHOUT ROWID`                | SQLite                  |
| `IDENTITY`, `NVARCHAR`, `GO`                    | MS SQL Server (geplant) |
| `NUMBER`, `VARCHAR2`, `PL/SQL`                  | Oracle (geplant)        |

Akzeptierte CLI-Aliase wie `postgres` werden intern auf kanonische Modellwerte normalisiert (`postgresql`, `mysql`, `sqlite`, `mssql`, `oracle`).

### 12.3 Verarbeitungspipeline

```
SQL-Datei(en)
     │
     ▼
┌────────────────────────┐
│ 1. Tokenizer           │  SQL-Text → Statement-Liste
│    - Kommentare filtern│  (;-separiert, DELIMITER-aware)
│    - String-Literals   │
└────────┬───────────────┘
         ▼
┌────────────────────────┐
│ 2. Statement-Classifier│  CREATE TABLE / INDEX / FUNCTION / ...
│    - Dialekt erkennen  │
└────────┬───────────────┘
         ▼
┌────────────────────────┐
│ 3. Statement-Parser    │  Pro Statement-Typ eigener Parser
│    - Column-Parser     │  Ergebnis: neutrales Teilmodell
│    - Constraint-Parser │
│    - Type-Parser       │
└────────┬───────────────┘
         ▼
┌────────────────────────┐
│ 4. Typ-Mapping         │  DB-spezifische Typen → NeutralType
│    - TypeMapper (SPI)  │  z.B. SERIAL → identifier
└────────┬───────────────┘
         ▼
┌────────────────────────┐
│ 5. Referenz-Auflösung  │  FK → references, TYPE → ref_type
│    - Cross-Reference   │  Zirkuläre Referenzen erkennen
└────────┬───────────────┘
         ▼
┌────────────────────────┐
│ 6. Validierung         │  Referenzielle Integrität
│    - Vollständigkeit   │  Typ-Kompatibilität
│    - Konsistenz        │  Doppelte Namen
└────────┬───────────────┘
         ▼
   SchemaDefinition
```

---

## 13. Validierungsregeln

Das neutrale Modell wird vor der DDL-Generierung validiert:

### 13.1 Syntaktische Regeln

- Jede Tabelle muss mindestens eine Spalte haben
- Jede Tabelle sollte einen `primary_key` haben (explizit oder über `identifier`-Typ); ein fehlender Primary Key erzeugt eine Warnung (E008), blockiert aber die Validierung nicht
- Spaltennamen müssen innerhalb einer Tabelle eindeutig sein
- Index-Spalten müssen in der Tabelle existieren
- Enum-Werte dürfen nicht leer sein
- Bei `type: enum` muss genau eine Variante verwendet werden: `ref_type` oder inline `values`
- Bei `type: char` ist `length` Pflicht und muss positiv sein
- Bei `type: array` ist `element_type` Pflicht
- Partitionierungs-Schlüssel müssen existierende Spalten der Tabelle referenzieren

### 13.2 Referenzielle Regeln

- `references.table` muss eine existierende Tabelle sein
- `references.column` muss in der referenzierten Tabelle existieren
- `ref_type` muss in `custom_types` existieren
- Trigger-`table` muss existieren
- `dependencies.views` muss auf existierende Eintraege in `views` verweisen
- Keine unauflösbaren zirkulären Abhängigkeiten (Warnung bei erkannten Zyklen)

### 13.3 Typkompatibilitäts-Regeln

- `references`-Spaltentyp muss kompatibel mit der referenzierten Spalte sein
- `default`-Wert muss zum Spaltentyp passen
- `precision` und `scale` müssen bei `decimal` angegeben sein
- `max_length` bei `text` muss positiv sein
- `check`-Expressions dürfen nur auf Spalten der eigenen Tabelle verweisen
- `float_precision` darf nur bei `type: float` gesetzt sein
- `timezone` darf nur bei `type: datetime` gesetzt sein

### 13.4 Spatial-Validierungsregeln

Diese Regeln pruefen das neutrale Schema selbst (`schema validate`):

| Code | Regel                                                              | Ebene  |
| ---- | ------------------------------------------------------------------ | ------ |
| E120 | Unbekannter `geometry_type`-Wert (nicht in der Allowlist aus §3.2) | Modell |
| E121 | `srid` muss groesser als 0 sein                                    | Modell |
| E020 | `dependencies.views` verweist auf eine nicht vorhandene View       | Modell |

Die folgenden Codes entstehen erst bei `schema generate` und sind
Generator-/Report-Regeln, keine Modellvalidierung:

| Code | Regel                                                                                                            | Ebene     |
| ---- | ---------------------------------------------------------------------------------------------------------------- | --------- |
| E052 | Spatial-Objekt kann mit dem gewaehlten Profil nicht generiert werden (z.B. `geometry` bei `--spatial-profile none`) | Generator |
| E053 | Dialektspezifischer SQL-Inhalt (View-Query, Routine- oder Trigger-Body) erfordert manuelle Transformation oder Implementierung | Generator |
| E054 | Objekttyp wird im Zieldialekt nicht unterstuetzt | Generator |
| E055 | Partitionierung wird im Zieldialekt nicht unterstuetzt | Generator |
| E056 | Benannte Sequence kann im Zieldialekt nicht nativ generiert werden und benoetigt Emulation oder manuelle Nacharbeit | Generator |
| W113 | View-Abhaengigkeiten konnten nicht vollstaendig topologisch sortiert werden; die Restmenge bleibt in Originalreihenfolge | Generator |
| W120 | SRID-Metadaten konnten nicht vollstaendig in den Zieldialekt uebertragen werden                                  | Generator |

Wichtig: `E020`/`E120`/`E121` werden von `schema validate` gemeldet.
`E052`-`E056`/`W113`/`W120` werden nur von `schema generate` gemeldet und sind Teil
des bestehenden `action_required`- bzw. Warning-Report-Vertrags.

Die Codes `E052`-`E056` beschreiben dabei **die Ursache** eines
`action_required`-Falls, nicht automatisch dessen Reichweite. Ob der
Generator nur das betroffene Objekt ueberspringt oder die weitere Generierung
einer ganzen Tabelle blockiert, ist eine separate Generatorwirkung.

| Code | Typische Wirkung bei `schema generate` |
| ---- | -------------------------------------- |
| E052 | Blockiert die gesamte betroffene Tabelle (Spatial-Profil verhindert Tabellen-DDL). |
| E053 | Ueberspringt das betroffene View-/Function-/Procedure-/Trigger-Objekt; keine Tabellenblockierung. |
| E054 | Ueberspringt das betroffene Objekt oder die betroffene Constraint-/Typ-Definition; keine Tabellenblockierung. |
| E055 | Blockiert die betroffene Tabelle, wenn deren Partitionierung im Zieldialekt nicht erzeugt werden kann. |
| E056 | Ueberspringt die betroffene benannte Sequence; keine Tabellenblockierung. |
| W113 | Warnung; Restmenge von Views bleibt in Originalreihenfolge, DDL-Erzeugung laeuft weiter. |
| W120 | Best-Effort-Warnung; blockiert keine DDL-Erzeugung. |

---

## 14. Erweiterbarkeit

### 14.1 Neue neutrale Typen hinzufügen

1. `NeutralType` Sealed Class in `hexagon:core` erweitern
2. Typ-Mapping in jedem `TypeMapper` (PostgreSQL, MySQL, SQLite) ergänzen
3. YAML-Parser um neuen Typ erweitern
4. Validierungsregeln anpassen
5. Tests: 100% Coverage für neuen Typ in allen Dialekten

### 14.2 Neue Objekt-Typen hinzufügen

1. Definition-Klasse erstellen (z.B. `MaterializedViewDefinition`)
2. In `SchemaDefinition` als optionales Feld aufnehmen
3. `SchemaReader` und `DdlGenerator` in der Driver-API erweitern
4. YAML-Serialisierung ergänzen
5. Ggf. DDL-Parser um neues Statement erweitern

---

## Verwandte Dokumentation

- [Lastenheft](./lastenheft-d-migrate.md) — Vollständige Anforderungsspezifikation ([`LF-001`](lastenheft-d-migrate.md#lf-001) bis [`LF-004`](lastenheft-d-migrate.md#lf-004))
- [DDL-Generierungsregeln](./ddl-generation-rules.md) — Quoting, Statement-Ordering, Dialekt-Besonderheiten
- [CLI-Spezifikation](./cli-spec.md) — Exit-Codes, Fehler-Codes, Kommando-Referenz

---

**Version**: 1.2
**Stand**: 2026-04-13
**Status**: Entwurf
