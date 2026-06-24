# Perf-Acceptance-Ergebnisse als CI-Artefakt sichern (TPC-H 4c Nightly)

> **Status:** Plan-Entwurf, Review offen (2026-06-24)
> **Typ:** operativer Follow-up zur [4c-Slice](../in-progress/tpc-4c-volume-acceptance-slice.md)
> (Punkt „Operativer Rest" — Nightly-Runner designieren + `CALIB_REFERENCE_MS` pinnen).
> **Trigger:** Frage „wo werden die Perf-Ergebnisse abgelegt?" — Antwort: **nirgends**.
> Sie existieren ausschließlich im flüchtigen GitHub-Actions-Job-Log. Das macht den
> dokumentierten nächsten Schritt (Bootstrap-Median ablesen → `CALIB_REFERENCE_MS`
> pinnen, `perf-acceptance.yml` Z. 18–20) unnötig umständlich.
> **Nicht RC-blockierend**, rein operativ/QoL.

## Problem (codeverifiziert 2026-06-24)

Der Nightly-Lauf erzeugt **keinen persistenten Ergebnis-Stand**:

- **`smoke-tpch-perf.sh` `log()` (Z. 46) schreibt nur nach `stdout`** (`printf`), in keine
  Datei. Damit liegen **Kalibrier-Median, Durchsatz-Diagnose und Verlustfreiheits-/
  Resume-Verdikt nur im stdout-Strom** — und nach dem Lauf nur noch im Actions-Job-Log.
- Die `/tmp/tpch-*.log` enthalten **rohe Subprozess-Ausgaben** (Export/Import/Schema/
  Resume/Kalibrierung), **nicht** die berechneten Verdikte/den Median; sie werden zudem
  nur **bei Fehler** via `cat`/`tail` ins Log gespiegelt.
- **`perf-acceptance.yml` hat keinen `upload-artifact`-Step** (5 Steps: checkout,
  compose-Check, build, run Z. 45–51, cleanup). Nichts wird host-persistent oder als
  CI-Artefakt gesichert.

**Folge:** Der per Workflow-Header (Z. 18–20) und Skript (Z. 245) geforderte Ablauf
„Bootstrap-Lauf → gemeldeten Median ablesen → `CALIB_REFERENCE_MS` als Repo-Variable
pinnen" zwingt zum manuellen Greppen im Job-Log. Es gibt keinen abrufbaren Ergebnis-Dump.

## Ziel

Nach jedem Nightly-/`workflow_dispatch`-Lauf ein **abrufbares Artefakt** mit (a) dem
vollen Lauf-Log (faithful, inkl. Median + Verdikte) und (b) einem **maschinenlesbaren
Summary**, sodass das Runner-Pinnen ohne Log-Grep funktioniert.

## Randbedingungen (verifiziert)

- **`sample-db-purge` = `docker compose down -v`** (Makefile Z. 375) — fasst **weder
  `/tmp/*.log` noch `out/` an**. Der Cleanup-Step ist also für die Artefakt-Quellen
  harmlos; kein Ordering-Zwang gegenüber dem Upload.
- **`out/` ist gitignored** (`examples/sample-db/.gitignore` Z. 12–13: `out/*`, außer
  `.gitkeep`) → ein Summary-File dort verschmutzt git nicht.
- **Alle Ergebnis-Werte stehen am Lauf-Ende bereits in Shell-Variablen** im selben Scope:
  `exp_rps` (Z. 100), `imp_rps` (Z. 112), `total_rows` (Z. 100), `CALIB_MS` (Z. 142),
  `HOST_OK` (Z. 150). Verlustfreiheit/Resume sind implizit „OK", sobald `SUCCESS` (Z. 244)
  erreicht ist — sonst `fail()` → `exit 1` (Z. 47). Ein Summary-Writer braucht also nichts
  zu fädeln.
- **GitHub-Actions-Default-Shell läuft mit `-eo pipefail`** → ein `… | tee log` erhält den
  `make`-Exit-Code. (Job hat ohnehin `continue-on-error: true`, Z. 34.)

## Design (zwei komplementäre Teile)

Die zwei Teile sind **nicht alternativ**: Teil B braucht den Upload aus Teil A ohnehin.

### Teil A — Workflow: Log teen + hochladen (`perf-acceptance.yml`)

1. Run-Step (Z. 51) stdout in Datei spiegeln:
   ```yaml
   run: make sample-db-tpch-perf PERF_GATE=true 2>&1 | tee perf-acceptance-run.log
   ```
2. Neuer Upload-Step **mit `if: always()`** (läuft auch, wenn der diagnostische Run-Step
   „fehlschlägt"):
   ```yaml
   - name: Upload perf results
     if: always()
     uses: actions/upload-artifact@v4
     with:
       name: tpch-perf-${{ github.run_id }}
       path: |
         perf-acceptance-run.log
         /tmp/tpch-*.log
         examples/sample-db/out/tpch-perf-summary.env
       retention-days: 30
   ```
   **Bewusst NICHT** dabei: `examples/sample-db/out/tpch-perf-export/**` (≈1,7 Mio Zeilen
   TPC-H-Export, hunderte MB).

### Teil B — Skript: maschinenlesbares Summary (`smoke-tpch-perf.sh`)

Eine `summary()`-Funktion schreibt die bereits vorhandenen Variablen nach
`$OUT_DIR/tpch-perf-summary.env` (gitignored), Kernzeile zum Pinnen:

```
CALIB_REFERENCE_MS=<CALIB_MS>
HOST_OK=<0|1>
EXPORT_RPS=<exp_rps>
IMPORT_RPS=<imp_rps>
TOTAL_ROWS=<total_rows>
PERF_GATE=<true|false>
RESULT=<SUCCESS|FAIL>
```

`.env`-Format, weil es per `source` / `>> $GITHUB_ENV` direkt weiterverwendbar ist (späterer
Auto-Pin-Step optional). Schreiben **nach** allen Zeitmessungen → keine Mess-Perturbation.

## Offene Entscheidung (Review-Input erbeten)

**Summary nur bei Erfolg vs. auch bei Abbruch.**
- **Variante 1 (End-Writer, simpel):** `summary()` direkt vor/nach `SUCCESS` (Z. 244).
  Bei einem harten Mid-Run-`fail()` entsteht **kein** Summary — fürs Hauptziel
  („erfolgreichen Bootstrap-Median ablesen") ausreichend.
- **Variante 2 (`trap … EXIT`, robuster):** flusht das Summary mit `RESULT=FAIL` auch bei
  Abbruch (Teil-Werte, soweit gesetzt). Mehr Komplexität, dafür Diagnose-Artefakt auch im
  Fehlerfall. (Teil A liefert das volle Log im Fehlerfall ohnehin.)

**Empfehlung:** Variante 1 — Teil A deckt den Fehlerfall-Diagnosebedarf bereits über das
hochgeladene Log; der `trap` zahlt sich erst aus, wenn das Summary auch im FAIL strukturiert
gebraucht wird.

## Build-Schritte (nach Freigabe)

1. `smoke-tpch-perf.sh`: `summary()` + Aufruf (Variante je Entscheidung oben).
2. `perf-acceptance.yml`: Run-Step `tee`, neuer `upload-artifact`-Step (`if: always()`).
3. Opt-in-Re-Verify: ein `make sample-db-tpch-perf`-Lauf (kein PR-Gate) — bestätigt, dass
   `tpch-perf-summary.env` korrekt geschrieben wird und der Bash-Edit fehlerfrei ist.
4. Doku-Sync: `docs/operations/performance-benchmarks.md` (Abschnitt „Reports" — bisher
   „Reports sind reine Laufzeit-Artefakte, kein Download" — auf das CI-Artefakt nachziehen)
   + Querverweis in der 4c-Slice (operativer Rest).

## Nicht-Ziele

- **Den Export selbst archivieren** (zu groß; Verlustfreiheit ist per kanonischem SHA-256
  im Lauf belegt, der Roh-Export ist kein Abnahme-Artefakt).
- **`CALIB_REFERENCE_MS` automatisch pinnen / committen** — bleibt bewusst manuell
  (Repo-Variable auf dem designierten Runner; Auto-Pin wäre ein separater Folge-Schritt).
- **Eingecheckter Benchmark-Zahlenstand** — widerspräche dem bewussten „keine publizierten
  Zahlen ohne normierte Umgebung" (`performance-benchmarks.md` Z. 14–19); das Artefakt ist
  pro-Lauf + retention-begrenzt, kein Repo-Stand.
- Die übrigen `perf`-Hotpaths (`make docker-perf`, JVM-`PerfReport`-JSONs unter `build/`) —
  separates Thema; dieses Ticket betrifft nur den TPC-H-4c-Nightly.
