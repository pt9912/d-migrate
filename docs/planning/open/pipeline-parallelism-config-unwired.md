# `pipeline.parallelism` Config-Key ist unverdrahtet (stiller No-op)

**Status**: Trigger (2026-07-13) — Nebenbefund aus dem Streaming-OOM-Slice
([`../done/ln005-streaming-oom-hardening.md`](../done/ln005-streaming-oom-hardening.md),
[`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005)).

**Befund**: `pipeline.parallelism: auto` ist in der Connection-Spec dokumentiert
([`connection-config-spec.md`](../../../spec/connection-config-spec.md), Abschnitt „Pipeline-Einstellungen"),
wird vom Runtime aber **nicht gelesen** — exakt das Muster, das für `pipeline.chunk_size` in jenem Slice
behoben wurde. Ein Nutzer, der `pipeline.parallelism` in `.d-migrate.yaml` setzt, bekommt keinen Effekt;
wirksam ist nur das CLI-Flag `--parallel` (→ `ParallelismClamp` → `PipelineConfig.parallelism`).

**Evidenz** (Code-Trace vollständig, review-verifiziert 2026-07-13): Kein Resolver liest den YAML-Key
`parallelism` — `PipelineCheckpointResolver` liest nur `pipeline.checkpoint.*`, der neue
`PipelineTuningResolver` liest `pipeline.chunk_size`/`pipeline.fetch_size`. Es gibt kein
`"parallelism"`-String-Literal im Config-Lesepfad. `PipelineConfig.parallelism` hat genau **einen**
Zufluss, das CLI-Flag: Import via `ImportPreflightValidator`, Export via `DataExportRunner`, Transfer
via `DataTransferRunner` — jeweils `request.parallel` → `ParallelismClamp` → `PipelineConfig`; die
Wirings (`DataImportWiring`/`DataExportWiring`) lesen nur diese fertige `PipelineConfig` zurück.

**Warum nicht im selben Slice mitbehoben**: `parallelism` gehört zum parallelen Datenpfad
([`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007)/[`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008)),
nicht zur Streaming-OOM-Härtung. Es ist außerdem **nicht trivial**: die Spec verspricht eine
`auto`-Semantik (= CPU-Kerne), die bislang **nirgends im Code existiert** (auch das CLI-Flag akzeptiert
kein `auto`); dazu kommen die SQLite-Klemmung auf 1 und ein anderer Pfad (`ParallelismClamp`) als bei
`chunkSize`/`fetchSize`. Ein „mitverdrahten wie chunk_size" wäre ein eigener, sauber zu schneidender Slice.

**Scope-Skizze (bei Aufnahme)**:
- `PipelineTuningResolver` (oder ein `PipelineParallelismResolver`) um `pipeline.parallelism` erweitern:
  Integer **oder** `auto` (→ CPU-Kerne); positive Validierung.
- Präzedenz **CLI-explizit (`--parallel`) > Config > Default** über den `ParallelismClamp`-Pfad, analog
  zum `resolveEffectivePipelineTuning` des chunk_size/fetch_size-Wirings. Dafür muss `--parallel`
  **nullbar** werden (heute `.int().default(1)` in allen drei Commands `data export`/`data import`/
  `data transfer`) — mit festem Flag-Default ist „CLI-explizit" nicht von „Default" unterscheidbar;
  der Default `1` wandert in den Merge, analog `--chunk-size`. Betroffen sind auch die
  `request.parallel`-Validierungen in den Runnern.
- **Design-Entscheidung `--resume`**: heute bricht `--parallel > 1` + `--resume` hart ab
  (`DataExportRunner`, „all-or-nothing"). Käme `parallelism > 1`/`auto` künftig aus der Config, schlüge
  jede `--resume`-Nutzung fehl, ohne dass auf der CLI etwas Widersprüchliches steht. Kandidat: bei
  Config-Herkunft mit Hinweis auf 1 zurückfallen (analog SQLite-Klemmung), harter Fehler nur bei
  CLI-explizitem `--parallel`.
- Spec-Beispielwert überdenken: das Voll-Schema in `connection-config-spec.md` zeigt
  `parallelism: auto` — wer das Beispiel kopiert, bekäme nach dem Fix unbeabsichtigt
  CPU-Kern-Parallelität (und liefe in die `--resume`-Entscheidung oben). Beispielwert konservativ
  setzen oder Kommentar um Default `1` ergänzen.
- CLI-Symmetrie klären: bleibt `auto` config-only, oder soll auch `--parallel auto` gehen?
- Spec-Notiz in `connection-config-spec.md` (Präzedenz), CHANGELOG (Fixed: stiller No-op).
