package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.SqlIdentifiers

/**
 * 0.9.7 SQLite-Sequence Phase B.3: pure SQL templates for the
 * helper-table emulation. Extracted so the DDL-Generator pipeline
 * ([SqliteSequenceDdlSupport]) and the upcoming `SqliteDiffSequenceOps`
 * (Phase E) can share a single source of truth for the emitted SQL
 * shape, analogous to `MysqlSequenceEmulationTemplates`.
 *
 * The templates are stateless and produce a single [String]; callers
 * wrap them into [dev.dmigrate.driver.DdlStatement] / per-renderer
 * statement shapes. Identifier quoting is fixed to SQLite's double-
 * quote dialect since these templates are SQLite-specific.
 *
 * Plan-Doc: `docs/planning/in-progress/sqlite-sequence-emulation-plan.md`
 * §3.2 (`dmg_sequences` shape), §3.4 (canonical trigger pair) and
 * §5.1 (DDL emission order).
 */
internal object SqliteSequenceEmulationTemplates {

    /** SQLite identifier quoting (always double-quote). */
    private fun q(name: String): String = "\"${name.replace("\"", "\"\"")}\""

    /** Single-quoted SQL string literal with embedded `'` escaping. */
    private fun lit(value: String): String =
        SqlIdentifiers.quoteStringLiteral(value, dev.dmigrate.driver.DatabaseDialect.SQLITE)

    /**
     * `CREATE TABLE IF NOT EXISTS dmg_sequences` — the helper table
     * holds one row per managed sequence.
     *
     * `IF NOT EXISTS` keeps the bootstrap idempotent across migration
     * runs that add new sequences to a database whose support table is
     * already in place. Schema drift (an existing `dmg_sequences` with
     * a non-canonical column shape) is detected by the reverse path,
     * not by this template.
     *
     * Column shape matches plan §3.2; `exhausted` is the boolean flag
     * the trigger pair sets when `cycle_enabled = 0` and the next
     * increment would leave the range. `last_returned_value` decouples
     * the `_ai`-trigger from the `next_value` state across a cycle
     * reset (see plan §3.4 lines 217–224).
     */
    fun supportTableSql(): String = buildString {
        appendLine("CREATE TABLE IF NOT EXISTS ${q(SqliteSequenceNaming.SUPPORT_TABLE)} (")
        appendLine("    ${q("managed_by")} TEXT NOT NULL,")
        appendLine("    ${q("format_version")} TEXT NOT NULL,")
        appendLine("    ${q("name")} TEXT NOT NULL,")
        appendLine("    ${q("next_value")} INTEGER NOT NULL,")
        appendLine("    ${q("last_returned_value")} INTEGER NULL,")
        appendLine("    ${q("exhausted")} INTEGER NOT NULL DEFAULT 0,")
        appendLine("    ${q("increment_by")} INTEGER NOT NULL,")
        appendLine("    ${q("min_value")} INTEGER NULL,")
        appendLine("    ${q("max_value")} INTEGER NULL,")
        appendLine("    ${q("cycle_enabled")} INTEGER NOT NULL,")
        appendLine("    ${q("cache_size")} INTEGER NULL,")
        appendLine("    PRIMARY KEY (${q("name")})")
        append(");")
    }

    /**
     * `INSERT INTO dmg_sequences …` seed row for a single sequence.
     *
     * - `next_value` is seeded to [SequenceDefinition.start] (default 1).
     * - `last_returned_value` is `NULL` until the first trigger fires.
     * - `exhausted` is 0; the trigger sets it to 1 when an
     *   `increment_by` overshoot meets `cycle_enabled = 0`.
     * - `cache_size` is stored verbatim from the neutral model so the
     *   round-trip is metadata-lossless; SQLite does not preallocate
     *   ([W114] in [SqliteSequenceDdlSupport]).
     */
    fun sequenceSeedSql(name: String, sequence: SequenceDefinition): String {
        val start = sequence.start ?: 1L
        val increment = sequence.increment ?: 1L
        val minValue = sequence.minValue?.toString() ?: "NULL"
        val maxValue = sequence.maxValue?.toString() ?: "NULL"
        val cycle = if (sequence.cycle == true) 1 else 0
        val cache = sequence.cache?.toString() ?: "NULL"
        return buildString {
            append("INSERT INTO ${q(SqliteSequenceNaming.SUPPORT_TABLE)} (")
            append(q("managed_by")).append(", ")
            append(q("format_version")).append(", ")
            append(q("name")).append(", ")
            append(q("next_value")).append(", ")
            append(q("last_returned_value")).append(", ")
            append(q("exhausted")).append(", ")
            append(q("increment_by")).append(", ")
            append(q("min_value")).append(", ")
            append(q("max_value")).append(", ")
            append(q("cycle_enabled")).append(", ")
            append(q("cache_size"))
            append(") VALUES (")
            append(lit(SqliteSequenceNaming.MANAGED_BY)).append(", ")
            append(lit(SqliteSequenceNaming.FORMAT_VERSION)).append(", ")
            append(lit(name)).append(", ")
            append(start).append(", ")
            append("NULL").append(", ")
            append(0).append(", ")
            append(increment).append(", ")
            append(minValue).append(", ")
            append(maxValue).append(", ")
            append(cycle).append(", ")
            append(cache)
            append(");")
        }
    }

    /**
     * Canonical `BEFORE INSERT` trigger (plan §3.4 lines 481–563):
     * reserves the next value, updates `last_returned_value`, advances
     * `next_value` with an overflow-safe boundary check, sets the
     * `exhausted` flag when the range is exhausted without `cycle`.
     *
     * The `WHEN NEW."<column>" IS NULL` filter only fires when the
     * INSERT leaves the column to the trigger; explicit values bypass
     * the sequence (and produce no gap).
     */
    fun beforeInsertTriggerSql(spec: SqliteSequenceTriggerSpec, triggerName: String): String = buildString {
        val table = q(spec.tableName)
        val column = q(spec.columnName)
        val sequenceLiteral = lit(spec.sequenceName)
        val support = q(SqliteSequenceNaming.SUPPORT_TABLE)
        val name = q("name")
        val nextValue = q("next_value")
        val lastReturned = q("last_returned_value")
        val exhausted = q("exhausted")
        val incrementBy = q("increment_by")
        val minValue = q("min_value")
        val maxValue = q("max_value")
        val cycleEnabled = q("cycle_enabled")

        appendLine("CREATE TRIGGER ${q(triggerName)}")
        appendLine("BEFORE INSERT ON $table")
        appendLine("FOR EACH ROW")
        appendLine("WHEN NEW.$column IS NULL")
        appendLine("BEGIN")
        appendLine(
            "    /* d-migrate:${SqliteSequenceNaming.FORMAT_VERSION} object=sequence-trigger " +
                "sequence=${markerValue(spec.sequenceName)} " +
                "table=${markerValue(spec.tableName)} " +
                "column=${markerValue(spec.columnName)} */"
        )
        appendLine("    SELECT RAISE(ABORT, 'dmg_sequences: sequence row ${markerSafeForMessage(spec.sequenceName)} not found')")
        appendLine("        WHERE NOT EXISTS (")
        appendLine("            SELECT 1 FROM $support WHERE $name = $sequenceLiteral")
        appendLine("        );")
        appendLine("    SELECT RAISE(ABORT, 'dmg_sequences: sequence ${markerSafeForMessage(spec.sequenceName)} exhausted')")
        appendLine("        WHERE (SELECT $exhausted FROM $support WHERE $name = $sequenceLiteral) = 1;")
        appendLine("    UPDATE $support")
        appendLine("        SET $lastReturned = $nextValue,")
        appendLine("            $nextValue = CASE")
        appendLine("                WHEN $incrementBy > 0")
        appendLine("                     AND $nextValue > COALESCE($maxValue, 9223372036854775807) - $incrementBy")
        appendLine("                     AND $cycleEnabled = 1")
        appendLine("                THEN COALESCE($minValue, 1)")
        appendLine("                WHEN $incrementBy < 0")
        appendLine("                     AND $nextValue < COALESCE($minValue, -9223372036854775808) - $incrementBy")
        appendLine("                     AND $cycleEnabled = 1")
        appendLine("                THEN COALESCE($maxValue, -1)")
        appendLine("                WHEN $incrementBy > 0")
        appendLine("                     AND $nextValue > COALESCE($maxValue, 9223372036854775807) - $incrementBy")
        appendLine("                     AND $cycleEnabled = 0")
        appendLine("                THEN $nextValue")
        appendLine("                WHEN $incrementBy < 0")
        appendLine("                     AND $nextValue < COALESCE($minValue, -9223372036854775808) - $incrementBy")
        appendLine("                     AND $cycleEnabled = 0")
        appendLine("                THEN $nextValue")
        appendLine("                ELSE $nextValue + $incrementBy")
        appendLine("            END,")
        appendLine("            $exhausted = CASE")
        appendLine("                WHEN $cycleEnabled = 0")
        appendLine("                     AND (")
        appendLine("                         ($incrementBy > 0")
        appendLine("                          AND $nextValue > COALESCE($maxValue, 9223372036854775807) - $incrementBy)")
        appendLine("                         OR")
        appendLine("                         ($incrementBy < 0")
        appendLine("                          AND $nextValue < COALESCE($minValue, -9223372036854775808) - $incrementBy)")
        appendLine("                     )")
        appendLine("                THEN 1")
        appendLine("                ELSE $exhausted")
        appendLine("            END")
        appendLine("        WHERE $name = $sequenceLiteral;")
        append("END;")
    }

    /**
     * Canonical `AFTER INSERT` trigger (plan §3.4 lines 566–579):
     * writes the previously reserved `last_returned_value` into the
     * just-inserted row via `UPDATE … WHERE ROWID = NEW.ROWID`.
     *
     * The `_ai`-trigger is paired with [beforeInsertTriggerSql]; both
     * fire only when the INSERT left the column NULL (the trigger
     * filter is symmetric).
     */
    fun afterInsertTriggerSql(spec: SqliteSequenceTriggerSpec, triggerName: String): String = buildString {
        val table = q(spec.tableName)
        val column = q(spec.columnName)
        val sequenceLiteral = lit(spec.sequenceName)
        val support = q(SqliteSequenceNaming.SUPPORT_TABLE)
        val name = q("name")
        val lastReturned = q("last_returned_value")

        appendLine("CREATE TRIGGER ${q(triggerName)}")
        appendLine("AFTER INSERT ON $table")
        appendLine("FOR EACH ROW")
        appendLine("WHEN NEW.$column IS NULL")
        appendLine("BEGIN")
        appendLine(
            "    /* d-migrate:${SqliteSequenceNaming.FORMAT_VERSION} object=sequence-trigger-post " +
                "sequence=${markerValue(spec.sequenceName)} " +
                "table=${markerValue(spec.tableName)} " +
                "column=${markerValue(spec.columnName)} */"
        )
        appendLine("    UPDATE $table")
        appendLine("        SET $column = (")
        appendLine("            SELECT $lastReturned FROM $support WHERE $name = $sequenceLiteral")
        appendLine("        )")
        appendLine("        WHERE ROWID = NEW.ROWID;")
        append("END;")
    }

    /**
     * 0.9.7 Phase F1 + G2-Followup: Rollback preflight (Plan §5.2
     * lines 1494–1568).
     *
     * Emits a multi-statement preamble that aborts the rollback
     * stream **before** any DROP runs if external objects reference
     * `dmg_sequences` (E058) or ATTACHed databases are present
     * (E060). The scan covers `main.sqlite_master` AND
     * `temp.sqlite_master` per Plan §5.2 lines 1548–1556.
     *
     * Mechanik: SQLite verbietet `RAISE()` außerhalb von Triggern,
     * deshalb fahren wir den Abbruch über eine CHECK-Constraint mit
     * sprechendem Constraint-**Namen**. Bei externer Referenz
     * inserten wir den Wert `1`, der die CHECK verletzt. Die JDBC-
     * Fehlermeldung enthält den Constraint-Namen
     * (`E058_external_dmg_sequences_refs`), so dass der Code im
     * Fehlertext nachweisbar bleibt.
     *
     * Managed-Erkennung: Plan §5.2 lines 1534–1540 verlangt dasselbe
     * strenge Matching wie im Reverse-Pfad (Marker-Kommentar primär,
     * 5-Kriterien sekundär). Reines SQL kann das nicht — wir
     * akzeptieren stattdessen jeden Trigger, dessen kanonischer Name
     * dem `dmg_seq_<...>_{bi,ai}`-Pattern entspricht UND dessen Body
     * entweder den `d-migrate:sqlite-sequence-v1`-Marker-Substring
     * oder (wenn fehlend) `WHEN NEW.` enthält. Trigger ohne diese
     * Mindest-Charakteristik gelten als fremde Abhängigkeit. False
     * Negatives bei sehr gekünstelten Trigger-Bodies sind möglich
     * (Operator kann via `--force-rollback` umgehen).
     *
     * LIKE-Pattern decken die vier SQLite-Identifier-Quoting-Formen
     * ab: `"…"`, backtick, `[…]`, unquoted; plus schema-qualifizierten
     * Zugriff und Wort-Grenzen über Whitespace, Klammern und
     * Funktionsaufruf-Klammern.
     */
    fun rollbackPreflightSqls(): List<String> =
        e058CheckSqls() + e060CheckSqls()

    private fun e058CheckSqls(): List<String> {
        val tbl = SqliteSequenceNaming.SUPPORT_TABLE
        val constraintName = "E058_external_${tbl}_refs"
        return listOf(
            """
                CREATE TEMP TABLE "_dmg_pf_e058" (
                    "x" INTEGER NOT NULL,
                    CONSTRAINT "$constraintName" CHECK ("x" = 0)
                );
            """.trimIndent(),
            """
                INSERT INTO "_dmg_pf_e058" ("x")
                    SELECT CASE WHEN EXISTS (
                        SELECT 1 FROM (
                            SELECT name, type, sql FROM sqlite_master
                            UNION ALL
                            SELECT name, type, sql FROM temp.sqlite_master
                        )
                            WHERE type IN ('view', 'trigger', 'index', 'table')
                            AND name != '$tbl'
                            AND NOT (
                                name GLOB 'dmg_seq_*_bi'
                                AND sql IS NOT NULL
                                AND (
                                    sql LIKE '%d-migrate:sqlite-sequence-v1%'
                                    OR sql LIKE '%WHEN NEW.%IS NULL%'
                                )
                            )
                            AND NOT (
                                name GLOB 'dmg_seq_*_ai'
                                AND sql IS NOT NULL
                                AND (
                                    sql LIKE '%d-migrate:sqlite-sequence-v1%'
                                    OR sql LIKE '%WHEN NEW.%IS NULL%'
                                )
                            )
                            AND sql IS NOT NULL
                            AND (
                                lower(sql) LIKE '%"$tbl"%'
                                OR lower(sql) LIKE '%`$tbl`%'
                                OR lower(sql) LIKE '%[$tbl]%'
                                OR lower(sql) LIKE '% $tbl %'
                                OR lower(sql) LIKE '%($tbl %'
                                OR lower(sql) LIKE '%($tbl)%'
                                OR lower(sql) LIKE '%($tbl,%'
                                OR lower(sql) LIKE '% $tbl(%'
                                OR lower(sql) LIKE '% $tbl,%'
                                OR lower(sql) LIKE '%.$tbl %'
                                OR lower(sql) LIKE '%.$tbl(%'
                                OR lower(sql) LIKE '%.$tbl)%'
                                OR lower(sql) LIKE '%.$tbl,%'
                            )
                    ) THEN 1 ELSE 0 END;
            """.trimIndent(),
            """DROP TABLE "_dmg_pf_e058";""",
        )
    }

    private fun e060CheckSqls(): List<String> {
        val constraintName = "E060_attached_databases_detected"
        return listOf(
            """
                CREATE TEMP TABLE "_dmg_pf_e060" (
                    "x" INTEGER NOT NULL,
                    CONSTRAINT "$constraintName" CHECK ("x" = 0)
                );
            """.trimIndent(),
            """
                INSERT INTO "_dmg_pf_e060" ("x")
                    SELECT CASE WHEN (
                        SELECT count(*) FROM pragma_database_list
                            WHERE name NOT IN ('main', 'temp')
                    ) > 0 THEN 1 ELSE 0 END;
            """.trimIndent(),
            """DROP TABLE "_dmg_pf_e060";""",
        )
    }

    /**
     * Plan §3.3 lines 271–303: percent-encode any character outside
     * `[A-Za-z0-9_.-]` ∪ Unicode-category `L` (letters). Digits in
     * Unicode-category `N` other than `Decimal_Number` (e.g. `²`,
     * roman numerals) are also encoded — `Char.isDigit()` matches
     * `Decimal_Number` only, matching the Plan-Spec exactly. The
     * encoder is closed-form so the reverse parser has a single
     * round-trip rule.
     */
    internal fun markerValue(identifier: String): String = buildString {
        for (ch in identifier) {
            val safe = ch.isLetter() || ch.isDigit() || ch == '_' || ch == '.' || ch == '-'
            if (safe) {
                append(ch)
            } else {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    append('%')
                    append(String.format(java.util.Locale.ROOT, "%02X", b.toInt() and 0xFF))
                }
            }
        }
    }

    /**
     * Escapes a sequence-name occurrence inside a `RAISE(ABORT, '…')`
     * literal. Embedded `'` is doubled (standard SQL); embedded `\`
     * is also doubled defensively, so the literal stays safe if the
     * SQLite build was compiled with backslash-escape support.
     */
    private fun markerSafeForMessage(identifier: String): String =
        identifier.replace("\\", "\\\\").replace("'", "''")
}

/**
 * Per-column sequence spec gathered by [SqliteSequenceDdlSupport]
 * while walking the schema; consumed by both [SqliteSequenceEmulationTemplates]
 * (for the trigger pair body) and the future reverse stage (for
 * canonical name lookup).
 */
internal data class SqliteSequenceTriggerSpec(
    val tableName: String,
    val columnName: String,
    val sequenceName: String,
)
