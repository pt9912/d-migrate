# Implementierungsplan: 0.9.6 - Phase E `Async-Jobs, Idempotenz und Policy`

> **Milestone**: 0.9.6 - Beta: MCP-Server
> **Phase**: E (`Async-Jobs, Idempotenz und Policy`)
> **Status**: Draft (2026-05-03)
> **Referenz**: `docs/planning/in-progress/implementation-plan-0.9.6.md`
> Abschnitt 4.4, Abschnitt 4.5, Abschnitt 4.7, Abschnitt 4.9,
> Abschnitt 5.4, Abschnitt 6.1a, Abschnitt 6.4, Abschnitt 6.6,
> Abschnitt 6.9, Abschnitt 8 Phase E0, Abschnitt 8 Phase E,
> Abschnitt 9.1 bis 9.4, Abschnitt 11 und Abschnitt 12;
> `docs/planning/done/ImpPlan-0.9.6-A.md`;
> `docs/planning/done/ImpPlan-0.9.6-B.md`;
> `docs/planning/in-progress/ImpPlan-0.9.6-C.md`;
> `docs/planning/open/ImpPlan-0.9.6-D.md`;
> `docs/planning/open/ImpPlan-0.9.6-E0.md`; `spec/ki-mcp.md`;
> `spec/job-contract.md`; `spec/architecture.md`; `spec/design.md`;
> `spec/cli-spec.md`; `hexagon/application`; `hexagon/ports-common`;
> `hexagon/ports-read`; `hexagon/ports-write`; `hexagon/profiling`;
> `adapters/driving/cli`; `adapters/driving/mcp`.

---

## 1. Ziel

Phase E fuehrt den produktiven Async-Job-Pfad fuer die ersten
policy-pflichtigen MCP-Start-Tools ein. Sie verbindet das gemeinsame
Jobmodell mit Idempotency, Policy, Approval-Grants, Quotas, Timeouts,
Audit und kontrolliertem Cancel.

Phase E liefert konkrete technische Ergebnisse:

- Job-Start-Service fuer:
  - `schema_reverse_start`
  - `data_profile_start`
  - `schema_compare_start`
- produktive MCP-Handler und JSON-Schemas fuer diese Start-Tools
- `job_cancel` als kontrolliertes MCP-Tool
- Worker-/Job-Orchestrierung fuer abbrechbare Langlaeufer
- Idempotency-Zustandsautomat fuer Start-Tools:
  - `PENDING`
  - `AWAITING_APPROVAL`
  - `DENIED`
  - `COMMITTED`
  - `FAILED`
- atomarer genehmigter Retry aus `AWAITING_APPROVAL`
- Policy-Service fuer kontrollierte Operationen
- Approval-Token-Flow mit mindestens einem produktiv nutzbaren Grant-Pfad
- `ApprovalGrantStore` ohne rohe Tokens
- Quotas fuer aktive Jobs pro Tenant/Principal und Operation
- Start-/Runner-Timeouts mit `OPERATION_TIMEOUT`
- Cancel-Propagation gemaess Phase-E0-Gate
- Audit fuer Auth-, Validation-, Scope-, Idempotency-, Policy-, Start-,
  Timeout- und Cancel-Outcomes

Nach Phase E soll gelten:

- Clients koennen erlaubte Langlaeufer idempotent starten.
- Fehlende Policy-Freigabe erzeugt eine Challenge, aber keinen Job.
- Ein spaeter genehmigter Retry startet genau einen Job oder liefert den
  bereits gestarteten Job zurueck.
- Identische Retries deduplizieren stabil.
- Payload-Konflikte enden deterministisch mit `IDEMPOTENCY_CONFLICT`.
- `schema_compare_start` laeuft auch ohne Connection-Ref policy-pflichtig und
  auditierbar.
- `job_cancel` kann eigene oder erlaubte Jobs kontrolliert abbrechen.
- Nach angenommenem Cancel publiziert der Worker keine neuen Artefakte und
  startet keine weiteren Daten-Schreibabschnitte.

Wichtig: Phase E implementiert nicht `data_import_start`,
`data_transfer_start`, policy-pflichtige Upload-Intents, KI-nahe Tools oder
MCP-Prompts. Diese Pfade bleiben Phase F bzw. spaeteren Phasen vorbehalten.
Phase E darf aber Cancel- und Job-Orchestrierungsbausteine so schneiden, dass
Phase F sie wiederverwenden kann.

---

## 2. Ausgangslage

### 2.1 Phase A stellt den Job- und Policy-Kern bereit

Phase A definiert die gemeinsamen Kernmodelle und Stores:

- `PrincipalContext`
- Job- und Artefaktmetadaten
- `IdempotencyStore`
- `ApprovalGrantStore`
- Quota-/Rate-Limit-Grundlagen
- Audit- und Fehlervertraege
- `ApprovalGrant`
- Payload-Fingerprint-Grundlagen

Phase E erweitert oder vervollstaendigt diese Vertraege, darf aber keine
MCP-spezifischen Parallelmodelle fuer Jobs, Idempotency oder Policy
einfuehren.

### 2.2 Phase B/C/D stellen MCP-Transport, read-only Tools und Discovery bereit

Phase B liefert Transport, Auth, Tool-Registry, JSON-Schemas und
MCP-Basisdiscovery. Phase C liefert read-only Tool-Handler und grosse
Schema-Staging-Pfade. Phase D liefert Discovery-Listen, Resource-Resolver,
Connection-Refs und secret-freie Resource-Projektionen.

Phase E baut darauf auf:

- Start-Tools werden in der bestehenden MCP-Tool-Registry produktiv
  verdrahtet.
- Jobs und Artefakte bleiben ueber Phase-D-Discovery auffindbar.
- Connection-Refs aus Phase D sind Input fuer connection-backed Starts.
- Secrets werden erst in autorisierten Runner-/Driver-Pfaden materialisiert.

### 2.3 Phase E0 ist Gate fuer Cancel

Phase E darf `job_cancel` nur fertigstellen, wenn Phase E0 nachweist, dass die
betroffenen Runner kooperative Cancel-Checkpoints und Side-Effect-Stopp
unterstuetzen. Ist E0 `Blocked`, ist Phase E ebenfalls blockiert.

### 2.4 d-migrate-Jobs bleiben das Async-Modell

Phase E fuehrt keine parallele MCP-Tasks-Abstraktion ein. Das Async-Modell
bleibt:

- Start-Tool erzeugt oder dedupliziert einen d-migrate-Job.
- `job_status_get` und `job_list` liefern Status/Discovery.
- `job_cancel` steuert laufende Jobs.
- MCP-Cancel-Notifications ersetzen `job_cancel` nicht.

---

## 3. Scope fuer Phase E

### 3.1 In Scope

- Start-Tools:
  - `schema_reverse_start`
  - `data_profile_start`
  - `schema_compare_start`
- `job_cancel`
- Job-Start-Service und Worker-Orchestrierung
- Worker-Handle-Registry oder aequivalente laufende Job-Zuordnung
- `CancellationToken`-Anbindung aus Phase E0
- Idempotency-Service fuer Start-Tools
- atomare Idempotency-Reservierung und Job-Erzeugung
- Payload-Fingerprint-Kanonik fuer Start-Tools
- Policy-Service und Policy-Entscheidungen
- Approval-Challenge mit `approvalRequestId`
- Approval-Grant-Validierung
- MCP-Authorization-Scopes `dmigrate:job:start` und `dmigrate:job:cancel`
- mindestens ein produktiv nutzbarer Grant-Aussteller:
  - schmales MCP-Admin-Grant-Unterkommando
  - oder signierte Grant-Datei
  - oder lokale Allowlist mit explizitem GrantIssuer-Modus
- lokale Policy-Allowlist als reine Policy-Allow-Quelle ohne Grant-Ausstellung
- optionaler Local-Demo-Auto-Approval-Modus nur fuer Loopback/`stdio`, klar
  unsicher markiert und auditpflichtig
- Quotas fuer aktive Jobs pro Tenant/Principal/Operation
- Start- und Runner-Timeouts
- Audit-Wiring fuer alle Start-, Policy-, Idempotency- und Cancel-Pfade
- Unit-, Adapter- und Integrationstests fuer die genannten Vertraege

### 3.2 Nicht in Scope

- `data_import_start`
- `data_transfer_start`
- policy-pflichtige `artifact_upload_init`-Varianten fuer `job_input`
- administrative `artifact_upload_abort`-Freigaben
- KI-nahe Tools:
  - `procedure_transform_plan`
  - `procedure_transform_execute`
  - `testdata_plan`
- MCP-Prompt-Discovery
- allgemeine Mandantenverwaltung
- produktive UI fuer Approval-Workflows
- parallele MCP-Tasks-Abstraktion
- harte Prozess-/Thread-Kills fuer Cancel

---

## 4. Leitentscheidungen

### 4.1 Start-Tools sind immer idempotent

Alle Phase-E-Start-Tools verlangen `idempotencyKey`. Der Store-Key ist
serverseitig gescoped auf:

```text
(tenantId, callerId, operation, idempotencyKey)
```

`tenantId`, `callerId` und `operation` werden aus Principal, Toolname und
validiertem Request abgeleitet. Der Client kann keinen fremden
Idempotency-Scope setzen.

Der `payloadFingerprint` ist SHA-256 ueber deterministisch serialisierten,
normalisierten Payload ohne transiente Kontrollfelder:

- `idempotencyKey`
- `approvalToken`
- `clientRequestId`
- `requestId`

Die Normalisierung wendet schema- und toolseitige Defaults vor der
Serialisierung an. Implizit fehlende Defaults und explizit gesetzte
Default-Werte ergeben denselben Fingerprint.

### 4.2 Policy prueft neue Starts, nicht Konflikte

Pipeline-Reihenfolge fuer Start-Tools:

1. `requestId` erzeugen
2. Audit-Scope oeffnen
3. Principal aus Transportkontext ableiten
4. Tool-Payload gegen Schema validieren
5. syntaktische Tenant-Prefix-, Scope-Key- und Resource-Ref-Form pruefen
6. MCP-Authorization-Scope fuer Start pruefen (`dmigrate:job:start`)
7. Idempotency-Reservierung atomar pruefen oder anlegen
8. Idempotency-Konflikte sofort mit `IDEMPOTENCY_CONFLICT` beenden
9. `COMMITTED` direkt dedupliziert zurueckgeben, ohne Resource-
   Materialisierung, Policy oder Quota erneut auszufuehren
10. Resource-Sichtbarkeit, Policy und Materialisierung nur fuer neue oder mit
   Grant atomar geclaimte Starts pruefen
11. Quota fuer neue Side Effects reservieren
12. Job erzeugen oder bestehendes Ergebnis zurueckgeben

Policy wird niemals fuer abweichende Payloads geprueft. Ein Konflikt mit
gleichem Store-Key und anderem Fingerprint liefert deterministisch
`IDEMPOTENCY_CONFLICT`.

Fehlende oder unzureichende MCP-Authorization-Scopes werden vor
`reserveOrGet` abgewiesen. Sie erzeugen Audit mit Scope-Challenge/403, aber
keine Idempotency-Reservierung, keine Approval-Challenge, keine Policy-
Entscheidung und keine Quota-Reservierung.

Die Idempotency-Reservierung bleibt vor Policy- und Quota-Pruefung. Deshalb
muessen alle Outcomes nach angelegter Reservierung eine verbindliche
Reservation-Transition haben:

- `POLICY_REQUIRED`: `PENDING` wird atomar zu `AWAITING_APPROVAL` mit
  `approvalRequestId` und `awaitingApprovalExpiresAt`.
- `RATE_LIMITED`: keine Job-Erzeugung; die Reservierung bleibt recoverable
  `PENDING` mit `pendingLeaseExpiresAt` maximal bis `retryAfter`. Identische
  Retries vor Ablauf liefern erneut `RATE_LIMITED`, identische Retries nach
  Ablauf duerfen die Reservierung recovern und Quota erneut pruefen.
- `RESOURCE_NOT_FOUND`, `TENANT_SCOPE_DENIED` und `VALIDATION_ERROR` aus
  Resource-Lookup oder Materialisierung nach Idempotency werden als
  deterministische Startfehler in `FAILED` mit gespeichertem Outcome
  ueberfuehrt. Identische Retries liefern denselben Fehler ohne neue Policy-
  oder Quota-Pruefung.
- retrybare Materialisierungsfehler, zum Beispiel temporaere Resolver- oder
  Secret-Store-Fehler, bleiben recoverable `PENDING` mit gespeichertem
  Outcome, `retryAfter` und kurzer Lease.
- Quota-Reservierung erfolgreich, aber Fehler vor Job-Commit: die
  Quota-Reservierung wird per `refund` zurueckgegeben und die
  Idempotency-Reservierung bleibt nach Fehlerart recoverable `PENDING` oder
  wird final `FAILED`.
- `OPERATION_TIMEOUT` vor Job-Commit: keine Job-Erzeugung; die Reservierung
  wird als recoverable `PENDING` mit abgelaufener oder kurzer Lease
  freigegeben, damit identische Retries die Start-Pipeline neu claimen
  koennen.
- technische Startfehler vor Job-Commit: keine Job-Erzeugung; retrybare
  Fehler werden wie recoverable `PENDING` behandelt, nicht-retrybare
  deterministische Fehler setzen `FAILED`.
- technische Fehler nach Job-Erzeugung, aber vor Response: muessen
  `COMMITTED(jobId)` recovern oder atomar fertigstellen; es darf kein Job ohne
  deduplizierbare Reservierung entstehen.

Nur die syntaktische Ref-Form und ein expliziter Tenant-Prefix duerfen vor
Idempotency geprueft werden. Ein identischer Retry fuer eine `COMMITTED`-
Reservierung muss denselben Job liefern, auch wenn referenzierte Connections,
Schemas oder Policy-Sichtbarkeit inzwischen geloescht, rotiert oder entzogen
wurden. Resource-Lookup und Secret-/Schema-Materialisierung finden deshalb
erst nach dem `COMMITTED`-Dedupe-Pfad statt.

### 4.3 Policy-Freigabe startet nicht automatisch

Fehlt eine Freigabe, liefert das Tool `POLICY_REQUIRED` mit Challenge-
Metadaten inklusive `approvalRequestId`, aber ohne verwendbares
`approvalToken`.

Ein verwendbares `approvalToken` entsteht nur durch einen serverseitigen
Policy-, Human- oder Admin-Mechanismus, der die `approvalRequestId` der
aktuellen Challenge prueft. Ein blosser Retry desselben Agenten darf keine
Freigabe erzeugen.

Der genehmigte zweite Aufruf muss denselben `idempotencyKey`, denselben
Payload-Fingerprint, dieselbe aktuelle `approvalRequestId` und ein passendes
`approvalToken` verwenden. Er claimt die Reservierung atomar und erzeugt genau
einen Job.

### 4.4 Grants enthalten keine rohen Tokens

`ApprovalGrantStore` speichert:

- Tenant
- Principal-/Caller-Bindung
- Toolname / Operation
- `correlationKind`
- `correlationKey`
- `approvalRequestId`
- `payloadFingerprint`
- Ablaufzeit
- Issuer-Fingerprint
- Scope / Entscheidungsgrund
- Token-Fingerprint

Der Store speichert nie rohe Approval-Tokens.

### 4.5 Cancel bleibt kontrollierte Job-Steuerung

`job_cancel` ist kein Kill-Schalter. Es ist eine berechtigte
Zustandsaenderung im gemeinsamen Jobmodell:

- Principal braucht `dmigrate:job:cancel`, bevor Job-Lookup oder Worker-
  Zugriff erfolgt
- eigene Jobs duerfen abgebrochen werden
- Admins duerfen Jobs innerhalb desselben Tenants abbrechen
- explizite Cross-Tenant-Job-`resourceUri` liefert `TENANT_SCOPE_DENIED`
- opake `jobId`-Eingaben bleiben tenant-lokal und liefern fuer fremde oder
  fehlende Jobs `RESOURCE_NOT_FOUND`
- nicht erlaubte Jobs liefern `FORBIDDEN_PRINCIPAL`
- terminale Jobs bleiben terminal
- erfolgreich angenommene Abbrueche enden im Jobstatus `cancelled`
- Worker beobachten `CancellationToken` oder Worker-Handle
- nach angenommenem Cancel keine neuen Artefakte und keine neuen
  Daten-Schreibabschnitte

### 4.6 Timeouts und Quotas sind fachliche Outcomes

Quota-/Rate-Limit-Verletzungen liefern `RATE_LIMITED`. Timeouts liefern
`OPERATION_TIMEOUT`. Beide Outcomes werden auditiert.

Bei bereits gestarteten Jobs muss der Jobstatus gemaess `spec/job-contract.md`
aktualisiert werden; weitere Side Effects werden ueber Cancel-/Cleanup-Pfade
gestoppt.

Aktive Quota-Slots folgen dem bestehenden `QuotaService`-Lifecycle:
`reserve` belegt den Slot vor Job-Erzeugung atomar, `commit` bestaetigt den
Erfolgspfad nur fuer Audit/Beobachtbarkeit, `refund` gibt Pre-Commit-Fehler
und Idempotency-Replays zurueck, `release` gibt terminalisierte Jobs frei.
Runner-Timeouts muessen denselben `release`-Pfad ausloesen, auch wenn der
Worker erst ueber Cleanup oder Watchdog terminalisiert wird.

---

## 5. Kernvertraege

### 5.1 Job-Start-Service

Der Job-Start-Service kapselt die gemeinsame Start-Pipeline fuer
`schema_reverse_start`, `data_profile_start` und `schema_compare_start`.

Eingabe:

- `PrincipalContext`
- Tool-/Operationstyp
- validierter Tool-Payload
- `idempotencyKey`
- optional `approvalToken`
- `requestId`
- `TimeoutBudget`

Ausgabe:

- bestehender oder neuer `jobId`
- Job-`resourceUri`
- `executionMeta`
- bei aktiver `PENDING`-Reservierung ohne `jobId`:
  `OPERATION_TIMEOUT` mit `retryable=true`, `retryAfter` und ohne internen
  Idempotency-State in der Response
- bei fehlender Freigabe: `POLICY_REQUIRED` mit Challenge
- bei Konflikt: `IDEMPOTENCY_CONFLICT`

Verbindlich:

- Job-Anlage und `COMMITTED`-Transition duerfen nicht auseinanderfallen.
- Parallele genehmigte Retries duerfen hoechstens einen Job erzeugen.
- Deduplizierte `COMMITTED`-Antworten verbrauchen keine neue Quota.
- Parallele identische Starts duerfen aktive `PENDING` nicht nach aussen
  leaken; sie warten nur innerhalb des Start-Timeout-Budgets und liefern
  danach retrybares `OPERATION_TIMEOUT`.

### 5.2 Idempotency-Zustandsautomat

Zustaende:

| Zustand | Bedeutung |
| --- | --- |
| `PENDING` | Reservierung angelegt, Job noch nicht committed |
| `AWAITING_APPROVAL` | Policy-Challenge offen |
| `DENIED` | Freigabe explizit abgelehnt |
| `COMMITTED` | Job wurde erzeugt und ist deduplizierbar |
| `FAILED` | endgueltige, nicht-retrybare Reservierung ohne Job |

Pflichtfelder:

- Scope-Key `(tenantId, callerId, operation, idempotencyKey)`
- `payloadFingerprint`
- `state`
- `createdAt`
- `updatedAt`
- `pendingLeaseExpiresAt`, wenn `PENDING`
- gespeichertes retrybares Outcome mit `retryAfter` und strukturierten
  Details, wenn `PENDING` einen determinierten Retry-Fehler wie
  `RATE_LIMITED` oder temporaeren Materialisierungsfehler repraesentiert
- `awaitingApprovalExpiresAt`, wenn `AWAITING_APPROVAL`
- `approvalRequestId`, wenn Freigabe offen
- `jobId`, wenn `COMMITTED`
- `idempotencyRetentionExpiresAt`, wenn `COMMITTED`; mindestens `job.expiresAt`
- `denialExpiresAt`, wenn `DENIED`
- Denial-Metadaten, wenn `DENIED`
- gespeichertes finales Fehleroutcome mit strukturierten Details, wenn
  `FAILED`

Regeln:

- identischer Scope/Fingerprint liefert bestehende Reservierung oder Job
- abweichender Fingerprint liefert `IDEMPOTENCY_CONFLICT`
- abgelaufene `PENDING`-Leases duerfen nur fuer identischen
  Scope/Fingerprint recovered werden
- abgelaufene `AWAITING_APPROVAL`-Challenges liefern neue Challenge oder
  erneuern die bestehende Challenge atomar
- `DENIED` liefert bis `denialExpiresAt` `POLICY_DENIED`
- nach `denialExpiresAt` darf ein identischer Retry wieder eine neue
  Policy-Entscheidung ausloesen; die alte Ablehnung bleibt auditierbar
- `FAILED` ist final fuer denselben Scope/Fingerprint und darf nicht fuer
  abgelaufene `PENDING`-Leases oder `AWAITING_APPROVAL`-Challenges verwendet
  werden
- recoverable `PENDING` aus `RATE_LIMITED`, `OPERATION_TIMEOUT` vor Commit
  oder retrybaren technischen Startfehlern darf nur von einem identischen
  Scope/Fingerprint neu geclaimt werden
- aktive nicht abgelaufene `PENDING` ohne gespeichertes Outcome darf nicht als
  interner Zustand an Clients ausgegeben werden; identische parallele Aufrufe
  warten bis zum Start-Timeout-Budget und liefern dann retrybares
  `OPERATION_TIMEOUT`
- `PENDING` mit gespeichertem `RATE_LIMITED`-Outcome liefert bis
  `retryAfter` deterministisch erneut `RATE_LIMITED`
- `FAILED` liefert das gespeicherte finale Fehleroutcome deterministisch fuer
  identische Scope/Fingerprint-Retries
- `COMMITTED`-Eintraege duerfen fruehestens nach `idempotencyRetentionExpiresAt`
  geloescht werden; dieser Zeitpunkt ist mindestens `job.expiresAt` gemaess
  `spec/job-contract.md`

### 5.3 Payload-Fingerprint

Fingerprint-Kanonik:

- strukturierter Payload wird deterministisch serialisiert
- Map-/Objekt-Feldreihenfolge ist stabil
- schema- und toolseitige Defaults werden vor der Serialisierung materialisiert
- implizite Defaults und explizit gesetzte Default-Werte sind gleichwertig
- transiente Felder sind ausgeschlossen
- `approvalToken` beeinflusst den Fingerprint nie
- Connection-Refs werden als tenant-scoped Resource-URIs normalisiert
- Secret-Materialisierung ist nie Teil des Fingerprints

Tests muessen gleiche Payloads mit anderer JSON-Feldreihenfolge als gleich und
Payloads mit fachlicher Abweichung als verschieden nachweisen.

### 5.4 Policy-Service

Der Policy-Service entscheidet fuer neue oder noch nicht genehmigte Starts:

- erlauben
- Challenge erforderlich
- ablehnen

Eingabe:

- Principal
- Tenant
- Tool/Operation
- Resource-/Connection-Refs
- Sensitivitaets- und Produktionsmetadaten
- `payloadFingerprint`
- `idempotencyKey`
- Audit-Kontext

Antwort:

- `Allowed`
- `RequiresApproval(approvalRequestId, approvalKey/idempotencyKey, reasons)`
- `Denied(reasonCode)`

Policy-Details duerfen keine fremden Ressourcendetails oder Secrets leaken.

### 5.5 Approval-Grant-Validierung

Ein Grant ist gueltig nur, wenn alle Bindungen passen:

- Tenant
- Caller/Principal oder erlaubter Admin-Issuer-Kontext
- Toolname / Operation
- `correlationKind = idempotencyKey`
- `correlationKey = idempotencyKey`
- `approvalRequestId` entspricht der aktuellen `AWAITING_APPROVAL`-Challenge
- `payloadFingerprint`
- Ablaufzeit
- erforderliche Scopes
- Token-Fingerprint

Ungueltige oder abgelaufene Grants liefern weiter `POLICY_REQUIRED` oder
`POLICY_DENIED` gemaess Reservierungszustand, nicht `AUTH_REQUIRED`.
Ein Grant fuer eine alte oder erneuerte `approvalRequestId` ist ungueltig; der
Retry liefert erneut `POLICY_REQUIRED` mit der aktuellen Challenge.

### 5.6 Job-Cancel-Vertrag

`job_cancel` Eingabe:

- `jobId` oder tenant-scoped Job-`resourceUri`
- optional `reason`

Antwort:

- aktueller Jobstatus gemaess `spec/job-contract.md`
- `executionMeta`
- optional Job-`resourceUri`

Regeln:

- fehlender `dmigrate:job:cancel`-Scope liefert 403 mit Scope-Challenge,
  auditiert ohne Job-Lookup und ohne Worker-Zugriff
- unbekannte oder nicht sichtbare Jobs im erlaubten Tenant liefern
  no-oracle `RESOURCE_NOT_FOUND`
- explizit fremde Tenants in tenant-scoped Job-`resourceUri` liefern
  `TENANT_SCOPE_DENIED`
- opake `jobId`-Eingaben duerfen keinen globalen Cross-Tenant-Lookup machen;
  fremde oder fehlende opake IDs liefern im Principal-Tenant
  `RESOURCE_NOT_FOUND`
- fremde Jobs im selben Tenant ohne Berechtigung liefern
  `FORBIDDEN_PRINCIPAL`
- terminale Jobs bleiben unveraendert terminal
- angenommener Cancel setzt oder bestaetigt `cancelled` nur per CAS von einem
  nicht-terminalen Zustand
- Worker-Handle wird gecancelt, falls der Job noch laeuft
- wenn der Worker bereits terminal ist, gewinnt der terminale Zustand

---

## 6. Tool-Vertraege

### 6.1 `schema_reverse_start`

Zweck:

- Reverse Engineering einer Connection als langlaufenden Job starten

Eingabe:

- `connectionRef`
- `idempotencyKey`
- Reverse-/Schema-Optionen
- optional `approvalToken`

Regeln:

- `connectionRef` muss tenant-scoped sein
- freie JDBC-Strings sind verboten
- Tool ist immer policy-gesteuert
- fehlender `idempotencyKey` liefert `IDEMPOTENCY_KEY_REQUIRED`
- fehlender Grant liefert `POLICY_REQUIRED`
- genehmigter Retry startet genau einen Job

### 6.2 `data_profile_start`

Zweck:

- Profiling einer Connection oder eines erlaubten Scopes als Job starten

Eingabe:

- `connectionRef`
- `idempotencyKey`
- Profiling-Optionen / Scope
- optional `approvalToken`

Regeln:

- Tool ist immer policy-gesteuert
- produktive oder sensitive Connections brauchen sichtbare Policy-Entscheidung
- Profiling-Reports werden als Artefakte/Resources publiziert
- Cancel vor Report-Publish verhindert neue Reports

### 6.3 `schema_compare_start`

Zweck:

- schemaRef- oder connection-backed Vergleich als Job starten

Eingabe:

- `left` und `right`, jeweils `schemaRef` oder `connectionRef`
- `idempotencyKey`
- Compare-Optionen
- optional `approvalToken`

Regeln:

- Tool ist immer policy-gesteuert, auch ohne Connection-Ref
- Connection-Refs muessen tenant-scoped sein
- Schema-Refs muessen tenant-scoped oder fuer den Tenant explizit sichtbar sein
- nicht sichtbare `schemaRef` liefert `RESOURCE_NOT_FOUND` oder
  `TENANT_SCOPE_DENIED` ohne Oracle-Details
- freie JDBC-Strings sind verboten
- synchroner `schema_compare` akzeptiert keine `connectionRef`
- `schema_compare_start` kann per `job_cancel` abgebrochen werden

### 6.4 `job_cancel`

Zweck:

- laufenden oder wartenden Job kontrolliert abbrechen

Eingabe:

- `jobId` oder Job-`resourceUri`
- optional `reason`

Regeln:

- kein `idempotencyKey` erforderlich
- Cancel ist selbst keine policy-pflichtige Start-Operation
- eigene Jobs sind cancelbar, sofern sie noch nicht terminal sind
- Admin-Cancel fremder Jobs bleibt tenant-begrenzt
- Audit speichert Reason gescrubbt

---

## 7. Umsetzungsschritte

### 7.1 AP E.1: E0-Gate pruefen und Cancel-Vertrag fixieren

- Status von `docs/planning/open/ImpPlan-0.9.6-E0.md` pruefen.
- Bei `Blocked` Phase E nicht fortsetzen.
- `CancellationToken` / Worker-Handle-Vertrag final platzieren.
- Runner-Checkpoints aus E0 als verbindliche Eingabe fuer Phase E uebernehmen.
- Zusaetzliches Phase-E-Gate fuer `schema_compare_start` definieren, weil E0
  neue Start-Tools ausschliesst: Compare-Materialisierung, Diff-Berechnung und
  Artefakt-/Resource-Publish muessen Cancel-Checkpoints nachweisen, bevor
  `schema_compare_start` produktiv aktiviert wird.

Tests/Gate:

- E0-Ergebnis ist dokumentiert
- relevante Runner koennen Cancel typisiert signalisieren
- Compare-Cancel-Gate ist dokumentiert und blockiert Phase E bei fehlendem
  Nachweis

### 7.2 AP E.2: Job-Orchestrierung einfuehren

- Job-Start-Service definieren.
- Worker-Handle-Registry oder aequivalente laufende Job-Zuordnung einfuehren.
- Job-Lifecycle auf `queued`, `running`, `succeeded`, `failed`, `cancelled`
  begrenzen.
- `expiresAt` fuer Retention nutzen, keinen neuen Jobstatus einfuehren.
- `COMMITTED`-Idempotency-Retention an Job-Retention binden:
  `idempotencyRetentionExpiresAt >= job.expiresAt`.
- Artefakt-Publish an Jobkontext binden.

Tests:

- neuer Job startet in erlaubtem Zustand
- terminale Status bleiben terminal
- Worker-Handle wird fuer laufende Jobs gefunden
- `COMMITTED`-Idempotency-Key bleibt mindestens bis `job.expiresAt`
  deduplizierbar

### 7.3 AP E.3: Idempotency-Service implementieren

- Core-/Port-Migration fuer `FAILED` explizit durchfuehren:
  `IdempotencyState` erweitern, Store-Outcomes/Mapper anpassen,
  Fehler-Mapping definieren, InMemory-Store nachziehen und Contract Tests
  aktualisieren.
- Scope-Key bilden: `(tenantId, callerId, operation, idempotencyKey)`.
- `payloadFingerprint` bilden und transiente Felder ausschliessen.
- `reserveOrGet` atomar implementieren.
- Zustandsautomat mit `PENDING`, `AWAITING_APPROVAL`, `DENIED`,
  `COMMITTED`, `FAILED` abbilden.
- Lease-/Recovery-Regeln fuer `PENDING` und `AWAITING_APPROVAL`
  implementieren.

Tests:

- fehlender Key -> `IDEMPOTENCY_KEY_REQUIRED`
- identischer Retry -> gleiche Reservierung / gleicher Job
- anderer Fingerprint -> `IDEMPOTENCY_CONFLICT`
- parallele identische Requests erzeugen hoechstens einen Job
- abgelaufene `PENDING`-Lease wird nur fuer identischen Fingerprint recovered
- Store/Port/Contract Tests decken `FAILED` als neuen Core-State ab
- recoverable `PENDING` nach `RATE_LIMITED` oder Start-Timeout wird nicht als
  final `FAILED` behandelt
- aktives `PENDING` ohne Job liefert nach Start-Timeout retrybares
  `OPERATION_TIMEOUT`, keinen internen State
- `PENDING` mit gespeichertem `RATE_LIMITED` liefert bis `retryAfter`
  deterministisch erneut `RATE_LIMITED`
- Resource-/Tenant-/Validation-Fehler nach Ref-Lookup werden final `FAILED`
  und bei identischem Retry deterministisch wiederholt
- temporaere Materialisierungsfehler bleiben recoverable `PENDING` und
  blockieren identische Retries nicht dauerhaft
- `FAILED` blockiert identische Retries nur fuer endgueltige
  nicht-retrybare Reservierungen

### 7.4 AP E.4: Policy-Service und Grant-Aussteller

- Policy-Service-Interface definieren.
- Policy-Entscheidungen fuer alle drei Start-Tools konfigurieren.
- `ApprovalGrantStore` ohne rohe Tokens verwenden.
- Mindestens einen produktiv nutzbaren Grant-Aussteller implementieren:
  Admin-Unterkommando, signierte Grant-Datei oder lokale Allowlist mit
  explizitem GrantIssuer-Modus.
- Reine lokale Policy-Allowlist ohne GrantIssuer-Modus darf nur direkte
  `ALLOW`-Entscheidungen liefern und zaehlt nicht als Grant-Pfad fuer
  `POLICY_REQUIRED` plus genehmigten Retry.
- Optionalen Demo-Auto-Approval-Modus nur fuer Loopback/`stdio` zulassen,
  deutlich markieren und auditieren.
- Ohne konfigurierten Grant-Aussteller fail-closed verhalten.

Tests:

- fehlender Grant -> `POLICY_REQUIRED` mit Challenge
- ungueltiger Grant -> weiterhin keine Job-Erzeugung
- gueltiger Grant passt nur fuer gebundenen Fingerprint
- gueltiger Grant passt nur fuer aktuelle `approvalRequestId`
- Grant fuer erneuerte/alte Challenge liefert wieder `POLICY_REQUIRED`
- konfigurierte Policy-Allowlist ohne GrantIssuer startet direkt erlaubte
  Requests, erzeugt aber kein `approvalToken`
- rohe Tokens erscheinen nicht in Store oder Audit
- fehlender Grant-Aussteller ist dokumentiert fail-closed

### 7.5 AP E.5: Genehmigten Retry atomar committen

- `AWAITING_APPROVAL` mit gueltigem Grant atomar claimen.
- Genau einen Job erzeugen.
- Reservierung auf `COMMITTED(jobId)` setzen.
- Wiederholter genehmigter Retry nach `COMMITTED` liefert denselben Job.
- Abgelehnte Reservierung auf `DENIED` setzen und `POLICY_DENIED` liefern.

Tests:

- parallele genehmigte Retries erzeugen genau einen Job
- zweiter Retry nach Commit liefert denselben Job
- abgelehnter Grant liefert `POLICY_DENIED`
- `DENIED` enthaelt `denialExpiresAt` und laeuft danach recoverable aus
- Idempotency-Konflikt prueft keine Policy

### 7.6 AP E.6: Start-Tool-Schemas und Handler verdrahten

- JSON-Schemas fuer `schema_reverse_start`, `data_profile_start` und
  `schema_compare_start` finalisieren.
- `idempotencyKey` als Pflichtfeld erzwingen.
- `dmigrate:job:start` vor Idempotency-/Policy-/Quota-Store-Writes pruefen.
- `connectionRef` tenant-scoped validieren.
- `schemaRef` tenant-scoped oder sichtbar fuer den Tenant validieren.
- freie JDBC-Strings abweisen.
- Output-Schema auf `jobId`, `resourceUri`, `executionMeta` festlegen.
- Tool-Registry von Unsupported-Handlern auf produktive Handler umstellen.

Tests:

- fehlender `idempotencyKey`
- fehlender oder unzureichender `dmigrate:job:start`-Scope -> 403 mit
  Scope-Challenge und ohne Idempotency-Store-Write
- ungueltige Connection-Ref
- nicht sichtbare oder fremde Schema-Ref
- freie JDBC-URL
- fehlender Grant
- erfolgreicher genehmigter Start
- deduplizierter Retry

### 7.7 AP E.7: Runner-Integration fuer Reverse, Profiling und Compare

- `SchemaReverseRunner` als Job-Worker anbinden.
- Profiling-Services/`DataProfileRunner` als Job-Worker anbinden.
- `schema_compare_start` materialisiert noetige Inputs und laeuft als Job.
- Compare-Materialisierung und Diff-/Artefakt-Publish erhalten eigene
  Cancel-Checkpoints, falls sie nicht durch E0 abgedeckt sind.
- Connection-Refs secret-frei in Discovery, aber autorisiert im Runner
  materialisieren.
- Artefakte und Jobstatus nach `spec/job-contract.md` publizieren.
- E0-Cancel-Checkpoints produktiv nutzen.

Tests:

- erfolgreicher Reverse-Job publiziert Artefakt/Resource
- erfolgreicher Profiling-Job publiziert Report/Resource
- `schema_compare_start` mit `connectionRef` laeuft als abbrechbarer Job
- `schema_compare_start` ohne Connection-Ref bleibt policy-pflichtig
- Cancel vor oder waehrend Compare-Materialisierung verhindert Diff- und
  Artefakt-Publish
- Secrets erscheinen nicht in Job-, Artefakt- oder Audit-Projektionen

### 7.8 AP E.8: `job_cancel` implementieren

- Handler fuer `job_cancel` anbinden.
- `jobId` oder Job-`resourceUri` normalisieren.
- `dmigrate:job:cancel` vor Job-Lookup und Worker-Zugriff pruefen.
- Tenant-, Principal- und Admin-Regeln pruefen.
- Terminale Jobs unveraendert lassen.
- Laufende Jobs ueber Worker-Handle canceln.
- Jobstatus `cancelled` per Compare-and-Set nur aus nicht-terminalem Zustand
  setzen, wenn Cancel angenommen wurde.
- Reason scrubben und auditieren.

Tests:

- eigener laufender Job wird gecancelt
- fehlender oder unzureichender `dmigrate:job:cancel`-Scope -> 403 mit
  Scope-Challenge und ohne Job-Lookup
- tenant-scoped URI mit fremdem Tenant -> `TENANT_SCOPE_DENIED`
- opake fremde oder fehlende `jobId` -> `RESOURCE_NOT_FOUND`
- fremder Principal ohne Admin -> `FORBIDDEN_PRINCIPAL`
- unbekannter Job -> `RESOURCE_NOT_FOUND`
- terminaler Job bleibt terminal
- Worker wird zwischen Lookup und Cancel terminal -> `succeeded`/`failed`
  bleibt erhalten und wird nicht durch `cancelled` ueberschrieben
- Worker publiziert nach Cancel keine neuen Artefakte

### 7.9 AP E.9: Quotas, Rate Limits und Timeouts

- aktive Joblimits pro Tenant/Principal/Operation konfigurieren.
- Quota-Pruefung nur fuer neue Job-Erzeugung ausfuehren, nicht fuer
  deduplizierte `COMMITTED`-Antworten.
- Bestehenden `QuotaService`-Vertrag festschreiben:
  `reserve -> commit/refund/release`.
- `reserve` vor Job-Erzeugung ausfuehren und bei `RateLimited`
  `RATE_LIMITED` ohne Job liefern.
- `commit` nur nach erfolgreichem Job-Commit als Success-/Audit-Hook
  aufrufen; der Slot bleibt belegt.
- `refund` fuer Start-Timeouts, technische Pre-Commit-Fehler und
  Idempotency-Replays nach bereits reservierter Quota aufrufen.
- `release` bei `succeeded`, `failed`, `cancelled` und
  Runner-Timeout-Cleanup freigeben.
- `RATE_LIMITED` mit strukturierten Details liefern.
- Start-Timeout und Runner-Timeout konfigurieren.
- Timeout auf `OPERATION_TIMEOUT` mappen und Worker-Cancel/Cleanup ausloesen.

Tests:

- aktive Jobquote ueberschritten -> `RATE_LIMITED`
- deduplizierter Retry verbraucht keine neue Quote
- parallele neue Starts koennen Quota durch `reserve` nicht ueberbuchen
- Start-Timeout oder technischer Fehler vor Job-Commit ruft `refund`
- Slot wird nach `succeeded`, `failed` und `cancelled` freigegeben
- Start-Timeout -> `OPERATION_TIMEOUT`
- Runner-Timeout stoppt weitere Side Effects und gibt aktive Slots frei

### 7.10 AP E.10: Audit und Dokumentation

- Around-/Finally-Audit fuer Start-Tools und `job_cancel` vervollstaendigen.
- Audit-Events fuer fruehe Fehler:
  - Auth
  - Validation
  - Tenant/Scope
  - Idempotency
  - Policy
  - Quota
  - Timeout
  - Cancel
- `spec/mcp-server.md` und `spec/ki-mcp.md` fuer Phase-E-Verhalten
  aktualisieren.
- Lokalen Grant-Pfad dokumentieren.

Tests:

- jedes Fehleroutcome wird auditiert
- fehlende Start-/Cancel-Scopes werden auditiert, ohne Idempotency-,
  Challenge-, Quota- oder Job-Lookup-Store-Writes auszufuehren
- keine Approval-Tokens, Secrets oder rohen Connection-Daten im Audit
- Doku beschreibt fail-closed Verhalten ohne Grant-Aussteller

---

## 8. Fehler- und Security-Regeln

Verbindliche Fehler:

- 403 mit Scope-Challenge fuer fehlende oder unzureichende MCP-
  Authorization-Scopes
- `IDEMPOTENCY_KEY_REQUIRED` fuer fehlenden Key bei Start-Tools
- `IDEMPOTENCY_CONFLICT` fuer gleichen Store-Key mit anderem Fingerprint
- `POLICY_REQUIRED` fuer fehlende Freigabe
- `POLICY_DENIED` fuer abgelehnte Reservierung
- `RATE_LIMITED` fuer Quota-/Rate-Limit-Verletzungen
- `OPERATION_TIMEOUT` fuer Start-/Runner-Timeout
- `TENANT_SCOPE_DENIED` fuer explizit fremde tenant-scoped Resource-URIs
- `FORBIDDEN_PRINCIPAL` fuer nicht erlaubten Job-Cancel im selben Tenant
- `RESOURCE_NOT_FOUND` fuer unbekannte oder nicht sichtbare Jobs im erlaubten
  Tenant
- `VALIDATION_ERROR` fuer ungueltige Payloads und freie JDBC-Strings

Security-Regeln:

- fehlende MCP-Authorization-Scopes werden vor Idempotency-/Policy-/Quota-
  Store-Writes und vor Job-Lookup abgewiesen
- keine rohen Approval-Tokens speichern oder auditieren
- keine JDBC-Secrets in Tool-Responses, Jobs, Artefakten oder Audit
- Connection-Refs muessen tenant-scoped sein
- Schema-Refs muessen tenant-scoped oder fuer den Tenant sichtbar sein
- produktive/sensitive Connections duerfen nur mit Policy-Freigabe genutzt
  werden
- `approvalToken` ist transient und nie Teil des Payload-Fingerprints
- Policy- und Tenant-Fehler leaken keine fremden Ressourcendetails

---

## 9. Teststrategie

Mindesttestklassen:

- Idempotency-Service-Unit-Tests
- Payload-Fingerprint-Tests
- Approval-Grant-Store- und Grant-Validator-Tests
- Policy-Service-Tests
- Job-Start-Service-Tests
- MCP-Handler-Tests fuer alle drei Start-Tools
- `job_cancel`-Handler-Tests
- Worker-/Runner-Integrationstests fuer Reverse, Profiling und Compare
- Quota-/Timeout-Tests
- Audit-Scrubbing-Tests

Mindestfaelle:

- fehlender `idempotencyKey`
- fehlender Start-Scope erzeugt Audit/403 ohne Idempotency- oder Challenge-
  Store-Write
- implizite Defaults und explizite Default-Werte ergeben denselben Fingerprint
- identischer Retry vor und nach `COMMITTED`
- `COMMITTED`-Retry dedupliziert auch bei inzwischen geloeschter oder
  unsichtbarer Resource
- paralleler identischer Start auf aktiver `PENDING`-Reservierung liefert
  retrybares `OPERATION_TIMEOUT` statt internem State
- Konflikt ohne Policy-Pruefung
- erster Start ohne Grant -> `AWAITING_APPROVAL` + `POLICY_REQUIRED`
- zweiter Start mit Grant -> genau ein Job
- Grant mit alter oder falscher `approvalRequestId`
- parallele genehmigte Retries -> ein Job
- `DENIED` gilt nur bis `denialExpiresAt`
- Grant abgelaufen oder falscher Fingerprint
- Quota ueberschritten
- Quota-`reserve` verhindert parallele Ueberbuchung
- Quota-`refund` bei Start-Timeout und Pre-Commit-Fehler
- Quota-Slot-Release nach `succeeded`, `failed`, `cancelled` und
  Runner-Timeout
- Timeout
- recoverable Start-Timeout blockiert identischen Retry nicht dauerhaft
- `schema_compare_start` mit fremder oder nicht sichtbarer `schemaRef`
- Resource-/Tenant-/Validation-Fehler nach Idempotency werden final und
  deterministisch wiederholt
- temporaerer Materialisierungsfehler nach Idempotency bleibt recoverable
- Compare-Cancel vor Materialisierung/Diff-Publish
- Cancel eigener Job
- Cancel ohne Scope erzeugt Audit/403 ohne Job-Lookup
- Cancel tenant-scoped URI mit fremdem Tenant
- Cancel opake fremde oder fehlende `jobId`
- Cancel fremder Principal im selben Tenant
- Cancel-Race: Worker terminalisiert zwischen Lookup und CAS
- Worker-Side-Effect-Stopp nach Cancel

---

## 10. Abnahmekriterien

- `schema_reverse_start`, `data_profile_start` und `schema_compare_start`
  sind produktiv verdrahtet.
- alle drei Start-Tools verlangen `idempotencyKey`.
- fehlender `idempotencyKey` liefert `IDEMPOTENCY_KEY_REQUIRED`.
- identische Wiederholung liefert denselben Job.
- identische Wiederholung nach `COMMITTED` materialisiert keine Resources neu
  und bleibt stabil, wenn referenzierte Resources spaeter geloescht oder
  unsichtbar wurden.
- aktive `PENDING`-Reservierungen ohne `jobId` werden nicht als interner State
  exponiert; identische parallele Aufrufe liefern nach Start-Budget
  retrybares `OPERATION_TIMEOUT`.
- Payload-Konflikte liefern `IDEMPOTENCY_CONFLICT` ohne Policy-Pruefung.
- fehlender oder unzureichender `dmigrate:job:start`-Scope erzeugt keine
  Idempotency-Reservierung und keine Approval-Challenge.
- fehlende Policy-Freigabe liefert `POLICY_REQUIRED`.
- Policy-pflichtige Tools koennen ueber mindestens einen Grant-Pfad produktiv
  freigegeben werden.
- ohne konfigurierten Grant-Aussteller ist das Verhalten fail-closed und
  dokumentiert.
- erster policy-pflichtiger Start ohne Grant erzeugt `AWAITING_APPROVAL`.
- zweiter Aufruf mit gueltigem Grant startet genau einen Job und setzt die
  Reservierung auf `COMMITTED`.
- Approval-Grants sind an die aktuelle `approvalRequestId` der
  `AWAITING_APPROVAL`-Challenge gebunden.
- wiederholter zweiter Aufruf nach `COMMITTED` liefert denselben Job.
- parallele genehmigte Retries erzeugen hoechstens einen Job.
- `DENIED`-Reservierungen liefern `POLICY_DENIED`.
- `DENIED` ist ueber `denialExpiresAt` begrenzt und danach neu entscheidbar.
- Quota- und Timeout-Faelle liefern `RATE_LIMITED` bzw.
  `OPERATION_TIMEOUT` und werden auditiert.
- Quota folgt `reserve -> commit/refund/release`; `reserve` verhindert
  Ueberbuchung, `refund` deckt Pre-Commit-Fehler ab, `release` terminale Jobs.
- `RATE_LIMITED`, Start-Timeouts und retrybare technische Startfehler lassen
  keine dauerhaft haengenden `PENDING`-Reservierungen zurueck.
- `RATE_LIMITED`-Retries vor `retryAfter` liefern deterministisch
  `RATE_LIMITED` aus gespeicherten Outcome-Metadaten.
- finale Resource-/Tenant-/Validation-Fehler nach Idempotency setzen
  `FAILED`; retrybare Materialisierungsfehler bleiben recoverable.
- `COMMITTED`-Idempotency-Eintraege bleiben mindestens bis `job.expiresAt`
  erhalten.
- `schema_compare_start` mit `connectionRef` laeuft als abbrechbarer Job und
  dedupliziert ueber `idempotencyKey`.
- `schema_compare_start` ohne Connection-Ref bleibt ebenfalls
  policy-pflichtig und auditierbar.
- `schema_compare_start` ist erst produktiv aktivierbar, wenn Compare-
  Materialisierung und Diff-/Artefakt-Publish Cancel-Checkpoints nachweisen.
- Connection-backed Starts akzeptieren nur tenant-scoped `connectionRef`.
- Schema-backed Starts akzeptieren nur tenant-scoped oder sichtbare
  `schemaRef`.
- freie JDBC-Strings werden mit `VALIDATION_ERROR` abgewiesen.
- `job_cancel` kann nur eigene oder erlaubte Jobs abbrechen; opake `jobId`
  macht keinen globalen Cross-Tenant-Lookup.
- fehlender oder unzureichender `dmigrate:job:cancel`-Scope fuehrt zu 403 mit
  Scope-Challenge ohne Job-Lookup.
- `job_cancel` setzt `cancelled` nur per CAS aus nicht-terminalem Zustand;
  terminale Worker-Races duerfen `succeeded`/`failed` nicht ueberschreiben.
- `job_cancel` erzeugt einen stabilen `cancelled`-Status gemaess
  `spec/job-contract.md`, sofern der Job noch nicht terminal war.
- terminale Jobs bleiben terminal.
- nach angenommenem Cancel publiziert der Worker keine neuen Artefakte und
  startet keine weiteren Daten-Schreiboperationen.
- rohe Approval-Tokens, Connection-Secrets und JDBC-URLs erscheinen nicht in
  Stores, Tool-Responses oder Audit.
- alle fruehen Fehlerpfade werden im Around-/Finally-Audit abgeschlossen.

---

## 11. Anschluss an Phase F

Phase F darf auf Phase E aufbauen, indem sie datenveraendernde Start-Tools und
policy-pflichtige Upload-Intents an dieselben Vertraege anbindet:

- `data_import_start`
- `data_transfer_start`
- `artifact_upload_init(uploadIntent=job_input)` und weitere
  policy-pflichtige Upload-Varianten
- administrative `artifact_upload_abort`-Pfade

Phase F darf keine zweite Idempotency-, Policy-, Approval- oder Cancel-
Architektur einfuehren. Wenn Import/Transfer zusaetzliche Felder brauchen,
werden die in Phase E eingefuehrten Vertraege erweitert.
