package dev.dmigrate.format

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.mssql.MssqlDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.oracle.OracleDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * S6 — was die **fünf** Generatoren aus einer `smallint`-Identity machen.
 *
 * Die Messung begründet, warum der PostgreSQL-Reverse eine
 * `smallint GENERATED ALWAYS AS IDENTITY`-Spalte weiter als `identifier`
 * liest und den verlorenen Modus nur **meldet** (`R406`), statt das Modell zu
 * erweitern: drei der fünf Ziele können die Form gar nicht rendern und
 * verlieren die Erzeugung **still**, und das eigene Modell lehnt sie mit
 * `E130` ohnehin ab (`SchemaColumnValidationRules`). Erreichbar wäre sie nur
 * über einen Reverse, der mehr liest, als sich erzeugen lässt.
 *
 * Der Fall kommt hier **an der Validierung vorbei**: die Spec wird direkt
 * gegen den Generator gefahren, nicht über `schema generate`. Genau deshalb
 * steht die Messung hier und nicht im Handbuch — sie beschreibt eine Form,
 * die ein Anwender nicht eingeben kann.
 */
class SmallIntIdentityRenderTest : FunSpec({

    fun schema(type: NeutralType) = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(
                        type = type,
                        generation = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS),
                        ordinal = 1,
                    ),
                    "label" to ColumnDefinition(NeutralType.Text(maxLength = 20), ordinal = 2),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun render(generator: DdlGenerator, type: NeutralType): Pair<String, List<String>> {
        val result = generator.generate(schema(type), DdlGenerationOptions())
        return result.render() to result.notes.map { it.code }
    }

    val generators: List<Triple<DatabaseDialect, DdlGenerator, String>> = listOf(
        Triple(DatabaseDialect.POSTGRESQL, PostgresDdlGenerator(), "postgresql"),
        Triple(DatabaseDialect.MYSQL, MysqlDdlGenerator(), "mysql"),
        Triple(DatabaseDialect.SQLITE, SqliteDdlGenerator(), "sqlite"),
        Triple(DatabaseDialect.MSSQL, MssqlDdlGenerator(), "mssql"),
        Triple(DatabaseDialect.ORACLE, OracleDdlGenerator(), "oracle"),
    )

    /**
     * Drei Generatoren kennen die Form nicht und lassen die Erzeugung fallen
     * — PostgreSQL und MySQL **ohne** jede Meldung, SQLite ebenso. Zwei
     * rendern sie (SQL Server als `SMALLINT IDENTITY(1,1)`, Oracle als
     * `NUMBER(5) GENERATED ALWAYS AS IDENTITY`).
     */
    test("S6-Messung: drei der fuenf Generatoren verlieren die smallint-Identity still") {
        val silent = listOf(
            Triple(DatabaseDialect.POSTGRESQL, PostgresDdlGenerator(), "SMALLINT"),
            Triple(DatabaseDialect.MYSQL, MysqlDdlGenerator(), "SMALLINT"),
            Triple(DatabaseDialect.SQLITE, SqliteDdlGenerator(), "INTEGER"),
        )
        for ((dialect, generator, declaredType) in silent) {
            val (sql, codes) = render(generator, NeutralType.SmallInt)
            withClue("$dialect:\n$sql\ncodes=$codes") {
                sql shouldContain declaredType
                // Keine Autowert-Klausel: die Erzeugung ist weg.
                sql.contains("IDENTITY", ignoreCase = true) shouldBe false
                sql.contains("AUTO_INCREMENT", ignoreCase = true) shouldBe false
                sql.contains("AUTOINCREMENT", ignoreCase = true) shouldBe false
                sql.contains("SERIAL", ignoreCase = true) shouldBe false
                // Und keine Meldung darüber — das ist der stille Teil.
                codes.none { it == "W151" || it == "W135" || it == "W163" } shouldBe true
            }
        }
    }

    test("S6-Messung: SQL Server und Oracle rendern die smallint-Identity") {
        val (mssql, _) = render(MssqlDdlGenerator(), NeutralType.SmallInt)
        mssql shouldContain "SMALLINT IDENTITY(1,1)"

        val (oracle, _) = render(OracleDdlGenerator(), NeutralType.SmallInt)
        oracle shouldContain "GENERATED ALWAYS AS IDENTITY"
    }

    /**
     * Die Gegenprobe, die zeigt, dass die Grenze an der **Breite** hängt und
     * nicht an der Erzeugung: dieselbe Spalte als `integer` rendert überall
     * als Autowert.
     */
    test("S6-Gegenprobe: als integer rendert jeder der fuenf einen Autowert") {
        for ((dialect, generator, _) in generators) {
            val (sql, codes) = render(generator, NeutralType.Integer)
            withClue("$dialect:\n$sql\ncodes=$codes") {
                val hasAuto = sql.contains("IDENTITY", ignoreCase = true) ||
                    sql.contains("AUTO_INCREMENT", ignoreCase = true) ||
                    sql.contains("AUTOINCREMENT", ignoreCase = true) ||
                    sql.contains("SERIAL", ignoreCase = true)
                hasAuto shouldBe true
            }
        }
    }
})
