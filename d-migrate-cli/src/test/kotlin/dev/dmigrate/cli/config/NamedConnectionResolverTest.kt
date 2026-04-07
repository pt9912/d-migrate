package dev.dmigrate.cli.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifiziert das §6.14-Auflösungsverhalten des [NamedConnectionResolver]
 * inkl. Fehlermeldungen aus §6.14.3.
 */
class NamedConnectionResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-test-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    // ─── URL-Direktnutzung ────────────────────────────────────────

    test("URL with :// is returned unchanged — config file is not even consulted") {
        // Bewusst ein nicht-existenter Pfad — der Resolver darf ihn gar nicht
        // erst öffnen, wenn die Source eine vollständige URL ist.
        val resolver = NamedConnectionResolver(
            configPathFromCli = Path.of("/does/not/exist.yaml"),
        )
        resolver.resolve("postgresql://u:p@host/db") shouldBe "postgresql://u:p@host/db"
        resolver.resolve("sqlite:///tmp/x.db") shouldBe "sqlite:///tmp/x.db"
    }

    // ─── Connection-Name-Lookup ───────────────────────────────────

    test("connection name resolves via database.connections map") {
        val cfg = tempConfig(
            """
            database:
              connections:
                local_pg: "postgresql://dev:dev@localhost:5432/myapp"
                local_sqlite: "sqlite:///tmp/test.db"
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(configPathFromCli = cfg)
        resolver.resolve("local_pg") shouldBe "postgresql://dev:dev@localhost:5432/myapp"
        resolver.resolve("local_sqlite") shouldBe "sqlite:///tmp/test.db"
    }

    test("§6.14: default_source is read but ignored for data export — no error") {
        val cfg = tempConfig(
            """
            database:
              default_source: local_pg
              connections:
                local_pg: "postgresql://dev:dev@localhost:5432/myapp"
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(configPathFromCli = cfg)
        // default_source darf hier nicht stören; --source local_pg wird normal aufgelöst
        resolver.resolve("local_pg") shouldBe "postgresql://dev:dev@localhost:5432/myapp"
    }

    // ─── Config-Pfad-Priorität CLI > ENV > Default ────────────────

    test("config-path priority: CLI flag wins over ENV variable") {
        val cliCfg = tempConfig("database:\n  connections:\n    n: \"sqlite:///cli.db\"\n")
        val envCfg = tempConfig("database:\n  connections:\n    n: \"sqlite:///env.db\"\n")
        val resolver = NamedConnectionResolver(
            configPathFromCli = cliCfg,
            envLookup = { name -> if (name == "D_MIGRATE_CONFIG") envCfg.toString() else null },
        )
        resolver.resolve("n") shouldBe "sqlite:///cli.db"
    }

    test("config-path priority: ENV variable used when CLI flag is absent") {
        val envCfg = tempConfig("database:\n  connections:\n    n: \"sqlite:///env.db\"\n")
        val resolver = NamedConnectionResolver(
            configPathFromCli = null,
            envLookup = { name -> if (name == "D_MIGRATE_CONFIG") envCfg.toString() else null },
        )
        resolver.resolve("n") shouldBe "sqlite:///env.db"
    }

    test("config-path priority: default ./.d-migrate.yaml when neither CLI nor ENV set") {
        val defaultCfg = tempConfig("database:\n  connections:\n    n: \"sqlite:///default.db\"\n")
        val resolver = NamedConnectionResolver(
            configPathFromCli = null,
            envLookup = { null },
            defaultConfigPath = defaultCfg,
        )
        resolver.resolve("n") shouldBe "sqlite:///default.db"
    }

    // ─── Fehlermeldungen aus §6.14.3 ──────────────────────────────

    test("§6.14.3: --config <path>, file missing → 'Config file not found'") {
        val resolver = NamedConnectionResolver(
            configPathFromCli = Path.of("/does/not/exist.yaml"),
        )
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("n") }
        ex.message!! shouldContain "Config file not found:"
        ex.message!! shouldContain "/does/not/exist.yaml"
    }

    test("§6.14.3: D_MIGRATE_CONFIG set, file missing → 'D_MIGRATE_CONFIG points to non-existent file'") {
        val resolver = NamedConnectionResolver(
            configPathFromCli = null,
            envLookup = { name -> if (name == "D_MIGRATE_CONFIG") "/nope.yaml" else null },
        )
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("n") }
        ex.message!! shouldContain "D_MIGRATE_CONFIG points to non-existent file:"
        ex.message!! shouldContain "/nope.yaml"
    }

    test("§6.14.3: no CLI, no ENV, default missing → 'looks like a connection name'") {
        val nonexistentDefault = Path.of("/tmp/d-migrate-test-no-such-file.yaml")
        Files.deleteIfExists(nonexistentDefault)
        val resolver = NamedConnectionResolver(
            configPathFromCli = null,
            envLookup = { null },
            defaultConfigPath = nonexistentDefault,
        )
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("local_pg") }
        ex.message!! shouldContain "'--source local_pg' looks like a connection name"
        ex.message!! shouldContain "Use --config <path>, set D_MIGRATE_CONFIG, or pass a full URL"
    }

    test("§6.14.3: name not in database.connections → 'is not defined in <path> under database.connections'") {
        val cfg = tempConfig(
            """
            database:
              connections:
                local_pg: "postgresql://dev@localhost/db"
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(configPathFromCli = cfg)
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("staging") }
        ex.message!! shouldContain "Connection name 'staging' is not defined in"
        ex.message!! shouldContain "database.connections"
    }

    test("§6.14.3: missing database section → 'is not defined ... database.connections'") {
        val cfg = tempConfig("# empty config\nfoo: bar\n")
        val resolver = NamedConnectionResolver(configPathFromCli = cfg)
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("local_pg") }
        ex.message!! shouldContain "is not defined in"
        ex.message!! shouldContain "database.connections"
    }

    test("§6.14.3: broken YAML → 'Failed to parse'") {
        val cfg = tempConfig("database:\n  connections:\n    bad: {unclosed\n")
        val resolver = NamedConnectionResolver(configPathFromCli = cfg)
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("bad") }
        ex.message!! shouldContain "Failed to parse"
    }

    test("§6.14.3: connection value is not a string → clear error") {
        val cfg = tempConfig(
            """
            database:
              connections:
                local_pg:
                  host: localhost
                  port: 5432
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(configPathFromCli = cfg)
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("local_pg") }
        ex.message!! shouldContain "must be a string"
    }

    // ─── ${ENV_VAR}-Substitution ─────────────────────────────────

    test("\${ENV_VAR} is substituted from envLookup") {
        val d = "${'$'}"  // literal '$' for use inside raw strings
        val cfg = tempConfig(
            """
            database:
              connections:
                prod: "postgresql://app:${d}{DB_PROD_PW}@prod.example.com/myapp"
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(
            configPathFromCli = cfg,
            envLookup = { name -> if (name == "DB_PROD_PW") "secret123" else null },
        )
        resolver.resolve("prod") shouldBe "postgresql://app:secret123@prod.example.com/myapp"
    }

    test("§6.14.3: missing \${ENV_VAR} → error attributed to connection name") {
        val d = "${'$'}"
        val cfg = tempConfig(
            """
            database:
              connections:
                prod: "postgresql://app:${d}{DB_PROD_PW}@prod.example.com/myapp"
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(
            configPathFromCli = cfg,
            envLookup = { null },
        )
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("prod") }
        ex.message!! shouldContain "Environment variable 'DB_PROD_PW' (referenced by connection 'prod') is not set."
    }

    test("F13: ENV values are substituted literally — no auto URL-encoding") {
        val d = "${'$'}"
        val cfg = tempConfig(
            """
            database:
              connections:
                prod: "postgresql://app:${d}{DB_PROD_PW}@host/db"
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(
            configPathFromCli = cfg,
            // Pre-encoded password — must NOT be double-encoded
            envLookup = { name -> if (name == "DB_PROD_PW") "p%40ss%3Aword" else null },
        )
        resolver.resolve("prod") shouldBe "postgresql://app:p%40ss%3Aword@host/db"
    }

    test("\$\${VAR} escape stays as literal \${VAR}") {
        val d = "${'$'}"
        val cfg = tempConfig(
            """
            database:
              connections:
                weird: "sqlite:///tmp/${d}${d}{NOT_A_VAR}.db"
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(
            configPathFromCli = cfg,
            envLookup = { error("must not be called for an escaped variable") },
        )
        resolver.resolve("weird") shouldBe "sqlite:///tmp/\${NOT_A_VAR}.db"
    }

    test("multiple \${ENV_VAR} substitutions in the same value") {
        val d = "${'$'}"
        val cfg = tempConfig(
            """
            database:
              connections:
                prod: "postgresql://${d}{DB_USER}:${d}{DB_PW}@${d}{DB_HOST}/${d}{DB_NAME}"
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(
            configPathFromCli = cfg,
            envLookup = { name ->
                when (name) {
                    "DB_USER" -> "alice"
                    "DB_PW" -> "secret"
                    "DB_HOST" -> "db.internal"
                    "DB_NAME" -> "appdb"
                    else -> null
                }
            },
        )
        resolver.resolve("prod") shouldBe "postgresql://alice:secret@db.internal/appdb"
    }

    // ─── Edge cases ───────────────────────────────────────────────

    test("blank source throws IllegalArgumentException") {
        val resolver = NamedConnectionResolver()
        shouldThrow<IllegalArgumentException> { resolver.resolve("") }
        shouldThrow<IllegalArgumentException> { resolver.resolve("   ") }
    }

    test("URL with :// bypasses lookup even when configPathFromCli is missing file") {
        // Soll auch dann durchgehen, wenn der Default-Pfad nicht existiert
        val resolver = NamedConnectionResolver(
            configPathFromCli = null,
            envLookup = { null },
            defaultConfigPath = Path.of("/no/such/file.yaml"),
        )
        resolver.resolve("postgresql://u:p@h/d") shouldBe "postgresql://u:p@h/d"
    }

    // ─── Structural errors in the config file ──────────────────────

    test("§6.14.3: top-level YAML is a scalar (not a mapping) → 'must be a mapping'") {
        // Scalars at top level parse to a String, not a Map — the resolver
        // must reject this with a clear error.
        val cfg = tempConfig("\"just a string\"\n")
        val resolver = NamedConnectionResolver(configPathFromCli = cfg)
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("local_pg") }
        ex.message!! shouldContain "top-level YAML must be a mapping"
    }

    test("§6.14.3: top-level YAML is a sequence (not a mapping) → 'must be a mapping'") {
        val cfg = tempConfig("- foo\n- bar\n")
        val resolver = NamedConnectionResolver(configPathFromCli = cfg)
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("local_pg") }
        ex.message!! shouldContain "top-level YAML must be a mapping"
    }

    test("§6.14.3: database.connections is a scalar (not a mapping) → clear error") {
        val cfg = tempConfig(
            """
            database:
              connections: "not a map"
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(configPathFromCli = cfg)
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("local_pg") }
        ex.message!! shouldContain "is not defined in"
        ex.message!! shouldContain "database.connections"
    }

    test("§6.14.3: database.connections is a sequence (not a mapping) → clear error") {
        val cfg = tempConfig(
            """
            database:
              connections:
                - foo
                - bar
            """.trimIndent()
        )
        val resolver = NamedConnectionResolver(configPathFromCli = cfg)
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve("local_pg") }
        ex.message!! shouldContain "database.connections"
    }
})
