package dev.dmigrate.driver.mysql

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
 * der Diff-Renderer erzeugt — inklusive der Bedingungen auf `managed_by` und
 * `format_version`, die eine fremde Zeile ungeschrieben lassen.
 */
class MysqlPreserveRestoreStatementsTest : FunSpec({

    fun request(name: String = "order_seq", sequence: SequenceDefinition? = null) =
        AtomicSequencePreserveRequest(
            sequenceRef = SequenceObjectRef(name, null, RenameProjectionDialect.MYSQL),
            sequence = sequence,
        )

    fun probe(value: Long, isCalled: Boolean? = null) =
        SequenceCurrentValueProbeResult.Read(value = value, isCalled = isCalled)

    val executor = MysqlAtomicSequencePreserveExecutor()

    test("die Hilfstabelle bekommt den geprobten Wert, und nur die eigene Zeile") {
        val sql = executor.restoreStatements(request(), probe(100L)).single()

        sql shouldContain "UPDATE `dmg_sequences` SET `next_value` = 100"
        sql shouldContain "WHERE `name` = 'order_seq'"
        sql shouldContain "`managed_by` IN ("
        sql shouldContain "`format_version` IN ("
    }

    test("das gerenderte SQL ist dasselbe wie im Diff-Pfad") {
        executor.restoreStatements(request(), probe(100L)) shouldContainExactly
            listOf(MysqlDiffSequenceOps.updateNextValueSql("order_seq", 100L))
    }
})
