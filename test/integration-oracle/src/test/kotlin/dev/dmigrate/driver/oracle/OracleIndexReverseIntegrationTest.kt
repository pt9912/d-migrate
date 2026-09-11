package dev.dmigrate.driver.oracle

import dev.dmigrate.cli.commands.capabilityIndexCanonicalizer
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Slice 6a: der Index-Rueckweg gegen ein ECHTES Oracle.
 *
 * Zwei Gruende, warum Mocks hier nicht reichen:
 *
 * 1. `ALL_IND_EXPRESSIONS.COLUMN_EXPRESSION` ist eine Oracle-**LONG**-Spalte.
 *    `JdbcMetadataSession` liest jede Spalte ueber `rs.getObject(col)`, und
 *    LONG hat mit Row-Prefetch eine bekannte Eigenheit
 *    (`oracle.jdbc.useFetchSizeWithLongColumn`). Kaeme dort leer statt
 *    `"AMT"` zurueck, faende die Rueckfaltung nichts -- und JEDER
 *    DESC-Index verschwaende still mit R354, also genau der Verlust, den
 *    Slice 6a beseitigt. Ein Mock kann das nicht sehen: er liefert den Wert
 *    definitionsgemaess.
 * 2. Die Katalogwerte in `INDEX_TYPE` (`FUNCTION-BASED BITMAP` &c.) sind
 *    gemessene Zeichenketten, keine dokumentierte Zusage.
 */
class OracleIndexReverseIntegrationTest : FunSpec({

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

    test("index kinds come back the way the catalog really reports them") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"facts\"'; EXCEPTION WHEN OTHERS THEN NULL; END;")
                    stmt.execute(
                        """CREATE TABLE "facts" ("id" NUMBER(9) PRIMARY KEY, "status" VARCHAR2(10),
                           "amt" NUMBER(9), "nm" VARCHAR2(50))""".trimIndent(),
                    )
                    stmt.execute("""CREATE BITMAP INDEX "bm_status" ON "facts" ("status")""")
                    stmt.execute("""CREATE BITMAP INDEX "bm_multi" ON "facts" ("status", "amt")""")
                    stmt.execute("""CREATE INDEX "ix_plain" ON "facts" ("amt")""")
                    stmt.execute("""CREATE INDEX "ix_desc" ON "facts" ("amt" DESC)""")
                    stmt.execute("""CREATE INDEX "ix_fn" ON "facts" (UPPER("nm"))""")
                }
            }

            val result = OracleSchemaReader().read(pool)
            val indices = result.schema.tables.getValue("facts").indices.associateBy { it.name }

            withClue("gelesene Indizes: ${indices.keys}") {
                indices.keys shouldBe setOf("bm_status", "bm_multi", "ix_plain", "ix_desc", "ix_fn")
            }
            indices.getValue("bm_status").type shouldBe IndexType.BITMAP
            indices.getValue("bm_multi").type shouldBe IndexType.BITMAP
            indices.getValue("bm_multi").columnNames shouldBe listOf("status", "amt")
            indices.getValue("ix_plain").type shouldBe IndexType.BTREE

            // Der Kern: Oracle setzt "amt" DESC intern als Ausdruck um und
            // fuehrt an der Stelle die unsichtbare Spalte SYS_NC0000n$. Kommt
            // hier deren Name oder gar nichts an, ist die LONG-Spalte nicht
            // gelesen worden.
            val desc = indices.getValue("ix_desc")
            desc.type shouldBe IndexType.BTREE
            desc.columnNames shouldBe listOf("amt")
            desc.columns.single().direction shouldBe IndexSortDirection.DESC

            // Seit 6b traegt das Modell den Ausdruck selbst.
            val fn = indices.getValue("ix_fn")
            fn.columns.single().expression shouldBe "UPPER(\"nm\")"
            fn.columnNames shouldBe emptyList()
            result.notes.none { it.code == "R354" } shouldBe true
        }
    }

    test("an expression index generated from the reversed model is valid Oracle DDL again") {
        // Der Rundschluss: Reverse liefert `UPPER("nm")`, der Generator
        // schreibt es wortgleich zurueck, Oracle nimmt es an. Wuerde der
        // Ausdruck als Bezeichner gequotet, liefe die Anweisung auf eine
        // Spalte, die es nicht gibt.
        val schema = SchemaDefinition(
            name = "S", version = "1",
            tables = mapOf(
                "expr_target" to TableDefinition(
                    columns = mapOf("nm" to ColumnDefinition(NeutralType.Text(maxLength = 50))),
                    indices = listOf(
                        IndexDefinition(
                            name = "ix_upper",
                            columns = listOf(IndexColumn.expression("UPPER(\"nm\")")),
                        ),
                    ),
                ),
            ),
        )
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"expr_target\"'; EXCEPTION WHEN OTHERS THEN NULL; END;",
                    )
                    OracleDdlGenerator().generate(schema).statements
                        .map { it.sql.lines().filterNot { line -> line.trimStart().startsWith("--") } }
                        .map { it.joinToString("\n").trim().removeSuffix(";") }
                        .filter { it.isNotBlank() }
                        .forEach { sql -> withClue("statement failed:\n$sql") { stmt.execute(sql) } }
                }
            }
            val readBack = OracleSchemaReader().read(pool)
                .schema.tables.getValue("expr_target").indices.single()
            readBack.columns.single().expression shouldBe "UPPER(\"nm\")"
        }
    }

    test("a partial index arrives as a full one, and the projection reconciles both sides") {
        // Der Verlust ist nicht nur still, er DRIFTET: `where` geht in den
        // Fingerabdruck ein. Ohne die Oracle-Projektion meldete der
        // Post-Compare nach jedem `migrate --execute` Drift, und der naechste
        // Lauf plante denselben Index erneut.
        val desired = SchemaDefinition(
            name = "S", version = "1",
            tables = mapOf(
                "partial_target" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Integer),
                        "active" to ColumnDefinition(NeutralType.Integer),
                    ),
                    indices = listOf(
                        IndexDefinition(
                            name = "ix_partial",
                            columns = listOf(IndexColumn("id")),
                            where = "\"active\" = 1",
                        ),
                    ),
                ),
            ),
        )
        HikariConnectionPoolFactory.create(config).use { pool ->
            val generated = OracleDdlGenerator().generate(desired)
            generated.notes.single { it.objectName == "ix_partial" }.code shouldBe "W155"

            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"partial_target\"'; " +
                            "EXCEPTION WHEN OTHERS THEN NULL; END;",
                    )
                    generated.statements
                        .map { it.sql.lines().filterNot { line -> line.trimStart().startsWith("--") } }
                        .map { it.joinToString("\n").trim().removeSuffix(";") }
                        .filter { it.isNotBlank() }
                        .forEach { sql -> withClue("statement failed:\n$sql") { stmt.execute(sql) } }
                }
            }

            val readBack = OracleSchemaReader().read(pool).schema.tables.getValue("partial_target")
            withClue("Oracle traegt kein Index-Praedikat -- der Reverse kann keines liefern") {
                readBack.indices.single { it.name == "ix_partial" }.where shouldBe null
            }

            val project = capabilityIndexCanonicalizer(DatabaseDialect.ORACLE)
            project(desired.tables.getValue("partial_target").indices.single()) shouldBe
                project(readBack.indices.single { it.name == "ix_partial" })
            // Gegenprobe: ungeprojiziert sind es zwei verschiedene Indizes,
            // und genau daran driftete der Post-Compare.
            desired.tables.getValue("partial_target").indices.single().where shouldBe "\"active\" = 1"
        }
    }

    test("Oracle accepts a parenthesised expression key and reports it without the parentheses") {
        // Belegt die Regel, die `renderKey` fuer ALLE Dialekte anwendet:
        // klammern ist ueberall zulaessig -- PostgreSQL verlangt es sogar fuer
        // jeden Ausdruck, der kein blosser Funktionsaufruf ist.
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { c ->
                c.createStatement().use {
                    it.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"pp\"'; EXCEPTION WHEN OTHERS THEN NULL; END;")
                    it.execute("""CREATE TABLE "pp" ("nm" VARCHAR2(50), "amt" NUMBER(9))""")
                    it.execute("""CREATE INDEX "p2" ON "pp" ((LOWER("nm")))""")
                    it.execute("""CREATE INDEX "p4" ON "pp" (("amt" + 2))""")
                }
            }
            val read = OracleSchemaReader().read(pool).schema.tables.getValue("pp").indices
                .associate { it.name to it.columns.single().expression }
            read["p2"] shouldBe "LOWER(\"nm\")"
            read["p4"] shouldBe "\"amt\"+2"
        }
    }
})
