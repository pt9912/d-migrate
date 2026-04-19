# gRPC-Service-Spezifikation: d-migrate

> Dokumenttyp: Zielbild / Spezifikation
>
> Status: Entwurf fuer die kuenftige interne Service-Schnittstelle
>
> Referenzen: `docs/architecture.md`, `docs/cli-spec.md`,
> `docs/implementation-plan-0.9.1.md`

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

### 4.1 SchemaService

Vorgeschlagene RPCs:

```proto
rpc ValidateSchema(ValidateSchemaRequest) returns (ValidateSchemaResponse);
rpc GenerateSchemaDdl(GenerateSchemaDdlRequest) returns (GenerateSchemaDdlResponse);
rpc ReverseSchema(ReverseSchemaRequest) returns (JobAccepted);
rpc CompareSchema(CompareSchemaRequest) returns (CompareSchemaResponse);
```

`ReverseSchema` wird als Job behandelt, weil die Laufzeit und Ergebnisgroesse
stark schwanken koennen.

### 4.2 DataService

Vorgeschlagene RPCs:

```proto
rpc ExportData(ExportDataRequest) returns (JobAccepted);
rpc StartImport(ImportDataRequest) returns (JobAccepted);
rpc TransferData(TransferDataRequest) returns (JobAccepted);
rpc ProfileData(ProfileDataRequest) returns (JobAccepted);
rpc UploadArtifact(stream ArtifactChunk) returns (ArtifactRef);
rpc DownloadArtifact(GetArtifactRequest) returns (stream ArtifactChunk);
```

Anmerkung:

- `UploadArtifact` uebernimmt den grossen Input-Strom in einen serverseitigen
  Artefakt-Speicher
- `StartImport` startet danach den eigentlichen Importjob ueber `artifact_id`
- `ExportData` laeuft in `v1` immer als Job und schreibt Ergebnis-Artefakte
- `DownloadArtifact` streamt grosse Ergebnisdateien kontrolliert aus
- ein direktes Import-Streaming ohne Jobmodell ist in `v1` bewusst nicht Teil
  des Vertrags

### 4.3 JobService

```proto
rpc GetJob(GetJobRequest) returns (JobStatus);
rpc CancelJob(CancelJobRequest) returns (CancelJobResponse);
rpc WatchJob(JobWatchRequest) returns (stream JobEvent);
rpc GetArtifactMetadata(GetArtifactRequest) returns (ArtifactMetadata);
```

`WatchJob` ist einer der Hauptgruende fuer gRPC: Fortschritt und Status lassen
sich sauber streamen, ohne Polling.

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
- Upload grosser Schema- oder Report-Elemente in Chunks

### 5.3 Bidirektionales Streaming

Nur dort einsetzen, wo ein echter Dialog noetig ist, z. B.:

- interaktiver Import mit Rueckmeldungen pro Chunk
- sehr spaeter, falls ein Agenten- oder Assistenzmodus per gRPC gebraucht wird

Fuer die erste Version ist bidi-Streaming nicht noetig.

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

- `ExportData`, `ReverseSchema`, `ProfileData` und grosse Compare-/Generate-
  Ergebnisse liefern nur Summary plus `artifact_id`
- Importdaten werden nicht als eingebettete Message-Bloecke in
  `StartImport` uebertragen, sondern ueber `UploadArtifact`

### 6.3 Zeit, IDs, Enums

- Timestamps als `google.protobuf.Timestamp`
- Job-IDs als stabile Strings, nicht als fortlaufende Zahlen
- Dialekte, Formate, Statuswerte als Enums

### 6.4 Lokalisierung

Strukturierte Felder bleiben sprachstabil englisch. Lokalisierte Texte werden
ueber Metadaten oder ein explizites `locale`-Feld angefordert.

---

## 7. Fehlerabbildung

gRPC-Status und fachliche Fehler muessen getrennt behandelt werden.

- Transport-/Infrastrukturfehler: gRPC Status (`UNAVAILABLE`,
  `DEADLINE_EXCEEDED`, `UNAUTHENTICATED`, `PERMISSION_DENIED`)
- fachliche Fehler: strukturierter `ErrorDetail`-Payload mit
  `exit_code`, `error_code`, `message`, `details`

Beispielhafte Zuordnung:

| CLI-Code | Fachlicher Typ | gRPC |
| --- | --- | --- |
| `2` | Request invalid | `INVALID_ARGUMENT` |
| `3` | Validation failed | `INVALID_ARGUMENT` |
| `4` | Connection failed | `UNAVAILABLE` |
| `5` | Migration failed | `INTERNAL` |
| `6` | AI provider failed | `UNAVAILABLE` |
| `7` | Local / render / file failure | `INTERNAL` |
| `130` | cancelled | `CANCELLED` |

Fuer Job-RPCs gilt zusaetzlich:

- ein erfolgreich angenommener Job liefert immer `OK` plus `JobAccepted`
- spaetere Laufzeitfehler erscheinen im `JobStatus` als fachlicher Status
  `FAILED`, nicht als nachtraeglicher Transportfehler
- `WatchJob` streamt Terminalereignisse fuer `SUCCEEDED`, `FAILED` und
  `CANCELLED`

---

## 8. Security

Pflicht fuer produktive interne Nutzung:

- TLS immer aktiv, bevorzugt mTLS
- Service Identity ueber Zertifikate oder Mesh
- Autorisierung auf RPC, ConnectionRef und Zielsystem
- Request- und Message-Size-Limits
- Deadline- und Cancellation-Unterstuetzung
- serverseitige Secret-Aufloesung

Besonders wichtig:

- kein unkontrollierter Raw-SQL-Filter im Proto-Vertrag
- keine Passwoerter in Response-Nachrichten
- keine unbegrenzten Streams ohne Backpressure-Regeln

---

## 9. Observability

Der gRPC-Adapter sollte liefern:

- OpenTelemetry-Interceptoren
- Metriken pro RPC und Statuscode
- Tracing ueber Runner, Driver und Format-Adapter
- Job-Event-Streams mit korrelierbaren IDs

---

## 10. Einfuehrungsreihenfolge

### Phase 1

- `HealthService`
- `SchemaService.ValidateSchema`
- `SchemaService.GenerateSchemaDdl`
- `JobService`

### Phase 2

- `SchemaService.ReverseSchema`
- `SchemaService.CompareSchema`
- `DataService.ProfileData`

### Phase 3

- `DataService.ExportData`
- `DataService.StartImport`
- `DataService.TransferData`
- `ArtifactService`

---

## 11. Entscheidung

gRPC ist die passende interne Schnittstelle fuer `d-migrate`, wenn mehrere
Services denselben Migrationskern nutzen sollen. Fuer externe Clients sollte
es durch REST ergaenzt werden, nicht ersetzt werden. Fuer KI-Agenten ist auch
gRPC nicht die erste Wahl; dort ist MCP die passendere Oberflaeche.
