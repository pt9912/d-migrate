package dev.dmigrate.server.persistence.jdbc.schema

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class SchemaIndexEntryJsonTest : FunSpec({

    fun sample(): SchemaIndexEntry = SchemaIndexEntry(
        schemaId = "schema_1",
        tenantId = TenantId("acme"),
        resourceUri = ServerResourceUri(
            tenantId = TenantId("acme"),
            kind = ResourceKind.SCHEMAS,
            id = "schema_1",
        ),
        artifactRef = "dmigrate://tenants/acme/artifacts/art_1",
        displayName = "Reverse-engineered schema",
        createdAt = Instant.parse("2026-05-06T10:00:00Z"),
        expiresAt = Instant.parse("2026-05-13T10:00:00Z"),
        jobRef = "job_1",
        labels = mapOf("origin" to "schema_reverse_start"),
        format = "yaml",
        origin = "schema_reverse_start",
        sizeBytes = 4096L,
        hash = "a".repeat(64),
    )

    test("round-trip preserves all fields") {
        val entry = sample()
        val parsed = SchemaIndexEntryJson.fromJson(SchemaIndexEntryJson.toJson(entry))
        parsed shouldBe entry
    }

    test("minimal entry (no jobRef/labels/format/origin/sizeBytes/hash) round-trips") {
        val entry = SchemaIndexEntry(
            schemaId = "schema_2",
            tenantId = TenantId("umbrella"),
            resourceUri = ServerResourceUri(
                tenantId = TenantId("umbrella"),
                kind = ResourceKind.SCHEMAS,
                id = "schema_2",
            ),
            artifactRef = "dmigrate://tenants/umbrella/artifacts/art_2",
            displayName = "Uploaded schema",
            createdAt = Instant.parse("2026-05-06T11:00:00Z"),
            expiresAt = Instant.parse("2026-05-13T11:00:00Z"),
        )
        val parsed = SchemaIndexEntryJson.fromJson(SchemaIndexEntryJson.toJson(entry))
        parsed shouldBe entry
    }
})
