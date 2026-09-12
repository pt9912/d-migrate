package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.metadata.ComputedColumnClause
import dev.dmigrate.driver.metadata.EnumValueCheck
import dev.dmigrate.driver.metadata.NamedUniqueConstraints

internal class PostgresColumnConstraintHelper(
    private val quoteIdentifier: (String) -> String,
    private val typeMapper: TypeMapper,
    private val columnSql: (String, String, ColumnDefinition, SchemaDefinition) -> String,
    private val referentialActionSql: (ReferentialAction) -> String,
    /**
     * Die Version des Ziels, wie sie der laufende `generate`-Aufruf kennt —
     * `null` bei einem Dateiziel. Eine Funktion statt eines Wertes, weil der
     * Helfer einmal gebaut und je Lauf benutzt wird.
     */
    private val serverVersion: () -> PostgresServerVersion? = { null },
) {

    fun generateColumnSql(
        colName: String,
        col: ColumnDefinition,
        schema: SchemaDefinition,
        tableName: String,
    ): String {
        val type = col.type

        // Eine berechnete Spalte bekommt ihren Wert aus dem Ausdruck; NOT NULL,
        // DEFAULT und UNIQUE sind dort keine Frage. Welche Speicherform
        // dahintersteht, haengt bei PostgreSQL als einzigem Dialekt an der
        // Serverversion (siehe PostgresComputedStorage).
        // Traegt der Ausdruck fremde Grammatik, faellt die Berechnung weg und
        // die Spalte bleibt gewoehnlich. Gemeldet wird das dort, wo die Notizen
        // entstehen (`PostgresDdlGenerator.generateTable`) — beide Seiten
        // fragen dieselbe Stelle, damit sie nicht auseinanderlaufen.
        ComputedColumnClause.of(col)?.let { computed ->
            if (!PostgresComputedStorage.isPortable(computed)) return@let
            return listOf(
                quoteIdentifier(colName),
                typeMapper.toSql(type),
                ComputedColumnClause.clause(computed, PostgresComputedStorage.suffix(computed, serverVersion())),
            ).joinToString(" ")
        }

        val generation = col.generation
        if (generation is ColumnGeneration.Identity) {
            val generatedSql = identityColumnSql(type, generation)
            if (generatedSql != null) {
                val parts = mutableListOf<String>()
                parts += quoteIdentifier(colName)
                parts += generatedSql
                if (NamedUniqueConstraints.rendersInline(col)) parts += "UNIQUE"
                return parts.joinToString(" ")
            }
        }

        // For Identifier type (SERIAL), skip NOT NULL since SERIAL implies it
        if (type is NeutralType.Identifier && type.autoIncrement) {
            val parts = mutableListOf<String>()
            parts += quoteIdentifier(colName)
            parts += typeMapper.toSql(type)
            if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
            if (NamedUniqueConstraints.rendersInline(col)) parts += "UNIQUE"
            return parts.joinToString(" ")
        }

        // For enum columns with ref_type, use the custom type name
        if (type is NeutralType.Enum) {
            val refType = type.refType
            if (refType != null) {
                val parts = mutableListOf<String>()
                parts += quoteIdentifier(colName)
                parts += quoteIdentifier(refType)
                if (col.required) parts += "NOT NULL"
                if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
                if (NamedUniqueConstraints.rendersInline(col)) parts += "UNIQUE"
                return parts.joinToString(" ")
            }
        }

        // For enum columns with inline values, use TEXT + CHECK constraint
        if (type is NeutralType.Enum) {
            val enumValues = type.values
            if (enumValues != null) {
                val parts = mutableListOf<String>()
                parts += quoteIdentifier(colName)
                parts += "TEXT"
                if (col.required) parts += "NOT NULL"
                if (col.default != null) parts += "DEFAULT ${typeMapper.toDefaultSql(col.default!!, type)}"
                if (NamedUniqueConstraints.rendersInline(col)) parts += "UNIQUE"
                parts += EnumValueCheck.clause(colName, enumValues, quoteIdentifier)
                return parts.joinToString(" ")
            }
        }

        // Default: delegate to base class columnSql
        return columnSql(tableName, colName, col, schema)
    }

    private fun identityColumnSql(type: NeutralType, identity: ColumnGeneration.Identity): String? {
        val baseType = when (type) {
            is NeutralType.Integer -> "INTEGER"
            is NeutralType.BigInteger -> "BIGINT"
            else -> return null
        }
        if (identity.legacySerialSyntax) {
            return when (type) {
                is NeutralType.Integer -> "SERIAL"
                is NeutralType.BigInteger -> "BIGSERIAL"
                else -> null
            }
        }
        val mode = when (identity.mode) {
            IdentityMode.ALWAYS -> "ALWAYS"
            IdentityMode.BY_DEFAULT -> "BY DEFAULT"
        }
        return "$baseType GENERATED $mode AS IDENTITY"
    }

    fun buildForeignKeyClause(
        constraintName: String,
        fromColumns: List<String>,
        toTable: String,
        toColumns: List<String>,
        onDelete: ReferentialAction?,
        onUpdate: ReferentialAction?
    ): String {
        val fromCols = fromColumns.joinToString(", ") { quoteIdentifier(it) }
        val toCols = toColumns.joinToString(", ") { quoteIdentifier(it) }
        val sql = buildString {
            append("CONSTRAINT ${quoteIdentifier(constraintName)} FOREIGN KEY ($fromCols) REFERENCES ${quoteIdentifier(toTable)} ($toCols)")
            if (onDelete != null) append(" ON DELETE ${referentialActionSql(onDelete)}")
            if (onUpdate != null) append(" ON UPDATE ${referentialActionSql(onUpdate)}")
        }
        return sql
    }

    /**
     * `null`, wenn der rohe Ausdruck auf PostgreSQL nicht gilt. Der Fall ist
     * seltener als andersherum — `::` und `~~` sind hier zu Hause —, aber
     * MySQLs Backtick-Quoting ist es nicht, und ein von dort stammender CHECK
     * ginge sonst unveraendert in die DDL.
     */
    fun generateConstraintClause(
        constraint: ConstraintDefinition,
        notes: MutableList<TransformationNote>,
    ): String? {
        if (constraint.type == ConstraintType.CHECK || constraint.type == ConstraintType.EXCLUDE) {
            val verdict = RawSqlExpressionPortability.assess(constraint.expression, DatabaseDialect.POSTGRESQL)
            if (!verdict.portable) {
                notes += RawSqlExpressionPortability.notPortableNote(
                    "constraint", constraint.name,
                    if (constraint.type == ConstraintType.CHECK) "CHECK expression" else "EXCLUDE expression",
                    verdict.reason, DatabaseDialect.POSTGRESQL,
                )
                return null
            }
        }
        return when (constraint.type) {
            ConstraintType.CHECK -> {
                "CONSTRAINT ${quoteIdentifier(constraint.name)} CHECK (${constraint.expression})"
            }
            ConstraintType.UNIQUE -> {
                val cols = constraint.columns?.joinToString(", ") { quoteIdentifier(it) } ?: ""
                "CONSTRAINT ${quoteIdentifier(constraint.name)} UNIQUE ($cols)"
            }
            ConstraintType.EXCLUDE -> {
                "CONSTRAINT ${quoteIdentifier(constraint.name)} EXCLUDE (${constraint.expression})"
            }
            ConstraintType.FOREIGN_KEY -> {
                val ref = constraint.references!!
                buildForeignKeyClause(
                    constraint.name,
                    constraint.columns ?: emptyList(),
                    ref.table,
                    ref.columns,
                    ref.onDelete,
                    ref.onUpdate
                )
            }
        }
    }
}
