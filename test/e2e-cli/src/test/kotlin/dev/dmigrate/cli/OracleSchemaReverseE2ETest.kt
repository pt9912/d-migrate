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
import org.testcontainers.oracle.OracleContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText

/**
 * Oracle Slice 1a (docs/planning/in-progress/oracle-dialect-scoping.md):
 * `schema reverse` als Subprozess-E2E gegen die ECHTE CLI und einen
 * Oracle-Testcontainer — der nutzersichtbare Oracle-Pfad von der
 * `oracle://`-URL ueber `RuntimeBootstrap`/ServiceLoader bis zur
 * geschriebenen Schema-Datei, in einem frischen Prozess.
 *
 * Unit-Tests in `driver-oracle` und der Container-Spike in
 * `test/integration-oracle` pruefen Reader und Lister in-process; dieser
 * Spec beweist das Ende-zu-Ende-Verhalten der CLI (Kommandozeile,
 * Treiber-Registrierung, Exit-Code, Sidecar-Report).
 *
 * Explizit auf 23ai gepinnt (wie der Slice-0-Spike): die gleitenden
 * `slim-faststart`-Tags liefern inzwischen "26ai" aus.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class OracleSchemaReverseE2ETest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withUsername(APP_USER)
        .withPassword(APP_PASSWORD)
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var tmp: Path

    fun dmigUrl(): String =
        "oracle://$APP_USER:$APP_PASSWORD@${container.host}:" +
            "${container.oraclePort}/${container.databaseName}"

    beforeSpec {
        container.start()
        tmp = Files.createTempDirectory("dmigrate-e2e-oracle-reverse-")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE customers (
                        id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        name VARCHAR2(100) NOT NULL,
                        email VARCHAR2(254) NULL,
                        active NUMBER(1) DEFAULT 1 NOT NULL,
                        CONSTRAINT uq_customers_email UNIQUE (email)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE orders (
                        id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        customer_id NUMBER NOT NULL,
                        amount NUMBER(10,2) NOT NULL,
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

    test("schema reverse via the real CLI writes schema + report for an oracle source") {
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
        schema.tables.keys shouldContainAll listOf("CUSTOMERS", "ORDERS")

        val customers = schema.tables.getValue("CUSTOMERS")
        customers.primaryKey shouldBe listOf("ID")
        customers.columns.getValue("NAME").let {
            it.type shouldBe NeutralType.Text(100)
            it.required shouldBe true
        }
        // NUMBER(1) faltet auf Boolean (Oracles 0/1-Konvention).
        customers.columns.getValue("ACTIVE").type shouldBe NeutralType.BooleanType
        customers.columns.getValue("EMAIL").unique shouldBe true

        val fk = schema.tables.getValue("ORDERS").constraints.first { it.name == "FK_ORDERS_CUSTOMER" }
        fk.references.shouldNotBeNull().table shouldBe "CUSTOMERS"

        // Dialekt-Herkunft bleibt im Reverse-Default-Namen sichtbar.
        schemaYaml.readText() shouldContain "oracle"
    }

    test("schema reverse via the real CLI fails closed on a wrong password") {
        val schemaYaml = tmp.resolve("never-written.yaml")
        val badUrl = dmigUrl().replace(":$APP_PASSWORD@", ":Wrong_Pa55word_E2E@")
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

/** Ohne `.withUsername`/`.withPassword` generiert `OracleContainer` ein zufaelliges Passwort. */
private const val APP_USER = "dmig_e2e"
private const val APP_PASSWORD = "DMigrate_E2E_Pa55word"
