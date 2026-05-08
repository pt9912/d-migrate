package dev.dmigrate.server.application.fingerprint

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FingerprintNestedFieldRetentionTest : FunSpec({

    val service = FingerprintFixtures.service()

    test("nested idempotencyKey value is preserved and changes the fingerprint") {
        val baseline = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj(
                "options" to JsonValue.obj("idempotencyKey" to JsonValue.Str("a")),
            ),
            FingerprintFixtures.bind(),
        )
        val different = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj(
                "options" to JsonValue.obj("idempotencyKey" to JsonValue.Str("b")),
            ),
            FingerprintFixtures.bind(),
        )
        (baseline == different) shouldBe false
    }

    test("nested approvalToken inside an array element changes the fingerprint") {
        val a = service.fingerprint(
            FingerprintScope.SYNC_TOOL,
            JsonValue.obj(
                "ops" to JsonValue.arr(
                    JsonValue.obj("approvalToken" to JsonValue.Str("x")),
                ),
            ),
            FingerprintFixtures.bind(),
        )
        val b = service.fingerprint(
            FingerprintScope.SYNC_TOOL,
            JsonValue.obj(
                "ops" to JsonValue.arr(
                    JsonValue.obj("approvalToken" to JsonValue.Str("y")),
                ),
            ),
            FingerprintFixtures.bind(),
        )
        (a == b) shouldBe false
    }

    test("nested fields with the same name as control fields are not stripped") {
        val fp = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj(
                "metadata" to JsonValue.obj(
                    "requestId" to JsonValue.Str("nested"),
                    "clientRequestId" to JsonValue.Str("nested"),
                ),
            ),
            FingerprintFixtures.bind(),
        )
        val baseline = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj(
                "metadata" to JsonValue.Obj.EMPTY,
            ),
            FingerprintFixtures.bind(),
        )
        (fp == baseline) shouldBe false
    }
})
