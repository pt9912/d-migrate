package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * P9 — `NUMERIC`/`DECIMAL` ohne Praezision wird Gleitkomma, und das wird
 * gesagt (`R221`).
 *
 * Der Schwesterfall ist beim Erzeugen laengst laut: eine `decimal(p,s)`-Spalte
 * bekommt `W200`, weil SQLite sie als `REAL` anlegt. Die Gegenrichtung fehlte
 * — eine `NUMERIC`-Spalte **ohne** Angabe kam still als `float` zurueck.
 */
class SqliteUnboundedNumericNoteTest : FunSpec({

    fun column(rawType: String) = SqliteTypeMapping.mapColumn(
        rawType = rawType,
        isAutoIncrement = false,
        tableName = "t",
        colName = "amount",
    )

    test("NUMERIC ohne Praezision meldet R221 und wird float") {
        val result = column("NUMERIC")
        result.type shouldBe NeutralType.Float()
        result.note!!.code shouldBe "R221"
        result.note!!.objectName shouldBe "t.amount"
        result.note!!.message shouldContain "without precision"
        result.note!!.hint!! shouldContain "precision and scale"
    }

    test("DECIMAL ohne Praezision ebenso") {
        column("DECIMAL").note!!.code shouldBe "R221"
    }

    // Gegenprobe 1: mit Praezision bleibt es `decimal`, und es gibt nichts zu
    // melden.
    test("DECIMAL(12,2) meldet nichts") {
        val result = column("DECIMAL(12,2)")
        result.type shouldBe NeutralType.Decimal(12, 2)
        result.note.shouldBeNull()
    }

    // Gegenprobe 2: eine echte Gleitkommaspalte verliert nichts.
    test("REAL meldet nichts") {
        val result = column("REAL")
        result.type shouldBe NeutralType.Float()
        result.note.shouldBeNull()
    }
})
