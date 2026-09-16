package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Ein Objekt, das nicht in die Ausgabe kommt, steht in `skippedObjects` —
 * nicht nur als Notiz. Vor diesem Test verschwanden eine CHECK-Constraint
 * mit fremder Grammatik und ein EXCLUDE-Constraint spurlos aus dem Bestand,
 * den der Aufrufer zaehlen kann.
 */
/**
 * Die Index-Seite desselben Vertrags. Sie kam spaeter als die Constraint-Seite
 * und war bis dahin **unvollstaendig**: der Index-Helfer baute fuenf
 * Verluststellen ueber eine private Funktion, die nur die Notiz erzeugte —
 * `E066`, `E070`, `E071`, „Spatial nicht renderbar" und der Ausdrucks-Index
 * fielen aus der Ausgabe, ohne in `skippedObjects` zu stehen. Dieselbe Klasse
 * wie der Konsumentenbefund gegen 1.7.0, eine Datei weiter.
 */
class MssqlIndexActionRequiredSkippedObjectsTest : FunSpec({

    val generator = MssqlDdlGenerator()

    fun schemaWith(table: TableDefinition) = SchemaDefinition(
        name = "t", version = "1.0", tables = mapOf("orders" to table),
    )

    test("ein Ausdrucks-Index, den SQL Server nicht bauen kann, steht in skippedObjects") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "email" to ColumnDefinition(NeutralType.Text(100), required = true),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(
                    name = "ix_expr",
                    columns = listOf(IndexColumn.expression("lower(email)")),
                ),
            ),
        )
        val result = generator.generate(schemaWith(table))
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "index"
        skipped.name shouldBe "ix_expr"
        skipped.code shouldBe "E057"
    }
})

class MssqlActionRequiredSkippedObjectsTest : FunSpec({

    val generator = MssqlDdlGenerator()

    fun schemaWith(table: TableDefinition) = SchemaDefinition(
        name = "t", version = "1.0", tables = mapOf("orders" to table),
    )

    test("eine CHECK-Constraint mit fremder Grammatik steht in skippedObjects") {
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(name = "ck_cast", type = ConstraintType.CHECK, expression = "id::int > 0"),
            ),
        )
        val result = generator.generate(schemaWith(table))
        result.skippedObjects shouldHaveSize 1
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "constraint"
        skipped.name shouldBe "ck_cast"
        skipped.code shouldBe "E053"
    }

    test("ein EXCLUDE-Constraint (in SQL Server grundsaetzlich unmoeglich) steht in skippedObjects") {
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(name = "no_overlap", type = ConstraintType.EXCLUDE, expression = "id WITH ="),
            ),
        )
        val result = generator.generate(schemaWith(table))
        result.skippedObjects shouldHaveSize 1
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "constraint"
        skipped.name shouldBe "no_overlap"
        skipped.code shouldBe "E054"
    }

    test("eine berechnete Spalte mit fremder Grammatik steht in skippedObjects") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "total" to ColumnDefinition(
                    NeutralType.Decimal(12, 2),
                    generation = ColumnGeneration.Computed("qty::int * price::int", stored = true),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val result = generator.generate(schemaWith(table))
        result.skippedObjects shouldHaveSize 1
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "computed_expression"
        skipped.name shouldBe "total"
        skipped.code shouldBe "E053"
    }
})
