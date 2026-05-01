package dev.dmigrate.mcp.schema

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec

/**
 * Golden-file pin for the Phase B tool-schema set per
 * `ImpPlan-0.9.6-B.md` §6.10. Any unintentional drift in
 * [PhaseBToolSchemas] fails this test; intentional changes go through
 * `UPDATE_GOLDEN=true` (regenerates the file) and a code review of the
 * resulting diff.
 */
class PhaseBToolSchemasGoldenTest : FunSpec({

    test("serialised schemas match the pinned golden file") {
        val actual = renderGolden()
        val expected = readGolden()
        val update = System.getenv("UPDATE_GOLDEN") == "true" ||
            System.getProperty("UPDATE_GOLDEN") == "true"
        if (update) {
            // Write the generated golden to the source tree.
            // `readGolden()` uses the classpath which is fixed at
            // process start, so we don't try to read back here — the
            // next normal test run picks up the new file from
            // src/test/resources and verifies it.
            writeGolden(actual)
            return@test
        }
        if (expected == null) {
            error(
                "Golden file '$GOLDEN_RESOURCE' missing. " +
                    "Regenerate it with UPDATE_GOLDEN=true — see " +
                    "src/test/resources/golden/README.md for the recipe. " +
                    "Actual content:\n\n$actual",
            )
        }
        if (actual != expected) {
            error(
                "Golden drift detected. The serialised schemas differ from " +
                    "the pinned file at src/test/resources$GOLDEN_RESOURCE. " +
                    "If this drift is intentional, regenerate with " +
                    "UPDATE_GOLDEN=true (see golden/README.md) and commit " +
                    "the new file alongside your schema change. " +
                    "Otherwise, something was renamed or restructured by " +
                    "accident — revert the offending edit.",
            )
        }
    }
})

private const val GOLDEN_RESOURCE = "/golden/phase-b-tool-schemas.json"

private val GSON = GsonBuilder()
    .setPrettyPrinting()
    .disableHtmlEscaping()
    .create()

private fun renderGolden(): String {
    // Iterate in the deterministic tool order PhaseBToolSchemas
    // exposes — alphabetical — so the file is reviewable as a diff.
    val payload = LinkedHashMap<String, Any>()
    for (name in PhaseBToolSchemas.toolNames()) {
        val pair = PhaseBToolSchemas.forTool(name)!!
        payload[name] = mapOf(
            "inputSchema" to pair.inputSchema,
            "outputSchema" to pair.outputSchema,
        )
    }
    return GSON.toJson(payload) + "\n"
}

private fun readGolden(): String? =
    PhaseBToolSchemasGoldenTest::class.java.getResource(GOLDEN_RESOURCE)?.readText()

private fun writeGolden(content: String) {
    // Resolve the source-tree path defensively: most build setups put
    // the test workingDir at the module root (`adapters/driving/mcp`),
    // but a future global `tasks.withType<Test> { workingDir = rootProject.projectDir }`
    // would silently relocate the write. We probe both candidates and
    // pick the first where `src/test/kotlin` exists (proof we're at
    // the module root) or where the module path is reachable.
    val candidates = listOf(
        java.nio.file.Paths.get("src/test/resources$GOLDEN_RESOURCE"),
        java.nio.file.Paths.get("adapters/driving/mcp/src/test/resources$GOLDEN_RESOURCE"),
    )
    val target = candidates.firstOrNull { java.nio.file.Files.isDirectory(it.parent.parent.parent) }
        ?: error(
            "cannot resolve golden file destination from cwd=${System.getProperty("user.dir")}; " +
                "tried ${candidates.joinToString { it.toAbsolutePath().toString() }}",
        )
    val absolute = target.toAbsolutePath()
    java.nio.file.Files.createDirectories(absolute.parent)
    java.nio.file.Files.writeString(absolute, content)
}
