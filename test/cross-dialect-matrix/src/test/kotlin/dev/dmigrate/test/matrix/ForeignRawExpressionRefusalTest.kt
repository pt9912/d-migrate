package dev.dmigrate.test.matrix

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
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

/**
 * Ein roher Ausdruck aus einem **fremden** Dialekt geht in keinem der fuenf
 * Ziele ungeprueft durch.
 *
 * Die Vorlage ist ein Konsumentenbefund: ein PostgreSQL-Reverse liefert
 * `(email ~~ '%@%'::text)` fuer einen CHECK und `((quantity)::numeric *
 * unit_price)` fuer eine berechnete Spalte. Der Reverse **uebersetzt** solchen
 * Text nicht — der Text ist die Aussage ([ADR 0053]). Das Rendern gegen ein
 * anderes Ziel muss ihn deshalb benannt verwerfen (`E053`), statt ihn
 * weiterzureichen, wo `~~` und `::` nicht existieren.
 *
 * Die Spec steht hier und nicht je Dialekt: die Zusage gilt fuer **alle**, und
 * ein neuer Dialekt soll an dieser Stelle auffallen statt in vier Kopien zu
 * fehlen.
 */
class ForeignRawExpressionRefusalTest : FunSpec({

    val pgCheck = "(email ~~ '%@%'::text)"
    val pgComputed = "((quantity)::numeric * unit_price)"

    /** Jeder Dialekt ausser dem, aus dem der Text stammt. */
    val foreignTargets: Map<DatabaseDialect, () -> DdlGenerator> = mapOf(
        DatabaseDialect.MYSQL to { MysqlDdlGenerator() },
        DatabaseDialect.SQLITE to { SqliteDdlGenerator() },
        DatabaseDialect.MSSQL to { MssqlDdlGenerator() },
        DatabaseDialect.ORACLE to { OracleDdlGenerator() },
    )

    fun schemaWith(table: TableDefinition) =
        SchemaDefinition(name = "App", version = "1", tables = mapOf("order_items" to table))

    val withCheck = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
            "email" to ColumnDefinition(NeutralType.Text(), required = true),
        ),
        primaryKey = listOf("id"),
        constraints = listOf(
            ConstraintDefinition(name = "customers_email_check", type = ConstraintType.CHECK, expression = pgCheck),
        ),
    )

    val withComputed = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
            "quantity" to ColumnDefinition(NeutralType.Integer, required = true),
            "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2), required = true),
            "line_total" to ColumnDefinition(
                NeutralType.Decimal(14, 2),
                generation = ColumnGeneration.Computed(pgComputed, stored = true),
            ),
        ),
        primaryKey = listOf("id"),
    )

    foreignTargets.forEach { (dialect, generator) ->
        test("${dialect.name.lowercase()} refuses a PostgreSQL CHECK expression") {
            val result = generator().generate(schemaWith(withCheck), DdlGenerationOptions())
            val sql = result.statements.joinToString("\n") { it.sql }

            withClue(sql) {
                sql.contains("~~") shouldBe false
                sql.contains("::") shouldBe false
            }
            withClue(result.notes.joinToString { "${it.code}:${it.message}" }) {
                result.notes.any { it.code == "E053" } shouldBe true
            }
        }

        test("${dialect.name.lowercase()} refuses a PostgreSQL computed expression") {
            val result = generator().generate(schemaWith(withComputed), DdlGenerationOptions())
            val sql = result.statements.joinToString("\n") { it.sql }

            withClue(sql) {
                sql.contains("~~") shouldBe false
                sql.contains("::") shouldBe false
            }
            withClue(result.notes.joinToString { "${it.code}:${it.message}" }) {
                result.notes.any { it.code == "E053" } shouldBe true
            }
        }
    }

    test("PostgreSQL itself renders its own expressions unchanged") {
        val result = PostgresDdlGenerator().generate(schemaWith(withComputed), DdlGenerationOptions())
        val sql = result.statements.joinToString("\n") { it.sql }

        withClue(sql) { sql.contains(pgComputed) shouldBe true }
        result.notes.none { it.code == "E053" } shouldBe true
    }

    test("PostgreSQL refuses a MySQL expression, so the rule is not one-directional") {
        val mysqlFlavoured = withComputed.copy(
            columns = LinkedHashMap(withComputed.columns).apply {
                this["line_total"] = ColumnDefinition(
                    NeutralType.Decimal(14, 2),
                    generation = ColumnGeneration.Computed("`quantity` * `unit_price`", stored = true),
                )
            },
        )

        val result = PostgresDdlGenerator().generate(schemaWith(mysqlFlavoured), DdlGenerationOptions())

        withClue(result.statements.joinToString("\n") { it.sql }) {
            result.notes.any { it.code == "E053" } shouldBe true
        }
    }
})
