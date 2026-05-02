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

    override fun deleteExpired(now: Instant): Int = delegate.deleteExpired(now)
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

    test("schemaRef with allowed cross-tenant scope resolves") {
        val multiTenantPrincipal = PRINCIPAL.copy(allowedTenantIds = setOf(ACME, OTHER))
        val foreignUri = ServerResourceUri(OTHER, ResourceKind.SCHEMAS, "s1")
        val store = InMemorySchemaStore().apply {
            save(SCHEMA_ENTRY.copy(tenantId = OTHER, resourceUri = foreignUri))
        }
        val outcome = resolver(store).resolve(
            SchemaSourceInput(schemaRef = foreignUri.render()),
            multiTenantPrincipal,
        )
        outcome.shouldBeInstanceOf<SchemaSource.Reference>().entry.tenantId shouldBe OTHER
    }

    test("schemaRef pointing at the principal's homeTenantId resolves even when not in allowedTenantIds") {
        // Defensive: TenantScopeChecker.isReachable includes home in
        // the allow set, so future code paths that decouple
        // effectiveTenantId from homeTenantId still let principals
        // read their own home tenant's schemas.
        val rebound = PRINCIPAL.copy(
            homeTenantId = ACME,
            effectiveTenantId = OTHER,
            allowedTenantIds = setOf(OTHER),
        )
        val store = InMemorySchemaStore().apply { save(SCHEMA_ENTRY) }
        val outcome = resolver(store).resolve(
            SchemaSourceInput(schemaRef = SCHEMA_URI.render()),
            rebound,
        )
        outcome.shouldBeInstanceOf<SchemaSource.Reference>().entry.tenantId shouldBe ACME
    }

    test("schemaRef for missing schemaId in same tenant throws RESOURCE_NOT_FOUND") {
        val missing = ServerResourceUri(ACME, ResourceKind.SCHEMAS, "does-not-exist").render()
        val ex = shouldThrow<ResourceNotFoundException> {
            resolver().resolve(SchemaSourceInput(schemaRef = missing), PRINCIPAL)
        }
        ex.resourceUri.id shouldBe "does-not-exist"
    }
})
