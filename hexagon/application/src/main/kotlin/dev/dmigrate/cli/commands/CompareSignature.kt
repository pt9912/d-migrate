package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.IndexDefinition

/**
 * Die Kurzform, mit der `schema compare` einen Index oder einen Constraint
 * nennt — in der CLI als Vorher-/Nachher-Zeile, in den MCP-`findings` als
 * `details`.
 *
 * Sie traegt **jedes** Feld, das der Vergleich wertet. Fehlt eines, meldet
 * eine Aenderung genau daran beidseitig dieselbe Zeile: ein geaenderter CHECK
 * stand als `ck_x (check) -> ck_x (check)` da, ein geaendertes
 * Index-Praedikat als `ix [btree] -> ix [btree]` — ein Fund, der nicht sagt,
 * was sich geaendert hat. Die reinen Generate-Hinweise eines Volltext-Index
 * (Vektorspalte, Zugriffsart) wertet der Vergleich nicht; sie fehlen hier
 * deshalb auch.
 *
 * Nie leer: der Name bzw. die Schluessel stehen immer vorn.
 */
object CompareSignature {

    /** `ix_open [btree,unique] on (status) include (total) where status <> 'DONE'` */
    fun index(index: IndexDefinition): String = buildString {
        append(index.name ?: index.columns.joinToString(","))
        append(" [").append(index.type.name.lowercase())
        if (index.unique) append(",unique")
        if (index.clustered) append(",clustered")
        append("]")
        append(" on (").append(index.columns.joinToString(", ")).append(")")
        if (index.includeColumns.isNotEmpty()) append(" include (").append(index.includeColumns.joinToString(", ")).append(")")
        index.textSearchConfig?.let { append(" text_search=").append(it) }
        index.where?.let { append(" where ").append(it) }
    }

    /** `fk_order (foreign_key on [order_id] -> orders[id] on_delete=cascade)`, `ck_qty (check: qty > 0)` */
    fun constraint(constraint: ConstraintDefinition): String = buildString {
        append(constraint.name).append(" (").append(constraint.type.name.lowercase())
        constraint.columns?.takeIf { it.isNotEmpty() }?.let { append(" on [").append(it.joinToString(",")).append("]") }
        constraint.references?.let { ref ->
            append(" -> ").append(ref.table).append("[").append(ref.columns.joinToString(",")).append("]")
            ref.onDelete?.let { append(" on_delete=").append(it.name.lowercase()) }
            ref.onUpdate?.let { append(" on_update=").append(it.name.lowercase()) }
        }
        constraint.expression?.let { append(": ").append(it) }
        append(")")
    }
}
