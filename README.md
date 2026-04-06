# d-migrate

**Database-agnostic CLI tool for schema migration and data management.**

<!-- Badges -->
![Build](https://github.com/pt9912/d-migrate/actions/workflows/build.yml/badge.svg)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-purple.svg)

---

## What is d-migrate?

d-migrate is a command-line tool that lets you define your database schema once in a neutral, database-agnostic format (YAML) and then validate, diff, and generate DDL for multiple target databases. No more maintaining separate migration scripts per database engine.

**Current capabilities:**
- Neutral schema model with 18 built-in types
- YAML-based schema definition and parsing
- Schema validation with 18 error codes (E001-E018)
- DDL generation for PostgreSQL, MySQL, and SQLite
- View query transformation (17 SQL functions)
- Transformation reports (YAML sidecar)
- CLI with `schema validate` and `schema generate`
- OCI image for Docker usage

**Planned:**
- Data export/import (JSON, YAML, CSV)
- Schema diffing and migration generation

## Quick Start

### Prerequisites

- **JDK 21** or later — _or_ Docker (see below, no local JDK required)

### Build

```bash
./gradlew build
```

### Run the CLI

```bash
# Validate a schema
./gradlew :d-migrate-cli:run --args="schema validate --source schema.yaml"

# Generate PostgreSQL DDL
./gradlew :d-migrate-cli:run --args="schema generate --source schema.yaml --target postgresql"

# Generate MySQL DDL with rollback
./gradlew :d-migrate-cli:run --args="schema generate --source schema.yaml --target mysql --generate-rollback"
```

### Docker

#### Use the published image

No JDK required — just pull the image and run:

```bash
# Validate
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema validate --source /work/schema.yaml

# Generate DDL
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema generate --source /work/schema.yaml --target postgresql
```

#### Build and test locally with the Dockerfile

The repository ships a multi-stage [`Dockerfile`](Dockerfile) that builds and
tests the project inside a container, then packages the CLI distribution into a
slim JRE runtime image. This is the easiest way to run the full build without
installing a JDK locally.

```bash
# Full build incl. tests and coverage verification (default)
docker build -t d-migrate:dev .

# Force a full test/coverage run (bypass Docker layer cache AND Gradle build cache)
docker build --no-cache \
  --build-arg GRADLE_TASKS="build :d-migrate-cli:installDist --rerun-tasks" \
  -t d-migrate:dev .

# Skip tests — only assemble the CLI distribution
docker build --build-arg GRADLE_TASKS="assemble :d-migrate-cli:installDist" \
  -t d-migrate:dev .

# Run only a build-stage subset without producing the final runtime image
docker build --target build \
  --build-arg GRADLE_TASKS=":d-migrate-core:test :d-migrate-driver-api:test" \
  -t d-migrate:phase-a .

# Run the locally built CLI
docker run --rm -v $(pwd):/work d-migrate:dev schema validate --source /work/schema.yaml
```

Notes:

- The build stage uses `eclipse-temurin:21-jdk-noble` and caches Gradle
  dependencies via BuildKit cache mounts, so repeated builds are fast.
- The runtime stage uses `eclipse-temurin:21-jre-noble` (the same base image as
  the published OCI image produced by `:d-migrate-cli:jibDockerBuild`).
- A full `docker build` always reaches the runtime stage. If you override
  `GRADLE_TASKS`, include `:d-migrate-cli:installDist`; otherwise use
  `--target build` for build/test-only subsets.
- To extract build artifacts from the build stage:
  ```bash
  docker build --target build -t d-migrate:build .
  docker create --name d-migrate-tmp d-migrate:build
  docker cp d-migrate-tmp:/src/d-migrate-cli/build/distributions ./dist
  docker rm d-migrate-tmp
  ```

### Minimal Schema Example

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

Then validate it:

```bash
./gradlew :d-migrate-cli:run --args="schema validate --source schema.yaml"
```

## Current Status

**[v0.2.0](https://github.com/pt9912/d-migrate/releases/tag/v0.2.0)** released:

- DDL generation for PostgreSQL, MySQL, SQLite
- TypeMapper with 18 neutral types per dialect
- View query transformation (17 SQL functions)
- Transformation reports (YAML sidecar, JSON output)
- `schema generate` CLI command with `--output`, `--generate-rollback`, `--report`
- 374 tests, coverage >= 90%

**[v0.1.0](https://github.com/pt9912/d-migrate/releases/tag/v0.1.0)** released:

- Neutral schema model, YAML parsing, schema validation, CLI `schema validate`

## Supported Databases

| Database   | Status           |
|------------|------------------|
| PostgreSQL | DDL Generation   |
| MySQL      | DDL Generation   |
| SQLite     | DDL Generation   |
| Oracle     | Future           |
| MSSQL      | Future           |

## Roadmap

See [docs/roadmap.md](docs/roadmap.md) for the full roadmap and milestone plan.

## Documentation

Detailed documentation is available in the [docs/](docs/) directory:

- [Quick Start Guide (German)](docs/guide.md)
- [Design](docs/design.md) / [Architecture](docs/architecture.md)
- [Neutral Model Specification](docs/neutral-model-spec.md)
- [CLI Specification](docs/cli-spec.md)
- [DDL Generation Rules](docs/ddl-generation-rules.md)
- [Connection & Config Specification](docs/connection-config-spec.md)
- [Roadmap](docs/roadmap.md)
- [Release Guide](docs/releasing.md)
- [Requirements (German)](docs/lastenheft-d-migrate.md)

## Contributing

Contributions are welcome! Please open an issue or submit a pull request on [GitHub](https://github.com/pt9912/d-migrate).

1. Fork the repository
2. Create a feature branch from `develop`
3. Write tests for your changes
4. Ensure all tests pass (`./gradlew build`)
5. Submit a pull request against `develop`

## License

This project is licensed under the [MIT License](LICENSE).
