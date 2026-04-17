# Release Guide

> Anleitung für das Veröffentlichen einer neuen `d-migrate`-Version.
> Dieses Dokument beschreibt Voraussetzungen, Pre-Release-Checks, den
> Schritt-für-Schritt-Ablauf für GitHub-Release-Assets, OCI und Homebrew
> sowie Rollback-Szenarien.
>
> Hinweis: Ein oeffentlicher Maven-Central-Publish-Vertrag ist bewusst noch
> nicht Teil dieses Dokuments. Der vorgeschaltete Library-Refactor ist in
> [`implementation-plan-0.9.1.md`](./implementation-plan-0.9.1.md)
> beschrieben; Maven-Central-Portal-Publishing folgt erst mit 1.0.0.

---

## 1. Branching-Modell

`d-migrate` verwendet ein einfaches `develop → main`-Modell:

- **`develop`** — aktiver Entwicklungsbranch, hier landen alle Features
- **`main`** — enthält ausschließlich Release-Stände, jeder Merge entspricht einem Release
- **Tags** `vX.Y.Z` werden auf den Merge-Commit auf `main` gesetzt
- **Versionierung** folgt [SemVer 2.0](https://semver.org/spec/v2.0.0.html); zwischen Releases trägt `build.gradle.kts` ein `-SNAPSHOT`-Suffix

Beispiel aus 0.1.0:

```
develop:  ... → "Release 0.1.0" → (Bump 0.2.0-SNAPSHOT) → ...
                      │
                      ▼ merge
main:     ... → "Merge develop into main for release 0.1.0"  ← tag v0.1.0
```

---

## 2. Voraussetzungen

| Voraussetzung | Prüfung |
|---|---|
| Sauberer Working-Tree auf `develop` | `git status` zeigt keine Änderungen |
| `develop` ist auf dem aktuellen Stand | `git pull --ff-only origin develop` |
| `main` ist auf dem aktuellen Stand | `git fetch origin main` |
| Docker verfügbar (lokaler Pre-Release-Build) | `docker version` |
| `gh` CLI authentifiziert | `gh auth status` |
| `brew` verfügbar auf einem Verifikations-Host | `brew --version` |
| Schreibrechte auf `main` und Tags im Remote | — |
| Alle PRs für den Release sind gemerged | GitHub-Milestone leer |

---

## 3. Pre-Release-Checks

**Alle Punkte müssen grün sein, bevor der Release-Commit erstellt wird.**

### 3.1 Vollständiger Build & Test im Docker-Container

```bash
DOCKER_BUILDKIT=1 docker build -t d-migrate:pre-release .
```

Das schließt `./gradlew build :adapters:driving:cli:installDist` ein und führt alle
Tests aller Module aus. Erwartetes Ergebnis: `BUILD SUCCESSFUL`.

Für eine garantiert frische Test-Ausführung ohne Build-Cache:

```bash
docker run --rm -v "$(pwd):/src" -w /src --entrypoint /bin/sh \
  eclipse-temurin:21-jdk-noble \
  -c "./gradlew --no-daemon --no-build-cache --rerun-tasks build"
```

### 3.2 Lokaler Preflight der Release-Assets

```bash
docker build --target build \
  --build-arg GRADLE_TASKS="build :adapters:driving:cli:assembleReleaseAssets --rerun-tasks" \
  -t d-migrate:release-assets .

docker create --name d-migrate-release-assets d-migrate:release-assets
docker cp d-migrate-release-assets:/src/adapters/driving/cli/build/release ./release
docker rm d-migrate-release-assets

ls -1 ./release
cat ./release/*.sha256
java -jar ./release/*-all.jar --help
```

Wichtig:

- `./release` ist nur der lokale Preflight-Ordner
- für den eigentlichen GitHub-Release werden später ausschließlich die Dateien
  aus dem grünen Workflow-Artefakt `release-assets` verwendet

### 3.3 Smoke-Test der CLI gegen die Fixture-Schemas

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

Hinweis: Fuer realistischere DB-Smokes ueber die eingebauten Fixture-Schemas
hinaus siehe auch die priorisierte Kandidatenliste in
[`test-database-candidates.md`](./test-database-candidates.md).

```bash
SMOKE_DIR="$(mktemp -d)"
mkdir -p "${SMOKE_DIR}/out"

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

#### Tool-Export-Smoke (0.7.0)

Fixture-Schema: `adapters/driven/integrations/src/test/resources/fixtures/export-test-schema.yaml`

```bash
SMOKE_OUT="$(mktemp -d)"

# Flyway
docker run --rm \
  -v "$(pwd)/adapters/driven/integrations/src/test/resources/fixtures:/work:ro" \
  -v "${SMOKE_OUT}:/out" \
  d-migrate:pre-release \
  export flyway --source /work/export-test-schema.yaml --target postgresql --version 1 --output /out/flyway
# Erwartete Artefakte: /out/flyway/V1__export_test.sql

# Flyway mit Rollback
docker run --rm \
  -v "$(pwd)/adapters/driven/integrations/src/test/resources/fixtures:/work:ro" \
  -v "${SMOKE_OUT}:/out" \
  d-migrate:pre-release \
  export flyway --source /work/export-test-schema.yaml --target postgresql --version 2 --output /out/flyway-rb --generate-rollback
# Erwartete Artefakte: /out/flyway-rb/V2__export_test.sql, /out/flyway-rb/U2__export_test.sql

# Liquibase
docker run --rm \
  -v "$(pwd)/adapters/driven/integrations/src/test/resources/fixtures:/work:ro" \
  -v "${SMOKE_OUT}:/out" \
  d-migrate:pre-release \
  export liquibase --source /work/export-test-schema.yaml --target mysql --version 1.0 --output /out/liquibase --generate-rollback
# Erwartete Artefakte: /out/liquibase/changelog-1.0-export_test.xml

# Django
docker run --rm \
  -v "$(pwd)/adapters/driven/integrations/src/test/resources/fixtures:/work:ro" \
  -v "${SMOKE_OUT}:/out" \
  d-migrate:pre-release \
  export django --source /work/export-test-schema.yaml --target sqlite --version 0001 --output /out/django
# Erwartete Artefakte: /out/django/0001.py

# Knex
docker run --rm \
  -v "$(pwd)/adapters/driven/integrations/src/test/resources/fixtures:/work:ro" \
  -v "${SMOKE_OUT}:/out" \
  d-migrate:pre-release \
  export knex --source /work/export-test-schema.yaml --target sqlite --version 20260414120000 --output /out/knex
# Erwartete Artefakte: /out/knex/20260414120000.js

# Prüfen, dass alle Artefakte existieren
ls -la "${SMOKE_OUT}"/flyway/ "${SMOKE_OUT}"/flyway-rb/ "${SMOKE_OUT}"/liquibase/ "${SMOKE_OUT}"/django/ "${SMOKE_OUT}"/knex/

rm -rf "${SMOKE_OUT}"
```

Hinweis: Diese Smokes pruefen nur, dass die CLI die erwarteten Artefakte
erzeugt. Die echte Tool-Runtime-Validierung (Flyway→PostgreSQL,
Liquibase→PostgreSQL, Django→SQLite, Knex→SQLite) wird ueber
`./scripts/test-integration-docker.sh` als Integrations-Test-Matrix
ausgefuehrt. Flyway-Undo erfordert Flyway Teams/Enterprise und ist in den
Smokes nur als Dateierzeugung, nicht als Runtime-Ausfuehrung validiert.

### 3.4 CHANGELOG-Review

- `[Unreleased]`-Block durchgehen — alles für diesen Release Wichtige enthalten?
- Sind die Einträge nach `Added / Changed / Fixed / Deprecated / Removed / Security`
  gegliedert (Keep-a-Changelog)?
- Stimmen die Test- und Coverage-Zahlen mit dem aktuellen Stand?

### 3.5 Coverage- und Workflow-Abgleich

Drei CI-Workflows tragen den Release:

- `.github/workflows/build.yml` — Build, Tests, Coverage-Verify,
  Release-Asset-Upload als Workflow-Artefakt
- `.github/workflows/release-homebrew.yml` — GitHub-Release-Publikation,
  `homebrew-releaser`-Push in den Tap `pt9912/homebrew-d-migrate`,
  macOS-Smoke über `verify-homebrew`
- `.github/workflows/verify-homebrew-formula.yml` — macOS-Verifikation der
  repo-lokalen Formula nach einer Änderung an
  `packaging/homebrew/d-migrate.rb`

Vor jedem Release prüfen:

- deckt `koverVerify` weiterhin alle aktuellen JVM-Module ab?
- baut der Tag-Workflow `:adapters:driving:cli:assembleReleaseAssets`?
- lädt der Tag-Workflow das Artefakt `release-assets` hoch?
- bleibt der `homebrew-releaser`-`install:`-Block in
  `release-homebrew.yml` deckungsgleich mit
  `packaging/homebrew/d-migrate.rb`?
- ist der `verify-homebrew`-Job in `release-homebrew.yml` und der
  `verify-homebrew-formula`-Workflow unverändert einsatzbereit?

```bash
rg -n "koverVerify|release-assets|assembleReleaseAssets" .github/workflows/build.yml
rg -n "verify-homebrew|homebrew-releaser" .github/workflows/release-homebrew.yml
```

### 3.6 Dokumentations- und Packaging-Konsistenz

- `README.md` „Current Status"-Block auf den neuen Release umstellen
- `docs/roadmap.md` Milestone als ✅ markieren, Footer-Stand aktualisieren
- `docs/guide.md` auf den aktuellen Funktionsumfang prüfen und ggf. aktualisieren
  (Modulliste, Beispielausgaben, neue CLI-Kommandos/Optionen)
- `docs/cli-spec.md`, `docs/architecture.md` und `docs/releasing.md` auf den
  tatsächlichen Vertrag prüfen
- `packaging/homebrew/d-migrate.rb` muss ZIP-basierte Installation, Java 21 und
  `bin/d-migrate`-Link konsistent beschreiben
- Falls `AbstractDdlGenerator.getVersion()` hart kodiert ist: Wert prüfen

---

## 4. Release-Ablauf

### 4.1 Version-Bump auf `develop`

```bash
git checkout develop
git pull --ff-only origin develop
```

Alle folgenden Dateien anpassen:

| Datei | Änderung |
|---|---|
| `build.gradle.kts` | `version = "X.Y.Z-SNAPSHOT"` → `"X.Y.Z"` |
| `CHANGELOG.md` | `[Unreleased]` und neue Sektion `[X.Y.Z] - YYYY-MM-DD` einfügen, alle Einträge unter den neuen Header verschieben |
| `README.md` | „Current Status"-Block: alte SNAPSHOT-Notiz durch released-Eintrag mit Link auf den GitHub-Tag ersetzen |
| `docs/guide.md`, `docs/cli-spec.md`, `docs/architecture.md`, `docs/releasing.md` | falls der Release neue Kommandos, Flags, Distributionen oder Packaging-Schritte dokumentiert |
| `docs/roadmap.md` | Milestone-Datum aktualisieren, Footer `**Stand**:` und `**Status**:` bumpen |
| `adapters/driven/driver-common/.../AbstractDdlGenerator.kt` | Falls `getVersion()` hart kodiert ist, neuen Wert eintragen |
| `adapters/driving/cli/.../Main.kt` | `versionOption("X.Y.Z-SNAPSHOT")` → `"X.Y.Z"` |

### 4.2 Release-Commit auf `develop`

```bash
git add build.gradle.kts CHANGELOG.md README.md docs/roadmap.md \
  adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt
git add docs/guide.md docs/cli-spec.md docs/architecture.md docs/releasing.md
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

**Was die CI im 0.5.0-Release-Flow automatisch tut**
(`.github/workflows/build.yml`):

1. Build + Tests gegen den Tag-Commit
2. Coverage-Verify
3. Build der Release-Assets über `:adapters:driving:cli:assembleReleaseAssets`
4. Upload des Workflow-Artefakts `release-assets`
5. Jib baut OCI-Image (`./gradlew :adapters:driving:cli:jibDockerBuild`)
6. Login zu `ghcr.io` mit `GITHUB_TOKEN`
7. Push zu `ghcr.io/pt9912/d-migrate:X.Y.Z` und `ghcr.io/pt9912/d-migrate:latest`

Auf den grünen Tag-Build warten, **bevor** der GitHub-Release veröffentlicht
wird:

```bash
gh run watch
```

### 4.5 Release-Assets aus dem grünen Tag-Build beziehen

```bash
gh run list --workflow build.yml --limit 10
gh run download <tag-run-id> -n release-assets -D ./release-assets
ls -1 ./release-assets
cat ./release-assets/d-migrate-X.Y.Z.sha256
java -jar ./release-assets/d-migrate-X.Y.Z-all.jar --help
```

Wichtig:

- `./release` aus Abschnitt 3.2 bleibt lokaler Preflight und ist nicht die
  Publish-Quelle
- `gh release create` und `gh release upload` arbeiten nur mit
  `./release-assets/*`

### 4.6 GitHub-Release erstellen

CHANGELOG-Inhalt für die Release-Notes extrahieren und veröffentlichen:

```bash
# CHANGELOG-Sektion für X.Y.Z extrahieren (alles bis zur nächsten ##-Sektion).
# Hinweis: Das awk-Pattern mit geschachteltem exit ist fragil und hat in der
# Vergangenheit wiederholt leere oder unvollständige Release-Notes erzeugt.
# Das folgende sed-Kommando ist robuster:
sed -n '/^## \[X\.Y\.Z\]/,/^## \[/{/^## \[X\.Y\.Z\]/!{/^## \[/!p}}' \
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

Der `release-homebrew.yml`-Workflow erstellt den GitHub-Release automatisch beim
Tag-Push. Falls der Release dadurch bereits existiert, schlägt `gh release create`
fehl. In diesem Fall die Release-Notes nachträglich setzen:

```bash
# Release existiert bereits (vom Workflow erstellt) → Body aktualisieren:
gh release edit vX.Y.Z --notes-file /tmp/release-notes.md
```

Falls der Release noch nicht existiert (Workflow deaktiviert oder fehlgeschlagen):

```bash
gh release create vX.Y.Z \
  --target main \
  --title "vX.Y.Z" \
  --notes-file /tmp/release-notes.md \
  ./release-assets/*
```

Falls Assets erneut hochgeladen werden müssen:

```bash
gh release upload vX.Y.Z ./release-assets/* --clobber
```

### 4.7 Homebrew-Formula auf finale URL und SHA bringen

Die Formula unter `packaging/homebrew/d-migrate.rb` muss auf das publizierte ZIP
zeigen:

- URL: `https://github.com/pt9912/d-migrate/releases/download/vX.Y.Z/d-migrate-X.Y.Z.zip`
- SHA256: die ZIP-Zeile aus `./release-assets/d-migrate-X.Y.Z.sha256`
- Installation bleibt launcherbasiert unter `libexec`
- `bin/d-migrate` bleibt der Nutzer-Einstieg
- Java 21 bleibt explizit deklariert

ZIP-SHA **aus dem tatsächlich publizierten Release-Asset** ziehen — nicht
aus `./release-assets/*.sha256`: der `build.yml`-Workflow lädt sein eigenes
Artefakt mit eigener SHA hoch, während der publizierte ZIP aus dem
separaten `release-homebrew.yml`-Lauf stammt und daher eine andere SHA hat:

```bash
curl -sL "https://github.com/pt9912/d-migrate/releases/download/vX.Y.Z/d-migrate-X.Y.Z.zip" \
  | sha256sum
# oder:
gh release download vX.Y.Z --pattern 'd-migrate-*.zip'
sha256sum d-migrate-*.zip
```

Nach dem Publish muss die Formula auf einem Host mit `brew` real verifiziert
werden. Modernes Homebrew lehnt `brew install --formula <path.rb>` ab und
verlangt, dass die Formula in einem Tap liegt — deshalb ueber einen lokalen
Ephemeral-Tap installieren (derselbe Mechanismus, den der Workflow
`verify-homebrew-formula.yml` benutzt):

```bash
brew tap-new local/d-migrate-verify --no-git
TAP_DIR="$(brew --repository local/d-migrate-verify)"
mkdir -p "${TAP_DIR}/Formula"
cp packaging/homebrew/d-migrate.rb "${TAP_DIR}/Formula/d-migrate.rb"
brew install local/d-migrate-verify/d-migrate
d-migrate --help
```

Alternativ ueber den veroeffentlichten Tap (bestaetigt zusaetzlich die
`homebrew-releaser`-Pipeline):

```bash
brew tap pt9912/d-migrate https://github.com/pt9912/homebrew-d-migrate
brew install d-migrate
d-migrate --help
```

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
- [ ] Homebrew-Formula installiert und startet `d-migrate --help`
- [ ] CI ist auf `main` und auf dem Tag grün

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

| Datei | Änderung |
|---|---|
| `build.gradle.kts` | `version = "X.Y.Z"` → `"X.Y'.Z'-SNAPSHOT"` (nächste Minor: `X.(Y+1).0-SNAPSHOT`) |
| `adapters/driving/cli/.../Main.kt` | `versionOption("X.Y.Z")` → `"X.Y'.Z'-SNAPSHOT"` |
| `CHANGELOG.md` | Neuen leeren `## [Unreleased]`-Block einfügen |
| `docs/roadmap.md` | Falls bereits geplant: nächsten Milestone als „in Arbeit" markieren |
| `packaging/homebrew/d-migrate.rb` | verifizierten URL-/SHA-Stand des zuletzt publizierten Releases nachziehen, falls die Formula erst nach dem Publish finalisiert wurde |
| `docs/implementation-plan-X.Y.md` | Optional: neuen Plan für nächste Minor-Version anlegen |

```bash
git add build.gradle.kts CHANGELOG.md docs/roadmap.md \
  adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt
git add packaging/homebrew/d-migrate.rb
git commit -m "Bump version to X.Y'.Z'-SNAPSHOT for next development cycle"
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

### 6.4 Image im GHCR überschreiben oder löschen

```bash
# Alternative 1: Image-Version löschen (Web-UI: Packages → d-migrate → Versions → Delete)
# Alternative 2: Tag neu pushen — die CI baut und überschreibt automatisch
git push --delete origin vX.Y.Z
git push origin vX.Y.Z   # frischer Tag-Push triggert den Workflow
```

`:latest` wird beim nächsten Tag-Push automatisch überschrieben — falls der
korrupte Tag der jüngste war, sollte schnell ein Hotfix-Tag folgen.

### 6.5 Build nach Release fehlschlägt (rote CI auf `main`)

1. **Nicht** den Tag verschieben — das verändert die Identität des Releases
2. Hotfix-Branch von `main` aus erstellen, Fix mergen
3. Neuen Patch-Release `X.Y.(Z+1)` erstellen (Schritte 4.1 – 4.6 wiederholen)
4. Im GitHub-Release-Body von `vX.Y.Z` einen Hinweis auf den Hotfix ergänzen

---

## 7. Release-Checkliste

Für jeden Release abhaken:

**Vorbereitung**
- [ ] `develop` und `main` auf Remote-Stand
- [ ] Working-Tree sauber
- [ ] Alle Milestone-PRs gemerged
- [ ] `docker build -t d-migrate:pre-release .` grün
- [ ] lokaler Asset-Preflight für `assembleReleaseAssets` grün
- [ ] `./release` enthält ZIP, TAR, Fat JAR und SHA256
- [ ] Fat JAR aus dem lokalen Preflight startet mit `--help`
- [ ] Smoke-Tests gegen Fixture-Schemas grün (generate, compare file/file)
- [ ] DB-basierte Smoke-Tests grün (reverse, compare file/db + db/db, transfer)
- [ ] CHANGELOG `[Unreleased]` reviewed
- [ ] `docs/guide.md`, `docs/cli-spec.md`, `docs/architecture.md` und `docs/releasing.md` auf aktuellem Funktionsstand
- [ ] `koverVerify`, `assembleReleaseAssets` und `release-assets` sind im Workflow korrekt verdrahtet
- [ ] `verify-homebrew` (in `release-homebrew.yml`) und `verify-homebrew-formula.yml` sind unverändert verdrahtet
- [ ] `homebrew-releaser`-`install:`-Block in `release-homebrew.yml` entspricht `packaging/homebrew/d-migrate.rb`
- [ ] `AbstractDdlGenerator.getVersion()` zeigt auf neue Version
- [ ] `Main.kt` `versionOption()` zeigt auf neue Version

**Version-Bump auf `develop`**
- [ ] `build.gradle.kts` Version
- [ ] `Main.kt` `versionOption()`
- [ ] `CHANGELOG.md` Sektion + Datum
- [ ] `README.md` Current-Status-Block
- [ ] `docs/guide.md`, `docs/cli-spec.md`, `docs/architecture.md`, `docs/releasing.md` falls nötig angepasst
- [ ] `docs/roadmap.md` Milestone-Status + Footer
- [ ] Commit `Release X.Y.Z` gepusht
- [ ] CI auf `develop` grün

**Merge & Tag**
- [ ] `develop` mit `--no-ff` in `main` gemerged und gepusht
- [ ] Tag `vX.Y.Z` auf den Merge-Commit gesetzt und gepusht
- [ ] CI für Tag grün
- [ ] Workflow-Artefakt `release-assets` des grünen Tag-Builds verfügbar
- [ ] Image auf `ghcr.io/pt9912/d-migrate:X.Y.Z` und `:latest` verfügbar

**Veröffentlichung**
- [ ] `release-assets` aus dem grünen Tag-Build heruntergeladen
- [ ] `gh release create vX.Y.Z` oder `gh release upload vX.Y.Z` mit `./release-assets/*` ausgeführt
- [ ] Release enthält ZIP, TAR, Fat JAR und SHA256
- [ ] Image-Smoke-Test gegen `ghcr.io/pt9912/d-migrate:X.Y.Z` ok
- [ ] `packaging/homebrew/d-migrate.rb` auf finale ZIP-URL und ZIP-SHA (aus dem publizierten Asset, nicht aus `release-assets/*.sha256`) gebracht
- [ ] `verify-homebrew`-Job des Tag-Builds grün (macOS-Install aus dem Tap)
- [ ] `verify-homebrew-formula`-Workflow auf dem Post-Release-Commit grün (macOS-Install aus der repo-lokalen Formula)

**Post-Release**
- [ ] `build.gradle.kts` zurück auf `X.Y'.Z'-SNAPSHOT`
- [ ] Neuer leerer `[Unreleased]`-Block in CHANGELOG
- [ ] Formula-Änderung als Repo-Stand nachgezogen, falls sie erst nach Publish finalisiert wurde
- [ ] Commit `Bump version to X.Y'.Z'-SNAPSHOT for next development cycle` gepusht

---

## 8. Referenzen

- [`CHANGELOG.md`](../CHANGELOG.md) — Keep-a-Changelog Format
- [`docs/roadmap.md`](./roadmap.md) — Milestone-Übersicht
- [`.github/workflows/build.yml`](../.github/workflows/build.yml) — Build/Test/Coverage/Release-Assets-CI
- [`.github/workflows/release-homebrew.yml`](../.github/workflows/release-homebrew.yml) — GitHub-Release + Homebrew-Tap-Publikation + macOS-Verify
- [`.github/workflows/verify-homebrew-formula.yml`](../.github/workflows/verify-homebrew-formula.yml) — macOS-Verifikation der repo-lokalen Homebrew-Formula
- [`packaging/homebrew/d-migrate.rb`](../packaging/homebrew/d-migrate.rb) — Homebrew-Formula-Template
- [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
- [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html)
