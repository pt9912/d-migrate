package dev.dmigrate.cli.commands

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.deleteRecursively

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class McpCursorKeyringConfigTest : FunSpec({

    fun secret(byte: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(McpCursorKeyringConfig.SECRET_BYTES) { byte.toByte() })

    test("loads signing and validation keys from YAML") {
        val dir = Files.createTempDirectory("dmigrate-mcp-cursor-keyring-")
        try {
            val file = dir.resolve("cursor-keyring.yaml")
            Files.writeString(
                file,
                """
                signing:
                  kid: "cursor-2026-05"
                  secretBase64: "${secret(1)}"
                validation:
                  - kid: "cursor-2026-04"
                    secretBase64: "${secret(2)}"
                """.trimIndent(),
            )

            val keyring = McpCursorKeyringConfig.load(file)

            keyring.signing.kid shouldBe "cursor-2026-05"
            keyring.allValidation.map { it.kid } shouldBe listOf("cursor-2026-05", "cursor-2026-04")
        } finally {
            dir.deleteRecursively()
        }
    }

    test("rejects short secrets before wiring the server") {
        val dir = Files.createTempDirectory("dmigrate-mcp-cursor-keyring-short-")
        try {
            val file = dir.resolve("cursor-keyring.yaml")
            Files.writeString(
                file,
                """
                signing:
                  kid: "cursor-short"
                  secretBase64: "${Base64.getEncoder().encodeToString(ByteArray(8) { 1 })}"
                validation: []
                """.trimIndent(),
            )

            shouldThrow<McpCursorKeyringConfigError> {
                McpCursorKeyringConfig.load(file)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("generated keyring YAML is loadable") {
        val dir = Files.createTempDirectory("dmigrate-mcp-cursor-keyring-generated-")
        try {
            val file = dir.resolve("cursor-keyring.yaml")
            Files.writeString(file, McpCursorKeyringConfig.renderSingleKeyFile("cursor-generated"))

            val keyring = McpCursorKeyringConfig.load(file)

            keyring.signing.kid shouldBe "cursor-generated"
        } finally {
            dir.deleteRecursively()
        }
    }
})
