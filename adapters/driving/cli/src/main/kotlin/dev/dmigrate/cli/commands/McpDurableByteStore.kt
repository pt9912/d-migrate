package dev.dmigrate.cli.commands

import dev.dmigrate.server.adapter.storage.s3.ArtifactStorageConfig
import java.nio.file.Files

/**
 * `server.state` haelt Job-, Schema- und Artefakt-Datensaetze ueber Neustarts hinweg; dann muessen auch die Bytes
 * hinter diesen Datensaetzen ueberleben (spec/cli-spec.md, `mcp serve`). Sonst zeigen sie nach dem ersten Neustart
 * ins Leere.
 */
internal object McpDurableByteStore {

    /**
     * Die Ablehnung fuer eine Konfiguration mit `server.state`, deren Byte-Store nicht persistent ist, sonst
     * `null`. Persistent ist `artifacts.store: s3` oder ein vom Betreiber benanntes Zustandsverzeichnis, das beim
     * Start schon besteht. Ein erst anzulegendes zaehlt nicht: Im Container ist es so fluechtig wie das temporaere.
     */
    fun violation(artifacts: ArtifactStorageConfig, stateDir: ResolvedStateDir): String? {
        val reason = when (artifacts) {
            is ArtifactStorageConfig.S3 -> null
            is ArtifactStorageConfig.File -> when {
                stateDir.owned -> "no state dir is configured, and the temporary one is deleted on stop"
                !Files.exists(stateDir.path) -> "state dir ${stateDir.path} does not exist"
                else -> null
            }
        } ?: return null
        return "server.state keeps job, schema and artifact records across restarts, but the artifact bytes " +
            "would not survive: $reason. Point --mcp-state-dir (or \$${StateDirResolver.ENV_STATE_DIR}) at an " +
            "existing persistent directory, or set artifacts.store: s3."
    }
}
