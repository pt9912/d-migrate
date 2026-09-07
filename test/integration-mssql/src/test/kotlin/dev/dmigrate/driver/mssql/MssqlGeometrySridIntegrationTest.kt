package dev.dmigrate.driver.mssql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Woher die SRID einer SQL-Server-Geometrie kommt — gegen echtes SQL Server.
 *
 * SQL Server fuehrt das Bezugssystem am **Wert**, nicht an der Spalte: die
 * Spaltenmetadaten sagen nur `geometry`, und WKB traegt selbst nichts. Ein
 * Transfer, der nur die Spalte fragt, schreibt am Ziel den Typ-Default (0)
 * und legt Werte aus UTM 32N als Gradmasse ab, ohne dass jemand einen Fehler
 * sieht.
 *
 * Was kein Unit-Test zeigen kann: dass `.STSrid` wirklich das liefert, was
 * beim Einfuegen angegeben wurde, und dass eine Spalte tatsaechlich Werte in
 * verschiedenen Bezugssystemen halten darf — die Voraussetzung dafuer, dass
 * die Ablehnung im Transfer ueberhaupt einen Fall hat.
 */
class MssqlGeometrySridIntegrationTest : FunSpec({

    val container = startMssqlContainer()

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = poolFor(container, "dmigrate_geom_srid")
        execDdl(
            pool,
            """
            CREATE TABLE places (
                id      INT NOT NULL PRIMARY KEY,
                utm     geometry NULL,
                globe   geography NULL,
                mixed   geometry NULL,
                nothing geometry NULL
            )
            """.trimIndent(),
            """
            INSERT INTO places (id, utm, globe, mixed, nothing) VALUES
              (1, geometry::STGeomFromText('POINT(500000 5600000)', 25832),
                  geography::STGeomFromText('POINT(9.4797 51.3127)', 4326),
                  geometry::STGeomFromText('POINT(1 2)', 4326), NULL),
              (2, geometry::STGeomFromText('POINT(600000 5700000)', 25832),
                  NULL,
                  geometry::STGeomFromText('POINT(3 4)', 25832), NULL)
            """.trimIndent(),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun queryOne(sql: String): Any? = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null }
        }
    }

    test("the column metadata carry no SRID at all") {
        val table = MssqlSchemaReader().read(pool, SchemaReadOptions()).schema.tables.getValue("places")
        // `geometry` schweigt; `geography` traegt nur den Typ-Default, nicht
        // eine gelesene Angabe -- fuer 25832 gaebe es keinen Weg ueber das Schema.
        withClue("gelesene Spalten: ${table.columns.keys}") {
            (table.columns.getValue("utm").type as NeutralType.Geometry).srid shouldBe null
        }
    }

    test("the values do carry it, and a probe reads it back per column") {
        val srids = MssqlDataReader().geometrySrids(
            pool, "places", listOf("utm", "globe", "mixed", "nothing"),
        )

        srids.getValue("utm") shouldContainExactly listOf(25832)
        srids.getValue("globe") shouldContainExactly listOf(4326)
        // Eine Spalte darf in SQL Server mehrere Bezugssysteme halten. Genau
        // deshalb genuegt die erste Zeile nicht als Antwort.
        srids.getValue("mixed") shouldContainExactly listOf(4326, 25832)
        // Nur NULLs: keine Aussage, kein Eintrag.
        srids.containsKey("nothing") shouldBe false
    }

    test("a table without geometry columns is never queried") {
        MssqlDataReader().geometrySrids(pool, "places", emptyList()).shouldBeEmpty()
    }

    test("the import binds the SRID it is handed, not the type default") {
        execDdl(pool, "CREATE TABLE target_places (id INT NOT NULL PRIMARY KEY, utm geometry NULL)")
        val wkb = queryOne("SELECT utm.STAsBinary() FROM places WHERE id = 1")

        MssqlDataWriter().openTable(
            pool, "target_places",
            ImportOptions(onConflict = OnConflict.ABORT, sourceGeometrySrids = mapOf("utm" to 25832)),
        ).use { session ->
            session.write(
                DataChunk(
                    table = "target_places",
                    columns = listOf("id", "utm").map { ColumnDescriptor(name = it, nullable = true) },
                    rows = listOf(arrayOf<Any?>(1, wkb)),
                    chunkIndex = 0,
                ),
            )
            session.commitChunk()
            session.finishTable()
        }

        // Ohne die uebergebene Angabe stuende hier 0 — der Typ-Default von
        // `geometry`, und damit ein anderes Bezugssystem als in der Quelle.
        queryOne("SELECT utm.STSrid FROM target_places WHERE id = 1") shouldBe 25832
    }
})
