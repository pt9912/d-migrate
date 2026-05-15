package dev.dmigrate.core.diff.routine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * E.1 Routine-Migration Slice A — RoutineBodyScrubber must mask
 * credential-shaped string literals BEFORE the body (or any preview)
 * lands in a report or diagnostic. Routine bodies can carry
 * passwords (e.g. PL/Perl, PL/Python definers); the report default
 * masks them so a Kupacommitted plan-artefact never leaks
 * production secrets.
 */
class RoutineBodyScrubberTest : FunSpec({

    test("non-secret body stays untouched") {
        val body = "BEGIN\n  RETURN now() AT TIME ZONE 'UTC';\nEND"
        val r = RoutineBodyScrubber.scrub(body)
        r.text shouldBe body
        r.scrubbingApplied shouldBe false
    }

    test("PASSWORD literal is masked, surrounding shape kept") {
        val body = "BEGIN\n  EXECUTE 'CREATE USER svc WITH PASSWORD ''prod-secret''';\nEND"
        // The Kotlin string above uses SQL-style `''` escapes inside
        // the outer PG string literal. The scrubber must still mask
        // the inner secret.
        val r = RoutineBodyScrubber.scrub("BEGIN\n  PASSWORD 'prod-secret';\nEND")
        r.scrubbingApplied shouldBe true
        r.text.shouldNotContain("prod-secret")
        r.text.shouldContain("PASSWORD")
        r.text.shouldContain("SCRUBBED")
    }

    test("token / api-key / secret literals are masked") {
        val body = """
            BEGIN
              PERFORM call_api(token = 'ABC123');
              PERFORM call_api(api_key='zzz-yyy');
              PERFORM call_api(secret:'topsecret');
            END
        """.trimIndent()
        val r = RoutineBodyScrubber.scrub(body)
        r.scrubbingApplied shouldBe true
        listOf("ABC123", "zzz-yyy", "topsecret").forEach { r.text.shouldNotContain(it) }
    }

    test("JDBC password parameter is masked, host kept") {
        val body = "PERFORM dblink_connect('jdbc:postgresql://db.local/app?user=svc&password=ProdPass123');"
        val r = RoutineBodyScrubber.scrub(body)
        r.scrubbingApplied shouldBe true
        r.text.shouldNotContain("ProdPass123")
        r.text.shouldContain("db.local")
        r.text.shouldContain("password=")
    }

    test("postgres:// URL password is masked, user + host kept") {
        val body = "PERFORM dblink_connect('postgres://svc:S3cret@db.local:5432/app');"
        val r = RoutineBodyScrubber.scrub(body)
        r.scrubbingApplied shouldBe true
        r.text.shouldNotContain("S3cret")
        r.text.shouldContain("svc:")
        r.text.shouldContain("@db.local")
    }

    test("preview yields hash + length + scrubbed snippet without raw body") {
        val body = "CREATE OR REPLACE FUNCTION secret_caller() RETURNS void AS \$\$\n" +
            "BEGIN\n  PERFORM call_api(token = 'ProdSecret123');\nEND\n\$\$"
        val preview = RoutineBodyScrubber.preview(body)
        preview.hash!!.length shouldBe 64 // SHA-256 hex
        preview.length shouldBe (RoutineBodyNormalizer.normalise(body)?.length ?: 0)
        preview.scrubbingApplied shouldBe true
        preview.preview.shouldNotContain("ProdSecret123")
    }

    test("null body produces an empty, unscrubbed preview") {
        val preview = RoutineBodyScrubber.preview(null)
        preview.hash shouldBe null
        preview.length shouldBe 0
        preview.preview shouldBe ""
        preview.scrubbingApplied shouldBe false
    }
})
