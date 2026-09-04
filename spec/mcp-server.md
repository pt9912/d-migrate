# MCP-Server

> **Vertrag:** d-migrate-MCP-Contract **v1** auf Basis von
> **MCP 2025-11-25**. Der Server stellt bereit: typisierte Schema- und
> Daten-Tools, Discovery (`*_list`) und produktives `resources/read`,
> asynchrone Jobs (Idempotency, Policy, Quota, Cancel), policy-gesteuerte
> Datenoperationen (`job_input`-Upload, `data_import_start`,
> `data_transfer_start`) sowie KI-nahe Tools
> (`procedure_transform_plan`/`execute`, `testdata_plan`) und MCP-Prompts
> (`prompts/list`/`prompts/get`). Die Verträge sind unten je
> Funktionsbereich beschrieben.

Der MCP-Server ist ein Driving-Adapter zu d-migrate
(`adapters/driving/mcp`) und implementiert **MCP 2025-11-25** mit
**stdio**- und **Streamable-HTTP**-Transport.

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
- `GET /mcp` antwortet HTTP 405 (kein SSE).
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

**Request-Härtung:** Der `POST /mcp`-Body ist auf `maxRequestBodyBytes`
(Default 8 MiB) begrenzt; ein größerer Body wird per `Content-Length` mit
`413` abgewiesen, **bevor** er in den Speicher gelesen wird. Die
Bearer-Validierung läuft **vor** dem Body-Read, damit ein
unauthentifizierter Request den Server nicht über einen großen POST
erschöpfen kann.

#### Validierungsregeln (§12.12)

- `port` ∈ `[0, 65535]`.
- `clockSkew` ∈ `[0s, 5min]`.
- `authMode == disabled` → `bind` MUSS Loopback sein, `publicBaseUrl`
  MUSS `null` sein.
- `authMode in {jwt-jwks, jwt-introspection}` → `issuer`, `audience`
  und (je nach Modus) `jwks-url` oder `introspection-url` MÜSSEN
  gesetzt sein.
- `publicBaseUrl != null` → MUSS `https`-Schema haben.
- `jwks-url` bzw. `introspection-url` MÜSSEN `https`-Schema haben; ein
  Loopback-Host (`localhost`, `127.0.0.0/8`, `::1`) darf `http` nutzen
  (Dev-IdP). Ein routbarer `http`-Endpoint ist ein Startfehler — die
  JWKS-URL ist der Vertrauensanker der Token-Prüfung, und der
  Introspection-Endpoint trägt das Client-Secret im Klartext.
- `allowedOrigins` darf `*` (Wildcard) nicht enthalten; bei
  Nicht-Loopback-Bind MUSS die Liste explizit gesetzt werden.
- `algorithmAllowlist` darf `none` und `HS*` nicht enthalten.
- `stdioTokenFile != null` → Datei MUSS lesbar sein.

---

## Capabilities & Tools

### `capabilities_list`

Liefert einen Snapshot der Server-Capabilities:

```json
{
  "mcpProtocolVersion": "2025-11-25",
  "dmigrateContractVersion": "v1",
  "serverName": "d-migrate",
  "tools": [ /* alle Tools mit Scope-Anforderungen */ ],
  "scopeTable": {
    "dmigrate:read": ["capabilities_list", "schema_validate", ...],
    "dmigrate:job:start": ["schema_reverse_start", ...],
    ...
  }
}
```

### `tools/list` und `tools/call`

`tools/list` liefert für jedes Tool:
- `name`, `title`, `description`
- `inputSchema` und `outputSchema` (JSON Schema 2020-12, §12.18)
- `requiredScopes` (d-migrate-Erweiterung)

`tools/call` für `capabilities_list` läuft fachlich; alle anderen
Tools antworten mit `ToolsCallResult(isError=true,
content=[ToolErrorEnvelope(code=UNSUPPORTED_TOOL_OPERATION, ...)])`.

### `resources/list` und `resources/templates/list`

Walks Jobs → Artifacts → Schemas → Profiles → Diffs → Connections.
Pagination per opaquem `nextCursor` (§12.17). Connection-Refs werden
**ohne Secrets** projiziert (§6.9-Akzeptanz). Ein nicht verdrahteter
`ResourceStores.empty()`-Default liefert leere Listen; produktive
Deployments verdrahten echte Stores.

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

## Abgrenzung (Nicht-Ziele)

Bewusst **nicht** Teil des MCP-Servers:

- **Kein SSE-/Server→Client-Push.** Der HTTP-Transport ist Request/Response
  (`POST /mcp`); `GET /mcp` → `405 Method Not Allowed`, die Capabilities
  `subscribe`/`listChanged` sind `false`, und es gibt keine server-initiierten
  `notifications/*`. Job-Fortschritt wird per `job_status_get` gepollt.
- **Kein eigener OAuth-Authorization-Server / keine Dynamic Client Registration.**
  Der Server ist OAuth-**Resource-Server**: er validiert extern ausgestellte JWTs
  (`jwt-jwks`/`jwt-introspection`) gegen einen OIDC-Issuer und liefert
  RFC-9728-Metadata, stellt aber selbst keine Tokens aus.

---

## Discovery und Ressourcen

Discovery macht Jobs, Artefakte, Schemas, Profile, Diffs und
Connection-Refs ueber MCP auffindbar und gezielt lesbar — additiv zu
den Basis-Tools, ohne bestehende Wire-Vertraege zu brechen.

### Discovery-Tools (`*_list`)

Es gibt fuenf Discovery-Tools, alle mit
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
Capabilities-Provider-Function (legacy-Pfad) kollabiert
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
- `sort` (heute immer `null`; reserviert)
- `version`, `kid`, `issuedAt`, `expiresAt` (TTL 15 min)

`artifact_chunk_get` produziert einen HMAC-gesealtenen
`nextChunkCursor` zusaetzlich zum `nextChunkUri`. Bindung:
(tenant, artifactId, chunkSize). Eingangsseitig akzeptiert das Tool
weiterhin den nackten `chunkId`-Integer (befristete
Kompatibilitaet) und wirft `VALIDATION_ERROR`, wenn beide gesetzt
sind. Der Output enthaelt nie ein `nextChunkId`-Feld.

Manipulierte Cursor (HMAC-Signatur falsch, Tenant-/Filter-/Page-
Size-Mismatch) kollabieren auf `VALIDATION_ERROR` — Tool-Pfade
ueber das Tool-Error-Envelope, `resources/list` ueber
JSON-RPC-`-32602`. Multi-Instanz-Deployments muessen einen
deterministischen `cursorKeyring` wiren; der Default-Random-
Keyring funktioniert nur fuer Single-Instance-Setups.

Unsignierte Alt-Cursor werden nicht dual-read-faehig gemacht, sobald
ein HMAC-Codec gewired ist. Der alte unsigned `resources/list`-Cursor
(Base64 von `{kind, innerToken}`) bleibt nur in Deployments ohne
Codec gueltig. Produktive Deployments mit Codec
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

Es gibt einen adapter-neutralen Bootstrap fuer Connection-
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
  `allowedPrincipalIds`, `allowedScopes`). Die bare-URL-String-Form
  wird silent gedroppt — der Discovery-Pfad materialisiert keine
  expandierten Secrets.
- `EnvConnectionSecretResolver` — Default-Resolver fuer das
  `env:VAR_NAME`-Schema. Authorisiert via
  `allowedPrincipalIds`/`allowedScopes` mit Admin-Bypass.

`resources/read` auf eine Connection-URI dropt `credentialRef`,
`providerRef`, `allowedPrincipalIds` und `allowedScopes` aus der
Wire-Projektion. Discovery-Konsumenten sehen ausschliesslich
`connectionId`, `tenantId`, `displayName`, `dialectId`, `sensitivity`.

### `connections/list`

Eigener Protokoll-Slot (kein `tools/call`-Tool, aus `tools/list`
ausgeschlossen wie `resources/list`), Scope `dmigrate:admin`
(ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md Slice B).

```jsonc
// Input
{
  "tenantId": "<optional — Default: eigener Tenant>",
  "pageSize": 50,
  "cursor": "<optional, HMAC-versiegelt>",
  "checkLive": false
}

// Output
{
  "connections": [
    {
      "connectionId": "conn-1",
      "displayName": "Local DB",
      "dialectId": "postgresql",
      "sensitivity": "NON_PRODUCTION",
      "status": null
    }
  ],
  "nextCursor": null
}
```

Zwei Abweichungen von den übrigen Discovery-Pfaden, beide bewusst und
scope-begründet:

- **Cross-Tenant-Adressierung.** `resources/list`/`resources/read`
  zeigen ausschliesslich `principal.effectiveTenantId`. `tenantId` bei
  `connections/list` darf jeden Tenant aus `allowedTenantIds`
  adressieren (nicht mehrere gleichzeitig — ein Tenant pro Aufruf, wie
  bei den `*_list`-Tools) — das ist der Sinn des `dmigrate:admin`-Scopes
  für genau diese Methode.
- **`checkLive=true` ruft den `ConnectionSecretResolver` auf.** Der
  Satz „Discovery darf den Resolver NIE aufrufen" oben gilt für den
  metadaten-only Pfad (`checkLive=false`, Default) unverändert.
  `checkLive=true` ist die einzige dokumentierte Ausnahme: ein kurzer
  (2s Timeout), unbewaffneter (`maximumPoolSize=1`) Verbindungsversuch
  pro Connection. Das Ergebnis ist ausschließlich eine grobe
  Statuskategorie — `REACHABLE`, `UNREACHABLE` oder
  `CREDENTIAL_ERROR` — nie die rohe Exception-Message (kein Host/Port/
  Netzwerkdetail auf dem Wire; volle Details nur serverseitig im Log
  bei DEBUG).

Response-Projektion ist wie bei `resources/read` minimal: kein
`credentialRef`/`providerRef`/`allowedPrincipalIds`/`allowedScopes`.

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
| `--connection-config`       | Project/server YAML fuer Connection-Refs. Wenn nicht gesetzt, wird ein globales `--config <path>` wiederverwendet. |
| `--cursor-keyring-file`     | YAML-Keyring fuer deterministische HMAC-Cursor in Multi-Instanz-Deployments. |
| `--policy-file`             | JSON/YAML mit `PolicyRule`-Eintraegen (Allow/Challenge/Deny pro Tool/Tenant/Aufrufer), einmal beim Start geladen. Ohne Angabe bleibt die Regelliste leer (fail-closed Default, siehe „Policy-Regeln konfigurieren" unten). |

---

## Async-Jobs, Idempotency, Policy

Vier Job-Tools:

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
// Output
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

**Grant-Aussteller-Modi**:

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

### Policy-Regeln konfigurieren

Die Entscheidung `Allowed` / `RequiresApproval` / `Denied` je Start-
Versuch trifft eine geordnete Liste von `PolicyRule`-Einträgen: die
erste Regel, deren `tenantId`/`toolName`/`callerId` passt (`null` =
Wildcard), bestimmt den Effekt; matcht keine, greift der Default
`Deny("policy:no-rule")` — fail-closed.

Die Regelliste wird über `--policy-file <pfad>` befüllt: eine JSON-/
YAML-Datei mit einem `rules`-Array, **einmal beim Start** geladen
(kein Hot-Reload — eine Änderung verlangt einen Neustart, damit sich
das Sicherheitsverhalten nicht unbemerkt mitten im Betrieb ändert):

```yaml
rules:
  - tenantId: acme            # optional, weggelassen = alle Tenants
    toolName: schema_reverse_start
    effect: allow
  - toolName: data_import_start
    effect: challenge
    requiredScopes: [dmigrate:writer]
    reasons: ["writes require approval"]
  - effect: deny
    reasonCode: policy:blocked-by-operator
```

Ein Eintrag mit `effect: challenge` erzeugt `PolicyDecision
.RequiresApproval` (siehe „Approval-Flow" oben) mit den angegebenen
`requiredScopes`/`reasons`; `effect: deny` erzeugt `PolicyDecision
.Denied(reasonCode)`. Eine ungültige Datei (kaputtes YAML/JSON,
unbekannter `effect`-Wert, fehlendes `reasonCode`/`requiredScopes`)
lässt den Server nicht starten, statt still auf den fail-closed-Default
zurückzufallen.

### Quotas + Rate-Limiting

Aktive Jobs werden pro `(tenantId, ACTIVE_JOBS, principalId, operation)`
gezählt. Überschreitet eine neue Reservierung den Limit-Wert, antwortet
der Start mit `RATE_LIMITED`:

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

Wichtig: RATE_LIMITED entsteht **vor**
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
ein `AuditEvent` (SUCCESS oder FAILURE mit ToolErrorCode). Job-
Outcomes bekommen damit automatisch Audit-Coverage. Reasons (z.B. im
Cancel-Pfad) werden über `SecretScrubber` gescrubbed bevor sie in
`cancelRequestedReason` oder Audit-Felder wandern.

---

## Policy-gesteuerte Datenoperationen

Drei policy-gesteuerte Bausteine:

1. den **policy-gesteuerten `job_input`-Upload** über
   `artifact_upload_init` / `artifact_upload` /
   `artifact_upload_abort` (zusätzlich zum read-only
   `schema_staging_readonly`-Pfad),
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
Payload-Fingerprint.

### Upload-Intent-Trennung

`uploadIntent` separiert read-only Schema-Staging und
write-nahe `job_input`-Uploads:

| Intent                        | Scope-Gate                                | Default-Schutz                                                                                |
| ----------------------------- | ----------------------------------------- | --------------------------------------------------------------------------------------------- |
| `schema_staging_readonly`     | `dmigrate:read`                           | nur Quota + Audit; idempotent über `clientRequestId`.                                         |
| `job_input`                   | `dmigrate:artifact:upload`                | policy-gesteuert mit `approvalKey` + Init-Fingerprint; finalisiertes Artefakt ist `UPLOAD_INPUT`. |

Read-only Staging-Artefakte (`SCHEMA`-Kind) dürfen nicht still
als `job_input` weiterverwendet werden — der `data_import_start`-
Handler erzwingt `kind=UPLOAD_INPUT` und liefert sonst
`VALIDATION_ERROR`.

### `artifact_upload_init` — Felder

Zusätzlich zu den Basis-Feldern (`uploadIntent`,
`expectedSizeBytes`/`sizeBytes`, `checksumSha256`, `filename`)
nimmt der Init-Pfad entgegen:

- `approvalKey` — verbindlich für `uploadIntent=job_input`;
  bindet Idempotenz und Policy-Challenge an
  (`tenantId`,`callerId`,`approvalKey`,Init-Fingerprint).
- `mimeType` — optional, default `application/octet-stream`.
  Allowlist siehe `spec/ki-mcp.md` §8.3 (CSV erlaubt:
  `text/csv` / `application/csv`).
- `artifactKind` — verpflichtend, eines aus `schema`, `ddl`,
  `transform-script`, `seed-data`, `rules`, `generic`.
- `targetTable` — optional Tabellenbindung für Single-File-
  Imports; verboten für `schema_staging_readonly`.
- `clientRequestId` — optional, nur für `schema_staging_readonly`
  resumable.

`sizeBytes=0` ist nur für nicht-Schema-`job_input` als Single-
Empty-Segment erlaubt; `artifactKind=schema` mit `sizeBytes=0`
liefert `VALIDATION_ERROR`.

`uploadSessionTtlSeconds` startet bei 900s mit absoluter Hard-
Cap 3600s ab Session-Erzeugung; jede erfolgreiche Segmentannahme
darf bis 3600s verlängern. Idle-Timeout 300s. Session-Quota
`STORED_ARTIFACT_BYTES` wird beim Übergang nach `COMPLETED`
gegen das Init-Reserve-Bucket umgebucht.

### Administrative Abort-Pipeline

`artifact_upload_abort` deckt zwei Pfade:

- **Owner-Abort** — eigene aktive Session, ohne Approval-Token,
  über `dmigrate:artifact:upload`-Scope.
- **Administrative Abort** — `reason` + `approvalKey` + Admin-
  Scope; Outcome wird als `AbortOutcome` in einem persistenten
  Store geschrieben und über `correlationKey=approvalKey` +
  Fingerprint dedupliziert. Approval-Reuse für andere
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

Fingerprint-Pflichtfelder: Artefakt-sha256 +
persistente Upload-Metadaten (mimeType, filename) für Imports;
beide Connection-Refs für Transfers; normalisierte Optionswerte;
Tenant + Principal.

### Quota + Timeout

Die Quota-Modellierung umfasst
`STORED_ARTIFACT_BYTES`: beim Übergang
einer Upload-Session nach `COMPLETED` wird die Reservierung des
Init-Buckets gegen das STORED-Bucket umgebucht; Expiry oder
Finalisations-Failure releasen beide Buckets sofort.

Der `FinalisationTimeoutSweeper` verschiebt verwaiste
`FINALIZING`-Sessions nach `OPERATION_TIMEOUT` und releast die
beanspruchte Quota; der Wert wird über
`McpServerConfig.operationTimeout` gepflegt.

`AuditFields.resourceRefs` trägt für Upload-Handler
die finalisierten/aborted Resource-URIs (`uploadSession`-,
`artifact`-, `abortOutcome`-Refs), damit Audit-Reader ohne
Cross-Lookups die wirksame Wirkung sehen.

### Wire-Bytes: ausschliesslich `contentBase64`

`artifact_upload` überträgt Segmentbytes immer als
`contentBase64` im JSON-RPC-Argument. **Separate binäre
Upload-Bodies (Multipart, Streamable Binary) sind nicht
vorgesehen** — auch das HTTP-Transport bleibt ein normaler
JSON-RPC-POST. Diese Festlegung ist absichtlich konservativ und
hält den Wire-Vertrag identisch zwischen `stdio`- und HTTP-
Transport. Eine spätere Erweiterung kann additiv einen separaten
Upload-Body-Pfad einführen, sobald MCP-Clients das einheitlich
unterstützen.

---

## KI-nahe Tools und MCP-Prompts

Drei Bausteine:

1. **AI-Tools** — `procedure_transform_plan`,
   `procedure_transform_execute`, `testdata_plan` als
   approval-driven, audit-pflichtige Tools.
2. **MCP-Prompts** — `prompts/list` + `prompts/get` mit drei
   Pflichtprompts (`procedure_analysis`,
   `procedure_transformation`, `testdata_planning`).
3. **Provider-Schicht** — `AiProviderPort` mit fail-closed-
   Konfiguration; NoOp-Default ohne Netzwerk und Secrets.

### Provider-Schicht

- `AiProviderPort` ist eine sync-Funktion `(AiProviderRequest)
  → AiProviderResult` (Success/Failure-Sealed). Provider-
  spezifische Exceptions werden durch den Adapter in
  `AiProviderError` normalisiert; der Tool-Handler sieht nie
  einen Stacktrace.
- `DefaultAiProviderRegistry` erzwingt fail-closed-Konfiguration:
  - **NoOp-Default** wird automatisch ergänzt, wenn keine
    `AiProviderConfig.noOpDefault()` geliefert wurde — NoOp ist
    immer verfügbar.
  - `EXTERNAL`-Provider verlangen HTTPS-Endpoint, `secretRef` und
    `allowExternalNetwork=true`. `LOCAL_LOOPBACK` (Ollama, LM
    Studio) erlaubt `secretRef=null`, verlangt aber Loopback-Host.
  - Invalide Configs schlagen den Server-Start fehl
    (`AiProviderConfigValidator`).
- Außenseiten (Wire, `capabilities_list`, Audit) sehen
  ausschließlich `providerName`, `model`, `modelVersion` —
  niemals Endpoints oder `secretRef`.

### KI-Tool-Pipeline

Jeder der drei Handler folgt demselben 7-stufigen Aufbau:

1. **Phase-1-Form-Validation** (materialisierungsfrei) —
   Required-Felder, exactly-one-Source-Variante,
   Resource-URI-Syntax. Throws `ValidationErrorException` vor
   Scope-Gate.
2. **Scope-Check** `dmigrate:ai:execute`.
3. **Single-Writer-Acquire** über `AiToolOrchestrator` +
   `AiToolOutcomeStore`. Terminale
   Outcomes (Succeeded, FailedTerminal) werden replayt; parallele
   identische Caller bekommen `OPERATION_TIMEOUT` (`InProgress`),
   abweichende Payloads `IDEMPOTENCY_CONFLICT`.
4. **Semantische Resolution** + **Policy** (`PolicyAttempt`).
5. **Provider-Quota** (`PROVIDER_CALLS`-Dimension), verbindlich:
   keine Secrets, kein Provider-Client, kein
   Provider-Aufruf bei `RATE_LIMITED`.
6. **Provider-Aufruf** mit Input-Hygiene (`PromptHygieneService`)
   + Output-Hygiene (auch der Provider-Output wird
   geprüft).
7. **Artefakt-Publish**: `ArtifactStore.save` +
   `ArtifactContentStore.write` + `AiArtifactMetadataStore.save`
   (atomisch zusammen). Deterministischer `artifactId` aus
   `(tenant, approvalKey, payloadFingerprint, op)`-Hash.

### KI-Artefakt-Provenance

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
  `planArtifactFingerprint`: Source-Refs
  kommen ausschließlich aus der Plan-Provenance, nicht aus dem
  Execute-Payload.

### MCP-Prompts

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

Verbindlich: **Prompts führen keine Tools aus**. Der
`PromptsHandler`-Konstruktor hat keinen Zugriff auf die
`ToolRegistry` — strukturell unmöglich, einen Tool-Aufruf zu
verstecken.

### Sicherheitsmodell

- **Keine Secrets im Payload** — JDBC-URLs, Bearer-Tokens, API-
  Keys werden vom Hygiene-Service blockiert.
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
- **Prompt-Hygiene** — Input und Output werden gegen
  Secret-Pattern gescannt.

### Grenzen und Nicht-Ziele

- **NoOp ist Default** — produktive externe Provider (OpenAI,
  Anthropic, Ollama, LM Studio) brauchen explizite YAML-
  Konfiguration und sind nicht Teil der getesteten Standardkonfiguration.
- **Externe Provider optional** — Bootstrap ohne Provider-Config
  hält den NoOp-Default; jeder Tool-Aufruf produziert
  deterministische Marker-Outputs.
- **Keine freie SQL-Ausführung** — KI-Tools produzieren Plan-
  Artefakte, keine direkten DB-Schreiboperationen.
  `procedure_transform_execute` erzeugt ein Output-Artefakt,
  führt aber keinen Ziel-DB-Code aus.
- **Keine Rohdaten im Prompt** — Profiling-Daten und Schema-
  Inhalte werden referenziert (`profileRef`, `schemaRef`), nicht
  inline serialisiert.
- **Keine versteckten Tool-Ausführungen durch Prompts** —
  `PromptsHandler` hat keinen Zugriff auf `ToolRegistry`.

---

## Verwandte Spezifikationen

- [`ki-mcp.md`](./ki-mcp.md) — fachliches MCP-Zielbild (Tools, Ressourcen, Fehler, Prompts).
- [`job-contract.md`](./job-contract.md) — Async-Job- und Polling-Vertrag.
- [`cli-spec.md`](./cli-spec.md) — CLI-Vertrag von `mcp serve` und `mcp cursor-key`.
