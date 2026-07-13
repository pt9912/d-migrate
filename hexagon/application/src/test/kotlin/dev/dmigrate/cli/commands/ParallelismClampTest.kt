package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ParallelismClampTest : FunSpec({

    test("non-sqlite keeps the requested degree") {
        ParallelismClamp.effective(4, involvesSqlite = false) shouldBe 4
    }

    test("degree is floored at 1") {
        ParallelismClamp.effective(0, involvesSqlite = false) shouldBe 1
        ParallelismClamp.effective(-3, involvesSqlite = false) shouldBe 1
    }

    test("sqlite clamps N>1 to 1 and emits one note") {
        val notes = mutableListOf<String>()
        val result = ParallelismClamp.effective(4, involvesSqlite = true) { notes.add(it) }
        result shouldBe 1
        notes shouldContainExactly listOf(
            "--parallel 4 ignored: SQLite uses a single connection (pool size 1); running sequentially.",
        )
    }

    test("sqlite with degree 1 stays sequential without a note") {
        val notes = mutableListOf<String>()
        val result = ParallelismClamp.effective(1, involvesSqlite = true) { notes.add(it) }
        result shouldBe 1
        notes.shouldBeEmpty()
    }
})
