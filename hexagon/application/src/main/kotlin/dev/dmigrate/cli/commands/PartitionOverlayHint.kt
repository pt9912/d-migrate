package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.TransformationNote

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

    /** Die Eintragsform des LIST-Falls. */
    const val LIST_ENTRY_FORM: String = "`table`, `sourcePartition`, `values`, `rangeUpperBound`"

    /** Die Meldung des Lesepfads, die ohne den Abdruck keinen Ausweg nennt. */
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

    /**
     * Der Hinweis fuer eine LIST-Partitionierung, die der Zieldialekt nicht
     * ausdruecken kann — eine Meldung fuer den ganzen Lauf, nicht je Tabelle:
     * der Abdruck ist derselbe, und das Overlay, das zu schreiben waere, ist
     * eine Datei, nicht mehrere.
     *
     * `null`, wenn der Dialekt LIST kennt oder das Schema keine LIST-Tabelle
     * traegt — dann gaebe es nichts beizusteuern.
     */
    fun listMappingHint(schema: SchemaDefinition, dialect: DatabaseDialect): TransformationNote? {
        if (DialectCapabilities.forDialect(dialect).supportsListPartitioning) return null
        val tables = schema.tables
            .filter { (_, table) -> table.partitioning?.type == PartitionType.LIST }
            .keys
            .sorted()
        if (tables.isEmpty()) return null
        return TransformationNote(
            type = NoteType.INFO,
            code = LIST_MAPPING_AVAILABLE,
            objectName = tables.joinToString(", "),
            message = "The LIST partitioning of ${tables.joinToString { "'$it'" }} has no form in this " +
                "dialect; the table is created unpartitioned (E055). A LIST partitioning is expressible " +
                "as RANGE when the value sets are contiguous and non-overlapping in some order — which " +
                "set belongs to which boundary is yours to state, not the tool's to guess.",
            hint = hint(schema, dialect, LIST_ENTRY_FORM),
        )
    }

    /** Die Meldung, dass eine Zuordnung vorlag und angewandt wurde. */
    fun listTranslationNote(table: String, tailPartition: String): TransformationNote = TransformationNote(
        type = NoteType.WARNING,
        code = LIST_RENDERED_AS_RANGE,
        objectName = table,
        message = "The LIST partitioning of '$table' was rendered as RANGE from a partition-mapping " +
            "overlay. RANGE accepts what LIST refused: values below the first boundary land in the " +
            "first partition, and everything above the last one in '$tailPartition', which LIST did " +
            "not have.",
        hint = "Functionally equivalent for the declared value sets; add a CHECK constraint if the " +
            "table must keep rejecting the values LIST rejected.",
    )

    /** LIST als RANGE gerendert, weil ein Overlay die Zuordnung trug. */
    const val LIST_RENDERED_AS_RANGE: String = "W156"

    /** LIST nicht ausdrueckbar, aber ueber ein Overlay aufloesbar. */
    const val LIST_MAPPING_AVAILABLE: String = "W157"
}
