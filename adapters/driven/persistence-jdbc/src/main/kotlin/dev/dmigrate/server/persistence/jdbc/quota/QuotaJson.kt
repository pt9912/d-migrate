package dev.dmigrate.server.persistence.jdbc.quota

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey

/**
 * JSON-Codec fuer [QuotaKey] und [QuotaReservation] (Plan-Refs:
 * `ImpPlan-0.9.6-E2.md` § 4.3 + § 4.4 + § 6.8 + § 6.9).
 *
 * - [QuotaKey] wird als TEXT-Schluessel in `quota_counters.quota_key`
 *   abgelegt; Field-Reihenfolge des Wire-DTO ist stabil, sodass identische
 *   Schluessel byte-gleich serialisieren (PK-Lookup-tauglich).
 * - [QuotaReservation] wird als JSONB in
 *   `quota_reservation_owners.reservation` abgelegt.
 *
 * Wire-DTO-Pattern (analog [JobRecordJson]) entkoppelt das persistente
 * Format vom Domain-Modell — V2-Migrationen koennen alte Records lesen,
 * ohne die Domain-Klasse zu beruehren.
 */
internal object QuotaJson {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    fun keyToText(key: QuotaKey): String =
        mapper.writeValueAsString(QuotaKeyWire.from(key))

    fun keyFromText(text: String): QuotaKey =
        mapper.readValue<QuotaKeyWire>(text).toDomain()

    fun reservationToJson(reservation: QuotaReservation): String =
        mapper.writeValueAsString(QuotaReservationWire.from(reservation))

    fun reservationFromJson(json: String): QuotaReservation =
        mapper.readValue<QuotaReservationWire>(json).toDomain()

    private data class QuotaKeyWire(
        val tenantId: String,
        val dimension: QuotaDimension,
        val principalId: String? = null,
        val operation: String? = null,
    ) {
        fun toDomain(): QuotaKey = QuotaKey(
            tenantId = TenantId(tenantId),
            dimension = dimension,
            principalId = principalId?.let(::PrincipalId),
            operation = operation,
        )

        companion object {
            fun from(k: QuotaKey) = QuotaKeyWire(
                tenantId = k.tenantId.value,
                dimension = k.dimension,
                principalId = k.principalId?.value,
                operation = k.operation,
            )
        }
    }

    private data class QuotaReservationWire(
        val key: QuotaKeyWire,
        val amount: Long,
    ) {
        fun toDomain(): QuotaReservation = QuotaReservation(
            key = key.toDomain(),
            amount = amount,
        )

        companion object {
            fun from(r: QuotaReservation) = QuotaReservationWire(
                key = QuotaKeyWire.from(r.key),
                amount = r.amount,
            )
        }
    }
}
