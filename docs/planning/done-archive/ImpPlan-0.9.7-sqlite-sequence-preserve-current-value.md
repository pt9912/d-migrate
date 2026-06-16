# Implementierungsplan: SQLite Sequence Current-Value Preserve (0.9.7 E.3-Folge-Slice)

> Status: ✅ erledigt (2026-05-29, `ff9fcc71` + Doc-Sync `5530137a`).
> Implementierung in einem Commit gelandet — alle Phasen A–F mit
> Tests, Capability-Flip, CLI-Option, Renderer-Down-Pfad und User-Doku
> (`docs/user/guide.md` §„preserveCurrentValue auf SQLite") sowie
> Changelog-Eintrag (`CHANGELOG.md` §„0.9.7 SQLite-Sequence
> preserveCurrentValue Folge-Slice"). Plan-Doc verlaesst
> `in-progress/` mit dem Closing-Commit, der die DoD-Checkboxen
> rueckwirkend gegen die ausgelieferten Artefakte gespiegelt hat.
> Workstream: E.3 Folge-Slice für SQLite `supportsCurrentValuePreserve`
> Vorarbeit:
> - `docs/planning/done-archive/sqlite-sequence-emulation-plan.md`
> - `docs/planning/done-archive/ImpPlan-0.9.7-sequence-preserve-current-value.md`
>
> Sub-Slice-Schnitt (2026-05-29):
> - Phase A (Vertragsdefinition): siehe §7 Vertragsmatrix.
> - Phase B: neuer `SqliteSequenceCurrentValueProbe`-Adapter.
> - Phase C: Runner-Dispatch + Stage-Wiring inkl. `--sqlite-named-sequences`
>   auf `schema migrate`.
> - Phase D: deterministisches DOWN-Rendering in `SqliteDiffSequenceOps`.
> - Phase E: Capability-Flip + Doku + Changelog.
> - Phase F: Tests + `make docker-test` grün.

## 1. Ausgangslage

SQLite emuliert Sequences im `helper_table`-Modus, aber der Preserve-Pfad für `preserveCurrentValue` ist nicht vollständig aktiv:

- Stage blockiert aktuell mit Dialekt-/Feature-Fehlmeldungen.
- Es gibt keinen dedizierten SQLite-Probe-Adapter.
- Down-Rendering für `AlterSequenceCurrentValue` ist nicht deterministisch.
- `supportsCurrentValuePreserve` bleibt für SQLite ausgeschaltet, obwohl die einzelnen Teile größtenteils vorhanden sind.

Ziel ist, das bisher implizite Gap kontrolliert zu schließen: Probe → Follow-up-Planung → deterministischer Up/Down-Render in einem konsistenten Opt-in-Modus.

## 2. Zielbild

1. SQLite ist im `preserveCurrentValue`-Flow vollständig an PG/MySQL anschlussfähig, jedoch nur im `helper_table`-Modus.
2. Probe liest für Kandidaten den laufenden Wert von `dmg_sequences.next_value` und liefert stabil typisierte Ergebnisse.
3. Up- und Down-Renderemissions für `AlterSequenceCurrentValue` sind vollständig definiert.
4. Die Capability wird erst nach vollständigem technischen Abschluss aktiviert.
5. Alle neuen Fehlerfälle landen als klare Diagnosen, nicht als stiller No-Op.

## 3. In-/Out-of-Scope

### In Scope

- SQLite `SequenceCurrentValueProbe` ergänzen.
- Runner-/Wiring auf Adapter inkl. Stage-Pfade anpassen.
- `SqliteDiffSequenceOps` Down-Restore implementieren.
- SQLite-Opt-in (`helper_table`) in den Preserve-Flow integrieren.
- Capability- und Dokumentations-Update.
- Tests für Probe, Stage, Runner, Down-Renderer.

### Out of Scope

- Transaktionsmäßige Atomgarantie zwischen Probe und Restore.
- Re-Architekturierung der bestehenden SQLite-Helfertabellen.
- Änderungen außerhalb der Preserve-/Sequence-Pipeline.

## 4. Referenzen

1. `docs/planning/done-archive/sqlite-sequence-emulation-plan.md`
2. `docs/planning/done-archive/ImpPlan-0.9.7-sequence-preserve-current-value.md`
3. `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SequenceCurrentValueProbeRunner.kt`
4. `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SequencePreserveStage.kt`
5. `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaMigrateRenderPipeline.kt`
6. `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SequenceCurrentValueProbe.kt`
7. `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SequenceCapabilityDefaults.kt`
8. `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSequenceOps.kt`
9. `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSequenceNaming.kt`
10. `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceCurrentValueProbe.kt`

## 5. Umsetzung in Phasen

### Phase A – Vertragsdefinition und Abbruchmodell

- Define Probe-Ergebnis-Contract für SQLite:
  - `Read`: genau eine passende Zeile, `managed_by = d-migrate`, `format_version = sqlite-sequence-v1`.
  - `NotFound`: Tabelle fehlt **oder** kein passender Datensatz.
  - `Failed(PROBE_PERMISSION_DENIED)` bei DB-Rechten-/Zugriffsfehlern.
  - `Failed(PROBE_UNMANAGED_ROW)` wenn `managed_by` nicht akzeptiert.
  - `Failed(PROBE_UNKNOWN_FORMAT_VERSION)` bei unbekannter `format_version`.
  - `Failed(PROBE_AMBIGUOUS_ROW)` bei mehr als einem Treffer.
  - `Failed(PROBE_QUERY_FAILED)` für generelle SQL-/Driverfehler.
  - `NotApplicable`: nur für Nicht-SQLite.
- Konkrete Preserve-Routing-Regel:
  - `preserveCurrentValue` aktiv **und** DB-Target **und** Modus `helper_table` ⇒ Probe erlaubt.
  - Sonst: explizite Diagnose vor DB-Zugriff.
- NotFound-Policy fixieren:
  - `CreateSequence`: Info/NotRun.
  - `AlterSequence`/`RenameSequence`: Blocker.
- Restore-Referenzverhalten festlegen:
  - UP → `applySequenceRef`
  - DOWN → `probeSequenceRef`

**DoD A**

- [x] Probe-Matrix dokumentiert (Plan oder KDoc).
- [x] `helper_table` als harte Vorbedingung im Preserve-Kontext dokumentiert.
- [x] Nicht-`helper_table` blockiert vor Live-Probe deterministisch.
- [x] Rename-Restore nutzt `probeSequenceRef`.

### Phase B – SQLite-Probe-Adapter implementieren

- Neue Datei ergänzen: `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSequenceCurrentValueProbe.kt`
- Implementierung per `dmg_sequences`-Abfrage mit gebundenem Sequenznamen.
- Mapping auf `SequenceCurrentValueProbeResult` inkl. Fehlercode-Verträge.
- Prüfung von `managed_by` und `format_version` gegen `SqliteSequenceNaming`.
- Keine ungefangene SQL-Exception in den Aufrufer gelangen lassen.

**DoD B**

- [x] Adapter existiert und implementiert das Probe-Interface.
- [x] Happy-Path liefert `Read(value)`.
- [x] `NotFound` deckt fehlende Tabelle und fehlende Zeile ab.
- [x] Mindestanforderung Fehlerszenarien ist getestet (unmanaged, format, permissions/ambiguous, query-fail).

### Phase C – Runner- und Stage-Wiring + Kontextfluss

- Runner-Wiring:
  - `SequenceCurrentValueProbeRunner` routet SQLite auf neuen Adapter.
  - Alte SQLite-`NotApplicable`-Default-Ableitung aufheben.
- Stage-Wiring:
  - Kein generischer „unsupported by dialect“-Stop mehr für SQLite im Preserve-Flow.
  - Reihenfolge strikt fixieren:
    1. Ziel ist DB?
    2. Modus/Opt-in geprüft?
    3. Probe vorhanden?
    4. Kandidat in Follow-up-Routing.
- `helper_table` in die Pipeline/Context tragen, damit Stage sauber bewertet.
- Bestehende Datei-Target-Blocker behalten Vorrang vor Preservergister/Capability-Checks.

**DoD C**

- [x] SQLite-Kandidaten erreichen bei DB-Execute den Probe-Flow.
- [x] `helper_table`-Opt-in ist Pflicht und wird vor Probe geprüft.
- [x] `probe == null` bleibt kontrollierter NotRun-Pfad.
- [x] Alte SQLite-unsupprted-Blocker-Tests ersetzt/angepasst.
- [x] PG/MySQL-Verhalten unverändert.

### Phase D – Down-Rendering fertigstellen

- `SqliteDiffSequenceOps.renderAlterSequenceCurrentValue`:
  - `DOWN` statt permanentem No-Op: `UPDATE "dmg_sequences" SET "next_value" = <value> WHERE "name" = <probeRef>;`
  - Rename-Fälle nutzen den Probe-Refnamen.
- Sicherstellen, dass fehlender Restore-Referenzwert nicht still ignoriert wird.

**DoD D**

- [x] Up-/Down-Restore sind in Tests explizit sichtbar.
- [x] Kein impliziter Skip im normalen Preserve-Down-Case.
- [x] Rename-Fall ist korrekt aufgelöst.

### Phase E – Capability, Docs, Aktivierung

- Erst nach Abschluss A–D:
  - `SequenceCapabilityDefaults.SQLite.supportsCurrentValuePreserve = true`.
  - KDoc/Comments aktualisieren.
  - Capability-Matrix-Tests ergänzen.
- Dokumentation/Guide:
  - `docs/ddl-generation-rules.md`, `docs/user/guide.md`: SQLite als unterstütztes Preserve im `helper_table`-Modus.
  - Klartext-Verhalten bei `action_required` (kein Live-Probe).
- `CHANGELOG.md`: kurze Follow-up-Notiz inkl. Hinweis zur nicht-atomaren Restore-Lücke.

**DoD E**

- [x] Capability ist erst nach komplettem technischen Abschluss aktiv.
- [x] Dokumentation enthält den Opt-in- und Blockierpfad.
- [x] Changelog-Eintrag vorhanden.

### Phase F – Abschlussabnahme

- Testabdeckung:
  - Adapter-Test (Probe), Stage-Test, Runner-Test, Down-Renderer-Test.
- End-to-End-Sicht:
  - SQLite mit `preserveCurrentValue` + `helper_table` erzeugt Probe- und Restore-flows.
  - SQLite ohne helper_table erzeugt klare Opt-in-Diagnose (kein Live-Probe).

**DoD F**

- [x] Kein Dialekt-Unsupprt-Block mehr im gültigen SQLite-Preserve-Flow.
- [x] Up- und Down-Statements enthalten deterministische `dmg_sequences.next_value`-Updates.
- [x] Fehler-/Block-Pfade sind deterministisch und dokumentiert.

## 6. Risiken

1. Zwischen Probe und Restore ist keine Transaktionsbarriere garantiert.
   Folge-Plan: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`.
2. Neue `format_version`-Werte in `dmg_sequences` erfordern Adaptererweiterung.
3. Capability darf nicht vor Abschluss aller technischen Phasen eingeschaltet werden.
4. SQLite-Fallback außerhalb `helper_table` bleibt hart blockiert, um unbestimmtes Verhalten zu vermeiden.

## 7. Vertragsmatrix (Phase A)

### 7.1 Probe-Ergebnis-Contract (SQLite)

| Outcome | Bedingung |
|---|---|
| `Read(value, isCalled=null, managedBy="d-migrate", formatVersion=1)` | genau eine Zeile in `dmg_sequences` mit `managed_by = "d-migrate"` und `format_version = "sqlite-sequence-v1"`. |
| `NotFound` | `dmg_sequences`-Tabelle fehlt **oder** kein Datensatz mit `name = <seq>`. |
| `Failed(PROBE_PERMISSION_DENIED)` | `SQLException` mit SQLite-Fehler `SQLITE_PERM (3)` oder `SQLITE_AUTH (23)`. |
| `Failed(PROBE_UNMANAGED_ROW)` | Zeile vorhanden, `managed_by != "d-migrate"`. |
| `Failed(PROBE_UNKNOWN_FORMAT_VERSION)` | `managed_by = "d-migrate"`, aber `format_version != "sqlite-sequence-v1"`. |
| `Failed(PROBE_AMBIGUOUS_ROW)` | >1 Zeile mit `name = <seq>` (defensiv; PK verhindert das real). |
| `Failed(PROBE_QUERY_FAILED)` | beliebige andere `SQLException`. |
| `NotApplicable` | nur Nicht-SQLite (SQLite gibt nie `NotApplicable` zurück). |

`Read.isCalled` bleibt für SQLite immer `null` — analog zu MySQL, weil
die `next_value`-Semantik bereits den nächsten Wert kodiert.

### 7.2 Stage-Routing für SQLite

Reihenfolge der Skip-/Block-Pfade in `SequencePreserveStage.run(...)`:

1. **File-Target + Kandidaten** → `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`
   (unverändert; gilt unabhängig vom Dialekt).
2. **`!request.execute`** → `NotRun`.
3. **Dialekt unsupported (≠ PG/MySQL/SQLite)** → `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT`.
4. **SQLite mit Kandidaten + Modus ≠ `helper_table`** →
   `SEQUENCE_PRESERVE_OPT_IN_REQUIRED` (neu; siehe §7.3).
5. **Keine Kandidaten** → `NotRun`.
6. **Probe-Fn `null`** → INFO `SEQUENCE_PRESERVE_NOT_RUN_POLICY` pro Kandidat.
7. **MySQL-Config-Invalid-Check (§6.4.4 aus 0.9.7)** → `SEQUENCE_PRESERVE_CONFIG_INVALID`.
8. **Probe-Routing pro Kandidat** (§6.4.5 aus 0.9.7).

### 7.3 Restore-Referenzen

- Up → `applySequenceRef.name` ist Ziel der `UPDATE dmg_sequences SET next_value = …`.
- Down → `probeSequenceRef.name` ist Ziel des Restore-`UPDATE`s.
- Bei `RenameSequence`: `revertAfterRename = true` ⇒ Down läuft nach der
  Rename-Rückoperation; `probeSequenceRef` zeigt auf den ursprünglichen
  (alten) Namen, der nach dem Rename-Down wieder existiert.

### 7.4 NotFound-Policy

- `CreateSequence` (Kandidat über `renameProvenance != null`) +
  `NotFound` → `SEQUENCE_PRESERVE_NOT_FOUND` (INFO, kein Blocker).
  Current-Value-Restore bleibt `ROLLBACK_NOT_POSSIBLE`.
- `AlterSequence`/`RenameSequence` + `NotFound` →
  `SEQUENCE_PRESERVE_PROBE_FAILED` (Blocker).

### 7.5 Neuer Diagnose-Code

`SEQUENCE_PRESERVE_OPT_IN_REQUIRED` → `PlannerBlockerClassifier`
`MANUAL_ACTION_REQUIRED`. Fires für SQLite-Kandidaten ohne
`--sqlite-named-sequences helper_table`; deutlich verschieden von
`NOT_SUPPORTED_BY_DIALECT`, weil die Capability vorhanden ist und
nur der Opt-in fehlt.

---

## Closure (2026-05-31)

Alle Phasen A–F geliefert; Plan-Doc verlaesst `in-progress/` und
wandert nach `done/`. Die Umsetzung lag in einem einzigen Feature-
Commit, die DoD-Checkboxen sind rueckwirkend gegen die ausgelieferten
Artefakte gespiegelt — kein Sub-Slice-Commit pro Phase.

| Bereich | Commit | Resultat |
|---|---|---|
| Implementation | `ff9fcc71` | `SqliteSequenceCurrentValueProbe` (Adapter + `dmg_sequences.next_value`-Read mit `managed_by`/`format_version`-Guard), Runner-Dispatch von SQLite auf den neuen Probe-Adapter (statt `NotApplicable`), `SequencePreserveStage`-Allowlist + Pre-Probe-Blocker `SEQUENCE_PRESERVE_OPT_IN_REQUIRED` (Classifier `MANUAL_ACTION_REQUIRED`), `SqliteDiffSequenceOps.renderAlterSequenceCurrentValue` Down deterministisches `UPDATE dmg_sequences SET next_value = <restoreValue> WHERE name = <probeRef>` (mit `SQLITE_SEQUENCE_CURRENT_VALUE_DOWN_ROLLBACK_IMPOSSIBLE`-Skip bei `null`-restoreValue), `SequenceCapabilityDefaults.SQLite.supportsCurrentValuePreserve` von `false` auf `true` geflippt, neue `--sqlite-named-sequences`-Option auf `schema migrate`, `DdlDialectContext.Sqlite` reicht den Modus durch. |
| Test-Coverage | `ff9fcc71` | `SqliteSequenceCurrentValueProbeTest` (Adapter), `SequencePreserveStageTest` (Stage), `SchemaMigrateRunnerSequencePreserveTest` (Runner), `SqliteDiffSequenceOpsTest` (Down-Renderer), `PlannerBlockerClassifierTest` (neuer Code → Classifier). |
| Doku/Doc-Sync | `5530137a` | `docs/user/guide.md` §„preserveCurrentValue auf SQLite (0.9.7-E.3-Folge-Slice)", `CHANGELOG.md` §„0.9.7 SQLite-Sequence preserveCurrentValue Folge-Slice", Roadmap-Eintrag, `spec/cli-spec.md`/`spec/ddl-generation-rules.md`/`spec/neutral-model-spec.md` mit Plan-Doc-Verweis. |
| Closing (dieser Commit) | — | 22 DoD-Checkboxen retroaktiv auf `[x]`, Header-Status auf `✅`, Plan-Doc-Move nach `done/`, 7 Cross-Refs (Roadmap, in-progress/README, CHANGELOG, drei Specs, `sequence-preserve-atomic-lock-plan`, `quality-coverage-expansion-plan`) auf den `done/`-Pfad nachgezogen. |

**Aktiv offene Folge-Themen** (nicht F-Blocker, ausserhalb dieses
Plans):

- **Atomare Probe + Restore unter Lock** — die Probe→Restore-Lücke
  bleibt nicht-atomar; eigener Draft-Plan in
  [`docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`](./sequence-preserve-atomic-lock-plan.md).
- **W123 (Attached-DB-Rollback-Gate)** bleibt plan-uebergreifend
  offen und ist in der SQLite-Sequence-Emulation-Roadmap als
  Carve-out notiert.
