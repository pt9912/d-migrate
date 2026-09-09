package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain

/**
 * Wo SQL Server nach dem Preserve fortsetzt. `current_value` ist der zuletzt
 * AUSGEGEBENE Wert, `RESTART WITH` setzt den naechsten — dazwischen liegt die
 * Schrittweite. Wer den geprobten Wert unveraendert zurueckschreibt, gibt ihn
 * ein zweites Mal aus.
 */
class MssqlPreserveRestoreStatementsTest : FunSpec({

    fun request(name: String = "order_seq", sequence: SequenceDefinition? = null) =
        AtomicSequencePreserveRequest(
            sequenceRef = SequenceObjectRef(name, null, RenameProjectionDialect.MSSQL),
            sequence = sequence,
        )

    fun probe(value: Long, isCalled: Boolean? = null) =
        SequenceCurrentValueProbeResult.Read(value = value, isCalled = isCalled)

    val executor = MssqlAtomicSequencePreserveExecutor()

    fun sequence(increment: Long = 1L, min: Long? = null, max: Long? = null, cycle: Boolean = false) =
        SequenceDefinition(start = 1L, increment = increment, minValue = min, maxValue = max, cycle = cycle)

    test("RESTART WITH setzt hinter den geprobten Wert, nicht auf ihn") {
        executor.restoreStatements(request(sequence = sequence()), probe(41L)) shouldContainExactly
            listOf("ALTER SEQUENCE [order_seq] RESTART WITH 42;")
    }

    test("die Schrittweite ist die der Sequenz, nicht 1") {
        executor.restoreStatements(request(sequence = sequence(increment = 5L)), probe(100L)) shouldContainExactly
            listOf("ALTER SEQUENCE [order_seq] RESTART WITH 105;")
    }

    test("am Rand bricht eine zyklische Sequenz auf die andere Schranke um") {
        val seq = sequence(min = 1L, max = 10L, cycle = true)

        executor.restoreStatements(request(sequence = seq), probe(10L)) shouldContainExactly
            listOf("ALTER SEQUENCE [order_seq] RESTART WITH 1;")
    }

    test("eine erschoepfte Sequenz ohne CYCLE wird benannt abgelehnt, nicht geraten") {
        val thrown = shouldThrow<IllegalArgumentException> {
            executor.restoreStatements(request(sequence = sequence(min = 1L, max = 10L)), probe(10L))
        }
        thrown.message!! shouldContain "exhausted"
    }

    test("ohne Definition ist der Fortsetzungspunkt unbekannt — und das steht da") {
        val thrown = shouldThrow<IllegalArgumentException> {
            executor.restoreStatements(request(), probe(41L))
        }
        thrown.message!! shouldContain "needs the sequence definition"
    }
})
