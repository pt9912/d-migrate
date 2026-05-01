# Implementierungsplan: 0.9.6 - Phase D `Discovery und Ressourcen`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: D (`Discovery und Ressourcen`)
> **Status**: Draft (2026-05-01)
> **Referenz**: `docs/implementation-plan-0.9.6.md` Abschnitt 1 bis 7,
> Abschnitt 8 Phase D, Abschnitt 9.1, Abschnitt 9.2, Abschnitt 9.3,
> Abschnitt 9.4, Abschnitt 11 und Abschnitt 12;
> `docs/ImpPlan-0.9.6-A.md`; `docs/ImpPlan-0.9.6-B.md`;
> `docs/ImpPlan-0.9.6-C.md`; `docs/ki-mcp.md`;
> `docs/job-contract.md`; `docs/architecture.md`; `docs/design.md`;
> `docs/cli-spec.md`; `hexagon/application`; `hexagon/ports-common`;
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
- Resource-Resolver fuer:
  - Jobs
  - Artefakte
  - Schemas
  - Profile
  - Diffs
  - Connection-Refs
- cursorbasierte Paginierung mit validierten, gekapselten Tokens
- tenant-sichere Resource-Aufloesung
- adapterneutrale Connection-Ref-Konfiguration fuer CLI und MCP
- Secret-freier Bootstrap von Connection-Refs

Nach Phase D soll gelten:

- Clients koennen vorhandene Ressourcen paginiert entdecken.
- Clients koennen Resource-URIs aus Discovery-Antworten gezielt lesen.
- grosse Inhalte werden ueber Chunk-Templates oder Artefaktreferenzen
  adressiert, nicht inline in Discovery-Antworten ausgeliefert.
- fremde Tenant-Ressourcen werden nicht sichtbar und nicht durch Details
  bestaetigt.
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
- ablauf- oder versionsfaehig, falls der Store das benoetigt

Manipulierte Tokens liefern `VALIDATION_ERROR`, niemals fremde Daten.

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
| Schema | `dmigrate://tenants/{tenantId}/schemas/{schemaRef}` | Schema-Metadaten oder Inhalt bei kleiner Groesse |
| Profile | `dmigrate://tenants/{tenantId}/profiles/{profileRef}` | Profiling-Metadaten oder Artefaktref |
| Diff | `dmigrate://tenants/{tenantId}/diffs/{diffRef}` | Diff-Metadaten oder Artefaktref |
| Connection | `dmigrate://tenants/{tenantId}/connections/{connectionRef}` | secret-freie Connection-Metadaten |
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

### 5.2 Resource-Read-Grenzen

Resource-Reads muessen dieselben Inline-Grenzen beachten wie Tool-Responses.

Kleine textuelle Ressourcen duerfen als MCP-Text-Content geliefert werden.
Groessere Ressourcen liefern:

- Artefaktref
- Chunk-Template
- naechsten Chunk-Cursor
- Groessen- und Hash-Metadaten, soweit vorhanden

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

- `RESOURCE_NOT_FOUND` fuer nicht existente, abgelaufene oder fuer den
  Principal nicht sichtbare bzw. nicht autorisierte Ressourcen im erlaubten
  Tenant
- `TENANT_SCOPE_DENIED` fuer fremde Ressourcen, sofern der Pfad den Tenant-
  Scope verletzt
- `VALIDATION_ERROR` fuer syntaktisch ungueltige Resource-URIs, Cursor oder
  Filter
- `AUTH_REQUIRED` / `FORBIDDEN` gemaess bestehendem Auth-/Scope-Mapping

Direkte `resources/read`-Zugriffe auf fremde Jobs, Artefakte oder andere
Ressourcen im erlaubten Tenant werden no-oracle wie nicht vorhandene Ressourcen
behandelt, ausser ein explizites Admin-/Scope-Modell erlaubt den Zugriff.
Fehler duerfen keine fremden Ressourcendetails leaken.

---

## 6. Discovery-Tools

### 6.1 Gemeinsame Parameter

Alle Listen-Tools verwenden konsistente Parameter:

- `pageSize`
- `cursor`
- `type` oder resource-spezifischer Filter, falls sinnvoll
- `createdAfter`
- `createdBefore`
- `status`, wo fachlich passend
- `jobId`, wo Artefakte/Profile/Diffs an Jobs gekoppelt sind

Filter werden strikt validiert. Unbekannte Filter liefern `VALIDATION_ERROR`.

### 6.2 Gemeinsame Antwortform

Listen-Antworten enthalten:

- `items`
- `nextCursor`
- optional `totalCount`
- optional `totalCountEstimate`

`totalCount` ist exakt, wenn vorhanden. `totalCountEstimate` muss als
Naeherung gekennzeichnet sein.

Fachliche Listen-Tools (`job_list`, `artifact_list`, `schema_list`,
`profile_list`, `diff_list`) verwenden eine deterministische
Standardsortierung. Default:

- primaer `createdAt DESC`
- sekundaer stabile ID `ASC`

Resource-spezifische Sortierungen duerfen nur ergaenzt werden, wenn sie
dokumentiert und getestet sind. Cursor muessen an Tenant, Filter, `pageSize`,
Sortierung und letzte Sort-Keys gebunden sein. Dadurch bleiben `items` und
`nextCursor` auch bei gleichzeitigen Inserts/Deletes stabil genug, um keine
Duplikate oder Luecken durch nichtdeterministische Reihenfolge zu erzeugen.

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
7-Template-Liste nicht gebrochen wird.

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

Fehlt ein dokumentierter Credential-/Secret-Provider fuer connection-backed
Pfade, muss fail-closed mit Konfigurationsfehler geantwortet werden.

---

## 9. Sicherheits- und Tenant-Regeln

Verbindliche Regeln:

- Jede Resource-Aufloesung prueft Tenant-Scope.
- Jede Listen-Antwort ist principal-gefiltert.
- Cursor duerfen nicht tenant- oder filteruebergreifend wiederverwendbar sein.
- Sensitive Connection-Refs liefern nur Policy-Hinweise und
  `allowedOperations`-Metadaten, keine Secrets und keine ausfuehrbaren
  Start-Tool-Vertraege.
- Resource-Fehler leaken keine fremden Details.
- Audit-Events entstehen auch fuer Validierungs-, Tenant-, Auth- und
  Cursor-Fehler.

---

## 10. Umsetzungsschritte

### 10.1 Resource-URI- und Resolver-Vertrag definieren

- Resource-Typen und URI-Templates festlegen.
- Parser und Validator fuer Resource-URIs implementieren.
- `ServerResourceUri` oder ein Nachfolgemodell um eine explizite
  `ArtifactChunkResourceUri(tenantId, artifactId, chunkId)`-Variante
  erweitern.
- `dmigrate://capabilities` als explizite globale Sonderresource mit eigenem
  Resolver modellieren.
- Fehler-Mapping fuer ungueltige, fehlende und fremde Ressourcen definieren.

### 10.2 Store-/Index-Abstraktionen erweitern

- Job-, Artifact- und Schema-Stores um Listen-/Index-Funktionen erweitern.
- Profile und Diffs als eigene Index-Sicht oder typisierte Artefakte
  modellieren.
- Connection-Ref-Store einfuehren.

### 10.3 Pagination einfuehren

- `PageRequest` / `PageResult` im gemeinsamen Vertrag nutzen oder ergaenzen.
- `pageSize`-Obergrenzen definieren.
- Cursor-Kapselung im MCP-Adapter implementieren.
- deterministische Standardsortierung (`createdAt DESC`, `id ASC`) und
  Cursor-Bindung an Tenant, Filter, `pageSize`, Sortierung und letzte Sort-Keys
  fuer fachliche Listen-Tools implementieren.
- `resources/list` beim Phase-B-Resource-Walk
  `JOBS -> ARTIFACTS -> SCHEMAS -> PROFILES -> DIFFS -> CONNECTIONS`
  belassen und den family-basierten Cursor (`kind`, `innerToken`) nur
  kapseln, nicht durch eine globale `createdAt`-Sortierung ersetzen.
- Kompatibilitaetspfad fuer Phase-B-Cursor planen: bestehende
  Base64-JSON-Cursor (`kind`, `innerToken`) duerfen fuer Phase-B-
  Entwicklungsdaten entweder weiter gelesen und in neue HMAC-gekapselte
  Cursor umgeschrieben oder mit klarer `VALIDATION_ERROR` plus
  dokumentiertem Breaking-Change abgewiesen werden. Vor einem oeffentlichen
  Adaptervertrag ist die zweite Variante erlaubt; nach Veroeffentlichung ist
  ein Migrations-/Dual-Read-Pfad Pflicht.
- Negative Tests fuer manipulierte Cursor schreiben.

### 10.4 Discovery-Tools implementieren

- `job_list`
- `artifact_list`
- `schema_list`
- `profile_list`
- `diff_list`
- `job_status_get` und `artifact_chunk_get` an denselben Resolver-Vertrag
  anbinden.

### 10.5 MCP-Standard-Discovery und Resource-Reads anbinden

- `resources/list`
- `resources/templates/list`
- `resources/read` als URI-only Handler anbinden.
- Resolver-Dispatch fuer `resources/read` ueber dasselbe URI-Modell wie
  `resources/list` implementieren.
- Fehler-Mapping fuer `resources/read` mit Discovery, Tenant-Scope und
  no-oracle `RESOURCE_NOT_FOUND` synchronisieren.
- Initialize-Capability pruefen.
- Resource-Registry mit produktiven Resolvern verbinden.

### 10.6 Connection-Ref-Bootstrap extrahieren

- adapterneutralen Port definieren.
- CLI-Named-Connection-Parsing splitten.
- MCP-Bootstrap an gemeinsamen Port anbinden.
- Secret-Aufloesung in autorisierte Ausfuehrungspfade verschieben.

### 10.7 Tests und Dokumentation

- Unit-Tests fuer Parser, Cursor, Filter und Resolver.
- Adaptertests fuer MCP-Tools, `resources/list`, `resources/templates/list` und
  `resources/read`.
- Tenant-Scope- und Secret-Scrubbing-Tests.
- Doku fuer Resource-URIs, Cursor und Connection-Refs ergaenzen.

---

## 11. Abnahmekriterien

- Listen liefern stabile `items` und `nextCursor`.
- Fachliche Listen-Tools verwenden eine dokumentierte deterministische
  Sortierung, mindestens `createdAt DESC`, `id ASC`.
- Cursor fachlicher Listen-Tools sind an Tenant, Filter, `pageSize`, Sortierung
  und letzte Sort-Keys gebunden.
- `resources/list` folgt dem Phase-B-Resource-Walk und setzt keine globale
  `createdAt`-Sortierung ueber alle Resource-Familien voraus.
- `totalCount` ist exakt, wenn vorhanden.
- `totalCountEstimate` ist optional und als Naeherung gekennzeichnet.
- modifizierte oder fremde Cursor-/Pagination-Token liefern
  `VALIDATION_ERROR`, niemals fremde Tenant-Daten.
- Profile und Diffs sind ueber `profile_list` bzw. `diff_list` auffindbar,
  auch wenn die Persistenz intern ueber typisierte Artefakte erfolgt.
- fremde Tenant-Ressourcen liefern `TENANT_SCOPE_DENIED`.
- sensitive Connection-Refs liefern nur secret-freie `allowedOperations`- und
  Policy-Hinweise; Phase D implementiert dadurch keine Start-Tools fuer
  `schema_compare_start`, Reverse, Import oder Transfer.
- Connection-Refs koennen in Tests aus dokumentierten Seeds geladen werden,
  ohne Secrets in MCP-Payloads zu uebergeben.
- reale connection-backed Pfade funktionieren nur mit dokumentiertem
  Credential-/Secret-Provider.
- fehlt dieser Provider, liefern connection-backed Pfade einen fail-closed
  Konfigurationsfehler statt teilweise expandierter Secret-URLs.
- CLI- und MCP-Startpfade nutzen dieselbe adapterneutrale
  Config-/Connection-Ref-Aufloesung.
- ungueltige Cursor liefern `VALIDATION_ERROR`.
- `resources/list` nutzt dieselben Resolver-/Tenant-Regeln wie
  `resources/read`.
- `resources/templates/list` bleibt statisch, principal-unabhaengig, ohne
  Resource-Aufloesung und beim Phase-B-Vertrag mit genau sieben Templates;
  `dmigrate://capabilities` ist dort kein zusaetzliches Template.
- `resources/read` akzeptiert nur `uri`, nutzt Resolver-Dispatch, liefert
  grosse Inhalte nur ueber Artefaktrefs oder Chunk-URIs und ist mit
  Adaptertests abgedeckt.
- `resources/read(dmigrate://capabilities)` liefert die globale, secret-freie
  Faehigkeitsbeschreibung ueber einen expliziten Capability-Resolver.
- Initialize meldet fuer `resources` explizit `listChanged=false` und
  `subscribe=false`.
- nicht sichtbare bzw. nicht autorisierte Ressourcen im erlaubten Tenant
  liefern no-oracle `RESOURCE_NOT_FOUND`, sofern kein Admin-/Scope-Modell den
  Zugriff erlaubt.
- keine Discovery-, Resource- oder Audit-Antwort enthaelt rohe Secrets.
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
