package dev.dmigrate.driver.mssql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Worauf `MssqlObjectRenamePolicy` sich stuetzt — gegen echtes SQL Server.
 *
 * Die Politik stuft Sicht, Sequenz, Trigger, Funktion und Prozedur als
 * **nativ** umbenennbar ein. Das ruht auf zwei gemessenen Aussagen, nicht auf
 * der Dokumentation:
 *
 * 1. `sp_rename` benennt jede dieser Objektarten wirklich um, und eine Sequenz
 *    behaelt dabei Startwert und Schrittweite.
 * 2. Der gespeicherte Rumpf in `sys.sql_modules` traegt danach weiter den
 *    **alten** Namen — fuer das neutrale Modell folgenlos, weil
 *    `MssqlViewDefinitionScanner` und `MssqlRoutineBody` alles vor dem `AS`
 *    wegschneiden.
 *
 * Faellt Aussage 2, ist die Einstufung als `Native` falsch: der Reverse
 * lieferte dann nach jedem Rename einen anderen Rumpf als vorher, und der
 * naechste Lauf plante eine Rumpfaenderung, die niemand verlangt hat.
 */
class MssqlObjectRenameIntegrationTest : FunSpec({

    val container = startMssqlContainer()

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = poolFor(container, "dmigrate_obj_rename")
        execDdl(
            pool,
            "CREATE TABLE orders (id INT NOT NULL PRIMARY KEY, amount INT NULL)",
            "CREATE SEQUENCE seq_old START WITH 5 INCREMENT BY 2",
        )
        // `EXEC(...)`: `CREATE VIEW`/`CREATE TRIGGER`/`CREATE FUNCTION` muessen
        // in T-SQL allein in einem Batch stehen.
        execDdl(
            pool,
            "EXEC('CREATE VIEW v_old AS SELECT id, amount FROM orders')",
            "EXEC('CREATE TRIGGER tr_old ON orders AFTER INSERT AS SET NOCOUNT ON;')",
            "EXEC('CREATE FUNCTION fn_old() RETURNS INT AS BEGIN RETURN 1 END')",
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun queryOne(sql: String): Any? = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null }
        }
    }

    fun readSchema() = MssqlSchemaReader().read(pool, SchemaReadOptions()).schema

    test("sp_rename really renames each object kind, and a sequence keeps its shape") {
        val before = readSchema()
        val viewBodyBefore = before.views.getValue("v_old").query
        // Trigger und Routinen tragen zusammengesetzte Schluessel
        // (Tabelle bzw. Signatur gehoeren dazu).
        val triggerBodyBefore = before.triggers.getValue(ObjectKeyCodec.triggerKey("orders", "tr_old")).body

        // Dieselbe Form, die `MssqlDiffSqlBuilders.renameSql` erzeugt; ihre
        // Zeichenkette pinnt der Unit-Test, hier zaehlt die Wirkung am Server.
        execDdl(
            pool,
            "EXEC sp_rename 'v_old', 'v_new';",
            "EXEC sp_rename 'seq_old', 'seq_new';",
            "EXEC sp_rename 'tr_old', 'tr_new';",
            "EXEC sp_rename 'fn_old', 'fn_new';",
        )

        val after = readSchema()
        withClue("gelesene Sichten: ${after.views.keys}") { after.views.containsKey("v_new") shouldBe true }
        after.sequences.containsKey("seq_new") shouldBe true
        withClue("gelesene Trigger: ${after.triggers.keys}") {
            after.triggers.containsKey(ObjectKeyCodec.triggerKey("orders", "tr_new")) shouldBe true
        }
        withClue("gelesene Funktionen: ${after.functions.keys}") {
            after.functions.containsKey(ObjectKeyCodec.routineKey("fn_new", emptyList())) shouldBe true
        }

        // Eine Sequenz traegt keinen Rumpf; der Rename darf ihre Form nicht anfassen.
        after.sequences.getValue("seq_new").start shouldBe 5L
        after.sequences.getValue("seq_new").increment shouldBe 2L

        // Der Kern: der gespeicherte Text nennt weiter den ALTEN Namen ...
        val storedView = queryOne(
            "SELECT definition FROM sys.sql_modules WHERE object_id = OBJECT_ID('v_new')",
        ) as String
        storedView shouldContain "v_old"

        // ... aber der Rumpf, den das Modell fuehrt, ist derselbe wie vorher.
        // Genau deshalb darf die Politik `Native` sagen.
        after.views.getValue("v_new").query shouldBe viewBodyBefore
        after.triggers.getValue(ObjectKeyCodec.triggerKey("orders", "tr_new")).body shouldBe triggerBodyBefore
    }
})
