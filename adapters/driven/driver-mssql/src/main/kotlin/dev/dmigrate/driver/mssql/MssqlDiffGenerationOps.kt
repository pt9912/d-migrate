package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnGenerationTransition
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.metadata.ColumnSwapGuard
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Renderer fuer `AlterColumnGeneration` (Sub-Slice `column-generation-diff-
 * unmapped.md`). Eigene Datei, aus demselben Grund wie
 * `PostgresDiffComputedColumnOps`: die Generierungsart-Frage kommt mit
 * eigenem Entwurf (Spaltentausch, Kapselung), der `MssqlDiffTableOps` sonst
 * ueber Detekts `TooManyFunctions`-Schwelle triebe.
 *
 * Nutzt `MssqlDiffTableOps`' `internal`-Helfer ([MssqlDiffTableOps
 * .dropColumnStatements], [MssqlDiffTableOps.alterColumnWithDefaultDance],
 * [MssqlDiffTableOps.blockMissingSchema], [MssqlDiffTableOps
 * .blockMissingColumn]) statt sie zu duplizieren -- derselbe Abhaengigkeits-
 * Abbau, dieselbe Voll-Neudeklarations-Dance, die `AlterColumnType`/
 * `AlterColumnDefault` schon benutzen.
 */
internal object MssqlDiffGenerationOps {

    private const val COMPUTED_KIND_SWAP_ENCUMBERED = "MSSQL_COMPUTED_KIND_SWAP_ENCUMBERED"

    /**
     * SQL Server hat fuer `AlterColumnGeneration` keinen In-Place-Zweig ueberhaupt
     * (`ALTER COLUMN c AS (…)` ist ein Syntaxfehler, `Cannot alter column …
     * because it is 'COMPUTED'` fuer die Gegenrichtung, IDENTITY nachtraeglich
     * scheitert an der Syntax — alle live gemessen gegen 2025). Bis
     * `column-generation-diff-unmapped.md` blockte der generische
     * `blockUnsupported`-Fallback alle vier Uebergaenge gleich. Diese Funktion
     * uebernimmt die Operation jetzt vollstaendig:
     *
     * - **Kind-Wechsel** (gewoehnlich ↔ berechnet) laufen ueber
     *   [renderComputedKindSwap].
     * - **Modus-Wechsel** und **reiner Ausdruckswechsel** bleiben geblockt —
     *   dieselbe, unveraenderte Meldung, die vorher aus `ownerOf` kam. Kein
     *   Tausch dafuer vorgesehen: eine Identity-Aenderung hat mit dem
     *   Ausdruck nichts zu tun, und ein Ausdruckswechsel allein braucht
     *   keinen Kind-Wechsel, um sicher zu sein.
     */
    fun renderAlterColumnGeneration(op: DiffOperation.AlterColumnGeneration, ctx: MssqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val up = ctx.direction == MssqlRenderDirection.UP
        val from = if (up) op.before else op.after
        val target = if (up) op.after else op.before
        when (ColumnGenerationTransition.of(from, target)) {
            ColumnGenerationTransition.COMPUTED_ADDED -> renderComputedKindSwap(op, ctx, table, column, becomingComputed = true)
            ColumnGenerationTransition.COMPUTED_DROPPED -> renderComputedKindSwap(op, ctx, table, column, becomingComputed = false)
            ColumnGenerationTransition.IDENTITY -> blockGenerationTransition(
                op,
                ctx,
                "this changes the column's identity, not a computed expression; T-SQL cannot add IDENTITY to " +
                    "an existing column (`Incorrect syntax near the keyword 'IDENTITY'`, measured against " +
                    "2025), and d-migrate does not render identity changes — express the transition through " +
                    "the column type (`identifier`) instead.",
            )
            ColumnGenerationTransition.COMPUTED_EXPRESSION -> blockGenerationTransition(
                op,
                ctx,
                "T-SQL has no `ALTER COLUMN … AS (…)`; the only route is dropping and recreating the column, " +
                    "which leaves a view over it silently broken.",
            )
        }
    }

    private fun blockGenerationTransition(op: DiffOperation, ctx: MssqlDiffRenderContext, reason: String) {
        ctx.skip(
            op,
            "Operation ${op.id} (${op::class.simpleName}) is not rendered by the MSSQL migrate path: $reason",
            code = "DIALECT_UNSUPPORTED_OPERATION",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, setOf(op.id))
    }

    /**
     * `column-generation-diff-unmapped.md`: der Kind-Wechsel per
     * Spaltentausch.
     *
     * - **gewoehnlich → berechnet**: kein Kopieren noetig — der Wert
     *   entsteht aus der Formel, sobald die Spalte neu angelegt wird.
     *   [MssqlDiffTableOps.dropColumnStatements] (dieselbe Abhaengigkeits-
     *   Abraeumung wie bei einem gewoehnlichen `DropColumn`) + `ADD` unter
     *   demselben Namen.
     * - **berechnet → gewoehnlich**: der eingefrorene Wert muss erhalten
     *   bleiben — Spaltentausch ueber eine Zwischenspalte (`ADD` nullbar +
     *   `UPDATE`-Kopie + [MssqlDiffTableOps.dropColumnStatements] +
     *   `sp_rename`), danach [MssqlDiffTableOps.alterColumnWithDefaultDance]
     *   auf die volle Zieldeklaration (NOT NULL/DEFAULT nachziehen —
     *   dieselbe Anweisungsfolge, die `AlterColumnType`/
     *   `AlterColumnNullability` schon benutzen).
     *
     * Jede Anweisung traegt ein festes Risiko statt `op.risks` — aus
     * demselben Grund wie bei `emitRebuild`: die Operation ist auf allen
     * fuenf Dialekten gleich markiert, aber nur hier laeuft sie ueber
     * Drop+Recreate.
     */
    private fun renderComputedKindSwap(
        op: DiffOperation.AlterColumnGeneration,
        ctx: MssqlDiffRenderContext,
        table: String,
        column: String,
        becomingComputed: Boolean,
    ) {
        val schema = ctx.schemaForDirection()
            ?: return MssqlDiffTableOps.blockMissingSchema(op, ctx, "swapping column '$table.$column'")
        val tableDef = schema.tables[table]
            ?: return MssqlDiffTableOps.blockMissingSchema(op, ctx, "swapping column '$table.$column'")
        val declaration = tableDef.columns[column]
            ?: return MssqlDiffTableOps.blockMissingColumn(op, ctx, table, column, "its full declaration")
        val guard = ColumnSwapGuard.check(schema, table, column)
        if (guard is ColumnSwapGuard.Result.Encumbered) {
            ctx.skip(
                op,
                "Operation ${op.id} would swap '$table.$column' to change its generation kind, but " +
                    "${guard.reason} — the swap cannot safely carry that across. SQL Server has no in-place " +
                    "route (`ALTER COLUMN … AS (…)` is a syntax error, measured against 2025); resolve the " +
                    "encumbrance and migrate the column manually.",
                code = COMPUTED_KIND_SWAP_ENCUMBERED,
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        val quotedTable = ctx.sql.quote(table)
        val notes = mutableListOf<TransformationNote>()
        if (becomingComputed) {
            MssqlDiffTableOps.dropColumnStatements(op, ctx, table, column)
            val decl = ctx.sql.columnDeclaration(table, column, declaration, tableDef, schema, notes)
            ctx.emit(op, "ALTER TABLE $quotedTable ADD $decl;", riskOverride = swapRisk(dataLossPossible = true))
            ctx.carryOverNotes(op, notes)
            return
        }
        val tempName = "${column}__dmg_swap"
        val bare = declaration.copy(
            required = false,
            default = null,
            unique = false,
            uniqueConstraintName = null,
            references = null,
        )
        val risk = swapRisk(dataLossPossible = false)
        val tempDecl = ctx.sql.columnDeclaration(table, tempName, bare, tableDef, schema, notes)
        ctx.emit(op, "ALTER TABLE $quotedTable ADD $tempDecl;", riskOverride = risk)
        ctx.emit(
            op,
            "UPDATE $quotedTable SET ${ctx.sql.quote(tempName)} = ${ctx.sql.quote(column)};",
            riskOverride = risk,
        )
        MssqlDiffTableOps.dropColumnStatements(op, ctx, table, column)
        ctx.emit(
            op,
            ctx.sql.renameSql("$table.$tempName", column, objectType = "COLUMN"),
            riskOverride = risk,
        )
        ctx.carryOverNotes(op, notes)
        MssqlDiffTableOps.alterColumnWithDefaultDance(op, ctx, table, column, declaration.type, declaration)
    }

    private fun swapRisk(dataLossPossible: Boolean) = OperationRisk(
        destructive = true,
        dataLossPossible = dataLossPossible,
        requiresManualConfirmation = true,
    )
}
