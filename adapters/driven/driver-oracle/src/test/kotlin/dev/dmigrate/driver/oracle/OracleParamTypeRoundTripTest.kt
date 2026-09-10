package dev.dmigrate.driver.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Der Rueckweg eines Parametertyps: gelesen und wieder gerendert.
 *
 * Eine PL/SQL-Signatur traegt keine Laenge — der Reverse legt Parametertypen
 * deshalb ohne ab, und einige Oracle-Typen fallen dabei mit anderen zusammen.
 * Welche das sind, entscheidet nicht eine gepflegte Liste, sondern der Rueckweg
 * selbst; diese Tests halten fest, was er heute hergibt.
 */
class OracleParamTypeRoundTripTest : FunSpec({

    fun back(oracleType: String): String =
        OracleRoutineDdl.paramTypeSql(OracleTypeMapping.mapParamType(oracleType))

    test("CLOB kommt als CLOB wieder — das war die Verengung, um die es ging") {
        back("CLOB") shouldBe "CLOB"
        OracleTypeMapping.paramTypeSurvivesRoundTrip("CLOB") shouldBe true
    }

    test("CHAR kommt als CHAR wieder") {
        back("CHAR") shouldBe "CHAR"
        OracleTypeMapping.paramTypeSurvivesRoundTrip("CHAR") shouldBe true
    }

    test("die uebrigen Grundtypen kommen unveraendert wieder") {
        for (type in listOf("NUMBER", "BLOB", "JSON", "XMLTYPE", "BOOLEAN", "TIMESTAMP")) {
            OracleTypeMapping.paramTypeSurvivesRoundTrip(type) shouldBe true
        }
    }

    test("auch VARCHAR2 kommt als CLOB wieder — der haeufige Fall, ausdruecklich festgehalten") {
        // Der neutrale Name `text` fasst VARCHAR2 und CLOB zusammen. Gerendert
        // wird die Form, die keinen Aufruf brechen kann: CLOB nimmt an, was
        // VARCHAR2 annimmt, und zusaetzlich das, woran VARCHAR2 scheitert.
        back("VARCHAR2") shouldBe "CLOB"
        OracleTypeMapping.paramTypeSurvivesRoundTrip("VARCHAR2") shouldBe false
    }

    test("was der neutrale Name nicht unterscheiden kann, kommt anders wieder — und sagt es") {
        // NVARCHAR2/NCLOB/LONG: die N-Variante bzw. der Alttyp fallen mit
        // `text` zusammen.
        back("NCLOB") shouldBe "CLOB"
        back("NVARCHAR2") shouldBe "CLOB"
        back("LONG") shouldBe "CLOB"
        back("NCHAR") shouldBe "CHAR"
        // DATE traegt bei Oracle eine Uhrzeit; `datetime` fasst DATE,
        // TIMESTAMP und TIMESTAMP WITH TIME ZONE zusammen. TIMESTAMP ist von
        // den dreien der breiteste — DATE zu rendern verengte zwei davon.
        back("DATE") shouldBe "TIMESTAMP"
        // Der neutrale Parametername traegt keine Gleitkomma-Genauigkeit.
        back("BINARY_FLOAT") shouldBe "BINARY_DOUBLE"
        // `binary` traegt keine Laenge — wie bei einer Spalte auch.
        back("RAW") shouldBe "BLOB"

        for (type in listOf("NCLOB", "NVARCHAR2", "LONG", "NCHAR", "DATE", "BINARY_FLOAT", "RAW")) {
            OracleTypeMapping.paramTypeSurvivesRoundTrip(type) shouldBe false
        }
    }

    test("ein benutzerdefinierter Typ wird durchgereicht und gilt deshalb als unveraendert") {
        OracleTypeMapping.paramTypeSurvivesRoundTrip("OBJECT") shouldBe true
    }
})
