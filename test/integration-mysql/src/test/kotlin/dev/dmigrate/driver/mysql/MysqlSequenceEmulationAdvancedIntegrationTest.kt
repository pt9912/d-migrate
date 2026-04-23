package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.cli.commands.DiffView
import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaCompareDocument
import dev.dmigrate.cli.commands.SchemaCompareRequest
import dev.dmigrate.cli.commands.SchemaCompareRunner
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.DriverManager

/**
 * Integration test for MySQL sequence emulation via helper_table mode.
 * Runs against a real MySQL container via Testcontainers.
 *
 * Validates:
 * - Generated DDL executes successfully against MySQL
 * - dmg_nextval() returns monotonically increasing values
 * - INSERT without explicit value fills column via trigger
 * - Explicit NULL triggers the same path (lossy W115 semantics)
 * - Rollback DDL drops all support objects cleanly
 */
private val IntegrationTag = NamedTag("integration")

class MysqlSequenceEmulationAdvancedIntegrationTest : FunSpec({

    tags(IntegrationTag)

    val container = MySQLContainer("mysql:8")
        .withDatabaseName("seqtest")
        .withUsername("test")
        .withPassword("test")
        .withCommand("--log-bin-trust-function-creators=1")

    afterSpec { container.stop() }

    fun conn() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    val generator = MysqlDdlGenerator()
    val reader = MysqlSchemaReader()
    val helperOpts = DdlGenerationOptions(mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE)
    val supportTriggerName = MysqlSequenceNaming.triggerName("invoices", "invoice_number")

    val schema = SchemaDefinition(
        name = "SeqIntegration", version = "1",
        sequences = mapOf("invoice_seq" to SequenceDefinition(start = 1000, increment = 1)),
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

    beforeSpec {
        container.start()
        val ddl = generator.generate(schema, helperOpts).render()
        conn().use { c ->
            for (block in splitMysqlStatements(ddl)) {
                if (block.isNotBlank()) {
                    c.createStatement().use { it.execute(block) }
                }
            }
        }
    }

    fun recreateCanonicalSupportTrigger() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DROP TRIGGER IF EXISTS `$supportTriggerName`")
                stmt.execute(
                    """
                    CREATE TRIGGER `$supportTriggerName`
                        BEFORE INSERT ON `invoices`
                        FOR EACH ROW
                    BEGIN
                        /* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=invoice_seq table=invoices column=invoice_number */
                        IF NEW.`invoice_number` IS NULL THEN
                            SET NEW.`invoice_number` = `dmg_nextval`('invoice_seq');
                        END IF;
                    END
                    """.trimIndent()
                )
            }
        }
    }

    fun resetSequenceState(nextValue: Long) {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    "UPDATE `${MysqlSequenceNaming.SUPPORT_TABLE}` " +
                        "SET `next_value` = $nextValue WHERE `name` = 'invoice_seq'"
                )
            }
        }
    }

    fun resetSequenceMetadata(
        nextValue: Long,
        incrementBy: Long = 1L,
        minValue: Long? = null,
        maxValue: Long? = null,
        cycleEnabled: Boolean = false,
        cacheSize: Int? = null,
    ) {
        fun sql(value: Any?): String = when (value) {
            null -> "NULL"
            is Boolean -> if (value) "1" else "0"
            else -> value.toString()
        }

        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    "UPDATE `${MysqlSequenceNaming.SUPPORT_TABLE}` " +
                        "SET `next_value` = ${sql(nextValue)}, " +
                        "`increment_by` = ${sql(incrementBy)}, " +
                        "`min_value` = ${sql(minValue)}, " +
                        "`max_value` = ${sql(maxValue)}, " +
                        "`cycle_enabled` = ${sql(cycleEnabled)}, " +
                        "`cache_size` = ${sql(cacheSize)} " +
                        "WHERE `name` = 'invoice_seq'"
                )
            }
        }
    }

    fun readLiveMysqlOperand(reference: String = "db:seqtest"): ResolvedSchemaOperand {
        val pool = object : dev.dmigrate.driver.connection.ConnectionPool {
            override val dialect = dev.dmigrate.driver.DatabaseDialect.MYSQL
            override fun borrow() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
            override fun activeConnections() = 0
            override fun close() {}
        }
        val result = reader.read(pool, dev.dmigrate.driver.SchemaReadOptions(includeTriggers = true))
        return ResolvedSchemaOperand(
            reference = reference,
            schema = result.schema,
            validation = dev.dmigrate.core.validation.SchemaValidator().validate(result.schema),
            notes = result.notes,
            skippedObjects = result.skippedObjects,
        )
    }

    test("compare reverse snapshot reports only sequencesChanged for live sequence drift") {
        recreateCanonicalSupportTrigger()
        resetSequenceState(nextValue = 1000L)
        val sourceSnapshot = readLiveMysqlOperand(reference = "/tmp/source.yaml")
        resetSequenceState(nextValue = 2000L)

        var capturedDoc: SchemaCompareDocument? = null
        val runner = SchemaCompareRunner(
            fileLoader = { sourceSnapshot },
            dbLoader = { _, _ -> readLiveMysqlOperand() },
            comparator = { left, right -> SchemaComparator().compare(left, right) },
            projectDiff = { DiffView() },
            renderPlain = { doc ->
                capturedDoc = doc
                doc.status
            },
            renderJson = { error("unused in integration test") },
            renderYaml = { error("unused in integration test") },
            printError = { message, source ->
                throw AssertionError("Unexpected compare error for $source: $message")
            },
            stdout = {},
            stderr = {},
        )

        runner.execute(
            SchemaCompareRequest(
                source = "/tmp/source.yaml",
                target = "db:seqtest",
                output = null,
                outputFormat = "plain",
                quiet = false,
            )
        ) shouldBe 1

        capturedDoc!!.status shouldBe "different"
        capturedDoc!!.summary.sequencesChanged shouldBe 1
        capturedDoc!!.summary.tablesChanged shouldBe 0
        capturedDoc!!.summary.tablesAdded shouldBe 0
        capturedDoc!!.summary.tablesRemoved shouldBe 0
        capturedDoc!!.summary.functionsAdded shouldBe 0
        capturedDoc!!.summary.functionsRemoved shouldBe 0
        capturedDoc!!.summary.functionsChanged shouldBe 0
        capturedDoc!!.summary.triggersAdded shouldBe 0
        capturedDoc!!.summary.triggersRemoved shouldBe 0
        capturedDoc!!.summary.triggersChanged shouldBe 0
        capturedDoc!!.targetOperand!!.notes.none { it.code == "W116" } shouldBe true
    }

    test("compare reverse snapshot reports sequence metadata drift as pure sequencesChanged") {
        recreateCanonicalSupportTrigger()
        resetSequenceMetadata(
            nextValue = 1000L,
            incrementBy = 1L,
            minValue = null,
            maxValue = null,
            cycleEnabled = false,
            cacheSize = null,
        )
        val sourceSnapshot = readLiveMysqlOperand(reference = "/tmp/source.yaml")

        try {
            resetSequenceMetadata(
                nextValue = 1000L,
                incrementBy = 5L,
                minValue = 500L,
                maxValue = 99999L,
                cycleEnabled = true,
                cacheSize = 20,
            )

            val live = readLiveMysqlOperand()
            val seq = live.schema.sequences["invoice_seq"]!!
            seq.increment shouldBe 5L
            seq.minValue shouldBe 500L
            seq.maxValue shouldBe 99999L
            seq.cycle shouldBe true
            seq.cache shouldBe 20

            var capturedDoc: SchemaCompareDocument? = null
            val runner = SchemaCompareRunner(
                fileLoader = { sourceSnapshot },
                dbLoader = { _, _ -> readLiveMysqlOperand() },
                comparator = { left, right -> SchemaComparator().compare(left, right) },
                projectDiff = { DiffView() },
                renderPlain = { doc ->
                    capturedDoc = doc
                    doc.status
                },
                renderJson = { error("unused in integration test") },
                renderYaml = { error("unused in integration test") },
                printError = { message, source ->
                    throw AssertionError("Unexpected compare error for $source: $message")
                },
                stdout = {},
                stderr = {},
            )

            runner.execute(
                SchemaCompareRequest(
                    source = "/tmp/source.yaml",
                    target = "db:seqtest",
                    output = null,
                    outputFormat = "plain",
                    quiet = false,
                )
            ) shouldBe 1

            capturedDoc!!.status shouldBe "different"
            capturedDoc!!.summary.sequencesChanged shouldBe 1
            capturedDoc!!.summary.tablesChanged shouldBe 0
            capturedDoc!!.summary.tablesAdded shouldBe 0
            capturedDoc!!.summary.tablesRemoved shouldBe 0
            capturedDoc!!.summary.functionsAdded shouldBe 0
            capturedDoc!!.summary.functionsRemoved shouldBe 0
            capturedDoc!!.summary.functionsChanged shouldBe 0
            capturedDoc!!.summary.triggersAdded shouldBe 0
            capturedDoc!!.summary.triggersRemoved shouldBe 0
            capturedDoc!!.summary.triggersChanged shouldBe 0
            capturedDoc!!.targetOperand!!.notes.none { it.code == "W116" } shouldBe true
        } finally {
            resetSequenceMetadata(
                nextValue = 1000L,
                incrementBy = 1L,
                minValue = null,
                maxValue = null,
                cycleEnabled = false,
                cacheSize = null,
            )
        }
    }

    test("reverse tolerates formatting and quoting differences in support trigger") {
        try {
            conn().use { c ->
                c.createStatement().use { stmt ->
                    stmt.execute("DROP TRIGGER IF EXISTS `$supportTriggerName`")
                    stmt.execute(
                        """
                        CREATE TRIGGER `$supportTriggerName`
                            BEFORE INSERT ON `invoices`
                            FOR EACH ROW
                        BEGIN
                            /* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=invoice_seq table=invoices column=invoice_number */

                            IF   NEW.`invoice_number`   IS   NULL   THEN
                                SET
                                    NEW.`invoice_number`
                                    =
                                    `dmg_nextval`('`seqtest`.`invoice_seq`');
                            END IF;
                        END
                        """.trimIndent()
                    )
                }
            }

            val pool = object : dev.dmigrate.driver.connection.ConnectionPool {
                override val dialect = dev.dmigrate.driver.DatabaseDialect.MYSQL
                override fun borrow() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
                override fun activeConnections() = 0
                override fun close() {}
            }
            val result = reader.read(pool, dev.dmigrate.driver.SchemaReadOptions(includeTriggers = true))

            result.schema.sequences.containsKey("invoice_seq") shouldBe true
            val col = result.schema.tables["invoices"]?.columns?.get("invoice_number")
            (col?.default is dev.dmigrate.core.model.DefaultValue.SequenceNextVal) shouldBe true
            result.schema.triggers.keys.none { it.contains("dmg_seq_") } shouldBe true
            result.notes.none { it.code == "W116" && it.objectName == "invoices.invoice_number" } shouldBe true
        } finally {
            recreateCanonicalSupportTrigger()
        }
    }

    // ── PF1: round-trip neutral → generate → MySQL → reverse ──

    test("PF1: round-trip neutral sequence definition survives generate-reverse cycle") {
        recreateCanonicalSupportTrigger()
        resetSequenceState(nextValue = 1000L)

        val reversed = readLiveMysqlOperand()

        // Sequence definition survives round-trip
        val originalSeq = schema.sequences["invoice_seq"]!!
        val reversedSeq = reversed.schema.sequences["invoice_seq"]!!
        reversedSeq.increment shouldBe originalSeq.increment
        reversedSeq.start shouldBe originalSeq.start

        // Column default survives
        val reversedDefault = reversed.schema.tables["invoices"]!!.columns["invoice_number"]!!.default
        reversedDefault shouldBe DefaultValue.SequenceNextVal("invoice_seq")

        // Support objects invisible
        reversed.schema.tables.keys.none { it == MysqlSequenceNaming.SUPPORT_TABLE } shouldBe true
        reversed.schema.functions.keys.none { it.startsWith("dmg_") } shouldBe true
        reversed.schema.triggers.keys.none { it.contains("dmg_seq_") } shouldBe true

        // No W116 on intact round-trip
        reversed.notes.none { it.code == "W116" } shouldBe true
    }

    // ── PF3: missing support routines degradation ──

    test("PF3: missing support routines produce W116 against real MySQL") {
        try {
            conn().use { c ->
                c.createStatement().use { stmt ->
                    stmt.execute("DROP FUNCTION IF EXISTS `dmg_nextval`")
                    stmt.execute("DROP FUNCTION IF EXISTS `dmg_setval`")
                }
            }

            val reversed = readLiveMysqlOperand()

            // Sequences still reconstructed from dmg_sequences
            reversed.schema.sequences.containsKey("invoice_seq") shouldBe true

            // W116 emitted because routines are missing
            reversed.notes.any { it.code == "W116" } shouldBe true
        } finally {
            // Restore routines by re-executing full DDL (ignoring already-exists errors)
            val ddl = generator.generate(schema, helperOpts).render()
            conn().use { c ->
                for (block in splitMysqlStatements(ddl)) {
                    if (block.isNotBlank()) {
                        try { c.createStatement().use { it.execute(block) } }
                        catch (_: Exception) { /* object may already exist */ }
                    }
                }
            }
            recreateCanonicalSupportTrigger()
        }
    }

    // ── PF12: multiple sequences in different tables ──

    test("PF12: multiple sequences in different tables reverse independently") {
        recreateCanonicalSupportTrigger()
        val ticketTriggerName = MysqlSequenceNaming.triggerName("tickets", "ticket_number")
        try {
            conn().use { c ->
                c.createStatement().use { stmt ->
                    stmt.execute(
                        "INSERT INTO `${MysqlSequenceNaming.SUPPORT_TABLE}` " +
                            "(`managed_by`, `format_version`, `name`, `next_value`, `increment_by`, " +
                            "`min_value`, `max_value`, `cycle_enabled`, `cache_size`) " +
                            "VALUES ('d-migrate', 'mysql-sequence-v1', 'ticket_seq', 500, 5, 500, 99999, 0, 10)"
                    )
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS `tickets` (" +
                            "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                            "`ticket_number` BIGINT)"
                    )
                    stmt.execute("DROP TRIGGER IF EXISTS `$ticketTriggerName`")
                }
                c.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TRIGGER `$ticketTriggerName`
                            BEFORE INSERT ON `tickets`
                            FOR EACH ROW
                        BEGIN
                            /* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=ticket_seq table=tickets column=ticket_number */
                            IF NEW.`ticket_number` IS NULL THEN
                                SET NEW.`ticket_number` = `dmg_nextval`('ticket_seq');
                            END IF;
                        END
                        """.trimIndent()
                    )
                }
            }

            val reversed = readLiveMysqlOperand()

            // Both sequences reconstructed
            reversed.schema.sequences.containsKey("invoice_seq") shouldBe true
            reversed.schema.sequences.containsKey("ticket_seq") shouldBe true

            val ticketSeq = reversed.schema.sequences["ticket_seq"]!!
            ticketSeq.increment shouldBe 5L
            ticketSeq.start shouldBe 500L

            // Both column defaults set
            reversed.schema.tables["invoices"]!!.columns["invoice_number"]!!.default shouldBe
                DefaultValue.SequenceNextVal("invoice_seq")
            reversed.schema.tables["tickets"]!!.columns["ticket_number"]!!.default shouldBe
                DefaultValue.SequenceNextVal("ticket_seq")

            // No W116 — both fully intact
            reversed.notes.none { it.code == "W116" } shouldBe true
        } finally {
            conn().use { c ->
                c.createStatement().use { stmt ->
                    stmt.execute("DROP TRIGGER IF EXISTS `$ticketTriggerName`")
                    stmt.execute("DROP TABLE IF EXISTS `tickets`")
                    stmt.execute("DELETE FROM `${MysqlSequenceNaming.SUPPORT_TABLE}` WHERE `name` = 'ticket_seq'")
                }
            }
        }
    }

    // ── PF13: shared sequence across multiple tables ──

    test("PF13: shared sequence across multiple tables reverses to same sequence") {
        recreateCanonicalSupportTrigger()
        val receiptTriggerName = MysqlSequenceNaming.triggerName("receipts", "receipt_number")
        try {
            conn().use { c ->
                c.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS `receipts` (" +
                            "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                            "`receipt_number` BIGINT)"
                    )
                    stmt.execute("DROP TRIGGER IF EXISTS `$receiptTriggerName`")
                }
                c.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TRIGGER `$receiptTriggerName`
                            BEFORE INSERT ON `receipts`
                            FOR EACH ROW
                        BEGIN
                            /* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=invoice_seq table=receipts column=receipt_number */
                            IF NEW.`receipt_number` IS NULL THEN
                                SET NEW.`receipt_number` = `dmg_nextval`('invoice_seq');
                            END IF;
                        END
                        """.trimIndent()
                    )
                }
            }

            val reversed = readLiveMysqlOperand()

            // Sequence exists exactly once
            reversed.schema.sequences.keys.count { it == "invoice_seq" } shouldBe 1

            // Both columns in different tables point to same sequence
            reversed.schema.tables["invoices"]!!.columns["invoice_number"]!!.default shouldBe
                DefaultValue.SequenceNextVal("invoice_seq")
            reversed.schema.tables["receipts"]!!.columns["receipt_number"]!!.default shouldBe
                DefaultValue.SequenceNextVal("invoice_seq")

            // No W116
            reversed.notes.none { it.code == "W116" } shouldBe true
        } finally {
            conn().use { c ->
                c.createStatement().use { stmt ->
                    stmt.execute("DROP TRIGGER IF EXISTS `$receiptTriggerName`")
                    stmt.execute("DROP TABLE IF EXISTS `receipts`")
                }
            }
        }
    }

    // ── existing degradation tests ──

    test("reverse treats markerless support-like trigger as degraded without SequenceNextVal") {
        try {
            conn().use { c ->
                c.createStatement().use { stmt ->
                    stmt.execute("DROP TRIGGER IF EXISTS `$supportTriggerName`")
                    stmt.execute(
                        """
                        CREATE TRIGGER `$supportTriggerName`
                            BEFORE INSERT ON `invoices`
                            FOR EACH ROW
                        BEGIN
                            IF NEW.`invoice_number` IS NULL THEN
                                SET NEW.`invoice_number` = `dmg_nextval`('invoice_seq');
                            END IF;
                        END
                        """.trimIndent()
                    )
                }
            }

            val pool = object : dev.dmigrate.driver.connection.ConnectionPool {
                override val dialect = dev.dmigrate.driver.DatabaseDialect.MYSQL
                override fun borrow() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
                override fun activeConnections() = 0
                override fun close() {}
            }
            val result = reader.read(pool, dev.dmigrate.driver.SchemaReadOptions(includeTriggers = true))

            result.schema.sequences.containsKey("invoice_seq") shouldBe true
            result.schema.tables["invoices"]!!.columns["invoice_number"]!!.default shouldBe null
            result.notes.any { it.code == "W116" && it.objectName == "invoices.invoice_number" } shouldBe true
            result.schema.triggers.keys.any { it.contains(supportTriggerName) } shouldBe true
        } finally {
            recreateCanonicalSupportTrigger()
        }
    }
})

/**
 * Splits MySQL DDL output handling DELIMITER blocks.
 * Blocks between `DELIMITER //` and `DELIMITER ;` are treated as single statements.
 */
internal fun splitMysqlStatements(ddl: String): List<String> {
    val result = mutableListOf<String>()
    var inDelimiter = false
    val current = StringBuilder()

    for (line in ddl.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.equals("DELIMITER //", ignoreCase = true) -> {
                inDelimiter = true
                current.clear()
            }
            trimmed.equals("DELIMITER ;", ignoreCase = true) -> {
                inDelimiter = false
                val block = current.toString().trim().removeSuffix("//").trim()
                if (block.isNotBlank()) result += block
                current.clear()
            }
            inDelimiter -> current.appendLine(line)
            trimmed.endsWith(";") && trimmed.length > 1 -> {
                current.append(line.removeSuffix(";"))
                val stmt = current.toString().trim()
                if (stmt.isNotBlank()) result += stmt
                current.clear()
            }
            trimmed.startsWith("--") -> { /* skip comments */ }
            else -> current.appendLine(line)
        }
    }
    val remaining = current.toString().trim()
    if (remaining.isNotBlank() && !remaining.startsWith("--")) result += remaining
    return result
}
