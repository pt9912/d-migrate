package dev.dmigrate.core.diff.migration.overlay

import dev.dmigrate.core.model.SchemaDefinition

/**
 * Baut die Herkunft der vier rohen SQL-Textfelder aus einem geglueckten Lauf.
 *
 * Nach einem sauberen Post-Compare steht fest, dass das Ziel dem Soll
 * entspricht. Damit ist fuer jedes Textfeld ein Paar belegt: der Text, den der
 * **Autor** geschrieben hat, und die Form, die der **Server** daraufhin fuehrt.
 * Genau dieses Paar laesst sich spaeter nicht mehr herstellen — der Server
 * druckt aus seinem Parsebaum, ein `--`-Kommentar ist danach spurlos weg.
 *
 * Gebaut wird aus **beiden** Schemata desselben Laufs: dem Soll (Autorentext)
 * und dem unmittelbar danach zurueckgelesenen Ist (Katalogform). Ein Feld, das
 * nur eine Seite fuehrt, ergibt keinen Eintrag — die Aussage braucht beide.
 */
object RawTextProvenance {

    fun entriesOf(authored: SchemaDefinition, observed: SchemaDefinition): List<RawTextProvenanceOverlayEntry> =
        buildList {
            for ((name, authoredView) in authored.views) {
                val observedView = observed.views[name] ?: continue
                addEntry(
                    objectType = "view", path = listOf(name),
                    field = RawTextProvenanceFields.VIEW_QUERY,
                    authoredText = authoredView.query, observedText = observedView.query,
                )
            }
            for ((tableName, authoredTable) in authored.tables) {
                val observedTable = observed.tables[tableName] ?: continue
                val observedConstraints = observedTable.constraints.associateBy { it.name }
                for (constraint in authoredTable.constraints) {
                    val counterpart = observedConstraints[constraint.name] ?: continue
                    addEntry(
                        objectType = "constraint", path = listOf(tableName, constraint.name),
                        field = RawTextProvenanceFields.CHECK_EXPRESSION,
                        authoredText = constraint.expression, observedText = counterpart.expression,
                    )
                }
                val observedIndices = observedTable.indices.filter { it.name != null }.associateBy { it.name }
                for (index in authoredTable.indices) {
                    val counterpart = observedIndices[index.name ?: continue] ?: continue
                    addEntry(
                        objectType = "index", path = listOf(tableName, index.name!!),
                        field = RawTextProvenanceFields.INDEX_WHERE,
                        authoredText = index.where, observedText = counterpart.where,
                    )
                    // Ausdrucks-Schluessel ueber ihre Stellung: die Reihenfolge
                    // der Schluessel ist bedeutungstragend, der Ausdruck allein
                    // identifiziert keinen.
                    for ((position, key) in index.columns.withIndex()) {
                        val otherKey = counterpart.columns.getOrNull(position) ?: continue
                        addEntry(
                            objectType = "index", path = listOf(tableName, index.name!!),
                            field = RawTextProvenanceFields.INDEX_KEY_EXPRESSION,
                            keyPosition = position + 1,
                            authoredText = key.expression, observedText = otherKey.expression,
                        )
                    }
                }
            }
        }

    /**
     * Der Bezeichner eines Eintrags. Aus Objektart, Pfad, Feld und Stellung
     * gebildet und damit ueber Laeufe hinweg derselbe — ein zufaellig
     * vergebener machte jedes Dokument gegen das vorige unvergleichbar.
     */
    fun entryId(objectType: String, path: List<String>, field: String, keyPosition: Int? = null): String =
        listOfNotNull(objectType, path.joinToString("."), field, keyPosition?.toString()).joinToString(":")

    private fun MutableList<RawTextProvenanceOverlayEntry>.addEntry(
        objectType: String,
        path: List<String>,
        field: String,
        authoredText: String?,
        observedText: String?,
        keyPosition: Int? = null,
    ) {
        // Ein Feld, das keine der beiden Seiten fuehrt, ist keine Herkunft,
        // sondern Rauschen.
        if (authoredText == null && observedText == null) return
        add(
            RawTextProvenanceOverlayEntry(
                id = entryId(objectType, path, field, keyPosition),
                objectType = objectType,
                objectPath = path,
                field = field,
                keyPosition = keyPosition,
                appliedAuthorText = authoredText.orEmpty(),
                observedCatalogText = observedText.orEmpty(),
            ),
        )
    }
}
