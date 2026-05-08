package dev.dmigrate.server.application.policy

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.policy.PolicyDecision
import dev.dmigrate.server.ports.contract.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicInteger

class ConfiguredPolicyServiceTest : FunSpec({

    fun attempt(
        tenant: String = "acme",
        caller: String = "alice",
        tool: String = "schema_reverse_start",
        correlationKey: String = "idem-1",
    ) = PolicyAttempt(
        tenantId = Fixtures.tenant(tenant),
        callerId = Fixtures.principal(caller),
        toolName = tool,
        correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
        correlationKey = correlationKey,
        payloadFingerprint = "fp-1",
    )

    fun service(
        rules: List<PolicyRule>,
        default: PolicyEffect = PolicyEffect.Deny("policy:no-rule"),
        seq: AtomicInteger = AtomicInteger(0),
    ) = ConfiguredPolicyService(
        rules = rules,
        defaultEffect = default,
        approvalRequestIdFactory = { "req_${seq.incrementAndGet()}" },
    )

    test("Allow-Rule liefert Allowed") {
        val svc = service(
            listOf(PolicyRule(toolName = "schema_reverse_start", effect = PolicyEffect.Allow)),
        )
        svc.decide(attempt()) shouldBe PolicyDecision.Allowed
    }

    test("Challenge-Rule erzeugt RequiresApproval mit frischer approvalRequestId") {
        val svc = service(
            listOf(
                PolicyRule(
                    toolName = "data_profile_start",
                    effect = PolicyEffect.Challenge(
                        requiredScopes = setOf("data.read"),
                        reasons = listOf("sensitivity:pii"),
                    ),
                ),
            ),
        )
        val decision = svc.decide(attempt(tool = "data_profile_start"))
        decision.shouldBeInstanceOf<PolicyDecision.RequiresApproval>()
        decision.approvalRequestId shouldStartWith "req_"
        decision.requiredScopes shouldBe setOf("data.read")
        decision.reasons shouldBe listOf("sensitivity:pii")
        decision.correlationKind shouldBe ApprovalCorrelationKind.IDEMPOTENCY_KEY
        decision.correlationKey shouldBe "idem-1"
    }

    test("zwei Challenges erzeugen verschiedene approvalRequestIds") {
        // LF-012 / LN-011 / LN-017 / LN-027: Grant fuer alte/erneuerte Challenge ist ungueltig.
        // Jede Entscheidung MUSS ihre eigene Id bekommen.
        val svc = service(
            listOf(PolicyRule(effect = PolicyEffect.Challenge(setOf("a")))),
        )
        val d1 = svc.decide(attempt()) as PolicyDecision.RequiresApproval
        val d2 = svc.decide(attempt()) as PolicyDecision.RequiresApproval
        (d1.approvalRequestId == d2.approvalRequestId) shouldBe false
    }

    test("Deny-Rule liefert Denied mit reasonCode") {
        val svc = service(
            listOf(PolicyRule(toolName = "schema_reverse_start", effect = PolicyEffect.Deny("policy:tool-blocked"))),
        )
        svc.decide(attempt()) shouldBe PolicyDecision.Denied("policy:tool-blocked")
    }

    test("kein Match faellt auf defaultEffect zurueck") {
        val svc = service(
            rules = listOf(PolicyRule(toolName = "other_tool", effect = PolicyEffect.Allow)),
            default = PolicyEffect.Deny("policy:no-rule"),
        )
        svc.decide(attempt()) shouldBe PolicyDecision.Denied("policy:no-rule")
    }

    test("Default ist fail-closed Deny('policy:no-rule')") {
        // Sicherheitsgarantie: Konfig ohne Default ist nicht offen.
        val svc = ConfiguredPolicyService(rules = emptyList())
        svc.decide(attempt()) shouldBe PolicyDecision.Denied("policy:no-rule")
    }

    test("erste passende Regel gewinnt — Reihenfolge entscheidet") {
        val svc = service(
            listOf(
                PolicyRule(toolName = "schema_reverse_start", effect = PolicyEffect.Allow),
                PolicyRule(toolName = "schema_reverse_start", effect = PolicyEffect.Deny("never")),
            ),
        )
        svc.decide(attempt()) shouldBe PolicyDecision.Allowed
    }

    test("Tenant-Match: Regel gilt nur fuer passenden Tenant") {
        val svc = service(
            listOf(
                PolicyRule(
                    tenantId = Fixtures.tenant("acme"),
                    effect = PolicyEffect.Allow,
                ),
                PolicyRule(effect = PolicyEffect.Deny("policy:tenant-blocked")),
            ),
        )
        svc.decide(attempt(tenant = "acme")) shouldBe PolicyDecision.Allowed
        svc.decide(attempt(tenant = "initech")) shouldBe PolicyDecision.Denied("policy:tenant-blocked")
    }

    test("Caller-Match: Regel gilt nur fuer passenden Principal") {
        val svc = service(
            listOf(
                PolicyRule(
                    callerId = Fixtures.principal("alice"),
                    effect = PolicyEffect.Allow,
                ),
                PolicyRule(effect = PolicyEffect.Deny("policy:caller-blocked")),
            ),
        )
        svc.decide(attempt(caller = "alice")) shouldBe PolicyDecision.Allowed
        svc.decide(attempt(caller = "bob")) shouldBe PolicyDecision.Denied("policy:caller-blocked")
    }

    test("Wildcard-Regel (alle Felder null) matcht alle Attempts") {
        val svc = service(listOf(PolicyRule(effect = PolicyEffect.Allow)))
        svc.decide(attempt()) shouldBe PolicyDecision.Allowed
        svc.decide(attempt(tenant = "x", caller = "y", tool = "z")) shouldBe PolicyDecision.Allowed
    }

    test("Challenge in defaultEffect wird ebenfalls zu RequiresApproval") {
        val svc = service(
            rules = emptyList(),
            default = PolicyEffect.Challenge(setOf("admin")),
        )
        val decision = svc.decide(attempt())
        decision.shouldBeInstanceOf<PolicyDecision.RequiresApproval>()
        decision.requiredScopes shouldBe setOf("admin")
    }
})
