# Implementierungsplan: Milestone 0.2.0 — DDL-Generierung

> Dieses Dokument beschreibt den konkreten Implementierungsplan für Milestone 0.2.0.
> Es dient als Review-Grundlage vor Beginn der Umsetzung.

---

## 1. Ziel

Aus neutralen YAML-Schema-Definitionen datenbankspezifisches DDL für PostgreSQL, MySQL und SQLite generieren.

```
schema.yaml → d-migrate schema generate --target postgresql → CREATE TABLE ...
```

## 2. Neue Module

### 2.1 `d-migrate-driver-api` (Port-Interface)

Reines Interface-Modul. Exportiert `d-migrate-core` transitiv an Consumer.

**Gradle-Plugin**: Das API-Modul verwendet `java-library` statt nur `kotlin("jvm")`, damit `api(project(":d-migrate-core"))` die Core-Typen (SchemaDefinition, NeutralType, etc.) transitiv an die konkreten Treiber weitergibt. Ohne `java-library` sind die in den Interface-Signaturen verwendeten Core-Typen für Consumer nicht sichtbar. Die konkreten Treiber-Module deklarieren deshalb nur `implementation(project(":d-migrate-driver-api"))` und sehen Core-Typen transitiv. Konkrete Build-Files: `d-migrate-driver-api/build.gradle.kts` und `d-migrate-driver-{postgresql,mysql,sqlite}/build.gradle.kts`.

```
d-migrate-driver-api/
└── src/main/kotlin/dev/dmigrate/driver/
    ├── DatabaseDialect.kt        # Enum: POSTGRESQL, MYSQL, SQLITE
    ├── DdlGenerator.kt           # Port-Interface
    ├── DdlStatement.kt           # Statement + zugehörige Notes
    └── TypeMapper.kt             # Port-Interface
```

**`DdlGenerator`** (neues Interface) — `dialect`, `generate(schema)` und `generateRollback(schema)`. Liefert ein `DdlResult` mit geordneten `DdlStatement`s, an denen die `TransformationNote`s direkt hängen (jede Note wird als SQL-Kommentar vor "ihrem" Statement gerendert), plus einer Liste übersprungener Objekte. Implementierung in `d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/` (`DdlGenerator.kt`, `DdlResult.kt`, `DdlStatement.kt`, `TransformationNote.kt`, `NoteType.kt`, `SkippedObject.kt`).

**`TypeMapper`** (neues Interface) — `dialect`, `toSql(NeutralType)` und `toDefaultSql(DefaultValue, NeutralType)`. Implementierung: `d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/TypeMapper.kt`.

### 2.2 `d-migrate-driver-postgresql`

```
d-migrate-driver-postgresql/
└── src/main/kotlin/dev/dmigrate/driver/postgresql/
    ├── PostgresTypeMapper.kt
    ├── PostgresDdlGenerator.kt
    └── PostgresDialect.kt        # Quoting, reservierte Wörter
```

### 2.3 `d-migrate-driver-mysql`

```
d-migrate-driver-mysql/
└── src/main/kotlin/dev/dmigrate/driver/mysql/
    ├── MysqlTypeMapper.kt
    ├── MysqlDdlGenerator.kt
    └── MysqlDialect.kt
```

### 2.4 `d-migrate-driver-sqlite`

```
d-migrate-driver-sqlite/
└── src/main/kotlin/dev/dmigrate/driver/sqlite/
    ├── SqliteTypeMapper.kt
    ├── SqliteDdlGenerator.kt
    └── SqliteDialect.kt
```

## 3. Bestehende Module — Änderungen

### 3.1 `d-migrate-core`

Keine Änderungen am Modell nötig. Alles was der DDL-Generator braucht, existiert bereits:
- `SchemaDefinition`, `TableDefinition`, `ColumnDefinition`, `NeutralType` (18 Typen)
- `IndexDefinition`, `ConstraintDefinition`, `CustomTypeDefinition`
- `PartitionConfig`, `ReferenceDefinition`, `DefaultValue`
- `ViewDefinition`, `FunctionDefinition`, `ProcedureDefinition`, `TriggerDefinition`, `SequenceDefinition`

### 3.2 `d-migrate-cli`

Neues Kommando `SchemaGenerateCommand` in `d-migrate-cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`. Es liest YAML, validiert das Schema, normalisiert den Dialekt, lädt den `DdlGenerator` aus der Registry, schreibt Up- und (optional) Down-Statements sowie den YAML-Report. Optionen: `--source`, `--target`, `--output`, `--report`, `--generate-rollback`.

**Rollback-Ausgabe**: Wenn `--generate-rollback` und `--output` gesetzt:
- `schema.sql` — Up-Statements
- `schema.rollback.sql` — Down-Statements (inverse Reihenfolge)
- `schema.report.yaml` — Transformations-Report

Ohne `--output` werden Up- und Down-Statements beide nach stdout geschrieben, getrennt durch `-- ROLLBACK` Marker.

### 3.3 `d-migrate-formats`

Neuer `TransformationReportWriter` zum Schreiben des Reports als YAML — Implementierung in `d-migrate-formats/src/main/kotlin/dev/dmigrate/format/report/TransformationReportWriter.kt`. Schreibt Source-Pfad, Dialekt, Schema-Metadaten, alle `TransformationNote`s und übersprungenen Objekte. Der Report enthält `source.file` mit dem Pfad zur Schema-Datei, wie in der DDL-Generierungsregeln-Spezifikation §14.2 gefordert.

### 3.4 `settings.gradle.kts`

Die vier neuen Treiber-Module (`d-migrate-driver-api`, `-postgresql`, `-mysql`, `-sqlite`) werden in `settings.gradle.kts` ergänzt.

## 4. Implementierungsreihenfolge

### Phase A: Driver-API-Modul

1. `settings.gradle.kts` um 4 neue Module erweitern
2. `d-migrate-driver-api/build.gradle.kts` — `java-library` Plugin + `api(project(":d-migrate-core"))`
3. `DatabaseDialect.kt` — Enum mit Normalisierung (`postgres` → `POSTGRESQL`)
4. `TypeMapper.kt` — Interface
5. `DdlGenerator.kt` — Interface + `DdlResult`, `DdlStatement`, `TransformationNote`, `SkippedObject`
6. `AbstractDdlGenerator.kt` — Basisklasse mit topologischer Sortierung, Rollback-Invertierung

### Phase B: TypeMapper pro Dialekt

Jeder TypeMapper implementiert die vollständige Typ-Mapping-Tabelle aus neutral-model-spec.md §3:

6. `PostgresTypeMapper` — 18 Typen → PostgreSQL SQL (SERIAL, JSONB, BOOLEAN, etc.)
7. `MysqlTypeMapper` — 18 Typen → MySQL SQL (AUTO_INCREMENT, JSON, TINYINT(1), etc.)
8. `SqliteTypeMapper` — 18 Typen → SQLite SQL (INTEGER, TEXT, REAL, BLOB)
9. `TypeMapperTest` pro Dialekt — **100% Coverage** über alle 18 Typen

### Phase C: DdlGenerator pro Dialekt

Jeder Generator implementiert:
- Header-Kommentar
- Custom Types (CREATE TYPE / inline ENUM / CHECK)
- Sequences (CREATE SEQUENCE / Emulation)
- Tabellen (topologisch sortiert, Spalten, Constraints)
- Indizes (BTREE, HASH, etc. mit Fallback)
- Views (Query-Transformation)
- Functions/Procedures (Hülle + Body/action_required)
- Triggers (inkl. PostgreSQL Trigger-Function-Split)

10. `PostgresDdlGenerator` — Quoting `"`, CREATE TYPE, SERIAL, $$-Blocks
11. `MysqlDdlGenerator` — Quoting `` ` ``, DELIMITER, ENGINE=InnoDB, CHARSET
12. `SqliteDdlGenerator` — Quoting `"`, inline FK, TEXT+CHECK für Enums

### Phase D: CLI-Integration

13. `SchemaGenerateCommand` in `SchemaCommands.kt` registrieren
14. `TransformationReportWriter` in `d-migrate-formats`
15. `OutputFormatter` erweitern für DDL-Generate-Output (JSON/YAML-Format)
16. Dialekt-Registry: Mapping von String → DdlGenerator

### Phase E: Golden-Master-Tests

17. 12 Golden-Master-Dateien erstellen:
    - `minimal.postgresql.sql`, `minimal.mysql.sql`, `minimal.sqlite.sql`
    - `e-commerce.postgresql.sql`, `e-commerce.mysql.sql`, `e-commerce.sqlite.sql`
    - `all-types.postgresql.sql`, `all-types.mysql.sql`, `all-types.sqlite.sql`
    - `full-featured.postgresql.sql`, `full-featured.mysql.sql`, `full-featured.sqlite.sql`
18. `DdlGoldenMasterTest` — 12 parametrisierte Tests
19. CLI-Integration-Tests für `schema generate`

## 5. Abhängigkeiten zwischen Modulen

```
d-migrate-driver-api
└── api(d-migrate-core)              # transitiv: Core-Typen sichtbar für Consumer

d-migrate-driver-postgresql
└── implementation(d-migrate-driver-api)  # Core-Typen kommen transitiv mit

d-migrate-driver-mysql
└── implementation(d-migrate-driver-api)

d-migrate-driver-sqlite
└── implementation(d-migrate-driver-api)

d-migrate-cli
├── implementation(d-migrate-core)
├── implementation(d-migrate-formats)
├── implementation(d-migrate-driver-api)
├── implementation(d-migrate-driver-postgresql)
├── implementation(d-migrate-driver-mysql)
└── implementation(d-migrate-driver-sqlite)
```

**Wichtig**: `d-migrate-driver-api` verwendet das `java-library`-Plugin und exportiert `d-migrate-core` via `api()`. Die konkreten Treiber brauchen deshalb **keine** direkte Dependency auf `d-migrate-core`.

## 6. Zentrale Design-Entscheidungen

### 6.1 Abweichung von der Architektur-Spezifikation

> **Bewusste temporäre Abweichung**: Die Architektur (architecture.md §3.1) beschreibt ein `SchemaWriter`-Interface mit `generateDdl()` und `generateRollback()` plus ServiceLoader-Registrierung. Für 0.2.0 verwenden wir stattdessen ein schlankeres `DdlGenerator`-Interface mit einer einfachen CLI-Registry. Begründung:
>
> - `SchemaWriter` in der Architektur umfasst auch `generateMigration(diff)` — Diff-basierte Migrationen sind erst für 0.5.0 geplant
> - ServiceLoader erfordert META-INF/services-Dateien und erschwert GraalVM Native Image — beides irrelevant solange keine externen Treiber existieren
> - Die Registry wird in 0.6.0 durch ServiceLoader ersetzt, wenn Oracle/MSSQL-Treiber als separate JARs hinzukommen
>
> Das `DdlGenerator`-Interface ist so designed, dass es in Zukunft als Implementierung von `SchemaWriter.generateDdl()` und `SchemaWriter.generateRollback()` wiederverwendet werden kann.

### 6.2 DdlGenerator-Registrierung

Einfache Registry in der CLI (kein ServiceLoader für 0.2.0); Implementierung als private `getGenerator(dialect)`-Funktion in `SchemaCommands.kt`. ServiceLoader wird erst relevant, wenn externe Treiber als JARs hinzugefügt werden (0.6.0+).

### 6.3 DdlGenerator-Architektur

Jeder Generator erbt von der abstrakten Basisklasse `AbstractDdlGenerator` (in `d-migrate-driver-api`), die Ordering, topologische Sortierung und Rollback-Invertierung vorgibt. Reihenfolge in `generate(schema)`: Header → Custom Types → Sequences → Tabellen (topologisch) → Indizes → zirkuläre FK-Constraints → Views → Functions → Procedures → Triggers. `generateRollback(schema)` erzeugt die inverse Reihenfolge und mappt jedes Up-Statement über `invertStatement()` (siehe `ddl-generation-rules.md §12`). Konkrete Dialekte überschreiben `quoteIdentifier`, `generateTable`, `generateCustomTypes`, `handleCircularReferences` u. a. Gemeinsame Helfer (`topologicalSort`, `generateHeader`, Default-`invertStatement`) sowie die Hilfstypen `TopologicalSortResult` und `CircularFkEdge` liegen in der Basisklasse.

Notes werden direkt an `DdlStatement` angehängt (nicht separat gesammelt), sodass die Zuordnung "welche Note gehört vor welches Statement" erhalten bleibt. Beim Rendern erzeugt `DdlStatement.render()` automatisch die Inline-Kommentare vor dem SQL.

### 6.3 Topologische Sortierung und zirkuläre FKs

Tabellen werden nach FK-Abhängigkeiten sortiert. Algorithmus:

1. Abhängigkeitsgraph aus `references`-Feldern aufbauen
2. Kahn's Algorithmus (BFS-basiert) für topologische Sortierung
3. Zirkuläre Referenzen erkennen

**Dialektspezifische Behandlung zirkulärer FKs:**

| Dialekt | Verhalten | Begründung |
|---|---|---|
| PostgreSQL | Zirkuläre FKs als nachträgliches `ALTER TABLE ADD CONSTRAINT` | PostgreSQL unterstützt deferred constraints |
| MySQL | Zirkuläre FKs als nachträgliches `ALTER TABLE ADD CONSTRAINT` | MySQL unterstützt ADD CONSTRAINT |
| SQLite | **Fehler (E019)** — DDL-Generierung bricht für dieses Schema ab | SQLite unterstützt kein `ALTER TABLE ADD CONSTRAINT`; FKs müssen inline sein. Zirkuläre Schemas sind in SQLite nicht darstellbar. |

Die Basisklasse ruft `handleCircularReferences()` auf, das von jedem Dialekt überschrieben wird — PostgreSQL/MySQL erzeugen nachträgliche `ALTER TABLE ADD CONSTRAINT`-Statements, SQLite emittiert Fehler `E019` ("Circular foreign keys not supported for SQLite").

### 6.5 View-Query-Transformation

Einfache String-Substitution (kein SQL-Parser). Implementierung als `ViewQueryTransformer` im Driver-API-Modul; Transformationsregeln aus `ddl-generation-rules.md §8.3` (17 Funktionen).

### 6.6 Quoting-Helfer

Jeder Dialekt liefert sein eigenes Quoting-Verhalten (PostgreSQL/SQLite: `"`, MySQL: `` ` ``); doppelte Quote-Zeichen im Identifier werden verdoppelt. Implementiert in den jeweiligen `*DdlGenerator`-Klassen über `quoteIdentifier(name)`.

## 7. Dateien (geschätzt ~35 neue Dateien)

```
# Neue Module
d-migrate-driver-api/build.gradle.kts
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/DatabaseDialect.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/TypeMapper.kt
d-migrate-driver-api/src/main/kotlin/dev/dmigrate/driver/AbstractDdlGenerator.kt

d-migrate-driver-postgresql/build.gradle.kts
d-migrate-driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt
d-migrate-driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGenerator.kt
d-migrate-driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapperTest.kt
d-migrate-driver-postgresql/src/test/kotlin/dev/dmigrate/driver/postgresql/PostgresDdlGeneratorTest.kt

d-migrate-driver-mysql/build.gradle.kts
d-migrate-driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt
d-migrate-driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt
d-migrate-driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapperTest.kt
d-migrate-driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGeneratorTest.kt

d-migrate-driver-sqlite/build.gradle.kts
d-migrate-driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapper.kt
d-migrate-driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGenerator.kt
d-migrate-driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapperTest.kt
d-migrate-driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/SqliteDdlGeneratorTest.kt

# Geänderte Dateien
settings.gradle.kts                                          # +4 Module
d-migrate-cli/build.gradle.kts                               # +3 Driver Dependencies
d-migrate-cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt  # +SchemaGenerateCommand
d-migrate-formats/src/main/kotlin/dev/dmigrate/format/TransformationReportWriter.kt

# Golden Masters (12 Dateien)
d-migrate-formats/src/test/resources/fixtures/ddl/minimal.postgresql.sql
d-migrate-formats/src/test/resources/fixtures/ddl/minimal.mysql.sql
d-migrate-formats/src/test/resources/fixtures/ddl/minimal.sqlite.sql
d-migrate-formats/src/test/resources/fixtures/ddl/e-commerce.postgresql.sql
d-migrate-formats/src/test/resources/fixtures/ddl/e-commerce.mysql.sql
d-migrate-formats/src/test/resources/fixtures/ddl/e-commerce.sqlite.sql
d-migrate-formats/src/test/resources/fixtures/ddl/all-types.postgresql.sql
d-migrate-formats/src/test/resources/fixtures/ddl/all-types.mysql.sql
d-migrate-formats/src/test/resources/fixtures/ddl/all-types.sqlite.sql
d-migrate-formats/src/test/resources/fixtures/ddl/full-featured.postgresql.sql
d-migrate-formats/src/test/resources/fixtures/ddl/full-featured.mysql.sql
d-migrate-formats/src/test/resources/fixtures/ddl/full-featured.sqlite.sql

# Tests
d-migrate-driver-api/src/test/kotlin/.../DdlGeneratorTest.kt  # Topologische Sortierung
d-migrate-formats/src/test/kotlin/.../DdlGoldenMasterTest.kt  # 12 parametrisierte Tests
d-migrate-cli/src/test/kotlin/dev/dmigrate/cli/CliGenerateTest.kt
```

## 8. Coverage-Ziele

| Modul | Ziel | Schwerpunkt |
|---|---|---|
| d-migrate-driver-api | 90% | Topologische Sortierung, DdlResult |
| d-migrate-driver-postgresql | 90% | TypeMapper 100%, DdlGenerator Kernpfade |
| d-migrate-driver-mysql | 90% | TypeMapper 100%, DELIMITER-Handling |
| d-migrate-driver-sqlite | 90% | TypeMapper 100%, Inline-FK, CHECK-Enums |
| d-migrate-cli | 50% → 60% | +SchemaGenerateCommand Tests |

**TypeMapper-Tests: 100% Coverage** — jeder der 18 neutralen Typen wird in jedem Dialekt getestet.

## 9. Verifikation

```bash
# Kompilierung aller Module
./gradlew build

# TypeMapper-Tests (100% Coverage)
./gradlew :d-migrate-driver-postgresql:test :d-migrate-driver-mysql:test :d-migrate-driver-sqlite:test

# Golden-Master-Tests
./gradlew :d-migrate-formats:test --tests "*GoldenMaster*"

# Coverage
./gradlew :d-migrate-driver-api:koverVerify :d-migrate-driver-postgresql:koverVerify :d-migrate-driver-mysql:koverVerify :d-migrate-driver-sqlite:koverVerify

# CLI Smoke-Test
./gradlew :d-migrate-cli:run --args="schema generate --source d-migrate-formats/src/test/resources/fixtures/schemas/e-commerce.yaml --target postgresql"

# Docker
./gradlew :d-migrate-cli:jibDockerBuild
docker run --rm -v $(pwd):/work dmigrate/d-migrate schema generate --source /work/schema.yaml --target mysql
```

## 10. Risiken

| Risiko | Mitigation |
|---|---|
| Zirkuläre FKs bei PostgreSQL/MySQL | Kahn's Algorithmus erkennt Zyklen; deferred ALTER TABLE ADD CONSTRAINT |
| Zirkuläre FKs bei SQLite | Fehler E019 — SQLite unterstützt kein ADD CONSTRAINT; Schema muss angepasst werden |
| View-Query-Transformation unvollständig | Best-Effort + W111 Warnung bei unbekannten Funktionen |
| DELIMITER-Handling bei MySQL verschachtelt | Pro-Statement-Wrapping, kein Nesting |
| Golden Masters brechen bei Format-Änderungen | Header-Zeilen beim Vergleich ignorieren |

---

**Referenzen**:
- [DDL-Generierungsregeln](../../spec/ddl-generation-rules.md) — Quoting, Ordering, Dialekt-Regeln
- [Neutrales-Modell-Spezifikation](../../spec/neutral-model-spec.md) — Typ-Mapping-Tabelle §3
- [CLI-Spezifikation](../../spec/cli-spec.md) — `schema generate` Command
- [Architektur](../../spec/architecture.md) — Modul-Struktur, TypeMapper §3.4, SchemaWriter §3.1
