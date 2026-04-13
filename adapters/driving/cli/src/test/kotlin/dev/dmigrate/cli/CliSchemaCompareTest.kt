package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

class CliSchemaCompareTest : FunSpec({

    fun resourcePath(name: String): String =
        CliSchemaCompareTest::class.java.getResource("/$name")!!.path

    fun cli() = DMigrate().subcommands(SchemaCommand())

    fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val capture = ByteArrayOutputStream()
        System.setOut(PrintStream(capture, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return capture.toString(Charsets.UTF_8)
    }

    // §8.3: --help
    test("schema compare --help is reachable") {
        shouldThrow<CliktError> {
            cli().parse(listOf("schema", "compare", "--help"))
        }
    }

    // §8.3: identical -> no ProgramResult
    test("identical schemas exit successfully") {
        val src = resourcePath("valid-schema.yaml")
        shouldNotThrowAny {
            cli().parse(listOf("schema", "compare", "--source", src, "--target", src))
        }
    }

    // §8.3: different -> ProgramResult(1)
    test("different schemas exit with code 1") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema-b.yaml")))
        }
        ex.statusCode shouldBe 1
    }

    // §8.3: broken YAML -> ProgramResult(7)
    test("broken YAML exits with code 7") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("broken.yaml"),
                "--target", resourcePath("valid-schema.yaml")))
        }
        ex.statusCode shouldBe 7
    }

    // §8.3: invalid schema -> ProgramResult(3)
    test("invalid schema exits with code 3") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("invalid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml")))
        }
        ex.statusCode shouldBe 3
    }

    // §8.3: --output writes file
    test("--output writes file") {
        val tmp = Files.createTempFile("compare-", ".txt")
        try {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml"),
                "--output", tmp.toString()))
            val content = tmp.readText()
            content shouldContain "IDENTICAL"
        } finally {
            tmp.deleteIfExists()
        }
    }

    // §8.3: --output in non-existent parent
    test("--output in non-existent parent directory creates directory and writes file") {
        val tmpDir = Files.createTempDirectory("compare-parent-")
        val nested = tmpDir.resolve("sub/dir/out.txt")
        try {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml"),
                "--output", nested.toString()))
            val content = nested.readText()
            content shouldContain "IDENTICAL"
        } finally {
            nested.deleteIfExists()
            tmpDir.toFile().deleteRecursively()
        }
    }

    // §8.3: --output-format json
    test("--output-format json produces json output") {
        val output = captureStdout {
            cli().parse(listOf("--output-format", "json", "schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml")))
        }
        output shouldContain """"command": "schema.compare""""
        output shouldContain """"status": "identical""""
    }

    // §8.3: --output-format yaml
    test("--output-format yaml produces yaml output") {
        val output = captureStdout {
            cli().parse(listOf("--output-format", "yaml", "schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml")))
        }
        output shouldContain "command: schema.compare"
        output shouldContain "status: identical"
    }

    // §8.3: different schemas with json format
    test("different schemas with json format produces json diff") {
        val output = captureStdout {
            try {
                cli().parse(listOf("--output-format", "json", "schema", "compare",
                    "--source", resourcePath("valid-schema.yaml"),
                    "--target", resourcePath("valid-schema-b.yaml")))
            } catch (_: ProgramResult) { /* exit 1 expected */ }
        }
        output shouldContain """"status": "different""""
    }
})
