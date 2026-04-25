# MCP-Spezifikation fuer KI-Umgebungen: d-migrate

> Dokumenttyp: Zielbild / Spezifikation
>
> Status: Entwurf fuer die kuenftige KI-Schnittstelle
>
> Referenzen: `docs/architecture.md`, `docs/design.md`,
> `docs/job-contract.md`, `docs/lastenheft-d-migrate.md`,
> `docs/beispiel-stored-procedure-migration.md`

---

## 1. Ziel

Fuer KI-gestuetzte Arbeitsumgebungen braucht `d-migrate` keine generische
Remote-API allein, sondern eine agentenfreundliche, sichere und auditierbare
Werkzeugschnittstelle. Dafuer ist MCP besser geeignet als rohe REST- oder
gRPC-Aufrufe.

Das MCP-Zielbild ist:

- ein zusaetzlicher driving adapter unter `adapters/driving/mcp`
- Werkzeuge fuer klar umrissene `d-migrate`-Operationen
- Ressourcen fuer Schemas, Reports, Diffs und Job-Status
- sichere Defaults, damit ein LLM nicht versehentlich destruktive oder
  unkontrollierte Operationen startet
- volle Nachvollziehbarkeit fuer KI-gestuetzte Aktionen

---

## 2. Warum MCP

MCP passt besser zu KI-Clients als REST oder gRPC, weil es:

- Werkzeuge statt generischer Endpunkte anbietet
- Ressourcen fuer gezielte Kontextbereitstellung definiert
- Agenten erlaubt, Faehigkeiten zu entdecken
- lokale und entfernte Laufzeitmodelle unterstuetzt
- besser zu Chat-, IDE- und Copilot-artigen Oberflaechen passt

REST und gRPC bleiben wichtig, aber sie adressieren primaer klassische
Systemintegration. MCP adressiert den Arbeitsmodus "Agent analysiert,
fragt nach, fuehrt gezielte Migrationsschritte aus".

---

## 3. Architekturposition

```text
AI Client / IDE / Agent Runtime
              |
              v
     adapters/driving/mcp
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

Der MCP-Adapter soll keine eigene Fachlogik enthalten. Er kapselt:

- Tool-Definitionen
- Request-Mapping
- Ergebniszuschnitt fuer Agenten
- Sicherheits- und Audit-Regeln

### 3.1 Gemeinsamer Kernvertrag fuer Jobs und Artefakte

MCP nutzt denselben Kernvertrag wie REST und gRPC:

- `jobId` und `artifactId` sind opake, stabile String-IDs
- Jobs und Artefakte tragen immer `createdAt` und `expiresAt`
- Artefakte sind immutable
- nur derselbe Mandant / Principal oder ein Administrator darf Job und
  Artefakt lesen, abbrechen oder herunterladen
- Resource-URIs sind tenant-scope-gesteuert, der Zugriff wird per
  Principal/Token aufgeloest
- grosse Ergebnisse werden nicht inline in Tool-Responses gehalten, sondern
  ueber `resourceUri` oder `artifactId` referenziert
- Jobs und Artefakte werden nach `expiresAt` serverseitig aufgeraeumt

---

## 4. Zielbild des MCP-Servers

Der MCP-Server soll drei Dinge bereitstellen:

- `tools` fuer aktive Operationen
- `resources` fuer gezielten Read-Kontext
- `prompts` fuer kuratierte Analyse-, Transformations- und
  Testdatenablaeufe

### 4.1 Tool-Grundsaetze

Tools muessen:

- klein und eindeutig sein
- moeglichst idempotent oder read-only sein
- begrenzte und erklaerbare Resultate liefern
- keine ungefilterten Massendaten in den Chat kippen

Verbindliche Antwortgrenzen:

- ein Tool-Resultat darf hoechstens `64 KiB` serialisierte Nutzdaten inline
  liefern
- ein Tool-Resultat darf hoechstens `200` strukturierte Findings inline
  liefern
- row-basierte Export- oder Importdaten duerfen nie inline in eine Tool-
  Antwort geschrieben werden
- bei Ueberschreitung muss die Antwort auf Summary plus `resourceUri` und/oder
  `artifactId` umschalten

### 4.2 Ressourcen-Grundsaetze

Ressourcen sollen:

- stabile URIs haben
- auf Artefakte, Reports und Schemata zeigen
- grosse Inhalte bei Bedarf chunkbar oder referenzierbar machen

Fuer `resources/read` gilt dasselbe Prompt-Schutzprinzip wie fuer
Tool-Resultate:

- `resources/read` bleibt MCP-konform und nimmt nur `uri` entgegen
- pro Antwort maximal `64 KiB` serialisierte Nutzdaten in `contents[]`
- chunkbare Ressourcen werden ueber eigene Chunk-URIs oder Resource-
  Templates adressiert, nicht ueber zusaetzliche `resources/read`-
  Parameter
- Chunk-Resources koennen im Payload Metadaten wie `chunkId`,
  `nextChunkUri`, `range`, `truncated=true` und `etag` oder
  `resourceVersion` enthalten
- grosse Artefakte koennen alternativ ueber ein d-migrate-spezifisches
  Tool wie `artifact_chunk_get` gelesen werden

---

## 5. Vorgeschlagene Tools

### 5.1 Read-only Kernset

| Tool | Zweck |
| --- | --- |
| `schema_validate` | neutrales Schema validieren |
| `schema_generate` | DDL fuer ein Zielsystem erzeugen |
| `schema_compare` | Unterschiede zwischen zwei bereits materialisierten Schemata ermitteln |
| `capabilities_list` | Dialekte, Formate, Features, KI-Backends anzeigen |
| `job_status_get` | Status eines langen Laufs abfragen |
| `job_list` | Zugelassene Jobs eines Tenants auflisten |
| `artifact_list` | Artefakt- und Berichtuebersicht mit Filterung |
| `artifact_chunk_get` | begrenzten Chunk eines grossen Artefakts lesen |
| `schema_list` | Verfuegbare bzw. angelegte Schema-Artefakte listen |
| `profile_list` | Profiling-Reports auffinden |
| `diff_list` | Schema-Vergleichsergebnisse auffinden |

### 5.2 Kontrollierte Write-Tools

| Tool | Zweck | Default-Schutz |
| --- | --- | --- |
| `schema_reverse_start` | Reverse-Engineering-Job starten | policy-gesteuert |
| `schema_compare_start` | Vergleichsjob mit Connection-Refs oder langlaufender Introspection starten | policy-gesteuert |
| `data_profile_start` | Profiling-Job fuer Analyse und Migrationsplanung starten | policy-gesteuert |
| `data_import_start` | Importjob anlegen | bestaetigungspflichtig / policy-gesteuert |
| `data_transfer_start` | DB-zu-DB-Transfer starten | bestaetigungspflichtig / policy-gesteuert |
| `artifact_upload_init` | Upload-Session mit Metadaten und Gesamt-Checksumme anlegen | read-only Schema-Staging: Quota/Audit; Job-Inputs: policy-gesteuert |
| `artifact_upload` | Eingabe-Artefakt fuer spaetere Jobs hochladen | session-scoped nach Init |
| `artifact_upload_abort` | eigenen laufenden Artefakt-Upload abbrechen; administrative Abbrueche policy-gesteuert | Owner-Pruefung / policy-gesteuert |
| `job_cancel` | langen Lauf abbrechen | nur fuer eigene oder erlaubte Jobs |

Hinweis:

- Alle `*_start`-Tools muessen Idempotenz unterstuetzen.
- Fuer alle `*_start`-Tools ist in der d-migrate-MCP-Toolvertragsversion
  `v1` ein `idempotencyKey` verbindlich.
  Bei fehlendem `idempotencyKey` gilt `IDEMPOTENCY_KEY_REQUIRED`.
- `payloadFingerprint` ist ein Hash des normalisierten Payloads ohne
  `idempotencyKey`, empfohlen als `SHA-256` ueber deterministisch
  serialisiertem JSON (64-stellig, hex-codiert).
- Deduplizierung basiert auf einem serverseitigen Scope-Key
  (`tenantId`,`callerId`,`operation`,`idempotencyKey`) plus
  `payloadFingerprint`.
  Wiederholte Requests mit gleichem Scope-Key und identischem
  `payloadFingerprint` muessen auf denselben bestehenden Job gemappt
  werden.
- Bei `idempotencyKey`-Wiederverwendung in anderem Tenant-, Caller- oder
  Operations-Scope ist kein Konflikt sichtbar zu machen.
- Bei gleichem Scope-Key mit anderem `payloadFingerprint` ist
  `IDEMPOTENCY_CONFLICT` zurueckzugeben.
- Idempotency-Reservierung und Job-Anlage muessen atomar sein:
  `reserveOrGet(scopeKey,payloadFingerprint)` liefert bei identischem
  Fingerprint denselben Pending-/Awaiting-/Committed-Eintrag, erzeugt bei
  leerem Key genau eine Pending-Reservierung und beendet Konflikte ohne
  Policy-Pruefung mit `IDEMPOTENCY_CONFLICT`. Fehlt ein Grant, wechselt die
  Reservierung nach `AWAITING_APPROVAL`; ein genehmigter zweiter Aufruf
  muss diese Reservierung atomar claimen, genau einen Job erzeugen und
  danach als `COMMITTED` deduplizieren. `PENDING` hat eine
  `pendingLeaseExpiresAt`-Lease und kann nach Ablauf fuer denselben
  Scope/Fingerprint recovered werden. `AWAITING_APPROVAL` hat
  `awaitingApprovalExpiresAt`; abgelaufene Challenges duerfen erneuert
  werden. Explizite Ablehnung setzt `DENIED` und liefert bis zum Ablauf
  `POLICY_DENIED`.
- `callerId` ist serverseitig aus dem Auth-Kontext abgeleitet (z.B. Principal-ID)
  und wird nicht durch den Client gesetzt.
- 2-Phasen-Flow fuer policy-gesteuerte Tools:
  - erster Aufruf kann `POLICY_REQUIRED` mit
    `error.details.approvalRequestId`, `approvalKey` bzw.
    `idempotencyKey`, `payloadFingerprint` und einer Challenge liefern,
    aber nie mit einem verwendbaren `approvalToken`;
  - ein verwendbares `approvalToken` entsteht erst durch einen
    serverseitig geprueften Policy-, Human- oder Admin-Grant;
  - zweiter Aufruf muss denselben `idempotencyKey` bzw. `approvalKey`
    und dieses extern ausgestellte Token enthalten.
- 0.9.6 braucht einen Beta-tauglichen Grant-Aussteller, obwohl keine
  vollstaendige Consent-/Admin-UI Teil des Milestones ist: lokale
  Policy-Allowlist, Admin-CLI oder signierte Grant-Datei. Ein optionaler
  Local-Demo-Auto-Approval-Modus ist nur fuer Loopback/`stdio` zulaessig,
  muss explizit unsicher konfiguriert werden und ist immer auditpflichtig.
- Fuer synchrone policy-pflichtige Tools ohne `idempotencyKey` ist
  `approvalKey` zugleich der Idempotency-Key fuer Nebenwirkungen. Gleicher
  Tenant/Caller/Tool/`approvalKey` mit identischem `payloadFingerprint`
  liefert dasselbe Ergebnis bzw. dieselbe Upload-Session oder Artefakt-/
  Provider-Referenz; abweichender Payload liefert
  `IDEMPOTENCY_CONFLICT`.

### 5.3 KI-nahe Spezialtools

Die KI-nahen Spezialtools gehoeren zum MCP-Zielbild und werden ueber
Policy, Prompt-Hygiene und Audit abgesichert:

| Tool | Zweck | Default-Schutz |
| --- | --- | --- |
| `procedure_transform_plan` | Stored-Procedure-Transformation analysieren | policy-gesteuert |
| `procedure_transform_execute` | KI-gestuetzte Transformation mit Audit-Trail | bestaetigungspflichtig / policy-gesteuert |
| `testdata_plan` | Testdatengenerierung aus Schema und Regeln planen | policy-gesteuert |

Diese Tools muessen strikt an die in `docs/design.md` beschriebene
Provider- und Audit-Strategie gekoppelt sein.
Fuer 0.9.6 ist dafuer mindestens ein adapterneutraler KI-/Testdaten-Port
mit `NoOp`- oder lokaler Provider-Implementierung noetig. Externe
Provider sind optional und brauchen explizite Konfiguration,
Secret-Scrubbing, Provider-/Modell-Audit-Metadaten und erlaubende
Policy.

---

## 6. Vorgeschlagene Ressourcen

| Resource URI | Inhalt |
| --- | --- |
| `dmigrate://capabilities` | unterstuetzte Dialekte, Formate, Features |
| `dmigrate://tenants/{tenantId}/jobs/{jobId}` | Job-Metadaten und Status |
| `dmigrate://tenants/{tenantId}/artifacts/{artifactId}` | generierte DDL, Reports, Exporte |
| `dmigrate://tenants/{tenantId}/schemas/{schemaId}` | bekannte oder erzeugte Schema-Artefakte |
| `dmigrate://tenants/{tenantId}/profiles/{profileId}` | Profiling-Reports |
| `dmigrate://tenants/{tenantId}/diffs/{diffId}` | Schema-Vergleichsergebnisse |
| `dmigrate://tenants/{tenantId}/connections/{connectionId}` | server-registrierte, non-secret Verbindungsreferenzen |

Wichtig:

- Connection-Secrets sind **keine** MCP-Ressourcen.
- Rohdaten-Exporte sollten nur referenziert, nicht blind inline geliefert
  werden.
- `tenantId` in der Resource-URI ist adressierend; der eigentliche Zugriff wird
  anhand des principalbasierten Scopes im Token geprueft.
- Tenant-/Principal-Pruefungen gelten bei jeder Ressourcenauflosung;
  bei Fremdzugriff ist `TENANT_SCOPE_DENIED` zu liefern.
- Listen-Tools leiten den effektiven Tenant aus dem Principal ab. Ein
  `tenantId`-Parameter ist nur adressierend; abweichende Tenant-IDs sind
  nur fuer Admins oder explizite Cross-Tenant-Scopes erlaubt.
- Verbindungsreferenzen fuer Tool-Inputs nutzen nur tenant-scoped Resource-URIs
  (`dmigrate://tenants/{tenantId}/connections/{connectionId}`), nicht
  unscoped Prefixe wie `conn:<id>`.
- Connections werden ausserhalb von MCP registriert und verwaltet. CLI
  und MCP muessen dafuer eine gemeinsame adapterneutrale
  Config-/Connection-Ref-Aufloesung nutzen; der MCP-Adapter darf nicht
  vom CLI-Adapter abhaengen und kein eigenes YAML-Parsing duplizieren.
  MCP bietet nur lesenden Zugriff auf non-secret Verbindungsreferenzen.
- Das Beta-Modell trennt Connection-Referenz und Secret-Materialisierung:
  Resource-/Listenpfade sehen nur non-secret Metadaten und
  `credentialRef`/`providerRef`; ein adapterneutraler
  `ConnectionSecretResolver` loest Secrets erst in autorisierten
  Runner-/Driver-Pfaden auf. `.d-migrate.yaml`-Bootstrap darf keine rohen
  Secrets oder bereits expandierten Secret-URLs in MCP-Stores uebernehmen.

### 6.1 Tool-Discovery fuer Ressourcen-IDs

Damit Clients/Agenten Discoverability haben, liefern diese Tools konsistente ID-Listen:

- `job_list`: optional adressierendes `tenantId` + Filter (`status`, `createdAfter`, `createdBefore`, `limit`, `cursor`)
- `artifact_list`: optional adressierendes `tenantId` + Filter (`artifactKind`, `createdAfter`, `createdBefore`, `limit`, `cursor`)
- `artifact_chunk_get`: `artifactId` oder Artefakt-`resourceUri` + optional `chunkId`; Antwort maximal `64 KiB` mit optionalem `nextChunkId`/`nextChunkUri`
- `schema_list`: optional adressierendes `tenantId` + Filter (`owner`, `connectionId`, `createdAfter`, `createdBefore`, `limit`, `cursor`)
- `profile_list`: optional adressierendes `tenantId` + Filter (`connectionId`, `jobId`, `createdAfter`, `createdBefore`, `limit`, `cursor`)
- `diff_list`: optional adressierendes `tenantId` + Filter (`jobId`, `leftSchemaId`, `rightSchemaId`, `createdAfter`, `createdBefore`, `limit`, `cursor`)
- Alle Listen-Tools liefern mindestens `items` und `nextCursor`.
  `totalCount` ist optional und darf nur gesetzt werden, wenn der Store die
  exakte gefilterte Gesamtzahl ohne teuren Vollscan bestimmen kann.
  Alternativ darf `totalCountEstimate` als klar erkennbare Naeherung
  geliefert werden; Clients muessen fuer Pagination nur `nextCursor`
  verwenden.

---

## 7. Tool-Vertraege

MCP-Tool-Inputs sollen klein, fachlich und agentenfreundlich sein.

Beispiel `schema_compare`:

```json
{
  "left": {
    "schemaRef": "dmigrate://tenants/{tenantId}/schemas/source"
  },
  "right": {
    "schemaRef": "dmigrate://tenants/{tenantId}/schemas/target-staging"
  },
  "options": {
    "includeCompatibleChanges": true
  }
}
```

Connection-backed Vergleiche verwenden `schema_compare_start`, weil sie
live Introspection ausloesen koennen und deshalb Idempotenz,
Jobstatus, Policy und `job_cancel` brauchen.

Beispiel `schema_generate`:

```json
{
  "schemaRef": "dmigrate://tenants/{tenantId}/schemas/source",
  "targetDialect": "postgresql",
  "options": {
    "spatialProfile": "postgis",
    "generateRollback": true
  }
}
```

Alternativ darf `schema_generate` fuer kleine, neutrale Schemas ein
Inline-`schema` statt `schemaRef` akzeptieren. Dieser Pfad bleibt
read-only und braucht keinen vorgelagerten Upload und keine
Policy-Freigabe; zu grosse Inline-Schemas muessen `PAYLOAD_TOO_LARGE`
liefern.

Antworten sollten standardmaessig liefern:

- ein kurzes, maschinenlesbares `summary`
- optionale `resourceUri` und/oder `artifactId`, falls grosse Daten referenziert werden
- `executionMeta` mit stabilen Referenzen (z.B. `jobId`, `artifactId`, `createdAt`)
- strukturierte `findings` maximal 200 Eintraege
- bei Fehlern ein einheitlicher Fehlerblock mit: `error.code`, `error.message`,
  optional `error.details`, `error.requestId` und `error.retryable`
  (die `requestId` ist in Erfolgs- und Fehlerantworten identisch und dient
  der Korrelation mit `executionMeta.requestId`)

Beispiel Erfolg:

```json
{
  "summary": "Schema-Vergleich abgeschlossen, 3 kompatible und 1 inkompatible Aenderung gefunden.",
  "findings": [
    {
      "type": "compatible",
      "severity": "low",
      "description": "Spalte auf bestehender Tabelle hinzugefuegt"
    }
  ],
  "resourceUri": "dmigrate://tenants/tenant-123/diffs/diff-456",
  "executionMeta": {
    "requestId": "req-9f2f",
    "artifactId": "art-456",
    "jobId": "job-123",
    "createdAt": "2026-04-19T08:15:00Z"
  }
}
```

Beispiel Fehler:

```json
{
  "error": {
    "code": "IDEMPOTENCY_KEY_REQUIRED",
    "message": "idempotencyKey ist fuer dieses Tool im d-migrate-MCP-Toolvertrag v1 verpflichtend.",
    "details": {
      "tool": "data_import_start"
    },
    "requestId": "req-a1b2",
    "retryable": false
  }
}
```

Beispiel paginierte Antwort (`job_list`):

```json
{
  "items": [
    {
      "jobId": "job-123",
      "operation": "schema.reverse",
      "status": "succeeded",
      "createdAt": "2026-04-18T14:00:00Z"
    },
    {
      "jobId": "job-124",
      "operation": "data.profile",
      "status": "running",
      "createdAt": "2026-04-19T09:30:00Z"
    }
  ],
  "nextCursor": "eyJqb2JJZCI6ImpvYi0xMjQifQ==",
  "totalCountEstimate": 47
}
```

Fuer die d-migrate-MCP-Toolvertragsversion `v1` gilt verbindlich:

- langlaufende oder grosse Operationen werden als Start-Tool plus
  `jobId` modelliert
- Start-Tools muessen idempotent angreifbar sein:
  bei identischem Scope-Key
  (`tenantId`,`callerId`,`operation`,`idempotencyKey`) und identischem
  `payloadFingerprint` muss derselbe laufende/fertige Job
  zurueckgegeben werden; Konflikte entstehen nur im selben Scope.
- grosse Ergebnisse werden nur ueber `resourceUri` oder `artifactId`
  bereitgestellt
- Importdaten werden ueber `artifact_upload_init` und `artifact_upload`
  vorbereitet und anschliessend per `data_import_start` referenziert
- ein Tool darf keine komplette Exportdatei oder ganze Tabelleninhalte inline
  zurueckgeben
- Verbindungen werden nur als vorregistrierte, tenantgebundene Referenzen im
  Resource-Format (`dmigrate://tenants/{tenantId}/connections/{connectionId}`)
  akzeptiert; freie JDBC- oder URL-Strings sind nicht zulaessig.

---

## 8. Sicherheitsmodell

MCP ist besonders sensibel, weil ein Agent autonom handeln kann. Deshalb:

- read-only Tools standardmaessig freigeben
- write- oder kostenintensive Tools separat freischalten
- keine direkte Uebergabe von JDBC-Passwoertern an das Modell
- kein freier SQL-Toolzugang
- harte Limits fuer Datenmengen, Tabellenanzahl, Laufzeit und Parallelitaet
  (konkrete Defaults werden bei Implementierung je Tool festgelegt)
- explizite Policy fuer produktive Verbindungen

### 8.1 Kontrollpflichtige Operationen

Folgende Aktionen duerfen nie stillschweigend passieren:

- `schema_reverse_start` und `data_profile_start`
- `data_import_start`
- `data_transfer_start`
- KI-Aufrufe gegen externe Provider
- grossvolumige Datenexports

Diese Operationen brauchen mindestens:

- Policy-Freigabe
- Audit-Eintrag
- klare Kennzeichnung im Tool-Resultat

### 8.2 Prompt- und Datenhygiene

Der MCP-Adapter muss verhindern, dass:

- Secrets in Tool-Responses auftauchen
- ganze Tabellen ungefiltert in den Prompt-Kontext fliessen
- sensible SQL-, DDL- oder Prozedurtexte ohne Audit an externe Modelle gehen

Lokale Modelle (`Ollama`, `LM Studio`) bleiben fuer sensible KI-Pfade die
bevorzugte Option, analog zu `docs/design.md`.

### 8.3 Artefakt-Upload-Vertrag

Fuer Uploads ist im d-migrate-`v1`-Kontext ein expliziter Init-Pfad
verbindlich:

- `artifactKind` und `mimeType` werden serverseitig validiert
- `artifactKind=schema` ist der verbindliche Pfad, um grosse neutrale
  Schema-Dateien nach erfolgreichem Upload in eine `schemaRef` zu
  materialisieren; ungueltige Schemas erzeugen kein Schema-Register-
  Objekt
- `uploadIntent` trennt read-only und write-nahe Artefakte:
  - `schema_staging_readonly` ist nur fuer `artifactKind=schema` erlaubt,
    braucht keine Write-Policy und erzeugt eine read-only `schemaRef` fuer
    `schema_validate`, `schema_generate` und `schema_compare`
  - `job_input` ist fuer Import, Transfer, Transformation, Seed-Daten,
    Regeln und KI-nahe Tools policy-gesteuert
  - read-only gestagte Schemas duerfen nicht implizit als `job_input`
    wiederverwendet werden
- `sizeBytes` ist Pflicht, um harte Payload-Limits durchzusetzen.
- `checksumSha256` fuer das vollstaendige Artefakt ist bereits in
  `artifact_upload_init` Pflicht.
- `artifact_upload_init` erzeugt serverseitig eine kryptografisch
  opake `uploadSessionId` und liefert Status, erwarteten ersten
  `segmentIndex`, erwarteten ersten `segmentOffset` und
  `uploadSessionTtlSeconds`.
- Der erfolgreiche Init-Aufruf erzeugt eine serverseitige,
  session-scoped Upload-Berechtigung, die an `uploadSessionId`, Tenant,
  Principal, Init-Metadaten und Ablaufzeit gebunden ist.
- Wiederholtes policy-pflichtiges `artifact_upload_init` mit gleichem
  `approvalKey` und identischem Payload liefert dieselbe
  `uploadSessionId`; read-only Schema-Staging kann fuer Retry-Deduplizierung
  stattdessen einen stabilen `clientRequestId` nutzen. Dadurch erzeugen
  Agent-Retries nach Timeouts keine doppelten Upload-Sessions.
- Ein optionaler clientseitiger Session-Kandidat ist nur fuer
  Resume-faehige Clients erlaubt und muss atomar kollisionsfrei an
  Tenant, Principal, Approval-Fingerprint und Init-Metadaten gebunden
  werden.
- Bei stdio-MCP und streambarem HTTP-MCP werden grosse Artefakte in
  fortlaufenden Segmenten als wiederholbare `artifact_upload`-Aufrufe
  fuer eine bestehende Upload-Session geliefert.
- Segmentbytes werden in MCP-`tools/call`-Argumenten als
  `contentBase64` uebertragen; streambares HTTP bleibt ein normaler
  JSON-RPC-POST und verwendet keinen separaten binaeren Upload-Body.
  - Segmentierte Uploads sind verbindlich mit:
    - `uploadSessionId` (verbindlich, opak, sichtbar ASCII/URL-sicher,
      mindestens 128 Bit Entropie, maximal 128 Zeichen)
    - `segmentIndex` (beginnend bei 1, fortlaufend)
    - `segmentOffset` (Byte-Offset)
    - `segmentTotal` (erwartete Gesamt-Segmentanzahl)
    - `isFinalSegment`
    - `segmentSha256`
    - optionaler `clientRequestId`
  - Segment-Kandidaten sind bis Abschluss idempotent; Wiederholungen derselben
    `segmentIndex`/`segmentOffset`-Kombination werden toleriert.
  - Der Server verifiziert Reihenfolge, Offsets, Segmentgroesse und die Segment-Checksumme.
  - Bei `isFinalSegment=true` wird die rekonstruierte Gesamt-Checksumme
    gegen den in `artifact_upload_init` registrierten Wert validiert.
  - Bei Abbruch/Unterbrechung bleiben Segmente fuer die `uploadSessionId` bis zu einem
    Statuswechsel (`EXPIRED` oder `ABORTED`) erhalten, sofern die Session-Lease
    aktiv ist.
  - `segmentSha256` ist fuer jedes Segment verpflichtend und serverseitig zu pruefen.
  - Die finale Gesamt-`checksumSha256` kommt aus dem Init-Aufruf und wird
    serverseitig gegen das vollstaendige Artefakt geprueft.
- Session-Management:
  - `uploadSessionTtlSeconds` muss im Response gesetzt werden:
    Der Wert beschreibt die verbleibende Lease-TTL und darf gegen `0`
    laufen.
  - Der konfigurierbare Initialwert ist `900` Sekunden; die absolute
    Max-Lease ist `3600` Sekunden ab Session-Erzeugung.
  - Ein optionaler Mindestwert von `300` Sekunden gilt nur fuer neu
    ausgestellte oder erneuerte Lease-Werte, nicht fuer die kurz vor Ablauf
    gemeldete Restlaufzeit.
  - Jede erfolgreiche Segmentannahme darf die Lease bis maximal `3600`
    Sekunden ab Session-Erzeugung erneuern.
  - Zusaetzlich gilt ein Idle-Timeout von `300` Sekunden ohne
    erfolgreiche Segmentannahme; bei Idle-Timeout oder abgelaufener
    absoluter Lease wird der Status `EXPIRED` gesetzt und Segmente
    verworfen.
  - Der Client kann eine eigene aktive Session per `artifact_upload_abort`
    ohne erneute Policy-Freigabe auf Status `ABORTED` setzen und alle
    Zwischensegmente sofort verwerfen; fremde oder administrative
    Abbrueche bleiben policy-gesteuert.
  - Bei `isFinalSegment=true` wird der Uploadstatus auf `COMPLETED` gesetzt.
  - Bei Statuswechsel auf `EXPIRED` ist der Fehlercode `UPLOAD_SESSION_EXPIRED` moeglich.
  - Bei explizitem Abbruch ist der Fehlercode `UPLOAD_SESSION_ABORTED` moeglich.
- Standard-Limits:
  - max. Uploadgroesse: `200 MiB`
  - erlaubte `mimeType`: `application/json`, `application/sql`,
    `application/yaml`, `text/plain`, `text/yaml`
  - erlaubte `artifactKind`: `schema`, `ddl`, `transform-script`,
    `seed-data`, `rules`, `generic`
  - `sizeBytes` ist verpflichtende Referenzgroesse fuer die erwartete
    Endgroesse.
  - Der Server prueft die kumulierte `segment`-Groesse laufend; bei
    Ueberschreitung von `sizeBytes` oder des Max-Limits wird die Session
    sofort mit `PAYLOAD_TOO_LARGE` beendet (siehe 8.4).
- Uploadsegmente und finalisierte Artefaktbytes werden in produktnahen
  `stdio`- und HTTP-Pfaden ueber einen Byte-/Segment-Store mit
  File-Spooling oder gleichwertiger externer Speicherung gehalten, nicht
  vollstaendig im Heap. Der Store muss atomare Segmentwrites,
  Range-/Chunk-Reads, Gesamt-Hash-Berechnung und TTL-/Abort-Cleanup
  unterstuetzen.
- Pro Tenant/Principal gelten konfigurierbare Quotas fuer aktive
  Upload-Sessions, reservierte Bytes, gespeicherte Bytes und parallele
  Segmentwrites; Verletzungen liefern `RATE_LIMITED`.

### 8.4 Standardisierte Fehlercodes

Alle MCP-Fehler sollen mit strukturiertem Fehler-Envelope geliefert werden:

- `error.code` (enum)
- `error.message`
- `error.details` (strukturierte Daten)
- `error.requestId` (optional)
- `error.retryable` (bool)

Verbindliche Fehlercodes:

- `AUTH_REQUIRED`
- `FORBIDDEN_PRINCIPAL`
- `POLICY_REQUIRED`
- `IDEMPOTENCY_CONFLICT`
- `IDEMPOTENCY_KEY_REQUIRED`
- `RESOURCE_NOT_FOUND`
- `VALIDATION_ERROR`
- `RATE_LIMITED`
- `OPERATION_TIMEOUT`
- `POLICY_DENIED`
- `PAYLOAD_TOO_LARGE`
- `UPLOAD_SESSION_EXPIRED`
- `UPLOAD_SESSION_ABORTED`
- `UNSUPPORTED_MEDIA_TYPE`
- `UNSUPPORTED_TOOL_OPERATION`
- `PROMPT_HYGIENE_BLOCKED`
- `TENANT_SCOPE_DENIED`
- `INTERNAL_AGENT_ERROR`

`POLICY_REQUIRED` enthaelt Challenge-Daten wie `approvalRequestId`,
`approvalKey` bzw. `idempotencyKey`, `payloadFingerprint` und
erforderliche Scopes/Gruende, aber kein verwendbares `approvalToken`.
Verwendbare Tokens duerfen erst nach serverseitig geprueftem Policy-,
Human- oder Admin-Grant ausgegeben werden.

`RATE_LIMITED` deckt Rate Limits und Quotas pro Tenant/Principal ab, u.a.
aktive Jobs, aktive Upload-Sessions, Upload-/Artefaktbytes, parallele
Segmentwrites und KI-/Provider-Aufrufe. `OPERATION_TIMEOUT` deckt
Runner-Starts, Upload-Finalisierung, langlaufende Tool-Handler und
Provider-Aufrufe ab. Beide Fehler muessen auditierbar sein und duerfen
keine fremden Ressourcendetails leaken. Reine Deduplizierungsantworten fuer
bereits angelegte Jobs, Sessions oder Artefakt-/Provider-Referenzen
verbrauchen keine neue Quota; neue Jobs, Sessions, Segmente,
Finalisierungen und Provider-Aufrufe werden vor der Nebenwirkung quotiert.

MCP-Wire-Mapping:

- Protokollfehler wie unbekannte Toolnamen, malformed
  `tools/call`-Requests oder Schema-Verletzungen des MCP-Protokolls
  werden als JSON-RPC-Error mit numerischem `error.code` beantwortet.
- Fachliche Tool-Ausfuehrungsfehler wie `VALIDATION_ERROR`,
  `POLICY_REQUIRED`, `TENANT_SCOPE_DENIED` oder
  `UNSUPPORTED_TOOL_OPERATION` werden als `tools/call`-Result mit
  `isError=true` geliefert.
- Die stabilen d-migrate-Fehlercodes stehen dabei in
  `structuredContent.error.code`, nicht im numerischen
  JSON-RPC-`error.code`.
- `UNSUPPORTED_TOOL_OPERATION` gilt nur fuer bekannte d-migrate-Tools
  mit fachlich nicht unterstuetzten Optionen oder Operationen; ein
  unbekannter Toolname bleibt ein MCP-/JSON-RPC-Protokollfehler.
- Resource-Request-Fehler wie eine fehlende Resource werden als
  JSON-RPC-Errors beantwortet. Der stabile d-migrate-Code steht dabei in
  `error.data.dmigrateCode`, z.B. `RESOURCE_NOT_FOUND` bei
  JSON-RPC-Code `-32002`.

---

## 9. Auditierbarkeit

Jede MCP-Aktion sollte nachvollziehbar sein:

- wer oder welcher Agent hat das Tool aufgerufen
- mit welchen Parametern
- gegen welche Verbindung oder welches Artefakt
- mit welchem Ergebnis
- bei KI-Transformationen: welches Modell, welcher Provider, welche Version

Fuer KI-generierte Inhalte gilt derselbe Audit-Trail wie im Design-Dokument:

- Quelle
- Zwischenformat
- Zielartefakt
- Modell-Metadaten

---

## 10. Transport

Sinnvolle Betriebsmodi:

- `stdio` fuer lokale IDE-/CLI-Agenten
- streambares HTTP fuer entfernte Agent-Plattformen

Empfohlene Sicherheitsgrundlagen:

- jeder MCP-Aufruf muss ein verifizierbares `principalId` haben
- HTTP:
  - fuer entfernte bzw. nicht-lokale Clients ist Auth verbindlich
  - d-migrate agiert hier als Resource Server; ein eigener
    Authorization Server, Client-Registration-UI oder Mandanten-Admin
    gehoert nicht zum 0.9.6-Ziel
  - `Authorization` Header mit Bearer-Token (oder aequivalentes
    signiertes Principalsignal)
  - bei fehlendem oder ungueltigem Token: HTTP 401 mit
    `WWW-Authenticate` und Protected-Resource-Metadata-Hinweis
  - bei unzureichenden Scopes: HTTP 403 mit Scope-Challenge
  - Protected Resource Metadata wird ueber well-known URI oder die im
    `WWW-Authenticate` referenzierte URL angeboten
  - das Metadata-Dokument enthaelt mindestens `resource` als kanonische
    MCP-Server-/Endpoint-URI und eine nicht-leere
    `authorization_servers`-Liste mit den zulaessigen HTTPS-Issuern;
    `scopes_supported` enthaelt die d-migrate-Scope-Namen und muss zu den
    Scope-Challenges passen
  - Tokens duerfen nicht aus Query-Parametern gelesen werden und muessen
    auf Audience/Resource des MCP-Servers validiert werden
  - Tokenvalidierung ist fail-closed: konfigurierte Issuer muessen exakt
    passen, JWTs werden ueber JWKS mit Algorithmus-Allowlist oder ueber
    explizit konfigurierte Introspection geprueft, `aud`/Resource,
    Ablauf/Clock-Skew, Pflichtclaims und Tool-/Resource-Scopes werden
    validiert; ein blosser Bearer-String darf nie als Principal genuegen
  - 0.9.6 nutzt rollenartige Scopes statt granularer Tool-Scopes:
    `dmigrate:read`, `dmigrate:job:start`,
    `dmigrate:artifact:upload`, `dmigrate:data:write`,
    `dmigrate:job:cancel`, `dmigrate:ai:execute` und
    `dmigrate:admin`.
  - `WWW-Authenticate` nennt fuer den konkreten Request die minimal
    notwendigen Scopes; `dmigrate:admin` ist nur fuer Cross-Tenant- oder
    fremde administrative Aktionen erforderlich.
  - Auth-Deaktivierung ist nur fuer lokale Tests/Demos mit expliziter
    unsicherer Konfiguration erlaubt und muss auf Loopback-Bindung
    (`127.0.0.1` oder `::1`) ohne Public Base URL beschraenkt sein;
    `0.0.0.0`, Public Hostnames oder nicht-lokale Bind-Adressen sind
    dabei hart abzulehnen
  - optional mTLS fuer Maschinen-zu-Maschinen-Verkehre
- stdio:
  - nur von vertrauenswuerdigem lokalem Prozess/Benutzer aufrufbar
  - mindestens eine der beiden Bedingungen ist verbindlich:
    - starke Prozess-/Benutzerauthentisierung durch den Host
    - Verbindungs-Token via Umgebung (`DMIGRATE_MCP_STDIO_TOKEN`)
  - der daraus abgeleitete `principalId` ist in Logs und Audit-Trail konsistent zu verwenden

Fortschrittsmeldungen fuer langlaufende Jobs werden in der
d-migrate-MCP-Toolvertragsversion `v1` ueber Polling
(`job_status_get`) abgebildet. SSE-basierte Notifications oder
MCP-Resource-Subscriptions sind nicht Teil des verbindlichen 0.9.6-
Umfangs. Der Streamable-HTTP-`GET`-Pfad bleibt trotzdem MCP-konform:
der Server liefert entweder `text/event-stream` fuer Transport-Interop
oder HTTP 405, wenn keine serverinitiierte Stream-Kommunikation
angeboten wird.

Bei session-basiertem streambarem HTTP soll der Client nicht mehr
benoetigte Sessions per `DELETE` mit `MCP-Session-Id` beenden koennen.
Der Server terminiert die Session oder liefert HTTP 405, wenn
clientseitige Session-Terminierung nicht angeboten wird.

0.9.6 fuehrt keine parallele MCP-Tasks-Abstraktion fuer
d-migrate-Langlaeufer ein. Das d-migrate-Jobmodell mit
`job_status_get`, `job_list` und `job_cancel` bleibt die einheitliche
Steuerflaeche. `job_cancel` ist dabei nur gueltig, wenn die betroffenen
Runner kooperative Cancellation-Checkpoints und Side-Effect-Stopp
unterstuetzen; eine reine `cancel_requested`-Markierung ohne Worker-
Propagation erfuellt den 0.9.6-Vertrag nicht.

### 10.1 Versionierung

Die MCP-Protokollversion wird in der `initialize`-Phase ueber das
datierte Feld `protocolVersion` ausgehandelt. Fuer 0.9.6 ist die
MCP-Protokollversion `2025-11-25` massgeblich. `v1` bezeichnet dagegen
nur den d-migrate-spezifischen Tool-/Resource-Vertrag und darf nicht als
MCP-`protocolVersion` verwendet werden. Bei inkompatiblen
MCP-Protokollversionen ist die Verbindung mit einer klaren
JSON-RPC-Fehlermeldung abzulehnen.

Empfehlung:

- `stdio` fuer lokale IDE-/CLI-Agenten
- streambares HTTP fuer entfernte Agent-Plattformen

---

## 11. Einfuehrungsreihenfolge

Die Phasen sind Gates innerhalb von 0.9.6, keine Verschiebung nach 0.9.7.
Ein fruehes Gate darf nicht als vollstaendig abgeschlossener Milestone
markiert werden.

### Phase 1

- `capabilities_list`
- `schema_validate`
- `schema_generate`
- `schema_compare`
- `job_status_get`
- `job_list`
- `artifact_list`
- `schema_list`
- `profile_list`
- `diff_list`

### Phase 2

- `schema_reverse_start`
- `schema_compare_start`
- `data_profile_start`
- Artefakt-Ressourcen
- Idempotency-Zustand `AWAITING_APPROVAL` und genehmigter Retry, der genau
  einen Job erzeugt
- Byte-/Segment-Store mit File-Spooling fuer Artefaktinhalte und Upload-
  Segmente
- read-only Schema-Staging fuer grosse Schemas ohne Write-Policy, aber mit
  Quota und Audit

### Phase 3

- kontrollierte Write-Tools fuer `artifact_upload_init`,
  `artifact_upload`, `data_import_start` und `data_transfer_start`
- `artifact_upload_abort`
- Quotas, Rate Limits und Timeouts fuer aktive Jobs, Upload-Sessions,
  Upload-/Artefaktbytes und Provider-Aufrufe

### Phase 4

- KI-nahe Spezialtools fuer Procedure-Transformation und Testdaten

---

## 12. Entscheidung

Fuer eine KI-Umgebung sollte `d-migrate` nicht nur "auch per REST erreichbar"
sein. Es sollte einen eigenen MCP-Adapter bekommen. Damit werden die
bestehenden Migrationsfunktionen fuer Agenten nutzbar, ohne die Sicherheits-
und Audit-Anforderungen einer autonomen KI-Nutzung zu verwischen.
