package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ChunkSequence
import dev.dmigrate.driver.data.DataReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk

/**
 * Woher die SRID einer Geometriespalte kommt, wenn das Ziel sie nicht auf der
 * Spalte fuehren kann: aus dem Quellschema, und wo dieses schweigt, aus den
 * Werten der Quelle.
 *
 * Der Wert-Weg ist die einzige Quelle fuer SQL Server, das die SRID am Wert
 * fuehrt. Er kann deshalb auch etwas sehen, was ein Schema nicht ausdruecken
 * kann: eine Spalte mit mehreren Bezugssystemen.
 */
class GeometrySridResolverTest : FunSpec({

    fun schema(vararg columns: Pair<String, NeutralType>) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            "places" to TableDefinition(
                columns = columns.associate { (name, type) -> name to ColumnDefinition(type = type) },
            ),
        ),
    )

    val pool = mockk<ConnectionPool>()

    test("a column whose schema carries the SRID is not probed") {
        val reader = RecordingReader(emptyMap())
        val resolved = GeometrySridResolver.resolve(
            schema("geom" to NeutralType.Geometry(srid = 4326)), reader, pool, listOf("places"),
        )

        resolved shouldBe mapOf("places" to mapOf("geom" to 4326))
        // Die Angabe steht schon da — ein Durchlauf ueber die Werte waere
        // reine Last.
        reader.asked shouldBe emptyList()
    }

    test("a column whose schema is silent takes the SRID of its values") {
        val reader = RecordingReader(mapOf("places" to mapOf("geom" to listOf(25832))))
        val resolved = GeometrySridResolver.resolve(
            schema("geom" to NeutralType.Geometry()), reader, pool, listOf("places"),
        )

        resolved shouldBe mapOf("places" to mapOf("geom" to 25832))
        reader.asked shouldBe listOf("places" to listOf("geom"))
    }

    test("schema and values meet in one map, per table") {
        val reader = RecordingReader(mapOf("places" to mapOf("bare" to listOf(3857))))
        val resolved = GeometrySridResolver.resolve(
            schema("typed" to NeutralType.Geometry(srid = 4326), "bare" to NeutralType.Geometry()),
            reader, pool, listOf("places"),
        )

        resolved shouldBe mapOf("places" to mapOf("typed" to 4326, "bare" to 3857))
        reader.asked shouldBe listOf("places" to listOf("bare"))
    }

    test("a column with more than one reference system is refused, naming both") {
        val reader = RecordingReader(mapOf("places" to mapOf("geom" to listOf(4326, 25832))))

        val thrown = shouldThrow<TransferPreflightException> {
            GeometrySridResolver.resolve(
                schema("geom" to NeutralType.Geometry()), reader, pool, listOf("places"),
            )
        }
        // Jede Wahl waere fuer einen Teil der Werte falsch — und unsichtbar.
        thrown.message!! shouldContain "4326, 25832"
        thrown.message!! shouldContain "geom"
        thrown.message!! shouldContain "places"
    }

    test("a source that carries no SRID at all leaves the map empty") {
        val reader = RecordingReader(emptyMap())
        GeometrySridResolver.resolve(
            schema("geom" to NeutralType.Geometry()), reader, pool, listOf("places"),
        ) shouldBe emptyMap()
    }

    test("only geometry columns are probed, and only the tables being transferred") {
        val reader = RecordingReader(emptyMap())
        GeometrySridResolver.resolve(
            schema("geom" to NeutralType.Geometry(), "name" to NeutralType.Text()),
            reader, pool, listOf("places", "unknown"),
        )
        // `name` ist keine Geometrie, `unknown` steht nicht im Schema.
        reader.asked shouldBe listOf("places" to listOf("geom"))
    }
})

private class RecordingReader(private val answers: Map<String, Map<String, List<Int>>>) : DataReader {
    val asked = mutableListOf<Pair<String, List<String>>>()

    override val dialect: DatabaseDialect = DatabaseDialect.MSSQL

    override fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
    ): ChunkSequence = error("not used")

    override fun geometrySrids(
        pool: ConnectionPool,
        table: String,
        columns: List<String>,
    ): Map<String, List<Int>> {
        asked += table to columns
        return answers[table].orEmpty()
    }
}
