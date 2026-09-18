package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * P2b — was einer **Extension** gehoert, gehoert nicht ins Modell.
 *
 * PostGIS in `public` bringt rund 800 Funktionen, 22 Aggregate und die zwei
 * Sichten `geometry_columns`/`geography_columns` mit (gemessen an PostGIS
 * 3.6). Sie stehen im Anwenderschema und kamen als Anwenderobjekte ins
 * Reverse — ein Konsument meldete ein Artefakt von 320 kB gegen 816 B.
 *
 * Der Filter kommt aus dem Katalog (`pg_depend.deptype = 'e'`), nicht aus dem
 * Namen. Nur ein echter Server zeigt beides: dass er die Flut nimmt **und**
 * dass er kein Anwenderobjekt mitnimmt — eine Funktion, die PostGIS benutzt,
 * und eine Sicht ueber eine Geometriespalte bleiben.
 */
class PostgresExtensionObjectFilterIntegrationTest : FunSpec({

    val container = PostgreSQLContainer(TestImages.POSTGIS)
        .withDatabaseName("dmigrate_extfilter")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var pool: ConnectionPool

    val options = SchemaReadOptions(
        includeViews = true,
        includeFunctions = true,
        includeProcedures = true,
        includeTriggers = false,
    )

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(PostgresDriver())
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL,
                host = container.host,
                port = container.firstMappedPort,
                database = "dmigrate_extfilter",
                user = "dmigrate",
                password = "dmigrate",
            ),
        )
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                // Genau die Lage des Konsumentenbefunds: PostGIS in `public`.
                stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis")
                stmt.execute(
                    """
                    CREATE TABLE places (
                        id integer PRIMARY KEY,
                        label text NOT NULL,
                        shape geometry(Point, 4326)
                    )
                    """.trimIndent(),
                )
                // Gegenprobe 1: eine Anwenderfunktion, die PostGIS **benutzt**
                // (Abhaengigkeit `n`, nicht `e`).
                stmt.execute(
                    """
                    CREATE FUNCTION place_longitude(p geometry) RETURNS double precision
                    LANGUAGE sql IMMUTABLE AS 'SELECT ST_X(p)'
                    """.trimIndent(),
                )
                // Gegenprobe 2: eine Anwendersicht ueber die Geometriespalte.
                stmt.execute("CREATE VIEW place_points AS SELECT id, ST_AsText(shape) AS wkt FROM places")
                // Gegenprobe 3: eine Anwenderprozedur.
                stmt.execute("CREATE PROCEDURE touch_places() LANGUAGE sql AS 'SELECT 1'")
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("ein PostGIS-in-public-Reverse fuehrt keine Funktion, kein Aggregat und keine Sicht der Extension") {
        val result = PostgresSchemaReader().read(pool, options)

        val functions = result.schema.functions.keys
        val views = result.schema.views.keys
        val aggregates = result.schema.aggregates.keys

        withClue("Funktionen: ${functions.sorted()}") {
            // Namensproben aus dem PostGIS-Vorrat — keine davon gehoert dem
            // Anwender. `st_asbinary` liest der Datenpfad selbst.
            functions.none { it.startsWith("st_") } shouldBe true
            functions.none { it.startsWith("geometry_") } shouldBe true
            functions.none { it.startsWith("postgis_") } shouldBe true
        }
        withClue("Sichten: ${views.sorted()}") {
            views shouldNotContainAny listOf("geometry_columns", "geography_columns")
        }
        withClue("Aggregate: ${aggregates.sorted()}") {
            aggregates.none { it.startsWith("st_") } shouldBe true
        }

        // Die Groessenordnung, nicht nur die Namen: ohne den Filter stuenden
        // hier Hunderte.
        withClue("Funktionen: ${functions.sorted()}") { (functions.size <= 5) shouldBe true }
        withClue("Aggregate: ${aggregates.sorted()}") { aggregates.shouldBeEmpty() }
    }

    test("Gegenprobe: Anwenderfunktion, -sicht und -prozedur bleiben") {
        val result = PostgresSchemaReader().read(pool, options)

        // Routinen stehen unter ihrem kanonischen Schluessel (Name samt
        // neutraler Parametertypen), Sichten unter ihrem Namen.
        withClue("Funktionen: ${result.schema.functions.keys}") {
            result.schema.functions.keys.any { it.startsWith("place_longitude") } shouldBe true
        }
        result.schema.views.keys shouldContain "place_points"
        withClue("Prozeduren: ${result.schema.procedures.keys}") {
            result.schema.procedures.keys.any { it.startsWith("touch_places") } shouldBe true
        }
        // Und die Tabelle, um die es geht, ist unveraendert da (die
        // extension-eigene `spatial_ref_sys` nicht — die filtert der
        // Tabellenpfad seit 5a).
        result.schema.tables.keys shouldContain "places"
        result.schema.tables.keys.contains("spatial_ref_sys") shouldBe false
    }
})

private infix fun <T> Collection<T>.shouldNotContainAny(unwanted: Collection<T>) {
    val hits = unwanted.filter { it in this }
    hits shouldBe emptyList()
}
