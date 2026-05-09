package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ReverseMarkerNormalizerTest : FunSpec({

    test("schema without reverse-marker prefix is passed through unchanged (identity)") {
        val schema = SchemaDefinition(name = "MyApp", version = "1.0")
        val out = ReverseMarkerNormalizer.normalize(schema)
        // Identity check — caller should be able to detect "no change".
        (out === schema) shouldBe true
    }

    test("valid reverse-marker schema is normalized to placeholder name+version") {
        val schema = SchemaDefinition(
            name = ReverseScopeCodec.postgresName("acme", "public"),
            version = ReverseScopeCodec.REVERSE_VERSION,
        )
        val out = ReverseMarkerNormalizer.normalize(schema)
        out.name shouldBe ReverseMarkerNormalizer.NORMALIZED_NAME
        out.version shouldBe ReverseMarkerNormalizer.NORMALIZED_VERSION
        // Other fields unchanged.
        out.tables shouldBe schema.tables
    }

    test("isNormalized is true after normalize, false on the input") {
        val schema = SchemaDefinition(
            name = ReverseScopeCodec.mysqlName("acme"),
            version = ReverseScopeCodec.REVERSE_VERSION,
        )
        ReverseMarkerNormalizer.isNormalized(schema) shouldBe false
        val normalized = ReverseMarkerNormalizer.normalize(schema)
        ReverseMarkerNormalizer.isNormalized(normalized) shouldBe true
    }

    test("schema with reverse prefix but wrong version throws IllegalStateException") {
        val schema = SchemaDefinition(
            name = ReverseScopeCodec.sqliteName("main"),
            version = "1.0",  // not REVERSE_VERSION
        )
        val ex = shouldThrow<IllegalStateException> {
            ReverseMarkerNormalizer.normalize(schema)
        }
        ex.message!! shouldContain "uses reserved prefix"
    }

    test("schema with reverse prefix but malformed body throws IllegalStateException") {
        val schema = SchemaDefinition(
            name = "${ReverseScopeCodec.PREFIX}postgresql:not_a_kv_pair",
            version = ReverseScopeCodec.REVERSE_VERSION,
        )
        shouldThrow<IllegalStateException> {
            ReverseMarkerNormalizer.normalize(schema)
        }
    }
})
