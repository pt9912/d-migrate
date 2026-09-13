package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlSequenceCanonicityDeclaration
import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus

/**
 * `mysql-sequenz-kanonizitaet-hinter-einen-port.md`: welche
 * [MysqlSequenceCanonicityDeclaration]s ein Plan erzeugt, und unter
 * welchem Status — die eine Frage, die vorher an zwei Stellen der
 * Anwendungsschicht identisch beantwortet wurde
 * (`MigrationPreflightPlanner.planMysqlSequenceCanonicity` fuer
 * `NOT_RUN_FILE_TARGET`/`NOT_RUN_POLICY`,
 * `MysqlSequenceCanonicityStage.stampStageFailure` fuer
 * `PROBE_RUNTIME_ERROR`). Beide Formen unterschieden sich nur im
 * [MysqlSequenceCanonicityDeclaration.status] und im `sqlHash`-Marker
 * — dieselbe Durchlauf-Logik baut fuer jeden Sequenz-Op eine
 * `SEQUENCE_ROW`-Deklaration und fuer jeden Spalten-Op mit einem
 * `SequenceNextVal`-Default eine `SUPPORT_TRIGGER`-Deklaration.
 *
 * Die Anwendungsschicht stellt die Frage (welcher Status, welcher
 * Plan) und bekommt die Antwort ueber [MysqlSequenceCanonicityPlannerFn]
 * gereicht, ohne diesen Treiber zu importieren — dieselbe
 * CLI-Bindung wie beim Live-Probe-Gegenstueck
 * ([MysqlSequenceCanonicityProbeAdapter]) und beim SQLite-Zwilling
 * (`SqliteCastPreflightPlanner`).
 */
object MysqlSequenceCanonicityPlanner {

    private val DIALECT_NAME = DatabaseDialect.MYSQL.name.lowercase()

    fun plan(
        diff: DiffResult,
        status: MysqlSequenceCanonicityStatus,
        sqlHash: String,
        problem: String? = null,
    ): List<MysqlSequenceCanonicityDeclaration> = diff.operations.mapNotNull { op ->
        declarationFor(op, status, sqlHash, problem)
    }

    private fun declarationFor(
        op: DiffOperation,
        status: MysqlSequenceCanonicityStatus,
        sqlHash: String,
        problem: String?,
    ): MysqlSequenceCanonicityDeclaration? = when (op) {
        is DiffOperation.CreateSequence -> rowDeclaration(op.id, op.objectRef.rootName, status, sqlHash, problem)
        is DiffOperation.AlterSequence -> rowDeclaration(op.id, op.objectRef.rootName, status, sqlHash, problem)
        is DiffOperation.DropSequence -> rowDeclaration(op.id, op.objectRef.rootName, status, sqlHash, problem)
        is DiffOperation.RenameSequence -> rowDeclaration(op.id, op.fromName, status, sqlHash, problem)
        is DiffOperation.AddColumn ->
            if (op.column.default is DefaultValue.SequenceNextVal) {
                triggerDeclaration(op.id, op.objectRef, status, sqlHash, problem)
            } else {
                null
            }
        is DiffOperation.AlterColumnDefault ->
            if (op.after is DefaultValue.SequenceNextVal) {
                triggerDeclaration(op.id, op.objectRef, status, sqlHash, problem)
            } else {
                null
            }
        else -> null
    }

    private fun rowDeclaration(
        operationId: String,
        sequenceName: String,
        status: MysqlSequenceCanonicityStatus,
        sqlHash: String,
        problem: String?,
    ): MysqlSequenceCanonicityDeclaration = MysqlSequenceCanonicityDeclaration(
        operationId = operationId,
        dialect = DIALECT_NAME,
        kind = MysqlSequenceCanonicityKind.SEQUENCE_ROW,
        objectName = sequenceName,
        status = status,
        sqlHash = sqlHash,
        problem = problem,
    )

    /** `columnRef.path` ist `[tableName, columnName]` (AddColumn / AlterColumnDefault). */
    private fun triggerDeclaration(
        operationId: String,
        columnRef: DiffObjectRef,
        status: MysqlSequenceCanonicityStatus,
        sqlHash: String,
        problem: String?,
    ): MysqlSequenceCanonicityDeclaration {
        val tableName = columnRef.path[0]
        val columnName = columnRef.path[1]
        return MysqlSequenceCanonicityDeclaration(
            operationId = operationId,
            dialect = DIALECT_NAME,
            kind = MysqlSequenceCanonicityKind.SUPPORT_TRIGGER,
            objectName = MysqlSequenceNaming.triggerName(tableName, columnName),
            status = status,
            sqlHash = sqlHash,
            problem = problem,
        )
    }
}
