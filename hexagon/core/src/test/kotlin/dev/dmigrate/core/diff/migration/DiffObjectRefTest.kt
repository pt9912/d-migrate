package dev.dmigrate.core.diff.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Path-arity invariants for [DiffObjectRef]. The `init`-block
 * normalises construction errors so downstream consumers can rely
 * on `path[0]` / `path[1]` access without re-validating.
 */
class DiffObjectRefTest : FunSpec({

    test("rejects empty path") {
        val ex = shouldThrow<IllegalArgumentException> {
            DiffObjectRef(DiffObjectType.TABLE, emptyList())
        }
        ex.message!! shouldContain "must not be empty"
    }

    test("rejects blank path segments") {
        val ex = shouldThrow<IllegalArgumentException> {
            DiffObjectRef(DiffObjectType.TABLE, listOf(""))
        }
        ex.message!! shouldContain "must not be blank"
    }

    test("schema-wide types require arity 1") {
        for (t in listOf(
            DiffObjectType.TABLE,
            DiffObjectType.PRIMARY_KEY,
            DiffObjectType.CUSTOM_TYPE,
            DiffObjectType.SEQUENCE,
            DiffObjectType.VIEW,
            DiffObjectType.FUNCTION,
            DiffObjectType.PROCEDURE,
            DiffObjectType.TRIGGER,
        )) {
            DiffObjectRef(t, listOf("foo"))  // OK
            shouldThrow<IllegalArgumentException> {
                DiffObjectRef(t, listOf("foo", "bar"))
            }
        }
    }

    test("table-scoped types require arity 2") {
        for (t in listOf(DiffObjectType.COLUMN, DiffObjectType.CONSTRAINT, DiffObjectType.INDEX)) {
            DiffObjectRef(t, listOf("orders", "name"))  // OK
            shouldThrow<IllegalArgumentException> {
                DiffObjectRef(t, listOf("orders"))
            }
            shouldThrow<IllegalArgumentException> {
                DiffObjectRef(t, listOf("orders", "name", "extra"))
            }
        }
    }

    test("displayName joins path with dots") {
        DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "status"))
            .displayName shouldBe "orders.status"
        DiffObjectRef(DiffObjectType.TABLE, listOf("orders"))
            .displayName shouldBe "orders"
    }

    test("rootName returns the leading path segment") {
        DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("orders", "fk_x"))
            .rootName shouldBe "orders"
    }
})
