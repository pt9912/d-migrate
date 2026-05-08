package dev.dmigrate.server.application.policy

import dev.dmigrate.server.core.policy.PolicyDecision

/**
 * Phase E §5.4 Policy-Service-Port.
 *
 * Entscheidet pro Start-Versuch eines Tool-Handlers (AP E.6), ob ein Job
 * direkt starten darf, eine Approval-Challenge benoetigt oder abgelehnt
 * wird. Die konkrete Entscheidung darf keine fremden Ressourcendetails
 * oder Secrets leaken (Plan §5.4); Implementierungen pruefen ausschliesslich
 * gegen [PolicyAttempt] und konfigurierte Regeln.
 *
 * Beispiel-Compose-Kette (AP E.6):
 *
 *     val decision = policyService.decide(attempt)
 *     when (decision) {
 *         is PolicyDecision.Allowed -> // direkt zum JobStartService
 *         is PolicyDecision.RequiresApproval -> // Caller liefert Token nach
 *         is PolicyDecision.Denied -> // Tool gibt POLICY_DENIED zurueck
 *     }
 */
fun interface PolicyService {
    fun decide(attempt: PolicyAttempt): PolicyDecision
}
