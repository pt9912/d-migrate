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
        mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE,
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
        sqls.any { it.contains("CREATE TABLE `dmg_sequences`") } shouldBe true
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
        sqls.none { it.contains("CREATE TABLE `dmg_sequences`") } shouldBe true
        sqls.single() shouldBe
            "DELETE FROM `dmg_sequences` WHERE `name` = 'order_seq';"
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
        sqls.count { it.contains("CREATE TABLE `dmg_sequences`") } shouldBe 1
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
        sqls.any { it.contains("CREATE TABLE `dmg_sequences`") } shouldBe true
        sqls.any { it.contains("INSERT INTO `dmg_sequences`") && it.contains("'order_seq', 42") } shouldBe true
    }

    // ── RenameSequence (defensive only) ──────────────────────────

    test("RenameSequence defensive renderer emits UPDATE name via a synthetic op") {
        // The Mapper's `MysqlObjectRenamePolicy.classify(SEQUENCE, ...)`
        // still blocks today; Sub-Slice C lifts it to
        // `DropCreateFallback`. Pin the defensive path by driving the
        // renderer directly with a synthetic `RenameSequence` op so
        // the regression coverage holds through both phases.
        val ctx = MysqlDiffRenderContext(
            direction = MysqlRenderDirection.UP,
            sql = MysqlDiffSqlBuilders(MysqlTypeMapper()),
            options = helperOptions,
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
        val sql = result.statements.single().sql
        sql shouldContainStr "UPDATE `dmg_sequences` SET `name` = 'new_seq' WHERE `name` = 'old_seq'"
    }
})
