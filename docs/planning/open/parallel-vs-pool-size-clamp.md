# Explizites `--parallel N` nicht gegen Pool-`max_size` geklemmt (Security-Audit #5, UX/Robustheit)

> **Status:** Beim Follow-up-Audit #5 (paralleler Datenpfad) 2026-07-19 entdeckt.
> **Kein Sicherheitsbefund** (`--parallel` ist CLI-operator-only, nicht MCP-exponiert;
> Operator ≠ Angreifer), aber eine UX-/Robustheit-Lücke.
> **Trigger:** Follow-up-Audit des parallelen Datenpfads (aus der „Nicht geprüft /
> offene Lücken"-Sektion des [`security-audit-2026-07-17.md`](../done/security-audit-2026-07-17.md),
> Punkt 5, Frage „Ist N gegen `maximumPoolSize` gedeckelt?").

## Beobachtung

`resolveEffectiveParallelism` (`PipelineParallelismResolver`) klemmt nur den
**Config**-Wert `pipeline.parallelism: auto` auf `min(availableProcessors, maxPoolSize)`.
Ein **explizites** `--parallel N` wird lediglich mit `require(N >= 1)` validiert und
sonst unverändert übernommen — **es wird NICHT gegen die Connection-Pool-Größe
geklemmt**. Die SQLite-Klemme (`ParallelismClamp` → 1) greift danach nur für SQLite.

Im parallelen Datenpfad hält jeder Work-Unit (eine Tabelle/Partition) gleichzeitig
**eine Source- und eine Target-Connection** (`reader.streamTable(sourcePool)` offen,
während `writer.openTable(targetPool)` schreibt). Bei `degree = D` braucht der Lauf
also D Source- + D Target-Connections. Ist `database.pool.max_size < D`, konkurrieren
`D − max_size` Worker um freie Connections und blockieren bis `connectionTimeout`
(HikariCP-Default 30 s); danach wirft der erste betroffene Worker → `ParallelWorkExecutor`
bricht fail-fast ab → der ganze Lauf scheitert nach ~30 s mit einem
Connection-Timeout-Fehler.

## Warum kein Sicherheitsbefund

- `--parallel` ist **CLI-operator-only**: der MCP-Datenpfad
  (`McpDataTransferJobWorker`/`McpDataImportJobWorker`) setzt `DataTransferRequest.parallel`
  nicht → Default 1 (sequenziell). Ein authentifizierter Tenant kann die Parallelität
  nicht setzen. Operator ≠ Angreifer (SECURITY.md).
- Der Fehler ist recoverbar (Operator senkt `--parallel` oder erhöht
  `database.pool.max_size`) und tritt fail-fast auf, kein Hang, kein Datenverlust
  über das dokumentierte Nicht-atomar-Verhalten hinaus.

## Zu tun (Vorschlag)

Analog zur SQLite-Klemme und zur `auto`-Deckelung: für **explizites** `--parallel N`
einen Preflight-Abgleich gegen die effektive `PoolSettings.maximumPoolSize` ziehen.
Optionen (User-Entscheidung):

1. **Warnung** (minimal): `--parallel N > pool.max_size` → einmaliger Hinweis
   („N=20 überschreitet database.pool.max_size=10; der Lauf wird um Connections
   konkurrieren und ggf. nach connectionTimeout scheitern — erhöhe pool.max_size
   oder senke --parallel"), Lauf läuft weiter (Operator-Vertrauen).
2. **Clamp** (wie SQLite): auf `max_size` klemmen + herkunftsbewusster Hinweis.
3. **Pool an Parallelität koppeln**: Pool-`max_size` automatisch auf `>= degree`
   anheben, wenn der Operator `--parallel` explizit setzt (aber keine `pool.max_size`).

Faktor 2 beachten (Source+Target je 1 Connection pro Unit) — die relevante Grenze
ist `max_size` **pro Pool**, nicht die Summe.

## Fundstellen

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/PipelineParallelismResolver.kt` (`resolveEffectiveParallelism` — nur `auto` gedeckelt)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/ParallelismClamp.kt` (SQLite-Klemme; hier ließe sich der Pool-Abgleich anhängen)
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/TransferExecutor.kt` (`transferTable` — Source+Target gleichzeitig geborgt)
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/PoolSettingsResolver.kt` (`database.pool.max_size`-Quelle)
