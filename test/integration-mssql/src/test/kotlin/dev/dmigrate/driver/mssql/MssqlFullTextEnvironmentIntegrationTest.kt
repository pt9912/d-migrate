package dev.dmigrate.driver.mssql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/**
 * Slice 8a: die Testumgebung fuer den Volltext-Slice.
 *
 * Das gepinnte Basis-Image kann **kein** Full-Text Search — `IsFullTextInstalled`
 * meldet dort `0`, und `mssql-server-fts` ist nicht aufloesbar. Der Slice baut
 * sich deshalb ein abgeleitetes Image (`test/integration-mssql/fts/Dockerfile`,
 * `make mssql-fts-image`).
 *
 * Diese Spec ist der Beleg, dass das Image haelt, was es soll. Sie ist bewusst
 * die **erste** Arbeit des Slice: alles Weitere — Katalog, Schluesselindex,
 * Reverse — waere ohne sie auf Annahmen gebaut.
 *
 * Uebersprungen, wenn das Image fehlt. Das ist keine Bequemlichkeit, sondern
 * Bedingung: das Image entsteht nur mit Netz (528 MB Microsoft-Pakete, 3,63 GB
 * Ergebnis) und wird deshalb nicht in jedem Lauf gebaut. Wer es hat, bekommt den
 * Beleg; wer nicht, sieht einen uebersprungenen Test mit dem Grund im Namen.
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
 * Ob das Image lokal vorliegt. Bewusst `docker image inspect` statt eines
 * Pull-Versuchs: ein Pull gegen ein nur lokal gebautes Tag laeuft in einen
 * 404 der Registry, und der kostet Zeit und produziert eine irrefuehrende
 * Fehlermeldung ("repository does not exist").
 */
private fun dockerImagePresent(image: String): Boolean = runCatching {
    ProcessBuilder("docker", "image", "inspect", image)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
        .waitFor() == 0
}.getOrDefault(false)
