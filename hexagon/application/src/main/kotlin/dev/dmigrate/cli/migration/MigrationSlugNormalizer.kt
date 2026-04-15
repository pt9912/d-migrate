package dev.dmigrate.cli.migration

/**
 * Normalizes `schema.name` into a filesystem-safe slug for use in
 * migration file names and identifiers.
 *
 * Rules:
 * - Lowercase
 * - Whitespace and non-alphanumeric characters → underscore
 * - Consecutive underscores collapsed
 * - Leading/trailing underscores stripped
 * - Empty result → "migration"
 */
object MigrationSlugNormalizer {

    private val NON_SLUG = Regex("[^a-z0-9]+")

    fun normalize(schemaName: String): String {
        val slug = schemaName
            .lowercase()
            .replace(NON_SLUG, "_")
            .trim('_')
        return slug.ifEmpty { "migration" }
    }
}
