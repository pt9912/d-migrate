package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import java.io.ByteArrayInputStream
import java.io.File

class CliTest : FunSpec({

    fun resourcePath(name: String): String =
        CliTest::class.java.getResource("/$name")!!.path

    fun cli() = DMigrate().subcommands(SchemaCommand())

    // Speist `bytes` als stdin ein und stellt System.in danach wieder her (Tests laufen
    // innerhalb einer FunSpec sequenziell, daher ist das globale System.in unkritisch).
    fun withStdin(bytes: ByteArray, block: () -> Unit) {
        val orig = System.`in`
        try {
            System.setIn(ByteArrayInputStream(bytes))
            block()
        } finally {
            System.setIn(orig)
        }
    }

    test("schema validate with valid schema exits successfully") {
        shouldNotThrowAny {
            cli().parse(listOf("schema", "validate", "--source", resourcePath("valid-schema.yaml")))
        }
    }

    test("schema validate with invalid schema exits with code 3") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "validate", "--source", resourcePath("invalid-schema.yaml")))
        }
        ex.statusCode shouldBe 3
    }

    test("schema validate with broken YAML exits with code 7") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "validate", "--source", resourcePath("broken.yaml")))
        }
        ex.statusCode shouldBe 7
    }

    test("schema validate with JSON output format") {
        shouldNotThrowAny {
            cli().parse(listOf("--output-format", "json", "schema", "validate", "--source", resourcePath("valid-schema.yaml")))
        }
    }

    test("schema validate with YAML output format") {
        shouldNotThrowAny {
            cli().parse(listOf("--output-format", "yaml", "schema", "validate", "--source", resourcePath("valid-schema.yaml")))
        }
    }

    test("schema validate with quiet flag") {
        shouldNotThrowAny {
            cli().parse(listOf("--quiet", "schema", "validate", "--source", resourcePath("valid-schema.yaml")))
        }
    }

    test("schema validate with no-color flag") {
        shouldNotThrowAny {
            cli().parse(listOf("--no-color", "schema", "validate", "--source", resourcePath("valid-schema.yaml")))
        }
    }

    test("invalid schema with JSON output exits with code 3") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("--output-format", "json", "schema", "validate", "--source", resourcePath("invalid-schema.yaml")))
        }
        ex.statusCode shouldBe 3
    }

    test("invalid schema with YAML output exits with code 3") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("--output-format", "yaml", "schema", "validate", "--source", resourcePath("invalid-schema.yaml")))
        }
        ex.statusCode shouldBe 3
    }

    // ── JSON schema file support ────────────────

    test("schema validate with valid JSON schema exits successfully") {
        shouldNotThrowAny {
            cli().parse(listOf("schema", "validate", "--source", resourcePath("valid-schema.json")))
        }
    }

    // ── stdin support (--source -) — spec 10.3 ────────────

    test("schema validate reads a valid YAML schema from stdin (--source -)") {
        withStdin(File(resourcePath("valid-schema.yaml")).readBytes()) {
            shouldNotThrowAny {
                cli().parse(listOf("schema", "validate", "--source", "-"))
            }
        }
    }

    test("schema validate reads a valid JSON schema from stdin (--source -)") {
        withStdin(File(resourcePath("valid-schema.json")).readBytes()) {
            shouldNotThrowAny {
                cli().parse(listOf("schema", "validate", "--source", "-"))
            }
        }
    }

    test("schema validate from stdin with invalid schema exits with code 3") {
        withStdin(File(resourcePath("invalid-schema.yaml")).readBytes()) {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(listOf("schema", "validate", "--source", "-"))
            }
            ex.statusCode shouldBe 3
        }
    }
})
