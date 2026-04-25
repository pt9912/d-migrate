package dev.dmigrate.server.core.resource

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ResourceKindTest : FunSpec({

    test("all kinds map to kebab-case path segments") {
        ResourceKind.JOBS.pathSegment shouldBe "jobs"
        ResourceKind.ARTIFACTS.pathSegment shouldBe "artifacts"
        ResourceKind.SCHEMAS.pathSegment shouldBe "schemas"
        ResourceKind.PROFILES.pathSegment shouldBe "profiles"
        ResourceKind.DIFFS.pathSegment shouldBe "diffs"
        ResourceKind.CONNECTIONS.pathSegment shouldBe "connections"
        ResourceKind.UPLOAD_SESSIONS.pathSegment shouldBe "upload-sessions"
    }

    test("fromPathSegment returns matching kind") {
        ResourceKind.fromPathSegment("jobs") shouldBe ResourceKind.JOBS
        ResourceKind.fromPathSegment("upload-sessions") shouldBe ResourceKind.UPLOAD_SESSIONS
    }

    test("fromPathSegment returns null for unknown segment") {
        ResourceKind.fromPathSegment("unknown") shouldBe null
        ResourceKind.fromPathSegment("Jobs") shouldBe null
    }
})
