package dev.dmigrate.cli.commands
import dev.dmigrate.driver.connection.asJdbc

import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.driver.MysqlSequenceCanonicityDeclaration
import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityProbe
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.mysql.MysqlSequenceCanonicityProbeAdapter
import dev.dmigrate.driver.mysql.MysqlSequenceNaming
import java.nio.file.Path

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice F follow-up
 * (2026-05-20): CLI-side wiring for the per-op drift probe. Wired
 * into [SchemaMigrateCommand] as the `mysqlSequenceCanonicityProbe`
 * argument to [SchemaMigrateRunner]; called by
 * `SchemaMigrateRenderPipeline.run` via
 * [MysqlSequenceCanonicityProbeFn] when the request is
 * `--execute` against a MySQL target and the plan contains at
 * least one sequence-related operation.
 *
 * Coverage per pooled connection:
 *
 * - Per sequence op (Create / Alter / Drop / Rename): support
 *   table column signature + PK (`SUPPORT_TABLE`), both routines
 *   (`NEXTVAL_ROUTINE`, `SETVAL_ROUTINE`), the
 *   `dmg_sequences` row managed-fields (`SEQUENCE_ROW`).
 * - Per column op (AddColumn / AlterColumnDefault) whose target
 *   default is a `SequenceNextVal`: the column-bound support
 *   trigger (`SUPPORT_TRIGGER`). The op-id is the column op's id
 *   so the renderer-gate inside `emitSupportTriggerForColumn`
 *   matches the declaration to the right operation.
 */
internal object MysqlSequenceCanonicityProbeRunner {

    fun probe(
        target: CompareOperand.Database,
        configPath: Path?,
        plan: DiffResult,
    ): List<MysqlSequenceCanonicityDeclaration> {
        val url = try {
            NamedConnectionResolver(configPathFromCli = configPath).resolve(target.source)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "Config resolution failed", e)
        }
        val config = try {
            CredentialFilling(target.source).fill(url)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "URL parse failed", e)
        }
        val pool = HikariConnectionPoolFactory.create(config)
        return pool.use { p ->
            p.borrow().asJdbc().use { conn ->
                val adapter = MysqlSequenceCanonicityProbeAdapter(conn)
                collect(adapter, plan)
            }
        }
    }

    internal fun collect(
        adapter: MysqlSequenceCanonicityProbe,
        plan: DiffResult,
    ): List<MysqlSequenceCanonicityDeclaration> = buildList {
        for (op in plan.operations) {
            when (op) {
                is DiffOperation.CreateSequence -> {
                    addAll(probeSupportObjects(adapter, op.id))
                    add(adapter.probeSequenceRow(
                        operationId = op.id,
                        sequenceName = op.objectRef.rootName,
                        expectedIncrement = op.sequence.increment,
                        expectedMinValue = op.sequence.minValue,
                        expectedMaxValue = op.sequence.maxValue,
                        expectedCycle = op.sequence.cycle,
                        expectedCache = op.sequence.cache,
                    ))
                }
                is DiffOperation.AlterSequence -> {
                    addAll(probeSupportObjects(adapter, op.id))
                    add(adapter.probeSequenceRow(
                        operationId = op.id,
                        sequenceName = op.objectRef.rootName,
                        expectedIncrement = op.before.increment,
                        expectedMinValue = op.before.minValue,
                        expectedMaxValue = op.before.maxValue,
                        expectedCycle = op.before.cycle,
                        expectedCache = op.before.cache,
                    ))
                }
                is DiffOperation.DropSequence -> {
                    addAll(probeSupportObjects(adapter, op.id))
                    add(adapter.probeSequenceRow(
                        operationId = op.id,
                        sequenceName = op.objectRef.rootName,
                        expectedIncrement = op.sequence.increment,
                        expectedMinValue = op.sequence.minValue,
                        expectedMaxValue = op.sequence.maxValue,
                        expectedCycle = op.sequence.cycle,
                        expectedCache = op.sequence.cache,
                    ))
                }
                is DiffOperation.RenameSequence -> {
                    addAll(probeSupportObjects(adapter, op.id))
                    // No managed-fields snapshot available; canonical
                    // defaults — drift surfaces a precise field,
                    // missing rows become MISSING which ALTER intent
                    // blocks.
                    add(adapter.probeSequenceRow(
                        operationId = op.id,
                        sequenceName = op.fromName,
                        expectedIncrement = 1L,
                        expectedMinValue = null,
                        expectedMaxValue = null,
                        expectedCycle = false,
                        expectedCache = null,
                    ))
                }
                is DiffOperation.AddColumn -> {
                    val def = op.column.default as? DefaultValue.SequenceNextVal ?: continue
                    add(probeTriggerForColumn(adapter, op.id, op.objectRef, def.sequenceName))
                }
                is DiffOperation.AlterColumnDefault -> {
                    val def = op.after as? DefaultValue.SequenceNextVal ?: continue
                    add(probeTriggerForColumn(adapter, op.id, op.objectRef, def.sequenceName))
                }
                else -> { /* no-op */ }
            }
        }
    }

    private fun probeSupportObjects(
        adapter: MysqlSequenceCanonicityProbe,
        operationId: String,
    ): List<MysqlSequenceCanonicityDeclaration> = listOf(
        adapter.probeSupportTable(operationId),
        adapter.probeRoutine(operationId, MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE),
        adapter.probeRoutine(operationId, MysqlSequenceCanonicityKind.SETVAL_ROUTINE),
    )

    private fun probeTriggerForColumn(
        adapter: MysqlSequenceCanonicityProbe,
        operationId: String,
        columnRef: dev.dmigrate.core.diff.migration.DiffObjectRef,
        sequenceName: String,
    ): MysqlSequenceCanonicityDeclaration {
        // `AddColumn` / `AlterColumnDefault` objectRef path is
        // `[tableName, columnName]`.
        val tableName = columnRef.path[0]
        val columnName = columnRef.path[1]
        val triggerName = MysqlSequenceNaming.triggerName(tableName, columnName)
        return adapter.probeSupportTrigger(
            operationId = operationId,
            triggerName = triggerName,
            expectedSequenceName = sequenceName,
        )
    }
}
