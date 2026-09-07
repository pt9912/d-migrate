package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TargetColumn
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.sql.Types

class OracleInsertSqlTest : FunSpec({

    val table = OracleQualifiedTableName("APP", "orders")

    fun col(name: String) = TargetColumn(name = name, nullable = true, jdbcType = Types.INTEGER)

    val columns = listOf(col("id"), col("name"))

    /** Was die Import-Session fuer eine gewoehnliche Spalte liefert. */
    val plain: (TargetColumn) -> String = { "?" }

    test("abort renders a plain INSERT") {
        OracleInsertSql.build(table, columns, emptyList(), OnConflict.ABORT, plain) shouldBe
            "INSERT INTO \"APP\".\"orders\" (\"id\", \"name\") VALUES (?, ?)"
    }

    test("skip renders a MERGE with only a NOT MATCHED / INSERT branch") {
        val sql = OracleInsertSql.build(table, columns, listOf("id"), OnConflict.SKIP, plain)
        sql shouldBe "MERGE INTO \"APP\".\"orders\" tgt " +
            "USING (SELECT ? AS \"c0\", ? AS \"c1\" FROM DUAL) src " +
            "ON (tgt.\"id\" = src.\"c0\") " +
            "WHEN NOT MATCHED THEN INSERT (\"id\", \"name\") VALUES (src.\"c0\", src.\"c1\")"
        sql shouldNotContain "WHEN MATCHED"
    }

    test("update renders a MERGE that updates the non-key columns") {
        val sql = OracleInsertSql.build(table, columns, listOf("id"), OnConflict.UPDATE, plain)
        sql shouldContain "WHEN MATCHED THEN UPDATE SET tgt.\"name\" = src.\"c1\" "
        sql shouldContain "WHEN NOT MATCHED THEN INSERT (\"id\", \"name\") VALUES (src.\"c0\", src.\"c1\")"
    }

    test("update with key-only columns skips the UPDATE branch (nothing to set)") {
        val sql = OracleInsertSql.build(table, listOf(col("id")), listOf("id"), OnConflict.UPDATE, plain)
        sql shouldNotContain "WHEN MATCHED"
        sql shouldContain "WHEN NOT MATCHED THEN INSERT (\"id\") VALUES (src.\"c0\")"
    }

    test("composite keys join the MERGE predicate with AND") {
        val sql = OracleInsertSql.build(
            table, listOf(col("a"), col("b"), col("payload")), listOf("a", "b"), OnConflict.UPDATE, plain,
        )
        sql shouldContain "ON (tgt.\"a\" = src.\"c0\" AND tgt.\"b\" = src.\"c1\") "
        sql shouldContain "UPDATE SET tgt.\"payload\" = src.\"c2\""
    }

    test("a geometry column carries its constructor into the VALUES list") {
        val geometryPlaceholder: (TargetColumn) -> String = { col ->
            if (col.name == "geom") "SDO_UTIL.FROM_WKBGEOMETRY(?, 4326)" else "?"
        }
        OracleInsertSql.build(
            table, listOf(col("id"), col("geom")), emptyList(), OnConflict.ABORT, geometryPlaceholder,
        ) shouldBe
            "INSERT INTO \"APP\".\"orders\" (\"id\", \"geom\") " +
            "VALUES (?, SDO_UTIL.FROM_WKBGEOMETRY(?, 4326))"
    }

    test("the MERGE wraps the geometry already in the DUAL projection, not in the INSERT branch") {
        val geometryPlaceholder: (TargetColumn) -> String = { col ->
            if (col.name == "geom") "SDO_UTIL.FROM_WKBGEOMETRY(?, 4326)" else "?"
        }
        val sql = OracleInsertSql.build(
            table, listOf(col("id"), col("geom")), listOf("id"), OnConflict.UPDATE, geometryPlaceholder,
        )
        sql shouldContain "USING (SELECT ? AS \"c0\", SDO_UTIL.FROM_WKBGEOMETRY(?, 4326) AS \"c1\" FROM DUAL) src"
        // Der INSERT-Zweig liest aus `src` und darf den Konstruktor kein
        // zweites Mal setzen -- er bekaeme dort eine SDO_GEOMETRY statt WKB.
        sql shouldContain "VALUES (src.\"c0\", src.\"c1\")"
        sql shouldContain "UPDATE SET tgt.\"geom\" = src.\"c1\""
    }

    test("merge without primary key columns and insert without columns are rejected") {
        shouldThrow<IllegalArgumentException> {
            OracleInsertSql.build(table, columns, emptyList(), OnConflict.UPDATE, plain)
        }.message!! shouldContain "primary key columns"
        shouldThrow<IllegalArgumentException> {
            OracleInsertSql.build(table, emptyList(), emptyList(), OnConflict.ABORT, plain)
        }.message!! shouldContain "at least one column"
    }
})
