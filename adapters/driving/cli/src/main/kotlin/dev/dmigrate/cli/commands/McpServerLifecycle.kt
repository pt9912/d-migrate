package dev.dmigrate.cli.commands

import dev.dmigrate.mcp.server.McpServerHandle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-shot lifecycle wrap for a started `mcp serve` per
 * `ImpPlan-0.9.6-C.md` §6.21 (Z. 1150–1169).
 *
 * Wires three idempotent cleanup actions — `handle.stop()`,
 * `lock.close()`, and `owner.cleanupIfOwned()` — into both a JVM
 * shutdown hook AND the normal-return finally block. The
 * [AtomicBoolean] guard guarantees they run exactly once regardless
 * of which path fires first.
 *
 * The shutdown-hook wiring is critical: `McpServerHandle.awaitTermination`
 * for HTTP defaults to `Thread.sleep(Long.MAX_VALUE)`. `KtorHandle.stop`
 * does NOT interrupt the main thread, so a SIGINT-only shutdown-hook
 * that calls `stop()` would never reach the surrounding `try/finally`
 * cleanup. Putting cleanup INSIDE the hook closes that gap.
 *
 * [registerShutdownHook] is injectable so unit tests can drive the
 * lifecycle without poisoning the JVM-wide hook list.
 */
internal object McpServerLifecycle {

    fun run(
        handle: McpServerHandle,
        lock: McpStateDirLock,
        owner: StateDirOwner,
        registerShutdownHook: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) },
    ) {
        val cleanedUp = AtomicBoolean(false)
        val cleanup = Runnable {
            if (!cleanedUp.compareAndSet(false, true)) return@Runnable
            try {
                handle.stop()
            } finally {
                try {
                    lock.close()
                } finally {
                    owner.cleanupIfOwned()
                }
            }
        }
        registerShutdownHook(Thread(cleanup, "dmigrate-mcp-shutdown"))
        try {
            handle.awaitTermination()
        } finally {
            cleanup.run()
        }
    }
}
