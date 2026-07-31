# Release Guide

> Anleitung für das Veröffentlichen einer neuen `d-migrate`-Version.
> Dieses Dokument beschreibt Voraussetzungen, Pre-Release-Checks, den
> Schritt-für-Schritt-Ablauf für GitHub-Release-Assets, OCI und Homebrew
> sowie Rollback-Szenarien.
>
> Hinweis: Ein öffentlicher Library-Publish-Vertrag ist bewusst noch nicht Teil
> dieses Dokuments. Der vorgeschaltete Library-Refactor ist in
> [`implementation-plan-0.9.1.md`](../planning/done-archive/implementation-plan-0.9.1.md)
> beschrieben. Das Publishing steht seit
> [ADR 0037](../adr/0037-database-agnostic-first-staffelung.md) (2026-07-17) **hinter dem
> Treiber-Port-Umbau** (Milestone 2.0.0) und **nicht** mehr bei 1.0.0: Der Umbau bricht
> Port-Signaturen, eine Stabilitätszusage mit 1.0.0 träfe also genau die Module, deren Bruch
> bereits beschlossen ist. **1.0.0 liefert CLI, OCI-Image und MCP — keine Library-Artefakte.**
> Als Kanal ist seit [ADR 0036](../adr/0036-library-artefakte-github-packages.md) **GitHub
> Packages** gesetzt, nicht das ursprünglich geplante Maven-Central-Portal; die
> Artefaktklassifikation unten bleibt gültig.
>
> **1.0.0-Artefaktklassifikation** — welche Module als Library veröffentlicht werden,
> wenn der Publish-Workflow ([ADR 0036](../adr/0036-library-artefakte-github-packages.md):
> GitHub Packages) gebaut wird. Angelegt in 0.9.1 Phase G, **aktualisiert 2026-07-17**
> gegen den tatsächlichen Modulschnitt (die Erstfassung kannte den Port-Split noch nicht
> und ließ 9 Module unerfasst).
>
> | Gruppe | Module | Publish-Ziel |
> |--------|--------|-------------|
> | Foundation | `hexagon:core` | Kernartefakt |
> | Ports | `hexagon:ports-common`, `hexagon:ports-read`, `hexagon:ports-write`, `hexagon:ports-execute` | Kernartefakt |
> | Ports (Umbrella) | `hexagon:ports` | Kernartefakt — **Vorbehalt**, s. u. |
> | Driver Runtime | `driver-common`, `driver-postgresql`, `driver-mysql`, `driver-sqlite` | Kernartefakt |
> | Optional Extensions | `hexagon:profiling`, `driver-postgresql-profiling`, `driver-mysql-profiling`, `driver-sqlite-profiling`, `formats`, `streaming` | Zusatzartefakt |
> | Optional Extensions | `formats-parquet` | Zusatzartefakt — **Vorbehalt**, s. u. |
> | Anwendung | `hexagon:application`, `adapters:driving:cli`, `adapters:driving:mcp`, `integrations`, `connection-config`, `persistence-jdbc`, `audit-logging`, `storage-file`, `storage-s3`, `text-icu`, `test:*` | nicht publiziert |
>
> **Leitlinie**: Publiziert wird, was ein einbettender Fremd-Consumer braucht, um d-migrate
> als Bibliothek zu *benutzen* — Domänenmodell, Port-Contracts, Treiber-Runtime, austauschbare
> Codecs. Nicht publiziert wird, was *diese* Anwendung verdrahtet.
>
> Der Umkehrschluss „hängt nur an `ports-common`, also wiederverwendbar" trägt **nicht**:
> `audit-logging`, `connection-config`, `storage-file`, `storage-s3` und `text-icu` haben ein
> technisch makelloses Library-Profil (schmale Fläche, kaum Fremdlast), bedienen aber Ports mit
> d-migrate-eigener Server-Semantik (Upload-Segmente, Artefakt-Content, `.d-migrate.yaml`-Schema).
> Der Hexagon-Schnitt macht sie **austauschbar, nicht wiederverwendbar** — ein Fremd-Consumer
> implementiert diese Ports selbst; genau dafür sind sie da.
>
> Die Lesefläche ist bereits ausführbar festgenagelt:
> [`test/consumer-read-probe`](../../test/consumer-read-probe/build.gradle.kts) kompiliert gegen
> `ports-read`, `ports-common`, `core`, `driver-common`, `formats` — „if this module compiles,
> external read consumers can integrate".
>
> **Vorbehalte vor dem Publish** (beide sind Bedingungen, keine Blocker der Klassifikation):
>
> - `hexagon:ports` ist kein reiner Umbrella: es trägt zusätzlich `DatabaseDriver`,
>   `DatabaseDriverRegistry` (globales, veränderliches Singleton) und `PreGenerationValidator`.
>   Wer die Umbrella-Bequemlichkeit zieht, bekommt das Registry zwangsweise mit. Vor einem
>   Publish klären, ob diese Typen nach `ports-common` (oder ein eigenes Modul) gehören.
> - `formats-parquet` manipuliert den Dependency-Graphen (`constraints { rejectAll() }` auf
>   parquet-avro/-protobuf/avro, `configurations.all { exclude … }` für snappy/zstd). Solche
>   Footprint-Entscheidungen werden in die Gradle-Metadata publiziert und **schlagen auf den
>   Consumer durch** — sie können den Graphen eines Consumers brechen, der Avro oder Snappy aus
>   anderen Gründen braucht. Vor einem Publish aus dem publizierten Scope nehmen.

---

## 1. Branching-Modell

`d-migrate` verwendet ein einfaches `develop → main`-Modell:

- **`develop`** — aktiver Entwicklungsbranch, hier landen alle Features
- **`main`** — enthält ausschließlich Release-Stände, jeder Merge entspricht einem Release
- **Tags** `vX.Y.Z` werden auf den Merge-Commit auf `main` gesetzt
- **Versionierung** folgt [SemVer 2.0](https://semver.org/spec/v2.0.0.html); zwischen Releases trägt [`build.gradle.kts`](../../build.gradle.kts) ein `-SNAPSHOT`-Suffix

Beispiel aus 0.1.0:

```
develop:  ... → "Release 0.1.0" → (Bump 0.2.0-SNAPSHOT) → ...
                      │
                      ▼ merge
main:     ... → "Merge develop into main for release 0.1.0"  ← tag v0.1.0
```

---

## 2. Voraussetzungen

| Voraussetzung                                 | Prüfung                                                                       |
| --------------------------------------------- | ----------------------------------------------------------------------------- |
| Sauberer Working-Tree auf `develop`           | `git status` zeigt keine Änderungen                                           |
| `develop` ist auf dem aktuellen Stand         | `git pull --ff-only origin develop`                                           |
| `main` ist auf dem aktuellen Stand            | `git checkout main && git pull --ff-only origin main && git checkout develop` |
| Docker verfügbar (lokaler Pre-Release-Build)  | `docker version`                                                              |
| `gh` CLI authentifiziert                      | `gh auth status`                                                              |
| `brew` verfügbar auf einem Verifikations-Host | `brew --version`                                                              |
| Schreibrechte auf `main` und Tags im Remote   | —                                                                             |
| Alle PRs für den Release sind gemerged        | GitHub-Milestone leer                                                         |
| `HOMEBREW_TAP_GITHUB_TOKEN` nicht abgelaufen  | `gh secret list --repo pt9912/d-migrate` (Update-Datum prüfen) — fine-grained PATs laufen ab; ein abgelaufener Token lässt den Tag-Publish mit `401` scheitern (nur der Homebrew-Tap-Push, nicht der GitHub-Release) |
| `DOCKERHUB_TOKEN` vorhanden und nicht abgelaufen | `gh secret list --repo pt9912/d-migrate` (Update-Datum prüfen) — Docker-Hub-PATs können ablaufen. **Fehlt** das Secret, wird der Docker-Hub-Push **still übersprungen** (grüner Build, kein Image, s. [4.4.1](#441-docker-hub-spiegel)); ist es **abgelaufen**, scheitert der Login und der Tag-Build wird rot |

---

## 3. Pre-Release-Checks

**Alle Punkte müssen grün sein, bevor der Release-Commit erstellt wird.**

### 3.1 Vollständiger Build & Test im Docker-Container

```bash
docker build --target runtime -t d-migrate:pre-release . 2>&1 | tee /tmp/build.log
```

Der `runtime`-Stage kopiert aus dem `build`-Stage, dessen Default-Tasks
`build :adapters:driving:cli:installDist` sind — dieser eine Build läuft
daher **alle Tests aller Module** *und* erzeugt das Smoke-Image
`d-migrate:pre-release`, gegen das die Smokes in §3.3 laufen. Erwartetes
Ergebnis: `BUILD SUCCESSFUL`.

> **Wichtig:** **nicht** `make docker-build` benutzen — das baut ohne
> `--target` die **letzte** Dockerfile-Stage (`ast-grep`, eigene
> Node-Base), nicht das Runtime-Image; die Modul-Tests laufen dann nicht
> zwingend und `d-migrate:pre-release` entsteht nicht. Nach einer
> Quelländerung zusätzlich `--no-cache-filter compile,build` anhängen,
> sonst liefert der gecachte `compile`-Layer alten Code.

Das separate Coverage-Gate (`koverVerify`) läuft in der CI über
`make ci-build`; lokal kann es mit
`docker build --target build --build-arg GRADLE_TASKS="build koverVerify --no-build-cache" .`
mitgeprüft werden.

Für eine garantiert frische Test-Ausführung ohne Build-Cache (Spezialfall,
deshalb direkter Docker-Aufruf statt `make docker-test` — letzteres
nutzt den Cache):

```bash
docker run --rm \
  -u "$(id -u):$(id -g)" \
  -e HOME=/tmp/home \
  -e GRADLE_USER_HOME=/tmp/gradle \
  -v "$(pwd):/src" \
  -w /src \
  --entrypoint /bin/sh \
  eclipse-temurin:21-jdk-noble \
  -c 'mkdir -p "$HOME" "$GRADLE_USER_HOME" && ./gradlew --no-daemon --no-build-cache --rerun-tasks build'
```

Der Lauf verwendet bewusst die aktuelle Host-UID/GID, damit im
gemounteten Workspace keine root-owned Build-Artefakte entstehen.
`HOME` und `GRADLE_USER_HOME` werden explizit auf beschreibbare
Temp-Pfade im Container gesetzt.

### 3.2 Lokaler Preflight der Release-Assets

```bash
make release-assets 2>&1 | tee /tmp/release-assets.log

ls -1 adapters/driving/cli/build/release
cat adapters/driving/cli/build/release/*.sha256
java -jar adapters/driving/cli/build/release/*-all.jar --help
```

`make release-assets` baut das `release-assets`-Stage-Image
(Default-Tag `d-migrate:release-assets`), erzeugt im Container die
ZIP-/TAR-/Fat-JAR-/SHA256-Assets über
`:adapters:driving:cli:assembleReleaseAssets` und extrahiert sie via
`docker run … | tar xf -` nach `adapters/driving/cli/build/release/` <!-- d-check:ignore (Build-Ausgabe, entsteht zur Build-Zeit; ADR 0011) -->
(der `release-assets`-Stage tart genau dieses Verzeichnis).

Wichtig:

- `adapters/driving/cli/build/release` ist nur der lokale Preflight-Ordner <!-- d-check:ignore (Build-Ausgabe, entsteht zur Build-Zeit; ADR 0011) -->
- für den eigentlichen GitHub-Release werden später ausschließlich die Dateien
  aus dem grünen Workflow-Artefakt `release-assets` verwendet

### 3.3 Smoke-Test der CLI gegen die Fixture-Schemas

Voraussetzung: Die folgenden Fixture-Dateien müssen unter
`adapters/driven/formats/src/test/resources/fixtures/schemas/` existieren:
`minimal.yaml`, `e-commerce.yaml`.

```bash
docker run --rm -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/work" \
  d-migrate:pre-release schema generate --source /work/minimal.yaml --target postgresql

docker run --rm -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/work" \
  d-migrate:pre-release schema generate --source /work/e-commerce.yaml --target sqlite --generate-rollback
```

#### Schema Compare (file/file)

```bash
docker run --rm -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/schemas:/work" \
  d-migrate:pre-release schema compare --source file:/work/minimal.yaml --target file:/work/e-commerce.yaml
```

#### DB-basierte Smokes (Reverse, Compare, Transfer)

Lokales Docker-Netzwerk mit PostgreSQL und MySQL aufsetzen:

Hinweis: Für realistischere DB-Smokes über die eingebauten Fixture-Schemas
hinaus siehe auch die priorisierte Kandidatenliste in
[`test-database-candidates.md`](../planning/open/test-database-candidates.md).

```bash
SMOKE_DIR="$(mktemp -d)"; chmod 777 "${SMOKE_DIR}"
mkdir -p "${SMOKE_DIR}/out"; chmod 777 "${SMOKE_DIR}/out"
# Das Runtime-Image läuft als uid 10001; ein `mktemp -d` gehört der Host-UID
# mit Mode 700 → der Container kann sonst nicht in gemountete Out-Dirs
# schreiben (reverse/transfer-Ausgabe scheitert mit "Failed to write").

cat > "${SMOKE_DIR}/d-migrate.yaml" <<'YAML'
database:
  connections:
    smoke_pg: "postgresql://dmigrate:dmigrate@d-migrate-smoke-pg:5432/dmigrate"
    smoke_mysql: "mysql://dmigrate:dmigrate@d-migrate-smoke-mysql:3306/dmigrate"
YAML

docker network inspect d-migrate-smoke >/dev/null 2>&1 || \
  docker network create d-migrate-smoke

docker run -d --rm --name d-migrate-smoke-pg --network d-migrate-smoke \
  -e POSTGRES_USER=dmigrate \
  -e POSTGRES_PASSWORD=dmigrate \
  -e POSTGRES_DB=dmigrate \
  postgres:16

docker run -d --rm --name d-migrate-smoke-mysql --network d-migrate-smoke \
  -e MYSQL_DATABASE=dmigrate \
  -e MYSQL_USER=dmigrate \
  -e MYSQL_PASSWORD=dmigrate \
  -e MYSQL_ROOT_PASSWORD=dmigrate \
  mysql:8

docker run --rm --network d-migrate-smoke \
  -e PGPASSWORD=dmigrate \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/ddl:/fixtures:ro" \
  postgres:16 sh -lc '
    until pg_isready -h d-migrate-smoke-pg -U dmigrate >/dev/null 2>&1; do sleep 1; done
    psql -h d-migrate-smoke-pg -U dmigrate -d dmigrate -f /fixtures/minimal.postgresql.sql
    psql -h d-migrate-smoke-pg -U dmigrate -d dmigrate -c "INSERT INTO users(name) VALUES (\$\$smoke user\$\$);"
  '

docker run --rm --network d-migrate-smoke \
  -v "$(pwd)/adapters/driven/formats/src/test/resources/fixtures/ddl:/fixtures:ro" \
  mysql:8 sh -lc '
    until mysqladmin ping -h d-migrate-smoke-mysql -u dmigrate -pdmigrate --silent; do sleep 1; done
    mysql -h d-migrate-smoke-mysql -u dmigrate -pdmigrate dmigrate < /fixtures/minimal.mysql.sql
  '
```

Schema Reverse:

```bash
docker run --rm --network d-migrate-smoke \
  -v "${SMOKE_DIR}:/smoke" \
  d-migrate:pre-release \
  --config /smoke/d-migrate.yaml \
  schema reverse --source smoke_pg --output /smoke/out/reverse.yaml --report /smoke/out/reverse.report.yaml
```

Schema Compare (file/db und db/db):

```bash
docker run --rm --network d-migrate-smoke \
  -v "${SMOKE_DIR}:/smoke" \
  -v "$(pwd):/repo:ro" \
  d-migrate:pre-release \
  --config /smoke/d-migrate.yaml \
  schema compare --source file:/repo/adapters/driven/formats/src/test/resources/fixtures/schemas/minimal.yaml --target db:smoke_pg

docker run --rm --network d-migrate-smoke \
  -v "${SMOKE_DIR}:/smoke" \
  d-migrate:pre-release \
  --config /smoke/d-migrate.yaml \
  schema compare --source db:smoke_pg --target db:smoke_mysql
```

Data Transfer:

```bash
docker run --rm --network d-migrate-smoke \
  -v "${SMOKE_DIR}:/smoke" \
  d-migrate:pre-release \
  --config /smoke/d-migrate.yaml \
  data transfer --source smoke_pg --target smoke_mysql --tables users
```

Aufräumen:

```bash
docker stop d-migrate-smoke-pg d-migrate-smoke-mysql 2>/dev/null
docker network rm d-migrate-smoke 2>/dev/null
rm -rf "${SMOKE_DIR}"
```

#### Tool-Export-Smoke

Fixture-Schema: `test/integration-integrations/src/test/resources/fixtures/export-test-schema.yaml`

```bash
SMOKE_OUT="$(mktemp -d)"; chmod 777 "${SMOKE_OUT}"
# uid-10001-Container muss in das gemountete /out schreiben können (s. §3.3
# oben); ohne `chmod 777` scheitern die Tool-Exports mit `EXIT=7 Failed to write`.
# Die erzeugten Dateien gehören danach uid 10001 — zum Aufräumen ggf.
# `docker run --rm -v "${SMOKE_OUT}":/o --entrypoint sh d-migrate:pre-release -c 'rm -rf /o/*'`.

# Flyway
docker run --rm \
  -v "$(pwd)/test/integration-integrations/src/test/resources/fixtures:/work:ro" \
  -v "${SMOKE_OUT}:/out" \
  d-migrate:pre-release \
  export flyway --source /work/export-test-schema.yaml --target postgresql --version 1 --output /out/flyway
# Erwartete Artefakte: /out/flyway/V1__export_test.sql

# Flyway mit Rollback
docker run --rm \
  -v "$(pwd)/test/integration-integrations/src/test/resources/fixtures:/work:ro" \
  -v "${SMOKE_OUT}:/out" \
  d-migrate:pre-release \
  export flyway --source /work/export-test-schema.yaml --target postgresql --version 2 --output /out/flyway-rb --generate-rollback
# Erwartete Artefakte: /out/flyway-rb/V2__export_test.sql, /out/flyway-rb/U2__export_test.sql

# Liquibase
docker run --rm \
  -v "$(pwd)/test/integration-integrations/src/test/resources/fixtures:/work:ro" \
  -v "${SMOKE_OUT}:/out" \
  d-migrate:pre-release \
  export liquibase --source /work/export-test-schema.yaml --target mysql --version 1.0 --output /out/liquibase --generate-rollback
# Erwartete Artefakte: /out/liquibase/changelog-1.0-export_test.xml

# Django
docker run --rm \
  -v "$(pwd)/test/integration-integrations/src/test/resources/fixtures:/work:ro" \
  -v "${SMOKE_OUT}:/out" \
  d-migrate:pre-release \
  export django --source /work/export-test-schema.yaml --target sqlite --version 0001 --output /out/django
# Erwartete Artefakte: /out/django/0001.py

# Knex
docker run --rm \
  -v "$(pwd)/test/integration-integrations/src/test/resources/fixtures:/work:ro" \
  -v "${SMOKE_OUT}:/out" \
  d-migrate:pre-release \
  export knex --source /work/export-test-schema.yaml --target sqlite --version 20260414120000 --output /out/knex
# Erwartete Artefakte: /out/knex/20260414120000.js

# Prüfen, dass alle Artefakte existieren
ls -la "${SMOKE_OUT}"/flyway/ "${SMOKE_OUT}"/flyway-rb/ "${SMOKE_OUT}"/liquibase/ "${SMOKE_OUT}"/django/ "${SMOKE_OUT}"/knex/

rm -rf "${SMOKE_OUT}"
find . -type d -name "build" -prune -exec rm -rf {} + 
```

Hinweis: Diese Smokes prüfen nur, dass die CLI die erwarteten Artefakte
erzeugt. Die echte Tool-Runtime-Validierung (Flyway→PostgreSQL,
Liquibase→PostgreSQL, Django→SQLite, Knex→SQLite) wird über
`scripts/test-integration-docker.sh` als Integrations-Test-Matrix
ausgeführt. Flyway-Undo erfordert Flyway Teams/Enterprise und ist in den
Smokes nur als Dateierzeugung, nicht als Runtime-Ausführung validiert.

### 3.4 CHANGELOG-Review

- `[Unreleased]`-Block durchgehen — alles für diesen Release Wichtige enthalten?
- Sind die Einträge nach `Added / Changed / Fixed / Deprecated / Removed / Security`
  gegliedert (Keep-a-Changelog)?
- Stimmen die Test- und Coverage-Zahlen mit dem aktuellen Stand?

### 3.5 Coverage- und Workflow-Abgleich

Drei CI-Workflows tragen den Release:

- [`.github/workflows/build.yml`](../../.github/workflows/build.yml) — Build, Tests, Coverage-Verify,
  Release-Asset-Upload als Workflow-Artefakt
- [`.github/workflows/release-homebrew.yml`](../../.github/workflows/release-homebrew.yml) — GitHub-Release-Publikation,
  `homebrew-releaser`-Push in den Tap `pt9912/homebrew-d-migrate`,
  macOS-Smoke über `verify-homebrew`
- [`.github/workflows/verify-homebrew-formula.yml`](../../.github/workflows/verify-homebrew-formula.yml) — macOS-Verifikation der
  repo-lokalen Formula nach einer Änderung an
  [`packaging/homebrew/d-migrate.rb`](../../packaging/homebrew/d-migrate.rb)

Vor jedem Release prüfen:

- deckt `koverVerify` weiterhin alle aktuellen JVM-Module ab?
- baut der Tag-Workflow `:adapters:driving:cli:assembleReleaseAssets`?
- lädt der Tag-Workflow das Artefakt `release-assets` hoch?
- bleibt der `homebrew-releaser`-`install:`-Block in
  [`release-homebrew.yml`](../../.github/workflows/release-homebrew.yml) deckungsgleich mit
  [`packaging/homebrew/d-migrate.rb`](../../packaging/homebrew/d-migrate.rb)?
- ist der `verify-homebrew`-Job in [`release-homebrew.yml`](../../.github/workflows/release-homebrew.yml) und der
  `verify-homebrew-formula`-Workflow unverändert einsatzbereit?

```bash
rg -n "koverVerify|release-assets|assembleReleaseAssets" .github/workflows/build.yml
rg -n "verify-homebrew|homebrew-releaser" .github/workflows/release-homebrew.yml
```

Coverage-Breakdown auf Paketebene prüfen — Pakete unter 90% Line-Coverage
identifizieren. Befehle und jq-Filter: siehe
[`docs/planning/done-archive/test-coverage.md`](../planning/done-archive/test-coverage.md).

### 3.6 Dokumentations- und Packaging-Konsistenz

- [`README.md`](../../README.md) „Current Status"-Block auf den neuen Release umstellen
- [`docs/planning/in-progress/roadmap.md`](../planning/in-progress/roadmap.md) Milestone als ✅ markieren, Footer-Stand aktualisieren
- [`docs/user/guide.md`](guide.md) auf den aktuellen Funktionsumfang prüfen und ggf. aktualisieren
  (Modulliste, Beispielausgaben, neue CLI-Kommandos/Optionen)
- [`spec/cli-spec.md`](../../spec/cli-spec.md), [`spec/architecture.md`](../../spec/architecture.md) und [`docs/user/releasing.md`](releasing.md) auf den
  tatsächlichen Vertrag prüfen
- [`packaging/homebrew/d-migrate.rb`](../../packaging/homebrew/d-migrate.rb) muss ZIP-basierte Installation, Java 21 und
  `bin/d-migrate`-Link konsistent beschreiben
- Seit der Versions-Quelle-Zentralisierung (2026-06-03) liest der
  gesamte Produktiv- und Test-Pfad seine Version über
  `dev.dmigrate.core.version.VersionInfo.PRODUCT_VERSION` aus
  [`hexagon/core/src/main/resources/dmigrate-version.properties`](../../hexagon/core/src/main/resources/dmigrate-version.properties).
  Diese Datei wird zur Build-Zeit aus [`build.gradle.kts`](../../build.gradle.kts)
  (`defaultProjectVersion`) befüllt — Hardcodes in
  `AbstractDdlGenerator`, `SchemaGenerateHelpers`,
  `TransformationReportWriter` und `NoOpAiProvider` gibt es nicht mehr.

---

## 4. Release-Ablauf

### 4.1 Version-Bump auf `develop`

```bash
git checkout develop
git pull --ff-only origin develop
```

Alle folgenden Dateien anpassen:

| Datei                                                                            | Änderung                                                                                                          |
| -------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| [`build.gradle.kts`](../../build.gradle.kts)                                                               | `version = "X.Y.Z-SNAPSHOT"` → `"X.Y.Z"`                                                                          |
| [`CHANGELOG.md`](../../CHANGELOG.md)                                             | `[Unreleased]` und neue Sektion `[X.Y.Z] - YYYY-MM-DD` einfügen, alle Einträge unter den neuen Header verschieben |
| [`README.md`](../../README.md)                                                                      | „Current Status"-Block: alte SNAPSHOT-Notiz durch released-Eintrag mit Link auf den GitHub-Tag ersetzen           |
| [`docs/user/guide.md`](guide.md), [`spec/cli-spec.md`](../../spec/cli-spec.md), [`spec/architecture.md`](../../spec/architecture.md), [`docs/user/releasing.md`](releasing.md) | falls der Release neue Kommandos, Flags, Distributionen oder Packaging-Schritte dokumentiert                      |
| [`docs/planning/in-progress/roadmap.md`](../planning/in-progress/roadmap.md)                                                                | Milestone-Datum aktualisieren, Footer `**Stand**:` und `**Status**:` bumpen                                       |

Hinweis: Kein einziger Kotlin-Pfad muss manuell angepasst werden —
sowohl CLI als auch alle Produktiv-Konsumenten (`AbstractDdlGenerator`,
`SchemaGenerateHelpers`, `TransformationReportWriter`,
`NoOpAiProvider`) lesen ihre Version über
`VersionInfo.PRODUCT_VERSION` aus dem zur Build-Zeit befüllten
`dmigrate-version.properties` in `:hexagon:core`. Einzige zu pflegende
Versions-Quelle ist [`build.gradle.kts`](../../build.gradle.kts) /
`defaultProjectVersion`.

### 4.2 Release-Commit auf `develop`

```bash
git add build.gradle.kts CHANGELOG.md README.md docs/planning/in-progress/roadmap.md
git add docs/user/guide.md spec/cli-spec.md spec/architecture.md docs/user/releasing.md
git commit -m "Release X.Y.Z"
git push origin develop
```

Auf grünen CI-Build warten (`gh run watch` oder GitHub-UI).

### 4.3 Merge `develop` → `main`

Direkter Merge mit Merge-Commit (kein Fast-Forward, damit der Release-Punkt im
Graph sichtbar bleibt — analog zum 0.1.0-Release):

```bash
git checkout main
git pull --ff-only origin main
git merge --no-ff develop -m "Merge develop into main for release X.Y.Z"
git push origin main
```

### 4.4 Tag setzen und pushen

```bash
git checkout main
git pull --ff-only origin main
git tag -a vX.Y.Z -m "Release X.Y.Z"
git push origin vX.Y.Z
```

**Was die CI beim Tag-Push automatisch tut**
([`.github/workflows/build.yml`](../../.github/workflows/build.yml)):

1. Build + Tests gegen den Tag-Commit
2. Coverage-Verify
3. Build der Release-Assets über `:adapters:driving:cli:assembleReleaseAssets`
4. Upload des Workflow-Artefakts `release-assets`
5. Jib baut OCI-Image (`./gradlew :adapters:driving:cli:jibDockerBuild`)
6. Login zu `ghcr.io` mit `GITHUB_TOKEN`
7. Push zu `ghcr.io/pt9912/d-migrate:X.Y.Z` und `ghcr.io/pt9912/d-migrate:latest`
8. Login zu Docker Hub und derselbe Image-Push nach `pt9912/d-migrate:X.Y.Z`
   (plus `:latest` bei Stable) — s. [4.4.1](#441-docker-hub-spiegel). Fehlt das
   Secret `DOCKERHUB_TOKEN`, werden diese Schritte still übersprungen und der
   Tag-Build bleibt grün.
9. Parallel dazu baut der Job `native-image` das **native Container-Image** und
   pusht `…:X.Y.Z-native` (+ `:native` bei Stable) nach GHCR und Docker Hub — s.
   [4.4.3](#443-natives-container-image).

Auf den grünen Tag-Build warten, **bevor** der GitHub-Release veröffentlicht
wird:

```bash
gh run watch
```

#### 4.4.1 Docker-Hub-Spiegel

Docker Hub ist ein **Spiegel**, keine zweite Build-Quelle: gepusht wird exakt
dasselbe lokal gebaute Image, das auch nach GHCR geht. `ghcr.io` bleibt die
Referenz-Registry, auf die README und Handbücher verweisen. Die `:latest`-Regel
ist identisch zu GHCR: Prerelease-Tags (Version enthält ein `-`, etwa
`v1.0.0-RC1`) aktualisieren `:latest` **nicht**.

**Eingerichtet** (2026-07-17): Ziel-Repository ist das öffentliche
[`pt9912/d-migrate`](https://hub.docker.com/r/pt9912/d-migrate); die Secrets
liegen im GitHub-Repository. Es ist **keine Handarbeit pro Release nötig** — der
Tag-Build pusht von allein.

Der Push ist an die Präsenz des Secrets `DOCKERHUB_TOKEN` gekoppelt. Ist es nicht
gesetzt (Fork, abgelaufenes oder rotiertes Token), überspringt der Tag-Build die
Docker-Hub-Schritte mit einer Notice, statt rot zu werden — ein Zusatzkanal soll
keinen Release blockieren. Kehrseite: **ein stillschweigend übersprungener Push
fällt nicht auf**, deshalb steht der Docker-Hub-Pull fest in der
Verifikationsliste ([4.8](#48-verifikation-des-releases)).

Einrichtung von Grund auf — nur nötig, falls Konto, Repository oder Token neu
aufgesetzt werden müssen:

1. Auf [hub.docker.com](https://hub.docker.com) anmelden und das Repository
   `pt9912/d-migrate` anlegen (Public).
2. Unter *Account Settings → Personal access tokens* ein Token mit dem Scope
   **Read & Write** erzeugen.
3. Im GitHub-Repository unter *Settings → Secrets and variables → Actions*
   hinterlegen:
   - Secret `DOCKERHUB_USERNAME` — der Docker-Hub-Benutzername
   - Secret `DOCKERHUB_TOKEN` — das Token aus Schritt 2
4. Optional Variable `DOCKERHUB_IMAGE`, falls das Ziel-Repository von
   `pt9912/d-migrate` abweichen soll (z. B. nach Umzug in eine Organisation).

#### 4.4.2 Native-Image-Binaries

Der Tag-Push löst zusätzlich
[`.github/workflows/native-image.yml`](../../.github/workflows/native-image.yml)
aus. Der Workflow baut mit GraalVM je Betriebssystem ein eigenständiges
Binary — native-image kompiliert nicht cross, jedes OS baut auf seinem
eigenen Runner — und hängt die Ergebnisse an den GitHub-Release.

Die Assets tragen Plattform und Version im Namen, jeweils mit
Prüfsummen-Datei:

| Asset | Runner |
| --- | --- |
| `d-migrate-X.Y.Z-linux-x64` + `.sha256` | `ubuntu-latest` |
| `d-migrate-X.Y.Z-macos-arm64` + `.sha256` | `macos-latest` |
| `d-migrate-X.Y.Z-windows-x64.exe` + `.sha256` | `windows-latest` |

Plattform und Architektur werden im Workflow zur Laufzeit aus `uname`
abgeleitet, nicht in der Matrix hinterlegt — wechselt GitHub die
Runner-Architektur, ändert sich der Asset-Name mit, statt still falsch zu
werden.

Zur Einordnung:

- Das Native-Binary ist eine **zusätzliche** Distributionsklasse. Es ersetzt
  weder Fat-JAR noch OCI-Image noch Homebrew; alle bisherigen Kanäle bleiben
  unverändert.
- Es deckt die **volle CLI** ab (`schema`, `reverse`, `compare`, `migrate`,
  `data`, `export`, `mcp` …) und braucht keine JVM. Bis Phase F.1 war der
  native Entrypoint auf ein Core-Subset (`schema validate`/`generate`)
  reduziert; seither ist der volle `MainKt` der einzige native Entrypoint.
- Jedes OS-Leg smoked sein eigenes Binary (`--help`, `schema validate --source`,
  `schema generate --source`), bevor es hochgeladen wird. Der Smoke bleibt
  DB-frei (der Runner stellt keine Datenbank); macOS und Windows sind lokal
  nicht nachbaubar, deshalb ist dieser Smoke dort die einzige Selbstvalidierung.
- Der Anhänge-Job erstellt **kein** Release, er lädt nur hoch. Das Release
  selbst kommt aus
  [`release-homebrew.yml`](../../.github/workflows/release-homebrew.yml),
  damit Titel, Notes und Prerelease-Flag in einer Hand bleiben. Findet der
  Job kein Release, wartet er in zehn Versuchen à 30 s und wird danach rot.
- **Nur das Linux-Leg ist ein Release-Gate** (Native-Slice Frage 6 = Hybrid): fehlt das
  `linux-x64`-Asset, wird der `attach-release`-Job **rot** und der Release ist **nicht** zu
  finalisieren. **macOS und Windows bleiben best-effort** — `fail-fast` ist aus, ein rotes
  macOS/Windows-Leg bricht die anderen nicht ab, sein fehlendes Asset ist zulässig und der Release
  selbst bleibt gültig. Deshalb steht die Asset-Liste in der Verifikation
  ([4.8](#48-verifikation-des-releases)).

Der Workflow lässt sich auch ohne Tag starten (`workflow_dispatch`), etwa um
das Rezept gegen `develop` zu prüfen. Solche Läufe hängen nichts an ein
Release; ihre Binaries tragen statt der Version die Commit-Kurz-SHA und
liegen nur als Workflow-Artefakt vor.

```bash
gh workflow run native-image.yml --ref develop
gh run watch
```

#### 4.4.3 Natives Container-Image

Zusätzlich zum JVM-OCI-Image (oben) und den rohen Native-Binaries
([4.4.2](#442-native-image-binaries)) publiziert der Tag-Build ein **natives
Container-Image**: dieselbe volle CLI als GraalVM-Binary, verpackt in ein
Runtime-Image (Entrypoint = Binary, Workdir `/work`, non-root, `mod_spatialite`).
Gebaut vom Job `native-image` in
[`build.yml`](../../.github/workflows/build.yml) über `make native-runtime-build`
(Stage `native-runtime` in
[`docker/native-image.Dockerfile`](../../docker/native-image.Dockerfile)).

- **Tags:** `ghcr.io/pt9912/d-migrate:X.Y.Z-native` (versioniert) plus das
  bewegliche `:native` (nur bei **Stable** — Prereleases fassen es nicht an,
  dieselbe Regel wie `:latest`). Docker-Hub-Spiegel
  `pt9912/d-migrate:X.Y.Z-native` unter derselben `DOCKERHUB_TOKEN`-Bedingung wie
  das JVM-Image ([4.4.1](#441-docker-hub-spiegel)).
- **Architektur:** nur `linux/amd64` — wie das JVM-Image.
- **Vor dem Push** wird das Image DB-frei gesmoked (`--version`, `--help`,
  `schema validate --source`); ein kaputtes Image blockiert den Push, nicht den
  Release.
- **Nur auf Tags:** der native Compile im Docker (~5–8 min) läuft nicht bei jedem
  Push; auf `develop`/`main` verifiziert `native-image.yml` (Dispatch) den Bau.

Das native Image ist eine **zusätzliche** Distributionsklasse — es ersetzt weder
das JVM-OCI-Image noch die rohen Binaries.

```bash
docker pull ghcr.io/pt9912/d-migrate:X.Y.Z-native
docker run --rm ghcr.io/pt9912/d-migrate:X.Y.Z-native --help
```

#### 4.4.4 SDKMAN

Mit dem Tag-Push publiziert
[`sdkman-release.yml`](../../.github/workflows/sdkman-release.yml) die Version an
SDKMAN (`sdk install dmigrate`). Ein **separater** Workflow, nicht Teil der
Release-Erzeugung — analog zum Native-Image-Workflow. Der Job wartet, bis das
ZIP-Asset am Release liegt, bevor er die URL an SDKMAN meldet (die API lädt sie
selbst herunter).

> **Nicht auf `on: release` umstellen.** Genau so war es zuerst gebaut, und der
> Workflow lief zum `v1.0.0-RC2`-Tag **gar nicht**: Releases, die dieser Repo per
> `GITHUB_TOKEN` erzeugt, lösen laut
> [GitHub-Doku](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)
> keine weiteren Workflow-Läufe aus. Details und die verworfenen Alternativen:
> [`sdkman-distribution.md`](../planning/next/sdkman-distribution.md).

- **Artefakt:** das UNIVERSAL-JVM-Launcher-ZIP `d-migrate-X.Y.Z.zip` (`bin/d-migrate`
  + `lib/`; braucht Java). Es ist bereits ein Release-Asset ([4.5](#45-release-assets-aus-dem-grünen-tag-build-beziehen)).
- **Mechanik:** offizielle Action `sdkman/sdkman-release-action` (SHA-gepinnt) für
  `POST /release`; `PUT /default` nur bei **Stable** (RCs werden released, aber nicht
  Default).
- **Gated auf Secrets:** `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN`. Fehlen sie,
  überspringt der Workflow den Publish mit einer Notice (kein roter Release) — wie der
  Docker-Hub-Spiegel.

**Voraussetzung (einmalig, EXTERN):** d-migrate muss ein SDKMAN-Candidate sein — PR an `sdkman/sdkman-db-migrations` (legt den Candidate `dmigrate` an — Anzeigename „d-migrate", keine Versionen), dann armored
GPG-Public-Key an `info@sdkman.io` → `Consumer-Key`/`Consumer-Token` → als GitHub-Secrets
hinterlegen. Details + Erweiterungen (plattform-native Binaries, `checksum-sha-256`):
[`docs/planning/next/sdkman-distribution.md`](../planning/next/sdkman-distribution.md).

```bash
# nach grünem sdkman-release.yml-Lauf, auf einem Host mit `sdk` + Java:
sdk install dmigrate X.Y.Z
d-migrate --version
```

### 4.5 Release-Assets aus dem grünen Tag-Build beziehen

```bash
# Tag-Run anhand des Refs identifizieren (filtert Branch-/PR-Runs heraus):
TAG="vX.Y.Z"
RUN_ID=$(gh run list --workflow build.yml --branch "${TAG}" --event push \
  --json databaseId,conclusion --jq '.[] | select(.conclusion=="success") | .databaseId' \
  | head -1)
echo "Tag-Run: ${RUN_ID}"

gh run download "${RUN_ID}" -n release-assets -D ./release-assets
ls -1 ./release-assets
cat ./release-assets/d-migrate-X.Y.Z.sha256
java -jar ./release-assets/d-migrate-X.Y.Z-all.jar --help
```

Wichtig:

- `adapters/driving/cli/build/release` aus Abschnitt 3.2 bleibt lokaler Preflight und ist nicht die <!-- d-check:ignore (Build-Ausgabe, entsteht zur Build-Zeit; ADR 0011) -->
  Publish-Quelle
- `gh release create` und `gh release upload` arbeiten nur mit
  `./release-assets/*`

### 4.6 GitHub-Release erstellen

CHANGELOG-Inhalt für die Release-Notes extrahieren und veröffentlichen:

```bash
# CHANGELOG-Sektion für X.Y.Z extrahieren (alles bis zur nächsten ##-Sektion).
# Hinweis: Das awk-Pattern mit geschachteltem exit ist fragil und hat in der
# Vergangenheit wiederholt leere oder unvollständige Release-Notes erzeugt.
# Das folgende sed-Kommando ist robuster. Die Version wird per Variable
# eingesetzt, damit kein manuelles Backslash-Escaping nötig ist:
VER="X.Y.Z"
VER_ESC=$(printf '%s' "$VER" | sed 's/\./\\./g')
sed -n "/^## \\[$VER_ESC\\]/,/^## \\[/{/^## \\[$VER_ESC\\]/!{/^## \\[/!p}}" \
  CHANGELOG.md > /tmp/release-notes.md

# Prüfen, dass die Datei nicht leer ist UND eine sinnvolle Mindestlänge hat.
# Ein reines `test -s` fängt nur leere Dateien ab, nicht versehentlich
# abgeschnittene Fragmente. Die Schwelle von 5 Zeilen ist konservativ —
# selbst ein Release mit nur einem Eintrag hat mindestens "### Added" +
# Leerzeilen + Eintrag + Leerzeile.
LINES=$(wc -l < /tmp/release-notes.md)
echo "Release notes: ${LINES} lines"
cat /tmp/release-notes.md
if [ "$LINES" -lt 5 ]; then
  echo "ERROR: release notes too short (${LINES} lines) — check CHANGELOG.md extraction"
  exit 1
fi
```

**Wichtig: Zuerst prüfen, ob der Release bereits existiert.** Der
[`release-homebrew.yml`](../../.github/workflows/release-homebrew.yml)-Workflow erstellt den GitHub-Release automatisch beim
Tag-Push. `gh release create` schlägt fehl, wenn der Release schon da ist.

```bash
# Prüfen, ob der Release bereits existiert:
if gh release view vX.Y.Z >/dev/null 2>&1; then
  echo "Release vX.Y.Z existiert bereits — Notes aktualisieren und Assets hochladen"
  gh release edit vX.Y.Z --notes-file /tmp/release-notes.md
  gh release upload vX.Y.Z ./release-assets/* --clobber
else
  echo "Release vX.Y.Z existiert noch nicht — neu erstellen"
  gh release create vX.Y.Z \
    --target main \
    --title "vX.Y.Z" \
    --notes-file /tmp/release-notes.md \
    ./release-assets/*
fi
```

### 4.7 Homebrew-Formula auf finale URL und SHA bringen

Die Formula unter [`packaging/homebrew/d-migrate.rb`](../../packaging/homebrew/d-migrate.rb) muss auf das publizierte ZIP
zeigen:

- URL: `https://github.com/pt9912/d-migrate/releases/download/vX.Y.Z/d-migrate-X.Y.Z.zip`
- SHA256: aus dem tatsächlich publizierten Release-Asset (siehe unten)
- Installation bleibt launcherbasiert unter `libexec`
- `bin/d-migrate` bleibt der Nutzer-Einstieg
- Java 21 bleibt explizit deklariert

**Zwei verschiedene Artefakte — nicht verwechseln:**

- Die **Tap-Formula** (`pt9912/homebrew-d-migrate`, der echte `brew install`-Kanal)
  wird von `homebrew-releaser` erzeugt und zeigt auf `d-migrate-X.Y.Z-homebrew.tar.gz`
  mit einer **automatisch** berechneten SHA — die ist immer self-konsistent, hier
  ist **nichts** von Hand zu pflegen.
- Das **Repo-Template** [`packaging/homebrew/d-migrate.rb`](../../packaging/homebrew/d-migrate.rb)
  (nur Referenz + Input für `verify-homebrew-formula.yml`) zeigt auf `d-migrate-X.Y.Z.zip`
  und trägt eine **manuell** gepflegte SHA. Nur diese ist unten gemeint.

ZIP-SHA **aus dem tatsächlich publizierten Release-Asset** ziehen — nicht
aus `./release-assets/*.sha256`: der `build.yml`-Workflow lädt sein eigenes
Artefakt mit eigener SHA hoch, während der publizierte ZIP aus dem
separaten [`release-homebrew.yml`](../../.github/workflows/release-homebrew.yml)-Lauf stammt und daher eine andere SHA hat.

> **⚠️ SHA erst nach dem *finalen* grünen `release-homebrew.yml`-Lauf ziehen.**
> Ein **Re-Run** dieses Workflows (z. B. nach einer `HOMEBREW_TAP_GITHUB_TOKEN`-
> Rotation) baut die Release-Assets **neu und ersetzt sie** — ZIPs sind nicht
> bit-reproduzierbar, also ändert sich die `.zip`-SHA bei jedem Lauf. Eine SHA
> aus einem früheren Lauf führt zu `Formula reports different checksum` in
> `verify-homebrew-formula.yml`. Immer den letzten Stand ziehen (die
> Download-URL ist autoritativ):

```bash
curl -sL "https://github.com/pt9912/d-migrate/releases/download/vX.Y.Z/d-migrate-X.Y.Z.zip" \
  | sha256sum
```

Nach dem Publish muss die Formula auf einem Host mit `brew` real verifiziert
werden. Modernes Homebrew lehnt `brew install --formula <path.rb>` ab und
verlangt, dass die Formula in einem Tap liegt — deshalb über einen lokalen
Ephemeral-Tap installieren (derselbe Mechanismus, den der Workflow
[`verify-homebrew-formula.yml`](../../.github/workflows/verify-homebrew-formula.yml) benutzt):

```bash
brew tap-new local/d-migrate-verify --no-git
TAP_DIR="$(brew --repository local/d-migrate-verify)"
mkdir -p "${TAP_DIR}/Formula"
cp packaging/homebrew/d-migrate.rb "${TAP_DIR}/Formula/d-migrate.rb"
# Homebrew 5.0 verweigert Install aus Fremd-Taps ohne explizites Vertrauen
# ("Refusing to load formula ... from untrusted tap"). Tap vor dem Install
# vertrauen — genau das schlägt `brew` bei Verweigerung selbst vor.
brew trust local/d-migrate-verify
brew install local/d-migrate-verify/d-migrate
d-migrate --help
```

Alternativ über den veröffentlichten Tap (bestätigt zusätzlich die
`homebrew-releaser`-Pipeline):

```bash
brew tap pt9912/d-migrate https://github.com/pt9912/homebrew-d-migrate
brew trust pt9912/d-migrate   # Homebrew 5.0: Fremd-Tap vor Install vertrauen
brew install d-migrate
d-migrate --help
```

> **Hinweis für Endnutzer:** Auch beim `brew install` aus dem veröffentlichten
> Tap verlangt Homebrew 5.0 vorab `brew trust pt9912/d-migrate`. Das gehört in
> die Installationsanleitung (README / Guide), nicht nur in die Release-Doku.

Wenn die Formula-Änderung nicht bereits im Release-Branch vorbereitet wurde,
anschließend als verifizierten Repo-Stand nachziehen.

### 4.8 Verifikation des Releases

- [ ] GitHub-Release ist sichtbar unter `https://github.com/pt9912/d-migrate/releases/tag/vX.Y.Z`
- [ ] GitHub-Release enthält ZIP, TAR, Fat JAR und SHA256
- [ ] Fat JAR startet mit `java -jar d-migrate-X.Y.Z-all.jar --help`
- [ ] Launcher-Distribution startet mit `bin/d-migrate --help`
- [ ] Image existiert: `docker pull ghcr.io/pt9912/d-migrate:X.Y.Z`
- [ ] Image-Smoke-Test:
  ```bash
  docker run --rm ghcr.io/pt9912/d-migrate:X.Y.Z --help
  ```
- [ ] Natives Container-Image ([4.4.3](#443-natives-container-image)):
      `docker pull ghcr.io/pt9912/d-migrate:X.Y.Z-native` und
      `docker run --rm ghcr.io/pt9912/d-migrate:X.Y.Z-native --help`.
- [ ] Docker-Hub-Spiegel ([4.4.1](#441-docker-hub-spiegel)) trägt dieselbe Version:
      `docker pull pt9912/d-migrate:X.Y.Z` und `docker run --rm pt9912/d-migrate:X.Y.Z --help`.
      Schlägt der Pull fehl, wurde der Push still übersprungen (Secret) — Tag-Build-Log
      auf die Notice prüfen
- [ ] Homebrew-Formula installiert und startet `d-migrate --help`
- [ ] **`linux-x64`-Native-Asset ([4.4.2](#442-native-image-binaries)) hängt am Release** (Gate,
      Frage 6 = Hybrid) — **fehlt es, den Release NICHT finalisieren** (`attach-release`-Job ist rot).
      macOS/Windows sind best-effort, je mit `.sha256`:
  ```bash
  gh release view vX.Y.Z --json assets --jq '.assets[].name' | grep -E 'linux-x64|macos-arm64|windows-x64'
  ```
      Fehlt `macos-arm64` oder `windows-x64`, ist nur ihr Matrix-Leg rot (`fail-fast` ist aus) — der
      Release bleibt gültig; Lauf von `native-image.yml` für den Tag prüfen
- [ ] Native-Binary der eigenen Plattform startet: heruntergeladen, `chmod +x`,
      `./d-migrate-X.Y.Z-<plattform> schema validate <schema.yaml>`
- [ ] CI ist auf `main` und auf dem Tag grün

### 4.9 Vorabversionen (Release Candidates / Prereleases)

Ein Release Candidate (z. B. `1.0.0-RC1` vor `1.0.0`) durchläuft denselben Ablauf
(§4.1–§4.6, §4.8), aber die Pipeline behandelt ihn **automatisch** als Prerelease.
Erkannt wird das an der SemVer-Regel „die Version enthält ein `-`" (Tag `vX.Y.Z-RCn`).

**Versionierung.** In [`build.gradle.kts`](../../build.gradle.kts) (`defaultProjectVersion`):
`X.Y.Z-RC-SNAPSHOT` → `X.Y.Z-RCn` für den Release-Commit (§4.2), Tag `vX.Y.Z-RCn`.
Post-Release (§5) zurück auf die nächste Vorab-Entwicklungsversion, z. B.
`X.Y.Z-RC(n+1)-SNAPSHOT`; erst der finale Stable-Cut bumpt auf `X.Y.Z`.

**Was die Pipeline für Prerelease-Tags abweichend tut** (automatisch, keine Handarbeit):

- **Kein `:latest`, in keiner Registry** — [`build.yml`](../../.github/workflows/build.yml) pusht
  nur das versionierte Tag; `:latest` bleibt auf GHCR **und** auf dem
  [Docker-Hub-Spiegel](#441-docker-hub-spiegel) auf dem letzten **Stable**.
- **GitHub-Release als `--prerelease`** markiert
  ([`release-homebrew.yml`](../../.github/workflows/release-homebrew.yml)) — erscheint nicht
  als „Latest release".
- **Kein Homebrew-Tap-Update** und **kein `verify-homebrew`** — Homebrew trackt nur Stable.

**Folge:** Die Homebrew-Schritte (§4.7) **entfallen** beim RC. Bei der Verifikation (§4.8) die
**versionierten** Image-Tags ziehen (nicht `:latest`) — GHCR und Docker Hub —, den Homebrew-Punkt
überspringen und prüfen, dass der GitHub-Release als Prerelease markiert ist. RC-Nutzer beziehen
die Vorabversion über das versionierte GHCR-Tag bzw. den GitHub-Prerelease.

---

## 5. Post-Release

Direkt nach dem erfolgreichen Release zurück auf `develop` und den nächsten
Entwicklungszyklus starten:

```bash
git checkout develop
git pull --ff-only origin develop
git merge --ff-only origin/main   # main-Commits in develop nachziehen (falls nötig)
```

Danach:

| Datei                             | Änderung                                                                                                                             |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| [`build.gradle.kts`](../../build.gradle.kts)                | `version = "X.Y.Z"` → nächste Entwicklungsversion, z.B. `"X.(Y+1).0-SNAPSHOT"`                                                       |
| [`CHANGELOG.md`](../../CHANGELOG.md)                    | Neuen leeren `## [Unreleased]`-Block einfügen                                                                                        |
| [`docs/planning/in-progress/roadmap.md`](../planning/in-progress/roadmap.md)                 | Falls bereits geplant: nächsten Milestone als „in Arbeit" markieren                                                                  |
| [`packaging/homebrew/d-migrate.rb`](../../packaging/homebrew/d-migrate.rb) | verifizierten URL-/SHA-Stand des zuletzt publizierten Releases nachziehen, falls die Formula erst nach dem Publish finalisiert wurde |
| `docs/implementation-plan-<version>.md` | Optional: neuen Plan für nächste Minor-Version anlegen                                                                               |

```bash
git add build.gradle.kts CHANGELOG.md docs/planning/in-progress/roadmap.md
git add packaging/homebrew/d-migrate.rb
git commit -m "Bump version to X.(Y+1).0-SNAPSHOT for next development cycle"
git push origin develop
```

---

## 6. Rollback-Szenarien

### 6.1 Tag falsch gesetzt (nur lokal, noch nicht gepusht)

```bash
git tag -d vX.Y.Z
```

### 6.2 Tag bereits gepusht, Release noch nicht veröffentlicht

```bash
git push --delete origin vX.Y.Z
git tag -d vX.Y.Z
# Korrigieren, neu taggen, neu pushen
```

Achtung: Der CI-Workflow hat ggf. bereits ein Image gepusht — siehe 6.4.

### 6.3 GitHub-Release zurückziehen

```bash
gh release delete vX.Y.Z --yes
# danach 6.2 ausführen, falls auch der Tag weg soll
```

### 6.4 Image überschreiben oder löschen (GHCR **und** Docker Hub)

Ein fehlerhaftes Image liegt in **zwei** Registries — beide müssen bereinigt
werden. Ein erneuter Tag-Push erledigt beide auf einmal, weil derselbe Job
([`build.yml`](../../.github/workflows/build.yml)) in beide pusht:

```bash
# Alternative 1: Tag neu pushen — die CI baut und überschreibt in BEIDEN Registries
git push --delete origin vX.Y.Z
git push origin vX.Y.Z   # frischer Tag-Push triggert den Workflow
```

Alternative 2 — manuell löschen, dann **je Registry einzeln**:

- **GHCR**: Web-UI → Packages → `d-migrate` → Versions → Delete
- **Docker Hub**: Web-UI → [`pt9912/d-migrate`](https://hub.docker.com/r/pt9912/d-migrate)
  → Tags → Delete

`:latest` wird beim nächsten Tag-Push in beiden Registries automatisch
überschrieben — falls der korrupte Tag der jüngste war, sollte schnell ein
Hotfix-Tag folgen. Achtung: Ist der Docker-Hub-Push wegen eines fehlenden
Secrets übersprungen worden ([4.4.1](#441-docker-hub-spiegel)), liegt das
korrupte Image dort gar nicht — dann ist nur GHCR zu bereinigen.

### 6.5 Build nach Release fehlschlägt (rote CI auf `main`)

1. **Nicht** den Tag verschieben — das verändert die Identität des Releases
2. Hotfix-Branch von `main` aus erstellen, Fix mergen
3. Neuen Patch-Release `X.Y.(Z+1)` erstellen (Schritte 4.1 – 4.6 wiederholen)
4. Im GitHub-Release-Body von `vX.Y.Z` einen Hinweis auf den Hotfix ergänzen
5. Prüfen, ob `homebrew-releaser` bereits die fehlerhafte Version in den
   Tap `pt9912/homebrew-d-migrate` gepusht hat — falls ja, wird der
   Hotfix-Release die Formula automatisch überschreiben; bis dahin ggf.
   manuell im Tap revertieren

### 6.6 `verify-homebrew`-Job eines Tags bleibt rot

Ein `verify-homebrew`-Job, der auf einem bereits gepushten Tag scheitert,
lässt sich **nicht** durch einen Workflow-Fix auf `develop`/`main`
retroaktiv grün machen: Ein Re-Run nutzt den Workflow-Stand **des
Tag-Commits**, nicht den aktuellen. Da der Tag nicht verschoben werden darf
(§6.5 Punkt 1), gilt:

- War der **`publish`-Job grün** (GitHub-Release + Tap-Push erfolgreich), ist
  die Distribution live; ein rein am `verify-homebrew`-Smoke gescheiterter Lauf
  ist **kosmetisch** — der Workflow-Fix greift ab dem nächsten Tag.
- Zur *manuellen* Bestätigung, dass die publizierte Version wirklich
  installierbar ist, den `verify-homebrew-formula.yml`-Pfad auf dem
  Post-Release-Commit heranziehen (läuft mit dem gefixten Workflow) oder lokal
  per Ephemeral-Tap (§4.7) verifizieren.

---

## 7. Release-Checkliste

Für jeden Release abhaken:

**Vorbereitung**
- [ ] `develop` und `main` auf Remote-Stand
- [ ] Working-Tree sauber
- [ ] Alle Milestone-PRs gemerged
- [ ] `docker build --target runtime -t d-migrate:pre-release .` grün (alle Tests + Smoke-Image; **nicht** `make docker-build`, s. §3.1)
- [ ] lokaler Asset-Preflight für `assembleReleaseAssets` grün
- [ ] `adapters/driving/cli/build/release` enthält ZIP, TAR, Fat JAR und SHA256 <!-- d-check:ignore (Build-Ausgabe, entsteht zur Build-Zeit; ADR 0011) -->
- [ ] Fat JAR aus dem lokalen Preflight startet mit `--help`
- [ ] Smoke-Tests gegen Fixture-Schemas grün (generate, compare file/file)
- [ ] DB-basierte Smoke-Tests grün (reverse, compare file/db + db/db, transfer)
- [ ] CHANGELOG `[Unreleased]` reviewed
- [ ] [`docs/user/guide.md`](guide.md), [`spec/cli-spec.md`](../../spec/cli-spec.md), [`spec/architecture.md`](../../spec/architecture.md) und [`docs/user/releasing.md`](releasing.md) auf aktuellem Funktionsstand
- [ ] `koverVerify`, `assembleReleaseAssets` und `release-assets` sind im Workflow korrekt verdrahtet
- [ ] `verify-homebrew` (in [`release-homebrew.yml`](../../.github/workflows/release-homebrew.yml)) und [`verify-homebrew-formula.yml`](../../.github/workflows/verify-homebrew-formula.yml) sind unverändert verdrahtet
- [ ] `homebrew-releaser`-`install:`-Block in [`release-homebrew.yml`](../../.github/workflows/release-homebrew.yml) entspricht [`packaging/homebrew/d-migrate.rb`](../../packaging/homebrew/d-migrate.rb)
- [ ] `VersionInfo.PRODUCT_VERSION` liefert die neue Version (kein
      `getVersion()`-Hardcode mehr; das `processResources`-Filtering
      in [`hexagon/core/build.gradle.kts`](../../hexagon/core/build.gradle.kts)
      ist intakt)

**Version-Bump auf `develop`**
- [ ] [`build.gradle.kts`](../../build.gradle.kts) Version
- [ ] [`CHANGELOG.md`](../../CHANGELOG.md) Sektion + Datum
- [ ] [`README.md`](../../README.md) Current-Status-Block
- [ ] [`docs/user/guide.md`](guide.md), [`spec/cli-spec.md`](../../spec/cli-spec.md), [`spec/architecture.md`](../../spec/architecture.md), [`docs/user/releasing.md`](releasing.md) falls nötig angepasst
- [ ] [`docs/planning/in-progress/roadmap.md`](../planning/in-progress/roadmap.md) Milestone-Status + Footer
- [ ] Commit `Release X.Y.Z` gepusht
- [ ] CI auf `develop` grün

**Merge & Tag**
- [ ] `develop` mit `--no-ff` in `main` gemerged und gepusht
- [ ] Tag `vX.Y.Z` auf den Merge-Commit gesetzt und gepusht
- [ ] CI für Tag grün
- [ ] Workflow-Artefakt `release-assets` des grünen Tag-Builds verfügbar
- [ ] Image auf `ghcr.io/pt9912/d-migrate:X.Y.Z` und `:latest` verfügbar
- [ ] Image auf dem Docker-Hub-Spiegel `pt9912/d-migrate:X.Y.Z` verfügbar (`:latest` nur bei Stable)
- [ ] Natives Container-Image `ghcr.io/pt9912/d-migrate:X.Y.Z-native` verfügbar (`:native` nur bei Stable), Docker-Hub-Spiegel dito
- [ ] [`native-image.yml`](../../.github/workflows/native-image.yml) für den Tag: **Linux-Leg + `attach-release` grün** (Gate, Frage 6 = Hybrid); macOS/Windows best-effort (rotes Leg = kein Asset, Release bleibt gültig)
- [ ] **SDKMAN** ([4.4.4](#444-sdkman)) — nur falls Candidate freigegeben + Secrets gesetzt: `sdkman-release.yml` grün, `sdk install dmigrate X.Y.Z` funktioniert. Sonst Skip (Notice im Log), Release bleibt gültig

**Veröffentlichung**
- [ ] `release-assets` aus dem grünen Tag-Build heruntergeladen
- [ ] Geprüft ob Release bereits existiert (`gh release view vX.Y.Z`), dann `edit`+`upload --clobber` statt `create`
- [ ] Release enthält ZIP, TAR, Fat JAR und SHA256
- [ ] Release enthält das `linux-x64`-Native-Binary mit `.sha256` (**Pflicht/Gate**); `macos-arm64` und `windows-x64.exe` best-effort (je mit `.sha256`, sofern ihr Leg grün war)
- [ ] Native-Binary der eigenen Plattform lokal gesmoked (`schema validate`)
- [ ] Image-Smoke-Test gegen `ghcr.io/pt9912/d-migrate:X.Y.Z` ok
- [ ] Image-Smoke-Test gegen `pt9912/d-migrate:X.Y.Z` (Docker Hub) ok
- [ ] Natives Image-Smoke-Test gegen `ghcr.io/pt9912/d-migrate:X.Y.Z-native` ok (`--help`, `schema validate --source`)
- [ ] [`packaging/homebrew/d-migrate.rb`](../../packaging/homebrew/d-migrate.rb) auf finale ZIP-URL und ZIP-SHA (aus dem publizierten Asset, nicht aus `release-assets/*.sha256`) gebracht
- [ ] `verify-homebrew`-Job des Tag-Builds grün (macOS-Install aus dem Tap)
- [ ] `verify-homebrew-formula`-Workflow auf dem Post-Release-Commit grün (macOS-Install aus der repo-lokalen Formula)

**Post-Release**
- [ ] [`build.gradle.kts`](../../build.gradle.kts) zurück auf nächste SNAPSHOT-Version (z.B. `X.(Y+1).0-SNAPSHOT`)
- [ ] Neuer leerer `[Unreleased]`-Block in CHANGELOG
- [ ] Formula-Änderung als Repo-Stand nachgezogen, falls sie erst nach Publish finalisiert wurde
- [ ] Commit `Bump version to X.(Y+1).0-SNAPSHOT for next development cycle` gepusht

---

## 8. Referenzen

- [`CHANGELOG.md`](../../CHANGELOG.md) — Keep-a-Changelog Format
- [`docs/planning/in-progress/roadmap.md`](../planning/in-progress/roadmap.md) — Milestone-Übersicht
- [`.github/workflows/build.yml`](../../.github/workflows/build.yml) — Build/Test/Coverage/Release-Assets-CI
- [`.github/workflows/release-homebrew.yml`](../../.github/workflows/release-homebrew.yml) — GitHub-Release + Homebrew-Tap-Publikation + macOS-Verify
- [`.github/workflows/verify-homebrew-formula.yml`](../../.github/workflows/verify-homebrew-formula.yml) — macOS-Verifikation der repo-lokalen Homebrew-Formula
- [`packaging/homebrew/d-migrate.rb`](../../packaging/homebrew/d-migrate.rb) — Homebrew-Formula-Template
- [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
- [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html)
