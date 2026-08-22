package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.yaml.YamlSchemaCodec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText

/**
 * MSSQL Slice 1a (docs/planning/in-progress/mssql-dialect-scoping.md):
 * `schema reverse` als Subprozess-E2E gegen die ECHTE CLI und einen
 * SQL-Server-Testcontainer — der nutzersichtbare MSSQL-Pfad von der
 * `mssql://`-URL ueber `RuntimeBootstrap`/ServiceLoader bis zur
 * geschriebenen Schema-Datei, in einem frischen Prozess.
 *
 * Unit-/Integrationstests in `driver-mssql` und `test/integration-mssql`
 * pruefen Reader und Lister in-process; dieser Spec beweist das Ende-zu-
 * Ende-Verhalten der CLI (Kommandozeile, Treiber-Registrierung, Exit-Code,
 * Sidecar-Report).
 *
 * EULA: `acceptLicense()` = `ACCEPT_EULA=Y` (docs/user/quality.md).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class MssqlSchemaReverseE2ETest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withPassword(PASSWORD)
        // mssql-jdbc >= 10 setzt encrypt=true; der Container hat nur ein
        // Self-Signed-Zertifikat.
        .withUrlParam("encrypt", "false")

    lateinit var tmp: Path

    fun dmigUrl(): String =
        "mssql://${container.username}:$PASSWORD@${container.host}:" +
            "${container.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT)}/$DATABASE?encrypt=false"

    beforeSpec {
        container.start()
        tmp = Files.createTempDirectory("dmigrate-e2e-mssql-reverse-")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $DATABASE") }
            conn.catalog = DATABASE
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE customers (
                        id INT IDENTITY(1,1) PRIMARY KEY,
                        name NVARCHAR(100) NOT NULL,
                        email NVARCHAR(254) NULL,
                        active BIT NOT NULL DEFAULT ((1)),
                        CONSTRAINT uq_customers_email UNIQUE (email)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE orders (
                        id BIGINT IDENTITY(1,1) PRIMARY KEY,
                        customer_id INT NOT NULL,
                        amount DECIMAL(10,2) NOT NULL,
                        CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id)
                            REFERENCES customers(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    afterSpec {
        container.stop()
        tmp.deleteRecursively()
    }

    test("schema reverse via the real CLI writes schema + report for an mssql source") {
        val schemaYaml = tmp.resolve("schema.yaml")
        val run = runRealCli(
            listOf(
                "schema", "reverse",
                "--source", dmigUrl(),
                "--output", schemaYaml.absolutePathString(),
            ),
        )
        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 0
        }
        Files.exists(schemaYaml) shouldBe true
        Files.exists(tmp.resolve("schema.report.yaml")) shouldBe true

        val schema = YamlSchemaCodec().read(schemaYaml)
        schema.tables.keys shouldContainAll listOf("customers", "orders")

        val customers = schema.tables.getValue("customers")
        customers.primaryKey shouldBe listOf("id")
        customers.columns.getValue("id").type shouldBe NeutralType.Identifier(autoIncrement = true)
        customers.columns.getValue("name").let {
            it.type shouldBe NeutralType.Text(100)
            it.required shouldBe true
        }
        customers.columns.getValue("active").type shouldBe NeutralType.BooleanType
        customers.columns.getValue("email").unique shouldBe true

        val fk = schema.tables.getValue("orders").constraints.first { it.name == "fk_orders_customer" }
        fk.references.shouldNotBeNull().table shouldBe "customers"

        // Dialekt-Herkunft bleibt im Reverse-Default-Namen sichtbar.
        schemaYaml.readText() shouldContain "mssql"
    }

    test("schema reverse via the real CLI fails closed on a wrong password") {
        val schemaYaml = tmp.resolve("never-written.yaml")
        val badUrl = dmigUrl().replace(PASSWORD, "Wrong_Pa55word_E2E")
        val run = runRealCli(
            listOf("schema", "reverse", "--source", badUrl, "--output", schemaYaml.absolutePathString()),
        )
        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            // 4 = connection error (SchemaReverseRunner-Exit-Vertrag); das
            // Passwort darf weder aus der URL-Referenz noch aus der Treiber-
            // Meldung auf stderr durchschlagen (ConnectionSecretMasker).
            run.exitCode shouldBe 4
            run.stderr shouldNotContain "Wrong_Pa55word_E2E"
        }
        Files.exists(schemaYaml) shouldBe false
    }
})

private const val DATABASE = "dmigrate_e2e"

/** Erfuellt die SQL-Server-Komplexitaetsregel (Gross, Klein, Ziffer, Symbol) und ist URL-sicher. */
private const val PASSWORD = "DMigrate_E2E_Pa55word"
