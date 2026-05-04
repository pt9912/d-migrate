package dev.dmigrate.mcp.protocol

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Plan-D §5.3 / §10.7 unit coverage for
 * [StrictReadResourceParamsAdapter]. The wire-level acceptance
 * (`resources/read` rejecting `cursor`/`range`/`chunkId` end-to-end
 * over stdio + HTTP) lands in AP D11; this spec pins the adapter
 * itself: known fields round-trip, unknown fields are captured into
 * [ReadResourceParams.unknownParameter] without throwing during
 * parse so the dispatcher (not Gson) gets to render the typed
 * VALIDATION_ERROR envelope.
 */
class StrictReadResourceParamsAdapterTest : FunSpec({

    val gson: Gson = GsonBuilder().create()

    test("uri field round-trips") {
        val parsed = gson.fromJson(
            """{"uri":"dmigrate://tenants/acme/jobs/j1"}""",
            ReadResourceParams::class.java,
        )
        parsed.uri shouldBe "dmigrate://tenants/acme/jobs/j1"
        parsed.unknownParameter shouldBe null
    }

    test("missing uri field surfaces uri=null without unknownParameter") {
        val parsed = gson.fromJson("""{}""", ReadResourceParams::class.java)
        parsed.uri shouldBe null
        parsed.unknownParameter shouldBe null
    }

    test("explicit null uri parses to null without unknownParameter") {
        val parsed = gson.fromJson("""{"uri":null}""", ReadResourceParams::class.java)
        parsed.uri shouldBe null
        parsed.unknownParameter shouldBe null
    }

    test("a single unknown field is captured into unknownParameter") {
        val parsed = gson.fromJson("""{"chunkId":"chunk-1"}""", ReadResourceParams::class.java)
        parsed.uri shouldBe null
        parsed.unknownParameter shouldBe "chunkId"
    }

    test("uri + an extension field captures the extension's name") {
        val parsed = gson.fromJson(
            """{"uri":"dmigrate://capabilities","range":"0-1024"}""",
            ReadResourceParams::class.java,
        )
        parsed.uri shouldBe "dmigrate://capabilities"
        parsed.unknownParameter shouldBe "range"
    }

    test("multiple unknown fields capture only the first offender") {
        // Stable shape: the dispatcher renders the message with
        // `unknownParameter`, and naming only the first offender keeps
        // the wire output deterministic for a single request.
        val parsed = gson.fromJson(
            """{"cursor":"c","range":"0-1","extra":true}""",
            ReadResourceParams::class.java,
        )
        parsed.unknownParameter shouldBe "cursor"
    }

    test("nested-object unknown field is captured (parser stays balanced)") {
        // skipValue MUST consume nested object/array values so the
        // parser doesn't desync on a structurally-valid extension
        // payload. Detects regressions where someone reaches for
        // nextString() on an object value.
        val parsed = gson.fromJson(
            """{"meta":{"k":"v","arr":[1,2,3]},"uri":"dmigrate://capabilities"}""",
            ReadResourceParams::class.java,
        )
        parsed.uri shouldBe "dmigrate://capabilities"
        parsed.unknownParameter shouldBe "meta"
    }

    test("write emits only the uri field (no unknownParameter leak)") {
        val payload = ReadResourceParams(
            uri = "dmigrate://tenants/acme/jobs/j1",
            unknownParameter = "chunkId",
        )
        val rendered = gson.toJson(payload)
        rendered shouldBe """{"uri":"dmigrate://tenants/acme/jobs/j1"}"""
    }
})
