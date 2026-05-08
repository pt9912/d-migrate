package dev.dmigrate.mcp.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import dev.dmigrate.mcp.server.McpServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * RFC 7662 Token-Introspection-backed [AuthValidator] for
 * `AuthMode.JWT_INTROSPECTION` per `ImpPlan-0.9.6-B.md` §12.14.
 *
 * Posts `token=<bearer>` (form-encoded) to the configured
 * introspection endpoint, parses the JSON response, and maps the
 * claims via [ClaimsMapper]. `active=false` is invalid; missing
 * `active` is treated as invalid (fail-closed).
 *
 * Per-request validation — there is no claim cache. The Ktor HTTP
 * client is reused across requests; closed when the route is shut
 * down via [close].
 */
internal class IntrospectionAuthValidator(
    config: McpServerConfig,
    private val httpClient: HttpClient = HttpClient(CIO),
    private val mapper: ObjectMapper = ObjectMapper(),
    private val now: () -> Instant = Instant::now,
) : AuthValidator, AutoCloseable {

    private val introspectionUrl: String = requireNotNull(config.introspectionUrl) {
        "introspectionUrl is required for JWT_INTROSPECTION mode"
    }.toString()
    private val expectedIssuer: String = requireNotNull(config.issuer) {
        "issuer is required for JWT_INTROSPECTION mode"
    }.toString()
    private val expectedAudience: String = requireNotNull(config.audience) {
        "audience is required for JWT_INTROSPECTION mode"
    }
    private val clockSkew: java.time.Duration = config.clockSkew

    override suspend fun validate(token: String): BearerValidationResult {
        val body = try {
            // TODO(0.9.7): RFC 7662 §2.1 client authentication. Phase B
            // assumes a trusted internal Introspection endpoint; production
            // setups with client_credentials need to add Authorization here.
            val response = httpClient.submitForm(
                url = introspectionUrl,
                formParameters = parameters { append("token", token) },
            )
            if (response.status != HttpStatusCode.OK) {
                LOG.warn("introspection endpoint returned {}", response.status.value)
                return BearerValidationResult.Invalid("introspection endpoint returned ${response.status.value}")
            }
            response.bodyAsText()
        } catch (e: Exception) {
            LOG.warn("introspection call failed", e)
            return BearerValidationResult.Invalid("authority unreachable")
        }

        val json = try {
            mapper.readTree(body)
        } catch (e: Exception) {
            LOG.warn("introspection response parse failed", e)
            return BearerValidationResult.Invalid("malformed introspection response")
        }

        val active = json["active"]?.asBoolean(false) ?: false
        if (!active) return BearerValidationResult.Invalid("token inactive")

        val subject = json["sub"]?.asText()?.takeUnless { it.isBlank() }
            ?: return BearerValidationResult.Invalid("subject claim missing or empty")
        val issuer = json["iss"]?.asText()
        if (issuer != expectedIssuer) {
            return BearerValidationResult.Invalid("issuer mismatch")
        }
        if (!audienceContains(json["aud"], expectedAudience)) {
            return BearerValidationResult.Invalid("audience mismatch")
        }
        val expiry = json["exp"]?.asLong()?.let { Instant.ofEpochSecond(it) }
            ?: return BearerValidationResult.Invalid("exp claim missing")
        // §12.14 Pflichtclaims: now <= exp + clockSkew. Allow the
        // introspector and our clock to differ by up to the configured
        // skew window — same window the JWKS path uses (Nimbus
        // `maxClockSkew`).
        val nowInstant = now()
        if (nowInstant.isAfter(expiry.plus(clockSkew))) {
            return BearerValidationResult.Invalid("token expired")
        }
        // §12.14: nbf and iat are optional, but when present must hold:
        //   nbf - clockSkew <= now (token not yet valid otherwise)
        //   iat            <= now + clockSkew (issued in the future =
        //                                       authority clock is broken)
        json["nbf"]?.asLong()?.let { nbfEpoch ->
            val nbf = Instant.ofEpochSecond(nbfEpoch)
            if (nowInstant.isBefore(nbf.minus(clockSkew))) {
                return BearerValidationResult.Invalid("token not yet valid (nbf=$nbf)")
            }
        }
        json["iat"]?.asLong()?.let { iatEpoch ->
            val iat = Instant.ofEpochSecond(iatEpoch)
            if (iat.isAfter(nowInstant.plus(clockSkew))) {
                return BearerValidationResult.Invalid("iat in the future (iat=$iat)")
            }
        }

        val scopes = ClaimsMapper.parseScopes(
            scopeClaim = json["scope"]?.asText(),
            scpClaim = (json["scp"] as? ArrayNode)?.mapNotNull { it.asText() },
        )
        val principal = ClaimsMapper.map(
            subject = subject,
            tenantClaim = json["tenant_id"]?.asText(),
            tidClaim = json["tid"]?.asText(),
            scopes = scopes,
            expiresAt = expiry,
        )
        return BearerValidationResult.Valid(principal)
    }

    override fun close() {
        httpClient.close()
    }

    private fun audienceContains(audNode: com.fasterxml.jackson.databind.JsonNode?, expected: String): Boolean {
        if (audNode == null || audNode.isNull) return false
        if (audNode.isTextual) return audNode.asText() == expected
        if (audNode is ArrayNode) return audNode.any { it.isTextual && it.asText() == expected }
        return false
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(IntrospectionAuthValidator::class.java)
    }
}
