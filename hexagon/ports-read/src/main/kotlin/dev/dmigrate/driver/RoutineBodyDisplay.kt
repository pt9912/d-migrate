package dev.dmigrate.driver

/**
 * E.1 Routine-Migration Slice C.1.a: display-plane switch for
 * routine bodies.
 *
 * The DDL renderer always writes the raw routine body into the
 * Execution-Plane (otherwise the migration is not executable). This
 * switch controls only the Display-/Diagnostic-Plane (reports,
 * goldens, CLI output):
 *
 * - [SCRUBBED_ONLY] (default): reports replace the raw body with
 *   `RoutineBodyScrubber.preview(...)` — a structured
 *   `{hash, length, scrubbedPreview, scrubbingApplied}` shape that
 *   masks credential-like literals.
 * - [RAW_DEBUG]: unsafe override behind the `--debug-body` CLI flag;
 *   reports carry the unmasked body. Document the unsafe usage in
 *   `spec/cli-spec.md` §6.1.
 */
enum class RoutineBodyDisplay {
    SCRUBBED_ONLY,
    RAW_DEBUG,
}
