package dev.dmigrate.format.yaml

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.mssql.MssqlDdlGenerator
import dev.dmigrate.driver.mssql.MssqlDiffDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDiffDdlGenerator
import dev.dmigrate.driver.oracle.OracleDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDiffDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDiffDdlGenerator
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Der Bitmap-Index (Oracle) ist der einzige Indextyp, der auf JEDEM Ziel
 * darstellbar bleibt: er indiziert gewoehnliche Spalten, nur die Ablageform
 * ist Oracle-eigen. Er darf deshalb nirgends verworfen werden -- anders als
 * GIN/GiST/BRIN, die SQLite bewusst weglaesst.
 *
 * Der Test steht hier statt je Dialekt, weil genau die Gleichheit ueber alle
 * Dialekte die Zusicherung ist; vier getrennte Tests koennten einzeln gruen
 * bleiben, waehrend ein neu hinzukommender Dialekt die Regel bricht.
 */
class BitmapIndexCrossDialectTest : FunSpec({

    val generators: Map<String, DdlGenerator> = mapOf(
        "postgresql" to PostgresDdlGenerator(),
        "mysql" to MysqlDdlGenerator(),
        "sqlite" to SqliteDdlGenerator(),
        "mssql" to MssqlDdlGenerator(),
    )

    val schema = SchemaDefinition(
        name = "bitmap-schema",
        version = "1.0.0",
        tables = mapOf(
            "facts" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                    "status" to ColumnDefinition(type = NeutralType.Text(maxLength = 10)),
                ),
                primaryKey = listOf("id"),
                indices = listOf(
                    IndexDefinition(
                        name = "bm_status",
                        columns = listOf(IndexColumn("status")),
                        type = IndexType.BITMAP,
                    ),
                ),
            ),
        ),
    )

    generators.forEach { (dialect, generator) ->
        test("$dialect renders a bitmap index as an ordinary index and says so") {
            val result = generator.generate(schema)

            val ddl = result.render()
            // Der Index bleibt erhalten -- der springende Punkt gegenueber
            // SQLites pauschaler Nicht-BTREE-Regel, die ihn frueher schluckte.
            // Auf die Zeile geprueft, nicht auf das Skript: das Wort BITMAP
            // steht legitim im Hinweistext, der als Kommentar mitgerendert wird.
            val create = ddl.lines().single { it.uppercase().startsWith("CREATE INDEX") }
            create shouldContain "bm_status"
            // Auf der Zeile, nicht im Skript: weder `CREATE BITMAP INDEX`
            // noch ein `USING BITMAP` -- Letzteres waere in PostgreSQL
            // syntaktisch wohlgeformt und trotzdem ungueltig (die
            // Zugriffsmethode existiert nicht).
            create.uppercase() shouldNotContain "BITMAP"

            val note = result.notes.single { it.code == "W102" && it.objectName == "bm_status" }
            note.message shouldContain "bitmap access method"
        }
    }

    test("oracle renders it natively, without a fallback note") {
        val result = OracleDdlGenerator().generate(schema)
        val create = result.render().lines().single { it.uppercase().startsWith("CREATE BITMAP INDEX") }
        create shouldContain "bm_status"
        result.notes.none { it.code == "W102" && it.objectName == "bm_status" } shouldBe true
    }

    // Was `schema generate` meldet, muss `schema migrate` auch melden. Ohne
    // diese Parität verliert der eine Pfad still, wovor der andere warnt --
    // und genau so war es fuer MySQL und SQLite, bis der Review es fand.
    val diffGenerators: Map<String, DiffDdlGenerator> = mapOf(
        "postgresql" to PostgresDiffDdlGenerator(),
        "mysql" to MysqlDiffDdlGenerator(),
        "sqlite" to SqliteDiffDdlGenerator(),
        "mssql" to MssqlDiffDdlGenerator(),
    )

    diffGenerators.forEach { (dialect, diffGenerator) ->
        test("$dialect reports the bitmap fallback in the migrate path too") {
            val index = schema.tables.getValue("facts").indices.single()
            val current = schema.copy(
                tables = mapOf("facts" to schema.tables.getValue("facts").copy(indices = emptyList())),
            )
            val plan = DiffPlanner().plan(
                current, schema,
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "facts", indicesAdded = listOf(index)))),
            )
            val diagnostics = diffGenerator.generateUp(plan, DdlGenerationOptions()).diagnostics
            withClue("$dialect diagnostics: ${diagnostics.map { it.code }}") {
                diagnostics.any { it.code == "W102" } shouldBe true
            }
        }
    }
})
