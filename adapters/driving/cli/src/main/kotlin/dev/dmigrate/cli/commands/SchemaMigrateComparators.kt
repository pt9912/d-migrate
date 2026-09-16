package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.RawTextAuthorship
import dev.dmigrate.core.diff.RawTextServerForm
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TargetProjection
import dev.dmigrate.core.model.SchemaDefinition

/**
 * Die Comparatoren, mit denen `schema migrate` plant und nachprueft.
 *
 * **Bewusst ohne** die Schreibweise-Faltung und ohne die
 * Erzeugungs-Projektion von `schema compare` (ADR 0056): hier kostet eine
 * uebersehene Aenderung eine falsch stehende Datenbank. Was der Migrate-Pfad
 * statt dessen faltet, sagen Zielprojektion, Herkunft und Sandkasten. Als
 * eigenes Objekt, damit ein Test genau die Comparatoren prueft, die der
 * Befehl benutzt (`SchemaMigrateComparatorsTest`).
 */
internal object SchemaMigrateComparators {

    /** Der strikte Vergleich — ohne Ziel, ohne Faltung. */
    val strict: (SchemaDefinition, SchemaDefinition) -> SchemaDiff = { left, right ->
        SchemaComparator().compare(left, right)
    }

    /** Der ziel-bewusste Vergleich — Zielprojektion, Herkunft und Sandkasten, sonst nichts. */
    val targetAware: (
        SchemaDefinition,
        SchemaDefinition,
        TargetProjection,
        RawTextAuthorship?,
        RawTextServerForm?,
    ) -> SchemaDiff = { left, right, projection, authorship, serverForm ->
        SchemaComparator(projection, authorship, serverForm).compare(left, right)
    }
}
