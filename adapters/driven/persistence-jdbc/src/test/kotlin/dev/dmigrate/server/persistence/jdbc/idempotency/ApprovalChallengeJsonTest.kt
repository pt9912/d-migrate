package dev.dmigrate.server.persistence.jdbc.idempotency

import dev.dmigrate.server.core.approval.ApprovalChallenge
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ApprovalChallengeJsonTest : FunSpec({

    test("round-trip preserves all five fields including reasons + sorted requiredScopes") {
        val challenge = ApprovalChallenge(
            approvalRequestId = "req-42",
            correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
            correlationKey = "tenant/acme/key/x",
            requiredScopes = setOf("dmigrate:write", "dmigrate:admin"),
            reasons = listOf("policy:tenant", "policy:scope"),
        )
        val json = ApprovalChallengeJson.toJson(challenge)
        val parsed = ApprovalChallengeJson.fromJson(json)
        parsed shouldBe challenge
    }

    test("serialized JSON sorts requiredScopes for deterministic output") {
        val challenge = ApprovalChallenge(
            approvalRequestId = "r",
            correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
            correlationKey = "k",
            requiredScopes = setOf("z:scope", "a:scope", "m:scope"),
        )
        val json = ApprovalChallengeJson.toJson(challenge)
        val aIdx = json.indexOf("a:scope")
        val mIdx = json.indexOf("m:scope")
        val zIdx = json.indexOf("z:scope")
        (aIdx > 0 && mIdx > 0 && zIdx > 0) shouldBe true
        (aIdx < mIdx) shouldBe true
        (mIdx < zIdx) shouldBe true
    }

    test("empty reasons defaults; round-trip empty list") {
        val challenge = ApprovalChallenge(
            approvalRequestId = "r",
            correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
            correlationKey = "k",
            requiredScopes = setOf("x"),
        )
        val parsed = ApprovalChallengeJson.fromJson(ApprovalChallengeJson.toJson(challenge))
        parsed.reasons shouldBe emptyList()
    }

    test("JSON contains correlationKind enum name") {
        val challenge = ApprovalChallenge(
            approvalRequestId = "r",
            correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
            correlationKey = "k",
            requiredScopes = emptySet(),
        )
        ApprovalChallengeJson.toJson(challenge).shouldContain("APPROVAL_KEY")
    }
})
