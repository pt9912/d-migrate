# Test-Coverage-Analyse

> Werkzeuge und Befehle zur Analyse der Kover-Coverage auf Paket- und
> Klassenebene. Ziel: **90% Line-Coverage pro Modul**.

---

## 1. Coverage-JSON erzeugen

Der aggregierte Kover-XML-Report wird im Dockerfile in der Stage
`coverage-json` via `yq` nach JSON konvertiert und als einzige Datei
im Image abgelegt.

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
  .report.package[] |
  {
    name: ."+@name",
    classes: [.class // [] | if type == "array" then .[] else . end |
      { counters: [.counter // [] | if type == "array" then .[] else . end |
          select(."+@type" == "LINE") |
          { missed: (."+@missed" | tonumber), covered: (."+@covered" | tonumber) }
      ]}
    ]
  } |
  ((.classes | map(.counters[0] // {missed:0,covered:0}) | map(.missed) | add // 0)) as $m |
  ((.classes | map(.counters[0] // {missed:0,covered:0}) | map(.covered) | add // 0)) as $c |
  select(($m + $c) > 0) |
  { pkg: .name, pct: (($c * 1000 / ($m + $c)) | floor | . / 10), missed: $m } |
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

## 3. Klassen mit den meisten verfehlten Zeilen

Zeigt pro Paket die Klassen mit den meisten verfehlten Zeilen, sortiert
nach Anzahl absteigend. `PAKETNAME` durch den Paketpfad ersetzen
(z.B. `dev/dmigrate/cli/config`):

```bash
jq -r --arg pkg "dev/dmigrate/PAKETNAME" '
  .report.package[] | select(."+@name" == $pkg) |
  [.class // [] | if type == "array" then .[] else . end |
    { file: ."+@sourcefilename",
      counters: [.counter // [] | if type == "array" then .[] else . end |
        select(."+@type" == "LINE") |
        { missed: (."+@missed" | tonumber), covered: (."+@covered" | tonumber) }
    ]} |
    select(.counters | length > 0) | select(.counters[0].missed > 0) |
    { file, missed: .counters[0].missed,
      total: (.counters[0].missed + .counters[0].covered),
      pct: ((.counters[0].covered * 1000 / (.counters[0].missed + .counters[0].covered)) | floor | . / 10)
    }
  ] | sort_by(-.missed)[] |
  "\(.missed)/\(.total) missed (\(.pct)%)\t\(.file)"
' /tmp/coverage.json
```

Beispielausgabe für `dev/dmigrate/cli/config`:

```
25/43 missed (41.8%)    PipelineCheckpointResolver.kt
8/87 missed (90.8%)     NamedConnectionResolver.kt
3/100 missed (97%)      I18nSettingsResolver.kt
```

---

## 4. Schwellenwert anpassen

Um einen anderen Schwellenwert als 90% zu verwenden, die Zeile
`select(.pct < 90)` in Abschnitt 2 anpassen.

Für Branch-Coverage statt Line-Coverage `"LINE"` durch `"BRANCH"`
ersetzen.

---

## 5. Referenzen

- [Dockerfile](../Dockerfile) — Stages `build`, `coverage-build`, `coverage-json`
- [`.github/workflows/build.yml`](../.github/workflows/build.yml) — koverVerify und koverHtmlReport
- [`docs/releasing.md`](./releasing.md) — Abschnitt 3.5 verweist hierher
