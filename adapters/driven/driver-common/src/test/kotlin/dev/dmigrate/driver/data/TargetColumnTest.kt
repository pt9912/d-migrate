package dev.dmigrate.driver.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Types

class TargetColumnTest : FunSpec({

    test("basic construction") {
        val col = TargetColumn(
            name = "email",
            nullable = true,
            jdbcType = Types.VARCHAR,
            sqlTypeName = "varchar(255)",
        )
        col.name shouldBe "email"
        col.nullable shouldBe true
        col.jdbcType shouldBe Types.VARCHAR
        col.sqlTypeName shouldBe "varchar(255)"
    }

    test("sqlTypeName defaults to null") {
        val col = TargetColumn(name = "id", nullable = false, jdbcType = Types.INTEGER)
        col.sqlTypeName shouldBe null
    }

    test("data class equality") {
        val a = TargetColumn("id", false, Types.INTEGER, null)
        val b = TargetColumn("id", false, Types.INTEGER, null)
        (a == b) shouldBe true
    }

    test("different jdbcType not equal") {
        val a = TargetColumn("id", false, Types.INTEGER)
        val b = TargetColumn("id", false, Types.BIGINT)
        (a == b) shouldBe false
    }
})
