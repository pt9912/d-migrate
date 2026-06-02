package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ParameterDefinition

/**
 * F.4 Sub-Slice A.1: input the Mapper passes to
 * `ObjectRenamePolicy.classify(...)` per rename candidate.
 *
 * The fields capture every dialect-decision input the policy needs:
 *
 * - [objectType] selects the per-kind branch
 *   (`VIEW`/`TRIGGER`/`FUNCTION`/`PROCEDURE`/`SEQUENCE`).
 * - [fromName] and [toName] are the visible names (overlay source).
 *   For triggers and routines, [ObjectKeyCodec][dev.dmigrate.core.identity.ObjectKeyCodec]-encoded
 *   keys live in `objectRef.path[0]` of the eventual `Rename*` op; the
 *   candidate carries the *visible* name pair for renderer template
 *   substitution.
 * - [materializedView] distinguishes regular vs. materialized views so
 *   the policy can keep materialized-view rename blocked until D.3b
 *   ships a dedicated contract.
 * - [triggerTableName] is required for PostgreSQL's
 *   `ALTER TRIGGER <name> ON <table> RENAME TO …` template. Null for
 *   non-trigger candidates.
 * - [routineSignature] disambiguates overloaded routines.
 *   Used both for identity (`ObjectKeyCodec.routineKey`) and for
 *   PostgreSQL's `ALTER FUNCTION <name>(<types>) RENAME TO …`
 *   template. Empty for non-routine candidates.
 * - [sourceBodyHash] / [targetBodyHash] drive the body-drift check.
 *   A `Native` classification is only safe when both bodies are
 *   present and equal; an unequal pair forces `DropCreateFallback`
 *   (on dialects that support it) or `Blocked` (when the prior body
 *   is missing).
 */
internal data class ObjectRenameCandidate(
    val objectType: DiffObjectType,
    val fromName: String,
    val toName: String,
    val materializedView: Boolean = false,
    val triggerTableName: String? = null,
    val routineSignature: List<ParameterDefinition> = emptyList(),
    val sourceBodyHash: String? = null,
    val targetBodyHash: String? = null,
)
