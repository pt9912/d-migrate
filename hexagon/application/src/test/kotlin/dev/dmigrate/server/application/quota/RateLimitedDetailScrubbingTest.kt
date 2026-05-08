package dev.dmigrate.server.application.quota

import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RateLimitedDetailScrubbingTest : FunSpec({

    test("RateLimitedDetail keeps only dimension, current, limit") {
        val key = QuotaKey(
            tenantId = Fixtures.tenant("acme"),
            dimension = QuotaDimension.UPLOAD_BYTES,
            principalId = Fixtures.principal("alice"),
        )
        val outcome = QuotaOutcome.RateLimited(
            key = key,
            amount = 100,
            current = 950,
            limit = 1_000,
        )
        val detail = RateLimitedDetail.from(outcome)
        detail shouldBe RateLimitedDetail(
            dimension = QuotaDimension.UPLOAD_BYTES,
            current = 950,
            limit = 1_000,
        )
    }

    test("RateLimitedDetail does not surface tenantId or principalId") {
        val outcome = QuotaOutcome.RateLimited(
            key = QuotaKey(
                tenantId = Fixtures.tenant("secret-tenant"),
                dimension = QuotaDimension.PROVIDER_CALLS,
                principalId = Fixtures.principal("internal-svc"),
            ),
            amount = 1,
            current = 60,
            limit = 60,
        )
        val detail = RateLimitedDetail.from(outcome)
        // Defensive: stringify and assert the sensitive identifiers are gone.
        val rendered = detail.toString()
        (rendered.contains("secret-tenant")) shouldBe false
        (rendered.contains("internal-svc")) shouldBe false
    }

    test("dimension is preserved verbatim — it's caller's own surface") {
        QuotaDimension.entries.forEach { dim ->
            val outcome = QuotaOutcome.RateLimited(
                key = QuotaKey(Fixtures.tenant("acme"), dim, null),
                amount = 1,
                current = 1,
                limit = 1,
            )
            RateLimitedDetail.from(outcome).dimension shouldBe dim
        }
    }
})
