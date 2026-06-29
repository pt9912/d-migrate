package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement

/**
 * Builds the `d-migrate rollback-sql v2` Down-SQL artefact per
 * `docs/planning/done-archive/diffresult-migration-plan.md §7.3` and
 * `spec/cli-spec.md §6.1`.
 *
 * Output shape:
 *
 * ```
 * -- d-migrate rollback-sql v2 begin
 * -- {"artifactHash":"…","artifactHashAlgorithm":"sha256-rollback-artifact-v2",…}
 * -- d-migrate rollback-sql v2 end
 * <down SQL body>
 * ```
 *
 * The header JSON is canonical: keys sorted lexicographically, no
 * whitespace outside string values. The `artifactHash` binds the
 * header (minus the hash field itself) to the SQL body. The v2
 * `statementIndex` also binds each executable body slice by UTF-8
 * byte range and SHA-256 hash, so rollback execution can reconstruct
 * statements without whitespace splitting.
 */
internal object RollbackArtefactBuilder {

    const val FORMAT: String = "d-migrate rollback-sql"
    const val FORMAT_VERSION_V1: String = "v1"
    const val FORMAT_VERSION: String = "v2"
    const val FINGERPRINT_ALGORITHM: String = MigrationFingerprint.ALGORITHM
    const val ARTIFACT_HASH_ALGORITHM_V1: String = "sha256-rollback-artifact-v1"
    const val ARTIFACT_HASH_ALGORITHM: String = "sha256-rollback-artifact-v2"

    const val BEGIN_DELIMITER_V1 = "-- d-migrate rollback-sql v1 begin"
    const val END_DELIMITER_V1 = "-- d-migrate rollback-sql v1 end"
    const val BEGIN_DELIMITER = "-- d-migrate rollback-sql v2 begin"
    const val END_DELIMITER = "-- d-migrate rollback-sql v2 end"

    data class Risk(
        val destructive: Boolean,
        val dataLossPossible: Boolean,
        val requiresManualConfirmation: Boolean,
        val operationIds: Set<String>,
    )

    /**
     * Bundle of all inputs the builder needs. Bundling avoids a long
     * positional parameter list at the call site (Detekt
     * LongParameterList) and makes future field additions
     * (e.g. recovery-only metadata) source-compatible.
     */
    data class Input(
        val dialect: DatabaseDialect,
        val currentFingerprint: String,
        val desiredFingerprint: String,
        val postUpFingerprint: String,
        val operationIds: Set<String>,
        val risk: Risk,
        val downStatements: List<MigrationDdlStatement>,
        val createdByVersion: String,
        val recovery: Boolean = false,
        val postUpVerified: Boolean = false,
        val allowedPostUpFingerprints: List<String>? = null,
        val partialRollback: Boolean = false,
        val skippedOperationIds: Set<String> = emptySet(),
        val emitPartialRollbackFields: Boolean = true,
        /**
         * Algorithm that produced [currentFingerprint]/[desiredFingerprint]/
         * [postUpFingerprint]. Recorded so `schema rollback` can reject an artefact
         * whose fingerprints are not comparable to this build's (ROLLBACK_FINGERPRINT_
         * ALGORITHM_MISMATCH). Defaults to the current [FINGERPRINT_ALGORITHM] — callers
         * compute the fingerprints with the live `MigrationFingerprint`, so the default is
         * always correct in production; the override exists for cross-version tests.
         */
        val fingerprintAlgorithm: String = FINGERPRINT_ALGORITHM,
    )

    fun build(input: Input): String {
        validateCompleteness(input, legacy = false)
        val body = canonicalBody(input.downStatements)
        val headerWithoutHash = headerJson(input, body.statementIndex, artifactHash = null)
        val artifactHash = sha256Hex(headerWithoutHash + body.sqlBody)
        val headerWithHash = headerJson(input, body.statementIndex, artifactHash = artifactHash)
        return buildString {
            append(BEGIN_DELIMITER).append('\n')
            append("-- ").append(headerWithHash).append('\n')
            append(END_DELIMITER).append('\n')
            append(body.sqlBody)
        }
    }

    fun buildLegacyV1(input: Input): String {
        validateCompleteness(input, legacy = true)
        val body = legacyCanonicalBody(input.downStatements)
        val headerWithoutHash = headerJsonV1(input, artifactHash = null)
        val artifactHash = sha256Hex(headerWithoutHash + body)
        val headerWithHash = headerJsonV1(input, artifactHash = artifactHash)
        return buildString {
            append(BEGIN_DELIMITER_V1).append('\n')
            append("-- ").append(headerWithHash).append('\n')
            append(END_DELIMITER_V1).append('\n')
            append(body)
        }
    }

    /**
     * Canonical SQL body: LF-only line endings, exactly one trailing newline.
     * Empty bodies produce a single newline so the canonical form is total.
     */
    private fun canonicalBody(stmts: List<MigrationDdlStatement>): CanonicalBody {
        if (stmts.isEmpty()) return CanonicalBody(sqlBody = "\n", statementIndex = emptyList())
        val normalized = stmts.map { it.sql.replace("\r\n", "\n").replace('\r', '\n') }
        val sqlBody = normalized.joinToString("\n\n").let { if (it.endsWith("\n")) it else "$it\n" }
        var byteOffset = 0
        val entries = stmts.zip(normalized).mapIndexed { index, (stmt, sql) ->
            val start = byteOffset
            val end = start + sql.toByteArray(Charsets.UTF_8).size
            byteOffset = end + if (index == normalized.lastIndex) {
                if (sql.endsWith("\n")) 0 else 1
            } else {
                2
            }
            StatementIndexEntry(
                index = index,
                operationIds = stmt.operationIds.sorted(),
                phase = stmt.phase.name,
                transactionScope = stmt.transactionScope.name,
                destructive = stmt.risk.destructive,
                dataLossPossible = stmt.risk.dataLossPossible,
                requiresManualConfirmation = stmt.risk.requiresManualConfirmation,
                startInclusive = start,
                endExclusive = end,
                sha256 = sha256Hex(sql),
            )
        }
        return CanonicalBody(sqlBody = sqlBody, statementIndex = entries)
    }

    private fun legacyCanonicalBody(stmts: List<MigrationDdlStatement>): String {
        if (stmts.isEmpty()) return "\n"
        val joined = stmts.joinToString("\n\n") { it.sql.replace("\r\n", "\n").replace('\r', '\n') }
        return if (joined.endsWith("\n")) joined else "$joined\n"
    }

    /**
     * Canonical JSON serialiser for the metadata header. Keys are
     * emitted in lexicographic order; string values JSON-escape only
     * `"`, `\`, and control characters (LF/CR/TAB). The implementation
     * is deliberately small — no third-party JSON dependency and the
     * field set is fixed by spec.
     */
    private fun headerJson(
        input: Input,
        statementIndex: List<StatementIndexEntry>,
        artifactHash: String?,
    ): String {
        val fields = mutableListOf<Pair<String, String>>()
        if (artifactHash != null) fields += "artifactHash" to jsonString(artifactHash)
        fields += "artifactHashAlgorithm" to jsonString(ARTIFACT_HASH_ALGORITHM)
        if (input.recovery && input.allowedPostUpFingerprints != null) {
            fields += "allowedPostUpFingerprints" to
                jsonArray(input.allowedPostUpFingerprints.map { jsonString(it) })
        }
        fields += "createdByVersion" to jsonString(input.createdByVersion)
        fields += "currentFingerprint" to jsonString(input.currentFingerprint)
        fields += "desiredFingerprint" to jsonString(input.desiredFingerprint)
        fields += "dialect" to jsonString(input.dialect.name)
        fields += "fingerprintAlgorithm" to jsonString(input.fingerprintAlgorithm)
        fields += "format" to jsonString(FORMAT)
        fields += "formatVersion" to jsonString(FORMAT_VERSION)
        fields += "operationIds" to jsonArray(input.operationIds.sorted().map { jsonString(it) })
        if (input.emitPartialRollbackFields) {
            fields += "partialRollback" to input.partialRollback.toString()
        }
        fields += "postUpFingerprint" to jsonString(input.postUpFingerprint)
        fields += "postUpVerified" to input.postUpVerified.toString()
        fields += "recovery" to input.recovery.toString()
        if (input.emitPartialRollbackFields) {
            fields += "rollbackComplete" to (!input.partialRollback).toString()
        }
        fields += "risk" to riskJson(input.risk)
        if (input.emitPartialRollbackFields) {
            fields += "skippedOperationIds" to jsonArray(input.skippedOperationIds.sorted().map { jsonString(it) })
        }
        fields += "statementIndex" to statementIndexJson(statementIndex)
        // Fields are emitted in sorted key order — re-sort defensively.
        val sorted = fields.sortedBy { it.first }
        return buildString {
            append('{')
            sorted.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append(jsonString(k)).append(':').append(v)
            }
            append('}')
        }
    }

    private fun headerJsonV1(input: Input, artifactHash: String?): String {
        val fields = mutableListOf<Pair<String, String>>()
        if (artifactHash != null) fields += "artifactHash" to jsonString(artifactHash)
        fields += "artifactHashAlgorithm" to jsonString(ARTIFACT_HASH_ALGORITHM_V1)
        if (input.recovery && input.allowedPostUpFingerprints != null) {
            fields += "allowedPostUpFingerprints" to
                jsonArray(input.allowedPostUpFingerprints.map { jsonString(it) })
        }
        fields += "createdByVersion" to jsonString(input.createdByVersion)
        fields += "currentFingerprint" to jsonString(input.currentFingerprint)
        fields += "desiredFingerprint" to jsonString(input.desiredFingerprint)
        fields += "dialect" to jsonString(input.dialect.name)
        fields += "fingerprintAlgorithm" to jsonString(input.fingerprintAlgorithm)
        fields += "format" to jsonString(FORMAT)
        fields += "formatVersion" to jsonString(FORMAT_VERSION_V1)
        fields += "operationIds" to jsonArray(input.operationIds.sorted().map { jsonString(it) })
        fields += "postUpFingerprint" to jsonString(input.postUpFingerprint)
        fields += "postUpVerified" to input.postUpVerified.toString()
        fields += "recovery" to input.recovery.toString()
        fields += "risk" to riskJson(input.risk)
        val sorted = fields.sortedBy { it.first }
        return buildString {
            append('{')
            sorted.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append(jsonString(k)).append(':').append(v)
            }
            append('}')
        }
    }

    private fun validateCompleteness(input: Input, legacy: Boolean) {
        require(input.partialRollback || input.skippedOperationIds.isEmpty()) {
            "skippedOperationIds require partialRollback=true"
        }
        require(!input.partialRollback || input.skippedOperationIds.isNotEmpty()) {
            "partial rollback artefacts must list skippedOperationIds"
        }
        require(!legacy || (!input.partialRollback && input.skippedOperationIds.isEmpty())) {
            "rollback-sql v1 cannot represent partial rollback artefacts"
        }
        require(input.emitPartialRollbackFields || (!input.partialRollback && input.skippedOperationIds.isEmpty())) {
            "partial rollback fields can only be omitted for complete rollback artefacts"
        }
    }

    private fun statementIndexJson(entries: List<StatementIndexEntry>): String =
        jsonArray(entries.map { statementIndexEntryJson(it) })

    private fun statementIndexEntryJson(e: StatementIndexEntry): String {
        val parts = listOf(
            "dataLossPossible" to e.dataLossPossible.toString(),
            "destructive" to e.destructive.toString(),
            "endExclusive" to e.endExclusive.toString(),
            "index" to e.index.toString(),
            "operationIds" to jsonArray(e.operationIds.map { jsonString(it) }),
            "phase" to jsonString(e.phase),
            "requiresManualConfirmation" to e.requiresManualConfirmation.toString(),
            "sha256" to jsonString(e.sha256),
            "startInclusive" to e.startInclusive.toString(),
            "transactionScope" to jsonString(e.transactionScope),
        )
        return buildString {
            append('{')
            parts.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append(jsonString(k)).append(':').append(v)
            }
            append('}')
        }
    }

    private fun riskJson(r: Risk): String {
        val parts = listOf(
            "dataLossPossible" to r.dataLossPossible.toString(),
            "destructive" to r.destructive.toString(),
            "operationIds" to jsonArray(r.operationIds.sorted().map { jsonString(it) }),
            "requiresManualConfirmation" to r.requiresManualConfirmation.toString(),
        )
        return buildString {
            append('{')
            parts.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append(jsonString(k)).append(':').append(v)
            }
            append('}')
        }
    }

    private fun jsonArray(elements: List<String>): String =
        elements.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private data class CanonicalBody(
        val sqlBody: String,
        val statementIndex: List<StatementIndexEntry>,
    )

    private data class StatementIndexEntry(
        val index: Int,
        val operationIds: List<String>,
        val phase: String,
        val transactionScope: String,
        val destructive: Boolean,
        val dataLossPossible: Boolean,
        val requiresManualConfirmation: Boolean,
        val startInclusive: Int,
        val endExclusive: Int,
        val sha256: String,
    )
}
