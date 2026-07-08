# S9b — Single-File-Test-Familien

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](parquet-productive-cut-a.md)
> §3 S9b).
>
> Status: **Closed (2026-06-09)** — direkt aus diesem Anker umgesetzt
> (analog S9a). Commits auf `develop`:
> - **S9b-0** `3306808e` — Single-File-Format-Codes → Exit 4
>   (`PARQUET_SINGLE_FILE_TABLE_MISMATCH`/`…TABLE_REQUIRED`; Hook-Übersetzung
>   via `PreflightExitException`, Infra aus S9a-0.a). `CONTENT_CHANGED`
>   bleibt Exit 3 (Resume-Familie/Manager, User-Entscheid).
> - **S9b.1** `591493f3` — CLI-Preflight-Codes end-to-end (Exit 4/2).
> - **S9b.3 + Resume-Fix** `0d40fd47` — Zwei-Phasen Single-File-Resume.
>   **Befund**: Single-File-Resume war produktiv gebrochen (Fresh-Run
>   persistierte den `contentSha256` nicht, weil `computeContentSha256`
>   nur bei `--resume` true war → jeder Resume fiel auf den Pre-AP8-Branch).
>   Fix: Hash auch beim Checkpoint-aktiven Fresh-Run (`--checkpoint-dir`)
>   berechnen. Danach Happy-Path (Exit 0) + `CONTENT_CHANGED` (Exit 3)
>   end-to-end.
> - **S9b.4** `038d735d` — DuckDB/Arrow-Single-File-KV-Toleranz (Kontrast
>   zu S9a.4: Single-File **hat** Footer-KV, Fremd-Reader tolerieren ihn).
> - **S9b.2** (Phase-1/2): bereits adapter-seitig durch
>   `ParquetSingleFileRoundTripTest` gedeckt (phase1/phase2, Table-Precedence,
>   Footer-Fallback, content-sha) + CLI-Ebene via S9b.1 — kein neues File.
>
> **Rest-Lücke (Folge-Scope):** nur per Config (`pipeline.checkpoint.directory`)
> gesetztes Checkpoint-Dir ist im Resolver nicht sichtbar → Fresh-Run
> berechnet dort den Hash nicht (Single-File-Resume nur mit `--checkpoint-dir`
> voll wirksam). Doc nach `done/` migriert.
>
> Diese Datei ist ein Anker fuer die Folgeaufgaben, die in S6 und
> S7 explizit an S9b uebergeben wurden. Der volle Implementierungs-
> plan (Sub-Slice-Schnitt, Code-Stellen, DoD-Detail, Test-Strategie)
> wird geschrieben, wenn S9b in Angriff genommen wird — analog dem
> Vorgehen bei S6 (ImpPlan-First → Review → Implementation).
>
> Diese Skeleton-Datei existiert ausschliesslich, damit die feinen
> Hand-off-Anker aus den vorherigen Slices nicht in deren
> Folgeaufgaben-Sektionen verloren gehen.

---

## 1. Scope (Umbrella-Referenz)

Per Umbrella §3 S9b-Cell
(`parquet-productive-cut-a.md:214`):

> Single-File-Tests: CLI-Preflight (Single-File-Codes),
> Phase-1/2-Tests, Single-File-Resume, DuckDB-/Arrow-Single-File-
> KV-Toleranz (AP12 §11).

Vier Test-Familien, die in den vier betroffenen Modulen (`:hexagon:application`,
`:adapters:driven:streaming`, `:adapters:driven:formats-parquet`,
`:adapters:driving:cli`) gruen werden muessen.

## 2. Hand-off-Anker aus vorherigen Slices

### Aus S6-ImpPlan §5 (`ImpPlan-0.9.8-parquet-S6-cli-wiring.md`)

- **CLI-Preflight-Codes** fuer Single-File: alle
  `PARQUET_SINGLE_FILE_*`-Fehlerklassen aus
  `ParquetSingleFilePreflight` (`ParquetSingleFileResumeException`,
  `ParquetSingleFileTableMismatchException`,
  `ParquetSingleFileTableRequiredException`) bekommen einen
  CLI-Test, der den exakten Exit-Code + die exakte stderr-Message
  verifiziert. **Exit-Code verifiziert = 3:** alle drei Klassen
  erweitern `RuntimeException` (nicht `IllegalArgumentException`),
  und der Runner mappt `IllegalArgumentException → Exit 2`, jeden
  anderen Wurf `→ Exit 3` (`ImportPreflightResolver.kt:88-93`,
  `DataImportRunner.kt:221`). `PARQUET_STDIN_NOT_SUPPORTED` (Single-
  File ueber Stdin) gehoert ebenfalls in diese Familie.
- **Format-Resolver-Hook-Edge-Cases**: `inferFormatFromExtension`
  fuer `.parquet`, `manifest.yaml`-Sniff-Pfad (Bundle vs.
  Non-Bundle vs. Mixed-Directory) — heute durch
  Batch-2-Review-Fixes abgedeckt (A3), aber nur als Helper-Test;
  S9b verbreitert das auf CLI-Ebene.
- **Phase-1/2-Fehlerklassen** im Runner-Kontext: das was heute
  in `DataImportRunnerCallbackTest` mit Fake-Hooks als „Phase-2-
  Hook IllegalArgumentException → Exit 2 / RuntimeException → Exit 3 /
  OperationCancelledException → CANCELLED_EXIT_CODE" abgedeckt ist,
  bekommt zusaetzlich produktive Parquet-Driver-Tests.

### Aus S7-ImpPlan §7 (`ImpPlan-0.9.8-parquet-S7-end-to-end.md`)

- **Phase-1/2-Tests gegen ECHTE Parquet-Files**: heute (Stand
  S6-Review-Batch 8) deckt `ParquetImportInputResolutionHookTest`
  nur die Routing-Logik der Hooks ab — Format-Early-Out,
  Stdin/Resolved\*-Pass-Through, null-Sha-Short-Circuit,
  Sha256-Mismatch-Throw. Was fehlt:
  - Echte Parquet-Files (via `ParquetChunkWriter` provisioniert)
    fuer den `Directory → ResolvedBundle`- und
    `SingleFile → ResolvedSingleFile`-Pfad des Phase-1-Hooks.
  - Echte Footer-KV-Files fuer den Phase-2-Hook, sodass die
    Phase-2-Routing-Logik (`ParquetSingleFilePreflight.phase2`)
    gegen produktive Bytes laeuft (nicht nur gegen das
    rekonstruierte `ResolvedParquetSingleFile`).
    **Achtung (S8d-Re-Cut, siehe §5):** Der produktive Resume-Hash-
    Vergleich passiert **nicht** im Hook. Der Hook ruft
    `phase2(resumeExpectedSha256 = null)` (Pass-Through); das echte
    Cross-Run-Gate ist `validateSingleFileResume` im
    `ImportCheckpointManager` (`ImportCheckpointManager.kt:192`).
    Die `phase2`-Hash-Branch
    (`PARQUET_SINGLE_FILE_CHECKPOINT_REQUIRES_HASH` /
    `…CONTENT_CHANGED_SINCE_CHECKPOINT`) ist im Produktionspfad tot
    und nur per **Unit-Test mit explizit gesetztem
    `resumeExpectedSha256`** erreichbar — dieser Test muss
    kommentieren, dass der Pfad produktiv nicht verdrahtet ist
    (sonst liest er sich faelschlich als Resume-E2E-Beleg). Der
    DoD-relevante Resume-Beleg laeuft ausschliesslich ueber die
    Manager-Gate-Familie (Single-File-Resume, §4).
  - End-to-End: `data import --format parquet --source x.parquet`
    ohne `--table` → Footer-KV-Inferenz liefert den Tabellennamen
    (Review-Finding A4) — der **kleine** Smoke landet schon im
    S7-E2E; S9b verbreitert das auf alle Edge-Cases (table-Mismatch,
    fehlendes Footer-KV → `AP11 §5.3-Fallback`-Pfad, Encoding-
    Override).

### Aus S7-Carve-out (Resume-E2E)

- **Volle Single-File-Resume-Familie** (Resume-Reference-
  Aufloesung, Manifest-Re-Hydration, KV-Toleranz, Content-Sha-
  Mismatch-Exit-Codes). S7 liefert nur den Dispatch-Skip-Smoke
  (Fake-Reader-`nextChunk()`-Zaehlung); S9b nimmt das auf S8-
  Specifics-Plumbing auf und macht es produktiv.

## 3. Bewusst NICHT in S9b

- **Keine Bundle-Tests** — gehoert zu S9a.
- **Kein Production-Code** — S9b ist eine reine Test-Slice; sollte
  die Implementierung ein Defizit aufdecken, faellt der Fix in
  einen Review-Batch oder Mini-Slice und nicht in S9b selbst.
- **Keine Cross-Driver-E2E** ueber das hinaus, was S7 bereits via
  PG/Testcontainers liefert (`DataParquetRoundTripE2EPostgresTest`).

## 4. Definition of Done (skeleton)

Umbrella-DoD ist die Quelle:

> Vier Test-Familien gruen via `make docker-test MODULES=":hexagon:application :adapters:driven:streaming :adapters:driven:formats-parquet :adapters:driving:cli"`
> (Phase-1/2 + Single-File-Resume in `hexagon:application`,
> KV-Toleranz in `formats-parquet`, CLI-Preflight-Codes in `cli`;
> `:adapters:driven:streaming` laeuft nur als Regressionsschutz
> mit — **kein** Single-File-eigener Resolver, da der
> `manifest.yaml`-Sniff Bundle-only ist (S9a) und
> `inferFormatFromExtension` in `hexagon:application` sitzt).

**Modul-Mapping-Korrektur (vs. urspruengliche Umbrella-Fassung):**
Die alte DoD listete „Resolver in `streaming`" als eigene Single-
File-Familie — das war ein Copy-Paste aus S9a. Single-File hat
**keinen** `manifest.yaml`-Sniff (das ist Bundle/S9a); die einzige
Single-File-Format-Inferenz ist `inferFormatFromExtension(.parquet)`
in `hexagon/application/.../DataImportHelpers.kt`. Die
`ImportInputResolver`-`SingleFile → ResolvedSingleFile`-when-Branch
in `:adapters:driven:streaming` ist bereits durch S5b abgedeckt;
ob S9b dort eine **eigene** Single-File-Resolver-Familie ergaenzt
oder `streaming` rein als Regressionsschutz mitlaufen laesst, ist
die offene Entscheidung in §7.1 — bis dahin behauptet die DoD
**keine** Single-File-Familie in `streaming`.

Konkrete Belegbefehle, DoD-Cases und Test-Datei-Liste werden im
vollen Plan ergaenzt.

## 5. Vorbedingungen

- ✅ **S7 abgeschlossen** (2026-06-08, siehe Umbrella §3.4):
  Stopgap entfaellt, Seekable-Dispatch produktiv, Footer-KV +
  Bundle-Manifest werden produktiv geschrieben.
- ✅ **S8 abgeschlossen** (2026-06-09, S8f-Closeout):
  `SingleFileCheckpointSpecifics(contentSha256)` persistiert +
  round-trippt (S8a), `validateSingleFileResume` ist der produktive
  Cross-Run-Resume-Gate (S8c). **Hinweis (S8d-Re-Cut):** Der
  Phase-2-Hook bekommt **nicht** den echten Resume-Sha — er bleibt
  Pass-Through (`resumeExpectedSha256 = null`); der Hash-Vergleich
  passiert im `ImportCheckpointManager` (S8c), nicht im Hook. Die
  Single-File-Resume-Familien-Tests pruefen daher den Manager-Gate,
  nicht einen Hook-Hash-Check.

## 6. Naechste Schritte (bei Slice-Start)

1. Diese Skeleton-Datei zum vollen ImpPlan ausbauen (Sub-Slice-
   Schnitt, Test-Datei-Liste, DoD-Detail-Tabelle, Folgeaufgaben).
2. Plan-Review-Zyklus analog S6/S7 (mehrere Runden gegen
   Code-Realitaet).
3. Implementierungs-Sub-Slices unter `fix(review)`/`feat(parquet)`-
   Commit-Konvention.
4. Plan-Doc nach `docs/planning/done/` migrieren + Umbrella §3.4-
   Status-Tabelle aktualisieren.

## 7. Reconciliation-Punkte fuer den Vollausbau

Bei der Skeleton-Review (2026-06-08) gegen die Code-Realitaet
aufgedeckt. Punkte 7.2/7.3 sind im Skeleton bereits korrigiert,
Punkt 7.1 ist eine offene Schnitt-Entscheidung fuer den vollen Plan.

### 7.1 — `:adapters:driven:streaming` hat (noch) keine eigene Single-File-Familie [OFFEN]

Die DoD nennt vier Familien, aber fuer Single-File mappt nur drei
Module sauber auf eine Familie (`hexagon:application`,
`formats-parquet`, `cli`). `:adapters:driven:streaming` traegt —
anders als bei S9a (`manifest.yaml`-Sniff) — keine Single-File-
eigene Resolver-Familie, weil es bei Single-File kein `manifest.yaml`
gibt. Die `ImportInputResolver`-`SingleFile`-when-Branch im
`streaming`-Modul ist schon durch S5b abgedeckt. **Entscheidung im
vollen Plan:** entweder (a) eine dedizierte Single-File-Resolver-
Familie in `streaming` ergaenzen (z.B. `Seekable`-Subtyp-Produktion
fuer `ResolvedSingleFile`, KV-Toleranz-Edge-Cases auf Resolver-
Ebene), oder (b) `streaming` explizit als reinen Regressionsschutz
deklarieren. Kein stiller Scheinbeleg — wenn (b), dann muss die
Test-Datei-Liste das so ausweisen.

### 7.2 — Phase-2-Resume-Hash: Gate liegt im Manager, nicht im Hook [KORRIGIERT]

Siehe §2 (S7-Anker) + §5. Der S7-Anker war vor dem S8d-Re-Cut
geschrieben und beschrieb eine `phase2`-Resume-Hash-Verifikation,
die produktiv nicht stattfindet (`resumeExpectedSha256 = null`).
Korrigiert: Resume-Beleg laeuft ueber `validateSingleFileResume`
im `ImportCheckpointManager`; die `phase2`-Hash-Branch ist nur per
Unit-Test mit non-null-Arg erreichbar und als nicht-produktiv zu
kommentieren.

### 7.3 — CLI-Preflight-Exit-Codes verifiziert = 3 [KORRIGIERT]

Siehe §2. Das urspruengliche „(vermutlich 3)" ist gegen den Code
bestaetigt: `ParquetSingleFile{Resume,TableMismatch,TableRequired}Exception`
erweitern `RuntimeException` → Runner-Mapping ergibt Exit 3.
