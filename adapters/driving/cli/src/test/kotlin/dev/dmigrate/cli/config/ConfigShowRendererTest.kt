package dev.dmigrate.cli.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

class ConfigShowRendererTest : FunSpec({

    val path = Path.of("/work/.d-migrate.yaml")

    fun ok(root: Map<*, *>?, section: String? = null, env: Map<String, String> = emptyMap()): String =
        ConfigShowRenderer.render(root, path, section, env)
            .shouldBeInstanceOf<ConfigShowRenderer.Result.Ok>().text

    val config = linkedMapOf(
        "database" to linkedMapOf(
            "host" to "localhost",
            "port" to 5432,
            "password" to "s3cretPW",
            "url" to "postgresql://app:urlPW@db:5432/prod",
        ),
        "pipeline" to linkedMapOf("parallelism" to 4),
        "ai" to linkedMapOf("apiKey" to "sk-LEAK", "model" to "claude"),
        "connections" to linkedMapOf(
            "prod" to linkedMapOf("credentialRef" to "keychain://pg-prod"),
        ),
    )

    test("sensitive key names are masked to *** at any depth (no cleartext)") {
        val text = ok(config)
        text shouldContain "password: ***"
        text shouldContain "apiKey: ***"
        text shouldContain "credentialRef: ***"
        text shouldNotContain "s3cretPW"
        text shouldNotContain "sk-LEAK"
    }

    test("URL passwords in non-sensitive string values are masked via ConnectionSecretMasker") {
        val text = ok(config)
        text shouldContain "url: postgresql://app:***@db:5432/prod"
        text shouldNotContain "urlPW"
    }

    test("non-sensitive scalars are shown as-is") {
        val text = ok(config)
        text shouldContain "host: localhost"
        text shouldContain "port: 5432"
        text shouldContain "parallelism: 4"
        text shouldContain "model: claude"
    }

    test("\${VAR} values are shown literally (not resolved) unless the key is sensitive") {
        val text = ok(
            linkedMapOf("database" to linkedMapOf("url" to "\${DB_URL}", "password" to "\${DB_PW}")),
        )
        text shouldContain "url: \${DB_URL}"
        text shouldContain "password: ***"
        text shouldNotContain "\${DB_PW}"
    }

    test("--section shows only that top-level section") {
        val text = ok(config, section = "database")
        text shouldContain "host: localhost"
        text shouldNotContain "parallelism"
        text shouldNotContain "model: claude"
    }

    test("unknown --section → UnknownSection with the available sections") {
        val result = ConfigShowRenderer.render(config, path, "nope", emptyMap())
            .shouldBeInstanceOf<ConfigShowRenderer.Result.UnknownSection>()
        result.section shouldBe "nope"
        result.available shouldContainElement "database"
    }

    test("known sections render before unknown ones (file order for the rest)") {
        val text = ok(config)
        // SECTION_ORDER: database … pipeline … ai …; 'connections' is unknown → after.
        text.indexOf("database:") shouldBe text.indexOf("database:")
        (text.indexOf("database:") < text.indexOf("pipeline:")) shouldBe true
        (text.indexOf("ai:") < text.indexOf("connections:")) shouldBe true
    }

    test("null root (no config file) → note, no sections") {
        val text = ok(null)
        text shouldContain "Keine Konfigurationsdatei"
        text shouldContain "/work/.d-migrate.yaml"
    }

    test("null root + --section → UnknownSection") {
        ConfigShowRenderer.render(null, path, "database", emptyMap())
            .shouldBeInstanceOf<ConfigShowRenderer.Result.UnknownSection>()
    }

    test("lists are rendered as - items") {
        val text = ok(linkedMapOf("security" to linkedMapOf("allowedOrigins" to listOf("https://a", "https://b"))))
        text shouldContain "- https://a"
        text shouldContain "- https://b"
    }

    test("D_MIGRATE_* overrides are listed by NAME only (never their values)") {
        val text = ok(
            config,
            env = mapOf("D_MIGRATE_DB_PASSWORD" to "supersecretpw", "D_MIGRATE_CONFIG" to "/etc/dm.yaml", "PATH" to "/bin"),
        )
        text shouldContain "D_MIGRATE_CONFIG, D_MIGRATE_DB_PASSWORD"
        text shouldNotContain "supersecretpw"
        text shouldNotContain "PATH"
    }

    test("no overrides → no override line") {
        ok(config, env = emptyMap()) shouldNotContain "Runtime-Overrides"
    }
})
