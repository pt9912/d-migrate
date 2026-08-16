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

| Befehl                              | Zweck                                                  |
| ----------------------------------- | ------------------------------------------------------ |
| `make docker-coverage-gate`         | Fuehrt das Kover-Gate im Dockerfile aus                |
| `make docker-coverage`              | Baut das Image fuer den Kover-HTML-Report              |
| `make docker-coverage-json`         | Baut das Image fuer den aggregierten Kover-JSON-Report |
| `make docker-coverage-modules-html` | Extrahiert ausgewählte per-Modul-Kover-HTML-Reports   |

Die Docker-Stages `coverage`, `coverage-json`, `coverage-verify` und
`docker-coverage-modules-html` sind in der [README](../../README.md)
beschrieben.

#### Pakete und Klassen unter der 90%-Grenze ermitteln

Die Stage `coverage-json` fuehrt den aggregierten Kover-XML-Report in ein
normalisiertes, JaCoCo-artiges JSON ueber:

```bash
docker build --target coverage-json -t d-migrate:coverage-json .
docker run --rm d-migrate:coverage-json > /tmp/coverage.json
```

Pakete unter 90% Line-Coverage, aufsteigend sortiert:

```bash
jq -r '
  .report.packages[] |
  .counters.LINE as $line |
  select($line and (($line.missed + $line.covered) > 0)) |
  { pkg: .name,
    pct: (($line.covered * 1000 / ($line.missed + $line.covered)) | floor | . / 10),
    missed: $line.missed } |
  select(.pct < 90) |
  "\(.pct)%\t\(.missed) missed\t\(.pkg)"
' /tmp/coverage.json | sort -n
```

Dieselbe Aufstellung auf Klassenebene:

```bash
jq -r '
  .report.packages[] as $pkg |
  $pkg.classes[] |
  .counters.LINE as $line |
  select($line and (($line.missed + $line.covered) > 0)) |
  { pkg: $pkg.name,
    cls: (.sourceFile // .name),
    pct: (($line.covered * 1000 / ($line.missed + $line.covered)) | floor | . / 10),
    missed: $line.missed } |
  select(.pct < 90) |
  "\(.pct)%\t\(.missed) missed\t\(.pkg)/\(.cls)"
' /tmp/coverage.json | sort -n
```

### Tag-Steuerung

Tests sind über Kotest-Tags kategorisiert. Der Gradle-Build filtert
automatisch je nach Modus:

| Modus                | Befehl                                 | Tag-Filter             |
| -------------------- | -------------------------------------- | ---------------------- |
| Unit-Tests (Default) | `docker build .`                       | `!integration & !perf` |
| Integration-Tests    | `scripts/test-integration-docker.sh` | `!perf`                |
| Perf-Tests (opt-in)  | `-Dkotest.tags=perf`                   | nur `perf`             |
| Expliziter Filter    | `-Dkotest.tags='...'`                  | wie angegeben          |

Perf-Tests (`perf`-Tag) prüfen Laufzeit- und Memory-Budgets ausgewählter
Hotpaths und laufen nie automatisch. Methodik, Hotpaths, Budgets und
Large-Schema-Scale sind in
[`../operations/performance-benchmarks.md`](../operations/performance-benchmarks.md)
beschrieben; regulärer Lauf über `make docker-perf` (opt-in/Nightly;
`PERF_GATE=true` macht die Baseline-Budgets zum harten Gate). Die frühen
Format-Budget-Entscheidungen stammen aus der 0.4.0-Reorder-Phase.
Manueller Einzelmodul-Start (Beispiel Formats):

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":adapters:driven:formats:test -Dkotest.tags=perf -PtestMaxHeapSize=4g" \
  -t d-migrate:perf .
```

### Integrations-Tests

Integrations-Tests (`integration`-Tag) benötigen Docker (Testcontainers)
und laufen über das Skript:

```bash
scripts/test-integration-docker.sh
```

Der Output wird zusätzlich in eine Log-Datei geschrieben
(`/tmp/d-migrate-integration-*.log`, konfigurierbar über
`DMIGRATE_TEST_LOG`).
