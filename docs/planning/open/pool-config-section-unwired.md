# `pool:` Config-Sektion ist für den Datenpfad unverdrahtet (stiller No-op)

**Status**: Trigger (2026-07-13) — Nebenbefund aus dem `pipeline.parallelism`-Slice
([`../done/pipeline-parallelism-config-wiring.md`](../done/pipeline-parallelism-config-wiring.md)).

**Befund**: Die YAML-Sektion `pool:` (`max_size`, `min_idle`, `connection_timeout_ms`, …) im
Voll-Schema-Beispiel von [`connection-config-spec.md`](../../../spec/connection-config-spec.md)
(Abschnitt „Vollständiges Schema") wird auf dem **CLI-Datenpfad nicht gelesen** — gleiches
No-op-Muster wie zuvor `pipeline.chunk_size` und jetzt `pipeline.parallelism`. Ein Nutzer, der
`pool.max_size` in `.d-migrate.yaml` setzt, bekommt für `data export`/`import`/`transfer`/`profile`
keinen Effekt.

**Evidenz** (Code-Trace 2026-07-13): `HikariConnectionPoolFactory.create(config)` nutzt
`config.pool.maximumPoolSize`; `config.pool` ist aber immer die `PoolSettings()`-Default-Instanz
(`ConnectionConfig.pool = PoolSettings()`), die von `ConnectionUrlParser`/`NamedConnectionResolver`
nicht aus der `pool:`-YAML-Sektion befüllt wird — es gibt kein `"pool"`-/`"max_size"`-String-Literal
im Datenpfad-Config-Lesepfad. Der **einzige** Konsument einer konfigurierten Pool-Größe ist der
**MCP-Serve**-Pfad (`McpServerStateConfig`/`McpServeWiring`, `hikari.maximumPoolSize`), der hier
irrelevant ist. (SQLite bleibt separat auf `maximumPoolSize = 1` geklemmt.)

**Relevanz**: Stützt die „Ansatz A"-Entscheidung des Parallelism-Slices — weil die Pool-Größe im
Datenpfad immer der `PoolSettings()`-Default ist, löst der Resolver `auto` korrekt gegen
`PoolSettings().maximumPoolSize` auf. Sobald `pool:` verdrahtet wird, liest der Parallelism-Resolver
den Wert aus **derselben** Config mit (kein Architekturwechsel; s. Parallelism-Ticket).

**Scope-Skizze (bei Aufnahme)**:
- `pool:`-Sektion (`max_size`, `min_idle`, Timeouts, …) in `ConnectionConfig.pool` einlesen — Ort
  klären: pro Named-Connection (`NamedConnectionResolver`) und/oder global. Präzedenz gegenüber
  URL-Parametern definieren.
- Interaktion mit der SQLite-Klemme (`maximumPoolSize = 1`) und dem `pipeline.parallelism`-`auto`
  (dann gegen den verdrahteten Wert statt Default) mitdenken.
- Spec-Notiz + CHANGELOG (Fixed: stiller No-op). Beispielwerte konservativ halten.
