# `pipeline.parallelism` Config-Key ist unverdrahtet (stiller No-op)

**Status**: Trigger (2026-07-13) — Nebenbefund aus dem Streaming-OOM-Slice
([`ln005-streaming-oom-hardening.md`](ln005-streaming-oom-hardening.md),
[`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005)). **ERLEDIGT (2026-07-13)** — Phase A Resolver
(`19f7ccbd`), Phase B+C Commands/Runner-Wiring + origin-bewusster Fallback (`17c83c35`); Ansatz A
code-verifiziert umgesetzt. Nebenbefund `pool:` verifiziert → eigenes Ticket
([`pool-config-section-unwired.md`](pool-config-section-unwired.md)). Graduiert nach `done/`.

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

## Entscheidungen (Review 2026-07-13)

- **`auto`-Auflösung (am Resolver-Level, „Ansatz A")**: `auto` = `min(Runtime.availableProcessors(),
  PoolSettings.maximumPoolSize)`, danach wie jede Zahl durch den `ParallelismClamp` (SQLite → 1). Die
  Pool-Deckelung ist **neue** Logik — der bestehende Clamp kennt nur die SQLite-Klemme und den Floor
  auf 1; das „keep <= pool size" im `--parallel`-Hilfetext ist unerzwungene Empfehlung. Die effektive
  Pool-Größe wird über `PoolSettings.maximumPoolSize` referenziert, **nicht** `10` hartkodiert.
  Begründung: ohne Deckel hieße `auto` auf einer 32-Kern-Maschine 32 Worker gegen einen Pool von 10 —
  überzählige Worker blockieren in `getConnection` und laufen bei langen Streaming-Reads in den
  10-s-`connectionTimeoutMs`-Default → echte Chunk-Fehler.
  - **Code-verifiziert (2026-07-13):** Der Datenpfad baut Pools **ausschließlich** über
    `HikariConnectionPoolFactory.create(config)` mit `ConnectionConfig.pool = PoolSettings()` (Default) —
    **kein** Data-Command übersteuert `pool` (`ConnectionConfig.kt:24` ist die einzige Main-Source-
    Konstruktion; nur der MCP-Serve-Pfad setzt eine eigene Größe, hier irrelevant). `PoolSettings()
    .maximumPoolSize` am Resolver ist damit die **tatsächliche** Pool-Größe aller Nicht-SQLite-Läufe
    (SQLite → Pool 1 fängt der nachgelagerte `ParallelismClamp` ab).
  - **Code-Notiz (Zukunft, nicht „Runner-Level"):** Bei künftiger `pool:`-Verdrahtung
    (s. Nebenbefund) kommt `pool.max_size` aus **derselben** `.d-migrate.yaml` — der Parallelism-
    Resolver liest sie ohnehin und löst `auto` dann am Resolver-Level gegen den verdrahteten Wert auf
    (kein Sentinel, kein Architekturwechsel). Runner-Level-Auflösung („Ansatz B") wird erst nötig, wenn
    Pool-Größen **pro Verbindung divergieren** können (z. B. Transfer Source≠Target → Deckel
    `min(source, target)`) — ein Konzept, das heute nirgends existiert, nicht mal als Spec-Idee.
- **Parallel-inkompatible Flags (`--resume`, `--atomic`)**: kommt `parallelism > 1`/`auto` aus der
  **Config**, fällt der Lauf mit stderr-Hinweis auf 1 zurück (analog SQLite-Klemme); harter Fehler
  nur bei CLI-explizitem `--parallel > 1`. Gilt für **alle** Kombinationen: Export `--resume`,
  Import `--resume` **und** `--atomic`, Transfer `--atomic` (Transfer hat kein `--resume`).
  Grundsatz: die Config darf keine überraschenden Hard-Fails verursachen.
- **Herkunftsbewusste Meldungstexte**: Die Herkunft steuert **auch die Wortwahl**, nicht nur
  Fail-vs-Fallback. Heute meldet `ParallelismClamp.kt:26` wörtlich „`--parallel $requested ignored`" —
  löst der Resolver Config-`auto` zu z. B. 8 auf und SQLite klemmt, behauptet die Meldung
  „`--parallel 8 ignored`", obwohl der Nutzer nie `--parallel` gesetzt hat. Bei Config-Herkunft muss
  der Text stattdessen den Config-Key nennen (z. B. „`pipeline.parallelism: auto (= 8)` ignoriert:
  SQLite …"). Gilt für die SQLite-Klemme **und** die `--resume`/`--atomic`-Fallback-Hinweise.
- **CLI-Symmetrie**: `auto` bleibt config-only; `--parallel` bleibt Integer. Per Lauf gibt man eine
  explizite Zahl; Config-Übersteuerung zurück auf sequenziell via `--parallel 1`.
- **Spec-Beispielwert**: Voll-Schema-Beispiel von `parallelism: auto` auf `parallelism: 1` — der
  echte Runtime-Default, konsistent damit, dass das Schema-Beispiel sonst Defaults zeigt
  (`chunk_size: 10000`, `fetch_size: 1000`). Kommentar erklärt `auto` (= CPU-Kerne, gedeckelt auf
  Pool-Größe) und die Präzedenz.

## Scope-Skizze (bei Aufnahme)

- `PipelineTuningResolver` (oder ein `PipelineParallelismResolver`) um `pipeline.parallelism`
  erweitern: positive Ganzzahl **oder** `auto`; Validierung laut statt still
  (Muster chunk_size/fetch_size).
- Präzedenz **CLI-explizit (`--parallel`) > Config > Default** analog
  `resolveEffectivePipelineTuning`. Dafür muss `--parallel` **nullbar** werden (heute
  `.int().default(1)` in allen drei Commands `data export`/`data import`/`data transfer`) — mit
  festem Flag-Default ist „CLI-explizit" nicht von „Default" unterscheidbar; der Default `1` wandert
  in den Merge, analog `--chunk-size`. Betroffen sind auch die `request.parallel`-Validierungen in
  den Runnern.
- Herkunft (CLI-explizit vs. Config) + eine origin-bewusste Label-Zeichenkette bis zu den vier
  Inkompatibilitäts-Prüfstellen (`DataExportRunner:171` resume, `DataImportHelpers:271/277`
  resume+atomic, `DataTransferRunner:312` atomic) **und** den drei `ParallelismClamp.effective`-Aufrufern
  (`DataExportRunner:278`, `ImportPreflightValidator:70`, `DataTransferRunner:137`) transportieren —
  für die Fallback-Entscheidung *und* die herkunftsbewussten Meldungstexte (`ParallelismClamp` bekommt
  den Label statt „`--parallel N`" hartzukodieren).
- Spec-Notiz in `connection-config-spec.md` (Präzedenz + Beispielwert), CHANGELOG
  (Fixed: stiller No-op).

## Nebenbefund — verifiziert, eigenes Ticket

Die YAML-Sektion `pool:` (`max_size`, `min_idle`, …) in `connection-config-spec.md`
(Abschnitt „Vollständiges Schema") ist **ebenfalls unverdrahtet** (Code-Trace 2026-07-13
bestätigt: kein `"pool"`-/`"max_size"`-String-Literal im Datenpfad-Config-Lesepfad; nur der
MCP-Serve-Pfad liest sein eigenes `hikari.maximumPoolSize`). Gleiches Muster wie dieser Befund;
ausgegliedert nach [`pool-config-section-unwired.md`](pool-config-section-unwired.md).
