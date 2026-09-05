package dev.dmigrate.server.persistence.jdbc.artifact

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ArtifactUploadMetadata
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * JSON-Codec fuer [ArtifactRecord] zur Persistierung in der
 * `artifact_records.record`-JSONB-Spalte
 * (ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md AP1).
 * Analog `JobRecordJson`/`SchemaIndexEntryJson` im selben Modul.
 */
internal object ArtifactRecordJson {

    private val mapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    fun toJson(record: ArtifactRecord): String =
        mapper.writeValueAsString(ArtifactRecordWire.from(record))

    fun fromJson(json: String): ArtifactRecord =
        mapper.readValue<ArtifactRecordWire>(json).toDomain()

    private data class ArtifactRecordWire(
        val managedArtifact: ManagedArtifact,
        val kind: ArtifactKind,
        val tenantId: String,
        val ownerPrincipalId: String,
        val visibility: JobVisibility,
        val resourceUri: ServerResourceUriWire,
        val adminScope: String? = null,
        val jobRef: String? = null,
        val uploadMetadata: ArtifactUploadMetadata? = null,
    ) {
        fun toDomain(): ArtifactRecord = ArtifactRecord(
            managedArtifact = managedArtifact,
            kind = kind,
            tenantId = TenantId(tenantId),
            ownerPrincipalId = PrincipalId(ownerPrincipalId),
            visibility = visibility,
            resourceUri = resourceUri.toDomain(),
            adminScope = adminScope,
            jobRef = jobRef,
            uploadMetadata = uploadMetadata,
        )

        companion object {
            fun from(r: ArtifactRecord) = ArtifactRecordWire(
                managedArtifact = r.managedArtifact,
                kind = r.kind,
                tenantId = r.tenantId.value,
                ownerPrincipalId = r.ownerPrincipalId.value,
                visibility = r.visibility,
                resourceUri = ServerResourceUriWire.from(r.resourceUri),
                adminScope = r.adminScope,
                jobRef = r.jobRef,
                uploadMetadata = r.uploadMetadata,
            )
        }
    }

    private data class ServerResourceUriWire(val tenantId: String, val kind: ResourceKind, val id: String) {
        fun toDomain(): ServerResourceUri = ServerResourceUri(TenantId(tenantId), kind, id)

        companion object {
            fun from(u: ServerResourceUri) = ServerResourceUriWire(u.tenantId.value, u.kind, u.id)
        }
    }
}
