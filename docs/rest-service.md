# REST-Service-Spezifikation: d-migrate

> Dokumenttyp: Zielbild / Spezifikation
>
> Status: Entwurf fuer die kuenftige Service-Schnittstelle
>
> Referenzen: `docs/architecture.md`, `docs/cli-spec.md`,
> `docs/implementation-plan-0.9.1.md`, `docs/lastenheft-d-migrate.md` LF-022

---

## 1. Ziel

`d-migrate` besitzt heute eine CLI als driving adapter. Fuer eine
Microservice-Umgebung braucht das System zusaetzlich eine HTTP-basierte,
remote nutzbare Schnittstelle.

Das REST-Service-Zielbild ist:

- derselbe fachliche Kern wie in der CLI
- ein zusaetzlicher driving adapter unter `adapters/driving/rest`
- klare Trennung zwischen kurzen Request/Response-Aufrufen und langen Jobs
- stabile JSON-Vertraege fuer andere Dienste, UIs und Automatisierung
- dieselben fachlichen Exit-/Fehlercodes wie in der CLI, aber in HTTP uebersetzt

Die REST-API ist kein zweites Produkt neben der CLI, sondern ein weiterer
Einstiegspunkt auf dieselben Use Cases.

---

## 2. Architekturposition

Geplante Einordnung in die bestehende Hexagon-Struktur:

```text
Client / API Gateway
        |
        v
adapters/driving/rest
        |
        v
hexagon/application
        |
        +--> hexagon/ports-common
        +--> hexagon/ports-read
        +--> hexagon/ports-write
        |
        v
adapters/driven/*
```

Der REST-Adapter soll keine DDL-, JDBC- oder Formatlogik duplizieren. Er
mappt HTTP-Requests auf bestehende Runner-/Service-Aufrufe wie
`SchemaGenerateRunner`, `SchemaReverseRunner`, `DataExportRunner`,
`DataImportRunner`, `DataTransferRunner` und `DataProfileRunner`.

### 2.1 Vorbedingung fuer saubere Service-Adapter

Die Klassen in `hexagon/application` tragen heute noch CLI-nahe Namen
(`dev.dmigrate.cli.commands.*Runner`). Fuer REST, gRPC und MCP sollte die
Verdrahtung in eine API-neutrale Schicht gezogen werden:

- Request-/Response-Modelle ohne Clikt-Bezug
- gemeinsame Job-Orchestrierung fuer lange Laeufe
- gemeinsame Fehlerabbildung
- wiederverwendbare Auth-/Audit-Hooks

Das ist kein Blocker fuer einen ersten Adapter, aber die technische Schuld
wuerde sonst in alle Service-Schnittstellen kopiert.

---

## 3. API-Grundsaetze

### 3.1 Versionsschema

- Basis-Pfad: `/api/v1`
- nur additive Erweiterungen innerhalb von `v1`
- breaking changes nur ueber `/api/v2`

### 3.2 Formate

- Request und Response standardmaessig `application/json`
- grosse Artefakte optional als Download (`application/sql`, `application/yaml`,
  `application/octet-stream`)
- strukturierte Antworten bleiben sprachstabil englisch, analog zur CLI
- menschenlesbare Texte koennen per `Accept-Language` lokalisiert werden
  (LF-022)

### 3.3 Synchron vs. asynchron

Kurze Operationen duerfen synchron sein:

- Schema validieren
- kleinere Schema-Diffs
- kleine DDL-Generierung
- Health- und Capability-Abfragen

Fuer `v1` gilt folgende feste Zuordnung:

- `GET /api/v1/health` -> synchron
- `GET /api/v1/capabilities` -> synchron
- `POST /api/v1/schema/validate` -> synchron
- `POST /api/v1/schema/generate` -> synchron (kleine DDL-Generierung)
- `POST /api/v1/schema/compare` -> synchron

Die folgenden Endpunkte sind asynchron als Job abzubilden:

- `schema reverse`
- `data export`
- `data import`
- `data transfer`
- `data profile`
- Tool-Exports (`flyway`, `liquibase`, `django`, `knex`)

Einzelne Endpunkte:

- `POST /api/v1/schema/reverse` -> asynchron
- `POST /api/v1/data/export` -> asynchron
- `POST /api/v1/data/import` -> asynchron
- `POST /api/v1/data/transfer` -> asynchron
- `POST /api/v1/data/profile` -> asynchron
- `POST /api/v1/export/flyway` -> asynchron
- `POST /api/v1/export/liquibase` -> asynchron
- `POST /api/v1/export/django` -> asynchron
- `POST /api/v1/export/knex` -> asynchron

### 3.4 Idempotenz

- lesende Endpunkte: `GET`
- rein berechnende Operationen: `POST`
- lange Laeufe erzeugen Job-Ressourcen
- asynchrone Job-Start-Endpunkte (z. B. `schema reverse`, `data export`, `data import`, `data transfer`, `data profile`, Tool-Exports) **müssen** über `Idempotency-Key` abgesichert werden
- identische Requests mit demselben Idempotency-Key sollen denselben `jobId` zurueckgeben
- abweichende Requests mit demselben Idempotency-Key sollen als Konflikt (`409`) zurueckgewiesen werden
- fehlender `Idempotency-Key` bei asynchronen Start-Endpunkten gilt als Client-Fehler

### 3.5 Gemeinsamer Kernvertrag fuer Jobs und Artefakte

REST, gRPC und MCP muessen denselben fachlichen Kernvertrag nutzen:

- `jobId` und `artifactId` sind opake, stabile String-IDs
- Jobs und Artefakte tragen immer `createdAt` und `expiresAt`
- `expiresAt` wird serverseitig festgelegt und ist fuer die Lebensdauer der
  Ressource unveraenderlich
- Artefakte sind nach Erzeugung immutable
- nur derselbe Mandant / Principal oder ein Administrator darf Job und
  Artefakt lesen, abbrechen oder herunterladen
- Terminalstatus eines Jobs aendert sich nach Erreichen von `succeeded`,
  `failed` oder `cancelled` nicht mehr
- grosse Ergebnisse werden nicht inline in den Job geschrieben, sondern als
  Artefakt referenziert

Empfohlene ID-Praefixe:

- `job_<opaque>`
- `artifact_<opaque>`

---

## 4. Ressourcenmodell

### 4.1 Basis-Endpunkte

| Pfad | Methode | Zweck |
| --- | --- | --- |
| `/api/v1/health` | `GET` | kombinierter Liveness-/Readiness-Status |
| `/api/v1/capabilities` | `GET` | unterstuetzte Dialekte, Formate, Features |
| `/api/v1/jobs/{jobId}` | `GET` | Status, Progress, Ergebnis-Metadaten |
| `/api/v1/jobs/{jobId}/cancel` | `POST` | laufenden Job abbrechen |
| `/api/v1/artifacts` | `POST` | Eingabe-Artefakt hochladen oder registrieren |
| `/api/v1/artifacts/{artifactId}` | `GET` | Ergebnisdatei oder Report herunterladen |
| `/api/v1/artifacts/{artifactId}/metadata` | `GET` | Metadaten zu Groesse, Typ, Hash, Ablaufzeit |

### 4.2 Schema-Endpunkte

| Pfad | Methode | Zweck | Modus |
| --- | --- | --- | --- |
| `/api/v1/schema/validate` | `POST` | neutrales Schema validieren | synchron |
| `/api/v1/schema/generate` | `POST` | DDL fuer Ziel-Dialekt erzeugen | synchron |
| `/api/v1/schema/reverse` | `POST` | DB nach neutralem Modell reverse-engineeren | asynchron |
| `/api/v1/schema/compare` | `POST` | zwei Schemata oder Umgebungen vergleichen | synchron |

### 4.3 Daten-Endpunkte

| Pfad | Methode | Zweck | Modus |
| --- | --- | --- | --- |
| `/api/v1/data/export` | `POST` | Datenexport als Job starten | asynchron |
| `/api/v1/data/import` | `POST` | Datenimport als Job starten | asynchron |
| `/api/v1/data/transfer` | `POST` | DB-zu-DB-Transfer als Job starten | asynchron |
| `/api/v1/data/profile` | `POST` | Profiling als Job starten | asynchron |

### 4.4 Integrations-Endpunkte

| Pfad | Methode | Zweck | Modus |
| --- | --- | --- | --- |
| `/api/v1/export/flyway` | `POST` | Flyway-Bundle erzeugen | asynchron |
| `/api/v1/export/liquibase` | `POST` | Liquibase-Bundle erzeugen | asynchron |
| `/api/v1/export/django` | `POST` | Django-Migrationsartefakte erzeugen | asynchron |
| `/api/v1/export/knex` | `POST` | Knex-Migrationsartefakte erzeugen | asynchron |

---

## 5. Request-Modell

Die REST-API sollte die CLI nicht 1:1 als Flag-Surface kopieren. Sie sollte
fachliche Requests transportieren.

Beispiel `schema generate`:

```json
{
  "targetDialect": "postgresql",
  "schema": {
    "...": "..."
  },
  "options": {
    "generateRollback": true,
    "spatialProfile": "postgis",
    "outputMode": "inline"
  }
}
```

Beispiel `data export`:

```json
{
  "source": {
    "connectionRef": "staging"
  },
  "tables": ["public.users", "public.orders"],
  "format": "json",
  "options": {
    "splitFiles": true,
    "incremental": {
      "sinceColumn": "updated_at",
      "since": "2026-04-01T00:00:00Z"
    }
  }
}
```

### 5.1 Verbindungsmodell

Fuer Service-Betrieb sind drei Modi sinnvoll:

- `connectionRef`: referenziert serverseitig bekannte Verbindung
- `connectionSecretRef`: referenziert Secret-Store-Eintrag
- `jdbcUrl` nur fuer trusted admin deployments

Direkte Credentials im Request sollten nicht Standard sein.

### 5.2 Filter-Modell

Der heutige CLI-Pfad `--filter` ist eine Trust-Boundary. Fuer REST gilt das
nicht. Deshalb soll die REST-API standardmaessig **keine rohe WHERE-Klausel**
akzeptieren.

Ziel fuer `v1`:

- strukturierter Filter-DSL oder
- serverseitig validierte, eingeschraenkte Ausdruckssprache

Ein "trusted raw sql" Modus darf hoechstens explizit konfigurierbar sein und
muss standardmaessig deaktiviert bleiben.

### 5.3 Import- und Export-Artefakte

Fuer grosse Ein- und Ausgabedaten gilt ein einheitliches Modell:

- `data import` akzeptiert fuer `v1` nur serverseitig bekannte Eingaben ueber
  `sourceArtifactId`
- Eingabe-Artefakte werden zuerst ueber `POST /api/v1/artifacts` angelegt
- `data export` liefert standardmaessig ein oder mehrere Ergebnis-Artefakte
- kleine Ergebnisse duerfen inline zurueckkommen, grosse nur per Artefakt

#### 5.3.1 `POST /api/v1/artifacts` - Upload- und Registrierungsmodus

Der Endpoint ist mit zwei exklusiven Modi definiert:

- `mode: "upload"` (Standard, Multipart-Upload):
  - `Content-Type: multipart/form-data`
  - Pflichtfeld: `file` (binaeres Artefakt)
  - optionales Feld: `metadata` (JSON), z. B. `{"contentType":"application/json","sha256":"...","sizeBytes":123456}`
- `mode: "register"`:
  - `Content-Type: application/json`
  - Pflichtfelder: `mode`, `contentType`, `connectionRef`, `path`
  - optional: `sha256`, `sizeBytes`
  - `connectionRef` darf nur auf serverseitig freigegebene Artefakt-Speicher verweisen
  - `connectionRef` wird auf Tenant und auf Rechte des aktiven Principals geprüft
  - `path` muss relativ, normalisiert und unter einem erlaubten Prefix des Speichers liegen (keine absoluten Pfade, kein Traversal)
  - serverseitige Verifikation von Dateiexistenz, Lesbarkeit und optionaler Prüfsumme vor der Registration
  - für `register` kann serverseitig konfiguriert werden, ob `sha256`/`sizeBytes` verpflichtend sind

Beispiel-Request fuer `mode: "register"`:

```json
{
  "mode": "register",
  "contentType": "application/sql",
  "connectionRef": "artifact-store",
  "path": "tenantA/uploads/schema.sql",
  "sha256": "5f3c...",
  "sizeBytes": 12345
}
```

Fehlerhafte Requests:

- unbekannter Modus: `400`
- ungueltiger oder unautorisierter Registrierungs-Pfad: `403`
- zu grosse Payload: `413`

Beispiel `data import`:

```json
{
  "target": {
    "connectionRef": "target-prod"
  },
  "sourceArtifactId": "artifact_01JS8M0Z9B6F2A1C4D5E6F7G8H",
  "format": "json",
  "options": {
    "onConflict": "update"
  }
}
```

Beispiel Antwort auf `POST /api/v1/artifacts`:

```json
{
  "artifactId": "artifact_01JS8M0Z9B6F2A1C4D5E6F7G8H",
  "contentType": "application/json",
  "sizeBytes": 182734,
  "sha256": "5f3c...",
  "createdAt": "2026-04-19T10:00:00Z",
  "expiresAt": "2026-04-26T10:00:00Z"
}
```

---

## 6. Job-Modell

Lange Laeufe werden als Job-Ressourcen abgebildet.

Beispiel:

```json
{
  "jobId": "job_01JS8J9B0K6R4W2A3Y7N8P1Q2R",
  "operation": "data.export",
  "status": "running",
  "progress": {
    "phase": "streaming",
    "tablesCompleted": 3,
    "tablesTotal": 12
  },
  "createdBy": "svc-platform-api",
  "createdAt": "2026-04-19T10:15:00Z",
  "expiresAt": "2026-04-26T10:15:00Z",
  "updatedAt": "2026-04-19T10:16:42Z",
  "artifacts": []
}
```

Statuswerte:

- `queued`
- `running`
- `succeeded`
- `failed`
- `cancelled`

Fuer `succeeded` und `failed` gilt:

- maschinenlesbare Ergebnisdaten im Body
- Artefakte ueber Download-Links
- keine ungebremsten Resultsets direkt im Status-Objekt

Pflichtfelder eines Jobs:

- `jobId`
- `operation`
- `status`
- `createdAt`
- `expiresAt`
- `createdBy`
- `artifacts`

Pflichtfelder eines Artefakts:

- `artifactId`
- `contentType`
- `sizeBytes`
- `sha256`
- `createdAt`
- `expiresAt`

Retention-Regeln:

- Jobs und Artefakte werden nach `expiresAt` serverseitig aufgeraeumt
- der konkrete Retention-Wert ist Deployment-Policy, muss aber immer ueber
  `expiresAt` sichtbar sein
- `GET /jobs/{jobId}` liefert auch fuer `failed` und `cancelled` weiterhin
  `200`, der Terminalstatus steht im Body

---

## 7. Fehlerabbildung

Die CLI-Exit-Codes sollen fachlich erhalten bleiben. Fuer synchrone REST-
Operationen ist die HTTP-Abbildung fest:

| CLI-Code | Bedeutung | HTTP |
| --- | --- | --- |
| `2` | Usage / Request invalid | `400` |
| `3` | Validation failed | `422` |
| `4` | Connection error | `503` |
| `5` | Migration / execution error | `500` |
| `6` | AI provider error | `503` |
| `7` | Local config / file / render error | `500` |
| `130` | cancelled | `410` |

`409` ist exklusiv fuer Idempotency-Konflikte mit demselben `Idempotency-Key` und abweichendem Request reserviert.

In asynchronen Workflows signalisiert ein laufender oder abgeschlossener Job den Statuswechsel (inkl. `cancelled`) über `GET /jobs/{jobId}`.

Zusatzliche HTTP-Fehler (nicht abgeleitet aus CLI-Codes):

| HTTP | Bedeutung | Wann |
| --- | --- | --- |
| `428` | Precondition Required | fehlender Idempotency-Key bei asynchroner Job-Anlage |
| `401` | Unauthorized | fehlende oder ungueltige Authentifizierung |
| `403` | Forbidden | fehlende Berechtigung auf Betrieb, Verbindung oder Objekt |
| `404` | Not Found | unbekannter `jobId` oder `artifactId` |
| `409` | Conflict | Konflikt durch Idempotency-Key bei abweichendem Request |
| `413` | Payload Too Large | Artefakt oder Request-Body zu gross |
| `429` | Too Many Requests | Rate-/Concurrency-Limits erreicht |

Fuer asynchrone Operationen gilt:

- `POST` zum Start eines Jobs liefert bei erfolgreicher Annahme `202 Accepted`
- `Idempotency-Key` ist für alle Job-Start-Endpunkte **erforderlich**; fehlt er, ist der Start mit `428` abzulehnen.
- Die Antwort MUSS einen `Location`-Header auf die Job-Ressource enthalten (`/api/v1/jobs/{jobId}`)
- Die Antwort muss mindestens folgendes JSON liefern:
  ```json
  {
    "jobId": "job_01JS8J9B0K6R4W2A3Y7N8P1Q2R",
    "statusUrl": "/api/v1/jobs/job_01JS8J9B0K6R4W2A3Y7N8P1Q2R",
    "status": "queued",
    "createdAt": "2026-04-19T10:15:00Z",
    "expiresAt": "2026-04-26T10:15:00Z"
  }
  ```
- Fehler beim Starten des Jobs nutzen die obige Tabelle
- spaetere Laufzeitfehler werden nicht als HTTP-Fehler des Start-Requests
  signalisiert, sondern ueber den Jobstatus `failed`
- ein abgebrochener Job wird ueber `GET /jobs/{jobId}` mit `200` und
  `status=cancelled` geliefert

Beispiel:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "exitCode": 3,
    "message": "Schema validation failed",
    "details": [
      {
        "object": "orders.customer_id",
        "level": "error",
        "code": "E002",
        "message": "Foreign key references non-existent table 'clients'"
      }
    ]
  }
}
```

---

## 8. Security

Pflicht fuer einen produktiven REST-Service:

- TLS immer aktiv; intern vorzugsweise mTLS
- Authentifizierung via OIDC/JWT oder Service-mTLS
- Autorisierung auf Operation, Verbindung und Zielsystem
- Secret-Aufloesung nur serverseitig
- Redaction fuer JDBC-URLs, Passwoerter, Tokens und Prompt-Inhalte
- Audit-Log fuer destruktive oder teure Operationen
- Rate-Limits und Concurrency-Limits pro Mandant / Client
- Payload-Groessenlimits

Besonders kritisch:

- kein offener Raw-SQL-Filter-Pfad
- kein frei beschreibbarer Dateisystempfad vom Client
- keine direkten JDBC-Credentials in Logs oder Job-Events

## 8.1 Health-Response (Details)

Der kombinierte Health-Endpunkt liefert getrennte Felder fuer Liveness und Readiness:

```json
{
  "status": "DEGRADED",
  "liveness": "UP",
  "readiness": "UP|DEGRADED|DOWN",
  "checks": {
    "storage": "UP",
    "db": "DOWN",
    "queue": "UP"
  }
}
```

HTTP-Status:

- `200`: `liveness=UP` und `readiness=UP`
- `503`: `liveness=UP` und `readiness!=UP`
- `503`: `liveness!=UP`

Der kombinierte Gesamtstatus wird wie folgt abgeleitet:

- `UP` bei `liveness=UP` und `readiness=UP`
- `DEGRADED` bei `liveness=UP` und `readiness=DEGRADED`
- `DOWN` bei `liveness=DOWN`

---

## 9. Observability

Der Service sollte von Anfang an folgende Signale liefern:

- strukturierte Logs mit `requestId`, `jobId`, `tenant`, `operation`
- Metriken fuer Dauer, Fehlerquote, Throughput, Queue-Laenge
- Tracing ueber HTTP-Eingang, Runner, Driver und Format-Adapter
- explizite Progress-Events fuer lange Jobs

---

## 10. Einfuehrungsreihenfolge

### Phase 1

- `health`
- `capabilities`
- `schema validate`
- `schema generate`
- `schema compare`

### Phase 2

- Job-System
- `schema reverse`
- `data profile`

### Phase 3

- `data export`
- `data import`
- `data transfer`
- Artefakt-Download

### Phase 4

- Tool-Exports
- KI-nahe Endpunkte nur falls REST fuer diesen Use Case wirklich noetig ist

---

## 11. Entscheidung

REST ist die richtige Schnittstelle fuer:

- externe Plattformen
- Web-UIs
- API-Gateways
- Mandantenfaehigkeit
- standardisierte Enterprise-Integration

REST sollte aber nicht die einzige neue Service-Schnittstelle sein. Fuer
interne, stark typisierte und streaming-lastige Kommunikation ist zusaetzlich
ein gRPC-Adapter sinnvoll. Fuer KI-Agenten ist MCP der passendere Adapter als
eine rohe REST-API.
