package dev.dmigrate.format.overlay

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayCanonicalJson
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayConversionReversibility
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDataRisk
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.OverlayText
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.diff.migration.overlay.UsingExpressionOverlayEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayBinding

class MigrationOverlayJsonCodecTest : FunSpec({

    val codec = MigrationOverlayJsonCodec()

    test("reads canonical migration overlay JSON") {
        val overlay = signedOverlay()
        val encoded = MigrationOverlayCanonicalJson.encode(overlay)

        codec.read(encoded.byteInputStream()) shouldBe overlay
    }

    test("reads unsigned overlays so validation can report missing hash") {
        val encoded = MigrationOverlayCanonicalJson.encode(signedOverlay())
            .replace(Regex(",\n  \"overlayHash\": \"[^\"]+\""), "")

        codec.read(encoded.byteInputStream()).overlayHash shouldBe null
    }

    test("writes canonical migration overlay JSON") {
        val overlay = signedOverlay()
        val out = ByteArrayOutputStream()

        codec.write(out, overlay)

        out.toString(Charsets.UTF_8) shouldBe MigrationOverlayCanonicalJson.encode(overlay)
    }

    test("F.4 reads rename mapping structure fingerprints") {
        val overlay = signedRenameOverlay()
        val encoded = MigrationOverlayCanonicalJson.encode(overlay)

        codec.read(encoded.byteInputStream()) shouldBe overlay
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

    // ── Bindung aus der Draht-Form ───────────────────────────────
    //
    // Welche Bindung ein Dokument traegt, sagen die vorhandenen Felder — nicht
    // die Formatversion. Ein Uebergang steht flach, wie seit jeher; eine
    // Darstellung bringt `schemaFingerprint` mit.

    test("a flat document reads as a transition") {
        val overlay = codec.read(rawOverlay(BINDING_FLAT, MigrationOverlay.FORMAT_VERSION_V1).byteInputStream())
        overlay.binding shouldBe MigrationOverlayBinding.Transition("src-fp", "dst-fp")
    }

    test("a schemaFingerprint reads as a representation") {
        val overlay = codec.read(
            rawOverlay(BINDING_SCHEMA, MigrationOverlay.FORMAT_VERSION_V2).byteInputStream(),
        )
        overlay.binding shouldBe MigrationOverlayBinding.Representation("schema-fp")
    }

    test("both bindings at once is a contradiction, not a preference") {
        val ex = shouldThrow<MigrationOverlayJsonDecodeException> {
            codec.read(
                rawOverlay(BINDING_FLAT + ",\n  " + BINDING_SCHEMA, MigrationOverlay.FORMAT_VERSION_V2)
                    .byteInputStream(),
            )
        }
        ex.code shouldBe MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH
    }

    test("neither binding is a missing field, named as such") {
        val ex = shouldThrow<MigrationOverlayJsonDecodeException> {
            codec.read(rawOverlay(NO_BINDING, MigrationOverlay.FORMAT_VERSION_V2).byteInputStream())
        }
        ex.code shouldBe MigrationOverlayDiagnostics.REQUIRED_FIELD_MISSING
        ex.path shouldBe "$.sourceFingerprint"
    }

    test("a representation binding in a v1 document is rejected at read time") {
        // Diese Formatversion kennt die Bindung nicht — sie kann sie also auch
        // nicht meinen.
        val ex = shouldThrow<MigrationOverlayJsonDecodeException> {
            codec.read(rawOverlay(BINDING_SCHEMA, MigrationOverlay.FORMAT_VERSION_V1).byteInputStream())
        }
        ex.code shouldBe MigrationOverlayDiagnostics.UNKNOWN_FORMAT_VERSION
        ex.path shouldBe "$.schemaFingerprint"
    }
})

private const val BINDING_FLAT = "\"sourceFingerprint\": \"src-fp\",\n  \"targetFingerprint\": \"dst-fp\""
private const val BINDING_SCHEMA = "\"schemaFingerprint\": \"schema-fp\""
private const val NO_BINDING = ""

/**
 * Ein Dokument mit frei gewaehlten Bindungsfeldern — der Hash ist hier nicht
 * die Frage, sondern was der Leser aus den vorhandenen Feldern macht.
 */
private fun rawOverlay(bindingFields: String, formatVersion: String): String {
    val binding = if (bindingFields.isBlank()) "" else "  $bindingFields,\n"
    return "{\n" +
        "  \"formatVersion\": \"$formatVersion\",\n" +
        "  \"overlayKind\": \"rename-mapping\",\n" +
        binding +
        "  \"dialect\": \"postgresql\",\n" +
        "  \"entries\": [],\n" +
        "  \"createdAt\": \"2026-09-08T10:00:00Z\",\n" +
        "  \"createdByVersion\": \"d-migrate-test\"\n" +
        "}\n"
}

private fun signedOverlay(): MigrationOverlay =
    MigrationOverlay(
        overlayKind = MigrationOverlayKinds.USING_EXPRESSION,
        binding = MigrationOverlayBinding.Transition("src-fp", "dst-fp"),
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

private fun signedRenameOverlay(): MigrationOverlay =
    MigrationOverlay(
        overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
        binding = MigrationOverlayBinding.Transition("src-fp", "dst-fp"),
        dialect = "postgresql",
        entries = listOf(
            RenameMappingOverlayEntry(
                id = "rename-users",
                objectType = "table",
                fromName = "app_user",
                toName = "users",
                fromStructureFingerprint = "from-struct-fp",
                toStructureFingerprint = "to-struct-fp",
            ),
        ),
        createdAt = "2026-05-12T10:15:30Z",
        createdByVersion = "d-migrate-test",
    ).withComputedHash()
