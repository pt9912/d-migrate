package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * P9 — Oracles `NUMBER` ohne Angabe wird still `decimal(38,10)`.
 *
 * Dieselbe Klasse wie `numeric` ohne Praezision auf PostgreSQL und SQLite,
 * nur mit einer anderen konservativen Wahl: Oracle laesst 38 signifikante
 * Stellen an beliebiger Position zu, das Mapping legt zehn davon hinter das
 * Komma. Was darueber hinausgeht, geht auf dem Rueckweg verloren — und das
 * wird jetzt gesagt (`R371`).
 */
class OracleUnboundedNumberNoteTest : FunSpec({

    fun column(typeName: String, precision: Int? = null, scale: Int? = null, isIdentity: Boolean = false) =
        OracleTypeMapping.mapColumn(
            "T.C",
            OracleTypeMapping.ColumnInput(
                typeName = typeName,
                length = null,
                precision = precision,
                scale = scale,
                isIdentity = isIdentity,
                identityGeneration = null,
                identitySequenceName = null,
            ),
        )

    test("NUMBER ohne Angabe meldet R371 und wird decimal(38,10)") {
        val result = column("NUMBER")
        result.type shouldBe NeutralType.Decimal(38, 10)
        result.note!!.code shouldBe "R371"
        result.note!!.severity shouldBe SchemaReadSeverity.WARNING
        result.note!!.objectName shouldBe "T.C"
        result.note!!.message shouldContain "38,10"
        result.note!!.hint!! shouldContain "precision and scale"
    }

    // Gegenprobe 1: mit Praezision ist nichts offen.
    test("NUMBER(12,2) meldet nichts") {
        val result = column("NUMBER", precision = 12, scale = 2)
        result.type shouldBe NeutralType.Decimal(12, 2)
        result.note.shouldBeNull()
    }

    // Gegenprobe 2: eine Identity-Spalte laeuft durch einen anderen Zweig und
    // wird `biginteger`, nicht `decimal(38,10)` — dort geht die Stellenzahl
    // nicht auf diese Weise verloren.
    test("eine NUMBER-Identity ohne Praezision meldet nichts") {
        val result = column("NUMBER", isIdentity = true)
        result.type shouldBe NeutralType.BigInteger
        result.note.shouldBeNull()
    }

    // Gegenprobe 3: ein anderer Typ ohne Praezision ist nicht betroffen.
    test("VARCHAR2 meldet R371 nicht") {
        column("VARCHAR2").note.shouldBeNull()
    }
})
