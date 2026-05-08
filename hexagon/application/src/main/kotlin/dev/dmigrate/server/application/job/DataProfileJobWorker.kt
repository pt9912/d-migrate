package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.server.application.connection.ConnectionMaterializer
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
 * Symmetrisch zu [SchemaReverseJobWorker]: drei Schritte (Materialize
 * → Profile → Publish) mit Cancel-Checkpoints zwischen den Phasen,
 * Token-Propagation in den Profiling-Pfad fuer interne Cancel-Checkpoints,
 * unverpackte Exception-Propagation an den Dispatcher.
 *
 * Eingabe-Beschraenkungen wie `tables`, `schema` oder `topN` sind
 * Tool-Handler-Konfiguration: sie muessen schon vor Worker-Konstruktion
 * normalisiert sein (oder via [runProfile]-Closure gebunden werden).
 * Die Worker-Klasse selbst kennt nur Connection + Lambda + Publisher.
 *
 * Output ist ein [DatabaseProfile]; der [JobArtifactPublisher] kennt
 * den Typ und serialisiert nach JSON gemaess `spec/job-contract.md`
 * (Konvention: Profile-Reports sind text/json — Adapter prueft
 * Payload-Klasse).
 */
class DataProfileJobWorker(
    private val connectionRef: String,
    private val materializer: ConnectionMaterializer,
    private val runProfile: (ConnectionConfig, CancellationToken) -> DatabaseProfile,
    private val publisher: JobArtifactPublisher,
) : JobWorker {

    override fun execute(job: JobRecord, token: CancellationToken): JobWorkerOutcome {
        token.throwIfCancellationRequested()
        val config = materializer.materialize(connectionRef, job.tenantId)

        token.throwIfCancellationRequested()
        val profile = runProfile(config, token)

        token.throwIfCancellationRequested()
        val artifactRef = publisher.publish(job, profile)

        return JobWorkerOutcome.Succeeded(artifactRefs = listOf(artifactRef))
    }
}
