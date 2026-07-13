# `pipeline.parallelism` Config-Key ist unverdrahtet (stiller No-op)

**Status**: Trigger (2026-07-13) — Nebenbefund aus dem Streaming-OOM-Slice
([`../done/ln005-streaming-oom-hardening.md`](../done/ln005-streaming-oom-hardening.md),
[`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005)).

**Befund**: `pipeline.parallelism: auto` ist in der Connection-Spec dokumentiert
([`connection-config-spec.md`](../../../spec/connection-config-spec.md), Abschnitt „Pipeline-Einstellungen"),
wird vom Runtime aber **nicht gelesen** — exakt das Muster, das für `pipeline.chunk_size` in jenem Slice
behoben wurde. Ein Nutzer, der `pipeline.parallelism` in `.d-migrate.yaml` setzt, bekommt keinen Effekt;
wirksam ist nur das CLI-Flag `--parallel` (→ `ParallelismClamp` → `PipelineConfig.parallelism`).

**Evidenz**: Kein Resolver liest den YAML-Key `parallelism` — `PipelineCheckpointResolver` liest nur
`pipeline.checkpoint.*`, der neue `PipelineTuningResolver` liest `pipeline.chunk_size`/`pipeline.fetch_size`.
Es gibt kein `"parallelism"`-String-Literal im Config-Lesepfad.

**Warum nicht im selben Slice mitbehoben**: `parallelism` gehört zum parallelen Datenpfad
([`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007)/[`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008)),
nicht zur Streaming-OOM-Härtung. Es ist außerdem **nicht trivial**: der Wert hat `auto`-Semantik
(= CPU-Kerne), eine SQLite-Klemmung auf 1 und speist einen anderen Pfad (`ParallelismClamp`) als
`chunkSize`/`fetchSize`. Ein „mitverdrahten wie chunk_size" wäre ein eigener, sauber zu schneidender Slice.

**Scope-Skizze (bei Aufnahme)**:
- `PipelineTuningResolver` (oder ein `PipelineParallelismResolver`) um `pipeline.parallelism` erweitern:
  Integer **oder** `auto` (→ CPU-Kerne); positive Validierung.
- Präzedenz **CLI-explizit (`--parallel`) > Config > Default** über den `ParallelismClamp`-Pfad, analog
  zum `resolveEffectivePipelineTuning` des chunk_size/fetch_size-Wirings.
- Spec-Notiz in `connection-config-spec.md` (Präzedenz), CHANGELOG (Fixed: stiller No-op).

**Offene Frage**: Wurde `opts.config.parallelism` (z. B. `DataImportWiring`) vollständig getraced? Der
Befund stützt sich auf „kein Resolver liest den Key" — die genaue `opts.config`-Herkunft ist noch zu
bestätigen, bevor der Fix geschnitten wird.
