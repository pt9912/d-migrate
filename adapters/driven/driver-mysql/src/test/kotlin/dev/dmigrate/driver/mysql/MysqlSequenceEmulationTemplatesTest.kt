package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.SequenceDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * E.3 MySQL Sequence-Diff Sub-Slice A: pins the SQL shape emitted by
 * the extracted helper-table templates. These tests live next to the
 * templates so any DDL drift caused by future edits surfaces here
 * (Sub-Slice B will then verify the diff renderer's wrapping; the
 * DDL-Generator pipeline already pins its own end-to-end behaviour
 * through the MySQL emulation tests).
 */
class MysqlSequenceEmulationTemplatesTest : FunSpec({

    fun backtickQuote(name: String): String = "`$name`"

    test("supportTableSql renders the canonical dmg_sequences DDL") {
        val sql = MysqlSequenceEmulationTemplates.supportTableSql(::backtickQuote)
        sql shouldContain "CREATE TABLE `dmg_sequences`"
        sql shouldContain "`managed_by` VARCHAR(32) NOT NULL"
        sql shouldContain "`format_version` VARCHAR(32) NOT NULL"
        sql shouldContain "`next_value` BIGINT NOT NULL"
        sql shouldContain "`increment_by` BIGINT NOT NULL"
        sql shouldContain "`min_value` BIGINT NULL"
        sql shouldContain "`max_value` BIGINT NULL"
        sql shouldContain "`cycle_enabled` TINYINT(1) NOT NULL"
        sql shouldContain "`cache_size` INT NULL"
        sql shouldContain "PRIMARY KEY (`name`)"
        sql shouldContain "ENGINE=InnoDB"
    }

    test("sequenceSeedSql carries managed_by / format_version + sequence defaults") {
        val seq = SequenceDefinition(start = 100L, increment = 2L, minValue = 1L, maxValue = 999L, cycle = true, cache = 50)
        val sql = MysqlSequenceEmulationTemplates.sequenceSeedSql("order_seq", seq, ::backtickQuote)
        sql shouldContain "INSERT INTO `dmg_sequences`"
        sql shouldContain "'d-migrate', 'mysql-sequence-v1', 'order_seq', 100, 2, 1, 999, 1, 50"
    }

    test("sequenceSeedSql falls back to start=1 / increment=1 / NULL bounds when fields are unset") {
        val seq = SequenceDefinition()
        val sql = MysqlSequenceEmulationTemplates.sequenceSeedSql("anon", seq, ::backtickQuote)
        sql shouldContain "'d-migrate', 'mysql-sequence-v1', 'anon', 1, 1, NULL, NULL, 0, NULL"
    }

    test("nextvalRoutineSql wraps the increment-and-return body in DELIMITER //") {
        val sql = MysqlSequenceEmulationTemplates.nextvalRoutineSql(::backtickQuote)
        sql shouldContain "DELIMITER //"
        sql shouldContain "CREATE FUNCTION `dmg_nextval`(seq_name VARCHAR(255))"
        sql shouldContain "DETERMINISTIC"
        sql shouldContain "MODIFIES SQL DATA"
        sql shouldContain "object=nextval"
        sql shouldContain "UPDATE `dmg_sequences` SET `next_value` = `next_value` + `increment_by`"
        sql.endsWith("DELIMITER ;") shouldBe true
    }

    test("setvalRoutineSql sets next_value to the operator-supplied argument") {
        val sql = MysqlSequenceEmulationTemplates.setvalRoutineSql(::backtickQuote)
        sql shouldContain "CREATE FUNCTION `dmg_setval`(seq_name VARCHAR(255), new_value BIGINT)"
        sql shouldContain "object=setval"
        sql shouldContain "SET `next_value` = new_value WHERE `name` = seq_name"
    }

    test("sequenceTriggerSql emits BEFORE INSERT body with marker comment + NULL guard") {
        val spec = MysqlSequenceEmulationTemplates.SequenceTriggerSpec(
            tableName = "orders",
            columnName = "id",
            sequenceName = "order_seq",
        )
        val triggerName = MysqlSequenceNaming.triggerName("orders", "id")
        val sql = MysqlSequenceEmulationTemplates.sequenceTriggerSql(spec, triggerName, ::backtickQuote)
        sql shouldContain "CREATE TRIGGER `$triggerName`"
        sql shouldContain "BEFORE INSERT ON `orders`"
        sql shouldContain "FOR EACH ROW"
        sql shouldContain "object=sequence-trigger"
        sql shouldContain "sequence=order_seq"
        sql shouldContain "table=orders"
        sql shouldContain "column=id"
        sql shouldContain "IF NEW.`id` IS NULL THEN"
        sql shouldContain "SET NEW.`id` = `dmg_nextval`('order_seq');"
    }

    test("templates are independent of the quote function — a custom quoter shows up verbatim") {
        // Verifies the templates do not hard-code backticks anywhere.
        val custom: (String) -> String = { "<<$it>>" }
        val sql = MysqlSequenceEmulationTemplates.supportTableSql(custom)
        sql shouldContain "CREATE TABLE <<dmg_sequences>>"
        sql shouldContain "<<managed_by>>"
    }
})
