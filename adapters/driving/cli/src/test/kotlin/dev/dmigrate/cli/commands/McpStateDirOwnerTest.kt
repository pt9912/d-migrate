package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpStateDirOwnerTest : FunSpec({

    test("owned tempdir is recursively deleted on cleanup") {
        val dir = Files.createTempDirectory("dmigrate-mcp-owner-")
        Files.createDirectories(dir.resolve("segments/session-1"))
        Files.write(dir.resolve("segments/session-1/0.bin"), byteArrayOf(1, 2, 3))
        Files.createDirectories(dir.resolve("artifacts/ab"))
        Files.write(dir.resolve("artifacts/ab/x.bin"), byteArrayOf(4, 5, 6))

        val owner = StateDirOwner.of(ResolvedStateDir(dir, owned = true))
        owner.cleanupIfOwned()

        Files.exists(dir).shouldBeFalse()
    }

    test("operator-supplied dir survives cleanup") {
        val dir = Files.createTempDirectory("dmigrate-mcp-operator-")
        try {
            Files.write(dir.resolve("keep.txt"), byteArrayOf(7, 8, 9))

            val owner = StateDirOwner.of(ResolvedStateDir(dir, owned = false))
            owner.cleanupIfOwned()

            Files.exists(dir).shouldBeTrue()
            Files.exists(dir.resolve("keep.txt")).shouldBeTrue()
        } finally {
            dir.deleteRecursively()
        }
    }

    test("cleanup is idempotent — second call after deletion is a no-op") {
        val dir = Files.createTempDirectory("dmigrate-mcp-owner-idemp-")
        val owner = StateDirOwner.of(ResolvedStateDir(dir, owned = true))

        owner.cleanupIfOwned()
        owner.cleanupIfOwned()

        Files.exists(dir).shouldBeFalse()
    }
})
