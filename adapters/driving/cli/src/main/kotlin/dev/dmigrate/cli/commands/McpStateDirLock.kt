package dev.dmigrate.cli.commands

import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-writer advisory lock on `<stateDir>/.lock` per
 * `ImpPlan-0.9.6-C.md` §6.21.
 *
 * The lock is OS-backed via [FileChannel.tryLock]: a crashed process
 * cannot poison the state dir because the kernel releases the lock
 * with the file descriptor, even when the lockfile bytes survive.
 * Stale lockfile contents (no active OS lock) are overwritten by the
 * next successful start.
 *
 * The diagnostic payload (`pid`, `startedAt`, `instance`, `version`)
 * is intended for operator messages — never as a poor-man's
 * existence-based lock substitute.
 */
internal class McpStateDirLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    val instanceId: String,
) : AutoCloseable {

    private val released = AtomicBoolean(false)

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        try {
            if (lock.isValid) lock.release()
        } catch (_: IOException) {
            // best effort — JVM exit will release the OS lock anyway.
        }
        try {
            channel.close()
        } catch (_: IOException) {
            // best effort
        }
    }

    sealed interface AcquireOutcome {
        data class Acquired(val lock: McpStateDirLock) : AcquireOutcome
        data class Conflict(val diagnostic: String) : AcquireOutcome
        data class Failed(val message: String) : AcquireOutcome
    }

    companion object {
        const val LOCKFILE_NAME: String = ".lock"

        fun tryAcquire(
            stateDir: Path,
            version: String,
            clock: Clock = Clock.systemUTC(),
            pidProvider: () -> Long = { ManagementFactory.getRuntimeMXBean().pid },
        ): AcquireOutcome {
            val lockfile = stateDir.resolve(LOCKFILE_NAME)
            val channel = try {
                FileChannel.open(
                    lockfile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
            } catch (failure: IOException) {
                return AcquireOutcome.Failed(
                    "could not open lockfile $lockfile: ${failure.message}",
                )
            }

            val acquired: FileLock? = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // In-process collision — another wiring in this JVM
                // already holds the lock on the same dir. Treat as a
                // conflict so callers get a deterministic exit 2.
                null
            } catch (failure: IOException) {
                try { channel.close() } catch (_: IOException) {}
                return AcquireOutcome.Failed(
                    "could not acquire lock on $lockfile: ${failure.message}",
                )
            }

            if (acquired == null) {
                val existing = LockPayloadIo.readBestEffort(lockfile)
                try { channel.close() } catch (_: IOException) {}
                val diag = if (existing != null) {
                    "another `mcp serve` is active for state dir $stateDir " +
                        "(lockfile payload: $existing)"
                } else {
                    "another `mcp serve` is active for state dir $stateDir " +
                        "(lockfile payload unreadable)"
                }
                return AcquireOutcome.Conflict(diag)
            }

            val instance = UUID.randomUUID().toString()
            val payload = LockPayloadIo.render(
                pid = pidProvider(),
                startedAt = Instant.now(clock).toString(),
                instance = instance,
                version = version,
            )
            try {
                LockPayloadIo.write(lockfile, payload)
            } catch (failure: IOException) {
                try { acquired.release() } catch (_: IOException) {}
                try { channel.close() } catch (_: IOException) {}
                return AcquireOutcome.Failed(
                    "could not write lockfile payload $lockfile: ${failure.message}",
                )
            }

            return AcquireOutcome.Acquired(McpStateDirLock(channel, acquired, instance))
        }
    }
}
