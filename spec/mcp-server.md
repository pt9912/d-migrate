# MCP-Server (Phase B + C + D + E + F + G)

> **Status (0.9.6):** Phase B (Transport / Auth / Discovery /
> JSON-Schemas), Phase C (typisierte Schema-Tools, Upload-Flow,
> `job_status_get`, `artifact_chunk_get`), Phase D (Discovery-
> Listen-Tools, produktives `resources/read`, HMAC-Cursor,
> Connection-Ref-Bootstrap), Phase E (Async-Job-Start-Tools,
> Idempotency, Policy, Quota, `job_cancel`), Phase F
> (policy-gesteuerter `job_input`-Upload, `data_import_start`,
> `data_transfer_start`) und Phase G (KI-nahe Tools
> `procedure_transform_plan/execute`, `testdata_plan` +
> MCP-Prompts `prompts/list`/`prompts/get`) sind abgeschlossen.
> Damit ist der 0.9.6-MCP-Vertrag vollständig produktiv.
> Details der jeweiligen Phase: §"Phase D: Discovery und
> Ressourcen", §"Phase E: Async-Jobs, Idempotency, Policy",
> §"Phase F: Policy-gesteuerte Datenoperationen" und §"Phase G:
> KI-nahe Tools und MCP-Prompts" unten + die done-Pläne unter
> `docs/planning/done/`.

Der MCP-Server ist ein Driving-Adapter zu d-migrate
(`adapters/driving/mcp`) und implementiert
**MCP 2025-11-25** mit **stdio**- und **Streamable-HTTP**-Transport. Die
vollständige Phasen-B-Spezifikation liegt in
[`docs/planning/ImpPlan-0.9.6-B.md`](../docs/planning/done/ImpPlan-0.9.6-B.md).

---

## Schnellstart

### Lokaler stdio-Server (Demo, ohne Auth)

Der primäre lokale Agentenpfad ist `stdio` — ein Server-Prozess pro
Client, gesprächsorientiert über stdin/stdout. **Auch hier ist der
Aufrufer nicht automatisch vertrauenswürdig** (§4.2): jede Methode
außer `initialize`/`notifications/initialized` braucht einen
validierten Principal mit den passenden Scopes
(`DMIGRATE_MCP_STDIO_TOKEN` plus Token-Registry).

Für eine **lokale Demo** ohne Token-Registry genügt
`AuthMode.DISABLED` über HTTP (siehe unten). stdio-Demo ohne Token
ist möglich, aber praktisch nutzlos — nur
`initialize`/`notifications/initialized` sind scope-frei (§12.14
`SCOPE_FREE_METHODS`). `tools/list`, `tools/call`, `resources/list`
und `resources/templates/list` verlangen alle `dmigrate:read` und
fallen ohne Principal mit `AUTH_REQUIRED` (Tool-Result-Envelope für
`tools/call`) bzw. JSON-RPC `-32600` (Resource-/Protocol-Methoden,
§12.8) durch.

```bash
# stdio mit Token-Registry (lokaler Dev-Use)
export DMIGRATE_MCP_STDIO_TOKEN="tok_local_dev"
d-migrate mcp serve \
  --transport stdio \
  --auth-mode disabled \
  --stdio-token-file /etc/d-migrate/stdio-tokens.yaml
```

### Lokaler HTTP-Server (Demo, ohne Auth — nur Loopback)

```bash
d-migrate mcp serve \
  --transport http \
  --bind 127.0.0.1 \
  --port 8080 \
  --auth-mode disabled
# -> stderr: MCP HTTP server listening on 127.0.0.1:8080
```

`AuthMode.DISABLED` ist **streng auf Loopback beschränkt**
(`127.0.0.1`, `::1`). Der Server lehnt ab, wenn `--bind` einen
Nicht-Loopback nutzt — siehe §12.12.

---

## Transports

### `--transport stdio`

- Liest NDJSON von `System.in`, schreibt nach `System.out`.
- Stoppt bei EOF auf stdin oder `SIGINT`.
- `--bind`/`--port`/`--allow-origin` werden ignoriert.
- Principal wird **einmal beim Start** aus `DMIGRATE_MCP_STDIO_TOKEN`
  + `--stdio-token-file` aufgelöst.

### `--transport http`

- Streamable-HTTP per MCP 2025-11-25 (§12.13).
- `POST /mcp` für JSON-RPC.
- `GET /mcp` antwortet HTTP 405 (kein SSE in Phase B).
- `DELETE /mcp` mit `MCP-Session-Id` terminiert die Session.
- `GET /.well-known/oauth-protected-resource` liefert Protected
  Resource Metadata (§12.7).
- Principal wird **pro Request** aus `Authorization: Bearer …`
  validiert (§12.14).

---

## Authorisierung

### stdio (§12.15)

| Quelle                          | Wirkung                                   |
| ------------------------------- | ----------------------------------------- |
| `DMIGRATE_MCP_STDIO_TOKEN` env  | gehasht (`sha256_hex`) → Fingerprint     |
| `--stdio-token-file` (JSON/YAML) | Token-Registry — Lookup via Fingerprint  |
| OS-User / Parent-PID / `pwd`    | **NIE** als Principal-Quelle (§4.2)       |

#### Token-Datei-Format

JSON oder YAML, identische Struktur. Die Datei-Endung wählt den Parser
(`.json` oder `.yaml`/`.yml`).

```yaml
tokens:
  - fingerprint: "deadbeef..."          # sha256_hex of the raw token
    principalId: "alice"
    tenantId: "acme"
    scopes:
      - "dmigrate:read"
      - "dmigrate:job:start"
    isAdmin: false
    auditSubject: "alice@acme"
    expiresAt: "2027-01-01T00:00:00Z"   # RFC-3339
```

Den Fingerprint zu einem rohen Token erzeugst du z.B. mit:

```bash
printf 'tok_local_dev' | sha256sum
# -> <fingerprint> -
```

### HTTP (§12.14)

| Modus               | Pflicht-Argumente                                       | Loopback-Only |
| ------------------- | ------------------------------------------------------- | ------------- |
| `disabled`          | (keine; nur Loopback)                                   | ✓             |
| `jwt-jwks`          | `--issuer`, `--jwks-url`, `--audience`                  | nein          |
| `jwt-introspection` | `--issuer`, `--introspection-url`, `--audience`         | nein          |

**Production-Setup (jwt-jwks):**

```bash
d-migrate mcp serve \
  --transport http \
  --bind 0.0.0.0 \
  --port 443 \
  --auth-mode jwt-jwks \
  --issuer https://issuer.example/ \
  --jwks-url https://issuer.example/.well-known/jwks.json \
  --audience mcp.dmigrate \
  --public-base-url https://mcp.example.com \
  --allow-origin https://app.example.com
```

> ⚠️ **Nicht-lokales HTTP ohne Auth ist explizit nicht fertig**
> (§4.3 + §6.11-Akzeptanz). Der Server lehnt den Start ab, sobald
> `--bind` keine Loopback-Adresse ist und `--auth-mode disabled` läuft
> oder Pflicht-Auth-Felder fehlen. Konfigurationsfehler werden vor dem
> ersten Client-Request gemeldet (Exit-Code 2, eine Zeile pro
> Verstoß).

#### Validierungsregeln (§12.12)

- `port` ∈ `[0, 65535]`.
- `clockSkew` ∈ `[0s, 5min]`.
- `authMode == disabled` → `bind` MUSS Loopback sein, `publicBaseUrl`
  MUSS `null` sein.
- `authMode in {jwt-jwks, jwt-introspection}` → `issuer`, `audience`
  und (je nach Modus) `jwks-url` oder `introspection-url` MÜSSEN
  gesetzt sein.
- `publicBaseUrl != null` → MUSS `https`-Schema haben.
- `allowedOrigins` darf `*` (Wildcard) nicht enthalten; bei
  Nicht-Loopback-Bind MUSS die Liste explizit gesetzt werden.
- `algorithmAllowlist` darf `none` und `HS*` nicht enthalten.
- `stdioTokenFile != null` → Datei MUSS lesbar sein.

---

## Capabilities & Tools

### `capabilities_list`

Phase B's einziger fachlicher Handler. Liefert einen Snapshot:

```json
{
  "mcpProtocolVersion": "2025-11-25",
  "dmigrateContractVersion": "v1",
  "serverName": "d-migrate",
  "tools": [ /* alle 0.9.6-Tools mit Scope-Anforderungen */ ],
  "scopeTable": {
    "dmigrate:read": ["capabilities_list", "schema_validate", ...],
    "dmigrate:job:start": ["schema_reverse_start", ...],
    ...
  }
}
```

### `tools/list` und `tools/call`

`tools/list` liefert für jedes 0.9.6-Tool:
- `name`, `title`, `description`
- `inputSchema` und `outputSchema` (JSON Schema 2020-12, §12.18)
- `requiredScopes` (d-migrate-Erweiterung)

`tools/call` für `capabilities_list` läuft fachlich; alle anderen
Tools antworten mit `ToolsCallResult(isError=true,
content=[ToolErrorEnvelope(code=UNSUPPORTED_TOOL_OPERATION, ...)])`.

### `resources/list` und `resources/templates/list`

Walks Jobs → Artifacts → Schemas → Profiles → Diffs → Connections.
Pagination per opaquem `nextCursor` (§12.17). Connection-Refs werden
**ohne Secrets** projiziert (§6.9-Akzeptanz). Phase B's
`ResourceStores.empty()`-Default liefert leere Listen — Phase C/D
verdrahtet echte Stores.

### Resource-URI-Templates

Genau 7 Templates (§5.5 + §12.17):

```
dmigrate://tenants/{tenantId}/jobs/{jobId}
dmigrate://tenants/{tenantId}/artifacts/{artifactId}
dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}
dmigrate://tenants/{tenantId}/schemas/{schemaId}
dmigrate://tenants/{tenantId}/profiles/{profileId}
dmigrate://tenants/{tenantId}/diffs/{diffId}
dmigrate://tenants/{tenantId}/connections/{connectionId}
```

---

## Bekannte Grenzen Phase B

| Bereich                                      | Status                                                       |
| -------------------------------------------- | ------------------------------------------------------------ |
| Tool-Handler (außer `capabilities_list`)     | **Phase C+D produktiv** — Schema-Tools, Upload-Flow, `job_status_get`, `artifact_chunk_get` (Phase C); `*_list`-Discovery (Phase D). |
| `resources/read`                             | **Phase D produktiv** — siehe Abschnitt "Phase D: Discovery und Ressourcen" unten. |
| SSE-Push / `notifications/*`                 | **Nicht implementiert**                                      |
| `subscribe`/`listChanged` Capabilities      | Beide `false` (§12.16, §12.17)                              |
| `connections/list` (Admin-Filter)           | **Phase D**: Connection-Refs erscheinen in `resources/list` und sind via `resources/read` lesbar (secret-frei). |
| `job_cancel`                                 | Registry-Eintrag — Handler folgt Phase E (Job-Lifecycle).   |
| Upload-Session-Tools                         | **Phase C + F produktiv** — `schema_staging_readonly` (Phase C, Quota/Audit) + `job_input` (Phase F, policy-gesteuert mit `approvalKey` + Init-Fingerprint). `artifact_upload_init`, `artifact_upload`, `artifact_upload_abort` decken beide Intents. |
| Data-write Start-Tools                       | **Phase F produktiv** — `data_import_start` und `data_transfer_start` (idempotent, policy-gesteuert, `targetConnectionRef`/`sourceConnectionRef` als tenant-scoped Resource-URI). |
| AI-Tools (procedure_transform_*, testdata_*) | **Phase G produktiv** — `procedure_transform_plan`, `procedure_transform_execute` und `testdata_plan` mit AiProviderPort, NoOp-Default-Provider, Prompt-Hygiene, Provider-Quota (`PROVIDER_CALLS`), AiArtifactMetadataStore (Provenance) und AiToolOutcomeStore (Single-Writer-Lease + Replay). `testdata_execute` bleibt Carve-out (separate Daten-Schreiboperation, nicht in 0.9.6). |
| MCP-Prompts (prompts/list, prompts/get) | **Phase G produktiv** — drei Pflichtprompts (`procedure_analysis`, `procedure_transformation`, `testdata_planning`) mit JSON-Schema-Argumentvalidierung, Plan-Hygiene auf Argument + zusammengebaute Prompt-Nachricht, dmigrate:read-Scope-Gate, JSON-RPC-Fehler mit `error.data.dmigrateCode`. |
| Resource-Stores (Real-Backends)              | **Phase D**: `ResourceStores.fromPhaseCWiring(...)` lädt Job/Artifact/Schema/Profile/Diff/Connection aus produktiver Wiring. |
| Cross-Tenant-Reads                           | **Phase D**: Tenant-Scope ueber `allowedTenantIds`; Cross-Tenant-Reads erlaubt, wenn der URI-Tenant in `allowedTenantIds` liegt. |
| OAuth Authorization Server / DCR             | **Nicht implementiert**                                      |
| Multi-Scope-Tools                            | Heute nicht im Default-Mapping — Wire-Format ist vorbereitet |

Eine Verfeinerung der `inputSchema`/`outputSchema`-Definitionen pro
Tool kommt in Phase C/D mit den jeweiligen Handlern; Phase-B-Schemas
sind typisiert auf die offensichtlichen Top-Level-Argumente und durch
einen Golden-Test gegen Drift gepinnt
(`adapters/driving/mcp/src/test/resources/golden/phase-b-tool-schemas.json`).

---

## Phase D: Discovery und Ressourcen

Phase D (`docs/planning/done/ImpPlan-0.9.6-D.md`) macht Jobs,
Artefakte, Schemas, Profile, Diffs und Connection-Refs ueber MCP
auffindbar und gezielt lesbar. Die Phase ergaenzt Phase B/C
additiv — bestehende Tools/Wire-Vertraege bleiben rueckwaerts-
kompatibel, sofern hier nicht ausdruecklich anders dokumentiert.

### Discovery-Tools (`*_list`)

Phase D liefert fuenf produktive Discovery-Tools, alle mit
`dmigrate:read`-Scope:

| Tool             | Collection-Feld | Wire-spezifische Filter                              |
| ---------------- | --------------- | ---------------------------------------------------- |
| `job_list`       | `jobs`          | `status`, `operation`, `createdAfter/Before`         |
| `artifact_list`  | `artifacts`     | `kind`, `jobId`, `createdAfter/Before`               |
| `schema_list`    | `schemas`       | `jobId`, `createdAfter/Before`                       |
| `profile_list`   | `profiles`      | `jobId`, `createdAfter/Before`                       |
| `diff_list`      | `diffs`         | `jobId`, `sourceRef`, `targetRef`, `createdAfter/Before` |

Gemeinsame Parameter aller fuenf Tools: `tenantId` (optional,
adressierend, muss in `allowedTenantIds` liegen), `pageSize`
(1..200, Default 50), `cursor` (HMAC-gekapselt). Standard-Sortierung:
`createdAt DESC, id ASC`. Antwort-Form: typisiertes Collection-Feld
plus `nextCursor` (`null` bei letzter Seite).

### `resources/read` produktiv

`resources/read` akzeptiert nur `uri` als Eingabe — `cursor`,
`range`, `chunkId` und andere Zusatzfelder werden mit
`-32602 InvalidParams` + `error.data.dmigrateCode=VALIDATION_ERROR`
abgewiesen.

Resource-URI-Familie:

```
dmigrate://capabilities                                   (tenantless, statisch)
dmigrate://tenants/{tenantId}/jobs/{jobId}
dmigrate://tenants/{tenantId}/artifacts/{artifactId}
dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}
dmigrate://tenants/{tenantId}/schemas/{schemaId}
dmigrate://tenants/{tenantId}/profiles/{profileId}
dmigrate://tenants/{tenantId}/diffs/{diffId}
dmigrate://tenants/{tenantId}/connections/{connectionId}
```

`upload-sessions` ist parsebar fuer interne Upload-Pfade, aber als
MCP-Resource-Kind blockiert: `resources/read` auf eine
`upload-sessions`-URI in einem erlaubten Tenant kollabiert auf
`-32602 InvalidParams` mit
`dmigrateCode=VALIDATION_ERROR` *vor* jedem Store-Lookup, damit
keine Upload-Session-ID via Existenz-Test eruiert werden kann.

Fehlerfamilien (alle `error.data.dmigrateCode` gesetzt):

| dmigrateCode            | JSON-RPC-Code   | Trigger                                                                  |
| ----------------------- | --------------- | ------------------------------------------------------------------------ |
| `VALIDATION_ERROR`      | `-32602`        | URI-Grammar-Fehler, blockierter Kind, unbekannter Parameter, Cursor-Tampering |
| `TENANT_SCOPE_DENIED`   | `-32600`        | URI-Tenant ausserhalb `allowedTenantIds`                                |
| `RESOURCE_NOT_FOUND`    | `-32002` (MCP)  | unbekannte ID / nicht-sichtbarer Datensatz / abgelaufenes Artefakt      |

Inline-vs-`artifactRef`: jede serialisierte JSON-Projektion bleibt
unter `MAX_INLINE_RESOURCE_CONTENT_BYTES` (Default 49152). Ueber-
volume Projektionen werden auf eine Stripped-Form mit
`artifactRef`/`inlineLimitExceeded`-Marker verkuerzt; Projektionen
ohne `artifactRef` (z. B. die Capabilities-Doc selbst) liefern
`VALIDATION_ERROR` mit dem Cap als Detail.

Artifact-Chunk-URIs liefern den adressierten Chunk direkt ueber
`resources/read`: Text-MIME-Typen (`text/*`, `application/json`,
`application/yaml`, `application/x-yaml`, `application/xml`) werden
als MCP-`text`-Content ausgegeben, binaere oder unbekannte MIME-Typen
als natives MCP-`blob`-Content-Feld mit Base64-Bytes. Groessere
Artefakte iterieren weiter ueber die `nextChunkUri` /
`nextChunkCursor`-Mechanik von `artifact_chunk_get`; `resources/read`
nimmt dafuer weiterhin nur die jeweilige URI entgegen.

### `dmigrate://capabilities`

Die einzige tenantlose Resource-URI. Liefert dieselbe
Capabilities-Projektion wie das `capabilities_list`-Tool, ohne den
per-Call `executionMeta.requestId`. Eine leer konfigurierte
Capabilities-Provider-Function (Phase-B-/legacy-Pfad) kollabiert
auf `RESOURCE_NOT_FOUND`, damit ein Stale-Deployment niemals einen
halbfertigen Capabilities-Body liefert.

### Cursor-Kapselung (HMAC)

`resources/list` und alle fuenf `*_list`-Tools sealen Cursor mit
HMAC-SHA256 (`McpCursorCodec`). Gebunden ist jeder Cursor an:

- `cursorType` (z. B. `"job_list"`, `"resources/list"`)
- `tenantId`
- `family` (Tool-spezifisch oder fixed `"resources/list-walk"`)
- `filters` (deterministische Map, leer bei `resources/list`)
- `pageSize`
- `sort` (heute immer `null`; Plan-E reserviert)
- `version`, `kid`, `issuedAt`, `expiresAt` (TTL 15 min)

`artifact_chunk_get` produziert einen HMAC-gesealtenen
`nextChunkCursor` zusaetzlich zum `nextChunkUri`. Bindung:
(tenant, artifactId, chunkSize). Eingangsseitig akzeptiert das Tool
weiterhin den nackten `chunkId`-Integer (befristete Phase-C-
Kompatibilitaet) und wirft `VALIDATION_ERROR`, wenn beide gesetzt
sind. Der Output enthaelt nie ein `nextChunkId`-Feld.

Manipulierte Cursor (HMAC-Signatur falsch, Tenant-/Filter-/Page-
Size-Mismatch) kollabieren auf `VALIDATION_ERROR` — Tool-Pfade
ueber das Tool-Error-Envelope, `resources/list` ueber
JSON-RPC-`-32602`. Multi-Instanz-Deployments muessen einen
deterministischen `cursorKeyring` wiren; der Default-Random-
Keyring funktioniert nur fuer Single-Instance-Setups.

Legacy-Phase-B-Cursor werden nicht dual-read-faehig gemacht, sobald
ein HMAC-Codec gewired ist. Der alte unsigned `resources/list`-Cursor
(Base64 von `{kind, innerToken}`) bleibt nur in Phase-B-only
Deployments ohne Codec gueltig. Produktive Deployments mit Codec
weisen unsigned Cursor mit `VALIDATION_ERROR` ab. Ein spaeteres
Compat-Flag darf additiv eingefuehrt werden, muss aber explizit
aktiviert werden; der Default bleibt fail-closed.

Produktive Multi-Instanz-Deployments starten `mcp serve` mit
`--cursor-keyring-file <path>`. Datei-Format:

```yaml
signing:
  kid: "cursor-2026-05"
  secretBase64: "base64-encoded-32-byte-secret"
validation:
  - kid: "cursor-2026-04"
    secretBase64: "base64-encoded-32-byte-secret"
```

Ein initiales File kann lokal erzeugt werden:

```bash
d-migrate mcp cursor-key generate --kid cursor-2026-05 > cursor-keyring.yaml
d-migrate mcp cursor-key validate --cursor-keyring-file cursor-keyring.yaml
```

Rotation folgt strikt `validation-first -> activate -> drop`:

1. **validation-first**: neuen Key auf allen Instanzen nur unter
   `validation` ausrollen; `signing.kid` bleibt unveraendert.
2. **activate**: nach vollstaendigem Rollout wird derselbe neue Key
   auf allen Instanzen als `signing` gesetzt; der alte Signing-Key
   bleibt unter `validation`.
3. **drop**: erst nach `maxCursorTtl + clockSkew` seit dem letzten
   moeglichen Signaturzeitpunkt wird der alte Key aus `validation`
   entfernt.

Kollidierende `kid`s mit unterschiedlichen Secrets sind ein
Startfehler. Validation-Keys duerfen den aktiven Signing-Key nur mit
identischem Secret duplizieren; die Duplikation wird ignoriert.

### Connection-Ref-Bootstrap

Phase D liefert einen adapter-neutralen Bootstrap fuer Connection-
Refs in `adapters/driven/connection-config`:

- `ConnectionReferenceConfigLoader` (Port) — laedt secret-freie
  `ConnectionReference`-Records aus Projekt-/Server-Config.
- `ConnectionSecretResolver` (Port) — separate Secret-Aufloesung
  fuer Runner-/Driver-Pfade. Discovery darf den Resolver NIE
  aufrufen; `ResolvedConnection.Failure` mit stabilen
  reason-Codes (`PROVIDER_MISSING`, `ENV_NOT_SET`,
  `PRINCIPAL_NOT_AUTHORISED`, `NO_CREDENTIAL_REF`).
- `YamlConnectionReferenceLoader` — produktive Implementation.
  Erwartet die Map-Form pro Connection (mit `displayName`,
  `dialectId`, `sensitivity`, `credentialRef`, `providerRef`,
  `allowedPrincipalIds`, `allowedScopes`). Phase-C-String-Form
  (bare URL) wird silent gedroppt — Phase-D §3.7 verbietet das
  Materialisieren expandierter Secrets im Discovery-Pfad.
- `EnvConnectionSecretResolver` — Default-Resolver fuer das
  `env:VAR_NAME`-Schema. Authorisiert via
  `allowedPrincipalIds`/`allowedScopes` mit Admin-Bypass.

`resources/read` auf eine Connection-URI dropt `credentialRef`,
`providerRef`, `allowedPrincipalIds` und `allowedScopes` aus der
Wire-Projektion. Discovery-Konsumenten sehen ausschliesslich
`connectionId`, `tenantId`, `displayName`, `dialectId`, `sensitivity`.

---

## Konfigurations-Flags-Referenz

| Flag                        | Wirkung                                                          |
| --------------------------- | ---------------------------------------------------------------- |
| `--transport`               | `stdio` (Default) oder `http`.                                   |
| `--bind`                    | HTTP-Bind-Adresse (Default `127.0.0.1`).                         |
| `--port`                    | HTTP-Port (`0` = ephemeral).                                     |
| `--public-base-url`         | Kanonische HTTPS-URI für Protected Resource Metadata.            |
| `--auth-mode`               | `disabled`, `jwt-jwks` (Default), `jwt-introspection`.          |
| `--issuer`                  | OIDC-Issuer-URI (Pflicht für `jwt-*`).                          |
| `--jwks-url`                | JWKS-URL (Pflicht für `jwt-jwks`).                              |
| `--introspection-url`       | RFC-7662-Introspection-Endpoint (Pflicht für `jwt-introspection`). |
| `--audience`                | Erwartetes `aud`/Resource-Indicator.                             |
| `--stdio-token-file`        | Token-Registry für stdio (JSON oder YAML).                       |
| `--allow-origin`            | Origin-Allowlist-Eintrag (mehrfach setzbar).                     |
| `--connection-config`       | Project/server YAML fuer Phase-D Connection-Refs. Wenn nicht gesetzt, wird ein globales `--config <path>` wiederverwendet. |
| `--cursor-keyring-file`     | YAML-Keyring fuer deterministische HMAC-Cursor in Multi-Instanz-Deployments. |

---

## Phase E: Async-Jobs, Idempotency, Policy

Phase E (`ImpPlan-0.9.6-E.md`) wirelt vier produktive Tool-Slots:

- `schema_reverse_start` — startet einen Schema-Reverse-Job (read-only, async).
- `data_profile_start` — startet einen Daten-Profiling-Job (read-only).
- `schema_compare_start` — startet einen Schema-Vergleichs-Job (zwei Refs).
- `job_cancel` — cancelt einen laufenden oder gequeueten Job.

### Wire-Contracts

**Start-Tools (alle drei symmetrisch)**:

```jsonc
// Input
{
  "connectionId": "dmigrate://tenants/<t>/connections/<id>",  // bzw. sourceUri/targetUri für compare
  "idempotencyKey": "<uuid>",       // Pflichtfeld
  "approvalToken": "<opaque>"        // optional, für Approved-Retry
}

// Output (Erfolg)
{
  "jobId": "job_...",
  "resourceUri": "dmigrate://tenants/<t>/jobs/job_...",
  "executionMeta": { "requestId": "..." }
}
```

**`job_cancel`** — genau eines von `jobId | resourceUri`, optional `reason`:

```jsonc
// Output (Plan §5.6 / §7.6)
{
  "jobId": "...",
  "operation": "schema_reverse",
  "status": "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED",
  "terminal": true | false,
  "resourceUri": "dmigrate://...",
  "executionMeta": {
    "requestId": "...",
    "cancelRequested": true,
    "cancelAckPending": true,        // bei RUNNING-Cancel
    "retryAfter": 2,
    "cancelRequestedReason": "...",  // scrubbed
    "cancelSignalSource": "job_cancel"
  }
}
```

`job_status_get` projiziert `executionMeta` einheitlich mit `job_cancel`.

### Approval-Flow + fail-closed Grant-Aussteller

Wenn die Policy für einen Start `RequiresApproval` zurückgibt, antwortet
der Server mit `POLICY_REQUIRED` plus `approvalRequestId` +
`requiredScopes`. Der Client muss einen `approvalToken` vom Grant-
Aussteller einholen und im Retry mitsenden.

**Grant-Aussteller-Modi** (Plan §7.4):

- `FailClosedGrantIssuer` — **Default ohne Konfiguration**. Lehnt jeden
  Issue-Versuch mit `policy:no-issuer-configured` ab. Eine laufende
  Instanz ohne explizite Konfiguration kann KEINEN `RequiresApproval`-
  Flow abschließen — direkte `ALLOW`-Policies bleiben unberührt.
- `ConfiguredAllowlistGrantIssuer` — produktive Allowlist mit
  `GrantIssuanceRule`-Liste; matchende Regeln stellen Grants aus,
  Nicht-Matchende liefern `NotIssuable`.
- `DemoAutoApprovalGrantIssuer` — **unsicher, nur für Loopback/stdio**.
  Stellt jeden Request aus; Audit-Markierung über fixierten
  `issuerFingerprint = "demo-auto-approval"` damit `IssuerCheck.AllowList`
  den Demo-Mode aussortieren kann. Transport-Restriktion (loopback only)
  erzwingt das Bootstrap-Wiring, nicht der Issuer selbst.

### Quotas + Rate-Limiting

Aktive Jobs werden pro `(tenantId, ACTIVE_JOBS, principalId, operation)`
gezählt. Überschreitet eine neue Reservierung den Limit-Wert, antwortet
der Start mit `RATE_LIMITED` (Plan §7.9):

```jsonc
{
  "code": "RATE_LIMITED",
  "details": {
    "retryAfter": "30",
    "current": "3",
    "limit": "3",
    "reason": "ACTIVE_JOBS_QUOTA"
  }
}
```

Wichtig (Plan §7.9 line 1270-1273): RATE_LIMITED entsteht **vor**
`jobBuilder`-Aufruf — keine Secret-Store-Reads, keine Pool-Initialisierung,
keine Schema-Materialisierung bei rate-limited Starts.
Der `reason`-Wert ist immer vorhanden: `ACTIVE_JOBS_QUOTA` fuer aktive
Job-Quota, `EXECUTOR_SATURATED` fuer bounded-Executor-Saturation vor
dem Job-Commit.

Slots werden freigegeben bei:
- erfolgreichem Job-Abschluss (succeeded/failed/cancelled über Dispatcher)
- queued-Cancel via `job_cancel` (über JobCancelService)
- Lease-Ablauf vor JobStartTransaction.commit (über
  `QuotaReservationSweeper`)

### Audit

Jeder `tools/call` durchläuft `AuditScope.around` und emittiert genau
ein `AuditEvent` (SUCCESS oder FAILURE mit ToolErrorCode). Phase-E-
Outcomes bekommen damit automatisch Audit-Coverage. Reasons (z.B. im
Cancel-Pfad) werden über `SecretScrubber` gescrubbed bevor sie in
`cancelRequestedReason` oder Audit-Felder wandern.

---

## Phase F: Policy-gesteuerte Datenoperationen

Phase F (`docs/planning/done/ImpPlan-0.9.6-F.md`) ergänzt Phase
C/D/E um drei produktive Bausteine:

1. den **policy-gesteuerten `job_input`-Upload** über
   `artifact_upload_init` / `artifact_upload` /
   `artifact_upload_abort` (zusätzlich zum read-only
   `schema_staging_readonly`-Pfad aus Phase C),
2. **`data_import_start`** — startet einen Importjob, der ein
   hochgeladenes `UPLOAD_INPUT`-Artefakt in eine tenant-scoped
   Zielverbindung schreibt,
3. **`data_transfer_start`** — startet einen DB-zu-DB-Transferjob
   zwischen zwei tenant-scoped Verbindungen.

Alle drei Pfade sind idempotent, brauchen entweder einen
`approvalKey` (Upload-Init / synchrone Side-Effects) oder einen
`idempotencyKey` (Job-Starts) plus optional `approvalToken` für
den Approved-Retry. Die Approval-Fingerprints binden Tenant,
Caller, Tool, Korrelations-Kind und den normalisierten
Payload-Fingerprint (Plan §5).

### Upload-Intent-Trennung

`uploadIntent` separiert read-only Schema-Staging und
write-nahe `job_input`-Uploads:

| Intent                        | Scope-Gate                                | Default-Schutz                                                                                |
| ----------------------------- | ----------------------------------------- | --------------------------------------------------------------------------------------------- |
| `schema_staging_readonly`     | `dmigrate:read`                           | nur Quota + Audit; idempotent über `clientRequestId` (Phase C).                               |
| `job_input`                   | `dmigrate:artifact:upload`                | policy-gesteuert mit `approvalKey` + Init-Fingerprint; finalisiertes Artefakt ist `UPLOAD_INPUT` (Phase F). |

Read-only Staging-Artefakte (`SCHEMA`-Kind) dürfen nicht still
als `job_input` weiterverwendet werden — der `data_import_start`-
Handler erzwingt `kind=UPLOAD_INPUT` und liefert sonst
`VALIDATION_ERROR` (Plan §6.1).

### `artifact_upload_init` — Phase-F-Felder

Zusätzlich zu den Phase-C-Feldern (`uploadIntent`,
`expectedSizeBytes`/`sizeBytes`, `checksumSha256`, `filename`)
nimmt der Init-Pfad in Phase F entgegen:

- `approvalKey` — verbindlich für `uploadIntent=job_input`;
  bindet Idempotenz und Policy-Challenge an
  (`tenantId`,`callerId`,`approvalKey`,Init-Fingerprint).
- `mimeType` — optional, default `application/octet-stream`.
  Allowlist siehe `spec/ki-mcp.md` §8.3 (CSV ist seit Phase F
  erlaubt: `text/csv` / `application/csv`).
- `artifactKind` — verpflichtend, eines aus `schema`, `ddl`,
  `transform-script`, `seed-data`, `rules`, `generic`.
- `targetTable` — optional Tabellenbindung für Single-File-
  Imports; verboten für `schema_staging_readonly`.
- `clientRequestId` — optional, nur für `schema_staging_readonly`
  resumable.

`sizeBytes=0` ist nur für nicht-Schema-`job_input` als Single-
Empty-Segment erlaubt; `artifactKind=schema` mit `sizeBytes=0`
liefert `VALIDATION_ERROR` (Plan §8.4 / F.4 2/3).

`uploadSessionTtlSeconds` startet bei 900s mit absoluter Hard-
Cap 3600s ab Session-Erzeugung; jede erfolgreiche Segmentannahme
darf bis 3600s verlängern. Idle-Timeout 300s. Session-Quota
`STORED_ARTIFACT_BYTES` wird beim Übergang nach `COMPLETED`
gegen das Init-Reserve-Bucket umgebucht (F.9 1/3).

### Administrative Abort-Pipeline

`artifact_upload_abort` deckt zwei Pfade:

- **Owner-Abort** — eigene aktive Session, ohne Approval-Token,
  über `dmigrate:artifact:upload`-Scope.
- **Administrative Abort** — `reason` + `approvalKey` + Admin-
  Scope; Outcome wird als `AbortOutcome` in einem persistenten
  Store geschrieben und über `correlationKey=approvalKey` +
  Fingerprint dedupliziert (F.6). Approval-Reuse für andere
  Session, anderen Caller oder anderen `reason` liefert
  `IDEMPOTENCY_CONFLICT`.

### `data_import_start` und `data_transfer_start`

Wire-Verträge (Auszug):

```jsonc
// data_import_start
{
  "idempotencyKey": "imp-2026-05-01-acme-warehouse-load",
  "targetConnectionRef": "dmigrate://tenants/acme/connections/warehouse",
  "artifactId": "art-…",                 // oder sourceArtifactRef
  "table": "events.click_events",        // Single-File-Import
  "format": "csv",                       // optional override (json/yaml/csv)
  "onError": "skip",
  "onConflict": "update",
  "chunkSize": 1000
}

// data_transfer_start
{
  "idempotencyKey": "trf-2026-05-01-acme-orders",
  "sourceConnectionRef": "dmigrate://tenants/acme/connections/legacy-pg",
  "targetConnectionRef": "dmigrate://tenants/acme/connections/warehouse",
  "tables": ["public.orders", "public.order_items"],
  "filter": "tenant_id = 'acme'",
  "sinceColumn": "updated_at",
  "since": "2026-04-01T00:00:00Z",
  "chunkSize": 5000
}
```

Beide Tools liefern bei Erfolg den symmetrischen Job-Start-
Envelope (`jobId`, `resourceUri`, `executionMeta.requestId`).

Validierung erfolgt zweistufig: das JSON-Schema gated
strukturelle Felder + `additionalProperties=false`, der Handler
prüft semantisch (Tabellen-/Topology-Eignung, Artefakt-
Eligibility, ConnectionRef-Resolution + Tenant-Scope, `chunkSize
<= 10000`, `sinceColumn`/`since` paarweise).

### Fingerprint-Vertrag

Der MCP-spezifische Import-/Transfer-Fingerprint enthält
**niemals**:

- materialisierte JDBC-URLs oder Connection-Secrets,
- temporäre Spool-Pfade oder lokale CLI-Pfade,
- rohe SQL-/Filter-Strings ohne Kanonisierung. `data_transfer_start`
  ersetzt Caller-Filter vor der Fingerprint-Bildung durch die
  kanonische Filter-DSL-Form; datenbankspezifisches Binding bleibt
  Runner-Concern.

Fingerprint-Pflichtfelder (Plan §8.7 / §8.8): Artefakt-sha256 +
persistente Upload-Metadaten (mimeType, filename) für Imports;
beide Connection-Refs für Transfers; normalisierte Optionswerte;
Tenant + Principal.

### Quota + Timeout

Phase F erweitert die Quota-Modellierung um
`STORED_ARTIFACT_BYTES` (Plan §8.9 / F.9 1/3): beim Übergang
einer Upload-Session nach `COMPLETED` wird die Reservierung des
Init-Buckets gegen das STORED-Bucket umgebucht; Expiry oder
Finalisations-Failure releasen beide Buckets sofort.

Der `FinalisationTimeoutSweeper` (F.9 2/3) verschiebt verwaiste
`FINALIZING`-Sessions nach `OPERATION_TIMEOUT` und releast die
beanspruchte Quota; der Wert wird über
`McpServerConfig.operationTimeout` gepflegt.

`AuditFields.resourceRefs` (F.9 3/3) trägt für Upload-Handler
die finalisierten/aborted Resource-URIs (`uploadSession`-,
`artifact`-, `abortOutcome`-Refs), damit Audit-Reader ohne
Cross-Lookups die wirksame Wirkung sehen.

### Wire-Bytes: ausschliesslich `contentBase64`

`artifact_upload` überträgt Segmentbytes immer als
`contentBase64` im JSON-RPC-Argument. **Separate binäre
Upload-Bodies (Multipart, Streamable Binary) sind nicht Teil
von 0.9.6** — auch das HTTP-Transport bleibt ein normaler
JSON-RPC-POST. Diese Festlegung ist absichtlich konservativ und
hält den Wire-Vertrag identisch zwischen `stdio`- und HTTP-
Transport. Eine spätere Erweiterung kann additiv einen separaten
Upload-Body-Pfad einführen, sobald MCP-Clients das einheitlich
unterstützen.

---

## Phase G: KI-nahe Tools und MCP-Prompts

Phase G (`docs/planning/done/ImpPlan-0.9.6-G.md`) schließt den
0.9.6-MCP-Vertrag ab. Drei produktive Bausteine:

1. **AI-Tools** — `procedure_transform_plan`,
   `procedure_transform_execute`, `testdata_plan` als
   approval-driven, audit-pflichtige Tools.
2. **MCP-Prompts** — `prompts/list` + `prompts/get` mit drei
   Pflichtprompts (`procedure_analysis`,
   `procedure_transformation`, `testdata_planning`).
3. **Provider-Schicht** — `AiProviderPort` mit fail-closed-
   Konfiguration; NoOp-Default ohne Netzwerk und Secrets.

### Provider-Schicht (Plan §5.1 + §5.2)

- `AiProviderPort` ist eine sync-Funktion `(AiProviderRequest)
  → AiProviderResult` (Success/Failure-Sealed). Provider-
  spezifische Exceptions werden durch den Adapter in
  `AiProviderError` normalisiert; der Tool-Handler sieht nie
  einen Stacktrace.
- `DefaultAiProviderRegistry` erzwingt fail-closed-Konfiguration:
  - **NoOp-Default** wird automatisch ergänzt, wenn keine
    `AiProviderConfig.noOpDefault()` geliefert wurde — Plan §4.1
    "NoOp ist immer verfügbar".
  - `EXTERNAL`-Provider verlangen HTTPS-Endpoint, `secretRef` und
    `allowExternalNetwork=true`. `LOCAL_LOOPBACK` (Ollama, LM
    Studio) erlaubt `secretRef=null`, verlangt aber Loopback-Host.
  - Invalide Configs schlagen den Server-Start fehl
    (`AiProviderConfigValidator`).
- Außenseiten (Wire, `capabilities_list`, Audit) sehen
  ausschließlich `providerName`, `model`, `modelVersion` —
  niemals Endpoints oder `secretRef` (Plan §5.2 Z. 611-612).

### KI-Tool-Pipeline (Plan §6 G.6)

Jeder der drei Handler folgt demselben 7-stufigen Aufbau:

1. **Phase-1-Form-Validation** (materialisierungsfrei) —
   Required-Felder, exactly-one-Source-Variante,
   Resource-URI-Syntax. Throws `ValidationErrorException` vor
   Scope-Gate.
2. **Scope-Check** `dmigrate:ai:execute`.
3. **Single-Writer-Acquire** über `AiToolOrchestrator` +
   `AiToolOutcomeStore` (Plan §6 G.6 Z. 1071-1073). Terminale
   Outcomes (Succeeded, FailedTerminal) werden replayt; parallele
   identische Caller bekommen `OPERATION_TIMEOUT` (`InProgress`),
   abweichende Payloads `IDEMPOTENCY_CONFLICT`.
4. **Semantische Resolution** + **Policy** (`PolicyAttempt`).
5. **Provider-Quota** (`PROVIDER_CALLS`-Dimension) — Plan §6 G.8
   verbindlich: keine Secrets, kein Provider-Client, kein
   Provider-Aufruf bei `RATE_LIMITED`.
6. **Provider-Aufruf** mit Input-Hygiene (`PromptHygieneService`)
   + Output-Hygiene (Plan §7.4 — Provider-Output wird ebenfalls
   geprüft).
7. **Artefakt-Publish**: `ArtifactStore.save` +
   `ArtifactContentStore.write` + `AiArtifactMetadataStore.save`
   (atomisch zusammen). Deterministischer `artifactId` aus
   `(tenant, approvalKey, payloadFingerprint, op)`-Hash.

### KI-Artefakt-Provenance (Plan §5.4)

KI-Artefakte werden als `ArtifactKind.OTHER` gespeichert; die
fachliche Typisierung lebt in `AiArtifactMetadata`:

- `wireArtifactKind` ∈ {`procedure-transform-plan`,
  `procedure-transform-output`, `testdata-plan`}
- `aiIntent` ∈ {`procedure_transform_plan`,
  `procedure_transform_execute`, `testdata_plan`}
- `provenance` als `AiArtifactProvenance` sealed (`Plan` /
  `Execute` / `TestdataPlan`) mit operations-spezifischen
  Fingerprints
- `Execute`-Provenance bindet zusätzlich `planRef` +
  `planArtifactFingerprint`: Plan §5.5 Z. 794-799 — Source-Refs
  kommen ausschließlich aus der Plan-Provenance, nicht aus dem
  Execute-Payload.

### MCP-Prompts (Plan §5.7 + §6 G.7)

`prompts/list` und `prompts/get` sind reine Read-Methoden
(`dmigrate:read`). Pflichtprompts:

| Prompt | Pflichtargumente |
|---|---|
| `procedure_analysis` | `schemaRef` oder `artifactRef`, optional `procedureName` |
| `procedure_transformation` | `planRef`/`planArtifactId`, `targetDialect` |
| `testdata_planning` | `schemaRef`, `targetDialect`, optional `profileRef` + `rulesSummary` |

Argumentvalidierung (`PromptArgumentValidator`) prüft
required-Felder, `additionalProperties=false`-Äquivalent,
URI-Syntax, ResourceKind-Match und Tenant-Scope. Die
zusammengebaute Prompt-Nachricht läuft durch
`PromptHygieneService` — Secrets oder bulk-Daten in Argumenten
führen zu `PROMPT_HYGIENE_BLOCKED`.

Plan §4.5 verbindlich: **Prompts führen keine Tools aus**. Der
`PromptsHandler`-Konstruktor hat keinen Zugriff auf die
`ToolRegistry` — strukturell unmöglich, einen Tool-Aufruf zu
verstecken.

### Sicherheitsmodell (Plan §6 G.10)

- **Keine Secrets im Payload** — JDBC-URLs, Bearer-Tokens, API-
  Keys werden vom Hygiene-Service blockiert (Plan §6 G.4).
- **Policy für Write- und KI-Tools** — alle Tool-Handler
  laufen durch `PolicyService.decide`; `RequiresApproval`
  liefert `POLICY_REQUIRED` ohne verwendbares `approvalToken`.
- **`approvalKey` vs. `idempotencyKey`** — `approvalKey` für
  synchrone Side-Effects (Upload-Init, KI-Tools);
  `idempotencyKey` für Async-Job-Starts. Beide deduplizieren
  über `(tenant, caller, tool, key, payloadFingerprint)`.
- **Provider fail-closed** — externe Provider sind nur mit
  expliziter Konfig + `secretRef` + Policy aktivierbar; ohne
  Konfig läuft NoOp.
- **Prompt-Hygiene** — Input und Output (Plan §7.4) werden gegen
  Secret-Pattern gescannt.

### Bekannte Grenzen 0.9.6

- **NoOp ist Default** — produktive externe Provider (OpenAI,
  Anthropic, Ollama, LM Studio) brauchen explizite YAML-
  Konfiguration und sind nicht Teil des 0.9.6-Tests.
- **Externe Provider optional** — Bootstrap ohne Provider-Config
  hält den NoOp-Default; jeder Tool-Aufruf produziert
  deterministische Marker-Outputs.
- **Keine freie SQL-Ausführung** — KI-Tools produzieren Plan-
  Artefakte, keine direkten DB-Schreiboperationen.
  `procedure_transform_execute` erzeugt ein Output-Artefakt,
  führt aber keinen Ziel-DB-Code aus (Plan §5.5 Z. 778).
- **Keine Rohdaten im Prompt** — Profiling-Daten und Schema-
  Inhalte werden referenziert (`profileRef`, `schemaRef`), nicht
  inline serialisiert.
- **Keine versteckten Tool-Ausführungen durch Prompts** —
  `PromptsHandler` hat keinen Zugriff auf `ToolRegistry`.
- **`testdata_execute`** bleibt Carve-out (separate Daten-
  Schreiboperation, nicht in 0.9.6).

---

## Weiterführend

- [`docs/planning/ImpPlan-0.9.6-B.md`](../docs/planning/done/ImpPlan-0.9.6-B.md) — Komplette
  Phasen-B-Spezifikation (§5 Architektur, §12.13–§12.18 Implementation
  Contracts).
- [`docs/planning/done/ImpPlan-0.9.6-C.md`](../docs/planning/done/ImpPlan-0.9.6-C.md) —
  Phase-C: produktive Tool-Handler, Upload-Flow, AP 6.24 Integrationssuite.
- [`docs/planning/done/ImpPlan-0.9.6-D.md`](../docs/planning/done/ImpPlan-0.9.6-D.md) —
  Phase-D: Discovery, `resources/read`, HMAC-Cursor, Connection-Ref-
  Bootstrap (siehe oben "Phase D: Discovery und Ressourcen").
- [`docs/planning/done/ImpPlan-0.9.6-E.md`](../docs/planning/done/ImpPlan-0.9.6-E.md) —
  Phase-E: Async-Jobs, Idempotency, Policy, Quotas, Cancel (siehe oben
  "Phase E: Async-Jobs, Idempotency, Policy").
- [`docs/planning/done/ImpPlan-0.9.6-F.md`](../docs/planning/done/ImpPlan-0.9.6-F.md) —
  Phase-F: policy-gesteuerter `job_input`-Upload, `data_import_start`,
  `data_transfer_start`, administrative Abort-Pipeline, STORED-Quota
  (siehe oben "Phase F: Policy-gesteuerte Datenoperationen").
- [`docs/planning/done/ImpPlan-0.9.6-G.md`](../docs/planning/done/ImpPlan-0.9.6-G.md) —
  Phase-G: KI-nahe Tools, MCP-Prompts, Provider-Schicht (siehe oben
  "Phase G: KI-nahe Tools und MCP-Prompts").
- [`docs/planning/in-progress/roadmap.md`](../docs/planning/in-progress/roadmap.md) — Roadmap für 0.9.7+.
