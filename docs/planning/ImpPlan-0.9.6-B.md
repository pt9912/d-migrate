# Implementierungsplan: 0.9.6 - Phase B `MCP-Modul und Transport`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: B (`MCP-Modul und Transport`)
> **Status**: Implementiert (2026-05-01) — Phase B komplett, alle DoD-Punkte erfuellt
> **Referenz**: `docs/planning/implementation-plan-0.9.6.md` Abschnitt 1 bis 5,
> Abschnitt 8 Phase B, Abschnitt 9.2, Abschnitt 9.3, Abschnitt 9.4,
> Abschnitt 11 und Abschnitt 12; `docs/planning/ImpPlan-0.9.6-A.md`;
> `spec/ki-mcp.md`; `spec/job-contract.md`; `spec/architecture.md`;
> `spec/design.md`; `spec/rest-service.md`; `spec/grpc-service.md`;
> `spec/cli-spec.md`; `hexagon/application`; `hexagon/ports-read`;
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

`spec/ki-mcp.md` beschreibt das Zielbild fuer Tools, Ressourcen, Fehler,
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

- gemeinsame MCP-Server-Konfiguration modellieren — verbindlicher
  Feldsatz und Validierungsregeln in §12.12
- `stdio`-Startpfad implementieren
- HTTP-Startpfad implementieren
- Bind-Adresse, Base-URL, Auth-Modus, Issuer, JWKS/Introspection,
  Clock-Skew, Session-Idle-Timeout und Scope-Mapping konfigurierbar
  machen
- unsichere lokale Demo-Konfiguration explizit markieren

Akzeptanz:

- Server kann fuer Tests in-process gestartet und gestoppt werden
- HTTP ohne Auth startet nur auf Loopback mit expliziter unsicherer
  Konfiguration
- HTTP ohne Auth auf `0.0.0.0`, Public Base URL oder nicht-lokaler
  Bind-Adresse wird abgelehnt
- alle Verstoesse aus §12.12 sind Startfehler vor dem ersten Client-
  Request

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
- Job- und Artefaktvertrag aus `spec/job-contract.md`
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

NDJSON-Parser-/Writer-Vertrag (verbindlich, fuer AP 6.4 Implementation):

- Lesen (stdin):
  - Bytes bis `\n` (LF, `0x0A`) sammeln, dann UTF-8 dekodieren
  - UTF-8-BOM (`EF BB BF`) am Stream-Anfang tolerieren, sonst nicht
  - leere Zeilen (nur Whitespace oder leer) werden ueberlesen, nicht
    als Parse-Error gewertet
  - Trailing `\r` vor `\n` (CRLF) wird tolerant gestripped
  - bei JSON-Parse-Error mit erkennbarer `id`: JSON-RPC-`-32700`-
    Response mit der id zurueckschreiben; ohne `id`: Zeile verworfen
    plus Audit-Log
- Schreiben (stdout):
  - UTF-8-Serialisierung der JSON-RPC-Nachricht
  - in JSON-Strings ist `\n` JSON-escaped (`\\n`); literal LF im
    Output-Stream ist ausschliesslich Frame-Trenner
  - jeder Frame endet mit genau einem `\n`, keine BOM
  - stdout-Schreibzugriff ist serialisiert (single writer thread oder
    `synchronized`), damit parallele Server-Antworten nicht
    interleaven
- stderr darf vom Server fuer Logging genutzt werden, niemals stdout
- Reader-Thread laeuft bis EOF oder Cancellation; eine `IOException`
  stoppt den Server-Lifecycle (kein Silent-Tod des Stream-Loops)

Test-Vertrag (Adapter-Test in AP 6.4):

- Roundtrip serialize -> parse: identitaetserhaltend
- JSON-String mit eingebettetem `\n`: Frame wird nicht mittendrin
  getrennt
- Multi-Frame in einem Read-Call (Buffer enthaelt mehrere `\n`)
  liefert mehrere `Message`-Instanzen
- leere Zeilen am Stream-Anfang/zwischen Frames werden ueberlesen
- UTF-8-BOM am Stream-Anfang wird toleriert
- Parse-Error mit id liefert `-32700`-Response mit Echo-id
- Parse-Error ohne id liefert kein Response, der Reader liest weiter

### 12.5 Session-ID-Lifecycle

Verbindlich (vorher offen, jetzt fixiert):

- Server vergibt `MCP-Session-Id` als UUID v4 bei der ersten
  HTTP-Antwort nach Initialize
- in-memory `ConcurrentHashMap<UUID, SessionState>` haelt
  `(principalContext, lastSeen)`
- TTL: 30 Minuten Idle (Default; `McpServerConfig.sessionIdleTimeout`)
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

### 12.12 Server-Konfiguration (`McpServerConfig`)

Verbindlicher Feldsatz fuer AP 6.2 (alle anderen §-Verweise sind die
fachliche Grundlage; dieses Anchor ist die Referenz beim Coden):

```kotlin
enum class AuthMode { DISABLED, JWT_JWKS, JWT_INTROSPECTION }

data class McpServerConfig(
    // Bind / HTTP
    val bindAddress: String = "127.0.0.1",
    val port: Int = 0,
    val publicBaseUrl: URI? = null,
    val allowedOrigins: Set<String> = setOf(
        "http://localhost:*", "http://127.0.0.1:*",
    ),
    // Auth (HTTP)
    val authMode: AuthMode = AuthMode.JWT_JWKS,
    val issuer: URI? = null,
    val jwksUrl: URI? = null,
    val introspectionUrl: URI? = null,
    val audience: String? = null,
    val algorithmAllowlist: Set<String> = setOf(
        "RS256", "RS384", "RS512", "ES256", "ES384", "ES512",
    ),
    val clockSkew: Duration = Duration.ofSeconds(60),
    val scopeMapping: Map<String, Set<String>> =
        McpServerConfig.DEFAULT_SCOPE_MAPPING, // §12.9
    // Session
    val sessionIdleTimeout: Duration = Duration.ofMinutes(30), // §12.5
    // stdio
    val stdioTokenFile: Path? = null, // §12.10
)
```

Verbindliche Validierung beim Serverstart (§4.3, §4.4, §12.6):

- `port` muss in `0..65535` liegen
- `clockSkew` muss in `[0s, 5min]` liegen
- `authMode == DISABLED` verlangt:
  - `InetAddress.getByName(bindAddress).isLoopbackAddress` `== true`
  - `publicBaseUrl == null`
  - `bindAddress` ist nicht `0.0.0.0`, `::`, oder ein DNS-Name, der zu
    nicht-loopback aufloest
- `authMode in {JWT_JWKS, JWT_INTROSPECTION}` verlangt:
  - `issuer != null`, `audience != null`
  - `JWT_JWKS` zusaetzlich `jwksUrl != null`
  - `JWT_INTROSPECTION` zusaetzlich `introspectionUrl != null`
- `publicBaseUrl != null` verlangt `scheme == "https"` (§4.4 verlangt
  kanonische HTTPS-URI)
- `allowedOrigins`:
  - darf `*` (Wildcard alleine) nicht enthalten
  - bei nicht-loopback-Bind muss die Liste explizit gesetzt sein (Default
    nur fuer Loopback erlaubt)
- `algorithmAllowlist`:
  - darf `none` und `HS*` nicht enthalten
- `stdioTokenFile != null` verlangt, dass die Datei existiert und
  lesbar ist (§12.10 — File-Backed Lookup)

Alle Verstoesse sind Startfehler vor dem ersten Client-Request (§5.2).

### 12.13 Streamable-HTTP-Vertraege (verbindlich, fuer AP 6.5 Implementation)

**Routing**

| Methode | Pfad   | Phase B Verhalten |
| ------- | ------ | ----------------- |
| `POST`  | `/mcp` | JSON-RPC-Dispatch |
| `GET`   | `/mcp` | HTTP `405 Method Not Allowed` (kein SSE in Phase B; Server-Push folgt fruehestens Phase C/D) |
| `DELETE`| `/mcp` | mit gueltiger `MCP-Session-Id`: `200 OK` + Session entfernen; ohne ID: `405` |

**`Accept`-Header (POST)**

- Phase B antwortet ausschliesslich mit `application/json` — SSE-Pfad
  ist nicht implementiert.
- Akzeptiert (kein 4xx): fehlend, `*/*`, `application/json`,
  `application/json, text/event-stream`, oder `text/event-stream,
  application/json`.
- Abgelehnt mit `406 Not Acceptable`: `Accept: text/event-stream`
  alleine (Client verlangt SSE only) — Body: JSON-RPC-Error
  `-32600` "client requires SSE; not implemented in Phase B".

**Validierungs-Reihenfolge fuer `POST /mcp`**

1. `Origin`-Check (§12.6) — Mismatch: HTTP `403 Forbidden` ohne
   JSON-RPC-Body, generischer text/plain.
2. Body parsen — Parse-Error: HTTP `400 Bad Request` mit
   JSON-RPC-Error-Body `-32700`.
3. Methode bestimmen (Request/Notification, Methodenname).
4. `MCP-Session-Id`-/`MCP-Protocol-Version`-Header pruefen
   (Initialize-Spezialfall siehe unten).
5. Auth (AP 6.6) — danach erst der Dispatch.
6. JSON-RPC-Dispatch via `RemoteEndpoint`.

**Initialize-Spezialfall**

- Ein Request mit `method == "initialize"` darf weder
  `MCP-Session-Id` noch `MCP-Protocol-Version` Header tragen — der
  Server vergibt beide Werte erst durch dieses Request.
- Server-Antwort auf erfolgreiches Initialize enthaelt zwei zusaetzliche
  Response-Header:
  - `MCP-Session-Id: <uuid v4>`
  - `MCP-Protocol-Version: 2025-11-25`
- Initialize-Race ist erlaubt: zwei parallele Initialize-Requests
  bekommen jeweils eine eigene UUID v4. Kein server-seitiges Locking
  noetig.

**Folge-Request-Header (alles ausser `initialize`)**

- `MCP-Session-Id` MUSS gesetzt und in der Session-Map vorhanden sein.
  - fehlt oder unbekannt: HTTP `404 Not Found` mit JSON-RPC-Error
    `-32000` "session expired or unknown".
- `MCP-Protocol-Version` MUSS gesetzt und gleich der bei Initialize
  ausgehandelten Version sein.
  - fehlt oder weicht ab: HTTP `400 Bad Request` mit JSON-RPC-Error
    `-32001` "MCP-Protocol-Version mismatch".

**Antwort-Statuscodes (POST)**

- erfolgreicher JSON-RPC-Request: `200 OK` mit
  `Content-Type: application/json` und JSON-RPC-Response-Body.
- erfolgreiche Notification (Body ist JSON-RPC-Notification, ohne
  `id`): `202 Accepted` ohne Body.
- erfolgreich empfangene Server-Response (Client liefert eine Response
  auf eine vom Server gestellte Request): `202 Accepted` ohne Body.
- `Content-Length: 0` Body: HTTP `400 Bad Request`.

**Session-Lifecycle (vertieft §12.5)**

- `SessionState` Felder in Phase B:
  ```kotlin
  data class SessionState(
      val negotiatedProtocolVersion: String,
      val createdAt: Instant,
      var lastSeen: Instant,
      // principalContext: PrincipalContext  ← AP 6.6 ergaenzt
  )
  ```
- Session-Map: `ConcurrentHashMap<UUID, SessionState>` im
  `McpServerHandle`-Lifetime.
- TTL-Reaper: Hintergrund-Coroutine prueft alle 60 s; Sessions mit
  `lastSeen + sessionIdleTimeout < now` werden entfernt.
- bei jedem akzeptierten Request wird `lastSeen` aktualisiert.

**`installMcpHttpRoute`-Anpassung gegenueber AP 6.4**

- AP 6.4 antwortete Notifications mit `204 No Content` — wird auf
  `202 Accepted` umgestellt (Plan-Vertrag).
- AP 6.4 hatte einen einzigen `serviceFactory()` pro Request — AP 6.5
  baut einen Session-Map-basierten Lookup darauf, sodass nach
  Initialize derselbe `McpServiceImpl` (mit derselben
  `negotiatedProtocolVersion`) fuer alle Folge-Requests der Session
  benutzt wird.

### 12.14 HTTP-Auth-Vertraege (verbindlich, fuer AP 6.6 Implementation)

**Geltungsbereich**

- Diese Regeln gelten ausschliesslich fuer den HTTP-Transport (`POST /mcp`).
  stdio-Auth ist §6.7 und nutzt `DMIGRATE_MCP_STDIO_TOKEN` plus die
  Token-Registry aus §12.10.
- `authMode == DISABLED`: Auth-Schicht ist no-op. Kein Token wird
  gelesen, keine Challenge erzeugt, kein Scope-Check ausgefuehrt.
  PrincipalContext wird mit `authSource = ANONYMOUS`,
  `principalId = "anonymous"`, `homeTenantId = TenantId("default")`,
  `scopes = setOf("dmigrate:admin")` (Demo-Owner) und einer beliebig
  langen `expiresAt` gesetzt. Nur in Loopback-Demo zulaessig.

**Token-Quelle (verbindlich)**

- ausschliesslich `Authorization: Bearer <token>` Header
  (RFC 6750 §2.1). Query-Parameter (`?access_token=...`) wird
  abgelehnt; Cookies und Body werden nicht inspiziert.
- fehlender Header bei `authMode != DISABLED` -> HTTP 401 mit
  `WWW-Authenticate`-Challenge.

**Validierungs-Reihenfolge (Erweiterung von §12.13)**

1. `Origin`-Check
2. `Accept`-Check
3. Body parsen
4. JSON-RPC-Parse (Methode + id bekannt)
5. **Bearer-Validation** (Token vorhanden + signatur-/claim-gueltig)
6. **Method-aware Header-Check** (`MCP-Session-Id`, `MCP-Protocol-Version`
   — Initialize-Spezialfall wie in §12.13)
7. **Scope-Check** (Method aus Schritt 4 gegen
   `McpServerConfig.scopeMapping[method]`)
8. Dispatch

Initialize selbst (`method = "initialize"`) und `notifications/initialized`
sind scope-frei: ohne diese beiden Methoden koennte ein Client die
Session nicht aufbauen, in der seine Tool-Aufrufe Scope-gueltig waeren.
Bearer-Validation gilt aber auch fuer Initialize.

**Pflichtclaims (alle JWT-Modi)**

| Claim | Verbindliche Pruefung |
| ----- | --------------------- |
| `iss` | exakter String-Match gegen `McpServerConfig.issuer` |
| `sub` | non-empty String |
| `exp` | `now <= exp + clockSkew` |
| `nbf` | wenn vorhanden: `now >= nbf - clockSkew` |
| `iat` | wenn vorhanden: nicht in Zukunft (mit clockSkew) |
| `aud` | `McpServerConfig.audience` ist im `aud`-Wert enthalten — `aud` darf String oder Array of String sein |
| Algorithmus | JWS-`alg` muss in `McpServerConfig.algorithmAllowlist` (§12.12 Default `RS256/RS384/RS512/ES256/ES384/ES512`) |
| `kid` | muss bei JWKS-Mode aufloesbar sein; unbekanntes `kid` -> abweisen |

**Claims -> PrincipalContext Mapping**

```
PrincipalContext(
    principalId         = PrincipalId(claims.subject),
    homeTenantId        = TenantId(claims.stringClaim("tenant_id") ?: claims.stringClaim("tid") ?: "default"),
    effectiveTenantId   = homeTenantId,                    // initial; cross-tenant-impersonation Phase E
    allowedTenantIds    = setOf(homeTenantId),             // Phase B: nur eigener Tenant
    scopes              = parseScopes(claims),             // siehe unten
    isAdmin             = "dmigrate:admin" in scopes,
    auditSubject        = claims.subject,
    authSource          = AuthSource.OIDC,
    expiresAt           = claims.expirationTime.toInstant(),
)
```

`parseScopes(claims)`:

- bevorzugt `scope` (RFC 8693, space-separated String) ueber `scp` (Microsoft, JSON-Array). Bei beiden vorhanden gilt `scope`.
- leere Scope-Liste ist erlaubt (fuehrt zu 403 bei jeder Method, die einen Scope verlangt).

**Token-Caching**

- per-Request Validation: jedes `POST /mcp` validiert sein
  Authorization-Header neu. Nimbus' `RemoteJWKSet` cached intern die
  JWKS-Schluessel und vermeidet so den Netzwerk-Roundtrip; das Token
  selbst wird trotzdem jedes Mal verifiziert.
- `SessionState.principalContext` (siehe unten) ist ein
  Audit-/Last-Validation-Snapshot, kein Source-of-Truth fuer
  Authorisierung.

**SessionState-Erweiterung gegenueber AP 6.5**

```kotlin
class SessionState(
    val negotiatedProtocolVersion: String,
    val createdAt: Instant,
    @Volatile var lastSeen: Instant,
    val service: McpService,
    val principalContext: PrincipalContext, // <- AP 6.6 ergaenzt
)
```

- Initialize-Erfolg: AP 6.5 erzeugt Session ohne Principal; AP 6.6
  fuegt den im Schritt 5 validierten PrincipalContext in den
  Konstruktor.
- Folge-Requests: jede neu validierte PrincipalContext-Instanz wird
  ignoriert (oder optional als `lastValidatedAt` gepatcht — Phase B
  laesst es weg).

**Network- / Parse-Fehler**

- JWKS-Endpoint nicht erreichbar, JSON-Parse-Error im Token, JWS-
  Signaturfehler, abgelaufenes Token, falsches `aud` etc.: HTTP 401
  mit `WWW-Authenticate`-Challenge und `error="invalid_token"`,
  `error_description="<kurz, ohne sensitive Daten>"`. Kein 503 (Client
  soll das Token erneuern, nicht den Server-Health probieren).

**`WWW-Authenticate`-Challenges (RFC 6750 §3)**

| HTTP | Challenge |
| ---- | --------- |
| `401` (kein Token) | `Bearer realm="dmigrate-mcp", resource_metadata="<absolute URL>"` |
| `401` (token invalid/expired/sig fail) | `Bearer realm="dmigrate-mcp", error="invalid_token", error_description="<text>", resource_metadata="<absolute URL>"` |
| `403` (insufficient_scope) | `Bearer realm="dmigrate-mcp", error="insufficient_scope", scope="<minimal required>", resource_metadata="<absolute URL>"` |

`<absolute URL>` ist die `publicBaseUrl` (oder, lokal:
`http://127.0.0.1:<port>`) plus `/.well-known/oauth-protected-resource`.

**Protected Resource Metadata Endpoint**

- HTTP-Pfad: `GET /.well-known/oauth-protected-resource` (RFC 9728,
  §12.7).
- Content-Type: `application/json`.
- Body (verbindliche Felder fuer Golden-Test in §6.6 Akzeptanz):
  ```json
  {
    "resource": "<canonical HTTPS URI of MCP server>",
    "authorization_servers": ["<issuer>"],
    "scopes_supported": [
      "dmigrate:read",
      "dmigrate:job:start",
      "dmigrate:artifact:upload",
      "dmigrate:data:write",
      "dmigrate:job:cancel",
      "dmigrate:ai:execute",
      "dmigrate:admin"
    ],
    "bearer_methods_supported": ["header"]
  }
  ```
- Reihenfolge der Felder ist Teil des Golden-Tests. Reihenfolge der
  `scopes_supported` matcht §4.5 (read, job:start, artifact:upload,
  data:write, job:cancel, ai:execute, admin).
- Endpoint ist auch im DISABLED-Mode verfuegbar (Demo-Tools fragen das
  Dokument an), liefert dann `authorization_servers = []` und ein
  klares Hinweis-Feld:
  ```json
  { "x-dmigrate-auth-mode": "disabled" }
  ```

**Introspection-Modus (`AuthMode.JWT_INTROSPECTION`)**

- HTTP-Client: Ktor-Client (CIO engine, schon Teil der Phase-B-Deps
  laut §12.3). Nimbus' OAuth2TokenValidator wird nicht direkt
  benutzt — wir bauen den Introspection-Call selbst auf einem
  Ktor-Client auf, weil das `RemoteJWKSet` aus Nimbus genug ist und
  ein zusaetzlicher Nimbus-OAuth2-Client unnoetigen Footprint
  hinzufuegt.
- Format: `POST <introspectionUrl>` mit
  `application/x-www-form-urlencoded` Body `token=<bearer>`.
  Authentifizierung des Servers gegen den Introspection-Endpoint
  bleibt aussen vor (AP 6.6 nutzt unauthenticated Calls auf einen
  vertrauenswuerdigen internen Endpoint; Production-Setup mit
  Client-Credentials kommt in einer spaeteren Iteration).
- Antwort `{"active": false}` -> 401 mit `error="invalid_token"`.
- Antwort `{"active": true, ...}` -> Claims werden wie bei JWT
  gemappt (selbe Tabelle).

**Scope-Mapping-Lookup**

- Quelle: `McpServerConfig.scopeMapping` (Default §12.9 in
  `McpServerConfig.DEFAULT_SCOPE_MAPPING`).
- Lookup-Key: JSON-RPC-Method-Name (z. B. `"tools/list"`,
  `"capabilities_list"`, `"resources/read"`).
- unbekannte Methode (kein Mapping-Eintrag) -> standardmaessig
  `dmigrate:admin` verlangen (fail-closed: Method existiert noch nicht
  oder Tippfehler im Mapping).

**Test-Vertrag (AP 6.6)**

- 401 ohne Token (mit Challenge inkl. `resource_metadata`)
- 401 mit ungueltigem Token (signatur-fail, exp abgelaufen, falscher
  aud, falscher iss, unbekanntes kid, nicht-erlaubter Algorithmus)
- 403 mit gueltigem Token aber unzureichenden Scopes (z. B.
  `tools/list` ohne `dmigrate:read`)
- 200 mit gueltigem Token + ausreichenden Scopes
- DISABLED-Mode: Auth komplett uebersprungen, PrincipalContext mit
  `AuthSource.ANONYMOUS`
- Protected Resource Metadata Golden-Test (JSON-Field-Set + -Reihenfolge)
- Introspection-Mode mit `active: false` -> 401
- Audience als Array enthaelt config -> akzeptiert

### 12.15 stdio-Auth-Vertraege (verbindlich, fuer AP 6.7 Implementation)

**Geltungsbereich**

- Diese Regeln gelten ausschliesslich fuer den stdio-Transport.
  HTTP-Auth ist §12.14.
- stdio kennt keine Auth-Modi: die Token-Registry aus §12.10 ist die
  einzige Principal-Quelle. `authMode` wirkt sich auf stdio nicht aus.

**Token-Quelle (verbindlich)**

- ausschliesslich Env-Var `DMIGRATE_MCP_STDIO_TOKEN`. CLI-Flags,
  Config-Dateien und ENV-Aliase sind kein Tokenkanal.
- der rohe Wert verlaesst den Aufruf-Stack nie ungehasht — er wird
  beim Eintritt in den Resolver per `sha256_hex(utf8(token))` zu
  einem Fingerprint, der fuer den `StdioTokenStore.lookup` benutzt
  wird. Audit-/Log-/Fehler-Pfade sehen nur Reason-Strings, nie den
  Token oder den Fingerprint.

**Resolution-Reihenfolge (verbindlich)**

1. `tokenSupplier()` lesen — `null`/leer -> `AuthRequired("DMIGRATE_MCP_STDIO_TOKEN not set")`
2. `StdioTokenStore` vorhanden? — `null` (kein `config.stdioTokenFile`)
   -> `AuthRequired("stdio token registry not configured")`
3. `store.lookup(sha256_hex(token))` -> `null` ergibt
   `AuthRequired("stdio token unknown")`
4. `grant.expiresAt.isAfter(now)` muss `true` sein — sonst
   `AuthRequired("stdio token expired")`. Ablauf "exakt jetzt" zaehlt
   als abgelaufen.
5. Erfolg: `Resolved(PrincipalContext(...))` (Mapping siehe unten).

OS-User, Parent-Prozess-Daten, `pwd`, `pid` und ungeprueftes ENV
duerfen NICHT zu einem Principal fuehren. Es gibt keinen impliziten
Demo-Principal fuer stdio.

**Grant -> PrincipalContext Mapping**

```
PrincipalContext(
    principalId       = grant.principalId,
    homeTenantId      = grant.tenantId,
    effectiveTenantId = grant.tenantId,                    // kein cross-tenant Switch
    allowedTenantIds  = setOf(grant.tenantId),             // Phase B: nur eigener Tenant
    scopes            = grant.scopes,
    isAdmin           = grant.isAdmin,
    auditSubject      = grant.auditSubject,
    authSource        = AuthSource.SERVICE_ACCOUNT,
    expiresAt         = grant.expiresAt,
)
```

**File-backed Default (`FileStdioTokenStore`)**

- akzeptiert `.json`, `.yaml`, `.yml` — Format-Wahl ueber Datei-Endung
  (lowercase). Keine Endung == JSON.
- Wurzelobjekt: `{ "tokens": [ { "fingerprint": "<hex>", ... } ] }`.
- Pflichtfelder pro Eintrag: `fingerprint`, `principalId`, `tenantId`,
  `scopes` (Array), `auditSubject`, `expiresAt` (RFC-3339).
- Optionale Felder: `isAdmin` (Default `false`).
- leere/leere-blanke Scope-Strings werden gefiltert.
- Fehlerklassen werden als `StdioTokenStoreLoadException` geworfen
  (unreadable, malformed, fehlendes Pflichtfeld, kein RFC-3339 in
  `expiresAt`, root nicht Objekt, `tokens` nicht Array, Eintrag nicht
  Objekt).
- Token-Rotation erfordert Server-Neustart — Phase B haelt die Datei
  beim Konstruieren einmal in immutable Map. Hot-Reload ist nicht
  zugesagt.

**Bootstrap-Verhalten**

- `McpServerBootstrap.startStdio` bindet die Resolution einmal beim
  Start an `StdioJsonRpc.principalResolution`. Ein
  `AuthRequired`-Resultat verhindert den Serverstart nicht — Initialize
  ist auth-frei (analog zu §12.14 Schritt 5/6).
- Tool-/Resource-Dispatch in Phase C/D MUSS die gebundene Resolution
  konsultieren und `AuthRequired` in `AUTH_REQUIRED` (`tools/call`
  `isError=true` mit `ToolErrorEnvelope`) bzw. JSON-RPC-Error mit
  `ToolErrorEnvelope`-Projektion (Resources) uebersetzen.
- Test-Hooks: `tokenStoreOverride: StdioTokenStore?` und
  `tokenSupplier: () -> String?` sind nur fuer Tests vorgesehen;
  Produktiv-Aufrufer lassen beide auf Default.

**Test-Vertrag (AP 6.7)**

- gueltiger Token + Store-Hit -> `Resolved` mit allen Mapping-Feldern
  (Tenant, Scopes, isAdmin, auditSubject, expiresAt, authSource =
  SERVICE_ACCOUNT)
- fehlende Env-Var, leere Env-Var, fehlender Store, unbekannter
  Fingerprint, abgelaufener Grant, Ablauf "exakt jetzt" -> jeweils
  `AuthRequired` mit dem oben festgelegten Reason-Text
- OS-User-Fallback wird explizit getestet: Resolver darf bei null-Token
  + bestehendem Store kein `Resolved` produzieren
- `FileStdioTokenStore`: JSON-Pfad, YAML-Pfad (`.yaml` UND `.yml`),
  fehlendes `tokens`-Feld, nicht-Array `tokens`, nicht-Object Eintrag,
  fehlendes Pflichtfeld, leeres Pflichtfeld, fehlendes `scopes`,
  Scope-Filter fuer leere Strings, ungueltiges `expiresAt`, malformed
  Body, unreadable file
- `StdioTokenFingerprint`: bekannter SHA-256-Vektor (`abc`), Determinismus,
  lowercase-Hex, Empty-String-Reject
- Bootstrap-Smoketest: `startStdio` ohne Env -> Handle traegt
  `AuthRequired`; `startStdio` mit Override-Store + Env -> Handle traegt
  `Resolved` mit erwartetem `principalId`

### 12.16 Tool-/Resource-Registry-Vertraege (verbindlich, fuer AP 6.8 Implementation)

**Geltungsbereich**

- AP 6.8 baut die transportneutrale Tool- und Resource-Registry. Die
  Resource-Registry ist in Phase B leer (AP 6.9 fuellt sie); die
  Tool-Registry deckt das vollstaendige 0.9.6-Tool-Vokabular ab.
- `capabilities_list` ist gemaess §12.11 der einzige fachliche
  Tool-Handler in Phase B; alle anderen 0.9.6-Tools sind in der
  Registry gefuehrt, dispatchen aber zum `UnsupportedToolHandler`.

**Tool-Universum**

- Quelle: `McpServerConfig.scopeMapping` (§12.9 Default).
- Eintraege deren Name in der Menge `{tools/list, tools/call,
  resources/list, resources/templates/list, resources/read,
  connections/list}` liegt sind MCP-Protokoll-Methoden und werden
  NICHT in `tools/list` projiziert.
- Alle anderen Eintraege werden als `ToolDescriptor` registriert.

**`ToolDescriptor`-Felder (verbindlich)**

```kotlin
data class ToolDescriptor(
    val name: String,
    val title: String,
    val description: String,
    val requiredScopes: Set<String>,           // mirrors scopeMapping[name]
    val inputSchema: Map<String, Any>,         // JSON Schema 2020-12
    val outputSchema: Map<String, Any>,        // JSON Schema 2020-12
    val inlineLimits: String? = null,          // capabilities_list-only
    val resourceFallbackHint: String? = null,  // capabilities_list-only
    val errorCodes: Set<ToolErrorCode> = emptySet(),
)
```

- `inputSchema`/`outputSchema` advertise `$schema =
  https://json-schema.org/draft/2020-12/schema` und type=object. Phase
  B liefert minimal valide Stubs; AP 6.10 ersetzt sie durch echte
  Definitionen (Golden-Test-Vertrag).
- `inlineLimits` und `resourceFallbackHint` werden NUR ueber
  `capabilities_list` projiziert, nicht ueber `tools/list` (`tools/list`
  bleibt MCP-konform Schlank).

**`ToolHandler`-Schnittstelle**

```kotlin
fun interface ToolHandler {
    fun handle(context: ToolCallContext): ToolCallOutcome
}

data class ToolCallContext(
    val name: String,
    val arguments: com.google.gson.JsonElement?, // raw lsp4j tree
    val principal: PrincipalContext,
)

sealed interface ToolCallOutcome {
    data class Success(val content: List<ToolContent>) : ToolCallOutcome
    data class Error(val envelope: ToolErrorEnvelope) : ToolCallOutcome
}
```

- Handler werfen bevorzugt `ApplicationException`-Subtypen — der
  `tools/call`-Dispatcher mappt sie via `DefaultErrorMapper` in einen
  `ToolErrorEnvelope` mit `isError=true`. `Error` als Rueckgabe ist nur
  fuer Custom-`details` jenseits des `ApplicationException`-Vertrags.

**`tools/list` (verbindlich)**

- liefert pro registriertem Tool ein `ToolMetadata`:
  ```kotlin
  data class ToolMetadata(
      val name, val title, val description,
      val inputSchema, val outputSchema,
      val requiredScopes: List<String>, // sorted; d-migrate-Erweiterung
  )
  ```
- `nextCursor` ist in Phase B konstant `null` — keine Pagination noetig
  bei ~25 Tools.
- Reihenfolge entspricht der Registrierungsreihenfolge des Builders.

**`tools/call` (verbindlich)**

- Lookup-Reihenfolge:
  1. `toolRegistry.findHandler(params.name)` — `null` -> JSON-RPC
     `-32601` "Method not found, unknown tool '<name>'" (NICHT als
     Tool-Result mit `isError=true`).
  2. `currentPrincipal.get()` — `null` -> Tool-Result mit
     `isError=true` und `ToolErrorEnvelope.code = AUTH_REQUIRED`
     (§4.2/§12.15). Der Toolname landet in `details` als
     `{"key":"toolName", "value":"<name>"}` (parallel zu
     `UnsupportedToolOperationException.toolName`).
  3. `handler.handle(context)`:
     - `Success` -> `ToolsCallResult(content, isError=false)`
     - `Error(envelope)` -> Tool-Result mit `isError=true` (Envelope in
       `content[0].text` als JSON, `mimeType=application/json`).
     - geworfene `ApplicationException` -> via `DefaultErrorMapper` zu
       `ToolErrorEnvelope` -> wie `Error`.
     - alles andere -> `INTERNAL_AGENT_ERROR`-Envelope.

**Principal-Bindung (HTTP vs stdio)**

- `McpServiceImpl(initialPrincipal: PrincipalContext?)` setzt einen
  `AtomicReference`-Slot beim Konstruieren.
- `McpServiceImpl.bindPrincipal(p)` aktualisiert den Slot zur Laufzeit.
- HTTP: `McpHttpRoute.dispatchAndRespond` ruft `bindPrincipal(...)`
  vor jedem Dispatch mit dem in Schritt 5 (Bearer-Validation) frisch
  validierten Principal auf — §12.14 verlangt per-request
  Source-of-truth, nicht den Initialize-Snapshot.
- stdio: `McpServerBootstrap.startStdio` ruft `bindPrincipal` NICHT
  nach dem Bootstrap auf — der stdio-Token-Principal ist statisch fuer
  die Lebenszeit des Prozesses (§12.15).
- `bindPrincipal(null)` ist erlaubt und produziert beim naechsten
  `tools/call` `AUTH_REQUIRED`.

**Envelope-Wire-Shape (verbindlich)**

```json
{
  "code": "<TOOL_ERROR_CODE>",
  "message": "<text>",
  "details": [
    { "key": "<key>", "value": "<value>" },
    ...
  ],
  "requestId": "<id>"   // omitted when ToolErrorEnvelope.requestId == null
}
```

- `details` ist eine JSON-ARRAY von `{key, value}`-Objekten, NICHT ein
  Object. Begruendung: `ValidationErrorException` emittiert legitim
  mehrere Details mit demselben Key (z. B. mehrere Verstoesse auf
  demselben Feld); ein objektartiges `details` wuerde Duplikate
  schweigend dropped.
- `requestId` wird bei `null` weggelassen (kein literales
  `"requestId": null`).
- Reihenfolge der Details bleibt stabil (Liste).

**`UNSUPPORTED_TOOL_OPERATION` vs `Method not found` (kritische
Unterscheidung)**

- unbekannter Toolname (Tippfehler, falsche Spec) -> JSON-RPC `-32601`
  *Method not found*. Client muss seinen Spec-Stand pruefen.
- bekannter Toolname ohne Phase-B-Handler -> Tool-Result mit
  `isError=true`, Envelope `code=UNSUPPORTED_TOOL_OPERATION`,
  `details=[{"key":"toolName","value":"<name>"},
  {"key":"operation","value":"<title>"}]`. Client weiss "Tool ist im
  Vertrag, aber dieser Server-Build implementiert es noch nicht".

**`capabilities_list`-Output (verbindlich)**

```json
{
  "mcpProtocolVersion": "2025-11-25",
  "dmigrateContractVersion": "v1",
  "serverName": "d-migrate",
  "tools": [
    {
      "name": "<tool-name>",
      "title": "<title>",
      "description": "<description>",
      "requiredScopes": ["<scope>", ...],   // sorted
      "inlineLimits": "<text>",             // optional
      "resourceFallbackHint": "<text>",     // optional
      "errorCodes": ["<CODE>", ...]         // optional, sorted
    }, ...
  ],
  "scopeTable": {
    "<scope>": ["<method>", ...]            // alphabetisch sortiert
  }
}
```

- `serverName` aus `McpProtocol.SERVER_NAME` (`d-migrate`).
- `dmigrateContractVersion` aus `McpProtocol.DMIGRATE_CONTRACT_VERSION`
  (`v1`).
- Output erscheint in `tools/call`-`content[0]` als
  `type=text`, `mimeType=application/json`.
- `inputSchema` und `outputSchema` werden BEWUSST ausgelassen — sie
  sind via `tools/list` reachable; die `capabilities_list`-Projektion
  bleibt schlank.
- `scopeTable` invertiert die `(method, scopes)`-Map nach
  `(scope, methods)`. Tools mit mehreren Pflicht-Scopes erscheinen
  unter JEDEM dieser Scopes (Drop wuerde §4.4 Protected Resource
  Metadata verfaelschen). Methods mit leerer Scope-Menge (z. B.
  `initialize`, `notifications/initialized`) werden NICHT projiziert.

**Bootstrap-Verhalten**

- `McpServerBootstrap.startHttp` und `startStdio` akzeptieren beide
  einen optionalen `toolRegistry: ToolRegistry`-Parameter. Default:
  `PhaseBRegistries.toolRegistry(config.scopeMapping)`.
- `PhaseBRegistries.toolRegistry(scopeMapping)` wirft
  `IllegalStateException` wenn `capabilities_list` nicht im Mapping
  steht — §12.11 verlangt mindestens diesen einen funktionsfaehigen
  Tool, ein zufaellig truncated Mapping wuerde sonst still ein Server
  ohne nutzbare Tools erzeugen.
- Wenn beide Transporte im selben Prozess laufen, MUSS der Caller
  dieselbe Registry-Instanz uebergeben (§6.8 Akzeptanz). Test-Hooks
  nutzen den Override fuer in-memory Registries.
- `startHttp` erstellt pro HTTP-Session einen frischen
  `McpServiceImpl`, der die *gemeinsame* `ToolRegistry`-Instanz teilt
  und seinen Principal pro Request via `bindPrincipal` aktualisiert
  bekommt.

**`ServerCapabilities` (Initialize)**

- AP 6.8 setzt `capabilities.tools = {"listChanged": false}`.
- `capabilities.resources` bleibt `null` bis AP 6.9.
- `capabilities.prompts` bleibt `null` (Phase B liefert keine Prompts).

**Test-Vertrag (AP 6.8)**

- `ToolRegistry`: registriert/findet Tools, Reihenfolge stabil,
  doppelte Registrierung wirft `IllegalArgumentException`.
- `UnsupportedToolHandler`: wirft `UnsupportedToolOperationException`
  mit `toolName` aus dem Call-Context und `operation` aus dem
  Konstruktor.
- `CapabilitiesListReadOnlyHandler`: Output-JSON enthaelt
  protocolVersion/contractVersion/serverName/tools/scopeTable;
  optionale Felder werden bei null/empty ausgelassen; mimeType ist
  `application/json`.
- `PhaseBRegistries`: alle 0.9.6-Tools sind registriert,
  MCP-Protokoll-Methoden NICHT, `capabilities_list`-Handler ist NICHT
  Unsupported, andere Tools schon; Stub-Schemas advertiseren
  Draft-2020-12; `requiredScopes` mirror das Mapping.
- `tools/list` ueber `McpServiceImpl`: liefert Metadaten mit
  Stub-Schemas, `nextCursor=null`; leere Registry liefert leere Liste;
  MCP-Protokoll-Methoden (`tools/list`, `resources/*`,
  `connections/list`) erscheinen NICHT in der Tool-Projektion.
- `tools/call`:
  - `capabilities_list` -> Erfolgreicher Snapshot.
  - unbekannter Name -> JSON-RPC `-32601`.
  - registriert-aber-unimplementiert -> `isError=true`,
    `code=UNSUPPORTED_TOOL_OPERATION`.
  - Custom-Handler wirft `ApplicationException` -> `isError=true`,
    Envelope-Details aus `details()`.
  - Custom-Handler liefert `ToolCallOutcome.Error(envelope)` ->
    `isError=true`, Envelope wird verbatim auf den Wire serialisiert
    (inkl. `requestId`, falls gesetzt).
  - fehlender Principal -> `isError=true`, `code=AUTH_REQUIRED`,
    `details=[{"key":"toolName","value":"<name>"}]`.
  - unbekannter Name MIT fehlendem Principal -> trotzdem `-32601`
    (Reihenfolge: Lookup vor Auth).
  - Argumente werden verbatim als `JsonElement` durchgereicht.
  - Envelope-`details` mit doppelten Keys (z. B. zwei
    `ValidationViolation`s auf demselben Feld) werden als
    JSON-Array beide erhalten — kein Map-Collapse.
- `bindPrincipal`: setzt/entfernt den Principal zur Laufzeit;
  Auswirkung wird beim naechsten `tools/call` sichtbar.
- `CapabilitiesListReadOnlyHandler.scopeTable`: Multi-Scope-Tools
  erscheinen unter jedem Scope; leere-Scope-Methoden werden
  weggelassen.
- `PhaseBRegistries.toolRegistry({})` ohne `capabilities_list` ->
  `IllegalStateException`.
- `ServerCapabilities.tools` ist nach AP 6.8 `{"listChanged": false}`.

### 12.17 Resource-Discovery-Vertraege (verbindlich, fuer AP 6.9 Implementation)

**Geltungsbereich**

- AP 6.9 implementiert `resources/list` und `resources/templates/list`.
- `resources/read` bleibt nach Phase B — Phase B liefert nur Discovery, nicht
  den Datenpfad. Phase D verdrahtet den produktiven Read-Handler auf diesem
  Vertrag.
- Beide Methoden brauchen `dmigrate:read` (§12.9).

**`ToolErrorEnvelope` ist hier NICHT der Wire-Pfad**

- Resource-Fehler werden als JSON-RPC-Errors modelliert (§5.5 +
  §12.8). Sie erscheinen also NICHT als Tool-Result mit
  `isError=true`. AP 6.9-relevante Codes:
  - `-32602` Invalid params -> ungueltiger / nicht parsebarer Cursor.
  - `-32600` Invalid Request -> kein Principal gebunden (Server-Fehler:
    Bootstrap haette das verhindern muessen).

**`resources/list` (verbindlich)**

- Request: `{ "cursor": "<opaque>" | null | absent }`.
- Response:
  ```json
  {
    "resources": [
      { "uri": "dmigrate://...", "name": "...", "mimeType": "application/json", "description": "..." },
      ...
    ],
    "nextCursor": "<opaque>" | null
  }
  ```
- `nextCursor == null` heisst "alle Resource-Familien durchgewalkt".
- `resources` darf leer sein, auch wenn `nextCursor != null` —
  Principal-Filter kann eine ganze Store-Page leer machen. Clients
  MUESSEN solange paginieren bis `nextCursor == null`.

**Resource-Walk (verbindlich)**

Reihenfolge: `JOBS -> ARTIFACTS -> SCHEMAS -> PROFILES -> DIFFS ->
CONNECTIONS`. `UPLOAD_SESSIONS` wird NICHT projiziert (kein
MCP-Resource).

Pro `resources/list`-Aufruf:

1. Cursor decoden (`null` -> `(JOBS, null)`).
2. Aktuelle Resource-Familie: `kind`, Innentoken: `innerToken`.
3. Store fuer `kind` mit `PageRequest(pageSize, innerToken)` aufrufen.
4. Items principal-/tenant-filtern (siehe unten).
5. Wenn Store `nextPageToken != null`: `nextCursor = (kind,
   nextPageToken)`. Stop.
6. Wenn Familie ausgeschoepft und es gibt eine Folge-Familie:
   `nextCursor = (next-kind, null)`, weiter sammeln bis pageSize voll.
7. Wenn alle Familien durch: `nextCursor = null`.

**Cursor-Format (verbindlich)**

- `Base64-URL-safe(JSON({kind: <ResourceKind.name>, innerToken: <store-token>|null}))`.
- Padding wird weggelassen.
- Server akzeptiert auch URL-encoded form ohne Padding (Standard).
- Decode-Fehler (Base64, JSON, unbekanntes `kind`, fehlendes `kind`)
  -> JSON-RPC `-32602` mit kurzer Reason-Message.
- Clients behandeln den Cursor als opaque — Server darf das Format
  zwischen Server-Versionen aendern.

**Principal-/Tenant-Filter (verbindlich)**

- `tenantId = principal.effectiveTenantId` wird beim Store-Lookup gesetzt.
- `JobRecord` und `ArtifactRecord` werden nach `isReadableBy(principal)`
  gefiltert (visibility OWNER/TENANT/ADMIN). Damit kann eine
  Store-Page nach Filter weniger Items haben als `pageSize` —
  legitim.
- `SchemaIndexEntry` / `ProfileIndexEntry` / `DiffIndexEntry` haben in
  Phase B keine per-Record-Visibility — sie werden ungefiltert fuer
  den Tenant zurueckgegeben.
- `ConnectionReferenceStore.list` nimmt selbst `PrincipalContext` und
  filtert intern nach `allowedPrincipalIds`/`allowedScopes`.

**Resource-Wire-Shape (verbindlich)**

- `uri` aus `ServerResourceUri.render()`.
- `name` aus dem Record:
  - JOB: `managedJob.jobId`
  - ARTIFACT: `managedArtifact.filename`
  - SCHEMA/PROFILE/DIFF: `displayName`
  - CONNECTION: `displayName`
- `mimeType` ist konstant `application/json` fuer alle Phase-B-Projektionen.
- `description` ist menschenlesbarer Kontext (Operation+Status fuer
  JOB; Kind+Size fuer ARTIFACT; usw.). Optional aber in Phase B
  immer gesetzt.
- ConnectionReference-Projektion DARF `credentialRef`,
  `providerRef`, JDBC-URL oder andere Secret-Felder NICHT enthalten —
  weder in `name`, `description` noch sonst irgendwo (§6.9 Akzeptanz).

**`resources/templates/list` (verbindlich)**

- Phase B liefert genau diese 7 Templates (geordnet, deterministisch):
  ```text
  dmigrate://tenants/{tenantId}/jobs/{jobId}
  dmigrate://tenants/{tenantId}/artifacts/{artifactId}
  dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}
  dmigrate://tenants/{tenantId}/schemas/{schemaId}
  dmigrate://tenants/{tenantId}/profiles/{profileId}
  dmigrate://tenants/{tenantId}/diffs/{diffId}
  dmigrate://tenants/{tenantId}/connections/{connectionId}
  ```
- Chunk-Template ist Pflicht (§6.9 Akzeptanz: "Templates enthalten
  Chunk-URIs").
- `nextCursor` ist konstant `null` (kein Paging).
- Cursor im Request wird akzeptiert aber ignoriert.

**`ServerCapabilities.resources` (Initialize)**

- AP 6.9 setzt `capabilities.resources = {"listChanged": false,
  "subscribe": false}`.
- Beide Flags bleiben `false` bis Subscriptions / Push-Notifications
  in einer spaeteren Iteration kommen (Phase C+).

**Bootstrap-Verhalten**

- `McpServerBootstrap.startHttp` und `startStdio` akzeptieren beide
  einen optionalen `resourceStores: ResourceStores`-Parameter. Default:
  `ResourceStores.empty()` (no-op stores).
- Production-Aufrufer wiren echte Stores (Phase C/D); Phase-B-Tests
  nutzen `testFixtures(:hexagon:ports-common)` InMemory-Stores.

**Test-Vertrag (AP 6.9)**

- `ResourcesListCursor`: encode/decode Roundtrip; null-Token-Roundtrip;
  null-Input -> null; ungueltige Base64 -> IAE; valides Base64 mit
  nicht-JSON -> IAE; unbekanntes `kind` -> IAE; bekanntes aber
  non-listable `kind` (z. B. `UPLOAD_SESSIONS`) -> IAE mit Reason
  "not listable"; URL-safe Encoding (kein `+`, `/`, kein Padding).
- `ResourceProjector`:
  - Job: `name=jobId`, `description` enthaelt Operation+Status.
  - Artifact: `name=filename`, `description` enthaelt Kind+SizeBytes.
  - Schema/Profile/Diff: `name=displayName`.
  - Connection: KEIN Secret in `toString()` (kein `credentialRef`,
    kein `providerRef`, kein `vault://`); `description` enthaelt
    Dialect+Sensitivity.
- `PhaseBResourceTemplates`: 7 Templates in dokumentierter Reihenfolge;
  Chunk-Template enthalten; alle MIME `application/json`.
- `ResourcesListHandler`:
  - leere Stores -> leere Liste, `nextCursor=null`.
  - voll bestueckte Stores -> alle 6 Familien werden projiziert,
    Reihenfolge stabil.
  - OWNER-Filter blendet fremde Owner-Jobs aus.
  - Foreign-Tenant-Records sind nicht sichtbar.
  - `pageSize=1` liefert 1 Resource und einen `nextCursor` (nicht
    `null`, solange weitere lesbare Items existieren).
  - Walk mit Cursor durchpaginiert konvergiert auf
    `nextCursor=null`.
  - Store liefert 0 Items + non-null `nextPageToken` -> Antwort hat
    `resources=[]` und `nextCursor != null` (Cursor pinnt aktuellen
    Kind+innerToken). Clients muessen weiter paginieren.
  - `pageSize <= 0` -> `IllegalArgumentException`.
  - Direkt-konstruierter Cursor mit `kind=UPLOAD_SESSIONS` (decode
    umgangen) -> `IllegalStateException` aus dem `pageFor`-Guard.
- `EmptyStores`:
  - alle 6 No-op-Implementierungen sind durch Smoke-Tests abgedeckt
    (save/find/list/delete) — kein Drift bei zukuenftigen
    Port-Erweiterungen.
- `McpServiceImpl`:
  - `resources/templates/list` ohne Principal funktioniert (statisch).
  - `resources/list` ohne Principal -> JSON-RPC `-32600` Invalid Request.
  - `resources/list` mit malformed Cursor -> JSON-RPC `-32602`
    Invalid params.
  - `resources/list` mit decodebarem aber non-listable Cursor (z. B.
    `kind=UPLOAD_SESSIONS`) -> JSON-RPC `-32602` Invalid params (vom
    Decoder geblockt, nicht still als leere Antwort absorbiert).
  - Cursor-Wire-Roundtrip durch JSON-RPC: opaker `nextCursor` einer
    Antwort wird verbatim als `cursor` der Folge-Antwort akzeptiert
    und konvergiert auf `null`.
  - `ServerCapabilities.resources = {"listChanged": false, "subscribe":
    false}`.

**Follow-up-Status aus AP-6.9-Review**

- Read-only Handler-Follow-ups sind in
  [`ImpPlan-0.9.6-C.md`](./ImpPlan-0.9.6-C.md) verschoben:
  Defense-in-depth Tenant-/Owner-Recheck nach Store-Load sowie Runtime-
  Scrubbing freier Output-Strings.
- Discovery-/Connection-Ref-Follow-ups sind Phase D:
  `resources/list`, `resources/read`, Discovery-Listen und
  Connection-Ref-Projektionen.
- Phase-B-Transportinvarianten bleiben in Phase B: `-32600 principal not
  bound` und Cursor-Decode-Fehlercodes werden durch Phase C nicht
  umverdrahtet.

### 12.18 JSON-Schema-Vertrag (verbindlich, fuer AP 6.10 Implementation)

**Geltungsbereich**

- AP 6.10 pinnt `inputSchema` und `outputSchema` fuer alle 0.9.6-Tools
  als JSON Schema 2020-12.
- Die Schemas sind transportneutral — `tools/list` ueber stdio und
  HTTP liefert exakt dieselben Strings (§5.6: keine
  Transport-spezifischen Varianten).
- Phase B verfeinert die Schemas Tool-fuer-Tool nicht weiter; Phase
  C/D ergaenzen Constraints, sobald die echten Handler kommen. Der
  Golden-Test pinnt die jetzige Form als Vertrag.

**Dialekt (verbindlich)**

- `JsonSchemaDialect.SCHEMA_URI = "https://json-schema.org/draft/2020-12/schema"`.
- Jedes Phase-B-Tool-Schema setzt `$schema` auf exakt diesen Wert.
- Verbotene Draft-07-Keywords (rekursiv geprueft):
  - `definitions` (durch `$defs` ersetzt)
  - `id` (durch `$id` ersetzt)
  - `dependencies` (in `dependentSchemas` / `dependentRequired`
    aufgeteilt)
- `type=object` als Wurzel ist Pflicht.
- `additionalProperties=false` ist Default fuer Inputs (strict
  payload-shape); Outputs duerfen `additionalProperties=true` setzen,
  wenn sie offen-gehaltene Metadaten transportieren (z. B.
  `capabilities_list`-Output).

**Property-Vertrag (verbindlich)**

- `SchemaSecretGuard.FORBIDDEN_PROPERTIES` zaehlt Property-Namen, die
  in keinem Phase-B-Schema (input ODER output) vorkommen duerfen,
  case-insensitive — `password`, `passwd`, `secret`, `secrets`,
  `token`, `apikey`, `api_key`, `credentialref`, `credentialsref`,
  `providerref`, `jdbcurl`, `connectionstring`, `connection_string`,
  `privatekey`, `private_key`.
- Der Guard walkt rekursiv durch `properties`, `items`,
  `additionalProperties`, `patternProperties`, `oneOf`/`anyOf`/`allOf`,
  `not`, `if`/`then`/`else`, `$defs`, `definitions` (zur Sicherheit
  trotz Verbot), `prefixItems`, `contains`, `unevaluatedItems`,
  `unevaluatedProperties`, `propertyNames`.
- Ein Schema, das einen verbotenen Property-Namen registriert, schlaegt
  den `PhaseBToolSchemasTest` (`no schema admits a secret-shaped
  property name`) — kann also nicht in `PhaseBRegistries` landen.

**Tool-Universum (verbindlich)**

- `PhaseBToolSchemas` registriert genau die Tool-Namen, die in
  `McpServerConfig.DEFAULT_SCOPE_MAPPING` stehen MINUS der MCP-
  Protocol-Methoden (`tools/list`, `tools/call`, `resources/list`,
  `resources/templates/list`, `resources/read`, `connections/list`).
- `PhaseBRegistries.toolRegistry(custom)` schlaegt mit
  `IllegalStateException` fehl, wenn das Custom-Mapping einen
  Tool-Namen enthaelt, fuer den kein Schema in `PhaseBToolSchemas`
  registriert ist — verhindert "Tool im Vertrag, aber kein Schema".

**Schema-Builder-Konvention**

- Pro Tool ein `Pair(inputSchema, outputSchema)` ueber den
  internen `SchemaBuilder`.
- `obj("field" to typeMap, ...).required("a", "b").build()` ist die
  Standardform; `.required(...)` und `.build()` sind beide terminale
  Operationen, die `Map<String, Any>` zurueckgeben.
- Listing-Tools nutzen den `listInput("itemsField")`-Helper fuer
  Cursor-Pagination.
- Job-Start-Tools nutzen `jobStart("primaryConnectionField")` fuer
  die `connectionId`+`includes`/`excludes`+`jobId`-Form.

**Golden-Test (verbindlich)**

- `PhaseBToolSchemasGoldenTest` serialisiert alle Schemas
  alphabetisch nach Tool-Namen sortiert mit Gson `setPrettyPrinting()`
  und vergleicht das Ergebnis mit
  `src/test/resources/golden/phase-b-tool-schemas.json`.
- Aktualisierung des Golden-Files: `UPDATE_GOLDEN=true` als Env-Var
  oder `-DUPDATE_GOLDEN=true` als JVM-System-Property beim
  Test-Lauf. Der Test schreibt dann die Datei neu und beendet
  erfolgreich; ein Code-Review prueft die Diff.
- CI laeuft ohne Update-Flag und failt bei Drift.

**Test-Vertrag (AP 6.10)**

- `JsonSchemaDialect`: konstante URIs und Forbidden-Keywords.
- `SchemaSecretGuard`: clean schema -> empty leaks; top-level,
  nested, `items`, `oneOf`, `$defs` Property-Leaks werden gefangen;
  case-insensitive Match; non-`properties` Top-Level-Keys
  (`type`/`required`/`description`/`title`) werden NICHT als
  Payload-Namen interpretiert.
- `PhaseBToolSchemas`:
  - jedes 0.9.6-Tool aus `DEFAULT_SCOPE_MAPPING` (minus
    Protocol-Methoden) hat `inputSchema` UND `outputSchema`.
  - Protocol-Methoden sind NICHT registriert.
  - jedes Schema hat `$schema = .../draft/2020-12/schema`.
  - jedes Schema hat `type=object` an der Wurzel.
  - keine Forbidden-Draft-07-Keywords auf irgendeiner Ebene.
  - keine Forbidden-Secret-Property-Namen.
  - `forTool(unknown)` -> `null`.
  - `toolNames()` ist alphabetisch sortiert (Stabilitaet fuer Golden).
  - `capabilities_list` Input ist leeres Objekt-Schema.
  - Listing-Tools (`*_list`) teilen `pageSize`+`cursor`-Inputs.
  - `schema_validate.input.required` enthaelt `schemaUri`.
- `PhaseBRegistries`: rejected Custom-Mapping mit Tool-Namen ohne
  Schema -> `IllegalStateException`.
- `PhaseBToolSchemasGoldenTest`: serialisierte Schemas matchen
  `phase-b-tool-schemas.json` bis aufs Byte; `UPDATE_GOLDEN`
  regeneriert die Datei.

**Follow-up-Status aus AP-6.10-Review**

- Runtime-Scrubbing fuer `additionalProperties: true`-Slots ist in
  [`ImpPlan-0.9.6-C.md`](./ImpPlan-0.9.6-C.md) verschoben.
- Golden-File-Workflow ist in
  `src/test/resources/golden/README.md` dokumentiert.
- `PhaseBToolSchemasGoldenTest` nutzt bereits defensive Working-Dir-
  Erkennung; weitere Testpfade sollen diese Erkennung erweitern statt
  neue Annahmen einzubauen.
