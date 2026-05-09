package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.SchemaDefinition

/**
 * Normalizes reverse-generated `name`/`version` markers on a
 * [SchemaDefinition] before fingerprinting / comparing / planning.
 *
 * Reverse-generated schemas use [ReverseScopeCodec.PREFIX] in
 * `SchemaDefinition.name` and `ReverseScopeCodec.REVERSE_VERSION` in
 * `SchemaDefinition.version`. These synthetic values are an
 * artefact of the reverse-engineering provenance; if they reach the
 * planner unmodified they would produce a phantom
 * `SchemaMetadataDiff` ("name/version changed") even though the
 * actual schema content matches.
 *
 * The normalizer is the **shared** entry point that `schema compare`
 * and `schema migrate` (the [DiffPlanner] in Phase C) both call —
 * keeping the comparison and the planning pipeline aligned. The
 * older `CompareOperandNormalizer` in `hexagon:application` remains
 * for compare-specific operand handling but delegates the actual
 * marker normalisation to this helper.
 *
 * Behaviour:
 *
 * - Schema name does NOT start with [ReverseScopeCodec.PREFIX]:
 *   pass-through (untouched copy).
 * - Schema name starts with the prefix and the
 *   `(name, version)` tuple is a valid reverse marker
 *   per [ReverseScopeCodec.isReverseGenerated]: replace `name` and
 *   `version` with [NORMALIZED_NAME] / [NORMALIZED_VERSION].
 * - Schema name starts with the prefix but the marker set is
 *   incomplete or malformed: throw [IllegalStateException]. This
 *   guards against a future bug where the reverse pipeline emits a
 *   half-formed marker that would otherwise silently produce a
 *   diff.
 *
 * See `docs/planning/open/diffresult-migration-plan.md §11.1` for
 * the Phase A decision.
 */
object ReverseMarkerNormalizer {

    /** Placeholder name written when a reverse-generated marker is normalized away. */
    const val NORMALIZED_NAME: String = "__compare_normalized__"

    /** Placeholder version written when a reverse-generated marker is normalized away. */
    const val NORMALIZED_VERSION: String = "0.0.0"

    /**
     * Returns [schema] unchanged if it does not carry a reverse
     * marker; otherwise returns a copy with `name` / `version` set
     * to the normalized placeholders.
     *
     * @throws IllegalStateException if `schema.name` starts with
     *   [ReverseScopeCodec.PREFIX] but the marker tuple is invalid.
     */
    fun normalize(schema: SchemaDefinition): SchemaDefinition {
        val name = schema.name
        val version = schema.version

        if (!name.startsWith(ReverseScopeCodec.PREFIX)) return schema

        if (!ReverseScopeCodec.isReverseGenerated(name, version)) {
            throw IllegalStateException(
                "Schema name '$name' uses reserved prefix " +
                    "'${ReverseScopeCodec.PREFIX}' but has invalid or " +
                    "incomplete reverse marker set (version='$version')",
            )
        }

        return schema.copy(
            name = NORMALIZED_NAME,
            version = NORMALIZED_VERSION,
        )
    }

    /**
     * True iff [schema] currently carries a normalized marker. Used
     * by reports / metadata blocks that want to flag "this side was
     * reverse-generated and has been normalized" without re-running
     * [normalize].
     */
    fun isNormalized(schema: SchemaDefinition): Boolean =
        schema.name == NORMALIZED_NAME && schema.version == NORMALIZED_VERSION
}
