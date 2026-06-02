package dev.dmigrate.core.cancel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class CancellationTokenTest : FunSpec({

    // --- CancellationToken.none() ---

    test("none() reports no cancellation") {
        val token = CancellationToken.none()
        token.isCancellationRequested shouldBe false
        token.cancellationReason.shouldBeNull()
    }

    test("none().throwIfCancellationRequested is a no-op") {
        CancellationToken.none().throwIfCancellationRequested()
    }

    test("none() returns the same singleton instance") {
        // Sanity: callers can hold onto none() across runs without allocation churn.
        val a = CancellationToken.none()
        val b = CancellationToken.none()
        (a === b) shouldBe true
    }

    // --- DefaultCancellationTokenSource basic semantics ---

    test("fresh source token is not cancelled") {
        val source = CancellationTokenSource.create()
        source.token.isCancellationRequested shouldBe false
        source.token.cancellationReason.shouldBeNull()
    }

    test("cancelled token throws OperationCancelledException with reason") {
        val source = CancellationTokenSource.create()
        source.cancel("user requested abort")

        source.token.isCancellationRequested shouldBe true
        source.token.cancellationReason shouldBe "user requested abort"

        val error = shouldThrow<OperationCancelledException> {
            source.token.throwIfCancellationRequested()
        }
        error.reason shouldBe "user requested abort"
        error.message shouldBe "user requested abort"
    }

    test("cancel without reason still flips the token") {
        val source = CancellationTokenSource.create()
        source.cancel()

        source.token.isCancellationRequested shouldBe true
        source.token.cancellationReason.shouldBeNull()

        val error = shouldThrow<OperationCancelledException> {
            source.token.throwIfCancellationRequested()
        }
        error.reason.shouldBeNull()
        error.message shouldBe "operation cancelled"
    }

    test("OperationCancelledException preserves cause when wrapped") {
        val rootCause = IllegalStateException("driver shutdown")
        val error = OperationCancelledException("transport closed", rootCause)
        error.reason shouldBe "transport closed"
        error.cause shouldBe rootCause
    }

    test("OperationCancelledException defaults source to JOB_CANCEL (LF-012 / LN-011 / LN-017 / LN-027)") {
        // LF-012 / LN-011 / LN-017 / LN-027: backward-compat default — legacy cancel callers, die
        // den source-Parameter nicht setzen, landen auf der Cancel-Seite
        // (nicht auf RUNNER_TIMEOUT/Failed).
        OperationCancelledException("user-cancel").source shouldBe
            OperationCancelSource.JOB_CANCEL
        OperationCancelledException("with-cause", IllegalStateException("x")).source shouldBe
            OperationCancelSource.JOB_CANCEL
    }

    test("OperationCancelledException carries explicit RUNNER_TIMEOUT source") {
        val ex = OperationCancelledException(
            reason = "budget-exhausted",
            source = OperationCancelSource.RUNNER_TIMEOUT,
        )
        ex.source shouldBe OperationCancelSource.RUNNER_TIMEOUT
        ex.reason shouldBe "budget-exhausted"
    }

    // --- Idempotent cancel — first reason wins ---

    test("repeated cancel calls preserve the first reason") {
        val source = CancellationTokenSource.create()
        source.cancel("first")
        source.cancel("second")
        source.cancel(null)

        source.token.cancellationReason shouldBe "first"
        shouldThrow<OperationCancelledException> {
            source.token.throwIfCancellationRequested()
        }.reason shouldBe "first"
    }

    test("first cancel without reason is preserved against later reasons") {
        val source = CancellationTokenSource.create()
        source.cancel(null)
        source.cancel("late attempt")

        source.token.cancellationReason.shouldBeNull()
    }

    // --- Cross-thread visibility ---

    test("cancel from another thread becomes visible deterministically") {
        val source = CancellationTokenSource.create()
        val cancellerStarted = java.util.concurrent.CountDownLatch(1)
        val cancellerDone = java.util.concurrent.CountDownLatch(1)

        val cancellerThread = Thread {
            cancellerStarted.countDown()
            source.cancel("from worker thread")
            cancellerDone.countDown()
        }
        cancellerThread.start()

        cancellerStarted.await()
        cancellerDone.await()

        source.token.isCancellationRequested shouldBe true
        source.token.cancellationReason shouldBe "from worker thread"
        cancellerThread.join()
    }

    test("token observers in another thread see cancel") {
        val source = CancellationTokenSource.create()
        val observed = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val observerReady = java.util.concurrent.CountDownLatch(1)
        val observerDone = java.util.concurrent.CountDownLatch(1)

        val observer = Thread {
            observerReady.countDown()
            // Spin briefly until the producer thread has cancelled. The
            // production token uses AtomicReference so this terminates.
            while (!source.token.isCancellationRequested) {
                Thread.yield()
            }
            observed.set(source.token.cancellationReason)
            observerDone.countDown()
        }
        observer.start()
        observerReady.await()
        source.cancel("cross-thread reason")
        observerDone.await()
        observed.get() shouldBe "cross-thread reason"
        observer.join()
    }
})
