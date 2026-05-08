package dev.dmigrate.server.application.fingerprint

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FingerprintArrayOrderTest : FunSpec({

    val service = FingerprintFixtures.service()

    test("reordered array elements change the fingerprint") {
        val a = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj("xs" to JsonValue.arr(JsonValue.Num(1), JsonValue.Num(2), JsonValue.Num(3))),
            FingerprintFixtures.bind(),
        )
        val b = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj("xs" to JsonValue.arr(JsonValue.Num(3), JsonValue.Num(2), JsonValue.Num(1))),
            FingerprintFixtures.bind(),
        )
        (a == b) shouldBe false
    }

    test("identical arrays produce identical fingerprints") {
        val sample = JsonValue.obj(
            "xs" to JsonValue.arr(JsonValue.Num(1), JsonValue.Num(2)),
        )
        service.fingerprint(FingerprintScope.START_TOOL, sample, FingerprintFixtures.bind()) shouldBe
            service.fingerprint(FingerprintScope.START_TOOL, sample, FingerprintFixtures.bind())
    }
})
