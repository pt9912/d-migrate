package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * P3 — PostGIS liegt in einem eigenen Schema, das **nicht** im `search_path`
 * steht.
 *
 * Dann loest `geometry_columns` nicht auf, und jede Geometriespalte kommt
 * ohne Subtyp und ohne SRID zurueck. Gemeldet wurde bis hierher nur `R401`
 * (`INFO`, je Spalte) — das nennt weder Ursache noch Ausweg. Der Grund ist an
 * der Abfrage bekannt und ging dort verloren.
 *
 * Nur ein echter Server kann das zeigen: die Spalte muss den PostGIS-Typ
 * tragen (`udt_name = 'geometry'`, aufloesbar ueber den qualifizierten
 * Typnamen), waehrend die **Sicht** unerreichbar ist. Ein Unit-Test kann
 * diese Lage nur behaupten.
 */
class PostgresPostgisSearchPathIntegrationTest : FunSpec({

    val container = PostgreSQLContainer(TestImages.POSTGIS)
        .withDatabaseName("dmigrate_postgis")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var pool: ConnectionPool

    fun newPool(): ConnectionPool = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_postgis",
            user = "dmigrate",
            password = "dmigrate",
        ),
    )

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(PostgresDriver())
        pool = newPool()
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                // Die Extension geht in ihr eigenes Schema; der `search_path`
                // der Datenbank nennt es **nicht**. Genau die Lage, die der
                // Konsument gemeldet hat.
                //
                // Das Image installiert PostGIS per Init-Skript in `public`;
                // `CREATE EXTENSION IF NOT EXISTS … SCHEMA postgis` waere dort
                // ein stilles No-op, und `postgis.geometry` gaebe es nicht.
                // Deshalb erst weg, dann an die richtige Stelle.
                stmt.execute("DROP EXTENSION IF EXISTS postgis CASCADE")
                stmt.execute("CREATE SCHEMA IF NOT EXISTS postgis")
                stmt.execute("CREATE EXTENSION postgis SCHEMA postgis")
                stmt.execute("ALTER DATABASE dmigrate_postgis SET search_path = public")
                stmt.execute(
                    """
                    CREATE TABLE gis_places (
                        id integer PRIMARY KEY,
                        location postgis.geometry(Point, 4326) NOT NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute("CREATE TABLE gis_plain (id integer PRIMARY KEY, label text NOT NULL)")
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    /**
     * Der `search_path` wird auf **Datenbankebene** gesetzt: er gilt dann fuer
     * jede **neue** Sitzung. Ein `SET` auf einer geborgten Verbindung saehe
     * der Reader nicht sicher — der Pool koennte ihm eine andere geben.
     */
    fun databaseSearchPath(value: String) = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("ALTER DATABASE dmigrate_postgis SET search_path = $value")
        }
    }

    test("ohne PostGIS im search_path: R405 mit Grund und Ausweg, und die Spalte verliert Subtyp und SRID") {
        val result = PostgresSchemaReader().read(pool)

        val note = result.notes.single { it.code == "R405" }
        note.severity shouldBe SchemaReadSeverity.WARNING
        note.objectName shouldBe "gis_places"
        note.message shouldContain "geometry_columns"
        note.message shouldContain "location"
        note.hint!! shouldContain "search_path"

        val type = result.schema.tables.getValue("gis_places").columns.getValue("location").type
        withClue("gelesen wurde $type") {
            (type as NeutralType.Geometry).geometryType shouldBe GeometryType.GEOMETRY
            type.srid shouldBe null
        }

        // Gegenprobe im selben Lauf: die Tabelle ohne Geometriespalte meldet
        // nichts — sonst traefe die Warnung jede Tabelle der Datenbank.
        result.notes.filter { it.code == "R405" && it.objectName == "gis_plain" }.shouldBeEmpty()
    }

    /**
     * Gegenprobe: mit PostGIS im `search_path` loest die Sicht auf, Subtyp und
     * SRID kommen mit — und `R405` entsteht **nicht**.
     */
    test("mit PostGIS im search_path: kein R405, Subtyp und SRID sind da") {
        databaseSearchPath("public, postgis")
        // Ein **neuer** Pool, damit die Sitzung den neuen `search_path` sieht;
        // die Verbindungen des alten tragen noch den vorherigen.
        val fresh = newPool()
        try {
            val result = PostgresSchemaReader().read(fresh)

            result.notes.filter { it.code == "R405" }.shouldBeEmpty()
            val type = result.schema.tables.getValue("gis_places").columns.getValue("location").type
            withClue("gelesen wurde $type") {
                (type as NeutralType.Geometry).geometryType shouldBe GeometryType.of("point")
                type.srid shouldBe 4326
            }
        } finally {
            runCatching { fresh.close() }
            databaseSearchPath("public")
        }
    }
})
