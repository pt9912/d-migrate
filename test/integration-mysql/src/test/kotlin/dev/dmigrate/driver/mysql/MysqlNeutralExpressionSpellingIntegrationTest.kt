package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.MySQLContainer
import java.math.BigDecimal
import java.sql.Connection

/**
 * Ein neutraler Ausdruck mit `"…"`-Bezeichnern und einem Backslash im Literal,
 * auf MySQL angelegt und **ausgewertet**.
 *
 * Ein Textvergleich zeigt nur, dass Backticks im DDL stehen. Ob MySQL damit
 * rechnet, sagt nur der Server: vorher las er `"Qty"` als Zeichenkette,
 * `'Qty' * 'UnitPrice'` ergab still `0`, und `'Qty' > 0` war immer falsch.
 * Das ist der Weg einer PascalCase-Spalte von SQL Server nach MySQL.
 */
class MysqlNeutralExpressionSpellingIntegrationTest : FunSpec({

    val container = MySQLContainer(TestImages.MYSQL)
        .withDatabaseName("dmigrate_spelling")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    var pool: ConnectionPool? = null

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.MYSQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
                params = mapOf("allowPublicKeyRetrieval" to "true"),
            ),
        )
    }

    afterSpec {
        pool?.close()
        container.stop()
    }

    val schema = SchemaDefinition(
        name = "spelling", version = "1",
        tables = mapOf(
            "OrderLine" to TableDefinition(
                columns = linkedMapOf(
                    "Id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "Qty" to ColumnDefinition(NeutralType.Integer, required = true),
                    "UnitPrice" to ColumnDefinition(NeutralType.Decimal(10, 2), required = true),
                    "Note" to ColumnDefinition(NeutralType.Text(maxLength = 40)),
                    "LineTotal" to ColumnDefinition(
                        NeutralType.Decimal(12, 2),
                        generation = ColumnGeneration.Computed("\"Qty\" * \"UnitPrice\"", stored = true),
                    ),
                ),
                primaryKey = listOf("Id"),
                indices = listOf(
                    IndexDefinition(
                        name = "ix_orderline_note",
                        columns = listOf(IndexColumn.expression("lower(\"Note\")")),
                    ),
                ),
                constraints = listOf(
                    ConstraintDefinition(name = "ck_orderline_qty", type = ConstraintType.CHECK, expression = "\"Qty\" > 0"),
                    // Der Backslash ist im neutralen Literal ein Zeichen; die
                    // Zeichenkette mit `"` bleibt eine Zeichenkette.
                    ConstraintDefinition(
                        name = "ck_orderline_note",
                        type = ConstraintType.CHECK,
                        expression = "\"Note\" <> 'a\\b' AND \"Note\" <> 'say \"hi\"'",
                    ),
                ),
            ),
        ),
    )

    fun Connection.apply(statements: List<DdlStatement>) = createStatement().use { stmt ->
        statements
            .map { it.sql.lines().filterNot { line -> line.trimStart().startsWith("--") } }
            .map { it.joinToString("\n").trim().removeSuffix(";") }
            .filter { it.isNotBlank() }
            .forEach { sql -> withClue("statement failed:\n$sql") { stmt.execute(sql) } }
    }

    fun Connection.insert(id: Int, qty: Int, note: String?): Result<Unit> = runCatching {
        prepareStatement("INSERT INTO OrderLine (Id, Qty, UnitPrice, Note) VALUES (?, ?, 2.50, ?)").use { ps ->
            ps.setInt(1, id)
            ps.setInt(2, qty)
            ps.setString(3, note)
            ps.executeUpdate()
        }
        Unit
    }

    test("the generated DDL computes, checks and indexes the named columns") {
        val ddl = MysqlDdlGenerator().generate(schema)
        ddl.render() shouldContain "GENERATED ALWAYS AS (`Qty` * `UnitPrice`)"
        pool!!.borrow().asJdbc().use { c ->
            c.apply(ddl.statements)

            c.insert(1, 3, "ok").isSuccess shouldBe true
            val total = c.createStatement().use { s ->
                s.executeQuery("SELECT LineTotal FROM OrderLine WHERE Id = 1").use { rs ->
                    rs.next()
                    rs.getBigDecimal(1)
                }
            }
            withClue("MySQL rechnete mit Zeichenketten?") { total.compareTo(BigDecimal("7.50")) shouldBe 0 }

            withClue("der CHECK auf Qty greift nicht") {
                (c.insert(2, 0, "ok").exceptionOrNull()?.message ?: "") shouldContain "ck_orderline_qty"
            }
            withClue("das Literal 'a\\b' kam mit einem anderen Wert an") {
                (c.insert(3, 1, "a\\b").exceptionOrNull()?.message ?: "") shouldContain "ck_orderline_note"
            }
            withClue("das Literal mit \" kam nicht als Zeichenkette an") {
                (c.insert(4, 1, "say \"hi\"").exceptionOrNull()?.message ?: "") shouldContain "ck_orderline_note"
            }
            // Gegenprobe zum Backslash: ohne Verdopplung stuende dort `a<BS>`.
            c.insert(5, 1, "a\b").isSuccess shouldBe true
        }
    }

})
