package org.postgresql.fake

/**
 * Stand-in for pgjdbc's `org.postgresql.util.PGobject` (which is not on this
 * module's classpath): same `org.postgresql.*` package prefix and a
 * `getValue(): String` accessor, so [dev.dmigrate.driver.data.JdbcForeignValueNormalizer]
 * detects it reflectively like the real PGobject.
 */
class FakePgObject(private val value: String) {
    fun getValue(): String = value
}
