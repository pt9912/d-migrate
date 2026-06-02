package dev.dmigrate.core.diff.migration

/**
 * E.2 Sub-Slice A.3: dialect-resolved trigger planning input the
 * Mapper reads to decide whether a `ReplaceTrigger` will be rendered
 * natively (`CREATE OR REPLACE TRIGGER`) or as Drop+Create with a
 * visibility gap.
 *
 * Lives in `hexagon:core` so the Mapper has no `hexagon:ports-read`
 * (`TriggerCapability`) dependency. The application layer maps the
 * upstream [TriggerCapability]-equivalent into a [replaceMode] value
 * before the first `DiffPlanner.plan(...)` call. Mirrors the
 * `RenameProjectionDialect` boundary pattern from F.4 (core-local
 * discriminator, application-layer mapping).
 *
 * Default is `DROP_CREATE_FALLBACK` because it matches every dialect
 * E.2 supports today (PG file-only, MySQL, SQLite) and is the safer
 * conservative posture: a Mapper that sees the default will set
 * `hasGap = true` on every `ReplaceTrigger`. Native replace is opt-in
 * once the application layer proves the target supports it.
 */
data class TriggerPlanningContext(
    val replaceMode: TriggerReplaceMode = TriggerReplaceMode.DROP_CREATE_FALLBACK,
)

/**
 * Whether the target dialect can execute `ReplaceTrigger` as a single
 * statement (PG-14+ `CREATE OR REPLACE TRIGGER`) or whether the
 * renderer has to fall back to `DROP TRIGGER` + `CREATE TRIGGER` and
 * accept the visibility gap between the two.
 */
enum class TriggerReplaceMode {
    /** Native single-statement replace (PG-14+). No gap. */
    NATIVE_REPLACE,

    /**
     * Drop+Create fallback. A short window between the two statements
     * leaves the trigger inactive. The Mapper marks
     * `ReplaceTrigger.risks.up.hasGap = true` (symmetric for `down`)
     * so downstream consumers (strict-mode renderer, plan-artefact
     * serialisation, report) can treat the operation as gap-bearing
     * without inspecting diagnostic codes.
     */
    DROP_CREATE_FALLBACK,
}
