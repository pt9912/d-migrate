package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * P3 — die Degradierung ohne `search_path` nennt Grund und Ausweg (`R405`).
 *
 * Liegt PostGIS in einem eigenen Schema, das nicht im `search_path` steht,
 * loest `geometry_columns` nicht auf. Jede Geometriespalte kommt dann ohne
 * Subtyp und ohne SRID zurueck; gemeldet wurde nur `R401` (`INFO`, je
 * Spalte), und das nennt weder Ursache noch Ausweg. Der Grund ist an der
 * Abfrage bekannt und ging dort verloren.
 */
class PostgresGeometryColumnsReachTest : FunSpec({

    fun scan(reachable: Boolean) =
        PostgresTableMetadataQueries.GeometryColumnsScan(reachable = reachable, rows = emptyList())

    fun columns(vararg udt: Pair<String, String>): List<Map<String, Any?>> =
        udt.map { (name, type) -> mapOf("column_name" to name, "udt_name" to type) }

    fun notesFor(reachable: Boolean, vararg cols: Pair<String, String>): List<SchemaReadNote> {
        val notes = mutableListOf<SchemaReadNote>()
        notePostgisOutOfReach("places", columns(*cols), scan(reachable), notes)
        return notes
    }

    test("eine unerreichbare Sicht meldet R405 mit Grund und Ausweg") {
        val note = notesFor(false, "id" to "int4", "location" to "geometry").single()

        note.code shouldBe "R405"
        note.severity shouldBe SchemaReadSeverity.WARNING
        note.objectName shouldBe "places"
        note.message shouldContain "geometry_columns"
        note.message shouldContain "location"
        note.message shouldContain "without SRID"
        note.hint!! shouldContain "search_path"
    }

    // Gegenprobe 1: ist die Sicht erreichbar, geht nichts verloren.
    test("eine erreichbare Sicht meldet nichts") {
        notesFor(true, "location" to "geometry").shouldBeEmpty()
    }

    // Gegenprobe 2: ohne Geometriespalte geht auch bei unerreichbarer Sicht
    // nichts verloren — sonst meldete jede Tabelle jeder Datenbank ohne
    // PostGIS eine Warnung.
    test("eine Tabelle ohne Geometriespalte meldet nichts") {
        notesFor(false, "id" to "int4", "label" to "text").shouldBeEmpty()
    }

    test("mehrere Geometriespalten stehen in einer Note") {
        val note = notesFor(false, "a" to "geometry", "b" to "geometry").single()
        note.message shouldContain "a, b"
    }
})
