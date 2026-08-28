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
 * Uebersprungen, wenn das Image fehlt: es entsteht nur mit Netz und gehoert
 * deshalb nicht in die netzlosen Gates.
 */
class MssqlFullTextEnvironmentIntegrationTest : FunSpec({

    val image = System.getenv("MSSQL_FTS_IMAGE") ?: "d-migrate-mssql-fts:local"

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
})
