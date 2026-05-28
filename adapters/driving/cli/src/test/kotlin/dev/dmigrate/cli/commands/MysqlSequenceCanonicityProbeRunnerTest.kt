package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.MysqlSequenceCanonicityDeclaration
import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityProbe
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
import dev.dmigrate.driver.mysql.MysqlSequenceNaming
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files

/**
 * Unit tests for [MysqlSequenceCanonicityProbeRunner].
 *
 * The runner ships two layers:
 *
 * 1. `collect(probe, plan)` — pure dispatch over the [DiffResult.operations]
 *    list; per op kind it calls a specific subset of the
 *    [MysqlSequenceCanonicityProbe] interface. This file covers all six
 *    op kinds (4 sequence ops + 2 column ops) plus the no-op fallthrough.
 *
 * 2. `probe(target, configPath, plan)` — outer wrapper that resolves the
 *    connection URL, builds a Hikari pool, and feeds a real
 *    [MysqlSequenceCanonicityProbeAdapter] into `collect`. We exercise
 *    only the two `CompareConfigException` branches here; the Hikari +
 *    real-MySQL path lives in `:test:integration-mysql` per project
 *    precedent (`SqliteCastPreflightProbeRunner` / `SequenceCurrentValueProbeRunner`).
 */
class MysqlSequenceCanonicityProbeRunnerTest : FunSpec({

    fun decl(
        opId: String,
        kind: MysqlSequenceCanonicityKind,
        name: String,
        status: MysqlSequenceCanonicityStatus = MysqlSequenceCanonicityStatus.CANONICAL,
    ) = MysqlSequenceCanonicityDeclaration(
        operationId = opId,
        dialect = "mysql",
        kind = kind,
        objectName = name,
        status = status,
        sqlHash = "h-$opId-${kind.name}",
    )

    fun probe(): MysqlSequenceCanonicityProbe {
        val m = mockk<MysqlSequenceCanonicityProbe>(relaxed = true)
        every {
            m.probeSupportTable(any())
        } answers { decl(firstArg(), MysqlSequenceCanonicityKind.SUPPORT_TABLE, "dmg_sequences") }
        every {
            m.probeRoutine(any(), any())
        } answers {
            val opId = firstArg<String>()
            val kind = secondArg<MysqlSequenceCanonicityKind>()
            val routineName = if (kind == MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE) "dmg_nextval" else "dmg_setval"
            decl(opId, kind, routineName)
        }
        every {
            m.probeSequenceRow(any(), any(), any(), any(), any(), any(), any())
        } answers { decl(firstArg(), MysqlSequenceCanonicityKind.SEQUENCE_ROW, secondArg()) }
        every {
            m.probeSupportTrigger(any(), any(), any())
        } answers { decl(firstArg(), MysqlSequenceCanonicityKind.SUPPORT_TRIGGER, secondArg()) }
        return m
    }

    fun planOf(vararg ops: DiffOperation): DiffResult = DiffResult(
        current = DiffEndpoint("acme", schemaVersion = "1"),
        desired = DiffEndpoint("acme", schemaVersion = "2"),
        schemaDiff = SchemaDiff(),
        operations = ops.toList(),
    )

    val sequenceRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("order_seq"))
    val columnRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "id"))
    val canonicalSeq = SequenceDefinition(start = 1L, increment = 1L)

    // ── collect(): per-op dispatch ─────────────────────────────────────

    test("CreateSequence yields support-table + 2 routines + row (4 decls)") {
        val p = probe()
        val op = DiffOperation.CreateSequence("create:order_seq", sequenceRef, canonicalSeq)

        val result = MysqlSequenceCanonicityProbeRunner.collect(p, planOf(op))

        result.map { it.kind }.shouldContainExactly(
            MysqlSequenceCanonicityKind.SUPPORT_TABLE,
            MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE,
            MysqlSequenceCanonicityKind.SETVAL_ROUTINE,
            MysqlSequenceCanonicityKind.SEQUENCE_ROW,
        )
        verify(exactly = 1) { p.probeSupportTable("create:order_seq") }
        verify(exactly = 1) { p.probeRoutine("create:order_seq", MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE) }
        verify(exactly = 1) { p.probeRoutine("create:order_seq", MysqlSequenceCanonicityKind.SETVAL_ROUTINE) }
        verify(exactly = 1) {
            p.probeSequenceRow(
                operationId = "create:order_seq",
                sequenceName = "order_seq",
                expectedIncrement = 1L,
                expectedMinValue = null,
                expectedMaxValue = null,
                expectedCycle = false,
                expectedCache = null,
            )
        }
    }

    test("AlterSequence uses `before` snapshot for the row probe expectations") {
        val before = SequenceDefinition(start = 1L, increment = 5L, minValue = 0L, maxValue = 100L, cycle = true, cache = 8)
        val after = SequenceDefinition(start = 1L, increment = 10L)
        val op = DiffOperation.AlterSequence("alter:order_seq", sequenceRef, before = before, after = after)
        val p = probe()

        MysqlSequenceCanonicityProbeRunner.collect(p, planOf(op))

        verify(exactly = 1) {
            p.probeSequenceRow(
                operationId = "alter:order_seq",
                sequenceName = "order_seq",
                expectedIncrement = 5L,
                expectedMinValue = 0L,
                expectedMaxValue = 100L,
                expectedCycle = true,
                expectedCache = 8,
            )
        }
        verify(exactly = 1) { p.probeSupportTable("alter:order_seq") }
        verify(exactly = 1) { p.probeRoutine("alter:order_seq", MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE) }
        verify(exactly = 1) { p.probeRoutine("alter:order_seq", MysqlSequenceCanonicityKind.SETVAL_ROUTINE) }
    }

    test("DropSequence uses the dropped sequence's snapshot for the row probe") {
        val def = SequenceDefinition(start = 1L, increment = 2L, minValue = 1L, maxValue = 9_999L, cycle = false, cache = 4)
        val op = DiffOperation.DropSequence("drop:order_seq", sequenceRef, def)
        val p = probe()

        MysqlSequenceCanonicityProbeRunner.collect(p, planOf(op))

        verify(exactly = 1) {
            p.probeSequenceRow(
                operationId = "drop:order_seq",
                sequenceName = "order_seq",
                expectedIncrement = 2L,
                expectedMinValue = 1L,
                expectedMaxValue = 9_999L,
                expectedCycle = false,
                expectedCache = 4,
            )
        }
    }

    test("RenameSequence probes the FROM-side row with canonical defaults (no snapshot)") {
        val op = DiffOperation.RenameSequence(
            id = "rename:old->new",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("new_seq")),
            fromName = "old_seq",
            toName = "new_seq",
            overlaySource = "test",
            overlayEntryId = "test-entry",
            overlayHash = null,
        )
        val p = probe()

        MysqlSequenceCanonicityProbeRunner.collect(p, planOf(op))

        verify(exactly = 1) {
            p.probeSequenceRow(
                operationId = "rename:old->new",
                sequenceName = "old_seq",
                expectedIncrement = 1L,
                expectedMinValue = null,
                expectedMaxValue = null,
                expectedCycle = false,
                expectedCache = null,
            )
        }
    }

    test("AddColumn with SequenceNextVal default emits ONLY a trigger decl") {
        val column = ColumnDefinition(NeutralType.BigInteger, default = DefaultValue.SequenceNextVal("order_seq"))
        val op = DiffOperation.AddColumn("add:orders.id", columnRef, column)
        val p = probe()

        val result = MysqlSequenceCanonicityProbeRunner.collect(p, planOf(op))

        result.map { it.kind }.shouldContainExactly(MysqlSequenceCanonicityKind.SUPPORT_TRIGGER)
        val expectedTrigger = MysqlSequenceNaming.triggerName("orders", "id")
        verify(exactly = 1) {
            p.probeSupportTrigger(
                operationId = "add:orders.id",
                triggerName = expectedTrigger,
                expectedSequenceName = "order_seq",
            )
        }
        verify(exactly = 0) { p.probeSupportTable(any()) }
        verify(exactly = 0) { p.probeSequenceRow(any(), any(), any(), any(), any(), any(), any()) }
    }

    test("AddColumn without a SequenceNextVal default is skipped (continue branch)") {
        val column = ColumnDefinition(NeutralType.Text())
        val op = DiffOperation.AddColumn("add:plain", columnRef, column)
        val p = probe()

        val result = MysqlSequenceCanonicityProbeRunner.collect(p, planOf(op))

        result.shouldBe(emptyList())
        verify(exactly = 0) { p.probeSupportTrigger(any(), any(), any()) }
    }

    test("AlterColumnDefault to a SequenceNextVal emits ONLY a trigger decl") {
        val op = DiffOperation.AlterColumnDefault(
            id = "alter-default:orders.id",
            objectRef = columnRef,
            before = null,
            after = DefaultValue.SequenceNextVal("order_seq"),
        )
        val p = probe()

        val result = MysqlSequenceCanonicityProbeRunner.collect(p, planOf(op))

        result.map { it.kind }.shouldContainExactly(MysqlSequenceCanonicityKind.SUPPORT_TRIGGER)
        verify(exactly = 1) {
            p.probeSupportTrigger(
                operationId = "alter-default:orders.id",
                triggerName = MysqlSequenceNaming.triggerName("orders", "id"),
                expectedSequenceName = "order_seq",
            )
        }
    }

    test("AlterColumnDefault to non-SequenceNextVal (literal default) is skipped") {
        val op = DiffOperation.AlterColumnDefault(
            id = "alter-default:plain",
            objectRef = columnRef,
            before = null,
            after = DefaultValue.NumberLiteral(0),
        )
        val p = probe()

        MysqlSequenceCanonicityProbeRunner.collect(p, planOf(op)).shouldBe(emptyList())
        verify(exactly = 0) { p.probeSupportTrigger(any(), any(), any()) }
    }

    test("unrelated ops fall through the else branch with no decls") {
        // DropColumn is not in the sequence/column-default scope — the
        // `else -> /* no-op */` branch must absorb it silently.
        val op = DiffOperation.DropColumn(
            id = "drop:orders.legacy",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "legacy")),
            column = ColumnDefinition(NeutralType.Text()),
        )
        val p = probe()

        MysqlSequenceCanonicityProbeRunner.collect(p, planOf(op)).shouldBe(emptyList())
    }

    test("empty plan yields empty decl list (no probe calls)") {
        val p = probe()
        MysqlSequenceCanonicityProbeRunner.collect(p, planOf()).shouldBe(emptyList())
        verify(exactly = 0) { p.probeSupportTable(any()) }
        verify(exactly = 0) { p.probeRoutine(any(), any()) }
        verify(exactly = 0) { p.probeSequenceRow(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { p.probeSupportTrigger(any(), any(), any()) }
    }

    test("mixed plan preserves operation order across decls") {
        val create = DiffOperation.CreateSequence("c", sequenceRef, canonicalSeq)
        val addCol = DiffOperation.AddColumn(
            "ac",
            columnRef,
            ColumnDefinition(NeutralType.BigInteger, default = DefaultValue.SequenceNextVal("order_seq")),
        )
        val p = probe()

        val result = MysqlSequenceCanonicityProbeRunner.collect(p, planOf(create, addCol))

        // 4 from create, then 1 from add-column → 5 in operation order
        result.map { it.operationId }.shouldContainExactly("c", "c", "c", "c", "ac")
    }

    // ── probe(): outer wrapper error paths ─────────────────────────────

    test("probe() throws CompareConfigException when NamedConnectionResolver fails") {
        // A bare alias without `://` triggers config-file resolution.
        // The runner's `configPath` points at a non-existent file → resolver throws.
        val nonExistentConfig = Files.createTempDirectory("dmigrate-probe-runner-test-")
            .resolve("does-not-exist.yaml")

        shouldThrow<CompareConfigException> {
            MysqlSequenceCanonicityProbeRunner.probe(
                target = CompareOperand.Database("unknown_alias"),
                configPath = nonExistentConfig,
                plan = planOf(),
            )
        }
    }

    test("probe() throws CompareConfigException when ConnectionUrlParser rejects the URL") {
        // A URL containing `://` skips the named-connection resolver
        // but still has to parse. An unsupported dialect triggers the
        // IllegalArgumentException that the runner wraps.
        shouldThrow<CompareConfigException> {
            MysqlSequenceCanonicityProbeRunner.probe(
                target = CompareOperand.Database("oracle://localhost/db"),
                configPath = null,
                plan = planOf(),
            )
        }
    }
})
