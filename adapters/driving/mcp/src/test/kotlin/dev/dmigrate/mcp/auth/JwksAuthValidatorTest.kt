package dev.dmigrate.mcp.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date

private const val RSA_KEY_SIZE = 2048
private const val ISSUER = "https://issuer.example/"
private const val AUDIENCE = "mcp.dmigrate"

private class TestSigner {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_SIZE) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey
    val keyId: String = "test-key"

    val keySource: JWKSource<SecurityContext> = ImmutableJWKSet(
        JWKSet(
            RSAKey.Builder(publicKey)
                .keyID(keyId)
                .algorithm(JWSAlgorithm.RS256)
                .build(),
        ),
    )

    fun sign(claims: JWTClaimsSet, kid: String = keyId, alg: JWSAlgorithm = JWSAlgorithm.RS256): String {
        val jwt = SignedJWT(
            JWSHeader.Builder(alg).keyID(kid).build(),
            claims,
        )
        jwt.sign(RSASSASigner(privateKey))
        return jwt.serialize()
    }
}

private fun config(): McpServerConfig = McpServerConfig(
    authMode = AuthMode.JWT_JWKS,
    issuer = URI.create(ISSUER),
    jwksUrl = URI.create("https://issuer.example/jwks"),
    audience = AUDIENCE,
)

private fun validClaims(builder: JWTClaimsSet.Builder.() -> Unit = {}): JWTClaimsSet =
    JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject("user-1")
        .audience(AUDIENCE)
        .expirationTime(Date.from(java.time.Instant.now().plusSeconds(60)))
        .issueTime(Date.from(java.time.Instant.now()))
        .apply(builder)
        .build()

class JwksAuthValidatorTest : FunSpec({

    test("valid JWT yields Authorized PrincipalContext") {
        val signer = TestSigner()
        val validator = JwksAuthValidator(config(), signer.keySource)
        val token = signer.sign(validClaims {
            claim("scope", "dmigrate:read dmigrate:job:start")
            claim("tenant_id", "tenant-A")
        })
        val result = runBlocking { validator.validate(token) }
        result.shouldBeInstanceOf<BearerValidationResult.Valid>()
        result.principal.principalId shouldBe PrincipalId("user-1")
        result.principal.homeTenantId shouldBe TenantId("tenant-A")
        result.principal.scopes shouldBe setOf("dmigrate:read", "dmigrate:job:start")
    }

    test("aud as JSON array containing audience is accepted") {
        val signer = TestSigner()
        val validator = JwksAuthValidator(config(), signer.keySource)
        val token = signer.sign(validClaims {
            audience(listOf("other-aud", AUDIENCE))
        })
        runBlocking { validator.validate(token) }.shouldBeInstanceOf<BearerValidationResult.Valid>()
    }

    test("expired token is rejected") {
        val signer = TestSigner()
        val validator = JwksAuthValidator(config(), signer.keySource)
        val token = signer.sign(validClaims {
            expirationTime(Date.from(java.time.Instant.now().minusSeconds(3600)))
        })
        val result = runBlocking { validator.validate(token) }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
    }

    test("wrong issuer is rejected") {
        val signer = TestSigner()
        val validator = JwksAuthValidator(config(), signer.keySource)
        val token = signer.sign(validClaims { issuer("https://attacker.example/") })
        val result = runBlocking { validator.validate(token) }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
    }

    test("wrong audience is rejected") {
        val signer = TestSigner()
        val validator = JwksAuthValidator(config(), signer.keySource)
        val token = signer.sign(validClaims { audience("not-the-audience") })
        val result = runBlocking { validator.validate(token) }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
    }

    test("missing subject yields Invalid (subject claim is mandatory)") {
        val signer = TestSigner()
        val validator = JwksAuthValidator(config(), signer.keySource)
        // JWTClaimsSet still requires sub at builder level; use empty string instead.
        val token = signer.sign(validClaims { subject("") })
        val result = runBlocking { validator.validate(token) }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
        result.reason shouldContain "subject"
    }

    test("unknown kid (signed with key not in JWKS) is rejected") {
        val signerA = TestSigner()
        val signerB = TestSigner()
        // validator only knows signerA's keys; signerB's token has a kid the JWKS doesn't list.
        val validator = JwksAuthValidator(config(), signerA.keySource)
        val token = signerB.sign(validClaims())
        val result = runBlocking { validator.validate(token) }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
    }

    test("malformed token is rejected") {
        val signer = TestSigner()
        val validator = JwksAuthValidator(config(), signer.keySource)
        val result = runBlocking { validator.validate("not.a.valid.jwt") }
        result.shouldBeInstanceOf<BearerValidationResult.Invalid>()
    }

    test("scp claim (Microsoft array) is read when scope is absent") {
        val signer = TestSigner()
        val validator = JwksAuthValidator(config(), signer.keySource)
        val token = signer.sign(validClaims {
            claim("scp", listOf("dmigrate:read", "dmigrate:job:start"))
        })
        val result = runBlocking { validator.validate(token) }
        result.shouldBeInstanceOf<BearerValidationResult.Valid>()
        result.principal.scopes shouldBe setOf("dmigrate:read", "dmigrate:job:start")
    }

    test("admin scope sets isAdmin") {
        val signer = TestSigner()
        val validator = JwksAuthValidator(config(), signer.keySource)
        val token = signer.sign(validClaims { claim("scope", "dmigrate:admin") })
        val result = runBlocking { validator.validate(token) }
        result.shouldBeInstanceOf<BearerValidationResult.Valid>()
        result.principal.isAdmin shouldBe true
    }

    test("tid claim (Microsoft) is used as tenant when tenant_id is absent") {
        val signer = TestSigner()
        val validator = JwksAuthValidator(config(), signer.keySource)
        val token = signer.sign(validClaims { claim("tid", "tenant-MSFT") })
        val result = runBlocking { validator.validate(token) }
        result.shouldBeInstanceOf<BearerValidationResult.Valid>()
        result.principal.homeTenantId shouldBe TenantId("tenant-MSFT")
    }
})
