package dev.dmigrate.cli.commands.verify

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * LN-009 / ADR 0030: reihenfolge-unabhängige, multiset-korrekte Tabellen-Prüfsumme.
 */
class TableChecksumTest : FunSpec({

    fun row(vararg cols: String?) = cols.map { it?.toByteArray() }

    test("Zeilenreihenfolge ist irrelevant") {
        val a = TableChecksum().apply { addRow(row("a")); addRow(row("b")); addRow(row("c")) }
        val b = TableChecksum().apply { addRow(row("c")); addRow(row("a")); addRow(row("b")) }
        a.digestHex() shouldBe b.digestHex()
    }

    test("Duplikate löschen sich NICHT aus (Multiset, anders als XOR)") {
        val one = TableChecksum().apply { addRow(row("x")) }
        val two = TableChecksum().apply { addRow(row("x")); addRow(row("x")) }
        two.digestHex() shouldNotBe one.digestHex()
        two.digestHex() shouldNotBe "0".repeat(64)
    }

    test("NULL unterscheidet sich strukturell vom Leerstring") {
        val nullRow = TableChecksum().apply { addRow(listOf<ByteArray?>(null)) }
        val emptyRow = TableChecksum().apply { addRow(listOf(ByteArray(0))) }
        nullRow.digestHex() shouldNotBe emptyRow.digestHex()
    }

    test("unterschiedliche Daten → unterschiedlicher Digest") {
        val a = TableChecksum().apply { addRow(row("a", "b")) }
        val b = TableChecksum().apply { addRow(row("a", "c")) }
        a.digestHex() shouldNotBe b.digestHex()
    }

    test("Spaltengrenzen sind eindeutig (kein Feldgrenzen-Kollaps)") {
        val a = TableChecksum().apply { addRow(row("ab", "c")) }
        val b = TableChecksum().apply { addRow(row("a", "bc")) }
        a.digestHex() shouldNotBe b.digestHex()
    }

    test("rowCount zählt aufgenommene Zeilen; Digest ist 64 Hex-Zeichen") {
        val c = TableChecksum().apply { addRow(row("a")); addRow(row("b")) }
        c.rowCount() shouldBe 2L
        c.digestHex().length shouldBe 64
    }

    test("leere Tabelle → Digest 0") {
        TableChecksum().digestHex() shouldBe "0".repeat(64)
    }
})
