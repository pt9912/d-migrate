package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import java.sql.DriverManager
import java.time.Duration

/**
 * Slice 8: Oracle Text gegen ein ECHTES Oracle.
 *
 * **Eigene Spezifikation, eigenes Image.** Die uebrigen Oracle-Tests fahren
 * `23-slim-faststart`; dort fehlt Oracle Text vollstaendig — das Oracle-Home
 * hat kein `ctx`-Verzeichnis, `CTXSYS` existiert nicht, und
 * `INDEXTYPE IS CTXSYS.CONTEXT` scheitert mit `ORA-29833`. Die Vollvariante
 * bringt es mit, ist aber deutlich groesser; sie deshalb nur hier zu
 * verwenden haelt die Laufzeit der uebrigen Tests unveraendert.
 *
 * Der von Testcontainers angelegte Nutzer hat die Rolle `CTXAPP` **nicht** —
 * ohne sie ist kein Text-Index anlegbar. Der Test vergibt sie als `system`,
 * so wie ein Betreiber es vor der Migration taete.
 */
class OracleFullTextIntegrationTest : FunSpec({

    val container = OracleContainer(TestImages.ORACLE_FULL)
        .withStartupTimeout(Duration.ofMinutes(8))

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        DriverManager.getConnection(container.jdbcUrl, "system", container.password).use { admin ->
            admin.createStatement().use { it.execute("GRANT CTXAPP TO ${container.username}") }
        }
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

    val schema = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "docs" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "body" to ColumnDefinition(NeutralType.Text()),
                ),
                primaryKey = listOf("id"),
                indices = listOf(
                    IndexDefinition(
                        name = "ft_docs_body",
                        columns = listOf(IndexColumn("body")),
                        type = IndexType.FULLTEXT,
                    ),
                ),
            ),
        ),
    )

    fun executableStatements(sqls: List<String>): List<String> = sqls
        .map { it.lines().filterNot { line -> line.trimStart().startsWith("--") } }
        .map { it.joinToString("\n").trim().removeSuffix(";") }
        .filter { it.isNotBlank() }

    test("the generated Oracle Text index is valid, finds rows without a manual sync, and reads back") {
        val ddl = OracleDdlGenerator().generate(schema)
        withClue("notes: ${ddl.notes.map { it.code }}") {
            ddl.notes.none { it.code == "E057" } shouldBe true
        }

        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"docs\"'; EXCEPTION WHEN OTHERS THEN NULL; END;")
                    executableStatements(ddl.statements.map { it.sql }).forEach { sql ->
                        withClue("statement failed:\n$sql") { stmt.execute(sql) }
                    }
                }

                // Der Kern der Slice-Entscheidung: ohne `SYNC (ON COMMIT)`
                // liefert die Suche nach einem INSERT null Treffer und bliebe
                // leer, bis jemand CTX_DDL.SYNC_INDEX ruft (live gemessen).
                conn.createStatement().use { stmt ->
                    stmt.execute("INSERT INTO \"docs\" VALUES (1, 'the quick brown fox')")
                }
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT COUNT(*) FROM \"docs\" WHERE CONTAINS(\"body\", 'quick') > 0",
                    ).use { rs ->
                        rs.next() shouldBe true
                        withClue("der Index findet nichts — fehlt SYNC (ON COMMIT)?") { rs.getInt(1) shouldBe 1 }
                    }
                }
            }

            val reversed = OracleSchemaReader().read(pool).schema

            // Ein Oracle-Text-Index legt im selben Schema sieben eigene
            // Tabellen an (gemessen: DR${'$'}…${'$'}B/C/I/K/N/Q/U). Kaemen sie mit,
            // haette der Reverse Phantom-Tabellen, der Post-Compare meldete
            // nach JEDEM Lauf Drift, und `data transfer` kopierte
            // Oracle-interne Token-Tabellen.
            withClue("gelesene Tabellen: ${reversed.tables.keys}") {
                reversed.tables.keys shouldBe setOf("docs")
            }

            val index = reversed.tables.getValue("docs").indices.single { it.name == "ft_docs_body" }
            index.type shouldBe IndexType.FULLTEXT
            index.columnNames shouldBe listOf("body")
        }
    }

    test("a multi-column full-text index is refused rather than silently split") {
        // ORA-29851 -- gemessen. Der Generator laesst es gar nicht erst
        // entstehen, statt es dem Server zu ueberlassen.
        val multi = schema.copy(
            tables = mapOf(
                "docs" to schema.tables.getValue("docs").copy(
                    columns = schema.tables.getValue("docs").columns +
                        ("title" to ColumnDefinition(NeutralType.Text(maxLength = 200))),
                    indices = listOf(
                        IndexDefinition(
                            name = "ft_multi",
                            columns = listOf(IndexColumn("title"), IndexColumn("body")),
                            type = IndexType.FULLTEXT,
                        ),
                    ),
                ),
            ),
        )
        val ddl = OracleDdlGenerator().generate(multi)
        ddl.notes.single { it.objectName == "ft_multi" }.code shouldBe "E057"

        // Und der Grund dafuer, gegen den Server geprueft statt behauptet:
        // die Anweisung, die der Generator NICHT schreibt, lehnt Oracle ab.
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                // Eigene Tabelle: der Test soll nicht davon abhaengen, dass
                // ein anderer vorher gelaufen ist.
                conn.createStatement().use { stmt ->
                    stmt.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"two_cols\"'; EXCEPTION WHEN OTHERS THEN NULL; END;")
                    stmt.execute("""CREATE TABLE "two_cols" ("title" VARCHAR2(200), "body" CLOB)""")
                }
                val failure = runCatching {
                    conn.createStatement().use {
                        it.execute(
                            """CREATE INDEX "ft_multi" ON "two_cols" ("title", "body") """ +
                                "INDEXTYPE IS CTXSYS.CONTEXT",
                        )
                    }
                }.exceptionOrNull()
                withClue("Oracle nahm einen mehrspaltigen Domain-Index an?") {
                    (failure?.message ?: "") shouldContain "ORA-29851"
                }
            }
        }
    }
})
