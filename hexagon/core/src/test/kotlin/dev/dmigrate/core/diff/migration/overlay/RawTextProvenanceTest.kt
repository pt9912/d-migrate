package dev.dmigrate.core.diff.migration.overlay

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Was ein geglueckter Lauf an Herkunft hinterlaesst: je Textfeld der
 * Autorentext und die Katalogform, die der Server daraufhin fuehrt.
 */
class RawTextProvenanceTest : FunSpec({

    fun schema(
        views: Map<String, ViewDefinition> = emptyMap(),
        constraints: List<ConstraintDefinition> = emptyList(),
        indices: List<IndexDefinition> = emptyList(),
    ) = SchemaDefinition(
        name = "s", version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = mapOf("status" to ColumnDefinition(NeutralType.Text(10))),
                constraints = constraints,
                indices = indices,
            ),
        ),
        views = views,
    )

    fun check(expression: String?) =
        ConstraintDefinition(name = "chk_status", type = ConstraintType.CHECK, expression = expression)

    test("ein CHECK ergibt ein Paar aus Autorentext und Katalogform") {
        val entries = RawTextProvenance.entriesOf(
            authored = schema(constraints = listOf(check("status = 'A'   -- nur aktive"))),
            observed = schema(constraints = listOf(check("((status = 'A'::text))"))),
        )

        val entry = entries.single()
        entry.objectType shouldBe "constraint"
        entry.objectPath shouldBe listOf("orders", "chk_status")
        entry.field shouldBe RawTextProvenanceFields.CHECK_EXPRESSION
        // Der Kommentar ist genau das, was aus der Katalogform nicht mehr
        // herauszuholen waere — deshalb steht er hier.
        entry.appliedAuthorText shouldBe "status = 'A'   -- nur aktive"
        entry.observedCatalogText shouldBe "((status = 'A'::text))"
    }

    test("ein Sichten-Rumpf ebenso") {
        val entries = RawTextProvenance.entriesOf(
            authored = schema(views = mapOf("v" to ViewDefinition(query = "SELECT status FROM orders"))),
            observed = schema(views = mapOf("v" to ViewDefinition(query = " SELECT\n  status\n FROM orders;"))),
        )

        entries.single().field shouldBe RawTextProvenanceFields.VIEW_QUERY
        entries.single().objectPath shouldBe listOf("v")
    }

    test("Praedikat und Ausdrucks-Schluessel eines Index, der Schluessel mit seiner Stellung") {
        val authored = IndexDefinition(
            name = "i", where = "status <> ''",
            columns = listOf(IndexColumn(name = "id"), IndexColumn(name = "upper(nm)", expression = "upper(nm)")),
        )
        val observed = IndexDefinition(
            name = "i", where = "(status <> ''::text)",
            columns = listOf(
                IndexColumn(name = "id"),
                IndexColumn(name = "upper(nm::text)", expression = "upper(nm::text)"),
            ),
        )

        val entries = RawTextProvenance.entriesOf(
            schema(indices = listOf(authored)),
            schema(indices = listOf(observed)),
        )

        entries.map { it.field } shouldBe listOf(
            RawTextProvenanceFields.INDEX_WHERE,
            RawTextProvenanceFields.INDEX_KEY_EXPRESSION,
        )
        // Stellung 2: der erste Schluessel ist eine gewoehnliche Spalte und
        // traegt keinen Ausdruck.
        entries.last().keyPosition shouldBe 2
        entries.last().appliedAuthorText shouldBe "upper(nm)"
        entries.last().observedCatalogText shouldBe "upper(nm::text)"
    }

    test("ein Feld, das keine Seite fuehrt, ergibt keinen Eintrag") {
        RawTextProvenance.entriesOf(
            authored = schema(constraints = listOf(check(null))),
            observed = schema(constraints = listOf(check(null))),
        ).shouldBeEmpty()
    }

    test("ein Objekt, das nur eine Seite kennt, ergibt keinen Eintrag — die Aussage braucht beide") {
        RawTextProvenance.entriesOf(
            authored = schema(views = mapOf("v" to ViewDefinition(query = "SELECT 1"))),
            observed = schema(),
        ).shouldBeEmpty()
    }

    test("der Bezeichner bleibt ueber Laeufe derselbe") {
        // Ein zufaellig vergebener machte jedes Dokument gegen das vorige
        // unvergleichbar.
        RawTextProvenance.entryId(
            "index", listOf("orders", "i"), RawTextProvenanceFields.INDEX_KEY_EXPRESSION, 2,
        ) shouldBe "index:orders.i:key-expression:2"
        RawTextProvenance.entryId("view", listOf("v"), RawTextProvenanceFields.VIEW_QUERY) shouldBe "view:v:query"
    }
})
