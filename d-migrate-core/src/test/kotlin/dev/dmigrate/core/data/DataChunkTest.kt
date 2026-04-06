package dev.dmigrate.core.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class DataChunkTest : FunSpec({

    val cols = listOf(
        ColumnDescriptor("id", nullable = false, sqlTypeName = "INTEGER"),
        ColumnDescriptor("name", nullable = false, sqlTypeName = "VARCHAR"),
    )

    test("empty chunk has zero rows but keeps column metadata") {
        val chunk = DataChunk(table = "users", columns = cols, rows = emptyList(), chunkIndex = 0)
        chunk.rows.size shouldBe 0
        chunk.columns shouldBe cols
        chunk.chunkIndex shouldBe 0L
    }

    test("equality compares array contents structurally") {
        val a = DataChunk(
            table = "users",
            columns = cols,
            rows = listOf(arrayOf<Any?>(1, "alice"), arrayOf<Any?>(2, "bob")),
            chunkIndex = 0,
        )
        val b = DataChunk(
            table = "users",
            columns = cols,
            rows = listOf(arrayOf<Any?>(1, "alice"), arrayOf<Any?>(2, "bob")),
            chunkIndex = 0,
        )
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    test("equality differentiates row content") {
        val a = DataChunk("t", cols, listOf(arrayOf<Any?>(1, "alice")), 0)
        val b = DataChunk("t", cols, listOf(arrayOf<Any?>(1, "bob")), 0)
        a shouldNotBe b
    }

    test("equality differentiates chunk index") {
        val rows = listOf(arrayOf<Any?>(1, "alice"))
        DataChunk("t", cols, rows, 0) shouldNotBe DataChunk("t", cols, rows, 1)
    }

    test("DataFilter sealed class — WhereClause carries raw SQL") {
        val f = DataFilter.WhereClause("created_at >= '2024-01-01'")
        f.sql shouldBe "created_at >= '2024-01-01'"
    }

    test("DataFilter ColumnSubset and Compound are constructible") {
        val sub = DataFilter.ColumnSubset(listOf("id", "name"))
        val compound = DataFilter.Compound(listOf(sub, DataFilter.WhereClause("id > 0")))
        compound.parts.size shouldBe 2
    }

    test("ColumnDescriptor sqlTypeName defaults to null") {
        val c = ColumnDescriptor("name", nullable = true)
        c.sqlTypeName shouldBe null
    }
})
