package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TargetColumn
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.sql.Types

class MssqlInsertSqlTest : FunSpec({

    val table = MssqlQualifiedTableName("dbo", "orders")

    fun col(name: String, type: String = "int", srid: Int? = null) =
        TargetColumn(name = name, nullable = true, jdbcType = Types.INTEGER, sqlTypeName = type, srid = srid)

    val columns = listOf(col("id"), col("name", "nvarchar"))

    test("abort renders a plain INSERT") {
        MssqlInsertSql.build(table, columns, emptyList(), OnConflict.ABORT) shouldBe
            "INSERT INTO [dbo].[orders] ([id], [name]) VALUES (?, ?)"
    }

    test("skip renders a MERGE that only inserts") {
        val sql = MssqlInsertSql.build(table, columns, listOf("id"), OnConflict.SKIP)
        sql shouldBe "MERGE INTO [dbo].[orders] AS tgt USING (VALUES (?, ?)) AS src ([id], [name]) " +
            "ON tgt.[id] = src.[id] " +
            "WHEN NOT MATCHED THEN INSERT ([id], [name]) VALUES (src.[id], src.[name]) " +
            "OUTPUT \$action;"
        sql shouldNotContain "WHEN MATCHED"
    }

    test("update renders a MERGE that updates the non-key columns") {
        val sql = MssqlInsertSql.build(table, columns, listOf("id"), OnConflict.UPDATE)
        sql shouldContain "WHEN MATCHED THEN UPDATE SET tgt.[name] = src.[name] "
        sql shouldContain "WHEN NOT MATCHED THEN INSERT ([id], [name])"
        sql shouldContain "OUTPUT \$action;"
    }

    test("update with key-only columns skips the UPDATE branch (nothing to set)") {
        val sql = MssqlInsertSql.build(table, listOf(col("id")), listOf("id"), OnConflict.UPDATE)
        sql shouldNotContain "WHEN MATCHED"
        sql shouldContain "WHEN NOT MATCHED THEN INSERT ([id]) VALUES (src.[id])"
    }

    test("composite keys join the MERGE predicate with AND") {
        val sql = MssqlInsertSql.build(
            table, listOf(col("a"), col("b"), col("payload")), listOf("a", "b"), OnConflict.UPDATE,
        )
        sql shouldContain "ON tgt.[a] = src.[a] AND tgt.[b] = src.[b] "
        sql shouldContain "UPDATE SET tgt.[payload] = src.[payload]"
    }

    test("geometry columns bind WKB through the T-SQL constructor with a SRID") {
        MssqlInsertSql.placeholder(col("loc", "geometry")) shouldBe "geometry::STGeomFromWKB(?, 0)"
        MssqlInsertSql.placeholder(col("loc", "geography")) shouldBe "geography::STGeomFromWKB(?, 4326)"
        MssqlInsertSql.placeholder(col("loc", "geometry", srid = 3857)) shouldBe "geometry::STGeomFromWKB(?, 3857)"
        MssqlInsertSql.placeholder(col("loc", "geography", srid = 4258)) shouldBe "geography::STGeomFromWKB(?, 4258)"
        MssqlInsertSql.placeholder(col("name", "nvarchar")) shouldBe "?"
    }

    test("geometry placeholders keep one bind position per column") {
        MssqlInsertSql.build(table, listOf(col("id"), col("loc", "geography")), emptyList(), OnConflict.ABORT) shouldBe
            "INSERT INTO [dbo].[orders] ([id], [loc]) VALUES (?, geography::STGeomFromWKB(?, 4326))"
    }

    test("bracketed identifiers are escaped in every position") {
        val odd = MssqlQualifiedTableName("dbo", "we]ird")
        MssqlInsertSql.build(odd, listOf(col("c]x")), emptyList(), OnConflict.ABORT) shouldBe
            "INSERT INTO [dbo].[we]]ird] ([c]]x]) VALUES (?)"
    }

    test("merge without primary key columns and insert without columns are rejected") {
        shouldThrow<IllegalArgumentException> {
            MssqlInsertSql.build(table, columns, emptyList(), OnConflict.UPDATE)
        }.message!! shouldContain "primary key columns"
        shouldThrow<IllegalArgumentException> {
            MssqlInsertSql.build(table, emptyList(), emptyList(), OnConflict.ABORT)
        }.message!! shouldContain "at least one column"
    }
})
