package dev.dmigrate.test.matrix

/**
 * Classpath loader for the cell's `current.yaml` + `desired.yaml`
 * fixture pair. The fixtures live under
 * `src/test/resources/fixtures/<workstream>/<kind>/`.
 *
 * Missing fixtures surface as [MatrixFixtureMissing] so the sweep
 * can classify the cell as `MATRIX_GAP` instead of producing an
 * opaque `NullPointerException`.
 */
internal object MatrixFixtures {

    data class Pair(val currentYaml: String, val desiredYaml: String)

    fun loadPair(cell: MatrixCell): Pair {
        val base = cell.fixtureBaseResource
        val current = readResource("$base/current.yaml")
            ?: throw MatrixFixtureMissing(cell, "$base/current.yaml")
        val desired = readResource("$base/desired.yaml")
            ?: throw MatrixFixtureMissing(cell, "$base/desired.yaml")
        return Pair(current, desired)
    }

    /**
     * Returns true iff both `current.yaml` and `desired.yaml` are
     * present under the cell's fixture base. Fixtures are shared
     * across dialects (see [MatrixCell.fixtureBaseResource]); a
     * pinned `(workstream, kind)` implies the cell is pinned for
     * every dialect unless a carve-out shadows specific dialects.
     */
    fun isPinned(cell: MatrixCell): Boolean {
        val base = cell.fixtureBaseResource
        return hasResource("$base/current.yaml") && hasResource("$base/desired.yaml")
    }

    private fun readResource(path: String): String? {
        val stream = MatrixFixtures::class.java.getResourceAsStream(path) ?: return null
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun hasResource(path: String): Boolean =
        MatrixFixtures::class.java.getResource(path) != null
}

internal class MatrixFixtureMissing(
    val cell: MatrixCell,
    val resourcePath: String,
) : RuntimeException("Missing matrix fixture for cell=${cell.id} at classpath:$resourcePath")
