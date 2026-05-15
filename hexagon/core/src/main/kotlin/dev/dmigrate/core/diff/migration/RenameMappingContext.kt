package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.SchemaDefinition

/**
 * F.4 dependency-projection T3 bundle of values that travel together
 * through the mapping pipeline: the current/desired schema endpoints
 * plus the dialect-aware [RenameProjectionCapabilities] that the
 * projector resolves the [RenameDependencyPolicy] from.
 *
 * Introduced so the per-table mapping helpers
 * ([OperationMapper.mapTables], [OperationMapper.mapTableColumns],
 * [RenameOverlayMapper.foldRenameTables], etc.) keep their parameter
 * lists below Detekt's `LongParameterList` threshold while still
 * carrying every piece of state the projector needs.
 */
internal data class RenameMappingContext(
    val current: SchemaDefinition,
    val desired: SchemaDefinition,
    val capabilities: RenameProjectionCapabilities,
)
