package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.NoteType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * Ein Objekt, das nicht in die Ausgabe kommt, steht in `skippedObjects` —
 * nicht nur als Notiz. Vor diesem Test verschwanden eine CHECK-Constraint
 * mit fremder Grammatik und ein EXCLUDE-Constraint spurlos aus dem Bestand,
 * den der Aufrufer zaehlen kann.
 *
 * Der LOB-Schluessel-Block unten kam mit dem Konsumentenbefund gegen 1.7.0
 * dazu: `unkeyableKeyNote` rief nur `toNote()`, die drei Index-Pfade trugen
 * den Zaehler gar nicht. Oracle meldete `skippedCount: 1` statt 3.
 */
class OracleActionRequiredSkippedObjectsTest : FunSpec({

    val generator = OracleDdlGenerator()

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

    test("ein EXCLUDE-Constraint (in Oracle grundsaetzlich unmoeglich) steht in skippedObjects") {
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

    // ── LOB-Schluessel (Konsumentenbefund gegen 1.7.0) ──────────────────
    //
    // `isUnkeyable` behandelt `text` OHNE `max_length` konservativ als LOB:
    // Oracle kann darauf keinen Schluessel bauen (ORA-02329). Jede dieser
    // Stellen liess das Objekt vorher aus der Ausgabe fallen, ohne es zu
    // zaehlen — der Ausgang blieb Exit 0.

    /** `text` ohne `max_length` — Oracle fuehrt es als unkeyable. */
    val unbound = ColumnDefinition(NeutralType.Text(null), required = true)

    test("ein ungenanntes UNIQUE auf einer ungebundenen text-Spalte steht in skippedObjects") {
        // Ohne Namen rendert die Spalte ihr UNIQUE **inline**
        // (`NamedUniqueConstraints.rendersInline`) — das ist der
        // Spalten-Pfad, `OracleColumnConstraintHelper` ueber den ColumnContext.
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "email" to unbound.copy(unique = true),
            ),
            primaryKey = listOf("id"),
        )
        val result = generator.generate(schemaWith(table))
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "constraint"
        skipped.name shouldBe "uq_orders_email"
        skipped.code shouldBe "E057"
    }

    test("ein benanntes UNIQUE auf einer ungebundenen text-Spalte steht in skippedObjects") {
        // MIT Namen wandert es auf die Tabellenebene
        // (`NamedUniqueConstraints.named`) — ein anderer Pfad als oben, und
        // beide muessen zaehlen. Ein Test je Pfad: der Sabotage-Lauf hat
        // gezeigt, dass eine Fixture ohne Namen den Tabellenpfad gar nicht
        // erreicht.
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "email" to unbound.copy(unique = true, uniqueConstraintName = "uq_orders_email"),
            ),
            primaryKey = listOf("id"),
        )
        val result = generator.generate(schemaWith(table))
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "constraint"
        skipped.name shouldBe "uq_orders_email"
        skipped.code shouldBe "E057"
        // Der Verlust ist gezaehlt **und** die Constraint fehlt in der Ausgabe.
        // Geprueft wird das SQL, nicht `render()` — das stellt die Notizen als
        // Kommentare voran und traegt den Namen damit selbst.
        result.statements.joinToString("\n") { it.sql } shouldNotContain "uq_orders_email"
    }

    test("ein PRIMARY KEY auf einer LOB-Spalte steht in skippedObjects") {
        val table = TableDefinition(
            columns = mapOf("sku" to unbound),
            primaryKey = listOf("sku"),
        )
        val result = generator.generate(schemaWith(table))
        val skipped = result.skippedObjects.single()
        skipped.name shouldBe "pk_orders"
        skipped.code shouldBe "E057"
    }

    test("eine UNIQUE-Constraint auf einer LOB-Spalte steht in skippedObjects") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "sku" to unbound,
            ),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(name = "uq_sku", type = ConstraintType.UNIQUE, columns = listOf("sku")),
            ),
        )
        val result = generator.generate(schemaWith(table))
        result.skippedObjects.single().name shouldBe "uq_sku"
        result.skippedObjects.single().code shouldBe "E057"
    }

    test("ein Index auf einer LOB-Spalte steht in skippedObjects; die Notiz bleibt W152") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "body" to unbound,
            ),
            primaryKey = listOf("id"),
            indices = listOf(IndexDefinition(name = "ix_body", columns = listOf(IndexColumn("body")))),
        )
        val result = generator.generate(schemaWith(table))
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "index"
        skipped.name shouldBe "ix_body"
        // Die Stufe der Notiz bleibt eine Warnung — der Fix zaehlt das Objekt,
        // er hebt die Notiz nicht an.
        skipped.code shouldBe "W152"
        result.notes.single { it.objectName == "ix_body" }.type shouldBe NoteType.WARNING
    }

    test("ein mehrspaltiger Volltext-Index steht in skippedObjects, mit POST_DATA") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "a" to ColumnDefinition(NeutralType.Text(100), required = true),
                "b" to ColumnDefinition(NeutralType.Text(100), required = true),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(
                    name = "ix_ft", columns = listOf(IndexColumn("a"), IndexColumn("b")),
                    type = IndexType.FULLTEXT,
                ),
            ),
        )
        val result = generator.generate(schemaWith(table))
        val skipped = result.skippedObjects.single()
        skipped.name shouldBe "ix_ft"
        skipped.code shouldBe "E057"
        // Ausnahme von der Block-Regel: das Statement waere POST_DATA, und der
        // Index-Block wird von `tagNewSkips` gar nicht erreicht.
        skipped.phase shouldBe DdlPhase.POST_DATA
        result.skippedObjectsForPhase(DdlPhase.POST_DATA) shouldHaveSize 1
    }
})
