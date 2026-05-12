package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation

/**
 * PostgreSQL sequence DDL for Plan-2 E.3's first declarative slice.
 *
 * The renderer handles only schema attributes present in SequenceDefinition.
 * It deliberately does not preserve or reset the live current value.
 */
internal object PostgresDiffSequenceOps {

    fun renderCreateSequence(op: DiffOperation.CreateSequence, ctx: PostgresDiffRenderContext) {
        val name = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(op, "DROP SEQUENCE ${ctx.sql.quote(name)};", PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
            return
        }
        ctx.emit(op, ctx.sql.createSequenceSql(name, op.sequence), PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    fun renderAlterSequence(op: DiffOperation.AlterSequence, ctx: PostgresDiffRenderContext) {
        val name = op.objectRef.rootName
        val target = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        ctx.emit(op, ctx.sql.alterSequenceSql(name, target), PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    fun renderDropSequence(op: DiffOperation.DropSequence, ctx: PostgresDiffRenderContext) {
        val name = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.createSequenceSql(name, op.sequence), PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
            return
        }
        ctx.emit(op, "DROP SEQUENCE ${ctx.sql.quote(name)};", PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }
}
