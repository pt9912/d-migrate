package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 0.9.7 Cross-Dialect-Sequencing Sub-Slice B: pins the `W114`
 * cache-preallocation warning in the MySQL diff path. The full-schema
 * `MysqlSequenceDdlSupport.generateSequences` already emits W114 since
 * 0.9.4; this slice extends the contract to `CreateSequence` /
 * `AlterSequence` / `DropSequence` diff ops so an operator running
 * `schema migrate` sees the same metadata-only-cache warning the
 * full-schema generation surfaces.
 *
 * Direction-specific gate: W114 fires only when this direction's SQL
 * actually writes `cache_size` into `dmg_sequences`. UP of `Create`,
 * DOWN of `Drop`, and either side of `Alter` (when cache differs)
 * write the value; UP of `Drop` and DOWN of `Create` are DELETE-only
 * and must stay quiet.
 *
 * Carved out of [MysqlDiffSequenceOpsTest] to keep Detekt's `LargeClass`
 * gate happy without `@Suppress`, mirroring the existing split into
 * [MysqlDiffSequenceOpsDriftGateTest] and
 * [MysqlDiffSequenceOpsPreserveCurrentValueTest].
 */
class MysqlDiffSequenceOpsCacheWarningTest : FunSpec({

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
    ) = gen.generateUp(planner.plan(current, desired, diff), helperOptions)

    fun planAndDown(
        diff: SchemaDiff,
        current: SchemaDefinition = schemaOf(),
        desired: SchemaDefinition = schemaOf(),
    ) = gen.generateDown(planner.plan(current, desired, diff), helperOptions)

    // ── CreateSequence ───────────────────────────────────────────

    test("CreateSequence UP emits W114 when sequence has cache != null") {
        val seq = SequenceDefinition(start = 1L, cache = 50)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val r = planAndUp(diff, desired = schemaOf(mapOf("order_seq" to seq)))
        r.isBlocked shouldBe false
        r.diagnostics.any { it.code == "W114" && it.message.contains("order_seq") } shouldBe true
    }

    test("CreateSequence UP does NOT emit W114 when sequence has no cache") {
        val seq = SequenceDefinition(start = 1L, cache = null)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val r = planAndUp(diff, desired = schemaOf(mapOf("order_seq" to seq)))
        r.isBlocked shouldBe false
        r.diagnostics.none { it.code == "W114" } shouldBe true
    }

    test("CreateSequence DOWN does NOT emit W114 (DELETE only, no cache_size write)") {
        val seq = SequenceDefinition(start = 1L, cache = 50)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("order_seq", seq)))
        val r = planAndDown(diff, desired = schemaOf(mapOf("order_seq" to seq)))
        r.isBlocked shouldBe false
        r.diagnostics.none { it.code == "W114" } shouldBe true
    }

    // ── AlterSequence ────────────────────────────────────────────

    test("AlterSequence UP emits W114 when cache changed to non-null target") {
        val before = SequenceDefinition(start = 1L, cache = 10)
        val after = before.copy(cache = 50)
        val diff = SchemaDiff(
            sequencesChanged = listOf(SequenceDiff(name = "order_seq", cache = ValueChange(10, 50))),
        )
        val r = planAndUp(
            diff,
            current = schemaOf(mapOf("order_seq" to before)),
            desired = schemaOf(mapOf("order_seq" to after)),
        )
        r.diagnostics.any { it.code == "W114" && it.message.contains("cache=50") } shouldBe true
    }

    test("AlterSequence DOWN emits W114 with the restored (pre-Up) cache value") {
        val before = SequenceDefinition(start = 1L, cache = 10)
        val after = before.copy(cache = 50)
        val diff = SchemaDiff(
            sequencesChanged = listOf(SequenceDiff(name = "order_seq", cache = ValueChange(10, 50))),
        )
        val r = planAndDown(
            diff,
            current = schemaOf(mapOf("order_seq" to before)),
            desired = schemaOf(mapOf("order_seq" to after)),
        )
        r.diagnostics.any { it.code == "W114" && it.message.contains("cache=10") } shouldBe true
    }

    test("AlterSequence UP does NOT emit W114 when cache was nulled out (target=null)") {
        val before = SequenceDefinition(start = 1L, cache = 10)
        val after = before.copy(cache = null)
        val diff = SchemaDiff(
            sequencesChanged = listOf(SequenceDiff(name = "order_seq", cache = ValueChange(10, null))),
        )
        val r = planAndUp(
            diff,
            current = schemaOf(mapOf("order_seq" to before)),
            desired = schemaOf(mapOf("order_seq" to after)),
        )
        r.diagnostics.none { it.code == "W114" } shouldBe true
    }

    test("AlterSequence UP does NOT emit W114 when only non-cache fields differ") {
        val before = SequenceDefinition(start = 1L, increment = 1L, cache = 50)
        val after = before.copy(increment = 5L)
        val diff = SchemaDiff(
            sequencesChanged = listOf(SequenceDiff(name = "order_seq", increment = ValueChange(1L, 5L))),
        )
        val r = planAndUp(
            diff,
            current = schemaOf(mapOf("order_seq" to before)),
            desired = schemaOf(mapOf("order_seq" to after)),
        )
        r.diagnostics.none { it.code == "W114" } shouldBe true
    }

    // ── DropSequence ─────────────────────────────────────────────

    test("DropSequence DOWN emits W114 (rebuilds the row with the original cache value)") {
        val seq = SequenceDefinition(start = 1L, cache = 50)
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("order_seq", seq)))
        val r = planAndDown(diff, current = schemaOf(mapOf("order_seq" to seq)))
        r.isBlocked shouldBe false
        r.diagnostics.any { it.code == "W114" && it.message.contains("cache=50") } shouldBe true
    }

    test("DropSequence UP does NOT emit W114 (DELETE only, no cache_size write)") {
        val seq = SequenceDefinition(start = 1L, cache = 50)
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("order_seq", seq)))
        val r = planAndUp(diff, current = schemaOf(mapOf("order_seq" to seq)))
        r.isBlocked shouldBe false
        r.diagnostics.none { it.code == "W114" } shouldBe true
    }
})
