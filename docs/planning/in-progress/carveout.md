# Carve-Out-Tracker

> Dauerhaft aktiver Aggregator analog zu
> [`roadmap.md`](roadmap.md). Sammelt **explizite Scope-Cut-
> Entscheidungen**, die in Plan-Docs als „bewusst out-of-scope"
> dokumentiert wurden — damit sie bei Folge-Releases und
> Architekturentscheidungen nicht stillschweigend rotten.
>
> Stand: 2026-06-02 (initialer Sweep nach 0.9.7-Release).

---

## 1. Was ist ein Carve-Out?

Ein **Carve-Out** ist eine **bewusste, dokumentierte Scope-
Entscheidung**, ein bestimmtes Verhalten / einen Vertrag /
eine Implementierung **nicht** zu bauen, obwohl es technisch
in den umgebenden Slice gepasst hätte.

Carve-Outs sind **kein Blocker** (Blocker = Tool weigert sich
sich zu liefern, mit Diagnostik) und **kein TODO** (TODO =
sollte gemacht werden, ist nur noch nicht). Ein Carve-Out
sagt: „Dieser Bereich wird **nicht durch dieses Slice**
gelöst — wir haben uns dagegen entschieden, und der Grund ist
dokumentiert."

Beispiele:
- „Cross-DB-Lock für Sequence-Preserve" (Sequence-Preserve-
  Lock-Plan §3.2): permanent out-of-scope, weil d-migrate
  kein verteilter Lock-Manager ist.
- „D-N10k Perf-Sweep" (Quality-Coverage-Expansion-Plan):
  opt-in Nightly, weil Standard-CI-Budget nicht reicht.

---

## 2. Status-Vokabular

| Status | Bedeutung | Lifecycle-Wirkung |
| ------ | --------- | ----------------- |
| **Permanent** | Bewusste Produkt-Entscheidung; wird **nie** gebaut werden ohne Produkt-Re-Charter. | Zeile bleibt für historische Klarheit; neue Slices verweisen darauf statt sie neu zu debattieren. |
| **Provisional** | Aktuell out-of-scope, aber **bei klarer Aktivierungsbedingung** rückholbar. | Zeile bleibt mit explizitem Trigger; sobald Trigger feuert, Eintrag nach `next/` oder `in-progress/` migrieren und Status → **Promoted**. |
| **Promoted** | Wurde aus Carve-Out zu echtem Plan-Slice gehoben; Plan-Doc lebt jetzt in `open/` / `next/` / `in-progress/`. | Zeile bleibt als Audit-Spur; `Plan-Doc`-Ref zeigt jetzt auf den Slice. |
| **Resolved** | Wurde implementiert; Carve-Out ist faktisch geschlossen. | Zeile kann in „§7 Resolved"-Abschnitt verschoben werden, bleibt aber historisch sichtbar. |

---

## 3. Sequence-Preserve / Atomic-Preserve (0.9.7)

Quelldokumente:
- [`../done/sequence-preserve-atomic-lock-plan.md`](../done/sequence-preserve-atomic-lock-plan.md)
  §3.2 Out-of-Scope, §6 Risiken, §7 Out-of-Scope-Folge-Themen,
  §8.2 Carve-outs.
- [`../done/atomic-preserve-followups.md`](../done/atomic-preserve-followups.md) §6 + §8.3.

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| Cross-DB-Lock-Koordination (Operator-Sicht) | Permanent | d-migrate ist kein verteilter Lock-Manager. Wenn zwei DB-Cluster denselben logischen Bereich teilen, ist das Sache des Operators. | sequence-preserve-atomic-lock-plan §3.2 |
| App-side Retry-Hooks | Permanent | d-migrate liefert nur den Blocker-Code (`SEQUENCE_PRESERVE_LOCK_TIMEOUT`); Backoff entscheidet die App. | sequence-preserve-atomic-lock-plan §7 |
| SQLite WAL `BEGIN CONCURRENT` Optimierung | Provisional | Trigger: SQLite-Floor-Hebung auf 3.42+. Bis dahin `BEGIN IMMEDIATE`. | sequence-preserve-atomic-lock-plan §7 |
| PG App-`nextval`-Race während Preserve | Permanent | PG-Sequenzen sind by-design lock-free; `pg_advisory_xact_lock` blockt App-`nextval` nicht. Residuelles Restrisiko ist als negativer Vertragstest (`PostgresSequencePreserveRaceTest`) gepinnt. | sequence-preserve-atomic-lock-plan §6 Risk 8 |
| Cross-JVM-Stresstest (CLI-Pfad) | Permanent | DB-side Lock-Verhalten identisch zu Same-JVM-Two-Threads-Setup. ProcessBuilder-Aufbau lohnt nicht. | atomic-preserve-followups §6 |
| Cross-JVM-Service-Mode-Verträge (MCP/REST/gRPC) | Promoted | Trigger: `schema_migrate` als Tool/Endpoint exponiert. Fünf JVM-Verträge (Pool, Cancellation, Rate-Limit, Lock-Timeout-Tuning, Idempotency-Replay). | [`../next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md) |
| Probe-Adapter-Implementierungen löschen | Permanent | §4.2-Scope-Korrektur 2026-06-01: Adapter-Klassen werden von Atomic-Executoren direkt aufgerufen; bleiben live. | atomic-preserve-followups §8.3 |

---

## 4. SQLite-Sequence-Emulation (0.9.7)

Quelldokument:
[`../done/sqlite-sequence-emulation-plan.md`](../done/sqlite-sequence-emulation-plan.md).

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| W121 — Conflict-Gap-INFO pro Sequence-Spalte | Permanent | INFO-Klassifikation reicht; keine Render-Konsequenz. | sqlite-sequence-emulation §3.4 |
| W122 — UPDATE-Trigger-Interferenz WARNING (konservativ) | Permanent | Echte Feinmatrix bräuchte `UPDATE OF`-Modellierung, ist out-of-scope. | sqlite-sequence-emulation §3.4 |
| W123 — Attached-DB-Rollback-Gate | Provisional | Bleibt plan-übergreifend offen, falls Attached-DB-Operationen je breit benötigt werden. | sqlite-sequence-emulation Phase E (Carve-out-Notiz) |

---

## 5. Diff-basierte Migrationen (Plan 2, 0.9.7)

Quelldokumente:
- [`../done/diffresult-migration-plan-2.md`](../done/diffresult-migration-plan-2.md) §14.3.
- [`../done/quality-coverage-expansion-plan.md`](../done/quality-coverage-expansion-plan.md).

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| `adapter-coverage-uplift` Folge-Slice | Promoted | Phase E.2 hat ihn als eigenständigen Plan-Doc-Trigger ausgegliedert. | [`../open/adapter-coverage-uplift.md`](../open/adapter-coverage-uplift.md) |
| D-N10k Perf-Sweep (N=10000, Nightly-Only) | Provisional | Trigger: dedizierter Nightly-Perf-Runner mit höherem Budget. Bis dahin opt-in. | quality-coverage-expansion-plan §9 |
| Phase H des ersten Plan-Docs nicht re-planen | Permanent | SQLite-Rebuild-Vertrag ist im Phase-H-Closure (Plan 1) abgeschlossen; nicht duplizieren. | diffresult-migration-plan-2 §2 |

---

## 6. F.5 CHECK / EXCLUDE-Constraints (0.9.7)

Quelldokument:
[`../done/ImpPlan-0.9.7-F.5-check-exclude-vollscheibe.md`](../done/ImpPlan-0.9.7-F.5-check-exclude-vollscheibe.md).

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| MySQL CHECK-Enforcement via Capability gegated | Permanent | MySQL hatte historisch CHECK nicht enforced; Capability `MysqlCheckEnforcementCapability` macht den Unterschied explizit, statt zu blocken oder still durchzulassen. | F.5-Vollscheibe Sub-Slice C |
| SQLite EXCLUDE blockiert | Permanent | SQLite kennt EXCLUDE nicht; Operator-Hinweis statt stiller Skip. | F.5-Vollscheibe Sub-Slice D |
| EXCLUDE Operator-Class Whitelist (PG) | Permanent | Nur whitelisted Operator-Klassen gerendert; alles andere blockiert via `ExcludeOperatorClassGate`. | F.5-Vollscheibe Sub-Slice B |

---

## 7. Telemetry + MCP + Produktscope

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| Produktives Metrics-/Tracing-Wiring außerhalb 0.9.7 | Provisional | Trigger: Telemetry-Adapter-Slice. Plan existiert in `next/`. | [`../next/telemetry-observability-port.md`](../next/telemetry-observability-port.md) |
| MCP-Server-Last-Tests | Provisional | Trigger: eigene Last-Strategie, gehört zum `spec/mcp-server.md`-Vertrag. | `../../../spec/mcp-server.md` |
| MCP-Migrate-Tool (`schema_migrate`/`_start`) | Promoted | Neues Produkt-/Contract-Thema. Vorabklärung 2026-06-03 angelegt; same-day Promote nach `next/` mit Sub-Slice-Schnitt F.1-F.5 + Wire-Vertrag V1. Atomic-Preserve-Plan-Doc 2026-06-03 in next/ + done/ImpPlan-0.9.8-AE.md gesplittet. | [`../next/mcp-schema-migrate-tool.md`](../next/mcp-schema-migrate-tool.md), [`../next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md), [`../done/ImpPlan-0.9.8-atomic-preserve-AE.md`](../done/ImpPlan-0.9.8-atomic-preserve-AE.md), quality-coverage-expansion-plan §3.2 + §9 |
| App-Layer-Replay für Concurrent-Writer-Tests | Permanent | Anwendungssache, nicht d-migrate-Scope. | quality-coverage-expansion-plan §9 |
| Mutation-Testing (PIT/Stryker) | Provisional | Trigger: stabile Coverage-Baseline + konsolidierte Excludes. | quality-coverage-expansion-plan §9 |

---

## 8. Lifecycle und Pflege

- **Neuer Carve-Out** → in das passende §3-§7 (oder neuen
  Abschnitt) als Zeile aufnehmen; Status setzen; Plan-Doc-Ref
  zeigen. Im Quelldokument einen Verweis auf diese Datei
  ergänzen.
- **Promotion** (Provisional → Plan-Slice): Status auf
  **Promoted** setzen, `Plan-Doc-Ref`-Spalte auf den neuen
  Slice umbiegen. Zeile bleibt für die Audit-Spur.
- **Resolution** (Permanent → Resolved): in §9 Resolved
  verschieben mit Datum und Release-Bezug.
- **Konvention für Quelldokumente**: jeder Carve-Out-Block in
  einem Plan-Doc sollte einen Link zurück auf die passende
  Zeile in diesem Tracker tragen (Format:
  `siehe [`carveout.md`](../in-progress/carveout.md) §X`).

---

## 9. Resolved

*(noch leer — wird beim ersten Carve-Out-Resolve gefüllt)*
