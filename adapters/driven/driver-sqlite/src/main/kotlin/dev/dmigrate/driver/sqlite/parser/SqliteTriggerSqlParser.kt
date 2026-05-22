package dev.dmigrate.driver.sqlite.parser

import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

/**
 * Token-based parser for SQLite `CREATE TRIGGER` DDL, as returned by
 * `sqlite_master.sql`.
 *
 * Grammar (simplified from https://sqlite.org/lang_createtrigger.html):
 *
 * ```
 * CREATE [TEMP|TEMPORARY] TRIGGER [IF NOT EXISTS]
 *   [schema-name.] trigger-name
 *   [BEFORE | AFTER | INSTEAD OF]
 *   { DELETE | INSERT | UPDATE [OF column-name [, ...]] }
 *   ON [schema-name.] table-name
 *   [FOR EACH ROW]
 *   [WHEN expr]
 *   BEGIN statement-list END
 * ```
 *
 * Body is preserved verbatim between `BEGIN` and `END` (modulo
 * CRLF -> LF normalisation, outer-whitespace trim, and one optional
 * trailing `;` directly before `END`) so that the round trip
 * `Reverse -> file-write -> Reverse` is idempotent —
 * [dev.dmigrate.driver.sqlite.SqliteDiffSqlBuilders.createTriggerSql]
 * appends `;\nEND;` unconditionally, so the stored body must not
 * carry its own trailing `;`.
 *
 * Replaces [dev.dmigrate.driver.sqlite.SqliteTypeMapping.parseTriggerSql],
 * which used naive substring matching and could mis-classify
 * `INSTEAD OF` triggers (substring collision with `BEFORE`/`AFTER`
 * never bit in practice, but the case-insensitive event keyword test
 * matched ` INSERT ` inside arbitrary table names), drop `WHEN`
 * clauses, and silently lose `forEach` and schema-qualified names.
 *
 * Diagnostics:
 * - `R210` (`ACTION_REQUIRED`): timing keyword missing or unparseable.
 * - `R211` (`ACTION_REQUIRED`): event keyword missing or unparseable.
 * - `R212` (`ACTION_REQUIRED`): schema-qualified name on trigger or
 *   target table — trigger is excluded from the result, no `TriggerDefinition`
 *   is built (see [ParsedTrigger.rejected]).
 * - `R213` (`WARNING`): `UPDATE OF <cols>` column list — the neutral
 *   model treats it as `UPDATE` on the whole row.
 */
internal object SqliteTriggerSqlParser {

    data class ParsedTrigger(
        val timing: TriggerTiming,
        val event: TriggerEvent,
        val forEach: TriggerForEach,
        val condition: String?,
        val body: String?,
        val notes: List<SchemaReadNote>,
        val rejected: Boolean,
    )

    fun parse(sql: String, triggerName: String): ParsedTrigger {
        val normalised = sql.replace("\r\n", "\n")
        val notes = mutableListOf<SchemaReadNote>()
        val (bodyText, headerEnd) = locateBodyAndHeaderEnd(normalised)
        val header = Scanner(normalised.substring(0, headerEnd))

        val preludeError = consumeCreatePrologue(header)
        if (preludeError != null) return malformed(triggerName, preludeError, bodyText, notes)

        val nameIdent = header.readIdentifier()
            ?: return malformed(triggerName, "Missing trigger name in CREATE TRIGGER.", bodyText, notes)
        if (nameIdent.qualified) return rejectQualifiedName(triggerName, nameIdent.fullText, bodyText, notes)

        val timing = parseTiming(header, triggerName, notes)
        val event = parseEvent(header, triggerName, notes)

        if (!header.consumeKeyword("ON")) {
            return missingOnClause(timing, event, triggerName, bodyText, notes)
        }
        val tableIdent = header.readIdentifier()
            ?: return missingTargetTable(timing, event, triggerName, bodyText, notes)
        if (tableIdent.qualified) return rejectQualifiedTable(triggerName, tableIdent.fullText, bodyText, notes)

        consumeForEachRow(header)
        val condition = parseWhenClause(header)

        return ParsedTrigger(timing, event, TriggerForEach.ROW, condition, bodyText, notes, rejected = false)
    }

    /** Locate the trigger body between `BEGIN` and the last `END` token,
     *  and the byte offset where the header (everything before `BEGIN`)
     *  ends. Both string- and comment-aware. */
    private fun locateBodyAndHeaderEnd(normalised: String): Pair<String?, Int> {
        val locator = Scanner(normalised)
        val beginIdx = locator.findKeyword("BEGIN", from = 0)
        if (beginIdx < 0) return null to normalised.length
        val endIdx = locator.findLastKeyword("END", from = beginIdx + BEGIN_LEN)
        val body = if (endIdx > beginIdx) extractBody(normalised, beginIdx + BEGIN_LEN, endIdx) else null
        return body to beginIdx
    }

    /** Consumes `CREATE [TEMP|TEMPORARY] TRIGGER [IF NOT EXISTS]`.
     *  Returns `null` on success or a diagnostic message on failure. */
    private fun consumeCreatePrologue(header: Scanner): String? {
        if (!header.consumeKeyword("CREATE")) return "Missing CREATE keyword."
        header.consumeKeyword("TEMPORARY") || header.consumeKeyword("TEMP")
        if (!header.consumeKeyword("TRIGGER")) return "Missing TRIGGER keyword."
        if (header.consumeKeyword("IF")) {
            header.consumeKeyword("NOT")
            header.consumeKeyword("EXISTS")
        }
        return null
    }

    private fun parseTiming(
        header: Scanner,
        triggerName: String,
        notes: MutableList<SchemaReadNote>,
    ): TriggerTiming = when {
        header.consumeKeyword("INSTEAD") -> {
            header.consumeKeyword("OF")
            TriggerTiming.INSTEAD_OF
        }
        header.consumeKeyword("BEFORE") -> TriggerTiming.BEFORE
        header.consumeKeyword("AFTER") -> TriggerTiming.AFTER
        else -> {
            notes += note(
                SchemaReadSeverity.ACTION_REQUIRED, "R210", triggerName,
                "Trigger timing keyword (BEFORE / AFTER / INSTEAD OF) is missing or " +
                    "unrecognised; defaulting to BEFORE.",
            )
            TriggerTiming.BEFORE
        }
    }

    private fun parseEvent(
        header: Scanner,
        triggerName: String,
        notes: MutableList<SchemaReadNote>,
    ): TriggerEvent = when {
        header.consumeKeyword("DELETE") -> TriggerEvent.DELETE
        header.consumeKeyword("INSERT") -> TriggerEvent.INSERT
        header.consumeKeyword("UPDATE") -> handleUpdateEvent(header, triggerName, notes)
        else -> {
            notes += note(
                SchemaReadSeverity.ACTION_REQUIRED, "R211", triggerName,
                "Trigger event keyword (INSERT / UPDATE / DELETE) is missing or " +
                    "unrecognised; defaulting to INSERT.",
            )
            TriggerEvent.INSERT
        }
    }

    private fun handleUpdateEvent(
        header: Scanner,
        triggerName: String,
        notes: MutableList<SchemaReadNote>,
    ): TriggerEvent {
        if (header.peekKeyword("OF")) {
            header.consumeKeyword("OF")
            header.skipUntilKeyword("ON")
            notes += note(
                SchemaReadSeverity.WARNING, "R213", triggerName,
                "UPDATE OF column list is not represented in the neutral trigger " +
                    "model; trigger reverse-reads as UPDATE on any column.",
            )
        }
        return TriggerEvent.UPDATE
    }

    private fun consumeForEachRow(header: Scanner) {
        if (header.consumeKeyword("FOR")) {
            header.consumeKeyword("EACH")
            header.consumeKeyword("ROW")
        }
    }

    private fun parseWhenClause(header: Scanner): String? =
        if (header.consumeKeyword("WHEN")) {
            header.readRemaining().takeIf { it.isNotBlank() }
        } else null

    private fun missingOnClause(
        timing: TriggerTiming,
        event: TriggerEvent,
        triggerName: String,
        bodyText: String?,
        notes: MutableList<SchemaReadNote>,
    ): ParsedTrigger {
        notes += note(
            SchemaReadSeverity.ACTION_REQUIRED, "R210", triggerName,
            "Missing ON <table> clause in CREATE TRIGGER.",
        )
        return ParsedTrigger(timing, event, TriggerForEach.ROW, null, bodyText, notes, rejected = false)
    }

    private fun missingTargetTable(
        timing: TriggerTiming,
        event: TriggerEvent,
        triggerName: String,
        bodyText: String?,
        notes: MutableList<SchemaReadNote>,
    ): ParsedTrigger {
        notes += note(
            SchemaReadSeverity.ACTION_REQUIRED, "R210", triggerName,
            "Missing target table name after ON in CREATE TRIGGER.",
        )
        return ParsedTrigger(timing, event, TriggerForEach.ROW, null, bodyText, notes, rejected = false)
    }

    private fun rejectQualifiedName(
        triggerName: String,
        qualified: String,
        bodyText: String?,
        notes: MutableList<SchemaReadNote>,
    ): ParsedTrigger {
        notes += note(
            SchemaReadSeverity.ACTION_REQUIRED, "R212", triggerName,
            "Schema-qualified trigger name '$qualified' is not supported; " +
                "trigger is excluded from the reverse-read result.",
        )
        return rejected(bodyText, notes)
    }

    private fun rejectQualifiedTable(
        triggerName: String,
        qualified: String,
        bodyText: String?,
        notes: MutableList<SchemaReadNote>,
    ): ParsedTrigger {
        notes += note(
            SchemaReadSeverity.ACTION_REQUIRED, "R212", triggerName,
            "Schema-qualified target table '$qualified' is not supported; " +
                "trigger is excluded from the reverse-read result.",
        )
        return rejected(bodyText, notes)
    }

    private fun extractBody(sql: String, from: Int, until: Int): String? {
        val raw = sql.substring(from, until)
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withoutTrailingSemi = if (trimmed.endsWith(";")) {
            trimmed.removeSuffix(";").trimEnd()
        } else trimmed
        return withoutTrailingSemi.ifEmpty { null }
    }

    private fun malformed(
        triggerName: String,
        message: String,
        body: String?,
        notes: MutableList<SchemaReadNote>,
    ): ParsedTrigger {
        notes += note(SchemaReadSeverity.ACTION_REQUIRED, "R210", triggerName, message)
        return ParsedTrigger(
            timing = TriggerTiming.BEFORE,
            event = TriggerEvent.INSERT,
            forEach = TriggerForEach.ROW,
            condition = null,
            body = body,
            notes = notes,
            rejected = false,
        )
    }

    private fun rejected(body: String?, notes: List<SchemaReadNote>): ParsedTrigger =
        ParsedTrigger(
            timing = TriggerTiming.BEFORE,
            event = TriggerEvent.INSERT,
            forEach = TriggerForEach.ROW,
            condition = null,
            body = body,
            notes = notes,
            rejected = true,
        )

    private fun note(severity: SchemaReadSeverity, code: String, name: String, message: String) =
        SchemaReadNote(severity = severity, code = code, objectName = name, message = message)

    private const val BEGIN_LEN = 5

    /**
     * Lightweight string/comment-aware SQL scanner. Knows just enough
     * to walk a CREATE TRIGGER header without mistaking keywords inside
     * `'literals'`, `"quoted idents"`, ``backticks``, `[bracketed]`,
     * `-- line comments`, or `/* block comments */`.
     */
    private class Scanner(private val sql: String) {
        private val upper: String = sql.uppercase()
        private var pos: Int = 0

        fun consumeKeyword(keyword: String): Boolean {
            skipWhitespaceAndComments()
            if (!matchesKeywordAt(pos, keyword)) return false
            pos += keyword.length
            return true
        }

        fun peekKeyword(keyword: String): Boolean {
            val saved = pos
            skipWhitespaceAndComments()
            val ok = matchesKeywordAt(pos, keyword)
            pos = saved
            return ok
        }

        fun skipUntilKeyword(keyword: String) {
            while (pos < sql.length) {
                skipWhitespaceAndComments()
                if (pos >= sql.length) return
                if (matchesKeywordAt(pos, keyword)) return
                advanceOneToken()
            }
        }

        fun readIdentifier(): Ident? {
            skipWhitespaceAndComments()
            val first = readSingleIdentifier() ?: return null
            val savedAfterFirst = pos
            skipWhitespaceAndComments()
            if (pos < sql.length && sql[pos] == '.') {
                pos++
                skipWhitespaceAndComments()
                val second = readSingleIdentifier()
                if (second == null) {
                    pos = savedAfterFirst
                    return Ident(qualified = false, fullText = first)
                }
                return Ident(qualified = true, fullText = "$first.$second")
            }
            return Ident(qualified = false, fullText = first)
        }

        fun readRemaining(): String {
            skipWhitespaceAndComments()
            val tail = sql.substring(pos).trim()
            pos = sql.length
            return tail
        }

        fun findKeyword(keyword: String, from: Int): Int {
            val saved = pos
            pos = from
            while (pos < sql.length) {
                skipWhitespaceAndComments()
                if (pos >= sql.length) break
                if (matchesKeywordAt(pos, keyword)) {
                    val found = pos
                    pos = saved
                    return found
                }
                advanceOneToken()
            }
            pos = saved
            return -1
        }

        fun findLastKeyword(keyword: String, from: Int): Int {
            val saved = pos
            pos = from
            var last = -1
            while (pos < sql.length) {
                skipWhitespaceAndComments()
                if (pos >= sql.length) break
                if (matchesKeywordAt(pos, keyword)) {
                    last = pos
                    pos += keyword.length
                } else {
                    advanceOneToken()
                }
            }
            pos = saved
            return last
        }

        private fun matchesKeywordAt(at: Int, keyword: String): Boolean {
            val end = at + keyword.length
            if (end > sql.length) return false
            for (i in keyword.indices) {
                if (upper[at + i] != keyword[i].uppercaseChar()) return false
            }
            if (end < sql.length) {
                val next = sql[end]
                if (next.isLetterOrDigit() || next == '_') return false
            }
            if (at > 0) {
                val prev = sql[at - 1]
                if (prev.isLetterOrDigit() || prev == '_') return false
            }
            return true
        }

        private fun skipWhitespaceAndComments() {
            while (pos < sql.length) {
                val c = sql[pos]
                when {
                    c.isWhitespace() -> pos++
                    c == '-' && pos + 1 < sql.length && sql[pos + 1] == '-' -> skipLineComment()
                    c == '/' && pos + 1 < sql.length && sql[pos + 1] == '*' -> skipBlockComment()
                    else -> return
                }
            }
        }

        private fun skipLineComment() {
            pos += 2
            while (pos < sql.length && sql[pos] != '\n') pos++
        }

        private fun skipBlockComment() {
            pos += 2
            while (pos + 1 < sql.length && !(sql[pos] == '*' && sql[pos + 1] == '/')) pos++
            pos = if (pos + 1 < sql.length) pos + 2 else sql.length
        }

        private fun advanceOneToken() {
            when (val c = sql[pos]) {
                '\'' -> skipSingleQuoted()
                '"' -> skipDelimited('"', '"')
                '`' -> skipDelimited('`', '`')
                '[' -> skipDelimited('[', ']')
                else -> {
                    if (c.isLetter() || c == '_') {
                        while (pos < sql.length && (sql[pos].isLetterOrDigit() || sql[pos] == '_')) pos++
                    } else {
                        pos++
                    }
                }
            }
        }

        private fun skipSingleQuoted() {
            pos++
            while (pos < sql.length) {
                if (sql[pos] == '\'') {
                    if (pos + 1 < sql.length && sql[pos + 1] == '\'') {
                        pos += 2
                        continue
                    }
                    pos++
                    return
                }
                pos++
            }
        }

        private fun skipDelimited(open: Char, close: Char) {
            pos++
            while (pos < sql.length) {
                if (sql[pos] == close) {
                    if (open == close && pos + 1 < sql.length && sql[pos + 1] == close) {
                        pos += 2
                        continue
                    }
                    pos++
                    return
                }
                pos++
            }
        }

        private fun readSingleIdentifier(): String? {
            if (pos >= sql.length) return null
            return when (sql[pos]) {
                '"' -> readDelimitedIdentifier('"', '"')
                '`' -> readDelimitedIdentifier('`', '`')
                '[' -> readDelimitedIdentifier('[', ']')
                else -> readBareIdentifier()
            }
        }

        private fun readDelimitedIdentifier(open: Char, close: Char): String? {
            if (sql[pos] != open) return null
            pos++
            val start = pos
            while (pos < sql.length && sql[pos] != close) pos++
            val text = sql.substring(start, pos)
            if (pos < sql.length) pos++
            return text
        }

        private fun readBareIdentifier(): String? {
            val start = pos
            while (pos < sql.length) {
                val c = sql[pos]
                if (c.isLetterOrDigit() || c == '_') pos++ else break
            }
            return if (pos > start) sql.substring(start, pos) else null
        }
    }

    private data class Ident(val qualified: Boolean, val fullText: String)
}
