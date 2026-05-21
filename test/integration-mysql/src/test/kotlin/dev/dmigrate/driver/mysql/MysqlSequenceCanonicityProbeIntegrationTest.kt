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
import io.kotest.matchers.shouldNotBe
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

    // ── SQL identifier edge cases (2026-05-21 follow-up) ────────
    //
    // The plan called out trigger-, routine-, and table-name
    // randfälle as the area most likely to silently regress when
    // the renderer's naming logic and the probe's catalog lookup
    // drift apart. Each test below bootstraps a non-canonical
    // shape (long identifiers, mixed-case identifiers, multi-row
    // helper table, SETVAL drift) and pins that the probe still
    // matches the renderer's view of the canonical world.

    fun bootstrapFromSchema(schema: SchemaDefinition) {
        // Mirror of bootstrapCanonical(), parameterised by schema
        // so identifier-edge-case tests can drive the DDL generator
        // with their own fixture without colliding with the
        // canonical setup.
        exec("DROP TABLE IF EXISTS `dmg_sequences`")
        exec("DROP FUNCTION IF EXISTS `dmg_nextval`")
        exec("DROP FUNCTION IF EXISTS `dmg_setval`")
        val result = MysqlDdlGenerator().generate(
            schema,
            DdlGenerationOptions(mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE),
        )
        for (block in splitMysqlStatements(result.render())) {
            if (block.isNotBlank()) {
                conn().use { c -> c.createStatement().use { it.execute(block) } }
            }
        }
    }

    test("probeSupportTrigger on a long table/column name → CANONICAL via hash10-based trigger name") {
        // Both identifiers exceed the 16-char truncation budget the
        // naming scheme uses for the `table16`/`column16` segments.
        // The probe MUST resolve the trigger by the same canonical
        // name the renderer produced (`dmg_seq_<16>_<16>_<hash10>_bi`)
        // — a regression that drops the hash segment would resolve
        // a different (or no) trigger and falsely surface MISSING.
        val longTable = "extremely_long_table_with_lots_of_chars"
        val longColumn = "very_long_column_name_for_sequence_default"
        val longSchema = SchemaDefinition(
            name = "LongIT", version = "1",
            sequences = mapOf("long_seq" to SequenceDefinition(start = 1L, increment = 1L)),
            tables = mapOf(longTable to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                    longColumn to ColumnDefinition(
                        type = NeutralType.BigInteger,
                        default = DefaultValue.SequenceNextVal("long_seq"),
                    ),
                ),
                primaryKey = listOf("id"),
            )),
        )
        // Tear down the canonical bootstrap from beforeEach and
        // replace with the long-name schema.
        val canonicalTrigger = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        exec("DROP TRIGGER IF EXISTS `$canonicalTrigger`")
        exec("DROP TABLE IF EXISTS `invoices`")
        bootstrapFromSchema(longSchema)

        val triggerName = MysqlSequenceNaming.triggerName(longTable, longColumn)
        // Sanity-check the format the production naming helper
        // produced — if the layout ever changes, this test fails
        // loudly rather than silently mismatching the probe.
        triggerName.length shouldBe 55
        triggerName.startsWith("dmg_seq_") shouldBe true
        triggerName.endsWith("_bi") shouldBe true

        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSupportTrigger(
                operationId = "op-long",
                triggerName = triggerName,
                expectedSequenceName = "long_seq",
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.CANONICAL
            decl.kind shouldBe MysqlSequenceCanonicityKind.SUPPORT_TRIGGER
            decl.objectName shouldBe triggerName
        }
    }

    test("triggerName hash10 distinguishes columns that share a 16-char prefix") {
        // Two columns whose normalised names share the first 16
        // characters MUST produce different trigger names because
        // the `_<hash10>_bi` segment differs. This is the contract
        // the probe relies on when the renderer emits two triggers
        // against one table — a hash regression would resolve both
        // probes to the same trigger row.
        val sharedPrefix = "transaction_amount"
        val name1 = "${sharedPrefix}_eur_total"
        val name2 = "${sharedPrefix}_usd_total"
        val trig1 = MysqlSequenceNaming.triggerName("ledger", name1)
        val trig2 = MysqlSequenceNaming.triggerName("ledger", name2)
        // Both column16 segments equal the same 16-char prefix
        // (transaction_amou), so the only distinguishing factor is
        // the hash10. Pinning the inequality protects against a
        // regression that would only keep the truncated segment.
        trig1 shouldNotBe trig2
        // And the canonical 55-char layout still holds.
        trig1.length shouldBe 55
        trig2.length shouldBe 55
    }

    test("probeSequenceRow targets one specific row when dmg_sequences holds many") {
        // The helper table accumulates one row per managed
        // sequence. The probe MUST find the row by exact `name`
        // match — a regression that joins on a substring or
        // surrogate id would mis-attribute drift to the wrong
        // sequence.
        exec(
            """
            INSERT INTO `dmg_sequences` (
                `managed_by`, `format_version`, `name`, `next_value`,
                `increment_by`, `min_value`, `max_value`, `cycle_enabled`, `cache_size`
            ) VALUES
                ('d-migrate', 'v1', 'invoice_seq_archive', 1, 1, NULL, NULL, 0, NULL),
                ('d-migrate', 'v1', 'order_seq',           1, 1, NULL, NULL, 0, NULL)
            """.trimIndent(),
        )
        // Drift just the OTHER sequence's increment_by. The probe
        // for `invoice_seq` MUST still see CANONICAL because each
        // row is independent.
        exec("UPDATE `dmg_sequences` SET `increment_by` = 7 WHERE `name` = 'order_seq'")
        conn().use { c ->
            val invoiceDecl = MysqlSequenceCanonicityProbeAdapter(c).probeSequenceRow(
                operationId = "op-invoice",
                sequenceName = "invoice_seq",
                expectedIncrement = 1L,
                expectedMinValue = null,
                expectedMaxValue = null,
                expectedCycle = false,
                expectedCache = null,
            )
            invoiceDecl.status shouldBe MysqlSequenceCanonicityStatus.CANONICAL

            val orderDecl = MysqlSequenceCanonicityProbeAdapter(c).probeSequenceRow(
                operationId = "op-order",
                sequenceName = "order_seq",
                expectedIncrement = 1L,
                expectedMinValue = null,
                expectedMaxValue = null,
                expectedCycle = false,
                expectedCache = null,
            )
            orderDecl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
            orderDecl.driftField shouldBe "increment_by"
            orderDecl.actual shouldBe "7"
        }
    }

    test("probeSequenceRow against a non-existent sequence name returns MISSING (not DRIFT) even when dmg_sequences has rows") {
        // A sequence that never appeared in the helper table is
        // MISSING, not DRIFT — the latter would suggest a value
        // mismatch where there is no value to compare. The gate
        // routing depends on this distinction (CreateSequence
        // accepts MISSING, AlterSequence/DropSequence(row) block).
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSequenceRow(
                operationId = "op-unknown",
                sequenceName = "never_inserted_seq",
                expectedIncrement = 1L,
                expectedMinValue = null,
                expectedMaxValue = null,
                expectedCycle = false,
                expectedCache = null,
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.MISSING
            decl.objectName shouldBe "never_inserted_seq"
        }
    }

    test("probeRoutine(SETVAL): marker intact but body rewritten → DRIFT body_signature") {
        // Symmetric counterpart to the existing NEXTVAL body-
        // signature drift case. SETVAL is the second routine
        // managed by the emulation and has its own marker —
        // catching only the NEXTVAL drift would leave a regression
        // surface where an operator-modified SETVAL renders the
        // sequence pool inconsistent.
        exec("DROP FUNCTION `dmg_setval`")
        exec(
            """
            CREATE FUNCTION `dmg_setval`(seq_name VARCHAR(255), v BIGINT) RETURNS BIGINT
                DETERMINISTIC
                MODIFIES SQL DATA
            BEGIN
                /* d-migrate:mysql-sequence-v1 object=setval */
                /* operator-added: clamp to a hard ceiling */
                UPDATE `dmg_sequences`
                    SET `next_value` = LEAST(v, 999999999)
                    WHERE `name` = seq_name;
                RETURN v;
            END
            """.trimIndent(),
        )
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeRoutine(
                "op-setval", MysqlSequenceCanonicityKind.SETVAL_ROUTINE,
            )
            decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
            decl.driftField shouldBe "body_signature"
            decl.kind shouldBe MysqlSequenceCanonicityKind.SETVAL_ROUTINE
        }
    }

    test("probeSupportTable accepts a canonical table when extra non-canonical rows are present") {
        // The probe inspects the COLUMN SIGNATURE, not row content.
        // Inserting unrelated rows MUST NOT flip the table-level
        // probe to DRIFT — that would conflate row drift with
        // schema drift and surface the wrong diagnostic code.
        exec(
            """
            INSERT INTO `dmg_sequences` (
                `managed_by`, `format_version`, `name`, `next_value`,
                `increment_by`, `min_value`, `max_value`, `cycle_enabled`, `cache_size`
            ) VALUES ('d-migrate', 'v1', 'aux_seq', 1, 1, NULL, NULL, 0, NULL)
            """.trimIndent(),
        )
        conn().use { c ->
            val decl = MysqlSequenceCanonicityProbeAdapter(c).probeSupportTable("op-tab-extra-rows")
            decl.status shouldBe MysqlSequenceCanonicityStatus.CANONICAL
        }
    }
})
