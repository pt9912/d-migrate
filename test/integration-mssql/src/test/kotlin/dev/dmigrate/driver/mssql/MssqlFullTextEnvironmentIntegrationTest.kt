package dev.dmigrate.driver.mssql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
    }
})

/**
 * Ob das Image lokal vorliegt. `docker image inspect` statt eines
 * Pull-Versuchs: ein nur lokal gebautes Tag kennt keine Registry.
 */
private fun dockerImagePresent(image: String): Boolean = runCatching {
    ProcessBuilder("docker", "image", "inspect", image)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
        .waitFor() == 0
}.getOrDefault(false)
