# Test-Coverage-Analyse

> Werkzeuge und Befehle zur Analyse der Kover-Coverage auf Paket- und
> Klassenebene. Ziel: **90% Line-Coverage pro Modul**.

---

## 1. Coverage-JSON erzeugen

Der aggregierte Kover-XML-Report wird im Dockerfile in der Stage
`coverage-json` zuerst via `yq` eingelesen und anschliessend via `jq`
in ein normalisiertes, JaCoCo-artiges JSON ueberfuehrt. Diese Datei
wird als einziges Artefakt im Image abgelegt.

```bash
docker build --target coverage-json -t d-migrate:coverage-json .
docker run --rm d-migrate:coverage-json > /tmp/coverage.json
```

Voraussetzung: Docker und ein erfolgreicher Build (Tests laufen in der
`build`-Stage).

---

## 2. Pakete unter 90% Line-Coverage

Listet alle Pakete auf, deren Line-Coverage unter 90% liegt, sortiert
nach Prozent aufsteigend:

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

Beispielausgabe:

```
66.6%   4 missed    dev/dmigrate/profiling
83.3%   38 missed   dev/dmigrate/format/data/yaml
85.3%   37 missed   dev/dmigrate/cli/config
88.5%   21 missed   dev/dmigrate/format/data/json
89%     372 missed  dev/dmigrate/cli/commands
```

---

## 3. Klassen unter 90% Line-Coverage

Listet alle Klassen auf, deren Line-Coverage unter 90% liegt, sortiert
nach Prozent aufsteigend:

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

Beispielausgabe:

```
41.8%   25 missed   dev/dmigrate/cli/config/PipelineCheckpointResolver.kt
66.6%   4 missed    dev/dmigrate/profiling/ProfilingSummary.kt
83.3%   8 missed    dev/dmigrate/cli/config/NamedConnectionResolver.kt
```

---

## 4. Klassen mit den meisten verfehlten Zeilen

Zeigt pro Paket die Klassen mit den meisten verfehlten Zeilen, sortiert
nach Anzahl absteigend. `PAKETNAME` durch den Paketpfad ersetzen
(z.B. `dev/dmigrate/cli/config`):

```bash
jq -r --arg pkg "dev/dmigrate/PAKETNAME" '
  .report.packages[] | select(.name == $pkg) |
  .classes[] |
  .counters.LINE as $line |
  select($line and $line.missed > 0) |
  { file: (.sourceFile // .name),
    missed: $line.missed,
    total: ($line.missed + $line.covered),
    pct: (($line.covered * 1000 / ($line.missed + $line.covered)) | floor | . / 10)
  } |
  "\(.missed)/\(.total) missed (\(.pct)%)\t\(.file)"
' /tmp/coverage.json | sort -nr
```

Beispielausgabe für `dev/dmigrate/cli/config`:

```
25/43 missed (41.8%)    PipelineCheckpointResolver.kt
8/87 missed (90.8%)     NamedConnectionResolver.kt
3/100 missed (97%)      I18nSettingsResolver.kt
```

---

## 5. Schwellenwert anpassen

Um einen anderen Schwellenwert als 90% zu verwenden, die Zeile
`select(.pct < 90)` in Abschnitt 2 anpassen.

Fuer Branch-Coverage statt Line-Coverage `.counters.LINE` durch
`.counters.BRANCH` ersetzen.

---

## 6. Referenzen

- [Dockerfile](../../../Dockerfile) — Stages `build`, `coverage-build`, `coverage-json`
- [`.github/workflows/build.yml`](../../../.github/workflows/build.yml) — koverVerify und koverHtmlReport
- [`docs/user/releasing.md`](../../user/releasing.md) — Abschnitt 3.5 verweist hierher
