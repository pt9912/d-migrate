# MCP-Tool `schema_migrate` / `schema_migrate_start`

**Status**: Vorabklärung

**Trigger**: Die Service-Mode-JVM-Verträge in
[`atomic-preserve-service-mode.md`](atomic-preserve-service-mode.md)
hängen seit 2026-06-02 mit Sub-Slices A+E geliefert in der Luft —
C (Connection-Sub-Pool), D (Quota-Plumbing) und F
(`schema_migrate`-Handler-Skeleton) warten explizit auf eine
Produkt-/Contract-Spezifikation für das MCP-Tool selbst. Solange
diese Spec fehlt, gibt es keinen Konsumenten für C/D, und F kann
nicht starten. Plan `atomic-preserve-service-mode` selbst sagt in
§3.3: „C/D/F warten effektiv auf den externen Trigger und liefern
erst in einer späteren Tranche, wenn `schema_migrate` als Tool
geplant wird."

Auch `done/quality-coverage-expansion-plan.md` §3.2 + §9 hält fest
(Zeile 384): „Ein MCP-Migrate-Tool (`schema_migrate` oder
`schema_migrate_start`) wäre ein eigener Produkt-/Contract-Slice."

Diese Datei ist die Erstanlage genau dafür — sie führt **noch keinen**
Scope, sondern sammelt offene Produkt-/Vertrags-Fragen, die geklärt
sein müssen, bevor der Slice nach `next/` wandern kann.

**Aktivierungsbedingung** (Move nach `next/`): die unter §3
gelisteten offenen Fragen sind beantwortet, ein Wire-Vertrag-Entwurf
analog [`spec/mcp-server.md`](../../../spec/mcp-server.md) §
„`data_import_start` und `data_transfer_start`" liegt vor, und ein
Sub-Slice-Schnitt für Handler + Tool-Schema + Policy-Gate + E2E-Test
ist skizziert.

---

## 1. Was bisher feststeht

Aus dem bestehenden Service-Mode-Plan und dem
`data_transfer_start`-Pattern lassen sich folgende Anker bereits
fixieren — sie sind Konsequenzen schon-getroffener Architektur-
Entscheidungen, keine Produkt-Fragen mehr:

- **Job-Worker-Pattern statt Sync-Tool.** Schema-Migrate-Calls
  dauern Sekunden bis Minuten (Lock-Acquire + Probe + Protected DDL
  + Restore). Das MCP-Tool ist konsequent als
  `schema_migrate_start` strukturiert: liefert sofort
  `{ jobId, resourceUri, executionMeta.requestId }`-Envelope,
  Status-Updates fließen über `resources/read` am Job-Resource-Pfad
  analog `data_transfer_start` ([`spec/mcp-server.md`](../../../spec/mcp-server.md)
  §661ff).
- **Idempotency-Wiring direkt am Handler** (gefaltete Sub-Slice B
  aus `atomic-preserve-service-mode` §5 B). Der bestehende
  [`IdempotencyStore`](../../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/server/ports/IdempotencyStore.kt)
  +
  [`JdbcIdempotencyStore`](../../../adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/idempotency/JdbcIdempotencyStore.kt)
  wird über
  [`OperationalMcpRegistries`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/OperationalMcpRegistries.kt)
  konsumiert, `resultRef` ist die `jobId` (nicht der
  `ExecutionTrace`).
- **Connection-Resolution analog `data_transfer_start`.** Source
  und Target werden als tenant-scoped
  `dmigrate://tenants/<tenant>/connections/<name>`-URIs
  übergeben; die JDBC-URLs leben nie im Wire-Vertrag (siehe
  Fingerprint-Vertrag §700ff).
- **Cancel über `JobCancelHandler`-Polling.** Sub-Slice E hat den
  `CancellationToken` schon bis in den Dialekt-Adapter durchgezogen
  (commit `7e6f39ae`); der Handler füttert ihn aus dem
  Job-Cancel-Pfad.
- **Lock-Timeout pro Request.** Sub-Slice A hat
  `SchemaMigrateRequest.lockTimeoutMillis`-Override und CLI-Flag
  `--lock-timeout-ms` mit Validation `[10, 60_000]` und Exit 2
  geliefert (commit `2fcb3846`); das Tool-Schema reicht diesen
  Override durch.
- **Policy-Gate-Architektur** ist durch
  `done/ImpPlan-0.9.6-F.md` (Phase F Job-Start-Tools) etabliert:
  Approval-Flow + Audit-Trail + Quota-basiertes Rate-Limit. Es
  existiert bereits — neu ist nur die Anwendung auf
  `schema_migrate_start`.

## 2. Skizzierter Wire-Vertrag (Diskussionsbasis)

Diese Felder folgen direkt aus §1. Sie sind kein Vertrag, nur eine
Ausgangsbasis für die Spec-Diskussion:

```jsonc
// schema_migrate_start (Skizze, kein Vertrag)
{
  "idempotencyKey": "smg-2026-06-03-acme-warehouse-v3",
  "sourceConnectionRef": "dmigrate://tenants/acme/connections/legacy-pg",
  "targetConnectionRef": "dmigrate://tenants/acme/connections/warehouse",
  "schemaRef":           "dmigrate://tenants/acme/schemas/orders-v3",
  "lockTimeoutMs": 30000,
  "tenant": "acme"
}
```

Bei Erfolg: symmetrischer Job-Start-Envelope (`jobId`,
`resourceUri`, `executionMeta.requestId`) — exakt wie
`data_transfer_start`.

## 3. Offene Produkt-/Vertrags-Fragen

Die folgenden Fragen blockieren den Move nach `next/`:

### 3.1 Scope-Variante: `schema_migrate_start` oder zweistufig?

`data_transfer_start` ist single-step (Caller startet einen Job,
Worker tut alles). Schema-Migrate hat eine natürliche Trennung
zwischen **Plan-Erzeugung** (Reverse + Diff + Plan-Validate) und
**Plan-Anwendung** (Protected-Lock + Probe + Restore). Optionen:

- **(a)** Einstufig: `schema_migrate_start` macht Plan + Anwendung
  als ein Job. Idempotency-Key deckt beides.
- **(b)** Zweistufig: `schema_migrate_plan_start` liefert ein
  Plan-Artefakt; `schema_migrate_apply_start` konsumiert das
  Artefakt + Approval. Erlaubt explizites Operator-Sign-off
  zwischen Plan und Apply (analog `procedure_transform_plan` /
  `procedure_transform_execute`).

Option (b) ist konsistent mit dem KI-Tool-Pattern in
[`spec/ki-mcp.md`](../../../spec/ki-mcp.md), passt aber schlechter
zur „atomic Probe+Apply"-Garantie aus
`done/atomic-preserve-followups.md`.

### 3.2 Schema-Quelle: Inline, ConnectionRef oder ArtifactRef?

`data_transfer_start` referenziert Connections, nicht Schemas. Für
Migrate kommen mindestens drei Quell-Varianten in Frage:

- **`sourceConnectionRef`** — Reverse-Read live, kein
  Pre-Materialisieren. Konsistent mit Transfer-Pattern.
- **`sourceArtifactRef`** — ein vorher exportiertes
  Schema-Artefakt (`d-migrate export schema`-Output) wird
  konsumiert. Erlaubt Plan-Stabilität über Source-Drift.
- **`schemaRef`** — eine tenant-scoped Schema-URI (würde einen
  neuen Resource-Namespace `dmigrate://…/schemas/…` brauchen).

Die Wahl beeinflusst Fingerprint-Vertrag (§5.2 unten) und
Idempotency-Replay-Semantik direkt.

### 3.3 Tenant-Modell

Sub-Slice D plant `tenant = "default"`-Konvention als Übergang
([`atomic-preserve-service-mode.md`](atomic-preserve-service-mode.md)
§5 D Risiken). Vor dem Move nach `next/` muss klar sein:

- Ist der `tenant`-Parameter im Wire-Vertrag explizit oder leitet
  ihn der Handler aus dem `Principal` ab?
- Welcher `tenant`-Wert gilt im Single-Tenant-Default-Mode?
- Wie verträgt sich das mit dem `dmigrate://tenants/<tenant>/…`-
  URI-Schema (Tenant ist dort schon Bestandteil der Resource-URI)?

### 3.4 Approval-Granularität

`data_transfer_start` nutzt einen einzelnen Approval-Key pro Job.
Schema-Migrate berührt potenziell viele Objekte (Tabellen,
Sequences, Constraints) — ein einzelner Approval-Key gibt
Operatoren wenig Sichtbarkeit auf den Diff. Optionen:

- **(a)** Single-Approval pro Migrate-Job — einfach, aber
  „blind unterzeichnet".
- **(b)** Plan-Artefakt enthält Per-Objekt-Sichtbarkeit, Approval
  bezieht sich auf den Plan-Fingerprint — bedingt zweistufige
  Variante aus §3.1.

### 3.5 Concurrency-Modell pro `targetConnectionRef`

Sub-Slice C (Connection-Sub-Pool) plant `maximumPoolSize = 4` als
Default für den Schreib-Pfad. Offene Frage: sollen parallele
Migrate-Jobs gegen denselben `targetConnectionRef` zulässig sein,
oder serialisiert der Handler sie über einen explizit beschränkten
Pool (`maximumPoolSize = 1` pro `targetConnectionRef`)?

`pg_advisory_xact_lock` / MySQL `SELECT FOR UPDATE` / SQLite
`BEGIN IMMEDIATE` würden sie auf DB-Seite ohnehin serialisieren,
aber Pool-Borrow-Timeout-Verhalten ist anders als
`SCHEMA_MIGRATE_LOCK_CONTENDED`-Fehler.

### 3.6 Quota-Bucket-Schema

Sub-Slice D plant
`QuotaScope("schema_migrate", tenant=<tenant>, schema=<targetRef>)`.
Offene Frage:

- Reicht ein Concurrency-Quota („max N parallele Migrate-Jobs pro
  Tenant pro `targetRef`"), oder braucht es zusätzlich einen
  Time-Window-Quota („max N Migrate-Jobs pro Stunde pro Tenant")?
- Wie greift das Quota in der zweistufigen Variante (§3.1 b) — auf
  `plan_start` oder `apply_start` oder beide?

### 3.7 Fingerprint-Eingaben

Der Fingerprint-Vertrag aus
[`spec/mcp-server.md`](../../../spec/mcp-server.md) §700ff
verbietet rohe SQL/Filter-Strings ohne Kanonisierung. Für Migrate:

- Geht der **Plan** (kanonisierte DDL-Sequenz) in den Fingerprint,
  oder die **Eingabe-Schemas** (Source-Reverse-Hash +
  Target-Reverse-Hash)?
- Wie wird `lockTimeoutMs` behandelt — Fingerprint-Pflichtfeld
  oder nicht (es ändert das Ergebnis bei Lock-Timeout-Fall, nicht
  beim Happy-Path)?

### 3.8 Failure-Klassifikation am Wire

Der Migrate-Pfad kennt mindestens vier Failure-Klassen, die der
Caller unterscheidbar zurückbekommen muss:

- `SCHEMA_MIGRATE_LOCK_TIMEOUT` — Lock-Acquire-Timeout (Sub-Slice A).
- `SCHEMA_MIGRATE_CANCELLED` — Externer Cancel (Sub-Slice E).
- `SERVICE_POOL_EXHAUSTED` — Pool-Borrow-Timeout (Sub-Slice C).
- `SERVICE_RATE_LIMITED` — Quota-Exhaustion (Sub-Slice D).

Plus die bestehenden Klassen aus dem Atomic-Preserve-Pfad
(`AtomicSequencePreserveResult.Failure`-Varianten). Müssen alle in
den MCP-Job-Status-Envelope mappen, ohne Stacktrace-Leak.

## 4. Was bewusst **kein** Teil dieser Vorabklärung ist

- **REST 1.2.0** und **gRPC 1.1.8** Migrate-Endpoints. Diese
  Roadmap-Positionen referenzieren denselben Service-Mode-
  Backbone, aber die Wire-Verträge sind RPC-eigen — sie laufen in
  eigenen Vorabklärungen, sobald 1.1.8 / 1.2.0 dran sind.
- **CLI-`schema migrate --execute --service-mode`-Subkommando.**
  Der CLI-Pfad bleibt regressionsfrei
  ([`atomic-preserve-service-mode.md`](atomic-preserve-service-mode.md)
  §4.3) und ohne Job-Worker. Ein zukünftiger Service-Mode-CLI-
  Adapter wäre ein eigener Slice.
- **Schema-Versionierung / Schema-Drift-Detection.** Falls
  Variante (b) aus §3.1 gewählt wird, könnte ein Plan-Artefakt
  veralten, bevor Apply läuft. Behandlung gehört in einen
  Schema-Versioning-Slice, nicht hierher.

## 5. Verweise

- [`atomic-preserve-service-mode.md`](atomic-preserve-service-mode.md)
  — Service-Mode-JVM-Verträge, die diese Tool-Spec konsumieren.
  §5 C/D/F sind die Sub-Slices, die ohne diese Spec nicht starten.
- [`spec/mcp-server.md`](../../../spec/mcp-server.md) §661ff —
  `data_transfer_start`/`data_import_start`-Pattern als nächste
  Analogie.
- [`spec/ki-mcp.md`](../../../spec/ki-mcp.md) — zweistufiges
  Pattern (`procedure_transform_plan` /
  `procedure_transform_execute`) als Diskussionsbasis für §3.1 (b).
- [`done/ImpPlan-0.9.6-F.md`](../done/ImpPlan-0.9.6-F.md) —
  Policy-Gate-Architektur (Approval + Audit + Quota), die
  `schema_migrate_start` übernehmen kann.
- [`done/atomic-preserve-followups.md`](../done/atomic-preserve-followups.md)
  — Atomic-Preserve-Garantien, die der Tool-Vertrag respektieren
  muss.
- [`in-progress/carveout.md`](../in-progress/carveout.md) §62, §113
  — Carve-Out-Einträge, die diese Vorabklärung adressieren.
