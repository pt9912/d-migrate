# API-Referenz (CLI + MCP)

> **Status:** 🚧 Gerüst (Milestone 0.9.9). Abschnitte sind gegliedert und mit
> Quellverweisen versehen, aber noch nicht ausformuliert.
>
> **Marker:** ✅ vorhanden (übernehmen) · ♻️ aus Spec konsolidieren · 🔲 neu ·
> 🔮 geplant (späterer Milestone)
>
> **Scope (0.9.9):** d-migrate bietet zwei Schnittstellen — die **CLI** und den
> **MCP-Server**. REST- und gRPC-APIs sind für 1.1.8/1.2.0 geplant und nicht
> Teil dieser Referenz. Dieses Dokument ist die konsolidierte, nutzerseitige
> Referenz; die normativen Verträge stehen in den Specs.

---

# Teil A — Kommandozeilen-Schnittstelle (CLI)

## A.1 Allgemeine Konventionen

- A.1.1 Aufrufsyntax, Subcommand-Struktur — ♻️ [`../../spec/cli-spec.md`](../../spec/cli-spec.md) §1
- A.1.2 Verbindungsangabe: URL vs. Named Connection — ♻️ cli-spec §1

## A.2 Globale Optionen

- A.2.1 Optionen vor dem Subcommand — ✅ [`guide.md`](guide.md) „Globale Optionen" / cli-spec §1
- A.2.2 `--lock-timeout-ms` und weitere Timeouts — ♻️ connection-config-spec §1.6

## A.3 Exit-Codes

- ♻️ cli-spec §2 (vollständige Tabelle)

## A.4 Ausgabeformate

- A.4.1 Text/Tabellen/JSON — ♻️ cli-spec §3
- A.4.2 stdout vs. stderr (Piping/Scripting) — ♻️ cli-spec §10

## A.5 Fehler- und Warnungs-Codes

- ♻️ cli-spec §4 / §5 (Fehlerausgabe)

## A.6 Kommando-Referenz

> Pro Kommando: Zweck, Syntax, Optionen, Beispiele, Exit-Codes.

- A.6.1 `schema validate` — ♻️ cli-spec §6 / ✅ `guide.md`
- A.6.2 `schema compare` (file/file, file/db, db/db) — ♻️ cli-spec §6
- A.6.3 `schema generate` (inkl. Rollback, Split-DDL) — ♻️ cli-spec §6 / ✅ `guide.md`
- A.6.4 `schema reverse` — ♻️ cli-spec §6
- A.6.5 `data export` (Filter-DSL, inkrementell) — ♻️ cli-spec §6 / ✅ `guide.md`
- A.6.6 `data import` (UPSERT, Truncate, Trigger-Disable) — ♻️ cli-spec §6 / ✅ `guide.md`
- A.6.7 `data transfer` — ♻️ cli-spec §6
- A.6.8 `data profile` — ♻️ cli-spec §6 / [`../../spec/profiling.md`](../../spec/profiling.md)
- A.6.9 `mcp serve` — ♻️ [`../../spec/mcp-server.md`](../../spec/mcp-server.md) „Schnellstart"

## A.7 Umgebungsvariablen

- ♻️ cli-spec §9

## A.8 Interaktiver Modus / Bestätigungen

- ♻️ cli-spec §8

---

# Teil B — MCP-Server

## B.1 Überblick

- B.1.1 Was der MCP-Server bereitstellt — ♻️ mcp-server (Einleitung)
- B.1.2 Bezug zur CLI (`mcp serve`) — 🔲

## B.2 Transports

- B.2.1 `--transport stdio` — ♻️ mcp-server „Transports"
- B.2.2 `--transport http` (Loopback-Default) — ♻️ mcp-server „Transports"

## B.3 Capabilities und Discovery

- B.3.1 `capabilities_list` — ♻️ mcp-server „Capabilities & Tools"
- B.3.2 `tools/list`, `tools/call` — ♻️ mcp-server
- B.3.3 `resources/list`, `resources/templates/list`, `resources/read` — ♻️ mcp-server Phase D
- B.3.4 Resource-URI-Templates — ♻️ mcp-server Phase D

## B.4 Tool-Katalog

> Vollständige Liste der registrierten Tools mit Ein-/Ausgabevertrag.

- B.4.1 Schema-Tools — ♻️ mcp-server
  - `schema_validate`, `schema_compare`, `schema_generate`, `schema_reverse`,
    `schema_format`, `schema_list`, `schema_metadata`, `schema_staging_readonly`
- B.4.2 Daten-Tools — ♻️ mcp-server Phase F
  - `data_import`, `data_transfer`, `data_profile`, `data_type`
- B.4.3 Async-Job-Varianten (`*_start`) — ♻️ mcp-server Phase E
  - `schema_reverse_start`, `schema_compare_start`, `data_export_start`,
    `data_import_start`, `data_transfer_start`, `data_profile_start`

## B.5 Asynchrone Jobs

- B.5.1 Job-Wire-Contracts — ♻️ mcp-server Phase E
- B.5.2 Idempotency — ♻️ mcp-server Phase E
- B.5.3 Quotas, Rate-Limiting, Timeouts — ♻️ mcp-server Phase E/F

## B.6 Artefakt-Handling

- B.6.1 `artifact_upload_init` und Upload-Intent — ♻️ mcp-server Phase F
- B.6.2 Wire-Bytes (`contentBase64`) und Fingerprint-Vertrag — ♻️ mcp-server Phase F

## B.7 Authorisierung

- B.7.1 stdio Token-Registry — ♻️ mcp-server „Authorisierung"
- B.7.2 HTTP-Autorisierung — ♻️ mcp-server „Authorisierung"
- B.7.3 Approval-Flow und Grants — ♻️ mcp-server Phase E

## B.8 Konfigurations-Flags-Referenz

- ♻️ mcp-server „Konfigurations-Flags-Referenz"

---

## Verwandte Spezifikationen

- [`../../spec/cli-spec.md`](../../spec/cli-spec.md) — normative CLI-Spezifikation
- [`../../spec/mcp-server.md`](../../spec/mcp-server.md) — normative MCP-Spezifikation
- [`../../spec/job-contract.md`](../../spec/job-contract.md) — Job-Vertrag
- [`../../spec/connection-config-spec.md`](../../spec/connection-config-spec.md) — Verbindungen/Konfiguration
