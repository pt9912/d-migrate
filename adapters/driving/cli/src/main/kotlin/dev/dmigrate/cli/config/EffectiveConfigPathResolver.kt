package dev.dmigrate.cli.config

import java.nio.file.Path
import java.nio.file.Paths

internal enum class EffectiveConfigSource {
    CLI,
    ENV,
    DEFAULT,
}

internal data class EffectiveConfigPath(
    val path: Path,
    val source: EffectiveConfigSource,
)

internal class EffectiveConfigPathResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {
    fun resolve(): EffectiveConfigPath {
        configPathFromCli?.let { return EffectiveConfigPath(it, EffectiveConfigSource.CLI) }
        envLookup("D_MIGRATE_CONFIG")?.takeIf { it.isNotBlank() }?.let {
            return EffectiveConfigPath(Paths.get(it), EffectiveConfigSource.ENV)
        }
        return EffectiveConfigPath(defaultConfigPath, EffectiveConfigSource.DEFAULT)
    }
}
