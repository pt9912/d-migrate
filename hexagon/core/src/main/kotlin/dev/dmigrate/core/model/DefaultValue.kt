package dev.dmigrate.core.model

sealed class DefaultValue {
    data class StringLiteral(val value: String) : DefaultValue()
    data class NumberLiteral(val value: Number) : DefaultValue()
    data class BooleanLiteral(val value: Boolean) : DefaultValue()
    data class FunctionCall(val name: String) : DefaultValue()
}
