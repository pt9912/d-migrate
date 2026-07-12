package dev.dmigrate.cli.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class AuditSettingsResolverTest : FunSpec({

    fun writeConfig(content: String): Path {
        val file = Files.createTempDirectory("audit-cfg").resolve(".d-migrate.yaml")
        Files.writeString(file, content)
        return file
    }

    fun resolver(configFile: Path? = null, default: Path = Paths.get("/nonexistent/.d-migrate.yaml")) =
        AuditSettingsResolver(configPathFromCli = configFile, envLookup = { null }, defaultConfigPath = default)

    test("ohne Config: opt-in aus, Default-Datei") {
        val settings = resolver().resolve()
        settings.enabled shouldBe false
        settings.file shouldBe Paths.get(".d-migrate/audit.log")
    }

    test("liest enabled + file aus logging.audit") {
        val cfg = writeConfig(
            """
            logging:
              audit:
                enabled: true
                file: "logs/my-audit.jsonl"
            """.trimIndent(),
        )
        val settings = resolver(cfg).resolve()
        settings.enabled shouldBe true
        settings.file shouldBe Paths.get("logs/my-audit.jsonl")
    }

    test("logging ohne audit-Section: Defaults") {
        val cfg = writeConfig(
            """
            logging:
              level: info
            """.trimIndent(),
        )
        val settings = resolver(cfg).resolve()
        settings.enabled shouldBe false
        settings.file shouldBe Paths.get(".d-migrate/audit.log")
    }

    test("enabled ohne file: Default-Datei behalten") {
        val cfg = writeConfig(
            """
            logging:
              audit:
                enabled: true
            """.trimIndent(),
        )
        val settings = resolver(cfg).resolve()
        settings.enabled shouldBe true
        settings.file shouldBe Paths.get(".d-migrate/audit.log")
    }

    test("logging.audit kein Mapping: Fehler") {
        val cfg = writeConfig(
            """
            logging:
              audit: "nope"
            """.trimIndent(),
        )
        val ex = shouldThrow<ConfigResolveException> { resolver(cfg).resolve() }
        ex.message!! shouldContain "logging.audit"
    }

    test("enabled kein Boolean: Fehler") {
        val cfg = writeConfig(
            """
            logging:
              audit:
                enabled: "yes"
            """.trimIndent(),
        )
        val ex = shouldThrow<ConfigResolveException> { resolver(cfg).resolve() }
        ex.message!! shouldContain "must be a boolean"
    }
})
