# Implementierungsplan: 0.9.6 - Phase D `Discovery und Ressourcen`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: D (`Discovery und Ressourcen`)
> **Status**: Draft, verfeinert (2026-05-03)
> **Referenz**: `docs/planning/in-progress/implementation-plan-0.9.6.md` Abschnitt 1 bis 7,
> Abschnitt 8 Phase D, Abschnitt 9.1, Abschnitt 9.2, Abschnitt 9.3,
> Abschnitt 9.4, Abschnitt 11 und Abschnitt 12;
> `docs/planning/done/ImpPlan-0.9.6-A.md`;
> `docs/planning/done/ImpPlan-0.9.6-B.md`;
> `docs/planning/in-progress/ImpPlan-0.9.6-C.md`; `spec/ki-mcp.md`;
> `spec/job-contract.md`; `spec/architecture.md`; `spec/design.md`;
> `spec/cli-spec.md`; `hexagon/application`; `hexagon/ports-common`;
> `hexagon/ports-read`; `hexagon/ports-write`; `hexagon/profiling`;
> `adapters/driving/cli`; `adapters/driving/mcp`.

---

## 1. Ziel

Phase D macht Jobs, Artefakte, Schemas, Profile, Diffs und Connection-Refs
ueber MCP auffindbar und gezielt lesbar. Der Schwerpunkt liegt auf
Discovery-Tools, MCP-Standard-Discovery, Resource-Resolvern, tenant-sicherer
Paginierung und einer adapterneutralen Connection-Ref-Aufloesung.

Phase D liefert konkrete technische Ergebnisse:

- `job_list`
- `artifact_list`
- `schema_list`
- `profile_list`
- `diff_list`
- `job_status_get` und `artifact_chunk_get` auf dem vollstaendigen
  Resource-/Discovery-Vertrag
- MCP-Standard-Discovery:
  - `resources/list`
  - `resources/templates/list`
  - `resources/read`
- Resource-Resolver fuer:
  - Jobs
  - Artefakte
  - Artefakt-Chunks
  - Schemas
  - Profile
  - Diffs
  - Connection-Refs
  - `dmigrate://capabilities`
- cursorbasierte Paginierung mit validierten, gekapselten Tokens
- tenant-sichere Resource-Aufloesung
- adapterneutrale Connection-Ref-Konfiguration fuer CLI und MCP
- Secret-freier Bootstrap von Connection-Refs

Nach Phase D soll gelten:

- Clients koennen vorhandene Ressourcen paginiert entdecken.
- Clients koennen Resource-URIs aus Discovery-Antworten gezielt lesen.
- grosse Inhalte werden ueber Chunk-Templates oder Artefaktreferenzen
  adressiert, nicht inline in Discovery-Antworten ausgeliefert.
- Ressourcen ausserhalb des erlaubten Tenant-Scopes werden nicht sichtbar und
  nicht durch Details bestaetigt.
- Connection-Refs sind auffindbar, enthalten aber keine Secrets.
- CLI und MCP nutzen denselben adapterneutralen Connection-Ref-Bootstrap.

Wichtig: Phase D erweitert Discovery und Ressourcen. Sie fuehrt keine neuen
datenveraendernden Start-Tools, keine Approval-Grants, keine Import-/Transfer-
Ausfuehrung und keine KI-Tools ein.

---

## 2. Ausgangslage

### 2.1 Phase A stellt Kernvertraege bereit

Phase A definiert die gemeinsamen Modelle und Stores fuer:

- `PrincipalContext`
- Job- und Artefaktmetadaten
- Resource-URIs
- Byte- und Content-Stores
- Schema-Stores
- Quotas, Audit und strukturierte Fehler

Phase D darf diese Vertraege erweitern, aber keine MCP-spezifischen
Parallelmodelle fuer Jobs, Artefakte oder Schemas einfuehren.

### 2.2 Phase B stellt Discovery-Grundlagen bereit

Phase B stellt Transport, Initialize, Capabilities, Registry-Infrastruktur und
MCP-Standard-Discovery-Grundpfade bereit. Phase D haengt produktive
Discovery-Daten, Resolver und Templates an diese Infrastruktur.

### 2.3 Phase C stellt read-only Tool- und Artefaktpfade bereit

Phase C erzeugt und nutzt read-only Schema-Artefakte und kleinere
Tool-Responses. Phase D macht diese Artefakte und Ressourcen konsistent
auffindbar und lesbar.

Phase D darf Phase-C-Tools nicht auf eigene Artefaktmodelle umstellen. Wenn
zusaetzliche Discovery-Metadaten noetig sind, werden die gemeinsamen
Store-/Index-Vertraege erweitert.

### 2.4 Bestehende Phase-B-Implementierung ist Startpunkt

Phase B hat bereits konkrete Adapterbausteine geliefert, die Phase D
weiterverwendet statt ersetzt:

- `ResourceStores` als Buendel der sechs listbaren Store-Ports
- `ResourcesListHandler` mit festem Resource-Walk
- `ResourcesListCursor` als Base64-JSON-Cursor fuer `(kind, innerToken)`
- `ResourceProjector` fuer MCP-`resources/list`-Projektionen
- `PhaseBResourceTemplates.ALL` als Quelle der sieben statischen Templates
- `ServerResourceUri` fuer einfache tenant-scoped URIs
- `PageRequest` / `PageResult` im gemeinsamen Kernvertrag

Phase D darf diese Typen umbauen, wenn es fuer HMAC-Cursor, Chunk-URIs oder
`resources/read` noetig ist. Sie darf aber keine zweite parallele
Resource-Registry oder zweite Resource-URI-Syntax neben diesen Bausteinen
einfuehren.

---

## 3. Scope fuer Phase D

### 3.1 In Scope

- Discovery-Tools:
  - `job_list`
  - `artifact_list`
  - `schema_list`
  - `profile_list`
  - `diff_list`
- Vollstaendiger Discovery-Vertrag fuer:
  - `job_status_get`
  - `artifact_chunk_get`
- MCP-Standard-Discovery:
  - `resources/list`
  - `resources/templates/list`
  - `resources/read`
- Resource-URI-Schema fuer Jobs, Artefakte, Schemas, Profile, Diffs und
  Connection-Refs
- Resource-Resolver pro Resource-Typ
- Store-/Index-Abstraktionen fuer Listen, Filter und Resource-Aufloesung
- Connection-Ref-Store mit Tenant-Scope und Sensitivitaetsmetadaten
- adapterneutraler Connection-Config-Port
- Split der bestehenden CLI-Named-Connection-Logik in:
  - Secret-freien Bootstrap
  - autorisierte Secret-Aufloesung im Ausfuehrungspfad
- Cursor-Paginierung mit Filtervalidierung
- gekapselte Cursor-/Pagination-Tokens fuer oeffentliche Adapter
- Tenant-Scope-Pruefung fuer jede Resource-Aufloesung
- Unit-, Adapter- und Integrationstests fuer Discovery, Resources,
  Pagination, Tenant-Scope und Connection-Refs

### 3.2 Nicht in Scope

- neue Start-Tools fuer Reverse, Profiling, Import oder Transfer
- Approval-, Policy-Grant- oder Upload-Intents fuer datenveraendernde
  Operationen
- freie SQL- oder Datenabfrage-Resources
- Secret-Ausgabe in Discovery, Resources, Audit oder Tool-Responses
- produktive Cloud-Secret-Provider-Implementierungen jenseits des
  adapterneutralen Vertrags
- Resource-Subscriptions, `listChanged` oder Server-Push
- Cross-Tenant-Discovery fuer normale Principals

---

## 4. Leitentscheidungen

### 4.1 Discovery ist ein Index, kein Content-Dump

Discovery-Antworten liefern Metadaten, Resource-URIs und Cursor. Sie liefern
keine grossen Schema-, Profil-, Diff- oder Artefaktinhalte inline.

Grosse Inhalte werden ueber:

- `resources/read`
- Chunk-Resource-Templates
- `artifact_chunk_get`
- Artefaktreferenzen

bereitgestellt.

### 4.2 Cursor-Tokens sind Adapter-Vertraege

Der Kernvertrag kann einfache `PageRequest`-/`PageResult`-Modelle verwenden.
Sobald ein produktiver Store oder oeffentlicher Adapter Pagination
veroeffentlicht, muessen Tokens adapterseitig gekapselt werden:

- Base64-codiert und strukturiert
- signiert oder per HMAC-MAC versiegelt
- tenant- und filtergebunden
- versioniert
- mit `kid` fuer Key-Rotation versehen
- mit `issuedAt` und `expiresAt` versehen

Diese Regel gilt fuer Listen-Cursor und fuer Chunk-Fortsetzungen wie
`nextChunkCursor`. Manipulierte, syntaktisch ungueltige, tenant-fremde oder
abgelaufene Tokens liefern `VALIDATION_ERROR`, niemals fremde Daten. Phase D
fuehrt keinen separaten `TOKEN_EXPIRED`-Fehlercode ein.

Produktive MCP-Cursor muessen ablaufen. Defaults:

- fachliche Listen-Cursor: `15min`
- `resources/list`-Cursor: `15min`
- Chunk-Cursor fuer `artifact_chunk_get`: `5min`

Test- und lokale Dev-Konfigurationen duerfen kuerzere oder laengere TTLs
setzen, muessen dies aber explizit konfigurieren. Ein fehlendes `expiresAt`,
ein unbekannter `version`-Wert oder ein unbekannter `kid` liefert
`VALIDATION_ERROR`.

Fehlerpraezedenz bei Requests mit Cursor:

1. Auth-/Scope-Pruefung bleibt das bestehende vorgelagerte MCP-Mapping.
2. Request-Schema, bekannte Parameter, Datums-/Enum-Formate, `pageSize` und
   syntaktisch parsebare Resource-URI werden zuerst validiert. Fehler liefern
   `VALIDATION_ERROR`.
3. Danach wird der adressierte Tenant aus explizitem `tenantId`, Resource-URI
   oder `principal.effectiveTenantId` bestimmt. Liegt dieser Tenant ausserhalb
   `PrincipalContext.allowedTenantIds`, liefert der Request
   `TENANT_SCOPE_DENIED`. Ein mitgelieferter Cursor wird in diesem Fall nicht
   decodiert.
4. Erst nach erfolgreicher Tenant-Aufloesung wird ein Cursor decodiert,
   HMAC-validiert und gegen Tenant, Tool/Resource-Familie, Filter, `pageSize`
   und Sortierung gebunden. Jeder Fehler in diesem Schritt liefert
   `VALIDATION_ERROR`, auch wenn der Cursor selbst einen anderen Tenant
   enthaelt.
5. Erst danach wird gegen Stores/Resolver gelesen. Fehlende oder nicht
   sichtbare Datensaetze im erlaubten Tenant liefern no-oracle
   `RESOURCE_NOT_FOUND`.

Die Entscheidungslogik trennt drei "fremd"-Faelle:

| Stufe | Beispiel | Verbindlicher Fehler |
| --- | --- | --- |
| Expliziter Request-Tenant ist syntaktisch nicht bestimmbar | URI oder `tenantId` nicht parsebar | `VALIDATION_ERROR` |
| Expliziter Request-Tenant ist bestimmbar, aber nicht erlaubt | `tenantId=other` oder URI mit fremdem Tenant | `TENANT_SCOPE_DENIED`; Cursor wird nicht gelesen |
| Request-Tenant ist erlaubt, aber Cursor ist manipuliert, abgelaufen oder anders gebunden | Cursor-Tenant, Filter, `pageSize`, Tool/Familie, Sortierung, `version` oder `kid` passt nicht | `VALIDATION_ERROR` |
| Request-Tenant ist erlaubt und Cursor ist gueltig, aber Datensatz ist nicht sichtbar | fremder Job im erlaubten Tenant | `RESOURCE_NOT_FOUND` |

Diese Logik wird als zentraler, golden-getesteter Error-Resolver fuer alle
Cursor-Handler umgesetzt. Einzelne Handler duerfen die Reihenfolge nicht
nachbauen.

### 4.3 Connection-Refs sind secret-frei

Connection-Refs duerfen in Discovery und Resource-Reads sichtbar sein, aber nur
mit non-secret Metadaten:

- Name / ID
- Dialekt
- Umgebung oder Label
- Sensitivitaetsklasse
- Produktionskennzeichen
- erlaubte Operationen oder Policy-Hinweise

JDBC-URLs mit Passwort, expandierte ENV-Werte und rohe Secrets duerfen in
Discovery, Audit, Tool-Responses oder Resource-Inhalten nicht erscheinen.

`credentialRef` und `providerRef` bleiben interne Bootstrap-/Runner-
Metadaten. MCP-Projektionen duerfen sie selbst nicht ausgeben. Nach aussen
sind nur abgeleitete, secret-freie Ersatzfelder erlaubt, z. B.:

- `hasCredential`
- `providerKind`
- `sensitivity`
- `isProduction`
- `allowedOperations`

`allowedOperations` und Policy-Hinweise sind reine Metadaten fuer Discovery und
Resource-Projektionen. Sie bereiten spaetere Start-Tool-Entscheidungen vor,
ziehen aber `schema_compare_start`, Reverse, Import oder Transfer nicht in den
Scope von Phase D.

### 4.4 CLI und MCP teilen Bootstrap-Logik

Die bestehende CLI-Config-/Connection-Aufloesung wird hinter einen
adapterneutralen Port gezogen. CLI und MCP nutzen denselben Bootstrap.

Der MCP-Adapter darf nicht abhaengen von:

- `adapters:driving:cli`
- `dev.dmigrate.cli.config.NamedConnectionResolver`
- CLI-spezifischem YAML-Parsing

YAML-/Projektkonfigurationsparser gehoeren in ein dediziertes Config- oder
Driven-Modul. `hexagon:application` nutzt nur den Port.

---

## 5. Resource-Vertrag

### 5.1 Resource-Typen

Phase D definiert Resource-URIs fuer:

| Typ | Beispiel-URI | Inhalt |
| --- | ------------ | ------ |
| Job | `dmigrate://tenants/{tenantId}/jobs/{jobId}` | Job-Metadaten, Status, Artefaktrefs |
| Artifact | `dmigrate://tenants/{tenantId}/artifacts/{artifactId}` | Artefaktmetadaten, Chunk-Links |
| Artifact chunk | `dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}` | begrenzter Inhaltsteil |
| Schema | `dmigrate://tenants/{tenantId}/schemas/{schemaId}` | Schema-Metadaten oder Inhalt bei kleiner Groesse |
| Profile | `dmigrate://tenants/{tenantId}/profiles/{profileId}` | Profiling-Metadaten oder Artefaktref |
| Diff | `dmigrate://tenants/{tenantId}/diffs/{diffId}` | Diff-Metadaten oder Artefaktref |
| Connection | `dmigrate://tenants/{tenantId}/connections/{connectionId}` | secret-freie Connection-Metadaten |
| Capabilities | `dmigrate://capabilities` | globale, secret-freie Faehigkeitsbeschreibung |

Diese tenant-scoped URI-Familie ist verbindlich und uebernimmt den
Phase-B-Vertrag. `tenantId` in der URI ist adressierend, nicht autorisierend:
jede Aufloesung prueft zusaetzlich den Principal-/Tenant-Scope.
`dmigrate://capabilities` bleibt die einzige tenantlose Sonderresource aus dem
uebergeordneten 0.9.6-Vertrag. Weitere tenantlose Resource-URIs sind nicht
Teil von Phase D.

Der bestehende `ServerResourceUri`-Parser unterstuetzt bereits
`dmigrate://tenants/{tenantId}/{kind}/{id}`. Artifact-Chunks brauchen eine
explizite Erweiterung, weil sie eine verschachtelte URI haben. Phase D muss
das Parse-Modell deshalb als ADT oder aequivalenten ParseResult erweitern,
z. B.:

- `TenantResourceUri(tenantId, kind, id)`
- `ArtifactChunkResourceUri(tenantId, artifactId, chunkId)`
- `GlobalCapabilitiesResourceUri`

Chunk-URIs duerfen nicht als normales Artifact-`id`-Segment kodiert werden,
weil sonst Templates, Tenant-Pruefung und Fehlerbehandlung uneindeutig werden.

`ResourceKind.UPLOAD_SESSIONS` bleibt im Kernmodell parsebar, weil Upload-
Tools und interne Stores den bestehenden URI-Vertrag weiter nutzen. Fuer Phase
D ist dieser Kind jedoch vollstaendig MCP-resource-blocked:

- kein Eintrag in `resources/list`
- kein Eintrag in `resources/templates/list`
- kein `resources/read`
- kein Resolver-Zugriff auf Upload-Session-Stores

Ein `resources/read` auf
`dmigrate://tenants/{tenantId}/upload-sessions/{uploadSessionId}` validiert
zunaechst URI und Tenant gemaess Abschnitt 4.2. Ist der Tenant erlaubt, endet
der Request ohne Store-Lookup mit `VALIDATION_ERROR`, weil dieser Resource-Kind
in Phase D nicht lesbar ist. Dadurch kann das Erraten von Upload-Session-IDs
keine Existenz bestaetigen.

### 5.2 Resource-Read-Grenzen

Resource-Reads muessen dieselben Inline-Grenzen beachten wie Tool-Responses.
Phase D legt dafuer verbindliche Adapter-Konstanten fest:

- `MAX_RESOURCE_READ_RESPONSE_BYTES = 65536`
  - harte Obergrenze fuer die serialisierte MCP-`resources/read`-Antwort
    inklusive Content-Array und Metadaten
- `MAX_INLINE_RESOURCE_CONTENT_BYTES = 49152`
  - Obergrenze fuer den UTF-8-Body eines einzelnen inline ausgelieferten
    Text-/JSON-Inhalts, damit die Gesamtantwort unter 64 KiB bleibt
- `MAX_ARTIFACT_CHUNK_BYTES = 32768`
  - bestehende Phase-C-Grenze fuer Artefakt-Chunk-Rohdaten

Nur textuelle Inhalte mit `application/json`, `text/*` oder einem explizit
dokumentierten textuellen MIME-Typ duerfen inline als MCP-Text-Content
geliefert werden. Binaere oder unbekannte MIME-Typen werden nicht als Base64-
Ersatzpayload in `resources/read` erfunden, sondern nur ueber Artefaktrefs
oder Chunk-URIs adressiert.

Ein Resource-Resolver darf inline liefern, wenn alle Bedingungen gelten:

- MIME-Typ ist textuell erlaubt.
- UTF-8-Content-Body ist kleiner oder gleich
  `MAX_INLINE_RESOURCE_CONTENT_BYTES`.
- geschaetzte bzw. tatsaechlich serialisierte Gesamtantwort bleibt kleiner
  oder gleich `MAX_RESOURCE_READ_RESPONSE_BYTES`.

Groessere Ressourcen liefern:

- Artefaktref
- Chunk-Template
- naechsten Chunk-Cursor
- Groessen- und Hash-Metadaten, soweit vorhanden

Chunk-Fortsetzungen duerfen nicht als nackte `chunkId`-Cursor ausgegeben werden.
Erlaubt sind:

- tenant-scoped `nextChunkUri` wie
  `dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}` fuer
  den naechsten `resources/read`-Aufruf
- HMAC-gekapselte `nextChunkCursor` fuer Tool-Pfade wie `artifact_chunk_get`,
  gebunden an Tenant, Resource-/Artifact-ID, Byte-Range bzw. Chunk-ID und
  optional Version/Expiry

### 5.3 `resources/read`

`resources/read` ist ein eigenes Deliverable in Phase D. Der Request bleibt
kompatibel zum Phase-B-Vertrag und nimmt nur `uri` entgegen. Chunking,
Range-Reads oder Filter duerfen nicht als zusaetzliche `resources/read`-
Parameter eingefuehrt werden.

Der Handler muss:

- die URI syntaktisch validieren
- per Resource-URI-ADT auf den passenden Resolver dispatchen
- Principal-, Tenant- und Sichtbarkeitsregeln vor dem Lesen pruefen
- kleine Inhalte inline und grosse Inhalte als Artefaktref oder Chunk-URI
  liefern
- dasselbe Fehler-Mapping nutzen wie Discovery und fachliche Listen-Tools

Tests muessen mindestens URI-only Request-Schema, Resolver-Dispatch,
Tenant-/Principal-Grenzen, secret-freie Projektionen, `dmigrate://capabilities`
und Chunk-Grenzen abdecken.

### 5.4 Resource-Fehler

Verbindliche Fehler:

- `RESOURCE_NOT_FOUND` fuer nicht existente, nach Retention geloeschte oder
  fuer den Principal nicht sichtbare bzw. nicht autorisierte Ressourcen im
  erlaubten Tenant
- `TENANT_SCOPE_DENIED` fuer Ressourcen-URIs oder explizite `tenantId`-
  Parameter, deren Tenant ausserhalb des erlaubten Principal-Tenant-Scopes liegt
- `VALIDATION_ERROR` fuer syntaktisch ungueltige Resource-URIs, Filter sowie
  ungueltige, manipulierte, tenant-fremde oder abgelaufene Cursor-/Chunk-Tokens
- `AUTH_REQUIRED` / `FORBIDDEN` gemaess bestehendem Auth-/Scope-Mapping

Direkte `resources/read`-Zugriffe auf fremde Jobs, Artefakte oder andere
Ressourcen im erlaubten Tenant werden no-oracle wie nicht vorhandene Ressourcen
behandelt, ausser ein explizites Admin-/Scope-Modell erlaubt den Zugriff.
Kurzform: falscher Tenant-Scope ergibt `TENANT_SCOPE_DENIED`; richtiger Tenant,
aber keine Sichtbarkeit auf den konkreten Datensatz, ergibt
`RESOURCE_NOT_FOUND`.
Fehler duerfen keine fremden Ressourcendetails leaken.

---

## 6. Discovery-Tools

### 6.1 Gemeinsame Parameter

Alle Listen-Tools verwenden konsistente Parameter:

- `tenantId`
- `pageSize`
- `cursor`
- `type` oder resource-spezifischer Filter, falls sinnvoll
- `createdAfter`
- `createdBefore`
- `status`, wo fachlich passend
- `jobId`, wo Artefakte/Profile/Diffs an Jobs gekoppelt sind

`tenantId` bleibt fuer Phase D Teil des Phase-B-Wire-Vertrags. Es ist
adressierend, nicht autorisierend: fehlt es, wird `principal.effectiveTenantId`
genutzt; ist es gesetzt, muss es im erlaubten Tenant-Scope des Principals
liegen. Cross-Tenant-Discovery fuer normale Principals bleibt ausserhalb von
Phase D und liefert `TENANT_SCOPE_DENIED`. Innerhalb des erlaubten Tenants
werden Listen principal-gefiltert, nicht mit Existenz-/Sichtbarkeitsfehlern
angereichert.

Fuer Principals mit mehreren erlaubten Tenants ist `effectiveTenantId` der
deterministische Default des aktuellen Auth-/Session-Kontexts. Phase D liefert
keine automatische "alle erlaubten Tenants"-Fanout-Liste. Clients muessen fuer
jeden gewuenschten Tenant einen eigenen Request mit explizitem `tenantId`
senden.

Filter werden strikt validiert. Unbekannte Filter liefern `VALIDATION_ERROR`.

### 6.2 Gemeinsame Antwortform

Listen-Antworten bleiben kompatibel zum Phase-B-Wire-Vertrag und verwenden
typisierte Collection-Felder statt eines generischen `items`-Felds:

- `jobs`, `artifacts`, `schemas`, `profiles` oder `diffs`
- `nextCursor`
- optional `totalCount`
- optional `totalCountEstimate`

Interne Ports duerfen weiterhin ein generisches `PageResult<T>` verwenden; der
MCP-Adapter projiziert dieses Ergebnis auf das jeweilige typisierte
Output-Schema. Eine Migration auf generisches `items` waere ein versionierter
Schema-Bruch inklusive Golden-Test-Update und ist nicht Teil von Phase D.

`totalCount` ist exakt, wenn vorhanden. `totalCountEstimate` muss als
Naeherung gekennzeichnet sein.

Fachliche Listen-Tools (`job_list`, `artifact_list`, `schema_list`,
`profile_list`, `diff_list`) verwenden eine deterministische
Standardsortierung. Default:

- primaer `createdAt DESC`
- sekundaer stabile ID `ASC`

Resource-spezifische Sortierungen duerfen nur ergaenzt werden, wenn sie
dokumentiert und getestet sind. Cursor muessen an Tenant, Filter, `pageSize`,
Sortierung und letzte Sort-Keys gebunden sein. Dadurch bleiben die typisierten
Collection-Felder und `nextCursor` auch bei gleichzeitigen Inserts/Deletes
stabil genug, um keine Duplikate oder Luecken durch nichtdeterministische
Reihenfolge zu erzeugen.

`pageSize` bleibt fuer Phase D der Wire-Vertrag aus Phase B. `limit` wird in
Phase D nicht als Alias eingefuehrt, weil die bestehenden JSON-Schemas
`additionalProperties=false` verwenden. Eine spaetere Umstellung auf `limit`
braucht einen versionierten Migrationspfad und Golden-Test-Anpassungen.

Diese Sortierregel gilt nicht fuer MCP-`resources/list`. `resources/list`
folgt weiter dem verbindlichen Phase-B-Resource-Walk:

```text
JOBS -> ARTIFACTS -> SCHEMAS -> PROFILES -> DIFFS -> CONNECTIONS
```

Der `resources/list`-Cursor bleibt family-basiert (`kind`, `innerToken`) bzw.
dessen gekapselte Weiterentwicklung. Pro Resource-Familie gilt die jeweilige
Store-Sortierung; Connection-Refs duerfen keine `createdAt`-Sortierung
voraussetzen.

### 6.3 Tool-spezifische Hinweise

`job_list`:

- filterbar nach Status, Zeitfenster und Operationstyp
- nur eigene oder erlaubte Jobs sichtbar

`artifact_list`:

- filterbar nach Job, Artifact-Kind und Zeitfenster
- grosse Inhalte nicht inline

`schema_list`:

- listet bekannte SchemaRefs aus Staging, Jobs oder registrierten Stores
- zeigt Format, Groesse, Ursprung und optionale Hashes

`profile_list`:

- Profile sind auffindbar, auch wenn sie intern typisierte Artefakte sind
- zeigt Scope, Ziel-DB, Erzeugungszeit und Warnungszusammenfassung, soweit
  vorhanden

`diff_list`:

- Diffs sind auffindbar, auch wenn sie intern typisierte Artefakte sind
- zeigt Quell-/Zielbezug und Statussummary

### 6.4 Mindestfelder der Listen-Projektionen

Alle Listen-Projektionen enthalten mindestens:

- stabile ID des fachlichen Objekts
- `resourceUri`
- `tenantId`
- `createdAt`, soweit der Store diese Information besitzt
- `expiresAt` oder Retention-Hinweis, soweit vorhanden
- eine neutralisierte `visibilityClass`, soweit die Information fuer Clients
  relevant ist

Resource-spezifische Mindestfelder:

| Tool | Collection-Feld | Mindestfelder je Eintrag |
| --- | --- | --- |
| `job_list` | `jobs` | `jobId`, `status`, `operation`, `resourceUri`, `createdAt`, `updatedAt`, optionale `artifactUris` |
| `artifact_list` | `artifacts` | `artifactId`, `artifactKind`, `jobId`, `filename`, `sizeBytes`, `contentType`, `resourceUri`, optionale `chunkTemplate` |
| `schema_list` | `schemas` | `schemaId`, `format`, `origin`, `sizeBytes`, `resourceUri`, optionale `hash` |
| `profile_list` | `profiles` | `profileId`, `jobId`, `connectionId` oder `connectionResourceUri`, `scope`, `resourceUri`, optionale `warningCount` |
| `diff_list` | `diffs` | `diffId`, `jobId`, `leftSchemaId`, `rightSchemaId`, `statusSummary`, `resourceUri` |

Felder mit potentiell sensitiven Werten, z. B. lokale Pfade, JDBC-URLs,
rohe Connection-Strings oder ENV-Expansionen, werden nicht in diese
Projektionen aufgenommen. Wenn ein bestehendes Kernmodell solche Felder
enthaelt, muss die MCP-Projektion explizit scrubben oder das Feld weglassen.

`ownerPrincipalId` gilt fuer MCP-Discovery als potentielles PII-Feld und ist
kein Standardfeld der v1-Listen-Projektionen. Standard-Projektionen verwenden
stattdessen eine neutrale Sichtbarkeitsklasse, z. B. `own`, `tenantVisible`,
`shared` oder `adminVisible`. Eine rohe `ownerPrincipalId` darf nur in
internen Test-/Diagnoseprojektionen oder spaeteren Admin-spezifischen
Vertraegen mit explizitem Scope erscheinen; dieser Admin-Vertrag ist nicht
Teil von Phase D.

---

## 7. MCP-Standard-Discovery

### 7.1 `resources/list`

`resources/list` liefert konkret bekannte Ressourcen fuer den aktuellen
Principal und Tenant.

Anforderungen:

- paginiert
- tenant-gefiltert
- keine Secrets
- keine grossen Inhalte inline
- Resource-URIs aus denselben Resolvern wie `resources/read`

### 7.2 `resources/templates/list`

`resources/templates/list` liefert weiter genau die sieben parametrisierten
Phase-B-URI-Templates fuer:

- Jobs
- Artefakte
- Artefakt-Chunks
- Schemas
- Profile
- Diffs
- Connection-Refs

Chunk-Templates fuer grosse Artefakte muessen sichtbar sein, ohne konkrete
fremde Artefakte zu bestaetigen.
`dmigrate://capabilities` ist direkt lesbar, aber kein Eintrag in
`resources/templates/list`, damit der Phase-B-Vertrag der statischen
7-Template-Liste nicht gebrochen wird. Clients erhalten diese Sonderresource
ueber den dokumentierten 0.9.6-Vertrag, `spec/mcp-server.md` und einen
`capabilities_list.resourceFallbackHint`; sie darf nicht aus der Template-Liste
abgeleitet werden.

### 7.3 Initialize-Capabilities

Die `resources` Capability setzt explizit `{"listChanged": false,
"subscribe": false}`, solange Resource-Subscriptions nicht implementiert sind.
Diese Felder bleiben Teil des Initialize-Vertrags und duerfen nicht durch
Absent-Werte ersetzt werden.

---

## 8. Connection-Ref-Bootstrap

### 8.1 Adapterneutraler Port

Ein adapterneutraler Port modelliert:

- Laden von Connection-Refs aus Projekt-/Serverkonfiguration
- secret-freie Metadaten fuer Discovery
- `credentialRef` / `providerRef`
- Sensitivitaets- und Produktionsmetadaten
- spaetere Secret-Aufloesung nur fuer autorisierte Runner-/Driver-Pfade

Zusaetzlich definiert Phase D einen adapterneutralen
`ConnectionSecretResolver`-Contract fuer Ausfuehrungspfade. Discovery und
Bootstrap duerfen diesen Port nicht materialisieren, sondern nur
`credentialRef`, `providerKind`, `hasCredential` und weitere secret-freie
Metadaten projizieren.

### 8.2 Split der bestehenden CLI-Logik

Die bestehende Named-Connection-Logik wird so geschnitten:

- Parser und secret-freier Bootstrap wandern aus dem CLI-Adapter heraus.
- CLI und MCP rufen denselben Bootstrap auf.
- ENV-/Secret-Substitution passiert erst im autorisierten
  Ausfuehrungspfad.
- Logs und Audit sehen nur scrubbed oder referenzierte Werte.

### 8.3 Test-Seeds

Tests nutzen explizite Connection-Ref-Seeds:

- ohne echte Secrets
- mit `credentialRef` oder Fake-Provider
- mit Tenant- und Sensitivitaetsmetadaten
- mit negativen Faellen fuer fehlende Provider

Fehlt ein dokumentierter `ConnectionSecretResolver` fuer connection-backed
Pfade, muss fail-closed mit Konfigurationsfehler geantwortet werden.

---

## 9. Sicherheits- und Tenant-Regeln

Verbindliche Regeln:

- Jede Resource-Aufloesung prueft Tenant-Scope.
- Jede fachliche Listen-Antwort und `resources/list` sind principal-gefiltert.
- `resources/templates/list` ist die einzige Listen-Ausnahme: statisch,
  principal-unabhaengig und ohne Resource-Aufloesung.
- Cursor duerfen nicht tenant- oder filteruebergreifend wiederverwendbar sein.
- Produktive MCP-Cursor enthalten `version`, `kid`, `issuedAt` und `expiresAt`;
  Cursor ohne Ablaufzeit sind ungueltig.
- Sensitive Connection-Refs liefern nur Policy-Hinweise und
  `allowedOperations`-Metadaten, keine Secrets und keine ausfuehrbaren
  Start-Tool-Vertraege.
- Resource-Fehler leaken keine fremden Details.
- `UPLOAD_SESSIONS` ist in Phase D kein lesbarer MCP-Resource-Kind, auch wenn
  der Kernparser die URI fuer interne Upload-Pfade weiterhin versteht.
- Audit-Events entstehen auch fuer Validierungs-, Tenant-, Auth- und
  Cursor-Fehler.

---

## 10. Umsetzungsschritte

Die Arbeit wird in kleinen, testbaren Paketen umgesetzt. Jedes Paket darf
gemergt werden, wenn seine lokalen Tests gruen sind und der bestehende
Phase-C-Stand nicht regressiert. Die Reihenfolge ist verbindlich, weil
spaetere Pakete auf denselben URI-, Resolver- und Cursor-Vertraegen aufbauen.

### 10.1 AP D1: Resource-URI-ADT und Parser

Ziel: Ein gemeinsames Parse-Modell fuer alle in Phase D lesbaren URIs.

Umsetzung:

- `ServerResourceUri` oder ein Nachfolgemodell von einem einfachen
  `data class`-Vertrag auf eine ADT / sealed hierarchy erweitern.
- Varianten mindestens:
  - `TenantResourceUri(tenantId, kind, id)` fuer Jobs, Artefakte, Schemas,
    Profile, Diffs und Connections
  - `ArtifactChunkResourceUri(tenantId, artifactId, chunkId)`
  - `GlobalCapabilitiesResourceUri`
- `ResourceKind.UPLOAD_SESSIONS` bleibt im Kernmodell erlaubt, wird aber in
  Phase D nicht ueber `resources/list` oder `resources/templates/list`
  advertised und nicht ueber `resources/read` aufgeloest.
- Parser-Regeln fuer Segment-Zeichensatz, leere Segmente, unbekannte Kinds,
  zu viele Segmente und tenantlose URIs explizit testen.
- Renderer fuer alle erlaubten Varianten bereitstellen, damit Discovery und
  `resources/read` keine URI-Strings handbauen.

Tests:

- gueltige URIs aller acht lesbaren Resource-Familien
- verschachtelte Chunk-URI wird nicht als Artifact-ID missverstanden
- `dmigrate://capabilities` ist gueltig und tenantlos
- andere tenantlose URIs liefern `VALIDATION_ERROR`
- Upload-Session-URI bleibt parsebar, aber nicht listbar/template-visible in
  Phase D
- Upload-Session-URI ist fuer `resources/read` als Phase-D-blocked Kind
  klassifiziert

### 10.2 AP D2: Resolver-Vertrag und Fehler-Mapping

Ziel: Ein adapterneutraler Resolver-Vertrag, den `resources/read`,
`job_status_get`, `artifact_chunk_get` und die Discovery-Listen gemeinsam
nutzen koennen.

Umsetzung:

- `ResourceResolver`-Interface oder aequivalente Dispatcher-Schicht definieren.
- Resolver-Eingabe enthaelt mindestens `PrincipalContext`, parsebare
  Resource-URI, Inline-/Chunk-Limits und Audit-Kontext.
- Resolver-Ausgabe unterscheidet:
  - JSON/Text-Inline-Inhalt innerhalb des Limits
  - Metadaten mit Artefaktref
  - Chunk-Weiterleitung ueber `nextChunkUri`
  - no-oracle `not found`
- Gemeinsames Mapping fuer:
  - syntaktische Fehler -> `VALIDATION_ERROR`
  - Tenant ausserhalb `allowedTenantIds` -> `TENANT_SCOPE_DENIED`
  - nicht sichtbarer Datensatz im erlaubten Tenant -> `RESOURCE_NOT_FOUND`
  - erlaubter Tenant, aber Phase-D-blocked Resource-Kind wie
    `UPLOAD_SESSIONS` -> `VALIDATION_ERROR` ohne Store-Lookup
  - fehlende Scopes -> bestehendes Auth-/Scope-Mapping
- Fehlerpraezedenz aus Abschnitt 4.2 in einem zentralen, golden-getesteten
  Error-Resolver abbilden, damit Listen-Tools, `resources/read` und
  `artifact_chunk_get` keine divergierende Reihenfolge implementieren.
- `dmigrate://capabilities` als eigener Resolver, nicht als Sonderfall im
  JSON-RPC-Handler.

Tests:

- Resolver-Dispatch je URI-Variante
- kombinierte Fehlerfaelle aus der Entscheidungslogik in Abschnitt 4.2
- Tenant-fremde URI bestaetigt keine Existenz fremder Datensaetze
- erlaubter Tenant + Upload-Session-URI liefert `VALIDATION_ERROR` ohne
  Upload-Session-Store-Zugriff
- Datensatz im erlaubten Tenant, aber ohne Sichtbarkeit, liefert
  `RESOURCE_NOT_FOUND`
- Capability-Resolver liefert nur secret-freie Daten

### 10.3 AP D3: Cursor-Kapselung und Page-Vertrag

Ziel: Opaque, validierte Cursor fuer oeffentliche Adapter ohne Aenderung des
internen `PageRequest` / `PageResult`-Kernvertrags.

Umsetzung:

- MCP-seitigen Cursor-Codec fuer fachliche Listen-Tools einfuehren.
- Cursor-Payload mindestens:
  - Cursor-Typ
  - `version`
  - `kid`
  - Tenant
  - Tool oder Resource-Familie
  - validierte Filter
  - `pageSize`
  - Sortierung
  - letzte Sort-Keys oder Store-`innerToken`
  - `issuedAt`
  - `expiresAt`
- Payload Base64-url-safe serialisieren und per HMAC versiegeln.
- HMAC-Keyring aus Serverkonfiguration laden:
  - genau ein aktiver Signing-Key
  - null oder mehr Validation-Keys fuer Rotation
  - jeder Key hat stabile `kid`
  - alte Keys duerfen nur bis zur maximalen Cursor-TTL plus Clock-Skew
    akzeptiert werden
- Dev-/Test-Modus nutzt explizit benannten Test-Key, keinen zufaelligen
  Prozess-Key fuer reproduzierbare Tests.
- Manipulierte, tenant-fremde, filter-fremde, abgelaufene und syntaktisch
  ungueltige Cursor liefern `VALIDATION_ERROR`.
- Unbekannte `version`, fehlende TTL-Felder, `expiresAt <= issuedAt`,
  unbekannte `kid` oder Signatur mit nicht mehr akzeptiertem Key liefern
  `VALIDATION_ERROR`.
- Bestehenden Phase-B-`ResourcesListCursor` entweder dual-read-faehig machen
  oder vor oeffentlicher Veroeffentlichung bewusst mit `VALIDATION_ERROR`
  abweisen und in `spec/mcp-server.md` dokumentieren.

Tests:

- Roundtrip je Cursor-Typ
- HMAC-Tampering
- Tenant-/Filter-/Sortier-Mismatch
- Expiry-Mapping auf `VALIDATION_ERROR`
- fehlende `expiresAt` / unbekannte `version` / unbekannte `kid` liefern
  `VALIDATION_ERROR`
- Key-Rotation: neuer aktiver `kid` signiert neue Cursor, alter Validation-Key
  validiert bestehende Cursor bis TTL-Ablauf, danach nicht mehr
- `resources/list` behaelt family-basierten Walk statt globaler Sortierung

### 10.4 AP D4: Store-/Index-Vertraege fuer Listen

Ziel: Listen-Tools koennen Metadaten aus gemeinsamen Stores lesen, ohne eigene
MCP-Datenmodelle einzufuehren.

Umsetzung:

- Bestehende Store-Ports pruefen und nur dort erweitern, wo Filter oder
  Sortierung fehlen.
- Pro Store einen filterbaren Listenrequest definieren:
  - `JobListFilter`
  - `ArtifactListFilter`
  - `SchemaListFilter`
  - `ProfileListFilter`
  - `DiffListFilter`
  - optional `ConnectionListFilter`
- Filter enthalten nur fachliche Felder aus Abschnitt 6.1 und Abschnitt 6.4.
- In-Memory-Stores implementieren dieselbe Sortierung und Filterlogik wie der
  Port-Vertrag fordert.
- Store-Contract-Tests um Filter, Sortierung und no-oracle Sichtbarkeit
  erweitern.

Tests:

- Default-Sortierung `createdAt DESC`, stabile ID `ASC`
- Zeitfenstergrenzen inkl. Gleichheit am Rand
- Status-/Kind-/Job-Filter
- `pageSize`-Grenzen und fortlaufende Pagination
- keine Secrets in Connection-Indexeintraegen

### 10.5 AP D5: Listen-Tool-Schemas und Projections

Ziel: JSON-Schemas und Projektionen der fuenf Discovery-Tools sind stabil,
typisiert und golden-getestet.

Umsetzung:

- Input-Schemas fuer `job_list`, `artifact_list`, `schema_list`,
  `profile_list`, `diff_list` mit `additionalProperties=false` pruefen und
  ggf. erweitern.
- `tenantId`, `pageSize`, `cursor`, Zeitfenster und resource-spezifische
  Filter exakt dokumentieren.
- Output-Schemas auf typisierte Collection-Felder festlegen:
  `jobs`, `artifacts`, `schemas`, `profiles`, `diffs`.
- `items` in Phase D nicht einfuehren.
- `totalCount` und `totalCountEstimate` optional halten.
- Projection-Funktionen pro Tool einfuehren oder bestehende Projektionen
  erweitern; alle potentiell sensitiven Felder scrubben.

Tests:

- Schema-Golden-Tests fuer Input und Output
- unbekannte Filter -> `VALIDATION_ERROR`
- `limit` wird nicht als Alias akzeptiert
- Collection-Feld heisst korrekt und `nextCursor` ist stabil

### 10.6 AP D6: Discovery-Tool-Handler

Ziel: Die fuenf Listen-Tools laufen fachlich gegen die Store-/Index-Vertraege.

Umsetzung:

- Handler fuer:
  - `job_list`
  - `artifact_list`
  - `schema_list`
  - `profile_list`
  - `diff_list`
- Gemeinsamen Helper fuer Tenant-Auswahl:
  - fehlende `tenantId` -> `principal.effectiveTenantId`
  - gesetzte `tenantId` muss in `allowedTenantIds` liegen
  - kein Cross-Tenant-Fanout
- Gemeinsamen Helper fuer `pageSize`-Obergrenzen und Cursor-Decode.
- Audit um Erfolgs-, Validierungs-, Tenant- und Cursor-Fehler ergaenzen.
- Handler in Tool-Registry von `UNSUPPORTED_TOOL_OPERATION` auf produktive
  Implementierung umhaengen.

Tests:

- jeder Handler mit leerem Store, einem Treffer und mehreren Seiten
- Tenant-Default und explizite Tenant-Auswahl
- principal-gefilterte Ergebnisse ohne Existenzdetails
- Audit-Events fuer Erfolg und Fehler

### 10.7 AP D7: `resources/read`

Ziel: MCP-konformer URI-only Resource-Read fuer alle Phase-D-Resource-Typen.

Umsetzung:

- `ResourcesReadParams` strikt auf `uri` begrenzen; schemafremde Felder
  werden abgewiesen.
- JSON-RPC-Methode an `McpServiceImpl` / Protokollschicht anbinden, sofern
  noch nicht produktiv verdrahtet.
- Handler nutzt ausschliesslich Resource-URI-ADT und Resolver-Dispatcher.
- Rueckgabe fuer JSON-Records als MCP-Text-Content mit `mimeType` /
  Resource-Metadaten gemaess bestehendem Wire-Modell.
- Grosse Inhalte nicht ueber zusaetzliche `resources/read`-Parameter loesen;
  stattdessen Artefaktref oder `nextChunkUri`.
- Inline-Entscheidung strikt ueber `MAX_INLINE_RESOURCE_CONTENT_BYTES`,
  `MAX_RESOURCE_READ_RESPONSE_BYTES` und MIME-Regeln aus Abschnitt 5.2 treffen.
- `dmigrate://capabilities` direkt lesbar machen.

Tests:

- Request mit nur `uri` erfolgreich
- Request mit `cursor`, `range`, `chunkId` oder anderen Zusatzfeldern
  fehlschlaegt
- read fuer Job, Artifact, Schema, Profile, Diff, Connection und Capability
- read fuer Upload-Session-URI im erlaubten Tenant liefert `VALIDATION_ERROR`
  ohne Store-Lookup
- grosse Ressource liefert Referenz/Chunk-URI statt zu grossem Inline-Content
- JSON/Text knapp unterhalb der Inline-Grenze wird inline geliefert; Inhalt
  oberhalb der Grenze und binaere MIME-Typen liefern Referenz/Chunk-URI
- fremder Tenant vs. fehlende Sichtbarkeit korrekt gemappt

### 10.8 AP D8: `resources/list` produktiv verdrahten

Ziel: Der bestehende Resource-Walk liefert echte produktive Ressourcen aus den
Stores und dieselben Resource-URIs wie `resources/read`.

Umsetzung:

- `ResourceStores.empty()` bleibt Test-/Bootstrap-Fallback, aber der normale
  `mcp serve`-Pfad verdrahtet die produktiven Stores.
- `ResourceProjector` auf Abschnitt 6.4 und Secret-Scrubbing pruefen.
- Connection-Projektionen enthalten weder `credentialRef` noch `providerRef`.
- Cursor von `resources/list` kapseln, ohne den Walk
  `JOBS -> ARTIFACTS -> SCHEMAS -> PROFILES -> DIFFS -> CONNECTIONS` zu
  veraendern.
- `resources/templates/list` bleibt statisch bei genau sieben Templates.

Tests:

- Walk ueber alle sechs Familien
- leere Seite mit nicht-null Cursor bleibt erlaubt
- Templates-Golden-Test bleibt bei sieben Eintraegen
- Capability-Resource erscheint nicht in Templates
- `resources/list` und `resources/read` stimmen fuer dieselben URIs ueberein

### 10.9 AP D9: `job_status_get` und `artifact_chunk_get` vereinheitlichen

Ziel: Phase-C-Tool-Pfade verwenden denselben Resource-/Tenant-/Cursor-Vertrag
wie `resources/read`.

Umsetzung:

- `job_status_get` akzeptiert weiterhin `jobId` oder `resourceUri`, normalisiert
  intern aber auf Resource-URI-ADT.
- `artifact_chunk_get` akzeptiert weiterhin den Phase-C-Vertrag, erzeugt
  `nextChunkUri` fuer `resources/read` und optional HMAC-gekapselten
  `nextChunkCursor` fuer Tool-Fortsetzungen.
- Nackte `nextChunkId` nur beibehalten, wenn das bestehende Output-Schema es
  zwingend erfordert; dann als legacy/deprecated markieren und nicht als
  alleinige Fortsetzung verwenden.
- Fehler fuer fremde Tenants, fehlende Sichtbarkeit und manipulierte Chunk-
  Cursor an AP D2/D3 anbinden.

Tests:

- `jobId` und `resourceUri` fuehren zu identischem Status-Resultat
- Chunk-Pagination ueber `nextChunkUri`
- manipulierte Chunk-Cursor -> `VALIDATION_ERROR`
- Retention-geloeschtes Artefakt -> `RESOURCE_NOT_FOUND`

### 10.10 AP D10: Connection-Ref-Bootstrap extrahieren

Ziel: CLI und MCP laden Connection-Refs ueber denselben adapterneutralen
Bootstrap, ohne Secrets in Discovery-Pfade zu materialisieren.

Umsetzung:

- Neuen neutralen Port fuer Connection-Konfiguration definieren, z. B.
  `ConnectionReferenceConfigLoader` oder aequivalent.
- Bestehenden `NamedConnectionResolver` schneiden:
  - Parsing und secret-freie Referenzextraktion in neutrales Modul
  - Secret-/ENV-Expansion nur in autorisierte Runner-/Driver-Pfade
- `ConnectionSecretResolver` als separaten Port fuer spaetere
  connection-backed Ausfuehrungspfade definieren.
- MCP-Bootstrap laedt nur secret-freie `ConnectionReference`-Records in den
  `ConnectionReferenceStore`.
- CLI-Pfade behalten ihr bisheriges Verhalten, nutzen aber den neutralen
  Bootstrap statt eigener Parser-Duplizierung.
- Konfigurationsfehler fuer fehlenden Secret-Provider fail-closed mappen.

Tests:

- YAML/Projektconfig mit Connection-Refs ohne Secret-Ausgabe
- ENV-Platzhalter wird im Discovery-Pfad nicht expandiert
- CLI-Regression fuer bestehende Named-Connection-Aufloesung
- MCP-Connection-Resource ohne `credentialRef`/`providerRef`
- fehlender Provider in connection-backed Pfad -> Konfigurationsfehler

### 10.11 AP D11: Integrationstests fuer stdio und HTTP

Ziel: Discovery und Resources funktionieren ueber beide MCP-Transporte mit
identischem Vertrag.

Umsetzung:

- Test-Seeds fuer Jobs, Artefakte, Schemas, Profile, Diffs und Connections
  bereitstellen.
- stdio-Suite:
  - `tools/list`
  - fuenf Listen-Tools
  - `resources/list`
  - `resources/templates/list`
  - `resources/read`
- HTTP-Suite mit Auth/Scopes:
  - `dmigrate:read` erfolgreich
  - fehlender Scope -> bestehendes Scope-Fehlermapping
  - fremder Tenant -> `TENANT_SCOPE_DENIED`
- Negative Tests fuer manipulierte Cursor und secret-scrubbed Payloads.

Tests:

- Transport-Suites laufen gegen dieselben Fixtures
- JSON-Schema- und Golden-Outputs bleiben stabil
- keine Payload enthaelt Test-Secrets, JDBC-Passwoerter oder expandierte ENV-
  Werte

### 10.12 AP D12: Dokumentation und Statusnachzug

Ziel: Nach Phase D sind Spezifikation, Nutzer-Doku und Implementierungsplan
konsistent.

Umsetzung:

- `spec/mcp-server.md` um produktive Discovery-Tools, `resources/read`,
  Cursor-Kapselung und `dmigrate://capabilities`-Discovery ergaenzen.
- `spec/ki-mcp.md` nur dort anpassen, wo Phase-D-Entscheidungen vom
  uebergeordneten Zielbild abweichen oder praezisiert wurden.
- CLI-/MCP-Doku fuer Connection-Ref-Bootstrap und Secret-Grenzen ergaenzen.
- Implementierungsstatus dieses Dokuments nach Abschluss auf
  `Implementiert` setzen und offene Nacharbeiten explizit listen.
- Falls Phase-B-Cursor nicht dual-read-faehig bleiben, den Breaking-Change
  mit Datum und Begruendung dokumentieren.

Tests/Gates:

- `./gradlew :adapters:driving:mcp:test`
- `./gradlew :adapters:driving:cli:test`
- betroffene Store-/Core-Tests
- vorhandene stdio+HTTP-Integrationstests aus Phase C/D

---

## 11. Abnahmekriterien

- Listen liefern stabile typisierte Collection-Felder (`jobs`, `artifacts`,
  `schemas`, `profiles`, `diffs`) und `nextCursor`.
- Fachliche Listen-Tools verwenden eine dokumentierte deterministische
  Sortierung, mindestens `createdAt DESC`, `id ASC`.
- Cursor fachlicher Listen-Tools sind an Tenant, Filter, `pageSize`, Sortierung
  und letzte Sort-Keys gebunden.
- Produktive fachliche Listen-Cursor, `resources/list`-Cursor und
  Chunk-Cursor enthalten verpflichtend `version`, `kid`, `issuedAt` und
  `expiresAt`; fehlende oder ungueltige Felder liefern `VALIDATION_ERROR`.
- Cursor-Key-Rotation ist ueber `kid` und HMAC-Keyring getestet: neue Cursor
  werden mit dem aktiven Key signiert, alte Cursor bleiben nur bis zur
  konfigurierten TTL plus Clock-Skew ueber Validation-Keys gueltig.
- `resources/list` folgt dem Phase-B-Resource-Walk und setzt keine globale
  `createdAt`-Sortierung ueber alle Resource-Familien voraus.
- `tenantId` bleibt in fachlichen Listen-Tool-Schemas erhalten; es ist
  adressierend, nicht autorisierend, und tenant-fremde Werte liefern
  `TENANT_SCOPE_DENIED`.
- Multi-Tenant-Clients muessen den gewuenschten Tenant pro Request ueber
  `tenantId` waehlen; fehlt `tenantId`, gilt ausschliesslich der
  deterministische `principal.effectiveTenantId`.
- `totalCount` ist exakt, wenn vorhanden.
- `totalCountEstimate` ist optional und als Naeherung gekennzeichnet.
- modifizierte oder fremde Cursor-/Pagination-Token liefern
  `VALIDATION_ERROR`, niemals fremde Tenant-Daten.
- abgelaufene Cursor-/Pagination-/Chunk-Tokens liefern ebenfalls
  `VALIDATION_ERROR`; nur nach Retention geloeschte Ressourcen liefern
  `RESOURCE_NOT_FOUND`.
- kombinierte Fehlerfaelle folgen der Praezedenz aus Abschnitt 4.2: explizit
  adressierter fremder Tenant schlaegt Cursor-Decode und liefert
  `TENANT_SCOPE_DENIED`; nach erfolgreicher Tenant-Aufloesung liefern alle
  Cursor-Bindungsfehler `VALIDATION_ERROR`.
- diese Fehlerpraezedenz wird ueber einen zentralen, golden-getesteten Error-
  Resolver umgesetzt; einzelne Handler duerfen keine eigene Reihenfolge
  implementieren.
- Profile und Diffs sind ueber `profile_list` bzw. `diff_list` auffindbar,
  auch wenn die Persistenz intern ueber typisierte Artefakte erfolgt.
- Ressourcen ausserhalb des erlaubten Principal-Tenant-Scopes liefern
  `TENANT_SCOPE_DENIED`; Ressourcen im erlaubten Tenant ohne Sichtbarkeit fuer
  den Principal liefern no-oracle `RESOURCE_NOT_FOUND`.
- sensitive Connection-Refs liefern nur secret-freie `allowedOperations`- und
  Policy-Hinweise; Phase D implementiert dadurch keine Start-Tools fuer
  `schema_compare_start`, Reverse, Import oder Transfer.
- Connection-Refs koennen in Tests aus dokumentierten Seeds geladen werden,
  ohne Secrets in MCP-Payloads zu uebergeben.
- reale connection-backed Pfade funktionieren nur mit dokumentiertem
  `ConnectionSecretResolver`.
- fehlt dieser Provider, liefern connection-backed Pfade einen fail-closed
  Konfigurationsfehler statt teilweise expandierter Secret-URLs.
- CLI- und MCP-Startpfade nutzen dieselbe adapterneutrale
  Config-/Connection-Ref-Aufloesung.
- ungueltige Cursor liefern `VALIDATION_ERROR`.
- `resources/list` nutzt dieselben Resolver-/Tenant-Regeln wie
  `resources/read`.
- `ResourceKind.UPLOAD_SESSIONS` ist in Phase D zwar parsebar, aber fuer MCP-
  Resources blockiert: nicht listbar, kein Template, kein `resources/read` und
  kein Store-Lookup; bei erlaubtem Tenant liefert `resources/read` dafuer
  `VALIDATION_ERROR`.
- `resources/templates/list` bleibt statisch, principal-unabhaengig, ohne
  Resource-Aufloesung und beim Phase-B-Vertrag mit genau sieben Templates;
  `dmigrate://capabilities` ist dort kein zusaetzliches Template.
- `resources/read` akzeptiert nur `uri`, nutzt Resolver-Dispatch, liefert
  grosse Inhalte nur ueber Artefaktrefs oder Chunk-URIs und ist mit
  Adaptertests abgedeckt.
- `resources/read` liefert nur textuelle Inhalte inline, wenn der Body
  hoechstens `MAX_INLINE_RESOURCE_CONTENT_BYTES = 49152` Bytes umfasst und
  die serialisierte Antwort hoechstens
  `MAX_RESOURCE_READ_RESPONSE_BYTES = 65536` Bytes umfasst; binaere oder
  unbekannte MIME-Typen werden nicht inline base64-kodiert.
- Chunk-Fortsetzungen werden als tenant-scoped `nextChunkUri` oder als
  HMAC-gekapselter `nextChunkCursor` ausgegeben; nackte `chunkId`-Cursor sind
  nicht erlaubt.
- `resources/read(dmigrate://capabilities)` liefert die globale, secret-freie
  Faehigkeitsbeschreibung ueber einen expliziten Capability-Resolver.
- Clients entdecken `dmigrate://capabilities` ueber den dokumentierten
  0.9.6-Vertrag, `spec/mcp-server.md` oder
  `capabilities_list.resourceFallbackHint`, nicht ueber
  `resources/templates/list`.
- Initialize meldet fuer `resources` explizit `listChanged=false` und
  `subscribe=false`.
- nicht sichtbare bzw. nicht autorisierte Ressourcen im erlaubten Tenant
  liefern no-oracle `RESOURCE_NOT_FOUND`, sofern kein Admin-/Scope-Modell den
  Zugriff erlaubt.
- keine Discovery-, Resource- oder Audit-Antwort enthaelt rohe Secrets.
- v1-Listen-Projektionen enthalten keine rohe `ownerPrincipalId`; sie nutzen
  eine neutrale `visibilityClass`, sofern Besitz-/Sichtbarkeitsinformation fuer
  Clients relevant ist.
- MCP-Projektionen von Connection-Refs enthalten weder `credentialRef` noch
  `providerRef`; erlaubt sind nur secret-freie Ersatzfelder wie
  `hasCredential`, `providerKind`, `sensitivity` und `allowedOperations`.
- tenantlose Resource-URIs werden nicht akzeptiert, mit genau einer
  Ausnahme: `dmigrate://capabilities`.
- Artifact-Chunk-URIs werden als eigene verschachtelte Resource-URI-Variante
  geparst und getestet.

---

## 12. Anschluss an Phase E

Phase E darf auf Phase D aufbauen, indem sie langlaufende Jobs,
Start-Tools, Cancel-Gates, Approval-Grants und Upload-Intents fuer
policy-pflichtige Operationen an dieselben Resource-, Job-, Artefakt- und
Connection-Ref-Vertraege anbindet.

Phase E darf keine eigenen Resource-URI- oder Connection-Ref-Modelle
einfuehren. Wenn Start-Tools zusaetzliche Metadaten brauchen, werden die in
Phase D eingefuehrten Store-/Index-Vertraege erweitert.
