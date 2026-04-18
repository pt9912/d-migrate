package dev.dmigrate.cli.commands

import java.nio.file.Path

/**
 * Resolves resume references for the data import path.
 * Extracted from [DataImportRunner] to isolate checkpoint logic.
 */
internal class ImportResumeCoordinator {

    /**
     * Resolves `--resume <checkpoint-id|path>` against the effective
     * checkpoint directory.
     *
     * - Path candidate (contains `/` or ends with `.checkpoint.yaml`):
     *   must be normalized inside the checkpoint directory; otherwise `null`.
     * - Otherwise: the value is a direct `operationId`.
     */
    fun resolveResumeReference(resumeValue: String, checkpointDir: Path): String? {
        val looksLikePath = '/' in resumeValue || resumeValue.endsWith(MANIFEST_SUFFIX)
        if (!looksLikePath) return resumeValue
        val candidate = try {
            Path.of(resumeValue).toAbsolutePath().normalize()
        } catch (_: Throwable) {
            return null
        }
        val baseDir = checkpointDir.toAbsolutePath().normalize()
        if (!candidate.startsWith(baseDir)) return null
        val fileName = candidate.fileName.toString()
        if (!fileName.endsWith(MANIFEST_SUFFIX)) return null
        return fileName.removeSuffix(MANIFEST_SUFFIX)
    }

    companion object {
        private const val MANIFEST_SUFFIX = ".checkpoint.yaml"
    }
}
