package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    test("generate command accepts kid and renders a keyring") {
        McpCursorKeyGenerateCommand().parse(listOf("--kid", "cursor-cli-generated"))
    }

    test("validate command accepts a loadable keyring file") {
        val dir = Files.createTempDirectory("dmigrate-mcp-cursor-keyring-cli-valid-")
        try {
            val file = dir.resolve("cursor-keyring.yaml")
            Files.writeString(file, McpCursorKeyringConfig.renderSingleKeyFile("cursor-cli-valid"))

            McpCursorKeyValidateCommand().parse(listOf("--cursor-keyring-file", file.toString()))
        } finally {
            dir.deleteRecursively()
        }
    }

    test("validate command exits 2 for an invalid keyring file") {
        val dir = Files.createTempDirectory("dmigrate-mcp-cursor-keyring-cli-invalid-")
        try {
            val file = dir.resolve("cursor-keyring.yaml")
            Files.writeString(file, "validation: []\n")

            val exit = shouldThrow<ProgramResult> {
                McpCursorKeyValidateCommand().parse(listOf("--cursor-keyring-file", file.toString()))
            }

            exit.statusCode shouldBe 2
        } finally {
            dir.deleteRecursively()
        }
    }

    test("loads Base64URL secrets and generated secrets have the required length") {
        val dir = Files.createTempDirectory("dmigrate-mcp-cursor-keyring-url-")
        try {
            val generated = McpCursorKeyringConfig.generateSecretBase64()
            Base64.getDecoder().decode(generated).size shouldBe McpCursorKeyringConfig.SECRET_BYTES

            val file = dir.resolve("cursor-keyring.yaml")
            val base64UrlSecret = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(McpCursorKeyringConfig.SECRET_BYTES) { 7 })
            Files.writeString(
                file,
                """
                signing:
                  kid: "cursor-url"
                  secretBase64: "$base64UrlSecret"
                validation: []
                """.trimIndent(),
            )

            val keyring = McpCursorKeyringConfig.load(file)

            keyring.signing.kid shouldBe "cursor-url"
        } finally {
            dir.deleteRecursively()
        }
    }

    test("rejects malformed keyring shapes with specific diagnostics") {
        val dir = Files.createTempDirectory("dmigrate-mcp-cursor-keyring-invalid-")
        try {
            val missing = shouldThrow<McpCursorKeyringConfigError> {
                McpCursorKeyringConfig.load(dir.resolve("missing.yaml"))
            }
            missing.message shouldContain "not found"

            val nonMap = dir.resolve("non-map.yaml")
            Files.writeString(nonMap, "- nope\n")
            shouldThrow<McpCursorKeyringConfigError> {
                McpCursorKeyringConfig.load(nonMap)
            }.message shouldContain "YAML mapping"

            val noSigning = dir.resolve("no-signing.yaml")
            Files.writeString(noSigning, "validation: []\n")
            shouldThrow<McpCursorKeyringConfigError> {
                McpCursorKeyringConfig.load(noSigning)
            }.message shouldContain "signing mapping"

            val validationNotMap = dir.resolve("validation-not-map.yaml")
            Files.writeString(
                validationNotMap,
                """
                signing:
                  kid: "cursor"
                  secretBase64: "${secret(3)}"
                validation:
                  - "bad"
                """.trimIndent(),
            )
            shouldThrow<McpCursorKeyringConfigError> {
                McpCursorKeyringConfig.load(validationNotMap)
            }.message shouldContain "validation[0] must be a mapping"

            val missingKid = dir.resolve("missing-kid.yaml")
            Files.writeString(
                missingKid,
                """
                signing:
                  secretBase64: "${secret(4)}"
                validation: []
                """.trimIndent(),
            )
            shouldThrow<McpCursorKeyringConfigError> {
                McpCursorKeyringConfig.load(missingKid)
            }.message shouldContain "signing.kid"

            val badSecret = dir.resolve("bad-secret.yaml")
            Files.writeString(
                badSecret,
                """
                signing:
                  kid: "cursor"
                  secretBase64: "not base64"
                validation: []
                """.trimIndent(),
            )
            shouldThrow<McpCursorKeyringConfigError> {
                McpCursorKeyringConfig.load(badSecret)
            }.message shouldContain "Base64"

            val missingSecret = dir.resolve("missing-secret.yaml")
            Files.writeString(
                missingSecret,
                """
                signing:
                  kid: "cursor"
                validation: []
                """.trimIndent(),
            )
            shouldThrow<McpCursorKeyringConfigError> {
                McpCursorKeyringConfig.load(missingSecret)
            }.message shouldContain "signing.secretBase64"

            val blankKid = dir.resolve("blank-kid.yaml")
            Files.writeString(
                blankKid,
                """
                signing:
                  kid: " "
                  secretBase64: "${secret(5)}"
                validation: []
                """.trimIndent(),
            )
            shouldThrow<McpCursorKeyringConfigError> {
                McpCursorKeyringConfig.load(blankKid)
            }.message shouldContain "non-blank"

            val collidingValidationKey = dir.resolve("colliding-validation-key.yaml")
            Files.writeString(
                collidingValidationKey,
                """
                signing:
                  kid: "cursor"
                  secretBase64: "${secret(6)}"
                validation:
                  - kid: "cursor"
                    secretBase64: "${secret(7)}"
                """.trimIndent(),
            )
            shouldThrow<McpCursorKeyringConfigError> {
                McpCursorKeyringConfig.load(collidingValidationKey)
            }.message shouldContain "collides"
        } finally {
            dir.deleteRecursively()
        }
    }
})
