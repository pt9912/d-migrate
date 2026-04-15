# Design-Dokument: d-migrate

**CLI-Tool für datenbankunabhängige Migrationen und Datenverwaltung**

> Dokumenttyp: Design-Spezifikation
>
> Dieses Dokument ist bewusst ein **Living Design**: Es beschreibt das
> architektonische Zielbild von d-migrate und markiert dort, wo relevant, den
> bereits erreichten Implementierungsstand. Abschnitte mit produktivem Verhalten
> benennen den **Ist-Zustand** explizit; übrige Abschnitte definieren den
> geplanten **Soll-Zustand** für kommende Milestones.
>
> Die aktuelle Codebasis steht auf `0.6.0-SNAPSHOT`. Implementiert sind die
> Milestones 0.1.0 (Schema-Modell, Validierung, YAML-Parsing), 0.2.0
> (DDL-Generierung, Type-Mapping, Rollback), 0.3.0 (Streaming-Datenexport,
> Chunk-Pipeline), 0.4.0 (Datenimport und inkrementelle Datenpfade), 0.5.0
> (file-based `schema compare`) und 0.5.5 (erweitertes Typsystem inkl.
> Spatial-Typen). Abschnitte zu Reverse-Engineering, Direkttransfer,
> Daten-Profiling, KI-Integration und weiteren späteren Features beschreiben
> den geplanten Soll-Zustand.

---

## 1. Design-Philosophie

### 1.1 Leitprinzipien

- **Database-Agnostic First**: Alle internen Datenstrukturen sind datenbankunabhängig. Datenbankspezifisches Verhalten wird ausschließlich in austauschbaren Adaptern gekapselt.
- **Convention over Configuration**: Sinnvolle Defaults für alle Einstellungen, explizite Konfiguration nur wo nötig.
- **Streaming by Default**: Datenverarbeitung erfolgt grundsätzlich streaming-basiert, um beliebig große Datenmengen zu unterstützen.
- **Fail-Safe**: Standardmaessig transaktionale Verarbeitung ohne stillschweigende Teilmigrationen. Best-Effort-Verhalten ist nur explizit konfigurierbar.
- **Privacy by Design**: Lokale Verarbeitung als Standard, externe APIs nur opt-in.

### 1.2 Technologie-Entscheidung: Kotlin/JVM

**Gewählt**: Kotlin 2.1.x auf JVM 21 (LTS)

**Begründung**:
- JDBC bietet die breiteste Datenbankabdeckung aller Plattformen
- Kotlin Coroutines ermöglichen elegante parallele Verarbeitung ohne Thread-Management
- Null-Safety auf Sprachebene — kritisch bei nullable Datenbank-Feldern
- 100% Interoperabilität mit dem Java-Ökosystem (Flyway, Liquibase, Jackson, etc.)
- GraalVM Native Image ermöglicht Single-Binary-Distribution
- DSL-Fähigkeit für intuitive Schema-Definitionen und Konfiguration

---

## 2. Domänenmodell

### 2.1 Neutrales Schema-Modell

Das Herzstück von d-migrate ist ein datenbankunabhängiges Schema-Modell, das als Zwischenformat zwischen allen unterstützten Datenbanken dient. Die vollständige Spezifikation mit YAML-Syntax, Validierungsregeln und Beispielen (PostgreSQL → Neutral → MySQL/SQLite) findet sich in der [Neutrales-Modell-Spezifikation](./neutral-model-spec.md).

```
┌─────────────────────────────────────────────┐
│              SchemaDefinition               │
│  - name: String                             │
│  - version: String                          │
│  - tables: Map<String, TableDefinition>     │
│  - procedures: Map<String, ProcedureDef>    │
│  - functions: Map<String, FunctionDef>      │
│  - views: Map<String, ViewDefinition>       │
│  - triggers: Map<String, TriggerDef>        │
│  - sequences: Map<String, SequenceDef>      │
│  - customTypes: Map<String, CustomTypeDef>  │
└─────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────┐
│              TableDefinition                │
│  - name: String                             │
│  - description: String?                     │
│  - columns: Map<String, ColumnDefinition>   │
│  - primaryKey: List<String>                 │
│  - indices: List<IndexDefinition>           │
│  - constraints: List<ConstraintDefinition>  │
│  - partitioning: PartitionConfig?           │
└─────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────┐
│     ColumnDefinition (Key = Spaltenname)    │
│  - type: NeutralType                        │
│  - required: Boolean                        │
│  - unique: Boolean                          │
│  - default: DefaultValue?                   │
│  - references: ReferenceDefinition?         │
└─────────────────────────────────────────────┘
```

### 2.2 Neutrales Typsystem

Statt datenbankspezifische Typen direkt zu verwenden, definiert d-migrate ein neutrales Typsystem. Typ-Attribute, semantische Typen und Custom Types (Enum, Composite, Domain) sind in der [Neutrales-Modell-Spezifikation §3-5](./neutral-model-spec.md#3-neutrales-typsystem) detailliert beschrieben.

Beziehungen werden dabei nicht als eigener Datentyp modelliert, sondern als Referenz-Metadaten an einer skalaren Spalte (siehe [Neutrales-Modell-Spezifikation §4.2](./neutral-model-spec.md#42-referenzen-foreign-keys)).

| Neutraler Typ         | PostgreSQL                                | MySQL                                    | SQLite                                           |
| --------------------- | ----------------------------------------- | ---------------------------------------- | ------------------------------------------------ |
| `identifier`          | SERIAL / BIGSERIAL                        | INT AUTO_INCREMENT                       | INTEGER PRIMARY KEY AUTOINCREMENT                |
| `text`                | VARCHAR(n) / TEXT                         | VARCHAR(n) / TEXT                        | TEXT                                             |
| `integer`             | INTEGER                                   | INT                                      | INTEGER                                          |
| `biginteger`          | BIGINT                                    | BIGINT                                   | INTEGER                                          |
| `decimal(p,s)`        | DECIMAL(p,s)                              | DECIMAL(p,s)                             | REAL                                             |
| `boolean`             | BOOLEAN                                   | TINYINT(1)                               | INTEGER                                          |
| `datetime`            | TIMESTAMP                                 | DATETIME                                 | TEXT (ISO 8601)                                  |
| `date`                | DATE                                      | DATE                                     | TEXT (ISO 8601)                                  |
| `time`                | TIME                                      | TIME                                     | TEXT (ISO 8601)                                  |
| `uuid`                | UUID                                      | CHAR(36)                                 | TEXT                                             |
| `json`                | JSONB                                     | JSON                                     | TEXT                                             |
| `binary`              | BYTEA                                     | BLOB                                     | BLOB                                             |
| `email`               | VARCHAR(254)                              | VARCHAR(254)                             | TEXT (Singleton, MAX_LENGTH=254)                 |
| `float`               | REAL / DOUBLE PREC.                       | FLOAT / DOUBLE                           | REAL                                             |
| `smallint`            | SMALLINT                                  | SMALLINT                                 | INTEGER                                          |
| `char(n)`             | CHAR(n)                                   | CHAR(n)                                  | TEXT                                             |
| `xml`                 | XML                                       | TEXT (Fallback)                          | TEXT                                             |
| `enum(values)`        | CREATE TYPE ... ENUM                      | ENUM(...)                                | TEXT + CHECK                                     |
| `array(type)`         | type[]                                    | JSON                                     | TEXT (JSON)                                      |
| `geometry(type,srid)` | `geometry(type, srid)` *(PostGIS-Profil)* | native Spatial Types *(Profil `native`)* | `AddGeometryColumn(...)` *(Profil `spatialite`)* |

Das heutige Typsystem deckt die erweiterten Typen `uuid`, `json`, `binary`,
`array` und `geometry` ab. Das
DDL-Ergebnis für `geometry` ist bewusst profilgesteuert (`postgis`, `native`,
`spatialite`, `none`) statt implizit vom Zieldialekt abzuleiten.

### 2.3 Datenfluss-Modell

Reverse-Engineering (LF-004) erfolgt in 0.6.0 ausschließlich über
Live-DB-Verbindungen. Ein DDL-Datei-Parser ist als späterer additiver
Funktionsschnitt vorgesehen, gehört aber nicht zum 0.6.0-Mindestvertrag (siehe
[Neutrales-Modell-Spezifikation §12](./neutral-model-spec.md#12-ddl-parser)).

1. **DB-Connection-basiert** *(0.6.0)*: `SchemaReader` liest Metadaten direkt
   aus der Datenbank via JDBC
2. **DDL-Datei-basiert** *(späterer Milestone)*: DDL-Parser analysiert
   SQL-Dateien (CREATE TABLE, CREATE PROCEDURE, etc.)

```
  Quelle                  Neutral                    Ziel
┌──────────┐         ┌──────────────┐          ┌──────────┐
│PostgreSQL│──JDBC────▶│              │──generate─▶│  MySQL   │
│  MySQL   │ reverse  │   Schema-    │          │  SQLite  │
│  SQLite  │         │   Modell     │◀─parse────│  YAML    │
│          │         │   (Kotlin)   │          │  JSON    │
└──────────┘         └──────┬───────┘          └──────────┘
                            │
                     ┌──────▼──────┐
                     │  Validierung │
                     │  - Syntax    │
                     │  - Referenzen│
                     │  - Typen     │
                     └─────────────┘
```

---

## 3. Datenverarbeitungs-Design

### 3.1 Streaming-Pipeline

Für Datenexport/-import wird eine Streaming-Pipeline verwendet, die Daten chunkweise verarbeitet.

**Heutiger Ist-Zustand**: Pull-basierte Streaming-Pipeline für Export und
Import via `StreamingExporter` und `StreamingImporter`:
```
Source DB ──DataReader──▶ DataChunk ──DataChunkWriter──▶ Output (JSON/YAML/CSV)
                │                          │
            ConnectionPool             ExportOptions
            ChunkSize (10.000)         (Encoding, BOM, Delimiter)

Input (JSON/YAML/CSV/stdin) ──DataChunkReader──▶ DataChunk ──DataWriter──▶ Target DB
                                 │                               │
                             ImportOptions                   Target-Schema
                             ChunkSize (10.000)              Conflict-/Trigger-Modus
```

**Soll-Zustand (spätere Milestones)**: Bidirektionale Pipeline mit Transformation:
```
Source DB ──▶ ResultSet Stream ──▶ Transformer ──▶ Serializer ──▶ Output
                  │                     │               │
                  │              ┌──────▼──────┐        │
                  │              │ Type Mapping │        │
                  │              │ Encoding     │        │
                  │              │ Validation   │        │
                  │              └─────────────┘        │
                  │                                      │
              Chunk-Size                          Format (JSON,
              (Default: 10.000)                   YAML, CSV, SQL)
```

### 3.2 Checkpoint/Resume

Langläufige Operationen erzeugen Checkpoints zur Wiederaufnahme:

```kotlin
// Konzept: Checkpoint-Datei
data class MigrationCheckpoint(
    val operationId: UUID,
    val table: String,
    val orderBy: List<String>,                 // Deterministische Verarbeitungsreihenfolge
    val lastProcessedKey: Map<String, String>, // Serialisierte Resume-Position, auch fuer UUID/String/Composite Keys
    val processedCount: Long,
    val totalCount: Long?,
    val checksum: String,       // SHA-256 der bisher verarbeiteten Daten
    val timestamp: Instant
)
```

Die konkrete Typkonvertierung zwischen `lastProcessedKey` und datenbankspezifischen Schluesseltypen erfolgt im jeweiligen Treiber.

### 3.3 Parallele Verarbeitung

Tabellen werden parallel verarbeitet, wobei Abhängigkeiten (Foreign Keys) respektiert werden:

```
1. Dependency-Graph aufbauen (topologische Sortierung)
2. Unabhängige Tabellen parallel verarbeiten (Coroutines)
3. Abhängige Tabellen sequentiell nach Reihenfolge
4. Konfigurierbare Parallelität (Default: CPU-Kerne)
```

### 3.4 Partitionierte Tabellen (LN-008)

Partitionierte Tabellen werden partition-aware verarbeitet:

```
1. Erkennung der Partitionierung (Typ, Schlüssel, Anzahl) via SchemaReader
2. Export/Import pro Partition als eigenständiger Stream
3. Partitionen werden parallel verarbeitet (unabhängig voneinander)
4. Checkpoint-Granularität: pro Partition statt pro Tabelle
5. Zusammenführung der Ergebnisse nach Abschluss aller Partitionen
```

### 3.5 Inkrementelle Migration (LF-013, LN-006)

Für inkrementelle Exports/Imports wird eine Delta-Erkennung unterstützt.

**Heutiger Ist-Zustand**: `data export` unterstützt `--since-column` +
`--since` für markerbasierte inkrementelle Exports (parametrisierte
WHERE-Klausel über `DataFilter`). `data import` ergänzt den Schreibpfad um
idempotente Konfliktbehandlung via `--on-conflict update`.

**Soll-Zustand**: Vollständige Strategien mit persistiertem Synchronisationszeitpunkt:

**Strategien zur Identifikation geänderter Datensätze**:

| Strategie         | Voraussetzung                                        | Eignung                                      |
| ----------------- | ---------------------------------------------------- | -------------------------------------------- |
| Timestamp-basiert | Spalte `updated_at` vorhanden                        | Schnell, gängigster Fall                     |
| ID-basiert        | Monoton steigende IDs                                | Nur für neue Datensätze (kein Update/Delete) |
| Hash-basiert      | Keine (berechnet SHA-256 pro Zeile)                  | Universell, aber langsamer                   |
| Change-Tracking   | DB-natives CDC (z.B. PostgreSQL Logical Replication) | Performant, aber DB-spezifisch               |

```
Konfiguration:
  incremental:
    strategy: timestamp          # timestamp | id | hash | cdc
    timestamp_column: updated_at # Nur für strategy=timestamp
    last_sync: 2025-10-20T14:00:00Z  # Automatisch verwaltet
```

Das System speichert den letzten Synchronisationszeitpunkt pro Tabelle, sodass nachfolgende Läufe nur geänderte Datensätze verarbeiten.

### 3.6 Daten-Profiling (0.7.5)

Ab Milestone 0.7.5 ergänzt d-migrate den Migrationspfad um ein
deterministisches Profiling bestehender Datenbestände. Anders als
`schema reverse` projiziert Profiling nicht primär in das neutrale
`SchemaDefinition`-Modell, sondern in ein eigenes datenorientiertes
Profil-Modell mit Kennzahlen, Qualitätswarnungen und Zieltyp-Kompatibilität.

Profiling ist damit kein Appendix zum Export/Import, sondern ein eigener
Vorbereitungsbaustein im Zielbild von d-migrate: vor Reverse, Generate,
Transfer und produktiven Migrationsläufen.

**Implementierter Stand (0.7.5)**:

```
Live DB ──▶ Schema-Introspection ──▶ Profiling-Queries ──▶ Warning Rules ──▶ JSON/YAML-Report
              │                         │                     │
              │                         │                     └─ 8 migrationsrelevante Regeln
              │                         └─ Counts, Top-N, Stats, Kompatibilität
              └─ eigene Profiling-Projektion (nicht SchemaReader)
```

- `hexagon:profiling` als eigenes Modul mit Domaenenmodell, Typsystem,
  Rule-Engine und Outbound-Ports
- Drei Dialekt-Adapter (PostgreSQL, MySQL, SQLite) in den bestehenden
  Driver-Modulen
- `DataProfileRunner` in `hexagon:application`, `DataProfileCommand`
  unter `d-migrate data profile`
- JSON (Default) oder YAML-Report via `ProfileReportWriter`
- Determinismus: stabile Reihenfolge, kein `generatedAt`
- `DatabaseDriver` bleibt unveraendert — Profiling-Adapter ueber separaten
  `ProfilingAdapterSet`-Lookup

Nicht Teil von 0.7.5: Query-Profiling, Normalisierungsanalyse,
Struktur-Findings, LLM-Integration. Das groessere Zielbild ist in
[profiling.md](./profiling.md) beschrieben.

---

## 4. KI-Integrations-Design

### 4.1 Provider-Abstraktion

```kotlin
// Einheitliche Schnittstelle für alle KI-Provider
interface AiProvider {
    val name: String
    val isLocal: Boolean

    suspend fun transform(request: TransformRequest): TransformResult
    suspend fun isAvailable(): Boolean
}

data class TransformRequest(
    val sourceCode: String,
    val sourceDialect: DatabaseDialect,
    val targetDialect: DatabaseDialect,
    val context: TransformContext       // Tabellen-Schema, Abhängigkeiten
)

data class TransformResult(
    val targetCode: String,
    val intermediateFormat: String,     // Markdown-Zwischenformat
    val metadata: TransformMetadata,    // Modell, Dauer, Token-Verbrauch
    val warnings: List<String>
)

data class TransformMetadata(
    val provider: String,              // z.B. "ollama", "anthropic"
    val model: String,                 // z.B. "llama3.1:70b", "claude-3-5-sonnet"
    val modelVersion: String?,         // Versionierung für Reproduzierbarkeit (LN-036)
    val durationMs: Long,
    val tokenCount: TokenCount?,       // Input/Output-Token (sofern vom Provider geliefert)
    val timestamp: Instant
)
```

### 4.2 Provider-Hierarchie

```
AiProvider (Interface)
├── OllamaProvider        — Lokale Modelle (Default bei privacy.prefer_local)
├── LmStudioProvider      — Lokale OpenAI-kompatible Runtime
├── OpenAiProvider        — GPT-4 / GPT-4o
├── AnthropicProvider     — Claude
├── XaiProvider           — Grok
├── GoogleProvider        — Gemini
├── VllmProvider          — Self-Hosted vLLM
├── TgiProvider           — Self-Hosted Text Generation Inference
└── NoOpProvider          — Fallback ohne KI (regelbasiert)
```

### 4.3 Datenschutz-Strategie

```
Konfiguration: privacy.prefer_local = true (Default)

1. Prüfe ob lokales Modell verfügbar (Ollama, LM Studio)
2. Falls ja → lokales Modell verwenden
3. Falls nein → Prüfe ob allow_external = true
4. Falls ja → externes API verwenden
5. Falls nein → Fehler mit Hinweis auf Konfiguration
```

### 4.4 KI-Audit-Trail (LN-030, LN-031)

KI-generierter Code wird als solcher gekennzeichnet und für Auditing archiviert:

**Kennzeichnung im generierten Code**:
```sql
-- Generated by d-migrate (AI-assisted)
-- Source: PostgreSQL PL/pgSQL | Target: MySQL
-- Model: ollama/llama3.1:70b | Date: 2025-10-22T14:30:00Z
-- Original: audit/20251022_143000_calculate_order_total_source.sql
CREATE PROCEDURE calculate_order_total(IN p_order_id INT)
...
```

**Persistenter Audit-Trail**:
```
.d-migrate/audit/
├── 20251022_143000_calculate_order_total_source.sql    # Quell-Code
├── 20251022_143000_calculate_order_total_target.sql    # Ziel-Code
├── 20251022_143000_calculate_order_total_spec.md       # Zwischenformat
└── 20251022_143000_calculate_order_total_meta.yaml     # TransformMetadata
```

### 4.5 A/B-Testing für KI-Modelle (LN-036)

Zur Evaluierung verschiedener Modelle kann eine Transformation parallel mit mehreren Providern ausgeführt werden:

```bash
d-migrate transform procedure \
  --source schema.yaml \
  --procedure calculate_order_total \
  --ai-backend ollama,anthropic \
  --compare
```

Das Ergebnis ist ein Vergleichsbericht mit Diff, Syntax-Validierung und optionalem semantischem Test für jedes Modell. Konfigurierbare Parameter (Temperatur, Max-Tokens) ermöglichen kontrollierte Vergleiche.

---

## 5. CLI-Design

### 5.1 Kommando-Struktur

**Heutiger Ist-Zustand**:

```bash
d-migrate <command> <subcommand> [options]

# Schema-Verwaltung
d-migrate schema validate     --source schema.yaml
d-migrate schema generate     --source schema.yaml --target postgresql
d-migrate schema compare      --source file:schema.yaml --target db:staging
d-migrate schema reverse      --source postgres://... --output schema.yaml

# Daten-Management
d-migrate data export         --source staging --format json
d-migrate data import         --source data.json --target local_pg
d-migrate data transfer       --source staging --target local_pg

# Integrations-Export (0.7.0)
d-migrate export flyway       --source schema.yaml --target postgresql --output migrations/
d-migrate export liquibase    --source schema.yaml --target postgresql --output changelog/ --generate-rollback
d-migrate export django       --source schema.yaml --target sqlite --version 0001 --output migrations/
d-migrate export knex         --source schema.yaml --target sqlite --version 20260414120000 --output migrations/

# Daten-Profiling (0.7.5)
d-migrate data profile        --source production --format json
d-migrate data profile        --source postgresql://localhost/mydb --tables users,orders --output profile.yaml --format yaml

# Globale Flags greifen vor dem Command
d-migrate --output-format json schema compare --source a.yaml --target b.yaml
d-migrate --no-progress data import --source ./dump/ --target sqlite:///tmp/app.db
```

Produktiv existieren die Command-Gruppen `schema` und `data`.
`schema compare` unterstuetzt file-based sowie `file/db` und `db/db`
ueber `file:`/`db:`-Operanden. `schema reverse` liest Live-Datenbanken
in das neutrale Format. `data transfer` streamt Daten direkt zwischen
Datenbanken.

**Soll-Zustand (spaetere Milestones)**:

```bash
# Daten-Management
d-migrate data seed           --schema schema.yaml --target postgres://...

# Stored Procedure Migration
d-migrate transform procedure --source schema.yaml --procedure name --ai-backend ollama
d-migrate generate procedure  --source spec.md --target mysql

# Migrations-Rollback (LF-014)
d-migrate schema migrate      --source schema.yaml --target postgres://... --generate-rollback
d-migrate schema rollback     --source rollback-001.sql --target postgres://...

# Validierung
d-migrate validate data       --source data.json --schema schema.yaml
d-migrate validate procedure  --source proc.sql --target mysql://...
```

### 5.2 Output-Design

```
# Fortschrittsanzeige bei langen Operationen
Exporting table 'orders' [████████░░░░░░░░] 52% | 520,000/1,000,000 | ~45s remaining

# Validierungsergebnis
✓ Schema validation passed (12 tables, 87 columns, 15 constraints)
⚠ Warning: Column 'price' uses FLOAT — consider DECIMAL for monetary values
✗ Error: Foreign key 'orders.customer_id' references non-existent table 'clients'
```

### 5.3 Konfiguration

**Heutiger Ist-Zustand**: Die produktive Verbindungsauflösung für
`data export`/`data import` unterstützt direkte URLs, Named Connections aus
`.d-migrate.yaml` sowie explizite Konfigurationspfade via `--config` oder
`D_MIGRATE_CONFIG`.

```
1. Direkte URL in --source / --target            # gewinnt sofort
2. CLI-Config: --config <path>
3. Umgebung: D_MIGRATE_CONFIG
4. Projektdatei: ./.d-migrate.yaml
```

`data import` kann zusätzlich `database.default_target` aus der
Konfigurationsdatei verwenden, wenn `--target` fehlt. Weitere Merge- und
Global-Config-Mechanismen bleiben Design-Zielbild und sind im Detail in der
[Connection- und Konfigurationsspezifikation](./connection-config-spec.md)
beschrieben.

---

## 6. Format-Design

### 6.1 Schema-Definition (YAML)

```yaml
name: "E-Commerce System"
version: "1.0"
encoding: "utf-8"
locale: "de_DE"

tables:
  customers:
    description: "Kundenstammdaten"
    columns:
      id:
        type: identifier
        auto_increment: true
      email:
        type: email
        required: true
        unique: true
      name:
        type: text
        max_length: 100
        required: true
    indices:
      - columns: [email]
        type: btree
        unique: true

  orders:
    columns:
      id:
        type: identifier
        auto_increment: true
      customer_id:
        type: integer
        references: customers.id
        on_delete: restrict
      total_amount:
        type: decimal
        precision: 10
        scale: 2
```

`references` beschreibt eine Beziehung auf Constraint-Ebene. Der Spaltentyp bleibt ein regulaerer neutraler Skalartyp.

### 6.2 Datenexport-Formate

**JSON** (strukturiert, UTF-8):
```json
{
  "table": "customers",
  "schema_version": "1.0",
  "exported_at": "2025-10-22T14:30:00Z",
  "encoding": "UTF-8",
  "records": [
    {"id": 1, "email": "kunde@example.de", "name": "Müller"}
  ]
}
```

**CSV** (maximale Tool-Kompatibilitaet, Metadaten optional als Sidecar-Datei):
```csv
id,email,name
1,kunde@example.de,Müller
```

CSV-Export und -Import unterstuetzen konfigurierbares Encoding sowie optionale BOM-Erzeugung bzw. BOM-Erkennung.

**YAML** (für kleinere Datensätze / Konfiguration):
```yaml
table: customers
records:
  - id: 1
    email: kunde@example.de
    name: Müller
```

Optionale CSV-Sidecar-Metadaten:
```yaml
table: customers
schema_version: "1.0"
encoding: UTF-8
exported_at: 2025-10-22T14:30:00Z
```

### 6.3 Dateiimport und Parsing

- Dateiimporte validieren Daten vor dem Schreiben gegen das neutrale Schema.
- Textformate verwenden standardmaessig UTF-8; UTF-16 und BOM-markierte Dateien werden automatisch erkannt.
- Weitere Encodings wie ISO-8859-1 sind explizit konfigurierbar, damit Importe reproduzierbar bleiben.
- Zeitwerte werden formatunabhaengig als ISO 8601 normalisiert, bevor sie in den Zieldialekt geschrieben werden.
- Der geplante Umgang mit Sequence-/Identity-/`AUTO_INCREMENT`-Folgezustand
  und Trigger-Verhalten beim Datenimport ist im Draft
  [design-import-sequences-triggers.md](./design-import-sequences-triggers.md)
  beschrieben.

---

## 7. Migrations-Rollback (LF-014)

### 7.1 Tool-Export-Rollback (0.7.0)

`d-migrate export flyway|liquibase|django|knex --generate-rollback` erzeugt
tool-spezifische Down-Artefakte auf Basis des bestehenden full-state-
`generateRollback()`-Pfads. Jedes Tool hat sein eigenes Format:

- **Flyway**: `U<version>__<name>.sql` (Undo-Datei)
- **Liquibase**: genau ein versionierter XML-Changelog mit genau einem
  deterministischen `changeSet`; `changeSet.id` wird aus Version, Slug und
  Dialekt abgeleitet, `changeSet.author` ist fuer 0.7.0 der feste
  Exporter-Wert `d-migrate`, Rollback liegt optional im selben
  `<rollback>`-Block; ein bestehender Master-Changelog wird dabei nicht
  mutiert
- **Django**: `reverse_sql` im `RunSQL`-Wrapper
- **Knex**: `exports.down` in der Migrations-Datei

Dies ist ein baseline-/full-state-Rollback — es kehrt die gesamte Schema-
Erstellung um, nicht einen inkrementellen Diff.

### 7.2 Diff-basierter Rollback (späterer Milestone)

Der spätere `schema migrate`-Pfad wird aus einem `DiffResult` die inverse
Operation ableiten:

| Up-Operation       | Down-Operation                                                    |
| ------------------ | ----------------------------------------------------------------- |
| CREATE TABLE       | DROP TABLE                                                        |
| ADD COLUMN         | DROP COLUMN                                                       |
| ADD INDEX          | DROP INDEX                                                        |
| ADD CONSTRAINT     | DROP CONSTRAINT                                                   |
| ALTER COLUMN (Typ) | ALTER COLUMN (alter Typ) — erfordert Speicherung des Vor-Zustands |
| DROP COLUMN        | Warnung: Datenverlust, kein automatischer Rollback                |

Nicht-reversible Operationen (z.B. DROP COLUMN, DROP TABLE) erzeugen eine
Warnung und erfordern explizite Bestätigung. Dieser Pfad wird in einem
späteren Milestone implementiert und ist bewusst nicht Teil von 0.7.0.

---

## 8. Fehlerbehandlung

### 8.1 Fehlerkategorien

```kotlin
sealed class MigrateError {
    // Konfigurationsfehler — vor Ausführung erkennbar
    data class ConfigError(val field: String, val reason: String) : MigrateError()

    // Validierungsfehler — Schema/Daten ungültig
    data class ValidationError(val path: String, val violations: List<Violation>) : MigrateError()

    // Verbindungsfehler — DB nicht erreichbar
    data class ConnectionError(val target: String, val cause: Throwable) : MigrateError()

    // Migrationsfehler — Fehler während Datenverarbeitung
    data class MigrationError(val table: String, val row: Long?, val cause: Throwable) : MigrateError()

    // KI-Fehler — Provider nicht erreichbar oder Transformation fehlgeschlagen
    data class AiError(val provider: String, val cause: Throwable) : MigrateError()
}
```

### 8.2 Fehlerbehandlungsstrategie

| Szenario                         | Verhalten                                                                                  |
| -------------------------------- | ------------------------------------------------------------------------------------------ |
| Einzelner Datensatz fehlerhaft   | Standard: aktuellen Chunk/Tabelle abbrechen; optional Best-Effort: loggen und überspringen |
| Constraint-Verletzung bei Import | Transaktion auf Chunk-Ebene zurückrollen                                                   |
| DB-Verbindung unterbrochen       | Retry mit Backoff (3 Versuche), dann Checkpoint schreiben                                  |
| KI-Provider nicht erreichbar     | Fallback auf nächsten Provider, dann regelbasiert                                          |
| Unbekannter Datentyp             | Warnung + Fallback auf `text`, kein Abbruch                                                |

---

## 9. Internationalisierung (i18n)

### 9.1 Architektur

```
src/main/resources/messages/
├── messages.properties       # Root-/Fallback-Bundle (Englisch)
├── messages_de.properties    # Deutsch
└── messages_en.properties    # optional explizites Englisch-Bundle
```

### 9.2 Sprachauswahl

```
1. D_MIGRATE_LANG
2. LC_ALL
3. LANG
4. i18n.default_locale aus der effektiv aufgeloesten Konfigurationsdatei
5. System-Locale
6. Fallback: Englisch (`en`)
```

Hinweise fuer Milestone 0.8.0:

- 0.8.0 liefert die technische Aufloesungs- und Bundle-Basis.
- Der finale CLI-Nutzervertrag fuer `--lang` als dokumentierte Override-Quelle folgt erst in 0.9.0.
- Die effektive Konfigurationsdatei wird nicht separat fuer i18n "geraten", sondern ueber denselben Pfadvertrag wie die bestehende CLI-Konfiguration bestimmt: `--config` > `D_MIGRATE_CONFIG` > `./.d-migrate.yaml`.

### 9.3 Unicode-Verarbeitung

- Interne Strings als Unicode; UTF-8 ist das Standard-Encoding an Datei-, CLI- und API-Grenzen
- Grapheme-aware String-Längenberechnung via ICU4J
- Unicode-Normalisierung fuer NFC, NFD, NFKC und NFKD; Standardvergleich erfolgt auf NFC-normalisierten Werten
- Unicode-Normalisierung ist Vergleichs-/Metadaten-Utility und keine stille Mutation von Export-/Import-/Transfer-Payloads
- BOM-Erkennung und -Behandlung bei CSV basiert auf dem seit 0.4.0 vorhandenen Unterbau und wird in 0.8.0 als Teil des I18n-Vertrags konsolidiert

### 9.4 Internationale Datenformate

- Temporale Werte werden intern zeitzonenbewusst verarbeitet; Export erfolgt standardmaessig in UTC, Import kann Quell- und Zielzeitzonen explizit konfigurieren.
- Datum, Uhrzeit und Timestamp werden formatuebergreifend auf ISO 8601 normalisiert.
- Geldbetraege werden ueber `decimal(p,s)` und locale-unabhaengige Serialisierung verarbeitet, um Punkt/Komma-Konflikte zu vermeiden.
- Telefonnummern koennen optional gegen E.164 validiert und in kanonischer Form exportiert werden.

Strukturierte JSON-/YAML-Ausgaben bleiben dabei sprachstabil: Schluessel,
Codes und freie Fehlermeldungstexte bleiben fuer 0.8.0 englisch, waehrend nur
menschenlesbare Plain-Text-Ausgaben lokalisiert werden duerfen.

---

## 10. Testbarkeit

### 10.1 Design for Testability

- **Dependency Injection** via Constructor Injection (kein Framework nötig für Core)
- **Interface-basierte Abstraktion** für alle externen Abhängigkeiten (DB, Dateisystem, KI)
- **Pure Functions** für Type-Mapping und Schema-Transformation
- **Determinismus**: Kein versteckter globaler State, Zeitstempel injizierbar

### 10.2 Test-Pyramide

```
         ╱╲
        ╱  ╲         E2E-Tests (CLI gegen Docker-DBs)
       ╱    ╲         ~50 Tests, <15 Min
      ╱──────╲
     ╱        ╲       Integration-Tests (Adapter gegen Testcontainers)
    ╱          ╲       ~200 Tests, <5 Min
   ╱────────────╲
  ╱              ╲    Unit-Tests (Pure Logic, Type-Mapping, Parsing)
 ╱                ╲    ~1000 Tests, <2 Min
╱──────────────────╲
```

### 10.3 Test-Infrastruktur

- **Unit-Tests**: Kotest + Jqwik (Property-Based Testing)
- **Integration-Tests**: Testcontainers (PostgreSQL, MySQL)
- **E2E-Tests**: CLI-Prozess gegen Docker-Datenbanken
- **Fixtures**: Versioniert in `adapters/driven/formats/src/test/resources/fixtures/`

---

## 11. Versionierung und Kompatibilität

### 11.1 Schema-Versionierung

```yaml
# Jede Schema-Datei hat eine Version
schema_format: "1.0"    # Format-Version von d-migrate
name: "My Schema"
version: "2.3.1"         # Anwendungs-Schema-Version
```

### 11.2 Kompatibilitätsmatrix

- **Schema-Format**: Rückwärtskompatibel für 2 Major-Versionen
- **CLI-Argumente**: Deprecated Flags bleiben 2 Minor-Versionen erhalten
- **Export-Formate**: Stabile Formate ab 1.0; JSON/YAML versionieren Metadaten im Dokument, CSV optional ueber Sidecar-Datei

---

## Verwandte Dokumentation

- [Lastenheft](./lastenheft-d-migrate.md) — Vollständige Anforderungsspezifikation
- [Architektur](./architecture.md) — Modul-Struktur, Komponenten, Build und Distribution
- [Neutrales-Modell-Spezifikation](./neutral-model-spec.md) — YAML-Format, Typsystem, DDL-Parser, Validierung
- [CLI-Spezifikation](./cli-spec.md) — Exit-Codes, Ausgabeformate, Kommando-Referenz
- [DDL-Generierungsregeln](./ddl-generation-rules.md) — Quoting, Statement-Ordering, Dialekt-Besonderheiten
- [Connection- und Konfigurationsspezifikation](./connection-config-spec.md) — URL-Format, `.d-migrate.yaml`-Schema
- [Roadmap](./roadmap.md) — Phasen, Milestones und Release-Planung
- [Profiling](./profiling.md) — Design für deterministisches Datenbank-Profiling ab 0.7.5
- [Beispiel: Stored Procedure Migration](./beispiel-stored-procedure-migration.md) — KI-gestützte Transformation PostgreSQL → MySQL

---

**Version**: 2.1
**Stand**: 2026-04-14
**Status**: Living Design mit expliziten Ist-/Soll-Markierungen; implementiert sind 0.1.0–0.7.0 (Tool-Integrationen) und 0.7.5 (Daten-Profiling)
