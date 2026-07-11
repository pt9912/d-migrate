package dev.dmigrate.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.pair

/**
 * Geteilter Generator für [SchemaDefinition] (LN-046, ADR 0029, Phase C).
 *
 * Erzeugt Tabellen + Spalten über die volle [NeutralType]-Abdeckung (via
 * [neutralType]) plus optionalen String-Default und Primärschlüssel. Bezeichner
 * sind bewusst **YAML-sicher präfixiert** (`t…`/`c…`/`s…`/`v…`, Suffix aus
 * `[a-z0-9]`): kein leerer Name, keine führende Ziffer, keine Kollision mit
 * YAML-Schlüsselwörtern (`true`/`no`/…) — so ist ein `write→read`-Round-Trip
 * nicht durch Quoting-/Typ-Inferenz-Artefakte verfälscht.
 *
 * Bewusst noch nicht generiert (Folge-Erweiterung): Referenzen, Constraints,
 * Indizes, Partitionierung, Views/Routinen/Sequenzen und die nicht-String-
 * Defaults. Der Fokus liegt auf dem Tabellen-/Spalten-/Typ-Kern.
 */
fun Arb.Companion.schemaDefinition(): Arb<SchemaDefinition> {
    fun named(prefix: String): Arb<String> =
        Arb.list(Arb.of(('a'..'z').toList() + ('0'..'9')), 0..6).map { prefix + it.joinToString("") }

    val alpha = Arb.list(Arb.of(('a'..'e').toList()), 1..6).map { it.joinToString("") }
    val default: Arb<DefaultValue?> = alpha.map { DefaultValue.StringLiteral(it) }.orNull(0.5)

    val column: Arb<ColumnDefinition> = Arb.bind(
        Arb.neutralType(),
        Arb.boolean(),
        Arb.boolean(),
        default,
    ) { type, required, unique, def ->
        ColumnDefinition(type = type, required = required, unique = unique, default = def)
    }

    val table: Arb<TableDefinition> = Arb.bind(
        Arb.list(Arb.pair(named("c"), column), 1..4),
        Arb.boolean(),
    ) { colPairs, withPk ->
        val cols = colPairs.toMap()
        TableDefinition(columns = cols, primaryKey = if (withPk) cols.keys.take(1).toList() else emptyList())
    }

    return Arb.bind(
        named("s"),
        named("v"),
        Arb.list(Arb.pair(named("t"), table), 0..3),
    ) { name, version, tablePairs ->
        SchemaDefinition(name = name, version = version, tables = tablePairs.toMap())
    }
}
