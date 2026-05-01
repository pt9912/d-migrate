# Implementierungsplan: Milestone 0.9.6 - MCP-Server

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.9.6. Es setzt den Roadmap-Punkt "MCP-Server" um und
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
  noetigen lokalen Stores
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
  - in Scope ist ein konkreter Validierungsvertrag fuer konfigurierte
    Issuer, JWKS oder Introspection, Algorithmus-Allowlist,
    Audience/Resource-Pruefung und Tool-/Resource-Scopes
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
  - `artifact_chunk_get`
  - `schema_list`
  - `profile_list`
  - `diff_list`
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
- mindestens ein produktiver Grant-Aussteller fuer `approvalRequestId`-
  Pruefung und Grant-Ausstellung: lokale Policy-Allowlist, signierte
  Grant-Datei oder schmales MCP-Admin-Grant-Unterkommando. Das Admin-
  Unterkommando ist nur dann in Scope, wenn es als gewaehlter
  Grant-Aussteller umgesetzt wird; generelle Mandantenverwaltung bleibt
  ausgeschlossen
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
- allgemeine Freigabe-UI fuer Policy-Grants; 0.9.6 braucht stattdessen
  einen schmalen Grant-Pfad gemaess Abschnitt 4.5
- produktive Langzeitpersistenz fuer Jobs und Artefakte ueber
  Prozessneustarts hinaus, sofern sie nicht bereits durch bestehende
  Infrastruktur vorhanden ist
- neue Datenbank-Treiberfaehigkeiten, die nicht fuer die MCP-Oberflaeche
  selbst erforderlich sind
- Veraenderung der CLI-Vertraege ausser Start-/Konfigurationsdoku fuer
  den MCP-Server und dem explizit in Scope stehenden schmalen
  MCP-Admin-Grant-Unterkommando

### 3.3 Lieferabschnitte innerhalb von 0.9.6

Der Milestone bleibt fachlich vollstaendig, wird aber innerhalb von 0.9.6
ueber explizite Lieferabschnitte umgesetzt. Das ist keine Verschiebung
nach 0.9.7 und kein reduzierter MVP-Scope:

- Abschnitt 0.9.6-A - Read-only MCP Core:
  - `stdio` plus HTTP-Initialize/Transport-Grundpfad nur fuer Loopback,
    solange die vollstaendige HTTP-Auth aus Abschnitt B noch nicht aktiv ist
  - MCP-`protocolVersion` `2025-11-25`
  - `capabilities_list`, read-only Schema-Tools und Discovery
  - read-only Schema-Staging fuer grosse Schemas
  - Resource-/Artifact-Byte-Stores mit File-Spooling
  - Auth-/Principal-Ableitung, Audit, Quotas und strukturierte Fehler
  - Idempotency nur fuer read-only/schema-staging-relevante Wiederaufnahme
    ohne Policy-Zustand
- Abschnitt 0.9.6-B - Controlled Operations:
  - Streamable-HTTP-Auth mit JWKS/Introspection und Scope-Challenges
  - Idempotency inklusive `AWAITING_APPROVAL`-/`DENIED`-Recovery-Vertrag
  - Policy-Grant-Pfad, Start-Tools, Import/Transfer, Uploads fuer
    `job_input`, `job_cancel` inklusive Cancel-Gate
- Abschnitt 0.9.6-C - AI and Prompts:
  - KI-nahe Tools mit NoOp/lokalem Provider, Prompt-Hygiene und Audit
  - MCP-Prompts fuer kuratierte Ablaeufe
- Die finale Definition of Done bleibt die vollstaendige 0.9.6-DoD; ein
  einzelner Lieferabschnitt darf nicht als abgeschlossener Milestone
  markiert werden

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

### 4.2 `stdio` und streambares HTTP sind Transporte

Verbindliche Entscheidung:

- 0.9.6 liefert `stdio` und streambares HTTP als unterstuetzte
  Startpfade
- jeder MCP-Aufruf bekommt trotzdem einen expliziten `PrincipalContext`
- fuer `stdio` wird der Principal lokal abgeleitet:
  - aus explizit registrierter Host-Attestation, sofern verfuegbar
  - sonst aus `DMIGRATE_MCP_STDIO_TOKEN`
  - in Testumgebungen aus einem expliziten Test-Principal
- Host-Attestation ist nur vertrauenswuerdig, wenn sie ueber eine
  konfigurierte Registry validiert wird und deterministisch auf
  `principalId`, `tenantId`, `allowedTenantIds`, Scopes/Rollen, `isAdmin`,
  Ablaufzeit und Audit-Identitaet mappt. Reine OS-Usernamen,
  Prozess-IDs, Parent-Prozessdaten, Arbeitsverzeichnisse oder ungepruefte
  Umgebungsvariablen duerfen keinen Principal erzeugen.
- `DMIGRATE_MCP_STDIO_TOKEN` darf nie direkt als Principal gelten:
  - Default ist ein serverseitiger Token-Fingerprint-Lookup gegen eine
    lokale, dateibasierte oder konfigurierte Token-Registry
  - alternativ sind lokal signierte Tokens erlaubt, wenn Signatur,
    Issuer, Audience, Ablaufzeit und Schluessel-ID validiert werden
  - der validierte Token-Eintrag mappt deterministisch auf `principalId`,
    `tenantId`, Scopes/Rollen, `isAdmin`, Ablaufzeit und Audit-Identitaet
  - ungueltige, unbekannte, abgelaufene oder scope-arme Tokens liefern
    `AUTH_REQUIRED` bzw. `FORBIDDEN_PRINCIPAL`
  - Audit speichert nur Token-ID oder Token-Fingerprint, nie den rohen
    Tokenwert
- wenn weder eine validierte Host-Attestation noch
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
- der HTTP-Resource-Server hat einen fail-closed Tokenvalidierungsvertrag:
  - erlaubte Issuer werden konfiguriert und muessen exakt mit Token-
    `iss` und Protected-Resource-Metadata uebereinstimmen
  - JWKS-basierte JWT-Validierung ist der Default; alternativ darf
    ein explizit konfigurierter Introspection-Endpoint genutzt werden
  - akzeptierte Signaturalgorithmen sind allowlist-basiert; `none`,
    ungekennzeichnete symmetrische Fallbacks und unerwartete
    Algorithmuswechsel sind verboten
  - `aud` oder OAuth-Resource-Indicator muss die kanonische MCP-Server-
    URI bzw. den MCP-Endpunkt enthalten
  - Ablauf (`exp`), Not-Before (`nbf`), Issued-At-Toleranz und Clock-Skew
    werden zentral konfiguriert und getestet
  - Tool- und Resource-Aufrufe mappen auf dokumentierte Scopes; fehlende
    Scopes liefern 403 mit Scope-Challenge
  - Tokenvalidierung darf nie auf "Bearer-String wird Principal" oder
    ungepruefte lokale Decodierung zurueckfallen
- Scope-Namen sind in 0.9.6 rollenartig und stabil; granulare
  `dmigrate:tool:<name>`-Scopes werden bewusst nicht eingefuehrt:
  - `dmigrate:read`: read-only Tools, `resources/list`,
    `resources/templates/list`, `resources/read` und Listen-Tools fuer
    eigene Tenant-Ressourcen
  - `dmigrate:job:start`: `schema_reverse_start`,
    `schema_compare_start`, `data_profile_start`
  - `dmigrate:artifact:upload`: policy-pflichtige
    `artifact_upload_init`-Intents wie `job_input`, dazugehoerige
    `artifact_upload`-Segmente und eigene `artifact_upload_abort`
  - `dmigrate:read`: zusaetzlich `artifact_upload_init` und
    `artifact_upload` fuer `uploadIntent=schema_staging_readonly`; die
    daraus entstehende session-scoped Upload-Berechtigung ist nur fuer
    read-only Schema-Staging gueltig
  - `dmigrate:data:write`: `data_import_start`, `data_transfer_start`
  - `dmigrate:job:cancel`: `job_cancel` fuer eigene Jobs
  - `dmigrate:ai:execute`: `procedure_transform_plan`,
    `procedure_transform_execute`, `testdata_plan`
  - `dmigrate:admin`: Cross-Tenant-Listen, fremde Ressourcen, administrative
    Upload-Abbrueche und erlaubte administrative Aktionen im selben Tenant
- Cross-Tenant-Job-Cancel ist in 0.9.6 nicht erlaubt; auch Admins duerfen
  `job_cancel` nur innerhalb ihres Tenants oder eines explizit im
  PrincipalContext erlaubten Tenant-Sets ausfuehren
- `WWW-Authenticate`-Challenges nennen immer die fuer den konkreten
  Request minimal notwendigen Scopes; `scopes_supported` in Protected
  Resource Metadata enthaelt mindestens die oben genannten Scope-Namen
- Deaktivierung von HTTP-Auth ist nur fuer lokale Tests und Demos ueber
  eine explizite unsichere Konfiguration erlaubt; entfernte HTTP-Clients
  duerfen ohne Auth nicht als fertig gelten
- unsichere HTTP-Auth-Deaktivierung ist nur zulaessig, wenn der Server
  ausschliesslich an Loopback-Adressen (`127.0.0.1` oder `::1`) bindet,
  keine oeffentliche Base-URL ausweist und keine nicht-lokale
  Bind-Adresse wie `0.0.0.0` nutzt; andernfalls muss der Start hart
  fehlschlagen
- Streamable HTTP implementiert die MCP-Transportregeln fuer
  `protocolVersion` `2025-11-25`:
  - JSON-RPC-Nachrichten laufen ueber einen einzelnen MCP-Endpunkt mit
    `POST` und optional `GET`
  - `POST`-Requests muessen im `Accept`-Header sowohl
    `application/json` als auch `text/event-stream` listen; Reihenfolge,
    Whitespace und Parameter werden robust geparst
  - nach Initialize muessen HTTP-Requests den ausgehandelten
    `MCP-Protocol-Version`-Header senden
  - nach erfolgreichem Initialize liefern fehlende, syntaktisch ungueltige
    oder nicht unterstuetzte `MCP-Protocol-Version`-Header einen
    MCP-konformen Transport-/JSON-RPC-Fehler und werden nicht als normaler
    Tool-Fehler modelliert
  - der Server validiert `Origin` gegen DNS-Rebinding und antwortet bei
    ungueltigem Origin mit HTTP 403
  - `MCP-Session-Id` ist optional, aber wenn der Server eine Session-ID
    vergibt, muss sie fuer Folge-Requests validiert werden
  - `GET` oeffnet entweder einen SSE-Stream mit
    `Content-Type: text/event-stream` oder liefert HTTP 405, wenn keine
    Server-initiierte Stream-Kommunikation angeboten wird
  - `DELETE` mit `MCP-Session-Id` terminiert die Session explizit oder
    liefert HTTP 405, wenn clientseitige Session-Terminierung nicht
    unterstuetzt wird
- optionales mTLS wird als konfigurierbarer Maschinen-zu-Maschinen-
  Schutz vorbereitet

Begruendung:

- lokale IDE- und Agent-Runtimes sind der wichtigste Pfad
- entfernte Agent-Plattformen brauchen denselben Tool-Vertrag ueber
  einen remote-faehigen Transport
- Tool-Vertraege, Ressourcen, Policies und Fehlercodes bleiben
  transportneutral

### 4.3 Read-only Tools duerfen inline antworten, grosse Ergebnisse nicht

Verbindliche Entscheidung:

- Tool-Responses bleiben unter `64 KiB` serialisierter Nutzdaten
- `findings` werden inline auf maximal `200` Eintraege begrenzt
- nicht-Upload-Tool-Requests bleiben unter `256 KiB` serialisiertem
  JSON-Argumentpayload
- Inline-Schemas in `schema_validate`, `schema_generate` und vergleichbaren
  read-only Tools bleiben unter `32 KiB` serialisiertem JSON; groessere
  Schemas muessen ueber Schema-Staging/Artefaktpfade laufen
- `artifact_upload` ist die einzige Toolfamilie mit groesseren Requests:
  maximal `6 MiB` serialisierter JSON-RPC-Request und maximal `4 MiB`
  decodierte Segmentbytes pro Aufruf
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
- der Idempotency-Store-Key ist auf
  (`tenantId`, `callerId`, `operation`, `idempotencyKey`) gescoped
- `callerId` wird serverseitig aus dem Auth-/Principal-Kontext
  abgeleitet
- `tenantId` und `operation` werden ebenfalls serverseitig bestimmt;
  der Client kann keinen fremden Idempotency-Scope setzen
- `payloadFingerprint` ist ein SHA-256 ueber deterministisch
  serialisierten, normalisierten Payload ohne transiente Kontrollfelder:
  `idempotencyKey`, `approvalToken`, `clientRequestId`, `requestId`
- wiederholte Requests mit identischem Store-Key und identischem
  `payloadFingerprint` geben denselben Job zurueck
- gleicher `idempotencyKey` in anderem Tenant-, Caller- oder
  Operations-Scope ist unabhaengig und darf fremde Nutzung nicht sichtbar
  machen
- gleicher Store-Key mit anderem `payloadFingerprint` fuehrt zu
  `IDEMPOTENCY_CONFLICT`
- Idempotency-Pruefung und Job-Anlage sind eine atomare Store-Operation:
  `reserveOrGet(scopeKey, payloadFingerprint)`
  - existiert ein Eintrag mit identischem Fingerprint, wird dessen Job
    oder Pending-/Awaiting-Approval-Reservierung geladen
  - existiert ein Eintrag mit anderem Fingerprint, endet der Aufruf
    deterministisch mit `IDEMPOTENCY_CONFLICT`
  - existiert kein Eintrag, wird eine `PENDING`-Reservierung atomar
    angelegt, bevor Policy oder Job-Erzeugung weitere Nebenwirkungen
    ausloesen
  - `PENDING` traegt `pendingLeaseExpiresAt`; verwaiste Reservierungen
    duerfen nach Ablauf vom naechsten identischen Request desselben
    Scope/Fingerprints atomar uebernommen oder erneuert werden
  - fehlt fuer eine policy-pflichtige Operation ein Grant, wird die
    Reservierung atomar auf `AWAITING_APPROVAL` gesetzt und der Aufruf
    liefert `POLICY_REQUIRED`
  - `AWAITING_APPROVAL` traegt `awaitingApprovalExpiresAt` und optional
    `approvalRequestId`; nach Ablauf liefert ein identischer Request erneut
    `POLICY_REQUIRED` mit neuer Challenge oder erneuert die bestehende
    Challenge atomar
  - explizite Ablehnung setzt die Reservierung auf `DENIED` mit
    `deniedAt`, `denialReasonCode` und optionaler `denialExpiresAt`;
    identische Requests liefern bis zum Ablauf `POLICY_DENIED`
  - ein zweiter Aufruf mit identischem Scope/Fingerprint und gueltigem
    `approvalToken` muss dieselbe `AWAITING_APPROVAL`-Reservierung atomar
    claimen, daraus genau einen Job erzeugen und die Reservierung auf
    `COMMITTED` setzen
  - nach erfolgreicher Job-Anlage wird die Reservierung auf `COMMITTED`
    mit `jobId` gesetzt; bei nicht-retrybaren Fehlern wird sie mit
    Fehlerstatus abgeschlossen oder nach definierter TTL freigegeben
- parallele identische Starts duerfen hoechstens eine Job-Anlage
  ausloesen; weitere Aufrufe sehen dieselbe Pending-/Committed-
  Reservierung

Begruendung:

- Agenten wiederholen Tool-Aufrufe haeufig nach Unterbrechungen oder
  Kontextwechseln
- langlaufende Operationen duerfen dadurch nicht mehrfach starten
- der Vertrag kann spaeter unveraendert fuer REST/gRPC genutzt werden

### 4.5 Write- und datenveraendernde Operationen sind policy-gesteuert

Verbindliche Entscheidung:

- `data_import_start` und `data_transfer_start` brauchen eine Policy-
  Freigabe
- `artifact_upload_init` wird nach Upload-Intent getrennt:
  - `uploadIntent=schema_staging_readonly` fuer `artifactKind=schema`
    materialisiert nach erfolgreicher Validierung eine read-only
    `schemaRef` fuer `schema_validate`, `schema_generate` und
    `schema_compare`; dieser Pfad braucht keine Write-Policy, aber
    `dmigrate:read`, Quota, Audit, Byte-Store und strikte MIME-/Schema-
    Validierung
  - `uploadIntent=job_input` oder ein Zielkontext fuer Import, Transfer,
    Transformation, Seed-Daten oder andere spaetere Write-/KI-Operationen
    bleibt policy-gesteuert
  - read-only gestagte Schema-Artefakte duerfen nicht spaeter implizit als
    Import-/Transfer-/KI-Eingaben verwendet werden; dafuer ist ein neuer
    policy-pflichtiger Init- oder Materialisierungspfad erforderlich
- `artifact_upload` nutzt danach eine serverseitige, session-scoped
  Upload-Berechtigung aus dem erfolgreichen Init-Aufruf; einzelne
  Segmente brauchen keine erneute Policy-Freigabe
- `artifact_upload_abort` fuer die eigene aktive Session braucht Tenant-,
  Principal- und Session-Owner-Pruefung, aber keine Policy-Freigabe;
  fremde oder administrative Abbrueche sind policy-gesteuert
- `schema_reverse_start`, `schema_compare_start` und
  `data_profile_start` sind immer policy-gesteuert und auditpflichtig;
  sie duerfen nie stillschweigend starten
- wenn eine Policy-Freigabe fehlt, liefert das Tool `POLICY_REQUIRED`
  mit `approvalRequestId`, `approvalKey` bzw. `idempotencyKey`,
  `payloadFingerprint`, erforderlichen Scopes/Entscheidungsgruenden und
  einer menschenlesbaren Challenge, aber ohne verwendbares
  `approvalToken`
- ein verwendbares `approvalToken` entsteht erst, nachdem ein
  serverseitiger Policy-, Human- oder Admin-Mechanismus den
  `approvalRequestId` geprueft und einen Grant ausgestellt hat; ein
  blosser Retry desselben Agenten darf keine Freigabe erzeugen
- bei Start-Tools muss der zweite Aufruf denselben `idempotencyKey` und
  ein zum ausgestellten Grant passendes `approvalToken` enthalten
- bei nicht-asynchronen policy-pflichtigen Tools ohne
  `idempotencyKey`, z.B. policy-pflichtige `artifact_upload_init`-
  Varianten, administrative
  `artifact_upload_abort`-Aufrufe und die KI-nahen Tools, muss der
  Client einen stabilen `approvalKey` mitsenden
- dieser `approvalKey` ist zugleich der Idempotency-Key fuer synchrone
  Nebenwirkungen:
  - der Store-Scope ist (`tenantId`, `callerId`, `toolName`,
    `approvalKey`)
  - identischer Scope und identischer `payloadFingerprint` liefern dasselbe
    Ergebnis, dieselbe Upload-Session bzw. dieselbe erzeugte Artefakt-/
    Provider-Referenz zurueck
  - identischer Scope mit anderem `payloadFingerprint` liefert
    `IDEMPOTENCY_CONFLICT`
  - die Reservierung erfolgt atomar vor Session-Erzeugung,
    Provider-Aufruf, Artefakt-Publish oder anderen Nebenwirkungen
  - `approvalToken` ist transient und darf den Fingerprint nicht veraendern
- das `approvalToken` wird an (`toolName`, `callerId`, `tenantId`,
  `correlationKind`, `correlationKey`, `payloadFingerprint`) gebunden:
  `correlationKind=idempotencyKey` fuer Start-Tools,
  `correlationKind=approvalKey` fuer synchrone policy-pflichtige
  Side-Effect-Tools
- fehlt bei einer policy-pflichtigen synchronen Operation der
  `approvalKey`, liefert der Server `VALIDATION_ERROR`
- fuer `artifact_upload_init` wird der Approval-Fingerprint aus den
  Session-Metadaten gebildet (`artifactKind`, `mimeType`, `sizeBytes`,
  `checksumSha256`, `uploadIntent`, Tenant, Principal, optionaler
  Zielkontext), nicht aus Segmentbytes, `segmentSha256`, `contentBase64`
  oder Segmentpositionen
- ein erfolgreicher `artifact_upload_init` wandelt die Freigabe in eine
  serverseitige Upload-Berechtigung um, die explizit an
  `uploadSessionId`, Tenant, Principal, `artifactKind`, `mimeType`,
  `sizeBytes`, `checksumSha256` und Ablaufzeit gebunden ist
- produktive oder als sensitiv markierte Verbindungen duerfen nicht
  ohne Policy-Freigabe genutzt werden
- KI-nahe Tools (`procedure_transform_plan`,
  `procedure_transform_execute`, `testdata_plan`) sind immer
  policy- und auditpflichtig
- 0.9.6 muss mindestens einen produktiv nutzbaren Grant-Aussteller
  implementieren, damit policy-pflichtige Pfade nicht dauerhaft bei
  `POLICY_REQUIRED` enden:
  - lokale Policy-Allowlist fuer klar begrenzte Operationen, Principals,
    Tenant, Toolnamen, Scopes und Fingerprints
  - oder schmales MCP-Admin-Grant-Unterkommando fuer
    `approvalRequestId`-Pruefung und Grant-Ausstellung
  - oder signierte Grant-Datei mit Ablaufzeit, Issuer-Fingerprint und
    Audit-Spur
  - optional zusaetzlich ein expliziter Local-Demo-Auto-Approval-Modus, nur
    fuer Loopback/`stdio`, deutlich als unsicher markiert und immer audit-
    pflichtig
- Test-Seeds allein gelten nicht als produktiver Grant-Pfad; ohne
  konfigurierten Grant-Aussteller muss der Server kontrolliert
  fail-closed starten oder alle policy-pflichtigen Tools mit dokumentiertem
  `POLICY_REQUIRED` blockieren

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
  - `uploadIntent`
  - Tenant, Principal und optionaler Zielkontext aus dem Request-Kontext
- `artifact_upload_init` verlangt noch keine `uploadSessionId`; der
  Server erzeugt sie primaer kryptografisch als opaken Wert und gibt sie
  mit TTL und erwartetem erstem Segment zurueck
- Clients duerfen keine `uploadSessionId` vorschlagen; Init-Resume erfolgt
  ueber `approvalKey` bzw. `clientRequestId` plus identische
  Init-Metadaten und gibt die serverseitig erzeugte Session zurueck
- fuer `uploadIntent=schema_staging_readonly` ist `clientRequestId`
  verpflichtend, wenn der Init-Aufruf resumable/idempotent sein soll:
  - Store-Key ist (`tenantId`, `callerId`, `toolName`, `clientRequestId`)
  - identische Init-Metadaten liefern dieselbe `uploadSessionId`
  - abweichende Init-Metadaten liefern `IDEMPOTENCY_CONFLICT`
  - die Reservierung hat `initResumeExpiresAt` und wird spaetestens mit der
    Upload-Session-Lease entfernt
  - ohne `clientRequestId` ist ein erfolgreicher read-only Init bewusst nicht
    resumable; Agenten muessen dann doppelte Sessions ueber Quotas/Cleanup
    behandeln
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
- "fremde Jobs" meint bei `job_cancel` nur Jobs desselben Tenants mit
  anderem Principal; Cross-Tenant-Cancel ist ausgeschlossen, sofern der
  PrincipalContext nicht explizit mehrere erlaubte Tenants enthaelt
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
- weil `docs/architecture.md` `ai/` und `testdata/` noch als geplante
  Module beschreibt, umfasst 0.9.6 einen minimalen adapterneutralen
  KI-/Testdaten-Port mit NoOp- bzw. lokaler Implementierung, damit die
  MCP-Tools real registrierbar, testbar und auditierbar sind
- externe KI-Provider bleiben optional und duerfen nur per expliziter
  Konfiguration, Secret-Scrubbing und erlaubender Policy genutzt werden
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
  - KI-/Testdaten-Port mit NoOp- bzw. lokaler Default-Implementierung
  - Secret-Scrubbing und Prompt-Hygiene-Service
- `hexagon/ports-common` oder `hexagon/application`
  - gemeinsame Principal-, Error-, Pagination- und Resource-URI-Modelle
- `hexagon:ports` bzw. ein bestehendes Port-Modul
  - adapterneutraler Port fuer Connection-/Project-Config-Bootstrap und
    Secret-Resolver-Referenzen
- dediziertes Config-/Driven-Modul
  - YAML-/Projektkonfigurationsparser und non-secret Connection-Metadaten-
    Bootstrap fuer CLI und MCP
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
  - `tenantId` als Default-/Home-Tenant
  - `effectiveTenantId` fuer den aktuellen Request
  - `allowedTenantIds` als explizit autorisierte Tenant-Menge
  - Rollen oder Scopes
  - `isAdmin`
  - `auditSubject`
  - `authSource`
  - `expiresAt`
  `effectiveTenantId` muss entweder `tenantId` entsprechen oder in
  `allowedTenantIds` enthalten sein; sonst liefert Tenant-Aufloesung
  `TENANT_SCOPE_DENIED`. `isAdmin` hebt diese Tenant-Grenze nicht
  automatisch auf.
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
  - `correlationKind` (`idempotencyKey` oder `approvalKey`)
  - `correlationKey`
  - `approvalRequestId`
  - `approvalTokenFingerprint`
  - `toolName`
  - `tenantId`
  - `callerId`
  - `payloadFingerprint`
  - `issuerFingerprint`
  - `issuedScopes`
  - `grantSource` (`policy-allowlist`, `admin-command`,
    `signed-grant-file`, `local-demo-auto-approval`)
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

### 5.3 Stores

0.9.6 braucht einen klaren, testbaren Store-Vertrag:

- `JobStore`
- `ArtifactStore`
- `SchemaStore`
- `ProfileStore`
- `DiffStore`
- `UploadSessionStore`
- `UploadSegmentStore`
- `ConnectionReferenceStore`
- `ArtifactContentStore`
- `IdempotencyStore`
- `ApprovalGrantStore`
- `SyncEffectIdempotencyStore` oder explizite Wiederverwendung des
  `IdempotencyStore` fuer synchrone Nebenwirkungen mit `approvalKey`
- `QuotaStore` bzw. Quota-Zaehler fuer aktive Jobs, Upload-Sessions,
  Upload-Bytes und Provider-Aufrufe
- `AuditSink`

`ApprovalGrantStore` speichert Grants mit Ablauf, Token-Fingerprint,
Issuer-Fingerprint, Scope-Metadaten und Bindung an
`correlationKind`/`correlationKey` (`idempotencyKey` fuer Start-Tools,
`approvalKey` fuer synchrone Nebenwirkungen). Ein Grant mit nur
`approvalKey` ohne Korrelationstyp ist ungueltig.

Schemas, Profile und Diffs duerfen intern als typisierte Artefakte
persistiert werden. Trotzdem braucht 0.9.6 je Typ eine explizite
Store-/Index-Abstraktion, damit `schema_list`, Resource-Resolver,
Tenant-Scope-Pruefung, Paginierung und Retention nicht von
dateispezifischen Artefaktdetails abhaengen.

Artefaktbytes und Uploadsegmente sind nicht Teil des Metadaten-Stores:

- `UploadSegmentStore` nimmt Segmente atomar je `uploadSessionId`,
  `segmentIndex` und `segmentOffset` an und verhindert konkurrierende
  abweichende Writes fuer dieselbe Position
- `ArtifactContentStore` materialisiert finalisierte Artefakte immutable
  und bietet Range-/Chunk-Reads fuer `artifact_chunk_get`,
  `resources/read`-Chunk-URIs, Import und Cleanup
- beide Stores berechnen bzw. validieren Segment- und Gesamt-SHA-256 ueber
  gespeicherte Bytes und koennen finalisierte Artefakte aus Segmenten ohne
  vollstaendiges Laden in den Heap zusammensetzen
- Quotas fuer aktive Sessions, reservierte Bytes, gespeicherte Bytes und
  Tenant-/Principal-Grenzen werden vor Segmentannahme und Finalisierung
  geprueft
- TTL-/Abort-/Expiry-Cleanup entfernt Segmente und nicht referenzierte
  Inhalte deterministisch
- ausserhalb reiner Unit-Tests ist File-Spooling oder ein gleichwertiger
  externer Byte-Store verpflichtend; 200-MiB-Uploads duerfen nicht in einer
  RAM-only-Implementierung enden

Connection-Refs werden ausserhalb von MCP registriert. Fuer die Version
muss der Store aus mindestens einer klaren Bootstrap-Quelle befuellt
werden:

- bestehende CLI-/Projektkonfiguration wie `.d-migrate.yaml` oder
  aequivalente lokale Konfigurationsdateien
- serverseitiger Credential-/Connection-Provider, falls vorhanden
- Test-/Demo-Seeds fuer Integrationstests ohne echte Secrets

Der MCP-Adapter darf keine freien Connection-Secrets im Tool-Payload
annehmen. Das Modell trennt strikt:

- `ConnectionReferenceStore` enthaelt nur non-secret Metadaten:
  `connectionId`, Tenant, Dialekt, Owner, Sensitivitaet,
  Produktionskennzeichen und einen `credentialRef`/`providerRef`
- lokale `.d-migrate.yaml`-Eintraege duerfen fuer MCP-Bootstrap nur dann
  genutzt werden, wenn Secret-Anteile als `envRef`, `credentialRef` oder
  Provider-Verweis modelliert sind; rohe Passwoerter oder vollstaendig
  expandierte Secret-URLs werden nicht in MCP-Stores uebernommen
- ein adapterneutraler `ConnectionSecretResolver` materialisiert Secrets
  erst innerhalb autorisierter Runner/Driver-Aufrufe und nie fuer
  Discovery, Listen, Resource-Reads, Audit oder Tool-Responses
- der bestehende CLI-Resolver darf nicht unveraendert aus dem MCP-Adapter
  heraus aufgerufen werden, wenn er ENV-Substitution zu vollstaendigen
  Secret-URLs bereits waehrend Discovery/Bootstrap ausfuehrt
- existiert kein dokumentierter `ConnectionSecretResolver` fuer reale
  Verbindungen, muessen connection-backed Tools fail-closed mit einem klaren
  Konfigurationsfehler enden; Test-Seeds duerfen nur non-secret Dummy-
  Referenzen enthalten

Default:

- lokaler In-Memory-Store fuer Tests und kurzlebige `stdio`-Server
- File-Spooling fuer `UploadSegmentStore` und `ArtifactContentStore` unter
  einem konfigurierbaren Arbeitsverzeichnis fuer alle realen `stdio`- und
  HTTP-Pfade
- In-Memory-Byte-Stores sind nur fuer reine Unit-Tests und sehr kleine
  Contract-Fakes erlaubt

Nicht erlaubt:

- Tool-Handler halten globale mutable Maps ohne Store-Abstraktion
- Artefakte liegen dauerhaft in Prompt- oder Tool-Response-Strukturen
- Secrets werden im Store persistiert
- Uploadsegmente oder finalisierte Artefaktbytes werden in produktnahen
  Pfaden vollstaendig im Heap gehalten

### 5.4 MCP-Tool-Pipeline

Jeder Tool-Aufruf laeuft logisch durch dieselbe Pipeline:

1. `requestId` erzeugen oder uebernehmen
2. Audit-Scope fuer diesen Aufruf oeffnen
3. Principal aus Transportkontext ableiten
4. Tool-Payload gegen Tool-Schema validieren
5. Tenant- und Resource-Scopes pruefen
6. `reserveOrGet(scopeKey, payloadFingerprint)` fuer Start-Tools
   atomar ausfuehren
   - vorhandener identischer Treffer liefert denselben Job oder die
     passende Pending-/Awaiting-/Committed-Reservierung
   - vorhandener Treffer mit anderem Fingerprint beendet den Aufruf
     sofort mit `IDEMPOTENCY_CONFLICT`
   - vorhandener `AWAITING_APPROVAL`-Treffer ohne gueltigen Grant liefert
     erneut `POLICY_REQUIRED`
   - vorhandener `AWAITING_APPROVAL`-Treffer mit gueltigem Grant wird
     atomar in Job-Erzeugung ueberfuehrt und danach `COMMITTED`
   - abgelaufene `PENDING`-Leases werden nur fuer identischen
     Scope/Fingerprint atomar recovered; abgelaufene oder abgelehnte
     Approval-Reservierungen liefern eine neue Challenge bzw.
     `POLICY_DENIED`
7. fuer synchrone policy-pflichtige Tools mit Nebenwirkungen
   `reserveOrGetSyncEffect(approvalScopeKey, payloadFingerprint)` atomar
   ausfuehren
   - vorhandener identischer Treffer liefert dieselbe Upload-Session,
     dieselbe Artefakt-/Provider-Referenz oder dasselbe Ergebnis
   - vorhandener Treffer mit anderem Fingerprint endet mit
     `IDEMPOTENCY_CONFLICT`
8. Quota- und Rate-Limit-Pruefung ausfuehren, aber nur fuer neue Side
   Effects:
   - reine Deduplizierungsantworten fuer `COMMITTED` Jobs, bestehende
     Upload-Sessions oder bestehende Provider-/Artefakt-Referenzen
     verbrauchen keine neue Quota
   - neue Jobs, neue Upload-Sessions, neue Segmente, Upload-Finalisierung
     und neue Provider-Aufrufe muessen vor der jeweiligen Nebenwirkung
     quotiert werden
   - Upload-Segmente werden vor Byte-Store-Write gegen Session-, Byte- und
     Parallelitaetsquotas geprueft
9. Policy nur fuer neue oder noch nicht genehmigte Pending-Starts und
   Sync-Effect-Reservierungen pruefen, niemals fuer Idempotency-Konflikte
10. Driver, Codecs und benoetigte Adapter registrieren bzw. verfuegbar
   machen
11. Anwendung ausfuehren, Upload-Session fortsetzen oder Job anlegen
12. Ergebnis zuschneiden und Limits anwenden
13. Artefakte/Ressourcen referenzieren
14. Erfolg oder Fehler als einheitliches Envelope liefern
15. Audit-Scope in einem `finally`-Pfad mit Outcome abschliessen

Verbindliche Audit-Semantik:

- ein `requestId` existiert vor Auth-, Schema-, Scope-, Policy- oder
  Idempotency-Pruefungen
- identische Start-Tool-Retries mit bestehendem Idempotency-Treffer geben
  nach Auth, Schema-Validierung und Scope-Pruefung denselben Job zurueck,
  auch wenn ein frueherer Approval-Grant inzwischen abgelaufen oder
  entzogen ist
- Idempotency-Konflikte werden nach Auth, Schema-Validierung und Scope-
  Pruefung deterministisch als `IDEMPOTENCY_CONFLICT` abgeschlossen;
  Policy wird dafuer nicht geprueft
- Policy wird fuer neue Starts und noch nicht genehmigte Pending-
  Reservierungen geprueft, nicht fuer reine Deduplizierungsantworten
- ein genehmigter Retry einer `AWAITING_APPROVAL`-Reservierung darf nicht
  nur die Reservierung zurueckgeben; er muss die reservierte Operation
  genau einmal starten oder einen bereits `COMMITTED` Job zurueckgeben
- Deduplizierungsantworten verbrauchen keine neue Quota; Quota- und
  Timeout-Entscheidungen werden erst vor neuen Side Effects relevant und
  trotzdem im selben Audit-Scope dokumentiert
- synchrone Side-Effect-Retries mit bestehendem `approvalKey`-Treffer
  liefern nach Auth, Schema-Validierung und Scope-Pruefung dasselbe
  Ergebnis zurueck; abweichende Payloads enden ohne Policy-Pruefung mit
  `IDEMPOTENCY_CONFLICT`
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
- Listen-Tools leiten den effektiven Tenant grundsaetzlich aus dem
  `PrincipalContext` ab; ein optionales `tenantId`-Eingabefeld ist nur
  adressierend
- wenn ein Client-`tenantId` vom Principal-Tenant abweicht, ist der
  Aufruf nur fuer Admins oder Principals mit explizitem Cross-Tenant-
  Scope erlaubt; sonst gilt `TENANT_SCOPE_DENIED`
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
- `resources/read` bleibt MCP-konform und nimmt nur `uri` entgegen; es
  gibt keine zusaetzlichen `resources/read`-Parameter wie `cursor`,
  `limitBytes`, `rangeStart` oder `rangeEnd`
- `resources/read` liefert maximal `64 KiB` serialisierte Nutzdaten und
  genau ein MCP-`contents[]`-Result; groessere Ressourcen werden nicht
  inline fortgesetzt
- chunkbare Inhalte werden ueber stabile Chunk-URIs modelliert, z.B.
  `dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}`
  oder aequivalente Resource-Template-URIs
- Chunk-Resources unterscheiden Text und Binaerinhalt:
  - textuelle Ressourcen duerfen als MCP-Text-Content geliefert werden und
    enthalten neben dem Text nur minimale Metadaten wie `chunkId`,
    `nextChunkUri`, `range`, `truncated=true` und `etag`/
    `resourceVersion`
  - binaere Artefakt-Chunks werden entweder als MCP-Blob-Content geliefert,
    sofern die verwendete MCP-Bibliothek Blob-Contents fuer
    `resources/read` unterstuetzt, oder ausschliesslich ueber
    `artifact_chunk_get`
  - `resources/read` erfindet kein Base64-in-JSON-Ersatzformat fuer binaere
    Artefakte
- grosse Artefakte koennen alternativ ueber das Tool
  `artifact_chunk_get` gelesen werden; dieses Tool ist d-migrate-
  spezifisch und nicht Teil des MCP-`resources/read`-Schemas
- `resources/list` und `resources/templates/list` muessen Chunk-Templates
  und normale Ressourcen auffindbar machen, statt implizite
  `resources/read`-Parameter zu verlangen

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
  - `maxToolResponseBytes=65536`
  - `maxNonUploadToolRequestBytes=262144`
  - `maxInlineSchemaBytes=32768`
  - `maxUploadToolRequestBytes=6291456`
  - `maxUploadSegmentBytes=4194304`
  - `maxInlineFindings=200`
  - `maxArtifactUploadBytes=209715200`
- `executionMeta`

Akzeptanz:

- Tool funktioniert ohne Datenbankverbindung
- Secrets oder lokale Pfade werden nicht offengelegt
- Antwort bleibt stabil genug fuer Client-Discovery
- `limits` enthaelt die numerischen Inline-, Request-, Segment- und
  Upload-Grenzen; Clients duerfen `PAYLOAD_TOO_LARGE` gegen diese Werte
  vorab vermeiden

### 6.1a Limits, Quotas und Timeouts

0.9.6 muss operative Grenzen explizit erzwingen:

- pro Tenant und Principal gibt es konfigurierbare Limits fuer:
  - aktive Jobs insgesamt und je Operation
  - aktive Upload-Sessions
  - reservierte und gespeicherte Upload-/Artefaktbytes
  - parallele Segmentwrites
  - KI-/Provider-Aufrufe pro Zeitfenster
  - HTTP-Requests bzw. Tool-Aufrufe pro Zeitfenster
- bei Quota- oder Rate-Limit-Verletzung liefert das Tool fachlich
  `RATE_LIMITED`; HTTP-Transporte mappen das auf HTTP 429, sofern der
  Fehler ausserhalb eines erfolgreichen `tools/call`-Results entsteht
- `RATE_LIMITED` enthaelt strukturierte Details wie `limitName`,
  `retryAfterSeconds`, Tenant/Principal-Scope und keine fremden
  Ressourcendetails
- lange Tool-Handler, Runner-Starts, Upload-Finalisierung und
  KI-/Provider-Aufrufe haben konfigurierte Timeouts
- ein Timeout liefert `OPERATION_TIMEOUT`; bei bereits gestarteten Jobs wird
  der Jobstatus gemaess `docs/job-contract.md` aktualisiert und weitere
  Side Effects werden ueber Cancellation-/Cleanup-Pfade verhindert
- Timeout- und Rate-Limit-Entscheidungen werden auditiert

### 6.2 `schema_validate`

Zweck:

- neutrales Schema aus `schemaRef` oder Inline-Payload bis
  `maxInlineSchemaBytes` validieren

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

- genau eine Schema-Quelle:
  - `schemaRef`
  - kleines neutrales Inline-`schema`
- `targetDialect`
- optionale DDL-Optionen

Antwort:

- Summary
- bei kleinem DDL optional inline
- bei groesserem DDL `artifactId` und `resourceUri`

Akzeptanz:

- kleine Inline-Schemas koennen ohne vorgelagerten Upload und ohne
  Policy-Freigabe in DDL umgewandelt werden
- zu grosse Inline-Schemas liefern `PAYLOAD_TOO_LARGE`
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
- optional `approvalToken`; weil `schema_compare_start` immer
  policy-gesteuert ist, liefert fehlende Freigabe `POLICY_REQUIRED` mit
  `approvalRequestId`

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
- der async Policy-Flow ist fuer `schema_compare_start` immer
  verbindlich:
  - ohne `idempotencyKey` liefert das Tool `IDEMPOTENCY_KEY_REQUIRED`
  - ohne gueltigen, extern ausgestellten Grant liefert das Tool
    `POLICY_REQUIRED` mit `approvalRequestId`, nicht mit verwendbarem
    Token
  - das Token ist an `idempotencyKey`, Caller und `payloadFingerprint`
    gebunden
- `job_cancel` kann einen laufenden `schema_compare_start`-Job
  abbrechen
- Diagnosehinweise der Operanden bleiben sichtbar:
  - bei `schemaRef` sind das Schema-Materialisierungs- oder
    Validierungswarnungen
  - bei `connectionRef` sind das Introspection-/Reverse-Warnungen aus dem
    vorgeschalteten materialisierenden Schritt

### 6.5 Discovery-Tools

Tools:

- `job_status_get`
- `job_list`
- `artifact_list`
- `artifact_chunk_get`
- `schema_list`
- `profile_list`
- `diff_list`

Weitere Discovery-Tools:

- `job_status_get` nimmt keine Listenfilter an, sondern genau eine
  Job-Referenz und liefert eine einzelne Job-Projektion
- `profile_list` und `diff_list` sind explizite Listenflaechen fuer
  Profile und Diffs; sie duerfen intern auf typisierte Artefaktindizes
  oder Job-Artefakte mappen, muessen aber eigene Tool-Schemas,
  Filterallowlists und Resource-URIs liefern
- `artifact_chunk_get` ist kein MCP-Resource-Request, sondern ein
  d-migrate-Tool fuer grosse Artefakte:
  - Eingabe: `artifactId` oder Artefakt-`resourceUri`, optional
    `chunkId`
  - Antwort: maximal `64 KiB` serialisierte Tool-Response inklusive
    Nutzdaten und Metadaten
  - fuer binaere Chunks muss die decodierte Byte-Groesse niedriger
    konfiguriert werden, sodass Base64-Expansion und JSON-Metadaten
    zusammen unter dem serialisierten `64 KiB`-Limit bleiben
  - Antwort enthaelt `chunkId`, optional `nextChunkId`, `nextChunkUri`,
    `range`, `truncated` und `etag`/`resourceVersion`
  - Zugriff folgt denselben Tenant-/Principal-Regeln wie
    `resources/read`

Gemeinsame Regeln:

- Filter sind allowlist-basiert
- Paginierung nutzt den eingefrorenen Phase-B-Wire-Vertrag `pageSize` und
  `cursor`; `limit` ist kein Alias, solange die JSON-Schemas
  `additionalProperties=false` verwenden
- `pageSize` hat ein serverseitiges Maximum
- Antworten enthalten stabil das jeweilige typisierte Collection-Feld
  (`jobs`, `artifacts`, `schemas`, `profiles`, `diffs`) und `nextCursor`
- `totalCount` ist optional und darf nur gesetzt werden, wenn der Store die
  exakte gefilterte Gesamtzahl ohne vollstaendigen teuren Scan bestimmen
  kann
- wenn nur eine guenstige Naeherung verfuegbar ist, darf die Antwort
  `totalCountEstimate` statt `totalCount` enthalten; Clients duerfen weder
  `totalCount` noch `totalCountEstimate` fuer Pagination benoetigen

`job_status_get`:

- Eingabe ist genau eine Job-Referenz:
  - `jobId`
  - tenant-scoped Job-`resourceUri`
- `jobId` wird immer im Tenant des abgeleiteten Principal-Kontexts
  gesucht; `resourceUri` muss denselben Tenant adressieren
- Antwort ist die aktuelle `ManagedJob`-Projektion gemaess
  `docs/job-contract.md` inklusive terminaler Jobs, `expiresAt`,
  Artefaktreferenzen, Fortschritt und optionalem Fehler
- eigene Jobs sind sichtbar; Admins oder Principals mit explizitem Scope
  duerfen erlaubte fremde Jobs sehen
- fremde Tenant-Jobs liefern `TENANT_SCOPE_DENIED`
- nicht erlaubte Jobs im selben Tenant liefern `FORBIDDEN_PRINCIPAL`
- unbekannte oder nach Retention geloeschte Jobs liefern
  `RESOURCE_NOT_FOUND`
- terminale, aber noch nicht retenierte Jobs liefern ihren terminalen
  Status; abgelaufene Retention wird ueber `expiresAt` und
  `RESOURCE_NOT_FOUND` modelliert, nicht ueber einen separaten
  `expired`-Jobstatus

Akzeptanz:

- fremde Tenant-Ressourcen sind nicht sichtbar
- Cursor sind opak
- ungueltige Cursor liefern `VALIDATION_ERROR`

### 6.6 Async-Start-Tools

Tools:

- `schema_reverse_start`
- `schema_compare_start`
- `data_profile_start`

Gemeinsame Regeln:

- `idempotencyKey` ist Pflicht
- Verbindungen werden nur ueber `connectionRef` referenziert
- fuer policy-pflichtige Starts liefert fehlende Freigabe
  `POLICY_REQUIRED` mit `approvalRequestId`; ein spaeter extern
  ausgestelltes `approvalToken` wird an denselben `idempotencyKey` und
  Payload-Fingerprint gebunden
- der erste Aufruf ohne Grant setzt die Idempotency-Reservierung auf
  `AWAITING_APPROVAL`; der genehmigte zweite Aufruf claimt diese
  Reservierung atomar und erzeugt genau einen Job
- Start liefert `jobId`, `resourceUri` und `executionMeta`
- Fortschritt wird ueber `job_status_get` abgefragt

Akzeptanz:

- fehlender `idempotencyKey` liefert `IDEMPOTENCY_KEY_REQUIRED`
- identische Wiederholung liefert denselben Job
- Konflikte liefern `IDEMPOTENCY_CONFLICT`
- genehmigter Retry nach `AWAITING_APPROVAL` startet genau einen Job

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
- fehlende Freigabe setzt die Idempotency-Reservierung auf
  `AWAITING_APPROVAL`; ein spaeter genehmigter Retry claimt diese
  Reservierung atomar
- Import nutzt zuvor hochgeladenes `artifactId`
- `data_import_start` verlangt ein tenant-scoped `targetConnectionRef`;
  implizite Zielaufloesung aus lokaler CLI-Konfiguration ist im MCP-
  Toolvertrag verboten, weil Zielauswahl Teil von Payload-Schema, Policy,
  Fingerprint und Audit sein muss
- Transfer nutzt tenant-scoped Source- und Target-Connection-Refs
- kein rohes SQL im Tool-Payload

Akzeptanz:

- fehlender `idempotencyKey` liefert `IDEMPOTENCY_KEY_REQUIRED`
- identische Wiederholung liefert denselben Job
- gleicher Idempotency-Store-Key mit anderem Payload liefert
  `IDEMPOTENCY_CONFLICT`
- fehlende Freigabe liefert `POLICY_REQUIRED`
- `data_import_start` ohne `targetConnectionRef` liefert
  `VALIDATION_ERROR`
- zweiter Aufruf mit passendem Token startet oder dedupliziert den Job
- ungueltige oder fremde Artefakte liefern `RESOURCE_NOT_FOUND` oder
  `TENANT_SCOPE_DENIED`

### 6.8 Artefakt-Upload

Tools:

- `artifact_upload_init`
- `artifact_upload`
- `artifact_upload_abort`

Gemeinsame Regeln:

- `artifact_upload_init` verlangt `uploadIntent`
- `uploadIntent=schema_staging_readonly` ist nur fuer
  `artifactKind=schema` erlaubt, braucht keine Write-Policy und erzeugt
  nach erfolgreicher Validierung eine read-only `schemaRef`
- read-only Schema-Staging braucht trotzdem `dmigrate:read`, Audit, Quotas,
  Byte-Store, Upload-Limits und Schema-Validierung; fehlende Auth/Quota
  scheitert mit `AUTH_REQUIRED`, `FORBIDDEN_PRINCIPAL` oder `RATE_LIMITED`
- `uploadIntent=job_input` und alle Uploads fuer Import, Transfer,
  Transformation, Seed-Daten, Regeln oder KI-nahe Tools sind
  policy-gesteuert
- fehlende Freigabe fuer einen policy-pflichtigen `artifact_upload_init`
  liefert `POLICY_REQUIRED` mit `approvalRequestId`, nicht mit
  verwendbarem Token
- der Approval-Flow darf Segmentvalidierung und Hashpruefung nicht
  umgehen
- `approvalKey` ist fuer policy-pflichtige Init-Pfade Pflicht; fuer
  read-only Schema-Staging ist `clientRequestId` Pflicht, wenn der Init-
  Aufruf resumable sein soll. Ohne `clientRequestId` darf der Server eine
  neue Session erzeugen und muss dies als nicht resumable auditieren.
- beim zweiten policy-pflichtigen Init-Aufruf muss der Client dasselbe
  `approvalKey` und ein durch Policy-/Human-/Admin-Mechanismus
  ausgestelltes `approvalToken` senden
- das policy-pflichtige Init-`approvalToken` ist an `toolName`, `tenantId`,
  `callerId`, `approvalKey` und den session-metadatenbezogenen
  `payloadFingerprint` gebunden
- `approvalKey` dedupliziert den erfolgreichen Init-Aufruf: gleicher
  Tenant, Caller, Toolname, `approvalKey` und `payloadFingerprint` liefern
  dieselbe `uploadSessionId` und dieselbe session-scoped
  Upload-Berechtigung; abweichender Payload liefert
  `IDEMPOTENCY_CONFLICT`
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
- fuer grosse neutrale Schemas ist `artifactKind=schema` erlaubt; andere
  Upload-Arten erzeugen kein `schemaRef`
- `uploadIntent=schema_staging_readonly` ist nur mit `artifactKind=schema`
  gueltig; andere Kombinationen liefern `VALIDATION_ERROR`
- `uploadIntent=job_input` ist fuer Artefakte erforderlich, die spaeter von
  `data_import_start`, `data_transfer_start`, KI-nahen Tools oder
  Transformationen genutzt werden
- `mimeType` ist allowlist-basiert
- `sizeBytes` ist Pflicht
- Gesamt-`checksumSha256` ist bereits fuer `artifact_upload_init`
  Pflicht und Teil des Approval-Fingerprints
- maximale Uploadgroesse ist `200 MiB`
- `uploadSessionId` ist fuer `artifact_upload_init` nicht Pflicht, fuer
  `artifact_upload` und `artifact_upload_abort` aber verbindlich und
  Bestandteil jeder session-scoped Upload- oder Abort-Berechtigung
- serverseitig erzeugte `uploadSessionId` ist ein opaker Token mit
  mindestens 128 Bit Entropie, sichtbaren ASCII- bzw. URL-sicheren Zeichen
  und maximal 128 Zeichen Laenge; Clients duerfen keine UUID- oder
  Laengen-Semantik ableiten
- clientseitige `uploadSessionId`-Kandidaten sind verboten; Resume eines
  Init-Aufrufs nutzt `approvalKey` fuer policy-pflichtige Pfade oder
  `clientRequestId` fuer read-only Schema-Staging plus identische
  Init-Metadaten und gibt die urspruenglich serverseitig erzeugte
  `uploadSessionId` zurueck
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
- wenn `artifactKind=schema` ist, wird das finalisierte Artefakt
  zusaetzlich validiert und in `SchemaStore` materialisiert; die Antwort
  enthaelt dann `schemaRef`/`resourceUri`, oder bei ungueltigem Schema
  ein strukturiertes Validierungsergebnis ohne Schema-Registrierung
- read-only materialisierte `schemaRef`s sind als
  `schema_staging_readonly` markiert und duerfen nur von read-only Tools
  genutzt werden

Antwort:

- `artifact_upload_init` liefert `uploadSessionId`, Uploadstatus,
  erwarteten ersten `segmentIndex`, erwarteten ersten `segmentOffset` und
  `uploadSessionTtlSeconds`
- jede erfolgreiche Segmentannahme liefert `uploadSessionId`,
  Uploadstatus, naechsten erwarteten `segmentIndex` oder
  `segmentOffset` und `uploadSessionTtlSeconds`
- finale Annahme eines gueltigen `artifactKind=schema` liefert neben
  Artefaktdaten auch die erzeugte `schemaRef`
- `uploadSessionTtlSeconds` ist die verbleibende Lease-TTL der Session und
  darf im Verlauf gegen `0` laufen
- der konfigurierbare Initialwert ist `900` Sekunden; die absolute
  Max-Lease ist `3600` Sekunden ab Session-Erzeugung
- ein optional konfigurierbarer Mindestwert von `300` Sekunden gilt nur fuer
  neu ausgestellte oder erneuerte Lease-Werte, nicht fuer die kurz vor
  Ablauf gemeldete Restlaufzeit
- jede erfolgreiche Segmentannahme darf die Lease erneuern, aber niemals
  ueber die absolute Max-Lease von `3600` Sekunden ab Session-Erzeugung
  hinaus
- zusaetzlich gilt ein Idle-Timeout von `300` Sekunden ohne erfolgreiche
  Segmentannahme; bei Idle-Timeout oder abgelaufener absoluter Lease wird
  die Session `EXPIRED` und Zwischensegmente werden verworfen

Akzeptanz:

- Upload-Fehler sind deterministisch:
  - fehlende oder syntaktisch ungueltige `uploadSessionId` liefert
    `VALIDATION_ERROR`
  - wohlgeformte, aber unbekannte `uploadSessionId` liefert
    `RESOURCE_NOT_FOUND`
  - tenant- oder principal-fremde Session liefert `TENANT_SCOPE_DENIED` bzw.
    `FORBIDDEN_PRINCIPAL`
  - abgebrochene Session liefert `UPLOAD_SESSION_ABORTED`
  - abgelaufene Session liefert `UPLOAD_SESSION_EXPIRED`
- wiederholtes identisches Segment ist erfolgreich oder liefert den
  bestehenden Segmentstatus
- Wiederholung mit gleicher Segmentposition, aber anderem Inhalt oder
  Hash liefert `VALIDATION_ERROR`
- abweichender Hash fuer ein bereits angenommenes Segment liefert
  `VALIDATION_ERROR`
- zu grosse Uploads liefern `PAYLOAD_TOO_LARGE`

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
- Admin-Abbruch fremder Jobs ist auf denselben Tenant bzw. explizit
  erlaubte Tenant-Sets im PrincipalContext begrenzt
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
  passende, extern ausgestellte `approvalToken` senden
- das `approvalToken` ist an `toolName`, `tenantId`, `callerId`,
  `approvalKey` und `payloadFingerprint` gebunden
- `approvalKey` dedupliziert synchrone Nebenwirkungen: gleicher Tenant,
  Caller, Toolname, `approvalKey` und `payloadFingerprint` liefern dasselbe
  Tool-Ergebnis bzw. dieselbe Artefakt-/Provider-Referenz; abweichender
  Payload liefert `IDEMPOTENCY_CONFLICT`
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
- `POLICY_REQUIRED` enthaelt nur Challenge-/Request-Daten
  (`approvalRequestId`, `approvalKey` oder `idempotencyKey`,
  `payloadFingerprint`, erforderliche Scopes/Gruende), aber kein
  verwendbares `approvalToken`
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

- Server implementiert MCP-Standardmethoden `prompts/list` und
  `prompts/get`
- `prompts/list` liefert Name, Beschreibung, Argument-Schema und stabile
  Version/Revision je Prompt
- `prompts/get` validiert Argumente, erzeugt nur Prompt-Nachrichten aus
  erlaubten Resource-/Artifact-Refs und gibt keine Secrets oder Rohdaten aus
- Prompts sind Vorlagen, keine versteckten Tool-Ausfuehrungen
- Prompts verweisen auf MCP-Tools und Ressourcen, statt grosse Inhalte
  direkt in den Kontext zu kopieren
- Prompt-Parameter sind schema-validiert und duerfen keine Secrets
  enthalten
- Prompt-Definitionen muessen versioniert oder stabil benannt sein,
  damit Clients sie referenzieren koennen

Akzeptanz:

- Prompts sind ueber MCP-Discovery sichtbar
- `prompts/list` und `prompts/get` funktionieren ueber `stdio` und HTTP
- jeder Prompt nennt die erforderlichen Resource-Refs und die
  erwarteten Tool-Schritte
- unzulaessige Parameter liefern `VALIDATION_ERROR`
- unbekannter Prompt liefert MCP-konformen `prompts/get`-/JSON-RPC-Fehler
  mit d-migrate-Code in `error.data`
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
- `POLICY_DENIED`
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
- Store-Interfaces fuer Jobs, Artefakte, Artefaktbytes, Upload-Sessions,
  Uploadsegmente, Connection-Refs, Idempotency, Approval-Grants, Quotas
  und Audit definieren
- `UploadSegmentStore` und `ArtifactContentStore` mit File-Spooling fuer
  produktnahe Pfade definieren; In-Memory nur fuer Unit-Tests
- Quota-/Rate-Limit-Service fuer Tenant-/Principal-Grenzen und Timeout-
  Konfiguration einfuehren
- In-Memory-Implementierungen fuer Tests bereitstellen
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
- Unit-Tests decken `UploadSegmentStore`, `ArtifactContentStore`, atomare
  Segmentwrites, Range-/Chunk-Reads, TTL-Cleanup, Quotas und Timeout-
  Mapping ab

### Phase B - MCP-Modul und Transport

- `adapters:driving:mcp` in Gradle aufnehmen
- MCP-Server-Bootstrap fuer `stdio` und streambares HTTP implementieren
- Initialize fuer MCP `protocolVersion` `2025-11-25` implementieren;
  d-migrate `v1` wird nur als Tool-/Resource-Vertragsversion in
  Capabilities oder `capabilities_list` sichtbar
- Streamable-HTTP-Endpunkt mit MCP-konformen `POST`-/`GET`-Regeln
  implementieren:
  - `POST`-`Accept`-Header muss Media Ranges robust parsen und sowohl
    `application/json` als auch `text/event-stream` als unterstuetzte
    Content Types erkennen; Reihenfolge, Whitespace und Parameter sind
    nicht normativ
  - JSON-RPC-Notification oder JSON-RPC-Response per `POST` liefert bei
    Annahme HTTP `202 Accepted` ohne Body
  - JSON-RPC-Request per `POST` liefert entweder
    `Content-Type: application/json` mit genau einem JSON-Objekt oder
    `Content-Type: text/event-stream` fuer SSE
  - `MCP-Protocol-Version` nach erfolgreichem Initialize
  - `Origin`-Validierung gegen DNS-Rebinding
  - optionales `MCP-Session-Id` mit 400/404-Fehlerpfaden
  - definierte `GET`-Semantik fuer SSE oder HTTP 405
  - definierte `DELETE`-Semantik fuer Session-Terminierung oder HTTP 405
- HTTP-Authorization nach MCP 2025-11-25 fuer nicht-lokales
  streambares HTTP verpflichtend implementieren:
  - HTTP 401 mit `WWW-Authenticate` und `resource_metadata`
  - Protected Resource Metadata well-known Endpoint
  - Protected Resource Metadata als JSON-Dokument mit mindestens:
    - `resource`: kanonische HTTPS-URI des MCP-Servers bzw. MCP-
      Endpunkts
    - `authorization_servers`: nicht-leere Liste der zulaessigen
      Authorization-Server-Issuer-URIs
    - `scopes_supported` mit den dokumentierten d-migrate-Scope-Namen
  - HTTP 403 mit Scope-Challenge bei unzureichenden Scopes
  - Token-Audience-/Resource-Validierung
  - konfigurierter Issuer muss exakt zu Token-`iss` und Metadata passen
  - JWKS-Validierung mit Cache/Rotation oder explizite Introspection-
    Konfiguration; beides fail-closed bei fehlender Konfiguration,
    Netzwerk-/Parsefehlern, unbekanntem `kid` oder inaktivem Token
  - Algorithmus-Allowlist, Ablauf-/Clock-Skew-Pruefung und Scope-Mapping
    fuer jedes Tool und jede Resource-Klasse
  - Scope-Tabelle implementieren und in Protected Resource Metadata als
    `scopes_supported` veroeffentlichen:
    `dmigrate:read`, `dmigrate:job:start`, `dmigrate:artifact:upload`,
    `dmigrate:data:write`, `dmigrate:job:cancel`, `dmigrate:ai:execute`,
    `dmigrate:admin`
  - Principal-Claims (`sub`, optional Tenant-/Gruppenclaims) werden
    deterministisch gemappt; fehlende Pflichtclaims liefern 401
  - keine Tokens in Query-Parametern
- Auth-Deaktivierung nur fuer lokale Tests/Demos mit expliziter
  unsicherer Konfiguration zulassen
- Auth-Deaktivierung bei HTTP nur erlauben, wenn die Bind-Adresse
  Loopback ist (`127.0.0.1` oder `::1`) und keine oeffentliche Base-URL
  konfiguriert ist; `0.0.0.0`, public Hostnames oder nicht-lokale
  Adressen muessen den Start verweigern
- Runtime-Bootstrap fuer Driver, Profiling-Adapter, Streaming-Adapter
  und Schema-Codecs aus der CLI teilen oder aequivalent kapseln
- Principal-Ableitung fuer lokale und HTTP-Transporte implementieren
- stdio-Tokenvalidierung implementieren:
  Token-Fingerprint-Lookup oder lokal signierte Tokens, Mapping auf
  `principalId`, `tenantId`, Scopes, `isAdmin`, Ablaufzeit und Audit-
  Identitaet
- Initialize-/Capability-Antwort bereitstellen
- Tool-Registry und Resource-Registry aufbauen
- MCP-Standard-Discovery implementieren:
  - `resources/list` mit Pagination fuer konkret bekannte Ressourcen
  - `resources/templates/list` fuer parametrisierte Resource-URIs,
    inklusive Chunk-URIs fuer grosse Artefakte
  - `resources` Capability im Initialize-Result korrekt setzen; keine
    `subscribe`/`listChanged`-Flags setzen, solange diese nicht
    implementiert sind
- JSON-Schema-Definitionen fuer alle 0.9.6-Tools erzeugen oder
  pflegen; `inputSchema` und `outputSchema` verwenden JSON Schema 2020-12.
  Wenn der Generator ein `$schema`-Feld ausgibt, muss es
  `https://json-schema.org/draft/2020-12/schema` enthalten; fehlt `$schema`,
  wird 2020-12 gemaess MCP-Tool-Schema-Vertrag als Dialekt angenommen.
  Aeltere Draft-spezifische Keywords sind verboten.
- Startbefehl und lokale Konfiguration dokumentieren

Abnahmekriterien:

- ein MCP-Client kann den Server initialisieren
- lokale und HTTP-Transporttests laufen gegen dieselbe Tool-Registry
- HTTP-Transporttests decken robuste Accept-Header-Auswertung,
  `MCP-Protocol-Version`, Origin-Validierung, optionales
  `MCP-Session-Id`, `GET`-Semantik und `DELETE` mit `MCP-Session-Id` ab
- negative HTTP-Transporttests decken fehlenden, syntaktisch ungueltigen
  und nicht unterstuetzten `MCP-Protocol-Version`-Header fuer Folgeaufrufe
  nach erfolgreichem Initialize ab
- Streamable-HTTP-POST-Tests decken MCP-Wire-Semantik ab:
  Notifications und JSON-RPC-Responses liefern `202 Accepted` ohne Body;
  JSON-RPC-Requests liefern `Content-Type: application/json` oder
  `text/event-stream`
- HTTP-Auth-Tests decken fuer nicht-lokales HTTP 401/403,
  `WWW-Authenticate`, Protected-Resource-Metadata und Scope-Challenges
  ab
- HTTP-Auth-Tests decken Issuer-Mismatch, fehlendes/ungueltiges JWKS,
  nicht erlaubten Algorithmus, abgelaufenes Token, Audience-/Resource-
  Mismatch, fehlende Pflichtclaims und Scope-Mapping pro Tool ab
- stdio-Auth-Tests decken unbekannte, ungueltige, abgelaufene und
  scope-arme `DMIGRATE_MCP_STDIO_TOKEN`-Werte sowie korrektes Mapping auf
  Tenant, Scopes, Admin-Status und Audit-Identitaet ab
- Scope-Tests pruefen fuer jede Scope-Klasse mindestens einen positiven und
  einen negativen Request sowie die konkrete `scope`-Challenge im
  `WWW-Authenticate`-Header
- Golden-Test validiert das Protected-Resource-Metadata-Dokument:
  `resource` entspricht der kanonischen Server-/Endpoint-URI,
  `authorization_servers` ist vorhanden, nicht leer und enthaelt nur
  zugelassene HTTPS-Issuer; `scopes_supported` enthaelt die dokumentierten
  Scope-Namen und ist konsistent mit den Scope-Challenges
- Golden-Test validiert Tool-JSON-Schemas gegen JSON Schema 2020-12 und
  prueft, dass ein vorhandenes `$schema` exakt auf
  `https://json-schema.org/draft/2020-12/schema` zeigt
- ein separater Test zeigt, dass Auth-Deaktivierung nur fuer lokale
  Test-/Demo-Konfigurationen erlaubt ist
- Auth-Deaktivierung mit `0.0.0.0`, Public Base URL oder nicht-lokaler
  Bind-Adresse wird mit klarem Konfigurationsfehler abgelehnt
- Abschnitt-0.9.6-A-HTTP ohne vollstaendige JWKS-/Introspection-Auth darf
  nur auf Loopback starten; nicht-lokale Bindings schlagen fail-closed fehl
- Driver- und Codec-Registry sind nach MCP-Serverstart befuellt
- `capabilities_list` funktioniert ohne DB-Verbindung
- `resources/list` liefert Jobs, Artefakte, Schemas, Profile, Diffs und
  Connection-Refs tenant-/principal-gefiltert mit MCP-konformem
  `nextCursor`
- `resources/templates/list` veroeffentlicht die
  `dmigrate://tenants/{tenantId}/...`-Templates inklusive Chunk-
  Resource-Template
- unbekannte Toolnamen liefern einen MCP-/JSON-RPC-Protokollfehler;
  `UNSUPPORTED_TOOL_OPERATION` wird nur fuer bekannte Tools mit
  fachlich nicht unterstuetzter Operation/Option genutzt

### Phase C - Read-only Tools

- `schema_validate` an bestehenden Validator und Format-Reader
  anbinden
- `schema_generate` an bestehende DDL-Generatoren anbinden
- `schema_generate` fuer `schemaRef` und kleine Inline-Schemas
  anbinden, ohne Upload- oder Policy-Zwang fuer Inline-Read-only-Nutzung
- grosse Schema-Dateien koennen ueber
  `artifact_upload_init(uploadIntent=schema_staging_readonly)` ohne
  Write-Policy in eine read-only `schemaRef` materialisiert und danach von
  `schema_validate`, `schema_generate` und `schema_compare` genutzt werden
- dafuer in Phase C ein read-only Upload-Subset implementieren:
  - `artifact_upload_init` nur fuer `uploadIntent=schema_staging_readonly`
  - Segmentannahme in `UploadSegmentStore`
  - File-Spooling, Gesamt-Hash, TTL-/Abort-Cleanup
  - Finalisierung zu `schema_staging_readonly`-Artefakt und read-only
    `schemaRef`
  - keine Policy-/Approval-Pfade und keine Nutzung als `job_input`
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
- `schema_generate` erzeugt DDL fuer kleine Inline-Schemas ohne
  vorheriges Materialisieren
- read-only Schema-Staging fuer grosse Schemas funktioniert ohne
  Approval-Flow und verletzt trotzdem Quota-, Audit- und Byte-Store-
  Anforderungen nicht
- das Phase-C-Subset reicht fuer read-only Schema-Staging im
  Lieferabschnitt 0.9.6-A; policy-pflichtige Upload-Intents bleiben Phase F
- keine Tool-Antwort verletzt die Inline-Limits

### Phase D - Discovery und Ressourcen

Detailplan: [`ImpPlan-0.9.6-D.md`](./ImpPlan-0.9.6-D.md)

- `job_status_get`, `job_list`, `artifact_list`, `artifact_chunk_get`,
  `schema_list`, `profile_list` und `diff_list` implementieren
- MCP-Standard-Discovery `resources/list` und
  `resources/templates/list` an dieselben Store-/Resolver-Regeln binden
- Resource-Resolver und Store-/Index-Abstraktionen fuer Jobs,
  Artefakte, Schemas, Profile und Diffs implementieren
- Resource-Resolver und Store fuer tenant-scoped Connection-Refs
  implementieren, inklusive Sensitivitaets-/Produktionsmetadaten fuer
  Policy
- bestehende CLI-Config-/Connection-Aufloesung aus
  `adapters/driving/cli` hinter einen adapterneutralen Port ziehen:
  Contract in `hexagon:ports` bzw. einem bestehenden Port-Modul,
  YAML-/Projektkonfigurationsparser in einem dedizierten Config-/Driven-
  Modul. `hexagon:application` darf nur den Port benutzen und bekommt
  keinen YAML-/CLI-Parser, weil diese Schicht nur Core/Ports kennt.
- CLI und MCP muessen diesen gemeinsamen Bootstrap nutzen; der MCP-
  Adapter darf nicht vom CLI-Adapter oder
  `dev.dmigrate.cli.config.NamedConnectionResolver` abhaengen und darf
  Config-Parsing nicht duplizieren
- Bootstrap fuer Connection-Refs aus `.d-migrate.yaml` oder
  aequivalenter Projekt-/Serverkonfiguration anbinden; Tests nutzen
  explizite Seeds ohne echte Secrets
- adapterneutralen `ConnectionSecretResolver` fuer Ausfuehrungspfade
  definieren; Discovery/Bootstrap duerfen nur `credentialRef`/
  `providerRef` und non-secret Metadaten sehen
- bestehende Named-Connection-Logik so splitten, dass ENV-/Secret-
  Substitution erst im autorisierten Runner/Driver-Pfad passiert und nicht
  beim MCP-Resource-Bootstrap
- Cursor-Paginierung und Filtervalidierung einfuehren
- Cursor-/Pagination-Token gegenueber Clients **kapseln**: sobald ein
  produktiver Store oder ein oeffentlicher Adapter (REST/MCP/gRPC)
  Pagination publiziert, muss der Token auf Adapter-Ebene Base64-codiert,
  signiert oder per HMAC-MAC versiegelt werden, damit Clients nicht
  durch Token-Modifikation an fremde Daten kommen oder Cursor-
  Stabilitaet brechen koennen. Der Kernvertrag (`PageRequest`/
  `PageResult`) bleibt unveraendert; die Kapselung ist Adapter-
  Verantwortung. Phase-A-In-Memory-Stores duerfen weiterhin
  Offset-Tokens verwenden; Tests duerfen die Kapselung nicht annehmen.
- Tenant-Scope-Pruefung fuer jede Resource-Aufloesung erzwingen

Abnahmekriterien:

- Listen liefern stabile typisierte Collection-Felder (`jobs`, `artifacts`,
  `schemas`, `profiles`, `diffs`) und `nextCursor`; `totalCount` ist exakt und
  optional, `totalCountEstimate` ist optional und als Naeherung gekennzeichnet
- modifizierte oder fremde Cursor-/Pagination-Token liefern
  `VALIDATION_ERROR`, niemals fremde Tenant-Daten
- Profile und Diffs sind ueber `profile_list` bzw. `diff_list`
  auffindbar, auch wenn die Persistenz intern ueber typisierte
  Artefakte erfolgt
- fremde Tenant-Ressourcen liefern `TENANT_SCOPE_DENIED`
- sensitive Connection-Refs liefern nur secret-freie `allowedOperations`- und
  Policy-Hinweise; Phase D implementiert dadurch keine Start-Tools fuer
  `schema_compare_start`, Reverse, Import oder Transfer
- Connection-Refs koennen in Tests aus dokumentierten Seeds
  geladen werden, ohne Secrets in MCP-Payloads zu uebergeben
- reale connection-backed Pfade funktionieren nur mit dokumentiertem
  `ConnectionSecretResolver`; fehlt dieser, liefern sie einen
  fail-closed Konfigurationsfehler statt teilweise expandierter Secret-URLs
- CLI- und MCP-Startpfade nutzen dieselbe adapterneutrale
  Config-/Connection-Ref-Aufloesung
- ungueltige Cursor liefern `VALIDATION_ERROR`

### Phase E0 - Cancel-Gate fuer bestehende Runner

- Vorab-Spike fuer Cancel-Faehigkeit der bestehenden Runner:
  - `SchemaReverseRunner`
  - Profiling-Runner/-Services
  - `DataImportRunner`
  - `DataTransferRunner`
  - betroffene Streaming-/Writer-Pfade
- Spike-Abnahme: fuer jeden Pfad ist dokumentiert, an welchen Stellen
  CancellationToken/Worker-Handle geprueft wird und welche Side Effects
  nach Cancel verhindert werden
- Gate-Entscheidung: `job_cancel` bleibt harte 0.9.6-DoD. Eine reduzierte
  Semantik wie nur `cancel_requested`, Store-Status ohne Worker-
  Propagation oder "best effort ohne Side-Effect-Stopp" ist fuer 0.9.6
  ausgeschlossen
- wenn der Spike fuer einen der genannten Langlaeufer keine kooperative
  Cancel-Propagation mit Side-Effect-Stopp nachweisen kann, ist der
  Milestone blockiert und Phase E darf nicht als fertig gelten

### Phase E - Async-Jobs, Idempotenz und Policy

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
- Idempotency-Zustandsautomat fuer Start-Tools implementieren:
  `PENDING`, `AWAITING_APPROVAL`, `DENIED`, `COMMITTED`, `FAILED`
- genehmigte Retries aus `AWAITING_APPROVAL` muessen die Reservierung
  atomar claimen und genau eine Job-Erzeugung ausloesen
- Quotas fuer aktive Jobs pro Tenant/Principal und Operation pruefen;
  Ueberschreitung liefert `RATE_LIMITED`
- Start-/Runner-Timeouts konfigurieren und auf `OPERATION_TIMEOUT` mappen
- Grant-Aussteller implementieren und dokumentieren:
  lokale Policy-Allowlist, schmales MCP-Admin-Grant-Unterkommando oder
  signierte Grant-Datei; optionaler Demo-Auto-Approval-Modus nur fuer
  Loopback/`stdio` und mit Audit
- `ApprovalGrantStore` speichert Grant-Metadaten, Tenant, Ablauf,
  `correlationKind`, `correlationKey`, Issuer-Fingerprint, Scope,
  Payload-Fingerprint und Token-Fingerprint, aber nie rohe Tokens
- Jobstatus exakt aus `docs/job-contract.md` verwenden:
  `queued`, `running`, `succeeded`, `failed`, `cancelled`
- Ablauf und Retention von Jobs ueber `expiresAt` modellieren, nicht
  ueber einen zusaetzlichen Jobstatus

Abnahmekriterien:

- fehlender Idempotency-Key liefert `IDEMPOTENCY_KEY_REQUIRED`
- identische Wiederholung liefert denselben Job
- Payload-Konflikte liefern `IDEMPOTENCY_CONFLICT`
- fehlende Policy-Freigabe liefert `POLICY_REQUIRED`
- policy-pflichtige Tools koennen ueber mindestens einen Grant-Pfad
  produktiv freigegeben werden; ohne konfigurierten Grant-Aussteller ist
  das Verhalten fail-closed und dokumentiert
- erster policy-pflichtiger Start ohne Grant erzeugt
  `AWAITING_APPROVAL`; zweiter Aufruf mit gueltigem Grant startet genau
  einen Job und setzt die Reservierung auf `COMMITTED`
- wiederholter zweiter Aufruf nach `COMMITTED` liefert denselben Job
- Quota- und Timeout-Faelle liefern `RATE_LIMITED` bzw.
  `OPERATION_TIMEOUT` und werden auditiert
- `schema_compare_start` mit `connectionRef` laeuft als abbrechbarer Job
  und dedupliziert ueber `idempotencyKey`
- `schema_compare_start` ohne Connection-Ref bleibt ebenfalls
  policy-pflichtig und auditierbar
- `job_cancel` kann nur eigene oder erlaubte Jobs abbrechen und
  erzeugt einen stabilen `cancelled`-Status gemaess
  `docs/job-contract.md`
- nach angenommenem Cancel publiziert der Worker keine neuen Artefakte
  und startet keine weiteren Daten-Schreiboperationen

### Phase F - Datenoperationen und policy-pflichtige Uploads

- policy-pflichtige `artifact_upload_init`-Varianten mit vollstaendigen
  Session-Metadaten, Gesamt-Checksumme und serverseitiger Session-
  Erzeugung umsetzen; das read-only Schema-Staging-Subset existiert bereits
  aus Phase C
- `approvalKey` fuer `artifact_upload_init` als synchrone Idempotency-
  Reservierung fuer policy-pflichtige Init-Pfade nutzen, sodass
  Agent-Retries dieselbe Session statt doppelter Sessions erzeugen
- `artifact_upload` mit Segmentverwaltung fuer bestehende Sessions
  umsetzen
- Segmentbytes in `UploadSegmentStore` schreiben; finalisierte Artefakte
  immutable in `ArtifactContentStore` materialisieren
- `contentBase64` decodieren, Segmentgroessenlimit erzwingen und Hashes
  ueber decodierte Bytes berechnen
- `artifact_upload_abort` umsetzen
- SHA-256-Pruefung pro Segment und Gesamtartefakt erzwingen
- Policy-Pruefung fuer Upload-Init und administrative Upload-Abbrueche
  anbinden; eigene Upload-Abbrueche ueber Session-Owner-Pruefung erlauben
- `data_import_start` an hochgeladene Artefakte und ein tenant-scoped
  `targetConnectionRef` binden
- `data_transfer_start` an Connection-Refs und Policy binden
- Idempotency-Pruefung fuer `data_import_start` und
  `data_transfer_start` anbinden
- Upload- und Artefakt-Limits zentral konfigurieren
- Quotas fuer aktive Upload-Sessions, reservierte Upload-Bytes, gespeicherte
  Artefaktbytes und parallele Segmentwrites erzwingen
- Cleanup fuer TTL, Abort und Expiry gegen `UploadSegmentStore` und
  `ArtifactContentStore` anbinden

Abnahmekriterien:

- Upload-Init liefert vor dem ersten Segment eine `uploadSessionId`, TTL
  und erwartete Startposition
- wiederholtes policy-pflichtiges `artifact_upload_init` mit gleichem
  `approvalKey` und identischem Payload liefert dieselbe
  `uploadSessionId`; abweichender Payload mit gleichem Scope liefert
  `IDEMPOTENCY_CONFLICT`
- read-only Schema-Staging kann grosse Schemas ohne Write-Policy zu einer
  nur read-only nutzbaren `schemaRef` materialisieren
- Versuch, eine read-only gestagte `schemaRef` oder ein
  `schema_staging_readonly`-Artefakt fuer Import/Transfer/KI-Tools zu
  nutzen, liefert `VALIDATION_ERROR` oder `POLICY_REQUIRED` fuer einen
  neuen policy-pflichtigen Pfad
- Upload-Resume mit wiederholten Segmenten funktioniert
- Hash-Abweichungen werden abgewiesen
- fehlendes oder zu grosses `contentBase64` wird mit
  `VALIDATION_ERROR` bzw. `PAYLOAD_TOO_LARGE` abgewiesen
- Segmentwrites sind atomar; konkurrierende abweichende Writes fuer dieselbe
  Session/Position liefern einen deterministischen Fehler
- `artifact_chunk_get`, Import aus Artefakt und Resource-Chunk-Reads lesen
  aus `ArtifactContentStore`, nicht aus Tool-Response- oder Heap-Kopien
- 200-MiB-Upload-Test nutzt File-Spooling oder einen gleichwertigen Byte-
  Store und belegt, dass keine RAM-only-Implementierung erforderlich ist
- Quota-Verletzungen fuer aktive Sessions oder Bytes liefern `RATE_LIMITED`
- finalisierte Artefakte sind immutable
- Upload-Init, administrative Upload-Abbrueche, Import und Transfer
  laufen nur mit Policy-Freigabe; eigene aktive Upload-Sessions duerfen
  per Owner-Pruefung abgebrochen werden
- Import/Transfer-Retries mit gleichem Idempotency-Store-Key
  deduplizieren; abweichender Payload mit gleichem Store-Key liefert
  `IDEMPOTENCY_CONFLICT`

### Phase G - KI-nahe Tools, Prompts, Tests und Dokumentation

- adapterneutralen `AiProviderPort` bzw. KI-Ausfuehrungsport definieren:
  Eingabe-Summary, Modell-/Provider-Auswahl, Policy-Kontext,
  Prompt-Fingerprint und redigierte Payloads
- `NoOp`-Provider fuer Tests und lokale Defaults implementieren,
  der keine externen Netzwerkaufrufe macht und deterministische
  Plan-/Transformationsantworten liefert
- optionale lokale Provider-Konfiguration fuer Ollama/LM Studio
  vorbereiten; externe Provider wie OpenAI/Anthropic bleiben nur
  konfigurierbar, wenn Secrets, Endpoint, Modell und erlaubende Policy
  explizit gesetzt sind
- Secret-Scrubbing und Prompt-Hygiene vor jedem Provider-Aufruf erzwingen
  und in Tests mit Secret-/Massendaten-Faellen abdecken
- Provider-, Modell-, Modellversions-, Prompt-Fingerprint- und
  Payload-Fingerprint-Metadaten in Audit-Ereignisse schreiben
- `procedure_transform_plan`, `procedure_transform_execute` und
  `testdata_plan` policy- und audit-gesteuert anbinden
- `approvalKey` fuer KI-nahe Tools als synchrone Idempotency-Reservierung
  nutzen, damit Timeouts/Agent-Retries keine doppelten Provider-Kosten oder
  Artefakt-Publishes erzeugen
- MCP-Prompts fuer kuratierte Analyse-, Transformations- und
  Testdatenablaeufe registrieren
- `prompts/list` und `prompts/get` inklusive Argument-Schemas,
  Argumentvalidierung, unbekanntem Prompt und Hygiene-Fehlern implementieren
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
  Grenzen aktualisieren

Abnahmekriterien:

- neue Tests laufen in den passenden Gradle-Modulen
- alle Roadmap-Aufgaben aus 0.9.6 sind durch Tests oder dokumentierte
  Abnahmekriterien abgedeckt
- KI-nahe Tools liefern Policy-, Audit- und Prompt-Hygiene-Fehler
  strukturiert statt als rohe Exceptions
- KI-nahe Tools funktionieren im Testpfad mit dem `NoOp`-Provider
  ohne externe Secrets oder Netzwerkzugriff
- KI-nahe Tool-Retries mit gleichem `approvalKey` und identischem Payload
  liefern dasselbe Ergebnis bzw. dieselbe Artefakt-/Provider-Referenz;
  abweichender Payload mit gleichem Scope liefert `IDEMPOTENCY_CONFLICT`
- externe Provider-Aufrufe sind ohne erlaubende Konfiguration und Policy
  blockiert
- MCP-Prompts sind ueber Discovery sichtbar und referenzieren nur
  erlaubte Ressourcen/Tools
- `prompts/get` liefert fuer jeden verbindlichen Prompt validierte
  Prompt-Nachrichten; ungueltige Argumente, unbekannte Prompts und Hygiene-
  Verletzungen sind getestet
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
- atomare Idempotency-Reservierung `reserveOrGet` mit `PENDING`,
  `AWAITING_APPROVAL`, `COMMITTED`, Konfliktpfad und TTL-/
  Fehlerbehandlung
- genehmigter Retry aus `AWAITING_APPROVAL` erzeugt genau einen Job und
  liefert bei weiteren Wiederholungen denselben `COMMITTED` Job
- Lease-/Recovery-/Denial-Semantik fuer `PENDING`,
  `AWAITING_APPROVAL` und `DENIED`, inklusive `POLICY_DENIED`
- Approval-Grant-Store mit `correlationKind`/`correlationKey` und
  Token-Fingerprint statt Roh-Token
- Grant-Aussteller fuer lokale Allowlist, schmales
  MCP-Admin-Grant-Unterkommando oder signierte Grant-Datei inklusive
  Ablauf, Issuer-Fingerprint und Audit-Metadaten
- Sync-Effect-Idempotency fuer `approvalKey` mit atomarer Reservierung,
  identischer Wiederholung und Konfliktpfad
- Quota-/Rate-Limit-Service fuer aktive Jobs, Upload-Sessions,
  Upload-Bytes, parallele Segmentwrites und Provider-Aufrufe
- Policy-Entscheidungen
- HTTP-Tokenvalidator fuer Issuer, JWKS/Introspection, Algorithmus-
  Allowlist, Audience/Resource, Pflichtclaims, Ablauf und Scope-Mapping
- Around-/Finally-Audit fuer Auth-, Validation-, Scope-, Policy- und
  Idempotency-Fehler
- Fehlercode-Mapping
- MCP-Wire-Mapping: JSON-RPC-Errors nur fuer Protokollfehler,
  fachliche Toolfehler als `tools/call`-Result mit `isError=true`
- Inline-Limit-Entscheidung
- `resources/read`-Limitierung ohne schemafremde Parameter; Chunking
  ueber Resource-Templates/Chunk-URIs oder `artifact_chunk_get`
- MCP-Standard-Discovery ueber `resources/list` und
  `resources/templates/list`
- Upload-Session-Zustandsautomat
- SHA-256-Segment- und Gesamtpruefung
- Base64-Decoding, Segmentgroessenlimit und Hash-Berechnung ueber
  decodierte Segmentbytes
- `UploadSegmentStore` und `ArtifactContentStore`: atomare Segmentwrites,
  immutable Finalisierung, Range-/Chunk-Reads, File-Spooling, Quotas und
  TTL-/Abort-Cleanup
- Upload-Session-TTL, opake `uploadSessionId` mit Zeichen-/Entropie-/
  Laengenvalidierung und fortlaufende `segmentIndex`-Validierung
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
- adapterneutraler Config-/Connection-Ref-Bootstrap ohne Abhaengigkeit
  von `adapters/driving/cli` und ohne dupliziertes YAML-Parsing
- Connection-Secret-Materialisierung nur im autorisierten Runner-/Driver-
  Pfad ueber `ConnectionSecretResolver`; Discovery/Audit/Listings sehen
  nur `credentialRef`/`providerRef`
- SchemaStore, ProfileStore und DiffStore bzw. gleichwertige typisierte
  Artefakt-Indizes fuer Listing, Resource-Resolution und Tenant-Scope
- Schema-Materialisierung aus finalisiertem `artifactKind=schema`-
  Upload inklusive Validierungsfehler ohne Registrierung
- job_status_get-Vertrag fuer `jobId`/`resourceUri`, Owner-/Admin-
  Berechtigungen, Tenant-Fehler, terminale Jobs und Retention
- MCP-konforme Resource-Reads: `resources/read` nur mit `uri`, Chunking
  ueber Resource-Templates/Chunk-URIs oder `artifact_chunk_get`
- Policy-Challenge-Flow: `POLICY_REQUIRED` enthaelt keinen verwendbaren
  Token; Grants entstehen nur serverseitig nach Policy-/Human-/Admin-
  Entscheidung
- minimaler `AiProviderPort`, `NoOp`-Provider, Provider-Konfiguration,
  Secret-Scrubbing und Provider-Audit-Metadaten
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

Fuer Start-Tools und synchrone Side-Effect-Tools zusaetzlich:

- fehlender Idempotency-Key
- identische Wiederholung
- identische Wiederholung eines bereits akzeptierten Jobs liefert auch
  nach Ablauf oder Entzug des Approval-Grants denselben Job, sofern Auth,
  Schema und Scope weiter gueltig sind
- Konflikt mit anderem Payload
- Idempotency-Konflikt liefert `IDEMPOTENCY_CONFLICT` ohne vorherige
  Policy-Pruefung
- parallele identische Starts gegen denselben Store-Key erzeugen nur
  eine `PENDING`-Reservierung und maximal einen Job
- gleicher `idempotencyKey` in anderem Tenant-, Caller- oder
  Operations-Scope ist unabhaengig und leakt keine fremde Nutzung
- Policy-Required-Flow
- Policy-Required-Flow gibt `approvalRequestId`/Challenge zurueck und
  kein direkt wiederverwendbares `approvalToken`
- `schema_compare` weist `connectionRef` synchron ab; connection-backed
  Vergleiche laufen ueber `schema_compare_start`
- synchroner Approval-Flow mit `approvalKey` fuer
  policy-pflichtige `artifact_upload_init`-Varianten, administrative
  `artifact_upload_abort`-Aufrufe und KI-nahe Tools
- synchrone Nebenwirkungen deduplizieren ueber `approvalKey`; identische
  Retries erzeugen keine zweite Upload-Session, keinen zweiten Provider-
  Aufruf und kein zweites Artefakt
- gleicher `approvalKey`-Scope mit anderem Payload liefert
  `IDEMPOTENCY_CONFLICT`
- `artifact_upload` akzeptiert Segmente nur mit gueltiger
  session-scoped Upload-Berechtigung
- `artifact_upload_abort` der eigenen aktiven Session ohne Policy ist
  erfolgreich und prueft nur Tenant, Principal, Session-Owner und
  Session-Status
- Idempotency-Flow fuer `data_import_start` und `data_transfer_start`

### 9.3 Integrationstests

Mindestens ein Integrationstest startet den MCP-Server ueber `stdio` und
ein weiterer ueber HTTP.

Gemeinsame Tests fuer `stdio` und HTTP:

- Initialize/Capabilities
- Initialize verhandelt MCP `protocolVersion` `2025-11-25` und trennt
  davon den d-migrate-Toolvertrag `v1`
- `capabilities_list`
- `schema_validate` mit kleinem Schema
- `schema_generate` mit kleinem Inline-Schema und mit Artefakt-Fallback
- Driver-Lookup ueber die normale MCP-Runtime-Registry
- Schema-Codec-Lookup ueber die normale MCP-Runtime-Registry
- `schema_compare` mit zwei `schemaRef`-Eingaengen
- `schema_compare_start` mit `connectionRef`, `idempotencyKey`,
  Policy-Flow und abbrechbarem Job
- `schema_reverse_start` mit Connection-Ref, Policy-Flow, Driver-Lookup,
  Jobstatus und abbrechbarem Runner
- `data_profile_start` mit Connection-Ref, Policy-Flow, Profiling-Adapter,
  Jobstatus und abbrechbarem Runner
- `artifactKind=schema` Upload materialisiert nach erfolgreicher
  Validierung eine `schemaRef`; ungueltige Schema-Artefakte werden nicht
  registriert
- `data_import_start` referenziert ein hochgeladenes Artefakt, nutzt ein
  tenant-scoped `targetConnectionRef`, Policy/Idempotency, startet den
  Import-Runner und kann abgebrochen werden
- `data_transfer_start` nutzt tenant-scoped Source-/Target-Connection-Refs,
  Policy/Idempotency, Transfer-Runner und Cancel-Propagation
- Rate-Limit-/Quota-Integration fuer aktive Jobs, aktive Upload-Sessions,
  Upload-Bytes und Provider-Aufrufe liefert `RATE_LIMITED` bzw. HTTP 429
  ausserhalb erfolgreicher Tool-Results
- Timeout-Integration fuer Runner-Start, Upload-Finalisierung und
  KI-/Provider-Aufruf liefert `OPERATION_TIMEOUT` und Audit-Outcome
- `job_status_get` fuer eigenen Job, erlaubten Admin-/Scope-Zugriff,
  fremden Tenant, unbekannten Job und terminalen Job vor Retention
- fachlicher Toolfehler mit `isError=true` und
  `structuredContent.error.code`
- unbekannter Toolname als Protokollfehler mit numerischem
  JSON-RPC-`error.code`
- `resources/read` fuer fehlende Resource liefert JSON-RPC-Error
  `-32002` mit `error.data.dmigrateCode=RESOURCE_NOT_FOUND`
- `resources/read` akzeptiert nur `uri` und liefert MCP-konforme
  `contents[]`; grosse Ressourcen werden ueber Chunk-URI oder
  `artifact_chunk_get` getestet
- `resources/list` und `resources/templates/list`
- `artifact_chunk_get` fuer ein grosses Artefakt mit `nextChunkUri`
- fachlich nicht unterstuetzte Option eines bekannten Tools als
  `isError=true` mit `UNSUPPORTED_TOOL_OPERATION`
- `job_list`
- Upload eines kleinen Artefakts in mehreren Segmenten
- `job_cancel`
- ein KI-nahes Spezialtool mit Policy-/Audit-Pfad
- MCP-Prompt-Discovery
- `prompts/list` und `prompts/get` mit gueltigen Argumenten

HTTP-only Tests:

- Streamable-HTTP-POST mit robust geparstem `Accept`-Header, der
  `application/json` und `text/event-stream` in beliebiger Reihenfolge
  listet
- HTTP-Folgeaufruf mit `MCP-Protocol-Version`
- HTTP-Folgeaufruf ohne, mit ungueltigem oder nicht unterstuetztem
  `MCP-Protocol-Version`-Header wird abgewiesen
- JSON-RPC-Notification/-Response per Streamable-HTTP-POST liefert
  `202 Accepted` ohne Body
- JSON-RPC-Request per Streamable-HTTP-POST liefert
  `Content-Type: application/json` oder `text/event-stream`
- ungueltiger `Origin` liefert HTTP 403
- optionales `MCP-Session-Id` wird korrekt ausgegeben, verlangt,
  abgewiesen oder mit HTTP 404 als abgelaufen behandelt
- HTTP-`GET` liefert SSE oder HTTP 405 gemaess Server-Konfiguration
- HTTP-`DELETE` mit `MCP-Session-Id` terminiert die Session oder liefert
  HTTP 405, wenn clientseitige Terminierung deaktiviert ist
- nicht-lokaler HTTP-Transport erzwingt Auth und liefert fuer fehlende
  oder unzureichende Tokens die dokumentierten 401/403-Antworten
- HTTP-Transport akzeptiert nur Tokens von konfigurierten Issuern mit
  gueltiger JWKS-/Introspection-Pruefung, erlaubtem Algorithmus,
  passender Audience/Resource und den benoetigten Tool-/Resource-Scopes
- `artifact_upload` ueber streambares HTTP mit `contentBase64` im
  `tools/call`-JSON-RPC-Argument

Stdio-only Tests:

- Principal-Ableitung aus validierter Host-Attestation,
  `DMIGRATE_MCP_STDIO_TOKEN` oder explizitem Test-Principal
- ungepruefte OS-User-, Prozess- oder Umgebungsdaten ohne Registry-Mapping
  liefern `AUTH_REQUIRED`
- `DMIGRATE_MCP_STDIO_TOKEN` wird per Fingerprint-Lookup oder lokaler
  Signaturvalidierung geprueft und mappt Tenant, Scopes, Admin-Status,
  Ablaufzeit und Audit-Identitaet
- ungueltiger, unbekannter, abgelaufener oder scope-armer stdio-Token
  liefert `AUTH_REQUIRED` bzw. `FORBIDDEN_PRINCIPAL`
- fehlender stdio-Principal liefert `AUTH_REQUIRED`
- `artifact_upload` ueber `stdio` mit `contentBase64`

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
- `uploadIntent=schema_staging_readonly` mit anderem `artifactKind` als
  `schema`
- read-only gestagtes Schema als `job_input` fuer Import/Transfer/KI-Tool
- fehlendes `contentBase64`
- ungueltige `uploadSessionId`-Zeichen, zu geringe Entropie oder Laenge
  ueber 128 Zeichen
- clientseitig gesetzte oder vorgeschlagene `uploadSessionId` im
  `artifact_upload_init`
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
- `data_import_start` ohne `targetConnectionRef`
- `schema_compare` mit `connectionRef`
- `schema_compare_start` mit `connectionRef` ohne `idempotencyKey`
- `schema_compare_start` ohne Policy-Freigabe
- Listen-Tool mit fremdem `tenantId` ohne Admin-/Cross-Tenant-Scope
- `resources/read` mit schemafremden Parametern wie `cursor`,
  `limitBytes`, `rangeStart` oder `rangeEnd`
- `resources/read` fuer eine Ressource ueber dem Antwortlimit ohne
  Chunk-URI oder Artefakt-Referenz
- HTTP-Auth-Deaktivierung mit `0.0.0.0`, Public Base URL oder nicht-
  lokaler Bind-Adresse
- policy-pflichtiges `artifact_upload_init` ohne `approvalKey`
- policy-pflichtiges `artifact_upload_init`-Retry mit gleichem
  `approvalKey` erzeugt keine zweite Session
- KI-nahes Tool-Retry mit gleichem `approvalKey` erzeugt keinen zweiten
  Provider-Aufruf
- gleicher `approvalKey` mit abweichendem Payload liefert
  `IDEMPOTENCY_CONFLICT`
- `artifact_upload` ohne vorherige Upload-Session
- `artifact_upload` mit gueltiger Session, aber fehlender oder fremder
  session-scoped Upload-Berechtigung
- `artifact_upload_abort` einer fremden Session ohne Owner-Recht
- administrativer `artifact_upload_abort` ohne Policy-Freigabe
- KI-nahes Tool ohne `approvalKey`
- Wiederverwendung eines Approval-Tokens mit anderem
  `payloadFingerprint`
- `POLICY_REQUIRED`-Antwort enthaelt kein verwendbares `approvalToken`
- zweiter Approval-Aufruf mit zusaetzlichem `approvalToken` veraendert
  den Payload-Fingerprint nicht
- Upload-Berechtigung bleibt ueber mehrere Segmente derselben Session
  stabil und kann nicht fuer eine andere `uploadSessionId` genutzt werden
- `data_import_start` oder `data_transfer_start` ohne
  `idempotencyKey`
- `data_import_start` oder `data_transfer_start` mit gleichem
  `idempotencyKey`, aber anderem Payload
- policy-pflichtiger Start bleibt nach erstem Aufruf ohne Grant in
  `AWAITING_APPROVAL` und startet erst beim genehmigten Retry
- genehmigte parallele Retries derselben `AWAITING_APPROVAL`-Reservierung
  erzeugen maximal einen Job
- verwaiste `PENDING`-Reservierung nach `pendingLeaseExpiresAt` wird fuer
  denselben Scope/Fingerprint recovered
- explizit abgelehnte Approval-Reservierung liefert `POLICY_DENIED`
- ueberschrittene Job-, Upload-Session-, Byte- oder Provider-Quota
- Runner-, Upload-Finalisierungs- oder Provider-Timeout
- `job_cancel` fuer fremde Tenants oder fremde Principals
- Worker publiziert nach angenommenem Cancel ein neues Artefakt oder
  startet weitere Daten-Schreiboperationen
- MCP-Tasks werden parallel zu d-migrate-Jobs fuer denselben
  Langlaeufer verwendet
- KI-nahes Tool ohne Policy-Freigabe
- externer KI-Provider ohne erlaubende Policy
- Prompt mit Secret- oder Rohdatenparameter
- `prompts/get` mit unbekanntem Prompt
- `prompts/get` mit ungueltigen Argumenten
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

- `stdio` und streambares HTTP sind die Transporte
- MCP-`initialize` nutzt datierte `protocolVersion`-Strings
  (`2025-11-25`); d-migrate `v1` ist nur die Tool-/Resource-
  Vertragsversion
- Streamable HTTP dokumentiert Accept-Header,
  `MCP-Protocol-Version`, Origin-Validierung, optionale
  `MCP-Session-Id`, `GET`-Semantik und `DELETE`-Semantik fuer
  Session-Terminierung oder HTTP 405
- HTTP-Authorization dokumentiert 401/403-Verhalten,
  `WWW-Authenticate`, Protected Resource Metadata und Scope-
  Challenges fuer nicht-lokale HTTP-Clients; das Metadata-Dokument nennt
  mindestens `resource` und `authorization_servers`; Auth-Deaktivierung
  ist nur fuer lokale Tests/Demos erlaubt
- `stdio` ohne validierte Host-Attestation und ohne
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
- produktnahe Upload- und Artefaktpfade nutzen File-Spooling bzw.
  Byte-Stores fuer Segmente und Inhalte; In-Memory ist nur fuer Unit-Tests
- Quotas, Rate Limits und Timeouts sind dokumentiert und mappen auf
  `RATE_LIMITED` bzw. `OPERATION_TIMEOUT`
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

### 11.2 Zu viel Infrastruktur im Milestone

Risiko:

- Job-, Artefakt-, Policy- und Upload-Kern koennen den Milestone
  ueberladen.

Gegenmassnahme:

- Store bewusst einfach halten
- keine Remote-Mandantenverwaltung implementieren
- klare Store-Interfaces fuer spaetere Persistenz definieren
- Byte-Stores trotzdem explizit machen: File-Spooling fuer Segmente und
  Artefaktinhalte ist verpflichtend, damit 200-MiB-Uploads, Chunk-Reads und
  Cleanup nicht als RAM-only-Sonderfall enden

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
- die Lieferabschnitte 0.9.6-A, 0.9.6-B und 0.9.6-C abgeschlossen sind;
  kein einzelner Abschnitt gilt allein als abgeschlossener Milestone
- lokale und entfernte MCP-Clients den Server ueber `stdio` bzw. HTTP
  initialisieren koennen
- Initialize MCP `protocolVersion` `2025-11-25` nutzt und d-migrate
  `v1` nur als Tool-/Resource-Vertragsversion ausweist
- streambares HTTP Accept-Header, `MCP-Protocol-Version`,
  Origin-Validierung, optionales `MCP-Session-Id`, `GET`-Semantik und
  `DELETE`-Semantik gemaess MCP-Spezifikation abdeckt
- nicht-lokales streambares HTTP Auth nach MCP 2025-11-25 erzwingt und
  Auth-Deaktivierung nur fuer lokale Tests/Demos auf Loopback-Bindung
  ohne Public Base URL zulaesst
- alle in Scope genannten Tools registriert sind
- read-only Tools gegen bestehende Anwendungskomponenten laufen
- Discovery-Tools Jobs, Artefakte, Schemas, Profile und Diffs paginiert
  liefern
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
- CLI und MCP fuer Connection-Refs dieselbe adapterneutrale Config-/
  Connection-Aufloesung nutzen; der MCP-Adapter haengt nicht vom CLI-
  Adapter ab
- Start-Tools Idempotency-Key, Payload-Fingerprint und Konflikte korrekt
  behandeln
- Idempotency-Reservierung atomar ist und Konflikte ohne Policy-Pruefung
  als `IDEMPOTENCY_CONFLICT` enden
- `resources/read` grosse Ressourcen nicht ueber schemafremde Parameter
  paginiert, sondern ueber Resource-Templates/Chunk-URIs oder
  `artifact_chunk_get` begrenzt bereitstellt
- Import und Transfer dieselben Idempotenzregeln wie andere Start-Tools
  erzwingen
- `schema_compare` synchron nur `schemaRef` akzeptiert und
  connection-backed Vergleiche ueber `schema_compare_start` als
  idempotente, abbrechbare Jobs laufen
- policy-pflichtige Upload-Init-Pfade, administrative Upload-Abbrueche und
  Datenoperationen den Approval-Flow erzwingen
- read-only Schema-Staging grosse Schemas ohne Write-Policy, aber mit
  `dmigrate:read`, Quota, Audit und read-only `schemaRef`-Markierung
  ermoeglicht
- Artefakt-Uploads mit `artifact_upload_init` beginnen, danach
  segmentiert, resumable und SHA-256-validiert sind
- Upload-Session-IDs opak, kryptografisch erzeugt oder streng
  validiert und an Tenant, Principal, Approval-Fingerprint,
  Session-Metadaten und session-scoped Berechtigungen gebunden sind
- grosse neutrale Schemas ueber `artifactKind=schema` Uploads zu
  `schemaRef` materialisiert werden koennen
- read-only gestagte Schemas nicht implizit als Import-/Transfer-/KI-
  Eingaben wiederverwendet werden koennen
- `job_cancel` eigene oder erlaubte Jobs kontrolliert abbrechen kann
- Cancel in laufende Worker propagiert wird und nach angenommenem
  Cancel keine neuen Artefakte oder Daten-Schreibabschnitte gestartet
  werden
- der Cancel-Spike als Phase-Gate vor der `job_cancel`-Umsetzung
  abgeschlossen ist und die notwendigen kooperativen Checkpoints
  dokumentiert sind
- reduzierte `cancel_requested`-Semantik ohne Worker-Propagation und
  Side-Effect-Stopp erfuellt die 0.9.6-DoD nicht
- 0.9.6 bewusst keine parallele MCP-Tasks-Abstraktion fuer
  d-migrate-Langlaeufer einfuehrt
- KI-nahe Spezialtools und MCP-Prompts aus `docs/ki-mcp.md`
  registriert, policy-gesteuert und auditierbar sind
- KI-nahe Tools mindestens ueber einen adapterneutralen
  `AiProviderPort` und `NoOp`-/lokalen Provider lauffaehig sind; externe
  Provider brauchen Konfiguration, Secret-Scrubbing und erlaubende Policy
- `POLICY_REQUIRED` nie ein self-approvendes `approvalToken` ausstellt,
  sondern nur `approvalRequestId`/Challenge-Daten
- Fehler immer als strukturiertes Envelope erscheinen
- Tenant-Scope- und Principal-Pruefungen in Tool- und Resource-Pfaden
  aktiv sind
- Audit-Ereignisse fuer alle Tool-Aufrufe geschrieben werden, auch bei
  fruehen Auth-, Validation-, Scope-, Policy- und Idempotency-Fehlern
- MCP-Integrationstests mindestens Initialize, Tool-Aufruf,
  Resource-Zugriff, Fehlerfall und Upload abdecken
- Dokumentation die konkreten Startpfade und die Sicherheitsgrenzen
  beschreibt

---

## 13. Folgearbeiten nach 0.9.6

Direkte Anschlussarbeiten laut Roadmap:

- 0.9.7 REST-API auf demselben Job-/Artefakt-/Policy-Kern aufbauen
- 0.9.8 gRPC-API auf denselben Kernvertraegen aufbauen
- 0.9.9 Dokumentation, API-Dokumentation und Pilot-Validierung
