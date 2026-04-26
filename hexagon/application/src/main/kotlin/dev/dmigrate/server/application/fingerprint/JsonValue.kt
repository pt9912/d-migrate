package dev.dmigrate.server.application.fingerprint

sealed interface JsonValue {

    object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data class Num(val value: Long) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue {
        companion object {
            val EMPTY: Obj = Obj(emptyMap())
        }
    }

    companion object {
        fun obj(vararg fields: Pair<String, JsonValue>): Obj = Obj(linkedMapOf(*fields))
        fun arr(vararg items: JsonValue): Arr = Arr(items.toList())
        fun num(value: Long): Num = Num(value)
        fun num(value: Int): Num = Num(value.toLong())
        fun str(value: String): Str = Str(value)
        fun bool(value: Boolean): Bool = Bool(value)
    }
}
