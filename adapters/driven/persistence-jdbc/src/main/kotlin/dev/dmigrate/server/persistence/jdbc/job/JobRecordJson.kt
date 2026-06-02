package dev.dmigrate.server.persistence.jdbc.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * JSON-Codec fuer [JobRecord] zur Persistierung in der
 * `jobs.managed_job`-JSONB-Spalte (LF-012 / LN-011 / LN-017 / LN-027).
 *
 * Format-Stabilitaet: Schema-Version 1 — der Wire-Type spiegelt das
 * Domain-Modell (ManagedJob inklusive verschachtelter `cancelRequest`,
 * `error`, `progress`) plus die JobRecord-Extras (ownerPrincipalId,
 * visibility, resourceUri, adminScope, quotaReservationOwnerId). Die
 * `tenant_id` / `job_id` / `status` / `cancel_requested` /
 * `cancel_source` / `created_at` / `updated_at` / `expires_at`-Spalten
 * sind extrahierte Kopien fuer Index-/Filter-Zwecke; die JSONB-Spalte
 * ist die Source-of-Truth.
 *
 * Bewusster LF-010 / LF-013 / LN-009 / LN-011 Carve-out gegen § 4.2-Wortlaut „managed_job: ManagedJob
 * serialized": die Spalte traegt den vollstaendigen [JobRecord], nicht
 * nur den inneren [ManagedJob]. Begruendung: das Schema hat keine
 * dedizierten Spalten fuer ownerPrincipalId/visibility/resourceUri etc.,
 * also muessen sie in JSONB. Spalten-Name ist historisch.
 */
internal object JobRecordJson {

    private val mapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    fun toJson(record: JobRecord): String =
        mapper.writeValueAsString(JobRecordWire.from(record))

    fun fromJson(json: String): JobRecord =
        mapper.readValue<JobRecordWire>(json).toDomain()

    private data class JobRecordWire(
        val managedJob: ManagedJob,
        val tenantId: String,
        val ownerPrincipalId: String,
        val visibility: JobVisibility,
        val resourceUri: ServerResourceUriWire,
        val adminScope: String? = null,
        val quotaReservationOwnerId: String? = null,
    ) {
        fun toDomain(): JobRecord = JobRecord(
            managedJob = managedJob,
            tenantId = TenantId(tenantId),
            ownerPrincipalId = PrincipalId(ownerPrincipalId),
            visibility = visibility,
            resourceUri = resourceUri.toDomain(),
            adminScope = adminScope,
            quotaReservationOwnerId = quotaReservationOwnerId,
        )

        companion object {
            fun from(r: JobRecord) = JobRecordWire(
                managedJob = r.managedJob,
                tenantId = r.tenantId.value,
                ownerPrincipalId = r.ownerPrincipalId.value,
                visibility = r.visibility,
                resourceUri = ServerResourceUriWire.from(r.resourceUri),
                adminScope = r.adminScope,
                quotaReservationOwnerId = r.quotaReservationOwnerId,
            )
        }
    }

    private data class ServerResourceUriWire(
        val tenantId: String,
        val kind: ResourceKind,
        val id: String,
    ) {
        fun toDomain(): ServerResourceUri = ServerResourceUri(
            tenantId = TenantId(tenantId),
            kind = kind,
            id = id,
        )

        companion object {
            fun from(u: ServerResourceUri) = ServerResourceUriWire(
                tenantId = u.tenantId.value,
                kind = u.kind,
                id = u.id,
            )
        }
    }
}
