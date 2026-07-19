# Auditor-Onboarding & Scope (Security-Audit-Readiness)

> **Zweck:** Ein **externer** Security-Auditor findet hier in einem Dokument die
> Angriffsfläche, die Vertrauensgrenzen, die schützenswerten Pfade, die Krypto-/Auth-
> Orte, den Build-/Reproduktionsweg und die Security-Gates — konsolidiert aus Spec,
> ADRs und dem internen Audit. Kontext: [ADR 0039](../adr/0039-externer-security-audit-kein-1.0.0-gate.md)
> (externer Audit ist kein 1.0.0-Gate) + Ticket
> [`audit-readiness-package.md`](../planning/open/audit-readiness-package.md).
>
> **Nicht** normativ: das Bedrohungsmodell ist in [`SECURITY.md`](../../SECURITY.md), die
> internen Befunde in [`security-audit-2026-07-17.md`](../planning/done/security-audit-2026-07-17.md).
> Dieses Dokument verweist, statt zu duplizieren.

## 1. Was ist d-migrate, was wird ausgeliefert

Datenbank-Migrations-/Transfer-Werkzeug (PostgreSQL, MySQL, SQLite) mit Schema-Reverse/
Generate/Compare/Migrate und Daten-Export/Import/Transfer/Profiling. Zwei Betriebsarten:

- **CLI** (Operator ruft `d-migrate <command>` lokal auf).
- **MCP-Server** (`d-migrate mcp serve`) — ein KI-Agent/Client spricht über das Model-
  Context-Protocol mit dem Server; **das ist die einzige netzwerkexponierte Fläche**.

Ausgelieferte Artefakte: CLI-Shadow-Jar, OCI-Image (GHCR), Homebrew-Formula. **Keine
Library-Artefakte** in 1.0.0 ([ADR 0037](../adr/0037-database-agnostic-first-staffelung.md)).

## 2. Vertrauensgrenze (Kurzfassung, autoritativ in SECURITY.md)

**Der Operator ist NICHT der Angreifer.** Als **untrusted** gelten:

1. **Quell-Datenbank-Metadaten und -Daten** (Tabellen-/Spaltennamen, Enum-Labels,
   Zellwerte) — wer die Quell-DB kontrolliert, ist im Modell.
2. **Eingabedateien** (zu importierende CSV/JSON/YAML/Parquet).
3. **MCP-Requests** (ein KI-Agent kann kompromittiert/bösartig sein).
4. **Fremde Konfiguration** (z. B. bezogene Schema-Files).

Ein **authentifizierter MCP-Mandant** ist im Mehrmandanten-Betrieb eine potenzielle
DoS-/Cross-Tenant-Quelle. Der lokale Operator, seine `.d-migrate.yaml` und seine
Datenbank-Credentials sind **vertraut**.

## 3. Architektur (Hexagonal / Ports & Adapters)

28 Gradle-Module (`settings.gradle.kts`):

- **`hexagon/`** — reine Domäne: `core` (Modell, Diff, Krypto-freie Logik), `ports*`
  (Port-Interfaces: `ports-common`/`-read`/`-write`/`-execute`), `application`
  (Orchestrierung: Job-Pipeline, Approval, Quota, Fingerprint), `profiling`.
- **`adapters/driven/`** — Outbound: `driver-{postgresql,mysql,sqlite}` (+`-profiling`),
  `driver-common`, `connection-config` (Credential-Store + -Resolver), `persistence-jdbc`
  (Server-State), `persistence-memory`, `formats`(+`-parquet`), `streaming`, `storage-file`/`-s3`,
  `audit-logging`, `integrations` (Flyway/Liquibase/Django/Knex-Exporter), `text-icu`.
- **`adapters/driving/`** — Inbound: `cli` (Clikt), `mcp` (Ktor-HTTP + stdio).

Architektur-Invarianten werden per Gate erzwungen (`make a-check`, s. §8) — u. a. dass
`java.sql`/JDBC nicht aus den Ports leckt.

## 4. Entry-Points & Vertrauensgrenzen im Detail

### 4.1 MCP-HTTP-Transport — die netzwerkexponierte Fläche

`adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/transport/http/McpHttpRoute.kt`.
Request-Pipeline für `POST /mcp` (`handleMcpPost`), **in dieser Reihenfolge**:

1. `checkOrigin` — CSRF-Gate (Origin-Allowlist; fehlender Origin = erlaubt, für
   Nicht-Browser-Clients bewusst).
2. `checkAccept` — Header-Match `application/json, text/event-stream`.
3. `checkBodySize` — Content-Length-Cap (`McpLimitsConfig`, Default 8 MiB) → 413 **vor**
   Body-Read (Pre-Auth-Heap-Schutz, Befund 4).
4. `validateBearer` — **vor** dem Body-Parse (Befund-4-Reorder): unauthentifizierte
   Requests lesen/parsen den Body nie. Token nur aus `Authorization: Bearer …`
   (Query-Param wird aktiv abgelehnt).
5. `parseBody` — `JsonNestingGuard` (Depth-Deckel 200, §Methoden-Lücken) → lsp4j
   `MessageJsonHandler` (Gson).
6. `checkScopes` — Scope-Gate gegen `params.name` (das echte Tool, nicht „tools/call").
7. `dispatchAndRespond` — bindet den per-Request-Principal, dispatcht.

**Auth-Modi** (`McpServerConfig`, `enum AuthMode`): `JWT_JWKS` (**Default**),
`JWT_INTROSPECTION`, `DISABLED`. Config-Validierung ist **fail-closed** (Exit 2):
`DISABLED` verlangt Loopback-Bind; `JWT_JWKS` verlangt issuer/audience/jwksUrl;
`jwksUrl`/`introspectionUrl` erzwingen https außer Loopback-Host (Befund 5). Validatoren:
`JwksAuthValidator`, `IntrospectionAuthValidator`, `DisabledAuthValidator`.

**Session** (`resolveContext`): an den erzeugenden Principal gebunden — ein Folge-Request
mit abweichender `principalId` wird als „unbekannte Session" (404) behandelt (Befund 13/14,
CWE-488). `currentPrincipal` ist ein `AtomicReference`, aber die Session-`principalId`-
Bindung verhindert Fremd-Principal-Einschleusung.

### 4.2 MCP-stdio-Transport

`transport/stdio/StdioJsonRpc.kt` — ein Principal pro Prozess, **lokal**. Kein Netz;
Deep-JSON-/Auth-Fragen greifen hier nicht (Operator-Vertrauen).

### 4.3 CLI

`adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt` → Top-Level-Commands
`schema` / `data` / `export` / `mcp` / `config`. Der **Datenpfad** (`data export`/
`import`/`transfer`/`profile`) ist operator-getrieben. `--parallel N` ist **CLI-only**
(nicht MCP-exponiert; s. §5).

### 4.4 Credential-Store & Connection-Auflösung

`adapters/driven/connection-config/`: `AesGcmCredentialStore` (AES-256-GCM, PBKDF2,
Header-als-AAD, Key-Wiping — im internen Audit als „gut belegt" geführt),
`ProviderBackedConnectionSecretResolver` (Principal-Autorisierung **fail-closed** gegen
`allowedPrincipalIds`/`allowedScopes`/`isAdmin`, dann Scheme→URL via
`CredentialProviderRegistry`). Verbindungs-URL-Parsing: `ConnectionUrlParser`.

## 5. Untrusted → privileged: die schützenswertesten Pfade

Diese Datenflüsse tragen die höchste Review-Priorität (hier lagen die bestätigten
P1/P2-Befunde):

| Fluss | Naht | Klasse |
| ----- | ---- | ------ |
| Quell-DB-**Metadaten** (Tabellen-/Spalten-/Enum-Namen) → **DDL** | Dialekt-Generatoren, `SqlIdentifiers.quoteStringLiteral(value, dialect)` (dialekt-bewusst, MySQL verdoppelt `\`) | SQL-Injection (CWE-89) — P1 behoben |
| Quell-DB-Tabellenname → **Export-Dateipfad** | `ExportOutput.resolveFileFor` (Containment `resolved.parent == base`) | Path-Traversal (CWE-22) — P1 behoben, live-repro'd |
| Quell-DB-**Zellwert** → CSV-Datei | `CsvChunkWriter` + `--csv-formula-guard` / `export.csv.formula_guard` | Formel-Injection (CWE-1236) — W203 + opt-in Guard |
| Partition-Bound-Literale → DDL | `MysqlPartitionBoundRenderer` (Backslash-Doubling) | SQL-Injection — P2 behoben |
| MCP-`tools/call` → **Job-Ausführung** | `JobStartOrchestrator` → Worker; Principal via `JobStartRequest.principalContext` (beim `factory.create` eingefangen), Connection-ACL in `resolve()` | Autorisierung/Principal — geprüft, sauber |
| Tool-Exporter (Liquibase/Django/Knex) → XML/Python/JS | `integrations/` `RenderHelpers.escapeXmlAttribute` etc. | Nicht-SQL-Escaping — P2 behoben |
| Deserialisierung (Import-Lese-Seite) | `formats`/`formats-parquet` | Deserialization — geprüft |

**Job-Ausführungs-Autorisierung** (MCP-Schreibpfad, im Detail geprüft): Policy/Approval →
Admission → Quota-Reserve laufen **vor** dem Commit; der Worker läuft mit dem
**Caller-Principal**, nicht mit Server-Rechten; Connection-Secrets werden fail-closed
gegen die Principal-ACL aufgelöst. Kein Cross-Tenant-Zugriff (Tenant strukturell in jedem
PK). Server-State-Store `persistence-jdbc` (Postgres): Quota-Reserve atomar per
`INSERT … ON CONFLICT DO UPDATE WHERE limit`, Idempotency per `INSERT … ON CONFLICT DO
NOTHING` + `SELECT … FOR UPDATE`, Job-Store per `FOR UPDATE`+CAS — race-frei unter
READ COMMITTED.

## 6. Krypto & Auth — wo hinschauen

- **Credential-Store:** `AesGcmCredentialStore` ([ADR 0034](../adr/0034-master-key-architektur-credential-store.md)).
- **JWT/JWKS-Kette:** `JwksAuthValidator`, `ClaimsMapper`, `ScopeChecker` — alg-Allowlist
  ohne `none`/`HS*`, fail-closed Scopes ([ADR 0009](../adr/0009-mcp-resource-server-no-auth-server.md)).
- **SSL/TLS zur DB:** typisiertes `SslSettings` ([ADR 0038](../adr/0038-ssl-default-prefer-verify-full-opt-in.md)
  — `prefer`-Default bewusst, `verify-full` opt-in; wirkungsloser `sslrootcert` → WARN).
- **Identifier-Quoting:** `SqlIdentifiers` über alle 3 Dialekte (im Audit als gut belegt).
- **Krypto-Dependencies:** s. [`dependency-inventory.md`](dependency-inventory.md) (der
  Credential-Store nutzt JDK-`javax.crypto`; `nimbus-jose-jwt` für JWT).

## 7. Nebenläufigkeit & Mehrmandantenfähigkeit

- **`persistence-jdbc`** (Postgres-only, `GREATEST`/`ON CONFLICT … RETURNING`): Quota-/
  Idempotency-/Job-Store, alle Tenant-PK-scoped, atomare CAS (s. §5). READ COMMITTED
  genügt.
- **Paralleler Datenpfad** (`--parallel N`, CLI-only): `ParallelWorkExecutor` (fail-fast
  via `AtomicReference`), FK-sichere Layer-Barrieren, per-Tabelle-State. SQLite auf 1
  geklemmt (nested-borrow-Deadlock). Bekanntes Robustheit-Residuum: explizites
  `--parallel N` nicht gegen Pool-`max_size` geklemmt
  ([`parallel-vs-pool-size-clamp.md`](../planning/open/parallel-vs-pool-size-clamp.md)).

## 8. Build, Reproduktion & Security-Gates

**Build/Test hermetisch über Docker** (nicht lokales Gradle):

```
docker build --target build --build-arg GRADLE_TASKS="build koverVerify" .   # Build + Test + Coverage
docker build --target runtime -t d-migrate:dev .                             # Runtime-CLI-Image
```

**Gates** (`make gates` = `docker-check docker-coverage-gate docs-check semgrep
ports-jdbc-free-gate a-check`):

| Gate | Kommando | Deckt ab |
| ---- | -------- | -------- |
| Build + Test + Detekt | `make ci-build` | Kompilierung, Unit/Integration-Tests, statische Analyse (Detekt) |
| Coverage | Teil von ci-build (`koverVerify`) | ≥90 % pro Modul |
| Doku/Spec/ADR-Konsistenz | `make docs-check` (d-check, offline) | Codepath-Existenz, ADR-Verlinkung |
| SAST | `make semgrep` (offline, `--network none`) | semgrep-Regeln (gepinnt+SHA256) |
| Architektur | `make a-check` (offline) | Hexagon-Regeln, `java.sql`-Leak-Freiheit der Ports |
| Ports JDBC-frei | `make ports-jdbc-free-gate` | keine JDBC-Typen in Port-Interfaces |

**Supply-Chain:** Dependabot (`.github/dependabot.yml`), `dependency-submission.yml`
(SBOM auf main), CI-Actions SHA-gepinnt, `GITHUB_TOKEN` least-privilege.

## 9. Interne Befundlage (Ausgangspunkt für den externen Audit)

Vollständig in [`security-audit-2026-07-17.md`](../planning/done/security-audit-2026-07-17.md):
27 Rohbefunde → 18 bestätigt (Multi-Agent, dreifach gegengeprüft), alle P1/P2 + P3-Backlog
behoben; die 6 anfangs ungeprüften „Nicht geprüft"-Restflächen und zwei methodische
Einschränkungen (P1-Live-Repro, Gson-Rekursionstiefe) nachgeholt. **Offen dokumentiert**
(kein Blocker): Gradle-Wrapper-JAR-Hash, Ktor-3.0.3-CVE-Spannen, Repo-Settings-Einsicht;
Folge-Tickets [`approved-retry-no-dispatch.md`](../planning/open/approved-retry-no-dispatch.md),
[`parallel-vs-pool-size-clamp.md`](../planning/open/parallel-vs-pool-size-clamp.md).

**Wichtig für den Auditor:** Das interne Audit ist gründlich, aber **intern** — es sieht
seine eigenen blinden Flecken nicht. Der externe Audit soll genau diese unabhängige
Perspektive liefern; die obige Befundlage ist der Startpunkt, nicht die Obergrenze.

## 10. Außerhalb des Scopes

Siehe [`SECURITY.md`](../../SECURITY.md) „Außerhalb des Bedrohungsmodells". Kurz: der
lokale Operator und seine vertraute Config/Credentials; bewusste Design-Entscheidungen
mit ADR-Begründung (z. B. SSL-`prefer`-Default, ADR 0038).
