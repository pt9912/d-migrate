package dev.dmigrate.core.diff.migration.overlay

/**
 * Woran ein Overlay-Dokument gebunden ist.
 *
 * Nicht jede Overlay-Art sagt dasselbe ueber die Welt. `using-expression`
 * („Spalte x wird von Typ A zu Typ B mit diesem Ausdruck konvertiert") und
 * `rename-mapping` („das Objekt, das `kunde` hiess, heisst jetzt `customer`")
 * sind Aussagen ueber einen **Uebergang** — ohne beide Zustaende sinnlos. Eine
 * Aussage ueber die **Darstellung** eines Sachverhalts in einem Dialekt
 * braucht dagegen nur ein Schema; der IST-Zustand einer laufenden Datenbank
 * ist dafuer belanglos.
 *
 * Beide Faelle in `sourceFingerprint`/`targetFingerprint` zu pressen ist nicht
 * bloss unschoen, sondern falsch: der Validator prueft `sourceFingerprint`
 * gegen den IST-Zustand, ein Darstellungs-Dokument mit dem SOLL-Abdruck in
 * beiden Feldern kaeme also nur durch, wenn IST und SOLL gleich waeren — genau
 * dann, wenn es nichts zu migrieren gibt.
 *
 * Sealed statt eines nullablen Feldes, dessen Bedeutung an einem
 * Geschwisterfeld haengt: dieselbe Bauart wurde im Projekt schon einmal
 * aufgeloest, als die nullablen `mysql*`/`sqlite*`-Felder generischer Ports
 * dem sealed `DdlDialectContext` wichen.
 */
sealed interface MigrationOverlayBinding {

    /** Eine Aussage ueber zwei Zustaende — `using-expression`, `rename-mapping`. */
    data class Transition(
        val sourceFingerprint: String,
        val targetFingerprint: String,
    ) : MigrationOverlayBinding

    /** Eine Aussage ueber die Darstellung **eines** Schemas. */
    data class Representation(val schemaFingerprint: String) : MigrationOverlayBinding
}

/**
 * Versioned migration overlay contract.
 *
 * Overlay files are external operator input for risky migration decisions
 * such as USING expressions or rename mappings. The core contract keeps the
 * document signed by schema fingerprints and a canonical JSON hash before any
 * renderer or planner may consume the entries.
 */
data class MigrationOverlay(
    val overlayKind: String,
    val binding: MigrationOverlayBinding,
    val dialect: String,
    val entries: List<MigrationOverlayEntry>,
    val createdAt: String,
    val createdByVersion: String,
    /**
     * Voreingestellt die **niedrigste** Version, die diese Bindung ausdruecken
     * kann. Ein Uebergangs-Dokument bleibt damit v1 und von aelteren Staenden
     * lesbar; nur wer die neue Darstellungs-Bindung benutzt, verlangt v2. Die
     * Version pauschal zu heben braeche die Abwaertskompatibilitaet fuer
     * Dokumente, die kein einziges v2-Merkmal tragen.
     */
    val formatVersion: String = minimumFormatVersionFor(binding),
    val overlayHash: String? = null,
    val producerMetadata: Map<String, String> = emptyMap(),
) {
    fun withComputedHash(): MigrationOverlay =
        copy(overlayHash = MigrationOverlayCanonicalJson.computeHash(this))

    companion object {
        /** Beide Fingerabdruecke flach; kennt nur [MigrationOverlayBinding.Transition]. */
        const val FORMAT_VERSION_V1: String = "migration-overlay.v1"

        /** Kennt zusaetzlich [MigrationOverlayBinding.Representation]. */
        const val FORMAT_VERSION_V2: String = "migration-overlay.v2"

        val SUPPORTED_FORMAT_VERSIONS: Set<String> = setOf(FORMAT_VERSION_V1, FORMAT_VERSION_V2)

        /** Die niedrigste Formatversion, die [binding] ausdruecken kann. */
        fun minimumFormatVersionFor(binding: MigrationOverlayBinding): String = when (binding) {
            is MigrationOverlayBinding.Transition -> FORMAT_VERSION_V1
            is MigrationOverlayBinding.Representation -> FORMAT_VERSION_V2
        }

        /**
         * Die Bindungsart, die eine Overlay-Art verlangt. Ein Dokument mit der
         * falschen wird abgelehnt, nicht umgedeutet — die Bindung ist eine
         * Eigenschaft der Aussage, nicht des Befehls, der sie liest.
         */
        fun requiredBindingFor(overlayKind: String): BindingKind? = when (overlayKind) {
            MigrationOverlayKinds.USING_EXPRESSION, MigrationOverlayKinds.RENAME_MAPPING -> BindingKind.TRANSITION
            MigrationOverlayKinds.PARTITION_MAPPING -> BindingKind.REPRESENTATION
            else -> null
        }
    }

    /** Die Bindungsart als Wert, fuer Vergleich und Meldung. */
    enum class BindingKind { TRANSITION, REPRESENTATION }
}

val MigrationOverlayBinding.kindOf: MigrationOverlay.BindingKind
    get() = when (this) {
        is MigrationOverlayBinding.Transition -> MigrationOverlay.BindingKind.TRANSITION
        is MigrationOverlayBinding.Representation -> MigrationOverlay.BindingKind.REPRESENTATION
    }

data class MigrationOverlayDocument(
    val source: String,
    val overlay: MigrationOverlay,
)

object MigrationOverlayKinds {
    const val USING_EXPRESSION: String = "using-expression"
    const val RENAME_MAPPING: String = "rename-mapping"

    /**
     * Partitions-Identitaet, die das Werkzeug nicht ableiten kann: welcher
     * Bezeichner des Ziels welche Kind-Partition meint, und welche
     * LIST-Wertemenge welcher RANGE-Grenze entspricht.
     *
     * Eine Aussage ueber die **Darstellung** eines Schemas, nicht ueber einen
     * Uebergang — deshalb `Representation`-gebunden.
     */
    const val PARTITION_MAPPING: String = "partition-mapping"
}

sealed interface MigrationOverlayEntry {
    val id: String
    val kind: String
    val requiredFeatures: Set<String>
}

data class OverlayText(
    val value: String,
    val secret: Boolean = false,
)

enum class MigrationOverlayDataRisk {
    NO_DATA_LOSS_EXPECTED,
    POSSIBLE_PRECISION_LOSS,
    POSSIBLE_TRUNCATION,
    POSSIBLE_PARSE_FAILURE,
    USER_ASSERTED_SAFE,
}

enum class MigrationOverlayConversionReversibility {
    AUTOMATIC,
    MANUAL_REQUIRED,
    NOT_REVERSIBLE,
}

data class UsingExpressionOverlayEntry(
    override val id: String,
    val table: String,
    val column: String,
    val sourceType: String,
    val targetType: String,
    val upUsingExpression: OverlayText,
    val downUsingExpression: OverlayText? = null,
    val dataRisk: MigrationOverlayDataRisk,
    val conversionReversibility: MigrationOverlayConversionReversibility,
    val expressionSource: String,
    val reviewedByUser: Boolean,
    override val requiredFeatures: Set<String> = emptySet(),
) : MigrationOverlayEntry {
    override val kind: String = MigrationOverlayKinds.USING_EXPRESSION
}

/**
 * Ein Eintrag der Overlay-Art `partition-mapping` — beide Faelle in einem Typ,
 * weil beide dieselbe Frage beantworten: **welche Partition des Ziels meint
 * welche der Quelle?**
 *
 * - **Kindname** ([targetPartition] gesetzt, [rangeUpperBound] nicht): „die
 *   Partition, die das Ziel `1` nennt, heisst in Wahrheit `p_2024`". SQL
 *   Server nummeriert Partitionen; ein Reverse kann den Namen nicht
 *   zurueckgeben und vergibt `p1`, `p2`, … (`R346`).
 * - **LIST → RANGE** ([values] und [rangeUpperBound] gesetzt): „die
 *   Wertemenge `(1, 2)` entspricht der oberen Grenze `3`". Ein Ziel ohne
 *   LIST-Partitionierung bricht sonst mit `E055` ab.
 *
 * Der zweite Fall ist **nachpruefbar**, und das ist sein Wert: die Mengen
 * lassen sich sortieren und auf Zusammenhang pruefen, die Grenzen daraus
 * ableiten und mit den angegebenen vergleichen. Eine Zuordnung, die eine
 * Zeile in die falsche Partition routen wuerde, kommt nicht durch. Ein
 * Namens-Mapping kann das nicht leisten — dort ist die Angabe des Anwenders
 * die einzige Quelle.
 */
data class PartitionMappingOverlayEntry(
    override val id: String,
    /** Die Tabelle, deren Partitionierung gemeint ist. */
    val table: String,
    /** Der Name, den die Partition im Soll-Schema traegt. */
    val sourcePartition: String,
    /** Der Bezeichner, unter dem das Ziel sie fuehrt — der Kindnamen-Fall. */
    val targetPartition: String? = null,
    /** Die LIST-Wertemenge dieser Partition — der LIST-Fall. */
    val values: List<String>? = null,
    /** Die RANGE-Obergrenze, die [values] entspricht — der LIST-Fall. */
    val rangeUpperBound: String? = null,
    override val requiredFeatures: Set<String> = emptySet(),
) : MigrationOverlayEntry {
    override val kind: String = MigrationOverlayKinds.PARTITION_MAPPING
}

data class RenameMappingOverlayEntry(
    override val id: String,
    val objectType: String,
    val fromName: String,
    val toName: String,
    val fromStructureFingerprint: String? = null,
    val toStructureFingerprint: String? = null,
    override val requiredFeatures: Set<String> = emptySet(),
) : MigrationOverlayEntry {
    override val kind: String = MigrationOverlayKinds.RENAME_MAPPING
}
