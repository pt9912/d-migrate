package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import java.sql.SQLException
import java.time.Duration

/**
 * Was ein einspaltiger UNIQUE-Constraint in Oracle wirklich braucht, um
 * abgebaut zu werden — die Grundlage der Entscheidung in
 * `docs/planning/open/single-column-constraint-synthetic-name.md`.
 *
 * Drei gemessene Aussagen:
 *
 * 1. Ein **erfundener** Name (`_unique_<spalte>`, wie der Vergleich ihn
 *    bildete, wenn die Seite den Constraint als Spalteneigenschaft fuehrt)
 *    trifft nichts: `ORA-02443`.
 * 2. Der **Katalogname** funktioniert — und ist bei einer inline deklarierten
 *    Spalte ein systemvergebener `SYS_C…`, den ein Soll-Schema nie tragen
 *    kann.
 * 3. Oracle kennt sehr wohl `ALTER TABLE … DROP UNIQUE (<spalte>)`. Das
 *    Ticket hielt diesen Weg fuer ausgeschlossen; er ist es nicht — aber er
 *    ist Oracle-eigen, die uebrigen Dialekte haben keine Entsprechung.
 */
class OracleSingleColumnConstraintIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.ORACLE,
                host = container.host,
                port = container.oraclePort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun exec(vararg sqls: String) = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
    }

    fun queryOne(sql: String): Any? = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null }
        }
    }

    fun freshTable(name: String, uniqueClause: String) = exec(
        "BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"$name\" CASCADE CONSTRAINTS PURGE'; " +
            "EXCEPTION WHEN OTHERS THEN NULL; END;",
        """CREATE TABLE "$name" ("id" NUMBER(9) PRIMARY KEY, "email" VARCHAR2(50) $uniqueClause)""",
    )

    test("an invented constraint name finds nothing") {
        freshTable("inv_probe", "UNIQUE")

        val thrown = shouldThrow<SQLException> {
            exec("""ALTER TABLE "inv_probe" DROP CONSTRAINT "_unique_email"""")
        }
        thrown.message!! shouldContain "ORA-02443"
    }

    test("the catalog name works — and is system-generated for an inline UNIQUE") {
        freshTable("cat_probe", "UNIQUE")
        val name = queryOne(
            "SELECT constraint_name FROM user_constraints " +
                "WHERE table_name = 'cat_probe' AND constraint_type = 'U'",
        ) as String

        // Ein `SYS_C…` steht in keinem vom Anwender geschriebenen Schema.
        name.startsWith("SYS_C") shouldBe true
        exec("""ALTER TABLE "cat_probe" DROP CONSTRAINT "$name"""")
        queryOne(
            "SELECT COUNT(*) FROM user_constraints " +
                "WHERE table_name = 'cat_probe' AND constraint_type = 'U'",
        ).toString() shouldBe "0"
    }

    test("a declared name survives into the catalog and drops by it") {
        freshTable("named_probe", """CONSTRAINT "uq_named_email" UNIQUE""")

        exec("""ALTER TABLE "named_probe" DROP CONSTRAINT "uq_named_email"""")
        queryOne(
            "SELECT COUNT(*) FROM user_constraints " +
                "WHERE table_name = 'named_probe' AND constraint_type = 'U'",
        ).toString() shouldBe "0"
    }

    test("Oracle does have DROP UNIQUE by column — the ticket ruled that out too early") {
        freshTable("bycol_probe", "UNIQUE")

        exec("""ALTER TABLE "bycol_probe" DROP UNIQUE ("email")""")
        queryOne(
            "SELECT COUNT(*) FROM user_constraints " +
                "WHERE table_name = 'bycol_probe' AND constraint_type = 'U'",
        ).toString() shouldBe "0"
    }
})
