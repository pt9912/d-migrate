package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TransferTypeCompatibilityTest : FunSpec({

    val compat = TransferTypeCompatibility()
    fun col(type: NeutralType) = ColumnDefinition(type = type)

    test("identical types are compatible") {
        compat.isCompatible(col(NeutralType.Integer), col(NeutralType.Integer)) shouldBe true
    }

    // I-01: vom Tool selbst erzeugte Cross-Dialect-Abbildungen
    test("boolean → integer (SQLite-Mapping) is compatible — I-01") {
        compat.isCompatible(col(NeutralType.BooleanType), col(NeutralType.Integer)) shouldBe true
    }

    test("enum → text (SQLite/PG-Mapping) is compatible — I-01") {
        compat.isCompatible(
            col(NeutralType.Enum(values = listOf("a", "b"))),
            col(NeutralType.Text()),
        ) shouldBe true
    }

    test("timestamptz → datetime (tz mismatch) is compatible — I-01") {
        compat.isCompatible(
            col(NeutralType.DateTime(timezone = true)),
            col(NeutralType.DateTime(timezone = false)),
        ) shouldBe true
    }

    // N3: PG-Named-Enum (values=null, refType=…) ↔ MySQL-Inline-Enum (values=[…])
    test("PG named enum → MySQL inline enum is compatible — N3") {
        compat.isCompatible(
            col(NeutralType.Enum(values = null, refType = "mpaa_rating")),
            col(NeutralType.Enum(values = listOf("G", "PG", "R"), refType = null)),
        ) shouldBe true
    }

    test("datetime/date/time → text (SQLite temporal mapping) is compatible — N3") {
        compat.isCompatible(col(NeutralType.DateTime(timezone = false)), col(NeutralType.Text())) shouldBe true
        compat.isCompatible(col(NeutralType.Date), col(NeutralType.Text())) shouldBe true
        compat.isCompatible(col(NeutralType.Time), col(NeutralType.Text())) shouldBe true
    }

    test("text → integer remains incompatible (no over-permissiveness)") {
        compat.isCompatible(col(NeutralType.Text()), col(NeutralType.Integer)) shouldBe false
    }

    test("integer → boolean remains incompatible (only documented direction added)") {
        compat.isCompatible(col(NeutralType.Integer), col(NeutralType.BooleanType)) shouldBe false
    }
})
