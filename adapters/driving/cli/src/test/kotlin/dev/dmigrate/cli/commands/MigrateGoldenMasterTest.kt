package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.format.yaml.YamlSchemaCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Phase F.1 — golden-master DDL tests for the migrate pipeline.
 *
 * Each scenario has a fixture pair under
 * `fixtures/migrate/<scenario>/{current,desired}.yaml` plus per-
 * dialect golden files
 * `fixtures/migrate/<scenario>/<dialect>.up.sql`.
 *
 * Down-rendering is golden-tested only for scenarios whose Up
 * direction is fully reversible (golden file
 * `<dialect>.down.sql`). NOT_REVERSIBLE scenarios such as
 * `drop-column` are pinned only on the Up side; the runner enforces
 * the ROLLBACK_NOT_POSSIBLE blocker downstream.
 *
 * To regenerate golden files (e.g. after a deliberate renderer
 * change), set the system property `update.goldens=true` and run
 * the test once: it will rewrite the golden file from current
 * actual output instead of asserting equality. This pattern keeps
 * the diff between an intentional change and a regression review-
 * able in a single PR.
 */
class MigrateGoldenMasterTest : FunSpec({

    val codec = YamlSchemaCodec()
    val planner = DiffPlanner()

    fun loadFixture(scenario: String, file: String): SchemaDefinition =
        codec.read(MigrateGoldenMasterTest::class.java.getResourceAsStream("/fixtures/migrate/$scenario/$file")!!)

    fun loadGolden(scenario: String, file: String): String? =
        MigrateGoldenMasterTest::class.java.getResourceAsStream("/fixtures/migrate/$scenario/$file")
            ?.bufferedReader()?.readText()

    fun renderUp(current: SchemaDefinition, desired: SchemaDefinition, renderer: DiffDdlGenerator): String {
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff)
        val rendered = renderer.generateUp(plan, DdlGenerationOptions())
        return rendered.statements.joinToString("\n") { it.sql } + if (rendered.statements.isNotEmpty()) "\n" else ""
    }

    val scenarios = listOf("add-table", "add-column", "drop-column", "alter-column-type-safe")
    val dialects: List<Pair<String, () -> DiffDdlGenerator>> = listOf(
        "postgresql" to { MigrateRendererRegistry.forDialect(DatabaseDialect.POSTGRESQL)!! },
        "mysql" to { MigrateRendererRegistry.forDialect(DatabaseDialect.MYSQL)!! },
        "sqlite" to { MigrateRendererRegistry.forDialect(DatabaseDialect.SQLITE)!! },
    )

    val updateGoldens = System.getProperty("update.goldens")?.lowercase() == "true"
    val capturedActuals = Paths.get("build/golden-actuals")

    for (scenario in scenarios) {
        for ((dialectName, rendererFactory) in dialects) {
            test("up: $scenario / $dialectName matches golden") {
                val current = loadFixture(scenario, "current.yaml")
                val desired = loadFixture(scenario, "desired.yaml")
                val actual = renderUp(current, desired, rendererFactory())
                val goldenFile = "$dialectName.up.sql"
                val expected = loadGolden(scenario, goldenFile)
                if (updateGoldens || expected == null) {
                    val outDir = capturedActuals.resolve(scenario)
                    Files.createDirectories(outDir)
                    Files.writeString(outDir.resolve(goldenFile), actual)
                    if (expected == null) {
                        // Print to stdout so the build log captures the actual output
                        // when running headlessly (Docker tests).
                        println("=== GOLDEN-CAPTURE: fixtures/migrate/$scenario/$goldenFile ===")
                        println(actual)
                        println("=== END-GOLDEN-CAPTURE ===")
                        error("Missing golden file `fixtures/migrate/$scenario/$goldenFile`")
                    }
                }
                actual shouldBe expected
            }
        }
    }
})
