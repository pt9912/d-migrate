package dev.dmigrate.server.persistence.jdbc.schema

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import java.time.Instant

/**
 * JSON-Codec fuer [SchemaIndexEntry] zur Persistierung in der
 * `schema_index_entries.entry`-JSONB-Spalte
 * (ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md AP1).
 * Analog `JobRecordJson` im `job`-Package desselben Moduls: `tenantId`
 * ist ein `value class`-Wrapper und braucht deshalb eine Wire-Form,
 * `ServerResourceUri` ebenso (eingebettetes `TenantId`).
 */
internal object SchemaIndexEntryJson {

    private val mapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    fun toJson(entry: SchemaIndexEntry): String =
        mapper.writeValueAsString(SchemaIndexEntryWire.from(entry))

    fun fromJson(json: String): SchemaIndexEntry =
        mapper.readValue<SchemaIndexEntryWire>(json).toDomain()

    private data class SchemaIndexEntryWire(
        val schemaId: String,
        val tenantId: String,
        val resourceUri: ServerResourceUriWire,
        val artifactRef: String,
        val displayName: String,
        val createdAt: Instant,
        val expiresAt: Instant,
        val jobRef: String? = null,
        val labels: Map<String, String> = emptyMap(),
        val format: String? = null,
        val origin: String? = null,
        val sizeBytes: Long? = null,
        val hash: String? = null,
    ) {
        fun toDomain(): SchemaIndexEntry = SchemaIndexEntry(
            schemaId = schemaId,
            tenantId = TenantId(tenantId),
            resourceUri = resourceUri.toDomain(),
            artifactRef = artifactRef,
            displayName = displayName,
            createdAt = createdAt,
            expiresAt = expiresAt,
            jobRef = jobRef,
            labels = labels,
            format = format,
            origin = origin,
            sizeBytes = sizeBytes,
            hash = hash,
        )

        companion object {
            fun from(e: SchemaIndexEntry) = SchemaIndexEntryWire(
                schemaId = e.schemaId,
                tenantId = e.tenantId.value,
                resourceUri = ServerResourceUriWire.from(e.resourceUri),
                artifactRef = e.artifactRef,
                displayName = e.displayName,
                createdAt = e.createdAt,
                expiresAt = e.expiresAt,
                jobRef = e.jobRef,
                labels = e.labels,
                format = e.format,
                origin = e.origin,
                sizeBytes = e.sizeBytes,
                hash = e.hash,
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
