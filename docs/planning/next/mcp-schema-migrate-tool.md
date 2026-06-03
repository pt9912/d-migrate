# MCP-Tool `schema_migrate` / `schema_migrate_start`

**Status**: Next (2026-06-03 — open/-Vorabklärung mit Strawman
§3 + Wire-Vertrag V1 §2 + Sub-Slice-Schnitt §5 ausgereift; promotet
nach `next/`).

**Trigger**: Die Service-Mode-JVM-Verträge in
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
hängen seit 2026-06-02 mit Sub-Slices A+E geliefert in der Luft —
C (Connection-Sub-Pool), D (Quota-Plumbing) und F
(`schema_migrate`-Handler-Skeleton) warten explizit auf eine
Produkt-/Contract-Spezifikation für das MCP-Tool selbst. Solange
diese Spec fehlt, gibt es keinen Konsumenten für C/D, und F kann
nicht starten. Plan `atomic-preserve-service-mode` selbst sagt in
§3.3: „C/D/F warten effektiv auf den externen Trigger und liefern
erst in einer späteren Tranche, wenn `schema_migrate` als Tool
geplant wird."

Auch [`done/quality-coverage-expansion-plan.md`](../done/quality-coverage-expansion-plan.md) §3.2 + §9 hält fest
(Zeile 384): „Ein MCP-Migrate-Tool (`schema_migrate` oder
`schema_migrate_start`) wäre ein eigener Produkt-/Contract-Slice."

Das Doc trägt den Sub-Slice-Schnitt F.1-F.5 (§5), den Wire-Vertrag
V1 (§2) und einen Strawman zu den acht Produkt-/Vertrags-Fragen
(§3). Es ist damit bereit, sobald C oder D aus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 dran ist, in `in-progress/` zu wandern und die Sub-Slices F.1-F.5
nacheinander zu liefern.

**Aktivierungsbedingung** (Move nach `in-progress/`): F.1
(Tool-Schema + Discovery) und F.2 (dryRun-Handler) sind ohne
Service-Mode-Vorarbeit implementierbar und können sofort starten.
F.3 (Pool-Wiring im Worker) blockiert auf C; F.4 (Apply mit
Approval+Quota+Lock+Cancel) blockiert auf A + D + E (alle aus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5). F.5 (E2E) hängt an F.4.

---

## 1. Was bisher feststeht

Aus dem bestehenden Service-Mode-Plan und dem
`data_transfer_start`-Pattern lassen sich folgende Anker bereits
fixieren — sie sind Konsequenzen schon-getroffener Architektur-
Entscheidungen, keine Produkt-Fragen mehr:

- **Job-Worker-Pattern für den Apply-Pfad.** Schema-Migrate-Applys
  dauern Sekunden bis Minuten (Lock-Acquire + Probe + Protected DDL
  + Restore). Der nicht-`dryRun`-Pfad ist konsequent als
  `schema_migrate_start` strukturiert: liefert sofort
  `{ jobId, resourceUri, executionMeta.requestId }`-Envelope,
  Status-Updates fließen über `resources/read` am Job-Resource-Pfad
  analog `data_transfer_start` ([`spec/mcp-server.md`](../../../spec/mcp-server.md)
  §661ff). `dryRun: true` ist die Plan-only-Ausnahme aus §3.1.
- **Idempotency-Wiring direkt am Handler** (gefaltete Sub-Slice B
  aus `atomic-preserve-service-mode` §5 B). Der bestehende
  [`IdempotencyStore`](../../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/server/ports/IdempotencyStore.kt)
  +
  [`JdbcIdempotencyStore`](../../../adapters/driven/persistence-jdbc/src/main/kotlin/dev/dmigrate/server/persistence/jdbc/idempotency/JdbcIdempotencyStore.kt)
  wird über
  [`OperationalMcpRegistries`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/OperationalMcpRegistries.kt)
  konsumiert, `resultRef` ist die `jobId` (nicht der
  `ExecutionTrace`).
- **Connection-Resolution analog `data_transfer_start`.** Live-Source
  und Target werden als tenant-scoped
  `dmigrate://tenants/<tenant>/connections/<name>`-URIs
  übergeben; alternativ kann die Source aus einem tenant-scoped
  `dmigrate://tenants/<tenant>/schemas/<schemaId>`-Eintrag im
  SchemaStore kommen. Die JDBC-URLs leben nie im Wire-Vertrag
  (siehe Fingerprint-Vertrag §700ff).
- **`payloadFingerprint` wird pre-Job-Start aus dem Wire-Payload
  gebildet.**
  [`JobStartOrchestrator.start`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt)
  Zeile 157 ruft
  `payloadFingerprintService.fingerprint(scope=START_TOOL,
  payload=request.payload, …)` synchron vor Idempotency- und
  Policy-Check. Reverse-Hashes der Source-/Target-DB sind damit
  **nicht** Teil des Idempotency-Identitäts-Fingerprints; sie würden
  einen synchronen Live-Reverse im Handler erzwingen und das
  Job-Start-Pattern brechen. Plan-Inhalt und Reverse-Hashes fließen
  ausschließlich in den `planFingerprint`, der im `dryRun`-Antwort-
  Pfad bzw. im Apply-Worker entsteht.
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
  [`done/ImpPlan-0.9.6-F.md`](../done/ImpPlan-0.9.6-F.md) (Phase F Job-Start-Tools) etabliert:
  Approval-Flow + Audit-Trail + Quota-basiertes Rate-Limit. Es
  existiert bereits — neu ist nur die Anwendung auf
  `schema_migrate_start`.

## 2. Wire-Vertrag V1

Robusterer Entwurf als Sub-Slice-Eingabe. JSON-Schema-Form,
Pflicht/Optional, Bounds, getrennte Response-Envelopes für `dryRun`
und Apply, gemeinsamer Error-Envelope.

### 2.1 Request-Schema

```jsonc
// schema_migrate_start — Request (V1)
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "targetConnectionRef"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 8,
      "maxLength": 128,
      "pattern": "^[A-Za-z0-9._:-]+$"
    },
    "sourceConnectionRef": {
      "type": "string",
      "pattern": "^dmigrate://tenants/[^/]+/connections/[^/]+$"
    },
    "sourceSchemaRef": {
      "type": "string",
      "pattern": "^dmigrate://tenants/[^/]+/schemas/[^/]+$"
    },
    "targetConnectionRef": {
      "type": "string",
      "pattern": "^dmigrate://tenants/[^/]+/connections/[^/]+$"
    },
    "dryRun": { "type": "boolean", "default": false },
    "lockTimeoutMs": {
      "type": "integer",
      "minimum": 10,
      "maximum": 60000,
      "default": 30000
    },
    "options": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "preserveSequences": { "type": "boolean", "default": true },
        "atomicPreserve":    { "type": "boolean", "default": true }
      }
    },
    "approvalToken": {
      "type": "string",
      "minLength": 8,
      "maxLength": 256
    }
  },
  "oneOf": [
    { "required": ["sourceConnectionRef"] },
    { "required": ["sourceSchemaRef"] }
  ]
}
```

`$schema` ist auf
[Draft 2020-12](https://json-schema.org/draft/2020-12/schema)
gepinnt — Draft-07-Keywords (`definitions`, `id`, `dependencies`)
sind durch
[`JsonSchemaDialect.DRAFT_07_FORBIDDEN_KEYWORDS`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/JsonSchemaDialect.kt)
verboten.

Die Ref-Patterns prüfen **strukturell** das URI-Skelett
(`scheme://tenants/<segment>/<kind>/<segment>`), nicht das
Tenant-/ID-Zeichenset. Der zentrale Parser
[`ServerResourceUri.parse`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/server/core/resource/ServerResourceUri.kt)
erzwingt anschließend `^[A-Za-z0-9_\-]+$` für beide Segmente. Diese
Aufteilung vermeidet Schema-vs-Parser-Drift: Schema-Verstöße liefern
`VALIDATION_ERROR` mit struktureller Begründung, semantische
Verstöße (verbotene Sonderzeichen) liefern denselben Fehlercode aus
dem Parser-Pfad.

`tenant` ist **kein** Wire-Feld — analog
[`DataTransferStartHandler`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/DataTransferStartHandler.kt)
liest der Handler `context.principal.effectiveTenantId` und prüft
beide Resource-Refs gegen diesen abgeleiteten Tenant. Damit gibt es
genau eine Tenant-Quelle.

Semantische Validation, die das JSON-Schema nicht trägt, läuft im
Handler (analog
[`spec/mcp-server.md`](../../../spec/mcp-server.md) §694ff):

- `sourceConnectionRef` / `sourceSchemaRef` / `targetConnectionRef`
  müssen unter dem aus dem Principal abgeleiteten Tenant auflösen.
  Nicht auflösbar → `RESOURCE_NOT_FOUND`. Tenant-Segment der URI
  passt nicht zum Principal-Tenant → `VALIDATION_ERROR` mit
  „tenant prefix mismatch"-Violation (analog
  [`DataTransferStartHandler`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/DataTransferStartHandler.kt)
  Zeile 292ff). Eine semantisch passendere
  `TENANT_SCOPE_DENIED`-Migration ist Risk #8.
- Approval-Flow folgt dem bestehenden Job-Start-Pattern aus
  [`JobStartHandlerSupport.toToolCallOutcome`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/JobStartHandlerSupport.kt):
  fehlender oder noch nicht ausgestellter `approvalToken` →
  `POLICY_REQUIRED` mit Challenge-`details` (Zeile 131-146);
  vorhandener, aber ungültiger Token →
  `POLICY_DENIED` via `PolicyDeniedException` (Zeile 147-148).
  Der Caller reicht den Token im Folge-Call nach. Bei `dryRun=true`
  ist `approvalToken` egal — der Pfad geht keine Job-Start-
  Pipeline durch.

### 2.2 Response — Dry-Run-Envelope

Sync-Antwort, kein Job-Start. Liefert das Plan-Artefakt samt
Fingerprints, ohne `BEGIN`/Dialekt-Lock/Probe/Apply/Restore. Das
Pflichtfeld `dryRun: true` ist Self-Discriminator für das
Output-`oneOf` (siehe §F.1):

```jsonc
{
  "dryRun": true,
  "payloadFingerprint": "sha256:…",
  "planFingerprint":    "sha256:…",
  "plan": {
    "dialect": "postgresql",
    "objects": [
      { "kind": "table",     "name": "public.orders",       "op": "CREATE" },
      { "kind": "sequence",  "name": "public.orders_id_seq","op": "PRESERVE" },
      { "kind": "index",     "name": "idx_orders_tenant",   "op": "CREATE" },
      { "kind": "constraint","name": "fk_orders_customer",  "op": "ADD" }
    ],
    "objectCount":  4,
    "warningCount": 0
  },
  "executionMeta": {
    "requestId": "req-…"
  }
}
```

`executionMeta` ist 1:1 das Format aus
[`executionMetaJobField`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/McpToolSchemas.kt)
(Zeile 722) — nur `requestId` ist Pflicht, Cancel-Felder sind
optional. `tenant` und `principal` leben ausschließlich im
Audit-Trail und im Job-Resource-URI, nicht im Wire-Envelope.

### 2.3 Response — Apply-Job-Start-Envelope

Symmetrischer Job-Start-Envelope wie `data_transfer_start`
([`spec/mcp-server.md`](../../../spec/mcp-server.md) §661ff). Der
Caller pollt Status über `resources/read` am `resourceUri`. Das
Pflichtfeld `jobId` (zusammen mit der Abwesenheit eines
`dryRun`-Felds) ist Self-Discriminator für das Output-`oneOf`:

```jsonc
{
  "jobId":       "job-…",
  "resourceUri": "dmigrate://tenants/acme/jobs/job-…",
  "executionMeta": {
    "requestId": "req-…"
  }
}
```

`executionMeta` folgt 1:1 dem Schema aus §2.2 — nur `requestId`
ist Pflicht; Cancel-bezogene Felder
(`cancelRequested`/`cancelAckPending`/…) sind optional und werden
beim Job-Status über `resources/read` projiziert, nicht im
Start-Envelope.

Idempotency-Replay (gleicher `idempotencyKey` + gleicher
`payloadFingerprint`) liefert denselben Envelope mit derselben
`jobId`. Replay mit anderem `payloadFingerprint` →
`IDEMPOTENCY_CONFLICT`.

### 2.4 Error-Envelope

Der Wire-Envelope passt 1:1 auf den bestehenden
[`ToolErrorEnvelope`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorEnvelope.kt):
`code: ToolErrorCode`, `message: String`,
`details: List<ToolErrorDetail>` (jeder
[`ToolErrorDetail`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorEnvelope.kt)
ist `{ key, value }` mit String-Wert), `requestId: String?`. Er ist
**ausschließlich** für synchrone Tool-Result-Codes (§3.8) reserviert;
Worker-Failures (Lock-Timeout, Pool-Exhaustion, Atomic-Failure)
erscheinen als Job-Status-Detail über `resources/read` und **nicht**
hier. Beispiel für die häufigste synchrone Apply-Antwort
(`POLICY_REQUIRED`-Challenge):

```jsonc
{
  "code":    "POLICY_REQUIRED",
  "message": "Policy approval required",
  "details": [
    { "key": "approvalRequestId",   "value": "appr-…" },
    { "key": "correlationKind",     "value": "MIGRATE_START" },
    { "key": "correlationKey",      "value": "smg-2026-06-03-…" },
    { "key": "payloadFingerprint",  "value": "sha256:…" },
    { "key": "requiredScopes",      "value": "dmigrate:data:write" },
    { "key": "reasons",             "value": "no-active-grant" }
  ],
  "requestId": "req-…"
}
```

Code-Inventar und Mapping siehe §3.8. Stacktraces gehören nicht in
`message` oder `details`.

## 3. Strawman zu den Produkt-/Vertrags-Fragen

Die folgenden Antworten sind ein Entscheidungsvorschlag, noch kein
implementierter Vertrag. Sie ersetzen den reinen Fragenkatalog durch
konservative Defaults, an denen der spätere `next/`-Scope geschnitten
werden kann.

### 3.1 Scope-Variante — Einstufig + `dryRun`

`schema_migrate_start` bleibt einstufig. Der Apply-Pfad
erzeugt den Plan und wendet ihn im selben Job an; Idempotency-Key,
Approval, Quota und Audit beziehen sich auf diesen einen Job.

Begründung:

- Die Atomic-Preserve-Garantie aus
  [`done/atomic-preserve-followups.md`](../done/atomic-preserve-followups.md)
  ist Probe + Apply in einer Transaktion. Ein getrenntes
  `plan_start`/`apply_start`-Paar würde zulassen, dass ein
  Plan-Artefakt zwischen Planung und Anwendung gegen Source- oder
  Target-Drift veraltet.
- `data_transfer_start` ist ebenfalls einstufig; `schema_migrate_start`
  bleibt damit im etablierten Start-Tool-Pattern.
- Operator-Sichtbarkeit läuft über `dryRun: true`: der Handler liefert
  das Plan-Artefakt samt Fingerprints zurück, beginnt aber keine
  Transaktion, nimmt keinen Dialekt-Lock und führt keinen Apply aus.

### 3.2 Schema-Quelle — `sourceConnectionRef` oder `sourceSchemaRef`

Der primäre Pfad ist Live-Reverse über `sourceConnectionRef`,
konsistent mit `data_transfer_start`.

`sourceSchemaRef` ist die optionale Alternative für ein vorher
registriertes Schema. Es nutzt den **bereits existierenden**
Schema-Resource-Namespace
`dmigrate://tenants/<tenant>/schemas/<schemaId>`, unter dem
[`SchemaStagingFinalizer`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/SchemaStagingFinalizer.kt)
und der
[`McpCoreJobWorkerFactory`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/McpCoreJobWorkerFactory.kt)
`SchemaIndexEntry`-Records registrieren. Damit kommen Format, Hash,
Tenant-Scope und No-Oracle-Checks aus dem SchemaStore automatisch
mit — kein direktes Artifact-Loading, das diese Garantien umgehen
würde.

Pins ein Source-Schema gegen Drift und ist nützlich, wenn Source und
Target bewusst mit einer drift-toleranten Migrate-Strategie betrieben
werden.

Validierung:

- `targetConnectionRef` ist Pflicht.
- Exakt eine Source ist Pflicht: entweder `sourceConnectionRef` oder
  `sourceSchemaRef`.
- Beide Source-Felder gesetzt oder beide fehlend liefert
  `VALIDATION_ERROR`.

### 3.3 Tenant-Modell — Principal-Ableitung + Per-Ref-Typ-Mapping

`tenant` ist **kein** Wire-Feld. Der Handler liest
`context.principal.effectiveTenantId` als Single-Source-of-Truth und
prüft beide Resource-Refs gegen diesen Tenant.

Der Wire-Code für einen Mismatch hängt vom Ref-Typ ab und folgt
dabei jeweils dem bestehenden Bestands-Pattern:

- **`sourceConnectionRef` / `targetConnectionRef`** — analog
  [`DataTransferStartHandler`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/DataTransferStartHandler.kt)
  Zeile 292ff: `ValidationErrorException` mit
  `tenant prefix mismatch`-Violation → `VALIDATION_ERROR`.
- **`sourceSchemaRef`** — analog
  [`SchemaSourceResolver`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/SchemaSourceResolver.kt)
  Zeile 108: `TenantScopeDeniedException`
  ([`ApplicationException.kt:149`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt))
  → `TENANT_SCOPE_DENIED`.

Diese Asymmetrie ist Bestand; ein einheitliches Mapping (alle
Start-Tools auf `TENANT_SCOPE_DENIED`) ist eigener Folge-Slice —
siehe Risk #8.

Bis ein echtes Tenant-Modell kommt, bedient der Single-Tenant-Default
das Pattern transparent: der Principal trägt
`effectiveTenantId = "default"`, und alle Resource-Refs nutzen
`dmigrate://tenants/default/...`. Sobald das Tenant-Modell erweitert
wird, ändert sich nichts am Wire-Vertrag — der Principal liefert den
neuen Tenant
([`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 D Risiken).

### 3.4 Approval-Granularität — Single-Approval + Plan-Fingerprint

Der Apply-Pfad nutzt ein einzelnes Approval pro Migrate-Job, exakt
wie die bestehenden Start-Tools. Weil §3.1 bewusst einstufig bleibt,
gibt es keinen belastbaren getrennten „Plan-Sign-off vor Apply"-
Zeitpunkt.

Der Flow folgt dem bestehenden Token-Challenge-Pattern aus
[`JobStartHandlerSupport.toToolCallOutcome`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/JobStartHandlerSupport.kt):

- `dryRun: false` ohne `approvalToken` →
  `POLICY_REQUIRED`-Antwort mit `details` (`approvalRequestId`,
  `correlationKind`, `correlationKey`, `payloadFingerprint`,
  `requiredScopes`, `reasons`).
- `dryRun: false` mit ungültigem `approvalToken` →
  `POLICY_DENIED`-Antwort (Grant invalid). Der Caller reicht den Token im
  Folge-Call nach.
- Approval-Grants binden an `principal`, `tenant`, `tool`,
  `correlationKey` und `payloadFingerprint`. Replay mit anderem
  `payloadFingerprint` → `IDEMPOTENCY_CONFLICT`.

Operator-Sichtbarkeit geht trotzdem nicht verloren:

- `dryRun: true` liefert das Plan-Artefakt, Objektliste,
  `payloadFingerprint` und `planFingerprint`.
- Der Audit-Trail des Apply-Jobs speichert denselben
  `payloadFingerprint`, den erzeugten `planFingerprint` und die
  redigierte Per-Objekt-Zusammenfassung.

### 3.5 Concurrency pro `targetConnectionRef` — `maximumPoolSize=1`

Für den Migrate-Sub-Pool gilt als Default
`maximumPoolSize = 1` pro `targetConnectionRef`.

Die DB-seitigen Locks (`pg_advisory_xact_lock`, MySQL
`SELECT FOR UPDATE`, SQLite `BEGIN IMMEDIATE`) bleiben die
Korrektheitsgrenze. Der App-Layer-Pool liefert aber früheres und
klareres Feedback: ein konkurrierender Writer endet mit
`SERVICE_POOL_EXHAUSTED` nach Borrow-Timeout, statt erst eine
Connection zu blockieren und anschließend im DB-Lock-Wait zu hängen.

Der Default ist pro `targetConnectionRef`-Konfiguration
überschreibbar, falls ein Betreiber bewusst mehrere parallele
Schreiber zulassen will.

### 3.6 Quota-Bucket-Schema — Bestehendes `ACTIVE_JOBS`-Quota

Der MVP nutzt das **bereits existierende** Concurrency-Quota aus
[`JobStartOrchestrator.reserveQuota`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt):
`QuotaKey(tenantId, dimension=ACTIVE_JOBS, principalId,
operation="schema_migrate_start")`.

Damit verhält sich der Apply-Pfad identisch zu allen anderen
Start-Tools — Quota-Exhaustion liefert `RATE_LIMITED`, Sweeper-
Refund läuft über den vorhandenen QuotaService-Pfad.

`targetRef`-spezifische Quota-Granularität („max N parallele Jobs
pro Ziel-DB, nicht nur pro Tenant/Principal") ist für den MVP
**bewusst nicht** Teil dieses Slices: sie würde eine Erweiterung der
`QuotaKey`/`QuotaDimension`-Vokabel und ein Update am
`JobStartOrchestrator` erfordern. Diese Erweiterung kommt als
eigenständiger Service-Mode-Quota-Slice, siehe §7 Risiko #5.

Time-Window-Quotas („max N Migrate-Jobs pro Stunde") bleiben
ebenfalls deferred, bis Betreiberfeedback sie verlangt.

### 3.7 Fingerprint-Eingaben — Reverse-Hashes + kanonische Optionen

Der Fingerprint-Vertrag aus
[`spec/mcp-server.md`](../../../spec/mcp-server.md) §700ff
verbietet rohe SQL/Filter-Strings ohne Kanonisierung. Für
`schema_migrate_start` gibt es zwei getrennte Fingerprints:

- `payloadFingerprint` ist die Idempotency-/Approval-Identität.
- `planFingerprint` ist Operator- und Audit-Sichtbarkeit für den
  erzeugten Plan, aber nicht die Replay-Identität.

Der `payloadFingerprint` wird **synchron vor dem Job-Start** aus
dem Wire-Payload gebildet
([`JobStartOrchestrator.start`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt)
Zeile 157, `scope = START_TOOL`). Er besteht ausschließlich aus
schnellen Eingaben:

- `sourceConnectionRef` oder `sourceSchemaRef`.
- `targetConnectionRef`.
- `tenant` (aus dem Principal abgeleitet via `BindContext`).
- `principal` (aus dem Principal über `BindContext.callerId`).
- `canonical(options)`.

`SchemaIndexEntry.hash` ist als Identitäts-Eingabe für
`sourceSchemaRef` **erlaubt**, weil der SchemaStore-Lookup schnell
ist und vor dem Fingerprint-Service stattfindet. Reverse-Hashes der
Source- oder Target-DB sind dagegen **nicht** Bestandteil:
`sourceConnectionRef` adressiert Identität über die ConnectionRef,
nicht über den Live-Schema-Snapshot. Sonst müsste der Handler vor
jedem Replay-Check einen Live-Reverse fahren — das bricht das
schnelle Job-Start-Pattern und verschiebt Replay-Identität auf einen
Zeitpunkt nach DB-IO.

Der `planFingerprint` ist davon getrennt und enthält die
Reverse-/Plan-Inhalte. Er entsteht:

- Im `dryRun: true`-Sync-Antwortpfad: Handler führt Reverse + Diff +
  Plan-Validate und gibt den Fingerprint im Antwort-Envelope zurück.
- Im Apply-Worker (F.4): Worker reverst, berechnet den Plan,
  schreibt `planFingerprint` in den Job-Status und ins Audit, **ohne
  ihn nachträglich in den `payloadFingerprint` zu falten**.

**Alle side-effect-relevanten Optionen sind Identitäts-Eingaben**,
auch `lockTimeoutMs`. Ein Replay mit gleichem `idempotencyKey`,
aber `lockTimeoutMs = 10` vs. `60000`, würde sonst auf denselben
Job zurückmappen, obwohl sich Worker-Verhalten und
Erfolgswahrscheinlichkeit (Lock-Acquire-Race) materiell ändern.
Konkret in `canonical(options)`-Eingabe gehören:

- `lockTimeoutMs`
- `options.preserveSequences`
- `options.atomicPreserve`
- alle künftigen Optionen mit Worker-Wirkung (Default-Position:
  **rein**, es sei denn die Option ist explizit als reiner
  Telemetrie-Parameter dokumentiert).

`approvalToken` geht nicht in den Fingerprint — bestehender
Token-Challenge-Flow aus
[`JobStartHandlerSupport`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/JobStartHandlerSupport.kt)
bindet ihn an Approval-Grant + Principal, nicht an die Payload-
Identität.

### 3.8 Failure-Klassifikation am Wire — Tool-Result vs. Job-Result

Der Migrate-Pfad führt Failures auf **zwei getrennten Achsen**, weil
das Job-Start-Pattern aus
[`spec/mcp-server.md`](../../../spec/mcp-server.md) §661ff den Tool-
Call vom Job-Worker entkoppelt:

- **Tool-Result-Codes**: synchrone Antwort auf den `tools/call`
  selbst. Sie fließen über
  [`ToolErrorEnvelope`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorEnvelope.kt)
  und tauchen in
  [`ToolDescriptor.errorCodes`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/ToolDescriptor.kt)
  auf — Bestand: „Tool-Result-Envelope-Codes".
- **Job-Result-Codes**: erscheinen ausschließlich im
  Job-Status-Projection über `resources/read`, mit demselben
  `ToolErrorCode`-Vokabular im Failure-Detail. Sie kommen **nicht**
  in `ToolDescriptor.errorCodes`, weil der Tool-Call selbst längst
  erfolgreich mit Job-Start-Envelope geantwortet hat.

Wo möglich, werden bestehende Codes wiederverwendet; nur die
migrate-spezifischen Job-Result-Klassen kommen als neue
Enum-Werte hinzu (F.1).

**Tool-Result-Codes (synchron auf den Tool-Call):**

- `VALIDATION_ERROR` — Schema-Verstoß: fehlendes Pflichtfeld,
  `additionalProperties`-Reject, ungültige Bounds (`lockTimeoutMs`
  außerhalb `[10, 60_000]`), beide Source-Felder gesetzt oder beide
  fehlend.
- `RESOURCE_NOT_FOUND` — `sourceConnectionRef`,
  `sourceSchemaRef` oder `targetConnectionRef` löst nicht auf.
- `VALIDATION_ERROR` für ConnectionRef-Tenant-Mismatch —
  Tenant-Segment von `sourceConnectionRef` oder
  `targetConnectionRef` ≠ `principal.effectiveTenantId`. Details:
  eine `ValidationViolation` mit `field`/`reason` (§3.3, Risk #8).
- `TENANT_SCOPE_DENIED` für SchemaRef-Tenant-Mismatch —
  Tenant-Segment von `sourceSchemaRef` ≠
  `principal.effectiveTenantId`. Details:
  `requestedTenant` aus
  `TenantScopeDeniedException`
  ([`ApplicationException.kt:149`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt)).
- `POLICY_REQUIRED` — Apply ohne `approvalToken` (Challenge-
  Antwort). `details` enthalten `approvalRequestId`,
  `correlationKind`, `correlationKey`, `payloadFingerprint`,
  `requiredScopes`, `reasons` (§3.4).
- `POLICY_DENIED` — Apply mit vorhandenem, aber ungültigem
  `approvalToken` (z. B. abgelaufener Grant, anderer Principal,
  abweichender `payloadFingerprint`). `details` enthalten
  `policyName` und `reason` aus `PolicyDeniedException`.
- `IDEMPOTENCY_CONFLICT` — Replay mit gleichem
  `idempotencyKey`, aber abweichendem `payloadFingerprint`.
- `RATE_LIMITED` — `JobStartOrchestrator.reserveQuota` liefert
  `RateLimited` (§3.6). `details` enthalten `retryAfter`,
  `current`, `limit` — wie alle anderen Start-Tools.

**Job-Result-Codes (Status-Projection via `resources/read`,
Enum-Erweiterung in F.1):**

- `SCHEMA_MIGRATE_LOCK_TIMEOUT` — Dialekt-Lock-Acquire-Timeout
  (Sub-Slice A). `details`: `lockTimeoutMs`, `dialect`.
- `SERVICE_POOL_EXHAUSTED` — Migrate-Sub-Pool-Borrow-Timeout
  (Sub-Slice C). `details`: `targetConnectionRef`,
  `connectionTimeoutMs`.
- `SCHEMA_MIGRATE_ATOMIC_FAILURE` — Sammelcode für die
  `AtomicSequencePreserveResult.Failure`-Varianten. `details`
  führen mindestens `kind`-Eintrag (`PROBE_FAILED`,
  `RESTORE_FAILED`, `LOCK_ESCALATION`, …) plus
  varianten-spezifische Felder.

**Cancel ist weder Tool-Result- noch Job-Result-Failure-Code.**
Externer Cancel via `job_cancel` setzt den Job-Status auf
`CANCELLED`; der Apply-Caller sieht das ausschließlich über
`resources/read` am `resourceUri` (wie bei `data_transfer_start`).
Es gibt **keinen** `SCHEMA_MIGRATE_CANCELLED`-Code.

Stacktraces bleiben ausschließlich server-side im Audit- oder
Log-Kontext und erscheinen nie im Wire-Envelope.

## 4. Leitentscheidungen

### 4.1 Sub-Slice-Schnitt folgt dem Job-Start-Pattern

Die Sub-Slices wachsen nicht entlang der DDL-Phasen, sondern entlang
der Job-Start-Tool-Architektur aus
[`done/ImpPlan-0.9.6-F.md`](../done/ImpPlan-0.9.6-F.md):
Tool-Schema → Handler-Skeleton (dryRun) → Pool-Wiring (Worker) →
Apply-Job (Approval+Quota+Lock+Cancel) → E2E. Das verteilt das
Risiko der Atomic-Preserve-Garantie auf einen einzigen Sub-Slice
(F.4) statt es über mehrere zu streuen.

### 4.2 `dryRun` vor Apply bauen

F.2 liefert den `dryRun`-Pfad zuerst. Apply ist initial ein
NOT_IMPLEMENTED-Stub. Das erlaubt Plan-Fingerprint-Verträge,
Reverse-Wiring und Operator-Sichtbarkeit zu pinnen, bevor die
Atomic-Preserve-Apply-Risiken (Lock, Probe, Restore, Rollback) ins
Spiel kommen. F.5 hängt damit an einem gegen `dryRun` schon
verifizierten Plan-Vertrag.

### 4.3 CLI-Pfad bleibt regressionsfrei

Genau wie
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§4.3: jede F.\*-Änderung muss die bestehenden Live-IT-Tests am
CLI-Pfad grün lassen. Der MCP-Handler liefert ein neues
Konsumenten-Profil; er ersetzt keinen.

## 5. Geplante Arbeitspakete

Die Sub-Slices F.1-F.5 schließen den Service-Mode-Vertrags-Track
ab. Sie konsumieren die JVM-Verträge C/D/E aus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 und liefern den MCP-Konsumenten.

### Sub-Slice F.1 — Tool-Schema + Discovery-Wiring

**Ziel**: `schema_migrate_start` ist vollständig discoverable:
Tool-Schema (Input + Output) registriert, in
[`McpServerConfig.DEFAULT_SCOPE_MAPPING`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt)
eingetragen und in
[`McpContractRegistries`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/McpContractRegistries.kt)
mit Title/Description/ErrorCodes versorgt.

**Akzeptanzkriterien**:
- [ ] Neuer `SchemaPair`-Eintrag in `McpToolSchemas.kt` mit Input-
  und Output-Schema:
  - **Input** = das Request-Schema aus §2.1 (Draft 2020-12,
    `additionalProperties: false`, `oneOf` für Source-Variante,
    Bounds für `lockTimeoutMs`).
  - **Output** = `oneOf` aus dem dryRun-Envelope (§2.2) und dem
    Job-Start-Envelope (§2.3). Das Output-JSON-Schema kann den
    Request nicht sehen — die Antwort selbst muss diskriminierbar
    sein: der dryRun-Envelope führt `dryRun: { const: true }` als
    Pflichtfeld; der Apply-Envelope führt `jobId` und
    `resourceUri` als Pflichtfelder (und kein `dryRun`-Feld). Im
    JSON-Schema realisiert über zwei disjunkte `required`-Sets
    plus `additionalProperties: false`.
- [ ] Schema-Validator-Test pinnt sechs Reject-Cases (Draft-07-
  Forbidden-Keyword wie `definitions` zusätzlich zu den fünf aus
  Runde 1: fehlende Pflichtfelder, beide Source-Felder gesetzt,
  beide Source-Felder fehlend, `lockTimeoutMs` außerhalb
  `[10, 60_000]`, fremde Properties, `definitions`-Keyword auf
  beliebiger Ebene).
- [ ] Schema-Validator-Test pinnt zwei Accept-Cases: minimaler
  Happy-Path mit `sourceConnectionRef`, minimaler Happy-Path mit
  `sourceSchemaRef`.
- [ ] Neuer Eintrag in `McpServerConfig.DEFAULT_SCOPE_MAPPING`:
  `"schema_migrate_start" to dataWrite` — analog
  `data_import_start` und `data_transfer_start`
  ([`McpServerConfig.kt:256-258`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt)).
  Schema-Mutationen am Target sind klar schreibend; `jobStart`
  reicht als Scope **nicht**. `dryRun: true` läuft unter
  demselben Scope; Plan-only-Sicht ohne Schreibrechte ist Scope
  für einen späteren `schema_migrate_plan`-Read-Tool, nicht für
  diesen Slice. Vertragstest:
  `tools/list` listet das neue Tool inkl. `requiredScopes`,
  `inputSchema`, `outputSchema`.
- [ ] Neue Einträge in `McpContractRegistries`:
  - `TITLES["schema_migrate_start"]`
  - `DESCRIPTIONS["schema_migrate_start"]`
  - `ERROR_CODES["schema_migrate_start"]` enthält **nur die
    Tool-Result-Codes** aus §3.8 — die der Tool-Call selbst
    synchron emittieren kann:
    `POLICY_REQUIRED`, `POLICY_DENIED`, `IDEMPOTENCY_CONFLICT`,
    `RATE_LIMITED`, `VALIDATION_ERROR`, `RESOURCE_NOT_FOUND`,
    `TENANT_SCOPE_DENIED` (für SchemaRef-Tenant-Mismatch),
    `UNSUPPORTED_TOOL_OPERATION` (Pre-F.4-Stub).
    Worker-Failure-Codes (`SCHEMA_MIGRATE_LOCK_TIMEOUT`,
    `SERVICE_POOL_EXHAUSTED`, `SCHEMA_MIGRATE_ATOMIC_FAILURE`)
    erscheinen ausschließlich im Job-Status-Detail und gehören
    laut [`ToolDescriptor`-Vertrag](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/ToolDescriptor.kt)
    **nicht** hierher.
- [ ] `ToolErrorCode`-Enum
  ([`hexagon/core/src/main/kotlin/.../ToolErrorCode.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorCode.kt))
  um die drei migrate-spezifischen Werte erweitert:
  `SCHEMA_MIGRATE_LOCK_TIMEOUT`, `SERVICE_POOL_EXHAUSTED`,
  `SCHEMA_MIGRATE_ATOMIC_FAILURE`. Sie werden zwar dem Enum
  hinzugefügt (für Job-Result-Failure-Klassifikation), aber **nicht**
  in `ERROR_CODES["schema_migrate_start"]` aufgenommen. Bestehende
  Codes (`POLICY_REQUIRED`, `POLICY_DENIED`,
  `IDEMPOTENCY_CONFLICT`, `RATE_LIMITED`, `RESOURCE_NOT_FOUND`,
  `VALIDATION_ERROR`, `TENANT_SCOPE_DENIED`,
  `UNSUPPORTED_TOOL_OPERATION`) werden wiederverwendet — siehe
  §3.8.
- [ ] Drei neue `ApplicationException`-Subtypen anlegen, weil
  [`AppExceptionHierarchyTest`](../../../hexagon/application/src/test/kotlin/dev/dmigrate/server/application/error/AppExceptionHierarchyTest.kt)
  (`§6.7`-Invariante) für jeden Enum-Wert genau ein Subtyp
  verlangt:
  - `SchemaMigrateLockTimeoutException`
  - `ServicePoolExhaustedException`
  - `SchemaMigrateAtomicFailureException`
- [ ] `ApplicationExceptionFixtures` um Fixture-Einträge für die
  drei neuen Codes erweitert; `AppExceptionHierarchyTest` läuft
  ohne manuelle Eingriffe transitiv durch.
- [ ] `ToolErrorEnvelopeTest`
  ([`hexagon/core/src/test/kotlin/.../ToolErrorEnvelopeTest.kt:36`](../../../hexagon/core/src/test/kotlin/dev/dmigrate/server/core/error/ToolErrorEnvelopeTest.kt))
  pinnt aktuell exakt 18 Codes; Pin auf 21 erhöhen und die drei
  neuen Codes in das Erwartungs-Set aufnehmen. Test-Header-
  Kommentar `// 18 codes mandated by docs/ki-mcp.md` auf 21
  aktualisieren.
- [ ] [`spec/mcp-server.md`](../../../spec/mcp-server.md) +
  [`spec/ki-mcp.md`](../../../spec/ki-mcp.md) Error-Code-Liste
  um die drei neuen Werte ergänzen (Job-Result-Detail-
  Klassifikation).
- [ ] `make ci` grün.

**Betroffene Dateien**:
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/McpToolSchemas.kt`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/McpContractRegistries.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorCode.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt`
  (drei neue Subtypen)
- `hexagon/application/src/test/kotlin/dev/dmigrate/server/application/error/ApplicationExceptionFixtures.kt`
  (drei neue Fixture-Einträge)
- `hexagon/core/src/test/kotlin/dev/dmigrate/server/core/error/ToolErrorEnvelopeTest.kt`
  (Pin auf 21 erhöhen)
- `spec/mcp-server.md`, `spec/ki-mcp.md` (Code-Liste)
- Neuer Test: `adapters/driving/mcp/src/test/kotlin/dev/dmigrate/mcp/schema/SchemaMigrateStartSchemaTest.kt`
- Erweiterung an `McpToolsListContractTest.kt` (oder Pendant) für
  den `tools/list`-Vertrag.

**Dependencies**: keine.

**Risiken**: niedrig — alle Eingriffe sind mechanisch und folgen
dem bestehenden 1:1-Mapping `ToolErrorCode` ↔
`ApplicationException`-Subtyp.

### Sub-Slice F.2 — Handler-Skeleton (dryRun-only)

**Ziel**: `SchemaMigrateStartHandler` liefert `dryRun: true` mit
Plan, `payloadFingerprint`, `planFingerprint`. Apply-Pfad ist
NOT_IMPLEMENTED.

**Akzeptanzkriterien**:
- [ ] Neuer Handler `SchemaMigrateStartHandler` analog
  `DataTransferStartHandler`-Pattern.
- [ ] Tenant wird via
  `context.principal.effectiveTenantId` abgeleitet (kein
  Wire-Feld).
- [ ] Source-Auflösung: `sourceConnectionRef` triggert Live-Reverse
  (analog `DataTransferStartHandler`-Pfad);
  `sourceSchemaRef` triggert
  [`SchemaStore`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/SchemaStagingFinalizer.kt)-
  Lookup → `SchemaIndexEntry` mit `hash`, `format`, `tenantId`.
  Beide gegen den aus dem Principal abgeleiteten Tenant.
- [ ] ConnectionRef-Tenant-Mismatch (`sourceConnectionRef` oder
  `targetConnectionRef`) liefert `VALIDATION_ERROR` mit
  `tenant prefix mismatch`-Violation via
  [`ValidationErrorException`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt).
- [ ] SchemaRef-Tenant-Mismatch (`sourceSchemaRef`) liefert
  `TENANT_SCOPE_DENIED` via
  [`TenantScopeDeniedException`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt)
  (§3.3, bestehendes
  [`SchemaSourceResolver`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/SchemaSourceResolver.kt)-
  Pattern Zeile 108).
- [ ] Unauflösbare Refs liefern `RESOURCE_NOT_FOUND` via
  `ResourceNotFoundException`.
- [ ] `dryRun: true` liefert die Antwort aus §2.2 (Plan-Objekte,
  Fingerprints, ExecutionMeta). Kein DB-Connection-Borrow für den
  Target-Apply, kein Dialekt-Lock, keine Job-Worker-Pipeline.
- [ ] `dryRun: false` liefert vorerst `UNSUPPORTED_TOOL_OPERATION`
  (bestehender Code) — Apply-Pfad kommt mit F.3/F.4.
- [ ] Handler registriert in `OperationalMcpRegistries`.
- [ ] Handler-Unit-Test pinnt sieben Pfade:
  Happy-dryRun-mit-ConnectionRef, Happy-dryRun-mit-SchemaRef,
  ConnectionRef-Tenant-Mismatch (`VALIDATION_ERROR`),
  SchemaRef-Tenant-Mismatch (`TENANT_SCOPE_DENIED`), fehlender
  `targetConnectionRef` (`VALIDATION_ERROR`), Apply-Stub
  (`UNSUPPORTED_TOOL_OPERATION`), unauflösbarer Source-Ref
  (`RESOURCE_NOT_FOUND`).
- [ ] `make ci` grün.

**Betroffene Dateien**:
- Neuer Handler:
  `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SchemaMigrateStartHandler.kt`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/OperationalMcpRegistries.kt`
- Neuer Test:
  `adapters/driving/mcp/src/test/kotlin/dev/dmigrate/mcp/registry/SchemaMigrateStartHandlerTest.kt`

**Dependencies**: F.1.

**Risiken**: mittel — der Plan-Erzeuger fasst Reverse + Diff +
Plan-Validate zusammen; der Fingerprint-Vertrag aus §3.7 muss
deterministisch gegen Reverse-Hashes pinnen.

### Sub-Slice F.3 — Connection-Sub-Pool-Wiring (Worker)

**Ziel**: Der **Apply-Job-Worker** least eine eigene Connection
aus dem MigratePoolFactory (Sub-Slice C). Der MCP-Handler selbst
fasst den Pool nicht an — sonst würde Pool-Borrow das synchrone
Tool-Call-Pattern blockieren und das Job-Start-Versprechen aus §2.3
brechen.

**Akzeptanzkriterien**:
- [ ] `MigratePoolFactory.acquire(targetConnectionRef)` läuft
  **im Worker** vor dem `BEGIN`.
- [ ] Handler bleibt synchron und schnell: `dryRun: true` greift
  nicht zum Pool; `dryRun: false` antwortet sofort mit
  Job-Start-Envelope und delegiert den Pool-Borrow an den Worker.
- [ ] Borrow-Timeout im Worker → Job-Status `FAILED` mit
  Failure-Detail `code = SERVICE_POOL_EXHAUSTED` und
  `details.connectionTimeoutMs`/`targetConnectionRef`. Kein
  Wire-Error vom Start-Tool — Caller sieht das über
  `resources/read`.
- [ ] Vertragstest: zwei parallele Apply-Jobs gegen denselben
  `targetConnectionRef` mit `maximumPoolSize = 1` → erster Job
  läuft erfolgreich; zweiter Job-Status wird nach
  `connectionTimeoutMs` zu `FAILED` mit
  `SERVICE_POOL_EXHAUSTED` im Failure-Detail.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- Job-Worker-Modul + Wiring (Pool-Bind im Worker, nicht im Handler)
- IT-Test: `McpSchemaMigratePoolExhaustionIT.kt`

**Dependencies**: F.2 plus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 C (Connection-Sub-Pool).

**Risiken**: niedrig — Worker-seitiges Borrow ist Bestands-Pattern
für Job-Worker (siehe `data_transfer_start`-Worker).

### Sub-Slice F.4 — Apply-Pfad (Approval + Quota + Job-Start + Worker)

**Ziel**: Apply ist ein echter Job-Start. Approval-Token-Validierung,
Quota-Reservation, Commit und Worker-Dispatch laufen atomar im
[`JobStartOrchestrator`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt)-
Pfad bzw. dem
[`ApprovedRetryService`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/ApprovedRetryService.kt).
Der Worker führt den Plan in einer Transaktion unter Dialekt-Lock
aus; Cancel + Lock-Timeout + Atomic-Preserve-Failures mappen auf
§3.8.

**Akzeptanzkriterien**:
- [ ] Handler ersetzt den `UNSUPPORTED_TOOL_OPERATION`-Stub aus
  F.2 durch einen `JobStartRequest`, der den
  bestehenden `JobStartOrchestrator.start`-Pfad fährt.
- [ ] Apply ohne `approvalToken` → `POLICY_REQUIRED` mit
  `details` (`approvalRequestId`, `correlationKind`,
  `correlationKey`, `payloadFingerprint`, `requiredScopes`,
  `reasons`) aus
  [`JobStartHandlerSupport.toToolCallOutcome`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/JobStartHandlerSupport.kt).
- [ ] Apply mit gültigem `approvalToken` → `ApprovedRetryService`
  validiert, claimed Idempotency-Slot, reserviert Quota,
  committet den Job atomar (Zeile 67ff). Approval-Wiring ist
  damit **untrennbar** an den Job-Start gebunden — kein
  „Approval-only"-Pfad nötig.
- [ ] Apply mit ungültigem `approvalToken` (Grant invalid,
  anderer Principal, abweichender `payloadFingerprint`) →
  `POLICY_DENIED` via `PolicyDeniedException`
  ([`JobStartHandlerSupport.kt`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/JobStartHandlerSupport.kt)
  Zeile 147). Kein Job-Start, kein Quota-Reservation.
- [ ] Quota-Reservation läuft synchron im Commit-Pfad mit
  `QuotaKey(tenantId, ACTIVE_JOBS, principalId,
  operation="schema_migrate_start")`. RateLimited liefert
  vor dem Commit `RATE_LIMITED` (synchroner Start-Tool-Fehler,
  kein Job).
- [ ] Audit-Eintrag pinnt `payloadFingerprint`, `planFingerprint`,
  `tenant`, `principal`, `approvalToken`-Redaktion.
- [ ] Job-Start liefert bei Erfolg die Antwort aus §2.3
  (`jobId`, `resourceUri`, `executionMeta`).
- [ ] Job-Worker komponiert `SchemaMigrateRunner` mit
  `lockTimeoutMs` aus dem Request, dem Pool-Lease aus F.3 und
  dem CancellationToken aus `JobCancelHandler`.
- [ ] Job-Status-Updates fließen über `resources/read` analog
  `data_transfer_start`.
- [ ] Cancel vor BEGIN → Job-Status `CANCELLED`, Connection
  released, kein Lock genommen. **Kein** Wire-Error vom
  Start-Tool — der Caller sieht den Cancel über
  `resources/read` (§3.8).
- [ ] Cancel zwischen Probe und Restore → Job-Status `CANCELLED`
  mit Rollback (Vertrag aus
  [`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
  §5 E).
- [ ] Lock-Acquire-Timeout → Job-Status `FAILED` mit
  Failure-Detail `code = SCHEMA_MIGRATE_LOCK_TIMEOUT` (und
  `details.lockTimeoutMs`/`dialect`) im Job-Result.
- [ ] Atomic-Preserve-Failure (Probe/Restore) → Job-Status
  `FAILED` mit Failure-Detail `code =
  SCHEMA_MIGRATE_ATOMIC_FAILURE` und `details.kind` (z. B.
  `PROBE_FAILED`).
- [ ] Idempotency-Replay (gleicher `idempotencyKey` + gleicher
  `payloadFingerprint`) liefert dieselbe `jobId` via
  `JobStartHandlerOutcome.AlreadyStarted`.
- [ ] `make ci` grün inkl. neuer Integrationstests pro Dialekt.

**Betroffene Dateien**:
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SchemaMigrateStartHandler.kt`
- Job-Worker-Wiring (Pool + Cancel + Apply-Sequenz)
- IT-Tests pro Dialekt

**Dependencies**: F.2 + F.3 plus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 A (Lock-Timeout), §5 D (Quota-Plumbing), §5 E
(Cancellation-Token).

**Risiken**: hoch — zusammengesetzter Atomicity-Vertrag
(Approval+Quota+Commit+Worker), Cancel-Mid-Apply,
Failure-Klassen-Mapping. Wichtigster Slice für Sub-Phase-Reviews.

### Sub-Slice F.5 — E2E-Scenario-Test

**Ziel**: `McpSchemaMigrateStartScenarioTest` gegen file-SQLite
(Operational-MCP-Harness aus Quality-Coverage-Expansion Phase F1)
pinnt das volle Vertragsbündel.

**Akzeptanzkriterien**:
- [ ] Scenario 1: Happy-dryRun-mit-ConnectionRef → §2.2.
- [ ] Scenario 2: Happy-Apply → Job durchgelaufen, Plan angewendet.
- [ ] Scenario 3: Idempotency-Replay → dieselbe `jobId`
  (`JobStartHandlerOutcome.AlreadyStarted`).
- [ ] Scenario 4: Replay mit fremdem `payloadFingerprint` →
  `IDEMPOTENCY_CONFLICT`.
- [ ] Scenario 5a: Quota-Exhaustion → synchroner `RATE_LIMITED`
  (kein Job).
- [ ] Scenario 5b: Apply ohne `approvalToken` → `POLICY_REQUIRED`
  mit Challenge-Details.
- [ ] Scenario 5c: Apply mit ungültigem `approvalToken` →
  `POLICY_DENIED`.
- [ ] Scenario 6: Cancel-Mid-Apply → Job-Status `CANCELLED` via
  `resources/read` + Rollback (kein Wire-Error vom Start-Tool).
- [ ] Scenario 7: Lock-Timeout → Job-Status `FAILED` mit
  `SCHEMA_MIGRATE_LOCK_TIMEOUT` im Failure-Detail.
- [ ] Scenario 8: Pool-Exhaustion (parallele Apply-Jobs gegen
  denselben Target) → zweiter Job `FAILED` mit
  `SERVICE_POOL_EXHAUSTED` im Failure-Detail.
- [ ] Scenario 9a: ConnectionRef-Tenant-Mismatch →
  `VALIDATION_ERROR` mit `tenant prefix mismatch`-Violation.
- [ ] Scenario 9b: SchemaRef-Tenant-Mismatch → `TENANT_SCOPE_DENIED`
  mit `requestedTenant`-Detail.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- Neuer Test:
  `adapters/driving/mcp/src/test/kotlin/dev/dmigrate/mcp/integration/McpSchemaMigrateStartScenarioTest.kt`

**Dependencies**: F.4.

**Risiken**: niedrig — Harness existiert, das Test-Profil ist
mechanische Komposition.

## 6. Dependency-Graph

```
F.1 (Tool-Schema + Discovery)   ──→ F.2 (dryRun-Handler)
                                            │
atomic-preserve C (Sub-Pool)    ──→ F.3 (Pool-Wiring im Worker)
                                            │
atomic-preserve A (Lock-Timeout)            ↓
atomic-preserve D (Quota-Plumbing)  ──→ F.4 (Apply: Approval+Quota+Worker)
atomic-preserve E (Cancel-Token)            │
                                            ↓
                                        F.5 (E2E)
```

- **F.1 → F.2** läuft sofort, **ohne** Abhängigkeit zu
  atomic-preserve. F.2 stellt den dryRun-Pfad fertig; Apply
  liefert `UNSUPPORTED_TOOL_OPERATION`.
- **F.3** hängt an F.2 und an atomic-preserve C
  (`MigratePoolFactory`).
- **F.4** ist die Synthese und hängt an F.2 + F.3 plus
  atomic-preserve A + D + E (Lock-Timeout, Quota-Plumbing,
  Cancellation-Token). Approval-Token-Validierung lebt hier
  zusammen mit Quota und Job-Start, weil
  [`ApprovedRetryService`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/ApprovedRetryService.kt)
  diese Schritte atomar koppelt.
- **F.5** ist die E2E-Pinnung; hängt an F.4.

Natürliche Reihenfolge: **F.1 → F.2** (ohne externe Trigger).
Sobald atomic-preserve C geliefert ist, **F.3**. Sobald A + D + E
geliefert sind, **F.4 → F.5**.

## 7. Risiken

1. **Tenant-Modell-Drift.** Der MVP fährt mit `tenant: "default"`
   bis ein echtes Tenant-Modell kommt
   ([`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
   §5 D Risiken). Mitigation: Tenant kommt durchgängig aus
   `principal.effectiveTenantId` (§3.3, kein Wire-Feld), und der
   URI-Konsistenz-Check liefert `VALIDATION_ERROR` für ConnectionRefs
   bzw. `TENANT_SCOPE_DENIED` für SchemaRefs (Risk #8) —
   beides übersteht den Wechsel auf ein echtes Tenant-Modell ohne
   Wire-Änderung.
2. **Source-Schema-Drift bei `sourceSchemaRef`.** Ein gepinntes
   Source-Schema kann gegen Target-Drift veralten zwischen Plan
   (oder Approval) und Apply. Mitigation: der Apply-Worker
   berechnet beim Apply den `planFingerprint` neu und vergleicht den
   Target-Reverse-Hash gegen den im Plan eingefrorenen — Drift →
   `SCHEMA_MIGRATE_ATOMIC_FAILURE` mit
   `details.kind = TARGET_DRIFT_DETECTED`. Da der
   `payloadFingerprint` keine Reverse-Hashes enthält (§3.7), bleibt
   die Identität des Replay-Jobs stabil — Drift wird als
   Job-Failure und nicht als IDEMPOTENCY_CONFLICT propagiert.
3. **Approval-Replay-Drift.** Approval-Grants binden an Principal
   + Tenant + Tool + `payloadFingerprint` (also refs + options).
   Solange Caller dieselbe Source-/Target-Ref + dieselben Options
   beibehält, bleibt der Grant über DB-Drift gültig — Drift wird
   sichtbar im `planFingerprint` und im Apply-Job-Status (Risk #2),
   nicht im Token-Replay. Operatoren, die jedes Mal explizite
   Plan-Sign-offs wollen, müssen vor Apply einen `dryRun`-Refresh
   einplanen.
4. **Atomic-Preserve-Failure-Bucket erlaubt nur ein generisches
   Mapping am Wire.** Operator-Werkzeuge müssen `detail.kind`
   parsen, um zwischen `PROBE_FAILED`, `RESTORE_FAILED`,
   `LOCK_ESCALATION` etc. zu unterscheiden. Mitigation:
   `detail.kind`-Vokabular ist in §3.8 dokumentiert; F.5 pinnt
   mindestens drei Varianten.
5. **Quota-Granularität nicht targetRef-spezifisch.** Der MVP
   benutzt das bestehende `ACTIVE_JOBS`-Quota
   ([`JobStartOrchestrator.reserveQuota`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt)),
   das tenant/principal/operation-weit ist. Ein Betreiber kann
   damit „max N parallele Migrate-Jobs pro Ziel-DB" **nicht**
   ausdrücken, sondern nur „max N parallele Migrate-Jobs pro
   Principal pro Tenant". Mitigation: eigenständiger Service-Mode-
   Quota-Slice für targetRef-Granularität, sobald Betreiberfeedback
   sie verlangt; bis dahin schützt der `SERVICE_POOL_EXHAUSTED`-
   Pfad aus F.4 (Pool-Borrow-Timeout pro `targetConnectionRef`) die
   Ziel-DB.
6. **Sub-Pool pro `targetConnectionRef` kann viele Pools
   erzeugen.** Bei vielen Tenants × vielen Targets explodiert die
   Pool-Anzahl. Mitigation: Pool-Lifecycle aus
   [`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
   §5 C (Idle-Eviction) ist Pflicht-Akzeptanzkriterium.
7. **JSON-Schema vs. Handler-Validation-Split.** Manche Constraints
   leben im JSON-Schema (Pflichtfelder, Bounds), andere im Handler
   (Tenant-Match, ConnectionRef-Resolution, Approval-Token).
   Mitigation: F.1 verifiziert das JSON-Schema-Set, F.2 verifiziert
   die Handler-Validation-Set; Reject-Cases-Tests in beiden
   Sub-Slices.
8. **Asymmetrisches Tenant-Mismatch-Mapping.** ConnectionRefs
   liefern `VALIDATION_ERROR` über
   [`ValidationErrorException`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt)
   (Bestand:
   [`DataTransferStartHandler`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/DataTransferStartHandler.kt)
   Zeile 292ff); SchemaRefs liefern `TENANT_SCOPE_DENIED` über die
   bereits existierende
   [`TenantScopeDeniedException`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt)
   (Bestand:
   [`SchemaSourceResolver`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/SchemaSourceResolver.kt)
   Zeile 108). Beide Pfade sind Bestands-Pattern, aber **Wire-
   Inkonsistenz** zwischen den Start-Tools-Familien. Eine
   einheitliche Migration aller ConnectionRef-Pfade auf
   `TenantScopeDeniedException` ist ein eigener Folge-Slice
   (`data_transfer_start`, `data_import_start`,
   `data_profile_start`, `schema_reverse_start` gleichzeitig
   mitziehen), um nicht eine zweite Inkonsistenz zwischen Familien
   einzuführen.

## 8. Was bewusst **kein** Teil dieser Vorabklärung ist

- **REST 1.2.0** und **gRPC 1.1.8** Migrate-Endpoints. Diese
  Roadmap-Positionen referenzieren denselben Service-Mode-
  Backbone, aber die Wire-Verträge sind RPC-eigen — sie laufen in
  eigenen Vorabklärungen, sobald 1.1.8 / 1.2.0 dran sind.
- **CLI-`schema migrate --execute --service-mode`-Subkommando.**
  Der CLI-Pfad bleibt regressionsfrei
  ([`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
  §4.3) und ohne Job-Worker. Ein zukünftiger Service-Mode-CLI-
  Adapter wäre ein eigener Slice.
- **Schema-Versionierung / Schema-Drift-Detection.** `sourceSchemaRef`
  pins nur die Source-Sicht über den bestehenden SchemaStore. Eine
  allgemeine Drift-Strategie für Source/Target-Versionen,
  Plan-Verfall oder drift-tolerante Apply-Regeln gehört in einen
  Schema-Versioning-Slice, nicht hierher.

## 9. Verweise

- [`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
  — Service-Mode-JVM-Verträge, die diese Tool-Spec konsumieren.
  §5 C/D/F sind die Sub-Slices, die ohne diese Spec nicht starten.
- [`spec/mcp-server.md`](../../../spec/mcp-server.md) §661ff —
  `data_transfer_start`/`data_import_start`-Pattern als nächste
  Analogie.
- [`spec/ki-mcp.md`](../../../spec/ki-mcp.md) — zweistufiges
  Pattern (`procedure_transform_plan` /
  `procedure_transform_execute`) als bewusst nicht übernommene
  Kontrastfolie zu §3.1.
- [`done/ImpPlan-0.9.6-F.md`](../done/ImpPlan-0.9.6-F.md) —
  Policy-Gate-Architektur (Approval + Audit + Quota), die
  `schema_migrate_start` übernehmen kann.
- [`done/atomic-preserve-followups.md`](../done/atomic-preserve-followups.md)
  — Atomic-Preserve-Garantien, die der Tool-Vertrag respektieren
  muss.
- [`in-progress/carveout.md`](../in-progress/carveout.md) §62, §113
  — Carve-Out-Einträge, die diese Vorabklärung adressieren.
