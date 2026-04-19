# gRPC-Service-Spezifikation: d-migrate

> Dokumenttyp: Zielbild / Spezifikation
>
> Status: Entwurf fuer die kuenftige interne Service-Schnittstelle
>
> Referenzen: `docs/architecture.md`, `docs/cli-spec.md`,
> `docs/job-contract.md`, `docs/implementation-plan-0.9.1.md`

---

## 1. Ziel

Fuer Service-zu-Service-Kommunikation in einer Microservice-Architektur reicht
REST allein nicht aus. `d-migrate` hat mehrere Operationen, die stark
typisiert, potenziell langlaufend und teilweise streaming-orientiert sind.

Das gRPC-Zielbild ist:

- ein zusaetzlicher driving adapter unter `adapters/driving/grpc`
- protobuf-basierte, stark typisierte Vertraege
- effiziente Uebertragung fuer interne Netze
- Streaming fuer Daten- und Progress-Pfade
- dieselben fachlichen Kern-Use-Cases wie CLI und REST

---

## 2. Wann gRPC sinnvoll ist

gRPC ist fuer `d-migrate` besonders passend bei:

- internen Plattformdiensten
- hohem Request-Volumen
- klar versionierten Vertraegen zwischen Teams
- server-, client- oder bidirektionalem Streaming
- polyglot Microservices, die Codegen aus `.proto` nutzen

REST bleibt sinnvoll fuer Gateway, Browser und externe Integrationen. gRPC ist
das bevorzugte Protokoll fuer interne Orchestrierung.

---

## 3. Architekturposition

```text
Internal Service / Orchestrator
             |
             v
   adapters/driving/grpc
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

Der gRPC-Adapter soll dieselben Anwendungsdienste nutzen wie REST. Er darf
nicht eine zweite fachliche Logikschicht aufbauen.

Empfohlene Modulstruktur:

- `adapters/driving/grpc`
- `api/grpc` oder `proto/` fuer `.proto`-Dateien und Codegen-Konfiguration

### 3.1 Gemeinsamer Kernvertrag fuer Jobs und Artefakte

gRPC, REST und MCP muessen denselben fachlichen Kernvertrag nutzen:

- `job_id` und `artifact_id` sind opake, stabile String-IDs
- Jobs und Artefakte tragen immer `created_at` und `expires_at`
- Artefakte sind immutable
- nur derselbe Mandant / Principal oder ein Administrator darf Job und
  Artefakt lesen, abbrechen oder herunterladen
- Terminalstatus eines Jobs aendert sich nach `SUCCEEDED`, `FAILED` oder
  `CANCELLED` nicht mehr
- grosse Ergebnisse werden ueber `artifact_id` referenziert, nicht inline in
  Statusobjekte eingebettet
- Jobs und Artefakte werden nach `expires_at` serverseitig aufgeraeumt

---

## 4. Service-Zuschnitt

Ein grober Dienstschnitt fuer `v1`:

| Service | Zweck |
| --- | --- |
| `HealthService` | Liveness, Readiness, Version, Capabilities |
| `SchemaService` | Validieren, Generieren, Reverse, Vergleichen |
| `DataService` | Export, Import, Transfer, Profile |
| `JobService` | Job-Status, Progress, Cancel |
| `ArtifactService` | Ergebnis-Artefakte laden |

### 4.1 HealthService

Vorgeschlagene RPCs:

```proto
rpc GetHealth(GetHealthRequest) returns (HealthStatus);
rpc GetReadiness(GetReadinessRequest) returns (HealthStatus);
rpc GetVersion(GetVersionRequest) returns (VersionInfo);
rpc GetCapabilities(GetCapabilitiesRequest) returns (ServiceCapabilities);
```

`GetHealth` und `GetReadiness` liefern gezielt Liveness-/Readiness-Daten ohne schwere
Business-Logik; `GetCapabilities` dokumentiert aktivierte Funktionen und
Feature-Switches.

### 4.2 SchemaService

Vorgeschlagene RPCs:

```proto
rpc ValidateSchema(ValidateSchemaRequest) returns (ValidateSchemaResponse);
rpc GenerateSchemaDdl(GenerateSchemaDdlRequest) returns (GenerateSchemaDdlResponse);
rpc ReverseSchema(ReverseSchemaRequest) returns (JobAccepted);
rpc CompareSchema(CompareSchemaRequest) returns (JobAccepted);
```

`ReverseSchema` wird als Job behandelt, weil die Laufzeit und Ergebnisgroesse
stark schwanken koennen.

`CompareSchema` wird ebenfalls als Job behandelt, wenn Ergebnisumfang oder
Laufzeit das vorgibt. Ergebnisdaten werden als Summary + optional `artifact_id`
im `JobStatus`/Artefakt zugaenglich gemacht.

### 4.3 DataService

Vorgeschlagene RPCs:

```proto
rpc ExportData(ExportDataRequest) returns (JobAccepted);
rpc OpenArtifactUpload(OpenArtifactUploadRequest) returns (UploadSession);
rpc StartImport(StartImportRequest) returns (JobAccepted);
rpc TransferData(TransferDataRequest) returns (JobAccepted);
rpc ProfileData(ProfileDataRequest) returns (JobAccepted);
rpc UploadArtifact(stream ArtifactChunk) returns (stream ArtifactChunkAck); // bi-direktional
rpc GetUploadSession(GetUploadSessionRequest) returns (UploadSession);
rpc FinalizeArtifactUpload(FinalizeArtifactUploadRequest) returns (UploadSession);
rpc AbortArtifactUpload(AbortArtifactUploadRequest) returns (UploadSession);
```

Anmerkung:

- `UploadArtifact` uebernimmt den grossen Input-Strom in einen serverseitigen
  Artefakt-Speicher.
- `OpenArtifactUpload` ist Pflichtvoraussetzung und erzeugt die `upload_session_id`
  (kurzlebig, serverseitig verwaltet).
- Uploads muessen explizit mit `FinalizeArtifactUpload` abgeschlossen werden.
- `AbortArtifactUpload` beendet Uploads bewusst mit definiertem Cleanup.
- `OpenArtifactUpload` setzt `expected_total_bytes` einmalig fuer die Session.
- Jede `ArtifactChunk`-Nachricht MUSS `upload_session_id`, `chunk_index` und
  `chunk_checksum` tragen.
- `UploadArtifact` ist in v1 verbindlich ein bidirektionales Streaming: Der Client sendet
  `ArtifactChunk`-Nachrichten; der Server antwortet nach jedem verwerteten Chunk mit
  `ArtifactChunkAck`.
- Jeder Upload-Chunk wird serverseitig idempotent verarbeitet; bei Duplikaten wird derselbe
  `chunk_index` nur einmal persistiert.
- Der Server lehnt Uploads ab, wenn Größen- oder Pruefsummenvalidierung fehlschlaegt
  oder Session-Metadaten unvollstaendig sind.
- Resume-Verhalten:
  - Upload-Sessions haben definierte Zustaende und ein konfigurierbares Time-to-live (TTL):
    `OPEN` -> `RECEIVING` -> `INTERRUPTED` -> (`RESUMING`) -> `COMPLETED` oder
    `ABORTED`/`EXPIRED`.
  - `OPEN`: erstellt durch `OpenArtifactUpload`.
  - `RECEIVING`: aktiver Stream laeuft und es wird validiert.
  - `INTERRUPTED`: Transportbruch/Timeout waehrend des Streams; bereits persistierte Chunks bleiben erhalten.
  - `RESUMING`: Uebergangszustand, waehrend der Client mit derselben `upload_session_id`
    auf der aktuellen `next_expected_chunk_index` fortsetzt; geht bei erfolgreichem
    Stream-Aufbau automatisch in `RECEIVING` ueber.
  - `COMPLETED`: nur nach erfolgreichem `FinalizeArtifactUpload`.
  - `ABORTED`: nur nach `AbortArtifactUpload`; weitere Upload-RPCs auf der Session werden abgelehnt.
  - `EXPIRED`: nach Inaktivitaets- oder Absolut-Zeitenlimit automatisch aufgeraeumt.
  - Nach `INTERRUPTED` kann ein neuer Upload mit derselben `upload_session_id` beim
    naechsten offenen Chunk fortsetzen.
  - `UploadArtifact` liefert nach jedem empfangenen Chunk einen `ArtifactChunkAck`
    mit `received_until_chunk_index` und `next_expected_chunk_index`.
  - Client und Server behandeln doppelte Chunks mit gleichem
    `upload_session_id` und `chunk_index` als idempotent.
  - `GetUploadSession` gibt bei Unterbrechung den Zustand einer laufenden Session
    (letzter bestaetigter Chunk, verbleibende Bytes, Ablaufzeit, aktueller State, letzte `chunk_checksum`) zur
    Wiederaufnahme zurueck.
- Bei abruptem Abbruch ohne `AbortArtifactUpload`/`FinalizeArtifactUpload` beendet der Server die Session in `INTERRUPTED`.
  Ein Hintergrundjob bereinigt `OPEN`, `RECEIVING` und `INTERRUPTED` Sessions nach konfigurierter Idle- oder
  Gesamtlaufzeit; transiente Artefakt-Fragmente werden danach verworfen.
- `StartImport` startet danach den eigentlichen Importjob ueber `artifact_id`
  - `StartImportRequest` enthaelt nur Artefakt-Referenzen und Import-Optionen (keine Rohdaten).
- `ExportData` laeuft in `v1` immer als Job und schreibt Ergebnis-Artefakte.
- Artefakt-Downloads sind bewusst im `ArtifactService` verortet (siehe Abschnitt 4.5).
- `TransferData` uebergibt Daten von einem Quell- zu einem Zielartefaktpfad oder
  -speicher und ist ebenfalls asynchron (`JobAccepted`). Es liefert selbst nur einen
  `JobAccepted`; Fortschritt und Ergebnisstatus erfolgen ueber `WatchJob`/`GetJob`.
- Ein direktes Import-Streaming ohne Jobmodell ist in `v1` bewusst nicht Teil
  des Vertrags

### 4.4 JobService

```proto
rpc GetJob(GetJobRequest) returns (JobStatus);
rpc ListJobs(ListJobsRequest) returns (ListJobsResponse);
rpc CancelJob(CancelJobRequest) returns (CancelJobResponse);
rpc WatchJob(JobWatchRequest) returns (stream JobEvent);
```

`ListJobs` liefert paginierte Ergebnisse fuer den eigenen Tenant
(`ListJobsRequest` enthaelt `page_token`, `page_size` und optionale Filter).

`WatchJob` ist einer der Hauptgruende fuer gRPC: Fortschritt und Status lassen
sich sauber streamen, ohne Polling. `JobEvent` umfasst sowohl Zwischenfortschritt
als auch Terminalstatus.

### 4.5 ArtifactService

```proto
rpc GetArtifactMetadata(GetArtifactRequest) returns (ArtifactMetadata);
rpc ListArtifacts(ListArtifactsRequest) returns (ListArtifactsResponse);
rpc DownloadArtifact(GetArtifactRequest) returns (stream ArtifactChunk);
```

`ListArtifacts` liefert paginierte Ergebnisse fuer den eigenen Tenant
(`ListArtifactsRequest` enthaelt `page_token`, `page_size`, optionale
Filter nach `job_id` oder Status).

`GetArtifactMetadata` ist bewusst im `ArtifactService` verortet, damit die
Artefakt-Lebensdauer- und Berechtigungsregeln dort zentral bleiben.

`DownloadArtifact` MUSS eine maximale Chunk-Groesse und die Ueberpruefung von
`artifact_version` (Versionstoken/ETag) erzwingen.

`GetArtifactRequest` ist mit dem Feld `artifact_id` auszulegen.
`artifact_version` ist optional und als stabiles Versionstoken (`version_token`) zu interpretieren.
- Ist es gesetzt, wird die angefragte Version mittels semantischer Token-Gleichheit validiert (entsprechend `If-Match`-Semantik).
  - Das Token darf nicht als HTTP-Header-Name verwendet werden; im Proto ist es ein normales Feld.
  - Das Vergleichsverhalten ist streng gleichheitbasiert; es gibt keine schwache Uebereinstimmung.
- Ist es nicht gesetzt, liefert der Server die aktuellste verfuegbare Version.
- Bei expliziten Clients, die eine exakte Version benoetigen, ist `GetArtifactMetadata`
  der empfohlene Einstiegspunkt.
- Aendert sich die Version zwischen `GetArtifactMetadata` und `DownloadArtifact`,
  antwortet der Server mit fachlichem Fehler `FAILED_PRECONDITION` und
  `d_migrate_error_code=artifact_version_mismatch`.
- `GetArtifactMetadata` gibt in jedem Fall das aktuell gueltige `artifact_version` zurueck, damit Clients
  anschließend konsistent herunterladen oder Versionen vergleichen koennen.

---

## 5. Streaming-Muster

### 5.1 Server-Streaming

Geeignet fuer:

- Job-Fortschritt
- kleine Exportvorschauen
- Validierungsereignisse in grossen Dokumenten

Nicht die Default-Variante fuer Voll-Export ueber das Netzwerk. Fuer grosse
Ergebnisse ist ein serverseitiger Job plus Artefakt-Download robuster.

### 5.2 Client-Streaming

Geeignet fuer:

- Import grosser Datenmengen
- Upload grosser Artefakte erfolgt in v1 **nicht** als reines Client-Streaming,
  sondern als bidirektionales Streaming mit Chunk-Acknowledgements.

### 5.3 Bidirektionales Streaming

Nur dort einsetzen, wo ein echter Dialog noetig ist, z. B.:

- interaktiver Import mit Rueckmeldungen pro Chunk
- sehr spaeter, falls ein Agenten- oder Assistenzmodus per gRPC gebraucht wird

Fuer die erste Version ist bidi-Streaming primaer fuer echten Dialog vorgesehen.
`UploadArtifact` ist die wichtigste Ausnahme: bidirektional, ohne menschliche
Interaktion, mit Chunk- und Acknowledgement-Steuerung (`ArtifactChunk`/`ArtifactChunkAck`),
und wird bei Unterbrechung mit `GetUploadSession` kombiniert.

---

## 6. Protobuf-Designregeln

### 6.1 Fachliche Requests statt CLI-Flags

Proto-Messages sollen fachliche Struktur abbilden, nicht Flag-Namen der CLI
kopieren.

Schlecht:

```proto
message Request {
  bool no_color = 1;
  bool verbose = 2;
}
```

Gut:

```proto
message GenerateSchemaDdlRequest {
  string target_dialect = 1;
  SchemaDefinition schema = 2;
  GenerateOptions options = 3;
}
```

### 6.2 Grosse Nutzdaten

Fuer grosse Ergebnisse sind zwei Wege sinnvoll:

- inline, wenn die Antwort klein und direkt konsumierbar ist
- als `artifact_id`, wenn die Antwort gross ist oder mehrfach geladen wird

Fuer `v1` gilt verbindlich:

- `ExportData`, `ReverseSchema`, `ProfileData` und `CompareSchema` liefern nur Summary plus `artifact_id`.
- `GenerateSchemaDdl` liefert bei kleinen Ergebnissen inline, bei groesseren Ergebnissen
  nur Summary plus `artifact_id` (konfigurierter Schwellwert).
- Importdaten werden nicht als eingebettete Message-Bloecke in
  `StartImport` uebertragen, sondern ueber `UploadArtifact`

### 6.3 Proto-Package-Konvention

Alle `.proto`-Dateien nutzen das Package `dmigrate.v1`:

```proto
package dmigrate.v1;
```

Service-Namen folgen dem Muster `dmigrate.v1.SchemaService`,
`dmigrate.v1.JobService` usw. Bei Breaking Changes wird `v2` eingefuehrt;
abwaertskompatible Erweiterungen bleiben in `v1`.

### 6.4 Zeit, IDs, Enums

- Timestamps als `google.protobuf.Timestamp`
- Job-IDs als stabile Strings, nicht als fortlaufende Zahlen
- Dialekte, Formate, Statuswerte als Enums

### 6.5 Lokalisierung

Strukturierte Felder bleiben sprachstabil englisch. Lokalisierte Texte werden
ueber Metadaten oder ein explizites `locale`-Feld angefordert.

---

## 7. Fehlerabbildung

gRPC-Status und fachliche Fehler muessen getrennt behandelt werden.

- Transport-/Infrastrukturfehler: gRPC Status (`UNAVAILABLE`,
  `DEADLINE_EXCEEDED`, `UNAUTHENTICATED`, `PERMISSION_DENIED`)
- fachliche Fehler: strukturierte gRPC-Fehlerantworten mit `google.rpc.Status` als
  Huelle und fachlichen Details in `google.rpc.ErrorInfo` bzw. `google.rpc.BadRequest`.
  - `google.rpc.Status.code` bildet den technischen gRPC-Status ab.
  - fachlicher Fehlercode steht in `google.rpc.ErrorInfo.metadata["d_migrate_error_code"]`.
  - fachliche Kategorie wird in `google.rpc.ErrorInfo.reason` transportiert.
- `exit_code` ist optional und dient nur der Kompatibilitaet mit existierenden
  CLI-Exitcodes; fachlich wird bevorzugt mit `error_code` (stringbasiert, stabil)
  gearbeitet.

Praktische Regel:

Transportfehler bleiben reine `grpc-status`-Antworten. Fachliche Fehler werden
in allen synchronen RPCs konsistent als gRPC-Fehlerantworten mit strukturierten
`google.rpc`-Details gemeldet; ein separates `error`-Feld im
Erfolgs-Response-Payload ist nicht vorgesehen.
`google.rpc.ErrorInfo`/`google.rpc.BadRequest` transportieren die fachliche Semantik.
`google.rpc.ErrorInfo.reason` traegt die semantische fachliche Kategorie
(`request_invalid`, `validation_failed`, `migration_failed`, `provider_failed`,
`io_failure`, `cancelled` etc.).
Der stabile fachliche Code wird in `google.rpc.ErrorInfo.metadata["d_migrate_error_code"]`
transportiert.
- Fuer neue Clients ist der fachliche Fehlercode in `google.rpc.ErrorInfo.metadata["d_migrate_error_code"]`
  die einzig vertraglich stabile Fehlerkennung.
- `d_migrate_error_code`/`error_code` im Payload ist optional und darf nur
  als Legacy-Kompatibilitaetsfeld gepflegt werden; Steuerlogik darf sich nicht
  darauf verlassen.
- Bei asynchronen Jobs gilt: Die erste Antwort ist fuer eingereichte Jobs immer fachlich
  erfolgreich (`OK` + `JobAccepted`), selbst wenn der fachliche Zustand spaeter zu `FAILED` wird.

Beispielhafte Zuordnung:

| CLI-Code | Fachlicher Typ | gRPC |
| --- | --- | --- |
| `1` | Allgemeiner Fehler | `INTERNAL` |
| `2` | Request invalid | `INVALID_ARGUMENT` |
| `3` | Validation failed | `INVALID_ARGUMENT` |
| `4` | Connection failed | `UNAVAILABLE` |
| `5` | Migration failed | `INTERNAL` |
| `6` | AI provider failed | `UNAVAILABLE` |
| `7` | Local / render / file failure | `INTERNAL` |
| `130` | cancelled | `CANCELLED` |

Fuer Job-RPCs gilt zusaetzlich:

- alle RPCs, die `JobAccepted` zurueckgeben (`ReverseSchema`, `CompareSchema`,
  `ExportData`, `StartImport`, `TransferData`, `ProfileData`), muessen ein
  `idempotency_key`-Feld im Request fuehren; fehlt der Key, antwortet der
  Server mit `INVALID_ARGUMENT` (siehe `docs/job-contract.md` §5)
- ein erfolgreich angenommener Job liefert immer `OK` plus `JobAccepted`
- spaetere Laufzeitfehler erscheinen im `JobStatus` als fachlicher Status
  `FAILED`, nicht als nachtraeglicher Transportfehler
- `WatchJob` streamt Fortschritt und Statusuebergaenge fuer den Job
  (inklusive `progress`, `phase`, `message`) sowie final mindestens eine dieser
  Terminalereignisse: `SUCCEEDED`, `FAILED` oder `CANCELLED`.
  Nach dem ersten Terminalereignis MUSS der Stream beendet werden.

---

## 8. Security

Pflicht fuer produktive interne Nutzung:

- TLS immer aktiv, bevorzugt mTLS
- Service Identity ueber Zertifikate oder Mesh
- Autorisierung auf RPC, ConnectionRef und Zielsystem
- Request- und Message-Size-Limits
- Deadline- und Cancellation-Unterstuetzung (synchrone RPCs: 30 s Default;
  Job-Annahme: 10 s; Streaming-RPCs: kein festes Deadline, aber
  Inactivity-Timeout)
- serverseitige Secret-Aufloesung

Konkrete Umsetzung:

- TLS-Clientzertifikat (Subjekt/AltNames) oder OIDC/JWT (`iss`, `sub`,
  `aud`, `d-migrate:tenant`, `d-migrate:roles`) ist Pflicht fuer die
  Authentifizierung.
- Jede RPC-Pruefung validiert Tenant + Rolle gegen Policy (`read_job`, `write_job`,
  `download_artifact` etc.).
- Artefakt- und Job-Aktionen validieren zusaetzlich die Eigentums-/Adminrechte
  gegen Tenant- und Principal-Kontext.

Besonders wichtig:

- kein unkontrollierter Raw-SQL-Filter im Proto-Vertrag
- keine Passwoerter in Response-Nachrichten
- keine unbegrenzten Streams ohne Backpressure-Regeln
- Rate Limiting pro Client/Tenant auf RPC-Ebene (konfigurierbar, z. B. ueber
  Token-Bucket oder Sliding-Window)

---

## 9. Observability

Der gRPC-Adapter sollte liefern:

- OpenTelemetry-Interceptoren
- Metriken pro RPC und Statuscode
- Tracing ueber Runner, Driver und Format-Adapter
- Job-Event-Streams mit korrelierbaren IDs
- gRPC Server Reflection (aktivierbar pro Umgebung; erleichtert Debugging mit
  `grpcurl` und `grpcui`)

---

## 10. Einfuehrungsreihenfolge

### Phase 1

- `HealthService`
- `SchemaService.ValidateSchema`
- `SchemaService.GenerateSchemaDdl`
- `JobService`
- `ArtifactService` (fuer Referenzierung und Download von Artefakten großer Ergebnisvolumen)

### Phase 2

- `SchemaService.ReverseSchema`
- `SchemaService.CompareSchema`
- `DataService.ProfileData`

### Phase 3

- `DataService.ExportData`
- `DataService.StartImport`
- `DataService.TransferData`

---

## 11. Entscheidung

gRPC ist die passende interne Schnittstelle fuer `d-migrate`, wenn mehrere
Services denselben Migrationskern nutzen sollen. Fuer externe Clients sollte
es durch REST ergaenzt werden, nicht ersetzt werden. Fuer KI-Agenten ist auch
gRPC nicht die erste Wahl; dort ist MCP die passendere Oberflaeche.
