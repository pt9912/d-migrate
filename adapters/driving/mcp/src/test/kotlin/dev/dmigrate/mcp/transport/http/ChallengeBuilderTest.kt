package dev.dmigrate.mcp.transport.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class ChallengeBuilderTest : FunSpec({

    val metadataUrl = "https://mcp.example/.well-known/oauth-protected-resource"

    test("missingToken returns realm + resource_metadata") {
        ChallengeBuilder.missingToken(metadataUrl) shouldBe
            """Bearer realm="dmigrate-mcp", resource_metadata="$metadataUrl""""
    }

    test("invalidToken returns invalid_token error with reason") {
        val challenge = ChallengeBuilder.invalidToken("token expired", metadataUrl)
        challenge shouldStartWith "Bearer realm=\"dmigrate-mcp\""
        challenge shouldContain "error=\"invalid_token\""
        challenge shouldContain "error_description=\"token expired\""
        challenge shouldContain "resource_metadata=\"$metadataUrl\""
    }

    test("insufficientScope returns required scopes space-separated") {
        val challenge = ChallengeBuilder.insufficientScope(
            required = setOf("dmigrate:read", "dmigrate:job:start"),
            metadataUrl = metadataUrl,
        )
        challenge shouldContain "error=\"insufficient_scope\""
        // Order in a Set is undefined, so just check both scopes appear inside the scope quoted value.
        challenge shouldContain "dmigrate:read"
        challenge shouldContain "dmigrate:job:start"
        challenge shouldContain "resource_metadata=\"$metadataUrl\""
    }

    test("escapes backslash and quote in error_description") {
        val challenge = ChallengeBuilder.invalidToken(
            "token has \"quote\" and \\back",
            metadataUrl,
        )
        challenge shouldContain "error_description=\"token has \\\"quote\\\" and \\\\back\""
    }

    test("strips control characters from input") {
        // RFC 7235 forbids CTL chars in quoted strings; we drop them.
        val challenge = ChallengeBuilder.invalidToken("good\u0001bad", metadataUrl)
        challenge shouldContain "error_description=\"goodbad\""
    }
})
