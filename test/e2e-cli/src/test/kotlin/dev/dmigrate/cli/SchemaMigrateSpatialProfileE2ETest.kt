package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/**
 * `--spatial-profile` auf dem Migrate-Pfad, gegen die ECHTE CLI und ohne
 * Container: Quelle und Ziel sind Schema-Dateien, `--plan-only` rendert nur.
 *
 * Zwei Dinge waren dort still. Ein Tippfehler im Profilnamen fiel auf den
 * Dialekt-Default zurueck, ohne dass jemand es erfuhr; und `none` blieb
 * wirkungslos, weil kein Renderer ausser SQLite das Profil las — waehrend
 * `schema generate` dieselbe Tabelle mit `E052` blockt.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class SchemaMigrateSpatialProfileE2ETest : FunSpec({

    lateinit var tmp: Path
    lateinit var withGeometry: Path
    lateinit var empty: Path

    beforeSpec {
        tmp = Files.createTempDirectory("dmigrate-e2e-spatial-profile-")
        withGeometry = tmp.resolve("desired.yaml").apply { writeText(SPATIAL_SCHEMA) }
        empty = tmp.resolve("current.yaml").apply { writeText(EMPTY_SCHEMA) }
    }

    afterSpec { tmp.deleteRecursively() }

    fun migrate(vararg extra: String) = runRealCli(
        listOf(
            "schema", "migrate",
            "--source", withGeometry.absolutePathString(),
            "--target", "file:" + empty.absolutePathString(),
            "--plan-only",
        ) + extra,
    )

    test("a typo in the profile name is refused instead of falling back to the default") {
        val run = migrate("--dialect", "postgresql", "--spatial-profile", "postgs")

        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 2
            (run.stdout + run.stderr) shouldContain "Unknown spatial profile 'postgs'"
            // Die Meldung nennt, was erlaubt gewesen waere.
            (run.stdout + run.stderr) shouldContain "postgis"
        }
    }

    test("a profile the dialect does not carry is refused") {
        val run = migrate("--dialect", "oracle", "--spatial-profile", "postgis")

        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 2
            (run.stdout + run.stderr) shouldContain "not allowed for oracle"
        }
    }

    test("profile none blocks a plan that would create geometry, with E052") {
        val run = migrate("--dialect", "oracle", "--spatial-profile", "none")

        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            val output = run.stdout + run.stderr
            // MIGRATION_BLOCKED — eine bewusste Schutzblockade, kein Aufruffehler.
            run.exitCode shouldBe 8
            output shouldContain "E052"
            output shouldContain "places"
        }
    }

    test("the same plan under the dialect default renders") {
        val run = migrate("--dialect", "oracle")

        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 0
            run.stdout shouldContain "\"blockers\": []"
        }
    }
})

private val SPATIAL_SCHEMA = """
    schema_format: "1.0"
    name: "spatial-profile-e2e"
    version: "1.0.0"

    tables:
      places:
        columns:
          id:
            type: identifier
            auto_increment: true
          geom:
            type: geometry
        primary_key: [id]
""".trimIndent()

private val EMPTY_SCHEMA = """
    schema_format: "1.0"
    name: "spatial-profile-e2e"
    version: "1.0.0"

    tables: {}
""".trimIndent()
