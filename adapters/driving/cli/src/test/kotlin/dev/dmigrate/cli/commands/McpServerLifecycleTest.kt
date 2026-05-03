package dev.dmigrate.cli.commands

import dev.dmigrate.mcp.server.McpServerHandle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.deleteRecursively

/**
 * Pins the §6.21 lifecycle wrap so the SIGINT-via-shutdown-hook path
 * actually cleans CLI-owned tempdirs, even when `awaitTermination`
 * never returns naturally (HTTP default).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpServerLifecycleTest : FunSpec({

    class CountingHandle(
        private val awaitImpl: () -> Unit = {},
    ) : McpServerHandle {
        override val boundPort: Int = 0
        val stopCount = AtomicInteger(0)
        override fun stop() { stopCount.incrementAndGet() }
        override fun awaitTermination() { awaitImpl() }
    }

    test("normal-return path runs cleanup exactly once") {
        val dir = Files.createTempDirectory("dmigrate-mcp-lifecycle-")
        val lock = McpStateDirLock.tryAcquire(dir, "v")
            .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>().lock
        val owner = StateDirOwner.of(ResolvedStateDir(dir, owned = true))
        val handle = CountingHandle()
        val capturedHook = AtomicReference<Thread?>()

        McpServerLifecycle.run(
            handle = handle,
            lock = lock,
            owner = owner,
            registerShutdownHook = { capturedHook.set(it) },
        )

        handle.stopCount.get() shouldBe 1
        Files.exists(dir).shouldBeFalse()

        // Hook still registered (we did not deregister it) — fire it
        // manually and confirm the AtomicBoolean guard makes it a no-op.
        capturedHook.get()!!.run()
        handle.stopCount.get() shouldBe 1
    }

    test("hook firing during awaitTermination runs cleanup once; finally is a no-op") {
        // Simulates the HTTP-SIGINT path: while the main thread is
        // blocked in awaitTermination, the JVM fires the shutdown hook
        // synchronously. The hook must clean up (because the JVM may
        // hard-exit immediately after, killing the main thread before
        // the surrounding try/finally can run). Once awaitTermination
        // does return, the lifecycle's own finally cleanup is reduced
        // to a no-op by the AtomicBoolean guard.
        val dir = Files.createTempDirectory("dmigrate-mcp-lifecycle-hook-")
        val lock = McpStateDirLock.tryAcquire(dir, "v")
            .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>().lock
        val owner = StateDirOwner.of(ResolvedStateDir(dir, owned = true))
        val capturedHook = AtomicReference<Thread?>()
        val handle = CountingHandle(awaitImpl = {
            // Drive the hook synchronously while "blocking" — same
            // observable order as a real shutdown hook firing while
            // the main thread is parked in Thread.sleep.
            capturedHook.get()!!.run()
        })

        McpServerLifecycle.run(
            handle = handle,
            lock = lock,
            owner = owner,
            registerShutdownHook = { capturedHook.set(it) },
        )

        handle.stopCount.get() shouldBe 1
        Files.exists(dir).shouldBeFalse()
    }

    test("operator-supplied dir survives the lifecycle wrap") {
        val dir = Files.createTempDirectory("dmigrate-mcp-lifecycle-operator-")
        try {
            val lock = McpStateDirLock.tryAcquire(dir, "v")
                .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>().lock
            val owner = StateDirOwner.of(ResolvedStateDir(dir, owned = false))
            val handle = CountingHandle()

            McpServerLifecycle.run(
                handle = handle,
                lock = lock,
                owner = owner,
                registerShutdownHook = { /* no-op */ },
            )

            Files.exists(dir).shouldBeTrue()
        } finally {
            dir.deleteRecursively()
        }
    }
})
