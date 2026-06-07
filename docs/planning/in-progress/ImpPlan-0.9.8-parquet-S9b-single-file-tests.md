# S9b — Single-File-Test-Familien (SKELETON)

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](./parquet-productive-cut-a.md)
> §3 S9b).
>
> Status: **Skeleton — Pending S7/S8 completion**.
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
  CLI-Test, der den exakten Exit-Code (vermutlich 3) + die exakte
  stderr-Message verifiziert.
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
  - Echte Footer-KV-Files fuer den Phase-2-Hook, sodass
    `ParquetSingleFilePreflight.phase2`'s Resume-Hash-Verifikation
    gegen produktive Bytes laeuft (nicht nur gegen das
    rekonstruierte `ResolvedParquetSingleFile`).
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
> (Phase-1/2/Resume in `hexagon:application`, Resolver in
> `streaming`, KV-Toleranz in `formats-parquet`, CLI-Codes in
> `cli`).

Konkrete Belegbefehle, DoD-Cases und Test-Datei-Liste werden im
vollen Plan ergaenzt.

## 5. Vorbedingungen

- **S7** abgeschlossen (Stopgap entfaellt, Seekable-Dispatch
  produktiv, Footer-KV/Bundle-Manifest werden geschrieben). Sonst
  ist der „echte Parquet-Files"-Hand-off nicht implementierbar.
- **S8** abgeschlossen
  (`SingleFileCheckpointSpecifics(contentSha256)` persistiert, der
  Phase-2-Hook bekommt den echten Resume-Sha). Sonst sind die
  Resume-Familien-Tests rein synthetisch.

## 6. Naechste Schritte (bei Slice-Start)

1. Diese Skeleton-Datei zum vollen ImpPlan ausbauen (Sub-Slice-
   Schnitt, Test-Datei-Liste, DoD-Detail-Tabelle, Folgeaufgaben).
2. Plan-Review-Zyklus analog S6/S7 (mehrere Runden gegen
   Code-Realitaet).
3. Implementierungs-Sub-Slices unter `fix(review)`/`feat(parquet)`-
   Commit-Konvention.
4. Plan-Doc nach `docs/planning/done/` migrieren + Umbrella §3.4-
   Status-Tabelle aktualisieren.
