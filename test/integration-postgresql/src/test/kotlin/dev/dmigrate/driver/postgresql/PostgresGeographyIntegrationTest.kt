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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * P4 — eine PostGIS-`geography`-Spalte liest als neutrale Geometrie.
 *
 * Bis hierher kannte `mapUserDefined` nur `geometry`; aus
 * `geography(Point,4326)` wurde `type: enum, ref_type: geography`, und das
 * gelesene Schema war mit `E007` ungueltig — jede PostgreSQL-Zelle der
 * Compare-Matrix waere daran gescheitert.
 *
 * Nur ein echter Server zeigt, was `geography_columns` fuehrt: den Subtyp
 * gemischt geschrieben (`Point`, nicht `POINT` wie `geometry_columns`) und
 * eine Spalte ohne Typmodifikator mit SRID `0`. Und nur an einem echten
 * Katalog laesst sich die Schema-Pruefung zeigen: PostGIS liegt hier im
 * Schema `postgis`, ein **Anwendertyp** gleichen Namens in `public` — ueber
 * den Namen allein sind die beiden nicht zu unterscheiden.
 */
class PostgresGeographyIntegrationTest : FunSpec({

    val container = PostgreSQLContainer(TestImages.POSTGIS)
        .withDatabaseName("dmigrate_geography")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(PostgresDriver())
        // Die Extension geht in ihr eigenes Schema; das Image installiert sie
        // per Init-Skript in `public`, und dort waere ein Anwendertyp namens
        // `geography` gar nicht anlegbar (Namenskollision). Der `search_path`
        // nennt beide, damit die Registriersichten aufloesen.
        HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL,
                host = container.host,
                port = container.firstMappedPort,
                database = "dmigrate_geography",
                user = "dmigrate",
                password = "dmigrate",
            ),
        ).use { setup ->
            setup.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP EXTENSION IF EXISTS postgis CASCADE")
                    stmt.execute("CREATE SCHEMA IF NOT EXISTS postgis")
                    stmt.execute("CREATE EXTENSION postgis SCHEMA postgis")
                    stmt.execute("ALTER DATABASE dmigrate_geography SET search_path = public, postgis")
                }
            }
        }
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL,
                host = container.host,
                port = container.firstMappedPort,
                database = "dmigrate_geography",
                user = "dmigrate",
                password = "dmigrate",
            ),
        )
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE geo_typed (
                        id integer PRIMARY KEY,
                        area postgis.geography(Point, 4326)
                    )
                    """.trimIndent(),
                )
                // L4: `geography` ohne Typmodifikator.
                stmt.execute("CREATE TABLE geo_bare (id integer PRIMARY KEY, area postgis.geography)")
                // L4: ein geodaetischer SRID ungleich 4326.
                stmt.execute(
                    "CREATE TABLE geo_etrs (id integer PRIMARY KEY, area postgis.geography(Point, 4258))",
                )
                // L4 / Gegenprobe: `geometry` bleibt `geometry` und meldet R401.
                stmt.execute(
                    "CREATE TABLE geo_plain (id integer PRIMARY KEY, shape postgis.geometry(Point, 4326))",
                )
                // L4: ein Anwendertyp namens `geography`, im gelesenen Schema.
                stmt.execute("CREATE TYPE geography AS ENUM ('a', 'b')")
                stmt.execute("CREATE TABLE user_typed (id integer PRIMARY KEY, kind geography)")
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("geography(Point,4326) liest als geometry mit Subtyp und SRID, gemeldet mit R403") {
        val result = PostgresSchemaReader().read(pool)

        val type = result.schema.tables.getValue("geo_typed").columns.getValue("area").type
        withClue("gelesen wurde $type") {
            (type as NeutralType.Geometry).geometryType shouldBe GeometryType.of("point")
            type.srid shouldBe 4326
        }

        val note = result.notes.single { it.objectName == "geo_typed.area" }
        note.code shouldBe "R403"
        note.severity shouldBe SchemaReadSeverity.WARNING
        note.message shouldContainText "geography"
        note.message shouldContainText "geometry"
        note.hint!! shouldContainText "geodesic"
    }

    test("L4: geography ohne Typmodifikator liest GEOMETRY ohne SRID, geography(Point,4258) behaelt 4258") {
        val result = PostgresSchemaReader().read(pool)

        val bare = result.schema.tables.getValue("geo_bare").columns.getValue("area").type
        withClue("gelesen wurde $bare") {
            (bare as NeutralType.Geometry).geometryType shouldBe GeometryType.GEOMETRY
            bare.srid.shouldBeNull()
        }

        val etrs = result.schema.tables.getValue("geo_etrs").columns.getValue("area").type
        withClue("gelesen wurde $etrs") {
            (etrs as NeutralType.Geometry).srid shouldBe 4258
        }
    }

    test("L4: geometry bleibt geometry mit R401, nicht R403") {
        val result = PostgresSchemaReader().read(pool)

        val type = result.schema.tables.getValue("geo_plain").columns.getValue("shape").type
        (type as NeutralType.Geometry).srid shouldBe 4326

        result.notes.filter { it.objectName == "geo_plain.shape" }.map { it.code } shouldBe listOf("R401")
    }

    test("L4: ein Anwendertyp namens geography bleibt enum mit seinem custom_types-Eintrag") {
        val result = PostgresSchemaReader().read(pool)

        val type = result.schema.tables.getValue("user_typed").columns.getValue("kind").type
        withClue("gelesen wurde $type") {
            (type as NeutralType.Enum).refType shouldBe "geography"
        }
        result.notes.filter { it.objectName == "user_typed.kind" }.shouldBeEmpty()
        // Ohne den Eintrag zeigte `ref_type` ins Leere — genau der Fall, den
        // der Silent-Loss-Check der Matrix als Klasse `reftype` meldet.
        result.schema.customTypes.keys shouldContain "geography"
    }
})
