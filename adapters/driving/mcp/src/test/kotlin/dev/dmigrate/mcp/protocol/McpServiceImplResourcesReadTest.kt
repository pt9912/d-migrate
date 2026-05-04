package dev.dmigrate.mcp.protocol

import com.google.gson.Gson
import dev.dmigrate.mcp.resources.ResourceStores
import dev.dmigrate.mcp.resources.ResourcesReadHandler
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.job.JobError
import dev.dmigrate.server.core.job.JobProgress
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
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
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import dev.dmigrate.server.ports.memory.InMemoryDiffStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryProfileStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.time.Instant
import java.util.concurrent.ExecutionException

private val TENANT = TenantId("acme")
private val SECONDARY_TENANT = TenantId("beta")
private val ALICE = PrincipalId("alice")
private val BOB = PrincipalId("bob")

private fun principal(
    id: PrincipalId = ALICE,
    tenant: TenantId = TENANT,
    allowedTenants: Set<TenantId> = setOf(TENANT),
    scopes: Set<String> = setOf("dmigrate:read"),
    isAdmin: Boolean = false,
): PrincipalContext = PrincipalContext(
    principalId = id,
    homeTenantId = tenant,
    effectiveTenantId = tenant,
    allowedTenantIds = allowedTenants,
    scopes = scopes,
    isAdmin = isAdmin,
    auditSubject = id.value,
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private fun jobRecord(
    jobId: String = "job-1",
    owner: PrincipalId = ALICE,
    visibility: JobVisibility = JobVisibility.OWNER,
    tenant: TenantId = TENANT,
): JobRecord = JobRecord(
    managedJob = ManagedJob(
        jobId = jobId,
        operation = "schema_generate",
        status = JobStatus.SUCCEEDED,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:01:00Z"),
        expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
        createdBy = owner.value,
        artifacts = listOf("artifact-1"),
    ),
    tenantId = tenant,
    ownerPrincipalId = owner,
    visibility = visibility,
    resourceUri = ServerResourceUri(tenant, ResourceKind.JOBS, jobId),
)

private fun artifactRecord(
    artifactId: String = "art-1",
    owner: PrincipalId = ALICE,
    visibility: JobVisibility = JobVisibility.OWNER,
    tenant: TenantId = TENANT,
): ArtifactRecord = ArtifactRecord(
    managedArtifact = ManagedArtifact(
        artifactId = artifactId,
        filename = "schema.sql",
        contentType = "application/sql",
        sizeBytes = 1024,
        sha256 = "deadbeef",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
    ),
    kind = ArtifactKind.SCHEMA,
    tenantId = tenant,
    ownerPrincipalId = owner,
    visibility = visibility,
    resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, artifactId),
    jobRef = "job-1",
)

private fun stores(
    jobs: List<JobRecord> = emptyList(),
    artifacts: List<ArtifactRecord> = emptyList(),
    schemas: List<SchemaIndexEntry> = emptyList(),
    profiles: List<ProfileIndexEntry> = emptyList(),
    diffs: List<DiffIndexEntry> = emptyList(),
    connections: List<ConnectionReference> = emptyList(),
): ResourceStores {
    val jobStore = InMemoryJobStore().apply { jobs.forEach { save(it) } }
    val artifactStore = InMemoryArtifactStore().apply { artifacts.forEach { save(it) } }
    val schemaStore = InMemorySchemaStore().apply { schemas.forEach { save(it) } }
    val profileStore = InMemoryProfileStore().apply { profiles.forEach { save(it) } }
    val diffStore = InMemoryDiffStore().apply { diffs.forEach { save(it) } }
    val connectionStore = InMemoryConnectionReferenceStore().apply { connections.forEach { save(it) } }
    return ResourceStores(
        jobStore = jobStore,
        artifactStore = artifactStore,
        schemaStore = schemaStore,
        profileStore = profileStore,
        diffStore = diffStore,
        connectionStore = connectionStore,
    )
}

private fun jsonRpcErrorOf(throwable: Throwable): ResponseErrorException =
    ((throwable as ExecutionException).cause as ResponseErrorException)

private fun parseTextContent(result: ReadResourceResult): Map<String, Any?> {
    result.contents.size shouldBe 1
    val slice = result.contents.single()
    slice.mimeType shouldBe "application/json"
    slice.blob shouldBe null
    @Suppress("UNCHECKED_CAST")
    return Gson().fromJson(slice.text, Map::class.java) as Map<String, Any?>
}

class McpServiceImplResourcesReadTest : FunSpec({

    test("resources/read returns the projected content for an own OWNER-visibility job") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            resourceStores = stores(jobs = listOf(jobRecord())),
        )
        val result = sut.resourcesRead(
            ReadResourceParams(uri = "dmigrate://tenants/acme/jobs/job-1"),
        ).get()
        result.contents.single().uri shouldBe "dmigrate://tenants/acme/jobs/job-1"
        val body = parseTextContent(result)
        body["jobId"] shouldBe "job-1"
        body["operation"] shouldBe "schema_generate"
        body["status"] shouldBe "SUCCEEDED"
        body["visibility"] shouldBe "OWNER"
        body["tenantId"] shouldBe "acme"
    }

    test("resources/read returns the projected content for an own artifact") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            resourceStores = stores(artifacts = listOf(artifactRecord())),
        )
        val result = sut.resourcesRead(
            ReadResourceParams(uri = "dmigrate://tenants/acme/artifacts/art-1"),
        ).get()
        val body = parseTextContent(result)
        body["artifactId"] shouldBe "art-1"
        body["filename"] shouldBe "schema.sql"
        body["kind"] shouldBe "SCHEMA"
        // sha256 surfaces — it is a content hash, not a secret.
        body["sha256"] shouldBe "deadbeef"
    }

    test("resources/read on a connection projects without credentialRef/providerRef") {
        val withCredentials = ConnectionReference(
            connectionId = "conn-1",
            tenantId = TENANT,
            displayName = "Local DB",
            dialectId = "postgresql",
            sensitivity = ConnectionSensitivity.NON_PRODUCTION,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.CONNECTIONS, "conn-1"),
            credentialRef = "vault://acme/db/main",
            providerRef = "providers://aws-secrets-manager",
        )
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            resourceStores = stores(connections = listOf(withCredentials)),
        )
        val result = sut.resourcesRead(
            ReadResourceParams(uri = "dmigrate://tenants/acme/connections/conn-1"),
        ).get()
        val body = parseTextContent(result)
        body.containsKey("dialectId") shouldBe true
        body.containsKey("sensitivity") shouldBe true
        // §6.9 secrets-free contract — the projection MUST drop both
        // refs even when the source record carries them.
        body.containsKey("credentialRef") shouldBe false
        body.containsKey("providerRef") shouldBe false
        // Defense-in-depth: scan the serialized text for both the
        // secret values themselves AND the source field names. Value
        // scanning catches a field rename, name scanning catches a
        // future provider that emits a different scheme prefix.
        val raw = result.contents.single().text!!
        check(!raw.contains("vault://")) { "credentialRef value leaked into resources/read body" }
        check(!raw.contains("providers://")) { "providerRef value leaked into resources/read body" }
        check(!raw.contains("credentialRef")) { "credentialRef field name leaked" }
        check(!raw.contains("providerRef")) { "providerRef field name leaked" }
    }

    test("resources/read for a schema/profile/diff index entry returns its metadata") {
        val schema = SchemaIndexEntry(
            schemaId = "schema-1",
            tenantId = TENANT,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, "schema-1"),
            artifactRef = "dmigrate://tenants/acme/artifacts/art-1",
            displayName = "users.sql",
            createdAt = Instant.parse("2026-02-01T00:00:00Z"),
            expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
        )
        val profile = ProfileIndexEntry(
            profileId = "profile-1",
            tenantId = TENANT,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.PROFILES, "profile-1"),
            artifactRef = "dmigrate://tenants/acme/artifacts/art-2",
            displayName = "Q1 profile",
            createdAt = Instant.parse("2026-02-02T00:00:00Z"),
            expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
        )
        val diff = DiffIndexEntry(
            diffId = "diff-1",
            tenantId = TENANT,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.DIFFS, "diff-1"),
            artifactRef = "dmigrate://tenants/acme/artifacts/art-3",
            sourceRef = "dmigrate://tenants/acme/schemas/schema-0",
            targetRef = "dmigrate://tenants/acme/schemas/schema-1",
            displayName = "schema-0 → schema-1",
            createdAt = Instant.parse("2026-02-03T00:00:00Z"),
            expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
        )
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            resourceStores = stores(schemas = listOf(schema), profiles = listOf(profile), diffs = listOf(diff)),
        )

        parseTextContent(
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/acme/schemas/schema-1")).get(),
        )["schemaId"] shouldBe "schema-1"
        parseTextContent(
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/acme/profiles/profile-1")).get(),
        )["profileId"] shouldBe "profile-1"
        parseTextContent(
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/acme/diffs/diff-1")).get(),
        )["diffId"] shouldBe "diff-1"
    }

    test("resources/read without bound principal fails with InvalidRequest") {
        val sut = McpServiceImpl(serverVersion = "0.0.0", initialPrincipal = null)
        val ex = shouldThrow<ExecutionException> {
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/acme/jobs/job-1")).get()
        }
        jsonRpcErrorOf(ex).responseError.code shouldBe ResponseErrorCode.InvalidRequest.value
    }

    test("resources/read without dmigrate:read scope fails with InvalidRequest") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(scopes = emptySet()),
        )
        val ex = shouldThrow<ExecutionException> {
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/acme/jobs/job-1")).get()
        }
        val err = jsonRpcErrorOf(ex).responseError
        err.code shouldBe ResponseErrorCode.InvalidRequest.value
        // The scope-error message names the method (not the URI) so
        // operators can debug; this is the documented enforce-scope
        // shape, not a no-oracle leak.
        check(err.message.contains("resources/read")) {
            "scope error message should name the method, was: ${err.message}"
        }
    }

    test("resources/read with missing uri parameter fails with InvalidParams") {
        val sut = McpServiceImpl(serverVersion = "0.0.0", initialPrincipal = principal())
        val ex = shouldThrow<ExecutionException> { sut.resourcesRead(ReadResourceParams(uri = null)).get() }
        jsonRpcErrorOf(ex).responseError.code shouldBe ResponseErrorCode.InvalidParams.value
    }

    test("resources/read with malformed URI fails with constant InvalidParams message (no parse-reason echo)") {
        // Pinning the exact wire message defends against a future
        // refactor that re-introduces the parse reason; varying
        // reasons would let a caller probe URI grammar without ever
        // touching a store.
        val sut = McpServiceImpl(serverVersion = "0.0.0", initialPrincipal = principal())
        val grammarVariants = listOf(
            "https://example.com/resource",
            "dmigrate://tenants/acme/unknown-kind/x",
            "dmigrate://tenants/acme/jobs/has spaces",
            "dmigrate://tenants/!!!/jobs/x",
        )
        grammarVariants.forEach { input ->
            val ex = shouldThrow<ExecutionException> {
                sut.resourcesRead(ReadResourceParams(input)).get()
            }
            val err = jsonRpcErrorOf(ex).responseError
            err.code shouldBe ResponseErrorCode.InvalidParams.value
            err.message shouldBe ResourcesReadHandler.INVALID_URI_MESSAGE
        }
    }

    test("resources/read with foreign tenant outside allowedTenantIds fails with InvalidRequest") {
        // syntactically valid URI, tenant explicitly outside the
        // principal's allowed set → tenant-scope-denied per §5.6.
        // The error surfaces BEFORE any record lookup, so the response
        // never reveals whether `other/jobs/job-x` exists.
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(allowedTenants = setOf(TENANT)),
        )
        val ex = shouldThrow<ExecutionException> {
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/other/jobs/job-x")).get()
        }
        val err = jsonRpcErrorOf(ex).responseError
        err.code shouldBe ResponseErrorCode.InvalidRequest.value
        check(err.message.contains("tenant scope denied")) {
            "tenant-scope-denied error should be classified, was: ${err.message}"
        }
    }

    test("resources/read with tenant in allowedTenantIds (not effectiveTenantId) reads in Phase D") {
        // Plan-D §4.2 / §5.4 broadens the Phase-B contract: tenant
        // addressing checks `allowedTenantIds`, not strict
        // `effectiveTenantId`. A principal whose home tenant differs
        // from the URI tenant may still read records there as long
        // as the URI tenant is in the allowed set — same surface the
        // AP D6 list-tools expose for an explicit `tenantId`
        // argument.
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(
                tenant = TENANT,
                allowedTenants = setOf(TENANT, SECONDARY_TENANT),
            ),
            resourceStores = stores(
                connections = listOf(
                    ConnectionReference(
                        connectionId = "secret-conn",
                        tenantId = SECONDARY_TENANT,
                        displayName = "Secondary",
                        dialectId = "postgresql",
                        sensitivity = ConnectionSensitivity.PRODUCTION,
                        resourceUri = ServerResourceUri(SECONDARY_TENANT, ResourceKind.CONNECTIONS, "secret-conn"),
                    ),
                ),
            ),
        )
        val body = parseTextContent(
            sut.resourcesRead(
                ReadResourceParams("dmigrate://tenants/beta/connections/secret-conn"),
            ).get(),
        )
        body["connectionId"] shouldBe "secret-conn"
        body["tenantId"] shouldBe SECONDARY_TENANT.value
        // Defense-in-depth: the secret-free projection contract still
        // holds across an allowed-non-effective tenant read.
        body.containsKey("credentialRef") shouldBe false
        body.containsKey("providerRef") shouldBe false
    }

    test("resources/read for an unknown id within tenant fails with -32002 Resource not found") {
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            resourceStores = stores(),
        )
        val ex = shouldThrow<ExecutionException> {
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/acme/jobs/missing")).get()
        }
        val err = jsonRpcErrorOf(ex).responseError
        err.code shouldBe ResourcesReadHandler.MCP_RESOURCE_NOT_FOUND_CODE
        err.message shouldBe "Resource not found"
    }

    test("MCP_RESOURCE_NOT_FOUND_CODE is the literal -32002 from the MCP 2025-11-25 spec") {
        // Pin the raw integer (not the const reference) so a future
        // refactor that swaps the constant for the lsp4j enum value
        // — which assigns -32002 to ServerNotInitialized — fails
        // loudly. The wire integer is the only thing MCP clients see
        // and they read it as "Resource not found" per the spec.
        ResourcesReadHandler.MCP_RESOURCE_NOT_FOUND_CODE shouldBe -32002
    }

    test("resources/read for a job owned by a different principal collapses to Resource not found (no-oracle)") {
        // The job exists, but visibility=OWNER and the requester is
        // not the owner → the no-oracle pattern (§5.6) demands the
        // exact same error as "id never existed". A test that asserts
        // the same wire shape for both branches is the only way to
        // prove the oracle is closed.
        val foreign = jobRecord(jobId = "bobs-job", owner = BOB, visibility = JobVisibility.OWNER)
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(id = ALICE),
            resourceStores = stores(jobs = listOf(foreign)),
        )
        val ex = shouldThrow<ExecutionException> {
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/acme/jobs/bobs-job")).get()
        }
        val err = jsonRpcErrorOf(ex).responseError
        err.code shouldBe ResourcesReadHandler.MCP_RESOURCE_NOT_FOUND_CODE
        err.message shouldBe "Resource not found"
    }

    test("resources/read on the upload-sessions kind in an allowed tenant surfaces VALIDATION_ERROR") {
        // Plan-D §5.1 / §10.7: UPLOAD_SESSIONS is not a Phase-D
        // readable kind. The precedence chain (§4.2 stage 3) runs
        // the blocked-kind gate BEFORE any store lookup, so an
        // attacker cannot probe upload-session ids through the
        // not-found timing channel. Phase-B collapsed this into
        // RESOURCE_NOT_FOUND; Phase-D narrows the wire shape so a
        // client gets a precise reason for the kind-rejection while
        // still preserving the no-oracle property (existence is
        // never confirmed because no lookup runs).
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
        )
        val ex = shouldThrow<ExecutionException> {
            sut.resourcesRead(
                ReadResourceParams("dmigrate://tenants/acme/upload-sessions/session-x"),
            ).get()
        }
        val err = jsonRpcErrorOf(ex).responseError
        err.code shouldBe ResponseErrorCode.InvalidParams.value
        @Suppress("UNCHECKED_CAST")
        val data = err.data as Map<String, Any?>
        data["dmigrateCode"] shouldBe "VALIDATION_ERROR"
    }

    test("resources/read on a FAILED job surfaces error.message and progress.phase") {
        // Operators reading resources/read for a FAILED job need the
        // failure reason — same shape job_status_get returns, so the
        // two surfaces stay coherent.
        val failed = JobRecord(
            managedJob = ManagedJob(
                jobId = "job-failed",
                operation = "schema_generate",
                status = JobStatus.FAILED,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:01:00Z"),
                expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
                createdBy = ALICE.value,
                error = JobError(code = "DRIVER_ERROR", message = "connection refused", exitCode = 1),
                progress = JobProgress(phase = "introspect", numericValues = mapOf("tables" to 7L)),
            ),
            tenantId = TENANT,
            ownerPrincipalId = ALICE,
            visibility = JobVisibility.OWNER,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.JOBS, "job-failed"),
        )
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            resourceStores = stores(jobs = listOf(failed)),
        )
        val body = parseTextContent(
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/acme/jobs/job-failed")).get(),
        )
        body["status"] shouldBe "FAILED"
        @Suppress("UNCHECKED_CAST")
        val errMap = body["error"] as Map<String, Any?>
        errMap["code"] shouldBe "DRIVER_ERROR"
        errMap["message"] shouldBe "connection refused"
        @Suppress("UNCHECKED_CAST")
        val progMap = body["progress"] as Map<String, Any?>
        progMap["phase"] shouldBe "introspect"
    }

    test("admin principal can read a TENANT-scoped record across the tenant") {
        // OWNER vs ADMIN: pin that the admin bypass works through
        // resources/read just like through tools/call so an operator
        // playbook touching both surfaces stays consistent.
        val tenantJob = jobRecord(jobId = "tenant-job", owner = BOB, visibility = JobVisibility.TENANT)
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(id = ALICE, isAdmin = true),
            resourceStores = stores(jobs = listOf(tenantJob)),
        )
        val result = sut.resourcesRead(
            ReadResourceParams("dmigrate://tenants/acme/jobs/tenant-job"),
        ).get()
        parseTextContent(result)["jobId"] shouldBe "tenant-job"
    }

    test("resources/read on dmigrate://capabilities returns the capabilities document") {
        // Plan-D §5.1 / §10.7: dmigrate://capabilities is the only
        // tenantless resource Phase D supports. The body must match
        // the `capabilities_list` tool's payload shape (sans
        // `executionMeta`) so a client reading capabilities through
        // either surface gets the same view.
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            capabilitiesProvider = {
                mapOf(
                    "mcpProtocolVersion" to "2025-11-25",
                    "serverName" to "d-migrate",
                    "tools" to emptyList<Map<String, Any?>>(),
                    "scopeTable" to emptyMap<String, List<String>>(),
                    "limits" to mapOf("maxToolResponseBytes" to 65_536),
                )
            },
        )
        val result = sut.resourcesRead(ReadResourceParams("dmigrate://capabilities")).get()
        result.contents.single().uri shouldBe "dmigrate://capabilities"
        val body = parseTextContent(result)
        body["mcpProtocolVersion"] shouldBe "2025-11-25"
        body["serverName"] shouldBe "d-migrate"
        // executionMeta is `capabilities_list` only — Plan-D §5.1
        // pins the static document for resources/read.
        body.containsKey("executionMeta") shouldBe false
    }

    test("resources/read on dmigrate://capabilities with no provider falls back to RESOURCE_NOT_FOUND") {
        // The default empty-map provider models a stale deployment
        // that hasn't wired capabilities. Plan-D's no-oracle contract
        // demands resources/read never returns a half-baked body —
        // an empty document collapses to the standard not-found
        // branch with `dmigrateCode=RESOURCE_NOT_FOUND`.
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
        )
        val ex = shouldThrow<ExecutionException> {
            sut.resourcesRead(ReadResourceParams("dmigrate://capabilities")).get()
        }
        val err = jsonRpcErrorOf(ex).responseError
        err.code shouldBe ResourcesReadHandler.MCP_RESOURCE_NOT_FOUND_CODE
        @Suppress("UNCHECKED_CAST")
        val data = err.data as Map<String, Any?>
        data["dmigrateCode"] shouldBe "RESOURCE_NOT_FOUND"
    }

    test("resources/read rejects unknown request parameters with VALIDATION_ERROR") {
        // Plan-D §5.3 / §10.7: `uri` is the ONLY accepted field.
        // The strict adapter captures the first offender into
        // ReadResourceParams.unknownParameter; the dispatcher rejects
        // BEFORE the URI even parses so a probe like {"chunkId":"x"}
        // can't elicit a parse-grammar oracle. Test the dispatcher
        // surface directly via a constructed params object — the
        // wire-shape adapter is exercised end-to-end through the
        // integration tests landing in AP D11.
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            resourceStores = stores(),
        )
        val rejectedNames = listOf("cursor", "range", "chunkId", "limit")
        for (name in rejectedNames) {
            val ex = shouldThrow<ExecutionException> {
                sut.resourcesRead(
                    ReadResourceParams(
                        uri = "dmigrate://tenants/acme/jobs/job-1",
                        unknownParameter = name,
                    ),
                ).get()
            }
            val err = jsonRpcErrorOf(ex).responseError
            err.code shouldBe ResponseErrorCode.InvalidParams.value
            check(err.message.contains(name)) {
                "rejection message should name the offending field, was: ${err.message}"
            }
            @Suppress("UNCHECKED_CAST")
            val data = err.data as Map<String, Any?>
            data["dmigrateCode"] shouldBe "VALIDATION_ERROR"
        }
    }

    test("oversized schema projection with artifactRef strips to a referral payload") {
        // Plan-D §5.2 / §10.7: a JSON projection whose serialised
        // size exceeds maxInlineResourceContentBytes MUST surface as
        // a referral (`artifactRef` follow-up) rather than an
        // oversized inline body. The schema projection carries a
        // `labels` map under operator control — synthesise one large
        // enough to push the body over a (deliberately small) cap and
        // pin the stripped referral shape.
        val tinyLimits = dev.dmigrate.mcp.server.McpLimitsConfig(
            maxInlineResourceContentBytes = 256,
            maxResourceReadResponseBytes = 1024,
        )
        val largeLabels = (0 until 50).associate { i -> "label-key-$i" to "label-value-$i".repeat(8) }
        val schema = SchemaIndexEntry(
            schemaId = "schema-bloat",
            tenantId = TENANT,
            displayName = "Bloated",
            artifactRef = "art://big",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
            jobRef = null,
            labels = largeLabels,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, "schema-bloat"),
        )
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            resourceStores = stores(schemas = listOf(schema)),
            limitsConfig = tinyLimits,
        )
        val result = sut.resourcesRead(
            ReadResourceParams("dmigrate://tenants/acme/schemas/schema-bloat"),
        ).get()
        val body = parseTextContent(result)
        body["uri"] shouldBe "dmigrate://tenants/acme/schemas/schema-bloat"
        body["tenantId"] shouldBe TENANT.value
        body["artifactRef"] shouldBe "art://big"
        body["inlineLimitExceeded"] shouldBe true
        // Original projection's bloat fields MUST NOT survive into
        // the referral — that's the entire point of stripping.
        body.containsKey("labels") shouldBe false
        body.containsKey("displayName") shouldBe false
    }

    test("oversized capabilities (no artifactRef) surfaces VALIDATION_ERROR") {
        // Plan-D §5.2: an over-cap projection without an artifactRef
        // referral path is a server-side limit-exceeded error. The
        // capabilities document has no backing artefact, so a
        // pathological deployment with too many tools registered
        // surfaces here loudly rather than silently truncating.
        val tinyLimits = dev.dmigrate.mcp.server.McpLimitsConfig(
            maxInlineResourceContentBytes = 64,
            maxResourceReadResponseBytes = 256,
        )
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            limitsConfig = tinyLimits,
            capabilitiesProvider = {
                mapOf(
                    "tools" to (0 until 100).map { mapOf("name" to "tool-$it") },
                    "scopeTable" to mapOf("dmigrate:read" to (0 until 100).map { "method-$it" }),
                )
            },
        )
        val ex = shouldThrow<ExecutionException> {
            sut.resourcesRead(ReadResourceParams("dmigrate://capabilities")).get()
        }
        val err = jsonRpcErrorOf(ex).responseError
        err.code shouldBe ResponseErrorCode.InvalidParams.value
        @Suppress("UNCHECKED_CAST")
        val data = err.data as Map<String, Any?>
        data["dmigrateCode"] shouldBe "VALIDATION_ERROR"
        check(err.message.contains("maxInlineResourceContentBytes")) {
            "cap-exceeded message should reference the cap, was: ${err.message}"
        }
    }

    test("a projection just under the inline cap stays inline (no referral wrapping)") {
        // Boundary check: a body that fits MUST NOT be wrapped in
        // the referral envelope. Pin that the inline-vs-referral
        // discriminator is strictly size-driven, not "always strip".
        val schema = SchemaIndexEntry(
            schemaId = "schema-small",
            tenantId = TENANT,
            displayName = "Small",
            artifactRef = "art://small",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
            jobRef = null,
            labels = mapOf("k" to "v"),
            resourceUri = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, "schema-small"),
        )
        // Default limits are 49152 bytes — way bigger than the
        // small projection.
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(),
            resourceStores = stores(schemas = listOf(schema)),
        )
        val body = parseTextContent(
            sut.resourcesRead(
                ReadResourceParams("dmigrate://tenants/acme/schemas/schema-small"),
            ).get(),
        )
        body["schemaId"] shouldBe "schema-small"
        body["displayName"] shouldBe "Small"
        body.containsKey("inlineLimitExceeded") shouldBe false
    }

    test("error envelopes carry error.data.dmigrateCode for every Phase-D error class") {
        // Plan-D §5.4 pins three stable codes on the wire:
        //   VALIDATION_ERROR     — URI grammar / blocked kind
        //   TENANT_SCOPE_DENIED  — tenant outside allowedTenantIds
        //   RESOURCE_NOT_FOUND   — visible-but-absent / not-readable
        // The numeric JSON-RPC code is the transport-level
        // discriminator; the d-migrate code is the stable contract
        // surface clients branch on.
        val sut = McpServiceImpl(
            serverVersion = "0.0.0",
            initialPrincipal = principal(allowedTenants = setOf(TENANT)),
            resourceStores = stores(),
        )
        // VALIDATION_ERROR: malformed URI
        val malformed = shouldThrow<ExecutionException> {
            sut.resourcesRead(ReadResourceParams("not-a-uri")).get()
        }
        @Suppress("UNCHECKED_CAST")
        ((jsonRpcErrorOf(malformed).responseError.data) as Map<String, Any?>)["dmigrateCode"] shouldBe
            "VALIDATION_ERROR"

        // TENANT_SCOPE_DENIED: tenant outside allowedTenantIds
        val foreign = shouldThrow<ExecutionException> {
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/never/jobs/x")).get()
        }
        @Suppress("UNCHECKED_CAST")
        ((jsonRpcErrorOf(foreign).responseError.data) as Map<String, Any?>)["dmigrateCode"] shouldBe
            "TENANT_SCOPE_DENIED"

        // RESOURCE_NOT_FOUND: allowed tenant, no record
        val absent = shouldThrow<ExecutionException> {
            sut.resourcesRead(ReadResourceParams("dmigrate://tenants/acme/jobs/missing")).get()
        }
        @Suppress("UNCHECKED_CAST")
        ((jsonRpcErrorOf(absent).responseError.data) as Map<String, Any?>)["dmigrateCode"] shouldBe
            "RESOURCE_NOT_FOUND"
    }
})
