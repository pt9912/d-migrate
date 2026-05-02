# Implementierungsplan: 0.9.6 - Phase C `Read-only Tools`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: C (`Read-only Tools`)
> **Status**: In Arbeit (2026-05-02) — AP 6.1 startet auf Phase-B-Registry
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
