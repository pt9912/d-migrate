package dev.dmigrate.core.diff.routine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * E.1 Plan §4 demands a central log-redaction hook with an audit
 * test that no unmasked body snippet reaches a log channel under
 * the default (non-`--debug-body`) path. These pins exercise the
 * [RoutineBodyLogRedactor] contract that other code paths bind
 * to.
 */
class RoutineBodyLogRedactorTest : FunSpec({

    test("redact masks password literals by default") {
        val raw = "CREATE FUNCTION login(pwd text) AS \$\$ password = 'super-secret-123' \$\$"
        val safe = RoutineBodyLogRedactor.redact(raw)
        safe.shouldNotBeNullAndContain("***SCRUBBED***")
        safe!!.shouldNotContain("super-secret-123")
    }

    test("redact masks bearer / api-key shapes") {
        val raw = "SET api_key = 'AKIAIOSFODNN7EXAMPLE' /* not a real key */"
        val safe = RoutineBodyLogRedactor.redact(raw)
        safe!!.shouldContain("***SCRUBBED***")
        safe.shouldNotContain("AKIAIOSFODNN7EXAMPLE")
    }

    test("allowRaw = true bypasses scrubbing (the --debug-body unsafe path)") {
        val raw = "password = 'super-secret-123'"
        val safe = RoutineBodyLogRedactor.redact(raw, allowRaw = true)
        safe shouldBe raw
    }

    test("null input passes through unchanged") {
        RoutineBodyLogRedactor.redact(null) shouldBe null
        RoutineBodyLogRedactor.redact(null, allowRaw = true) shouldBe null
    }

    test("non-secret body text is returned verbatim under the default path") {
        // No credential-shaped tokens → the scrubber must not
        // mutate the message. This pins that the hook is safe to
        // call on arbitrary log payloads without losing
        // information.
        val raw = "BEGIN\n  RETURN amount * 1.19;\nEND"
        RoutineBodyLogRedactor.redact(raw) shouldBe raw
    }
})

private fun String?.shouldNotBeNullAndContain(substring: String) {
    this shouldBe this // type-narrowing nudge for the assertion below
    this!!.shouldContain(substring)
}
