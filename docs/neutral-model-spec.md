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
2. **JDBC-Reverse-Engineering** *(0.6.0)*: Extraktion aus einer bestehenden
   Datenbank über eine Live-Verbindung (`schema reverse`)

Ein dritter Pfad — **DDL-Datei-Parsing** (Analyse von SQL-DDL-Dateien) — ist
als späterer additiver Funktionsschnitt vorgesehen, gehört aber nicht zum
0.6.0-Mindestvertrag.

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

### 2.2 Reverse-generierte Metadaten (ab 0.6.0)

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
│       │   └── ColumnDefinition (name, type, required, unique, default, references, typ-spezifische Attribute)
│       ├── primary_key
│       ├── indices
│       │   └── IndexDefinition (name, columns, type, unique)
│       ├── constraints
│       │   └── ConstraintDefinition (name, type, columns, expression)
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
| `identifier`  | SERIAL / BIGSERIAL      | INT AUTO_INCREMENT    | INTEGER PRIMARY KEY AUTOINCREMENT |
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

\* Spatial-Mapping haengt vom gewaehlten `--spatial-profile` ab. Details in
`docs/ddl-generation-rules.md`. Bei Profil `none` wird die Spalte nicht als
DDL generiert, sondern als `action_required` gemeldet.

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

  # Geometry mit Typ und SRID (Spatial Phase 1, ab 0.5.5)
  location:
    type: geometry
    geometry_type: point        # optional, Default: geometry
    srid: 4326                  # optional, positive Ganzzahl
```

#### Spatial-Typ-Attribute (ab 0.5.5)

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

Nicht Teil von 0.5.5:

- `geography` (sphaerische Koordinaten)
- `z` (3D-Koordinaten)
- `m` (Messwerte)
- Spatial-Indizes als eigener neutraler Typ

`geometry` ist in 0.5.5 **nicht** als zulaessiger `array.element_type`
vorgesehen. Basistyp-Allowlist und Array-Element-Allowlist sind getrennte
Vertraege.

### 3.3 Semantische Typen

Neben den technischen Typen bietet das Modell semantische Typen, die als Alias mit eingebauter Validierung fungieren:

| Semantischer Typ | Technischer Typ    | Eingebaute Einschränkung                            |
| ---------------- | ------------------ | --------------------------------------------------- |
| `email`          | `text(254)`        | Feste Maximallänge 254 (Singleton, keine Parameter) |
| `identifier`     | `integer`/`bigint` | Auto-Increment, Primary Key                         |

Semantische Typen werden beim DDL-Export in ihren technischen Typ aufgelöst. `email` ist als Singleton implementiert (`data object Email` mit `MAX_LENGTH = 254`) — die Länge ist nicht konfigurierbar.

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
        columns: [customer_id, order_date]
        type: btree                      # btree | hash | gin | gist | brin
        unique: false
      - name: idx_orders_status
        columns: [status]
        type: hash

    # ── Constraints ──────────────────
    constraints:
      - name: chk_total_positive
        type: check                      # check | unique | exclude
        expression: "total_amount >= 0"
      - name: uq_customer_date
        type: unique
        columns: [customer_id, order_date]

    # ── Partitionierung (optional) ───
    partitioning:
      type: range                        # range | hash | list
      key: [order_date]
      partitions:
        - name: orders_2024
          from: "2024-01-01"
          to: "2025-01-01"
        - name: orders_2025
          from: "2025-01-01"
          to: "2026-01-01"

    # ── Tabellen-Metadaten (optional, ab 0.6.0) ───
    metadata:
      engine: InnoDB                     # MySQL-Tabellen-Engine (InnoDB, MyISAM, etc.)
      without_rowid: false               # SQLite WITHOUT ROWID-Tabelle
```

### 4.2 Tabellen-Metadaten

Ab 0.6.0 koennen Tabellen optionale physische Metadaten tragen, die
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
```

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

### 6.3 Kanonische Objekt-Keys (ab 0.6.0)

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
2. **KI-gestützt**: Über `d-migrate transform procedure` wird der Body in ein abstraktes Markdown-Zwischenformat transformiert und dann im Ziel-Dialekt neu generiert (siehe [Beispiel Stored Procedure Migration](./beispiel-stored-procedure-migration.md))

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
    dependencies:
      tables: [orders, customers]
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
    source_dialect: postgresql
```

**Transformationshinweise**:
- Materialized Views werden in MySQL als reguläre Tabelle mit Trigger/Event-basiertem Refresh emuliert (Warnung wird erzeugt)
- SQLite unterstützt keine Materialized Views; Fallback auf reguläre View mit Hinweis
- `DATE_TRUNC` wird pro Dialekt übersetzt (PostgreSQL: nativ, MySQL: `DATE_FORMAT`, SQLite: `strftime`)

---

## 8. Triggers

Trigger-Keys folgen ab 0.6.0 dem kanonischen Format `table::name`
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
```

**Generierung**:
- PostgreSQL: `CREATE SEQUENCE ... START WITH ... INCREMENT BY ...`
- MySQL: Emulation über dedizierte Sequenz-Tabelle oder generator-spezifische Hilfsstruktur
- SQLite: Keine nativen benannten Sequenzen; Emulation nur über explizite Hilfstabelle/Trigger oder `action_required`

---

## 10. Vollständiges Beispiel

### 10.1 PostgreSQL-Eingabe (DDL-Referenz)

> Das folgende DDL dient als Referenz dafür, welches neutrale Modell bei einem
> Reverse-Engineering dieser Datenbankstruktur entstehen würde. In 0.6.0
> erfolgt die Extraktion via Live-DB-Verbindung (`schema reverse`), nicht
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

CREATE INDEX idx_orders_customer_date ON orders (customer_id, order_date);

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
        columns: [customer_id, order_date]
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

CREATE INDEX `idx_orders_customer_date` ON `orders` (`customer_id`, `order_date`);

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

CREATE INDEX "idx_orders_customer_date" ON "orders" ("customer_id", "order_date");

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

## 12. DDL-Parser *(späterer Milestone — nicht Teil von 0.6.0)*

> **Hinweis**: Der DDL-Parser (SQL-Datei-Parsing, Dialekt-Erkennung aus
> Dateien, stdin-DDL) gehört **nicht** zum 0.6.0-Mindestvertrag. In 0.6.0
> arbeitet `schema reverse` ausschließlich gegen Live-DB-Verbindungen via
> JDBC. Dieser Abschnitt beschreibt den geplanten Entwurf für einen späteren
> additiven Funktionsschnitt.

### 12.1 Unterstützte Statements (geplant)

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

### 12.2 Dialekt-Erkennung (geplant)

Der Parser soll den Quell-Dialekt automatisch erkennen:

| Indikator                                       | Erkannter Dialekt       |
| ----------------------------------------------- | ----------------------- |
| `SERIAL`, `BIGSERIAL`, `$$`, `LANGUAGE plpgsql` | PostgreSQL              |
| `AUTO_INCREMENT`, `ENGINE=`, `DELIMITER //`     | MySQL                   |
| `AUTOINCREMENT`, `WITHOUT ROWID`                | SQLite                  |
| `IDENTITY`, `NVARCHAR`, `GO`                    | MS SQL Server (geplant) |
| `NUMBER`, `VARCHAR2`, `PL/SQL`                  | Oracle (geplant)        |

Akzeptierte CLI-Aliase wie `postgres` werden intern auf kanonische Modellwerte normalisiert (`postgresql`, `mysql`, `sqlite`, `mssql`, `oracle`).

### 12.3 Verarbeitungspipeline (geplant)

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
- Keine unauflösbaren zirkulären Abhängigkeiten (Warnung bei erkannten Zyklen)

### 13.3 Typkompatibilitäts-Regeln

- `references`-Spaltentyp muss kompatibel mit der referenzierten Spalte sein
- `default`-Wert muss zum Spaltentyp passen
- `precision` und `scale` müssen bei `decimal` angegeben sein
- `max_length` bei `text` muss positiv sein
- `check`-Expressions dürfen nur auf Spalten der eigenen Tabelle verweisen
- `float_precision` darf nur bei `type: float` gesetzt sein
- `timezone` darf nur bei `type: datetime` gesetzt sein

### 13.4 Spatial-Validierungsregeln (ab 0.5.5)

Diese Regeln pruefen das neutrale Schema selbst (`schema validate`):

| Code | Regel                                                              | Ebene  |
| ---- | ------------------------------------------------------------------ | ------ |
| E120 | Unbekannter `geometry_type`-Wert (nicht in der Allowlist aus §3.2) | Modell |
| E121 | `srid` muss groesser als 0 sein                                    | Modell |

Die folgenden Codes entstehen erst bei `schema generate` und sind
Generator-/Report-Regeln, keine Modellvalidierung:

| Code | Regel                                                                                                            | Ebene     |
| ---- | ---------------------------------------------------------------------------------------------------------------- | --------- |
| E052 | Spatial-Typ kann mit dem gewaehlten Profil nicht generiert werden (z.B. `geometry` bei `--spatial-profile none`) | Generator |
| W120 | SRID-Metadaten konnten nicht vollstaendig in den Zieldialekt uebertragen werden                                  | Generator |

Wichtig: `E120`/`E121` werden von `schema validate` gemeldet.
`E052`/`W120` werden nur von `schema generate` gemeldet und sind Teil des
bestehenden `action_required`- bzw. Warning-Report-Vertrags.

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
3. `SchemaReader` (ab 0.6.0) und `DdlGenerator` in der Driver-API erweitern
4. YAML-Serialisierung ergänzen
5. Ggf. DDL-Parser um neues Statement erweitern (späterer Milestone)

---

## Verwandte Dokumentation

- [Lastenheft](./lastenheft-d-migrate.md) — Vollständige Anforderungsspezifikation (LF-001 bis LF-004)
- [Design](./design.md) — Design-Philosophie, Streaming-Pipeline, KI-Integration
- [Architektur](./architecture.md) — Modul-Struktur, TypeMapper-Implementierung, Driver-SPI
- [DDL-Generierungsregeln](./ddl-generation-rules.md) — Quoting, Statement-Ordering, Dialekt-Besonderheiten
- [CLI-Spezifikation](./cli-spec.md) — Exit-Codes, Fehler-Codes, Kommando-Referenz
- [Roadmap](./roadmap.md) — Phasen, Milestones und Release-Planung
- [Beispiel: Stored Procedure Migration](./beispiel-stored-procedure-migration.md) — KI-gestützte Transformation PostgreSQL → MySQL

---

**Version**: 1.2
**Stand**: 2026-04-13
**Status**: Entwurf — DDL-Parser-Abschnitt (§12) als späterer Milestone markiert; Reverse-Eingabepfad auf Live-DB-first für 0.6.0 bereinigt
