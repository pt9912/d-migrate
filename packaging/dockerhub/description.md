# d-migrate

**Database-agnostic tool for schema migration and data management** — usable as a
CLI **and** as an MCP server. Define your schema once in a neutral YAML format,
then validate, compare, generate DDL, and run live diff-based migrations against
**PostgreSQL, MySQL, and SQLite**. Also covers reverse engineering, streaming
data export/import/transfer, and export to Flyway, Liquibase, Django, and Knex.

- **Source & docs:** https://github.com/pt9912/d-migrate
- **Changelog:** https://github.com/pt9912/d-migrate/blob/main/CHANGELOG.md
- **License:** MIT

> **This repository is a mirror.** The primary registry is GitHub Container
> Registry — `ghcr.io/pt9912/d-migrate`. Identical images are pushed to both
> (same build, same digest); Docker Hub exists so `docker pull` works without a
> GHCR login.

## Supported tags

| Tag | Contents |
| --- | --- |
| `<version>` | JVM image (Eclipse Temurin 21 JRE on Ubuntu Noble) |
| `<version>-native` | GraalVM native binary, **no JVM** — smaller image, starts in milliseconds instead of a few hundred |
| `latest` | Most recent **stable** release. Prereleases never move it. |

**Currently available here:** `1.0.0-RC2` and `1.0.0-RC2-native`.

The mirror went live with the `1.0.0-RC2` **prerelease**, so there is **no
`latest` tag on Docker Hub yet** — `docker pull pt9912/d-migrate` without an
explicit tag will fail. It will appear with the first stable release published
after the mirror went live (1.0.0). For a stable image today, use
`ghcr.io/pt9912/d-migrate:latest`. Always pin an explicit version in CI.

## Quick start

The entrypoint is the `d-migrate` CLI itself, so arguments go straight after the
image name. The default command is `--help`.

```bash
# Validate a schema
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:1.0.0-RC2 \
  schema validate --source /work/schema.yaml

# Generate DDL for a target dialect
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:1.0.0-RC2 \
  schema generate --source /work/schema.yaml --target postgresql

# Compare two schema files
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:1.0.0-RC2 \
  schema compare --source file:/work/schema.yaml --target file:/work/schema-new.yaml

# Database-to-database data transfer
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:1.0.0-RC2 \
  data transfer --source sourcedb --target targetdb --tables users,orders
```

The native variant is a drop-in replacement — same entrypoint, same flags, same
mounts:

```bash
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:1.0.0-RC2-native \
  schema validate --source /work/schema.yaml
```

## Writing files: run as your own user

The image runs as a **non-root** user (`uid 10001`) and its working directory and
volume is `/work`. Read-only commands (`validate`, `compare`, `generate` to
stdout) work as shown above.

Commands that **write into a bind-mounted host directory** — `schema reverse
--output`, `generate` to a file, file-target `data transfer` — need the mount to
be writable by the container user. Add `--user` so output lands with your host
ownership:

```bash
docker run --rm --user "$(id -u):$(id -g)" -v "$(pwd):/work" \
  pt9912/d-migrate:1.0.0-RC2 \
  schema reverse --source mydb --output /work/reverse.yaml
```

## Connecting to databases

Connections are passed as URLs or as named connections from a config file. Mount
the config alongside your work directory and point `--config` at it:

```bash
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:1.0.0-RC2 \
  --config /work/.d-migrate.yaml \
  schema reverse --source mydb --output /work/reverse.yaml
```

Credentials should **not** be baked into the config — d-migrate resolves them
from environment variables (`D_MIGRATE_DB_PASSWORD`), a `credentialRef`
(`env:`, `file:`, `keychain:`), or an encrypted credential store. See the
[connection configuration spec](https://github.com/pt9912/d-migrate/blob/main/spec/connection-config-spec.md).

When reaching a database on the Docker host from inside the container, use a
user-defined network and container names rather than `localhost` — inside the
container, `localhost` is the container itself.

## What's in the image

- **JVM image:** `eclipse-temurin:21-jre-noble`; the CLI is installed under
  `/opt/d-migrate` and is on `PATH`.
- **Native image:** `ubuntu:24.04` with the GraalVM-built binary at
  `/usr/local/bin/d-migrate` — no JRE.
- Both include `mod_spatialite`, loaded only when a SQLite connection requests it
  via `?spatialite=true`.
- Both run as `uid 10001`, with `/work` as workdir and volume.

## Which artefact should I use?

This image is one of several distributions. There is also a launcher ZIP/TAR, a
fat JAR, standalone native binaries for Linux/macOS/Windows, and a Homebrew
formula — see the
[release page](https://github.com/pt9912/d-migrate/releases) and the
[README](https://github.com/pt9912/d-migrate#readme).

## Security

Vulnerability reports and the threat model:
[SECURITY.md](https://github.com/pt9912/d-migrate/blob/main/SECURITY.md).
