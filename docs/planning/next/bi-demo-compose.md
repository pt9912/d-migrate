# Plan: BI-Demo-Umgebung mit Docker Compose

> Dokumenttyp: Demo- und Integrationsplan
>
> Status: Next (verfeinert 2026-06-03 — Sub-Slice-Schnitt BD.1-BD.5,
> Image-Pinning, Healthcheck-Verträge, Skeletons; Entwurf-Stand
> 2026-05-01 weitergeschrieben).
>
> **Aktivierungsbedingung** (Move nach `in-progress/`): Maintainer-
> Ressourcen + BD.1 wird begonnen. BD.1-BD.5 sind ohne externe
> Abhängigkeit implementierbar. Für vollständige Demo-Story
> (`s3://`-Artifakt-Output, Parquet-Schritt) hängen einzelne
> Erweiterungen aus §8 an
> [`object-storage-artifact-store.md`](object-storage-artifact-store.md)
> bzw.
> [`parquet-export-import-evaluation.md`](parquet-export-import-evaluation.md);
> der MVP-Schnitt (BD.1-BD.5) kommt ohne diese aus.
>
> Referenzen:
>
> - [`../in-progress/roadmap.md`](../in-progress/roadmap.md)
> - [`orchestrator-examples.md`](orchestrator-examples.md)
> - [`profiling-data-quality-export.md`](profiling-data-quality-export.md)
> - [`parquet-export-import-evaluation.md`](parquet-export-import-evaluation.md)
> - [`object-storage-artifact-store.md`](object-storage-artifact-store.md)
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
- MinIO als S3-kompatibler Object-Storage fuer Demo-Artefakte
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
- MinIO-Service mit initialem Demo-Bucket fuer `d-migrate`-Artefakte
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
  minio/
    init-bucket.sh
  scripts/
    smoke.sh
```

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

- **Image**: `postgres:17-alpine` (Alpine-Variante fuer
  Disk-Footprint; Stand 2026-06-03 sind 17.x und 18.x beide
  aktuell supportet — siehe
  [PostgreSQL Release-Hinweise 18.4 / 17.10 / 16.14 / 15.18 / 14.23](https://www.postgresql.org/about/news/postgresql-184-1710-1614-1518-and-1423-released-3297/)
  und [Docker Hub `postgres`](https://hub.docker.com/_/postgres) —;
  17 ist die juengere stabile Reihe mit groesserer
  Treiber-/Tooling-Vertraeglichkeit als die ganz frische 18er-Linie
  und bewusst auf 17 statt 18 gepinnt, bis 18 in BD-Tag-Refresh
  durchgepruft ist). Tag in `docker-compose.yml` explizit pinnen,
  **kein** `:latest`.
- **Datenbank**: `dmigrate_demo`
- **User**: `dmigrate`
- **Passwort**: nur Demo-Secret aus `.env.example`, nicht produktiv
- **Port**: lokal `55432`, um Konflikte mit vorhandenen
  PostgreSQL-Instanzen zu reduzieren
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

- **Image**: `metabase/metabase:v0.52.10.4` (zum Doc-Refresh aktueller
  stable-Track; bei Pflege auf neueren stable umstellen). Explizit
  pinnen, **kein** `:latest`.
- **Port**: lokal `3000`
- **State-Volume**: Named-Volume `metabase-data` fuer das interne
  H2-State-File (Demo-Default; eine produktive Metabase-Deployment-
  Backplane via Postgres ist explizit out-of-scope).
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

### 5.3 MinIO / S3-kompatibler Object Storage

MinIO dient als lokaler S3-kompatibler Speicher fuer Demo-Artefakte. Der
Service macht die Zero-Disk-Richtung greifbar, ohne direkt eine echte
Cloud-Abhaengigkeit einzufuehren.

- **Image**: `minio/minio:RELEASE.2025-09-07T16-13-09Z` (Server) +
  `minio/mc:RELEASE.2025-09-07T16-13-09Z` (Client). Pinning auf die
  selben RELEASE-Tags, damit Server + Client zusammenpassen.
- **API-Port**: lokal `59000`
- **Console-Port**: lokal `59001`
- **Bucket**: `dmigrate-demo`
- **Access Key / Secret Key**: nur Demo-Credentials aus
  `.env.example`
- **Prefix fuer Laeufe**: `runs/<timestamp-or-operation-id>/`
- **Bucket-Init**: separater `minio-init`-Service (one-shot
  `restart: "no"`), der `minio/mc` startet, mit eingebautem
  Retry auf MinIO wartet und `mc mb --ignore-existing
  local/dmigrate-demo` ausfuehrt. Konkretes Pattern:

  ```yaml
  minio-init:
    image: minio/mc:RELEASE.2025-09-07T16-13-09Z
    depends_on:
      - minio
    entrypoint: >
      /bin/sh -c "
      until mc alias set local http://minio:9000 \
        $${MINIO_ROOT_USER} $${MINIO_ROOT_PASSWORD}
      do
        echo 'waiting for minio…'; sleep 2
      done;
      mc mb --ignore-existing local/$${MINIO_BUCKET}
      "
    restart: "no"
  ```

- **Ad-hoc-`mc`-Service**: zusaetzlicher `mc-tools`-Service
  **ohne** `entrypoint`-Override, damit
  `docker compose run --rm mc-tools mc <cmd>` direkt das Image-
  Default-Entrypoint nutzt. Service ist via `profiles: ["tools"]`
  vom `up -d`-Default ausgeschlossen, damit er bei
  `docker compose up` nicht startet, sondern nur bei
  expliziten `compose run`-Aufrufen erzeugt wird:

  ```yaml
  mc-tools:
    image: minio/mc:RELEASE.2025-09-07T16-13-09Z
    depends_on:
      minio-init:
        condition: service_completed_successfully
    profiles: ["tools"]
    environment:
      MC_HOST_local: http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@minio:9000
    volumes:
      - ./out:/work
  ```

  Damit laeuft `docker compose run --rm mc-tools mc ls
  local/$MINIO_BUCKET/` ohne `--entrypoint`-Override und ohne
  Host-`mc`. Das `out/`-Mount macht `mc cp` aus dem Repo-Root
  reproduzierbar.

- **Server-Healthcheck**: bewusst **kein** Compose-Healthcheck am
  `minio`-Service. Das offizielle `minio/minio`-Image enthaelt
  weder `curl` noch `wget` verlaesslich
  ([minio/minio#18389](https://github.com/minio/minio/issues/18389)),
  und ein `mc`-Check wuerde einen zweiten Container im selben
  Compose-Service brauchen. Stattdessen wartet `minio-init` selbst
  bis zum erfolgreichen `mc alias set`; alle weiteren Services
  (z. B. `dmigrate`) haengen via `depends_on:
  minio-init: { condition: service_completed_successfully }` an
  diesem Init-Service statt am MinIO-Server direkt. Damit ist
  „MinIO ist nutzbar" eindeutig pinbar, ohne auf Image-interne
  HTTP-Tools angewiesen zu sein.

Der erste Demo-Schnitt muss noch keine produktive S3-Integration in
`d-migrate` voraussetzen. Solange der Object-Storage-ArtifactStore noch nicht
implementiert ist
([`object-storage-artifact-store.md`](object-storage-artifact-store.md)),
kann das Smoke-Script Artefakte lokal erzeugen und sie mit
dem MinIO-Client in den Demo-Bucket kopieren. Sobald `d-migrate` `s3://`-
Artefaktziele unterstuetzt, wird dieser Zwischenschritt durch direkte Ausgabe
nach MinIO ersetzt.

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

# MinIO — Demo-only credentials, NOT for production
# Defaults absichtlich auf den MinIO-Bekanntwert minioadmin/minioadmin,
# damit die Copy-Paste-Befehle in §6 und im README ohne env-Resolver
# laufen. README warnt explizit, dass diese Werte vor jedem nicht-
# lokalen Lauf zu ersetzen sind.
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin
MINIO_API_PORT=59000
MINIO_CONSOLE_PORT=59001
MINIO_BUCKET=dmigrate-demo

# Metabase
METABASE_PORT=3000
```

Konvention: PostgreSQL-Passwort traegt den `change-me`-Suffix als
sichtbaren Marker. MinIO-Defaults sind bewusst
`minioadmin`/`minioadmin` — der MinIO-Bekanntwert — damit der
`mc-tools`-Service (§5.3) ohne Demo-spezifisches Lookup laeuft
und der Compose-Stack direkt mit dem aus dem Image bekannten
Default startet. Das ist **keine** Production-Konvention; README
dokumentiert das Risiko explizit. `.gitignore` muss `.env` (ohne
`.example`) ausschliessen.

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
    demo_pg: "postgresql://dmigrate:${POSTGRES_PASSWORD}@localhost:55432/dmigrate_demo"
```

Wenn die Demo aus dem Container-CLI-Pfad (§5.4) laeuft, ersetzt der
Compose-Service-Eintrag den Host-Teil durch
`postgresql://dmigrate:${POSTGRES_PASSWORD}@postgres:5432/dmigrate_demo`.
README dokumentiert beide Varianten.

---

## 6. Demo-Flow

Ein minimaler Demo-Ablauf:

```text
cp examples/bi-demo/.env.example examples/bi-demo/.env  # einmalig
set -a; source examples/bi-demo/.env; set +a
docker compose -f examples/bi-demo/docker-compose.yml up -d

export D_MIGRATE_CONFIG=examples/bi-demo/.d-migrate.yaml
d-migrate schema reverse --source demo_pg --output out/reverse.yaml
d-migrate data profile --source demo_pg --output out/profile.json
d-migrate schema generate --source out/reverse.yaml --target postgresql

docker compose -f examples/bi-demo/docker-compose.yml run --rm mc-tools \
    mc cp --recursive /work/ "local/${MINIO_BUCKET}/runs/manual/"
```

Hinweise:

- `examples/bi-demo/.d-migrate.yaml` liegt **nicht** im Repo-Root;
  ohne `D_MIGRATE_CONFIG` (oder `--config examples/bi-demo/.d-migrate.yaml`)
  findet der CLI-Resolver die Datei nicht — Fallback ist nur
  `./.d-migrate.yaml`
  ([`NamedConnectionResolver.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/NamedConnectionResolver.kt)
  Zeile 36).
- `minio-init` (siehe §5.3) erzeugt den Bucket beim Compose-Start;
  ad-hoc `mc`-Aufrufe laufen ueber den separaten `mc-tools`-Service
  (§5.3), der das `out/`-Verzeichnis als `/work` mountet. Damit ist
  `mc` **keine** Host-Voraussetzung.

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
- `orders` — ~500 Zeilen (verteilt ueber 90 Tage)
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
  `POSSIBLE_PLACEHOLDER_VALUES`-Warning.
- `products.category` als Low-Cardinality-Spalte (3-4 distinkte
  Werte ueber ~30 Zeilen) fuer `LOW_CARDINALITY`-Warning.
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
- direkte `s3://`-Ausgabe nach MinIO, sobald der Object-Storage-
  ArtifactStore umgesetzt ist
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

**Ziel**: `docker compose up -d` startet PostgreSQL, MinIO und den
`minio-init`-One-Shot stabil. Pro Service ein passender Erreichbarkeits-
Vertrag (siehe §5.3: MinIO hat **keinen** Server-Healthcheck).

**Akzeptanzkriterien**:

- [ ] `examples/bi-demo/docker-compose.yml` mit den vier Services
  aus §5.1 + §5.3 (Postgres, MinIO, minio-init, mc-tools) und
  gepinnten Image-Tags.
- [ ] `examples/bi-demo/.env.example` aus §5.5; `.env` ist via
  `examples/bi-demo/.gitignore` ausgeschlossen.
- [ ] Nach `docker compose up -d` (ohne Profile) gilt innerhalb
  von 90 s:
  - **Postgres**: `state=healthy` via `pg_isready`-Healthcheck
    aus §5.1.
  - **MinIO-Server**: keine Healthcheck-Bedingung. Erreichbarkeit
    wird **nicht** direkt am Container gemessen, sondern indirekt
    via `minio-init`.
  - **minio-init**: `state=exited (0)`, d. h.
    `service_completed_successfully` — Bucket-Init lief grun und
    der mc-Retry hat MinIO als nutzbar bestaetigt.
  - **mc-tools**: nicht gestartet (steht unter
    `profiles: ["tools"]` und wird nur durch
    `docker compose run mc-tools …` materialisiert).
- [ ] Smoke-Check fuer „MinIO ist nutzbar":
  `docker compose run --rm mc-tools mc ls local/` exited 0 und
  listet den Demo-Bucket.
- [ ] `docker compose down -v` raeumt Named-Volumes komplett ab
  (Idempotenz-Test).
- [ ] `make ci` grün (keine zusätzlichen Tests, aber Doc-Linting
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

- [ ] `sql/001_schema.sql` mit den 5 Tabellen aus §7 (inklusive
  Fremdschluessel + Datentypen-Mix + optional `jsonb`-Spalte).
- [ ] `sql/002_seed.sql` mit den Volumen-/Verteilungs-Vorgaben aus
  §7. Insbesondere die ~2 Outlier in `order_items.unit_price`
  und die ~5% `NULL`-Werte sind deterministisch reproduzierbar
  (fester Seed in PL/pgSQL-`setseed`).
- [ ] Mount-Punkt
  `examples/bi-demo/sql/:/docker-entrypoint-initdb.d/`-Mount in
  `docker-compose.yml`.
- [ ] Idempotenz: `docker compose down -v && docker compose up -d`
  produziert byte-identische Tabelleninhalte.
- [ ] `make ci` grün.

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

- [ ] `metabase`-Service aus §5.2 in `docker-compose.yml` (inkl.
  `start_period: 60s`-Healthcheck).
- [ ] Named-Volume `metabase-data` ueberlebt `down` (aber **nicht**
  `down -v` — siehe BD.1).
- [ ] README dokumentiert: Admin-User-Anlage, Datenquelle
  `demo_pg` einrichten (Host: `postgres`, Port: `5432`,
  Datenbank/User/Passwort aus `.env`).
- [ ] README dokumentiert mindestens drei Beispiel-Fragen
  (Umsatz/Tag, Bestellungen/Status, Top-Kunden) als Schritt-fuer-
  Schritt-Anleitung.
- [ ] `make ci` grün.

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

- [ ] `examples/bi-demo/.d-migrate.yaml` aus §5.6.
- [ ] `d-migrate schema reverse --source demo_pg --output out/reverse.yaml`
  liefert eine valide Reverse-Definition (alle 5 Tabellen, FKs,
  Datentypen).
- [ ] `d-migrate data profile --source demo_pg --output out/profile.json`
  liefert einen Profile-Report. BD.4 pinnt drei sichtbare
  Profiling-Signale entlang heutiger
  [`WarningCode`](../../../hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/types/WarningCode.kt):
  - `column.nullCount > 0` fuer `customers.middle_name` und
    `orders.notes` (~5% NULL aus §7 — keine Warning, aber im
    Report sichtbar).
  - `numericStats.max` >> `numericStats.avg` fuer
    `order_items.unit_price` (Outlier aus §7).
  - Mindestens drei Warning-Codes im Report:
    `CONTAINS_EMPTY_STRINGS` (auf `customers.email`),
    `POSSIBLE_PLACEHOLDER_VALUES` (auf `customers.middle_name`),
    `LOW_CARDINALITY` (auf `products.category`).
- [ ] `d-migrate schema generate --source out/reverse.yaml --target postgresql`
  rendert eine valide DDL.
- [ ] `docker compose run --rm mc-tools mc cp --recursive
  /work/ "local/${MINIO_BUCKET}/runs/manual/"` laed die Artefakte
  in MinIO (Smoke-Vertrag aus §5.3; `out/` ist im `mc-tools`-
  Service als `/work` gemountet). Host-`mc` ist nicht
  erforderlich.
- [ ] README dokumentiert Container-CLI-Variante fuer
  d-migrate als optional (mit `docker-compose.yml`-Service-
  Eintrag oder `docker run --rm --network bi-demo_default ...`).
- [ ] `make ci` grün.

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

- [ ] `examples/bi-demo/scripts/smoke.sh` mit `set -euo pipefail`,
  prueft Container-Health (`docker compose ps --format json |
  jq …`), faehrt mindestens den d-migrate-Reverse + Profile
  Workflow aus BD.4, prueft MinIO-Upload via
  `docker compose run --rm mc-tools mc ls "local/${MINIO_BUCKET}/runs/"`.
  **`mc` wird ausschliesslich ueber den `mc-tools`-Service
  (§5.3) aufgerufen**, damit die Demo ohne Host-`mc` laeuft; `jq`
  ist Host-Voraussetzung (siehe README-Prereqs).
- [ ] `examples/bi-demo/README.md` vollstaendig:
  - **Voraussetzungen (Host)**: Docker (≥ 24), Docker Compose
    (≥ v2.20), `jq` (fuer Smoke-Script-`ps`-Parsing), d-migrate-
    CLI. `mc` ist **keine** Host-Voraussetzung — der
    `mc-tools`-Service (§5.3) liefert ihn via
    `docker compose run`.
  - Start/Stop-Block (mit/ohne `-v`)
  - Metabase-Erstkonfiguration (Screenshot oder Schritt-fuer-Schritt)
  - d-migrate-Workflow aus BD.4 als Copy-Paste-Block
  - Cleanup-Block
  - Troubleshooting (Port-Konflikte, Healthcheck-Timeouts,
    Metabase-`start_period`, MinIO via
    `docker compose run --rm mc-tools mc …`)
- [ ] Optional: GitHub-Actions-Workflow `bi-demo-smoke.yml`, der
  `scripts/smoke.sh` ohne Metabase-Browser-Schritt im CI
  ausfuehrt (Best-Effort, kann anfangs als
  `continue-on-error: true` markiert sein).
- [ ] `make ci` grün; manueller Demo-Run dokumentiert in
  Commit-Message des BD.5-Slices.

**Betroffene Dateien**:

- Neu: `examples/bi-demo/scripts/smoke.sh`
- Update: `examples/bi-demo/README.md`
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
   PostgreSQL + MinIO + Metabase.
4. **PG-Version-Drift bei Seed-Determinismus**. `setseed()` +
   `random()` kann ueber PG-Major-Versionen leicht variieren.
   Mitigation: Image-Tag `postgres:17-alpine` pinnen; bei
   Major-Update Seed-Regeneration testen.
5. **Demo-Credentials in Produktion**. Mitigation: PostgreSQL-
   Default-Passwort traegt `change-me`-Suffix als sichtbaren
   Marker (§5.5). MinIO laeuft bewusst auf den MinIO-Default-
   Credentials `minioadmin`/`minioadmin`, damit `mc-tools` und
   Compose-Stack ohne Demo-spezifische Substitution starten —
   das ist eine Demo-Konvention, nicht ein versehentlicher
   `change-me`-Verlust. README warnt explizit fuer beide Faelle
   (PG-Passwort vor jedem nicht-lokalen Run ersetzen; MinIO-
   Default vor jedem nicht-lokalen Run ersetzen UND
   `mc-tools`-`MC_HOST_local` neu setzen). `.env` ist gitignored.
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

---

## 11. Dependencies zu anderen Plaenen

- **[`object-storage-artifact-store.md`](object-storage-artifact-store.md)**:
  bringt nativen `s3://`-Artifakt-Output. Solange noch nicht
  geliefert, faellt BD.4 auf den `mc cp`-Zwischenschritt zurueck
  (§5.3). Sobald geliefert, ist ein BD.6-Slice „Direkte
  s3://-Ausgabe nach MinIO" sinnvoll.
- **[`parquet-export-import-evaluation.md`](parquet-export-import-evaluation.md)**:
  Parquet-Artifakte sind in §8 als spaetere Erweiterung gelistet;
  ein BD.7-Slice „Parquet-Demo gegen MinIO" haengt direkt daran.
- **[`profiling-data-quality-export.md`](profiling-data-quality-export.md)**:
  Data-Quality-Export aus Profiling-Reports ist §8 — Sub-Slice
  „BD.8 Data-Quality-Dashboard in Metabase" haengt am Export-Format
  dieses Plans.
- **[`telemetry-observability-port.md`](telemetry-observability-port.md)**:
  Telemetrie ist nicht Teil der Basisdemo, aber ein zukuenftiger
  „BD-Telemetry"-Slice koennte OpenTelemetry-Collector als
  vierten Service zeigen.

Keine dieser Plaene ist **Voraussetzung** fuer BD.1-BD.5; alle sind
Erweiterungsoptionen fuer BD.6+.
