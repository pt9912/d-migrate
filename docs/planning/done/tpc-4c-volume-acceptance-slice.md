# Slice: TPC Sub-Slice 4c — Volumen-Abnahme (gemessen, LF 8.1 + 8.2)

> Dokumenttyp: in-progress-Slice — **Plan-Entwurf, Review offen** (graduiert aus dem
> Umbrella [`../done/tpc-performance-slice.md`](../done/tpc-performance-slice.md),
> Sub-Slice 4c). Baut auf [4a](../done/tpc-4a-sourcing-slice.md) (Generator) +
> [4b](../done/tpc-4b-roundtrip-slice.md) (Korrektheit).
> ADR: [0018](../../adr/0018-normalized-perf-measurement-environment.md) (normierte
> Mess-Umgebung) · [0017](../../adr/0017-tpc-benchmark-workload-sourcing.md).
> **Status: Teil 1 (Mess-Kern) + Teil 2 (Kalibrier-Guard-Mechanik) gebaut + live
> verifiziert** (`make sample-db-tpch-perf`, SF=0.2 → 1,73 Mio: Verlustfreiheit + Resume
> hart, Durchsatz kalibrier-guarded, alles unter Caps 2 CPU/4 GB; scharf-gestellter
> in-band-Lauf grün). **Bleibt in `in-progress/`** wegen des **operativen Rests** (kein
> Code): einen Nightly-Runner designieren + `CALIB_REFERENCE_MS` darauf pinnen, dann ist
> das absolute Zeit-Gate live.

## Ziel + LF-Kriterien

Die **gemessene** Volumen-Abnahme über 4b hinaus:
- **LF 8.1 / 8.5 — Verlustfreiheit** ≥ 1 Mio Datensätze Export+Re-Import, verifiziert per
  **SHA-256-Inhalts-Vergleich** (strenger als Phase-3-Zeilen-Parität + Einzel-Summe).
- **LF 8.2 — Zeit/Durchsatz:** Export 1 Mio < 100 s, Import 1 Mio < 200 s.
- **LF 8.2 — Resume:** erfolgreicher Wiederanlauf nach Abbruch **bei ~50 %**.

## Spike-Befunde (2026-06-23, live, grundieren den Plan)

1. **`data export` ist deterministisch bei statischer Quelle** — zweimaliger Export von
   `tpch_pg_src.lineitem` ist byte-identisch (SHA `601a11d5…`).
2. **Aber der Round-Trip ist NICHT byte-stabil:** der Ziel-Export (`tpch_pg_target` nach
   4b-Transfer) weicht ab (SHA `31bbaccf…`) — die **Spaltenreihenfolge ist nach
   reverse→generate vertauscht** (Quelle `l_orderkey,l_partkey,…`; Ziel `l_comment,…`).
   → **Die literale „Byte-für-Byte-Hash der Exportdatei"-Methode (so im Umbrella-Plan
   formuliert) würde einen KORREKTEN Transfer fälschlich als verlustig melden (False-FAIL).**
3. **Nebenbefund (eigenes Ticket-Kandidat):** Spaltenreihenfolge überlebt reverse→generate
   nicht. Für relationale Korrektheit **kosmetisch** (Werte/Spalten unverändert, nur
   Ordinalposition), aber relevant für jede byte-basierte Prüfung. Separat zu bewerten,
   **nicht** 4c-blockierend.

## Festgenagelte Design-Entscheidungen

- **Pfad: `data export` → `data import`** (nicht `data transfer`). Begründung: nur der
  datei-basierte Pfad hat `--resume` (live aus `--help` bestätigt) **und** trennt Export-
  und Import-Zeit (LF 8.2 nennt beide separat). `data transfer` hat nur `--chunk-size`.
- **Verlustfreiheit (LF 8.5) = kanonischer Inhalts-SHA-256, NICHT roher Datei-Hash**
  (Spike-Befund 2). Pro Tabelle ein **spalten-namens-geordneter + zeilen-sortierter**
  Hash (psql-seitig: `\copy (SELECT <cols ORDER BY name> FROM t ORDER BY 1..n) TO STDOUT`
  | `sha256sum`), Quelle vs. Ziel. Invariant gegen physische Spalten-/Zeilen-Reihenfolge,
  sensibel für **jede** Zellabweichung. **Live validiert:** Quelle `tpch.lineitem` und das
  spalten-physisch vertauschte `tpch_target.lineitem` ergeben denselben kanonischen Hash
  `b0913f4c…`; der rohe Datei-Hash wich ab. Das ist die faithful-Realisierung der
  LF-8.5-Absicht; die literale Datei-Byte-Lesart ist selbst bei korrektem Transfer
  unmöglich. **Umbrella-4c korrigiert (R4);** ADR 0017 nennt „SHA-256 Quelle↔Ziel"
  (Inhalts-, nicht Datei-Byte-Vergleich) → konsistent, keine ADR-Änderung nötig.
- **Budgets als Durchsatz (skaleninvariant):** Export ≥ **10 000 Sätze/s** (= [`LN-002`](../../../spec/lastenheft-d-migrate.md#ln-002) →
  1 Mio/100 s), Import ≥ **5 000 Sätze/s** (= [`LN-003`](../../../spec/lastenheft-d-migrate.md#ln-003) → 1 Mio/200 s). Entkoppelt die
  Abnahme von der exakten SF und bindet sie an die nummerierten LN-Anforderungen.
- **Scale:** SF konfigurierbar; **Default ≥ 1 Mio Zeilen** (SF=0.2 → `lineitem` ~1,2 Mio).
  Der „echte" Nightly-Lauf kann SF=1 (~6 Mio `lineitem`, ~1 GB) fahren.
- **Resume bei ~50 %:** Export mit `--split-files --chunk-size`, Mid-Stream-Abbruch
  (`docker kill`) **nahe der Hälfte** der Chunks (Phase 3 bricht beim ERSTEN Checkpoint
  ab = < 50 %; 4c muss gezielter bei ~50 % treffen), dann `--resume <operationId>`.
- **Container-Caps:** Mess-Lauf unter `--cpus=2 --memory=4g` (ADR 0018).

## Offene Entscheidungen / Risiken (Review-Input erbeten)

1. **Kalibrier-Substrat-Lücke (architektonisch).** ADR 0018 definiert die Kalibrier-Op als
   `diff-planner`-**JVM-Hotpath** (`PerfMeasure`/Kotest). 4c misst aber **CLI/Bash**
   Export/Import — die teilen kein Mess-Substrat. **Vorschlag:** einen **CLI-Kalibrier-
   Proxy** im d-migrate-Image (z. B. `schema generate` auf einem synthetischen
   100-Tabellen-Schema, getimt) als Host-Speed-Referenz nutzen, statt den JVM-Hotpath.
   → braucht eine **ADR 0018-Ergänzung** („Kalibrier-Op je Mess-Substrat: JVM-Tier =
   diff-planner-Hotpath; CLI-Tier = generate-Proxy"). **Entscheidung: Proxy bauen +
   ADR 0018 ergänzen?**
2. **Referenz-Median-Henne-Ei.** Der Kalibrier-Guard braucht einen auf dem designierten
   Runner gepinnten Referenzwert (ADR 0018). Den gibt es noch nicht. **Vorschlag:** der
   erste Nightly-Lauf erfasst + pinnt ihn (Bootstrap), bis dahin läuft 4c **diagnostisch**.
3. **Diagnostisch vs. hart in dieser Sandbox.** Absolute Zeit-Budgets sind hier **nicht**
   abnehmbar (Off-Spec-Host → Kalibrier-Guard → diagnostisch). Hier verifizierbar:
   Mechanik (getimter Export/Import, kanonischer SHA-256, Resume@50%, Caps, Kalibrier-
   Messung). Hart erst auf `perf-acceptance.yml`.

## Scoping: was hier verifizierbar ist vs. nur authored

| Baustein | Hier (Sandbox) | Designierter Runner |
|---|---|---|
| Getimter Export/Import + Durchsatz | ✅ diagnostisch gemessen | hart asserriert (PERF_GATE) |
| Kanonischer SHA-256 (Verlustfreiheit) | ✅ hart (host-unabhängig) | ✅ hart |
| Resume @ ~50 % | ✅ hart | ✅ hart |
| Container-Caps 2CPU/4GB | ✅ angewandt | ✅ angewandt |
| Kalibrier-Guard (Proxy) | ✅ Mechanik, Referenz lokal | ✅ Referenz gepinnt |
| `perf-acceptance.yml`-Nightly | authored (nicht lauffähig hier) | ✅ trägt das harte Gate |

## Build-Schritte (nach Plan-Freigabe)

1. (falls 1 bestätigt) ADR 0018 um Kalibrier-Substrat-Tier ergänzen.
2. `smoke-tpch-perf.sh`: generate (≥1 Mio) → load → **getimter** `data export` (Durchsatz)
   → **getimter** `data import` in frische Ziel-DB → kanonischer SHA-256 Quelle==Ziel →
   Resume@50%-Abschnitt → Kalibrier-Proxy + Host-Ratio → diagnostisch/hart je `PERF_GATE`.
3. Caps-Wrapper (`--cpus=2 --memory=4g`) für den Mess-Container.
4. `make sample-db-tpch-perf` (opt-in) + `.github/workflows/perf-acceptance.yml` (Nightly).
5. Doku-Sync `performance-benchmarks.md` (Acceptance-Tier + Referenz-Umgebung) +
   Umbrella-4c + `expected/tpch.md`.

## Definition of Done (Modul 5)

**Teil 1 (Mess-Kern) — live verifiziert `make sample-db-tpch-perf` (SF=0.2, 1,73 Mio):**
- [x] Verlustfreiheit ≥ 1 Mio per **kanonischem SHA-256** Quelle==Ziel (hart, host-unabh.;
      alle 8 Tabellen identisch).
- [x] Export/Import getrennt getimt unter Caps; Durchsatz vs. [`LN-002`](../../../spec/lastenheft-d-migrate.md#ln-002)/003 (hier
      diagnostisch: Export ~216k/s, Import ~78k/s, beide ≫ Budget; hart nur PERF_GATE+Runner).
- [x] Resume nach **Mid-Stream-Abbruch** + `--resume` → vollständiger, verlustfreier
      Export (Abbruchpunkt host-abhängig ~70 %, Band [25,90] belegt mid-stream; ehrlich
      berichtet statt „~50 %" behauptet).
- [x] Mess-Lauf unter Caps 2CPU/4GB (`dmigrate-capped`, cgroup-verifiziert 2.0 CPU/4 GiB).
- [x] `perf-acceptance.yml`-Nightly authored (diagnostisch); `make sample-db-tpch-perf`.
- [x] Opt-in, **nicht** im PR-Gate; `make docs-check` grün; `expected/tpch.md` gepinnt.

**Teil 2 (Kalibrier-Guard-Mechanik) — gebaut + verifiziert:**
- [x] Kalibrier-Guard: diff-planner-CLI-Op (`schema generate` auf `calib-schema.yaml`,
      5× Median unter Caps, ±25 %-Band, Off-Spec → diagnostisch). Logik (Bootstrap/
      in-band/off-spec) + scharf-gestellte Integration (in-band armed, drift 3 %) live grün.
- [x] **JVM↔CLI-Substrat-Lücke geschlossen** ohne fremden Proxy: dieselbe diff-planner-
      Op, nur CLI-invokiert → ADR 0018 entsprechend ergänzt.
- [x] `perf-acceptance.yml` auf `PERF_GATE=true` (guard-abgesichert: ohne Referenz →
      Bootstrap → diagnostisch; kein False-Fail).
- [x] `performance-benchmarks.md` auf den gebauten Mess-Kern + Guard nachgezogen.

**Operativer Rest (kein Code mehr — reine Repo-Variablen, braucht einen stabilen Runner):**
- [ ] Stabilen Runner designieren (Repo-Variable `PERF_RUNNER`) + `CALIB_REFERENCE_MS` aus
      einem Bootstrap-Lauf darauf pinnen → absolutes Zeit-Gate live. Schritt-für-Schritt im
      Runbook unten. Verlustfreiheit + Resume sind ohnehin host-unabhängig hart.

## Nicht-Ziele

- Cross-Dialect-Volumen (PG→MySQL/SQLite) + die weiteren LF-8.2-Skalierungskriterien
  (10 Mio OOM, 5×-Parallel-Speedup, inkrementell 1000 Tab. < 1 h) — eigene Slices.
- Der DDL-1000-< 30 s-Teil = **4d** (synthetisch, nicht TPC).
- Spaltenreihenfolge-Fidelity (Nebenbefund 3) — separate Bewertung.

## Runbook: Hart-Gate scharf stellen (Operator)

Die Mechanik ist vollständig gebaut; das absolute Durchsatz-Gate (LF 8.2) live zu schalten
ist **reine Ops** — zwei Repo-Variablen, **kein Code-Edit**. Verlustfreiheit (kanonischer
SHA-256) und Resume laufen ohnehin host-unabhängig **hart**, unabhängig von diesen Schritten.

1. **Stabilen Runner designieren.** Repo-Variable `PERF_RUNNER` auf das Label eines Runners
   mit **stabiler Hardware** setzen (self-hosted oder dediziert):
   `gh variable set PERF_RUNNER --body "<runner-label>"`.
   Der Default `ubuntu-latest` ist *variable* Hardware → der Kalibrier-Guard hält das Gate
   dort by-design diagnostisch (kein verlässlicher Zeit-Bezug). Für Mehr-Label-Runner ein
   eindeutiges Einzel-Label vergeben oder `runs-on` direkt setzen.
2. **Bootstrap-Lauf** auf diesem Runner: `gh workflow run perf-acceptance.yml` (oder den
   Nightly abwarten). Solange `CALIB_REFERENCE_MS` leer ist, läuft die Kalibrierung im
   Bootstrap und das Log meldet die Zeile
   `calibration BOOTSTRAP: median=<N> ms — … pin CALIB_REFERENCE_MS=<N> (under these caps) …`.
   `<N>` ablesen.
3. **Referenz pinnen:** `gh variable set CALIB_REFERENCE_MS --body "<N>"`.
   Ab dem nächsten Lauf: Kalibrier-Median im ±25 %-Band → Host in-band → mit `PERF_GATE=true`
   greift das **Hart-Gate** (Export <100 s / Import <200 s je 1 Mio, ADR 0018). Off-Spec
   (Drift > 25 %, z. B. Runner ausgetauscht) → automatischer Rückfall auf diagnostisch
   (kein False-Fail). Nach einem Hardware-Wechsel Schritt 2–3 wiederholen (neu kalibrieren).

Damit ist der „operative Rest" auf zwei `gh variable set`-Aufrufe rund um **einen**
Bootstrap-Lauf reduziert — danach ist tpc-4c graduierungsreif.

## Closure (2026-06-25)

**Mess-Kern (Teil 1) + Kalibrier-Guard (Teil 2) gebaut + live-verifiziert → graduiert nach `done/`.**

- **Verlustfreiheit** (LF 8.1/8.5, kanonischer SHA-256 Quelle==Re-Import) und **Resume** nach
  Mid-Stream-Abbruch laufen **host-unabhängig hart** (`make sample-db-tpch-perf`, SF=0.2 → 1,73 Mio
  Zeilen, unter Caps 2 CPU/4 GB).
- **Durchsatz (LF 8.2)** ist kalibrier-guarded: auf dem variablen `ubuntu-latest`-CI-Runner
  **diagnostisch** (per Design, kein False-Fail). Das **absolute Zeit-Gate** wird hart, sobald ein
  stabiler Runner designiert ist — beides reine Repo-Variablen (`PERF_RUNNER`, `CALIB_REFERENCE_MS`),
  Schritt-für-Schritt im Runbook oben.

**Bewusste Entscheidung (Option C, 2026-06-25):** `ubuntu-latest` bleibt diagnostisch; das Hart-Gate-
Arming ist ein reiner Ops-Schritt (kein Code) und als Provisional-Carve-Out mit Trigger verfolgt
([`carveout.md`](../in-progress/carveout.md), Sektion „TPC-Performance-Abnahme"). Begründung: für ein
Public-/Solo-Projekt ohne bestehende Self-hosted-Infra ist der ROI eines dedizierten Runners gering,
solange der Durchsatz nightly **sichtbar** gemessen wird (Regressionen fallen auf, nur ohne Gate-Fail).

Separater operativer Follow-up (Ergebnisse als CI-Artefakt):
[`../done/tpch-perf-result-artifact.md`](../done/tpch-perf-result-artifact.md).
