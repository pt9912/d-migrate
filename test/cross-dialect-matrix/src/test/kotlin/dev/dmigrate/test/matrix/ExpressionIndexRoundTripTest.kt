package dev.dmigrate.test.matrix

import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.sqlite.SqliteDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteSchemaReader
import dev.dmigrate.format.SchemaFileResolver
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Rundlauf des Ausdrucks-Index gegen echtes SQLite (Issue #55): die Datei der
 * Matrix-Fixture `expression-index` wird mit dem SQLite-Generator angelegt und
 * vom SQLite-Reader zurueckgelesen; jeder Index muss so zurueckkommen, wie die
 * Datei ihn schreibt. Der Generator klammert jeden Ausdruck (`((lower(a)))`),
 * der Reader nimmt genau diese eine Klammer wieder ab -- und keine, die zum
 * Ausdruck gehoert (`(a) || (b)`, `'(' || a`).
 *
 * Der Sweep (`MatrixSweepTest`) rendert dieselbe Fixture file-mode in jedem
 * Dialekt; hier steht, was er nicht kann: die Datenbank lesen.
 */
class ExpressionIndexRoundTripTest : FunSpec({

    val cell = MatrixCell("expression-index", DatabaseDialect.SQLITE, MatrixCell.Kind.POSITIVE, 0)
    val desiredYaml = MatrixFixtures.loadPair(cell).desiredYaml

    /** Was am Index zaehlt: Name, Eindeutigkeit, Praedikat, Schluessel (Ausdruck oder Spalte, Richtung). */
    fun shape(index: IndexDefinition) = Triple(
        index.unique to index.where,
        index.columns.map(::keyOf),
        index.name,
    )

    test("jeder Ausdrucks-Index der Fixture kommt aus SQLite so zurueck, wie die Datei ihn schreibt") {
        val dir = Files.createTempDirectory("matrix-expression-index-")
        val path = dir.resolve("desired.yaml")
        Files.writeString(path, desiredYaml)
        val desired = SchemaFileResolver.codecForPath(path).read(path)

        val create = SqliteDdlGenerator().generate(desired).render()
        val pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(DatabaseDialect.SQLITE, null, null, ":memory:", null, null),
        )
        try {
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { st ->
                    create.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) }
                }
            }
            val read = SqliteSchemaReader().read(pool).schema.tables.getValue("t").indices
            val wanted = desired.tables.getValue("t").indices

            withClue("SQL:\n$create") {
                read.map(::shape) shouldContainExactlyInAnyOrder wanted.map(::shape)
            }
            read.size shouldBe wanted.size
        } finally {
            pool.close()
            dir.toFile().deleteRecursively()
        }
    }
})

private fun keyOf(column: IndexColumn): Triple<String?, String?, String?> =
    Triple(column.expression, if (column.expression == null) column.name else null, column.direction?.name?.takeIf { it == "DESC" })
