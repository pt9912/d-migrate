package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.SequenceDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Wo eine SQL-Server-Sequenz nach dem Preserve weiterlaeuft — die Rechnung,
 * an der ein Fehler nicht auffaellt, bis Schluessel kollidieren.
 *
 * `sys.sequences.current_value` ist der zuletzt AUSGEGEBENE Wert und
 * `RESTART WITH` setzt den naechsten; dazwischen liegt die Schrittweite.
 */
class MssqlSequenceResumeTest : FunSpec({

    fun sequence(
        start: Long = 1L,
        increment: Long = 1L,
        min: Long? = null,
        max: Long? = null,
        cycle: Boolean = false,
    ) = SequenceDefinition(start = start, increment = increment, minValue = min, maxValue = max, cycle = cycle)


    test("MSSQL: der Fortsetzungspunkt liegt eine Schrittweite hinter dem gelesenen Wert") {
        MssqlSequenceResume.resumePoint(41L, sequence()) shouldBe 42L
        MssqlSequenceResume.resumePoint(100L, sequence(increment = 5L)) shouldBe 105L
    }

    test("MSSQL: eine absteigende Sequenz laeuft rueckwaerts weiter, nicht vorwaerts") {
        MssqlSequenceResume.resumePoint(10L, sequence(increment = -1L, min = 1L, max = 10L)) shouldBe 9L
    }

    test("MSSQL: am Rand bricht eine zyklische Sequenz auf die andere Schranke um") {
        // `RESTART WITH` macht den Umbruch nicht selbst, und ausserhalb der
        // Schranken lehnt SQL Server ihn ab.
        MssqlSequenceResume.resumePoint(10L, sequence(cycle = true, min = 1L, max = 10L)) shouldBe 1L
        MssqlSequenceResume.resumePoint(1L, sequence(increment = -1L, cycle = true, min = 1L, max = 10L)) shouldBe 10L
    }

    test("MSSQL: am Rand ohne CYCLE gibt es keinen Fortsetzungspunkt") {
        MssqlSequenceResume.resumePoint(10L, sequence(min = 1L, max = 10L)).shouldBeNull()
    }

    test("MSSQL: ein Ueberlauf ist kein Fortsetzungspunkt") {
        MssqlSequenceResume.resumePoint(Long.MAX_VALUE, sequence()).shouldBeNull()
    }

    test("MSSQL: die wirksamen Schranken folgen der Richtung, wenn keine erklaert sind") {
        MssqlSequenceResume.boundedMinValue(sequence(cycle = true, start = 5L)) shouldBe 1L
        MssqlSequenceResume.boundedMaxValue(sequence(increment = -1L, cycle = true, start = -5L)) shouldBe -1L
        MssqlSequenceResume.boundedMinValue(sequence()).shouldBeNull()
    }
})
