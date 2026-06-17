package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType

internal class TransferTypeCompatibility {

    fun isCompatible(source: ColumnDefinition, target: ColumnDefinition): Boolean {
        if (source.type == target.type) return true
        val sourceType = source.type
        val targetType = target.type
        if (sourceType is NeutralType.Integer && targetType is NeutralType.BigInteger) return true
        if (sourceType is NeutralType.SmallInt && isIntegralTargetType(targetType)) return true
        if (isIdentifierCompatible(sourceType, targetType)) return true
        if (sourceType is NeutralType.Integer && targetType is NeutralType.Identifier) return true
        if (sourceType is NeutralType.Text && targetType is NeutralType.Text) return true
        if (sourceType is NeutralType.Char && targetType is NeutralType.Text) return true
        // I-01: vom Tool selbst erzeugte Cross-Dialect-Abbildungen sind
        // transfer-kompatibel — boolean→INTEGER (SQLite), Enum→Text (SQLite/PG)
        // und DateTime↔DateTime trotz tz-Flag (timestamptz→DATETIME, beim
        // generate bereits per W100 ausgewiesen).
        if (sourceType is NeutralType.BooleanType && isIntegralTargetType(targetType)) return true
        if (sourceType is NeutralType.Enum && targetType is NeutralType.Text) return true
        if (sourceType is NeutralType.DateTime && targetType is NeutralType.DateTime) return true
        // N3: weitere tool-eigene Cross-Dialect-Abbildungen.
        // PG-Named-Enum (values=null, refType=…) ↔ MySQL-Inline-Enum (values=[…],
        // refType=null) ist derselbe logische Enum, vom Tool beim generate erzeugt.
        if (sourceType is NeutralType.Enum && targetType is NeutralType.Enum) return true
        // SQLite legt Temporal-Typen als TEXT ab (DateTime/Date/Time → Text).
        if (isTemporalType(sourceType) && targetType is NeutralType.Text) return true
        return false
    }

    private fun isTemporalType(type: NeutralType): Boolean =
        type is NeutralType.DateTime || type is NeutralType.Date || type is NeutralType.Time

    private fun isIdentifierCompatible(source: NeutralType, target: NeutralType): Boolean {
        if (source !is NeutralType.Identifier) return false
        return isIntegralTargetType(target) || target is NeutralType.Identifier
    }

    private fun isIntegralTargetType(target: NeutralType): Boolean =
        target is NeutralType.Integer || target is NeutralType.BigInteger
}
