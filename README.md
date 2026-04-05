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

- **JDK 21** or later

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

No JDK required — just pull the image and run:

```bash
# Validate
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema validate --source /work/schema.yaml

# Generate DDL
docker run --rm -v $(pwd):/work ghcr.io/pt9912/d-migrate:latest schema generate --source /work/schema.yaml --target postgresql
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

**v0.2.0** (in development):

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
