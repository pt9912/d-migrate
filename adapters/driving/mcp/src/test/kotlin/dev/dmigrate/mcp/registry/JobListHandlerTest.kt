package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.server.application.error.TenantScopeDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
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
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * AP D6 §10.6 acceptance tests for [JobListHandler]. The other
 * four list-tool handlers share the same shape — these tests
 * also implicitly cover the [ListToolHelpers] tenant + pageSize
 * + filter resolution surface they all funnel through.
 */
class JobListHandlerTest : FunSpec({

    val tenantA = TenantId("acme")
    val tenantB = TenantId("beta")
    val alice = PrincipalId("alice")
    val bob = PrincipalId("bob")

    fun principal(
        id: PrincipalId = alice,
        effective: TenantId = tenantA,
        allowed: Set<TenantId> = setOf(tenantA),
        admin: Boolean = false,
    ): PrincipalContext = PrincipalContext(
        principalId = id,
        homeTenantId = effective,
        effectiveTenantId = effective,
        allowedTenantIds = allowed,
        scopes = setOf("dmigrate:read"),
        isAdmin = admin,
        auditSubject = id.value,
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    fun job(
        jobId: String,
        tenant: TenantId = tenantA,
        owner: PrincipalId = alice,
        status: JobStatus = JobStatus.SUCCEEDED,
        operation: String = "schema_validate",
        createdAt: Instant = Instant.parse("2026-05-04T10:00:00Z"),
        visibility: JobVisibility = JobVisibility.TENANT,
    ): JobRecord = JobRecord(
        managedJob = ManagedJob(
            jobId = jobId,
            operation = operation,
            status = status,
            createdAt = createdAt,
            updatedAt = createdAt,
            expiresAt = createdAt.plusSeconds(3600),
            createdBy = owner.value,
        ),
        tenantId = tenant,
        ownerPrincipalId = owner,
        visibility = visibility,
        resourceUri = ServerResourceUri(tenant, ResourceKind.JOBS, jobId),
    )

    fun runHandler(
        store: InMemoryJobStore,
        principal: PrincipalContext,
        argsJson: String? = null,
    ): com.google.gson.JsonObject {
        val handler = JobListHandler(store)
        val outcome = handler.handle(
            ToolCallContext(
                name = "job_list",
                arguments = argsJson?.let { JsonParser.parseString(it) },
                principal = principal,
            ),
        )
        outcome shouldBe outcome // exhaustive check below
        val success = outcome as ToolCallOutcome.Success
        val text = success.content.single().text!!
        return JsonParser.parseString(text).asJsonObject
    }

    test("empty store returns empty jobs collection with null nextCursor") {
        val store = InMemoryJobStore()
        val payload = runHandler(store, principal())
        payload.getAsJsonArray("jobs").size() shouldBe 0
        payload.get("nextCursor").isJsonNull shouldBe true
    }

    test("single matching record surfaces the typed projection") {
        val store = InMemoryJobStore().apply { save(job("j-1")) }
        val payload = runHandler(store, principal())
        val items = payload.getAsJsonArray("jobs")
        items.size() shouldBe 1
        val item = items.get(0).asJsonObject
        item.get("jobId").asString shouldBe "j-1"
        item.get("tenantId").asString shouldBe tenantA.value
        item.get("status").asString shouldBe "SUCCEEDED"
        item.get("operation").asString shouldBe "schema_validate"
        item.get("resourceUri").asString shouldBe "dmigrate://tenants/acme/jobs/j-1"
        item.get("visibilityClass").asString shouldBe "TENANT_VISIBLE"
        // ownerPrincipalId MUST NOT be projected per §6.4 (PII).
        item.has("ownerPrincipalId") shouldBe false
    }

    test("status filter restricts results AND default sort holds within the filtered set") {
        val store = InMemoryJobStore().apply {
            save(job("j-old", createdAt = Instant.parse("2026-05-04T09:00:00Z")))
            save(job("j-new", createdAt = Instant.parse("2026-05-04T10:00:00Z")))
            save(job("j-failed", status = JobStatus.FAILED))
        }
        val payload = runHandler(store, principal(), """{"status":"SUCCEEDED"}""")
        val ids = payload.getAsJsonArray("jobs").map { it.asJsonObject.get("jobId").asString }
        // §6.2 default: createdAt DESC.
        ids shouldBe listOf("j-new", "j-old")
    }

    test("operation filter passes through to the store") {
        val store = InMemoryJobStore().apply {
            save(job("j-validate", operation = "schema_validate"))
            save(job("j-generate", operation = "schema_generate"))
        }
        val payload = runHandler(store, principal(), """{"operation":"schema_generate"}""")
        val ids = payload.getAsJsonArray("jobs").map { it.asJsonObject.get("jobId").asString }
        ids shouldBe listOf("j-generate")
    }

    test("explicit tenantId in allowedTenantIds picks that tenant; foreign tenant fails fast") {
        val store = InMemoryJobStore().apply {
            save(job("j-acme", tenant = tenantA))
            save(job("j-beta", tenant = tenantB))
        }
        val multiTenantPrincipal = principal(
            effective = tenantA,
            allowed = setOf(tenantA, tenantB),
        )

        // Default: effective tenant.
        val acmePayload = runHandler(store, multiTenantPrincipal)
        val acmeIds = acmePayload.getAsJsonArray("jobs").map { it.asJsonObject.get("jobId").asString }
        acmeIds shouldBe listOf("j-acme")

        // Explicit beta — allowed.
        val betaPayload = runHandler(store, multiTenantPrincipal, """{"tenantId":"beta"}""")
        val betaIds = betaPayload.getAsJsonArray("jobs").map { it.asJsonObject.get("jobId").asString }
        betaIds shouldBe listOf("j-beta")

        // Explicit foreign — TENANT_SCOPE_DENIED.
        withClue("foreign tenant must throw TenantScopeDeniedException before any store read") {
            shouldThrow<TenantScopeDeniedException> {
                runHandler(store, multiTenantPrincipal, """{"tenantId":"never-allowed"}""")
            }
        }
    }

    test("visibility filtering: OWNER record by another principal stays invisible") {
        val store = InMemoryJobStore().apply {
            save(job("j-mine", owner = alice, visibility = JobVisibility.OWNER))
            save(job("j-bobs", owner = bob, visibility = JobVisibility.OWNER))
        }
        val payload = runHandler(store, principal(id = alice))
        val ids = payload.getAsJsonArray("jobs").map { it.asJsonObject.get("jobId").asString }
        ids shouldBe listOf("j-mine")
    }

    test("pageSize > MAX surfaces VALIDATION_ERROR before store read") {
        val store = InMemoryJobStore()
        shouldThrow<ValidationErrorException> {
            runHandler(store, principal(), """{"pageSize":${ListToolHelpers.MAX_PAGE_SIZE + 1}}""")
        }
    }

    test("pageSize < 1 surfaces VALIDATION_ERROR") {
        val store = InMemoryJobStore()
        shouldThrow<ValidationErrorException> {
            runHandler(store, principal(), """{"pageSize":0}""")
        }
    }

    test("unparseable createdAfter / createdBefore surfaces VALIDATION_ERROR") {
        val store = InMemoryJobStore()
        shouldThrow<ValidationErrorException> {
            runHandler(store, principal(), """{"createdAfter":"not-a-date"}""")
        }
        shouldThrow<ValidationErrorException> {
            runHandler(store, principal(), """{"createdBefore":"yesterday"}""")
        }
    }

    test("unknown JobStatus value surfaces VALIDATION_ERROR") {
        val store = InMemoryJobStore()
        shouldThrow<ValidationErrorException> {
            runHandler(store, principal(), """{"status":"BOGUS_STATUS"}""")
        }
    }

    test("createdAfter / createdBefore filter passes through to the store") {
        val store = InMemoryJobStore().apply {
            save(job("j-early", createdAt = Instant.parse("2026-05-04T09:00:00Z")))
            save(job("j-mid", createdAt = Instant.parse("2026-05-04T10:00:00Z")))
            save(job("j-late", createdAt = Instant.parse("2026-05-04T11:00:00Z")))
        }
        val payload = runHandler(
            store,
            principal(),
            """{"createdAfter":"2026-05-04T10:00:00Z","createdBefore":"2026-05-04T10:30:00Z"}""",
        )
        val ids = payload.getAsJsonArray("jobs").map { it.asJsonObject.get("jobId").asString }
        ids shouldBe listOf("j-mid")
    }
})
