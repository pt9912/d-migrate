# Implementierungsplan: 0.9.6 - Phase A `Gemeinsamer Serverkern`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: A (`Gemeinsamer Serverkern`)
> **Status**: Draft (2026-04-25)
> **Referenz**: `docs/implementation-plan-0.9.6.md` Abschnitt 1 bis 5,
> Abschnitt 8 Phase A, Abschnitt 9.1, Abschnitt 11 und Abschnitt 12;
> `docs/ki-mcp.md`; `docs/job-contract.md`; `docs/architecture.md`;
> `docs/design.md`; `docs/rest-service.md`; `docs/grpc-service.md`;
> `hexagon/application`; `hexagon/ports-read`; `hexagon/ports-write`;
> `hexagon/profiling`; `adapters/driving/cli`.

---

## 1. Ziel

Phase A schafft den adapterneutralen Serverkern fuer den MCP-Milestone
0.9.6. Sie implementiert noch keinen vollstaendigen MCP-Transport und keine
fertigen MCP-Tools, sondern die gemeinsamen Modelle, Stores und Services, auf
denen MCP, spaeter REST und spaeter gRPC denselben Job-, Artefakt-, Upload-,
Fehler-, Audit- und Idempotenzvertrag nutzen koennen.

Phase A liefert konkrete technische Ergebnisse:

- adapterneutrale Kernmodelle fuer Principal, Resource-URI, Upload,
  Fehler, Pagination und Execution-Meta
- Wiederverwendung des normativen Job- und Artefaktvertrags aus
  `docs/job-contract.md`
- Store-Interfaces fuer Jobs, Artefakte, Artefaktbytes, Upload-Sessions,
  Uploadsegmente, Connection-Refs, Idempotency, Approval-Grants, Quotas und
  Audit
- produktnaher Byte-Store-Vertrag fuer Uploadsegmente und Artefaktinhalte
  mit File-Spooling statt RAM-only-Verhalten
- Quota-/Rate-Limit- und Timeout-Grundlagen fuer Tenant-/Principal-Grenzen
- deterministische Payload-Fingerprints fuer Idempotency und Approval
- Fingerprint-Kanonik fuer transiente Kontrollfelder und Upload-
  Session-Metadaten
- Error-Mapping fuer Anwendungsausnahmen und Validierungsfehler
- In-Memory-Implementierungen fuer Unit-Tests

Nach Phase A soll gelten:

- der MCP-Adapter kann gegen einen klaren Anwendungskern implementiert
  werden, ohne eigene Kernvertraege zu erfinden
- REST/gRPC koennen denselben Kern spaeter wiederverwenden
- Upload-, Resource- und Idempotency-Verhalten ist testbar, bevor
  Transport- oder Tool-Handler hinzukommen
- Security-relevante Grundmodelle wie `PrincipalContext` und Tenant-Scope
  sind nicht ad hoc im MCP-Adapter versteckt

Wichtig: Dieses Dokument beschreibt **Phase A aus Abschnitt 8
`Umsetzungsschritte`**. Es ist nicht identisch mit dem Lieferabschnitt
`0.9.6-A - Read-only MCP Core` aus Abschnitt 3.3 des Masterplans. Der
Lieferabschnitt 0.9.6-A umfasst zusaetzlich Transport-Grundpfade,
read-only Tools, Discovery und read-only Schema-Staging, die in spaeteren
Umsetzungsphasen liegen.

---

## 2. Ausgangslage

### 2.1 Hexagonale Basis ist vorhanden

`d-migrate` trennt Fachlogik und Adapter bereits:

- `hexagon/application` enthaelt anwendungsnahe Runner und
  Orchestrierungen
- `hexagon/ports-read` und `hexagon/ports-write` definieren Datenbank-,
  DDL- und Datenstrom-Ports
- `hexagon/profiling` enthaelt Profiling-Services
- `adapters/driving/cli` ist der bisherige primaere Driving-Adapter

Der MCP-Adapter darf diese Fachlogik nicht duplizieren. Phase A muss daher
die gemeinsamen Vertraege so vorbereiten, dass `adapters:driving:mcp` spaeter
nur noch Protokollmapping und Handler-Orchestrierung verantwortet.

### 2.2 MCP-Zielbild ist dokumentiert

`docs/ki-mcp.md` beschreibt bereits:

- Tool-Gruppen fuer read-only, kontrollierte Write-Operationen und
  KI-nahe Spezialpfade
- Ressourcen fuer Jobs, Artefakte, Schemas, Profile, Diffs und
  Connection-Refs
- strukturierte Fehlercodes
- Idempotency-Key-, Approval-Token- und Upload-Verhalten
- Auth-, Policy- und Audit-Grundsaetze

Phase A zieht daraus die adapterneutralen Bausteine heraus. Alles, was nicht
MCP-spezifisch ist, darf nicht in `adapters/driving/mcp` landen.

### 2.3 Job- und Artefaktvertrag ist normativ

`docs/job-contract.md` ist fuer Jobs und Artefakte verbindlich. Phase A darf
keinen zweiten MCP-spezifischen Job- oder Artefaktvertrag einfuehren.

Erlaubt sind nur:

- adapterneutrale Ergaenzungen am gemeinsamen Kernvertrag, falls wirklich
  erforderlich
- MCP-spezifische Projektionen spaeter im Adapter, z. B. `resourceUri`,
  gekuerzte Summaries oder `executionMeta`

---

## 3. Scope fuer Phase A

### 3.1 In Scope

- Kernmodelle:
  - `PrincipalContext`
  - `ServerResourceUri`
  - Upload-Session- und Upload-Segment-Modelle
  - Fehler-Envelopes und Fehlercodes
  - Pagination-Modelle
  - `ExecutionMeta`
  - Approval-Grant-Modell
- Store-Interfaces:
  - `JobStore`
  - `ArtifactStore`
  - `SchemaStore`
  - `ProfileStore`
  - `DiffStore`
  - `ArtifactContentStore`
  - `UploadSessionStore`
  - `UploadSegmentStore`
  - `ConnectionReferenceStore`
  - `IdempotencyStore`
  - `SyncEffectIdempotencyStore` oder explizite Wiederverwendung des
    `IdempotencyStore` fuer synchrone Nebenwirkungen mit `approvalKey`
  - `ApprovalGrantStore`
  - Quota-/Rate-Limit-Store oder aequivalente Zaehler
  - `AuditSink`
- File-Spooling-Vertrag fuer produktnahe Upload-/Artefaktbytes
- In-Memory-Implementierungen fuer Unit-Tests
- deterministische Payload-Fingerprint-Funktion
- Fingerprint-Kanonik:
  - transiente Kontrollfelder ausschliessen
  - Upload-Segmente separat von Upload-Session-Metadaten behandeln
  - Approval- und Idempotency-Fingerprints stabil halten
- Quota-/Rate-Limit-Service fuer Tenant-/Principal-Grenzen
- Timeout-Konfiguration und Timeout-Fehlermapping
- Error-Mapper fuer Anwendungsausnahmen und Validierungsfehler
- Audit-Event-Kernvertrag inklusive Around-/Finally-Scope, Outcome,
  Secret-Scrubbing und fruehen Fehlerpfaden
- Unit-Tests fuer die Kernservices und Stores

### 3.2 Bewusst nicht Teil von Phase A

- Gradle-Modul `adapters:driving:mcp`
- MCP-Server-Bootstrap fuer `stdio` oder HTTP
- Initialize-/Capability-Response
- Tool-Registry und Resource-Registry im MCP-Adapter
- konkrete MCP-Tool-Handler
- HTTP-Authorization, JWKS, Introspection oder Scope-Challenges
- Policy-Grant-Aussteller fuer produktive Freigaben
- Start-Tools wie `schema_reverse_start`, `schema_compare_start` oder
  `data_profile_start`
- Datenoperationen wie `data_import_start` oder `data_transfer_start`
- `job_cancel`
- KI-nahe Tools und MCP-Prompts
- Dokumentationsnachzug fuer konkrete Startbefehle

Praezisierung:

Phase A loest "welcher gemeinsame Kernvertrag existiert?", nicht "wie wird
dieser Kern bereits ueber MCP bedient?".

---

## 4. Leitentscheidungen fuer Phase A

### 4.1 Kernmodelle bleiben adapterneutral

Verbindliche Folge:

- gemeinsame Modelle duerfen keine MCP-JSON-RPC-Typen referenzieren
- Fehler-, Pagination-, Execution- und Resource-URI-Modelle muessen auch
  fuer REST/gRPC wiederverwendbar bleiben
- MCP-spezifische Felder gehoeren spaeter in Adapterprojektionen, nicht in
  den Kernvertrag

Begruendung:

0.9.7 und 0.9.8 planen REST und gRPC. Ein MCP-spezifischer Kern wuerde den
spaeteren Vertragsabgleich unnoetig verteuern.

### 4.2 `PrincipalContext` modelliert Tenant-Grenzen explizit

Phase A definiert `PrincipalContext` mindestens mit:

- `principalId`
- Home-`tenantId`
- `effectiveTenantId`
- `allowedTenantIds`
- Rollen oder Scopes
- `isAdmin`
- `auditSubject`
- `authSource`
- `expiresAt`

Verbindliche Folge:

- `effectiveTenantId` muss entweder dem Home-`tenantId` entsprechen oder in
  `allowedTenantIds` enthalten sein
- andernfalls liefert Tenant-Aufloesung `TENANT_SCOPE_DENIED`
- `isAdmin` hebt Tenant-Grenzen nicht automatisch auf
- Tool- und Resource-Pfade duerfen Tenant-Entscheidungen nicht ad hoc aus
  Request-Payloads ableiten

### 4.3 Job- und Artefaktmodell kommt aus `docs/job-contract.md`

Phase A verwendet fuer Jobs und Artefakte den bestehenden gemeinsamen
Vertrag.

Verbindliche Folge:

- Jobstatus bleibt auf `queued`, `running`, `succeeded`, `failed`,
  `cancelled` begrenzt
- Ablauf, Retention und Sichtbarkeit werden ueber Felder wie `expiresAt`
  modelliert, nicht ueber zusaetzliche Lifecycle-Status
- Artefakte nutzen den gemeinsamen Artefaktvertrag, nicht MCP-spezifische
  Feldnamen

### 4.4 Byte-Stores sind Pflicht, RAM-only ist nur Teststrategie

Phase A definiert:

- `UploadSegmentStore` fuer unfertige Uploadsegmente
- `ArtifactContentStore` fuer finalisierte immutable Artefaktbytes
- atomare Segmentwrites
- Range-/Chunk-Reads
- TTL-/Abort-/Expiry-Cleanup
- Quota-Anbindung fuer reservierte und gespeicherte Bytes

Verbindliche Folge:

- 200-MiB-Uploads duerfen nicht in einer einzelnen Heap-Struktur gehalten
  werden muessen
- In-Memory-Stores sind nur fuer Unit-Tests zulaessig
- Phase A liefert deshalb neben In-Memory-Testdoubles auch eine file-backed
  `UploadSegmentStore`-/`ArtifactContentStore`-Implementierung oder ein
  gleichwertiges produktnahes Spooling-Modul mit Contract-Tests
- spaetere Tool-Responses und `resources/read` duerfen grosse Inhalte nur
  referenzieren oder chunkweise lesen

### 4.5 Fingerprints sind stabil und ohne transiente Kontrollfelder

Phase A definiert eine deterministische Fingerprint-Funktion ueber
normalisierten Payload.

Ausgeschlossen werden mindestens:

- `idempotencyKey`
- `approvalToken`
- `clientRequestId`
- `requestId`

Die Exclusion gilt nur fuer den expliziten top-level Tool-Control-Envelope
des normalisierten Requests. Gleichnamige fachliche Felder innerhalb von
Nutzdatenobjekten, Arrays oder eingebetteten Artefaktmetadaten werden nicht
rekursiv entfernt. Der Fingerprint-Service muss Normalisierung fuer
Objektfeldreihenfolge, Arrays, Zahlen, Strings, Booleans, `null` und
Defaultwerte eindeutig definieren und testen.

Upload-Segmente werden nicht mit Upload-Session-Metadaten vermischt:

- Approval-/Init-Fingerprints binden Session-Metadaten wie `artifactKind`,
  `mimeType`, `sizeBytes`, `checksumSha256`, `uploadIntent`, Tenant und
  Principal
- Segmentbytes, `segmentSha256`, `contentBase64`, `segmentIndex` und
  `segmentOffset` werden separat validiert

### 4.6 Approval-Grants koennen Start- und Sync-Tools modellieren

Phase A definiert `ApprovalGrant` mit:

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
- `grantSource`
- `expiresAt`

Verbindliche Folge:

- Start-Tools korrelieren Grants ueber `idempotencyKey`
- synchrone policy-pflichtige Side-Effect-Tools korrelieren Grants ueber
  `approvalKey`
- rohe Approval-Tokens werden nie persistiert oder auditiert
- ein Grant mit nur `approvalKey` ohne `correlationKind` ist ungueltig

Phase A implementiert den adapterneutralen Approval-Grant-Service fuer
Validierung und Redeem-Entscheidungen. Produktive Grant-Aussteller folgen in
spaeteren Phasen; der Kernservice muss aber bereits Grants gegen Tenant,
Caller, Tool, `correlationKind`, `correlationKey`, Payload-Fingerprint,
Scopes und Ablaufzeit pruefen koennen.

### 4.7 Quotas, Rate Limits und Timeouts sind Kernverhalten

Phase A definiert zentrale Dienste fuer:

- aktive Jobs je Tenant/Principal und Operation
- aktive Upload-Sessions
- reservierte und gespeicherte Upload-/Artefaktbytes
- parallele Segmentwrites
- Provider-/Tool-Aufrufe pro Zeitfenster
- Timeout-Konfiguration fuer lange Operationen

Verbindliche Fehler:

- Quota- oder Rate-Limit-Verletzung liefert `RATE_LIMITED`
- Timeout liefert `OPERATION_TIMEOUT`
- Details duerfen keine fremden Ressourcendaten leaken

### 4.8 Fehler-Mapping ist fachlich, nicht transportgebunden

Phase A definiert fachliche Fehlercodes und Anwendungsausnahmen so, dass sie
spaeter von MCP, REST und gRPC unterschiedlich auf Wire-Formate gemappt werden
koennen.

Verbindliche Folge:

- Phase A erzeugt noch keine MCP-JSON-RPC-Fehler
- sie definiert aber die d-migrate-Codes, Details und Diagnosefelder
- Adapter entscheiden spaeter, ob ein Fehler als Tool-Result, JSON-RPC-Error,
  HTTP-Status oder gRPC-Status sichtbar wird

---

## 5. Zielarchitektur

### 5.1 Modul- und Paketgrenzen

Die Paketgrenze ist Teil des Phase-A-Vertrags:

- gemeinsame Modelle und Store-/Service-Ports gehoeren in das bestehende
  Modul `hexagon:ports-common`
- eine Alternative zu `hexagon:ports-common` ist nur mit expliziter
  Begruendung im Code-Review zulaessig und muss dieselbe
  Abhaengigkeitsrichtung erzwingen: Core/Ports nach innen,
  Driving-/Driven-Adapter nach aussen
- `hexagon:ports-common` darf nicht von `hexagon:profiling` abhaengen.
  `ProfileStore` ist dort deshalb nur ein tenant-scoped Metadaten-/
  Artefaktindex mit Profile-ID, Resource-URI, Job-/Artifact-Refs,
  Retention, Pagination und Suchfeldern. Profilingspezifische Projektionen
  wie `DatabaseProfile` bleiben in `hexagon:profiling` oder werden in einem
  separaten, bewusst begruendeten Inner-Hexagon-Refactoring verschoben.
- `hexagon/application` enthaelt die adapterneutralen Services fuer Upload,
  Idempotency, Approval-Grant-Validierung, Quotas, Fehler und Audit, kennt
  aber keine MCP-, HTTP-, JSON-RPC-, REST- oder gRPC-Wire-Typen
- file-backed Byte-Store-Implementierungen liegen in einem driven Adapter
  oder einem dedizierten Infrastrukturmodul, nicht in
  `adapters/driving/mcp`
- Test-In-Memory-Implementierungen liegen in Testquellen oder dedizierten
  Test-Hilfsmodulen
- `adapters/driving/mcp` darf in Phase A noch nicht entstehen und darf
  spaeter nur gegen diese Ports und Services linken

### 5.2 Erwartete Kernmodelle

Mindestens zu definieren oder zu konsolidieren:

- `PrincipalContext`
- `ServerResourceUri`
- `UploadSession`
- `UploadSegment`
- `ManagedJob` gemaess `docs/job-contract.md`
- `ManagedArtifact` gemaess `docs/job-contract.md`
- interne `JobRecord`-/`ArtifactRecord`-Storage-Envelopes fuer Tenant- und
  Owner-Scope
- `ConnectionReference`
- `ApprovalGrant`
- `ToolErrorEnvelope` bzw. adapterneutraler Fehlerkern
- `PageRequest` / `PageResult`
- `ExecutionMeta`

`ManagedJob` und `ManagedArtifact` bleiben die oeffentlichen,
protokollneutralen Modelle aus `docs/job-contract.md`. Store-Records muessen
zusaetzlich ein internes, nicht zwingend nach aussen projiziertes Security-
Envelope tragen:

- `tenantId`
- `ownerPrincipalId`
- `createdBy` aus dem oeffentlichen Vertrag
- `visibility` (`owner`, `tenant`, `admin`)
- optional `adminScope` oder erlaubte Admin-/Service-Principals
- `resourceUri`
- Retention-/Expiry-Metadaten, sofern nicht bereits im oeffentlichen Modell
  enthalten

Stores und Resource-Resolver duerfen Tenant-/Owner-Pruefungen nie allein aus
`createdBy` oder aus der Resource-URI ableiten. Die Resource-URI ist
adressierend; das Storage-Envelope ist autorisierend.

`UploadSession` hat eine explizite Zustandsmaschine:

- erlaubte States: `ACTIVE`, `COMPLETED`, `ABORTED`, `EXPIRED`
- erlaubte Uebergaenge:
  - `ACTIVE -> COMPLETED` nur nach erfolgreicher Finalisierung,
    Gesamt-Hash-Pruefung und Artefaktmaterialisierung
  - `ACTIVE -> ABORTED` durch Owner-/Admin-Abbruch
  - `ACTIVE -> EXPIRED` bei Idle-Timeout oder abgelaufener absoluter Lease
  - terminale States bleiben terminal
- Resume ist nur im State `ACTIVE` erlaubt und muss erwarteten
  `segmentIndex`, `segmentOffset`, `segmentTotal`, bisherige Segmenthashes
  und Lease-Informationen konsistent liefern
- Finalize-Invarianten:
  - alle Segmente sind lueckenlos vorhanden
  - Segment-Hashes stimmen
  - Gesamt-`checksumSha256` stimmt
  - finalisiertes Artefakt ist immutable
  - nach `COMPLETED`, `ABORTED` oder `EXPIRED` werden keine neuen Segmente
    akzeptiert

`ConnectionReference` enthaelt mindestens:

- `connectionId`
- `tenantId`
- non-secret Anzeigename
- Dialekt
- Sensitivitaets-/Produktionsklassifizierung fuer Policy
- `credentialRef` oder `providerRef` auf serverseitig verwaltete Secrets
- optional erlaubte Principal-/Scope-Metadaten
- `resourceUri`

Secrets selbst sind nie Teil von `ConnectionReference`.

### 5.3 Erwartete Store-Interfaces

Mindestens zu definieren:

- `JobStore`
- `ArtifactStore`
- `SchemaStore`
- `ProfileStore`
- `DiffStore`
- `ArtifactContentStore`
- `UploadSessionStore`
- `UploadSegmentStore`
- `ConnectionReferenceStore`
- `IdempotencyStore`
- `SyncEffectIdempotencyStore` oder explizite Wiederverwendung des
  `IdempotencyStore` fuer synchrone Nebenwirkungen mit `approvalKey`
- `ApprovalGrantStore`
- `QuotaStore` oder aequivalente Quota-Zaehler
- `AuditSink`

Die Interfaces muessen atomare Operationen ausdruecken koennen, insbesondere:

- Segmentwrite nur einmal fuer dieselbe Session/Position
- Idempotency-Reservierung ohne Race zwischen parallelen Requests,
  inklusive `PENDING`, `AWAITING_APPROVAL`, `COMMITTED`, `DENIED`,
  Lease-/Recovery-/TTL- und Konfliktpfaden
- Sync-Effect-Reservierung fuer `approvalKey`, damit synchrone
  Nebenwirkungen wie Upload-Init oder KI-Provider-Aufrufe retry-sicher sind
- Read-only-Init-Resume fuer `uploadIntent=schema_staging_readonly` ueber
  `clientRequestId`:
  - Store-Key ist (`tenantId`, `callerId`, `toolName`, `clientRequestId`)
  - identische Init-Metadaten liefern dieselbe `uploadSessionId`
  - abweichende Init-Metadaten liefern `IDEMPOTENCY_CONFLICT`
  - Reservierung traegt `initResumeExpiresAt` und wird spaetestens mit der
    Upload-Session-Lease entfernt
  - ohne `clientRequestId` ist ein erfolgreicher read-only Init bewusst
    nicht resumable
- Upload-Session-Statuswechsel fuer Abort/Expiry/Complete
- Byte-Cleanup nach TTL, Abort oder Expiry
- Connection-Reference-Lookups mit Tenant-/Principal-Filterung und
  non-secret Policy-Metadaten
- Quota-Accounting mit `reserve`, `commit`, `release` und `refund`

`SchemaStore`, `ProfileStore` und `DiffStore` duerfen intern auf typisierte
Artefakte zeigen. Trotzdem brauchen sie explizite Store-/Index-Abstraktionen,
damit `schema_list`, Resource-Resolver, Tenant-Scope, Pagination und Retention
nicht von artefaktspezifischen Details abhaengen.

Fuer `ProfileStore` gilt wegen der bestehenden Modulgrenze zusaetzlich:
Der Store-Port in `hexagon:ports-common` darf keine Typen aus
`hexagon:profiling` referenzieren. Er indexiert nur Profile-Metadaten und
Artefakt-/Job-Referenzen. Typed Reads oder Projektionen auf
`DatabaseProfile` bleiben Aufgabe von `hexagon:profiling` bzw. eines
spaeter explizit festgelegten inneren Profiling-Ports.

`ConnectionReferenceStore` ist ein non-secret Index. Bootstrap-Quellen sind
Projekt-/Serverkonfiguration, Seeds oder ein spaeterer Config-Provider. Der
Store liefert keine JDBC-URL mit Secrets, keine Passwoerter und keine Tokens,
sondern nur Metadaten und `credentialRef`/`providerRef` fuer autorisierte
Runner-/Driver-Pfade.

### 5.4 Test-Implementierungen

Phase A liefert In-Memory-Implementierungen nur fuer Tests.

Diese Implementierungen muessen trotzdem:

- atomare Konfliktfaelle simulieren
- TTL/Expiry pruefbar machen
- Quota-Verletzungen deterministisch ausloesen koennen
- keine Semantik verstecken, die produktive Stores spaeter nicht erfuellen
  muessen

File-backed Byte-Stores sind keine Testdoubles. Phase A muss fuer sie dieselben
Contract-Tests wie fuer In-Memory-Stores ausfuehren und zusaetzlich
Dateisystem-Cleanup, atomare Writes und Range-Reads gegen echte Dateien
abdecken.

---

## 6. Arbeitspakete

### 6.1 Kernmodelle anlegen oder konsolidieren

Aufgaben:

- Paket-/Modulort fuer adapterneutrale Modelle festlegen
- `PrincipalContext` mit Tenant-Set-Semantik definieren
- Resource-URI-Modell fuer `dmigrate://tenants/{tenantId}/...` definieren
- Pagination- und Execution-Meta-Modelle definieren
- Fehlerkern mit d-migrate-Codes und strukturierten Details definieren
- Job-/Artefaktfelder gegen `docs/job-contract.md` abgleichen
- `UploadSession`-State-Machine mit `ACTIVE`, `COMPLETED`, `ABORTED`,
  `EXPIRED`, erlaubten Uebergaengen, Resume-Regeln und Finalize-
  Invarianten definieren
- interne `JobRecord`-/`ArtifactRecord`-Envelopes mit `tenantId`,
  `ownerPrincipalId`, `visibility`, optionalem `adminScope`, `resourceUri`
  und Retention-/Expiry-Metadaten definieren
- `ConnectionReference` als non-secret Modell mit Policy-Metadaten,
  `credentialRef`/`providerRef` und Tenant-/Principal-Filterdaten
  definieren

Abnahme:

- Modelle enthalten keine MCP-spezifischen Wire-Typen
- Tenant-Scope kann ohne Request-Payload-Vertrauen entschieden werden
- Job-/Artefaktprojektionen weichen nicht vom gemeinsamen Vertrag ab
- Store-Records fuer Jobs und Artefakte enthalten Tenant-/Owner-
  Sicherheitsmetadaten und Resource-Resolver pruefen gegen diese Records,
  nicht nur gegen `createdBy` oder URI-Bestandteile
- Upload-Session-Transitions sind vollstaendig und terminale States nehmen
  keine neuen Segmente an
- Connection-Refs enthalten keine Secrets und sind fuer Policy-Entscheidungen
  ausreichend klassifiziert

### 6.2 Store-Interfaces definieren

Aufgaben:

- Store-Interfaces fuer Jobs, Artefakte, Schemas, Profile, Diffs, Uploads,
  Connection-Refs, Idempotency, Sync-Effect-Idempotency, Approval-Grants,
  Quotas und Audit definieren
- `ProfileStore` als reinen Metadaten-/Artefaktindex ohne
  `hexagon:profiling`-Typabhaengigkeit definieren
- atomare Methoden fuer Idempotency, Sync-Effect-Idempotency und
  Segmentwrites ausdruecken
- Idempotency-State-Machine verbindlich modellieren:
  - `PENDING` mit `pendingLeaseExpiresAt` und Recovery fuer denselben
    Scope/Fingerprint
  - `AWAITING_APPROVAL` mit `awaitingApprovalExpiresAt`
  - `COMMITTED` als deduplizierbarer Erfolgseintrag
  - `DENIED` mit `POLICY_DENIED` bis zum Ablauf
  - Konfliktpfad fuer gleicher Scope-Key mit anderem Payload-Fingerprint
- genehmigter Retry aus `AWAITING_APPROVAL` muss die Reservierung atomar
  claimen, genau eine Job-Erzeugung ausloesen und danach `COMMITTED`
  deduplizieren
- read-only `artifact_upload_init(uploadIntent=schema_staging_readonly)`
  ueber `clientRequestId` als eigenen Init-Resume-Pfad modellieren,
  inklusive Store-Key, TTL, Konfliktfall und nicht-resumable Verhalten ohne
  `clientRequestId`
- TTL-/Cleanup-Verhalten in Interfaces oder Service-Vertraege aufnehmen
- Fehlerfaelle fuer unbekannt, abgelaufen, fremd, konfliktbehaftet und
  quota-limitiert modellieren

Abnahme:

- Interfaces reichen fuer spaetere MCP-Tool-Handler aus
- In-Memory-Tests koennen Race- und Konfliktfaelle nachstellen
- Byte-Stores koennen Range-/Chunk-Reads ausdruecken
- `schema_list`, `profile_list`, `diff_list` und Resource-Resolver koennen
  ueber explizite Store-/Index-Abstraktionen implementiert werden
- `ConnectionReferenceStore` liefert tenant-/principal-gefilterte,
  non-secret Connection-Metadaten mit Sensitivitaets-/Produktions-
  klassifizierung und `credentialRef`/`providerRef`
- parallele identische Starts erzeugen maximal eine `PENDING`-Reservierung
  und genehmigte parallele Retries maximal einen `COMMITTED` Job

### 6.3 Upload-Byte-Store-Vertrag implementieren

Aufgaben:

- `UploadSegmentStore` fuer unfertige Segmente implementieren
- `ArtifactContentStore` fuer finalisierte Artefakte implementieren
- file-backed Implementierungen fuer produktnahe Pfade bereitstellen
  (Konstruktor-Parameter `Path root`; `ServerCoreLimits`-getriebene
  Konfiguration erst in spaeteren Phasen)
- In-Memory-Implementierung nur fuer Unit-Tests bereitstellen
- Hash- und Range-Read-Hilfen definieren
- Filesystem-Layout verbindlich festlegen:
  - Segmente unter `<root>/segments/<sessionId>/<segmentIndex>.bin`
  - Artefakte unter `<root>/artifacts/<sha256(artifactId)[0:2]>/<artifactId>.bin`
    (Shard wird aus dem `artifactId` abgeleitet, nicht aus dem Inhalt; der
    Lookup ist damit O(1) ohne Verzeichnisscan)
  - Sidecar-Datei `<file>.meta.json` mit `sha256` und `sizeBytes` neben
    jeder finalisierten Datei; `existingSegmentSha256` /
    `existingSha256` werden aus dem Sidecar gelesen, nicht durch Re-Hash
    der Zieldatei
- atomare Schreibstrategie verbindlich festlegen:
  - Schreiben in `<finalpath>.tmp.<uuid>` im selben Verzeichnis
  - `sha256` streamend ueber `DigestInputStream` berechnen
  - `Files.move(... ATOMIC_MOVE)` ohne `REPLACE_EXISTING` als
    Sichtbarkeitsschritt; existierendes Ziel mappt deterministisch auf
    `AlreadyStored`/`AlreadyExists` (gleicher Hash) bzw. `Conflict`
    (anderer Hash); abgebrochene Writes hinterlassen kein sichtbares Ziel
- Path-Traversal-Schutz: `sessionId` und `artifactId` werden vor jedem
  Pfadbau gegen ein striktes Whitelist-Pattern (`[A-Za-z0-9_-]{1,128}`)
  validiert; `segmentIndex` muss nicht negativ sein
- Range-Read-Semantik (gilt fuer In-Memory und file-backed identisch):
  - `length == 0` liefert leeren Stream
  - `offset < 0`, `length < 0`, `offset > size` oder
    `offset + length > size` werfen `IllegalArgumentException`
- Cleanup-Vertrag:
  - TTL-/Abort-/Expiry-Cleanup wird durch den UploadSession-Service
    getrieben, der je abgelaufener Session `deleteAllForSession(sessionId)`
    aufruft; keine zusaetzliche Methode am Port-Interface
  - Crash-Recovery (`cleanupOrphans(activeSessions: Set<String>)`) ist
    file-backed-spezifisch und liegt direkt am
    `FileBackedUploadSegmentStore`, nicht auf dem Port; raeumt
    liegengebliebene `.tmp.*`-Dateien und verwaiste Session-Verzeichnisse
    beim Serverstart

Abnahme:

- Segmentwrites sind atomar; ein Crash mid-write hinterlaesst kein
  sichtbares Ziel
- abweichender Write auf gleiche Session/Position liefert deterministischen
  Konflikt; identischer Write liefert `AlreadyStored` ohne Datei-Re-Hash
- Range-/Chunk-Reads funktionieren fuer Segmente und finalisierte
  Artefakte; Length-0- und Out-of-Bounds-Faelle sind einheitlich
  spezifiziert und getestet
- TTL-/Abort-/Expiry-Cleanup entfernt unfertige Segmente;
  `cleanupOrphans` raeumt `.tmp.*`-Reste und verwaiste Verzeichnisse
- file-backed Store besteht dieselben Contract-Tests wie In-Memory plus
  Tests fuer echte Dateien, atomare Writes, Filesystem-Cleanup,
  Crash-Recovery und Path-Traversal-Abwehr
- parallele Writes auf gleichem `(sessionId, segmentIndex)` werden
  deterministisch in genau ein `Stored` plus `AlreadyStored`/`Conflict`
  aufgeloest (Contract-Test mit konkurrierenden Threads)

### 6.4 Fingerprint-Service implementieren

Aufgaben:

- deterministische JSON-/Payload-Normalisierung festlegen
- transiente Kontrollfelder nur aus dem top-level Tool-Control-Envelope
  ausschliessen; gleichnamige fachliche Nested Fields bleiben erhalten
- Fingerprint fuer Start-Tools, Sync-Tools und Upload-Init-Metadaten
  unterscheiden
- Normalisierung fuer verschachtelte Objekte, Arrays, Zahlen, Strings,
  Booleans, `null`, fehlende Felder und Defaultwerte festlegen
- SHA-256 hex-codiert als Standardausgabe festlegen
- adapterneutrales JSON-AST `JsonValue` (sealed interface mit `Null`,
  `Bool`, `Num`, `Str`, `Arr`, `Obj`) in `application.fingerprint`
  bereitstellen; spaetere MCP-/HTTP-Adapter konvertieren ihre eigene
  Wire-Repraesentation (Jackson, kotlinx.serialization) in dieses AST.
  Keine JSON-Library-Abhaengigkeit in `hexagon:application`.
- `JsonValue.Num` haelt 64-bit-Integer (`Long`); `Double`/`Float` und
  `BigInteger`/`BigDecimal` werden im Canonicalizer abgewiesen
  (`IllegalArgumentException`)
- Service-API als adapterneutrales Interface:
  ```kotlin
  interface PayloadFingerprintService {
      fun fingerprint(
          scope: FingerprintScope,
          payload: JsonValue.Obj,
          bind: BindContext,
      ): String
  }

  data class BindContext(
      val tenantId: TenantId,
      val callerId: PrincipalId,
      val toolName: String,
  )
  ```
- `_bind` ist reserved top-level key. Liefert der Caller selbst ein
  `_bind` im `payload`, wird mit `IllegalArgumentException` abgewiesen.
  `callerId` stammt immer aus `PrincipalContext.principalId.value`,
  nicht aus `auditSubject`.
- `FingerprintScope` enthaelt die Werte `START_TOOL`, `SYNC_TOOL` und
  `UPLOAD_INIT`. `UPLOAD_SEGMENT` entfaellt — Segmentbytes werden ueber
  `segmentSha256` direkt gehasht und nie an
  `PayloadFingerprintService.fingerprint(...)` gereicht.

Abnahme:

- gleiche semantische Payloads erzeugen identische Fingerprints
- Feldreihenfolge beeinflusst den Fingerprint nicht
- `approvalToken`, `idempotencyKey`, `clientRequestId` und `requestId`
  veraendern den Fingerprint nicht
- gleichnamige verschachtelte fachliche Felder werden nicht entfernt und
  veraendern den Fingerprint
- Arrays bleiben reihenfolgesensitiv; Objektfeldreihenfolge ist irrelevant
- Zahlen-/String-Unterschiede, `null` und explizite Defaultwerte sind
  deterministisch getestet
- Upload-Segmentdaten veraendern den Session-Metadaten-Fingerprint nicht
- `JsonCanonicalizer` ist gegen mindestens drei publizierte Test-Vektoren
  aus RFC 8785 Appendix B verifiziert; Edge-Cases fuer NFC, Escape-
  Pflicht-Zeichen und Long-Grenzen sind separat getestet
- `payload` mit reserviertem `_bind`-Top-level-Key wird abgewiesen;
  `Double`/`Float` werden abgewiesen
- leeres Objekt `{}` ist erlaubt; Fingerprint wird dann von
  `{"_bind":{...}}` allein berechnet

### 6.5 Approval-Grant-Service und Store-Vertrag vorbereiten

Aufgaben:

- `ApprovalGrant` mit `correlationKind`/`correlationKey` modellieren
- Token-Fingerprint statt Roh-Token speichern
- Scope-, Issuer- und Ablaufmetadaten speichern
- Validierung gegen Tenant, Caller, Tool, Correlation und Payload-
  Fingerprint definieren
- adapterneutralen Approval-Grant-Service implementieren:
  - Grant anhand Token-Fingerprint laden
  - Ablaufzeit, Issuer, Scopes und Correlation pruefen
  - Payload-Fingerprint vergleichen
  - Reuse mit anderem Payload, Tenant, Caller, Tool oder Correlation
    deterministisch ablehnen
- Eingaben in einem `ApprovalAttempt` zusammenfassen, statt einzelne
  Parameter durch die API zu reichen:
  ```kotlin
  data class ApprovalAttempt(
      val tokenFingerprint: String,
      val tenantId: TenantId,
      val callerId: PrincipalId,
      val toolName: String,
      val correlationKind: ApprovalCorrelationKind,
      val correlationKey: String,
      val payloadFingerprint: String,
      val requiredScopes: Set<String>,
  )

  interface ApprovalGrantService {
      fun validate(attempt: ApprovalAttempt, now: Instant): ApprovalGrantValidation
  }
  ```
- `ApprovalGrantValidation` als sealed result mit `Valid(grant)` und
  einer `Invalid`-Hierarchie pro Reject-Grund
  (`Unknown`, `Expired`, `TenantMismatch`, `CallerMismatch`,
  `ToolMismatch`, `CorrelationMismatch`, `PayloadMismatch`,
  `ScopeMismatch(missing)`, optional `IssuerMismatch`)
- Verantwortungsverteilung:
  - `ApprovalGrantValidator`: reine Vergleichslogik, kennt keinen Store
    und kein `now` (nimmt Grant + Attempt + `now`)
  - `ApprovalGrantService`: orchestriert Store-Lookup -> Validator ->
    Result; einziges Tor zur Außenwelt
- `ApprovalTokenFingerprint.compute(rawToken: String): String =
  sha256_hex(rawToken.utf8)` als Helper, damit Adapter den Übergang zum
  gespeicherten Fingerprint deterministisch erzeugen koennen; rohe
  Tokens bleiben am Adapter-Rand
- Issuer-Pruefung: Validator nimmt eine `IssuerCheck`-Sealed
  (`Off | AllowList(Set<String>)`). Composition Root muss explizit
  einen Wert waehlen — kein Default, damit der Security-Default nicht
  unbemerkt auf "Off" steht. `AllowList(emptySet())` ist Deny-All.
- Reuse-Semantik: ein Grant ist innerhalb seiner Ablaufzeit fuer
  identische `ApprovalAttempt` wiederverwendbar (idempotent). Schutz
  vor Doppel-Ausfuehrung liegt in der Idempotency-State-Machine
  (AP 6.2), nicht im Approval-Service. Der Store hat kein
  `markConsumed`, das ist Absicht.
- Validierungsreihenfolge ist deterministisch:
  1. `Unknown` (kein Grant fuer `tokenFingerprint` + Tenant)
  2. `Expired`
  3. `TenantMismatch`
  4. `CallerMismatch`
  5. `ToolMismatch`
  6. `CorrelationMismatch` (Kind oder Key)
  7. `PayloadMismatch`
  8. `ScopeMismatch(missing)`
  9. `IssuerMismatch` (nur wenn Issuer-Check aktiv)

Abnahme:

- Start-Tool-Grants koennen ueber `idempotencyKey` validiert werden
- Sync-Tool-Grants koennen ueber `approvalKey` validiert werden
- Reuse mit anderem Payload wird abgewiesen
- rohe Tokens tauchen nicht in Store oder Audit auf
- abgelaufene Grants werden abgewiesen
- fehlende Scopes werden abgewiesen
- `ScopeMismatch.missing` enthaelt die genaue fehlende Scope-Menge
- Validierungsreihenfolge ist getestet: bei mehreren Fehlern wird der
  in der Reihenfolge erste gemeldet

### 6.6 Quota-, Rate-Limit- und Timeout-Grundlagen

Aufgaben:

- Quota-Schluessel fuer Tenant/Principal/Operation definieren
- aktive Jobs insgesamt und je Operation zaehlbar machen
- aktive Upload-Sessions und reservierte Bytes zaehlbar machen
- parallele Segmentwrites begrenzen
- Provider-/Tool-Aufrufe pro Zeitfenster zaehlbar machen
- Lifecycle-Semantik fuer Quota-Accounting definieren:
  - `reserve` vor Side Effects
  - `commit` nach erfolgreicher Job-/Session-/Segment-/Provider-
    Reservierung
  - `release` bei Job-Terminalstatus, Upload-Abort, Upload-Expiry,
    erfolgreicher Upload-Finalisierung und Provider-Aufrufende
  - `refund` bei fehlgeschlagener Reservierung, fehlgeschlagener
    Finalisierung vor Publish und Idempotency-Recovery ohne neue Side
    Effects
- Timeout-Konfiguration fuer spaetere Handler/Runner vorbereiten
- Fehlerdetails fuer `RATE_LIMITED` und `OPERATION_TIMEOUT` definieren
- `QuotaService`-API als Wrapper um `QuotaStore`:
  ```kotlin
  data class QuotaReservation(val key: QuotaKey, val amount: Long)

  interface QuotaService {
      fun reserve(key: QuotaKey, amount: Long): QuotaOutcome
      fun commit(reservation: QuotaReservation)
      fun release(reservation: QuotaReservation)
      fun refund(reservation: QuotaReservation)
  }
  ```
  `commit` ist in Phase A ein No-op am Counter (der Reserve-Increment
  bleibt stehen), dient aber als Audit-Hook fuer AP 6.8. `release` und
  `refund` dekrementieren den Counter und unterscheiden sich nur im
  Grund (success-path vs error/idempotency-replay) — wichtig fuer das
  spaetere Audit-Mapping.
- `RateLimiter`-Algorithmus Phase A: **Fixed Window**, eine Bucket pro
  `(QuotaKey, Minute)`, Reset beim Minutenwechsel. Genauere
  Sliding-Window-/Token-Bucket-Verfahren koennen spaeter ohne
  API-Bruch ergaenzt werden.
- `ServerCoreLimits` als data class mit nested Subgruppen, damit der
  Konstruktor handhabbar bleibt:
  ```kotlin
  data class ServerCoreLimits(
      val idempotency: IdempotencyLimits = IdempotencyLimits(),
      val approval: ApprovalLimits = ApprovalLimits(),
      val upload: UploadLimits = UploadLimits(),
      val quota: QuotaLimits = QuotaLimits(),
  )
  ```
  Defaults stammen aus §14.2.
- Multi-Reserve-Kompensation: keine `reserveAll`-API in Phase A. Wenn
  ein Handler mehrere Dimensionen reservieren muss (z.B.
  `ACTIVE_JOBS` + `UPLOAD_BYTES`) und die zweite RateLimited liefert,
  ruft er `release` auf den bereits erfolgreichen ersten Reserve. Das
  Compensating-Pattern wird in `QuotaLifecycleTest` als Pflichtfall
  dokumentiert.
- Quota-vs-Idempotency-Verantwortung: `QuotaService` weiss nichts von
  Idempotency. Der Handler ruft `reserve` **nur**, wenn
  `IdempotencyStore.reserve(...)` `Reserved` (nicht `ExistingPending`
  oder `Committed`) liefert. Bei Idempotency-Replay → kein
  `quota.reserve`, sondern direkter `Committed`-Pfad ohne neue Side
  Effects.
- `RateLimited`-Detail-Scrubbing erfolgt am Error-Mapper-Boundary
  (AP 6.7), nicht im Quota-Vertrag. `QuotaOutcome.RateLimited` haelt
  intern `key`/`current`/`limit`; beim Mapping zur Tool-Response
  bleiben nur `dimension`, `current`, `limit` (Werte des eigenen
  Tenants — keine "fremden" Daten); `key.tenantId` und
  `key.principalId` werden verworfen.
- `TimeoutBudget` bleibt in Phase A passiver Wert-Carrier
  (`Duration` pro Operation); aktive Enforcement (z.B.
  `Job.cancel(after: deadline)`) folgt im Handler-Layer in spaeteren
  Phasen.

Abnahme:

- Quota-Verletzungen liefern strukturierte Details ohne fremde Ressourcen
- identische Idempotency-Treffer verbrauchen keine neue Quota
- neue Jobs, Sessions, Segmente und Provider-/Tool-Aufrufe werden vor Side
  Effects quotiert
- aktive Job-Quota, Upload-Session-Quota, Byte-Quota, parallele
  Segmentwrite-Quota und Provider-/Tool-Aufruf-Rate-Limit sind getrennt
  konfigurier- und testbar
- Terminalstatus, Abort, Expiry, fehlgeschlagene Finalisierung und
  Idempotency-Recovery geben reservierte Quota deterministisch frei oder
  erstatten sie
- Multi-Reserve-Compensation ist getestet: bei RateLimited auf der
  zweiten Dimension wird der erste Reserve via `release` zurueckgenommen
- `RateLimiter` rolliert deterministisch beim Minutenwechsel (Test mit
  injizierter Clock)
- `ServerCoreLimits` ist mit den §14.2-Defaults konstruierbar, ohne
  dass Tests jeden einzelnen Wert setzen muessen

### 6.7 Error-Mapper bauen

Aufgaben:

- Anwendungsausnahmen auf d-migrate-Fehlercodes mappen
- Validierungsfehler strukturiert abbilden
- Tenant-/Principal-Fehler trennen
- Resource-not-found, expired, aborted und conflict deterministisch trennen
- komplettes Kern-Code-Vokabular aus `docs/ki-mcp.md` abbilden:
  `AUTH_REQUIRED`, `FORBIDDEN_PRINCIPAL`, `POLICY_REQUIRED`,
  `IDEMPOTENCY_CONFLICT`, `IDEMPOTENCY_KEY_REQUIRED`,
  `RESOURCE_NOT_FOUND`, `VALIDATION_ERROR`, `RATE_LIMITED`,
  `OPERATION_TIMEOUT`, `POLICY_DENIED`, `PAYLOAD_TOO_LARGE`,
  `UPLOAD_SESSION_EXPIRED`, `UPLOAD_SESSION_ABORTED`,
  `UNSUPPORTED_MEDIA_TYPE`, `UNSUPPORTED_TOOL_OPERATION`,
  `PROMPT_HYGIENE_BLOCKED`, `TENANT_SCOPE_DENIED` und
  `INTERNAL_AGENT_ERROR`
- `ApplicationException` als sealed root, erweitert `RuntimeException`:
  ```kotlin
  sealed class ApplicationException(
      val code: ToolErrorCode,
      message: String,
      cause: Throwable? = null,
  ) : RuntimeException(message, cause) {
      open fun details(): List<ToolErrorDetail> = emptyList()
  }
  ```
  `cause` propagiert intern (Stack-Tracing), wird **nie** in
  `ToolErrorEnvelope.details` serialisiert. Jeder Subtyp liefert eine
  deterministische Default-Message ohne PII und seine eigenen Details.
- Genau ein konkreter Subtyp pro `ToolErrorCode` (18 insgesamt) mit
  Code-spezifischen Feldern:
  | Code | Subtyp | Felder |
  | --- | --- | --- |
  | `AUTH_REQUIRED` | `AuthRequiredException` | — |
  | `FORBIDDEN_PRINCIPAL` | `ForbiddenPrincipalException` | `principalId`, optional `reason` |
  | `POLICY_REQUIRED` | `PolicyRequiredException` | `policyName` |
  | `POLICY_DENIED` | `PolicyDeniedException` | `policyName`, optional `reason` |
  | `IDEMPOTENCY_KEY_REQUIRED` | `IdempotencyKeyRequiredException` | — |
  | `IDEMPOTENCY_CONFLICT` | `IdempotencyConflictException` | `existingFingerprint` |
  | `RESOURCE_NOT_FOUND` | `ResourceNotFoundException` | `resourceUri` |
  | `VALIDATION_ERROR` | `ValidationErrorException` | `violations: List<ValidationViolation>` |
  | `RATE_LIMITED` | `RateLimitedException` | `detail: RateLimitedDetail` (aus AP 6.6) |
  | `OPERATION_TIMEOUT` | `OperationTimeoutException` | `operation`, `budget: Duration` |
  | `PAYLOAD_TOO_LARGE` | `PayloadTooLargeException` | `actualBytes`, `maxBytes` |
  | `UPLOAD_SESSION_EXPIRED` | `UploadSessionExpiredException` | `sessionId` |
  | `UPLOAD_SESSION_ABORTED` | `UploadSessionAbortedException` | `sessionId` |
  | `UNSUPPORTED_MEDIA_TYPE` | `UnsupportedMediaTypeException` | `actual`, `allowed` |
  | `UNSUPPORTED_TOOL_OPERATION` | `UnsupportedToolOperationException` | `toolName`, `operation` |
  | `PROMPT_HYGIENE_BLOCKED` | `PromptHygieneBlockedException` | `reason` |
  | `TENANT_SCOPE_DENIED` | `TenantScopeDeniedException` | `requestedTenant` |
  | `INTERNAL_AGENT_ERROR` | `InternalAgentErrorException` | optional `cause` |
- `ValidationErrorException` haelt eine Liste:
  ```kotlin
  data class ValidationViolation(val field: String, val reason: String)
  ```
  `details()` mappt jede `violation` auf
  `ToolErrorDetail(field, reason)` in Insertion-Order.
- `RateLimitedException(detail: RateLimitedDetail)` brueckt zu AP 6.6:
  `details()` liefert genau `dimension`/`current`/`limit` aus
  `RateLimitedDetail` — `tenantId`/`principalId` sind dort schon
  abgestreift, der Mapper produziert keinen weiteren Leak.
- `ErrorMapper`-API als Interface:
  ```kotlin
  interface ErrorMapper {
      fun map(throwable: Throwable, requestId: String? = null): ToolErrorEnvelope
  }
  ```
  Verhalten:
  - `ApplicationException` -> Envelope aus `code`, `message`,
    `details()` und `requestId`
  - jede andere `Throwable` -> `INTERNAL_AGENT_ERROR` mit
    deterministischer generischer Message (kein Stacktrace, kein
    Original-`message`, keine `cause`-Daten in den Details — Schutz
    vor Information Disclosure)

Abnahme:

- gleiche Fehlerursache erzeugt denselben Code
- fehlende/malformed Eingaben liefern `VALIDATION_ERROR`
- wohlgeformte, aber unbekannte Ressourcen liefern `RESOURCE_NOT_FOUND`
- fremde Tenants liefern `TENANT_SCOPE_DENIED`
- jeder verbindliche Fehlercode hat einen deterministischen Mapper-Test
- jeder der 18 Codes hat genau einen Subtyp; Bidirektionalitaet
  (`subtype.code == X`) ist getestet
- unbekannte Throwables fallen deterministisch auf
  `INTERNAL_AGENT_ERROR` ohne Information Disclosure
- `ValidationErrorException`-Details bewahren Insertion-Order der
  `violations`-Liste

### 6.8 Audit-Kernvertrag und Scrubbing definieren

Aufgaben:

- Audit-Event-Struktur definieren:
  - `requestId`
  - `tenantId` bzw. adressierter Tenant, sofern bekannt
  - `principalId` oder authentifizierbare Fehlerkontextdaten
  - `toolName` oder Resource-Methode, sofern bekannt
  - Outcome-Code
  - Fehlercode
  - Payload-Fingerprint
  - Resource-/Artifact-/Job-Refs
  - Zeitstempel und Dauer
- Around-/Finally-Audit-Scope modellieren:
  - Scope beginnt direkt nach `requestId`-Erzeugung
  - Scope endet im `finally`-Pfad mit Erfolg oder Fehler
  - fruehe Fehler wie Auth, Validation, Scope, Policy und Idempotency werden
    ebenfalls auditiert
- `AuditScope`-API mit Builder fuer late-bound Felder:
  ```kotlin
  class AuditScope(
      private val sink: AuditSink,
      private val clock: Clock,
      private val scrubber: SecretScrubber,
  ) {
      fun <T> around(context: AuditContext, block: (AuditFields) -> T): T
  }

  data class AuditContext(
      val requestId: String,
      val toolName: String? = null,
      val tenantId: TenantId? = null,
      val principalId: PrincipalId? = null,
  )

  class AuditFields {
      var payloadFingerprint: String? = null
      var resourceRefs: List<String> = emptyList()
  }
  ```
  Der `block` erhaelt eine mutable [AuditFields]-Instanz, ueber die er
  spaet gebundene Felder (Fingerprint nach Canonicalization, Resource-Refs
  nach Lookup) befuellt. `around` misst `durationMs`, faengt Throwables,
  emittiert das Event im `finally`-Pfad und **wirft die Original-Exception
  weiter**.
- Outcome-Mapping fuer den Scope:
  - Block returnt -> `SUCCESS`, `errorCode = null`
  - `ApplicationException` -> `FAILURE`, `errorCode = ex.code`, rethrow
  - jede andere `Throwable` -> `FAILURE`,
    `errorCode = INTERNAL_AGENT_ERROR`, rethrow
- Secret-Scrubbing fuer Audit-Felder definieren:
  - keine rohen Tokens
  - keine JDBC-Secrets
  - keine kompletten Uploadsegmente
  - keine ungefilterten SQL-/DDL-/Prozedurtexte
  - Approval-Tokens nur als ID oder Fingerprint
- `SecretScrubber` als adapterneutrales Helper-Object:
  ```kotlin
  class SecretScrubber {
      fun scrub(text: String): String
  }
  ```
  `AuditScope` ruft `scrubber.scrub(...)` ueber jede `resourceRef`
  vor `sink.emit`. `AuditEvent` haelt sowieso keine Raw-Payloads (nur
  Fingerprints/IDs), daher zielt das Scrubbing auf
  versehentlich-eingeschleuste Marker in `resourceRefs`. Phase-A-
  Patterns: JDBC-Passwords (`password=...`), Bearer-Tokens, Approval-
  Token-Marker (`tok_...`).
- `LoggingAuditSink` schreibt **eine Zeile pro Event** als manuell
  formatiertes JSON (kein Jackson — neues Modul soll duenn bleiben),
  Logger-Name `dev.dmigrate.audit`, Level `INFO`. Beispiel:
  ```
  {"requestId":"req-1","outcome":"SUCCESS","toolName":"data.export",
   "tenantId":"acme","durationMs":42}
  ```
  `null`-Felder werden ausgelassen, damit Log-Aggregatoren keine
  unnoetigen Keys parsen.
- Neues Modul `adapters:driven:audit-logging` analog zu
  `adapters:driven:storage-file`: haengt an `:hexagon:ports-common`,
  `implementation` SLF4J, keine weiteren Deps. Eintrag in
  `settings.gradle.kts` + Kover-Aggregator in Root-`build.gradle.kts`.
- AuditSink-Test-Doubles fuer Unit-Tests bereitstellen
  (`InMemoryAuditSink` existiert seit AP 6.2 in
  `hexagon:ports-common` testFixtures)

Abnahme:

- `AUTH_REQUIRED`, `VALIDATION_ERROR`, `TENANT_SCOPE_DENIED`,
  `POLICY_REQUIRED`, `POLICY_DENIED` und `IDEMPOTENCY_CONFLICT` erzeugen
  Audit-Outcomes
- bei fehlendem Principal wird kein erfundener Principal auditiert
  (`AuditContext.principalId == null` -> `AuditEvent.principalId == null`)
- gescrubbte Felder enthalten keine bekannten Secret-/Token-Marker
- identische Retries sind im Audit erkennbar, ohne Payloads im Klartext zu
  speichern
- `AuditScope.around` rethrowt die Original-Exception nach dem
  `sink.emit`-Aufruf — Caller sehen denselben Throwable, den sie ohne
  Audit-Wrapper sehen wuerden
- `LoggingAuditSink` schreibt deterministisches JSON, das von einem
  Logger-Spy in `LoggingAuditSinkTest` parsbar bestaetigt wird

---

## 7. Verifikationsstrategie

### 7.1 Unit-Tests

Pflichtabdeckung:

- `PrincipalContext`:
  - `effectiveTenantId` im Home-Tenant
  - `effectiveTenantId` in `allowedTenantIds`
  - fremder Tenant -> `TENANT_SCOPE_DENIED`
  - `isAdmin` ohne Tenant-Erlaubnis hebt Scope nicht auf
- Resource-URI-Parsing:
  - gueltige Job-/Artefakt-/Schema-/Connection-URIs
  - malformed URI
  - Tenant-Mismatch
- ConnectionReferenceStore:
  - non-secret Felder
  - Bootstrap aus Test-Seed oder Projekt-/Serverkonfiguration
  - Tenant-/Principal-Filterung
  - Sensitivitaets-/Produktionsklassifizierung
  - `credentialRef`/`providerRef` statt Secret
- Fingerprint-Stabilitaet:
  - stabile Feldreihenfolge
  - Ausschluss transienter Kontrollfelder
  - top-level Control-Feld wird ausgeschlossen
  - gleichnamiges Nested-Fachfeld bleibt enthalten
  - Array-Reihenfolge bleibt relevant
  - Zahlen/String/Boolean/`null`/Defaultwerte sind deterministisch
  - Konflikt bei anderem Payload
- UploadSession-State-Machine:
  - erlaubte States `ACTIVE`, `COMPLETED`, `ABORTED`, `EXPIRED`
  - erlaubte Transitions
  - terminale States akzeptieren keine Segmente
  - Resume nur in `ACTIVE`
  - Finalize prueft lueckenlose Segmente, Segmenthashes und Gesamthash
- Upload-Stores:
  - atomarer Segmentwrite
  - abweichender Segmentwrite-Konflikt
  - Range-/Chunk-Read
  - TTL-Cleanup
  - Abort-Cleanup
  - file-backed Contract-Test mit echter Datei
  - Filesystem-Cleanup
- Schema-/Profile-/Diff-Stores:
  - typisierte Artefakt-Indizes
  - `ProfileStore` ohne direkte `hexagon:profiling`-Typen
  - tenant-scoped Lookup
  - Pagination
  - Retention-/Expiry-Filter
- Idempotency-State-Machine:
  - atomare `PENDING`-Reservierung
  - parallele identische Starts erzeugen nur eine Reservierung
  - `PENDING`-Lease-Recovery
  - Wechsel nach `AWAITING_APPROVAL`
  - genehmigter Retry erzeugt genau einen `COMMITTED` Job
  - `DENIED` liefert `POLICY_DENIED` bis Ablauf
  - gleicher Store-Key mit anderem Payload liefert
    `IDEMPOTENCY_CONFLICT`
- Sync-Effect-Idempotency:
  - `approvalKey`-Scope
  - identischer Retry liefert dasselbe Ergebnis
  - abweichender Payload liefert `IDEMPOTENCY_CONFLICT`
- Read-only Init-Resume:
  - `clientRequestId`-Scope fuer `schema_staging_readonly`
  - identischer Init liefert dieselbe `uploadSessionId`
  - abweichende Init-Metadaten liefern `IDEMPOTENCY_CONFLICT`
  - `initResumeExpiresAt`
  - ohne `clientRequestId` nicht resumable
- Approval-Grants:
  - `correlationKind=idempotencyKey`
  - `correlationKind=approvalKey`
  - Token-Fingerprint statt Roh-Token
  - Reuse mit anderem Payload
  - Ablaufzeit
  - Scope-Mismatch
  - Tenant-/Caller-/Tool-/Correlation-Mismatch
- Quotas und Timeouts:
  - aktive Job-Quota
  - aktive Session-Quota
  - Byte-Quota
  - parallele Segmentwrite-Quota
  - Provider-/Tool-Aufruf-Rate-Limit
  - Release bei Job-Terminalstatus
  - Release bei Upload-Abort und Upload-Expiry
  - Release/Refund bei fehlgeschlagener Finalisierung
  - Refund bei Idempotency-Recovery ohne neue Side Effects
  - `RATE_LIMITED`
  - `OPERATION_TIMEOUT`
- Error-Mapper:
  - `AUTH_REQUIRED`
  - `POLICY_REQUIRED`
  - `POLICY_DENIED`
  - `IDEMPOTENCY_KEY_REQUIRED`
  - `VALIDATION_ERROR`
  - `RESOURCE_NOT_FOUND`
  - `TENANT_SCOPE_DENIED`
  - `FORBIDDEN_PRINCIPAL`
  - `IDEMPOTENCY_CONFLICT`
  - `RATE_LIMITED`
  - `OPERATION_TIMEOUT`
  - `PAYLOAD_TOO_LARGE`
  - `UPLOAD_SESSION_EXPIRED`
  - `UPLOAD_SESSION_ABORTED`
  - `UNSUPPORTED_MEDIA_TYPE`
  - `UNSUPPORTED_TOOL_OPERATION`
  - `PROMPT_HYGIENE_BLOCKED`
  - `INTERNAL_AGENT_ERROR`
- Audit:
  - Around-/Finally-Outcome fuer Erfolg
  - Outcome fuer `AUTH_REQUIRED`
  - Outcome fuer `VALIDATION_ERROR`
  - Outcome fuer `TENANT_SCOPE_DENIED`
  - Outcome fuer `POLICY_REQUIRED` und `POLICY_DENIED`
  - Outcome fuer `IDEMPOTENCY_CONFLICT`
  - Scrubbing fuer Tokens, Secrets, Uploadsegmente und Roh-SQL/-DDL

### 7.2 Keine Integrationstests in Phase A erforderlich

Phase A hat noch keinen MCP-Adapter und keinen Serverstart. Integrationstests
gegen `stdio` oder HTTP gehoeren in spaetere Phasen.

Stattdessen muessen die Unit-Tests so angelegt sein, dass Phase B und C die
Kernservices ohne Vertragsnacharbeit verwenden koennen.

---

## 8. Betroffene Codebasis

Voraussichtlich betroffen:

- `hexagon/application`
  - gemeinsame Services fuer Upload, Idempotency, Quotas, Fehler und Audit
- bestehende Port-Module oder ein gemeinsames Port-Modul
  - adapterneutrale Store-Interfaces und Kernmodelle
- `hexagon:ports-common`
  - konkrete Zielablage fuer gemeinsame Modelle und Store-/Service-Ports;
    `ProfileStore` bleibt dort ein Metadaten-/Artefaktindex ohne
    Abhaengigkeit auf `hexagon:profiling`
- driven Infrastrukturmodul fuer file-backed Byte-Stores, z.B.
  `adapters:driven:storage-file` oder ein dediziertes internes
  Infrastrukturmodul
  - file-backed `UploadSegmentStore`
  - file-backed `ArtifactContentStore`
  - Filesystem-Cleanup, atomare Writes und Range-Reads
- Testquellen der betroffenen Module
  - In-Memory-Stores
  - Contract-Tests fuer In-Memory- und file-backed Stores
  - Fingerprint- und Store-Tests

Bewusst nicht betroffen:

- `adapters/driving/mcp`
  - wird erst in Phase B eingefuehrt
- `adapters/driving/cli`
  - keine CLI-Vertragsaenderung in Phase A
- konkrete Datenbanktreiber
  - keine neuen Driver-Faehigkeiten in Phase A

---

## 9. Risiken und Gegenmassnahmen

### 9.1 Kernmodelle werden zu MCP-nah

Risiko:

- spaetere REST-/gRPC-Adapter muessen MCP-spezifische Felder wieder
  entfernen oder uebersetzen

Gegenmassnahme:

- keine MCP-JSON-RPC-Typen in Kernmodellen
- MCP-Projektionen erst im Adapter

### 9.2 Store-Interfaces sind zu schwach fuer Nebenlaeufigkeit

Risiko:

- spaetere Handler erzeugen doppelte Jobs, doppelte Upload-Sessions oder
  inkonsistente Segmentwrites

Gegenmassnahme:

- atomare Store-Operationen direkt im Interface ausdruecken
- parallele Unit-Tests fuer Idempotency und Segmentwrites

### 9.3 RAM-only-Implementierung schleicht in produktnahe Pfade

Risiko:

- 200-MiB-Uploads oder grosse Artefakte ueberlasten den Heap

Gegenmassnahme:

- File-Spooling als Pflichtvertrag
- In-Memory nur fuer Unit-Tests
- Range-/Chunk-Reads als Kernfunktion testen

### 9.4 Approval-Grant-Modell passt nur fuer Sync-Tools

Risiko:

- Start-Tools koennen Grants nicht sauber ueber `idempotencyKey`
  korrelieren

Gegenmassnahme:

- `correlationKind`/`correlationKey` verbindlich modellieren
- Tests fuer beide Korrelationstypen

### 9.5 Tenant-Scope wird ad hoc im Adapter geloest

Risiko:

- unterschiedliche Tool-/Resource-Pfade behandeln Admin- oder Cross-Tenant-
  Zugriffe inkonsistent

Gegenmassnahme:

- `PrincipalContext` mit `effectiveTenantId` und `allowedTenantIds`
- gemeinsame Scope-Pruefung als Kernservice oder Utility

---

## 10. Definition of Done fuer Phase A

Phase A ist abgeschlossen, wenn:

- alle Kernmodelle adapterneutral definiert sind
- Job- und Artefaktmodelle aus `docs/job-contract.md` wiederverwendet
  werden
- `PrincipalContext` Tenant-Sets, `effectiveTenantId`, Scopes, Admin-Status,
  Audit-Subject, Auth-Quelle und Ablaufzeit ausdrueckt
- die Modulgrenze umgesetzt ist: gemeinsame Modelle/Ports in
  `hexagon:ports-common`,
  Services in `hexagon/application`, file-backed Byte-Stores in driven
  Infrastruktur, keine Wire-Typen im Kern
- interne Job-/Artefakt-Store-Records zwingend `tenantId`,
  `ownerPrincipalId`, `visibility`, optionalen `adminScope`, `resourceUri`
  und Retention-/Expiry-Metadaten enthalten; oeffentliche
  `ManagedJob`-/`ManagedArtifact`-Projektionen bleiben
  `docs/job-contract.md`-konform
- Store-Interfaces fuer Jobs, Artefakte, Artefaktbytes, Upload-Sessions,
  Uploadsegmente, Schemas, Profile, Diffs, Connection-Refs, Idempotency,
  Sync-Effect-Idempotency, Approval-Grants, Quotas und Audit existieren
- `IdempotencyStore` bildet `PENDING`, `AWAITING_APPROVAL`, `COMMITTED`,
  `DENIED`, Lease-/Recovery-/TTL- und Konfliktpfade atomar ab
- Sync-Effect-Idempotency fuer `approvalKey` ist modelliert und getestet
- read-only Init-Resume fuer
  `artifact_upload_init(uploadIntent=schema_staging_readonly)` ueber
  `clientRequestId` ist mit Store-Key, TTL, Konfliktverhalten und
  nicht-resumable Verhalten ohne `clientRequestId` modelliert und getestet
- `UploadSegmentStore` und `ArtifactContentStore` atomare Writes,
  Range-/Chunk-Reads, TTL-Cleanup und File-Spooling-Vertrag abbilden
- file-backed `UploadSegmentStore`/`ArtifactContentStore` oder ein
  gleichwertiges produktnahes Spooling-Modul existiert und besteht
  Contract-Tests
- `UploadSession`-State-Machine mit `ACTIVE`, `COMPLETED`, `ABORTED`,
  `EXPIRED`, Resume-Regeln und Finalize-Invarianten ist definiert und
  getestet
- `ConnectionReferenceStore` liefert non-secret, tenant-/principal-
  gefilterte Connection-Metadaten mit Policy-Klassifizierung und
  `credentialRef`/`providerRef`
- `SchemaStore`, `ProfileStore` und `DiffStore` bzw. gleichwertige
  Store-/Index-Abstraktionen fuer Discovery, Resource-Resolver,
  Tenant-Scope, Pagination und Retention existieren
- `ProfileStore` in `hexagon:ports-common` referenziert keine
  `hexagon:profiling`-Typen; profilingspezifische Projektionen bleiben in
  `hexagon:profiling` oder einem explizit begruendeten inneren Profiling-
  Port
- In-Memory-Implementierungen fuer Unit-Tests existieren
- Fingerprint-Kanonik fuer Idempotency, Approval und Upload-Init-Metadaten
  implementiert und getestet ist
- Fingerprint-Tests decken top-level Control-Exclusion, Nested Fields,
  Arrays, Typunterschiede, `null` und Defaultwerte ab
- `ApprovalGrant` `correlationKind`/`correlationKey`, Token-Fingerprint,
  Issuer-Fingerprint, Scope, Payload-Fingerprint und Ablauf abbildet
- der Approval-Grant-Service Grants gegen Tenant, Caller, Tool,
  Correlation, Payload-Fingerprint, Scope und Ablauf validiert
- Quota-/Rate-Limit- und Timeout-Grundlagen implementiert und getestet sind
- Quota-Tests decken aktive Jobs, Upload-Sessions, Upload-/Artefaktbytes,
  parallele Segmentwrites und Provider-/Tool-Aufrufe pro Zeitfenster ab
- Quota-Lifecycle-Tests decken `reserve`, `commit`, `release` und `refund`
  bei Terminalstatus, Abort, Expiry, fehlgeschlagener Finalisierung und
  Idempotency-Recovery ab
- Error-Mapping fuer das vollstaendige Kern-Code-Vokabular aus
  `docs/ki-mcp.md` deterministisch ist, inklusive Auth, Policy,
  Idempotency, Validation, Media/Payload, Rate-Limit, Timeout,
  Upload-Session, Prompt-Hygiene, Tenant-Scope und internem Fehler
- Audit-Event-Struktur, Around-/Finally-Scope, fruehe Fehler-Outcomes und
  Secret-Scrubbing implementiert und getestet sind
- Unit-Tests die in Abschnitt 7 genannten Pflichtfaelle abdecken

---

## 11. Anschluss an Phase B

Phase B darf beginnen, wenn Phase A die Kernvertraege stabil bereitstellt.
Phase B baut darauf auf:

- neues Gradle-Modul `adapters:driving:mcp`
- MCP-Server-Bootstrap fuer `stdio` und streambares HTTP
- Initialize fuer MCP `protocolVersion` `2025-11-25`
- Tool- und Resource-Registry
- Principal-Ableitung fuer lokale und HTTP-Transporte
- Standard-Discovery fuer Resources

Phase B darf keine Kernmodelle nachtraeglich MCP-spezifisch umformen. Wenn
beim Adapterbau Luecken sichtbar werden, muessen sie als adapterneutrale
Ergaenzung in den Phase-A-Kern zurueckgefuehrt werden.

---

## 12. Verbindliche Paket-, Modul- und Klassenstruktur

Dieser Abschnitt konkretisiert Abschnitt 5 fuer die Implementierung. Die hier
festgelegten Modul-, Paket- und Klassennamen sind verbindlich. Abweichungen
sind nur mit expliziter Begruendung im Code-Review zulaessig und muessen die
Modulgrenzen und Abhaengigkeitsrichtungen aus 5.1 erhalten.

### 12.1 Modulzuordnung (verbindlich)

`docs/architecture.md` 1.2 legt fest:

- `hexagon:core` = Domain (Modell, Validierung, Typsystem), keine externen Deps
- `hexagon:ports-common` = gemeinsame Port-Typen (Interfaces, Port-DTOs)
- `hexagon:application` = Use-Cases, haengt nur am Hexagon-Inneren, nie an Adaptern
- Driven-Adapter fuer Logging/Infra (z. B. SLF4J) liegen ausserhalb des Hexagons

Daraus folgt fuer Phase A:

| Inhalt | Modul | Begruendung |
| --- | --- | --- |
| Domaenenentitaeten (Principal, Resource, Job, Artifact, Upload, Approval, Idempotency-State, Connection, Error-Vokabular, Audit-Event-Modell, Pagination, Execution) | `hexagon:core` | reine Domain, keine externen Deps, konsistent mit `architecture.md` 1.2 |
| Store-Ports (alle in 5.3 genannten Interfaces, Sealed-Outcome-Typen) | `hexagon:ports-common` | Port-Interfaces gemeinsam fuer Driving- und Driven-Adapter |
| Services (Fingerprint, Idempotency, Approval, Upload, Quota, Error, Audit-Scope/Scrubber, Tenant-Scope) | `hexagon:application` | adapterneutrale Anwendungsschicht, nutzt nur Core und Ports |
| In-Memory-Stores fuer Tests | `src/testFixtures/kotlin` der jeweiligen Port-Module (Default, siehe 14.8) | Test-Doubles, kein Produktionscode |
| Contract-Tests (gemeinsamer Vertrag fuer In-Memory + file-backed) | `src/testFixtures` von `hexagon:ports-common` | jede konkrete Implementierung ruft die gemeinsame Suite auf |
| File-backed `UploadSegmentStore`, `ArtifactContentStore` | NEU `adapters:driven:storage-file` | Filesystem-Infrastruktur ist Driven-Adapter, nicht Hexagon-intern |
| `LoggingAuditSink` (SLF4J) | NEU `adapters:driven:audit-logging` | SLF4J ist laut Modultabelle Driven-Adapter-Dep (`driver-common`), darf nicht im Hexagon-Inneren liegen |
| `InMemoryAuditSink` | `src/testFixtures` von `hexagon:ports-common` | Test-Double |

Konsequenzen:

- `hexagon:core` bekommt unter `dev.dmigrate.server.core.*` die neuen
  Server-Domaenenentitaeten; Bestands-Pakete unter `dev.dmigrate.core.*`
  (Schema-Domain) bleiben unveraendert
- `hexagon:ports-common` bleibt unabhaengig von `hexagon:profiling` und
  enthaelt ausschliesslich Port-Interfaces fuer den Server-Kern
- `hexagon:application` hat heute Deps auf `core`, `ports`, `profiling`;
  Phase A ergaenzt **keine** Adapter-Deps. Logging-Sink ist deshalb in
  einem Driven-Modul
- `settings.gradle.kts` bekommt zwei neue Eintraege:
  `adapters:driven:storage-file` und `adapters:driven:audit-logging`
- `adapters:driven:storage-file` haengt nur an `hexagon:ports-common`
- `adapters:driven:audit-logging` haengt an `hexagon:ports-common` und SLF4J
- `adapters/driving/cli` bleibt in Phase A unangetastet (bestaetigt 3.2)
- `java-test-fixtures`-Plugin wird in den betroffenen Modulen aktiviert

### 12.2 Kotlin-Pakete

Server-Kernpakete leben unter `dev.dmigrate.server.*`. Das trennt sie
sichtbar von bestehenden CLI-/Driver-/Format-/Schema-Paketen. Die physische
Modulzuordnung (siehe 12.1) bestimmt das Modul; das Paket bleibt logisch.

| Inhalt | Paket | Modul |
| --- | --- | --- |
| `PrincipalContext`, `TenantId`, `PrincipalId`, Tenant-Scope-Pruefung | `dev.dmigrate.server.core.principal` | `hexagon:core` |
| `ServerResourceUri`, `ResourceKind` | `dev.dmigrate.server.core.resource` | `hexagon:core` |
| `ManagedJob`, `JobRecord`, `JobStatus`, `JobVisibility` | `dev.dmigrate.server.core.job` | `hexagon:core` |
| `ManagedArtifact`, `ArtifactRecord`, `ArtifactKind` | `dev.dmigrate.server.core.artifact` | `hexagon:core` |
| `UploadSession`, `UploadSegment`, `UploadSessionState`, Transitions | `dev.dmigrate.server.core.upload` | `hexagon:core` |
| `IdempotencyKey`, `IdempotencyState`, `IdempotencyReserveOutcome`, `SyncEffectReserveOutcome` | `dev.dmigrate.server.core.idempotency` | `hexagon:core` |
| `ApprovalGrant`, `ApprovalCorrelationKind` | `dev.dmigrate.server.core.approval` | `hexagon:core` |
| `ConnectionReference`, `ConnectionSensitivity` | `dev.dmigrate.server.core.connection` | `hexagon:core` |
| `ToolErrorCode`, `ToolErrorDetail`, `ToolErrorEnvelope` | `dev.dmigrate.server.core.error` | `hexagon:core` |
| `PageRequest`, `PageResult` | `dev.dmigrate.server.core.pagination` | `hexagon:core` |
| `ExecutionMeta` | `dev.dmigrate.server.core.execution` | `hexagon:core` |
| `AuditEvent`, `AuditOutcome` | `dev.dmigrate.server.core.audit` | `hexagon:core` |
| Store-Ports (alle Stores aus 5.3 inkl. `AuditSink`) | `dev.dmigrate.server.ports` | `hexagon:ports-common` |
| Quota-Ports + Counter-Vertraege | `dev.dmigrate.server.ports.quota` | `hexagon:ports-common` |
| In-Memory-Stores (testFixtures), `InMemoryAuditSink` | `dev.dmigrate.server.ports.memory` | `hexagon:ports-common` testFixtures |
| Contract-Test-Basisklassen (testFixtures) | `dev.dmigrate.server.ports.contract` | `hexagon:ports-common` testFixtures |
| File-backed Stores | `dev.dmigrate.server.adapter.storage.file` | `adapters:driven:storage-file` |
| `LoggingAuditSink` | `dev.dmigrate.server.adapter.audit.logging` | `adapters:driven:audit-logging` |
| Services | `dev.dmigrate.server.application.<scope>` mit `<scope>` aus `fingerprint`, `idempotency`, `approval`, `upload`, `quota`, `error`, `audit`, `tenant`, `config` | `hexagon:application` |

### 12.3 Klassen pro AP

#### AP 6.1 Kernmodelle

| Klasse | Paket | Typ |
| --- | --- | --- |
| `PrincipalContext` | `...core.principal` | data class |
| `TenantId`, `PrincipalId` | `...core.principal` | value class |
| `TenantScopeChecker` | `...core.principal` | object (Resolve-Logik fuer `effectiveTenantId`) |
| `ServerResourceUri` | `...core.resource` | data class + Companion-Parser |
| `ResourceKind` | `...core.resource` | enum (`JOBS`, `ARTIFACTS`, `SCHEMAS`, `PROFILES`, `DIFFS`, `CONNECTIONS`, `UPLOAD_SESSIONS`) |
| `ManagedJob` | `...core.job` | data class (oeffentlicher Vertrag aus `docs/job-contract.md`) |
| `JobStatus` | `...core.job` | enum |
| `JobRecord` | `...core.job` | data class (Storage-Envelope mit `tenantId`, `ownerPrincipalId`, `visibility`, `adminScope?`, `resourceUri`, Retention) |
| `JobVisibility` | `...core.job` | enum (`OWNER`, `TENANT`, `ADMIN`) |
| `ManagedArtifact`, `ArtifactRecord` | `...core.artifact` | data classes |
| `ArtifactKind` | `...core.artifact` | enum |
| `UploadSession` | `...core.upload` | data class |
| `UploadSessionState` | `...core.upload` | enum (`ACTIVE`, `COMPLETED`, `ABORTED`, `EXPIRED`) |
| `UploadSegment` | `...core.upload` | data class |
| `UploadSessionTransitions` | `...core.upload` | object (allowed-transitions Tabelle + Validator) |
| `ConnectionReference` | `...core.connection` | data class |
| `ConnectionSensitivity` | `...core.connection` | enum (`NON_PRODUCTION`, `PRODUCTION`, `SENSITIVE`) |
| `ToolErrorCode` | `...core.error` | enum (alle 18 Codes aus 6.7) |
| `ToolErrorEnvelope`, `ToolErrorDetail` | `...core.error` | data classes |
| `PageRequest`, `PageResult<T>` | `...core.pagination` | data classes |
| `ExecutionMeta` | `...core.execution` | data class |
| `ApprovalGrant` | `...core.approval` | data class |
| `ApprovalCorrelationKind` | `...core.approval` | enum (`IDEMPOTENCY_KEY`, `APPROVAL_KEY`) |

#### AP 6.2 Store-Interfaces

Alle in 5.3 genannten Stores sind Kotlin-`interface`s in
`dev.dmigrate.server.ports`. Sealed-Result-Typen liegen daneben:

| Klasse | Paket |
| --- | --- |
| `JobStore`, `ArtifactStore`, `SchemaStore`, `ProfileStore`, `DiffStore` | `...server.ports` |
| `UploadSessionStore`, `UploadSegmentStore` | `...server.ports` |
| `ConnectionReferenceStore` | `...server.ports` |
| `IdempotencyStore`, `SyncEffectIdempotencyStore` | `...server.ports` |
| `ApprovalGrantStore` | `...server.ports` |
| `QuotaStore`, `QuotaKey`, `QuotaCounter` | `...server.ports.quota` |
| `AuditSink` | `...server.ports` |
| `IdempotencyReserveOutcome`, `SyncEffectReserveOutcome` (sealed) | `...server.core.idempotency` |
| `ArtifactContentStore` | `...server.ports` |

#### AP 6.3 Byte-Stores

| Klasse | Paket | Modul |
| --- | --- | --- |
| `UploadSegmentStore`, `ArtifactContentStore` | `...server.ports` | `hexagon:ports-common` |
| `UploadSegmentStoreContractTests`, `ArtifactContentStoreContractTests` (abstrakt, je eigene Basisklasse) | `...server.ports.contract` | `hexagon:ports-common` testFixtures |
| `InMemoryUploadSegmentStore`, `InMemoryArtifactContentStore` | `...server.ports.memory` | `hexagon:ports-common` testFixtures |
| `FileBackedUploadSegmentStore`, `FileBackedArtifactContentStore` | `...server.adapter.storage.file` | `adapters:driven:storage-file` (NEU) |

#### AP 6.4 Fingerprint-Service

| Klasse | Paket |
| --- | --- |
| `PayloadFingerprintService` (interface) | `...application.fingerprint` |
| `DefaultPayloadFingerprintService` | `...application.fingerprint` |
| `JsonCanonicalizer` (RFC 8785-kompatibel) | `...application.fingerprint` |
| `JsonValue` (sealed: `Null`, `Bool`, `Num`, `Str`, `Arr`, `Obj`) | `...application.fingerprint` |
| `FingerprintScope` | `...application.fingerprint` (enum: `START_TOOL`, `SYNC_TOOL`, `UPLOAD_INIT`) |
| `BindContext` | `...application.fingerprint` (`tenantId`, `callerId`, `toolName`) |
| `FingerprintNormalization` | `...application.fingerprint` (Top-level-Control-Exclusion + Tenant-Bind) |

#### AP 6.5 Approval-Grant-Service

| Klasse | Paket |
| --- | --- |
| `ApprovalGrantService` (interface) | `...application.approval` |
| `DefaultApprovalGrantService` | `...application.approval` |
| `ApprovalGrantValidator` | `...application.approval` |
| `ApprovalAttempt` (data class) | `...application.approval` |
| `ApprovalGrantValidation` (sealed: `Valid`, `Invalid.{Unknown, Expired, TenantMismatch, CallerMismatch, ToolMismatch, CorrelationMismatch, PayloadMismatch, ScopeMismatch, IssuerMismatch}`) | `...application.approval` |
| `IssuerCheck` (sealed: `Off`, `AllowList(trusted)`) | `...application.approval` |
| `ApprovalTokenFingerprint` (object, `compute(rawToken)`) | `...application.approval` |

#### AP 6.6 Quota/Rate-Limit/Timeout

| Klasse | Paket |
| --- | --- |
| `QuotaService` (interface) | `...application.quota` |
| `DefaultQuotaService` | `...application.quota` |
| `QuotaReservation` (data class) | `...application.quota` |
| `RateLimiter` (interface) | `...application.quota` |
| `FixedWindowRateLimiter` | `...application.quota` |
| `TimeoutBudget` (data class, `Duration`-Carrier) | `...application.quota` |
| `ServerCoreLimits` (data class with nested `IdempotencyLimits`, `ApprovalLimits`, `UploadLimits`, `QuotaLimits`) | `...application.config` |

`QuotaOutcome` lebt bereits seit AP 6.2 in `...server.ports.quota` und
wird vom Service durchgereicht.

#### AP 6.7 Error-Mapper

| Klasse | Paket |
| --- | --- |
| `ApplicationException` (sealed root, erweitert `RuntimeException`) | `...application.error` |
| 18 konkrete Subtypen je Code (`AuthRequiredException`, ..., `InternalAgentErrorException` — siehe Tabelle in §6.7) | `...application.error` |
| `ValidationViolation` (data class) | `...application.error` |
| `ErrorMapper` (interface) | `...application.error` |
| `DefaultErrorMapper` | `...application.error` |

#### AP 6.8 Audit-Kern

| Klasse | Paket | Modul |
| --- | --- | --- |
| `AuditEvent`, `AuditOutcome` | `...server.core.audit` | `hexagon:core` (seit AP 6.1) |
| `AuditSink` | `...server.ports` | `hexagon:ports-common` (seit AP 6.2) |
| `AuditScope` (around-/finally-Wrapper) | `...server.application.audit` | `hexagon:application` |
| `AuditContext` (data class) | `...server.application.audit` | `hexagon:application` |
| `AuditFields` (mutable Builder fuer late-bound Felder) | `...server.application.audit` | `hexagon:application` |
| `SecretScrubber` | `...server.application.audit` | `hexagon:application` |
| `LoggingAuditSink` | `...server.adapter.audit.logging` | `adapters:driven:audit-logging` (NEU) |
| `InMemoryAuditSink` (testFixtures) | `...server.ports.memory` | `hexagon:ports-common` testFixtures (seit AP 6.2) |

---

## 13. Arbeitspaket-Abhaengigkeiten und Test-Tabelle

### 13.1 Abhaengigkeitsgraph

```text
AP 6.1 (Kernmodelle, inkl. ToolErrorCode-Enum)
  |
  +-- AP 6.4 (Fingerprint, ohne Stores)
  |     |
  |     +-- AP 6.5 (Approval)
  |     +-- AP 6.8 (Audit, payloadFingerprint im Event)
  |
  +-- AP 6.7 (Error-Mapper, ohne Stores)
  |
  +-- AP 6.2 (Store-Ports)
        |
        +-- AP 6.3 (Byte-Stores: file-backed + InMemory + Contract-Tests)
        +-- AP 6.5 (ApprovalGrantStore)
        +-- AP 6.6 (QuotaStore)
        +-- AP 6.8 (AuditSink)
```

Empfohlene Implementierungsreihenfolge fuer einen sequenziellen Run:

1. AP 6.1 (Kernmodelle, neue Modulpakete, `ToolErrorCode`)
2. parallel: AP 6.4 (Fingerprint), AP 6.7 (Error-Mapper)
3. AP 6.2 (Store-Ports inkl. Sealed-Outcomes)
4. parallel: AP 6.3 (Byte-Stores + neuer Modulslot), AP 6.5 (Approval), AP 6.6 (Quota), AP 6.8 (Audit)

### 13.2 Test-Klassen pro AP

Tests laufen in den jeweiligen Modulen unter `src/test/kotlin/...test`.
Contract-Test-Basisklassen liegen in `src/testFixtures/kotlin/...contract`.

| AP | Pflicht-Testklassen |
| --- | --- |
| 6.1 | `PrincipalContextTest`, `TenantScopeCheckerTest`, `ServerResourceUriParserTest`, `ResourceKindTest`, `JobRecordTest`, `JobVisibilityTest`, `ArtifactRecordTest`, `UploadSessionTransitionsTest`, `UploadSessionFinalizeInvariantsTest`, `ConnectionReferenceTest`, `ToolErrorEnvelopeTest`, `PaginationTest` |
| 6.2 | `JobStoreContractTest`, `ArtifactStoreContractTest`, `SchemaStoreContractTest`, `ProfileStoreContractTest`, `DiffStoreContractTest`, `UploadSessionStoreContractTest`, `UploadSegmentStoreContractTest`, `ConnectionReferenceStoreContractTest`, `IdempotencyStoreContractTest` (inkl. parallel-reserve, lease-recovery, awaiting-approval, committed, denied, conflict), `SyncEffectIdempotencyStoreContractTest`, `ReadOnlyInitResumeContractTest`, `ApprovalGrantStoreContractTest`, `QuotaStoreContractTest`, `AuditSinkContractTest` |
| 6.3 | `UploadSegmentStoreContractTests` (abstract), `ArtifactContentStoreContractTests` (abstract), `InMemoryUploadSegmentStoreTest` und `InMemoryArtifactContentStoreTest` (in `InMemoryStoreContractTests`), `FileBackedUploadSegmentStoreTest`, `FileBackedArtifactContentStoreTest`, `FileBackedAtomicWriteTest`, `FileBackedRangeReadTest`, `FileBackedTtlCleanupTest`, `FileBackedConcurrentWriteTest`, `FileBackedOrphanCleanupTest`, `FileBackedPathTraversalTest` |
| 6.4 | `JsonCanonicalizerTest`, `JsonCanonicalizerRfc8785VectorTest`, `JsonValueRejectsFloatAndBigIntTest`, `PayloadFingerprintServiceTest`, `FingerprintTopLevelExclusionTest`, `FingerprintNestedFieldRetentionTest`, `FingerprintArrayOrderTest`, `FingerprintNullAndDefaultsTest`, `FingerprintTenantBindTest`, `FingerprintReservedBindKeyTest`, `UploadSessionFingerprintVsSegmentTest` |
| 6.5 | `ApprovalGrantValidatorTest` (correlation-kinds, expiry, reuse, scope-mismatch, tenant/caller/tool-mismatch, payload-mismatch), `ApprovalGrantValidationOrderTest` (deterministische Reihenfolge bei mehreren Fehlern), `ApprovalGrantTokenFingerprintTest`, `ApprovalGrantServiceTest` (Store-Integration + Issuer-Check on/off) |
| 6.6 | `QuotaServiceTest` (aktive Jobs, Sessions, Bytes, parallel Segmentwrites, Provider-Calls), `QuotaLifecycleTest` (reserve/commit/release/refund inkl. Terminalstatus, Abort, Expiry, Failed-Finalize, Idempotency-Recovery, Multi-Reserve-Compensation), `FixedWindowRateLimiterTest` (Window-Rollover via injizierte Clock), `TimeoutBudgetTest`, `RateLimitedDetailScrubbingTest`, `ServerCoreLimitsDefaultsTest` |
| 6.7 | `ErrorMapperTest` (parametrisiert pro Code: alle 18 Codes aus `docs/ki-mcp.md`), `ValidationErrorMapperTest`, `AppExceptionHierarchyTest` |
| 6.8 | `AuditScopeAroundFinallyTest`, `AuditEarlyFailureTest` (`AUTH_REQUIRED`, `VALIDATION_ERROR`, `TENANT_SCOPE_DENIED`, `POLICY_REQUIRED`/`POLICY_DENIED`, `IDEMPOTENCY_CONFLICT`), `SecretScrubberTest`, `AuditOutcomeMappingTest`, `LoggingAuditSinkTest` |

### 13.3 Coverage-Ziele

- Bestehende Kover-Verify-Regel `minBound(90)` gilt unveraendert fuer
  `hexagon:core`, `hexagon:ports-common` und `hexagon:application`
- Neue Module `adapters:driven:storage-file` und
  `adapters:driven:audit-logging` uebernehmen dieselbe
  `minBound(90)`-Regel
- Reine data classes / enums / sealed-Hierarchien duerfen ueber den
  bestehenden `kover.reports.filters.excludes`-Mechanismus ausgenommen
  werden, aber **keine** Service-Logik
- Contract-Test-Basisklassen sind selbst testbar; ihre Coverage zaehlt
  nicht gegen die Service-Module, weil sie in testFixtures liegen

---

## 14. Offene Entscheidungen mit Default-Empfehlung

Diese Defaults gelten ab Phase-A-Start, falls nicht vor dem ersten Edit
explizit ueberschrieben.

### 14.1 JSON-Kanonisierung fuer Fingerprints

- Default: **RFC 8785 JCS-kompatibel**
- Felder lexikographisch nach UTF-16-Code-Unit sortiert (Java
  `String.compareTo`-Semantik)
- Kein Whitespace
- Strings: UTF-8 Output, Escape-Pflicht:
  - `\"` fuer `"`
  - `\\` fuer `\`
  - `\uXXXX` (lowercase hex) fuer Control-Zeichen `0x00`–`0x1F`
  - alle anderen Zeichen literal
- Zahlen: nur ganzzahlig (`Long`, Bereich
  `Long.MIN_VALUE`..`Long.MAX_VALUE`). Float, Double, BigInteger und
  BigDecimal sind in Phase A nicht erlaubt und werden vom Canonicalizer
  mit `IllegalArgumentException` abgewiesen. Spaetere Tools, die Floats
  brauchen, fuegen die Zahlen-Kanonisierung in einer eigenen Refinement-
  Iteration hinzu
- Booleans: `true`/`false`
- `null`: explizit erhalten
- Unicode-Normalisierung: NFC fuer alle Stringwerte **und Object-Keys**
  vor dem Sortieren und Hashing (ICU4J ist auf
  `hexagon:application` bereits vorhanden)

### 14.2 Default-TTLs/-Leases

| Parameter | Default | Begruendung |
| --- | --- | --- |
| `idempotency.pendingLease` | 60 s | typische Job-Annahme < 1 min |
| `idempotency.awaitingApproval` | 600 s | manuelle Approval-Schritte |
| `idempotency.committedRetention` | 24 h | dominierende Job-Retention |
| `idempotency.initResumeTtl` | 600 s | read-only Init-Resume |
| `approval.grantDefaultTtl` | 300 s | kurzlebige Tokens |
| `upload.sessionIdleTimeout` | 900 s | grosse Uploads ueber instabile Netze |
| `upload.sessionAbsoluteLease` | 3600 s | obere Schranke pro Session |
| `quota.activeJobsPerTenant` | 16 | konservativ |
| `quota.activeUploadsPerTenant` | 4 | begrenzt parallele Spool-Files |
| `quota.uploadBytesPerTenant` | 1 GiB | konservativ; per Deployment ueberschreibbar |
| `quota.providerCallsPerMinute` | 60 | KI-Provider-Schutz |

Carrier: `dev.dmigrate.server.application.config.ServerCoreLimits` (data class
mit nested `IdempotencyLimits`/`ApprovalLimits`/`UploadLimits`/`QuotaLimits`),
per Konstruktorinjektion in Services. Defaults entsprechen der Tabelle oben;
Unit-Tests setzen abweichende Werte ueber `copy(...)`. Per-Principal-Limits
(Subset von `QuotaLimits` mit `principalId`-Override) bleiben in Phase A
absichtlich offen — `QuotaKey.principalId: PrincipalId?` erlaubt sie schon
auf Store-Ebene, aber kein Default ist konfiguriert.

### 14.3 `IdempotencyStore.reserve(...)` Rueckgabetyp

```kotlin
sealed interface IdempotencyReserveOutcome {
    data class Reserved(
        val key: IdempotencyKey,
        val leaseExpiresAt: Instant,
    ) : IdempotencyReserveOutcome

    data class ExistingPending(
        val key: IdempotencyKey,
        val leaseExpiresAt: Instant,
    ) : IdempotencyReserveOutcome

    data class AwaitingApproval(
        val key: IdempotencyKey,
        val expiresAt: Instant,
    ) : IdempotencyReserveOutcome

    data class Committed(
        val key: IdempotencyKey,
        val resultRef: String,
    ) : IdempotencyReserveOutcome

    data class Denied(
        val key: IdempotencyKey,
        val expiresAt: Instant,
        val reason: String,
    ) : IdempotencyReserveOutcome

    data class Conflict(
        val key: IdempotencyKey,
        val existingFingerprint: String,
    ) : IdempotencyReserveOutcome
}
```

`SyncEffectIdempotencyStore.reserveSyncEffect(...)` verwendet eine
strukturell aequivalente Hierarchie unter dem Namen
`SyncEffectReserveOutcome`. Die Hierarchien werden bewusst nicht geteilt,
damit Aufrufstellen den Scope (`idempotencyKey` vs. `approvalKey`) sehen.

### 14.4 `ApprovalGrant.issuerFingerprint`

- Format: SHA-256 hex einer kanonischen Issuer-Identitaet
- Phase-A-Default: `sha256("issuerType:issuerId")` mit
  `issuerType` aus {`local`, `oidc`, `service-account`} und `issuerId` als
  Issuer-eindeutigem String (`iss`-Claim oder Service-Identifier)
- Phase A speichert nur den Fingerprint; produktive Verifikation gegen
  Public-Key/JWKS folgt in spaeteren Phasen, muss aber denselben
  Fingerprint herstellen

### 14.5 `ServerResourceUri`-Schema

```text
dmigrate://tenants/{tenantId}/{kind}/{id}
```

- `kind` aus `ResourceKind`-Enum, in URI klein-kebab-cased (`upload-sessions`, `connections`, `jobs`, ...)
- `tenantId` und `id` matchen `[A-Za-z0-9_\-]+`
- Parsing-Fehler -> `VALIDATION_ERROR`
- Tenant-Mismatch gegenueber `effectiveTenantId` -> `TENANT_SCOPE_DENIED`
- unbekannte Ressource -> `RESOURCE_NOT_FOUND`

### 14.6 `payloadFingerprint`-Algorithmus

```text
payloadFingerprint = sha256_hex( jcs_canonicalize( normalize( payload ) ) )
```

Normalisierung:

- Top-level Control-Felder entfernen: `idempotencyKey`, `approvalToken`,
  `clientRequestId`, `requestId`
- Tenant-/Caller-Bindung explizit als
  `_bind = { tenantId, callerId, toolName, scope, ... }` voranstellen,
  damit identische Fachpayloads ueber Tenants/Caller hinweg
  unterschiedliche Fingerprints liefern
- `_bind` ist reserviert; ein vom Caller geliefertes Top-level-`_bind`
  fuehrt zu `IllegalArgumentException`
- `callerId` ist `PrincipalContext.principalId.value` (nicht
  `auditSubject` und nicht `homeTenantId`)
- alle anderen Felder bleiben strukturell unveraendert

`_bind`-Schema pro Scope:

| Scope | `_bind`-Felder |
| --- | --- |
| `START_TOOL` | `tenantId`, `callerId`, `toolName`, `scope` |
| `SYNC_TOOL` | `tenantId`, `callerId`, `toolName`, `scope` |
| `UPLOAD_INIT` | `tenantId`, `callerId`, `toolName`, `scope`, `artifactKind`, `mimeType`, `sizeBytes`, `checksumSha256`, `uploadIntent` |

Upload-Init-Fingerprint nutzt `scope = UPLOAD_INIT`. Upload-Segment-Hash
ist davon getrennt und nutzt ausschliesslich `segmentSha256`; der
`PayloadFingerprintService` sieht Segmentbytes nie. `FingerprintScope`
hat deshalb in Phase A nur drei Werte (kein `UPLOAD_SEGMENT`).

### 14.7 `AuditSink`-Vertrag

```kotlin
interface AuditSink {
    fun emit(event: AuditEvent)
}
```

Modulplatzierung gemaess `docs/architecture.md` 1.2:

- `AuditSink`-Interface in `hexagon:ports-common`
- `AuditEvent`/`AuditOutcome` in `hexagon:core`
- `LoggingAuditSink` (SLF4J, strukturierter Logger `dev.dmigrate.audit`)
  in NEUEM Modul `adapters:driven:audit-logging` — SLF4J ist laut
  Architektur ein Driven-Adapter-Dep, darf nicht im Hexagon-Inneren
  liegen
- `InMemoryAuditSink` in `hexagon:ports-common` testFixtures

Persistente Sinks (DB/Datei) sind Phase-B-Thema und liegen ebenfalls
auessen.

Contract-Test-Strategie: `AuditSink` exportiert nur `emit(event)` und
hat absichtlich kein Read-API. `AuditSinkContractTests` nimmt deshalb
einen `() -> InMemoryAuditSink`-Factory (nicht `() -> AuditSink`) und
verifiziert ueber die test-only `recorded()`-Methode der In-Memory-
Implementierung. Driven-Sinks (`LoggingAuditSink`, kuenftige DB-Sinks)
brauchen keinen Contract-Test gegen den Read-Pfad; ihre Korrektheit
wird ueber das Output-Verhalten geprueft (Logger-Spy, DB-Query, etc.)
in den jeweiligen Adapter-Modulen.

### 14.8 testFixtures vs. dediziertes Test-Modul

Default: **`java-test-fixtures`-Plugin** in den betroffenen Modulen.
In-Memory-Stores und Contract-Test-Basisklassen werden ueber
`testFixturesApi` exportiert.

Falls `java-test-fixtures` mit Coverage- oder Publishing-Setup kollidiert,
Fallback auf ein neues Modul `test:server-core-fixtures` analog zu
bestehenden `test:integration-*`-Modulen. Die Fallback-Entscheidung wird
beim ersten Build-Fehler getroffen, nicht spekulativ.

### 14.9 Anti-Scope-Bestaetigung fuer bestehende CLI-Pfade

`adapters/driving/cli` und alle `*Runner`-Klassen in
`hexagon/application/.../cli/commands/` werden in Phase A **nicht**
modifiziert. Sie sind in Phase B/C potenzielle Konsumenten der neuen
Kernservices, aber nicht Teil der Phase-A-Auslieferung. Phase A fuegt
ausschliesslich neue Pakete unter `dev.dmigrate.server.*` hinzu.

### 14.10 Pagination-Token-Format

`PageRequest.pageToken` ist aus Sicht des Vertrags ein **opaker String**.
Phase-A-Default in den In-Memory-Stores: einfacher numerischer Offset
(`"0"`, `"2"`, `"4"`, ...). Das ist akzeptabel, weil:

- Der Token wird vom Server erzeugt und vom Client unveraendert
  zurueckgereicht (Client darf keine Struktur annehmen — siehe
  `docs/job-contract.md` §4 zu IDs)
- Die Phase-A-Stores sind In-Memory und brauchen keine
  Manipulationssicherheit

Verbindliche Regel fuer spaetere Adapter (Phase B/C):

- Sobald ein produktiver Store oder ein oeffentlicher Adapter (REST/MCP/
  gRPC) Pagination publiziert, **muss** der Token auf einer adapter-
  spezifischen Ebene gekapselt werden (Base64-codiert, signiert, oder
  HMAC-MAC), damit Clients nicht durch Token-Modifikation an fremde
  Daten kommen oder Cursor-Stabilitaet brechen koennen
- Der Kernvertrag (`PageRequest`/`PageResult`) bleibt unveraendert; die
  Kapselung ist Adapter-Verantwortung
- In-Memory-Stores duerfen weiterhin Offset-Tokens verwenden; Tests
  duerfen die Kapselung nicht annehmen
