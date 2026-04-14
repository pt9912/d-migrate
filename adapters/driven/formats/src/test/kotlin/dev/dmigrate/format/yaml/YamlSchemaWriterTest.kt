package dev.dmigrate.format.yaml

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class YamlSchemaWriterTest : FunSpec({

    val codec = YamlSchemaCodec()

    fun roundTrip(schema: SchemaDefinition): SchemaDefinition {
        val out = ByteArrayOutputStream()
        codec.write(out, schema)
        return codec.read(ByteArrayInputStream(out.toByteArray()))
    }

    fun loadFixture(path: String) =
        codec.read(YamlSchemaWriterTest::class.java.getResourceAsStream("/fixtures/$path")!!)

    // ── Round-Trip Tests ────────────────────────

    test("round-trip minimal fixture") {
        val original = loadFixture("schemas/minimal.yaml")
        val result = roundTrip(original)
        result shouldBe original
    }

    test("round-trip e-commerce fixture") {
        val original = loadFixture("schemas/e-commerce.yaml")
        val result = roundTrip(original)
        result shouldBe original
    }

    test("round-trip full-featured fixture") {
        val original = loadFixture("schemas/full-featured.yaml")
        val result = roundTrip(original)
        result shouldBe original
    }

    test("round-trip canonical-keys fixture") {
        val original = loadFixture("schemas/canonical-keys.yaml")
        val result = roundTrip(original)
        result shouldBe original
    }

    test("round-trip table-metadata fixture") {
        val original = loadFixture("schemas/table-metadata.yaml")
        val result = roundTrip(original)
        result shouldBe original
    }

    test("round-trip spatial fixture") {
        val original = loadFixture("schemas/spatial.yaml")
        val result = roundTrip(original)
        result shouldBe original
    }

    test("round-trip all-types fixture") {
        val original = loadFixture("schemas/all-types.yaml")
        val result = roundTrip(original)
        result shouldBe original
    }

    // ── Determinism ─────────────────────────────

    test("write is deterministic — identical bytes on repeated calls") {
        val schema = loadFixture("schemas/e-commerce.yaml")
        val out1 = ByteArrayOutputStream()
        val out2 = ByteArrayOutputStream()
        codec.write(out1, schema)
        codec.write(out2, schema)
        out1.toByteArray().toList() shouldBe out2.toByteArray().toList()
    }

    // ── Optional field suppression ──────────────

    test("empty optional fields are not written") {
        val schema = SchemaDefinition(name = "Minimal", version = "1.0")
        val out = ByteArrayOutputStream()
        codec.write(out, schema)
        val yaml = out.toString(Charsets.UTF_8)
        yaml shouldNotContain "description"
        yaml shouldNotContain "locale"
        yaml shouldNotContain "custom_types"
        yaml shouldNotContain "tables"
        yaml shouldNotContain "procedures"
        yaml shouldNotContain "functions"
        yaml shouldNotContain "views"
        yaml shouldNotContain "triggers"
        yaml shouldNotContain "sequences"
        // encoding should not appear when it's the default "utf-8"
        yaml shouldNotContain "encoding"
    }

    test("metadata only appears on tables that have it") {
        val schema = SchemaDefinition(
            name = "Test", version = "1.0",
            tables = mapOf(
                "with_meta" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(true))),
                    primaryKey = listOf("id"),
                    metadata = TableMetadata(engine = "InnoDB"),
                ),
                "without_meta" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(true))),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val out = ByteArrayOutputStream()
        codec.write(out, schema)
        val yaml = out.toString(Charsets.UTF_8)
        // "metadata" should appear exactly once (for with_meta)
        yaml.split("metadata:").size shouldBe 2  // 1 occurrence → split gives 2 parts
    }

    // ── No notes or skipped_objects in output ───

    test("write does not include reverse-report fields") {
        val schema = SchemaDefinition(
            name = "Clean", version = "1.0",
            tables = mapOf("t" to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(true))),
                primaryKey = listOf("id"),
            )),
        )
        val out = ByteArrayOutputStream()
        codec.write(out, schema)
        val yaml = out.toString(Charsets.UTF_8)
        yaml shouldNotContain "skipped_objects"
        yaml shouldNotContain "severity"
        yaml shouldNotContain "action_required"
    }
})
