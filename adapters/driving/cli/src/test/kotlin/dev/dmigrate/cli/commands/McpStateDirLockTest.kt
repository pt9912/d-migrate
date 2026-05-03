package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpStateDirLockTest : FunSpec({

    test("acquire writes a payload containing pid, instance, version, startedAt") {
        val dir = Files.createTempDirectory("dmigrate-mcp-lock-")
        try {
            val outcome = McpStateDirLock.tryAcquire(
                stateDir = dir,
                version = "0.9.6-test",
                pidProvider = { 42L },
            )
            val acquired = outcome.shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>()

            val payload = Files.readString(dir.resolve(McpStateDirLock.LOCKFILE_NAME), StandardCharsets.UTF_8)
            payload shouldContain "\"pid\":42"
            payload shouldContain "\"version\":\"0.9.6-test\""
            payload shouldContain "\"instance\":\"${acquired.lock.instanceId}\""
            payload shouldContain "\"startedAt\":\""

            acquired.lock.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    test("second tryAcquire while first is held returns Conflict with payload diagnostic") {
        val dir = Files.createTempDirectory("dmigrate-mcp-lock-conflict-")
        try {
            val first = McpStateDirLock.tryAcquire(dir, "v1", pidProvider = { 100L })
                .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>()
            try {
                val second = McpStateDirLock.tryAcquire(dir, "v2", pidProvider = { 200L })
                val conflict = second.shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Conflict>()
                conflict.diagnostic shouldContain dir.toString()
                conflict.diagnostic shouldContain "\"pid\":100"
            } finally {
                first.lock.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("tryAcquire after release succeeds and overwrites the previous payload") {
        val dir = Files.createTempDirectory("dmigrate-mcp-lock-reuse-")
        try {
            val first = McpStateDirLock.tryAcquire(dir, "v1", pidProvider = { 100L })
                .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>()
            first.lock.close()

            val second = McpStateDirLock.tryAcquire(dir, "v2", pidProvider = { 200L })
                .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>()
            try {
                val payload = Files.readString(
                    dir.resolve(McpStateDirLock.LOCKFILE_NAME),
                    StandardCharsets.UTF_8,
                )
                payload shouldContain "\"pid\":200"
                payload shouldContain "\"version\":\"v2\""
            } finally {
                second.lock.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("stale lockfile content without an active OS lock can be overwritten") {
        val dir = Files.createTempDirectory("dmigrate-mcp-lock-stale-")
        try {
            val lockfile = dir.resolve(McpStateDirLock.LOCKFILE_NAME)
            // Hand-rolled stale payload — simulates a previous crashed
            // process that left the file behind without releasing the OS
            // lock (which the kernel actually does on process exit).
            Files.writeString(lockfile, """{"pid":99999,"stale":true}""", StandardCharsets.UTF_8)

            val outcome = McpStateDirLock.tryAcquire(dir, "v3", pidProvider = { 300L })
            val acquired = outcome.shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>()
            try {
                val payload = Files.readString(lockfile, StandardCharsets.UTF_8)
                payload shouldContain "\"pid\":300"
                payload.contains("stale") shouldBe false
            } finally {
                acquired.lock.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("close is idempotent — second close after first does not throw") {
        val dir = Files.createTempDirectory("dmigrate-mcp-lock-idemp-")
        try {
            val acquired = McpStateDirLock.tryAcquire(dir, "v")
                .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>()

            acquired.lock.close()
            acquired.lock.close()

            // After release, a fresh tryAcquire must succeed.
            val again = McpStateDirLock.tryAcquire(dir, "v")
                .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>()
            again.lock.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    test("payload with embedded quotes / backslashes is JSON-escaped, not corrupted") {
        // Defends the lockfile's parseability when `cliVersion()` ever
        // picks up a git-describe string with `"` or `\` characters.
        val rendered = LockPayloadIo.render(
            pid = 7L,
            startedAt = "2026-05-03T10:00:00Z",
            instance = "in\"st",
            version = """0.9.6"-dirty\with\backslash""",
        )
        rendered shouldContain "\"instance\":\"in\\\"st\""
        rendered shouldContain "\"version\":\"0.9.6\\\"-dirty\\\\with\\\\backslash\""
    }

    test("acquire diagnostic stays informative when the existing payload is unreadable garbage") {
        val dir = Files.createTempDirectory("dmigrate-mcp-lock-garbage-")
        try {
            val first = McpStateDirLock.tryAcquire(dir, "v1")
                .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>()
            try {
                // Overwrite the payload with garbage while the lock is
                // still held; the read-back is best-effort and just
                // included in the diagnostic verbatim.
                Files.writeString(
                    dir.resolve(McpStateDirLock.LOCKFILE_NAME),
                    "<<not-json>>",
                    StandardCharsets.UTF_8,
                )

                val second = McpStateDirLock.tryAcquire(dir, "v2")
                val conflict = second.shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Conflict>()
                conflict.diagnostic shouldContain dir.toString()
                conflict.diagnostic.contains("<<not-json>>").shouldBeTrue()
            } finally {
                first.lock.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
})
