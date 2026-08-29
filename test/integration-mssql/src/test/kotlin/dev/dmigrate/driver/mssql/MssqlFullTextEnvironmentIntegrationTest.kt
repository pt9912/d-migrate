package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as strShouldContain
import dev.dmigrate.cli.commands.capabilityIndexCanonicalizer
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import org.testcontainers.DockerClientFactory
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/**
 * Prueft, dass das abgeleitete SQL-Server-Image Full-Text Search wirklich
 * traegt: die Server-Eigenschaft meldet es, und ein Volltext-Katalog samt
 * Index laesst sich anlegen.
 *
 * Das Image entsteht ueber `make mssql-fts-image`
 * (`test/integration-mssql/fts/Dockerfile`); fehlt es, ueberspringt sich die
 * Spec. Warum es ein eigenes Image braucht, steht dort und in
 * `docs/planning/in-progress/mssql-dialect-scoping.md` (Slice 8).
 */
class MssqlFullTextEnvironmentIntegrationTest : FunSpec({

    val image = System.getenv("MSSQL_FTS_IMAGE") ?: "d-migrate-mssql-fts:local"

    if (!dockerImagePresent(image)) {
        xtest("full-text environment — image '$image' fehlt; `make mssql-fts-image` baut es") {}
    } else {
        val container = MSSQLServerContainer(
            DockerImageName.parse(image).asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server"),
        ).acceptLicense().withUrlParam("encrypt", "false")

        lateinit var conn: Connection

        beforeSpec {
            container.start()
            conn = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        }
        afterSpec {
            runCatching { conn.close() }
            runCatching { container.stop() }
        }

        fun scalar(sql: String): String? = conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }

        test("the derived image reports full-text search as installed") {
            // Auf dem Basis-Image liefert das `0` — genau deshalb gibt es dieses Image.
            scalar("SELECT CAST(SERVERPROPERTY('IsFullTextInstalled') AS NVARCHAR(10))") shouldBe "1"
        }

        // Der eigentliche Beleg: nicht die Eigenschaft, sondern dass ein
        // Volltext-Index wirklich entsteht. Dabei zeigen sich die beiden Objekte,
        // die das neutrale Modell nicht traegt — Katalog und Schluesselindex.
        test("a full-text catalog and index can actually be created") {
            conn.createStatement().use { st ->
                st.execute("CREATE DATABASE fts_probe")
            }
            // SQL Server verbietet Volltext in `master`, `tempdb` und `model`. Die
            // Testcontainers-URL traegt gar keine Datenbank, also wird sie angehaengt
            // — ein `replace` auf `databaseName=master` liefe ins Leere.
            DriverManager.getConnection(
                "${container.jdbcUrl};databaseName=fts_probe",
                container.username,
                container.password,
            ).use { db ->
                db.createStatement().use { st ->
                    st.execute("CREATE TABLE docs (id INT NOT NULL CONSTRAINT pk_docs PRIMARY KEY, body NVARCHAR(MAX))")
                    st.execute("CREATE FULLTEXT CATALOG ftc_docs")
                    // KEY INDEX verlangt einen EINSPALTIGEN, eindeutigen, nicht
                    // nullbaren Index — hier der Primaerschluessel.
                    st.execute("CREATE FULLTEXT INDEX ON docs (body) KEY INDEX pk_docs ON ftc_docs")
                }
                val count = db.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM sys.fulltext_indexes").use { rs -> rs.next(); rs.getInt(1) }
                }
                count shouldBe 1
            }
        }

        // Der Beleg fuer Sub-Slice 8b: nicht das erzeugte DDL, sondern dass der
        // Server es annimmt — Katalog und Index sind eigenstaendige Objekte mit
        // eigenen Regeln (einspaltiger, nicht nullbarer Schluesselindex).
        test("generated full-text DDL is accepted by the server") {
            val schema = SchemaDefinition(
                name = "ft", version = "1",
                tables = mapOf(
                    "articles" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = true),
                            "body" to ColumnDefinition(NeutralType.Text()),
                            "title" to ColumnDefinition(NeutralType.Text(200)),
                        ),
                        primaryKey = listOf("id"),
                        indices = listOf(
                            IndexDefinition(
                                "fx_articles",
                                listOf(IndexColumn("body"), IndexColumn("title")),
                                type = IndexType.FULLTEXT,
                            ),
                        ),
                    ),
                ),
            )
            // Ganze Statements, NICHT an ';' gesplittet — genau so reicht der
            // Runner sie an JDBC weiter. Nur so prueft der Test die Zusicherung,
            // dass Katalog und Index in EINEM Batch zulaessig sind.
            val result = MssqlDdlGenerator().generate(schema)
            val statements = result.statements.map { it.sql }
            withClue("erzeugt:\n${statements.joinToString("\n")}") {
                statements.any { it.contains("CREATE FULLTEXT INDEX") } shouldBe true
            }

            DriverManager.getConnection(
                "${container.jdbcUrl};databaseName=fts_probe", container.username, container.password,
            ).use { db ->
                try {
                    db.createStatement().use { stmt -> statements.forEach { stmt.execute(it) } }
                    val indexed = db.createStatement().use { stmt ->
                        stmt.executeQuery(
                            "SELECT COUNT(*) FROM sys.fulltext_index_columns " +
                                "WHERE object_id = OBJECT_ID('articles')",
                        ).use { rs -> rs.next(); rs.getInt(1) }
                    }
                    withClue("beide Spalten sollten im Volltext-Index liegen") { indexed shouldBe 2 }
                } finally {
                    runCatching { db.createStatement().use { it.execute("DROP FULLTEXT INDEX ON articles") } }
                    runCatching { db.createStatement().use { it.execute("DROP TABLE articles") } }
                    runCatching { db.createStatement().use { it.execute("DROP FULLTEXT CATALOG ftc_articles") } }
                }
            }
        }

        // Sub-Slice 8c: der Reverse liest den Volltext-Index zurueck. SQL Server
        // benennt ihn nicht, der Name wird synthetisiert und gemeldet (R348).
        test("a full-text index survives the round trip") {
            val schema = SchemaDefinition(
                name = "ft-rt", version = "1",
                tables = mapOf(
                    "notes" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = true),
                            "body" to ColumnDefinition(NeutralType.Text()),
                        ),
                        primaryKey = listOf("id"),
                        indices = listOf(
                            IndexDefinition("fx_notes", listOf(IndexColumn("body")), type = IndexType.FULLTEXT),
                        ),
                    ),
                ),
            )
            val statements = MssqlDdlGenerator().generate(schema).statements.map { it.sql }

            HikariConnectionPoolFactory.create(
                ConnectionConfig(
                    dialect = DatabaseDialect.MSSQL,
                    host = container.host,
                    port = container.firstMappedPort,
                    database = "fts_probe",
                    user = container.username,
                    password = container.password,
                    ssl = SslSettings(SslMode.DISABLE),
                ),
            ).use { pool ->
                try {
                    pool.borrow().asJdbc().use { conn ->
                        conn.createStatement().use { stmt -> statements.forEach { stmt.execute(it) } }
                    }
                    val readBack = MssqlSchemaReader().read(pool)
                    val index = readBack.schema.tables.getValue("notes").indices
                        .single { it.type == IndexType.FULLTEXT }

                    index.columns.map { it.name } shouldBe listOf("body")
                    // Der Name ist synthetisiert, nicht der aus dem Modell.
                    index.name shouldBe "ft_notes"
                    readBack.notes.any { it.code == "R348" } shouldBe true

                    // Und deshalb der eigentliche Punkt: der Fingerabdruck traegt
                    // den Indexnamen. Ohne Kanonisierung driftete jeder
                    // Volltext-Round-Trip im Post-Compare — genau dort, wo
                    // `migrate --execute` ihn prueft.
                    val canonicalize = capabilityIndexCanonicalizer(DatabaseDialect.MSSQL)
                    // Nur die eigene Tabelle: die Datenbank ist spec-weit geteilt.
                    val onlyNotes = readBack.schema.copy(
                        tables = mapOf("notes" to readBack.schema.tables.getValue("notes")),
                    )
                    val before = MigrationFingerprint.compute(schema, canonicalizeIndex = canonicalize)
                    val after = MigrationFingerprint.compute(onlyNotes, canonicalizeIndex = canonicalize)
                    withClue("Modellname 'fx_notes' vs. zurueckgelesener 'ft_notes'") {
                        after shouldBe before
                    }
                } finally {
                    pool.borrow().asJdbc().use { conn ->
                        conn.createStatement().use { stmt ->
                            runCatching { stmt.execute("DROP FULLTEXT INDEX ON notes") }
                            runCatching { stmt.execute("DROP TABLE notes") }
                            runCatching { stmt.execute("DROP FULLTEXT CATALOG ftc_notes") }
                        }
                    }
                }
            }
        }
    }
})

/**
 * Ob das Image vorliegt — gefragt ueber denselben Docker-Socket, den
 * Testcontainers benutzt.
 *
 * Nicht ueber die `docker`-CLI: der Integrationslauf faehrt im
 * `gradle`-Basisimage, das den Socket gemountet bekommt, aber kein
 * `docker`-Binary hat. Ein Shell-Aufruf schluege dort immer fehl und die Spec
 * uebersprunge sich immer — unbemerkt, weil ein uebersprungener Test gruen ist.
 */
private fun dockerImagePresent(image: String): Boolean = runCatching {
    DockerClientFactory.instance().client().inspectImageCmd(image).exec()
    true
}.getOrDefault(false)
