package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName
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

class MysqlSequenceEmulationIntegrationTest : FunSpec({

    tags(IntegrationTag)

    val container = MySQLContainer(DockerImageName.parse("mysql:8"))
        .withDatabaseName("seqtest")
        .withUsername("test")
        .withPassword("test")
        .withCommand("--log-bin-trust-function-creators=1")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun conn() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    val generator = MysqlDdlGenerator()
    val helperOpts = DdlGenerationOptions(mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE)

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

    test("generated DDL executes against real MySQL") {
        val result = generator.generate(schema, helperOpts)
        val ddl = result.render()
        conn().use { c ->
            // Execute each statement (split on semicolons, handle DELIMITER)
            val statements = splitMysqlStatements(ddl)
            for (block in statements) {
                if (block.isNotBlank()) {
                    try {
                        c.createStatement().use { it.execute(block) }
                    } catch (e: Exception) {
                        println("FAILED SQL BLOCK:\n---\n$block\n---\nERROR: ${e.message}")
                        throw AssertionError("Failed to execute SQL:\n---\n$block\n---\nError: ${e.message}", e)
                    }
                }
            }
        }
    }

    test("dmg_nextval returns monotonically increasing values") {
        conn().use { c ->
            val values = (1..3).map {
                c.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT `dmg_nextval`('invoice_seq')")
                    rs.next()
                    rs.getLong(1)
                }
            }
            values shouldBe listOf(1000L, 1001L, 1002L)
        }
    }

    test("INSERT without explicit value fills column via trigger") {
        conn().use { c ->
            c.createStatement().use { it.execute("INSERT INTO `invoices` (`description`) VALUES ('test1')") }
            val num = c.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT `invoice_number` FROM `invoices` WHERE `description` = 'test1'")
                rs.next()
                rs.getLong(1)
            }
            // Should be the next value after the 3 calls above
            num shouldBe 1003L
        }
    }

    test("explicit NULL triggers same path (lossy W115 semantics)") {
        conn().use { c ->
            c.createStatement().use {
                it.execute("INSERT INTO `invoices` (`invoice_number`, `description`) VALUES (NULL, 'test-null')")
            }
            val num = c.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT `invoice_number` FROM `invoices` WHERE `description` = 'test-null'")
                rs.next()
                rs.getLong(1)
            }
            num shouldBe 1004L
        }
    }

    test("rollback DDL drops all support objects cleanly") {
        val rollback = generator.generateRollback(schema, helperOpts)
        val rollbackDdl = rollback.render()
        conn().use { c ->
            for (block in splitMysqlStatements(rollbackDdl)) {
                if (block.isNotBlank()) {
                    c.createStatement().use { it.execute(block) }
                }
            }
            // Verify dmg_sequences no longer exists
            val rs = c.createStatement().executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'dmg_sequences' AND table_schema = 'seqtest'"
            )
            rs.next()
            rs.getInt(1) shouldBe 0
        }
    }
})

/**
 * Splits MySQL DDL output handling DELIMITER blocks.
 * Blocks between `DELIMITER //` and `DELIMITER ;` are treated as single statements.
 */
private fun splitMysqlStatements(ddl: String): List<String> {
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
