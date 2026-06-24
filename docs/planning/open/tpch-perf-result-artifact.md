# Perf-Acceptance-Ergebnisse als CI-Artefakt sichern (TPC-H 4c Nightly)

> **Status:** Plan-Entwurf, Review-Runde 1 eingearbeitet (2026-06-24) — `shell: bash`/
> `pipefail` (High), Step-`timeout-minutes` < Job-Limit (Med), Trap-Komposition mit dem
> vorhandenen EXIT-Handler Z. 182 (Med), `CALIB_MS`/`CALIB_REFERENCE_CANDIDATE_MS` statt
> `CALIB_REFERENCE_MS` (Low), `upload-artifact@v6` (Low).
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
- **`pipefail` ist NICHT der Actions-Default.** Ein `run`-Step ohne `shell:` läuft auf
  Linux als `bash -e {0}` (kein `pipefail`); dann maskiert ein erfolgreiches `tee` einen
  fehlgeschlagenen `make` ([GitHub-Doku](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idstepsshell)).
  Erst **explizit `shell: bash`** ergibt `bash --noprofile --norc -eo pipefail {0}` und
  erhält damit den `make`-Exit-Code durchs Pipe. (Der Job-Status ist durch
  `continue-on-error: true` (Z. 34) ohnehin nicht bindend, aber der Step-Status soll
  ehrlich „rot" zeigen, wenn `make` scheitert.)

## Design (zwei komplementäre Teile)

Die zwei Teile sind **nicht alternativ**: Teil B braucht den Upload aus Teil A ohnehin.

### Teil A — Workflow: Log teen + hochladen (`perf-acceptance.yml`)

1. Run-Step (Z. 45–51) stdout in Datei spiegeln — **mit explizitem `shell: bash`** (für
   `pipefail`, s. Randbedingungen) und **eigenem `timeout-minutes` unter dem Job-Limit**
   (45 min, Z. 35), damit bei Überlauf Upload + Cleanup noch Slack haben (ein Job-Timeout
   canceled den Job, dann sind `if: always()`-Steps nicht zuverlässig — der Step-Timeout
   beendet nur den Run-Step und lässt die Folge-Steps laufen):
   ```yaml
   - name: Run TPC-H volume acceptance (calibration-guarded)
     shell: bash            # explizit -> bash -eo pipefail (Default waere bash -e, kein pipefail)
     timeout-minutes: 35    # < Job 45 min; laesst Upload/Cleanup Slack
     env:
       CALIB_REFERENCE_MS: ${{ vars.CALIB_REFERENCE_MS }}
     run: make sample-db-tpch-perf PERF_GATE=true 2>&1 | tee perf-acceptance-run.log
   ```
2. Neuer Upload-Step **mit `if: always()`** (läuft auch, wenn der diagnostische Run-Step
   „fehlschlägt"; Version `@v6` analog zu `build.yml`):
   ```yaml
   - name: Upload perf results
     if: always()
     uses: actions/upload-artifact@v6
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
CALIB_MS=<gemessener Median dieses Laufs>
CALIB_REFERENCE_CANDIDATE_MS=<gemessener Median, als Pin-Kandidat gekennzeichnet>
HOST_OK=<0|1>
EXPORT_RPS=<exp_rps>
IMPORT_RPS=<imp_rps>
TOTAL_ROWS=<total_rows>
PERF_GATE=<true|false>
RESULT=<SUCCESS|FAIL>
```

**Bewusst kein `CALIB_REFERENCE_MS` im Summary:** das Skript trennt `CALIB_MS` (gemessen,
Z. 142) sauber vom gepinnten Input `CALIB_REFERENCE_MS` (Z. 43). Ein `source` des Summary
darf den gepinnten Referenzwert **nicht** überschreiben — daher der gemessene Wert als
`CALIB_MS` plus explizit benannter `CALIB_REFERENCE_CANDIDATE_MS` für das manuelle Pinnen.

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
  **Achtung Trap-Komposition:** das Skript setzt bereits `trap '… docker rm -f …' EXIT`
  (Z. 182, Container-Cleanup im Resume-Abschnitt). Ein zweiter `trap … EXIT` würde diesen
  **überschreiben** (Bash hält pro Signal nur einen Handler). Variante 2 müsste daher den
  bestehenden Cleanup **mit** dem Summary-Flush in **einem** EXIT-Handler vereinen (gemeinsame
  `on_exit()`-Funktion) oder die Registrierung an die Stelle in Z. 182 ziehen — nicht naiv
  ein zweites `trap` setzen.

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
