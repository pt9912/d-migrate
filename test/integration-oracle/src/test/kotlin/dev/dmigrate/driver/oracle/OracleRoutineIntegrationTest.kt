package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
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
 * Routinen und Trigger gegen ein ECHTES Oracle.
 *
 * Der Unit-Test des Readers arbeitet gegen handgeschriebene Katalogzeilen; er
 * kann also nur bestaetigen, was beim Schreiben angenommen wurde. Genau dort
 * liegen aber die teuren Fehler: `ALL_TRIGGERS.COLUMN_NAME` ist bei
 * `UPDATE OF` leer, `SQL_MACRO` traegt die Zeichenkette `'NULL'`,
 * `ALL_ARGUMENTS.DATA_TYPE` ist bei einem benutzerdefinierten Typ nur die
 * Kategorie. Dieser Test legt die Objekte an und liest sie zurueck.
 */
class OracleRoutineIntegrationTest : FunSpec({

    val container = OracleContainer(TestImages.ORACLE)
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        config = ConnectionConfig(
            dialect = DatabaseDialect.ORACLE,
            host = container.host,
            port = container.oraclePort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
        )
    }

    afterSpec { container.stop() }

    val setup = listOf(
        """CREATE TABLE "orders" ("id" NUMBER(9) PRIMARY KEY, "amt" NUMBER(10,2), "note" VARCHAR2(50))""",
        """CREATE OR REPLACE FUNCTION "calc"("p_a" IN NUMBER, "p_b" IN VARCHAR2) RETURN NUMBER
           DETERMINISTIC AUTHID CURRENT_USER IS
             v_r NUMBER;
           BEGIN
             -- ein Kommentar, der erhalten bleiben muss
             v_r := "p_a" * 2;
             RETURN v_r;
           END;""",
        """CREATE OR REPLACE PROCEDURE "touch"("p_x" IN NUMBER, "p_out" OUT NUMBER, "p_io" IN OUT VARCHAR2) AS
           BEGIN
             "p_out" := "p_x" + 1;
           END;""",
        """CREATE OR REPLACE PROCEDURE "noargs" IS BEGIN NULL; END;""",
        """CREATE OR REPLACE TRIGGER "trg_row"
           BEFORE INSERT OR UPDATE ON "orders"
           FOR EACH ROW
           WHEN ((NEW."amt" > 10) AND (NEW."note" IS NOT NULL))
           BEGIN
             :NEW."amt" := :NEW."amt" + 1;
           END;""",
        """CREATE OR REPLACE TRIGGER "trg_stmt" AFTER DELETE ON "orders" BEGIN NULL; END;""",
    )

    test("routines and triggers read back with neutral signatures, canonical keys and verbatim bodies") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    setup.forEach { sql -> withClue("setup failed:\n$sql") { stmt.execute(sql) } }
                }
            }

            val result = OracleSchemaReader().read(pool)
            val schema = result.schema

            // Der kanonische Schluessel traegt Richtung und **neutralen** Typ
            // ohne Laenge -- `NUMBER` wird `decimal`, `VARCHAR2` wird `text`.
            withClue("gelesene Funktionen: ${schema.functions.keys}") {
                schema.functions.keys shouldBe setOf("calc(in:decimal,in:text)")
            }
            val calc = schema.functions.getValue("calc(in:decimal,in:text)")
            calc.parameters.map { it.name } shouldBe listOf("p_a", "p_b")
            calc.returns!!.type shouldBe "decimal"
            // PL/SQL laesst keinen beschraenkten Rueckgabetyp zu; was
            // ALL_ARGUMENTS dort fuehrt, ist die Speichergroesse.
            calc.returns!!.precision.shouldBeNull()
            calc.deterministic shouldBe true
            calc.security shouldBe RoutineSecurity.INVOKER
            calc.sourceDialect shouldBe "oracle"
            // Der Rumpf steht wortgleich in ALL_SOURCE -- samt Kommentar.
            withClue("Rumpf: ${calc.body}") {
                calc.body!!.contains("ein Kommentar, der erhalten bleiben muss") shouldBe true
                calc.body!!.trimStart().startsWith("v_r NUMBER;") shouldBe true
                calc.body!!.trimEnd().endsWith("END;") shouldBe true
            }

            withClue("gelesene Prozeduren: ${schema.procedures.keys}") {
                schema.procedures.keys shouldBe setOf(
                    "touch(in:decimal,out:decimal,inout:text)",
                    // Eine parameterlose Prozedur hat KEINE Zeile in
                    // ALL_ARGUMENTS -- keine Marker-Zeile, die sie verwerfen
                    // wuerde.
                    "noargs()",
                )
            }
            schema.procedures.getValue("touch(in:decimal,out:decimal,inout:text)")
                .parameters.map { it.direction } shouldBe
                listOf(ParameterDirection.IN, ParameterDirection.OUT, ParameterDirection.INOUT)
            // DETERMINISTIC steht nicht dran, also gilt der Dialekt-Default:
            // `null`, nicht `false` -- sonst plante jeder Lauf erneut.
            schema.functions.values.none { it.deterministic == false } shouldBe true

            val row = schema.triggers.getValue("orders::trg_row")
            row.timing shouldBe TriggerTiming.BEFORE
            row.forEach shouldBe TriggerForEach.ROW
            row.events shouldBe setOf(TriggerEvent.INSERT, TriggerEvent.UPDATE)
            // Die Bedingung kommt ohne die aeusseren Klammern zurueck, die
            // inneren bleiben stehen.
            row.condition shouldBe """(NEW."amt" > 10) AND (NEW."note" IS NOT NULL)"""

            schema.triggers.getValue("orders::trg_stmt").forEach shouldBe TriggerForEach.STATEMENT

            // Keine Routine wurde uebergangen: `SQL_MACRO`/`POLYMORPHIC`
            // tragen fuer gewoehnliche Routinen die Zeichenkette 'NULL', kein
            // SQL-NULL -- ein Test auf „nicht leer" haette hier alles verworfen.
            withClue("uebergangen: ${result.skippedObjects.map { it.name to it.code }}") {
                result.skippedObjects.none { it.code in setOf("R358", "R359", "R360", "R361") } shouldBe true
            }
        }
    }

    test("an UPDATE OF restriction is read from ALL_TRIGGER_COLS and reported as R362") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """CREATE OR REPLACE TRIGGER "trg_upd"
                           BEFORE UPDATE OF "amt", "note" ON "orders"
                           FOR EACH ROW BEGIN NULL; END;""",
                    )
                }
            }
            val result = OracleSchemaReader().read(pool)
            // Der Trigger wird gelesen -- aber die Einschraenkung ist weg, und
            // das steht in der Meldung.
            result.schema.triggers.keys.contains("orders::trg_upd") shouldBe true
            val note = result.notes.single { it.code == "R362" }
            withClue(note.message) {
                note.message.contains("amt") shouldBe true
                note.message.contains("note") shouldBe true
            }
        }
    }

    test("the generated PL/SQL applies over JDBC and leaves every object VALID") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val schema = OracleSchemaReader().read(pool).schema
            val ddl = OracleDdlGenerator().generate(schema)
            val routines = ddl.statements.filter { it.sql.startsWith("CREATE OR REPLACE ") }
            // Namen statt Anzahl: welche Trigger zusaetzlich in der Datenbank
            // stehen, haengt an der Reihenfolge der Tests in dieser Spec.
            withClue("gerendert: ${routines.map { it.sql.lineSequence().first() }}") {
                listOf("\"calc\"", "\"touch\"", "\"noargs\"", "\"trg_row\"", "\"trg_stmt\"").forEach { name ->
                    routines.any { it.sql.contains(name) } shouldBe true
                }
            }

            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    // Genau so, wie der Migrate-Pfad es sendet: ohne `/` und
                    // ohne zweites `;`. Beides laesst execute() gelingen und
                    // das Objekt INVALID zurueck.
                    routines.forEach { s -> withClue("statement failed:\n${s.sql}") { stmt.execute(s.sql) } }
                }
                // Der Nachweis, den `execute()` nicht erbringt.
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """SELECT object_type || ' ' || object_name FROM user_objects
                           WHERE status <> 'VALID'
                             AND object_type IN ('FUNCTION', 'PROCEDURE', 'TRIGGER')""",
                    ).use { rs ->
                        val invalid = buildList { while (rs.next()) add(rs.getString(1)) }
                        withClue("INVALID nach dem Anwenden: $invalid") { invalid shouldBe emptyList() }
                    }
                }
            }
        }
    }
})
