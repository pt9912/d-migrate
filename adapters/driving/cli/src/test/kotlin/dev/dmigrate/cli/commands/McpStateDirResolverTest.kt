package dev.dmigrate.cli.commands

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * §6.21 resolver covers:
 *  - CLI > Env > Tempdir precedence
 *  - relative path normalisation against the working directory
 *  - the `owned` flag (tempdir owned; operator-supplied not)
 */
class McpStateDirResolverTest : FunSpec({

    test("CLI option wins over env and is marked operator-supplied") {
        val cli = Path.of("/srv/dmigrate/state")
        val env: (String) -> String? = { name ->
            if (name == StateDirResolver.ENV_STATE_DIR) "/etc/should-be-ignored" else null
        }

        val resolved = StateDirResolver.resolve(cliOption = cli, env = env)

        resolved.path shouldBe cli.normalize()
        resolved.owned.shouldBeFalse()
    }

    test("env DMIGRATE_MCP_STATE_DIR used when CLI option is null") {
        val env: (String) -> String? = { name ->
            if (name == StateDirResolver.ENV_STATE_DIR) "/var/lib/dmigrate" else null
        }

        val resolved = StateDirResolver.resolve(cliOption = null, env = env)

        resolved.path shouldBe Path.of("/var/lib/dmigrate")
        resolved.owned.shouldBeFalse()
    }

    test("blank env value is ignored, falls through to tempdir") {
        val tempDir = Files.createTempDirectory("dmigrate-mcp-test-")
        try {
            val env: (String) -> String? = { name ->
                if (name == StateDirResolver.ENV_STATE_DIR) "   " else null
            }
            val resolved = StateDirResolver.resolve(
                cliOption = null,
                env = env,
                tempDirFactory = { tempDir },
            )
            resolved.path shouldBe tempDir.toAbsolutePath().normalize()
            resolved.owned.shouldBeTrue()
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    test("relative CLI path is normalised against the working directory") {
        val workingDir = Path.of("/work/dir").toAbsolutePath()
        val cli = Path.of("./mcp-state")

        val resolved = StateDirResolver.resolve(
            cliOption = cli,
            env = { null },
            workingDir = workingDir,
        )

        resolved.path shouldBe Path.of("/work/dir/mcp-state").normalize()
        resolved.owned.shouldBeFalse()
    }

    test("absolute CLI path is normalised but not joined with workingDir") {
        val workingDir = Path.of("/should/not/matter").toAbsolutePath()
        val cli = Path.of("/srv/dmigrate/./state/")

        val resolved = StateDirResolver.resolve(
            cliOption = cli,
            env = { null },
            workingDir = workingDir,
        )

        resolved.path shouldBe Path.of("/srv/dmigrate/state")
    }

    test("tempdir factory failure surfaces as StateDirConfigError") {
        val ex = shouldThrow<StateDirConfigError> {
            StateDirResolver.resolve(
                cliOption = null,
                env = { null },
                tempDirFactory = { throw java.io.IOException("disk full") },
            )
        }
        ex.message!! shouldContain "could not create temporary state dir"
        ex.message!! shouldContain "disk full"
    }
})
