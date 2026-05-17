package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * E.1 Routine-Migration Slice C.1.a + 0.9.7
 * routine-capability-configurable-source Sub-Slice A pins for
 * [RoutineKindCapability.resolve], [RoutineCapabilityDefaults],
 * [EffectiveRoutineCapability], and [RoutineBodyDisplay]. The
 * Sub-Slice A carve-out wrapped the earlier top-level
 * `RoutineCapability` data class in the sealed
 * [EffectiveRoutineCapability] envelope; defaults still return
 * [EffectiveRoutineCapability.Valid].
 */
class RoutineCapabilityTest : FunSpec({

    test("Resolution: enabled + no minServerVersion -> Active regardless of live version") {
        val cap = RoutineKindCapability(enabled = true, minServerVersion = null)
        cap.resolve(serverVersion = null) shouldBe RoutineCapabilityResolution.Active
        cap.resolve(MysqlServerVersion(8, 0, 36)) shouldBe RoutineCapabilityResolution.Active
    }

    test("Resolution: disabled -> Disabled regardless of version") {
        val cap = RoutineKindCapability(enabled = false, minServerVersion = null)
        cap.resolve(serverVersion = null) shouldBe RoutineCapabilityResolution.Disabled
        cap.resolve(MysqlServerVersion(8, 0, 36)) shouldBe RoutineCapabilityResolution.Disabled
    }

    test("Resolution: enabled + minServerVersion declared + null live version -> Disabled (no proof)") {
        val cap = RoutineKindCapability(enabled = true, minServerVersion = MysqlServerVersion(8, 0, 0))
        cap.resolve(serverVersion = null) shouldBe RoutineCapabilityResolution.Disabled
    }

    test("Resolution: live version >= minServerVersion -> Active") {
        val cap = RoutineKindCapability(enabled = true, minServerVersion = MysqlServerVersion(5, 7, 0))
        cap.resolve(MysqlServerVersion(8, 0, 36)) shouldBe RoutineCapabilityResolution.Active
        cap.resolve(MysqlServerVersion(5, 7, 0)) shouldBe RoutineCapabilityResolution.Active
    }

    test("Resolution: live version < minServerVersion -> Disabled") {
        val cap = RoutineKindCapability(enabled = true, minServerVersion = MysqlServerVersion(8, 0, 0))
        cap.resolve(MysqlServerVersion(5, 7, 44)) shouldBe RoutineCapabilityResolution.Disabled
    }

    test("EffectiveRoutineCapability.Invalid carries a reason for the renderer manifest") {
        val invalid = EffectiveRoutineCapability.Invalid(reason = "unparsable minServerVersion=not-a-version")
        invalid.reason shouldBe "unparsable minServerVersion=not-a-version"
    }

    test("Defaults for PostgreSQL: function + procedure are both enabled with no min version") {
        val cap = RoutineCapabilityDefaults.forDialect(DatabaseDialect.POSTGRESQL)
        cap.function shouldBe RoutineKindCapability(enabled = true, minServerVersion = null)
        cap.procedure shouldBe RoutineKindCapability(enabled = true, minServerVersion = null)
    }

    test("Defaults for MySQL are conservative Oracle MySQL semantics") {
        val cap = RoutineCapabilityDefaults.forDialect(DatabaseDialect.MYSQL)
        cap.function shouldBe RoutineKindCapability(enabled = false, minServerVersion = null)
        cap.procedure shouldBe RoutineKindCapability(enabled = false, minServerVersion = null)
    }

    test("MariaDB live server version enables routine CREATE OR REPLACE") {
        val cap = RoutineCapabilityDefaults.forMysqlServerVersion(MysqlServerVersion(10, 11, 6, "MariaDB"))
        cap.function shouldBe RoutineKindCapability(enabled = true, minServerVersion = null)
        cap.procedure shouldBe RoutineKindCapability(enabled = true, minServerVersion = null)
    }

    test("Oracle MySQL live server version keeps routine CREATE OR REPLACE disabled") {
        val cap = RoutineCapabilityDefaults.forMysqlServerVersion(MysqlServerVersion(8, 4, 0, "log"))
        cap.function shouldBe RoutineKindCapability(enabled = false, minServerVersion = null)
        cap.procedure shouldBe RoutineKindCapability(enabled = false, minServerVersion = null)
    }

    test("Defaults for SQLite: function + procedure disabled (no routine path)") {
        val cap = RoutineCapabilityDefaults.forDialect(DatabaseDialect.SQLITE)
        cap.function.enabled shouldBe false
        cap.procedure.enabled shouldBe false
    }

    test("Valid.forKind dispatches to function vs procedure slot") {
        val cap = EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true),
            procedure = RoutineKindCapability(enabled = false),
        )
        cap.forKind(RoutineKind.FUNCTION).enabled shouldBe true
        cap.forKind(RoutineKind.PROCEDURE).enabled shouldBe false
    }

    test("RoutineBodyDisplay enum has exactly the two documented values") {
        RoutineBodyDisplay.entries.map { it.name } shouldBe listOf("SCRUBBED_ONLY", "RAW_DEBUG")
    }
})
