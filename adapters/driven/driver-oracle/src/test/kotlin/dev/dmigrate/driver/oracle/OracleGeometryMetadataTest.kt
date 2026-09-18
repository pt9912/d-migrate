package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * P1 — der verlorene SRID einer Oracle-Geometriespalte wird gemeldet.
 *
 * Zwei Faelle, die sich ausschliessen: die Metadatensicht ist lesbar und eine
 * Zeile fehlt (`R370`, mit zwei Texten), oder sie ist gar nicht lesbar
 * (`R365`). Beide `WARNING`, beide ohne Block.
 */
class OracleGeometryMetadataTest : FunSpec({

    fun readable(vararg rows: Pair<String, Int?>) = OracleGeometryMetadata.Scan.Readable(
        rows.associate { (col, srid) ->
            col to OracleMetadataQueries.GeometryMetadataRow(column = col, srid = srid)
        },
    )

    fun notesFor(
        table: String,
        columns: List<String>,
        scan: OracleGeometryMetadata.Scan,
    ): List<SchemaReadNote> {
        val notes = mutableListOf<SchemaReadNote>()
        OracleGeometryMetadata.noteMissingSrids(table, columns, scan, notes)
        return notes
    }

    test("quotiert kleingeschriebene Tabelle: genau ein R370, und die Zeile von Hand ist NICHT der Ausweg") {
        val note = notesFor("places", listOf("geom"), readable()).single()

        note.code shouldBe "R370"
        note.severity shouldBe SchemaReadSeverity.WARNING
        note.objectName shouldBe "places.geom"
        note.message shouldContain "upper-cases"
        note.hint!! shouldContain "Declare the SRID"
        note.hint!! shouldContain "Do not insert the row by hand"
    }

    test("grossgeschriebene Tabelle ohne Zeile: R370 mit dem zweiten Text") {
        val note = notesFor("PLACES", listOf("GEOM"), readable()).single()

        note.code shouldBe "R370"
        note.objectName shouldBe "PLACES.GEOM"
        note.message shouldContain "no row for it"
        note.hint!! shouldContain "Register the row in USER_SDO_GEOM_METADATA"
        // Der Ausweg, den ADR 0058 fuer die andere Lage ausschliesst, steht
        // hier zu Recht — und nur hier.
        note.hint!! shouldNotContain "Do not insert"
    }

    // Gegenprobe 1: eine registrierte Zeile liest den SRID, und es entsteht
    // nichts.
    test("grossgeschriebene Tabelle mit registrierter Zeile meldet nichts") {
        val scan = readable("GEOM" to 4326)
        notesFor("PLACES", listOf("GEOM"), scan).shouldBeEmpty()
        OracleGeometryMetadata.sridOf(scan, "GEOM") shouldBe 4326
    }

    // Gegenprobe 2: im `R365`-Fall entsteht kein zusaetzliches `R370` — ueber
    // die Zeilen einer unlesbaren Sicht laesst sich nichts sagen.
    test("unlesbare Sicht: kein R370 daneben") {
        notesFor("places", listOf("geom"), OracleGeometryMetadata.Scan.Unreadable).shouldBeEmpty()
    }

    // Gegenprobe 3: eine Tabelle ohne Geometriespalte meldet nichts — dort
    // geht kein SRID verloren, und `R365` stand dort frueher trotzdem.
    test("eine Tabelle ohne Geometriespalte meldet nichts") {
        notesFor("PLAIN", emptyList(), readable()).shouldBeEmpty()
        notesFor("PLAIN", emptyList(), OracleGeometryMetadata.Scan.Unreadable).shouldBeEmpty()
    }

    // Eine gemischtgeschriebene Spalte an einer grossgeschriebenen Tabelle
    // faellt ebenso unter den ersten Text: auch den Spaltennamen schreibt
    // Oracle in der Metadatenzeile gross.
    test("gemischtgeschriebene Spalte: der erste Text") {
        notesFor("PLACES", listOf("Geom"), readable()).single()
            .message shouldContain "upper-cases"
    }

    test("nur die Spalte ohne Zeile wird gemeldet") {
        val notes = notesFor("PLACES", listOf("GEOM_A", "GEOM_B"), readable("GEOM_A" to 4326))
        notes.single().objectName shouldBe "PLACES.GEOM_B"
    }
})
