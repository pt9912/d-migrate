# Approval-Grant Anti-Replay: Verdikt + Härtungen (Security-Audit #2)

> **Status:** Fläche GEPRÜFT 2026-07-18 — als Replay-Lücke **widerlegt**; eine
> Härtung **umgesetzt**, eine als Folgearbeit offen.
> **Trigger:** Follow-up-Audit der beim Erst-Audit ungeprüften Approval-Grant-Kette
> (aus der „Nicht geprüft / offene Lücken"-Sektion des
> [`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Punkt 2).

## Ausgangsfrage

Der Bericht beobachtete: `ApprovalGrantValidator` bindet sauber, aber
`ApprovalGrantStore` kennt nur `save`/`findByTokenFingerprint`/`deleteExpired` —
**keine `markUsed`/Nonce/Consumption-Semantik**. Ist ein Grant innerhalb seiner
TTL beliebig oft einlösbar (echte Replay-Lücke) oder fängt etwas es ab?

## Verdikt: keine Replay-Doppel-Ausführung

Die Store-Beobachtung stimmt wörtlich, führt aber **nicht** zu Mehrfach-Ausführung.
Der Einmal-Effekt wird strukturell **doppelt** erzwungen — ohne dass der Grant
selbst konsumiert werden müsste:

1. **Idempotenz an der Ausführungs-Naht.** Jede Einlösung läuft durch
   `AiToolOrchestrator.dispatch` (KI-Tool-Pfad) bzw. `IdempotencyStore.reserve`
   (Job-/Upload-Pfad), atomar pro `(scope, payloadFingerprint)`. Die
   Grant-Validierung liegt **innerhalb** des deduplizierten `work`/`retry`. Nach
   dem ersten Commit liefert jeder Replay den terminalen Outcome 1:1 zurück (kein
   zweiter Lauf); eine Payload-Abweichung fängt `ApprovalGrantValidator`
   (`PayloadMismatch`).
2. **Nonce-Bindung.** Der Grant ist an eine **frische Zufalls-`approvalRequestId`
   pro Challenge** gebunden (`ConfiguredPolicyService` → `appr_${UUID}`), die nur im
   Challenge-Record lebt. Ein Grant gegen eine andere/erneuerte Challenge →
   `ApprovalRequestIdMismatch`. Ein Grant ist also nur gegen genau die lebende
   Challenge einlösbar, die ihn erzeugt hat.

**Durabilitäts-Asymmetrie geprüft, kein Loch.** Der KI-Tool-Pfad paart einen
durablen file-backed `ApprovalGrantStore` mit einem **volatilen** In-Process
`AiToolOutcomeStore` (keine JDBC-Variante). Ein Replay nach Server-Neustart
scheitert trotzdem: die volatile Challenge ist weg; die Token-Einlösung verlangt
eine lebende `previousRetryable`-Challenge (sonst `POLICY_DENIED` „approval token
supplied without a pending approval challenge"), und eine neu abgeleitete Challenge
trägt eine neue `approvalRequestId` → alter Grant `ApprovalRequestIdMismatch`.

## Härtung B — umgesetzt (`JobStartOrchestrator`)

Der Job-Pfad enthielt einen vom Code-Kommentar selbst „Anti-Replay-Bypass"
genannten Fallback: fehlte die durable Challenge (`durableChallenge == null`), zog
`handleApprovedRetry` die `approvalRequestId` **aus dem Grant selbst** — dann kann
`ApprovalRequestIdMismatch` nie feuern, ein angreifer-gewählter Grant liefe durch.
Im aktuellen Wiring unerreichbar (der einzige Caller persistiert die Challenge
immer, beide Prod-`IdempotencyStore`-Impls geben sie zurück), aber latent.

**Fix:**
- `handleApprovedRetry` nimmt jetzt eine **non-null** `ApprovalChallenge` — der
  Grant-basierte `approvalRequestId`-Lookup ist entfernt (strukturell
  bypass-unmöglich).
- Der `null`-Fall fällt in `handleExistingAwaitingApproval` **fail-closed** auf
  `reDecideAwaiting` zurück: Policy neu entscheiden → frische Challenge; ein
  mitgeschickter (stale) Token treibt keine Ausführung.
- Das dadurch redundante `approvalGrantStore`-Konstruktor-Feld ist aus dem
  Orchestrator entfernt (die echte Grant-Validierung liegt im
  `ApprovedRetryService`).
- Regressionstest (`JobStartOrchestratorTest`) deckt genau die alte Bypass-
  Aufstellung: AWAITING_APPROVAL ohne durable Challenge + passend gewählter Grant +
  Token → **alt** hätte `Started` geliefert, **neu** `PolicyRequired`.

## Härtung A — offen (Folgearbeit, kein Bug)

Der `AiToolOutcomeStore` existiert nur als In-Process-Impl
(`InProcessAiToolOutcomeStore`, `ConcurrentHashMap`). Terminale Outcomes überleben
keinen Neustart. Sicherheitsseitig ist das durch die Nonce-Bindung abgedeckt (s.
o.), aber eine **mittendrin genehmigte KI-Op geht bei einem Crash verloren** und
muss re-genehmigt werden. Eine durable Variante (JDBC/file) für den
server-state-JDBC-Modus würde die Robustheit angleichen — nicht sicherheitskritisch,
daher hier als Folgearbeit geparkt.

## Fundstellen

- `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/job/JobStartOrchestrator.kt` (Härtung B)
- `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/approval/ApprovalGrantValidator.kt` (Nonce-/Payload-Bindung)
- `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/policy/ConfiguredPolicyService.kt` (`approvalRequestId`-Nonce)
- `hexagon/application/src/main/kotlin/dev/dmigrate/server/application/ai/AiToolOrchestrator.kt` + `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/AiMcpInProcessStores.kt` (Idempotenz-Naht + volatiler Outcome-Store, Härtung A)
