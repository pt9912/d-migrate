package dev.dmigrate.server.application.policy

import dev.dmigrate.server.core.policy.PolicyDecision

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
 * Entscheidet pro Start-Versuch eines Tool-Handlers (LF-012 / LN-011 / LN-017 / LN-027), ob ein Job
 * direkt starten darf, eine Approval-Challenge benoetigt oder abgelehnt
 * wird. Die konkrete Entscheidung darf keine fremden Ressourcendetails
 * oder Secrets leaken (LF-012 / LN-011 / LN-017 / LN-027); Implementierungen pruefen ausschliesslich
 * gegen [PolicyAttempt] und konfigurierte Regeln.
 *
 * Beispiel-Compose-Kette (LF-012 / LN-011 / LN-017 / LN-027):
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
