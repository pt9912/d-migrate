package dev.dmigrate.server.persistence.jdbc.schema

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ProfileIndexEntry
import java.time.Instant

/** JSON-Codec fuer [ProfileIndexEntry] in `profile_index_entries.entry`, wie [SchemaIndexEntryJson]. */
internal object ProfileIndexEntryJson {

    private val mapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    fun toJson(entry: ProfileIndexEntry): String = mapper.writeValueAsString(ProfileIndexEntryWire.from(entry))

    fun fromJson(json: String): ProfileIndexEntry = mapper.readValue<ProfileIndexEntryWire>(json).toDomain()

    private data class ProfileIndexEntryWire(
        val profileId: String,
        val tenantId: String,
        val resourceUri: ResourceUriWire,
        val artifactRef: String,
        val displayName: String,
        val createdAt: Instant,
        val expiresAt: Instant,
        val jobRef: String? = null,
        val labels: Map<String, String> = emptyMap(),
        val connectionRef: String? = null,
        val scope: String? = null,
        val warningCount: Int? = null,
    ) {
        fun toDomain(): ProfileIndexEntry = ProfileIndexEntry(
            profileId = profileId,
            tenantId = TenantId(tenantId),
            resourceUri = resourceUri.toDomain(),
            artifactRef = artifactRef,
            displayName = displayName,
            createdAt = createdAt,
            expiresAt = expiresAt,
            jobRef = jobRef,
            labels = labels,
            connectionRef = connectionRef,
            scope = scope,
            warningCount = warningCount,
        )

        companion object {
            fun from(e: ProfileIndexEntry) = ProfileIndexEntryWire(
                profileId = e.profileId,
                tenantId = e.tenantId.value,
                resourceUri = ResourceUriWire.from(e.resourceUri),
                artifactRef = e.artifactRef,
                displayName = e.displayName,
                createdAt = e.createdAt,
                expiresAt = e.expiresAt,
                jobRef = e.jobRef,
                labels = e.labels,
                connectionRef = e.connectionRef,
                scope = e.scope,
                warningCount = e.warningCount,
            )
        }
    }
}
