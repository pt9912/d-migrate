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
- `d-migrate` CLI (kommt mit BD.4)

`aws` und `mc` sind **keine** Host-Voraussetzung — der
`aws-tools`-Service kapselt die AWS-CLI samt
`--endpoint-url`-Wrapper.

## Start

```bash
cp examples/bi-demo/.env.example examples/bi-demo/.env
mkdir -p examples/bi-demo/out

docker compose -f examples/bi-demo/docker-compose.yml pull
docker compose -f examples/bi-demo/docker-compose.yml up -d
```

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

## Stoppen / Aufraeumen

```bash
# Nur Container stoppen (Volumes bleiben — naechster `up -d`
# startet mit denselben Daten + Metabase-Konfig)
docker compose -f examples/bi-demo/docker-compose.yml down

# Komplett-Reset (verwirft Postgres-Daten, Metabase-Konfig,
# SeaweedFS-Bucket-Inhalt — alle Named Volumes)
docker compose -f examples/bi-demo/docker-compose.yml down -v
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

### Port belegt (55432, 59000, 59001, 3000)

In `.env` einen anderen Port setzen (z. B. `POSTGRES_PORT=55433`)
und `docker compose down && docker compose up -d` ausfuehren.

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
docker compose -f examples/bi-demo/docker-compose.yml down -v
docker compose -f examples/bi-demo/docker-compose.yml up -d
```

## Slice-Status

- BD.1 (Compose-Skeleton + Healthchecks) — done
- BD.2 (Schema + deterministischer Seed) — done
- BD.3 (Metabase-Integration) — done
- BD.4 (d-migrate-Smoke) — pending
- BD.5 (Smoke-Script + Make-Targets + CI) — pending

Spec + Sub-Slice-Akzeptanzkriterien:
[`bi-demo-compose.md`](../../docs/planning/in-progress/bi-demo-compose.md)
