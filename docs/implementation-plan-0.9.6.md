# Implementierungsplan: Milestone 0.9.6 - Beta: MCP-Server

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.9.6. Es setzt den Roadmap-Punkt "Beta: MCP-Server" um und
> macht `d-migrate` fuer KI-Agenten ueber das Model Context Protocol
> steuerbar.
>
> Status: Draft (2026-04-25)
> Referenzen: `docs/roadmap.md` Abschnitt "Milestone 0.9.6",
> `docs/ki-mcp.md`,
> `docs/job-contract.md`,
> `docs/architecture.md`,
> `docs/design.md`,
> `docs/rest-service.md`,
> `docs/grpc-service.md`,
> `docs/cli-spec.md`,
> `hexagon/application`,
> `hexagon/ports-read`,
> `hexagon/ports-write`,
> `hexagon/profiling`,
> `adapters/driving/cli`.

---

## 1. Ziel

Milestone 0.9.6 fuehrt einen zusaetzlichen Driving-Adapter fuer MCP ein.
Der Adapter stellt die bestehenden `d-migrate`-Faehigkeiten als
agentenfreundliche Tools und Ressourcen bereit, ohne Fachlogik in den
Adapter zu verschieben.

Der Milestone liefert konkrete Nutzerergebnisse:

- `d-migrate` kann als MCP-Server ueber `stdio` und streambares HTTP
  gestartet und von MCP-Clients initialisiert werden
- read-only Tools fuer `schema_validate`, `schema_compare` und
  `schema_generate` sind verfuegbar
- Jobs, Artefakte und bekannte Schemas koennen ueber Discovery-Tools mit
  Filterung und Paginierung gefunden werden
- langlaufende Operationen fuer Reverse und Profiling koennen
  kontrolliert und idempotent gestartet werden
- Import und Transfer sind policy-gesteuert und koennen nicht
  versehentlich ohne Freigabe ausgefuehrt werden
- grosse Eingabeartefakte koennen segmentiert, wiederaufnehmbar und mit
  SHA-256-Pruefung hochgeladen werden
- laufende Jobs koennen ueber `job_cancel` fuer eigene oder erlaubte
  Jobs abgebrochen werden
- KI-nahe Spezialtools und MCP-Prompts aus `docs/ki-mcp.md` sind
  verfuegbar und policy-/audit-gesteuert

Bewusst nicht Teil dieses Milestones:

- eine vollstaendige REST- oder gRPC-Implementierung
- freie SQL-Ausfuehrung ueber MCP
- Uebergabe von JDBC-Secrets im Prompt- oder Tool-Payload
- persistente Multi-Tenant-Produktinfrastruktur jenseits des fuer die
  Beta noetigen lokalen Stores
- SSE-basierte Notifications, Resource-Subscriptions oder
  Push-Notifications. Der MCP-konforme Streamable-HTTP-`GET`-Pfad
  bleibt trotzdem definiert: der Server liefert entweder einen
  `text/event-stream` fuer Transport-Interop oder HTTP 405, wenn keine
  serverinitiierte Stream-Kommunikation angeboten wird.

---

## 2. Ausgangslage

### 2.1 Hexagonale Basis ist vorhanden

`d-migrate` trennt Fachlogik und Adapter bereits sauber:

- `hexagon/application` enthaelt die anwendungsnahen Runner und
  CLI-orientierten Orchestrierungen
- `hexagon/ports-read` und `hexagon/ports-write` definieren
  Datenbank-, DDL- und Datenstrom-Ports
- `hexagon/profiling` enthaelt Profiling-Services
- `adapters/driving/cli` ist der bisherige primare Driving-Adapter

Der MCP-Adapter soll auf diesen bestehenden Bausteinen aufsetzen. Er
darf keine zweite Implementierung von Validate, Compare, Generate,
Reverse, Profiling, Import oder Transfer enthalten.

### 2.2 MCP-Zielbild ist spezifiziert

`docs/ki-mcp.md` beschreibt bereits:

- Tool-Gruppen fuer read-only, kontrollierte Write-Operationen und
  KI-nahe Spezialpfade
- Ressourcen fuer Jobs, Artefakte, Schemas, Profile und Diffs
- Antwortgrenzen fuer inline Tool-Resultate
- ein strukturiertes Fehler-Envelope
- Idempotency-Key- und Approval-Token-Verhalten
- segmentierte Artefakt-Uploads
- `stdio` und streambares HTTP als Transportmodi
- MCP-Prompts fuer kuratierte Agentenablaeufe
- Auth-, Policy- und Audit-Grundsaetze

0.9.6 ist die erste produktive Umsetzung dieses Zielbilds. Der Plan
uebersetzt die Spezifikation in einen konkreten Adapter-, Service- und
Testumfang.

### 2.3 Job- und Artefakt-Vertrag muss geteilt werden

Roadmap 0.9.7 und 0.9.8 planen REST und gRPC. Der MCP-Milestone darf
deshalb keine MCP-spezifischen Job- oder Artefaktmodelle fest
einbetten, die spaeter durch REST/gRPC wieder ersetzt werden muessen.

Konsequenz:

- Job-, Artefakt-, Upload-, Policy-, Idempotency- und Error-Modelle
  gehoeren in einen gemeinsamen Anwendungskern
- MCP mappt nur zwischen MCP-Tool-Payload und diesen Kernvertraegen
- REST und gRPC koennen denselben Kernvertrag spaeter wiederverwenden

---

## 3. Scope

### 3.1 In Scope

- neues Gradle-Modul `adapters:driving:mcp`
- MCP-Server-Start ueber `stdio` fuer lokale IDE-/Agenten-Integration
- streambarer HTTP-Transport fuer entfernte Agent-Plattformen
- HTTP-Auth mit Bearer-Token und optionalem mTLS-Anschluss
- OAuth-2.1-kompatible MCP-Authorization fuer HTTP mit Protected
  Resource Metadata, `WWW-Authenticate`-Challenges und Scope-
  Fehlermapping
  - in Scope ist Resource-Server-Verhalten: Token validieren,
    Protected Resource Metadata anbieten, Scopes challengen und 401/403
    korrekt mappen
- Capability-/Initialize-Vertrag fuer MCP `protocolVersion`
  `2025-11-25`; `v1` bezeichnet nur den d-migrate-spezifischen
  Tool-/Resource-Vertrag, nicht die MCP-Protokollversion
- Tool-Registry mit stabilen Namen, Beschreibungen, JSON-Schemas und
  Request-/Response-Mapping
- read-only Tools:
  - `capabilities_list`
  - `schema_validate`
  - `schema_generate`
  - `schema_compare`
  - `job_status_get`
  - `job_list`
  - `artifact_list`
  - `schema_list`
- kontrollierte Start-Tools:
  - `schema_reverse_start`
  - `schema_compare_start`
  - `data_profile_start`
- Job-Steuerung:
  - `job_cancel`
- policy-gesteuerte Datenoperationen:
  - `data_import_start`
  - `data_transfer_start`
- Artefakt-Upload:
  - `artifact_upload_init`
  - `artifact_upload`
  - `artifact_upload_abort`
  - segmentierter Upload mit `uploadSessionId`
  - Segment- und Gesamt-`SHA-256`
  - Resume bei wiederholten Segmenten derselben Session
- MCP-Ressourcen fuer:
  - Job-Metadaten und Status
  - Artefakte
  - Schemas
  - Profile
  - Diffs
  - Connection-Refs
- strukturierte Fehler-Envelopes mit den in `docs/ki-mcp.md`
  festgelegten Codes
- KI-nahe Spezialtools:
  - `procedure_transform_plan`
  - `procedure_transform_execute`
  - `testdata_plan`
- MCP-Prompts fuer kuratierte Analyse-, Transformations- und
  Testdatenablaeufe
- Limits fuer Inline-Antworten, Findings und Uploadgroessen
- Audit-Ereignisse fuer jeden Tool-Aufruf
- Unit-, Adapter- und Integrationstests fuer Tool-Contracts,
  Fehlerfaelle, Policies, Idempotenz und Uploads
- Dokumentationsnachzug in `docs/ki-mcp.md`, `docs/guide.md`,
  `docs/cli-spec.md` oder separater MCP-Startdoku, falls der konkrete
  Startbefehl oder die Konfiguration vom Zielbild abweicht

### 3.2 Bewusst nicht Teil von 0.9.6

- MCP-Tools fuer rohe Datenexports als Chat-Inline-Daten
- Tool fuer freie SQL-Ausfuehrung
- direkte JDBC-URL- oder Secret-Parameter im MCP-Tool-Payload
- eigener OAuth-/Authorization-Server, Dynamic Client Registration,
  Consent-/Client-Registration-UI, Mandanten-Admin und vollstaendige
  Remote-Server-Betriebsplattform
- produktive Langzeitpersistenz fuer Jobs und Artefakte ueber
  Prozessneustarts hinaus, sofern sie nicht bereits durch bestehende
  Infrastruktur vorhanden ist
- neue Datenbank-Treiberfaehigkeiten, die nicht fuer die MCP-Oberflaeche
  selbst erforderlich sind
- Veraenderung der CLI-Vertraege ausser Start-/Konfigurationsdoku fuer
  den MCP-Server

---

## 4. Leitentscheidungen

### 4.1 MCP ist ein Driving-Adapter, kein zweiter Anwendungskern

Verbindliche Entscheidung:

- das neue Modul heisst `adapters:driving:mcp`
- Tool-Handler delegieren an Anwendungskomponenten und Ports
- fachliche Validierung bleibt in Core/Application/Ports
- MCP-spezifischer Code enthaelt nur:
  - Tool- und Resource-Definitionen
  - JSON-Schema- und Payload-Mapping
  - Transportintegration
  - Policy-/Auth-Kontextuebergabe
  - Antwortzuschnitt fuer Agenten

Begruendung:

- REST und gRPC folgen laut Roadmap direkt nach MCP
- der gemeinsame Job-/Artefaktkern muss adapterunabhaengig bleiben
- ein schlanker Adapter reduziert das Risiko divergierender Semantik

### 4.2 `stdio` und streambares HTTP sind Beta-Transporte

Verbindliche Entscheidung:

- 0.9.6 liefert `stdio` und streambares HTTP als unterstuetzte
  Startpfade
- jeder MCP-Aufruf bekommt trotzdem einen expliziten `PrincipalContext`
- fuer `stdio` wird der Principal lokal abgeleitet:
  - aus Host-/Prozesskontext, sofern verfuegbar
  - sonst aus `DMIGRATE_MCP_STDIO_TOKEN`
  - in Testumgebungen aus einem expliziten Test-Principal
- wenn weder ein vertrauenswuerdiger Host-/Prozesskontext noch
  `DMIGRATE_MCP_STDIO_TOKEN` verfuegbar ist, liefert jeder Tool- und
  Resource-Aufruf `AUTH_REQUIRED`; ein stiller lokaler Default-
  Principal ist verboten
- fuer HTTP wird der Principal aus `Authorization`-Header oder
  aequivalentem signiertem Principalsignal abgeleitet
- HTTP-Authorization folgt fuer nicht-lokales streambares HTTP
  verpflichtend der MCP-2025-11-25-Authorization-Spezifikation:
  - fehlendes, ungueltiges oder abgelaufenes Token liefert HTTP 401
    mit `WWW-Authenticate: Bearer ...`
  - die Challenge enthaelt `resource_metadata` und, wenn bestimmbar,
    `scope`
  - unzureichende Scopes liefern HTTP 403 mit
    `WWW-Authenticate`-Scope-Challenge
  - Protected Resource Metadata wird ueber die well-known URI oder die
    im `WWW-Authenticate` referenzierte URL angeboten
  - Access Tokens duerfen nicht aus Query-Parametern gelesen werden
  - Tokens werden auf Audience/Resource des MCP-Servers validiert
- Deaktivierung von HTTP-Auth ist nur fuer lokale Tests und Demos ueber
  eine explizite unsichere Konfiguration erlaubt; entfernte HTTP-Clients
  duerfen ohne Auth nicht als Beta-fertig gelten
- Streamable HTTP implementiert die MCP-Transportregeln fuer
  `protocolVersion` `2025-11-25`:
  - JSON-RPC-Nachrichten laufen ueber einen einzelnen MCP-Endpunkt mit
    `POST` und optional `GET`
  - `POST`-Requests muessen `Accept: application/json,
    text/event-stream` enthalten
  - nach Initialize muessen HTTP-Requests den ausgehandelten
    `MCP-Protocol-Version`-Header senden
  - der Server validiert `Origin` gegen DNS-Rebinding und antwortet bei
    ungueltigem Origin mit HTTP 403
  - `MCP-Session-Id` ist optional, aber wenn der Server eine Session-ID
    vergibt, muss sie fuer Folge-Requests validiert werden
  - `GET` oeffnet entweder einen SSE-Stream mit
    `Content-Type: text/event-stream` oder liefert HTTP 405, wenn keine
    Server-initiierte Stream-Kommunikation angeboten wird
- optionales mTLS wird als konfigurierbarer Maschinen-zu-Maschinen-
  Schutz vorbereitet

Begruendung:

- lokale IDE- und Agent-Runtimes sind der wichtigste Beta-Pfad
- entfernte Agent-Plattformen brauchen denselben Tool-Vertrag ueber
  einen remote-faehigen Transport
- Tool-Vertraege, Ressourcen, Policies und Fehlercodes bleiben
  transportneutral

### 4.3 Read-only Tools duerfen inline antworten, grosse Ergebnisse nicht

Verbindliche Entscheidung:

- Tool-Responses bleiben unter `64 KiB` serialisierter Nutzdaten
- `findings` werden inline auf maximal `200` Eintraege begrenzt
- bei groesseren Ergebnissen gibt das Tool eine Summary plus
  `resourceUri` und/oder `artifactId` zurueck
- row-basierte Import-, Export- oder Transferdaten erscheinen nie
  vollstaendig inline

Begruendung:

- MCP-Clients geben Tool-Ergebnisse haeufig direkt in Modellkontext
  weiter
- grosse Resultate sind teuer, unuebersichtlich und koennen sensible
  Inhalte enthalten
- Ressourcen sind der richtige Pfad fuer gezielten Kontextabruf

### 4.4 Start-Tools sind idempotent

Verbindliche Entscheidung:

- alle `*_start`-Tools verlangen `idempotencyKey`
- Deduplizierung nutzt das Tripel:
  (`idempotencyKey`, `callerId`, `payloadFingerprint`)
- `callerId` wird serverseitig aus dem Auth-/Principal-Kontext
  abgeleitet
- `payloadFingerprint` ist ein SHA-256 ueber deterministisch
  serialisierten, normalisierten Payload ohne transiente Kontrollfelder:
  `idempotencyKey`, `approvalToken`, `clientRequestId`, `requestId`
- wiederholte Requests mit identischem Tripel geben denselben Job
  zurueck
- gleicher `idempotencyKey` mit anderem Caller oder anderem Payload
  fuehrt zu `IDEMPOTENCY_CONFLICT`

Begruendung:

- Agenten wiederholen Tool-Aufrufe haeufig nach Unterbrechungen oder
  Kontextwechseln
- langlaufende Operationen duerfen dadurch nicht mehrfach starten
- der Vertrag kann spaeter unveraendert fuer REST/gRPC genutzt werden

### 4.5 Write- und datenveraendernde Operationen sind policy-gesteuert

Verbindliche Entscheidung:

- `data_import_start` und `data_transfer_start` brauchen eine Policy-
  Freigabe
- `artifact_upload_init` ist policy-gesteuert, weil damit ein
  Eingabeartefakt fuer spaetere Datenoperationen vorbereitet wird
- `artifact_upload` nutzt danach eine serverseitige, session-scoped
  Upload-Berechtigung aus dem erfolgreichen Init-Aufruf; einzelne
  Segmente brauchen keine erneute Policy-Freigabe
- `artifact_upload_abort` fuer die eigene aktive Session braucht Tenant-,
  Principal- und Session-Owner-Pruefung, aber keine Policy-Freigabe;
  fremde oder administrative Abbrueche sind policy-gesteuert
- `schema_reverse_start` und `data_profile_start` sind ebenfalls
  policyfaehig, weil sie kostenintensiv oder sensitiv sein koennen
- wenn eine Policy-Freigabe fehlt, liefert das Tool
  `POLICY_REQUIRED` mit `approvalToken`
- bei Start-Tools muss der zweite Aufruf denselben `idempotencyKey` und
  das passende `approvalToken` enthalten
- bei nicht-asynchronen policy-pflichtigen Tools ohne
  `idempotencyKey`, z.B. `artifact_upload_init`, administrative
  `artifact_upload_abort`-Aufrufe und die KI-nahen Tools, muss der
  Client einen stabilen `approvalKey` mitsenden; das `approvalToken` wird an
  (`toolName`, `callerId`, `approvalKey`, `payloadFingerprint`)
  gebunden
- fehlt bei einer policy-pflichtigen synchronen Operation der
  `approvalKey`, liefert der Server `VALIDATION_ERROR`
- fuer `artifact_upload_init` wird der Approval-Fingerprint aus den
  Session-Metadaten gebildet (`artifactKind`, `mimeType`, `sizeBytes`,
  `checksumSha256`, Tenant, Principal, optionaler Zielkontext), nicht
  aus Segmentbytes, `segmentSha256`, `contentBase64` oder
  Segmentpositionen
- ein erfolgreicher `artifact_upload_init` wandelt die Freigabe in eine
  serverseitige Upload-Berechtigung um, die explizit an
  `uploadSessionId`, Tenant, Principal, `artifactKind`, `mimeType`,
  `sizeBytes`, `checksumSha256` und Ablaufzeit gebunden ist
- produktive oder als sensitiv markierte Verbindungen duerfen nicht
  ohne Policy-Freigabe genutzt werden
- KI-nahe Tools (`procedure_transform_plan`,
  `procedure_transform_execute`, `testdata_plan`) sind immer
  policy- und auditpflichtig

Begruendung:

- MCP wird durch autonome Agenten genutzt
- destruktive oder kostenintensive Operationen brauchen einen sichtbaren
  Kontrollpunkt
- der zweiphasige Flow passt zu Chat- und IDE-Clients

### 4.6 Artefakt-Upload ist segmentiert und pruefbar

Verbindliche Entscheidung:

- `artifact_upload_init` erzeugt vor dem ersten Segment eine Upload-
  Session aus vollstaendigen Metadaten:
  - `artifactKind`
  - `mimeType`
  - `sizeBytes`
  - Gesamt-`checksumSha256`
  - Tenant, Principal und optionaler Zielkontext aus dem Request-Kontext
  - optionaler clientseitiger Session-Kandidat nur fuer Resume-faehige
    Clients
- `artifact_upload_init` verlangt noch keine `uploadSessionId`; der
  Server erzeugt sie primaer kryptografisch als opaken Wert und gibt sie
  mit TTL und erwartetem erstem Segment zurueck
- `artifact_upload` akzeptiert grosse Inhalte nur ueber Segmente einer
  bestehenden Upload-Session
- jedes Segment enthaelt:
  - `uploadSessionId`
  - `segmentIndex`
  - `segmentOffset`
  - `segmentTotal`
  - `isFinalSegment`
  - `contentBase64`
  - `segmentSha256`
  - optional `clientRequestId`
- fuer den d-migrate-MCP-Toolvertrag `v1` werden Segmentbytes in beiden
  Standardtransporten (`stdio` und streambares HTTP) als
  `contentBase64` im `tools/call`-JSON-RPC-Argument uebertragen
- streambares HTTP nutzt weiterhin einen normalen MCP-JSON-RPC-POST;
  es gibt keinen separaten binaeren Upload-Body innerhalb des
  MCP-Standardtransports
- ein spaeterer dedizierter Nicht-MCP-Upload-Endpunkt waere eine eigene
  dokumentierte Extension und ist nicht Teil dieses 0.9.6-Vertrags
- `segmentSha256` wird ueber die decodierten Segmentbytes berechnet,
  nicht ueber den Base64-Text
- `checksumSha256` wird beim Init-Aufruf festgelegt und bei
  `isFinalSegment=true` gegen das rekonstruierte Gesamtartefakt
  validiert
- Wiederholungen desselben Segments sind idempotent
- Reihenfolge, Offset, Segmentgroesse, Segmenthash und Gesamthash
  werden serverseitig validiert
- Sessions koennen `ACTIVE`, `COMPLETED`, `EXPIRED` oder `ABORTED`
  sein
- `artifact_upload_abort` setzt die eigene aktive Session nach
  Tenant-/Principal-/Owner-Pruefung auf `ABORTED` und verwirft
  Zwischensegmente; fremde oder administrative Abbrueche brauchen Policy

Begruendung:

- stdio-MCP ist fuer grosse Dateiuebergaben empfindlich
- Agenten muessen Uploads nach Unterbrechung wiederholen koennen
- SHA-256-Pruefung verhindert still korrupte Eingabeartefakte

### 4.7 `job_cancel` ist kontrollierte Job-Steuerung

Verbindliche Entscheidung:

- `job_cancel` ist kein freier Kill-Schalter, sondern eine
  berechtigte Zustandsaenderung im gemeinsamen Jobmodell
- laufende Worker muessen ein `CancellationToken` oder einen
  aequivalenten Job-Worker-Handle beobachten
- langlaufende Pfade fuer Reverse, Profiling, Import und Transfer
  muessen kooperative Cancel-Checkpoints setzen
- nach angenommenem Cancel duerfen keine neuen Artefakte publiziert und
  keine weiteren Daten-Schreiboperationen begonnen werden
- ein Principal darf nur eigene Jobs abbrechen; Administratoren duerfen
  Jobs innerhalb desselben Tenants abbrechen
- Cancel fuer fremde Tenants liefert `TENANT_SCOPE_DENIED`
- Cancel fuer nicht erlaubte Jobs liefert `FORBIDDEN_PRINCIPAL`
- bereits terminale Jobs bleiben terminal und werden nicht in
  `cancelled` umgeschrieben
- erfolgreich angenommene Abbrueche enden im Jobstatus `cancelled`
  gemaess `docs/job-contract.md`

Begruendung:

- `docs/ki-mcp.md` fuehrt `job_cancel` als kontrolliertes Write-Tool
  auf
- Abbruch ist sicherheitsrelevant, weil Agenten sonst fremde oder
  produktive Laeufe stoeren koennten

### 4.8 KI-nahe Tools und Prompts sind Teil von 0.9.6

Verbindliche Entscheidung:

- `procedure_transform_plan`, `procedure_transform_execute` und
  `testdata_plan` werden als MCP-Tools registriert und getestet
- diese Tools nutzen denselben Policy-, Audit-, Resource- und
  Fehlervertrag wie andere kontrollierte Tools
- `procedure_transform_execute` darf nur mit expliziter Freigabe
  laufen und muss Modell-/Provider-Metadaten auditierbar machen
- `testdata_plan` plant Testdaten, schreibt aber keine produktiven
  Daten ohne separates datenveraenderndes Tool
- MCP-Prompts werden als kuratierte Vorlagen fuer Analyse-,
  Transformations- und Testdatenablaeufe registriert
- Prompt-Inputs muessen auf Ressourcen, Artefakte und verdichtete
  Summaries referenzieren; Secrets oder rohe Massendaten sind verboten

Begruendung:

- die Roadmap verlangt fuer 0.9.6 die vollstaendige Umsetzung von
  `docs/ki-mcp.md`
- KI-nahe Tools sind besonders sensibel und duerfen deshalb nicht als
  freie Modellaufrufe ausserhalb von Policy und Audit entstehen

### 4.9 d-migrate-Jobs bleiben das Async-Modell

Verbindliche Entscheidung:

- 0.9.6 nutzt fuer fachliche Langlaeufer strikt das bestehende
  d-migrate-Jobmodell aus `docs/job-contract.md`
- experimentelle oder optionale MCP-Tasks aus der MCP-Spezifikation
  werden in 0.9.6 nicht parallel zu `job_*` eingefuehrt
- MCP-Cancel-Notifications fuer einzelne JSON-RPC-Requests ersetzen
  nicht `job_cancel`; sie koennen nur das Warten auf eine konkrete
  MCP-Antwort beenden

Begruendung:

- ein zweites Async-Modell wuerde REST/gRPC/MCP auseinanderlaufen
  lassen
- `job_status_get`, `job_list` und `job_cancel` bleiben damit die
  einheitliche Steuerflaeche fuer Agenten und spaetere APIs

---

## 5. Zielarchitektur

### 5.1 Modulstruktur

Neue und zu erweiternde Bereiche:

- `settings.gradle.kts`
  - `include("adapters:driving:mcp")`
- `adapters/driving/mcp`
  - MCP-Transporte fuer `stdio` und streambares HTTP
  - Runtime-Bootstrap fuer Driver, Codecs und optionale Integrationen
  - Tool-Registry
  - Resource-Registry
  - JSON-Schema-Mapping
  - Handler fuer Tool-Aufrufe
  - Adaptertests
- `hexagon/application`
  - gemeinsamer Job-Service
  - Artefakt-Service
  - Upload-Session-Service
  - Idempotency-Service
  - Policy-Service-Schnittstelle
  - Audit-Service-Schnittstelle
- `hexagon/ports-common` oder `hexagon/application`
  - gemeinsame Principal-, Error-, Pagination- und Resource-URI-Modelle
- bestehende Adapter und Ports
  - werden nur angebunden, nicht fachlich dupliziert

Die genaue Paketgrenze wird beim Coding nach bestehenden
Modulabhaengigkeiten entschieden. Die Leitlinie bleibt: gemeinsame
Vertraege gehoeren nicht in `adapters/driving/mcp`.

### 5.2 Kernmodelle

`docs/job-contract.md` ist fuer Jobs und Artefakte normativ. 0.9.6
fuehrt deshalb keinen abweichenden MCP-spezifischen Job- oder
Artefaktvertrag ein. Der MCP-Adapter darf diese Modelle nur
protokollspezifisch projizieren, z.B. durch `resourceUri`,
`executionMeta` oder gekuerzte Summaries.

Erwartete Kernmodelle und Projektionen:

- `PrincipalContext`
  - `principalId`
  - `tenantId`
  - Rollen oder Scopes
  - `isAdmin`
- `ServerResourceUri`
  - typisierte Abbildung von
    `dmigrate://tenants/{tenantId}/...`
- `ConnectionReference`
  - `connectionId`
  - `tenantId`
  - non-secret Anzeigename und Dialekt
  - Sensitivitaets-/Produktionsklassifizierung fuer Policy
  - Verweis auf serverseitig verwaltetes Secret oder lokalen
    Connection-Provider, nie das Secret selbst
- `ApprovalGrant`
  - `approvalKey`
  - `approvalTokenFingerprint`
  - `toolName`
  - `callerId`
  - `payloadFingerprint`
  - `expiresAt`
- `ManagedJob` gemaess `docs/job-contract.md`
  - `jobId`
  - `operation`
  - `status`
  - `createdAt`
  - `updatedAt`
  - `expiresAt`
  - `createdBy`
  - `artifacts`
  - optional `error`
  - optional `progress`
- MCP-Job-Projektion
  - tenant-scoped `resourceUri`
  - gekuerzte Summary fuer Tool-Responses
  - `executionMeta.requestId`
- `ManagedArtifact` gemaess `docs/job-contract.md`
  - `artifactId`
  - `filename`
  - `contentType`
  - `sizeBytes`
  - `sha256`
  - `createdAt`
  - `expiresAt`
- MCP-Artefakt-Projektion
  - tenant-scoped `resourceUri`
  - optionale MCP-Metadaten wie erlaubter Upload-Typ oder
    Ergebnis-Kategorie, ohne den Kernvertrag umzubenennen
- `UploadSession`
  - `uploadSessionId`
  - `tenantId`
  - `status`
  - erwartete Gesamtgroesse
  - erwarteter Gesamt-Hash
  - Segmentstatus
  - TTL
- `ToolExecutionMeta`
  - `requestId`
  - `jobId`
  - `artifactId`
  - `createdAt`
- `ToolErrorEnvelope`
  - `code`
  - `message`
  - `details`
  - `requestId`
  - `retryable`
- `McpToolErrorResult`
  - MCP-Tool-Result mit `isError=true`
  - `structuredContent.error.code` fuer die stabilen
    d-migrate-Fehlercodes
  - kurze menschenlesbare Diagnose in `content`
- `McpResourceError`
  - JSON-RPC-Error fuer `resources/read` und andere Resource-Requests
  - numerisches MCP/JSON-RPC-`error.code`
  - d-migrate-Fehlercode in `error.data.dmigrateCode`

### 5.3 Stores fuer die Beta

0.9.6 braucht einen klaren, testbaren Store-Vertrag:

- `JobStore`
- `ArtifactStore`
- `SchemaStore`
- `ProfileStore`
- `DiffStore`
- `UploadSessionStore`
- `ConnectionReferenceStore`
- `IdempotencyStore`
- `ApprovalGrantStore`
- `AuditSink`

Schemas, Profile und Diffs duerfen intern als typisierte Artefakte
persistiert werden. Trotzdem braucht 0.9.6 je Typ eine explizite
Store-/Index-Abstraktion, damit `schema_list`, Resource-Resolver,
Tenant-Scope-Pruefung, Paginierung und Retention nicht von
dateispezifischen Artefaktdetails abhaengen.

Connection-Refs werden ausserhalb von MCP registriert. Fuer die Beta
muss der Store aus mindestens einer klaren Bootstrap-Quelle befuellt
werden:

- bestehende CLI-/Projektkonfiguration wie `.d-migrate.yaml` oder
  aequivalente lokale Konfigurationsdateien
- serverseitiger Credential-/Connection-Provider, falls vorhanden
- Test-/Demo-Seeds fuer Integrationstests ohne echte Secrets

Der MCP-Adapter darf keine freien Connection-Secrets im Tool-Payload
annehmen. Connection-Bootstrap liest nur non-secret Metadaten direkt;
Secrets bleiben im bestehenden Credential-Store oder Provider.

Beta-Default:

- lokaler In-Memory-Store fuer Tests und kurzlebige `stdio`-Server
- optional dateibasierter Store unter einem konfigurierbaren
  Arbeitsverzeichnis, falls vorhandene Infrastruktur das einfach
  erlaubt

Nicht erlaubt:

- Tool-Handler halten globale mutable Maps ohne Store-Abstraktion
- Artefakte liegen dauerhaft in Prompt- oder Tool-Response-Strukturen
- Secrets werden im Store persistiert

### 5.4 MCP-Tool-Pipeline

Jeder Tool-Aufruf laeuft logisch durch dieselbe Pipeline:

1. `requestId` erzeugen oder uebernehmen
2. Audit-Scope fuer diesen Aufruf oeffnen
3. Principal aus Transportkontext ableiten
4. Tool-Payload gegen Tool-Schema validieren
5. Tenant- und Resource-Scopes pruefen
6. bestehenden Idempotency-Treffer fuer Start-Tools pruefen, falls nach
   Auth, Schema-Validierung und Scope-Pruefung eindeutig bestimmbar
7. Policy pruefen, falls kein bestehender Idempotency-Treffer vorliegt
   oder der Idempotency-Key mit anderem Payload/Caller kollidiert
8. Driver, Codecs und benoetigte Adapter registrieren bzw. verfuegbar
   machen
9. Anwendung ausfuehren, Upload-Session fortsetzen oder Job anlegen
10. Ergebnis zuschneiden und Limits anwenden
11. Artefakte/Ressourcen referenzieren
12. Erfolg oder Fehler als einheitliches Envelope liefern
13. Audit-Scope in einem `finally`-Pfad mit Outcome abschliessen

Verbindliche Audit-Semantik:

- ein `requestId` existiert vor Auth-, Schema-, Scope-, Policy- oder
  Idempotency-Pruefungen
- identische Start-Tool-Retries mit bestehendem Idempotency-Treffer geben
  nach Auth, Schema-Validierung und Scope-Pruefung denselben Job zurueck,
  auch wenn ein frueherer Approval-Grant inzwischen abgelaufen oder
  entzogen ist
- Policy wird fuer neue Starts und fuer Idempotency-Konflikte geprueft,
  nicht fuer reine Deduplizierungsantworten
- auch `AUTH_REQUIRED`, `VALIDATION_ERROR`, `TENANT_SCOPE_DENIED`,
  `POLICY_REQUIRED` und `IDEMPOTENCY_CONFLICT` werden auditierbar
  abgeschlossen
- wenn kein Principal abgeleitet werden kann, enthaelt das
  Audit-Ereignis keinen erfundenen Principal, sondern nur `requestId`,
  Transport, Toolname soweit erkennbar und Fehlercode `AUTH_REQUIRED`
- rohe Secrets, rohe Uploadinhalte und rohe Approval-Tokens duerfen auch
  im Fehlerpfad nicht im Audit landen

Fehler in den Schritten 3 bis 11 duerfen nicht als rohe Exception an
den MCP-Client gelangen. Sie werden auf die standardisierten
Fehlercodes gemappt.

### 5.5 Runtime-Bootstrap fuer Driver und Codecs

Der MCP-Adapter muss denselben Runtime-Bootstrap wie die CLI
ausfuehren, bevor ein Tool fachliche Arbeit startet.

Verbindliche Regeln:

- `DatabaseDriverRegistry.loadAll()` oder eine gemeinsame
  Bootstrap-Funktion wird beim MCP-Serverstart ausgefuehrt
- `adapters:driving:mcp` bringt die noetigen driven Driver-,
  Profiling-, Streaming- und Format-/Codec-Module als
  Runtime-Dependencies mit
- der Bootstrap ist testbar gekapselt, damit Tests ohne Prozess-Exit
  pruefen koennen, dass PostgreSQL-, MySQL-, SQLite-Driver und
  Schema-Codecs gefunden werden
- Tool-Handler duerfen Driver nicht lazy per Klassenname oder
  ad-hoc-Reflection nachladen

Akzeptanz:

- ein MCP-Integrationstest startet den echten MCP-Bootstrap und fuehrt
  danach mindestens einen Driver-Lookup und einen Schema-Codec-Lookup
  aus
- `schema_generate`, `schema_reverse_start`, Import und Transfer laufen
  im Testpfad nicht gegen eine manuell injizierte Spezialregistry,
  sondern gegen die normale Runtime-Registry

### 5.6 Ressourcen

0.9.6 implementiert mindestens diese Resource-URI-Formen:

- `dmigrate://tenants/{tenantId}/jobs/{jobId}`
- `dmigrate://tenants/{tenantId}/artifacts/{artifactId}`
- `dmigrate://tenants/{tenantId}/schemas/{schemaId}`
- `dmigrate://tenants/{tenantId}/profiles/{profileId}`
- `dmigrate://tenants/{tenantId}/diffs/{diffId}`
- `dmigrate://tenants/{tenantId}/connections/{connectionId}`
- `dmigrate://capabilities`

Verbindliche Regeln:

- `tenantId` ist adressierend, aber nicht autorisierend
- Zugriff wird immer gegen `PrincipalContext` geprueft
- Fremdzugriff liefert `TENANT_SCOPE_DENIED`
- Connection-Ressourcen enthalten keine Secrets
- Connection-Ressourcen werden ueber `ConnectionReferenceStore`
  aufgeloest und enthalten Policy-Metadaten wie Dialekt, Owner,
  Sensitivitaet und Produktionsklassifizierung
- nicht existente Ressourcen liefern `RESOURCE_NOT_FOUND`
- Resource-Fehler werden nicht als `tools/call`-Result mit
  `isError=true` modelliert. `resources/read` und andere Resource-
  Requests liefern JSON-RPC-Errors; `RESOURCE_NOT_FOUND` wird dabei
  z.B. auf JSON-RPC `error.code=-32002` gemappt und der stabile
  d-migrate-Code steht in `error.data.dmigrateCode`.
- grosse Ressourcen werden chunkbar oder als Artefakt-Referenz
  bereitgestellt

---

## 6. Tool-Vertraege

### 6.1 `capabilities_list`

Zweck:

- Dialekte, Formate, Features, verfuegbare Tools und Limits anzeigen

Pflichtfelder der Antwort:

- `summary`
- `dialects`
- `formats`
- `tools`
- `limits`
- `executionMeta`

Akzeptanz:

- Tool funktioniert ohne Datenbankverbindung
- Secrets oder lokale Pfade werden nicht offengelegt
- Antwort bleibt stabil genug fuer Client-Discovery

### 6.2 `schema_validate`

Zweck:

- neutrales Schema aus `schemaRef` oder kleinem Inline-Payload validieren

Verbindliche Eingaben:

- genau eine Quelle:
  - `schemaRef`
  - `schema`
- optionale Format-/Strictness-Optionen

Antwort:

- `summary`
- bis zu 200 Findings
- bei mehr Findings `artifactId` oder `resourceUri`

Akzeptanz:

- ungueltiges Schema liefert strukturiertes Validierungsergebnis, nicht
  `INTERNAL_AGENT_ERROR`
- zu grosse Inline-Payloads liefern `PAYLOAD_TOO_LARGE`

### 6.3 `schema_generate`

Zweck:

- DDL fuer ein Zielsystem aus neutralem Schema erzeugen

Verbindliche Eingaben:

- `schemaRef`
- `targetDialect`
- optionale DDL-Optionen

Antwort:

- Summary
- bei kleinem DDL optional inline
- bei groesserem DDL `artifactId` und `resourceUri`

Akzeptanz:

- vorhandene Generatorwarnungen werden als Findings oder Notes
  strukturiert sichtbar
- DDL wird nicht abgeschnitten, sondern als Artefakt referenziert

### 6.4 `schema_compare` und `schema_compare_start`

Zweck:

- zwei bereits materialisierte Schemas synchron vergleichen oder einen
  connection-backed Vergleich als Job starten

Verbindliche Eingaben:

`schema_compare`:

- `left.schemaRef`
- `right.schemaRef`
- optionale Compare-Optionen

`schema_compare_start`:

- `left` und `right`, jeweils als `schemaRef` oder `connectionRef`
- `idempotencyKey`
- optionale Compare-Optionen
- optional `approvalToken`, falls eine Seite eine policy-pflichtige
  oder sensitive `connectionRef` nutzt

Antwort:

`schema_compare`:

- Summary
- Findings bis Limit
- Diff-Resource oder Artefakt bei groesserem Ergebnis

`schema_compare_start`:

- `jobId`
- `resourceUri`
- `executionMeta`

Akzeptanz:

- `schema_compare` akzeptiert keine `connectionRef`; connection-backed
  Vergleiche muessen `schema_compare_start` nutzen
- bei `schema_compare_start` muessen Connection-Refs tenant-scoped
  `dmigrate://.../connections/...` sein
- freie JDBC-Strings werden mit `VALIDATION_ERROR` abgewiesen
- bei sensitiven oder produktiven Connection-Refs ist der async
  Policy-Flow verbindlich:
  - ohne `idempotencyKey` liefert das Tool `IDEMPOTENCY_KEY_REQUIRED`
  - ohne gueltiges `approvalToken` liefert das Tool `POLICY_REQUIRED`
  - das Token ist an `idempotencyKey`, Caller und `payloadFingerprint`
    gebunden
- `job_cancel` kann einen laufenden `schema_compare_start`-Job
  abbrechen
- Reverse-Warnungen der Operanden bleiben als Diagnose sichtbar

### 6.5 Discovery-Tools

Tools:

- `job_status_get`
- `job_list`
- `artifact_list`
- `schema_list`

Gemeinsame Regeln:

- Filter sind allowlist-basiert
- Paginierung nutzt `limit` und `cursor`
- `limit` hat ein serverseitiges Maximum
- Antworten enthalten stabil `items`, `nextCursor` und `totalCount`
- wenn ein Store die exakte Gesamtzahl intern nicht billig bestimmen
  kann, muss er fuer 0.9.6 trotzdem eine konsistente Zaehllogik fuer
  die gefilterte Ergebnisliste bereitstellen; eine spaetere Lockerung
  muesste zuerst `docs/ki-mcp.md` aendern

Akzeptanz:

- fremde Tenant-Ressourcen sind nicht sichtbar
- Cursor sind opak
- ungueltige Cursor liefern `VALIDATION_ERROR`

### 6.6 Async-Start-Tools

Tools:

- `schema_reverse_start`
- `data_profile_start`

Gemeinsame Regeln:

- `idempotencyKey` ist Pflicht
- Verbindungen werden nur ueber `connectionRef` referenziert
- fuer policy-pflichtige Verbindungen wird das `approvalToken` an
  denselben `idempotencyKey` und Payload-Fingerprint gebunden
- Start liefert `jobId`, `resourceUri` und `executionMeta`
- Fortschritt wird ueber `job_status_get` abgefragt

Akzeptanz:

- fehlender `idempotencyKey` liefert `IDEMPOTENCY_KEY_REQUIRED`
- identische Wiederholung liefert denselben Job
- Konflikte liefern `IDEMPOTENCY_CONFLICT`

### 6.7 Datenoperationen

Tools:

- `data_import_start`
- `data_transfer_start`

Gemeinsame Regeln:

- immer idempotent
- `idempotencyKey` ist Pflicht
- Deduplizierung nutzt denselben Payload-Fingerprint-Vertrag wie
  andere Start-Tools; `approvalToken` ist dabei ausgeschlossen
- immer policy-gesteuert
- Import nutzt zuvor hochgeladenes `artifactId`
- Transfer nutzt tenant-scoped Source- und Target-Connection-Refs
- kein rohes SQL im Tool-Payload

Akzeptanz:

- fehlender `idempotencyKey` liefert `IDEMPOTENCY_KEY_REQUIRED`
- identische Wiederholung liefert denselben Job
- gleicher `idempotencyKey` mit anderem Payload liefert
  `IDEMPOTENCY_CONFLICT`
- fehlende Freigabe liefert `POLICY_REQUIRED`
- zweiter Aufruf mit passendem Token startet oder dedupliziert den Job
- ungueltige oder fremde Artefakte liefern `RESOURCE_NOT_FOUND` oder
  `TENANT_SCOPE_DENIED`

### 6.8 Artefakt-Upload

Tools:

- `artifact_upload_init`
- `artifact_upload`
- `artifact_upload_abort`

Gemeinsame Regeln:

- `artifact_upload_init` ist policy-gesteuert
- fehlende Freigabe fuer `artifact_upload_init` liefert
  `POLICY_REQUIRED`
- der Approval-Flow darf Segmentvalidierung und Hashpruefung nicht
  umgehen
- `approvalKey` ist fuer `artifact_upload_init` Pflicht
- beim zweiten Init-Aufruf muss der Client dasselbe `approvalKey` und
  das passende `approvalToken` senden
- das Init-`approvalToken` ist an `toolName`, `callerId`,
  `approvalKey` und den session-metadatenbezogenen
  `payloadFingerprint` gebunden
- nach erfolgreichem Init legt der Server eine session-scoped
  Upload-Berechtigung an; sie ist an `uploadSessionId`, Tenant,
  Principal, `artifactKind`, `mimeType`, `sizeBytes`,
  `checksumSha256`, Approval-Fingerprint und Ablaufzeit gebunden
- `artifact_upload` prueft `uploadSessionId`, Tenant, Principal,
  Session-Owner, Session-Status und die session-scoped
  Upload-Berechtigung statt pro Segment eine neue Policy-Freigabe zu
  verlangen
- `artifact_upload_abort` fuer die eigene aktive Session prueft
  `uploadSessionId`, Tenant, Principal und Session-Owner, aber kein
  `approvalKey`; administrative oder fremde Abbrueche bleiben
  policy-gesteuert und brauchen `approvalKey`/`approvalToken`
- `artifact_upload_init` ist der einzige Aufruf, der eine neue
  Upload-Session erzeugt; `artifact_upload` und `artifact_upload_abort`
  referenzieren immer eine bestehende `uploadSessionId`
- Segmentdaten, `segmentSha256`, `contentBase64`, `segmentIndex` und
  `segmentOffset` sind nicht Teil des
  Approval-Fingerprints; sie werden separat pro Segment validiert
- Wiederverwendung desselben Tokens mit anderen Session-Metadaten oder
  mit einer anderen `uploadSessionId` liefert `POLICY_REQUIRED` oder
  `FORBIDDEN_PRINCIPAL`

Verbindliche Validierungen:

- `artifactKind` ist allowlist-basiert
- `mimeType` ist allowlist-basiert
- `sizeBytes` ist Pflicht
- Gesamt-`checksumSha256` ist bereits fuer `artifact_upload_init`
  Pflicht und Teil des Approval-Fingerprints
- maximale Uploadgroesse ist `200 MiB`
- `uploadSessionId` ist fuer `artifact_upload_init` nicht Pflicht, fuer
  `artifact_upload` und `artifact_upload_abort` aber verbindlich und
  Bestandteil jeder session-scoped Upload- oder Abort-Berechtigung
- serverseitig erzeugte `uploadSessionId` ist 36 Zeichen lang, wird
  kryptografisch sicher erzeugt und als opaker Wert behandelt
- falls ein Client fuer Resume einen Session-Kandidaten sendet, muss er
  UUID-kompatibel bzw. opak validierbar sein, atomar kollisionsfrei
  angelegt werden und an Tenant, Principal, `approvalKey`,
  Approval-Fingerprint, `artifactKind`, `mimeType`, `sizeBytes` und
  `checksumSha256` gebunden werden
- Wiederverwendung einer Session-ID mit anderem Tenant, Principal,
  Approval-Fingerprint oder anderen Session-Metadaten liefert
  `VALIDATION_ERROR` oder `FORBIDDEN_PRINCIPAL`
- `artifact_upload_abort` ohne Session-Owner-Recht liefert
  `FORBIDDEN_PRINCIPAL`; administrative Abbrueche ohne Policy-Freigabe
  liefern `POLICY_REQUIRED`
- `segmentIndex` beginnt bei `1` und ist fortlaufend
- `segmentOffset` ist der Byte-Offset in der vollstaendigen
  Artefakt-Bytefolge
- `contentBase64` ist fuer jedes nicht-leere Segment Pflicht, sowohl
  bei `stdio` als auch bei streambarem HTTP
- maximale Segmentgroesse nach Base64-Decoding: `4 MiB`
- `segmentOffset` und `sizeBytes` beziehen sich auf decodierte Bytes
- jedes Segment braucht `segmentSha256`
- `segmentSha256` ist SHA-256 ueber die decodierten Bytes aus
  `contentBase64`
- `checksumSha256` ist SHA-256 ueber die vollstaendige rekonstruierte
  Artefakt-Bytefolge und muss dem in `artifact_upload_init` registrierten
  Wert entsprechen
- nach erfolgreichem Abschluss wird daraus ein Artefakt gemaess
  `docs/job-contract.md`; `mimeType` wird zu `contentType` und
  `checksumSha256` zu `sha256` gemappt

Antwort:

- `artifact_upload_init` liefert `uploadSessionId`, Uploadstatus,
  erwarteten ersten `segmentIndex`, erwarteten ersten `segmentOffset` und
  `uploadSessionTtlSeconds`
- jede erfolgreiche Segmentannahme liefert `uploadSessionId`,
  Uploadstatus, naechsten erwarteten `segmentIndex` oder
  `segmentOffset` und `uploadSessionTtlSeconds`
- Initial-TTL ist `900`, Minimum `300`, Maximum `3600`
- bei `300` Sekunden ohne Aktivitaet wird die Session `EXPIRED` und
  Zwischensegmente werden verworfen

Akzeptanz:

- erster Upload-Pfad ohne vorheriges `artifact_upload_init` liefert
  `RESOURCE_NOT_FOUND` oder `VALIDATION_ERROR`
- wiederholtes identisches Segment ist erfolgreich oder liefert den
  bestehenden Segmentstatus
- Wiederholung mit gleicher Segmentposition, aber anderem Inhalt oder
  Hash liefert `VALIDATION_ERROR`
- abweichender Hash fuer ein bereits angenommenes Segment liefert
  `VALIDATION_ERROR`
- zu grosse Uploads liefern `PAYLOAD_TOO_LARGE`
- abgebrochene Sessions liefern `UPLOAD_SESSION_ABORTED`
- abgelaufene Sessions liefern `UPLOAD_SESSION_EXPIRED`

### 6.9 `job_cancel`

Zweck:

- einen laufenden oder wartenden Job kontrolliert abbrechen

Verbindliche Eingaben:

- `jobId` oder tenant-scoped Job-`resourceUri`
- optionaler menschenlesbarer `reason`

Antwort:

- aktueller Jobstatus gemaess `docs/job-contract.md`
- `executionMeta` mit `requestId`
- optional `resourceUri` des Jobs

Akzeptanz:

- nur eigene oder explizit erlaubte Jobs koennen abgebrochen werden
- fremde Tenant-Jobs liefern `TENANT_SCOPE_DENIED`
- nicht erlaubte Jobs liefern `FORBIDDEN_PRINCIPAL`
- unbekannte Jobs liefern `RESOURCE_NOT_FOUND`
- bereits terminale Jobs bleiben unveraendert terminal und liefern
  ihren aktuellen Status
- erfolgreich angenommene Abbrueche erzeugen Status `cancelled`

### 6.10 KI-nahe Spezialtools

Tools:

- `procedure_transform_plan`
- `procedure_transform_execute`
- `testdata_plan`

Gemeinsame Regeln:

- alle drei Tools sind policy-gesteuert
- `approvalKey` ist fuer alle drei Tools Pflicht
- beim zweiten Aufruf muss der Client dasselbe `approvalKey` und das
  passende `approvalToken` senden
- das `approvalToken` ist an `toolName`, `callerId`, `approvalKey` und
  `payloadFingerprint` gebunden
- Wiederverwendung desselben Tokens mit anderem Payload liefert
  `POLICY_REQUIRED` oder `FORBIDDEN_PRINCIPAL`
- alle drei Tools schreiben Audit-Ereignisse mit Toolname,
  Principal, Tenant, Resource-Refs und Payload-Fingerprint
- Eingaben nutzen `schemaRef`, `artifactRef`, `profileRef`,
  `connectionRef` oder kleine strukturierte Inline-Optionen
- Secrets, freie JDBC-Strings und rohe Massendaten sind unzulaessig
- grosse Planungs- oder Transformationsresultate werden als Artefakte
  oder Ressourcen referenziert
- bei Prompt- oder Datenhygiene-Verletzungen gilt
  `PROMPT_HYGIENE_BLOCKED`

`procedure_transform_plan`:

- erstellt einen Analyse- und Transformationsplan fuer eine gespeicherte
  Prozedur oder ein Prozedur-Artefakt
- darf ohne expliziten Execute-Schritt keinen Ziel-DB-Code als
  auszufuehrendes Artefakt freigeben
- liefert Summary, Findings und ggf. Plan-Artefakt

`procedure_transform_execute`:

- fuehrt eine freigegebene Prozedurtransformation aus
- ist bestaetigungspflichtig und policy-gesteuert
- muss Provider, Modell, Modellversion, Prompt-/Payload-Fingerprint und
  Zielartefakt im Audit-Trail erfassen
- darf keine Secrets oder unredigierten Massendaten an externe Provider
  geben

`testdata_plan`:

- erzeugt einen Testdatenplan aus Schema, Regeln und optionalen
  Profil-Summaries
- erzeugt keine produktiven Datenbank-Schreiboperationen
- groessere Plaene werden als Artefakt oder Ressource referenziert

Akzeptanz:

- fehlender `approvalKey` liefert `VALIDATION_ERROR`
- fehlende Freigabe liefert `POLICY_REQUIRED`
- unpassendes oder fuer anderen Payload ausgestelltes `approvalToken`
  liefert `POLICY_REQUIRED` oder `FORBIDDEN_PRINCIPAL`
- externe Provider-Nutzung ohne erlaubende Policy liefert
  `PROMPT_HYGIENE_BLOCKED` oder `FORBIDDEN_PRINCIPAL`
- Resultate enthalten keine Secrets und keine unlimitierten Rohdaten
- alle Tool-Aufrufe sind auditierbar

### 6.11 MCP-Prompts

Zweck:

- kuratierte Analyse-, Transformations- und Testdatenablaeufe fuer
  Agenten auffindbar machen

Verbindliche Prompts:

- Procedure-Analyse auf Basis von Schema-/Artefaktreferenzen
- Procedure-Transformation mit explizitem Policy-Hinweis
- Testdatenplanung auf Basis von Schema, Regeln und Profil-Summaries

Regeln:

- Prompts sind Vorlagen, keine versteckten Tool-Ausfuehrungen
- Prompts verweisen auf MCP-Tools und Ressourcen, statt grosse Inhalte
  direkt in den Kontext zu kopieren
- Prompt-Parameter sind schema-validiert und duerfen keine Secrets
  enthalten
- Prompt-Definitionen muessen versioniert oder stabil benannt sein,
  damit Clients sie referenzieren koennen

Akzeptanz:

- Prompts sind ueber MCP-Discovery sichtbar
- jeder Prompt nennt die erforderlichen Resource-Refs und die
  erwarteten Tool-Schritte
- unzulaessige Parameter liefern `VALIDATION_ERROR`
- Prompt-Hygiene-Verletzungen liefern `PROMPT_HYGIENE_BLOCKED`

---

## 7. Fehler- und Audit-Vertrag

### 7.1 Fehlercodes

0.9.6 implementiert mindestens:

- `AUTH_REQUIRED`
- `FORBIDDEN_PRINCIPAL`
- `POLICY_REQUIRED`
- `IDEMPOTENCY_CONFLICT`
- `IDEMPOTENCY_KEY_REQUIRED`
- `RESOURCE_NOT_FOUND`
- `VALIDATION_ERROR`
- `RATE_LIMITED`
- `OPERATION_TIMEOUT`
- `PAYLOAD_TOO_LARGE`
- `UPLOAD_SESSION_EXPIRED`
- `UPLOAD_SESSION_ABORTED`
- `UNSUPPORTED_MEDIA_TYPE`
- `UNSUPPORTED_TOOL_OPERATION`
- `PROMPT_HYGIENE_BLOCKED`
- `TENANT_SCOPE_DENIED`
- `INTERNAL_AGENT_ERROR`

MCP-Wire-Mapping:

- Protokollfehler bleiben JSON-RPC-Errors mit numerischem
  `error.code`, z.B. parse error, invalid request, unknown method,
  unbekannter Toolname in `tools/call` oder ein Request, der das
  MCP-Schema nicht erfuellt
- fachliche Tool-Ausfuehrungsfehler werden als normales
  `tools/call`-Result mit `isError=true` zurueckgegeben
- Resource-Request-Fehler werden als JSON-RPC-Errors zurueckgegeben,
  nicht als Tool-Result. `RESOURCE_NOT_FOUND` nutzt fuer
  `resources/read` den JSON-RPC-Code `-32002`; der stabile
  d-migrate-Code steht in `error.data.dmigrateCode`.
- die stabilen d-migrate-Fehlercodes stehen dabei nicht im numerischen
  JSON-RPC-`error.code`, sondern bei Toolfehlern in
  `structuredContent.error.code` und bei Resource-Fehlern in
  `error.data.dmigrateCode`
- `structuredContent.error` enthaelt mindestens `code`, `message`,
  optional `details`, `requestId` und `retryable`
- `content` enthaelt nur eine kurze, bereinigte menschliche Diagnose
  und keine Secrets, Rohdaten oder Approval-Tokens
- `UNSUPPORTED_TOOL_OPERATION` bleibt ein fachlicher Fehlercode fuer
  bekannte d-migrate-Tools, die eine nicht unterstuetzte fachliche
  Operation oder Option anfordern; ein unbekannter Toolname wird dagegen
  als JSON-RPC-/MCP-Protokollfehler beantwortet

Mapping-Regeln:

- Nutzer- oder Payloadfehler werden nicht als
  `INTERNAL_AGENT_ERROR` gemeldet
- Policy- und Tenant-Verletzungen enthalten keine Details, die fremde
  Ressourcen bestaetigen oder verraten
- technische Exceptions bekommen eine `requestId` und werden geloggt,
  aber im Tool-Response bereinigt

### 7.2 Audit

Jeder Tool-Aufruf erzeugt mindestens ein Audit-Ereignis:

- `requestId`
- `toolName`
- `principalId`, falls ableitbar
- `tenantId`, falls ableitbar
- Zeitpunkt
- normalisierter Payload-Fingerprint
- betroffene Resource-IDs
- Ergebnisstatus
- Fehlercode, falls vorhanden

Audit ist ein Around-/Finally-Vertrag:

- der Audit-Scope wird direkt nach Erzeugung von `requestId`
  begonnen
- der Outcome wird auch bei fruehen Fehlern wie `AUTH_REQUIRED`,
  `VALIDATION_ERROR`, `POLICY_REQUIRED`, `TENANT_SCOPE_DENIED` oder
  `IDEMPOTENCY_CONFLICT` geschrieben
- ein fehlender Principal wird als Auth-Fehler protokolliert, aber nie
  durch einen Default-Principal ersetzt

Nicht im Audit-Event:

- Secrets
- komplette Artefaktinhalte
- grosse DDL-/Diff-/Daten-Payloads

Fuer policy-gesteuerte Operationen wird zusaetzlich protokolliert:

- ob ein Approval erforderlich war
- welche Policy-Regel gegriffen hat
- welcher Approval-Grant verwendet wurde, aber nur als Token-ID oder
  SHA-256-Fingerprint, niemals als roher `approvalToken`
- ob der Job wirklich neu gestartet oder idempotent dedupliziert wurde

---

## 8. Umsetzungsschritte

### Phase A - Gemeinsamer Serverkern

- Kernmodelle fuer Principal, Resource-URI, Upload, Fehler,
  Pagination und Execution-Meta einfuehren
- Job- und Artefaktmodelle aus `docs/job-contract.md` verwenden und
  nur adapterneutrale Ergaenzungen dort vornehmen, falls wirklich
  noetig
- Store-Interfaces fuer Jobs, Artefakte, Upload-Sessions,
  Connection-Refs, Idempotency, Approval-Grants und Audit definieren
- In-Memory-Implementierungen fuer Beta und Tests bereitstellen
- deterministische Payload-Fingerprint-Funktion implementieren
- Fingerprint-Kanonik fuer Approval und Idempotenz definieren:
  transiente Kontrollfelder werden ausgeschlossen, Upload-Segmente
  werden separat von Upload-Session-Metadaten behandelt
- Approval-Grant-Service fuer synchrone und asynchrone
  policy-pflichtige Tools implementieren
- Error-Mapper fuer Anwendungsausnahmen und Validierungsfehler bauen

Abnahmekriterien:

- Kernmodelle sind adapterunabhaengig
- Unit-Tests decken Fingerprint-Stabilitaet, Idempotency-Konflikte,
  Approval-Grant-Bindung ohne rohe Tokens, Upload-Session-Fingerprint,
  Resource-URI-Parsing und Tenant-Scope-Pruefung ab

### Phase B - MCP-Modul und Transport

- `adapters:driving:mcp` in Gradle aufnehmen
- MCP-Server-Bootstrap fuer `stdio` und streambares HTTP implementieren
- Initialize fuer MCP `protocolVersion` `2025-11-25` implementieren;
  d-migrate `v1` wird nur als Tool-/Resource-Vertragsversion in
  Capabilities oder `capabilities_list` sichtbar
- Streamable-HTTP-Endpunkt mit MCP-konformen `POST`-/`GET`-Regeln
  implementieren:
  - `Accept: application/json, text/event-stream` fuer `POST`
  - `MCP-Protocol-Version` nach erfolgreichem Initialize
  - `Origin`-Validierung gegen DNS-Rebinding
  - optionales `MCP-Session-Id` mit 400/404-Fehlerpfaden
  - definierte `GET`-Semantik fuer SSE oder HTTP 405
- HTTP-Authorization nach MCP 2025-11-25 fuer nicht-lokales
  streambares HTTP verpflichtend implementieren:
  - HTTP 401 mit `WWW-Authenticate` und `resource_metadata`
  - Protected Resource Metadata well-known Endpoint
  - Protected Resource Metadata als JSON-Dokument mit mindestens:
    - `resource`: kanonische HTTPS-URI des MCP-Servers bzw. MCP-
      Endpunkts
    - `authorization_servers`: nicht-leere Liste der zulaessigen
      Authorization-Server-Issuer-URIs
    - optional `scopes_supported`, falls der Server Scope-Discovery fuer
      Basisfunktionen anbieten kann
  - HTTP 403 mit Scope-Challenge bei unzureichenden Scopes
  - Token-Audience-/Resource-Validierung
  - keine Tokens in Query-Parametern
- Auth-Deaktivierung nur fuer lokale Tests/Demos mit expliziter
  unsicherer Konfiguration zulassen
- Runtime-Bootstrap fuer Driver, Profiling-Adapter, Streaming-Adapter
  und Schema-Codecs aus der CLI teilen oder aequivalent kapseln
- Principal-Ableitung fuer lokale und HTTP-Transporte implementieren
- Initialize-/Capability-Antwort bereitstellen
- Tool-Registry und Resource-Registry aufbauen
- JSON-Schema-Definitionen fuer alle 0.9.6-Tools erzeugen oder
  pflegen
- Startbefehl und lokale Konfiguration dokumentieren

Abnahmekriterien:

- ein MCP-Client kann den Server initialisieren
- lokale und HTTP-Transporttests laufen gegen dieselbe Tool-Registry
- HTTP-Transporttests decken Accept-Header, `MCP-Protocol-Version`,
  Origin-Validierung, optionales `MCP-Session-Id` und `GET`-Semantik ab
- HTTP-Auth-Tests decken fuer nicht-lokales HTTP 401/403,
  `WWW-Authenticate`, Protected-Resource-Metadata und Scope-Challenges
  ab
- Golden-Test validiert das Protected-Resource-Metadata-Dokument:
  `resource` entspricht der kanonischen Server-/Endpoint-URI,
  `authorization_servers` ist vorhanden, nicht leer und enthaelt nur
  zugelassene HTTPS-Issuer; optionales `scopes_supported` ist konsistent
  mit den dokumentierten Scope-Challenges
- ein separater Test zeigt, dass Auth-Deaktivierung nur fuer lokale
  Test-/Demo-Konfigurationen erlaubt ist
- Driver- und Codec-Registry sind nach MCP-Serverstart befuellt
- `capabilities_list` funktioniert ohne DB-Verbindung
- unbekannte Toolnamen liefern einen MCP-/JSON-RPC-Protokollfehler;
  `UNSUPPORTED_TOOL_OPERATION` wird nur fuer bekannte Tools mit
  fachlich nicht unterstuetzter Operation/Option genutzt

### Phase C - Read-only Tools

- `schema_validate` an bestehenden Validator und Format-Reader
  anbinden
- `schema_generate` an bestehende DDL-Generatoren anbinden
- `schema_compare` an bestehende Compare-Pfade anbinden
- `schema_compare` auf bereits materialisierte `schemaRef`-Eingaenge
  beschraenken; `connectionRef` liefert `VALIDATION_ERROR` mit Hinweis
  auf `schema_compare_start`
- Output-Limits und Artefakt-Fallback umsetzen
- Findings und Notes strukturiert mappen

Abnahmekriterien:

- kleine Ergebnisse koennen inline geliefert werden
- grosse Ergebnisse werden als Artefakt referenziert
- bestehende Warnungen bleiben maschinenlesbar sichtbar
- keine Tool-Antwort verletzt die Inline-Limits

### Phase D - Discovery und Ressourcen

- `job_status_get`, `job_list`, `artifact_list`, `schema_list`
  implementieren
- Resource-Resolver und Store-/Index-Abstraktionen fuer Jobs,
  Artefakte, Schemas, Profile und Diffs implementieren
- Resource-Resolver und Store fuer tenant-scoped Connection-Refs
  implementieren, inklusive Sensitivitaets-/Produktionsmetadaten fuer
  Policy
- Bootstrap fuer Connection-Refs aus `.d-migrate.yaml` oder
  aequivalenter Projekt-/Serverkonfiguration anbinden; Tests nutzen
  explizite Seeds ohne echte Secrets
- Cursor-Paginierung und Filtervalidierung einfuehren
- Tenant-Scope-Pruefung fuer jede Resource-Aufloesung erzwingen

Abnahmekriterien:

- Listen liefern stabile `items`, `nextCursor` und `totalCount`
- fremde Tenant-Ressourcen liefern `TENANT_SCOPE_DENIED`
- sensitive Connection-Refs liefern Policy-Metadaten fuer
  `schema_compare_start`, Reverse, Import und Transfer
- Connection-Refs koennen in Beta-Tests aus dokumentierten Seeds
  geladen werden, ohne Secrets in MCP-Payloads zu uebergeben
- ungueltige Cursor liefern `VALIDATION_ERROR`

### Phase E - Async-Jobs, Idempotenz und Policy

- Vorab-Spike fuer Cancel-Faehigkeit der bestehenden Runner:
  - `SchemaReverseRunner`
  - Profiling-Runner/-Services
  - `DataImportRunner`
  - `DataTransferRunner`
  - betroffene Streaming-/Writer-Pfade
- Spike-Abnahme: fuer jeden Pfad ist dokumentiert, an welchen Stellen
  CancellationToken/Worker-Handle geprueft wird und welche Side Effects
  nach Cancel verhindert werden
- Job-Start-Service fuer `schema_reverse_start`, `data_profile_start`
  und `schema_compare_start` einfuehren
- `job_cancel` an Jobkern und Berechtigungspruefung anbinden
- CancellationToken bzw. Job-Worker-Handle in Reverse-, Profiling-,
  Import- und Transfer-Runner propagieren
- kooperative Cancel-Checkpoints vor Artefakt-Publish und vor
  datenveraendernden Schreibabschnitten einfuehren
- Idempotency-Pruefung fuer Start-Tools anbinden
- Policy-Service fuer kontrollierte Operationen einfuehren
- Approval-Token-Flow fuer policy-pflichtige Operationen abbilden
- Jobstatus exakt aus `docs/job-contract.md` verwenden:
  `queued`, `running`, `succeeded`, `failed`, `cancelled`
- Ablauf und Retention von Jobs ueber `expiresAt` modellieren, nicht
  ueber einen zusaetzlichen Jobstatus

Abnahmekriterien:

- fehlender Idempotency-Key liefert `IDEMPOTENCY_KEY_REQUIRED`
- identische Wiederholung liefert denselben Job
- Payload-Konflikte liefern `IDEMPOTENCY_CONFLICT`
- fehlende Policy-Freigabe liefert `POLICY_REQUIRED`
- `schema_compare_start` mit `connectionRef` laeuft als abbrechbarer Job
  und dedupliziert ueber `idempotencyKey`
- `job_cancel` kann nur eigene oder erlaubte Jobs abbrechen und
  erzeugt einen stabilen `cancelled`-Status gemaess
  `docs/job-contract.md`
- nach angenommenem Cancel publiziert der Worker keine neuen Artefakte
  und startet keine weiteren Daten-Schreiboperationen

### Phase F - Datenoperationen und Uploads

- `artifact_upload_init` mit Policy, vollstaendigen Session-Metadaten,
  Gesamt-Checksumme und serverseitiger Session-Erzeugung umsetzen
- `artifact_upload` mit Segmentverwaltung fuer bestehende Sessions
  umsetzen
- `contentBase64` decodieren, Segmentgroessenlimit erzwingen und Hashes
  ueber decodierte Bytes berechnen
- `artifact_upload_abort` umsetzen
- SHA-256-Pruefung pro Segment und Gesamtartefakt erzwingen
- Policy-Pruefung fuer Upload-Init und administrative Upload-Abbrueche
  anbinden; eigene Upload-Abbrueche ueber Session-Owner-Pruefung erlauben
- `data_import_start` an hochgeladene Artefakte binden
- `data_transfer_start` an Connection-Refs und Policy binden
- Idempotency-Pruefung fuer `data_import_start` und
  `data_transfer_start` anbinden
- Upload- und Artefakt-Limits zentral konfigurieren

Abnahmekriterien:

- Upload-Init liefert vor dem ersten Segment eine `uploadSessionId`, TTL
  und erwartete Startposition
- Upload-Resume mit wiederholten Segmenten funktioniert
- Hash-Abweichungen werden abgewiesen
- fehlendes oder zu grosses `contentBase64` wird mit
  `VALIDATION_ERROR` bzw. `PAYLOAD_TOO_LARGE` abgewiesen
- finalisierte Artefakte sind immutable
- Upload-Init, administrative Upload-Abbrueche, Import und Transfer
  laufen nur mit Policy-Freigabe; eigene aktive Upload-Sessions duerfen
  per Owner-Pruefung abgebrochen werden
- Import/Transfer-Retries mit gleichem `idempotencyKey` deduplizieren;
  abweichender Payload mit gleichem Key liefert
  `IDEMPOTENCY_CONFLICT`

### Phase G - KI-nahe Tools, Prompts, Tests und Dokumentation

- `procedure_transform_plan`, `procedure_transform_execute` und
  `testdata_plan` policy- und audit-gesteuert anbinden
- MCP-Prompts fuer kuratierte Analyse-, Transformations- und
  Testdatenablaeufe registrieren
- Prompt-Hygiene-Pruefung fuer Secrets, rohe Massendaten und externe
  Provider-Policies anbinden
- Unit-Tests fuer alle Kernservices
- MCP-Handler-Tests fuer jedes Tool
- Integrationstests mit echten MCP-Transporten ueber `stdio` und HTTP
- Fehler-Envelope-Golden-Tests
- Upload-Tests fuer Resume, Abort, Expiry, Hash-Fehler und
  Payload-Limits
- Policy- und Idempotency-Konflikttests
- Dokumentation fuer Start, Tool-Liste, Sicherheitsmodell und bekannte
  Beta-Grenzen aktualisieren

Abnahmekriterien:

- neue Tests laufen in den passenden Gradle-Modulen
- alle Roadmap-Aufgaben aus 0.9.6 sind durch Tests oder dokumentierte
  Abnahmekriterien abgedeckt
- KI-nahe Tools liefern Policy-, Audit- und Prompt-Hygiene-Fehler
  strukturiert statt als rohe Exceptions
- MCP-Prompts sind ueber Discovery sichtbar und referenzieren nur
  erlaubte Ressourcen/Tools
- Doku nennt die konkreten Startpfade fuer lokale und entfernte
  MCP-Clients

---

## 9. Teststrategie

### 9.1 Unit-Tests

Pflichtabdeckung:

- Resource-URI-Parser
- Tenant-Scope-Pruefung
- Payload-Fingerprint
- Ausschluss transienter Kontrollfelder aus dem Fingerprint:
  `idempotencyKey`, `approvalToken`, `clientRequestId`, `requestId`
- Upload-Approval-Fingerprint ueber Session-Metadaten, nicht ueber
  Segmentdaten
- Idempotency-Store
- Approval-Grant-Store mit Token-Fingerprint statt Roh-Token
- Policy-Entscheidungen
- Around-/Finally-Audit fuer Auth-, Validation-, Scope-, Policy- und
  Idempotency-Fehler
- Fehlercode-Mapping
- MCP-Wire-Mapping: JSON-RPC-Errors nur fuer Protokollfehler,
  fachliche Toolfehler als `tools/call`-Result mit `isError=true`
- Inline-Limit-Entscheidung
- Upload-Session-Zustandsautomat
- SHA-256-Segment- und Gesamtpruefung
- Base64-Decoding, Segmentgroessenlimit und Hash-Berechnung ueber
  decodierte Segmentbytes
- Upload-Session-TTL, `uploadSessionId`-Laenge und fortlaufende
  `segmentIndex`-Validierung
- `artifact_upload_init` erzwingt Gesamt-`checksumSha256` vor dem ersten
  Segment und bindet Session-ID an die Init-Metadaten
- session-scoped Upload-Berechtigung bindet `uploadSessionId`, Tenant,
  Principal, Init-Metadaten, Approval-Fingerprint und Ablaufzeit
- eigener Upload-Abbruch braucht keine Policy, administrative oder
  fremde Abbrueche aber schon
- Cursor-Serialisierung und -Validierung
- Runtime-Bootstrap fuer Driver- und Codec-Registries
- ConnectionReferenceStore und Connection-Resolver inklusive
  Sensitivitaets-/Produktionsmetadaten
- Connection-Ref-Bootstrap aus lokaler Projekt-/Serverkonfiguration
  oder Test-Seeds ohne Secrets
- SchemaStore, ProfileStore und DiffStore bzw. gleichwertige typisierte
  Artefakt-Indizes fuer Listing, Resource-Resolution und Tenant-Scope
- Cancel-Berechtigungen und terminale Jobzustaende
- CancellationToken-/Worker-Handle-Propagation und Side-Effect-Stopp
  nach Cancel
- Prompt-Registry und Prompt-Parameter-Validierung
- Prompt-Hygiene-Pruefung fuer Secrets und Massendaten

### 9.2 Adaptertests

Pflichtabdeckung je Tool:

- gueltiger Minimalaufruf
- ungueltiger Payload
- Scope-Verletzung
- limitierter Output
- strukturierter Fehler
- Audit-Ereignis

Fuer Start-Tools zusaetzlich:

- fehlender Idempotency-Key
- identische Wiederholung
- identische Wiederholung eines bereits akzeptierten Jobs liefert auch
  nach Ablauf oder Entzug des Approval-Grants denselben Job, sofern Auth,
  Schema und Scope weiter gueltig sind
- Konflikt mit anderem Payload
- Policy-Required-Flow
- `schema_compare` weist `connectionRef` synchron ab; connection-backed
  Vergleiche laufen ueber `schema_compare_start`
- synchroner Approval-Flow mit `approvalKey` fuer
  `artifact_upload_init`, administrative `artifact_upload_abort`-Aufrufe
  und KI-nahe Tools
- `artifact_upload` akzeptiert Segmente nur mit gueltiger
  session-scoped Upload-Berechtigung
- Idempotency-Flow fuer `data_import_start` und `data_transfer_start`

### 9.3 Integrationstests

Mindestens ein Integrationstest startet den MCP-Server ueber `stdio` und
ein weiterer ueber HTTP. Beide pruefen:

- Initialize/Capabilities
- Initialize verhandelt MCP `protocolVersion` `2025-11-25` und trennt
  davon den d-migrate-Toolvertrag `v1`
- Streamable-HTTP-POST mit `Accept: application/json,
  text/event-stream`
- HTTP-Folgeaufruf mit `MCP-Protocol-Version`
- ungueltiger `Origin` liefert HTTP 403
- optionales `MCP-Session-Id` wird korrekt ausgegeben, verlangt,
  abgewiesen oder mit HTTP 404 als abgelaufen behandelt
- HTTP-`GET` liefert SSE oder HTTP 405 gemaess Server-Konfiguration
- `capabilities_list`
- `schema_validate` mit kleinem Schema
- `schema_generate` mit Artefakt-Fallback
- Driver-Lookup ueber die normale MCP-Runtime-Registry
- Schema-Codec-Lookup ueber die normale MCP-Runtime-Registry
- `schema_compare` mit zwei `schemaRef`-Eingaengen
- `schema_compare_start` mit sensitiver `connectionRef`,
  `idempotencyKey`, Policy-Flow und abbrechbarem Job
- nicht-lokaler HTTP-Transport erzwingt Auth und liefert fuer fehlende
  oder unzureichende Tokens die dokumentierten 401/403-Antworten
- `artifact_upload` ueber `stdio` mit `contentBase64`
- `artifact_upload` ueber streambares HTTP mit `contentBase64` im
  `tools/call`-JSON-RPC-Argument
- fachlicher Toolfehler mit `isError=true` und
  `structuredContent.error.code`
- unbekannter Toolname als Protokollfehler mit numerischem
  JSON-RPC-`error.code`
- `resources/read` fuer fehlende Resource liefert JSON-RPC-Error
  `-32002` mit `error.data.dmigrateCode=RESOURCE_NOT_FOUND`
- fachlich nicht unterstuetzte Option eines bekannten Tools als
  `isError=true` mit `UNSUPPORTED_TOOL_OPERATION`
- `job_list`
- Upload eines kleinen Artefakts in mehreren Segmenten
- `job_cancel`
- ein KI-nahes Spezialtool mit Policy-/Audit-Pfad
- MCP-Prompt-Discovery

Wenn die verwendete MCP-Bibliothek einen Testclient anbietet, wird
dieser genutzt. Falls nicht, wird die JSON-RPC-Kommunikation in Tests
minimal und lokal gekapselt.

### 9.4 Negative Tests

Verbindliche Negativfaelle:

- unbekannter Toolname muss ein MCP-/JSON-RPC-Protokollfehler sein
- bekannte Tools mit nicht unterstuetzter fachlicher Option liefern
  `isError=true` mit `UNSUPPORTED_TOOL_OPERATION`
- fehlende Resource muss JSON-RPC-Error mit d-migrate-Code in
  `error.data` liefern
- fehlender Principal
- fremder Tenant
- zu grosse Inline-Payload
- nicht erlaubter MIME-Type
- `artifact_upload_init` ohne Gesamt-`checksumSha256`
- fehlendes `contentBase64`
- ungueltige `uploadSessionId`-Laenge
- clientseitig wiederverwendete `uploadSessionId` mit anderem Tenant,
  Principal, Approval-Fingerprint oder anderen Session-Metadaten
- `segmentIndex=0` oder nicht fortlaufender Segmentindex
- Upload nach TTL-Ablauf
- Segmenthash ueber andere Bytes als die decodierten
  `contentBase64`-Bytes
- HTTP-Upload-Versuch mit separatem binaerem Nicht-JSON-RPC-Body im
  MCP-Endpunkt
- Upload-Hash-Mismatch
- Upload nach Abort
- Upload nach Expiry
- Import ohne Approval
- `schema_compare` mit `connectionRef`
- `schema_compare_start` mit `connectionRef` ohne `idempotencyKey`
- `schema_compare_start` mit sensitiver `connectionRef` ohne Policy-
  Freigabe
- `artifact_upload_init` ohne `approvalKey`
- `artifact_upload` ohne vorherige Upload-Session
- `artifact_upload` mit gueltiger Session, aber fehlender oder fremder
  session-scoped Upload-Berechtigung
- `artifact_upload_abort` der eigenen aktiven Session ohne Policy ist
  erlaubt
- administrativer `artifact_upload_abort` ohne Policy-Freigabe
- KI-nahes Tool ohne `approvalKey`
- Wiederverwendung eines Approval-Tokens mit anderem
  `payloadFingerprint`
- zweiter Approval-Aufruf mit zusaetzlichem `approvalToken` veraendert
  den Payload-Fingerprint nicht
- Upload-Berechtigung bleibt ueber mehrere Segmente derselben Session
  stabil und kann nicht fuer eine andere `uploadSessionId` genutzt werden
- `data_import_start` oder `data_transfer_start` ohne
  `idempotencyKey`
- `data_import_start` oder `data_transfer_start` mit gleichem
  `idempotencyKey`, aber anderem Payload
- `job_cancel` fuer fremde Tenants oder fremde Principals
- Worker publiziert nach angenommenem Cancel ein neues Artefakt oder
  startet weitere Daten-Schreiboperationen
- MCP-Tasks werden parallel zu d-migrate-Jobs fuer denselben
  Langlaeufer verwendet
- KI-nahes Tool ohne Policy-Freigabe
- externer KI-Provider ohne erlaubende Policy
- Prompt mit Secret- oder Rohdatenparameter
- Connection-Ref als freier JDBC-String
- rohe SQL-Payloads in Datenoperationen
- nicht-lokaler HTTP-Start mit deaktivierter Auth-Konfiguration

---

## 10. Dokumentationsnachzug

Nach Umsetzung muessen mindestens diese Dokumente konsistent sein:

- `docs/ki-mcp.md`
  - falls Tool-Namen, Limits, Startpfad oder Transportumfang angepasst
    wurden
- `docs/guide.md`
  - kurzer Abschnitt "MCP-Server lokal starten"
- `docs/cli-spec.md`
  - falls ein CLI-Unterkommando oder Startparameter fuer MCP eingefuehrt
    wird
- `docs/roadmap.md`
  - Status von 0.9.6 nach Abschluss
- `docs/job-contract.md`
  - falls der gemeinsame Job-/Artefaktvertrag konkretisiert wurde

Dokumentation muss explizit nennen:

- `stdio` und streambares HTTP sind die Beta-Transporte
- MCP-`initialize` nutzt datierte `protocolVersion`-Strings
  (`2025-11-25`); d-migrate `v1` ist nur die Tool-/Resource-
  Vertragsversion
- Streamable HTTP dokumentiert Accept-Header,
  `MCP-Protocol-Version`, Origin-Validierung, optionale
  `MCP-Session-Id` und `GET`-Semantik
- HTTP-Authorization dokumentiert 401/403-Verhalten,
  `WWW-Authenticate`, Protected Resource Metadata und Scope-
  Challenges fuer nicht-lokale HTTP-Clients; das Metadata-Dokument nennt
  mindestens `resource` und `authorization_servers`; Auth-Deaktivierung
  ist nur fuer lokale Tests/Demos erlaubt
- `stdio` ohne vertrauenswuerdigen Host-/Prozesskontext und ohne
  `DMIGRATE_MCP_STDIO_TOKEN` liefert `AUTH_REQUIRED`
- Secrets werden nicht ueber MCP-Payloads uebergeben
- grosse Ergebnisse werden als Ressourcen/Artefakte referenziert
- Write-Tools brauchen Policy-Freigabe
- Uploads beginnen mit `artifact_upload_init`, sind danach segmentiert
  und hash-validiert
- Upload-Segmente nutzen bei `stdio` und streambarem HTTP
  `contentBase64`; Hashes gelten ueber die decodierten Segmentbytes
- streambares HTTP nutzt fuer MCP-Toolcalls JSON-RPC-POSTs; binaere
  Nicht-MCP-Upload-Bodies sind keine 0.9.6-Standardtransport-Funktion
- Upload-Responses enthalten TTL-Informationen, und abgelaufene
  Sessions werden definiert verworfen
- fachliche Toolfehler erscheinen als `isError=true`-Tool-Result mit
  `structuredContent.error.code`; JSON-RPC-Errors bleiben
  Protokollfehlern vorbehalten
- unbekannte Toolnamen werden als MCP-/JSON-RPC-Protokollfehler
  behandelt, nicht als d-migrate-`UNSUPPORTED_TOOL_OPERATION`
- Resource-Fehler erscheinen als JSON-RPC-Errors mit stabilem
  d-migrate-Code in `error.data.dmigrateCode`
- Audit speichert Approval-Grants nur als Token-ID oder Fingerprint,
  nie als rohen `approvalToken`
- KI-nahe Tools brauchen Policy, Prompt-Hygiene und Audit
- MCP-Prompts sind kuratierte Vorlagen und keine versteckten
  Tool-Ausfuehrungen

---

## 11. Risiken und Gegenmassnahmen

### 11.1 MCP-Bibliotheksreife

Risiko:

- Kotlin/JVM-MCP-Unterstuetzung kann API-Brueche oder Luecken haben.

Gegenmassnahme:

- MCP-spezifische Bibliotheksaufrufe in einem kleinen Transportpaket
  kapseln
- Tool-Handler unabhaengig vom konkreten SDK testbar halten

### 11.2 Zu viel Infrastruktur im Beta-Milestone

Risiko:

- Job-, Artefakt-, Policy- und Upload-Kern koennen den Milestone
  ueberladen.

Gegenmassnahme:

- Beta-Store bewusst einfach halten
- keine Remote-Mandantenverwaltung implementieren
- klare Store-Interfaces fuer spaetere Persistenz definieren

### 11.3 Sicherheitsbruch durch bequeme Tool-Payloads

Risiko:

- Agenten koennten Secrets, freie JDBC-URLs oder rohe SQL-Fragmente in
  Tool-Payloads senden.

Gegenmassnahme:

- Payload-Schemas erlauben nur Resource-Refs und allowlist-basierte
  Optionen
- Fehler `VALIDATION_ERROR` statt toleranter Interpretation
- Log- und Audit-Scrubbing fuer sensitive Felder

### 11.4 Prompt-Kontext wird versehentlich geflutet

Risiko:

- Compare-, DDL- oder Profiling-Ergebnisse koennen gross werden.

Gegenmassnahme:

- zentrale Response-Limit-Komponente
- Artefakt-Fallback als Pflichtpfad
- Tests fuer grosse synthetische Ergebnisse

---

## 12. Definition of Done

0.9.6 ist abgeschlossen, wenn:

- `adapters:driving:mcp` gebaut und getestet wird
- lokale und entfernte MCP-Clients den Server ueber `stdio` bzw. HTTP
  initialisieren koennen
- Initialize MCP `protocolVersion` `2025-11-25` nutzt und d-migrate
  `v1` nur als Tool-/Resource-Vertragsversion ausweist
- streambares HTTP Accept-Header, `MCP-Protocol-Version`,
  Origin-Validierung, optionales `MCP-Session-Id` und `GET`-Semantik
  gemaess MCP-Spezifikation abdeckt
- nicht-lokales streambares HTTP Auth nach MCP 2025-11-25 erzwingt und
  Auth-Deaktivierung nur fuer lokale Tests/Demos zulaesst
- alle in Scope genannten Tools registriert sind
- read-only Tools gegen bestehende Anwendungskomponenten laufen
- Discovery-Tools Jobs, Artefakte und Schemas paginiert liefern
- Schemas, Profile und Diffs ueber explizite Store-/Index-
  Abstraktionen auffindbar und als Ressourcen aufloesbar sind
- MCP-Bootstrap Driver und Schema-Codecs fuer die realen Tool-Pfade
  registriert
- tenant-scoped Connection-Refs ueber einen eigenen Store/Resolver
  aufgeloest werden und Policy-Metadaten fuer sensitive Verbindungen
  liefern
- Connection-Refs aus dokumentierter Projekt-/Serverkonfiguration oder
  Test-Seeds gebootstrappt werden, ohne Secrets in MCP-Payloads
  anzunehmen
- Start-Tools Idempotency-Key, Payload-Fingerprint und Konflikte korrekt
  behandeln
- Import und Transfer dieselben Idempotenzregeln wie andere Start-Tools
  erzwingen
- `schema_compare` synchron nur `schemaRef` akzeptiert und
  connection-backed Vergleiche ueber `schema_compare_start` als
  idempotente, abbrechbare Jobs laufen
- policy-pflichtige Upload-Init-, administrative Upload-Abbruch- und
  Datenoperationen den Approval-Flow erzwingen
- Artefakt-Uploads mit `artifact_upload_init` beginnen, danach
  segmentiert, resumable und SHA-256-validiert sind
- Upload-Session-IDs opak, kryptografisch erzeugt oder streng
  validiert und an Tenant, Principal, Approval-Fingerprint,
  Session-Metadaten und session-scoped Berechtigungen gebunden sind
- `job_cancel` eigene oder erlaubte Jobs kontrolliert abbrechen kann
- Cancel in laufende Worker propagiert wird und nach angenommenem
  Cancel keine neuen Artefakte oder Daten-Schreibabschnitte gestartet
  werden
- der Cancel-Spike fuer bestehende Runner abgeschlossen ist und die
  notwendigen kooperativen Checkpoints dokumentiert sind
- 0.9.6 bewusst keine parallele MCP-Tasks-Abstraktion fuer
  d-migrate-Langlaeufer einfuehrt
- KI-nahe Spezialtools und MCP-Prompts aus `docs/ki-mcp.md`
  registriert, policy-gesteuert und auditierbar sind
- Fehler immer als strukturiertes Envelope erscheinen
- Tenant-Scope- und Principal-Pruefungen in Tool- und Resource-Pfaden
  aktiv sind
- Audit-Ereignisse fuer alle Tool-Aufrufe geschrieben werden, auch bei
  fruehen Auth-, Validation-, Scope-, Policy- und Idempotency-Fehlern
- MCP-Integrationstests mindestens Initialize, Tool-Aufruf,
  Resource-Zugriff, Fehlerfall und Upload abdecken
- Dokumentation die konkreten Beta-Startpfade und die Sicherheitsgrenzen
  beschreibt

---

## 13. Folgearbeiten nach 0.9.6

Direkte Anschlussarbeiten laut Roadmap:

- 0.9.7 REST-API auf demselben Job-/Artefakt-/Policy-Kern aufbauen
- 0.9.8 gRPC-API auf denselben Kernvertraegen aufbauen
- 0.9.9 Beta-Dokumentation, API-Dokumentation und Pilot-Validierung
