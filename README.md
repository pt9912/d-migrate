# d-migrate

**Database-agnostic tool for schema migration and data management.**

> 🇩🇪 [Deutsche Version](README.de.md)

<!-- Badges -->
![Build](https://github.com/pt9912/d-migrate/actions/workflows/build.yml/badge.svg)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-purple.svg)

---

## What is d-migrate?

d-migrate is a database-agnostic tool for schema migration and data management — usable through a CLI **and** as an MCP server (`mcp serve --transport stdio|http`, MCP 2025-11-25). You define your schema once in a neutral format (YAML) and can then validate, compare and generate DDL for PostgreSQL, MySQL and SQLite. Beyond that, d-migrate supports reverse engineering of existing databases, streaming-based data export/import/transfer between databases, and integration with existing migration toolchains (Flyway, Liquibase, Django, Knex).

**Current capabilities:**
- **Schema model**: neutral YAML schema with 19 types + Spatial Geometry; validator with 35+ error codes
- **Schema operations**: `validate`, `generate`, `compare`, `reverse`, `migrate`, `rollback` for PostgreSQL, MySQL, SQLite — file/file, file/db, db/db
- **Diff migrations**: tables, columns, indexes, constraints incl. CHECK/EXCLUDE with live-data preflight, foreign keys, sequences, views, materialized views (PG), triggers, functions/procedures; signed `migration-plan.v1` artefact via `--plan-artefact`
- **Renames** for tables, columns, views, triggers, functions, procedures, sequences — native `RENAME` DDL or Drop+Create fallback depending on dialect; CLI shortcuts `--rename-table` / `--rename-column` or file overlay `--migration-overlay`
- **Sequence pipeline**: MySQL helper-table emulation (`dmg_sequences`) with live drift check; opt-in `preserveCurrentValue` for PG/MySQL/SQLite (probe + restore folded into a single transaction under per-dialect lock since 0.9.7 — `pg_advisory_xact_lock` / `SELECT FOR UPDATE` / `BEGIN IMMEDIATE`); SQLite sequence emulation via `--sqlite-named-sequences helper_table`
- **Spatial DDL**: PostGIS, MySQL native, SpatiaLite (`--spatial-profile`); view-query transformation across dialects
- **Data operations**: streaming `data export` / `import` / `transfer` (JSON/YAML/CSV) with named connections, UPSERT, truncate, trigger handling, reseeding, incremental export (`--since-column` / `--since`); `data profile` for data statistics
- **Integrations**: `d-migrate export flyway|liquibase|django|knex`
- **MCP server** (`mcp serve --transport stdio|http`): MCP 2025-11-25 with auth (JWT-JWKS/introspection, stdio token registry), discovery and JSON schema contract
- **CLI UX**: i18n EN/DE with ICU4J, explicit time-zone/temporal policy, CSV/BOM encoding contract, phased DDL via `--split pre-post`
- **OCI image** for Docker usage

## Quick start

### Prerequisites

- Docker
- Optional for local development without containers: **JDK 21** or newer

### Installation

#### GitHub Release assets

Published releases ship ZIP, TAR and a fat JAR on the
[Releases page](https://github.com/pt9912/d-migrate/releases).

```bash
# Unpack the launcher-based distribution
tar -xf d-migrate-<version>.tar
./d-migrate-<version>/bin/d-migrate --help

# Or run the fat JAR directly
java -jar d-migrate-<version>-all.jar --help
```

Note: The Homebrew formula is maintained in the repository starting with 0.5.0
but is not yet a fully automated default install path.

#### Build from source

```bash
make ci-build
```

#### Makefile convenience targets

The top-level [`Makefile`](Makefile) is a thin wrapper around the canonical
Gradle, Docker and script entrypoints. The available shortcuts are shown by:

```bash
make help
```

Common targets:

```bash
make ci-build             # Build/Test/Coverage gate in the Dockerfile build stage
make docker-resolve-deps  # Pre-warm Gradle dependencies in the Dockerfile deps stage
make docker-check         # Gradle check in the Dockerfile build stage
make docker-test          # Gradle test in the Dockerfile build stage
make docker-detekt        # Detekt in the Dockerfile detekt stage
make docker-coverage-gate  # Kover gate in the Dockerfile coverage stage
make gates             # Docker check, Docker coverage gate and docs-check
make ci                # Docker CI build plus docs-check
make docker-smoke      # Build the Docker runtime image and verify --version/--help
make integration       # Testcontainers integration tests via Docker script
make docs-check        # Validate Markdown link targets in docs/
make docker-gates      # Docker runtime build, coverage gate and runtime smoke
make docker-full-gates # docker-gates plus Docker integration tests
make release-assets    # Build ZIP, TAR, fat JAR and SHA256 via Dockerfile
make docker-oci-build  # Build the Jib OCI image via Dockerfile and run docker load
```

#### Build release assets locally

```bash
make release-assets
ls -1 adapters/driving/cli/build/release
```

### Run the CLI

```bash
# Build locally once
make docker-build

# Validate a schema
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/schema.yaml

# Compare two schemas
docker run --rm -v $(pwd):/work d-migrate:dev schema compare --source /work/schema.yaml --target /work/schema-new.yaml

# Generate PostgreSQL DDL
docker run --rm -v $(pwd):/work d-migrate:dev schema generate --source /work/schema.yaml --target postgresql

# Generate MySQL DDL with a rollback script
docker run --rm -v $(pwd):/work d-migrate:dev schema generate --source /work/schema.yaml --target mysql --generate-rollback

# Extract a schema from an existing database
docker run --rm -v $(pwd):/work d-migrate:dev schema reverse --source mydb --output /work/reverse.yaml --report /work/reverse.report.yaml

# DB-based schema comparison
docker run --rm -v $(pwd):/work d-migrate:dev schema compare --source file:/work/schema.yaml --target db:mydb

# DB-to-DB data transfer
docker run --rm -v $(pwd):/work d-migrate:dev data transfer --source sourcedb --target targetdb --tables users,orders
```

### Docker

#### Use the published image

No local JDK required — pull the image and run it:

```bash
# Validation
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema validate --source /work/schema.yaml

# Compare (file/file)
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema compare --source file:/work/schema.yaml --target file:/work/schema-new.yaml

# Generate DDL
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema generate --source /work/schema.yaml --target postgresql

# Reverse engineering
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest \
  --config /work/.d-migrate.yaml schema reverse --source mydb --output /work/reverse.yaml
```

#### Build and test locally with the Dockerfile

The repository ships a multi-stage [`Dockerfile`](Dockerfile) that builds and
tests the project inside the container and then packages the CLI distribution
into a slim JRE runtime image. This is the simplest way to run the full build
without installing a local JDK.

```bash
# Full build incl. tests and coverage validation (default)
docker build -t d-migrate:dev .

# Force a full test/coverage run (bypasses both the Docker layer cache and the Gradle cache)
docker build --no-cache \
  --progress=plain \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:installDist --rerun-tasks" \
  -t d-migrate:dev .

# Build the aggregated Kover HTML report and view it locally in a browser
docker build --target coverage -t d-migrate:coverage .
docker run --rm -p 8080:8080 d-migrate:coverage
# then open http://localhost:8080 in a browser

# Stream the aggregated Kover JSON report directly to stdout
docker build --target coverage-json -t d-migrate:coverage-json .
docker run --rm d-migrate:coverage-json > coverage.json

# Optionally enforce the 90% Kover gate just like CI does
docker build --target coverage-verify -t d-migrate:coverage-verify .

# Skip tests — build only the CLI distribution
docker build --build-arg GRADLE_TASKS="assemble :adapters:driving:cli:installDist" \
  -t d-migrate:dev .

# Run only part of the build stage without producing the final runtime image
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:core:test :adapters:driven:driver-common:test" \
  -t d-migrate:phase-a .

# Run the locally built CLI
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/schema.yaml

# Run the testcontainers-based integration suite
./scripts/test-integration-docker.sh

# Or run just a subset of the integration tests
./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test
```

### Dockerfile workflows (quick overview)

<details>
<summary>Build and runtime stages</summary>

- Build stage: `gradle:8.12-jdk21`
- Runtime stage: `eclipse-temurin:21-jre-noble` (same as the official Jib OCI image)
- Gradle dependencies are pre-warmed in a dedicated `deps` stage.
- A plain `docker build` always lands in the `runtime` stage.
- When overriding `GRADLE_TASKS`, append: `:adapters:driving:cli:installDist`
- For build/test only without the runtime image: `--target build`

</details>

<details>
<summary>Coverage stages</summary>

- `coverage`: `test koverHtmlReport koverXmlReport` + HTTP server on port `8080` for the root Kover HTML.
- `coverage`: the HTML report is produced even when the 90% gate is missed.
- `coverage-json`: identical root Kover report as JaCoCo-like JSON on `stdout` (via `ENTRYPOINT`).
- `coverage-verify`: hard `koverVerify`; the build target fails if the minimum coverage is not met.
- `docker-coverage-modules-html`: per-module Kover HTML reports as a tar stream for `make docker-coverage-modules-html`.

</details>

<details>
<summary>Release and OCI stages</summary>

- `release-assets`: builds ZIP, TAR, fat JAR and SHA256 and streams `adapters/driving/cli/build/release` as a tar for `make release-assets`.
- `jib-image-tar`: builds the Jib OCI image as a tar, including Jib labels; `make docker-oci-build` then loads it via `docker load`.

</details>

<details>
<summary>Documentation and integration tests</summary>

- `scripts/verify-doc-refs.sh` validates links in `docs/`, `spec/`, `README.md`, `CHANGELOG.md` against the file system.
- External HTTP links are ignored; broken internal links return exit code `1`.
- Do not run testcontainers jobs inside `docker build`.
- Use [`scripts/test-integration-docker.sh`](scripts/test-integration-docker.sh) instead — it mounts the host Docker socket into a JDK container.

</details>

<details>
<summary>Export build artefacts from the build stage</summary>

```bash
docker build --target build -t d-migrate:build .
docker create --name d-migrate-tmp d-migrate:build
docker cp d-migrate-tmp:/src/adapters/driving/cli/build/distributions ./dist
docker rm d-migrate-tmp
```

</details>

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
docker run --rm -v $(pwd):/work d-migrate:dev schema compare --source /work/schema.yaml --target /work/schema-v2.yaml
```

## Current status

Current release: **[v0.9.6](https://github.com/pt9912/d-migrate/releases/tag/v0.9.6)**

MCP server:

- Runtime as a **Model Context Protocol v1 server** (`stdio`, Streamable HTTP)
- Asynchronous jobs
- Idempotency
- Policy/approval
- Quotas
- JDBC persistence
- File-backed artifact stores
- Bundle import
- AI-adjacent tools (`procedure_transform_*`, `testdata_*`)

Further improvements:

- Deterministic DDL generation (`--deterministic` / `SOURCE_DATE_EPOCH`)
- BigInt identity columns for PostgreSQL/MySQL
- Partial index predicates
- Per-column index ordering
- More robust `schema reverse` path with `--split=pre-post`

All releases and details: [CHANGELOG.md](CHANGELOG.md) | [GitHub Releases](https://github.com/pt9912/d-migrate/releases)

## Supported databases

| Database   | Status                                                              |
| ---------- | ------------------------------------------------------------------- |
| PostgreSQL | DDL generation, reverse engineering, data export/import/transfer    |
| MySQL      | DDL generation, reverse engineering, data export/import/transfer    |
| SQLite     | DDL generation, reverse engineering, data export/import/transfer    |
| Oracle     | Planned                                                             |
| MSSQL      | Planned                                                             |

## Roadmap

You'll find the full roadmap and milestone plan in
[docs/planning/roadmap.md](docs/planning/in-progress/roadmap.md).

## Documentation

Detailed documentation lives in [docs/](docs/) and [spec/](spec/):

- [Quick Start Guide (German)](docs/user/guide.md)
- [Design](spec/design.md) / [Architecture](spec/architecture.md)
- [Schema YAML reference](spec/schema-reference.md)
- [Neutral model specification](spec/neutral-model-spec.md)
- [CLI specification](spec/cli-spec.md)
- [MCP server (`d-migrate mcp serve`)](spec/mcp-server.md)
- [DDL generation rules](spec/ddl-generation-rules.md)
- [Connection and configuration specification](spec/connection-config-spec.md)
- [Roadmap](docs/planning/in-progress/roadmap.md)
- [Release guide](docs/user/releasing.md)
- [Requirements document (German)](spec/lastenheft-d-migrate.md)

## Contributing

Contributions are welcome! Please open an issue or a pull request on [GitHub](https://github.com/pt9912/d-migrate).

1. Fork the repository
2. Create a feature branch off `develop`
3. Write tests for your changes
4. Make sure the Docker CI gates pass (`make ci`)
5. Submit a pull request against `develop`

## License

This project is licensed under the [MIT License](LICENSE).
