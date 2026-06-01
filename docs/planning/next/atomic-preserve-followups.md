# Plan: Atomic-Preserve Folge-Slices (Findings + Dead-Code + Docs)

> Dokumenttyp: Backlog-Tracker für Folge-Arbeiten aus dem
> Atomic-Sequence-Preserve-Refactor (Phasen A–C).
>
> Status: Entwurf (2026-06-01)
>
> Referenzen:
> - `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
>   (Quelldokument; §3.2 Out-of-Scope, §5 Phasen D + E, §10 Carve-Outs).
> - `/code-review` 2026-06-01, Commit-Range `9d6dcba3..d72e572f`.
> - `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SequenceCapability.kt`
>   (Ziel der KDoc-Sync-Arbeit).

---

## 1. Ziel

Die Phasen A + B + C des Atomic-Preserve-Refactors sind gelandet
(`174c3891`, `1c09147d`, `8c2e0a07`, `11d04e57`,
`b4f548b0`+`39bcaa29`+`d72e572f`). Dabei sind drei Klassen von
Folge-Arbeiten entstanden, die nicht im laufenden Phase-C-Slice
adressiert wurden:

- **Code-Review-Findings 2026-06-01** (eines davon ein
  Production-Crash-Pfad).
- **Dead-Code des alten Probe-Pfads** (Port-Interface +
  3 Adapter-Implementierungen + Tests).
- **Phase D + Phase E** aus dem Plan-Doc, insbesondere die
  Docs-Synchronisation auf `SequenceCapability` inklusive eines neuen
  KDoc-Verweises auf den §3.2 Out-of-Scope-Block (cross-DB-Lock,
  App-Retry, globaler Schema-Lock).

Dieses Dokument bündelt diese Folge-Slices, ordnet sie nach
Release-Wirkung (vor / nach 0.9.7) und benennt pro Slice eine
DoD-Kurzform. Detail-DoD bleibt im Quelldokument.

---

## 2. Ausgangslage

Phase A + B + C sind grün auf `develop`; CI hat den Atomic-Preserve-
Pfad als neuen Default für alle drei Dialekte (PostgreSQL, MySQL,
SQLite). Carve-Outs sind im Quelldokument §3.2 (permanent, per
Produktentscheidung) sowie §10 (temporär, aus Code-Review)
festgehalten.

Releaseplanung 0.9.7 wartet auf Klärung, welche Slices noch vor dem
Release gemacht werden.

---

## 3. Scope-Skizze — Pre-Release-Slices (vor 0.9.7)

### 3.1 Finding #1 — Contiguity-Crash absichern *(high)*

`SchemaMigrateExecutionStage.kt:79` ruft `segmentForExecute(...)`
außerhalb des try-catch-Blocks. Eine `IllegalStateException` aus
`segmentForExecute` (z. B. wenn der Planner einen nicht-zusammen-
hängenden Atomic-Bereich liefert) propagiert unhandled bis zum CLI-
Top-Level und crasht das Kommando statt einen strukturierten
`SEQUENCE_PRESERVE_*`-Blocker zu melden.

**DoD F1**

- [ ] `segmentForExecute`-Aufruf in den try-Block der ExecutionStage
      verschoben.
- [ ] `IllegalStateException` wird in eine `SchemaMigrateOutcome`-
      Variante (Vorschlag: `SEQUENCE_PRESERVE_PLAN_INVALID`)
      gemappt, mit Operation-IDs der betroffenen Sequenzen im Detail.
- [ ] Unit-Test in `hexagon/application/src/test/.../SchemaMigrateExecutionStageTest.kt`
      mit synthetischer Plan-Liste, die Contiguity-Verletzung
      provoziert.
- [ ] Keine bestehenden Tests brechen.

### 3.2 Phase E — Docs + KDoc-Sync + neuer SequenceCapability-Verweis

Phase E aus dem Plan-Doc ist reine Dokumentationsarbeit; sie sollte
mit dem 0.9.7-Release synchron landen, damit CHANGELOG und User-Guide
das atomare Verhalten beschreiben.

**DoD E**

- [ ] CHANGELOG-Eintrag „atomic-preserve" für 0.9.7 (Breaking-Change-
      Markierung wenn Probe-Port-API in C.4 strukturell verändert
      wurde, sonst Feature-Eintrag).
- [ ] User-Guide-Eintrag: „`preserveCurrentValue` ist seit 0.9.7
      atomar unter Lock; Maintenance-Fenster nicht mehr nötig.
      PG-App-`nextval`-Race bleibt — siehe Quelldokument §6
      Risiko Nr. 8."
- [ ] KDoc-Update auf `SequencePreserveStage` mit Hinweis auf den
      atomaren Pfad und den `AtomicSequencePreserveBatch`-Aufbau.
- [ ] KDoc-Update auf
      `SequenceCapability.transactionalProtectedSequenceOperations`
      ersetzt den Phase-B-Wortlaut durch den korrekten C.4-Verweis.
- [ ] **Neu (2026-06-01):** KDoc-Block auf `SequenceCapability` mit
      explizitem Verweis auf §3.2 Out-of-Scope des Plan-Docs
      (cross-DB-Lock, App-Retry, globaler Schema-Lock), damit Code-
      Leser nicht annehmen, ein fehlendes Capability-Flag bedeute
      „TODO" statt „bewusst nicht in Scope".
- [ ] KDoc auf `SequenceCurrentValueProbe`: Hinweis auf den atomaren
      Pfad **oder** Markierung als „obsolet, siehe Dead-Code-Cleanup-
      Slice" — abhängig davon, ob der Cleanup-Slice (§4.2) noch vor
      0.9.7 oder erst danach landet.

---

## 4. Scope-Skizze — Post-Release-Slices (0.9.8 oder Patch)

### 4.1 Phase D — Cross-Plan-Deadlock + AllInPlan-Flag

Vollständige DoD im Quelldokument §5 Phase D. Kurzform:

- [ ] Cross-Plan-Deadlock-Test in `:test:integration-concurrency`
      pro Dialekt: zwei parallele `schema migrate`-Läufe über
      überlappende Sequenzen committen ohne Deadlock.
- [ ] `SequenceCapabilityDefaults.supportsAtomicPreserveAllInPlan =
      true` pro Dialekt nach grünem Stresstest.
- [ ] Stage emittiert
      `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`, wenn ein Plan ≥ 2
      Preserve-Kandidaten enthält und der Dialekt das Flag auf
      `false` hat.

### 4.2 Dead-Code-Cleanup Probe-Adapter

Aus C.1: `SequenceCurrentValueProbe`-Port + die drei dialekt-
spezifischen Probe-Adapter-Implementierungen (+ ihre Tests) werden
nach C.1 nicht mehr referenziert. `SequenceCurrentValueProbeResult`
selbst bleibt **erhalten**, weil die `Read`-Variante als
Restore-Vertrag im Atomic-Executor weiterlebt.

**DoD F2**

- [ ] `SequenceCurrentValueProbe`-Interface gelöscht.
- [ ] `Postgres*ProbeAdapter`, `Mysql*ProbeAdapter`,
      `Sqlite*ProbeAdapter` (Driver-Adapter-Klassen) gelöscht.
- [ ] Adapter-Tests gelöscht.
- [ ] `SequenceCurrentValueProbeResult` bleibt; KDoc aktualisiert
      auf den neuen Use-Case.
- [ ] `make docker-verify` grün; Coverage-Schwelle 90 % pro Modul
      bleibt erfüllt.

### 4.3 Findings #2–6 *(mittel/niedrig)*

| # | Severity | Datei / Stelle | Kurzbeschreibung |
|---|---|---|---|
| 2 | mittel | `AlterSequenceCurrentValue`-Render (PG-Codec) | Sentinel `0L` rendert wörtlich `setval('seq', 0, true)` in plan-only/report-Output. Lösung: Render-Filter für Sentinel-Werte oder explizite Markierung im Report. |
| 3 | mittel | `SegmentAwareMigrationExecutor.kt:162` | `statementsAttempted` zählt interne Follow-ups mit (Diagnostic-Überzählung). Lösung: nur protected statements zählen. |
| 4 | niedrig | `SchemaMigrateRequest`-Validierung | Stummer Fallback bei unbekanntem `--mysql/sqlite-named-sequences`-Wert; asymmetrisch zur `generate`-Validierung. Lösung: gleiches Validierungsverhalten wie bei `generate`. |
| 5 | mittel | `Mysql/SqliteSequencePreserveRaceTest` | Assertion `finalValue >= initial + writerAdvances` beweist nicht eindeutig, dass der Lock die Race geschlossen hat. Lösung: zusätzlich exakte Trace-Reihenfolge oder Lock-Wait-Counter prüfen. |
| 6 | niedrig | LockTimeout-Decorator | `lockTimeoutMillis` hardcodet; Test-only Issue. Lösung: Parameter aus Test-Setup durchreichen. |

**DoD F3**

- [ ] Findings 2 + 3 + 5 gefixt (mittel-Severity).
- [ ] Findings 4 + 6 gefixt oder explizit als „won't fix" mit
      Begründung dokumentiert.
- [ ] Keine Regression in den Atomic-Preserve-ITs.

---

## 5. Vorbedingungen

- Plan-Doc `sequence-preserve-atomic-lock-plan.md` ist Source of
  Truth — alle DoD-Details werden dort geführt; dieses Dokument
  verweist nur.
- Code-Review-Findings sind im Plan-Doc §10 als
  „Bekannte Carve-Outs" mit Commit-Range fixiert.
- Pre-Release-Slices §3.1 + §3.2 müssen vor dem 0.9.7-Tag landen,
  wenn das Release das atomare Verhalten als Headline bewirbt.

---

## 6. Offene Fragen

- Soll Finding #1 als hotfix-Patch direkt nach 0.9.7 möglich
  bleiben, falls er nicht mehr pre-Release reinpasst?
- Wird der Dead-Code-Cleanup §4.2 vor oder nach dem Release
  gemacht? (Wirkt sich auf die KDoc-Formulierung von
  `SequenceCurrentValueProbe` in §3.2 aus.)
- Phase D §4.1: reicht der heutige `:test:integration-concurrency`-
  Aufbau für den Cross-Process-Stresstest, oder braucht es einen
  zweiten echten JVM-Prozess via ProcessBuilder?

---

## 7. Lebenszyklus

Bei erstem Implementierungs-Commit eines Slice wandert dieser Slice
(z. B. §3.1) aus diesem Dokument in einen eigenen Eintrag unter
`../in-progress/` oder direkt ins Quelldokument als neuer Sub-
Abschnitt. Nach Abschluss des kompletten Backlogs landet dieses
Dokument in `../done/`.
