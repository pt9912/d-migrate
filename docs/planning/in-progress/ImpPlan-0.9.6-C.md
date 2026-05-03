# Implementierungsplan: 0.9.6 - Phase C `Read-only Tools`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: C (`Read-only Tools`)
> **Status**: In Arbeit (2026-05-02) — AP 6.1–6.20 abgeschlossen
> (Phase-C-Handler, zentrale Limits, Audit-Wiring, idempotenter Replay,
> CLI-Phase-C-Aktivierung); AP 6.21–6.24 offen für die volle
> "produktiv nutzbar"-Akzeptanz: File-backed Byte-Stores im
> CLI-Pfad (§4.1), streamingfähige Finalisierung, Output-Schema-Drift
> für `artifactRef`/`details` (§6.13), stdio+HTTP-Integrationstest-
> Suite (§7.3).
> **Referenz**: `docs/planning/implementation-plan-0.9.6.md` Abschnitt 1 bis 7,
> Abschnitt 8 Phase C, Abschnitt 9.1, Abschnitt 9.2, Abschnitt 9.3,
> Abschnitt 9.4, Abschnitt 11 und Abschnitt 12;
> `docs/planning/ImpPlan-0.9.6-A.md`; `docs/planning/ImpPlan-0.9.6-B.md`;
> `spec/ki-mcp.md`; `spec/job-contract.md`; `spec/architecture.md`;
> `spec/design.md`; `spec/cli-spec.md`; `hexagon/application`;
> `hexagon/ports-read`; `hexagon/ports-write`; `hexagon/profiling`;
> `adapters/driving/cli`; `adapters/driving/mcp`.

---

## 1. Ziel

Phase C macht die ersten produktiven MCP-Tools nutzbar, ohne datenveraendernde
Operationen, Policy-Grants oder langlaufende Start-Tools einzufuehren. Der
Schwerpunkt liegt auf read-only Schema-Arbeit, kontrollierten Inline-Limits
und einem kleinen read-only Upload-Subset fuer grosse Schema-Eingaenge.

Phase C liefert konkrete technische Ergebnisse:

- `capabilities_list`
- `schema_validate`
- `schema_generate`
- `schema_compare` fuer bereits materialisierte `schemaRef`-Eingaenge
- `job_status_get` fuer bestehende Job-Metadaten
- `artifact_chunk_get` fuer grosse Artefaktinhalte
- read-only Schema-Staging fuer grosse Schemas:
  - `artifact_upload_init(uploadIntent=schema_staging_readonly)`
  - `artifact_upload` fuer Segmente dieser Session
  - `artifact_upload_abort` fuer eigene aktive read-only Staging-Sessions
  - implizite Finalisierung beim komplettierenden Segment zu
    `schema_staging_readonly`-Artefakt und `schemaRef`, sofern das Schema
    gueltig ist
- Output-Limits fuer Tool-Responses
- Artefakt- und Resource-Fallback fuer groessere Ergebnisse
- strukturierte Findings, Notes und Generatorwarnungen
- Adapter- und Integrationstests fuer read-only Tool-Contracts

Nach Phase C soll gelten:

- kleine Schema-Validierungen, DDL-Generierungen und Schema-Vergleiche
  koennen inline beantwortet werden
- grosse Resultate werden nicht abgeschnitten, sondern als Artefakt oder
  Resource referenziert
- grosse Schema-Eingaenge koennen ohne Write-Policy in eine read-only
  `schemaRef` materialisiert werden
- `schema_compare` bleibt synchron und schemaRef-basiert
- connection-backed Vergleiche werden mit klarem Hinweis auf
  `schema_compare_start` abgewiesen
- keine Tool-Antwort verletzt die Inline-Limits
- Secrets, lokale Pfade und rohe Connection-Daten erscheinen nicht in
  Tool-Responses, Artifactsummaries oder Audit-Events

Wichtig: Phase C ist Teil des Lieferabschnitts `0.9.6-A - Read-only MCP
Core`. Sie fuehrt bewusst noch keine policy-pflichtigen Upload-Intents,
Start-Tools, Import-/Transfer-Tools, KI-Tools oder MCP-Prompts ein.

---

## 2. Ausgangslage

### 2.1 Phase A stellt Kernvertraege bereit

Phase A definiert die adapterneutralen Modelle, Stores und Services fuer:

- `PrincipalContext`
- `ServerResourceUri`
- Job- und Artefaktmetadaten
- `ArtifactContentStore`
- `UploadSessionStore`
- `UploadSegmentStore`
- `SchemaStore`
- Quotas, Rate Limits und Timeouts
- Error-Mapping
- Audit und Secret-Scrubbing

Phase C nutzt diese Bausteine. Sie darf keine MCP-spezifischen Job-,
Artefakt-, Upload- oder Schema-Kernmodelle einfuehren.

### 2.2 Phase B stellt MCP-Transport und Registries bereit

Phase B stellt bereit:

- `adapters:driving:mcp`
- `stdio` und streambares HTTP
- Initialize und Capabilities
- Principal-Ableitung
- Tool-Registry
- Resource-Registry
- JSON-Schema-Registry
- `resources/list` und `resources/templates/list`

Phase C haengt konkrete read-only Handler an diese Registries. Sie darf
keine zweite Transportlogik neben Phase B einfuehren.

Offene Punkte aus den Phase-B-Reviews uebernimmt Phase C nur, soweit sie
read-only Tool-Handler oder deren Runtime-Payloads betreffen:

- Phase-C-Handler revalidieren Tenant-, Principal- und Owner-Grenzen fuer jede
  gelesene Job-, Artefakt-, Upload- oder Schema-Projektion auch dann, wenn der
  Store bereits mit `principal.effectiveTenantId` abgefragt wurde.
- Freie Strings und dynamische Payload-Slots aus Phase-C-Outputs laufen vor der
  Serialisierung durch Secret-/Path-Scrubbing oder werden durch typisierte
  Output-Records begrenzt.
- `resources/list`, `resources/templates/list`, Discovery-Listen und
  Connection-Ref-Projektionen bleiben Phase D.
- Phase-B-Transportinvarianten wie "principal not bound" und Cursor-Decode-
  Fehlercodes werden in Phase C nicht umverdrahtet; Phase-C-Handler muessen
  trotzdem fail-closed bleiben und duerfen keine fremden Details leaken.

### 2.3 Bestehende Anwendungspfade muessen wiederverwendet werden

`d-migrate` besitzt bereits fachliche Pfade fuer Schema-Verarbeitung:

- Format-Reader fuer neutrale Schemas
- Schema-Validierung
- DDL-Generatoren
- Schema-/DDL-/Compare-nahe Hilfen
- Artefakt- und Output-Modelle aus CLI- und Application-Pfaden

Phase C mappt MCP-Payloads auf diese bestehenden Pfade. Fachlogik wird nicht
im MCP-Adapter nachgebaut.

---

## 3. Scope fuer Phase C

### 3.1 In Scope

- `capabilities_list`
  - Dialekte
  - Formate
  - Tools
  - Limits
  - Produktvertragsversion
  - `executionMeta`
- `schema_validate`
  - genau eine Quelle: `schemaRef` oder kleines Inline-`schema`
  - Format-/Strictness-Optionen
  - strukturierte Validierungsfindings
  - `PAYLOAD_TOO_LARGE` fuer zu grosse Inline-Schemas
- `schema_generate`
  - genau eine Quelle: `schemaRef` oder kleines Inline-`schema`
  - `targetDialect`
  - optionale DDL-Optionen
  - strukturierte Generatorwarnungen
  - Artefakt-Fallback fuer grosses DDL
- `schema_compare`
  - `left.schemaRef`
  - `right.schemaRef`
  - optionale Compare-Optionen
  - strukturierte Findings
  - Diff-Resource oder Artefakt-Fallback fuer grosse Ergebnisse
  - explizite Ablehnung von `connectionRef`
- `job_status_get`
  - `jobId` oder `resourceUri`
  - Owner-/Admin-/Tenant-Pruefung
  - terminale Jobs vor Retention
  - no-oracle `RESOURCE_NOT_FOUND` fuer unbekannte, abgelaufene und nicht
    autorisiert sichtbare Jobs
  - `TENANT_SCOPE_DENIED` nur fuer syntaktisch fremde Tenant-URIs ohne
    Existenzbestaetigung
- `artifact_chunk_get`
  - Chunk-Reads fuer grosse Artefakte
  - `nextChunkUri`
  - explizites Text-/Base64-Response-Schema
  - `maxArtifactChunkBytes=32768`
- read-only Schema-Staging:
  - `artifact_upload_init` nur fuer `uploadIntent=schema_staging_readonly`
  - Segmentannahme in `UploadSegmentStore`
  - File-Spooling
  - Segment- und Gesamt-SHA-256
  - TTL- und Abort-Cleanup
  - implizite Finalisierung beim komplettierenden `artifact_upload`-
    Segment
  - Finalisierung zu `schema_staging_readonly`-Artefakt nur fuer gueltige
    Schemas
  - Materialisierung einer read-only `schemaRef`
  - Validierungsfehler ohne Registrierung als `schemaRef` und ohne
    dauerhaft veroeffentlichtes Rohartefakt
- Output-Limits:
  - maximal `64 KiB` serialisierte Tool-Response-Nutzdaten
  - maximal `200` Findings inline
  - maximal `256 KiB` nicht-Upload-Tool-Request-Payload
  - maximal `32 KiB` Inline-Schema
  - maximal `32 KiB` Artefakt-Chunk-Rohdaten je `artifact_chunk_get`
  - Uploadsegmentgrenzen aus `capabilities_list`
- Audit fuer alle read-only Tools und read-only Upload-Subset-Pfade
- Quota-/Rate-Limit-Pruefung fuer Upload-Sessions, Segmentwrites und
  Artefaktbytes
- Adapter- und Integrationstests fuer `stdio` und HTTP

### 3.2 Bewusst nicht Teil von Phase C

- `schema_reverse_start`
- `schema_compare_start`
- `data_profile_start`
- `data_import_start`
- `data_transfer_start`
- `job_cancel`
- policy-pflichtige Upload-Intents wie `job_input`
- Approval-Grant-Aussteller
- produktive Policy-Flow-Implementierung
- Connection-backed synchroner `schema_compare`
- freie JDBC-Strings oder Connection-Secrets in Tool-Payloads
- rohe Datenexports als Chat-Inline-Daten
- KI-nahe Tools
- MCP-Prompts
- Resource-Subscriptions oder Push-Notifications

Praezisierung:

Phase C loest "welche read-only Schema- und Artefaktpfade sind ueber MCP
nutzbar?", nicht "wie werden datenveraendernde oder connection-backed
Operationen gestartet?".

---

## 4. Leitentscheidungen fuer Phase C

### 4.1 Read-only bedeutet nicht RAM-only

Auch read-only Schema-Staging kann grosse Dateien verarbeiten. Deshalb gilt:

- Uploadsegmente werden nicht dauerhaft im Heap gehalten
- `UploadSegmentStore` und `ArtifactContentStore` nutzen File-Spooling oder
  gleichwertige Byte-Stores
- Hashes werden ueber gespeicherte Bytes validiert
- Finalisierung setzt Segmentbytes streamingfaehig zusammen
- TTL- und Abort-Cleanup entfernen unfertige Sessions deterministisch

In-Memory-Byte-Stores bleiben nur fuer reine Unit-Tests erlaubt.

### 4.2 Inline ist der kleine Pfad, Artefakt ist der grosse Pfad

Tool-Responses duerfen nicht unkontrolliert Modellkontext fuellen.

Verbindliche Limits:

- `maxToolResponseBytes=65536`
- `maxNonUploadToolRequestBytes=262144`
- `maxInlineSchemaBytes=32768`
- `maxUploadToolRequestBytes=6291456`
- `maxUploadSegmentBytes=4194304`
- `maxArtifactChunkBytes=32768`
- `maxInlineFindings=200`
- `maxArtifactUploadBytes=209715200`

Wenn Ergebnisse groesser werden:

- Summary bleibt inline
- vollstaendiges Ergebnis wird als Artefakt oder Resource referenziert
- DDL, Diff oder Findings werden nicht still abgeschnitten
- `artifactId`, `resourceUri`, `nextChunkUri` oder aehnliche Referenzen
  sind maschinenlesbar

### 4.3 `schema_compare` bleibt synchron und schemaRef-basiert

Phase C implementiert nur den synchronen read-only Vergleich.

Erlaubt:

- `left.schemaRef`
- `right.schemaRef`
- optionale Compare-Optionen

Nicht erlaubt:

- `connectionRef` in `schema_compare`
- freie JDBC-Strings
- Secrets im Payload
- implizites Reverse vor Compare

Wenn ein Client `connectionRef` uebergibt, liefert `schema_compare`
`VALIDATION_ERROR` mit Hinweis auf `schema_compare_start`.

### 4.4 Read-only Schema-Staging braucht keine Approval-Policy

`uploadIntent=schema_staging_readonly` erzeugt nur ein read-only Schema-
Artefakt und eine `schemaRef`. Der Intent darf nicht als Job-Eingabe fuer
Import, Transfer oder andere Write-Pfade genutzt werden.

Verbindlich:

- keine Approval-Challenge fuer diesen Intent
- trotzdem Auth, Scope, Tenant, Quota, Audit und Byte-Store-Pruefung
- Session ist an Tenant, Principal, Intent, Hash, erwartete Groesse und
  Ablauf gebunden
- Finalisierung registriert nur gueltige neutrale Schemas als `schemaRef`
- ungueltige Schema-Artefakte bleiben als fehlgeschlagene Staging-
  Operation sichtbar, aber werden nicht als nutzbare `schemaRef`
  registriert

### 4.5 Findings bleiben maschinenlesbar

Validierungs-, Generator- und Compare-Hinweise duerfen nicht nur als Text
im `content` landen.

Findings enthalten mindestens:

- Code oder Kategorie
- Severity
- Pfad oder Objektbezug, sofern vorhanden
- kurze Message
- optionale Details

Bestehende Warnungen aus Validator, Generator und Compare-Pfaden werden auf
dieses Format gemappt.

### 4.6 Fachliche Toolfehler sind Tool-Results

Phase C nutzt das Wire-Mapping aus Phase B:

- unbekannte Toolnamen bleiben JSON-RPC-/MCP-Protokollfehler
- bekannte read-only Tools mit fachlichem Fehler liefern
  `tools/call`-Result mit `isError=true`
- `structuredContent.error.code` enthaelt stabile d-migrate Fehlercodes
- Resource-Request-Fehler bleiben JSON-RPC-Errors

Nutzer- oder Payloadfehler duerfen nicht als `INTERNAL_AGENT_ERROR`
erscheinen.

### 4.7 Runtime-Scrubbing fuer dynamische Outputs

Phase B schuetzt JSON-Schemas statisch vor verbotenen Property-Namen, kann aber
keine zur Laufzeit erzeugten Inhalte in `additionalProperties: true`-Slots
bereinigen. Phase C uebernimmt deshalb Runtime-Scrubbing fuer alle konkreten
read-only Handler.

Verbindlich:

- Handler bevorzugen typisierte Output-Records statt freier Maps.
- Wo freie Maps fachlich noetig bleiben, laeuft vor der Serialisierung ein
  `SecretScrubber`-/Forbidden-Key-Pass ueber Keys und Stringwerte.
- Betroffen sind insbesondere `capabilities_list`-Top-Level-Ergaenzungen,
  `job_status_get.output.progress`, Findings, Notes, Generatorwarnungen und
  Artefakt-/Fallback-Summaries.
- Scrubbing ersetzt oder entfernt rohe Secrets, expandierte Secret-URLs,
  lokale Pfade, ENV-Werte und verbotene Property-Namen wie `password`,
  `credentialRef`, `providerRef`, `secret` oder `token`.
- Audit nutzt denselben Scrubbing-Pfad oder strengere Projektionen, damit Wire
  und Audit keine widerspruechlichen Leaks erzeugen.

---

## 5. Zielarchitektur

### 5.1 Tool-Handler im MCP-Adapter

Phase C ergaenzt im MCP-Modul konkrete Handler fuer:

- `capabilities_list`
- `schema_validate`
- `schema_generate`
- `schema_compare`
- `job_status_get`
- `artifact_chunk_get`
- `artifact_upload_init` fuer `schema_staging_readonly`
- `artifact_upload` fuer read-only Staging-Segmente
- `artifact_upload_abort` fuer eigene read-only Staging-Sessions

Die Handler:

- validieren MCP-Payloads gegen die Phase-B-Schemas
- leiten `PrincipalContext` aus dem Transportkontext weiter
- pruefen Tenant- und Resource-Scope
- revalidieren Tenant-/Owner-Scope auf geladenen Datensaetzen, auch wenn der
  Store bereits tenant-scoped abgefragt wurde
- scrubben freie Strings und dynamische Output-Slots vor der Serialisierung
- nutzen Phase-A-Store- und Service-Vertraege
- delegieren fachliche Schema-Arbeit an bestehende Application-Pfade
- schneiden Output gemaess Limits zu
- schreiben Audit-Outcome auch bei fruehen Fehlern

### 5.2 Schema-Quellen

Read-only Schema-Tools akzeptieren genau eine Quelle.

Inline:

- kleines neutrales Schema im Tool-Payload
- maximal `maxInlineSchemaBytes`
- keine vorgelagerte Materialisierung erforderlich

Referenz:

- `schemaRef`
- tenant-scoped `dmigrate://.../schemas/...` oder aequivalente stabile
  Referenz aus `SchemaStore`
- Zugriff gegen `PrincipalContext` geprueft

Nicht erlaubt in Phase C:

- `connectionRef` als Quelle fuer `schema_validate`
- `connectionRef` als Quelle fuer synchrones `schema_compare`
- freie Dateipfade
- JDBC-URLs
- Secrets

### 5.3 Read-only Schema-Staging

Der read-only Staging-Flow:

1. Client ruft `artifact_upload_init` mit
   `uploadIntent=schema_staging_readonly`, erwarteter Groesse,
   Gesamt-`checksumSha256` und Metadaten auf.
2. Server prueft Auth, Scope, Tenant, Quotas und Request-Limits.
3. Server erzeugt eine opake `uploadSessionId` mit TTL.
4. Client sendet Segmente ueber `artifact_upload`.
5. Server validiert Segmentindex, Offset, Segmentgroesse und Segmenthash.
6. Server schreibt Segmentbytes in `UploadSegmentStore`.
7. Das Segment, das die erwartete Gesamtgroesse exakt komplettiert, ist das
   einzige Finalisierungssignal.
8. Der Server validiert beim komplettierenden `artifact_upload` den
   Gesamt-SHA-256 ueber gespeicherte Bytes.
9. Server materialisiert nur bei erfolgreicher Hashpruefung ein immutable
   Staging-Artefakt im `ArtifactContentStore`.
10. Server versucht Schema-Parsing und Validierung.
11. Bei gueltigem Schema wird eine read-only `schemaRef` registriert.
12. Bei ungueltigem Schema wird keine `schemaRef` registriert; der Fehler
    bleibt strukturiert sichtbar.

Es gibt in Phase C keinen separaten Finalize-Tool-Aufruf und kein optionales
`finalize=true`-Feld. Wiederholungen des komplettierenden Segments sind
idempotent: bei identischem Segment und bereits finalisierter Session liefert
`artifact_upload` dasselbe Finalisierungsergebnis inklusive `schemaRef` oder
denselben strukturierten Validierungsfehler.

Wenn Schema-Parsing oder Validierung fehlschlaegt, wird das hochgeladene
Rohartefakt nicht dauerhaft als normales Artefakt veroeffentlicht. Die
Session wechselt in `failed_validation`; gespeicherte Rohbytes und Segmente
werden nur fuer eine kurze, konfigurierbare Failure-Retention gehalten
(`stagingFailureRetention`, Default maximal 24 Stunden) und zaehlen bis zum
Cleanup gegen Storage-Quotas. Danach entfernt Cleanup Rohbytes, Segmente und
nicht referenzierte Staging-Metadaten deterministisch.

Der Flow darf keinen policy-pflichtigen `job_input` erzeugen.

### 5.4 Output- und Artefakt-Fallback

Jeder read-only Tool-Handler entscheidet nach der fachlichen Ausfuehrung:

- passt Summary plus strukturierter Inhalt unter `64 KiB`, darf inline
  geantwortet werden
- uebersteigt das Ergebnis das Limit, wird ein Artefakt geschrieben
- inline bleiben nur Summary, gekuerzte Findings und Referenzen
- `truncated=true` oder aequivalente Metadaten zeigen an, dass mehr Inhalt
  ueber Artefakt/Resource abrufbar ist

DDL- und Diff-Ergebnisse werden nie still abgeschnitten.

### 5.5 `artifact_chunk_get`

`artifact_chunk_get` ist ein d-migrate Tool fuer gezielten Artefaktabruf.

Verbindlich:

- Zugriff gegen Tenant und Principal pruefen
- Chunkgroesse auf `maxArtifactChunkBytes=32768` Rohbytes begrenzen
- `nextChunkUri` oder aehnliche Fortsetzungsreferenz liefern
- Textartefakte mit `encoding="text"` und Feld `text` liefern
- Binaerartefakte mit `encoding="base64"` und Feld `contentBase64`
  liefern
- `lengthBytes` beschreibt immer die dekodierte Rohdatenlaenge
- `sha256` beschreibt den Chunk ueber die dekodierten Rohdaten
- `nextChunkUri` ist `null`, wenn kein weiterer Chunk existiert
- `RESOURCE_NOT_FOUND` fuer fehlende, abgelaufene oder nicht autorisiert
  sichtbare Artefakte

Pflichtfelder der Antwort:

- `artifactId`
- `resourceUri`
- `chunkId`
- `offset`
- `lengthBytes`
- `contentType`
- `encoding` (`text` oder `base64`)
- genau eines von `text` oder `contentBase64`
- `sha256`
- `nextChunkUri`
- `executionMeta`

Binaerartefakte duerfen ueber `artifact_chunk_get` Base64 liefern. Das
Verbot aus dem Resource-Vertrag bezieht sich auf `resources/read`: dort wird
kein frei erfundenes Base64-in-JSON-Ersatzformat fuer binaere Artefakte
modelliert.

### 5.6 `job_status_get`

`job_status_get` ist read-only und liefert nur Metadaten.

Verbindlich:

- Eingabe ist `jobId` oder `resourceUri`
- Job muss im Tenant des Principals liegen oder explizit erlaubt sein
- terminale Jobs bleiben bis Retention sichtbar
- Fehlerpfad ist no-oracle: unbekannte, abgelaufene, nicht autorisiert
  sichtbare und fremde Jobs liefern nach aussen einheitlich
  `RESOURCE_NOT_FOUND`
- `TENANT_SCOPE_DENIED` wird nur fuer syntaktisch gueltige Resource-URIs
  genutzt, deren `tenantId` explizit ausserhalb der erlaubten Tenant-Menge
  des Principals liegt und bei denen keine konkrete fremde Job-Existenz
  bestaetigt wird
- Fehlerdetails leaken keine fremden Job-IDs oder Tenant-IDs

---

## 6. Arbeitspakete

### 6.1 Tool-Registry fuer Phase-C-Handler verdrahten

Aufgaben:

- Phase-B-Tool-Registry um konkrete Handler-Instanzen erweitern
- Scope-Klasse je read-only Tool pruefen
- nicht implementierte spaetere Tools weiter kontrolliert abweisen
- gemeinsame Testfixtures fuer `stdio` und HTTP nutzen

Akzeptanz:

- alle Phase-C-Tools sind ueber `tools/list` bzw. Registry sichtbar
- Aufrufe nutzen denselben Handler unabhaengig vom Transport
- unbekannte Toolnamen bleiben Protokollfehler

### 6.2 `capabilities_list` implementieren

Aufgaben:

- Dialekte und Formate aus registrierten Codecs/Generatoren ableiten
- verfuegbare Tools aus Tool-Registry ableiten
- Limits aus zentraler Konfiguration ausgeben
- Produktvertragsversion `v1` ausgeben
- `executionMeta` mit `requestId` liefern
- Secrets, lokale Pfade und interne Klassenamen ausblenden

Akzeptanz:

- Tool funktioniert ohne DB-Verbindung
- `limits` enthaelt alle numerischen Grenzen
- Antwort ist stabil genug fuer Client-Discovery

### 6.3 Schema-Quellen-Resolver bauen

Aufgaben:

- Inline-Schema validieren und Groessenlimit pruefen
- `schemaRef` gegen `SchemaStore` aufloesen
- Tenant-/Principal-Scope pruefen
- Fehler auf `VALIDATION_ERROR`, `PAYLOAD_TOO_LARGE`,
  `RESOURCE_NOT_FOUND` oder `TENANT_SCOPE_DENIED` mappen

Akzeptanz:

- genau eine Quelle ist erlaubt
- fehlende oder mehrere Quellen liefern `VALIDATION_ERROR`
- zu grosse Inline-Schemas liefern `PAYLOAD_TOO_LARGE`

### 6.4 `schema_validate` anbinden

Aufgaben:

- bestehende Validator-/Format-Reader-Pfade nutzen
- Findings strukturieren
- optionale Format-/Strictness-Optionen mappen
- Ergebnis unter Inline-Limit halten
- bei vielen Findings Artefakt-Fallback nutzen

Akzeptanz:

- gueltiges Schema liefert erfolgreiche Summary
- ungueltiges Schema liefert strukturiertes Validierungsergebnis
- keine Validator-Ausnahme wird roh an den Client gereicht

### 6.5 `schema_generate` anbinden

Aufgaben:

- bestehende DDL-Generatoren nutzen
- `targetDialect` validieren
- optionale DDL-Optionen mappen
- Generatorwarnungen als Notes oder Findings strukturieren
- grosse DDL als Artefakt schreiben

Akzeptanz:

- kleines Inline-Schema erzeugt DDL ohne Upload und ohne Policy
- `schemaRef` erzeugt DDL
- unbekannter Dialekt liefert `VALIDATION_ERROR`
- grosse DDL wird referenziert, nicht abgeschnitten

### 6.6 `schema_compare` anbinden

Aufgaben:

- nur `left.schemaRef` und `right.schemaRef` akzeptieren
- bestehende Compare-Pfade nutzen
- Compare-Optionen mappen
- Findings strukturieren
- grosse Diffs als Artefakt oder Resource referenzieren
- `connectionRef` mit Hinweis auf `schema_compare_start` ablehnen

Akzeptanz:

- zwei `schemaRef`-Eingaenge koennen synchron verglichen werden
- `connectionRef` liefert `VALIDATION_ERROR`
- Diff-Ergebnis verletzt keine Inline-Limits

### 6.6.5 `artifact_upload`-Umbenennung umsetzen (Phase-B-Vertragsdrift)

Aufgaben:

- `artifact_upload_chunk` und `artifact_upload_complete` aus
  `McpServerConfig.DEFAULT_SCOPE_MAPPING` durch ein einziges
  `artifact_upload` ersetzen (spec/ki-mcp.md, §3.1, §12.4)
- entsprechenden JSON-Schema-Eintrag in `PhaseBToolSchemas` zu einem
  einzigen `artifact_upload`-Schema konsolidieren
- `PhaseBRegistries`-Titel/Beschreibungen anpassen
- `tools/list`-Golden in `PhaseBToolSchemasGoldenTest` und
  vergleichbare Snapshot-Tests neu fixieren
- `ImpPlan-0.9.6-B.md` §11 (Tabelle Auth/Scopes) entsprechend
  korrigieren
- Phase-A-/Phase-B-Wire-Tests, die `artifact_upload_chunk` oder
  `artifact_upload_complete` namentlich erwarten, anpassen

Akzeptanz:

- `tools/list` enthaelt `artifact_upload` (singular)
- weder `artifact_upload_chunk` noch `artifact_upload_complete` werden
  von Server oder Tests vorausgesetzt
- Phase-B-Goldens reflektieren den neuen Tool-Vertrag
- AP 6.7 und AP 6.8 koennen Handler unter dem Phase-C-Vertragsnamen
  registrieren, ohne erneut Goldens anzufassen

Hinweis: Diese Umbenennung wird vor AP 6.7/6.8 in einem eigenen
Commit durchgefuehrt, damit Schema- und Golden-Aenderungen nicht mit
der Handlerlogik vermischt werden.

### 6.6.6 Spaltentiefe Findings fuer `schema_compare`

Aufgaben:

- `TableDiff`-Substruktur (`columnsAdded`, `columnsRemoved`,
  `columnsChanged`, `primaryKey`, `indicesAdded/Removed/Changed`,
  `constraintsAdded/Removed/Changed`, `metadata`) pro Property in
  Findings entfalten
- `ColumnDiff` mit `type`/`required`/`unique`/`default`/`references`
  in Einzel-Findings zerlegen
- Wire-Codes mit gemeinsamem `TABLE_…`-Praefix und gepunktetem Pfad
  (`tables.t1.columns.c1.type`) vergeben, damit Clients ohne
  Free-Form-Parsing filtern oder rollup koennen
- groben `TABLE_CHANGED`-Eintrag entfernen; jede Aenderung wird
  einzeln sichtbar
- Severity-Policy am Handler-KDoc dokumentieren (statt im Code zu
  verstreuen)

Severity-Policy:

| Aenderung | Severity | Begruendung |
| --- | --- | --- |
| Spalte hinzugefuegt | `info` | additiv, generell kompatibel |
| Spalte entfernt | `error` | breaking |
| Spaltentyp geaendert | `error` | inkompatible Lese-Semantik |
| `required` false→true | `error` | bestehende NULL-Zeilen blockieren |
| `required` true→false | `info` | Relaxation, non-breaking |
| `unique` false→true | `warning` | bestehende Duplikate blockieren |
| `unique` true→false | `info` | Relaxation |
| `default`-Aenderung | `warning` | App-Verhalten kann driften |
| `references`-Aenderung | `warning` | FK-Semantik aendert sich |
| Primary-Key-Aenderung | `error` | Identitaetsvertrag bricht |
| Index hinzugefuegt | `info` | additiv |
| Index entfernt/geaendert | `warning` | Query-Plan kann driften |
| Constraint hinzugefuegt | `warning` | bestehende Zeilen koennen blockieren |
| Constraint entfernt | `info` | Relaxation |
| Constraint geaendert | `warning` | semantischer Drift |
| Tabellen-Metadaten | `info` | non-breaking Hint |

Akzeptanz:

- `schema_compare`-Findings enthalten pro Property einen Eintrag,
  nicht eine grobe `TABLE_CHANGED`-Sammelmeldung
- der Wire-Code `TABLE_CHANGED` wird nicht mehr emittiert
- Severity-Policy ist im Handler-KDoc dokumentiert und durch Tests
  pro Code abgesichert
- non-table Kinds (View/Sequence/CustomType/Function/Procedure/
  Trigger) behalten ihre coarse `_ADDED/_REMOVED/_CHANGED`-Findings;
  Sub-Walking dieser Kinds ist nicht Teil von 6.6.6 und kann bei
  Bedarf separat nachgezogen werden

Bewusst ausgelassen:

- `details: { before, after }`-Wire-Field. Vor- und Nachzustand
  stehen weiter im `message`; ein zusaetzliches strukturiertes Feld
  waere ein cross-cutting Wire-Refactor und gehoert zu AP 6.13.

### 6.7 Read-only Schema-Staging-Init implementieren

Aufgaben:

- `artifact_upload_init` fuer
  `uploadIntent=schema_staging_readonly` freischalten
- alle anderen Upload-Intents in Phase C ablehnen
- Gesamtgroesse und Gesamt-`checksumSha256` verlangen
- Quotas fuer aktive Sessions und reservierte Bytes pruefen
- session-scoped Upload-Berechtigung erzeugen
- TTL setzen

Akzeptanz:

- read-only Staging-Session kann ohne Approval erzeugt werden
- fehlender Gesamt-Hash liefert `VALIDATION_ERROR`
- `job_input` und andere Intents bleiben nicht verfuegbar

### 6.8 Read-only Segmentannahme implementieren

Aufgaben:

- `artifact_upload` fuer aktive read-only Staging-Sessions implementieren
- Segmentindex, Offset, Laenge und Hash pruefen
- Base64-Inhalt decodieren
- Segmentbytes in `UploadSegmentStore` schreiben
- parallele abweichende Writes derselben Position verhindern
- Segment- und Byte-Quotas pruefen

Akzeptanz:

- Segmente koennen wiederaufnehmbar hochgeladen werden
- identisches Segment kann dedupliziert werden
- abweichendes Segment derselben Position liefert Konflikt oder
  Validierungsfehler
- Segmentlimit wird erzwungen

### 6.9 Finalisierung zu `schemaRef` implementieren

Aufgaben:

- Vollstaendigkeit der Segmente pruefen
- Gesamt-SHA-256 ueber gespeicherte Bytes validieren
- Artefakt nach erfolgreicher Hashpruefung immutable materialisieren
- Schema parsen und validieren
- gueltiges Schema im `SchemaStore` registrieren
- ungueltiges Schema nicht registrieren und kein dauerhaftes normales
  Rohartefakt veroeffentlichen
- invalidierte Staging-Rohbytes nach `stagingFailureRetention` cleanupen
- Cleanup fuer abgelaufene, abgebrochene oder validierungsfehlgeschlagene
  Sessions ausloesen

Akzeptanz:

- grosses gueltiges Schema erzeugt `schemaRef`
- ungueltiges Schema erzeugt strukturierte Fehler und keine `schemaRef`
- ungueltiges Schema hinterlaesst hoechstens temporaere Staging-Bytes bis
  zur Failure-Retention
- File-Spooling wird verwendet

### 6.10 `artifact_upload_abort` fuer eigene read-only Sessions

Aufgaben:

- eigene aktive read-only Staging-Session abbrechen
- Tenant, Principal, Session-Owner und Session-Status pruefen
- Segmente deterministisch cleanupen
- administrative oder fremde Abbrueche in Phase C ablehnen

Akzeptanz:

- eigener Abort funktioniert ohne Policy
- fremder Abort liefert `TENANT_SCOPE_DENIED` oder `FORBIDDEN_PRINCIPAL`
- abgebrochene Session nimmt keine Segmente mehr an

### 6.11 `artifact_chunk_get` implementieren

Aufgaben:

- Artefaktmetadaten und Bytes aufloesen
- Tenant-/Principal-Scope pruefen
- Range-/Chunk-Read aus `ArtifactContentStore` nutzen
- `nextChunkUri` setzen
- Text/Binary-Handling gemaess explizitem Tool-Response-Schema abbilden
- Chunk auf `maxArtifactChunkBytes=32768` Rohbytes begrenzen

Akzeptanz:

- grosses Artefakt kann in Chunks gelesen werden
- Text-Chunks liefern `encoding="text"` und `text`
- Binaer-Chunks liefern `encoding="base64"` und `contentBase64`
- fehlendes, abgelaufenes oder nicht autorisiert sichtbares Artefakt
  liefert `RESOURCE_NOT_FOUND`

### 6.12 `job_status_get` implementieren

Aufgaben:

- `jobId` oder `resourceUri` akzeptieren
- Job aus `JobStore` laden
- Owner-/Admin-/Tenant-Regeln pruefen
- no-oracle Fehlerpfad fuer unbekannte, abgelaufene und nicht autorisiert
  sichtbare Jobs anwenden
- terminale Jobs vor Retention darstellen
- Ergebnis als gekuerzte Job-Projektion liefern

Akzeptanz:

- eigener Jobstatus ist abrufbar
- fremder oder nicht autorisiert sichtbarer Job liefert nach aussen
  `RESOURCE_NOT_FOUND`, ohne Existenz zu bestaetigen
- syntaktisch fremde Tenant-URI kann `TENANT_SCOPE_DENIED` liefern, ohne
  konkrete Job-Existenz zu bestaetigen
- unbekannter Job liefert `RESOURCE_NOT_FOUND`

### 6.13 Output-Limits und Artefakt-Fallback zentralisieren

Aufgaben:

- gemeinsame Limit-Pruefung fuer read-only Tool-Responses bauen
- Findings auf `maxInlineFindings` begrenzen
- strukturiertes `details`-Wire-Feld fuer Diff-/Finding-Records einfuehren,
  mindestens `{ before, after }` fuer schema_compare-Aenderungen
- Backward-Compatibility-Regel festlegen: `message` bleibt lesbar und
  enthaelt weiterhin eine knappe Zusammenfassung; `details` ist die
  maschinenlesbare Ergaenzung fuer Clients
- JSON-Schema fuer `details` ergaenzen und Forbidden-Key-/Scrubbing-Regeln
  auf `details` anwenden
- grosse DDL-/Diff-/Finding-Resultate als Artefakt schreiben
- Summary, `artifactId`, `resourceUri`, `truncated` und
  `executionMeta` konsistent mappen

Akzeptanz:

- keine Phase-C-Tool-Antwort ueberschreitet `64 KiB`
- schema_compare-Findings koennen Vor- und Nachzustand strukturiert ueber
  `details.before` und `details.after` transportieren
- `details` wird wie `message` auf Secrets, lokale Pfade und verbotene Keys
  geprueft
- grosse Ergebnisse sind vollstaendig ueber Artefakte abrufbar
- gekuerzte Inline-Findings sind als gekuerzt markiert

### 6.14 Phase-C-Bootstrap-Verdrahtung

Aufgaben:

- `PhaseCWiring`-Bundle einfuehren, das die fachlichen Abhaengigkeiten
  fuer alle Phase-C-Handler buendelt (`UploadSessionStore`,
  `UploadSegmentStore`, `ArtifactStore`, `ArtifactContentStore`,
  `SchemaStore`, `JobStore`, `QuotaService`, `McpLimitsConfig`,
  `Clock`, optional `SchemaStagingFinalizer`-Override fuer Tests)
- `PhaseCRegistries.defaultToolRegistry(wiring)` implementieren, das
  die acht produktiven Handler instanziert und ueber
  `PhaseCRegistries.toolRegistry(handlerOverrides=...)` registriert:
  - `capabilities_list` → `CapabilitiesListReadOnlyHandler`
  - `schema_validate` → `SchemaValidateHandler`
  - `schema_generate` → `SchemaGenerateHandler`
  - `schema_compare` → `SchemaCompareHandler`
  - `artifact_chunk_get` → `ArtifactChunkGetHandler`
  - `artifact_upload_init` → `ArtifactUploadInitHandler`
  - `artifact_upload` → `ArtifactUploadHandler`
  - `artifact_upload_abort` → `ArtifactUploadAbortHandler`
  - `job_status_get` → `JobStatusGetHandler`
- `McpServerBootstrap.startHttp` und `startStdio` einen optionalen
  `phaseCWiring: PhaseCWiring?`-Parameter geben; wenn gesetzt wird
  die Phase-C-Registry verdrahtet, sonst bleibt die Phase-B-Default-
  Registry aktiv (Backwards-kompatibel fuer bestehende Tests)
- Integrationstest, der pinnt:
  - mit `phaseCWiring` ist jeder Phase-C-Tool aus §3.1 ueber
    `tools/list` sichtbar UND dispatcht NICHT auf
    `UnsupportedToolHandler`
  - ohne `phaseCWiring` bleibt das Phase-B-Verhalten unveraendert
    (nur `capabilities_list` ist fachlich aktiv)

Akzeptanz:

- alle in §3.1 gelisteten Phase-C-Tools sind in der produktiven
  Laufzeit (HTTP- und stdio-Bootstrap) ueber `tools/call`
  fachlich aktiv, sobald ein `PhaseCWiring` uebergeben wird
- `tools/list` reflektiert dieselbe Wahrheit fuer beide Transports
- der Phase-B-Default bleibt ohne Phase-C-Wiring kompatibel zu den
  Phase-B-Tests, damit die Umstellung keine bestehenden
  Smoke-/Integrationstests bricht

Hinweis: Diese Verdrahtung war ueber AP 6.1 bis AP 6.13 hinweg
bewusst deferiert worden — die Phase-C-Handler liefen in den
Unit-Tests, dispatchten aber zur Laufzeit auf
`UnsupportedToolHandler`, weil der Bootstrap die Phase-B-Registry
weiter benutzt hat. AP 6.14 schliesst diese Luecke und macht das
§10 DoD ("Adaptertests fuer stdio und HTTP decken alle Phase-C-Tools
ab") tatsaechlich erreichbar.

### 6.15 Coverage-Verifikation `:hexagon:core`

Aufgaben:

- Aktuellen `:hexagon:core:koverVerify`-Bericht auswerten (CI-Status:
  `89.84%`, Schwelle `90%`).
- Diff der Phase-C-bezogenen Aenderungen in `:hexagon:core` seit
  Phase-C-Start auflisten und gegen die unter Coverage stehenden
  Klassen halten.
- Root-Cause klaeren: Kommt der Drift aus den Phase-C-APs (z. B.
  ungetestete Zweige in einer hinzugefuegten/modifizierten Klasse)
  oder ist es Pre-existing (z. B. eine Klasse, die schon vor Phase
  C an der Schwelle stand und durch eine unrelated kleine Aenderung
  unter 90 % gefallen ist)?

Akzeptanz:

- `make docker-check MODULES=":hexagon:core"` laeuft mit
  `koverVerify` gruen.
- Diagnose-Notes (Root-Cause + getroffene Massnahme) sind im
  Commit-Message verankert.

Diagnose (Resolution):

- Lokaler kover-XML-Report (`make docker-check MODULES=":hexagon:core"`
  mit `koverXmlReport`): `LINE missed=11 covered=941` → **98.84 %**.
  Die Schwelle (90 %) ist also lokal komfortabel ueberschritten.
- Phase-C-Aenderungen in `:hexagon:core` summieren sich auf den
  Round-Trip `TenantScopeChecker.isReachable` (Commit 592c02b →
  3086efd). Net-Diff seit Phase-C-Start: `0` (siehe
  `git diff 592c02b^..3086efd -- hexagon/core/...`).
- Der CI-Befund (`89.84 %`) stammt aus einem `origin/develop`-Stand,
  der vor den lokalen Phase-C-Commits liegt; jene Commits sind beim
  Auftreten des Befunds noch nicht gepusht gewesen
  (`Ihr Branch ist N Commits vor origin/develop`).
- Folge: Der CI-Coverage-Drift ist **nicht** durch die Phase-C-APs
  verursacht. Sobald der lokale Branch nach `origin/develop` gepusht
  und CI auf den neuen Stand getriggert wird, sollte
  `:hexagon:core:koverVerify` an der lokal gemessenen Coverage von
  98.84 % gruen werden.
- Falls CI weiterhin unter 90 % bleibt: separater Coverage-AP
  `0.9.6-D Hexagon-Core Coverage-Audit` (nicht Bestandteil der
  Phase-C-Implementierung), der das CI-spezifische Test-Set
  inspiziert.

### 6.16 Defense-in-depth: schemaRef-Recheck und Segment-Offset

Aufgaben:

- `SchemaSourceResolver.resolveReference` revalidiert nach dem
  `SchemaStore.findById`-Hit das geladene `SchemaIndexEntry`:
  - `entry.tenantId == principal.effectiveTenantId`
  - `entry.resourceUri.tenantId == principal.effectiveTenantId`
  - `entry.resourceUri.id == requested schemaId`
  - bei einem Mismatch wird `RESOURCE_NOT_FOUND` geworfen (no-oracle),
    nicht `INTERNAL_AGENT_ERROR`
- `ArtifactUploadHandler.validateSegmentBudget` erweitert die Pruefung
  auf Offset-Konsistenz: nicht-finale Segmente muessen
  `segmentOffset == (segmentIndex - 1) * maxUploadSegmentBytes` haben;
  das finale Segment muss `segmentOffset == sum(prior segments.size)`
  haben (oder gleichwertig: `segmentOffset + decodedSize == session.sizeBytes`).
- Tests pinnen beide Pfade: gefakter Store mit driftendem Entry plus
  ein Segment mit absichtlich falschem Offset.

Akzeptanz:

- ein vom Store zurueckgegebener `SchemaIndexEntry`, dessen
  `tenantId`/`resourceUri` nicht zur Anfrage passt, kommt nicht durch
- ein Segment mit inkonsistentem `segmentOffset` wird mit
  `VALIDATION_ERROR(field=segmentOffset)` abgewiesen
- `RESOURCE_NOT_FOUND` bleibt no-oracle (keine Existenz wird leaken)

### 6.17 Runtime-Scrubbing in den verbleibenden Read-only Handlern

Aufgaben:

- `SchemaValidateHandler` laesst `message`, `path` und (kuenftig)
  `details` aller Findings durch `SecretScrubber.scrub`.
- `SchemaGenerateHandler` scrubbt Generator-`message`, `path`, `hint`
  und Skipped-Object-Felder.
- `JobStatusGetHandler` scrubbt `progress.phase`, alle
  `progress.numericValues`-Strings (sofern stringifiziert),
  `error.message` und `error.code`.
- Defense-in-depth-Tests fuer jeden Handler: ein Bearer-Token-Literal
  in einem Eingabewert taucht nicht im Response-Payload auf.

Akzeptanz:

- kein Phase-C-Handler emittiert dynamische Strings ungescrubbt
- `SecretScrubber.scrub` ist die einzige Quelle der Scrubbing-Wahrheit
  ueber alle Handler

### 6.18 Idempotenter Final-Segment-Replay

Aufgaben:

- `ArtifactUploadHandler` muss bei einem Replay des komplettierenden
  Segments dasselbe Finalisierungsergebnis (Erfolg: `schemaRef`;
  Fehler: derselbe strukturierte `VALIDATION_ERROR`) liefern wie
  beim Erstaufruf.
- Erforderliche Persistenz: das `schemaRef`-Ergebnis (oder die
  fehlerhafte Validierung) muss an der Session beobachtbar sein.
  Optionen: Zusatzfelder am `UploadSession`-Modell oder eine kleine
  `FinalisationOutcomeStore`-Tabelle, die die letzte Finalisierung
  pro `uploadSessionId` haelt.
- Replay-Pfad: bei `state == COMPLETED` und identischem `args`
  liefert der Handler die persistierte Outcome zurueck statt
  `IDEMPOTENCY_CONFLICT` zu werfen; bei abweichenden Args bleibt der
  Conflict-Pfad.
- Test-Update: der bisherige `IDEMPOTENCY_CONFLICT`-Test fuer Replay
  nach COMPLETED wird ersetzt durch einen, der die identische
  Antwort beim Replay pinnt.

Akzeptanz:

- Replay des komplettierenden Segments mit identischem
  `segmentSha256`/`contentBase64` liefert die gleiche `schemaRef`-
  Antwort
- Replay mit abweichendem Hash am gleichen Index liefert weiterhin
  `IDEMPOTENCY_CONFLICT`
- die vorhandene "session COMPLETED → IDEMPOTENCY_CONFLICT"-Semantik
  ist fuer ECHTE Conflicts (anderer Hash) erhalten

### 6.19 Zentrale Output- und Request-Limits

Aufgaben:

- `ResponseLimitEnforcer` (oder gleichwertige `dispatch`-interne
  Pruefung) einfuehren, der vor jedem Phase-C-Handler-Aufruf prueft:
  - serialisierte Request-Groesse <= `maxNonUploadToolRequestBytes`
    fuer Nicht-Upload-Tools
  - serialisierte Request-Groesse <= `maxUploadToolRequestBytes`
    fuer `artifact_upload`
- nach jedem Handler-Aufruf prueft der Enforcer:
  - Response-Bytes <= `maxToolResponseBytes`
  - bei Ueberschreitung: Schreiben des Payloads als Artefakt via
    `ArtifactSink` und Ersatz-Response mit Summary, `artifactRef`,
    `truncated=true` analog zu AP 6.5/6.6
- `SchemaValidateHandler` wechselt vom Inline-Findings-Cap auf den
  zentralen Pfad: bei Ueberschreitung schreibt der Enforcer das
  vollstaendige Findings-Set ins Artefakt; inline bleiben Summary +
  gekuerzte Findings + `truncated=true`.

Akzeptanz:

- keine Phase-C-Tool-Antwort ueberschreitet `maxToolResponseBytes`
- ein zu grosser Request liefert `PAYLOAD_TOO_LARGE` mit dem
  jeweiligen Limit-Wert in den Details
- `schema_validate` mit ueberlangem Findings-Set liefert eine
  Inline-Zusammenfassung plus `artifactRef`, anstatt nur zu kappen

### 6.20 Audit-Wiring fuer alle Phase-C-Tools

Aufgaben:

- `PhaseCWiring` um einen `AuditSink` erweitern (Bestand aus
  `:hexagon:application/audit`).
- `McpServiceImpl.dispatch` (oder ein vorgelagerter Wrapper) emittiert
  pro `tools/call` einen Audit-Event mit:
  - Tool-Name, Tenant, Principal, Outcome (success / validation /
    payload-too-large / tenant-scope-denied / quota / auth-required /
    unsupported-tool-operation / internal-error)
  - `requestId` zur Korrelation mit `executionMeta.requestId`
  - sanitisierten Resource-Refs (kein Payload-Roh-Inhalt)
- bestehende Audit-Konventionen aus Phase A wiederverwenden — kein
  neues Event-Schema erfinden.

Akzeptanz:

- jeder Phase-C-Tool-Aufruf in der produktiven Laufzeit erzeugt
  genau einen Audit-Event
- Validation/Payload/Tenant/Quota/Auth-Fehler erzeugen ebenfalls
  einen Audit-Event mit dem korrekten Outcome
- Audit-Events enthalten weder Klartext-Secrets noch komplette
  Payloads (Phase-A-Konventionen aus
  `:hexagon:application/audit/SecretScrubber.kt` greifen)

### 6.21 File-backed Byte-Stores im produktiven CLI-Pfad

Aufgaben:

- Zielbild: `mcp serve` darf im produktiven CLI-Pfad keine
  Uploadsegment- oder Artefaktbytes mehr in `InMemory*ByteStore`-
  Implementierungen halten. Nur handlernahe Unit-Tests duerfen die
  In-Memory-Testfixtures weiter verwenden.
- `:adapters:driving:cli` erhaelt eine Produktionsabhaengigkeit auf
  `:adapters:driven:storage-file`. Die aktuelle Testfixture-
  Abhaengigkeit auf `:hexagon:ports-common` bleibt nur fuer die
  ephemeren Metadata-Stores noetig; der Kommentar im Gradle-File wird
  entsprechend korrigiert, damit die CLI nicht mehr als vollstaendig
  in-memory dokumentiert ist.
- Das produktive CLI-Wiring bekommt einen neutralen Namen, z.B.
  `McpCliPhaseCWiring.phaseCWiring(stateDir: Path, ownsStateDir: Boolean, ...)`.
  `McpDevelopmentWiring.developmentPhaseCWiring` darf hoechstens als
  test-/dev-nahe Delegation oder Compatibility-Alias bestehen bleiben,
  darf aber nicht mehr der Produktions-Anker fuer `mcp serve` sein.
  Produktiver CLI-Code darf keine Testfixture-Defaults versehentlich
  hinter einem `Development`-Helper verstecken.
- Das CLI-Wiring wird mit expliziter State-Konfiguration aufgebaut.
  Darin werden:
  - `FileBackedUploadSegmentStore(stateDir)`
    fuer `UploadSegmentStore`
  - `FileBackedArtifactContentStore(stateDir)`
    fuer `ArtifactContentStore`
  verdrahtet. Die Adapter legen unter diesem Root selbst die
  kanonischen Unterverzeichnisse `segments` und `artifacts` an; der
  CLI-Plan darf keine zweiten sprechenden Unterordner vorschalten,
  damit das finale Layout stabil bei `<stateDir>/segments/...` und
  `<stateDir>/artifacts/...` bleibt. Artefakt-Sharding ist Teil dieses
  stabilen Layouts: `FileBackedArtifactContentStore` legt Artefakte
  unter `<stateDir>/artifacts/<sha256-prefix>/<artifactId>.bin` plus
  Sidecar ab; Cleanup- oder Diagnose-Code darf daher nicht von einem
  flachen `artifacts/*`-Layout ausgehen.
- State-Directory-Aufloesung in `McpCommands` zentralisieren:
  1. CLI-Option `--mcp-state-dir`
  2. Environment `DMIGRATE_MCP_STATE_DIR`
  3. Default `Files.createTempDirectory("dmigrate-mcp-")`
  Die CLI-Option gewinnt gegen die Environment-Variable. Relative
  Pfade werden gegen das aktuelle Arbeitsverzeichnis normalisiert.
- Im erfolgreichen Startpfad wird einmal auf stderr ausgegeben:
  - effektiver State-Dir-Pfad
  - ob der Pfad operator-supplied oder CLI-owned temporary ist
  - der Hinweis `metadata is ephemeral; only byte content is file-backed`
  Fehler-, Shutdown- und Cleanup-Diagnosen duerfen zusaetzlich eigene
  stderr-Zeilen schreiben; die Start-State-Zusammenfassung selbst soll
  nicht mehrfach erscheinen. Secrets oder lokale Eingabepfade aus
  Tool-Payloads duerfen dabei nicht geloggt werden.
- Directory-Vorbedingungen fail-fast pruefen: Pfad existiert oder kann
  angelegt werden, ist ein Verzeichnis, ist lesbar und beschreibbar.
  Fehler werden als CLI-Konfigurationsfehler mit Exit 2 gemeldet, bevor
  HTTP-Port oder stdio-Loop gestartet werden.
- Operator-supplied State-Dirs sind exklusive Laufzeit-Workdirs. Die
  CLI oeffnet beim Start `<stateDir>/.lock`, schreibt eine Diagnose-
  Payload (PID, Startzeit, random UUID, Version) hinein und haelt
  darauf ein OS-gestuetztes Advisory-Lock via `FileChannel.tryLock()`
  bis zum Stop. Das Lock darf nicht als reine Lockfile-Existenzpruefung
  implementiert werden, weil Crashs sonst den State-Dir dauerhaft
  blockieren koennten; das Betriebssystem muss das Lock beim
  Prozessende freigeben. Ein zweiter `mcp serve` mit demselben
  State-Dir scheitert vor Serverstart mit Exit 2 und klarer stderr-
  Diagnose inklusive der aus dem Lockfile gelesenen PID/UUID, soweit
  die Payload gueltig lesbar ist. Kaputte, teilgeschriebene oder
  unbekannt formatierte Lockfile-Payloads duerfen die Diagnose
  degradieren, aber nicht die Lock-Entscheidung ersetzen: bei aktivem
  Advisory-Lock bleibt es Exit 2, ohne aktives Advisory-Lock darf der
  naechste Start die Payload ueberschreiben. Stale Lockfiles ohne
  aktives Advisory-Lock duerfen beim naechsten erfolgreichen Start
  ueberschrieben werden. Ohne dieses Single-Writer-Lock waeren die
  ephemeren Metadata-Stores pro Prozess, die Byte-Dateien aber
  gemeinsam, was Segment-/Artefakt-Zuordnungen unzuverlaessig machen
  wuerde.
- Shutdown-/Lifecycle-Regel:
  - CLI-owned Tempdir wird ueber einen idempotenten Owner verwaltet,
    der bei normalem Ende, SIGINT/JVM-Shutdown und explizitem
    programmgesteuertem `McpServerHandle.stop()` best-effort rekursiv
    loescht.
  - Wenn nach `Files.createTempDirectory("dmigrate-mcp-")` ein
    Startfehler passiert (Directory-Validierung, Lock, Sweep,
    Bootstrap-Konfiguration oder Transportstart), muss derselbe Owner
    im Fehlerpfad best-effort aufraeumen, bevor Exit 2 zurueckgegeben
    wird. Tempdirs duerfen nicht erst nach erfolgreichem Serverstart
    registriert werden.
  - Die Loeschung darf nicht davon abhaengen, dass
    `awaitTermination()` durch `stop()` geweckt wird; der CLI-Adapter
    muss Cleanup entweder um das Handle wrappen oder in einer
    `try/finally`/Shutdown-Hook-Kombination ausloesen, die auch fuer
    HTTP-Handles mit blockierendem Default-`awaitTermination()` greift.
  - Operator-supplied `--mcp-state-dir`/
    `DMIGRATE_MCP_STATE_DIR` wird nie automatisch geloescht.
  - Cleanup wird idempotent implementiert, damit SIGINT,
    Shutdown-Hook und normales Ende nicht doppelt fehlschlagen.
- Persistenzgrenze klar dokumentieren: `UploadSessionStore`,
  `ArtifactStore`, `SchemaStore`, `JobStore` und `QuotaStore` bleiben
  in dieser Phase In-Memory. Nach einem Restart koennen vorhandene
  Byte-Dateien im operator-supplied State-Dir ohne passende Metadaten
  nicht wieder ueber MCP referenziert werden; sie sind nur
  diagnostische/forensische Reste bis ein persistenter Metadata-
  Adapter landet.
- Operator-supplied State-Dirs bekommen trotzdem eine begrenzte
  Dateiebene-Cleanup-Strategie, damit Crashs und abgebrochene Sessions
  nicht zu unbounded Disk Growth fuehren:
  - beim Start nach erfolgreichem Lock wird ein best-effort Startup-
    Sweep ausgefuehrt
  - `FileBackedUploadSegmentStore.cleanupOrphans(activeSessions=emptySet())`
    darf Segment-Sessions aus frueheren Prozessen entfernen, weil die
    passende `UploadSessionStore`-Metadaten nach Restart ohnehin
    fehlen
  - nicht referenzierbare Artefaktdateien aus frueheren Prozessen
    werden nicht blind geloescht, sondern nach einer konfigurierbaren
    Retention-Grenze fuer orphaned Byte Files entfernt
    (`--mcp-state-orphan-retention`/
    `DMIGRATE_MCP_STATE_ORPHAN_RETENTION`, Default z.B. `24h`)
  - Artefakt-Orphan-Sweep ist store-layout-aware und rekursiv ueber
    die Shards unter `<stateDir>/artifacts/<sha256-prefix>/...`; er
    behandelt `.bin` und zugehoerige `.meta.json` als Einheit, entfernt
    verwaiste Temp-/Sidecar-Reste defensiv nach Retention und laesst
    unbekannte Dateien ausserhalb des Store-Layouts unangetastet
  - Retention-Parsing ist eine explizite String-Union:
    `never` deaktiviert den Sweep fuer forensische Betriebsmodi,
    `0`/`0s` bedeutet sofort loeschen, ansonsten werden nur positive
    Durations akzeptiert (`<number>ms|s|m|h|d` plus optional
    ISO-8601-`Duration.parse` fuer `PT...`-Werte). Ungueltige Werte
    liefern Exit 2 vor Serverstart.
  - Cleanup-Aktionen werden nur als Counts und State-Dir-Scope geloggt,
    nie mit Payload-Inhalten oder Secrets
- Keine Store-API erweitern fuer diesen AP. Falls Glue-Code fuer
  State-Dir/cleanup noetig ist, bleibt er im CLI-Adapter; die
  file-backed Store-Contracts aus `:adapters:driven:storage-file`
  bleiben unveraendert, ausser wenn fuer die Artefakt-Orphan-
  Retention eine eng begrenzte file-adapter-interne Cleanup-API noetig
  ist.
- Bestehende read-only Handler-Unit-Tests bleiben in-memory. Neue
  CLI-/Bootstrap-Tests muessen dagegen das echte `mcp serve`-Wiring
  verwenden und dadurch die file-backed Byte-Stores pinnen.

Akzeptanz:

- `mcp serve` instanziert im Phase-C-Wiring nachweislich
  `FileBackedUploadSegmentStore` und `FileBackedArtifactContentStore`;
  `InMemoryUploadSegmentStore` und `InMemoryArtifactContentStore`
  tauchen im produktiven CLI-Wiring nicht mehr auf.
- Produktives `mcp serve` nutzt ein neutral benanntes CLI-Wiring; ein
  `Development`-Helper ist nicht der Einstiegspunkt fuer die
  Produktionsverdrahtung.
- Uploadsegmente liegen unter dem effektiven State-Dir auf Platte und
  Artefaktbytes werden aus dem file-backed `ArtifactContentStore`
  gelesen. Die Segment-/Artefakt-Store-Auswahl selbst haelt keine Bytes
  proportional zur Uploadgroesse im Heap; die vollstaendige
  End-to-End-Heap-Garantie fuer Finalisierung ist die Abhaengigkeit aus
  AP 6.22.
- `--mcp-state-dir`, `DMIGRATE_MCP_STATE_DIR`,
  `--mcp-state-orphan-retention` und
  `DMIGRATE_MCP_STATE_ORPHAN_RETENTION` werden in Helptext, Tests und
  Startlog sichtbar; die jeweiligen CLI-Optionen haben Vorrang vor den
  Environment-Variablen.
- CLI-owned Tempdirs werden beim normalen Stop und beim SIGINT-Pfad
  best-effort entfernt; operator-supplied State-Dirs ueberleben den
  Shutdown inklusive ihrer Byte-Dateien. CLI-owned Tempdirs werden auch
  bei Startfehlern nach Tempdir-Erzeugung best-effort entfernt.
- Zwei parallele `mcp serve`-Prozesse mit demselben State-Dir koennen
  nicht starten; der zweite Prozess endet mit Exit 2. Ein nach Crash
  zurueckgebliebenes `.lock`-File ohne aktives Advisory-Lock blockiert
  den naechsten Start nicht.
- Startup-Sweep begrenzt orphaned Segment- und Artefaktbytes aus
  frueheren Prozessen gemaess Retention-Konfiguration; Tests decken
  Default-Retention, `0`, `never`, eine positive Duration und einen
  ungueltigen Wert ab.
- Ungueltige State-Dirs liefern vor Serverstart Exit 2 mit klarer
  stderr-Diagnose.
- Helptext und Startlog sagen eindeutig, dass nur Byte-Content
  file-backed ist, Metadata weiterhin ephemer bleibt, State-Dirs
  single-writer sind und orphaned Byte Files nach Retention
  aufgeraeumt werden.
- Der AP gilt erst als abgeschlossen, wenn AP 6.24 seinen Upload-Flow
  mit diesem CLI-Wiring laufen lassen kann und AP 6.22 die
  streamingfaehige Finalisierung geliefert hat; sonst waere der
  file-backed Pfad nicht transportnah und heap-seitig verifiziert.

### 6.22 Streamingfaehige Finalisierung

Aufgaben:

- `ArtifactUploadHandler.assembleSessionBytes` baut heute eine
  `ByteArray(session.sizeBytes.toInt())` und fuellt sie mit
  `System.arraycopy` — Plan §4.1 verlangt aber "Finalisierung setzt
  Segmentbytes streamingfaehig zusammen". Der Heap-Peak ist heute
  `sizeBytes`; bei `maxArtifactUploadBytes = 200 MiB` ist das
  realistisch das Limit, aber ein 1-GiB-Cap waere damit nicht mehr
  haltbar.
- Zielbild: `ArtifactUploadHandler` baut keine Gesamtdaten-
  `ByteArray` mehr. Stattdessen wird eine kleine interne Abstraktion
  eingefuehrt, z.B. `AssembledUploadPayload(sizeBytes, sha256,
  openStream(), close/delete)`. Sie ist wiederoeffnend, weil der
  Finalizer denselben Payload mindestens zweimal braucht: einmal fuer
  Parse/Validate und einmal fuer `ArtifactContentStore.write`.
- Neben dem Streaming-Refactor wird die Finalisierung explizit
  single-writer gemacht. Vor Assembly, Parse, Artefaktschreiben oder
  `SchemaStore`-Registrierung muss der completing call die Session
  atomar claimen, z.B. ueber einen neuen transienten
  `UploadSessionState.FINALIZING` oder einen vergleichbaren
  Compare-and-set-Claim im `UploadSessionStore`.
  - Erlaubter Kernpfad: `ACTIVE -> FINALIZING -> COMPLETED` bei
    Erfolg und `ACTIVE -> FINALIZING -> ABORTED` bei Parse-,
    Validierungs- oder Materialisierungsfehlern.
  - `FINALIZING` akzeptiert keine weiteren Segmente und ist kein
    erfolgreicher terminaler Zustand; es ist nur der exklusive
    Side-Effect-Claim fuer die laufende Finalisierung.
  - Der Claim ist lease-basiert (`finalizingClaimId`,
    `finalizingClaimedAt`, `finalizingLeaseExpiresAt`) oder bekommt
    eine aequivalente Stale-Erkennung. Crasht der Prozess zwischen
    Claim und Terminal-State, darf die Session nicht dauerhaft in
    `FINALIZING` haengen: nach Lease-Ablauf kann ein neuer completing
    call denselben deterministischen Finalisierungsversuch reclaimen.
    Vor Lease-Ablauf bleibt es beim retrybaren Busy-/Conflict-Pfad.
    Lease-Zeit wird ausschliesslich mit der injizierten `Clock` des
    Phase-C-Wirings berechnet; negative Clock-Jumps verlaengern keinen
    bereits gespeicherten Lease, sondern behalten den gespeicherten
    `finalizingLeaseExpiresAt` als obere Grenze. Tests muessen
    Vorwaerts- und Rueckwaerts-Clock-Jumps simulieren.
  - Verliert ein konkurrierender `isFinalSegment=true`-Call den Claim,
    darf er keine Assembly starten und kein Artefakt/schemaRef
    erzeugen. Er laedt den aktuellen Session-Zustand neu: bei
    `COMPLETED` mit `finalisedSchemaRef` greift der AP-6.18-Replay-
    Pfad, bei noch laufender `FINALIZING` gibt es einen bestehenden
    retrybaren Konflikt/Busy-Fehler ohne Side Effects.
  - Der Claim und die spaetere Persistierung von `finalisedSchemaRef`
    muessen in den Store-Contracts testbar sein; ein einfacher
    read-then-save ohne CAS reicht fuer diesen AP nicht.
- Die Finalisierung bekommt einen deterministischen Outcome-Anker, der
  Crashs zwischen Artefaktwrite und Session-Commit replayfaehig macht:
  - Prioritaer wird ein atomarer `FinalizationOutcome`-Record am
    Session-Datensatz reserviert, bevor der erste Side Effect startet.
    Der Record enthaelt mindestens `claimId`, `payloadSha256`,
    `artifactId`, `schemaId`, `format`, Status (`IN_PROGRESS`,
    `FAILED`, `SUCCEEDED`) und optional sanitisierten Fehlercode/
    Details. `artifactId` und `schemaId` sind darin deterministisch
    aus Tenant, UploadSessionId, Gesamt-SHA und Format abgeleitet,
    damit der Record auch nach Crash rekonstruiert werden kann.
  - `ArtifactContentStore.write` muss mit `AlreadyExists` fuer den
    deterministischen `artifactId` idempotent behandelbar sein, sofern
    der vorhandene SHA/Size zum Payload passt; Konflikte bleiben harte
    interne Inkonsistenz
  - `SchemaStore` muss denselben deterministischen `schemaId` ebenso
    idempotent behandeln: existiert bereits ein Eintrag mit gleichem
    Tenant, Schema-Hash/normalisierter Schema-Projektion,
    Artifact-Ref und Format, ist die Registrierung ein No-op und
    liefert dieselbe `schemaRef`; abweichende Inhalte unter derselben
    `schemaId` bleiben harte interne Inkonsistenz
  - Nach erfolgreichem Artefaktwrite und SchemaStore-Registrierung
    wird `finalisedSchemaRef` zusammen mit Artifact-/Schema-Ids und
    Payload-SHA persistiert, bevor die Session auf `COMPLETED`
    wechselt. Ein Crash nach Artefaktwrite, aber vor `COMPLETED`, kann
    nach FINALIZING-Lease-Ablauf denselben Outcome rekonstruieren und
    ohne zweites Artefakt/schemaRef fortfahren.
  - Der AP darf nicht auf zufaellige `UUID`-Generatoren als alleinige
    Idempotenzbasis fuer Artefakt/schemaRef setzen.
- `assembleSessionBytes` wird durch `assembleSessionPayload` ersetzt:
  - Segmente werden aus `UploadSegmentStore.listSegments(...)` streng
    nach `segmentIndex` sortiert und auf Luecken, Duplikate,
    Offset-Kontinuitaet, `segmentTotal` und erwartete Gesamtgroesse
    validiert.
  - Fuer jedes Segment wird `openSegmentRangeRead(..., length =
    segment.sizeBytes)` genutzt und mit einem festen Buffer
    (`<= 64 KiB`, keine `readAllBytes()`) in den Payload geschrieben.
    Alle Byte-Zaehler, Offsets, Segmentlaengen, `runningBytes` und
    Limits sind `Long`; Addition wird vorab gegen Overflow geprueft
    (`runningBytes > Long.MAX_VALUE - read` ist ein interner Fehler),
    damit Upload-Caps oberhalb 1 GiB defensiv bleiben.
  - Das gerade komplettierende Segment darf nur als bereits im
    `UploadSegmentStore` gespeichertes Segment in die Assembly
    eingehen. Falls der Implementierungspfad die Request-Bytes als
    Optimierung weiterreicht, bleibt diese ByteArray-Nutzung auf
    `maxUploadSegmentBytes` begrenzt und darf nicht zur Gesamtpayload-
    Kopie werden.
  - Die Assembly berechnet Gesamt-SHA-256 und geschriebenes Byte-Count
    streaming mit und vergleicht beides mit Session-/Request-
    Finalitaetsdaten. Mismatch fuehrt zu `VALIDATION_ERROR` bzw. dem
    bestehenden Upload-Fehlermapping, bevor der Finalizer startet.
  - Das zentrale Cap wird waehrend jedes Segment-Leseschritts hart
    erzwungen: `runningBytes + read > maxArtifactUploadBytes` bricht
    sofort ab, loescht die temporaere Spool-Datei und liefert den
    bestehenden `PAYLOAD_TOO_LARGE`/Quota-Fehler. Die Implementierung
    darf nicht erst nach kompletter Assembly feststellen, dass
    inkonsistente Store-Metadaten zu viele Bytes geschrieben haben.
  - Persistente Assembly-Inkonsistenzen (Luecken, Duplikate,
    Offsetbruch, Size-/SHA-Mismatch nach gespeicherten Segmenten)
    werden als terminaler, replay-stabiler Session-Outcome gespeichert
    (ABORTED mit sanitisiertem Fehlercode/Details oder aequivalenter
    Rejection-Record). Retrys derselben completing Anfrage erhalten
    denselben Fehlerpfad und starten keine neue Assembly-Schleife mit
    abweichender Diagnose.
- Primaerer Produktionspfad: Assembly schreibt in eine temporaere
  Spool-Datei unter dem AP-6.21-State-Dir, z.B.
  `<stateDir>/assembly/<uploadSessionId>/<uuid>.bin`, und gibt eine
  wiederoeffnende Payload-Sicht auf diese Datei zurueck. Die Spool-
  Datei ist nur Arbeitszustand, wird nie als normales Artefakt
  registriert und erscheint nicht in MCP-Responses.
- Die Spool-Datei wird in `try/finally` geloescht, egal ob Parse,
  Validierung, Artefaktmaterialisierung, `SchemaStore`-Registrierung
  oder Session-Transition fehlschlaegt. Der ABORTED-Pfad darf keine
  Assembly-Spools zuruecklassen; AP 6.21 Startup-Sweep darf verwaiste
  Assembly-Spools aus Crashs nach Retention entfernen.
- Assembly-Spool-Cleanup ist Teil dieses APs, nicht nur ein Verweis
  auf 6.21:
  - normaler Prozesspfad: jede erzeugte Assembly-Datei und ihr
    Session-Unterverzeichnis werden in `finally` best-effort entfernt
  - Crash-/Kill-Pfad: der AP-6.21 Startup-Sweep muss
    `<stateDir>/assembly/<uploadSessionId>/...` rekursiv kennen und
    Dateien nach derselben Orphan-Retention-Policy entfernen
  - unbekannte Dateien ausserhalb des Assembly-Layouts bleiben
    unangetastet; Cleanup-Logs nennen nur Counts und State-Dir-Scope
- `SchemaStagingFinalizer.complete` erhaelt eine streambasierte,
  wiederoeffnende Payload-Sicht statt `ByteArray`. Die Default-
  Implementierung:
  - parst via `payload.openStream().use { codec.read(it) }`
  - validiert das `SchemaDefinition`
  - schreibt bei Erfolg via neu geoeffnetem Stream in
    `ArtifactContentStore.write(artifactId, source,
    expectedSizeBytes = payload.sizeBytes)`
  - nutzt `payload.sha256` fuer Plausibilitaets-/Testasserts, aber
    verlaesst sich weiterhin auf das `WriteArtifactOutcome` des Stores
    fuer die publizierte Artefakt-Integritaet
- Fuer handlernahe Tests bleibt eine ByteArray-Konvenience erlaubt,
  z.B. `AssembledUploadPayload.fromBytes(bytes)`. Sie darf nicht im
  produktiven CLI-Wiring genutzt werden und muss in Tests sichtbar vom
  file-backed Assembly-Pfad unterscheidbar sein.
- Quotas: `maxArtifactUploadBytes` wird beim Assembly-Schreiben
  parallel zum bestehenden Limit-Check geprueft, damit ein
  segment-by-segment Spool nicht versehentlich groesser werden kann
  als der zentrale Cap. Bei Ueberschreitung wird die temporaere Datei
  sofort geloescht und der bestehende `PAYLOAD_TOO_LARGE`/Quota-
  Fehlerpfad genutzt.
- Idempotenz-/Replay-Grenze: Ein Replay nach erfolgreicher
  Finalisierung darf keine neue Assembly starten. Es liest wie AP 6.18
  den persistierten `finalisedSchemaRef` aus der Session. Nur der erste
  echte completing call, der den Finalisierungs-Claim gewonnen hat,
  erzeugt einen Assembly-Spool.
- Replay nach terminalem Fehler wird ebenfalls explizit:
  - `ABORTED` wegen Parse-/Validierungsfehler oder Assembly-
    Inkonsistenz liefert deterministisch denselben sanitisierten
    Fehlercode/Details aus dem persistierten `FinalizationOutcome`
  - kann der Fehler-Outcome nicht persistiert werden, gilt die
    Finalisierung als nicht abgeschlossen und bleibt retrybar innerhalb
    des `FINALIZING`-Lease-/Reclaim-Modells; ein erfolgreicher Abort
    ohne persistierten sanitized Outcome ist fuer diesen AP nicht
    akzeptabel
  - `ABORTED` ist nie ein Signal, die Assembly erneut zu starten
- Fehlersemantik bleibt fuer fachliche Fehler unveraendert:
  Parse-/Validierungsfehler rollen die noch aktive Session auf
  `ABORTED` und liefern die strukturierten Findings.
  Transiente Storage-/IO-Fehler beim Assembly-Spool,
  Artefaktschreiben oder SchemaStore-Commit duerfen die Session nicht
  vorschnell auf `ABORTED` setzen; sie bleiben retrybar innerhalb des
  `FINALIZING`-Lease-/Reclaim-Modells oder werden als interner
  retrybarer Fehler gemappt. Lokale Pfade duerfen in Tool-Responses
  nicht leaken.

Akzeptanz:

- `ArtifactUploadHandler` enthaelt im Finalisierungspfad keine
  `ByteArray(session.sizeBytes.toInt())`, kein `System.arraycopy` ueber
  Gesamtpayload und kein `readAllBytes()` fuer gespeicherte Segmente.
- Heap-Peak waehrend der Assembly ist `O(maxUploadSegmentBytes +
  copyBuffer)` statt `O(sizeBytes)`; im produktiven CLI-Wiring ist die
  grosse Payload nur als Datei/Stream sichtbar.
- `SchemaStagingFinalizer` parst und materialisiert ueber Streams; die
  ByteArray-Konvenience ist nur fuer Tests und kleine Fixtures erlaubt.
- Gesamtgroesse, Segmentreihenfolge, Offsets und Gesamt-SHA werden im
  Streaming-Pfad erneut validiert; fehlerhafte Stored-Segmente koennen
  nicht zu einem `schemaRef` werden.
- Erfolgreiche Finalisierung schreibt genau ein normales Artefakt in
  `ArtifactContentStore` und registriert genau eine `schemaRef`;
  temporaere Assembly-Dateien werden nie als Artefakte registriert.
- Zwei konkurrierende completing calls fuer dieselbe Session koennen
  nicht beide finalisieren: genau ein Call gewinnt den atomaren
  Finalisierungs-Claim, Verlierer erzeugen keinen Assembly-Spool, kein
  Artefakt und keine `schemaRef`.
- Ein stale `FINALIZING` nach simuliertem Crash wird nach Lease-Ablauf
  deterministisch reclaimt; vor Lease-Ablauf erhalten weitere Calls
  einen retrybaren Busy-/Conflict-Fehler ohne Side Effects. Tests
  nutzen die injizierte `Clock` und decken Vorwaerts- sowie
  Rueckwaerts-Clock-Jumps ab.
- Der `FinalizationOutcome`-Record ist der priorisierte
  Implementierungsweg; reine deterministische ID-Ableitung ohne
  persistierten Outcome-Record reicht fuer AP 6.22 nicht.
- Crash nach erfolgreichem `ArtifactContentStore.write`, aber vor
  `COMPLETED`, wird replay-stabil fortgesetzt: deterministischer
  Artifact-/Schema-Outcome verhindert doppelte Artefakte und fuehrt am
  Ende zu derselben `schemaRef`.
- SchemaStore-Registrierung ist fuer denselben deterministischen
  `schemaId` idempotent: gleicher Inhalt/Artifact-Ref liefert dieselbe
  `schemaRef`, abweichender Inhalt ist eine interne Inkonsistenz.
- `maxArtifactUploadBytes` wird pro Buffer-Read waehrend der Assembly
  erzwungen; ein korruptes Segment oder falsche Store-Metadaten koennen
  keine Spool-Datei groesser als den zentralen Cap schreiben. Byte-
  Arithmetik nutzt `Long` und prueft Addition auf Overflow.
- Parse-/Validation-/IO-Fehler und ABORTED-Sessions hinterlassen keine
  Assembly-Spool-Files im normalen Prozesspfad.
- Replays fuer ABORTED-Sessions starten keine neue Assembly; sie
  liefern den persistierten sanitisierten Fehler-Outcome aus
  `FinalizationOutcome`.
- Persistente Segment-/Assembly-Inkonsistenzen werden als terminaler
  Session-Outcome dedupliziert; wiederholte completing calls erzeugen
  dieselbe Antwortklasse und keine neuen Side Effects.
- Verwaiste Assembly-Spool-Files aus simuliertem Crash/Kill unter
  `<stateDir>/assembly/...` werden beim naechsten Start gemaess
  Orphan-Retention bereinigt; Tests decken Retention noch nicht
  faellige Dateien und faellige Dateien ab.
- existierende AP-6.18 Idempotenz-Tests bleiben gruen; ein Replay-Pfad
  liest den schon persistierten `schemaRef` und oeffnet keinen neuen
  Assembly-Spool.
- AP 6.24 deckt den End-to-End-Pfad mit file-backed Byte-Stores ab und
  pinnt, dass ein grosser Upload nicht proportional zur Gesamtgroesse
  Heap allokiert.

### 6.23 Output-Schema-Drift fuer `artifactRef` und `findings.details`

Aufgaben:

- Ziel: JSON-Schema und Runtime-Wire-Output muessen denselben Phase-C-
  Contract beschreiben. AP 6.13/6.19 haben bereits Runtime-Felder wie
  `artifactRef`, `diffArtifactRef`, `truncated` und Finding-`details`
  eingefuehrt; AP 6.23 macht diese Felder in den
  `PhaseBToolSchemas`-Output-Schemas der betroffenen Tools explizit,
  streng und drift-getestet.
- `PhaseBToolSchemas.kt` bekommt gemeinsame Output-Bausteine statt
  freier `arrayField()`/`objectField()`-Platzhalter:
  - `findingArray(detailsSchema: Map<String, Any>? = null)`
  - `findingItem(...)` mit `additionalProperties=false`
  - `artifactRefField()` fuer `dmigrate://tenants/{tenantId}/artifacts/{artifactId}`
  - `executionMetaField()` als optionales Top-Level-Feld, aber wenn
    vorhanden mit `required: ["requestId"]` und ohne offene Secret-/
    Debug-Slots
  Die Helper verwenden die bestehenden Wire-Konstanten aus
  `SchemaFindingSeverity`, damit Handler und Schema nicht auseinander
  laufen.
- Gemeinsames `findings`-Item-Schema fuer `schema_validate`,
  `schema_compare` und als Basis fuer `schema_generate`:
  - required: `severity`, `code`, `path`, `message`
  - `severity`: enum `error`/`warning`/`info`
  - `code`, `path`, `message`: string
  - optional `details`
  - keine zusaetzlichen Properties auf Finding-Ebene
  - `details`-Value-Slots erlauben nur scrubbed Skalare oder Arrays
    davon mit `maxItems: 16`; Array-Elemente sind ebenfalls auf
    `string`/`number`/`boolean`/`null` beschraenkt und Arrays duerfen
    nicht geschachtelt werden. Strings in `details` nutzen dieselbe
    Laengenbegrenzung wie andere kurze Output-Strings, damit "kleine
    Arrays" schema-pruefbar bleibt.
  `path` bleibt string und darf leer sein, wenn der fachliche Pfad
  unbekannt ist; `message` bleibt lesbare Kurzfassung.
- `schema_generate` nutzt einen eigenen Finding-Helper, z.B.
  `generatorFindingItem()`, der das gemeinsame Basisschema erweitert
  und ausschliesslich dort `hint: string` als optionales Feld erlaubt.
  `schema_validate` und `schema_compare` muessen weiterhin ohne
  `hint` validieren; kein globales `additionalProperties=true`.
- `schema_validate` Output-Schema:
  - `artifactRef` optional, Format string mit URI-Pattern fuer
    Artefakt-Resource-Refs
  - `findings` nutzt das gemeinsame Finding-Schema; `details` ist fuer
    Validierungsfindings optional, aber wenn vorhanden ein geschlossenes
    Objekt mit nur scrubbed skalaren Feldern (`string`/`number`/
    `boolean`/`null`) oder kleinen Arrays davon
  - `truncated=true` bedeutet: inline Findings sind gekuerzt; wenn ein
    `ArtifactSink` verfuegbar war, muss `artifactRef` auf die
    vollstaendige Findings-Projektion zeigen
  - ohne `ArtifactSink` darf `schema_validate` keine unbounded
    Inline-Ausgabe am `ResponseLimitEnforcer` vorbei erzeugen. Der
    einzige erlaubte No-Sink-Pfad ist ein tool-spezifischer, bounded
    degradierter Output mit `truncated=true`, gekappten Findings und
    explizitem `truncationReason: "artifact_sink_unavailable"`;
    alternativ darf der Handler einen scrubbed Toolfehler liefern. Der
    generische ResponseLimit-Enforcer-Fallback bleibt auch hier
    ungueltig.
- `schema_generate` Output-Schema:
  - `artifactRef` optional fuer grossen DDL-/Finding-Fallback
  - `ddl` optional, weil grosse DDL vollstaendig ins Artefakt wandert
  - `truncated=true` bedeutet: inline DDL und/oder Findings sind nur
    Summary; vollstaendige Ausgabe ist ueber `artifactRef` abrufbar
  - DDL- und Findings-Bytes muessen vor jedem `ArtifactSink`-Write aus
    `SchemaGenerateHandler` durch denselben Scrubbing-/Projection-Pfad
    laufen wie Inline-Output. `artifactSink.writeReadOnly(...,
    content = ddlBytes, ...)` darf nicht mehr ungefilterte DDL
    persistieren; Artefaktinhalt ist der gescrubbte Wire-View, nicht
    ein interner Rohdump.
  - Findings-Overflow muss wie `schema_validate` ueber
    `ArtifactSink` ausgelagert werden; `truncated=true` ohne
    `artifactRef` ist nur erlaubt, wenn kein `ArtifactSink` im Wiring
    vorhanden ist und muss in Tests als degradierter Pfad gepinnt sein
  - die Fallback-Entscheidung darf nicht nur an der DDL-Groesse
    haengen: Findings-Truncation allein muss bei verfuegbarem
    `ArtifactSink` ebenfalls ein Artefakt mit der vollstaendigen,
    gescrubbten Ausgabe erzeugen und `artifactRef` setzen. Der
    Runtime-Pfad in `SchemaGenerateHandler` muss deshalb DDL-Overflow
    und Findings-Overflow ueber denselben Artifact-Fallback
    zusammenfuehren.
  - Generatorwarnungen nutzen das gemeinsame Finding-Basisschema plus
    explizit erlaubtem optionalem `hint: string`, weil die Runtime
    heute `hint` top-level emittiert. `hint` bleibt scrubbed und ist
    keine freie `additionalProperties`-Oeffnung. Eine spaetere
    Migration nach `details.hint` waere ein separater Wire-Contract-
    Change.
- `schema_compare` Output-Schema:
  - `diffArtifactRef` optional, URI-Pattern wie `artifactRef`
  - `truncated=true` bedeutet: inline Diff-/Finding-Ausgabe ist
    gekuerzt; wenn ein `ArtifactSink` verfuegbar war, muss
    `diffArtifactRef` auf die vollstaendige, gescrubbte Diff-Projektion
    zeigen. Inline-Pflichtfelder wie Summary, `findings`, `truncated`
    und `diffArtifactRef` bleiben auch im Fallback schema-kompatibel;
    der globale `ResponseLimitEnforcer` darf sie nicht durch ein
    generisches Envelope ersetzen.
  - `findings` nutzt ein compare-spezifisches Finding-Schema, bei dem
    `details` optional ist und, falls vorhanden, ein geschlossenes
    Objekt mit den optionalen Feldern `before` und `after` enthaelt.
    Gueltig sind `before` allein, `after` allein oder beide zusammen;
    null-/leere Platzhalter ersetzen fehlende Seiten nicht.
  - `details.before` und `details.after` sind im Phase-C-Wire-Contract
    scrubbed Strings, weil `SchemaCompareHandler.beforeAfter(...)`
    die Werte heute via `toString()` serialisiert. Strukturierte JSON-
    Values waeren ein eigener Handler-/Wire-Refactor und gehoeren
    nicht zu AP 6.23.
  - das `details`-Schema muss `additionalProperties=false` und eine
    Nicht-Leer-Regel (`minProperties: 1` oder passendes
    `required`/`oneOf`) erzwingen. Sobald `details` emittiert wird,
    muss mindestens `before` oder `after` vorhanden sein; ein formal
    gueltiges leeres Objekt ist nicht erlaubt.
  - additive/removal-Findings ohne Vor-/Nachzustand lassen `details`
    weg; ein leeres `details: {}` ist kein gueltiger Ersatz
- `job_status_get` Output-Schema wird mit AP 6.17 abgeglichen:
  - `progress` ist optional, aber wenn vorhanden geschlossen
    typisiert nach dem aktuellen `ManagedJob.JobProgress`-Wire:
    `phase: string` und `numericValues: object` mit kuratierten
    Counter-Namen und `Long`-Zahlenwerten; keine
    `completedUnits`/`totalUnits`/`unit`-Felder ohne separaten Core-
    Refactor
  - `error` ist optional, aber wenn vorhanden geschlossen typisiert
    nach aktuellem `JobError`: `code`, `message`, optional
    `exitCode`; kein `error.details` in `job_status_get`, solange
    `ManagedJob.JobError` dieses Feld nicht besitzt
  - `artifacts` bleibt Array von Resource-URI-Strings; rohe lokale
    Pfade, Connection-Strings oder Secret-Felder sind nicht erlaubt.
    Das Schema nutzt fuer jedes `artifacts[]`-Element dasselbe
    Artefakt-Resource-URI-Pattern wie `artifactRef`; `resourceUri`
    selbst nutzt das Job-Resource-URI-Pattern
  - der Handler darf intern weiterhin Artefakt-IDs aus Job-Metadaten
    lesen, muss sie vor dem Wire-Output aber tenantgebunden in
    `dmigrate://tenants/{tenantId}/artifacts/{artifactId}` projizieren.
    Tests/Fixtures mit nackten IDs wie `art-1` muessen entsprechend auf
    Resource-URIs migriert werden; nackte IDs sind nur interne
    Speicherwerte, kein `job_status_get`-Output.
    Falls bestehende In-Memory-/Fixture-Daten bereits nackte IDs
    enthalten, behandelt AP 6.23 sie als Backfill-Projektion im
    Handler: ID rein, Resource-URI raus. Bereits URI-foermige Werte
    werden validiert und nicht doppelt umgeschrieben.
  - AP 6.23 umfasst die Runtime-Umruestung in
    `JobStatusGetHandler`: `managed.artifacts` darf nicht mehr direkt
    durchgereicht werden. Unit-Tests muessen sowohl den positiven
    URI-Output als auch die schema-seitige Ablehnung nackter IDs
    pinnen.
- Wichtige Abgrenzung: Finding-`details` ist ein Objekt auf
  Tool-Output-Findings. Toolfehler-`details` im `isError=true`-
  Envelope bleibt gemaess `McpServiceImpl` ein Array von
  `{key, value}`. AP 6.23 darf diese zwei Wire-Shapes nicht
  zusammenfuehren. Falls AP 6.23 Schema-Validation fuer
  Toolfehler-Envelopes ergaenzt, muss die zentrale Fehlerpipeline
  (`ErrorMapper`/`McpServiceImpl`) die Detail-Values vor der
  Serialisierung scrubben; das ist getrennt vom `job_status_get.error`
  Output.
  Diese Scrub-Stufe ist eine unbedingte Serialisierungsgrenze von
  AP 6.23: alle vorhandenen, gemappten oder weitergereichten
  Fehlerobjekte muessen direkt vor dem MCP-Wire-Payload durchlaufen.
  Weder `ErrorMapper` noch `McpServiceImpl` duerfen
  `error.details[].value` 1:1 ins Wire-Payload schreiben; bereits
  persistierte oder upstream erzeugte Detailwerte werden beim
  Antworten ebenfalls scrubbed projiziert.
- Forbidden-Key- und Scrubbing-Regel auf alle Output-`details`
  ausweiten:
  - `details`, `details.before`, `details.after`,
    `job_status_get.progress.phase`, `job_status_get.error.message`
    und, fuer Toolfehler-Envelopes,
    `error.details[].value` laufen vor Serialisierung durch
    `SecretScrubber.scrub`
  - Schema-Builder/Golden-Tests muessen verbotene Detail-Keys
    normalisiert pruefen: case-insensitive, Separatoren wie `_`, `-`
    und `.` ignorierend, und als Substring-/Pattern-Treffer fuer
    Woerter wie `password`, `secret`, `token`, `credential`,
    `connectionString`, `jdbcUrl`, `apiKey`, `privateKey`. Varianten
    wie `dbPassword`, `credentialToken` oder `api_token` muessen
    blockiert oder vor der Serialisierung umbenannt/gescrubbt werden.
    `SchemaSecretGuard` muss dieselbe Normalisierung nutzen; exakte
    Lowercase-Token-Vergleiche reichen fuer AP 6.23 nicht aus.
  - Scrubbing findet vor Artefakt-Fallback statt, damit auch
    ausgelagerte Findings-/DDL-/Diff-Artefakte keine Secrets oder
    lokalen Pfade enthalten
  - Regressionstests lesen den tatsaechlich geschriebenen
    `schema_generate`-Artefaktinhalt und pinnen, dass DDL-Defaults,
    Finding-Details und Tool-Error-Detail-Values mit Bearer-Token,
    Passwort oder JDBC-URL nur gescrubbt im Wire-Output und im Artefakt
    vorkommen.
- `ResponseLimitEnforcer` darf fuer die vier AP-6.23-Tools kein
  generisches Truncated-Envelope emittieren, das die per-Tool-
  Output-Schemas umgeht. Groessen-Fallbacks muessen entweder
  tool-spezifisch vor dem Enforcer passieren und alle Pflichtfelder des
  jeweiligen Schemas erhalten, oder der Enforcer bekommt pro Tool einen
  schema-kompatiblen Fallback-Builder. Ein generisches
  `{summary, artifactRef, truncated}`-Objekt ist fuer
  `schema_validate`, `schema_generate`, `schema_compare` und
  `job_status_get` kein valider AP-6.23-Output.
- `PhaseBToolSchemas` bildet die `truncated`-/Ref-Kopplung
  maschinenpruefbar ab:
  - fuer `schema_validate` und `schema_generate`: `if
    truncated=true` dann `artifactRef` required, ausser der Output
    traegt explizit `truncationReason:
    "artifact_sink_unavailable"` fuer den getesteten No-Sink-
    Degradationspfad
  - fuer `schema_compare`: `if truncated=true` dann
    `diffArtifactRef` required, ausser derselbe No-Sink-
    Degradationsgrund ist gesetzt
  - fuer alle anderen Faelle darf `truncationReason` fehlen; der
    Grund ist kein Ersatz fuer einen vorhandenen `ArtifactSink`.
- Goldenfile-Pin-Test (`phase-b-tool-schemas.json`) regenerieren und
  dabei die Diffs bewusst reviewen. Zusaetzlich zu Goldenfiles braucht
  AP 6.23 Runtime-Schema-Validation-Tests: exemplarische Outputs von
  `schema_validate`, `schema_generate`, `schema_compare` und
  `job_status_get` muessen gegen ihr eigenes `PhaseBToolSchemas`-
  Output-Schema validieren.
- Backward-Compatibility:
  - bestehende Top-Level-Felder bleiben erhalten
  - neu typisierte optionale Felder duerfen fehlen, wenn der Handler
    sie nicht emittiert
  - `additionalProperties=false` gilt fuer neue strukturierte
    Objekte; dynamische Fachwerte duerfen nur in den explizit
    erlaubten `details`-Value-Slots liegen

Akzeptanz:

- jedes Tool, das `artifactRef` (oder `diffArtifactRef`) im Wire-
  Output emittieren kann, listet das Feld im JSON-Schema mit
  Resource-URI-Pattern
- `findings`-Item-Schema ist strukturell typisiert, nicht
  `additionalProperties: true`; `details`-Arrays haben ein hartes
  `maxItems` und lehnen geschachtelte Arrays oder unbeschraenkte
  Detail-Listen schema-seitig ab
- `schema_compare` akzeptiert `details.before`/`details.after` fuer
  Aenderungsfindings als Strings; das Details-Schema erzwingt
  `additionalProperties=false` plus Nicht-Leer-Regel und lehnt
  `details: {}` sowie unbekannte Credential-Key-Felder ab
- `schema_compare` setzt bei Diff-/Finding-Truncation mit verfuegbarem
  `ArtifactSink` ein `diffArtifactRef` und behaelt alle
  tool-spezifischen Pflichtfelder im Inline-Output; generische
  Fallback-Envelopes sind kein gueltiger Ersatz
- `schema_validate` setzt bei `truncated=true` und verfuegbarem
  `ArtifactSink` ein `artifactRef` auf die vollstaendige gescrubbte
  Findings-Projektion; der degradierte Pfad ohne Sink ist gesondert
  getestet und bleibt bounded mit `truncationReason:
  "artifact_sink_unavailable"` oder liefert einen scrubbed Toolfehler
- `schema_validate` und `schema_generate` koennen optionale
  Finding-`details` schema-valide transportieren; `schema_generate`
  akzeptiert ueber einen tool-spezifischen Finding-Helper zusaetzlich
  das explizite legacy-`hint`-Feld, waehrend `schema_validate` und
  `schema_compare` dieses Feld ablehnen
- `job_status_get.progress` und `job_status_get.error` sind typisiert
  und spiegeln die AP-6.17-Scrubbing-Projektion
- `job_status_get.resourceUri` und jedes `job_status_get.artifacts[]`
  validieren gegen Resource-URI-Patterns; lokale Pfade oder nackte
  IDs werden schema-seitig abgelehnt. Runtime-Tests decken den
  Backfill-Fall ab, in dem `managed.artifacts` nackte IDs enthaelt und
  der Handler trotzdem Resource-URIs emittiert. Die Migration ist
  dual-read/single-write: interne Legacy-IDs werden akzeptiert und im
  Output projiziert, neue Job-Metadaten speichern nach Moeglichkeit
  Resource-URIs oder eindeutig ableitbare Artifact-IDs, nie lokale
  Pfade.
- `executionMeta` darf als Ganzes fehlen; sobald es emittiert wird,
  ist `executionMeta.requestId` required und scrubbed
- `schema_generate` setzt bei Findings-Overflow mit verfuegbarem
  `ArtifactSink` ein `artifactRef`; der degradierte Pfad ohne Sink ist
  gesondert getestet. Ein Testfall deckt Findings-only-Overflow bei
  kleiner DDL ab, damit der Fallback nicht DDL-groessenabhaengig
  bleibt.
- Runtime-Outputs der vier betroffenen Tools validieren gegen ihre
  eigenen Output-Schemas, auch wenn der Response-Groessenfallback
  greift; der generische `ResponseLimitEnforcer`-Envelope wird fuer
  diese Tools nicht als schema-valider Ersatz akzeptiert
- JSON-Schema-Tests pinnen die `if/then`-Constraints fuer
  `truncated=true`: ohne Ref ist nur der explizite No-Sink-
  Degradationsgrund gueltig; bei verfuegbarem Sink muss die passende
  `artifactRef`/`diffArtifactRef` vorhanden sein
- `details`-Werte in Inline-Responses und ausgelagerten Artefakten
  werden gescrubbt; Tests pinnen mindestens Bearer-Token, Passwort-Key
  und lokalen Pfad
- verbotene Detail-Keys werden normalisiert und case-insensitive
  erkannt; Tests pinnen Varianten wie `dbPassword`,
  `credentialToken`, `api_token` und `JDBCUrl`
- Tool-Error-Envelopes scrubben `error.details[].value` zentral vor
  der Serialisierung; ein Regressionstest deckt einen Fehlerdetail-
  Wert mit Secret ab
- der goldfile-Pin-Test scheitert bei einer Schema-Aenderung, die
  nicht bewusst regeneriert wurde

### 6.24 stdio+HTTP-Integrationstest-Suite fuer alle Phase-C-Tools

Aufgaben:

- Neues Test-Source-Set (oder Kotest-`integration`-Tag in
  `:adapters:driving:mcp`) das beide Transports einmal hochfaehrt
  und denselben Tool-Flow gegen jeden ausfuehrt — keine doppelten
  Asserts pro Transport, sondern eine generische `transports.forAll`-
  Schleife.
- Abdeckung pro Plan §7.3:
  - Initialize/Capabilities aus Phase B
  - `capabilities_list`
  - `schema_validate` mit kleinem Inline-Schema
  - `schema_generate` mit kleinem Inline-Schema, `schemaRef`,
    Artefakt-Fallback
  - read-only Schema-Staging eines grossen Schemas
    (`artifact_upload_init` → mehrere `artifact_upload`-Segmente →
    Finalisierung zu `schemaRef`)
  - `schema_compare` mit zwei materialisierten `schemaRef`-Eingaengen
  - `artifact_chunk_get` fuer ein grosses Artefakt
  - `job_status_get` fuer eigenen Job und unbekannten Job
  - fachlicher Toolfehler als `isError=true`
  - Resource-Fehler als JSON-RPC-Error
  - keine Tool-Antwort ueber Inline-Limit
  - keine Tool-/Artefakt-/Audit-Antwort enthaelt verbotene
    Secret-Keys
- Test nutzt das CLI-Wiring aus AP 6.21 (file-backed) damit der
  Upload-Flow real persistente Bytes schreibt.
- Audit-Sink wird auf `InMemoryAuditSink` umgeleitet, damit die
  Tests pinnen koennen, dass jeder tools/call genau einen
  AuditEvent erzeugt.

Akzeptanz:

- jede Tool-Flow-Sequenz aus Plan §7.3 laeuft gegen stdio UND HTTP
  in einem einzigen CI-Lauf
- gemeinsame Tool-Liste, gemeinsame Fehler-Mappings, gemeinsame
  Audit-Outcomes
- der Integrationstest scheitert, sobald ein Phase-C-Tool nur in
  einem der zwei Transports korrekt funktioniert

---

## 7. Verifikationsstrategie

### 7.1 Unit-Tests

Pflichtabdeckung:

- Schema-Quellen-Resolver fuer Inline- und `schemaRef`
- genau-eine-Quelle-Regel
- Inline-Schema-Groessenlimit
- `targetDialect`-Validierung
- Findings-/Notes-Mapping
- Output-Limit-Entscheidung
- Artefakt-Fallback-Entscheidung
- `schema_compare` lehnt `connectionRef` ab
- read-only Upload-Intent-Validierung
- Segmentindex-/Offset-/Hash-Validierung
- Gesamt-SHA-256-Pruefung
- Upload-Session-TTL
- Abort-Cleanup
- Failure-Retention und Cleanup fuer validierungsfehlgeschlagene Staging-
  Uploads
- Schema-Materialisierung nach Upload
- ungueltiges Schema erzeugt keine `schemaRef`
- `artifact_chunk_get` Chunk-Berechnung, `maxArtifactChunkBytes`,
  Text/Base64-Encoding und Chunk-SHA-256
- `job_status_get` Owner-/Tenant-Regeln
- `job_status_get` No-Oracle-Fehlerpfad fuer fremde und unbekannte Jobs
- Secret-/Path-Scrubbing fuer dynamische Output-Slots und freie Strings
- Forbidden-Key-Filter fuer `additionalProperties: true`-Outputs
- Defense-in-depth Tenant-/Owner-Recheck nach Store-Load fuer
  `job_status_get`, `artifact_chunk_get` und `schemaRef`-Materialisierung
- Audit-Outcome fuer Validierungs-, Payload-, Tenant- und Quota-Fehler

### 7.2 Adaptertests je Tool

Pflichtabdeckung fuer jedes Phase-C-Tool:

- gueltiger Minimalaufruf
- ungueltiger Payload
- Scope-Verletzung
- limitierter Output
- strukturierter Fehler
- Audit-Ereignis

Zusaetzlich:

- `capabilities_list` ohne DB-Verbindung
- `schema_validate` mit gueltigem und ungueltigem Schema
- `schema_generate` mit Inline-Schema und `schemaRef`
- `schema_generate` mit Artefakt-Fallback
- `schema_compare` mit zwei `schemaRef`-Eingaengen
- `schema_compare` mit `connectionRef` als `VALIDATION_ERROR`
- read-only Upload-Init ohne Approval
- Segment-Upload mit Resume
- Upload-Finalisierung zu `schemaRef`
- Upload-Finalisierung erfolgt ausschliesslich implizit durch das
  komplettierende Segment
- Abort eigener Session
- `artifact_chunk_get` fuer grosses Artefakt
- `artifact_chunk_get` fuer Binaerartefakt mit Base64-Response
- `job_status_get` fuer eigenen, fremden und unbekannten Job
- dynamische Output-Slots mit verbotenen Keys oder Secret-aehnlichen Werten
  werden gescrubbt oder abgelehnt
- falsch gescopte Store-Records werden nach dem Laden fail-closed behandelt

### 7.3 Integrationstests

Gemeinsame Tests fuer `stdio` und HTTP:

- Initialize/Capabilities aus Phase B bleiben funktionsfaehig
- `capabilities_list`
- `schema_validate` mit kleinem Inline-Schema
- `schema_generate` mit kleinem Inline-Schema
- `schema_generate` mit `schemaRef`
- `schema_generate` mit Artefakt-Fallback
- read-only Schema-Staging eines grossen Schemas
- `schema_compare` mit zwei materialisierten `schemaRef`-Eingaengen
- `artifact_chunk_get` fuer ein grosses Artefakt
- `job_status_get` fuer eigenen Job und unbekannten Job
- fachlicher Toolfehler als `isError=true`
- Resource-Fehler als JSON-RPC-Error
- keine Tool-Antwort ueber Inline-Limit
- keine Tool-, Artefakt- oder Audit-Antwort enthaelt verbotene Secret-Keys,
  rohe Secrets, expandierte Secret-URLs oder lokale Pfade

HTTP-spezifisch:

- Phase-B-HTTP-Auth und Scope-Mapping gelten fuer Phase-C-Tools
- `PAYLOAD_TOO_LARGE` ausserhalb erfolgreicher Tool-Results wird korrekt
  transportnah gemappt, falls der HTTP-Transport den Request vor
  `tools/call` abweist
- Uploadsegmente ueber HTTP respektieren Request- und Segmentlimits

Stdio-spezifisch:

- Phase-B-stdio-Principal-Ableitung gilt fuer Phase-C-Tools
- fehlender oder scope-armer stdio-Principal wird fuer read-only Tools
  abgewiesen
- Uploadsegmente ueber `stdio` respektieren Request- und Segmentlimits

### 7.4 Negative Tests

Verbindliche Negativfaelle:

- Inline-Schema groesser als `maxInlineSchemaBytes`
- nicht-Upload-Tool-Payload groesser als `maxNonUploadToolRequestBytes`
- Uploadsegment groesser als `maxUploadSegmentBytes`
- fehlender Gesamt-`checksumSha256`
- falscher Segmenthash
- falscher Gesamthash
- Segment fuer abgelaufene oder abgebrochene Session
- Wiederholung des komplettierenden Segments liefert dasselbe
  Finalisierungsergebnis
- `artifact_upload_init` mit `uploadIntent=job_input`
- `schema_compare` mit `connectionRef`
- unbekannter `schemaRef`
- fremder Tenant in `schemaRef`, `jobId` oder `artifactId`
- nicht autorisiert sichtbarer fremder `jobId` liefert nach aussen kein
  Existenzsignal
- unbekannter `targetDialect`
- Validator-/Generatorfehler wird nicht zu `INTERNAL_AGENT_ERROR`
- grosse DDL-/Diff-Ausgabe wird nicht abgeschnitten
- dynamische Output-Maps mit verbotenen Keys werden gescrubbt oder abgelehnt
- Store-Records mit abweichendem Tenant/Owner werden nach dem Laden
  fail-closed behandelt, auch wenn der Store-Query bereits tenant-scoped war

---

## 8. Betroffene Codebasis

Voraussichtlich zu erweitern:

- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/...`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/tools/...`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/resources/...`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/schema/...`
- `adapters/driving/mcp/src/test/kotlin/dev/dmigrate/mcp/...`
- gemeinsame Testfixtures fuer `stdio` und HTTP

Voraussichtlich zu nutzen oder ggf. adapterneutral zu ergaenzen:

- `hexagon/application` Schema-Validator-/Generator-/Compare-Pfade
- `hexagon/application` Store- und Artifact-Services aus Phase A
- `hexagon/application` Error-, Audit-, Quota- und Timeout-Services
- bestehende Format-/Codec-Registries

Nicht zu aendern ohne separaten Grund:

- CLI-Vertraege
- produktive Write-Runner
- Import-/Transfer-Verhalten
- `schema_compare_start`
- Policy-Grant-Vertrag
- REST-/gRPC-Dokumente ausser indirekte Referenzen

---

## 9. Risiken und Gegenmassnahmen

### 9.1 Read-only Upload wird versehentlich zu Write-Upload

Risiko:

- `schema_staging_readonly` wird spaeter als `job_input` oder fuer
  datenveraendernde Pfade genutzt

Gegenmassnahme:

- Upload-Intent hart typisieren
- Artefaktkind `schema_staging_readonly` getrennt halten
- Write-Pfade akzeptieren dieses Artefaktkind nicht als Job-Input
- Tests fuer abgelehnte `job_input`-Nutzung in Phase C

### 9.2 Inline-Limits werden pro Tool unterschiedlich interpretiert

Risiko:

- einzelne Handler liefern zu grosse Antworten oder schneiden Ergebnisse
  still ab

Gegenmassnahme:

- zentrale Limit- und Fallback-Komponente
- Golden-/Adaptertests fuer grosse DDL, grosse Diffs und viele Findings
- `capabilities_list` als einzige Quelle fuer publizierte Limits

### 9.3 Schema-Fachlogik wird im MCP-Adapter dupliziert

Risiko:

- MCP-Resultate weichen von CLI/Application-Verhalten ab

Gegenmassnahme:

- Handler delegieren an bestehende Application-Pfade
- Adaptertests pruefen Mapping, nicht zweite Fachlogik
- bei fehlendem adapterneutralem Service wird dieser im Kern ergaenzt,
  nicht im MCP-Adapter nachgebaut

### 9.4 Ungueltige Schemas werden als `schemaRef` registriert

Risiko:

- spaetere Tools arbeiten auf defekten materialisierten Schemas

Gegenmassnahme:

- Finalisierung trennt Artefaktmaterialisierung von `SchemaStore`-
  Registrierung
- Registrierung nur nach erfolgreichem Parse/Validate
- negative Tests fuer defekte Uploads

### 9.5 Tenant-Leaks ueber Fehlermeldungen

Risiko:

- `schemaRef`, `artifactId` oder `jobId` fremder Tenants werden durch
  Fehlermeldungen bestaetigt

Gegenmassnahme:

- Tenant-Scope vor Detailauflosung pruefen
- `TENANT_SCOPE_DENIED` ohne fremde Ressourcendetails
- Audit intern mit `requestId`, Response bereinigt

### 9.6 Runtime-Slots umgehen den SchemaSecretGuard

Risiko:

- `additionalProperties: true`-Slots koennen zur Laufzeit beliebige Felder
  tragen, obwohl der Phase-B-`SchemaSecretGuard` nur statische Schema-Properties
  prueft.

Gegenmassnahme:

- typisierte Output-Records bevorzugen
- zentrale Runtime-Scrubbing-Funktion vor der Serialisierung
- Tests mit verbotenen Keys und Secret-aehnlichen Stringwerten in dynamischen
  Slots
- Audit- und Wire-Projektionen gegen dieselben Scrubbing-Regeln pruefen

### 9.7 Store-Scope wird implizit vertraut

Risiko:

- ein zukuenftiger Store liefert trotz tenant-scoped Query einen Datensatz mit
  falschem Tenant, falschem Owner oder fehlender Principal-Sichtbarkeit.

Gegenmassnahme:

- Phase-C-Handler revalidieren geladene Datensaetze gegen
  `PrincipalContext.effectiveTenantId`, erlaubte Tenants und Owner-/Scope-
  Regeln
- Fehlerpfade bleiben no-oracle, wie bei `job_status_get` beschrieben
- Tests nutzen absichtlich falsch gescopte Store-Records

---

## 10. Definition of Done fuer Phase C

- `capabilities_list` funktioniert ohne DB-Verbindung.
- `capabilities_list` enthaelt Tools, Dialekte, Formate und alle
  numerischen Limits.
- `capabilities_list` enthaelt `maxArtifactChunkBytes=32768`.
- `schema_validate` akzeptiert `schemaRef` oder kleines Inline-Schema.
- `schema_validate` liefert strukturierte Findings.
- `schema_generate` akzeptiert `schemaRef` oder kleines Inline-Schema.
- `schema_generate` erzeugt DDL fuer kleine Inline-Schemas ohne Upload und
  ohne Policy.
- `schema_generate` referenziert grosse DDL als Artefakt.
- `schema_compare` akzeptiert zwei `schemaRef`-Eingaenge.
- `schema_compare` lehnt `connectionRef` mit `VALIDATION_ERROR` und Hinweis
  auf `schema_compare_start` ab.
- Read-only Schema-Staging fuer grosse Schemas funktioniert ohne
  Approval-Flow.
- Read-only Schema-Staging nutzt File-Spooling, Gesamt-Hash, Segmenthash,
  TTL- und Abort-Cleanup.
- Read-only Schema-Staging finalisiert ausschliesslich implizit beim
  komplettierenden `artifact_upload`-Segment.
- Nur gueltige gestagte Schemas erzeugen eine `schemaRef`.
- Ungueltige Staging-Uploads veroeffentlichen kein dauerhaftes Rohartefakt
  und werden nach `stagingFailureRetention` bereinigt.
- Policy-pflichtige Upload-Intents bleiben in Phase C nicht verfuegbar.
- `artifact_chunk_get` kann grosse Artefakte chunkweise liefern.
- `artifact_chunk_get` hat ein festes Text-/Base64-Response-Schema und
  begrenzt Rohchunks auf `32768` Bytes.
- `job_status_get` liefert eigene bzw. erlaubte Jobmetadaten.
- `job_status_get` nutzt einen no-oracle Fehlerpfad fuer unbekannte,
  abgelaufene und nicht autorisiert sichtbare Jobs.
- Owner-, Tenant- und Scope-Pruefungen sind fuer alle Phase-C-Tools aktiv.
- Geladene Datensaetze werden defense-in-depth gegen Tenant, Owner und
  Principal-Sichtbarkeit revalidiert.
- Dynamische Output-Slots und freie Strings werden vor Wire- und Audit-Ausgabe
  gescrubbt oder typisiert begrenzt.
- Output-Limits werden zentral erzwungen.
- Grosse Ergebnisse werden nicht still abgeschnitten.
- Fachliche Toolfehler werden als `tools/call`-Result mit `isError=true`
  gemappt.
- Resource-Fehler bleiben JSON-RPC-Errors.
- Audit-Events entstehen auch fuer Validierungs-, Payload-, Tenant-,
  Quota- und Auth-Fehler.
- Adaptertests fuer `stdio` und HTTP decken alle Phase-C-Tools ab.
- Integrationstests zeigen read-only Schema-Staging und anschliessende
  Nutzung der erzeugten `schemaRef`.

---

## 11. Anschluss an Phase D

Phase D darf beginnen, wenn Phase C die read-only Toolbasis stabil
bereitstellt.

Phase D baut darauf auf:

- Discovery-Tools fuer Jobs, Artefakte, Schemas, Profile, Diffs und
  Connection-Refs
- MCP-Ressourcen fuer Jobs, Artefakte, Schemas, Profile, Diffs und
  Connections
- Resource-Reads und Chunk-Templates ueber den vollstaendigen
  Resource-Vertrag
- Listen-, Filter- und Pagination-Verhalten fuer bekannte Ressourcen

Phase D darf die read-only Schema-Tools nicht wieder auf eigene
Resource- oder Artefaktmodelle umstellen. Wenn Discovery zusaetzliche
Metadaten braucht, werden die Phase-A-Store-/Index-Vertraege erweitert.

---

## 12. Offene Entscheidungen mit Default-Empfehlung

### 12.1 Materialisierte `schemaRef`-Form

Default:

- `schemaRef` als tenant-scoped `dmigrate://tenants/{tenantId}/schemas/{schemaId}`
  oder als stabile Projektion auf `ServerResourceUri`

Verbindlich:

- keine lokalen Dateipfade
- keine rohen Artefaktpfade
- Zugriff immer gegen `PrincipalContext`

### 12.2 DDL-Artefaktformat

Default:

- textuelles Artefakt mit Dialekt, Generatoroptionen, SHA-256, Groesse und
  Resource-URI

Verbindlich:

- DDL wird nicht abgeschnitten
- Generatorwarnungen bleiben strukturiert erreichbar

### 12.3 Compare-Diff-Format

Default:

- strukturierte Summary und Findings inline, vollstaendiger Diff als
  Artefakt bei Ueberschreitung der Limits

Verbindlich:

- Diff-Findings bleiben maschinenlesbar
- grosse Diffs werden ueber `artifact_chunk_get` oder Resource-Chunks
  abrufbar

### 12.4 Read-only Upload-Finalisierung

Entscheidung:

- Finalisierung erfolgt ausschliesslich implizit durch das
  `artifact_upload`-Segment, das die erwartete Gesamtgroesse exakt
  komplettiert.
- Phase C fuehrt keinen separaten Finalize-Tool-Aufruf und kein optionales
  `finalize=true`-Feld ein.

Verbindlich:

- Gesamt-SHA-256 wird vor `schemaRef`-Registrierung validiert
- unvollstaendige Sessions registrieren keine `schemaRef`
- abgebrochene oder abgelaufene Sessions nehmen keine Segmente mehr an
- Wiederholungen des komplettierenden Segments liefern bei identischem
  Payload dasselbe Finalisierungsergebnis
- validierungsfehlgeschlagene Sessions halten Rohbytes nur bis
  `stagingFailureRetention` (Default maximal 24 Stunden)

### 12.5 Umfang von `job_status_get`

Default:

- Phase C liefert Job-Metadaten fuer bereits vorhandene Jobs und
  testweise erzeugte Store-Eintraege, startet aber keine Jobs

Verbindlich:

- keine Runner-Steuerung
- kein Cancel
- keine Policy-Entscheidung
- keine fremden Tenant-Details im Fehlerpfad
