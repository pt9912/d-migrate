package dev.dmigrate.profiling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ProfilingExceptionsTest : FunSpec({

    test("SchemaIntrospectionError with message only") {
        val ex = SchemaIntrospectionError("introspection failed")
        ex.message shouldBe "introspection failed"
        ex.cause shouldBe null
        ex.shouldBeInstanceOf<ProfilingException>()
        ex.shouldBeInstanceOf<RuntimeException>()
    }

    test("SchemaIntrospectionError with message and cause") {
        val cause = RuntimeException("connection lost")
        val ex = SchemaIntrospectionError("introspection failed", cause)
        ex.message shouldBe "introspection failed"
        ex.cause shouldBe cause
    }

    test("ProfilingQueryError with message only") {
        val ex = ProfilingQueryError("query timeout")
        ex.message shouldBe "query timeout"
        ex.cause shouldBe null
        ex.shouldBeInstanceOf<ProfilingException>()
    }

    test("ProfilingQueryError with message and cause") {
        val cause = IllegalStateException("pool exhausted")
        val ex = ProfilingQueryError("query timeout", cause)
        ex.message shouldBe "query timeout"
        ex.cause shouldBe cause
    }

    test("TypeResolutionError with message only") {
        val ex = TypeResolutionError("unknown type: JSONB")
        ex.message shouldBe "unknown type: JSONB"
        ex.cause shouldBe null
        ex.shouldBeInstanceOf<ProfilingException>()
    }

    test("TypeResolutionError with message and cause") {
        val cause = NullPointerException()
        val ex = TypeResolutionError("unknown type", cause)
        ex.message shouldBe "unknown type"
        ex.cause shouldBe cause
    }
})
