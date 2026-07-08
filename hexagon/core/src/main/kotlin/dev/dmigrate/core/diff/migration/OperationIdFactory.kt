package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.util.sha256Hex

/**
 * Deterministic [DiffOperation] ID derivation per
 * `docs/planning/done-archive/diffresult-migration-plan.md §4.2.1`.
 *
 * An ID is a stable function of:
 *
 * 1. plan direction — the constant string `current->desired` for the
 *    first slice;
 * 2. operation kind — the simple class name of the [DiffOperation]
 *    subtype, e.g. `AddColumn`;
 * 3. object kind — the [DiffObjectType] enum name;
 * 4. object path — joined with a unit-separator that cannot occur in
 *    schema identifiers (``);
 * 5. payload fingerprint — the SHA-256 of a canonical
 *    payload string the caller supplies.
 *
 * Tie-breaking: when two operations would compute identical IDs (the
 * same kind on the same path with the same payload — a degenerate
 * case but contractually possible for caller-controlled
 * [payloadCanonical] strings), [makeId] returns the base ID and the
 * caller appends a `#N` suffix during deterministic sorting via
 * [disambiguate].
 *
 * The output format is `<kind>:<objectType>:<pathHex>:<payloadHex>`
 * where the hex tails are short prefixes of the SHA-256 to keep IDs
 * legible while preserving collision resistance.
 */
object OperationIdFactory {

    /** Direction prefix folded into every ID hash. */
    const val DIRECTION_PREFIX: String = "current->desired"

    /** Path component separator. Shared with [CanonicalPayload]. */
    private const val PATH_SEP: Char = CanonicalEncoding.SEP

    /** Hex prefix length retained from each SHA-256 input. */
    private const val SHORT_HEX_PREFIX: Int = 12

    /**
     * Build the deterministic base ID for an operation.
     *
     * @param operationKind simple Kotlin class name of the
     *   [DiffOperation] subtype, e.g. `"AddColumn"`. Pass
     *   `op::class.simpleName!!` from the planner.
     * @param objectRef the operation's [DiffObjectRef].
     * @param payloadCanonical deterministic string projection of the
     *   operation's rendering payload — e.g. for `AddColumn` the
     *   serialised before/after column definition, for
     *   `AlterColumnType` the canonical type names. Empty string is
     *   acceptable for parameterless operations like
     *   `AlterColumnNullability`-without-payload (none today, but
     *   future-proof).
     */
    fun makeId(
        operationKind: String,
        objectRef: DiffObjectRef,
        payloadCanonical: String,
    ): String {
        val pathHash = sha256Hex(
            buildString {
                append(DIRECTION_PREFIX)
                append(PATH_SEP)
                append(objectRef.type.name)
                for (segment in objectRef.path) {
                    append(PATH_SEP)
                    append(segment)
                }
            },
        ).take(SHORT_HEX_PREFIX)
        val payloadHash = sha256Hex(payloadCanonical).take(SHORT_HEX_PREFIX)
        return "$operationKind:${objectRef.type.name}:$pathHash:$payloadHash"
    }

    /**
     * Apply deterministic `#N` suffixes to a list of (id, position)
     * pairs so that colliding base IDs receive a stable disambiguator.
     * The lowest position retains the bare ID; subsequent collisions
     * receive `#2`, `#3`, … in order of appearance.
     *
     * Callers pass `(baseId, indexInPlannerOutput)`; the result has
     * the same length and order as the input.
     */
    fun disambiguate(items: List<Pair<String, Int>>): List<String> {
        val sorted = items.sortedBy { it.second }
        val seen = mutableMapOf<String, Int>()
        val resultByPosition = HashMap<Int, String>(sorted.size)
        for ((baseId, position) in sorted) {
            val n = seen.merge(baseId, 1, Int::plus) ?: 1
            resultByPosition[position] = if (n == 1) baseId else "$baseId#$n"
        }
        return items.map { resultByPosition.getValue(it.second) }
    }
}
