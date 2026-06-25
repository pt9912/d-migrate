# Slice: TPC Sub-Slice 4d — LN-004 DDL-1000-Gate (synthetisch, nicht TPC)

> Dokumenttyp: abgeschlossener Slice (aus dem Umbrella
> [`../done/tpc-performance-slice.md`](../done/tpc-performance-slice.md), Sub-Slice 4d).
> **Status: abgeschlossen + live verifiziert (2026-06-23).** LN-004 erfüllt, Gate grün
> unter `PERF_GATE=true`.

## Ziel

LN-004 / LF 8.2: „DDL-Generierung für **1.000 Tabellen** in unter 30 s" als verlässliches
Gate. Synthetisch (JVM-Kotest-Perf-Spec `LargeSchemaScaleSpec`, `make docker-perf`),
**nicht** TPC.

## Datenbefund (live gemessen, unter PERF_GATE)

| Hotpath | Objekte | Median | Bedeutung |
|---------|---------|--------|-----------|
| `ddl-1000-tables-ln004` (**neu**) | 1000 reine Tabellen | **1 711 ms** | **LN-004 erfüllt, ~17× Marge** |
| `large-schema-render-n1000` (4×n) | 4001 (1000 Tab+Seq+View+Trigger+1 Fn) | **52 460 ms** | umfassender Stress, **nicht** LN-004 |
| `large-schema-render-n100` (4×n) | 401 | 385 ms | LN-001 („100 Tab < 5 s") erfüllt |

## Entscheidung: 4×n-Diff vs. reiner DDL-Pfad (der Plan-Fork) — datenbelegt

Das bestehende N=1000-4×n-„30-s-Gate" war auf LN-004 **fehl-gemappt**: es misst 4001
**gemischte** Objekte + Dependency-Topologie (Views→Tables, Trigger→Function), nicht
„1.000 Tabellen". Die a-fortiori-Richtung greift **nicht** (4×n > 30 s beweist nicht
1000-Tabellen > 30 s). Lösung:

- **Faithful LN-004-Gate ergänzt** (`ddl-1000-tables-ln004`): `mixedSchema(tables=1000,
  sequences=0, views=0, triggers=0)`, reine Tabellen-DDL → **1,7 s**, Baseline 30 s
  (hart unter `PERF_GATE`). Erfüllt LN-004 mit großer, host-robuster Marge.
- **4×n-N=1000-Baseline 30 s → 90 s korrigiert** (realistischer Regressions-Guard für den
  umfassenden Pfad ~52 s, **kein** LF-Abnahmebudget). Damit ist das Modul endlich
  `PERF_GATE`-fähig (vorher riss das 4×n-30s-Gate immer).

## Nebenbefund (eigenes Ticket): 4×n skaliert super-linear

N=100 (401 Obj) ~385 ms vs. N=1000 (4001 Obj) ~52 s ≈ **136× für 10× Objekte** — stark
super-linear, vermutlich O(n²) in der Dependency-Auflösung (Views/Trigger → Tabellen/
Funktion). Reine Tabellen (1,7 s/1000) skalieren ~linear. Eigenes Ticket:
[`../open/large-schema-superlinear-scaling.md`](../open/large-schema-superlinear-scaling.md).
Nicht 4d-blockierend (LN-004 ist erfüllt).

## Definition of Done (Modul 5)

- [x] LN-004 („1000 Tabellen < 30 s") faithful gemessen: **1,7 s**, hart grün unter
      `PERF_GATE=true`.
- [x] LN-001 („100 Tabellen < 5 s") via N=100-4×n (0,4 s) bestätigt.
- [x] 4×n-vs-reiner-DDL-Fork **datenbelegt entschieden** (reiner Pfad = LN-004; 4×n =
      Stress-Guard, Baseline auf 90 s realistisch korrigiert) → Modul `PERF_GATE`-fähig.
- [x] Super-linear-Skalierung als Ticket notiert.
- [x] Doku-Sync `performance-benchmarks.md` §4 (Baseline-Spalte + LN-004-Zeile).
- [x] `make docker-perf MODULES=":test:perf-large-schema" PERF_GATE=true` grün; opt-in,
      **nicht** im PR-Gate; `make docs-check` grün.

## Hinweis (normierte Umgebung)

Anders als die 4c-CLI-Durchsatz-Budgets ist das LN-004-Gate (1,7 s ≪ 30 s) **host-robust**
— die ~17× Marge übersteht auch einen Off-Spec-Host, kein Kalibrier-Guard nötig. Der
4×n-Stress-Guard (90 s) ist großzügig genug, dass die ~52 s + Cold-Start-Streuung nicht
flaken.
