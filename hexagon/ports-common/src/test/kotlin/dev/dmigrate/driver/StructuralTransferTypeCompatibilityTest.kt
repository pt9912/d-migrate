package dev.dmigrate.driver

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies the structural rule with a fake target [TypeMapper] whose [toSql]
 * outputs mimic a dialect — proves "same normalised SQL ⇒ compatible" without
 * depending on a concrete driver. Per-dialect behaviour is covered in the driver
 * modules' own `*DriverTest`s.
 */
class StructuralTransferTypeCompatibilityTest : FunSpec({

    // Fake: emulates a target dialect's toSql so we can drive the structural rule.
    fun mapperOf(map: Map<NeutralType, String>) = object : TypeMapper {
        override val dialect = DatabaseDialect.SQLITE
        override fun toSql(type: NeutralType): String =
            map[type] ?: error("unmapped $type")
        override fun toDefaultSql(default: DefaultValue, type: NeutralType): String = ""
    }

    test("identical types are compatible (fast path, no mapper call)") {
        val compat = StructuralTransferTypeCompatibility(mapperOf(emptyMap()))
        compat.isCompatible(NeutralType.Integer, NeutralType.Integer) shouldBe true
    }

    test("integral storage classes are mutually compatible (widening, no mapper call)") {
        val compat = StructuralTransferTypeCompatibility(mapperOf(emptyMap()))
        compat.isCompatible(NeutralType.Integer, NeutralType.BigInteger) shouldBe true
        compat.isCompatible(NeutralType.SmallInt, NeutralType.Integer) shouldBe true
    }

    test("same target SQL type ⇒ compatible (Decimal/Float → REAL)") {
        val compat = StructuralTransferTypeCompatibility(
            mapperOf(mapOf(NeutralType.Decimal(10, 2) to "REAL", NeutralType.Float() to "REAL")),
        )
        compat.isCompatible(NeutralType.Decimal(10, 2), NeutralType.Float()) shouldBe true
    }

    test("different target SQL type ⇒ incompatible (TEXT vs INTEGER)") {
        val compat = StructuralTransferTypeCompatibility(
            mapperOf(mapOf(NeutralType.Text() to "TEXT", NeutralType.Integer to "INTEGER")),
        )
        compat.isCompatible(NeutralType.Text(), NeutralType.Integer) shouldBe false
    }

    test("length/precision is ignored (VARCHAR(50) vs VARCHAR(100))") {
        val compat = StructuralTransferTypeCompatibility(
            mapperOf(mapOf(NeutralType.Text(50) to "VARCHAR(50)", NeutralType.Text(100) to "VARCHAR(100)")),
        )
        compat.isCompatible(NeutralType.Text(50), NeutralType.Text(100)) shouldBe true
    }

    test("string spellings fold together (VARCHAR vs TEXT vs CHAR)") {
        val compat = StructuralTransferTypeCompatibility(
            mapperOf(mapOf(NeutralType.Text(50) to "VARCHAR(50)", NeutralType.Text() to "TEXT", NeutralType.Char(2) to "CHAR(2)")),
        )
        compat.isCompatible(NeutralType.Text(50), NeutralType.Text()) shouldBe true
        compat.isCompatible(NeutralType.Char(2), NeutralType.Text()) shouldBe true
    }
})
