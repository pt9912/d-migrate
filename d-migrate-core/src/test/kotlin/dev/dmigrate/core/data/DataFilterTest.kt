package dev.dmigrate.core.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class DataFilterTest : FunSpec({

    test("WhereClause carries raw SQL") {
        val f = DataFilter.WhereClause("created_at >= '2024-01-01'")
        f.sql shouldBe "created_at >= '2024-01-01'"
    }

    test("ColumnSubset carries the column list") {
        val f = DataFilter.ColumnSubset(listOf("id", "name"))
        f.columns shouldContainExactly listOf("id", "name")
    }

    test("Compound nests other filters") {
        val compound = DataFilter.Compound(
            listOf(
                DataFilter.ColumnSubset(listOf("id", "name")),
                DataFilter.WhereClause("id > 0"),
            )
        )
        compound.parts.size shouldBe 2
    }

    test("ParameterizedClause carries SQL fragment and params") {
        val f = DataFilter.ParameterizedClause(
            sql = "\"updated_at\" >= ?",
            params = listOf(LocalDate.parse("2026-01-01")),
        )
        f.sql shouldBe "\"updated_at\" >= ?"
        f.params.size shouldBe 1
        f.params[0] shouldBe LocalDate.parse("2026-01-01")
    }

    test("ParameterizedClause tolerates multiple params including nulls") {
        val f = DataFilter.ParameterizedClause(
            sql = "a = ? AND (b IS NULL OR b < ?)",
            params = listOf("foo", null),
        )
        f.params shouldContainExactly listOf<Any?>("foo", null)
    }

    test("ParameterizedClause can be nested inside Compound") {
        val compound = DataFilter.Compound(
            listOf(
                DataFilter.WhereClause("status = 'active'"),
                DataFilter.ParameterizedClause("\"since\" >= ?", listOf(42L)),
            )
        )
        compound.parts.size shouldBe 2
        val raw = compound.parts[0] as DataFilter.WhereClause
        val param = compound.parts[1] as DataFilter.ParameterizedClause
        raw.sql shouldBe "status = 'active'"
        param.params shouldContainExactly listOf<Any?>(42L)
    }

    test("ParameterizedClause equals compares sql and params structurally") {
        val a = DataFilter.ParameterizedClause("x = ?", listOf(1))
        val b = DataFilter.ParameterizedClause("x = ?", listOf(1))
        val c = DataFilter.ParameterizedClause("x = ?", listOf(2))
        (a == b) shouldBe true
        (a == c) shouldBe false
    }
})
