# Gemeinsamer Kernvertrag: Jobs und Artefakte

> Dokumenttyp: Spezifikation (protokollneutral)
>
> Status: Entwurf
>
> Konsumenten: `docs/rest-service.md`, `docs/grpc-service.md`,
> `docs/ki-mcp.md`

---

## 1. Zweck

Dieses Dokument definiert das fachliche Modell für Jobs und Artefakte, das
allen Service-Schnittstellen von `d-migrate` zugrunde liegt. REST, gRPC und
MCP bilden diesen Vertrag protokollspezifisch ab (z. B. JSON vs. Protobuf,
HTTP-Header vs. gRPC-Metadaten); die fachliche Semantik bleibt identisch.

Protokollspezifische Ergänzungen (z. B. HTTP-Statuscodes, gRPC-Streaming,
MCP-Resource-URIs) werden in den jeweiligen Spezifikationen definiert.

---

## 2. Job-Modell

Ein Job repräsentiert eine lang laufende Operation, die asynchron
ausgeführt wird.

### 2.1 Pflichtfelder

| Feld | Typ | Beschreibung |
| --- | --- | --- |
| `jobId` | String (opak) | Eindeutige, stabile ID des Jobs |
| `operation` | String | Fachliche Operation (z. B. `data.export`, `schema.reverse`) |
| `status` | Enum | Aktueller Status (siehe §2.2) |
| `createdAt` | Timestamp | Zeitpunkt der Job-Erstellung |
| `updatedAt` | Timestamp | Zeitpunkt der letzten Statusänderung |
| `expiresAt` | Timestamp | Ablaufzeitpunkt; danach darf der Server den Job aufräumen |
| `createdBy` | String | Principal oder Service-Account, der den Job ausgelöst hat |
| `artifacts` | Liste | Referenzen auf erzeugte Artefakte (kann leer sein) |

### 2.2 Statuswerte und Lebenszyklus

```text
queued ──> running ──> succeeded
                  ──> failed
           ──> cancelled
queued ──> cancelled
```

| Status | Bedeutung | Terminal |
| --- | --- | --- |
| `queued` | Job angenommen, wartet auf Ausführung | nein |
| `running` | Job wird ausgeführt | nein |
| `succeeded` | Job erfolgreich abgeschlossen | ja |
| `failed` | Job mit Fehler beendet | ja |
| `cancelled` | Job durch Client oder Server abgebrochen | ja |

Invarianten:

- Ein Terminalstatus (`succeeded`, `failed`, `cancelled`) ändert sich nach
  Erreichen nicht mehr
- Die Abfrage eines Jobs in Terminalstatus liefert weiterhin den Job
  (kein 404 / NOT_FOUND), der Status steht im Body
- Laufzeitfehler werden über `status=failed` signalisiert, nicht als
  Transportfehler des Start-Requests

### 2.3 Progress

Das `progress`-Objekt ist optional und operationsspezifisch.

Gemeinsame Felder:

| Feld | Typ | Beschreibung |
| --- | --- | --- |
| `phase` | String | Aktuelle Verarbeitungsphase (z. B. `streaming`, `validating`, `importing`) |

Zusätzliche numerische Fortschrittswerte sind operationsspezifisch erlaubt
(z. B. `tablesCompleted`/`tablesTotal`, `rowsProcessed`). Der Server darf
weitere Felder liefern; Clients müssen unbekannte Felder ignorieren.

---

## 3. Artefakt-Modell

Ein Artefakt ist ein Ergebnis- oder Eingabeobjekt, das mit einem Job
verknüpft ist oder unabhängig hochgeladen wurde.

### 3.1 Pflichtfelder

| Feld | Typ | Beschreibung |
| --- | --- | --- |
| `artifactId` | String (opak) | Eindeutige, stabile ID des Artefakts |
| `contentType` | String | MIME-Typ (z. B. `application/json`, `application/sql`) |
| `sizeBytes` | Integer | Größe in Bytes |
| `sha256` | String | SHA-256-Prüfsumme des Inhalts |
| `createdAt` | Timestamp | Zeitpunkt der Erzeugung |
| `expiresAt` | Timestamp | Ablaufzeitpunkt |

### 3.2 Immutabilität

- Artefakte sind nach Erzeugung inhaltlich unveränderlich
- Große Ergebnisse werden als Artefakt referenziert, nicht inline in den
  Job geschrieben
- Protokolle, die optimistisches Locking unterstützen (z. B. `ETag` in
  REST, `artifact_version` in gRPC), nutzen die Artefakt-ID und einen
  Versionstoken zur Konsistenzprüfung

---

## 4. ID-Konventionen

- IDs sind opake Strings; Clients dürfen keine Struktur annehmen
- Empfohlene Präfixe für Lesbarkeit in Logs und Debugging:
  - `job_<opaque>` (z. B. `job_01JS8J9B0K6R4W2A3Y7N8P1Q2R`)
  - `artifact_<opaque>` (z. B. `artifact_01JS8M0Z9B6F2A1C4D5E6F7G8H`)
- IDs sind stabil über die gesamte Lebensdauer der Ressource

---

## 5. Idempotenz

Alle asynchronen Job-Start-Operationen müssen über einen Idempotency-Key
abgesichert werden.

### 5.1 Regeln

- Der Client sendet bei jedem Job-Start einen Idempotency-Key mit
- Identischer Request mit demselben Key liefert denselben `jobId` zurück
- Abweichender Request mit demselben Key wird als Konflikt abgewiesen
- "Abweichend" ist definiert als semantische Ungleichheit der normalisierten
  Request-Felder; Reihenfolge und Whitespace sind irrelevant
- Fehlender Idempotency-Key bei asynchronen Start-Operationen ist ein
  Client-Fehler

### 5.2 Lebensdauer

- Der Server muss Idempotency-Keys mindestens für die Lebensdauer des
  zugehörigen Jobs vorhalten
- Nach `expiresAt` des Jobs darf der Key verworfen werden

### 5.3 Protokollspezifische Übertragung

| Protokoll | Mechanismus | Konflikt-Signal |
| --- | --- | --- |
| REST | `Idempotency-Key`-Header | `409 Conflict` (abweichend) / `428` (fehlend) |
| gRPC | `idempotency_key`-Feld im Request oder Metadaten | `ALREADY_EXISTS` (abweichend) |
| MCP | `idempotencyKey`-Feld im Tool-Input | `IDEMPOTENCY_CONFLICT` Error-Code |

---

## 6. Retention und Ablauf

- `expiresAt` wird serverseitig bei Erstellung festgelegt und ist für die
  Lebensdauer der Ressource unveränderlich
- Eine Verlängerung der Lebensdauer ist bewusst nicht vorgesehen, um die
  Aufräumlogik einfach und vorhersagbar zu halten
- Jobs und Artefakte werden nach `expiresAt` serverseitig aufgeräumt
- Der konkrete Retention-Wert ist Deployment-Policy, muss aber immer über
  `expiresAt` sichtbar sein

---

## 7. Zugriffskontrolle

- Nur derselbe Mandant / Principal oder ein Administrator darf Jobs und
  Artefakte lesen, abbrechen oder herunterladen
- Die Zugriffsprüfung erfolgt serverseitig; der Principal wird aus dem
  Authentifizierungskontext abgeleitet

---

## 8. Fehlermodell

### 8.1 Fachliche Fehlercodes

Die CLI-Exit-Codes bleiben als stabile, protokollübergreifende Fehler-IDs
erhalten:

| Code | Bedeutung |
| --- | --- |
| `1` | Allgemeiner Fehler |
| `2` | Usage / Request invalid |
| `3` | Validation failed |
| `4` | Connection error |
| `5` | Migration / execution error |
| `6` | AI provider error |
| `7` | Local config / file / render error |
| `130` | Cancelled |

### 8.2 Protokollspezifische Abbildung

| CLI-Code | REST | gRPC |
| --- | --- | --- |
| `1` | `500` | `INTERNAL` |
| `2` | `400` | `INVALID_ARGUMENT` |
| `3` | `422` | `INVALID_ARGUMENT` |
| `4` | `503` | `UNAVAILABLE` |
| `5` | `500` | `INTERNAL` |
| `6` | `503` | `INTERNAL` |
| `7` | `500` | `INTERNAL` |
| `130` | `503` | `CANCELLED` |

Fehler, die nicht aus CLI-Exit-Codes abgeleitet sind (z. B. Auth-Fehler,
Payload-Limits), werden protokollspezifisch in den jeweiligen
Spezifikationen definiert.

### 8.3 Fehler bei asynchronen Jobs

- Der Start-Request signalisiert nur Fehler bei der Job-Annahme
  (z. B. ungültige Parameter, fehlende Berechtigung)
- Laufzeitfehler werden über den Jobstatus `failed` kommuniziert, nicht als
  Transportfehler des Start-Requests

---

## 9. Protokollspezifische Abbildung — Übersicht

| Konzept | REST | gRPC | MCP |
| --- | --- | --- | --- |
| Feldnamen | camelCase (JSON) | snake_case (Protobuf) | camelCase (JSON) |
| Timestamps | ISO 8601 String | `google.protobuf.Timestamp` | ISO 8601 String |
| Job-Start-Antwort | `202 Accepted` + `Location`-Header | `OK` + `JobAccepted` | Tool-Result mit `jobId` |
| Job-Fortschritt | Polling `GET /jobs/{id}` + opt. SSE | `WatchJob`-Stream | Polling via `job_status`-Tool |
| Artefakt-Download | `GET /artifacts/{id}` + `ETag` | `DownloadArtifact` + `artifact_version` | `artifact_download`-Tool |
| Idempotency-Key | HTTP-Header | Request-Feld / Metadaten | Tool-Input-Feld |
| Fehlerstruktur | JSON `error`-Objekt mit `exitCode` | `google.rpc.Status` + `ErrorInfo` | MCP Error mit `code` |
