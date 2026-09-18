package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException

/**
 * P7 — eine `required`-Geometriespalte entsteht auf SpatiaLite **nativ**, an
 * einer echten Datei mit geladener Extension.
 *
 * Bis hierher fiel dafuer die ganze Tabelle mit `E052` weg. Nur ein echter
 * Server zeigt, dass das sechste Argument von `AddGeometryColumn` wirklich
 * traegt: die Spalte entsteht als `NOT NULL`, eine Zeile **ohne** Geometrie
 * wird abgewiesen, eine mit angenommen — und der Reverse liefert
 * `required: true` **ohne** den Default, den SpatiaLite selbst setzt
 * (`DEFAULT ''`). Ohne diese Ausnahme blockte der zweite `schema generate`
 * genau die Tabelle, die der erste angelegt hat.
 *
 * Die Standalone-DDL traegt den Metadaten-Bootstrap nicht (ADR 0016 hat ihn
 * im Generate-Pfad aufgeschoben); der Test setzt ihn deshalb selbst vorweg.
 */
class SqliteSpatialiteNotNullIntegrationTest : FunSpec({

    lateinit var dir: Path

    fun configFor(file: Path) = ConnectionConfig(
        dialect = DatabaseDialect.SQLITE,
        host = null,
        port = null,
        database = file.toString(),
        user = null,
        password = null,
        params = mapOf("spatialite" to "true"),
    )

    fun schema() = SchemaDefinition(
        name = "Geo",
        version = "1",
        tables = mapOf(
            "places" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true), ordinal = 1),
                    "label" to ColumnDefinition(NeutralType.Text(), required = true, ordinal = 2),
                    "loc" to ColumnDefinition(
                        type = NeutralType.Geometry(GeometryType("point"), srid = 4326),
                        required = true,
                        ordinal = 3,
                    ),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun generateDdl(schema: SchemaDefinition): String =
        SqliteDdlGenerator()
            .generate(schema, DdlGenerationOptions(spatialProfile = SpatialProfile.SPATIALITE))
            .render()

    beforeSpec { dir = Files.createTempDirectory("spatialite-notnull") }
    afterSpec { dir.toFile().deleteRecursively() }

    fun <T> onDatabase(file: Path, body: (ConnectionPool) -> T): T =
        HikariConnectionPoolFactory.create(configFor(file)).use(body)

    test("P7 live: eine required-Geometriespalte entsteht als NOT NULL und der Reverse erfindet keinen Default") {
        val file = dir.resolve("places.db")
        val ddl = generateDdl(schema())
        withClue(ddl) { ddl shouldContain "AddGeometryColumn('places', 'loc', 4326, 'POINT', 'XY', 1)" }

        onDatabase(file) { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    // ADR 0016: der Bootstrap steht (noch) nicht in der
                    // Standalone-DDL; ohne ihn hat eine frische Datei keine
                    // SpatiaLite-Metatabellen.
                    stmt.execute("SELECT InitSpatialMetaData(1)")
                    // Kommentarzeilen zuerst weg, dann trennen: ein Block, der
                    // mit `--` beginnt, traegt sonst seine Anweisung mit sich
                    // und faellt als Ganzes heraus.
                    val statements = ddl.lines()
                        .filterNot { it.trimStart().startsWith("--") }
                        .joinToString("\n")
                        .split(";")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    for (sql in statements) stmt.execute(sql)
                }
            }
        }

        // Der Server sagt, ob die Spalte wirklich NOT NULL ist.
        onDatabase(file) { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA table_info(places)").use { rs ->
                        var seen = false
                        while (rs.next()) {
                            if (rs.getString("name") == "loc") {
                                seen = true
                                rs.getInt("notnull") shouldBe 1
                            }
                        }
                        seen shouldBe true
                    }
                    // Eine Zeile ohne Geometrie wird abgewiesen …
                    val rejected = runCatching {
                        stmt.execute("INSERT INTO places (label) VALUES ('ohne')")
                    }.exceptionOrNull()
                    withClue("die Zeile ohne Geometrie ging durch") { (rejected is SQLException) shouldBe true }
                    // … eine mit angenommen.
                    stmt.execute(
                        "INSERT INTO places (label, loc) VALUES ('mit', GeomFromText('POINT(9.5 51.3)', 4326))",
                    )
                }
            }
        }

        // Der Reverse: `required` ja, SpatiaLites Fuellwert nein.
        val reversed = onDatabase(file) { pool -> SqliteSchemaReader().read(pool).schema }
        val column = reversed.tables.getValue("places").columns.getValue("loc")
        withClue("gelesen wurde $column") {
            column.required shouldBe true
            column.default.shouldBeNull()
            (column.type as NeutralType.Geometry).srid shouldBe 4326
        }

        // Und ein zweiter Generate aus dem Reverse blockt die Tabelle nicht
        // mehr — genau der Rueckweg, den der erfundene Default brach.
        val second = SqliteDdlGenerator().generate(
            reversed,
            DdlGenerationOptions(spatialProfile = SpatialProfile.SPATIALITE),
        )
        withClue(second.notes.map { it.code to it.message }.toString()) {
            second.notes.none { it.code == "E052" } shouldBe true
        }
        second.render() shouldContain "AddGeometryColumn('places', 'loc', 4326, 'POINT', 'XY', 1)"
    }
})
