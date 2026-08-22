package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import dev.dmigrate.format.yaml.YamlSchemaCodec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.Container
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * MSSQL Slice 2a: das von `schema generate --target mssql` geschriebene Skript
 * wird **als Datei** mit `sqlcmd` im SQL-Server-Container angewendet — dem
 * Client, der Batches nur an `GO`-Zeilen trennt (T-SQL verlangt, dass
 * `CREATE OR ALTER VIEW` allein in seinem Batch steht). Anschliessend liest
 * `schema reverse` den Stand zurueck. Damit ist der Datei-/Tool-Export-Pfad
 * (nicht nur die statementweise Ausfuehrung des d-migrate-Runners) belegt.
 *
 * EULA: `acceptLicense()` = `ACCEPT_EULA=Y` (docs/user/quality.md).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class MssqlGenerateApplyE2ETest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withPassword(PASSWORD)
        .withUrlParam("encrypt", "false")

    lateinit var tmp: Path

    fun dmigUrl(): String =
        "mssql://${container.username}:$PASSWORD@${container.host}:" +
            "${container.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT)}/$DATABASE?encrypt=false"

    beforeSpec {
        container.start()
        tmp = Files.createTempDirectory("dmigrate-e2e-mssql-apply-")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $DATABASE") }
        }
    }

    afterSpec {
        container.stop()
        tmp.deleteRecursively()
    }

    fun sqlcmd(vararg args: String): Container.ExecResult {
        // mssql-tools18 (2022-Image) mit -C (Self-Signed-Zertifikat vertrauen); Fallback auf mssql-tools.
        val tools18 = container.execInContainer("/opt/mssql-tools18/bin/sqlcmd", "-C", *args)
        if (tools18.exitCode == 0 || !tools18.stderr.contains("No such file", ignoreCase = true)) return tools18
        return container.execInContainer("/opt/mssql-tools/bin/sqlcmd", *args)
    }

    test("generated script applies via sqlcmd (GO batches) and reverses back") {
        val schemaYaml = tmp.resolve("schema.yaml").apply { writeText(SCHEMA) }
        val script = tmp.resolve("schema.sql")
        val generate = runRealCli(
            listOf(
                "schema", "generate",
                "--source", schemaYaml.absolutePathString(),
                "--target", "mssql",
                "--output", script.absolutePathString(),
                "--deterministic",
            ),
        )
        withClue("--- stdout ---\n${generate.stdout}\n--- stderr ---\n${generate.stderr}") {
            generate.exitCode shouldBe 0
        }
        script.readText() shouldContain "\nGO\n"

        container.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/schema.sql")
        val apply = sqlcmd(
            "-S", "localhost", "-U", container.username, "-P", PASSWORD,
            "-d", DATABASE, "-b", "-i", "/tmp/schema.sql",
        )
        withClue("sqlcmd stdout:\n${apply.stdout}\nstderr:\n${apply.stderr}") {
            apply.exitCode shouldBe 0
        }

        val reversed = tmp.resolve("reversed.yaml")
        val reverse = runRealCli(
            listOf(
                "schema", "reverse",
                "--source", dmigUrl(),
                "--output", reversed.absolutePathString(),
                "--include-views",
            ),
        )
        withClue("--- stdout ---\n${reverse.stdout}\n--- stderr ---\n${reverse.stderr}") {
            reverse.exitCode shouldBe 0
        }
        val schema = YamlSchemaCodec().read(reversed)
        schema.tables.keys shouldContainAll listOf("customers", "orders")
        schema.views.keys shouldContainAll listOf("active_customers")
        schema.sequences.keys shouldContainAll listOf("order_seq")
        schema.tables.getValue("orders").indices.map { it.name } shouldContainAll listOf("idx_orders_customer")
        schema.tables.getValue("orders").constraints.map { it.name } shouldContainAll listOf("fk_orders_customer_id")
    }
})

private const val DATABASE = "dmigrate_apply"
private const val PASSWORD = "DMigrate_E2E_Pa55word"

private val SCHEMA = """
    schema_format: "1.0"
    name: "mssql-apply-e2e"
    version: "1.0.0"

    sequences:
      order_seq:
        start: 100
        increment: 1

    tables:
      customers:
        columns:
          id:
            type: identifier
            auto_increment: true
          email:
            type: text
            max_length: 254
            required: true
            unique: true
          created_at:
            type: datetime
            timezone: true
            default: current_timestamp
        primary_key: [id]
      orders:
        columns:
          id:
            type: identifier
            auto_increment: true
          customer_id:
            type: integer
            required: true
            references:
              table: customers
              column: id
          number:
            type: biginteger
            default:
              sequence_nextval: order_seq
          state:
            type: enum
            values: [open, paid, shipped]
            required: true
        primary_key: [id]
        indices:
          - name: idx_orders_customer
            columns: [customer_id]
          - name: idx_orders_open
            columns: [state]
            where: "[state] = N'open'"

    views:
      active_customers:
        query: "SELECT id, email FROM customers"
        dependencies:
          tables: [customers]
""".trimIndent()
