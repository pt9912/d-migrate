package dev.dmigrate.server.persistence.jdbc.schema

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.ProfileIndexEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/** Jedes Feld muss die JSONB-Spalte ueberstehen: ein fehlendes fiele erst nach einem Neustart auf. */
class IndexEntryJsonTest : FunSpec({

    val tenant = TenantId("acme")
    val now = Instant.parse("2026-11-27T10:00:00Z")

    test("DiffIndexEntry mit allen Feldern uebersteht den Rundlauf") {
        val entry = DiffIndexEntry(
            diffId = "diff-1",
            tenantId = tenant,
            resourceUri = ServerResourceUri(tenant, ResourceKind.DIFFS, "diff-1"),
            artifactRef = "art-1",
            sourceRef = "src",
            targetRef = "dst",
            displayName = "Diff",
            createdAt = now,
            expiresAt = now.plusSeconds(60),
            jobRef = "job-1",
            labels = mapOf("k" to "v"),
            statusSummary = "DIFF_PUBLISHED",
        )

        DiffIndexEntryJson.fromJson(DiffIndexEntryJson.toJson(entry)) shouldBe entry
    }

    test("ProfileIndexEntry mit allen Feldern uebersteht den Rundlauf") {
        val entry = ProfileIndexEntry(
            profileId = "prof-1",
            tenantId = tenant,
            resourceUri = ServerResourceUri(tenant, ResourceKind.PROFILES, "prof-1"),
            artifactRef = "art-2",
            displayName = "Profile",
            createdAt = now,
            expiresAt = now.plusSeconds(60),
            jobRef = "job-2",
            labels = mapOf("k" to "v"),
            connectionRef = "src",
            scope = "full",
            warningCount = 2,
        )

        ProfileIndexEntryJson.fromJson(ProfileIndexEntryJson.toJson(entry)) shouldBe entry
    }
})
