package dev.dmigrate.server.application.fingerprint

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FingerprintTenantBindTest : FunSpec({

    val service = FingerprintFixtures.service()
    val payload = JsonValue.obj("op" to JsonValue.Str("export"))

    test("different tenants produce different fingerprints") {
        val acme = service.fingerprint(
            FingerprintScope.START_TOOL,
            payload,
            FingerprintFixtures.bind(tenant = "acme"),
        )
        val initech = service.fingerprint(
            FingerprintScope.START_TOOL,
            payload,
            FingerprintFixtures.bind(tenant = "initech"),
        )
        (acme == initech) shouldBe false
    }

    test("different callers produce different fingerprints") {
        val alice = service.fingerprint(
            FingerprintScope.START_TOOL,
            payload,
            FingerprintFixtures.bind(caller = "alice"),
        )
        val bob = service.fingerprint(
            FingerprintScope.START_TOOL,
            payload,
            FingerprintFixtures.bind(caller = "bob"),
        )
        (alice == bob) shouldBe false
    }

    test("different tool names produce different fingerprints") {
        val export = service.fingerprint(
            FingerprintScope.START_TOOL,
            payload,
            FingerprintFixtures.bind(tool = "start.export.data"),
        )
        val import = service.fingerprint(
            FingerprintScope.START_TOOL,
            payload,
            FingerprintFixtures.bind(tool = "start.import.data"),
        )
        (export == import) shouldBe false
    }
})
