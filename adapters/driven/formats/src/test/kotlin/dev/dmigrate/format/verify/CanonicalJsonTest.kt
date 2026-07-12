package dev.dmigrate.format.verify

import dev.dmigrate.verify.ValueCanonicalizationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * LN-009 / ADR 0030: semantische JSON-Kanonik — Key-Reihenfolge und Whitespace
 * irrelevant, Zahlen normalisiert, Array-Reihenfolge signifikant.
 */
class CanonicalJsonTest : FunSpec({

    val json = CanonicalJson()
    fun canon(s: String) = String(json.canonicalize(s), Charsets.UTF_8)

    test("Key-Reihenfolge und Whitespace sind irrelevant") {
        canon("""{"b":1,"a":2}""") shouldBe canon("""{ "a" : 2 , "b" : 1 }""")
    }

    test("verschachtelte Objekte werden rekursiv sortiert") {
        canon("""{"x":{"q":1,"p":2}}""") shouldBe canon("""{"x":{"p":2,"q":1}}""")
    }

    test("Zahlen normalisiert (1.0 == 1, 1.50 == 1.5)") {
        canon("""{"n":1.0}""") shouldBe canon("""{"n":1}""")
        canon("""{"n":1.50}""") shouldBe canon("""{"n":1.5}""")
    }

    test("Array-Reihenfolge ist signifikant") {
        canon("""[1,2,3]""") shouldNotBe canon("""[3,2,1]""")
    }

    test("unterschiedliche Inhalte unterscheiden sich") {
        canon("""{"a":1}""") shouldNotBe canon("""{"a":2}""")
    }

    test("nicht-parsebares JSON wirft") {
        shouldThrow<ValueCanonicalizationException> { json.canonicalize("{not json") }
    }
})
