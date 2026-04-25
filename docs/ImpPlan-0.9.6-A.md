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
- In-Memory-Implementierung nur fuer Unit-Tests bereitstellen
- Hash- und Range-Read-Hilfen definieren

Abnahme:

- Segmentwrites sind atomar
- abweichender Write auf gleiche Session/Position liefert deterministischen
  Konflikt
- Range-/Chunk-Reads funktionieren fuer finalisierte Artefakte
- TTL-/Abort-/Expiry-Cleanup entfernt unfertige Segmente
- file-backed Store besteht dieselben Contract-Tests wie In-Memory plus
  Tests fuer echte Dateien, atomare Writes und Filesystem-Cleanup

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

Abnahme:

- Start-Tool-Grants koennen ueber `idempotencyKey` validiert werden
- Sync-Tool-Grants koennen ueber `approvalKey` validiert werden
- Reuse mit anderem Payload wird abgewiesen
- rohe Tokens tauchen nicht in Store oder Audit auf
- abgelaufene Grants werden abgewiesen
- fehlende Scopes werden abgewiesen

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

Abnahme:

- gleiche Fehlerursache erzeugt denselben Code
- fehlende/malformed Eingaben liefern `VALIDATION_ERROR`
- wohlgeformte, aber unbekannte Ressourcen liefern `RESOURCE_NOT_FOUND`
- fremde Tenants liefern `TENANT_SCOPE_DENIED`
- jeder verbindliche Fehlercode hat einen deterministischen Mapper-Test

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
- Secret-Scrubbing fuer Audit-Felder definieren:
  - keine rohen Tokens
  - keine JDBC-Secrets
  - keine kompletten Uploadsegmente
  - keine ungefilterten SQL-/DDL-/Prozedurtexte
  - Approval-Tokens nur als ID oder Fingerprint
- AuditSink-Test-Doubles fuer Unit-Tests bereitstellen

Abnahme:

- `AUTH_REQUIRED`, `VALIDATION_ERROR`, `TENANT_SCOPE_DENIED`,
  `POLICY_REQUIRED`, `POLICY_DENIED` und `IDEMPOTENCY_CONFLICT` erzeugen
  Audit-Outcomes
- bei fehlendem Principal wird kein erfundener Principal auditiert
- gescrubbte Felder enthalten keine bekannten Secret-/Token-Marker
- identische Retries sind im Audit erkennbar, ohne Payloads im Klartext zu
  speichern

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
