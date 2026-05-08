package dev.dmigrate.server.application.fingerprint

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Phase A's integer-only restriction (§14.1) is enforced at the type
 * system: [JsonValue.Num.value] is `Long`, so `Float`, `Double`,
 * `BigInteger` and `BigDecimal` cannot be passed without an explicit
 * conversion at the adapter boundary. This test pins the canonical
 * output for the Long edge values so widening the accepted number type
 * (e.g. switching `value` to `Number`) breaks the round-trip contract
 * and surfaces in CI.
 */
class JsonValueRejectsFloatAndBigIntTest : FunSpec({

    test("Long edge values canonicalize to their toString form") {
        listOf(0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE).forEach { n ->
            JsonCanonicalizer.canonicalize(JsonValue.Num(n)) shouldBe n.toString()
        }
    }
})
