package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.Container
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText

/**
 * MSSQL Slice 3: der vollständige Weg PostgreSQL → SQL Server über die ECHTE
 * CLI — `schema reverse` (PG), `schema generate --target mssql`, Anwenden des
 * Skripts per `sqlcmd` (GO-Batches/SET-Präambel aus Slice 2a) und
 * `data transfer` in das erzeugte Schema.
 *
 * Deckt am lebenden System ab, was Unit-Tests nicht können: IDENTITY_INSERT
 * für mitgelieferte Schlüssel, NVARCHAR/DATETIMEOFFSET/DECIMAL-Bindung über
 * Dialektgrenzen und den Reseed danach.
 *
 * EULA: `acceptLicense()` = `ACCEPT_EULA=Y` (docs/user/quality.md).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class MssqlTransferE2ETest : FunSpec({

    val source = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_src")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    val target = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withPassword(MSSQL_PASSWORD)
        .withUrlParam("encrypt", "false")

    lateinit var tmp: Path

    fun pgUrl(): String =
        "postgresql://${source.username}:${source.password}@${source.host}:${source.firstMappedPort}/${source.databaseName}"

    fun mssqlUrl(): String =
        "mssql://${target.username}:$MSSQL_PASSWORD@${target.host}:" +
            "${target.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT)}/$MSSQL_DATABASE?encrypt=false"

    fun mssqlRows(sql: String): List<List<Any?>> {
        val url = "jdbc:sqlserver://${target.host}:" +
            "${target.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT)};" +
            "databaseName=$MSSQL_DATABASE;encrypt=false"
        return DriverManager.getConnection(url, target.username, MSSQL_PASSWORD).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) })
                    }
                }
            }
        }
    }

    fun sqlcmd(vararg args: String): Container.ExecResult {
        val tools18 = target.execInContainer("/opt/mssql-tools18/bin/sqlcmd", "-C", *args)
        if (tools18.exitCode == 0 || !tools18.stderr.contains("No such file", ignoreCase = true)) return tools18
        return target.execInContainer("/opt/mssql-tools/bin/sqlcmd", *args)
    }

    beforeSpec {
        source.start()
        target.start()
        tmp = Files.createTempDirectory("dmigrate-e2e-mssql-transfer-")

        DriverManager.getConnection(
            "jdbc:postgresql://${source.host}:${source.firstMappedPort}/${source.databaseName}",
            source.username, source.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE customers (
                        id      SERIAL PRIMARY KEY,
                        email   TEXT NOT NULL UNIQUE,
                        name    VARCHAR(100) NOT NULL,
                        joined  TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE orders (
                        id          SERIAL PRIMARY KEY,
                        customer_id INTEGER NOT NULL REFERENCES customers(id),
                        amount      NUMERIC(10,2) NOT NULL,
                        note        TEXT
                    )
                    """.trimIndent(),
                )
            }
            conn.prepareStatement("INSERT INTO customers (email, name) VALUES (?, ?)").use { ps ->
                ps.setString(1, "alice@test.com"); ps.setString(2, "Alice Ähnlich"); ps.execute()
                ps.setString(1, "bob@test.com"); ps.setString(2, "Bob"); ps.execute()
            }
            conn.prepareStatement("INSERT INTO orders (customer_id, amount, note) VALUES (?, ?, ?)").use { ps ->
                ps.setInt(1, 1); ps.setBigDecimal(2, java.math.BigDecimal("99.95")); ps.setString(3, "erste"); ps.execute()
                ps.setInt(1, 1); ps.setBigDecimal(2, java.math.BigDecimal("10.50")); ps.setNull(3, java.sql.Types.VARCHAR); ps.execute()
                ps.setInt(1, 2); ps.setBigDecimal(2, java.math.BigDecimal("42.00")); ps.setString(3, "zwei"); ps.execute()
            }
        }

        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $MSSQL_DATABASE") }
        }
    }

    afterSpec {
        source.stop()
        target.stop()
        tmp.deleteRecursively()
    }

    test("postgres to sql server: reverse, generate, apply, transfer") {
        val schemaYaml = tmp.resolve("schema.yaml")
        val reverse = runRealCli(
            listOf("schema", "reverse", "--source", pgUrl(), "--output", schemaYaml.absolutePathString()),
        )
        withClue("reverse stderr:\n${reverse.stderr}") { reverse.exitCode shouldBe 0 }

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
        withClue("generate stderr:\n${generate.stderr}") { generate.exitCode shouldBe 0 }
        script.readText() shouldContain "IDENTITY(1,1)"

        target.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/transfer-schema.sql")
        val apply = sqlcmd(
            "-S", "localhost", "-U", target.username, "-P", MSSQL_PASSWORD,
            "-d", MSSQL_DATABASE, "-b", "-i", "/tmp/transfer-schema.sql",
        )
        withClue("sqlcmd stdout:\n${apply.stdout}\nstderr:\n${apply.stderr}") { apply.exitCode shouldBe 0 }

        val transfer = runRealCli(
            listOf(
                "data", "transfer",
                "--source", pgUrl(),
                "--target", mssqlUrl(),
                "--tables", "customers,orders",
            ),
        )
        withClue("transfer stdout:\n${transfer.stdout}\nstderr:\n${transfer.stderr}") {
            transfer.exitCode shouldBe 0
        }

        // Schlüssel bleiben erhalten (IDENTITY_INSERT), Unicode und Dezimalstellen auch.
        mssqlRows("SELECT id, email, name FROM customers ORDER BY id") shouldContainExactly listOf(
            listOf(1, "alice@test.com", "Alice Ähnlich"),
            listOf(2, "bob@test.com", "Bob"),
        )
        mssqlRows("SELECT id, customer_id, amount, note FROM orders ORDER BY id") shouldContainExactly listOf(
            listOf(1, 1, java.math.BigDecimal("99.95"), "erste"),
            listOf(2, 1, java.math.BigDecimal("10.50"), null),
            listOf(3, 2, java.math.BigDecimal("42.00"), "zwei"),
        )

        // Nach dem Transfer vergibt SQL Server kollisionsfrei weiter (DBCC-Reseed).
        val insertWithOutput = "INSERT INTO customers (email, name, joined) OUTPUT INSERTED.id " +
            "VALUES (N'carol@test.com', N'Carol', SYSDATETIMEOFFSET())"
        mssqlRows(insertWithOutput).single().single() shouldBe 3
    }
})

private const val MSSQL_DATABASE = "dmigrate_transfer"
private const val MSSQL_PASSWORD = "DMigrate_E2E_Pa55word"
