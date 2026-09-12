package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Ein Index auf einer virtuellen Spalte kommt als **Spaltenindex** zurueck —
 * so, wie er geschrieben wurde.
 *
 * **Was Oracle daraus macht** — und was der erste Erklaerungsversuch falsch
 * hatte. `CREATE INDEX … ("line_total")` ueber einer virtuellen Spalte legt der
 * Server als `FUNCTION-BASED NORMAL` an und schreibt die Berechnung nach
 * `ALL_IND_EXPRESSIONS`. `ALL_IND_COLUMNS` nennt aber die **echte** Spalte; eine
 * unsichtbare `SYS_NC…` steht dort nur bei einem *echten* Ausdrucks-Index.
 * Gemessen an Oracle 23:
 *
 * ```
 * all_ind_columns:       ix_total -> line_total          ix_nm -> SYS_NC00005$
 * all_ind_expressions:   ix_total -> "quantity"*"unit_price"
 *                        ix_nm    -> UPPER("nm")
 * ```
 *
 * Der Reverse hoerte allein auf die Ausdruckszeile und meldete deshalb auch
 * `ix_total` als Ausdrucks-Index — ein Soll mit `columns: [line_total]`
 * konvergierte nie. Oracle sagt selbst, welcher Fall vorliegt; die
 * Unterscheidung braucht keinen Textvergleich.
 *
 * **Oracle ist damit allein**, und das ist gemessen: auf PostgreSQL, MySQL, SQL
 * Server und SQLite kommt derselbe Index als Spaltenindex zurueck.
 *
 * Was kein Unit-Test zeigen kann, ist genau diese Doppelbuchfuehrung des Data
 * Dictionary.
 */
class OracleVirtualColumnIndexIntegrationTest : FunSpec({

    val container = OracleContainer(TestImages.ORACLE).withStartupTimeout(Duration.ofMinutes(5))
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.ORACLE,
                host = container.host, port = container.oraclePort,
                database = container.databaseName,
                user = container.username, password = container.password,
            ),
        )
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use {
                it.execute(
                    """CREATE TABLE "vix" (
                         "id" NUMBER(9) NOT NULL PRIMARY KEY,
                         "quantity" NUMBER(9) NOT NULL,
                         "unit_price" NUMBER(12,2) NOT NULL,
                         "line_total" NUMBER(14,2) GENERATED ALWAYS AS ("quantity" * "unit_price") VIRTUAL,
                         "nm" VARCHAR2(40))""",
                )
            }
            // Wie ein Anwender ihn schreibt: ueber der Spalte.
            c.createStatement().use { it.execute("""CREATE INDEX "ix_vix_total" ON "vix" ("line_total")""") }
            // Und ein echter Ausdrucks-Index daneben, der etwas anderes sagt.
            c.createStatement().use { it.execute("""CREATE INDEX "ix_vix_nm" ON "vix" (UPPER("nm"))""") }
        }
    }

    afterSpec {
        runCatching {
            pool.borrow().asJdbc().use { c ->
                c.createStatement().use { it.execute("""DROP TABLE "vix" PURGE""") }
            }
        }
        runCatching { pool.close() }
        container.stop()
    }

    fun read() = OracleSchemaReader().read(pool).schema.tables.getValue("vix")

    fun dictionary(sql: String): String? = pool.borrow().asJdbc().use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
    }

    test("the dictionary keeps both books, and that is the whole difficulty") {
        // Beide Indizes sind function-based …
        dictionary(
            """SELECT index_type FROM all_indexes WHERE index_name = 'ix_vix_total'""",
        ) shouldBe "FUNCTION-BASED NORMAL"
        dictionary(
            """SELECT index_type FROM all_indexes WHERE index_name = 'ix_vix_nm'""",
        ) shouldBe "FUNCTION-BASED NORMAL"

        // … und beide haben eine Ausdruckszeile.
        dictionary(
            """SELECT column_expression FROM all_ind_expressions WHERE index_name = 'ix_vix_total'""",
        ) shouldBe """"quantity"*"unit_price""""

        // Getrennt werden sie allein hier: die virtuelle Spalte steht mit
        // ihrem Namen, der echte Ausdruck mit einer Systemspalte.
        dictionary(
            """SELECT column_name FROM all_ind_columns WHERE index_name = 'ix_vix_total'""",
        ) shouldBe "line_total"
        withClue("nur der echte Ausdrucks-Index bekommt eine SYS_NC-Spalte") {
            (
                dictionary(
                    """SELECT column_name FROM all_ind_columns WHERE index_name = 'ix_vix_nm'""",
                )?.startsWith("SYS_NC") == true
            ) shouldBe true
        }
    }

    test("the reverse gives the column back") {
        val index = read().indices.single { it.name == "ix_vix_total" }

        withClue(index.toString()) {
            index.columns.single().name shouldBe "line_total"
            index.columns.single().expression.shouldBeNull()
        }
    }

    test("an expression index that means something else stays one") {
        val index = read().indices.single { it.name == "ix_vix_nm" }

        withClue(index.toString()) { index.columns.single().expression shouldBe """UPPER("nm")""" }
    }

    test("a schema that writes the index over the column now converges") {
        val authored = SchemaDefinition(
            name = "oracle_virtual_index", version = "1",
            tables = mapOf(
                "vix" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "quantity" to ColumnDefinition(NeutralType.Integer, required = true),
                        "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2), required = true),
                        "line_total" to ColumnDefinition(
                            NeutralType.Decimal(14, 2),
                            generation = ColumnGeneration.Computed(""""quantity"*"unit_price"""", stored = false),
                        ),
                        "nm" to ColumnDefinition(NeutralType.Text(maxLength = 40)),
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        // So, wie ein Anwender ihn schreibt — und bis heute
                        // fruehstueckte jeder Vergleich daran.
                        IndexDefinition(name = "ix_vix_total", columns = listOf(IndexColumn("line_total"))),
                        IndexDefinition(
                            name = "ix_vix_nm",
                            columns = listOf(IndexColumn(name = """UPPER("nm")""", expression = """UPPER("nm")""")),
                        ),
                    ),
                ),
            ),
        )

        val diff = SchemaComparator().compare(
            SchemaDefinition(name = "x", version = "1", tables = mapOf("vix" to read())),
            authored,
        )

        withClue(diff.toString()) { diff.tablesChanged shouldBe emptyList() }
    }
})
