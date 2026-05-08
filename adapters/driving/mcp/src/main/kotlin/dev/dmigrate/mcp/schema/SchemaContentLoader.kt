package dev.dmigrate.mcp.schema

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import java.io.IOException
import java.io.InputStream

/**
 * Materialises a [SchemaSource] into a parsed [SchemaDefinition] for
 * read-only schema tools (AP 6.4-6.6 per `ImpPlan-0.9.6-C.md`).
 *
 * Inline sources are streamed into the JSON codec without re-reading
 * the bytes; reference sources chain through [ArtifactStore] to learn
 * the artifact size, then through [ArtifactContentStore] to open the
 * byte stream. Reference reads are capped at
 * [McpLimitsConfig.maxArtifactUploadBytes] so a corrupt/oversized
 * artifact can't OOM the validator.
 *
 * Format selection:
 * - inline path is always JSON (the source is a `JsonObject` already)
 * - reference path defaults to JSON; clients pass `format=yaml` when
 *   the artifact bytes are YAML
 *
 * Codec exceptions become structured `VALIDATION_ERROR` findings on
 * the `schema` field so the client never sees a raw parser stack
 * trace (§6.4 acceptance).
 */
class SchemaContentLoader(
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
    private val limits: McpLimitsConfig,
) {

    fun load(source: SchemaSource, format: String?): SchemaDefinition = when (source) {
        is SchemaSource.Inline -> runCodec("json") {
            source.serialisedJson.byteInputStream(Charsets.UTF_8)
        }
        is SchemaSource.Reference -> loadReference(source, format ?: "json")
    }

    private fun loadReference(source: SchemaSource.Reference, format: String): SchemaDefinition {
        val entry = source.entry
        val record = artifactStore.findById(entry.tenantId, entry.artifactRef)
            ?: throw ResourceNotFoundException(entry.resourceUri)
        val size = record.managedArtifact.sizeBytes
        if (size > limits.maxArtifactUploadBytes) {
            throw PayloadTooLargeException(actualBytes = size, maxBytes = limits.maxArtifactUploadBytes)
        }
        return runCodec(format) { artifactContentStore.openRangeRead(entry.artifactRef, 0L, size) }
    }

    @Suppress("SwallowedException")
    private fun runCodec(format: String, openStream: () -> InputStream): SchemaDefinition {
        // SwallowedException: the original codec/IO error MUST NOT
        // reach the client (§6.4 acceptance "keine Validator-Ausnahme
        // wird roh an den Client gereicht"). We surface the sanitised
        // ValidationErrorException; the cause is dropped on purpose
        // because the client-facing wire envelope cannot carry stack
        // traces without leaking PII / implementation detail.
        val codec = try {
            SchemaFileResolver.codecForFormat(format)
        } catch (e: IllegalArgumentException) {
            throw ValidationErrorException(
                listOf(ValidationViolation("format", e.message ?: "unknown format '$format'")),
            )
        }
        return try {
            openStream().use { codec.read(it) }
        } catch (e: IOException) {
            throw ValidationErrorException(
                listOf(ValidationViolation("schema", "failed to read schema content: ${e.message}")),
            )
        } catch (e: RuntimeException) {
            // JsonSyntaxException, YAMLException, IllegalStateException
            // from the codec all land here. Same sanitisation rule.
            throw ValidationErrorException(
                listOf(ValidationViolation("schema", "schema parse failed: ${e.message ?: "malformed"}")),
            )
        }
    }
}
