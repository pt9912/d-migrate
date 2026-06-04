# Plan: BI-Demo-Umgebung mit Docker Compose

> Dokumenttyp: Demo- und Integrationsplan
>
> Status: In Progress (2026-06-04 — BD.2 done: 5-Tabellen-Schema +
> deterministischer Seed (50/30/500/1500/10000 Zeilen), Mount
> `./sql:/docker-entrypoint-initdb.d:ro`. Idempotenz pinnbar via
> `pg_dump | grep -v restrict | sha256sum` (Run1 == Run2 empirisch
> verifiziert; pg_dump-v17 setzt pro Invocation ein neues
> `\restrict`-Token, das gefiltert werden muss — siehe BD.2-
> Akzeptanz). Davor: BD.1 v4 — S3-Client von
> `minio/mc` auf `amazon/aws-cli:2.34.61` umgestellt. Letzte
> MinIO-Branding-Referenz aus dem Stack entfernt; Compose nutzt
> drei `aws-cli`-Container (seaweed-config, seaweed-init,
> aws-tools — Letzterer ersetzt `mc-tools`). Credentials nun
> via `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`-Env-Vars
> (statt URL/Positions-Argument). `seaweed-init` nutzt
> `head-bucket || create-bucket`-Pattern. Empirisch verifiziert
> inkl. Stresstest mit `foo"bar\\baz/qux@quux#hash` als Secret.
> Davor: BD.1 v3 (Commit `8626b6a5`) — bash-Parameter-
> Expansion in `seaweed-config` fuer JSON-Safety. Damit fiel die
> `.env`-Restriktion (`"`/`\\` im Key). Davor: BD.1 v2 (Commit
> `f6a185d8`) — `seaweed-config`-One-Shot rendert
> `s3.json` aus `.env` in ein Named Volume (eliminiert die
> `.env`/`s3.json`-Drift, die der BD.1-Review als
> Maintenance-Falle markiert hatte); `aws-tools`-Service erhaelt
> Entrypoint-Wrapper mit `mc alias set` und separaten
> Argumenten (eliminiert das `MC_HOST_local`-URL-Embedding und
> damit den URL-reserved-Char-Caveat). `config/seaweed-s3.json`
> entfernt. Davor: BD.1 v1 (Commit `cc1a5179`) — Compose-
> Skeleton + Object-Storage-Wechsel
> auf SeaweedFS (Risk #9 RESOLVED: MinIO CE archiviert,
> Docker-Hub-Tag-Pflege beendet; Auswahl-Tabelle in §5.3).
> Davor: Review-Runde 5 (2026-06-04, Commit `480e2489`) —
> Server-Skeleton, Bind-Adressen `127.0.0.1`, psql-`\set`-
> Korrektur in §7/BD.2, init-Service-Entrypoint auf Literal-
> Block, kombinierte `.d-migrate.yaml`, BD.4 fuenf Warning-Codes,
> Make-Targets, `out/`-`mkdir`-Pflicht in §6/BD.1. Davor
> Review-Runde 4 (2026-06-03) — Sub-Slice-Schnitt BD.1-BD.5,
> Image-Pinning, Healthcheck-Verträge, Skeletons; Entwurf-Stand
> 2026-05-01).
>
> **Slice-Fortschritt**:
>
> - BD.1 — Compose-Skeleton + Healthchecks: **done** (Commits
>   `cc1a5179`, `f6a185d8`, `8626b6a5`, `317adff6` + Polish-Folge)
> - BD.2 — Schema + Seed-Daten: **done** (5-Tabellen-Schema,
>   deterministischer Seed, Idempotenz empirisch verifiziert)
> - BD.3 — Metabase-Integration: **done** (Tag `v0.55.24.1`,
>   `/api/health` = `{"status":"ok"}`, `metabase-data`-Volume
>   ueberlebt `down`, README mit drei Beispiel-Fragen)
> - BD.4 — d-migrate-Smoke: **done** (`dmigrate`-Service im
>   `tools`-Profil + `.d-migrate.yaml` mit zwei Connections;
>   reverse/profile/generate end-to-end gegen Demo-Postgres,
>   alle 5 Tabellen + 5 Warning-Codes + Outlier verifiziert;
>   `aws s3 cp` lud 5 Artefakte in `s3://dmigrate-demo/runs/manual/`;
>   zwei `ProfileReportWriter`-Serializer-Bugs im
>   `targetCompatibility`-Block (doppelte JSON-Quotes; un-quotierte
>   Whitespace-Strings im YAML) im BD.5-Review-Sweep gefixt + mit
>   Parser-basierten Tests gegen Jackson/YAMLMapper abgesichert)
> - BD.5 — README + Smoke-Script + Make-Targets + CI: **done**
>   (`examples/bi-demo/scripts/smoke.sh` end-to-end gruen
>   verifiziert; Repo-Root `Makefile` hat `bi-demo-{pull,up,down,
>   purge,smoke}`; README auf Make-Targets umgestellt mit
>   docker-compose-Aequivalenztabelle im Troubleshooting;
>   optionaler `.github/workflows/bi-demo-smoke.yml` mit
>   `continue-on-error: true`)
>
> Basisdemo komplett**. Für vollständige Demo-Story
>   (`s3://`-Artifakt-Output, Parquet-Schritt) hängen einzelne
>   Erweiterungen aus §8 an
>   [`object-storage-artifact-store.md`](../next/object-storage-artifact-store.md)
>   bzw.
>   [`parquet-export-import-evaluation.md`](../next/parquet-export-import-evaluation.md);
>   der MVP-Schnitt (BD.1-BD.5) kommt ohne diese aus.
>
> Referenzen:
>
> - [`roadmap.md`](roadmap.md)
> - [`orchestrator-examples.md`](../next/orchestrator-examples.md)
> - [`profiling-data-quality-export.md`](../next/profiling-data-quality-export.md)
> - [`parquet-export-import-evaluation.md`](../next/parquet-export-import-evaluation.md)
> - [`object-storage-artifact-store.md`](../next/object-storage-artifact-store.md)
> - [The Evolution of Business Intelligence: From Monolithic to Composable Architecture](https://www.pracdata.io/p/the-evolution-of-business-intelligence-stack)
> - [Open Source Data Engineering Landscape 2025](https://www.pracdata.io/p/open-source-data-engineering-landscape-2025)

---

## 1. Ziel

Unter `examples/bi-demo/` soll eine kleine, reproduzierbare BI-Test- und
Demo-Umgebung entstehen. Sie soll zeigen, wie `d-migrate` in einen modernen,
komponierbaren Analytics-Workflow passt, ohne eine vollstaendige Enterprise-BI-
Plattform nachzubauen.

Der erste Schnitt soll bewusst einfach bleiben:

- PostgreSQL als relationale Demo-Datenbank
- Metabase als schnell nutzbares BI-Frontend
- SeaweedFS als S3-kompatibler Object-Storage fuer Demo-Artefakte
- optional ein `d-migrate`-CLI-Container oder Host-CLI-Kommandos
- Beispiel-Schema und Seed-Daten
- Smoke-Script fuer Start, Healthcheck und minimale Demo-Kommandos

---

## 2. Motivation

Die BI-Entwicklung bewegt sich in Richtung komponierbarer Stacks: leichtes
Frontend, klare Datenquellen, reproduzierbare Artefakte und spaeter embedded
Analytics mit DuckDB/Arrow/Parquet. Eine lokale Demo-Umgebung macht sichtbar,
welche Rolle `d-migrate` in diesem Stack spielt:

- Schema aus einer echten Datenbank reversen
- Profiling-Reports erzeugen
- Datenmigrationen oder Transfers demonstrieren
- Reports, Schema-Snapshots und spaetere Exporte in Object Storage ablegen
- BI-Tools auf derselben Datenbasis verwenden
- spaetere Parquet-/DuckDB-/Lakehouse-Schritte vorbereiten

---

## 3. Scope

### 3.1 In Scope

- `examples/bi-demo/docker-compose.yml`
- PostgreSQL-Service mit initialem Demo-Schema
- Metabase-Service mit persistenter lokaler Demo-Konfiguration
- SeaweedFS-Service (S3-kompatibel) mit initialem Demo-Bucket fuer
  `d-migrate`-Artefakte
- `.d-migrate.yaml` fuer benannte Verbindungen
- README fuer Demo-Start und typische Kommandos
- Smoke-Script fuer grundlegende Verfuegbarkeit

### 3.2 Nicht in Scope

- produktionsfaehige BI-Deployment-Architektur
- Auth-/SSO-/RBAC-Konfiguration fuer BI-Tools
- Superset und Metabase gleichzeitig im ersten Schritt
- ClickHouse, DuckDB, Parquet oder Iceberg als Pflichtbestandteil
- produktionsfaehige S3-Lifecycle-, IAM- oder Verschluesselungsregeln
- automatisches Provisioning komplexer Dashboards in der ersten Iteration

---

## 4. Vorgeschlagene Struktur

```text
examples/bi-demo/
  README.md
  docker-compose.yml
  .d-migrate.yaml
  .env.example
  sql/
    001_schema.sql
    002_seed.sql
  scripts/
    smoke.sh
```

> Hinweis: die SeaweedFS-`s3.json` wird zur Laufzeit von einem
> `seaweed-config`-One-Shot-Service aus `.env` in ein Named Volume
> gerendert (siehe §5.3). Es gibt **keine** versionierte
> `config/seaweed-s3.json` mehr — der vorherige Plan-Stand mit
> hartkodierter Datei wurde im BD.1-Sicherheitsreview als
> Maintenance-Falle markiert (Drift zwischen `.env` und JSON) und
> ist in dieser Iteration eliminiert.

Optional spaeter:

```text
examples/bi-demo/
  metabase/
    provisioning/
  duckdb/
    profile.sql
  parquet/
```

---

## 5. Erster Compose-Schnitt

### 5.1 PostgreSQL

PostgreSQL dient als relationale Quelle fuer `d-migrate` und als Datenquelle
fuer Metabase.

- **Image**: `postgres:17.10-trixie` (Debian-13-Trixie-Basis,
  **nicht** `-alpine`, **nicht** `-bookworm`). Tag bewusst mit
  Patch-Komponente — `postgres:17-trixie` driftet mit jedem
  17.x-Update und ist deshalb fuer den Seed-Determinismus aus
  §7/§10 Risk #4 ungeeignet. Optional zusaetzlich Image-Digest
  (`@sha256:…`) pinnen, sobald Docker-Hub-Digest stabil
  reproduzierbar ist.

  Begruendung — Alpine vs. Debian: Alpine setzt auf `musl`
  libc, das unter Multi-Thread-Last (parallele Backends,
  hoehere Konkurrenz beim Profiling-Scan) deutlich schlechter
  skaliert als die `glibc` aus der Debian-Variante. Fuer eine
  Demo, die Profiling-Workloads zeigt, ist konsistente
  Multi-Thread-Performance wichtiger als der Disk-Footprint.

  Begruendung — Trixie vs. Bookworm: Trixie (Debian 13) ist
  seit 2025-08-09 stable und der aktuelle Debian-Stable-
  Track; Bookworm wird parallel als oldstable weitergefuehrt.
  Pinning auf `-trixie` haelt den Stack auf der aktuellen
  glibc-Linie und vermeidet die Lebenszyklus-Vermischung mit
  dem Default-Tag (`postgres:17` zeigt aktuell noch auf
  Bookworm; das wechselt im Lauf des Trixie-Adopts, ohne dass
  unser Pin sich aendert).

  Stand 2026-06-03 sind PostgreSQL 17.x und 18.x beide
  aktuell supportet — siehe
  [PostgreSQL Release-Hinweise 18.4 / 17.10 / 16.14 / 15.18 / 14.23](https://www.postgresql.org/about/news/postgresql-184-1710-1614-1518-and-1423-released-3297/)
  und [Docker Hub `postgres`](https://hub.docker.com/_/postgres);
  17 ist die juengere stabile Reihe mit groesserer
  Treiber-/Tooling-Vertraeglichkeit als die ganz frische
  18er-Linie und bewusst auf 17 statt 18 gepinnt, bis 18 in
  BD-Tag-Refresh durchgepruft ist. Tag in `docker-compose.yml`
  explizit pinnen, **kein** `:latest`.
- **Datenbank**: `dmigrate_demo`
- **User**: `dmigrate`
- **Passwort**: nur Demo-Secret aus `.env.example`, nicht produktiv
- **Port**: lokal `55432`, um Konflikte mit vorhandenen
  PostgreSQL-Instanzen zu reduzieren. Bind explizit auf
  `127.0.0.1:${POSTGRES_PORT}:5432` (kein Default-`0.0.0.0`),
  damit das Demo-Passwort aus §5.5 nicht versehentlich nach LAN
  exponiert ist.
- **Init-Scripts**: `sql/001_schema.sql` + `sql/002_seed.sql` werden via
  `/docker-entrypoint-initdb.d/`-Mount idempotent beim Erststart
  geladen
- **Healthcheck**:

  ```yaml
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U dmigrate -d dmigrate_demo"]
    interval: 5s
    timeout: 3s
    retries: 12
    start_period: 10s
  ```

### 5.2 Metabase

Metabase ist fuer die erste Demo geeigneter als Superset, weil es schnell
startet und wenig Initialkonfiguration braucht.

- **Image**: `metabase/metabase:v0.55.<patch>` — bewusst auf
  die 0.55-Linie gepinnt statt auf die ganz aktuelle 0.61er,
  weil 0.55 als juengste durchgepruefte UI-stabile Reihe fuer
  Schritt-fuer-Schritt-Screenshots im README dient. Der konkrete
  Patch-Tag ist beim Implementierungs-Slice BD.3 zu setzen
  (juengster verfuegbarer 0.55er-Patch zum Zeitpunkt der
  Screenshot-Aufnahme), damit die README-Screenshots gegen
  einen konsistenten Tag pinnen. Quellen:
  [Metabase Releases](https://github.com/metabase/metabase/releases),
  [Docker Hub `metabase/metabase`](https://hub.docker.com/r/metabase/metabase).
  Bei BD-Tag-Refresh ein eigener Slice: Screenshots fuer
  aktuellen 0.55er-Patch (oder spaeter 0.61er) neu einsammeln,
  dann Tag-Update + README-Folge. Explizit pinnen, **kein**
  `:latest`.
- **Port**: lokal `3000`, Bind explizit auf
  `127.0.0.1:${METABASE_PORT}:3000` (analog §5.1, damit die
  Browser-Setup-Phase nicht offen im LAN haengt)
- **State-Persistenz**: Named-Volume `metabase-data`, gemountet
  auf `/metabase-data` im Container; `MB_DB_FILE=/metabase-data/metabase.db`
  zeigt Metabase auf die H2-Datei im Volume. Ohne expliziten
  `MB_DB_FILE` schreibt Metabase die H2-DB unter
  `/metabase.db/...` im Container-FS — der State waere nach
  `docker compose down` weg. Konkretes Pattern:

  ```yaml
  metabase:
    # ... image/healthcheck/depends_on ...
    environment:
      MB_DB_FILE: /metabase-data/metabase.db
    volumes:
      - metabase-data:/metabase-data

  volumes:
    metabase-data:
  ```

  Demo-Default; eine produktive Metabase-Deployment-Backplane
  via Postgres (`MB_DB_TYPE=postgres`) ist explizit out-of-scope.
- **Erstkonfiguration**: manuell im Browser; README dokumentiert die
  Schritte (Admin-User anlegen, Datenquelle `demo_pg` einrichten).
- **Healthcheck**:

  ```yaml
  healthcheck:
    test: ["CMD-SHELL", "curl -fsS http://localhost:3000/api/health || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 18
    start_period: 60s
  ```

  Lange `start_period`, weil Metabase beim Erststart die H2-DB
  initialisiert.

Automatisches Dashboard-Provisioning kann spaeter folgen, wenn der
Demo-Datenbestand stabil ist.

### 5.3 S3-kompatibler Object Storage (SeaweedFS)

SeaweedFS dient als lokaler S3-kompatibler Speicher fuer Demo-Artefakte.
Der Service macht die Zero-Disk-Richtung greifbar, ohne direkt eine echte
Cloud-Abhaengigkeit einzufuehren.

**Provider-Wahl SeaweedFS statt MinIO** — verglichene Alternativen im
Implementierungs-Sweep 2026-06-04 (Live-Befund Docker Hub + GitHub):

| Kandidat                                 | Tag-Pin                       | Lizenz     | Demo-Fit                                | Befund                              |
| ---------------------------------------- | ----------------------------- | ---------- | --------------------------------------- | ----------------------------------- |
| **SeaweedFS** `chrislusf/seaweedfs:4.31` | Semver, 2026-06-02            | Apache-2.0 | Single-Container `weed server -s3`      | **gewaehlt**                        |
| Garage `dxflrs/garage`                   | nur SHA-Tags auf Docker Hub   | AGPL-3.0   | OK, aber SHA-Pin-Reibung mit Plan-Stil  | abgelehnt: Pin-Konvention           |
| RustFS `rustfs/rustfs:1.0.0-beta.6`      | Semver Beta                   | Apache-2.0 | OK, aber pre-1.0 API-Breaking-Risiko    | abgelehnt: Beta-Status              |
| Ceph RGW `quay.io/ceph/ceph:v19.2.4`     | Semver                        | LGPL       | Multi-Komponenten-Stack, ~2 GB RAM      | abgelehnt: overhead fuer Demo       |

Hintergrund der MinIO-Abloese: MinIO Community Edition wurde 2025-Q3
archiviert; Docker-Hub-Tag-Pflege endete mit
`RELEASE.2025-09-07T16-13-09Z` (bekannt-CVE-anfaellig), der
Upstream-Security-Release `2025-10-15T17-29-55Z` ist nur als Quelltext
verfuegbar. Self-Build aus archiviertem Repo ist keine nachhaltige
Demo-Loesung — siehe Risk #9 (RESOLVED).

- **Image (Server)**: `chrislusf/seaweedfs:4.31` — aktueller Semver-Tag
  auf Docker Hub (2026-06-02). Tag in `docker-compose.yml` explizit
  pinnen, **kein** `:latest`. Tag-Refresh ist eigener BD-Slice analog
  Risk #7.
- **Image (S3-Client)**: `amazon/aws-cli:2.34.61` — offizielles
  AWS-CLI-Image (Amazon-Linux-2023-basiert) als neutraler
  S3-Standard-Pfad. Damit fallen drei MinIO-Client-Container weg
  (BD.1 v3 nutzte noch `minio/mc`); der CLI-Pfad ist universell
  AWS-kompatibel und immun gegen das MinIO-CE-Source-Only-Risiko.
  Verglichene Alternativen im Implementierungs-Sweep 2026-06-04:

  | Kandidat                          | Größe   | Stand              | Befund              |
  | --------------------------------- | ------- | ------------------ | ------------------- |
  | **`amazon/aws-cli:2.34.61`**      | ~250 MB | aktiv (2026-06-03) | **gewaehlt**        |
  | `peakcom/s5cmd:v2.3.0`            | ~20 MB  | Dec 2024, eingefroren | abgelehnt: Pflege-Stagnation analog `minio/mc` |
  | `rclone/rclone:1.x`               | ~50 MB  | aktiv              | abgelehnt: Multi-Backend-Tool, S3-Fokus nicht idiomatisch |
  | `minio/mc:RELEASE.2025-08-13…`    | ~50 MB  | eingefroren (CE-Source-Only) | **historisch** (BD.1 v1-v3) |

  AWS-CLI wird via `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
  konfiguriert (in BD.1 aus `S3_ACCESS_KEY` / `S3_SECRET_KEY` der
  `.env` gemappt), `AWS_DEFAULT_REGION=us-east-1` als
  SeaweedFS-Konventions-Region, `AWS_EC2_METADATA_DISABLED=true`
  um den EC2-Instanz-Metadata-Probe-Pfad zu sparen
  (vermeidet ~6s Hang in Nicht-AWS-Umgebungen). Endpoint via
  `--endpoint-url http://seaweed:8333` pro Aufruf; im Tools-
  Service kapselt ein Bash-Wrapper das Flag.
- **API-Port**: lokal `59000`, Bind `127.0.0.1:${S3_API_PORT}:8333`.
  SeaweedFS-S3-API laeuft default auf Container-Port 8333.
- **Master-UI-Port**: lokal `59001`, Bind
  `127.0.0.1:${SEAWEED_MASTER_PORT}:9333`. Master-UI zeigt
  Cluster-Status + Volume-Allocations; Demo-nuetzlich fuer Debugging,
  ist kein Auth-relevanter Endpunkt.
- **Bucket**: `dmigrate-demo`
- **Access Key / Secret Key**: Demo-Credentials **nur** aus
  `.env.example`. Hintergrund (empirisch festgestellt im
  BD.1-Smoke 2026-06-04): SeaweedFS startet ohne `-s3.config=...`
  zwar, lehnt aber Objekt-Operationen wie `PutObject` ab —
  „Signed request requires setting up SeaweedFS S3
  authentication". BD.1 rendert deshalb eine Demo-Identity-Config
  zur Laufzeit aus `.env` (siehe `seaweed-config`-Block weiter
  unten); es gibt **keine** versionierte `seaweed-s3.json` mehr,
  d.h. **kein** Sync-Vertrag zwischen `.env` und einer
  hartkodierten JSON. Eine echte IAM-Loesung mit
  Multi-Identity-Konfiguration (statt der gerenderten
  Single-Identity-Demo) ist Folge-Slice (BD.6+).
- **Prefix fuer Laeufe**: `runs/<timestamp-or-operation-id>/`
- **Config-Render-Service**: ein `seaweed-config`-One-Shot
  rendert die SeaweedFS-`s3.json` aus `.env` in ein Named Volume,
  noch bevor der `seaweed`-Server startet. Damit ist die Drift
  zwischen `.env` und einer hartkodierten JSON eliminiert (war
  Befund des BD.1-Sicherheitsreviews — Pflicht-Konvention war
  Maintenance-Falle). Konkretes Pattern via Heredoc im
  Compose-File, basierend auf dem ohnehin gepullten
  `amazon/aws-cli`-Image (kein zusaetzliches Image noetig — das
  AWS-CLI-Image ist Amazon-Linux-2023, enthaelt `bash`):

  ```yaml
  seaweed-config:
    image: amazon/aws-cli:2.34.61
    env_file:
      - .env
    volumes:
      - seaweed-config:/etc/seaweed
    entrypoint:
      - /bin/bash
      - -c
      - |
        set -eu
        esc_key="$${S3_ACCESS_KEY//\\/\\\\}"
        esc_key="$${esc_key//\"/\\\"}"
        esc_sec="$${S3_SECRET_KEY//\\/\\\\}"
        esc_sec="$${esc_sec//\"/\\\"}"
        cat > /etc/seaweed/s3.json <<EOF
        {
          "identities": [
            {
              "name": "demo",
              "credentials": [
                {
                  "accessKey": "$$esc_key",
                  "secretKey": "$$esc_sec"
                }
              ],
              "actions": ["Admin", "Read", "Write", "List", "Tagging"]
            }
          ]
        }
        EOF
        chmod 444 /etc/seaweed/s3.json
    restart: "no"
  ```

  Heredoc mit `<<EOF` (unquoted) expandiert `${esc_key}` /
  `${esc_sec}` aus der Container-Env. Compose-Escape `$$` ist
  Pflicht, weil Compose YAML-seitig `${VAR}` selbst substituieren
  wuerde; mit `$$VAR` bleibt das Literal `$VAR` ueber, das die
  Shell dann per `.env` aufloest.

  **JSON-Safety via Bash-Parameter-Expansion**: die zwei
  `${var//pattern/replacement}`-Konstrukte vor dem Heredoc
  escapen erst `\` zu `\\` und dann `"` zu `\"`. Das ist
  bash-Builtin (kein externes `sed`/`jq` noetig). Damit gibt es
  **keine** Zeichen-Restriktion in `.env`: jeder Wert, den ein
  Password-Manager erzeugt, wird korrekt fuer JSON eskapiert.
  Empirisch verifiziert mit
  `S3_SECRET_KEY=foo"bar\\baz/qux@quux#hash` im BD.1
  v4-Stresstest (Output: `"secretKey":
  "foo\\"bar\\\\baz/qux@quux#hash"` — valides JSON, korrekt
  parsbar, aws-cli authentifiziert erfolgreich).

- **Server-Service**: konkretes Pattern fuer den `seaweed`-Service. Bind
  explizit auf `127.0.0.1`; Server-Healthcheck entfaellt bewusst (gleiche
  Argumentation wie zuvor bei MinIO — `seaweed-init` ist der
  Erreichbarkeits-Vertrag, siehe Block weiter unten):

  ```yaml
  seaweed:
    image: chrislusf/seaweedfs:4.31
    depends_on:
      seaweed-config:
        condition: service_completed_successfully
    command: server -dir=/data -s3 -s3.config=/etc/seaweed/s3.json
    ports:
      - "127.0.0.1:${S3_API_PORT}:8333"
      - "127.0.0.1:${SEAWEED_MASTER_PORT}:9333"
    volumes:
      - seaweed-data:/data
      - seaweed-config:/etc/seaweed:ro

  volumes:
    seaweed-data:
    seaweed-config:
  ```

  **Kein `env_file: .env`** am Server: SeaweedFS liest auch im
  konfigurierten Modus keine Credentials aus dem Environment, sondern
  ausschliesslich aus dem via `-s3.config=...` referenzierten
  JSON-File. Das ist anders als bei MinIO, womit Risk #10 fuer den
  Server-Container entfaellt; die Risk-Note bleibt fuer
  `seaweed-config`, `seaweed-init` und `aws-tools` relevant (alle drei
  brauchen die Keys aus `env_file:` als Container-Env).

  **Rebuild-Vertrag** (korrigiert im BD.1 v4-Review nach
  empirischer Beobachtung):
  - `seaweed-config` re-rennt bei **jedem** `compose run` oder
    `compose up`, weil die `depends_on:
    service_completed_successfully`-Kette ihn als Voraussetzung
    fuer `seaweed`/`seaweed-init`/`aws-tools` immer wieder
    re-evaluiert (Compose v5.x setzt die One-Shots bei jedem
    Trigger neu auf). Das Named Volume `seaweed-config` ueberlebt
    `compose up -d` ohne `-v`, der Inhalt wird dabei aber **jedes
    Mal aus der aktuellen `.env` neu geschrieben** — der
    Container-Eintrag im `docker ps --all`-Output zeigt jeweils
    einen neuen `StartedAt`-Timestamp.
  - **Effekt fuer Credential-Aenderungen**: `.env` editieren
    reicht **nicht** allein. Der `seaweed`-Server liest seine
    `s3.json` nur beim Container-Start in den Memory und haelt
    die Identity dann persistent. Eine geaenderte `.env`
    triggert zwar das Re-Render der `s3.json` im Volume, der
    laufende `seaweed`-Server bemerkt das aber nicht.
  - **Korrekte Rebuild-Sequenz** je nach Zielzustand:
    - **Credentials aktualisieren, Daten behalten**:
      `compose down && compose up -d` (ohne `-v`). Postgres-
      Daten + SeaweedFS-Bucket-Inhalt bleiben, `seaweed`
      restartet und liest die neue `s3.json`.
    - **Komplett-Reset (Demo zuruecksetzen)**: `compose down
      -v && compose up -d`. Verwirft Postgres-Daten +
      Bucket-Inhalt + s3.json-Volume; alle Init-Schritte
      laufen frisch.
  - **Was `-v` wirklich loescht**: alle drei Named Volumes
    (`postgres-data`, `seaweed-data`, `seaweed-config`).
    Bucket-Inhalt geht damit weg — das ist meistens **nicht**
    erwuenscht, wenn nur Credentials aktualisiert werden
    sollen. README muss das im Troubleshooting-Block klar
    trennen.

- **Bucket-Init**: separater `seaweed-init`-Service (one-shot
  `restart: "no"`), der `amazon/aws-cli` startet, mit eingebautem
  Retry auf die S3-API wartet und den Bucket idempotent anlegt
  (head-bucket || create-bucket). Konkretes Pattern:

  ```yaml
  seaweed-init:
    image: amazon/aws-cli:2.34.61
    depends_on:
      - seaweed
    env_file:
      - .env
    environment:
      AWS_ACCESS_KEY_ID: ${S3_ACCESS_KEY}
      AWS_SECRET_ACCESS_KEY: ${S3_SECRET_KEY}
      AWS_DEFAULT_REGION: us-east-1
      AWS_EC2_METADATA_DISABLED: "true"
    entrypoint:
      - /bin/bash
      - -c
      - |
        set -eu
        until aws --endpoint-url http://seaweed:8333 \
            s3api list-buckets > /dev/null 2>&1
        do
          echo 'waiting for seaweed s3…'
          sleep 2
        done
        aws --endpoint-url http://seaweed:8333 \
            s3api head-bucket --bucket "$${S3_BUCKET}" 2>/dev/null \
          || aws --endpoint-url http://seaweed:8333 \
            s3api create-bucket --bucket "$${S3_BUCKET}"
    restart: "no"
  ```

  Das `aws s3api list-buckets` ist der Readiness-Probe (statt eines
  Health-Endpunkts am Server). Die anschliessende
  `head-bucket || create-bucket`-Kette ist idempotent, weil
  `head-bucket` mit ExitCode != 0 bei nicht existierendem Bucket
  scheitert und dann der `create-bucket`-Pfad greift. Die `aws`-
  CLI liest die Credentials aus `AWS_ACCESS_KEY_ID` /
  `AWS_SECRET_ACCESS_KEY` (gemappt aus `.env`), keine
  URL-Embedding-Fragwuerdigkeiten mehr.

  **`AWS_DEFAULT_REGION=us-east-1`** ist Pflicht: aws-cli weigert
  sich, ohne Region zu arbeiten; SeaweedFS ignoriert die Region
  S3-API-seitig, akzeptiert aber `us-east-1` als kanonischen
  Default. **`AWS_EC2_METADATA_DISABLED=true`** spart einen 6s-
  Hang beim ersten Aufruf in Nicht-EC2-Umgebungen, weil aws-cli
  sonst versucht, IAM-Rollen vom EC2-Metadata-Service zu lesen.

  **YAML-Form bewusst array + Literal-Block (`|`)**: ein folded
  scalar (`>`) wuerde die Newlines zu Spaces falten und damit die
  Backslash-Line-Continuation `\\` zu `\ ` (escaped space) machen.
  Die array-Form macht `/bin/bash -c <script>` explizit und der
  Literal-Block (`|`) haelt Newlines, sodass die Continuation
  wirklich Continuation ist.

  Wichtig: `env_file: .env` ist am `seaweed-init`-Container
  **Pflicht** (siehe Risk #10). Die zusaetzlichen `environment:`-
  Eintraege fuer `AWS_*`-Variablen sind YAML-Substitutionen aus der
  Compose-Shell-Env (nicht `$${...}`-Escapes), weil sie zur
  Container-Build-Zeit fix gesetzt werden sollen — anders als die
  Heredoc-Variablen, die zur Runtime aus der Container-Env
  expandieren.

- **Ad-hoc-Tools-Service**: zusaetzlicher `aws-tools`-Service mit
  Entrypoint-Wrapper, der `--endpoint-url` als Default voranstellt
  und das User-Subkommando per `exec` weiterreicht. Service ist
  via `profiles: ["tools"]` vom `up -d`-Default ausgeschlossen;
  nur durch expliziten `compose run`-Aufruf materialisiert:

  ```yaml
  aws-tools:
    image: amazon/aws-cli:2.34.61
    depends_on:
      seaweed-init:
        condition: service_completed_successfully
    profiles: ["tools"]
    env_file:
      - .env
    environment:
      AWS_ACCESS_KEY_ID: ${S3_ACCESS_KEY}
      AWS_SECRET_ACCESS_KEY: ${S3_SECRET_KEY}
      AWS_DEFAULT_REGION: us-east-1
      AWS_EC2_METADATA_DISABLED: "true"
    entrypoint:
      - /bin/bash
      - -c
      - |
        set -eu
        exec aws --endpoint-url http://seaweed:8333 "$$@"
      - bash
    volumes:
      - ./out:/work
  ```

  **Mechanik des Entrypoint-Wrappers**: das `amazon/aws-cli`-Image
  hat `aws` als Default-`ENTRYPOINT`; das Override hier ersetzt das
  durch ein `bash -c`-Script, das per `exec aws --endpoint-url …
  "$@"` das vom User uebergebene Subkommando weiterreicht. Der
  trailing `bash`-Eintrag als letztes Listenelement ist das
  Sentinel-`$0` fuer `bash -c`; Compose uebergibt dann
  `compose run`-Args als `$1`, `$2`, ... in den Script. Damit
  verhalten sich Aufrufe wie
  `docker compose run --rm aws-tools s3 ls s3://${S3_BUCKET}/`
  exakt so, als waere `aws --endpoint-url=…` der Direkt-Entrypoint
  — User merken den Wrapper nicht.

  **Sicherheits-Effekt**: Credentials erscheinen ausschliesslich
  als Env-Vars (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`),
  nicht in URLs, nicht in Argumenten. URL-reservierte Zeichen
  (`:`, `@`, `/`, `?`, `#`) und JSON-Special-Chars (`"`, `\`) in
  den Demo-Keys sind alle unkritisch (Stresstest mit
  `foo"bar\\baz/qux@quux#hash` als Secret empirisch verifiziert).

  **Wichtig zum Mount-Pfad**: relative Bind-Mounts loest Compose
  **gegen das Verzeichnis der Compose-Datei** auf, nicht gegen
  das Working Directory des Aufrufers. `./out` ist damit
  `examples/bi-demo/out/`, nicht das Repo-Root-`out/`. Der
  Demo-Flow §6 + BD.4 schreiben CLI-Output deshalb konsequent
  nach `examples/bi-demo/out/...`; die `aws s3 cp`-Quelle bleibt
  `/work/` im Container.

- **Server-Healthcheck**: bewusst **kein** Compose-Healthcheck am
  `seaweed`-Service. Zwar enthaelt das SeaweedFS-Image `wget`, der
  konkrete Healthcheck-Endpunkt-Vertrag ist aber zwischen
  SeaweedFS-Versionen nicht stabil dokumentiert. Stattdessen wartet
  `seaweed-init` selbst bis zum erfolgreichen `aws s3api
  list-buckets` + `head-bucket||create-bucket`-Vertrag; alle
  weiteren Services (z. B. `dmigrate`) haengen via `depends_on:
  seaweed-init: { condition: service_completed_successfully }` an
  diesem Init-Service statt am SeaweedFS-Server direkt. Damit ist
  „S3-API ist nutzbar" eindeutig pinbar — strikter als ein
  HTTP-Status-Endpunkt-Check, weil der Vertrag eine echte
  S3-Operation deckt.

Der erste Demo-Schnitt muss noch keine produktive S3-Integration in
`d-migrate` voraussetzen. Solange der Object-Storage-ArtifactStore noch
nicht implementiert ist
([`object-storage-artifact-store.md`](../next/object-storage-artifact-store.md)),
kann das Smoke-Script Artefakte lokal erzeugen und sie mit dem
`aws s3 cp`-Client (im `aws-tools`-Service, §5.3) in den Demo-Bucket
kopieren. Sobald `d-migrate` `s3://`-Artefaktziele unterstuetzt, wird
dieser Zwischenschritt durch direkte Ausgabe nach SeaweedFS ersetzt.

### 5.4 d-migrate

Der erste Schnitt kann zwei Betriebsarten dokumentieren:

- Host-CLI: lokal gebautes `d-migrate` greift auf `localhost:55432` zu
- Container-CLI: optionaler Compose-Service nutzt das Runtime-Image und greift
  auf `postgres:5432` zu

Host-CLI ist fuer Entwickler einfacher, Container-CLI ist fuer reproduzierbare
Demo-Skripte interessanter.

### 5.5 `.env.example` (Skeleton)

```dotenv
# PostgreSQL — Demo-only credentials, NOT for production
POSTGRES_DB=dmigrate_demo
POSTGRES_USER=dmigrate
POSTGRES_PASSWORD=demo-pg-pw-change-me
POSTGRES_PORT=55432

# SeaweedFS S3 API — Demo-only credentials, NOT for production.
# Single Source of Truth: der `seaweed-config`-One-Shot rendert
# die SeaweedFS-`s3.json` aus diesen Werten; `seaweed-init` und
# `aws-tools` mappen sie auf `AWS_ACCESS_KEY_ID` /
# `AWS_SECRET_ACCESS_KEY` fuer die AWS CLI (Env-Var-basiert,
# kein URL-Embedding, keine Argument-Positionierung).
#
# Keine Zeichen-Restriktion: `"` und `\` werden vor dem JSON-
# Heredoc bash-parameter-expanded; `:` `@` `/` `?` `#` gehen
# als Env-Var-Wert ohne URL-Kontext. Jeder Password-Manager-
# generierte Wert akzeptiert (Stresstest §5.3).
S3_ACCESS_KEY=demoaccesskey
S3_SECRET_KEY=demosecretkey
S3_API_PORT=59000
SEAWEED_MASTER_PORT=59001
S3_BUCKET=dmigrate-demo

# Metabase
METABASE_PORT=3000
```

Konvention: PostgreSQL-Passwort traegt den `change-me`-Suffix
als sichtbaren Marker. SeaweedFS S3-Keys sind Demo-Strings; sie
sind Single Source of Truth fuer die zur Laufzeit gerenderte
`s3.json` und werden von der AWS CLI als `AWS_ACCESS_KEY_ID` /
`AWS_SECRET_ACCESS_KEY`-Env-Vars verarbeitet — **keine**
Spiegelung in einer zweiten Datei, **kein** URL-reserved-Char-
Caveat, **keine** JSON-Special-Char-Restriktion (bash-Parameter-
Expansion in `seaweed-config` escaped `"` und `\\` JSON-konform,
siehe §5.3). Damit ist jeder Password-Manager-generierte Wert
akzeptiert. Das ist trotzdem
**keine** Production-Konvention; eine echte Multi-Identity-
IAM-Loesung mit IAM-Policies pro User bleibt Folge-Slice
(BD.6+). README dokumentiert die Demo-Natur explizit.
`.gitignore` muss `.env` (ohne `.example`) ausschliessen.

### 5.6 `.d-migrate.yaml` (Skeleton)

`.d-migrate.yaml` wird vom CLI-Resolver
([`NamedConnectionResolver.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt)
Zeile 46) als `database.connections.<name>: "<scheme>://..."`-
**String-URL** gelesen, **nicht** als objektfoermiger Eintrag. Das
URL-Schema folgt
[`ConnectionUrlParser`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionUrlParser.kt)
(Zeile 12ff): `postgresql://` (nicht `jdbc:postgresql://`).
`${ENV_VAR}`-Substitution ist Bestand der Resolver-Konvention.

```yaml
database:
  connections:
    demo_pg: "postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@localhost:${POSTGRES_PORT}/${POSTGRES_DB}"
    demo_pg_container: "postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}"
```

Alle ENV-Werte werden aus `.env` substituiert (§5.5). Damit
funktioniert die Demo auch, wenn der User einen Port-Konflikt mit
`POSTGRES_PORT=55433` umgeht (§10 Risk #6) — die `.d-migrate.yaml`
bleibt unveraendert.

Die zwei Eintraege decken die beiden Betriebsarten aus §5.4 ab:

- **Host-CLI** ruft `--source demo_pg` (geht ueber Host-Port
  `localhost:${POSTGRES_PORT}` aus dem `127.0.0.1:…:5432`-Bind
  in §5.1).
- **Container-CLI** ruft `--source demo_pg_container` (geht
  ueber das Compose-Default-Netz und den Container-Port
  `postgres:5432`; der Host-Port-Bind ist hier irrelevant).

README dokumentiert beide Varianten in einem einzigen Copy-
Paste-Block, damit der User nicht zwischen zwei `.d-migrate.yaml`-
Versionen umschalten muss.

---

## 6. Demo-Flow

Ein minimaler Demo-Ablauf:

```text
cp examples/bi-demo/.env.example examples/bi-demo/.env  # einmalig
mkdir -p examples/bi-demo/out                           # Host-Owner
set -a; source examples/bi-demo/.env; set +a
docker compose -f examples/bi-demo/docker-compose.yml up -d

export D_MIGRATE_CONFIG=examples/bi-demo/.d-migrate.yaml
d-migrate schema reverse --source demo_pg \
    --output examples/bi-demo/out/reverse.yaml
d-migrate data profile --source demo_pg \
    --output examples/bi-demo/out/profile.json
d-migrate schema generate --source examples/bi-demo/out/reverse.yaml \
    --target postgresql

docker compose -f examples/bi-demo/docker-compose.yml run --rm aws-tools \
    s3 cp --recursive /work/ "s3://${S3_BUCKET}/runs/manual/"
```

Hinweise:

- `examples/bi-demo/.d-migrate.yaml` liegt **nicht** im Repo-Root;
  ohne `D_MIGRATE_CONFIG` (oder `--config examples/bi-demo/.d-migrate.yaml`)
  findet der CLI-Resolver die Datei nicht — Fallback ist nur
  `./.d-migrate.yaml`
  ([`NamedConnectionResolver.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt)
  Zeile 36).
- `seaweed-init` (siehe §5.3) erzeugt den Bucket beim Compose-Start;
  ad-hoc `aws s3`-Aufrufe laufen ueber den separaten
  `aws-tools`-Service (§5.3), der das `out/`-Verzeichnis als
  `/work` mountet und `--endpoint-url=http://seaweed:8333` via
  Entrypoint-Wrapper voranstellt. Damit ist `aws`/`mc` **keine**
  Host-Voraussetzung.
- `examples/bi-demo/out/` muss vor dem ersten `compose run`
  existieren (deshalb das `mkdir -p`-Praefix). Sonst legt der
  Docker-Daemon das Bind-Mount-Ziel als `root:root` an und der
  nachfolgende Host-CLI-Schreibzugriff im `d-migrate schema
  reverse --output examples/bi-demo/out/reverse.yaml`-Schritt
  scheitert mit `Permission denied`.

Danach kann Metabase im Browser auf die Demo-DB zeigen und einfache Fragen
beantworten:

- Umsatz pro Tag
- Bestellungen pro Status
- Top-Kunden
- Datenqualitaetsauffaelligkeiten aus Profiling-Report gegen Dashboard-Werte

---

## 7. Beispieldaten

Der Demo-Datenbestand sollte klein, aber realistisch sein:

- `customers` — ~50 Zeilen
- `products` — ~30 Zeilen
- `orders` — ~500 Zeilen (verteilt ueber 90 Tage; deterministisch
  via festem Datums-Anker `'2026-01-01'::date`, eingebracht per
  psql-`\set demo_start_date '2026-01-01'` und referenziert als
  `DATE :'demo_start_date'`; daraus `orders.created_at :=
  DATE :'demo_start_date' + ((i * random()) || ' days')::interval`
  aus `generate_series(1, 500) AS s(i)` — **kein**
  `current_date`, **kein** `now()`, sonst flippt der Datums-
  Korridor bei jedem Build. `\set` funktioniert, weil das
  offizielle Postgres-Image `.sql`-Dateien aus
  `/docker-entrypoint-initdb.d/` via `psql -f` ausfuehrt — siehe
  Determinismus-Vertrag in BD.2.)
- `order_items` — ~1500 Zeilen (3:1 zu `orders`)
- optional `events` fuer Zeitreihen — ~10.000 Zeilen (60 Tage,
  ~165/Tag)

Volumen ist bewusst klein (`docker compose up` < 30 s gesamt),
deckt aber die heutigen Profiling-Signale ab. Die genauen
Warning-Codes sind durch
[`WarningCode`](../../../hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/types/WarningCode.kt)
+ [`WarningRules`](../../../hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/rules/WarningRules.kt)
festgelegt; der Report rendert pro Spalte `nullCount` /
`distinctCount` / `numericStats.{min,max,avg,sum}` etc., **kein**
`null_ratio`-Feld.

- Fremdschluessel `orders.customer_id → customers.id`,
  `order_items.{order_id,product_id}`
- Datentypen-Mix: `int`, `text`, `timestamp with time zone`,
  `numeric(10,2)`, `boolean`, optional `jsonb` fuer eine
  Stored-Procedure-Demo
- Sichtbare NULL-Verteilung: `customers.middle_name` und
  `orders.notes` mit ~5% NULL, sichtbar im
  `column.nullCount`-Feld. (`HIGH_NULL_RATIO` feuert erst ab
  ≥ 50% — siehe `HighNullRatioRule.threshold = 0.5`; eine
  zusaetzliche Spalte mit ≥ 50% NULL ist optional, wenn die
  Demo ein Warning explizit pinnen will.)
- Bewusste Outlier in `order_items.unit_price` (z. B. ein
  Wert > 99 999, der die `numericStats.max` sichtbar von
  `numericStats.avg` abhebt). Ein eigener Outlier-Warning-Code
  existiert heute **nicht** — siehe Risk #8 und BD.6+ als
  Folge-Slice fuer Outlier-Rule.
- `customers.email` mit ~5% leerem String fuer
  `CONTAINS_EMPTY_STRINGS`-Warning.
- `customers.middle_name` mit „N/A"-Eintraegen fuer
  `POSSIBLE_PLACEHOLDER_VALUES`-Warning (Rule matcht
  case-insensitiv gegen Placeholder-Set `{n/a, na, null, none,
  -, --, tbd, unknown, test, xxx, dummy}`, siehe
  [`PlaceholderValuesRule`](../../../hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/rules/WarningRules.kt)
  Z.158).
- `orders.notes` zusaetzlich zu ~5% NULL noch ~3%
  Whitespace-only-Eintraege (`'   '`, drei Spaces) fuer
  `CONTAINS_BLANK_STRINGS`-Warning. Rule feuert bei
  `blankStringCount > 0` auf STRING-Spalten
  ([`BlankStringsRule`](../../../hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/rules/WarningRules.kt)
  Z.81).
- `products.category` als Low-Cardinality-Spalte (3-4 distinkte
  Werte ueber ~30 Zeilen) fuer `LOW_CARDINALITY`-Warning (Rule
  feuert bei `distinctCount in 1..5` und `nonNullCount >= 10`,
  [`LowCardinalityRule`](../../../hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/rules/WarningRules.kt)
  Z.114); liefert zusaetzlich `DUPLICATE_VALUES`-Warning, weil
  ~30 Zeilen ueber nur 3-4 distinkte Werte zwangslaeufig viele
  Duplikate ergeben und
  [`DuplicateValuesRule`](../../../hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/rules/WarningRules.kt)
  Z.128 bei `duplicateValueCount > 0` feuert.
- `orders.status` deckt {`pending`, `paid`, `cancelled`,
  `refunded`} ab (fuer BI-Charts „Bestellungen pro Status")
- ausreichend Daten fuer sinnvolle BI-Charts, aber schnell startbar

---

## 8. Spaetere Erweiterungen

Nach dem ersten Metabase/PostgreSQL-Schnitt koennen weitere Varianten folgen:

- Superset als alternative BI-Oberflaeche
- ClickHouse als OLAP-Ziel fuer schnelle Dashboards
- DuckDB fuer lokale Analyse exportierter Artefakte
- Parquet-Ausgabe, sobald der Parquet-Plan umgesetzt ist
- direkte `s3://`-Ausgabe nach SeaweedFS, sobald der Object-
  Storage-ArtifactStore umgesetzt ist
- Data-Quality-Export aus Profiling-Reports
- Catalog-Publishing von Demo-Schema und Profiling-Artefakten

Diese Erweiterungen sollten optional bleiben, damit die Basisdemo einfach
startbar bleibt.

---

## 9. Geplante Arbeitspakete

Die Sub-Slices BD.1-BD.5 schliessen die Basisdemo ab. Sie sind so
geschnitten, dass nach jedem Slice ein nutzbarer Zustand entsteht
(Infrastruktur → Daten → BI → CLI → Doku/Smoke).

### Sub-Slice BD.1 — Compose-Skeleton + Healthchecks

**Ziel**: `docker compose up -d` startet PostgreSQL, SeaweedFS
und die beiden One-Shots (`seaweed-config`, `seaweed-init`)
stabil. Pro Service ein passender Erreichbarkeits-Vertrag (siehe
§5.3: SeaweedFS hat **keinen** Server-Healthcheck,
`seaweed-init` ist der Readiness-Vertrag).

**Akzeptanzkriterien**:

- [x] `examples/bi-demo/docker-compose.yml` mit den fuenf
  Services aus §5.1 + §5.3 (Postgres, seaweed-config, SeaweedFS,
  seaweed-init, aws-tools) und gepinnten Image-Tags. Keine
  versionierte `config/seaweed-s3.json` — `seaweed-config`
  rendert sie aus `.env` in ein Named Volume (siehe §5.3).
- [x] `examples/bi-demo/.env.example` aus §5.5;
  `examples/bi-demo/.gitignore` schliesst `.env` **und** `out/`
  aus (Demo-Workflow schreibt CLI-Artefakte nach
  `examples/bi-demo/out/`, siehe §5.3 + §6).
- [x] `examples/bi-demo/out/.gitkeep` ist mitcommitted **oder**
  README + Smoke-Script praefixieren `mkdir -p
  examples/bi-demo/out` vor dem ersten `compose run`.
  Hintergrund: der `aws-tools`-Bind-Mount `./out:/work` legt das
  Host-Verzeichnis sonst beim ersten `compose run` als
  `root:root` an, was den Host-CLI-Schreibzugriff in BD.4
  (`d-migrate ... --output examples/bi-demo/out/…`) mit
  `Permission denied` bricht.
- [x] **Pre-Start**: `docker compose pull` zieht alle gepinnten
  Tags und scheitert sofort, wenn ein Image auf Docker Hub
  nicht mehr verfuegbar ist (siehe Risk #9).
- [x] Nach `docker compose up -d` (ohne Profile) gilt innerhalb
  von 90 s:
  - **Postgres**: `state=healthy` via `pg_isready`-Healthcheck
    aus §5.1.
  - **seaweed-config**: `state=exited`, `ExitCode=0` — Render
    der `s3.json` ins Named Volume lief gruen. Selber
    `jq -s`-Pinnungs-Pfad wie fuer `seaweed-init`, nur mit
    `Service == "seaweed-config"`.
  - **SeaweedFS-Server**: keine Healthcheck-Bedingung.
    Erreichbarkeit wird **nicht** direkt am Container gemessen,
    sondern indirekt via `seaweed-init`.
  - **seaweed-init**: `state=exited`, `ExitCode=0`,
    `service_completed_successfully` — Bucket-Init lief gruen
    und der aws-CLI-Retry hat die S3-API als nutzbar bestaetigt.
    Pinnung via `docker compose ps --all --format json | jq -s
    -e 'map(select(.Service == "seaweed-init")) | .[0].State
    == "exited" and .[0].ExitCode == 0'`. **Wichtig**:
    `--format json` liefert je nach Compose-Version
    **JSONL** (eine JSON-Zeile pro Service, Compose v2.24+
    inkl. v5.x) oder ein **Array** (aelteres v2.20-v2.23) —
    `jq -s` (slurp) schiebt beides in ein Array, danach ist
    der `map(select(...)) | .[0]`-Pfad ueber beide Formate
    robust. Empirisch im BD.1-Smoke 2026-06-04 gegen Compose
    v5.1.4 verifiziert (das `-s` ist gegen v5.x Pflicht; ohne
    `-s` scheitert die jq-Expression mit „Cannot index string
    with string"). `--all` ist Pflicht — `docker compose ps`
    ohne Flag listet beendete Container **nicht**.
  - **aws-tools**: nicht gestartet (steht unter
    `profiles: ["tools"]` und wird nur durch
    `docker compose run aws-tools …` materialisiert).
- [x] Smoke-Check fuer „S3-API ist nutzbar":
  `docker compose run --rm aws-tools s3 ls` exited 0 und
  listet `dmigrate-demo` als Bucket.
- [x] `docker compose down -v` raeumt Named-Volumes komplett ab
  (Idempotenz-Test).
- [x] `make ci` grün (keine zusätzlichen Tests, aber Doc-Linting
  muss laufen).

**Betroffene Dateien**:

- Neu: `examples/bi-demo/docker-compose.yml`
- Neu: `examples/bi-demo/.env.example`
- Neu: `examples/bi-demo/.gitignore`

**Dependencies**: keine.

**Risiken**: niedrig. Standard-Compose-Verträge.

### Sub-Slice BD.2 — Schema + Seed-Daten

**Ziel**: PostgreSQL haelt das Demo-Schema und die Seed-Daten aus
§7 nach Erststart bereit.

**Akzeptanzkriterien**:

- [x] `sql/001_schema.sql` mit den 5 Tabellen aus §7 (inklusive
  Fremdschluessel + Datentypen-Mix + optional `jsonb`-Spalte).
- [x] `sql/002_seed.sql` mit den Volumen-/Verteilungs-Vorgaben aus
  §7. Determinismus-Vertrag (alle Bausteine zusammen, sonst kein
  byte-identisches Replay):
  - Festes `SELECT setseed(0.42);` am Skript-Beginn.
  - Festes Datums-Anker `'2026-01-01'::date`, eingebracht via
    psql-Meta-Befehl `\set demo_start_date '2026-01-01'` und
    referenziert als `DATE :'demo_start_date'`. **Keine**
    Verwendung von `current_date` / `now()` /
    `clock_timestamp()` im Seed. Hintergrund: das offizielle
    Postgres-Image fuehrt `.sql`-Dateien aus
    `/docker-entrypoint-initdb.d/` via `psql -f` aus — `\set`
    und `:'var'`-Substitution sind damit Bestandteil der
    Demo-Toolchain. (Pure-SQL-Alternative: CTE `WITH params AS
    (SELECT '2026-01-01'::date AS demo_start)`, falls ein
    spaeterer Refactor die psql-Abhaengigkeit eliminieren will.)
  - `SET timezone = 'UTC';` am Skript-Beginn — sonst haengt die
    `timestamptz`-Repraesentation von der Container-Default-
    Zeitzone ab und die `pg_dump`-Ausgabe driftet. **Wichtig**:
    plain `SET`, kein `SET LOCAL`. Das offizielle Postgres-Image
    fuehrt `.sql`-Files via `psql -f` ohne explizite Transaktion
    aus; `SET LOCAL` waere ein No-op mit WARNING (`SET LOCAL can
    only be used in transaction blocks`). Session-scope reicht
    fuer die Skript-Laufzeit.
  - `SET max_parallel_workers_per_gather = 0;` — zwingt
    Single-Thread-Ausfuehrung. Mit Parallel-Scans waere die
    Reihenfolge der `random()`-Aufrufe nicht garantiert, was den
    Seed wert-mutiert (gleiche Sequenz, aber andere Zuordnung
    zu Zeilen). Auch hier plain `SET` aus dem gleichen Grund.
  - Stabiler Insert-Order: jede `INSERT INTO ... SELECT ...
    FROM ... ORDER BY <natural-key>` mit explizitem `ORDER BY`,
    damit die physische Reihenfolge in der Tabelle reproduzierbar
    ist (relevant fuer Idempotenz-Hashes und Profiling-Output).
- [x] Mount-Punkt
  `examples/bi-demo/sql/:/docker-entrypoint-initdb.d/`-Mount in
  `docker-compose.yml`.
- [x] Idempotenz: `docker compose down -v && docker compose up -d`
  produziert byte-identische Tabelleninhalte. Pinnung via
  Hash-Vergleich:
  `pg_dump --data-only --no-comments --no-sync -U dmigrate
  dmigrate_demo | grep -vE '^\\(un)?restrict ' | sha256sum`
  muss zwischen zwei fresh-up-Runs identisch sein. Der
  `\restrict`/`\unrestrict`-Filter ist ab Postgres-17-pg_dump
  Pflicht, weil dieser pro Dump-Invocation ein frisches
  Session-Restrict-Token generiert (Format-Artefakt, keine
  Daten-Aenderung); ohne den Filter unterscheiden sich die
  Hashes trotz byte-identischer Daten — empirisch im
  BD.2-Smoke 2026-06-04 verifiziert (Datendiff jenseits dieser
  zwei Zeilen war leer).
- [x] `make ci` grün.

**Betroffene Dateien**:

- Neu: `examples/bi-demo/sql/001_schema.sql`
- Neu: `examples/bi-demo/sql/002_seed.sql`
- Update: `examples/bi-demo/docker-compose.yml` (initdb-Mount)

**Dependencies**: BD.1.

**Risiken**: mittel. Deterministische Seed-Werte
(`setseed(0.42)` + `random()`) muessen ueber PG-Versionen stabil
bleiben — siehe §10 Risk #4.

### Sub-Slice BD.3 — Metabase-Integration

**Ziel**: Metabase ist erreichbar, manuelle Erstkonfiguration
gegen `demo_pg` ist im README dokumentiert.

**Akzeptanzkriterien**:

- [x] Konkreter Image-Tag-Pin: `metabase/metabase:v0.55.24.1`
  (juengster 0.55er-Patch auf Docker Hub Stand 2026-06-04,
  publiziert 2026-05-08). `docker compose pull metabase`
  belegt durchgelaufen.
- [x] `metabase`-Service aus §5.2 in `docker-compose.yml` (inkl.
  `start_period: 60s`-Healthcheck).
- [x] Named-Volume `metabase-data` ueberlebt `down` (aber **nicht**
  `down -v` — siehe BD.1). Konkreter Smoke: Admin-User anlegen +
  `demo_pg`-Datenquelle einrichten, dann `docker compose down`,
  dann `docker compose up -d`, dann im Browser pruefen, dass
  Login + Datenquelle weiter da sind (`MB_DB_FILE`-Mount aus
  §5.2 wirkt).
- [x] README dokumentiert: Admin-User-Anlage, Datenquelle
  `demo_pg` einrichten (Host: `postgres`, Port: `5432`,
  Datenbank/User/Passwort aus `.env`).
- [x] README dokumentiert mindestens drei Beispiel-Fragen
  (Umsatz/Tag, Bestellungen/Status, Top-Kunden) als Schritt-fuer-
  Schritt-Anleitung.
- [x] `make ci` grün.

**Betroffene Dateien**:

- Update: `examples/bi-demo/docker-compose.yml`
- Update: `examples/bi-demo/README.md`

**Dependencies**: BD.2 (Daten müssen da sein, damit Charts
sinnvoll sind).

**Risiken**: mittel. Metabase-Versions-Updates können
Erstkonfigurations-UI ändern — siehe §10 Risk #2.

### Sub-Slice BD.4 — d-migrate-Smoke

**Ziel**: Host-CLI-Workflow gegen `demo_pg` funktioniert; optional
Container-CLI-Variante dokumentiert.

**Akzeptanzkriterien**:

- [x] `examples/bi-demo/.d-migrate.yaml` aus §5.6.
- [x] `d-migrate schema reverse --source demo_pg --output
  examples/bi-demo/out/reverse.yaml` liefert eine valide
  Reverse-Definition (alle 5 Tabellen, FKs, Datentypen). **Output-
  Pfad ist `examples/bi-demo/out/...`**, nicht Repo-Root-`out/`
  — der `aws-tools`-Bind-Mount `./out:/work` loest gegen das
  Compose-Datei-Verzeichnis auf (§5.3).
- [x] `d-migrate data profile --source demo_pg --output
  examples/bi-demo/out/profile.json`
  liefert einen Profile-Report. BD.4 pinnt sichtbare
  Profiling-Signale entlang heutiger
  [`WarningCode`](../../../hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/types/WarningCode.kt):
  - `column.nullCount > 0` fuer `customers.middle_name` und
    `orders.notes` (~5% NULL aus §7 — keine Warning, aber im
    Report sichtbar).
  - `numericStats.max` >> `numericStats.avg` fuer
    `order_items.unit_price` (Outlier aus §7).
  - Mindestens fuenf Warning-Codes im Report:
    `CONTAINS_EMPTY_STRINGS` (auf `customers.email`),
    `CONTAINS_BLANK_STRINGS` (auf `orders.notes`,
    Whitespace-only-Eintraege aus §7),
    `POSSIBLE_PLACEHOLDER_VALUES` (auf `customers.middle_name`),
    `LOW_CARDINALITY` (auf `products.category`),
    `DUPLICATE_VALUES` (auf `products.category`, das durch seine
    Low-Cardinality automatisch viele Duplikate ueber ~30 Zeilen
    erzeugt — siehe §7).
- [x] `d-migrate schema generate --source
  examples/bi-demo/out/reverse.yaml --target postgresql` rendert
  eine valide DDL.
- [x] `docker compose run --rm aws-tools s3 cp --recursive
  /work/ "s3://${S3_BUCKET}/runs/manual/"` laed die Artefakte
  in den SeaweedFS-Bucket (Smoke-Vertrag aus §5.3; `out/` ist im
  `aws-tools`-Service als `/work` gemountet). Host-`aws` / `mc`
  ist nicht erforderlich. **Wichtig**: kein `aws`-Praefix — der
  Entrypoint-Wrapper stellt `aws --endpoint-url=…` automatisch
  voran (§5.3).
- [x] README dokumentiert Container-CLI-Variante fuer
  d-migrate als optional (mit `docker-compose.yml`-Service-
  Eintrag oder `docker run --rm --network bi-demo_default ...`).
- [x] `make ci` grün.

**Betroffene Dateien**:

- Neu: `examples/bi-demo/.d-migrate.yaml`
- Update: `examples/bi-demo/README.md`
- Update: `examples/bi-demo/docker-compose.yml` (optional:
  `dmigrate`-Service)

**Dependencies**: BD.2 (Daten) + BD.3 nicht (Metabase ist
unabhaengig).

**Risiken**: niedrig — d-migrate-CLI-Pfade sind etabliert
([`docs/user/guide.md`](../../user/guide.md)).

### Sub-Slice BD.5 — README + Smoke-Script + CI-Smoke

**Ziel**: Vollstaendiges README, Smoke-Script, das die Demo
End-to-End faehrt, und optional ein CI-Smoke-Job, der die Demo
ohne menschlichen Browser-Schritt prueft.

**Akzeptanzkriterien**:

- [x] `examples/bi-demo/scripts/smoke.sh` mit `set -euo pipefail`,
  beginnt mit `mkdir -p "$(dirname "$0")/../out"` (Bind-Mount-
  Owner, siehe §6 + BD.1), prueft Container-Health via
  `docker compose ps --all --format json | jq -s -e
  'map(select(.Service == "seaweed-init")) | .[0].State == "exited"
  and .[0].ExitCode == 0'` (`--all` ist Pflicht, sonst fehlt
  `seaweed-init` im exited-State; `jq -s` ist Pflicht, weil
  `--format json` je nach Compose-Version JSONL oder Array
  liefert — siehe BD.1), faehrt
  mindestens den d-migrate-Reverse + Profile Workflow aus BD.4,
  prueft S3-Upload via `docker compose run --rm aws-tools s3 ls
  "s3://${S3_BUCKET}/runs/"`. **`aws` wird ausschliesslich
  ueber den `aws-tools`-Service (§5.3) aufgerufen**, damit die
  Demo ohne Host-`aws` laeuft; `jq` ist Host-Voraussetzung
  (siehe README-Prereqs).
- [x] `Makefile`-Targets im Repo-Root (Konsistenz mit der
  Make-Konvention dieses Repos): `bi-demo-pull` (Pre-Start-Pull
  aus BD.1), `bi-demo-up`, `bi-demo-down` (mit/ohne `-v` als
  zwei separate Targets `bi-demo-down` und `bi-demo-purge`),
  `bi-demo-smoke` (ruft `examples/bi-demo/scripts/smoke.sh`).
  Kapseln den langen `docker compose -f
  examples/bi-demo/docker-compose.yml ...`-Pfad. README +
  Smoke-Script verwenden ausschliesslich Make-Targets als
  Top-Level-Befehle; die zugrunde liegenden `docker compose`-
  Kommandos stehen nur im Troubleshooting-Block.
- [x] `examples/bi-demo/README.md` vollstaendig:
  - **Voraussetzungen (Host)**: Docker (≥ 24), Docker Compose
    (≥ v2.20), `jq` (fuer Smoke-Script-`ps`-Parsing), d-migrate-
    CLI. `aws`/`mc` sind **keine** Host-Voraussetzung — der
    `aws-tools`-Service (§5.3) liefert die AWS CLI via
    `docker compose run`.
  - Start/Stop-Block (mit/ohne `-v`)
  - Metabase-Erstkonfiguration (Screenshot oder Schritt-fuer-Schritt)
  - d-migrate-Workflow aus BD.4 als Copy-Paste-Block
  - Cleanup-Block
  - Troubleshooting (Port-Konflikte, Healthcheck-Timeouts,
    Metabase-`start_period`, S3 via
    `docker compose run --rm aws-tools <aws-subcmd> …` — kein
    `aws`-Praefix, §5.3)
- [x] Optional: GitHub-Actions-Workflow `bi-demo-smoke.yml`, der
  `scripts/smoke.sh` ohne Metabase-Browser-Schritt im CI
  ausfuehrt (Best-Effort, kann anfangs als
  `continue-on-error: true` markiert sein).
- [x] `make ci` grün; manueller Demo-Run dokumentiert in
  Commit-Message des BD.5-Slices.

**Betroffene Dateien**:

- Neu: `examples/bi-demo/scripts/smoke.sh`
- Update: `examples/bi-demo/README.md`
- Update: `Makefile` (Repo-Root) — `bi-demo-*`-Targets
- Optional neu: `.github/workflows/bi-demo-smoke.yml`

**Dependencies**: BD.1-BD.4.

**Risiken**: niedrig. Smoke-Script ist Standard-Shell.

---

## 9a. Dependency-Graph

```
BD.1 (Compose+Healthchecks)
  └─→ BD.2 (Schema+Seed)
        ├─→ BD.3 (Metabase)
        └─→ BD.4 (d-migrate-Smoke)
              └─→ BD.5 (README+Smoke-Script)
```

- BD.3 und BD.4 sind unabhaengig voneinander und koennen parallel
  laufen, sobald BD.2 fertig ist.
- BD.5 schliesst die End-to-End-Pinnung.

---

## 10. Risiken

1. **Metabase-Provisioning aufwendig pro Version**. Mitigation:
   manuelle Browser-Konfiguration im Erstschnitt akzeptieren;
   Automatisierung als spaeterer Slice (BD.6+).
2. **Metabase-Versions-Updates aendern die Erstkonfigurations-UI**.
   Mitigation: Image-Tag in `docker-compose.yml` pinnen (siehe
   §5.2); README an einen konkreten Tag binden; Tag-Refresh ist
   ein eigener Slice.
3. **Service-Wildwuchs**. Superset, ClickHouse und DuckDB sollten
   erst nach der Basisdemo optional hinzukommen (§8). MVP =
   PostgreSQL + SeaweedFS + Metabase.
4. **PG-Version-Drift bei Seed-Determinismus**. `setseed()` +
   `random()` kann ueber PG-Major-Versionen leicht variieren.
   Mitigation: Image-Tag `postgres:17.10-trixie` pinnen
   (Patch-Komponente Pflicht, sonst driftet der Tag mit
   17.x-Updates); bei Major-Update Seed-Regeneration testen.
5. **Demo-Credentials in Produktion**. Mitigation: PostgreSQL-
   Default-Passwort traegt `change-me`-Suffix als sichtbaren
   Marker (§5.5). SeaweedFS S3-API laeuft mit einer
   Single-Identity-Demo-Config, die zur Laufzeit aus `.env` in
   ein Named Volume gerendert wird (`seaweed-config`-One-Shot,
   §5.3) — Single Source of Truth ist `.env`, keine Spiegelung
   in einer JSON-Datei mehr. Der Stack ist strikt an `127.0.0.1`
   gebunden (§5.3); README warnt explizit fuer beide Faelle
   (PG-Passwort vor jedem nicht-lokalen Run ersetzen;
   SeaweedFS-Credentials in `.env` ersetzen — `seaweed-config`
   rendert die `s3.json` bei jedem `compose run` neu, aber der
   laufende `seaweed`-Server liest sie nur beim Start; korrekte
   Rebuild-Sequenz fuer Credential-Updates ist deshalb `compose
   down && compose up -d` (ohne `-v`, damit Bucket-/Postgres-
   Daten bleiben — siehe §5.3 Rebuild-Vertrag-Block). Von
   127.0.0.1 abruecken nur, wenn Multi-Identity-IAM via BD.6+
   kommt. `.env` ist gitignored.
6. **Port-Konflikte mit Host-Diensten**. Mitigation: Ports
   bewusst hoch gewaehlt (`55432`, `59000`, `59001`, `3000`);
   README-Troubleshooting-Block deckt Anpassung ab.
7. **Image-Tag-Veraltung**. Pinning auf konkrete Tags faengt mit
   der Zeit Sicherheits-Updates nicht ein. Mitigation: BD.5 + ggf.
   spaeterer „BD-Tag-Refresh"-Slice; Dependabot/Renovate-Pfad
   ausserhalb dieses Plans.
8. **Profiling-Outlier-Code fehlt heute**.
   [`WarningCode`](../../../hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/types/WarningCode.kt)
   kennt keinen `OUTLIER`-Code; `HIGH_NULL_RATIO` feuert erst ab
   50%. Mitigation: BD.4-Akzeptanz beschraenkt sich auf die heute
   verfuegbaren Codes (`CONTAINS_EMPTY_STRINGS`,
   `POSSIBLE_PLACEHOLDER_VALUES`, `LOW_CARDINALITY`) plus
   sichtbare `nullCount`/`numericStats.max`-Werte. Eine
   `OUTLIER`-Rule oder ein abgesenkter `HIGH_NULL_RATIO`-
   Threshold ist Profiling-Folge-Slice (BD.6+ oder eigener
   `profiling-data-quality-export.md`-Sub-Slice).
9. **MinIO Community Edition archiviert — RESOLVED**
   (2026-06-04 im BD.1-Implementierungs-Sweep). Befund:
   - `minio/mc` ist auf
     [Docker Hub](https://hub.docker.com/r/minio/mc) eingefroren
     auf `RELEASE.2025-08-13T08-35-41Z-cpuv1` (CE ist
     Source-Only seit 2025-Q3).
   - `minio/minio` Docker-Hub-Tag-Pflege endete mit
     `RELEASE.2025-09-07T16-13-09Z` (bekannt-CVE-anfaellig); der
     Upstream-Security-Release `RELEASE.2025-10-15T17-29-55Z`
     existiert auf GitHub (Fix fuer CVE-2025-62506), wurde aber
     **nicht** auf Docker Hub publiziert.
   - Empirisch verifiziert via Docker-Hub-Tags-API + GitHub
     Releases-API am 2026-06-04 im BD.1-Implementierungs-Sweep:
     `docker compose pull` mit dem 2025-10-15-Tag scheitert mit
     `manifest unknown` (genau das vom Plan-Stand 2026-06-03
     erwartete Verhalten).

   Resolution: Object-Storage-Provider von MinIO auf SeaweedFS
   umgestellt. Verglichene Alternativen siehe §5.3-Tabelle:
   - **SeaweedFS** `chrislusf/seaweedfs:4.31` — gewaehlt
     (Apache-2.0, Semver-Tag, aktiv gepflegt, single-Container).
   - Garage — abgelehnt (Docker Hub nur SHA-Tags).
   - RustFS — abgelehnt (1.0.0-Beta, API-Breaking-Risiko).
   - Ceph RGW — abgelehnt (Multi-Komponenten-Overhead).

   Self-Build aus archiviertem MinIO-GitHub-Repo (vorheriger
   Plan-Stand 2026-06-03) wurde als nicht-nachhaltig verworfen:
   gefixte CVEs wandern nicht zurueck in den eingefrorenen
   GitHub-Tree, eine Self-Build-Strategie waere nur ein
   Verzoegerer der gleichen Source-Only-Falle.

   Auch der S3-Client wurde im BD.1 v4-Sweep (2026-06-04) auf
   `amazon/aws-cli:2.34.61` umgestellt — die vorherige Wahl
   `minio/mc` waere als „bleibt funktional" durchgegangen, aber:
   das `minio/mc`-Repo ist auf Docker Hub eingefroren (CE-Source-
   Only) und steht im selben Sustainability-Risiko wie der
   Server-Tag, vor dem Risk #9 schuetzt. AWS CLI ist der
   neutrale S3-Standard-Pfad mit offiziellem Amazon-Image und
   aktiver Versionspflege. Verglichene Alternativen: s5cmd
   (technisch passend, aber 2024-12-Release als juengstes
   Update — analoge Stagnation), rclone (Multi-Backend-Tool,
   S3-Fokus weniger idiomatisch).
10. **`env_file` im Compose-Container vergessen**. Compose-
    `.env`-Interpolation ist YAML-Substitution (`${VAR}` im
    Compose-File selbst), **nicht** Container-Env. Wer
    `$${S3_ACCESS_KEY}` im `entrypoint`-Skript verwendet,
    aber keinen `env_file:`- oder `environment:`-Block setzt,
    kriegt leere Variablen und schweigende AWS-CLI-Fehler
    (`Unable to locate credentials` oder vergleichbar).
    Mitigation: §5.3-Skeletons zeigen `env_file: .env` als
    Pflichtfeld fuer `seaweed-config`, `seaweed-init` und
    `aws-tools` (alle drei Container brauchen `S3_ACCESS_KEY`/
    `S3_SECRET_KEY` / `S3_BUCKET` als Container-Env). Der
    `seaweed`-Server-Container braucht **kein** `env_file:`,
    weil er Credentials ausschliesslich aus der via
    `-s3.config=...` gemounteten JSON liest, nicht aus dem
    Environment (§5.3). BD.1-Akzeptanz pinnt das durch den
    Smoke-Check.

---

## 11. Dependencies zu anderen Plaenen

- **[`object-storage-artifact-store.md`](../next/object-storage-artifact-store.md)**:
  bringt nativen `s3://`-Artifakt-Output. Solange noch nicht
  geliefert, faellt BD.4 auf den `aws s3 cp`-Zwischenschritt
  zurueck (§5.3). Sobald geliefert, ist ein BD.6-Slice „Direkte
  s3://-Ausgabe nach SeaweedFS" sinnvoll.
- **[`parquet-export-import-evaluation.md`](../next/parquet-export-import-evaluation.md)**:
  Parquet-Artifakte sind in §8 als spaetere Erweiterung gelistet;
  ein BD.7-Slice „Parquet-Demo gegen SeaweedFS" haengt direkt
  daran.
- **[`profiling-data-quality-export.md`](../next/profiling-data-quality-export.md)**:
  Data-Quality-Export aus Profiling-Reports ist §8 — Sub-Slice
  „BD.8 Data-Quality-Dashboard in Metabase" haengt am Export-Format
  dieses Plans.
- **[`telemetry-observability-port.md`](../next/telemetry-observability-port.md)**:
  Telemetrie ist nicht Teil der Basisdemo, aber ein zukuenftiger
  „BD-Telemetry"-Slice koennte OpenTelemetry-Collector als
  vierten Service zeigen.

Keine dieser Plaene ist **Voraussetzung** fuer BD.1-BD.5; alle sind
Erweiterungsoptionen fuer BD.6+.
