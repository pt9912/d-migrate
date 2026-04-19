package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.streaming.checkpoint.CheckpointResumePosition
import java.nio.file.Path

/**
 * Resolves resume references and per-table resume markers for the
 * data export path. Extracted from [DataExportRunner] to isolate
 * resume/checkpoint logic.
 */
internal class ExportResumeCoordinator(
    private val primaryKeyLookup: (ConnectionPool, DatabaseDialect, String) -> List<String>,
    private val stderr: (String) -> Unit,
) {

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

    /**
     * Memoized PK lookup across tables. Failures per table produce an
     * empty list (Fall 2 → C.1 fallback).
     */
    fun resolvePrimaryKeys(
        pool: ConnectionPool,
        dialect: DatabaseDialect,
        tables: List<String>,
    ): Map<String, List<String>> = tables.associateWith { table ->
        try {
            primaryKeyLookup(pool, dialect, table)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Resolves per-table resume marker using 3-case logic (Phase C.2 §4.1):
     *
     * - Fall 1: no `--since-column`, no manifest position → `null`
     * - Fall 2: `--since-column` set, table without PK → `null` + stderr warning
     * - Fall 3: manifest has position but no `--since-column` → throws [TableResumeMismatchException]
     */
    fun resolveTableResumeMarker(
        sinceColumn: String?,
        table: String,
        existingPosition: CheckpointResumePosition?,
        primaryKey: List<String>,
    ): ResumeMarker? {
        val effectiveSinceColumn = sinceColumn?.takeIf { it.isNotBlank() }
        if (existingPosition != null && effectiveSinceColumn == null) {
            throw TableResumeMismatchException(table, existingPosition.markerColumn)
        }
        if (effectiveSinceColumn == null) return null
        if (primaryKey.isEmpty()) {
            stderr(
                "Warning: mid-table resume disabled for table '$table': " +
                    "no primary key found for tie-breaker; re-exporting from scratch."
            )
            return null
        }
        val position = existingPosition?.let { MarkerCodec.toRuntimePosition(it) }
        return ResumeMarker(
            markerColumn = effectiveSinceColumn,
            tieBreakerColumns = primaryKey,
            position = position,
        )
    }

    companion object {
        private const val MANIFEST_SUFFIX = ".checkpoint.yaml"
    }
}
