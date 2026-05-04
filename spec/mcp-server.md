# MCP-Server (Phase B + C + D)

> **Status (0.9.6):** Phase B (Transport / Auth / Discovery /
> JSON-Schemas), Phase C (typisierte Schema-Tools, Upload-Flow,
> `job_status_get`, `artifact_chunk_get`) und Phase D (Discovery-
> Listen-Tools, produktives `resources/read`, HMAC-Cursor,
> Connection-Ref-Bootstrap) sind abgeschlossen. Verbleibende
> `UNSUPPORTED_TOOL_OPERATION`-Tools sind die Phase-E Job-Start-
> Tools (`schema_reverse_start`, `data_profile_start`,
> `schema_compare_start`, `data_export_start`) und die Phase-F
> AI-Tools. Details der jeweiligen Phase: §"Phase D: Discovery und
> Ressourcen" unten + die done-Pläne unter `docs/planning/done/`.

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
| Upload-Session-Tools                         | **Phase C produktiv** (`artifact_upload_init`, `artifact_upload`, `artifact_upload_abort`). |
| AI-Tools (procedure_transform_*, testdata_*) | Registry-Einträge — Handler folgen Phase F (AI-Tools).      |
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

## Weiterführend

- [`docs/planning/ImpPlan-0.9.6-B.md`](../docs/planning/done/ImpPlan-0.9.6-B.md) — Komplette
  Phasen-B-Spezifikation (§5 Architektur, §12.13–§12.18 Implementation
  Contracts).
- [`docs/planning/done/ImpPlan-0.9.6-C.md`](../docs/planning/done/ImpPlan-0.9.6-C.md) —
  Phase-C: produktive Tool-Handler, Upload-Flow, AP 6.24 Integrationssuite.
- [`docs/planning/done/ImpPlan-0.9.6-D.md`](../docs/planning/done/ImpPlan-0.9.6-D.md) —
  Phase-D: Discovery, `resources/read`, HMAC-Cursor, Connection-Ref-
  Bootstrap (siehe oben "Phase D: Discovery und Ressourcen").
- [`docs/planning/in-progress/roadmap.md`](../docs/planning/in-progress/roadmap.md) — Plan für Phase E+
  (Tool-Handler, `resources/read`, Upload-Sessions, AI-Tools).
