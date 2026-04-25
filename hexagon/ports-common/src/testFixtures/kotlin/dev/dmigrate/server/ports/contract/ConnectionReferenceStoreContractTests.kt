package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.ports.ConnectionReferenceStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

abstract class ConnectionReferenceStoreContractTests(factory: () -> ConnectionReferenceStore) : FunSpec({

    test("save and findById round-trip") {
        val store = factory()
        val ref = Fixtures.connectionRef("c1")
        store.save(ref)
        store.findById(Fixtures.tenant("acme"), "c1") shouldBe ref
    }

    test("list returns only references for the principal's effective tenant") {
        val store = factory()
        store.save(Fixtures.connectionRef("c_acme", tenant = "acme"))
        store.save(Fixtures.connectionRef("c_other", tenant = "umbrella"))
        val page = store.list(Fixtures.principalContext(tenant = "acme"), PageRequest(pageSize = 10))
        page.items.map { it.connectionId } shouldBe listOf("c_acme")
    }

    test("list excludes references whose allowedPrincipalIds do not include the caller") {
        val store = factory()
        store.save(
            Fixtures.connectionRef(
                "restricted",
                allowedPrincipals = setOf(Fixtures.principal("trusted")),
            ),
        )
        store.save(Fixtures.connectionRef("public"))
        val page = store.list(
            Fixtures.principalContext(principalId = "alice"),
            PageRequest(pageSize = 10),
        )
        page.items.map { it.connectionId } shouldBe listOf("public")
    }

    test("references carry sensitivity but no inline secrets") {
        val store = factory()
        val ref = Fixtures.connectionRef("c1", sensitivity = ConnectionSensitivity.SENSITIVE)
        store.save(ref)
        val loaded = store.findById(Fixtures.tenant("acme"), "c1")!!
        loaded.sensitivity shouldBe ConnectionSensitivity.SENSITIVE
        loaded.credentialRef shouldBe "vault:acme/c1"
    }

    test("delete removes reference") {
        val store = factory()
        store.save(Fixtures.connectionRef("c1"))
        store.delete(Fixtures.tenant("acme"), "c1") shouldBe true
        store.findById(Fixtures.tenant("acme"), "c1") shouldBe null
        store.delete(Fixtures.tenant("acme"), "c1") shouldBe false
    }
})
