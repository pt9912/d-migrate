package dev.dmigrate.mcp.auth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import dev.dmigrate.mcp.server.McpServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URL
import java.text.ParseException

/**
 * JWT/JWKS-backed [AuthValidator] for `AuthMode.JWT_JWKS` per
 * `ImpPlan-0.9.6-B.md` §12.3 + §12.14.
 *
 * Uses Nimbus' `JWKSource` (with built-in cache + rotation) for the
 * key lookup and `DefaultJWTProcessor` for signature + claim
 * verification. Per-request validation; per-process JWKS cache.
 */
internal class JwksAuthValidator(
    config: McpServerConfig,
    keySource: JWKSource<SecurityContext>? = null,
) : AuthValidator {

    private val processor: DefaultJWTProcessor<SecurityContext>

    init {
        val jwksUrl = config.jwksUrl
        val issuer = requireNotNull(config.issuer) { "issuer is required for JWT_JWKS mode" }
        val audience = requireNotNull(config.audience) { "audience is required for JWT_JWKS mode" }
        val algorithms = config.algorithmAllowlist.map { JWSAlgorithm.parse(it) }.toSet()

        val resolvedKeySource = keySource
            ?: JWKSourceBuilder.create<SecurityContext>(
                URL(requireNotNull(jwksUrl) { "jwksUrl is required for JWT_JWKS mode without an injected key source" }.toString()),
            ).build()
        val keySelector = JWSVerificationKeySelector(algorithms, resolvedKeySource)

        // Nimbus 10.x exposes only the `String` audience overload here;
        // its internal check is "config audience appears anywhere in
        // token aud" — so a JWT that ships `aud` as either a string OR
        // an array containing our audience is accepted (§12.14).
        val requiredClaims: Set<String> = setOf("iss", "sub", "exp", "aud")
        val claimsVerifier = DefaultJWTClaimsVerifier<SecurityContext>(
            audience,
            JWTClaimsSet.Builder().issuer(issuer.toString()).build(),
            requiredClaims,
        ).apply {
            maxClockSkew = config.clockSkew.seconds.toInt()
        }

        processor = DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = keySelector
            jwtClaimsSetVerifier = claimsVerifier
        }
    }

    override suspend fun validate(token: String): BearerValidationResult =
        // CPU-bound after the JWKS cache hit (Nimbus' RemoteJWKSet handles
        // the network round-trip on key rotation only). `Dispatchers.IO`
        // would waste a scarce IO thread on signature verification.
        withContext(Dispatchers.Default) {
            try {
                val claims = processor.process(token, null)
                val subject = claims.subject?.takeUnless { it.isBlank() }
                    ?: return@withContext BearerValidationResult.Invalid("subject claim missing or empty")
                val expiry = claims.expirationTime?.toInstant()
                    ?: return@withContext BearerValidationResult.Invalid("exp claim missing")
                val scopes = ClaimsMapper.parseScopes(
                    scopeClaim = claims.getStringClaim("scope"),
                    scpClaim = readStringList(claims, "scp"),
                )
                val principal = ClaimsMapper.map(
                    subject = subject,
                    tenantClaim = claims.getStringClaim("tenant_id"),
                    tidClaim = claims.getStringClaim("tid"),
                    scopes = scopes,
                    expiresAt = expiry,
                )
                BearerValidationResult.Valid(principal)
            } catch (e: BadJOSEException) {
                // Covers BadJWTException (claim validation), BadJWSException
                // (kid not found / signature mismatch), BadJWEException.
                LOG.debug("token rejected at JOSE layer", e)
                BearerValidationResult.Invalid(e.message ?: "token rejected")
            } catch (e: JOSEException) {
                LOG.debug("JWS verification failed", e)
                BearerValidationResult.Invalid(e.message ?: "signature verification failed")
            } catch (e: ParseException) {
                LOG.debug("token parse failed", e)
                BearerValidationResult.Invalid("malformed token")
            } catch (e: java.io.IOException) {
                LOG.warn("JWKS fetch failed", e)
                BearerValidationResult.Invalid("authority unreachable")
            }
        }

    private fun readStringList(claims: JWTClaimsSet, name: String): List<String>? {
        val raw = claims.getClaim(name) ?: return null
        return when (raw) {
            is List<*> -> raw.filterIsInstance<String>()
            is String -> listOf(raw)
            else -> null
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(JwksAuthValidator::class.java)
    }
}
