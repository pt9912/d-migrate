# Kover Excludes Ledger

> Status: Disposition column added (E.1, 2026-05-31)
> Verification: `make coverage-excludes-check`

This ledger lists every active Kover exclude from `build.gradle.kts`.
Each row carries a `Disposition` that classifies the exclude according
to Phase E of
`docs/planning/done-archive/quality-coverage-expansion-plan.md`.

## Disposition vocabulary

The verifier (`scripts/verify-kover-excludes-ledger.py`) fails closed
on two layers:

1. **Gradle-side** — every `kover { ... excludes { ... } }` block in
   every `build.gradle.kts` is parsed for selector calls; selectors
   outside `ALLOWED_GRADLE_SELECTORS = {classes, packages}` (e.g. a
   future `annotatedBy(...)` or `inheritedFrom(...)`) surface as an
   explicit unknown-selector error. Adding a new Kover selector
   means extending both the script's allowlist **and** the ledger
   schema below in the same commit.
2. **Ledger-side** — every row's `Disposition` cell must be present,
   non-empty, and carry one of the three allowed prefixes. Missing/
   empty/unknown values are reported with the offending line.

Allowed Disposition prefixes are exactly three:

- `permanent: <ref>` — the exclude reflects a structural reason that
  will not be refactored away. The `<ref>` is a short keyword from the
  list below (one of `port-contract`, `dto-or-value-carrier`,
  `sealed-outcome`, `cli-command-shell-pattern`, `thin-dispatch-table`)
  **or** an ADR path (`docs/adr/NNNN-*.md`) when a class needs a
  case-specific justification.
- `refactor-plan: <pfad>` — the exclude is acknowledged technical debt;
  `<pfad>` is either a planning document under `docs/planning/` that
  drives the refactor, or the placeholder `TBD` until Sub-Slice E.2
  promotes the entry to a concrete plan or to `permanent:`.
- `aggregate-carveout: <ref>` — used for per-module ledger rows
  (selector `module`, pattern `*`) where a `:test:*`-module is
  intentionally not part of the root Kover aggregate. Allowed `<ref>`
  tokens are `matrix-sweep-runner`, `opt-in-gated-runner` and
  `tag-gated-perf-runner` (one row per current carve-out — extend the
  vocabulary by adding both the row and the token together). The
  verifier rejects `aggregate-carveout:` on `classes`/`packages`
  selectors and rejects any other prefix on selector `module`.

Permanent reference tokens:

| Token | Meaning |
| --- | --- |
| `port-contract` | Port/interface contract (or its Kotlin `$DefaultImpls` helper); behaviour is covered indirectly via adapter and core tests. |
| `dto-or-value-carrier` | Pure data carriers — DTOs, value classes, options/config/metadata/result/note/declaration/enum carriers, thin exception types. |
| `sealed-outcome` | Sealed outcome / sealed result hierarchies and their subtype data carriers. |
| `cli-command-shell-pattern` | Thin Clikt command shells and their private parameter carriers; logic lives in Runner/Wiring (see `feedback_cli_command_refactor_pattern`). |
| `thin-dispatch-table` | Thin dispatch table without executable branches (e.g. `MigrateRendererRegistry`). |

| Module | Selector | Pattern | Disposition | Rationale |
| --- | --- | --- | --- | --- |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.DdlGenerator` | `permanent: port-contract` | Port/interface contract; no executable adapter logic. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.TypeMapper` | `permanent: port-contract` | Port/interface contract; no executable adapter logic. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.connection.PoolSettings` | `permanent: dto-or-value-carrier` | Pure configuration carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.data.DataWriter` | `permanent: port-contract` | Port/interface contract; no executable adapter logic. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.data.SchemaSync` | `permanent: port-contract` | Port/interface contract; no executable adapter logic. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.data.SequenceAdjustment` | `permanent: dto-or-value-carrier` | Pure result/configuration carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.data.TableImportSession` | `permanent: port-contract` | Port/interface contract; no executable adapter logic. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.data.UnsupportedTriggerModeException` | `permanent: dto-or-value-carrier` | Thin exception type. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.ColumnProjection` | `permanent: dto-or-value-carrier` | Pure metadata carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.ConstraintProjection` | `permanent: dto-or-value-carrier` | Pure metadata carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.ForeignKeyProjection` | `permanent: dto-or-value-carrier` | Pure metadata carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.IndexProjection` | `permanent: dto-or-value-carrier` | Pure metadata carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.PrimaryKeyProjection` | `permanent: dto-or-value-carrier` | Pure metadata carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.TableRef` | `permanent: dto-or-value-carrier` | Pure metadata carrier. |
| `:adapters:driven:driver-mysql` | `classes` | `dev.dmigrate.driver.mysql.MysqlDataReader` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Live JDBC adapter; covered through integration paths. |
| `:adapters:driven:driver-mysql` | `classes` | `dev.dmigrate.driver.mysql.MysqlAtomicSequencePreserveExecutor*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Atomic-preserve executor: I/O-bound `SELECT FOR UPDATE` + session-timeout paths covered by `:test:integration-mysql` (root-Kover-aggregated per E.3); pure helpers (classify/ceilDiv) keep unit tests. |
| `:adapters:driven:driver-mysql` | `classes` | `dev.dmigrate.driver.mysql.MysqlDriver` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Driver composition shell; covered through integration paths. |
| `:adapters:driven:driver-postgresql` | `classes` | `dev.dmigrate.driver.postgresql.PostgresDataReader` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Live JDBC adapter; covered through integration paths. |
| `:adapters:driven:driver-postgresql` | `classes` | `dev.dmigrate.driver.postgresql.PostgresDriver` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Driver composition shell; covered through integration paths. |
| `:adapters:driven:driver-sqlite` | `classes` | `dev.dmigrate.driver.sqlite.SqliteAtomicSequencePreserveExecutor*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Atomic-preserve executor: `BEGIN IMMEDIATE` + `PRAGMA busy_timeout` + probe/restore covered by `:test:integration-sqlite` (root-Kover-aggregated per E.3); per-module-Gate sees no JDBC test data. |
| `:adapters:driven:driver-sqlite` | `classes` | `dev.dmigrate.driver.sqlite.SqliteSchemaReader` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Live JDBC schema reader; covered through integration paths. |
| `:adapters:driven:formats` | `classes` | `dev.dmigrate.format.data.yaml.StreamDataWriterAdapter` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Streaming adapter glue; covered via format integration paths. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Postgres-only JDBC adapter; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.job.JdbcJobStartTransaction*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Postgres-only JDBC transaction composition; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.job.JdbcJobStore*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Postgres-only JDBC adapter; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Flyway/Postgres wrapper; covered by integration tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.quota.JdbcOwnerAwareQuotaService*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Postgres-only quota adapter; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaReservationOwnerStore*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Postgres-only quota adapter; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaStore*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Postgres-only quota adapter; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `packages` | `dev.dmigrate.server.persistence.jdbc.quota` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Postgres-only quota stack; covered by integration contract tests. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DataCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DataExportCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DataImportCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DataProfileCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DataTransferCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DefaultMcpServeLauncher*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Blocking in-process MCP server start + retention/finalisation sweep loops; covered via `:test:integration-server-state`. Extracted as an injectable seam from `McpServeRunner` to de-flake cli koverVerify (multi-threaded coverage registration under `org.gradle.parallel`). |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DefaultServerStateFactory*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Hikari/Flyway/Postgres default factory; covered via integration and fake factory unit paths. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportCommandsKt*` | `permanent: cli-command-shell-pattern` | Command helper shell for Clikt dispatch. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportDjangoCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in shared wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportFlywayCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in shared wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportKnexCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in shared wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportLiquibaseCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in shared wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportParams*` | `permanent: cli-command-shell-pattern` | Private parameter carrier for excluded command shell. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.JdbcMigrationExecutor*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | JDBC execution helper; integration-bound. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.McpCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell (mcp parent); subcommand wiring only. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.McpServeCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; option parsing + McpServeOptions mapping, logic in McpServeRunner/DefaultMcpServeLauncher. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.MigrateRendererRegistry*` | `permanent: thin-dispatch-table` | Thin renderer dispatch table. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaCompareCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaGenerateCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaMigrateCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaReverseCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaRollbackCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaValidateCommand*` | `permanent: cli-command-shell-pattern` | Thin Clikt command shell; logic lives in wiring. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SequenceCurrentValueProbeRunner*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Live JDBC/Hikari probe dispatcher; covered by integration paths. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SqliteCastPreflightProbeRunner*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Live JDBC/Hikari probe; covered by integration paths. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SqliteLiveCatalogProbeRunner*` | `refactor-plan: docs/planning/next/adapter-coverage-uplift.md` | Live JDBC/Hikari probe; covered by integration paths. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedCustomType` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedFunction` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedProcedure` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedSequence` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedTable` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedTrigger` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedView` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.ValueChange` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.AggregateDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ColumnDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ConstraintDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ConstraintReferenceDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.CustomTypeDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.DependencyInfo` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.FunctionDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.IndexDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ParameterDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.PartitionConfig` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.PartitionDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ProcedureDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ReferenceDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ReturnType` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.SchemaDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.SequenceDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.TableDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.TableMetadata` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.TriggerDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ViewDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.validation.ValidationWarning` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.artifact.ManagedArtifact` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.connection.ConnectionReference` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.error.ToolErrorDetail` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.error.ToolErrorEnvelope` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.execution.ExecutionMeta` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome$*` | `permanent: sealed-outcome` | Sealed outcome subtype data carriers. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyKey` | `permanent: dto-or-value-carrier` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome$*` | `permanent: sealed-outcome` | Sealed outcome subtype data carriers. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyScope` | `permanent: dto-or-value-carrier` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyState` | `permanent: dto-or-value-carrier` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.InitResumeOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.InitResumeOutcome$*` | `permanent: sealed-outcome` | Sealed outcome subtype data carriers. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.InitResumeScope` | `permanent: dto-or-value-carrier` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome$*` | `permanent: sealed-outcome` | Sealed outcome subtype data carriers. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.SyncEffectScope` | `permanent: dto-or-value-carrier` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.job.JobCancelRequest` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.job.JobError` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.job.JobProgress` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.job.ManagedJob` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.pagination.PageRequest` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.pagination.PageResult` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.principal.PrincipalContext` | `permanent: dto-or-value-carrier` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.principal.PrincipalId` | `permanent: dto-or-value-carrier` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.principal.TenantId` | `permanent: dto-or-value-carrier` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.resource.ServerResourceUri` | `permanent: dto-or-value-carrier` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.upload.UploadSegment` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.upload.UploadSession` | `permanent: dto-or-value-carrier` | Pure server-core DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.TypeMapper` | `permanent: port-contract` | Port/interface contract; no executable adapter logic. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.connection.ConnectionConfig` | `permanent: dto-or-value-carrier` | Pure configuration carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.connection.ConnectionPool` | `permanent: port-contract` | Port/interface contract; no executable adapter logic. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.connection.JdbcUrlBuilder` | `permanent: port-contract` | Port/interface contract; no executable adapter logic. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.connection.JdbcUrlBuilder$DefaultImpls` | `permanent: port-contract` | Kotlin default-impl helper for interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.connection.PoolSettings` | `permanent: dto-or-value-carrier` | Pure configuration carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.data.ResumeMarker` | `permanent: dto-or-value-carrier` | Pure data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.data.ResumeMarker$Position` | `permanent: dto-or-value-carrier` | Pure data carrier subtype. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.format.SchemaCodec` | `permanent: port-contract` | Port/interface contract; no executable adapter logic. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.format.SchemaCodec$DefaultImpls` | `permanent: port-contract` | Kotlin default-impl helper for interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.verify.ValueCanonicalizer` | `permanent: port-contract` | Value-canonicalization port/interface; impl (CanonicalValueCodec) covered in formats tests. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.verify.ValueCanonicalizationException` | `permanent: dto-or-value-carrier` | Thin exception type. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ApprovalGrantStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ArtifactContentStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ArtifactStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ArtifactStore$DefaultImpls` | `permanent: port-contract` | Kotlin default-impl helper for interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.AuditSink` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ConnectionReferenceStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.DiffIndexEntry` | `permanent: dto-or-value-carrier` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.DiffStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.IdempotencyStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStartTransaction` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStartTransactionOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStartTransactionOutcome$Committed` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStartTransactionOutcome$IdempotencyNotEligible` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStore$DefaultImpls` | `permanent: port-contract` | Kotlin default-impl helper for interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobTransitionOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobTransitionOutcome$Applied` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobTransitionOutcome$IllegalTransition` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobTransitionOutcome$NotFound` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ProfileIndexEntry` | `permanent: dto-or-value-carrier` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ProfileStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SchemaIndexEntry` | `permanent: dto-or-value-carrier` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SchemaStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SignalOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SignalOutcome$NotFound` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SignalOutcome$Signaled` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.StdioTokenGrant` | `permanent: dto-or-value-carrier` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.StdioTokenStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SyncEffectIdempotencyStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.TransitionOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.TransitionOutcome$Applied` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.TransitionOutcome$IllegalTransition` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.TransitionOutcome$NotFound` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.UploadSegmentStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.UploadSessionStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.UploadSessionStore$DefaultImpls` | `permanent: port-contract` | Kotlin default-impl helper for interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WorkerHandleRegistry` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteArtifactOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteArtifactOutcome$AlreadyExists` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteArtifactOutcome$Conflict` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteArtifactOutcome$SizeMismatch` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteArtifactOutcome$Stored` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteSegmentOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteSegmentOutcome$AlreadyStored` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteSegmentOutcome$Conflict` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteSegmentOutcome$SizeMismatch` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteSegmentOutcome$Stored` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaCounter` | `permanent: dto-or-value-carrier` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaDimension` | `permanent: dto-or-value-carrier` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaKey` | `permanent: dto-or-value-carrier` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaOutcome` | `permanent: sealed-outcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaOutcome$Granted` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaOutcome$RateLimited` | `permanent: sealed-outcome` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaStore` | `permanent: port-contract` | Server port/interface contract. |
| `:hexagon:ports-execute` | `classes` | `dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult` | `permanent: sealed-outcome` | Sealed outcome marker for the runner-internal protected-execution callback. |
| `:hexagon:ports-execute` | `classes` | `dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult$*` | `permanent: sealed-outcome` | Sealed outcome subtype data carriers. |
| `:hexagon:ports-execute` | `classes` | `dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch` | `permanent: dto-or-value-carrier` | Pure batch data carrier (requests + protected op IDs + internal follow-up IDs). |
| `:hexagon:ports-execute` | `classes` | `dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveExecutor` | `permanent: port-contract` | Port/interface contract; no executable adapter logic. |
| `:hexagon:ports-execute` | `classes` | `dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest` | `permanent: dto-or-value-carrier` | Pure per-sequence preserve-request carrier (ref + render-restore callback). |
| `:hexagon:ports-execute` | `classes` | `dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult` | `permanent: sealed-outcome` | Sealed outcome marker for the execute-time atomic preserve result. |
| `:hexagon:ports-execute` | `classes` | `dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult$*` | `permanent: sealed-outcome` | Sealed outcome subtype data carriers (Applied/NotFound/LockTimeout/Failed). |
| `:hexagon:ports-execute` | `classes` | `dev.dmigrate.driver.migration.preserve.AtomicPreserveSegment` | `permanent: dto-or-value-carrier` | Pure data carrier for the atomic-preserve execute segment (Phase C.2). |
| `:hexagon:ports-execute` | `classes` | `dev.dmigrate.driver.migration.preserve.ExecutableSegment` | `permanent: sealed-outcome` | Sealed segment marker — distinguishes plain-SQL vs. atomic-preserve segments in the segment-aware executor. |
| `:hexagon:ports-execute` | `classes` | `dev.dmigrate.driver.migration.preserve.PlainSqlSegment` | `permanent: dto-or-value-carrier` | Pure data carrier for the plain-SQL execute segment (Phase C.2). |
| `:hexagon:ports-read` | `classes` | `*$DefaultImpls` | `permanent: port-contract` | Kotlin default-impl helpers for interface contracts. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.core.model.DependencyInfo` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.core.model.DependencyProjectionStatus` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.core.model.ViewColumnDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.core.model.ViewDefinition` | `permanent: dto-or-value-carrier` | Pure DTO/data carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.DdlGenerationOptions` | `permanent: dto-or-value-carrier` | Pure options carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.DdlGenerator` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.DdlPhase` | `permanent: dto-or-value-carrier` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.DdlResult` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.DdlStatement` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ExtensionAvailabilityDeclaration` | `permanent: dto-or-value-carrier` | Pure declaration carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ExtensionAvailabilityStatus` | `permanent: dto-or-value-carrier` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ExtensionDependencyReport` | `permanent: dto-or-value-carrier` | Pure report carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ExtensionInstallPolicy` | `permanent: dto-or-value-carrier` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ManualActionRequired` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.MysqlNamedSequenceMode` | `permanent: dto-or-value-carrier` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.NoteType` | `permanent: dto-or-value-carrier` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ReverseSourceKind` | `permanent: dto-or-value-carrier` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ReverseSourceRef` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReadNote` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReadOptions` | `permanent: dto-or-value-carrier` | Pure options carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReadReportInput` | `permanent: dto-or-value-carrier` | Pure report carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReadResult` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReadSeverity` | `permanent: dto-or-value-carrier` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReader` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SkippedObject` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SpatialProfile` | `permanent: dto-or-value-carrier` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SpatialProfilePolicy` | `permanent: port-contract` | Policy contract; behavior covered by core/driver tests. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SpatialProfilePolicy$Result` | `permanent: sealed-outcome` | Sealed result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SpatialProfilePolicy$Result$*` | `permanent: sealed-outcome` | Sealed result subtype carriers. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SqliteCastPreflightDeclaration` | `permanent: dto-or-value-carrier` | Pure declaration carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SqliteCastPreflightStatus` | `permanent: dto-or-value-carrier` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SqliteLiveCatalog` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.TransformationNote` | `permanent: dto-or-value-carrier` | Pure note carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.data.ChunkSequence` | `permanent: port-contract` | Port/result contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.data.DataReader` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.data.TableLister` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.migration.DiffDdlGenerator` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.migration.MigrationBlockedReason` | `permanent: dto-or-value-carrier` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.migration.MigrationBlocker` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.migration.MigrationDdlStatement` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.format.data.DataChunkReader` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.format.data.DataChunkReaderFactory` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.format.data.FormatReadOptions` | `permanent: dto-or-value-carrier` | Pure options carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.DataWriter` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.ImportOptions` | `permanent: dto-or-value-carrier` | Pure options carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.SchemaSync` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.SequenceAdjustment` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.TableImportSession` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.TargetColumn` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.UnsupportedTriggerModeException` | `permanent: dto-or-value-carrier` | Thin exception type. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.WriteResult` | `permanent: dto-or-value-carrier` | Pure result carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.format.data.DataChunkWriter` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.format.data.DataChunkWriterFactory` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.format.data.ExportOptions` | `permanent: dto-or-value-carrier` | Pure options carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.migration.ArtifactRelativePath` | `permanent: dto-or-value-carrier` | Pure value carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.migration.MigrationIdentity` | `permanent: dto-or-value-carrier` | Pure value carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.migration.ToolMigrationExporter` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.streaming.ProgressReporter` | `permanent: port-contract` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.streaming.checkpoint.CheckpointStore` | `permanent: port-contract` | Port/interface contract. |

## Aggregate carve-outs (Sub-Slice E.3)

These rows track `:test:*`-modules that are intentionally **not** part
of the root Kover aggregate in `build.gradle.kts`. Each row uses
selector `module` and disposition prefix `aggregate-carveout:` — the
verifier cross-validates that pairing. Pattern `*` is a placeholder
that signals "the whole module" (matching Kover's wildcard
convention).

| Module | Selector | Pattern | Disposition | Rationale |
| --- | --- | --- | --- | --- |
| `:test:cross-dialect-matrix` | `module` | `*` | `aggregate-carveout: matrix-sweep-runner` | File-mode regression sweep over `SchemaMigrateRunner` across PG/MySQL/SQLite; the runner's production paths are already covered by `hexagon:application` unit specs. Aggregating would add no new coverage. |
| `:test:integration-concurrency` | `module` | `*` | `aggregate-carveout: opt-in-gated-runner` | Concurrent-writer race reproducer gated under `-PintegrationTests -PconcurrencyTests`. Probe→restore production paths are covered by driver-module unit tests; the reproducer only fires under explicit opt-in and would leave the aggregate empty in the default flow. |
| `:test:perf-large-schema` | `module` | `*` | `aggregate-carveout: tag-gated-perf-runner` | Large-schema scale tests gated under `kotest.tags=perf`; never executed in the default coverage flow. Render-pipeline production paths are covered by `hexagon:application`'s `SchemaMigrateRenderPipelinePerfSpec` and unit specs. |
