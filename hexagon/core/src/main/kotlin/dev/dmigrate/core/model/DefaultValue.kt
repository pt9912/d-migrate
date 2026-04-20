package dev.dmigrate.core.model

sealed class DefaultValue {
    data class StringLiteral(val value: String) : DefaultValue()
    data class NumberLiteral(val value: Number) : DefaultValue()
    data class BooleanLiteral(val value: Boolean) : DefaultValue()
    data class FunctionCall(val name: String) : DefaultValue()
    /** Sequence-based column default (0.9.3). References a named sequence from schema.sequences. */
    data class SequenceNextVal(val sequenceName: String) : DefaultValue()
}
