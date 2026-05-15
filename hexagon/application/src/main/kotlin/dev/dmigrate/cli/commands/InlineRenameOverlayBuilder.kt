package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry

/**
 * F.4 cli-inline-overlay slice §3.2: builds a synthetic
 * [MigrationOverlayDocument] from operator-supplied
 * `--rename-table` / `--rename-column` CLI flags. The resulting
 * document is wired through the same validator/preflight/mapper
 * pipeline as a file-loaded overlay; the only operational
 * difference is the stable `source = "cli-inline"` and the
 * sentinel `createdAt = "cli-inline"` so two CLI invocations with
 * the same flags produce a bit-identical overlay hash regardless of
 * wall-clock.
 *
 * Parse rules (§3.2 + Akzeptanzkriterien):
 *
 * - `<from>:<to>` for table renames, `<table>.<from>:<table>.<to>`
 *   for column renames, exactly one `:` as separator, whitespace
 *   trimmed.
 * - No SQL identifier quoting (`"`, `` ` ``, `[`, `]`) in the
 *   shortcut — the operator types raw names. The validator stays
 *   responsible for the overlay contract; dialect quoting is the
 *   renderer's job.
 * - Both sides of a column rename must use the same `<table>`
 *   prefix; otherwise [InlineRenameOverlayResult.ParseFailed].
 * - Duplicate `from` entries within the same invocation produce
 *   [InlineRenameOverlayResult.ParseFailed] — the runner converts
 *   that into Exit 2. Cross-document duplicates (e.g. file overlay
 *   plus inline) are caught later by the pre-plan gate as Exit 8.
 */
internal object InlineRenameOverlayBuilder {

    const val INLINE_SOURCE: String = "cli-inline"
    const val INLINE_CREATED_AT_SENTINEL: String = "cli-inline"

    fun build(
        renameTableFlags: List<String>,
        renameColumnFlags: List<String>,
        sourceFingerprint: String,
        targetFingerprint: String,
        dialect: String,
        version: String,
    ): InlineRenameOverlayResult {
        if (renameTableFlags.isEmpty() && renameColumnFlags.isEmpty()) {
            return InlineRenameOverlayResult.Empty
        }

        val errors = mutableListOf<String>()
        val entries = mutableListOf<RenameMappingOverlayEntry>()

        renameTableFlags.forEachIndexed { index, raw ->
            when (val parsed = parseTableFlag(raw)) {
                is ParsedRename.Ok -> entries += RenameMappingOverlayEntry(
                    id = "rename-table-$index",
                    objectType = "table",
                    fromName = parsed.from,
                    toName = parsed.to,
                )
                is ParsedRename.Err -> errors += "--rename-table[$index]: ${parsed.message}"
            }
        }

        renameColumnFlags.forEachIndexed { index, raw ->
            when (val parsed = parseColumnFlag(raw)) {
                is ParsedRename.Ok -> entries += RenameMappingOverlayEntry(
                    id = "rename-column-$index",
                    objectType = "column",
                    fromName = parsed.from,
                    toName = parsed.to,
                )
                is ParsedRename.Err -> errors += "--rename-column[$index]: ${parsed.message}"
            }
        }

        if (errors.isNotEmpty()) {
            return InlineRenameOverlayResult.ParseFailed(errors)
        }

        val duplicateFroms = entries.groupBy { it.objectType to it.fromName }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateFroms.isNotEmpty()) {
            val rendered = duplicateFroms.joinToString(", ") { (kind, from) -> "$kind:$from" }
            return InlineRenameOverlayResult.ParseFailed(
                listOf("Duplicate inline rename source(s) within the same invocation: $rendered"),
            )
        }

        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = sourceFingerprint,
            targetFingerprint = targetFingerprint,
            dialect = dialect,
            entries = entries,
            createdAt = INLINE_CREATED_AT_SENTINEL,
            createdByVersion = "d-migrate ($version) cli-inline",
        ).withComputedHash()

        return InlineRenameOverlayResult.Built(
            MigrationOverlayDocument(source = INLINE_SOURCE, overlay = overlay),
        )
    }

    private fun parseTableFlag(raw: String): ParsedRename {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParsedRename.Err("flag value must not be blank")
        val separatorIndex = trimmed.indexOf(':')
        if (separatorIndex < 0 || trimmed.indexOf(':', startIndex = separatorIndex + 1) >= 0) {
            return ParsedRename.Err("expected `<from>:<to>`, got '$trimmed'")
        }
        val from = trimmed.substring(0, separatorIndex).trim()
        val to = trimmed.substring(separatorIndex + 1).trim()
        if (from.isEmpty() || to.isEmpty()) {
            return ParsedRename.Err("from/to must be non-blank in '$trimmed'")
        }
        rejectQuotingChars(from)?.let { return ParsedRename.Err("from: $it") }
        rejectQuotingChars(to)?.let { return ParsedRename.Err("to: $it") }
        return ParsedRename.Ok(from = from, to = to)
    }

    private fun parseColumnFlag(raw: String): ParsedRename {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParsedRename.Err("flag value must not be blank")
        val separatorIndex = trimmed.indexOf(':')
        if (separatorIndex < 0 || trimmed.indexOf(':', startIndex = separatorIndex + 1) >= 0) {
            return ParsedRename.Err("expected `<table>.<from>:<table>.<to>`, got '$trimmed'")
        }
        val leftRaw = trimmed.substring(0, separatorIndex).trim()
        val rightRaw = trimmed.substring(separatorIndex + 1).trim()
        val (leftTable, leftColumn) = splitTableColumn(leftRaw)
            ?: return ParsedRename.Err("left side must be `<table>.<column>`, got '$leftRaw'")
        val (rightTable, rightColumn) = splitTableColumn(rightRaw)
            ?: return ParsedRename.Err("right side must be `<table>.<column>`, got '$rightRaw'")
        if (leftTable != rightTable) {
            return ParsedRename.Err(
                "table prefix must be identical on both sides ('$leftTable' vs '$rightTable'); " +
                    "use the file-overlay path for cross-table renames.",
            )
        }
        rejectQuotingChars(leftTable)?.let { return ParsedRename.Err("table: $it") }
        rejectQuotingChars(leftColumn)?.let { return ParsedRename.Err("from column: $it") }
        rejectQuotingChars(rightColumn)?.let { return ParsedRename.Err("to column: $it") }
        return ParsedRename.Ok(
            from = "$leftTable.$leftColumn",
            to = "$rightTable.$rightColumn",
        )
    }

    private fun splitTableColumn(raw: String): Pair<String, String>? {
        val dotIndex = raw.indexOf('.')
        if (dotIndex <= 0 || dotIndex == raw.length - 1) return null
        val table = raw.substring(0, dotIndex).trim()
        val column = raw.substring(dotIndex + 1).trim()
        if (table.isEmpty() || column.isEmpty()) return null
        // Reject multi-segment dotted paths — the CLI shortcut is
        // deliberately narrow; use the file-overlay path for
        // schema-qualified renames.
        if (column.contains('.')) return null
        return table to column
    }

    private fun rejectQuotingChars(value: String): String? {
        val forbidden = QUOTING_CHARS.firstOrNull { value.contains(it) }
        return forbidden?.let {
            "SQL identifier quoting char '$it' is not allowed in the CLI rename shortcut; use the file-overlay path instead."
        }
    }

    private val QUOTING_CHARS: List<Char> = listOf('"', '`', '[', ']')

    private sealed interface ParsedRename {
        data class Ok(val from: String, val to: String) : ParsedRename
        data class Err(val message: String) : ParsedRename
    }
}

internal sealed interface InlineRenameOverlayResult {
    data class Built(val document: MigrationOverlayDocument) : InlineRenameOverlayResult
    data class ParseFailed(val errors: List<String>) : InlineRenameOverlayResult
    data object Empty : InlineRenameOverlayResult
}
