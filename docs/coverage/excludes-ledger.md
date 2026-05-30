# Kover Excludes Ledger

> Status: Initial inventory (2026-05-30)
> Verification: `make coverage-excludes-check`

This ledger lists every active Kover exclude from `build.gradle.kts`.
The first inventory keeps the rationale concise and points at the
module-local Gradle comments where the technical context lives. Phase E
of `docs/planning/in-progress/quality-coverage-expansion-plan.md`
audits which entries are temporary refactor debt and which are permanent
contract/DTO exclusions.

| Module | Selector | Pattern | Rationale |
| --- | --- | --- | --- |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.DdlGenerator` | Port/interface contract; no executable adapter logic. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.TypeMapper` | Port/interface contract; no executable adapter logic. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.connection.PoolSettings` | Pure configuration carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.data.DataWriter` | Port/interface contract; no executable adapter logic. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.data.SchemaSync` | Port/interface contract; no executable adapter logic. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.data.SequenceAdjustment` | Pure result/configuration carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.data.TableImportSession` | Port/interface contract; no executable adapter logic. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.data.UnsupportedTriggerModeException` | Thin exception type. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.ColumnProjection` | Pure metadata carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.ConstraintProjection` | Pure metadata carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.ForeignKeyProjection` | Pure metadata carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.IndexProjection` | Pure metadata carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.PrimaryKeyProjection` | Pure metadata carrier. |
| `:adapters:driven:driver-common` | `classes` | `dev.dmigrate.driver.metadata.TableRef` | Pure metadata carrier. |
| `:adapters:driven:driver-mysql` | `classes` | `dev.dmigrate.driver.mysql.MysqlDataReader` | Live JDBC adapter; covered through integration paths. |
| `:adapters:driven:driver-mysql` | `classes` | `dev.dmigrate.driver.mysql.MysqlDriver` | Driver composition shell; covered through integration paths. |
| `:adapters:driven:driver-postgresql` | `classes` | `dev.dmigrate.driver.postgresql.PostgresDataReader` | Live JDBC adapter; covered through integration paths. |
| `:adapters:driven:driver-postgresql` | `classes` | `dev.dmigrate.driver.postgresql.PostgresDriver` | Driver composition shell; covered through integration paths. |
| `:adapters:driven:driver-sqlite` | `classes` | `dev.dmigrate.driver.sqlite.SqliteSchemaReader` | Live JDBC schema reader; covered through integration paths. |
| `:adapters:driven:formats` | `classes` | `dev.dmigrate.format.data.yaml.StreamDataWriterAdapter` | Streaming adapter glue; covered via format integration paths. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore*` | Postgres-only JDBC adapter; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.job.JdbcJobStartTransaction*` | Postgres-only JDBC transaction composition; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.job.JdbcJobStore*` | Postgres-only JDBC adapter; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner*` | Flyway/Postgres wrapper; covered by integration tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.quota.JdbcOwnerAwareQuotaService*` | Postgres-only quota adapter; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaReservationOwnerStore*` | Postgres-only quota adapter; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `classes` | `dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaStore*` | Postgres-only quota adapter; covered by integration contract tests. |
| `:adapters:driven:persistence-jdbc` | `packages` | `dev.dmigrate.server.persistence.jdbc.quota` | Postgres-only quota stack; covered by integration contract tests. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DataCommand*` | Thin Clikt command shell. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DataExportCommand*` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DataImportCommand*` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DataProfileCommand*` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DataTransferCommand*` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.DefaultServerStateFactory*` | Hikari/Flyway/Postgres default factory; covered via integration and fake factory unit paths. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportCommand*` | Thin Clikt command shell. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportCommandsKt*` | Command helper shell for Clikt dispatch. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportDjangoCommand*` | Thin Clikt command shell; logic lives in shared wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportFlywayCommand*` | Thin Clikt command shell; logic lives in shared wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportKnexCommand*` | Thin Clikt command shell; logic lives in shared wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportLiquibaseCommand*` | Thin Clikt command shell; logic lives in shared wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.ExportParams*` | Private parameter carrier for excluded command shell. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.JdbcMigrationExecutor*` | JDBC execution helper; integration-bound. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.MigrateRendererRegistry*` | Thin renderer dispatch table. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaCommand*` | Thin Clikt command shell. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaCompareCommand*` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaGenerateCommand*` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaMigrateCommand*` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaReverseCommand*` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaRollbackCommand*` | Thin Clikt command shell; logic lives in wiring/runner. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SchemaValidateCommand*` | Thin Clikt command shell; logic lives in wiring. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SequenceCurrentValueProbeRunner*` | Live JDBC/Hikari probe dispatcher; covered by integration paths. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SqliteCastPreflightProbeRunner*` | Live JDBC/Hikari probe; covered by integration paths. |
| `:adapters:driving:cli` | `classes` | `dev.dmigrate.cli.commands.SqliteLiveCatalogProbeRunner*` | Live JDBC/Hikari probe; covered by integration paths. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedCustomType` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedFunction` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedProcedure` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedSequence` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedTable` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedTrigger` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.NamedView` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.diff.ValueChange` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ColumnDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ConstraintDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ConstraintReferenceDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.CustomTypeDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.DependencyInfo` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.FunctionDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.IndexDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ParameterDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.PartitionConfig` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.PartitionDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ProcedureDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ReferenceDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ReturnType` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.SchemaDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.SequenceDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.TableDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.TableMetadata` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.TriggerDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.model.ViewDefinition` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.core.validation.ValidationWarning` | Pure DTO/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.artifact.ManagedArtifact` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.connection.ConnectionReference` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.error.ToolErrorDetail` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.error.ToolErrorEnvelope` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.execution.ExecutionMeta` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome$*` | Sealed outcome subtype data carriers. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyKey` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome$*` | Sealed outcome subtype data carriers. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyScope` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.IdempotencyState` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.InitResumeOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.InitResumeOutcome$*` | Sealed outcome subtype data carriers. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.InitResumeScope` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome$*` | Sealed outcome subtype data carriers. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.idempotency.SyncEffectScope` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.job.JobCancelRequest` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.job.JobError` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.job.JobProgress` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.job.ManagedJob` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.pagination.PageRequest` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.pagination.PageResult` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.principal.PrincipalContext` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.principal.PrincipalId` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.principal.TenantId` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.resource.ServerResourceUri` | Pure server-core value carrier. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.upload.UploadSegment` | Pure server-core DTO. |
| `:hexagon:core` | `classes` | `dev.dmigrate.server.core.upload.UploadSession` | Pure server-core DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.TypeMapper` | Port/interface contract; no executable adapter logic. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.connection.ConnectionConfig` | Pure configuration carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.connection.ConnectionPool` | Port/interface contract; no executable adapter logic. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.connection.JdbcUrlBuilder` | Port/interface contract; no executable adapter logic. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.connection.JdbcUrlBuilder$DefaultImpls` | Kotlin default-impl helper for interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.connection.PoolSettings` | Pure configuration carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.data.ResumeMarker` | Pure data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.driver.data.ResumeMarker$Position` | Pure data carrier subtype. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.format.SchemaCodec` | Port/interface contract; no executable adapter logic. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.format.SchemaCodec$DefaultImpls` | Kotlin default-impl helper for interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ApprovalGrantStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ArtifactContentStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ArtifactStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ArtifactStore$DefaultImpls` | Kotlin default-impl helper for interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.AuditSink` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ConnectionReferenceStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.DiffIndexEntry` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.DiffStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.IdempotencyStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStartTransaction` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStartTransactionOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStartTransactionOutcome$Committed` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStartTransactionOutcome$IdempotencyNotEligible` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobStore$DefaultImpls` | Kotlin default-impl helper for interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobTransitionOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobTransitionOutcome$Applied` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobTransitionOutcome$IllegalTransition` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.JobTransitionOutcome$NotFound` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ProfileIndexEntry` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.ProfileStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SchemaIndexEntry` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SchemaStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SignalOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SignalOutcome$NotFound` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SignalOutcome$Signaled` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.StdioTokenGrant` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.StdioTokenStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.SyncEffectIdempotencyStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.TransitionOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.TransitionOutcome$Applied` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.TransitionOutcome$IllegalTransition` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.TransitionOutcome$NotFound` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.UploadSegmentStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.UploadSessionStore` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.UploadSessionStore$DefaultImpls` | Kotlin default-impl helper for interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WorkerHandleRegistry` | Server port/interface contract. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteArtifactOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteArtifactOutcome$AlreadyExists` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteArtifactOutcome$Conflict` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteArtifactOutcome$SizeMismatch` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteArtifactOutcome$Stored` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteSegmentOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteSegmentOutcome$AlreadyStored` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteSegmentOutcome$Conflict` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteSegmentOutcome$SizeMismatch` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.WriteSegmentOutcome$Stored` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaCounter` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaDimension` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaKey` | Pure server-port DTO. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaOutcome` | Sealed outcome marker/data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaOutcome$Granted` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaOutcome$RateLimited` | Sealed outcome subtype data carrier. |
| `:hexagon:ports-common` | `classes` | `dev.dmigrate.server.ports.quota.QuotaStore` | Server port/interface contract. |
| `:hexagon:ports-read` | `classes` | `*$DefaultImpls` | Kotlin default-impl helpers for interface contracts. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.core.model.DependencyInfo` | Pure DTO/data carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.core.model.DependencyProjectionStatus` | Pure DTO/data carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.core.model.ViewColumnDefinition` | Pure DTO/data carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.core.model.ViewDefinition` | Pure DTO/data carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.DdlGenerationOptions` | Pure options carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.DdlGenerator` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.DdlPhase` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.DdlResult` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.DdlStatement` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ExtensionAvailabilityDeclaration` | Pure declaration carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ExtensionAvailabilityStatus` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ExtensionDependencyReport` | Pure report carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ExtensionInstallPolicy` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ManualActionRequired` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.MysqlNamedSequenceMode` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.NoteType` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ReverseSourceKind` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.ReverseSourceRef` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReadNote` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReadOptions` | Pure options carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReadReportInput` | Pure report carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReadResult` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReadSeverity` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SchemaReader` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SkippedObject` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SpatialProfile` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SpatialProfilePolicy` | Policy contract; behavior covered by core/driver tests. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SpatialProfilePolicy$Result` | Sealed result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SpatialProfilePolicy$Result$*` | Sealed result subtype carriers. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SqliteCastPreflightDeclaration` | Pure declaration carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SqliteCastPreflightStatus` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.SqliteLiveCatalog` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.TransformationNote` | Pure note carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.data.ChunkSequence` | Port/result contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.data.DataReader` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.data.TableLister` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.migration.DiffDdlGenerator` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.migration.MigrationBlockedReason` | Pure enum/value carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.migration.MigrationBlocker` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.driver.migration.MigrationDdlStatement` | Pure result carrier. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.format.data.DataChunkReader` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.format.data.DataChunkReaderFactory` | Port/interface contract. |
| `:hexagon:ports-read` | `classes` | `dev.dmigrate.format.data.FormatReadOptions` | Pure options carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.DataWriter` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.ImportOptions` | Pure options carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.SchemaSync` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.SequenceAdjustment` | Pure result carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.TableImportSession` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.TargetColumn` | Pure result carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.UnsupportedTriggerModeException` | Thin exception type. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.driver.data.WriteResult` | Pure result carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.format.data.DataChunkWriter` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.format.data.DataChunkWriterFactory` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.format.data.ExportOptions` | Pure options carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.migration.ArtifactRelativePath` | Pure value carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.migration.MigrationIdentity` | Pure value carrier. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.migration.ToolMigrationExporter` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.streaming.ProgressReporter` | Port/interface contract. |
| `:hexagon:ports-write` | `classes` | `dev.dmigrate.streaming.checkpoint.CheckpointStore` | Port/interface contract. |
