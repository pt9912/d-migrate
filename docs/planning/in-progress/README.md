# Laufende Arbeit

Aktive Roadmap- und Slice-Pläne mit gestartetem Implementierungs-Pfad.
Drei Typen leben hier:

1. **Top-Level-Aggregatoren** mit sprechenden Namen (dauerhaft
   aktiv, wandern nicht):
   - [`roadmap.md`](roadmap.md) — Gesamt-Milestone-Sicht.
   - [`carveout.md`](carveout.md) — Living Tracker für bewusste
     Scope-Cut-Entscheidungen (Permanent / Provisional / Promoted /
     Resolved) mit Verweis aufs Quelldokument.
2. **Per-Feature-Umbrella-Pläne**, die einen mehrphasigen Workstream
   tragen, bei dem mindestens ein Commit den Plan referenziert.
3. **Aktive Per-Slice-ImpPlans und Skeletons**, die einen laufenden
   Slice konkretisieren oder Hand-off-Anker aus aktiven Slices
   festhalten. Sie bleiben hier bis zur Slice-Closure; Skeletons sind
   erlaubt, wenn sie explizit als `Pending <Voraussetzung>` markiert
   sind.

Lebenszyklus und Verzeichnisstruktur sind in
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md)
festgehalten.

## Konvention für Einträge

- Status-Header pflegen: `> Status: In Progress (<refresh-datum>)`
  plus pro abgeschlossene Phase einen Commit-Ref-Hinweis.
- Per-Slice-ImpPlans wandern beim Abschluss eines Slice nach
  `../done/ImpPlan-<version>-<slice>.md`. Die Umbrella selbst bleibt
  in `in-progress/`, bis alle Phasen geliefert sind — dann wandert
  sie als Ganzes nach `../done/` mit einer `## Closure`-Sektion am
  Ende, die den finalen Stand zusammenfasst.
- Skeletons tragen im Status klar `Skeleton`/`Pending` plus die
  blockierende Voraussetzung und werden beim Start des Slice zu einem
  vollstaendigen ImpPlan erweitert.
- `roadmap.md` und `carveout.md` sind die dauerhaften Top-Level-
  Aggregatoren und wandern nicht. Versions-spezifische Workstream-
  Aggregatoren (z. B.
  der 0.9.7-Aggregator
  [`diffresult-migration-plan-2.md`](../done/diffresult-migration-plan-2.md),
  geschlossen 2026-06-02) wandern beim Milestone-Abschluss nach
  `../done/` und tragen dort eine `## Closure`-Sektion.

## Wann **nicht** hierher

- Scope steht, aber kein Implementierungs-Commit existiert und es gibt
  keinen aktiven Hand-off-Anker aus einem laufenden Slice → `../next/`.
- Trigger ohne Scope → `../open/`.
- Alle Phasen geliefert (für Umbrella-Pläne) → `../done/`.
- Einzelne Sub-Slice-Closure-Notiz → direkt nach
  `../done/ImpPlan-<version>-<slice>.md`, der Umbrella bleibt
  hier.

## Bestand

| Datei | Typ | Gegenstand |
| ----- | --- | ---------- |
| [`roadmap.md`](roadmap.md) | Top-Level-Aggregator | Gesamt-Milestone-Sicht. |
| [`carveout.md`](carveout.md) | Top-Level-Aggregator | Living Tracker fuer bewusste Scope-Cut-Entscheidungen mit Verweis aufs Quelldokument. || [`ImpPlan-0.9.8-parquet-S7-end-to-end.md`](../done/ImpPlan-0.9.8-parquet-S7-end-to-end.md) | Per-Slice-ImpPlan | S7 End-to-End-Integration fuer den Seekable-Pfad: Stopgap-Removal, `SeekableDataChunkReaderFactory`-Dispatch, Footer-KV-/Bundle-Manifest-Wiring und PG/Testcontainers-Parquet-Roundtrip. |
| [`ImpPlan-0.9.8-parquet-S9a-bundle-tests.md`](../done/ImpPlan-0.9.8-parquet-S9a-bundle-tests.md) | Skeleton | S9a Bundle-Test-Familien; startbereit nach S7/S8, sammelt Hand-off-Anker fuer CLI-Preflight-Codes, Format-Resolver, Bundle-Resume und DuckDB-/Arrow-Bundle-KV-Toleranz. |
| [`ImpPlan-0.9.8-parquet-S9b-single-file-tests.md`](../done/ImpPlan-0.9.8-parquet-S9b-single-file-tests.md) | Skeleton | S9b Single-File-Test-Familien; startbereit nach S7/S8, sammelt Hand-off-Anker fuer CLI-Preflight-Codes, echte Parquet-Phase-1/2-Tests, Single-File-Resume und KV-Toleranz. |
| [`ImpPlan-0.9.8-object-storage-s3.md`](../done/ImpPlan-0.9.8-object-storage-s3.md) | ImpPlan | Object-Storage S3-Adapter (`adapters:driven:storage-s3`): **abgeschlossen 2026-06-12** (S3.0-S3.6: Gate GO, beide Stores + Vertragssuiten, `artifacts`-Config + MCP-Wiring, Subprocess-E2E, Footprint +8,02 MiB). |