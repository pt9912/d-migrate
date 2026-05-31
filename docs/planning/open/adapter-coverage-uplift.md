# Adapter-Coverage-Uplift (Folge-Plan zu Phase E.2)

- **Status**: Draft (Trigger registriert, kein Scope-Schnitt)
- **Trigger**: Sub-Slice E.2 des
  [`quality-coverage-expansion-plan`](../in-progress/quality-coverage-expansion-plan.md)
  hat 19 heutige Kover-Excludes auf Live-JDBC- bzw. Live-Streaming-
  Adapter-Glue identifiziert, die im
  [Excludes-Ledger](../../coverage/excludes-ledger.md) bisher nur als
  `refactor-plan: TBD` standen. E.2 hat sie auf diesen Plan-Doc-Pfad
  promotet, damit der Verifier keine `TBD`-Platzhalter mehr toleriert;
  die tatsaechliche Coverage-Uplift-Umsetzung ist bewusst aus
  `quality-coverage-expansion-plan` herausgeschnitten und faellt in
  diesen Folge-Plan.
- **Aktivierungsbedingung**: Sobald ein Scope-Schnitt mit Ziel-Coverage
  pro Modul, Strategie (Testcontainers-/Fixture-/Splitting-Refactor)
  und Per-Modul-Akzeptanzkriterien steht, wandert das Dokument nach
  `../next/`.

## Betroffene Excludes (Snapshot 2026-05-31)

Quelle: `docs/coverage/excludes-ledger.md`, Dispositionsspalte
`refactor-plan: docs/planning/open/adapter-coverage-uplift.md`.

### `:adapters:driven:driver-mysql`

- `dev.dmigrate.driver.mysql.MysqlDataReader` — Live JDBC adapter; heute
  ueber `:test:integration-mysql` integration-gedeckt.
- `dev.dmigrate.driver.mysql.MysqlDriver` — Driver-Composition-Shell;
  integration-gedeckt.

### `:adapters:driven:driver-postgresql`

- `dev.dmigrate.driver.postgresql.PostgresDataReader` — Live JDBC
  adapter; integration-gedeckt ueber `:test:integration-postgresql`.
- `dev.dmigrate.driver.postgresql.PostgresDriver` — Driver-Composition-
  Shell; integration-gedeckt.

### `:adapters:driven:driver-sqlite`

- `dev.dmigrate.driver.sqlite.SqliteSchemaReader` — Live JDBC Schema-
  Reader, heute mit „edge cases requiring exotic real-world schemas"
  begruendet; §5.5 des `quality-coverage-expansion-plan`s benennt einen
  Splittungs-Plan parallel zur Cross-Dialekt-Matrix (Phase B) als
  bevorzugte Strategie.

### `:adapters:driven:persistence-jdbc`

Postgres-only JDBC-Stack mit JSONB-, `SELECT FOR UPDATE`-,
`INSERT … ON CONFLICT … RETURNING`- und `jsonb`-Spezifika; gedeckt
durch Contract-Tests in `:test:integration-server-state` unter
`-PintegrationTests`:

- `dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner*`
- `dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore*`
- `dev.dmigrate.server.persistence.jdbc.job.JdbcJobStore*`
- `dev.dmigrate.server.persistence.jdbc.job.JdbcJobStartTransaction*`
- `dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaStore*`
- `dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaReservationOwnerStore*`
- `dev.dmigrate.server.persistence.jdbc.quota.JdbcOwnerAwareQuotaService*`
- `packages("dev.dmigrate.server.persistence.jdbc.quota")` — Paket-
  weiter Backstop, der zusaetzlich `QuotaJson` (Wire-Codec) mitnimmt;
  die Wire-Versionierung koppelt mit der Schema-Version, daher heute
  zusammen mit dem Quota-Stack exkludiert.

### `:adapters:driven:formats`

- `dev.dmigrate.format.data.yaml.StreamDataWriterAdapter` — Streaming-
  Adapter-Glue, ueber Format-Integration-Pfade gedeckt.

### `:adapters:driving:cli`

Live-JDBC-/Hikari-/Flyway-Glue im CLI-Command-Layer; nicht durch das
CLI-Command-Shell-Pattern erfasst, sondern eigenstaendige Composition-
und Probe-Helfer:

- `dev.dmigrate.cli.commands.DefaultServerStateFactory*` — Hikari/
  Flyway/Postgres-Default-Factory.
- `dev.dmigrate.cli.commands.JdbcMigrationExecutor*` — JDBC-Execution-
  Helper, integration-bound.
- `dev.dmigrate.cli.commands.SequenceCurrentValueProbeRunner*` — Live-
  JDBC-/Hikari-Probe-Dispatcher.
- `dev.dmigrate.cli.commands.SqliteCastPreflightProbeRunner*` — Live-
  JDBC-/Hikari-Probe.
- `dev.dmigrate.cli.commands.SqliteLiveCatalogProbeRunner*` — Live-
  JDBC-/Hikari-Probe.

## Ungelöste Fragen

- Strategie pro Modul: Testcontainers-getragene Kover-Aufnahme vs.
  Splitting in dünne Composition-Shells plus testbare Kerne.
- Reihenfolge: vermutlich startet `:adapters:driven:driver-sqlite`
  (in-process testbar, kein Container-Aufwand), gefolgt vom
  Postgres-Stack (`:test:integration-postgresql` ist bereits im
  Root-Kover-Aggregat) und schliesst mit MySQL.
- Verhaeltnis zum geplanten `persistence-jdbc-mig`-Slice unter
  [`../next/persistence-jdbc-mig.md`](../next/persistence-jdbc-mig.md):
  ggfs. erhoehte Kopplung pruefen.

## Nicht-Ziel

- Mutation-Testing (eigener Folge-Plan, siehe §9 des Umbrella-Plans).
- Aenderungen am Kover-Excludes-Vertrag selbst (E.1-/E.3-Scope).
