# d-migrate

**Database-agnostic tool for schema migration and data management** — usable as a
CLI **and** as an MCP server. Define your schema once in a neutral YAML format,
then validate, compare, generate DDL, and run live diff-based migrations against
**PostgreSQL, MySQL, and SQLite**. Also covers reverse engineering, streaming
data export/import/transfer, and export to Flyway, Liquibase, Django, and Knex.

- **Source & docs:** https://github.com/pt9912/d-migrate
- **User guide** (task-oriented: "I need X → do Y"): https://github.com/pt9912/d-migrate/blob/main/docs/user/anwenderhandbuch.md
- **Operations guide** (deployment, configuration, connections): https://github.com/pt9912/d-migrate/blob/main/docs/user/administrationshandbuch.md
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

See the **Tags** tab for everything published here. The examples below use
`__VERSION__` — the most recent release at the time this page was updated.

**If you don't see a `latest` tag:** this mirror started with a *prerelease*, and
prereleases deliberately never move `latest`, so it only appears once a stable
release is published here. Until then `docker pull pt9912/d-migrate` **without an
explicit tag will fail** — use `ghcr.io/pt9912/d-migrate:latest` for a stable
image. Either way, pin an explicit version in CI rather than tracking `latest`.

## Quick start

The entrypoint is the `d-migrate` CLI itself, so arguments go straight after the
image name. The default command is `--help`.

```bash
# Validate a schema
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:__VERSION__ \
  schema validate --source /work/schema.yaml

# Generate DDL for a target dialect
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:__VERSION__ \
  schema generate --source /work/schema.yaml --target postgresql

# Compare two schema files
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:__VERSION__ \
  schema compare --source file:/work/schema.yaml --target file:/work/schema-new.yaml

# Database-to-database data transfer
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:__VERSION__ \
  data transfer --source sourcedb --target targetdb --tables users,orders
```

The native variant is a drop-in replacement — same entrypoint, same flags, same
mounts:

```bash
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:__VERSION__-native \
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
  pt9912/d-migrate:__VERSION__ \
  schema reverse --source mydb --output /work/reverse.yaml
```

## Connecting to databases

Connections are passed as URLs or as named connections from a config file. Mount
the config alongside your work directory and point `--config` at it:

```bash
docker run --rm -v "$(pwd):/work" pt9912/d-migrate:__VERSION__ \
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
