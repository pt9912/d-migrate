package dev.dmigrate.driver.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Der Schnitt zwischen Kopf und Rumpf. Die Quelltexte hier sind so
 * geschrieben, wie `ALL_SOURCE` sie liefert: ohne `CREATE OR REPLACE`, in der
 * Schreibweise des Autors.
 */
class OracleRoutineBodyTest : FunSpec({

    test("a function splits behind the top-level IS") {
        val split = OracleRoutineBody.splitRoutine(
            """
            FUNCTION p9_calc(p_a IN NUMBER, p_b IN VARCHAR2)
            RETURN NUMBER IS
              v_r NUMBER;
            BEGIN
              -- ein Kommentar
              v_r := p_a * 2;
              RETURN v_r;
            END;
            """.trimIndent(),
        )
        split!!.header shouldBe "FUNCTION p9_calc(p_a IN NUMBER, p_b IN VARCHAR2)\nRETURN NUMBER"
        split.body shouldBe "v_r NUMBER;\nBEGIN\n  -- ein Kommentar\n  v_r := p_a * 2;\n  RETURN v_r;\nEND;"
    }

    test("a procedure splits behind the top-level AS") {
        val split = OracleRoutineBody.splitRoutine("PROCEDURE p(x IN NUMBER)\nAS\nBEGIN\n  NULL;\nEND;")
        split!!.header shouldBe "PROCEDURE p(x IN NUMBER)"
        split.body shouldBe "BEGIN\n  NULL;\nEND;"
    }

    test("a parameter list containing IS or AS does not move the cut") {
        // Der Parametername enthaelt das Wort, aber nicht als eigenes Token,
        // und der Default steht in Klammern.
        val split = OracleRoutineBody.splitRoutine(
            "PROCEDURE p(p_island IN VARCHAR2 DEFAULT 'AS IS')\nIS\nBEGIN\n  NULL;\nEND;",
        )
        split!!.body shouldBe "BEGIN\n  NULL;\nEND;"
    }

    test("IS inside a string literal, a comment or a quoted identifier is skipped") {
        val split = OracleRoutineBody.splitRoutine(
            """
            FUNCTION f RETURN VARCHAR2
            /* AS IS */
            -- IS
            IS
            BEGIN
              RETURN 'IS';
            END;
            """.trimIndent(),
        )
        split!!.body shouldBe "BEGIN\n  RETURN 'IS';\nEND;"
    }

    test("a doubled quote does not end the literal early") {
        val split = OracleRoutineBody.splitRoutine(
            "FUNCTION f(p IN VARCHAR2 DEFAULT 'it''s') RETURN NUMBER IS BEGIN RETURN 1; END;",
        )
        split!!.body shouldBe "BEGIN RETURN 1; END;"
    }

    test("Oracle's alternative quoting hides its content from the scanner") {
        val split = OracleRoutineBody.splitRoutine(
            "FUNCTION f RETURN VARCHAR2 IS BEGIN RETURN q'[AS IS]'; END;",
        )
        split!!.body shouldBe "BEGIN RETURN q'[AS IS]'; END;"
    }

    test("a q that is part of an identifier does not start alternative quoting") {
        // `seq'` waere sonst der Beginn eines q-Literals und verschluckte den Rest.
        val split = OracleRoutineBody.splitRoutine(
            "FUNCTION f RETURN NUMBER IS BEGIN RETURN myseq.NEXTVAL; END;",
        )
        split!!.body shouldBe "BEGIN RETURN myseq.NEXTVAL; END;"
    }

    test("an aggregate function has no IS or AS and yields null") {
        OracleRoutineBody.splitRoutine("FUNCTION f(x IN NUMBER) RETURN NUMBER AGGREGATE USING t;").shouldBeNull()
    }

    test("a trigger splits before BEGIN, keeping the keyword in the body") {
        val split = OracleRoutineBody.splitTrigger(
            """
            TRIGGER p9_trg_row
            BEFORE INSERT OR UPDATE OF amt, note ON p9_orders
            FOR EACH ROW
            WHEN (NEW.amt > 10)
            BEGIN
              :NEW.amt := :NEW.amt + 1;
            END;
            """.trimIndent(),
        )
        split!!.header shouldBe
            "TRIGGER p9_trg_row\nBEFORE INSERT OR UPDATE OF amt, note ON p9_orders\nFOR EACH ROW\nWHEN (NEW.amt > 10)"
        split.body shouldBe "BEGIN\n  :NEW.amt := :NEW.amt + 1;\nEND;"
    }

    test("the REFERENCING clause's AS does not confuse the trigger cut") {
        val split = OracleRoutineBody.splitTrigger(
            "TRIGGER t\nBEFORE INSERT ON x\nREFERENCING NEW AS n OLD AS o\nFOR EACH ROW\nBEGIN\n  NULL;\nEND;",
        )
        split!!.body shouldBe "BEGIN\n  NULL;\nEND;"
    }

    test("a trigger with a DECLARE section keeps it in the body") {
        val split = OracleRoutineBody.splitTrigger(
            "TRIGGER t\nAFTER INSERT ON x\nDECLARE\n  v NUMBER;\nBEGIN\n  NULL;\nEND;",
        )
        split!!.body shouldBe "DECLARE\n  v NUMBER;\nBEGIN\n  NULL;\nEND;"
    }

    test("a CALL trigger has no PL/SQL block and yields null") {
        OracleRoutineBody.splitTrigger("TRIGGER t\nAFTER INSERT ON x\nCALL log_it()").shouldBeNull()
    }
})
