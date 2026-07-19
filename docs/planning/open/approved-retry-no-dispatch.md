# Approved-Retry committet ohne Auto-Dispatch (Security-Audit #4, funktionale Beobachtung)

> **Status:** Beim Follow-up-Audit #4 (MCP-Job-Ausführung) 2026-07-19 entdeckt.
> **Kein Sicherheitsbefund** (fail-safe), aber eine funktionale Ausführungslücke.
> **Trigger:** Follow-up-Audit des MCP-Job-Ausführungspfads (aus der „Nicht geprüft /
> offene Lücken"-Sektion des [`security-audit-2026-07-17.md`](security-audit-2026-07-17.md),
> Punkt 4).

## Beobachtung

Der primäre Job-Start (`JobStartOrchestrator.commitJob`) dispatcht nach dem
`JobStartTransaction.commit` **automatisch**: `runAutoDispatch` ruft
`jobWorkerFactory.create` + `jobDispatcher.dispatch` (fire-and-forget), der Worker
läuft QUEUED→RUNNING→Terminal durch.

Der **Approved-Retry-Pfad** (`AWAITING_APPROVAL` + gültiger Approval-Token) läuft
NICHT durch `commitJob`, sondern durch `ApprovedRetryService.retry` →
`claimAndCommit` → `commitJob` (die Service-eigene Variante). Diese committet den
NEUEN Job und ruft `workerHandleRegistry.register(jobId, source)` — **aber kein
Dispatch**. `ApprovedRetryService` hat strukturell keine `jobDispatcher`/
`jobWorkerFactory`-Felder (im Konstruktor nicht vorhanden), und der Orchestrator
mappt das `JobStartOutcome.Started` aus dem Retry nur `toHandlerOutcome()` weiter,
ohne selbst zu dispatchen.

Es existiert **kein Recovery-Poller** für QUEUED-Jobs (grep über
`adapters/driving/mcp/src/main` + `hexagon/application/src/main` nach
poll/sweep/drain/recover/redispatch: keine Fundstelle). Ein Server-Neustart
re-dispatcht ebenfalls keine QUEUED-Jobs.

**Folge:** Sobald ein Operator eine `RequiresApproval`-Policy-Regel konfiguriert
(erreichbar — `ConfiguredPolicyService`-Default ist `Deny("policy:no-rule")`, aber
eine explizite `PolicyRule` mit `effect = RequiresApproval` schaltet den Pfad
scharf), bleibt eine **genehmigte** Operation nach dem Approval-Retry dauerhaft
QUEUED und wird nie ausgeführt. Zusätzlich wird die dabei per
`quota.reserve` + `commitForOwner` belegte `ACTIVE_JOBS`-Quota mangels
Terminal-Transition (`JobDispatcher.applyTerminal` → `releaseForOwner` läuft nie)
nicht freigegeben — der Slot bleibt bis Lease-Ablauf/Sweeper belegt.

## Warum kein Sicherheitsbefund

Fail-safe in die sichere Richtung: Approval-gegatete Operationen sind gerade die
höher-privilegierten (Human-in-the-Loop vor destruktiven DB-Ops). „Läuft ohne
Dispatch gar nicht" bedeutet **keine unautorisierte Ausführung** — die
Autorisierungsreihenfolge (Approval + Quota strikt VOR jedem Dispatch) bleibt in
beiden Pfaden korrekt. Es ist ein Verfügbarkeits-/Korrektheitsloch, kein C/I-Verlust.

## Zu klären / zu tun

1. **Ist das beabsichtigt?** Möglicherweise bewusste Unreife des Approval-/
   AI-Tool-Bereichs (vgl. offene Folgearbeit „durable `AiToolOutcomeStore`" in
   [`approval-grant-antireplay-hardening.md`](approval-grant-antireplay-hardening.md)).
   Falls ja: als bekannte Einschränkung dokumentieren (Spec/Handbuch), damit ein
   Operator nicht auf still-hängende genehmigte Jobs läuft.
2. **Falls nicht:** Approved-Retry symmetrisch zum Primärpfad auto-dispatchen —
   entweder `ApprovedRetryService` einen `JobDispatcher`/`JobWorkerFactory` +
   Admission geben, oder der Orchestrator dispatcht nach einem `Started` aus dem
   Retry selbst (mit demselben `runAutoDispatch`-Setup-Failure-/Permit-Vertrag).
   Quota-Release-Pfad bei nicht-dispatchtem Job mitbedenken.
3. Regressionstest: genehmigter Retry → Job erreicht RUNNING/Terminal (nicht nur
   QUEUED), Quota nach Terminal freigegeben.

## Fundstellen

- `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/ApprovedRetryService.kt` (`commitJob` — register ohne Dispatch)
- `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt` (`handleApprovedRetry` → mappt Outcome ohne Dispatch; Kontrast: `commitJob`/`runAutoDispatch`)
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/OperationalMcpWiring.kt` (`approvedRetryService`-Wiring — kein Dispatcher übergeben)
- `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/policy/ConfiguredPolicyService.kt` (`RequiresApproval`-Erreichbarkeit)
