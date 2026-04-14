package dev.dmigrate.format

import java.nio.file.Path

/**
 * Shared helper for computing sidecar file paths. Used by both
 * schema generation reports and reverse-engineering reports.
 */
object SidecarPath {

    /**
     * Builds a sidecar path next to [outputPath] by replacing the
     * file extension with [suffix].
     *
     * - If the filename has an extension (contains `.`), the extension
     *   is replaced: `schema.sql` + `.report.yaml` → `schema.report.yaml`
     * - If not (e.g. `schema`), the suffix is appended: `schema.report.yaml`
     * - Parent directory is preserved if present.
     */
    fun of(outputPath: Path, suffix: String): Path {
        val fileName = outputPath.fileName.toString()
        val dotIndex = fileName.lastIndexOf('.')
        val sidecarName = if (dotIndex > 0) {
            "${fileName.substring(0, dotIndex)}$suffix"
        } else {
            "$fileName$suffix"
        }
        return outputPath.parent?.resolve(sidecarName) ?: Path.of(sidecarName)
    }
}
