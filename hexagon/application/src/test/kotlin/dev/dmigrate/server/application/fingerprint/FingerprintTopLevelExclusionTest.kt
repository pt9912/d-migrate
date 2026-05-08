package dev.dmigrate.server.application.fingerprint

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FingerprintTopLevelExclusionTest : FunSpec({

    val service = FingerprintFixtures.service()
    val baseline = service.fingerprint(
        scope = FingerprintScope.START_TOOL,
        payload = JsonValue.obj("table" to JsonValue.Str("orders")),
        bind = FingerprintFixtures.bind(),
    )

    listOf("idempotencyKey", "approvalToken", "clientRequestId", "requestId").forEach { control ->
        test("$control at top level does not change the fingerprint") {
            val fp = service.fingerprint(
                scope = FingerprintScope.START_TOOL,
                payload = JsonValue.obj(
                    "table" to JsonValue.Str("orders"),
                    control to JsonValue.Str("transient-value"),
                ),
                bind = FingerprintFixtures.bind(),
            )
            fp shouldBe baseline
        }
    }

    test("changing all four control fields together does not change the fingerprint") {
        val fp = service.fingerprint(
            scope = FingerprintScope.START_TOOL,
            payload = JsonValue.obj(
                "table" to JsonValue.Str("orders"),
                "idempotencyKey" to JsonValue.Str("k1"),
                "approvalToken" to JsonValue.Str("t1"),
                "clientRequestId" to JsonValue.Str("c1"),
                "requestId" to JsonValue.Str("r1"),
            ),
            bind = FingerprintFixtures.bind(),
        )
        fp shouldBe baseline
    }
})
