package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.SequenceDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * E.3 MySQL Sequence-Diff Sub-Slice A: pins the SQL shape emitted by
 * the extracted helper-table templates. Each test compares the full
 * emitted string against an explicit snapshot so a vertauschte
 * Reihenfolge (e.g. `next_value` before `format_version`) or an
 * accidental extra blank line is caught — `shouldContain` would
 * silently accept that as long as the named substrings still
 * appeared.
 *
 * `appendLine` resolves to the platform line separator at run-time;
 * the tests normalise `\r\n` → `\n` before comparing so the snapshots
 * remain valid on dev machines that happen to use CRLF.
 */
class MysqlSequenceEmulationTemplatesTest : FunSpec({

    fun backtickQuote(name: String): String = "`$name`"

    fun normalise(sql: String): String = sql.replace("\r\n", "\n")

    test("supportTableSql renders the canonical dmg_sequences DDL byte-for-byte") {
        val expected = """
            CREATE TABLE `dmg_sequences` (
                `managed_by` VARCHAR(32) NOT NULL,
                `format_version` VARCHAR(32) NOT NULL,
                `name` VARCHAR(255) NOT NULL,
                `next_value` BIGINT NOT NULL,
                `increment_by` BIGINT NOT NULL,
                `min_value` BIGINT NULL,
                `max_value` BIGINT NULL,
                `cycle_enabled` TINYINT(1) NOT NULL,
                `cache_size` INT NULL,
                PRIMARY KEY (`name`)
            ) ENGINE=InnoDB;
        """.trimIndent()
        normalise(MysqlSequenceEmulationTemplates.supportTableSql(::backtickQuote)) shouldBe expected
    }

    test("sequenceSeedSql carries managed_by / format_version + sequence defaults") {
        val seq = SequenceDefinition(start = 100L, increment = 2L, minValue = 1L, maxValue = 999L, cycle = true, cache = 50)
        val expected = "INSERT INTO `dmg_sequences` (`managed_by`, `format_version`, `name`, " +
            "`next_value`, `increment_by`, `min_value`, `max_value`, `cycle_enabled`, " +
            "`cache_size`) VALUES ('d-migrate', 'mysql-sequence-v1', 'order_seq', 100, 2, 1, " +
            "999, 1, 50);"
        normalise(MysqlSequenceEmulationTemplates.sequenceSeedSql("order_seq", seq, ::backtickQuote)) shouldBe expected
    }

    test("sequenceSeedSql falls back to start=1 / increment=1 / NULL bounds when fields are unset") {
        val seq = SequenceDefinition()
        val expected = "INSERT INTO `dmg_sequences` (`managed_by`, `format_version`, `name`, " +
            "`next_value`, `increment_by`, `min_value`, `max_value`, `cycle_enabled`, " +
            "`cache_size`) VALUES ('d-migrate', 'mysql-sequence-v1', 'anon', 1, 1, NULL, " +
            "NULL, 0, NULL);"
        normalise(MysqlSequenceEmulationTemplates.sequenceSeedSql("anon", seq, ::backtickQuote)) shouldBe expected
    }

    test("nextvalRoutineSql wraps the increment-and-return body in DELIMITER //") {
        val expected = """
            DELIMITER //
            CREATE FUNCTION `dmg_nextval`(seq_name VARCHAR(255))
            RETURNS BIGINT
            DETERMINISTIC
            MODIFIES SQL DATA
            BEGIN
                /* d-migrate:mysql-sequence-v1 object=nextval */
                DECLARE val BIGINT;
                UPDATE `dmg_sequences` SET `next_value` = `next_value` + `increment_by` WHERE `name` = seq_name;
                SELECT `next_value` - `increment_by` INTO val FROM `dmg_sequences` WHERE `name` = seq_name;
                RETURN val;
            END //
            DELIMITER ;
        """.trimIndent()
        normalise(MysqlSequenceEmulationTemplates.nextvalRoutineSql(::backtickQuote)) shouldBe expected
    }

    test("setvalRoutineSql sets next_value to the operator-supplied argument") {
        val expected = """
            DELIMITER //
            CREATE FUNCTION `dmg_setval`(seq_name VARCHAR(255), new_value BIGINT)
            RETURNS BIGINT
            DETERMINISTIC
            MODIFIES SQL DATA
            BEGIN
                /* d-migrate:mysql-sequence-v1 object=setval */
                UPDATE `dmg_sequences` SET `next_value` = new_value WHERE `name` = seq_name;
                RETURN new_value;
            END //
            DELIMITER ;
        """.trimIndent()
        normalise(MysqlSequenceEmulationTemplates.setvalRoutineSql(::backtickQuote)) shouldBe expected
    }

    test("sequenceTriggerSql emits BEFORE INSERT body with marker comment + NULL guard") {
        val spec = MysqlSequenceTriggerSpec(
            tableName = "orders",
            columnName = "id",
            sequenceName = "order_seq",
        )
        val triggerName = MysqlSequenceNaming.triggerName("orders", "id")
        val expected = """
            DELIMITER //
            CREATE TRIGGER `$triggerName`
                BEFORE INSERT ON `orders`
                FOR EACH ROW
            BEGIN
                /* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=order_seq table=orders column=id */
                IF NEW.`id` IS NULL THEN
                    SET NEW.`id` = `dmg_nextval`('order_seq');
                END IF;
            END //
            DELIMITER ;
        """.trimIndent()
        normalise(
            MysqlSequenceEmulationTemplates.sequenceTriggerSql(spec, triggerName, ::backtickQuote),
        ) shouldBe expected
    }

    test("templates do not hard-code backticks — custom quoter shows up verbatim") {
        val custom: (String) -> String = { "<<$it>>" }
        val sql = normalise(MysqlSequenceEmulationTemplates.supportTableSql(custom))
        // Identifier list is fully driven by the custom quoter.
        sql.startsWith("CREATE TABLE <<dmg_sequences>> (") shouldBe true
        sql.contains("<<managed_by>>") shouldBe true
        sql.contains("PRIMARY KEY (<<name>>)") shouldBe true
        // Body literals (`VARCHAR`, `BIGINT`, `ENGINE=InnoDB`) are not
        // identifiers and must NOT be wrapped by the custom quoter.
        sql.contains("VARCHAR(32) NOT NULL") shouldBe true
        sql.endsWith("ENGINE=InnoDB;") shouldBe true
    }
})
