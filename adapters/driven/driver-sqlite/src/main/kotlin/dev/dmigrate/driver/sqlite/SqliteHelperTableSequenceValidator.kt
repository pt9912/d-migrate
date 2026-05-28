package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.driver.SqliteNamedSequenceMode

/**
 * 0.9.7 SQLite-Sequence Phase B.2 step 2: cross-check validation that
 * fires only when the SQLite generator runs in `helper_table` mode.
 *
 * The plan-doc reserves these rules to a dedicated SQLite-mode-specific
 * validation stage rather than the dialect-agnostic
 * [dev.dmigrate.core.validation.SchemaValidator] because a column that
 * carries [DefaultValue.SequenceNextVal] is perfectly valid against a
 * PostgreSQL or MySQL target — only the SQLite two-trigger emulation
 * (§3.4) has the structural conflict that makes the combination
 * unsafe.
 *
 * Rules in scope of this step:
 *
 * - **E059** — a column with `DefaultValue.SequenceNextVal` is part of
 *   the table's `primaryKey`. SQLite enforces an implicit `NOT NULL`
 *   on PK columns (`docs/planning/in-progress/sqlite-sequence-emulation-plan.md`
 *   §3.4 lines 737-757); the W119 NOT-NULL-suppression that lets the
 *   `_bi`/`_ai` trigger pair survive does NOT lift the PK-implicit
 *   NULL rejection, so an `INSERT` whose sequence column is left to
 *   the trigger fails before `_ai` can write the reserved value.
 *
 * Out of scope here (deferred to Phase B.3 generator):
 *
 * - **E057** (WITHOUT ROWID + `SequenceNextVal`) — per plan §3.5 the
 *   generator emits this as a per-column `action_required` skip note,
 *   not a hard validation error; the table itself stays renderable.
 * - **CHECK `IS NOT NULL` auto-suppression** — per plan §3.4 lines
 *   728-735 the generator transforms the CHECK and emits a warning,
 *   it does not block generation.
 *
 * The [validate] entry point is a no-op when [mode] is
 * [SqliteNamedSequenceMode.ACTION_REQUIRED] — the existing skip path
 * already absorbs the offending column without ever opening the
 * helper-table emulation pipeline.
 */
object SqliteHelperTableSequenceValidator {

    fun validate(
        schema: SchemaDefinition,
        mode: SqliteNamedSequenceMode,
    ): List<ValidationError> {
        if (mode != SqliteNamedSequenceMode.HELPER_TABLE) return emptyList()
        val errors = mutableListOf<ValidationError>()
        for ((tableName, table) in schema.tables) {
            val pkColumns = table.primaryKey.toSet()
            if (pkColumns.isEmpty()) continue
            for (columnName in pkColumns) {
                val column = table.columns[columnName] ?: continue
                if (column.default is DefaultValue.SequenceNextVal) {
                    errors += ValidationError(
                        code = "E059",
                        message = "Sequence-backed column '$columnName' cannot be part of " +
                            "PRIMARY KEY in SQLite helper-table mode; use " +
                            "INTEGER PRIMARY KEY AUTOINCREMENT or application-level sequencing.",
                        objectPath = "tables.$tableName.columns.$columnName",
                    )
                }
            }
        }
        return errors
    }
}
