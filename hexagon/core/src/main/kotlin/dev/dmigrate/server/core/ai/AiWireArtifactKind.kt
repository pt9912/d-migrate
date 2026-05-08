package dev.dmigrate.server.core.ai

/**
 * LF-017 / LF-024 / LN-030 / LN-031— verbindliche Wertkonstanten für das
 * `wireArtifactKind`-Metadatenfeld an KI-Artefakten.
 *
 * LF-017 / LF-024 / LN-030 / LN-031 listet drei Werte. Solange der Core-
 * `ArtifactKind`-Enum nicht erweitert wird (LF-017 / LF-024 / LN-030 / LN-031 Pfad-A,
 * LF-017 / LF-024 / LN-030 / LN-031-Bestandsaufnahme §3.2 — Default-Pfad), ist `wireArtifactKind`
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
     * Follow-up AP 3 — von `testdata_execute` erzeugtes importierbares
     * Datenartefakt (Single-Table-CSV/JSON). LF-017 / LF-024 / LN-030 / LN-031 Pfad-A: Ergebnis
     * trägt zusätzlich [dev.dmigrate.server.core.artifact.ArtifactUploadMetadata]
     * mit `uploadIntent=job_input`, sodass `data_import_start` es als
     * normales Upload-Artefakt akzeptiert.
     */
    const val GENERATED_TESTDATA: String = "generated-testdata"

    /**
     * Follow-up AP 3 — von `testdata_execute` erzeugtes Bundle-Artefakt
     * (Mehrtabellen). Trägt zusätzlich `bundleFormat=seed-bundle.v1.zip`
     * im persistenten `ArtifactUploadMetadata`, damit `data_import_start`
     * den Bundle-Vertrag aus AP 2 wiederverwenden kann.
     */
    const val SEED_DATA_BUNDLE: String = "seed-data-bundle"

    /**
     * Sentinel-Set für Resource-Read- und Execute-Lookup-Pruefungen
     * (LF-017 / LF-024 / LN-030 / LN-031/f). Ein KI-Artefakt mit `wireArtifactKind` ausserhalb
     * dieses Sets ist `RESOURCE_NOT_FOUND` für KI-spezifische
     * Konsumenten.
     */
    val ALL: Set<String> = setOf(
        PROCEDURE_TRANSFORM_PLAN,
        PROCEDURE_TRANSFORM_OUTPUT,
        TESTDATA_PLAN,
        GENERATED_TESTDATA,
        SEED_DATA_BUNDLE,
    )
}

/**
 * LF-017 / LF-024 / LN-030 / LN-031— verbindliche Werte für das
 * `aiIntent`-Metadatenfeld.
 *
 * `aiIntent` ist die Maschinenversion des Tool-Namens, der das
 * Artefakt erzeugt hat. LF-017 / LF-024 / LN-030 / LN-031:
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

    /** Follow-up AP 3 — `testdata_execute`-Tool-Marker. */
    const val TESTDATA_EXECUTE: String = "testdata_execute"

    val ALL: Set<String> = setOf(
        PROCEDURE_TRANSFORM_PLAN,
        PROCEDURE_TRANSFORM_EXECUTE,
        TESTDATA_PLAN,
        TESTDATA_EXECUTE,
    )
}
