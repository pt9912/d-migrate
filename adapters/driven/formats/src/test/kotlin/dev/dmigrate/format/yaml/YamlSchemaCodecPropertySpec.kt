package dev.dmigrate.format.yaml

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.schemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.io.ByteArrayOutputStream

/**
 * Property-Based Testing für [YamlSchemaCodec] (LN-046, ADR 0029, Phase C) —
 * die spec-wörtliche „Testfall-Generierung für Schema-Parsing".
 *
 * Round-Trip-Orakel ist [MigrationFingerprint], nicht `==`: der Fingerprint
 * kanonisiert (sortiert Map-Keys, ignoriert `ordinal`/Reporting-Metadaten), so
 * dass die Invariante echten Datenverlust fängt, aber nicht an belangloser
 * Serialisierungs-Normalisierung falsch-rot wird.
 */
class YamlSchemaCodecPropertySpec : FunSpec({

    val codec = YamlSchemaCodec()

    test("read wirft nie eine NullPointerException auf beliebigem Text (nur Domänen-/Parse-Fehler)") {
        checkAll(Arb.string(0..40)) { text ->
            val thrown = runCatching { codec.read(text.byteInputStream()) }.exceptionOrNull()
            (thrown is NullPointerException) shouldBe false
        }
    }

    test("write→read erhält den kanonischen Fingerprint (semantischer Round-Trip)") {
        checkAll(Arb.schemaDefinition()) { schema ->
            val out = ByteArrayOutputStream()
            codec.write(out, schema)
            val reparsed = codec.read(out.toByteArray().inputStream())
            MigrationFingerprint.compute(reparsed) shouldBe MigrationFingerprint.compute(schema)
        }
    }
})
