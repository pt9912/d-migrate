# ImpPlan 1.0.0-RC — `pool:`-Config-Wiring (Datenpfad)

> Status: Done (2026-07-16) — AP1–AP3 geliefert; full-build (`build koverVerify --no-build-cache`)
> + docs-check grün. Siehe [Closure](#closure).

**Quelle/Trigger**: [`pool-config-section-unwired.md`](pool-config-section-unwired.md)
(Nebenbefund aus dem `pipeline.parallelism`-Slice,
[`../done/pipeline-parallelism-config-wiring.md`](../done/pipeline-parallelism-config-wiring.md)).
Vorbild ist exakt jenes Slice: gleiche Config-No-op-Familie
(`chunk_size` ✅ → `parallelism` ✅ → **`pool` hier**).

**Spec-Vertrag**: [`connection-config-spec.md`](../../../spec/connection-config-spec.md)
§2.2 (HikariCP-Defaults) + §3.2 (Voll-Schema, `database.pool:`-Block).

## Befund (Code-Trace bestätigt)

`ConnectionConfig.pool` hat produktiv **genau einen** Zufluss: den `PoolSettings()`-Default
(`ConnectionConfig.kt:24`). Kein Config-Leser füllt `database.pool.*` —
es gibt kein `"pool"`/`"max_size"`-Literal im Datenpfad-Lesepfad. Der einzige Konsument einer
konfigurierten Pool-Größe ist heute der MCP-Serve-Pfad (`McpServerStateConfig`, eigener
`server.state.hikari.*`-Subtree — hier irrelevant).

Der `poolFactory: (ConnectionConfig) -> ConnectionPool`-Seam existiert in **allen 4** Daten-Wirings
und endet immer in `HikariConnectionPoolFactory.create`. Der Faktor konsumiert bereits **alle**
`PoolSettings`-Felder und behält die SQLite-Klemme (`maximumPoolSize=1, minimumIdle=1`) →
**keine Änderung am Faktor nötig**.

## Entscheidungen (User-Review 2026-07-16, freigegeben)

- **D1 — Welche Keys**: nur die **5 spec'd Keys** (`max_size`, `min_idle`,
  `connection_timeout_ms`, `idle_timeout_ms`, `max_lifetime_ms`).
  Die drei weiteren `PoolSettings`-Felder (`keepaliveTimeMs`, `statementTimeoutMs`,
  `networkTimeoutMs`) bleiben **bewusst nicht** user-tunbar über diese Sektion: es sind die
  sicherheitskritischen Cancel-Reaktions-Schranken aus `PoolSettings.kt` (Doc-Kommentar) und
  stehen nicht im YAML-Schema. Ungesetzte Keys → `PoolSettings()`-Default pro Feld.
- **D2 — Validierung**: streng positiv (`> 0`) über die vorhandenen
  `requirePositiveIntConfig`/`requirePositiveLongConfig` (laut-statt-still, wie `pipeline.*`).
  `min_idle: 0` wird abgelehnt (HikariCP erlaubte es zwar, aber wir bleiben konsistent; echte
  0-Semantik wäre eine spätere Extension).
- **D3 — Cross-Field**: `min_idle <= max_size` wird **laut** geprüft (`ConfigResolveException`),
  wenn beide gesetzt sind — statt HikariCPs stiller Coercion.
- **D4 — Injektionsstelle**: am **`poolFactory`**-Seam
  (`HikariConnectionPoolFactory.create(config.copy(pool = resolvedPool))`) — einheitliche
  Signatur in allen 4 Wirings, SQLite-Klemme bleibt downstream, `urlParser` bleibt fokussiert.
- **D5 — Scope**: **global** (`database.pool`) — die Spec kennt keinen pro-Connection-Pool.

**Präzedenz**: Config > Default (es gibt **kein** CLI-Flag für Pool, anders als
`--parallel`/`--chunk-size`). URL-Parameter speisen `pool` heute nicht → keine URL-Präzedenz-Frage.
**Exit-Codes**: Pool-Config-Fehler → **Exit 7** (`ConfigResolveException`); ungültiger effektiver
Wert → **Exit 2** (`IllegalArgumentException`) — wie bei `pipeline.*`. Für `data profile` dieselbe
Mapping-Konsistenz herstellen.

## Key-Mapping (`database.pool.*` → `PoolSettings`)

| YAML-Key | Feld | Typ | Validierung |
|---|---|---|---|
| `max_size` | `maximumPoolSize` | Int | `requirePositiveIntConfig` |
| `min_idle` | `minimumIdle` | Int | `requirePositiveIntConfig` + `<= max_size` |
| `connection_timeout_ms` | `connectionTimeoutMs` | Long | `requirePositiveLongConfig` |
| `idle_timeout_ms` | `idleTimeoutMs` | Long | `requirePositiveLongConfig` |
| `max_lifetime_ms` | `maxLifetimeMs` | Long | `requirePositiveLongConfig` |

## AP1 — `PoolSettingsResolver` + Unit-Tests

- Neu: `PoolSettingsResolver.kt` (CLI-`config`-Package) — `internal class PoolSettingsResolver`
  (Muster `PipelineTuningResolver`) + `internal fun resolveEffectivePoolSettings(configPath, preloaded?): PoolSettings`.
  Liest `root["database"] as? Map` → `["pool"] as? Map`; fehlt eine Ebene → `PoolSettings()` (alle
  Defaults). Per-Key `requirePositive*Config`; Cross-Field-Check D3; Rückgabe gebautes `PoolSettings`.
- Test: `PoolSettingsResolverTest` — jeder Key gesetzt; Teilmenge (Rest Default); keine
  `database`/`pool`-Sektion → alle Defaults; streng: `1.5`/`"5"`/`0`/`-1` → `ConfigResolveException`;
  `min_idle > max_size` → `ConfigResolveException`.

## AP2 — Pipeline-Fold + 4 Wiring-Injektionen

- `EffectiveDataPipelineResolver.kt`: `EffectiveDataPipeline` um `val pool: PoolSettings` ergänzen;
  im selben `loadEffectiveConfig`-Ladevorgang `resolveEffectivePoolSettings(preloaded = loaded)`
  aufrufen und `pool.maximumPoolSize` als `maxPoolSize` an `resolveEffectiveParallelism` reichen →
  **`auto` deckelt gegen den konfigurierten Wert**. Der bisherige Default-Parameter
  `maxPoolSize = PoolSettings().maximumPoolSize` wird intern abgeleitet (Signatur bereinigen,
  `availableProcessors` für Tests behalten).
- `Data{Export,Import,Transfer}Options` um `pool: PoolSettings` (Default `PoolSettings()`);
  in den drei Commands aus `pipeline.pool` befüllen.
- **Injektion** in allen 4 Wirings am `poolFactory`-Seam:
  `HikariConnectionPoolFactory.create(config.copy(pool = <resolved>))`.
  - export `DataExportWiring.kt:126`, import `DataImportWiring.kt:99/201`,
    transfer `DataTransferWiring.kt:98`.
  - **profile** ruft `resolveEffectiveDataPipeline` nicht (kein Parallelism): in
    `DefaultDataProfileWiringFactory.build(configPath, readOnly)` standalone
    `resolveEffectivePoolSettings(configPath)` und am `poolFactory` (`DataProfileWiring.kt:76`)
    injizieren; Config-Fehler → Exit 7 konsistent mappen.
- Tests: `EffectiveDataPipelineResolverTest` erweitern (`auto` gegen `pool.max_size`, z. B.
  max_size=4/cores=16 → 4); Wiring-Tests mit Fake-`poolFactory`, der `ConnectionConfig.pool` fängt
  (Muster `capturedContext.pool`); profile-Pfad analog.

## AP3 — Doku + Build + Verify + Commit

- `connection-config-spec.md` §3.2: Kurznotiz „diese 5 Keys werden auf dem Datenpfad honoriert;
  Präzedenz Config > Default" (Beispielwerte bleiben — sind schon die echten Defaults).
- `CHANGELOG.md`: „Fixed: `database.pool:` war auf dem Datenpfad ein stiller No-op".
- Build: docker `--no-cache-filter compile,build`; betroffene Module `:check`; koverVerify ≥90%;
  `make docs-check`; Full-Build. Commit auf `develop`. ImpPlan → `../done/`.

## DoD

- `database.pool.max_size` etc. wirkt messbar (Wiring-Test fängt injizierte `PoolSettings`).
- `pipeline.parallelism: auto` deckelt gegen konfigurierten `max_size` statt Default 10.
- Ungültige Pool-Config → Exit 7/2 laut, nicht still ignoriert.
- SQLite bleibt auf Pool 1 geklemmt (Faktor-Klemme, unverändert).
- koverVerify ≥90% je berührtem Modul; docs-check grün.

## Closure

Geliefert wie geplant (D1–D5 unverändert umgesetzt).

- **AP1** — `PoolSettingsResolver` + `resolveEffectivePoolSettings` (5 Keys, streng positiv,
  Cross-Field `min_idle <= max_size`, per-Feld-Defaults); `PoolSettingsResolverTest` (12 Fälle).
- **AP2** — `resolveEffectiveDataPipeline` löst Pool im selben Ladevorgang auf und deckelt
  `parallelism: auto` gegen `pool.maximumPoolSize` (redundanter `maxPoolSize`-Param entfernt);
  Injektion am `poolFactory`-Seam in **allen 4** Wirings (`config.copy(pool = …)`); `data profile`
  löst standalone auf (Exit-7-Mapping). Direkte Injektions-Assertions in `DataProfileWiringTest`
  + `DataImportWiringTest`; `EffectiveDataPipelineResolverTest` um auto-Kopplung/Pool-Surface erweitert.
- **AP3** — `connection-config-spec.md` §3.2 Präzedenz-Notiz; CHANGELOG „Fixed: `database.pool:`
  No-op". Full-build (`build koverVerify --no-build-cache`, alle Module) + `make docs-check` grün.

**Verifikation**: `:adapters:driving:cli:check` (test/detekt/koverVerify) grün; aggregat-`:koverVerify`
grün; `d-check` 0 Befunde. **Injektion am `poolFactory`-Seam** gewählt (D4) — `urlParser` bleibt
fokussiert, SQLite-Klemme bleibt downstream im `HikariConnectionPoolFactory` (unverändert).
