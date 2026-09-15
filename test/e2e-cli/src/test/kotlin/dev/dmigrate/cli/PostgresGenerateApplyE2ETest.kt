package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import dev.dmigrate.format.yaml.YamlSchemaCodec
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.testcontainers.containers.Container
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile

/**
 * Das von `schema generate --target postgresql` geschriebene Skript wird **als
 * Datei** mit `psql` angewendet und danach zurueckgelesen.
 *
 * **Warum es diesen Test braucht.** Die uebrigen Pruefungen vergleichen Text
 * (Unit-Tests) oder Schemata (Compare). Ob PostgreSQL die DDL **annimmt**,
 * sagt keine davon — und PostgreSQL ist der Dialekt mit den meisten
 * Konstrukten, die einzeln scheitern koennen: berechnete Spalten, Enums als
 * eigener Typ, Sequenzen, `GENERATED … AS IDENTITY`, Partitionsklauseln.
 *
 * Der Ausloeser war ein Konsumenten-Schema, dessen MySQL-DDL am Server
 * scheiterte (`ERROR 1170`), waehrend die Unit-Tests gruen waren. Fuer SQL
 * Server und Oracle gibt es diesen Test seit laengerem; fuer PostgreSQL
 * fehlte er.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class PostgresGenerateApplyE2ETest : FunSpec({

    val container = PostgreSQLContainer(TestImages.POSTGRESQL)
        .withDatabaseName(DATABASE).withUsername(USER).withPassword(PASSWORD)

    lateinit var tmp: Path

    fun dmigUrl(): String =
        "postgresql://$USER:$PASSWORD@${container.host}:${container.firstMappedPort}/$DATABASE"

    beforeSpec {
        container.start()
        tmp = Files.createTempDirectory("dmigrate-e2e-pg-apply-")
    }

    afterSpec {
        container.stop()
        tmp.deleteRecursively()
    }

    fun psql(sql: String): Container.ExecResult = container.execInContainer(
        "psql", "-U", USER, "-d", DATABASE, "-v", "ON_ERROR_STOP=1", "-c", sql,
    )

    /** Raeumt zwischen den Specs auf — sie teilen sich einen Container. */
    fun resetDatabase() {
        val tables = psql(
            "SELECT tablename FROM pg_tables WHERE schemaname='public'",
        ).stdout.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("(") }
            .filterNot { it == "tablename" || it.startsWith("-") }
        if (tables.isEmpty()) return
        psql(tables.joinToString("") { "DROP TABLE IF EXISTS \"$it\" CASCADE; " })
        psql("DROP TYPE IF EXISTS order_status CASCADE")
    }

    /** Der erzeugte Stand, so wie ihn der Reader sieht. */
    fun reversed(script: Path): dev.dmigrate.core.model.SchemaDefinition {
        resetDatabase()
        container.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/schema.sql")
        val apply = container.execInContainer(
            "sh", "-c", "psql -U $USER -d $DATABASE -v ON_ERROR_STOP=1 -f /tmp/schema.sql",
        )
        withClue("apply stdout:\n${apply.stdout}\nstderr:\n${apply.stderr}") {
            // Der Punkt des Tests: die DDL muss **durchlaufen**.
            apply.exitCode shouldBe 0
        }
        val out = tmp.resolve("reversed.yaml")
        val reverse = runRealCli(
            listOf("schema", "reverse", "--source", dmigUrl(), "--output", out.absolutePathString()),
        )
        withClue("reverse stderr:\n${reverse.stderr}") { reverse.exitCode shouldBe 0 }
        return YamlSchemaCodec().read(out)
    }

    fun generate(schema: String): Pair<Int, Path> {
        val yaml = tmp.resolve("schema.yaml").apply { writeText(schema) }
        val script = tmp.resolve("schema.sql")
        val gen = runRealCli(
            listOf(
                "schema", "generate", "--source", yaml.absolutePathString(),
                "--target", "postgresql", "--output", script.absolutePathString(), "--deterministic",
            ),
        )
        return gen.exitCode to script
    }

    /**
     * Das Geruest des Konsumenten-Schemas: Enum-Typ, berechnete Spalte,
     * Identity, Fremdschluessel, CHECK und Index — die Konstrukte, die einzeln
     * scheitern koennen, in einer Anweisungsfolge.
     */
    test("the full construct mix applies via psql and reverses back") {
        val (exit, script) = generate(SCHEMA_FULL)
        withClue("generate exit=$exit") { exit shouldBe 0 }

        val schema = reversed(script)

        schema.tables.keys shouldContainAll listOf("customers", "order_items")
        schema.customTypes.keys shouldContainAll listOf("order_status")
        schema.tables.getValue("order_items").constraints.map { it.name }
            .shouldContainAll(listOf("order_items_quantity_check"))
        schema.tables.getValue("order_items").indices.map { it.name }
            .shouldContainAll(listOf("ix_items_order"))
        // Die berechnete Spalte ueberlebt den Weg — der Server rechnet sie.
        val lineTotal = schema.tables.getValue("order_items").columns.getValue("line_total")
        (lineTotal.generation != null) shouldBe true
    }

    /** Die Gegenprobe zum Identitaets-Modus: `BY DEFAULT` kommt so zurueck. */
    test("identity and check survive and the computed column actually computes") {
        val (exit, script) = generate(SCHEMA_FULL)
        withClue("generate exit=$exit") { exit shouldBe 0 }
        // Ein zweites Anwenden braucht den aufgeraeumten Stand.
        resetDatabase()
        container.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/schema2.sql")
        val apply = container.execInContainer(
            "sh", "-c", "psql -U $USER -d $DATABASE -v ON_ERROR_STOP=1 -f /tmp/schema2.sql",
        )
        withClue("apply stderr:\n${apply.stderr}") { apply.exitCode shouldBe 0 }

        psql("INSERT INTO customers (id, email) VALUES (1, 'a@b.c')").exitCode shouldBe 0
        psql("INSERT INTO order_items (id, order_id, quantity, unit_price) VALUES (1, 1, 3, 7.00)").exitCode shouldBe 0
        psql("SELECT line_total FROM order_items WHERE id = 1").stdout shouldContain "21"
    }
})

private const val DATABASE = "dmigrate_apply"
private const val USER = "dmigrate"
private const val PASSWORD = "DMigrate_E2E_Pa55word"

private val SCHEMA_FULL = """
    schema_format: "1.0"
    name: apply_probe
    version: 1.0.0
    custom_types:
      order_status:
        kind: enum
        values: [NEW, PAID, SHIPPED, CANCELLED]
    tables:
      customers:
        columns:
          id: { type: identifier, auto_increment: true }
          email: { type: text, max_length: 255, required: true, unique: true, unique_constraint: uq_customer_email }
        primary_key: [id]
      order_items:
        columns:
          id: { type: identifier, auto_increment: true }
          order_id: { type: integer, required: true }
          quantity: { type: integer, required: true }
          unit_price: { type: decimal, precision: 12, scale: 2, required: true }
          line_total:
            type: decimal
            precision: 14
            scale: 2
            generation:
              type: computed
              expression: "quantity * unit_price"
              stored: true
        primary_key: [id]
        constraints:
          - name: order_items_quantity_check
            type: check
            expression: "quantity > 0"
        indices:
          - name: ix_items_order
            columns: [order_id]
""".trimIndent()
