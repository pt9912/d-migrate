package dev.dmigrate.driver.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.postgresql.fake.FakePgObject
import java.math.BigDecimal

class JdbcForeignValueNormalizerTest : FunSpec({

    test("pgjdbc PGobject (e.g. tsvector) is bound via getValue() (L1)") {
        JdbcForeignValueNormalizer.normalize(FakePgObject("'word':1 'other':2")) shouldBe "'word':1 'other':2"
    }

    test("java.sql.Array is encoded as a JSON string (K1, delegated)") {
        JdbcForeignValueNormalizer.normalize(FakeSqlArray(arrayOf("a", "b"))) shouldBe """["a","b"]"""
    }

    test("scalars are returned unchanged") {
        JdbcForeignValueNormalizer.normalize("plain") shouldBe "plain"
        JdbcForeignValueNormalizer.normalize(BigDecimal("9.99")) shouldBe BigDecimal("9.99")
        JdbcForeignValueNormalizer.normalize(42) shouldBe 42
    }

    test("a non-postgresql object with getValue() is NOT stringified (package guard)") {
        val value = NonPgWithGetValue("keep-as-object")
        JdbcForeignValueNormalizer.normalize(value) shouldBe value
    }
})

/** A getValue()-bearing class outside org.postgresql.* — must NOT be treated as a PGobject. */
internal class NonPgWithGetValue(private val v: String) {
    fun getValue(): String = v
}
