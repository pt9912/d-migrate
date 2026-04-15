# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

### Changed

### Fixed

## [0.7.0] - 2026-04-15

### Added

- `d-migrate export flyway` CLI command — generates Flyway SQL migration files (`V<version>__<slug>.sql`, optional `U<version>__<slug>.sql` undo)
- `d-migrate export liquibase` CLI command — generates a versioned XML changelog with deterministic `changeSet` (id from version+slug+dialect, author `d-migrate`, optional `<rollback>` block)
- `d-migrate export django` CLI command — generates a minimal Django `RunSQL` migration with optional `reverse_sql`
- `d-migrate export knex` CLI command — generates a Knex.js CommonJS migration with sequential `knex.raw()` calls and optional `exports.down`
- Tool-neutral migration export contract in `hexagon:ports` (`MigrationBundle`, `MigrationIdentity`, `MigrationDdlPayload`, `MigrationRollback`, `ArtifactRelativePath`, `ToolMigrationExporter`, `ToolExportResult`)
- Adapter-free application helpers: `MigrationIdentityResolver`, `MigrationVersionValidator`, `MigrationSlugNormalizer`, `DdlNormalizer`, `ArtifactCollisionChecker`
- `ToolExportRunner` orchestrator in application layer with 12-step pipeline (schema read, validate, identity resolve, DDL generate, bundle build, exporter delegation, collision check, file write, report sidecar)
- New Gradle module `adapters:driven:integrations` with four `ToolMigrationExporter` implementations (side-effect-free, no tool-runtime dependencies)
- Collision detection: in-run artifact duplicates, existing file collisions (recursive), and report/artifact path overlap — all checked before first write
- YAML report sidecar via `--report` with notes, skippedObjects, rollbackNotes, rollbackSkippedObjects, exportNotes, and artifact paths
- Dockerfile `integration-test` stage with JDK + Python + Django + Node.js for runtime validation
- Runtime validation tests: Flyway→PostgreSQL, Liquibase→PostgreSQL (with rollback), Django→SQLite (with reverse), Knex→SQLite (with rollback)
- Tool export smoke tests in `docs/releasing.md`
- Architecture section 3.6 documenting the full hexagonal export pipeline
- Extensibility section 8.4 for adding new tool exporters

### Changed

- `AbstractDdlGenerator.getVersion()` returns `0.7.0`
- `test-integration-docker.sh` builds from Dockerfile `integration-test` stage (JDK + Python + Django + Node.js) instead of plain JDK image
- `.github/workflows/integration.yml` provisions Python, Django, Node.js, and pnpm for CI
- Django/Knex exporters filter comment-only statements to prevent tool runtime errors

## [0.6.0] - 2026-04-14

### Added

- `d-migrate schema reverse` CLI command — extracts the schema of a live database (PostgreSQL, MySQL, SQLite) into the neutral YAML format with structured notes and optional YAML sidecar report
- `SchemaReader` port interface with `SchemaReadResult` envelope (schema + notes + skipped objects) and `SchemaReadOptions` for object-type filtering (views, functions, procedures, triggers)
- PostgreSQL `SchemaReader`: tables, columns, PKs, FKs, indices, CHECK constraints, sequences, ENUM/DOMAIN/COMPOSITE custom types, views, functions, procedures, triggers, partitioning, extension notes
- MySQL `SchemaReader`: tables with engine metadata, columns, PKs, FKs, indices, CHECK constraints, ENUM columns, views, functions, procedures, triggers, `lower_case_table_names`-aware lookups
- SQLite `SchemaReader`: PRAGMA-based metadata extraction, `WITHOUT ROWID` detection, CHECK constraint regex parser, views, triggers
- `ObjectKeyCodec` for canonical routine keys `name(direction:type,...)` and trigger keys `table::name` with percent-encoding
- `ReverseScopeCodec` for `__dmigrate_reverse__:` prefixed schema names with dialect/database/schema components
- DB-based `schema compare` operands: `file:<path>` and `db:<url-or-alias>` prefix disambiguation with `CompareOperandParser` and `CompareOperandNormalizer` for reverse-marker normalization
- `d-migrate data transfer` CLI command — direct DB-to-DB streaming without intermediate format, with target-authoritative preflight, FK topological sort, and per-chunk commit
- `CustomTypeDiff`, `SequenceDiff`, `FunctionDiff`, `ProcedureDiff`, `TriggerDiff` in core diff engine
- `SchemaComparator` extended for DOMAIN, COMPOSITE, sequences, functions, procedures, triggers
- `TableMetadata` data class with `engine` (MySQL) and `withoutRowid` (SQLite) properties
- `SchemaReadNote` and `SchemaReadSeverity` for reverse-specific diagnostic notes (R300, R310, R320, R330, R400)
- `ReverseReportWriter` for structured YAML sidecar reports
- `SchemaNodeParser` and `SchemaNodeBuilder` for format-agnostic JSON/YAML codec extraction
- `JdbcMetadataSession` and typed metadata projections (TableRef, Column, PK, FK, Index, Constraint) in driver-common
- PostgresTypeMapping, MysqlTypeMapping, SqliteTypeMapping as pure testable type mapping objects
- Release smoke paths for Reverse, Compare (file/db, db/db), and Transfer in `docs/releasing.md`
- `CliDataTransferTest` with flag parsing, validation, and error path coverage

### Changed

- `SchemaDiff`: `enumTypes*` fields renamed to `customTypes*` to support ENUM, DOMAIN, and COMPOSITE types uniformly
- `SchemaValidator`: E008 (missing primary key) downgraded from error to warning
- `DatabaseDriver` interface: `schemaReader()` method added (implemented for all three dialects)
- `MysqlJdbcUrlBuilder`: removed deprecated `useUnicode`/`characterEncoding` properties (Connector/J 9.x), added `allowPublicKeyRetrieval=true`
- `AbstractDdlGenerator.getVersion()` returns `0.6.0`
- Dependency upgrades: PostgreSQL JDBC 42.7.10, MySQL Connector/J 9.6.0, SQLite JDBC 3.51.3.0, Jackson 2.21.2

### Fixed

- PostgreSQL `listSequences`: `cache_size` column referenced from `information_schema.sequences` (does not exist); fixed via LEFT JOIN to `pg_sequences`
- PostgreSQL `readSequences`: `information_schema.sequences` returns varchar values, not numbers; fixed with `toLongOrNull()` helper for mixed Number/String parsing
- PostgreSQL `listForeignKeys`: cartesian product on composite FKs from `constraint_column_usage`; fixed with `pg_constraint` + `unnest(conkey, confkey) WITH ORDINALITY`

## [0.5.5] - 2026-04-13

### Added

- Spatial geometry type (`type: geometry`) in the neutral schema model with `geometry_type` (8 canonical values: geometry, point, linestring, polygon, multipoint, multilinestring, multipolygon, geometrycollection) and `srid` (spatial reference system identifier)
- DDL generation for spatial columns across all three dialects: PostgreSQL/PostGIS (`geometry(Point, 4326)`), MySQL (native spatial types with SRID constraint), SQLite/SpatiaLite (`AddGeometryColumn()`/`DiscardGeometryColumn()` strategy)
- `--spatial-profile` CLI flag for `schema generate` with profiles `postgis`, `native`, `spatialite`, `none` and dialect-appropriate defaults
- Generator options architecture: `DdlGenerationOptions` data class with typed `SpatialProfile` enum, `SpatialProfilePolicy` for central defaults and validation
- Schema validation rules E120 (unknown geometry type) and E121 (invalid SRID); generation-time codes E052 (spatial column cannot be generated with chosen profile) and W120 (SRID transfer limitation)
- YAML codec support for `type: geometry` with lossless parsing of spatial attributes (`geometry_type`, `srid`)
- Spatial Golden Master tests for all three dialects (`spatial.postgresql.sql`, `spatial.mysql.sql`, `spatial.sqlite.sql`)

### Changed

- `DdlGenerator.generate()` and `generateRollback()` extended with `options: DdlGenerationOptions` parameter (backward-compatible with default arguments)
- TypeMapper implementations for all three dialects extended with geometry type handling
- `AbstractDdlGenerator.getVersion()` returns `0.5.5`

## [0.5.0] - 2026-04-13

### Added

- `d-migrate schema compare` CLI command — file-based comparison of two neutral schema definitions with deterministic Plain/JSON/YAML output
- Core-Diff-Engine (`SchemaComparator`) with hierarchical before/after diff model (`SchemaDiff`, `TableDiff`, `ColumnDiff`, `EnumTypeDiff`, `ViewDiff`)
- Single-column UNIQUE/FK normalization to avoid false positives between column-level and constraint-level representations
- Stable DiffView projection for CLI output — primitive-only types, no raw core model leakage
- Exit code contract for `schema compare`: 0 (identical), 1 (different), 2 (CLI error), 3 (invalid schema), 7 (parse/IO error)
- Validation of both schemas before comparison; warnings visible on stderr (plain) or in validation block (JSON/YAML) without changing exit code
- `--output` support for `schema compare` with automatic parent directory creation and input collision detection
- Line-oriented stderr progress display for `data export` and `data import` with event-based reporting (RunStarted, TableStarted, ChunkProcessed, TableFinished)
- `ProgressEvent` sealed interface and `ProgressReporter` fun interface in hexagon:ports for cross-module progress contract
- `ProgressRenderer` in CLI adapter with Locale.US number formatting
- Kover XML-to-JSON coverage report conversion in Docker build stage (via yq)
- GitHub Actions workflow for automated Homebrew tap releases (`release-homebrew.yml`)
- Release packaging with Fat JAR, ZIP, TAR distributions and SHA256 checksums

### Changed

- `cli-spec.md` section 7 updated to reflect actual MVP progress display: line-oriented, sequential single-table, no >2s threshold
- `ExportExecutor` and `ImportExecutor` interfaces extended with `ProgressReporter` parameter
- `--quiet` suppresses progress events and final summary; `--no-progress` suppresses progress events and summary but keeps non-progress stderr output (e.g., export warnings)

## [0.4.0] - 2026-04-12

### Added

- `d-migrate data import` CLI command — transactional import of JSON, YAML, or CSV data into PostgreSQL, MySQL, and SQLite databases
- `DataWriter` / `TableImportSession` port interfaces for hexagonal import pipeline
- PostgreSQL, MySQL, and SQLite data writers with batch INSERT, UPSERT (`--on-conflict update`), and TRUNCATE support
- Streaming import pipeline (chunk-based, transactional, no full-table buffering)
- Schema preflight validation on import (target schema is authoritative)
- Sequence / Identity / AUTO_INCREMENT reseeding after import for all three dialects
- Dialect-specific trigger handling during import (`--trigger-mode disable`)
- `--truncate` flag for clearing target tables before import
- `--on-conflict update` flag for idempotent UPSERT imports
- `--trigger-mode` flag for controlling trigger behavior during import
- Format read path: `DataChunkReader`, `DataChunkReaderFactory`, `ImportOptions` with `JsonChunkReader`, `YamlChunkReader`, `CsvChunkReader` for streaming deserialization
- `EncodingDetector` with BOM sniffing for UTF-8 / UTF-16 BE / UTF-16 LE
- `ValueDeserializer` for JDBC-type-hint based conversion on import
- `DefaultDataChunkReaderFactory` implementation
- `SchemaSync` contract for pre-import schema matching and validation
- `NamedConnectionResolver` extended with `resolveSource` / `resolveTarget`
- Incremental export flags `--since-column` and `--since` on `d-migrate data export` (LF-013), including typed parameter binding and composition with `--filter`
- `DataFilter.ParameterizedClause` and `SelectQuery(sql, params)` for safely bound WHERE fragments
- Testcontainers E2E import tests for PostgreSQL and MySQL
- Driver-level truncate integration tests
- E2E CLI data import tests: JSON/YAML/CSV round-trips, `--truncate`, `--on-conflict update`, `--trigger-mode disable`
- Round-trip tests (export → import → comparison) and incremental round-trip tests (initial export → delta export → UPSERT import → comparison)
- Golden-Master round-trip and null-row property tests

### Changed

- **Hexagonal architecture restructuring**: project reorganized from flat modules into `hexagon/core`, `hexagon/ports`, `hexagon/application`, `adapters/driven/*`, `adapters/driving/cli` — all module paths, imports, and CI workflows updated accordingly
- `DatabaseDriverRegistry` replaces legacy per-dialect registries
- `SchemaGenerateRunner` and `DataExportRunner` extracted from CLI command classes into `hexagon:application`
- `DataExportCommand` extracted into its own file
- `Main.kt` bootstrap split for testability; Help and edge-gap tests added
- `AbstractDdlGenerator.getVersion()` returns `0.4.0`
- CLI Kover coverage gate raised from 60% to 90%
- `d-migrate data export` now rejects literal `?` inside `--filter` when combined with `--since`, returning exit code 2 in the CLI preflight

### Fixed

- `ImportOptions.encoding` defaults to auto-detect (`null`) instead of hard-coded UTF-8
- `EncodingDetector.UnsupportedEncodingException` renamed to `UnsupportedFileEncodingException` to avoid JDK collision
- `ValueDeserializer` distinguishes `NUMERIC` / `DECIMAL` via precision, scale, and token shape instead of forcing every value onto `BigDecimal`
- Large-fixture cache invalidation uses generator source hash instead of manual fingerprint
- Enum type check no longer rejects custom PG enums without `refType`
- `Types.OTHER` cross-contamination in schema type compatibility check
- Fragile `message.startsWith` check replaced with typed exception
- Schema-qualified table names no longer break `--schema` validation
- Directory import now includes `.yml` files for YAML format
- Schema target validation decoupled from application layer for cleaner error handling
- `autoCommit` guard before PostgreSQL `TRUNCATE` in `openTable`
- Writer wiring and cleanup-failure reporting hardened

## [0.3.0] - 2026-04-06

### Added

- `d-migrate data export` CLI command — streaming export of database tables to JSON, YAML, or CSV
- 2 new Gradle modules: `d-migrate-driver-api` (connection layer + data ports), `d-migrate-streaming` (pull-based StreamingExporter)
- Connection layer: `ConnectionConfig`, `ConnectionUrlParser`, `JdbcUrlBuilder` per dialect, HikariCP-backed `HikariConnectionPoolFactory`, `LogScrubber` (password-safe URL masking)
- Hexagonal data ports in `d-migrate-driver-api`: `DataReader`, `TableLister`, `ChunkSequence` (single-use, AutoCloseable, transaction lifecycle in `close()`)
- JDBC `DataReader` + `TableLister` adapters for PostgreSQL (cursor streaming via `setFetchSize` + `autoCommit=false`), MySQL (`useCursorFetch=true`), and SQLite (lazy file read)
- Driver bootstrap objects (`PostgresDriver`, `MysqlDriver`, `SqliteDriver`) for explicit registry registration in `Main.kt`
- Pull-based `StreamingExporter` (chunk-based, no full-table buffering) with `ExportOutput` resolution (`Stdout` / `SingleFile` / `FilePerTable`)
- Format writers in `d-migrate-formats/data/`: `JsonChunkWriter` (DSL-JSON), `YamlChunkWriter` (SnakeYAML Engine), `CsvChunkWriter` (uniVocity-parsers) — chosen over Jackson for streaming throughput
- `ValueSerializer` mapping table (Plan §6.4.1) covering 18+ JDBC types (Numeric, BigInteger/BigDecimal, Date/Time/UUID, BLOB/CLOB, `java.sql.Array` as recursive `SerializedValue.Sequence`)
- `NamedConnectionResolver` (Plan §6.14): minimal `.d-migrate.yaml` loader with CLI > ENV > default-path priority, `${ENV_VAR}` substitution, `$${VAR}` escape, literal substitution (no auto URL-encoding)
- CLI flags for `data export`: `--source` (URL or named connection), `--format` (required), `--output`, `-o`, `--tables`, `--filter`, `--encoding`, `--chunk-size`, `--split-files`, `--csv-delimiter`, `--csv-bom`, `--csv-no-header`, `--null-string`
- `--tables` strict identifier validation (`[A-Za-z_][A-Za-z0-9_]*` optionally schema-qualified) — rejects whitespace, hyphens, SQL injection attempts (Plan §6.7)
- §6.17 empty-table contract: every reader emits at least one chunk with `columns` even when `rows.isEmpty()`; pre-format outputs are `[]` (JSON), `[]` (YAML), header line (CSV), or empty file (`--csv-no-header`)
- Exit-code matrix for `data export`: `0` success, `2` CLI/usage error, `4` connection error, `5` export error, `7` config error
- Testcontainers-based integration tests for PostgreSQL 16 and MySQL 8.0 — both at the driver level and end-to-end via the CLI
- New `.github/workflows/integration.yml` running `./gradlew test koverVerify -PintegrationTests`; default `build.yml` excludes the `integration` Kotest tag and stays under the 5-min CI budget
- `scripts/test-integration-docker.sh` for running integration tests locally in a disposable Docker container
- `docs/archive/implementation-plan-0.3.0.md` (1400+ lines), `docs/releasing.md`
- `data export` section in `docs/cli-spec.md` §6.2 covering all flags, output resolution, exit codes, and 6 example invocations
- 600+ tests across all modules (was 374 in 0.2.0)
- Kover coverage gates: ≥ 90% for all production modules, ≥ 60% for `d-migrate-cli` (per Plan §11)

### Changed

- `AbstractDdlGenerator.getVersion()` now returns `0.3.0`
- Bumped Testcontainers from 1.20.4 to 2.0.4 — module artifacts renamed (`org.testcontainers:postgresql` → `org.testcontainers:testcontainers-postgresql`) and classes relocated (`org.testcontainers.containers.PostgreSQLContainer` → `org.testcontainers.postgresql.PostgreSQLContainer`); resolves Docker Engine ≥ 25.x API negotiation failures
- `d-migrate-cli` Kover gate raised from 50% to 60% (Plan §11) now that CLI integration tests are in place
- LF-013 (incremental export/import) moved from milestone 0.9.0 forward to 0.4.0 in `docs/roadmap.md` — paired with `data import`, no longer requires `SchemaReader`
- Roadmap milestone 0.9.0 split into 0.9.0 (Beta core: Checkpoint/Resume + `--lang`) and 0.9.5 (Beta docs and pilot QA) — different cadences for code vs. documentation work
- `cli-spec.md` §6.2 `data export` updated to reflect the actual 0.3.0 surface (named connections, all CSV flags, exit codes 2/7, 6 example invocations)
- `--quiet` now also suppresses `ValueSerializer` warnings on stderr (per `cli-spec.md` §1.3 — "Nur Fehler"), not just the `ProgressSummary`
- `--no-progress` is now honored by `data export` (previously only `--quiet` suppressed the `ProgressSummary`)

### Fixed

- SQLite `:memory:` URLs are now preserved as in-memory databases instead of being interpreted as filesystem paths
- MySQL `characterEncoding` parameter: use Java charset name `UTF-8` instead of MySQL identifier `utf8mb4`, which Connector/J rejects with `UnsupportedEncodingException`
- `java.sql.Array` is now serialized recursively as a JSON array / YAML sequence per Plan §6.4.1; CSV produces `null` plus a single deduplicated `W201` warning per `(table, column)` tuple
- `BigInteger` and `BigDecimal` are now mapped to separate `SerializedValue` variants — `BigInteger` renders as a YAML number (no precision loss), `BigDecimal` as a quoted string for both JSON and YAML
- `--csv-delimiter` validation now produces a clean exit code 2 with a stderr message instead of a raw `IllegalArgumentException` stack trace
- `StreamingExporter.end()` is no longer called when `begin()` failed, preventing format writers from emitting unmatched closing tokens (e.g. JSON `]` without `[`)
- `JsonChunkWriter` honors `ExportOptions.encoding` via a `CharsetReencodingOutputStream` wrapper when the target encoding is not UTF-8
- `CsvChunkWriter` warnings now carry the real column name instead of `col0`/`col1`
- `Stdout` exports no longer close `System.out` — a `NonClosingOutputStream` wrapper guards against the writer's `close()` propagating to the process-wide stream

## [0.2.0] - 2026-04-06

### Added

- DDL generation for PostgreSQL, MySQL, and SQLite via `d-migrate schema generate --target <dialect>`
- 4 new Gradle modules: `d-migrate-driver-api`, `d-migrate-driver-postgresql`, `d-migrate-driver-mysql`, `d-migrate-driver-sqlite`
- `TypeMapper` interface with full 18-type mapping per dialect (SERIAL, AUTO_INCREMENT, AUTOINCREMENT, JSONB, TINYINT(1), etc.)
- `AbstractDdlGenerator` base class with topological sort (Kahn's algorithm) and rollback generation
- `DdlStatement` model pairing SQL with inline `TransformationNote`s
- `ViewQueryTransformer` with 17 regex-based SQL function transformations between dialects
- `TransformationReportWriter` generating YAML sidecar reports (`<output>.report.yaml`)
- `--output`, `--target`, `--generate-rollback`, `--report` flags on `schema generate`
- `--output-format json` support for `schema generate` with ddl, notes, skipped_objects
- Circular FK handling: PostgreSQL/MySQL via ALTER TABLE ADD CONSTRAINT, SQLite via E019 error
- MySQL DELIMITER wrapping for triggers, functions, procedures
- 12 golden master test files (4 schemas x 3 dialects)
- 374 tests across all modules (was 83 in 0.1.0)
- Kover coverage enforcement >= 90% for all driver modules

### Fixed

- SQL injection prevention: identifier quoting escapes embedded quote characters
- Enum values with single quotes are properly escaped (`''`)

## [0.1.0] - 2026-04-05

### Added

- Gradle multi-module project structure with three modules: `d-migrate-core`, `d-migrate-formats`, `d-migrate-cli`
- `NeutralType` sealed class with 18 database-agnostic types (identifier, text, char, integer, smallint, biginteger, float, decimal, boolean, datetime, date, time, uuid, json, xml, binary, email, enum, array)
- `SchemaDefinition` model for representing database schemas in a neutral format
- `SchemaValidator` with 18 validation error codes (E001 -- E018) covering structural and semantic checks
- `YamlSchemaCodec` for parsing and writing schema definitions in YAML format
- CLI command `schema validate` for validating schema files from the command line
- CLI output format support: plain text, JSON, and YAML (`--output-format`)
- 83 unit and integration tests across all modules
- Kover code coverage enforcement: >= 90% for core and formats modules, >= 50% for CLI module
- Kotlin 2.1.20 with JVM 21 target
- Gradle 8.12 build configuration
- MIT License
