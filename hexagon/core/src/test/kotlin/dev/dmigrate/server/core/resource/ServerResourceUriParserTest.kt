package dev.dmigrate.server.core.resource

import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class ServerResourceUriParserTest : FunSpec({

    test("parses valid job URI") {
        val result = ServerResourceUri.parse("dmigrate://tenants/acme/jobs/job_123")
        result.shouldBeInstanceOf<ResourceUriParseResult.Valid>()
        result.uri shouldBe ServerResourceUri(
            tenantId = TenantId("acme"),
            kind = ResourceKind.JOBS,
            id = "job_123",
        )
    }

    test("parses upload-sessions kebab segment") {
        val result = ServerResourceUri.parse("dmigrate://tenants/acme/upload-sessions/u1")
        result.shouldBeInstanceOf<ResourceUriParseResult.Valid>()
        result.uri.kind shouldBe ResourceKind.UPLOAD_SESSIONS
    }

    test("parses artifact URI with hyphens and underscores") {
        val result = ServerResourceUri.parse("dmigrate://tenants/acme-eu/artifacts/art_01-AB")
        result.shouldBeInstanceOf<ResourceUriParseResult.Valid>()
        result.uri.tenantId shouldBe TenantId("acme-eu")
        result.uri.id shouldBe "art_01-AB"
    }

    test("rejects missing scheme prefix") {
        val result = ServerResourceUri.parse("https://tenants/acme/jobs/job_123")
        result.shouldBeInstanceOf<ResourceUriParseResult.Invalid>()
        result.reason shouldContain "scheme"
    }

    test("rejects malformed path with too few segments") {
        val result = ServerResourceUri.parse("dmigrate://tenants/acme/jobs")
        result.shouldBeInstanceOf<ResourceUriParseResult.Invalid>()
    }

    test("rejects malformed path with too many segments") {
        val result = ServerResourceUri.parse("dmigrate://tenants/acme/jobs/job_1/extra")
        result.shouldBeInstanceOf<ResourceUriParseResult.Invalid>()
    }

    test("rejects unknown resource kind") {
        val result = ServerResourceUri.parse("dmigrate://tenants/acme/widgets/w1")
        result.shouldBeInstanceOf<ResourceUriParseResult.Invalid>()
        result.reason shouldContain "unknown resource kind"
    }

    test("rejects empty tenantId") {
        val result = ServerResourceUri.parse("dmigrate://tenants//jobs/job_1")
        result.shouldBeInstanceOf<ResourceUriParseResult.Invalid>()
    }

    test("rejects tenantId with invalid characters") {
        val result = ServerResourceUri.parse("dmigrate://tenants/acme!/jobs/job_1")
        result.shouldBeInstanceOf<ResourceUriParseResult.Invalid>()
        result.reason shouldContain "tenantId"
    }

    test("rejects id with invalid characters") {
        val result = ServerResourceUri.parse("dmigrate://tenants/acme/jobs/job!1")
        result.shouldBeInstanceOf<ResourceUriParseResult.Invalid>()
        result.reason shouldContain "id"
    }

    test("render returns canonical form") {
        val uri = ServerResourceUri(
            tenantId = TenantId("acme"),
            kind = ResourceKind.SCHEMAS,
            id = "s1",
        )
        uri.render() shouldBe "dmigrate://tenants/acme/schemas/s1"
    }

    test("render and parse round-trip") {
        val original = ServerResourceUri(
            tenantId = TenantId("acme"),
            kind = ResourceKind.UPLOAD_SESSIONS,
            id = "u_42",
        )
        val parsed = ServerResourceUri.parse(original.render())
        parsed.shouldBeInstanceOf<ResourceUriParseResult.Valid>()
        parsed.uri shouldBe original
    }
})
