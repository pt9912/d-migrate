package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.ManualActionRequired
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.sqliteContext

/**
 * 0.9.7 SQLite-Sequence Phase B.3: stateful coordinator wired into
 * [SqliteDdlGenerator] that turns named sequences and
 * [DefaultValue.SequenceNextVal] columns into the canonical helper-
 * table emulation (`dmg_sequences` + `_bi`/`_ai` trigger pair).
 *
 * Modelled after `MysqlSequenceDdlSupport`: a per-run stateful object
 * that captures schema/options on [beginRun], collects pending notes
 * and trigger specs during column-level resolution
 * ([resolveSequenceDefault]), emits the helper-table DDL from
 * [generateSequences] and the trigger pair from
 * [generateSupportTriggers], and finalises the result with the
 * cross-cutting `W117` warning.
 *
 * Mode gate: [DdlDialectContext.Sqlite.namedSequenceMode]
 * - [SqliteNamedSequenceMode.ACTION_REQUIRED] (default): existing E056
 *   skip behaviour, no support objects emitted.
 * - [SqliteNamedSequenceMode.HELPER_TABLE]: full emulation pipeline.
 */
internal class SqliteSequenceDdlSupport {

    private var currentOptions: DdlGenerationOptions = DdlGenerationOptions()
    private var currentSchema: SchemaDefinition? = null
    private var supportObjectsBlocked = false
    private val pendingSupportTriggerSpecs = mutableListOf<SqliteSequenceTriggerSpec>()
    private val pendingSequenceNotes = mutableListOf<TransformationNote>()
    private val sequenceBackedColumns = mutableSetOf<TableColumnKey>()
    private val w119EmittedColumns = mutableSetOf<TableColumnKey>()

    private val isHelperTable: Boolean
        get() = currentOptions.sqliteContext?.namedSequenceMode == SqliteNamedSequenceMode.HELPER_TABLE

    /**
     * 0.9.7 Phase F1: lets [SqliteDdlGenerator.generateRollback]
     * decide whether the helper-table mode is active without going
     * through [DdlGenerationOptions.sqliteContext] itself.
     */
    fun helperTableModeActive(): Boolean = isHelperTable

    /**
     * 0.9.7 Phase F1: returns `true` if the helper-table forward
     * pass against [schema] would have produced support objects
     * (`dmg_sequences` + at least one seed/trigger). If `false`,
     * the rollback has nothing to drop and the preflight check
     * would be noise.
     */
    fun helperTableProducedSupportObjects(schema: SchemaDefinition): Boolean {
        if (!isHelperTable) return false
        if (schema.sequences.isNotEmpty()) return true
        return hasSequenceBackedColumns(schema)
    }

    fun beginRun(schema: SchemaDefinition, options: DdlGenerationOptions) {
        currentOptions = options
        currentSchema = schema
        supportObjectsBlocked = false
        pendingSupportTriggerSpecs.clear()
        pendingSequenceNotes.clear()
        sequenceBackedColumns.clear()
        w119EmittedColumns.clear()
    }

    /**
     * Cross-cutting `W117`: SQLite helper-table sequence values are
     * transaction-bound — a ROLLBACK retracts the `dmg_sequences`
     * UPDATE alongside the user table changes, unlike native
     * PostgreSQL sequences. Emitted once per run if the helper-table
     * mode actually produced support objects.
     */
    fun finalizeResult(result: DdlResult): DdlResult {
        val schema = currentSchema
        val emittedSupport = isHelperTable &&
            !supportObjectsBlocked &&
            (schema?.sequences?.isNotEmpty() == true || pendingSupportTriggerSpecs.isNotEmpty())
        if (!emittedSupport) return result
        val warning = TransformationNote(
            type = NoteType.WARNING,
            code = "W117",
            objectName = SqliteSequenceNaming.SUPPORT_TABLE,
            message = "Sequence values in SQLite helper-table mode are transaction-bound; " +
                "rollback retracts increments (unlike native PostgreSQL sequences).",
        )
        return DdlResult(result.statements, result.skippedObjects, result.globalNotes + warning)
    }

    /**
     * Hook for [SqliteDdlGenerator.resolveSequenceDefault]: decides
     * whether a `DefaultValue.SequenceNextVal` column emits a
     * `DEFAULT …` clause (always `null` here — the trigger pair owns
     * the value injection) and registers the per-column trigger spec
     * so [generateSupportTriggers] can emit the kanonisches Paar.
     *
     * Side effects (helper_table mode):
     * - W115 lossy NULL semantics (one per affected column)
     * - W119 NOT NULL suppression registration (consumed by
     *   [shouldSuppressNotNull])
     * - E057 if the host table is `WITHOUT ROWID` (the trigger pair
     *   needs ROWID for the AFTER INSERT UPDATE — plan §3.5)
     *
     * Side effects (action_required mode):
     * - E056 action_required note, no trigger spec recorded
     */
    fun resolveSequenceDefault(
        tableName: String,
        columnName: String,
        seqDefault: DefaultValue.SequenceNextVal,
    ): String? {
        if (!isHelperTable) {
            pendingSequenceNotes += TransformationNote(
                type = NoteType.ACTION_REQUIRED,
                code = "E056",
                objectName = "$tableName.$columnName",
                message = "Sequence-based default on '$columnName' requires " +
                    "--sqlite-named-sequences helper_table to generate support objects.",
                hint = "Add --sqlite-named-sequences helper_table to enable sequence emulation.",
            )
            return null
        }

        val schema = currentSchema
        val table = schema?.tables?.get(tableName)
        if (table?.metadata?.withoutRowid == true) {
            pendingSequenceNotes += TransformationNote(
                type = NoteType.ACTION_REQUIRED,
                code = "E057",
                objectName = "$tableName.$columnName",
                message = "Sequence-backed column '$columnName' on WITHOUT ROWID table '$tableName' " +
                    "cannot use the two-trigger helper-table emulation; AFTER INSERT requires ROWID.",
                hint = "Use a ROWID table or fall back to --sqlite-named-sequences action_required.",
            )
            return null
        }

        pendingSupportTriggerSpecs += SqliteSequenceTriggerSpec(
            tableName = tableName,
            columnName = columnName,
            sequenceName = seqDefault.sequenceName,
        )
        pendingSequenceNotes += TransformationNote(
            type = NoteType.WARNING,
            code = "W115",
            objectName = "$tableName.$columnName",
            message = "SequenceNextVal on '$columnName' uses lossy SQLite trigger semantics; " +
                "explicit NULL is treated like an omitted value.",
        )
        // Plan §5.1 lines 1269–1290: conflict-handling INSERT forms
        // can consume a sequence value without producing a row.
        // Emitted per-column so the report points at the precise
        // table/column/sequence triple under risk.
        pendingSequenceNotes += TransformationNote(
            type = NoteType.INFO,
            code = "W121",
            objectName = "$tableName.$columnName",
            message = "Sequence '${seqDefault.sequenceName}' on column '$columnName' may consume " +
                "values without insertion under conflict-handling INSERT forms when the column " +
                "is NULL/omitted (ON CONFLICT DO UPDATE/DO NOTHING, INSERT OR IGNORE, INSERT OR " +
                "FAIL on multi-row inserts). INSERTs with explicit non-NULL values bypass the " +
                "sequence trigger. ON CONFLICT ABORT/ROLLBACK roll the increment back and produce " +
                "no gap.",
        )
        // Plan §3.4 lines 600–676: the AFTER INSERT trigger writes
        // the reserved value via `UPDATE … WHERE ROWID = NEW.ROWID`.
        // Under `PRAGMA recursive_triggers = ON` that UPDATE fires
        // any user-defined UPDATE trigger on the same table. The
        // neutral model does not (yet) carry the `UPDATE OF collist`
        // refinement, so the detection is conservatively coarse:
        // any UPDATE trigger on the host table raises W122. Plan
        // line 644-647 ("ohne OF-Einschraenkung: immer W122")
        // approves the conservative path.
        val hasUpdateTrigger = schema?.triggers?.values?.any {
            it.table == tableName && dev.dmigrate.core.model.TriggerEvent.UPDATE in it.events
        } == true
        if (hasUpdateTrigger) {
            pendingSequenceNotes += TransformationNote(
                type = NoteType.WARNING,
                code = "W122",
                objectName = "$tableName.$columnName",
                message = "AFTER INSERT sequence trigger on '$tableName.$columnName' performs an " +
                    "UPDATE on the same table; existing user-defined UPDATE triggers will fire " +
                    "when PRAGMA recursive_triggers is ON — verify compatibility (e.g. audit " +
                    "triggers logging the sequence assignment as a data change).",
                hint = "Under the SQLite default (recursive_triggers = OFF) this warning is moot.",
            )
        }
        sequenceBackedColumns += TableColumnKey(tableName, columnName)
        return null
    }

    /**
     * Records a per-column W119 note exactly once, even when the
     * suppression hook is consulted multiple times via the
     * [SqliteDdlGenerator.columnSql] override path. The
     * `columnSql`-side caller invokes this after deciding to
     * suppress; the dedupe set is reset per run in [beginRun].
     */
    fun recordNotNullSuppressionNote(tableName: String, columnName: String) {
        val key = TableColumnKey(tableName, columnName)
        require(key in sequenceBackedColumns) {
            "recordNotNullSuppressionNote called for '$tableName.$columnName' before " +
                "resolveSequenceDefault registered it as sequence-backed; check the columnSql " +
                "override's call order."
        }
        if (!w119EmittedColumns.add(key)) return
        pendingSequenceNotes += TransformationNote(
            type = NoteType.WARNING,
            code = "W119",
            objectName = "$tableName.$columnName",
            message = "NOT NULL constraint suppressed on sequence-backed column '$columnName' " +
                "for two-trigger compatibility; column value is guaranteed by AFTER INSERT trigger.",
        )
    }

    /**
     * Plan §3.4 lines 678–708: in helper_table mode the two-trigger
     * pipeline inserts NULL first and lets the AFTER INSERT trigger
     * write the reserved value, so a generator-emitted NOT NULL on
     * the sequence-backed column would block the INSERT before the
     * trigger gets a chance to run.
     *
     * Pure decision — no side effects. The matching W119 note is
     * emitted by [recordNotNullSuppressionNote] (dedupe-aware via
     * `w119EmittedColumns`); the suppression hook can be consulted
     * from any number of [SqliteDdlGenerator.columnSql] invocations
     * without producing duplicate notes.
     *
     * The decision is order-independent: it keys off the column's
     * own `DefaultValue.SequenceNextVal` (so `AbstractDdlGenerator.
     * columnSql`'s NOT-NULL-before-default ordering is irrelevant)
     * and skips WITHOUT ROWID tables (those land on the E057 path
     * via [resolveSequenceDefault] and never get a trigger pair).
     */
    fun shouldSuppressNotNull(tableName: String, column: dev.dmigrate.core.model.ColumnDefinition): Boolean {
        if (!isHelperTable) return false
        if (!column.required) return false
        if (column.default !is DefaultValue.SequenceNextVal) return false
        val schema = currentSchema
        if (schema?.tables?.get(tableName)?.metadata?.withoutRowid == true) return false
        return true
    }

    /**
     * Plan §3.4 lines 710–740: a CHECK constraint that rejects NULL
     * explicitly on a sequence-backed column would fail during the
     * INSERT (before the AFTER INSERT trigger runs). The generator
     * suppresses such CHECK constraints. Implicit NULL-tolerant
     * CHECKs (the SQL standard `NULL → UNKNOWN → "not violated"`
     * behaviour) stay in place.
     *
     * Suppression is **whole-expression only** — the matcher accepts
     * the canonical shapes `<col> IS NOT NULL` and
     * `NOT (<col> IS NULL)` (with optional outer parens / whitespace
     * / SQLite identifier quoting), but a CHECK that combines
     * `IS NOT NULL` with other predicates (`<col> > 0 AND
     * <col> IS NOT NULL`) is **not** suppressed: dropping the whole
     * constraint would silently lose the other predicate, and SQLite
     * would surface the NULL-rejection at INSERT time with a clear
     * error. Users that want both behaviours split the constraint
     * into two named CHECKs.
     */
    fun shouldSuppressCheckConstraint(tableName: String, expression: String): Boolean {
        if (!isHelperTable) return false
        if (sequenceBackedColumns.none { it.tableName == tableName }) return false
        val referencedSequenceColumn = sequenceBackedColumns
            .filter { it.tableName == tableName }
            .firstOrNull { isPureIsNotNullCheck(expression, it.columnName) }
            ?: return false
        pendingSequenceNotes += TransformationNote(
            type = NoteType.WARNING,
            code = "W119",
            objectName = "$tableName.${referencedSequenceColumn.columnName}",
            message = "CHECK constraint suppressed on sequence-backed column " +
                "'${referencedSequenceColumn.columnName}'; NULL-rejecting CHECK is incompatible with " +
                "the two-trigger helper-table pipeline.",
        )
        return true
    }

    /**
     * True when [expression] is — after collapsing whitespace and
     * stripping outer parentheses — exactly one of:
     * - `<col> IS NOT NULL`
     * - `NOT (<col> IS NULL)`
     *
     * The column reference may be unquoted or wrapped in any of
     * SQLite's three identifier-quoting forms (`"`, backtick, `[]`).
     * Anything else (compound predicates, additional clauses, unknown
     * operators around the NULL-check) leaves the CHECK untouched.
     */
    private fun isPureIsNotNullCheck(expression: String, columnName: String): Boolean {
        val collapsed = stripOuterParens(expression.trim().replace(Regex("\\s+"), " ")).lowercase()
        val colNorm = columnName.lowercase()
        val colRefs = listOf(
            colNorm,
            "\"${colNorm.replace("\"", "\"\"")}\"",
            "`${colNorm.replace("`", "``")}`",
            "[$colNorm]",
        )
        return colRefs.any { ref ->
            collapsed == "$ref is not null" ||
                collapsed == "not ($ref is null)" ||
                collapsed == "not($ref is null)"
        }
    }

    private fun stripOuterParens(s: String): String {
        var t = s.trim()
        while (t.startsWith("(") && t.endsWith(")") && parensBalanced(t.substring(1, t.length - 1))) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    private fun parensBalanced(s: String): Boolean {
        var depth = 0
        for (ch in s) {
            when (ch) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0
    }

    fun drainPendingNotes(): List<TransformationNote> =
        pendingSequenceNotes.toList().also { pendingSequenceNotes.clear() }

    /**
     * action_required: per-sequence E056 skip (backward compatible).
     * helper_table: emit `dmg_sequences` table + per-sequence seed
     * INSERTs; emit W114 per sequence with a `cache` value (SQLite is
     * single-writer; the value is metadata-only); abort with E124 if
     * the user schema already defines `dmg_sequences`.
     */
    fun generateSequences(
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        if (!isHelperTable) {
            return sequences.map { (name, _) ->
                val action = ManualActionRequired(
                    code = "E056",
                    objectType = "sequence",
                    objectName = name,
                    reason = "Sequence '$name' is not supported in SQLite without helper_table mode.",
                    hint = "Add --sqlite-named-sequences helper_table to enable sequence emulation.",
                )
                skipped += action.toSkipped()
                DdlStatement("", listOf(action.toNote()))
            }
        }

        val statements = mutableListOf<DdlStatement>()
        val schema = currentSchema
        val collision = schema?.let { detectReservedNameCollision(it) }
        if (collision != null) {
            val action = ManualActionRequired(
                code = "E124",
                objectType = collision.objectType,
                objectName = collision.name,
                reason = "Support object name collision: '${collision.name}' " +
                    "(${collision.objectType}) lives in the d-migrate-reserved namespace.",
                hint = "Rename the existing ${collision.objectType} or use --sqlite-named-sequences action_required.",
            )
            skipped += action.toSkipped()
            statements += DdlStatement("", listOf(action.toNote()))
            supportObjectsBlocked = true
            return statements
        }

        if (sequences.isEmpty() && !hasSequenceBackedColumns(schema)) {
            return emptyList()
        }

        statements += DdlStatement(SqliteSequenceEmulationTemplates.supportTableSql())
        val cacheNotes = mutableListOf<TransformationNote>()
        for ((name, sequence) in sequences) {
            statements += DdlStatement(SqliteSequenceEmulationTemplates.sequenceSeedSql(name, sequence))
            if (sequence.cache != null) {
                cacheNotes += TransformationNote(
                    type = NoteType.WARNING,
                    code = "W114",
                    objectName = name,
                    message = "Sequence '$name' has cache=${sequence.cache} but SQLite helper-table mode " +
                        "does not emulate preallocation; cache value is stored as metadata only.",
                )
            }
        }
        if (cacheNotes.isNotEmpty()) statements += DdlStatement("", cacheNotes)
        return statements
    }

    /**
     * Emits the `_bi`/`_ai` trigger pair for every column registered
     * via [resolveSequenceDefault]. Called by [SqliteDdlGenerator]
     * from `generateTriggers` so the support triggers are CREATEd
     * before user-defined triggers and therefore fire first (plan
     * §5.1 — SQLite executes triggers in their creation order).
     *
     * Name collisions against user-defined triggers raise E124 and
     * skip the offending pair; collisions on the BEFORE/AFTER pair
     * are checked independently so a malformed reverse round-trip
     * cannot leak through.
     */
    fun generateSupportTriggers(
        userTriggerNames: Set<String>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        if (!isHelperTable || supportObjectsBlocked) return emptyList()
        if (pendingSupportTriggerSpecs.isEmpty()) return emptyList()

        val statements = mutableListOf<DdlStatement>()
        val emittedTriggerNames = mutableSetOf<String>()
        for (spec in pendingSupportTriggerSpecs) {
            val biName = SqliteSequenceNaming.beforeInsertTriggerName(spec.tableName, spec.columnName, spec.sequenceName)
            val aiName = SqliteSequenceNaming.afterInsertTriggerName(spec.tableName, spec.columnName, spec.sequenceName)

            val userCollision = listOf(biName, aiName).firstOrNull { it in userTriggerNames }
            if (userCollision != null) {
                val action = ManualActionRequired(
                    code = "E124",
                    objectType = "trigger",
                    objectName = userCollision,
                    reason = "Support object name collision: '$userCollision' already exists in the neutral schema.",
                    hint = "Rename the existing trigger or use --sqlite-named-sequences action_required.",
                )
                skipped += action.toSkipped()
                statements += DdlStatement("", listOf(action.toNote()))
                continue
            }

            // Plan §3.3 line 341: hash10 collisions between different
            // sequence specs in the same run are an explicit generate
            // error, not a silent overwrite. Probability is ~2^-40 in
            // practice but the diagnostic is cheap and avoids a
            // confusing SQLite-runtime `trigger already exists` fail.
            val intraRunCollision = listOf(biName, aiName).firstOrNull { it in emittedTriggerNames }
            if (intraRunCollision != null) {
                val action = ManualActionRequired(
                    code = "E124",
                    objectType = "trigger",
                    objectName = intraRunCollision,
                    reason = "Support object hash collision: '$intraRunCollision' is shared by two " +
                        "different sequence-backed column specs in the same generation run.",
                    hint = "Rename one of the colliding sequences or columns to disambiguate the hash; " +
                        "this is extremely rare (10-hex SHA-256 prefix).",
                )
                skipped += action.toSkipped()
                statements += DdlStatement("", listOf(action.toNote()))
                continue
            }

            statements += DdlStatement(SqliteSequenceEmulationTemplates.beforeInsertTriggerSql(spec, biName))
            statements += DdlStatement(SqliteSequenceEmulationTemplates.afterInsertTriggerSql(spec, aiName))
            emittedTriggerNames += biName
            emittedTriggerNames += aiName
        }
        return statements
    }

    private fun hasSequenceBackedColumns(schema: SchemaDefinition?): Boolean {
        if (schema == null) return false
        return schema.tables.any { (_, table) ->
            table.columns.any { (_, column) -> column.default is DefaultValue.SequenceNextVal }
        }
    }

    /**
     * Plan §3.3 line 333–348: scans the neutral schema for any
     * user-side object whose name lives in the d-migrate-reserved
     * namespace (`dmg_sequences` exact, or canonical
     * `dmg_seq_<table16>_<column16>_<hash10>_{bi,ai}` pattern). SQLite
     * shares one namespace across tables, views, indices and
     * triggers, so a name collision on any of these would surface as
     * a `CREATE … already exists` at run time; E124 catches it
     * up-front with a clean diagnostic.
     */
    private fun detectReservedNameCollision(schema: SchemaDefinition): ReservedNameCollision? {
        for ((name, _) in schema.tables) {
            if (SqliteSequenceNaming.isReservedName(name)) {
                return ReservedNameCollision("table", name)
            }
        }
        for ((name, _) in schema.views) {
            if (SqliteSequenceNaming.isReservedName(name)) {
                return ReservedNameCollision("view", name)
            }
        }
        for ((name, _) in schema.triggers) {
            if (SqliteSequenceNaming.isReservedName(name)) {
                return ReservedNameCollision("trigger", name)
            }
        }
        for ((_, table) in schema.tables) {
            for (index in table.indices) {
                val indexName = index.name ?: continue
                if (SqliteSequenceNaming.isReservedName(indexName)) {
                    return ReservedNameCollision("index", indexName)
                }
            }
        }
        return null
    }

    private data class ReservedNameCollision(val objectType: String, val name: String)

    private data class TableColumnKey(val tableName: String, val columnName: String)
}
