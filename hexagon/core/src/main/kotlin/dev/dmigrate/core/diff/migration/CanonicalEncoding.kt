package dev.dmigrate.core.diff.migration

/**
 * Shared canonical-encoding constants for the diff/fingerprint
 * serialisation family. Both [MigrationFingerprint] and
 * [CanonicalPayload] hash strings produced under this contract.
 *
 * Bumping the [VERSION] string is a contract break: existing
 * fingerprints / operation IDs in artefacts become invalid. Pair
 * any change with a `schema-fingerprint-vN+1` algorithm bump.
 */
internal object CanonicalEncoding {

    /** Field-/key separator. ASCII Unit Separator (0x1F). */
    const val SEP: Char = ''

    /** Encoding-format version. Folded into [MigrationFingerprint.ALGORITHM]. */
    const val VERSION: String = "v1"
}
