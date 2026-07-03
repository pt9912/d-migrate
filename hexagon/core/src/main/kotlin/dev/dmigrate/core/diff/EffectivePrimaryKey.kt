package dev.dmigrate.core.diff

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition

/**
 * The *effective* primary key of a table — the v3-Fingerprint-Regel als
 * geteilte Wahrheit für Fingerprint UND target-aware Comparator (AP7):
 * ein expliziter `primary_key` gewinnt verbatim; sonst wird der PK aus
 * genau **einer** `identifier`-Spalte abgeleitet
 * (`spec/neutral-model-spec.md` 13.1 — `identifier` trägt PK-Semantik).
 * Mehrere `identifier`-Spalten sind ambig → kein abgeleiteter PK.
 */
internal object EffectivePrimaryKey {

    fun of(table: TableDefinition): List<String> {
        if (table.primaryKey.isNotEmpty()) return table.primaryKey
        val identifierColumns = table.columns.entries.filter { it.value.type is NeutralType.Identifier }
        return if (identifierColumns.size == 1) listOf(identifierColumns.first().key) else emptyList()
    }
}
