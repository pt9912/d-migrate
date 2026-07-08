# Adapter-Coverage-Uplift (Folge-Plan zu Phase E.2)

- **Status**: Draft mit Scope (nach `next/` promotet 2026-06-26). Design-Spike erledigt:
  Per-Modul-Split-Plan (Strategie durchgängig Split/Refactor), Phasen P0–P4 +
  Per-Modul-Akzeptanzkriterien. Die 22 `excludes-ledger.md`-`refactor-plan`-Einträge zeigen
  jetzt auf diesen `next/`-Pfad; eingefrorene `done-archive/`-Historie bleibt unangetastet
  (beschreibt E.2s damalige Promotion nach `open/`). Aktiv erst beim ersten
  Implementierungs-Commit.
- **Trigger**: Sub-Slice E.2 des
  [`quality-coverage-expansion-plan`](../done-archive/quality-coverage-expansion-plan.md)
  hat 19 heutige Kover-Excludes auf Live-JDBC- bzw. Live-Streaming-
  Adapter-Glue identifiziert, die im
  [Excludes-Ledger](../../coverage/excludes-ledger.md) bisher nur als
  `refactor-plan: TBD` standen. E.2 hat sie auf diesen Plan-Doc-Pfad
  promotet, damit der Verifier keine `TBD`-Platzhalter mehr toleriert;
  die tatsaechliche Coverage-Uplift-Umsetzung ist bewusst aus
  `quality-coverage-expansion-plan` herausgeschnitten und faellt in
  diesen Folge-Plan.
- **Aktivierungsbedingung**: Sobald ein Scope-Schnitt mit Ziel-Coverage
  pro Modul, Strategie (Testcontainers-/Fixture-/Splitting-Refactor)
  und Per-Modul-Akzeptanzkriterien steht, wandert das Dokument nach
  `../next/`.

## Betroffene Excludes (Snapshot 2026-05-31)

Quelle: `docs/coverage/excludes-ledger.md`, Dispositionsspalte
`refactor-plan: docs/planning/open/adapter-coverage-uplift.md`.

### `:adapters:driven:driver-mysql`

- `dev.dmigrate.driver.mysql.MysqlDataReader` — Live JDBC adapter; heute
  ueber `:test:integration-mysql` integration-gedeckt.
- `dev.dmigrate.driver.mysql.MysqlDriver` — Driver-Composition-Shell;
  integration-gedeckt.

### `:adapters:driven:driver-postgresql`

- `dev.dmigrate.driver.postgresql.PostgresDataReader` — Live JDBC
  adapter; integration-gedeckt ueber `:test:integration-postgresql`.
- `dev.dmigrate.driver.postgresql.PostgresDriver` — Driver-Composition-
  Shell; integration-gedeckt.

### `:adapters:driven:driver-sqlite`

- `dev.dmigrate.driver.sqlite.SqliteSchemaReader` — Live JDBC Schema-
  Reader, heute mit „edge cases requiring exotic real-world schemas"
  begruendet; §5.5 des `quality-coverage-expansion-plan`s benennt einen
  Splittungs-Plan parallel zur Cross-Dialekt-Matrix (Phase B) als
  bevorzugte Strategie.

### `:adapters:driven:persistence-jdbc`

Postgres-only JDBC-Stack mit JSONB-, `SELECT FOR UPDATE`-,
`INSERT … ON CONFLICT … RETURNING`- und `jsonb`-Spezifika; gedeckt
durch Contract-Tests in `:test:integration-server-state` unter
`-PintegrationTests`:

- `dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner*`
- `dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore*`
- `dev.dmigrate.server.persistence.jdbc.job.JdbcJobStore*`
- `dev.dmigrate.server.persistence.jdbc.job.JdbcJobStartTransaction*`
- `dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaStore*`
- `dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaReservationOwnerStore*`
- `dev.dmigrate.server.persistence.jdbc.quota.JdbcOwnerAwareQuotaService*`
- `packages("dev.dmigrate.server.persistence.jdbc.quota")` — Paket-
  weiter Backstop, der zusaetzlich `QuotaJson` (Wire-Codec) mitnimmt;
  die Wire-Versionierung koppelt mit der Schema-Version, daher heute
  zusammen mit dem Quota-Stack exkludiert.

### `:adapters:driven:formats`

- `dev.dmigrate.format.data.yaml.StreamDataWriterAdapter` — Streaming-
  Adapter-Glue, ueber Format-Integration-Pfade gedeckt.

### `:adapters:driving:cli`

Live-JDBC-/Hikari-/Flyway-Glue im CLI-Command-Layer; nicht durch das
CLI-Command-Shell-Pattern erfasst, sondern eigenstaendige Composition-
und Probe-Helfer:

- `dev.dmigrate.cli.commands.DefaultServerStateFactory*` — Hikari/
  Flyway/Postgres-Default-Factory.
- `dev.dmigrate.cli.commands.JdbcMigrationExecutor*` — JDBC-Execution-
  Helper, integration-bound.
- `dev.dmigrate.cli.commands.SequenceCurrentValueProbeRunner*` — Live-
  JDBC-/Hikari-Probe-Dispatcher.
- `dev.dmigrate.cli.commands.SqliteCastPreflightProbeRunner*` — Live-
  JDBC-/Hikari-Probe.
- `dev.dmigrate.cli.commands.SqliteLiveCatalogProbeRunner*` — Live-
  JDBC-/Hikari-Probe.

## Ungelöste Fragen

- Strategie pro Modul: Testcontainers-getragene Kover-Aufnahme vs.
  Splitting in dünne Composition-Shells plus testbare Kerne.
- Reihenfolge: vermutlich startet `:adapters:driven:driver-sqlite`
  (in-process testbar, kein Container-Aufwand), gefolgt vom
  Postgres-Stack (`:test:integration-postgresql` ist bereits im
  Root-Kover-Aggregat) und schliesst mit MySQL.
- Verhaeltnis zum geplanten `persistence-jdbc-mig`-Slice unter
  [`../next/persistence-jdbc-mig.md`](../next/persistence-jdbc-mig.md):
  ggfs. erhoehte Kopplung pruefen.

## Nicht-Ziel

- Mutation-Testing (eigener Folge-Plan, siehe §9 des Umbrella-Plans).
- Aenderungen am Kover-Excludes-Vertrag selbst (E.1-/E.3-Scope).

---

## Design-Spike (2026-06-26): Per-Modul-Split-Plan

> Strategie: **durchgängig Split/Refactor** — jeden exkludierten Adapter in pure, DB-frei
> testbaren Kern + minimale JDBC/I-O-Shell zerlegen, bis der Exclude entfällt und das
> Per-Modul-`koverVerify` (≥90%, [Test-Coverage-Standard](../../coverage/excludes-ledger.md))
> ohne den Exclude grün bleibt. Vier Modulgruppen am Code kartiert (Naht pure Kern ↔ JDBC-Shell);
> Befunde direkt verifiziert. Kein Code.

### Wichtig: zwei **tote** Excludes (Ledger-Hygiene, sofort)

- `StreamDataWriterAdapter` (Eintrag `adapters/driven/formats/build.gradle.kts`, Z. 44) — die
  Klasse **existiert nicht**. Exclude ersatzlos entfernen.
- `SequenceCurrentValueProbeRunner*` (Eintrag `adapters/driving/cli/build.gradle.kts`, Z. 242) —
  Klasse **existiert nicht** (forward-looking). Exclude entfernen; wird sie gebaut, dann gleich
  nach dem cli-Pattern testbar (siehe unten).

### Befund pro Modul

| Modul / Entität | Pure Kern (DB-frei) | Irreduzible Shell | 90% per Split? |
|---|---|---|---|
| **driver-sqlite** `SqliteSchemaReader` | Model-Assembler (PRAGMA-Row→Modell, Ordinal, PK/Index/Constraint-Aufbau) + SpatiaLite-Geometrie-Assembler (SRID-Injektion, R*Tree-Filter) extrahierbar | dünne `pool.borrow()`-Orchestrierung | **ja** — SQLite ist **in-process** (`:memory:`), selbst die Shell ist ohne Container testbar |
| **driver-postgresql/-mysql** `*DataReader` | Override-Methoden `geometryReadExpression`/`isGeometryTypeName` sind **pure** (String/Vergleich) → Direkt-Aufruf-Unit-Tests decken sie; Such-/Mapping-Logik liegt bereits getestet in `AbstractJdbcDataReader` (driver-common) | rohe `executeQuery`/`rs.next()`-Schleife (geerbt, getestet) | **ja** — Direkt-Aufruf-Tests + Driver-Konstruktions-Test (7 Factory-Methoden) |
| **driver-postgresql/-mysql** `*Driver` | reine Wiring-Fassade — Logik in den Sub-Komponenten | — | **ja** via Konstruktions-Test (Typ-Korrektheit der Factory-Methoden) |
| **persistence-jdbc** `JdbcIdempotencyStore`/`JdbcJobStore`/`JdbcQuotaStore`/`JdbcQuotaReservationOwnerStore` | State-Machines, Lease-/Quota-Arithmetik (`canReserve`, floor-at-zero), SQLState-23505-Error-Mapping, Result-Mapping → pure Kerne | `INSERT … ON CONFLICT … RETURNING`, `SELECT … FOR UPDATE`, JSONB-Cast (PG-Verhalten) | **ja, ~90%** nach Kern-Extraktion |
| **persistence-jdbc** `QuotaJson` | **vollständig** (Jackson-Codec) — `QuotaJsonTest` existiert bereits | — | **ja** — paketweiten `quota`-Exclude **verengen**, QuotaJson herauslösen |
| **persistence-jdbc** `JdbcMigrationRunner`/`JdbcJobStartTransaction`/`JdbcOwnerAwareQuotaService` | **null** extrahierbare Logik (Flyway-Wrapper / reine TX-Orchestratoren) | 100% Shell | **nein** — harter Boden (siehe unten) |
| **cli** `JdbcMigrationExecutor`, `DefaultServerStateFactory`, `Sqlite*ProbeRunner` | testbarer Runner-/Assembler-Kern (TX-/Statement-Logik, Store-Aggregation, Dialekt-Dispatch) | dünne Factory-Port-Shell (Hikari/Flyway/`acquireConnection`) | **ja** — etabliertes CLI-Command-Refactor-Pattern (Command→Runner→Wiring→Factory-Port; bewiesen: cli 88,93%→93,26% via Launcher-Naht, z. B. `DefaultMcpServeLauncher`) |
| **formats** `StreamDataWriterAdapter` | — | — | **toter Exclude** (Klasse fehlt) |

### Harter Boden (eine Slice-Entscheidung)

Drei persistence-jdbc-**Orchestratoren** (`JdbcMigrationRunner` = Flyway-Wrapper, `JdbcJobStartTransaction`,
`JdbcOwnerAwareQuotaService`) haben **keine** extrahierbare Logik — Strikt-Split kann ihren Exclude
nicht eliminieren. Zu entscheiden im Slice: **(a)** ein dünner **In-Modul-Testcontainers-Test** deckt
die irreduzible JDBC-Shell (`ON CONFLICT`/`FOR UPDATE`/JSONB) — schwererer Modul-Build, oder **(b)** ein
**dokumentierter Minimal-Exclude** für reine Orchestrierung (mit Begründung im Ledger). Empfehlung: (b)
für die reinen Orchestratoren (ehrlich begründet, kein I/O-Glue-Versteck für *Logik*), (a) nur falls die
Modul-Quote es ohne sie nicht hält.

### Phasen (Reihenfolge: leicht → schwer)

- **P0 — Ledger-Hygiene.** Zwei tote Excludes (`StreamDataWriterAdapter`, `SequenceCurrentValueProbeRunner*`)
  entfernen; `QuotaJson` aus dem paketweiten `quota`-Exclude lösen (Test existiert). **DoD:** Build grün,
  Ledger konsistent.
- **P1 — driver-sqlite.** Assembler-Kerne extrahieren + Unit-Tests (in-process, kein Container);
  `SqliteSchemaReader`-Exclude entfernen.
- **P2 — cli.** Die fünf Glue-Klassen nach dem Factory-Port-Pattern in Runner-Kern + Shell splitten;
  Excludes entfernen.
- **P3 — driver-postgresql/-mysql.** Direkt-Aufruf-Tests der pure Override-Methoden + Driver-Konstruktions-
  Tests; `*DataReader`/`*Driver`-Excludes entfernen.
- **P4 — persistence-jdbc.** Pure Kerne (State-Machines, Arithmetik, Error-/Result-Mapping) extrahieren +
  Unit-Tests; harter-Boden-Entscheidung umsetzen; Excludes auf den dokumentierten Rest reduzieren.

### Akzeptanzkriterien (pro Modul)

- Die plan-bezogenen Excludes des Moduls **entfernt** — oder auf einen **dokumentierten, irreduziblen
  Minimal-Shell** reduziert (reine Orchestrierung, mit Begründung).
- Per-Modul `koverVerify` ≥ 90% bleibt **ohne** diese Excludes grün.
- Excludes-Ledger entsprechend bereinigt (E.1-Vertrag, keine `refactor-plan: TBD`-Reste).
- **Keine** Integrationstests in den Per-Modul-Gate gezogen — außer beim bewusst entschiedenen
  harten-Boden-Fall (a).

Damit liegt ein Scope-Schnitt mit Ziel-Coverage, Strategie und Per-Modul-Akzeptanz vor → **next/-fähig**.
