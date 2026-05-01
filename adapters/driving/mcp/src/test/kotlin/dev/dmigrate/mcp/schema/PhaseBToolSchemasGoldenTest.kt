package dev.dmigrate.mcp.schema

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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
                    "Run with -DUPDATE_GOLDEN=true (or UPDATE_GOLDEN=true env) " +
                    "to create it. Actual content:\n\n$actual",
            )
        }
        actual shouldBe expected
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
    // Gradle's default test workingDir is the module project dir
    // (`adapters/driving/mcp`), so a path relative to that points
    // straight at this module's test resources. Only used under
    // UPDATE_GOLDEN=true — never on CI.
    val file = java.nio.file.Paths.get("src/test/resources$GOLDEN_RESOURCE").toAbsolutePath()
    java.nio.file.Files.createDirectories(file.parent)
    java.nio.file.Files.writeString(file, content)
}
