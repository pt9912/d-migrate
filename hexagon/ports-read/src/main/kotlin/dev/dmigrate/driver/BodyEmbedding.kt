package dev.dmigrate.driver

/**
 * E.1 Routine-Migration Slice F.3: artefact-/persistence-flag that
 * records whether the routine body was safely embedded into a
 * report or rollback artefact for this run.
 *
 * Per E.1 Plan §1:
 * - [BodyEmbeddingStatus.ENABLED]: the raw body is safe to persist in
 *   artefacts AND Down-generation is reliable for this run.
 * - [BodyEmbeddingStatus.DISABLED]: no safe persistence path; the
 *   Execution-Plane statements can still run, but Display-Plane
 *   stays scrubbed-only.
 * - [BodyEmbeddingStatus.BLOCKED]: the gate configuration is
 *   inconsistent / incomplete. Artefact persistence is blocked and
 *   Down-rendering may not consume stored embedding data — only
 *   `current_schema` or a valid live-DB readback in the same run
 *   counts as a safe pre-body source.
 *
 * `bodyEmbedding` is purely an artefact-/persistence flag. It is
 * NEVER a decision input for whether Down-rendering can produce
 * SQL — that path is governed by the pre-body validation
 * (`current_schema` / `db_readback`) inside the same run, regardless
 * of [BodyEmbeddingStatus]. See §1 "Up-only-Prinzip" and
 * `ROUTINE_DOWN_BODY_UNKNOWN`.
 *
 * In the E.1 iteration the initial value is [BodyEmbeddingStatus.DISABLED]
 * with [version] `"body-embed.v1"` and [source] [BodyEmbeddingSource.NONE].
 * Persistence enablement is a follow-up workstream (F.2).
 */
data class BodyEmbedding(
    val status: BodyEmbeddingStatus,
    val version: String,
    val source: BodyEmbeddingSource,
    /**
     * Reason text for [BodyEmbeddingStatus.BLOCKED]. Null when status is
     * not BLOCKED. The reason surfaces in the report so operators see
     * why persistence was suppressed (e.g. "no valid pre-body source").
     */
    val reason: String? = null,
) {
    init {
        require(status != BodyEmbeddingStatus.BLOCKED || reason != null) {
            "BodyEmbeddingStatus.BLOCKED requires a non-null reason"
        }
        require(status == BodyEmbeddingStatus.BLOCKED || reason == null) {
            "reason is only allowed for BodyEmbeddingStatus.BLOCKED"
        }
    }

    companion object {
        /** Stable wire-format version pin per E.1 Plan §1. */
        const val CURRENT_VERSION: String = "body-embed.v1"

        /**
         * E.1 initial state: persistence disabled, no source bound.
         * Used as the default on every fresh [BodyEmbedding] until a
         * future slice wires the ENABLED path.
         */
        fun disabledDefault(): BodyEmbedding = BodyEmbedding(
            status = BodyEmbeddingStatus.DISABLED,
            version = CURRENT_VERSION,
            source = BodyEmbeddingSource.NONE,
        )

        fun blocked(reason: String, source: BodyEmbeddingSource = BodyEmbeddingSource.NONE): BodyEmbedding =
            BodyEmbedding(
                status = BodyEmbeddingStatus.BLOCKED,
                version = CURRENT_VERSION,
                source = source,
                reason = reason,
            )
    }
}

enum class BodyEmbeddingStatus { ENABLED, DISABLED, BLOCKED }

enum class BodyEmbeddingSource { CURRENT_SCHEMA, DB_READBACK, NONE }
