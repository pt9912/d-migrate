package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers

/**
 * Zustandslose T-SQL-Fragmente fuer den MSSQL-Diff-Renderer: Quoting,
 * Spaltendeklarationen, Constraint-Zeilen und die beiden Bausteine des
 * Default-Constraint-Dreischritts.
 *
 * Getrennt vom Operations-Dispatch, damit dieser unter Detekts
 * `TooManyFunctions`-Schwelle bleibt — dieselbe Aufteilung wie bei den drei
 * bestehenden Dialekten.
 */
internal class MssqlDiffSqlBuilders(private val typeMapper: MssqlTypeMapper) {

    fun quote(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.MSSQL)

    fun stringLiteral(value: String): String = SqlIdentifiers.quoteStringLiteral(value, DatabaseDialect.MSSQL)

    fun toSql(type: NeutralType): String = typeMapper.toSql(type)

    fun toDefaultSql(default: DefaultValue, type: NeutralType): String = typeMapper.toDefaultSql(default, type)

    /**
     * Spaltendeklaration fuer `CREATE TABLE` und `ALTER TABLE … ADD`.
     *
     * Der Default bekommt einen **benannten** Constraint
     * ([MssqlConstraintNames]) — anonym koennte ihn kein spaeterer
     * `ALTER COLUMN` mehr loesen.
     */
    fun columnDeclaration(table: String, name: String, col: ColumnDefinition): String {
        val parts = mutableListOf(quote(name), toSql(col.type))
        parts += if (col.required) "NOT NULL" else "NULL"
        col.default?.let {
            parts += "CONSTRAINT ${quote(MssqlConstraintNames.default(table, name))} " +
                "DEFAULT ${toDefaultSql(it, col.type)}"
        }
        if (col.unique) {
            parts += "CONSTRAINT ${quote(MssqlConstraintNames.unique(table, name))} UNIQUE"
        }
        col.references?.let { ref ->
            val onDelete = ref.onDelete?.let { " ON DELETE ${referentialActionSql(it)}" }.orEmpty()
            val onUpdate = ref.onUpdate?.let { " ON UPDATE ${referentialActionSql(it)}" }.orEmpty()
            parts += "REFERENCES ${quote(ref.table)}(${quote(ref.column)})$onDelete$onUpdate"
        }
        return parts.joinToString(" ")
    }

    /**
     * `ALTER TABLE … ALTER COLUMN` ist in T-SQL eine **Voll-Neudeklaration**:
     * was hier fehlt, ist danach weg. Vor allem gilt das fuer die
     * Nullability — ohne explizites `NOT NULL` wird die Spalte still nullable,
     * auch wenn nur der Typ geaendert werden sollte. Der Aufrufer muss beide
     * Werte liefern; raten darf er nicht.
     */
    fun alterColumnSql(table: String, column: String, type: NeutralType, required: Boolean): String =
        "ALTER TABLE ${quote(table)} ALTER COLUMN ${quote(column)} ${toSql(type)} " +
            (if (required) "NOT NULL" else "NULL") + ";"

    /** Erster Schritt des Dreischritts — idempotent, damit er auch ohne Default traegt. */
    fun dropDefaultConstraintSql(table: String, column: String): String =
        "ALTER TABLE ${quote(table)} DROP CONSTRAINT IF EXISTS " +
            "${quote(MssqlConstraintNames.default(table, column))};"

    /** Dritter Schritt des Dreischritts. */
    fun addDefaultConstraintSql(table: String, column: String, default: DefaultValue, type: NeutralType): String =
        "ALTER TABLE ${quote(table)} ADD CONSTRAINT " +
            "${quote(MssqlConstraintNames.default(table, column))} " +
            "DEFAULT ${toDefaultSql(default, type)} FOR ${quote(column)};"

    fun addPrimaryKeySql(table: String, columns: List<String>): String =
        "ALTER TABLE ${quote(table)} ADD CONSTRAINT ${quote(MssqlConstraintNames.primaryKey(table))} " +
            "PRIMARY KEY (${columns.joinToString(", ") { quote(it) }});"

    fun dropPrimaryKeySql(table: String): String =
        "ALTER TABLE ${quote(table)} DROP CONSTRAINT IF EXISTS " +
            "${quote(MssqlConstraintNames.primaryKey(table))};"

    /**
     * Umbenennen laeuft in T-SQL ueber die Systemprozedur, nicht ueber
     * `ALTER TABLE … RENAME`. `sp_rename` nimmt den alten Namen als
     * String-**Literal** (dort ist Klammer-Quoting erlaubt) und den neuen
     * Namen **unqualifiziert**.
     */
    fun renameSql(oldQualifiedName: String, newBareName: String, objectType: String? = null): String {
        val typeArg = objectType?.let { ", ${stringLiteral(it)}" }.orEmpty()
        return "EXEC sp_rename ${stringLiteral(oldQualifiedName)}, ${stringLiteral(newBareName)}$typeArg;"
    }

    fun constraintLine(table: String, c: ConstraintDefinition): String? = when (c.type) {
        ConstraintType.UNIQUE -> c.columns?.let { cols ->
            "CONSTRAINT ${quote(c.name)} UNIQUE (${cols.joinToString(", ") { quote(it) }})"
        }
        ConstraintType.FOREIGN_KEY -> {
            val cols = c.columns
            val ref = c.references
            if (cols == null || ref == null) {
                null
            } else {
                "CONSTRAINT ${quote(c.name)} FOREIGN KEY (${cols.joinToString(", ") { quote(it) }}) " +
                    "REFERENCES ${quote(ref.table)}(${ref.columns.joinToString(", ") { quote(it) }})"
            }
        }
        ConstraintType.CHECK -> c.expression?.takeIf { it.isNotBlank() }?.let {
            "CONSTRAINT ${quote(c.name)} CHECK ($it)"
        }
        // EXCLUDE ist PostgreSQL-eigen und in T-SQL nicht abbildbar; der
        // Aufrufer meldet das als Blocker, hier faellt nur die Zeile weg.
        ConstraintType.EXCLUDE -> null
        else -> null
    }

    /** T-SQL kennt kein `RESTRICT`; ohne aufschiebbare Constraints ist `NO ACTION` dasselbe. */
    private fun referentialActionSql(action: ReferentialAction): String = when (action) {
        ReferentialAction.CASCADE -> "CASCADE"
        ReferentialAction.SET_NULL -> "SET NULL"
        ReferentialAction.SET_DEFAULT -> "SET DEFAULT"
        ReferentialAction.RESTRICT, ReferentialAction.NO_ACTION -> "NO ACTION"
    }
}
