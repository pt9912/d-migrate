package dev.dmigrate.driver.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JdbcArrayJsonEncoderTest : FunSpec({

    fun sqlArray(vararg elements: Any?): java.sql.Array = FakeSqlArray(elements)

    test("encodes a text array as a JSON string array (K1)") {
        JdbcArrayJsonEncoder.encode(sqlArray("NEW", "DELETED")) shouldBe """["NEW","DELETED"]"""
    }

    test("escapes quotes and backslashes") {
        JdbcArrayJsonEncoder.encode(sqlArray("a\"b", "c\\d")) shouldBe """["a\"b","c\\d"]"""
    }

    test("renders numbers and booleans unquoted, null as null") {
        JdbcArrayJsonEncoder.encode(sqlArray(1, 2.5, true, null)) shouldBe """[1,2.5,true,null]"""
    }

    test("empty array → []") {
        JdbcArrayJsonEncoder.encode(sqlArray()) shouldBe "[]"
    }
})

/** Minimal [java.sql.Array] whose [getArray] returns the supplied elements. */
private class FakeSqlArray(private val elements: Array<out Any?>) : java.sql.Array {
    override fun getArray(): Any = elements
    override fun getArray(map: MutableMap<String, Class<*>>?): Any = elements
    override fun getArray(index: Long, count: Int): Any = elements
    override fun getArray(index: Long, count: Int, map: MutableMap<String, Class<*>>?): Any = elements
    override fun getBaseTypeName(): String = "text"
    override fun getBaseType(): Int = java.sql.Types.VARCHAR
    override fun getResultSet(): java.sql.ResultSet = throw UnsupportedOperationException()
    override fun getResultSet(map: MutableMap<String, Class<*>>?): java.sql.ResultSet = throw UnsupportedOperationException()
    override fun getResultSet(index: Long, count: Int): java.sql.ResultSet = throw UnsupportedOperationException()
    override fun getResultSet(index: Long, count: Int, map: MutableMap<String, Class<*>>?): java.sql.ResultSet =
        throw UnsupportedOperationException()
    override fun free() {}
}
