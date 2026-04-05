package dev.dmigrate.driver

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType

interface TypeMapper {
    val dialect: DatabaseDialect
    fun toSql(type: NeutralType): String
    fun toDefaultSql(default: DefaultValue, type: NeutralType): String
}
