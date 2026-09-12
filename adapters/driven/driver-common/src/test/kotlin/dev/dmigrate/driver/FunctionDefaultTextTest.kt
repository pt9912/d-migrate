package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Klammern bekommt nur, was ein Name ist.
 *
 * Die Regel stand fuenfmal in den Typ-Mappern und traf daneben, sobald der
 * Text weder Name noch Aufruf war: aus `ARRAY['NEW'::order_status]` wurde
 * `ARRAY['NEW'::order_status]()`.
 */
class FunctionDefaultTextTest : FunSpec({

    test("a bare name becomes a call") {
        FunctionDefaultText.call("now") shouldBe "now()"
        FunctionDefaultText.call("gen_random_uuid") shouldBe "gen_random_uuid()"
        FunctionDefaultText.call("  sysdate  ") shouldBe "sysdate()"
        // Schema-qualifiziert ist immer noch ein Name.
        FunctionDefaultText.call("sys.getdate") shouldBe "sys.getdate()"
    }

    test("an existing call is left alone") {
        FunctionDefaultText.call("sysdatetimeoffset()") shouldBe "sysdatetimeoffset()"
        FunctionDefaultText.call("date_trunc('day', now())") shouldBe "date_trunc('day', now())"
    }

    test("what is no function name at all is not turned into one") {
        FunctionDefaultText.call("ARRAY['NEW'::order_status]") shouldBe "ARRAY['NEW'::order_status]"
        FunctionDefaultText.call("'{}'::jsonb") shouldBe "'{}'::jsonb"
        FunctionDefaultText.call("1 + 1") shouldBe "1 + 1"
    }
})
