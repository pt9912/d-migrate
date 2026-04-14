package dev.dmigrate.format

import dev.dmigrate.format.json.JsonSchemaCodec
import dev.dmigrate.format.yaml.YamlSchemaCodec
import java.nio.file.Path

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
