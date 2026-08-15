# Administrationshandbuch

> **Software-Version:** 1.0.0 · **Stand:** 15.08.2026
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
| **Docker / GHCR-Image** | [`README.md`](../../README.md), [`guide.md`](guide.md) | `ghcr.io/pt9912/d-migrate:<version>` bzw. `:latest`; read-only-Mount des Arbeitsverzeichnisses empfohlen |
| **Docker Hub (Spiegel)** | [`README.md`](../../README.md) | `pt9912/d-migrate:<version>` — dasselbe Image wie auf GHCR (identischer Digest), für Umgebungen ohne GHCR-Login. GHCR bleibt die Referenz-Registry |
| **Natives Container-Image** | [`releasing.md`](releasing.md) | `…/d-migrate:<version>-native` — enthält das GraalVM-Binary statt einer JVM: kleineres Image, Start in Millisekunden, gleicher CLI-Vertrag |
| **Native Binaries** | [`releasing.md`](releasing.md) | eigenständige Binaries **ohne Java** am GitHub-Release: `linux-x64` (pro Release garantiert) und `windows-x64` (Best-Effort), je mit `.sha256`. **Für macOS gibt es kein natives Binary** ([ADR 0044](../adr/0044-kein-macos-native-binary.md)) — dort Homebrew, JVM-Artefakte oder Container-Image. Dynamisch gegen glibc gelinkt — unter Alpine/musl die JVM-Artefakte oder das Container-Image verwenden |
| **GitHub-Release-Assets** | [`guide.md`](guide.md) Option A | Launcher-Skript bzw. Fat JAR (JVM 21) |
| **Homebrew-Tap** | [`releasing.md`](releasing.md) | drei Schritte, nicht einer: `brew tap pt9912/d-migrate`, dann `brew trust pt9912/d-migrate` (aktuelles Homebrew laedt Fremd-Taps sonst nicht), dann `brew install d-migrate`. Zieht `openjdk@21` mit; folgt nur **stabilen** Releases, keine RCs. Unter macOS der empfohlene Weg, da dort kein natives Binary existiert ([ADR 0044](../adr/0044-kein-macos-native-binary.md)) |
| **Aus Quellcode bauen** | [`README.md`](../../README.md) „Build, Test, Lint" | `make`-Targets; Build/Test laufen im Docker-Build (Stage `build`) |
| **Image lokal aus dem Dockerfile** | [`guide.md`](guide.md) Option B | `make docker-build` |

> **Vorabversionen:** Release Candidates (`1.0.1-RC1`) erscheinen als versioniertes
> Tag und GitHub-Prerelease, bewegen aber **weder `:latest`** in einer Registry
> **noch den Homebrew-Tap** — beide bleiben auf dem letzten stabilen Release. Wer
> einen RC einsetzen will, zieht ihn ausdrücklich über seine Version.

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

> ℹ️ `D_MIGRATE_DB_PASSWORD` (globale Passwort-Fallback-Variable) wird seit 1.0.0-RC
> gelesen — Details und die vollständige Prioritätskette in
> [Abschnitt 4.6](#46-credential-handling). Die weiteren in der
> [`cli-spec.md`](../../spec/cli-spec.md) genannten `D_MIGRATE_OUTPUT_FORMAT`,
> `D_MIGRATE_NO_COLOR`, `D_MIGRATE_ASSUME_YES` und `D_MIGRATE_AI_API_KEY` sind
> Zielbild und werden vom aktuellen Code noch nicht gelesen.

### 3.4 Internationalisierung (i18n)

`i18n.default_locale` (CLI-Sprache), `i18n.default_timezone` und
`i18n.normalize_unicode` steuern die Lokalisierung. Vertragsregeln:

- Root-/Fallback-Bundle bleibt Englisch; **JSON/YAML-Verträge sind
  sprachstabil** — lokalisiert werden nur Plain-Text-Ausgaben.
- `normalize_unicode` (NFC/NFD/NFKC/NFKD) steuert Vergleichs-/Metadatenverhalten,
  **keine** stille Nutzdatenmutation.
- `default_timezone` ist ein **expliziter** Konvertierungsbaustein und löst
  keine pauschale Umdeutung vorhandener lokaler Zeitwerte aus.

### 3.5 Pipeline-Tuning (`pipeline.*`)

Die `pipeline`-Sektion steuert den Streaming-Datenpfad von `data export`/`import`/
`transfer`. Alle Schlüssel sind optional; die Präzedenz ist **CLI-Flag > Config >
eingebauter Default**. Ungültige Werte (Fließkomma, `<= 0`, unbekannter String)
werden **laut** abgelehnt (Exit 7), nicht still ignoriert oder gekürzt.

| Schlüssel | Default | Wirkung | CLI-Override |
| --------- | ------- | ------- | ------------ |
| `pipeline.chunk_size` | `10000` | Zeilen pro Chunk/Transaktion | `--chunk-size` |
| `pipeline.fetch_size` | dialektspezifisch `1000` | JDBC-Cursor-Prefetch beim Lesen der Quelle (nur Export/Transfer — der Import liest aus Dateien). SQLite: nur Hint. | `--fetch-size` |
| `pipeline.parallelism` | `1` | nebenläufige unabhängige Tabellen/Partitionen; `auto` = `min(CPU-Kerne, Pool-Größe)`. **SQLite → 1.** | `--parallel` |

Verhalten von `pipeline.parallelism`:

- `auto` wird gegen die Connection-Pool-Größe gedeckelt (`maximumPoolSize`, Default
  `10`; siehe [Abschnitt 4.3](#43-connection-pool-defaults-hikaricp)) — mehr Worker
  als Pool-Verbindungen brächten nur Wartezeit in `getConnection`.
- Kommt der Wert aus der **Config** und ist der Lauf mit `--resume`/`--atomic`
  kombiniert, fällt er mit Hinweis auf `1` zurück statt hart zu scheitern; nur ein
  **explizit** gesetztes `--parallel > 1` wird mit `--resume`/`--atomic` abgelehnt.
- `pipeline.checkpoint.*` (Wiederaufnahme) und `incremental.*` (Since-Pfade)
  gehören ebenfalls in diese Sektion; vollständiges Schema:
  [`connection-config-spec.md`](../../spec/connection-config-spec.md).

> ℹ️ Die `pool:`-Sektion (`max_size`/`min_idle`/`connection_timeout_ms`/
> `idle_timeout_ms`/`max_lifetime_ms`) wirkt seit 1.0.0-RC auch auf den
> **CLI-Datenpfad** (`data export`/`import`/`transfer`/`profile`): sie wird aus
> `.d-migrate.yaml` gelesen und in die Verbindung injiziert (Präzedenz Config >
> Default; kein CLI-Flag). Dadurch deckelt `pipeline.parallelism: auto` gegen den
> konfigurierten `max_size` statt gegen den Default. Werte müssen positive
> Ganzzahlen sein und unbekannte/vertippte Keys werden **laut** abgelehnt (Exit 7);
> ein allein gesetztes `max_size: 1` ist gültig (das Default-`min_idle` wird
> heruntergeklemmt). SQLite bleibt auf Pool-Größe 1 geklemmt. Die drei
> Cancel-Reaktions-Schranken (keepalive-/statement-/network-Timeout aus
> [Abschnitt 4.3](#43-connection-pool-defaults-hikaricp)) bleiben bewusst nicht über
> diese Sektion tunbar.

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

Ein DB-Passwort wird über eine Prioritätskette aufgelöst — die erste Quelle, die
liefert, gewinnt (vollständige Reihenfolge in
[`connection-config-spec.md`](../../spec/connection-config-spec.md), Abschnitt 4.1):

1. **Inline in der Connection-URL** (`dialect://user:password@…`).
2. **`D_MIGRATE_DB_PASSWORD`** — globale Fallback-Umgebungsvariable
   ([`LN-049`](../../spec/lastenheft-d-migrate.md#ln-049)); ergänzt ein **fehlendes**
   Passwort der gewählten Verbindung und überschreibt nichts Höherpriorisiertes.
3. **`${VAR}`-Referenz** in `.d-migrate.yaml` (`database.connections`) — das Passwort
   kommt aus der gleichnamigen Prozess-Umgebungsvariable, nicht aus der Datei.
4. **`credentialRef`-Provider** in der Map-Form einer Verbindung — löst zu einer
   **vollständigen** Connect-URL auf und ist **fail-closed** (ein gesetzter, aber
   unauflösbarer Ref führt zum Abbruch, nicht zu einer Verbindung ohne Secret):
   - `credentialRef: "env:<VAR>"` — die Variable enthält die URL,
   - `credentialRef: "file:/pfad"` — der **Datei-Inhalt** ist die URL (z. B. ein
     k8s-Secret-Mount; cross-platform, headless-tauglich).
   - `credentialRef: "keychain:<service>"` — der Eintrag im OS-Schlüsselbund ist die URL
     (macOS, Linux und Windows, optional mit Account;
     [ADR 0040](../adr/0040-keychain-credential-provider-backend-port.md)).
     Ohne verfügbaren Schlüsselbund (headless CI/Container/Server) scheitert die Auflösung
     **fail-closed** — dort ist `env:`/`file:` die richtige Schicht.

   Diese Auflösung gilt seit 1.0.0-RC sowohl auf dem CLI-`--source`/`--target`-Pfad
   als auch im MCP-Serve-Pfad (gemeinsame Provider-Registry,
   [ADR 0035](../adr/0035-credential-provider-scheme-registry.md)).
5. **Verschlüsselter lokaler Store** (interaktiver Betrieb,
   [`LN-025`](../../spec/lastenheft-d-migrate.md#ln-025)): `d-migrate config
   credentials set --name <n> --user <u>` legt Benutzer/Passwort AES-256-GCM-
   verschlüsselt in `~/.d-migrate/credentials.enc` ab; der Schlüssel wird aus einem
   Master-Secret (`D_MIGRATE_MASTER_PASSWORD` oder TTY-Prompt) abgeleitet und liegt
   **nicht als Datei** auf Platte. `d-migrate config credentials list` zeigt nur die
   Namen (nie Werte). Beim Verbinden ergänzt der Store ein fehlendes Passwort.

Passwörter und API-Keys werden in Logs/Audit **maskiert** (`***`); secret-freie
Connection-Refs werden ohne URL/Passwort projiziert.

> **Schicht-Wahl:** interaktiv (Arbeitsplatz) → lokaler Store; headless (CI/Container)
> → Delegation (Env/`${VAR}`/`credentialRef`), damit kein Secret im Klartext-
> Ruhezustand liegt. `D_MIGRATE_MASTER_PASSWORD` in CI ist **kein** Gewinn gegenüber
> den DB-Zugangsdaten direkt per Env. Nehmen Sie `~/.d-migrate/` in `.gitignore` auf.

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
**secret-frei** (`connectionId` + `sensitivity`, keine URLs/Secrets). Auch die
**CLI**-DB-Operationen emittieren Audit-Events (opt-in via `logging.audit`,
JSONL-Datei) — siehe [§8.1](#8-logging-und-telemetrie).

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

**Audit-Log der DB-Operationen** (`logging.audit`, [`LN-027`](../../spec/lastenheft-d-migrate.md#ln-027), opt-in): mit
`logging.audit.enabled: true` schreibt die CLI für jede DB-berührende Operation
(`schema reverse`/`migrate`/`compare` mit DB-Operand/`rollback --execute`, `data
export`/`import`/`transfer`/`profile`) genau ein `AuditEvent` als JSONL-Zeile an
`logging.audit.file` (Default `.d-migrate/audit.log`). Jedes Event trägt
`requestId`, `toolName`, `outcome` (SUCCESS/FAILURE aus dem Exit-Code), `exitCode`,
gescrubbte `resourceRefs` und `durationMs`; Verbindungs-Secrets werden maskiert.
Das Schreiben ist **best-effort** — ein Audit-Fehler bricht die Operation nie ab.
Default ist **aus**: ohne die Config entsteht keine Datei.

### 8.2 Telemetrie-/Observability-Port

> 🔮 **Geplant:** ein neutraler Telemetry-/Observability-Port (Eventmodell +
> JSONL-Referenzadapter) ist als eigener Plan skizziert; den Stand führt die
> [Roadmap](../planning/in-progress/roadmap.md).
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

SSL wird über die Connection-URL gesetzt, dialektspezifisch: **PostgreSQL**
`?sslmode=disable|allow|prefer|require|verify-ca|verify-full` (+ `sslrootcert=`
CA-Pfad); **MySQL** `?sslMode=DISABLED|PREFERRED|REQUIRED|VERIFY_CA|VERIFY_IDENTITY`
(Legacy `?ssl=true` wird opportunistisch als `PREFERRED` interpretiert).
Seit **[`LN-026`](../../spec/lastenheft-d-migrate.md#ln-026)** (2026-07-11) werden
diese Modi **typisiert geparst und validiert** (ein ungültiger Modus ist ein
Fehler, kein stiller Passthrough) und intern über ein neutrales `SslMode`-Modell
per Dialekt korrekt gemappt.

> **Hinweis:** MySQL `VERIFY_CA`/`VERIFY_IDENTITY` benötigen eine CA im
> Truststore. Truststore-/Keystore-Konfiguration ist noch nicht Teil dieser Stufe
> (System-Truststore-Fallback); ohne CA schlägt der Connect fehl.

🔮 **Geplant (nächste Tiefenstufen):** **Erzwingung** (require-SSL, fail-closed) und
**Truststore/Keystore**-Konfiguration.

### 9.4 Audit-Logging

Heute: secret-freies MCP-`tools/call`-Audit (genau ein Event pro Aufruf, siehe
[§6.6](#6-mcp-server-betrieb)).
🔮 **Geplant (1.0.0-RC, [`LN-027`](../../spec/lastenheft-d-migrate.md#ln-027)):** Audit-Logging **aller** Operationen
(CLI + Server) als durchgängiges Feature.

### 9.5 CSV-Export und Formel-Injection (CWE-1236)

Daten aus einer Quell-DB sind im Bedrohungsmodell **untrusted** (siehe
[`SECURITY.md`](../../SECURITY.md)). Ein Textwert, der mit `=`, `+`, `-`, `@`, Tab
oder Wagenrücklauf beginnt, wird von Excel/LibreOffice beim Öffnen einer CSV-Datei
als **Formel** ausgeführt (RFC-4180-Quoting verhindert das nicht). Der Export
schreibt Werte standardmäßig **treu** (wie `pg_dump`) und meldet betroffene
Spalten einmalig per Warnung `W203`.

Für Exporte, die in einer Tabellenkalkulation geöffnet werden, aktiviert der
**opt-in** Guard das `'`-Präfix (die Zelle wird nicht mehr als Formel gewertet):

```yaml
export:
  csv:
    formula_guard: true   # nur Text-Zellen; verändert den Wert (kein byte-treuer Roundtrip)
```

Präzedenz: `--csv-formula-guard` / `--no-csv-formula-guard` (CLI) >
`export.csv.formula_guard` (Config) > Default `false`. Nur **Text**-Zellen tragen
den Vektor — typisierte Zahlen/Booleans werden nie präfixt. Der Guard verändert den
exportierten Wert; für einen byte-treuen Roundtrip (z. B. Re-Import) bleibt er aus.

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

- [Anwenderhandbuch](anwenderhandbuch.md) · [Best-Practices-Leitfaden](best-practices-leitfaden.md) · [Troubleshooting-Leitfaden](troubleshooting-leitfaden.md) · [API-Referenz](api-referenz.md) · [Migrations-Leitfaden](migrations-leitfaden.md)
- [`spec/architecture.md`](../../spec/architecture.md), [`spec/connection-config-spec.md`](../../spec/connection-config-spec.md), [`spec/mcp-server.md`](../../spec/mcp-server.md), [`spec/job-contract.md`](../../spec/job-contract.md), [`spec/ki-mcp.md`](../../spec/ki-mcp.md)
- [`operations/job-executor.md`](../operations/job-executor.md) · [Changelog](../../CHANGELOG.md)
