package dev.dmigrate.cli.commands

import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.SchemaDefinition
import java.nio.file.Path

/**
 * Sealed type for compare operand disambiguation.
 *
 * - `file:<path>` or prefix-less → [File]
 * - `db:<url-or-alias>` → [Database]
 */
sealed interface CompareOperand {
    data class File(val path: Path) : CompareOperand
    data class Database(val source: String) : CompareOperand
}

/**
 * Parses a raw CLI operand string into a [CompareOperand].
 */
object CompareOperandParser {

    fun parse(raw: String): CompareOperand {
        return when {
            raw.startsWith("file:") -> {
                val path = raw.removePrefix("file:")
                require(path.isNotBlank()) { "Empty file path in operand: $raw" }
                CompareOperand.File(Path.of(path))
            }
            raw.startsWith("db:") -> {
                val source = raw.removePrefix("db:")
                require(source.isNotBlank()) { "Empty database source in operand: $raw" }
                CompareOperand.Database(source)
            }
            else -> {
                // Backward compatibility: prefix-less → file path
                CompareOperand.File(Path.of(raw))
            }
        }
    }
}

/**
 * Normalizes reverse-generated markers on a [ResolvedSchemaOperand]
 * before comparison, so that synthetic `name`/`version` values don't
 * produce fake diffs.
 *
 * Operates on each operand independently — a `file/db` compare where
 * only one side is reverse-generated will still be normalized correctly.
 *
 * Invalid markers (prefix present but set syntactically invalid) produce
 * an [IllegalStateException] rather than a silent fallback.
 */
object CompareOperandNormalizer {

    private const val NORMALIZED_NAME = "__compare_normalized__"
    private const val NORMALIZED_VERSION = "0.0.0"

    /**
     * If the operand's schema has valid reverse markers, replace
     * `name` and `version` with neutral placeholders.
     *
     * @throws IllegalStateException if the schema name starts with the
     *         reserved prefix but the marker set is incomplete or invalid
     */
    fun normalize(operand: ResolvedSchemaOperand): ResolvedSchemaOperand {
        val name = operand.schema.name
        val version = operand.schema.version

        // Not a reverse-generated schema → pass through unchanged
        if (!name.startsWith(ReverseScopeCodec.PREFIX)) return operand

        // Has prefix → must be fully valid
        if (!ReverseScopeCodec.isReverseGenerated(name, version)) {
            throw IllegalStateException(
                "Schema '${operand.reference}' uses reserved prefix " +
                    "'${ReverseScopeCodec.PREFIX}' but has invalid or " +
                    "incomplete reverse marker set"
            )
        }

        // Valid reverse markers → normalize to prevent fake diffs
        return operand.copy(
            schema = operand.schema.copy(
                name = NORMALIZED_NAME,
                version = NORMALIZED_VERSION,
            ),
        )
    }
}
