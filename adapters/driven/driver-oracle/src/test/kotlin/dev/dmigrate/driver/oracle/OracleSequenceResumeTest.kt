package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.SequenceDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Wo eine Oracle-Sequenz nach dem Preserve weiterlaeuft — die Rechnung, an
 * der ein Fehler nicht auffaellt, bis Schluessel kollidieren.
 *
 * `ALL_SEQUENCES.LAST_NUMBER` ist bereits der naechste auszugebende Wert,
 * anders als SQL Servers `current_value`.
 */
class OracleSequenceResumeTest : FunSpec({

    fun sequence(
        start: Long = 1L,
        increment: Long = 1L,
        min: Long? = null,
        max: Long? = null,
        cycle: Boolean = false,
    ) = SequenceDefinition(start = start, increment = increment, minValue = min, maxValue = max, cycle = cycle)


    test("Oracle: LAST_NUMBER ist bereits der naechste Wert — er wird nicht versetzt") {
        // Live gemessen: nach `NEXTVAL` = 1 steht `LAST_NUMBER` auf 2, und
        // `RESTART START WITH 2` gibt als naechstes die 2 aus. Die
        // Schrittweite zu addieren verschenkte bei jedem Preserve einen Wert.
        OracleSequenceResume.resumePoint(42L, sequence()) shouldBe 42L
        OracleSequenceResume.resumePoint(105L, sequence(start = 100L, increment = 5L)) shouldBe 105L
    }

    test("Oracle: nicht erklaerte Schranken sind richtungsabhaengige Defaults, nicht unbegrenzt") {
        // NOMINVALUE ist bei aufsteigenden Sequenzen **1**, nicht minus
        // unendlich; ein Fortsetzungspunkt darunter waere keiner.
        OracleSequenceResume.resumePoint(-5L, sequence()).shouldBeNull()
        OracleSequenceResume.resumePoint(1L, sequence()) shouldBe 1L

        // Absteigend gilt dasselbe von der anderen Seite: NOMAXVALUE ist -1.
        OracleSequenceResume.resumePoint(-6L, sequence(increment = -1L)) shouldBe -6L
        OracleSequenceResume.resumePoint(5L, sequence(increment = -1L)).shouldBeNull()
    }

    test("Oracle: am Rand bricht eine zyklische Sequenz um, eine andere gibt auf") {
        OracleSequenceResume.resumePoint(11L, sequence(cycle = true, min = 1L, max = 10L)) shouldBe 1L
        OracleSequenceResume.resumePoint(11L, sequence(min = 1L, max = 10L)).shouldBeNull()
    }
})
