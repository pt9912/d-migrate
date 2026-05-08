package dev.dmigrate.server.persistence.jdbc.quota

import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class QuotaJsonTest : FunSpec({

    test("QuotaKey round-trip with all fields") {
        val key = QuotaKey(
            tenantId = TenantId("acme"),
            dimension = QuotaDimension.ACTIVE_JOBS,
            principalId = PrincipalId("alice"),
            operation = "data.export",
        )
        QuotaJson.keyFromText(QuotaJson.keyToText(key)) shouldBe key
    }

    test("QuotaKey round-trip with optional fields null") {
        val key = QuotaKey(
            tenantId = TenantId("acme"),
            dimension = QuotaDimension.ACTIVE_JOBS,
            principalId = null,
            operation = null,
        )
        QuotaJson.keyFromText(QuotaJson.keyToText(key)) shouldBe key
    }

    test("QuotaKey serializes deterministically — identische Eingabe ergibt byte-gleichen Output") {
        val key = QuotaKey(
            tenantId = TenantId("acme"),
            dimension = QuotaDimension.ACTIVE_JOBS,
            principalId = PrincipalId("alice"),
            operation = "data.export",
        )
        QuotaJson.keyToText(key) shouldBe QuotaJson.keyToText(key.copy())
    }

    test("QuotaReservation round-trip") {
        val reservation = QuotaReservation(
            key = QuotaKey(TenantId("acme"), QuotaDimension.ACTIVE_UPLOAD_SESSIONS),
            amount = 7L,
        )
        QuotaJson.reservationFromJson(QuotaJson.reservationToJson(reservation)) shouldBe reservation
    }

    test("QuotaReservation JSON enthaelt amount + key") {
        val reservation = QuotaReservation(
            key = QuotaKey(TenantId("t"), QuotaDimension.UPLOAD_BYTES),
            amount = 42L,
        )
        val json = QuotaJson.reservationToJson(reservation)
        json shouldContain "\"amount\":42"
        json shouldContain "\"dimension\":\"UPLOAD_BYTES\""
    }
})
