package dev.dmigrate.format

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.format.json.JsonSchemaCodec
import dev.dmigrate.format.yaml.YamlSchemaCodec
import java.nio.file.Path
import kotlin.io.path.outputStream

/**
 * Resolves the appropriate [SchemaCodec] for a given file path or
 * explicit format name.
 *
 * Accepted schema file extensions:
 * - `.yaml`, `.yml` → YAML
 * - `.json` → JSON
 *
 * Unknown or missing extensions produce a clear error.
 */
object SchemaFileResolver {

    private val yamlCodec = YamlSchemaCodec()
    private val jsonCodec = JsonSchemaCodec()

    /**
     * Returns the codec matching the file extension of [path].
     *
     * @throws IllegalArgumentException if the extension is unknown
     */
    fun codecForPath(path: Path): SchemaCodec {
        val format = detectFormat(path)
        return codecForFormat(format)
    }

    /**
     * Returns the codec for the given format name.
     *
     * @param format `"yaml"` or `"json"`
     * @throws IllegalArgumentException if the format is unknown
     */
    fun codecForFormat(format: String): SchemaCodec = when (format) {
        "yaml" -> yamlCodec
        "json" -> jsonCodec
        else -> throw IllegalArgumentException(
            "Unknown schema file format: '$format'. Supported formats: yaml, json"
        )
    }

    /**
     * Detects the schema file format from the file extension.
     *
     * @return `"yaml"` or `"json"`
     * @throws IllegalArgumentException if the extension is unknown or missing
     */
    fun detectFormat(path: Path): String {
        val fileName = path.fileName?.toString()
            ?: throw IllegalArgumentException("Cannot determine format: path has no file name")
        return when {
            fileName.endsWith(".yaml", ignoreCase = true) -> "yaml"
            fileName.endsWith(".yml", ignoreCase = true) -> "yaml"
            fileName.endsWith(".json", ignoreCase = true) -> "json"
            else -> throw IllegalArgumentException(
                "Unknown schema file extension in '$fileName'. " +
                    "Supported extensions: .yaml, .yml, .json"
            )
        }
    }

    /**
     * Writes a schema to the given path, validating that the file
     * extension matches the explicit format (if given).
     *
     * This is the canonical schema write entry point for reverse
     * output. It enforces the 0.6.0 file I/O contract: format and
     * extension must not contradict each other.
     *
     * @param path output file path (must have .yaml/.yml/.json extension)
     * @param schema the schema to write
     * @param format explicit format override, or null to detect from extension
     * @throws IllegalArgumentException on extension/format mismatch
     */
    fun writeSchema(path: Path, schema: SchemaDefinition, format: String? = null) {
        val resolvedFormat = format ?: detectFormat(path)
        validateOutputPath(path, format)
        val codec = codecForFormat(resolvedFormat)
        path.outputStream().use { codec.write(it, schema) }
    }

    /**
     * Validates that the output path extension matches the requested format.
     *
     * @throws IllegalArgumentException if there is a mismatch
     */
    fun validateOutputPath(path: Path, format: String?) {
        if (format == null) return
        val detected = try { detectFormat(path) } catch (_: IllegalArgumentException) { return }
        if (detected != format) {
            throw IllegalArgumentException(
                "Output file extension (.${path.fileName}) does not match " +
                    "requested format '$format'. Use a matching extension " +
                    "(.yaml/.yml for yaml, .json for json)."
            )
        }
    }
}
