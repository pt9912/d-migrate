# Implementierungsplan: 0.9.6 - Phase G `KI-nahe Tools, Prompts, Tests und Dokumentation`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: G (`KI-nahe Tools, Prompts, Tests und Dokumentation`)
> **Status**: Draft (2026-05-03)
> **Referenz**: `docs/planning/in-progress/implementation-plan-0.9.6.md`
> Abschnitt 4.3, Abschnitt 4.4, Abschnitt 4.5, Abschnitt 4.8,
> Abschnitt 5.4, Abschnitt 6.10, Abschnitt 6.11, Abschnitt 8 Phase G,
> Abschnitt 9, Abschnitt 10, Abschnitt 11 und Abschnitt 12;
> `docs/planning/done/ImpPlan-0.9.6-A.md`;
> `docs/planning/done/ImpPlan-0.9.6-B.md`;
> `docs/planning/in-progress/ImpPlan-0.9.6-C.md`;
> `docs/planning/open/ImpPlan-0.9.6-D.md`;
> `docs/planning/open/ImpPlan-0.9.6-E0.md`;
> `docs/planning/open/ImpPlan-0.9.6-E.md`;
> `docs/planning/open/ImpPlan-0.9.6-F.md`; `spec/ki-mcp.md`;
> `spec/job-contract.md`; `spec/architecture.md`; `spec/design.md`;
> `docs/user/guide.md`; `docs/planning/roadmap.md`;
> `adapters/driving/mcp`; `hexagon/application`;
> `hexagon/ports-common`; `hexagon/ports-read`; `hexagon/ports-write`.

---

## 1. Ziel

Phase G schliesst den 0.9.6-MCP-Umfang ab. Sie implementiert die KI-nahen
Spezialtools, MCP-Prompts, Provider-Hygiene, Testabdeckung und
Dokumentationsnachzuege, ohne die bis Phase F aufgebauten Sicherheits-,
Policy-, Idempotency-, Resource-, Upload- und Job-Vertraege zu umgehen.

Phase G liefert konkrete technische Ergebnisse:

- adapterneutraler `AiProviderPort` bzw. KI-Ausfuehrungsport
- deterministischer `NoOp`-Provider fuer Tests und lokale Defaults
- optionale lokale Provider-Konfiguration fuer Ollama/LM Studio
- fail-closed Konfigurationsmodell fuer externe Provider
- zentraler Secret-Scrubbing- und Prompt-Hygiene-Service
- Provider-, Modell-, Modellversions-, Prompt-Fingerprint- und
  Payload-Fingerprint-Metadaten im Audit-Trail
- MCP-Tools:
  - `procedure_transform_plan`
  - `procedure_transform_execute`
  - `testdata_plan`
- synchrone Idempotency fuer KI-nahe Tools ueber `approvalKey`
- Policy-/Approval-Anbindung fuer alle KI-nahen Tools
- MCP-Prompt-Registry mit verbindlichen Prompt-Vertraegen:
  - `procedure_analysis`
  - `procedure_transformation`
  - `testdata_planning`
- `prompts/list` und `prompts/get` inklusive Argument-Schemas,
  Argumentvalidierung und deterministischer Fehler
- Unit-, Adapter-, Integration- und Golden-Tests fuer den gesamten
  0.9.6-MCP-Vertrag
- Dokumentationsnachzug fuer lokale und entfernte MCP-Clients,
  Sicherheitsmodell, Tool-Liste, Prompt-Liste und bekannte Grenzen

Nach Phase G soll gelten:

- Kein KI-nahes Tool ruft einen Provider ohne Policy-Freigabe,
  Prompt-Hygiene und Audit-Metadaten auf.
- Der Default-Testpfad nutzt keinen Netzwerkzugriff und keine externen
  Secrets.
- Identische und parallele Retries mit gleichem `approvalKey` und identischem
  Payload erzeugen keinen zweiten Provider-Aufruf und kein zweites Artefakt.
- Prompts sind ueber MCP-Discovery sichtbar, versioniert und
  argumentvalidiert.
- Prompts fuehren keine versteckten Tools aus und kopieren keine Secrets oder
  Rohdaten in den Modellkontext.
- Die Roadmap-Aufgaben aus 0.9.6 sind durch Tests oder explizite
  Abnahmekriterien abgedeckt.

---

## 2. Ausgangslage

### 2.1 Phasen A bis F liefern den Sicherheitsrahmen

Phase G baut auf den bereits geplanten oder erledigten 0.9.6-Phasen auf:

- Phase A/B: MCP-Adapter-Grundlagen, Auth, Principal-Kontext,
  Tool-Registry und Basistransporte
- Phase C: read-only Tools, Schema-Staging, Artifact-Reads und
  Inline-/Chunk-Limits
- Phase D: Resources, Listen, Resource-Kinds, Cursor, Tenant-Scope und
  Datenschutzgrenzen
- Phase E0/E: `job_cancel`, Jobmodell, Idempotency, Policy, Approval,
  Audit, Quotas und Timeouts
- Phase F: policy-pflichtige Uploads, `job_input`-Artefakte, Import,
  Transfer und grosse Byte-Stores

Phase G darf keine parallele Auth-, Policy-, Approval-, Idempotency-,
Resource-, Upload- oder Job-Architektur einfuehren.

### 2.2 `spec/ki-mcp.md` ist das fachliche Zielbild

`spec/ki-mcp.md` fordert fuer KI-Umgebungen:

- kleine, eindeutige MCP-Tools
- begrenzte Tool-Responses
- Ressourcen statt Massendaten im Chat-Kontext
- kontrollierte Write-Tools mit Policy
- KI-nahe Spezialtools unter Provider- und Audit-Strategie
- kuratierte MCP-Prompts fuer Analyse-, Transformations- und
  Testdatenablaeufe

Phase G macht diese Spezifikation fuer 0.9.6 testbar.

### 2.3 KI-/Testdatenmodule sind noch nicht voll ausgebaut

`spec/architecture.md` beschreibt `ai/` und `testdata/` als geplante Module.
Phase G implementiert deshalb keinen vollstaendigen produktiven
KI-Stack, sondern einen minimalen adapterneutralen Port mit
deterministischem Provider und klaren Erweiterungspunkten.

Das Ziel ist:

- MCP-Tools sind real registrierbar.
- Policy, Audit, Hygiene und Idempotency sind produktionsnah.
- Tests koennen ohne externe Provider stabil laufen.
- Externe Provider bleiben optional und explizit konfiguriert.

---

## 3. Scope fuer Phase G

### 3.1 In Scope

- `AiProviderPort` bzw. aequivalenter adapterneutraler KI-Port
- Datenmodelle fuer:
  - redigierte Eingabe-Summary
  - Resource-/Artifact-Refs
  - Provider-Auswahl
  - Modell-Auswahl
  - Policy-Kontext
  - Prompt-Fingerprint
  - Payload-Fingerprint
  - Provider-Antwort-Metadaten
- `NoOpAiProvider`
- optionale lokale Provider-Konfigurationsoberflaeche fuer Ollama/LM Studio
  als vorbereiteter, fail-closed Pfad
- externe Provider-Konfigurationsmodellierung fuer OpenAI/Anthropic oder
  gleichartige Anbieter, aber nur blockiert, solange keine erlaubende
  Konfiguration und Policy vorliegt
- `PromptHygieneService`
- Secret-Scrubbing fuer:
  - freie JDBC-Strings
  - Passwoerter
  - Tokens
  - API-Keys
  - Connection-Secrets
  - bekannte Secret-Patterns in JSON/YAML/Text
- Massendaten-/Rohdaten-Blockade fuer Prompt- und Provider-Payloads
- Tool-Handler fuer:
  - `procedure_transform_plan`
  - `procedure_transform_execute`
  - `testdata_plan`
- synchrone Idempotency-Reservierung ueber `approvalKey`
- Policy-Required-Flow fuer alle drei Tools
- Provider-Audit fuer alle Erfolg- und Fehlerpfade, soweit ein Provider-
  Aufruf versucht oder blockiert wird
- Prompt-Registry
- `prompts/list`
- `prompts/get`
- stabile Prompt-Namen:
  - `procedure_analysis`
  - `procedure_transformation`
  - `testdata_planning`
- JSON Schema 2020-12 fuer Tool- und Prompt-Argumente
- MCP-Handler-Tests fuer jedes KI-nahe Tool
- MCP-Prompt-Tests fuer `stdio` und HTTP
- Fehler-Envelope-Golden-Tests
- Policy- und Idempotency-Konflikttests
- Quota- und Timeout-Tests fuer Provider-Pfade
- Dokumentationsupdates fuer:
  - `spec/ki-mcp.md`
  - `docs/user/guide.md`
  - `docs/planning/roadmap.md`
  - ggf. lokale MCP-Startdokumente

### 3.2 Nicht in Scope

- produktive Prompt-Engineering-Suite
- allgemeine Chat- oder Agenten-Oberflaeche
- freie Modellaufrufe als generisches MCP-Tool
- freie SQL-, JDBC- oder Secret-Payloads
- produktive Cloud-KI-Provider als Default
- automatische Provider-Auswahl ohne explizite Konfiguration
- automatische Ausfuehrung von durch KI erzeugtem SQL oder Code
- produktive Datenbank-Schreiboperationen durch `testdata_plan`
- neue Upload-Protokolle ausserhalb der in Phase F definierten Tools
- neue Approval-UI
- Cross-Tenant-Analyse ohne explizites Principal-/Scope-Modell
- Speicherung kompletter Prompts oder Rohdaten in Audit-Events

---

## 4. Leitentscheidungen

### 4.1 `NoOp` ist der Default-Provider

Ohne explizite Provider-Konfiguration nutzt Phase G einen deterministischen
`NoOp`-Provider.

Der `NoOp`-Provider:

- macht keinen Netzwerkaufruf
- liest keine Secrets
- liefert stabile, testbare Antworten
- erzeugt Provider-Metadaten mit `provider=noop`
- ist fuer lokale Tests und CI geeignet
- kann Plan-/Transformationsantworten synthetisch aus erlaubten Summaries
  erzeugen

Akzeptanz:

- Alle KI-nahen Tool-Tests koennen ohne externe Secrets laufen.
- CI braucht keinen Netzwerkzugriff fuer Provider-Aufrufe.
- `capabilities_list` weist den aktiven Provider-Modus aus.

### 4.2 Externe Provider sind fail-closed

Externe Provider duerfen nur genutzt werden, wenn alle Bedingungen erfuellt
sind:

- Provider ist serverseitig explizit konfiguriert.
- Endpoint ist erlaubt.
- Modell ist erlaubt.
- Secret-Quelle ist serverseitig registriert.
- Policy erlaubt externe Provider-Nutzung fuer Tenant, Caller, Tool,
  Resource-Refs und Payload-Fingerprint.
- Prompt-Hygiene hat keine Secrets oder Rohdaten gefunden.

Fehlt eine Bedingung, startet kein Provider-Aufruf.

Fehlerzuordnung:

- fehlende erlaubende Policy: `POLICY_REQUIRED` oder
  `FORBIDDEN_PRINCIPAL`, je nach Policy-Entscheidung
- blockierte externe Provider-Konfiguration: `PROMPT_HYGIENE_BLOCKED` oder
  `FORBIDDEN_PRINCIPAL`
- fehlendes Secret in einem erlaubten Providerpfad:
  `INTERNAL_AGENT_ERROR` fuer fehlerhafte Serverkonfiguration oder
  `FORBIDDEN_PRINCIPAL`/`POLICY_DENIED`, wenn der Principal bzw. die Policy
  die Secret-/Provider-Nutzung nicht erlaubt.
- Provider-Timeout: `OPERATION_TIMEOUT`
- Provider-Quota: `RATE_LIMITED`

### 4.3 Prompt-Hygiene ist zentral und vor jedem Provider-Aufruf

Der MCP-Adapter darf KI-nahe Payloads nicht direkt an Provider uebergeben.
Jeder Pfad laeuft durch denselben Hygiene-Service:

1. Payload normalisieren
2. erlaubte Resource-/Artifact-Refs aufloesen
3. Summaries begrenzen
4. Secrets scrubben oder blockieren
5. Rohdaten-/Massendaten-Muster erkennen
6. Prompt-Fingerprint bilden
7. Provider-Request erzeugen

Verbindliche Regel:

- Wenn der Hygiene-Service eine Verletzung findet, gibt das Tool
  `PROMPT_HYGIENE_BLOCKED` zurueck und ruft keinen Provider auf.

### 4.4 KI-nahe Tools nutzen `approvalKey` als synchrone Idempotency

Alle drei KI-nahen Tools sind policy-gesteuert und verlangen `approvalKey`.

Der serverseitige Scope-Key besteht aus:

- `tenantId`
- `callerId`
- `toolName`
- `approvalKey`

Der Fingerprint wird aus normalisiertem Payload ohne `approvalToken`
gebildet.

Regeln:

- fehlender `approvalKey` liefert `VALIDATION_ERROR`
- identischer Retry liefert dasselbe Ergebnis bzw. dieselbe
  Artefakt-/Provider-Referenz
- gleicher Scope-Key mit anderem Payload liefert `IDEMPOTENCY_CONFLICT`
  ohne vorherige Policy-Pruefung
- erster Aufruf ohne Grant liefert `POLICY_REQUIRED`
- zweiter Aufruf muss denselben `approvalKey` und ein extern ausgestelltes,
  passendes `approvalToken` senden
- Approval-Token werden nie in Tool-Responses erzeugt
- `SyncEffectIdempotencyStore` darf fuer KI-nahe Provider-Side-Effects nicht
  nur als spaeter Replay-Store genutzt werden. Phase G muss eine atomare
  Single-Writer-/Pending-Semantik einfuehren, entweder als Store-Erweiterung
  oder als dedizierter Outcome-Store fuer KI-Tool-Aufrufe:
  - erster Caller mit neuem `(tenantId, callerId, toolName, approvalKey,
    payloadFingerprint)` erhaelt den Ausfuehrungs-Claim.
  - parallele identische Caller duerfen keinen Provider-Aufruf starten und kein
    zweites Artefakt schreiben; sie warten/replayen oder erhalten einen klar
    retrybaren Pending-Status.
  - nach durablem Provider-Ergebnis, Artefakt-Commit und Outcome-Commit liefert
    jeder identische Retry `Existing(resultRef)` bzw. dasselbe Tool-Result.
  - terminale Fehler nach einem begonnenen Provider-Aufruf muessen ebenfalls
    als Outcome committed werden, auch wenn kein Artefakt publiziert wurde.
    Dazu zaehlen Output-Hygiene-Blockaden, Provider-Timeouts,
    Provider-Ausfuehrungsfehler und Result-Limitierungsfehler. Identische
    Retries replayen denselben strukturierten Fehler und starten keinen
    zweiten Provider-Aufruf, solange der Fehler als terminal klassifiziert ist.
  - nicht-terminal klassifizierte Provider-/Recovery-Zustaende muessen
    explizit als retrybarer Pending-/Recovery-Outcome persistiert werden; auch
    dann darf ein Retry nicht parallel einen zweiten Provider-Aufruf starten.
  - ein Crash vor terminalem Outcome darf den Scope nicht dauerhaft blockieren;
    Pending-Claims brauchen Lease-/Reclaim-Semantik oder einen
    crash-sicheren Outcome-Commit, der vor Replay immer durable Artefakte
    nachweist.
  - ein Crash nach Provider-Ergebnis, aber vor Outcome-Commit darf beim Reclaim
    nicht ungeprueft einen zweiten Provider-Aufruf starten; zuerst ist anhand
    persistenter Artefakte/Provider-Request-Metadaten ein vorhandenes Ergebnis
    zu replayen oder sauber als retrybarer Pending-/Recovery-Zustand zu melden.

### 4.5 Prompts sind Vorlagen, keine versteckten Tool-Ausfuehrungen

`prompts/get` erzeugt nur Prompt-Nachrichten. Es darf:

- keine Tools ausfuehren
- keine Jobs starten
- keine Uploads anlegen
- keine Provider aufrufen
- keine Artefakte erzeugen

Prompts koennen erlaubte Tools, Ressourcen und erwartete Arbeitsschritte
benennen. Die Ausfuehrung bleibt beim Client bzw. beim expliziten
`tools/call`.

### 4.6 Ressourcen statt Rohdaten im Modellkontext

KI-nahe Tools und Prompts akzeptieren fuer groessere Eingaben nur
Referenzen:

- `schemaRef`
- `artifactRef`
- `profileRef`
- `diffRef`
- `connectionRef` nur als non-secret, tenant-scoped Referenz
- kleine strukturierte Inline-Optionen

Verboten:

- freie JDBC-Strings
- Passwoerter
- API-Keys
- rohe Tabellenzeilen
- grosse DDL-/SQL-/JSON-/CSV-Bloecke im Prompt- oder Tool-Payload
- Base64-Artefakte ausserhalb von Phase-F-Upload-Tools

### 4.7 Resultate bleiben begrenzt und referenziert

Tool-Responses folgen den 0.9.6-Limits:

- maximal `64 KiB` serialisierte Tool-Response
- maximal `200` inline Findings
- groessere Plaene oder Transformationsergebnisse als Artefakt oder
  Resource-Ref
- keine unlimitierten Rohdaten
- keine Secrets

Wenn ein Ergebnis groesser ist:

- Tool liefert Summary, Findings-Ausschnitt und `artifactId` oder
  `resourceUri`
- vollstaendiger Inhalt ist ueber Resource-/Artifact-Pfade lesbar
- Zugriff prueft Tenant, Principal, Artifact-Kind und ggf. KI-Intent

### 4.8 Audit speichert Fingerprints, keine Secrets

Audit-Events fuer KI-nahe Tools enthalten mindestens:

- `requestId`
- `tenantId`
- `principalId`
- `toolName`
- `approvalKey` oder dessen Fingerprint
- `payloadFingerprint`
- `promptFingerprint`
- Resource-/Artifact-Refs
- Provider
- Modell
- Modellversion
- Provider-Request-Id, falls vorhanden
- Ergebnisreferenz
- Fehlercode
- Quota-/Timeout-Metadaten, falls relevant

Audit-Events duerfen nicht enthalten:

- `approvalToken`
- Provider-API-Key
- Connection-Secrets
- rohe Massendaten
- vollstaendige Prompts mit sensiblen Daten

---

## 5. Vertragsmodell

### 5.1 `AiProviderPort`

Der Port bleibt adapterneutral und enthaelt keine MCP-Typen.

Minimaler Vertrag:

```kotlin
interface AiProviderPort {
    fun execute(request: AiProviderRequest): AiProviderResult
}
```

`AiProviderRequest` enthaelt:

- `tenantId`
- `principalId`
- `operation`
- `providerId`
- `modelId`
- `modelVersion`
- `promptRevision`
- `promptFingerprint`
- `payloadFingerprint`
- `policyContext`
- `messages`
- `inputRefs`
- `limits`
- `requestTimeout`

`AiProviderResult` enthaelt:

- `providerId`
- `modelId`
- `modelVersion`
- `providerRequestId`
- `createdAt`
- `summary`
- `findings`
- strukturierte, sanitisierbare Provider-Ausgabe, aber keine d-migrate-
  Artefaktreferenz
- `usage`
- `finishReason`
- `rawProviderMetadata`, bereinigt und optional

Nicht erlaubt:

- MCP-Request-Objekte im Port
- freie Secret-Strings im Request
- unredigierte Connection-Konfiguration
- d-migrate-`artifactRef`/`resourceUri` im Provider-Result vor
  Output-Hygiene und Publish
- Provider-spezifische Exceptions bis in Tool-Handler

### 5.2 Provider-Konfiguration

Provider-Konfiguration ist serverseitig.

Pflichtfelder fuer konfigurierte Provider:

- `providerId`
- `providerKind`
- `enabled`
- `endpoint`
- `allowedModels`
- `secretRef`
- `defaultTimeout`
- `maxPromptBytes`
- `maxOutputBytes`
- `allowExternalNetwork`
- `auditMode`

Regeln:

- `enabled=false` blockiert Provider-Aufrufe.
- `allowExternalNetwork=false` blockiert nicht-lokale Endpoints.
- `secretRef` wird erst im Provider-Adapter aufgeloest.
- Secrets werden nie in `capabilities_list`, Tool-Responses, Prompts oder
  Audit-Events ausgegeben.
- Lokale Provider duerfen nur explizit konfigurierte Loopback- oder
  lokale Netzwerkziele nutzen.

### 5.3 Prompt-Hygiene-Service

Der Service stellt eine zentrale API bereit:

```kotlin
interface PromptHygieneService {
    fun sanitize(request: PromptHygieneRequest): PromptHygieneResult
}
```

`PromptHygieneRequest` enthaelt:

- Tool- oder Promptname
- Tenant und Principal
- erlaubte Resource-/Artifact-Refs
- normalisierten Payload
- inline Optionen
- Provider-Ziel
- Limits

`PromptHygieneResult` enthaelt:

- bereinigte Messages oder Prompt-Fragmente
- `promptFingerprint`
- `payloadFingerprint`
- erkannte und entfernte Secret-Klassen
- erlaubte Input-Refs
- Entscheidung `ALLOW` oder `BLOCK`
- deterministischer Fehlergrund bei `BLOCK`

Blockierende Faelle:

- freier JDBC-String
- Secret-Pattern
- API-Key-Pattern
- `approvalToken` im Prompt-Payload
- rohe Massendaten oberhalb der Inline-Limits
- nicht erlaubter Resource-Kind
- tenantfremde Resource
- externe Provider-Nutzung ohne erlaubenden Kontext

### 5.4 `procedure_transform_plan`

Zweck:

- Analyse- und Transformationsplan fuer eine gespeicherte Prozedur oder ein
  Prozedur-Artefakt erzeugen.

Input:

- `approvalKey`
- optional `approvalToken`
- genau eine Quelle:
  - `procedureRef`
  - `artifactRef`
  - `schemaRef` plus `procedureName`
- `targetDialect`
- optionale kleine `rules`
- optionale `profileRef`
- optionale `diffRef`
- optionale Provider-Auswahl

Regeln:

- Tool ist policy-gesteuert.
- Ergebnis ist ein Plan, kein ausfuehrbarer Zielcode.
- Jeder erfolgreiche Aufruf persistiert ein immutable Plan-Artefakt mit
  verbindlichem `wireArtifactKind=procedure-transform-plan` und gibt eine stabile
  `planRef` bzw. `planArtifactId` zurueck. Inline-Daten in `summary` und
  `findings` sind nur Preview und duerfen nicht als Execute-Quelle gelten.
- Grosse Planinhalte werden nicht inline geliefert, sondern ausschliesslich
  ueber `planRef`/`planArtifactId` bzw. Resource-Reads referenziert.
- Secrets und Rohdaten sind verboten.
- `schema_staging_readonly` darf nur als Analysekontext genutzt werden, wenn
  Resource-Kind, Intent und Policy dies erlauben.

Output:

- `summary`
- `findings`
- `planRef` oder `planArtifactId`
- optional `planResourceUri` als lesbare Resource-Referenz fuer grosse Plaene
- `providerMeta`
- `executionMeta`

Artefaktmodell:

- Phase G fuehrt kein stillschweigendes neues Core-`ArtifactKind` ein. Solange
  der Core-Enum nur `SCHEMA`, `PROFILE`, `DIFF`, `DATA_EXPORT`,
  `UPLOAD_INPUT` und `OTHER` kennt, werden KI-nahe Plan-/Output-Artefakte als
  `ArtifactKind.OTHER` gespeichert.
- Die fachliche Typisierung ist dann Pflicht-Metadatum, nicht frei
  interpretierbarer Text. Verbindliche Werte fuer Phase G:
  - `wireArtifactKind=procedure-transform-plan`,
    `aiIntent=procedure_transform_plan` fuer Plaene aus
    `procedure_transform_plan`
  - `wireArtifactKind=procedure-transform-output`,
    `aiIntent=procedure_transform_execute` fuer Zielartefakte aus
    `procedure_transform_execute`
  - `wireArtifactKind=testdata-plan`, `aiIntent=testdata_plan` fuer
    Testdatenplaene aus `testdata_plan`
- Alle KI-Artefakte speichern persistente Provenance mit Tenant,
  Principal/Policy-Intent, Source-Refs, `targetDialect`, Prompt-/Payload-
  Fingerprint, Provider-/Modell-Metadaten, erzeugendem Toolnamen und bei
  Execute-Outputs zusaetzlich `planRef`/`planArtifactId` samt Plan-
  Fingerprint.
- `procedure_transform_execute`, Resource-Reads und Prompts duerfen Plan-
  Artefakte nur ueber diese Metadaten als Procedure-Transformationsplan
  akzeptieren. Ein Core-`ArtifactKind.OTHER` ohne diese Metadaten ist kein
  gueltiger Plan.
- Falls Phase G stattdessen den Core-Enum erweitert, muss derselbe AP auch
  Artifact-Stores, Specs, Schemas, Golden-Tests, Resource-Kind-Mapping und
  Migration/Kompatibilitaet aktualisieren; beide Modelle duerfen nicht
  uneinheitlich nebeneinander verwendet werden.

### 5.5 `procedure_transform_execute`

Zweck:

- freigegebene Procedure-Transformation ausfuehren und Zielartefakt erzeugen.

Input:

- `approvalKey`
- optional `approvalToken`
- `planRef` oder `planArtifactId`
- `targetDialect`
- optionale kleine Ausfuehrungsoptionen
- optionale Provider-Auswahl

Regeln:

- Tool ist bestaetigungspflichtig und policy-gesteuert.
- Tool erzeugt hoechstens Artefakte, fuehrt aber keinen Ziel-DB-Code aus.
- Erfolgreiche Zielartefakte nutzen `wireArtifactKind=procedure-transform-output`,
  `aiIntent=procedure_transform_execute` und persistieren Provenance inklusive
  verwendeter Plan-Referenz, Plan-Fingerprint, Source-Refs, `targetDialect`,
  Prompt-/Payload-Fingerprint und Provider-/Modell-Metadaten.
- `planRef`/`planArtifactId` muss auf ein freigegebenes Plan-Artefakt aus
  `procedure_transform_plan` zeigen. Zulaessig ist nur ein Plan mit passendem
  Tenant, berechtigtem Principal bzw. Policy-Intent, passendem Source-Ref-
  Set, `targetDialect`, Prompt-/Payload-Fingerprint, Provider-/Modell-
  Provenance und `wireArtifactKind=procedure-transform-plan`.
- Stale, fremde, manuell hochgeladene oder andersartige Artefakte duerfen
  nicht als Transformationsplan verwendet werden. Abweichende Provenance,
  falscher `wireArtifactKind`, fremder Tenant, falscher Dialekt oder nicht zum
  Execute-Payload passende Source-Refs liefern einen fachlichen Fehler vor
  Provider-Aufruf.
- Provider-, Modell-, Prompt- und Payload-Fingerprints sind auditpflichtig.
- Output-Artefakte sind immutable.
- Wiederholung mit gleichem `approvalKey` und identischem Payload liefert
  dieselbe Zielreferenz.

Output:

- `summary`
- `findings`
- `targetArtifactId`
- `targetResourceUri`
- `providerMeta`
- `executionMeta`

### 5.6 `testdata_plan`

Zweck:

- Testdatenplan aus Schema, Regeln und optionalen Profil-Summaries erzeugen.

Input:

- `approvalKey`
- optional `approvalToken`
- `schemaRef`
- optionale `profileRef`
- kleine strukturierte `rules`
- `targetDialect`
- optionale Provider-Auswahl

Regeln:

- Tool erzeugt einen Plan, keine produktiven Datenbank-Schreiboperation.
- Tool darf keine echten sensiblen Daten in Testdaten kopieren.
- Profiling-Daten duerfen nur als verdichtete Summary oder erlaubte
  Resource-Ref genutzt werden.
- Jeder erfolgreiche Aufruf persistiert einen immutable Testdatenplan mit
  `wireArtifactKind=testdata-plan`, `aiIntent=testdata_plan` und Provenance
  ueber Tenant, Principal/Policy-Intent, `schemaRef`, optionale `profileRef`,
  `targetDialect`, Prompt-/Payload-Fingerprint sowie Provider-/Modell-
  Metadaten. Inline-`summary`/`findings` sind nur Preview.
- Grosse Plaene werden ausschliesslich als Artefakt/Resource referenziert.

Output:

- `summary`
- `findings`
- `testdataPlanArtifactId` oder `testdataPlanResourceUri`
- `providerMeta`
- `executionMeta`

### 5.7 Prompt-Registry

Die Prompt-Registry ist serverseitig und versioniert.

Pflichtprompts:

| Name | Zweck | Mindestargumente |
| --- | --- | --- |
| `procedure_analysis` | Procedure-Analyse auf Basis von Schema-/Artefaktreferenzen | `schemaRef` oder `artifactRef`, optional `procedureName` |
| `procedure_transformation` | Procedure-Transformation mit explizitem Policy-Hinweis | `planRef` oder `planArtifactId` bzw. `artifactRef` nur mit `wireArtifactKind=procedure-transform-plan`, `targetDialect` |
| `testdata_planning` | Testdatenplanung auf Basis von Schema, Regeln und Profil-Summaries | `schemaRef`, optional `profileRef`, optionale `rules` |

Jeder Prompt enthaelt:

- stabilen `name`
- `description`
- `revision`
- JSON Schema 2020-12 fuer Argumente
- erlaubte Resource-Kinds
- erwartete Tool-Schritte
- Hygiene-Regeln
- kurze Prompt-Nachrichten

`prompts/list` liefert nur Metadaten und Argument-Schemas.

`prompts/get`:

- validiert Argumente
- prueft Resource-/Artifact-Refs
- nutzt Prompt-Hygiene
- erzeugt nur Prompt-Nachrichten
- nennt benoetigte Tools und Resource-Refs
- liefert keine Secrets oder Rohdaten

---

## 6. Umsetzungsschritte

### AP G.1 - Bestandsaufnahme und Vertragsschnitt

Aufgaben:

- bestehende MCP-Tool-Registry und Handler-Struktur pruefen
- vorhandene Policy-, Approval-, Idempotency-, Audit- und Error-Services
  identifizieren
- vorhandene Artifact-/Resource-Resolver fuer KI-Inputs erfassen
- passende Modulgrenzen fuer KI-Port und Prompt-Hygiene festlegen
- fehlende Store- oder Service-Abstraktionen dokumentieren

Ergebnis:

- kurzer Implementierungszuschnitt pro Modul
- keine neue Parallelarchitektur fuer MCP-Querschnittsdienste

### AP G.2 - KI-Port und `NoOp`-Provider

Aufgaben:

- `AiProviderPort` definieren
- Request-/Result-Datenklassen anlegen
- `NoOpAiProvider` implementieren
- Provider-Registry oder Provider-Factory einhaengen
- `capabilities_list` um Provider-/Modell-Faehigkeiten erweitern
- Timeouts und Output-Limits in Provider-Aufrufe integrieren

Tests:

- `NoOp` liefert deterministische Ergebnisse
- kein Netzwerkzugriff im Defaultpfad
- Provider-Timeout wird als `OPERATION_TIMEOUT` strukturiert gemappt
- unbekannter Provider wird deterministisch abgewiesen

### AP G.3 - Provider-Konfiguration fail-closed

Aufgaben:

- serverseitige Provider-Konfiguration modellieren
- lokale Provider-Ziele fuer Ollama/LM Studio vorbereiten
- externe Provider nur explizit konfigurierbar machen
- Secret-Refs statt Secret-Werte verwenden
- Policy-Check vor externer Provider-Nutzung erzwingen
- Audit-Metadaten fuer Provider-Entscheidungen schreiben

Tests:

- externer Provider ohne Konfiguration wird blockiert
- externer Provider ohne Policy wird blockiert
- fehlendes Secret wird nicht geleakt
- lokaler Provider ist nur bei erlaubtem Endpoint aktivierbar

### AP G.4 - Prompt-Hygiene und Secret-Scrubbing

Aufgaben:

- `PromptHygieneService` implementieren
- Secret-Pattern fuer JDBC, URLs, Passwoerter, Tokens und API-Keys
  definieren
- Rohdaten-/Massendaten-Heuristik implementieren
- Resource-/Artifact-Kind-Pruefung anbinden
- Prompt- und Payload-Fingerprints deterministisch bilden
- Fehler `PROMPT_HYGIENE_BLOCKED` mit bereinigten Details liefern

Tests:

- JDBC-URL mit Passwort wird blockiert
- API-Key-Pattern wird blockiert
- grosse CSV-/JSON-/SQL-Rohdaten werden blockiert
- erlaubte kleine Optionen bleiben erlaubt
- Fingerprints sind stabil
- Fehlerdetails enthalten keine Secrets

### AP G.5 - KI-nahe Tool-Schemas und Registry

Aufgaben:

- JSON Schema 2020-12 fuer alle drei Tools definieren
- Toolbeschreibungen in Registry ergaenzen
- Scope- und Policy-Metadaten setzen
- Output-Schemas oder strukturierte Output-Vertraege definieren
- Limits aus `capabilities_list` wiederverwenden

Tests:

- Schema-Golden-Tests fuer alle drei Tools
- gueltiger Minimalaufruf pro Tool
- ungueltiger Payload pro Tool
- fehlender `approvalKey` pro Tool
- unbekannte/unerlaubte Optionen deterministisch als fachlicher Fehler

### AP G.6 - KI-nahe Tool-Handler

Aufgaben:

- `procedure_transform_plan` anbinden
- `procedure_transform_execute` anbinden
- `testdata_plan` anbinden
- Policy-Required-Flow einheitlich nutzen
- `approvalKey`-Idempotency fuer synchrone Side Effects mit atomarer
  Single-Writer-/Pending-Semantik nutzen; der bestehende
  `SyncEffectIdempotencyStore` reicht unveraendert nicht aus, wenn parallele
  gleiche Pending-Reserves erneut `Reserved` liefern.
- Provider-Aufrufe ueber Hygiene-Service und Port fuehren
- `procedure_transform_plan` muss auch fuer kleine Ergebnisse ein immutable
  Plan-Artefakt bzw. eine stabile `planRef` persistieren; Inline-Preview ist
  kein Execute-Eingang.
- KI-nahe Artefaktmetadaten fuer Plan-/Output-Artefakte modellieren oder den
  Core-`ArtifactKind` inklusive Stores, Specs, Schemas und Goldens explizit
  erweitern. Ohne Enum-Erweiterung gilt `ArtifactKind.OTHER` plus
  verpflichtendes `wireArtifactKind`/`aiIntent`/Provenance-Metadatenmodell.
- `procedure_transform_execute` muss `planRef`/`planArtifactId` gegen
  Plan-Provenance, Tenant, Principal/Policy-Intent, Source-Refs,
  `targetDialect`, Prompt-/Payload-Fingerprint und
  `wireArtifactKind=procedure-transform-plan` pruefen.
- Provider-Port-Resultate erst nach Output-Hygiene, Limitierung und Scrubbing
  durch die Tool-Schicht als d-migrate-Artefakt publizieren; der Provider-Port
  liefert keine vertrauenswuerdige d-migrate-`artifactRef`.
- grosse Ergebnisse als Artefakt/Resource referenzieren
- Audit fuer alle Erfolg- und Fehlerpfade schreiben

Tests:

- fehlende Policy liefert `POLICY_REQUIRED`
- genehmigter Aufruf nutzt Provider
- identischer Retry erzeugt keinen zweiten Provider-Aufruf
- parallele identische Retries erzeugen genau einen Provider-Aufruf und genau
  ein Artefakt; parallele Verlierer warten/replayen oder erhalten einen
  retrybaren Pending-Status.
- Crash-/Reclaim-Fall vor terminalem Outcome blockiert den Scope nicht
  dauerhaft und startet vorhandene durable Ergebnisse nicht erneut beim
  Provider.
- identischer Retry nach terminalem Output-Hygiene-Block, Provider-Timeout
  oder Provider-Ausfuehrungsfehler replayt denselben strukturierten Fehler und
  startet keinen zweiten Provider-Aufruf.
- retrybarer Pending-/Recovery-Outcome nach Provider-Versuch blockiert
  parallele zweite Provider-Aufrufe und liefert einen retrybaren Status.
- abweichender Payload mit gleichem `approvalKey` liefert
  `IDEMPOTENCY_CONFLICT`
- `procedure_transform_plan` liefert bei kleinen und grossen Planinhalten immer
  eine persistierte `planRef` oder `planArtifactId`; `summary`/`findings`
  duerfen nur Preview sein.
- `procedure_transform_plan` speichert `ArtifactKind.OTHER` plus
  `wireArtifactKind=procedure-transform-plan`, `aiIntent` und vollstaendige
  Provenance oder nutzt einen explizit erweiterten Core-Enum mit denselben
  Store-/Spec-/Golden-Anpassungen.
- `procedure_transform_execute` lehnt fehlenden/falschen `wireArtifactKind`,
  fremden Tenant,
  nicht aus `procedure_transform_plan` erzeugte Artefakte, falschen Dialekt,
  abweichende Source-Refs und abweichende Plan-Provenance ab.
- Provider-Result mit grossem Output erzeugt erst nach Output-Hygiene und
  Scrubbing ein d-migrate-Artefakt; geblockter Output publiziert kein Artefakt.
- `procedure_transform_execute`-Zielartefakt traegt
  `wireArtifactKind=procedure-transform-output`,
  `aiIntent=procedure_transform_execute` und Provenance inklusive Plan-
  Referenz/Fingerprint.
- `testdata_plan`-Artefakt traegt `wireArtifactKind=testdata-plan`,
  `aiIntent=testdata_plan` und Provenance inklusive `schemaRef`,
  optionalem `profileRef`, `targetDialect` und Fingerprints.
- Resource-Reads/Prompts lehnen KI-Artefakte mit fehlendem oder falschem
  `wireArtifactKind`/`aiIntent` ab.
- Provider-Fehler wird als strukturierter Tool-Fehler gemappt
- Resultate verletzen keine Inline-Limits

### AP G.7 - Prompt-Registry und MCP-Prompt-Methoden

Aufgaben:

- Prompt-Registry mit drei Pflichtprompts implementieren
- `prompts/list` anbinden
- `prompts/get` anbinden
- Prompt-Argument-Schemas definieren
- Prompt-Argumentvalidierung implementieren
- unbekannten Prompt als MCP-/JSON-RPC-Fehler mit d-migrate-Code in
  `error.data` mappen
- Prompt-Hygiene in `prompts/get` erzwingen
- Prompt-Nachrichten klein und referenzbasiert halten

Tests:

- `prompts/list` ueber `stdio`
- `prompts/list` ueber HTTP
- `prompts/get` fuer jeden Pflichtprompt mit gueltigen Argumenten
- unbekannter Prompt
- ungueltige Argumente
- Secret-/Rohdatenparameter
- keine versteckte Tool-Ausfuehrung durch `prompts/get`

### AP G.8 - Quotas, Timeouts und Audit-Golden-Tests

Aufgaben:

- Provider-Quota in vorhandene Quota-Struktur integrieren
- Provider-Timeouts konfigurieren
- Audit-Events fuer KI-nahe Tools golden-testen
- Audit-Events fuer Prompt-Hygiene-Blockaden testen
- Audit-Scrubbing fuer Tokens, Secrets und Rohdaten verifizieren

Tests:

- Provider-Quota liefert `RATE_LIMITED`
- Provider-Timeout liefert `OPERATION_TIMEOUT`
- Audit enthaelt Provider-/Modell-Metadaten
- Audit enthaelt keine Secrets
- blockierte Hygiene-Faelle sind auditierbar

### AP G.9 - 0.9.6-End-to-End-Testabdeckung

Aufgaben:

- MCP-Integrationstests fuer `initialize`
- Tool-Discovery-Test
- Resource-Discovery-Test
- Prompt-Discovery-Test
- Tool-Aufruf ueber `stdio`
- Tool-Aufruf ueber HTTP
- Prompt-Aufruf ueber `stdio`
- Prompt-Aufruf ueber HTTP
- Fehler-Envelope-Golden-Tests aktualisieren
- vorhandene Phase-F-Uploadtests im Gesamtgate belassen
- Policy-/Idempotency-Konflikte fuer Start-Tools und KI-nahe Tools
  abdecken

Mindestfaelle:

- KI-nahes Tool ohne `approvalKey`
- KI-nahes Tool ohne Policy-Freigabe
- KI-nahes Tool-Retry mit gleichem `approvalKey`
- gleicher `approvalKey` mit anderem Payload
- externer Provider ohne erlaubende Policy
- Prompt mit Secret-Parameter
- Prompt mit Rohdatenparameter
- `prompts/get` mit unbekanntem Prompt
- `prompts/get` mit ungueltigen Argumenten
- Provider-Timeout
- Provider-Quota

### AP G.10 - Dokumentation und Roadmap-Abschluss

Aufgaben:

- `spec/ki-mcp.md` mit finalen Tool- und Prompt-Vertraegen abgleichen
- `docs/user/guide.md` um lokale MCP-Starts, entfernte Clients,
  Auth-Hinweise, Tool-Liste und Prompt-Liste erweitern
- Sicherheitsmodell dokumentieren:
  - keine Secrets im Payload
  - Policy fuer Write-/KI-Tools
  - `approvalKey` vs. `idempotencyKey`
  - Provider fail-closed
  - Prompt-Hygiene
- bekannte Grenzen dokumentieren:
  - `NoOp` Default
  - externe Provider optional
  - keine freie SQL-Ausfuehrung
  - keine Rohdaten im Prompt
  - keine versteckten Tool-Ausfuehrungen durch Prompts
- `docs/planning/roadmap.md` fuer 0.9.6-Abdeckung aktualisieren

Ergebnis:

- User-Doku nennt konkrete Startpfade fuer lokale und entfernte MCP-Clients.
- Spezifikation und Implementierungsstand widersprechen sich nicht.
- Offene Nachfolgearbeiten sind als explizite Grenzen dokumentiert.

---

## 7. Fehler- und Security-Regeln

### 7.1 Fehlerprecedence fuer KI-nahe Tools

Reihenfolge:

1. Auth-/Transport-Fehler
2. unbekannter Toolname als MCP-/JSON-RPC-Protokollfehler
3. Payload-Schema-Validierung
4. fehlender `approvalKey`
5. Tenant-/Resource-Scope-Pruefung
6. Idempotency-Konflikt ueber `approvalKey`
7. Policy-/Approval-Pruefung
8. Prompt-Hygiene
9. Provider-Quota
10. Provider-Timeout
11. Provider-Ausfuehrung
12. Result-Limitierung und Artifact-Publish

Begruendung:

- offensichtliche Clientfehler sollen deterministisch vor teuren Policy- oder
  Provider-Pfaden enden
- Idempotency-Konflikte duerfen keine Policy-Informationen leaken
- Provider-Aufrufe duerfen erst nach Policy und Hygiene stattfinden

### 7.2 Fehlercodes

Phase G erbt den bestehenden 0.9.6-Fehlercodevertrag und darf keine engere
Teilmenge definieren. Verbindliche Codes fuer Golden-Tests, Tool-Registry und
Error-Mapping bleiben:

- `AUTH_REQUIRED`
- `FORBIDDEN_PRINCIPAL`
- `POLICY_REQUIRED`
- `POLICY_DENIED`
- `IDEMPOTENCY_KEY_REQUIRED`
- `IDEMPOTENCY_CONFLICT`
- `RESOURCE_NOT_FOUND`
- `VALIDATION_ERROR`
- `RATE_LIMITED`
- `OPERATION_TIMEOUT`
- `PAYLOAD_TOO_LARGE`
- `UPLOAD_SESSION_EXPIRED`
- `UPLOAD_SESSION_ABORTED`
- `UNSUPPORTED_MEDIA_TYPE`
- `UNSUPPORTED_TOOL_OPERATION`
- `PROMPT_HYGIENE_BLOCKED`
- `TENANT_SCOPE_DENIED`
- `INTERNAL_AGENT_ERROR`

Regeln:

- fachliche Toolfehler erscheinen als `tools/call`-Result mit
  `isError=true`.
- unbekannte Tools sind Protokollfehler, kein fachlicher Toolfehler.
- unbekannte Prompts sind MCP-/JSON-RPC-Fehler mit d-migrate-Code in
  `error.data`.
- Fehlerdetails werden gescrubbt.

### 7.3 Prompt-Hygiene-Blockaden

`PROMPT_HYGIENE_BLOCKED` enthaelt nur:

- Hygiene-Kategorie
- betroffenes Feld oder Referenz, soweit ungefaehrlich
- Entscheidung
- `requestId`
- `retryable=false`, ausser der Client kann durch kleinere/andere
  Referenzen korrigieren

Nicht enthalten:

- Secret-Wert
- kompletter Prompt
- komplette Rohdaten
- `approvalToken`
- Provider-Secret-Ref mit sensitivem Pfad

### 7.4 Provider-Ausgaben werden ebenfalls geprueft

Auch Provider-Ausgaben koennen sensible Inhalte enthalten.

Vor Tool-Response oder Artifact-Publish gilt:

- Output-Hygiene pruefen
- Inline-Limits anwenden
- Secrets scrubben oder Ergebnis blockieren
- erst danach darf die Tool-Schicht ein d-migrate-Artefakt oder eine Resource-
  Referenz erzeugen
- grosse Outputs als Artefakt speichern
- Core-`ArtifactKind` und verpflichtende `wireArtifactKind`-/Intent-/
  Provenance-Metadaten setzen
- Audit mit Output-Fingerprint schreiben
- fuer terminale Fehler nach Provider-Versuch einen replaybaren Outcome-
  Record committen, auch wenn kein Artefakt publiziert wurde

Wenn Output-Hygiene blockiert:

- Tool liefert `PROMPT_HYGIENE_BLOCKED`
- Provider-Aufruf bleibt auditierbar
- sensibles Output-Material wird nicht in Tool-Response geschrieben
- kein d-migrate-Artefakt wird publiziert
- identischer Retry replayt denselben Fehler ohne zweiten Provider-Aufruf

---

## 8. Teststrategie

### 8.1 Unit-Tests

Pflichtabdeckung:

- `NoOpAiProvider`
- Provider-Konfigurationsvalidierung
- `PromptHygieneService`
- Secret-Pattern
- Rohdaten-/Massendaten-Erkennung
- Prompt-/Payload-Fingerprint
- Provider-Result-Limitierung
- Prompt-Registry
- Prompt-Argumentvalidierung
- Fehler-Mapping

### 8.2 Adaptertests

Pflichtabdeckung je KI-nahem Tool:

- gueltiger Minimalaufruf
- ungueltiger Payload
- fehlender `approvalKey`
- Scope-Verletzung
- Policy-Required-Flow
- genehmigter Flow mit `NoOp`
- identischer Retry
- Konflikt mit anderem Payload
- Hygiene-Blockade
- limitierter Output
- strukturierter Fehler
- Audit-Ereignis

Pflichtabdeckung fuer Prompts:

- `prompts/list`
- `prompts/get`
- gueltige Argumente pro Prompt
- ungueltige Argumente
- unbekannter Prompt
- Hygiene-Blockade
- keine versteckte Tool-Ausfuehrung

### 8.3 Integrationstests

Transporte:

- `stdio`
- streambares HTTP

Mindestablaeufe:

- Initialize, Tool-Discovery, Prompt-Discovery
- `procedure_transform_plan` mit `NoOp`
- `procedure_transform_execute` mit `NoOp`
- `testdata_plan` mit `NoOp`
- `prompts/get(procedure_analysis)`
- `prompts/get(procedure_transformation)`
- `prompts/get(testdata_planning)`
- Policy-Required-Flow
- Approval-Retry
- Idempotency-Konflikt
- Prompt-Hygiene-Fehler

### 8.4 Golden-Tests

Golden-Test-Artefakte:

- Tool-JSON-Schemas
- Prompt-Argument-Schemas
- Fehler-Envelopes
- Audit-Event-Form
- `capabilities_list` mit Provider- und Prompt-Faehigkeiten
- `prompts/list`-Antwort

Golden-Regeln:

- keine Secrets in Snapshots
- stabile Sortierung
- deterministische Fingerprints ueber normalisierte Payloads
- JSON Schema 2020-12

### 8.5 Nichtfunktionale Tests

Pflichtfaelle:

- Provider-Timeout
- Provider-Quota
- Output-Limit
- parallele identische KI-Tool-Retries
- parallele Konflikt-Retries
- fehlende Provider-Konfiguration
- blockierter externer Provider
- Audit-Scrubbing
- Doku-/Spec-Link-Check, soweit bestehende Toolchain vorhanden

---

## 9. Abnahmekriterien

- `AiProviderPort` oder aequivalenter KI-Ausfuehrungsport ist
  adapterneutral implementiert.
- `NoOp`-Provider ist Default und laeuft ohne Netzwerkzugriff und externe
  Secrets.
- Externe Provider sind ohne explizite Konfiguration und erlaubende Policy
  blockiert.
- Secret-Scrubbing und Prompt-Hygiene laufen vor jedem Provider-Aufruf.
- Prompt-Hygiene blockiert Secrets, freie JDBC-Strings, rohe Massendaten und
  unerlaubte Resource-Kinds.
- Provider-, Modell-, Modellversions-, Prompt-Fingerprint- und
  Payload-Fingerprint-Metadaten erscheinen in Audit-Events.
- Audit-Events enthalten keine Approval-Tokens, Provider-Secrets,
  Connection-Secrets oder unredigierten Massendaten.
- `procedure_transform_plan` ist registriert, policy-gesteuert,
  auditierbar, mit `NoOp` testbar und persistiert bei jedem erfolgreichen
  Aufruf ein immutable Plan-Artefakt bzw. eine stabile `planRef`.
- `procedure_transform_execute` ist registriert, bestaetigungspflichtig,
  policy-gesteuert, auditierbar und mit `NoOp` testbar.
- `testdata_plan` ist registriert, policy-gesteuert, auditierbar und mit
  `NoOp` testbar.
- Alle drei KI-nahen Tools verlangen `approvalKey`.
- Fehlender `approvalKey` liefert `VALIDATION_ERROR`.
- Fehlende Freigabe liefert `POLICY_REQUIRED` ohne verwendbares
  `approvalToken`.
- Identischer Retry mit gleichem `approvalKey` und identischem Payload
  liefert dasselbe Ergebnis bzw. dieselbe Artefakt-/Provider-Referenz.
- Parallele identische Retries mit gleichem `approvalKey` starten keinen
  zweiten Provider-Aufruf und erzeugen kein zweites Artefakt.
- Identische Retries nach terminalem post-provider Fehler, etwa
  Output-Hygiene-Block, Provider-Timeout oder Provider-Ausfuehrungsfehler,
  replayen denselben strukturierten Fehler ohne zweiten Provider-Aufruf.
- Abweichender Payload mit gleichem `approvalKey`-Scope liefert
  `IDEMPOTENCY_CONFLICT`.
- `procedure_transform_execute` akzeptiert nur freigegebene Plan-Artefakte mit
  passender Provenance, Tenant-/Principal-Bindung, Source-Refs,
  `targetDialect`, Fingerprints und `wireArtifactKind=procedure-transform-plan`
  bzw. explizit erweitertem Core-Artifact-Kind.
- KI-Artefakte verwenden verbindliche Typen:
  `procedure-transform-plan`, `procedure-transform-output` und `testdata-plan`
  mit passendem `aiIntent` und Provenance.
- Provider-Port-Resultate enthalten keine d-migrate-Artefaktreferenz; die
  Tool-Schicht publiziert Artefakte erst nach Output-Hygiene, Limitierung und
  Scrubbing.
- KI-nahe Tool-Resultate verletzen keine Inline-Limits.
- Grosse KI-Ergebnisse werden als Artefakt oder Resource-Ref referenziert.
- Provider-Timeouts liefern `OPERATION_TIMEOUT`.
- Provider-Quotas liefern `RATE_LIMITED`.
- MCP-Prompts sind ueber `prompts/list` sichtbar.
- `prompts/list` liefert Name, Beschreibung, Revision und Argument-Schema.
- `prompts/get` liefert fuer `procedure_analysis`,
  `procedure_transformation` und `testdata_planning` validierte
  Prompt-Nachrichten.
- `prompts/get` fuehrt keine Tools aus.
- Ungueltige Prompt-Argumente liefern `VALIDATION_ERROR`.
- Unbekannte Prompts liefern MCP-konforme JSON-RPC-Fehler mit d-migrate-Code
  in `error.data`.
- Prompt-Hygiene-Verletzungen in `prompts/get` liefern
  `PROMPT_HYGIENE_BLOCKED`.
- Integrationstests decken `stdio` und HTTP fuer Tools und Prompts ab.
- Fehler-Envelope-Golden-Tests sind aktualisiert.
- `capabilities_list` zeigt KI-/Provider-/Prompt-Faehigkeiten und relevante
  Limits an.
- Dokumentation nennt lokale und entfernte MCP-Startpfade.
- Dokumentation beschreibt Sicherheitsmodell, Tool-Liste, Prompt-Liste,
  Provider-Defaults und bekannte Grenzen.
- `docs/planning/roadmap.md` ist mit dem 0.9.6-Abschluss abgeglichen.

---

## 10. Anschluss nach 0.9.6

Nach Phase G koennen spaetere Milestones auf dem 0.9.6-MCP-Vertrag aufbauen:

- produktive externe KI-Provider mit erweitertem Policy-Modell
- bessere lokale Provider-Adapter
- richer Prompt-Versionierung
- UI fuer Approval- und Provider-Freigaben
- tiefere Testdatengenerierung
- automatische Transformationsvorschlaege mit Review-Workflow
- zusaetzliche KI-nahe Tools
- separate Provider-Kosten- und Budgetkontrolle

Diese Erweiterungen duerfen den 0.9.6-Grundsatz nicht aufweichen:

- keine Secrets im Prompt- oder Tool-Payload
- keine Rohdatenflutung in Modellkontexte
- keine versteckten Tool-Ausfuehrungen durch Prompts
- keine Provider-Aufrufe ohne Policy, Hygiene und Audit
