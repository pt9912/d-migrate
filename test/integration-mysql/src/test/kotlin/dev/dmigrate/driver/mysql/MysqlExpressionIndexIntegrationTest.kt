package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.MySQLContainer

/**
 * Slice 6b: MySQL 8 verlangt fuer einen **funktionalen** Indexschluessel ein
 * zweites Klammernpaar — `((UPPER(nm)))`. Einfach geklammert liest es den
 * Ausdruck als Spaltenliste und lehnt ab.
 *
 * Das gegen einen echten Server zu pruefen ist der Punkt: die Regel steht so
 * im Handbuch, aber eine Klammer mehr oder weniger sieht in einem
 * Zeichenketten-Vergleich genauso richtig aus.
 */
class MysqlExpressionIndexIntegrationTest : FunSpec({

    val container = MySQLContainer("mysql:9.7.2")
        .withDatabaseName("dmigrate_expr")
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
            )
        )
    }

    afterSpec {
        pool?.close()
        container.stop()
    }

    val schema = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "people" to TableDefinition(
                columns = mapOf("nm" to ColumnDefinition(NeutralType.Text(maxLength = 100))),
                indices = listOf(
                    IndexDefinition(
                        name = "ix_people_upper",
                        columns = listOf(IndexColumn.expression("UPPER(nm)")),
                    ),
                ),
            ),
        ),
    )

    test("the generated functional index is valid MySQL DDL") {
        val ddl = MysqlDdlGenerator().generate(schema)
        withClue("die zweite Klammer fehlt?") {
            ddl.render() shouldContain "((UPPER(nm)))"
        }
        pool!!.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS people")
                ddl.statements
                    .map { it.sql.lines().filterNot { line -> line.trimStart().startsWith("--") } }
                    .map { it.joinToString("\n").trim().removeSuffix(";") }
                    .filter { it.isNotBlank() }
                    .forEach { sql -> withClue("statement failed:\n$sql") { stmt.execute(sql) } }
            }
        }
    }

    test("a single pair of parentheses is what MySQL actually rejects") {
        // Die Begruendung fuer die zweite Klammer, gegen den Server geprueft
        // statt behauptet.
        pool!!.borrow().asJdbc().use { conn ->
            val failure = runCatching {
                conn.createStatement().use { it.execute("CREATE INDEX ix_single ON people (UPPER(nm))") }
            }.exceptionOrNull()
            // Auf den Grund geprueft, nicht bloss auf „irgendein Fehler":
            // sonst erfuellte auch „Tabelle existiert nicht" die Zusicherung.
            withClue("MySQL nahm einen einfach geklammerten Ausdruck an?") {
                (failure?.message ?: "") shouldContain "You have an error in your SQL syntax"
            }
        }
    }
})
