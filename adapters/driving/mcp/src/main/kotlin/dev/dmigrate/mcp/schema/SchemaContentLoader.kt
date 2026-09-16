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
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Materialises a [SchemaSource] into a parsed [SchemaDefinition] for
 * read-only schema tools (LF-012 / LN-027 / LN-028 / LN-038-6.6 per LF-012 / LN-027 / LN-028 / LN-038).
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
 * - reference path uses the explicit `format`; without one it **detects** the
 *   bytes (`SchemaFileResolver.sniffFormat`), so a YAML artifact from
 *   `schema_reverse` no longer needs the client to say so
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
        // Inline ist bereits ein `JsonObject` — hier gibt es nichts zu
        // erkennen, und ein falsches `format` soll auch nicht hinter einer
        // Heuristik verschwinden.
        is SchemaSource.Inline -> runCodec("json") {
            source.serialisedJson.byteInputStream(Charsets.UTF_8)
        }
        is SchemaSource.Reference -> loadReference(source, format)
    }

    private fun loadReference(source: SchemaSource.Reference, format: String?): SchemaDefinition {
        val entry = source.entry
        val record = artifactStore.findById(entry.tenantId, entry.artifactRef)
            ?: throw ResourceNotFoundException(entry.resourceUri)
        val size = record.managedArtifact.sizeBytes
        if (size > limits.maxArtifactUploadBytes) {
            throw PayloadTooLargeException(actualBytes = size, maxBytes = limits.maxArtifactUploadBytes)
        }
        return runCodec(format) { artifactContentStore.openRangeRead(entry.artifactRef, 0L, size) }
    }

    /**
     * Liest den Strom mit dem Codec fuer [format] — oder, wenn keiner angegeben
     * ist, mit dem **erkannten**.
     *
     * Ein Verweis auf ein per `schema_reverse` erzeugtes Artefakt ist YAML, und
     * bis 1.7.0 nahm dieser Pfad ohne `format` JSON an: jeder Aufruf, der
     * `schema_reverse` mit `schema_generate` verkettete, scheiterte mit
     * `Unrecognized token 'schema_format'`. Die Erkennung liegt jetzt hier —
     * dieselbe wie bei `schema validate` auf stdin.
     *
     * Gepuffert wird nur, damit `mark`/`reset` moeglich ist; der Inhalt wandert
     * weiterhin nicht in den Speicher.
     */
    @Suppress("SwallowedException")
    private fun runCodec(format: String?, openStream: () -> InputStream): SchemaDefinition {
        // SwallowedException: the original codec/IO error MUST NOT
        // reach the client (§6.4 acceptance "keine Validator-Ausnahme
        // wird roh an den Client gereicht"). We surface the sanitised
        // ValidationErrorException; the cause is dropped on purpose
        // because the client-facing wire envelope cannot carry stack
        // traces without leaking PII / implementation detail.
        //
        // Ein **angegebenes** Format wird vor dem Strom geprueft. Sonst
        // maskierte ein Fehler beim Oeffnen eine Format-Angabe, die der
        // Aufrufer falsch geschrieben hat — die Meldung soll den Tippfehler
        // nennen, nicht das Artefakt.
        val explicit = format?.let { name ->
            try {
                SchemaFileResolver.codecForFormat(name)
            } catch (e: IllegalArgumentException) {
                throw ValidationErrorException(
                    listOf(ValidationViolation("format", e.message ?: "unknown format '$name'")),
                )
            }
        }
        return try {
            openStream().use { raw ->
                val stream = if (raw is BufferedInputStream) raw else BufferedInputStream(raw)
                val codec = explicit
                    ?: SchemaFileResolver.codecForFormat(SchemaFileResolver.sniffFormat(stream))
                codec.read(stream)
            }
        } catch (e: IOException) {
            throw ValidationErrorException(
                listOf(ValidationViolation("schema", "failed to read schema content: ${e.message}")),
            )
        } catch (e: RuntimeException) {
            // JsonSyntaxException, YAMLException, IllegalStateException
            // from the codec all land here. Same sanitisation rule.
            //
            // Bewusst **nach** dem Strom: ein `IllegalArgumentException` aus
            // dem Codec ist ein kaputter Inhalt, kein unbekanntes Format —
            // beide als "format" zu melden waere die falsche Auskunft.
            throw ValidationErrorException(
                listOf(ValidationViolation("schema", "schema parse failed: ${e.message ?: "malformed"}")),
            )
        }
    }
}
