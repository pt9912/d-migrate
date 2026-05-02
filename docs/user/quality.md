# Qualität

## Statische Analyse (detekt)

Das Projekt nutzt [detekt](https://detekt.dev/) als Build-Gate für statische
Code-Analyse. detekt läuft automatisch als Teil von `./gradlew build` (und
damit bei jedem `docker build .`).

### Konfiguration

| Datei                            | Zweck                                              |
| -------------------------------- | -------------------------------------------------- |
| `config/detekt/detekt.yml`       | Regelwerk mit projektspezifischen Schwellenwerten  |
| `<modul>/detekt-baseline.xml`    | Bestehende Violations pro Modul (werden toleriert) |
| `build.gradle.kts` (Zeile 39-43) | Plugin-Setup für alle Submodule                    |

### Wie es wirkt

- **Neuer Code** muss alle Regeln in `detekt.yml` einhalten. Verstöße brechen
  den Build.
- **Bestehende Violations** sind in den `detekt-baseline.xml`-Dateien erfasst
  und werden vom Build ignoriert. Sie können inkrementell abgebaut werden.

### Baselines aktualisieren

| Situation                     | Baseline-Update nötig?                                              |
| ----------------------------- | ------------------------------------------------------------------- |
| Neuen Code schreiben          | Nein -- detekt prüft neuen Code unabhängig von Baselines            |
| Bestehende Violation fixen    | **Ja** -- damit die Baseline schrumpft und der Fix geschützt bleibt |
| Regeln in `detekt.yml` ändern | **Ja** -- neue Regeln/Schwellenwerte ändern die Violation-Menge     |
| Normaler Commit ohne Cleanup  | Nein                                                                |

```bash
find . -name "detekt-baseline.xml" -not -path "./.gradle/*" -delete
docker build --target detekt-baseline -t d-migrate:detekt-baseline .
docker run --rm d-migrate:detekt-baseline | tar xf -
```

Zeile 1 löscht alle bestehenden Baselines, damit stale Einträge (z. B.
für umbenannte oder aufgeteilte Klassen) nicht mitgeschleppt werden.
Zeile 2 generiert die Baselines per Gradle im Docker-Container. Zeile 3
streamt ein tar-Archiv mit allen `<modul>/detekt-baseline.xml`-Dateien nach
stdout und entpackt es direkt ins Arbeitsverzeichnis -- die Pfade im Archiv
entsprechen der Projektstruktur. Wurden alle Violations gefixt, erzeugt
der Schritt keine Baseline-Dateien und der Build läuft ohne Baselines.

### Regeln anpassen

Schwellenwerte und Regel-Toggles stehen in `config/detekt/detekt.yml`.
Nach Änderungen am Regelwerk die Baselines regenerieren (s. o.), da sich
die Violation-Menge ändern kann.

## Tests

## Coverage (Kover)

Das Projekt nutzt Kover als Build-Gate fuer aggregierte Line-Coverage.
Die Root-Konfiguration in `build.gradle.kts` setzt `minBound(90)`;
`koverVerify` bricht den Build ab, sobald dieser Mindestwert unterschritten
wird.

### Lokale Befehle

| Befehl                 | Zweck                                      |
| ---------------------- | ------------------------------------------ |
| `make coverage-gate`   | Fuehrt `test koverVerify` aus              |
| `make coverage-report` | Erzeugt Kover-HTML- und XML-Reports        |

Die Docker-Stages `coverage`, `coverage-json` und `coverage-verify` sind in
der [README](../../README.md) beschrieben. Das erledigte Analyse-Dokument
[`test-coverage.md`](../planning/done/test-coverage.md) enthaelt Befehle, um
Pakete und Klassen unterhalb der 90%-Grenze aus dem JSON-Report zu ermitteln.

### Tag-Steuerung

Tests sind über Kotest-Tags kategorisiert. Der Gradle-Build filtert
automatisch je nach Modus:

| Modus                | Befehl                                 | Tag-Filter             |
| -------------------- | -------------------------------------- | ---------------------- |
| Unit-Tests (Default) | `docker build .`                       | `!integration & !perf` |
| Integration-Tests    | `./scripts/test-integration-docker.sh` | `!perf`                |
| Perf-Tests (opt-in)  | `-Dkotest.tags=perf`                   | nur `perf`             |
| Expliziter Filter    | `-Dkotest.tags='...'`                  | wie angegeben          |

Perf-Tests (`perf`-Tag) prüfen Memory-Budgets mit großen Fixtures
(100 MB JSON, 100k YAML) und laufen nie automatisch. Ergebnisse und
Entscheidungen sind im erledigten Planungsdokument
[`0.4.0-phase-d-reorder.md`](../planning/done/0.4.0-phase-d-reorder.md)
dokumentiert. Manueller Start:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:formats:test -Dkotest.tags=perf -PtestMaxHeapSize=4g" \
  -t d-migrate:perf .
```

### Integrations-Tests

Integrations-Tests (`integration`-Tag) benötigen Docker (Testcontainers)
und laufen über das Skript:

```bash
./scripts/test-integration-docker.sh
```

Der Output wird zusätzlich in eine Log-Datei geschrieben
(`/tmp/d-migrate-integration-*.log`, konfigurierbar über
`DMIGRATE_TEST_LOG`).
