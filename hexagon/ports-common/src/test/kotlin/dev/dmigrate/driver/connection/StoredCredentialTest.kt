package dev.dmigrate.driver.connection

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class StoredCredentialTest : FunSpec({

    test("password() returns a fresh copy, not the internal array") {
        val c = StoredCredential("admin", "s3cr3t".toCharArray())
        val a = c.password()
        val b = c.password()
        (a === b) shouldBe false
        String(a) shouldBe "s3cr3t"
    }

    test("wipe clears the internal secret") {
        val c = StoredCredential("admin", "s3cr3t".toCharArray())
        c.wipe()
        String(c.password()) shouldBe "      " // 6 spaces
    }

    test("toString masks both user and password") {
        val s = StoredCredential("admin", "s3cr3t".toCharArray()).toString()
        s shouldBe "StoredCredential(user=***, password=***)"
        s shouldNotContain "admin"
        s shouldNotContain "s3cr3t"
    }

    test("CredentialStoreException carries a secret-free message and cause") {
        val cause = IllegalStateException("boom")
        val e = CredentialStoreException("Store nicht lesbar", cause)
        e.message shouldBe "Store nicht lesbar"
        (e.cause === cause) shouldBe true
    }
})
