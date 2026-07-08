# Administrationshandbuch

> **Software-Version:** 0.9.9 (Beta) · **Stand:** 16.06.2026
>
> **Zielgruppe:** Personen, die d-migrate bereitstellen, konfigurieren und
> betreiben. Aufgabenorientierte Anwender-Workflows stehen im
> [Anwenderhandbuch](anwenderhandbuch.md); die maschinennahen Schnittstellen in
> der [API-Referenz](api-referenz.md).
>
> **Konvention:** Mit **🔮** markierte Punkte sind für spätere Milestones
> geplant; hier wird jeweils der **aktuelle** Stand beschrieben und das geplante
> Ziel mit Roadmap-Bezug genannt. Normative Verträge stehen in `spec/`; dieses
> Handbuch konsolidiert sie betrieblich und verlinkt für die vollständige
> Definition dorthin.

---

## 1. Einführung und Betriebsmodell

### 1.1 Komponentenüberblick

d-migrate ist ein einzelnes JVM-Artefakt mit zwei Betriebsformen und einem
optionalen Speicher-Backend:

| Komponente | Rolle | Lebensdauer |
| ---------- | ----- | ----------- |
| **CLI** (`d-migrate`) | Schema-/Daten-Operationen als Einzelkommando | ein Prozess pro Aufruf |
| **MCP-Server** (`d-migrate mcp serve`) | dieselben Operationen als MCP-Tools für KI-Agenten/Netzwerk-Clients | langlaufend |
| **ArtifactStore** | Ablage der vom MCP-Server erzeugten/empfangenen Artefakte (DDL, Reports, Exporte, Uploads) | Datei (Default) oder S3 (siehe [§5](#5-object-storage--artifactstore-s3)) |

### 1.2 Betriebsmodi

- **Einmalige CLI-Läufe:** Jeder Aufruf parst die Konfiguration, baut bei Bedarf
  einen Connection-Pool auf, führt eine Operation aus und beendet sich mit einem
  [Exit-Code](api-referenz.md#22-exit-codes-cli). Geeignet für Skripte und
  CI/CD. Kein persistenter Zustand zwischen Aufrufen.
- **Langlaufender MCP-Server:** hält Connection-Pool, Job-Executor und
  Server-State über die Laufzeit. Asynchrone Jobs, Quotas, Idempotenz und Audit
  sind nur in diesem Modus relevant (siehe [§6](#6-mcp-server-betrieb),
  [§7](#7-asynchrone-jobs-und-job-executor)).

### 1.3 Architektur-Kurzüberblick

d-migrate folgt einer hexagonalen Architektur (Ports & Adapters): ein
datenbankunabhängiger Domain-Core, Output-Ports und austauschbare Adapter
(JDBC-Treiber, Format-Codecs, CLI/MCP als Driving-Adapter). Details:
[`spec/architecture.md`](../../spec/architecture.md).

---

## 2. Deployment

| Weg | Bezug | Hinweis |
| --- | ----- | ------- |
| **Docker / GHCR-Image** | [`README.md`](../../README.md), [`guide.md`](guide.md) | `ghcr.io/pt9912/d-migrate:0.9.8` bzw. `:latest`; read-only-Mount des Arbeitsverzeichnisses empfohlen |
| **GitHub-Release-Assets** | [`guide.md`](guide.md) Option A | Launcher-Skript bzw. Fat JAR (JVM 21) |
| **Homebrew-Tap** | [`releasing.md`](releasing.md) | `brew tap pt9912/d-migrate …` dann `brew install d-migrate` |
| **Aus Quellcode bauen** | [`README.md`](../../README.md) „Build, Test, Lint" | `make`-Targets; Build/Test laufen im Docker-Build (Stage `build`) |
| **Image lokal aus dem Dockerfile** | [`guide.md`](guide.md) Option B | `make docker-build` |

Für reproduzierbare Builds und Releases (Asset-Erzeugung, Tag-Workflow,
Homebrew-Formula-Bump) ist [`releasing.md`](releasing.md) maßgeblich.

---

## 3. Konfiguration

### 3.1 `.d-migrate.yaml`

Die Konfigurationsdatei ist optional; alle Felder haben Defaults. Sektionen
(vollständiges Schema:
[`connection-config-spec.md`](../../spec/connection-config-spec.md) §3.2):

| Sektion | Zweck |
| ------- | ----- |
| `database` | benannte Verbindungen + Default-Source/-Target |
| `export` / `import` | Format-/Encoding-Defaults für Daten-Ein-/Ausgabe |
| `pipeline` / `incremental` | Streaming-Chunking, inkrementelle Pfade |
| `ki` | KI-Provider-Konfiguration (opt-in, lokale Verarbeitung als Default) |
| `i18n` | Sprache, Zeitzone, Unicode-Normalisierung (siehe 3.4) |
| `ddl` / `docgen` | DDL-Generierungs- und Doku-Optionen |
| `logging` | Log-Level und -Ausgabe (siehe [§8.1](#8-logging-und-telemetrie)) |
| `artifacts` | nur MCP-Server: Artefakt-Speicher-Backend (siehe [§5](#5-object-storage--artifactstore-s3)) |

### 3.2 Effektiver Konfigurationspfad

Die CLI arbeitet mit **genau einer** effektiv aufgelösten Datei (kein Merge):

```
1. --config <pfad>
2. $D_MIGRATE_CONFIG
3. ./.d-migrate.yaml (Default)
```

Dieselbe Datei gilt für `database.*` **und** `i18n.*`
([`connection-config-spec.md`](../../spec/connection-config-spec.md) §3.1).

### 3.3 Umgebungsvariablen

Tatsächlich vom Code gelesen (Stand 0.9.9):

| Variable | Wirkung |
| -------- | ------- |
| `D_MIGRATE_CONFIG` | effektiver Config-Pfad (wie `--config`) |
| `D_MIGRATE_LANG` | Ausgabesprache (wie `--lang`, mit Root-Bundle-Fallback) |
| `DMIGRATE_MCP_STDIO_TOKEN` | stdio-Principal-Token (sha256-Fingerprint) |
| `DMIGRATE_MCP_STATE_DIR` / `_ORPHAN_RETENTION` | dateibasierter MCP-Zustand + Aufräum-Retention |
| `D_MIGRATE_SERVER_STATE_JDBC_URL` / `_USERNAME` / `_PASSWORD` | JDBC-backed Server-State (MCP-Persistenz) |
| `D_MIGRATE_SERVER_STATE_HIKARI_*` / `_MIGRATIONS_AUTO` | Pool-/Migrations-Konfiguration des Server-State |
| `D_MIGRATE_SERVER_JOBS_EXECUTOR_*` | Job-Executor (siehe [§7](#7-asynchrone-jobs-und-job-executor)) |

> 🔮 Die normative [`cli-spec.md`](../../spec/cli-spec.md) §9 listet zusätzlich
> `D_MIGRATE_OUTPUT_FORMAT`, `D_MIGRATE_NO_COLOR`, `D_MIGRATE_ASSUME_YES`,
> `D_MIGRATE_DB_PASSWORD` und `D_MIGRATE_AI_API_KEY` als Zielbild. Diese werden
> vom aktuellen Code **nicht** gelesen. Passwörter kommen über die URL, eine
> `${VAR}`-Referenz in `.d-migrate.yaml` oder den MCP-`env:`-Secret-Ref (siehe
> [§4.6](#4-datenbank-verbindungen)).

### 3.4 Internationalisierung (i18n)

`i18n.default_locale` (CLI-Sprache), `i18n.default_timezone` und
`i18n.normalize_unicode` steuern die Lokalisierung. Vertragsregeln:

- Root-/Fallback-Bundle bleibt Englisch; **JSON/YAML-Verträge sind
  sprachstabil** — lokalisiert werden nur Plain-Text-Ausgaben.
- `normalize_unicode` (NFC/NFD/NFKC/NFKD) steuert Vergleichs-/Metadatenverhalten,
  **keine** stille Nutzdatenmutation.
- `default_timezone` ist ein **expliziter** Konvertierungsbaustein und löst
  keine pauschale Umdeutung vorhandener lokaler Zeitwerte aus.

---

## 4. Datenbank-Verbindungen

### 4.1 Connection-URL-Format und Aliase

`dialect://user:password@host:port/db?params`. Unterstützte Dialekte/Aliase und
dialektspezifische Parameter:
[`connection-config-spec.md`](../../spec/connection-config-spec.md) §1.1–§1.5.

### 4.2 Verbindungsaufbau

URL parsen → Dialekt-Alias normalisieren → JDBC-URL konstruieren → HikariCP-Pool
konfigurieren → Verbindung testen (`SELECT 1`) → bei Fehler aussagekräftige
Meldung mit Hinweis ([`connection-config-spec.md`](../../spec/connection-config-spec.md) §2.1, §2.3).

### 4.3 Connection-Pool-Defaults (HikariCP)

| Parameter | Default | Bedeutung |
| --------- | ------- | --------- |
| `maximumPoolSize` | `10` | maximale Verbindungen (**SQLite: `1`** — kein paralleles Schreiben) |
| `minimumIdle` | `2` | minimale Idle-Verbindungen |
| `connectionTimeout` | `10000` ms | max. Wartezeit auf eine Verbindung |
| `idleTimeout` / `maxLifetime` | `300000` / `600000` ms | Idle- bzw. Lebenszeit |
| `keepaliveTime` | `60000` ms | Keepalive-Intervall |
| `statementTimeout` | `30000` ms | Per-Statement-Cancel-Budget (`0` deaktiviert) |
| `networkTimeout` | `30000` ms | Per-Connection-I/O-Schutz (`0` deaktiviert) |

### 4.4 Timeout-Einheiten und `--lock-timeout-ms`

Connection-URL-Timeouts folgen der **nativen JDBC-Konvention je Treiber**
(PostgreSQL: Sekunden; MySQL: Millisekunden; SQLite: `busy_timeout` in ms).
Werte in `.d-migrate.yaml` verwenden konsistent **Millisekunden** (Suffix `_ms`);
d-migrate konvertiert automatisch
([`connection-config-spec.md`](../../spec/connection-config-spec.md) §1.6).
`--lock-timeout-ms` begrenzt das Warten auf DB-Locks bei
ausführenden Operationen (siehe [`guide.md`](guide.md)).

### 4.5 Sonderzeichen in Passwörtern

Sonderzeichen in der URL müssen URL-encoded werden (`@`→`%40`, `:`→`%3A`,
`/`→`%2F`, `?`→`%3F`, `#`→`%23`, `%`→`%25`). Alternative: Passwort weglassen und
über eine `${VAR}`-Referenz bereitstellen
([`connection-config-spec.md`](../../spec/connection-config-spec.md) §1.7).

### 4.6 Credential-Handling

**Heute** stehen drei Quellen zur Verfügung:

1. **Inline in der Connection-URL** (`dialect://user:password@…`).
2. **`${VAR}`-Referenz** in `.d-migrate.yaml` (`database.connections`) — das
   Passwort kommt aus der Prozess-Umgebung, nicht aus der Datei.
3. **MCP-Secret-Ref** `credentialRef: "env:<VAR_NAME>"` — der MCP-Secret-Resolver
   ist **fail-closed**: eine Connection ohne verdrahteten Secret-Provider wird
   abgelehnt, nicht ohne Secret geöffnet.

Passwörter und API-Keys werden in Logs/Audit **maskiert** (`***`); secret-freie
Connection-Refs werden ohne URL/Passwort projiziert.

> 🔮 **Geplant (1.0.0-RC, [`LN-025`](../../spec/lastenheft-d-migrate.md#ln-025)):** verschlüsselter Credential-Store
> (`~/.d-migrate/credentials.enc`, AES-256) mit `config credentials set|list`
> und die Umgebungsvariable `D_MIGRATE_DB_PASSWORD`. Die `config`-Command-Gruppe
> und `D_MIGRATE_DB_PASSWORD` sind **noch nicht** implementiert; die Beschreibung
> in [`connection-config-spec.md`](../../spec/connection-config-spec.md) §4.2 ist
> insoweit Zielbild. Bis dahin gilt: `.d-migrate/`-Verzeichnis in `.gitignore`
> aufnehmen.

---

## 5. Object Storage / ArtifactStore (S3)

Wo der **MCP-Server** Artefakt-Bytes ablegt, steuert der `artifacts`-Block der
`.d-migrate.yaml`. Default ist ein lokaler Datei-Store; seit 0.9.8 ist alternativ
ein S3-kompatibler Object-Storage wählbar (AWS S3, MinIO, SeaweedFS, Ceph/RGW).

```yaml
artifacts:
  store: s3                            # file (Default) | s3
  s3:
    bucket: "d-migrate-artifacts"      # Pflicht bei store: s3
    endpoint: "https://s3.example.com" # optional; fehlt = echtes AWS
    region: "eu-central-1"             # optional, Default us-east-1
    prefix: "prod/"                    # optional, Default ""
    pathStyle: true                    # optional, Default true (S3-kompatibel)
```

- `store` akzeptiert nur `file` oder `s3`; `store: s3` verlangt mindestens
  `bucket`. Ein `s3`-Block **ohne** `store: s3` ist ein **harter Fehler**
  (Foot-Gun-Guard), damit die Config nicht still ignoriert wird.
- **Credentials** stehen **nicht** in der Datei, sondern kommen aus der
  AWS-SDK-Default-Chain (`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`, Profile,
  IAM-Rolle).
- Für MinIO/SeaweedFS: `endpoint` + `pathStyle: true` setzen. Die opaken
  `dmigrate://…/artifacts/{id}`-Refs sind backend-unabhängig — Datei oder S3 ist
  für Clients transparent.

Normativ: [`ki-mcp.md`](../../spec/ki-mcp.md) §6.2.

---

## 6. MCP-Server-Betrieb

> Vollständiger Tool-/Resource-Katalog und Flag-Tabellen:
> [API-Referenz §4](api-referenz.md#4-mcp-server-referenz).

### 6.1 Transports: stdio vs. HTTP

- **stdio** (Default): NDJSON über stdin/stdout, ein Prozess pro Client, stoppt
  bei EOF/SIGINT. Für lokale Agenten.
- **HTTP**: Streamable HTTP (MCP `2025-11-25`), `POST /mcp`. Für
  netzwerkbasierte Integration. Normativ:
  [`mcp-server.md`](../../spec/mcp-server.md).

### 6.2 Autorisierung

- **stdio:** Principal aus `DMIGRATE_MCP_STDIO_TOKEN` (env, sha256-Fingerprint)
  + Token-Registry `--stdio-token-file`. OS-User/PID sind **nie** Principal-Quelle.
- **HTTP:** `Authorization: Bearer <jwt>`, pro Request validiert; `--auth-mode`
  `jwt-jwks` (Default) oder `jwt-introspection`. `--auth-mode disabled` ist
  **strikt Loopback-only**.

Boot-Validierung (sonst Exit 2): Nicht-Loopback-`--bind` verlangt aktive Auth;
`--public-base-url` muss `https` sein; `--allow-origin` ohne `*` und bei
Nicht-Loopback Pflicht; Algorithmus-Allowlist ohne `none`/`HS*`.

### 6.3 Konfigurations-Flags-Referenz

Transport/Netz, Auth, Token-Registry, CORS, Connection-Config, Cursor-Keyring,
Approval-Store, State-Dir und Timeouts werden über `mcp serve`-Flags gesetzt —
vollständige Tabelle: [API-Referenz §4.12](api-referenz.md#412-server-konfiguration).
Server-State und Job-Executor zusätzlich über `D_MIGRATE_SERVER_*` (siehe
[§3.3](#3-konfiguration), [§7](#7-asynchrone-jobs-und-job-executor)).

### 6.4 Approval-Flow und fail-closed Grants

Verlangt die Policy eine Freigabe, antwortet ein `*_start`-Tool mit
`POLICY_REQUIRED` + `approvalRequestId` + `requiredScopes`; der Client holt einen
`approvalToken` und sendet ihn im Retry. Grant-Aussteller:

- `FailClosedGrantIssuer` (**Default**): lehnt jede Ausstellung ab. Direkte
  `ALLOW`-Policies bleiben unberührt.
- `ConfiguredAllowlistGrantIssuer`: produktive Allowlist.
- `DemoAutoApprovalGrantIssuer`: **unsicher, nur Loopback/stdio**.

CLI-seitig stellt `mcp approval-grant issue` Grants in den
`--approval-grants-file`-Store aus.

### 6.5 Quotas und Rate-Limiting

Aktive Jobs werden pro `(tenantId, principalId, operation)` gezählt. Bei
Überschreitung antwortet der Start **vor** jeder Ressourcen-Allokation mit
`RATE_LIMITED` und `reason ∈ {ACTIVE_JOBS_QUOTA, EXECUTOR_SATURATED}` plus
`retryAfter`. Slots werden bei Abschluss, queued-Cancel oder Lease-Ablauf
freigegeben. Diagnose/Behebung: [§7](#7-asynchrone-jobs-und-job-executor) und
[`operations/job-executor.md`](../operations/job-executor.md) §6.

### 6.6 Audit

Jeder `tools/call` durchläuft `AuditScope.around` und emittiert **genau ein**
`AuditEvent` (SUCCESS oder FAILURE mit Fehlercode). Audit-Oberflächen bleiben
**secret-frei** (`connectionId` + `sensitivity`, keine URLs/Secrets).

### 6.7 Policy-gesteuerte Datenoperationen

Schreibende Tools (`data_import_start`, `data_transfer_start`) sind
policy-gesteuert und scope-gegated (`dmigrate:data:write`). Read-only-Tools
(`dmigrate:read`) bleiben davon getrennt — Prinzip der geringsten Rechte.

---

## 7. Asynchrone Jobs und Job-Executor

### 7.1 Job-Modell und Lebenszyklus

`*_start`-Tools starten Jobs mit `status ∈ {QUEUED, RUNNING, SUCCEEDED, FAILED,
CANCELLED}`; `job_status_get`/`job_cancel` projizieren Status, `terminal`,
`resourceUri` und `executionMeta`. Der Dispatcher hat zwei Modi:

| Modus | Verhalten | Wann |
| ----- | --------- | ---- |
| **`sync`** (Default) | Worker laufen synchron auf dem Request-Thread; keine Queue/Backpressure | MVP, Tests, Single-Tenant-Dev |
| **`async`** (opt-in) | bounded `ThreadPoolExecutor` + `ArrayBlockingQueue`; `tools/call` kehrt mit `jobId` zurück, sobald Admission/Quota/Commit durch sind | Production-Loads, mehrere Tenants |

`async` wird über `server.jobs.executor.mode: async` bzw.
`D_MIGRATE_SERVER_JOBS_EXECUTOR_MODE=async` aktiviert; Pool-Größe
(`coreThreads`=`maxThreads`, Default 4), `queueCapacity` (Default 1024),
`retryAfterMillis` und `shutdownTimeoutMillis` sind YAML- bzw.
`D_MIGRATE_SERVER_JOBS_EXECUTOR_*`-konfigurierbar. Ungültige Werte verhindern den
Bootstrap. Sizing, Saturation-Diagnose (`EXECUTOR_SATURATED` vs.
`ACTIVE_JOBS_QUOTA`), Shutdown-Drain und der 429-Spike-Workflow stehen in
[`operations/job-executor.md`](../operations/job-executor.md); der Job-Vertrag in
[`job-contract.md`](../../spec/job-contract.md).

### 7.2 Idempotency-Keys

`idempotencyKey` ist bei allen `*_start`-Tools Pflicht. Ein erneuter Start mit
demselben Key und identischem Payload-Fingerprint liefert denselben Job statt
eines neuen — sicher für Retries.

### 7.3 Administrative Abort-Pipeline

Hängende Upload-Sessions/Jobs werden über die administrative Abort-Pipeline
beendet (fingerprint-gegatet, audit-pflichtig); Details in
[`mcp-server.md`](../../spec/mcp-server.md).

---

## 8. Logging und Telemetrie

### 8.1 Logging-Konfiguration und -Level

Framework: SLF4J + Logback. Level/Ausgabe über die `logging`-Sektion der
`.d-migrate.yaml`. **Technische Logs sind Englisch**, User-Meldungen lokalisiert;
Ergebnisse gehen nach stdout, Fortschritt/Warnungen nach stderr. Passwörter/
API-Keys/URLs werden maskiert ([§4.6](#4-datenbank-verbindungen)). Strukturierte
Dispatcher-Events (`job.dispatch.scheduled|started|finished`) erlauben
Queue-Latenz-Korrelation
([`operations/job-executor.md`](../operations/job-executor.md) §4.4).

### 8.2 Telemetrie-/Observability-Port

> 🔮 **Geplant:** ein neutraler Telemetry-/Observability-Port (Eventmodell +
> JSONL-Referenzadapter) ist als eigener Plan skizziert
> ([`telemetry-observability-port.md`](../planning/next/telemetry-observability-port.md)).
> Heute gibt es **keinen** HTTP-Health-Endpoint und keine Micrometer-/
> OpenTelemetry-Integration; der Job-Executor liefert nur einen
> Status-Snapshot über die interne API.

---

## 9. Sicherheit

> Mehrere Härtungen sind für 1.0.0-RC geplant (🔮). Hier steht der **aktuelle**
> Stand.

### 9.1 Netzwerk-Exposition des MCP-Servers

Default ist Loopback-only. `--auth-mode disabled` ist **strikt** auf
`127.0.0.1`/`::1` beschränkt; jede Nicht-Loopback-Bindung verlangt aktive Auth
(`jwt-jwks`/`jwt-introspection`) und `https`. Siehe
[§6.2](#6-mcp-server-betrieb).

### 9.2 Token-/Grant-Verwaltung

stdio-Tokens liegen in der `--stdio-token-file`-Registry (sha256-Fingerprint,
Scopes, optionales `expiresAt`); Approval-Grants im `--approval-grants-file`-
Store. Tokens/Grants sind Secrets — Dateien restriktiv berechtigen und aus der
Versionskontrolle ausschließen.

### 9.3 TLS/SSL für DB-Verbindungen

SSL-Parameter werden heute über die Connection-URL gesetzt (z. B.
`?ssl=verify-full`/`sslmode`), dialektspezifisch.
🔮 **Geplant (1.0.0-RC, [`LN-026`](../../spec/lastenheft-d-migrate.md#ln-026)):** durchgängig erzwungenes TLS/SSL für alle
DB-Verbindungen.

### 9.4 Audit-Logging

Heute: secret-freies MCP-`tools/call`-Audit (genau ein Event pro Aufruf, siehe
[§6.6](#6-mcp-server-betrieb)).
🔮 **Geplant (1.0.0-RC, [`LN-027`](../../spec/lastenheft-d-migrate.md#ln-027)):** Audit-Logging **aller** Operationen
(CLI + Server) als durchgängiges Feature.

---

## 10. Betrieb und Wartung

### 10.1 Upgrades und Versionswechsel

d-migrate ist zustandslos pro CLI-Lauf; ein Upgrade ist ein Austausch des Images
bzw. Artefakts. Für den MCP-Server mit JDBC-backed Server-State gilt ein expliziter
Flyway-Workflow:

- **Production:** Die Schema-Migration ist ein eigener Ops-Schritt **vor** dem
  Server-Start — der Operator ruft `JdbcMigrationRunner(dataSource).migrate()` aus
  einem Deployment-Script bzw. Ops-Job gegen die Server-State-DB auf. Der Server
  migriert **nicht** automatisch beim Start; bei Schema-Drift schlägt der Bootstrap
  fehl (`validate()` beim Start — kein stilles Auto-Migrate in Production).
- **Dev/Test:** opt-in über `server.state.migrations.auto = true`; der Bootstrap ruft
  `migrate()` dann selbst.
- Die Schema-History liegt in der dedizierten Tabelle `flyway_phase_e_history`, sodass
  das Server-State-Schema von etwaigen Co-Mietern derselben DB getrennt bleibt.

Beim Versionswechsel die [Änderungshistorie](../../CHANGELOG.md) auf Vertrags-/
Flag-Änderungen prüfen.

### 10.2 Rollback-Szenarien

Schema-Rollbacks über `schema rollback` mit dem erzeugten Down-SQL-Artefakt;
Release-/Deployment-Rollback siehe [`releasing.md`](releasing.md). Diff-basierte
Migrationen erzeugen optional ein Rollback-Artefakt (`--generate-rollback`).

### 10.3 Backup-/Recovery-Hinweise für Artefakte

- **Datei-Store:** das Artefakt-Verzeichnis (unter dem MCP-State-Dir) in das
  reguläre Datei-Backup aufnehmen.
- **S3-Store:** Bucket-Versionierung/Lifecycle des Object-Storage nutzen;
  Credentials kommen aus der AWS-SDK-Chain, liegen also nicht im Backup.
- **JDBC-Server-State:** die Phase-E-Tabellen sind klein (Owner-Counts skalieren mit
  aktiven Jobs, Idempotency-Einträge mit Retry-Volumen); Standard-DB-Backup reicht
  (`pg_dump --schema=public` oder kontinuierliche WAL-Archivierung). Wichtig:
  Idempotency-COMMITTED-Einträge dienen als Wire-Replay-Cache — ein Restore muss die
  Retention-Spalte einbeziehen.

---

## Verwandte Dokumentation

- [Anwenderhandbuch](anwenderhandbuch.md) · [API-Referenz](api-referenz.md) · [Migrations-Leitfaden](migrations-leitfaden.md)
- [`spec/architecture.md`](../../spec/architecture.md), [`spec/connection-config-spec.md`](../../spec/connection-config-spec.md), [`spec/mcp-server.md`](../../spec/mcp-server.md), [`spec/job-contract.md`](../../spec/job-contract.md), [`spec/ki-mcp.md`](../../spec/ki-mcp.md)
- [`operations/job-executor.md`](../operations/job-executor.md) · [Changelog](../../CHANGELOG.md)
