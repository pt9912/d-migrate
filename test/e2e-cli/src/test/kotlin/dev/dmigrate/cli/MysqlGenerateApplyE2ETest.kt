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
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.MountableFile

/**
 * Das von `schema generate --target mysql` geschriebene Skript wird **als
 * Datei** mit dem `mysql`-Client im Container angewendet und danach
 * zurueckgelesen.
 *
 * **Warum es diesen Test braucht.** Alle anderen Pruefungen vergleichen Text
 * (Unit-Tests) oder Schemata (Compare). Ob MySQL die DDL **annimmt**, sagt
 * keine davon. Ein Konsumenten-Schema hat genau das gezeigt: die erzeugte DDL
 * enthielt `CONSTRAINT … UNIQUE (email)` auf einer `TEXT`-Spalte, und MySQL
 * lehnte die **ganze Anweisung** ab (`ERROR 1170: BLOB/TEXT column 'email'
 * used in key specification without a key length`). Die Unit-Tests waren dabei
 * gruen — sie prueften den Ausdruck, nicht die Annehmbarkeit.
 *
 * Fuer SQL Server und Oracle gibt es diesen Test seit laengerem
 * ([MssqlGenerateApplyE2ETest], `OracleSchemaGenerateE2ETest`); fuer MySQL
 * und PostgreSQL fehlte er.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class MysqlGenerateApplyE2ETest : FunSpec({

    val container = MySQLContainer(TestImages.MYSQL)
        .withDatabaseName(DATABASE).withUsername(USER).withPassword(PASSWORD)

    lateinit var tmp: Path

    fun dmigUrl(): String =
        "mysql://$USER:$PASSWORD@${container.host}:${container.firstMappedPort}/$DATABASE"

    beforeSpec {
        container.start()
        tmp = Files.createTempDirectory("dmigrate-e2e-mysql-apply-")
    }

    afterSpec {
        container.stop()
        tmp.deleteRecursively()
    }

    fun mysql(sql: String): Container.ExecResult = container.execInContainer(
        "mysql", "-u$USER", "-p$PASSWORD", DATABASE, "-e", sql,
    )

    /**
     * Die Specs teilen sich einen Container — ohne dieses Aufraeumen faende der
     * zweite Lauf die Tabellen des ersten (`ERROR 1050: Table already exists`).
     * Aufgeraeumt wird die Datenbank, **nicht** der Container: ihn je Test neu
     * zu starten kostete ein Vielfaches.
     */
    fun resetDatabase() {
        val tables = mysql("SHOW TABLES").stdout.lines()
            .drop(1).map { it.trim() }.filter { it.isNotEmpty() }
        if (tables.isEmpty()) return
        mysql(
            "SET FOREIGN_KEY_CHECKS=0; " +
                tables.joinToString("; ") { "DROP TABLE IF EXISTS `$it`" } +
                "; SET FOREIGN_KEY_CHECKS=1;",
        )
    }

    /** Der erzeugte Stand, so wie ihn der Reader sieht. */
    fun reversed(script: Path): dev.dmigrate.core.model.SchemaDefinition {
        resetDatabase()
        container.copyFileToContainer(MountableFile.forHostPath(script), "/tmp/schema.sql")
        val apply = container.execInContainer(
            "sh", "-c", "mysql -u$USER -p$PASSWORD $DATABASE < /tmp/schema.sql",
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
                "--target", "mysql", "--output", script.absolutePathString(), "--deterministic",
            ),
        )
        return gen.exitCode to script
    }

    /**
     * Der Fall, der den Defekt ausgeloest hat: eine `UNIQUE`-Constraint auf
     * einer unbegrenzten `TEXT`-Spalte. Sie wird **uebersprungen und benannt**
     * (W125) — und die restliche DDL muss deshalb durchlaufen. Vor dem Fix
     * scheiterte hier die ganze `CREATE TABLE`-Anweisung.
     */
    test("a UNIQUE on an unbounded TEXT column is skipped, and the rest still applies") {
        val (exit, script) = generate(SCHEMA_WITH_TEXT_UNIQUE)
        withClue("generate exit=$exit") { exit shouldBe 8 }

        val schema = reversed(script)

        // Die Tabelle steht — die Anweisung ist nicht mehr gescheitert.
        schema.tables.keys shouldContainAll listOf("customers", "orders")
        // Die uebersprungene Zusicherung fehlt, wie angekuendigt.
        schema.tables.getValue("customers").columns.getValue("email").uniqueConstraintName shouldBe null
        // Und alles andere ist angekommen.
        schema.tables.getValue("orders").indices.map { it.name } shouldContainAll listOf("ix_orders_customer")
    }

    test("a schema MySQL can express applies cleanly and reverses back") {
        val (exit, script) = generate(SCHEMA_PLAIN)
        withClue("generate exit=$exit") { exit shouldBe 0 }
        script.readText() shouldContain "CONSTRAINT `uq_customer_email` UNIQUE (`email`)"

        val schema = reversed(script)
        schema.tables.keys shouldContainAll listOf("customers", "orders")
        // Ein benanntes UNIQUE kommt als `unique_constraint` an der Spalte
        // zurueck — nicht in `constraints`. Der Reverse-Blick ist der einzige,
        // der zaehlt: er zeigt, was der Server wirklich angelegt hat.
        schema.tables.getValue("customers").columns.getValue("email").uniqueConstraintName shouldBe "uq_customer_email"
    }

    /** Die Gegenprobe zur W125-Regel: eine **begrenzte** Spalte ist schluesselfaehig. */
    test("a bounded VARCHAR column carries its UNIQUE through the whole way") {
        val (exit, script) = generate(SCHEMA_BOUNDED_UNIQUE)
        withClue("generate exit=$exit") { exit shouldBe 0 }

        val schema = reversed(script)
        schema.tables.getValue("customers").columns.getValue("email").uniqueConstraintName shouldBe "uq_customer_email"
        withClue("die Spalte muss begrenzt zurueckkommen") {
            mysql("SHOW COLUMNS FROM customers LIKE 'email'").stdout shouldContain "varchar(255)"
        }
    }
})

private const val DATABASE = "dmigrate_apply"
private const val USER = "dmigrate"
private const val PASSWORD = "DMigrate_E2E_Pa55word"

private const val SCHEMA_HEADER = """
    schema_format: "1.0"
    name: apply_probe
    version: 1.0.0
    tables:
"""

/** `email` ist unbegrenzt — MySQL kann darauf keinen Schluessel bilden. */
private val SCHEMA_WITH_TEXT_UNIQUE = """
    schema_format: "1.0"
    name: apply_probe
    version: 1.0.0
    tables:
      customers:
        columns:
          id: { type: identifier, auto_increment: true }
          email: { type: text, required: true, unique: true, unique_constraint: uq_customer_email }
        primary_key: [id]
      orders:
        columns:
          id: { type: identifier, auto_increment: true }
          customer_id: { type: integer, required: true }
        primary_key: [id]
        indices:
          - name: ix_orders_customer
            columns: [customer_id]
""".trimIndent()

private val SCHEMA_PLAIN = (SCHEMA_HEADER + """
      customers:
        columns:
          id: { type: identifier, auto_increment: true }
          email: { type: text, max_length: 255, required: true, unique: true, unique_constraint: uq_customer_email }
        primary_key: [id]
      orders:
        columns:
          id: { type: identifier, auto_increment: true }
          customer_id: { type: integer, required: true }
        primary_key: [id]
""").trimIndent()

private val SCHEMA_BOUNDED_UNIQUE = (SCHEMA_HEADER + """
      customers:
        columns:
          id: { type: identifier, auto_increment: true }
          email: { type: text, max_length: 255, required: true, unique: true, unique_constraint: uq_customer_email }
        primary_key: [id]
""").trimIndent()
