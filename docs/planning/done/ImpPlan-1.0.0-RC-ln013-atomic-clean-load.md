# ImpPlan 1.0.0-RC — LN-013: Atomarer Rollback auf Checkpoint-Ebene (`--atomic`, Clean-Load-Kompensation)

> Status: **DONE / graduiert** (2026-07-12; [ADR 0031](../../adr/0031-atomic-clean-load-rollback.md)).
> Phase A (Flag + Preflight + `truncateTables`-Port), B (Kompensation import+transfer),
> C (SQLite-Live-Smoke: „alle oder keine" + Kontrast + Preflight), D (Spec/Doku/ADR)
> abgeschlossen; roadmap-Eintrag → ✅. Schließt
> [`LN-013`](../../../spec/lastenheft-d-migrate.md#ln-013) („Atomare
> Rollback-Fähigkeit auf Checkpoint-Ebene bei Fehlern"): heute committet der
> Datenpfad chunk-weise und lässt bei Fehler einen Teil-Import stehen. Kein
> dedizierter Spec-Vertrag — nur die Lastenheft-Akzeptanzkriterien
> (`spec/lastenheft-d-migrate.md` 8.5: „Keine Teil-Importe" / „Alle Tabellen
> oder keine bei Multi-Table-Import").

## Kontext / Ist-Stand (verifiziert)

- **Chunk = Transaktionsgrenze**: `AbstractTableImportSession.commitChunk()`
  (`conn.commit()`) committet jeden Chunk einzeln; keine tabellen-/laufweite Tx.
  Fehler mittendrin → nur der offene Chunk rollt zurück, **alle vorherigen
  committeten Chunks/Tabellen bleiben** (Teil-Import). Gilt für Import
  (`TableImportLoopSupport`/`TableImporter`) **und** Transfer (`TransferExecutor`).
- **`--truncate` ist bewusst NICHT-atomar** (läuft vor der Import-Tx, dialekt-SQL
  in `*DataWriter.openTable`; `ImportOptions.kt:34-40`).
- **Kein Undo committeter Daten**, **keine „alle Tabellen oder keine"-Semantik**;
  Transfer hat gar keine Checkpoint-/Resume-Infra.
- Checkpoint-Grenze pro Tabelle existiert (`CheckpointTableSlice.chunksProcessed`,
  atomar via temp+`ATOMIC_MOVE` in `FileCheckpointStore.save`).

## Scope (user-abgestimmt 2026-07-12)

Modell **Kompensierender Clean-Load-Rollback** (von vier Optionen gewählt): das
einzige, das beide Lastenheft-Kriterien erfüllt, **ohne** das >10-TB-Streaming zu
brechen — die Kompensation (Truncate bei Fehler) ist eine **O(1)-Metadaten-
Operation**, unabhängig vom Datenvolumen.

- **Neues `--atomic`-Flag** auf `data import` UND `data transfer`.
- **Semantik:** „alle Tabellen oder keine" für einen **sauberen Load**. Bei
  Fehler in irgendeiner Tabelle werden **alle Tabellen der Operation** auf den
  Vor-Import-Zustand (leer) zurückgesetzt (Truncate-Kompensation).
- **Nutzt die vorhandene Truncate-Maschinerie** (kein Savepoint-Umbau, keine
  Swap-Semantik).
- **Beide Pfade über die Finalize-Fehler-Naht** — kompensationsbasiert, also
  **unabhängig von Checkpoints** (deshalb auch im checkpoint-freien Transfer baubar).

## Architektur-Entscheidungen

**D1 — `--atomic` erfordert explizit `--truncate` (Fail-Fast-Preflight, user-
entschieden 2026-07-12).** Kein stilles Impliziern, kein Leer-Check: `--atomic`
**ohne** `--truncate` → Fail-Fast mit klarer Meldung („`--atomic` requires
`--truncate`") **vor** dem ersten Write. Begründung: `--atomic` truncatet bei Fehler
ohnehin (Kompensation) — der Nutzer hat der Ziel-Zerstörung also schon zugestimmt;
`--truncate` vorzuschreiben macht die destruktive Natur **am Call-Site sichtbar**
statt sie zu verbergen. Vermeidet zudem den TOCTOU-Leer-Check (kein Row-Count-Query,
keine „leer unter welcher Isolation?"-Frage — bei falschem Check könnte die
Kompensation fremde Daten truncaten → Datenverlust). Der Vertrag ist ein **Superset**:
„leeres Ziel auch ohne `--truncate`" ließe sich später additiv dazunehmen, ohne den
`--truncate`-Vertrag zu brechen. Append-in-nicht-leeres-Ziel bleibt Nicht-Scope
(Staging/Swap).

**D2 — Kompensation = Truncate des vollständigen Operations-Tabellensatzes.** Bei
Fehler truncatet die Kompensation **alle** Tabellen der Operation (nicht nur die
gescheiterte) in **umgekehrter FK-/Topo-Reihenfolge** (Dependents zuerst; Postgres
`CASCADE`, MySQL/SQLite reverse-order-`DELETE`). Der Tabellensatz hängt an der
Finalize-Naht (Runner kennt die topo-sortierte Liste).

**D3 — Idempotent + resume-sicher (der Kern-DoD).** Die Kompensations-Truncate ist
selbst nicht transaktional (dieselbe Eigenschaft wie `--truncate`). Robustheit,
stark vereinfacht durch D1 (`--truncate` immer gesetzt):
- **`--atomic` ist inkompatibel mit `--resume`** (Exit 2) — atomar heißt
  all-or-nothing, es gibt keinen Teilzustand zum Wiederaufnehmen.
- Da `--truncate` per D1 **immer** gesetzt ist, truncatet **jeder** `--atomic`-Lauf
  am Start ohnehin alle Tabellen → jeder Lauf startet garantiert sauber. Eine
  **abgebrochene Kompensation** (Prozess stirbt mitten im Truncate) wird beim Re-Run
  trivial re-cleant (Start-Truncate). Kein Leer-Check, keine TOCTOU-Frage; der
  Re-Run ist immer wohldefiniert.

**D4 — Standalone-Truncate-Port.** Neue Methode `DataWriter.truncateTables(pool,
tables)` — extrahiert die vorhandene dialekt-Truncate-SQL aus `openTable` in eine
aufrufbare Primitive (Postgres `TRUNCATE … RESTART IDENTITY CASCADE`, MySQL/SQLite
`DELETE` reverse-order). Nutzbar für Start-Truncate UND Kompensation; hält die
Kompensation dialekt-korrekt.

**D5 — Exit-Codes.** `--atomic` ohne `--truncate` → Exit **2** (Usage), `--atomic`
+ `--resume` → Exit **2** (Usage) — beides reine Flag-Combo-Preflight-Fehler vor dem
ersten Write. Nach erfolgreicher Kompensation bleibt der ursprüngliche Fehler-Exit
(Import/Transfer **5**), aber mit klarer Meldung „atomic rollback: alle N Tabellen
auf leeren Zustand zurückgesetzt" statt „data was committed".

## Phasen

- **Phase A — Preflight + Truncate-Primitive.** `--atomic`-Flag (Command→Wiring→
  Request, import + transfer), `DataWriter.truncateTables`-Port + 3 Dialekt-Impls
  (SQL aus `openTable` extrahiert/geteilt), Preflight-Validierung (Clean-Start,
  `--resume`-Konflikt). Unit-Tests (Fake-Writer/Fake-Pool: Preflight-Fälle,
  truncateTables-Reihenfolge).
- **Phase B — Kompensations-Verdrahtung.** `AtomicCompensator` in `application`:
  bei Import-Fehler (via `ImportCompletionSupport`/`DataImportRunner`) und
  Transfer-Fehler (`DataTransferRunner` Exit-5-catch) truncatet er den vollen
  Tabellensatz; angepasste Fehlermeldung. Unit-Tests (Fehler injizieren →
  truncateTables über alle Tabellen aufgerufen; Cancel bleibt 130, kein
  Kompensations-Trigger bei Erfolg).
- **Phase C — Live-Härtung (sample-db).** SQLite `data import`/`data transfer
  --atomic`: Happy-Path → vollständig; **Fehler injiziert (z. B. Constraint-
  Verletzung in Tabelle 2 von 3)** → Ziel auf leer zurück (alle 3 Tabellen leer),
  Exit 5. Preflight-Fälle (nicht-leeres Ziel ohne `--truncate` → Exit 3;
  `--atomic --resume` → Exit 2).
- **Phase D — Spec/Doku/ADR + Gates.** cli-spec `--atomic`-Flag + Semantik (import +
  transfer), `ImportOptions`-non-atomic-Note ergänzen (`--atomic` als atomare
  Alternative), roadmap-Eintrag → ✅, **ADR** für den Clean-Load-Kompensations-
  Vertrag (D1–D3, permanenter Design-Vertrag, benennt den Nicht-leer-Carve-Out),
  `make docs-check` + Docker-Build grün.

## Nicht-Scope

- **Append-in-nicht-leeres-Ziel** (Staging + atomarer Swap, Option 4) — späterer
  Slice; `--atomic` verbaut ihn nicht (lässt sich darunterlegen). Überlappt mit
  `spec/shadow-migration.md`.
- **Savepoint-Fenster pro Checkpoint** (Option 3) — tiefer State-Machine-Umbau,
  skaliert Undo-Log mit Datenmenge.
- **Per-Tabelle-Einzel-Tx** (Option 2) — kollidiert mit >10-TB-Streaming.
- Multi-Table-Tx / laufweite DB-Transaktion (per Design unmöglich beim Streaming).

## Referenzen

- [`LN-013`](../../../spec/lastenheft-d-migrate.md#ln-013),
  [`spec/lastenheft-d-migrate.md`](../../../spec/lastenheft-d-migrate.md) 8.5
  (Transaktionale Konsistenz: „Keine Teil-Importe" / „Alle Tabellen oder keine").
- [`LN-012`](../../../spec/lastenheft-d-migrate.md#ln-012) (Checkpoint/Resume, ✅) —
  Komplement (Vorwärts-Wiederaufnahme); dieser Slice ist die Rückwärts-Atomarität.
- [`spec/cli-spec.md`](../../../spec/cli-spec.md) — `data import`/`data transfer`
  (Zielbild, `--atomic` ergänzen).
- [`spec/job-contract.md`](../../../spec/job-contract.md) 8.1 (Exit-Codes).
- ADR für den Kompensations-Vertrag → **ADR 0031** (Phase D).
