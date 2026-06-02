package dev.dmigrate.server.persistence.jdbc.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.dmigrate.server.core.approval.ApprovalChallenge
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind

/**
 * JSON-Codec fuer [ApprovalChallenge] zur Persistierung in der
 * `idempotency_reservations.challenge`-JSONB-Spalte (LF-012 / LN-011 / LN-017 / LN-027).
 *
 * Format-Stabilitaet: Schema-Version 1 — die fuenf Felder spiegeln das
 * Kotlin-DTO eins-zu-eins. Erweiterungen brauchen einen neuen Migration-
 * Step (Vx + Code-Pfad fuer alte Persisted-Records).
 *
 * Bewusst NICHT generisch fuer beliebige Domain-Typen: jeder Store
 * (Idempotency, JobStore, QuotaOwner) bringt seinen eigenen Codec, damit
 * Schema-Bumps lokal isoliert sind.
 */
internal object ApprovalChallengeJson {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    fun toJson(challenge: ApprovalChallenge): String =
        mapper.writeValueAsString(Wire.from(challenge))

    fun fromJson(json: String): ApprovalChallenge =
        mapper.readValue<Wire>(json).toDomain()

    /**
     * Wire-DTO trennt das persistente JSON-Format vom domain-Objekt.
     * Wenn [ApprovalChallenge] Felder umbenannt oder umstrukturiert
     * werden, kann dieses Wire-Schema mit V2-Migration koexistieren.
     */
    private data class Wire(
        val approvalRequestId: String,
        val correlationKind: ApprovalCorrelationKind,
        val correlationKey: String,
        val requiredScopes: List<String>,
        val reasons: List<String>,
    ) {
        fun toDomain(): ApprovalChallenge = ApprovalChallenge(
            approvalRequestId = approvalRequestId,
            correlationKind = correlationKind,
            correlationKey = correlationKey,
            requiredScopes = requiredScopes.toSet(),
            reasons = reasons,
        )

        companion object {
            fun from(c: ApprovalChallenge) = Wire(
                approvalRequestId = c.approvalRequestId,
                correlationKind = c.correlationKind,
                correlationKey = c.correlationKey,
                requiredScopes = c.requiredScopes.sorted(),
                reasons = c.reasons,
            )
        }
    }
}
