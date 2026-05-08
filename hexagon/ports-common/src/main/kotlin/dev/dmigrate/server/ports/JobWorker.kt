package dev.dmigrate.server.ports

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.server.core.job.JobRecord

/**
 * Phase E §7.7 Job-Worker-Port.
 *
 * Eine [JobWorker]-Implementierung verkapselt den fachlichen Runner
 * (Schema-Reverse, Data-Profile, Schema-Compare, …), der einen
 * `QUEUED`-Job tatsaechlich ausfuehrt. Der [dev.dmigrate.server.application.job.JobDispatcher]
 * uebergibt einen committeten [JobRecord] plus einen
 * [CancellationToken], wartet auf das Ergebnis und persistiert den
 * Statuswechsel `QUEUED -> RUNNING -> {SUCCEEDED|FAILED|CANCELLED}`.
 *
 * Worker-Verantwortlichkeiten:
 *
 * - Connection-/Schema-Refs aus dem Job autorisiert materialisieren
 *   (Secret-Resolver). Discovery-Pfade sehen die Refs secret-frei.
 * - Cancel-Token an alle Stufen propagieren (Plan §7.7 +
 *   E0-Checkpoints).
 * - Artefakte ueber [ArtifactStore]/[ArtifactContentStore] selbst
 *   persistieren; in [JobWorkerOutcome.Succeeded.artifactRefs] nur
 *   die wire-shape `dmigrate://...artifacts/...`-URIs liefern.
 * - Bei `OperationCancelledException` aus JOB_CANCEL ist der Worker
 *   verpflichtet, [JobWorkerOutcome.Cancelled] zurueckzugeben (statt
 *   die Exception zu propagieren). Der Dispatcher faengt sie als
 *   Fallback, aber die source-bewusste Klassifikation lebt im Worker
 *   (Plan §7.7 — JOB_CANCEL vs. RUNNER_TIMEOUT).
 */
fun interface JobWorker {

    fun execute(job: JobRecord, token: CancellationToken): JobWorkerOutcome
}

/**
 * Phase E §7.7 Worker-Outcome. Drei Branches, alle auf den
 * Job-Status-Uebergang abgebildet:
 *
 * - [Succeeded] -> `RUNNING -> SUCCEEDED`, [artifactRefs] landen in
 *   `ManagedJob.artifacts`.
 * - [Cancelled] -> `RUNNING -> CANCELLED`, [reason] landet im
 *   `cancelRequest`-Audit-Trail (scrubbed beim Adapter).
 * - [Failed] -> `RUNNING -> FAILED`, ([errorCode], [errorMessage],
 *   [exitCode]) landen in `ManagedJob.error`.
 */
sealed interface JobWorkerOutcome {

    data class Succeeded(
        val artifactRefs: List<String> = emptyList(),
    ) : JobWorkerOutcome

    data class Cancelled(val reason: String) : JobWorkerOutcome

    data class Failed(
        val errorCode: String,
        val errorMessage: String,
        val exitCode: Int? = null,
    ) : JobWorkerOutcome
}
