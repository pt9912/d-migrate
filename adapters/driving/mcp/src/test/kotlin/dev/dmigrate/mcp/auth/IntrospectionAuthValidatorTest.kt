package dev.dmigrate.mcp.auth

import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.Instant

private const val ISSUER = "https://issuer.example/"
private const val AUDIENCE = "mcp.dmigrate"

private fun mockClient(status: HttpStatusCode, body: String): HttpClient {
    val engine = MockEngine { _ ->
        respond(
            content = ByteReadChannel(body),
            status = status,
            headers = headersOf("Content-Type", "application/json"),
        )
    }
    return HttpClient(engine)
}

private fun config() = McpServerConfig(
    authMode = AuthMode.JWT_INTROSPECTION,
    issuer = URI.create(ISSUER),
    introspectionUrl = URI.create("https://issuer.example/introspect"),
    audience = AUDIENCE,
)

private val FAR_FUTURE: Long = Instant.parse("2099-01-01T00:00:00Z").epochSecond

class IntrospectionAuthValidatorTest : FunSpec({

    test("active=true with valid claims yields Valid PrincipalContext") {
        val body = """
            {
              "active": true,
              "sub": "user-1",
              "iss": "$ISSUER",
              "aud": "$AUDIENCE",
              "exp": $FAR_FUTURE,
              "scope": "dmigrate:read dmigrate:admin",
              "tenant_id": "tenant-A"
            }
        """.trimIndent()
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Valid>()
        result.principal.principalId shouldBe PrincipalId("user-1")
        result.principal.homeTenantId shouldBe TenantId("tenant-A")
        result.principal.isAdmin shouldBe true
    }

    test("active=false yields Invalid") {
        val body = """{"active": false}"""
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
        result.reason shouldContain "inactive"
    }

    test("HTTP error from introspection endpoint yields Invalid") {
        val validator = IntrospectionAuthValidator(
            config(),
            mockClient(HttpStatusCode.InternalServerError, ""),
        )
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
        result.reason shouldContain "500"
    }

    test("malformed JSON response yields Invalid") {
        val validator = IntrospectionAuthValidator(
            config(),
            mockClient(HttpStatusCode.OK, "not-json"),
        )
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
        result.reason shouldContain "malformed"
    }

    test("issuer mismatch yields Invalid") {
        val body = """
            {"active": true, "sub": "u", "iss": "https://attacker.example/",
             "aud": "$AUDIENCE", "exp": $FAR_FUTURE}
        """.trimIndent()
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
        result.reason shouldContain "issuer"
    }

    test("audience as array containing config audience is accepted") {
        val body = """
            {"active": true, "sub": "u", "iss": "$ISSUER",
             "aud": ["other", "$AUDIENCE"], "exp": $FAR_FUTURE}
        """.trimIndent()
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        runBlocking { validator.validate("any-token") }.shouldBeInstanceOf<BearerValidationResult.Valid>()
    }

    test("audience mismatch yields Invalid") {
        val body = """
            {"active": true, "sub": "u", "iss": "$ISSUER",
             "aud": "wrong-audience", "exp": $FAR_FUTURE}
        """.trimIndent()
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
        result.reason shouldContain "audience"
    }

    test("missing exp claim yields Invalid") {
        val body = """{"active": true, "sub": "u", "iss": "$ISSUER", "aud": "$AUDIENCE"}"""
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
        result.reason shouldContain "exp"
    }

    test("expired exp yields Invalid") {
        val past = Instant.now().minusSeconds(3600).epochSecond
        val body = """
            {"active": true, "sub": "u", "iss": "$ISSUER",
             "aud": "$AUDIENCE", "exp": $past}
        """.trimIndent()
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
        result.reason shouldContain "expired"
    }

    test("exp within configured clock-skew is accepted") {
        // §12.14: now <= exp + clockSkew. With the default 60s skew,
        // an exp 30s in the past is still valid.
        val justPast = Instant.now().minusSeconds(30).epochSecond
        val body = """
            {"active": true, "sub": "u", "iss": "$ISSUER",
             "aud": "$AUDIENCE", "exp": $justPast}
        """.trimIndent()
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Valid>()
    }

    test("nbf in the future (beyond clock-skew) yields Invalid") {
        val notYetValid = Instant.now().plusSeconds(3600).epochSecond
        val body = """
            {"active": true, "sub": "u", "iss": "$ISSUER",
             "aud": "$AUDIENCE", "exp": $FAR_FUTURE, "nbf": $notYetValid}
        """.trimIndent()
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
        result.reason shouldContain "not yet valid"
    }

    test("nbf in the past is accepted") {
        val pastNbf = Instant.now().minusSeconds(60).epochSecond
        val body = """
            {"active": true, "sub": "u", "iss": "$ISSUER",
             "aud": "$AUDIENCE", "exp": $FAR_FUTURE, "nbf": $pastNbf}
        """.trimIndent()
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Valid>()
    }

    test("iat in the future (beyond clock-skew) yields Invalid") {
        val futureIat = Instant.now().plusSeconds(3600).epochSecond
        val body = """
            {"active": true, "sub": "u", "iss": "$ISSUER",
             "aud": "$AUDIENCE", "exp": $FAR_FUTURE, "iat": $futureIat}
        """.trimIndent()
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
        result.reason shouldContain "iat in the future"
    }

    test("scp array claim is read when scope is absent") {
        val body = """
            {"active": true, "sub": "u", "iss": "$ISSUER",
             "aud": "$AUDIENCE", "exp": $FAR_FUTURE,
             "scp": ["dmigrate:read"]}
        """.trimIndent()
        val validator = IntrospectionAuthValidator(config(), mockClient(HttpStatusCode.OK, body))
        val result = runBlocking { validator.validate("any-token") }
        result.shouldBeInstanceOf<BearerValidationResult.Valid>()
        result.principal.scopes shouldBe setOf("dmigrate:read")
    }
})
