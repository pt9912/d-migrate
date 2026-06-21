package dev.dmigrate.driver.data

import dev.dmigrate.core.data.DataFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure tests for the VA1b (Spatial-Slice) projection helpers in
 * [JdbcSelectQuerySupport]: geometry-aware projection wrapping and the
 * metadata-derived [ProbedColumn] handling. The JDBC probe execution itself is
 * covered end-to-end against real sqlite-jdbc in [AbstractJdbcDataReaderTest].
 */
class JdbcSelectQuerySupportTest : FunSpec({

    val quote: (String) -> String = { "\"${it.replace("\"", "\"\"")}\"" }
    val geomExpr: (String) -> String = { "ST_AsEWKB($it)" }

    test("geometryAwareProjection wraps only geometry columns, preserves DB order") {
        val probed = listOf(
            ProbedColumn("id", isGeometry = false),
            ProbedColumn("geom", isGeometry = true),
            ProbedColumn("name", isGeometry = false),
        )
        JdbcSelectQuerySupport.geometryAwareProjection(null, probed, quote, geomExpr) shouldBe
            "\"id\", ST_AsEWKB(\"geom\") AS \"geom\", \"name\""
    }

    test("geometryAwareProjection respects ColumnSubset order and wraps geometry within it") {
        val probed = listOf(
            ProbedColumn("id", isGeometry = false),
            ProbedColumn("geom", isGeometry = true),
            ProbedColumn("name", isGeometry = false),
        )
        val filter = DataFilter.ColumnSubset(listOf("geom", "id"))
        JdbcSelectQuerySupport.geometryAwareProjection(filter, probed, quote, geomExpr) shouldBe
            "ST_AsEWKB(\"geom\") AS \"geom\", \"id\""
    }

    test("geometryAwareProjection ColumnSubset excluding the geometry column emits no wrapper") {
        val probed = listOf(
            ProbedColumn("id", isGeometry = false),
            ProbedColumn("geom", isGeometry = true),
            ProbedColumn("name", isGeometry = false),
        )
        val filter = DataFilter.ColumnSubset(listOf("id", "name"))
        JdbcSelectQuerySupport.geometryAwareProjection(filter, probed, quote, geomExpr) shouldBe
            "\"id\", \"name\""
    }

    test("geometryAwareProjection with multiple geometry columns wraps each") {
        val probed = listOf(
            ProbedColumn("a", isGeometry = true),
            ProbedColumn("b", isGeometry = true),
        )
        JdbcSelectQuerySupport.geometryAwareProjection(null, probed, quote, geomExpr) shouldBe
            "ST_AsEWKB(\"a\") AS \"a\", ST_AsEWKB(\"b\") AS \"b\""
    }
})
