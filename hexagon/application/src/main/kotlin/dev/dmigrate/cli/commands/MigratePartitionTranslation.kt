package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidationContext
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayValidator
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect

/**
 * Dieselbe LIST-nach-RANGE-Uebersetzung wie im Generate-Pfad, nur frueher:
 * **vor** dem Vergleich.
 *
 * Ohne sie ist ein mit Overlay erzeugtes Schema nicht wieder migrierbar. Das
 * Soll sagt LIST, der Reverse des Ziels sagt RANGE, und der Vergleich meldet
 * bei jedem Lauf dieselbe Strategieaenderung — eine Migration, die gesund
 * aussieht und nie fertig wird.
 *
 * **Zwei Abdruecke, und beide gehoeren dazu.** Das Overlay bindet an das
 * Schema, wie es geschrieben wurde (ADR 0050) — sonst haenge die Bindung an
 * ihrer eigenen Wirkung. Der Plan haelt dagegen fest, was danach auf dem Ziel
 * steht, also den Abdruck des uebersetzten Schemas. Der Aufrufer rechnet
 * deshalb neu, wenn hier etwas geschehen ist.
 *
 * **Eine untaugliche Zuordnung geht denselben Weg wie ein unlesbares
 * Overlay** — als Befund in den Preflight, nicht ueber einen eigenen Ausgang.
 * Zwei Arten, dasselbe zu melden, waeren eine zu viel.
 */
internal object MigratePartitionTranslation {

    data class Result(
        val schema: SchemaDefinition,
        val translatedTables: List<String>,
        val failures: List<MigrationOverlayLoadFailure>,
    ) {
        val changed: Boolean get() = translatedTables.isNotEmpty()
    }

    fun translate(
        schema: SchemaDefinition,
        dialect: DatabaseDialect,
        documents: List<MigrationOverlayDocument>,
        representationFingerprint: String,
    ): Result {
        val usable = documents.filter { accepts(it, dialect, representationFingerprint) }
        if (usable.isEmpty()) return Result(schema, emptyList(), emptyList())

        return when (val applied = PartitionListRangeApplier.apply(schema, dialect, usable)) {
            is PartitionListRangeApplier.Result.Applied ->
                Result(applied.schema, applied.tables, emptyList())

            is PartitionListRangeApplier.Result.Refused -> Result(
                schema = schema,
                translatedTables = emptyList(),
                failures = usable.map { document ->
                    MigrationOverlayLoadFailure(
                        source = document.source,
                        diagnosticCode = MigrationOverlayDiagnostics.PARTITION_MAPPING_INVALID,
                        message = "Partition mapping for table '${applied.table}' cannot be expressed as " +
                            "RANGE bounds: ${applied.reason}",
                    )
                },
            )
        }
    }

    /**
     * Ob dieses Dokument ueberhaupt etwas beitragen darf. Ein Overlay, das der
     * Validator ablehnt, wird hier **still** uebergangen — der Preflight
     * meldet es gleich darauf, und zweimal dieselbe Ablehnung zu drucken sagt
     * nichts Zweites.
     */
    private fun accepts(
        document: MigrationOverlayDocument,
        dialect: DatabaseDialect,
        representationFingerprint: String,
    ): Boolean = !MigrationOverlayValidator.validate(
        overlay = document.overlay,
        context = MigrationOverlayValidationContext(
            expectedSourceFingerprint = UNCHECKED,
            expectedTargetFingerprint = UNCHECKED,
            expectedDialect = dialect.name.lowercase(),
            expectedRepresentationFingerprint = representationFingerprint,
        ),
        source = document.source,
    ).hasBlockers

    /**
     * Fuer die Uebergangs-Bindung ist hier nichts zu pruefen: ein
     * Uebergangs-Overlay traegt keine Partitionszuordnung, und der Preflight
     * prueft es ohnehin gegen die echten Abdruecke.
     */
    private const val UNCHECKED = "<checked by the preflight>"
}
