package dev.dmigrate.driver.oracle

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
 * Wo Oracle nach dem Preserve fortsetzt. `ALL_SEQUENCES.LAST_NUMBER` ist
 * bereits der naechste auszugebende Wert — anders als SQL Servers
 * `current_value`. Er wird deshalb unveraendert gesetzt; ihn zu versetzen
 * verschenkte bei jedem Preserve einen Wert.
 */
class OraclePreserveRestoreStatementsTest : FunSpec({

    fun request(name: String = "order_seq", sequence: SequenceDefinition? = null) =
        AtomicSequencePreserveRequest(
            sequenceRef = SequenceObjectRef(name, null, RenameProjectionDialect.ORACLE),
            sequence = sequence,
        )

    fun probe(value: Long, isCalled: Boolean? = null) =
        SequenceCurrentValueProbeResult.Read(value = value, isCalled = isCalled)

    val executor = OracleSequencePreserveExecutor()

    test("LAST_NUMBER wird unveraendert gesetzt") {
        executor.restoreStatements(request(sequence = SequenceDefinition()), probe(42L)) shouldContainExactly
            listOf("""ALTER SEQUENCE "order_seq" RESTART START WITH 42;""")
    }

    test("was ausserhalb der Schranken laege, wird benannt abgelehnt") {
        val seq = SequenceDefinition(start = 1L, increment = 1L, minValue = 1L, maxValue = 10L)
        val thrown = shouldThrow<IllegalArgumentException> {
            executor.restoreStatements(request(sequence = seq), probe(11L))
        }
        thrown.message!! shouldContain "cannot resume"
    }

    test("ohne Definition ist der Fortsetzungspunkt unbekannt — und das steht da") {
        val thrown = shouldThrow<IllegalArgumentException> {
            executor.restoreStatements(request(), probe(42L))
        }
        thrown.message!! shouldContain "needs the sequence definition"
    }
})
