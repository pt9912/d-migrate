package dev.dmigrate.test.matrix

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Cross-dialect regression-matrix sweep (Plan-Doc
 * `quality-coverage-expansion-plan.md` §5.2).
 *
 * For every (workstream × dialect × kind) cell in the candidate
 * set, the sweep classifies the cell as one of:
 *
 * - **PINNED** — fixture pair on classpath; execute via
 *   [MatrixSweepRunner] and assert exit code.
 * - **CARVE_OUT** — registered in `fixtures/carve-outs.yaml` with a
 *   `reason` + `planRef`; skipped from execution but reported.
 * - **MATRIX_GAP** — neither pinned nor a carve-out; the sweep
 *   fails fast with a diagnostic naming the missing cell so the
 *   workstream introducing it is forced to register either a
 *   pinning or a justified carve-out.
 *
 * The sweep runs as part of the default `:test` pass for this
 * module — no `-Pintegration` or `perf` tag — so PR runs catch
 * coverage gaps the moment a new workstream lands.
 */
class MatrixSweepTest : FunSpec({

    val sweepRunner = MatrixSweepRunner()
    val registry = CarveOutRegistry.load()
    val candidates = MatrixCell.candidates(MatrixWorkstreams.ALL)

    test("every cell is either PINNED or registered as a CARVE_OUT (no MATRIX_GAP)") {
        val gaps = candidates.mapNotNull { (workstream, dialect, kind) ->
            val cell = MatrixCell(workstream, dialect, kind, expectedExitCode = -1)
            val pinned = MatrixFixtures.isPinned(cell)
            val carved = registry.lookup(cell) != null
            if (!pinned && !carved) cell.id else null
        }
        if (gaps.isNotEmpty()) {
            error(
                "MATRIX_GAP — ${gaps.size} cell(s) have neither a fixture pair nor a carve-out entry:\n" +
                    gaps.joinToString("\n") { "  - $it" } +
                    "\nFix by either pinning fixtures under " +
                    "src/test/resources/fixtures/<workstream>/<dialect>/<kind>/ " +
                    "or by adding an entry to fixtures/carve-outs.yaml with a reason + planRef."
            )
        }
    }

    test("pinned workstreams have fixtures for at least one (dialect, kind) pair") {
        // Belt-and-braces: the gap test above already catches missing
        // cells, but this asserts the headline invariant directly so
        // an accidentally empty pinned-workstream is flagged with a
        // clearer error than a generic MATRIX_GAP listing.
        MatrixWorkstreams.PINNED.forEach { workstream ->
            val hasAnyFixture = MatrixCell.ALL_DIALECTS.any { dialect ->
                MatrixCell.ALL_KINDS.any { kind ->
                    MatrixFixtures.isPinned(MatrixCell(workstream, dialect, kind, -1))
                }
            }
            if (!hasAnyFixture) {
                error("PINNED workstream '$workstream' has zero fixture pairs on classpath.")
            }
        }
    }

    test("carve-out entries reference workstreams from the canonical catalogue") {
        val unknown = registry.all
            .map { it.workstream }
            .filter { it !in MatrixWorkstreams.ALL }
            .distinct()
        if (unknown.isNotEmpty()) {
            error(
                "Carve-out registry references unknown workstreams: ${unknown.joinToString(", ")}. " +
                    "Either add the workstream to MatrixWorkstreams.ALL or remove the carve-out entry."
            )
        }
    }

    test("permanent carve-outs carry a non-empty ownerTests reference") {
        // B-Vervollst closing rule (plan-doc §6): every permanent
        // carve-out must point at the test module(s) that actually
        // cover the workstream. The parser already enforces this on
        // load; this test surfaces the invariant explicitly so a
        // future regression in the YAML schema lands here, not
        // hidden in an opaque parser failure.
        val violations = registry.all
            .filter { it.permanent && it.ownerTests.isEmpty() }
            .map { it.workstream }
        if (violations.isNotEmpty()) {
            error(
                "Permanent carve-outs without ownerTests: ${violations.joinToString(", ")}. " +
                    "Either supply ownerTests (test-module paths) or flip permanent: false."
            )
        }
    }

    test("ownerTests paths resolve to existing files in the repository tree") {
        // Closing invariant: a permanent carve-out's ownerTests
        // entries must point at real test files. Otherwise a typo or
        // a rename leaves the carve-out as a dangling promise and
        // the coverage claim drifts silently. We walk up from the
        // forked test JVM's working dir to find the repo root (the
        // directory containing settings.gradle.kts) and resolve each
        // path against it. Missing files surface as a single batch
        // error so the operator sees the full list to fix at once.
        val repoRoot = findRepoRoot()
        val missing = registry.all.flatMap { entry ->
            entry.ownerTests.mapNotNull { path ->
                val resolved = repoRoot.resolve(path)
                if (java.nio.file.Files.exists(resolved)) null
                else "${entry.workstream}: $path"
            }
        }
        if (missing.isNotEmpty()) {
            error(
                "Carve-out ownerTests reference non-existent files:\n" +
                    missing.joinToString("\n") { "  - $it" } +
                    "\nResolve these paths against $repoRoot or update carve-outs.yaml " +
                    "to point at the real test file. Run `find . -name '<expected-name>'` " +
                    "from the repo root to locate the correct path."
            )
        }
    }

    // ── Executable cells ────────────────────────────────────────────
    //
    // Each pinned (workstream, dialect, kind) triple becomes one
    // generated Kotest test. The fixture catalogue dictates the
    // expected exit code via a per-cell mapping; cells without an
    // explicit override default to 0 for POSITIVE and 8 for BLOCKER
    // (the MIGRATION_BLOCKED exit code per CLI-spec §6.1).

    candidates.forEach { (workstream, dialect, kind) ->
        val candidate = MatrixCell(
            workstream = workstream,
            dialect = dialect,
            kind = kind,
            expectedExitCode = defaultExitCodeFor(kind),
        )
        // Skip cells without a fixture pair on classpath: they are
        // either fully covered by carve-outs (the gap test above
        // would have failed otherwise) or are wildcard-carved.
        if (!MatrixFixtures.isPinned(candidate)) return@forEach
        // Pinned-but-carved cells (e.g. D.3 POSITIVE on MySQL/SQLite
        // where MV is unsupported) are skipped from execution but
        // still appear in the carve-out registry so a B-Vervollst
        // promotion will surface them. The gap test classified them
        // as carve-outs already.
        if (registry.lookup(candidate) != null) return@forEach

        test("[${candidate.id}] file-mode migrate produces exit ${candidate.expectedExitCode}") {
            val outcome = sweepRunner.executeCell(candidate)
            // Surface the cell id, stdout and stderr in the assertion
            // message so a failing cell is easy to diagnose from CI
            // logs without needing to re-run with --info.
            withClueCell(candidate, outcome) {
                outcome.exitCode shouldBe candidate.expectedExitCode
            }
        }
    }
})

private inline fun withClueCell(cell: MatrixCell, outcome: MatrixSweepRunner.Outcome, block: () -> Unit) {
    try {
        block()
    } catch (t: AssertionError) {
        throw AssertionError(
            "matrix-cell=${cell.id} expected=${cell.expectedExitCode} actual=${outcome.exitCode}\n" +
                "stdout:\n${outcome.stdout}\nstderr:\n${outcome.stderr}\n${t.message}",
            t,
        )
    }
}

private fun defaultExitCodeFor(kind: MatrixCell.Kind): Int = when (kind) {
    MatrixCell.Kind.POSITIVE -> 0
    MatrixCell.Kind.BLOCKER -> 8
}

/**
 * Walk up from the test JVM's working directory until a
 * `settings.gradle.kts` is found. Gradle's default working dir for
 * `test` is the project dir, so the walk-up terminates at the repo
 * root after one or two iterations.
 */
private fun findRepoRoot(): java.nio.file.Path {
    var current: java.nio.file.Path? = java.nio.file.Paths.get("").toAbsolutePath()
    while (current != null) {
        if (java.nio.file.Files.exists(current.resolve("settings.gradle.kts"))) {
            return current
        }
        current = current.parent
    }
    error("Could not locate repo root (no settings.gradle.kts on the walk-up).")
}
