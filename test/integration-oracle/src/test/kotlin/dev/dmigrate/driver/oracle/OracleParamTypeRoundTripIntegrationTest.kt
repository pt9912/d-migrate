package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Was aus einem Parametertyp wird, wenn die Routine gelesen und wieder erzeugt
 * wird — gegen ein echtes Oracle.
 *
 * Der Unit-Test rechnet den Rueckweg auf dem Papier nach. Er kann nicht
 * bestaetigen, dass Oracle die erzeugte Signatur auch annimmt: eine PL/SQL-
 * Signatur ohne Laenge ist gueltig, mit Laenge nicht, und `CLOB` als Parameter
 * ist eine Zusage des Servers, keine Modellannahme.
 */
class OracleParamTypeRoundTripIntegrationTest : FunSpec({

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
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """CREATE OR REPLACE FUNCTION "rt_lob"("p_c" IN CLOB, "p_ch" IN CHAR) RETURN NUMBER IS
                       BEGIN RETURN 1; END;""",
                )
                st.execute(
                    """CREATE OR REPLACE PROCEDURE "rt_io"("p_io" IN OUT CLOB) IS BEGIN NULL; END;""",
                )
                st.execute(
                    """CREATE OR REPLACE FUNCTION "rt_other"("p_d" IN DATE, "p_f" IN BINARY_FLOAT,
                       "p_r" IN RAW) RETURN NUMBER IS BEGIN RETURN 1; END;""",
                )
                st.execute(
                    """CREATE OR REPLACE FUNCTION "rt_vc"("p_v" IN VARCHAR2) RETURN NUMBER IS
                       BEGIN RETURN 1; END;""",
                )
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    val options = SchemaReadOptions(includeFunctions = true, includeProcedures = true)

    test("CLOB und CHAR ueberstehen den Rueckweg — und Oracle nimmt die erzeugte Signatur an") {
        val read = OracleSchemaReader().read(pool, options)
        val fn = read.schema.functions.entries.single { it.key.startsWith("rt_lob") }.value

        fn.parameters.map { it.type } shouldBe listOf("text", "char")

        // Und der Beweis, dass daraus wieder gueltiges PL/SQL wird: ueber den
        // Generierungspfad neu erzeugen und den Katalog fragen.
        val sql = routineSql(SchemaDefinition(name = "s", version = "1", functions = mapOf("rt_lob2" to fn)))
        pool.borrow().asJdbc().use { c -> c.createStatement().use { it.execute(sql) } }

        withClue("$sql\n-> ${diagnose(pool, "rt_lob2")}") {
            declaredTypes(pool, "rt_lob2") shouldBe listOf("NUMBER", "CLOB", "CHAR")
        }
    }

    test("ein IN-OUT-CLOB ist ebenfalls gueltig") {
        val read = OracleSchemaReader().read(pool, options)
        val proc = read.schema.procedures.entries.single { it.key.startsWith("rt_io") }.value

        val sql = routineSql(SchemaDefinition(name = "s", version = "1", procedures = mapOf("rt_io2" to proc)))
        pool.borrow().asJdbc().use { c -> c.createStatement().use { it.execute(sql) } }

        withClue("$sql\n-> ${diagnose(pool, "rt_io2")}") {
            declaredTypes(pool, "rt_io2") shouldBe listOf("CLOB")
        }
    }

    test("was den Rueckweg nicht uebersteht, meldet R368 mit Vorher und Nachher") {
        val read = OracleSchemaReader().read(pool, options)
        val note = read.notes.single { it.code == "R368" && it.objectName == "rt_other" }

        withClue(note.message) {
            note.message shouldContain "DATE -> TIMESTAMP"
            note.message shouldContain "BINARY_FLOAT -> BINARY_DOUBLE"
            note.message shouldContain "RAW -> BLOB"
        }
    }

    test("auch VARCHAR2 kommt als CLOB wieder — und das steht in der Notiz") {
        // Der haeufige Fall, und deshalb ausdruecklich festgehalten: der
        // neutrale Name `text` fasst VARCHAR2 und CLOB zusammen, gerendert wird
        // die Form, die keinen Aufruf brechen kann.
        val read = OracleSchemaReader().read(pool, options)
        val note = read.notes.single { it.code == "R368" && it.objectName == "rt_vc" }

        withClue(note.message) { note.message shouldContain "VARCHAR2 -> CLOB" }
    }

    test("freistehende Routinen lassen sich nicht ueberladen — ein breiterer Typ kollidiert also nicht") {
        // Das ist der Grund, warum die Verbreiterung ungefaehrlich ist: eine
        // zweite Routine gleichen Namens ERSETZT die erste, sie tritt nicht
        // daneben. Ueberladung gibt es bei Oracle nur in Packages, und die
        // liest das neutrale Modell nicht (R342).
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use { st ->
                st.execute("""CREATE OR REPLACE FUNCTION "ov"("p" IN NUMBER) RETURN NUMBER IS
                              BEGIN RETURN 1; END;""")
                st.execute("""CREATE OR REPLACE FUNCTION "ov"("p" IN VARCHAR2) RETURN NUMBER IS
                              BEGIN RETURN 2; END;""")
            }
        }

        declaredTypes(pool, "ov") shouldBe listOf("NUMBER", "VARCHAR2")
    }

    test("eine Routine, deren Typen alle zurueckkommen, meldet nichts") {
        val read = OracleSchemaReader().read(pool, options)

        read.notes.count { it.code == "R368" && it.objectName == "rt_lob" } shouldBe 0
    }
})

/**
 * Die `CREATE`-Anweisung der einen Routine im Schema — ueber den
 * Generierungspfad, nicht ueber den modulinternen Helfer: gepruefte werden soll,
 * was ein Anwender wirklich bekommt.
 */
private fun routineSql(schema: SchemaDefinition): String =
    OracleDdlGenerator().generate(schema).statements
        .map { it.sql.trim() }
        // Kein `removeSuffix(";")`: das abschliessende Semikolon gehoert zum
        // PL/SQL-Rumpf (`END;`), nicht zur Skriptdarstellung. Ohne es uebersetzt
        // Oracle die Routine nicht — und meldet den Fehlschlag trotzdem nicht.
        .single { it.startsWith("CREATE OR REPLACE FUNCTION") || it.startsWith("CREATE OR REPLACE PROCEDURE") }

private fun diagnose(pool: ConnectionPool, routine: String): String =
    pool.borrow().asJdbc().use { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT o.object_name, o.status, o.owner, " +
                    "(SELECT count(*) FROM all_errors e WHERE e.name = o.object_name) AS errs " +
                    "FROM all_objects o WHERE o.object_name = '$routine'",
            ).use { rs ->
                buildString {
                    while (rs.next()) {
                        append("${rs.getString(1)}/${rs.getString(2)}/${rs.getString(3)}/errs=${rs.getInt(4)} ")
                    }
                }.ifBlank { "kein Objekt namens '$routine'" }
            }
        }
    }

private fun declaredTypes(pool: ConnectionPool, routine: String): List<String> =
    pool.borrow().asJdbc().use { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT data_type FROM all_arguments WHERE object_name = '$routine' " +
                    "AND owner = SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AND data_level = 0 " +
                    "ORDER BY position",
            ).use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }
