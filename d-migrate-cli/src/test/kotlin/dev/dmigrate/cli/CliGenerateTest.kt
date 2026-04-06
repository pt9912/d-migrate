package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CliGenerateTest : FunSpec({

    fun resourcePath(name: String): String =
        CliGenerateTest::class.java.getResource("/$name")!!.path

    fun cli() = DMigrate().subcommands(SchemaCommand())

    test("schema generate with valid schema and --target postgresql exits successfully") {
        shouldNotThrowAny {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("valid-schema.yaml"), "--target", "postgresql"))
        }
    }

    test("schema generate with valid schema and --target mysql exits successfully") {
        shouldNotThrowAny {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("valid-schema.yaml"), "--target", "mysql"))
        }
    }

    test("schema generate with valid schema and --target sqlite exits successfully") {
        shouldNotThrowAny {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("valid-schema.yaml"), "--target", "sqlite"))
        }
    }

    test("schema generate with invalid target exits with code 2") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("valid-schema.yaml"), "--target", "oracle"))
        }
        ex.statusCode shouldBe 2
    }

    test("schema generate with broken YAML exits with code 7") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("broken.yaml"), "--target", "postgresql"))
        }
        ex.statusCode shouldBe 7
    }

    test("schema generate with invalid schema exits with code 3") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("invalid-schema.yaml"), "--target", "postgresql"))
        }
        ex.statusCode shouldBe 3
    }
})
