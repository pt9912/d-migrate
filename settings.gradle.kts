rootProject.name = "d-migrate"

// Hexagon (innen)
include("hexagon:core")
include("hexagon:ports-common")
include("hexagon:ports-read")
include("hexagon:ports-write")
include("hexagon:ports-execute")
include("hexagon:ports")
include("hexagon:application")
include("hexagon:profiling")

// Adapters (außen)
include("adapters:driven:driver-common")
include("adapters:driven:driver-postgresql")
include("adapters:driven:driver-postgresql-profiling")
include("adapters:driven:driver-mysql")
include("adapters:driven:driver-mysql-profiling")
include("adapters:driven:driver-sqlite")
include("adapters:driven:driver-sqlite-profiling")
include("adapters:driven:audit-logging")
include("adapters:driven:connection-config")
include("adapters:driven:formats")
include("adapters:driven:formats-parquet")
include("adapters:driven:integrations")
include("adapters:driven:persistence-jdbc")
include("adapters:driven:persistence-memory")
include("adapters:driven:storage-file")
include("adapters:driven:storage-s3")
include("adapters:driven:streaming")
include("adapters:driven:text-icu")
include("adapters:driving:cli")
include("adapters:driving:mcp")

// Integration test modules (Testcontainers, separated from driver unit tests)
include("test:integration-postgresql")
include("test:integration-mysql")
include("test:integration-sqlite")
include("test:integration-server-state")
include("test:integration-integrations")
include("test:integration-persistence-jdbc")
include("test:integration-storage-s3")
include("test:e2e-cli")

// Consumer integration probe (read-only surface verification)
include("test:consumer-read-probe")

// Cross-dialect regression matrix (file-mode sweep, no Testcontainers).
// See docs/planning/done-archive/quality-coverage-expansion-plan.md §5.2.
include("test:cross-dialect-matrix")

// Sequence-Preserve race reproducers (Testcontainers PG/MySQL + file SQLite).
// See docs/planning/done-archive/quality-coverage-expansion-plan.md §5.3.
include("test:integration-concurrency")

// Large-schema scale tests for the SchemaMigrateRenderPipeline.
// See docs/planning/done-archive/quality-coverage-expansion-plan.md §5.4.
include("test:perf-large-schema")
include("test:perf-data-path")
