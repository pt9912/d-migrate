# Implementierungsplan: 0.9.6 - Phase F `Datenoperationen und policy-pflichtige Uploads`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: F (`Datenoperationen und policy-pflichtige Uploads`)
> **Status**: Draft (2026-05-03)
> **Referenz**: `docs/planning/in-progress/implementation-plan-0.9.6.md`
> Abschnitt 4.5, Abschnitt 4.6, Abschnitt 5.4, Abschnitt 6.1a,
> Abschnitt 6.7, Abschnitt 6.8, Abschnitt 8 Phase F, Abschnitt 9.1,
> Abschnitt 9.2, Abschnitt 9.3, Abschnitt 9.4, Abschnitt 11 und
> Abschnitt 12; `docs/planning/done/ImpPlan-0.9.6-A.md`;
> `docs/planning/done/ImpPlan-0.9.6-B.md`;
> `docs/planning/in-progress/ImpPlan-0.9.6-C.md`;
> `docs/planning/open/ImpPlan-0.9.6-D.md`;
> `docs/planning/open/ImpPlan-0.9.6-E0.md`;
> `docs/planning/open/ImpPlan-0.9.6-E.md`; `spec/ki-mcp.md`;
> `spec/job-contract.md`; `spec/architecture.md`; `spec/design.md`;
> `spec/cli-spec.md`; `hexagon/application`; `hexagon/ports-common`;
> `hexagon/ports-read`; `hexagon/ports-write`; `hexagon/profiling`;
> `adapters/driven/streaming`; `adapters/driven/storage-file`;
> `adapters/driving/cli`; `adapters/driving/mcp`.

---

## 1. Ziel

Phase F fuehrt datenveraendernde MCP-Pfade und policy-pflichtige Uploads ein.
Sie erweitert den read-only Upload-Pfad aus Phase C um vollstaendige,
segmentierte und policy-gesteuerte Artefakt-Uploads und bindet Import und
Transfer als idempotente, policy-pflichtige Jobs an.

Phase F liefert konkrete technische Ergebnisse:

- policy-pflichtige `artifact_upload_init`-Varianten, insbesondere
  `uploadIntent=job_input`
- `artifact_upload` fuer Segmente bestehender Sessions
- `artifact_upload_abort` inklusive eigener aktiver Sessions und
  policy-pflichtiger administrativer/fremder Abbrueche
- session-scoped Upload-Berechtigung nach erfolgreichem Init
- SHA-256-Pruefung pro Segment und fuer das Gesamtartefakt
- atomare Segmentwrites in `UploadSegmentStore`
- immutable Finalisierung in `ArtifactContentStore`
- File-Spooling bzw. gleichwertiger produktnaher Byte-Store fuer grosse
  Uploads bis `200 MiB`
- TTL-, Abort- und Expiry-Cleanup gegen Segment- und Artefaktbytes
- `data_import_start` als idempotenter, policy-pflichtiger Job
- `data_transfer_start` als idempotenter, policy-pflichtiger Job
- Quotas fuer aktive Upload-Sessions, reservierte Upload-Bytes,
  gespeicherte Artefaktbytes und parallele Segmentwrites
- Upload-Finalisierungs-Timeouts
- Tests fuer Resume, Abort, Expiry, Hash-Fehler, Quotas, Import/Transfer und
  Policy-/Idempotency-Flows

Nach Phase F soll gelten:

- Upload-Init liefert vor dem ersten Segment eine serverseitige
  `uploadSessionId`, TTL und erwartete Startposition.
- Policy-pflichtige Init-Retries mit gleichem `approvalKey` und identischem
  Payload liefern dieselbe Session.
- Segmentuploads sind resumable, hash-validiert und tenant-/principal-
  gebunden.
- finalisierte Artefakte sind immutable und aus `ArtifactContentStore`
  lesbar.
- Import und Transfer starten nur mit Policy-Freigabe und deduplizieren ueber
  `idempotencyKey`.
- read-only gestagte Schemas koennen nicht implizit als `job_input` fuer
  Import, Transfer oder KI-Tools wiederverwendet werden.

Wichtig: Phase F implementiert keine KI-nahen Tools, Prompts oder allgemeine
Approval-UI. Sie nutzt die in Phase E eingefuehrten Idempotency-, Policy-,
Approval-, Job-, Cancel- und Audit-Vertraege.

---

## 2. Ausgangslage

### 2.1 Phase C liefert read-only Schema-Staging

Phase C erlaubt `artifact_upload_init(uploadIntent=schema_staging_readonly)`
fuer grosse neutrale Schemas. Dieser Pfad:

- braucht keine Write-Policy
- benoetigt trotzdem `dmigrate:read`, Quota, Audit, Byte-Store und
  Schema-Validierung
- materialisiert nur read-only nutzbare `schemaRef`s
- darf nicht implizit als Import-/Transfer-/KI-Eingabe dienen

Phase F erweitert Uploads um policy-pflichtige Intents. Der read-only Pfad
bleibt erhalten und darf durch Phase F nicht policy-pflichtig werden.

### 2.2 Phase E liefert Job, Idempotency und Policy

Phase F baut auf Phase E auf:

- `IdempotencyStore` und Start-Tool-Zustandsautomat
- `approvalKey` fuer synchrone Side Effects
- `ApprovalGrantStore`
- Policy-Service und Grant-Validierung
- Job-Start-Service
- `CancellationToken` und `job_cancel`
- Quotas, Timeouts und Audit

Phase F darf keine zweite Idempotency-, Policy-, Approval- oder Cancel-
Architektur einfuehren.

### 2.3 Bestehende Import-/Transfer-Runner sind CLI-gepraegt

Die relevanten Runner existieren bereits in `hexagon/application`:

- `DataImportRunner`
- `DataTransferRunner`
- `ImportStreamingInvoker`
- `TransferExecutor`
- Streaming- und Writer-Pfade unter `adapters/driven/streaming` und
  `hexagon/ports-write`

Phase F bindet diese Pfade als Jobs an. Zielauswahl erfolgt im MCP-Vertrag
ueber tenant-scoped Connection-Refs, nicht ueber implizite lokale CLI-
Konfiguration.

Die Runner duerfen im MCP-Pfad keine rohen CLI-Connection-Strings erhalten.
Phase F braucht dafuer eine explizite Adapter-Schicht:

- `ConnectionReferenceResolver` validiert tenant-scoped
  `targetConnectionRef`/`sourceConnectionRef` gegen Tenant, Principal,
  Sensitivitaet und erlaubte Scopes.
- `ConnectionSecretResolver` materialisiert aus `credentialRef` bzw.
  `providerRef` kurzlebige, nicht auditierte Connection-Secrets.
- `McpDataRunnerAdapter` uebergibt den bestehenden Runnern nur intern
  materialisierte Source-/Target-Strings und passende Resolver,
  URL-Parser und `ConnectionPoolFactory`; Tool-Payloads, Audit und
  Idempotency-Fingerprints enthalten weiter nur Connection-Refs.
- Secret-Materialisierung ist zeitlich auf die Job-Ausfuehrung begrenzt.
  Secrets duerfen waehrend des laufenden Jobs in kurzlebigen Pools/Handles
  leben, muessen bei Job-Ende, Cancel, Timeout oder Fehler geschlossen werden
  und werden nicht in Idempotency-, Approval-, Job-, Audit- oder
  Artifact-Stores persistiert.

---

## 3. Scope fuer Phase F

### 3.1 In Scope

- `artifact_upload_init` fuer policy-pflichtige Intents:
  - mindestens `uploadIntent=job_input`
  - optional weitere klar benannte Intents fuer Transfer/Transformation, falls
    der Masterplan sie im 0.9.6-Toolvertrag bereits vorsieht
- `artifact_upload`
- `artifact_upload_abort`
- session-scoped Upload-Berechtigung
- `UploadSession`-Zustandsautomat:
  - `ACTIVE`
  - `FINALIZING`
  - `COMPLETED`
  - `EXPIRED`
  - `ABORTED`
- `UploadSegmentStore` und `ArtifactContentStore` fuer produktnahe Bytes
- Upload-Session-TTL:
  - initial `900s`
  - absolute Max-Lease `3600s`
  - Idle-Timeout `300s`
- opake serverseitige `uploadSessionId`
- Segmentvalidierung:
  - `segmentIndex`
  - `segmentOffset`
  - `contentBase64`
  - `segmentSha256`
  - `checksumSha256`
- immutable Artefakt-Finalisierung
- Schema-Materialisierung fuer `artifactKind=schema`
- `data_import_start`
- `data_transfer_start`
- Import-/Transfer-Policy und Idempotency
- Import-/Transfer-Cancel-Propagation aus Phase E0/E
- Quotas und Timeouts fuer Upload und Datenoperationen
- Cleanup fuer TTL, Abort und Expiry
- MCP-Adapter- und Integrationstests fuer `stdio` und streambares HTTP

### 3.2 Nicht in Scope

- KI-nahe Tools:
  - `procedure_transform_plan`
  - `procedure_transform_execute`
  - `testdata_plan`
- MCP-Prompts
- Nicht-MCP-Binaerupload-Endpunkt
- freie SQL- oder JDBC-String-Payloads fuer Import/Transfer
- implizite Zielaufloesung aus lokaler CLI-Konfiguration
- Wiederverwendung von `schema_staging_readonly` als `job_input`
- produktive Cloud-Object-Storage-Implementierung, sofern File-Spooling den
  0.9.6-Vertrag bereits erfuellt
- allgemeine Approval-UI

---

## 4. Leitentscheidungen

### 4.1 Upload-Init ist der einzige Session-Erzeuger

`artifact_upload_init` erzeugt serverseitig eine neue `uploadSessionId` oder
liefert bei idempotentem Retry dieselbe Session. `artifact_upload` und
`artifact_upload_abort` referenzieren immer eine bestehende Session.

Clients duerfen keine `uploadSessionId` in Init vorschlagen. Resume eines
Init-Aufrufs laeuft ueber:

- `approvalKey` fuer policy-pflichtige Init-Pfade
- `clientRequestId` fuer read-only Schema-Staging, falls resumable

### 4.2 Policy-Freigabe bindet Session-Metadaten, nicht Segmentbytes

Der Approval-Fingerprint fuer policy-pflichtiges `artifact_upload_init` wird
aus Session-Metadaten gebildet:

- `artifactKind`
- `mimeType`
- `sizeBytes`
- `checksumSha256`
- `uploadIntent`
- Tenant
- Principal
- optionaler Zielkontext

Nicht Teil des Approval-Fingerprints:

- `contentBase64`
- `segmentSha256`
- `segmentIndex`
- `segmentOffset`
- einzelne Segmentbytes

Segmentdaten werden separat pro Segment validiert.

### 4.3 Upload-Berechtigung ist session-scoped

Ein erfolgreicher Init wandelt die Freigabe in eine serverseitige Upload-
Berechtigung um. Diese ist gebunden an:

- `uploadSessionId`
- Tenant
- Principal
- `artifactKind`
- `mimeType`
- `sizeBytes`
- `checksumSha256`
- Approval-Fingerprint
- Ablaufzeit

`artifact_upload` prueft diese Berechtigung statt pro Segment eine neue
Policy-Freigabe zu verlangen.

Das oeffentliche Init-Vertragsfeld bleibt fuer Phase F gemaess Masterplan und
`spec/ki-mcp.md` `sizeBytes`. Falls bestehende Phase-B/C-Handler oder Golden
Tests noch `expectedSizeBytes` verwenden, ist das ein Legacy-Alias, der in
Phase F auf `sizeBytes` migriert oder eindeutig als kompatibler Alias
getestet werden muss.

### 4.4 Segmentbytes werden ueber MCP-JSON-RPC transportiert

Fuer den d-migrate-MCP-Toolvertrag `v1` werden Segmentbytes in `stdio` und
streambarem HTTP als `contentBase64` im `tools/call`-JSON-RPC-Argument
uebertragen.

Nicht Teil von 0.9.6:

- separater binaerer HTTP-Body im MCP-Endpunkt
- Multipart-Upload ausserhalb des MCP-Standardtransports
- Base64-Hashing ueber den Text statt ueber decodierte Bytes

### 4.5 Import und Transfer sind policy-pflichtige Jobs

`data_import_start` und `data_transfer_start` nutzen dieselbe Job-,
Idempotency-, Policy-, Approval-, Quota-, Timeout-, Audit- und Cancel-
Architektur aus Phase E.

Verbindlich:

- `idempotencyKey` ist Pflicht
- fehlende Freigabe liefert `POLICY_REQUIRED`
- genehmigter Retry startet genau einen Job oder dedupliziert
- Import referenziert ein hochgeladenes Artefakt
- Import verlangt tenant-scoped `targetConnectionRef`
- Transfer verlangt tenant-scoped Source- und Target-Connection-Refs
- keine freien JDBC-Strings
- kein rohes SQL im Payload

---

## 5. Upload-Vertrag

### 5.1 `artifact_upload_init`

Pflichteingaben:

- `uploadIntent`
- `artifactKind`
- `mimeType`
- `sizeBytes`
- `checksumSha256`
- fuer policy-pflichtige Intents:
  - `approvalKey`
  - optional `approvalToken`
- fuer resumable read-only Staging optional:
  - `clientRequestId`

Antwort:

- `uploadSessionId`
- Uploadstatus
- erwarteter erster `segmentIndex`
- erwarteter erster `segmentOffset`
- `uploadSessionTtlSeconds`
- bei fehlender Policy-Freigabe:
  - `POLICY_REQUIRED`
  - `approvalRequestId`
  - Challenge-Metadaten
  - kein verwendbares `approvalToken`

Regeln:

- `uploadIntent=schema_staging_readonly` ist nur mit `artifactKind=schema`
  erlaubt.
- `uploadIntent=job_input` ist policy-pflichtig.
- `approvalKey` ist fuer policy-pflichtige Init-Pfade Pflicht.
- gleicher Tenant, Caller, Toolname, `approvalKey` und
  `payloadFingerprint` liefern dieselbe Session.
- gleicher Scope mit abweichendem Payload liefert `IDEMPOTENCY_CONFLICT`.
- `sizeBytes=0` ist nur fuer `uploadIntent=job_input` mit nicht-Schema-
  Artefakten erlaubt. Ein Null-Byte-Upload besteht aus genau einem finalen
  Segment mit `segmentIndex=1`, `segmentOffset=0`, `segmentTotal=1`,
  `isFinalSegment=true`, `contentBase64=""` und dem SHA-256 des leeren
  Byte-Arrays. Fuer `schema_staging_readonly` und `artifactKind=schema` ist
  `sizeBytes=0` ungueltig und liefert `VALIDATION_ERROR`, weil kein nutzbares
  Schema materialisiert werden kann.
- `sizeBytes` darf maximal `209715200` Bytes betragen.
- `checksumSha256` ist Pflicht und Teil des Init-Fingerprints.

Migrationsregel:

- `sizeBytes` ist das kanonische Wire-Feld in Masterplan, Spec, Schemas,
  Golden Tests und Beispielen fuer Phase F.
- Falls Phase F `expectedSizeBytes` als Legacy-Alias akzeptiert, ist der Alias
  additiv: `expectedSizeBytes` und `sizeBytes` duerfen nicht widersprechen; ein
  widersprechender Doppelwert liefert `VALIDATION_ERROR`.
- Bestehende Phase-B/C-Golden-Tests werden entweder auf `sizeBytes` migriert
  oder explizit als Legacy-Alias-Tests gekennzeichnet und um
  Alias-/Konfliktfaelle ergaenzt.

### 5.2 `artifact_upload`

Pflichteingaben:

- `uploadSessionId`
- `segmentIndex`
- `segmentOffset`
- `segmentTotal`
- `isFinalSegment`
- `contentBase64`
- `segmentSha256`
- optional `clientRequestId`

Validierungen:

- `uploadSessionId` syntaktisch gueltig
- Session existiert und gehoert zu Tenant/Principal oder erlaubtem Scope
- neue oder abweichende Segmentwrites sind nur fuer `ACTIVE` erlaubt
- finale/idempotente Retries duerfen auch `FINALIZING`, `COMPLETED` oder
  persistierte Failure-Outcomes sehen:
  - `FINALIZING` mit aktiver Lease liefert deterministischen In-Flight-Status
    oder vorhandenes persistiertes Ergebnis
  - `FINALIZING` mit abgelaufener Lease darf den Finalisierungsclaim reclaimen
  - `COMPLETED` replayt das persistierte erfolgreiche Ergebnis, sofern Segment
    und Fingerprint identisch sind
  - persistierte Fehler-Outcomes werden idempotent replayt, statt neue
    Side Effects zu starten
- session-scoped Upload-Berechtigung passt
- `segmentIndex` beginnt bei `1` und ist fortlaufend
- `segmentTotal` ist positiv, bleibt fuer die Session stabil und passt zur
  beim Init berechneten Segmentanzahl:
  `max(1, ceil(sizeBytes / maxUploadSegmentBytes))`
- `segmentIndex <= segmentTotal`
- `isFinalSegment == (segmentIndex == segmentTotal)`
- fuer nicht-finale Segmente gilt:
  `segmentOffset == (segmentIndex - 1) * maxUploadSegmentBytes`
- fuer finale Segmente gilt derselbe Offset aus der festen Segmentgroesse der
  Vorgaenger; zusammen mit `decodedSize` muss er `sizeBytes` exakt erreichen
- decodierte Segmentgroesse maximal `4194304` Bytes
- nicht-finale Segmente muessen exakt `maxUploadSegmentBytes` decodierte Bytes
  enthalten
- das finale Segment muss den Bytebereich exakt schliessen:
  `segmentOffset + decodedSize == sizeBytes`; es darf kleiner als
  `maxUploadSegmentBytes` sein und ist nur fuer `sizeBytes=0` leer
- `segmentSha256` ist SHA-256 ueber decodierte Bytes
- Gesamt-`checksumSha256` passt bei finalem Segment
- wiederholte Segmente muessen denselben `segmentTotal`- und
  `isFinalSegment`-Wert tragen wie der urspruengliche akzeptierte Write;
  abweichende Retries liefern `IDEMPOTENCY_CONFLICT` oder
  `VALIDATION_ERROR`, bevor Bytes neu geschrieben werden

Antwort:

- `uploadSessionId`
- Uploadstatus
- naechster erwarteter `segmentIndex`
- naechster erwarteter `segmentOffset`
- `uploadSessionTtlSeconds`
- bei finalem Segment:
  - Artefaktdaten
  - `resourceUri`
  - optional `schemaRef`, wenn `artifactKind=schema` gueltig materialisiert
    wurde

### 5.3 `artifact_upload_abort`

Eingabe:

- `uploadSessionId`
- optional `reason`
- bei administrativem/fremdem Abbruch:
  - `approvalKey`
  - optional `approvalToken`

Regeln:

- eigene aktive Session braucht keine Policy
- eigene aktive Session prueft Tenant, Principal, Owner und Status
- fremde oder administrative Abbrueche sind policy-gesteuert
- vor der Policy-Pruefung wird fuer fremde/administrative Abbrueche ein
  Eintrag im bestehenden SyncEffect-/Idempotency-Scope fuer
  `(tenant, caller, toolName, approvalKey)` reserviert; der
  `payloadFingerprint`-Reserve-Input ist der vollstaendige Abort-Fingerprint,
  nicht nur `uploadSessionId`. Existiert bereits
  `SyncEffectReserveOutcome.Existing(resultRef)`, wird `resultRef` in einem
  `AbortOutcomeStore` auf einen strukturierten Abort-Outcome-Record aufgeloest.
  Dieser Record enthaelt mindestens Abort-Fingerprint, `uploadSessionId`,
  Pre-Abort-Status, terminalen Status, Quota-Release-Status, Zeitpunkt und
  optionale Fehler-/Antwortdetails. Nur bei identischem Fingerprint wird das
  Outcome zurueckgegeben, ohne den Approval-Fingerprint aus dem aktuellen
  Sessionzustand neu zu berechnen. Abweichende Request-Felder wie `reason`,
  Caller oder Session-ID liefern `IDEMPOTENCY_CONFLICT`, `POLICY_REQUIRED`
  oder `POLICY_DENIED` je nach Store-/Policy-Zustand. Phase F fuehrt dafuer
  keine zweite Idempotency-Architektur und keinen separaten Abort-Claim-Key
  ein; der SyncEffect-Store bleibt ResultRef-basiert.
- der Approval-Fingerprint fuer fremde/administrative Abbrueche bindet den
  vorab genehmigten Pre-Abort-Zustand:
  - Toolname `artifact_upload_abort`
  - `uploadSessionId`
  - Session-Tenant
  - Session-Owner-Principal
  - Admin-/Caller-Principal
  - Pre-Abort-Session-Status
  - `artifactKind`
  - `uploadIntent`
  - Pre-Abort reservierte bzw. empfangene Bytes
  - optionalen `reason`
- ein Grant fuer einen fremden/administrativen Abbruch darf nur fuer denselben
  Pre-Abort-Fingerprint verwendet werden; andere Session, anderer Owner,
  anderer Pre-Abort-Status, anderer Caller oder anderer `reason` liefern
  `IDEMPOTENCY_CONFLICT`, `POLICY_REQUIRED` oder `POLICY_DENIED` je nach
  Store-/Policy-Zustand
- nach erfolgreichem Abort wird das terminale Abort-Outcome persistiert.
  Retry desselben genehmigten Abbruchs ist idempotent und liefert dieses
  Outcome nur bei identischem gespeicherten Abort-Fingerprint zurueck, auch
  wenn Session-Status oder Byte-Kontext durch Abort und Cleanup inzwischen
  veraendert wurden
- abgebrochene Sessions werden `ABORTED`
- Zwischensegmente werden verworfen oder als nicht finalisierte Spool-Dateien
  fuer Cleanup markiert
- finalisierte Artefakte werden nicht durch Abort geloescht

### 5.4 Session-ID und TTL

`uploadSessionId`:

- wird serverseitig erzeugt
- ist opak
- hat mindestens `128 Bit` Entropie
- nutzt sichtbare ASCII- bzw. URL-sichere Zeichen
- ist maximal `128` Zeichen lang

TTL-Regeln:

- initiale Lease: `900s`
- absolute Max-Lease: `3600s` ab Session-Erzeugung
- Idle-Timeout: `300s` ohne erfolgreiche Segmentannahme
- erfolgreiche Segmentannahme darf Lease erneuern
- Erneuerung darf Max-Lease nicht ueberschreiten
- `uploadSessionTtlSeconds` ist Restlaufzeit und darf gegen `0` laufen

### 5.5 Finalisierung

Bei finalem Segment:

- alle Segmente muessen vorhanden sein
- Gesamtgroesse muss der Session-Groesse entsprechen, die aus
  `sizeBytes` in `UploadSession.sizeBytes` uebernommen wurde
- Gesamt-SHA-256 muss `checksumSha256` entsprechen
- Finalisierung wechselt atomar von `ACTIVE` nach `FINALIZING` und setzt
  einen opaken `finalizingClaimId` mit Lease.
- Vor nicht idempotenten Side Effects darf nur ein Finalisierungsplan bzw.
  Claim persistiert werden, kein terminaler Erfolg.
- Nur der Inhaber des aktuellen `FINALIZING`-Claims darf Assemblierung,
  Validierung, Artefakt-/Schema-Materialisierung und Statuswechsel
  ausfuehren.
- Konkurrierende finale Segmente, die den Claim nicht erhalten, duerfen keine
  zweite Assemblierung oder Materialisierung starten; sie lesen entweder das
  persistierte Ergebnis oder liefern einen deterministischen In-Flight-
  Fehler.
- Eine abgelaufene `FINALIZING`-Lease darf durch eine neue finale Anfrage
  reclaimt werden; der Reclaim muss ein vorhandenes
  Finalisierungsplan-/Claim-Record wiederverwenden, statt ein zweites
  Artefakt zu erzeugen.
- Fuer `uploadIntent=job_input` wird das Artefakt immutable in
  `ArtifactContentStore` materialisiert und erst danach in `ArtifactStore`
  registriert.
- Fuer policy-pflichtige `job_input`-Artefakte werden dauerhafte
  Upload-Metadaten registriert. Phase F muss dafuer entweder
  `ArtifactRecord`/`ManagedArtifact` bzw. den Artifact-Store um strukturierte
  Metadaten erweitern oder einen tenant-scoped `ArtifactUploadMetadataStore`
  einfuehren. Mindestfelder:
  - `artifactId` / `resourceUri`
  - `uploadIntent`
  - `wireArtifactKind`
  - `mimeType` / `contentType`
  - optional abgeleitetes `format`
  - `sourceUploadSessionId`
  - `ownerPrincipalId` / Tenant
  - `policyFingerprint`
  - `sizeBytes` / `sha256`
  Diese Metadaten sind die verbindliche Grundlage fuer `data_import_start`;
  Tool-Responses oder fluechtige Session-Felder duerfen dafuer nicht die
  einzige Quelle sein.
- Fuer `uploadIntent=schema_staging_readonly` duerfen Rohbytes vor der
  Schema-Validierung nur als nicht publizierter Staging-/Finalisierungsinhalt
  existieren.
- Fuer `schema_staging_readonly` werden `ArtifactStore`-Metadaten,
  `artifactRef` und nutzbare `schemaRef` erst nach erfolgreicher
  Schema-Validierung veroeffentlicht.
- Terminale `finalizationOutcome`-Ergebnisse werden erst nach dauerhaftem
  Zustand persistiert: Erfolgs-Outcome nach durable Bytes plus notwendiger
  Metadaten-/Schema-Registrierung, Fehler-Outcome nach dauerhaftem
  ABORT-/Failure-Zustand und Quota-Refund.
- `mimeType` wird zu `contentType`
- `checksumSha256` wird zu `sha256`
- `artifactKind=schema` wird zusaetzlich validiert und in `SchemaStore`
  materialisiert, sofern gueltig

Ungueltige Schema-Artefakte liefern strukturiertes Validierungsergebnis ohne
Schema-Registrierung. Im read-only Staging erzeugen sie auch keine dauerhaft
nutzbare Rohartefakt-Registrierung; eventuell geschriebene Staging-Bytes
werden cleanup-faehig markiert oder entfernt. Das Rohartefakt darf nicht
implizit als gueltige `schemaRef` erscheinen.

---

## 6. Datenoperationen

### 6.1 `data_import_start`

Zweck:

- Import eines zuvor hochgeladenen Artefakts in eine Ziel-Connection als Job
  starten

Pflichteingaben:

- `artifactId` oder Artefakt-`resourceUri`
- tenant-scoped `targetConnectionRef`
- `idempotencyKey`
- Import-Optionen
- optional `approvalToken`

Regeln:

- immer policy-gesteuert
- `idempotencyKey` ist Pflicht
- `targetConnectionRef` ist Pflicht
- freie JDBC-Strings sind verboten
- Import-Artefakt muss policy-pflichtig als `uploadIntent=job_input`
  finalisiert worden sein
- read-only gestagte Schema-Artefakte sind nicht als Import-Eingabe erlaubt
  und werden hart mit `VALIDATION_ERROR` abgelehnt; Approval darf bestehende
  read-only Refs nicht implizit zu `job_input` upgraden
- Phase F richtet die Import-Eignung am aktuellen Core-Enum aus. Ohne
  Core-Migration gilt:
  - importierbare Datenartefakte werden als `ArtifactKind.UPLOAD_INPUT`
    persistiert und muessen zusaetzliche Upload-Metadaten
    `wireArtifactKind`/`uploadIntent` enthalten
  - `wireArtifactKind=seed-data` entspricht einem importierbaren
    Datenartefakt
  - `wireArtifactKind=generic` ist nur mit explizitem `format` importierbar
  - `ArtifactKind.SCHEMA`, `PROFILE`, `DIFF`, `DATA_EXPORT` und `OTHER` sind
    nicht automatisch importierbar
- Falls Phase F das Core-Enum stattdessen erweitert, muss dieser Schritt
  Masterplan, Spec, Stores, Golden Tests und Migrationen synchron auf
  `SEED_DATA`/`GENERIC` bzw. die finalen Namen bringen. Bis dahin duerfen
  Handler nicht auf nicht existierende Core-Enum-Werte wie `seed-data`
  pruefen.
- erlaubte Artefakt-/Format-Kombinationen sind:
  - `ArtifactKind.UPLOAD_INPUT`, `wireArtifactKind=seed-data`,
    `mimeType=application/json`, `format=json`
  - `ArtifactKind.UPLOAD_INPUT`, `wireArtifactKind=seed-data`,
    `mimeType=application/yaml` oder `text/yaml`, `format=yaml`
  - `ArtifactKind.UPLOAD_INPUT`, `wireArtifactKind=seed-data`,
    `mimeType=text/csv` oder `application/csv`, `format=csv`
  - `ArtifactKind.UPLOAD_INPUT`, `wireArtifactKind=generic` nur, wenn
    `format` explizit gesetzt ist und der `mimeType` eindeutig zu diesem
    Format passt
- Ohne explizit spezifiziertes Bundle-/Archivformat ist ein MCP-Upload-
  Import immer ein Single-File-/stdin-aequivalentes Artefakt und mappt auf
  `ImportInput.Stdin` bzw. serverseitig gespultes `ImportInput.SingleFile`.
  `ImportInput.Directory` und `tables` fuer Mehrtabellenimporte sind in Phase F
  nur erlaubt, wenn ein Bundle-Format mit MIME-Type, Manifest,
  Entpackung/Path-Sanitizing, Artefakt-Fingerprint und Cleanup explizit
  eingefuehrt wird. Solange dieses Bundle-Format nicht definiert ist, sind
  `tables` und Directory-/Mehrtabellen-Topologien fuer Upload-Importe
  `VALIDATION_ERROR`.
- `wireArtifactKind=schema`, `ddl`, `transform-script` und `rules` sind fuer
  `data_import_start` nicht als Datenquelle erlaubt; ebenso alle Core-Kinds
  ausser `UPLOAD_INPUT`. Sie liefern
  `VALIDATION_ERROR`
- `format` darf entfallen, wenn `mimeType` es eindeutig bestimmt; bei
  `wireArtifactKind=generic` oder mehrdeutigem `mimeType` ist `format` Pflicht
- `format` und `mimeType` muessen kompatibel sein; widerspruechliche
  Kombinationen liefern `VALIDATION_ERROR`
- unbekannte Artefakte liefern `RESOURCE_NOT_FOUND`
- fremde Tenant-Artefakte oder Ziel-Connections liefern
  `TENANT_SCOPE_DENIED`
- Cancel propagiert in Import-Runner und Streaming-/Writer-Pfade

Erlaubte Import-Optionen fuer Phase F:

- `format`: `json`, `yaml` oder `csv`, sofern nicht eindeutig aus dem
  Artefakt ableitbar
- `schemaRef`: optionale tenant-scoped Schema-Ref fuer Preflight und
  Tabellenreihenfolge
- `table`: optionaler Zieltabellen-Identifier fuer Single-File-/stdin-
  aequivalente Artefakte
- `tables`: nur fuer explizit definierte Bundle-/Directory-Artefakte; ohne
  Bundle-Format nicht erlaubt
- `onError`: `abort`, `skip` oder `log`
- `onConflict`: `abort`, `skip` oder `update`
- `triggerMode`: `fire`, `disable` oder `strict`
- `truncate`: Boolean, default `false`
- `disableFkChecks`: Boolean, default `false`
- `reseedSequences`: Boolean, default `true`
- `encoding`: optionaler Encoding-Name wie im CLI-Vertrag
- `csvNoHeader`: Boolean, default `false`
- `csvNullString`: String, default `""`
- `chunkSize`: positive Ganzzahl bis `10000`, default `10000`

Diese Optionsschicht ist eine strukturierte MCP-Abbildung der vorhandenen
`DataImportRequest`-/`ImportOptions`-Runner-Vertraege. Phase F fuehrt keine
neuen Mode-Werte wie `replace` oder `validate_only` ein. Destruktives Leeren
bleibt ausschliesslich `truncate=true`; es ist nicht atomar und muss im
Policy-Fingerprint, Audit und Approval-Challenge als destruktive Option
sichtbar sein.

`schemaRef` wird nicht in einen Client- oder CLI-Pfad aufgeloest. Phase F muss
dafuer einen `SchemaRefImportPreflightAdapter` bereitstellen:

- Schema wird tenant-/principal-geprueft aus `SchemaStore` geladen.
- Fuer Runner-Pfade, die weiter `DataImportRequest.schema: Path?` erwarten,
  wird entweder ein direkter In-Memory-/Domain-Adapter fuer
  `ImportPreflightResolver` gebaut oder ein serverseitiger, cleanup-faehiger
  Temp-Spool erzeugt.
- Temp-Spool-Pfade werden serverseitig erstellt, nie aus Tool-Payloads
  gelesen, nicht in Audit/Idempotency/Job-Response persistiert und bei Job-
  Ende, Cancel, Timeout oder Fehler entfernt.
- Wenn kein `schemaRef` gesetzt ist, bleibt der bisherige Runner-Preflight nur
  fuer Artefakte zulaessig, deren Format/Metadaten eine sichere
  Zieltabellen-Ableitung erlauben.

Tabellenoptionen folgen der bestehenden CLI-Semantik:

- `table` und `tables` sind gegenseitig exklusiv.
- Single-File- bzw. stdin-aequivalente Artefakte brauchen `table`, sofern
  kein `schemaRef` oder keine Artefaktmetadaten die Zieltabellen eindeutig
  bestimmen.
- Directory-/Mehrtabellen-Artefakte nutzen `tables` als optionale geordnete
  Filter-/Importliste; `table` ist dafuer ungueltig. Diese Topologie ist fuer
  Phase-F-Upload-Artefakte blockiert, solange kein Bundle-Format spezifiziert
  ist.
- `tables` darf nicht leer sein und alle Identifier muessen der bestehenden
  CLI-Identifier-Validierung entsprechen.

Nicht erlaubte Import-Optionen:

- rohe SQL-Statements
- JDBC-URLs oder Connection-Secrets
- Tabellen-/Schema-Identifier, die nicht der bestehenden CLI-Identifier-
  Validierung entsprechen
- `resume`, `checkpointDir`, lokale Pfade oder lokale CLI-Konfigurationspfade
- unbekannte Optionsfelder; sie liefern `VALIDATION_ERROR`

### 6.2 `data_transfer_start`

Zweck:

- Transfer zwischen zwei tenant-scoped Connection-Refs als Job starten

Pflichteingaben:

- `sourceConnectionRef`
- `targetConnectionRef`
- `idempotencyKey`
- Transfer-Optionen
- optional `approvalToken`

Regeln:

- immer policy-gesteuert
- beide Connection-Refs muessen tenant-scoped sein
- freie JDBC-Strings sind verboten
- kein implizites Ziel aus lokaler CLI-Konfiguration
- Policy bewertet Source, Target, Sensitivitaet, Produktionskennzeichen und
  Operation
- Cancel propagiert in Transfer-Runner, Source-Reader und Target-Writer

Erlaubte Transfer-Optionen fuer Phase F:

- `tables`: optionale Liste von Tabellen-Identifiern
- `filter`: optionale Filter-DSL nach bestehendem CLI-Vertrag; keine rohen
  SQL-Fragmente
- `sinceColumn`: optionaler Marker-Identifier, nur zusammen mit `since`
- `since`: optionale untere Marker-Grenze, nur zusammen mit `sinceColumn`
- `onConflict`: `abort`, `skip` oder `update`
- `triggerMode`: `fire`, `disable` oder `strict`
- `truncate`: Boolean, default `false`
- `chunkSize`: positive Ganzzahl bis `10000`, default `10000`

Diese Optionsschicht ist eine strukturierte MCP-Abbildung von
`DataTransferRequest` und `DataTransferRunner`. Phase F fuehrt keine neuen
Mode-Werte wie `copy`, `compare_only` oder `validate_only` ein. Destruktives
Leeren bleibt ausschliesslich `truncate=true`; es ist nicht atomar und muss im
Policy-Fingerprint, Audit und Approval-Challenge als destruktive Option
sichtbar sein.

Nicht erlaubte Transfer-Optionen:

- rohe SQL-Statements
- JDBC-URLs oder Connection-Secrets
- `filter`-Werte, die nicht der bestehenden Filter-DSL entsprechen
- Tabellen-/Marker-Identifier, die nicht der bestehenden CLI-Identifier-
  Validierung entsprechen
- lokale CLI-Konfigurationspfade
- unbekannte Optionsfelder; sie liefern `VALIDATION_ERROR`

### 6.3 Idempotency fuer Import und Transfer

Import und Transfer verwenden denselben Start-Tool-Vertrag aus Phase E:

- Store-Key `(tenantId, callerId, operation, idempotencyKey)`
- `payloadFingerprint` ohne `approvalToken`, `clientRequestId`, `requestId`
- normalisierte Import-/Transfer-Optionen sind Teil des
  `payloadFingerprint`
- erster Aufruf ohne Grant -> `AWAITING_APPROVAL` + `POLICY_REQUIRED`
- genehmigter Retry claimt Reservierung atomar
- `COMMITTED` liefert denselben Job
- abweichender Payload -> `IDEMPOTENCY_CONFLICT`

---

## 7. Quotas, Limits und Cleanup

### 7.1 Verbindliche Limits

- `maxNonUploadToolRequestBytes = 262144`
- `maxUploadToolRequestBytes = 6291456`
- `maxUploadSegmentBytes = 4194304`
- `maxArtifactUploadBytes = 209715200`

Zu grosse Payloads liefern:

- `PAYLOAD_TOO_LARGE` fuer Groessenlimits
- `VALIDATION_ERROR` fuer fehlende oder syntaktisch ungueltige Felder

### 7.2 Quotas

Phase F erzwingt Quotas fuer:

- aktive Upload-Sessions
- reservierte Upload-Bytes
- gespeicherte Artefaktbytes
- parallele Segmentwrites
- aktive Import-/Transfer-Jobs

Quota-Verletzungen liefern `RATE_LIMITED` mit strukturierten Details wie
`limitName`, `retryAfterSeconds`, Tenant-/Principal-Scope und ohne fremde
Ressourcendetails.

Quota-Lifecycle:

- Init reserviert genau eine aktive Upload-Session und `sizeBytes`
  reservierte Upload-Bytes im Tenant-/Principal-Scope.
- Segmentannahme darf keine zusaetzlichen Gesamtbytes reservieren; sie
  verbraucht nur parallele Segmentwrite-Slots fuer die Dauer des atomaren
  Writes.
- Erfolgreiche Finalisierung (`COMPLETED`) gibt aktive Session-Slots und
  reservierte Upload-Bytes frei. Fuer veroeffentlichte Artefakte werden
  gespeicherte Artefaktbytes einmalig auf Basis der finalen, validierten
  Artefaktgroesse gebucht.
- `ABORTED`, `EXPIRED`, fehlgeschlagene Finalisierung und expliziter Abort
  geben aktive Session-Slots, reservierte Upload-Bytes und Segmentwrite-Slots
  frei; nicht veroeffentlichte Segment-/Stagingbytes werden entfernt oder als
  cleanup-faehige Orphans markiert.
- `FINALIZING` haelt die aktive Session- und Upload-Byte-Reservierung, bis die
  Session nach `COMPLETED` oder `ABORTED` wechselt oder die Finalizing-Lease
  reclaimt wird.
- Retention-Cleanup fuer veroeffentlichte Artefakte gibt gespeicherte
  Artefaktbytes frei. Diese Freigabe darf reservierte Upload-Bytes nicht noch
  einmal veraendern.
- Wenn Phase F einen separaten `ArtifactUploadMetadataStore` nutzt, ist dessen
  Lifecycle an `ArtifactStore`/`ArtifactContentStore` gekoppelt:
  - Metadaten werden mit der erfolgreichen Artefaktregistrierung committed.
  - Retention-Cleanup loescht oder tombstoned Metadaten zusammen mit den
    Artefaktbytes.
  - `data_import_start` muss Metadaten ohne vorhandene Artefaktbytes bzw.
    Artefaktbytes ohne passende Metadaten deterministisch ablehnen.
- Alle Reserve-/Commit-/Refund-/Release-Schritte sind idempotent an Session
  bzw. Artefakt-ID gebunden, damit Retry und Crash-Recovery keine Quotas
  doppelt buchen oder doppelt freigeben.

### 7.3 Cleanup

Cleanup-Pfade:

- TTL-Ablauf
- Idle-Timeout
- Abort
- fehlgeschlagene Finalisierung
- Orphan-Spool-Dateien nach Crash/Restart

Cleanup muss Segmentbytes und nicht finalisierte Artefaktbytes aus
`UploadSegmentStore` bzw. `ArtifactContentStore` entfernen oder eindeutig als
orphaned markieren. Finalisierte Artefakte bleiben immutable bis zu ihrer
Retention. Upload-Metadaten fuer finalisierte Artefakte folgen derselben
Retention-/Tombstone-Entscheidung wie das Artefakt; dangling Metadaten ohne
Bytes sind nicht als importierbares Artefakt nutzbar.

---

## 8. Umsetzungsschritte

### 8.1 AP F.1: Phase-E-Vertraege pruefen

- Status von Phase E pruefen.
- Job-Start-Service, Idempotency, Policy, Approval, Quota, Timeout und Cancel
  als Abhaengigkeiten fixieren.
- Wenn Phase E nicht fertig ist, Phase F nur bis zu isolierten Upload-
  Bausteinen fortsetzen.

Tests/Gate:

- Phase-E-Start-Tool- und Policy-Tests sind gruen
- `job_cancel` und Cancel-Propagation fuer Import/Transfer-Pfade sind aus E0/E
  verfuegbar oder als blockierend dokumentiert

### 8.2 AP F.2: Upload-Session-Modell vervollstaendigen

- `UploadSession` um policy-pflichtige Init-Metadaten erweitern.
- session-scoped Upload-Berechtigung modellieren.
- TTL-/Idle-/Max-Lease-Felder abbilden.
- `FINALIZING` als transienten Single-Writer-Claim mit
  `finalizingClaimId`, `finalizingLeaseExpiresAt` und reclaim-faehigem
  Finalisierungsplan im Store abbilden.
- Terminales `finalizationOutcome` getrennt davon erst nach dauerhaftem
  Erfolgs- oder Fehlerzustand speichern.
- `uploadSessionId` serverseitig opak erzeugen und validieren.
- clientseitige Session-ID-Kandidaten in Init abweisen.

Tests:

- Entropie-/Zeichen-/Laengenvalidierung
- Init ohne clientseitige Session-ID erzeugt neue ID
- Retry mit gleichem `approvalKey` liefert dieselbe ID
- andere Metadaten mit gleichem Scope -> `IDEMPOTENCY_CONFLICT`
- erlaubte Transitionen enthalten `ACTIVE -> FINALIZING` und
  `FINALIZING -> COMPLETED/ABORTED`
- abgelaufener `FINALIZING`-Claim kann ohne doppelte Materialisierung
  reclaimed werden

### 8.3 AP F.3: Policy-pflichtiges `artifact_upload_init`

- `uploadIntent=job_input` implementieren.
- Approval-Fingerprint ueber Session-Metadaten bilden.
- `approvalKey` fuer policy-pflichtige Init-Pfade erzwingen.
- `sizeBytes` als kanonisches Vertragsfeld beibehalten und in
  `UploadSession.sizeBytes` uebernehmen.
- fehlende Freigabe -> `POLICY_REQUIRED`
- gueltiger Grant -> Session erzeugen und Upload-Berechtigung durable
  ausstellen.
- policy-pflichtiges Init nutzt den bestehenden
  `SyncEffectIdempotencyStore`: `reserve(scope, payloadFingerprint)` mit
  `scope=(tenant, caller, artifact_upload_init, approvalKey)`.
  `commit(scope, resultRef)` darf erst nach durablem Commit von
  `UploadSession`, session-scoped Upload-Berechtigung und Quota-Reservierung
  erfolgen. `resultRef` ist entweder die `uploadSessionId` selbst oder eine
  `UploadInitOutcomeStore`-Referenz, die `uploadSessionId`,
  `payloadFingerprint`, TTL, Quota-Reservierung und Antwortdaten enthaelt.
  Die Wahl muss in Phase F festgelegt und getestet werden.
- Crash vor SyncEffect-Commit darf beim Retry keine zweite Session erzeugen:
  Retry muss entweder den durable Session-/Outcome-Record finden und den
  SyncEffect-Eintrag nachcommitten oder einen deterministischen Konflikt
  liefern, aber nie eine zweite Session fuer denselben
  `(approvalKey, payloadFingerprint)` erzeugen.
- read-only `schema_staging_readonly` unveraendert ohne Write-Policy lassen.

Tests:

- policy-pflichtiges Init ohne `approvalKey` -> `VALIDATION_ERROR`
- policy-pflichtiges Init ohne Grant -> `POLICY_REQUIRED`
- genehmigter Init erzeugt Session
- Retry erzeugt keine zweite Session
- Retry nach Crash zwischen Session-Commit und SyncEffect-Commit replayt
  dieselbe `uploadSessionId` oder liefert deterministischen Konflikt, aber
  erzeugt keine zweite Session
- `SyncEffectReserveOutcome.Existing(resultRef)` replayt dieselben Init-
  Antwortdaten ueber `uploadSessionId` oder `UploadInitOutcomeStore`
- `schema_staging_readonly` mit anderem `artifactKind` -> `VALIDATION_ERROR`
- Golden Tests, Schemas und Beispiele verwenden kanonisch `sizeBytes`
- optionaler `expectedSizeBytes`-Legacy-Alias wird nur additiv akzeptiert;
  widersprechende Doppelwerte liefern `VALIDATION_ERROR`

### 8.4 AP F.4: Segmentannahme und Hashvalidierung

- `artifact_upload` gegen bestehende Session verdrahten.
- Base64 decodieren.
- Segmentgroesse gegen `maxUploadSegmentBytes` pruefen.
- nicht-finale Segmentgroesse exakt gegen `maxUploadSegmentBytes` pruefen.
- finales Segment muss `segmentOffset + decodedSize == sizeBytes` erfuellen.
- `segmentSha256` ueber decodierte Bytes berechnen.
- `segmentIndex`, `segmentOffset`, `segmentTotal` und `isFinalSegment`
  fortlaufend validieren.
- feste Offset-Regel `segmentOffset == (segmentIndex - 1) *
  maxUploadSegmentBytes` fuer alle Segmente pinnen.
- `segmentTotal` pro Session stabil halten.
- `isFinalSegment` nur fuer `segmentIndex == segmentTotal` akzeptieren.
- Null-Byte-Upload als ein finales leeres Segment modellieren und validieren.
- atomare Segmentwrites in `UploadSegmentStore` nutzen.
- wiederholte identische Segmente idempotent behandeln.
- abweichende Wiederholung einschliesslich anderer Segment-Metadaten
  deterministisch ablehnen.

Tests:

- fehlendes `contentBase64`
- zu grosses Segment -> `PAYLOAD_TOO_LARGE`
- nicht-finales Segment mit zu kleiner Groesse -> `VALIDATION_ERROR`
- finales Segment schliesst Bytebereich nicht exakt -> `VALIDATION_ERROR`
- Segmentoffset weicht von der festen Segmentgroessen-Position ab ->
  `VALIDATION_ERROR`
- Hash ueber falsche Bytes -> `VALIDATION_ERROR`
- `sizeBytes=0` mit leerem finalem Segment und Empty-SHA fuer erlaubtes
  nicht-Schema-`job_input` erfolgreich
- `sizeBytes=0` fuer `schema_staging_readonly` oder `artifactKind=schema` ->
  `VALIDATION_ERROR`
- `segmentIndex=0`
- `segmentIndex > segmentTotal`
- `isFinalSegment` widerspricht `segmentIndex == segmentTotal`
- Retry mit abweichendem `segmentTotal`
- nicht fortlaufender Segmentindex
- gleicher Segmentindex mit anderem Inhalt
- parallele gleiche Writes konvergieren deterministisch

### 8.5 AP F.5: Finalisierung und Artefaktmaterialisierung

- finale Segmentannahme erkennt vollstaendigen Upload.
- finale Segmentannahme claimt atomar `FINALIZING`.
- Gesamtgroesse und `checksumSha256` pruefen.
- Finalisierungsplan/Claim vor nicht idempotenten Side Effects persistieren.
- Artefaktbytes immutable in `ArtifactContentStore` schreiben.
- Artefaktmetadaten fuer `job_input` erst nach erfolgreicher Byte- und
  Checksum-Validierung registrieren.
- persistente `ArtifactUploadMetadata` fuer `job_input` mit
  `uploadIntent`, `wireArtifactKind`, Format-/MIME-Informationen und
  Source-Session-ID registrieren.
- Artefaktmetadaten fuer `schema_staging_readonly` erst nach erfolgreicher
  Schema-Validierung veroeffentlichen.
- Schema-Artefakte validieren und ggf. `schemaRef` materialisieren.
- terminales `finalizationOutcome` erst nach durablem Erfolg oder durablem
  Fehlerzustand persistieren.
- ungueltige Schema-Artefakte liefern strukturiertes Validierungsergebnis
  ohne Schema- oder nutzbare Rohartefakt-Registrierung.

Tests:

- finaler Hash-Mismatch
- fehlendes Segment bei Finalisierung
- parallele finale Segmente erzeugen maximal einen `FINALIZING`-Claim und ein
  Artefakt
- Crash/Reclaim nach `FINALIZING` replayt `finalizationOutcome`
- finalisiertes Artefakt ist immutable
- Upload-Metadaten sind nach Finalisierung persistent aus dem Artifact-Store
  oder `ArtifactUploadMetadataStore` lesbar
- `artifact_chunk_get` liest aus `ArtifactContentStore`
- gueltiges Schema erzeugt `schemaRef`
- ungueltiges read-only Schema erzeugt keine `schemaRef` und keine nutzbare
  Rohartefakt-Registrierung
- 200-MiB-Upload nutzt File-Spooling oder gleichwertigen Byte-Store

### 8.6 AP F.6: Abort, Expiry und Cleanup

- `artifact_upload_abort` fuer eigene aktive Sessions implementieren.
- administrative/fremde Abbrueche mit eigenem Abort-Approval-Fingerprint aus
  Abschnitt 5.3 an Policy anbinden.
- terminales Abort-Outcome fuer administrative/fremde Abbrueche ueber
  `SyncEffectIdempotencyStore.commit(scope, resultRef)` persistieren; der
  `resultRef` verweist auf einen strukturierten `AbortOutcomeStore`-Record.
  Der terminale Erfolgs-`resultRef` darf erst committed werden, nachdem
  Session-Status `ABORTED`, Cleanup/Tombstone der Zwischenbytes und Quota-
  Release dauerhaft abgeschlossen sind. Vorher darf hoechstens ein
  nicht-terminaler In-Progress-Record existieren, der bei Retry weiterarbeitet
  oder deterministisch In-Flight meldet.
- Persistierte Abort-Outcomes mit dem vollstaendigen Abort-Fingerprint
  verknuepfen oder vor der Rueckgabe gegen den aktuellen Request-Fingerprint
  vergleichen.
- `UPLOAD_SESSION_ABORTED` und `UPLOAD_SESSION_EXPIRED` mappen.
- TTL- und Idle-Expiry implementieren.
- Cleanup gegen Segment- und Artefaktspools ausfuehren.
- Quota-Release fuer Abort, Expiry und fehlgeschlagene Finalisierung
  idempotent ausfuehren.

Tests:

- eigener Abort ohne Policy erfolgreich
- fremde Session ohne Owner-Recht -> `FORBIDDEN_PRINCIPAL`
- administrativer Abort ohne Freigabe -> `POLICY_REQUIRED`
- genehmigter administrativer Abort setzt fremde aktive Session auf
  `ABORTED`
- Retry desselben genehmigten administrativen Abbruchs ist idempotent
- Retry nach Cleanup liest persistiertes Abort-Outcome, statt den aktuellen
  Session-Status oder Byte-Kontext erneut in den Fingerprint zu rechnen
- Crash vor durablem `ABORTED`/Cleanup/Quota-Release committed keinen
  terminalen Erfolgs-`resultRef`; Retry setzt Abort fort oder meldet
  In-Flight
- Retry nach Cleanup mit anderem `reason` oder anderem
  Abort-Fingerprint-Material gibt nicht das alte Outcome zurueck
- `SyncEffectReserveOutcome.Existing(resultRef)` wird ueber
  `AbortOutcomeStore` aufgeloest; fehlender oder fingerprint-fremder Record
  liefert deterministischen Fehler
- gleicher `approvalKey` mit anderer `uploadSessionId` oder anderem `reason`
  -> `IDEMPOTENCY_CONFLICT` oder `POLICY_REQUIRED`
- Abort-Grant kann nicht fuer andere Session, anderen Owner, anderen Caller
  oder anderen Session-Status wiederverwendet werden
- administrative/fremde Abbrueche nutzen den bestehenden SyncEffect-Scope mit
  vollstaendigem Abort-`payloadFingerprint`, keinen separaten Abort-Claim-Key
- Abort einer bereits `COMPLETED` Session loescht kein Artefakt und liefert
  deterministischen Fehler oder unveraenderten Terminalstatus gemaess
  Handler-Vertrag
- Upload nach Abort -> `UPLOAD_SESSION_ABORTED`
- Upload nach Expiry -> `UPLOAD_SESSION_EXPIRED`
- Cleanup entfernt Zwischenbytes, aber keine finalisierten Artefakte
- Abort/Expiry/fehlgeschlagene Finalisierung geben aktive Session- und
  reservierte Upload-Byte-Quotas frei

### 8.7 AP F.7: `data_import_start`

- Tool-Schema finalisieren.
- erlaubte Import-Optionen aus Abschnitt 6.1 als Runner-nahe
  `DataImportRequest`-/`ImportOptions`-Abbildung im Schema pinnen.
- Import-Artefakt-Eignungsmatrix aus Abschnitt 6.1 im Handler pinnen.
- Core-ArtifactKind-Abbildung festlegen: entweder `UPLOAD_INPUT` plus
  `wireArtifactKind`/`uploadIntent`-Metadaten verwenden oder Core-Enum,
  Stores, Specs und Migrationen synchron erweitern.
- persistente Upload-Metadaten aus AP F.5 lesen und nicht aus transienten
  Upload-Session- oder Tool-Response-Daten ableiten.
- `artifactId` / Artefakt-`resourceUri` validieren.
- Artefaktbytes aus `ArtifactContentStore` in den Import-Pfad einspeisen.
  Phase F muss dafuer entweder einen `ArtifactImportSourceAdapter` fuer
  `ImportStreamingInvoker` bauen oder kontrolliertes Temp-/File-Spooling
  verwenden, das serverseitig erzeugt, tenant-scoped, cleanup-faehig und nicht
  im Tool-Payload sichtbar ist. Lokale Client-Pfade, CLI-Konfigurationspfade
  oder rohe Dateipfade aus Optionen bleiben verboten.
- `targetConnectionRef` erzwingen.
- `targetConnectionRef` ueber `ConnectionReferenceResolver` und
  `ConnectionSecretResolver` in einen runner-internen Pool/Connection-String
  materialisieren; keine CLI-Konfiguration und keine rohen JDBC-Strings aus
  dem Tool-Payload verwenden.
- `idempotencyKey` erzwingen.
- `schemaRef` ueber `SchemaStore` und `SchemaRefImportPreflightAdapter`
  materialisieren; keine lokalen Schema-Pfade aus Tool-Payloads verwenden.
- `table`/`tables`-Semantik aus Abschnitt 6.1 validieren.
- MCP-spezifischen Import-Fingerprint bilden: `artifactId`/`resourceUri`,
  Artefakt-`sha256`, persistente Upload-Metadaten, `targetConnectionRef`,
  optional `schemaRef`, normalisierte Import-Optionen, Tenant und Principal.
  Der Fingerprint darf keine serverseitigen Temp-/Spool-Pfade,
  materialisierten JDBC-URLs, Connection-Secrets oder lokalen CLI-Pfade
  enthalten und darf den bestehenden CLI-`ImportOptionsFingerprint` nicht
  unveraendert wiederverwenden.
- Policy-/Approval-Flow aus Phase E anbinden.
- Import-Runner als abbrechbaren Job starten und Cancel bis in
  Table-/Chunk-Grenzen, Writer-Sessions, Artefakt-/Schema-Temp-Spool-Cleanup
  und ConnectionPool-/Secret-Handle-Close durchreichen.
- read-only Staging-Artefakte als `job_input` abweisen.
- `truncate=true` als nicht-atomare destruktive Option in Policy, Audit und
  Approval-Challenge kennzeichnen.

Tests:

- fehlender `idempotencyKey`
- fehlender `targetConnectionRef`
- Artefakt-zu-Import-Adapter streamt aus `ArtifactContentStore` oder
  serverseitigem Temp-Spool, ohne lokale Pfade im Tool-Payload
- fehlende persistente Upload-Metadaten fuer Import-Artefakt ->
  `VALIDATION_ERROR` oder `RESOURCE_NOT_FOUND`
- `schemaRef` wird aus `SchemaStore` geladen oder serverseitig gespult, nicht
  aus lokalem Pfad
- `table` und `tables` gemeinsam -> `VALIDATION_ERROR`
- Single-File-Artefakt ohne ableitbare Zieltabelle und ohne `table` ->
  `VALIDATION_ERROR`
- Directory-/Mehrtabellen-Artefakt mit `table` -> `VALIDATION_ERROR`
- leere oder syntaktisch ungueltige `tables` -> `VALIDATION_ERROR`
- unbekanntes Import-Optionsfeld -> `VALIDATION_ERROR`
- rohes SQL oder JDBC-Secret in Import-Optionen -> `VALIDATION_ERROR`
- `wireArtifactKind=schema`, `ddl`, `transform-script` oder `rules` als
  Import-Artefakt -> `VALIDATION_ERROR`
- Core-Kind ausser `UPLOAD_INPUT` als Import-Artefakt ->
  `VALIDATION_ERROR`
- `wireArtifactKind=generic` ohne explizites `format` -> `VALIDATION_ERROR`
- `format` widerspricht `mimeType` -> `VALIDATION_ERROR`
- `targetConnectionRef` ohne aufloesbare ConnectionReference, Secret oder
  Principal-Berechtigung -> `RESOURCE_NOT_FOUND`, `TENANT_SCOPE_DENIED` oder
  `FORBIDDEN_PRINCIPAL`
- Runner erhaelt materialisierte interne Connection, waehrend Audit/Job/
  Idempotency nur die Connection-Ref enthalten
- Import-Fingerprint enthaelt Artefakt-Hash, `targetConnectionRef`,
  `schemaRef` und normalisierte Optionen, aber keine Temp-Pfade oder
  materialisierte Ziel-URL
- ungueltiges `onError`, `onConflict` oder `triggerMode` ->
  `VALIDATION_ERROR`
- `chunkSize=0` oder `chunkSize>10000` -> `VALIDATION_ERROR`
- neuer MCP-Mode wie `replace` oder `validate_only` ->
  `VALIDATION_ERROR`
- `truncate=true` erscheint im Policy-Fingerprint und Audit als destruktiv
- Import ohne Approval
- unbekanntes Artefakt
- read-only gestagtes Schema als Job-Input -> `VALIDATION_ERROR`
- genehmigter Retry startet genau einen Job
- abweichende Import-Option mit gleichem `idempotencyKey` ->
  `IDEMPOTENCY_CONFLICT`
- Cancel stoppt an der naechsten Table-/Chunk-Grenze weitere Writes, schliesst
  Writer-Session/Pool/Secret-Handles und raeumt Temp-Spools auf

### 8.8 AP F.8: `data_transfer_start`

- Tool-Schema finalisieren.
- erlaubte Transfer-Optionen aus Abschnitt 6.2 als Runner-nahe
  `DataTransferRequest`-Abbildung im Schema pinnen.
- Source-/Target-Connection-Refs tenant-scoped validieren.
- Source-/Target-Connection-Refs ueber `ConnectionReferenceResolver` und
  `ConnectionSecretResolver` in runner-interne Pools/Connection-Strings
  materialisieren; keine CLI-Konfiguration und keine rohen JDBC-Strings aus
  dem Tool-Payload verwenden.
- `idempotencyKey` erzwingen.
- freie JDBC-Strings abweisen.
- `filter` mit derselben Filter-DSL wie CLI-`data transfer` parsen und in eine
  kanonische, parametrisierte Form ueberfuehren; blanke oder ungueltige Filter
  liefern `VALIDATION_ERROR`.
- `sinceColumn` und `since` paarweise validieren: beide fehlen oder beide
  gesetzt. `sinceColumn` nutzt dieselbe Identifier-Validierung wie CLI,
  `since` wird wie im bestehenden Runner typisiert/normalisiert.
- MCP-spezifischen Transfer-Fingerprint bilden: `sourceConnectionRef`,
  `targetConnectionRef`, kanonische Filterform, normalisierte `since`-
  Optionen, weitere normalisierte Transfer-Optionen, Tenant und Principal.
  Der Fingerprint darf keine materialisierten JDBC-URLs, Connection-Secrets
  oder lokalen CLI-Pfade enthalten.
- Policy-/Approval-Flow aus Phase E anbinden.
- Transfer-Runner als abbrechbaren Job starten und Cancel bis in
  Table-/Chunk-Grenzen, Source-/Target-Reader/Writer-Sessions und
  ConnectionPool-/Secret-Handle-Close durchreichen.
- `truncate=true` als nicht-atomare destruktive Option in Policy, Audit und
  Approval-Challenge kennzeichnen.

Tests:

- fehlender `idempotencyKey`
- fehlende oder ungueltige Connection-Ref
- ConnectionRef ohne aufloesbare Secret-/Provider-Referenz oder Principal-
  Berechtigung -> `RESOURCE_NOT_FOUND`, `TENANT_SCOPE_DENIED` oder
  `FORBIDDEN_PRINCIPAL`
- Runner erhaelt materialisierte interne Connections, waehrend Audit/Job/
  Idempotency nur Connection-Refs enthalten
- freie JDBC-URL
- unbekanntes Transfer-Optionsfeld -> `VALIDATION_ERROR`
- rohes SQL oder Connection-Secret in Transfer-Optionen ->
  `VALIDATION_ERROR`
- Transfer-Fingerprint enthaelt Connection-Refs und normalisierte Optionen,
  aber keine materialisierten Source-/Target-URLs
- blanker oder ungueltiger `filter` -> `VALIDATION_ERROR`
- `filter`-Fingerprint nutzt kanonische DSL-Form, nicht rohe Eingabestrings
- `sinceColumn` ohne `since` oder `since` ohne `sinceColumn` ->
  `VALIDATION_ERROR`
- ungueltiger `sinceColumn`-Identifier -> `VALIDATION_ERROR`
- ungueltiges `onConflict` oder `triggerMode` -> `VALIDATION_ERROR`
- `chunkSize=0` oder `chunkSize>10000` -> `VALIDATION_ERROR`
- neuer MCP-Mode wie `copy`, `compare_only` oder `validate_only` ->
  `VALIDATION_ERROR`
- `truncate=true` erscheint im Policy-Fingerprint und Audit als destruktiv
- Transfer ohne Approval
- genehmigter Retry dedupliziert
- abweichende Transfer-Option mit gleichem `idempotencyKey` ->
  `IDEMPOTENCY_CONFLICT`
- Cancel stoppt an der naechsten Table-/Chunk-Grenze weitere Target-Writes und
  schliesst Source-/Target-Sessions, Pools und Secret-Handles

### 8.9 AP F.9: Quota, Timeout und Audit

- Upload-Session-, Byte- und Segmentwrite-Quotas anbinden.
- Reserve-/Commit-/Refund-/Release-Lifecycle fuer aktive Upload-Sessions,
  reservierte Upload-Bytes und gespeicherte Artefaktbytes implementieren.
- Import-/Transfer-Jobquotas anbinden.
- Upload-Finalisierungs-Timeout konfigurieren.
- Runner-Timeouts fuer Import/Transfer anbinden.
- Around-/Finally-Audit fuer Init, Segment, Abort, Import und Transfer
  vervollstaendigen.

Tests:

- aktive Session-Quota -> `RATE_LIMITED`
- Byte-Quota -> `RATE_LIMITED`
- `COMPLETED` bucht gespeicherte Artefaktbytes genau einmal und gibt
  reservierte Upload-Bytes frei
- Retention-Cleanup gibt gespeicherte Artefaktbytes genau einmal frei
- Segmentwrite-Quota -> `RATE_LIMITED`
- Upload-Finalisierungs-Timeout -> `OPERATION_TIMEOUT`
- Audit enthaelt keine rohen Uploadbytes oder Approval-Tokens

### 8.10 AP F.10: Dokumentation und Integrationstests

- `spec/mcp-server.md` und `spec/ki-mcp.md` fuer Phase F aktualisieren.
- CSV-Upload-MIME-Types `text/csv` und `application/csv` in der
  `spec/ki-mcp.md`-Allowlist, den Tool-Schemas, Beispielen und Golden Tests
  nachziehen, sofern CSV-Import-Artefakte in Phase F erlaubt bleiben.
- Upload-Limits in `capabilities_list.limits` pruefen.
- stdio-Integrationstest fuer mehrsegmentigen Upload.
- HTTP-Integrationstest fuer mehrsegmentigen Upload ueber `contentBase64`.
- Import-/Transfer-Integrationstests mit Fake-/Test-Drivers.
- Dokumentieren, dass separate binaere Nicht-MCP-Upload-Bodies nicht Teil von
  0.9.6 sind.

---

## 9. Fehler- und Security-Regeln

Verbindliche Fehler:

- `VALIDATION_ERROR` fuer ungueltige Felder, freie JDBC-Strings,
  falsche Intent-/Kind-Kombinationen, falsche Segmentreihenfolge oder
  Hash-Mismatch
- `PAYLOAD_TOO_LARGE` fuer zu grosse Upload-Requests, Segmente oder
  Gesamtartefakte
- `POLICY_REQUIRED` fuer fehlende Freigabe
- `POLICY_DENIED` fuer abgelehnte Reservierungen
- `IDEMPOTENCY_KEY_REQUIRED` fuer fehlenden Key bei Import/Transfer
- `IDEMPOTENCY_CONFLICT` fuer gleichen Scope mit anderem Payload
- `RESOURCE_NOT_FOUND` fuer unbekannte Artefakte oder unbekannte wohlgeformte
  Upload-Sessions im erlaubten Tenant
- `TENANT_SCOPE_DENIED` fuer fremde Tenant-Ressourcen
- `FORBIDDEN_PRINCIPAL` fuer fehlendes Owner-/Admin-Recht im erlaubten Tenant
- `RATE_LIMITED` fuer Quotas
- `OPERATION_TIMEOUT` fuer Finalisierungs- oder Runner-Timeout
- `UPLOAD_SESSION_ABORTED`
- `UPLOAD_SESSION_EXPIRED`

Security-Regeln:

- keine rohen Uploadbytes im Audit
- keine rohen Approval-Tokens in Stores, Audit oder Responses
- Upload-Berechtigung ist an Session, Tenant, Principal und Init-Fingerprint
  gebunden
- Connection-Refs sind tenant-scoped
- produktive/sensitive Connections brauchen Policy
- read-only Staging-Artefakte koennen nicht still als `job_input` genutzt
  werden
- Segmenthashes gelten ueber decodierte Bytes, nicht ueber Base64-Text

---

## 10. Teststrategie

Mindesttestklassen:

- Upload-Session-State-Machine-Tests
- Upload-Session-ID-Validierungstests
- Approval-Fingerprint-Tests fuer Upload-Init
- Sync-Effect-Idempotency-Tests fuer `approvalKey`
- `artifact_upload_init`-Handler-Tests
- `artifact_upload`-Handler-Tests
- `artifact_upload_abort`-Handler-Tests
- `UploadSegmentStore`-/`ArtifactContentStore`-Contract-Tests
- `FINALIZING`-Claim-/Reclaim- und Crash-Replay-Tests
- File-Spooling-/200-MiB-Smoke-Test
- `data_import_start`-Handler-Tests
- `data_transfer_start`-Handler-Tests
- ConnectionReference-/ConnectionSecretResolver-Adaptertests fuer Import und
  Transfer
- ArtifactContentStore-zu-ImportSource-Adaptertests
- SchemaStore-zu-ImportPreflight-Adaptertests fuer `schemaRef`
- ArtifactUploadMetadataStore- bzw. Artifact-Metadaten-Contract-Tests
- AbortOutcomeStore-/SyncEffect-resultRef-Tests
- Import-/Transfer-Runner-Cancel-Tests
- Quota-/Timeout-/Audit-Tests
- stdio- und HTTP-Integrationstests

Mindestfaelle:

- policy-pflichtiges Init ohne `approvalKey`
- Init ohne Gesamt-`checksumSha256`
- Init mit nicht erlaubtem MIME-Type
- Init-Retry mit gleichem `approvalKey`
- Init-Retry mit anderem Payload
- policy-pflichtiges Init-Retry nach Crash zwischen Session-Commit und
  SyncEffect-Commit
- Init mit kanonischem `sizeBytes`
- Init mit `sizeBytes=0` fuer erlaubtes nicht-Schema-`job_input`
- Init mit `sizeBytes=0` fuer `schema_staging_readonly` oder
  `artifactKind=schema` -> `VALIDATION_ERROR`
- optionaler Init-Alias `expectedSizeBytes` widerspricht `sizeBytes`
- ungueltige `segmentTotal`-/`isFinalSegment`-Kombination
- nicht-finales Segment ist kleiner als `maxUploadSegmentBytes`
- finales Segment schliesst Bytebereich nicht exakt
- Segmentoffset weicht von der festen Segmentgroessen-Position ab
- genehmigter administrativer Upload-Abbruch
- Abort-Approval-Reuse fuer andere Session, anderen Caller oder anderen
  `reason`
- Abort-Retry nach Cleanup nutzt persistiertes Abort-Outcome trotz geaendertem
  Session-Status/Byte-Kontext
- Abort einer finalisierten Session loescht kein Artefakt
- fehlendes `contentBase64`
- zu grosses Segment
- Segmenthash-Mismatch
- Upload nach Abort
- Upload nach Expiry
- Gesamtchecksumme falsch
- parallele finale Segmente materialisieren genau ein Artefakt
- finaler Retry gegen `FINALIZING`/`COMPLETED` replayt Ergebnis oder
  In-Flight-Status ohne neuen Segmentwrite
- finalisiertes `job_input` speichert `uploadIntent`, `wireArtifactKind`,
  MIME/Format und Source-Session-ID dauerhaft
- ungueltiges read-only Schema erzeugt keine nutzbare Rohartefakt-
  Registrierung
- read-only Staging als Import-Input
- Core-`ArtifactKind` ausser `UPLOAD_INPUT` als Import-Artefakt
- `wireArtifactKind=schema`, `ddl`, `transform-script` oder `rules` als
  Import-Artefakt
- `wireArtifactKind=generic` als Import-Artefakt ohne explizites `format`
- inkompatible `mimeType`-/`format`-Kombination
- ConnectionRef-Aufloesung ohne Secret/Provider, mit falschem Tenant oder
  ohne Principal-Berechtigung
- Import aus `ArtifactContentStore` ohne lokale Pfade
- `schemaRef`-Preflight ohne lokale Schema-Pfade
- `table`/`tables` gegenseitig exklusiv und topology-spezifisch validiert
- `tables` ohne explizit definiertes Bundle-/Directory-Artefakt ->
  `VALIDATION_ERROR`
- MCP-Import-Fingerprint enthaelt keine Temp-Pfade, materialisierte URLs oder
  Connection-Secrets
- MCP-Transfer-Fingerprint enthaelt keine materialisierten URLs oder
  Connection-Secrets
- Transfer-`filter` blank/ungueltig und kanonische Filterform im Fingerprint
- Transfer-`sinceColumn`/`since` nur paarweise gueltig
- ungueltige Import-Optionswerte `onError`/`onConflict`/`triggerMode`
- ungueltige Transfer-Optionswerte `onConflict`/`triggerMode`
- `chunkSize=0` und `chunkSize>10000` fuer Import und Transfer
- Import-Artefakt ohne persistente Upload-Metadaten
- Retention loescht/tombstoned Upload-Metadaten zusammen mit Artefaktbytes
- dangling Upload-Metadaten ohne Artefaktbytes sind nicht importierbar
- SyncEffect-`resultRef` zeigt auf Abort-Outcome mit abweichendem Fingerprint
- unbekannte Import-/Transfer-Optionsfelder
- rohe SQL-/JDBC-/Secret-Werte in Import-/Transfer-Optionen
- erfundene MCP-Mode-Werte statt Runner-naher Optionen
- `truncate=true` als destruktive, nicht-atomare Option im Fingerprint
- Import ohne Approval
- Transfer ohne Approval
- Import/Transfer ohne `idempotencyKey`
- Import/Transfer mit gleichem Key, aber anderem Payload
- Cancel stoppt Import/Transfer an Table-/Chunk-Grenzen und schliesst
  Writer-/Reader-Sessions, Pools, Secrets und Temp-Spools
- genehmigte parallele Retries erzeugen maximal einen Job
- Quota-Reserve-/Commit-/Refund-/Release- und Timeout-Faelle

---

## 11. Abnahmekriterien

- Policy-pflichtiges `artifact_upload_init` mit vollstaendigen
  Session-Metadaten, Gesamt-Checksumme und serverseitiger Session-Erzeugung
  ist implementiert.
- `artifact_upload_init` verwendet `sizeBytes` als kanonisches Vertragsfeld;
  optionale `expectedSizeBytes`-Legacy-Aliase brechen den bestehenden
  read-only Pfad nicht und widersprechende Doppelwerte werden abgelehnt.
- Upload-Init liefert vor dem ersten Segment `uploadSessionId`,
  `uploadSessionTtlSeconds`, erwarteten `segmentIndex` und erwarteten
  `segmentOffset`.
- Wiederholtes policy-pflichtiges Init mit gleichem `approvalKey` und
  identischem Payload liefert dieselbe Session.
- Policy-pflichtiges Init committed SyncEffect-`resultRef` erst nach durablem
  Session-/Upload-Berechtigungs-/Quota-Commit; Crash-Retry erzeugt keine
  zweite Session und replayt ueber `uploadSessionId` oder
  `UploadInitOutcomeStore`.
- Abweichender Payload mit gleichem `approvalKey`-Scope liefert
  `IDEMPOTENCY_CONFLICT`.
- Read-only Schema-Staging kann weiterhin grosse Schemas ohne Write-Policy zu
  einer nur read-only nutzbaren `schemaRef` materialisieren.
- Ungueltiges read-only Schema-Staging erzeugt weder `schemaRef` noch
  dauerhaft nutzbare Rohartefakt-Registrierung.
- Versuch, `schema_staging_readonly` als Import-/Transfer-/KI-Input zu nutzen,
  liefert `VALIDATION_ERROR`; Approval darf bestehende read-only Refs nicht
  implizit zu `job_input` upgraden.
- Upload-Resume mit wiederholten identischen Segmenten funktioniert.
- Hash-Abweichungen werden abgewiesen.
- Fehlendes oder zu grosses `contentBase64` wird mit `VALIDATION_ERROR` bzw.
  `PAYLOAD_TOO_LARGE` abgewiesen.
- Segmentwrites sind atomar; konkurrierende abweichende Writes fuer dieselbe
  Session/Position liefern deterministischen Fehler.
- `segmentTotal` bleibt pro Session stabil, `segmentIndex <= segmentTotal`
  wird erzwungen und `isFinalSegment` muss exakt
  `segmentIndex == segmentTotal` entsprechen.
- Nicht-finale Segmente sind exakt `maxUploadSegmentBytes` gross; nur das
  finale Segment darf kleiner sein und muss den Bytebereich exakt schliessen.
  Segmentoffsets folgen fuer alle Segmente der festen Position
  `(segmentIndex - 1) * maxUploadSegmentBytes`.
- `sizeBytes=0` ist nur fuer erlaubte nicht-Schema-`job_input`-Artefakte
  erlaubt und wird dort als ein finales leeres Segment mit Empty-SHA
  validiert; Schema-Staging und `artifactKind=schema` lehnen Null-Byte-
  Uploads ab.
- Finale Segmentverarbeitung nutzt `FINALIZING` als Single-Writer-Claim;
  parallele finale Segmente und Reclaim nach Lease-Ablauf erzeugen maximal ein
  Artefakt und replayen persistierte Finalisierungsergebnisse nur nach
  durablem terminalem `finalizationOutcome`.
- Neue Segmentwrites sind nur in `ACTIVE` erlaubt; finale/idempotente Retries
  gegen `FINALIZING`, `COMPLETED` oder persistierte Fehler-Outcomes replayen
  Ergebnis, In-Flight-Status oder reclaimen eine abgelaufene Lease ohne
  zweite Materialisierung.
- `artifact_chunk_get`, Import aus Artefakt und Resource-Chunk-Reads lesen
  aus `ArtifactContentStore`, nicht aus Tool-Response- oder Heap-Kopien.
- `data_import_start` nutzt einen Artefakt-zu-Import-Adapter, der
  Artefaktbytes aus `ArtifactContentStore` streamt oder serverseitig
  kontrolliert spoolt; der bestehende `DataImportRunner.source` erhaelt keine
  Client- oder Tool-Payload-Pfade.
- `schemaRef` fuer Import-Preflight wird ueber `SchemaStore` und einen
  `SchemaRefImportPreflightAdapter` materialisiert oder serverseitig
  cleanup-faehig gespult; der bestehende `DataImportRequest.schema` erhaelt
  keine Client- oder Tool-Payload-Pfade.
- `table` und `tables` uebernehmen die bestehende CLI-Semantik: gegenseitig
  exklusiv, `table` fuer Single-File-Artefakte ohne eindeutige Ableitung,
  `tables` fuer Directory-/Mehrtabellen-Artefakte.
- Mehrtabellen-Upload-Import ist ohne explizit spezifiziertes Bundle-/Archiv-
  Format nicht erlaubt; `tables` fuer normale einzelne Upload-Artefakte liefert
  `VALIDATION_ERROR`.
- `job_input`-Finalisierung speichert dauerhafte Upload-Metadaten
  (`uploadIntent`, `wireArtifactKind`, MIME/Format, Source-Session, Policy-
  und Byte-Fingerprint), und `data_import_start` validiert Import-Eignung nur
  gegen diese persistenten Metadaten.
- Retention/Cleanup haelt Upload-Metadaten und Artefaktbytes synchron:
  dangling Metadaten ohne Bytes oder Bytes ohne passende Metadaten werden nicht
  als importierbar akzeptiert.
- 200-MiB-Upload-Test nutzt File-Spooling oder gleichwertigen Byte-Store und
  belegt, dass keine RAM-only-Implementierung erforderlich ist.
- Quota-Verletzungen fuer aktive Sessions, Bytes oder parallele Segmentwrites
  liefern `RATE_LIMITED`.
- Aktive Session-Slots, reservierte Upload-Bytes und gespeicherte
  Artefaktbytes haben idempotente Reserve-/Commit-/Refund-/Release-Pfade fuer
  `COMPLETED`, `ABORTED`, `EXPIRED`, fehlgeschlagene Finalisierung und
  Retention-Cleanup.
- Finalisierte Artefakte sind immutable.
- `artifact_upload_abort` eigener aktiver Sessions funktioniert ohne Policy
  und prueft Owner/Sitzungsstatus.
- Administrative oder fremde Upload-Abbrueche brauchen Policy-Freigabe.
- Der Approval-Fingerprint fuer administrative/fremde Upload-Abbrueche bindet
  den persistierten Pre-Abort-Zustand aus Session, Tenant, Ziel-Owner,
  Admin-/Caller-Principal, Status, Intent, Kind, Byte-Kontext und `reason`;
  Grants sind nicht fuer andere Sessions oder veraenderte Payloads
  wiederverwendbar.
- Idempotente Abort-Retries lesen ein persistiertes terminales Abort-Outcome,
  bevor aktueller Session-Status oder durch Cleanup veraenderter Byte-Kontext
  in eine neue Policy-Bewertung einfliesst; das Outcome wird nur
  zurueckgegeben, wenn der gespeicherte Abort-Fingerprint zum aktuellen
  Request passt. Der bestehende `SyncEffectIdempotencyStore` speichert dabei
  nur `resultRef`; strukturierte Abort-Daten liegen in einem referenzierten
  `AbortOutcomeStore`-Record oder einer gleichwertigen Store-Erweiterung.
  Terminaler Erfolgs-`resultRef` wird erst nach durablem `ABORTED`, Cleanup
  und Quota-Release committed.
- `data_import_start` bindet hochgeladene Artefakte und
  `targetConnectionRef` an Policy und Idempotency.
- `data_import_start` akzeptiert nur `job_input`-Artefakte mit kompatibler
  Artefakt-/MIME-/Format-Kombination: aktuell `ArtifactKind.UPLOAD_INPUT`
  plus `wireArtifactKind=seed-data` mit JSON/YAML/CSV oder explizit
  formatierte `wireArtifactKind=generic`-Artefakte. Schema-, DDL-,
  Transform- und Rules-Artefakte sowie Core-Kinds ausser `UPLOAD_INPUT` werden
  als Import-Datenquelle abgelehnt, sofern das Core-Enum nicht synchron
  migriert wurde.
- `data_transfer_start` bindet Source-/Target-Connection-Refs an Policy und
  Idempotency.
- Import und Transfer materialisieren tenant-scoped Connection-Refs nur fuer
  die Dauer der Job-Ausfuehrung ueber
  einen `ConnectionReferenceResolver`/`ConnectionSecretResolver` und einen
  MCP-Runner-Adapter zu kurzlebigen runner-internen Connections; rohe
  JDBC-Strings oder lokale CLI-Konfiguration gelangen nicht aus Tool-Payloads
  in die Runner.
- Import- und Transfer-Optionen sind als strukturierte Allowlist-Schemas
  gepinnt und bilden die bestehenden Runner-Vertraege ab; unbekannte Felder,
  neue MCP-Mode-Werte, rohe SQL-Werte, JDBC-URLs und Secrets werden vor
  Policy-Claim mit `VALIDATION_ERROR` abgelehnt.
- MCP-spezifische Import-/Transfer-Fingerprints enthalten Artefakt-Refs,
  Artefakt-Hash, Connection-Refs, `schemaRef` und normalisierte Optionen, aber
  keine serverseitigen Temp-Pfade, materialisierten JDBC-URLs oder
  Connection-Secrets. Der CLI-Import-Fingerprint darf nicht unveraendert
  wiederverwendet werden.
- Transfer-`filter` wird mit derselben DSL wie die CLI geparst,
  blanke/ungueltige Filter werden abgelehnt, `sinceColumn`/`since` sind nur
  paarweise gueltig, und der Fingerprint nutzt die kanonische Filterform.
- `truncate=true` ist der einzige Phase-F-Mechanismus fuer destruktives Leeren
  von Zielen; er wird nicht als `replace` abstrahiert und erscheint sichtbar
  in Policy, Audit und Approval-Challenge.
- Import/Transfer-Retries mit gleichem Idempotency-Store-Key deduplizieren.
- Abweichender Import-/Transfer-Payload mit gleichem Store-Key liefert
  `IDEMPOTENCY_CONFLICT`.
- Import und Transfer koennen per `job_cancel` kontrolliert abgebrochen
  werden, pruefen Cancel an Table-/Chunk-Grenzen, starten danach keine
  weiteren Daten-Schreibabschnitte und schliessen Writer-/Reader-Sessions,
  Pools, Secret-Handles sowie Temp-Spools.
- Rohe Uploadbytes, Approval-Tokens und Connection-Secrets erscheinen nicht in
  Audit, Stores oder Tool-Responses.

---

## 12. Anschluss an Phase G

Phase G darf auf Phase F aufbauen, indem KI-nahe Tools, Prompts, Tests und
Dokumentation die bestehenden Upload-, Artifact-, Policy-, Approval-,
Idempotency-, Job- und Cancel-Vertraege wiederverwenden.

Phase G darf read-only gestagte Schemas oder `job_input`-Artefakte nicht ohne
explizite Policy- und Intent-Pruefung als KI-Input verwenden. Wenn KI-nahe
Tools zusaetzliche Upload- oder Provider-Artefakte brauchen, werden die in
Phase F eingefuehrten Vertraege erweitert.
