package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.ReverseMarkerNormalizer
import dev.dmigrate.core.model.SchemaDefinition

/**
 * Der Vergleich von `schema compare` — **eine** Semantik fuer alle drei
 * Oberflaechen: die CLI (`SchemaCompareWiring`), das MCP-Werkzeug
 * `schema_compare` und den MCP-Job `schema_compare_start`
 * (`spec/cli-spec.md`, `spec/mcp-server.md`).
 *
 * - Jede Seite verliert ihre Reverse-Markierung; der Dialekt, aus dem sie
 *   zurueckgelesen wurde, bleibt als [CompareSide.sourceDialect] erhalten
 *   ([side]).
 * - Der Comparator setzt die Dialekt-Schreibweise roher Ausdruecke gleich
 *   (ADR 0056) und blendet die Server-Buchhaltung einer Identity-Spalte aus,
 *   wo ein Reverse sie so liest ([compareGenerationCanonicalizer]).
 * - Ein unentscheidbarer Berechnungsausdruck wird als `W137` gemeldet
 *   ([undecided]).
 *
 * `schema migrate` und der Fingerabdruck benutzen nichts davon.
 */
object SchemaCompareSemantics {

    /**
     * Eine Seite aus einem geladenen Schema: die Reverse-Markierung entfernt
     * (sonst ergaeben zwei Reverses aus verschiedenen Dialekten einen
     * `name`-Fund), der Dialekt aus ihr gelesen.
     *
     * @throws IllegalStateException wenn der Name das reservierte Praefix
     *   traegt, die Markierung aber unvollstaendig ist
     *   ([ReverseMarkerNormalizer]).
     */
    fun side(schema: SchemaDefinition): CompareSide =
        CompareSide(ReverseMarkerNormalizer.normalize(schema), reverseSourceDialect(schema))

    /** Der Vergleich zweier Seiten — [source] ist das Ist, [target] das Soll. */
    fun compare(source: CompareSide, target: CompareSide): SchemaDiff =
        SchemaComparator(
            canonicalizeRawExpressions = true,
            comparisonGeneration = compareGenerationCanonicalizer(source, target),
        ).compare(source.schema, target.schema)

    /**
     * Die Berechnungsausdruecke, deren Aenderung der Vergleich nicht
     * entscheiden kann (`W137`) — fuer dasselbe Paar wie [compare]
     * (`source` = Ist, `target` = Soll).
     */
    fun undecided(source: CompareSide, target: CompareSide): List<DiffDiagnostic> =
        ComputedExpressionDecidability.diagnostics(
            current = source.schema,
            desired = target.schema,
            authorship = null,
            serverForm = null,
        )
}
