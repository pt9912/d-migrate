---
status: accepted
date: 2026-06-23
decision-makers: pt9912
consulted: docs/planning/next/tpc-performance-slice.md (Phase 4, Blocker 3), docs/operations/performance-benchmarks.md (Zwei-Budget-Modell + Abnahme-Lücke), spec/lastenheft-d-migrate.md (LF 8.2)
informed: test/perf-large-schema (LargeSchemaScaleSpec), hexagon/profiling (PerfMeasure/PerfReport)
---

# Normierte Mess-Umgebung für die LF-8.2-Performance-Abnahme

> **Status: accepted (ratifiziert 2026-06-23).** Die zuvor offenen Parameter sind unten
> unter „Ratifizierte Parameter" konkretisiert. Löst **Blocker 3** des TPC-Slice
> [`../planning/next/tpc-performance-slice.md`](../planning/next/tpc-performance-slice.md);
> entsperrt die harten 4c/4d-Zeit-Budgets.

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

## Ratifizierte Parameter (ratifiziert 2026-06-23)

1. **Container-Caps der Referenz: `--cpus=2 --memory=4g`.** Passt unter den kleinsten
   plausiblen designierten Runner (GitHub-hosted `ubuntu-latest`, historisch 2 vCPU/7 GB,
   aktuell 4 vCPU/16 GB) — dieselben Caps reproduzieren auf beiden, da der Host stets
   ≥ Caps ist. Bewusst konservativ niedrig: ein engerer Cap macht das Absolutbudget
   **strenger** (kein False-Pass auf dickem Host) und bleibt auf bescheidener Hardware
   nachfahrbar. `4g` gibt Headroom über das JVM-Heap-Budget; **die LN-004-Grenze
   „max. 2 GB für Schema-Operationen" wird davon nicht berührt** — sie betrifft den
   Heap-Arbeitssatz, der separat und strenger durch das bestehende Heap-Budget
   (N=1000 ≤ 1024 MB) erzwungen wird, nicht den Container-Gesamtspeicher (JVM-Non-Heap
   + OS-Page-Cache für den Export-IO). *Pin-Charakter:* die konkreten Zahlen sind der
   ratifizierte Startwert; zeigt die erste 4c/4d-Messung, dass ein korrekter Impl
   darunter nicht in Budget kommt, folgt ein **dokumentierter Pin-Bump** (analog
   Image-/Versions-Pins) — der **Vertrag** (fixe dokumentierte Caps + Guard) bleibt.
2. **Kalibrierungs-Operation: der `diff-planner`-Hotpath** (`DiffPlanner.plan` gegen
   das synthetische 100-Tabellen-Schema) — rein CPU-gebunden, deterministisch,
   allokationsarm, bereits als gepinnter Hotpath vorhanden (250-ms-Baseline), kein
   neuer Code/keine Daten. **Toleranzband: ±25 %.** Der im selben Lauf gemessene
   Kalibrier-Median wird gegen einen **Referenz-Median** verglichen, der **einmalig auf
   dem designierten Runner unter den Referenz-Caps bei Acceptance-Tier-Aktivierung
   (4d-Bau) erfasst und im CI/Perf-Report gepinnt** wird (kein hier erfundener Zahlwert).
   Bei `|1 − Kalibrier/Referenz| > 0,25` **fällt das Acceptance-Gate auf diagnostisch
   zurück** (Drift berichtet, kein False-Fail auf Off-Spec-Runner). ±25 % trennt
   „gleiche Maschinenklasse" von „off-spec/laut"; Startband, nach beobachteter
   Nightly-Streuung per Pin-Bump nachjustierbar. Die Kalibrier-Op läuft mit K=2/M=5
   (billig, stabiler Median).

   **Ergänzung (4c Teil 2, 2026-06-23) — Kalibrier-Op je Mess-Substrat.** Die
   ursprüngliche Formulierung („`DiffPlanner.plan` via `PerfMeasure`") setzt das
   **JVM-Tier** voraus (Kotest-Perf-Specs wie `LargeSchemaScaleSpec`). Die
   4c-Volumen-Abnahme misst aber im **CLI-Tier** (Bash/Docker `data export`→`import`),
   das kein `PerfMeasure` teilt. Auflösung: im CLI-Tier ist die Kalibrier-Op
   **derselbe diff-planner-Hotpath, nur CLI-invokiert** — `schema generate` gegen ein
   fixes generisches Schema (`examples/sample-db/calib-schema.yaml`) fährt
   SchemaComparator→DiffPlanner→Renderer. Sie trägt zusätzlich den **JVM-Startup**,
   den die gemessenen CLI-Ops (export/import) ebenfalls zahlen → repräsentativ fürs
   CLI-Substrat. Stabilität live belegt (~8 % Streuung über 5 Läufe unter Caps),
   komfortabel unter dem ±25 %-Band. Referenz-Median bleibt **runner-spezifisch
   gepinnt** (`CALIB_REFERENCE_MS`); ohne Pin läuft das CLI-Tier im **Bootstrap**
   (diagnostisch, meldet den Median zum Pinnen). Damit ist die Substrat-Lücke
   geschlossen, ohne die JVM-Tier-Definition zu ändern.
3. **Mess-Vertrag des Acceptance-Tiers: K=1 Warmup + M=3 gemessen, Gate auf Median,
   `p95` im Report.** Bewusste, hier dokumentierte Abweichung vom `PerfMeasure`-Default
   5/20 (dessen KDoc einen Begründungs-Vermerk verlangt — diese ADR ist er): die
   Volumen-Ops (Export/Import 1 Mio ≈ 100/200 s, DDL-1000 < 30 s) machen 20 Iterationen
   wand-zeit-unbezahlbar. 1 Warmup absorbiert Cold-JIT/Classload/Page-Cache, Median-aus-3
   ist robust gegen einen einzelnen Ausreißer. Die billigen bestehenden Micro-Hotpaths
   behalten den Lib-Default 5/20.
4. **Designierter Nightly-Runner: ein eigener geplanter Workflow `perf-acceptance.yml`**
   (in 4c/4d anzulegen), `runs-on: ubuntu-latest`, getriggert per `schedule` (Cron) **+**
   `workflow_dispatch`, `continue-on-error` — exakt das Gating-Muster von
   `sample-db-scale.yml` (versetzter Cron-Slot, z. B. 04:47 UTC, um die Runner-Spitze
   des 03:17-Scale-Jobs zu meiden). **Nur dort** wird `PERF_GATE=true` gesetzt, sodass
   das Acceptance-Tier ausschließlich auf diesem Lauf hart assertiert; überall sonst
   (PR, lokal) bleibt es diagnostisch. Die GH-hosted-Runner-Streuung fängt der
   Kalibrierungs-Guard (Punkt 2) ab.

## Konsequenzen

- Die LF-8.2-Abnahme ist als „**unter Referenz-Caps 2 CPU/4 GB** läuft X in < T" formal
  belegbar — reproduzierbar, ohne dedizierte Hardware.
- Baut additiv auf `PerfMeasure`/`PerfReport` + dem Zwei-Budget-Modell auf (drittes
  Tier + Kalibrierungs-Guard); kein Umbau.
- 4c/4d des TPC-Slice sind mit diesen ratifizierten Parametern baubar (harte
  Acceptance-Gates); `performance-benchmarks.md` wird beim Bau um das Acceptance-Tier
  + die Referenz-Umgebung ergänzt.
