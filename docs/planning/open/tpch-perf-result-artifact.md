# Perf-Acceptance-Ergebnisse als CI-Artefakt sichern (TPC-H 4c Nightly)

> **Status:** Plan-Entwurf, Review-Runde 1 + 2 eingearbeitet (2026-06-24).
> Runde 1 — `shell: bash`/`pipefail` (High), Step-`timeout-minutes` < Job-Limit (Med),
> Trap-Komposition mit dem vorhandenen EXIT-Handler Z. 182 (Med),
> `CALIB_MS`/`CALIB_REFERENCE_CANDIDATE_MS` statt `CALIB_REFERENCE_MS` (Low),
> `upload-artifact@v6` (Low).
> Runde 2 — Staging-Dir statt `/tmp`+Workspace-Mischpfaden (Med), Step-Reihenfolge
> Run→Collect→Cleanup→Upload (Med), Ziel/`RESULT` ehrlich an die Variante gekoppelt (Med),
> `CALIB_STATUS=BOOTSTRAP|IN_BAND|OFF_SPEC` gegen `HOST_OK=0`-Mehrdeutigkeit (Low),
> Runner-Version-Voraussetzung für `@v6` notiert (Note).
> Runde 3 — Job-`timeout-minutes` 45→60 (geteiltes Budget mit Build, sonst Step-Timeout
> nicht bindend) (High), Cleanup-Step eigenes `timeout-minutes: 5` gegen hängendes
> `down -v` (Med), Artefaktname `+run_attempt` gegen Re-run-Kollision (Med), Pre-Run-Clean
> `/tmp/tpch-*.log` gegen Staleness auf reused Runner (Med).
> **Typ:** operativer Follow-up zur [4c-Slice](../done/tpc-4c-volume-acceptance-slice.md)
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
vollen Lauf-Log (faithful, inkl. Median + Verdikte) — **bei jedem Lauf, auch im
Fehlerfall** (kommt aus dem `tee` + `if: always()`-Upload) — und (b) einem
**maschinenlesbaren Summary**, sodass das Runner-Pinnen ohne Log-Grep funktioniert.

**Wichtig (Konsistenz mit der offenen Variante):** Ziel (b) ist an die Variantenwahl
gekoppelt. Variante 1 schreibt das Summary nur bei Erfolg → es trägt dann **immer**
`RESULT=SUCCESS` (das Feld ist konstant, der Fehlerfall wird allein durch das Log (a)
abgedeckt). Erst Variante 2 (`trap`-Flush) macht `RESULT=FAIL` im Summary erreichbar. Der
Zieltext „maschinenlesbares Summary nach jedem Lauf" gilt also **streng nur für Variante 2**;
unter der empfohlenen Variante 1 lautet das Ziel „Summary nach jedem **erfolgreichen** Lauf".

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
- **Runner-Voraussetzung für `upload-artifact@v6`:** v6 setzt einen Actions-Runner
  **≥ 2.327.1** voraus ([Release-Notes](https://github.com/actions/upload-artifact/releases/tag/v6.0.0)).
  Auf GitHub-gehosteten Runnern (aktuell `ubuntu-latest`, Z. 33) ist das gegeben; **wird der
  designierte Nightly-Runner self-hosted**, ist die Runner-Version vorab zu prüfen (sonst
  `@v6` → `@v4` zurücknehmen).

## Design (zwei komplementäre Teile)

Die zwei Teile sind **nicht alternativ**: Teil B braucht den Upload aus Teil A ohnehin.

### Teil A — Workflow: Log teen + hochladen (`perf-acceptance.yml`)

**Job-Timeout zuerst korrigieren (Finding-Runde 3, High).** Das 45-min-Job-Limit (Z. 35)
ist **geteilt** mit Checkout + Compose-Check + `make docker-build` (Z. 37–43; der volle
Runtime-Image-Build kann mehrere Minuten kosten). Ein 35-min-Step-Timeout im selben Budget
ist daher **nicht** zuverlässig bindend: brauchen die Vorstufen z. B. 12 min, schlägt das
**Job**-Limit (45) vor dem **Step**-Limit (35+12 = 47 > 45) zu → Cancel → Collect/Cleanup/
Upload laufen nicht. Damit der **Step**-Timeout (nicht der Job-Timeout) die bindende
Schranke ist, muss das **Job-Limit angehoben** werden, sodass `build + run-step + Post-Steps`
mit Reserve hineinpassen:
```yaml
jobs:
  tpch-perf:
    timeout-minutes: 60     # war 45; deckt build(~15) + run-step(35) + collect/cleanup/upload(~10) mit Reserve
```
(Konkrete Zahlen sind **Start-Budgets**, im Re-Verify-Lauf zu validieren.)

1. **Pre-Run-Clean** (`/tmp`-Staleness, Finding-Runde 3, Med) — das Skript schreibt **fixe**
   `/tmp/tpch-*.log`-Namen; auf einem **reused self-hosted Runner** (der designierte
   Nightly-Runner) könnte ein früh abgebrochener Lauf alte Logs hinterlassen, die der
   Collect-Step fälschlich einsammelt. Daher vor dem Lauf gezielt bereinigen (auf
   GitHub-gehosteten Runnern ohnehin no-op, da ephemer):
   ```yaml
   - name: Clean stale perf logs
     shell: bash
     run: rm -f /tmp/tpch-*.log
   ```
   *(Robustere Alternative für später: Teil B lässt das Skript in ein run-scoptes Temp-Dir
   statt fixe `/tmp`-Namen schreiben — größerer Eingriff, hier bewusst nicht gewählt.)*
2. **Run-Step** stdout in Datei spiegeln — **mit explizitem `shell: bash`** (für `pipefail`,
   s. Randbedingungen) und **eigenem `timeout-minutes`** (jetzt zuverlässig bindend, da das
   Job-Limit angehoben ist):
   ```yaml
   - name: Run TPC-H volume acceptance (calibration-guarded)
     shell: bash            # explizit -> bash -eo pipefail (Default waere bash -e, kein pipefail)
     timeout-minutes: 35    # bindende Schranke; Job-Limit (60) laesst Post-Steps Slack
     env:
       CALIB_REFERENCE_MS: ${{ vars.CALIB_REFERENCE_MS }}
     run: make sample-db-tpch-perf PERF_GATE=true 2>&1 | tee perf-acceptance-run.log
   ```
3. **Collect/Staging-Step** (`if: always()`) — alle Artefakt-Quellen in **ein**
   Workspace-Verzeichnis kopieren. Grund: `upload-artifact` bildet aus mehreren `path`-
   Einträgen den **gemeinsamen Wurzel-Pfad**; mischt man `/tmp/tpch-*.log` (Wurzel `/`) mit
   Workspace-Dateien, bekommt das Artefakt eine hässliche Struktur (`tmp/…` **und**
   `home/runner/work/…`). Ein Staging-Dir erzwingt eine flache, saubere Wurzel:
   ```yaml
   - name: Collect perf artifacts
     if: always()
     shell: bash
     run: |
       mkdir -p perf-acceptance-artifact
       cp -f perf-acceptance-run.log perf-acceptance-artifact/ 2>/dev/null || true
       cp -f /tmp/tpch-*.log perf-acceptance-artifact/ 2>/dev/null || true
       cp -f examples/sample-db/out/tpch-perf-summary.env perf-acceptance-artifact/ 2>/dev/null || true
   ```
4. **Cleanup VOR Upload, aber selbst zeit-beschränkt** (Finding-Runde 2 Reihenfolge +
   Finding-Runde 3 Med): Staging läuft **vor** dem Cleanup (so kann ein zäher Upload den
   Container-Cleanup nicht verdrängen). Damit umgekehrt ein **hängendes** `docker compose
   down -v` nicht den Upload aushungert, bekommt der Cleanup-Step ein **eigenes kleines
   `timeout-minutes`**:
   ```yaml
   - name: Cleanup (best-effort, bounded)
     if: always()
     timeout-minutes: 5     # haengendes down -v darf den Upload nicht aushungern
     run: make sample-db-purge || true
   ```
   Zielreihenfolge der Steps: **Pre-Run-Clean → Run → Collect → Cleanup (bounded) → Upload.**
5. **Upload-Step** (`if: always()`, `@v6` analog `build.yml`) — lädt **nur** das
   Staging-Verzeichnis; Artefaktname mit `run_attempt`, sonst **kollidiert ein Re-run**
   (`github.run_id` bleibt gleich, `github.run_attempt` zählt hoch; `upload-artifact`
   verlangt eindeutige Namen ohne `overwrite`) — Finding-Runde 3, Med:
   ```yaml
   - name: Upload perf results
     if: always()
     uses: actions/upload-artifact@v6
     with:
       name: tpch-perf-${{ github.run_id }}-${{ github.run_attempt }}
       path: perf-acceptance-artifact/
       retention-days: 30
       if-no-files-found: warn
   ```
   **Bewusst NICHT** dabei: `examples/sample-db/out/tpch-perf-export/**` (≈1,7 Mio Zeilen
   TPC-H-Export, hunderte MB).

### Teil B — Skript: maschinenlesbares Summary (`smoke-tpch-perf.sh`)

Eine `summary()`-Funktion schreibt die bereits vorhandenen Variablen nach
`$OUT_DIR/tpch-perf-summary.env` (gitignored), Kernzeile zum Pinnen:

```
CALIB_MS=<gemessener Median dieses Laufs>
CALIB_REFERENCE_CANDIDATE_MS=<gemessener Median, als Pin-Kandidat gekennzeichnet>
CALIB_STATUS=<BOOTSTRAP|IN_BAND|OFF_SPEC>
HOST_OK=<0|1>
EXPORT_RPS=<exp_rps>
IMPORT_RPS=<imp_rps>
TOTAL_ROWS=<total_rows>
PERF_GATE=<true|false>
RESULT=<SUCCESS|FAIL>   # unter Variante 1 konstant SUCCESS (s. „Offene Entscheidung")
```

**Bewusst kein `CALIB_REFERENCE_MS` im Summary:** das Skript trennt `CALIB_MS` (gemessen,
Z. 142) sauber vom gepinnten Input `CALIB_REFERENCE_MS` (Z. 43). Ein `source` des Summary
darf den gepinnten Referenzwert **nicht** überschreiben — daher der gemessene Wert als
`CALIB_MS` plus explizit benannter `CALIB_REFERENCE_CANDIDATE_MS` für das manuelle Pinnen.

**`CALIB_STATUS` statt nur `HOST_OK` (Finding 4):** `HOST_OK=0` ist mehrdeutig — es trifft
sowohl den **Bootstrap** (kein `CALIB_REFERENCE_MS` gepinnt, Z. 144–145) als auch den
**Off-Spec-Runner** (Drift > Toleranz, Z. 152–153) zu; nur `HOST_OK=1` = **in-band**
(Z. 150–151). Für maschinenlesbare Nutzung trägt das Summary zusätzlich `CALIB_STATUS` mit
den drei trennscharfen Werten, abgeleitet aus genau diesen Kalibrier-Zweigen. `HOST_OK`
bleibt für Abwärtskompatibilität erhalten.

`.env`-Format, weil es per `source` / `>> $GITHUB_ENV` direkt weiterverwendbar ist (späterer
Auto-Pin-Step optional). Schreiben **nach** allen Zeitmessungen → keine Mess-Perturbation.

## Offene Entscheidung (Review-Input erbeten)

**Summary nur bei Erfolg vs. auch bei Abbruch.**
- **Variante 1 (End-Writer, simpel):** `summary()` direkt vor/nach `SUCCESS` (Z. 244).
  Bei einem harten Mid-Run-`fail()` entsteht **kein** Summary — fürs Hauptziel
  („erfolgreichen Bootstrap-Median ablesen") ausreichend. **Konsequenz fürs Schema:** das
  Summary existiert dann nur im Erfolgsfall → `RESULT` ist **konstant `SUCCESS`** (Feld
  bleibt der Symmetrie/Variante-2-Kompatibilität halber, ist aber unter Variante 1 nie
  `FAIL`); der Fehlerfall wird allein durch das hochgeladene Log abgedeckt.
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
2. `perf-acceptance.yml`: **Job-`timeout-minutes` 45 → 60** (geteiltes Budget mit Build),
   Run-Step (`shell: bash` + `tee` + `timeout-minutes: 35`), Steps in der Reihenfolge
   **Pre-Run-Clean (`rm -f /tmp/tpch-*.log`) → Run → Collect (Staging-Dir) → Cleanup
   (bounded `timeout-minutes: 5`) → Upload** (`@v6`, nur das Staging-Verzeichnis,
   Name `…-${{ github.run_attempt }}`; Collect/Cleanup/Upload je `if: always()`).
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
