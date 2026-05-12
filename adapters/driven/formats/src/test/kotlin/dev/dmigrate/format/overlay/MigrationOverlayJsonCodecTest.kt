package dev.dmigrate.format.overlay

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayCanonicalJson
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayConversionReversibility
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDataRisk
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.OverlayText
import dev.dmigrate.core.diff.migration.overlay.UsingExpressionOverlayEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream

class MigrationOverlayJsonCodecTest : FunSpec({

    val codec = MigrationOverlayJsonCodec()

    test("reads canonical migration overlay JSON") {
        val overlay = signedOverlay()
        val encoded = MigrationOverlayCanonicalJson.encode(overlay)

        codec.read(encoded.byteInputStream()) shouldBe overlay
    }

    test("writes canonical migration overlay JSON") {
        val overlay = signedOverlay()
        val out = ByteArrayOutputStream()

        codec.write(out, overlay)

        out.toString(Charsets.UTF_8) shouldBe MigrationOverlayCanonicalJson.encode(overlay)
    }

    test("writer rejects unsigned or stale-hash overlays") {
        val unsigned = signedOverlay().copy(overlayHash = null)
        val stale = signedOverlay().copy(createdByVersion = "changed")

        val missing = shouldThrow<MigrationOverlayJsonEncodeException> {
            codec.write(ByteArrayOutputStream(), unsigned)
        }
        val mismatch = shouldThrow<MigrationOverlayJsonEncodeException> {
            codec.write(ByteArrayOutputStream(), stale)
        }

        missing.code shouldBe MigrationOverlayDiagnostics.HASH_MISSING
        mismatch.code shouldBe MigrationOverlayDiagnostics.HASH_MISMATCH
    }

    test("rejects unknown top-level fields before validation") {
        val encoded = MigrationOverlayCanonicalJson.encode(signedOverlay())
            .replace("\"overlayHash\"", "\"rollback\": {},\n  \"overlayHash\"")

        val ex = shouldThrow<MigrationOverlayJsonDecodeException> {
            codec.read(encoded.byteInputStream())
        }

        ex.code shouldBe MigrationOverlayDiagnostics.UNKNOWN_REQUIRED_FIELD
        ex.path shouldBe "$.rollback"
    }

    test("rejects unknown entry kind before validation") {
        val encoded = MigrationOverlayCanonicalJson.encode(signedOverlay())
            .replace("\"kind\": \"using-expression\"", "\"kind\": \"approve-risk\"")

        val ex = shouldThrow<MigrationOverlayJsonDecodeException> {
            codec.read(encoded.byteInputStream())
        }

        ex.code shouldBe MigrationOverlayDiagnostics.UNKNOWN_ENTRY_KIND
        ex.path shouldBe "$.entries[0].kind"
    }

    test("rejects unknown entry fields before validation") {
        val encoded = MigrationOverlayCanonicalJson.encode(signedOverlay())
            .replace("\"requiredFeatures\": []", "\"risk\": \"manual\", \"requiredFeatures\": []")

        val ex = shouldThrow<MigrationOverlayJsonDecodeException> {
            codec.read(encoded.byteInputStream())
        }

        ex.code shouldBe MigrationOverlayDiagnostics.UNKNOWN_REQUIRED_FIELD
        ex.path shouldBe "$.entries[0].risk"
    }
})

private fun signedOverlay(): MigrationOverlay =
    MigrationOverlay(
        overlayKind = MigrationOverlayKinds.USING_EXPRESSION,
        sourceFingerprint = "src-fp",
        targetFingerprint = "dst-fp",
        dialect = "postgresql",
        entries = listOf(
            UsingExpressionOverlayEntry(
                id = "use-email",
                table = "users",
                column = "email",
                sourceType = "TEXT",
                targetType = "TEXT",
                upUsingExpression = OverlayText("COALESCE(profile->>'email', '')"),
                dataRisk = MigrationOverlayDataRisk.NO_DATA_LOSS_EXPECTED,
                conversionReversibility = MigrationOverlayConversionReversibility.AUTOMATIC,
                expressionSource = "user",
                reviewedByUser = true,
            ),
        ),
        createdAt = "2026-05-12T10:15:30Z",
        createdByVersion = "d-migrate-test",
    ).withComputedHash()
