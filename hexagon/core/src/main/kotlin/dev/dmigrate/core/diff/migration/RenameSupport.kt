package dev.dmigrate.core.diff.migration

/**
 * F.4 Sub-Slice A.1: per-(dialect, object-kind) classification of how
 * a rename candidate can be turned into actual DDL. The Mapper-/
 * Planner-phase `ObjectRenamePolicy` returns one of these values; the
 * renderer never re-classifies.
 *
 * - [Native]: the dialect has a native `ALTER … RENAME TO …` for this
 *   object kind. The Mapper emits a `Rename*` subtype directly; the
 *   renderer renders it as one statement.
 *
 * - [DropCreateFallback]: the dialect has no native rename for this
 *   kind, but the Drop+Create equivalence is safe (same body, same
 *   signature). The Mapper emits `Drop*` + `Create*` with a
 *   [RenameProvenance] attached so the report can present the pair
 *   as a logical rename rather than a destructive change. [rationale]
 *   names the specific reason ("MySQL has no `ALTER TRIGGER … RENAME`",
 *   "SQLite has no native view rename").
 *
 * - [Blocked]: rename is not safe for this (dialect, kind) at all —
 *   missing prior body, materialized-view-without-D.3b, sequence on a
 *   dialect that has no sequence rendering yet, etc. The Mapper emits
 *   a `MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED` blocker with
 *   [code] / [message] in the diagnostic; no `Rename*` or Drop+Create
 *   leaks through.
 */
internal sealed interface RenameSupport {

    data object Native : RenameSupport

    data class DropCreateFallback(val rationale: String) : RenameSupport

    data class Blocked(val code: String, val message: String) : RenameSupport
}
