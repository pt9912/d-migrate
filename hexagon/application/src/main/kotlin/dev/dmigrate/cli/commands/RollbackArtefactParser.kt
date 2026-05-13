package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionScope

/**
 * Strict parser for the `d-migrate rollback-sql` artefact per
 * `docs/planning/done/diffresult-migration-plan.md §7.3`.
 *
 * The input must contain exactly one delimited block:
 *
 * ```
 * -- d-migrate rollback-sql v2 begin
 * -- {"format":"d-migrate rollback-sql",…}
 * -- d-migrate rollback-sql v2 end
 * <SQL body, LF-only, single trailing newline>
 * ```
 *
 * On success, the parser:
 *
 * 1. extracts the canonical metadata header,
 * 2. parses it into a [ParsedRollbackArtefact],
 * 3. recomputes the `artifactHash` over header (without the hash
 *    field) plus the SQL body and compares it against the parsed
 *    value.
 *
 * Any deviation (missing delimiters, multiple blocks, malformed
 * JSON, missing required fields, hash mismatch, unknown
 * `formatVersion`/`artifactHashAlgorithm`) results in a
 * [Result.Failure] with a structured [Result.Failure.code]. Callers
 * (notably [SchemaRollbackRunner]) translate failures to Exit 7
 * (Artefakt ungueltig) before any DB access.
 */
internal object RollbackArtefactParser {

    private val DELIMITERS = listOf(
        Delimiters(
            formatVersion = RollbackArtefactBuilder.FORMAT_VERSION,
            artifactHashAlgorithm = RollbackArtefactBuilder.ARTIFACT_HASH_ALGORITHM,
            begin = RollbackArtefactBuilder.BEGIN_DELIMITER,
            end = RollbackArtefactBuilder.END_DELIMITER,
        ),
        Delimiters(
            formatVersion = RollbackArtefactBuilder.FORMAT_VERSION_V1,
            artifactHashAlgorithm = RollbackArtefactBuilder.ARTIFACT_HASH_ALGORITHM_V1,
            begin = RollbackArtefactBuilder.BEGIN_DELIMITER_V1,
            end = RollbackArtefactBuilder.END_DELIMITER_V1,
        ),
    )

    sealed interface Result {
        data class Success(val parsed: ParsedRollbackArtefact) : Result
        data class Failure(val code: String, val message: String) : Result
    }

    fun parse(text: String): Result {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val matched = DELIMITERS.mapNotNull { delimiters ->
            val idx = normalized.indexOf(delimiters.begin)
            if (idx < 0) null else FoundDelimiters(delimiters, idx)
        }.minByOrNull { it.beginIdx }
            ?: return Result.Failure("MISSING_BEGIN_DELIMITER", "no rollback-sql begin delimiter found")
        val delimiters = matched.delimiters
        val beginIdx = matched.beginIdx
        val endIdx = normalized.indexOf(delimiters.end, startIndex = beginIdx + delimiters.begin.length)
        if (endIdx < 0) return Result.Failure("MISSING_END_DELIMITER", "no `${delimiters.end}` after begin")

        // Reject multiple metadata blocks (Plan §7.3 strictness).
        val secondBeginIdx = DELIMITERS
            .map { normalized.indexOf(it.begin, startIndex = endIdx + delimiters.end.length) }
            .filter { it >= 0 }
            .minOrNull()
        if (secondBeginIdx != null) {
            return Result.Failure("MULTIPLE_METADATA_BLOCKS", "more than one rollback-sql begin delimiter found")
        }

        // Extract the single comment line between the delimiters.
        val between = normalized.substring(beginIdx + delimiters.begin.length, endIdx).trim('\n', ' ')
        val lines = between.lines().filter { it.isNotBlank() }
        if (lines.size != 1) {
            return Result.Failure(
                "MALFORMED_HEADER_BLOCK",
                "expected exactly one metadata line between delimiters, got ${lines.size}",
            )
        }
        val header = lines[0].trim()
        if (!header.startsWith("-- ")) {
            return Result.Failure("MALFORMED_HEADER_PREFIX", "metadata line must start with `-- `")
        }
        val json = header.substring(3).trim()

        // Body = everything after the END_DELIMITER + following newline.
        val afterEnd = endIdx + delimiters.end.length
        val bodyStart = if (afterEnd < normalized.length && normalized[afterEnd] == '\n') afterEnd + 1 else afterEnd
        val sqlBody = if (bodyStart >= normalized.length) "" else normalized.substring(bodyStart)

        return try {
            val obj = MiniJson.parseObject(json)
            val parsed = decode(obj, sqlBody, delimiters.formatVersion)
            verifyHash(parsed, sqlBody)?.let { return it }
            Result.Success(parsed)
        } catch (e: ParseException) {
            Result.Failure(e.code, e.message ?: "parse error")
        }
    }

    private fun decode(
        obj: Map<String, Any?>,
        sqlBody: String,
        delimiterFormatVersion: String,
    ): ParsedRollbackArtefact {
        val format = obj.requireString("format")
        if (format != RollbackArtefactBuilder.FORMAT) {
            throw ParseException("UNKNOWN_FORMAT", "unexpected format `$format`")
        }
        val formatVersion = obj.requireString("formatVersion")
        if (formatVersion != delimiterFormatVersion) {
            throw ParseException(
                "FORMAT_VERSION_DELIMITER_MISMATCH",
                "formatVersion `$formatVersion` does not match rollback delimiter `$delimiterFormatVersion`",
            )
        }
        val supported = DELIMITERS.firstOrNull { it.formatVersion == formatVersion }
        if (supported == null) {
            throw ParseException("UNKNOWN_FORMAT_VERSION", "unsupported formatVersion `$formatVersion`")
        }
        val artifactHashAlgo = obj.requireString("artifactHashAlgorithm")
        if (artifactHashAlgo != supported.artifactHashAlgorithm) {
            throw ParseException(
                "UNKNOWN_ARTIFACT_HASH_ALGORITHM",
                "unsupported artifactHashAlgorithm `$artifactHashAlgo`",
            )
        }
        val recovery = obj.requireBool("recovery")
        val allowed = obj["allowedPostUpFingerprints"]
        if (recovery) {
            if (allowed !is List<*> || allowed.isEmpty()) {
                throw ParseException(
                    "MISSING_ALLOWED_POST_UP_FINGERPRINTS",
                    "recovery=true requires a non-empty allowedPostUpFingerprints list",
                )
            }
        }
        val partialContract = decodePartialRollback(formatVersion, obj)
        @Suppress("UNCHECKED_CAST")
        return ParsedRollbackArtefact(
            format = format,
            formatVersion = formatVersion,
            dialect = obj.requireString("dialect"),
            currentFingerprint = obj.requireString("currentFingerprint"),
            desiredFingerprint = obj.requireString("desiredFingerprint"),
            postUpFingerprint = obj.requireString("postUpFingerprint"),
            operationIds = obj.requireStringList("operationIds"),
            risk = decodeRisk(obj["risk"]),
            createdByVersion = obj.requireString("createdByVersion"),
            fingerprintAlgorithm = obj.requireString("fingerprintAlgorithm"),
            artifactHashAlgorithm = artifactHashAlgo,
            artifactHash = obj.requireString("artifactHash"),
            recovery = recovery,
            postUpVerified = obj.requireBool("postUpVerified"),
            allowedPostUpFingerprints = (allowed as? List<String>),
            rollbackComplete = partialContract.rollbackComplete,
            partialRollback = partialContract.partialRollback,
            skippedOperationIds = partialContract.skippedOperationIds,
            partialRollbackContractPresent = partialContract.present,
            statementIndex = decodeStatementIndex(formatVersion, obj["statementIndex"], sqlBody),
            sqlBody = sqlBody,
        )
    }

    private fun decodePartialRollback(
        formatVersion: String,
        obj: Map<String, Any?>,
    ): PartialRollbackContract {
        val hasPartialContract = PARTIAL_ROLLBACK_FIELDS.any { it in obj }
        if (formatVersion == RollbackArtefactBuilder.FORMAT_VERSION_V1 || !hasPartialContract) {
            return PartialRollbackContract(
                rollbackComplete = true,
                partialRollback = false,
                skippedOperationIds = emptyList(),
                present = false,
            )
        }
        val partialRollback = obj.requireBool("partialRollback")
        val rollbackComplete = obj.requireBool("rollbackComplete")
        val skippedOperationIds = obj.requireStringList("skippedOperationIds")
        if (partialRollback && rollbackComplete) {
            throw ParseException(
                "PARTIAL_ROLLBACK_MARKED_COMPLETE",
                "partialRollback=true requires rollbackComplete=false",
            )
        }
        if (partialRollback && skippedOperationIds.isEmpty()) {
            throw ParseException(
                "PARTIAL_ROLLBACK_SKIPPED_OPERATIONS_MISSING",
                "partial rollback artefacts must list skippedOperationIds",
            )
        }
        if (!partialRollback && !rollbackComplete) {
            throw ParseException(
                "ROLLBACK_COMPLETENESS_MISMATCH",
                "rollbackComplete=false requires partialRollback=true",
            )
        }
        if (!partialRollback && skippedOperationIds.isNotEmpty()) {
            throw ParseException(
                "SKIPPED_OPERATIONS_WITH_COMPLETE_ROLLBACK",
                "skippedOperationIds require partialRollback=true",
            )
        }
        return PartialRollbackContract(
            rollbackComplete = rollbackComplete,
            partialRollback = partialRollback,
            skippedOperationIds = skippedOperationIds,
            present = true,
        )
    }

    private fun decodeStatementIndex(
        formatVersion: String,
        node: Any?,
        sqlBody: String,
    ): List<ParsedStatementIndexEntry> {
        if (formatVersion == RollbackArtefactBuilder.FORMAT_VERSION_V1) return emptyList()
        if (node !is List<*>) {
            throw ParseException("MISSING_FIELD_STATEMENTINDEX", "v2 requires statementIndex")
        }
        val entries = node.mapIndexed { expectedIndex, raw ->
            if (raw !is Map<*, *>) throw ParseException("MALFORMED_STATEMENTINDEX", "statementIndex entries must be objects")
            @Suppress("UNCHECKED_CAST")
            val m = raw as Map<String, Any?>
            val entry = ParsedStatementIndexEntry(
                index = m.requireInt("index"),
                operationIds = m.requireStringList("operationIds"),
                phase = m.requireString("phase"),
                transactionScope = m.requireString("transactionScope"),
                destructive = m.requireBool("destructive"),
                dataLossPossible = m.requireBool("dataLossPossible"),
                requiresManualConfirmation = m.requireBool("requiresManualConfirmation"),
                startInclusive = m.requireInt("startInclusive"),
                endExclusive = m.requireInt("endExclusive"),
                sha256 = m.requireString("sha256"),
            )
            if (entry.index != expectedIndex) {
                throw ParseException("STATEMENT_INDEX_ORDER_MISMATCH", "statementIndex entry ${entry.index} is out of order")
            }
            entry
        }
        validateStatementRanges(entries, sqlBody)
        return entries
    }

    private fun validateStatementRanges(entries: List<ParsedStatementIndexEntry>, sqlBody: String) {
        val bodyBytes = sqlBody.toByteArray(Charsets.UTF_8)
        var previousEnd = 0
        entries.forEachIndexed { ordinal, entry ->
            if (entry.startInclusive < previousEnd || entry.endExclusive < entry.startInclusive) {
                throw ParseException("STATEMENT_RANGE_INVALID", "invalid statement range at index ${entry.index}")
            }
            if (entry.endExclusive > bodyBytes.size) {
                throw ParseException("STATEMENT_RANGE_OUT_OF_BOUNDS", "statement range exceeds SQL body")
            }
            if (ordinal > 0 && entry.startInclusive != previousEnd + 2) {
                throw ParseException("STATEMENT_RANGE_GAP", "statement ranges must be separated by exactly one blank line")
            }
            val slice = bodyBytes.copyOfRange(entry.startInclusive, entry.endExclusive).toString(Charsets.UTF_8)
            if (sha256Hex(slice) != entry.sha256) {
                throw ParseException("STATEMENT_HASH_MISMATCH", "statement hash mismatch at index ${entry.index}")
            }
            previousEnd = entry.endExclusive
        }
    }

    private fun decodeRisk(node: Any?): ParsedRisk {
        if (node !is Map<*, *>) throw ParseException("MALFORMED_RISK", "risk must be an object")
        @Suppress("UNCHECKED_CAST")
        val m = node as Map<String, Any?>
        return ParsedRisk(
            destructive = m.requireBool("destructive"),
            dataLossPossible = m.requireBool("dataLossPossible"),
            requiresManualConfirmation = m.requireBool("requiresManualConfirmation"),
            operationIds = m.requireStringList("operationIds"),
        )
    }

    private fun verifyHash(parsed: ParsedRollbackArtefact, sqlBody: String): Result.Failure? {
        val statements = if (parsed.formatVersion == RollbackArtefactBuilder.FORMAT_VERSION) {
            parsed.statementsFromIndex()
        } else {
            listOf(MigrationDdlStatement(
                sql = sqlBody.trimEnd('\n'),
                operationIds = parsed.operationIds.toSet(),
                risk = OperationRisk.SAFE,
                phase = DiffPhase.TABLES,
            ))
        }
        val input = RollbackArtefactBuilder.Input(
            dialect = DatabaseDialect.valueOf(parsed.dialect),
            currentFingerprint = parsed.currentFingerprint,
            desiredFingerprint = parsed.desiredFingerprint,
            postUpFingerprint = parsed.postUpFingerprint,
            operationIds = parsed.operationIds.toSet(),
            risk = RollbackArtefactBuilder.Risk(
                destructive = parsed.risk.destructive,
                dataLossPossible = parsed.risk.dataLossPossible,
                requiresManualConfirmation = parsed.risk.requiresManualConfirmation,
                operationIds = parsed.risk.operationIds.toSet(),
            ),
            downStatements = statements,
            createdByVersion = parsed.createdByVersion,
            recovery = parsed.recovery,
            postUpVerified = parsed.postUpVerified,
            allowedPostUpFingerprints = parsed.allowedPostUpFingerprints,
            partialRollback = parsed.partialRollback,
            skippedOperationIds = parsed.skippedOperationIds.toSet(),
            emitPartialRollbackFields = parsed.partialRollbackContractPresent,
        )
        val rebuilt = if (parsed.formatVersion == RollbackArtefactBuilder.FORMAT_VERSION) {
            RollbackArtefactBuilder.build(input)
        } else {
            RollbackArtefactBuilder.buildLegacyV1(input)
        }
        val rebuiltHash = Regex("\"artifactHash\":\"([a-f0-9]+)\"").find(rebuilt)?.groupValues?.get(1)
        if (rebuiltHash != parsed.artifactHash) {
            return Result.Failure(
                "ARTIFACT_HASH_MISMATCH",
                "recomputed hash $rebuiltHash does not match parsed ${parsed.artifactHash}",
            )
        }
        return null
    }

    // ── Mini JSON parser (canonical-output-shaped only) ─────────────

    private class ParseException(val code: String, message: String) : RuntimeException(message)

    private fun Map<String, Any?>.requireString(key: String): String {
        val v = this[key] ?: throw ParseException("MISSING_FIELD_$key".uppercase(), "missing required field `$key`")
        if (v !is String) throw ParseException("TYPE_MISMATCH_$key".uppercase(), "field `$key` must be a string")
        return v
    }

    private fun Map<String, Any?>.requireBool(key: String): Boolean {
        val v = this[key] ?: throw ParseException("MISSING_FIELD_$key".uppercase(), "missing required field `$key`")
        if (v !is Boolean) throw ParseException("TYPE_MISMATCH_$key".uppercase(), "field `$key` must be a boolean")
        return v
    }

    private fun Map<String, Any?>.requireStringList(key: String): List<String> {
        val v = this[key] ?: throw ParseException("MISSING_FIELD_$key".uppercase(), "missing required field `$key`")
        if (v !is List<*>) throw ParseException("TYPE_MISMATCH_$key".uppercase(), "field `$key` must be an array")
        return v.map {
            it as? String ?: throw ParseException("TYPE_MISMATCH_$key".uppercase(), "field `$key` must be array of strings")
        }
    }

    private fun Map<String, Any?>.requireInt(key: String): Int {
        val v = this[key] ?: throw ParseException("MISSING_FIELD_$key".uppercase(), "missing required field `$key`")
        val n = when (v) {
            is Long -> v
            is Int -> v.toLong()
            else -> throw ParseException("TYPE_MISMATCH_$key".uppercase(), "field `$key` must be an integer")
        }
        if (n !in Int.MIN_VALUE..Int.MAX_VALUE) {
            throw ParseException("TYPE_MISMATCH_$key".uppercase(), "field `$key` is out of integer range")
        }
        return n.toInt()
    }

    private object MiniJson {
        fun parseObject(text: String): Map<String, Any?> {
            val state = ParseState(text, 0)
            state.skipWs()
            val obj = parseValue(state)
            state.skipWs()
            if (state.pos != text.length) throw ParseException("TRAILING_TOKENS", "unexpected content after JSON value")
            if (obj !is Map<*, *>) throw ParseException("NOT_AN_OBJECT", "top-level JSON must be an object")
            @Suppress("UNCHECKED_CAST")
            return obj as Map<String, Any?>
        }

        private class ParseState(val text: String, var pos: Int) {
            fun peek(): Char = text[pos]
            fun take(): Char = text[pos++]
            fun skipWs() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
            fun expect(c: Char) {
                if (pos >= text.length || text[pos] != c) throw ParseException("EXPECTED_CHAR_$c", "expected `$c` at pos $pos")
                pos++
            }
        }

        private fun parseValue(s: ParseState): Any? {
            s.skipWs()
            return when (s.peek()) {
                '{' -> parseObjectInternal(s)
                '[' -> parseArray(s)
                '"' -> parseString(s)
                't', 'f' -> parseBool(s)
                'n' -> parseNull(s)
                else -> parseNumber(s)
            }
        }

        private fun parseObjectInternal(s: ParseState): Map<String, Any?> {
            s.expect('{')
            val out = linkedMapOf<String, Any?>()
            s.skipWs()
            if (s.peek() == '}') { s.take(); return out }
            while (true) {
                s.skipWs()
                val key = parseString(s)
                s.skipWs(); s.expect(':')
                val value = parseValue(s)
                out[key] = value
                s.skipWs()
                when (s.peek()) {
                    ',' -> { s.take(); continue }
                    '}' -> { s.take(); return out }
                    else -> throw ParseException("EXPECTED_COMMA_OR_CLOSE", "expected `,` or `}` at pos ${s.pos}")
                }
            }
        }

        private fun parseArray(s: ParseState): List<Any?> {
            s.expect('[')
            val out = mutableListOf<Any?>()
            s.skipWs()
            if (s.peek() == ']') { s.take(); return out }
            while (true) {
                out += parseValue(s)
                s.skipWs()
                when (s.peek()) {
                    ',' -> { s.take(); continue }
                    ']' -> { s.take(); return out }
                    else -> throw ParseException("EXPECTED_COMMA_OR_CLOSE", "expected `,` or `]` at pos ${s.pos}")
                }
            }
        }

        @Suppress("ComplexMethod")
        private fun parseString(s: ParseState): String {
            s.expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = s.take()
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    when (val esc = s.take()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '/' -> sb.append('/')
                        'u' -> {
                            val hex = s.text.substring(s.pos, s.pos + 4)
                            s.pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw ParseException("INVALID_ESCAPE", "unknown escape `\\$esc`")
                    }
                } else {
                    sb.append(c)
                }
            }
        }

        private fun parseBool(s: ParseState): Boolean {
            return if (s.text.startsWith("true", s.pos)) { s.pos += 4; true }
            else if (s.text.startsWith("false", s.pos)) { s.pos += 5; false }
            else throw ParseException("INVALID_BOOL", "expected true/false at pos ${s.pos}")
        }

        private fun parseNull(s: ParseState): Any? {
            if (s.text.startsWith("null", s.pos)) { s.pos += 4; return null }
            throw ParseException("INVALID_NULL", "expected null at pos ${s.pos}")
        }

        private fun parseNumber(s: ParseState): Number {
            val start = s.pos
            while (s.pos < s.text.length && (s.text[s.pos].isDigit() || s.text[s.pos] in "-+.eE")) s.pos++
            val token = s.text.substring(start, s.pos)
            return token.toLongOrNull() ?: token.toDouble()
        }
    }

    private data class Delimiters(
        val formatVersion: String,
        val artifactHashAlgorithm: String,
        val begin: String,
        val end: String,
    )

    private data class FoundDelimiters(
        val delimiters: Delimiters,
        val beginIdx: Int,
    )

    private data class PartialRollbackContract(
        val rollbackComplete: Boolean,
        val partialRollback: Boolean,
        val skippedOperationIds: List<String>,
        val present: Boolean,
    )

    private val PARTIAL_ROLLBACK_FIELDS: Set<String> = setOf(
        "partialRollback",
        "rollbackComplete",
        "skippedOperationIds",
    )
}

// ── DTOs ────────────────────────────────────────────────────────────

internal data class ParsedRollbackArtefact(
    val format: String,
    val formatVersion: String,
    val dialect: String,
    val currentFingerprint: String,
    val desiredFingerprint: String,
    val postUpFingerprint: String,
    val operationIds: List<String>,
    val risk: ParsedRisk,
    val createdByVersion: String,
    val fingerprintAlgorithm: String,
    val artifactHashAlgorithm: String,
    val artifactHash: String,
    val recovery: Boolean,
    val postUpVerified: Boolean,
    val allowedPostUpFingerprints: List<String>?,
    val rollbackComplete: Boolean,
    val partialRollback: Boolean,
    val skippedOperationIds: List<String>,
    val partialRollbackContractPresent: Boolean,
    val statementIndex: List<ParsedStatementIndexEntry>,
    val sqlBody: String,
)

internal data class ParsedRisk(
    val destructive: Boolean,
    val dataLossPossible: Boolean,
    val requiresManualConfirmation: Boolean,
    val operationIds: List<String>,
)

internal data class ParsedStatementIndexEntry(
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

internal fun ParsedRollbackArtefact.statementsFromIndex(): List<MigrationDdlStatement> {
    val bodyBytes = sqlBody.toByteArray(Charsets.UTF_8)
    return statementIndex.map { entry ->
        val sql = bodyBytes.copyOfRange(entry.startInclusive, entry.endExclusive).toString(Charsets.UTF_8)
        MigrationDdlStatement(
            sql = sql,
            operationIds = entry.operationIds.toSet(),
            risk = OperationRisk(
                destructive = entry.destructive,
                dataLossPossible = entry.dataLossPossible,
                requiresManualConfirmation = entry.requiresManualConfirmation,
            ),
            phase = DiffPhase.valueOf(entry.phase),
            transactionScope = TransactionScope.valueOf(entry.transactionScope),
        )
    }
}
