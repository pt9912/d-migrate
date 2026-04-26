package dev.dmigrate.server.application.fingerprint

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FingerprintNullAndDefaultsTest : FunSpec({

    val service = FingerprintFixtures.service()

    test("explicit null differs from absent field") {
        val withNull = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj("optional" to JsonValue.Null),
            FingerprintFixtures.bind(),
        )
        val without = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.Obj.EMPTY,
            FingerprintFixtures.bind(),
        )
        (withNull == without) shouldBe false
    }

    test("explicit default value differs from absent field") {
        val withDefault = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj("retries" to JsonValue.Num(0)),
            FingerprintFixtures.bind(),
        )
        val without = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.Obj.EMPTY,
            FingerprintFixtures.bind(),
        )
        (withDefault == without) shouldBe false
    }

    test("string and integer with same textual form differ") {
        val asString = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj("x" to JsonValue.Str("1")),
            FingerprintFixtures.bind(),
        )
        val asInt = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj("x" to JsonValue.Num(1)),
            FingerprintFixtures.bind(),
        )
        (asString == asInt) shouldBe false
    }

    test("Long.MAX_VALUE and Long.MIN_VALUE produce different fingerprints") {
        val max = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj("n" to JsonValue.Num(Long.MAX_VALUE)),
            FingerprintFixtures.bind(),
        )
        val min = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj("n" to JsonValue.Num(Long.MIN_VALUE)),
            FingerprintFixtures.bind(),
        )
        (max == min) shouldBe false
    }
})
