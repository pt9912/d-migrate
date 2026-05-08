package dev.dmigrate.server.application.fingerprint

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch

class PayloadFingerprintServiceTest : FunSpec({

    val service = FingerprintFixtures.service()

    test("fingerprint is a 64-char lowercase hex string") {
        val fp = service.fingerprint(
            scope = FingerprintScope.START_TOOL,
            payload = JsonValue.Obj.EMPTY,
            bind = FingerprintFixtures.bind(),
        )
        fp shouldHaveLength 64
        fp shouldMatch Regex("[0-9a-f]{64}")
    }

    test("identical payloads produce identical fingerprints") {
        val payload = JsonValue.obj("table" to JsonValue.Str("orders"))
        val a = service.fingerprint(
            FingerprintScope.START_TOOL,
            payload,
            FingerprintFixtures.bind(),
        )
        val b = service.fingerprint(
            FingerprintScope.START_TOOL,
            payload,
            FingerprintFixtures.bind(),
        )
        a shouldBe b
    }

    test("payload field order is irrelevant") {
        val a = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj(
                "a" to JsonValue.Num(1),
                "b" to JsonValue.Num(2),
            ),
            FingerprintFixtures.bind(),
        )
        val b = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj(
                "b" to JsonValue.Num(2),
                "a" to JsonValue.Num(1),
            ),
            FingerprintFixtures.bind(),
        )
        a shouldBe b
    }

    test("array order changes the fingerprint") {
        val a = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj("tables" to JsonValue.arr(JsonValue.Str("a"), JsonValue.Str("b"))),
            FingerprintFixtures.bind(),
        )
        val b = service.fingerprint(
            FingerprintScope.START_TOOL,
            JsonValue.obj("tables" to JsonValue.arr(JsonValue.Str("b"), JsonValue.Str("a"))),
            FingerprintFixtures.bind(),
        )
        (a == b) shouldBe false
    }

    test("scope difference yields different fingerprints") {
        val payload = JsonValue.obj("x" to JsonValue.Num(1))
        val start = service.fingerprint(FingerprintScope.START_TOOL, payload, FingerprintFixtures.bind())
        val sync = service.fingerprint(FingerprintScope.SYNC_TOOL, payload, FingerprintFixtures.bind())
        (start == sync) shouldBe false
    }

    test("FingerprintScope wireValue is the only string in _bind.scope") {
        // Pins the wire form so a future enum rename would surface here
        // instead of silently changing every persisted fingerprint.
        FingerprintScope.START_TOOL.wireValue shouldBe "start_tool"
        FingerprintScope.SYNC_TOOL.wireValue shouldBe "sync_tool"
        FingerprintScope.UPLOAD_INIT.wireValue shouldBe "upload_init"
    }

    test("UPLOAD_INIT extras enter _bind and change the fingerprint") {
        val payload = JsonValue.obj("placeholder" to JsonValue.Str("p"))
        val withoutExtras = service.fingerprint(
            FingerprintScope.UPLOAD_INIT,
            payload,
            FingerprintFixtures.bind(),
        )
        val withExtras = service.fingerprint(
            FingerprintScope.UPLOAD_INIT,
            payload,
            FingerprintFixtures.bind(extras = mapOf(
                "artifactKind" to JsonValue.Str("schema"),
                "mimeType" to JsonValue.Str("application/json"),
                "sizeBytes" to JsonValue.Num(1024),
                "checksumSha256" to JsonValue.Str("a".repeat(64)),
                "uploadIntent" to JsonValue.Str("schema_staging"),
            )),
        )
        (withoutExtras == withExtras) shouldBe false
    }
})
