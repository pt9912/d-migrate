package dev.dmigrate.cli.commands

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

class CompareOperandTest : FunSpec({

    // ── Parser ──────────────────────────────────

    test("file: prefix parses as File operand") {
        val op = CompareOperandParser.parse("file:/tmp/schema.yaml")
        op.shouldBeInstanceOf<CompareOperand.File>()
        (op as CompareOperand.File).path shouldBe Path.of("/tmp/schema.yaml")
    }

    test("db: prefix parses as Database operand") {
        val op = CompareOperandParser.parse("db:postgresql://user@host/db")
        op.shouldBeInstanceOf<CompareOperand.Database>()
        (op as CompareOperand.Database).source shouldBe "postgresql://user@host/db"
    }

    test("db: with alias parses as Database operand") {
        val op = CompareOperandParser.parse("db:staging")
        op.shouldBeInstanceOf<CompareOperand.Database>()
        (op as CompareOperand.Database).source shouldBe "staging"
    }

    test("prefix-less string parses as File operand (backward compat)") {
        val op = CompareOperandParser.parse("/tmp/schema.yaml")
        op.shouldBeInstanceOf<CompareOperand.File>()
        (op as CompareOperand.File).path shouldBe Path.of("/tmp/schema.yaml")
    }

    test("prefix-less relative path parses as File") {
        val op = CompareOperandParser.parse("schema.yaml")
        op.shouldBeInstanceOf<CompareOperand.File>()
    }

    test("file: with empty path throws") {
        shouldThrow<IllegalArgumentException> {
            CompareOperandParser.parse("file:")
        }
    }

    test("db: with empty source throws") {
        shouldThrow<IllegalArgumentException> {
            CompareOperandParser.parse("db:")
        }
    }

    // ── Normalizer ──────────────────────────────

    fun operand(name: String, version: String) = ResolvedSchemaOperand(
        reference = "test",
        schema = SchemaDefinition(name = name, version = version),
        validation = ValidationResult(),
    )

    test("non-reverse schema passes through unchanged") {
        val op = operand("My App", "1.0.0")
        val normalized = CompareOperandNormalizer.normalize(op)
        normalized.schema.name shouldBe "My App"
        normalized.schema.version shouldBe "1.0.0"
    }

    test("valid reverse markers are normalized") {
        val name = ReverseScopeCodec.postgresName("mydb", "public")
        val op = operand(name, ReverseScopeCodec.REVERSE_VERSION)
        val normalized = CompareOperandNormalizer.normalize(op)
        normalized.schema.name shouldBe "__compare_normalized__"
        normalized.schema.version shouldBe "0.0.0"
    }

    test("both file and db operands normalize independently") {
        val fileOp = operand("My App", "1.0.0")
        val dbOp = operand(
            ReverseScopeCodec.mysqlName("shopdb"),
            ReverseScopeCodec.REVERSE_VERSION,
        )
        val normalizedFile = CompareOperandNormalizer.normalize(fileOp)
        val normalizedDb = CompareOperandNormalizer.normalize(dbOp)

        // File side unchanged
        normalizedFile.schema.name shouldBe "My App"
        // DB side normalized
        normalizedDb.schema.name shouldBe "__compare_normalized__"
    }

    test("invalid reverse marker throws") {
        val op = operand("__dmigrate_reverse__:invalid", "1.0.0")
        shouldThrow<IllegalStateException> {
            CompareOperandNormalizer.normalize(op)
        }.message shouldBe "Schema 'test' uses reserved prefix '__dmigrate_reverse__:' but has invalid or incomplete reverse marker set"
    }

    test("prefix with wrong version throws") {
        val name = ReverseScopeCodec.postgresName("db", "public")
        val op = operand(name, "1.0.0") // wrong version
        shouldThrow<IllegalStateException> {
            CompareOperandNormalizer.normalize(op)
        }
    }

    test("notes and skippedObjects are preserved through normalization") {
        val name = ReverseScopeCodec.sqliteName("main")
        val op = ResolvedSchemaOperand(
            reference = "db:test.db",
            schema = SchemaDefinition(name = name, version = ReverseScopeCodec.REVERSE_VERSION),
            validation = ValidationResult(),
            notes = listOf(dev.dmigrate.driver.SchemaReadNote(
                dev.dmigrate.driver.SchemaReadSeverity.WARNING, "R001", "t", "note")),
            skippedObjects = listOf(dev.dmigrate.driver.SkippedObject("TABLE", "vt", "skip")),
        )
        val normalized = CompareOperandNormalizer.normalize(op)
        normalized.notes.size shouldBe 1
        normalized.skippedObjects.size shouldBe 1
    }
})
