package dev.dmigrate.server.application.fingerprint

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Codifies §4.5/§14.6: the upload-session fingerprint binds session
 * metadata via [BindContext.extras]; segment bytes are hashed
 * independently via `segmentSha256` and never reach the fingerprint
 * service. Callers must not push segment-level fields through the
 * payload — the surface for what binds the session lives in
 * [BindContext.extras].
 */
class UploadSessionFingerprintVsSegmentTest : FunSpec({

    val service = FingerprintFixtures.service()

    val sessionExtras = mapOf(
        "artifactKind" to JsonValue.Str("schema"),
        "mimeType" to JsonValue.Str("application/json"),
        "sizeBytes" to JsonValue.Num(8_000),
        "checksumSha256" to JsonValue.Str("a".repeat(64)),
        "uploadIntent" to JsonValue.Str("schema_staging"),
    )

    test("session fingerprint is stable across calls when only sessionExtras drive _bind") {
        val a = service.fingerprint(
            FingerprintScope.UPLOAD_INIT,
            JsonValue.Obj.EMPTY,
            FingerprintFixtures.bind(extras = sessionExtras),
        )
        val b = service.fingerprint(
            FingerprintScope.UPLOAD_INIT,
            JsonValue.Obj.EMPTY,
            FingerprintFixtures.bind(extras = sessionExtras),
        )
        a shouldBe b
    }

    test("changing one session metadata field changes the fingerprint") {
        val baseline = service.fingerprint(
            FingerprintScope.UPLOAD_INIT,
            JsonValue.Obj.EMPTY,
            FingerprintFixtures.bind(extras = sessionExtras),
        )
        val changed = service.fingerprint(
            FingerprintScope.UPLOAD_INIT,
            JsonValue.Obj.EMPTY,
            FingerprintFixtures.bind(
                extras = sessionExtras + ("sizeBytes" to JsonValue.Num(9_000)),
            ),
        )
        (baseline == changed) shouldBe false
    }
})
