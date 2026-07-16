package dev.dmigrate.cli.commands

import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.NoOpCliAuditRecorder
import dev.dmigrate.driver.connection.CredentialStorePort
import dev.dmigrate.driver.connection.StoredCredential
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.nio.file.Paths

private class FakeStore(var initialized: Boolean = false) : CredentialStorePort {
    val saved = linkedMapOf<String, String>()
    override fun put(name: String, user: String, password: CharArray) {
        saved[name] = "$user:${String(password)}"
        initialized = true
    }
    override fun listNames(): List<String> = saved.keys.sorted()
    override fun resolve(name: String): StoredCredential? = null
    override fun isInitialized(): Boolean = initialized
}

class ConfigCredentialsWiringTest : FunSpec({

    val dummyDir: Path = Paths.get("/tmp/does-not-matter")

    fun resolverReturning(secret: String?) = MasterSecretResolver(
        prompt = { secret?.toCharArray() },
        env = { null },
        isTty = { secret != null },
    )

    test("executeSet stores via the runner and returns 0") {
        val store = FakeStore()
        val exit = ConfigCredentialsWiring.executeSet(
            options = CredentialSetOptions("prod", "admin", configPath = null, baseDir = dummyDir),
            masterSecretResolver = resolverReturning("master"),
            dbPasswordProvider = { "dbpw".toCharArray() },
            stdout = {}, stderr = {},
            recorder = NoOpCliAuditRecorder,
            storeFactory = { _, _ -> store },
        )
        exit shouldBe 0
        store.saved["prod"] shouldBe "admin:dbpw"
    }

    test("executeSet fails closed (exit 7) when no master secret is available") {
        val err = mutableListOf<String>()
        val exit = ConfigCredentialsWiring.executeSet(
            options = CredentialSetOptions("prod", "admin", configPath = null, baseDir = dummyDir),
            masterSecretResolver = resolverReturning(null),
            dbPasswordProvider = { "dbpw".toCharArray() },
            stdout = {}, stderr = err::add,
            recorder = NoOpCliAuditRecorder,
            storeFactory = { _, _ -> FakeStore() },
        )
        exit shouldBe 7
        err.single() shouldContain "no master secret"
    }

    test("executeList on an empty store returns 0 without needing a secret") {
        var prompted = false
        val exit = ConfigCredentialsWiring.executeList(
            options = CredentialListOptions(configPath = null, baseDir = dummyDir),
            masterSecretResolver = MasterSecretResolver(
                prompt = { prompted = true; null }, env = { null }, isTty = { false },
            ),
            stdout = {}, stderr = {},
            storeFactory = { _, _ -> FakeStore(initialized = false) },
        )
        exit shouldBe 0
        prompted shouldBe false
    }

    test("executeList on an initialized store lists names") {
        val store = FakeStore(initialized = true).apply { saved["zeta"] = "x"; saved["alpha"] = "x" }
        val out = mutableListOf<String>()
        val exit = ConfigCredentialsWiring.executeList(
            options = CredentialListOptions(configPath = null, baseDir = dummyDir),
            masterSecretResolver = resolverReturning("master"),
            stdout = out::add, stderr = {},
            storeFactory = { _, _ -> store },
        )
        exit shouldBe 0
        out shouldContainExactly listOf("alpha", "zeta")
    }

    test("executeSet emits a secret-free audit event (op + name, no user/password)") {
        val recorded = mutableListOf<Pair<String, List<String>>>()
        val recorder = object : CliAuditRecorder {
            override fun record(toolName: String, resourceRefs: List<String>, block: () -> Int): Int {
                recorded += toolName to resourceRefs
                return block()
            }
        }
        ConfigCredentialsWiring.executeSet(
            options = CredentialSetOptions("prod", "admin", configPath = null, baseDir = dummyDir),
            masterSecretResolver = resolverReturning("master"),
            dbPasswordProvider = { "dbpw".toCharArray() },
            stdout = {}, stderr = {},
            recorder = recorder,
            storeFactory = { _, _ -> FakeStore() },
        )
        recorded.single() shouldBe ("config.credentials.set" to listOf("prod"))
    }
})
