package dev.dmigrate.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * ADR 0025: the single `isSpatialGeometryIndex` predicate shared by every per-dialect
 * generate/diff geometry router. A FULLTEXT index lists its source TEXT columns, so it must
 * never be routed to the spatial path — even if a source column is geometry-typed.
 */
class IndexGeometryPredicateTest : FunSpec({

    val types = mapOf<String, NeutralType>(
        "shape" to NeutralType.Geometry(),
        "title" to NeutralType.Text(),
    )
    val columnType: (String) -> NeutralType? = { types[it] }

    test("a non-fulltext index over a geometry column references geometry") {
        IndexDefinition(columns = listOf(IndexColumn("shape")), type = IndexType.GIST)
            .isSpatialGeometryIndex(columnType) shouldBe true
    }

    test("a FULLTEXT index whose source column is geometry-typed does NOT reference geometry") {
        IndexDefinition(columns = listOf(IndexColumn("shape")), type = IndexType.FULLTEXT)
            .isSpatialGeometryIndex(columnType) shouldBe false
    }

    test("an index over only non-geometry columns does not reference geometry") {
        IndexDefinition(columns = listOf(IndexColumn("title")), type = IndexType.BTREE)
            .isSpatialGeometryIndex(columnType) shouldBe false
    }

    test("an unknown column type does not falsely report geometry") {
        IndexDefinition(columns = listOf(IndexColumn("missing")), type = IndexType.GIST)
            .isSpatialGeometryIndex(columnType) shouldBe false
    }
})
