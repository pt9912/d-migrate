package dev.dmigrate.driver.metadata

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class SchemaReaderUtilsTest : FunSpec({

    // ── toReferentialAction ────────────────────────

    test("CASCADE maps correctly") {
        SchemaReaderUtils.toReferentialAction("CASCADE") shouldBe ReferentialAction.CASCADE
    }

    test("SET NULL maps correctly") {
        SchemaReaderUtils.toReferentialAction("SET NULL") shouldBe ReferentialAction.SET_NULL
    }

    test("SET DEFAULT maps correctly") {
        SchemaReaderUtils.toReferentialAction("SET DEFAULT") shouldBe ReferentialAction.SET_DEFAULT
    }

    test("RESTRICT maps correctly") {
        SchemaReaderUtils.toReferentialAction("RESTRICT") shouldBe ReferentialAction.RESTRICT
    }

    test("NO ACTION maps correctly") {
        SchemaReaderUtils.toReferentialAction("NO ACTION") shouldBe ReferentialAction.NO_ACTION
    }

    test("null maps to null") {
        SchemaReaderUtils.toReferentialAction(null).shouldBeNull()
    }

    test("unknown action maps to null") {
        SchemaReaderUtils.toReferentialAction("WHATEVER").shouldBeNull()
    }

    test("case insensitive") {
        SchemaReaderUtils.toReferentialAction("cascade") shouldBe ReferentialAction.CASCADE
    }

    // ── liftSingleColumnFks ────────────────────────

    test("single-column FK lifted to ReferenceDefinition") {
        val fks = listOf(
            ForeignKeyProjection("fk1", listOf("user_id"), "users", listOf("id"), "CASCADE", null),
        )
        val result = SchemaReaderUtils.liftSingleColumnFks(fks)
        result shouldContainKey "user_id"
        result["user_id"]!!.table shouldBe "users"
        result["user_id"]!!.column shouldBe "id"
        result["user_id"]!!.onDelete shouldBe ReferentialAction.CASCADE
    }

    test("multi-column FK is NOT lifted") {
        val fks = listOf(
            ForeignKeyProjection("fk1", listOf("a", "b"), "t", listOf("x", "y")),
        )
        SchemaReaderUtils.liftSingleColumnFks(fks).shouldBeEmpty()
    }

    // ── buildMultiColumnFkConstraints ───────────────

    test("multi-column FK becomes constraint") {
        val fks = listOf(
            ForeignKeyProjection("fk1", listOf("a", "b"), "target", listOf("x", "y"), "CASCADE", "RESTRICT"),
        )
        val result = SchemaReaderUtils.buildMultiColumnFkConstraints(fks)
        result shouldHaveSize 1
        result[0].type shouldBe ConstraintType.FOREIGN_KEY
        result[0].columns shouldBe listOf("a", "b")
        result[0].references!!.table shouldBe "target"
        result[0].references!!.onDelete shouldBe ReferentialAction.CASCADE
        result[0].references!!.onUpdate shouldBe ReferentialAction.RESTRICT
    }

    test("single-column FK is excluded from constraints") {
        val fks = listOf(
            ForeignKeyProjection("fk1", listOf("id"), "t", listOf("id")),
        )
        SchemaReaderUtils.buildMultiColumnFkConstraints(fks).shouldBeEmpty()
    }

    // ── buildMultiColumnUniqueFromConstraints ───────

    test("multi-column unique constraint from map") {
        val constraints = mapOf("uq1" to listOf("a", "b"), "uq2" to listOf("c"))
        val result = SchemaReaderUtils.buildMultiColumnUniqueFromConstraints(constraints)
        result shouldHaveSize 1
        result[0].name shouldBe "uq1"
        result[0].type shouldBe ConstraintType.UNIQUE
    }

    // ── buildMultiColumnUniqueFromIndices ───────────

    test("multi-column unique index becomes constraint") {
        val indices = listOf(
            IndexProjection("idx1", listOf("a", "b"), isUnique = true),
            IndexProjection("idx2", listOf("c"), isUnique = true),
            IndexProjection("idx3", listOf("d", "e"), isUnique = false),
        )
        val result = SchemaReaderUtils.buildMultiColumnUniqueFromIndices(indices)
        result shouldHaveSize 1
        result[0].name shouldBe "idx1"
    }

    // ── buildCheckConstraints ──────────────────────

    test("check constraints built from projections") {
        val checks = listOf(
            ConstraintProjection("chk1", "CHECK", expression = "x > 0"),
            ConstraintProjection("chk2", "CHECK", expression = "y IS NOT NULL"),
        )
        val result = SchemaReaderUtils.buildCheckConstraints(checks)
        result shouldHaveSize 2
        result[0].type shouldBe ConstraintType.CHECK
        result[0].expression shouldBe "x > 0"
    }

    // ── singleColumnUniqueFromIndices ───────────────

    test("single-column unique extracted from indices") {
        val indices = listOf(
            IndexProjection("idx1", listOf("email"), isUnique = true),
            IndexProjection("idx2", listOf("a", "b"), isUnique = true),
            IndexProjection("idx3", listOf("name"), isUnique = false),
        )
        SchemaReaderUtils.singleColumnUniqueFromIndices(indices) shouldBe setOf("email")
    }

    // ── singleColumnUniqueFromConstraints ───────────

    test("single-column unique extracted from constraint map") {
        val constraints = mapOf("uq1" to listOf("email"), "uq2" to listOf("a", "b"))
        SchemaReaderUtils.singleColumnUniqueFromConstraints(constraints) shouldBe setOf("email")
    }
})
