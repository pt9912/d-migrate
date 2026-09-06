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

    test("abort renders a plain INSERT") {
        OracleInsertSql.build(table, columns, emptyList(), OnConflict.ABORT) shouldBe
            "INSERT INTO \"APP\".\"orders\" (\"id\", \"name\") VALUES (?, ?)"
    }

    test("skip renders a MERGE with only a NOT MATCHED / INSERT branch") {
        val sql = OracleInsertSql.build(table, columns, listOf("id"), OnConflict.SKIP)
        sql shouldBe "MERGE INTO \"APP\".\"orders\" tgt " +
            "USING (SELECT ? AS \"c0\", ? AS \"c1\" FROM DUAL) src " +
            "ON (tgt.\"id\" = src.\"c0\") " +
            "WHEN NOT MATCHED THEN INSERT (\"id\", \"name\") VALUES (src.\"c0\", src.\"c1\")"
        sql shouldNotContain "WHEN MATCHED"
    }

    test("update renders a MERGE that updates the non-key columns") {
        val sql = OracleInsertSql.build(table, columns, listOf("id"), OnConflict.UPDATE)
        sql shouldContain "WHEN MATCHED THEN UPDATE SET tgt.\"name\" = src.\"c1\" "
        sql shouldContain "WHEN NOT MATCHED THEN INSERT (\"id\", \"name\") VALUES (src.\"c0\", src.\"c1\")"
    }

    test("update with key-only columns skips the UPDATE branch (nothing to set)") {
        val sql = OracleInsertSql.build(table, listOf(col("id")), listOf("id"), OnConflict.UPDATE)
        sql shouldNotContain "WHEN MATCHED"
        sql shouldContain "WHEN NOT MATCHED THEN INSERT (\"id\") VALUES (src.\"c0\")"
    }

    test("composite keys join the MERGE predicate with AND") {
        val sql = OracleInsertSql.build(
            table, listOf(col("a"), col("b"), col("payload")), listOf("a", "b"), OnConflict.UPDATE,
        )
        sql shouldContain "ON (tgt.\"a\" = src.\"c0\" AND tgt.\"b\" = src.\"c1\") "
        sql shouldContain "UPDATE SET tgt.\"payload\" = src.\"c2\""
    }

    test("merge without primary key columns and insert without columns are rejected") {
        shouldThrow<IllegalArgumentException> {
            OracleInsertSql.build(table, columns, emptyList(), OnConflict.UPDATE)
        }.message!! shouldContain "primary key columns"
        shouldThrow<IllegalArgumentException> {
            OracleInsertSql.build(table, emptyList(), emptyList(), OnConflict.ABORT)
        }.message!! shouldContain "at least one column"
    }
})
