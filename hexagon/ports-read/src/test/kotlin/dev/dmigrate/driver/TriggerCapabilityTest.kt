package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * E.2 Sub-Slice A.2: capability resolution pins.
 *
 * Mirrors the RoutineKindCapability resolver semantics with PG-only
 * floors. File-only targets resolve a declared floor to `Disabled`
 * (conservative); a capability without a floor stays `Active` when
 * enabled.
 */
class TriggerCapabilityTest : FunSpec({

    test("disabled capability always resolves to Disabled") {
        val cap = TriggerCapability(enabled = false, minPostgresMajorVersion = null)
        cap.resolve(postgresMajorVersion = null) shouldBe TriggerCapabilityResolution.Disabled
        cap.resolve(postgresMajorVersion = 16) shouldBe TriggerCapabilityResolution.Disabled
    }

    test("enabled without floor resolves to Active regardless of server version") {
        val cap = TriggerCapability(enabled = true, minPostgresMajorVersion = null)
        cap.resolve(postgresMajorVersion = null) shouldBe TriggerCapabilityResolution.Active
        cap.resolve(postgresMajorVersion = 13) shouldBe TriggerCapabilityResolution.Active
    }

    test("enabled with floor resolves to Disabled when server version is null") {
        val cap = TriggerCapability(enabled = true, minPostgresMajorVersion = 14)
        cap.resolve(postgresMajorVersion = null) shouldBe TriggerCapabilityResolution.Disabled
    }

    test("enabled with floor resolves to Disabled when live server is below the floor") {
        val cap = TriggerCapability(enabled = true, minPostgresMajorVersion = 14)
        cap.resolve(postgresMajorVersion = 13) shouldBe TriggerCapabilityResolution.Disabled
    }

    test("enabled with floor resolves to Active when live server meets the floor") {
        val cap = TriggerCapability(enabled = true, minPostgresMajorVersion = 14)
        cap.resolve(postgresMajorVersion = 14) shouldBe TriggerCapabilityResolution.Active
    }

    test("enabled with floor resolves to Active when live server exceeds the floor") {
        val cap = TriggerCapability(enabled = true, minPostgresMajorVersion = 14)
        cap.resolve(postgresMajorVersion = 16) shouldBe TriggerCapabilityResolution.Active
    }

    test("PostgreSQL default has enabled+floor=14") {
        TriggerCapabilityDefaults.forDialect(DatabaseDialect.POSTGRESQL) shouldBe
            TriggerCapability(enabled = true, minPostgresMajorVersion = 14)
    }

    test("MySQL default is disabled") {
        TriggerCapabilityDefaults.forDialect(DatabaseDialect.MYSQL) shouldBe
            TriggerCapability(enabled = false)
    }

    test("SQLite default is disabled") {
        TriggerCapabilityDefaults.forDialect(DatabaseDialect.SQLITE) shouldBe
            TriggerCapability(enabled = false)
    }
})
