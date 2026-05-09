package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Direct tests for [TopologicalSorter] — happy paths are covered
 * via [DiffPlannerTest], but cycle detection and tie-breaker stability
 * are easier to assert here without going through the full planner.
 */
class TopologicalSorterTest : FunSpec({

    fun makeOp(id: String, deps: Set<String> = emptySet(), name: String = "t") =
        DiffOperation.CreateTable(
            id = id,
            objectRef = DiffObjectRef(DiffObjectType.TABLE, listOf(name)),
            table = TableDefinition(),
            dependencies = deps,
        )

    test("empty input yields empty result and no cycle") {
        val r = TopologicalSorter.sort(emptyList())
        r.sorted.shouldBeEmpty()
        r.cycleIds.shouldBeEmpty()
    }

    test("acyclic input is sorted in dependency order; phase / objectRef tie-break stable") {
        val a = makeOp("a", deps = emptySet(), name = "a")
        val b = makeOp("b", deps = setOf("a"), name = "b")
        val c = makeOp("c", deps = setOf("b"), name = "c")
        val r = TopologicalSorter.sort(listOf(c, b, a))
        r.cycleIds.shouldBeEmpty()
        r.sorted.map { it.id } shouldBe listOf("a", "b", "c")
    }

    test("cycle is reported in cycleIds; sorted suffix carries the cycle members in deterministic order") {
        // a → b → a (mutual)
        val a = makeOp("a", deps = setOf("b"), name = "a")
        val b = makeOp("b", deps = setOf("a"), name = "b")
        val c = makeOp("c", deps = emptySet(), name = "c")
        val r = TopologicalSorter.sort(listOf(a, b, c))
        r.cycleIds shouldBe setOf("a", "b")
        // c is the only ready op so it is sorted first; a/b appear in stable order at the tail.
        r.sorted.map { it.id }.first() shouldBe "c"
        r.sorted.map { it.id }.drop(1) shouldContainExactly listOf("a", "b")
    }

    test("self-loop is reported as a cycle of one") {
        val a = makeOp("a", deps = setOf("a"), name = "a")
        val r = TopologicalSorter.sort(listOf(a))
        r.cycleIds shouldBe setOf("a")
        r.sorted.map { it.id } shouldBe listOf("a")
    }

    test("cross-plan deps (id not present) are silently dropped — no spurious cycle") {
        val a = makeOp("a", deps = setOf("not-in-plan"), name = "a")
        val r = TopologicalSorter.sort(listOf(a))
        r.cycleIds.shouldBeEmpty()
        r.sorted.map { it.id } shouldBe listOf("a")
    }

    test("tie-breaker uses phase before objectRef name") {
        // Two unrelated ops in different phases (CONSTRAINTS < INDEXES).
        val constraint = DiffOperation.AddPrimaryKey(
            id = "z-pk",
            objectRef = DiffObjectRef(DiffObjectType.PRIMARY_KEY, listOf("orders")),
            columns = listOf("id"),
        )
        val column = DiffOperation.AddColumn(
            id = "a-col",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("orders", "x")),
            column = dev.dmigrate.core.model.ColumnDefinition(NeutralType.Text()),
        )
        val r = TopologicalSorter.sort(listOf(constraint, column))
        // COLUMNS phase precedes CONSTRAINTS phase regardless of id.
        r.sorted.first().id shouldBe "a-col"
        r.sorted.last().id shouldBe "z-pk"
    }
})
