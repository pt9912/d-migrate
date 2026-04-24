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
        return false
    }

    private fun isIdentifierCompatible(source: NeutralType, target: NeutralType): Boolean {
        if (source !is NeutralType.Identifier) return false
        return isIntegralTargetType(target) || target is NeutralType.Identifier
    }

    private fun isIntegralTargetType(target: NeutralType): Boolean =
        target is NeutralType.Integer || target is NeutralType.BigInteger
}
