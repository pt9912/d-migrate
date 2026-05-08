package dev.dmigrate.mcp.schema

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.TenantScopeDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

private val ACME = TenantId("acme")
private val OTHER = TenantId("other")

private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = ACME,
    effectiveTenantId = ACME,
    allowedTenantIds = setOf(ACME),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private val SCHEMA_URI = ServerResourceUri(ACME, ResourceKind.SCHEMAS, "s1")

private val SCHEMA_ENTRY = SchemaIndexEntry(
    schemaId = "s1",
    tenantId = ACME,
    resourceUri = SCHEMA_URI,
    artifactRef = "art-s1",
    displayName = "test schema",
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    expiresAt = Instant.parse("2027-01-01T00:00:00Z"),
)

private fun resolver(store: SchemaStore = InMemorySchemaStore()): SchemaSourceResolver =
    SchemaSourceResolver(schemaStore = store, limits = McpLimitsConfig())

private fun jsonObj(content: String = "{}"): JsonObject =
    JsonParser.parseString(content).asJsonObject

/**
 * Counting decorator used by the no-oracle test to pin that
 * [SchemaSourceResolver] never reaches the store for foreign tenants.
 * Otherwise differential timing/observation could leak existence.
 */
private class CountingSchemaStore(private val delegate: SchemaStore) : SchemaStore {
    var findByIdCalls: Int = 0
        private set

    override fun save(entry: SchemaIndexEntry): SchemaIndexEntry = delegate.save(entry)

    override fun findById(tenantId: TenantId, schemaId: String): SchemaIndexEntry? {
        findByIdCalls++
        return delegate.findById(tenantId, schemaId)
    }

    override fun list(tenantId: TenantId, page: PageRequest): PageResult<SchemaIndexEntry> =
        delegate.list(tenantId, page)

    override fun list(
        tenantId: TenantId,
        filter: dev.dmigrate.server.ports.SchemaListFilter,
        page: PageRequest,
    ): PageResult<SchemaIndexEntry> = delegate.list(tenantId, filter, page)

    override fun deleteExpired(now: Instant): Int = delegate.deleteExpired(now)

    override fun register(entry: SchemaIndexEntry) = delegate.register(entry)
}

class SchemaSourceResolverTest : FunSpec({

    test("inline schema returns Inline with measured UTF-8 byte size") {
        val schema = jsonObj("""{"name":"orders"}""")
        val outcome = resolver().resolve(SchemaSourceInput(schema = schema), PRINCIPAL)
        val inline = outcome.shouldBeInstanceOf<SchemaSource.Inline>()
        inline.schema shouldBe schema
        inline.byteSize shouldBe schema.toString().toByteArray(Charsets.UTF_8).size
    }

    test("schemaRef returns Reference loaded from SchemaStore") {
        val store = InMemorySchemaStore().apply { save(SCHEMA_ENTRY) }
        val outcome = resolver(store).resolve(
            SchemaSourceInput(schemaRef = SCHEMA_URI.render()),
            PRINCIPAL,
        )
        outcome.shouldBeInstanceOf<SchemaSource.Reference>().entry shouldBe SCHEMA_ENTRY
    }

    test("no source supplied throws VALIDATION_ERROR") {
        val ex = shouldThrow<ValidationErrorException> {
            resolver().resolve(SchemaSourceInput(), PRINCIPAL)
        }
        ex.violations.map { it.field } shouldContain "source"
    }

    test("both inline and schemaRef supplied throws VALIDATION_ERROR") {
        val ex = shouldThrow<ValidationErrorException> {
            resolver().resolve(
                SchemaSourceInput(schema = jsonObj(), schemaRef = SCHEMA_URI.render()),
                PRINCIPAL,
            )
        }
        ex.violations.map { it.field } shouldContain "source"
    }

    test("blank schemaRef counts as no source (not as a reference)") {
        // Defensive: empty/whitespace strings are likely client bugs;
        // surfacing "missing scheme prefix" from the URI parser would
        // hide the real cause.
        shouldThrow<ValidationErrorException> {
            resolver().resolve(SchemaSourceInput(schemaRef = "   "), PRINCIPAL)
        }
    }

    test("JsonNull schema with valid schemaRef resolves the reference (treats null as absent)") {
        // Gson's `null` JSON value is JsonNull.INSTANCE, not Kotlin null.
        // Treating it as "have a source" would deny the schemaRef
        // path for clients that send `{"schema": null, "schemaRef": "..."}`.
        val store = InMemorySchemaStore().apply { save(SCHEMA_ENTRY) }
        val outcome = resolver(store).resolve(
            SchemaSourceInput(schema = JsonNull.INSTANCE, schemaRef = SCHEMA_URI.render()),
            PRINCIPAL,
        )
        outcome.shouldBeInstanceOf<SchemaSource.Reference>()
    }

    test("inline non-object element prefers VALIDATION_ERROR over PAYLOAD_TOO_LARGE even when oversized") {
        // §6.3 acceptance: structural type errors must surface as
        // VALIDATION_ERROR. Letting the size check run first would
        // turn "I sent an array by mistake" into "your payload is too
        // big" — a worse diagnostic.
        val tinyLimits = McpLimitsConfig(maxInlineSchemaBytes = 4)
        val sut = SchemaSourceResolver(InMemorySchemaStore(), tinyLimits)
        val element = JsonParser.parseString("[1, 2, 3, 4, 5, 6, 7, 8, 9]")
        val ex = shouldThrow<ValidationErrorException> {
            sut.resolve(SchemaSourceInput(schema = element), PRINCIPAL)
        }
        ex.violations.map { it.field } shouldContain "schema"
    }

    test("inline schema over maxInlineSchemaBytes throws PAYLOAD_TOO_LARGE") {
        val tinyLimits = McpLimitsConfig(maxInlineSchemaBytes = 16)
        val sut = SchemaSourceResolver(InMemorySchemaStore(), tinyLimits)
        val schema = jsonObj("""{"name":"this object is definitely longer than sixteen bytes"}""")
        val ex = shouldThrow<PayloadTooLargeException> {
            sut.resolve(SchemaSourceInput(schema = schema), PRINCIPAL)
        }
        ex.maxBytes shouldBe 16L
        (ex.actualBytes > 16L) shouldBe true
    }

    test("inline schema exactly at maxInlineSchemaBytes is accepted (boundary)") {
        // `>` not `>=` — pin the off-by-one so tightening the check
        // later breaks this test instead of silently rejecting valid
        // payloads.
        val schema = jsonObj("""{"a":"b"}""")
        val exactLimit = schema.toString().toByteArray(Charsets.UTF_8).size
        val sut = SchemaSourceResolver(InMemorySchemaStore(), McpLimitsConfig(maxInlineSchemaBytes = exactLimit))
        val outcome = sut.resolve(SchemaSourceInput(schema = schema), PRINCIPAL)
        outcome.shouldBeInstanceOf<SchemaSource.Inline>().byteSize shouldBe exactLimit
    }

    test("inline JSON primitive throws VALIDATION_ERROR (not PAYLOAD_TOO_LARGE)") {
        // Coverage gap: the array case proves arrays fail; primitives
        // (string/number/bool) take a different Gson code path. They
        // must surface the same structural-error diagnostic.
        val element = JsonParser.parseString("\"just a string\"")
        val ex = shouldThrow<ValidationErrorException> {
            resolver().resolve(SchemaSourceInput(schema = element), PRINCIPAL)
        }
        ex.violations.map { it.field } shouldContain "schema"
    }

    test("schemaRef with malformed URI throws VALIDATION_ERROR") {
        val ex = shouldThrow<ValidationErrorException> {
            resolver().resolve(SchemaSourceInput(schemaRef = "not-a-uri"), PRINCIPAL)
        }
        ex.violations.map { it.field } shouldContain "schemaRef"
    }

    test("schemaRef pointing at a non-schemas resource throws VALIDATION_ERROR") {
        val ex = shouldThrow<ValidationErrorException> {
            resolver().resolve(
                SchemaSourceInput(schemaRef = "dmigrate://tenants/acme/jobs/j1"),
                PRINCIPAL,
            )
        }
        ex.violations.map { it.reason }.any { "expected schemas" in it } shouldBe true
    }

    test("schemaRef with foreign tenant throws TENANT_SCOPE_DENIED before any store lookup (no-oracle)") {
        // §5.6 no-oracle pins existence-leak prevention: a foreign
        // tenant must NOT touch the store, otherwise differential
        // timing could distinguish "exists" from "absent" through
        // store-side observability (logs, latency, hit counters).
        val foreignUri = ServerResourceUri(OTHER, ResourceKind.SCHEMAS, "s1").render()
        val store = CountingSchemaStore(
            InMemorySchemaStore().apply {
                save(
                    SCHEMA_ENTRY.copy(
                        tenantId = OTHER,
                        resourceUri = ServerResourceUri(OTHER, ResourceKind.SCHEMAS, "s1"),
                    ),
                )
            },
        )
        val ex = shouldThrow<TenantScopeDeniedException> {
            resolver(store).resolve(SchemaSourceInput(schemaRef = foreignUri), PRINCIPAL)
        }
        ex.requestedTenant shouldBe OTHER
        store.findByIdCalls shouldBe 0
    }

    test("schemaRef matches the principal's effectiveTenantId regardless of allowedTenantIds membership") {
        // Tenant readability follows the rest of Phase B/C
        // (JobRecord.isReadableBy, ArtifactRecord.isReadableBy):
        // effectiveTenantId is the read scope. allowedTenantIds is
        // about *granting* an effective tenant at request entry, not
        // about widening reads.
        val rebound = PRINCIPAL.copy(
            homeTenantId = ACME,
            effectiveTenantId = OTHER,
            allowedTenantIds = setOf(ACME, OTHER),
        )
        val foreignUri = ServerResourceUri(OTHER, ResourceKind.SCHEMAS, "s1")
        val store = InMemorySchemaStore().apply {
            save(SCHEMA_ENTRY.copy(tenantId = OTHER, resourceUri = foreignUri))
        }
        val outcome = resolver(store).resolve(
            SchemaSourceInput(schemaRef = foreignUri.render()),
            rebound,
        )
        outcome.shouldBeInstanceOf<SchemaSource.Reference>().entry.tenantId shouldBe OTHER
    }

    test("schemaRef pointing at home tenant is denied when effectiveTenantId differs") {
        // Counterpart to the test above: home/allowed-tenant access
        // does NOT widen read scope. A principal whose effective
        // tenant is OTHER cannot read ACME schemas just because ACME
        // is in homeTenantId or allowedTenantIds.
        val rebound = PRINCIPAL.copy(
            homeTenantId = ACME,
            effectiveTenantId = OTHER,
            allowedTenantIds = setOf(ACME, OTHER),
        )
        val ex = shouldThrow<TenantScopeDeniedException> {
            resolver().resolve(SchemaSourceInput(schemaRef = SCHEMA_URI.render()), rebound)
        }
        ex.requestedTenant shouldBe ACME
    }

    test("AP 6.16: drifted SchemaIndexEntry (tenant/id/uri mismatch) maps to RESOURCE_NOT_FOUND") {
        // Defense-in-depth: a misbehaving SchemaStore that returns
        // a record whose tenantId / schemaId / resourceUri doesn't
        // match the lookup key must NOT be trusted. The handler
        // surfaces RESOURCE_NOT_FOUND (no-oracle) so a caller can't
        // confirm the existence of an entry shadowed by a buggy
        // store.
        val driftingStore = object : SchemaStore by InMemorySchemaStore() {
            override fun findById(tenantId: TenantId, schemaId: String): SchemaIndexEntry? =
                SchemaIndexEntry(
                    schemaId = "wrong-id",
                    tenantId = OTHER, // foreign tenant
                    resourceUri = ServerResourceUri(OTHER, ResourceKind.SCHEMAS, "wrong-id"),
                    artifactRef = "art-x",
                    displayName = "drifted",
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    expiresAt = Instant.parse("2027-01-01T00:00:00Z"),
                )
        }
        val sut = SchemaSourceResolver(driftingStore, McpLimitsConfig())
        shouldThrow<ResourceNotFoundException> {
            sut.resolve(SchemaSourceInput(schemaRef = SCHEMA_URI.render()), PRINCIPAL)
        }
    }

    test("schemaRef for missing schemaId in same tenant throws RESOURCE_NOT_FOUND") {
        val missing = ServerResourceUri(ACME, ResourceKind.SCHEMAS, "does-not-exist").render()
        val ex = shouldThrow<ResourceNotFoundException> {
            resolver().resolve(SchemaSourceInput(schemaRef = missing), PRINCIPAL)
        }
        ex.resourceUri.id shouldBe "does-not-exist"
    }
})
