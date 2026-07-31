package dev.dmigrate.integration

import dev.dmigrate.migration.MigrationIdentity

/**
 * Shared rendering helpers for tool-specific exporters.
 * All methods are pure functions — no I/O, no side effects.
 */
internal object RenderHelpers {

    /** Builds a Flyway-style file name: `V1.0__my_app.sql` or `U1.0__my_app.sql`. */
    fun flywayFileName(identity: MigrationIdentity, prefix: String): String =
        "${prefix}${identity.version}__${identity.slug}.sql"

    /** Escapes a string for safe embedding inside an XML **text node** (`&`, `<`, `>`). */
    fun escapeXml(sql: String): String = sql
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * Escapes a string for safe embedding inside a **double-quoted XML attribute
     * value** — additionally `"` and `'`, so an untrusted value (e.g. a
     * `schema.version` from a foreign schema file, see `SECURITY.md`) cannot break
     * out of `id="…"` and inject sibling XML into the generated changelog.
     */
    fun escapeXmlAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** Escapes a string for safe embedding inside a Python triple-quoted string. */
    fun escapePython(sql: String): String = sql
        .replace("\\", "\\\\")
        .replace("\"\"\"", "\\\"\\\"\\\"")

    /** Escapes a string for safe embedding inside a JavaScript backtick template literal. */
    fun escapeJavaScript(sql: String): String = sql
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("\${", "\\\${")
}
