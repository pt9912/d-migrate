package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class MasterSecretResolverTest : FunSpec({

    test("environment variable wins; prompt is not called") {
        var prompted = false
        val r = MasterSecretResolver(
            prompt = { prompted = true; null },
            env = { if (it == MasterSecretResolver.ENV_VAR) "envpw" else null },
            isTty = { true },
        )
        String(r.resolve(isNewStore = true)!!) shouldBe "envpw"
        prompted shouldBe false
    }

    test("no env + TTY → prompt receives the confirm flag") {
        var seenConfirm: Boolean? = null
        val r = MasterSecretResolver(
            prompt = { c -> seenConfirm = c; "typed".toCharArray() },
            env = { null },
            isTty = { true },
        )
        String(r.resolve(isNewStore = true)!!) shouldBe "typed"
        seenConfirm shouldBe true
    }

    test("no env + non-TTY → null (fail-closed)") {
        MasterSecretResolver(prompt = { "x".toCharArray() }, env = { null }, isTty = { false })
            .resolve(isNewStore = false).shouldBeNull()
    }

    test("blank env is treated as unset") {
        val r = MasterSecretResolver(prompt = { "typed".toCharArray() }, env = { "" }, isTty = { true })
        String(r.resolve(isNewStore = false)!!) shouldBe "typed"
    }

    test("confirmedSecret returns the secret on a match and wipes the second copy") {
        val first = "master".toCharArray()
        val second = "master".toCharArray()
        var mismatched = false
        val result = confirmedSecret(first, second) { mismatched = true }
        String(result!!) shouldBe "master"
        mismatched shouldBe false
        String(second) shouldBe "      " // 6 spaces (wiped)
    }

    test("confirmedSecret returns null and signals a mismatch, wiping both copies") {
        val first = "master".toCharArray()
        val second = "typo!!".toCharArray()
        var mismatched = false
        confirmedSecret(first, second) { mismatched = true }.shouldBeNull()
        mismatched shouldBe true
        String(first) shouldBe "      "
        String(second) shouldBe "      "
    }

    test("confirmedSecret returns null without a mismatch when the confirmation is aborted") {
        val first = "master".toCharArray()
        var mismatched = false
        confirmedSecret(first, null) { mismatched = true }.shouldBeNull()
        mismatched shouldBe false
        String(first) shouldBe "      "
    }
})
