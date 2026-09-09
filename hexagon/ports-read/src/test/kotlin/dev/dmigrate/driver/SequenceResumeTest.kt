package dev.dmigrate.driver

import dev.dmigrate.core.model.SequenceDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Wo eine Sequenz nach dem Preserve weiterlaeuft — die Rechnung, an der ein
 * Fehler nicht auffaellt, bis Schluessel kollidieren.
 *
 * Beide Dialekte addieren die Schrittweite, aber aus verschiedenen Gruenden:
 * SQL Server, weil `current_value` der zuletzt AUSGEGEBENE Wert ist und
 * `RESTART WITH` den naechsten setzt; Oracle, weil `LAST_NUMBER` ohne `CACHE`
 * dasselbe bedeutet — mit `CACHE` liegt es ohnehin davor, und dann kostet die
 * Addition nur eine Luecke.
 */
class SequenceResumeTest : FunSpec({

    fun sequence(
        start: Long = 1L,
        increment: Long = 1L,
        min: Long? = null,
        max: Long? = null,
        cycle: Boolean = false,
    ) = SequenceDefinition(start = start, increment = increment, minValue = min, maxValue = max, cycle = cycle)

    // ── SQL Server ───────────────────────────────────────────────

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

    // ── Oracle ───────────────────────────────────────────────────

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

    // ── Die Zusicherung selbst ───────────────────────────────────

    test("nur NONE laesst das Fenster ungeschuetzt") {
        PreserveWindowIsolation.NONE.guardsWindow shouldBe false
        PreserveWindowIsolation.SERIALIZED.guardsWindow shouldBe true
        PreserveWindowIsolation.ATOMIC.guardsWindow shouldBe true
    }
})
