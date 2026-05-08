package dev.dmigrate.connection

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class PhaseCConnectionConfigParserTest : FunSpec({

    fun tempYaml(content: String): Path {
        val path = Files.createTempFile("phase-c-conn-", ".yaml")
        Files.writeString(path, content)
        return path
    }

    test("parseConnections returns the raw URL templates including unexpanded \${VAR}") {
        // Plan-D §8.2 acceptance: the neutral parser MUST NOT
        // expand env-vars; the CLI-adapter's NamedConnectionResolver
        // owns ENV-substitution. Pin that ${VAR} stays verbatim.
        val yaml = """
            database:
              connections:
                pg-prod: "jdbc:postgresql://localhost:5432/db?password=${'$'}{PG_PASS}"
                sqlite-dev: "jdbc:sqlite:./dev.db"
        """.trimIndent()
        val parsed = PhaseCConnectionConfigParser.parseConnections(tempYaml(yaml))
        parsed shouldBe mapOf(
            "pg-prod" to "jdbc:postgresql://localhost:5432/db?password=\${PG_PASS}",
            "sqlite-dev" to "jdbc:sqlite:./dev.db",
        )
    }

    test("parseConnections silently drops Phase-D map-form entries (let YamlConnectionReferenceLoader handle them)") {
        // A YAML mid-migration may carry both shapes side-by-side.
        // The Phase-C parser only surfaces bare-URL entries.
        val yaml = """
            database:
              connections:
                legacy: "jdbc:sqlite:./dev.db"
                pg-d:
                  displayName: PG Phase-D
                  dialectId: postgresql
                  sensitivity: PRODUCTION
        """.trimIndent()
        val parsed = PhaseCConnectionConfigParser.parseConnections(tempYaml(yaml))
        parsed shouldBe mapOf("legacy" to "jdbc:sqlite:./dev.db")
    }

    test("parseConnections returns empty for missing database / connections blocks") {
        val withoutDatabase = tempYaml("""
            other:
              key: value
        """.trimIndent())
        PhaseCConnectionConfigParser.parseConnections(withoutDatabase) shouldBe emptyMap()

        val withoutConnections = tempYaml("""
            database:
              default_source: pg
        """.trimIndent())
        PhaseCConnectionConfigParser.parseConnections(withoutConnections) shouldBe emptyMap()
    }

    test("parseConnections returns empty when the file does not exist") {
        val absent = Path.of("/tmp/phase-c-absent-${'$'}{System.nanoTime()}.yaml")
        PhaseCConnectionConfigParser.parseConnections(absent) shouldBe emptyMap()
    }

    test("parseDefault returns the default value when set") {
        val yaml = """
            database:
              default_source: pg
              default_target: sqlite
              connections:
                pg: "jdbc:postgresql://x/y"
        """.trimIndent()
        val path = tempYaml(yaml)
        PhaseCConnectionConfigParser.parseDefault(path, "default_source") shouldBe "pg"
        PhaseCConnectionConfigParser.parseDefault(path, "default_target") shouldBe "sqlite"
        PhaseCConnectionConfigParser.parseDefault(path, "default_unknown") shouldBe null
    }

    test("parseDefault returns null when database block is absent") {
        val yaml = "other: value"
        PhaseCConnectionConfigParser.parseDefault(tempYaml(yaml), "default_source") shouldBe null
    }

    test("parseDefault throws when the value is not a string") {
        val yaml = """
            database:
              default_source: 12345
        """.trimIndent()
        shouldThrow<PhaseCConnectionConfigException> {
            PhaseCConnectionConfigParser.parseDefault(tempYaml(yaml), "default_source")
        }
    }

    test("parseConnections throws on top-level non-mapping YAML") {
        val yaml = "- not\n- a\n- mapping"
        shouldThrow<PhaseCConnectionConfigException> {
            PhaseCConnectionConfigParser.parseConnections(tempYaml(yaml))
        }
    }

    test("parseConnections throws on malformed YAML") {
        val yaml = "this is\n  : not: balanced:"
        shouldThrow<PhaseCConnectionConfigException> {
            PhaseCConnectionConfigParser.parseConnections(tempYaml(yaml))
        }
    }
})
