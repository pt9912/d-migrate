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

Der Milestone liefert sechs konkrete Nutzerergebnisse:

- `d-migrate` kann als lokaler MCP-Server gestartet und von MCP-Clients
  initialisiert werden
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

Bewusst nicht Teil dieses Milestones:

- eine vollstaendige REST- oder gRPC-Implementierung
- MCP-Spezialtools fuer KI-basierte Procedure-Transformation oder
  Testdatengenerierung
- freie SQL-Ausfuehrung ueber MCP
- Uebergabe von JDBC-Secrets im Prompt- oder Tool-Payload
- persistente Multi-Tenant-Produktinfrastruktur jenseits des fuer die
  Beta noetigen lokalen Stores
- Server-Sent Events, Resource-Subscriptions oder Push-Notifications

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
  spaetere KI-Spezialpfade
- Ressourcen fuer Jobs, Artefakte, Schemas, Profile und Diffs
- Antwortgrenzen fuer inline Tool-Resultate
- ein strukturiertes Fehler-Envelope
- Idempotency-Key- und Approval-Token-Verhalten
- segmentierte Artefakt-Uploads
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
- interne Transportabstraktion, damit streambares HTTP spaeter ohne
  Tool-Vertragsbruch ergaenzt werden kann
- Capability-/Initialize-Vertrag fuer MCP `v1`
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
  - `data_profile_start`
- policy-gesteuerte Datenoperationen:
  - `data_import_start`
  - `data_transfer_start`
- Artefakt-Upload:
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
- strukturierte Fehler-Envelopes mit den in `docs/ki-mcp.md`
  festgelegten Codes
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
- vollstaendige Remote-Server-Betriebsplattform mit OAuth,
  Mandantenverwaltung und Admin-UI
- produktive Langzeitpersistenz fuer Jobs und Artefakte ueber
  Prozessneustarts hinaus, sofern sie nicht bereits durch bestehende
  Infrastruktur vorhanden ist
- KI-Provider-Aufrufe fuer Procedure-Transformationen
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

### 4.2 `stdio` ist der Beta-Transport

Verbindliche Entscheidung:

- 0.9.6 liefert `stdio` als unterstuetzten Startpfad
- jeder MCP-Aufruf bekommt trotzdem einen expliziten `PrincipalContext`
- fuer `stdio` wird der Principal lokal abgeleitet:
  - aus Host-/Prozesskontext, sofern verfuegbar
  - sonst aus `DMIGRATE_MCP_STDIO_TOKEN`
  - in Testumgebungen aus einem expliziten Test-Principal
- streambares HTTP bleibt architektonisch vorbereitet, aber nicht
  Abnahmekriterium fuer 0.9.6

Begruendung:

- lokale IDE- und Agent-Runtimes sind der wichtigste Beta-Pfad
- HTTP-Security wuerde ohne REST-Milestone zu viel Infrastruktur in
  0.9.6 ziehen
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
  serialisierten Payload ohne `idempotencyKey`
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
- `schema_reverse_start` und `data_profile_start` sind ebenfalls
  policyfaehig, weil sie kostenintensiv oder sensitiv sein koennen
- wenn eine Policy-Freigabe fehlt, liefert das Tool
  `POLICY_REQUIRED` mit `approvalToken`
- der zweite Aufruf muss denselben `idempotencyKey` und das passende
  `approvalToken` enthalten
- produktive oder als sensitiv markierte Verbindungen duerfen nicht
  ohne Policy-Freigabe genutzt werden

Begruendung:

- MCP wird durch autonome Agenten genutzt
- destruktive oder kostenintensive Operationen brauchen einen sichtbaren
  Kontrollpunkt
- der zweiphasige Flow passt zu Chat- und IDE-Clients

### 4.6 Artefakt-Upload ist segmentiert und pruefbar

Verbindliche Entscheidung:

- `artifact_upload` akzeptiert grosse Inhalte nur ueber Segmente
- jedes Segment enthaelt:
  - `uploadSessionId`
  - `segmentIndex`
  - `segmentOffset`
  - `segmentTotal`
  - `isFinalSegment`
  - `segmentSha256`
  - optional `clientRequestId`
- bei `isFinalSegment=true` ist `checksumSha256` fuer das
  Gesamtartefakt Pflicht
- Wiederholungen desselben Segments sind idempotent
- Reihenfolge, Offset, Segmentgroesse, Segmenthash und Gesamthash
  werden serverseitig validiert
- Sessions koennen `ACTIVE`, `COMPLETED`, `EXPIRED` oder `ABORTED`
  sein
- `artifact_upload_abort` setzt die Session auf `ABORTED` und verwirft
  Zwischensegmente

Begruendung:

- stdio-MCP ist fuer grosse Dateiuebergaben empfindlich
- Agenten muessen Uploads nach Unterbrechung wiederholen koennen
- SHA-256-Pruefung verhindert still korrupte Eingabeartefakte

---

## 5. Zielarchitektur

### 5.1 Modulstruktur

Neue und zu erweiternde Bereiche:

- `settings.gradle.kts`
  - `include("adapters:driving:mcp")`
- `adapters/driving/mcp`
  - MCP-Transport
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

Erwartete neue Kernmodelle:

- `PrincipalContext`
  - `principalId`
  - `tenantId`
  - Rollen oder Scopes
  - `isAdmin`
- `ServerResourceUri`
  - typisierte Abbildung von
    `dmigrate://tenants/{tenantId}/...`
- `ManagedJob`
  - `jobId`
  - `tenantId`
  - `operation`
  - `status`
  - `createdAt`
  - `expiresAt`
  - optionale `artifactIds`
  - optionale strukturierte Summary
- `ManagedArtifact`
  - `artifactId`
  - `tenantId`
  - `artifactKind`
  - `mimeType`
  - `sizeBytes`
  - `checksumSha256`
  - `createdAt`
  - `expiresAt`
  - immutable Content-Referenz
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

### 5.3 Stores fuer die Beta

0.9.6 braucht einen klaren, testbaren Store-Vertrag:

- `JobStore`
- `ArtifactStore`
- `UploadSessionStore`
- `IdempotencyStore`
- `AuditSink`

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
2. Principal aus Transportkontext ableiten
3. Tool-Payload gegen Tool-Schema validieren
4. Tenant- und Resource-Scopes pruefen
5. Policy pruefen
6. Idempotency pruefen, falls Start-Tool
7. Anwendung ausfuehren oder Job anlegen
8. Ergebnis zuschneiden und Limits anwenden
9. Artefakte/Ressourcen referenzieren
10. Audit-Ereignis schreiben
11. Erfolg oder Fehler als einheitliches Envelope liefern

Fehler in den Schritten 2 bis 9 duerfen nicht als rohe Exception an
den MCP-Client gelangen. Sie werden auf die standardisierten
Fehlercodes gemappt.

### 5.5 Ressourcen

0.9.6 implementiert mindestens diese Resource-URI-Formen:

- `dmigrate://tenants/{tenantId}/jobs/{jobId}`
- `dmigrate://tenants/{tenantId}/artifacts/{artifactId}`
- `dmigrate://tenants/{tenantId}/schemas/{schemaId}`
- `dmigrate://tenants/{tenantId}/profiles/{profileId}`
- `dmigrate://tenants/{tenantId}/diffs/{diffId}`
- `dmigrate://tenants/{tenantId}/connections/{connectionId}`

Verbindliche Regeln:

- `tenantId` ist adressierend, aber nicht autorisierend
- Zugriff wird immer gegen `PrincipalContext` geprueft
- Fremdzugriff liefert `TENANT_SCOPE_DENIED`
- Connection-Ressourcen enthalten keine Secrets
- nicht existente Ressourcen liefern `RESOURCE_NOT_FOUND`
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

### 6.4 `schema_compare`

Zweck:

- zwei Schemas oder Umgebungen vergleichen

Verbindliche Eingaben:

- `left`
- `right`
- beide Seiten jeweils als `schemaRef` oder `connectionRef`
- optionale Compare-Optionen

Antwort:

- Summary
- Findings bis Limit
- Diff-Resource oder Artefakt bei groesserem Ergebnis

Akzeptanz:

- Connection-Refs muessen tenant-scoped `dmigrate://.../connections/...`
  sein
- freie JDBC-Strings werden mit `VALIDATION_ERROR` abgewiesen
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
- Antworten enthalten `items`, `nextCursor` und bei Listen
  `totalCount`, sofern guenstig bestimmbar

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
- immer policy-gesteuert
- Import nutzt zuvor hochgeladenes `artifactId`
- Transfer nutzt tenant-scoped Source- und Target-Connection-Refs
- kein rohes SQL im Tool-Payload

Akzeptanz:

- fehlende Freigabe liefert `POLICY_REQUIRED`
- zweiter Aufruf mit passendem Token startet oder dedupliziert den Job
- ungueltige oder fremde Artefakte liefern `RESOURCE_NOT_FOUND` oder
  `TENANT_SCOPE_DENIED`

### 6.8 Artefakt-Upload

Tools:

- `artifact_upload`
- `artifact_upload_abort`

Verbindliche Validierungen:

- `artifactKind` ist allowlist-basiert
- `mimeType` ist allowlist-basiert
- `sizeBytes` ist Pflicht
- maximale Uploadgroesse ist `200 MiB`
- jedes Segment braucht `segmentSha256`
- finales Segment braucht `checksumSha256`

Akzeptanz:

- wiederholtes identisches Segment ist erfolgreich oder liefert den
  bestehenden Segmentstatus
- abweichender Hash fuer ein bereits angenommenes Segment liefert
  `VALIDATION_ERROR`
- zu grosse Uploads liefern `PAYLOAD_TOO_LARGE`
- abgebrochene Sessions liefern `UPLOAD_SESSION_ABORTED`
- abgelaufene Sessions liefern `UPLOAD_SESSION_EXPIRED`

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
- `principalId`
- `tenantId`
- Zeitpunkt
- normalisierter Payload-Fingerprint
- betroffene Resource-IDs
- Ergebnisstatus
- Fehlercode, falls vorhanden

Nicht im Audit-Event:

- Secrets
- komplette Artefaktinhalte
- grosse DDL-/Diff-/Daten-Payloads

Fuer policy-gesteuerte Operationen wird zusaetzlich protokolliert:

- ob ein Approval erforderlich war
- welche Policy-Regel gegriffen hat
- welches Approval-Token verwendet wurde
- ob der Job wirklich neu gestartet oder idempotent dedupliziert wurde

---

## 8. Umsetzungsschritte

### Phase A - Gemeinsamer Serverkern

- Kernmodelle fuer Principal, Resource-URI, Job, Artefakt, Upload,
  Fehler, Pagination und Execution-Meta einfuehren
- Store-Interfaces fuer Jobs, Artefakte, Upload-Sessions,
  Idempotency und Audit definieren
- In-Memory-Implementierungen fuer Beta und Tests bereitstellen
- deterministische Payload-Fingerprint-Funktion implementieren
- Error-Mapper fuer Anwendungsausnahmen und Validierungsfehler bauen

Abnahmekriterien:

- Kernmodelle sind adapterunabhaengig
- Unit-Tests decken Fingerprint-Stabilitaet, Idempotency-Konflikte,
  Resource-URI-Parsing und Tenant-Scope-Pruefung ab

### Phase B - MCP-Modul und Transport

- `adapters:driving:mcp` in Gradle aufnehmen
- MCP-Server-Bootstrap fuer `stdio` implementieren
- Initialize-/Capability-Antwort bereitstellen
- Tool-Registry und Resource-Registry aufbauen
- JSON-Schema-Definitionen fuer alle 0.9.6-Tools erzeugen oder
  pflegen
- Startbefehl und lokale Konfiguration dokumentieren

Abnahmekriterien:

- ein MCP-Client kann den Server initialisieren
- `capabilities_list` funktioniert ohne DB-Verbindung
- unbekannte Tools liefern `UNSUPPORTED_TOOL_OPERATION`

### Phase C - Read-only Tools

- `schema_validate` an bestehenden Validator und Format-Reader
  anbinden
- `schema_generate` an bestehende DDL-Generatoren anbinden
- `schema_compare` an bestehende Compare-Pfade anbinden
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
- Resource-Resolver fuer Jobs, Artefakte, Schemas, Profile und Diffs
  implementieren
- Cursor-Paginierung und Filtervalidierung einfuehren
- Tenant-Scope-Pruefung fuer jede Resource-Aufloesung erzwingen

Abnahmekriterien:

- Listen liefern stabile `items`, `nextCursor` und `totalCount`
- fremde Tenant-Ressourcen liefern `TENANT_SCOPE_DENIED`
- ungueltige Cursor liefern `VALIDATION_ERROR`

### Phase E - Async-Jobs, Idempotenz und Policy

- Job-Start-Service fuer `schema_reverse_start` und
  `data_profile_start` einfuehren
- Idempotency-Pruefung fuer Start-Tools anbinden
- Policy-Service fuer kontrollierte Operationen einfuehren
- Approval-Token-Flow fuer policy-pflichtige Operationen abbilden
- Jobstatus fuer `queued`, `running`, `succeeded`, `failed`,
  `cancelled` und `expired` definieren

Abnahmekriterien:

- fehlender Idempotency-Key liefert `IDEMPOTENCY_KEY_REQUIRED`
- identische Wiederholung liefert denselben Job
- Payload-Konflikte liefern `IDEMPOTENCY_CONFLICT`
- fehlende Policy-Freigabe liefert `POLICY_REQUIRED`

### Phase F - Datenoperationen und Uploads

- `artifact_upload` mit Session- und Segmentverwaltung umsetzen
- `artifact_upload_abort` umsetzen
- SHA-256-Pruefung pro Segment und Gesamtartefakt erzwingen
- `data_import_start` an hochgeladene Artefakte binden
- `data_transfer_start` an Connection-Refs und Policy binden
- Upload- und Artefakt-Limits zentral konfigurieren

Abnahmekriterien:

- Upload-Resume mit wiederholten Segmenten funktioniert
- Hash-Abweichungen werden abgewiesen
- finalisierte Artefakte sind immutable
- Import/Transfer starten nur mit Policy-Freigabe

### Phase G - Tests und Dokumentation

- Unit-Tests fuer alle Kernservices
- MCP-Handler-Tests fuer jedes Tool
- Integrationstest mit echtem MCP-Transport ueber `stdio`
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
- Doku nennt den konkreten Startpfad fuer lokale MCP-Clients

---

## 9. Teststrategie

### 9.1 Unit-Tests

Pflichtabdeckung:

- Resource-URI-Parser
- Tenant-Scope-Pruefung
- Payload-Fingerprint
- Idempotency-Store
- Policy-Entscheidungen
- Fehlercode-Mapping
- Inline-Limit-Entscheidung
- Upload-Session-Zustandsautomat
- SHA-256-Segment- und Gesamtpruefung
- Cursor-Serialisierung und -Validierung

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
- Konflikt mit anderem Payload
- Policy-Required-Flow

### 9.3 Integrationstests

Mindestens ein Integrationstest startet den MCP-Server ueber `stdio` und
prueft:

- Initialize/Capabilities
- `capabilities_list`
- `schema_validate` mit kleinem Schema
- `schema_generate` mit Artefakt-Fallback
- `job_list`
- Upload eines kleinen Artefakts in mehreren Segmenten

Wenn die verwendete MCP-Bibliothek einen Testclient anbietet, wird
dieser genutzt. Falls nicht, wird die JSON-RPC-Kommunikation in Tests
minimal und lokal gekapselt.

### 9.4 Negative Tests

Verbindliche Negativfaelle:

- unbekanntes Tool
- fehlender Principal
- fremder Tenant
- zu grosse Inline-Payload
- nicht erlaubter MIME-Type
- Upload-Hash-Mismatch
- Upload nach Abort
- Upload nach Expiry
- Import ohne Approval
- Connection-Ref als freier JDBC-String
- rohe SQL-Payloads in Datenoperationen

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

- `stdio` ist der Beta-Transport
- Secrets werden nicht ueber MCP-Payloads uebergeben
- grosse Ergebnisse werden als Ressourcen/Artefakte referenziert
- Write-Tools brauchen Policy-Freigabe
- Uploads sind segmentiert und hash-validiert

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
- ein lokaler MCP-Client den Server ueber `stdio` initialisieren kann
- alle in Scope genannten Tools registriert sind
- read-only Tools gegen bestehende Anwendungskomponenten laufen
- Discovery-Tools Jobs, Artefakte und Schemas paginiert liefern
- Start-Tools Idempotency-Key, Payload-Fingerprint und Konflikte korrekt
  behandeln
- policy-pflichtige Datenoperationen den Approval-Flow erzwingen
- Artefakt-Uploads segmentiert, resumable und SHA-256-validiert sind
- Fehler immer als strukturiertes Envelope erscheinen
- Tenant-Scope- und Principal-Pruefungen in Tool- und Resource-Pfaden
  aktiv sind
- Audit-Ereignisse fuer Tool-Aufrufe geschrieben werden
- MCP-Integrationstests mindestens Initialize, Tool-Aufruf,
  Resource-Zugriff, Fehlerfall und Upload abdecken
- Dokumentation den konkreten Beta-Startpfad und die Sicherheitsgrenzen
  beschreibt

---

## 13. Folgearbeiten nach 0.9.6

Direkte Anschlussarbeiten laut Roadmap:

- 0.9.7 REST-API auf demselben Job-/Artefakt-/Policy-Kern aufbauen
- 0.9.8 gRPC-API auf denselben Kernvertraegen aufbauen
- 0.9.9 Frontend fuer Jobs, Artefakte, Diffs und Migrationsablaeufe

Moegliche spaetere MCP-Erweiterungen:

- streambares HTTP als zweiter Transport
- `job_cancel`, falls Cancel im Jobkern stabil verfuegbar ist
- MCP-Prompts fuer kuratierte Analyseablaeufe
- Procedure-Transformationstools mit KI-Audit-Trail
- Testdatenplanung als separates, policy-gesteuertes Tool
