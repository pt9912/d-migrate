package dev.dmigrate.server.core.resource

import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * AP D1 (`ImpPlan-0.9.6-D.md` §10.1) coverage:
 *  - all eight readable resource families parse to the right ADT
 *    variant
 *  - chunk URI does not collapse into a TenantResourceUri with a
 *    slash-bearing id
 *  - `dmigrate://capabilities` is the only valid tenantless URI
 *  - all other tenantless URIs are rejected
 *  - upload-session URI parses but is callers' responsibility to
 *    classify as Phase-D-blocked (resolver layer in AP D2)
 */
class McpResourceUriTest : FunSpec({

    test("parses tenant URIs for every readable resource kind") {
        val cases = listOf(
            "dmigrate://tenants/acme/jobs/job-1" to ResourceKind.JOBS,
            "dmigrate://tenants/acme/artifacts/art-1" to ResourceKind.ARTIFACTS,
            "dmigrate://tenants/acme/schemas/schema-1" to ResourceKind.SCHEMAS,
            "dmigrate://tenants/acme/profiles/profile-1" to ResourceKind.PROFILES,
            "dmigrate://tenants/acme/diffs/diff-1" to ResourceKind.DIFFS,
            "dmigrate://tenants/acme/connections/conn-1" to ResourceKind.CONNECTIONS,
        )
        for ((input, kind) in cases) {
            val parsed = McpResourceUri.parse(input)
            parsed.shouldBeInstanceOf<McpResourceUriParseResult.Valid>()
            val uri = parsed.uri
            uri.shouldBeInstanceOf<TenantResourceUri>()
            uri.kind shouldBe kind
        }
    }

    test("upload-sessions URI parses (but is Phase-D-blocked at resolver layer)") {
        // §10.1: "Upload-Session-URI bleibt parsebar, aber nicht
        // listbar/template-visible in Phase D". The parser MUST
        // succeed; classification as `VALIDATION_ERROR` lives at
        // the AP-D2 resolver, not here.
        val parsed = McpResourceUri.parse("dmigrate://tenants/acme/upload-sessions/upl-1")
        parsed.shouldBeInstanceOf<McpResourceUriParseResult.Valid>()
        parsed.uri.shouldBeInstanceOf<TenantResourceUri>()
        (parsed.uri as TenantResourceUri).kind shouldBe ResourceKind.UPLOAD_SESSIONS
    }

    test("nested chunk URI parses as ArtifactChunkResourceUri, not as TenantResourceUri with slashed id") {
        // The chunk shape has FIVE segments under the tenant prefix
        // (tenantId, "artifacts", artifactId, "chunks", chunkId).
        // Treating the four trailing segments as one slash-bearing
        // id would silently match the segment-pattern check on the
        // first, and pass — the parser MUST recognise the chunk
        // shape explicitly so resolvers don't dispatch a chunk URI
        // through the artifact-record resolver.
        val parsed = McpResourceUri.parse("dmigrate://tenants/acme/artifacts/art-1/chunks/chunk-3")
        parsed.shouldBeInstanceOf<McpResourceUriParseResult.Valid>()
        val uri = parsed.uri
        uri.shouldBeInstanceOf<ArtifactChunkResourceUri>()
        uri.tenantId shouldBe TenantId("acme")
        uri.artifactId shouldBe "art-1"
        uri.chunkId shouldBe "chunk-3"
    }

    test("dmigrate://capabilities parses as the singleton tenantless capability URI") {
        val parsed = McpResourceUri.parse("dmigrate://capabilities")
        parsed.shouldBeInstanceOf<McpResourceUriParseResult.Valid>()
        parsed.uri shouldBe GlobalCapabilitiesResourceUri
    }

    test("other tenantless URIs are rejected") {
        // §10.1: "andere tenantlose URIs liefern VALIDATION_ERROR"
        val tenantless = listOf(
            "dmigrate://everything",
            "dmigrate://capabilities/extra",
            "dmigrate://tenants",
            "dmigrate://tenants/",
        )
        for (input in tenantless) {
            val parsed = McpResourceUri.parse(input)
            parsed.shouldBeInstanceOf<McpResourceUriParseResult.Invalid>()
        }
    }

    test("rejects missing scheme prefix") {
        val parsed = McpResourceUri.parse("https://tenants/acme/jobs/job-1")
        parsed.shouldBeInstanceOf<McpResourceUriParseResult.Invalid>()
        parsed.reason shouldContain "scheme"
    }

    test("rejects too-few-segments tenant URI") {
        val parsed = McpResourceUri.parse("dmigrate://tenants/acme/jobs")
        parsed.shouldBeInstanceOf<McpResourceUriParseResult.Invalid>()
    }

    test("rejects too-many-segments tenant URI that is not the chunk shape") {
        // 4 segments under the prefix is neither a tenant resource
        // (3) nor a chunk URI (5) — invalid.
        val parsed = McpResourceUri.parse("dmigrate://tenants/acme/jobs/j1/extra")
        parsed.shouldBeInstanceOf<McpResourceUriParseResult.Invalid>()
    }

    test("rejects 5-segment URI whose third segment is not `artifacts`") {
        val parsed = McpResourceUri.parse("dmigrate://tenants/acme/schemas/s1/chunks/c1")
        parsed.shouldBeInstanceOf<McpResourceUriParseResult.Invalid>()
        parsed.reason shouldContain "artifacts"
    }

    test("rejects 5-segment URI whose fourth segment is not `chunks`") {
        val parsed = McpResourceUri.parse("dmigrate://tenants/acme/artifacts/art-1/parts/p1")
        parsed.shouldBeInstanceOf<McpResourceUriParseResult.Invalid>()
        parsed.reason shouldContain "chunks"
    }

    test("rejects unknown tenant-resource kind") {
        val parsed = McpResourceUri.parse("dmigrate://tenants/acme/widgets/w1")
        parsed.shouldBeInstanceOf<McpResourceUriParseResult.Invalid>()
        parsed.reason shouldContain "unknown resource kind"
    }

    test("rejects empty segments anywhere") {
        val empties = listOf(
            "dmigrate://tenants//jobs/j1",
            "dmigrate://tenants/acme/jobs/",
            "dmigrate://tenants/acme//j1",
            "dmigrate://tenants/acme/artifacts//chunks/c1",
            "dmigrate://tenants/acme/artifacts/a1/chunks/",
        )
        for (input in empties) {
            val parsed = McpResourceUri.parse(input)
            parsed.shouldBeInstanceOf<McpResourceUriParseResult.Invalid>()
        }
    }

    test("rejects invalid characters in any segment") {
        val invalids = listOf(
            "dmigrate://tenants/acme!/jobs/j1",
            "dmigrate://tenants/acme/jobs/j!1",
            "dmigrate://tenants/acme/artifacts/a1/chunks/c$1",
        )
        for (input in invalids) {
            val parsed = McpResourceUri.parse(input)
            parsed.shouldBeInstanceOf<McpResourceUriParseResult.Invalid>()
        }
    }

    test("render produces canonical form per variant") {
        val tenant = TenantResourceUri(TenantId("acme"), ResourceKind.SCHEMAS, "s1")
        tenant.render() shouldBe "dmigrate://tenants/acme/schemas/s1"

        val chunk = ArtifactChunkResourceUri(TenantId("acme"), "art-1", "chunk-3")
        chunk.render() shouldBe "dmigrate://tenants/acme/artifacts/art-1/chunks/chunk-3"

        GlobalCapabilitiesResourceUri.render() shouldBe "dmigrate://capabilities"
    }

    test("parse + render round-trip preserves the shape for every variant") {
        val cases = listOf<McpResourceUri>(
            TenantResourceUri(TenantId("acme"), ResourceKind.JOBS, "job_42"),
            TenantResourceUri(TenantId("acme-eu"), ResourceKind.UPLOAD_SESSIONS, "u_1"),
            ArtifactChunkResourceUri(TenantId("acme"), "art-001", "chunk-7"),
            GlobalCapabilitiesResourceUri,
        )
        for (original in cases) {
            val parsed = McpResourceUri.parse(original.render())
            parsed.shouldBeInstanceOf<McpResourceUriParseResult.Valid>()
            parsed.uri shouldBe original
        }
    }

    test("legacy bridge: TenantResourceUri.toLegacy / fromLegacy preserve the data-class shape") {
        // AP D1 keeps the legacy ServerResourceUri data class for
        // Phase-B/-C call sites; the AP-D2 migration funnels through
        // these bridge helpers. Round-trip pins they stay in sync.
        val legacy = ServerResourceUri(TenantId("acme"), ResourceKind.PROFILES, "profile-1")
        val migrated = TenantResourceUri.fromLegacy(legacy)
        migrated.tenantId shouldBe legacy.tenantId
        migrated.kind shouldBe legacy.kind
        migrated.id shouldBe legacy.id
        migrated.toLegacy() shouldBe legacy
    }
})
