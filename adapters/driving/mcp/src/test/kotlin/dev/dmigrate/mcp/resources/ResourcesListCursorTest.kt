package dev.dmigrate.mcp.resources

import dev.dmigrate.server.core.resource.ResourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ResourcesListCursorTest : FunSpec({

    test("encode then decode roundtrips kind and innerToken") {
        val original = ResourcesListCursor(ResourceKind.JOBS, "page-2-token")
        ResourcesListCursor.decode(original.encode()) shouldBe original
    }

    test("encode then decode roundtrips a null inner token") {
        val original = ResourcesListCursor(ResourceKind.ARTIFACTS, null)
        ResourcesListCursor.decode(original.encode()) shouldBe original
    }

    test("decode of null returns null (treated as start of listing)") {
        ResourcesListCursor.decode(null) shouldBe null
    }

    test("decode rejects non-base64 garbage") {
        val ex = shouldThrow<IllegalArgumentException> {
            ResourcesListCursor.decode("not!?valid base64...")
        }
        ex.message!! shouldContain "encoding"
    }

    test("decode rejects valid base64 with non-JSON body") {
        val notJson = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not-json".toByteArray())
        shouldThrow<IllegalArgumentException> { ResourcesListCursor.decode(notJson) }
    }

    test("decode rejects unknown ResourceKind") {
        val bad = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"kind":"UNKNOWN_KIND","innerToken":null}""".toByteArray())
        val ex = shouldThrow<IllegalArgumentException> { ResourcesListCursor.decode(bad) }
        ex.message!! shouldContain "UNKNOWN_KIND"
    }

    test("decode rejects a known but non-listable ResourceKind (UPLOAD_SESSIONS)") {
        val forged = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"kind":"UPLOAD_SESSIONS","innerToken":null}""".toByteArray())
        val ex = shouldThrow<IllegalArgumentException> { ResourcesListCursor.decode(forged) }
        ex.message!! shouldContain "not listable"
    }

    test("decode rejects body without 'kind'") {
        val bad = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"innerToken":"x"}""".toByteArray())
        shouldThrow<IllegalArgumentException> { ResourcesListCursor.decode(bad) }
    }

    test("encoded form is URL-safe (no '+' or '/')") {
        // URL-safe Base64 uses '-' and '_' instead of '+' and '/'.
        val encoded = ResourcesListCursor(ResourceKind.SCHEMAS, "x".repeat(100)).encode()
        encoded.contains('+') shouldBe false
        encoded.contains('/') shouldBe false
    }
})
