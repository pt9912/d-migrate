package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.mysql.MySQLContainer
import java.sql.DriverManager

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice F follow-up
 * (2026-05-20): live-MySQL integration coverage for
 * `MysqlSequenceCanonicityProbeAdapter`. Pins the probe's
 * INFORMATION_SCHEMA + SHOW CREATE behaviour against a real
 * MySQL 8 instance so the unit-tests' MockK doubles can't drift
 * out of sync with what mysql actually returns.
 *
 * The container hosts the canonical helper-table bootstrap
 * (`dmg_sequences` table + `dmg_nextval` / `dmg_setval` routines)
 * via the DDL-generator's full-schema output (the same SQL the
 * production migration path emits). Each test mutates a specific
 * canonical object to a known-bad state, runs the probe, and
 * pins the resulting Declaration's status / kind / driftField.
 */
class MysqlSequenceCanonicityProbeIntegrationTest : FunSpec({

    val container = MySQLContainer("mysql:8")
        .withDatabaseName("drift")
        .withUsername("test")
        .withPassword("test")
        .withCommand("--log-bin-trust-function-creators=1")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun conn() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    fun exec(sql: String) {
        conn().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    fun bootstrapCanonical() {
        // Canonical column shape per `MysqlSequenceCanonicityProbeAdapter.probeSupportTable`.
        exec("DROP TABLE IF EXISTS `dmg_sequences`")
        exec(
            """
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
            )
            """.trimIndent(),
        )

        exec("DROP FUNCTION IF EXISTS `dmg_nextval`")
        exec(
            """
            CREATE FUNCTION `dmg_nextval`(seq_name VARCHAR(255)) RETURNS BIGINT
                NOT DETERMINISTIC
                MODIFIES SQL DATA
            BEGIN
                /* d-migrate:mysql-sequence-v1 object=nextval */
                DECLARE next_val BIGINT;
                UPDATE `dmg_sequences` SET `next_value` = `next_value` + `increment_by`
                    WHERE `name` = seq_name;
                SELECT `next_value` - `increment_by` INTO next_val FROM `dmg_sequences`
                    WHERE `name` = seq_name;
                RETURN next_val;
            END
            """.trimIndent(),
        )

        exec("DROP FUNCTION IF EXISTS `dmg_setval`")
        exec(
            """
            CREATE FUNCTION `dmg_setval`(seq_name VARCHAR(255), new_value BIGINT) RETURNS BIGINT
                NOT DETERMINISTIC
                MODIFIES SQL DATA
            BEGIN
                /* d-migrate:mysql-sequence-v1 object=setval */
                UPDATE `dmg_sequences` SET `next_value` = new_value WHERE `name` = seq_name;
                RETURN new_value;
            END
            """.trimIndent(),
        )

        exec(
            "INSERT INTO `dmg_sequences` (`managed_by`, `format_version`, `name`, `next_value`, " +
                "`increment_by`, `min_value`, `max_value`, `cycle_enabled`, `cache_size`) " +
                "VALUES ('d-migrate', 'mysql-sequence-v1', 'invoice_seq', 1000, 1, NULL, NULL, 0, NULL)",
        )

        exec("DROP TABLE IF EXISTS `invoices`")
        exec(
            "CREATE TABLE `invoices` (`id` BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "`invoice_number` BIGINT, `description` VARCHAR(200))",
        )
        val triggerName = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        exec("DROP TRIGGER IF EXISTS `$triggerName`")
        exec(
            """
            CREATE TRIGGER `$triggerName`
                BEFORE INSERT ON `invoices`
                FOR EACH ROW
            BEGIN
                /* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=invoice_seq table=invoices column=invoice_number */
                IF NEW.`invoice_number` IS NULL THEN
                    SET NEW.`invoice_number` = `dmg_nextval`('invoice_seq');
                END IF;
            END
            """.trimIndent(),
        )
    }

    beforeEach { bootstrapCanonical() }

    test("probeSupportTable on canonical bootstrap → CANONICAL") {
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSupportTable("op-1")
            decl.status shouldBe MysqlSequenceCanonicityStatus.CANONICAL
            decl.kind shouldBe MysqlSequenceCanonicityKind.SUPPORT_TABLE
            decl.objectName shouldBe MysqlSequenceNaming.SUPPORT_TABLE
        }
    }

    test("probeSupportTable on missing table → MISSING") {
        exec("DROP TABLE `dmg_sequences`")
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSupportTable("op-1")
            decl.status shouldBe MysqlSequenceCanonicityStatus.MISSING
        }
    }

    test("probeSupportTable with column-nullability drift → DRIFT with cache_size nullable field") {
        // Switch cache_size from INT NULL → INT NOT NULL — the
        // canonical signature expects NULLABLE for cache_size.
        // The seed row carries cache_size = NULL, which the ALTER
        // would reject; backfill before the schema change so the
        // drift the test pins is the column-nullability one.
        exec("UPDATE `dmg_sequences` SET `cache_size` = 0 WHERE `cache_size` IS NULL")
        exec("ALTER TABLE `dmg_sequences` MODIFY `cache_size` INT NOT NULL")
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSupportTable("op-1")
            decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
            decl.driftField shouldContain "cache_size"
        }
    }

    test("probeSupportTable on a dmg_sequences without PRIMARY KEY → DRIFT primary_key") {
        // Rebuild the table without the PK on `name`. The plan's
        // canonical contract requires `PRIMARY KEY (name)` — a
        // table with the right columns but no PK must not pass.
        exec("DROP TABLE `dmg_sequences`")
        exec(
            """
            CREATE TABLE `dmg_sequences` (
                `managed_by` VARCHAR(32) NOT NULL,
                `format_version` VARCHAR(32) NOT NULL,
                `name` VARCHAR(255) NOT NULL,
                `next_value` BIGINT NOT NULL,
                `increment_by` BIGINT NOT NULL,
                `min_value` BIGINT NULL,
                `max_value` BIGINT NULL,
                `cycle_enabled` TINYINT(1) NOT NULL,
                `cache_size` INT NULL
            )
            """.trimIndent(),
        )
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSupportTable("op-1")
            decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
            decl.driftField shouldBe "primary_key"
            decl.expected shouldBe "name"
        }
    }

    test("probeRoutine(NEXTVAL) on canonical body → CANONICAL") {
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeRoutine(
                "op-1", MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE,
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.CANONICAL
            decl.kind shouldBe MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE
        }
    }

    test("probeRoutine(NEXTVAL) on body without canonical marker → DRIFT body_marker") {
        // Replace dmg_nextval with a body that omits the
        // `d-migrate:mysql-sequence-v1 object=nextval` marker.
        exec("DROP FUNCTION `dmg_nextval`")
        exec(
            """
            CREATE FUNCTION `dmg_nextval`(seq_name VARCHAR(255)) RETURNS BIGINT
                NOT DETERMINISTIC
                MODIFIES SQL DATA
            BEGIN
                DECLARE v BIGINT;
                UPDATE `dmg_sequences` SET `next_value` = `next_value` + `increment_by` WHERE `name` = seq_name;
                SELECT `next_value` - `increment_by` INTO v FROM `dmg_sequences` WHERE `name` = seq_name;
                RETURN v;
            END
            """.trimIndent(),
        )
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeRoutine(
                "op-1", MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE,
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
            decl.driftField shouldBe "body_marker"
        }
    }

    test("probeRoutine(SETVAL) on missing routine → MISSING") {
        exec("DROP FUNCTION `dmg_setval`")
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeRoutine(
                "op-1", MysqlSequenceCanonicityKind.SETVAL_ROUTINE,
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.MISSING
        }
    }

    test("probeSequenceRow with matching managed fields → CANONICAL") {
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSequenceRow(
                operationId = "op-1",
                sequenceName = "invoice_seq",
                expectedIncrement = 1L,
                expectedMinValue = null,
                expectedMaxValue = null,
                expectedCycle = false,
                expectedCache = null,
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.CANONICAL
            decl.kind shouldBe MysqlSequenceCanonicityKind.SEQUENCE_ROW
            decl.objectName shouldBe "invoice_seq"
        }
    }

    test("probeSequenceRow with increment_by drift → DRIFT with field-level diff") {
        exec("UPDATE `dmg_sequences` SET `increment_by` = 5 WHERE `name` = 'invoice_seq'")
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSequenceRow(
                operationId = "op-1",
                sequenceName = "invoice_seq",
                expectedIncrement = 1L,
                expectedMinValue = null,
                expectedMaxValue = null,
                expectedCycle = false,
                expectedCache = null,
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
            decl.driftField shouldBe "increment_by"
            decl.expected shouldBe "1"
            decl.actual shouldBe "5"
        }
    }

    test("probeSequenceRow with missing row → MISSING") {
        exec("DELETE FROM `dmg_sequences` WHERE `name` = 'invoice_seq'")
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSequenceRow(
                operationId = "op-1",
                sequenceName = "invoice_seq",
                expectedIncrement = 1L,
                expectedMinValue = null,
                expectedMaxValue = null,
                expectedCycle = false,
                expectedCache = null,
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.MISSING
        }
    }

    test("probeSupportTrigger on canonical trigger → CANONICAL") {
        val triggerName = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSupportTrigger(
                operationId = "op-1",
                triggerName = triggerName,
                expectedSequenceName = "invoice_seq",
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.CANONICAL
            decl.kind shouldBe MysqlSequenceCanonicityKind.SUPPORT_TRIGGER
        }
    }

    test("probeSupportTrigger pointing at a different sequence → DRIFT") {
        val triggerName = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        // Re-emit the trigger with a body that resolves a
        // different sequence. The body marker is intact; only the
        // referenced sequence name drifts.
        exec("DROP TRIGGER `$triggerName`")
        exec(
            """
            CREATE TRIGGER `$triggerName`
                BEFORE INSERT ON `invoices`
                FOR EACH ROW
            BEGIN
                /* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=other_seq table=invoices column=invoice_number */
                IF NEW.`invoice_number` IS NULL THEN
                    SET NEW.`invoice_number` = `dmg_nextval`('other_seq');
                END IF;
            END
            """.trimIndent(),
        )
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSupportTrigger(
                operationId = "op-1",
                triggerName = triggerName,
                expectedSequenceName = "invoice_seq",
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
        }
    }

    test("probeSupportTrigger on missing trigger → MISSING") {
        val triggerName = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        exec("DROP TRIGGER `$triggerName`")
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSupportTrigger(
                operationId = "op-1",
                triggerName = triggerName,
                expectedSequenceName = "invoice_seq",
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.MISSING
        }
    }
})
