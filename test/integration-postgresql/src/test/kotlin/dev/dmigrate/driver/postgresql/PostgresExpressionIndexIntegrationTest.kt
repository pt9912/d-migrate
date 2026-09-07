package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Slice 6b: PostgreSQL ist der Herkunftsdialekt, aus dem ein Ausdrucks-Index
 * am ehesten stammt. Der Generate-Pfad rendert ihn seit 6b — dieser Test
 * misst, was der **Reverse** daraus macht.
 *
 * Der Verdacht kommt aus der Abfrage selbst: sie verbindet `pg_attribute`
 * ueber `attnum`, und `pg_index.indkey` traegt fuer eine Ausdrucks-Position
 * eine `0`, zu der es keine Spalte gibt. Ein INNER JOIN liesse solche
 * Positionen still wegfallen — der Index kaeme mit zu wenigen Schluesseln
 * zurueck, nicht als Fehler.
 */
class PostgresExpressionIndexIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_expr")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(PostgresDriver())
        config = ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_expr",
            user = "dmigrate",
            password = "dmigrate",
            ssl = SslSettings(SslMode.DISABLE),
        )
    }

    afterSpec { container.stop() }

    test("the generated expression index is valid PostgreSQL DDL") {
        val schema = SchemaDefinition(
            name = "S", version = "1",
            tables = mapOf(
                "people" to TableDefinition(
                    columns = mapOf("nm" to ColumnDefinition(NeutralType.Text(maxLength = 100))),
                    indices = listOf(
                        IndexDefinition(
                            name = "ix_people_upper",
                            columns = listOf(IndexColumn.expression("upper(nm)")),
                        ),
                    ),
                ),
            ),
        )
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP TABLE IF EXISTS people CASCADE")
                    PostgresDdlGenerator().generate(schema).statements
                        .map { it.sql.lines().filterNot { line -> line.trimStart().startsWith("--") } }
                        .map { it.joinToString("\n").trim().removeSuffix(";") }
                        .filter { it.isNotBlank() }
                        .forEach { sql -> withClue("statement failed:\n$sql") { stmt.execute(sql) } }
                }
            }
        }
    }

    test("the reverse brings the expression back instead of losing the index") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val reversed = PostgresSchemaReader().read(pool)
            val index = reversed.schema.tables.getValue("people").indices
                .singleOrNull { it.name == "ix_people_upper" }

            // Vor 6b kam hier `null` zurueck: die Abfrage verband
            // `pg_attribute` ueber `attnum`, und eine Ausdrucks-Position
            // traegt dort 0 -- der INNER JOIN liess den ganzen Index
            // wegfallen, ohne Fehler.
            withClue("gelesener Index: $index") {
                index shouldNotBe null
                val key = index!!.columns.single()
                // Genau der Text, nicht bloss „enthaelt upper": nur so faellt
                // auf, wenn PostgreSQL die Form aendert.
                key.expression shouldBe "upper(nm::text)"
                // Und er zaehlt nicht als Spalte.
                index.columnNames shouldBe emptyList()
            }
        }
    }

    test("a mixed index keeps column and expression in order") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP INDEX IF EXISTS ix_mixed")
                    stmt.execute("CREATE INDEX ix_mixed ON people (nm, upper(nm))")
                }
            }
            val index = PostgresSchemaReader().read(pool)
                .schema.tables.getValue("people").indices.single { it.name == "ix_mixed" }
            // Die Reihenfolge ist bedeutungstragend: ein Index (a, expr) ist
            // etwas anderes als (expr, a).
            index.columns[0].expression shouldBe null
            index.columns[0].name shouldBe "nm"
            index.columns[1].expression shouldNotBe null
            index.columnNames shouldBe listOf("nm")
        }
    }

    test("PostgreSQL rejects an unparenthesised non-function expression") {
        // Die Begruendung dafuer, dass `renderKey` IMMER klammert -- gegen den
        // Server geprueft, nicht angenommen.
        HikariConnectionPoolFactory.create(config).use { pool ->
            val bare = runCatching {
                pool.borrow().asJdbc().use { c ->
                    c.createStatement().use { it.execute("CREATE INDEX p_bare ON people (nm || 'x')") }
                }
            }.exceptionOrNull()
            withClue("PostgreSQL nahm einen ungeklammerten Ausdruck an?") {
                (bare?.message ?: "") shouldContain "syntax error"
            }
            pool.borrow().asJdbc().use { c ->
                c.createStatement().use { it.execute("CREATE INDEX p_wrapped ON people ((nm || 'x'))") }
            }
        }
    }

    test("the reversed expression text is NOT identical to what was authored") {
        // Festgehalten, weil es eine Folge hat: PostgreSQL gibt den Ausdruck
        // deparst zurueck (`upper(nm)` -> `upper(nm::text)`). Der
        // Fingerabdruck hasht den Text, also driftet ein Ausdrucks-Index nach
        // `migrate --execute` dauerhaft. Siehe
        // docs/planning/open/raw-sql-text-drift.md.
        HikariConnectionPoolFactory.create(config).use { pool ->
            val expression = PostgresSchemaReader().read(pool)
                .schema.tables.getValue("people").indices
                .single { it.name == "ix_people_upper" }.columns.single().expression
            expression shouldBe "upper(nm::text)"
        }
    }
})
