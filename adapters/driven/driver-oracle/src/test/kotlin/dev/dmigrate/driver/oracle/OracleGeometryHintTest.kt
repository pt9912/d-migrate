package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SpatialProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * P1 — `W120` sagt je Fall das Richtige.
 *
 * Oracle fuehrt eine Metadatenzeile bedingungslos grossgeschrieben. Der
 * Hinweis empfahl fuer **jede** Tabelle, die Zeile von Hand einzufuegen —
 * auch fuer eine quotiert kleingeschriebene, fuer die genau das
 * ausgeschlossen ist: Oracle schriebe den Namen gross, und die Zeile
 * beschriebe eine andere Tabelle.
 */
class OracleGeometryHintTest : FunSpec({

    val generator = OracleDdlGenerator()

    // Ohne `NATIVE` rendert Oracle die Geometriespalte gar nicht, sondern
    // meldet sie als `action_required` — dann gaebe es auch kein `W120`.
    fun notesOf(schema: SchemaDefinition) =
        generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE)).notes

    fun schemaWith(table: String, column: String) = SchemaDefinition(
        name = "s", version = "1",
        tables = mapOf(
            table to TableDefinition(
                columns = mapOf(
                    column to ColumnDefinition(
                        NeutralType.Geometry(GeometryType.of("point"), srid = 4326),
                        ordinal = 1,
                    ),
                ),
            ),
        ),
    )

    test("quotiert kleingeschrieben: der Ausweg ist der Name in der Schemadatei, nicht die Zeile") {
        val note = notesOf(schemaWith("places", "geom")).single { it.code == "W120" }

        note.objectName shouldBe "places.geom"
        note.message shouldContain "upper-cases"
        note.hint!! shouldContain "upper-case table and column name in the schema file"
        note.hint!! shouldContain "Do not insert the row by hand"
    }

    test("grossgeschrieben: die Zeile von Hand ist der richtige Ausweg") {
        val note = notesOf(schemaWith("PLACES", "GEOM")).single { it.code == "W120" }

        note.objectName shouldBe "PLACES.GEOM"
        note.hint!! shouldContain "Insert the USER_SDO_GEOM_METADATA row manually"
        note.hint!! shouldNotContain "Do not insert"
        note.message shouldNotContain "upper-cases"
    }

    // Gegenprobe: eine gemischtgeschriebene Spalte an einer
    // grossgeschriebenen Tabelle traegt dieselbe Grenze — auch den
    // Spaltennamen schreibt Oracle in der Zeile gross.
    test("gemischtgeschriebene Spalte: kein Rat zur Zeile von Hand") {
        val note = notesOf(schemaWith("PLACES", "Geom")).single { it.code == "W120" }
        note.hint!! shouldContain "Do not insert the row by hand"
    }
})
