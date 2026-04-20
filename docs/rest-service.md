# REST-Service-Spezifikation: d-migrate

> Dokumenttyp: Zielbild / Spezifikation
>
> Status: Entwurf für die künftige Service-Schnittstelle
>
> Referenzen: `docs/architecture.md`, `docs/cli-spec.md`,
> `docs/grpc-service.md`, `docs/job-contract.md`,
> `docs/implementation-plan-0.9.1.md`,
> `docs/lastenheft-d-migrate.md` LF-022

---

## 1. Ziel

`d-migrate` besitzt heute eine CLI als driving adapter. Für eine
Microservice-Umgebung braucht das System zusätzlich eine HTTP-basierte,
remote nutzbare Schnittstelle.

Das REST-Service-Zielbild ist:

- derselbe fachliche Kern wie in der CLI
- ein zusätzlicher driving adapter unter `adapters/driving/rest`
- klare Trennung zwischen kurzen Request/Response-Aufrufen und langen Jobs
- stabile JSON-Verträge für andere Dienste, UIs und Automatisierung
- dieselben fachlichen Exit-/Fehlercodes wie in der CLI, aber in HTTP übersetzt

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

### 2.1 Vorbedingung für saubere Service-Adapter

Die Klassen in `hexagon/application` tragen heute noch CLI-nahe Namen
(`dev.dmigrate.cli.commands.*Runner`). Für REST, gRPC und MCP sollte die
Verdrahtung in eine API-neutrale Schicht gezogen werden:

- Request-/Response-Modelle ohne Clikt-Bezug
- gemeinsame Job-Orchestrierung für lange Läufe
- gemeinsame Fehlerabbildung
- wiederverwendbare Auth-/Audit-Hooks

Das ist kein Blocker für einen ersten Adapter, aber die technische Schuld
würde sonst in alle Service-Schnittstellen kopiert.

---

## 3. API-Grundsätze

### 3.1 Versionsschema

- Basis-Pfad: `/api/v1`
- nur additive Erweiterungen innerhalb von `v1`
- breaking changes nur über `/api/v2`

### 3.2 Formate

- Request und Response standardmäßig `application/json`
- große Artefakte optional als Download (`application/sql`, `application/yaml`,
  `application/octet-stream`)
- strukturierte Antworten bleiben sprachstabil englisch, analog zur CLI
- menschenlesbare Texte können per `Accept-Language` lokalisiert werden
  (LF-022)

### 3.3 Synchron vs. asynchron

Kurze Operationen dürfen synchron sein:

- Schema validieren
- kleinere Schema-Diffs
- kleine DDL-Generierung
- Health- und Capability-Abfragen

Die autoritative Zuordnung synchron/asynchron je Endpunkt steht in §4.2–4.4.
Hier die Zusammenfassung:

Synchrone Endpunkte:

- `GET /api/v1/health`
- `GET /api/v1/capabilities`
- `POST /api/v1/schema/validate`
- `POST /api/v1/schema/generate` — synchron; der Server MUSS ein
  konfigurierbares Limit für die Schema-Größe durchsetzen und bei
  Überschreitung mit `413` antworten

Asynchrone Endpunkte (Job-basiert):

- `POST /api/v1/schema/reverse`
- `POST /api/v1/schema/compare`
- `POST /api/v1/data/export`
- `POST /api/v1/data/import`
- `POST /api/v1/data/transfer`
- `POST /api/v1/data/profile`
- `POST /api/v1/export/flyway`
- `POST /api/v1/export/liquibase`
- `POST /api/v1/export/django`
- `POST /api/v1/export/knex`

Für Job-Fortschritt kann ein SSE-Endpunkt
(`GET /api/v1/jobs/{jobId}/events`, `Accept: text/event-stream`) angeboten
werden, um Polling-Last bei langlebigen Jobs zu reduzieren. Spezifikation
des SSE-Endpunkts (Event-Typen, Reconnect via `Last-Event-ID`) erfolgt in
Phase 2 zusammen mit dem Job-System. In `v1` ist Polling über
`GET /api/v1/jobs/{jobId}` der Standardweg.

### 3.4 Idempotenz

- lesende Endpunkte: `GET`
- rein berechnende Operationen: `POST`
- lange Läufe erzeugen Job-Ressourcen
- asynchrone Job-Start-Endpunkte (z. B. `schema reverse`, `schema compare`, `data export`, `data import`, `data transfer`, `data profile`, Tool-Exports) **müssen** über `Idempotency-Key` abgesichert werden
- identische Requests mit demselben Idempotency-Key sollen denselben `jobId` zurückgeben
- abweichende Requests mit demselben Idempotency-Key sollen als Konflikt (`409`) zurückgewiesen werden
- "abweichend" ist definiert als semantische Ungleichheit der normalisierten Request-Felder (Feldnamen, Werte, Typen); Reihenfolge von JSON-Keys und Whitespace sind irrelevant
- der Server muss Idempotency-Keys mindestens für die Lebensdauer des zugehörigen Jobs vorhalten; nach `expiresAt` des Jobs darf der Key verworfen werden
- fehlender `Idempotency-Key` bei asynchronen Start-Endpunkten gilt als Client-Fehler

### 3.5 Gemeinsamer Kernvertrag für Jobs und Artefakte

Der fachliche Kernvertrag für Jobs und Artefakte ist in
`docs/job-contract.md` definiert. REST, gRPC und MCP bilden diesen Vertrag
protokollspezifisch ab (z. B. Header vs. Metadaten, JSON vs. Protobuf);
die fachliche Semantik bleibt identisch.

REST-spezifische Ergänzungen:

- `expiresAt` wird serverseitig festgelegt und ist für die Lebensdauer der
  Ressource unveränderlich; eine Verlängerung ist bewusst nicht vorgesehen,
  um die Aufräumlogik einfach und vorhersagbar zu halten
- große Ergebnisse werden nicht inline in den Job geschrieben, sondern als
  Artefakt referenziert

Empfohlene ID-Präfixe:

- `job_<opaque>`
- `artifact_<opaque>`

---

## 4. Ressourcenmodell

### 4.1 Basis-Endpunkte

| Pfad | Methode | Zweck |
| --- | --- | --- |
| `/api/v1/health` | `GET` | kombinierter Liveness-/Readiness-Status |
| `/api/v1/capabilities` | `GET` | unterstützte Dialekte, Formate, Features |
| `/api/v1/jobs` | `GET` | paginierte Job-Liste für den eigenen Tenant |
| `/api/v1/jobs/{jobId}` | `GET` | Status, Progress, Ergebnis-Metadaten |
| `/api/v1/jobs/{jobId}/cancel` | `POST` | laufenden Job abbrechen |
| `/api/v1/artifacts` | `GET` | paginierte Artefakt-Liste für den eigenen Tenant |
| `/api/v1/artifacts` | `POST` | Eingabe-Artefakt hochladen oder registrieren |
| `/api/v1/artifacts/{artifactId}` | `GET` | Artefakt-Inhalt im Originalformat herunterladen; unterstützt `ETag` / `If-None-Match` |
| `/api/v1/artifacts/{artifactId}/metadata` | `GET` | Metadaten inkl. Größe, Typ, Hash, Ablaufzeit und `ETag` |

Paginierte List-Endpunkte (`GET /api/v1/jobs`, `GET /api/v1/artifacts`)
unterstützen die Query-Parameter `pageToken`, `pageSize` und optionale
Filter (z. B. nach `status` oder `operation`). Die Default-Sortierung ist
`createdAt` absteigend.

`POST /api/v1/jobs/{jobId}/cancel` liefert den aktualisierten Job-Status als
Response-Body. Ist der Job bereits in einem Terminalstatus, antwortet der
Server idempotent mit `200` und dem unveränderten Status. Der Abbruch ist
best-effort: zwischen Request und Verarbeitung kann der Job in einen
Terminalstatus wechseln; auch dann antwortet der Server mit `200`.

#### Health-Response

Der kombinierte Health-Endpunkt liefert getrennte Felder für Liveness und Readiness:

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

#### Capabilities-Response

```json
{
  "dialects": ["postgresql", "mysql", "sqlite", "oracle", "sqlserver"],
  "formats": ["json", "csv", "sql", "yaml"],
  "features": {
    "spatialProfiles": ["postgis", "spatialite"],
    "rollbackGeneration": true,
    "incrementalExport": true,
    "dataProfile": true
  },
  "toolExports": ["flyway", "liquibase", "django", "knex"]
}
```

### 4.2 Schema-Endpunkte

| Pfad | Methode | Zweck | Modus |
| --- | --- | --- | --- |
| `/api/v1/schema/validate` | `POST` | neutrales Schema validieren | synchron |
| `/api/v1/schema/generate` | `POST` | DDL für Ziel-Dialekt erzeugen | synchron |
| `/api/v1/schema/reverse` | `POST` | DB nach neutralem Modell reverse-engineeren | asynchron |
| `/api/v1/schema/compare` | `POST` | zwei Schemata oder Umgebungen vergleichen | asynchron |

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

Beispiel `schema compare`:

```json
{
  "left": {
    "type": "schema",
    "artifactId": "artifact_01JS8LXYZ..."
  },
  "right": {
    "type": "connection",
    "connectionRef": "staging"
  },
  "options": {
    "ignoreComments": true,
    "ignoreOrder": true
  }
}
```

`left` und `right` unterstützen jeweils die Typen `schema` (mit
`artifactId`), `connection` (mit `connectionRef`) und `inline` (mit
eingebettetem Schema-Objekt).

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

Für Service-Betrieb sind drei Modi sinnvoll:

- `connectionRef`: referenziert serverseitig bekannte Verbindung
- `connectionSecretRef`: referenziert Secret-Store-Eintrag
- `jdbcUrl` nur für trusted admin deployments

Direkte Credentials im Request sollten nicht Standard sein.

### 5.2 Filter-Modell

Der heutige CLI-Pfad `--filter` ist eine Trust-Boundary. Für REST gilt das
nicht. Deshalb soll die REST-API standardmäßig **keine rohe WHERE-Klausel**
akzeptieren.

Ziel für `v1`:

- strukturierter Filter-DSL oder
- serverseitig validierte, eingeschränkte Ausdruckssprache

Ein "trusted raw sql" Modus darf höchstens explizit konfigurierbar sein und
muss standardmäßig deaktiviert bleiben.

### 5.3 Import- und Export-Artefakte

Für große Ein- und Ausgabedaten gilt ein einheitliches Modell:

- `data import` akzeptiert für `v1` nur serverseitig bekannte Eingaben über
  `sourceArtifactId`
- Eingabe-Artefakte werden zuerst über `POST /api/v1/artifacts` angelegt
- `data export` liefert standardmäßig ein oder mehrere Ergebnis-Artefakte
- kleine Ergebnisse dürfen inline zurückkommen, große nur per Artefakt

#### 5.3.1 `POST /api/v1/artifacts` - Upload- und Registrierungsmodus

Der Endpoint ist mit zwei exklusiven Modi definiert:

- `mode: "upload"` (Standard, Multipart-Upload):
  - `Content-Type: multipart/form-data`
  - Pflichtfeld: `file` (binäres Artefakt)
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

Beispiel-Request für `mode: "register"`:

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
- ungültiger oder unautorisierter Registrierungs-Pfad: `403`
- zu große Payload: `413`

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
  "filename": "upload.json",
  "contentType": "application/json",
  "sizeBytes": 182734,
  "sha256": "5f3c...",
  "createdAt": "2026-04-19T10:00:00Z",
  "expiresAt": "2026-04-26T10:00:00Z"
}
```

---

## 6. Job-Modell

Lange Läufe werden als Job-Ressourcen abgebildet.

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

Für `succeeded` und `failed` gilt:

- maschinenlesbare Ergebnisdaten im Body
- Artefakte über Download-Links
- keine ungebremsten Resultsets direkt im Status-Objekt

Pflichtfelder eines Jobs:

- `jobId`
- `operation`
- `status`
- `createdAt`
- `updatedAt`
- `expiresAt`
- `createdBy`
- `artifacts`

Das `progress`-Objekt ist optional und operationsspezifisch. Gemeinsame
Felder sind `phase` (String) und optional numerische Fortschrittswerte
(z. B. `tablesCompleted`/`tablesTotal`, `rowsProcessed`). Der Server darf
zusätzliche operationsspezifische Felder liefern.

Pflichtfelder eines Artefakts:

- `artifactId`
- `filename`
- `contentType`
- `sizeBytes`
- `sha256`
- `createdAt`
- `expiresAt`

Retention-Regeln:

- Jobs und Artefakte werden nach `expiresAt` serverseitig aufgeräumt
- der konkrete Retention-Wert ist Deployment-Policy, muss aber immer über
  `expiresAt` sichtbar sein
- `GET /jobs/{jobId}` liefert auch für `failed` und `cancelled` weiterhin
  `200`, der Terminalstatus steht im Body

---

## 7. Fehlerabbildung

Die CLI-Exit-Codes sollen fachlich erhalten bleiben. Für synchrone REST-
Operationen ist die HTTP-Abbildung fest:

| CLI-Code | Bedeutung | HTTP |
| --- | --- | --- |
| `1` | Allgemeiner Fehler | `500` |
| `2` | Usage / Request invalid | `400` |
| `3` | Validation failed | `422` |
| `4` | Connection error | `503` |
| `5` | Migration / execution error | `500` |
| `6` | AI provider error | `503` |
| `7` | Local config / file / render error | `500` |
| `130` | cancelled | — |

`130` (cancelled) hat kein direktes HTTP-Mapping: bei Client-Abbruch
wird die Verbindung geschlossen, bei serverseitigem Abbruch (Shutdown,
Timeout) greift `503`. Für asynchrone Jobs wird Abbruch über
`status=cancelled` im Job-Body signalisiert.

`409` ist exklusiv für Idempotency-Konflikte mit demselben `Idempotency-Key` und abweichendem Request reserviert.

In asynchronen Workflows signalisiert ein laufender oder abgeschlossener Job den Statuswechsel (inkl. `cancelled`) über `GET /jobs/{jobId}`.

Zusätzliche HTTP-Fehler (nicht abgeleitet aus CLI-Codes):

| HTTP | Bedeutung | Wann |
| --- | --- | --- |
| `400` | Bad Request | fehlender Idempotency-Key bei asynchroner Job-Anlage |
| `401` | Unauthorized | fehlende oder ungültige Authentifizierung |
| `403` | Forbidden | fehlende Berechtigung auf Betrieb, Verbindung oder Objekt |
| `404` | Not Found | unbekannter `jobId` oder `artifactId` |
| `409` | Conflict | Konflikt durch Idempotency-Key bei abweichendem Request |
| `413` | Payload Too Large | Artefakt oder Request-Body zu groß |
| `429` | Too Many Requests | Rate-/Concurrency-Limits erreicht |

Bei `429` und `503` MUSS der Server einen `Retry-After`-Header setzen.

Für asynchrone Operationen gilt:

- `POST` zum Start eines Jobs liefert bei erfolgreicher Annahme `202 Accepted`
- `Idempotency-Key` ist für alle Job-Start-Endpunkte **erforderlich**; fehlt er, ist der Start mit `400` abzulehnen.
- Die Antwort MUSS einen `Location`-Header auf die Job-Ressource enthalten (`/api/v1/jobs/{jobId}`)
- Die Antwort muss mindestens folgendes JSON liefern:
  ```json
  {
    "jobId": "job_01JS8J9B0K6R4W2A3Y7N8P1Q2R",
    "statusUrl": "/api/v1/jobs/job_01JS8J9B0K6R4W2A3Y7N8P1Q2R",
    "status": "queued",
    "createdBy": "svc-platform-api",
    "createdAt": "2026-04-19T10:15:00Z",
    "expiresAt": "2026-04-26T10:15:00Z"
  }
  ```
- Fehler beim Starten des Jobs nutzen die obige Tabelle
- spätere Laufzeitfehler werden nicht als HTTP-Fehler des Start-Requests
  signalisiert, sondern über den Jobstatus `failed`
- ein abgebrochener Job wird über `GET /jobs/{jobId}` mit `200` und
  `status=cancelled` geliefert

Fehler, die nicht aus CLI-Exit-Codes abgeleitet sind (z. B. `401`, `403`,
`404`, `413`, `429`), enthalten kein `exitCode`-Feld im Error-Body.

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

Pflicht für einen produktiven REST-Service:

- TLS immer aktiv; intern vorzugsweise mTLS
- Authentifizierung via OIDC/JWT oder Service-mTLS
- Autorisierung auf Operation, Verbindung und Zielsystem
- Secret-Auflösung nur serverseitig
- Redaction für JDBC-URLs, Passwörter, Tokens und Prompt-Inhalte
- Audit-Log für destruktive oder teure Operationen
- Rate-Limits und Concurrency-Limits pro Mandant / Client
- Payload-Größenlimits
- CORS-Policy für Browser-Clients:
  - Preflight-Requests (`OPTIONS`) werden unterstützt
  - `Access-Control-Allow-Origin` nur für explizit zugelassene Origins
  - erlaubte Methoden: `GET`, `POST`, `OPTIONS`
  - erlaubte Header: `Authorization`, `Content-Type`, `Idempotency-Key`,
    `Accept-Language`, `If-None-Match`
  - `Access-Control-Allow-Credentials: true` nur bei Cookie-basierter Auth
  - `Access-Control-Max-Age` auf mindestens 1 Stunde

Besonders kritisch:

- kein offener Raw-SQL-Filter-Pfad
- kein frei beschreibbarer Dateisystempfad vom Client
- keine direkten JDBC-Credentials in Logs oder Job-Events

---

## 9. Observability

Der Service sollte von Anfang an folgende Signale liefern:

- strukturierte Logs mit `requestId`, `jobId`, `tenant`, `operation`
- Metriken für Dauer, Fehlerquote, Throughput, Queue-Länge
- Tracing über HTTP-Eingang, Runner, Driver und Format-Adapter
- explizite Progress-Events für lange Jobs

---

## 10. Einführungsreihenfolge

### Phase 1 — Synchrone Basis

- `health`
- `capabilities`
- `schema validate`
- `schema generate`

### Phase 2 — Job-System und Analyse

- Job-System (inkl. SSE-Endpunkt `jobs/{jobId}/events`)
- `schema reverse`
- `schema compare`
- `data profile`

Profiling wird vor Export/Import eingeführt, weil es ohne Schreibzugriff auf
die Ziel-DB auskommt und als erstes den asynchronen Job-Pfad validiert.

### Phase 3 — Datenbewegung

- `data export`
- `data import`
- `data transfer`
- Artefakt-Download

### Phase 4 — Integrationen

- Tool-Exports
- KI-nahe Endpunkte nur falls REST für diesen Use Case wirklich nötig ist

---

## 11. Entscheidung

REST ist die richtige Schnittstelle für:

- externe Plattformen
- Web-UIs
- API-Gateways
- Mandantenfähigkeit
- standardisierte Enterprise-Integration

REST sollte aber nicht die einzige neue Service-Schnittstelle sein. Für
interne, stark typisierte und streaming-lastige Kommunikation ist zusätzlich
ein gRPC-Adapter sinnvoll. Für KI-Agenten ist MCP der passendere Adapter als
eine rohe REST-API.
