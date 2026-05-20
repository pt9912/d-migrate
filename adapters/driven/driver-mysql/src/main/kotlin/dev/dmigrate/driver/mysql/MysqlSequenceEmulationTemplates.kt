package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.SequenceDefinition

/**
 * Canonical specification of a sequence-backed insert trigger,
 * shared between [MysqlSequenceDdlSupport] (DDL-Generator pipeline)
 * and the upcoming `MysqlDiffSequenceOps` (per-op diff rendering,
 * Sub-Slice B). Lives at top level so callers do not need the
 * nested `MysqlSequenceEmulationTemplates.SequenceTriggerSpec`
 * qualifier.
 */
internal data class MysqlSequenceTriggerSpec(
    val tableName: String,
    val columnName: String,
    val sequenceName: String,
)

/**
 * E.3 MySQL Sequence-Diff Sub-Slice A: pure SQL templates for the
 * helper-table emulation. Extracted from [MysqlSequenceDdlSupport]
 * so both the DDL-Generator pipeline (full schema emission) and the
 * upcoming `MysqlDiffSequenceOps` (per-op diff rendering, Sub-Slice
 * B) share a single source of truth for the emitted SQL shape.
 *
 * Plan-Doc: `docs/planning/in-progress/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`
 * §5.1 (Re-Use vs. Duplikation) and §6 Sub-Slice A.
 *
 * All functions are stateless and produce a single
 * [String] — callers wrap them into `DdlStatement` / per-renderer
 * statement shapes. Identifier quoting is parameterised so adapter-
 * and diff-context callers can pass their own `quoteIdentifier`
 * implementation; the templates themselves do not assume backticks.
 *
 * What stays in [MysqlSequenceDdlSupport]: pipeline state
 * (`pendingSupportTriggers`, `pendingSequenceNotes`,
 * `supportObjectsBlocked`), collision checks against the neutral
 * schema, and the `TransformationNote` / `ManualActionRequired`
 * stream. Those concerns live above the SQL-template layer.
 */
internal object MysqlSequenceEmulationTemplates {

    /**
     * `CREATE TABLE dmg_sequences` — the helper table that stores
     * one row per managed sequence.
     */
    fun supportTableSql(quoteIdentifier: (String) -> String): String = buildString {
        appendLine("CREATE TABLE ${quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE)} (")
        appendLine("    ${quoteIdentifier("managed_by")} VARCHAR(32) NOT NULL,")
        appendLine("    ${quoteIdentifier("format_version")} VARCHAR(32) NOT NULL,")
        appendLine("    ${quoteIdentifier("name")} VARCHAR(255) NOT NULL,")
        appendLine("    ${quoteIdentifier("next_value")} BIGINT NOT NULL,")
        appendLine("    ${quoteIdentifier("increment_by")} BIGINT NOT NULL,")
        appendLine("    ${quoteIdentifier("min_value")} BIGINT NULL,")
        appendLine("    ${quoteIdentifier("max_value")} BIGINT NULL,")
        appendLine("    ${quoteIdentifier("cycle_enabled")} TINYINT(1) NOT NULL,")
        appendLine("    ${quoteIdentifier("cache_size")} INT NULL,")
        appendLine("    PRIMARY KEY (${quoteIdentifier("name")})")
        append(") ENGINE=InnoDB;")
    }

    /**
     * `INSERT INTO dmg_sequences …` for a single named sequence. The
     * `next_value` field is seeded to [SequenceDefinition.start] (or
     * `1` if not set) — the helper-table contract treats
     * `next_value` as the runtime state and exposes it via
     * `dmg_nextval` / `dmg_setval`.
     */
    fun sequenceSeedSql(
        name: String,
        sequence: SequenceDefinition,
        quoteIdentifier: (String) -> String,
    ): String {
        val start = sequence.start ?: 1L
        val increment = sequence.increment ?: 1L
        val minValue = sequence.minValue?.toString() ?: "NULL"
        val maxValue = sequence.maxValue?.toString() ?: "NULL"
        val cycle = if (sequence.cycle == true) 1 else 0
        val cache = sequence.cache?.toString() ?: "NULL"
        val nameLiteral = MysqlSequenceSqlCodec.quoteStringLiteral(name)
        return "INSERT INTO ${quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE)} " +
            "(${quoteIdentifier("managed_by")}, ${quoteIdentifier("format_version")}, ${quoteIdentifier("name")}, " +
            "${quoteIdentifier("next_value")}, ${quoteIdentifier("increment_by")}, ${quoteIdentifier("min_value")}, " +
            "${quoteIdentifier("max_value")}, ${quoteIdentifier("cycle_enabled")}, " +
            "${quoteIdentifier("cache_size")}) VALUES " +
            "('d-migrate', 'mysql-sequence-v1', $nameLiteral, $start, $increment, " +
            "$minValue, $maxValue, $cycle, $cache);"
    }

    /**
     * `CREATE FUNCTION dmg_nextval` — atomically increments the row
     * and returns the previous value. Emitted with the
     * `DELIMITER //` wrapper because the multi-statement body needs
     * an explicit statement separator that MySQL's client honours.
     */
    fun nextvalRoutineSql(quoteIdentifier: (String) -> String): String = buildString {
        appendLine("DELIMITER //")
        appendLine("CREATE FUNCTION ${quoteIdentifier(MysqlSequenceNaming.NEXTVAL_ROUTINE)}(seq_name VARCHAR(255))")
        appendLine("RETURNS BIGINT")
        appendLine("DETERMINISTIC")
        appendLine("MODIFIES SQL DATA")
        appendLine("BEGIN")
        appendLine("    /* d-migrate:mysql-sequence-v1 object=nextval */")
        appendLine("    DECLARE val BIGINT;")
        appendLine(
            "    UPDATE ${quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE)} " +
                "SET ${quoteIdentifier("next_value")} = ${quoteIdentifier("next_value")} + " +
                "${quoteIdentifier("increment_by")} " +
                "WHERE ${quoteIdentifier("name")} = seq_name;"
        )
        appendLine(
            "    SELECT ${quoteIdentifier("next_value")} - ${quoteIdentifier("increment_by")} INTO val " +
                "FROM ${quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE)} " +
                "WHERE ${quoteIdentifier("name")} = seq_name;"
        )
        appendLine("    RETURN val;")
        appendLine("END //")
        append("DELIMITER ;")
    }

    /**
     * `CREATE FUNCTION dmg_setval` — sets and returns a new value
     * (PostgreSQL-`setval` semantics). Same delimiter convention as
     * [nextvalRoutineSql].
     */
    fun setvalRoutineSql(quoteIdentifier: (String) -> String): String = buildString {
        appendLine("DELIMITER //")
        appendLine(
            "CREATE FUNCTION ${quoteIdentifier(MysqlSequenceNaming.SETVAL_ROUTINE)}" +
                "(seq_name VARCHAR(255), new_value BIGINT)"
        )
        appendLine("RETURNS BIGINT")
        appendLine("DETERMINISTIC")
        appendLine("MODIFIES SQL DATA")
        appendLine("BEGIN")
        appendLine("    /* d-migrate:mysql-sequence-v1 object=setval */")
        appendLine(
            "    UPDATE ${quoteIdentifier(MysqlSequenceNaming.SUPPORT_TABLE)} " +
                "SET ${quoteIdentifier("next_value")} = new_value WHERE ${quoteIdentifier("name")} = seq_name;"
        )
        appendLine("    RETURN new_value;")
        appendLine("END //")
        append("DELIMITER ;")
    }

    /**
     * `CREATE TRIGGER dmg_seq_<table>_<col>_<hash>_bi` — fires
     * `dmg_nextval` when an INSERT leaves the sequence-backed column
     * NULL. The body marker keeps the trigger identifiable through
     * a round-trip via [MysqlSequenceReverseSupport]; the
     * [MysqlSequenceSqlCodec] handles literal / marker escaping.
     *
     * [triggerName] is computed by [MysqlSequenceNaming.triggerName]
     * — passed in so callers can also rebuild a known-good trigger
     * during reconcile.
     */
    fun sequenceTriggerSql(
        spec: MysqlSequenceTriggerSpec,
        triggerName: String,
        quoteIdentifier: (String) -> String,
    ): String = buildString {
        val sequenceLiteral = MysqlSequenceSqlCodec.quoteStringLiteral(spec.sequenceName)
        appendLine("DELIMITER //")
        appendLine("CREATE TRIGGER ${quoteIdentifier(triggerName)}")
        appendLine("    BEFORE INSERT ON ${quoteIdentifier(spec.tableName)}")
        appendLine("    FOR EACH ROW")
        appendLine("BEGIN")
        appendLine(
            "    /* d-migrate:mysql-sequence-v1 object=sequence-trigger " +
                "sequence=${MysqlSequenceSqlCodec.markerValue(spec.sequenceName)} " +
                "table=${MysqlSequenceSqlCodec.markerValue(spec.tableName)} " +
                "column=${MysqlSequenceSqlCodec.markerValue(spec.columnName)} */"
        )
        appendLine("    IF NEW.${quoteIdentifier(spec.columnName)} IS NULL THEN")
        appendLine(
            "        SET NEW.${quoteIdentifier(spec.columnName)} = " +
                "${quoteIdentifier(MysqlSequenceNaming.NEXTVAL_ROUTINE)}($sequenceLiteral);"
        )
        appendLine("    END IF;")
        appendLine("END //")
        append("DELIMITER ;")
    }
}
