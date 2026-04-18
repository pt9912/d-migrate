package dev.dmigrate.core.dependency

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class TableDependencySortTest : FunSpec({

    test("empty input") {
        val result = sortTablesByDependency(emptySet(), emptyList())
        result.sorted.shouldBeEmpty()
        result.circularEdges.shouldBeEmpty()
    }

    test("single table no edges") {
        val result = sortTablesByDependency(setOf("users"), emptyList())
        result.sorted shouldContainExactly listOf("users")
        result.circularEdges.shouldBeEmpty()
    }

    test("linear chain: a -> b -> c") {
        val result = sortTablesByDependency(
            setOf("a", "b", "c"),
            listOf(
                FkEdge("a", "b_id", "b", "id"),
                FkEdge("b", "c_id", "c", "id"),
            ),
        )
        // c first (no deps), then b, then a
        result.sorted shouldContainExactly listOf("c", "b", "a")
        result.circularEdges.shouldBeEmpty()
    }

    test("diamond: a -> b, a -> c, b -> d, c -> d") {
        val result = sortTablesByDependency(
            setOf("a", "b", "c", "d"),
            listOf(
                FkEdge("a", toTable = "b"),
                FkEdge("a", toTable = "c"),
                FkEdge("b", toTable = "d"),
                FkEdge("c", toTable = "d"),
            ),
        )
        // d first, then b and c (order depends on input), then a
        result.sorted.first() shouldBe "d"
        result.sorted.last() shouldBe "a"
        result.circularEdges.shouldBeEmpty()
    }

    test("self-reference is ignored") {
        val result = sortTablesByDependency(
            setOf("a", "b"),
            listOf(
                FkEdge("a", "parent_id", "a", "id"), // self-ref
                FkEdge("a", "b_id", "b", "id"),
            ),
        )
        result.sorted shouldContainExactly listOf("b", "a")
        result.circularEdges.shouldBeEmpty()
    }

    test("reference to unknown table is ignored") {
        val result = sortTablesByDependency(
            setOf("a", "b"),
            listOf(
                FkEdge("a", "b_id", "b", "id"),
                FkEdge("a", "ext_id", "external_table", "id"), // not in set
            ),
        )
        result.sorted shouldContainExactly listOf("b", "a")
        result.circularEdges.shouldBeEmpty()
    }

    test("cycle: a -> b -> a") {
        val result = sortTablesByDependency(
            setOf("a", "b"),
            listOf(
                FkEdge("a", "b_id", "b", "id"),
                FkEdge("b", "a_id", "a", "id"),
            ),
        )
        // Both in sorted (appended as remaining), circular edges reported
        result.sorted shouldHaveSize 2
        result.circularEdges shouldHaveSize 2
    }

    test("partial cycle: c is clean, a <-> b cycle") {
        val result = sortTablesByDependency(
            setOf("a", "b", "c"),
            listOf(
                FkEdge("a", toTable = "b"),
                FkEdge("b", toTable = "a"),
                FkEdge("a", toTable = "c"),
            ),
        )
        // c first (no cycle), then a and b appended
        result.sorted.first() shouldBe "c"
        result.circularEdges shouldHaveSize 2
        result.circularEdges.all { it.fromTable in setOf("a", "b") } shouldBe true
    }
})
