package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import dev.dmigrate.core.model.SequenceDefinition

/**
 * Atomic-Preserve Phase C.1 contract pin for
 * [AtomicPreserveRestoreSql]: per-dialect restore-SQL shape is
 * load-bearing because the closure runs **inside the atomic
 * executor's lock** at execute time. Drift between the dialect
 * renderer's UP path and this builder is a real risk (Phase D/E
 * follow-up plan-doc note).
 */
class AtomicPreserveRestoreSqlTest : FunSpec({

    fun pgRef(name: String = "users_id_seq") =
        SequenceObjectRef(name = name, dialect = RenameProjectionDialect.POSTGRESQL)

    fun mysqlRef(name: String = "order_seq") =
        SequenceObjectRef(name = name, dialect = RenameProjectionDialect.MYSQL)

    fun sqliteRef(name: String = "items_seq") =
        SequenceObjectRef(name = name, dialect = RenameProjectionDialect.SQLITE)

    fun mssqlRef(name: String = "invoice_seq") =
        SequenceObjectRef(name = name, dialect = RenameProjectionDialect.MSSQL)

    fun oracleRef(name: String = "invoice_seq") =
        SequenceObjectRef(name = name, dialect = RenameProjectionDialect.ORACLE)

    test("PG: SELECT setval('<name>', <value>, <isCalled>);") {
        val sql = AtomicPreserveRestoreSql.forDialect(
            dialect = DatabaseDialect.POSTGRESQL,
            sequenceRef = pgRef(),
            probe = SequenceCurrentValueProbeResult.Read(value = 42L, isCalled = true),
        )
        sql.size shouldBe 1
        sql.single() shouldBe "SELECT setval('users_id_seq', 42, true);"
    }

    test("PG: isCalled=false flows through verbatim") {
        val sql = AtomicPreserveRestoreSql.forDialect(
            dialect = DatabaseDialect.POSTGRESQL,
            sequenceRef = pgRef("orders_id_seq"),
            probe = SequenceCurrentValueProbeResult.Read(value = 1L, isCalled = false),
        )
        sql.single() shouldBe "SELECT setval('orders_id_seq', 1, false);"
    }

    test("PG: missing isCalled on probe throws IllegalArgumentException with sequence name") {
        val ex = shouldThrow<IllegalArgumentException> {
            AtomicPreserveRestoreSql.forDialect(
                dialect = DatabaseDialect.POSTGRESQL,
                sequenceRef = pgRef("noisy_seq"),
                probe = SequenceCurrentValueProbeResult.Read(value = 7L, isCalled = null),
            )
        }
        ex.message!! shouldContain "noisy_seq"
        ex.message!! shouldContain "isCalled"
    }

    test("MySQL: UPDATE dmg_sequences SET next_value=… WHERE name=… AND managed_by IN(…) AND format_version IN(…);") {
        val sql = AtomicPreserveRestoreSql.forDialect(
            dialect = DatabaseDialect.MYSQL,
            sequenceRef = mysqlRef(),
            probe = SequenceCurrentValueProbeResult.Read(value = 99L, isCalled = null),
        )
        sql.size shouldBe 1
        val s = sql.single()
        s shouldContain "UPDATE `dmg_sequences`"
        s shouldContain "`next_value` = 99"
        s shouldContain "`name` = 'order_seq'"
        s shouldContain "`managed_by` IN ('d-migrate')"
        s shouldContain "`format_version` IN ('mysql-sequence-v1')"
    }

    test("SQLite: UPDATE \"dmg_sequences\" SET \"next_value\" = … WHERE \"name\" = …;") {
        val sql = AtomicPreserveRestoreSql.forDialect(
            dialect = DatabaseDialect.SQLITE,
            sequenceRef = sqliteRef(),
            probe = SequenceCurrentValueProbeResult.Read(value = 142L, isCalled = null),
        )
        sql.size shouldBe 1
        val s = sql.single()
        s shouldContain "UPDATE \"dmg_sequences\" SET \"next_value\" = 142"
        s shouldContain "WHERE \"name\" = 'items_seq'"
    }

    fun sequence(increment: Long = 1L, cycle: Boolean = false, min: Long? = null, max: Long? = null) =
        SequenceDefinition(start = 1L, increment = increment, minValue = min, maxValue = max, cycle = cycle)

    test("MSSQL: RESTART WITH continues AFTER the probed value, not ON it") {
        // `sys.sequences.current_value` ist der zuletzt ausgegebene Wert.
        // Ihn unveraendert zurueckzuschreiben gaebe ihn ein zweites Mal aus —
        // bei einer Schluesselspalte ein Duplikat.
        val sql = AtomicPreserveRestoreSql.forDialect(
            dialect = DatabaseDialect.MSSQL,
            sequenceRef = mssqlRef(),
            probe = SequenceCurrentValueProbeResult.Read(value = 41L, isCalled = null),
            sequence = sequence(),
        )
        sql shouldBe listOf("ALTER SEQUENCE [invoice_seq] RESTART WITH 42;")
    }

    test("MSSQL: the step is the sequence's own, not 1") {
        val sql = AtomicPreserveRestoreSql.forDialect(
            dialect = DatabaseDialect.MSSQL,
            sequenceRef = mssqlRef(),
            probe = SequenceCurrentValueProbeResult.Read(value = 100L, isCalled = null),
            sequence = sequence(increment = 5L),
        )
        sql shouldBe listOf("ALTER SEQUENCE [invoice_seq] RESTART WITH 105;")
    }

    test("MSSQL: a cycling sequence at its edge wraps to the opposite bound") {
        // `RESTART WITH` macht den Umbruch nicht selbst, und ausserhalb der
        // Schranken lehnt SQL Server ihn ab.
        val sql = AtomicPreserveRestoreSql.forDialect(
            dialect = DatabaseDialect.MSSQL,
            sequenceRef = mssqlRef(),
            probe = SequenceCurrentValueProbeResult.Read(value = 10L, isCalled = null),
            sequence = sequence(cycle = true, min = 1L, max = 10L),
        )
        sql shouldBe listOf("ALTER SEQUENCE [invoice_seq] RESTART WITH 1;")
    }

    test("MSSQL: an exhausted non-cycling sequence is a named refusal, not a guess") {
        val ex = shouldThrow<IllegalArgumentException> {
            AtomicPreserveRestoreSql.forDialect(
                dialect = DatabaseDialect.MSSQL,
                sequenceRef = mssqlRef(),
                probe = SequenceCurrentValueProbeResult.Read(value = 10L, isCalled = null),
                sequence = sequence(min = 1L, max = 10L),
            )
        }
        ex.message!! shouldContain "exhausted"
    }

    test("MSSQL: without the definition the resume point is unknowable — and says so") {
        val ex = shouldThrow<IllegalArgumentException> {
            AtomicPreserveRestoreSql.forDialect(
                dialect = DatabaseDialect.MSSQL,
                sequenceRef = mssqlRef(),
                probe = SequenceCurrentValueProbeResult.Read(value = 1L, isCalled = null),
            )
        }
        ex.message!! shouldContain "needs the sequence definition"
    }

    test("Oracle: RESTART START WITH takes LAST_NUMBER as it stands") {
        // Live gemessen: `LAST_NUMBER` ist bereits der naechste auszugebende
        // Wert (nach `NEXTVAL` = 41 steht es auf 42). Ihn zu versetzen
        // verschenkte bei jedem Preserve einen Wert.
        val sql = AtomicPreserveRestoreSql.forDialect(
            dialect = DatabaseDialect.ORACLE,
            sequenceRef = oracleRef(),
            probe = SequenceCurrentValueProbeResult.Read(value = 42L, isCalled = null),
            sequence = sequence(),
        )
        sql shouldBe listOf("ALTER SEQUENCE \"invoice_seq\" RESTART START WITH 42")
    }

    test("Oracle: a sequence that cannot resume is a named refusal") {
        val ex = shouldThrow<IllegalArgumentException> {
            AtomicPreserveRestoreSql.forDialect(
                dialect = DatabaseDialect.ORACLE,
                sequenceRef = oracleRef(),
                probe = SequenceCurrentValueProbeResult.Read(value = 11L, isCalled = null),
                sequence = sequence(min = 1L, max = 10L),
            )
        }
        ex.message!! shouldContain "cannot resume"
    }
})
