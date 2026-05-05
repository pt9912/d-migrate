package dev.dmigrate.core.cancel

import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TestCancellationTokenSourceTest : FunSpec({

    test("fresh hook source mirrors the production source defaults") {
        val source = TestCancellationTokenSource()
        source.token.isCancellationRequested shouldBe false
        source.observedCheckpoints shouldBe 0
    }

    test("explicit cancel produces an immediately cancelled token") {
        val source = TestCancellationTokenSource()
        source.cancel("immediate")

        source.token.isCancellationRequested shouldBe true
        shouldThrow<OperationCancelledException> {
            source.token.throwIfCancellationRequested()
        }.reason shouldBe "immediate"
    }

    test("cancelAfterCheckpoints(0) cancels on the first checkpoint") {
        val source = TestCancellationTokenSource()
        source.cancelAfterCheckpoints(0, "before-first")

        shouldThrow<OperationCancelledException> {
            source.token.throwIfCancellationRequested()
        }.reason shouldBe "before-first"
        source.observedCheckpoints shouldBe 1
    }

    test("cancelAfterCheckpoints(2) lets two checkpoints pass and throws on the third") {
        val source = TestCancellationTokenSource()
        source.cancelAfterCheckpoints(2, "after-two")

        shouldNotThrow<OperationCancelledException> {
            source.token.throwIfCancellationRequested()
            source.token.throwIfCancellationRequested()
        }
        source.token.isCancellationRequested shouldBe false

        shouldThrow<OperationCancelledException> {
            source.token.throwIfCancellationRequested()
        }.reason shouldBe "after-two"
        source.observedCheckpoints shouldBe 3
    }

    test("subsequent throwIfCancellationRequested keeps throwing the same reason") {
        val source = TestCancellationTokenSource()
        source.cancelAfterCheckpoints(0, "first-trip")

        shouldThrow<OperationCancelledException> {
            source.token.throwIfCancellationRequested()
        }
        // Re-arming after cancel must not overwrite the original reason.
        source.cancelAfterCheckpoints(0, "second-trip")

        shouldThrow<OperationCancelledException> {
            source.token.throwIfCancellationRequested()
        }.reason shouldBe "first-trip"
        source.token.cancellationReason shouldBe "first-trip"
    }

    test("cancelAfterCheckpoints rejects negative arming") {
        val source = TestCancellationTokenSource()
        shouldThrow<IllegalArgumentException> {
            source.cancelAfterCheckpoints(-1)
        }
    }

    test("re-arming before fire overrides the prior arming") {
        val source = TestCancellationTokenSource()
        source.cancelAfterCheckpoints(5, "later")
        source.cancelAfterCheckpoints(1, "sooner")

        // Two checkpoints: first passes, second observes "sooner" and throws.
        source.token.throwIfCancellationRequested()
        shouldThrow<OperationCancelledException> {
            source.token.throwIfCancellationRequested()
        }.reason shouldBe "sooner"
    }

    test("checkpoint counter advances even when not armed") {
        val source = TestCancellationTokenSource()
        repeat(4) { source.token.throwIfCancellationRequested() }
        source.observedCheckpoints shouldBe 4
        source.token.isCancellationRequested shouldBe false
    }
})
