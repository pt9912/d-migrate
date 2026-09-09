package dev.dmigrate.driver.postgresql

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
 * Was der Executor innerhalb der Sperre zurueckschreibt. `setval` nimmt den
 * geprobten Wert unveraendert — samt `is_called`, ohne das die Sequenz danach
 * um eins versetzt stuende.
 */
class PostgresPreserveRestoreStatementsTest : FunSpec({

    fun request(name: String = "order_seq", sequence: SequenceDefinition? = null) =
        AtomicSequencePreserveRequest(
            sequenceRef = SequenceObjectRef(name, null, RenameProjectionDialect.POSTGRESQL),
            sequence = sequence,
        )

    fun probe(value: Long, isCalled: Boolean? = null) =
        SequenceCurrentValueProbeResult.Read(value = value, isCalled = isCalled)

    val executor = PostgresAtomicSequencePreserveExecutor()

    test("setval nimmt Wert und is_called, wie die Probe sie gelesen hat") {
        executor.restoreStatements(request(), probe(99L, isCalled = true)) shouldContainExactly
            listOf("SELECT setval('order_seq', 99, true);")
    }

    test("is_called = false geht unveraendert durch") {
        executor.restoreStatements(request(), probe(7L, isCalled = false)) shouldContainExactly
            listOf("SELECT setval('order_seq', 7, false);")
    }

    test("ohne is_called wird abgelehnt statt eines angenommen") {
        // Ein unterstelltes `true` verschoebe die Sequenz still um eins.
        val thrown = shouldThrow<IllegalArgumentException> {
            executor.restoreStatements(request(), probe(99L))
        }
        thrown.message!! shouldContain "order_seq"
    }
})
