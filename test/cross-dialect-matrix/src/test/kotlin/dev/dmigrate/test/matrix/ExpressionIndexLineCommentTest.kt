package dev.dmigrate.test.matrix

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.mysql.MysqlDdlGenerator
import dev.dmigrate.driver.oracle.OracleDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import dev.dmigrate.driver.DdlGenerator
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Ein `--`-Zeilenkommentar im Ausdruck eines Index-Schluessels verschluckt die
 * schliessende Klammer nicht (Issue #55, Folge): in jedem Dialekt, der
 * Ausdrucks-Indizes rendert, steht sie auf der Zeile hinter dem Kommentar.
 * Ein `--` in einer Zeichenkette bleibt unberuehrt.
 */
class ExpressionIndexLineCommentTest : FunSpec({

    val generators: Map<DatabaseDialect, () -> DdlGenerator> = mapOf(
        DatabaseDialect.POSTGRESQL to { PostgresDdlGenerator() },
        DatabaseDialect.MYSQL to { MysqlDdlGenerator() },
        DatabaseDialect.SQLITE to { SqliteDdlGenerator() },
        DatabaseDialect.ORACLE to { OracleDdlGenerator() },
    )

    fun schema(expression: String) = SchemaDefinition(
        name = "S", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "a" to ColumnDefinition(NeutralType.Text(), required = true),
                ),
                primaryKey = listOf("id"),
                indices = listOf(
                    IndexDefinition(name = "ix_t", columns = listOf(IndexColumn(name = expression, expression = expression))),
                ),
            ),
        ),
    )

    for ((dialect, generator) in generators) {
        test("$dialect: closing parenthesis stands behind a trailing line comment") {
            val sql = generator().generate(schema("lower(a) -- c")).render()
            withClue(sql) {
                val line = sql.lines().first { it.contains("-- c") && it.contains("lower(a)") }
                line.substringAfter("-- c").isEmpty() shouldBe true
                sql shouldContain "lower(a) -- c\n)"
            }
        }

        test("$dialect: a comment in the middle and a string with dashes stay as written") {
            val sql = generator().generate(schema("lower(a) -- c\n || '--'")).render()
            sql shouldContain "lower(a) -- c\n || '--')"
        }
    }
})
