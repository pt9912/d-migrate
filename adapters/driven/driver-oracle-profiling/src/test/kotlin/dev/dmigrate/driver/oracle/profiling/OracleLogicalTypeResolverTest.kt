package dev.dmigrate.driver.oracle.profiling

import dev.dmigrate.profiling.types.LogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OracleLogicalTypeResolverTest : FunSpec({

    val resolver = OracleLogicalTypeResolver()

    test("NUMBER stays DECIMAL: the type name alone does not tell a boolean apart") {
        // Oracles Wahrheitskonvention ist NUMBER(1); die Praezision steht hier
        // nicht zur Verfuegung, und aus dem Namen allein sie zu schliessen
        // hiesse raten.
        resolver.resolve("NUMBER") shouldBe LogicalType.DECIMAL
        resolver.resolve("NUMBER(1)") shouldBe LogicalType.DECIMAL
        resolver.resolve("NUMBER(10,2)") shouldBe LogicalType.DECIMAL
    }

    test("DATE is DATETIME: Oracle's DATE carries a time component") {
        resolver.resolve("DATE") shouldBe LogicalType.DATETIME
    }

    test("TIMESTAMP variants keep their precision and zone in the name") {
        resolver.resolve("TIMESTAMP(6)") shouldBe LogicalType.DATETIME
        resolver.resolve("TIMESTAMP(6) WITH TIME ZONE") shouldBe LogicalType.DATETIME
        resolver.resolve("TIMESTAMP(6) WITH LOCAL TIME ZONE") shouldBe LogicalType.DATETIME
    }

    test("textual, binary and opaque types land in their families") {
        listOf("VARCHAR2", "NVARCHAR2", "CHAR", "CLOB", "NCLOB", "LONG", "ROWID")
            .forEach { resolver.resolve(it) shouldBe LogicalType.STRING }
        listOf("RAW", "LONG RAW", "BLOB", "BFILE")
            .forEach { resolver.resolve(it) shouldBe LogicalType.BINARY }
        resolver.resolve("JSON") shouldBe LogicalType.JSON
        resolver.resolve("SDO_GEOMETRY") shouldBe LogicalType.GEOMETRY
        // XMLTYPE ist Text, kein eigener Familienname im Profiling.
        resolver.resolve("XMLTYPE") shouldBe LogicalType.STRING
    }

    test("an interval is text; an unknown or empty name is UNKNOWN") {
        resolver.resolve("INTERVAL DAY(2) TO SECOND(6)") shouldBe LogicalType.STRING
        resolver.resolve("MY_OBJECT_TYPE") shouldBe LogicalType.UNKNOWN
        resolver.resolve("") shouldBe LogicalType.UNKNOWN
        resolver.resolve("   ") shouldBe LogicalType.UNKNOWN
    }

    test("the name is matched case-insensitively") {
        resolver.resolve("varchar2(50)") shouldBe LogicalType.STRING
        resolver.resolve("binary_double") shouldBe LogicalType.DECIMAL
    }
})
