# d-migrate

**Database-agnostic tool for schema migration and data management.**

**English** | [Deutsch](README.de.md)

![Build](https://github.com/pt9912/d-migrate/actions/workflows/build.yml/badge.svg)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-purple.svg)

d-migrate is a database-agnostic tool for schema migration and data
management, usable as a CLI **and** as an MCP server
(`mcp serve --transport stdio|http`, MCP 2025-11-25). You define your
schema once in a neutral YAML format and then validate, compare,
generate DDL, and execute live diff-based migrations against
PostgreSQL, MySQL, and SQLite. d-migrate also covers reverse
engineering of existing databases, streaming-based data
export/import/transfer between databases, and export to existing
migration toolchains (Flyway, Liquibase, Django, Knex).

## Who is it for?

d-migrate targets database administrators, platform engineers, data
teams, and integrators who:

- need a **dialect-agnostic** schema artefact (PostgreSQL / MySQL /
  SQLite from the same YAML source)
- want **reproducible, signed migration plans** with explicit rollback
  contracts, drift checks, and per-statement metadata
- run schema and data operations against existing databases —
  including reverse engineering, comparison, transfer, and
  incremental export — **without locking into a single vendor's
  tooling**
- need an **AI-agent-callable MCP server** for read-only schema
  discovery (validate / compare / generate / reverse) plus
  policy-gated job workers for data import / transfer / profile

It is not (yet) an ETL platform, a streaming-CDC pipeline, or a
replacement for hand-tuned dialect-specific migrations — but it
captures the schema and data work that's common across these
stacks.

## What can I run today?

d-migrate is a working production tool at version **0.9.11**
(stable, [released 2026-07-12](https://github.com/pt9912/d-migrate/releases/tag/v0.9.11)).

> **New in 0.9.11:** end-to-end SHA-256 data-integrity verification
> (`data transfer --verify`, LN-009) reconciling source and target with a
> dialect-neutral, order-independent per-table checksum; first-class SSL/TLS
> connection config (LN-026) and CLI audit logging (LN-027); plus a `data
> profile` crash fix — profiling a column with integer values or an empty table
> no longer aborts with a `ClassCastException`. See `CHANGELOG.md`.

The current capabilities:

- **Schema model**: neutral YAML schema with 19 types + Spatial
  Geometry; validator with 35+ error codes.
- **Schema operations**: `validate`, `generate`, `compare`,
  `reverse`, `migrate`, `rollback` for PostgreSQL, MySQL, SQLite —
  file/file, file/db, db/db.
- **Diff migrations**: tables, columns, indexes, constraints incl.
  CHECK/EXCLUDE with live-data preflight, foreign keys, sequences,
  views, materialized views (PG), triggers, functions/procedures;
  signed `migration-plan.v1` artefact via `--plan-artefact`.
- **Renames** for tables, columns, views, triggers, functions,
  procedures, sequences — native `RENAME` DDL or Drop+Create
  fallback per dialect; CLI shortcuts (`--rename-table`,
  `--rename-column`) or file overlay (`--migration-overlay`).
- **Sequence pipeline**: MySQL helper-table emulation
  (`dmg_sequences`) with live drift check; opt-in
  `preserveCurrentValue` for PG / MySQL / SQLite — probe + restore
  folded into a single transaction under per-dialect lock since
  0.9.7 (`pg_advisory_xact_lock` / `SELECT FOR UPDATE` /
  `BEGIN IMMEDIATE`); SQLite sequence emulation via
  `--sqlite-named-sequences helper_table`.
- **Spatial DDL**: PostGIS, MySQL native, SpatiaLite
  (`--spatial-profile`); view-query transformation across dialects.
- **Data operations**: streaming `data export` / `import` /
  `transfer` (JSON / YAML / CSV / Parquet) with named connections,
  UPSERT, truncate, trigger handling, reseeding, incremental export
  (`--since-column` / `--since`); `data profile` for data
  statistics.
- **Parquet & object storage** (0.9.8): `data export` / `import
  --format parquet` for bundle (multi-table + `manifest.yaml`) and
  single-file (footer-KV) layouts with checkpoint/resume and
  `--table-order`; S3-compatible `ArtifactStore`
  (`artifacts.store: s3`, AWS SDK v2) for server-mode artefacts.
- **Integrations**: `d-migrate export flyway|liquibase|django|knex`.
- **MCP server** (`mcp serve --transport stdio|http`, MCP
  2025-11-25): read-only tool surface (`schema_validate`,
  `schema_compare`, `schema_generate`, `schema_reverse_start`) plus
  policy-gated job workers (`data_import_start`,
  `data_transfer_start`, `data_profile_start`,
  `procedure_transform_*`, `testdata_*`) with idempotency and
  JDBC-backed state; auth via JWT-JWKS / RFC-7662 introspection /
  stdio token registry.
- **CLI UX**: i18n EN/DE with ICU4J, explicit time-zone / temporal
  policy, CSV / BOM encoding contract, phased DDL via
  `--split pre-post`.
- **OCI image** at `ghcr.io/pt9912/d-migrate:<version>` and
  `:latest`.

The simplest way to try the tool is the published OCI image:

```bash
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  schema validate --source /work/schema.yaml
```

See [Quick start](#quick-start) below for more concrete recipes.

## What makes it trustworthy?

- **≥ 90 % line coverage per module**, enforced by Kover
  (`minBound(90)` in every module's `build.gradle.kts`). The CI
  build fails if any module drops below.
- **Doc-check gate**: Markdown link targets in [`docs/`](docs/),
  [`spec/`](spec/), and root Markdown files (including both READMEs
  and [`CHANGELOG.md`](CHANGELOG.md)) are validated against the file
  system on every CI run via
  [d-check](https://github.com/pt9912/d-check) (digest-pinned
  container image, configured in [`.d-check.yml`](.d-check.yml));
  broken internal links and anchors break the build.
- **Static-analysis gate**: Detekt plus a SOLID-suppression-gate
  ([`scripts/solid-suppression-gate.sh`](scripts/solid-suppression-gate.sh))
  — `@Suppress("LargeClass")` and friends are tracked in a ledger
  and require structural fixes, not inline waivers.
- **Cross-dialect test matrix**:
  [`test/cross-dialect-matrix`](test/cross-dialect-matrix) sweeps
  every workstream × dialect × test-kind cell, with a carve-out
  registry (`carve-outs.yaml`) that requires every non-pinned cell
  to declare reason + plan-doc reference; silent carve-outs are
  rejected at load time.
- **Live-DB integration tests** against Testcontainers PostgreSQL
  16, MySQL 8, and file-backed SQLite — every diff, rename,
  sequence, and atomic-preserve pipeline runs against real engines
  via
  [`scripts/test-integration-docker.sh`](scripts/test-integration-docker.sh).
- **Reproducible builds**: `--deterministic` plus `SOURCE_DATE_EPOCH`
  emit byte-identical DDL across timestamps and OS environments.
- **Signed migration plans**: `--plan-artefact` writes a canonical,
  signed `migration-plan.v1` JSON with stable fingerprints,
  statement IDs, and rollback metadata; tampered artefacts are
  rejected by the `MigrationPlanArtifactValidator`.
- **Hexagonal architecture**: pure-domain
  [`hexagon:core`](hexagon/core/) plus
  `hexagon:ports-{common,read,write,execute}` with driving adapters
  (CLI, MCP) and driven adapters (drivers, formats, persistence)
  isolated through explicit interfaces. Architectural decisions
  live as ADRs in [`docs/adr/`](docs/adr/).
- **CI mirrors local**: every gate `make ci` runs locally also runs
  in GitHub Actions on every push
  ([`.github/workflows/build.yml`](.github/workflows/build.yml)).

## Status

The full release history (0.1.0–0.9.7) lives in
[`CHANGELOG.md`](CHANGELOG.md). Current and upcoming milestones:

- **0.9.8 Analytics + storage anchor (Parquet Cut A + S3
  ArtifactStore + BI demo)** · `Released` (2026-06-14): productive
  Parquet `data export` / `import` (bundle + single-file,
  checkpoint/resume, `--table-order`, exit-code contract);
  S3-compatible `ArtifactStore` (AWS SDK v2 +
  `url-connection-client`, `artifacts.store: s3`); BI-demo Compose
  stack (Postgres + Metabase + SeaweedFS). All closure plan-docs in
  [`docs/planning/done/`](docs/planning/done/).
- **0.9.9 Documentation + pilot validation + cross-dialect
  fidelity hardening** · `Released` (2026-07-08): complete beta
  documentation set, ≥5-tester pilot acceptance, and all P1/P2/P3
  cross-dialect blockers from five pilot rounds fixed (structural
  transfer preflight, array/`tsvector` binding, `CURRENT_DATE`
  defaults, view portability, routine emission, post-execute
  compare canonicalisation). See [`CHANGELOG.md`](CHANGELOG.md).
- **1.0.0 Stable release** · `Planned`.

For per-milestone task tables and ADR pointers see the canonical
roadmap at
[`docs/planning/in-progress/roadmap.md`](docs/planning/in-progress/roadmap.md).
ADRs live under [`docs/adr/`](docs/adr/); the canonical index is
[`docs/adr/README.md`](docs/adr/README.md).

All releases and details:
[`CHANGELOG.md`](CHANGELOG.md) |
[GitHub Releases](https://github.com/pt9912/d-migrate/releases).

## Build, Test, Lint

Individual gates for fast feedback loops:

```bash
make help              # list all available targets
make ci                # Docker CI build + docs-check (full local gate)
make gates             # Docker check, coverage, docs, and semgrep gates
make docker-build      # build the runtime image
make docker-check      # Gradle check inside the Dockerfile build stage
make docker-test       # Gradle test inside the Dockerfile build stage
make docker-detekt     # Detekt static analysis
make docker-coverage-gate  # Kover ≥ 90 % per module
make docs-check        # validate Markdown link targets + Kover-excludes ledger
make semgrep           # hermetic semgrep scan with pinned rules
make integration       # Testcontainers integration suite
make docker-full-gates # docker-gates plus Docker-backed integration tests
make docker-oci-build  # build the Jib OCI image tar via Dockerfile stage
make release-assets    # build ZIP, TAR, fat JAR, SHA256 release assets
```

Targeted module runs:

```bash
make docker-check MODULES=":hexagon:core :adapters:driven:driver-postgresql"
make docker-test  MODULES=":adapters:driving:mcp"
```

## Quick start

### Prerequisites

- Docker
- Optional for local development without containers: **JDK 21** or
  newer

### Use the published OCI image

No local JDK required — pull the image and run it:

```bash
# Validation
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  schema validate --source /work/schema.yaml

# Compare (file/file)
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  schema compare --source file:/work/schema.yaml --target file:/work/schema-new.yaml

# Generate DDL
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  schema generate --source /work/schema.yaml --target postgresql

# Reverse engineering
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  --config /work/.d-migrate.yaml schema reverse --source mydb --output /work/reverse.yaml

# DB-to-DB data transfer
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  data transfer --source sourcedb --target targetdb --tables users,orders
```

#### Docker / Volumes — running as non-root

The published image runs as a **non-root** user (`uid 10001`). Read-only commands
(`validate`, `compare`) work as shown above. Commands that **write** into a
bind-mounted host directory (`reverse --output`, `generate` to a file, `data
transfer` to file targets) need the mount to be writable by the container user —
add `--user "$(id -u):$(id -g)"` so output lands with your host ownership:

```bash
docker run --rm --user "$(id -u):$(id -g)" -v $(pwd):/work \
  ghcr.io/pt9912/d-migrate:latest \
  schema reverse --source mydb --output /work/reverse.yaml
```

### GitHub Release assets

Published releases ship ZIP, TAR, and a fat JAR on the
[Releases page](https://github.com/pt9912/d-migrate/releases).

```bash
# Launcher-based distribution
tar -xf d-migrate-<version>.tar
./d-migrate-<version>/bin/d-migrate --help

# Or run the fat JAR directly
java -jar d-migrate-<version>-all.jar --help
```

Note: the Homebrew formula is maintained in the repository from
0.5.0 on and is verified per release via
[`.github/workflows/verify-homebrew-formula.yml`](.github/workflows/verify-homebrew-formula.yml).

### Build from source

```bash
make ci-build
```

### Minimal schema example

Create a file called `schema.yaml`:

```yaml
schema_format: "1.0"
name: "My App"
version: "1.0.0"

tables:
  users:
    columns:
      id:
        type: identifier
        auto_increment: true
      email:
        type: text
        max_length: 254
        required: true
        unique: true
      created_at:
        type: datetime
        default: current_timestamp
    primary_key: [id]
```

Validate it like this:

```bash
make docker-build
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/schema.yaml
```

And compare two versions like this:

```bash
docker run --rm -v $(pwd):/work d-migrate:dev \
  schema compare --source /work/schema.yaml --target /work/schema-v2.yaml
```

### Build and test locally with the Dockerfile

The repository ships a multi-stage [`Dockerfile`](Dockerfile) that
builds and tests the project inside the container and then packages
the CLI distribution into a slim JRE runtime image. This is the
simplest way to run the full build without installing a local JDK.

<details>
<summary>Dockerfile stages — overview</summary>

- **`deps`**: Gradle dependency pre-warm.
- **`build`**: build, tests, coverage gate, distribution. Use
  `--target build --build-arg GRADLE_TASKS="..."` to scope to a
  specific task list.
- **`detekt`**: Detekt static analysis.
- **`coverage`**: aggregated Kover HTML report on port 8080.
  `docker build --target coverage -t d-migrate:coverage .` +
  `docker run --rm -p 8080:8080 d-migrate:coverage`.
- **`coverage-json`**: Kover JSON to stdout via `ENTRYPOINT`.
- **`coverage-verify`**: hard `koverVerify` (≥ 90 % per module).
- **`release-assets`**: ZIP / TAR / fat JAR / SHA256 (target of
  `make release-assets`).
- **`jib-image-tar`**: Jib OCI image as tar (target of
  `make docker-oci-build`).
- **`runtime`** (default): slim `eclipse-temurin:21-jre-noble`
  runtime image.

</details>

<details>
<summary>Common Dockerfile recipes</summary>

```bash
# Full build incl. tests and coverage validation
docker build -t d-migrate:dev .

# Force a full test/coverage run (bypasses both the Docker layer cache and the Gradle cache)
docker build --no-cache --progress=plain \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist --rerun-tasks" \
  -t d-migrate:dev .

# Skip tests — build only the CLI distribution
docker build --build-arg GRADLE_TASKS="assemble :adapters:driving:cli:installDist" \
  -t d-migrate:dev .

# Run only part of the build stage without producing the final runtime image
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :adapters:driven:driver-common:test" \
  -t d-migrate:phase-a .

# Run the locally built CLI
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/schema.yaml

# Run the testcontainers integration suite
./scripts/test-integration-docker.sh

# Or a subset of integration tests
./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test
```

</details>

## Supported databases

| Database   | Status                                                              |
| ---------- | ------------------------------------------------------------------- |
| PostgreSQL | DDL generation, reverse engineering, data export/import/transfer    |
| MySQL      | DDL generation, reverse engineering, data export/import/transfer    |
| SQLite     | DDL generation, reverse engineering, data export/import/transfer    |
| Oracle     | Planned                                                             |
| MSSQL      | Planned                                                             |

## Project Structure

```text
.
├── .github/workflows/             ← GitHub Actions: build, integration, demo/sample DB, release
├── CHANGELOG.md
├── Dockerfile                     ← multi-stage (deps, build, detekt, coverage, runtime, release-assets, jib-image-tar)
├── Makefile                       ← build/test gates per Dockerfile stage
├── README.md                      ← English main version (this document)
├── README.de.md                   ← German version
├── build.gradle.kts               ← root build config + module aggregation
├── settings.gradle.kts            ← Gradle multi-module declaration
├── gradle.properties              ← pinned dependency versions
├── config/                        ← detekt and semgrep configuration
├── hexagon/                       ← pure domain + ports (no driver dependencies)
│   ├── core/                      ← neutral schema model, diff core, validators
│   ├── ports-common/              ← cross-cutting port contracts
│   ├── ports-read/                ← read-side ports (DDL generation, reverse, capabilities)
│   ├── ports-write/               ← write-side ports (data import / transfer)
│   ├── ports-execute/             ← atomic-execution ports (preserve, lock contracts)
│   ├── ports/                     ← driver-registry port (DatabaseDriver, DatabaseDriverRegistry, PreGenerationValidator) + facade re-export of ports-{common,read,write,execute}
│   ├── application/               ← use-case orchestration + stage pipelines
│   └── profiling/                 ← perf measurement infrastructure
├── adapters/
│   ├── driven/                    ← outbound: driver-postgresql/-mysql/-sqlite (+ -profiling),
│   │                                formats + formats-parquet, integrations
│   │                                (Flyway/Liquibase/Django/Knex), persistence-jdbc,
│   │                                storage-file/-s3, streaming, text-icu,
│   │                                audit-logging, connection-config
│   └── driving/                   ← inbound: cli, mcp
├── examples/
│   ├── bi-demo/                   ← Compose demo for Parquet/S3/BI flows
│   └── sample-db/                 ← on-demand sample database harness
├── test/
│   ├── consumer-read-probe/       ← read-only consumer surface verification
│   ├── cross-dialect-matrix/      ← workstream × dialect × kind sweep + carve-out registry
│   ├── integration-postgresql/    ← Testcontainers PG live-DB tests
│   ├── integration-mysql/         ← Testcontainers MySQL live-DB tests
│   ├── integration-sqlite/        ← file-backed SQLite live-DB tests
│   ├── integration-concurrency/   ← race-condition reproducers (sequence preserve, atomic locks)
│   ├── integration-integrations/  ← export integration contract tests
│   ├── integration-persistence-jdbc/ ← JDBC store + migration runner ITs
│   ├── integration-server-state/  ← MCP server state machine ITs
│   ├── integration-storage-s3/    ← S3-compatible artifact store ITs
│   ├── e2e-cli/                   ← end-to-end CLI + MCP harness scenarios
│   └── perf-large-schema/         ← N = 100 / 1000 / 10000 perf scales
├── scripts/                       ← verify-doc-refs.sh, solid-suppression-gate.sh,
│                                    test-integration-docker.sh, kover utilities
├── ledger/                        ← suppression and quality ledgers
├── packaging/homebrew/            ← Homebrew formula (d-migrate.rb)
├── spec/                          ← normative specs (German): lastenheft, architecture,
│                                    design, cli-spec, neutral-model-spec,
│                                    ddl-generation-rules, mcp-server, schema-reference,
│                                    connection-config-spec
└── docs/
    ├── adr/                       ← Architecture Decision Records + index
    ├── planning/
    │   ├── open/                  ← trigger watch + open follow-ups
    │   ├── next/                  ← planned but not yet active
    │   ├── in-progress/           ← active roadmap + slice plans
    │   └── done/                  ← completed slices + closure notes
    └── user/                      ← user / operator facing (guide.md, releasing.md)
```

**Note:** the linked ADRs, slice plans, and planning documents under
[`docs/`](docs/) and [`spec/`](spec/) are written in German. The
English README mirrors the structure and key facts; for deep-dive
content, consult [`README.de.md`](README.de.md) or the German source
documents.

## Documentation

Detailed documentation lives in [`docs/`](docs/) and
[`spec/`](spec/):

- [Documentation overview (German)](docs/user/README.md)
  - [Anwenderhandbuch](docs/user/anwenderhandbuch.md)
  - [Administrationshandbuch](docs/user/administrationshandbuch.md)
  - [Migrations-Leitfaden](docs/user/migrations-leitfaden.md)
  - [API-Referenz (CLI + MCP)](docs/user/api-referenz.md)
- [Quick Start Guide (German)](docs/user/guide.md)
- [Architecture](spec/architecture.md)
- [Schema YAML reference](spec/schema-reference.md)
- [Neutral model specification](spec/neutral-model-spec.md)
- [CLI specification](spec/cli-spec.md)
- [MCP server](spec/mcp-server.md)
- [DDL generation rules](spec/ddl-generation-rules.md)
- [Connection and configuration specification](spec/connection-config-spec.md)
- [Roadmap](docs/planning/in-progress/roadmap.md)
- [Release guide](docs/user/releasing.md)
- [Requirements document (German)](spec/lastenheft-d-migrate.md)

## Contributing

Contributions are welcome! Please open an issue or a pull request on
[GitHub](https://github.com/pt9912/d-migrate).

1. Fork the repository
2. Create a feature branch off `develop`
3. Write tests for your changes (≥ 90 % per-module Kover gate
   applies)
4. Make sure the Docker CI gates pass (`make ci`)
5. Submit a pull request against `develop`

## License

This project is licensed under the [MIT License](LICENSE).
