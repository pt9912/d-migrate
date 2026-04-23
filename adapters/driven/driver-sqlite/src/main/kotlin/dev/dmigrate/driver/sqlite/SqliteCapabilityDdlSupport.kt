package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.CircularFkEdge
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.TransformationNote

internal class SqliteCapabilityDdlSupport(
    private val quoteIdentifier: (String) -> String,
) {

    fun generateCustomTypes(types: Map<String, CustomTypeDefinition>): List<DdlStatement> {
        val statements = mutableListOf<DdlStatement>()
        for ((name, typeDefinition) in types) {
            statements += when (typeDefinition.kind) {
                CustomTypeKind.ENUM -> informationalTypeMapping(
                    name = name,
                    sqlComment = "-- Enum type ${quoteIdentifier(name)} is handled inline via CHECK constraints",
                    message = "Enum type '$name' mapped to inline TEXT + CHECK constraint in SQLite."
                )
                CustomTypeKind.COMPOSITE -> unsupportedTypeMapping(
                    name = name,
                    sqlComment = "-- Composite type ${quoteIdentifier(name)} is not supported in SQLite",
                    code = "E054",
                    message = "Composite type '$name' is not supported in SQLite.",
                    hint = "Flatten composite fields into individual table columns or use JSON."
                )
                CustomTypeKind.DOMAIN -> informationalTypeMapping(
                    name = name,
                    sqlComment = "-- Domain type ${quoteIdentifier(name)} is mapped to its base type with inline CHECK in SQLite",
                    message = "Domain type '$name' mapped to base type with inline CHECK constraint in SQLite."
                )
            }
        }
        return statements
    }

    fun generateSequences(
        sequences: Map<String, SequenceDefinition>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        val statements = mutableListOf<DdlStatement>()
        for ((name, _) in sequences) {
            skipped += SkippedObject("sequence", name, "Sequences are not supported in SQLite")
            statements += DdlStatement(
                "-- Sequence ${quoteIdentifier(name)} is not supported in SQLite",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E056",
                        objectName = name,
                        message = "Sequence '$name' is not supported in SQLite.",
                        hint = "Use INTEGER PRIMARY KEY AUTOINCREMENT or application-level sequencing."
                    )
                )
            )
        }
        return statements
    }

    fun handleCircularReferences(
        edges: List<CircularFkEdge>,
        skipped: MutableList<SkippedObject>,
    ): List<DdlStatement> {
        val statements = mutableListOf<DdlStatement>()
        for (edge in edges) {
            val constraintName = "fk_${edge.fromTable}_${edge.fromColumn}"
            skipped += SkippedObject("foreign_key", constraintName, circularForeignKeySkipReason(edge))
            statements += DdlStatement(
                "-- Circular FK ${quoteIdentifier(constraintName)} skipped: SQLite cannot ALTER TABLE ADD CONSTRAINT",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E019",
                        objectName = constraintName,
                        message = circularForeignKeyMessage(edge),
                        hint = "SQLite does not support ALTER TABLE ADD CONSTRAINT. Enforce referential integrity at the application level."
                    )
                )
            )
        }
        return statements
    }

    fun invertStatement(stmt: DdlStatement): DdlStatement? {
        val sql = stmt.sql.trim()
        if (sql.startsWith("CREATE VIEW IF NOT EXISTS", ignoreCase = true)) {
            val afterKeyword = sql.substring("CREATE VIEW IF NOT EXISTS".length).trimStart()
            val name = afterKeyword.split(Regex("[\\s(]"), limit = 2).first()
            return DdlStatement("DROP VIEW IF EXISTS $name;")
        }
        if (sql.startsWith("SELECT AddGeometryColumn(", ignoreCase = true)) {
            val argsStart = sql.indexOf('(') + 1
            val argsEnd = sql.lastIndexOf(')')
            if (argsStart > 0 && argsEnd > argsStart) {
                val args = sql.substring(argsStart, argsEnd).split(',').map { it.trim() }
                if (args.size >= 2) {
                    return DdlStatement("SELECT DiscardGeometryColumn(${args[0]}, ${args[1]});")
                }
            }
        }
        return null
    }

    private fun informationalTypeMapping(
        name: String,
        sqlComment: String,
        message: String,
    ): DdlStatement =
        DdlStatement(
            sqlComment,
            listOf(
                TransformationNote(
                    type = NoteType.INFO,
                    code = "I001",
                    objectName = name,
                    message = message,
                )
            )
        )

    private fun unsupportedTypeMapping(
        name: String,
        sqlComment: String,
        code: String,
        message: String,
        hint: String,
    ): DdlStatement =
        DdlStatement(
            sqlComment,
            listOf(
                TransformationNote(
                    type = NoteType.ACTION_REQUIRED,
                    code = code,
                    objectName = name,
                    message = message,
                    hint = hint,
                )
            )
        )

    private fun circularForeignKeySkipReason(edge: CircularFkEdge): String =
        buildString {
            append("Circular foreign key from '")
            append(edge.fromTable)
            append('.')
            append(edge.fromColumn)
            append("' to '")
            append(edge.toTable)
            append('.')
            append(edge.toColumn)
            append("' cannot be added in SQLite (no ALTER TABLE ADD CONSTRAINT)")
        }

    private fun circularForeignKeyMessage(edge: CircularFkEdge): String =
        buildString {
            append("Circular foreign key from '")
            append(edge.fromTable)
            append('.')
            append(edge.fromColumn)
            append("' to '")
            append(edge.toTable)
            append('.')
            append(edge.toColumn)
            append("' cannot be created in SQLite.")
        }
}
