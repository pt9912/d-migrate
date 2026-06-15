# Administrationshandbuch

> **Status:** 🚧 Gerüst (Milestone 0.9.9). Abschnitte sind gegliedert und mit
> Quellverweisen versehen, aber noch nicht ausformuliert.
>
> **Marker:** ✅ vorhanden (übernehmen) · ♻️ aus Spec konsolidieren · 🔲 neu ·
> 🔮 geplant (späterer Milestone — aktuellen Stand dokumentieren, Roadmap nennen)
>
> **Zielgruppe:** Personen, die d-migrate bereitstellen, konfigurieren und
> betreiben. Anwender-Workflows stehen im
> [Anwenderhandbuch](anwenderhandbuch.md).

---

## 1. Einführung und Betriebsmodell

- 1.1 Komponentenüberblick (CLI, MCP-Server, ArtifactStore) — 🔲
- 1.2 Betriebsmodi: einmalige CLI-Läufe vs. langlaufender MCP-Server — 🔲
- 1.3 Architektur-Kurzüberblick — ♻️ [`../../spec/architecture.md`](../../spec/architecture.md)

## 2. Deployment

- 2.1 Docker / GHCR-Image (`:0.9.8`/`:latest`) — ✅ [`../../README.md`](../../README.md), [`guide.md`](guide.md)
- 2.2 GitHub-Release-Assets (Launcher / Fat JAR) — ✅ `guide.md` Option A
- 2.3 Homebrew-Tap — 🔲 (Verweis [`releasing.md`](releasing.md) §4.7)
- 2.4 Aus Quellcode bauen (`make`-Targets) — ✅ README „Build, Test, Lint"
- 2.5 Image lokal aus dem Dockerfile bauen — ✅ `guide.md` Option B/A.2

## 3. Konfiguration

- 3.1 `.d-migrate.yaml` — vollständige Referenz — ♻️
  [`../../spec/connection-config-spec.md`](../../spec/connection-config-spec.md) §3.2
  (alle Sektionen: database, export, import, pipeline, incremental, ki,
  i18n, ddl, docgen, logging)
- 3.2 Effektiver Konfigurationspfad / Suchreihenfolge — ♻️ connection-config-spec §3.1
- 3.3 Umgebungsvariablen — ♻️ [`../../spec/cli-spec.md`](../../spec/cli-spec.md) §9
- 3.4 Internationalisierung (i18n) und Vertragsregeln — ♻️ connection-config-spec §3.2 (i18n)

## 4. Datenbank-Verbindungen

- 4.1 Connection-URL-Format und Aliase — ♻️ connection-config-spec §1.1–§1.5
- 4.2 Verbindungsaufbau und Ablauf — ♻️ connection-config-spec §2.1
- 4.3 Connection-Pool-Defaults (HikariCP) — ♻️ connection-config-spec §2.2
- 4.4 Timeout-Einheiten und `--lock-timeout-ms` — ♻️ connection-config-spec §1.6
- 4.5 Sonderzeichen in Passwörtern — ♻️ connection-config-spec §1.7
- 4.6 Credential-Handling (heute) — 🔲 ·
  AES-256-Credential-Store 🔮 (1.0.0-RC, LN-025)

## 5. Object Storage / ArtifactStore (S3)

- 5.1 S3-kompatibler ArtifactStore — Konfiguration — ♻️
  [`../../spec/ki-mcp.md`](../../spec/ki-mcp.md) (6.2) — 0.9.8-Feature
- 5.2 Endpunkt, Region, Credentials, Pfad-Stil — 🔲
- 5.3 SeaweedFS/MinIO als Testziel — 🔲

## 6. MCP-Server-Betrieb

> Tool-/Resource-Katalog: [API-Referenz](api-referenz.md) Teil B.

- 6.1 Transports: stdio vs. HTTP — ♻️ [`../../spec/mcp-server.md`](../../spec/mcp-server.md) „Transports"
- 6.2 Authorisierung (stdio Token-Registry, HTTP) — ♻️ mcp-server „Authorisierung"
- 6.3 Konfigurations-Flags-Referenz — ♻️ mcp-server „Konfigurations-Flags-Referenz"
- 6.4 Approval-Flow und fail-closed Grants — ♻️ mcp-server Phase E
- 6.5 Quotas und Rate-Limiting — ♻️ mcp-server Phase E
- 6.6 Audit — ♻️ mcp-server Phase E/F
- 6.7 Policy-gesteuerte Datenoperationen — ♻️ mcp-server Phase F

## 7. Asynchrone Jobs und Job-Executor

- 7.1 Job-Modell und Lebenszyklus — ♻️ [`operations/job-executor.md`](../operations/job-executor.md), [`../../spec/job-contract.md`](../../spec/job-contract.md)
- 7.2 Idempotency-Keys — ♻️ mcp-server Phase E
- 7.3 Administrative Abort-Pipeline — ♻️ mcp-server Phase F

## 8. Logging und Telemetrie

- 8.1 Logging-Konfiguration und -Level — ♻️ connection-config-spec §3.2 (logging)
- 8.2 Telemetrie-/Observability-Port — 🔮 (geplant, [`../planning/next/telemetry-observability-port.md`](../planning/next/telemetry-observability-port.md))

## 9. Sicherheit

> Hinweis: Mehrere Härtungen sind für 1.0.0-RC geplant. Hier wird der
> **aktuelle** Stand dokumentiert, Geplantes klar als 🔮 markiert.

- 9.1 Netzwerk-Exposition des MCP-Servers (Loopback-Default) — ♻️ mcp-server
- 9.2 Token-/Grant-Verwaltung — ♻️ mcp-server
- 9.3 TLS/SSL für DB-Verbindungen — 🔮 (1.0.0-RC, LN-026)
- 9.4 Audit-Logging aller Operationen — 🔮 (1.0.0-RC, LN-027) / heutiger MCP-Audit ♻️

## 10. Betrieb und Wartung

- 10.1 Upgrades und Versionswechsel — 🔲
- 10.2 Rollback-Szenarien — ✅ Verweis [`releasing.md`](releasing.md) §6
- 10.3 Backup-/Recovery-Hinweise für Artefakte — 🔲
