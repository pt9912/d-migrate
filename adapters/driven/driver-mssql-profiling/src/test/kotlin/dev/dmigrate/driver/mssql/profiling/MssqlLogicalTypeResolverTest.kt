package dev.dmigrate.driver.mssql.profiling

import dev.dmigrate.profiling.types.LogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MssqlLogicalTypeResolverTest : FunSpec({

    val resolver = MssqlLogicalTypeResolver()

    test("numeric families") {
        listOf("int", "INTEGER", "bigint", "smallint", "tinyint").forEach {
            resolver.resolve(it) shouldBe LogicalType.INTEGER
        }
        listOf("decimal(10,2)", "numeric", "float", "real", "money", "smallmoney").forEach {
            resolver.resolve(it) shouldBe LogicalType.DECIMAL
        }
        // SQL Server kennt kein `boolean`; `bit` ist die Wahrheitsspalte.
        resolver.resolve("bit") shouldBe LogicalType.BOOLEAN
    }

    test("temporal families") {
        resolver.resolve("date") shouldBe LogicalType.DATE
        listOf("datetime", "datetime2(7)", "smalldatetime", "datetimeoffset", "time").forEach {
            resolver.resolve(it) shouldBe LogicalType.DATETIME
        }
    }

    test("textual, binary and spatial") {
        listOf("varchar(50)", "nvarchar(max)", "char", "nchar", "text", "ntext", "sysname",
            "uniqueidentifier", "xml").forEach {
            resolver.resolve(it) shouldBe LogicalType.STRING
        }
        listOf("binary", "varbinary(max)", "image").forEach {
            resolver.resolve(it) shouldBe LogicalType.BINARY
        }
        listOf("geometry", "geography").forEach { resolver.resolve(it) shouldBe LogicalType.GEOMETRY }
    }

    test("an empty or unknown type name stays UNKNOWN") {
        resolver.resolve("") shouldBe LogicalType.UNKNOWN
        resolver.resolve("   ") shouldBe LogicalType.UNKNOWN
        resolver.resolve("hierarchyid") shouldBe LogicalType.UNKNOWN
    }
})
