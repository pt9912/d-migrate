package dev.dmigrate.cli.commands

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.deleteRecursively

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpStateDirValidatorTest : FunSpec({

    test("existing readable+writable directory passes") {
        val dir = Files.createTempDirectory("dmigrate-mcp-validator-")
        try {
            StateDirValidator.validate(dir)
            Files.isDirectory(dir).shouldBeTrue()
        } finally {
            dir.deleteRecursively()
        }
    }

    test("non-existing directory is created") {
        val parent = Files.createTempDirectory("dmigrate-mcp-validator-")
        val child = parent.resolve("not-yet-created/sub")
        try {
            StateDirValidator.validate(child)
            Files.isDirectory(child).shouldBeTrue()
        } finally {
            parent.deleteRecursively()
        }
    }

    test("regular file at the target path fails") {
        val file = Files.createTempFile("dmigrate-mcp-not-a-dir-", ".tmp")
        try {
            val ex = shouldThrow<StateDirConfigError> {
                StateDirValidator.validate(file)
            }
            ex.message!! shouldContain "is not a directory"
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("non-writable POSIX directory fails") {
        // Skip when the test JVM runs as root: POSIX permission bits
        // do not constrain root, so `Files.isWritable` returns true
        // even on r-xr-xr-x. The Docker build stage runs as root, so
        // this assertion can only be exercised on POSIX hosts where
        // the test user is unprivileged.
        if (System.getProperty("user.name") == "root") return@test
        val dir = Files.createTempDirectory("dmigrate-mcp-readonly-")
        val supportsPosix = try {
            Files.setPosixFilePermissions(
                dir,
                PosixFilePermissions.fromString("r-xr-xr-x"),
            )
            true
        } catch (_: UnsupportedOperationException) {
            false
        }
        try {
            if (!supportsPosix) return@test
            val ex = shouldThrow<StateDirConfigError> {
                StateDirValidator.validate(dir)
            }
            ex.message!! shouldContain "not writable"
        } finally {
            try {
                Files.setPosixFilePermissions(
                    dir,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
            } catch (_: UnsupportedOperationException) {
                // best-effort cleanup on non-POSIX FS
            }
            dir.deleteRecursively()
        }
    }
})
