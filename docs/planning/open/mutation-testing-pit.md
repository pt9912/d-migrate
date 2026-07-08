# Vorschlag: Mutation-Testing (PIT) für die JVM-Module

> **Status:** Draft (Vorschlag, 2026-06-27)
> **Trigger:** Carve-Out aus dem Quality-Coverage-Expansion-Plan, getrackt in
> [`../in-progress/carveout.md`](../in-progress/carveout.md), Abschnitt 7. Die dort
> genannte Aktivierungsbedingung — „stabile Coverage-Baseline **und** konsolidierte
> Excludes" — ist **erfüllt** (2026-06-27): Kover-Per-Modul-Gate bei 90 %, 251
> Kover-Excludes ledger-dokumentiert + d-check-verifiziert.
> **Aktivierungsbedingung:** Sobald die Mutationsabdeckung als Qualitätssignal priorisiert
> wird, wandert dieser Vorschlag nach [`../next/`](../next/) — **dort** mit Phasenschnitt,
> Modul-Reihenfolge und konkreten Schwellen/Akzeptanzkriterien
> ([ADR 0004](../../adr/0004-documentation-and-planning-structure.md) reserviert
> ausgearbeitete Phasen/Akzeptanz für `next/`). Dieses `open/`-Dokument bleibt auf
> Vorschlags-Altitude: Ziel, Scope und offene Designentscheidungen.

## 1. Ziel

Die **Test-Wirksamkeit** messbar machen, nicht nur die Zeilenabdeckung. Kover sichert heute
≥ 90 % Zeilen-/Branch-Coverage pro Modul — aber hohe Coverage beweist nur, dass Code
*ausgeführt*, nicht dass er *sinnvoll asserriert* wird. Mutation-Testing (PIT) führt
gezielte Code-Mutationen ein (Bedingung negiert, Rückgabewert verfälscht, Operator
getauscht) und prüft, ob die Testsuite sie **fängt** (killt). Überlebende Mutanten zeigen
schwache/fehlende Assertions, die Coverage allein nicht aufdeckt.

## 2. Hintergrund (Ist-Stand im Code)

- **Build:** Gradle (Kotlin), Coverage via Kover 0.9.8 — Plugin + Per-Modul-Aggregation in
  [`build.gradle.kts`](../../../build.gradle.kts); das 90-%-Gate läuft als `koverVerify` je
  Modul, die Excludes sind über
  [`scripts/verify-kover-excludes-ledger.py`](../../../scripts/verify-kover-excludes-ledger.py)
  ledger-pflichtig (251 Einträge, d-check-Gate).
- **CI:** Coverage als eigener Workflow
  [`.github/workflows/coverage-modules.yml`](../../../.github/workflows/coverage-modules.yml);
  daneben `build.yml`, `integration.yml` und opt-in-/Nightly-Jobs (`perf-acceptance.yml`,
  `sample-db-*`). **Es gibt heute kein pitest/Mutation-Setup** (grep leer).
- **Modul-Topologie:** viele Module
  ([`settings.gradle.kts`](../../../settings.gradle.kts)) — reine JVM-Domänenlogik
  (`hexagon:core`, `hexagon:application`, `hexagon:ports-*`) gegen container-abhängige
  Integrationsmodule (`test:integration-*`, `test:e2e-cli`, `test:cross-dialect-matrix`).
  Diese Trennung ist für Mutation-Testing zentral (siehe offene Entscheidung 2).

## 3. Scope

### 3.1 In Scope

- **PIT (pitest)** als JVM-Mutation-Engine via Gradle-Plugin, zunächst auf die schnellen,
  reinen Unit-Test-Module (Domänen-Kern).
- **Eine erste Zielmodul-Auswahl** mit dem höchsten Signal-zu-Laufzeit-Verhältnis (Vorschlag:
  `hexagon:core` zuerst — pure Logik, keine DB).
- **Opt-in-/Nightly-Integration** analog `perf-acceptance.yml` (eigener Workflow, **kein**
  PR-blockierendes Gate in der ersten Stufe).
- Eine **Mutations-Score-Schwelle** je Zielmodul (Bootstrap-Messung zuerst, dann pinnen —
  wie beim Perf-Kalibrier-Guard).

### 3.2 Nicht in Scope

- **Container-/integrationsabhängige Module** (`test:integration-*`, `e2e-cli`,
  `cross-dialect-matrix`) in der ersten Stufe — die Test-pro-Mutant-Laufzeit gegen echte DBs
  ist prohibitiv; eigene spätere Stufe.
- **Ersatz** des Kover-Coverage-Gates — Mutation-Testing ist ein **zusätzliches**,
  komplementäres Signal, kein Coverage-Ersatz.
- PR-blockierendes Hart-Gate von Anfang an (erst Sichtbarkeit/Bootstrap, dann ggf. Gate).

## 4. Offene Designentscheidungen

1. **Kotlin-Reibung von Standard-PIT (die Kernfrage).** PIT mutiert **Bytecode**; Kotlin
   erzeugt viel synthetischen Bytecode (Null-Checks, `when`-Exhaustiveness, data-class-
   generierte Methoden, Intrinsics), der „equivalente"/uninteressante Mutanten produziert →
   verrauschte Scores. Zu entscheiden: Standard-PIT mit kuratierten Mutatoren/Filtern gegen
   die **Kotlin-aware Arcmutate**-Erweiterung (kommerziell, lizenz-/kostenrelevant — analog
   zur semgrep-Lizenz-Abwägung). Diese Entscheidung trägt die Brauchbarkeit des Signals.
2. **Zielmodul-Reihenfolge.** `hexagon:core` zuerst (reine Logik, schnell, höchstes Signal)
   gegen breiteren Erststart. Container-Module bewusst später/separat.
3. **Schwelle + Gate-Modus.** Mutations-Score-Schwelle je Modul (Bootstrap → pinnen) und ob
   der Workflow nur misst (diagnostisch, wie der Perf-Nightly anfangs) oder ab Stufe 2
   blockiert.
4. **Laufzeit-/Budget-Rahmen.** Mutation-Testing läuft die Testsuite **einmal pro Mutant** →
   teuer. Opt-in/Nightly ist gesetzt; offen sind Mutator-Auswahl, Inkrementell-Modus (nur
   geänderte Klassen, `--changed-since`) und ein Zeit-Cap analog ADR 0018.
5. **Verhältnis zur Excludes-Ledger-Disziplin.** Ob Mutation-Excludes (equivalente Mutanten)
   demselben Ledger-Zwang unterliegen wie die Kover-Excludes (kein stiller Skip) — konsistent
   mit der bestehenden „No-Suppress"-Kultur.

## 5. Bezug

- Carve-Out-Tracker: [`../in-progress/carveout.md`](../in-progress/carveout.md), Abschnitt 7.
- Quelle (Folge-Thema): [`../done-archive/quality-coverage-expansion-plan.md`](../done-archive/quality-coverage-expansion-plan.md)
  (Abschnitt „Out-of-Scope / Folge-Themen").
- Coverage-Gegenstück (bestehend): [`build.gradle.kts`](../../../build.gradle.kts) (Kover) +
  [`.github/workflows/coverage-modules.yml`](../../../.github/workflows/coverage-modules.yml).
- Budget-/Kalibrier-Muster als Vorbild: [ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md)
  (Bootstrap → Referenz pinnen → diagnostisch/hart).
