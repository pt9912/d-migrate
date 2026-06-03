# MCP-Tool `schema_migrate` / `schema_migrate_start`

**Status**: Vorabklärung (Strawman §3 ergänzt 2026-06-03)

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

Auch [`done/quality-coverage-expansion-plan.md`](../done/quality-coverage-expansion-plan.md) §3.2 + §9 hält fest
(Zeile 384): „Ein MCP-Migrate-Tool (`schema_migrate` oder
`schema_migrate_start`) wäre ein eigener Produkt-/Contract-Slice."

Diese Datei ist die Erstanlage genau dafür — sie führt **noch keinen**
aktivierbaren Scope, sondern dokumentiert den aktuellen Strawman zu
den Produkt-/Vertrags-Fragen, die geklärt sein müssen, bevor der Slice
nach `next/` wandern kann.

**Aktivierungsbedingung** (Move nach `next/`): die unter §3
skizzierten Entscheidungen sind bestätigt oder korrigiert, ein
Wire-Vertrag-Entwurf analog
[`spec/mcp-server.md`](../../../spec/mcp-server.md) §
„`data_import_start` und `data_transfer_start`" liegt vor, und ein
Sub-Slice-Schnitt für Handler + Tool-Schema + Policy-Gate + E2E-Test
ist skizziert.

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

## 2. Skizzierter Wire-Vertrag (Diskussionsbasis)

Diese Felder folgen direkt aus §1. Sie sind kein Vertrag, nur eine
Ausgangsbasis für die Spec-Diskussion:

```jsonc
// schema_migrate_start (Skizze, kein Vertrag)
{
  "idempotencyKey": "smg-2026-06-03-acme-warehouse-v3",
  "tenant": "acme",
  "sourceConnectionRef": "dmigrate://tenants/acme/connections/legacy-pg",
  // oder, exakt eine Source muss gesetzt sein:
  // "sourceArtifactRef": "dmigrate://tenants/acme/artifacts/schema-legacy-pg-20260603",
  "targetConnectionRef": "dmigrate://tenants/acme/connections/warehouse",
  "dryRun": false,
  "lockTimeoutMs": 30000,
  "options": {
    "preserveSequences": true,
    "atomicPreserve": true
  }
}
```

Bei `dryRun: false`: symmetrischer Job-Start-Envelope (`jobId`,
`resourceUri`, `executionMeta.requestId`) — exakt wie
`data_transfer_start`.

Bei `dryRun: true`: Plan-only-Antwort mit Plan-Artefakt,
Per-Objekt-Sichtbarkeit, `payloadFingerprint` und `planFingerprint`;
kein `BEGIN`, kein Dialekt-Lock, kein Probe/Apply/Restore.

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
  `INVALID_REQUEST`.

### 3.3 Tenant-Modell — Explizit + Konsistenz-Check gegen URI

Das `tenant`-Feld bleibt im Wire-Vertrag explizit, analog zum
`data_transfer_start`-Pattern. Der Handler validiert, dass `tenant`
mit dem Tenant-Segment aller Resource-Refs übereinstimmt:
`sourceConnectionRef` oder `sourceArtifactRef` sowie
`targetConnectionRef`.

Ein Mismatch liefert `TENANT_SCOPE_MISMATCH`, nicht einen generischen
Validation- oder Authorization-Fehler. Im Single-Tenant-Default gilt
bis zu einem echten Tenant-Modell die Konvention `tenant: "default"`
([`atomic-preserve-service-mode.md`](atomic-preserve-service-mode.md)
§5 D Risiken).

### 3.4 Approval-Granularität — Single-Approval + Plan-Fingerprint

Der Apply-Pfad nutzt ein einzelnes Approval pro Migrate-Job. Weil §3.1
bewusst einstufig bleibt, gibt es keinen belastbaren getrennten
„Plan-Sign-off vor Apply"-Zeitpunkt.

Operator-Sichtbarkeit geht trotzdem nicht verloren:

- `dryRun: true` liefert das Plan-Artefakt, Objektliste,
  `payloadFingerprint` und `planFingerprint`.
- Der Audit-Trail des Apply-Jobs speichert denselben
  `payloadFingerprint`, den erzeugten `planFingerprint` und die
  redigierte Per-Objekt-Zusammenfassung.
- Approval-Grants binden wie bei den bestehenden Start-Tools an
  Principal, Tenant, Tool, Approval-Key und `payloadFingerprint`.

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

### 3.6 Quota-Bucket-Schema — Concurrency-MVP

Der MVP nutzt nur ein Concurrency-Quota:
`QuotaScope("schema_migrate", tenant=<tenant>, schema=<targetRef>)`
mit „max N parallele Migrate-Jobs" pro Ziel-Scope.

Time-Window-Quotas („max N Migrate-Jobs pro Stunde") bleiben
deferred, bis Betreiberfeedback sie verlangt. Für die einstufige
Variante ist die Semantik lokal: das Quota greift bei Job-Start vor
dem Pool-Borrow und wird beim terminalen Job-Status oder durch einen
Sweeper wieder freigegeben.

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
- `tenant`.
- `principal`.
- `canonical(options)`.

Die Plan-DDL selbst ist Konsequenz dieser Eingaben, nicht Eingabe des
Jobs. Sie gehört deshalb nicht in den `payloadFingerprint`; sie kann
aber in den `planFingerprint` eingehen.

`lockTimeoutMs` ist ein Lieferungsparameter, kein
Identitätsparameter. Ein Replay desselben Migrate-Jobs mit anderem
Lock-Timeout bleibt semantisch derselbe Job und verändert den
`payloadFingerprint` nicht.

### 3.8 Failure-Klassifikation am Wire — Vier Codes + Atomic-Bucket

Der Migrate-Pfad kennt mindestens vier Failure-Klassen, die der
Caller unterscheidbar zurückbekommen muss:

- `SCHEMA_MIGRATE_LOCK_TIMEOUT` — Lock-Acquire-Timeout (Sub-Slice A).
- `SCHEMA_MIGRATE_CANCELLED` — Externer Cancel (Sub-Slice E).
- `SERVICE_POOL_EXHAUSTED` — Pool-Borrow-Timeout (Sub-Slice C).
- `SERVICE_RATE_LIMITED` — Quota-Exhaustion (Sub-Slice D).

Plus die bestehenden Klassen aus dem Atomic-Preserve-Pfad
(`AtomicSequencePreserveResult.Failure`-Varianten). Diese Varianten
werden nicht als eigene MCP-Code-Familie aufgefächert, sondern mappen
auf einen Sammelcode:

- `SCHEMA_MIGRATE_ATOMIC_FAILURE` mit
  `detail: { "kind": "...", ... }`, z. B. `PROBE_FAILED`,
  `RESTORE_FAILED`, `LOCK_ESCALATION`.

Stacktraces bleiben ausschließlich server-side im Audit- oder
Log-Kontext und erscheinen nie im Wire-Envelope.

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
- **Schema-Versionierung / Schema-Drift-Detection.** `sourceArtifactRef`
  pins nur die Source-Sicht. Eine allgemeine Drift-Strategie für
  Source/Target-Versionen, Plan-Verfall oder drift-tolerante Apply-
  Regeln gehört in einen Schema-Versioning-Slice, nicht hierher.

## 5. Verweise

- [`atomic-preserve-service-mode.md`](atomic-preserve-service-mode.md)
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
