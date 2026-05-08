package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.cursor.CursorKey
import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.cursor.McpCursorCodec
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryDiffStore
import dev.dmigrate.server.ports.memory.InMemoryProfileStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * AP D8 sub-commit 2 cursor coverage for the four non-`job_list`
 * discovery handlers. The job_list handler keeps its own
 * round-trip test in [JobListHandlerTest]; this spec mirrors the
 * surface for `artifact_list`, `schema_list`, `profile_list` and
 * `diff_list` so each handler's seal/unseal path stays pinned
 * even though they share the same shape.
 */
class ListToolHandlersCursorTest : FunSpec({

    val tenantA = TenantId("acme")
    val alice = PrincipalId("alice")
    val principal = PrincipalContext(
        principalId = alice,
        homeTenantId = tenantA,
        effectiveTenantId = tenantA,
        allowedTenantIds = setOf(tenantA),
        scopes = setOf("dmigrate:read"),
        isAdmin = false,
        auditSubject = alice.value,
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )
    val codec = McpCursorCodec(
        keyring = CursorKeyring(
            signing = CursorKey(kid = "k1", secret = ByteArray(32) { it.toByte() }),
        ),
    )
    val sealed = SealedListToolCursor(codec)

    fun runHandler(handler: ToolHandler, argsJson: String?): com.google.gson.JsonObject {
        val outcome = handler.handle(
            ToolCallContext(
                name = "list-tool",
                arguments = argsJson?.let { JsonParser.parseString(it) },
                principal = principal,
            ),
        ) as ToolCallOutcome.Success
        return JsonParser.parseString(outcome.content.single().text!!).asJsonObject
    }

    test("artifact_list cursor round-trips") {
        val store = InMemoryArtifactStore().apply {
            (1..60).forEach { i ->
                save(
                    ArtifactRecord(
                        managedArtifact = ManagedArtifact(
                            artifactId = "art-%03d".format(i),
                            filename = "f$i.txt",
                            contentType = "text/plain",
                            sizeBytes = 1L,
                            sha256 = "0",
                            createdAt = Instant.parse("2026-05-04T10:00:00Z"),
                            expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
                        ),
                        kind = ArtifactKind.OTHER,
                        tenantId = tenantA,
                        ownerPrincipalId = alice,
                        visibility = JobVisibility.TENANT,
                        resourceUri = ServerResourceUri(tenantA, ResourceKind.ARTIFACTS, "art-%03d".format(i)),
                    ),
                )
            }
        }
        val handler = ArtifactListHandler(store, sealed)
        val first = runHandler(handler, """{"pageSize":50}""")
        first.getAsJsonArray("artifacts").size() shouldBe 50
        val cursor = first.get("nextCursor").asString
        val second = runHandler(handler, """{"pageSize":50,"cursor":"$cursor"}""")
        second.getAsJsonArray("artifacts").size() shouldBe 10
    }

    test("schema_list cursor round-trips") {
        val store = InMemorySchemaStore().apply {
            (1..60).forEach { i ->
                save(
                    SchemaIndexEntry(
                        schemaId = "schema-%03d".format(i),
                        tenantId = tenantA,
                        displayName = "n$i",
                        artifactRef = "art://$i",
                        createdAt = Instant.parse("2026-05-04T10:00:00Z"),
                        expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
                        jobRef = null,
                        labels = emptyMap(),
                        resourceUri = ServerResourceUri(tenantA, ResourceKind.SCHEMAS, "schema-%03d".format(i)),
                    ),
                )
            }
        }
        val handler = SchemaListHandler(store, sealed)
        val first = runHandler(handler, """{"pageSize":50}""")
        first.getAsJsonArray("schemas").size() shouldBe 50
        val cursor = first.get("nextCursor").asString
        val second = runHandler(handler, """{"pageSize":50,"cursor":"$cursor"}""")
        second.getAsJsonArray("schemas").size() shouldBe 10
    }

    test("profile_list cursor round-trips") {
        val store = InMemoryProfileStore().apply {
            (1..60).forEach { i ->
                save(
                    ProfileIndexEntry(
                        profileId = "prof-%03d".format(i),
                        tenantId = tenantA,
                        displayName = "n$i",
                        artifactRef = "art://$i",
                        createdAt = Instant.parse("2026-05-04T10:00:00Z"),
                        expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
                        jobRef = null,
                        labels = emptyMap(),
                        resourceUri = ServerResourceUri(tenantA, ResourceKind.PROFILES, "prof-%03d".format(i)),
                    ),
                )
            }
        }
        val handler = ProfileListHandler(store, sealed)
        val first = runHandler(handler, """{"pageSize":50}""")
        first.getAsJsonArray("profiles").size() shouldBe 50
        val cursor = first.get("nextCursor").asString
        val second = runHandler(handler, """{"pageSize":50,"cursor":"$cursor"}""")
        second.getAsJsonArray("profiles").size() shouldBe 10
    }

    test("diff_list cursor round-trips") {
        val store = InMemoryDiffStore().apply {
            (1..60).forEach { i ->
                save(
                    DiffIndexEntry(
                        diffId = "diff-%03d".format(i),
                        tenantId = tenantA,
                        displayName = "n$i",
                        artifactRef = "art://$i",
                        sourceRef = "src",
                        targetRef = "tgt",
                        createdAt = Instant.parse("2026-05-04T10:00:00Z"),
                        expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
                        jobRef = null,
                        labels = emptyMap(),
                        resourceUri = ServerResourceUri(tenantA, ResourceKind.DIFFS, "diff-%03d".format(i)),
                    ),
                )
            }
        }
        val handler = DiffListHandler(store, sealed)
        val first = runHandler(handler, """{"pageSize":50}""")
        first.getAsJsonArray("diffs").size() shouldBe 50
        val cursor = first.get("nextCursor").asString
        val second = runHandler(handler, """{"pageSize":50,"cursor":"$cursor"}""")
        second.getAsJsonArray("diffs").size() shouldBe 10
    }

    test("cursor minted with pageSize=50 cannot resume with pageSize=100") {
        // AP D8: pageSize is part of the binding. Phrasing the
        // pin against artifact_list (any list-tool would do)
        // proves the binding-mismatch path surfaces as
        // VALIDATION_ERROR through the handler-shared SealedListToolCursor.
        val store = InMemoryArtifactStore().apply {
            (1..60).forEach { i ->
                save(
                    ArtifactRecord(
                        managedArtifact = ManagedArtifact(
                            artifactId = "art-%03d".format(i),
                            filename = "f$i",
                            contentType = "text/plain",
                            sizeBytes = 1L,
                            sha256 = "0",
                            createdAt = Instant.parse("2026-05-04T10:00:00Z"),
                            expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
                        ),
                        kind = ArtifactKind.OTHER,
                        tenantId = tenantA,
                        ownerPrincipalId = alice,
                        visibility = JobVisibility.TENANT,
                        resourceUri = ServerResourceUri(tenantA, ResourceKind.ARTIFACTS, "art-%03d".format(i)),
                    ),
                )
            }
        }
        val handler = ArtifactListHandler(store, sealed)
        val first = runHandler(handler, """{"pageSize":50}""")
        val cursor = first.get("nextCursor").asString
        shouldThrow<ValidationErrorException> {
            runHandler(handler, """{"pageSize":100,"cursor":"$cursor"}""")
        }
    }
})
