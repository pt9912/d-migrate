package dev.dmigrate.format

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.format.yaml.YamlSchemaCodec
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Der Schreiber setzt den Indextyp als `type.name.lowercase()` ins YAML
 * (`SchemaNodeStructureBuilders`), der Leser holt ihn ueber `toIndexType()`
 * zurueck. Beide Listen wurden bisher von Hand gepflegt — ein neuer
 * Enum-Wert konnte also geschrieben, aber nicht mehr gelesen werden, und
 * zwar still: der Fehler faellt erst beim erneuten Einlesen eines
 * Reverse-Ergebnisses auf, nicht beim Erzeugen.
 *
 * Dieser Test schliesst die Luecke erschoepfend, statt fuer einen einzelnen
 * Typ: er laeuft ueber [IndexType.entries] und faellt daher automatisch bei
 * jedem kuenftigen Zuwachs.
 */
class IndexTypeWireRoundTripTest : FunSpec({

    test("every index type survives the YAML wire form") {
        IndexType.entries.forEach { type ->
            type.name.lowercase().toIndexType() shouldBe type
        }
    }
})

/**
 * Slice 6b: derselbe Grund wie oben, andere Achse. Der Schreiber setzt einen
 * Ausdrucks-Schluessel als Objekt mit `expression` ins YAML, der Leser holt
 * ihn zurueck. Ein Ausdruck, der als blosser Text geschrieben wuerde, kaeme
 * als **Spaltenname** zurueck — und der Generate-Pfad legte einen Index auf
 * eine Spalte, die es nicht gibt.
 */
class IndexColumnWireRoundTripTest : FunSpec({

    test("an expression key survives the YAML wire form as an expression") {
        val original = SchemaDefinition(
            name = "s", version = "1.0.0",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = mapOf("nm" to ColumnDefinition(NeutralType.Text(maxLength = 50))),
                    indices = listOf(
                        IndexDefinition(
                            name = "ix",
                            columns = listOf(
                                IndexColumn.expression("UPPER(nm)", IndexSortDirection.DESC),
                                IndexColumn("nm"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val codec = YamlSchemaCodec()
        val bytes = java.io.ByteArrayOutputStream().also { codec.write(it, original) }.toByteArray()
        val roundTripped = codec.read(java.io.ByteArrayInputStream(bytes))
        val keys = roundTripped.tables.getValue("t").indices.single().columns
        keys[0].expression shouldBe "UPPER(nm)"
        keys[0].direction shouldBe IndexSortDirection.DESC
        keys[1].expression shouldBe null
        keys[1].name shouldBe "nm"
        roundTripped.tables.getValue("t").indices.single().columnNames shouldBe listOf("nm")
    }
})
