package dev.dmigrate.server.ports.memory

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.server.ports.SignalOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * LF-012 / LN-011 / LN-017 / LN-027 §7.2 contract test for [InMemoryWorkerHandleRegistry].
 */
class InMemoryWorkerHandleRegistryTest : FunSpec({

    test("signal returns NotFound for an unregistered jobId") {
        val registry = InMemoryWorkerHandleRegistry()
        registry.signal("missing", reason = "user requested") shouldBe SignalOutcome.NotFound
    }

    test("signal cancels the registered token source with the given reason") {
        val registry = InMemoryWorkerHandleRegistry()
        val source = CancellationTokenSource.create()
        registry.register("job_1", source)

        registry.signal("job_1", reason = "user requested") shouldBe SignalOutcome.Signaled
        source.token.isCancellationRequested shouldBe true
        source.token.cancellationReason shouldBe "user requested"
    }

    test("signal is idempotent — second call keeps the first reason") {
        val registry = InMemoryWorkerHandleRegistry()
        val source = CancellationTokenSource.create()
        registry.register("job_1", source)

        registry.signal("job_1", reason = "first")
        registry.signal("job_1", reason = "second")

        source.token.cancellationReason shouldBe "first"
    }

    test("unregister removes the source — subsequent signal returns NotFound") {
        val registry = InMemoryWorkerHandleRegistry()
        val source = CancellationTokenSource.create()
        registry.register("job_1", source)
        registry.unregister("job_1")

        registry.signal("job_1", reason = "after unregister") shouldBe SignalOutcome.NotFound
    }

    test("unregister on unknown jobId is a no-op") {
        val registry = InMemoryWorkerHandleRegistry()
        registry.unregister("never-registered")
        // No exception is the assertion.
    }

    test("two distinct jobs have independent cancel state") {
        val registry = InMemoryWorkerHandleRegistry()
        val sourceA = CancellationTokenSource.create()
        val sourceB = CancellationTokenSource.create()
        registry.register("job_a", sourceA)
        registry.register("job_b", sourceB)

        registry.signal("job_a", reason = "a-reason") shouldBe SignalOutcome.Signaled
        sourceA.token.isCancellationRequested shouldBe true
        sourceB.token.isCancellationRequested shouldBe false
    }
})
