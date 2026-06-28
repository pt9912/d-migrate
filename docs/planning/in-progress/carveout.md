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
- [`../done-archive/sequence-preserve-atomic-lock-plan.md`](../done-archive/sequence-preserve-atomic-lock-plan.md)
  §3.2 Out-of-Scope, §6 Risiken, §7 Out-of-Scope-Folge-Themen,
  §8.2 Carve-outs.
- [`../done-archive/atomic-preserve-followups.md`](../done-archive/atomic-preserve-followups.md) §6 + §8.3.

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
[`../done-archive/sqlite-sequence-emulation-plan.md`](../done-archive/sqlite-sequence-emulation-plan.md).

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| W121 — Conflict-Gap-INFO pro Sequence-Spalte | Permanent | INFO-Klassifikation reicht; keine Render-Konsequenz. | sqlite-sequence-emulation §3.4 |
| W122 — UPDATE-Trigger-Interferenz WARNING (konservativ) | Permanent | Echte Feinmatrix bräuchte `UPDATE OF`-Modellierung, ist out-of-scope. | sqlite-sequence-emulation §3.4 |
| W123 — Attached-DB-Rollback-Gate | Provisional | Bleibt plan-übergreifend offen, falls Attached-DB-Operationen je breit benötigt werden. | sqlite-sequence-emulation Phase E (Carve-out-Notiz) |

---

## 5. Diff-basierte Migrationen (Plan 2, 0.9.7)

Quelldokumente:
- [`../done-archive/diffresult-migration-plan-2.md`](../done-archive/diffresult-migration-plan-2.md) §14.3.
- [`../done-archive/quality-coverage-expansion-plan.md`](../done-archive/quality-coverage-expansion-plan.md).

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| `adapter-coverage-uplift` Folge-Slice | Promoted | Phase E.2 hat ihn als eigenständigen Plan-Doc-Trigger ausgegliedert; Spike erledigt, jetzt in `next/`. | [`../next/adapter-coverage-uplift.md`](../next/adapter-coverage-uplift.md) |
| D-N10k Perf-Sweep (N=10000, Nightly-Only) | Provisional | Trigger: dedizierter Nightly-Perf-Runner mit höherem Budget. Bis dahin opt-in. | quality-coverage-expansion-plan §9 |
| Phase H des ersten Plan-Docs nicht re-planen | Permanent | SQLite-Rebuild-Vertrag ist im Phase-H-Closure (Plan 1) abgeschlossen; nicht duplizieren. | diffresult-migration-plan-2 §2 |

---

## 6. F.5 CHECK / EXCLUDE-Constraints (0.9.7)

Quelldokument:
[`../done-archive/ImpPlan-0.9.7-F.5-check-exclude-vollscheibe.md`](../done-archive/ImpPlan-0.9.7-F.5-check-exclude-vollscheibe.md).

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| MySQL CHECK-Enforcement via Capability gegated | Permanent | MySQL hatte historisch CHECK nicht enforced; Capability `MysqlCheckEnforcementCapability` macht den Unterschied explizit, statt zu blocken oder still durchzulassen. | F.5-Vollscheibe Sub-Slice C |
| SQLite EXCLUDE blockiert | Permanent | SQLite kennt EXCLUDE nicht; Operator-Hinweis statt stiller Skip. | F.5-Vollscheibe Sub-Slice D |
| EXCLUDE Operator-Class Whitelist (PG) | Permanent | Nur whitelisted Operator-Klassen gerendert; alles andere blockiert via `ExcludeOperatorClassGate`. | F.5-Vollscheibe Sub-Slice B |

---

## 7. Telemetry + MCP + Produktscope

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| Produktives Metrics-/Tracing-Wiring außerhalb 0.9.7 | Promoted | Produktives Wiring ist Teil des Telemetry-Adapter-Slice; der Plan lebt in `next/` (Status Draft) → per Status-Vokabular Promoted (Plan-Doc existiert), nicht mehr Provisional. | [`../next/telemetry-observability-port.md`](../next/telemetry-observability-port.md) |
| MCP-Server-Last-Tests | Provisional | Trigger: eigene Last-Strategie, gehört zum `spec/mcp-server.md`-Vertrag. | `../../../spec/mcp-server.md` |
| MCP-Migrate-Tool (`schema_migrate`/`_start`) | Promoted | Neues Produkt-/Contract-Thema. Vorabklärung 2026-06-03 angelegt; same-day Promote nach `next/` mit Sub-Slice-Schnitt F.1-F.5 + Wire-Vertrag V1. Atomic-Preserve-Plan-Doc 2026-06-03 in next/ + done-archive/ImpPlan-0.9.8-AE.md gesplittet. | [`../next/mcp-schema-migrate-tool.md`](../next/mcp-schema-migrate-tool.md), [`../next/atomic-preserve-service-mode.md`](../next/atomic-preserve-service-mode.md), [`../done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md`](../done-archive/ImpPlan-0.9.8-atomic-preserve-AE.md), quality-coverage-expansion-plan §3.2 + §9 |
| App-Layer-Replay für Concurrent-Writer-Tests | Permanent | Anwendungssache, nicht d-migrate-Scope. | quality-coverage-expansion-plan §9 |
| Mutation-Testing (PIT) | Promoted | Trigger (stabile Coverage-Baseline + konsolidierte Excludes) 2026-06-27 **erfüllt** (Kover-90-%-Per-Modul-Gate, 251 ledger-dokumentierte Excludes). Scope-Entwurf (Draft, Vorschlags-Altitude) in `open/`; noch nicht nach `next/` priorisiert. | [`../open/mutation-testing-pit.md`](../open/mutation-testing-pit.md) |

---

## 8. Volltext-Spaltentyp `fulltext` (ADR 0015, 0.9.9)

Quelldokument:
[`../../adr/0015-fulltext-tsvector-neutral-type.md`](../../adr/0015-fulltext-tsvector-neutral-type.md)
(Abschnitt „Abgrenzung").

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| Strukturelle Cross-Dialect-Volltext-Übersetzung (SQLite FTS5 / MySQL FULLTEXT) | Promoted | SQLite-FTS5 ist eine **virtuelle Tabelle**, MySQL-FULLTEXT ein **Index** — beide sind ein *struktureller* Umbau (Spalte → separate Tabelle/Index + Sync-Trigger), keine Typ-↔-Typ-Abbildung. Bis dahin degradiert der `fulltext`-Spaltentyp cross-dialect zu `text`. **Trigger:** Cross-Dialect-Phase des Sample-DB-Harness (Phase 2/2b) bzw. eigener Volltext-Struktur-Slice (eigene ADR). Scope mit Phasen P0–P5 + Akzeptanzkriterien in `in-progress/` (P0 erledigt — Degradierungs-Note W132; P1–P5 offen). | [`fulltext-structural-cross-dialect.md`](fulltext-structural-cross-dialect.md) |
| Weitere PG-only-Typen first-class (`inet`, `cidr`, `tsquery`, Ranges, `ltree`, …) | Provisional | ADR 0015 deckt **nur** Volltext-Vektoren ab; jeder weitere native Typ ist eine eigene first-class-Entscheidung. Bis dahin degradieren sie zu `text` + `R301`. **Trigger:** konkreter Fidelity-Bedarf (z. B. ein neues Sample-DB-Finding). Kandidaten-Sammlung/Trigger-Watch (kein Slice — pro Typ eigene Entscheidung) in `open/`. | [`../open/pg-only-types-first-class-candidates.md`](../open/pg-only-types-first-class-candidates.md) |

---

## 9. Cross-Dialect-Partitionierung (ADR 0020, 0.9.9)

Quelldokument: [`../done/cross-dialect-partitioning.md`](../done/cross-dialect-partitioning.md)
(Review-Härtung Runde 1 — bewusst nicht in den AP6-Abschluss gezogen, beide sind eigene Sub-Slices).

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| Kind-lokale FK-Constraints round-trippen (E065-Transparenz) | Promoted | PG erlaubt FKs direkt auf Kind-Partitionen (z. B. Pagila-`payment`-Kinder); `PartitionDefinition` trägt heute **kein** FK-Feld → kind-lokale FKs fallen beim Reverse still weg. Das MySQL-**Ergebnis** bleibt korrekt (FKs auf partitionierten Tabellen dort ohnehin verboten, E065), nur die Transparenz für *kind-lokale* FKs fehlt. **Trigger:** Cross-Dialect-Fidelity-Bedarf; braucht FK-Feld auf `PartitionDefinition` + Ergänzung von [ADR 0019](../../adr/0019-partition-hierarchy-structured-representation.md) + [ADR 0020](../../adr/0020-cross-dialect-partitioning-mysql.md) + Reverse-Erfassung. Scope-Entwurf (Draft, Vorschlags-Altitude) in `open/`; noch nicht nach `next/` priorisiert. | [`../open/partition-child-local-fk-transparency.md`](../open/partition-child-local-fk-transparency.md) |
| LIST-`DEFAULT`-Transfer-Preflight | Promoted | Eine PG-LIST-`DEFAULT`-Partition hat kein MySQL-Pendant; die Generate-Note **E063** flaggt den Verwurf bereits laut (`action_required`, „Transfer-Datenverlust"). Eine zusätzliche Transfer-Zeit-Preflight (Zeilen zählen, die in die DEFAULT-Partition fielen) fehlt. **Trigger:** dediziertes Transfer-Preflight-Slice (spiegelt `CheckPreflight`: planner→runner→renderer→report). Scope-Entwurf (Draft, Vorschlags-Altitude) in `open/`; noch nicht nach `next/` priorisiert. | [`../open/partition-list-default-transfer-preflight.md`](../open/partition-list-default-transfer-preflight.md) |

---

## 10. TPC-Performance-Abnahme (ADR 0018, 0.9.9)

Quelldokument: [`../done/tpc-4c-volume-acceptance-slice.md`](../done/tpc-4c-volume-acceptance-slice.md)
(Closure — Option C: `ubuntu-latest` diagnostisch, Hart-Gate-Arming als reiner Ops-Schritt).

| Carve-Out | Status | Reason / Trigger | Plan-Doc-Ref |
| --------- | ------ | ---------------- | ------------ |
| Absolutes Durchsatz-Hart-Gate (LF 8.2) scharf stellen | Provisional | Der Kalibrier-Guard hält das Zeit-Gate auf dem variablen `ubuntu-latest`-CI-Runner by-design diagnostisch (kein verlässlicher Zeit-Bezug). Verlustfreiheit + Resume sind ohnehin host-unabhängig hart. **Trigger:** einen stabilen Runner designieren (Repo-Variable `PERF_RUNNER`) + `CALIB_REFERENCE_MS` aus einem Bootstrap-Lauf pinnen — reine Ops, kein Code (Runbook im Quelldokument). | [`../done/tpc-4c-volume-acceptance-slice.md`](../done/tpc-4c-volume-acceptance-slice.md) |

---

## 11. Lifecycle und Pflege

- **Neuer Carve-Out** → in das passende §3-§7 (oder neuen
  Abschnitt) als Zeile aufnehmen; Status setzen; Plan-Doc-Ref
  zeigen. Im Quelldokument einen Verweis auf diese Datei
  ergänzen.
- **Promotion** (Provisional → Plan-Slice): Status auf
  **Promoted** setzen, `Plan-Doc-Ref`-Spalte auf den neuen
  Slice umbiegen. Zeile bleibt für die Audit-Spur.
- **Resolution** (Permanent → Resolved): in §12 Resolved
  verschieben mit Datum und Release-Bezug.
- **Konvention für Quelldokumente**: jeder Carve-Out-Block in
  einem Plan-Doc sollte einen Link zurück auf die passende
  Zeile in diesem Tracker tragen (Format:
  `siehe [`carveout.md`](../in-progress/carveout.md) §X`).

---

## 12. Resolved

*(noch leer — wird beim ersten Carve-Out-Resolve gefüllt)*
