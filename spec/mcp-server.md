# MCP-Server (Phase B)

> **Status (0.9.6 Phase B):** Transport, Authorisierung, Discovery und
> JSON-Schemas sind fertig — fachlich liefert der Server in Phase B nur
> `capabilities_list` (siehe §12.11). Alle anderen 0.9.6-Tools sind in
> der Tool-Registry geführt, dispatchen aber zum
> `UnsupportedToolHandler` und antworten mit
> `ToolErrorEnvelope.code = UNSUPPORTED_TOOL_OPERATION`. Phase C/D
> ergänzen die echten Handler.

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
| Tool-Handler (außer `capabilities_list`)     | **Nicht implementiert** — `UNSUPPORTED_TOOL_OPERATION`       |
| `resources/read`                             | **Nicht implementiert** — Phase D                            |
| SSE-Push / `notifications/*`                 | **Nicht implementiert**                                      |
| `subscribe`/`listChanged` Capabilities      | Beide `false` (§12.16, §12.17)                              |
| `connections/list` (Admin-Filter)           | **Nicht implementiert** als Tool                             |
| `job_cancel`                                 | Registry-Eintrag — Handler folgt Phase C/D                   |
| Upload-Session-Tools                         | Registry-Einträge — Handler folgen Phase C/D                 |
| AI-Tools (procedure_transform_*, testdata_*) | Registry-Einträge — Handler folgen Phase C/D                 |
| Resource-Stores (Real-Backends)              | `ResourceStores.empty()` als Default — Phase C/D verdrahtet |
| Cross-Tenant-Reads                           | **Nicht implementiert**                                      |
| OAuth Authorization Server / DCR             | **Nicht implementiert**                                      |
| Multi-Scope-Tools                            | Heute nicht im Default-Mapping — Wire-Format ist vorbereitet |

Eine Verfeinerung der `inputSchema`/`outputSchema`-Definitionen pro
Tool kommt in Phase C/D mit den jeweiligen Handlern; Phase-B-Schemas
sind typisiert auf die offensichtlichen Top-Level-Argumente und durch
einen Golden-Test gegen Drift gepinnt
(`adapters/driving/mcp/src/test/resources/golden/phase-b-tool-schemas.json`).

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

---

## Weiterführend

- [`docs/planning/ImpPlan-0.9.6-B.md`](../docs/planning/done/ImpPlan-0.9.6-B.md) — Komplette
  Phasen-B-Spezifikation (§5 Architektur, §12.13–§12.18 Implementation
  Contracts).
- [`docs/planning/roadmap.md`](../docs/planning/roadmap.md) — Plan für Phase C/D
  (Tool-Handler, `resources/read`, Upload-Sessions, AI-Tools).
