package dev.dmigrate.test.matrix

import dev.dmigrate.driver.DatabaseDialect
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Loads `src/test/resources/fixtures/carve-outs.yaml` and exposes a
 * lookup for whether a given [MatrixCell] is registered as a
 * carve-out (cell intentionally not pinned in this slice, with a
 * documented reason).
 *
 * Schema:
 *
 * ```yaml
 * carve_outs:
 *   - workstream: F.4
 *     dialect: postgresql
 *     kind: positive
 *     reason: rename-dependency-projection covered by F.4-renderer-blocker-bridge-tests
 *     planRef: docs/planning/done-archive/ImpPlan-0.9.7-F.4-renderer-blocker-bridge.md
 *   - workstream: D.3
 *     dialect: sqlite
 *     kind: positive
 *     reason: SQLite has no MATERIALIZED VIEW; blocker cell pins the contract
 *     planRef: docs/planning/done-archive/diffresult-migration-plan-2.md §8 D.3b
 * ```
 *
 * Every carve-out entry must carry a `reason` and a `planRef` so a
 * future reviewer can decide whether the carve-out should be promoted
 * to a pinned cell. Missing fields surface as a hard failure during
 * registry load — silent carve-outs are not allowed.
 *
 * Plan-Doc: `docs/planning/done-archive/quality-coverage-expansion-plan.md`
 * §5.2 (carve-out-registry-mechanik).
 */
internal class CarveOutRegistry private constructor(
    private val entries: List<Entry>,
) {

    /** All carve-out entries, for diagnostics + README generation. */
    val all: List<Entry> = entries

    fun isRegistered(cell: MatrixCell): Boolean = lookup(cell) != null

    /** Returns the carve-out entry for the cell, or `null` if not registered. */
    fun lookup(cell: MatrixCell): Entry? =
        entries.firstOrNull { it.matches(cell) }

    /**
     * Carve-out entry. `dialect` and `kind` carry `null` for wildcard
     * matches — a single entry can cover all dialects, all kinds, or
     * the entire cross-product for a workstream. Wildcards keep the
     * carve-out YAML manageable when 17 unpinned workstreams each
     * contribute 6 cells.
     *
     * **Permanent vs provisional** — Sub-Slice B-Vervollständigung
     * (plan-doc §6) requires every carve-out to declare whether the
     * cells are permanently outside the matrix surface
     * (`permanent: true` + non-empty [ownerTests] pointing to where
     * the actual coverage lives) or provisional follow-up work
     * (`permanent: false`, deferred to a later slice). The sweep
     * test rejects permanent entries without `ownerTests` so a
     * carve-out cannot quietly become a coverage hole.
     */
    data class Entry(
        val workstream: String,
        /** `null` matches every dialect; non-null restricts to one. */
        val dialect: DatabaseDialect?,
        /** `null` matches every kind; non-null restricts to one. */
        val kind: MatrixCell.Kind?,
        val reason: String,
        val planRef: String,
        /**
         * `true` — the cell is intentionally outside the matrix
         * surface and the listed [ownerTests] are the authoritative
         * coverage. `false` — provisional carve-out, follow-up
         * pinning is expected in a later slice. Defaults to `false`
         * for backwards compatibility with the pre-B-Vervollst
         * YAML format.
         */
        val permanent: Boolean,
        /**
         * Test-module paths that cover the workstream when
         * `permanent = true`. Required to be non-empty for permanent
         * entries; empty list for provisional carve-outs. The paths
         * are informational (no auto-link verification today) but
         * must reference real source locations so a future maintainer
         * can trace from the carve-out to the coverage.
         */
        val ownerTests: List<String>,
    ) {
        fun matches(cell: MatrixCell): Boolean =
            workstream == cell.workstream &&
                (dialect == null || dialect == cell.dialect) &&
                (kind == null || kind == cell.kind)
    }

    companion object {
        private const val RESOURCE = "/fixtures/carve-outs.yaml"

        fun load(): CarveOutRegistry {
            val stream = CarveOutRegistry::class.java.getResourceAsStream(RESOURCE)
                ?: error("Missing carve-out registry at classpath:$RESOURCE")
            val yaml = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val raw = Load(LoadSettings.builder().build()).loadFromString(yaml)
            return parse(raw)
        }

        @Suppress("UNCHECKED_CAST")
        internal fun parse(raw: Any?): CarveOutRegistry {
            requireNotNull(raw) { "carve-outs.yaml must contain a `carve_outs` map at the top level" }
            val root = raw as? Map<String, Any?>
                ?: error("carve-outs.yaml top level must be a map; got ${raw::class.simpleName}")
            val list = root["carve_outs"] as? List<Map<String, Any?>>
                ?: error("carve-outs.yaml must declare a `carve_outs` list")
            val entries = list.mapIndexed { idx, item ->
                parseEntry(idx, item)
            }
            return CarveOutRegistry(entries)
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseEntry(idx: Int, item: Map<String, Any?>): Entry {
            val workstream = requireString(item, "workstream", idx)
            val dialectSlug = requireString(item, "dialect", idx)
            val kindSlug = requireString(item, "kind", idx)
            val reason = requireString(item, "reason", idx)
            val planRef = requireString(item, "planRef", idx)
            val permanent = item["permanent"] as? Boolean ?: false
            val ownerTestsRaw = item["ownerTests"] as? List<Any?> ?: emptyList()
            val ownerTests = ownerTestsRaw.map {
                val s = it?.toString()?.trim().orEmpty()
                require(s.isNotEmpty()) {
                    "carve-outs.yaml entry #$idx: ownerTests entries must be non-blank strings"
                }
                s
            }
            if (permanent) {
                require(ownerTests.isNotEmpty()) {
                    "carve-outs.yaml entry #$idx (workstream=$workstream): permanent=true requires a non-empty ownerTests list."
                }
            }
            val dialect = if (dialectSlug == WILDCARD) null else {
                DatabaseDialect.values().firstOrNull { it.name.lowercase() == dialectSlug.lowercase() }
                    ?: error("carve-outs.yaml entry #$idx: unknown dialect '$dialectSlug'")
            }
            val kind = if (kindSlug == WILDCARD) null else {
                MatrixCell.Kind.values().firstOrNull { it.slug == kindSlug }
                    ?: error("carve-outs.yaml entry #$idx: unknown kind '$kindSlug'")
            }
            return Entry(
                workstream = workstream,
                dialect = dialect,
                kind = kind,
                reason = reason,
                planRef = planRef,
                permanent = permanent,
                ownerTests = ownerTests,
            )
        }

        private const val WILDCARD = "*"

        private fun requireString(item: Map<String, Any?>, key: String, idx: Int): String {
            val v = item[key]
                ?: error("carve-outs.yaml entry #$idx: missing required field '$key'")
            val s = v.toString().trim()
            require(s.isNotEmpty()) {
                "carve-outs.yaml entry #$idx: field '$key' must not be blank"
            }
            return s
        }
    }
}
