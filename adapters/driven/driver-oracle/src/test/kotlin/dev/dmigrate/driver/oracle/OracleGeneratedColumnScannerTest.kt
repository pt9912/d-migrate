package dev.dmigrate.driver.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Geprueft gegen die Form, die `DBMS_METADATA.GET_DDL` auf Oracle 23 wirklich
 * liefert — abgenommen aus einem Live-Lauf, nicht erfunden.
 */
class OracleGeneratedColumnScannerTest : FunSpec({

    val liveDdl = """
        |
        |  CREATE TABLE "TEST"."probe_t" 
        |   (	"id" NUMBER(9,0), 
        |	"qty" NUMBER(9,0) NOT NULL ENABLE, 
        |	"price" NUMBER(12,2) NOT NULL ENABLE, 
        |	"virt" NUMBER(14,2) GENERATED ALWAYS AS ("qty"*"price") VIRTUAL , 
        |	"mat" NUMBER(14,2) GENERATED ALWAYS AS ("qty"*"price"*2) MATERIALIZED , 
        |	"plain" NUMBER(14,2) DEFAULT 7, 
        |	 PRIMARY KEY ("id")
        |  USING INDEX PCTFREE 10 INITRANS 2 MAXTRANS 255 
        |  TABLESPACE "USERS"  ENABLE
        |   ) SEGMENT CREATION DEFERRED 
        |  PCTFREE 10 PCTUSED 40 INITRANS 1 MAXTRANS 255 
        | NOCOMPRESS LOGGING
        |  TABLESPACE "USERS" 
    """.trimMargin()

    test("only the materialized column is reported") {
        OracleGeneratedColumnScanner.materializedColumns(liveDdl) shouldBe setOf("mat")
    }

    test("a type with a comma does not split the column") {
        // `NUMBER(14,2)` traegt ein Komma — wer die Liste naiv daran trennt,
        // verliert die halbe Spaltendeklaration und findet das Wort nie.
        val ddl = """CREATE TABLE "T" ("A" NUMBER(10,2) GENERATED ALWAYS AS ("B"*2) MATERIALIZED )"""
        OracleGeneratedColumnScanner.materializedColumns(ddl) shouldBe setOf("A")
    }

    test("the word inside a string literal is not the keyword") {
        val ddl = """CREATE TABLE "T" ("A" VARCHAR2(20) DEFAULT 'MATERIALIZED GENERATED')"""
        OracleGeneratedColumnScanner.materializedColumns(ddl).shouldBeEmpty()
    }

    test("a plain table yields nothing") {
        OracleGeneratedColumnScanner.materializedColumns(
            """CREATE TABLE "T" ("A" NUMBER, "B" VARCHAR2(10))""",
        ).shouldBeEmpty()
    }

    test("the storage tail after the column list is not mistaken for a column") {
        // `PRIMARY KEY ("id") USING INDEX PCTFREE …` ist ein Glied der Liste
        // ohne fuehrenden Spaltennamen — und die Speicherklauseln hinter der
        // Liste stehen ganz ausserhalb.
        OracleGeneratedColumnScanner.materializedColumns(liveDdl).size shouldBe 1
    }

    test("a bracket in the table name does not shift the parse") {
        val ddl = """CREATE TABLE "T(1)" ("A" NUMBER GENERATED ALWAYS AS ("B"*2) MATERIALIZED )"""
        OracleGeneratedColumnScanner.materializedColumns(ddl) shouldBe setOf("A")
    }

    test("a virtual column is not materialized") {
        val ddl = """CREATE TABLE "T" ("A" NUMBER GENERATED ALWAYS AS ("B"+1) VIRTUAL )"""
        OracleGeneratedColumnScanner.materializedColumns(ddl).shouldBeEmpty()
    }
})
