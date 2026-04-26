# Implementierungsplan: 0.9.6 - Phase B `MCP-Modul und Transport`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: B (`MCP-Modul und Transport`)
> **Status**: Draft (2026-04-26)
> **Referenz**: `docs/implementation-plan-0.9.6.md` Abschnitt 1 bis 5,
> Abschnitt 8 Phase B, Abschnitt 9.2, Abschnitt 9.3, Abschnitt 9.4,
> Abschnitt 11 und Abschnitt 12; `docs/ImpPlan-0.9.6-A.md`;
> `docs/ki-mcp.md`; `docs/job-contract.md`; `docs/architecture.md`;
> `docs/design.md`; `docs/rest-service.md`; `docs/grpc-service.md`;
> `docs/cli-spec.md`; `hexagon/application`; `hexagon/ports-read`;
> `hexagon/ports-write`; `hexagon/profiling`; `adapters/driving/cli`.

---

## 1. Ziel

Phase B fuehrt den MCP-Driving-Adapter als eigenes Gradle-Modul ein und
macht den Server ueber `stdio` und streambares HTTP initialisierbar. Sie
implementiert die Transport-, Auth-, Bootstrap-, Registry- und Discovery-
Grundlagen, auf denen die konkreten Tool-Handler der spaeteren Phasen
laufen.

Phase B liefert konkrete technische Ergebnisse:

- neues Modul `adapters:driving:mcp`
- MCP-Server-Bootstrap fuer `stdio`
- MCP-Server-Bootstrap fuer streambares HTTP
- Initialize-Vertrag fuer MCP `protocolVersion` `2025-11-25`
- klare Trennung zwischen MCP-Protokollversion und d-migrate Tool-/
  Resource-Vertragsversion `v1`
- Streamable-HTTP-Endpunkt mit MCP-konformen `POST`-, `GET`- und
  `DELETE`-Regeln
- HTTP-Authorization fuer nicht-lokale HTTP-Transporte gemaess MCP
  2025-11-25
- Protected Resource Metadata und `WWW-Authenticate`-Challenges
- Principal-Ableitung fuer lokale und HTTP-Transporte
- stdio-Tokenvalidierung ohne stillen lokalen Default-Principal
- gemeinsame Runtime-Bootstrap-Funktion fuer Driver, Profiling-Adapter,
  Streaming-Adapter und Schema-Codecs
- Tool-Registry und Resource-Registry als Transport-unabhaengige
  Adapterbausteine
- MCP-Standard-Discovery fuer `resources/list` und
  `resources/templates/list`
- JSON-Schema-Definitionen fuer alle 0.9.6-Tools mit JSON Schema 2020-12
- lokale Start- und Konfigurationsdokumentation

Nach Phase B soll gelten:

- ein MCP-Client kann den Server ueber `stdio` und HTTP initialisieren
- beide Transporte verwenden dieselbe Tool-Registry und Resource-Registry
- Transportfehler, Auth-Fehler und fachliche Toolfehler sind sauber
  getrennt
- nicht-lokaler HTTP-Betrieb ist fail-closed, solange keine gueltige Auth-
  Konfiguration vorhanden ist
- `capabilities_list` kann ohne Datenbankverbindung funktionieren
- spaetere Phasen koennen Tool-Handler anbinden, ohne den Transportkern zu
  veraendern

Wichtig: Phase B implementiert die MCP-Modul- und Transportbasis. Sie muss
keine vollstaendigen read-only Tools, Start-Tools, Upload-Flows,
Policy-Grant-Aussteller, KI-Tools oder Prompts fertigstellen. Wo diese
spaeteren Tools bereits in Registries oder Schemas sichtbar werden, muessen
sie stabil beschrieben, aber nicht fachlich lauffaehig sein.

---

## 2. Ausgangslage

### 2.1 Phase A stellt den gemeinsamen Kern bereit

Phase A definiert den adapterneutralen Serverkern:

- `PrincipalContext`
- `ServerResourceUri`
- Job-, Artefakt-, Upload-, Approval- und Execution-Modelle
- Store-Interfaces fuer Jobs, Artefakte, Uploads, Connections,
  Idempotency, Approval, Quotas und Audit
- Error-Mapping fuer Anwendungsausnahmen und Validierungsfehler
- Audit- und Secret-Scrubbing-Grundlagen
- File-Spooling-Vertrag fuer Upload- und Artefaktbytes

Phase B darf diese Modelle nutzen, aber nicht MCP-spezifisch umformen. Wenn
Transportcode ein anderes Format benoetigt, entsteht eine Adapterprojektion
im MCP-Modul.

### 2.2 MCP-Zielbild ist normativ dokumentiert

`docs/ki-mcp.md` beschreibt das Zielbild fuer Tools, Ressourcen, Fehler,
Idempotency, Approval, Auth, Audit, Uploads und Prompts. Phase B setzt daraus
die transportnahen Teile um:

- MCP Initialize
- Capabilities
- Tool- und Resource-Discovery
- `stdio`
- streambares HTTP
- Protected Resource Metadata
- Auth-/Scope-Challenges
- JSON-Schema-Vertrag

Die fachliche Ausfuehrung einzelner Tools wird in spaeteren Phasen
erganzt.

### 2.3 Existing CLI-Bootstrap darf nicht kopiert werden

Die CLI initialisiert bereits Driver, Profiling, Streaming und Schema-Codecs.
Phase B muss diese Initialisierung teilen oder aequivalent kapseln.

Nicht erlaubt:

- Driver lazy per Klassenname aus Tool-Handlern laden
- CLI-Code unveraendert aus dem MCP-Adapter heraus aufrufen, wenn dadurch
  Prozess-Exit, direkte Console-Ausgabe oder Secret-Materialisierung in
  Discovery-Pfaden entsteht
- eine zweite, divergierende Registry nur fuer MCP pflegen

---

## 3. Scope fuer Phase B

### 3.1 In Scope

- Gradle- und Modulstruktur:
  - `settings.gradle.kts` um `include("adapters:driving:mcp")` erweitern
  - `adapters/driving/mcp/build.gradle.kts` anlegen
  - Runtime-Dependencies fuer benoetigte Driver-, Profiling-, Streaming-
    und Format-/Codec-Module konfigurieren
  - Test-Dependencies fuer Adapter- und Integrationstests konfigurieren
- Server-Bootstrap:
  - `stdio` Startpfad
  - HTTP Startpfad
  - gemeinsame Server-Konfiguration
  - gemeinsame Registry- und Runtime-Initialisierung
  - klare Startfehler fuer unsichere HTTP-Konfiguration
- MCP Initialize:
  - `protocolVersion` `2025-11-25`
  - korrekte Capabilities
  - d-migrate Vertragsversion `v1` nur als Tool-/Resource-Version
  - kein Vermischen von MCP-Protokollversion und Produktvertrag
- Streamable HTTP:
  - ein MCP-Endpunkt fuer JSON-RPC-Nachrichten
  - robuste `Accept`-Header-Auswertung
  - `MCP-Protocol-Version` fuer Folgeaufrufe
  - `Origin`-Validierung
  - optionales `MCP-Session-Id`
  - definierte `POST`-, `GET`- und `DELETE`-Semantik
- HTTP-Authorization:
  - Bearer-Token-Pflicht fuer nicht-lokales HTTP
  - `WWW-Authenticate` mit `resource_metadata`
  - Protected Resource Metadata Endpoint
  - Issuer-, JWKS- oder Introspection-Vertrag
  - Algorithmus-Allowlist
  - Audience-/Resource-Pruefung
  - Ablauf-/Clock-Skew-Pruefung
  - Scope-Mapping fuer Tool- und Resource-Klassen
- stdio-Authorization:
  - validierte Host-Attestation, wenn verfuegbar
  - sonst `DMIGRATE_MCP_STDIO_TOKEN`
  - Token-Fingerprint-Lookup oder lokal signierte Tokens
  - deterministisches Mapping auf Principal, Tenant, Scopes, Admin-Status,
    Ablaufzeit und Audit-Identitaet
- Adapter-Registries:
  - Tool-Registry
  - Resource-Registry
  - JSON-Schema-Registry
  - Prompt-Registry nur als spaeter erweiterbarer Stub, falls fuer
    Capabilities noetig
- MCP-Standard-Discovery:
  - `resources/list`
  - `resources/templates/list`
  - tenant-/principal-gefilterte Resource-Projektionen
  - MCP-konformes `nextCursor`
  - keine Subscribe-/`listChanged`-Flags, solange diese nicht
    implementiert sind
- Tool-Schema-Vertrag:
  - `inputSchema` und `outputSchema` fuer alle 0.9.6-Tools
  - JSON Schema 2020-12
  - Golden-Test fuer `$schema`, falls der Generator dieses Feld ausgibt
- Fehler- und Wire-Mapping:
  - unbekannte Toolnamen als MCP-/JSON-RPC-Protokollfehler
  - bekannte, fachlich nicht unterstuetzte Operationen als
    `UNSUPPORTED_TOOL_OPERATION`
  - Resource-Fehler als JSON-RPC-Errors, nicht als `tools/call`
    `isError=true`
- Dokumentation:
  - Startbefehl fuer `stdio`
  - Startbefehl fuer HTTP
  - lokale Demo-/Testkonfiguration
  - Auth-Konfigurationspflicht fuer nicht-lokales HTTP

### 3.2 Bewusst nicht Teil von Phase B

- produktive Implementierung aller read-only Tool-Handler **ausser
  `capabilities_list`** (siehe §12.11 — Phase B liefert
  `capabilities_list` als reine Registry-Projektion ohne
  Store-/Driver-Anbindung)
- fachliche Implementierung von `schema_validate`, `schema_generate` oder
  `schema_compare`
- Start-Tools wie `schema_reverse_start`, `schema_compare_start` oder
  `data_profile_start`
- Upload-Tools und Upload-Session-Zustandsmaschine
- `job_cancel`
- Policy-Grant-Aussteller fuer produktive Freigaben
- Import und Transfer
- KI-nahe Tools und MCP-Prompts
- SSE-basierte Notifications, Resource-Subscriptions oder
  Push-Notifications
- vollstaendige REST- oder gRPC-Implementierung
- Mandanten-Admin, OAuth-Authorization-Server oder Dynamic Client
  Registration

Praezisierung:

Phase B loest "wie wird der MCP-Server sicher und testbar transportiert?",
nicht "welche fachlichen Operationen sind bereits vollstaendig verfuegbar?".

---

## 4. Leitentscheidungen fuer Phase B

### 4.1 MCP bleibt ein Driving-Adapter

Das neue Modul heisst verbindlich `adapters:driving:mcp`.

Der Adapter verantwortet:

- MCP-Transportintegration
- JSON-RPC- und MCP-Wire-Mapping
- Tool- und Resource-Definitionen
- Payload-Schema-Validierung
- Principal-Ableitung aus Transportkontext
- Uebersetzung zwischen MCP-Payloads und Anwendungskern
- Antwortzuschnitt fuer Agenten

Der Adapter verantwortet nicht:

- fachliche Neuimplementierung bestehender Runner
- eigene Job- oder Artefaktmodelle
- eigene Secret- oder Connection-Resolver ohne Kernvertrag
- eigene Tenant-Logik neben `PrincipalContext`

### 4.2 `stdio` ist lokal, aber nicht automatisch vertrauenswuerdig

`stdio` ist der primaere lokale Agentenpfad. Trotzdem muss jeder Aufruf
einen validierten `PrincipalContext` erhalten.

Erlaubte Principal-Quellen:

- validierte Host-Attestation ueber konfigurierte Registry
- `DMIGRATE_MCP_STDIO_TOKEN` mit Fingerprint-Lookup gegen lokale oder
  konfigurierte Token-Registry
- lokal signierte Tokens mit Signatur-, Issuer-, Audience-, Ablauf- und
  Key-ID-Pruefung
- expliziter Test-Principal nur in Testkonfiguration

Nicht erlaubt:

- OS-Username als Principal
- Parent-Prozessdaten als Principal
- Prozess-ID, Arbeitsverzeichnis oder ungepruefte ENV-Werte als Principal
- roher `DMIGRATE_MCP_STDIO_TOKEN` im Audit
- stiller lokaler Default-Principal

Wenn kein Principal ableitbar ist, liefern Tool- und Resource-Aufrufe
`AUTH_REQUIRED`.

### 4.3 Nicht-lokales HTTP ist fail-closed

Streambares HTTP darf ohne Auth nur fuer lokale Tests und Demos starten.

Auth-Deaktivierung ist nur zulaessig, wenn:

- die Konfiguration explizit unsicher markiert ist
- der Server ausschliesslich an `127.0.0.1` oder `::1` bindet
- keine oeffentliche Base-URL konfiguriert ist
- keine nicht-lokale Bind-Adresse wie `0.0.0.0` genutzt wird

Andernfalls muss der Serverstart mit klarem Konfigurationsfehler
abbrechen.

### 4.4 HTTP-Authorization folgt MCP 2025-11-25

Fuer nicht-lokales streambares HTTP gilt:

- fehlendes, ungueltiges oder abgelaufenes Token liefert HTTP 401
- 401 enthaelt `WWW-Authenticate: Bearer ...`
- die Challenge enthaelt `resource_metadata`
- unzureichende Scopes liefern HTTP 403 mit Scope-Challenge
- Access Tokens duerfen nicht aus Query-Parametern gelesen werden
- Protected Resource Metadata wird ueber eine well-known URI oder die in
  der Challenge referenzierte URL angeboten

Das Protected-Resource-Metadata-Dokument enthaelt mindestens:

- `resource`: kanonische HTTPS-URI des MCP-Servers bzw. MCP-Endpunkts
- `authorization_servers`: nicht-leere Liste zulaessiger Issuer-URIs
- `scopes_supported`: dokumentierte d-migrate Scope-Namen

Tokenvalidierung ist fail-closed:

- konfigurierte Issuer muessen exakt zu Token-`iss` und Metadata passen
- JWKS ist der Default
- Introspection ist erlaubt, wenn explizit konfiguriert
- fehlende JWKS-/Introspection-Konfiguration ist ein Start- oder
  Auth-Fehler
- Netzwerkfehler, Parsefehler, unbekannte `kid`, inaktive Tokens und
  nicht erlaubte Algorithmen werden abgewiesen
- `aud` oder OAuth-Resource-Indicator muss den MCP-Server bzw. Endpunkt
  adressieren

### 4.5 Scope-Namen bleiben rollenartig

Phase B implementiert die transportseitige Scope-Tabelle und
veroeffentlicht sie in Protected Resource Metadata. Diese Tabelle beschreibt
den vollstaendigen 0.9.6-Scope-Vertrag, nicht automatisch die in Phase B
bereits fachlich implementierten Tools.

Verbindliche Scope-Namen:

- `dmigrate:read`
- `dmigrate:job:start`
- `dmigrate:artifact:upload`
- `dmigrate:data:write`
- `dmigrate:job:cancel`
- `dmigrate:ai:execute`
- `dmigrate:admin`

Granulare `dmigrate:tool:<name>`-Scopes werden in 0.9.6 nicht eingefuehrt.
Challenges nennen immer den fuer den konkreten Request minimal notwendigen
Scope.

Die Aufnahme von `dmigrate:job:cancel` bereitet die Auth- und Challenge-
Semantik fuer Phase E0/E vor. `job_cancel` selbst bleibt gemaess Abschnitt
3.2 bewusst ausserhalb der Phase-B-Funktionsimplementierung.

### 4.6 Streamable HTTP ist Transport, kein Tool-Vertrag

HTTP-Transportfehler duerfen nicht als fachliche Toolfehler erscheinen.

Verbindliche Regeln:

- JSON-RPC-Nachrichten laufen ueber einen MCP-Endpunkt
- `POST` verarbeitet Client-Nachrichten
- `GET` liefert entweder SSE oder HTTP 405
- `DELETE` terminiert eine Session oder liefert HTTP 405
- `POST`-`Accept` muss `application/json` und `text/event-stream` robust
  erkennen
- Whitespace, Reihenfolge und Media-Type-Parameter sind nicht normativ
- nach Initialize muessen Folgeaufrufe `MCP-Protocol-Version` senden
- fehlende, syntaktisch ungueltige oder nicht unterstuetzte
  `MCP-Protocol-Version`-Header werden als Transport-/JSON-RPC-Fehler
  behandelt
- ungueltiger `Origin` liefert HTTP 403
- optionale Session-IDs werden strikt validiert

### 4.7 Registries sind transportneutral

`stdio` und HTTP muessen dieselbe Tool-Registry und Resource-Registry
nutzen. Ein Tool darf nicht nur in einem Transport registriert sein, ausser
der Plan nennt den Unterschied explizit.

Die Registries duerfen konkrete Handler noch als nicht implementiert
markieren, muessen aber stabile Metadaten liefern:

- Toolname
- Beschreibung
- Eingabe-Schema
- Ausgabe-Schema
- benoetigte Scope-Klasse
- Inline-Limits oder Resource-Fallback-Hinweise
- Fehlercodes, soweit transportnah relevant

---

## 5. Zielarchitektur

### 5.1 Modulstruktur

Phase B legt diesen Bereich an:

```text
adapters/driving/mcp/
  build.gradle.kts
  src/main/kotlin/dev/dmigrate/mcp/
  src/test/kotlin/dev/dmigrate/mcp/
```

Erwartete Paketgruppen:

- `dev.dmigrate.mcp.server`
  - Server-Bootstrap
  - Server-Konfiguration
  - Lifecycle
- `dev.dmigrate.mcp.transport`
  - `stdio`
  - HTTP
  - JSON-RPC Wire-Mapping
- `dev.dmigrate.mcp.auth`
  - stdio Principal-Ableitung
  - HTTP Bearer-Tokenvalidierung
  - Scope-Mapping
  - Protected Resource Metadata
- `dev.dmigrate.mcp.registry`
  - Tool-Registry
  - Resource-Registry
  - JSON-Schema-Registry
- `dev.dmigrate.mcp.resources`
  - Resource-Projektionen
  - Resource-Templates
  - Resource-Fehlermapping
- `dev.dmigrate.mcp.schema`
  - JSON-Schema-Erzeugung oder statische Schema-Definitionen
- `dev.dmigrate.mcp.bootstrap`
  - Driver-/Codec-/Adapter-Bootstrap
- `dev.dmigrate.mcp.test`
  - lokaler Testclient
  - Transport-Fakes
  - Golden-Test-Hilfen

Die genaue Paketstruktur darf beim Coding an bestehende Gradle- und
Kotlin-Konventionen angepasst werden. Die Grenze bleibt verbindlich:
gemeinsame Kernmodelle gehoeren nicht in `adapters/driving/mcp`.

### 5.2 Server-Bootstrap

Der Bootstrap erzeugt:

- Server-Konfiguration
- Transport-Konfiguration
- Auth-Konfiguration
- Registry-Instanzen
- Runtime-Bootstrap fuer Driver und Codecs
- Store-/Service-Anbindungen aus Phase A
- Audit-/Error-Mapping-Anbindung

Startfehler muessen vor dem ersten Client-Request auftreten, wenn:

- HTTP ohne Auth nicht lokal gebunden ist
- Issuer-/JWKS-/Introspection-Konfiguration unvollstaendig ist
- die kanonische HTTP-Resource-URI nicht bestimmbar ist
- Runtime-Registries nicht initialisiert werden koennen
- JSON-Schema-Registry inkonsistente Toolnamen enthaelt

### 5.3 Initialize-Antwort

Initialize muss:

- MCP `protocolVersion` `2025-11-25` verhandeln
- Server-Metadaten liefern
- Capabilities korrekt setzen
- `resources` Capability setzen, sobald `resources/list` und
  `resources/templates/list` implementiert sind
- keine `subscribe`- oder `listChanged`-Flags setzen, solange
  Subscriptions nicht implementiert sind
- d-migrate Vertragsversion `v1` nur in Produkt-Capabilities oder
  `capabilities_list` sichtbar machen

Nicht erlaubt:

- `v1` als MCP-Protokollversion ausgeben
- nicht implementierte Capabilities bewerben
- Transport-spezifische Capabilities in Tool-Vertraege einmischen

### 5.4 Streamable-HTTP-Endpunkt

Der HTTP-Endpunkt implementiert:

- `POST` fuer JSON-RPC Client-Nachrichten
- `GET` fuer SSE oder HTTP 405
- `DELETE` fuer Session-Terminierung oder HTTP 405

`POST`-Semantik:

- Notification oder JSON-RPC-Response liefert bei Annahme HTTP
  `202 Accepted` ohne Body
- JSON-RPC-Request liefert entweder genau ein JSON-Objekt mit
  `Content-Type: application/json` oder SSE mit
  `Content-Type: text/event-stream`
- fachliche Toolfehler bleiben im MCP-Tool-Result, wenn es sich um einen
  erfolgreichen `tools/call`-Transport handelt
- Protokoll-, Header-, Session- und Auth-Fehler werden nicht als
  fachliche Toolfehler modelliert

### 5.5 Resource-Discovery

Phase B implementiert die MCP-Standard-Discovery:

- `resources/list`
- `resources/templates/list`

Resource-URI-Formen:

- `dmigrate://tenants/{tenantId}/jobs/{jobId}`
- `dmigrate://tenants/{tenantId}/artifacts/{artifactId}`
- `dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}`
- `dmigrate://tenants/{tenantId}/schemas/{schemaId}`
- `dmigrate://tenants/{tenantId}/profiles/{profileId}`
- `dmigrate://tenants/{tenantId}/diffs/{diffId}`
- `dmigrate://tenants/{tenantId}/connections/{connectionId}`
- `dmigrate://capabilities`

Verbindliche Regeln:

- `tenantId` in der URI ist adressierend, nicht autorisierend
- Zugriff wird gegen `PrincipalContext` geprueft
- fremde Tenants liefern `TENANT_SCOPE_DENIED`
- nicht existente Ressourcen liefern `RESOURCE_NOT_FOUND`
- Resource-Fehler werden als JSON-RPC-Errors modelliert
- `resources/read` nimmt nur `uri` entgegen
- Chunking erfolgt ueber Chunk-URIs oder `artifact_chunk_get`, nicht ueber
  schemafremde `resources/read`-Parameter

### 5.6 JSON-Schema-Vertrag

Alle 0.9.6-Tools bekommen stabile Schema-Definitionen:

- `inputSchema`
- `outputSchema`
- JSON Schema 2020-12

Wenn ein `$schema`-Feld ausgegeben wird, muss es exakt lauten:

```text
https://json-schema.org/draft/2020-12/schema
```

Wenn kein `$schema` ausgegeben wird, gilt JSON Schema 2020-12 gemaess MCP-
Tool-Schema-Vertrag als angenommener Dialekt.

Nicht erlaubt:

- Draft-07-spezifische Keywords
- uneinheitliche Schema-Dialekte
- verschiedene Schemas fuer `stdio` und HTTP
- Schemas, die rohe JDBC-Secrets als Tool-Payload erlauben

---

## 6. Arbeitspakete

### 6.1 Gradle-Modul anlegen

Aufgaben:

- `settings.gradle.kts` um `adapters:driving:mcp` erweitern
- `adapters/driving/mcp/build.gradle.kts` anlegen
- Kotlin-/Test-Konfiguration an bestehende Adaptermodule angleichen
- Dependencies auf gemeinsame Kernmodule und benoetigte Runtime-Adapter
  setzen
- Test-Setup fuer Unit-, Adapter- und Integrationstests vorbereiten

Akzeptanz:

- Modul kompiliert leer oder mit minimalem Bootstrap
- bestehende Module bleiben unveraendert lauffaehig
- keine zyklische Abhaengigkeit zu `adapters:driving:cli`

### 6.2 Server-Konfiguration und Startpfade definieren

Aufgaben:

- gemeinsame MCP-Server-Konfiguration modellieren
- `stdio`-Startpfad implementieren
- HTTP-Startpfad implementieren
- Bind-Adresse, Base-URL, Auth-Modus, Issuer, JWKS/Introspection,
  Clock-Skew und Scope-Mapping konfigurierbar machen
- unsichere lokale Demo-Konfiguration explizit markieren

Akzeptanz:

- Server kann fuer Tests in-process gestartet und gestoppt werden
- HTTP ohne Auth startet nur auf Loopback mit expliziter unsicherer
  Konfiguration
- HTTP ohne Auth auf `0.0.0.0`, Public Base URL oder nicht-lokaler
  Bind-Adresse wird abgelehnt

### 6.3 Runtime-Bootstrap kapseln

Aufgaben:

- gemeinsame Bootstrap-Funktion fuer Driver und Codecs bereitstellen
- CLI-nahe Bootstrap-Logik teilen oder duplizierungsarm kapseln
- Test-Hooks ohne Prozess-Exit bereitstellen
- Runtime-Dependencies fuer Driver, Profiling, Streaming und Formate
  sicherstellen

Akzeptanz:

- Test kann nach MCP-Serverstart Driver-Lookup ausfuehren
- Test kann nach MCP-Serverstart Schema-Codec-Lookup ausfuehren
- Tool-Handler brauchen keine ad-hoc-Reflection

### 6.4 MCP Initialize implementieren

Aufgaben:

- Initialize-Request validieren
- `protocolVersion` `2025-11-25` verhandeln
- Capabilities aus Registries ableiten
- d-migrate `v1` als Produktvertragsversion veroeffentlichen
- Folgeaufrufe mit ausgehandelter MCP-Protokollversion absichern

Akzeptanz:

- MCP-Client kann ueber `stdio` initialisieren
- MCP-Client kann ueber HTTP initialisieren
- falsche Protokollversion wird als Protokollfehler behandelt

### 6.5 Streamable HTTP implementieren

Aufgaben:

- MCP-Endpunkt fuer `POST`, `GET`, `DELETE` bereitstellen
- `Accept`-Header robust parsen
- `MCP-Protocol-Version` nach Initialize pruefen
- `Origin` validieren
- optionales `MCP-Session-Id` erzeugen und pruefen
- JSON-RPC Request, Response und Notification korrekt unterscheiden
- SSE oder 405 fuer `GET`
- Session-Terminierung oder 405 fuer `DELETE`

Akzeptanz:

- Notifications und JSON-RPC-Responses liefern `202 Accepted` ohne Body
- JSON-RPC-Requests liefern JSON-Objekt oder SSE
- fehlende/ungueltige Folge-Header werden abgewiesen
- ungueltiger Origin liefert HTTP 403
- abgelaufene oder unbekannte Session liefert definierten Fehlerpfad

### 6.6 HTTP-Authorization implementieren

Aufgaben:

- Bearer-Token aus `Authorization` lesen
- Tokens in Query-Parametern ablehnen
- Protected Resource Metadata bereitstellen
- `WWW-Authenticate`-Challenges erzeugen
- Issuer exakt pruefen
- JWKS-Validierung mit Cache/Rotation oder Introspection implementieren
- Algorithmus-Allowlist erzwingen
- `aud`/Resource validieren
- `exp`, `nbf`, `iat` und Clock-Skew pruefen
- Pflichtclaims validieren
- Claims deterministisch auf `PrincipalContext` mappen
- Scope-Challenges fuer Tool- und Resource-Klassen erzeugen

Akzeptanz:

- fehlendes Token liefert 401 mit Challenge
- unzureichende Scopes liefern 403 mit Scope-Challenge
- Issuer-Mismatch, unbekanntes `kid`, nicht erlaubter Algorithmus,
  abgelaufenes Token und Audience-Mismatch werden abgewiesen
- Metadata-Golden-Test ist stabil

### 6.7 stdio-Authorization implementieren

Aufgaben:

- Host-Attestation-Registry anbinden, falls konfiguriert
- `DMIGRATE_MCP_STDIO_TOKEN` validieren
- Fingerprint-Lookup oder lokale Signaturvalidierung implementieren
- Mapping auf Principal, Tenant, Scopes, Admin-Status, Ablaufzeit und
  Audit-Identitaet
- Audit-Scrubbing fuer Tokenwerte sicherstellen

Akzeptanz:

- gueltiger stdio-Token erzeugt erwarteten `PrincipalContext`
- unbekannter, ungueltiger, abgelaufener oder scope-armer Token liefert
  `AUTH_REQUIRED` oder `FORBIDDEN_PRINCIPAL`
- fehlender stdio-Principal liefert `AUTH_REQUIRED`
- OS-User und Prozessdaten erzeugen keinen Principal

### 6.8 Tool- und Resource-Registry aufbauen

Aufgaben:

- Tool-Metadatenmodell im Adapter definieren
- Resource-Metadatenmodell im Adapter definieren
- Scope-Klasse je Tool/Resource hinterlegen
- Handler-Schnittstelle fuer spaetere Phasen definieren
- nicht implementierte Handler kontrolliert als bekannte, aber fachlich
  nicht verfuegbare Operation abbilden
- Registry fuer `capabilities_list` vorbereiten

Akzeptanz:

- `stdio` und HTTP nutzen dieselben Registry-Instanzen
- unbekannte Toolnamen liefern Protokollfehler
- bekannte, noch nicht implementierte Operationen liefern strukturierten
  fachlichen Fehler

### 6.9 MCP-Standard-Discovery implementieren

Aufgaben:

- `resources/list` implementieren
- `resources/templates/list` implementieren
- Tenant-/Principal-Filter anwenden
- Cursor-/Pagination-Modell aus Phase A verwenden
- Chunk-Templates fuer grosse Artefakte veroeffentlichen
- Connection-Refs ohne Secrets projizieren

Akzeptanz:

- Jobs, Artefakte, Schemas, Profile, Diffs und Connection-Refs werden
  principal-gefiltert gelistet
- `nextCursor` ist MCP-konform und opak
- Templates enthalten Chunk-URIs
- Subscribe-/`listChanged`-Flags werden nicht gesetzt

### 6.10 JSON-Schemas pflegen und testen

Aufgaben:

- Schema-Definitionen fuer alle 0.9.6-Tools anlegen oder generieren
- `inputSchema` und `outputSchema` registrieren
- JSON Schema 2020-12 als Dialekt sicherstellen
- Golden-Test fuer Schemas schreiben
- verbotene Secret-Payload-Felder pruefen

Akzeptanz:

- Golden-Test validiert alle Tool-Schemas
- vorhandenes `$schema` zeigt exakt auf Draft 2020-12
- keine Transport-spezifischen Schema-Varianten

### 6.11 Startdokumentation nachziehen

Aufgaben:

- lokalen `stdio`-Startbefehl dokumentieren
- HTTP-Startbefehl dokumentieren
- lokale Demo-Konfiguration dokumentieren
- Auth-Konfiguration fuer nicht-lokales HTTP dokumentieren
- bekannte Grenzen von Phase B dokumentieren

Akzeptanz:

- ein Entwickler kann den Server lokal starten
- nicht-lokaler HTTP-Betrieb ist ohne Auth-Konfiguration nicht als fertig
  dokumentiert

---

## 7. Verifikationsstrategie

### 7.1 Unit-Tests

Pflichtabdeckung:

- `Accept`-Header-Parser fuer `application/json` und `text/event-stream`
- `MCP-Protocol-Version`-Validierung
- Origin-Validierung
- Session-ID-Validierung
- HTTP-Auth-Konfigurationsregeln
- Protected Resource Metadata Builder
- `WWW-Authenticate`-Challenge Builder
- Scope-Mapping
- Tokenvalidator fuer Issuer, JWKS/Introspection, Algorithmus,
  Audience/Resource, Pflichtclaims, Ablauf und Clock-Skew
- stdio-Principal-Ableitung
- Token-Fingerprint-Scrubbing
- Tool-Registry
- Resource-Registry
- JSON-Schema-Registry
- Resource-URI-Projektionen
- Cursor-Serialisierung fuer `resources/list`
- Runtime-Bootstrap-Kapselung ohne Prozess-Exit

### 7.2 Adaptertests

Pflichtabdeckung:

- Initialize ueber `stdio`
- Initialize ueber HTTP
- Capabilities fuer beide Transporte identisch
- `capabilities_list` ohne DB-Verbindung
- unbekannter Toolname als Protokollfehler
- bekannte, aber nicht implementierte Operation als fachlicher Fehler
- `resources/list` mit Principal-/Tenant-Filter
- `resources/templates/list` mit Chunk-Templates
- Resource-Fehler als JSON-RPC-Errors
- JSON-Schemas fuer Tool-Definitionen

### 7.3 HTTP-only Tests

Pflichtabdeckung:

- Streamable-HTTP-`POST` mit robust geparstem `Accept`-Header
- Folgeaufruf mit `MCP-Protocol-Version`
- Folgeaufruf ohne, mit ungueltigem oder nicht unterstuetztem
  `MCP-Protocol-Version`
- JSON-RPC-Notification per `POST` liefert `202 Accepted` ohne Body
- JSON-RPC-Response per `POST` liefert `202 Accepted` ohne Body
- JSON-RPC-Request per `POST` liefert JSON oder SSE
- ungueltiger `Origin` liefert HTTP 403
- optionales `MCP-Session-Id` wird korrekt ausgegeben, verlangt,
  abgewiesen oder als abgelaufen behandelt
- `GET` liefert SSE oder HTTP 405
- `DELETE` mit `MCP-Session-Id` terminiert Session oder liefert HTTP 405
- nicht-lokaler HTTP-Transport erzwingt Auth
- 401 enthaelt `WWW-Authenticate` und `resource_metadata`
- 403 enthaelt Scope-Challenge
- Metadata-Golden-Test fuer `resource`, `authorization_servers` und
  `scopes_supported`

### 7.4 stdio-only Tests

Pflichtabdeckung:

- Principal-Ableitung aus validierter Host-Attestation
- Principal-Ableitung aus `DMIGRATE_MCP_STDIO_TOKEN`
- expliziter Test-Principal nur in Testkonfiguration
- ungepruefte OS-User-, Prozess- oder Umgebungsdaten liefern
  `AUTH_REQUIRED`
- unbekannter, ungueltiger, abgelaufener oder scope-armer stdio-Token
  liefert `AUTH_REQUIRED` oder `FORBIDDEN_PRINCIPAL`
- roher Tokenwert erscheint nicht in Audit oder Fehlerdetails

### 7.5 Integrationstests

Mindestens:

- echter MCP-Serverstart ueber `stdio`
- echter MCP-Serverstart ueber HTTP
- Initialize/Capabilities in beiden Transporten
- Driver-Lookup ueber normale Runtime-Registry
- Schema-Codec-Lookup ueber normale Runtime-Registry
- `resources/list`
- `resources/templates/list`
- `capabilities_list`

Wenn die verwendete MCP-Bibliothek einen Testclient anbietet, wird dieser
genutzt. Falls nicht, wird die JSON-RPC-Kommunikation in Tests minimal und
lokal gekapselt.

---

## 8. Betroffene Codebasis

Neu:

- `adapters/driving/mcp/build.gradle.kts`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/...`
- `adapters/driving/mcp/src/test/kotlin/dev/dmigrate/mcp/...`

Zu aendern:

- `settings.gradle.kts`
- ggf. Root- oder Convention-Gradle-Konfiguration fuer neues Adaptermodul
- ggf. gemeinsame Bootstrap-Hilfen in `hexagon/application` oder einem
  bestehenden Port-/Application-Modul
- ggf. Dokumentation fuer Start und lokale Konfiguration

Nicht zu aendern ohne separaten Grund:

- bestehende CLI-Vertraege
- bestehende Runner-Semantik
- Job- und Artefaktvertrag aus `docs/job-contract.md`
- Phase-A-Kernmodelle in Richtung MCP-Spezifik

---

## 9. Risiken und Gegenmassnahmen

### 9.1 MCP-Bibliothek deckt Streamable HTTP nicht vollstaendig ab

Risiko:

- die gewaehlte Bibliothek unterstuetzt Initialize, Tools oder Resources,
  aber nicht alle HTTP-Transportdetails

Gegenmassnahme:

- MCP-spezifische Bibliotheksaufrufe in kleinem Transportpaket kapseln
- Wire-Tests gegen HTTP-Status, Header und JSON-RPC-Struktur schreiben
- notfalls minimalen HTTP-Transport selbst kapseln, ohne Tool-Handler zu
  veraendern

### 9.2 Auth-Deaktivierung wird versehentlich produktnah nutzbar

Risiko:

- lokale Demo-Konfiguration wird auf oeffentlichen Interfaces gestartet

Gegenmassnahme:

- Start fail-closed bei `0.0.0.0`, Public Base URL oder nicht-lokaler
  Bind-Adresse
- eigene Tests fuer jede verbotene Konfiguration
- unsichere Konfiguration im Namen und in Logs deutlich markieren

### 9.3 Transportfehler werden als fachliche Toolfehler modelliert

Risiko:

- Clients koennen Auth-, Header- oder JSON-RPC-Fehler nicht korrekt
  unterscheiden

Gegenmassnahme:

- klare Error-Mapping-Schicht fuer Wire-Fehler
- Tests fuer unbekannte Toolnamen, Headerfehler und Resource-Fehler
- `UNSUPPORTED_TOOL_OPERATION` nur fuer bekannte fachliche Operationen

### 9.4 Registries divergieren zwischen `stdio` und HTTP

Risiko:

- ein Tool funktioniert lokal, aber nicht ueber HTTP oder umgekehrt

Gegenmassnahme:

- gemeinsame Registry-Instanzen im Server-Bootstrap erzeugen
- Adaptertests vergleichen Capabilities beider Transporte
- Tool-Schemas nur aus einer Registry ausliefern

### 9.5 Bootstrap dupliziert CLI-Logik unsauber

Risiko:

- Driver oder Codecs fehlen im MCP-Pfad, obwohl sie in der CLI funktionieren

Gegenmassnahme:

- gemeinsame Bootstrap-Funktion extrahieren oder aequivalent kapseln
- Integrationstest fuer Driver- und Codec-Lookup nach MCP-Serverstart
- keine ad-hoc-Reflection aus Tool-Handlern

---

## 10. Definition of Done fuer Phase B

- `adapters:driving:mcp` ist in Gradle aufgenommen.
- Der MCP-Server kann ueber `stdio` initialisiert werden.
- Der MCP-Server kann ueber HTTP initialisiert werden.
- Initialize verhandelt MCP `protocolVersion` `2025-11-25`.
- d-migrate `v1` wird nur als Tool-/Resource-Vertragsversion verwendet.
- `stdio` und HTTP nutzen dieselbe Tool-Registry.
- `stdio` und HTTP nutzen dieselbe Resource-Registry.
- Streamable-HTTP-`POST` implementiert die MCP-Wire-Semantik fuer
  Notifications, Responses und Requests.
- `GET` und `DELETE` haben definierte SSE-/405- bzw.
  Session-/405-Semantik.
- `Accept`-Header-Auswertung ist robust gegen Reihenfolge, Whitespace und
  Parameter.
- Folgeaufrufe validieren `MCP-Protocol-Version`.
- `Origin`-Validierung ist aktiv.
- optionales `MCP-Session-Id` ist getestet.
- nicht-lokales HTTP erzwingt Auth.
- Auth-Deaktivierung ist nur fuer explizite Loopback-Test-/Demo-
  Konfiguration moeglich.
- Protected Resource Metadata wird korrekt ausgeliefert.
- 401/403-Challenges enthalten die erwarteten Auth- und Scope-Informationen.
- JWKS- oder Introspection-Validierung ist fail-closed.
- stdio-Tokenvalidierung erzeugt `PrincipalContext` ohne rohen Token im
  Audit.
- Runtime-Bootstrap initialisiert Driver und Schema-Codecs.
- `resources/list` und `resources/templates/list` sind implementiert.
- Resource-Discovery ist tenant-/principal-gefiltert.
- JSON-Schemas fuer alle 0.9.6-Tools sind in JSON Schema 2020-12
  verfuegbar.
- Golden-Tests fuer Protected Resource Metadata und Tool-Schemas laufen.
- Startbefehl und lokale Konfiguration sind dokumentiert.

---

## 11. Anschluss an Phase C

Phase C darf beginnen, wenn Phase B die MCP-Transportbasis stabil
bereitstellt.

Phase B liefert bereits:

- `capabilities_list` (siehe §12.11) — read-only Registry-Projektion
  ohne Driver-/Store-Anbindung

Phase C baut darauf auf:

- `schema_validate`
- `schema_generate`
- `schema_compare`
- `job_status_get`
- `artifact_chunk_get`
- strukturierte Tool-Resultate mit Inline-Limits und Artefakt-Fallback
- Adaptertests fuer read-only Tool-Contracts

Phase C darf keine eigene Transportlogik einfuehren. Wenn ein read-only Tool
Transportaenderungen benoetigt, ist das ein Hinweis auf eine Luecke in Phase
B und muss dort korrigiert werden.

---

## 12. Library-Entscheidungen und offene Punkte

### 12.1 MCP-Bibliothek

Verbindlich:

- **kein** vorhandenes Kotlin-MCP-SDK einbinden — die MCP-Schicht
  (Initialize, Tools, Resources, Capabilities) wird auf
  `org.eclipse.lsp4j.jsonrpc` als JSON-RPC-Transport selbst aufgebaut.
  lsp4j liefert das JSON-RPC-Framing, Request/Response-Lifecycle und
  Cancellation; der MCP-Methoden-Handler wird als d-migrate-eigener
  Code implementiert.
- HTTP-Wire-Verhalten wird mit eigenen Adaptertests gegen den Ktor-
  Endpunkt abgesichert (siehe §12.2).
- stdio-Framing folgt **MCP 2025-11-25**: newline-delimited JSON
  (`\n`-getrennte JSON-Objekte ohne Content-Length-Header). lsp4j
  unterstuetzt beide Framings ueber `MessageJsonHandler`/
  `StreamMessageProducer` — die MCP-Variante muss explizit gewaehlt
  werden, der LSP-Default ist Content-Length.

### 12.2 HTTP-Server-Technologie

Verbindlich:

- **Ktor** (Server-Engine `CIO`) als HTTP-Server. Idiomatisch in
  Kotlin, leichtgewichtig in-process testbar, native Coroutine-
  Integration, kein Reflection-Magic.
- in-process Tests ueber `testApplication { ... }` (Ktor-Test-DSL),
  keine globalen Ports.

Verbot:

- Transportauswahl darf keine Fachlogik in HTTP-spezifische Klassen ziehen
- Ktor-Routing-Code ruft die Tool-/Resource-Registry, niemals
  Application-Services direkt

### 12.3 JWT-/JWKS-Library

Verbindlich:

- **Nimbus JOSE-JWT** (`com.nimbusds:nimbus-jose-jwt`) fuer
  Tokenvalidierung. Bietet `RemoteJWKSet` mit eingebautem Cache und
  Rotation, Algorithm-Allowlist via `JWSVerifier`-Whitelist,
  Audience-/Resource-Validation, Clock-Skew-Behandlung. RFC 7662
  Token Introspection wird ueber Nimbus' `OAuth2TokenValidator` plus
  HTTP-Client (Ktor-Client) realisiert.
- JWKS ist Default fuer JWT-Validierung; Introspection nur bei
  expliziter Konfiguration.

Verbindlich:

- beide Varianten fail-closed
- keine ungepruefte lokale JWT-Decodierung als Auth
- Algorithm-Allowlist Phase A: `RS256`, `RS384`, `RS512`, `ES256`,
  `ES384`, `ES512` (matched OAuth 2.1 Verbot von `none` und
  symmetrischen HS-Algorithms ueber JWKS)
- Clock-Skew-Default: 60 Sekunden

### 12.4 stdio-Framing und JSON-RPC

Verbindlich:

- newline-delimited JSON (`\n`) ueber stdio
- lsp4j wird mit `RemoteEndpoint`-Setup ueber JSON-RPC verwendet, das
  Wire-Format aber durch Custom-`MessageProducer`/`MessageConsumer`
  auf NDJSON umgestellt
- HTTP nutzt dasselbe lsp4j-Endpoint, eingebettet in den Ktor-Handler

### 12.5 Session-ID-Lifecycle

Verbindlich (vorher offen, jetzt fixiert):

- Server vergibt `MCP-Session-Id` als UUID v4 bei der ersten
  HTTP-Antwort nach Initialize
- in-memory `ConcurrentHashMap<UUID, SessionState>` haelt
  `(principalContext, lastSeen)`
- TTL: 30 Minuten Idle (Default; `ServerCoreLimits.mcp.sessionIdleTimeout`)
- unbekannte/abgelaufene IDs liefern HTTP 404 mit JSON-RPC-Error
  `-32000` `session expired or unknown`
- `DELETE /mcp` mit gueltiger Session-Id terminiert die Session
  (200 OK), ohne Id liefert 405

### 12.6 Origin-Allow-List

Verbindlich:

- `McpServerConfig.allowedOrigins: Set<String>` als Konfiguration
- lokaler Default (Test/Demo): `http://localhost:*`, `http://127.0.0.1:*`
- nicht-lokaler Betrieb verlangt explizite Origin-Liste; leere Liste
  ist Startfehler
- Wildcard `*` ist verboten (kein "alles erlauben")
- Origin-Match: exakte Origin-Komponente (Scheme + Host + Port);
  kein Substring/Regex

### 12.7 Protected Resource Metadata Path

Verbindlich:

- Pfad: `/.well-known/oauth-protected-resource` (RFC 9728)
- Inhalt JSON wie in §4.4 spezifiziert
- `WWW-Authenticate`-Challenge enthaelt
  `resource_metadata="<absolute URL zum Metadata-Endpoint>"`

### 12.8 JSON-RPC-Error-Mapping

Verbindlich (Code → Bedeutung):

| JSON-RPC code | Bedeutung | wann |
| --- | --- | --- |
| `-32700` | Parse error | malformed JSON Body |
| `-32600` | Invalid Request | Body nicht JSON-RPC 2.0 |
| `-32601` | Method not found | unbekannter Toolname oder unbekannte MCP-Methode |
| `-32602` | Invalid params | Schema-Validation-Fehler an JSON-RPC-Layer |
| `-32603` | Internal error | unerwarteter Fehler in lsp4j/Bootstrap |
| `-32000` | Session expired or unknown | (custom) abgelaufene `MCP-Session-Id` |
| `-32001` | MCP-Protocol-Version mismatch | (custom) ungueltiger oder fehlender Header nach Initialize |

Tool-Fehler folgen MCP-`tools/call`-Konvention:
- `UNSUPPORTED_TOOL_OPERATION` → Tool-Result mit `isError=true`,
  `content` enthaelt `ToolErrorEnvelope` aus AP 6.7
- alle anderen `ApplicationException`-Subtypen → Tool-Result mit
  `isError=true`, `content` enthaelt `ToolErrorEnvelope`

Resource-Fehler (`resources/read`, `resources/list`) → JSON-RPC-Error
mit `data` = `ToolErrorEnvelope`-Projektion.

Auth-Fehler bleiben am HTTP-Layer (401/403), erscheinen nicht im
JSON-RPC-Layer.

### 12.9 Scope-Tabelle pro Tool/Resource

Verbindlich (Phase B baut die Tabelle, Tool-Handler folgen in
Phase C/D):

| Operation | Scope |
| --- | --- |
| `capabilities_list`, `tools/list`, `resources/list`, `resources/templates/list`, `resources/read` | `dmigrate:read` |
| `schema_validate`, `schema_compare`, `schema_generate`, `schema_list`, `profile_list`, `diff_list`, `job_list`, `job_status_get`, `artifact_list`, `artifact_chunk_get` | `dmigrate:read` |
| `schema_reverse_start`, `schema_compare_start`, `data_profile_start`, `data_export_start` | `dmigrate:job:start` |
| `artifact_upload_init`, `artifact_upload_chunk`, `artifact_upload_complete`, `artifact_upload_abort` | `dmigrate:artifact:upload` |
| `data_import_start`, `data_transfer_start` | `dmigrate:data:write` |
| `job_cancel` | `dmigrate:job:cancel` |
| `procedure_transform_plan`, `procedure_transform_execute`, `testdata_plan`, `testdata_execute` | `dmigrate:ai:execute` |
| Cross-Tenant-Reads, `connections/list` mit Admin-Filter | `dmigrate:admin` |

Granulare `dmigrate:tool:<name>`-Scopes werden in 0.9.6 nicht
eingefuehrt. Die Tabelle deckt das vollstaendige 0.9.6-Vokabular ab,
auch wenn Phase B die meisten Tools nur als Registry-Eintrag (ohne
Handler) liefert.

### 12.10 Token-Registry fuer stdio

Verbindlich:

- neuer Port `StdioTokenStore` in `hexagon:ports-common` (Phase B
  ergaenzt diesen Port; Phase A war fertig)
- API:
  ```kotlin
  interface StdioTokenStore {
      fun lookup(tokenFingerprint: String): StdioTokenGrant?
  }
  data class StdioTokenGrant(
      val principalId: PrincipalId,
      val tenantId: TenantId,
      val scopes: Set<String>,
      val isAdmin: Boolean,
      val auditSubject: String,
      val expiresAt: Instant,
  )
  ```
- file-backed Default-Implementierung im mcp-Modul:
  YAML-/JSON-File mit Token-Fingerprint → Grant (Pfad ueber
  `McpServerConfig.stdioTokenFile`)
- `DMIGRATE_MCP_STDIO_TOKEN`-Env-Var wird gehasht (sha256_hex), das
  Resultat ist der Fingerprint fuer den Lookup; rohe Token-Werte
  erscheinen nie im Audit (`SecretScrubber` deckt das `tok_*`-Prefix)

### 12.11 `capabilities_list` als Phase-B-Pfad

Klarstellung: `capabilities_list` ist der einzige fachliche Tool-
Handler, den Phase B implementiert. Begruendung:

- §1 verlangt explizit "ohne Datenbankverbindung" funktionsfaehig
- der Handler liefert nur Registry-Metadaten (Tool-Liste, Scope-
  Tabelle, Vertragsversionen) — keine Stores, keine Driver
- alle anderen 0.9.6-Tools bleiben Phase C/D-Scope (siehe §3.2)

§11 wird entsprechend angepasst: `capabilities_list` zaehlt nicht zu
"Phase C baut darauf auf" sondern ist bereits in Phase B vorhanden.

### 12.4 Session-ID-Pflicht

Default:

- `MCP-Session-Id` optional halten, solange keine serverseitigen Streams
  oder Sessionzustand zwingend erforderlich sind

Verbindlich:

- wenn der Server eine Session-ID vergibt, muss sie fuer Folge-Requests
  validiert werden
- unbekannte oder abgelaufene Session-IDs liefern definierte Fehlerpfade

### 12.5 Discovery ohne fertige Tool-Handler

Default:

- Tool-Metadaten und Schemas fuer 0.9.6 vollstaendig registrieren
- noch nicht implementierte Handler liefern bekannte fachliche Fehler,
  keine unbekannten Toolnamen

Verbindlich:

- Capabilities duerfen nur implementierte MCP-Faehigkeiten bewerben
- Toollisten duerfen geplante Tools zeigen, wenn ihr Status und Schema
  stabil sind und Aufrufe kontrolliert fehlschlagen
