package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
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

    val canonicalSchema = SchemaDefinition(
        name = "DriftIT", version = "1",
        sequences = mapOf("invoice_seq" to SequenceDefinition(start = 1000L, increment = 1L)),
        tables = mapOf("invoices" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "invoice_number" to ColumnDefinition(
                    type = NeutralType.BigInteger,
                    default = DefaultValue.SequenceNextVal("invoice_seq"),
                ),
                "description" to ColumnDefinition(type = NeutralType.Text(maxLength = 200)),
            ),
            primaryKey = listOf("id"),
        )),
    )

    fun bootstrapCanonical() {
        // Tear down any leftovers from a previous test so each
        // case starts on a clean slate.
        val triggerName = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        exec("DROP TRIGGER IF EXISTS `$triggerName`")
        exec("DROP TABLE IF EXISTS `invoices`")
        exec("DROP FUNCTION IF EXISTS `dmg_nextval`")
        exec("DROP FUNCTION IF EXISTS `dmg_setval`")
        exec("DROP TABLE IF EXISTS `dmg_sequences`")

        // Drive the production DDL generator so the routine /
        // trigger bodies installed here are byte-equivalent to
        // what the runtime renderer would emit. That makes the
        // probe's body-signature check pin a real canonical
        // shape, not a hand-rolled stand-in.
        val result = MysqlDdlGenerator().generate(
            canonicalSchema,
            DdlGenerationOptions(mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE),
        )
        for (block in splitMysqlStatements(result.render())) {
            if (block.isNotBlank()) {
                conn().use { c -> c.createStatement().use { it.execute(block) } }
            }
        }
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

    test("probeSupportTrigger pointing at a different sequence (marker too) → DRIFT body_marker") {
        val triggerName = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        // Both the marker and the dmg_nextval call mention a
        // different sequence. The marker-mismatch is the first
        // signal the gate sees.
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
            decl.driftField shouldBe "body_marker"
        }
    }

    test("probeSupportTrigger with intact marker but redirected nextval call → DRIFT sequence_reference") {
        val triggerName = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        // Plan-Doc §1.4 calls this out explicitly: operator keeps
        // the marker pointing at the original sequence but flips
        // the dmg_nextval call to a different one. Before the
        // Sub-Slice F follow-up this drifted past the probe as
        // CANONICAL.
        exec("DROP TRIGGER `$triggerName`")
        exec(
            """
            CREATE TRIGGER `$triggerName`
                BEFORE INSERT ON `invoices`
                FOR EACH ROW
            BEGIN
                /* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=invoice_seq table=invoices column=invoice_number */
                IF NEW.`invoice_number` IS NULL THEN
                    SET NEW.`invoice_number` = `dmg_nextval`('redirected_seq');
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
            decl.driftField shouldBe "sequence_reference"
        }
    }

    test("probeRoutine: marker intact but body rewritten → DRIFT body_signature") {
        // Plan-Doc §1.2: operator-modified routine body (extra
        // logging / different increment semantics) with the
        // marker comment left alone. Pre-follow-up this passed
        // the probe as CANONICAL.
        exec("DROP FUNCTION `dmg_nextval`")
        exec(
            """
            CREATE FUNCTION `dmg_nextval`(seq_name VARCHAR(255)) RETURNS BIGINT
                DETERMINISTIC
                MODIFIES SQL DATA
            BEGIN
                /* d-migrate:mysql-sequence-v1 object=nextval */
                DECLARE v BIGINT;
                /* operator-added: bump by 2 instead of increment_by */
                UPDATE `dmg_sequences` SET `next_value` = `next_value` + 2 WHERE `name` = seq_name;
                SELECT `next_value` - 2 INTO v FROM `dmg_sequences` WHERE `name` = seq_name;
                RETURN v;
            END
            """.trimIndent(),
        )
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeRoutine(
                "op-1", MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE,
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
            decl.driftField shouldBe "body_signature"
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
