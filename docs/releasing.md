# Release Guide

> Anleitung für das Veröffentlichen einer neuen `d-migrate`-Version.
> Dieses Dokument beschreibt Voraussetzungen, Pre-Release-Checks, den
> Schritt-für-Schritt-Ablauf und Rollback-Szenarien.

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
| Schreibrechte auf `main` und Tags im Remote | — |
| Alle PRs für den Release sind gemerged | GitHub-Milestone leer |

---

## 3. Pre-Release-Checks

**Alle Punkte müssen grün sein, bevor der Release-Commit erstellt wird.**

### 3.1 Vollständiger Build & Test im Docker-Container

```bash
DOCKER_BUILDKIT=1 docker build -t d-migrate:pre-release .
```

Das schließt `./gradlew build :d-migrate-cli:installDist` ein und führt alle
Tests aller Module aus. Erwartetes Ergebnis: `BUILD SUCCESSFUL`.

Für eine garantiert frische Test-Ausführung ohne Build-Cache:

```bash
docker run --rm -v "$(pwd):/src" -w /src --entrypoint /bin/sh \
  eclipse-temurin:21-jdk-noble \
  -c "./gradlew --no-daemon --no-build-cache --rerun-tasks build"
```

### 3.2 Smoke-Test der CLI gegen die Fixture-Schemas

```bash
docker run --rm -v "$(pwd)/d-migrate-formats/src/test/resources/fixtures/schemas:/work" \
  d-migrate:pre-release schema generate --source /work/minimal.yaml --target postgresql

docker run --rm -v "$(pwd)/d-migrate-formats/src/test/resources/fixtures/schemas:/work" \
  d-migrate:pre-release schema generate --source /work/e-commerce.yaml --target sqlite --generate-rollback
```

### 3.3 CHANGELOG-Review

- `[Unreleased]`-Block durchgehen — alles für diesen Release Wichtige enthalten?
- Sind die Einträge nach `Added / Changed / Fixed / Deprecated / Removed / Security`
  gegliedert (Keep-a-Changelog)?
- Stimmen die Test- und Coverage-Zahlen mit dem aktuellen Stand?

### 3.4 Coverage-Verify deckt alle Module ab

Der CI-Workflow `.github/workflows/build.yml` ruft `koverVerify` aktuell nur für
`core`, `formats` und `cli` auf. **Bei jedem Release prüfen, ob neue Module
hinzugekommen sind und ggf. den Workflow ergänzen.**

```bash
./gradlew $(./gradlew :projects | grep "Project ':d-migrate-" | \
  sed -n "s/.*':\(.*\)'/:\1:koverVerify/p" | tr '\n' ' ')
```

### 3.5 Dokumentations-Konsistenz

- `README.md` „Current Status"-Block auf den neuen Release umstellen
- `docs/roadmap.md` Milestone als ✅ markieren, Footer-Stand aktualisieren
- Für Spezifikations-Updates ggf. `docs/architecture.md`, `docs/cli-spec.md`,
  `docs/design.md` anpassen
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
| `docs/roadmap.md` | Milestone-Datum aktualisieren, Footer `**Stand**:` und `**Status**:` bumpen |
| `d-migrate-driver-api/.../AbstractDdlGenerator.kt` | Falls `getVersion()` hart kodiert ist, neuen Wert eintragen |

### 4.2 Release-Commit auf `develop`

```bash
git add build.gradle.kts CHANGELOG.md README.md docs/roadmap.md
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

**Was die CI jetzt automatisch tut** (`.github/workflows/build.yml`):

1. Build + Tests gegen den Tag-Commit
2. Coverage-Verify
3. Jib baut OCI-Image (`./gradlew :d-migrate-cli:jibDockerBuild`)
4. Login zu `ghcr.io` mit `GITHUB_TOKEN`
5. Push zu `ghcr.io/pt9912/d-migrate:X.Y.Z` und `ghcr.io/pt9912/d-migrate:latest`

Auf den grünen Tag-Build warten, **bevor** der GitHub-Release veröffentlicht
wird:

```bash
gh run watch
```

### 4.5 GitHub-Release erstellen

CHANGELOG-Inhalt für die Release-Notes extrahieren und veröffentlichen:

```bash
# CHANGELOG-Sektion für X.Y.Z extrahieren (alles bis zur nächsten ##-Sektion)
awk '/^## \[X\.Y\.Z\]/,/^## \[/{if(/^## \[/ && !/^## \[X\.Y\.Z\]/)exit; print}' \
  CHANGELOG.md > /tmp/release-notes.md

gh release create vX.Y.Z \
  --target main \
  --title "v X.Y.Z" \
  --notes-file /tmp/release-notes.md
```

### 4.6 Verifikation des Releases

- [ ] GitHub-Release ist sichtbar unter `https://github.com/pt9912/d-migrate/releases/tag/vX.Y.Z`
- [ ] Image existiert: `docker pull ghcr.io/pt9912/d-migrate:X.Y.Z`
- [ ] Image-Smoke-Test:
  ```bash
  docker run --rm ghcr.io/pt9912/d-migrate:X.Y.Z --help
  ```
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
| `CHANGELOG.md` | Neuen leeren `## [Unreleased]`-Block einfügen |
| `docs/roadmap.md` | Falls bereits geplant: nächsten Milestone als „in Arbeit" markieren |
| `docs/implementation-plan-X.Y.md` | Optional: neuen Plan für nächste Minor-Version anlegen |

```bash
git add build.gradle.kts CHANGELOG.md docs/roadmap.md
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
- [ ] Smoke-Tests gegen Fixture-Schemas grün
- [ ] CHANGELOG `[Unreleased]` reviewed
- [ ] `koverVerify` deckt alle aktuellen Module ab (CI-Workflow ggf. ergänzt)
- [ ] `AbstractDdlGenerator.getVersion()` zeigt auf neue Version

**Version-Bump auf `develop`**
- [ ] `build.gradle.kts` Version
- [ ] `CHANGELOG.md` Sektion + Datum
- [ ] `README.md` Current-Status-Block
- [ ] `docs/roadmap.md` Milestone-Status + Footer
- [ ] Commit `Release X.Y.Z` gepusht
- [ ] CI auf `develop` grün

**Merge & Tag**
- [ ] `develop` mit `--no-ff` in `main` gemerged und gepusht
- [ ] Tag `vX.Y.Z` auf den Merge-Commit gesetzt und gepusht
- [ ] CI für Tag grün
- [ ] Image auf `ghcr.io/pt9912/d-migrate:X.Y.Z` und `:latest` verfügbar

**Veröffentlichung**
- [ ] `gh release create vX.Y.Z` mit CHANGELOG-Notes ausgeführt
- [ ] Image-Smoke-Test gegen `ghcr.io/pt9912/d-migrate:X.Y.Z` ok

**Post-Release**
- [ ] `build.gradle.kts` zurück auf `X.Y'.Z'-SNAPSHOT`
- [ ] Neuer leerer `[Unreleased]`-Block in CHANGELOG
- [ ] Commit `Bump version to X.Y'.Z'-SNAPSHOT for next development cycle` gepusht

---

## 8. Referenzen

- [`CHANGELOG.md`](../CHANGELOG.md) — Keep-a-Changelog Format
- [`docs/roadmap.md`](./roadmap.md) — Milestone-Übersicht
- [`.github/workflows/build.yml`](../.github/workflows/build.yml) — CI-Pipeline
- [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
- [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html)
