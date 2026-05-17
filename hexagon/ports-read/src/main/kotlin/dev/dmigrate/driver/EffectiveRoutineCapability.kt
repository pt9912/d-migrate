package dev.dmigrate.driver

/**
 * 0.9.7 routine-capability-configurable-source Sub-Slice A: sealed
 * envelope for the per-kind routine capability that
 * [DdlGenerationOptions.routineCapability] carries. Replaces the
 * earlier `data class RoutineCapability` (E.1 Slice C.1.a); existing
 * call sites construct [Valid] with the same field shape.
 *
 * Two states:
 *
 * - [Valid] — the per-dialect default resolver or the operator-supplied
 *   configuration source produced a parseable, internally consistent
 *   mapping. The renderer reads `function` / `procedure` directly via
 *   [Valid.forKind].
 * - [Invalid] — a configurable source (CLI flag or YAML entry) is
 *   structurally broken. The renderer pattern-matches this case first
 *   and emits `ROUTINE_CAPABILITY_CONFIG_INVALID` for every routine
 *   operation, carrying [Invalid.reason] verbatim into the manifest.
 *
 * Lives in `hexagon:ports-read` (not `hexagon:core`) because [Valid]
 * references [RoutineKindCapability], which in turn references
 * [MysqlServerVersion] — both hosted in ports-read.
 */
sealed interface EffectiveRoutineCapability {

    data class Valid(
        val function: RoutineKindCapability,
        val procedure: RoutineKindCapability,
    ) : EffectiveRoutineCapability {

        fun forKind(kind: RoutineKind): RoutineKindCapability = when (kind) {
            RoutineKind.FUNCTION -> function
            RoutineKind.PROCEDURE -> procedure
        }
    }

    data class Invalid(val reason: String) : EffectiveRoutineCapability
}
