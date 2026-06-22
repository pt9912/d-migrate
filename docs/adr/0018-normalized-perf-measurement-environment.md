---
status: proposed
date: 2026-06-22
decision-makers: pt9912
consulted: docs/planning/next/tpc-performance-slice.md (Phase 4, Blocker 3), docs/operations/performance-benchmarks.md (Zwei-Budget-Modell + Abnahme-Lücke), spec/lastenheft-d-migrate.md (LF 8.2)
informed: test/perf-large-schema (LargeSchemaScaleSpec), hexagon/profiling (PerfMeasure/PerfReport)
---

# Normierte Mess-Umgebung für die LF-8.2-Performance-Abnahme

> **Status: proposed (Entwurf/Vorlage).** Konkrete Parameter (unten „Offene Felder")
> vor `accepted` festlegen. Löst **Blocker 3** des TPC-Slice
> [`../planning/next/tpc-performance-slice.md`](../planning/next/tpc-performance-slice.md);
> gatet die harten 4c/4d-Zeit-Budgets.

## Kontext und Problemstellung

LF 8.2 nennt **absolute Wandzeiten** („Export 1 Mio < 100 s", „Import 1 Mio < 200 s",
„DDL-Generierung 1.000 Tabellen < 30 s" = LN-004) — aber **keine Hardware**. Eine
absolute Zahl ist nur auf einer **definierten** Umgebung sinnvoll: auf wechselnden,
geteilten CI-Runnern ist sie entweder flaky (schneller vs. langsamer Runner) oder so
locker, dass sie nichts abnimmt.

Das vorhandene Framework (`performance-benchmarks.md`) hat die richtige **Form** —
`PerfMeasure` (Iterationen → median/p95) + ein **Zwei-Budget-Modell**: Smoke (immer
geprüft, Runaway-Guard) und Baseline (Nightly-Ziel, auf geteilter CI nur diagnostisch,
hart nur unter `PERF_GATE=true`). Es fehlt aber eine **definierte Abnahme-Umgebung**,
gegen die die LF-8.2-Absolutbudgets autoritativ assertiert werden.

## Entscheidung

**Eine reproduzierbare, container-normierte Referenz-Umgebung + ein drittes
„Acceptance"-Budget-Tier, mit Kalibrierungs-Guard** — aufbauend auf dem bestehenden
Zwei-Budget-Modell (KEINE dedizierte Hardware-Beschaffung):

1. **Acceptance-Tier (neu, dritte Stufe).** Zusätzlich zu Smoke (Runaway) und Baseline
   (Regression) trägt der relevante Hotpath ein **Acceptance-Budget** = die
   LF-8.2-Absolutzahl (z. B. DDL-1000 < 30 s, Export 1 Mio < 100 s, Import < 200 s).
2. **Container-Caps-Referenz.** Die Abnahme läuft in einem Container mit **fixierten,
   dokumentierten Ressourcen-Caps** (`--cpus=N`, `--memory=M`). Das ist die
   reproduzierbare Referenz: die Absolutbudgets gelten „unter Caps N/M". Jeder Host
   mindestens dieser Kapazität kann die Abnahme mit identischen Caps fahren.
3. **Designierter Nightly-Runner.** `PERF_GATE=true` assertiert das Acceptance-Tier **nur**
   auf einem designierten Nightly-Runner (nicht im PR-Gate). Überall sonst bleibt es
   diagnostisch — exakt der bestehende diagnostisch↔hart-Mechanismus, nur um das
   Acceptance-Tier erweitert.
4. **Kalibrierungs-Guard.** Eine **stabile Referenz-Micro-Operation** läuft im selben
   Lauf mit. Ihr Messwert gegen einen dokumentierten Referenzwert ergibt das
   **Host-Speed-Verhältnis**. Weicht der Host > Toleranzband von der Referenz ab,
   **fällt das Acceptance-Gate auf diagnostisch zurück** (kein False-Fail auf einem
   Off-Spec-Runner; der Drift wird berichtet). So ist das harte Gate nur dann scharf,
   wenn die Umgebung tatsächlich der Referenz entspricht.
5. **Mess-Vertrag.** `PerfMeasure` mit **K Warmup-Iterationen (verworfen)** + **M
   gemessenen**; Gate auf **Median**, `p95` im Report. Absorbiert JIT-/Cold-Start-
   Schwankung (heute schon teils so; hier als Acceptance-Vertrag fixiert).

## Verworfene Alternativen

- **Dedizierter Referenz-Runner** (fixe Maschine/VM-Type): genaueste Abnahme, aber
  Hardware-Beschaffung + Kosten + Runner-Verdrahtung. Bei Bedarf später als
  schärfere Variante nachrüstbar (Caps-Referenz bleibt der Default).
- **Rein self-calibrating relative Budgets** (Budget = Absolut × Host/Referenz-Ratio):
  hardware-unabhängig, aber bildet die LF-**Absolut**zahl nur indirekt ab — schwächerer
  formaler „erfüllt < 100 s"-Beleg. Der Kalibrierungs-Guard übernimmt das *Gute* daran
  (Off-Spec-Erkennung), ohne das Budget selbst zu relativieren.

## Offene Felder (vor `accepted`)

1. **Konkrete Caps:** `--cpus`/`--memory` der Referenz (+ kurze Begründung der Wahl).
2. **Kalibrierungs-Operation + Toleranzband** (welche stabile Op; ± wieviel % Drift
   schaltet auf diagnostisch).
3. **Warmup-/Iterations-Zahlen** (K/M) für das Acceptance-Tier.
4. **Designierter Nightly-Runner** (welcher Workflow/Runner trägt das harte Gate).

## Konsequenzen

- Die LF-8.2-Abnahme ist als „**unter Referenz-Caps N/M** läuft X in < T" formal
  belegbar — reproduzierbar, ohne dedizierte Hardware.
- Baut additiv auf `PerfMeasure`/`PerfReport` + dem Zwei-Budget-Modell auf (drittes
  Tier + Kalibrierungs-Guard); kein Umbau.
- 4c/4d des TPC-Slice werden nach Ratifizierung dieser Parameter baubar; `performance-
  benchmarks.md` wird um das Acceptance-Tier + die Referenz-Umgebung ergänzt.
