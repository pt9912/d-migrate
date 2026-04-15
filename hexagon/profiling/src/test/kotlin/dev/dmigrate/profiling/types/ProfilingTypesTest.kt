package dev.dmigrate.profiling.types

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProfilingTypesTest : FunSpec({

    test("LogicalType has 10 values") {
        LogicalType.entries.size shouldBe 10
    }

    test("LogicalType includes UNKNOWN") {
        LogicalType.valueOf("UNKNOWN") shouldBe LogicalType.UNKNOWN
    }

    test("TargetLogicalType has 6 values") {
        TargetLogicalType.entries.size shouldBe 6
    }

    test("Severity has 3 levels") {
        Severity.entries.size shouldBe 3
    }

    test("WarningCode has 8 codes") {
        WarningCode.entries.size shouldBe 8
    }

    test("DeterminationStatus distinguishes FULL_SCAN from UNKNOWN") {
        dev.dmigrate.profiling.model.DeterminationStatus.FULL_SCAN shouldBe
            dev.dmigrate.profiling.model.DeterminationStatus.FULL_SCAN
        (dev.dmigrate.profiling.model.DeterminationStatus.FULL_SCAN !=
            dev.dmigrate.profiling.model.DeterminationStatus.UNKNOWN) shouldBe true
    }
})
