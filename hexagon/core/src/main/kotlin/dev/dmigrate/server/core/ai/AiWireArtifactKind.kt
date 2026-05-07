package dev.dmigrate.server.core.ai

/**
 * Phase G § 5.4 (G.6.b) — verbindliche Wertkonstanten für das
 * `wireArtifactKind`-Metadatenfeld an KI-Artefakten.
 *
 * Plan §5.4 Z. 712-721 listet drei Werte. Solange der Core-
 * `ArtifactKind`-Enum nicht erweitert wird (Plan-§-5.4-Pfad-A,
 * G.1-Bestandsaufnahme §3.2 — Default-Pfad), ist `wireArtifactKind`
 * Pflicht-Metadatum, damit Resource-Reads, Execute-Lookups und
 * Prompt-Argumentvalidierung KI-Artefakte zuverlässig erkennen.
 *
 * Strings statt Enum: bewusste Wahl, damit ein zukünftiges KI-Tool
 * (etwa `data_classification_plan`) einen neuen Wert ergänzt, ohne
 * dass alle bestehenden Switches/`when`-Ausdrücke
 * Exhaustiveness-bedingt anschlagen. Wer auf den Wert match'en
 * muss, prüft explizit gegen die Konstanten unten.
 */
object AiWireArtifactKind {
    const val PROCEDURE_TRANSFORM_PLAN: String = "procedure-transform-plan"
    const val PROCEDURE_TRANSFORM_OUTPUT: String = "procedure-transform-output"
    const val TESTDATA_PLAN: String = "testdata-plan"

    /**
     * Sentinel-Set für Resource-Read- und Execute-Lookup-Pruefungen
     * (G.6.d/e/f). Ein KI-Artefakt mit `wireArtifactKind` ausserhalb
     * dieses Sets ist `RESOURCE_NOT_FOUND` für KI-spezifische
     * Konsumenten.
     */
    val ALL: Set<String> = setOf(
        PROCEDURE_TRANSFORM_PLAN,
        PROCEDURE_TRANSFORM_OUTPUT,
        TESTDATA_PLAN,
    )
}

/**
 * Phase G § 5.4 (G.6.b) — verbindliche Werte für das
 * `aiIntent`-Metadatenfeld.
 *
 * `aiIntent` ist die Maschinenversion des Tool-Namens, der das
 * Artefakt erzeugt hat. Plan §5.4 Z. 716-721:
 *
 * - `PROCEDURE_TRANSFORM_PLAN` — Plan-Artefakte aus
 *   `procedure_transform_plan`.
 * - `PROCEDURE_TRANSFORM_EXECUTE` — Zielartefakte aus
 *   `procedure_transform_execute`.
 * - `TESTDATA_PLAN` — Testdatenpläne aus `testdata_plan`.
 *
 * Im Gegensatz zu [AiWireArtifactKind] ist `aiIntent` strikt an
 * den Tool-Namen gebunden. Resource-Reads und Execute-Lookups
 * müssen `aiIntent` und `wireArtifactKind` zusammen prüfen.
 */
object AiIntent {
    const val PROCEDURE_TRANSFORM_PLAN: String = "procedure_transform_plan"
    const val PROCEDURE_TRANSFORM_EXECUTE: String = "procedure_transform_execute"
    const val TESTDATA_PLAN: String = "testdata_plan"

    val ALL: Set<String> = setOf(
        PROCEDURE_TRANSFORM_PLAN,
        PROCEDURE_TRANSFORM_EXECUTE,
        TESTDATA_PLAN,
    )
}
