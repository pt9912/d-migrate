package dev.dmigrate.driver.mssql

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Die Zeichenketten stammen aus einer Messung gegen SQL Server 2022: die
 * Emulation von Hand angelegt und `sys.computed_columns.definition` gelesen.
 * Der Server schreibt den erzeugten Ausdruck um, und genau diese
 * umgeschriebene Form muss der Reverse wiedererkennen.
 */
class MssqlHashPartitionRecognitionTest : FunSpec({

    test("the form the server actually stores is recognised") {
        val recognized = MssqlHashPartitionRecognition.recognize(
            "(abs(checksum([customer_id])%(4)))",
            boundaries = listOf("1", "2", "3"),
        )
        recognized.shouldBe(MssqlHashPartitionRecognition.Recognized(listOf("customer_id"), 4))
    }

    test("a multi-column key keeps its order") {
        MssqlHashPartitionRecognition.recognize(
            "(abs(checksum([a],[b])%(2)))",
            boundaries = listOf("1"),
        ).shouldBe(MssqlHashPartitionRecognition.Recognized(listOf("a", "b"), 2))
    }

    test("the boundaries are an independent cross-check") {
        // Ausdruck und RANGE-Funktion muessen dieselbe Eimerzahl sagen. Tun
        // sie es nicht, hat jemand an einem der beiden Teile gedreht.
        MssqlHashPartitionRecognition.recognize(
            "(abs(checksum([a])%(4)))",
            boundaries = listOf("1", "2"),
        ).shouldBeNull()
    }

    test("an identifier may carry spaces, commas and escaped brackets") {
        MssqlHashPartitionRecognition.recognize(
            "(abs(checksum([my col],[a,b],[od]]d])%(2)))",
            boundaries = listOf("1"),
        ).shouldBe(
            MssqlHashPartitionRecognition.Recognized(listOf("my col", "a,b", "od]d"), 2),
        )
    }

    test("the identifier keeps its case — only the function names are folded") {
        MssqlHashPartitionRecognition.recognize(
            "(abs(checksum([CustomerId])%(2)))",
            boundaries = listOf("1"),
        )!!.key shouldBe listOf("CustomerId")
    }

    test("anything that is not this expression falls back to RANGE") {
        // Erkennen oder zurueckfallen, nie raten: eine fremde berechnete
        // Spalte darf nicht als Emulation dieses Werkzeugs gelten.
        val foreign = listOf(
            "(abs(checksum(*)%(4)))",
            "(abs(checksum([a])%(1)))",
            "(abs(checksum([a]))%(4))",
            "([a]+[b])",
            "(abs(checksum([a] [b])%(2)))",
            "(abs(checksum([a])%(4)))extra",
            null,
        )
        for (definition in foreign) {
            withClue(definition ?: "null") {
                MssqlHashPartitionRecognition.recognize(definition, listOf("1", "2", "3")).shouldBeNull()
            }
        }
    }
})
