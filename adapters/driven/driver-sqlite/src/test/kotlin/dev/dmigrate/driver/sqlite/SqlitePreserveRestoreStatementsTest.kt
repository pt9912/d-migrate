package dev.dmigrate.driver.sqlite

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
 * Was der Executor innerhalb der Sperre zurueckschreibt: dieselbe Form, die
 * der Diff-Renderer erzeugt.
 */
class SqlitePreserveRestoreStatementsTest : FunSpec({

    fun request(name: String = "order_seq", sequence: SequenceDefinition? = null) =
        AtomicSequencePreserveRequest(
            sequenceRef = SequenceObjectRef(name, null, RenameProjectionDialect.SQLITE),
            sequence = sequence,
        )

    fun probe(value: Long, isCalled: Boolean? = null) =
        SequenceCurrentValueProbeResult.Read(value = value, isCalled = isCalled)

    val executor = SqliteAtomicSequencePreserveExecutor()

    test("die Hilfstabelle bekommt den geprobten Wert unveraendert") {
        executor.restoreStatements(request(), probe(100L)) shouldContainExactly
            listOf("""UPDATE "dmg_sequences" SET "next_value" = 100 WHERE "name" = 'order_seq';""")
    }

    test("ein Apostroph im Namen wird verdoppelt, nicht durchgereicht") {
        val sql = executor.restoreStatements(request("o'seq"), probe(1L)).single()

        sql shouldContain """"name" = 'o''seq'"""
    }
})
