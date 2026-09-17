# CI-Läufe, die Fehlschläge verdecken: `continue-on-error` und Gradle ohne `--continue`

> **Status:** Befund / Vorabklärung (2026-09-17), zwei Mechanismen und ein
> Beobachtungspunkt.
> **Trigger:** Compare-Slice
> [`compare-projektion-und-normalisierung.md`](../done/compare-projektion-und-normalisierung.md)
> (vierter Bauabschnitt: CI an `e3116c34c`; sechster Bauabschnitt:
> Eigner-Entscheidung zum Workflow der Compare-Matrix; „Offen"). Der Eigner hat
> dort entschieden, den **neuen** Workflow rot sichtbar zu machen und die
> Geschwister nicht anzufassen, sondern hier festzuhalten.
> **Aktivierungsbedingung:** Eigner-Entscheidung je Workflow (Teil 1) bzw. zur
> Integrations-Pipeline (Teil 2); spätestens vor dem nächsten Release prüfen,
> welche Läufe zuletzt tatsächlich grün waren.

## 1 — Job-weites `continue-on-error`: ein roter Lauf ist ein grüner Haken

Diese Workflows tragen `continue-on-error: true` auf Job-Ebene. Scheitert der
Smoke, bleibt der Lauf grün; der Fehlschlag steht nur im Job-Log.

| Gruppe | Workflows unter `.github/workflows/` |
| --- | --- |
| Sample-DB-Cross-Smokes (sechs) | `sample-db-cross-smoke.yml`, `sample-db-cross-smoke-ms2pg.yml`, `sample-db-cross-smoke-ora2pg.yml`, `sample-db-cross-smoke-pg2ms.yml`, `sample-db-cross-smoke-pg2my.yml`, `sample-db-cross-smoke-pg2ora.yml` |
| weitere (sieben) | `sample-db-smoke.yml`, `sample-db-sqlite-smoke.yml`, `sample-db-spatial-smoke.yml`, `sample-db-scale.yml`, `bi-demo-smoke.yml`, `mcp-e2e-smoke.yml`, `perf-acceptance.yml` |

Die Begründung im Kopf der Dateien ist dieselbe: transiente Netz- oder
Image-Pull-Fehler sollen den Hauptbuild nicht rot machen. Der Preis: ob die
Smokes zuletzt durchliefen, ist am Status nicht abzulesen. Ein Beispiel dafür,
dass das zählt, steht in
[`mssql-testimage-2025-cu1-startet-nicht.md`](mssql-testimage-2025-cu1-startet-nicht.md)
(der MSSQL-Leg der Sample-DB ist auf einem Host rot, unbemerkt).

**Vorlage:** `mcp-e2e-compare-matrix.yml` ist seit dem sechsten Bauabschnitt
des Compare-Slices rot sichtbar — kein job-weites `continue-on-error`, kein
Pflicht-Check, Upload der Laufartefakte mit `if: always()`. Das ist kein
PR-Gate, macht einen Fehlschlag aber sichtbar.

**Zu entscheiden je Workflow:** rot sichtbar nach dieser Vorlage, oder
best-effort bleiben (dann mit einem anderen Weg, den Ausgang abzulesen, etwa
einem Zusammenfassungs-Schritt, der den Fehlschlag benennt).

## 2 — Der Integrations-Workflow läuft ohne `--continue`

`integration.yml` fährt alle Integrationsmodule in einem Gradle-Lauf ohne
`--continue`. Scheitert ein Modul, bricht Gradle ab, und die übrigen Module
laufen nicht — ein Ausreißer verdeckt deterministische Fehler anderswo.

**Gemessen an `e3116c34c`:** `MssqlFullTextEnvironmentIntegrationTest`
scheiterte (der Container `d-migrate-mssql-fts:local` startete nicht);
`:test:integration-mysql:test` lief deshalb nicht. Dessen neuer Fall wäre dort
deterministisch rot gewesen und fiel erst im nächsten Lauf auf (im Slice
behoben in `542008cbd`).

**Zu klären:** `--continue` in `INTEGRATION_TASKS` des Workflows (und ob
`make integration` es ebenfalls setzen soll); die Wechselwirkung mit dem
Kover-Verify und `--no-build-cache`, die der Workflow begründet. Der Kandidat
für den Ausreißer selbst steht in
[`mssql-testimage-2025-cu1-startet-nicht.md`](mssql-testimage-2025-cu1-startet-nicht.md)
(FTS-Image).

## 3 — Beobachtung: der erste Lauf der Compare-Matrix in CI

`mcp-e2e-compare-matrix.yml` ist zur Graduation des Compare-Slices noch nie in
CI gelaufen (Auslöser: `workflow_dispatch`, wöchentlich, Push auf `main` in
den beobachteten Pfaden). Die Zellen sind auf dem Messhost gepinnt
(PostgreSQL 18.6, MySQL 9.7.2, SQL Server 2025, SQLite 3.45 vom Host).

**Zu beobachten beim ersten Lauf:** ob die Matrix auf dem Runner dieselben
Zellen misst (vor allem die SQLite-Zeile, deren Client dort ein anderer sein
kann) und ob ein roter Lauf ein Befund oder eine Umgebungsfrage ist. Ein
Umgebungsunterschied gehört in die Erwartungsdatei oder den Workflow, nicht in
ein stilles Neu-Pinnen.
