# API-Referenz: d-migrate (CLI + MCP)

**Contract-Version:** `v1`
**Stabilität:** CLI und MCP-Server sind Beta (Contract `v1`). REST- und
gRPC-API sind **nicht** Teil dieser Referenz.

Diese Referenz beschreibt die maschinennahen Schnittstellen von d-migrate so
exakt, dass man dagegen automatisieren kann. Aufgabenorientierte Anleitungen
stehen im [Anwenderhandbuch](anwenderhandbuch.md); die normativen Verträge in
[`cli-spec.md`](../../spec/cli-spec.md), [`mcp-server.md`](../../spec/mcp-server.md)
und [`job-contract.md`](../../spec/job-contract.md).

## Inhalt

1. [Überblick](#1-überblick)
2. [Allgemeine Konzepte](#2-allgemeine-konzepte)
3. [CLI-Referenz](#3-cli-referenz)
4. [MCP-Server-Referenz](#4-mcp-server-referenz)
5. [Sicherheit und Datenschutz](#5-sicherheit-und-datenschutz)
6. [Verwandte Spezifikationen](#6-verwandte-spezifikationen)

---

## 1. Überblick

**Zweck.** d-migrate bietet zwei programmatische Schnittstellen:

| Schnittstelle | Form | Zielgruppe |
| ------------- | ---- | ---------- |
| **CLI** | lokales Kommando `d-migrate` (Prozess pro Aufruf) | Skripte, CI/CD, lokale Automatisierung |
| **MCP-Server** | `d-migrate mcp serve` (stdio oder Streamable-HTTP) | KI-Agenten, netzwerkbasierte Integration |

- **Datenformat:** Ein-/Ausgabe als JSON oder YAML. **Feldnamen, Command-IDs,
  Status- und Fehlercodes sind englisch und stabil**; lokalisiert werden nur
  menschenlesbare Plain-Text-Ausgaben auf stdout/stderr.
- **Zeitstempel:** ISO-8601/RFC-3339, UTC.
- **MCP-Protokoll:** `2025-11-25` (Streamable HTTP), d-migrate-Contract `v1`.

---

## 2. Allgemeine Konzepte

### 2.1 Verbindungsangabe

| Kontext | Form | Secret-Quelle |
| ------- | ---- | ------------- |
| CLI direkt | `dialect://user:password@host:port/db?params` | in der URL |
| CLI benannt | Name aus `database.connections` in `.d-migrate.yaml` | URL bzw. `${VAR}` in der Config |
| MCP | Resource-Ref `dmigrate://tenants/<t>/connections/<id>` mit `credentialRef: "env:<VAR_NAME>"` | Prozess-Umgebungsvariable `<VAR_NAME>` |

URL-Syntax, Dialekt-Aliase und Parameter: [`connection-config-spec.md`](../../spec/connection-config-spec.md).
Der MCP-Secret-Resolver ist **fail-closed**: Eine Connection ohne wired Secret
provider wird abgelehnt, nicht stillschweigend ohne Secret geöffnet.

### 2.2 Exit-Codes (CLI)

| Code | Name | Bedeutung |
| ---- | ---- | --------- |
| `0` | SUCCESS | erfolgreich |
| `1` | ERROR | allgemeiner Fehler (auch: `schema compare` „Unterschiede gefunden") |
| `2` | USAGE_ERROR | ungültige Argumente/Flags |
| `3` | VALIDATION_ERROR | Schema-/Daten-Validierung fehlgeschlagen / Resume-Inkompatibilität |
| `4` | CONNECTION_ERROR | Datenbankverbindung fehlgeschlagen |
| `5` | MIGRATION_ERROR | Fehler nach Ausführungsbeginn (z. B. Constraint-Verletzung) |
| `6` | AI_ERROR | KI-Provider nicht erreichbar/fehlgeschlagen |
| `7` | LOCAL_ERROR | Konfigurations-/Parse-/Datei-/I/O-Fehler |
| `8` | MIGRATION_BLOCKED | Migration nicht renderbar (Risiko-/Rollback-/Dialektblocker) |
| `130` | INTERRUPTED | durch SIGINT (Ctrl+C) abgebrochen |

Regeln: Warnungen ohne Fehler → `0`; bei mehreren Fehlern der spezifischste Code;
Exit-Code bleibt bei `--output-format json|yaml` gleich.

### 2.3 Ausgabeformate

`--output-format plain` (Default, menschenlesbar) · `json` · `yaml`. Bei `json`/
`yaml` liegt die Nutzlast auf `stdout` bzw. in `--output`; der Vertrag ist stabil
und englisch. Beispiel `schema validate --output-format json`:

```json
{
  "command": "schema.validate",
  "status": "failed",
  "exit_code": 3,
  "schema": { "name": "Webshop", "version": "1.0.0" },
  "summary": { "tables": 2, "columns": 9, "indices": 1, "constraints": 0 },
  "results": [
    { "level": "error", "object": "orders.customer_id",
      "message": "Foreign key references non-existent table 'payments'",
      "code": "E002" }
  ],
  "errors": 1,
  "warnings": 0
}
```

### 2.4 Fehler- und Warnungscodes

Namensschema: `E001–E099` Validierung · `E100–E199` Verbindung · `E200–E299`
Migration · `E300–E399` KI · `E400–E499` Konfiguration · `W001–W099`
Validierungswarnungen · `W100–W199` Kompatibilität · `W200–W299` Performance.
Auswahl: `E002` FK→fehlende Tabelle · `E008` kein Primärschlüssel · `E053`
dialektspezifischer Inhalt erfordert manuelle Umschreibung · `E054` Objekttyp im
Ziel nicht unterstützt · `E055` Partitionierung nicht unterstützt · `E056`
Sequenz braucht Emulation · `E101` Connection refused · `E102` Auth
fehlgeschlagen · `W100` Zeitzonenverlust · `W103` Materialized View → View.
Vollständig: [`cli-spec.md`](../../spec/cli-spec.md) §4.

### 2.5 stdout / stderr / Piping

- **Ergebnisse → stdout, Fortschritt/Warnungen → stderr.** Pipebar:
  `… --output-format json | jq '.errors'`.
- Bei Nicht-Terminal-stdout werden Farbe und Fortschrittsanzeige automatisch
  deaktiviert.
- `-` als Pfad bedeutet stdin/stdout (heute: `schema validate --source -`).

### 2.6 Umgebungsvariablen

Tatsächlich vom Code gelesen:

| Variable | Entspricht / Wirkung |
| -------- | -------------------- |
| `D_MIGRATE_CONFIG` | wie `--config` (effektiver Config-Pfad) |
| `D_MIGRATE_LANG` | wie `--lang` (toleranter als `--lang`: gültige Locales → Root-Bundle-Fallback) |
| `DMIGRATE_MCP_STDIO_TOKEN` | stdio-Principal-Token (sha256-Fingerprint) |
| `DMIGRATE_MCP_STATE_DIR` | wie `--mcp-state-dir` |
| `DMIGRATE_MCP_STATE_ORPHAN_RETENTION` | wie `--mcp-state-orphan-retention` |
| `D_MIGRATE_SERVER_STATE_JDBC_URL` / `_USERNAME` / `_PASSWORD` | JDBC-backed Server-State (MCP-Persistenz) |
| `D_MIGRATE_SERVER_STATE_HIKARI_*` / `_MIGRATIONS_AUTO` | Pool-/Migrations-Konfiguration des Server-State |
| `D_MIGRATE_SERVER_JOBS_EXECUTOR_*` | Job-Executor-Konfiguration (Mode, Threads, Queue, Retry, Shutdown) |

> **Hinweis:** [`cli-spec.md`](../../spec/cli-spec.md) §9 listet zusätzlich
> `D_MIGRATE_OUTPUT_FORMAT`, `D_MIGRATE_NO_COLOR`, `D_MIGRATE_ASSUME_YES`,
> `D_MIGRATE_DB_PASSWORD` und `D_MIGRATE_AI_API_KEY`. Diese werden vom aktuellen
> Code **nicht** gelesen (Spec ist hier voraus). Passwörter kommen über die URL,
> `${VAR}` in `.d-migrate.yaml` bzw. den MCP-`env:`-Secret-Ref (siehe 2.1).

---

## 3. CLI-Referenz

### 3.1 Aufrufsyntax und globale Optionen

```
d-migrate [globale Optionen] <gruppe> <befehl> [befehlsoptionen]
```

Gruppen: `schema`, `data`, `export`, `mcp`. `--help` an jeder Ebene.

| Globale Option | Wirkung |
| -------------- | ------- |
| `-c`, `--config <pfad>` | Konfigurationsdatei |
| `--lang <de\|en>` | Ausgabesprache (Vorrang vor `D_MIGRATE_LANG`/`LC_ALL`/`LANG`; ungültig → Exit 2) |
| `--output-format <plain\|json\|yaml>` | Ausgabeformat (Default `plain`) |
| `-v`, `--verbose` / `-q`, `--quiet` | DEBUG / nur Fehler (schließen sich aus) |
| `--no-color` / `--no-progress` | Farbe / Fortschritt aus |
| `-y`, `--yes` | Bestätigungen automatisch akzeptieren |
| `--version` / `-h`, `--help` | Version / Hilfe |

### 3.2 Kommando-Übersicht

| Befehl | Zweck | Schreibt DB? |
| ------ | ----- | :----------: |
| `schema validate` | Schema gegen Regeln prüfen | nein |
| `schema compare` | zwei Schemas/DBs vergleichen (Exit 1 = Diff) | nein |
| `schema generate` | DDL für einen Zieldialekt erzeugen | nein |
| `schema reverse` | DB → neutrales Schema | nein |
| `schema migrate` | Diff Soll↔Ist planen/ausführen | mit `--execute` |
| `schema rollback` | Down-SQL-Artefakt ausführen | mit `--execute` |
| `export flyway\|liquibase\|django\|knex` | Framework-Migrationsdateien | nein |
| `data export` | Tabellen → Datei (JSON/YAML/CSV/Parquet) | nein |
| `data import` | Datei → Tabellen | ja |
| `data transfer` | DB → DB direkt | ja |
| `data profile` | Datenqualitäts-Report | nein |
| `mcp serve` | MCP-Server starten | — |
| `mcp approval-grant issue` / `mcp cursor-key generate\|validate` | MCP-Administration | — |

Die **vollständigen Optionstabellen je Befehl** stehen im
[Anwenderhandbuch, Anhang A](anwenderhandbuch.md#anhang-a--befehls--und-optionsreferenz);
die normative Definition in [`cli-spec.md`](../../spec/cli-spec.md) §6. Jeder
Befehl unterstützt `--output-format json|yaml` für maschinenlesbare Ergebnisse
(2.3) und folgt den Exit-Codes aus 2.2.

### 3.3 Interaktiver vs. nicht-interaktiver Modus

Destruktive Operationen fragen interaktiv nach Bestätigung. Für CI/CD: `--yes`
voranstellen. Bei Nicht-Terminal-stdin wird nicht interaktiv nachgefragt.

---

## 4. MCP-Server-Referenz

Der MCP-Server (`d-migrate mcp serve`) stellt die d-migrate-Operationen als
**MCP-Tools** bereit. Protokollversion `2025-11-25`, Contract `v1`.

### 4.1 Transports

| Transport | Start | Eigenschaften |
| --------- | ----- | ------------- |
| `stdio` (Default) | `--transport stdio` | NDJSON über stdin/stdout; ein Prozess pro Client; stoppt bei EOF/SIGINT |
| `http` | `--transport http` | Streamable HTTP (MCP 2025-11-25): `POST /mcp` (JSON-RPC), `GET /mcp` → HTTP 405, `DELETE /mcp` (mit `MCP-Session-Id`) beendet Session, `GET /.well-known/oauth-protected-resource` liefert Protected-Resource-Metadata |

### 4.2 Authentifizierung

**stdio:** Principal kommt aus `DMIGRATE_MCP_STDIO_TOKEN` (env, sha256-Fingerprint)
+ Token-Registry `--stdio-token-file` (JSON/YAML). OS-User/PID/`pwd` sind **nie**
Principal-Quelle. Registry-Format:

```yaml
tokens:
  - fingerprint: "<sha256_hex des rohen Tokens>"
    principalId: "alice"
    tenantId: "acme"
    scopes: ["dmigrate:read", "dmigrate:job:start"]
    isAdmin: false
    auditSubject: "alice@acme"
    expiresAt: "2027-01-01T00:00:00Z"   # RFC-3339
```

**HTTP:** Header `Authorization: Bearer <jwt>`, pro Request validiert.

| `--auth-mode` | Pflicht-Argumente | Loopback-only |
| ------------- | ----------------- | :-----------: |
| `disabled` | — | ✓ (nur `127.0.0.1`/`::1`) |
| `jwt-jwks` (Default) | `--issuer`, `--jwks-url`, `--audience` | nein |
| `jwt-introspection` | `--issuer`, `--introspection-url`, `--audience` | nein |

Validierung (vor dem ersten Request, sonst Exit 2): Nicht-Loopback-`--bind`
verlangt aktive Auth; `--public-base-url` muss `https` sein; `--allow-origin`
darf kein `*` enthalten und ist bei Nicht-Loopback Pflicht; Algorithmus-Allowlist
ohne `none`/`HS*`; `port ∈ [0,65535]`; `clockSkew ∈ [0s,5min]`.

### 4.3 Scopes

Pro Tool gilt ein `requiredScopes`-Vertrag. Bekannte Scopes: `dmigrate:read`
(lesende Tools), `dmigrate:job:start` (`*_start`-Jobs). Die vollständige,
aktuelle Zuordnung liefert `capabilities_list.scopeTable` zur Laufzeit. Fehlt
ein Scope, antwortet `tools/call` mit `AUTH_REQUIRED` (Tool-Result-Envelope)
bzw. Protokoll-Methoden mit JSON-RPC `-32600`.

### 4.4 Discovery

| Methode | Liefert |
| ------- | ------- |
| `capabilities_list` | `mcpProtocolVersion`, `dmigrateContractVersion`, `serverName`, `tools[]` (mit `requiredScopes`), `scopeTable` |
| `tools/list` | je Tool: `name`, `title`, `description`, `inputSchema`/`outputSchema` (JSON Schema 2020-12), `requiredScopes` |
| `tools/call` | führt ein Tool aus |
| `resources/list`, `resources/templates/list`, `resources/read` | Jobs/Artifacts/Schemas/Profiles/Diffs/Connections (secret-frei), Pagination per opaquem `nextCursor` |

**Resource-URI-Templates** (7, tenant-scoped):

```
dmigrate://tenants/{tenantId}/jobs/{jobId}
dmigrate://tenants/{tenantId}/artifacts/{artifactId}
dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}
dmigrate://tenants/{tenantId}/schemas/{schemaId}
dmigrate://tenants/{tenantId}/profiles/{profileId}
dmigrate://tenants/{tenantId}/diffs/{diffId}
dmigrate://tenants/{tenantId}/connections/{connectionId}
```

### 4.5 Tool-Katalog

Stand des Golden-Contracts (`tools/list`); die maßgebliche, aktuelle Zuordnung
liefert `capabilities_list.scopeTable` zur Laufzeit.

| Tool | Art | Scope | Zweck |
| ---- | --- | ----- | ----- |
| `capabilities_list` | sync | `dmigrate:read` | Server-/Tool-/Scope-Snapshot |
| `schema_validate` / `schema_compare` / `schema_generate` | sync | `dmigrate:read` | Schema prüfen / vergleichen / rendern |
| `schema_list` / `schema_staging_readonly` | sync | `dmigrate:read` | Schema-Discovery / read-only-Staging-Upload |
| `profile_list` / `diff_list` / `job_list` / `artifact_list` / `artifact_chunk_get` | sync | `dmigrate:read` | Ressourcen auflisten / Artefakt-Chunk lesen |
| `job_status_get` | sync | `dmigrate:read` | Job-Status |
| `schema_reverse_start` / `schema_compare_start` / `data_profile_start` | **async** | `dmigrate:job:start` | read-only Job starten |
| `data_import_start` / `data_transfer_start` | **async** | `dmigrate:data:write` | schreibenden Job starten (idempotent, policy-gesteuert) |
| `job_cancel` | sync | `dmigrate:job:cancel` | Job abbrechen |
| `artifact_upload_init` / `artifact_upload` | sync | `dmigrate:read`¹ | Upload-Session öffnen / Bytes senden (`contentBase64`) |
| `artifact_upload_abort` | sync | `dmigrate:artifact:upload` | Upload-Session abbrechen |
| `procedure_transform_plan` / `procedure_transform_execute` / `testdata_plan` / `testdata_execute` | sync | `dmigrate:ai:execute` | KI-gestützte Tools (Phase G) |

¹ Method-Level `dmigrate:read`, damit `schema_staging_readonly` ohne Write-Policy
startbar ist; intent-abhängige Write-Gates greifen im Handler.

**Nur als async `*_start`-Job (kein synchrones MCP-Tool):** `schema_reverse`,
`data_profile`, `data_import`, `data_transfer`. **Kein MCP-Tool für Daten-Export**
(nur CLI `data export`). **Nicht implementiert:** SSE-Push / `notifications/*`,
OAuth-Authorization-Server/DCR (ADR 0008/0009).

### 4.6 Asynchrone Jobs

Start-Tools sind symmetrisch:

```jsonc
// Input
{
  "connectionId": "dmigrate://tenants/<t>/connections/<id>",  // bzw. sourceUri/targetUri (compare/transfer)
  "idempotencyKey": "<uuid>",      // PFLICHT
  "approvalToken": "<opaque>"       // optional, für Approved-Retry
}
// Output (Erfolg)
{
  "jobId": "job_...",
  "resourceUri": "dmigrate://tenants/<t>/jobs/job_...",
  "executionMeta": { "requestId": "..." }
}
```

`job_status_get`/`job_cancel` projizieren `status ∈ {QUEUED, RUNNING, SUCCEEDED,
FAILED, CANCELLED}`, `terminal`, `resourceUri` und ein einheitliches
`executionMeta` (u. a. `cancelRequested`, `cancelAckPending`, `retryAfter`).

### 4.7 Idempotenz

`idempotencyKey` ist bei allen `*_start`-Tools Pflicht. Ein erneuter Start mit
demselben Key (und identischem Payload-Fingerprint) liefert denselben Job statt
eines neuen — sicher für Retries.

### 4.8 Freigabe-Flow (Approval)

Verlangt die Policy eine Freigabe, antwortet der Start mit **`POLICY_REQUIRED`**
+ `approvalRequestId` + `requiredScopes`. Der Client holt einen `approvalToken`
und sendet ihn im Retry. Grant-Aussteller-Modi:

- `FailClosedGrantIssuer` — **Default**: lehnt jede Ausstellung ab
  (`policy:no-issuer-configured`). Direkte `ALLOW`-Policies bleiben unberührt.
- `ConfiguredAllowlistGrantIssuer` — produktive Allowlist (`GrantIssuanceRule`).
- `DemoAutoApprovalGrantIssuer` — **unsicher, nur Loopback/stdio**; Audit-Marker
  `issuerFingerprint = "demo-auto-approval"`.

CLI-seitig stellt `mcp approval-grant issue` Grants in den `--approval-grants-file`-
Store aus (siehe [Anwenderhandbuch 3.15](anwenderhandbuch.md#315-d-migrate-als-mcp-server-für-ki-agenten-bereitstellen)).

### 4.9 Quotas und Rate-Limiting

Aktive Jobs werden pro `(tenantId, principalId, operation)` gezählt. Bei
Überschreitung antwortet der Start **vor** jeder Ressourcen-Allokation mit:

```jsonc
{
  "code": "RATE_LIMITED",
  "details": { "retryAfter": "30", "current": "3", "limit": "3",
               "reason": "ACTIVE_JOBS_QUOTA" }   // oder EXECUTOR_SATURATED
}
```

Slots werden bei Job-Abschluss, queued-Cancel oder Lease-Ablauf freigegeben.

### 4.10 Fehlerformat

- **Tool-Ebene:** `tools/call` liefert `ToolsCallResult(isError=true,
  content=[ToolErrorEnvelope(code=…)])` — z. B. `AUTH_REQUIRED`,
  `UNSUPPORTED_TOOL_OPERATION`, `POLICY_REQUIRED`, `RATE_LIMITED`.
- **Protokoll-Ebene:** JSON-RPC-Fehler (z. B. `-32600`) mit
  `error.data.dmigrateCode`.

### 4.11 Audit

Jeder `tools/call` durchläuft `AuditScope.around` und emittiert **genau ein**
`AuditEvent` (SUCCESS oder FAILURE mit `ToolErrorCode`). Audit-Oberflächen
bleiben secret-frei (`connectionId` + `sensitivity`, keine URLs/Secrets).

### 4.12 Server-Konfiguration

| Flag | Wirkung |
| ---- | ------- |
| `--transport` / `--bind` / `--port` / `--public-base-url` | Transport/Netzwerk |
| `--auth-mode` / `--issuer` / `--jwks-url` / `--introspection-url` / `--introspection-client-id` / `--introspection-client-secret` / `--audience` | Authentifizierung (4.2) |
| `--stdio-token-file` | stdio-Token-Registry |
| `--allow-origin` (mehrfach) | CORS-Origin-Allowlist |
| `--connection-config` | Server-YAML mit secret-freien Connection-Refs (sonst Root-`--config`) |
| `--cursor-keyring-file` | HMAC-Cursor-Keyring (Multi-Instanz) |
| `--approval-grants-file` | Freigabe-Store |
| `--mcp-state-dir` / `--mcp-state-orphan-retention` | dateibasierter Zustand + Aufräum-Retention |
| `--operation-timeout-seconds` | Upload-Finalisierung/Sweeper-Timeout |

Server-State und Job-Executor sind zusätzlich über die `D_MIGRATE_SERVER_*`-
Umgebungsvariablen (2.6) konfigurierbar. Vollständige Flag-Tabellen:
[Anwenderhandbuch Anhang A.13–A.16](anwenderhandbuch.md#a13-mcp-serve).

---

## 5. Sicherheit und Datenschutz

- **TLS/Auth:** Netzwerk-MCP nur mit `jwt-jwks`/`jwt-introspection` + `https`;
  `--auth-mode disabled` ist strikt Loopback-only.
- **Secrets:** secret-frei referenzieren (`credentialRef: env:VAR`); keine
  Secrets/URLs in Logs oder Audit. Keine echten Tokens/Passwörter/Kundendaten in
  Beispielen.
- **Scopes & Mandanten:** Tools sind scope-gegated; Resources sind tenant-scoped
  (`allowedTenantIds`). Connection-Refs werden ohne Secrets projiziert.
- **Geringste Rechte:** read-only-Tools (`dmigrate:read`) von schreibenden und
  Job-Start-Scopes trennen.
- Betrieb/Härtung: [Administrationshandbuch](administrationshandbuch.md#6-mcp-server-betrieb).

---

## 6. Verwandte Spezifikationen

- [`../../spec/cli-spec.md`](../../spec/cli-spec.md) — normative CLI-Spezifikation
- [`../../spec/mcp-server.md`](../../spec/mcp-server.md) — normative MCP-Spezifikation
- [`../../spec/job-contract.md`](../../spec/job-contract.md) — Job-Vertrag
- [`../../spec/connection-config-spec.md`](../../spec/connection-config-spec.md) — Verbindungen/Konfiguration
- [Anwenderhandbuch](anwenderhandbuch.md) — aufgabenorientierte Anleitungen + vollständige Optionstabellen (Anhang A)
- [Changelog](../../CHANGELOG.md)

