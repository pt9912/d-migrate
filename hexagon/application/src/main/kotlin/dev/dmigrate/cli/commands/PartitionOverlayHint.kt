package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadNote

/**
 * Sagt einer Partitions-Meldung, **woran** ein Overlay zu binden waere.
 *
 * Ohne das ist `R346` („SQL Server nummeriert Partitionen") und `E055`
 * („LIST wird nicht gerendert") eine Feststellung ohne Ausweg: kein Befehl
 * gibt den Fingerabdruck eines Schemas aus, und ohne ihn laesst sich kein
 * `partition-mapping`-Overlay schreiben, das der Validator annimmt.
 *
 * **Eine Stelle berechnet ihn.** [representationFingerprint] speist die
 * Meldung *und* die Erwartung des Validators. Zwei Rechnungen waeren zwei
 * Gelegenheiten auseinanderzulaufen, und der Anwender saehe den Unterschied
 * erst an einer Ablehnung, die er nicht erklaeren kann.
 *
 * **Die Bindung ist stabil gegenueber ihrer eigenen Wirkung.** ADR 0050
 * verlangt, dass ein Reverse-Overlay an den Zustand *vor* seiner Anwendung
 * bindet, weil Kindnamen im Abdruck stehen. Fuer die Dialekte, um die es hier
 * geht, loest sich das von selbst: wo der Server Partitionen nummeriert,
 * blendet [capabilityPartitionCanonicalizer] den Namen aus dem Abdruck aus.
 * Das Overlay aendert also genau die Angabe, die die Projektion ohnehin nicht
 * sieht — vorher und nachher derselbe Wert.
 */
object PartitionOverlayHint {

    /**
     * Der Abdruck, an den ein Darstellungs-Overlay fuer [schema] zu binden ist.
     *
     * Dieselben Projektionen wie ueberall sonst; ein Overlay bindet an das
     * Schema, wie der Ziel-Dialekt es sieht, nicht an seinen Rohtext.
     */
    fun representationFingerprint(schema: SchemaDefinition, dialect: DatabaseDialect): String =
        MigrationFingerprint.compute(
            schema,
            canonicalizeType = registrySchemaAwareCanonicalizer(dialect, schema),
            canonicalizeIndex = capabilityIndexCanonicalizer(dialect),
            canonicalizeGeneration = capabilityGenerationCanonicalizer(dialect),
            canonicalizePartitioning = capabilityPartitionCanonicalizer(dialect),
            foldsAutoIncrementOntoIdentity = capabilityFoldsAutoIncrementOntoIdentity(dialect),
        )

    /**
     * Der Satz, den eine Partitions-Meldung anhaengt. Die Form ist bewusst
     * maschinell lesbar — `schemaFingerprint \`…\`` —, damit ein Anwender (oder
     * ein Werkzeug) den Wert herausnehmen kann, ohne ihn abzutippen.
     */
    fun hint(schema: SchemaDefinition, dialect: DatabaseDialect, entryForm: String): String =
        "To supply the missing partition identity, write a `${MigrationOverlayKinds.PARTITION_MAPPING}` " +
            "overlay bound to schemaFingerprint `${representationFingerprint(schema, dialect)}` " +
            "with one entry per partition ($entryForm)."

    /** Die Eintragsform des Kindnamen-Falls. */
    const val NAME_ENTRY_FORM: String = "`table`, `sourcePartition`, `targetPartition`"

    /**
     * Die Meldung, die ohne den Abdruck keinen Ausweg nennt.
     *
     * `E055` (LIST wird nicht gerendert) gehoert der Sache nach dazu, bekommt
     * den Hinweis aber erst, wenn der Generate-Pfad das Overlay auch liest —
     * ein Hinweis auf eine Datei, die kein Befehl entgegennimmt, waere
     * schlechter als keiner.
     */
    private val PARTITION_CODES = setOf("R346")

    /**
     * Haengt den Hinweis an die Partitions-Meldungen eines Reverse-Laufs.
     *
     * Nachtraeglich statt im Leser: der Leser baut Tabelle fuer Tabelle, das
     * Schema ist dort noch nicht fertig — und ohne fertiges Schema gibt es
     * keinen Abdruck.
     */
    fun enrichReadNotes(
        notes: List<SchemaReadNote>,
        schema: SchemaDefinition,
        dialect: DatabaseDialect,
    ): List<SchemaReadNote> {
        if (notes.none { it.code in PARTITION_CODES }) return notes
        val hint = hint(schema, dialect, NAME_ENTRY_FORM)
        return notes.map { note ->
            if (note.code in PARTITION_CODES) note.copy(hint = listOfNotNull(note.hint, hint).joinToString(" ")) else note
        }
    }
}
