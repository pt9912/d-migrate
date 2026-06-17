package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Structural transfer compatibility through the real PostgreSQL type mapping. */
class PostgresDriverTest : FunSpec({

    val compat = PostgresDriver().transferCompatibility()

    test("integer → bigint is compatible (integral widening)") {
        compat.isCompatible(NeutralType.Integer, NeutralType.BigInteger) shouldBe true
    }

    test("timestamptz → timestamp is compatible (tz variance is value-level)") {
        compat.isCompatible(NeutralType.DateTime(timezone = true), NeutralType.DateTime(timezone = false)) shouldBe true
    }

    test("text → integer remains incompatible") {
        compat.isCompatible(NeutralType.Text(), NeutralType.Integer) shouldBe false
    }
})
