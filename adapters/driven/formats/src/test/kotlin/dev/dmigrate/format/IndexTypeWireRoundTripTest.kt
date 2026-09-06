package dev.dmigrate.format

import dev.dmigrate.core.model.IndexType
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
