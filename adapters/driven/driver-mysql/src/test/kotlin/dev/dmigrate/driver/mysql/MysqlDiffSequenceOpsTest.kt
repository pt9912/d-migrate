package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * E.3 MySQL Sequence-Diff Sub-Slice B: pins Up/Down rendering per
 * `DiffOperation` subtype against the helper-table emulation. Lives
 * in its own file (analogous to the F.5 CheckExclude pattern) so the
 * sequence renderer's contract is reviewed independently from the
 * rest of the MySQL diff generator surface.
 */
class MysqlDiffSequenceOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()
    fun schemaOf(sequences: Map<String, SequenceDefinition> = emptyMap()) =
        SchemaDefinition(name = "App", version = "1", sequences = sequences)

    val helperOptions = DdlGenerationOptions(
        dialectContext = DdlDialectContext.MySql(namedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE),
    )

    fun planAndUp(
        diff: SchemaDiff,
        current: SchemaDefinition = schemaOf(),
        desired: SchemaDefinition = schemaOf(),
        options: DdlGenerationOptions = helperOptions,
    ) = gen.generateUp(planner.plan(current, desired, diff), options)

    fun planAndDown(
        diff: SchemaDiff,
        current: SchemaDefinition = schemaOf(),
        desired: SchemaDefinition = schemaOf(),
        options: DdlGenerationOptions = helperOptions,
    ) = gen.generateDown(planner.plan(current, desired, diff), options)

    // ── Mode gate ────────────────────────────────────────────────

    test("CreateSequence without --mysql-named-sequences helper_table blocks with E056") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val r = gen.generateUp(
            planner.plan(schemaOf(), schemaOf(mapOf("order_seq" to seq)), diff),
            DdlGenerationOptions(),
        )
        r.isBlocked shouldBe true
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "E056" } shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
    }

    // ── CreateSequence ───────────────────────────────────────────

    test("CreateSequence UP emits bootstrap + INSERT INTO dmg_sequences") {
        val seq = SequenceDefinition(start = 100L, increment = 2L, minValue = 1L, maxValue = 999L, cycle = true, cache = 50)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val r = planAndUp(diff, desired = schemaOf(mapOf("order_seq" to seq)))
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        sqls.any { it.contains("CREATE TABLE IF NOT EXISTS `dmg_sequences`") } shouldBe true
        sqls.any { it.contains("CREATE FUNCTION `dmg_nextval`") } shouldBe true
        sqls.any { it.contains("CREATE FUNCTION `dmg_setval`") } shouldBe true
        sqls.any {
            it.contains("INSERT INTO `dmg_sequences`") &&
                it.contains("'order_seq', 100, 2, 1, 999, 1, 50")
        } shouldBe true
    }

    test("CreateSequence DOWN emits DELETE only — no bootstrap teardown") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val r = planAndDown(diff, desired = schemaOf(mapOf("order_seq" to seq)))
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        sqls.none { it.contains("CREATE TABLE IF NOT EXISTS `dmg_sequences`") } shouldBe true
        sqls.single() shouldBe
            "DELETE FROM `dmg_sequences` WHERE `name` = 'order_seq';"
    }

    test("Diff renderer emits delimiterfreie routines + triggers (Sub-Slice H)") {
        // Sub-Slice H: `schema migrate --execute` submits each
        // `MigrationDdlStatement.sql` directly to JDBC, which has
        // no DELIMITER concept. None of the emitted statements
        // may contain `DELIMITER //` or `DELIMITER ;` substrings —
        // the diff path must use the delimiterfreien templates.
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val r = planAndUp(diff, desired = schemaOf(mapOf("order_seq" to seq)))
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        sqls.none { it.contains("DELIMITER //") } shouldBe true
        sqls.none { it.contains("DELIMITER ;") } shouldBe true
        // Routine bodies still appear as single statements that JDBC
        // submits in one go (multi-statement BEGIN…END is a single
        // logical MySQL statement).
        sqls.any { it.startsWith("CREATE FUNCTION `dmg_nextval`") && it.endsWith("END") } shouldBe true
    }

    test("Two CreateSequence ops in one migration emit the bootstrap exactly once") {
        val a = SequenceDefinition(start = 1L)
        val b = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(
            sequencesAdded = listOf(
                NamedSequence("seq_a", a),
                NamedSequence("seq_b", b),
            ),
        )
        val r = planAndUp(diff, desired = schemaOf(mapOf("seq_a" to a, "seq_b" to b)))
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        sqls.count { it.contains("CREATE TABLE IF NOT EXISTS `dmg_sequences`") } shouldBe 1
        sqls.count { it.contains("CREATE FUNCTION `dmg_nextval`") } shouldBe 1
        sqls.count { it.contains("CREATE FUNCTION `dmg_setval`") } shouldBe 1
        sqls.count { it.contains("INSERT INTO `dmg_sequences`") } shouldBe 2
    }

    // ── AlterSequence ────────────────────────────────────────────

    test("AlterSequence UP emits UPDATE on managed fields toward op.after") {
        val before = SequenceDefinition(start = 1L, increment = 1L, minValue = 1L, maxValue = 1000L, cycle = false, cache = 10)
        val after = before.copy(increment = 5L, maxValue = 5000L, cycle = true, cache = 50)
        val diff = SchemaDiff(
            sequencesChanged = listOf(
                SequenceDiff(
                    name = "order_seq",
                    increment = ValueChange(1L, 5L),
                    maxValue = ValueChange(1000L, 5000L),
                    cycle = ValueChange(false, true),
                    cache = ValueChange(10, 50),
                ),
            ),
        )
        val r = planAndUp(
            diff,
            current = schemaOf(mapOf("order_seq" to before)),
            desired = schemaOf(mapOf("order_seq" to after)),
        )
        r.isBlocked shouldBe false
        val sql = r.statements.single().sql
        sql shouldContainStr "UPDATE `dmg_sequences`"
        sql shouldContainStr "`increment_by` = 5"
        sql shouldContainStr "`max_value` = 5000"
        sql shouldContainStr "`cycle_enabled` = 1"
        sql shouldContainStr "`cache_size` = 50"
        sql shouldContainStr "WHERE `name` = 'order_seq'"
    }

    test("AlterSequence DOWN reverses to op.before values") {
        val before = SequenceDefinition(start = 1L, increment = 1L, minValue = 1L, maxValue = 1000L, cycle = false, cache = 10)
        val after = before.copy(increment = 5L)
        val diff = SchemaDiff(
            sequencesChanged = listOf(
                SequenceDiff(name = "order_seq", increment = ValueChange(1L, 5L)),
            ),
        )
        val r = planAndDown(
            diff,
            current = schemaOf(mapOf("order_seq" to before)),
            desired = schemaOf(mapOf("order_seq" to after)),
        )
        r.isBlocked shouldBe false
        val sql = r.statements.single().sql
        sql shouldContainStr "`increment_by` = 1"
    }

    test("AlterSequence UP delta SET only contains the fields that actually differ") {
        // Only `cycle` changes; the SQL must SET exactly one field.
        val before = SequenceDefinition(start = 1L, increment = 1L, minValue = 1L, maxValue = 1000L, cycle = false, cache = 10)
        val after = before.copy(cycle = true)
        val diff = SchemaDiff(
            sequencesChanged = listOf(SequenceDiff(name = "order_seq", cycle = ValueChange(false, true))),
        )
        val r = planAndUp(
            diff,
            current = schemaOf(mapOf("order_seq" to before)),
            desired = schemaOf(mapOf("order_seq" to after)),
        )
        val sql = r.statements.single().sql
        sql shouldContainStr "`cycle_enabled` = 1"
        // No other field surfaces in the SET list.
        sql.contains("`increment_by`") shouldBe false
        sql.contains("`min_value`") shouldBe false
        sql.contains("`max_value`") shouldBe false
        sql.contains("`cache_size`") shouldBe false
    }

    test("AlterSequence renders NULL fallbacks when fields are nulled out") {
        // before has populated min/max/cache; after sets all three to NULL.
        val before = SequenceDefinition(start = 1L, increment = 1L, minValue = 1L, maxValue = 1000L, cycle = false, cache = 10)
        val after = SequenceDefinition(start = 1L, increment = 1L, minValue = null, maxValue = null, cycle = false, cache = null)
        val diff = SchemaDiff(
            sequencesChanged = listOf(
                SequenceDiff(
                    name = "order_seq",
                    minValue = ValueChange(1L, null),
                    maxValue = ValueChange(1000L, null),
                    cache = ValueChange(10, null),
                ),
            ),
        )
        val r = planAndUp(
            diff,
            current = schemaOf(mapOf("order_seq" to before)),
            desired = schemaOf(mapOf("order_seq" to after)),
        )
        val sql = r.statements.single().sql
        sql shouldContainStr "`min_value` = NULL"
        sql shouldContainStr "`max_value` = NULL"
        sql shouldContainStr "`cache_size` = NULL"
    }

    // ── DropSequence ─────────────────────────────────────────────

    test("DropSequence UP emits DELETE on dmg_sequences row") {
        val seq = SequenceDefinition(start = 1L)
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("order_seq", seq)))
        val r = planAndUp(diff, current = schemaOf(mapOf("order_seq" to seq)))
        r.isBlocked shouldBe false
        val sql = r.statements.single().sql
        sql shouldBe "DELETE FROM `dmg_sequences` WHERE `name` = 'order_seq';"
    }

    test("DropSequence DOWN re-emits bootstrap + INSERT (no triggers when no column binds)") {
        val seq = SequenceDefinition(start = 42L, increment = 1L)
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("order_seq", seq)))
        val r = planAndDown(diff, current = schemaOf(mapOf("order_seq" to seq)))
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        sqls.any { it.contains("CREATE TABLE IF NOT EXISTS `dmg_sequences`") } shouldBe true
        sqls.any { it.contains("INSERT INTO `dmg_sequences`") && it.contains("'order_seq', 42") } shouldBe true
    }

    test("DropSequence UP drops every column-bound trigger before deleting the row") {
        // Two columns reference the dropped sequence via
        // SequenceNextVal defaults. The renderer must emit a
        // `DROP TRIGGER IF EXISTS` per binding from `currentSchema`
        // and only then `DELETE FROM dmg_sequences`.
        val seq = SequenceDefinition(start = 1L)
        val ordersTable = dev.dmigrate.core.model.TableDefinition(
            columns = mapOf(
                "id" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.BigInteger,
                    required = true,
                    default = dev.dmigrate.core.model.DefaultValue.SequenceNextVal("order_seq"),
                ),
            ),
        )
        val invoicesTable = dev.dmigrate.core.model.TableDefinition(
            columns = mapOf(
                "ref" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.BigInteger,
                    default = dev.dmigrate.core.model.DefaultValue.SequenceNextVal("order_seq"),
                ),
            ),
        )
        val current = SchemaDefinition(
            name = "App",
            version = "1",
            sequences = mapOf("order_seq" to seq),
            tables = mapOf("orders" to ordersTable, "invoices" to invoicesTable),
        )
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("order_seq", seq)))
        val r = planAndUp(diff, current = current)
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        val ordersTrigger = MysqlSequenceNaming.triggerName("orders", "id")
        val invoicesTrigger = MysqlSequenceNaming.triggerName("invoices", "ref")
        sqls.any { it == "DROP TRIGGER IF EXISTS `$ordersTrigger`;" } shouldBe true
        sqls.any { it == "DROP TRIGGER IF EXISTS `$invoicesTrigger`;" } shouldBe true
        // The row delete is the final statement.
        sqls.last() shouldBe "DELETE FROM `dmg_sequences` WHERE `name` = 'order_seq';"
    }

    test("DropSequence DOWN re-creates every column-bound trigger from currentSchema") {
        // The Down inverse re-emits bootstrap + INSERT + the same
        // triggers that the UP path dropped. The trigger spec MUST
        // come from `currentSchema` (pre-Up state, where the column
        // bindings exist) — `desiredSchema` is post-Up and would
        // yield zero triggers.
        val seq = SequenceDefinition(start = 7L)
        val ordersTable = dev.dmigrate.core.model.TableDefinition(
            columns = mapOf(
                "id" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.BigInteger,
                    required = true,
                    default = dev.dmigrate.core.model.DefaultValue.SequenceNextVal("order_seq"),
                ),
            ),
        )
        val current = SchemaDefinition(
            name = "App",
            version = "1",
            sequences = mapOf("order_seq" to seq),
            tables = mapOf("orders" to ordersTable),
        )
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("order_seq", seq)))
        val r = planAndDown(diff, current = current)
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        sqls.any { it.contains("CREATE TABLE IF NOT EXISTS `dmg_sequences`") } shouldBe true
        sqls.any { it.contains("INSERT INTO `dmg_sequences`") && it.contains("'order_seq', 7") } shouldBe true
        val ordersTrigger = MysqlSequenceNaming.triggerName("orders", "id")
        sqls.any { it.contains("CREATE TRIGGER `$ordersTrigger`") } shouldBe true
    }

    // ── RenameSequence (defensive regression guard) ──────────────

    test("RenameSequence defensive renderer emits UPDATE name + trigger rebuild for a bound column") {
        // Sub-Slice C: `MysqlObjectRenamePolicy` now returns
        // `DropCreateFallback`, so the Mapper decomposes sequence
        // renames into DropSequence + CreateSequence. The
        // `RenameSequence` op type should therefore not reach the
        // renderer under normal flow. The defensive path stays as a
        // regression guard — if a planner ever emits it directly,
        // the renderer rebuilds the helper-table row + every bound
        // trigger so the migration is at least self-consistent.
        val orders = dev.dmigrate.core.model.TableDefinition(
            columns = mapOf(
                "id" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.BigInteger,
                    required = true,
                    default = dev.dmigrate.core.model.DefaultValue.SequenceNextVal("old_seq"),
                ),
            ),
        )
        val currentSchema = SchemaDefinition(
            name = "App",
            version = "1",
            sequences = mapOf("old_seq" to SequenceDefinition(start = 1L)),
            tables = mapOf("orders" to orders),
        )
        val ctx = MysqlDiffRenderContext(
            direction = MysqlRenderDirection.UP,
            sql = MysqlDiffSqlBuilders(MysqlTypeMapper()),
            options = helperOptions,
            currentSchema = currentSchema,
        )
        val op = DiffOperation.RenameSequence(
            id = "rename-seq",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("new_seq")),
            fromName = "old_seq",
            toName = "new_seq",
            overlaySource = "test",
            overlayEntryId = "test#0",
            overlayHash = null,
        )
        MysqlDiffSequenceOps.renderRenameSequence(op, ctx)
        val plan = dev.dmigrate.core.diff.migration.DiffResult(
            current = dev.dmigrate.core.diff.migration.DiffEndpoint(schemaName = "App"),
            desired = dev.dmigrate.core.diff.migration.DiffEndpoint(schemaName = "App"),
            schemaDiff = SchemaDiff(),
            operations = listOf(op),
            diagnostics = emptyList(),
        )
        val result = ctx.toResult(plan)
        result.isBlocked shouldBe false
        val sqls = result.statements.map { it.sql }
        // 1) Rename the helper-table row.
        sqls.first() shouldContainStr
            "UPDATE `dmg_sequences` SET `name` = 'new_seq' WHERE `name` = 'old_seq'"
        // 2) For each bound column: drop the trigger, then recreate
        //    with the new sequence literal in the body.
        val triggerName = MysqlSequenceNaming.triggerName("orders", "id")
        sqls.any { it == "DROP TRIGGER IF EXISTS `$triggerName`;" } shouldBe true
        sqls.any {
            it.contains("CREATE TRIGGER `$triggerName`") &&
                it.contains("`dmg_nextval`('new_seq')")
        } shouldBe true
    }

    // ── E.3 Sub-Slice F: start-only AlterSequence + column-defaults ──────

    test("AlterSequence with runtime-state-only delta skips with INFO + no SQL") {
        // SequenceDiff carrying only a `start` change should not
        // crash and should not silently disappear from the report:
        // the renderer emits an INFO-severity diagnostic, marks the
        // op as skipped, and does NOT add a blocker.
        val before = SequenceDefinition(start = 1L, increment = 1L)
        val after = before.copy(start = 100L)
        val diff = SchemaDiff(
            sequencesChanged = listOf(
                SequenceDiff(name = "order_seq", start = ValueChange(1L, 100L)),
            ),
        )
        val r = planAndUp(
            diff,
            current = schemaOf(mapOf("order_seq" to before)),
            desired = schemaOf(mapOf("order_seq" to after)),
        )
        r.isBlocked shouldBe false
        r.statements.shouldBeEmpty()
        r.diagnostics.any {
            it.code == MysqlDiffSequenceOps.RUNTIME_STATE_NO_OP_CODE &&
                it.severity == dev.dmigrate.core.diff.migration.DiffDiagnostic.Severity.INFO
        } shouldBe true
    }

    test("CreateTable with SequenceNextVal column default emits trigger after CREATE TABLE") {
        // E.3 Sub-Slice F core fix: the diff renderer must NOT route
        // a SequenceNextVal default through `toDefaultSql` (which
        // crashes on MySQL by contract). Instead the column line
        // omits the `DEFAULT` clause and the renderer emits a
        // BEFORE INSERT trigger that calls `dmg_nextval`.
        val seq = SequenceDefinition(start = 1L)
        val orders = dev.dmigrate.core.model.TableDefinition(
            columns = mapOf(
                "id" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.BigInteger,
                    required = true,
                    default = dev.dmigrate.core.model.DefaultValue.SequenceNextVal("order_seq"),
                ),
                "name" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.Text(),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to seq),
            tables = mapOf("orders" to orders),
        )
        val r = planAndUp(
            SchemaDiff(
                sequencesAdded = listOf(NamedSequence("order_seq", seq)),
                tablesAdded = listOf(dev.dmigrate.core.diff.NamedTable("orders", orders)),
            ),
            desired = desired,
        )
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        // Bootstrap + sequence INSERT come first (Sequence-phase ops).
        sqls.any { it.contains("CREATE TABLE IF NOT EXISTS `dmg_sequences`") } shouldBe true
        // CREATE TABLE column line must NOT contain a DEFAULT clause
        // for the sequence-defaulted column.
        val createTableSql = sqls.first { it.contains("CREATE TABLE `orders`") }
        createTableSql.contains("DEFAULT") shouldBe false
        // Per sequence-defaulted column: DROP TRIGGER IF EXISTS +
        // CREATE TRIGGER.
        val triggerName = MysqlSequenceNaming.triggerName("orders", "id")
        sqls.any { it == "DROP TRIGGER IF EXISTS `$triggerName`;" } shouldBe true
        sqls.any {
            it.contains("CREATE TRIGGER `$triggerName`") &&
                it.contains("`dmg_nextval`('order_seq')")
        } shouldBe true
    }

    test("AddColumn with SequenceNextVal default emits trigger after ADD COLUMN") {
        val seq = SequenceDefinition(start = 1L)
        val before = dev.dmigrate.core.model.TableDefinition(
            columns = mapOf(
                "id" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.BigInteger,
                    required = true,
                ),
            ),
            primaryKey = listOf("id"),
        )
        val after = before.copy(
            columns = before.columns + mapOf(
                "seq_id" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.BigInteger,
                    default = dev.dmigrate.core.model.DefaultValue.SequenceNextVal("order_seq"),
                ),
            ),
        )
        val current = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to seq),
            tables = mapOf("orders" to before),
        )
        val desired = current.copy(tables = mapOf("orders" to after))
        val r = planAndUp(
            SchemaDiff(
                tablesChanged = listOf(
                    dev.dmigrate.core.diff.TableDiff(
                        name = "orders",
                        columnsAdded = mapOf(
                            "seq_id" to after.columns.getValue("seq_id"),
                        ),
                    ),
                ),
            ),
            current = current,
            desired = desired,
        )
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        val addColumn = sqls.first { it.contains("ADD COLUMN") }
        addColumn.contains("DEFAULT") shouldBe false
        val triggerName = MysqlSequenceNaming.triggerName("orders", "seq_id")
        sqls.any { it.contains("CREATE TRIGGER `$triggerName`") } shouldBe true
    }

    test("CreateTable with SequenceNextVal default blocks E056 when mode is not helper_table") {
        val seq = SequenceDefinition(start = 1L)
        val orders = dev.dmigrate.core.model.TableDefinition(
            columns = mapOf(
                "id" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.BigInteger,
                    required = true,
                    default = dev.dmigrate.core.model.DefaultValue.SequenceNextVal("order_seq"),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("order_seq" to seq),
            tables = mapOf("orders" to orders),
        )
        val r = gen.generateUp(
            planner.plan(
                schemaOf(),
                desired,
                SchemaDiff(
                    sequencesAdded = listOf(NamedSequence("order_seq", seq)),
                    tablesAdded = listOf(dev.dmigrate.core.diff.NamedTable("orders", orders)),
                ),
            ),
            DdlGenerationOptions(), // default = ACTION_REQUIRED
        )
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "E056" } shouldBe true
        r.statements.any { it.sql.contains("CREATE TABLE `orders`") } shouldBe false
    }

    test("AlterColumnDefault — SequenceNextVal → constant drops trigger then SET DEFAULT") {
        val seq = SequenceDefinition(start = 1L)
        val beforeCol = dev.dmigrate.core.model.ColumnDefinition(
            type = dev.dmigrate.core.model.NeutralType.BigInteger,
            default = dev.dmigrate.core.model.DefaultValue.SequenceNextVal("order_seq"),
        )
        val afterCol = beforeCol.copy(default = dev.dmigrate.core.model.DefaultValue.NumberLiteral(42))
        val before = dev.dmigrate.core.model.TableDefinition(columns = mapOf("seq_id" to beforeCol))
        val after = before.copy(columns = mapOf("seq_id" to afterCol))
        val r = planAndUp(
            SchemaDiff(
                tablesChanged = listOf(
                    dev.dmigrate.core.diff.TableDiff(
                        name = "orders",
                        columnsChanged = listOf(
                            dev.dmigrate.core.diff.ColumnDiff(
                                name = "seq_id",
                                default = ValueChange(beforeCol.default, afterCol.default),
                            ),
                        ),
                    ),
                ),
            ),
            current = SchemaDefinition(
                name = "App", version = "1",
                sequences = mapOf("order_seq" to seq),
                tables = mapOf("orders" to before),
            ),
            desired = SchemaDefinition(
                name = "App", version = "1",
                sequences = mapOf("order_seq" to seq),
                tables = mapOf("orders" to after),
            ),
        )
        r.isBlocked shouldBe false
        val sqls = r.statements.map { it.sql }
        val triggerName = MysqlSequenceNaming.triggerName("orders", "seq_id")
        // Drop the trigger first.
        sqls.any { it == "DROP TRIGGER IF EXISTS `$triggerName`;" } shouldBe true
        // Then SET DEFAULT '42'.
        sqls.any { it.contains("SET DEFAULT") && it.contains("42") } shouldBe true
    }

    test("RenameSequence defensive renderer DOWN reads bindings from desiredSchema") {
        // The DOWN path swaps from/to: `oldName = op.toName`,
        // `newName = op.fromName`. The bindings to look up are the
        // ones still active POST-Up — `new_seq` — which the renderer
        // finds in `desiredSchema` (the rename was applied; bindings
        // there reference `new_seq`). Verify that the renderer picks
        // the right side and rewrites the trigger back to
        // `dmg_nextval('old_seq')`.
        val orders = dev.dmigrate.core.model.TableDefinition(
            columns = mapOf(
                "id" to dev.dmigrate.core.model.ColumnDefinition(
                    type = dev.dmigrate.core.model.NeutralType.BigInteger,
                    required = true,
                    default = dev.dmigrate.core.model.DefaultValue.SequenceNextVal("new_seq"),
                ),
            ),
        )
        val desiredSchema = SchemaDefinition(
            name = "App",
            version = "1",
            sequences = mapOf("new_seq" to SequenceDefinition(start = 1L)),
            tables = mapOf("orders" to orders),
        )
        val ctx = MysqlDiffRenderContext(
            direction = MysqlRenderDirection.DOWN,
            sql = MysqlDiffSqlBuilders(MysqlTypeMapper()),
            options = helperOptions,
            desiredSchema = desiredSchema,
        )
        val op = DiffOperation.RenameSequence(
            id = "rename-seq",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("new_seq")),
            fromName = "old_seq",
            toName = "new_seq",
            overlaySource = "test",
            overlayEntryId = "test#0",
            overlayHash = null,
        )
        MysqlDiffSequenceOps.renderRenameSequence(op, ctx)
        val plan = dev.dmigrate.core.diff.migration.DiffResult(
            current = dev.dmigrate.core.diff.migration.DiffEndpoint(schemaName = "App"),
            desired = dev.dmigrate.core.diff.migration.DiffEndpoint(schemaName = "App"),
            schemaDiff = SchemaDiff(),
            operations = listOf(op),
            diagnostics = emptyList(),
        )
        val result = ctx.toResult(plan)
        result.isBlocked shouldBe false
        val sqls = result.statements.map { it.sql }
        // 1) Rename the row backwards: new → old.
        sqls.first() shouldContainStr
            "UPDATE `dmg_sequences` SET `name` = 'old_seq' WHERE `name` = 'new_seq'"
        // 2) Trigger name stable; body literal flips back to old_seq.
        val triggerName = MysqlSequenceNaming.triggerName("orders", "id")
        sqls.any { it == "DROP TRIGGER IF EXISTS `$triggerName`;" } shouldBe true
        sqls.any {
            it.contains("CREATE TRIGGER `$triggerName`") &&
                it.contains("`dmg_nextval`('old_seq')")
        } shouldBe true
    }
})
