package dev.dmigrate.cli.commands

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Naming + path-derivation contract for **recovery rollback
 * artefacts** (Plan §F.5.d).
 *
 * When `schema migrate --execute --generate-rollback` succeeds at
 * Up but cannot finalise the user-requested `--rollback-output`
 * (post-introspection failure, atomic-write failure on the primary
 * path), the runner writes a SEPARATE artefact with `recovery=true`
 * and `allowedPostUpFingerprints` instead. The naming convention is
 * fixed by Plan §7.1 (line 1142):
 *
 *     <user --rollback-output>.recovery.<timestamp>.rollback.sql
 *
 * with `<timestamp>` in compact ISO 8601 basic UTC form
 * (`YYYYMMDDTHHMMSSZ`). The recovery file lives in the SAME parent
 * directory as the original `--rollback-output` and **never
 * overwrites** it — the literal append guarantees the new filename
 * differs from the original by at least the `.recovery.<ts>.…`
 * infix even when the user's chosen path already ends in
 * `.rollback.sql`.
 *
 * The contract intentionally does NOT strip any user extension:
 * `--rollback-output=/etc/dm/rollback.sql` becomes
 * `/etc/dm/rollback.sql.recovery.20260510T143045Z.rollback.sql`.
 * Slightly verbose, completely unambiguous, and trivially proves
 * "never touches the original path."
 */
internal object RecoveryArtefactPath {

    private val TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)

    /**
     * Compact ISO 8601 basic UTC timestamp (e.g.
     * `20260510T143045Z`). Filesystem-safe on every OS the project
     * targets (no colons, no spaces) and sortable lexicographically.
     */
    fun timestamp(instant: Instant): String = TIMESTAMP_FORMATTER.format(instant)

    /**
     * Derives the recovery-artefact path from the user's
     * `--rollback-output`. Same parent directory; literal-append
     * naming so the recovery file is provably distinct from the
     * original.
     *
     * Returns the relative form (`./<filename>`) when the input has
     * no parent component, mirroring `Path.resolve` semantics.
     */
    fun recoveryPathFor(rollbackOutput: Path, instant: Instant): Path {
        val parent = rollbackOutput.parent ?: Path.of(".")
        val baseName = rollbackOutput.fileName.toString()
        return parent.resolve("$baseName.recovery.${timestamp(instant)}.rollback.sql")
    }
}
