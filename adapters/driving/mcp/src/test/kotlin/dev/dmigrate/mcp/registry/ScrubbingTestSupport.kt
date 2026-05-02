package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import io.kotest.matchers.shouldBe

/**
 * Shared bearer-token literal + leak-assertion helper for the
 * AP 6.13/6.17 `SecretScrubber` wire tests. The literal is opaque
 * to the scrubber's regex (the regex anchors on the `Bearer ` prefix
 * and a token-shaped tail) — matching the production pattern keeps
 * each handler's "is the scrub call wired?" test honest. The bare
 * `BEARER_SECRET_FRAGMENT` is what we then assert is absent from
 * the wire response.
 */
internal const val BEARER_TOKEN_LITERAL = "Bearer abc123secret"
internal const val BEARER_SECRET_FRAGMENT = "abc123secret"

internal fun JsonObject.assertNoBearerLeak(field: String) {
    get(field).asString.contains(BEARER_SECRET_FRAGMENT) shouldBe false
}
