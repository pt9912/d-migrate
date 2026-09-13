package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.metadata.ColumnSwapGuard
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * `column-generation-diff-unmapped.md`: der Kind-Wechsel per Spaltentausch
 * fuer Oracle. Eigene Datei aus demselben Grund wie
 * `OracleComputedExpressionDuplication` — sonst triebe der Zweig
 * `OracleDiffTableOps` ueber Detekts `TooManyFunctions`-Schwelle. Nutzt
 * `OracleDiffTableOps`' `internal`-Helfer ([OracleDiffTableOps.columnHelper],
 * [OracleDiffTableOps.blockMissingSchema], [OracleDiffTableOps
 * .blockMissingColumn]) statt sie zu duplizieren.
 */
internal object OracleDiffGenerationSwapOps {

    const val COMPUTED_KIND_SWAP_ENCUMBERED: String = "ORACLE_COMPUTED_KIND_SWAP_ENCUMBERED"

    /**
     * Oracle lehnt beide Richtungen in place ab — gewoehnlich → berechnet
     * mit `ORA-54026`; berechnet → gewoehnlich nimmt `MODIFY (c <typ>)` zwar
     * an, aendert aber nichts (live durch den vollen Migrationspfad
     * gemessen).
     *
     * - **gewoehnlich → berechnet**: kein Kopieren noetig — der Wert
     *   entsteht aus der Formel, sobald die Spalte neu angelegt wird.
     *   `DROP COLUMN` + `ADD (…)` unter demselben Namen.
     * - **berechnet → gewoehnlich**: der eingefrorene Wert muss erhalten
     *   bleiben — Spaltentausch ueber eine Zwischenspalte (`ADD` nullbar +
     *   `UPDATE`-Kopie + `DROP` der berechneten Spalte + `RENAME COLUMN`),
     *   danach ein `MODIFY` auf die volle Zieldeklaration (NOT NULL
     *   nachziehen).
     *
     * Jede Anweisung traegt ein festes Risiko statt `op.risks` — aus
     * demselben Grund wie bei `emitRebuild`: die Operation ist auf allen
     * fuenf Dialekten gleich markiert, aber nur hier laeuft sie ueber
     * Drop+Recreate.
     */
    fun renderComputedKindSwap(
        op: DiffOperation.AlterColumnGeneration,
        ctx: OracleDiffRenderContext,
        table: String,
        column: String,
        becomingComputed: Boolean,
    ) {
        val schema = ctx.schemaForDirection()
            ?: return OracleDiffTableOps.blockMissingSchema(op, ctx, "swapping column '$table.$column'")
        val declaration = schema.tables[table]?.columns?.get(column)
            ?: return OracleDiffTableOps.blockMissingColumn(op, ctx, table, column, "its full declaration")
        val guard = ColumnSwapGuard.check(schema, table, column)
        if (guard is ColumnSwapGuard.Result.Encumbered) {
            ctx.skip(
                op,
                "Operation ${op.id} would swap '$table.$column' to change its generation kind, but " +
                    "${guard.reason} — the swap cannot safely carry that across. Oracle refuses the " +
                    "in-place route (`ORA-54026` for gewoehnlich → berechnet; `MODIFY (c <typ>)` changes " +
                    "nothing for berechnet → gewoehnlich, measured against 23); resolve the encumbrance and " +
                    "migrate the column manually.",
                code = COMPUTED_KIND_SWAP_ENCUMBERED,
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        val quotedTable = ctx.sql.quote(table)
        val notes = mutableListOf<TransformationNote>()
        if (becomingComputed) {
            val lossy = swapRisk(dataLossPossible = true)
            ctx.emit(op, "ALTER TABLE $quotedTable DROP COLUMN ${ctx.sql.quote(column)};", riskOverride = lossy)
            val decl = OracleDiffTableOps.columnHelper.generateColumnSql(table, column, declaration, schema, notes)
            ctx.emit(op, "ALTER TABLE $quotedTable ADD ($decl);", riskOverride = lossy)
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
        val tempDecl = OracleDiffTableOps.columnHelper.generateColumnSql(table, tempName, bare, schema, notes)
        ctx.emit(op, "ALTER TABLE $quotedTable ADD ($tempDecl);", riskOverride = risk)
        ctx.emit(
            op,
            "UPDATE $quotedTable SET ${ctx.sql.quote(tempName)} = ${ctx.sql.quote(column)};",
            riskOverride = risk,
        )
        ctx.emit(op, "ALTER TABLE $quotedTable DROP COLUMN ${ctx.sql.quote(column)};", riskOverride = risk)
        ctx.emit(
            op,
            "ALTER TABLE $quotedTable RENAME COLUMN ${ctx.sql.quote(tempName)} TO ${ctx.sql.quote(column)};",
            riskOverride = risk,
        )
        val finalDecl = OracleDiffTableOps.columnHelper.generateColumnSql(table, column, declaration, schema, notes)
        ctx.emit(op, "ALTER TABLE $quotedTable MODIFY ($finalDecl);", riskOverride = risk)
        ctx.carryOverNotes(op, notes)
    }

    private fun swapRisk(dataLossPossible: Boolean) = OperationRisk(
        destructive = true,
        dataLossPossible = dataLossPossible,
        requiresManualConfirmation = true,
    )
}
