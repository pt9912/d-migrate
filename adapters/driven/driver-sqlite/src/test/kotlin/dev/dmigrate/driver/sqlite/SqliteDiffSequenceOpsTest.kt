package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * 0.9.7 Phase F2 unit coverage for [SqliteDiffSequenceOps]. Drives
 * the dispatcher in [SqliteDiffDdlGenerator] through a tiny synthetic
 * `DiffResult` so each render method's UP/DOWN behaviour and the
 * action_required-mode gating are pinned without depending on the
 * full planner.
 */
class SqliteDiffSequenceOpsTest : FunSpec({

    val gen = SqliteDiffDdlGenerator()

    fun helperOpts() = DdlGenerationOptions(
        dialectContext = DdlDialectContext.Sqlite(namedSequenceMode = SqliteNamedSequenceMode.HELPER_TABLE),
    )

    fun seqRef(name: String) = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(name))

    fun seqObjRef(name: String) = SequenceObjectRef(name, null, RenameProjectionDialect.SQLITE)

    fun runUp(ops: List<DiffOperation>, opts: DdlGenerationOptions = helperOpts()) = gen.generateUp(
        DiffResult(
            current = DiffEndpoint("A", "1", "c"),
            desired = DiffEndpoint("A", "1", "d"),
            schemaDiff = SchemaDiff(),
            operations = ops,
        ),
        opts,
    )

    fun runDown(ops: List<DiffOperation>, opts: DdlGenerationOptions = helperOpts()) = gen.generateDown(
        DiffResult(
            current = DiffEndpoint("A", "1", "c"),
            desired = DiffEndpoint("A", "1", "d"),
            schemaDiff = SchemaDiff(),
            operations = ops,
        ),
        opts,
    )

    // ── CreateSequence ─────────────────────────────────────────────

    test("CreateSequence UP — emits bootstrap + INSERT") {
        val op = DiffOperation.CreateSequence(
            id = "cs1",
            objectRef = seqRef("order_seq"),
            sequence = SequenceDefinition(start = 1000, increment = 2),
        )
        val result = runUp(listOf(op))
        val sqls = result.statements.map { it.sql }
        sqls.any { it.contains("CREATE TABLE IF NOT EXISTS \"dmg_sequences\"") } shouldBe true
        sqls.any { it.contains("INSERT INTO \"dmg_sequences\"") && it.contains("'order_seq'") } shouldBe true
    }

    test("CreateSequence DOWN — emits DELETE") {
        val op = DiffOperation.CreateSequence(
            id = "cs1",
            objectRef = seqRef("order_seq"),
            sequence = SequenceDefinition(start = 1000),
        )
        val result = runDown(listOf(op))
        val sqls = result.statements.map { it.sql }
        sqls.any { it.contains("DELETE FROM \"dmg_sequences\"") && it.contains("'order_seq'") } shouldBe true
    }

    test("CreateSequence in action_required mode — MANUAL_ACTION_REQUIRED with opt-in hint") {
        val op = DiffOperation.CreateSequence(
            id = "cs1",
            objectRef = seqRef("order_seq"),
            sequence = SequenceDefinition(start = 1),
        )
        val result = runUp(listOf(op), DdlGenerationOptions())
        result.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }

    // ── AlterSequence ──────────────────────────────────────────────

    test("AlterSequence UP — emits UPDATE for changed fields") {
        val op = DiffOperation.AlterSequence(
            id = "as1",
            objectRef = seqRef("s"),
            before = SequenceDefinition(start = 1, increment = 1, cycle = false),
            after = SequenceDefinition(start = 1, increment = 5, cycle = true, maxValue = 999),
        )
        val result = runUp(listOf(op))
        val sql = result.statements.first().sql
        sql shouldContain "UPDATE \"dmg_sequences\""
        sql shouldContain "\"increment_by\" = 5"
        sql shouldContain "\"cycle_enabled\" = 1"
        sql shouldContain "\"max_value\" = 999"
    }

    test("AlterSequence DOWN — swaps before/after") {
        val op = DiffOperation.AlterSequence(
            id = "as1",
            objectRef = seqRef("s"),
            before = SequenceDefinition(start = 1, increment = 1),
            after = SequenceDefinition(start = 1, increment = 5),
        )
        val result = runDown(listOf(op))
        val sql = result.statements.first().sql
        sql shouldContain "\"increment_by\" = 1"
    }

    test("AlterSequence with empty managed-field delta — skipped with diagnostic") {
        val op = DiffOperation.AlterSequence(
            id = "as1",
            objectRef = seqRef("s"),
            before = SequenceDefinition(start = 1, increment = 1),
            after = SequenceDefinition(start = 1, increment = 1),
        )
        val result = runUp(listOf(op))
        result.statements.none { it.sql.contains("UPDATE") } shouldBe true
    }

    // ── DropSequence ───────────────────────────────────────────────

    test("DropSequence UP — emits DELETE") {
        val op = DiffOperation.DropSequence(
            id = "ds1",
            objectRef = seqRef("s"),
            sequence = SequenceDefinition(start = 1),
        )
        val result = runUp(listOf(op))
        val sqls = result.statements.map { it.sql }
        sqls.any { it.contains("DELETE FROM \"dmg_sequences\"") && it.contains("'s'") } shouldBe true
    }

    test("DropSequence DOWN — emits bootstrap + INSERT (re-creates)") {
        val op = DiffOperation.DropSequence(
            id = "ds1",
            objectRef = seqRef("s"),
            sequence = SequenceDefinition(start = 1000),
        )
        val result = runDown(listOf(op))
        val sqls = result.statements.map { it.sql }
        sqls.any { it.contains("CREATE TABLE IF NOT EXISTS \"dmg_sequences\"") } shouldBe true
        sqls.any { it.contains("INSERT INTO \"dmg_sequences\"") } shouldBe true
    }

    // ── RenameSequence ────────────────────────────────────────────

    test("RenameSequence UP — emits UPDATE name") {
        val op = DiffOperation.RenameSequence(
            id = "rn1",
            objectRef = seqRef("old"),
            fromName = "old",
            toName = "new",
            overlaySource = "overlay",
            overlayEntryId = "rn1",
            overlayHash = null,
        )
        val result = runUp(listOf(op))
        val sql = result.statements.first().sql
        sql shouldContain "UPDATE \"dmg_sequences\""
        sql shouldContain "\"name\" = 'new'"
        sql shouldContain "WHERE \"name\" = 'old'"
    }

    test("RenameSequence DOWN — flips old/new") {
        val op = DiffOperation.RenameSequence(
            id = "rn1",
            objectRef = seqRef("old"),
            fromName = "old",
            toName = "new",
            overlaySource = "overlay",
            overlayEntryId = "rn1",
            overlayHash = null,
        )
        val result = runDown(listOf(op))
        val sql = result.statements.first().sql
        sql shouldContain "\"name\" = 'old'"
        sql shouldContain "WHERE \"name\" = 'new'"
    }

    // ── AlterSequenceCurrentValue ─────────────────────────────────

    test("AlterSequenceCurrentValue UP — emits UPDATE next_value") {
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "acv1",
            objectRef = seqRef("s"),
            pairId = "alter:s",
            probeSequenceRef = seqObjRef("s"),
            applySequenceRef = seqObjRef("s"),
            currentValue = 5000L,
        )
        val result = runUp(listOf(op))
        val sql = result.statements.first().sql
        sql shouldContain "UPDATE \"dmg_sequences\""
        sql shouldContain "\"next_value\" = 5000"
        sql shouldContain "WHERE \"name\" = 's'"
    }

    test("AlterSequenceCurrentValue DOWN — no SQL (carve-out for cross-dialect follow-up)") {
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "acv1",
            objectRef = seqRef("s"),
            pairId = "alter:s",
            probeSequenceRef = seqObjRef("s"),
            applySequenceRef = seqObjRef("s"),
            currentValue = 5000L,
        )
        val result = runDown(listOf(op))
        result.statements.none { it.sql.contains("UPDATE") } shouldBe true
    }

    // ── escapeLiteral edge cases ───────────────────────────────────

    test("sequence name with embedded apostrophe is properly escaped in WHERE clause") {
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "acv1",
            objectRef = seqRef("o'reilly_seq"),
            pairId = "alter:o'reilly_seq",
            probeSequenceRef = seqObjRef("o'reilly_seq"),
            applySequenceRef = seqObjRef("o'reilly_seq"),
            currentValue = 1L,
        )
        val result = runUp(listOf(op))
        val sql = result.statements.first().sql
        sql shouldContain "WHERE \"name\" = 'o''reilly_seq'"
    }
})

