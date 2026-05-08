package dev.dmigrate.cli.commands

import dev.dmigrate.mcp.cursor.CursorKey
import dev.dmigrate.mcp.cursor.CursorKeyring
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

internal object McpCursorKeyringConfig {
    const val SECRET_BYTES: Int = 32

    fun generateSecretBase64(): String {
        val bytes = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun load(path: Path): CursorKeyring {
        if (!Files.isRegularFile(path)) {
            throw McpCursorKeyringConfigError("cursor keyring file not found: $path")
        }
        val root = parse(path)
        val signingRaw = root["signing"] as? Map<*, *>
            ?: throw McpCursorKeyringConfigError("cursor keyring file $path must contain a signing mapping")
        val validationRaw = root["validation"] as? List<*> ?: emptyList<Any?>()
        val signing = readKey(signingRaw, "signing")
        val validation = validationRaw.mapIndexed { idx, raw ->
            val map = raw as? Map<*, *>
                ?: throw McpCursorKeyringConfigError("validation[$idx] must be a mapping")
            readKey(map, "validation[$idx]")
        }
        return try {
            CursorKeyring(signing = signing, additionalValidation = validation)
        } catch (e: IllegalArgumentException) {
            throw McpCursorKeyringConfigError(e.message ?: "invalid cursor keyring", e)
        }
    }

    fun renderSingleKeyFile(kid: String): String =
        """
        signing:
          kid: "$kid"
          secretBase64: "${generateSecretBase64()}"
        validation: []
        """.trimIndent()

    private fun parse(path: Path): Map<*, *> {
        val parsed = try {
            Files.newInputStream(path).use { input ->
                Load(LoadSettings.builder().build()).loadFromInputStream(input)
            }
        } catch (t: Throwable) {
            throw McpCursorKeyringConfigError(
                "failed to parse cursor keyring file $path: ${t.message ?: t::class.simpleName}",
                t,
            )
        }
        return parsed as? Map<*, *>
            ?: throw McpCursorKeyringConfigError("cursor keyring file $path must be a YAML mapping")
    }

    private fun readKey(source: Map<*, *>, label: String): CursorKey {
        val kid = source["kid"] as? String
            ?: throw McpCursorKeyringConfigError("$label.kid must be a string")
        val secretText = source["secretBase64"] as? String
            ?: throw McpCursorKeyringConfigError("$label.secretBase64 must be a string")
        val secret = decodeSecret(secretText, "$label.secretBase64")
        return try {
            CursorKey(kid = kid, secret = secret)
        } catch (e: IllegalArgumentException) {
            throw McpCursorKeyringConfigError(e.message ?: "invalid cursor key", e)
        }
    }

    private fun decodeSecret(raw: String, label: String): ByteArray {
        val trimmed = raw.trim()
        val decoded = runCatching { Base64.getDecoder().decode(trimmed) }
            .recoverCatching { Base64.getUrlDecoder().decode(trimmed) }
            .getOrElse {
                throw McpCursorKeyringConfigError("$label must be Base64 or Base64URL")
            }
        if (decoded.size < SECRET_BYTES) {
            throw McpCursorKeyringConfigError("$label must decode to at least $SECRET_BYTES bytes")
        }
        return decoded
    }
}

internal class McpCursorKeyringConfigError(
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
