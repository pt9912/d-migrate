package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.SchemaDefinition

/**
 * Welche Sichten ein Tabellen-Neubau mitnehmen muss.
 *
 * Der [SqliteSchemaReader] setzt an keiner Sicht `dependencies`; eine
 * handgeschriebene Datei setzt sie nur, wenn der Autor daran denkt. Die
 * Bezuege kommen deshalb aus dem Sichttext: eine Sicht gehoert zu einer
 * Tabelle, wenn ihr Text den Namen als Bezeichner traegt
 * ([SqliteIdentifierTokenScanner]; Literale und Kommentare zaehlen nicht)
 * oder `dependencies.tables` sie nennt. Eine Sicht ueber eine Sicht haengt
 * transitiv an der Tabelle.
 *
 * Eine Ueberschaetzung ist harmlos (die Sicht wird geloescht und neu
 * angelegt), eine Unterschaetzung laesst den Neubau am `RENAME` scheitern.
 */
internal object SqliteViewDependencies {

    /** Namen der Sichten in [schema], die direkt oder ueber andere Sichten an [table] haengen. */
    fun viewNamesOf(schema: SchemaDefinition?, table: String): Set<String> {
        if (schema == null) return emptySet()
        val reached = linkedSetOf<String>()
        val referenced = mutableSetOf(table)
        var grew = true
        while (grew) {
            grew = false
            for ((name, view) in schema.views) {
                if (name in reached) continue
                val declared = view.dependencies?.let { it.tables + it.views }.orEmpty()
                val hit = declared.any { it in referenced } ||
                    referenced.any { SqliteIdentifierTokenScanner.containsIdentifier(view.query.orEmpty(), it) }
                if (hit) {
                    reached += name
                    referenced += name
                    grew = true
                }
            }
        }
        return reached
    }

    /**
     * Fuer jede Tabelle in [tables] die Sichten, die in [current] **oder** [desired]
     * an ihr haengen: der Eimer eines Neubaus nimmt die Operationen beider Seiten auf.
     */
    fun viewsByTable(
        tables: Set<String>,
        current: SchemaDefinition?,
        desired: SchemaDefinition?,
    ): Map<String, Set<String>> =
        tables.associateWith { viewNamesOf(current, it) + viewNamesOf(desired, it) }
}
