package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.fingerprint.BindContext
import dev.dmigrate.server.application.fingerprint.FingerprintScope
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.fingerprint.PayloadFingerprintService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSessionState

/**
 * Phase F § 5.3 + § 8.6 (F.6 2/3) — typsicherer Wrapper ueber
 * [PayloadFingerprintService] fuer den Pre-Abort-Approval-
 * Fingerprint des `artifact_upload_abort`-Tools.
 *
 * Der Plan § 5.3 fixiert das Fingerprint-Material wortlaeufig:
 *
 * - Toolname `artifact_upload_abort`
 * - `uploadSessionId`
 * - Session-Tenant
 * - Session-Owner-Principal
 * - Admin-/Caller-Principal
 * - Pre-Abort-Session-Status
 * - `artifactKind`
 * - `uploadIntent`
 * - Pre-Abort reservierte bzw. empfangene Bytes
 * - optionalen `reason`
 *
 * Verwendet die bestehende [FingerprintScope.UPLOAD_INIT]-Bindung —
 * fuer Phase F unterscheidet das `toolName`-Feld in `BindContext` den
 * Init- vom Abort-Fingerprint, sodass kein separater Scope eingefuehrt
 * werden muss (Plan: "keinen separaten Abort-Claim-Key").
 */
class AbortApprovalFingerprint(
    private val payloadFingerprintService: PayloadFingerprintService,
) {

    fun fingerprint(attempt: AbortApprovalAttempt): String {
        val extras = buildMap<String, JsonValue> {
            put("uploadSessionId", JsonValue.str(attempt.uploadSessionId))
            put("sessionTenantId", JsonValue.str(attempt.sessionTenantId.value))
            put("sessionOwnerPrincipalId", JsonValue.str(attempt.sessionOwnerPrincipalId.value))
            put("preAbortState", JsonValue.str(attempt.preAbortState.name))
            put("artifactKind", JsonValue.str(attempt.artifactKind.name))
            put("uploadIntent", JsonValue.str(attempt.uploadIntent))
            put("preAbortBytes", JsonValue.num(attempt.preAbortBytes))
            // Plan § 5.3: optionaler `reason` muss in den Fingerprint —
            // sonst koennte ein zweiter Aufruf mit veraendertem
            // `reason` denselben Outcome zurueckbekommen, was die
            // "abweichende Wiederholung deterministisch ablehnen"-
            // Akzeptanz verletzt. Bei Abwesenheit darf das Feld nicht
            // mit leerem String "verfaelscht" werden.
            attempt.reason?.let { put("reason", JsonValue.str(it)) }
        }
        return payloadFingerprintService.fingerprint(
            scope = FingerprintScope.UPLOAD_INIT,
            payload = JsonValue.Obj.EMPTY,
            bind = BindContext(
                tenantId = attempt.callerTenantId,
                callerId = attempt.callerId,
                toolName = TOOL_NAME,
                extras = extras,
            ),
        )
    }

    companion object {
        const val TOOL_NAME: String = "artifact_upload_abort"
    }
}

/**
 * Eingabe fuer [AbortApprovalFingerprint]. Felder spiegeln den
 * Plan-§-5.3-Vertrag eins-zu-eins.
 *
 * @property callerTenantId Tenant-Scope des Caller-Principals
 *   (kann von [sessionTenantId] abweichen, wenn ein Admin eine
 *   fremde Session in seinem Heim-Tenant freigibt — der Plan
 *   bindet beides separat).
 * @property callerId Admin-/Caller-Principal, der den Abort
 *   anstoesst.
 * @property sessionTenantId Tenant der abzubrechenden Session.
 * @property sessionOwnerPrincipalId urspruenglicher Session-Owner;
 *   bindet den Approval-Grant an genau diesen Pre-Abort-Owner.
 * @property uploadSessionId opaque Session-ID.
 * @property preAbortState Session-Status zum Zeitpunkt des
 *   Approval-Grants (Plan: ACTIVE, FINALIZING, etc.).
 * @property artifactKind, [uploadIntent] Session-Vertragsfelder.
 * @property preAbortBytes Pre-Abort reservierte / empfangene Bytes;
 *   verhindert, dass ein Approval-Grant aus einer leeren Session
 *   gegen eine spaeter "groessere" Session repurposed wird.
 * @property reason optionaler Caller-Grund.
 */
data class AbortApprovalAttempt(
    val callerTenantId: TenantId,
    val callerId: PrincipalId,
    val sessionTenantId: TenantId,
    val sessionOwnerPrincipalId: PrincipalId,
    val uploadSessionId: String,
    val preAbortState: UploadSessionState,
    val artifactKind: ArtifactKind,
    val uploadIntent: String,
    val preAbortBytes: Long,
    val reason: String? = null,
)
