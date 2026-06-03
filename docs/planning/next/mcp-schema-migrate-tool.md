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
  "$schema": "http://json-schema.org/draft-07/schema#",
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
    "sourceArtifactRef": {
      "type": "string",
      "pattern": "^dmigrate://tenants/[a-z0-9-]+/artifacts/[A-Za-z0-9._-]+$"
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
    { "required": ["sourceArtifactRef"] }
  ]
}
```

`tenant` ist **kein** Wire-Feld — analog
[`DataTransferStartHandler`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/DataTransferStartHandler.kt)
liest der Handler `context.principal.effectiveTenantId` und prüft
beide Resource-Refs gegen diesen abgeleiteten Tenant. Damit gibt es
genau eine Tenant-Quelle.

Semantische Validation, die das JSON-Schema nicht trägt, läuft im
Handler (analog
[`spec/mcp-server.md`](../../../spec/mcp-server.md) §694ff):

- `sourceConnectionRef` / `sourceArtifactRef` / `targetConnectionRef`
  müssen unter dem aus dem Principal abgeleiteten Tenant auflösen.
  Nicht auflösbar → `RESOURCE_NOT_FOUND`. Tenant-Segment der URI
  passt nicht zum Principal-Tenant → `TENANT_SCOPE_DENIED`.
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

### 3.2 Schema-Quelle — `sourceConnectionRef` oder `sourceArtifactRef`

Der primäre Pfad ist Live-Reverse über `sourceConnectionRef`,
konsistent mit `data_transfer_start`.

`sourceArtifactRef` ist die optionale Alternative für ein vorher
exportiertes Schema. Das pins ein Source-Schema gegen Drift und ist
nützlich, wenn Source und Target bewusst mit einer drift-toleranten
Migrate-Strategie betrieben werden.

`schemaRef` mit neuem `dmigrate://.../schemas/...`-Namespace wird für
diesen Slice verworfen. Der zusätzliche Resource-Resolution-Pfad bringt
heute keinen Nutzen und würde neues Wiring erzwingen.

Validierung:

- `targetConnectionRef` ist Pflicht.
- Exakt eine Source ist Pflicht: entweder `sourceConnectionRef` oder
  `sourceArtifactRef`.
- Beide Source-Felder gesetzt oder beide fehlend liefert
  `VALIDATION_ERROR`.

### 3.3 Tenant-Modell — Principal-Ableitung + URI-Konsistenz-Check

`tenant` ist **kein** Wire-Feld. Der Handler liest
`context.principal.effectiveTenantId` als Single-Source-of-Truth und
prüft `sourceConnectionRef` oder `sourceArtifactRef` sowie
`targetConnectionRef` gegen diesen Tenant — exakt wie
[`DataTransferStartHandler`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/DataTransferStartHandler.kt)
Zeile 75-84.

Tenant-Segment der URI passt nicht zum Principal-Tenant →
`TENANT_SCOPE_DENIED` (bestehender
[`ToolErrorCode`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorCode.kt)).
Ein eigener „Mismatch"-Code wird bewusst nicht eingeführt.

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

- `sha256(sourceReverse)` bei `sourceConnectionRef`, oder dem
  kanonischen Schema-Hash des `sourceArtifactRef`.
- `sha256(targetReverse)`.
- `sourceConnectionRef` oder `sourceArtifactRef`.
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
  `sourceArtifactRef` oder `targetConnectionRef` löst nicht auf.
- `TENANT_SCOPE_DENIED` — Tenant-Segment einer Ref ≠
  `principal.effectiveTenantId` (§3.3).
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

### Sub-Slice F.1 — Tool-Schema-Registry

**Ziel**: `schema_migrate_start` ist im MCP-Tool-Schema-Registry
mit dem Request-Schema aus §2.1 angemeldet.

**Akzeptanzkriterien**:
- [ ] Neuer Eintrag in `McpToolSchemas.kt` mit dem vollständigen
  JSON-Schema aus §2.1 (`additionalProperties: false`, `oneOf`
  für Source-Variante, Bounds für `lockTimeoutMs`).
- [ ] Schema-Validator-Test pinnt fünf Reject-Cases: fehlende
  Pflichtfelder, beide Source-Felder gesetzt, beide Source-Felder
  fehlend, `lockTimeoutMs` außerhalb `[10, 60_000]`, fremde
  Properties.
- [ ] Schema-Validator-Test pinnt zwei Accept-Cases: minimaler
  Happy-Path mit `sourceConnectionRef`, minimaler Happy-Path mit
  `sourceArtifactRef`.
- [ ] `ToolErrorCode`-Enum
  ([`hexagon/core/src/main/kotlin/.../ToolErrorCode.kt`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorCode.kt))
  um die drei migrate-spezifischen Werte erweitert:
  `SCHEMA_MIGRATE_LOCK_TIMEOUT`, `SERVICE_POOL_EXHAUSTED`,
  `SCHEMA_MIGRATE_ATOMIC_FAILURE`. Bestehende Codes
  (`POLICY_REQUIRED`, `IDEMPOTENCY_CONFLICT`, `RATE_LIMITED`,
  `TENANT_SCOPE_DENIED`, `RESOURCE_NOT_FOUND`, `VALIDATION_ERROR`)
  werden wiederverwendet — siehe §3.8.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/McpToolSchemas.kt`
- `hexagon/core/src/main/kotlin/dev/dmigrate/server/core/error/ToolErrorCode.kt`
- Neuer Test: `adapters/driving/mcp/src/test/kotlin/dev/dmigrate/mcp/schema/SchemaMigrateStartSchemaTest.kt`

**Dependencies**: keine.

**Risiken**: niedrig — JSON-Schema-Registrierung ist mechanisch;
Enum-Erweiterung berührt nur den Wire-Kontrakt.

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
- [ ] Source-Auflösung: `sourceConnectionRef` triggert Live-Reverse,
  `sourceArtifactRef` triggert Artefakt-Load — beide gegen den
  aus dem Principal abgeleiteten Tenant.
- [ ] Mismatch zwischen URI-Tenant und Principal-Tenant liefert
  `TENANT_SCOPE_DENIED` (§3.3).
- [ ] Unauflösbare Refs liefern `RESOURCE_NOT_FOUND`.
- [ ] `dryRun: true` liefert die Antwort aus §2.2 (Plan-Objekte,
  Fingerprints, ExecutionMeta). Kein DB-Connection-Borrow für den
  Target-Apply, kein Dialekt-Lock, keine Job-Worker-Pipeline.
- [ ] `dryRun: false` liefert vorerst `UNSUPPORTED_TOOL_OPERATION`
  (bestehender Code) — Apply-Pfad kommt mit F.3/F.4/F.5.
- [ ] Handler registriert in `OperationalMcpRegistries`.
- [ ] Handler-Unit-Test pinnt sechs Pfade: Happy-dryRun-mit-
  ConnectionRef, Happy-dryRun-mit-ArtifactRef, Tenant-Mismatch
  (`TENANT_SCOPE_DENIED`), fehlender `targetConnectionRef`
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

### Sub-Slice F.3 — Policy-Gate-Wiring (Apply)

**Ziel**: Approval + Audit + Quota für den Apply-Pfad. Replaced
NOT_IMPLEMENTED-Stub aus F.2 durch einen Pre-Apply-Pfad, der noch
keinen Pool und keinen Lock anfasst.

**Akzeptanzkriterien**:
- [ ] `dryRun: false` ohne passenden `approvalToken` →
  `POLICY_REQUIRED` mit `details` (`approvalRequestId`,
  `correlationKind`, `correlationKey`, `payloadFingerprint`,
  `requiredScopes`, `reasons`) — exakt der Pfad aus
  [`JobStartHandlerSupport.toToolCallOutcome`](../../../adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/JobStartHandlerSupport.kt).
- [ ] Idempotency-Replay mit anderem `payloadFingerprint` →
  `IDEMPOTENCY_CONFLICT`.
- [ ] Quota-Reservation läuft über den bestehenden
  [`JobStartOrchestrator.reserveQuota`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt)
  mit `QuotaKey(tenantId, ACTIVE_JOBS, principalId,
  operation="schema_migrate_start")`. Erschöpfung →
  `RATE_LIMITED` (mit `retryAfter`/`current`/`limit` in
  `details`).
- [ ] Audit-Eintrag enthält `payloadFingerprint`,
  `planFingerprint`, `tenant`, `principal`,
  `approvalToken`-Redaktion.
- [ ] Apply liefert weiterhin `UNSUPPORTED_TOOL_OPERATION`
  (Sub-Slice F.5 ergänzt den Rest).
- [ ] Handler-Test pinnt die drei Reject-Pfade + den
  Approval-/Quota-OK-Pfad.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SchemaMigrateStartHandler.kt`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/audit/...`

**Dependencies**: F.2 plus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 D (Quota-Plumbing).

**Risiken**: mittel — bestehende `ACTIVE_JOBS`-Quota-Granularität
ist tenant/principal-weit, nicht targetRef-spezifisch (§7 Risiko
#5). Tenant-Modell-Drift bleibt das Sekundärrisiko.

### Sub-Slice F.4 — Connection-Sub-Pool-Wiring

**Ziel**: Apply-Pfad bekommt eine eigene Connection aus dem
MigratePoolFactory (Sub-Slice C). `dryRun` greift weiter nicht zum
Pool.

**Akzeptanzkriterien**:
- [ ] `MigratePoolFactory.acquire(targetConnectionRef)` läuft im
  Apply-Pfad vor dem `BEGIN`.
- [ ] Borrow-Timeout → `SERVICE_POOL_EXHAUSTED` (anstatt generic
  DB-Fehler).
- [ ] `dryRun: true` führt keinen Pool-Borrow aus.
- [ ] Vertragstest: zwei parallele Apply-Calls gegen denselben
  `targetConnectionRef` mit `maximumPoolSize = 1` → zweiter
  blockt bis `connectionTimeoutMs`, dann
  `SERVICE_POOL_EXHAUSTED`.
- [ ] `make ci` grün.

**Betroffene Dateien**:
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SchemaMigrateStartHandler.kt`
- MCP-Wiring (Pool-Bind)

**Dependencies**: F.2 plus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 C (Connection-Sub-Pool).

**Risiken**: niedrig — Pool-Adapter ist isoliert.

### Sub-Slice F.5 — Apply-Pfad

**Ziel**: Job-Worker führt den `dryRun`-Plan aus F.2 in einer
Transaktion unter Dialekt-Lock aus. Cancel + Lock-Timeout +
Atomic-Preserve-Failures mappen auf §3.8.

**Akzeptanzkriterien**:
- [ ] Job-Worker komponiert
  `SchemaMigrateRunner` mit `lockTimeoutMs` aus dem Request, dem
  Pool-Lease aus F.4 und dem CancellationToken aus
  `JobCancelHandler`.
- [ ] Job-Start liefert die Antwort aus §2.3 (jobId, resourceUri,
  ExecutionMeta).
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
  `payloadFingerprint`) liefert dieselbe `jobId`.
- [ ] `make ci` grün inkl. neuer Integrationstests pro Dialekt.

**Betroffene Dateien**:
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/SchemaMigrateStartHandler.kt`
- Job-Worker-Wiring
- IT-Tests pro Dialekt

**Dependencies**: F.2 + F.3 + F.4 plus
[`../open/atomic-preserve-service-mode.md`](../open/atomic-preserve-service-mode.md)
§5 A (Lock-Timeout) + §5 E (Cancellation-Token).

**Risiken**: hoch — zusammengesetzter Atomicity-Vertrag,
Cancel-Mid-Apply, Failure-Klassen-Mapping. Wichtigster Slice für
Sub-Phase-Reviews.

### Sub-Slice F.6 — E2E-Scenario-Test

**Ziel**: `McpSchemaMigrateStartScenarioTest` gegen file-SQLite
(Operational-MCP-Harness aus Quality-Coverage-Expansion Phase F1)
pinnt das volle Vertragsbündel.

**Akzeptanzkriterien**:
- [ ] Scenario 1: Happy-dryRun-mit-ConnectionRef → §2.2.
- [ ] Scenario 2: Happy-Apply → Job durchgelaufen, Plan angewendet.
- [ ] Scenario 3: Idempotency-Replay → dieselbe `jobId`.
- [ ] Scenario 4: Replay mit fremdem `payloadFingerprint` →
  `IDEMPOTENCY_CONFLICT`.
- [ ] Scenario 5: Quota-Exhaustion → `RATE_LIMITED`.
- [ ] Scenario 6: Cancel-Mid-Apply → Job-Status `CANCELLED` via
  `resources/read` + Rollback (kein Wire-Error vom Start-Tool).
- [ ] Scenario 7: Lock-Timeout → Job-Status `FAILED` mit
  `SCHEMA_MIGRATE_LOCK_TIMEOUT` im Failure-Detail.
- [ ] Scenario 8: Tenant-Mismatch → `TENANT_SCOPE_DENIED`.
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
   URI-Konsistenz-Check liefert `TENANT_SCOPE_DENIED` — beides
   übersteht den Wechsel auf ein echtes Tenant-Modell ohne
   Wire-Änderung.
2. **Source-Schema-Drift bei `sourceArtifactRef`.** Ein gepinntes
   Source-Schema kann gegen Target-Drift veralten zwischen Plan
   (oder Approval) und Apply. Mitigation: `planFingerprint`
   speichert die Reverse-Hashes; der Apply-Pfad vergleicht den
   Target-Reverse-Hash zum Apply-Zeitpunkt gegen den im Plan
   eingefrorenen — Drift → `SCHEMA_MIGRATE_ATOMIC_FAILURE` mit
   `detail.kind = TARGET_DRIFT_DETECTED`.
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
- **Schema-Versionierung / Schema-Drift-Detection.** `sourceArtifactRef`
  pins nur die Source-Sicht. Eine allgemeine Drift-Strategie für
  Source/Target-Versionen, Plan-Verfall oder drift-tolerante Apply-
  Regeln gehört in einen Schema-Versioning-Slice, nicht hierher.

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
