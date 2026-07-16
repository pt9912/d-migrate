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

    test("fallbackIfIncompatible: config-sourced N>1 with an incompatible flag → 1 + origin-aware note") {
        val notes = mutableListOf<String>()
        val result = ParallelismClamp.fallbackIfIncompatible(
            parallel = 8, fromCli = false, sourceLabel = "pipeline.parallelism: auto (= 8)",
            incompatibleFlag = "--resume", onNote = { notes.add(it) },
        )
        result shouldBe 1
        notes shouldContainExactly listOf(
            "pipeline.parallelism: auto (= 8) ignored with --resume: running sequentially.",
        )
    }

    test("fallbackIfIncompatible: CLI-explicit is NOT reduced (runner hard-fails instead)") {
        val notes = mutableListOf<String>()
        val result = ParallelismClamp.fallbackIfIncompatible(
            parallel = 8, fromCli = true, sourceLabel = "--parallel 8",
            incompatibleFlag = "--resume", onNote = { notes.add(it) },
        )
        result shouldBe 8
        notes.shouldBeEmpty()
    }

    test("fallbackIfIncompatible: no incompatible flag and degree 1 are no-ops") {
        val notes = mutableListOf<String>()
        ParallelismClamp.fallbackIfIncompatible(8, false, "x", incompatibleFlag = null) { notes.add(it) } shouldBe 8
        ParallelismClamp.fallbackIfIncompatible(1, false, "x", incompatibleFlag = "--atomic") { notes.add(it) } shouldBe 1
        notes.shouldBeEmpty()
    }
})
