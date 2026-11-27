package dev.dmigrate.server.persistence.jdbc.schema

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.DiffIndexEntry
import java.time.Instant

/** JSON-Codec fuer [DiffIndexEntry] in `diff_index_entries.entry`, wie [SchemaIndexEntryJson]. */
internal object DiffIndexEntryJson {

    private val mapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    fun toJson(entry: DiffIndexEntry): String = mapper.writeValueAsString(DiffIndexEntryWire.from(entry))

    fun fromJson(json: String): DiffIndexEntry = mapper.readValue<DiffIndexEntryWire>(json).toDomain()

    private data class DiffIndexEntryWire(
        val diffId: String,
        val tenantId: String,
        val resourceUri: ResourceUriWire,
        val artifactRef: String,
        val sourceRef: String,
        val targetRef: String,
        val displayName: String,
        val createdAt: Instant,
        val expiresAt: Instant,
        val jobRef: String? = null,
        val labels: Map<String, String> = emptyMap(),
        val statusSummary: String? = null,
    ) {
        fun toDomain(): DiffIndexEntry = DiffIndexEntry(
            diffId = diffId,
            tenantId = TenantId(tenantId),
            resourceUri = resourceUri.toDomain(),
            artifactRef = artifactRef,
            sourceRef = sourceRef,
            targetRef = targetRef,
            displayName = displayName,
            createdAt = createdAt,
            expiresAt = expiresAt,
            jobRef = jobRef,
            labels = labels,
            statusSummary = statusSummary,
        )

        companion object {
            fun from(e: DiffIndexEntry) = DiffIndexEntryWire(
                diffId = e.diffId,
                tenantId = e.tenantId.value,
                resourceUri = ResourceUriWire.from(e.resourceUri),
                artifactRef = e.artifactRef,
                sourceRef = e.sourceRef,
                targetRef = e.targetRef,
                displayName = e.displayName,
                createdAt = e.createdAt,
                expiresAt = e.expiresAt,
                jobRef = e.jobRef,
                labels = e.labels,
                statusSummary = e.statusSummary,
            )
        }
    }
}
