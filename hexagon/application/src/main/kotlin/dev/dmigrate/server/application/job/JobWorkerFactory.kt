package dev.dmigrate.server.application.job

import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.ports.JobWorker

/**
 * Phase E §7.7 Auto-Dispatch-Hook: konstruiert pro frisch committed
 * [JobRecord] den passenden [JobWorker]. Der [JobStartOrchestrator] ruft
 * die Factory unmittelbar nach erfolgreichem `JobStartTransaction.commit`,
 * konstruiert den Worker mit den Tool-Args aus dem [JobStartRequest] und
 * uebergibt ihn an den [JobDispatcher].
 *
 * Tool-spezifische Implementierungen schauen ueblicherweise auf
 * `record.managedJob.operation` und konstruieren z.B.
 * [SchemaReverseJobWorker], [DataProfileJobWorker] oder
 * [SchemaCompareJobWorker] mit den passenden Refs aus
 * [JobStartRequest.refs] / [JobStartRequest.payload].
 *
 * Default-Implementierung (siehe [PassthroughJobWorkerFactory]) liefert
 * einen no-op-Worker, der sofort `JobWorkerOutcome.Succeeded()` ohne
 * Artefakte zurueckgibt — proves wiring ohne dass Bootstrap-Schicht
 * Connection-/Schema-/Reader-Adapter-Stacks fertig hat. Production-
 * Wiring ueberschreibt mit operation-spezifischen Konstruktionen.
 *
 * `null`-Return bedeutet "kein Worker fuer diese Operation registriert" —
 * der Orchestrator ueberspringt den Auto-Dispatch dann (Job bleibt
 * QUEUED). Das passiert z.B. fuer Tools, die in dieser Phase noch nicht
 * abbrechbar laufen.
 */
fun interface JobWorkerFactory {

    fun create(record: JobRecord, request: JobStartRequest): JobWorker?
}

/**
 * Default-Factory fuer das MVP-Wiring: konstruiert einen no-op-Worker,
 * der ohne Side-Effects sofort succeeded. Production-Wiring ersetzt
 * sie mit echten operation-spezifischen Workern.
 */
object PassthroughJobWorkerFactory : JobWorkerFactory {
    override fun create(record: JobRecord, request: JobStartRequest): JobWorker =
        JobWorker { _, _ -> dev.dmigrate.server.ports.JobWorkerOutcome.Succeeded() }
}
