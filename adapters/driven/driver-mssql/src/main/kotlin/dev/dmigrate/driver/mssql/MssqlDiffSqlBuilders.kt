package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.DialectCapabilities
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

    /**
     * Der Spalten-Helfer des GENERATE-Pfads. Der Diff rendert Spalten nicht
     * selbst: `generate` und `migrate` muessen fuer dasselbe Schema dieselbe
     * Tabelle bauen, und der Helfer kann bereits, was eine frisch geschriebene
     * Kopie vergisst — `IDENTITY` aus `generation`, Enums als begrenztes
     * `NVARCHAR` + CHECK statt `NVARCHAR(MAX)`, Geometrie, Kaskaden samt
     * [MssqlCascadePathGuard] und die LOB-Schluessel-Hinweise.
     */
    private val typeResolver = MssqlColumnTypeResolver(typeMapper)

    private val columnHelper = MssqlColumnConstraintHelper(
        quoteIdentifier = { SqlIdentifiers.quoteIdentifier(it, DatabaseDialect.MSSQL) },
        typeMapper = typeMapper,
        typeResolver = typeResolver,
        referentialActionSql = ::referentialActionSql,
    )

    /** Auch die Indizes rendert der Generate-Pfad — aus demselben Grund wie die Spalten. */
    private val indexHelper = MssqlIndexDdlHelper(
        quoteIdentifier = { SqlIdentifiers.quoteIdentifier(it, DatabaseDialect.MSSQL) },
        typeMapper = typeMapper,
    )

    fun quote(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.MSSQL)

    fun stringLiteral(value: String): String = SqlIdentifiers.quoteStringLiteral(value, DatabaseDialect.MSSQL)

    fun toSql(type: NeutralType): String = typeMapper.toSql(type)

    fun toDefaultSql(default: DefaultValue, type: NeutralType): String = typeMapper.toDefaultSql(default, type)

    /**
     * Spaltendeklaration fuer `CREATE TABLE` und `ALTER TABLE … ADD` — dieselbe,
     * die `schema generate` schreiben wuerde. Die Hinweise des Helfers
     * (W136/W140/E057 …) reicht der Aufrufer in seine Diagnosen weiter.
     */
    fun columnDeclaration(
        table: String,
        name: String,
        col: ColumnDefinition,
        tableDef: TableDefinition,
        schema: SchemaDefinition,
        notes: MutableList<TransformationNote>,
    ): String = columnHelper.generateColumnSql(table, name, col, tableDef, schema, notes)

    /**
     * Dieselbe Spalte, aber Deklaration und benannte Objekte GETRENNT — was der
     * Tabellen-Neubau braucht ([MssqlRebuildRenderer]): seine Zwischentabelle
     * darf `df_`/`uq_`/`ck_` noch nicht tragen, weil die alte Tabelle die Namen
     * bis zu ihrem `DROP` belegt.
     */
    fun renderColumn(
        table: String,
        name: String,
        col: ColumnDefinition,
        tableDef: TableDefinition,
        schema: SchemaDefinition,
        notes: MutableList<TransformationNote>,
    ): MssqlColumnConstraintHelper.ColumnRendering =
        columnHelper.renderColumn(table, name, col, tableDef, schema, notes)

    /** Ein benanntes Spalten-Objekt als nachgelagertes `ALTER TABLE … ADD CONSTRAINT`. */
    fun columnObjectStatement(table: String, column: String, obj: MssqlColumnObject): String =
        columnHelper.alterStatement(table, column, obj)

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

    /**
     * Erster Schritt des Dreischritts: den Default-Constraint der Spalte
     * loesen, **wie auch immer er heisst**.
     *
     * Ein `DROP CONSTRAINT IF EXISTS df_<tabelle>_<spalte>` traegt nur fuer
     * Schemata, die d-migrate selbst angelegt hat. SQL Server vergibt sonst
     * Namen wie `DF__tabelle__spalte__1A2B3C4D` — mit zufaelligem Suffix, also
     * offline nicht vorhersagbar. Das `IF EXISTS` traefe dann nichts, und das
     * nachfolgende `ALTER COLUMN` scheiterte mit Msg 5074. Genau der Fall, der
     * bei einer FREMDEN Datenbank der Normalfall ist — und Migrieren fremder
     * Datenbanken ist der Zweck des Kommandos.
     *
     * Deshalb schlaegt das Statement den Namen im Katalog nach. `QUOTENAME`
     * uebernimmt das Quoting des gefundenen Namens; interpoliert wird nur, was
     * aus `sys.default_constraints` kommt, nie Nutzereingabe.
     *
     * Die Zeichenkette entsteht in einer Variablen und laeuft ueber
     * `sp_executesql`: `EXEC('… ' + QUOTENAME(@df))` waere ein Syntaxfehler,
     * weil `EXEC(...)` in seiner Verkettung keine Funktionsaufrufe erlaubt.
     * Live gefunden — dem Statement sieht man das nicht an.
     */
    fun dropDefaultConstraintSql(table: String, column: String): String = buildString {
        append("DECLARE @df sysname, @sql nvarchar(max);\n")
        append("SELECT @df = dc.name FROM sys.default_constraints dc\n")
        append("    JOIN sys.columns c ON c.object_id = dc.parent_object_id\n")
        append("        AND c.column_id = dc.parent_column_id\n")
        append("    WHERE dc.parent_object_id = OBJECT_ID(${stringLiteral(table)})\n")
        append("        AND c.name = ${stringLiteral(column)};\n")
        append("IF @df IS NOT NULL\n")
        append("BEGIN\n")
        append("    SET @sql = ${stringLiteral("ALTER TABLE ${quote(table)} DROP CONSTRAINT ")} + QUOTENAME(@df);\n")
        append("    EXEC sp_executesql @sql;\n")
        append("END;")
    }

    /** Dritter Schritt des Dreischritts. */
    fun addDefaultConstraintSql(table: String, column: String, default: DefaultValue, type: NeutralType): String =
        "ALTER TABLE ${quote(table)} ADD CONSTRAINT " +
            "${quote(MssqlConstraintNames.default(table, column))} " +
            "DEFAULT ${toDefaultSql(default, type)} FOR ${quote(column)};"

    fun addPrimaryKeySql(table: String, columns: List<String>): String =
        "ALTER TABLE ${quote(table)} ADD CONSTRAINT ${quote(MssqlConstraintNames.primaryKey(table))} " +
            "PRIMARY KEY (${columns.joinToString(", ") { quote(it) }});"

    /**
     * Denselben Namensgriff braucht der Primaerschluessel: ein fremdes Schema
     * nennt ihn nicht `pk_<tabelle>`, und ein `IF EXISTS` auf den falschen
     * Namen liesse den Schluessel stehen — das anschliessende ADD scheiterte
     * dann. Der Katalog kennt hoechstens einen PK je Tabelle, die Abfrage ist
     * also eindeutig.
     */
    fun dropPrimaryKeySql(table: String): String = buildString {
        append("DECLARE @pk sysname, @sql nvarchar(max);\n")
        append("SELECT @pk = kc.name FROM sys.key_constraints kc\n")
        append("    WHERE kc.type = 'PK' AND kc.parent_object_id = OBJECT_ID(${stringLiteral(table)});\n")
        append("IF @pk IS NOT NULL\n")
        append("BEGIN\n")
        append("    SET @sql = ${stringLiteral("ALTER TABLE ${quote(table)} DROP CONSTRAINT ")} + QUOTENAME(@pk);\n")
        append("    EXEC sp_executesql @sql;\n")
        append("END;")
    }

    /**
     * Loest das UNIQUE-Objekt auf genau dieser Spalte — per Katalog, weil sein
     * Name im Modell nicht steht.
     *
     * Der Reverse hebt ein einspaltiges UNIQUE auf `column.unique` und wirft
     * dabei den Objektnamen weg (`MssqlSchemaReader`). Bei einer Datenbank, die
     * d-migrate angelegt hat, waere er `uq_<tabelle>_<spalte>`; bei jeder
     * anderen heisst er `UQ__tabelle__spalte__1A2B3C4D`. Geraten wuerde also
     * genau dort scheitern, wo es darauf ankommt — dieselbe Lehre wie beim
     * Default-Constraint.
     *
     * Deckt beide Formen ab: UNIQUE als Constraint und als blosser eindeutiger
     * Index.
     */
    fun dropUniqueOnColumnSql(table: String, column: String): String = buildString {
        append("DECLARE @uq sysname, @sql nvarchar(max);\n")
        append("SELECT TOP 1 @uq = kc.name FROM sys.key_constraints kc\n")
        append("    JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id\n")
        append("        AND ic.index_id = kc.unique_index_id\n")
        append("    JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id\n")
        append("    WHERE kc.type = 'UQ' AND kc.parent_object_id = OBJECT_ID(${stringLiteral(table)})\n")
        append("        AND c.name = ${stringLiteral(column)};\n")
        append("IF @uq IS NOT NULL\n")
        append("BEGIN\n")
        append("    SET @sql = ${stringLiteral("ALTER TABLE ${quote(table)} DROP CONSTRAINT ")} + QUOTENAME(@uq);\n")
        append("    EXEC sp_executesql @sql;\n")
        append("END\n")
        append("ELSE\n")
        append("BEGIN\n")
        append("    SELECT TOP 1 @uq = i.name FROM sys.indexes i\n")
        append("        JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id\n")
        append("        JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id\n")
        append("        WHERE i.is_unique = 1 AND i.is_primary_key = 0\n")
        append("            AND i.object_id = OBJECT_ID(${stringLiteral(table)}) AND c.name = ${stringLiteral(column)};\n")
        append("    IF @uq IS NOT NULL\n")
        append("    BEGIN\n")
        append("        SET @sql = N'DROP INDEX ' + QUOTENAME(@uq) + ${stringLiteral(" ON ${quote(table)}")};\n")
        append("        EXEC sp_executesql @sql;\n")
        append("    END\n")
        append("END;")
    }

    /** Der Fremdschluessel auf dieser Spalte, dessen Name das Modell nicht traegt. */
    fun dropForeignKeyOnColumnSql(table: String, column: String): String = buildString {
        append("DECLARE @fk sysname, @sql nvarchar(max);\n")
        append("SELECT TOP 1 @fk = fk.name FROM sys.foreign_keys fk\n")
        append("    JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id\n")
        append("    JOIN sys.columns c ON c.object_id = fkc.parent_object_id AND c.column_id = fkc.parent_column_id\n")
        append("    WHERE fk.parent_object_id = OBJECT_ID(${stringLiteral(table)})\n")
        append("        AND c.name = ${stringLiteral(column)};\n")
        append("IF @fk IS NOT NULL\n")
        append("BEGIN\n")
        append("    SET @sql = ${stringLiteral("ALTER TABLE ${quote(table)} DROP CONSTRAINT ")} + QUOTENAME(@fk);\n")
        append("    EXEC sp_executesql @sql;\n")
        append("END;")
    }

    /** Ob eine UNIQUE-Zusage auf dieser Spalte in T-SQL ueberhaupt zulaessig ist (LOB-Spalten sind es nicht). */
    fun isKeyEligible(col: ColumnDefinition, schema: SchemaDefinition): Boolean =
        !typeResolver.isLobColumn(col, schema)

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


    fun createIndexStatement(
        table: String,
        tableDef: TableDefinition,
        index: IndexDefinition,
        schema: SchemaDefinition,
    ): DdlStatement = indexHelper.generateIndex(table, tableDef, index, typeResolver.lobColumns(tableDef, schema))

    fun dropIndexSql(table: String, index: IndexDefinition): String {
        val name = index.name ?: "idx_${table}_${index.columnNames.joinToString("_")}"
        // T-SQL braucht die Tabelle im DROP INDEX — anders als PostgreSQL, wo
        // Indexnamen schema-global eindeutig sind.
        return "DROP INDEX IF EXISTS ${quote(name)} ON ${quote(table)};"
    }

    fun dropConstraintSql(table: String, name: String): String =
        "ALTER TABLE ${quote(table)} DROP CONSTRAINT IF EXISTS ${quote(name)};"

    /**
     * Ein gefilterter Index verlangt beim Anlegen bestimmte SET-Optionen, sonst
     * lehnt SQL Server ihn mit Msg 1934 ab. Im Skript setzt Slice 2a sie als
     * eigenen Batch voran; hier muessen sie in dasselbe Statement, weil der
     * Runner jedes Statement einzeln ausfuehrt und die Praeambel nie sieht.
     */
    fun withFilteredIndexSetOptions(index: IndexDefinition, sqlText: String): String =
        if (index.where.isNullOrBlank()) {
            sqlText
        } else {
            DialectCapabilities.forDialect(DatabaseDialect.MSSQL).scriptPreamble
                ?.let { "$it\n$sqlText" } ?: sqlText
        }

    fun constraintLine(
        table: String,
        c: ConstraintDefinition,
        guard: MssqlCascadePathGuard = MssqlCascadePathGuard.NONE,
    ): String? = when (c.type) {
        ConstraintType.UNIQUE -> c.columns?.let { cols ->
            "CONSTRAINT ${quote(c.name)} UNIQUE (${cols.joinToString(", ") { quote(it) }})"
        }
        ConstraintType.FOREIGN_KEY -> {
            val cols = c.columns
            val ref = c.references
            if (cols == null || ref == null) {
                null
            } else {
                val neutralise = guard.mustNeutralise(c.name)
                val onDelete = ref.onDelete
                    ?.let { " ON DELETE ${referentialActionSql(if (neutralise) ReferentialAction.NO_ACTION else it)}" }
                    .orEmpty()
                val onUpdate = ref.onUpdate
                    ?.let { " ON UPDATE ${referentialActionSql(if (neutralise) ReferentialAction.NO_ACTION else it)}" }
                    .orEmpty()
                "CONSTRAINT ${quote(c.name)} FOREIGN KEY (${cols.joinToString(", ") { quote(it) }}) " +
                    "REFERENCES ${quote(ref.table)}(${ref.columns.joinToString(", ") { quote(it) }})" +
                    onDelete + onUpdate
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
