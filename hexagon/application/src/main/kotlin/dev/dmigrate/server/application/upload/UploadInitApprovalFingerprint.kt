package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.fingerprint.BindContext
import dev.dmigrate.server.application.fingerprint.FingerprintScope
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.fingerprint.PayloadFingerprintService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

/**
 * Phase F § 4.2 + § 5.1 (F.3 1/4) — typsicherer Wrapper ueber
 * [PayloadFingerprintService] fuer den policy-Init-Fingerprint des
 * `artifact_upload_init`-Tools.
 *
 * Inputs gemaess Plan § 4.2 ("Policy-Freigabe bindet Session-Metadaten,
 * nicht Segmentbytes"):
 *
 * - `artifactKind`, `mimeType`, `sizeBytes`, `checksumSha256`,
 *   `uploadIntent`
 * - optional `targetTable` fuer Single-File-`job_input`
 * - Tenant + Principal via [BindContext]
 *
 * **Nicht** Teil des Fingerprints (Plan § 4.2):
 * `contentBase64`, `segmentSha256`, `segmentIndex`, `segmentOffset`,
 * einzelne Segmentbytes.
 *
 * Verwendet die bestehende `FingerprintScope.UPLOAD_INIT`-Bindung —
 * Session-Metadaten landen im `BindContext.extras` (siehe
 * Plan § 14.6 + Phase-C-Konvention), `payload` bleibt
 * `JsonValue.Obj.EMPTY`.
 */
class UploadInitApprovalFingerprint(
    private val payloadFingerprintService: PayloadFingerprintService,
) {

    fun fingerprint(attempt: UploadInitApprovalAttempt): String {
        val extras = buildMap<String, JsonValue> {
            put("artifactKind", JsonValue.str(attempt.artifactKind.name))
            put("mimeType", JsonValue.str(attempt.mimeType))
            put("sizeBytes", JsonValue.num(attempt.sizeBytes))
            put("checksumSha256", JsonValue.str(attempt.checksumSha256))
            put("uploadIntent", JsonValue.str(attempt.uploadIntent))
            // Plan § 5.1: targetTable nur fuer Single-File-job_input
            // erlaubt; bei Abwesenheit darf der Fingerprint nicht durch
            // einen leeren String "verfaelscht" werden — das Feld wird
            // dann gar nicht in das _bind-Objekt aufgenommen.
            attempt.targetTable?.let { put("targetTable", JsonValue.str(it)) }
        }
        return payloadFingerprintService.fingerprint(
            scope = FingerprintScope.UPLOAD_INIT,
            payload = JsonValue.Obj.EMPTY,
            bind = BindContext(
                tenantId = attempt.tenantId,
                callerId = attempt.callerId,
                toolName = TOOL_NAME,
                extras = extras,
            ),
        )
    }

    companion object {
        const val TOOL_NAME: String = "artifact_upload_init"
    }
}

/**
 * Eingabe fuer [UploadInitApprovalFingerprint]. Felder spiegeln den
 * Plan-§-4.2-Vertrag eins-zu-eins.
 */
data class UploadInitApprovalAttempt(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val artifactKind: ArtifactKind,
    val mimeType: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val uploadIntent: String,
    val targetTable: String? = null,
)
