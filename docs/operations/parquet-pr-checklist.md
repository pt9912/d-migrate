# Parquet Cut-A (0.9.8) — PR-Checkliste

Pflicht-Checks vor jedem Merge auf `feature/parquet-0.9.8`
(Umbrella
[`parquet-productive-cut-a.md`](../planning/done/parquet-productive-cut-a.md)
§5 PI-2).

## Sealed-`when`-Sweep

```bash
make parquet-sweep
```

Faehrt die acht `rg`-Patterns aus AP13 §4.1
([`parquet-decision-template.md`](../planning/done/parquet-decision-template.md)
Zeilen 227-237) pro Sealed-Hierarchie und druckt jeden
Treffer-Block:

- `ImportInput` — direct `is`-Checks + `when`-Statements
  (neu in S5a/S5b: `ResolvedBundle`, `ResolvedSingleFile`).
- `SchemaOrigin` — direct `is`-Checks + `when`-Statements
  (neu via AP9: `MANIFEST_FALLBACK`).
- `SeekableChunkSource` — direct `is`-Checks (neu in S2,
  AP10 §3.2).
- `CheckpointOperationSpecifics` — direct `is`-Checks
  (Bundle + SingleFile aus S8).
- `DataExportFormat` — direct `is`-Checks +
  `when`-Statements (`PARQUET` neu in S3).

Pro Treffer pruefen:

- [ ] Treffer ist exhaustive (kein `else`-Zweig) **oder**
- [ ] Treffer hat einen begruendet `else`-belassenen Zweig
  (Begruendung im Code-Kommentar oder im PR-Beschreibung).

Reflection-Pfade (`when (val v = obj::class)`), Service-
Loader (`META-INF/services/...`) und non-exhaustive
`when`-Statements ohne Rueckgabe-Verwendung sind vom
`gradle assemble`-Gate nicht abgedeckt — der Sweep ist die
einzige Sicherheit.

## Build- und Test-Gates

- [ ] `make docker-check` (Repo-weit) gruen — Compile +
  Detekt + Kover-Gate.
- [ ] `make docker-test MODULES=":<slice-modul-liste>"`
  gruen — Modul-Tests des aktuellen Slice (siehe Slice-DoD
  im Umbrella §3).
- [ ] Bei Slice-Eskalation auf `ultra`-Risk (S6/S7/S8/S9a/
  S9b): `/code-review ultra` Lauf gegen den PR.

## Plan-Doc-Spur

- [ ] Slice-Closure-Plan-Doc
  `docs/planning/done/ImpPlan-0.9.8-parquet-S<N>-…md`
  liegt vor und ist im selben Commit gepushed wie der
  Slice-Code (Umbrella §5.1 Engineering Goal).
- [ ] Befund-Rueckspiel in `docs/planning/done/parquet-…`-
  Sub-Docs, falls der Slice von der Plan-Annahme abweicht
  (analog AP10-Befund-Rueckspiel `f89e2920`).

## Slice-spezifisch

- **S10a**: `dependencyInsight --dependency
  org.apache.avro:avro --configuration runtimeClasspath`
  + `dependencies --configuration runtimeClasspath`-
  Snapshots im Closure-Doc; Pfad A oder Pfad B explizit
  begruendet (Umbrella §4.1).
- **S3**: `grep "parquet\|hadoop"
  adapters/driven/formats/build.gradle.kts` liefert keinen
  Treffer (Hadoop-/Parquet-frei bleibt erhalten, Umbrella
  S3-DoD).
- **S6**: Phase-2-Hook im `DataImportRunner` mit Single-
  File-Fall verifiziert; `data export --format parquet`
  und `data import --format parquet` Pfad-only.
- **S7**: `make integration INTEGRATION_TASKS="-PintegrationTests
  :test:e2e-cli:test"` gruen (das `-PintegrationTests`-
  Property ist Pflicht, sonst skipped).
