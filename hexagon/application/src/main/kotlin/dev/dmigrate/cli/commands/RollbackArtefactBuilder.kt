package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement

/**
 * Builds the `d-migrate rollback-sql v1` Down-SQL artefact per
 * `docs/planning/in-progress/diffresult-migration-plan.md §7.3` and
 * `spec/cli-spec.md §6.1`.
 *
 * Output shape:
 *
 * ```
 * -- d-migrate rollback-sql v1 begin
 * -- {"artifactHash":"…","artifactHashAlgorithm":"sha256-rollback-artifact-v1",…}
 * -- d-migrate rollback-sql v1 end
 * <down SQL body>
 * ```
 *
 * The header JSON is canonical: keys sorted lexicographically, no
 * whitespace outside string values. The `artifactHash` binds the
 * header (minus the hash field itself) to the SQL body so manual
 * tampering with risk / fingerprints / dialect / SQL all break the
 * hash.
 */
internal object RollbackArtefactBuilder {

    const val FORMAT: String = "d-migrate rollback-sql"
    const val FORMAT_VERSION: String = "v1"
    const val FINGERPRINT_ALGORITHM: String = MigrationFingerprint.ALGORITHM
    const val ARTIFACT_HASH_ALGORITHM: String = "sha256-rollback-artifact-v1"

    private const val BEGIN_DELIMITER = "-- d-migrate rollback-sql v1 begin"
    private const val END_DELIMITER = "-- d-migrate rollback-sql v1 end"

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
    )

    fun build(input: Input): String {
        val sqlBody = canonicalBody(input.downStatements)
        val headerWithoutHash = headerJson(input, artifactHash = null)
        val artifactHash = sha256Hex(headerWithoutHash + sqlBody)
        val headerWithHash = headerJson(input, artifactHash = artifactHash)
        return buildString {
            append(BEGIN_DELIMITER).append('\n')
            append("-- ").append(headerWithHash).append('\n')
            append(END_DELIMITER).append('\n')
            append(sqlBody)
        }
    }

    /**
     * Canonical SQL body: LF-only line endings, exactly one trailing newline.
     * Empty bodies produce a single newline so the canonical form is total.
     */
    private fun canonicalBody(stmts: List<MigrationDdlStatement>): String {
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
    private fun headerJson(input: Input, artifactHash: String?): String {
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
        fields += "fingerprintAlgorithm" to jsonString(FINGERPRINT_ALGORITHM)
        fields += "format" to jsonString(FORMAT)
        fields += "formatVersion" to jsonString(FORMAT_VERSION)
        fields += "operationIds" to jsonArray(input.operationIds.sorted().map { jsonString(it) })
        fields += "postUpFingerprint" to jsonString(input.postUpFingerprint)
        fields += "postUpVerified" to input.postUpVerified.toString()
        fields += "recovery" to input.recovery.toString()
        fields += "risk" to riskJson(input.risk)
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
}
