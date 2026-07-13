package dev.dmigrate.cli.commands

import dev.dmigrate.driver.connection.CredentialStoreException
import dev.dmigrate.driver.connection.CredentialStorePort
import dev.dmigrate.driver.connection.StoredCredential
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private class FakeStore(var fail: Boolean = false) : CredentialStorePort {
    val saved = linkedMapOf<String, String>()
    override fun put(name: String, user: String, password: CharArray) {
        if (fail) throw CredentialStoreException("boom")
        saved[name] = "$user:${String(password)}"
    }
    override fun listNames(): List<String> {
        if (fail) throw CredentialStoreException("boom")
        return saved.keys.sorted()
    }
    override fun resolve(name: String): StoredCredential? = null
    override fun isInitialized(): Boolean = saved.isNotEmpty()
}

class CredentialCommandRunnerTest : FunSpec({

    test("set stores and returns 0 with a confirmation on stderr") {
        val store = FakeStore()
        val err = mutableListOf<String>()
        val exit = CredentialCommandRunner(store, {}, err::add).set("prod", "admin", "pw".toCharArray())
        exit shouldBe 0
        store.saved["prod"] shouldBe "admin:pw"
        err.single() shouldBe "Stored credentials for 'prod'."
    }

    test("set maps a store error to exit 7") {
        val err = mutableListOf<String>()
        val exit = CredentialCommandRunner(FakeStore(fail = true), {}, err::add).set("p", "u", "pw".toCharArray())
        exit shouldBe 7
        err.single() shouldBe "Error: boom"
    }

    test("set wipes the password array") {
        val pw = "secret".toCharArray()
        CredentialCommandRunner(FakeStore(), {}, {}).set("p", "u", pw)
        String(pw) shouldBe "      "
    }

    test("list prints sorted names and returns 0") {
        val store = FakeStore().apply { saved["zeta"] = "x"; saved["alpha"] = "x" }
        val out = mutableListOf<String>()
        val exit = CredentialCommandRunner(store, out::add, {}).list()
        exit shouldBe 0
        out shouldContainExactly listOf("alpha", "zeta")
    }

    test("list maps a store error to exit 7") {
        val err = mutableListOf<String>()
        CredentialCommandRunner(FakeStore(fail = true), {}, err::add).list() shouldBe 7
        err.single() shouldBe "Error: boom"
    }
})
