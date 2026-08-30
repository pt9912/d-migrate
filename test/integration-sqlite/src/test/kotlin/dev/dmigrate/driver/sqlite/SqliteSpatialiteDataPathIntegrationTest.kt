package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * SpatiaLite im Datenpfad, gegen eine echte Datei mit geladener Extension.
 *
 * SpatiaLite legt Geometrie in einem **eigenen** Binaerformat ab: der rohe BLOB
 * einer 2D-Punktspalte misst 60 Bytes, ihr WKB 21. Ohne die Wicklung in
 * `ST_AsBinary` liefe genau dieses Eigenformat in eine PostGIS-Spalte.
 */
class SqliteSpatialiteDataPathIntegrationTest : FunSpec({

    lateinit var dir: Path
    lateinit var dbFile: Path

    fun configFor(spatialite: Boolean) = ConnectionConfig(
        dialect = DatabaseDialect.SQLITE,
        host = null,
        port = null,
        database = dbFile.toString(),
        user = null,
        password = null,
        params = if (spatialite) mapOf("spatialite" to "true") else emptyMap(),
    )

    beforeSpec {
        dir = Files.createTempDirectory("spatialite-datapath")
        dbFile = dir.resolve("places.db")
        HikariConnectionPoolFactory.create(configFor(spatialite = true)).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SELECT InitSpatialMetaData(1)")
                    stmt.execute("CREATE TABLE places (id INTEGER PRIMARY KEY, name TEXT)")
                    stmt.execute("SELECT AddGeometryColumn('places','geom',4326,'POINT','XY')")
                    stmt.execute(
                        "INSERT INTO places (id, name, geom) " +
                            "VALUES (1, 'a', GeomFromText('POINT(1 2)',4326))",
                    )
                }
            }
        }
    }

    afterSpec { dir.toFile().deleteRecursively() }

    test("a geometry column is read as WKB, not as SpatiaLite's own blob") {
        HikariConnectionPoolFactory.create(configFor(spatialite = true)).use { pool ->
            SqliteDataReader().streamTable(pool, "places", null, 100).use { seq ->
                seq.schema.columns.single { it.name == "geom" }.neutralType
                    .shouldBeInstanceOf<dev.dmigrate.core.model.NeutralType.Geometry>()
                val chunk = seq.toList().single()
                val at = chunk.columns.indexOfFirst { it.name == "geom" }
                // WKB eines 2D-Punkts: 1 Byte Byte-Order + 4 Byte Typ + 2x8 Byte.
                (chunk.rows.single()[at] as ByteArray).size shouldBe 21
            }
        }
    }

    // Ohne `?spatialite=true` ist die Extension nicht geladen; `ST_AsBinary`
    // gaebe es dann nicht. Der Reader muss das an der Verbindung erkennen und
    // die Wicklung lassen, statt an einer unbekannten Funktion zu scheitern.
    test("without the extension the read falls back instead of failing") {
        HikariConnectionPoolFactory.create(configFor(spatialite = false)).use { pool ->
            SqliteDataReader().streamTable(pool, "places", null, 100).use { seq ->
                val chunk = seq.toList().single()
                val at = chunk.columns.indexOfFirst { it.name == "geom" }
                // Das rohe SpatiaLite-Format, deutlich groesser als sein WKB.
                (chunk.rows.single()[at] as ByteArray).size shouldBe 60
            }
        }
    }

    // Der Beleg, der zaehlt: gelesen, geschrieben, und im Ziel steht wieder ein
    // Punkt — nicht ein BLOB, den SpatiaLite nicht deuten kann.
    test("a geometry round-trips into a second SpatiaLite database") {
        val targetFile = dir.resolve("target.db")
        val targetConfig = ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null, port = null, database = targetFile.toString(),
            user = null, password = null,
            params = mapOf("spatialite" to "true"),
        )
        HikariConnectionPoolFactory.create(targetConfig).use { targetPool ->
            targetPool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SELECT InitSpatialMetaData(1)")
                    stmt.execute("CREATE TABLE places (id INTEGER PRIMARY KEY, name TEXT)")
                    stmt.execute("SELECT AddGeometryColumn('places','geom',4326,'POINT','XY')")
                }
            }

            HikariConnectionPoolFactory.create(configFor(spatialite = true)).use { sourcePool ->
                SqliteDataReader().streamTable(sourcePool, "places", null, 100).use { seq ->
                    SqliteDataWriter().openTable(targetPool, "places", ImportOptions()).use { session ->
                        seq.forEach { chunk -> session.write(chunk) }
                        session.commitChunk()
                        session.finishTable()
                    }
                }
            }

            targetPool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    // SpatiaLite deutet den Wert wieder als Geometrie.
                    stmt.executeQuery("SELECT AsText(geom), ST_SRID(geom) FROM places").use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe "POINT(1 2)"
                        rs.getInt(2) shouldBe 4326
                    }
                }
            }
        }
    }

    // Der deklarierte Typname taugt nicht als Kriterium: SQLite erzwingt keine
    // Typen. Eine Spalte darf `POINT` heissen und Text enthalten — durch
    // `ST_AsBinary` geschickt kaeme `NULL` heraus, und die Daten waeren ohne
    // Fehlermeldung weg.
    test("a column merely declared POINT is left alone") {
        val file = dir.resolve("fake.db")
        val cfg = ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null, port = null, database = file.toString(), user = null, password = null,
            params = mapOf("spatialite" to "true"),
        )
        HikariConnectionPoolFactory.create(cfg).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SELECT InitSpatialMetaData(1)")
                    stmt.execute("CREATE TABLE fake (id INTEGER PRIMARY KEY, g POINT)")
                    stmt.execute("INSERT INTO fake VALUES (1, 'POINT(1 2)')")
                }
            }
            SqliteDataReader().streamTable(pool, "fake", null, 100).use { seq ->
                val chunk = seq.toList().single()
                val at = chunk.columns.indexOfFirst { it.name == "g" }
                chunk.rows.single()[at] shouldBe "POINT(1 2)"
            }
        }
    }

    // Ohne geladene Extension gibt es `GeomFromWKB` nicht. Wickelte der
    // Schreibpfad trotzdem, scheiterte der Einsatz an „no such function" — auf
    // einem Weg, der vorher lief.
    test("a plain SQLite target without the extension still takes the rows") {
        val file = dir.resolve("plain.db")
        val cfg = ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null, port = null, database = file.toString(), user = null, password = null,
        )
        HikariConnectionPoolFactory.create(cfg).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE plain (id INTEGER PRIMARY KEY, geom GEOMETRY)")
                }
            }
            val schema = ChunkSchema(
                table = "plain",
                columns = listOf(
                    ChunkColumnSchema("id", nullable = false, neutralType = NeutralType.Integer),
                    ChunkColumnSchema("geom", nullable = true, neutralType = NeutralType.Binary),
                ),
                origin = SchemaOrigin.JDBC_METADATA,
            )
            val chunk = DataChunk(
                table = "plain",
                columns = listOf(ColumnDescriptor("id", nullable = false), ColumnDescriptor("geom", nullable = true)),
                rows = listOf(arrayOf<Any?>(1, byteArrayOf(1, 2, 3))),
                chunkIndex = 0,
            )
            SqliteDataWriter().openTable(pool, "plain", ImportOptions()).use { session ->
                session.write(chunk)
                session.commitChunk()
                session.finishTable()
            }
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT length(geom) FROM plain").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 3
                    }
                }
            }
            schema.table shouldBe "plain"
        }
    }
})
