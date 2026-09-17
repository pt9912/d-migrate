package dev.dmigrate.mcp.registry

import dev.dmigrate.cli.commands.CompareSide
import dev.dmigrate.cli.commands.SchemaCompareSemantics
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition

/**
 * Das Ergebnis eines Schema-Vergleichs, wie **beide** MCP-Oberflaechen es
 * tragen: das Werkzeug `schema_compare` (in seiner Antwort und, wenn sie zu
 * gross wird, als Ueberlauf-Artefakt) und der Job `schema_compare_start`
 * (als Artefakt). Beide Artefakte haben die Art `COMPARE` und **eine** Form
 * ([artifact]).
 *
 * [findings] ist die ungekuerzte Liste — die Funde des Diffs
 * ([SchemaCompareFindings]) und danach die `W137`-Funde. [identical] haengt
 * nur am Diff: ein `W137` laesst den Status `identical` stehen.
 */
internal data class SchemaCompareOutcome(
    val identical: Boolean,
    val findings: List<Map<String, Any?>>,
) {

    val status: String get() = if (identical) "identical" else "different"

    /** Die Zusammenfassung; [asArtifact] = die volle Liste steht in einem eigenen Artefakt. */
    fun summary(asArtifact: Boolean = false): String = when {
        identical -> "Schemas are identical."
        asArtifact -> "Schemas differ (${findings.size} change(s)); full diff returned as artefact."
        else -> "Schemas differ (${findings.size} change(s))."
    }

    /**
     * Der Inhalt eines Compare-Artefakts (`spec/mcp-server.md`): `status`,
     * `summary` und `findings` wie in der Antwort von `schema_compare`, aber
     * ungekuerzt. `truncated`, `diffArtifactRef` und `executionMeta` gehoeren
     * zur Antwort eines Aufrufs, nicht zum Ergebnis — der Job hat keinen
     * Aufruf, dessen `requestId` er tragen koennte, und dasselbe Ergebnis
     * soll unabhaengig vom Weg dieselben Bytes ergeben.
     */
    fun artifact(): Map<String, Any?> = linkedMapOf(
        "status" to status,
        "summary" to summary(),
        "findings" to findings,
    )

    companion object {

        /**
         * Vergleicht zwei geladene Schemata — Ist links, Soll rechts — nach
         * der Semantik von `schema compare` ([SchemaCompareSemantics]).
         *
         * [comparator] ist injiziert, damit die Baustellen ihn bauen; die
         * Seiten sind bereits ohne Reverse-Markierung.
         */
        fun of(
            source: CompareSide,
            target: CompareSide,
            comparator: (CompareSide, CompareSide) -> SchemaDiff,
        ): SchemaCompareOutcome {
            val diff = comparator(source, target)
            val undecided = SchemaCompareSemantics.undecided(source, target)
            return SchemaCompareOutcome(
                identical = diff.isEmpty(),
                findings = SchemaCompareFindings.of(diff) + undecided.map(SchemaCompareFindings::undecided),
            )
        }

        /** Wie [of], fuer zwei Schemata, die ihre Reverse-Markierung noch tragen. */
        fun ofSchemas(
            source: SchemaDefinition,
            target: SchemaDefinition,
            comparator: (CompareSide, CompareSide) -> SchemaDiff,
        ): SchemaCompareOutcome =
            of(SchemaCompareSemantics.side(source), SchemaCompareSemantics.side(target), comparator)
    }
}
