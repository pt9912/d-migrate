package dev.dmigrate.driver

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.NeutralType

/**
 * Projects a neutral type onto the neutral type the TARGET dialect's reverse
 * reader yields after the tool's own generate has rendered it — the dialect's
 * storage reality as an idempotent NeutralType → NeutralType projection.
 *
 * Two neutral types are equivalent under this projection exactly when the
 * target dialect flattens them onto the same declared column type (SQLite:
 * `smallint`/`boolean` → INTEGER, `datetime`/`uuid`/… → TEXT). The migrate
 * post-compare fingerprint canonicalises both operands through the target's
 * projection so a lossless-per-dialect round trip no longer reports drift.
 * Types whose fidelity travels OUTSIDE the declared column type (geometry via
 * dialect metadata, fulltext via index objects) stay identity in the driver
 * implementations, keeping their drift sensitivity.
 *
 * Provided by the target [DatabaseDriver] (`typeCanonicalizer()`); the default
 * is the conservative [IDENTITY] so a driver without an explicit flattening
 * declaration never folds types away.
 */
interface NeutralTypeCanonicalizer {
    fun canonicalize(type: NeutralType): NeutralType

    /**
     * Dieselbe Projektion, aber mit den Custom Types des Schemas.
     *
     * Ein `Enum(refType)` traegt seine Werte nicht selbst — sie stehen im
     * Schema. Ein Dialekt, der Enums an der Spalte aufloest (SQL Server: kein
     * Enum-Typ, also `NVARCHAR(<laengster Wert>)` + CHECK), kann den Typ ohne
     * diesen Kontext nicht projizieren und liesse ihn stehen; der Reverse gibt
     * den `refType` aber nie zurueck, und der Post-Compare meldete Drift.
     *
     * Der Default ignoriert den Kontext: fuer Dialekte mit nativem Enum-Typ
     * (PostgreSQL) waere ein Aufloesen sogar falsch — zwei verschiedene Typen
     * mit denselben Werten sind dort verschiedene Typen.
     */
    fun canonicalize(type: NeutralType, customTypes: Map<String, CustomTypeDefinition>): NeutralType =
        canonicalize(type)

    companion object {
        /** No-op canonicaliser: every type is its own canonical form. */
        val IDENTITY: NeutralTypeCanonicalizer = object : NeutralTypeCanonicalizer {
            override fun canonicalize(type: NeutralType): NeutralType = type
        }
    }
}
