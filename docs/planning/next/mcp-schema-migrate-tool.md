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

Das Doc trägt den Sub-Slice-Schnitt F.1-F.6 (§5), den Wire-Vertrag
V1 (§2) und einen Strawman zu den acht Produkt-/Vertrags-Fragen
(§3). Es ist damit bereit, sobald C oder D aus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 dran ist, in `in-progress/` zu wandern und die Sub-Slices F.1-F.6
nacheinander zu liefern.

**Aktivierungsbedingung** (Move nach `in-progress/`): mindestens
einer der C/D-Sub-Slices aus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 startet — F.3 hängt an D, F.4 hängt an C. F.1 (Tool-Schema) und
F.2 (dryRun-Handler) sind ohne Service-Mode-Vorarbeit
implementierbar und können bei Bedarf vorgezogen werden.

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
  Schema-Artefakt kommen. Die JDBC-URLs leben nie im Wire-Vertrag
  (siehe Fingerprint-Vertrag §700ff).
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
      "pattern": "^dmigrate://tenants/[a-z0-9-]+/connections/[A-Za-z0-9._-]+$"
    },
    "sourceSchemaRef": {
      "type": "string",
      "pattern": "^dmigrate://tenants/[a-z0-9-]+/schemas/[A-Za-z0-9._-]+$"
    },
    "targetConnectionRef": {
      "type": "string",
      "pattern": "^dmigrate://tenants/[a-z0-9-]+/connections/[A-Za-z0-9._-]+$"
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
- Approval-Flow folgt dem bestehenden Job-Start-Pattern: ohne
  passenden `approvalToken` liefert der Handler
  `POLICY_REQUIRED` mit Challenge-`details` (analog
  [`JobStartHandlerSupport.toToolCallOutcome`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/JobStartHandlerSupport.kt)).
  Der Caller reicht den Token im Folge-Call nach. Bei `dryRun=true`
  ist `approvalToken` egal — der Pfad geht keine Job-Start-Pipeline
  durch.

### 2.2 Response — `dryRun: true`

Sync-Antwort, kein Job-Start. Liefert das Plan-Artefakt samt
Fingerprints, ohne `BEGIN`/Dialekt-Lock/Probe/Apply/Restore:

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
    "requestId": "req-…",
    "tenant":    "acme",
    "principal": "did:dmigrate:user:…"
  }
}
```

### 2.3 Response — `dryRun: false` (Apply)

Symmetrischer Job-Start-Envelope wie `data_transfer_start`
([`spec/mcp-server.md`](../../../spec/mcp-server.md) §661ff). Der
Caller pollt Status über `resources/read` am `resourceUri`:

```jsonc
{
  "jobId":       "job-…",
  "resourceUri": "dmigrate://tenants/acme/jobs/job-…",
  "executionMeta": {
    "requestId": "req-…",
    "tenant":    "acme",
    "principal": "did:dmigrate:user:…"
  }
}
```

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
ist `{ key, value }` mit String-Wert), `requestId: String?`. Beispiel
für einen Lock-Timeout:

```jsonc
{
  "code":    "SCHEMA_MIGRATE_LOCK_TIMEOUT",
  "message": "Acquiring dialect lock exceeded lockTimeoutMs=30000",
  "details": [
    { "key": "lockTimeoutMs", "value": "30000" },
    { "key": "dialect",       "value": "postgresql" }
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

### 3.3 Tenant-Modell — Principal-Ableitung + URI-Konsistenz-Check

`tenant` ist **kein** Wire-Feld. Der Handler liest
`context.principal.effectiveTenantId` als Single-Source-of-Truth und
prüft `sourceConnectionRef` oder `sourceSchemaRef` sowie
`targetConnectionRef` gegen diesen Tenant — exakt wie
[`DataTransferStartHandler`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/DataTransferStartHandler.kt)
Zeile 75-84.

Tenant-Segment der URI passt nicht zum Principal-Tenant →
`VALIDATION_ERROR` mit `tenant prefix mismatch`-Violation. Dieser
Pfad spiegelt 1:1 das Verhalten von `DataTransferStartHandler`
Zeile 292ff: die `ValidationErrorException`-Klasse
([`ApplicationException.kt:73`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt))
mappt jeden Resource-Ref-Tenant-Mismatch auf den
`VALIDATION_ERROR`-Code. Ein eigener `TENANT_SCOPE_DENIED`-Pfad ist
**bewusst nicht** Teil dieses Slices — siehe Risk #8.

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

- `dryRun: false` ohne passenden `approvalToken` →
  `POLICY_REQUIRED`-Antwort mit `details` (`approvalRequestId`,
  `correlationKind`, `correlationKey`, `payloadFingerprint`,
  `requiredScopes`, `reasons`). Der Caller reicht den Token im
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

Der `payloadFingerprint` besteht aus:

- `sha256(sourceReverse)` bei `sourceConnectionRef`, oder
  `SchemaIndexEntry.hash` bei `sourceSchemaRef` (kommt direkt aus
  dem SchemaStore — keine erneute Reverse-Berechnung).
- `sha256(targetReverse)`.
- `sourceConnectionRef` oder `sourceSchemaRef`.
- `targetConnectionRef`.
- `tenant` (aus dem Principal abgeleitet, kein Wire-Feld; siehe §3.3).
- `principal`.
- `canonical(options)`.

Die Plan-DDL selbst ist Konsequenz dieser Eingaben, nicht Eingabe des
Jobs. Sie gehört deshalb nicht in den `payloadFingerprint`; sie kann
aber in den `planFingerprint` eingehen.

`lockTimeoutMs` ist ein Lieferungsparameter, kein
Identitätsparameter. Ein Replay desselben Migrate-Jobs mit anderem
Lock-Timeout bleibt semantisch derselbe Job und verändert den
`payloadFingerprint` nicht.

### 3.8 Failure-Klassifikation am Wire — Bestehende Codes + drei Neue

Wo immer möglich, mappt der Migrate-Pfad auf die existierenden
[`ToolErrorCode`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorCode.kt)-
Werte; nur die migrate-spezifischen Klassen kommen als neue Codes
hinzu (Enum-Erweiterung als Akzeptanzkriterium von F.1).

**Bestehende Codes:**

- `VALIDATION_ERROR` — Schema-Verstoß: fehlendes Pflichtfeld,
  `additionalProperties`-Reject, ungültige Bounds (`lockTimeoutMs`
  außerhalb `[10, 60_000]`), beide Source-Felder gesetzt oder beide
  fehlend.
- `RESOURCE_NOT_FOUND` — `sourceConnectionRef`,
  `sourceSchemaRef` oder `targetConnectionRef` löst nicht auf.
- `VALIDATION_ERROR` für Tenant-Mismatch — Tenant-Segment einer
  Ref ≠ `principal.effectiveTenantId`. Details: einer
  `ValidationViolation` mit `field`/`reason` (§3.3, Risk #8).
- `POLICY_REQUIRED` — Apply ohne `approvalToken` oder mit
  ungültigem Token. `details` enthalten `approvalRequestId`,
  `correlationKind`, `correlationKey`, `payloadFingerprint`,
  `requiredScopes`, `reasons` (§3.4).
- `IDEMPOTENCY_CONFLICT` — Replay mit gleichem
  `idempotencyKey`, aber abweichendem `payloadFingerprint`.
- `RATE_LIMITED` — `JobStartOrchestrator.reserveQuota` liefert
  `RateLimited` (§3.6). `details` enthalten `retryAfter`,
  `current`, `limit` — wie alle anderen Start-Tools.

**Neue Codes (Enum-Erweiterung in F.1):**

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

**Cancel ist kein Wire-Error.** Externer Cancel via
`job_cancel` setzt den Job-Status auf `CANCELLED`; der Apply-Caller
sieht das ausschließlich über `resources/read` am `resourceUri` (wie
bei `data_transfer_start`). Es gibt **keinen**
`SCHEMA_MIGRATE_CANCELLED`-Code im Start-Tool-Envelope.

Stacktraces bleiben ausschließlich server-side im Audit- oder
Log-Kontext und erscheinen nie im Wire-Envelope.

## 4. Leitentscheidungen

### 4.1 Sub-Slice-Schnitt folgt dem Job-Start-Pattern

Die Sub-Slices wachsen nicht entlang der DDL-Phasen, sondern entlang
der Job-Start-Tool-Architektur aus
[`done/ImpPlan-0.9.6-F.md`](../done/ImpPlan-0.9.6-F.md):
Tool-Schema → Handler-Skeleton → Policy-Gate → Pool/Cancel-Wiring →
Apply-Job → E2E. Das verteilt das Risiko der Atomic-Preserve-
Garantie auf einen einzigen Sub-Slice (F.5) statt es über mehrere zu
streuen.

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

Die Sub-Slices F.1-F.6 schließen den Service-Mode-Vertrags-Track
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
  - **Output** = `oneOf` aus dem `dryRun: true`-Envelope (§2.2)
    und dem Job-Start-Envelope (§2.3). Discriminator ist das
    `dryRun`-Feld im Request: `dryRun=true` → dryRun-Response,
    sonst Job-Start-Response.
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
  `"schema_migrate_start" to jobStart`. Vertragstest:
  `tools/list` listet das neue Tool inkl. `requiredScopes`,
  `inputSchema`, `outputSchema`.
- [ ] Neue Einträge in `McpContractRegistries`:
  - `TITLES["schema_migrate_start"]`
  - `DESCRIPTIONS["schema_migrate_start"]`
  - `ERROR_CODES["schema_migrate_start"]` mit dem Set aus §3.8
    (`POLICY_REQUIRED`, `IDEMPOTENCY_CONFLICT`, `RATE_LIMITED`,
    `VALIDATION_ERROR`, `RESOURCE_NOT_FOUND`,
    `SCHEMA_MIGRATE_LOCK_TIMEOUT`, `SERVICE_POOL_EXHAUSTED`,
    `SCHEMA_MIGRATE_ATOMIC_FAILURE`,
    `UNSUPPORTED_TOOL_OPERATION` für den Pre-F.5-Pfad).
- [ ] `ToolErrorCode`-Enum
  ([`hexagon/core/src/main/kotlin/.../ToolErrorCode.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorCode.kt))
  um die drei migrate-spezifischen Werte erweitert:
  `SCHEMA_MIGRATE_LOCK_TIMEOUT`, `SERVICE_POOL_EXHAUSTED`,
  `SCHEMA_MIGRATE_ATOMIC_FAILURE`. Bestehende Codes
  (`POLICY_REQUIRED`, `IDEMPOTENCY_CONFLICT`, `RATE_LIMITED`,
  `RESOURCE_NOT_FOUND`, `VALIDATION_ERROR`,
  `UNSUPPORTED_TOOL_OPERATION`) werden wiederverwendet — siehe
  §3.8.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/McpToolSchemas.kt`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/McpContractRegistries.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorCode.kt`
- Neuer Test: `adapters/driving/mcp/src/test/kotlin/dev/dmigrate/mcp/schema/SchemaMigrateStartSchemaTest.kt`
- Erweiterung an `McpToolsListContractTest.kt` (oder Pendant) für
  den `tools/list`-Vertrag.

**Dependencies**: keine.

**Risiken**: niedrig — alle vier Eingriffe sind mechanisch und
folgen den bestehenden Job-Start-Tool-Pattern aus
`data_transfer_start` / `data_import_start` / `schema_reverse_start`.

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
- [ ] Mismatch zwischen URI-Tenant und Principal-Tenant liefert
  `VALIDATION_ERROR` mit `tenant prefix mismatch`-Violation
  (§3.3, Bestands-Pattern via
  [`ValidationErrorException`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt)).
- [ ] Unauflösbare Refs liefern `RESOURCE_NOT_FOUND` via
  `ResourceNotFoundException`.
- [ ] `dryRun: true` liefert die Antwort aus §2.2 (Plan-Objekte,
  Fingerprints, ExecutionMeta). Kein DB-Connection-Borrow für den
  Target-Apply, kein Dialekt-Lock, keine Job-Worker-Pipeline.
- [ ] `dryRun: false` liefert vorerst `UNSUPPORTED_TOOL_OPERATION`
  (bestehender Code) — Apply-Pfad kommt mit F.3/F.4/F.5.
- [ ] Handler registriert in `OperationalMcpRegistries`.
- [ ] Handler-Unit-Test pinnt sechs Pfade: Happy-dryRun-mit-
  ConnectionRef, Happy-dryRun-mit-SchemaRef, Tenant-Mismatch
  (`VALIDATION_ERROR`), fehlender `targetConnectionRef`
  (`VALIDATION_ERROR`), Apply-Stub
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

### Sub-Slice F.3 — Approval-Wiring (Pre-Apply)

**Ziel**: Approval-Token-Validierung für den Apply-Pfad. Replaced
nicht den Apply-Stub aus F.2 — F.5 liefert sowohl Quota-Reservation
als auch den Job-Start atomar.

**Begründung für den Schnitt**:
[`JobStartOrchestrator.start`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt)
führt `reserveQuota` (Zeile 260) und `jobStartTransaction.commit`
(Zeile 276) im selben kritischen Pfad aus — RateLimited gibt vor dem
Commit sofort zurück, ohne Job-Erzeugung. Es gibt also keinen
sicheren „Quota reservieren, aber keinen Job starten"-Pfad. Approval
und Quota werden deshalb getrennt: Approval ist Pre-Job-Start
(Token-Challenge), Quota ist Teil des Job-Starts in F.5.

**Akzeptanzkriterien**:
- [ ] `dryRun: false` ohne passenden `approvalToken` →
  `POLICY_REQUIRED` mit `details` (`approvalRequestId`,
  `correlationKind`, `correlationKey`, `payloadFingerprint`,
  `requiredScopes`, `reasons`) — exakt der Pfad aus
  [`JobStartHandlerSupport.toToolCallOutcome`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/JobStartHandlerSupport.kt).
- [ ] `dryRun: false` mit gültigem `approvalToken` → weiter zum
  Apply-Stub `UNSUPPORTED_TOOL_OPERATION` (F.5 ersetzt den Stub).
- [ ] Audit-Eintrag pinnt `payloadFingerprint`, `planFingerprint`,
  `tenant`, `principal`, `approvalToken`-Redaktion — analog
  bestehender Start-Tools.
- [ ] Handler-Test pinnt drei Pfade: Pre-Approval-Reject
  (`POLICY_REQUIRED`), Approval-OK + Apply-Stub
  (`UNSUPPORTED_TOOL_OPERATION`), und ein
  `dryRun: true` ignoriert den Token-Pfad komplett.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SchemaMigrateStartHandler.kt`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/audit/...`

**Dependencies**: F.2. Keine Abhängigkeit zu `atomic-preserve` §5
D — die kommt erst in F.5 (Quota im Commit-Pfad).

**Risiken**: niedrig — Approval-Token-Flow ist Bestands-Pattern,
keine neue Verdrahtung.

### Sub-Slice F.4 — Connection-Sub-Pool-Wiring (Worker)

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

### Sub-Slice F.5 — Apply-Pfad (Quota + Job-Start + Worker)

**Ziel**: Apply ist ein echter Job-Start. Quota-Reservation, Commit
und Worker-Dispatch laufen atomar im
[`JobStartOrchestrator`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt)-
Pfad. Der Worker führt den Plan in einer Transaktion unter
Dialekt-Lock aus; Cancel + Lock-Timeout + Atomic-Preserve-Failures
mappen auf §3.8.

**Akzeptanzkriterien**:
- [ ] Handler ersetzt den `UNSUPPORTED_TOOL_OPERATION`-Stub aus
  F.3 durch einen `JobStartRequest`, der den
  bestehenden `JobStartOrchestrator.start`-Pfad fährt.
- [ ] Quota-Reservation läuft synchron im Commit-Pfad mit
  `QuotaKey(tenantId, ACTIVE_JOBS, principalId,
  operation="schema_migrate_start")`. RateLimited liefert
  vor dem Commit `RATE_LIMITED` (synchroner Start-Tool-Fehler,
  kein Job).
- [ ] Job-Start liefert bei Erfolg die Antwort aus §2.3
  (`jobId`, `resourceUri`, `executionMeta`).
- [ ] Job-Worker komponiert `SchemaMigrateRunner` mit
  `lockTimeoutMs` aus dem Request, dem Pool-Lease aus F.4 und
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

**Dependencies**: F.2 + F.3 + F.4 plus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 A (Lock-Timeout), §5 D (Quota-Plumbing), §5 E
(Cancellation-Token).

**Risiken**: hoch — zusammengesetzter Atomicity-Vertrag
(Quota+Commit+Worker), Cancel-Mid-Apply, Failure-Klassen-Mapping.
Wichtigster Slice für Sub-Phase-Reviews.

### Sub-Slice F.6 — E2E-Scenario-Test

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
- [ ] Scenario 5: Quota-Exhaustion → synchroner `RATE_LIMITED`
  (kein Job).
- [ ] Scenario 6: Cancel-Mid-Apply → Job-Status `CANCELLED` via
  `resources/read` + Rollback (kein Wire-Error vom Start-Tool).
- [ ] Scenario 7: Lock-Timeout → Job-Status `FAILED` mit
  `SCHEMA_MIGRATE_LOCK_TIMEOUT` im Failure-Detail.
- [ ] Scenario 8: Pool-Exhaustion (parallele Apply-Jobs gegen
  denselben Target) → zweiter Job `FAILED` mit
  `SERVICE_POOL_EXHAUSTED` im Failure-Detail.
- [ ] Scenario 9: Tenant-Mismatch → `VALIDATION_ERROR` mit
  `tenant prefix mismatch`-Violation.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- Neuer Test:
  `adapters/driving/mcp/src/test/kotlin/dev/dmigrate/mcp/integration/McpSchemaMigrateStartScenarioTest.kt`

**Dependencies**: F.5.

**Risiken**: niedrig — Harness existiert, das Test-Profil ist
mechanische Komposition.

## 6. Dependency-Graph

```
F.1 (Tool-Schema)                ──┐
                                   ├──→ F.2 (dryRun-Handler)
atomic-preserve A (Lock-Timeout)   │           │
atomic-preserve E (Cancel-Token)   │           │
                                   │           ↓
atomic-preserve C (Sub-Pool)       ├──→ F.4 (Pool-Wiring)
                                   │           │
atomic-preserve D (Quota)          ├──→ F.3 (Policy-Gate)
                                   │           │
                                   │           ↓
                                   └──→ F.5 (Apply-Pfad) ──→ F.6 (E2E)
```

- F.1 ist unabhängig — Start-Slice.
- F.2 hängt an F.1.
- F.3 und F.4 sind unabhängig voneinander, hängen aber an F.2 und
  jeweils an einem atomic-preserve-Sub-Slice. Sie können parallel
  laufen, sobald die jeweiligen Service-Mode-Verträge geliefert
  sind.
- F.5 ist die Synthese; sie hängt an allen vier
  Service-Mode-Slices (A/C/D/E) und an F.2 + F.3 + F.4.
- F.6 ist die E2E-Pinnung.

Damit ergibt sich als natürliche Reihenfolge **F.1 → F.2 → (F.3 ‖
F.4) → F.5 → F.6**. Das ist auch die Reihenfolge, in der die
atomic-preserve-Sub-Slices C/D dran sein müssen, bevor F.3 und F.4
starten können.

## 7. Risiken

1. **Tenant-Modell-Drift.** Der MVP fährt mit `tenant: "default"`
   bis ein echtes Tenant-Modell kommt
   ([`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
   §5 D Risiken). Mitigation: Tenant kommt durchgängig aus
   `principal.effectiveTenantId` (§3.3, kein Wire-Feld), und der
   URI-Konsistenz-Check liefert `VALIDATION_ERROR` (Risk #8) —
   beides übersteht den Wechsel auf ein echtes Tenant-Modell ohne
   Wire-Änderung.
2. **Source-Schema-Drift bei `sourceSchemaRef`.** Ein gepinntes
   Source-Schema kann gegen Target-Drift veralten zwischen Plan
   (oder Approval) und Apply. Mitigation: `planFingerprint`
   speichert die Reverse-Hashes; der Apply-Pfad vergleicht den
   Target-Reverse-Hash zum Apply-Zeitpunkt gegen den im Plan
   eingefrorenen — Drift → `SCHEMA_MIGRATE_ATOMIC_FAILURE` mit
   `details.kind = TARGET_DRIFT_DETECTED`.
3. **Approval-Replay-Drift.** Wenn Source- oder Target-Reverse
   zwischen Approval und Apply driftet, ändert sich
   `payloadFingerprint`, und der Approval-Grant wird ungültig →
   `IDEMPOTENCY_CONFLICT`. Das ist gewollt — neue Reverse =
   neuer Job. Mitigation auf Operator-Seite: kurze
   Approval-/Apply-Fenster oder `dryRun`-Refresh vor Apply.
4. **Atomic-Preserve-Failure-Bucket erlaubt nur ein generisches
   Mapping am Wire.** Operator-Werkzeuge müssen `detail.kind`
   parsen, um zwischen `PROBE_FAILED`, `RESTORE_FAILED`,
   `LOCK_ESCALATION` etc. zu unterscheiden. Mitigation:
   `detail.kind`-Vokabular ist in §3.8 dokumentiert; F.6 pinnt
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
8. **Tenant-Mismatch über `VALIDATION_ERROR` statt
   `TENANT_SCOPE_DENIED`.** Konsistent mit dem Bestand
   ([`DataTransferStartHandler`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/DataTransferStartHandler.kt)
   Zeile 292ff: `ValidationErrorException` wirft `VALIDATION_ERROR`
   für jeden Tenant-Prefix-Mismatch). Semantisch wäre
   `TENANT_SCOPE_DENIED` präziser, aber der bestehende
   `ApplicationException`-Baum
   ([`ApplicationException.kt`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/error/ApplicationException.kt))
   kennt keine `TenantScopeDeniedException`. Migration ist ein
   eigenständiger Folge-Slice, der `data_transfer_start`,
   `data_import_start`, `data_profile_start` und
   `schema_reverse_start` gleichzeitig mitnimmt — sonst entsteht
   Wire-Inkonsistenz zwischen den Start-Tools.

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
