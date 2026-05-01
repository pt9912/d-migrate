# Plan: BI-Demo-Umgebung mit Docker Compose

> Dokumenttyp: Demo- und Integrationsplan
>
> Status: Entwurf (2026-05-01)
>
> Referenzen: `docs/roadmap.md`, `docs/orchestrator-examples.md`,
> `docs/profiling-data-quality-export.md`,
> `docs/parquet-export-import-evaluation.md`,
> [The Evolution of Business Intelligence: From Monolithic to Composable Architecture](https://www.pracdata.io/p/the-evolution-of-business-intelligence-stack),
> [Open Source Data Engineering Landscape 2025](https://www.pracdata.io/p/open-source-data-engineering-landscape-2025)

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

Empfohlene Defaults:

- Datenbank: `dmigrate_demo`
- User: `dmigrate`
- Passwort: nur Demo-Secret, nicht produktiv
- Port: lokal `55432`, um Konflikte mit vorhandenen PostgreSQL-Instanzen zu
  reduzieren

### 5.2 Metabase

Metabase ist fuer die erste Demo geeigneter als Superset, weil es schnell
startet und wenig Initialkonfiguration braucht.

Empfohlene Defaults:

- Port: lokal `3000`
- eigener Container-Volume fuer Metabase-State
- manuelle Erstkonfiguration im Browser dokumentieren

Automatisches Dashboard-Provisioning kann spaeter folgen, wenn der
Demo-Datenbestand stabil ist.

### 5.3 MinIO / S3-kompatibler Object Storage

MinIO dient als lokaler S3-kompatibler Speicher fuer Demo-Artefakte. Der
Service macht die Zero-Disk-Richtung greifbar, ohne direkt eine echte
Cloud-Abhaengigkeit einzufuehren.

Empfohlene Defaults:

- API-Port: lokal `59000`
- Console-Port: lokal `59001`
- Bucket: `dmigrate-demo`
- Access Key / Secret Key: nur Demo-Credentials aus `.env.example`
- Prefix fuer Laeufe: `runs/<timestamp-or-operation-id>/`

Der erste Demo-Schnitt muss noch keine produktive S3-Integration in
`d-migrate` voraussetzen. Solange der Object-Storage-ArtifactStore noch nicht
implementiert ist, kann das Smoke-Script Artefakte lokal erzeugen und sie mit
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

---

## 6. Demo-Flow

Ein minimaler Demo-Ablauf:

```text
docker compose up -d
d-migrate schema reverse --source demo_pg --output out/reverse.yaml
d-migrate data profile --source demo_pg --output out/profile.json
d-migrate schema generate --source out/reverse.yaml --target postgresql
mc alias set local http://localhost:59000 minioadmin minioadmin
mc mb --ignore-existing local/dmigrate-demo
mc cp --recursive out/ local/dmigrate-demo/runs/manual/
```

Danach kann Metabase im Browser auf die Demo-DB zeigen und einfache Fragen
beantworten:

- Umsatz pro Tag
- Bestellungen pro Status
- Top-Kunden
- Datenqualitaetsauffaelligkeiten aus Profiling-Report gegen Dashboard-Werte

---

## 7. Beispieldaten

Der Demo-Datenbestand sollte klein, aber realistisch sein:

- `customers`
- `orders`
- `order_items`
- `products`
- optional `events` fuer Zeitreihen

Wichtig sind:

- Fremdschluessel
- unterschiedliche Datentypen
- einige NULL-Werte
- einige bewusst auffaellige Werte fuer Profiling-Warnungen
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

## 9. Akzeptanzkriterien

- `docker compose up -d` startet PostgreSQL, Metabase und MinIO stabil.
- PostgreSQL enthaelt ein nichttriviales Demo-Schema mit Seed-Daten.
- MinIO enthaelt einen Demo-Bucket fuer `d-migrate`-Artefakte.
- `d-migrate schema reverse` funktioniert gegen die Demo-DB.
- `d-migrate data profile` erzeugt einen Report gegen die Demo-DB.
- Die README erklaert Metabase-Zugriff, DB-Verbindungsdaten und MinIO-Zugriff.
- Ein Smoke-Script prueft Container-Health und mindestens ein `d-migrate`-
  Kommando sowie optional einen Artefakt-Upload nach MinIO.

---

## 10. Risiken

- Metabase-Provisioning kann je nach Version aufwendig werden; deshalb sollte
  die erste Iteration manuelle Browser-Konfiguration akzeptieren.
- Zu viele Services machen die Demo schwerfaellig. Superset, ClickHouse und
  DuckDB sollten erst nach der Basisdemo optional hinzukommen.
- Demo-Credentials fuer PostgreSQL und MinIO muessen klar als nicht-produktiv
  markiert werden.
