package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.NoteType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Eine Domain-Spalte schreibt auf MySQL nie einen Basistyp, den MySQL nicht
 * annimmt: der Basistyp laeuft ueber `DomainTypes.resolveBaseType` und den
 * MySQL-Typmapper; ohne neutrales Gegenstueck entsteht E053 mit der
 * Ersatzspalte `TEXT` (wie SQLite und MSSQL).
 */
class MysqlDomainBaseTypeTest : FunSpec({

    val generator = MysqlDdlGenerator()

    fun generate(
        baseType: String,
        precision: Int? = null,
        scale: Int? = null,
        check: String? = null,
        required: Boolean = false,
    ): DdlResult = generator.generate(
        SchemaDefinition(
            name = "s", version = "1.0",
            customTypes = mapOf(
                "d" to CustomTypeDefinition(
                    kind = CustomTypeKind.DOMAIN, baseType = baseType,
                    precision = precision, scale = scale, check = check,
                ),
            ),
            tables = mapOf(
                "t" to TableDefinition(
                    columns = linkedMapOf("c" to ColumnDefinition(type = NeutralType.Enum(refType = "d"), required = required)),
                ),
            ),
        ),
    )

    fun DdlResult.columnLine(): String = render().lines().first { it.trim().startsWith("`c`") }.trim()

    // Basistyp -> MySQL-Spaltentyp (Soll). Jede Zeile von `resolveBaseTypeName`.
    val mapped = listOf(
        Triple("integer", null to null, "INT"),
        Triple("int", null to null, "INT"),
        Triple("int4", null to null, "INT"),
        Triple("bigint", null to null, "BIGINT"),
        Triple("biginteger", null to null, "BIGINT"),
        Triple("int8", null to null, "BIGINT"),
        Triple("smallint", null to null, "SMALLINT"),
        Triple("int2", null to null, "SMALLINT"),
        Triple("boolean", null to null, "TINYINT(1)"),
        Triple("bool", null to null, "TINYINT(1)"),
        Triple("real", null to null, "FLOAT"),
        Triple("float4", null to null, "FLOAT"),
        Triple("float", null to null, "DOUBLE"),
        Triple("double precision", null to null, "DOUBLE"),
        Triple("float8", null to null, "DOUBLE"),
        Triple("numeric", 10 to 2, "DECIMAL(10,2)"),
        Triple("decimal", 5 to null, "DECIMAL(5,0)"),
        Triple("text", null to null, "TEXT"),
        Triple("character varying", null to null, "TEXT"),
        Triple("character varying", 50 to null, "VARCHAR(50)"),
        Triple("varchar", 50 to null, "VARCHAR(50)"),
        Triple("varchar(20)", null to null, "VARCHAR(20)"),
        Triple("char", null to null, "CHAR(1)"),
        Triple("char", 3 to null, "CHAR(3)"),
        Triple("uuid", null to null, "CHAR(36)"),
        Triple("json", null to null, "JSON"),
        Triple("jsonb", null to null, "JSON"),
        Triple("xml", null to null, "TEXT"),
        Triple("bytea", null to null, "BLOB"),
        Triple("date", null to null, "DATE"),
        Triple("time", null to null, "TIME"),
        Triple("time without time zone", null to null, "TIME"),
        Triple("timestamp", null to null, "DATETIME"),
        Triple("timestamp without time zone", null to null, "DATETIME"),
    )
    for ((baseType, params, sql) in mapped) {
        test("Basistyp '$baseType' ${params} wird $sql und traegt keine Notiz") {
            val result = generate(baseType, params.first, params.second)
            result.columnLine() shouldBe "`c` $sql"
            result.notes.shouldBeEmpty()
        }
    }

    // Ohne neutrales Gegenstueck: E053 + Ersatzspalte TEXT, nie der Rohname.
    val unmapped = listOf(
        "integer[]", "varchar[]", "numeric[]", "_int4", "timetz", "varbit(7)", "varbit",
        "bpchar", "money", "interval", "inet", "cidr", "macaddr", "tsvector",
        // MySQL kennt BIT, aber das neutrale Modell hat keinen Bit-Typ; eine Abbildung waere
        // eine neue Regel (Option C), die P1 nicht festlegt.
        "bit", "bit(3)",
        // PostgreSQL-`numeric` ohne Genauigkeit ist beliebig genau; `DECIMAL(10,0)` schnitte die
        // Nachkommastellen still ab.
        "numeric", "decimal",
    )
    for (baseType in unmapped) {
        test("Basistyp '$baseType' ohne Gegenstueck erzeugt genau eine E053 und die Spalte TEXT") {
            val result = generate(baseType)
            result.columnLine() shouldBe "`c` TEXT"
            val e053 = result.notes.filter { it.code == "E053" }
            e053 shouldHaveSize 1
            e053.single().type shouldBe NoteType.ACTION_REQUIRED
            e053.single().objectName shouldBe "t.c"
            e053.single().message shouldContain "'$baseType'"
            result.notes shouldHaveSize 1
        }
    }

    test("numeric mit precision/scale an der Domain ergibt DECIMAL(p,s), nicht DECIMAL(10,0)") {
        generate("numeric", precision = 12, scale = 4).columnLine() shouldBe "`c` DECIMAL(12,4)"
    }

    test("NOT NULL und CHECK der Domain bleiben neben dem aufgeloesten Typ") {
        val result = generate("integer", check = "c > 0", required = true)
        result.columnLine() shouldBe "`c` INT NOT NULL CHECK (c > 0)"
    }

    test("Domain ueber timestamptz(3) wird DATETIME mit W100, die Nachkommastellen traegt das Modell nicht") {
        val result = generate("timestamptz(3)")
        result.columnLine() shouldBe "`c` DATETIME"
        result.notes.map { it.code } shouldBe listOf("W100")
    }

    test("Domain ueber timestamptz traegt W100 wie eine gewoehnliche Spalte") {
        val result = generate("timestamptz")
        result.columnLine() shouldBe "`c` DATETIME"
        val w100 = result.notes.filter { it.code == "W100" }
        w100 shouldHaveSize 1
        w100.single().objectName shouldBe "t.c"
    }
})
