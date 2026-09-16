package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.ReferentialAction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Die Kurzform eines Index bzw. Constraints traegt jedes Feld, das der
 * Vergleich wertet — eine Aenderung an einem davon ergibt zwei verschiedene
 * Zeilen.
 */
class CompareSignatureTest : FunSpec({

    val check = ConstraintDefinition(name = "ck_qty", type = ConstraintType.CHECK, expression = "qty > 0")

    test("a CHECK names its expression") {
        CompareSignature.constraint(check) shouldBe "ck_qty (check: qty > 0)"
        CompareSignature.constraint(check.copy(expression = "qty > 1")) shouldNotBe CompareSignature.constraint(check)
    }

    test("a CHECK without an expression is still named, never blank") {
        CompareSignature.constraint(check.copy(expression = null)) shouldBe "ck_qty (check)"
    }

    test("a foreign key names its columns, target and actions") {
        val fk = ConstraintDefinition(
            name = "fk_order", type = ConstraintType.FOREIGN_KEY, columns = listOf("order_id"),
            references = ConstraintReferenceDefinition(
                table = "orders", columns = listOf("id"),
                onDelete = ReferentialAction.CASCADE, onUpdate = ReferentialAction.SET_NULL,
            ),
        )
        CompareSignature.constraint(fk) shouldBe
            "fk_order (foreign_key on [order_id] -> orders[id] on_delete=cascade on_update=set_null)"
        CompareSignature.constraint(fk.copy(references = fk.references!!.copy(onDelete = null))) shouldBe
            "fk_order (foreign_key on [order_id] -> orders[id] on_update=set_null)"
    }

    test("a unique constraint names its columns") {
        val unique = ConstraintDefinition(name = "uq_ab", type = ConstraintType.UNIQUE, columns = listOf("a", "b"))
        CompareSignature.constraint(unique) shouldBe "uq_ab (unique on [a,b])"
        CompareSignature.constraint(unique.copy(columns = emptyList())) shouldBe "uq_ab (unique)"
    }

    test("an index names its keys and predicate") {
        val index = IndexDefinition(
            name = "ix_open", columns = listOf(IndexColumn("status"), IndexColumn("created", IndexSortDirection.DESC)),
            unique = true, where = "status <> 'DONE'",
        )
        CompareSignature.index(index) shouldBe "ix_open [btree,unique] on (status, created DESC) where status <> 'DONE'"
        CompareSignature.index(index.copy(where = "status <> 'VOID'")) shouldNotBe CompareSignature.index(index)
    }

    test("an index names its include columns, clustering and text search configuration") {
        val index = IndexDefinition(
            name = "ix_doc", columns = listOf(IndexColumn("body")), type = IndexType.FULLTEXT,
            includeColumns = listOf("title"), clustered = true, textSearchConfig = "english",
        )
        CompareSignature.index(index) shouldBe
            "ix_doc [fulltext,clustered] on (body) include (title) text_search=english"
    }

    test("an unnamed index is named by its keys, and a key expression is marked") {
        val index = IndexDefinition(columns = listOf(IndexColumn("a"), IndexColumn.expression("upper(b)")))
        CompareSignature.index(index) shouldBe "a,expr:upper(b) [btree] on (a, expr:upper(b))"
    }

    test("the generate-only full-text hints are no part of it") {
        val index = IndexDefinition(name = "ix_doc", columns = listOf(IndexColumn("body")), type = IndexType.FULLTEXT)
        CompareSignature.index(index.copy(fullTextVectorColumn = "body_tsv", fullTextAccessMethod = IndexType.GIN)) shouldBe
            CompareSignature.index(index)
    }
})
