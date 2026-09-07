package dev.dmigrate.driver.oracle.profiling

import dev.dmigrate.profiling.types.TargetLogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Die Ausdruecke, die die Oracle-Eigenheiten tragen. */
class OracleProfilingExpressionsTest : FunSpec({

    test("a CLOB is compared through a truncated projection, not directly") {
        // `COUNT(DISTINCT clob)` scheitert mit ORA-22849; ohne die Projektion
        // gaebe es fuer LOB-Spalten gar keine Kennzahlen.
        val cmp = OracleProfilingExpressions.comparable("\"body\"", "CLOB")
        cmp shouldContain "SUBSTR(\"body\", 1, 4000)"
        // Eine gewoehnliche Spalte wird nicht projiziert.
        OracleProfilingExpressions.comparable("\"nm\"", "VARCHAR2") shouldBe "\"nm\""
    }

    test("grouping and display use the same projection so the counts agree") {
        val cmp = OracleProfilingExpressions.comparable("\"body\"", "CLOB")
        OracleProfilingExpressions.display("\"body\"", "CLOB") shouldBe cmp
    }

    test("binary values are shown as hex, a BLOB through a truncated projection") {
        OracleProfilingExpressions.display("\"r\"", "RAW") shouldBe "RAWTOHEX(\"r\")"
        OracleProfilingExpressions.display("\"b\"", "BLOB") shouldContain "RAWTOHEX(DBMS_LOB.SUBSTR"
    }

    test("temporal display carries an explicit mask so the session locale cannot decide") {
        listOf("DATE", "TIMESTAMP(6)", "TIMESTAMP(6) WITH TIME ZONE").forEach {
            OracleProfilingExpressions.display("\"ts\"", it) shouldContain "YYYY-MM-DD"
        }
    }

    test("LOB length goes through DBMS_LOB, everything else through LENGTH") {
        OracleProfilingExpressions.length("\"body\"", "CLOB") shouldBe "DBMS_LOB.GETLENGTH(\"body\")"
        OracleProfilingExpressions.length("\"nm\"", "VARCHAR2") shouldBe "LENGTH(\"nm\")"
    }

    test("a temporal column needs no conversion check") {
        // VALIDATE_CONVERSION naehme einen TIMESTAMP gar nicht (ORA-43909),
        // und ein Zeitpunkt IST bereits einer.
        OracleProfilingExpressions.fits("\"ts\"", "TIMESTAMP(6)", TargetLogicalType.DATETIME) shouldBe "1 = 1"
        OracleProfilingExpressions.fits("\"ts\"", "DATE", TargetLogicalType.INTEGER) shouldBe "1 = 0"
    }

    test("a binary column is judged through its hex text") {
        OracleProfilingExpressions.fits("\"r\"", "RAW", TargetLogicalType.DECIMAL) shouldContain "RAWTOHEX"
    }

    test("a numeric column is judged on its value, not on its text form") {
        // Ueber `TO_CHAR` zu gehen machte die Antwort von
        // NLS_NUMERIC_CHARACTERS abhaengig: in einer Sitzung mit
        // Dezimalkomma gaelte 10,5 als ganzzahlig.
        OracleProfilingExpressions.fits("\"amt\"", "NUMBER", TargetLogicalType.INTEGER) shouldBe
            "MOD(\"amt\", 1) = 0"
        OracleProfilingExpressions.fits("\"amt\"", "NUMBER", TargetLogicalType.DECIMAL) shouldBe "1 = 1"
        OracleProfilingExpressions.fits("\"amt\"", "NUMBER", TargetLogicalType.DATE) shouldBe "1 = 0"
    }

    test("a LOB column is judged through its projection, not through the LOB itself") {
        // VALIDATE_CONVERSION nimmt keinen CLOB (ORA-43909).
        OracleProfilingExpressions.fits("\"body\"", "CLOB", TargetLogicalType.DECIMAL) shouldContain
            "SUBSTR(\"body\", 1, 4000)"
    }

    test("a text column is judged by the server, and an integer tolerates no separator") {
        val integer = OracleProfilingExpressions.fits("\"nm\"", "VARCHAR2", TargetLogicalType.INTEGER)
        integer shouldContain "VALIDATE_CONVERSION(TO_CHAR(\"nm\") AS NUMBER) = 1"
        // Beide Trennzeichen auszuschliessen ist sitzungsunabhaengig.
        integer shouldContain "NOT LIKE '%.%'"
        integer shouldContain "NOT LIKE '%,%'"
        OracleProfilingExpressions.fits("\"nm\"", "VARCHAR2", TargetLogicalType.STRING) shouldBe "1 = 1"
    }

    test("date fits are pinned to unambiguous masks") {
        // Ohne Maske entschiede NLS_DATE_FORMAT mit, und dasselbe Profil fiele
        // je nach Anmeldung anders aus.
        val date = OracleProfilingExpressions.fits("\"nm\"", "VARCHAR2", TargetLogicalType.DATE)
        date shouldContain "'YYYY-MM-DD'"
        date shouldContain "'YYYYMMDD'"
        OracleProfilingExpressions.fits("\"nm\"", "VARCHAR2", TargetLogicalType.DATETIME) shouldContain
            "HH24:MI:SS"
    }

    test("boolean fit accepts the text forms other dialects use") {
        val boolean = OracleProfilingExpressions.fits("\"nm\"", "VARCHAR2", TargetLogicalType.BOOLEAN)
        listOf("'0'", "'1'", "'true'", "'false'", "'yes'", "'no'").forEach { boolean shouldContain it }
    }

    test("a temporal mask carries fractional seconds only where the type has them") {
        // `.FF` an einem DATE ist ORA-01821; ohne `.FF` an einem TIMESTAMP
        // faenden zwei verschiedene Zeitpunkte denselben Text.
        OracleProfilingExpressions.temporalMask("DATE") shouldBe "YYYY-MM-DD\"T\"HH24:MI:SS"
        OracleProfilingExpressions.temporalMask("TIMESTAMP(6)") shouldContain ".FF9"
        OracleProfilingExpressions.temporalMask("TIMESTAMP(6) WITH TIME ZONE") shouldContain "TZH:TZM"
        OracleProfilingExpressions.temporalMask("NUMBER").shouldBeNull()
    }

    test("a number is rendered with a pinned decimal point") {
        OracleProfilingExpressions.display("\"amt\"", "NUMBER") shouldContain "NLS_NUMERIC_CHARACTERS"
    }

    test("XML and JSON are projected to VARCHAR2, not to a LOB") {
        // `SUBSTR(... AS CLOB)` liefe in denselben ORA-22849, den die
        // Projektion vermeiden soll.
        OracleProfilingExpressions.comparable("\"x\"", "XMLTYPE") shouldContain "AS VARCHAR2(4000)"
        OracleProfilingExpressions.comparable("\"j\"", "JSON") shouldContain "RETURNING VARCHAR2(4000)"
    }

    test("types that cannot be aggregated at all are named, not left to the server") {
        // COUNT(long) ist ORA-00997, und es gibt keine Projektion darauf.
        OracleProfilingExpressions.unprofilableReason("LONG")!! shouldContain "ORA-00997"
        OracleProfilingExpressions.unprofilableReason("LONG RAW")!! shouldContain "ORA-00997"
        OracleProfilingExpressions.unprofilableReason("BFILE")!! shouldContain "outside the database"
        OracleProfilingExpressions.unprofilableReason("SDO_GEOMETRY")!! shouldContain "MAP or ORDER"
        OracleProfilingExpressions.unprofilableReason("VARCHAR2").shouldBeNull()
        OracleProfilingExpressions.unprofilableReason("CLOB").shouldBeNull()
    }

    test("textual and character-LOB classification") {
        OracleProfilingExpressions.isTextual("VARCHAR2(50)") shouldBe true
        OracleProfilingExpressions.isTextual("CLOB") shouldBe true
        OracleProfilingExpressions.isTextual("NUMBER") shouldBe false
        OracleProfilingExpressions.isCharacterLob("NCLOB") shouldBe true
        OracleProfilingExpressions.isCharacterLob("VARCHAR2") shouldBe false
    }
})
