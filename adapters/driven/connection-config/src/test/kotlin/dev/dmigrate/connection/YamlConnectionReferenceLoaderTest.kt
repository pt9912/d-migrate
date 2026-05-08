package dev.dmigrate.connection

import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.ports.ConnectionReferenceConfigException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class YamlConnectionReferenceLoaderTest : FunSpec({

    val tenant = TenantId("acme")

    fun tempYaml(content: String): Path {
        val path = Files.createTempFile("conn-config-", ".yaml")
        Files.writeString(path, content)
        return path
    }

    test("loads a single Phase-D connection record with all metadata") {
        val yaml = """
            database:
              connections:
                pg-prod:
                  displayName: Production DB
                  dialectId: postgresql
                  sensitivity: PRODUCTION
                  credentialRef: env:PG_PASS
                  providerRef: secrets-manager
                  allowedPrincipalIds:
                    - alice
                    - bob
                  allowedScopes:
                    - dmigrate:data:write
        """.trimIndent()
        val loader = YamlConnectionReferenceLoader(tempYaml(yaml), tenant)
        val refs = loader.loadAll()
        refs.size shouldBe 1
        val ref = refs.single()
        ref.connectionId shouldBe "pg-prod"
        ref.tenantId shouldBe tenant
        ref.displayName shouldBe "Production DB"
        ref.dialectId shouldBe "postgresql"
        ref.sensitivity shouldBe ConnectionSensitivity.PRODUCTION
        ref.credentialRef shouldBe "env:PG_PASS"
        ref.providerRef shouldBe "secrets-manager"
        ref.allowedPrincipalIds shouldBe setOf(PrincipalId("alice"), PrincipalId("bob"))
        ref.allowedScopes shouldBe setOf("dmigrate:data:write")
        ref.resourceUri.kind shouldBe ResourceKind.CONNECTIONS
        ref.resourceUri.id shouldBe "pg-prod"
    }

    test("string-form connection (Phase-C compat) is silently dropped from Phase-D discovery") {
        // Plan-D §3.7: bare URLs MUST NOT materialise into the
        // discovery surface. The loader returns an empty list so
        // CLI flows that still hold bare-URL entries don't pollute
        // `resources/list`.
        val yaml = """
            database:
              connections:
                legacy: "jdbc:postgresql://localhost:5432/db"
        """.trimIndent()
        val loader = YamlConnectionReferenceLoader(tempYaml(yaml), tenant)
        loader.loadAll() shouldBe emptyList()
    }

    test("env-var placeholder in credentialRef is captured verbatim, NOT expanded") {
        // Plan-D §10.10 acceptance: "ENV-Platzhalter wird im
        // Discovery-Pfad nicht expandiert". Pin that the loader
        // never reaches for `System.getenv`.
        val yaml = """
            database:
              connections:
                pg:
                  displayName: PG
                  dialectId: postgresql
                  sensitivity: NON_PRODUCTION
                  credentialRef: env:PG_PASS
        """.trimIndent()
        val loader = YamlConnectionReferenceLoader(tempYaml(yaml), tenant)
        loader.loadAll().single().credentialRef shouldBe "env:PG_PASS"
    }

    test("missing config file returns an empty list (no hard failure)") {
        // Plan-D §10.10: a deployment without a project YAML
        // should still bootstrap — the discovery surface just
        // shows zero connections.
        val absent = Path.of("/tmp/definitely-does-not-exist-${'$'}{System.nanoTime()}.yaml")
        YamlConnectionReferenceLoader(absent, tenant).loadAll() shouldBe emptyList()
    }

    test("malformed YAML surfaces ConnectionReferenceConfigException") {
        val yaml = "this is not\n  a: valid:\n  - mapping"
        val loader = YamlConnectionReferenceLoader(tempYaml(yaml), tenant)
        shouldThrow<ConnectionReferenceConfigException> { loader.loadAll() }
    }

    test("missing required field surfaces ConnectionReferenceConfigException") {
        // displayName is required; a Phase-D record without it is
        // a misconfiguration that must fail loud at bootstrap.
        val yaml = """
            database:
              connections:
                pg:
                  dialectId: postgresql
                  sensitivity: PRODUCTION
        """.trimIndent()
        val loader = YamlConnectionReferenceLoader(tempYaml(yaml), tenant)
        shouldThrow<ConnectionReferenceConfigException> { loader.loadAll() }
    }

    test("unknown sensitivity value surfaces ConnectionReferenceConfigException") {
        val yaml = """
            database:
              connections:
                pg:
                  displayName: PG
                  dialectId: postgresql
                  sensitivity: BOGUS
        """.trimIndent()
        val loader = YamlConnectionReferenceLoader(tempYaml(yaml), tenant)
        shouldThrow<ConnectionReferenceConfigException> { loader.loadAll() }
    }

    test("allowedPrincipalIds entries are wrapped as PrincipalId values") {
        val yaml = """
            database:
              connections:
                pg:
                  displayName: PG
                  dialectId: postgresql
                  sensitivity: NON_PRODUCTION
                  allowedPrincipalIds:
                    - alice
                    - bob
        """.trimIndent()
        val loader = YamlConnectionReferenceLoader(tempYaml(yaml), tenant)
        loader.loadAll().single().allowedPrincipalIds shouldContainExactly
            setOf(PrincipalId("alice"), PrincipalId("bob"))
    }

    test("absent database block returns an empty list") {
        val yaml = """
            other:
              key: value
        """.trimIndent()
        YamlConnectionReferenceLoader(tempYaml(yaml), tenant).loadAll() shouldBe emptyList()
    }

    test("absent connections block under database returns an empty list") {
        val yaml = """
            database:
              default_source: pg
        """.trimIndent()
        YamlConnectionReferenceLoader(tempYaml(yaml), tenant).loadAll() shouldBe emptyList()
    }

    test("non-string field type surfaces ConnectionReferenceConfigException") {
        val yaml = """
            database:
              connections:
                pg:
                  displayName: 12345
                  dialectId: postgresql
                  sensitivity: PRODUCTION
        """.trimIndent()
        val loader = YamlConnectionReferenceLoader(tempYaml(yaml), tenant)
        shouldThrow<ConnectionReferenceConfigException> { loader.loadAll() }
    }

    test("non-list allowedScopes surfaces ConnectionReferenceConfigException") {
        val yaml = """
            database:
              connections:
                pg:
                  displayName: PG
                  dialectId: postgresql
                  sensitivity: PRODUCTION
                  allowedScopes: dmigrate:read
        """.trimIndent()
        val loader = YamlConnectionReferenceLoader(tempYaml(yaml), tenant)
        shouldThrow<ConnectionReferenceConfigException> { loader.loadAll() }
    }
})
