package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan

/**
 * ADR 0021: `generate` emittiert Spalten in physischer Ordinalreihenfolge,
 * nicht alphabetisch und nicht in Einfügereihenfolge.
 */
class PostgresDdlGeneratorOrdinalTest : FunSpec({

    val generator = PostgresDdlGenerator()

    test("CREATE TABLE columns follow ordinal, not alphabetical/insertion order") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "alpha" to ColumnDefinition(NeutralType.Text(), ordinal = 3),
                "zeta" to ColumnDefinition(NeutralType.Integer, ordinal = 1),
                "mid" to ColumnDefinition(NeutralType.BooleanType, ordinal = 2),
            ),
        )
        val schema = SchemaDefinition(name = "s", version = "1", tables = mapOf("t" to table))

        val ddl = generator.generate(schema).render()

        // Reihenfolge im CREATE TABLE: zeta (1) < mid (2) < alpha (3).
        ddl.indexOf("\"zeta\"") shouldBeLessThan ddl.indexOf("\"mid\"")
        ddl.indexOf("\"mid\"") shouldBeLessThan ddl.indexOf("\"alpha\"")
    }
})
