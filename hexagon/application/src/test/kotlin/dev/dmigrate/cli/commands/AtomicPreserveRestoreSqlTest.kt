package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

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

    test("MSSQL: no atomic-preserve support -- forDialect throws IllegalStateException") {
        val ex = shouldThrow<IllegalStateException> {
            AtomicPreserveRestoreSql.forDialect(
                dialect = DatabaseDialect.MSSQL,
                sequenceRef = mssqlRef(),
                probe = SequenceCurrentValueProbeResult.Read(value = 1L, isCalled = null),
            )
        }
        ex.message!! shouldContain "mssql"
    }

    test("Oracle: no atomic-preserve support -- forDialect throws IllegalStateException") {
        val ex = shouldThrow<IllegalStateException> {
            AtomicPreserveRestoreSql.forDialect(
                dialect = DatabaseDialect.ORACLE,
                sequenceRef = oracleRef(),
                probe = SequenceCurrentValueProbeResult.Read(value = 1L, isCalled = null),
            )
        }
        ex.message!! shouldContain "oracle"
    }
})
