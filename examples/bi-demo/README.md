# d-migrate BI-Demo

Reproduzierbare Demo-Umgebung, die `d-migrate` in einen kleinen,
komponierbaren Analytics-Stack einbettet — PostgreSQL als
Datenquelle, SeaweedFS (S3-kompatibel) als Object-Storage,
Metabase als BI-Frontend.

Spec: [`docs/planning/in-progress/bi-demo-compose.md`](../../docs/planning/in-progress/bi-demo-compose.md)

## Voraussetzungen

Auf dem Host:

- Docker (≥ 24)
- Docker Compose (≥ v2.20; getestet gegen v5.1.4)
- `jq` (fuer Smoke-Script-Status-Parsing, kommt mit BD.5)

`aws`/`mc` sind **keine** Host-Voraussetzung — der
`aws-tools`-Service kapselt die AWS-CLI samt
`--endpoint-url`-Wrapper.

Fuer den `d-migrate`-Workflow (siehe unten) wird das lokale
Runtime-Image `d-migrate:dev` erwartet. Bauen aus dem
Repo-Root:

```bash
make docker-build IMAGE_TAG=dev
```

Das ist eine einmalige Investition pro Branch-Stand.

## Start

```bash
make bi-demo-pull   # einmalig pro Branch-Stand
make bi-demo-up     # legt .env aus .env.example an, falls noch nicht da
```

`make bi-demo-up` ist idempotent: bereits laufende Services
bleiben, fehlende werden gestartet, das `out/`-Verzeichnis wird
sichergestellt.

Nach ~60 Sekunden sind alle Services bereit:

| Service       | URL / Port                | Zweck                          |
| ------------- | ------------------------- | ------------------------------ |
| Postgres      | `127.0.0.1:55432`         | Demo-DB `dmigrate_demo`        |
| Metabase      | <http://127.0.0.1:3000>   | BI-Frontend                    |
| SeaweedFS S3  | `127.0.0.1:59000`         | S3-kompatibler Object-Storage  |
| SeaweedFS UI  | <http://127.0.0.1:59001>  | Cluster-Status (Debug)         |

Standard-Credentials kommen aus `.env.example` und sind nur fuer
lokale Demo-Nutzung gedacht.

## Metabase-Erstkonfiguration

Beim ersten Browser-Aufruf von <http://127.0.0.1:3000> fuehrt
Metabase durch einen Setup-Wizard:

### 1. Sprache + Admin-Account

- Sprache waehlen
- Admin-Account anlegen (E-Mail, Passwort frei waehlbar — Demo)

### 2. Datenquelle hinzufuegen

| Feld                | Wert                                       |
| ------------------- | ------------------------------------------ |
| Database type       | PostgreSQL                                 |
| Display name        | `demo_pg`                                  |
| Host                | `postgres`                                 |
| Port                | `5432`                                     |
| Database name       | `dmigrate_demo`                            |
| Username            | `dmigrate`                                 |
| Password            | (Wert aus `.env`: `POSTGRES_PASSWORD`)     |

**Wichtig**: `host: postgres` (Container-DNS), **nicht**
`localhost` — Metabase laeuft im Compose-Netz und erreicht
Postgres ueber den Servicenamen, nicht ueber den Host-Bind.

Metabase synct die Schemas einmalig (~10 Sekunden) und ist
dann bereit.

## Drei Beispiel-Fragen

Nach dem Schema-Sync hat Metabase die fuenf Tabellen aus dem
BI-Demo-Seed sichtbar: `customers`, `products`, `orders`,
`order_items`, `events`.

### Beispiel 1 — Umsatz pro Tag (paid-Orders)

1. Linke Navigation: **+ Neue Frage** → **Einfache Frage**
2. Datenquelle: `demo_pg` → Tabelle: `orders`
3. Filter: `status` = `paid`
4. Zusammenfassen: **Summe von** `total_amount`
5. Gruppieren nach: `created_at` → Zeitraster **Tag**
6. **Visualisieren** → Liniendiagramm

Erwartung: Linie ueber ~90 Tage mit ~50% der ~500 Bestellungen
(status-Mix aus dem Seed §7).

### Beispiel 2 — Bestellungen pro Status

1. **+ Neue Frage** → **Einfache Frage**
2. Datenquelle: `demo_pg` → Tabelle: `orders`
3. Zusammenfassen: **Anzahl Zeilen**
4. Gruppieren nach: `status`
5. **Visualisieren** → Tortendiagramm

Erwartung: vier Segmente (`pending` ~20%, `paid` ~50%,
`cancelled` ~20%, `refunded` ~10%) — Verteilung kommt aus dem
deterministischen Seed.

### Beispiel 3 — Top-Kunden nach Umsatz

1. **+ Neue Frage** → **Einfache Frage**
2. Datenquelle: `demo_pg` → Tabelle: `orders`
3. Filter: `status` = `paid`
4. Zusammenfassen: **Summe von** `total_amount`
5. Gruppieren nach: `customer_id`
6. Sortieren: Summe absteigend, Limit 10
7. **Visualisieren** → Balkendiagramm

Erwartung: Top-10-Kunden-IDs aus dem zyklischen
`customer_id`-Assignment im Seed.

## d-migrate-Workflow (Container-CLI)

Der `dmigrate`-Service kapselt das Runtime-Image und reicht
Subkommandos durch. Standard-Variante (Container-CLI, geht
ueber das Compose-Netz an `postgres:5432`):

```bash
# 1) Reverse-Engineer das Schema in eine YAML-Definition
docker compose -f examples/bi-demo/docker-compose.yml run --rm \
    dmigrate schema reverse \
    --source demo_pg_container \
    --output /work/out/reverse.yaml

# 2) Profile die Daten (Statistik + Quality-Warnings)
docker compose -f examples/bi-demo/docker-compose.yml run --rm \
    dmigrate data profile \
    --source demo_pg_container \
    --output /work/out/profile.json

# 3) Generiere PostgreSQL-DDL aus der Reverse-Definition
docker compose -f examples/bi-demo/docker-compose.yml run --rm \
    dmigrate schema generate \
    --source /work/out/reverse.yaml \
    --target postgresql \
    --output /work/out/generated.sql

# 4) Lade die Artefakte in den SeaweedFS-Bucket
docker compose -f examples/bi-demo/docker-compose.yml run --rm \
    aws-tools s3 cp --recursive /work/ \
    "s3://${S3_BUCKET}/runs/manual/"
```

Output landet auf dem Host unter `examples/bi-demo/out/` (Bind-
Mount des `dmigrate`-Service) und wird von `aws-tools` ueber das
gleiche Verzeichnis (dort als `/work/`) hochgeladen.

### Was zu erwarten ist

Nach Schritt 1-2 enthaelt `examples/bi-demo/out/`:

| Datei                   | Inhalt                                              |
| ----------------------- | --------------------------------------------------- |
| `reverse.yaml`          | YAML-Schema-Definition mit 5 Tabellen + FKs         |
| `reverse.report.yaml`   | Reverse-Run-Metadaten                               |
| `profile.json`          | Profil-Report (Stats + Warnings + Type-Compat)      |
| `generated.sql`         | 5 `CREATE TABLE`-Statements fuer PostgreSQL         |
| `generated.report.yaml` | Generate-Run-Metadaten                              |

Im Profil sind die fuenf Warning-Codes aus dem Seed sichtbar:

- `CONTAINS_EMPTY_STRINGS` (auf `customers.email`, ids 1-3)
- `CONTAINS_BLANK_STRINGS` (auf `orders.notes`, ~3% whitespace)
- `POSSIBLE_PLACEHOLDER_VALUES` (auf `customers.middle_name`,
  `'N/A'` und `'tbd'`)
- `LOW_CARDINALITY` (u. a. `products.category`, drei Werte)
- `DUPLICATE_VALUES` (mehrere Spalten mit Wiederholungen)

Plus `numericStats.max = 99999.99` auf `products.unit_price`
(id=15) und `order_items.unit_price` (id=42) als Outlier-Signal.

### Host-CLI-Variante (alternativ)

Wer `d-migrate` lokal installiert hat, kann den Workflow auch
host-seitig fahren:

```bash
set -a; source examples/bi-demo/.env; set +a
export D_MIGRATE_CONFIG=examples/bi-demo/.d-migrate.yaml

d-migrate schema reverse  --source demo_pg \
    --output examples/bi-demo/out/reverse.yaml
d-migrate data profile    --source demo_pg \
    --output examples/bi-demo/out/profile.json
d-migrate schema generate --source examples/bi-demo/out/reverse.yaml \
    --target postgresql \
    --output examples/bi-demo/out/generated.sql
```

`demo_pg` statt `demo_pg_container` — die Host-CLI geht ueber
den Compose-Port-Bind `127.0.0.1:${POSTGRES_PORT}` an Postgres.

## End-to-End-Smoke

```bash
make bi-demo-smoke
```

Faehrt pull → up → state-pinnung → d-migrate-Workflow (reverse +
profile + generate) → S3-Upload → Verifikation. Bei Erfolg endet
mit `[smoke] SUCCESS — stack is up`. Der Stack bleibt danach
**oben** — Cleanup explizit via `make bi-demo-down` oder
`make bi-demo-purge`.

Voraussetzung: das lokal gebaute `d-migrate:dev`-Image
(`make docker-build IMAGE_TAG=dev` einmalig). Host-`jq` wird im
Script fuer State-Pinnung verwendet.

## Stoppen / Aufraeumen

```bash
make bi-demo-down    # Container stoppen, Named Volumes bleiben
make bi-demo-purge   # Komplett-Reset (alle Named Volumes weg)
```

**Persistenz-Modell**:

| Operation        | Postgres-Daten | Metabase-Konfig | Bucket-Inhalt |
| ---------------- | -------------- | --------------- | ------------- |
| `down`           | bleibt         | bleibt          | bleibt        |
| `down -v`        | weg            | weg             | weg           |

Wenn nur Credentials in `.env` geaendert werden, reicht `down &&
up -d` — der `seaweed-config`-One-Shot rendert die `s3.json` neu
und der `seaweed`-Server liest sie beim Restart in den Memory.
`-v` ist nur fuer Komplett-Reset noetig.

## Troubleshooting

Die unter den Make-Targets liegenden `docker compose`-Kommandos
sind im naechsten Block aufgefuehrt — sie sind nur fuer Fehlersuche
und Sonderfaelle relevant.

### Port belegt (55432, 59000, 59001, 3000)

In `.env` einen anderen Port setzen (z. B. `POSTGRES_PORT=55433`)
und `make bi-demo-down && make bi-demo-up` ausfuehren.

### Metabase Health-Check schlaegt fehl

Der erste Start dauert bis zu 60 Sekunden (H2-DB-Init). Compose
ist mit `start_period: 60s` darauf eingestellt. Bei laengeren
Zeiten:

```bash
docker compose -f examples/bi-demo/docker-compose.yml logs metabase
```

### `aws-tools`-Aufruf ohne Bucket-Zugriff

Stack hochfahren wartet, bis `seaweed-init` exited(0) ist. Dann:

```bash
docker compose -f examples/bi-demo/docker-compose.yml run --rm \
    aws-tools s3 ls s3://dmigrate-demo/
```

Kein `aws`-Praefix noetig — der Service-Entrypoint stellt
`aws --endpoint-url http://seaweed:8333` automatisch voran.

### Seed-Daten anders als erwartet

Der Seed ist deterministisch via `setseed(0.42)` +
`\set demo_start_date '2026-01-01'`. Reset-Workflow:

```bash
make bi-demo-purge && make bi-demo-up
```

### Make-Targets entsprechen folgenden docker-Befehlen

| Make-Target         | docker compose-Aequivalent                                                            |
| ------------------- | ------------------------------------------------------------------------------------- |
| `bi-demo-pull`      | `docker compose -f examples/bi-demo/docker-compose.yml pull`                          |
| `bi-demo-up`        | `cp .env.example .env`, `mkdir -p out`, `docker compose ... up -d`                    |
| `bi-demo-down`      | `docker compose -f examples/bi-demo/docker-compose.yml down`                          |
| `bi-demo-purge`     | `docker compose -f examples/bi-demo/docker-compose.yml down -v`                       |
| `bi-demo-smoke`     | `./examples/bi-demo/scripts/smoke.sh` (End-to-End-Smoke, siehe BD.5)                  |

## Slice-Status

- BD.1 (Compose-Skeleton + Healthchecks) — done
- BD.2 (Schema + deterministischer Seed) — done
- BD.3 (Metabase-Integration) — done
- BD.4 (d-migrate-Smoke + `.d-migrate.yaml`) — done
- BD.5 (Smoke-Script + Make-Targets + CI) — done

Spec + Sub-Slice-Akzeptanzkriterien:
[`bi-demo-compose.md`](../../docs/planning/in-progress/bi-demo-compose.md)
